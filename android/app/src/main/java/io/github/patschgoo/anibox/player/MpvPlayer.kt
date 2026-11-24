package io.github.patschgoo.anibox.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import io.github.patschgoo.anibox.R
import java.io.File
import java.util.Locale

/** One audio or subtitle track as mpv lists it. */
data class Track(
    val id: Int,
    val type: String,
    val lang: String,
    val title: String,
    val codec: String,
    val selected: Boolean,
    val external: Boolean,
    val forced: Boolean,
) {
    val label: String
        get() {
            val name = if (lang.isNotEmpty()) Languages.name(lang) else ""
            val parts = listOf(name, title).filter { it.isNotEmpty() }.distinct()
            val text = parts.joinToString(" · ").ifEmpty { "Track $id" }
            val extra = listOfNotNull(codecName(codec), if (external) "file" else null, if (forced) "signs" else null)
            return if (extra.isEmpty()) text else "$text (${extra.joinToString(", ")})"
        }
}

/** mpv's codec names as people know them. */
private fun codecName(codec: String): String? = when (codec.lowercase(Locale.ROOT)) {
    "" -> null
    "subrip", "srt" -> "SRT"
    "ass", "ssa" -> "ASS"
    "webvtt" -> "WebVTT"
    "hdmv_pgs_subtitle", "pgssub" -> "PGS"
    "dvd_subtitle", "dvdsub" -> "VobSub"
    "mov_text" -> "MP4 text"
    "eac3" -> "E-AC-3"
    "ac3" -> "AC-3"
    "truehd" -> "TrueHD"
    else -> codec.uppercase(Locale.ROOT)
}

/** What the player shows: where it is, and whether it plays. */
data class MpvState(
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val eof: Boolean = false,
    val idle: Boolean = true,
    /** The address of the file mpv has open now ("" while none). */
    val path: String = "",
)

/**
 * libmpv (mpv + FFmpeg, from github.com/jarnedemeulemeester/libmpv-android, the build Findroid uses)
 * with options like mpv-android / Findroid: GPU output on Android's surface, hardware decoding by
 * MediaCodec when the phone has a decoder for the file (Hi10P/10-bit H.264 usually has none: mpv then
 * decodes in software by itself), Android's AudioTrack for sound.
 *
 * All calls go to one mpv instance; it's thread safe. Callbacks come on mpv's own thread.
 */
class MpvPlayer(context: Context, hardwareDecoding: Boolean, private val events: Events) {
    interface Events {
        fun fileLoaded()
        fun endFile(error: Boolean)
    }

    private val mpv: MPVLib = MPVLib.create(context.applicationContext) ?: throw IllegalStateException("libmpv couldn't start")
    @Volatile var surfaceReady = false
        private set
    @Volatile private var destroyed = false

