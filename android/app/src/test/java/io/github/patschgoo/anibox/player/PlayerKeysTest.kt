package io.github.patschgoo.anibox.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKeysTest {
    @Test
    fun `media keys work with and without the buttons`() {
        for (shown in listOf(true, false)) {
            assertEquals(PlayerKey.TOGGLE, PlayerKeys.map(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, shown))
            assertEquals(PlayerKey.TOGGLE, PlayerKeys.map(KeyEvent.KEYCODE_HEADSETHOOK, shown))
            assertEquals(PlayerKey.PLAY, PlayerKeys.map(KeyEvent.KEYCODE_MEDIA_PLAY, shown))
            assertEquals(PlayerKey.PAUSE, PlayerKeys.map(KeyEvent.KEYCODE_MEDIA_PAUSE, shown))
            assertEquals(PlayerKey.FORWARD, PlayerKeys.map(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, shown))
            assertEquals(PlayerKey.BACK, PlayerKeys.map(KeyEvent.KEYCODE_MEDIA_REWIND, shown))
            assertEquals(PlayerKey.NEXT, PlayerKeys.map(KeyEvent.KEYCODE_MEDIA_NEXT, shown))
            assertEquals(PlayerKey.PREVIOUS, PlayerKeys.map(KeyEvent.KEYCODE_MEDIA_PREVIOUS, shown))
            assertEquals(PlayerKey.CLOSE, PlayerKeys.map(KeyEvent.KEYCODE_MEDIA_STOP, shown))
        }
    }

    @Test
    fun `buttons hidden - OK pauses, left and right jump, up and down show the buttons`() {
        assertEquals(PlayerKey.TOGGLE, PlayerKeys.map(KeyEvent.KEYCODE_DPAD_CENTER, false))
        assertEquals(PlayerKey.TOGGLE, PlayerKeys.map(KeyEvent.KEYCODE_ENTER, false))
        assertEquals(PlayerKey.BACK, PlayerKeys.map(KeyEvent.KEYCODE_DPAD_LEFT, false))
        assertEquals(PlayerKey.FORWARD, PlayerKeys.map(KeyEvent.KEYCODE_DPAD_RIGHT, false))
        assertEquals(PlayerKey.SHOW, PlayerKeys.map(KeyEvent.KEYCODE_DPAD_UP, false))
        assertEquals(PlayerKey.SHOW, PlayerKeys.map(KeyEvent.KEYCODE_DPAD_DOWN, false))
    }

    @Test
    fun `buttons shown - the arrows and OK belong to the buttons`() {
        for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertNull(PlayerKeys.map(code, true))
        }
    }

    @Test
    fun `back and other keys are left to Android`() {
        assertNull(PlayerKeys.map(KeyEvent.KEYCODE_BACK, false))
        assertNull(PlayerKeys.map(KeyEvent.KEYCODE_VOLUME_UP, false))
        assertNull(PlayerKeys.map(KeyEvent.KEYCODE_HOME, true))
    }

    @Test
    fun `held jumps add up`() {
        assertEquals("+10 s", PlayerKeys.jumpLabel(10.0))
        assertEquals("+40 s", PlayerKeys.jumpLabel(40.0))
        assertEquals("−30 s", PlayerKeys.jumpLabel(-30.0))
        assertEquals("+0 s", PlayerKeys.jumpLabel(0.0))
    }
}
