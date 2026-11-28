package io.github.patschgoo.anibox.cast

import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.ProgressStore
import io.github.patschgoo.anibox.sync.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CastQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private var time = 1000.0
    private fun store() = ProgressStore(tmp.root) { time }

    private val show = Show(
        id = "Anime\\Kaze", library = "Anime", folder = "Kaze", title = "Kaze",
        episodes = (1..5).map {
            Episode("Anime\\Kaze\\Kaze %02d.mkv".format(it), "Kaze %02d.mkv".format(it), label = "Episode $it", share = "s1",
                path = "Anime/Kaze/Kaze %02d.mkv".format(it), local = it == 3)
        },
    )
    private fun k(i: Int) = show.episodes[i - 1].key

    @Test fun theQueueStartsAtTheEpisodeAndSkipsWhatOnlyThePcHas() {
        val eps = CastQueue.episodes(show, show.episodes[1], { !it.local })
        assertEquals(listOf(k(2), k(4), k(5)), eps.map { it.key })
        assertEquals(listOf(k(2), k(4)), CastQueue.episodes(show, show.episodes[1], { !it.local }, max = 2).map { it.key })
    }

    @Test fun startsAtTheResumePlaceUnlessWatched() {
        val s = store()
        s.savePosition(k(1), 600.0, 1400.0, journal = false)
        assertEquals(590_000L, CastQueue.startMs(s, k(1), fromStart = false, back = 10))
        assertEquals(0L, CastQueue.startMs(s, k(1), fromStart = true, back = 10))
        assertEquals(0L, CastQueue.startMs(s, k(2), fromStart = false, back = 10))
        s.finished(k(1), 1400.0)
        assertEquals(0L, CastQueue.startMs(s, k(1), fromStart = false, back = 10))
    }

    @Test fun theItemCarriesTheKeyAsItsId() {
        val o = CastQueue.item(show.episodes[0], show, "content://x/1/a.mkv", "video/x-matroska", listOf(Triple("content://x/2/a.en.srt", "en", "a.en.srt")))
        assertEquals(k(1), o.getString("id"))
        assertEquals("Kaze · Episode 1", o.getString("title"))
        assertEquals("en", o.getJSONArray("subtitles").getJSONObject(0).getString("language"))
        assertFalse(CastQueue.item(show.episodes[0], show, "u", "m", emptyList()).has("subtitles"))
    }

    @Test fun reportsKeepThePlaceAndOnlyTheRealEndMarksWatched() {
        val s = store()
        assertFalse(CastQueue.report(s, k(1), "started", 0, 0)) // nothing played yet
        assertNull(s.get(k(1)))
        assertTrue(CastQueue.report(s, k(1), "progress", 300_000, 1_400_000))
        assertEquals(300.0, s.get(k(1))!!.position, 0.01)
        // CastApp says "finished" at 90 %: AniBox's 95 % rule keeps it a place.
        assertTrue(CastQueue.report(s, k(1), "finished", 1_260_000, 1_400_000))
        assertFalse(s.get(k(1))!!.watched)
        assertTrue(CastQueue.report(s, k(1), "finished", 1_390_000, 1_400_000))
        assertTrue(s.get(k(1))!!.watched)
        assertFalse(CastQueue.report(s, k(2), "failed", 10_000, 1_400_000))
        assertNull(s.get(k(2)))
    }

    @Test fun typesForTheTv() {
        assertEquals("video/x-matroska", CastFiles.mime("A 01.MKV"))
        assertEquals("text/x-ssa", CastFiles.mime("A 01.en.ass"))
        assertEquals("application/octet-stream", CastFiles.mime("noext"))
    }
}
