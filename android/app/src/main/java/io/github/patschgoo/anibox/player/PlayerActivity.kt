package io.github.patschgoo.anibox.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
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
import io.github.patschgoo.anibox.AppGraph
import io.github.patschgoo.anibox.debug.DebugLog
import io.github.patschgoo.anibox.debug.DebugReport
import io.github.patschgoo.anibox.graph
import io.github.patschgoo.anibox.net.Link
import io.github.patschgoo.anibox.net.Problem
import io.github.patschgoo.anibox.net.SignIn
import io.github.patschgoo.anibox.net.SmbConnection
import io.github.patschgoo.anibox.net.SmbProblem
import io.github.patschgoo.anibox.net.Use
import io.github.patschgoo.anibox.stream.LocalServer
import io.github.patschgoo.anibox.net.LocalDrives
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
import java.io.File
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
    private val server = LocalServer(log = { DebugLog.i("http", it) })
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
    /** Loads the current episode into mpv again (after a failure, with the safe outputs). */
    private var reload: (() -> Unit)? = null
    /** The safe outputs were tried for this episode already. */
    private var triedSafe = false
    /** The episode being opened (a second tap on Next cancels the first). */
    private var openJob: Job? = null
    /** The next episode, opened on the share during the countdown so it starts at once (key to addresses). */
    private var preloaded: Pair<String, Pair<String, List<String>>>? = null
    private var preloadJob: Job? = null
    /** Size of the file [prepare] opened last (for the mobile data question). */
    @Volatile private var lastSize = 0L
    private var openSeq = 0
    /** The stream ended before the episode's end (Wi-Fi or the share dropped): it's loaded again at that place. */
    private var drops = 0
    /** Loads the current episode again at a place, with the usual outputs (after a drop). */
    private var resume: ((Double) -> Unit)? = null

    val ui = MutableStateFlow(PlayerUi())
    /** Headset, Bluetooth and lock-screen buttons (A4). */
    private var media: PlayerSession? = null
    private var mediaJob: Job? = null

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
        DebugLog.secret(server.token)
        mpv = try {
            MpvPlayer(this, g.settings.hardwareDecoding, this)
        } catch (e: Throwable) {
            DebugLog.e("player", "mpv couldn't start", e)
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
        media = PlayerSession(this, controls)
        mediaJob = lifecycleScope.launch { ui.collect { media?.update(it) } }
        show.episodes.firstOrNull { it.key == key }?.let { open(it, fromStart) } ?: finish()
    }

    // -- picture in picture (phones; a TV has no use for it) ---------------------------------------------

    private val canPip: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()

    /** Home or another app while an episode plays: it goes on in a small window instead of pausing. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val st = ui.value
        if (canPip && !st.paused && st.error == null && !st.finished && session?.episode != null) {
            try {
                enterPictureInPictureMode(pipParams())
            } catch (e: IllegalStateException) {
                DebugLog.w("player", "Picture in picture refused", e)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // In the small window only the picture shows; the buttons come back with the full screen.
        ui.update { it.copy(controls = !isInPictureInPictureMode) }
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
        openJob?.cancel()
        val seq = ++openSeq
        ended = false
        val playing = currentUrl.isNotEmpty()
        currentUrl = ""
        ignoreEofUntil = System.currentTimeMillis() + 3000
        tracksRead = false
        triedSafe = false
        drops = 0
        reload = null
        resume = null
        val start = s.startFor(e.key, fromStart)
        DebugLog.i("player", "Opening ${s.show.title} · ${e.label}: share ${e.share}, path ${e.path}, start at ${start.toInt()} s")
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
        openJob = lifecycleScope.launch {
            // The episode before ends first (quietly: "stop", not an error), so its addresses can go.
            if (playing) mpv?.let { m -> withContext(mpvThread) { m.stop() } }
            val share = lib.share(e.share)
            if (share == null || !lib.playable(e)) {
                ui.update { it.copy(loading = false, error = "This episode is only on the PC.") }
                return@launch
            }
            val began = System.currentTimeMillis()
            val early = preloaded?.takeIf { it.first == e.key }?.second
            preloadJob?.cancel()
            preloaded = null
            val prepared = try {
                early ?: withContext(Dispatchers.IO) { prepare(share.host, share.share, share.address, e) }
            } catch (p: SmbProblem) {
                DebugLog.w("player", "Couldn't open the episode on \\${share.host}\${share.share} (${p.problem})", p)
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
                DebugLog.w("player", "Couldn't open the episode on \\${share.host}\${share.share}", x)
                g.shares.reset(share.host)
                ui.update { it.copy(loading = false, error = x.message ?: "The episode couldn't be opened.") }
                return@launch
            }
            if (seq != openSeq) return@launch // another episode was asked for meanwhile
            val link = AppGraph.link(this@PlayerActivity)
            if (link.kind == Link.Kind.MOBILE && !mobileOk && !prepared.first.startsWith("/")) {
                val mb = lastSize / 1024 / 1024
                ui.update {
                    it.copy(
                        loading = false, mobileAsk = true,
                        error = "You're on mobile data. This episode is the original file, " +
                            (if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB") +
                            ", and it all comes over your phone's data plan.",
                    )
                }
                return@launch
            }
            server.keepOnly(listOf(prepared.first) + prepared.second)
            DebugLog.i("player", "Share ready in ${System.currentTimeMillis() - began} ms, ${prepared.second.size} subtitle file(s); handing it to mpv")
            val prefs = g.settings.effectiveLanguages(lib.prefs)
            val choice = TrackPrefs.choose(prefs.audio, prefs.subs, prefs.mode, g.settings.showLanguage(s.show.id))
            val title = "${s.show.title} · ${e.label}"
            s.begin(e.key, start)
            requestFocus()
            currentUrl = prepared.first
            val load = {
                lifecycleScope.launch(mpvThread) {
                    mpv?.useNormalOutputs()
                    mpv?.clearProblems()
                    mpv?.load(prepared.first, start, prepared.second, title, choice)
                }
                startTicker()
            }
            resume = { at ->
                lifecycleScope.launch(mpvThread) {
                    mpv?.clearProblems()
                    mpv?.load(prepared.first, at, prepared.second, title, choice)
                }
            }
            reload = {
                val at = ui.value.position.takeIf { it > 1 } ?: start
                lifecycleScope.launch(mpvThread) {
                    mpv?.useSafeOutputs()
                    mpv?.clearProblems()
                    mpv?.load(prepared.first, at, prepared.second, title, choice)
                }
            }
            if (mpv?.surfaceReady == true) load() else pendingLoad = load
        }
    }

    /** Opens [next] on the share while the countdown runs; a failure is simply tried again when it opens. */
    private fun preload(next: Episode) {
        val lib = library ?: return
        val share = lib.share(next.share) ?: return
        if (!lib.playable(next)) return
        preloadJob?.cancel()
        preloaded = null
        preloadJob = lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { prepare(share.host, share.share, share.address, next) }
                preloaded = next.key to p
                DebugLog.i("player", "Next episode ready on the share: ${next.label}")
            } catch (x: IOException) {
                DebugLog.w("player", "Couldn't get the next episode ready early (it's opened again when it starts)", x)
            }
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
        // Kept on this phone: no share needed at all (A5).
        g.downloads.video(e.key)?.let { f ->
            lastSize = f.length()
            DebugLog.i("player", "Playing the download on this phone (${f.length() / 1024 / 1024} MB)")
            val prefs = g.settings.effectiveLanguages(library?.prefs)
            val subs = Sidecars.find(e.fileName, g.downloads.files(e.key), "", prefs.subs)
            return f.path to subs.mapNotNull { s -> File(f.parentFile, s.name).takeIf { it.isFile }?.path }
        }
        // On the Shield the drive is plugged in here: mpv opens the file itself (A3), no network, no password.
        LocalDrives.file(shareName, e.path)?.let { f ->
            lastSize = f.length()
            DebugLog.i("player", "Playing from this device's own drive (${f.length() / 1024 / 1024} MB)")
            val dir = f.parentFile
            val names = dir?.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
            val folder = e.path.substringBeforeLast('/', "")
            val prefs = g.settings.effectiveLanguages(library?.prefs)
            val subs = Sidecars.find(e.fileName, names, folder, prefs.subs)
            val subFiles = subs.mapNotNull { s -> dir?.let { File(it, s.name) }?.takeIf { it.isFile }?.path }
            return f.path to subFiles
        }
        // The player's own connection: a failed progress write (which closes the sync one) can't cut it off.
        val conn: SmbConnection = g.shares.connection(host, shareName, address.ifEmpty { g.sync.hint(host) }, Use.PLAYER)
        val size = conn.size(e.path)
        lastSize = size
        DebugLog.i("player", "File size ${size / 1024 / 1024} MB (${e.fileName.substringAfterLast('.', "?")})")
        val folder = e.path.substringBeforeLast('/', "")
        val names = try { conn.list(folder).filter { !it.folder }.map { it.name } } catch (_: IOException) { emptyList() }
        val prefs = g.settings.effectiveLanguages(library?.prefs)
        val subs = Sidecars.find(e.fileName, names, folder, prefs.subs)
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
                if (st.eof && !ended && st.duration > 0 && System.currentTimeMillis() > ignoreEofUntil) {
                    // mpv says "end of file" too when the share or the Wi-Fi drops: only the real end counts.
                    if (PlaybackSession.reachedEnd(st.position, st.duration)) endOfEpisode(st.duration) else streamDropped(st.position, st.duration)
                }
                // The queue goes out about once a minute while playing.
                if (System.currentTimeMillis() - lastFlush > 60_000) {
                    lastFlush = System.currentTimeMillis()
                    g.scope.launch { g.sync.flush() }
                }
            }
        }
    }

    /** The stream stopped before the episode's end: load it again at that place (twice), then say so; never "watched". */
    private fun streamDropped(position: Double, duration: Double) {
        val again = resume
        DebugLog.w("player", "The stream ended at ${position.toInt()} s of ${duration.toInt()} s, before the episode's end (drop ${drops + 1})")
        if (drops < MAX_DROPS && again != null) {
            drops++
            ignoreEofUntil = System.currentTimeMillis() + 8000
            ui.update { it.copy(buffering = true) }
            again(maxOf(0.0, position - 2))
            return
        }
        ended = true
        session?.pauseOrStop(position, duration)
        g.scope.launch { g.sync.flush() }
        ui.update {
            it.copy(
                controls = true,
                error = "The connection to the share dropped at ${clock(position)}. Your place is kept: tap Retry when the Shield answers again.",
            )
        }
    }

    private fun clock(seconds: Double): String {
        val t = seconds.toInt()
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun endOfEpisode(duration: Double) {
        ended = true
        val next = session?.ended(duration)
        g.scope.launch { g.sync.flush() }
        if (next == null) {
            ui.update { it.copy(finished = true, controls = true) }
            return
        }
        if (!g.settings.autoplay) {
            ui.update { it.copy(finished = true, controls = true, nextLabel = next.label) }
            return
        }
        preload(next)
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
        g.scope.launch { g.sync.flush() } // not the screen's scope: closing the player would cancel it
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

        override fun sendDebug() {
            try {
                DebugReport.send(this@PlayerActivity, "from the player: ${ui.value.error.orEmpty().take(120)}")
            } catch (e: Exception) {
                DebugLog.e("debug", "Couldn't make the debug info", e)
                ui.update { it.copy(error = (it.error ?: "") + "\n\nThe debug info couldn't be made (${e.javaClass.simpleName}).") }
            }
        }

        override fun playOnMobileData() {
            mobileOk = true
            retry()
        }

        override fun retry() {
            val e = session?.episode ?: return
            ui.update { it.copy(error = null, signInShare = null, mobileAsk = false) }
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
        g.scope.launch { g.sync.flush() }
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
        mediaJob?.cancel()
        media?.release()
        media = null
        try { unregisterReceiver(noisy) } catch (_: IllegalArgumentException) {}
        focusRequest?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        if (!ended && session?.episode != null) {
            val st = ui.value
            session?.pauseOrStop(st.position, st.duration)
            g.scope.launch { g.sync.flush() }
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

    override fun endFile(error: Boolean, code: Int) {
        if (!error || currentUrl.isEmpty()) return // not the episode before, while the next one is being opened
        val said = mpv?.problems().orEmpty()
        DebugLog.w("player", "mpv couldn't play the file (${MpvPlayer.errorName(code)}). Its last warnings: ${said.joinToString(" | ").ifEmpty { "none" }}")
        lifecycleScope.launch {
            val again = reload
            if (!triedSafe && again != null) {
                triedSafe = true
                again()
                return@launch
            }
            ui.update { it.copy(loading = false, error = errorText(code, said)) }
        }
    }

    /** What the error box says: mpv's reason in words, its last message, and what to do. */
    private fun errorText(code: Int, said: List<String>): String {
        val why = when (code) {
            dev.jdtech.mpv.MPVLib.MpvError.MPV_ERROR_LOADING_FAILED -> "it couldn't read the file. Is the Shield still on, and is the file all there?"
            dev.jdtech.mpv.MPVLib.MpvError.MPV_ERROR_VO_INIT_FAILED -> "the picture output didn't start on this phone."
            dev.jdtech.mpv.MPVLib.MpvError.MPV_ERROR_AO_INIT_FAILED -> "the sound output didn't start on this phone."
            dev.jdtech.mpv.MPVLib.MpvError.MPV_ERROR_NOTHING_TO_PLAY -> "it found no picture or sound it can play in this file."
            dev.jdtech.mpv.MPVLib.MpvError.MPV_ERROR_UNKNOWN_FORMAT -> "it doesn't know this file type, or the file isn't all there."
            else -> "it stopped with an error (${MpvPlayer.errorName(code)})."
        }
        val last = said.lastOrNull { it.contains("error", ignoreCase = true) || it.contains("fail", ignoreCase = true) } ?: said.lastOrNull()
        return buildString {
            append("mpv couldn't play this file: ").append(why)
            if (last != null) append("\n\nmpv said: ").append(DebugLog.scrub(last).take(220))
            append("\n\nTap “Send debug info” to send us what happened.")
        }
    }

    companion object {
        /** Said yes to mobile data once: not asked again until the app closes. */
        @Volatile private var mobileOk = false
        private const val EXTRA_SHOW = "show"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_FROM_START = "fromStart"
        private const val STATE_KEY = "playing"
        private const val MAX_DROPS = 2

        fun intent(context: Context, showId: String, key: String, fromStart: Boolean): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_SHOW, showId)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_FROM_START, fromStart)
    }
}