    init {
        copyFonts(context)
        val dir = File(context.filesDir, "mpv")
        val fonts = File(dir, "fonts")
        val opts = linkedMapOf(
            "config" to "yes",
            "config-dir" to dir.path,
            "sub-fonts-dir" to fonts.path,
            "osd-fonts-dir" to fonts.path,
            "vo" to "gpu",
            "gpu-context" to "android",
            "opengl-es" to "yes",
            "hwdec" to if (hardwareDecoding) "mediacodec,mediacodec-copy" else "no",
            "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
            "ao" to "audiotrack,opensles",
            "audio-channels" to "auto-safe",
            "force-window" to "no",
            "idle" to "yes",
            "keep-open" to "yes",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "osc" to "no",
            "load-scripts" to "no",
            "ytdl" to "no",
            "save-position-on-quit" to "no",
            "sub-auto" to "no",
            "audio-file-auto" to "no",
            "sub-scale-with-window" to "yes",
            "embeddedfonts" to "yes",
            "cache" to "yes",
            "demuxer-max-bytes" to "64MiB",
            "demuxer-max-back-bytes" to "32MiB",
            "network-timeout" to "60",
            "hr-seek" to "yes",
            "terminal" to "no",
        )
        for ((k, v) in opts) mpv.setOptionString(k, v)
        mpv.init()
        mpv.addObserver(object : MPVLib.EventObserver {
            override fun event(eventId: Int) {
                if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) events.fileLoaded()
            }

            override fun eventEndFile(reason: Int, error: Int) {
                if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR) events.endFile(true)
            }
        })
    }

    /** A font for plain subtitles (SRT) and styled ones without their own fonts: Inter (OFL), from the app's fonts. */
    @SuppressLint("ResourceType") // a font resource is a plain file, readable like a raw one
    private fun copyFonts(context: Context) {
        val dir = File(context.filesDir, "mpv").apply { mkdirs() }
        val fonts = File(dir, "fonts").apply { mkdirs() }
        for (target in listOf(File(dir, "subfont.ttf"), File(fonts, "Inter-Regular.ttf"))) {
            if (target.isFile) continue
            try {
                context.resources.openRawResource(R.font.inter_regular).use { input -> target.outputStream().use { input.copyTo(it) } }
            } catch (_: Exception) {
            }
        }
        File(dir, "mpv.conf").takeIf { !it.exists() }?.writeText("# AniBox sets its options itself.\n")
    }

    // -- the picture ----------------------------------------------------------------------------------

    fun attach(surface: Surface) {
        if (destroyed) return
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
        surfaceReady = true
    }

    fun surfaceSize(width: Int, height: Int) {
        if (!destroyed) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    fun detach() {
        if (destroyed) return
        surfaceReady = false
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    // -- playing ----------------------------------------------------------------------------------------

    /** Opens [url] at [start] seconds with [subs] (external subtitle addresses) and the language choice. */
    fun load(url: String, start: Double, subs: List<String>, title: String, choice: TrackChoice) {
        if (destroyed) return
        mpv.setPropertyString("alang", choice.alang)
        mpv.setPropertyString("slang", choice.slang)
        mpv.setPropertyString("sid", choice.sid)
        mpv.setPropertyString("aid", "auto")
        mpv.setPropertyString("subs-fallback", choice.subsFallback)
        mpv.command(arrayOf("change-list", "sub-files", "clr", ""))
        for (s in subs) mpv.command(arrayOf("change-list", "sub-files", "append", s))
        mpv.setPropertyString("start", if (start > 0) String.format(Locale.ROOT, "%.1f", start) else "none")
        mpv.setPropertyString("force-media-title", title)
        mpv.setPropertyBoolean("pause", false)
        mpv.command(arrayOf("loadfile", url, "replace"))
    }

    fun stop() {
        if (!destroyed) mpv.command(arrayOf("stop"))
    }

    fun setPaused(paused: Boolean) {
        if (!destroyed) mpv.setPropertyBoolean("pause", paused)
    }

    fun seekTo(seconds: Double) {
        if (!destroyed) mpv.command(arrayOf("seek", String.format(Locale.ROOT, "%.2f", maxOf(0.0, seconds)), "absolute"))
    }

    fun seekBy(seconds: Double) {
        if (!destroyed) mpv.command(arrayOf("seek", String.format(Locale.ROOT, "%.2f", seconds), "relative"))
    }

    fun setAudio(id: Int) {
        if (!destroyed) mpv.setPropertyString("aid", id.toString())
    }

    /** A subtitle track, or null = off. */
    fun setSub(id: Int?) {
        if (!destroyed) mpv.setPropertyString("sid", id?.toString() ?: "no")
    }

    fun state(): MpvState {
        if (destroyed) return MpvState()
        return MpvState(
            position = mpv.getPropertyDouble("time-pos") ?: 0.0,
            duration = mpv.getPropertyDouble("duration") ?: 0.0,
            paused = mpv.getPropertyBoolean("pause") ?: false,
            buffering = (mpv.getPropertyBoolean("paused-for-cache") ?: false) || (mpv.getPropertyBoolean("seeking") ?: false),
            eof = mpv.getPropertyBoolean("eof-reached") ?: false,
            idle = mpv.getPropertyBoolean("idle-active") ?: true,
            path = mpv.getPropertyString("path") ?: "",
        )
    }

    fun tracks(): List<Track> {
        if (destroyed) return emptyList()
        val n = mpv.getPropertyInt("track-list/count") ?: 0
        val out = ArrayList<Track>()
        for (i in 0 until n) {
            val type = mpv.getPropertyString("track-list/$i/type") ?: continue
            if (type != "audio" && type != "sub") continue
            out += Track(
                id = mpv.getPropertyInt("track-list/$i/id") ?: continue,
                type = type,
                lang = mpv.getPropertyString("track-list/$i/lang") ?: "",
                title = mpv.getPropertyString("track-list/$i/title") ?: "",
                codec = mpv.getPropertyString("track-list/$i/codec") ?: "",
                selected = mpv.getPropertyBoolean("track-list/$i/selected") ?: false,
                external = mpv.getPropertyBoolean("track-list/$i/external") ?: false,
                forced = mpv.getPropertyBoolean("track-list/$i/forced") ?: false,
            )
        }
        return out
    }

    /** Which decoder mpv uses now ("mediacodec", "mediacodec-copy" or "no" = software), for the info line. */
    fun decoder(): String = if (destroyed) "" else mpv.getPropertyString("hwdec-current") ?: ""

    fun destroy() {
        if (destroyed) return
        destroyed = true
        try {
            mpv.destroy()
        } catch (_: Exception) {
        }
    }
}
