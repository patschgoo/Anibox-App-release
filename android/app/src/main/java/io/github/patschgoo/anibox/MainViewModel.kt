package io.github.patschgoo.anibox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.patschgoo.anibox.data.SyncStatus
import io.github.patschgoo.anibox.net.Found
import io.github.patschgoo.anibox.net.Problem
import io.github.patschgoo.anibox.net.SignIn
import io.github.patschgoo.anibox.net.SmbProblem
import io.github.patschgoo.anibox.sync.LibraryResult
import io.github.patschgoo.anibox.sync.QrCode
import io.github.patschgoo.anibox.sync.QrResult
import io.github.patschgoo.anibox.sync.SyncTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.github.patschgoo.anibox.debug.DebugLog
import io.github.patschgoo.anibox.net.LocalDrives
import io.github.patschgoo.anibox.data.Downloads
import io.github.patschgoo.anibox.net.Use
import io.github.patschgoo.anibox.player.Sidecars
import io.github.patschgoo.anibox.sync.Episode
import java.io.IOException

/** The setup's steps: how to get the folder, then the sign-in. */
enum class SetupStep { CHOOSE, SCAN, PASTE, TYPE, SIGN_IN }

data class SetupState(
    val step: SetupStep = SetupStep.CHOOSE,
    val target: SyncTarget? = null,
    val user: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val problem: Problem? = null,
    /** The host wasn't found: the sign-in step also asks for its address. */
    val askAddress: Boolean = false,
    /** Coming from Settings → "Sign in again" / "Change the folder" (Back returns to the app). */
    val fromSettings: Boolean = false,
    /** A drive is plugged into this device (the Shield): offer "Use this device's own drives". */
    val localDrives: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val graph = app.graph
    val library = graph.sync.library
    val progress = graph.store.state
    val status = graph.sync.status
    val settingsVersion = graph.settings.version

    private val _setup = MutableStateFlow(SetupState(localDrives = LocalDrives.volumes().isNotEmpty()))
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    private val _setupOpen = MutableStateFlow(graph.settings.target == null)

    /** True while the setup is shown (no folder yet, or opened again from Settings). */
    val setupOpen: StateFlow<Boolean> = _setupOpen.asStateFlow()

    private var syncJob: Job? = null

    init {
        // Lines that couldn't be sent wait in the queue: try again now and then.
        viewModelScope.launch {
            while (isActive) {
                delay(120_000)
                if (graph.store.pending().isNotEmpty()) graph.sync.flush()
            }
        }
    }

    fun setProfile(id: String) {
        graph.sync.switchProfile(id)
        syncNow()
    }

    fun syncNow() {
        if (syncJob?.isActive == true || graph.settings.target == null) return
        syncJob = viewModelScope.launch { graph.sync.sync() }
    }

    /** The app came to the front: sync again if the last good sync is more than a minute old. */
    fun syncIfStale() {
        if (System.currentTimeMillis() - graph.sync.lastSuccess > 60_000) syncNow()
    }

    fun setWatched(keys: Collection<String>, watched: Boolean) {
        graph.store.setWatched(keys, watched)
        viewModelScope.launch {
            graph.store.save()
            graph.sync.flush()
        }
    }

    // -- downloads ----------------------------------------------------------------------------------------

    val downloads = graph.downloads.states

    /** Keeps [e] on this phone (the video and its subtitle files), one download after the other. */
    fun download(e: Episode) {
        val lib = graph.sync.library.value ?: return
        graph.downloads.start({
            val share = lib.share(e.share) ?: return@start null
            val c = graph.shares.connection(share.host, share.share, share.address.ifEmpty { graph.sync.hint(share.host) }, Use.PLAYER)
            val folder = e.path.substringBeforeLast('/', "")
            val names = try { c.list(folder).filter { !it.folder }.map { it.name } } catch (_: IOException) { emptyList() }
            val langs = graph.settings.effectiveLanguages(lib.prefs).subs
            val subs = Sidecars.find(e.fileName, names, folder, langs).map { it.name to c.mediaSource(it.path) }
            Downloads.Request(e.key, e.fileName to c.mediaSource(e.path), subs)
        }, e.key)
    }

    fun removeDownload(key: String) = graph.downloads.remove(key)

    // -- setup ------------------------------------------------------------------------------------------

    fun openSetup(signInOnly: Boolean) {
        val t = graph.settings.target
        _setup.value = if (signInOnly && t != null) {
            SetupState(step = SetupStep.SIGN_IN, target = t, user = graph.credentials.forHost(t.host)?.user ?: "", fromSettings = true)
        } else {
            SetupState(fromSettings = t != null)
        }.copy(localDrives = LocalDrives.volumes().isNotEmpty())
        _setupOpen.value = true
    }

    fun closeSetup() {
        if (graph.settings.target != null) _setupOpen.value = false
    }

    fun setupStep(step: SetupStep) {
        _setup.value = _setup.value.copy(step = step, error = null, problem = null)
    }

    /** Back inside the setup: true when it was handled. */
    fun setupBack(): Boolean {
        val s = _setup.value
        if (s.busy) return true
        return when (s.step) {
            SetupStep.CHOOSE -> if (s.fromSettings) { closeSetup(); true } else false
            SetupStep.SIGN_IN -> if (s.fromSettings && s.target == graph.settings.target) { closeSetup(); true } else { setupStep(SetupStep.CHOOSE); true }
            else -> { setupStep(SetupStep.CHOOSE); true }
        }
    }

    /** The text of the QR code (scanned or pasted). Returns an error message, or null when it was taken. */
    fun useQrText(text: String): String? = when (val r = QrCode.parse(text)) {
        is QrResult.Bad -> r.message.also { _setup.value = _setup.value.copy(error = it) }
        is QrResult.Ok -> {
            useTarget(r.target)
            null
        }
    }

    fun useTyped(host: String, share: String, folder: String): String? {
        val h = host.trim().trim('\\', '/')
        val s = share.trim().trim('\\', '/')
        if (h.isEmpty()) return "Type the Shield's name or address."
        if (s.isEmpty()) return "Type the share's name (the drive's name as Windows shows it)."
        useTarget(SyncTarget(h, s, SyncTarget.syncFolder(folder), address = if (QrCode.isIpv4(h)) h else ""))
        return null
    }

    /** Settings → "Use this device's own drives" (A2/A3): finds the sync folder on a plugged-in drive, no password. */
    fun useLocal() {
        if (_setup.value.busy) return
        _setup.value = _setup.value.copy(busy = true, error = null, problem = null)
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { LocalDrives.targets() }
            val t = found.firstOrNull()
            if (t == null) {
                _setup.value = _setup.value.copy(
                    busy = false,
                    error = "No AniBox Sync folder was found on this device's drives. On the PC, pick the sync folder on one of " +
                        "the Shield's drives (Settings → Phone app), let it write once, then try again.",
                )
                return@launch
            }
            finishLocal(t)
        }
    }

    private suspend fun finishLocal(t: SyncTarget) {
        DebugLog.i("setup", "Using this device's own drive for the sync folder")
        graph.sync.connectLocal(t)
        _setup.value = SetupState()
        _setupOpen.value = false
        graph.sync.sync()
    }

    private fun useTarget(t: SyncTarget) {
        // The folder is on a drive plugged in here: nothing to sign in to.
        if (LocalDrives.has(t)) {
            viewModelScope.launch { finishLocal(t) }
            return
        }
        val user = graph.credentials.forHost(t.host)?.user ?: _setup.value.user
        _setup.value = _setup.value.copy(step = SetupStep.SIGN_IN, target = t, user = user, error = null, problem = null, askAddress = false)
    }

    /** Tests the folder with this sign-in; on success saves both and syncs. */
    fun connect(user: String, password: String, address: String) {
        val s = _setup.value
        val t = s.target ?: return
        if (s.busy) return
        val typed = address.trim()
        if (typed.isNotEmpty()) {
            if (!QrCode.isIpv4(typed)) {
                _setup.value = s.copy(error = "Type the address as four numbers with dots, for example 192.168.0.20.")
                return
            }
            graph.settings.remember(t.host, typed, Found.SAVED)
            graph.shares.reset(t.host)
        }
        _setup.value = s.copy(busy = true, error = null, problem = null, user = user)
        viewModelScope.launch {
            val signIn = SignIn(user.trim(), password)
            val (problem, message) = try {
                when (val r = graph.sync.test(t, signIn)) {
                    is LibraryResult.Ok -> null to null
                    is LibraryResult.TooNew -> Problem.OTHER to "This library is newer than the app: update the app."
                    is LibraryResult.Broken -> Problem.OTHER to r.reason
                }
            } catch (e: SmbProblem) {
                e.problem to (e.message ?: "That didn't work.")
            } catch (e: IOException) {
                Problem.OTHER to (e.message ?: "That didn't work.")
            } catch (e: RuntimeException) {
                Problem.OTHER to (e.message ?: e.javaClass.simpleName)
            }
            if (message != null) {
                if (problem == Problem.HOST_NOT_FOUND || problem == Problem.NO_ANSWER) graph.shares.reset(t.host)
                _setup.value = _setup.value.copy(
                    busy = false,
                    error = message,
                    problem = problem,
                    // Typing an address only helps for a name: an address that doesn't answer needs the right network.
                    askAddress = !QrCode.isIpv4(t.host) &&
                        (_setup.value.askAddress || problem == Problem.HOST_NOT_FOUND || problem == Problem.NO_ANSWER),
                )
                return@launch
            }
            if (!graph.sync.connect(t, signIn)) {
                _setup.value = _setup.value.copy(busy = false, error = "This phone couldn't keep the password safely (its key store doesn't work). Nothing was saved.")
                return@launch
            }
            _setup.value = SetupState()
            _setupOpen.value = false
            graph.sync.sync()
        }
    }

    /** Settings → "Sign in again": forgets every saved password and asks for the sync share's. */
    fun signInAgain() {
        graph.credentials.clear()
        graph.shares.reset()
        openSetup(signInOnly = true)
    }

    /** The player says a share refused the sign-in: its own user name and password. */
    fun lastStatusProblem(): Problem? = (status.value as? SyncStatus.Failed)?.problem
}
