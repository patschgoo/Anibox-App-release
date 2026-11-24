package io.github.patschgoo.anibox.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.patschgoo.anibox.graph
import io.github.patschgoo.anibox.net.Problem
import io.github.patschgoo.anibox.net.SignIn
import io.github.patschgoo.anibox.net.SmbConnection
import io.github.patschgoo.anibox.net.SmbProblem
import io.github.patschgoo.anibox.stream.LocalServer
import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.Library
import io.github.patschgoo.anibox.ui.AniBoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Full screen, landscape player for one show's episodes. The episode is read from the Shield's share
 * through [LocalServer] (mpv can't open smb:// here); the progress goes to [PlaybackSession].
 */
class PlayerActivity : ComponentActivity(), MpvPlayer.Events {
    private val g by lazy { graph }
    /** Every mpv call runs on this one thread, so mpv is never destroyed while a call is still running. */
    private val mpvExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "anibox-mpv").apply { isDaemon = true } }
    private val mpvThread = mpvExecutor.asCoroutineDispatcher()
    private val server = LocalServer()
    private var mpv: MpvPlayer? = null
    private var session: PlaybackSession? = null
    private var library: Library? = null
    private var pendingLoad: (() -> Unit)? = null
    private var ticker: Job? = null
    private var countdownJob: Job? = null
    private var tracksRead = false
    private var resumeOnFocus = false
    private var focusRequest: AudioFocusRequest? = null
    private var lastFlush = 0L
    private var ended = false
    /** mpv couldn't start (e.g. no build for this phone's processor). */
    private var startError: String? = null
    /** The address given to mpv for the episode the session is on: ticks from any other file are ignored. */
    @Volatile private var currentUrl = ""
    /** Right after a seek mpv may still say "at the end" for a moment. */
    private var ignoreEofUntil = 0L

    val ui = MutableStateFlow(PlayerUi())

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)

        val showId = intent.getStringExtra(EXTRA_SHOW).orEmpty()
        val key = savedInstanceState?.getString(STATE_KEY) ?: intent.getStringExtra(EXTRA_KEY).orEmpty()
        val fromStart = savedInstanceState == null && intent.getBooleanExtra(EXTRA_FROM_START, false)
        val lib = g.sync.library.value
        val show = lib?.show(showId)
        if (lib == null || show == null) {
            finish()
            return
        }
        library = lib
        session = PlaybackSession(show, g.store, { lib.playable(it) }, g.settings.resumeBack)
        server.start()
        mpv = try {
            MpvPlayer(this, g.settings.hardwareDecoding, this)
        } catch (e: Throwable) {
            startError = "The player couldn't start on this phone (${e.javaClass.simpleName})."
            null
        }

        setContent {
            AniBoxTheme {
                val state by ui.collectAsState()
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { ctx -> SurfaceView(ctx).also { it.holder.addCallback(surfaceCallback) } }, modifier = Modifier.fillMaxSize())
                    PlayerControls(state, controls)
                }
            }
        }
        show.episodes.firstOrNull { it.key == key }?.let { open(it, fromStart) } ?: finish()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            mpv?.attach(holder.surface)
            pendingLoad?.let { pendingLoad = null; it() }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            mpv?.surfaceSize(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            mpv?.detach()
        }
    }

    // -- opening an episode ---------------------------------------------------------------------------------

    private fun open(e: Episode, fromStart: Boolean) {
        val s = session ?: return
        val lib = library ?: return
        startError?.let { msg ->
            ui.update { it.copy(title = s.show.title, error = msg, controls = true) }
            return
        }
        countdownJob?.cancel()
        ended = false
        val playing = currentUrl.isNotEmpty()
        currentUrl = ""
        ignoreEofUntil = System.currentTimeMillis() + 3000
        tracksRead = false
        val start = s.startFor(e.key, fromStart)
        ui.update {
            PlayerUi(
                title = s.show.title,
                episode = listOf(e.label, e.title).filter { it.isNotEmpty() }.joinToString(" · "),
                loading = true,
                controls = true,
                hasPrevious = hasNeighbour(e, -1),
                hasNext = hasNeighbour(e, 1),
            )
        }
        lifecycleScope.launch {
            // The episode before ends first (quietly: "stop", not an error), so its addresses can go.
            if (playing) mpv?.let { m -> withContext(mpvThread) { m.stop() } }
            val share = lib.share(e.share)
            if (share == null || !lib.playable(e)) {
                ui.update { it.copy(loading = false, error = "This episode is only on the PC.") }
                return@launch
            }
            val prepared = try {
                withContext(Dispatchers.IO) { prepare(share.host, share.share, share.address, e) }
            } catch (p: SmbProblem) {
                ui.update {
                    it.copy(
                        loading = false,
                        error = p.message ?: "The episode couldn't be opened.",
                        signInShare = if (p.problem == Problem.WRONG_SIGN_IN || p.problem == Problem.ACCESS_DENIED) share.share else null,
                        signInHost = share.host,
                    )
                }
                g.shares.reset(share.host)
                return@launch
            } catch (x: IOException) {
                g.shares.reset(share.host)
                ui.update { it.copy(loading = false, error = x.message ?: "The episode couldn't be opened.") }
                return@launch
            }
            val prefs = g.settings.effectiveLanguages(lib.prefs)
            val choice = TrackPrefs.choose(prefs.audio, prefs.subs, prefs.mode, g.settings.showLanguage(s.show.id))
            val title = "${s.show.title} · ${e.label}"
            s.begin(e.key, start)
            requestFocus()
            currentUrl = prepared.first
            val load = {
                lifecycleScope.launch(mpvThread) { mpv?.load(prepared.first, start, prepared.second, title, choice) }
                startTicker()
            }
            if (mpv?.surfaceReady == true) load() else pendingLoad = load
        }
    }

    private fun hasNeighbour(e: Episode, step: Int): Boolean {
        val s = session ?: return false
        val lib = library ?: return false
        val i = s.show.episodes.indexOfFirst { it.key == e.key }
        var j = i + step
        while (j in s.show.episodes.indices) {
            if (lib.playable(s.show.episodes[j])) return true
            j += step
        }
        return false
    }

    /** The episode's address on the local server and its subtitle files' addresses. */
    private fun prepare(host: String, shareName: String, address: String, e: Episode): Pair<String, List<String>> {
        val conn: SmbConnection = g.shares.connection(host, shareName, address.ifEmpty { g.sync.hint(host) })
        val size = conn.size(e.path)
        val folder = e.path.substringBeforeLast('/', "")
        val names = try { conn.list(folder).filter { !it.folder }.map { it.name } } catch (_: IOException) { emptyList() }
        val prefs = g.settings.effectiveLanguages(library?.prefs)
        val subs = Sidecars.find(e.fileName, names, folder, prefs.subs)
        server.clear()
        val video = server.add(conn.mediaSource(e.path, size), e.fileName)
        val subUrls = subs.map { server.add(conn.mediaSource(it.path), it.name) }
        return video to subUrls
    }

    // -- while playing -------------------------------------------------------------------------------------

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                val m = mpv ?: break
                val st = withContext(mpvThread) { m.state() }
                val s = session ?: break
                if (st.path != currentUrl) continue // the file before is still open, or the next one isn't yet
                if (!st.idle && st.duration > 0) {
                    if (ui.value.loading) ui.update { it.copy(loading = false) }
                    if (!st.paused && !ended) s.tick(st.position, st.duration)
                }
                if (!tracksRead && st.duration > 0) {
                    tracksRead = true
                    val list = withContext(mpvThread) { m.tracks() }
                    val decoder = withContext(mpvThread) { m.decoder() }
                    ui.update { it.copy(tracks = list, decoder = decoder) }
                }
                if (tick++ % 4 == 0 && tracksRead) {
                    val list = withContext(mpvThread) { m.tracks() }
                    val decoder = withContext(mpvThread) { m.decoder() }
                    if (list != ui.value.tracks || decoder != ui.value.decoder) ui.update { it.copy(tracks = list, decoder = decoder) }
                }
                ui.update { it.copy(position = st.position, duration = st.duration, paused = st.paused, buffering = st.buffering) }
                if (st.eof && !ended && st.duration > 0 && System.currentTimeMillis() > ignoreEofUntil) endOfEpisode(st.duration)
                // The queue goes out about once a minute while playing.
                if (System.currentTimeMillis() - lastFlush > 60_000) {
                    lastFlush = System.currentTimeMillis()
                    launch { g.sync.flush() }
                }
            }
        }
    }

    private fun endOfEpisode(duration: Double) {
        ended = true
        val next = session?.ended(duration)
        lifecycleScope.launch { g.sync.flush() }
        if (next == null) {
            ui.update { it.copy(finished = true, controls = true) }
            return
        }
        if (!g.settings.autoplay) {
            ui.update { it.copy(finished = true, controls = true, nextLabel = next.label) }
            return
        }
        countdownJob = lifecycleScope.launch {
            for (left in PlaybackSession.COUNTDOWN downTo 1) {
                ui.update { it.copy(countdown = left, nextLabel = next.label, controls = true) }
                delay(1000)
            }
            ui.update { it.copy(countdown = null) }
            open(next, false)
        }
    }

    private fun pause(paused: Boolean) {
        val m = mpv ?: return
        lifecycleScope.launch(mpvThread) { m.setPaused(paused) }
        if (paused) saveNow()
    }

    private fun saveNow() {
        val st = ui.value
        if (!ended) session?.pauseOrStop(st.position, st.duration)
        lifecycleScope.launch { g.sync.flush() }
    }

    private val controls = object : PlayerControlActions {
        override fun togglePause() {
            pause(!ui.value.paused)
        }

        override fun seekTo(seconds: Double) {
            val m = mpv ?: return
            stopCountdown()
            lifecycleScope.launch(mpvThread) { m.seekTo(seconds) }
        }

        override fun seekBy(seconds: Double) {
            val m = mpv ?: return
            stopCountdown()
            lifecycleScope.launch(mpvThread) { m.seekBy(seconds) }
        }

        override fun toggleControls() = ui.update { it.copy(controls = !it.controls) }
        override fun showControls() = ui.update { it.copy(controls = true) }

        override fun next() = step(1)
        override fun previous() = step(-1)

        override fun playNow() {
            countdownJob?.cancel()
            val s = session ?: return
            s.neighbour(1)?.let { ui.update { u -> u.copy(countdown = null) }; open(it, false) }
        }

        override fun cancelCountdown() {
            countdownJob?.cancel()
            ui.update { it.copy(countdown = null, finished = true) }
        }

        override fun audio(track: Track) {
            val m = mpv ?: return
            lifecycleScope.launch(mpvThread) { m.setAudio(track.id) }
            if (track.lang.isNotEmpty()) session?.let { g.settings.setShowLanguage(it.show.id, audio = track.lang) }
            tracksRead = false
        }

        override fun subtitle(track: Track?) {
            val m = mpv ?: return
            lifecycleScope.launch(mpvThread) { m.setSub(track?.id) }
            session?.let { g.settings.setShowLanguage(it.show.id, sub = track?.lang?.ifEmpty { null } ?: if (track == null) "no" else "") }
            tracksRead = false
        }

        override fun close() {
            saveNow()
            finish()
        }

        override fun retry() {
            val e = session?.episode ?: return
            ui.update { it.copy(error = null, signInShare = null) }
            open(e, false)
        }

        override fun signIn(user: String, password: String) {
            val st = ui.value
            val host = st.signInHost ?: return
            val share = st.signInShare ?: return
            if (!g.credentials.setShare(host, share, SignIn(user.trim(), password))) {
                ui.update { it.copy(error = "This phone couldn't keep the password safely. Nothing was saved.") }
                return
            }
            g.shares.reset(host)
            retry()
        }
    }

    /** Seeking after the end (or during the countdown): watching on, no next episode by itself. */
    private fun stopCountdown() {
        ended = false
        ignoreEofUntil = System.currentTimeMillis() + 1500
        if (countdownJob?.isActive == true || ui.value.finished) {
            countdownJob?.cancel()
            ui.update { it.copy(countdown = null, finished = false, nextLabel = "") }
        }
    }

    private fun step(dir: Int) {
        val s = session ?: return
        val target = s.neighbour(dir) ?: return
        countdownJob?.cancel()
        if (!ended) {
            val st = ui.value
            s.pauseOrStop(st.position, st.duration)
        }
        lifecycleScope.launch { g.sync.flush() }
        open(target, false)
    }

    // -- sound focus -----------------------------------------------------------------------------------------

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause(true)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (!ui.value.paused) {
                        resumeOnFocus = true
                        pause(true)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                        resumeOnFocus = false
                        pause(false)
                    }
                }
            }
            .build().also { focusRequest = it }
        am.requestAudioFocus(req)
    }

    // -- the activity's life -----------------------------------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session?.episode?.key?.let { outState.putString(STATE_KEY, it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onStop() {
        super.onStop()
        // Gone to the background (or the screen went off): pause and keep the place.
        if (!isChangingConfigurations && !ui.value.paused) pause(true) else saveNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(noisy) } catch (_: IllegalArgumentException) {}
        focusRequest?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        if (!ended && session?.episode != null) {
            val st = ui.value
            session?.pauseOrStop(st.position, st.duration)
        }
        ticker?.cancel()
        val m = mpv
        mpv = null
        server.stop()
        // After every call already waiting for mpv's thread.
        mpvExecutor.execute { m?.destroy() }
        mpvExecutor.shutdown()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // -- mpv's events (on mpv's thread) --------------------------------------------------------------------

    override fun fileLoaded() {
        tracksRead = false
    }

    override fun endFile(error: Boolean) {
        if (error && currentUrl.isNotEmpty()) { // not the episode before, while the next one is being opened
            ui.update { it.copy(loading = false, error = "mpv couldn't play this file. Is the Shield still on, and is the file all there?") }
        }
    }

    companion object {
        private const val EXTRA_SHOW = "show"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_FROM_START = "fromStart"
        private const val STATE_KEY = "playing"

        fun intent(context: Context, showId: String, key: String, fromStart: Boolean): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_SHOW, showId)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_FROM_START, fromStart)
    }
}
