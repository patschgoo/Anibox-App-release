package io.github.patschgoo.anibox.cast

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import io.github.patschgoo.anibox.debug.DebugLog
import io.github.patschgoo.anibox.stream.MediaSource
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Files handed to CastApp for "Play on TV" (A1). CastApp takes only http(s) or content:// addresses, and
 * the episodes are on SMB shares, so each one gets a content:// address here that reads the share as
 * CastApp asks (seeking included). Only CastApp gets the read grant; the addresses carry a random part
 * and are forgotten with the next queue.
 */
object CastFiles {
    class Entry(val name: String, val mime: String, val local: File?, val source: () -> MediaSource)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    fun authority(context: Context) = context.packageName + ".cast.files"

    /** A new address for one file (kept until [clear]). */
    fun add(context: Context, entry: Entry): Uri {
        val bytes = ByteArray(12).also { random.nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        entries[id] = entry
        return Uri.Builder().scheme("content").authority(authority(context)).appendPath(id).appendPath(entry.name).build()
    }

    fun clear() = entries.clear()

    internal fun get(uri: Uri): Entry? = uri.pathSegments.firstOrNull()?.let { entries[it] }

    /** The type CastApp passes on to the TV. */
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }
}

/** Reads a [MediaSource] at the offsets asked for, keeping one open stream while reads follow each other. */
internal class SeekingReader(private val source: MediaSource) : ProxyFileDescriptorCallback() {
    private var stream: InputStream? = null
    private var at = -1L

    override fun onGetSize(): Long = source.length

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        try {
            if (stream == null || offset != at) {
                stream?.close()
                stream = source.open(offset)
                at = offset
            }
            val s = stream!!
            var done = 0
            while (done < size) {
                val n = s.read(data, done, size - done)
                if (n < 0) break
                done += n
            }
            at += done
            return done
        } catch (e: IOException) {
            DebugLog.w("cast", "Reading for CastApp failed at $offset", e)
            stream?.let { try { it.close() } catch (_: IOException) { } }
            stream = null
            throw ErrnoException("onRead", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        try { stream?.close() } catch (_: IOException) { }
        stream = null
    }
}

class CastFilesProvider : ContentProvider() {
    private val thread by lazy { HandlerThread("cast-files").also { it.start() } }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = CastFiles.get(uri)?.mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val e = CastFiles.get(uri) ?: throw FileNotFoundException("gone")
        e.local?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        val ctx = context ?: throw FileNotFoundException("no context")
        val source = try { e.source() } catch (x: IOException) { throw FileNotFoundException(x.message ?: "share not reachable") }
        if (source.length < 0) throw FileNotFoundException("size unknown")
        val sm = ctx.getSystemService(StorageManager::class.java)
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, SeekingReader(source), Handler(thread.looper))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val e = CastFiles.get(uri) ?: return null
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        val size: Long? = e.local?.length()
        c.addRow(arrayOf<Any?>(e.name, size))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
