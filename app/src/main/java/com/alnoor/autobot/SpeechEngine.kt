package com.alnoor.autobot

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * v3.0 — SPEECH ENGINE (Vosk): OFFLINE voice recognition.
 * Bina internet mic se bolte ho → text ban jata hai → wahi text chat pipeline
 * (Offline Brain / Ollama / Transformer / API) ko jata hai. Voice = typing.
 * Model (ek baar download, ~40MB):
 *  - "english"   : vosk-model-small-en-us-0.15 (English commands)
 *  - "urdu-hindi": vosk-model-small-hi-0.22 (Urdu/Hindi/Hinglish commands)
 */
object SpeechEngine {

    class VoiceModel(val name: String, val display: String, val size: String, val url: String, val lang: String)

    val MODELS = listOf(
        VoiceModel("english", "English (US)", "~40 MB", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "en"),
        VoiceModel("urdu-hindi", "Urdu/Hindi", "~42 MB", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", "hi-ur")
    )

    fun find(name: String): VoiceModel? = MODELS.firstOrNull { it.name == name || it.name == name.trim().lowercase() }

    private fun dir(ctx: Context, m: VoiceModel): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "voice/" + m.name)

    fun downloaded(ctx: Context): List<VoiceModel> = MODELS.filter { dir(ctx, it).exists() }

