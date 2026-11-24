package io.github.patschgoo.anibox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.dp
import io.github.patschgoo.anibox.ui.LocalTv
import io.github.patschgoo.anibox.ui.TvMenu
import io.github.patschgoo.anibox.ui.isTv
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.patschgoo.anibox.data.SyncStatus
import io.github.patschgoo.anibox.library.Kind
import io.github.patschgoo.anibox.library.Sort
import io.github.patschgoo.anibox.player.PlayerActivity
import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.Show
import io.github.patschgoo.anibox.ui.AniBoxTheme
import io.github.patschgoo.anibox.ui.AniColors
import io.github.patschgoo.anibox.ui.HomeScreen
import io.github.patschgoo.anibox.ui.LibraryFilter
import io.github.patschgoo.anibox.ui.LibraryScreen
import io.github.patschgoo.anibox.ui.LocalPosters
import io.github.patschgoo.anibox.ui.PosterLoader
import io.github.patschgoo.anibox.ui.PosterMemory
import io.github.patschgoo.anibox.ui.SettingsActions
import io.github.patschgoo.anibox.ui.SettingsScreen
import io.github.patschgoo.anibox.ui.SettingsUi
import io.github.patschgoo.anibox.ui.SetupActions
import io.github.patschgoo.anibox.ui.SetupScreen
import io.github.patschgoo.anibox.ui.ShowScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AniColors.bg.toArgb()), SystemBarStyle.dark(AniColors.bg.toArgb()))
        super.onCreate(savedInstanceState)
        val posters = PosterLoader { name -> PosterMemory.decode(name, graph.sync.poster(name)) }
        val tv = isTv(this)
        setContent {
            AniBoxTheme(tv = tv) {
                CompositionLocalProvider(LocalPosters provides posters) {
                    Root(vm, ::play)
                }
            }
        }
        // Back from the player (or the background): the progress it saved shows at once; send what waits.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.syncIfStale()
                graph.sync.flush()
            }
        }
    }

    private fun play(show: Show, episode: Episode, fromStart: Boolean) {
        startActivity(PlayerActivity.intent(this, show.id, episode.key, fromStart))
    }
}

private enum class Tab { HOME, LIBRARY, SETTINGS }

