package com.alnoor.autobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Multiple projects — alag folders + notes + last commands.
 * Chat: project list | project open X | project new X | project note ...
 */
object ProjectStore {

    private const val PREF = "autobot_projects"

    data class Project(
        val id: String,
        var name: String,
        val path: String,
        var notes: String = "",
        var lastTask: String = "",
        var updated: Long = System.currentTimeMillis()
    )

    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work").apply { mkdirs() }

    fun list(ctx: Context): List<Project> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("list", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<Project>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Project(
                    o.optString("id"),
                    o.optString("name"),
                    o.optString("path"),
                    o.optString("notes"),
                    o.optString("lastTask"),
                    o.optLong("updated", 0L)
                )
            )
        }
        return out.sortedByDescending { it.updated }
    }

    private fun save(ctx: Context, list: List<Project>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("path", p.path)
                    .put("notes", p.notes)
                    .put("lastTask", p.lastTask)
                    .put("updated", p.updated)
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
    }

    fun activeId(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("active", null)

    fun setActive(ctx: Context, id: String?) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("active", id).apply()
    }

    fun active(ctx: Context): Project? {
        val id = activeId(ctx) ?: return null
        return list(ctx).firstOrNull { it.id == id }
    }

    fun create(ctx: Context, name: String): Project {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "project" }
        val id = System.currentTimeMillis().toString(36)
        val dir = File(root(ctx), safe).apply { mkdirs(); File(this, "libs").mkdirs() }
        val p = Project(id, safe, dir.absolutePath)
        val all = list(ctx).toMutableList()
        all.add(0, p)
        save(ctx, all)
        setActive(ctx, id)
        return p
    }

    fun open(ctx: Context, query: String): Project? {
        val q = query.trim().lowercase()
        val all = list(ctx)
        val byIndex = q.toIntOrNull()?.let { n -> all.getOrNull(n - 1) }
        val p = byIndex ?: all.firstOrNull {
            it.name.lowercase() == q || it.name.lowercase().contains(q) || it.id == q
        } ?: return null
        setActive(ctx, p.id)
        p.updated = System.currentTimeMillis()
        save(ctx, all.map { if (it.id == p.id) p else it })
        return p
    }

    fun note(ctx: Context, text: String): Boolean {
        val a = active(ctx) ?: return false
        val all = list(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == a.id }
        if (i < 0) return false
        all[i].notes = (all[i].notes + "\n" + text).trim()
        all[i].updated = System.currentTimeMillis()
        save(ctx, all)
        return true
    }

    fun setLastTask(ctx: Context, task: String) {
        val a = active(ctx) ?: return
        val all = list(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == a.id }
        if (i < 0) return
        all[i].lastTask = task
        all[i].updated = System.currentTimeMillis()
        save(ctx, all)
    }


    fun memoryGet(ctx: Context, key: String): String {
        val a = active(ctx) ?: return ""
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("mem_" + a.id, "{}") ?: "{}"
        return try { org.json.JSONObject(raw).optString(key, "") } catch (_: Exception) { "" }
    }

    fun memorySet(ctx: Context, key: String, value: String) {
        val a = active(ctx) ?: return
        val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = pref.getString("mem_" + a.id, "{}") ?: "{}"
        val o = try { org.json.JSONObject(raw) } catch (_: Exception) { org.json.JSONObject() }
        o.put(key, value)
        pref.edit().putString("mem_" + a.id, o.toString()).apply()
    }

    fun memoryAll(ctx: Context): String {
        val a = active(ctx) ?: return "❌ Pehle project open karo"
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("mem_" + a.id, "{}") ?: "{}"
        val o = try { org.json.JSONObject(raw) } catch (_: Exception) { org.json.JSONObject() }
        if (o.length() == 0) return "🧠 Project '${a.name}' memory khali. 'remember key | value'"
        val sb = StringBuilder("🧠 *Project ${a.name} memory*\n")
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            sb.append("• $k = ${o.optString(k)}\n")
        }
        return sb.toString()
    }

    // ---------- v4.4: per-project TODO / pending list ----------
    fun todoAdd(ctx: Context, text: String): String {
        val a = active(ctx) ?: return "❌ Pehle project open karo (project open <naam>)"
        val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = try { org.json.JSONArray(pref.getString("todo_" + a.id, "[]") ?: "[]") } catch (_: Exception) { org.json.JSONArray() }
        arr.put(text)
        pref.edit().putString("todo_" + a.id, arr.toString()).apply()
        return "📝 Todo add: $text\n(total ${arr.length()}) — 'todo' se list, 'todo done <number>' se hatao"
    }

    fun todoList(ctx: Context): String {
        val a = active(ctx) ?: return "❌ Pehle project open karo (project open <naam>)"
        val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = try { org.json.JSONArray(pref.getString("todo_" + a.id, "[]") ?: "[]") } catch (_: Exception) { org.json.JSONArray() }
        if (arr.length() == 0) return "📝 Project '${a.name}' mein koi pending nahi 🎉"
        val sb = StringBuilder("📝 *${a.name} — pending:*\n")
        for (i in 0 until arr.length()) sb.append("${i + 1}. ${arr.optString(i)}\n")
        sb.append("\nDone hone par: todo done <number>")
        return sb.toString()
    }

    fun todoDone(ctx: Context, n: Int): String {
        val a = active(ctx) ?: return "❌ Pehle project open karo"
        val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = try { org.json.JSONArray(pref.getString("todo_" + a.id, "[]") ?: "[]") } catch (_: Exception) { org.json.JSONArray() }
        if (n < 1 || n > arr.length()) return "❌ Number galat — 'todo' se list dekho"
        val done = arr.optString(n - 1)
        val na = org.json.JSONArray()
        for (i in 0 until arr.length()) if (i != n - 1) na.put(arr.get(i))
        pref.edit().putString("todo_" + a.id, na.toString()).apply()
        return "✅ Done: $done\n" + if (na.length() == 0) "Sab clear! 🎉" else "Baaki pending: ${na.length()}"
    }

    fun status(ctx: Context): String {
        val all = list(ctx)
        val act = active(ctx)
        val sb = StringBuilder("📁 *Projects*\n")
        if (all.isEmpty()) sb.append("— koi nahi. 'project new myapp'\n")
        all.forEachIndexed { i, p ->
            val mark = if (act?.id == p.id) " 👉 active" else ""
            sb.append("${i + 1}. ${p.name}$mark\n   ${p.path}\n")
            if (p.notes.isNotBlank()) sb.append("   note: ${p.notes.take(80)}\n")
            if (p.lastTask.isNotBlank()) sb.append("   last task: ${p.lastTask.take(60)}\n")
        }
        sb.append("\n💡 project new <name> | project open <name|n> | project note <text> | project status")
        return sb.toString()
    }
}
