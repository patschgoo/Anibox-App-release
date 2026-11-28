package io.github.patschgoo.anibox.net

import io.github.patschgoo.anibox.sync.LibraryParser
import io.github.patschgoo.anibox.sync.LibraryResult
import io.github.patschgoo.anibox.sync.SyncTarget
import java.io.File

/**
 * Drives plugged into this device (A3): the Shield shares each USB drive as "External USB drive-1a2b3c4d"
 * and mounts it itself at /storage/1A2B-3C4D. On the Shield the app reads them directly: faster than going
 * over the network to itself, and no password.
 */
object LocalDrives {
    private val hexId = Regex("([0-9A-Fa-f]{4})-?([0-9A-Fa-f]{4})(?![0-9A-Fa-f])")
    private val volumeName = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    /** Where [share] is mounted on this device, or null (not plugged in here, or not readable). */
    fun root(share: String, storage: File = File("/storage")): File? {
        val m = hexId.findAll(share).lastOrNull() ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        for (name in listOf("${a.uppercase()}-${b.uppercase()}", "${a.lowercase()}-${b.lowercase()}", "$a-$b")) {
            val f = File(storage, name)
            if (f.isDirectory && f.canRead()) return f
        }
        return null
    }

    /** The share name a mounted drive would have on the network ("…-1a2b3c4d" part only). */
    fun idOf(volume: File): String? = volume.name.takeIf { volumeName.matches(it) }?.replace("-", "")?.lowercase()

    /** A file of [share] at [path] (forward slashes) when the drive is here, else null. */
    fun file(share: String, path: String, storage: File = File("/storage")): File? {
        val r = root(share, storage) ?: return null
        val f = File(r, path)
        return f.takeIf { it.isFile && it.canRead() }
    }

    /**
     * "AniBox Sync" folders with a library.json on this device's drives: (drive, folder relative to it).
     * Looks at most [depth] folders deep (the PC puts it near the top, e.g. NVIDIA_SHIELD/Anime/AniBox Sync).
     */
    fun syncFolders(storage: File = File("/storage"), depth: Int = 4): List<Pair<File, String>> {
        val out = ArrayList<Pair<File, String>>()
        val volumes = storage.listFiles()?.filter { volumeName.matches(it.name) && it.isDirectory && it.canRead() }.orEmpty()
        for (v in volumes.sortedBy { it.name }) {
            var level = listOf(v)
            for (d in 0..depth) {
                val next = ArrayList<File>()
                for (dir in level) {
                    if (dir.name.equals("AniBox Sync", ignoreCase = true) && File(dir, "library.json").isFile) {
                        out += v to dir.relativeTo(v).invariantSeparatorsPath
                        continue
                    }
                    if (d < depth) dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.let { next += it.take(200) }
                }
                level = next
                if (level.isEmpty()) break
            }
        }
        return out
    }

    /**
     * Sync folders on this device's drives as targets: the share named in the folder's own library.json
     * whose drive is the one it sits on (so host and share are exactly what the PC uses for the episodes).
     */
    fun targets(storage: File = File("/storage")): List<SyncTarget> = syncFolders(storage).mapNotNull { (volume, rel) ->
        val text = try { File(File(volume, rel), "library.json").readText() } catch (_: Exception) { return@mapNotNull null }
        val lib = (LibraryParser.parse(text) as? LibraryResult.Ok)?.library ?: return@mapNotNull null
        val share = lib.shares.firstOrNull { root(it.share, storage)?.canonicalPath == volume.canonicalPath } ?: return@mapNotNull null
        SyncTarget(share.host, share.share, rel, address = share.address)
    }

    /** Drives mounted on this device (the names are visible without any permission). */
    fun volumes(storage: File = File("/storage")): List<File> =
        storage.listFiles()?.filter { volumeName.matches(it.name) && it.isDirectory }.orEmpty()

    /** True when [t]'s folder is on a drive plugged into this device (then no sign-in is needed). */
    fun has(t: SyncTarget, storage: File = File("/storage")): Boolean =
        root(t.share, storage)?.let { File(File(it, t.path), "library.json").isFile } == true
}