    private fun pref(ctx: Context) = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)

    fun activeModel(ctx: Context): VoiceModel? {
        val name = pref(ctx).getString("voice_model", null) ?: return downloaded(ctx).firstOrNull()
        val m = find(name)
        return if (m != null && dir(ctx, m).exists()) m else downloaded(ctx).firstOrNull()
    }

    fun setActive(ctx: Context, name: String): String {
        val m = find(name) ?: return "❌ Voice model nahi mila: $name (options: ${MODELS.joinToString { it.name }})"
        if (!dir(ctx, m).exists()) return "❌ $name pehle download karo: \"voice download $name\""
        pref(ctx).edit().putString("voice_model", m.name).apply()
        return "✅ Active voice model: ${m.display} (${m.name})"
    }

    fun status(ctx: Context): String {
        val dl = downloaded(ctx)
        val act = activeModel(ctx)
        val sb = StringBuilder()
        sb.append("🎤 OFFLINE VOICE (bina internet mic)\n\n")
        if (dl.isEmpty()) {
            sb.append("Abhi koi voice model download nahi hai. Ek baar download karo (sirf ~40MB), phir hamesha offline chalega:\n")
            for (m in MODELS) sb.append("• ${m.name} — ${m.display} (${m.size})\n")
            sb.append("\nChat mein likho: \"voice download urdu-hindi\"\n(English commands ke liye: \"voice download english\")\n\n⚠️ Download ke liye internet chahiye — uske baad sab offline.")
        } else {
            sb.append("✅ Downloaded:\n")
            for (m in dl) sb.append("• ${m.display} (${m.name})" + (if (act != null && act.name == m.name) " ← ACTIVE" else "") + "\n")
            sb.append("\nMic button 🎤 dabao aur bolo. Ya chat mein \"mic on\" likho.\nModel change: \"voice use english\" ya \"voice use urdu-hindi\"\n")
        }
        sb.append("\nVoice = typing: jo bolo wo chat mein jata hai — Offline Brain, Ollama, Transformer, API sab voice se chalenge.")
        return sb.toString()
    }

    /** Progress bar ke liye: har 1% par 0..100 milta hai (MainActivity set karta hai). */
    @Volatile var pctListener: ((Int) -> Unit)? = null

    /** Model download + unzip (zip-slip safe). Background thread se call karo. */
    fun download(ctx: Context, m: VoiceModel, progress: (String) -> Unit): String {
        val out = dir(ctx, m)
        if (out.exists()) return "✅ ${m.display} already downloaded hai."
        return try {
            progress("⬇️ ${m.display} download shuru (${m.size})...")
            val conn = URL(m.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            if (conn.responseCode !in 200..299) return "❌ Download fail (HTTP ${conn.responseCode}) — internet check karo."
            val total = conn.contentLengthLong
            val tmp = File(ctx.cacheDir, m.name + ".zip")
            var lastPct = -1
            var lastBar = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { fos ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        fos.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastBar) { lastBar = pct; pctListener?.invoke(pct) }
                            if (pct >= lastPct + 10) { lastPct = pct; progress("⬇️ ${m.display}: $pct%") }
                        }
                    }
                }
            }
            progress("📦 ${m.display} unpack ho raha hai...")
            val parent = File(out.parentFile.path)
            val tmpDir = File(parent, "_tmp_" + m.name)
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            ZipInputStream(tmp.inputStream()).use { zis ->
                var entry = zis.nextEntry
                val canonicalBase = tmpDir.canonicalPath + File.separator
                while (entry != null) {
                    val outFile = File(tmpDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(canonicalBase)) { zis.closeEntry(); entry = zis.nextEntry; continue }
                    if (entry.isDirectory) outFile.mkdirs()
                    else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            tmp.delete()
            // zip ke andar top-level folder hota hai (e.g. vosk-model-small-hi-0.22/) — usko model dir banao
            val inner = tmpDir.listFiles()?.firstOrNull { it.isDirectory }
            if (inner == null) { tmpDir.deleteRecursively(); return "❌ Unzip fail — model folder nahi bana." }
            out.deleteRecursively()
            if (!inner.renameTo(out)) {
                // rename fail ho to copy fallback
                inner.copyRecursively(out)
                tmpDir.deleteRecursively()
            }
            if (!File(out, "conf").exists() && !File(out, "AM").exists()) return "❌ Unzip fail — model files missing."
            if (pref(ctx).getString("voice_model", null) == null) pref(ctx).edit().putString("voice_model", m.name).apply()
            "✅ ${m.display} ready! Ab mic button 🎤 dabao ya \"mic on\" likho — bina internet bologe, main samajh lunga."
        } catch (e: Exception) {
            "❌ Voice model download fail: ${e.message}"
        }
    }

    fun delete(ctx: Context, name: String): String {
        val m = find(name) ?: return "❌ Model nahi mila: $name"
        val d = dir(ctx, m)
        return if (d.exists()) { d.deleteRecursively(); if (activeModel(ctx)?.name == m.name) pref(ctx).edit().remove("voice_model").apply(); "🗑️ ${m.display} delete ho gaya." }
        else "❌ ${m.display} downloaded hi nahi hai."
    }

    // ---------- live recognition ----------
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null

    var isListening = false
        private set

    fun ready(ctx: Context): Boolean = activeModel(ctx) != null

    /**
     * Sunna shuru. Caller ko RECORD_AUDIO permission check karna hai.
     * onPartial: live text (input box mein dikhta hai)
     * onFinal  : poora sentence → chat pipeline ko jata hai
     */
    fun start(ctx: Context, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onFail: (String) -> Unit): String {
        if (isListening) return "🎤 Already sun raha hoon..."
        val m = activeModel(ctx) ?: return "❌ Voice model nahi hai. Chat mein likho: \"voice download urdu-hindi\" (~42MB, ek baar internet chahiye)"
        stop()
        return try {
            model = Model(dir(ctx, m).absolutePath)
            recognizer = Recognizer(model, 16000.0f)
            service = SpeechService(recognizer, 16000.0f)
            service?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val t = try { JSONObject(hypothesis ?: "").optString("partial", "") } catch (_: Exception) { "" }
                    if (t.isNotBlank()) onPartial(t.trim())
                }
                override fun onResult(hypothesis: String?) {
                    val t = try { JSONObject(hypothesis ?: "").optString("text", "").trim() } catch (_: Exception) { "" }
                    if (t.isNotEmpty()) onFinal(t)
                }
                override fun onFinalResult(hypothesis: String?) {
                    val t = try { JSONObject(hypothesis ?: "").optString("text", "").trim() } catch (_: Exception) { "" }
                    if (t.isNotEmpty()) onFinal(t)
                    isListening = false
                }
                override fun onError(exception: Exception?) {
                    isListening = false
                    onFail(exception?.message ?: "Mic error")
                }
                override fun onTimeout() {
                    isListening = false
                }
            })
            isListening = true
            "🎤 Sun raha hoon... bolo!"
        } catch (e: Exception) {
            stop()
            "❌ Voice start fail: ${e.message}"
        }
    }

    fun stop() {
        try { service?.stop() } catch (_: Exception) {}
        try { service?.shutdown() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        service = null; recognizer = null; model = null
        isListening = false
    }
}
