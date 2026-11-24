package io.github.patschgoo.anibox.data

import io.github.patschgoo.anibox.net.Credentials
import io.github.patschgoo.anibox.net.Problem
import io.github.patschgoo.anibox.net.ShareAccess
import io.github.patschgoo.anibox.net.SignIn
import io.github.patschgoo.anibox.net.SmbConnection
import io.github.patschgoo.anibox.net.SmbProblem
import io.github.patschgoo.anibox.net.SmbSyncFolder
import io.github.patschgoo.anibox.net.SmbTarget
import io.github.patschgoo.anibox.sync.Library
import io.github.patschgoo.anibox.sync.LibraryParser
import io.github.patschgoo.anibox.sync.LibraryResult
import io.github.patschgoo.anibox.sync.ProgressStore
import io.github.patschgoo.anibox.sync.Seen
import io.github.patschgoo.anibox.sync.SyncEngine
import io.github.patschgoo.anibox.sync.SyncTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** How the last sync went. */
sealed class SyncStatus {
    data object Never : SyncStatus()
    data object Running : SyncStatus()
    data class Done(val time: Long, val shows: Int, val taken: Int, val sent: Int) : SyncStatus()

    /** library.json is newer than this app. */
    data class TooNew(val format: Int) : SyncStatus()
    data class Failed(val problem: Problem, val message: String, val time: Long) : SyncStatus()
}

/**
 * The library and the progress journals: reads library.json and the journals from the sync folder,
 * keeps a copy of library.json in the app's storage (so the library opens at once and without the
 * Shield), sends this phone's journal lines, and fetches posters into a disk cache.
 */
