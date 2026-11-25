package io.github.patschgoo.anibox.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private var time = 1_763_900_000.0
    private val own = "android-pixel-test-3fa2.jsonl"
    private val store by lazy { ProgressStore(tmp.root) { time } }
    private fun engine(compactAt: Long = SyncEngine.COMPACT_AT) = SyncEngine(store, own, { time }, compactAt)
    private val a1 = "Anime\\Kaze no Tabi\\Kaze no Tabi 01.mkv"
    private val a2 = "Anime\\Kaze no Tabi\\Kaze no Tabi 02.mkv"

    private fun line(key: String, pos: Double, watched: Boolean, updated: Double, profile: String = "main") =
        JournalFormat.encode(ProgressLine(key, pos, 1400.0, watched, updated, profile)) + "\n"

    @Test fun pcFileOnlyMainProfile() {
        val f = FakeFolder()
        f.put("progress/pc.jsonl", line(a1, 100.0, false, time - 5) + line(a2, 0.0, true, time - 5, profile = "p2"))
        val r = engine().importProgress(f, HashMap())
        assertEquals(1, r.taken)
        assertEquals(100.0, store.get(a1)!!.position, 0.0)
        assertEquals(null, store.get(a2)) // another profile's
    }

    @Test fun pcFileReadAgainOnlyWhenItChanged() {
        val f = FakeFolder().apply { stamps = true }
        val seen = HashMap<String, Seen>()
        f.put("progress/pc.jsonl", line(a1, 100.0, false, time - 5))
        assertEquals(1, engine().importProgress(f, seen).files)
        assertEquals(0, engine().importProgress(f, seen).files) // unchanged: not read
        f.put("progress/pc.jsonl", line(a1, 200.0, false, time - 1))
        assertEquals(1, engine().importProgress(f, seen).files)
        assertEquals(200.0, store.get(a1)!!.position, 0.0)
        assertEquals(1, engine().importProgress(FakeFolder().apply { files.putAll(f.files) }, HashMap()).files) // no stamps: always read
    }

        @Test fun otherDevicesFromWhereTheyWereRead() {
        val f = FakeFolder()
        val seen = HashMap<String, Seen>()
        f.put("progress/android-other-0001.jsonl", line(a1, 10.0, false, time - 50))
        f.put("progress/.android-other-0001.jsonl.anibox-abc.tmp", line(a1, 999.0, false, time + 50)) // a temp file: skipped
        f.put("progress/$own", line(a1, 777.0, false, time + 99)) // our own: skipped
        f.put("progress/notes.txt", "hello")
        engine().importProgress(f, seen)
        assertEquals(10.0, store.get(a1)!!.position, 0.0)
        assertTrue("android-other-0001.jsonl" in seen)

        // appended: only the new line is read
        f.files["progress/android-other-0001.jsonl"] = f.files["progress/android-other-0001.jsonl"]!! + line(a1, 20.0, false, time - 40).toByteArray()
        val r = engine().importProgress(f, seen)
        assertEquals(1, r.lines)
        assertEquals(20.0, store.get(a1)!!.position, 0.0)

        // written anew (compacted by that phone): read from the start again
        f.put("progress/android-other-0001.jsonl", line(a2, 0.0, true, time - 30) + line(a1, 30.0, false, time - 30))
        val r2 = engine().importProgress(f, seen)
        assertEquals(2, r2.lines)
        assertTrue(store.get(a2)!!.watched)
        assertEquals(30.0, store.get(a1)!!.position, 0.0)
    }

    @Test fun goneFilesAreForgotten() {
        val f = FakeFolder()
        val seen = HashMap<String, Seen>()
        f.put("progress/tv-box.jsonl", line(a1, 10.0, false, time - 50))
        engine().importProgress(f, seen)
        f.files.remove("progress/tv-box.jsonl")
        engine().importProgress(f, seen)
        assertTrue(seen.isEmpty())
    }

    @Test fun flushAppendsOnly() {
        val f = FakeFolder()
        f.put("progress/pc.jsonl", line(a1, 5.0, false, time - 500))
        val pcBefore = f.files["progress/pc.jsonl"]!!.copyOf()
        store.savePosition(a1, 100.0, 1400.0, journal = true)
        assertEquals(1, engine().flush(f))
        time += 30
        store.savePosition(a1, 130.0, 1400.0, journal = true)
        store.setWatched(listOf(a2), true)
        assertEquals(2, engine().flush(f))
        val text = f.text("progress/$own")!!
        assertEquals(3, text.count { it == '\n' })
        assertTrue(text.endsWith("\n"))
        assertEquals(listOf("append progress/$own", "append progress/$own"), f.writes)
        assertArrayEquals(pcBefore, f.files["progress/pc.jsonl"]) // never touched
        assertEquals(0, store.pendingCount())
        assertEquals(0, engine().flush(f)) // nothing waits
    }

    @Test fun offlineLinesWaitInTheQueue() {
        val f = FakeFolder()
        f.offline = true
        store.savePosition(a1, 100.0, 1400.0, journal = true)
        try {
            engine().flush(f)
            fail("should fail while offline")
        } catch (_: IOException) {
        }
        assertEquals(1, store.pendingCount())
        store.savePosition(a1, 160.0, 1400.0, journal = true)
        f.offline = false
        assertEquals(2, engine().flush(f))
        assertEquals(0, store.pendingCount())
        assertEquals(2, JournalFormat.decodeAll(f.files["progress/$own"]!!, time).size)
    }

    @Test fun brokenLastLineIsEndedFirst() {
        val f = FakeFolder()
        f.put("progress/$own", line(a1, 1.0, false, time - 9) + "{\"key\":\"half")
        store.savePosition(a2, 50.0, 1400.0, journal = true)
        engine().flush(f)
        val lines = JournalFormat.decodeAll(f.files["progress/$own"]!!, time)
        assertEquals(listOf(a1, a2), lines.map { it.key }) // the half line is junk on its own, ours is readable
    }

    @Test fun compactionKeepsOneLinePerEpisode() {
        val f = FakeFolder()
        for (i in 1..40) {
            time += 1
            store.savePosition(a1, i * 10.0, 1400.0, journal = true)
            store.savePosition(a2, i * 5.0, 1400.0, journal = true)
            engine(compactAt = 1L shl 40).flush(f)
        }
        val before = f.files["progress/$own"]!!
        assertEquals(80, JournalFormat.decodeAll(before, time).size)
        engine(compactAt = 1000).flush(f)
        val after = f.files["progress/$own"]!!
        val lines = JournalFormat.decodeAll(after, time)
        assertEquals(2, lines.size)
        assertEquals(400.0, lines.first { it.key == a1 }.position, 0.0)
        assertEquals(200.0, lines.first { it.key == a2 }.position, 0.0)
        assertTrue(f.writes.last().startsWith("replace progress/$own via progress/.$own.anibox-"))
        assertTrue(f.writes.last().endsWith(".tmp"))
        // the PC must notice the file was written anew: its start differs from the old one
        assertNotEquals(JournalReader.head(before, 256), JournalReader.head(after, 256))
        val pcSeen = Seen(before.size.toLong(), JournalReader.head(before, before.size.toLong()))
        assertEquals(2, JournalReader.newLines(after, pcSeen).first.size)
    }

    @Test fun compactionStartsDifferentlyEvenWhenTheFirstLineStays() {
        val f = FakeFolder()
        store.savePosition(a1, 10.0, 1400.0, journal = true) // oldest line first, and it stays the newest for a1
        time += 1
        repeat(30) {
            time += 1
            store.savePosition(a2, it.toDouble(), 1400.0, journal = true)
        }
        engine(compactAt = 1L shl 40).flush(f)
        val before = f.files["progress/$own"]!!
        engine().compact(f)
        val after = f.files["progress/$own"]!!
        assertFalse(after.copyOf(minOf(256, after.size)).contentEquals(before.copyOf(minOf(256, after.size))))
        assertEquals(2, JournalFormat.decodeAll(after, time).size)
    }

    @Test fun deviceNames() {
        val n = SyncEngine.deviceFileName("Pixel 8 Pro (5G)", "3fa2")
        assertEquals("android-pixel-8-pro-5g-3fa2.jsonl", n)
        assertTrue(Regex("^[A-Za-z0-9_-]+\\.jsonl$").matches(n))
        assertTrue(SyncEngine.isDeviceFile(n))
        assertEquals("android-phone-0000.jsonl", SyncEngine.deviceFileName("…", "0000"))
        assertTrue(SyncEngine.deviceFileName("x".repeat(80)).length < 60)
        assertFalse(SyncEngine.isDeviceFile(".hidden.jsonl"))
        assertFalse(SyncEngine.isDeviceFile("x.jsonl.tmp"))
    }
}
