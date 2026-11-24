package io.github.patschgoo.anibox.sync

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Small helpers around org.json. Android's own org.json and the plain-Java one used in the JVM tests
 * differ in details (Android's optString() turns a JSON null into the text "null"), so every read
 * here checks for null itself.
 */

internal fun JSONObject.str(name: String): String {
    if (!has(name) || isNull(name)) return ""
    return when (val v = opt(name)) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> ""
    }
}

internal fun JSONObject.strOrNull(name: String): String? = str(name).ifEmpty { null }

internal fun JSONObject.num(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return when (val v = opt(name)) {
        is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> v.trim().toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
        else -> null
    }
}

internal fun JSONObject.int(name: String): Int? = num(name)?.let { if (it in -1e9..1e9) it.toInt() else null }

internal fun JSONObject.bool(name: String): Boolean = has(name) && !isNull(name) && opt(name) == true

internal fun JSONObject.obj(name: String): JSONObject? = if (has(name) && !isNull(name)) opt(name) as? JSONObject else null

internal fun JSONObject.arr(name: String): JSONArray? = if (has(name) && !isNull(name)) opt(name) as? JSONArray else null

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) (opt(i) as? JSONObject)?.let { out += it }
    return out
}

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is String -> if (v.isNotBlank()) out += v
            is Number -> out += v.toString()
        }
    }
    return out
}

/**
 * JSON text the way Python's `json.dumps(..., ensure_ascii=False, separators=(",", ":"))` writes it,
 * so this phone's journal lines are byte for byte what AniBox on the PC writes in pc.jsonl.
 */
internal object PyJson {
    fun string(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** `repr(round(x, places))` in Python: exact half-even rounding of the binary value, always a "." in it. */
    fun number(x: Double, places: Int): String {
        if (x.isNaN() || x.isInfinite()) return "0.0"
        var text = BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
        if (text == "-0") text = "0"
        if (text.startsWith("-0") && text.trimStart('-').all { it == '0' || it == '.' }) text = text.removePrefix("-")
        return if ('.' in text) text else "$text.0"
    }

    /** The value [number] writes, read back (what the PC sees). */
    fun rounded(x: Double, places: Int): Double = number(x, places).toDouble()
}
