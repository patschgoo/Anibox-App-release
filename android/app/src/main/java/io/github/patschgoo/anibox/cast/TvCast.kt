package io.github.patschgoo.anibox.cast

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import io.github.patschgoo.anibox.debug.DebugLog
import io.github.patschgoo.anibox.graph
import io.github.patschgoo.anibox.library.Watching
import io.github.patschgoo.anibox.net.LocalDrives
import io.github.patschgoo.anibox.net.Use
import io.github.patschgoo.anibox.player.PlaybackSession
import io.github.patschgoo.anibox.player.Sidecars
import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.Library
import io.github.patschgoo.anibox.sync.ProgressStore
import io.github.patschgoo.anibox.sync.Show
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/** The parts of "Play on TV" that need no Android (the tests drive them). */
object CastQueue {
    const val MAX_ITEMS = 50

    /** [from] and the playable episodes after it, at most [max]. */
    fun episodes(show: Show, from: Episode, playable: (Episode) -> Boolean, max: Int = MAX_ITEMS): List<Episode> {
        val i = show.episodes.indexOfFirst { it.key == from.key }
        if (i < 0) return emptyList()
        return show.episodes.drop(i).filter(playable).take(max)
    }

    /** Where CastApp starts the first item, in ms: 0 when it's watched or [fromStart], else the resume place. */
    fun startMs(store: ProgressStore, key: String, fromStart: Boolean, back: Int): Long {
        val p = store.get(key) ?: return 0
        if (fromStart || p.watched) return 0
        return (Watching.resumeFrom(p.position, p.duration, back) * 1000).toLong()
    }

    /** One queue item as CAST_QUEUE wants it (AUTOMATION.md in CastApp). */
    fun item(e: Episode, show: Show, url: String, mime: String, subs: List<Triple<String, String, String>>): JSONObject =
        JSONObject().apply {
            put("id", e.key)
            put("url", url)
            put("title", listOf(show.title, e.label, e.title).filter { it.isNotEmpty() }.joinToString(" · "))
            put("mime", mime)
            if (subs.isNotEmpty()) {
                put("subtitles", JSONArray().apply {
                    subs.take(20).forEach { (u, lang, label) -> put(JSONObject().put("url", u).put("language", lang).put("label", label)) }
                })
            }
        }

    /**
     * A PLAYBACK report from CastApp: saves the place (or "watched") for [key]. CastApp calls 90 % "finished";
     * AniBox's own rule (95 % or the last 20 s) decides, so a finish a little early only keeps the place.
     * Returns true when something was saved.
     */
    fun report(store: ProgressStore, key: String, state: String, positionMs: Long, durationMs: Long): Boolean {
        val pos = positionMs / 1000.0
        val dur = durationMs / 1000.0
        when (state) {
            "finished" -> {
                if (dur > 0 && pos > 0 && !PlaybackSession.reachedEnd(pos, dur)) store.savePosition(key, pos, dur, journal = true)
                else store.finished(key, if (dur > 0) dur else store.get(key)?.duration ?: 0.0)
            }
            "progress", "paused", "stopped", "resumed" -> {
                if (pos <= 0 || dur <= 0) return false // nothing played yet: the saved place stays
                store.savePosition(key, pos, dur, journal = true)
            }
            else -> return false
        }
        store.save()
        return true
    }
}

/** "Play on TV" through CastApp (A1): the phone builds the queue, CastApp's sheet picks the TV. */
object TvCast {
    const val ACTION_QUEUE = "io.github.patschgoo.castapp.automation.CAST_QUEUE"
    const val ACTION_PLAYBACK = "io.github.patschgoo.castapp.automation.PLAYBACK"
    private val PACKAGES = listOf("io.github.patschgoo.castapp", "io.github.patschgoo.castapp.debug")
    private val random = SecureRandom()

