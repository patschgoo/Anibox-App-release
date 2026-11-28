package io.github.patschgoo.anibox.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** This phone's own record of an episode (profile "main"). */
data class LocalProgress(
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val watched: Boolean = false,
    /** Unix seconds, kept to the millisecond the journal line carries. */
    val updated: Double = 0.0,
    /** You took the watched mark away on this phone: an older mark from elsewhere doesn't bring it back. */
    val unmarkedByHand: Boolean = false,
) {
    /** [unmarked]: this line is the un-mark itself (only that line says so, not later plays). */
    fun line(key: String, unmarked: Boolean = false) = ProgressLine(key, position, duration, watched, updated, ProgressLine.MAIN, unmarked = unmarked && !watched)
}

/**
 * Watch progress on this phone: one entry per episode key, saved in the app's own storage, plus the
 * queue of journal lines that still have to go to `progress/<device>.jsonl` in the sync folder.
 *
 * Rules (the same spirit as the PC's `Store.apply_newer_progress`):
 * - progress from the PC or another phone is taken only when it's newer (by `updated`);
 * - syncing never takes a watched mark away; only your own "Mark as not watched" here does;
 * - an older watched mark from elsewhere still marks an episode you haven't un-marked by hand.
 */
class ProgressStore(private val dir: File, private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private val lock = Any()
    private val entries = HashMap<String, LocalProgress>()
    private val _state = MutableStateFlow<Map<String, LocalProgress>>(emptyMap())
    private var dirty = false

    /** Every episode's progress; a new map after each change (for the screens). */
    val state: StateFlow<Map<String, LocalProgress>> = _state.asStateFlow()

    private val file get() = File(dir, FILE)
    private val queueFile get() = File(dir, QUEUE)

    init {
        dir.mkdirs()
        load()
    }

    fun get(key: String): LocalProgress? = synchronized(lock) { entries[key] }

    fun all(): Map<String, LocalProgress> = synchronized(lock) { HashMap(entries) }

    fun now(): Double = PyJson.rounded(clock(), 3)

    // -- changes made on this phone (each one goes into the journal) ------------------------------------

    /**
     * Where you are in an episode (the player calls this). Watched from 95 % on; below that the mark
     * stays as it was (watching a watched episode again keeps it watched, like on the PC). [journal]:
     * also queue a journal line (the player does that at most every 30 s, and on pause/stop).
     */
    fun savePosition(key: String, position: Double, duration: Double, journal: Boolean, watchedAt: Double = WATCHED_AT): LocalProgress {
        synchronized(lock) {
            val old = entries[key]
            val reached = duration > 0 && position >= duration * watchedAt
            val dur = if (duration > 0) duration else old?.duration ?: 0.0
            val e = LocalProgress(
                position = maxOf(0.0, position),
                duration = dur,
                watched = reached || (old?.watched ?: false),
                updated = now(),
                unmarkedByHand = if (reached) false else old?.unmarkedByHand ?: false,
            )
            put(key, e, journal)
            return e
        }
    }

    /** Played to the end: watched, and the next time it starts from the beginning (the PC saves position 0 too). */
    fun finished(key: String, duration: Double): LocalProgress = synchronized(lock) {
        val old = entries[key]
        val e = LocalProgress(0.0, if (duration > 0) duration else old?.duration ?: 0.0, true, now(), false)
        put(key, e, true)
        e
    }

    /** "Mark as watched" / "Mark as not watched" by your own hand (the place goes back to the start, like on the PC). */
    fun setWatched(keys: Collection<String>, watched: Boolean) = synchronized(lock) {
        val t = now()
        for (key in keys) {
            val old = entries[key]
            put(key, LocalProgress(0.0, old?.duration ?: 0.0, watched, t, unmarkedByHand = !watched), true, unmarkLine = !watched)
        }
    }

    private fun put(key: String, e: LocalProgress, journal: Boolean, unmarkLine: Boolean = false) {
        entries[key] = e
        dirty = true
        if (journal) queueLine(e.line(key, unmarkLine))
        publish()
    }

    // -- progress from the PC and other phones ------------------------------------------------------------

    /** Takes what's newer; returns how many episodes changed. Nothing here goes into this phone's journal. */
    fun merge(lines: List<ProgressLine>): Int = synchronized(lock) {
        var changed = 0
        for (r in lines) {
            val l = entries[r.key]
            val next = mergeOne(l, r) ?: continue
            if (next != l) {
                entries[r.key] = next
                changed++
            }
        }
        if (changed > 0) {
            dirty = true
            publish()
        }
        changed
    }

    private fun publish() {
        _state.value = HashMap(entries)
    }

    // -- the queue of journal lines --------------------------------------------------------------------------

    private fun queueLine(line: ProgressLine) {
        try {
            FileOutputStream(queueFile, true).use { it.write(JournalFormat.encodeLine(line)) }
        } catch (_: IOException) {
            // The app's own storage is full: the place is still kept in progress.json.
        }
    }

    /** The journal lines that haven't reached the sync folder yet (complete lines only). */
    fun pending(): ByteArray = synchronized(lock) {
        val data = try { queueFile.takeIf { it.isFile }?.readBytes() } catch (_: IOException) { null } ?: return ByteArray(0)
        val end = data.lastIndexOf('\n'.code.toByte()) + 1
        if (end == data.size) data else data.copyOf(end)
    }

    fun pendingCount(): Int = pending().count { it == '\n'.code.toByte() }

    /** The first [bytes] of [pending] are in the sync folder now. Lines queued meanwhile stay. */
    fun sent(bytes: Int) {
        synchronized(lock) {
            if (bytes <= 0) return
            val data = try { queueFile.readBytes() } catch (_: IOException) { return }
            val rest = if (bytes >= data.size) ByteArray(0) else data.copyOfRange(bytes, data.size)
            writeAtomic(queueFile, rest)
        }
    }

    // -- storage ---------------------------------------------------------------------------------------------

    /** Writes progress.json if something changed since the last time. */
    fun save(): Unit = synchronized(lock) {
        if (!dirty) return
        val o = JSONObject()
        for ((k, e) in entries) {
            o.put(k, JSONObject().apply {
                put("p", e.position)
                put("d", e.duration)
                put("w", e.watched)
                put("u", e.updated)
                if (e.unmarkedByHand) put("h", true)
            })
        }
        val root = JSONObject().put("v", 1).put("entries", o)
        if (writeAtomic(file, root.toString().toByteArray(Charsets.UTF_8))) dirty = false
    }

    private fun load() {
        val text = try { file.takeIf { it.isFile }?.readText(Charsets.UTF_8) } catch (_: IOException) { null } ?: return
        try {
            val o = JSONObject(text).obj("entries") ?: return
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                entries[k] = LocalProgress(e.num("p") ?: 0.0, e.num("d") ?: 0.0, e.bool("w"), e.num("u") ?: 0.0, e.bool("h"))
            }
        } catch (_: JSONException) {
            file.renameTo(File(dir, "$FILE.damaged"))
        }
        publish()
    }

    companion object {
        const val FILE = "progress.json"
        const val QUEUE = "journal-queue.jsonl"
        const val WATCHED_AT = 0.95

        /**
         * One remote line against the local entry: the new local entry, or null to keep it.
         * Newer wins; a watched mark is never removed by syncing; an older watched mark still marks an
         * episode this phone has but hasn't un-marked by hand.
         */
        fun mergeOne(local: LocalProgress?, remote: ProgressLine): LocalProgress? {
            if (local == null) return LocalProgress(remote.position, remote.duration, remote.watched, remote.updated, unmarkedByHand = remote.unmarked)
            if (remote.updated > local.updated) {
                // A newer "un-marked by hand" (on the PC or another phone) takes the mark away; nothing else does.
                val watched = if (remote.unmarked) false else remote.watched || local.watched
                return LocalProgress(
                    position = remote.position,
                    duration = if (remote.duration > 0) remote.duration else local.duration,
                    watched = watched,
                    updated = remote.updated,
                    unmarkedByHand = when {
                        remote.unmarked -> true
                        watched -> false
                        else -> local.unmarkedByHand
                    },
                )
            }
            if (remote.watched && !local.watched && !local.unmarkedByHand) return local.copy(watched = true)
            return null
        }

        internal fun writeAtomic(target: File, data: ByteArray): Boolean {
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            return try {
                FileOutputStream(tmp).use {
                    it.write(data)
                    it.fd.sync()
                }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                } else {
                    true
                }
            } catch (_: IOException) {
                tmp.delete()
                false
            }
        }
    }
}