class SyncRepository(
    private val dir: File,
    private val settings: AppSettings,
    private val credentials: Credentials,
    val store: ProgressStore,
    val shares: ShareAccess,
    val journalName: String,
) {
    private val engine = SyncEngine(store, journalName)
    private val mutex = Mutex()
    private val posterDownloads = Semaphore(3)
    /** Posters that couldn't be fetched (the Shield is off): not asked for again until the next good sync. */
    private val posterMisses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var lastGood = 0L

    /** When the last sync worked (milliseconds), or 0. */
    val lastSuccess: Long get() = lastGood
    private val _library = MutableStateFlow<Library?>(null)
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Never)

    val library: StateFlow<Library?> = _library.asStateFlow()
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val libraryCopy get() = File(dir, "library.json")
    private val stateFile get() = File(dir, "sync-state.json")
    val posterDir: File get() = File(dir, "posters").also { it.mkdirs() }

    init {
        loadCopy()
    }

    private fun loadCopy() {
        val bytes = try { libraryCopy.takeIf { it.isFile }?.readBytes() } catch (_: IOException) { null } ?: return
        (LibraryParser.parse(String(bytes, Charsets.UTF_8)) as? LibraryResult.Ok)?.let { _library.value = it.library }
    }

    /** The PC's address for [host] (QR code or library.json), if it gave one. */
    fun hint(host: String): String? {
        settings.target?.takeIf { it.host.equals(host, ignoreCase = true) }?.address?.ifEmpty { null }?.let { return it }
        return _library.value?.shares?.firstOrNull { it.host.equals(host, ignoreCase = true) && it.address.isNotEmpty() }?.address
    }

    private fun folder(t: SyncTarget): SmbSyncFolder = SmbSyncFolder(shares.connection(t.host, t.share, hint(t.host)), t.path)

    /** Read everything once: library.json, the journals; send this phone's lines. */
    suspend fun sync(): SyncStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            val t = settings.target ?: return@withLock SyncStatus.Never
            _status.value = SyncStatus.Running
            val result = try {
                runSync(t)
            } catch (e: SmbProblem) {
                if (e.problem != Problem.WRONG_SIGN_IN) shares.reset(t.host)
                SyncStatus.Failed(e.problem, e.message ?: "The sync folder couldn't be read.", System.currentTimeMillis())
            } catch (e: IOException) {
                shares.reset(t.host)
                SyncStatus.Failed(Problem.OTHER, e.message ?: "The sync folder couldn't be read.", System.currentTimeMillis())
            } catch (e: RuntimeException) {
                shares.reset(t.host)
                SyncStatus.Failed(Problem.OTHER, e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
            }
            store.save()
            _status.value = result
            result
        }
    }

    private fun runSync(t: SyncTarget): SyncStatus {
        val f = folder(t)
        if (!f.exists()) throw SmbProblem(Problem.NOT_FOUND, "The folder ${t.path} isn't on “${t.share}”. Check the folder in AniBox on the PC (Settings → Phone app).")
        val bytes = engine.readLibrary(f)
            ?: throw SmbProblem(Problem.NOT_FOUND, "The sync folder has no library.json yet. On the PC: AniBox → Settings → Phone app → Write now.")
        val lib = when (val r = LibraryParser.parse(String(bytes, Charsets.UTF_8))) {
            is LibraryResult.TooNew -> return SyncStatus.TooNew(r.format)
            is LibraryResult.Broken -> throw SmbProblem(Problem.OTHER, r.reason)
            is LibraryResult.Ok -> r.library
        }
        ProgressStore.writeAtomic(libraryCopy, bytes)
        _library.value = lib
        prunePosters(lib)
        val (dirKey, seen) = loadSeen()
        val seenNow = if (dirKey == t.display) seen else HashMap()
        val report = engine.importProgress(f, seenNow)
        saveSeen(t.display, seenNow)
        val sent = engine.flush(f)
        posterMisses.clear()
        lastGood = System.currentTimeMillis()
        return SyncStatus.Done(lastGood, lib.shows.size, report.taken, sent)
    }

    /** Only this phone's journal lines (while playing, about once a minute, and when the player stops). Quiet on failure. */
    suspend fun flush(): Boolean = withContext(Dispatchers.IO) {
        if (store.pending().isEmpty()) return@withContext true
        val t = settings.target ?: return@withContext false
        mutex.withLock {
            try {
                engine.flush(folder(t))
                true
            } catch (_: IOException) {
                shares.reset(t.host)
                false
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    /** A first look at a sync folder with a sign-in, before anything is saved (the setup). */
    suspend fun test(t: SyncTarget, signIn: SignIn): LibraryResult = withContext(Dispatchers.IO) {
        val address = shares.address(t.host, t.address.ifEmpty { null }).address
        SmbConnection(SmbTarget(t.host, address, t.share, signIn)).use { smb ->
            val f = SmbSyncFolder(smb, t.path)
            if (!f.exists()) {
                throw SmbProblem(Problem.NOT_FOUND, "There's no folder ${t.path} on “${t.share}”. Check the folder in AniBox on the PC (Settings → Phone app).")
            }
            val bytes = f.read(SyncEngine.LIBRARY)
                ?: throw SmbProblem(Problem.NOT_FOUND, "The folder is there but has no library.json yet. On the PC: AniBox → Settings → Phone app → Write now.")
            LibraryParser.parse(String(bytes, Charsets.UTF_8))
        }
    }

    /** Saves the setup after a good [test]: the folder and the host's sign-in. */
    fun connect(t: SyncTarget, signIn: SignIn): Boolean {
        val old = settings.target
        if (old != null && old.display != t.display) clearSeen()
        settings.target = t
        val ok = credentials.setHost(t.host, signIn)
        shares.reset()
        return ok
    }

    // -- posters -------------------------------------------------------------------------------------------

    /** The poster's file in the cache, fetched from `posters/<name>` when it isn't there yet. */
    suspend fun poster(name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.startsWith(".")) return null
        val file = File(posterDir, name)
        if (file.isFile && file.length() > 0) return file
        if (name in posterMisses) return null
        val t = settings.target ?: return null
        return posterDownloads.withPermit {
            withContext(Dispatchers.IO) {
                if (file.isFile && file.length() > 0) return@withContext file
                if (name in posterMisses) return@withContext null
                val got = try {
                    folder(t).read("${SyncEngine.POSTERS}/$name")?.let { data -> if (ProgressStore.writeAtomic(file, data)) file else null }
                } catch (_: IOException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                if (got == null) posterMisses += name
                got
            }
        }
    }

    private fun prunePosters(lib: Library) {
        val keep = lib.shows.map { it.poster }.filter { it.isNotEmpty() }.toSet()
        posterDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    // -- how far the other devices' journals were read ---------------------------------------------------------

    private fun loadSeen(): Pair<String, HashMap<String, Seen>> {
        val out = HashMap<String, Seen>()
        val text = try { stateFile.takeIf { it.isFile }?.readText() } catch (_: IOException) { null } ?: return "" to out
        return try {
            val o = JSONObject(text)
            val files = o.optJSONObject("seen") ?: JSONObject()
            for (k in files.keys()) {
                val e = files.optJSONObject(k) ?: continue
                out[k] = Seen(e.optLong("offset", 0), e.optString("head", ""))
            }
            o.optString("dir", "") to out
        } catch (_: JSONException) {
            "" to out
        }
    }

    private fun saveSeen(dirKey: String, seen: Map<String, Seen>) {
        val files = JSONObject()
        seen.forEach { (k, v) -> files.put(k, JSONObject().put("offset", v.offset).put("head", v.head)) }
        ProgressStore.writeAtomic(stateFile, JSONObject().put("dir", dirKey).put("seen", files).toString().toByteArray())
    }

    private fun clearSeen() {
        stateFile.delete()
    }
}
