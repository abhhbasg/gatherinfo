package com.metrowin.keyboard

import com.metrowin.keyboard.clipboard.ClipboardStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipboardStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun store() = ClipboardStore(tmp.root, { 20 })

    @Test fun deletedTextIsNotBroughtBackByReadingTheSameSystemClipboard() {
        val s = store()
        s.addText("ciao")
        s.remove(s.all().first().id)
        // The phone's clipboard still holds "ciao" when the keyboard opens again.
        assertFalse(s.addText("ciao", copied = false))
        assertTrue(s.all().isEmpty())
    }

    @Test fun clearedTextIsNotBroughtBack() {
        val s = store()
        s.addText("uno"); s.addText("due")
        s.clear()
        assertFalse(s.addText("due", copied = false))
        assertTrue(s.all().isEmpty())
    }

    @Test fun copyingTheSameTextAgainAddsItAgain() {
        val s = store()
        s.addText("ciao")
        s.remove(s.all().first().id)
        assertTrue(s.addText("ciao", copied = true))
        assertEquals(1, s.all().size)
    }

    @Test fun deletionIsRememberedAfterARestart() {
        store().apply { addText("ciao"); remove(all().first().id) }
        assertFalse(store().addText("ciao", copied = false))
    }
}
