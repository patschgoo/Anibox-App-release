package io.github.patschgoo.anibox.debug

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's own log, for "Send debug info": what the sync, the shares, the player's little web server and
 * mpv itself said, with times. Kept in memory (the last [MEMORY] lines) and in `files/logs/anibox.log`
 * (two files of [FILE_LIMIT] bytes each, so the lines before a crash or a restart are still there).
 *
 * Never logs passwords, user names or the player server's secret token (see [scrub]). Plain Kotlin (no
 * Android), so the JVM tests can use it; without [init] it only keeps lines in memory.
 */
object DebugLog {
    const val MEMORY = 6000
    const val FILE_LIMIT = 1_000_000L

    private val lines = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "anibox-log").apply { isDaemon = true } }
    @Volatile private var dir: File? = null
    private val secrets = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val time = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT) }

    /** Starts writing to `<dir>/anibox.log` (and reads nothing back: the files are added to the report as they are). */
    fun init(logDir: File) {
        logDir.mkdirs()
        dir = logDir
    }

    /** A text that must never appear in the log (e.g. the player server's token): replaced by "…". */
    fun secret(text: String) {
        if (text.length >= 6) secrets += text
    }

    fun i(tag: String, message: String) = add('I', tag, message)
    fun w(tag: String, message: String, e: Throwable? = null) = add('W', tag, message + describe(e))
    fun e(tag: String, message: String, e: Throwable? = null) = add('E', tag, message + describe(e))

    /** mpv's own lines ("[ffmpeg] …", "[vo/gpu] …"), level letter as mpv gives it. */
    fun mpv(level: Char, prefix: String, text: String) = add(level, "mpv", "[$prefix] ${text.trimEnd()}")

    /** "": no exception; else the exception chain, class and message of each (at most 6), no stack. */
    fun describe(e: Throwable?): String {
        if (e == null) return ""
        val sb = StringBuilder()
        var c: Throwable? = e
        var n = 0
        while (c != null && n++ < 6) {
            sb.append(if (n == 1) " — " else " ← ").append(c.javaClass.simpleName)
            c.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it.take(300)) }
            c = c.cause
        }
        return sb.toString()
    }

    fun scrub(text: String): String {
        var t = text
        for (s in secrets) if (t.contains(s)) t = t.replace(s, "…")
        return t
    }

    private fun add(level: Char, tag: String, message: String) {
        val line = "${time.get().format(Date())} $level $tag: ${scrub(message)}"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MEMORY) lines.removeFirst()
        }
        val d = dir ?: return
        try {
            writer.execute { write(d, line) }
        } catch (_: Exception) {
        }
    }

    private fun write(d: File, line: String) {
        try {
            val f = File(d, "anibox.log")
            if (f.length() > FILE_LIMIT) {
                val old = File(d, "anibox.1.log")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        } catch (_: IOException) {
        }
    }

    /** The lines kept in memory (this run). */
    fun recent(): List<String> = synchronized(lines) { lines.toList() }

    /** Both log files, older first, as they are on disk now (waits for the lines still being written). */
    fun files(): List<File> {
        val d = dir ?: return emptyList()
        try {
            writer.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return listOf(File(d, "anibox.1.log"), File(d, "anibox.log")).filter { it.isFile }
    }
}
