package io.github.patschgoo.anibox.sync

import org.json.JSONException
import org.json.JSONObject

/**
 * Where the "AniBox Sync" folder is: [host] and [share] as AniBox on the PC sees them, [path] the folder
 * itself relative to the share's top folder (forward slashes), [pc] the computer that writes it.
 * [address] is the IPv4 address the PC found for [host] (newer AniBox versions put it in the QR code).
 */
data class SyncTarget(
    val host: String,
    val share: String,
    val path: String,
    val pc: String = "",
    val address: String = "",
) {
    /** How people know the place from Windows: \\HOST\Share\folder. */
    val display: String get() = "\\\\$host\\$share" + if (path.isEmpty()) "" else "\\" + path.replace('/', '\\')

    companion object {
        const val SYNC_DIR = "AniBox Sync"
        const val QR_TYPE = "anibox-sync"

        /** The folder the user typed: like the PC, "Anime" means "Anime/AniBox Sync"; "…/AniBox Sync" stays. */
        fun syncFolder(typed: String): String {
            val parts = cleanPath(typed)
            if (parts.isEmpty()) return SYNC_DIR
            return if (parts.substringAfterLast('/').equals(SYNC_DIR, ignoreCase = true)) parts else "$parts/$SYNC_DIR"
        }

        fun cleanPath(path: String): String =
            path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }
}

/** What reading a QR code (or pasted text) gave. */
sealed class QrResult {
    data class Ok(val target: SyncTarget) : QrResult()
    data class Bad(val message: String) : QrResult()
}

object QrCode {
    private val ipv4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

    fun isIpv4(text: String): Boolean = ipv4.matches(text.trim())

    /**
     * The QR code's text: `{"v":1,"type":"anibox-sync","host","share","path","pc"}` (+ optional
     * `"address"`). Also takes a share path pasted by hand: `\\HOST\Share\folder` or `smb://HOST/Share/folder`.
     */
    fun parse(text: String): QrResult {
        val t = text.trim().removePrefix("\uFEFF")
        if (t.isEmpty()) return QrResult.Bad("There's nothing to read. Scan the code again or paste its text.")
        if (t.startsWith("\\\\") || t.startsWith("//") || t.startsWith("smb://", ignoreCase = true)) return fromSharePath(t)
        val o = try {
            JSONObject(t)
        } catch (_: JSONException) {
            return QrResult.Bad("That isn't AniBox's code. Open AniBox on the PC: Settings → Phone app shows the right one.")
        }
        if (o.str("type") != QR_TYPE_NAME) {
            return QrResult.Bad("That isn't AniBox's code. Open AniBox on the PC: Settings → Phone app shows the right one.")
        }
        val v = o.int("v") ?: 1
        if (v > 1) return QrResult.Bad("This code comes from a newer AniBox on the PC. Update this app, then scan it again.")
        val host = o.str("host").trim().trim('\\', '/')
        val share = o.str("share").trim().trim('\\', '/')
        if (host.isEmpty() || share.isEmpty()) {
            return QrResult.Bad("The code has no share in it. On the PC, pick the sync folder on one of the Shield's drives first.")
        }
        val path = SyncTarget.cleanPath(o.str("path"))
        val address = o.str("address").trim().takeIf { isIpv4(it) } ?: ""
        return QrResult.Ok(SyncTarget(host, share, path, o.str("pc").trim(), address))
    }

    private const val QR_TYPE_NAME = SyncTarget.QR_TYPE

    private fun fromSharePath(t: String): QrResult {
        val rest = t.removePrefix("smb://").removePrefix("SMB://").replace('\\', '/').trimStart('/')
        val parts = rest.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return QrResult.Bad("Type the whole path, like \\\\MEDIABOX\\Drive A\\AniBox Sync.")
        return QrResult.Ok(SyncTarget(parts[0], parts[1], SyncTarget.syncFolder(parts.drop(2).joinToString("/"))))
    }
}
