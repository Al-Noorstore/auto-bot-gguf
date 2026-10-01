package com.alnoor.autobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hamesha yaad: mera number, clients/suppliers (project-scoped optional), daily report.
 * Duplicate name/number detect — confirm ke baad overwrite.
 */
object MemoryVault {

    private const val PREF = "autobot_memory"

    fun myNumber(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("my_number", null)

    fun setMyNumber(ctx: Context, num: String) {
        val n = OfflineBrain.normalizePhone(num)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("my_number", n).apply()
    }

    data class Client(
        val name: String,
        val phone: String = "",
        val email: String = "",
        val profile: String = "",
        val country: String = "",
        val niche: String = "",
        val interest: String = "",
        val source: String = "",
        val notes: String = "",
        val role: String = "client", // client | supplier
        val projectId: String = "", // empty = global
        val updated: Long = System.currentTimeMillis()
    )

    fun clients(ctx: Context, projectId: String? = null, role: String? = null): MutableList<Client> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("clients", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<Client>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = Client(
                o.optString("name"), o.optString("phone"), o.optString("email"),
                o.optString("profile"), o.optString("country"), o.optString("niche"),
                o.optString("interest"), o.optString("source"), o.optString("notes"),
                o.optString("role", "client"), o.optString("projectId"),
                o.optLong("updated", 0L)
            )
            if (projectId != null) {
                if (projectId.isEmpty() && c.projectId.isNotEmpty()) continue
                if (projectId.isNotEmpty() && c.projectId != projectId && c.projectId.isNotEmpty()) continue
                // active project: show project-specific + global (no projectId)
                if (projectId.isNotEmpty() && c.projectId.isNotEmpty() && c.projectId != projectId) continue
            }
            if (role != null && !c.role.equals(role, true)) continue
            out.add(c)
        }
        // filter properly for project
        if (projectId != null && projectId.isNotEmpty()) {
            return out.filter { it.projectId == projectId || it.projectId.isEmpty() }.toMutableList()
        }
        return out
    }

    fun allRaw(ctx: Context): MutableList<Client> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("clients", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<Client>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Client(
                    o.optString("name"), o.optString("phone"), o.optString("email"),
                    o.optString("profile"), o.optString("country"), o.optString("niche"),
                    o.optString("interest"), o.optString("source"), o.optString("notes"),
                    o.optString("role", "client"), o.optString("projectId"),
                    o.optLong("updated", 0L)
                )
            )
        }
        return out
    }

    private fun saveClients(ctx: Context, list: List<Client>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("name", c.name).put("phone", c.phone).put("email", c.email)
                    .put("profile", c.profile).put("country", c.country).put("niche", c.niche)
                    .put("interest", c.interest).put("source", c.source).put("notes", c.notes)
                    .put("role", c.role).put("projectId", c.projectId).put("updated", c.updated)
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("clients", arr.toString()).apply()
    }

    data class DupCheck(val byName: Client?, val byPhone: Client?)

    fun findDuplicates(ctx: Context, name: String, phone: String, projectId: String = ""): DupCheck {
        val all = allRaw(ctx)
        val byName = all.firstOrNull {
            it.name.equals(name, true) && (projectId.isEmpty() || it.projectId == projectId || it.projectId.isEmpty())
        }
        val norm = phone.filter { it.isDigit() }
        val byPhone = if (norm.length >= 8) all.firstOrNull {
            val p = it.phone.filter { ch -> ch.isDigit() }
            p.isNotEmpty() && (p.endsWith(norm.takeLast(10)) || norm.endsWith(p.takeLast(10)))
        } else null
        return DupCheck(byName, byPhone)
    }

    fun addClient(ctx: Context, c: Client, force: Boolean = true) {
        val list = allRaw(ctx)
        val i = list.indexOfFirst {
            it.name.equals(c.name, true) && it.projectId == c.projectId
        }
        if (i >= 0) list[i] = c.copy(updated = System.currentTimeMillis()) else list.add(0, c)
        saveClients(ctx, list)
    }

    fun findByName(ctx: Context, query: String, projectId: String? = null): List<Client> {
        val q = query.trim().lowercase()
        val list = if (projectId != null) clients(ctx, projectId) else allRaw(ctx)
        return list.filter {
            it.name.lowercase() == q || it.name.lowercase().contains(q) ||
                (it.phone.isNotEmpty() && it.phone.contains(q))
        }
    }

    fun clientsReport(ctx: Context, projectId: String? = null): String {
        val list = if (projectId != null) clients(ctx, projectId) else allRaw(ctx)
        if (list.isEmpty()) return "📭 Abhi koi client/supplier save nahi.\nclient add Name | phone +92... | role client"
        val sb = StringBuilder("👥 *Saved contacts* (${list.size})\n")
        if (projectId != null) sb.append("(active project + global)\n")
        sb.append("\n")
        list.take(40).forEachIndexed { i, c ->
            val role = if (c.role == "supplier") "🏭" else "👤"
            sb.append("${i + 1}. $role *${c.name}*")
            if (c.projectId.isNotEmpty()) sb.append(" [proj]")
            sb.append("\n")
            if (c.phone.isNotBlank()) sb.append("   📞 ${c.phone}\n")
            if (c.email.isNotBlank()) sb.append("   📧 ${c.email}\n")
            if (c.profile.isNotBlank()) sb.append("   🔗 ${c.profile}\n")
            if (c.country.isNotBlank()) sb.append("   🌍 ${c.country}\n")
            if (c.niche.isNotBlank()) sb.append("   🏷 ${c.niche}\n")
            if (c.interest.isNotBlank()) sb.append("   🛒 ${c.interest}\n")
            if (c.notes.isNotBlank()) sb.append("   📝 ${c.notes.take(80)}\n")
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    fun dailyReportEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("daily_wa_report", false)

    fun setDailyReport(ctx: Context, on: Boolean, hour: Int = 9, minute: Int = 0) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("daily_wa_report", on)
            .putInt("daily_wa_hour", hour)
            .putInt("daily_wa_min", minute)
            .apply()
    }

    fun dailyHour(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("daily_wa_hour", 9)
    fun dailyMin(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("daily_wa_min", 0)

    fun buildDailyMessage(ctx: Context): String {
        val list = allRaw(ctx)
        val sb = StringBuilder("📋 *Auto Bot — Daily report*\n${java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}\n\n")
        if (list.isEmpty()) sb.append("Koi client/supplier save nahi.\n")
        else {
            sb.append("Total: ${list.size}\n\n")
            list.take(15).forEach { c ->
                sb.append("• ${c.name} (${c.role})")
                if (c.phone.isNotBlank()) sb.append(" ${c.phone}")
                if (c.niche.isNotBlank()) sb.append(" | ${c.niche}")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    // pending duplicate confirm
    fun setPendingDup(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("pending_dup", json).apply()
    }

    fun getPendingDup(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("pending_dup", null)

    fun clearPendingDup(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("pending_dup").apply()
    }
}
