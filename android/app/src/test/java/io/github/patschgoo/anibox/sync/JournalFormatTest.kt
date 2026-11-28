package io.github.patschgoo.anibox.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalFormatTest {
    private val now = 1_763_900_000.0

    /** Lines AniBox on the PC wrote (phonesync.progress_lines), with rounding and escaping cases. The PC's
     *  tests (app/tests/test_phonesync.py) check that they still write exactly these bytes. */
    private fun pcBytes(): ByteArray = javaClass.classLoader!!.getResource("pc-lines.jsonl")!!.readBytes()

    @Test fun sameBytesAsThePc() {
        val rows = listOf(
            ProgressLine("Anime (ger dub)\\Sora \"Hoshi\" Monogatari\\Folge 03 – Café.mp4", 2.675, 0.05, false, 1763900200.0),
            ProgressLine("Anime\\Kaze no Tabi\\Kaze no Tabi 01.mkv", 612.45, 1420.04, false, 1763900000.1255),
            ProgressLine("Anime\\Kaze no Tabi\\Kaze no Tabi 02.mkv", 0.0, 1420.0, true, 1763900100.5),
            ProgressLine("Anime\\Kaze no Tabi\\Kaze no Tabi 03.mkv", 0.0, 1420.0, false, 1763900400.0, unmarked = true),
            ProgressLine("TV\\Tab\tName\\x.mkv", 1e-9, 99999.95, false, 1763900300.0005),
        )
        val out = java.io.ByteArrayOutputStream()
        rows.forEach { out.write(JournalFormat.encodeLine(it)) }
        assertArrayEquals(pcBytes(), out.toByteArray())
    }

    @Test fun unmarkedOnlyWhenSet() {
        val line = ProgressLine("a\\b\\c.mkv", 0.0, 10.0, false, 1763900000.0, unmarked = true)
        val text = JournalFormat.encode(line)
        assertTrue(text.endsWith(",\"profile\":\"main\",\"unmarked\":true}"))
        assertTrue(JournalFormat.decode(text, now)!!.unmarked)
        // a watched line is never "un-marked"; the field is left out otherwise
        assertFalse(JournalFormat.encode(line.copy(watched = true)).contains("unmarked"))
        assertFalse(JournalFormat.decode("{\"key\":\"k\",\"watched\":true,\"updated\":5,\"unmarked\":true}", now)!!.unmarked)
    }

    @Test fun readsThePcsLines() {
        val lines = JournalFormat.decodeAll(pcBytes(), now + 10_000)
        assertEquals(5, lines.size)
        assertTrue(lines[3].unmarked)
        assertEquals("Anime\\Kaze no Tabi\\Kaze no Tabi 01.mkv", lines[1].key)
        assertEquals(612.5, lines[1].position, 0.0)
        assertEquals(1763900000.125, lines[1].updated, 0.0)
        assertTrue(lines[2].watched)
        assertEquals("main", lines[2].profile)
        assertEquals("TV\\Tab\tName\\x.mkv", lines[4].key)
        // and our own encoding of what we read gives the same bytes again
        val again = java.io.ByteArrayOutputStream()
        lines.forEach { again.write(JournalFormat.encodeLine(it)) }
        assertArrayEquals(pcBytes(), again.toByteArray())
    }

    @Test fun pcParseLineRules() {
        // from app/tests/test_phonesync.py test_parse_line
        val n = 1_999_999_100.0
        val e = JournalFormat.decode("""{"key":"A\\B\\c.mkv","position":12.5,"duration":1400,"watched":true,"updated":1999999000,"profile":"main"}""", n)!!
        assertEquals(ProgressLine("A\\B\\c.mkv", 12.5, 1400.0, true, 1999999000.0, "main"), e)
        assertEquals(1999999000.0, JournalFormat.decode("""{"key":"k","updated":1999999000000}""", n)!!.updated, 0.0) // milliseconds
        assertEquals(n, JournalFormat.decode("""{"key":"k","updated":2100000000}""", n)!!.updated, 0.0) // a clock far ahead
        assertFalse(JournalFormat.decode("""{"key":"k","watched":"yes","updated":5}""", n)!!.watched) // only true counts
        for (bad in listOf("", "x", "[]", """{"key":""}""", """{"key":"k"}""", """{"key":"k","updated":"x"}""", """{"key":5,"updated":5}""",
            """{"key":"k","updated":5,"position":"far"}""", "{\"key\":\"" + "k".repeat(601) + "\",\"updated\":5}", "{\"key\":\"k\",\"updated\":5,\"p\":\"" + "x".repeat(4100) + "\"}")) {
            assertNull(bad.take(60), JournalFormat.decode(bad, n))
        }
    }

    @Test fun unfinishedLastLineWaits() {
        val data = "{\"key\":\"a\",\"updated\":5}\n{\"key\":\"b\",\"upd".toByteArray()
        assertEquals(listOf("a"), JournalFormat.decodeAll(data, now).map { it.key })
    }

    @Test fun readerLikeThePc() {
        val one = "{\"key\":\"a\",\"updated\":5}\n".toByteArray()
        val (l1, s1) = JournalReader.newLines(one, null)
        assertEquals(1, l1.size)
        assertEquals(one.size.toLong(), s1.offset)
        val two = one + "{\"key\":\"b\",\"updated\":6}\n{\"key\":\"c\"".toByteArray()
        val (l2, s2) = JournalReader.newLines(two, s1)
        assertEquals(listOf("{\"key\":\"b\",\"updated\":6}"), l2)
        // nothing new
        assertTrue(JournalReader.newLines(two, s2).first.isEmpty())
        // written anew (another start): read from the beginning
        val anew = "{\"key\":\"z\",\"updated\":9}\n{\"key\":\"y\",\"updated\":9}\n{\"key\":\"x\",\"updated\":9}\n".toByteArray()
        assertEquals(3, JournalReader.newLines(anew, s2).first.size)
        // shorter than where it was read: from the beginning too
        assertEquals(1, JournalReader.newLines("{\"key\":\"q\",\"updated\":1}\n".toByteArray(), s2).first.size)
    }

    @Test fun headMatchesPythonsSha1() {
        // hashlib.sha1(b'{"key":"a","updated":5}\n').hexdigest()[:16]
        val data = "{\"key\":\"a\",\"updated\":5}\n".toByteArray()
        val py = java.security.MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }.take(16)
        assertEquals(py, JournalReader.head(data, data.size.toLong()))
        assertEquals(16, JournalReader.head(ByteArray(1000), 900).length)
    }

    @Test fun pythonNumbers() {
        assertEquals("0.0", PyJson.number(0.0, 1))
        assertEquals("12.0", PyJson.number(12.0, 1))
        assertEquals("1763900000.125", PyJson.number(1763900000.125, 3))
        assertEquals("2.67", PyJson.number(2.675, 2)) // the binary value is below .675
        assertEquals("0.0", PyJson.number(-0.01, 1))
        assertEquals("\"a\\\\b\\\"c\\u0001\"", PyJson.string("a\\b\"c\u0001"))
    }
}
