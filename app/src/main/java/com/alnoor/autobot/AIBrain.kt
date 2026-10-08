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
        "Keep responses natural and relevant to the user's exact question. " +
        "IMPORTANT: You run inside Auto Bot, an Android assistant with REAL device commands. " +
        "If the user asks you to DO a phone task (open/close app, call someone, send WhatsApp, set alarm/timer, screenshot, lock app, set volume/brightness, find contact), your reply MUST start with exactly one line: TASK: <one simple bot command>, then a short confirmation. " +
        "Valid commands: open whatsapp | close youtube | call amir | call 03001234567 | wa bhejo amir | assalam | alarm lagao 6 baje | timer 5 minute | screenshot | app lock whatsapp pin 1234 | volume 50 | brightness 80 | contacts amir. " +
        "For normal questions and conversation, answer helpfully WITHOUT any TASK line."

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
    /** v3.6: kya AI jawab de sakti hai? (GGUF model ya koi API key) — direct-chat ke liye */
    fun aiAvailable(ctx: Context): Boolean {
        try { if (GgufEngine.enabled(ctx) && LlamaBridge.available && GgufEngine.modelPresent(ctx)) return true } catch (_: Exception) {}
        try { if (KeyStore.load(ctx).any { it.enabled && it.key.isNotBlank() }) return true } catch (_: Exception) {}
        // v4.7: GitHub AI fallback (PAT) bhi jawab de sakta hai
        return githubAiOn(ctx) && githubFallbackKeys(ctx).isNotEmpty()
    }

    /** v4.7: GITHUB AI — toggle (admin panel / chat: github ai on-off) */
    private fun githubAiOn(ctx: Context): Boolean =
        try { ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE).getBoolean("github_ai", true) } catch (_: Exception) { true }

    /** v4.7: GITHUB AI fallback keys — TokenVault ke saare github tokens PAT ki tarah models.github.ai pe (multiple allowed) */
    private fun githubFallbackKeys(ctx: Context): List<KeyStore.ApiKey> {
        if (!githubAiOn(ctx)) return emptyList()
        val model = try { ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE).getString("github_ai_model", "") ?: "" } catch (_: Exception) { "" }
        val toks = try { TokenVault.list(ctx).filter { it.key.startsWith("github") && it.value.isNotBlank() }.map { it.toPair() } } catch (_: Exception) { emptyList<Pair<String, String>>() }
        return toks.map { KeyStore.ApiKey("GitHub", "GitHubAI:" + it.first, it.second, "https://models.github.ai/inference", model.ifBlank { "openai/gpt-4o-mini" }) }
    }

    fun ask(ctx: Context, question: String): String {
        // v3.8 SMART ROUTE (user ke rules):
        // auto: API key ho to API pehle → na chale to Qwen → na chale to suggestion
        // qwen: sirf offline Qwen (user ne "qwen se jawab do" kaha ho)
        // api:  sirf API keys (user ne "api se jawab do" kaha ho)
        val route = try { ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE).getString("ai_route", "auto") ?: "auto" } catch (_: Exception) { "auto" }
        val keys = KeyStore.load(ctx).filter { it.enabled && it.key.isNotBlank() }
        val active = keys.firstOrNull { it.active } ?: keys.firstOrNull()
        fun askApi(): String? {
            if (active != null) {
                tryAsk(active, question)?.let { return it }
                for (k in keys) {
                    if (k.label == active.label) continue
                    tryAsk(k, question)?.let { return "(🔑 $k.label se aaya — active key kaam nahi kar rahi thi)\n$it" }
                }
            }
            // v4.7: GITHUB AI FALLBACK — koi API key nahi / sab fail -> GitHub PAT se models.github.ai
            for (gk in githubFallbackKeys(ctx)) {
                tryAsk(gk, question)?.let { return "(🐙 GitHub AI se aaya — ${gk.label})\n$it" }
            }
            return null
        }
        when (route) {
            "qwen" -> {
                try { GgufEngine.ask(ctx, question)?.let { return it } } catch (e: Throwable) {}
                return "🧠 Qwen offline jawab nahi bana paya. 'gguf test' chalao." +
                    (if (active == null) "\n\n💡 Free Gemini key add karo: api key <key>\n(aistudio.google.com/apikey)" else "\n\n💡 API se jawab ke liye likho: api se jawab do")
            }
            "api" -> {
                askApi()?.let { return it }
                return "🔑 API se jawab nahi aaya (key invalid / internet band / credit khatam?).\n🔍 'api key test' chalao." +
                    "\n\n💡 Offline Qwen ke liye likho: qwen se jawab do"
            }
            else -> {
                // AUTO: API pehle (key connect ho), warna Qwen, warna dono fail
                askApi()?.let { return it }
                try { GgufEngine.ask(ctx, question)?.let { return it } } catch (e: Throwable) {}
                return failReply(ctx, question)
            }
        }
    }

    /** v4.4: API-only answer (bugfix / code generation ke liye) — local fallback NAHI. null = key nahi ya fail */
    fun askApi(ctx: Context, prompt: String): String? {
        val keys = KeyStore.load(ctx).filter { it.enabled && it.key.isNotBlank() }
        val active = keys.firstOrNull { it.active } ?: keys.firstOrNull()
        if (active != null) {
            tryAsk(active, prompt)?.let { return it }
            for (k in keys) {
                if (k.label == active.label) continue
                tryAsk(k, prompt)?.let { return it }
            }
        }
        // v4.7: GITHUB AI FALLBACK
        for (gk in githubFallbackKeys(ctx)) {
            tryAsk(gk, prompt)?.let { return it }
        }
        return null
    }

    private fun tryAsk(k: KeyStore.ApiKey, q: String): String? {
        return try {
            val ans = when (k.provider) {
                "Gemini" -> gemini(k, q)
                "Ollama (PC/Local)", "Ollama Cloud" -> ollama(k, q)
                else -> openaiCompatible(k, q)
            }
            // error wale jawab ko fail maano (credit khatam / key invalid / network)
            val low = ans.lowercase()
            if (ans.startsWith("❌") || ans.startsWith("⚠️")) null
            else if (low.contains("quota") || low.contains("rate limit") || low.contains("insufficient") ||
                low.contains("credit") || low.contains("billing") || low.contains("resource_exhausted") ||
                low.contains("exceeded") || low.contains("payment")) null
            else ans
        } catch (e: Exception) { null }
    }

    private fun failReply(ctx: Context, question: String): String {
        val keys = KeyStore.load(ctx)
        val hasAnyKey = keys.isNotEmpty()
        val sb = StringBuilder()
        if (hasAnyKey) {
            sb.append("🧠 AI se jawab nahi mil paya — mumkin wajah:\n")
            sb.append("• API key ka credit khatam / rate-limit\n")
            sb.append("• Key invalid ya internet band\n")
            sb.append("• Active key: ${keys.firstOrNull { it.active }?.label ?: keys.first().label}\n\n")
            sb.append("🔍 Check: api key test | api keys\n")
        } else {
            sb.append("🧠 Is sawal ke liye AI chahiye, aur abhi koi API key connect nahi hai.\n\n")
        }
        sb.append("⚡ API key jodo:\n   api key <apni-key>\n(Free Gemini: aistudio.google.com/apikey)\n")
        sb.append("\uD83D\uDC19 Ya GitHub PAT se AI: github ai token <PAT> (toggle: github ai on)")
        val ge = if (GgufEngine.enabled(ctx)) "ON" else "OFF ('gguf on' likho)"
        val err = try { GgufEngine.lastError() } catch (_: Exception) { null }
        sb.append("\n🧠 GGUF offline: $ge")
        if (err != null) sb.append(" — last error: $err")
        sb.append("\n")
        try { sb.append(GgufEngine.suggestModel(ctx, heavy = question.length > 80)).append("\n") } catch (_: Exception) {}
        sb.append("\n💡 Commands: model list | model use smol | gguf test | api keys")
        sb.append("\n✅ Local commands: open youtube, call, contact, torch… ('help')")
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
