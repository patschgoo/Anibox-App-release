package io.github.patschgoo.anibox.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** The "AniBox Sync" folder on a drive plugged into this device (the Shield's own USB drives). */
class FileSyncFolder(private val base: File) : SyncFolder {
    private fun f(path: String): File {
        val clean = SyncTarget.filePath(path)
        require(clean.split('/').none { it == ".." }) { "bad path" }
        return File(base, clean)
    }

    override fun exists(): Boolean = base.isDirectory

    override fun read(path: String): ByteArray? = f(path).takeIf { it.isFile }?.readBytes()

    override fun size(path: String): Long? = f(path).takeIf { it.isFile }?.length()

    override fun stamp(path: String): String? = f(path).takeIf { it.isFile }?.let { "${it.length()}:${it.lastModified()}" }

    override fun readRange(path: String, offset: Long, length: Int): ByteArray {
        RandomAccessFile(f(path), "r").use { r ->
            if (offset >= r.length()) return ByteArray(0)
            r.seek(offset)
            val buf = ByteArray(minOf(length.toLong(), r.length() - offset).toInt())
            r.readFully(buf)
            return buf
        }
    }

    override fun listFiles(dir: String): List<String> = f(dir).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    override fun ensureDir(dir: String) {
        val d = f(dir)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("Couldn't make ${d.name} on this drive.")
    }

    override fun append(path: String, data: ByteArray) {
        FileOutputStream(f(path), true).use { it.write(data); it.fd.sync() }
    }

    override fun replace(path: String, temp: String, data: ByteArray) {
        val t = f(temp)
        FileOutputStream(t).use { it.write(data); it.fd.sync() }
        val target = f(path)
        if (!t.renameTo(target)) {
            // Some drive file systems refuse to rename over a file: replace it in two steps.
            if (!target.delete() || !t.renameTo(target)) {
                t.delete()
                throw IOException("Couldn't replace ${target.name} on this drive.")
            }
        }
    }
}
