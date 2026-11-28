package io.github.patschgoo.anibox.sync

import org.json.JSONException
import org.json.JSONObject

/** library.json (format 1), as AniBox on the PC writes it (app/anibox/routes_phonesync.py `_ps_library`). */
data class Library(
    val format: Int,
    val version: String,
    val generated: Double,
    val pc: String,
    val libraries: List<LibrarySection>,
    val shares: List<ShareInfo>,
    val prefs: LanguagePrefs?,
    val shows: List<Show>,
    /** Who's watching (profiles without a PIN on the PC; "main" always). Empty from an older PC. */
    val profiles: List<ProfileInfo> = emptyList(),
) {
    /** What [profile] may see: a kids profile only its shows, everyone else everything. */
    fun forProfile(profile: ProfileInfo?): Library =
        if (profile?.shows == null) this else copy(shows = shows.filter { it.id in profile.shows })

    private val showsById: Map<String, Show> by lazy { shows.associateBy { it.id } }
    private val sharesById: Map<String, ShareInfo> by lazy { shares.associateBy { it.id } }
    private val episodeIndex: Map<String, Pair<Show, Int>> by lazy {
        val m = HashMap<String, Pair<Show, Int>>()
        for (s in shows) s.episodes.forEachIndexed { i, e -> m[e.key] = s to i }
        m
    }

    fun show(id: String): Show? = showsById[id]
    fun share(id: String?): ShareInfo? = id?.let { sharesById[it] }
    fun episode(key: String): Pair<Show, Int>? = episodeIndex[key]

    /** True when the phone can open the episode: on a share this library lists (not "only on the PC"). */
    fun playable(e: Episode): Boolean = !e.local && e.path.isNotEmpty() && share(e.share) != null

    fun kindOf(library: String): String = libraries.firstOrNull { it.name == library }?.kind ?: ""
}

/** A profile from the PC. [shows]: the only shows a kids profile may see (null: no limit). */
data class ProfileInfo(val id: String, val name: String, val emoji: String = "", val kids: Boolean = false, val shows: Set<String>? = null)

/** A library as the PC shows it: [kind] is "anime", "series" or "movies". */
data class LibrarySection(val name: String, val kind: String)

/** A share the episodes are on; [address] is the host's IPv4 address as the PC found it (optional). */
data class ShareInfo(val id: String, val host: String, val share: String, val address: String = "")

/** The PC's preferred languages (codes as mpv takes them) and subtitle mode "auto" | "on" | "off". */
data class LanguagePrefs(val audioLangs: List<String>, val subLangs: List<String>, val subs: String)

data class Show(
    val id: String,
    val library: String,
    val folder: String,
    val title: String,
    val originalTitle: String = "",
    val englishTitle: String = "",
    val sortTitle: String = "",
    val malId: String? = null,
    val anilistId: String? = null,
    /** MyAnimeList's type as the PC has it ("TV", "MOVIE", "OVA" …) or the own info's kind. */
    val type: String = "",
    /** "anime", "series" or "movie". */
    val kind: String = "",
    /** For anime: tv, movie, ova, special, ona, music (lower case), else "". */
    val atype: String = "",
    val year: Int? = null,
    val description: String = "",
    val genres: List<String> = emptyList(),
    val studio: String = "",
    val poster: String = "",
    /** MyAnimeList's length of one episode in seconds, if known. */
    val episodeLength: Double? = null,
    /** AniList's wide picture (https), or "". */
    val banner: String = "",
    /** Sound and subtitle languages ("ja", "de" …) and the PC's badges ("DUB DE", "SUB DE·EN"). */
    val audio: List<String> = emptyList(),
    val subs: List<String> = emptyList(),
    val badges: List<String> = emptyList(),
    /** Subtitles some TVs can't show (ASS, PGS, VobSub). */
    val subWarn: Boolean = false,
    /** The PC's "Plays on the TV?" check: "plex"/"castapp" -> "ok", "slow", "convert" … (only what was checked). */
    val tv: Map<String, String> = emptyMap(),
    /** "open", "semi" or "locked": shown only, the phone never changes files. */
    val lock: String = "open",
    val episodes: List<Episode> = emptyList(),
)

/** An opening ("opening") or ending ("ending") from the PC, in seconds. */
data class SkipMark(val kind: String, val start: Double, val end: Double)

data class Episode(
    /** `<library>\<show folder>\<file>` (backslashes), the same key as on the PC. */
    val key: String,
    val file: String,
    val season: Int? = null,
    val number: Double? = null,
    val label: String = "",
    val title: String = "",
    /** Seconds, as AniBox measured it (null when unknown). */
    val duration: Double? = null,
    /** Id from [Library.shares] and the path inside that share (forward slashes). */
    val share: String? = null,
    val path: String = "",
    /** Only on the PC (a local drive there): the phone can't play it. */
    val local: Boolean = false,
    val plot: String = "",
    /** An episode picture (https), or "". */
    val image: String = "",
    /** Openings/endings the PC found by sound. */
    val skip: List<SkipMark> = emptyList(),
) {
    val fileName: String get() = file.substringAfterLast('/')
}

sealed class LibraryResult {
    data class Ok(val library: Library) : LibraryResult()

