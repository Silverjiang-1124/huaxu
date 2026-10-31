package com.engnotes.artistorders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal object ReminderSchedule {
    private const val ACTION_NOON = "com.engnotes.artistorders.DUE_REMINDER_SLOT_NOON"
    private const val ACTION_EVENING = "com.engnotes.artistorders.DUE_REMINDER_SLOT_EVENING"
    private const val LEGACY_ACTION_NOON = "com.engnotes.artistorders.DUE_REMINDER_NOON"
    private const val LEGACY_ACTION_EVENING = "com.engnotes.artistorders.DUE_REMINDER_EVENING"
    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, Intent(context, DueReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(context: Context) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val legacyEnabled = preferences.getBoolean("reminders_enabled", true)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancel(context)
        if (preferences.getBoolean("reminder_noon_enabled", legacyEnabled)) {
            scheduleOne(context, alarms, ACTION_NOON, 1200, preferences.getString("reminder_noon_time", "12:00") ?: "12:00")
        }
        if (preferences.getBoolean("reminder_evening_enabled", legacyEnabled)) {
            scheduleOne(context, alarms, ACTION_EVENING, 1800, preferences.getString("reminder_evening_time", "18:00") ?: "18:00")
        }
    }

    private fun scheduleOne(context: Context, alarms: AlarmManager, action: String, id: Int, time: String) {
        val (hour, minute) = time.split(":").map(String::toInt)
        val now = LocalDateTime.now()
        var trigger = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
        if (!trigger.isAfter(now)) trigger = trigger.plusDays(1)
        val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, millis, AlarmManager.INTERVAL_DAY, pending(context, action, id))
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(pending(context, ACTION_NOON, 1200))
        alarms.cancel(pending(context, ACTION_EVENING, 1800))
        alarms.cancel(pending(context, LEGACY_ACTION_NOON, 1200))
        alarms.cancel(pending(context, LEGACY_ACTION_EVENING, 1800))
    }
}

internal object DueReminderNotifications {
    const val CHANNEL_ID = "due_reminders"
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "交期提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "提醒查看当天到期的委托"
            })
        }
    }
}

class DueReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isNoon = action == "com.engnotes.artistorders.DUE_REMINDER_SLOT_NOON"
        val key = if (isNoon) "reminder_noon_enabled" else "reminder_evening_enabled"
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean(key, settings.getBoolean("reminders_enabled", true))) return
        val dbFile = context.getDatabasePath("orders.db")
        if (!dbFile.exists()) return
        val today = LocalDate.now().toString()
        val stats = runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*), COALESCE(SUM(quantity),0), COALESCE(SUM(quantity * minutes_per_image),0) FROM orders WHERE done=0 AND due=?", arrayOf(today)).use { cursor ->
                    if (cursor.moveToFirst()) Triple(cursor.getInt(0), cursor.getInt(1), cursor.getLong(2)) else Triple(0, 0, 0L)
                }
            }
        }.getOrNull() ?: return
        if (stats.first == 0) return
        DueReminderNotifications.createChannel(context)
        val openApp = PendingIntent.getActivity(
            context, 1300, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(context, DueReminderNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("今天有 ${stats.first} 笔委托到期")
            .setContentText("共 ${stats.second} 张 · 预计 ${formatHours(stats.third)} 小时")
            .setStyle(Notification.BigTextStyle().bigText("今天有 ${stats.first} 笔委托计划交付，共 ${stats.second} 张，预计工时 ${formatHours(stats.third)} 小时。"))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
            if (isNoon) 1200 else 1800, notification
        )
    }

    private fun formatHours(minutes: Long): String = "%.1f".format(minutes / 60.0)
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) ReminderSchedule.schedule(context)
    }
}
