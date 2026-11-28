package io.github.patschgoo.anibox.sync

import java.io.IOException

/**
 * The "AniBox Sync" folder, wherever it is (an SMB share on the Shield; a fake in the tests). Paths are
 * relative to the folder, with forward slashes ("library.json", "progress/pc.jsonl").
 */
interface SyncFolder {
    /** The whole file, or null when it doesn't exist. */
    @Throws(IOException::class)
    fun read(path: String): ByteArray?

    /** The file's size, or null when it doesn't exist. */
    @Throws(IOException::class)
    fun size(path: String): Long?

    /**
     * Something that changes whenever the file changes (size and time written), or null when that can't
     * be told (then the file is read every time) or the file isn't there.
     */
    @Throws(IOException::class)
    fun stamp(path: String): String? = null

    /** Bytes [offset] until [offset] + [length] (fewer at the end of the file). */
    @Throws(IOException::class)
    fun readRange(path: String, offset: Long, length: Int): ByteArray

    /** The names of the files (not folders) in [dir]; empty when [dir] doesn't exist. */
    @Throws(IOException::class)
    fun listFiles(dir: String): List<String>

    /** Makes [dir] if it isn't there. */
    @Throws(IOException::class)
    fun ensureDir(dir: String)

    /** Writes [data] at the end of [path] (made if missing). */
    @Throws(IOException::class)
    fun append(path: String, data: ByteArray)

    /** Writes [data] into the new file [temp] (same folder), then renames it to [path], replacing it. */
    @Throws(IOException::class)
    fun replace(path: String, temp: String, data: ByteArray)
}
