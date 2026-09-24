package com.alnoor.autobot

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transformer models (GGUF) — offline AI ke liye chhote mobile-friendly models.
 * Chat: "transformer list" | "transformer download 1" | "transformer delete 1"
 * Admin panel mein bhi buttons hain.
 */
object ModelStore {

    data class Model(val name: String, val file: String, val size: String, val url: String)

    val presets = listOf(
        Model("SmolLM2-135M", "smollm2-135m.gguf", "~145 MB",
            "https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct-GGUF/resolve/main/smollm2-135m-instruct-q8_0.gguf"),
        Model("Qwen2.5-0.5B", "qwen2.5-0.5b.gguf", "~400 MB",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
        Model("TinyLlama-1.1B", "tinyllama-1.1b.gguf", "~670 MB",
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf")
    )

    /** Progress bar ke liye: har 1% par 0..100 milta hai (MainActivity set karta hai). */
    @Volatile var pctListener: ((Int) -> Unit)? = null

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    fun downloaded(ctx: Context): List<String> =
        dir(ctx).listFiles()?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }
            ?.map { it.name } ?: emptyList()

    fun find(name: String): Model? {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return null
        presets.getOrNull(q.toIntOrNull()?.minus(1) ?: -1)?.let { return it }
        presets.firstOrNull { it.name.lowercase().replace(" ", "").contains(q.replace(" ", "")) }?.let { return it }
        return null
    }

    /** Background thread se call karo. onProgress mein % aayega. */
    fun download(ctx: Context, model: Model, onProgress: (String) -> Unit): String {
        val target = File(dir(ctx), model.file + ".part")
        val finalFile = File(dir(ctx), model.file)
        return try {
            val conn = URL(model.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 AutoBot")
            if (conn.responseCode !in 200..299) {
                "❌ Server ne download nahi diya (HTTP ${conn.responseCode})"
            } else {
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1
                var lastBar = -1
                conn.inputStream.use { inp ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastBar) { lastBar = pct; pctListener?.invoke(pct) }
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct
                                    onProgress("⬇️ ${model.name}: $pct% (${done / 1048576}/${total / 1048576} MB)")
                                }
                            } else if (done % (16L * 1048576) < 65536) {
                                onProgress("⬇️ ${model.name}: ${done / 1048576} MB...")
                            }
                        }
                    }
                }
                if (finalFile.exists()) finalFile.delete()
                if (!target.renameTo(finalFile)) {
                    "⚠️ Download hua lekin file move fail: ${target.absolutePath}"
                } else {
                    "✅ ${model.name} download complete: ${finalFile.absolutePath}"
                }
            }
        } catch (e: Exception) {
            "❌ Download fail: ${e.message}"
        } finally {
            if (target.exists() && !finalFile.exists()) target.delete()
        }
    }

    fun delete(ctx: Context, model: Model): String =
        if (File(dir(ctx), model.file).delete()) "🗑 ${model.name} hata diya." else "❌ Delete fail."

    fun list(ctx: Context): String {
        val sb = StringBuilder("📦 Transformer models (offline AI):\n\n")
        val dl = downloaded(ctx)
        presets.forEachIndexed { i, m ->
            val mark = if (dl.contains(m.file)) " ✅" else ""
            sb.append("${i + 1}. ${m.name} (${m.size})$mark\n")
        }
        sb.append("\nDownload: transformer download 1\nDelete: transformer delete 1")
        return sb.toString()
    }
}
