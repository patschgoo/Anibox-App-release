package io.github.patschgoo.anibox.data

import io.github.patschgoo.anibox.stream.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class DownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Bytes(val data: ByteArray) : MediaSource {
        var opens = ArrayList<Long>()
        override val length: Long get() = data.size.toLong()
        override fun open(offset: Long): InputStream { opens += offset; return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt()) }
    }

    private val key = "Anime\\Kaze\\Kaze 01.mkv"

    @Test fun keepsTheVideoAndItsSubtitles() = runBlocking {
        val d = Downloads(tmp.root, CoroutineScope(Dispatchers.IO))
        val video = Bytes(ByteArray(5_000_000) { (it % 251).toByte() })
        d.fetch(Downloads.Request(key, "Kaze 01.mkv" to video, listOf("Kaze 01.en.srt" to Bytes("1\n".toByteArray()))))
        val f = d.video(key)!!
        assertArrayEquals(video.data, f.readBytes())
        assertEquals(setOf("Kaze 01.mkv", "Kaze 01.en.srt"), d.files(key).toSet())
        assertTrue(d.states.value[key]!!.ready)
        // A new start of the app finds it again.
        assertTrue(Downloads(tmp.root, CoroutineScope(Dispatchers.IO)).states.value[key]!!.ready)
        d.remove(key)
        assertNull(d.video(key))
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun goesOnFromWhereItStopped() = runBlocking {
        val d = Downloads(tmp.root, CoroutineScope(Dispatchers.IO))
        val video = Bytes(ByteArray(1000) { it.toByte() })
        val folder = File(tmp.root, Downloads.sha1(key)).also { it.mkdirs() }
        File(folder, "Kaze 01.mkv.part").writeBytes(video.data.copyOf(400))
        assertNull(d.video(key))
        d.fetch(Downloads.Request(key, "Kaze 01.mkv" to video, emptyList()))
        assertEquals(listOf(400L), video.opens)
        assertArrayEquals(video.data, d.video(key)!!.readBytes())
    }
}
