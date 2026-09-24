package com.alnoor.autobot

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * AIBrain — admin panel ki saved keys se kisi bhi AI se baat karta hai.
 * Chat command: "ask <sawal>"
 * - Gemini: Google generateContent API
 * - Ollama (PC/Local ya Cloud): /api/chat
 * - Baaki sab (OpenAI/OpenRouter/Groq/Custom): OpenAI-compatible chat/completions
 */
object AIBrain {

    // v2.9: multilingual system instruction — sab AI providers ko user ki language mein jawab dena
    private const val SYSTEM_INSTRUCTION: String =
        "You are Auto Bot's multilingual assistant. " +
        "Understand English, Urdu, Roman Urdu, Hindi, Hinglish and mixed-language messages whenever the selected model supports them. " +
        "Answer in the language/style used by the user. Do not translate unless requested. " +
        "Do not unnecessarily change names, phone numbers, commands or technical terms. " +
        "Keep responses natural and relevant to the user's exact question."

    private fun http(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        var code = -1
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** Admin panel "Test karo" button — key sahi hai ya nahi. */
    fun testKey(k: KeyStore.ApiKey): String {
        return try {
            val (code, _) = when (k.provider) {
                "Gemini" -> http(k.base.trimEnd('/') + "/v1beta/models?key=" + java.net.URLEncoder.encode(k.key, "UTF-8"), "GET", emptyMap(), null)
                "Hugging Face" -> http("https://huggingface.co/api/whoami-v2", "GET", mapOf("Authorization" to "Bearer ${k.key}"), null)
                "Ollama (PC/Local)" -> http(k.base.trimEnd('/') + "/api/tags", "GET", emptyMap(), null)
                "Ollama Cloud" -> http(k.base.trimEnd('/') + "/api/tags", "GET", mapOf("Authorization" to "Bearer ${k.key}"), null)
                else -> http(k.base.trimEnd('/') + "/models", "GET", mapOf("Authorization" to "Bearer ${k.key}"), null)
            }
            when (code) {
                in 200..299 -> "✅ Kaam kar rahi hai! (HTTP $code) — key sahi hai."
                401, 403 -> "❌ HTTP $code — key ghalat ya expire ho gayi lagti hai."
                404 -> "⚠️ HTTP 404 — base URL check karo (URL ghalat lagta hai)."
                else -> "❌ HTTP $code — internet/URL/permission check karo."
            }
        } catch (e: Exception) {
            "❌ Connection fail: ${e.message} — internet on hai? URL sahi hai? (Ollama PC ke liye phone aur PC ek WiFi pe hone chahiye aur 'ollama serve' chalna chahiye)"
        }
    }

    /**
     * v2.7 AUTO-FALLBACK CHAIN:
     * 1) Active key se try → 2) baaki ON keys se try → 3) sab fail (credit/invalid/off) →
     * natural reply: API key connect karo ya Ollama offline model; agar model connected hai to batado.
     */
    fun ask(ctx: Context, question: String): String {
        val keys = KeyStore.load(ctx).filter { it.enabled && it.key.isNotBlank() }
        val active = keys.firstOrNull { it.active } ?: keys.firstOrNull()
        if (active != null) {
            tryAsk(active, question)?.let { return it }
            for (k in keys) {
                if (k.label == active.label) continue
                tryAsk(k, question)?.let { return "(🔑 $k.label se aaya — active key kaam nahi kar rahi thi)\n$it" }
            }
        }
        return failReply(ctx, question)
    }

    private fun tryAsk(k: KeyStore.ApiKey, q: String): String? {
        return try {
            val ans = when (k.provider) {
                "Gemini" -> gemini(k, q)
                "Ollama (PC/Local)", "Ollama Cloud" -> ollama(k, q)
                else -> openaiCompatible(k, q)
            }
            // error wale jawab ko fail maano (credit khatam / key invalid / network)
            if (ans.startsWith("❌") || ans.startsWith("⚠️")) null else ans
        } catch (e: Exception) { null }
    }

    private fun failReply(ctx: Context, question: String): String {
        val off = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)
            .getString("brain_offline_model", null)
        val hasAnyKey = KeyStore.load(ctx).isNotEmpty()
        val sb = StringBuilder()
        sb.append(if (hasAnyKey) "🧠 AI se jawab nahi mil paya — key ka credit khatam / key ghalat ho sakti hai, ya internet band hai.\n\n"
                  else "🧠 Is sawal ke liye AI chahiye, aur abhi koi API key connect nahi hai.\n\n")
        sb.append("⚡ Sabse aasan fix — chat mein likho:\n   api key <apni-key>\n(Gemini / OpenAI / Groq / OpenRouter khud pehchan lunga; free Gemini key: aistudio.google.com/apikey)\n")
        if (off != null) sb.append("\n📦 Offline model ($off) select hai, lekin abhi app mein model chalane wala engine nahi — filhal key best hai.")
        else sb.append("\n📦 Offline model chahiye? Likho: transformer download (phone ke hisaab se suggest karunga).")
        sb.append("\n\n✅ Tab tak local commands chalte hain: open youtube, call, contact, torch, volume… ('help' likho).")
        return sb.toString()
    }