    /** CastApp's package on this phone, or null (then the button isn't shown). */
    fun castApp(context: Context): String? = PACKAGES.firstOrNull { p ->
        try {
            context.packageManager.getPackageInfo(p, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** The intent for CastApp (built off the main thread: it lists the episodes' folders for subtitles). */
    fun intent(context: Context, lib: Library, show: Show, from: Episode, fromStart: Boolean): Intent? {
        val pkg = castApp(context) ?: return null
        val g = context.graph
        val eps = CastQueue.episodes(show, from, { lib.playable(it) })
        if (eps.isEmpty()) return null
        CastFiles.clear()
        val prefs = g.settings.effectiveLanguages(lib.prefs)
        val listed = HashMap<String, List<String>>()
        val uris = ArrayList<Uri>()
        val queue = JSONArray()
        for (e in eps) {
            val share = lib.share(e.share) ?: continue
            val local = LocalDrives.file(share.share, e.path)
            val source = {
                val c = g.shares.connection(share.host, share.share, share.address.ifEmpty { g.sync.hint(share.host) }, Use.PLAYER)
                c.mediaSource(e.path)
            }
            val url = CastFiles.add(context, CastFiles.Entry(e.fileName, CastFiles.mime(e.fileName), local, source))
            uris += url
            val folder = e.path.substringBeforeLast('/', "")
            val names = listed.getOrPut(share.id + "/" + folder) {
                try {
                    local?.parentFile?.listFiles()?.filter { it.isFile }?.map { it.name }
                        ?: g.shares.connection(share.host, share.share, share.address.ifEmpty { g.sync.hint(share.host) }, Use.PLAYER)
                            .list(folder).filter { !it.folder }.map { it.name }
                } catch (x: IOException) {
                    DebugLog.w("cast", "Couldn't list the folder for subtitles", x)
                    emptyList()
                }
            }
            val subs = Sidecars.find(e.fileName, names, folder, prefs.subs).map { s ->
                val subLocal = local?.parentFile?.let { File(it, s.name) }?.takeIf { it.isFile }
                val subSource = {
                    g.shares.connection(share.host, share.share, share.address.ifEmpty { g.sync.hint(share.host) }, Use.PLAYER).mediaSource(s.path)
                }
                val u = CastFiles.add(context, CastFiles.Entry(s.name, CastFiles.mime(s.name), subLocal, subSource))
                uris += u
                Triple(u.toString(), s.lang, s.name)
            }
            queue.put(CastQueue.item(e, show, url.toString(), CastFiles.mime(e.fileName), subs))
        }
        if (queue.length() == 0) return null
        val request = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        remember(context, request)
        DebugLog.i("cast", "Play on TV: ${queue.length()} episode(s) to CastApp")
        return Intent(ACTION_QUEUE).apply {
            setPackage(pkg)
            putExtra("queue", queue.toString())
            putExtra("start_index", 0)
            putExtra("start_position_ms", CastQueue.startMs(g.store, eps.first().key, fromStart, g.settings.resumeBack))
            putExtra("title", show.title)
            putExtra("report_to", context.packageName)
            putExtra("request_id", request)
            val clip = ClipData.newRawUri(show.title, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun remember(context: Context, request: String) {
        val p = context.getSharedPreferences("cast", Context.MODE_PRIVATE)
        val list = (p.getString("requests", "") ?: "").split(',').filter { it.isNotEmpty() }.takeLast(4) + request
        p.edit { putString("requests", list.joinToString(",")) }
    }

    internal fun known(context: Context, request: String?): Boolean =
        !request.isNullOrEmpty() &&
            (context.getSharedPreferences("cast", Context.MODE_PRIVATE).getString("requests", "") ?: "").split(',').contains(request)

    internal fun fromCastApp(r: BroadcastReceiver): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true // only the request id can be checked before Android 14
        return r.sentFromPackage in PACKAGES
    }
}

/** CastApp's PLAYBACK reports: the place and "watched" go into the progress like the phone's own player. */
class CastReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvCast.ACTION_PLAYBACK) return
        if (!TvCast.fromCastApp(this) || !TvCast.known(context, intent.getStringExtra("request_id"))) return
        val key = intent.getStringExtra("item_id") ?: return
        val state = intent.getStringExtra("state") ?: return
        val g = context.graph
        if (g.sync.library.value?.episode(key) == null) return
        if (state == "failed") DebugLog.w("cast", "CastApp couldn't play an episode (${intent.getStringExtra("reason")})")
        if (CastQueue.report(g.store, key, state, intent.getLongExtra("position_ms", 0), intent.getLongExtra("duration_ms", 0))) {
            val pending = goAsync()
            g.scope.launch {
                try { g.sync.flush() } finally { pending.finish() }
            }
        }
    }
}
