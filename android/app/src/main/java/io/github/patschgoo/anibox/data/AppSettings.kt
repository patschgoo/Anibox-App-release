package io.github.patschgoo.anibox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.patschgoo.anibox.net.AddressBook
import io.github.patschgoo.anibox.net.Found
import io.github.patschgoo.anibox.player.Languages
import io.github.patschgoo.anibox.player.ShowLanguage
import io.github.patschgoo.anibox.sync.LanguagePrefs
import io.github.patschgoo.anibox.sync.SyncTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

/** A saved host address and how it was found (shown in Settings). */
data class HostAddress(val host: String, val address: String, val how: Found)

/**
 * The app's settings (no passwords: those are in [io.github.patschgoo.anibox.net.Credentials]).
 * Languages left empty follow the PC's choice from library.json.
 */
class AppSettings(context: Context) : AddressBook {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Goes up on every change (the Settings screen redraws). */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun changed() {
        _version.value += 1
    }

    var target: SyncTarget?
        get() {
            val host = prefs.getString("host", "") ?: ""
            val share = prefs.getString("share", "") ?: ""
            if (host.isEmpty() || share.isEmpty()) return null
            return SyncTarget(host, share, prefs.getString("path", "") ?: "", prefs.getString("pc", "") ?: "", prefs.getString("address", "") ?: "")
        }
        set(t) {
            prefs.edit {
                putString("host", t?.host ?: "")
                putString("share", t?.share ?: "")
                putString("path", t?.path ?: "")
                putString("pc", t?.pc ?: "")
                putString("address", t?.address ?: "")
            }
            changed()
        }

    // -- host addresses ----------------------------------------------------------------------------------

    fun addresses(): List<HostAddress> {
        val o = json("addresses")
        return o.keys().asSequence().mapNotNull { h ->
            val e = o.optJSONObject(h) ?: return@mapNotNull null
            val how = runCatching { Found.valueOf(e.optString("how", "SAVED")) }.getOrDefault(Found.SAVED)
            HostAddress(e.optString("host", h), e.optString("address", ""), how)
        }.filter { it.address.isNotEmpty() }.sortedBy { it.host.lowercase() }.toList()
    }

    override fun address(host: String): String? =
        json("addresses").optJSONObject(host.trim().lowercase())?.optString("address", "")?.ifEmpty { null }

    override fun remember(host: String, address: String, how: Found) {
        val o = json("addresses")
        o.put(host.trim().lowercase(), JSONObject().put("host", host.trim()).put("address", address).put("how", how.name))
        prefs.edit { putString("addresses", o.toString()) }
        changed()
    }

    fun forgetAddress(host: String) {
        val o = json("addresses")
        o.remove(host.trim().lowercase())
        prefs.edit { putString("addresses", o.toString()) }
        changed()
    }

    // -- playback ----------------------------------------------------------------------------------------

    /** Your own audio languages ("" = the PC's). */
    var audioLangs: String
        get() = prefs.getString("audio_langs", "") ?: ""
        set(v) {
            prefs.edit { putString("audio_langs", Languages.clean(v).joinToString(",")) }
            changed()
        }

    var subLangs: String
        get() = prefs.getString("sub_langs", "") ?: ""
        set(v) {
            prefs.edit { putString("sub_langs", Languages.clean(v).joinToString(",")) }
            changed()
        }

    /** "auto" | "on" | "off", or "" = the PC's. */
    var subsMode: String
        get() = prefs.getString("subs", "") ?: ""
        set(v) {
            prefs.edit { putString("subs", if (v in setOf("auto", "on", "off")) v else "") }
            changed()
        }

    /** The PC profile whose progress this phone shows and writes ("main" unless picked). */
    var profile: String
        get() = prefs.getString("profile", "main") ?: "main"
        set(v) {
            prefs.edit { putString("profile", v) }
            changed()
        }

    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(v) {
            prefs.edit { putBoolean("autoplay", v) }
            changed()
        }

    /** Seconds to go back when resuming (the PC's "resume_back", 10 by default). */
    var resumeBack: Int
        get() = prefs.getInt("resume_back", 10)
        set(v) {
            prefs.edit { putInt("resume_back", v.coerceIn(0, 60)) }
            changed()
        }

    var hardwareDecoding: Boolean
        get() = prefs.getBoolean("hwdec", true)
        set(v) {
            prefs.edit { putBoolean("hwdec", v) }
            changed()
        }

    data class Effective(val audio: List<String>, val subs: List<String>, val mode: String)

    /** The languages in use: your own, else the PC's, else AniBox's defaults. */
    fun effectiveLanguages(pc: LanguagePrefs?): Effective {
        val audio = Languages.clean(audioLangs).ifEmpty { pc?.audioLangs?.let { Languages.clean(it.joinToString(",")) }.orEmpty() }
            .ifEmpty { DEFAULT_AUDIO }
        val subs = Languages.clean(subLangs).ifEmpty { pc?.subLangs?.let { Languages.clean(it.joinToString(",")) }.orEmpty() }
            .ifEmpty { DEFAULT_SUBS }
        val mode = subsMode.ifEmpty { pc?.subs ?: "auto" }
        return Effective(audio, subs, mode)
    }

    /** The audio/subtitle language you last picked for a show on this phone. */
    fun showLanguage(showId: String): ShowLanguage {
        val e = json("show_langs").optJSONObject(showId) ?: return ShowLanguage()
        return ShowLanguage(e.optString("audio", ""), e.optString("sub", ""))
    }

    fun setShowLanguage(showId: String, audio: String? = null, sub: String? = null) {
        val o = json("show_langs")
        val e = o.optJSONObject(showId) ?: JSONObject()
        audio?.let { e.put("audio", it) }
        sub?.let { e.put("sub", it) }
        o.put(showId, e)
        prefs.edit { putString("show_langs", o.toString()) }
    }

    private fun json(name: String): JSONObject = try {
        JSONObject(prefs.getString(name, "{}") ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }

    companion object {
        val DEFAULT_AUDIO = listOf("jpn", "ger", "eng")
        val DEFAULT_SUBS = listOf("ger", "eng")
    }
}
