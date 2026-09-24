package com.alnoor.autobot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * v3.0 — ALARM ENGINE: one-time + regular (roz ka) alarms, in-app.
 *  - "alarm lagao 6 baje"       → one-time (kal/aaj 6:00)
 *  - "roz ka alarm 6 baje"      → daily recurring
 *  - "5 minute ka alarm"        → timer (duration)
 *  - "alarm list / dikhao"      → sab alarms
 *  - "alarm hatao / cancel"     → remove (sab ya aakhri wala)
 * Alarms app ke andar AlarmManager se hote hain; phone restart ke baad
 * BootReceiver dobara schedule kar deta hai. Ring: AlarmRingingActivity
 * (loud sound + snooze 5 min + stop).
 */
object AlarmEngine {

    class AbAlarm(val id: Int, val hour: Int, val minute: Int, val daily: Boolean,
                  val label: String, var enabled: Boolean = true)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<AbAlarm> {
        val out = ArrayList<AbAlarm>()
        try {
            val arr = JSONArray(prefs(ctx).getString("brain_alarms", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(AbAlarm(o.optInt("id"), o.optInt("hour"), o.optInt("minute"), o.optBoolean("daily"), o.optString("label"), o.optBoolean("enabled", true)))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun save(ctx: Context, list: List<AbAlarm>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("id", a.id); o.put("hour", a.hour); o.put("minute", a.minute)
            o.put("daily", a.daily); o.put("label", a.label); o.put("enabled", a.enabled)
            arr.put(o)
        }
        prefs(ctx).edit().putString("brain_alarms", arr.toString()).apply()
    }

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(ctx: Context, a: AbAlarm): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction("com.alnoor.autobot.ALARM_FIRE")
            .putExtra("id", a.id)
            .putExtra("label", a.label)
            .putExtra("daily", a.daily)
            .putExtra("hour", a.hour)
            .putExtra("minute", a.minute)
        return PendingIntent.getBroadcast(ctx, a.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Next trigger time: daily → kal/aaj (future), one-time → aaj ya kal (future). */
    private fun nextTrigger(a: AbAlarm, addDays: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, a.hour)
        cal.set(Calendar.MINUTE, a.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, addDays)
        while (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun schedule(ctx: Context, hour: Int, minute: Int, daily: Boolean, label: String): AbAlarm {
        val alarms = list(ctx)
        val id = (prefs(ctx).getInt("brain_alarm_seq", 1)).also { prefs(ctx).edit().putInt("brain_alarm_seq", it + 1).apply() }
        val a = AbAlarm(id, hour, minute, daily, label)
        alarms.add(a)
        save(ctx, alarms)
        reschedule(ctx, a)
        return a
    }

    private fun reschedule(ctx: Context, a: AbAlarm) {
        try {
            val pi = pending(ctx, a)
            val at = nextTrigger(a)
            if (Build.VERSION.SDK_INT >= 31 && !am(ctx).canScheduleExactAlarms()) am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) {
            try { am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger(a), pending(ctx, a)) } catch (_: Exception) {}
        }
    }

    /** Receiver se daily alarm dobara schedule (next day). */
    fun rescheduleDaily(ctx: Context, id: Int) {
        val a = list(ctx).firstOrNull { it.id == id } ?: return
        if (a.daily && a.enabled) reschedule(ctx, a)
    }

    /** Timer (duration seconds) — one-shot, id negative (conflict na ho). */
    fun scheduleTimer(ctx: Context, seconds: Long, label: String): AbAlarm {
        val seq = prefs(ctx).getInt("brain_alarm_seq", 1)
        prefs(ctx).edit().putInt("brain_alarm_seq", seq + 1).apply()
        val a = AbAlarm(-seq, 0, 0, false, label)
        try {
            val intent = Intent(ctx, AlarmReceiver::class.java)
                .setAction("com.alnoor.autobot.ALARM_FIRE")
                .putExtra("id", a.id).putExtra("label", label).putExtra("daily", false)
            val pi = PendingIntent.getBroadcast(ctx, a.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = System.currentTimeMillis() + seconds * 1000
            if (Build.VERSION.SDK_INT >= 31 && !am(ctx).canScheduleExactAlarms()) am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) { /* best effort */ }
        return a
    }

    fun cancel(ctx: Context, id: Int): Boolean {
        val alarms = list(ctx)
        val a = alarms.firstOrNull { it.id == id } ?: return false
        try { am(ctx).cancel(pending(ctx, a)) } catch (_: Exception) {}
        alarms.removeAll { it.id == id }
        save(ctx, alarms)
        return true
    }

    fun cancelAll(ctx: Context): Int {
        val alarms = list(ctx)
        for (a in alarms) { try { am(ctx).cancel(pending(ctx, a)) } catch (_: Exception) {} }
        save(ctx, emptyList())
        return alarms.size
    }

    /** Phone restart ke baad sab (enabled) alarms dobara schedule. */
    fun rescheduleAll(ctx: Context) {
        for (a in list(ctx)) if (a.enabled) reschedule(ctx, a)
    }

    fun pretty(a: AbAlarm): String {
        val h = String.format("%02d:%02d", a.hour, a.minute)
        return "$h" + (if (a.daily) " (roz ka)" else " (one-time)") + if (a.label.isNotBlank()) " — ${a.label}" else ""
    }

    /** "alarm list" ke liye text. */
    fun listText(ctx: Context): String {
        val alarms = list(ctx)
        if (alarms.isEmpty()) return "⏰ Koi alarm set nahi hai.\nSet: \"alarm lagao 6 baje\" ya \"roz ka alarm 7 baje\"\nTimer: \"5 minute ka alarm\""
        return "⏰ Alarms (${alarms.size}):\n" + alarms.joinToString("\n") { "• " + pretty(it) } +
            "\n\nRemove: \"alarm hatao\" (aakhri) ya \"sab alarm hatao\""
    }
}
