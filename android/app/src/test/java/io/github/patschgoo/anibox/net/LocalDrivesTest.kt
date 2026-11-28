package io.github.patschgoo.anibox.net

import io.github.patschgoo.anibox.sync.FileSyncFolder
import io.github.patschgoo.anibox.sync.SyncTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalDrivesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun storage(): File = tmp.newFolder("storage").also { File(it, "1A2B-3C4D").mkdirs(); File(it, "emulated").mkdirs() }

    private fun library(share: String) =
        """{"format": 1, "pc": "DESKTOP-TEST", "libraries": [], "shares": [
          {"id": "s1", "host": "MEDIABOX", "share": "Other drive-99998888"},
          {"id": "s2", "host": "MEDIABOX", "share": "$share", "address": "192.0.2.10"}], "shows": []}"""

    @Test fun findsTheDriveOfAShareByItsId() {
        val st = storage()
        assertEquals(File(st, "1A2B-3C4D"), LocalDrives.root("External USB drive-1a2b3c4d", st))
        assertNull(LocalDrives.root("External USB drive-99998888", st)) // not plugged in here
        assertNull(LocalDrives.root("Drive A", st)) // no id in the name
        assertEquals("1a2b3c4d", LocalDrives.idOf(File(st, "1A2B-3C4D")))
        assertEquals(listOf("1A2B-3C4D"), LocalDrives.volumes(st).map { it.name })
    }

    @Test fun findsAnEpisodeFileOnlyWhenItIsThere() {
        val st = storage()
        val ep = File(st, "1A2B-3C4D/NVIDIA_SHIELD/Anime/Show/Show 01.mkv").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(ep, LocalDrives.file("External USB drive-1a2b3c4d", "NVIDIA_SHIELD/Anime/Show/Show 01.mkv", st))
        assertNull(LocalDrives.file("External USB drive-1a2b3c4d", "NVIDIA_SHIELD/Anime/Show/Show 02.mkv", st))
    }

    @Test fun turnsASyncFolderOnTheDriveIntoATarget() {
        val st = storage()
        val dir = File(st, "1A2B-3C4D/NVIDIA_SHIELD/AniBox Sync").apply { mkdirs() }
        File(dir, "library.json").writeText(library("External USB drive-1a2b3c4d"))
        val t = LocalDrives.targets(st).single()
        assertEquals(SyncTarget("MEDIABOX", "External USB drive-1a2b3c4d", "NVIDIA_SHIELD/AniBox Sync", address = "192.0.2.10"), t)
        assertTrue(LocalDrives.has(t, st))
        assertFalse(LocalDrives.has(t.copy(path = "Elsewhere/AniBox Sync"), st))
    }

    @Test fun aSyncFolderWhoseLibraryNamesNoShareOfThisDriveIsLeftOut() {
        val st = storage()
        val dir = File(st, "1A2B-3C4D/AniBox Sync").apply { mkdirs() }
        File(dir, "library.json").writeText(library("Drive A"))
        assertTrue(LocalDrives.targets(st).isEmpty())
    }

    @Test fun theFolderOnTheDriveReadsAppendsAndReplaces() {
        val base = tmp.newFolder("sync")
        val f = FileSyncFolder(base)
        assertNull(f.read("library.json"))
        f.ensureDir("progress")
        f.append("progress/phone.jsonl", "a\n".toByteArray())
        f.append("progress/phone.jsonl", "b\n".toByteArray())
        assertEquals("a\nb\n", String(f.read("progress/phone.jsonl")!!))
        assertEquals(4L, f.size("progress/phone.jsonl"))
        assertArrayEquals("b\n".toByteArray(), f.readRange("progress/phone.jsonl", 2, 100))
        assertEquals(0, f.readRange("progress/phone.jsonl", 10, 5).size)
        f.replace("progress/phone.jsonl", "progress/phone.jsonl.tmp", "c\n".toByteArray())
        assertEquals("c\n", String(f.read("progress/phone.jsonl")!!))
        assertEquals(listOf("phone.jsonl"), f.listFiles("progress"))
        assertTrue(f.stamp("progress/phone.jsonl")!!.startsWith("2:"))
        try {
            f.read("../outside")
            throw AssertionError("a path out of the folder was read")
        } catch (_: IllegalArgumentException) {
        }
    }
}