@Composable
private fun Root(vm: MainViewModel, play: (Show, Episode, Boolean) -> Unit) {
    val setupOpen by vm.setupOpen.collectAsState()
    if (setupOpen) {
        val state by vm.setup.collectAsState()
        BackHandler(enabled = state.step != SetupStep.CHOOSE || state.fromSettings) { vm.setupBack() }
        val actions = remember(vm) {
            object : SetupActions {
                override fun step(step: SetupStep) = vm.setupStep(step)
                override fun back() { vm.setupBack() }
                override fun qrText(text: String) = vm.useQrText(text)
                override fun typed(host: String, share: String, folder: String) = vm.useTyped(host, share, folder)
                override fun connect(user: String, password: String, address: String) = vm.connect(user, password, address)
            }
        }
        SetupScreen(state, actions)
        return
    }
    val library by vm.library.collectAsState()
    val progress by vm.progress.collectAsState()
    val status by vm.status.collectAsState()
    val settingsVersion by vm.settingsVersion.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showId by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf<Kind?>(null) }
    var atype by rememberSaveable { mutableStateOf("") }
    var libName by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.TITLE) }
    /** The show opened last: Home puts the remote's focus back on its card. */
    var lastShow by rememberSaveable { mutableStateOf<String?>(null) }
    val lib = library
    val open = showId?.let { lib?.show(it) }
    val openShow: (Show) -> Unit = { showId = it.id; lastShow = it.id }

    BackHandler(enabled = open != null || tab != Tab.HOME) {
        if (open != null) showId = null else tab = Tab.HOME
    }

    val content: @Composable () -> Unit = {
        if (open != null && lib != null) {
            ShowScreen(
                show = open,
                progress = progress,
                playable = { lib.playable(it) },
                onBack = { showId = null },
                onPlay = { e, fromStart -> play(open, e, fromStart) },
                onWatched = { keys, w -> vm.setWatched(keys, w) },
            )
        } else {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    library = lib,
                    progress = progress,
                    status = status,
                    onSync = vm::syncNow,
                    onSignInAgain = vm::signInAgain,
                    onPlay = { s, e -> play(s, e, false) },
                    onShow = openShow,
                    onLibrary = { name ->
                        kind = lib?.shows?.firstOrNull { it.library == name }?.let { io.github.patschgoo.anibox.library.Browse.kindOf(it) }
                        libName = name
                        atype = ""
                        tab = Tab.LIBRARY
                    },
                    focusShow = lastShow,
                )
                Tab.LIBRARY -> LibraryScreen(
                    library = lib,
                    progress = progress,
                    filter = LibraryFilter(kind, atype, libName, query, sort),
                    onFilter = { f -> kind = f.kind; atype = f.atype; libName = f.library; query = f.query; sort = f.sort },
                    onShow = openShow,
                    syncing = status == SyncStatus.Running,
                    onSync = vm::syncNow,
                )
                Tab.SETTINGS -> {
                    val ui = remember(settingsVersion, status, lib, progress) { settingsUi(vm, status) }
                    SettingsScreen(ui, remember(vm) { settingsActions(vm) })
                }
            }
        }
    }

    if (LocalTv.current) {
        // TV: a menu on the left (Left reaches it from any page), the page on the right; no bottom bar.
        Row(Modifier.fillMaxSize().background(AniColors.bg)) {
            if (open == null) TvMenu(tab.ordinal) { tab = Tab.entries[it] }
            Box(Modifier.weight(1f).fillMaxHeight().background(AniColors.bg).padding(top = 12.dp)) { content() }
        }
        return
    }

    Scaffold(
        containerColor = AniColors.bg,
        bottomBar = {
            if (open == null) {
                NavigationBar(containerColor = AniColors.s1) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AniColors.acc,
                        selectedTextColor = AniColors.text,
                        indicatorColor = AniColors.s2,
                        unselectedIconColor = AniColors.muted,
                        unselectedTextColor = AniColors.muted,
                    )
                    NavigationBarItem(tab == Tab.HOME, { tab = Tab.HOME }, { Icon(Icons.Filled.Home, null) }, label = { Text(stringResource(R.string.tab_home)) }, colors = colors)
                    NavigationBarItem(tab == Tab.LIBRARY, { tab = Tab.LIBRARY }, { Icon(Icons.Filled.VideoLibrary, null) }, label = { Text(stringResource(R.string.tab_library)) }, colors = colors)
                    NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) }, colors = colors)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(AniColors.bg).padding(padding)) { content() }
    }
}

private fun settingsUi(vm: MainViewModel, status: SyncStatus): SettingsUi {
    val g = vm.graph
    val lib = vm.library.value
    val s = g.settings
    return SettingsUi(
        target = s.target,
        status = status,
        pc = lib?.pc?.ifEmpty { null } ?: s.target?.pc.orEmpty(),
        journal = "progress/" + g.sync.journalName,
        pending = g.store.pendingCount(),
        addresses = s.addresses(),
        hosts = (listOfNotNull(s.target?.host) + lib?.shares?.map { it.host }.orEmpty()).distinctBy { it.lowercase() },
        audio = s.audioLangs,
        subs = s.subLangs,
        mode = s.subsMode,
        pcPrefs = lib?.prefs,
        autoplay = s.autoplay,
        resumeBack = s.resumeBack,
        hwdec = s.hardwareDecoding,
        version = BuildConfig.VERSION_NAME,
    )
}

private fun settingsActions(vm: MainViewModel): SettingsActions {
    val s = vm.graph.settings
    return object : SettingsActions {
        override fun syncNow() = vm.syncNow()
        override fun changeFolder() = vm.openSetup(signInOnly = false)
        override fun signInAgain() = vm.signInAgain()
        override fun setAddress(host: String, address: String): String? {
            val a = address.trim()
            if (!io.github.patschgoo.anibox.sync.QrCode.isIpv4(a)) return "Type the address as four numbers with dots, for example 192.168.0.20."
            s.remember(host, a, io.github.patschgoo.anibox.net.Found.SAVED)
            vm.graph.shares.reset(host)
            return null
        }
        override fun forgetAddress(host: String) {
            s.forgetAddress(host)
            vm.graph.shares.reset(host)
        }
        override fun setAudio(text: String) { s.audioLangs = text }
        override fun setSubs(text: String) { s.subLangs = text }
        override fun setMode(mode: String) { s.subsMode = mode }
        override fun setAutoplay(on: Boolean) { s.autoplay = on }
        override fun setResumeBack(seconds: Int) { s.resumeBack = seconds }
        override fun setHwdec(on: Boolean) { s.hardwareDecoding = on }
    }
}
