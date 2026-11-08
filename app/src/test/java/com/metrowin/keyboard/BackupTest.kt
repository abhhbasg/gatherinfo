package com.metrowin.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metrowin.keyboard.backup.Backup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun settingsAndFilesComeBackAfterTheyAreLost() {
        val prefs = KeyboardApp.of(c).prefs
        prefs.putInt(K.KEY_HEIGHT, 120); prefs.putString(K.GIF_API_KEY, "secret"); prefs.putBoolean(K.VIBRATION, false)
        File(c.filesDir, "user").apply { mkdirs() }.resolve("it.txt").writeText("U\tciao\t3\n")
        File(c.filesDir, "background.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val out = ByteArrayOutputStream(); Backup.write(c, out)

        prefs.sp.edit().clear().commit()
        File(c.filesDir, "user").deleteRecursively(); File(c.filesDir, "background.jpg").delete()
        assertEquals(100, prefs.keyHeight)

        assertTrue(Backup.restore(c, ByteArrayInputStream(out.toByteArray())) >= 3)
        assertEquals(120, prefs.keyHeight); assertEquals("secret", prefs.gifApiKey); assertFalse(prefs.vibration)
        assertEquals("U\tciao\t3\n", File(c.filesDir, "user/it.txt").readText())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(c.filesDir, "background.jpg").readBytes())
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private val meta = "backup.json" to """{"format":"metrowin-keyboard-backup","version":1,"prefs":[]}"""

    @Test fun aFileThatLeavesItsFolderIsRefusedAndNothingIsTouched() {
        val prefs = KeyboardApp.of(c).prefs
        prefs.putInt(K.KEY_HEIGHT, 130)
        for (bad in listOf("files/../evil.txt", "files/user/../../evil.txt", "files//etc/evil", "files/other/x.txt", "outside.txt")) {
            assertThrows(bad, Backup.Invalid::class.java) { Backup.restore(c, ByteArrayInputStream(zip(meta, bad to "x"))) }
        }
        assertEquals(130, prefs.keyHeight)
    }

    @Test fun somethingThatIsNotABackupIsRefused() {
        assertThrows(Exception::class.java) { Backup.restore(c, ByteArrayInputStream("not a zip".toByteArray())) }
        assertThrows(Backup.Invalid::class.java) { Backup.restore(c, ByteArrayInputStream(zip("backup.json" to """{"format":"other","version":1,"prefs":[]}"""))) }
    }
}
