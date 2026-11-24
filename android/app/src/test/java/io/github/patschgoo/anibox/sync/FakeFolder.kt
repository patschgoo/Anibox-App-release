package io.github.patschgoo.anibox.sync

import java.io.IOException

/** A sync folder in memory; [offline] makes every call fail like an unreachable share. */
class FakeFolder : SyncFolder {
    val files = LinkedHashMap<String, ByteArray>()
    val dirs = HashSet<String>()
    var offline = false
    val writes = ArrayList<String>()

    private fun check() {
        if (offline) throw IOException("The share doesn't answer")
    }

    fun text(path: String) = files[path]?.toString(Charsets.UTF_8)

    fun put(path: String, text: String) {
        files[path] = text.toByteArray()
        dirs += path.substringBeforeLast('/', "")
    }

    override fun read(path: String): ByteArray? { check(); return files[path]?.copyOf() }
    override fun size(path: String): Long? { check(); return files[path]?.size?.toLong() }
    override fun readRange(path: String, offset: Long, length: Int): ByteArray {
        check()
        val f = files[path] ?: throw IOException("missing")
        return f.copyOfRange(offset.toInt().coerceAtMost(f.size), (offset + length).toInt().coerceAtMost(f.size))
    }
    override fun listFiles(dir: String): List<String> {
        check()
        return files.keys.filter { it.substringBeforeLast('/', "") == dir }.map { it.substringAfterLast('/') }
    }
    override fun ensureDir(dir: String) { check(); dirs += dir }
    override fun append(path: String, data: ByteArray) {
        check()
        writes += "append $path"
        files[path] = (files[path] ?: ByteArray(0)) + data
    }
    override fun replace(path: String, temp: String, data: ByteArray) {
        check()
        writes += "replace $path via $temp"
        files[path] = data
    }
}
