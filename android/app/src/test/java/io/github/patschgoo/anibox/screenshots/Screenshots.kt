package io.github.patschgoo.anibox.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.patschgoo.anibox.SetupState
import io.github.patschgoo.anibox.SetupStep
import io.github.patschgoo.anibox.data.HostAddress
import io.github.patschgoo.anibox.data.SyncStatus
import io.github.patschgoo.anibox.library.Kind
import io.github.patschgoo.anibox.net.Found
import io.github.patschgoo.anibox.net.Problem
import io.github.patschgoo.anibox.player.PlayerControlActions
import io.github.patschgoo.anibox.player.PlayerControls
import io.github.patschgoo.anibox.player.PlayerUi
import io.github.patschgoo.anibox.player.Track
import io.github.patschgoo.anibox.sync.LibraryParser
import io.github.patschgoo.anibox.sync.LibraryResult
import io.github.patschgoo.anibox.sync.LocalProgress
import io.github.patschgoo.anibox.sync.SyncTarget
import io.github.patschgoo.anibox.ui.AniBoxTheme
import io.github.patschgoo.anibox.ui.AniColors
import io.github.patschgoo.anibox.ui.HomeScreen
import io.github.patschgoo.anibox.ui.LibraryFilter
import io.github.patschgoo.anibox.ui.LibraryScreen
import io.github.patschgoo.anibox.ui.LocalPosters
import io.github.patschgoo.anibox.ui.PosterLoader
import io.github.patschgoo.anibox.ui.SettingsActions
import io.github.patschgoo.anibox.ui.SettingsScreen
import io.github.patschgoo.anibox.ui.SettingsUi
import io.github.patschgoo.anibox.ui.SetupActions
import io.github.patschgoo.anibox.ui.SetupScreen
import io.github.patschgoo.anibox.ui.ShowScreen
import io.github.patschgoo.anibox.ui.TvMenu
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The screens with made-up data, rendered on the JVM (opt-in: `-Pscreenshots`), as PNGs in
 * app/build/outputs/roborazzi/. Look at them after UI changes: cut-off text and broken layouts show.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class Screenshots {
    @get:Rule val compose = createComposeRule()

    private val out = File(System.getProperty("anibox.outDir") ?: "build/outputs/roborazzi")
    private val library = (LibraryParser.parse(fakeLibrary()) as LibraryResult.Ok).library
    private val now = 1_763_900_000.0
    private val progress = mapOf(
        "Anime\\Kaze no Tabi\\Kaze no Tabi 01.mkv" to LocalProgress(0.0, 1420.0, true, now - 3000),
        "Anime\\Kaze no Tabi\\Kaze no Tabi 02.mkv" to LocalProgress(0.0, 1420.0, true, now - 2000),
        "Anime\\Kaze no Tabi\\Kaze no Tabi 03.mkv" to LocalProgress(612.0, 1420.0, false, now - 100),
        "Anime\\Umi to Sora no Aida ni Saku Hana no Monogatari\\Umi 01.mkv" to LocalProgress(0.0, 1440.0, true, now - 500),
        "TV-Shows\\Example Street\\Season 01\\Example Street S01E01.mkv" to LocalProgress(0.0, 2600.0, true, now - 9000),
    )

    private val posters = PosterLoader { name ->
        if (name.isEmpty()) null else gradient(name.hashCode())
    }

    private fun gradient(seed: Int): ImageBitmap {
        val bmp = ImageBitmap(200, 300)
        val c = Canvas(bmp)
        val hue = (seed and 0xFFFF) % 360
        val a = Color.hsv(hue.toFloat(), 0.55f, 0.55f)
        val b = Color.hsv(((hue + 50) % 360).toFloat(), 0.7f, 0.25f)
        c.drawRect(0f, 0f, 200f, 300f, Paint().apply { color = a })
        c.drawRect(0f, 150f, 200f, 300f, Paint().apply { color = b })
        c.drawCircle(Offset(140f, 90f), 46f, Paint().apply { color = Color.White.copy(alpha = 0.25f) })
        return bmp
    }

    private fun shoot(name: String, interact: () -> Unit = {}, tv: Boolean = false, content: @Composable () -> Unit) {
        // A TV has no touch screen: the remote's white focus ring shows.
        if (tv) runCatching { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false) }
        compose.setContent {
            AniBoxTheme(tv = tv) {
                CompositionLocalProvider(LocalPosters provides posters) {
                    Box(Modifier.fillMaxSize().background(AniColors.bg)) { content() }
                }
            }
        }
        compose.waitForIdle()
        interact()
        compose.waitForIdle()
        File(out, "$name.png").parentFile?.mkdirs()
        captureScreenRoboImage(File(out, "$name.png").path)
    }

    @Test fun home() = shoot("home") {
        HomeScreen(library, progress, SyncStatus.Done(1_763_900_000_000, library.shows.size, 3, 1), {}, {}, { _, _ -> }, {}, {})
    }

    @Test fun homeOffline() = shoot("home_offline") {
        HomeScreen(library, progress, SyncStatus.Failed(Problem.WRONG_SIGN_IN, "The user name or password for “Drive A” on MEDIABOX isn't right.", 0), {}, {}, { _, _ -> }, {}, {})
    }

    @Test fun libraryAnime() = shoot("library_anime") {
        LibraryScreen(library, progress, LibraryFilter(kind = Kind.ANIME), {}, {})
    }

    @Test fun librarySearch() = shoot("library_search") {
        LibraryScreen(library, progress, LibraryFilter(kind = Kind.ANIME, query = "kaze"), {}, {})
    }

    @Test fun showPage() = shoot("show") {
        val s = library.show("Anime\\Kaze no Tabi")!!
        ShowScreen(s, progress, { library.playable(it) }, {}, { _, _ -> }, { _, _ -> })
    }

    @Test fun showPageMenu() = shoot("show_menu", interact = {
        compose.onAllNodesWithText("Episode 2").onFirst().assertExists()
    }) {
        val s = library.show("Anime\\Umi to Sora no Aida ni Saku Hana no Monogatari")!!
        ShowScreen(s, progress, { library.playable(it) }, {}, { _, _ -> }, { _, _ -> })
    }

    @Test fun settings() = shoot("settings") {
        SettingsScreen(settingsUi(), noSettings)
    }

    @Test fun setupChoose() = shoot("setup_choose") {
        SetupScreen(SetupState(), noSetup) { _, _ -> }
    }

    @Test fun setupSignIn() = shoot("setup_signin") {
        SetupScreen(
            SetupState(
                step = SetupStep.SIGN_IN,
                target = SyncTarget("MEDIABOX", "Drive A", "Anime/AniBox Sync", "DESKTOP-TEST"),
                user = "viewer",
                error = "Can't find MEDIABOX on this network. Type its address (your router's list of devices shows it).",
                problem = Problem.HOST_NOT_FOUND,
                askAddress = true,
            ),
            noSetup,
        ) { _, _ -> }
    }

    @Test fun setupType() = shoot("setup_type") {
        SetupScreen(SetupState(step = SetupStep.TYPE), noSetup) { _, _ -> }
    }

    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    @Test fun player() = shoot("player") {
        PlayerControls(playerUi(), noPlayer)
    }

    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    @Test fun playerCountdown() = shoot("player_countdown") {
        PlayerControls(playerUi().copy(countdown = 7, nextLabel = "Episode 4", position = 1420.0), noPlayer)
    }

    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    @Test fun playerSignIn() = shoot("player_signin") {
        PlayerControls(
            PlayerUi(
                title = "Hoshi Movie", episode = "Hoshi Movie",
                error = "The user name or password for “Drive B” on MEDIABOX isn't right.", signInShare = "Drive B", signInHost = "MEDIABOX",
            ),
            noPlayer,
        )
    }

    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    @Test fun playerSubtitles() = shoot("player_subtitles", interact = {
        compose.onAllNodesWithContentDescription("Subtitles").onFirst().performClick()
    }) {
        PlayerControls(playerUi(), noPlayer)
    }

    // -- the Shield (960 x 540 dp, remote) ----------------------------------------------------------------

    @Config(qualifiers = TV)
    @Test fun tvHome() = shoot("tv_home", tv = true) {
        androidx.compose.foundation.layout.Row {
            TvMenu(0) {}
            HomeScreen(library, progress, SyncStatus.Done(1_763_900_000_000, library.shows.size, 3, 1), {}, {}, { _, _ -> }, {}, {})
        }
    }

    @Config(qualifiers = TV)
    @Test fun tvLibrary() = shoot("tv_library", tv = true) {
        androidx.compose.foundation.layout.Row {
            TvMenu(1) {}
            LibraryScreen(library, progress, LibraryFilter(kind = Kind.ANIME), {}, {})
        }
    }

    @Config(qualifiers = TV)
    @Test fun tvShow() = shoot("tv_show", tv = true) {
        val s = library.show("Anime\\Kaze no Tabi")!!
        ShowScreen(s, progress, { library.playable(it) }, {}, { _, _ -> }, { _, _ -> })
    }

    @Config(qualifiers = TV)
    @Test fun tvPlayer() = shoot("tv_player", tv = true) {
        PlayerControls(playerUi(), noPlayer)
    }

    @Config(qualifiers = TV)
    @Test fun tvSetup() = shoot("tv_setup", tv = true) {
        SetupScreen(SetupState(), noSetup) { _, _ -> }
    }

    private fun playerUi() = PlayerUi(
        title = "Kaze no Tabi",
        episode = "Episode 3 · The Third Wind",
        position = 612.0,
        duration = 1420.0,
        paused = true,
        controls = true,
        decoder = "no",
        hasPrevious = true,
        hasNext = true,
        tracks = listOf(
            Track(1, "audio", "jpn", "", "aac", true, false, false),
            Track(2, "audio", "ger", "German dub", "ac3", false, false, false),
            Track(1, "sub", "ger", "Full", "ass", true, false, false),
            Track(2, "sub", "ger", "Signs & Songs", "ass", false, false, true),
            Track(3, "sub", "eng", "", "subrip", false, true, false),
        ),
    )

    private fun settingsUi() = SettingsUi(
        target = SyncTarget("MEDIABOX", "Drive A", "Anime/AniBox Sync", "DESKTOP-TEST"),
        status = SyncStatus.Done(1_763_900_000_000, 4, 3, 1),
        pc = "DESKTOP-TEST",
        journal = "progress/android-pixel-8-3fa2.jsonl",
        pending = 2,
        addresses = listOf(HostAddress("MEDIABOX", "192.0.2.10", Found.NETBIOS)),
        hosts = listOf("MEDIABOX"),
        audio = "",
        subs = "",
        mode = "",
        pcPrefs = library.prefs,
        autoplay = true,
        resumeBack = 10,
        hwdec = true,
        version = "0.1.0",
    )

    private val noSetup = object : SetupActions {
        override fun step(step: SetupStep) {}
        override fun back() {}
        override fun qrText(text: String): String? = null
        override fun typed(host: String, share: String, folder: String): String? = null
        override fun connect(user: String, password: String, address: String) {}
    }

    private val noSettings = object : SettingsActions {
        override fun syncNow() {}
        override fun changeFolder() {}
        override fun signInAgain() {}
        override fun setAddress(host: String, address: String): String? = null
        override fun forgetAddress(host: String) {}
        override fun setAudio(text: String) {}
        override fun setSubs(text: String) {}
        override fun setMode(mode: String) {}
        override fun setAutoplay(on: Boolean) {}
        override fun setResumeBack(seconds: Int) {}
        override fun setHwdec(on: Boolean) {}
    }

    private val noPlayer = object : PlayerControlActions {
        override fun togglePause() {}
        override fun seekTo(seconds: Double) {}
        override fun seekBy(seconds: Double) {}
        override fun toggleControls() {}
        override fun showControls() {}
        override fun next() {}
        override fun previous() {}
        override fun playNow() {}
        override fun cancelCountdown() {}
        override fun audio(track: Track) {}
        override fun subtitle(track: Track?) {}
        override fun close() {}
        override fun retry() {}
        override fun signIn(user: String, password: String) {}
    }

    private fun fakeLibrary(): String {
        val sb = StringBuilder()
        sb.append("""{"format":1,"version":"1.15.0","generated":1763900000.5,"pc":"DESKTOP-TEST",""")
        sb.append(""""libraries":[{"name":"Anime","kind":"anime"},{"name":"TV-Shows","kind":"series"},{"name":"Movies","kind":"movies"}],""")
        sb.append(""""shares":[{"id":"s1","host":"MEDIABOX","share":"Drive A","address":"192.0.2.10"}],""")
        sb.append(""""prefs":{"audioLangs":["jpn","ger"],"subLangs":["ger","eng"],"subs":"auto"},"shows":[""")
        val shows = listOf(
            Triple("Anime", "Kaze no Tabi", "tv"), Triple("Anime", "Umi to Sora no Aida ni Saku Hana no Monogatari", "tv"),
            Triple("Anime", "Hoshi Movie", "movie"), Triple("Anime", "Yama OVA", "ova"), Triple("Anime", "Tsuki Special", "special"),
            Triple("Anime", "Kawa", "tv"), Triple("Anime", "Mori no Uta", "tv"), Triple("Anime", "Hikari", "ona"),
            Triple("TV-Shows", "Example Street", ""), Triple("Movies", "The Made-up Film", ""),
        )
        shows.forEachIndexed { i, (lib, name, at) ->
            if (i > 0) sb.append(',')
            val kind = when (lib) { "Anime" -> "anime"; "Movies" -> "movie"; else -> "series" }
            val n = when (at) { "movie", "special" -> 1; "ova" -> 2; else -> if (lib == "Movies") 1 else 6 }
            val poster = if (i % 4 == 3) "" else "%012x-%08x.jpg".format(i * 77777L, i * 999L)
            sb.append("""{"id":"$lib\\$name","library":"$lib","folder":"$name","title":"$name","originalTitle":"${if (i == 0) "風の旅" else ""}",""")
            sb.append(""""englishTitle":"${if (i == 0) "Journey of the Wind" else if (i == 1) "The Flower Between Sea and Sky" else ""}","sortTitle":"","mal_id":"${99900 + i}",""")
            sb.append(""""type":"${at.uppercase()}","kind":"$kind","atype":"$at","year":${2010 + i},"description":"${if (i < 2) "A made-up show for the screenshots. Two friends follow the wind across a country of islands, looking for the place where it starts. On the way they meet a lighthouse keeper, a travelling cook and a cat that knows every shortcut." else ""}",""")
            sb.append(""""genres":["Adventure","Slice of Life","Fantasy"],"studio":"Studio Example","poster":"$poster","episodeLength":1440,"episodes":[""")
            for (e in 1..n) {
                if (e > 1) sb.append(',')
                val file = if (lib == "TV-Shows") "Season 01/Example Street S01E0$e.mkv" else if (name == "Kaze no Tabi") "Kaze no Tabi 0$e.mkv" else if (name.startsWith("Umi")) "Umi 0$e.mkv" else "$name 0$e.mkv"
                val key = "$lib\\$name\\" + file.replace('/', '\\')
                val label = if (lib == "TV-Shows") "S01E0$e" else if (n == 1) name else "Episode $e"
                val title = if (name == "Kaze no Tabi") listOf("The First Wind", "A Lighthouse at Night", "The Third Wind", "", "", "")[e - 1] else ""
                val local = name == "Kaze no Tabi" && e == 6
                sb.append("""{"key":"${key.replace("\\", "\\\\")}","file":"$file","season":null,"number":$e,"label":"$label","title":"$title","duration":${if (e < 4) 1420.0 else "null"},""")
                sb.append(if (local) """"local":true}""" else """"share":"s1","path":"$lib/$name/$file"}""")
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }
}

private const val TV = "w960dp-h540dp-land-television-night-xhdpi"
