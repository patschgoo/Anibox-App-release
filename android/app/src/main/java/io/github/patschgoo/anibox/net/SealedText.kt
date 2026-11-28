package io.github.patschgoo.anibox.net

// From CastApp (network/SealedText.kt, same owner, GPL-3.0). Unlike CastApp, AniBox never keeps a
// password in plain text: when this phone's Keystore can't encrypt, nothing is saved.

/** Text kept on the phone encrypted with [Secrets]: "anibox-enc1:" plus the cipher text. */
object SealedText {
    const val PREFIX = "anibox-enc1:"

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /** [plain] encrypted, or null when this phone's Keystore can't encrypt (broken Keystores exist). */
    fun seal(plain: String, encrypt: (String) -> String = Secrets::encrypt): String? = try {
        val cipher = encrypt(plain)
        if (cipher.isEmpty() && plain.isNotEmpty()) null else PREFIX + cipher
    } catch (_: Exception) {
        null
    }

    /** The plain text of [stored]; null when it can't be decrypted (e.g. restored onto another phone). */
    fun open(stored: String, decrypt: (String) -> String = Secrets::decrypt): String? {
        if (!isSealed(stored)) return null
        val cipher = stored.removePrefix(PREFIX)
        if (cipher.isEmpty()) return ""
        return decrypt(cipher).ifEmpty { null }
    }
}
