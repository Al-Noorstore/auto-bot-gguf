package com.alnoor.autobot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * Dual-SIM default + place call on chosen slot.
 * Pref: sim_default = 0 (SIM1), 1 (SIM2), -1 (ask each time)
 */
object SimDialer {

    private const val PREF = "autobot_sim"
    const val ASK = -1

    data class SimInfo(val slot: Int, val label: String, val subId: Int)

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** -1 = ask, 0 = SIM1, 1 = SIM2 */
    fun defaultSlot(ctx: Context): Int = prefs(ctx).getInt("default_slot", ASK)

    fun setDefaultSlot(ctx: Context, slot: Int) {
        prefs(ctx).edit().putInt("default_slot", slot).apply()
    }

    fun defaultLabel(ctx: Context): String = when (val s = defaultSlot(ctx)) {
        0 -> "SIM 1"
        1 -> "SIM 2"
        else -> "Har call pe poochho"
    }

    fun listSims(ctx: Context): List<SimInfo> {
        val out = mutableListOf<SimInfo>()
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return listOf(SimInfo(0, "SIM 1", -1), SimInfo(1, "SIM 2", -1))
            }
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return listOf(SimInfo(0, "SIM 1", -1), SimInfo(1, "SIM 2", -1))
            val list = if (Build.VERSION.SDK_INT >= 28) {
                sm.activeSubscriptionInfoList
            } else {
                @Suppress("DEPRECATION")
                sm.activeSubscriptionInfoList
            } ?: emptyList()
            list.forEachIndexed { i, info ->
                val slot = info.simSlotIndex.coerceIn(0, 1)
                val name = info.displayName?.toString()?.ifBlank { "SIM ${slot + 1}" } ?: "SIM ${slot + 1}"
                val carrier = info.carrierName?.toString() ?: ""
                val label = if (carrier.isNotBlank()) "SIM ${slot + 1} ($carrier)" else name
                out.add(SimInfo(slot, label, info.subscriptionId))
            }
        } catch (_: Exception) {}
        if (out.isEmpty()) {
            out.add(SimInfo(0, "SIM 1", -1))
            out.add(SimInfo(1, "SIM 2", -1))
        }
        return out.distinctBy { it.slot }.sortedBy { it.slot }
    }

    fun parseSimFromText(low: String): Int? {
        val t = low.lowercase()
        return when {
            Regex("\\bsim\\s*1\\b|\\bsim\\s*one\\b|\\bsim\\s*ek\\b|\\bfirst\\s*sim\\b").containsMatchIn(t) -> 0
            Regex("\\bsim\\s*2\\b|\\bsim\\s*two\\b|\\bsim\\s*do\\b|\\bsecond\\s*sim\\b").containsMatchIn(t) -> 1
            else -> null
        }
    }

    /**
     * Place voice call. slot: 0/1 or null to use system default.
     */
    fun placeCall(ctx: Context, phone: String, slot: Int?): String {
        val num = phone.filter { it.isDigit() || it == '+' }
        if (num.length < 7) return "❌ Number short: $phone"
        // v4.12: AGAIN-CALL — har call ka number yaad rakho ("again" = redial)
        try { ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putString("last_call_num", num).putLong("last_call_time", System.currentTimeMillis()).apply() } catch (_: Exception) {}
        val uri = Uri.fromParts("tel", num, null)
        return try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val useSlot = slot ?: defaultSlot(ctx).takeIf { it >= 0 }
                if (useSlot != null) {
                    intent.putExtra("com.android.phone.extra.slot", useSlot)
                    intent.putExtra("slot", useSlot)
                    intent.putExtra("sim_slot", useSlot)
                    // Android Telecom phone account
                    tryAttachPhoneAccount(ctx, intent, useSlot)
                }
                ctx.startActivity(intent)
                val simTxt = when (useSlot) {
                    0 -> "SIM 1"
                    1 -> "SIM 2"
                    else -> "default SIM"
                }
                "✅ Call lag rahi hai ($simTxt): $num"
            } else {
                try { ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putLong("auto_dial_at", System.currentTimeMillis()).putString("auto_dial_num", num).apply() } catch (_: Exception) {}
                val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(dial)
                "📞 Dialer khula — call button khud dabayenge ($num)"
            }
        } catch (e: Exception) {
            try {
                try { ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putLong("auto_dial_at", System.currentTimeMillis()).putString("auto_dial_num", num).apply() } catch (_: Exception) {}
                ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "📞 Dialer: $num — call button khud dabayenge (${e.message})"
            } catch (e2: Exception) {
                "❌ Call fail: ${e2.message}"
            }
        }
    }

    /** v4.8: AUTO SLOT — bina poochhe best SIM: contact ki aadat -> default setting -> zyada use wali SIM */
    fun autoSlot(ctx: Context, phone: String): Int {
        try { contactSim(ctx, phone)?.let { if (it in 0..1) return it } } catch (_: Exception) {}
        val d = defaultSlot(ctx)
        if (d in 0..1) return d
        val (u0, u1) = try { usage(ctx) } catch (_: Exception) { Pair(0, 0) }
        return if (u1 > u0) 1 else 0
    }

    private fun tryAttachPhoneAccount(ctx: Context, intent: Intent, slot: Int) {
        try {
            if (Build.VERSION.SDK_INT < 23) return
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val accounts: List<PhoneAccountHandle> = tm.callCapablePhoneAccounts ?: return
            if (accounts.isEmpty()) return
            val handle = accounts.getOrNull(slot) ?: accounts.firstOrNull() ?: return
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        } catch (_: Exception) {}
    }

    /** WhatsApp voice / video — needs internet */
    fun whatsAppCall(ctx: Context, phone: String, video: Boolean): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 8) return "❌ WA call ke liye valid number chahiye"
        // jid without + 
        val jid = digits
        return try {
            val action = if (video) "com.whatsapp.intent.action.CALL" else "com.whatsapp.voip.action.CALL"
            // Try official-style voip intent
            val i = Intent().apply {
                setPackage("com.whatsapp")
                this.action = if (video) "com.whatsapp.voip.action.CALL" else "com.whatsapp.voip.action.CALL"
                putExtra("jid", "$jid@s.whatsapp.net")
                putExtra("video_call", video)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (video) {
                i.action = "com.whatsapp.voip.action.CALL"
                i.putExtra("video_call", true)
            }
            try {
                ctx.startActivity(i)
                return if (video) "📹 WhatsApp video call: $jid" else "📞 WhatsApp voice call: $jid"
            } catch (_: Exception) {}
            // Fallback: open chat (user taps call)
            val wa = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$jid")).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(wa)
            "💬 WhatsApp chat khuli ($jid) — ${if (video) "video" else "voice"} call button dabao (direct intent block ho sakta hai)."
        } catch (e: Exception) {
            "❌ WhatsApp call fail: ${e.message}. Data/Wi‑Fi on karo."
        }
    }

    // ================= v4.2: SIM LEARNING (aadat note karo, default suggest karo) =================

    /** Har call par: kis sim se lagi. null = pata nahi (system default). */
    fun noteCall(ctx: Context, slot: Int?) {
        if (slot == null || slot !in 0..1) return
        val p = prefs(ctx)
        p.edit().putInt("use_" + slot, p.getInt("use_" + slot, 0) + 1).apply()
    }

    /** (SIM1 calls, SIM2 calls) ab tak */
    fun usage(ctx: Context): Pair<Int, Int> {
        val p = prefs(ctx)
        return Pair(p.getInt("use_0", 0), p.getInt("use_1", 0))
    }

    /** Per-contact aadat: "Rizwan Bai ko zyada-tar SIM 2 se hi call hoti hai" */
    fun noteContactCall(ctx: Context, phone: String, slot: Int) {
        if (slot !in 0..1) return
        val key = "csim_" + phone.filter { it.isDigit() }.takeLast(7)
        val p = prefs(ctx)
        val parts = (p.getString(key, "") ?: "").split(":")
        val curSlot = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val curCnt = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val newSlot: Int
        val newCnt: Int
        if (curSlot == slot) { newSlot = slot; newCnt = curCnt + 1 }
        else if (curSlot < 0 || curCnt <= 1) { newSlot = slot; newCnt = 1 }
        else { newSlot = curSlot; newCnt = curCnt }  // purani aadat zyada strong — badalta nahi
        p.edit().putString(key, "$newSlot:$newCnt").apply()
    }

    /** Contact ki preferred SIM (2+ baar usi se call hui ho) ya null */
    fun contactSim(ctx: Context, phone: String): Int? {
        val key = "csim_" + phone.filter { it.isDigit() }.takeLast(7)
        val parts = (prefs(ctx).getString(key, "") ?: "").split(":")
        val slot = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val cnt = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return if (slot in 0..1 && cnt >= 2) slot else null
    }

    /** Agar user zyada-tar ek SIM se call karta hai (ASK mode) → us slot ki suggestion (ek hi baar) */
    fun suggestSlot(ctx: Context): Int? {
        val p = prefs(ctx)
        if (p.getBoolean("sim_suggest_done", false)) return null
        if (defaultSlot(ctx) != ASK) return null
        val (u0, u1) = usage(ctx)
        if (u0 + u1 < 3) return null
        val lead = if (u0 >= u1) 0 else 1
        val leadU = if (lead == 0) u0 else u1
        val otherU = if (lead == 0) u1 else u0
        if (leadU - otherU < 2) return null
        return lead
    }

    fun noteSuggestShown(ctx: Context) {
        prefs(ctx).edit().putBoolean("sim_suggest_done", true).apply()
    }

    /** User ne khud default choose kiya (page ya chat) — ab dobara suggestion nahi karenge */
    fun noteUserChoseDefault(ctx: Context) {
        prefs(ctx).edit().putBoolean("sim_suggest_done", true).apply()
    }

    fun statusText(ctx: Context): String {
        val sims = listSims(ctx)
        val def = defaultSlot(ctx)
        val sb = StringBuilder("📱 *SIM Dialer*\n\n")
        sb.append("Default: **${defaultLabel(ctx)}**\n\n")
        sims.forEach { sb.append("• ${it.label} (slot ${it.slot + 1})\n") }
        val (u0, u1) = usage(ctx)
        sb.append("\nAapki aadat: SIM 1 = $u0 calls, SIM 2 = $u1 calls\n")
        sb.append("\nChat:\n• sim 1 default / sim 2 default / sim ask\n• call Ali sim 1\n• wa call Ali / wa video Ali\n")
        return sb.toString()
    }
}
