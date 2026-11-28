package io.github.patschgoo.anibox.data

import io.github.patschgoo.anibox.debug.DebugLog
import io.github.patschgoo.anibox.stream.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** One episode's download: [done] of [total] bytes; [ready] = whole and playable; [error] = stopped with this. */
data class DownloadState(val done: Long = 0, val total: Long = -1, val ready: Boolean = false, val error: String? = null) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Episodes kept on this phone for watching without the share (A5: away from home, no Tailscale, a plane).
 * Each one gets its own folder `<dir>/<sha1(key)>/` with the video and its subtitle files under their own
 * names; `.done` marks a whole one. A broken-off download goes on from where it stopped (`.part`).
 * Only these folders are ever deleted; nothing is written anywhere else.
 */
class Downloads(private val dir: File, private val scope: CoroutineScope) {
    /** What to fetch for one episode: the video, then its subtitle files (name to source). */
    class Request(val key: String, val video: Pair<String, MediaSource>, val subs: List<Pair<String, MediaSource>>)

    private val _states = MutableStateFlow(scan())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()
    private val queue = Mutex()
    private val jobs = HashMap<String, Job>()

    private fun folder(key: String): File = File(dir, sha1(key))

    private fun scan(): Map<String, DownloadState> {
        val out = HashMap<String, DownloadState>()
        dir.listFiles()?.forEach { d ->
            val key = try { File(d, ".key").takeIf { it.isFile }?.readText() } catch (_: IOException) { null } ?: return@forEach
            val ready = File(d, ".done").isFile
            val size = d.listFiles()?.filter { !it.name.startsWith(".") }?.sumOf { it.length() } ?: 0
            out[key] = DownloadState(size, if (ready) size else -1, ready, if (ready) null else "Stopped")
        }
        return out
    }

    /** The downloaded video of [key] (whole ones only), or null. */
    fun video(key: String): File? {
        val d = folder(key)
        if (!File(d, ".done").isFile) return null
        val name = try { File(d, ".video").readText() } catch (_: IOException) { return null }
        return File(d, name).takeIf { it.isFile }
    }

    /** Names of the files next to [key]'s video (for its subtitles). */
    fun files(key: String): List<String> = folder(key).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.map { it.name }.orEmpty()

    fun start(request: () -> Request?, key: String) {
        if (jobs[key]?.isActive == true || _states.value[key]?.ready == true) return
        update(key) { DownloadState(it?.done ?: 0, it?.total ?: -1) }
        jobs[key] = scope.launch {
            queue.withLock { // one at a time: the share and the Wi-Fi stay usable
                try {
                    val r = request() ?: throw IOException("This episode can't be downloaded from here.")
                    fetch(r)
                } catch (e: IOException) {
                    DebugLog.w("downloads", "Download stopped", e)
                    update(key) { (it ?: DownloadState()).copy(error = e.message ?: "The download stopped.") }
                }
            }
        }
    }

    fun remove(key: String) {
        jobs.remove(key)?.cancel()
        folder(key).deleteRecursively()
        _states.value = _states.value - key
    }

    /** Space the downloads take, in bytes. */
    fun bytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal suspend fun fetch(r: Request) {
        val d = folder(r.key).also { it.mkdirs() }
        File(d, ".key").writeText(r.key)
        File(d, ".video").writeText(r.video.first)
        val total = r.video.second.length
        update(r.key) { DownloadState(it?.done ?: 0, total) }
        copy(r.video.second, File(d, r.video.first)) { done -> update(r.key) { DownloadState(done, total) } }
        for ((name, src) in r.subs) {
            try { copy(src, File(d, name)) {} } catch (e: IOException) { DebugLog.w("downloads", "A subtitle file didn't come along", e) }
        }
        File(d, ".done").writeText("1")
        update(r.key) { DownloadState(total, total, ready = true) }
        DebugLog.i("downloads", "Download ready (${total / 1024 / 1024} MB)")
    }

    private suspend fun copy(src: MediaSource, target: File, progress: (Long) -> Unit) {
        if (target.isFile) return
        val part = File(target.path + ".part")
        var at = if (part.isFile) part.length() else 0L
        val total = src.length
        if (total in 0 until at) { part.delete(); at = 0 }
        src.open(at).use { input ->
            FileOutputStream(part, true).use { out ->
                val buf = ByteArray(256 * 1024)
                var last = 0L
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    at += n
                    if (at - last > 4 * 1024 * 1024) { last = at; progress(at) }
                }
                out.fd.sync()
            }
        }
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
        if (total >= 0 && at != total) throw IOException("The download stopped at ${at / 1024 / 1024} of ${total / 1024 / 1024} MB.")
        if (!part.renameTo(target)) throw IOException("The downloaded file couldn't be kept.")
        progress(at)
    }

    private fun update(key: String, f: (DownloadState?) -> DownloadState) {
        synchronized(this) { _states.value = _states.value + (key to f(_states.value[key])) }
    }

    companion object {
        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
