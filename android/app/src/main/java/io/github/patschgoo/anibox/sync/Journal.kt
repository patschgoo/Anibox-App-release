package io.github.patschgoo.anibox.sync

import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/** One line of a progress journal (progress/pc.jsonl or a device's own file). */
data class ProgressLine(
    val key: String,
    val position: Double,
    val duration: Double,
    val watched: Boolean,
    /** Unix seconds (with decimals) on the device that made the change. */
    val updated: Double,
    val profile: String = MAIN,
    /** Un-marked by hand at [updated] (the PC writes `"unmarked":true`): a newer line like this takes the mark away. */
    val unmarked: Boolean = false,
) {
    companion object {
        const val MAIN = "main"
    }
}

/**
 * The journal line format, the same as AniBox on the PC (app/anibox/phonesync.py `progress_lines` and
 * `parse_line`): compact JSON, keys in this order, position and duration to 0.1 s, updated to 1 ms,
 * UTF-8, every line ending with "\n".
 */
object JournalFormat {
    const val MAX_LINE = 4096
    const val MAX_KEY = 600
    const val FUTURE_SLACK = 600.0

    fun encode(e: ProgressLine): String =
        "{\"key\":" + PyJson.string(e.key) +
            ",\"position\":" + PyJson.number(maxOf(0.0, e.position), 1) +
            ",\"duration\":" + PyJson.number(maxOf(0.0, e.duration), 1) +
            ",\"watched\":" + (if (e.watched) "true" else "false") +
            ",\"updated\":" + PyJson.number(e.updated, 3) +
            ",\"profile\":" + PyJson.string(e.profile.ifEmpty { ProgressLine.MAIN }) +
            // only when set, so every other line stays byte for byte what the PC writes
            (if (e.unmarked && !e.watched) ",\"unmarked\":true" else "") + "}"

    fun encodeLine(e: ProgressLine): ByteArray = (encode(e) + "\n").toByteArray(Charsets.UTF_8)

    /**
     * One line → entry, or null, with the PC's rules: at most 4096 characters, a key of 1–600
     * characters, numbers that aren't numbers make the line invalid, milliseconds are recognised,
     * a time far in the future counts as [now], only `true` counts as watched.
     */
    fun decode(line: String, now: Double): ProgressLine? {
        if (line.length > MAX_LINE) return null
        val o = try {
            JSONObject(line)
        } catch (_: JSONException) {
            return null
        }
        val key = o.opt("key") as? String ?: return null
        if (key.isEmpty() || key.length > MAX_KEY) return null
        val updatedRaw = number(o, "updated") ?: return null
        val position = number(o, "position") ?: return null
        val duration = number(o, "duration") ?: return null
        var updated = if (updatedRaw > 1e11) updatedRaw / 1000.0 else updatedRaw
        if (updated <= 0) return null
        if (updated > now + FUTURE_SLACK) updated = now
        val profile = if (o.has("profile") && !o.isNull("profile")) (o.opt("profile")?.toString() ?: "").take(60) else ""
        val watched = o.opt("watched") == true
        return ProgressLine(key, maxOf(0.0, position), maxOf(0.0, duration), watched, updated, profile, unmarked = !watched && o.opt("unmarked") == true)
    }

    /** Like Python's `float(d.get(name) or 0)`: missing/null/false/0/"" → 0; a number or numeric text → it; else invalid. */
    private fun number(o: JSONObject, name: String): Double? {
        if (!o.has(name) || o.isNull(name)) return 0.0
        return when (val v = o.opt(name)) {
            is Boolean -> if (v) 1.0 else 0.0
            is Number -> v.toDouble().takeIf { !it.isNaN() }
            is String -> if (v.isEmpty()) 0.0 else v.trim().toDoubleOrNull()
            else -> null
        }
    }

    /** All the complete lines of [data] (an unfinished last line is left out), decoded. */
    fun decodeAll(data: ByteArray, now: Double): List<ProgressLine> {
        val end = data.lastIndexOf('\n'.code.toByte()) + 1
        if (end <= 0) return emptyList()
        return String(data, 0, end, Charsets.UTF_8).split('\n').mapNotNull { l -> l.trim().takeIf { it.isNotEmpty() }?.let { decode(it, now) } }
    }
}

/** How far another device's journal was read, as on the PC (`read_new_lines`): byte offset + start fingerprint. */
data class Seen(val offset: Long, val head: String)

object JournalReader {
    /** A fingerprint of the file's start (up to where it was read), to notice a file written anew. */
    fun head(data: ByteArray, offset: Long): String {
        val n = minOf(256L, offset, data.size.toLong()).toInt()
        val sha = MessageDigest.getInstance("SHA-1").digest(data.copyOfRange(0, n))
        return sha.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * The complete lines added since [seen]. A file that got shorter or whose start changed (the device
     * wrote it anew) is read from the beginning; an unfinished last line waits for the next time.
     */
    fun newLines(whole: ByteArray, seen: Seen?): Pair<List<String>, Seen> {
        var offset = maxOf(0L, seen?.offset ?: 0L)
        if (offset > 0 && (offset > whole.size || seen?.head != head(whole, offset))) offset = 0
        val end = whole.lastIndexOf('\n'.code.toByte()) + 1
        if (end <= offset) return emptyList<String>() to Seen(offset, head(whole, offset))
        val lines = String(whole, offset.toInt(), (end - offset).toInt(), Charsets.UTF_8).split('\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
        return lines to Seen(end.toLong(), head(whole, end.toLong()))
    }
}
