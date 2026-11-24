package io.github.patschgoo.anibox.sync

import java.io.IOException
import java.security.SecureRandom

/**
 * What one sync does with the "AniBox Sync" folder (no Android in here, so the tests run it against a fake):
 *
 *     library.json            read (never written)
 *     posters/<name>          read on demand (never written)
 *     progress/pc.jsonl       read: the PC's progress, profile "main" only (never written)
 *     progress/<other>.jsonl  read: other phones' and TVs' progress, from where it was read last time
 *     progress/<device>.jsonl this phone's own journal: only ever appended to, and rewritten with one line
 *                             per episode when it passes ~512 KB (temp file + rename)
 */
class SyncEngine(
    private val store: ProgressStore,
    /** This phone's journal file name, e.g. "android-pixel-8-3fa2.jsonl". */
    private val ownFile: String,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val compactAt: Long = COMPACT_AT,
) {
    data class ImportReport(val files: Int, val lines: Int, val taken: Int)

    /** library.json as text, or null when the folder has none. */
    @Throws(IOException::class)
    fun readLibrary(folder: SyncFolder): ByteArray? = folder.read(LIBRARY)

    /**
     * Reads the PC's pc.jsonl (whole, it's rewritten each time) and every other device's journal from
     * where it was read last time ([seen], updated in place), and merges them into the store.
     */
    @Throws(IOException::class)
    fun importProgress(folder: SyncFolder, seen: MutableMap<String, Seen>): ImportReport {
        val now = clock()
        var files = 0
        var lines = 0
        val taken = ArrayList<ProgressLine>()
        folder.read("$PROGRESS/$PC_FILE")?.let { data ->
            files++
            val all = JournalFormat.decodeAll(data, now)
            lines += all.size
            taken += all.filter { isMain(it.profile) }
        }
        val names = folder.listFiles(PROGRESS)
        for (name in names) {
            if (!isDeviceFile(name) || name.equals(PC_FILE, ignoreCase = true) || name.equals(ownFile, ignoreCase = true)) continue
            val path = "$PROGRESS/$name"
            val before = seen[name]
            val size = folder.size(path) ?: continue
            // Same length as last time: nothing new. (A file written anew is noticed once it grows again.)
            if (before != null && size == before.offset) continue
            val data = folder.read(path) ?: continue
            files++
            val (newLines, now2) = JournalReader.newLines(data, before)
            seen[name] = now2
            for (l in newLines) {
                lines++
                val e = JournalFormat.decode(l, now) ?: continue
                if (isMain(e.profile)) taken += e
            }
        }
        seen.keys.retainAll(names.toSet())
        return ImportReport(files, lines, store.merge(taken))
    }

    /**
     * Sends the queued lines to this phone's journal (appended at the end; a broken last line from an
     * interrupted write is ended first so the next line stays readable), then rewrites the journal when
     * it's too big. Returns how many lines went out.
     */
    @Throws(IOException::class)
    fun flush(folder: SyncFolder): Int {
        val data = store.pending()
        val path = "$PROGRESS/$ownFile"
        if (data.isNotEmpty()) {
            folder.ensureDir(PROGRESS)
            val size = folder.size(path) ?: 0L
            val broken = size > 0 && folder.readRange(path, size - 1, 1).firstOrNull() != '\n'.code.toByte()
            folder.append(path, if (broken) byteArrayOf('\n'.code.toByte()) + data else data)
            store.sent(data.size)
        }
        val size = folder.size(path) ?: 0L
        if (size > compactAt) {
            try {
                compact(folder)
            } catch (_: IOException) {
                // e.g. the PC was reading the journal right then: the lines are in, it's tried again next time
            }
        }
        return data.count { it == '\n'.code.toByte() }
    }

    /** One line per episode (the newest), written to a temp file and renamed over the journal. */
    @Throws(IOException::class)
    fun compact(folder: SyncFolder): Boolean {
        val path = "$PROGRESS/$ownFile"
        val old = folder.read(path) ?: return false
        val latest = LinkedHashMap<String, ProgressLine>()
        for (e in JournalFormat.decodeAll(old, Double.MAX_VALUE)) {
            val have = latest[e.key]
            if (have == null || e.updated >= have.updated) latest[e.key] = e
        }
        val lines = latest.values.sortedWith(compareBy<ProgressLine> { it.updated }.thenBy { it.key }).map { JournalFormat.encodeLine(it) }.toMutableList()
        if (lines.size > 1) {
            // The PC goes on reading where it stopped if the file's start looks the same: make sure it doesn't.
            val sameStart = lines.first().let { first -> old.size >= first.size && old.copyOf(first.size).contentEquals(first) }
            if (sameStart) lines.add(lines.removeAt(0))
        }
        val out = java.io.ByteArrayOutputStream()
        lines.forEach { out.write(it) }
        val temp = "$PROGRESS/.$ownFile.anibox-${hex(3)}.tmp"
        folder.replace(path, temp, out.toByteArray())
        return true
    }

    companion object {
        const val LIBRARY = "library.json"
        const val POSTERS = "posters"
        const val PROGRESS = "progress"
        const val PC_FILE = "pc.jsonl"
        const val COMPACT_AT = 512L * 1024

        private val deviceFile = Regex("^[^.\\\\/][^\\\\/]*\\.jsonl$")
        private val random = SecureRandom()

        /** A device journal's name as the PC accepts it (a `.jsonl` file in progress/, not hidden, not a temp file). */
        fun isDeviceFile(name: String): Boolean = deviceFile.matches(name)

        fun isMain(profile: String): Boolean = profile.isEmpty() || profile.equals(ProgressLine.MAIN, ignoreCase = true)

        /** This phone's journal name: `android-<model>-<4 hex>.jsonl`, only [A-Za-z0-9_-] before ".jsonl". */
        fun deviceFileName(model: String, suffix: String = hex(2)): String {
            val m = model.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-', '_').take(30).trim('-', '_').ifEmpty { "phone" }
            return "android-$m-$suffix.jsonl"
        }

        fun hex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
