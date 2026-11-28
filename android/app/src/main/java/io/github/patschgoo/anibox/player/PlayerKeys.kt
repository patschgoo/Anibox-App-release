package io.github.patschgoo.anibox.player

import android.view.KeyEvent

/** What a key on a TV remote, a keyboard or a headset does in the player. */
enum class PlayerKey { TOGGLE, PLAY, PAUSE, BACK, FORWARD, NEXT, PREVIOUS, SHOW, CLOSE }

/**
 * The player's keys (like the TV mockup: "OK pauses · Left and Right jump 10 seconds · Up and Down show these
 * buttons · Back closes"). Media keys always work. The arrows and OK only while the buttons are hidden: when
 * they show, the arrows move between them and OK presses the one with the white ring. Back is Android's own
 * (it closes the player, the place is kept).
 */
object PlayerKeys {
    /** Seconds one press of Left/Right, rewind or fast forward jumps. */
    const val JUMP = 10.0

    fun map(keyCode: Int, controlsShown: Boolean): PlayerKey? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE -> PlayerKey.TOGGLE
        KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerKey.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerKey.PAUSE
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> PlayerKey.FORWARD
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> PlayerKey.BACK
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> PlayerKey.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> PlayerKey.PREVIOUS
        KeyEvent.KEYCODE_MEDIA_STOP -> PlayerKey.CLOSE
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (controlsShown) null else PlayerKey.TOGGLE
        KeyEvent.KEYCODE_DPAD_LEFT -> if (controlsShown) null else PlayerKey.BACK
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (controlsShown) null else PlayerKey.FORWARD
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO ->
            if (controlsShown) null else PlayerKey.SHOW
        else -> null
    }

    /** "+30 s" / "−10 s" for the jumps added up while the remote's button is held. */
    fun jumpLabel(total: Double): String {
        val s = Math.round(total).toInt()
        return if (s >= 0) "+$s s" else "−${-s} s"
    }
}
