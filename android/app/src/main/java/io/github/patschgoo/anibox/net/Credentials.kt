package io.github.patschgoo.anibox.net

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class SignIn(val user: String, val password: String, val domain: String = "") {
    override fun toString(): String = "SignIn(user=$user, password=…)" // never in a log
}

/**
 * The shares' user names and passwords, on this phone only: one sign-in per host (used for every share
 * on it), plus a share's own when that share refused the host's. The whole file (`signin.json` in the
 * app's own storage) is encrypted with a Keystore key ([SealedText]) and left out of Android's backups;
 * nothing of it ever goes into the sync folder or a log.
 */
class Credentials(
    private val file: File,
    private val seal: (String) -> String? = { SealedText.seal(it) },
    private val open: (String) -> String? = { SealedText.open(it) },
) {
    private val lock = Any()
    private var hosts = HashMap<String, SignIn>()
    private var shares = HashMap<String, SignIn>()

    /** False when a saved file couldn't be decrypted (e.g. this app's data came from another phone). */
    var readable = true
        private set

    init {
        load()
    }

    private fun hostKey(host: String) = host.trim().lowercase()
    private fun shareKey(host: String, share: String) = hostKey(host) + "\\" + share.trim().lowercase()

    /** The share's own sign-in, else the host's, else null. */
    fun forShare(host: String, share: String): SignIn? = synchronized(lock) { shares[shareKey(host, share)] ?: hosts[hostKey(host)] }

    fun forHost(host: String): SignIn? = synchronized(lock) { hosts[hostKey(host)] }

    fun hasOwn(host: String, share: String): Boolean = synchronized(lock) { shareKey(host, share) in shares }

    fun setHost(host: String, signIn: SignIn): Boolean = synchronized(lock) {
        hosts[hostKey(host)] = signIn
        save()
    }

    fun setShare(host: String, share: String, signIn: SignIn): Boolean = synchronized(lock) {
        shares[shareKey(host, share)] = signIn
        save()
    }

    /** "Sign in again": forgets every saved sign-in. */
    fun clear() = synchronized(lock) {
        hosts.clear()
        shares.clear()
        file.delete()
        readable = true
    }

    private fun save(): Boolean {
        val o = JSONObject()
        o.put("hosts", JSONObject().apply { hosts.forEach { (k, v) -> put(k, v.toJson()) } })
        o.put("shares", JSONObject().apply { shares.forEach { (k, v) -> put(k, v.toJson()) } })
        val sealed = seal(o.toString()) ?: return false // never kept in plain text
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        return try {
            FileOutputStream(tmp).use { it.write(sealed.toByteArray(Charsets.UTF_8)) }
            tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    private fun load() {
        val stored = try { file.takeIf { it.isFile }?.readText(Charsets.UTF_8) } catch (_: IOException) { null } ?: return
        val plain = open(stored.trim())
        if (plain == null) {
            readable = false
            return
        }
        try {
            val o = JSONObject(plain)
            hosts = read(o.optJSONObject("hosts"))
            shares = read(o.optJSONObject("shares"))
        } catch (_: JSONException) {
            readable = false
        }
    }

    private fun read(o: JSONObject?): HashMap<String, SignIn> {
        val out = HashMap<String, SignIn>()
        if (o == null) return out
        for (k in o.keys()) {
            val v = o.optJSONObject(k) ?: continue
            out[k] = SignIn(v.optString("user", ""), v.optString("password", ""), v.optString("domain", ""))
        }
        return out
    }

    private fun SignIn.toJson() = JSONObject().put("user", user).put("password", password).put("domain", domain)
}
