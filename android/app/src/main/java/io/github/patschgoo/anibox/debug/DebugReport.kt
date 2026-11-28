package io.github.patschgoo.anibox.debug

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.github.patschgoo.anibox.AppGraph
import io.github.patschgoo.anibox.BuildConfig
import io.github.patschgoo.anibox.graph
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Send debug info": one text file with the phone, the app's settings (never a password or user name),
 * the library's numbers and the log ([DebugLog]), handed to Android's share sheet (mail, Messenger, Drive …).
 */
object DebugReport {
    /** Writes the report into the app's cache and returns it. */
    fun write(context: Context, why: String = ""): File {
        val g = context.graph
        val dir = File(context.cacheDir, "debug").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the newest report is kept
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date())
        val file = File(dir, "anibox-debug-$stamp.txt")
        file.bufferedWriter().use { w ->
            w.appendLine("AniBox for Android — debug info")
            w.appendLine("Made: ${Date()}${if (why.isNotEmpty()) " ($why)" else ""}")
            w.appendLine()
            header(context, g).forEach { w.appendLine(it) }
            w.appendLine()
            w.appendLine("==== Log (older first) ====")
            val files = DebugLog.files()
            if (files.isEmpty()) {
                DebugLog.recent().forEach { w.appendLine(it) }
            } else {
                for (f in files) f.forEachLine { w.appendLine(DebugLog.scrub(it)) }
            }
        }
        DebugLog.i("debug", "Debug info written (${file.length() / 1024} KB)")
        return file
    }

    /** The share sheet for [file]. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.debug.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "AniBox debug info")
            .putExtra(Intent.EXTRA_TEXT, "AniBox for Android ${BuildConfig.VERSION_NAME}: debug info attached (${file.name}).")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, "Send AniBox debug info").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Writes the report and opens the share sheet. */
    fun send(context: Context, why: String = "") {
        val file = write(context, why)
        context.startActivity(shareIntent(context, file).addFlags(if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun header(context: Context, g: AppGraph): List<String> {
        val out = ArrayList<String>()
        out += "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.APPLICATION_ID}"
        out += "Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        out += "Processor: ${Build.SUPPORTED_ABIS.joinToString(", ")}${if (Build.VERSION.SDK_INT >= 31) " · SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""}"
        out += "Network now: ${AppGraph.link(context)}"
        val s = g.settings
        out += "Settings: hardware decoding ${if (s.hardwareDecoding) "on" else "off"}, autoplay ${s.autoplay}, resume ${s.resumeBack} s back"
        // Names and addresses are replaced by placeholders: the report may be sent to someone else.
        val names = Placeholders()
        out += "Sync folder: ${s.target?.let { "\\\\${names.host(it.host)}\\${names.share(it.share)}\\…" } ?: "none"}"
        out += "Saved addresses: ${s.addresses().joinToString { "${names.host(it.host)} = ${maskAddress(it.address)} (${it.how})" }.ifEmpty { "none" }}"
        out += "Last sync: ${g.sync.status.value}"
        out += "Waiting to be sent: ${g.store.pending().size} bytes"
        val lib = g.sync.library.value
        if (lib == null) {
            out += "Library: none yet"
        } else {
            val posters = g.sync.posterDir.list()?.size ?: 0
            val noPoster = lib.shows.filter { it.poster.isEmpty() }
            out += "Library: ${lib.shows.size} shows, ${lib.shows.sumOf { it.episodes.size }} episodes, from the PC (AniBox ${lib.version})"
            out += "Shares: ${lib.shares.joinToString { "${it.id} = \\\\${names.host(it.host)}\\${names.share(it.share)}${if (it.address.isNotEmpty()) " (${maskAddress(it.address)})" else ""}" }}"
            for (sec in lib.libraries) {
                val shows = lib.shows.filter { it.library == sec.name }
                out += "  ${sec.name} (${sec.kind}): ${shows.size} shows, ${shows.count { it.poster.isEmpty() }} without a poster from the PC"
            }
            out += "Posters: ${lib.shows.size - noPoster.size} named in library.json, $posters on this phone, ${g.sync.posterMissCount} couldn't be fetched"
            if (noPoster.isNotEmpty()) out += "  Without a poster (first 15): ${noPoster.take(15).joinToString { it.title }}"
        }
        return out
    }

    /** host1, share1 … in the order they come up, so lines still say which is the same one. */
    class Placeholders {
        private val hosts = LinkedHashMap<String, String>()
        private val shares = LinkedHashMap<String, String>()
        fun host(h: String): String = hosts.getOrPut(h.lowercase()) { "host${hosts.size + 1}" }
        fun share(s: String): String = shares.getOrPut(s.lowercase()) { "share${shares.size + 1}" }
    }

    /** Keeps what kind of network it is (192.168 home, 100.x Tailscale …), not the address itself. */
    fun maskAddress(a: String): String {
        val p = a.split('.')
        return if (p.size == 4) "${p[0]}.${p[1]}.x.x" else if (a.isEmpty()) "" else "an address"
    }
}
