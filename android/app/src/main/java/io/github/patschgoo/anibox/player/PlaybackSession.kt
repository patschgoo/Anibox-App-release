package io.github.patschgoo.anibox.player

import io.github.patschgoo.anibox.library.Watching
import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.ProgressStore
import io.github.patschgoo.anibox.sync.Show

/**
 * What playing one show's episodes does to the progress (no mpv in here, so the tests drive it):
 * - starts [resumeBack] seconds before the saved place (from the start when it's watched or early);
 * - nothing is saved until the episode really plays (a failed or cancelled open changes nothing);
 * - keeps the place on this phone every few seconds, and a journal line at most every 30 s;
 * - a line right away on pause, stop, the 95 % mark and the episode's end;
 * - at the end: watched, place back to 0, and the next episode (if any) is offered.
 */
class PlaybackSession(
    val show: Show,
    private val store: ProgressStore,
    private val playable: (Episode) -> Boolean,
    private val resumeBack: Int = 10,
    /** Seconds on a clock that only goes forward (for the "every 30 s"). */
    private val ticks: () -> Double = { System.nanoTime() / 1e9 },
) {
    var index: Int = -1
        private set
    val episode: Episode? get() = show.episodes.getOrNull(index)

    private var lastLocal = Double.NEGATIVE_INFINITY
    private var lastJournal = Double.NEGATIVE_INFINITY
    private var watchedMarked = false
    private var position = 0.0
    private var duration = 0.0
    /** A real position came from mpv for this episode (before that, pause/stop saves nothing). */
    private var played = false

    /** Where [key] starts (seconds): 0 when it's watched or [fromStart], else [resumeBack] before the saved place. */
    fun startFor(key: String, fromStart: Boolean = false): Double {
        val p = store.get(key) ?: return 0.0
        if (fromStart || p.watched) return 0.0
        return Watching.resumeFrom(p.position, p.duration, resumeBack)
    }

    /**
     * The player is about to open [key] at [start]. Nothing is saved yet: opening an episode that then
     * doesn't play (or is closed at once) must not move its place back by [resumeBack] or look "newer"
     * than the PC's place. The first line goes out once it plays ([tick]).
     */
    fun begin(key: String, start: Double) {
        index = show.episodes.indexOfFirst { it.key == key }
        position = start
        duration = store.get(key)?.duration ?: episode?.duration ?: 0.0
        watchedMarked = false
        played = false
        lastJournal = Double.NEGATIVE_INFINITY
        lastLocal = Double.NEGATIVE_INFINITY
    }

    /** About once a second while playing. Returns true when a journal line was queued. */
    fun tick(pos: Double, dur: Double): Boolean {
        val key = episode?.key ?: return false
        position = pos
        if (dur > 0) duration = dur
        played = true
        val now = ticks()
        if (!watchedMarked && duration > 0 && pos >= duration * ProgressStore.WATCHED_AT) {
            watchedMarked = true
            store.savePosition(key, pos, duration, journal = true)
            lastJournal = now
            lastLocal = now
            store.save()
            return true
        }
        if (now - lastLocal < SAVE_EVERY) return false
        val journal = now - lastJournal >= JOURNAL_EVERY
        store.savePosition(key, pos, duration, journal)
        lastLocal = now
        if (journal) {
            lastJournal = now
            store.save()
        }
        return journal
    }

    /** Paused, stopped, gone to the background, or switching episodes: the place goes out right away. */
    fun pauseOrStop(pos: Double = position, dur: Double = duration) {
        val key = episode?.key ?: return
        if (!played) return // it never played: the saved place stays as it was
        if (pos > 0 || dur > 0) {
            position = pos
            if (dur > 0) duration = dur
        }
        store.savePosition(key, position, duration, journal = true)
        lastJournal = ticks()
        lastLocal = lastJournal
        store.save()
    }

    /** Played to the end. Returns the next playable episode (or null). */
    fun ended(dur: Double = duration): Episode? {
        val key = episode?.key ?: return null
        store.finished(key, if (dur > 0) dur else duration)
        store.save()
        watchedMarked = true
        return neighbour(1)
    }

    /** The playable episode [step] places away (+1 next, -1 previous), or null. */
    fun neighbour(step: Int): Episode? {
        var i = index + step
        while (i in show.episodes.indices) {
            if (playable(show.episodes[i])) return show.episodes[i]
            i += step
        }
        return null
    }

    companion object {
        /** At the real end (mpv's "end of file" also comes when the stream drops halfway). */
        fun reachedEnd(position: Double, duration: Double): Boolean =
            duration > 0 && (position >= duration * ProgressStore.WATCHED_AT || duration - position <= 20.0)

        const val SAVE_EVERY = 5.0
        const val JOURNAL_EVERY = 30.0
        const val COUNTDOWN = 10
    }
}
