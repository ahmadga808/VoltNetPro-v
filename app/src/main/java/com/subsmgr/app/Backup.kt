package com.subsmgr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class BackupWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result = try { AutoBackup.run(applicationContext); Result.success() } catch (e: Exception) { Result.retry() }
}

/** نسخ احتياطي يومي لقاعدتَي النشاطين إلى مجلد يختاره المستخدم، مع الاحتفاظ بآخر 7 نسخ لكل نشاط. */
object AutoBackup {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_global", Context.MODE_PRIVATE)

    fun dirUri(ctx: Context): Uri? = prefs(ctx).getString("backup_dir", "")?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
    fun lastRun(ctx: Context): String = prefs(ctx).getString("last_auto_backup", "") ?: ""

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("auto_backup", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun setDir(ctx: Context, uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(ctx).edit().putString("backup_dir", uri.toString()).apply()
        schedule(ctx)
    }

    fun disable(ctx: Context) {
        prefs(ctx).edit().remove("backup_dir").apply()
        WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork("auto_backup")
    }

    /** يعيد عدد الملفات المنسوخة. */
    fun run(ctx: Context): Int {
        val uri = dirUri(ctx) ?: return 0
        val dir = DocumentFile.fromTreeUri(ctx, uri) ?: throw IllegalStateException("المجلد غير متاح")
        val date = LocalDate.now().toString()
        var n = 0
        for (mode in MODES.keys) {
            val f = ctx.getDatabasePath(dbFileName(mode))
            if (!f.exists()) continue
            val name = "auto_${mode}_$date.db"
            dir.findFile(name)?.delete()
            val out = dir.createFile("application/octet-stream", name) ?: continue
            ctx.contentResolver.openOutputStream(out.uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            n++
            dir.listFiles().filter { (it.name ?: "").startsWith("auto_${mode}_") }
                .sortedByDescending { it.name ?: "" }.drop(7).forEach { it.delete() }
        }
        prefs(ctx).edit().putString("last_auto_backup", LocalDateTime.now().toString().take(16).replace('T', ' ')).apply()
        return n
    }
}