    private fun gemini(k: KeyStore.ApiKey, q: String): String {
        val model = if (k.model.isNotBlank()) k.model else "gemini-2.0-flash"
        val url = k.base.trimEnd('/') + "/v1beta/models/$model:generateContent?key=" +
            java.net.URLEncoder.encode(k.key, "UTF-8")
        val msgPart = org.json.JSONObject().put("text", q)
        val partsArr = org.json.JSONArray().put(msgPart)
        val contentsItem = org.json.JSONObject().put("parts", partsArr)
        val contentsArr = org.json.JSONArray().put(contentsItem)
        val sysPart = org.json.JSONObject().put("text", SYSTEM_INSTRUCTION)
        val body = org.json.JSONObject().put("contents", contentsArr)
            .put("system_instruction", org.json.JSONObject().put("parts", org.json.JSONArray().put(sysPart)))
        val (code, text) = http(url, "POST", emptyMap(), body.toString())
        if (code !in 200..299) return "❌ Gemini error (HTTP $code): ${text.take(200)}"
        val candidates = JSONObject(text).optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) return "⚠️ Gemini ne khaali jawab diya."
        val parts = candidates.getJSONObject(0).getJSONObject("message").optJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
        return sb.toString().ifBlank { "⚠️ Jawab khaali aaya." }
    }

    private fun ollama(k: KeyStore.ApiKey, q: String): String {
        val model = if (k.model.isNotBlank()) k.model else "llama3.2"
        val headers = if (k.key.isNotBlank()) mapOf("Authorization" to "Bearer ${k.key}") else emptyMap()
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_INSTRUCTION))
                .put(JSONObject().put("role", "user").put("content", q)))
        val (code, text) = http(k.base.trimEnd('/') + "/api/chat", "POST", headers, body.toString())
        if (code !in 200..299) return "❌ Ollama error (HTTP $code): ${text.take(200)}"
        return JSONObject(text).getJSONObject("message").optString("content", "⚠️ Khaali jawab.")
    }

    private fun openaiCompatible(k: KeyStore.ApiKey, q: String): String {
        val model = if (k.model.isNotBlank()) k.model else "gpt-4o-mini"
        val body = JSONObject()
            .put("model", model)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_INSTRUCTION))
                .put(JSONObject().put("role", "user").put("content", q)))
        val (code, text) = http(k.base.trimEnd('/') + "/chat/completions", "POST",
            mapOf("Authorization" to "Bearer ${k.key}"), body.toString())
        if (code !in 200..299) return "❌ AI error (HTTP $code): ${text.take(200)}"
        val choices = JSONObject(text).optJSONArray("choices")
        if (choices == null || choices.length() == 0) return "⚠️ Khaali jawab."
        return choices.getJSONObject(0).getJSONObject("message").optString("content", "⚠️ Khaali jawab.")
    }
}
