package com.metrowin.keyboard

import com.metrowin.keyboard.update.Changelog
import com.metrowin.keyboard.update.Release
import com.metrowin.keyboard.update.Repo
import com.metrowin.keyboard.update.UpdateChecker
import com.metrowin.keyboard.update.Versions
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    private val base = "${Repo.DOWNLOADS}v26.2S/"

    private fun release(tag: String = "v26.2S", apkUrl: String = "${Repo.DOWNLOADS}$tag/MetroWinKeyboard-26.2S.apk", extra: String = "", draft: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":false,"published_at":"2026-12-01T10:00:00Z","body":"Auto notes",
         "assets":[{"name":"MetroWinKeyboard-26.2S.apk","browser_download_url":"$apkUrl","size":3500000},
                   {"name":"SHA256SUMS","browser_download_url":"${Repo.DOWNLOADS}$tag/SHA256SUMS","size":100}$extra]}
    """.trimIndent()

    @Test fun versionsAreComparedNumberByNumber() {
        assertTrue(Versions.isNewer("26.2S", "26.1S"))
        assertTrue(Versions.isNewer("v26.7.2S", "26.7S"))
        assertTrue(Versions.isNewer("26.1S", "0.3.0"))
        assertFalse(Versions.isNewer("26.1S", "26.1S"))
        assertFalse(Versions.isNewer("v26.1S", "26.1S"))
        assertFalse(Versions.isNewer("26.0.9S", "26.1S"))
        assertFalse("a name without numbers is never an update", Versions.isNewer("nightly", "26.1S"))
        assertTrue(Versions.isNewer("26.10S", "26.9S"))
    }

    @Test fun theLatestReleaseIsReadFromItsFiles() {
        val r = UpdateChecker { release() }.latest()!!
        assertEquals("26.2S", r.version); assertEquals("2026-12-01", r.date); assertEquals(3_500_000L, r.apkSize)
        assertEquals("MetroWinKeyboard-26.2S.apk", r.apkName); assertEquals("${base}SHA256SUMS", r.sumsUrl)
        assertEquals(r, Release.fromJson(r.toJson()))
    }

    @Test fun aReleaseThatPointsAnywhereElseIsIgnored() {
        assertNull(UpdateChecker { release(apkUrl = "https://evil.example/MetroWinKeyboard-26.2S.apk") }.latest())
        assertNull(UpdateChecker { release(apkUrl = "https://github.com/someone-else/MetroWin-Keyboard/releases/download/v26.2S/MetroWinKeyboard-26.2S.apk") }.latest())
        assertNull("a file of another release", UpdateChecker { release(apkUrl = "${Repo.DOWNLOADS}v1.0S/MetroWinKeyboard-26.2S.apk") }.latest())
        assertNull(UpdateChecker { release(tag = "v26.2S/../../x") }.latest())
        assertNull(UpdateChecker { release(draft = true) }.latest())
        assertNull(Release.fromJson(Release("v1", "1", "", "", "https://evil.example/a.apk", "a.apk", 1, null).toJson()))
    }

    @Test fun onlyAddressesOfThisRepositoryAreAsked() {
        assertTrue(Repo.isOurs(Repo.LATEST)); assertTrue(Repo.isOurs(Repo.DOWNLOADS + "v1/a.apk")); assertTrue(Repo.isOurs(Repo.RAW + "v1/CHANGELOG.md"))
        for (bad in listOf(
            "https://api.github.com/repos/someone-else/other/releases/latest", "http://api.github.com/repos/${Repo.OWNER}/${Repo.NAME}/releases/latest",
            "https://evil.example/", Repo.RAW + "../../evil/x/main/a", Repo.DOWNLOADS + "v1/%2e%2e/x", "https://github.com/${Repo.OWNER}/${Repo.NAME}-fork/releases/download/v1/a.apk",
            Repo.DOWNLOADS + "v1/a.apk@evil.example", Repo.LATEST + "?x=1 ",
        )) assertFalse(bad, Repo.isOurs(bad))
    }

    private val md = """
        # Changelog

        ## Unreleased

        - not a version

        ## 26.2S

        - **Updates:** the keyboard looks for a new version. *Settings → Updates*, see [the page](https://example.org).
        - Second line with `code`.

        ## 26.1S

        - **Calibration:** the first one.

        ## 0.3.0

        - Old.
    """.trimIndent()

    @Test fun theChangelogShowsWhatIsNewerThanTheInstalledVersion() {
        val s = Changelog.since(md, "26.1S", "26.2S")
        assertTrue(s.startsWith("26.2S"))
        assertTrue(s.contains("• Updates: the keyboard looks for a new version. Settings → Updates, see the page."))
        assertTrue(s.contains("• Second line with code."))
        assertFalse(s.contains("Calibration")); assertFalse(s.contains("not a version")); assertFalse(s.contains("**")); assertFalse(s.contains("http"))
        val both = Changelog.since(md, "0.3.0", "26.2S")
        assertTrue(both.indexOf("26.2S") < both.indexOf("26.1S")); assertFalse(both.contains("Old."))
    }

    @Test fun anUpdateCarriesTheChangelogOrFallsBackToTheReleaseText() {
        val withLog = UpdateChecker { if (it == Repo.LATEST) release() else md }.newerThan("26.1S")!!
        assertTrue(withLog.notes.startsWith("26.2S"))
        val without = UpdateChecker { if (it == Repo.LATEST) release() else throw java.io.IOException("404") }.newerThan("26.1S")!!
        assertEquals("Auto notes", without.notes)
        assertNull(UpdateChecker { release() }.newerThan("26.2S"))
    }

    @Test fun theChecksumIsFoundByFileName() {
        val sums = "ABCDEF0123  MetroWinKeyboard-26.2S.apk\n9999  other.apk\n"
        assertEquals("abcdef0123", UpdateChecker.checksumOf(sums, "MetroWinKeyboard-26.2S.apk"))
        assertNull(UpdateChecker.checksumOf(sums, "MetroWinKeyboard-26.3S.apk"))
        assertEquals("aa", UpdateChecker.checksumOf("aa *file.apk", "file.apk"))
    }
}
