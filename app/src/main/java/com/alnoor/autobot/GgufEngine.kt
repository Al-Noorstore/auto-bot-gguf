package com.alnoor.autobot

import android.content.Context
import java.io.File

/**
 * GgufEngine (v3.5) — REAL offline AI engine.
 * - llama.cpp (libautobot-jni) se GGUF model chalata hai
 * - PRO APK mein Qwen2.5-0.5B built-in (assets/models) — pehli baar files mein extract hota hai
 * - LITE: transformer download karke wahi model chalao
 * - AIBrain isko sabse pehle try karta hai (offline > API)
 *
 * Chat: "gguf status" | "gguf on/off" | "gguf unload"
 */
object GgufEngine {

    private const val PREF = "autobot"
    const val ASSET_MODEL = "models/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    private const val SYS = "You are Auto Bot, a helpful phone assistant. " +
        "Understand English, Urdu, Roman Urdu, Hindi, Hinglish. " +
        "Answer in the user's language, briefly (2-4 lines unless asked for detail). " +
        "You run fully offline on the user's phone."

    @Volatile private var loadedPath: String? = null
    @Volatile private var loading = false

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("gguf_engine", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("gguf_engine", on).apply()
        if (!on) unload()
    }

    fun unload() { LlamaBridge.free(); loadedPath = null }

    /** kaunsa model available hai: bundled asset > downloaded */
    private fun pickModel(ctx: Context): String? {
        // 1) PRO bundled asset (one-time extract ~2 min, sirf pehli baar)
        try {
            val target = File(ModelStore.dir(ctx), ASSET_MODEL.substringAfterLast('/'))
            if (target.isFile && target.length() > 10_000_000L) return target.absolutePath
            if (!target.exists()) {
                val part = File(target.absolutePath + ".part")
                ctx.assets.open(ASSET_MODEL).use { input ->
                    java.io.FileOutputStream(part).use { out -> input.copyTo(out, 1 shl 16) }
                }
                if (part.isFile && part.length() > 10_000_000L) {
                    part.renameTo(target); return target.absolutePath
                }
                part.delete()
            }
        } catch (e: Exception) { /* LITE ya asset nahi */ }
        // 2) downloaded transformer models
        ModelStore.downloaded(ctx).firstOrNull()?.let { return File(ModelStore.dir(ctx), it).absolutePath }
        return null
    }

    /** model ready hai? (chat status ke liye) */
    fun status(ctx: Context): String {
        val sb = StringBuilder("🧠 GGUF Engine v1 (llama.cpp 0.5.0):\n")
        sb.append("   Engine: ${if (LlamaBridge.available) "✅ native lib ready" else "❌ load nahi hui"}\n")
        sb.append("   On/Off: ${if (enabled(ctx)) "🟢 ON" else "⚪ OFF"}\n")
        val bundled = try { ctx.assets.open(ASSET_MODEL).close(); true } catch (e: Exception) { false }
        sb.append("   Built-in Qwen (PRO): ${if (bundled) "✅ bundled" else "❌ nahi (LITE)"}\n")
        val dl = ModelStore.downloaded(ctx)
        sb.append("   Downloaded models: ${if (dl.isEmpty()) "— koi nahi" else dl.joinToString()} \n")
        sb.append(if (loadedPath != null) "   🟢 Model loaded: ${File(loadedPath!!).name}\n" else "   ⚪ Model load nahi (pehle 'ask' par khud load hoga)\n")
        sb.append("💡 'ask <sawal>' offline chalega jab tak model maujood hai.")
        return sb.toString()
    }

    /**
     * Offline jawab. null = engine is sawal se nahi kar sakta (caller API chain par jaye).
     * BLOCKING hai — background thread se bulao.
     */
    fun ask(ctx: Context, question: String): String? {
        if (!enabled(ctx) || !LlamaBridge.available) return null
        if (loading) return null
        val path = try { pickModel(ctx) } catch (e: Exception) { null } ?: return null
        try {
            if (loadedPath != path) {
                loading = true
                try {
                    unload()
                    if (!LlamaBridge.load(path)) { loading = false; return null }
                    loadedPath = path
                } finally { loading = false }
            }
            val prompt = "<|im_start|>system\n$SYS<|im_end|>\n" +
                "<|im_start|>user\n$question<|im_end|>\n<|im_start|>assistant\n"
            var ans = LlamaBridge.generate(prompt, maxTokens = 220).trim()
            ans = ans.substringBefore("<|im_end|>").trim()
            if (ans.isBlank()) return null
            val name = File(path).nameWithoutExtension
            return "🧠 *GGUF offline* ($name):\n$ans"
        } catch (e: Throwable) { return null }
    }
}
