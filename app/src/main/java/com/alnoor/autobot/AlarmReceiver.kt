package com.alnoor.autobot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v3.0 — Alarm fire hone par ringing activity kholta hai.
 * Daily alarm ke liye khud ko next day dobara schedule karta hai.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != "com.alnoor.autobot.ALARM_FIRE") return
        val id = intent.getIntExtra("id", 0)
        val daily = intent.getBooleanExtra("daily", false)
        if (daily) AlarmEngine.rescheduleDaily(ctx, id)
        val i = Intent(ctx, AlarmRingingActivity::class.java)
            .putExtra("id", id)
            .putExtra("label", intent.getStringExtra("label") ?: "Alarm")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }
}

/**
 * Phone boot: roz ke alarms dobara schedule.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AlarmEngine.rescheduleAll(ctx)
    }
}
