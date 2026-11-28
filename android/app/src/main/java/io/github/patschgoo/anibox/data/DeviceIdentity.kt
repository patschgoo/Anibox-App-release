package io.github.patschgoo.anibox.data

import io.github.patschgoo.anibox.sync.ProgressStore
import io.github.patschgoo.anibox.sync.SyncEngine
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * This phone's journal name in the sync folder (`progress/android-<model>-<4 hex>.jsonl`), chosen once
 * and kept. It lives in `device.json`, which Android's backups leave out: a phone set up from this
 * one's backup picks its own name, so two phones never write the same file.
 */
object DeviceIdentity {
    fun journalName(dir: File, model: String): String {
        val file = File(dir, "device.json")
        try {
            file.takeIf { it.isFile }?.readText()?.let { text ->
                val name = JSONObject(text).optString("journal", "")
                if (SyncEngine.isDeviceFile(name) && name.matches(Regex("^[A-Za-z0-9_-]+\\.jsonl$"))) return name
            }
        } catch (_: IOException) {
        } catch (_: JSONException) {
        }
        val name = SyncEngine.deviceFileName(model)
        ProgressStore.writeAtomic(file, JSONObject().put("journal", name).toString().toByteArray())
        return name
    }
}
