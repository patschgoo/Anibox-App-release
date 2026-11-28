package io.github.patschgoo.anibox.player

/** Language codes and which tracks to pick, the same way AniBox on the PC does it (player.py). */
object Languages {
    private val langRe = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})?$", RegexOption.IGNORE_CASE)

    /** Words and codes → two letters (app/anibox/languages.py `LANG`). */
    val TWO: Map<String, String> = mapOf(
        "ger" to "de", "deu" to "de", "de" to "de", "german" to "de", "deutsch" to "de",
        "jpn" to "ja", "ja" to "ja", "jp" to "ja", "japanese" to "ja",
        "eng" to "en", "en" to "en", "english" to "en",
        "fre" to "fr", "fra" to "fr", "fr" to "fr", "spa" to "es", "es" to "es",
        "ita" to "it", "it" to "it", "por" to "pt", "pt" to "pt", "rus" to "ru", "ru" to "ru",
        "chi" to "zh", "zho" to "zh", "zh" to "zh", "kor" to "ko", "ko" to "ko", "ara" to "ar", "ar" to "ar",
    )

    /** Every code a file may use for one language (mpv compares the codes as they are). */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "de" to listOf("ger", "deu", "de"), "ja" to listOf("jpn", "ja", "jp"), "en" to listOf("eng", "en"),
        "fr" to listOf("fre", "fra", "fr"), "es" to listOf("spa", "es"), "it" to listOf("ita", "it"),
        "pt" to listOf("por", "pt"), "ru" to listOf("rus", "ru"), "zh" to listOf("chi", "zho", "zh"),
        "ko" to listOf("kor", "ko"), "ar" to listOf("ara", "ar"),
    )

    val NAMES: Map<String, String> = mapOf(
        "de" to "German", "ja" to "Japanese", "en" to "English", "fr" to "French", "es" to "Spanish", "it" to "Italian",
        "pt" to "Portuguese", "ru" to "Russian", "zh" to "Chinese", "ko" to "Korean", "ar" to "Arabic",
    )

    /** "jpn, ger" → ["jpn", "ger"]; anything that isn't a language code is dropped (player.py `clean_langs`). */
    fun clean(text: String): List<String> =
        text.split(Regex("[,;\\s]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() && langRe.matches(it) }.distinct()

    fun two(code: String): String {
        val c = code.trim().lowercase().replace('_', '-')
        if (c.isEmpty() || c in setOf("und", "zxx", "mis", "mul")) return ""
        val base = c.substringBefore('-')
        return TWO[c] ?: TWO[base] ?: base.take(3)
    }

    /** The codes as written, each followed by its other spellings ("ger" → ger, deu, de). */
    fun expand(codes: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (c in codes) {
            out += c
            SYNONYMS[two(c)]?.let { out += it }
        }
        return out.toList()
    }

    fun name(code: String): String = NAMES[two(code)] ?: code
}

/** What mpv is told before a file opens: `alang`, `slang`, `sid`, `subs-fallback` (player.py `_apply_track_prefs`). */
data class TrackChoice(val alang: String, val slang: String, val sid: String, val subsFallback: String)

data class ShowLanguage(val audio: String = "", val sub: String = "")

object TrackPrefs {
    /**
     * [show]: this show's last pick on this phone (sub "no" = subtitles off), then the preferred
     * languages, then the subtitle mode (auto | on | off).
     */
    fun choose(audioLangs: List<String>, subLangs: List<String>, mode: String, show: ShowLanguage = ShowLanguage()): TrackChoice {
        val alang = Languages.clean((listOf(show.audio) + audioLangs).filter { it.isNotEmpty() }.joinToString(","))
        val subPref = show.sub
        val slang = Languages.clean((listOf(if (subPref == "no") "" else subPref) + subLangs).filter { it.isNotEmpty() }.joinToString(","))
        return TrackChoice(
            alang = Languages.expand(alang).joinToString(","),
            slang = Languages.expand(slang).joinToString(","),
            sid = if (mode == "off" || subPref == "no") "no" else "auto",
            subsFallback = if (mode == "on") "yes" else "default",
        )
    }
}

/** A subtitle file next to the episode. */
data class Sidecar(val name: String, val path: String, val lang: String, val forced: Boolean)

/** Subtitle files that go with an episode (app/anibox/tv.py `find_subtitles`, plus PGS .sup files). */
object Sidecars {
    val EXTS = setOf("srt", "vtt", "ass", "ssa", "sup")
    val VIDEO_EXTS = setOf("mkv", "mp4", "m4v", "avi", "webm", "ts", "m2ts", "mts", "mov", "wmv", "flv", "mpg", "mpeg", "ogv")
    private val forcedWords = setOf("forced", "signs", "sign", "songs", "sns")

    /** "Ep 1.en" and "Ep 1 [eng]" go with "Ep 1"; "Ep 10" doesn't. */
    fun belongs(subStem: String, videoStem: String): Boolean {
        if (videoStem.isEmpty() || subStem.length < videoStem.length || !subStem.regionMatches(0, videoStem, 0, videoStem.length, ignoreCase = true)) return false
        return subStem.length == videoStem.length || !subStem[videoStem.length].isLetterOrDigit()
    }

    /** (language as two letters or "", signs-only?) from the part after the video's name. */
    fun info(name: String, videoStem: String): Pair<String, Boolean> {
        val stem = name.substringBeforeLast('.')
        val rest = if (stem.startsWith(videoStem, ignoreCase = true)) stem.substring(videoStem.length) else stem
        val tokens = rest.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }.map { it.lowercase() }
        val code = tokens.firstNotNullOfOrNull { Languages.TWO[it] } ?: ""
        return code to tokens.any { it in forcedWords }
    }

    /**
     * The sidecars of [videoFile] among [names] (the folder's files), best first: the wanted languages
     * in order, then files without a language, then others; full before signs-only; text before ASS.
     */
    fun find(videoFile: String, names: List<String>, folder: String, langs: List<String>): List<Sidecar> {
        val videoStem = videoFile.substringBeforeLast('.')
        val stems = names.filter { it.substringAfterLast('.', "").lowercase() in VIDEO_EXTS }.map { it.substringBeforeLast('.') }
        val want = langs.map { Languages.two(it) }.filter { it.isNotEmpty() }.distinct()
        val picked = names.filter { n ->
            val ext = n.substringAfterLast('.', "").lowercase()
            val stem = n.substringBeforeLast('.')
            ext in EXTS && belongs(stem, videoStem) && stems.none { v -> v.length > videoStem.length && belongs(stem, v) }
        }
        return picked.map { n ->
            val (code, forced) = info(n, videoStem)
            Sidecar(n, if (folder.isEmpty()) n else "$folder/$n", code, forced)
        }.sortedWith(
            compareBy<Sidecar>(
                { s -> want.indexOf(s.lang).takeIf { it >= 0 } ?: (want.size + if (s.lang.isEmpty()) 0 else 1) },
                { it.forced },
                { !it.name.substringBeforeLast('.').equals(videoStem, ignoreCase = true) },
                { it.name.substringAfterLast('.').lowercase() in setOf("ass", "ssa") },
            ).thenComparator { a, b -> io.github.patschgoo.anibox.net.NaturalOrder.compare(a.name, b.name) },
        )
    }

    /** The three-letter code mpv gets for a sidecar ("de" → "ger"), or "". */
    fun mpvLang(two: String): String = when (two) {
        "" -> ""
        "de" -> "ger"
        "ja" -> "jpn"
        "en" -> "eng"
        "fr" -> "fre"
        "es" -> "spa"
        "it" -> "ita"
        "pt" -> "por"
        "ru" -> "rus"
        "zh" -> "chi"
        "ko" -> "kor"
        "ar" -> "ara"
        else -> two
    }
}