    /** library.json is newer than this app understands. */
    data class TooNew(val format: Int) : LibraryResult()
    data class Broken(val reason: String) : LibraryResult()
}

object LibraryParser {
    const val FORMAT = 1
    private val posterName = Regex("^[A-Za-z0-9._-]{1,120}$")

    fun parse(text: String): LibraryResult {
        val root = try {
            JSONObject(text.removePrefix("\uFEFF"))
        } catch (e: JSONException) {
            return LibraryResult.Broken("library.json can't be read (${e.message?.take(80)}).")
        }
        val format = root.int("format") ?: return LibraryResult.Broken("library.json has no format number.")
        if (format > FORMAT) return LibraryResult.TooNew(format)
        if (format < 1) return LibraryResult.Broken("library.json has an unknown format ($format).")
        val shares = root.arr("shares").objects().mapNotNull { s ->
            val id = s.str("id")
            if (id.isEmpty()) null else ShareInfo(id, s.str("host"), s.str("share"), s.str("address").takeIf { QrCode.isIpv4(it) } ?: "")
        }
        val prefs = root.obj("prefs")?.let { p ->
            LanguagePrefs(
                audioLangs = p.arr("audioLangs").strings().map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                subLangs = p.arr("subLangs").strings().map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                subs = p.str("subs").takeIf { it in setOf("auto", "on", "off") } ?: "auto",
            )
        }
        val shows = root.arr("shows").objects().mapNotNull { parseShow(it) }
        return LibraryResult.Ok(
            Library(
                format = format,
                version = root.str("version"),
                generated = root.num("generated") ?: 0.0,
                pc = root.str("pc"),
                libraries = root.arr("libraries").objects().mapNotNull { l ->
                    l.str("name").takeIf { it.isNotEmpty() }?.let { LibrarySection(it, l.str("kind")) }
                },
                shares = shares,
                prefs = prefs,
                shows = shows,
                profiles = root.arr("profiles").objects().mapNotNull { p ->
                    val id = p.str("id").take(60)
                    if (id.isEmpty()) null else ProfileInfo(
                        id, p.str("name").ifEmpty { id }, p.str("emoji"), p.bool("kids"),
                        if (p.bool("kids")) p.arr("shows").strings().toSet() else null,
                    )
                },
            ),
        )
    }

    private fun parseShow(o: JSONObject): Show? {
        val id = o.str("id")
        if (id.isEmpty()) return null
        val folder = o.str("folder").ifEmpty { id.substringAfterLast('\\') }
        val episodes = o.arr("episodes").objects().mapNotNull { parseEpisode(it) }
        val year = o.num("year")?.toInt()?.takeIf { it in 1800..3000 }
        return Show(
            id = id,
            library = o.str("library").ifEmpty { id.substringBefore('\\') },
            folder = folder,
            title = o.str("title").ifEmpty { folder },
            originalTitle = o.str("originalTitle"),
            englishTitle = o.str("englishTitle"),
            sortTitle = o.str("sortTitle"),
            malId = o.strOrNull("mal_id"),
            anilistId = o.strOrNull("anilist_id"),
            type = o.str("type"),
            kind = o.str("kind"),
            atype = o.str("atype").lowercase(),
            year = year,
            description = o.str("description"),
            genres = o.arr("genres").strings(),
            studio = o.str("studio"),
            poster = o.str("poster").takeIf { posterName.matches(it) && !it.startsWith(".") } ?: "",
            episodeLength = o.num("episodeLength")?.takeIf { it > 0 },
            banner = o.str("banner").takeIf { it.startsWith("https://") } ?: "",
            audio = o.arr("audio").strings(),
            subs = o.arr("subs").strings(),
            badges = o.arr("badges").strings(),
            subWarn = o.bool("subWarn"),
            tv = o.obj("tv")?.let { t -> t.keys().asSequence().associateWith { t.optString(it) }.filterValues { it.isNotEmpty() } } ?: emptyMap(),
            lock = o.str("lock").takeIf { it in setOf("open", "semi", "locked") } ?: "open",
            episodes = episodes,
        )
    }

    private fun parseEpisode(o: JSONObject): Episode? {
        val key = o.str("key")
        if (key.isEmpty()) return null
        val local = o.bool("local")
        return Episode(
            key = key,
            file = o.str("file").ifEmpty { key.substringAfterLast('\\') },
            season = o.num("season")?.toInt(),
            number = o.num("number"),
            label = o.str("label").ifEmpty { o.str("file").substringAfterLast('/') },
            title = o.str("title"),
            duration = o.num("duration")?.takeIf { it > 0 },
            share = if (local) null else o.strOrNull("share"),
            path = if (local) "" else SyncTarget.filePath(o.str("path")),
            local = local,
            plot = o.str("plot"),
            image = o.str("image").takeIf { it.startsWith("https://") } ?: "",
            skip = o.arr("skip")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val m = a.optJSONArray(i) ?: return@mapNotNull null
                    val start = m.optDouble(1, Double.NaN)
                    val end = m.optDouble(2, Double.NaN)
                    if (m.length() < 3 || start.isNaN() || end.isNaN() || end <= start) null
                    else SkipMark(m.optString(0), start, end)
                }
            } ?: emptyList(),
        )
    }
}
