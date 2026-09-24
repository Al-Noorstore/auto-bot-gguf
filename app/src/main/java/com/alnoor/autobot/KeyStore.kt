package com.alnoor.autobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Admin API keys ka store — admin_keys.json mein multiple keys save hoti hain.
 * Har key: provider, label, key, base, model, active (sirf ek active).
 * Beginner friendly: sab kuch app ke apne storage mein, plain JSON.
 */
object KeyStore {

    data class ApiKey(
        val provider: String,
        val label: String,
        var key: String,
        val base: String,
        val model: String,
        var active: Boolean = false,
        var enabled: Boolean = true
    )

    // Provider defaults — user sirf key paste kare, baaki khud fill ho jata hai
    val providers = listOf(
        "Gemini", "OpenAI", "OpenRouter", "Groq",
        "Ollama Cloud", "Ollama (PC/Local)", "Custom"
    )

    fun defaultBase(provider: String): String = when (provider) {
        "Gemini" -> "https://generativelanguage.googleapis.com"
        "OpenAI" -> "https://api.openai.com/v1"
        "OpenRouter" -> "https://openrouter.ai/api/v1"
        "Groq" -> "https://api.groq.com/openai/v1"
        "Ollama Cloud" -> "https://chat.ollama.com"
        "Ollama (PC/Local)" -> "http://localhost:11434"
        else -> ""
    }

    fun defaultModel(provider: String): String = when (provider) {
        "Gemini" -> "gemini-2.0-flash"
        "OpenAI" -> "gpt-4o-mini"
        "Groq" -> "llama-3.1-8b-instant"
        "OpenRouter" -> "openrouter/auto"
        "Ollama Cloud", "Ollama (PC/Local)" -> "llama3.2"
        else -> ""
    }

    private fun file(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "admin_keys.json")

    fun load(ctx: Context): MutableList<ApiKey> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<ApiKey>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ApiKey(
                        o.optString("provider", "Custom"),
                        o.optString("label", ""),
                        o.optString("key", ""),
                        o.optString("base", ""),
                        o.optString("model", ""),
                        o.optBoolean("active", false),
                        o.optBoolean("enabled", true)
                    )
                )
            }
            list
        } catch (e: Exception) { mutableListOf() }
    }

    fun save(ctx: Context, keys: List<ApiKey>) {
        val arr = JSONArray()
        for (k in keys) {
            arr.put(JSONObject().put("provider", k.provider).put("label", k.label)
                .put("key", k.key).put("base", k.base).put("model", k.model)
                .put("active", k.active).put("enabled", k.enabled))
        }
        file(ctx).writeText(arr.toString())
    }

    fun add(ctx: Context, k: ApiKey): String {
        val keys = load(ctx)
        if (keys.isNotEmpty()) keys.forEach { it.active = false }
        val nk = k.copy(active = true) // naya/pehla active
        keys.add(nk)
        save(ctx, keys)
        return "✅ Key save ho gayi: ${k.label} (${k.provider}) — abhi active bhi hai."
    }

    fun setActive(ctx: Context, label: String): String {
        val keys = load(ctx)
        var found = false
        keys.forEach {
            it.active = it.label == label
            if (it.active) found = true
        }
        save(ctx, keys)
        return if (found) "✅ Ab AI brain: $label" else "❌ Key nahi mili: $label"
    }

    fun remove(ctx: Context, label: String): String {
        val keys = load(ctx)
        val wasActive = keys.any { it.label == label && it.active }
        keys.removeAll { it.label == label }
        if (wasActive && keys.isNotEmpty()) keys.first().active = true
        save(ctx, keys)
        return "🗑 Key hata di: $label"
    }

    fun active(ctx: Context): ApiKey? = load(ctx).firstOrNull { it.active }

    /** v2.7: per-key ON/OFF — sirf enabled keys AI tasks/fallback ke liye use hoti hain */
    fun setEnabled(ctx: Context, label: String, on: Boolean) {
        val keys = load(ctx)
        keys.forEach { if (it.label == label) it.enabled = on }
        save(ctx, keys)
    }

    fun update(ctx: Context, label: String, newKey: String): String {
        val keys = load(ctx)
        keys.forEach { if (it.label == label && newKey.isNotBlank()) it.key = newKey.trim() }
        save(ctx, keys)
        return "✅ Key update ho gayi: $label"
    }
}
