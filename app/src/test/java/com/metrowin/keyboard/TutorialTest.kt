package com.metrowin.keyboard

import androidx.test.core.app.ApplicationProvider
import com.metrowin.keyboard.layout.KeyboardBuilder
import com.metrowin.keyboard.tutorial.Tutorial
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tutorial must stay complete: a page without its text in a language is a bug caught here, not by the user. */
@RunWith(RobolectricTestRunner::class)
class TutorialTest {
    private fun check(app: KeyboardApp) {
        for (p in Tutorial.pages) {
            assertTrue("${p.id} title", app.getString(p.title).isNotBlank())
            assertTrue("${p.id} body", app.getString(p.body).length > 40)
        }
    }

    @Test fun everyPageHasItsTextInEnglish() = check(ApplicationProvider.getApplicationContext())
    @Config(qualifiers = "it") @Test fun everyPageHasItsTextInItalian() {
        val app = ApplicationProvider.getApplicationContext<KeyboardApp>()
        check(app)
        // And it is really Italian, not the English fallback.
        assertNotEquals("Welcome", app.getString(Tutorial.pages[0].title))
    }

    @Test fun versionMatchesTheNewestPage() {
        assertEquals(Tutorial.pages.maxOf { it.since }, Tutorial.VERSION)
        assertEquals(Tutorial.pages.size, Tutorial.pages.map { it.id }.toSet().size)
    }

    @Test fun peopleWhoSawAnOlderTutorialStartAtWhatIsNew() {
        assertEquals(0, Tutorial.startIndex(0))
        assertEquals(Tutorial.pages.indexOfFirst { it.since > 1 }, Tutorial.startIndex(1))
        assertTrue(Tutorial.hasNew(1)); assertFalse(Tutorial.hasNew(Tutorial.VERSION)); assertFalse(Tutorial.hasNew(0))
    }

    @Test fun holdBarStartsWithTheKeysOwnLetter() {
        val app = ApplicationProvider.getApplicationContext<KeyboardApp>()
        val page = KeyboardBuilder.letters(app.layouts.get("it")!!, false, true, false)
        val a = page.keys.first { it.label == "a" }
        assertEquals("a", a.popups.first())
        assertTrue(a.popups.contains("à"))
        // On the top row the digit comes right after the letter.
        assertEquals(listOf("q", "1"), page.keys.first { it.label == "q" }.popups.take(2))
    }

    @Test fun everyScreenOfTheSettingsIsListed() {
        // The settings index points to fragments by name; a typo would crash only when the screen is opened.
        val app = ApplicationProvider.getApplicationContext<KeyboardApp>()
        val names = Regex("app:fragment=\"([^\"]+)\"").findAll(java.io.File("src/main/res/xml/prefs_root.xml").readText()).map { it.groupValues[1] }.toList()
        assertTrue(names.size >= 11)
        for (n in names) Class.forName(n)
        assertNotNull(app)
    }
}
