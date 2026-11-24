package io.github.patschgoo.anibox.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class CredentialsTest {
    @get:Rule val tmp = TemporaryFolder()

    // A stand-in for the Keystore: the tests only check that nothing is kept in plain text.
    private val seal: (String) -> String? = { SealedText.seal(it) { p -> Base64.getEncoder().encodeToString(p.reversed().toByteArray()) } }
    private val open: (String) -> String? = { SealedText.open(it) { c -> String(Base64.getDecoder().decode(c)).reversed() } }

    @Test fun perHostThenPerShare() {
        val f = File(tmp.root, "signin.json")
        val c = Credentials(f, seal, open)
        assertTrue(c.setHost("MEDIABOX", SignIn("viewer", "s3cret")))
        assertEquals("viewer", c.forShare("mediabox", "Drive A")!!.user) // every share on that host
        c.setShare("MEDIABOX", "Drive B", SignIn("other", "pw2"))
        assertEquals("other", c.forShare("MEDIABOX", "drive b")!!.user)
        assertEquals("viewer", c.forShare("MEDIABOX", "Drive A")!!.user)
        assertNull(c.forShare("OTHERBOX", "Drive A"))

        val text = f.readText()
        assertTrue(text.startsWith(SealedText.PREFIX))
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("viewer"))

        val again = Credentials(f, seal, open)
        assertEquals("s3cret", again.forShare("MEDIABOX", "Drive A")!!.password)
        again.clear()
        assertFalse(f.exists())
        assertNull(again.forHost("MEDIABOX"))
    }

    @Test fun neverPlainWhenTheKeystoreFails() {
        val f = File(tmp.root, "signin.json")
        val c = Credentials(f, { SealedText.seal(it) { "" } }, open)
        assertFalse(c.setHost("MEDIABOX", SignIn("viewer", "s3cret")))
        assertFalse(f.exists())
    }

    @Test fun unreadableFileAsksAgain() {
        val f = File(tmp.root, "signin.json").apply { writeText(SealedText.PREFIX + "garbage") }
        val c = Credentials(f, seal) { null }
        assertFalse(c.readable)
        assertNull(c.forHost("MEDIABOX"))
    }

    @Test fun passwordNotInToString() {
        assertFalse(SignIn("u", "hunter2").toString().contains("hunter2"))
    }
}
