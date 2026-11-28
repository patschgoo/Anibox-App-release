package io.github.patschgoo.anibox.net

import io.github.patschgoo.anibox.sync.SyncFolder

/** The "AniBox Sync" folder [base] (forward slashes, relative to the share's top) on an SMB share. */
class SmbSyncFolder(private val smb: SmbConnection, private val base: String) : SyncFolder {
    private fun p(path: String) = if (base.isEmpty()) path else "$base/$path"

    override fun read(path: String): ByteArray? = smb.readAll(p(path))

    override fun size(path: String): Long? = smb.sizeOrNull(p(path))

    override fun stamp(path: String): String? = smb.stampOrNull(p(path))

    override fun readRange(path: String, offset: Long, length: Int): ByteArray = smb.readRange(p(path), offset, length)

    override fun listFiles(dir: String): List<String> = try {
        smb.list(p(dir)).filter { !it.folder }.map { it.name }
    } catch (e: SmbProblem) {
        if (e.problem == Problem.NOT_FOUND) emptyList() else throw e
    }

    override fun ensureDir(dir: String) = smb.mkdir(p(dir))

    override fun append(path: String, data: ByteArray) = smb.append(p(path), data)

    override fun replace(path: String, temp: String, data: ByteArray) = smb.replace(p(path), p(temp), data)

    /** True when the folder itself is there. */
    fun exists(): Boolean = smb.folderExists(base)
}
