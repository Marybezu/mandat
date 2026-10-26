package ua.mandat.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ua.mandat.R
import ua.mandat.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Daily reminders that carry no details at all.
 *
 * The database stays encrypted and cannot be read in the background, so when Mandat locks it
 * stores only the list of days on which something is due (no titles, no names). On such a day,
 * at the chosen hour, a reminder says only that there is something to look at.
 */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY_ON = "on"
    private const val KEY_HOUR = "hour"
    private const val KEY_DAYS = "days"
    private const val CHANNEL = "reminders"
    private const val NOTIFICATION_ID = 1
    const val DEFAULT_HOUR = 9

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context) = prefs(context).getBoolean(KEY_ON, false)

    fun hour(context: Context) = prefs(context).getInt(KEY_HOUR, DEFAULT_HOUR)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
        if (!on) {
            prefs(context).edit().remove(KEY_DAYS).apply()
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
        schedule(context)
    }

    fun setHour(context: Context, hour: Int) {
        prefs(context).edit().putInt(KEY_HOUR, hour.coerceIn(0, 23)).apply()
        schedule(context)
    }

    /** Called when Mandat locks: remembers only on which days something is due. */
    fun storeDays(context: Context, days: List<Long>) {
        if (!isOn(context)) return
        prefs(context).edit().putString(KEY_DAYS, days.take(400).joinToString(",")).apply()
        schedule(context)
    }

    private fun days(context: Context): List<Long> =
        prefs(context).getString(KEY_DAYS, "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }

    /** Sets one alarm for the next reminder day, or none. Not exact: no special permission needed. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        alarms.cancel(pending)
        if (!isOn(context)) return
        val now = LocalDateTime.now()
        val hour = hour(context)
        val next = days(context).asSequence()
            .map { LocalDate.ofEpochDay(it).atTime(hour, 0) }
            .firstOrNull { it.isAfter(now) } ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 20 * 60 * 1000L, pending)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun show(context: Context) {
        val today = LocalDate.now().toEpochDay()
        if (!isOn(context) || today !in days(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.reminder_text)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            // The same plain text on the lock screen; there are no details to hide anyway
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications not allowed; nothing to do
        }
    }

    const val ACTION_REMIND = "ua.mandat.REMIND"
}

/** Wakes up for a reminder, and re-arms after a restart, an update or a time change. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Reminders.ACTION_REMIND) Reminders.show(context)
        Reminders.schedule(context)
    }
}
