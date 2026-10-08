package com.alnoor.autobot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Alarm fire: normal ringing UI, ya AUTO_CLIENT_REPORT → WhatsApp pe daily clients.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != "com.alnoor.autobot.ALARM_FIRE") return
        val id = intent.getIntExtra("id", 0)
        val daily = intent.getBooleanExtra("daily", false)
        val label = intent.getStringExtra("label") ?: "Alarm"
        if (daily) AlarmEngine.rescheduleDaily(ctx, id)

        if (label.startsWith("AUTO_CLIENT_REPORT") && MemoryVault.dailyReportEnabled(ctx)) {
            val msg = MemoryVault.buildDailyMessage(ctx)
            val my = MemoryVault.myNumber(ctx)
            try {
                if (!my.isNullOrBlank()) {
                    val url = "https://wa.me/" + my.filter { it.isDigit() } + "?text=" +
                        Uri.encode(msg.take(3500))
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                } else {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage("com.whatsapp")
                        putExtra(Intent.EXTRA_TEXT, msg.take(3500))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(i)
                }
            } catch (_: Exception) {
                try {
                    val i = Intent(ctx, AlarmRingingActivity::class.java)
                        .putExtra("id", id)
                        .putExtra("label", "Client report ready — WhatsApp kholo")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    ctx.startActivity(i)
                } catch (_: Exception) {}
            }
            return
        }

        if (label.startsWith("AUTOCALL:")) {
            val bits = label.removePrefix("AUTOCALL:").split("|")
            val phone = bits.getOrNull(0) ?: ""
            val name = bits.getOrNull(1) ?: phone
            if (phone.isNotBlank()) {
                val slot = try { SimDialer.autoSlot(ctx, phone) } catch (_: Exception) { 0 }
                try { SimDialer.noteCall(ctx, slot) } catch (_: Exception) {}
                try { SimDialer.noteContactCall(ctx, phone, slot) } catch (_: Exception) {}
                val res = try { SimDialer.placeCall(ctx, phone, slot) } catch (e: Exception) { "\u274C " + e.message }
                try { android.widget.Toast.makeText(ctx, "\U0001F4DE Scheduled call: $name\n$res", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            }
            return
        }

        val i = Intent(ctx, AlarmRingingActivity::class.java)
            .putExtra("id", id)
            .putExtra("label", label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AlarmEngine.rescheduleAll(ctx)
    }
}
