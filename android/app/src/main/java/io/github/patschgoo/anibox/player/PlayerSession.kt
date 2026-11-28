package io.github.patschgoo.anibox.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * The player as Android sees it (framework MediaSession, no extra library): headset and Bluetooth
 * buttons, the lock screen and Android's media controls play, pause, seek and move to the next or
 * previous episode while the player is open.
 */
class PlayerSession(context: Context, private val actions: PlayerControlActions) {
    private val session = MediaSession(context, "AniBox player").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (paused) actions.togglePause() }
            override fun onPause() { if (!paused) actions.togglePause() }
            override fun onSkipToNext() = actions.next()
            override fun onSkipToPrevious() = actions.previous()
            override fun onSeekTo(pos: Long) = actions.seekTo(pos / 1000.0)
            override fun onFastForward() = actions.seekBy(10.0)
            override fun onRewind() = actions.seekBy(-10.0)
            override fun onStop() = actions.close()
        })
        isActive = true
    }
    private var paused = false
    private var last: Triple<String, String, Long>? = null

    /** From the player's state, about twice a second (cheap: only changes are passed on). */
    fun update(ui: PlayerUi) {
        paused = ui.paused
        val meta = Triple(ui.title, ui.episode, (ui.duration * 1000).toLong())
        if (meta != last) {
            last = meta
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, ui.episode.ifEmpty { ui.title })
                .putString(MediaMetadata.METADATA_KEY_ALBUM, ui.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, ui.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, meta.third)
                .build())
        }
        var can = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_STOP
        if (ui.hasNext) can = can or PlaybackState.ACTION_SKIP_TO_NEXT
        if (ui.hasPrevious) can = can or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val state = when {
            ui.error != null -> PlaybackState.STATE_ERROR
            ui.loading || ui.buffering -> PlaybackState.STATE_BUFFERING
            ui.finished -> PlaybackState.STATE_STOPPED
            ui.paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(can)
            .setState(state, (ui.position * 1000).toLong(), if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
            .build())
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}
