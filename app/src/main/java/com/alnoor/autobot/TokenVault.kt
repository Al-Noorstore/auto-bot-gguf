package com.alnoor.autobot

import android.content.Context
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * v3.0 — TOKEN VAULT: kisi bhi platform ka access token (GitHub, Vercel, YouTube...)
 * yahan save hota hai. Chat commands:
 *   "token save github ghp_xxx"  •  "token list"  •  "token delete github"
 * Plus chhota HTTP helper (GET/POST) jo GitHub/YouTube jaise APIs ke liye hai.
 */
object TokenVault {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableMap<String, String> {
        val out = HashMap<String, String>()
        try {
            val o = JSONObject(prefs(ctx).getString("brain_tokens", "{}"))
            for (k in o.keys()) out[k] = o.getString(k)
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, name: String, value: String) {
        val m = list(ctx)
        m[name.trim().lowercase()] = value.trim()
        prefs(ctx).edit().putString("brain_tokens", JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun get(ctx: Context, name: String): String? = list(ctx)[name.trim().lowercase()]

    fun delete(ctx: Context, name: String): Boolean {
        val m = list(ctx)
        val k = name.trim().lowercase()
        if (!m.containsKey(k)) return false
        m.remove(k)
        prefs(ctx).edit().putString("brain_tokens", JSONObject(m as Map<*, *>).toString()).apply()
        return true
    }

    fun masked(v: String): String =
        if (v.length <= 8) "••••" else v.take(4) + "••••" + v.takeLast(4)

    // ---------- chhota HTTP helper (APIs ke liye) ----------
    fun http(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            Pair(code, text)
        } catch (e: Exception) {
            Pair(-1, "Error: " + e.message)
        } finally {
            conn?.disconnect()
        }
    }
}
