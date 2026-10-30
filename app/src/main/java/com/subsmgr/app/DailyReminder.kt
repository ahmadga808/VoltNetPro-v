package com.subsmgr.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class ReminderWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        try { DailyReminder.notifyNow(applicationContext) } catch (e: Exception) { }
        return Result.success()
    }
}

/** إشعار يومي الساعة 9 صباحاً بعدد المنتهين قريباً والمدينين. */
object DailyReminder {
    private const val CH = "daily_alerts"

    fun schedule(ctx: Context) {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(9, 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val delay = Duration.between(now, at).toMinutes()
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).setInitialDelay(delay, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("daily_reminder", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** يطلب إذن الإشعارات (أندرويد 13+) مرة واحدة. */
    fun askPermission(act: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val p = act.getSharedPreferences("app_global", Context.MODE_PRIVATE)
        if (p.getBoolean("asked_notif", false)) return
        p.edit().putBoolean("asked_notif", true).apply()
        ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
    }

    /** يعرض إشعاراً عاماً (يستخدمه تقرير الرسائل اليومي). */
    fun post(ctx: Context, id: Int, channel: String, channelName: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text.lineSequence().firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(id, n)
    }

    fun notifyNow(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = Db(ctx).alertsText()
        if (text.isBlank()) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "تنبيهات يومية", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("تنبيهات الاشتراكات")
            .setContentText(text.lineSequence().firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(1001, n)
    }
}
