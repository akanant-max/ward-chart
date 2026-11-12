package com.wardchart.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Arms the reminders the page computes.
 *
 * Division of labour: the page (assets/index.html) owns every rule — which
 * blocks remind, at what time, on which weekdays, how often to nag — and turns
 * them into a flat list of concrete fire times for the next week. This object
 * knows nothing about the rules. It takes that list, cancels whatever it armed
 * last time, arms the new set with AlarmManager, and stores the list so a
 * reboot or an app update can re-arm it without the page running.
 *
 * Each list entry: { key, at (epoch ms), title, body, kind, id }.
 *   key     stable string; hashed into the PendingIntent request code
 *   kind+id hashed into the notification id, so a nag replaces the previous
 *           notification for the same block rather than stacking four of them
 */
object Reminders {

    const val CHANNEL = "reminders"
    private const val PREFS = "wardchart"
    private const val KEY_SCHEDULE = "reminder_schedule"
    private const val KEY_CODES = "reminder_codes"
    private const val ACTION = "com.wardchart.app.REMIND"
    private const val MAX_ITEMS = 150
    private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Block and chore reminders"
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    fun notificationsAllowed(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** Exact alarms need a grant on Android 12+; without it we fall back to a 10-minute window. */
    fun exactAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun codeFor(key: String): Int = key.hashCode() and 0x7fffffff
    private fun notifIdFor(kind: String, id: String): Int = "$kind:$id".hashCode() and 0x7fffffff

    /**
     * Cancelling needs an Intent that filterEquals the one armed: same action
     * and component, extras ignored. So the cancel path passes item = null and
     * still matches.
     */
    private fun pending(ctx: Context, code: Int, item: JSONObject?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION
            if (item != null) {
                putExtra("title", item.optString("title", "Ward Chart"))
                putExtra("body", item.optString("body", ""))
                putExtra("notifId", notifIdFor(item.optString("kind", "x"), item.optString("id", "x")))
            }
        }
        return PendingIntent.getBroadcast(
            ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val codes = prefs(ctx).getStringSet(KEY_CODES, emptySet()) ?: emptySet()
        for (c in codes) {
            val code = c.toIntOrNull() ?: continue
            try { am.cancel(pending(ctx, code, null)) } catch (_: Exception) {}
        }
        prefs(ctx).edit().remove(KEY_CODES).apply()
    }

    /** Replace the whole schedule with this list. Returns how many were armed. */
    fun apply(ctx: Context, json: String): Int {
        ensureChannel(ctx)
        cancelAll(ctx)

        val items = try {
            JSONObject(json).optJSONArray("items") ?: JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }

        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = exactAllowed(ctx)
        val now = System.currentTimeMillis()
        val codes = HashSet<String>()
        var armed = 0

        val n = minOf(items.length(), MAX_ITEMS)
        for (k in 0 until n) {
            val item = items.optJSONObject(k) ?: continue
            val at = item.optLong("at", 0L)
            if (at <= now) continue
            val key = item.optString("key", "")
            if (key.isEmpty()) continue

            val code = codeFor(key)
            val pi = pending(ctx, code, item)
            val ok = try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setWindow(AlarmManager.RTC_WAKEUP, at, INEXACT_WINDOW_MS, pi)
                true
            } catch (_: SecurityException) {
                // Exact grant was revoked between the check and the call.
                try { am.setWindow(AlarmManager.RTC_WAKEUP, at, INEXACT_WINDOW_MS, pi); true }
                catch (_: Exception) { false }
            }
            if (ok) { codes.add(code.toString()); armed++ }
        }

        prefs(ctx).edit().putString(KEY_SCHEDULE, json).putStringSet(KEY_CODES, codes).apply()
        return armed
    }

    /** After a reboot or an update every alarm is gone; re-arm the last list. */
    fun rearmStored(ctx: Context) {
        val json = prefs(ctx).getString(KEY_SCHEDULE, null) ?: return
        apply(ctx, json)
    }

    @SuppressLint("MissingPermission") // guarded by notificationsAllowed()
    fun show(ctx: Context, notifId: Int, title: String, body: String) {
        ensureChannel(ctx)
        if (!notificationsAllowed(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(notifId, n) } catch (_: SecurityException) {}
    }

    fun dismiss(ctx: Context, kind: String, id: String) {
        try { NotificationManagerCompat.from(ctx).cancel(notifIdFor(kind, id)) } catch (_: Exception) {}
    }
}

/** Fired by AlarmManager; shows one notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.show(
            ctx,
            intent.getIntExtra("notifId", 1),
            intent.getStringExtra("title") ?: "Ward Chart",
            intent.getStringExtra("body") ?: ""
        )
    }
}

/** Alarms do not survive a reboot or an app update; this puts them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.rearmStored(ctx)
        }
    }
}
