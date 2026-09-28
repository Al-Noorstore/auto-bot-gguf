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

    /** kaunsa model available hai: bundled asset > downloaded. onMsg = progress lines (optional). */
    private fun pickModel(ctx: Context, onMsg: ((String) -> Unit)? = null): String? {
        // 1) PRO bundled asset (one-time extract ~1-2 min, sirf pehli baar)
        try {
            val target = File(ModelStore.dir(ctx), ASSET_MODEL.substringAfterLast('/'))
            if (target.isFile && target.length() > 10_000_000L) return target.absolutePath
            if (!target.exists()) {
                val part = File(target.absolutePath + ".part")
                val total = try { ctx.assets.openFd(ASSET_MODEL).use { fd -> fd.length } } catch (_: Exception) { -1L }
                onMsg?.invoke("📦 Pehli baar: model phone mein copy ho raha hai" +
                    (if (total > 0) " (${"%.0f".format(total / 1048576.0)}MB, 1-2 min)" else " (1-2 min)") +
                    " — app khula rakho, band na karo.")
                val t0 = System.currentTimeMillis()
                ctx.assets.open(ASSET_MODEL).use { input ->
                    java.io.FileOutputStream(part).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var lastPct = -100
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt()
                                if (pct >= lastPct + 25) { lastPct = pct; onMsg?.invoke("📦 Extract: $pct%") }
                            }
                        }
                    }
                }
                if (part.isFile && part.length() > 10_000_000L) {
                    part.renameTo(target)
                    onMsg?.invoke("📦 Extract complete (${"%.0f".format((System.currentTimeMillis() - t0) / 1000.0)}s)")
                    return target.absolutePath
                }
                part.delete()
            }
        } catch (e: Exception) { onMsg?.invoke("❌ Model extract fail: ${e.message}") }
        // 2) downloaded transformer models
        ModelStore.downloaded(ctx).firstOrNull()?.let { return File(ModelStore.dir(ctx), it).absolutePath }
        return null
    }

    /** memory mein loaded hai? */
    fun isReady(): Boolean = loadedPath != null && LlamaBridge.isLoaded()

    /** prepare kahin aur chal raha hai? */
    fun isLoading(): Boolean = loading

    /**
     * v3.7: model ready karo — extract (agar zaruri) + llama load — background thread se.
     * true = ready. onMsg = live progress lines chat mein dikhane ke liye.
     */
    fun prepare(ctx: Context, onMsg: ((String) -> Unit)? = null): Boolean {
        // v3.7: dobara load ki race se bacho — pehla prepare khatam hone do
        if (loading) {
            var waited = 0
            while (loading && waited < 180_000) { try { Thread.sleep(400) } catch (_: InterruptedException) {}; waited += 400 }
            if (loadedPath != null && LlamaBridge.isLoaded()) { onMsg?.invoke("✅ Model already loaded: ${File(loadedPath!!).name}"); return true }
        }
        if (!enabled(ctx)) { onMsg?.invoke("⚪ GGUF engine OFF hai — 'gguf on' likho."); return false }
        if (!LlamaBridge.available) { onMsg?.invoke("❌ Native engine lib load nahi hui (libautobot-jni) — 'gguf test' chalao."); return false }
        return try {
            val path = pickModel(ctx, onMsg) ?: run {
                onMsg?.invoke("❌ Koi GGUF model nahi mila. (PRO: Qwen built-in; LITE: 'transformer download' se lo)"); return false
            }
            if (loadedPath == path && LlamaBridge.isLoaded()) { onMsg?.invoke("✅ Model already loaded: ${File(path).name}"); return true }
            onMsg?.invoke("⏳ Model load ho raha hai (${File(path).name})...")
            val t0 = System.currentTimeMillis()
            loading = true
            try {
                unload()
                if (!LlamaBridge.load(path)) { onMsg?.invoke("❌ llama.cpp load FAIL — RAM/storage kam? 'gguf test' chalao."); false }
                else {
                    loadedPath = path
                    onMsg?.invoke("🧠 Offline AI ready! (load ${"%.0f".format((System.currentTimeMillis() - t0) / 1000.0)}s)")
                    true
                }
            } finally { loading = false }
        } catch (e: Throwable) { onMsg?.invoke("❌ Prepare fail: ${e.message}"); false }
    }

    /** model maujood hai? (light check — extract nahi karta) */
    fun modelPresent(ctx: Context): Boolean {
        try { ctx.assets.open(ASSET_MODEL).close(); return true } catch (_: Exception) {}
        return try { ModelStore.downloaded(ctx).isNotEmpty() } catch (_: Exception) { false }
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
        sb.append(if (isReady()) "   🟢 Model loaded: ${File(loadedPath!!).name} — sab ready!\n" else "   ⚪ Model memory mein nahi (app khulte hi background load hota hai)\n")
        sb.append("💡 Ab 'ask' ki zarurat nahi — seedha koi bhi sawal likho, main samajh jaunga.\n🔧 Masla ho to: gguf test")
        return sb.toString()
    }

    /**
     * Offline jawab. null = engine is sawal se nahi kar sakta (caller API chain par jaye).
     * BLOCKING hai — background thread se bulao.
     */
    fun ask(ctx: Context, question: String): String? {
        if (!enabled(ctx) || !LlamaBridge.available) return null
        // v3.7: prepare kahin aur chal raha ho to wait karo (max 3 min) — pehle silent null nahi
        if (loading) {
            var waited = 0
            while (loading && waited < 180_000) { try { Thread.sleep(400) } catch (_: InterruptedException) {}; waited += 400 }
            if (loadedPath == null) return null
        }
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
            val t0 = System.currentTimeMillis()
            var ans = LlamaBridge.generate(prompt, maxTokens = 220).trim()
            ans = ans.substringBefore("<|im_end|>").trim()
            if (ans.isBlank()) return null
            val name = File(path).nameWithoutExtension
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            return "🧠 *GGUF offline* ($name, ${"%.0f".format(secs)}s):\n$ans"
        } catch (e: Throwable) { return null }
    }
}
