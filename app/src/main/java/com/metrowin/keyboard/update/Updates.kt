package com.metrowin.keyboard.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.ime.ContentSender
import java.io.File
import java.io.IOException

/** Looks for a newer keyboard in this repository's releases, downloads it and hands it to the installer. See [Repo] for the one place it looks. */
object Updates {
    const val EVERY_MS = 12L * 60 * 60 * 1000

    fun installed(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()

    /** The newer release found by the last check, if it is still newer than what is installed. */
    fun pending(c: Context): Release? = Release.fromJson(KeyboardApp.of(c).prefs.updateRelease)?.takeIf { Versions.isNewer(it.version, installed(c)) }

    /** Looks now and keeps the outcome. Throws [IOException] when GitHub cannot be reached. */
    fun check(c: Context): Release? {
        val prefs = KeyboardApp.of(c).prefs
        // No release published yet is the same as no update.
        val found = try { UpdateChecker { UpdateNet.getString(it) }.newerThan(installed(c)) } catch (e: NotFound) { null }
        prefs.updateLast = System.currentTimeMillis()
        prefs.updateRelease = found?.toJson() ?: ""
        return found
    }

    /**
     * Downloads the keyboard file of [r] and checks it: its size, and its SHA-256 against the SHA256SUMS of the same release. A file that
     * does not match is deleted. (Android then checks that it is signed with the same key as the installed app, which is the real lock.)
     */
    fun download(c: Context, r: Release, progress: (Long) -> Unit): File {
        require(r.apkUrl.startsWith(Repo.DOWNLOADS)) { "Not a file of this repository" }
        val dir = File(c.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, r.apkName)
        val actual = UpdateNet.download(r.apkUrl, file, progress = progress)
        try {
            if (r.apkSize > 0 && file.length() != r.apkSize) throw IOException("size does not match")
            r.sumsUrl?.takeIf { it.startsWith(Repo.DOWNLOADS) }?.let { url ->
                val expected = UpdateChecker.checksumOf(UpdateNet.getString(url), r.apkName)
                if (expected == null || expected != actual) throw IOException("checksum does not match")
            }
        } catch (e: IOException) { file.delete(); throw e }
        return file
    }

    /** Opens the system installer on [file]. False when the app first has to be allowed to install apps (the settings page for it is opened). */
    fun install(c: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(c, ContentSender.authority(c), file)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

/** Keeps one periodic job that checks every 12 hours (when the setting is on); the system runs it with network, even after a restart. */
object UpdateScheduler {
    private const val JOB_ID = 4412

    fun sync(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (!KeyboardApp.of(c).prefs.updateAuto) { js.cancel(JOB_ID); return }
        // Scheduling again restarts the period, so a job that is already there with this period is left alone.
        if (js.getPendingJob(JOB_ID)?.intervalMillis == Updates.EVERY_MS) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(Updates.EVERY_MS).setPersisted(true).build(),
        )
    }
}

class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread { runCatching { Updates.check(applicationContext) }; jobFinished(params, false) }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
