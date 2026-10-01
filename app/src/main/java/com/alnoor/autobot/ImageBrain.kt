package com.alnoor.autobot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Image generate (API) + image/screenshot padhna (vision).
 * Gemini + OpenAI-compatible keys (KeyStore active key).
 */
object ImageBrain {

    private fun http(url: String, method: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            return Pair(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpJson(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val (code, bytes) = http(
            url, method,
            headers + mapOf("Content-Type" to "application/json"),
            body?.toByteArray(Charsets.UTF_8)
        )
        return Pair(code, bytes.toString(Charsets.UTF_8))
    }

    fun outDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "images").apply { mkdirs() }

    /** Generate image from prompt → saved file path or error string starting with ❌ */
    fun generate(ctx: Context, prompt: String): String {
        val k = KeyStore.active(ctx)
            ?: return "❌ Pehle API key save karo: api key gemini <key>  ya  api key openai <key>"
        if (prompt.isBlank()) return "❌ Prompt likho: image generate red shoes product photo"
        return try {
            when (k.provider) {
                "Gemini" -> generateGemini(ctx, k, prompt)
                else -> generateOpenAI(ctx, k, prompt)
            }
        } catch (e: Exception) {
            "❌ Image generate fail: ${e.message}"
        }
    }

    private fun generateOpenAI(ctx: Context, k: KeyStore.ApiKey, prompt: String): String {
        val base = k.base.trimEnd('/')
        val url = if (base.contains("openai") || base.endsWith("/v1")) "$base/images/generations"
        else "$base/v1/images/generations"
        val body = JSONObject()
            .put("model", if (k.model.contains("dall", true) || k.model.contains("image", true)) k.model else "dall-e-3")
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", "1024x1024")
            .toString()
        val (code, text) = httpJson(
            url, "POST",
            mapOf("Authorization" to "Bearer ${k.key}"),
            body
        )
        if (code !in 200..299) {
            // try gpt-image style / fallback message
            return "❌ OpenAI image HTTP $code: ${text.take(300)}\n💡 Model/dall-e access check karo, ya Gemini key use karo."
        }
        val data = JSONObject(text).optJSONArray("data")?.optJSONObject(0)
            ?: return "❌ Image response empty"
        val b64 = data.optString("b64_json", "")
        val remote = data.optString("url", "")
        val file = File(outDir(ctx), "gen_${System.currentTimeMillis()}.png")
        when {
            b64.isNotBlank() -> {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                FileOutputStream(file).use { it.write(bytes) }
            }
            remote.isNotBlank() -> {
                val (c2, bytes) = http(remote, "GET", emptyMap(), null)
                if (c2 !in 200..299) return "❌ Download fail HTTP $c2"
                FileOutputStream(file).use { it.write(bytes) }
            }
            else -> return "❌ No image url/b64 in response"
        }
        return file.absolutePath
    }

    /** Gemini: imagen-style or flash image preview via generateContent */
    private fun generateGemini(ctx: Context, k: KeyStore.ApiKey, prompt: String): String {
        // Prefer imagen if available; else try gemini-2.0-flash-preview-image-generation style
        val models = listOf(
            "imagen-3.0-generate-002",
            "gemini-2.0-flash-preview-image-generation",
            "gemini-2.0-flash-exp-image-generation"
        )
        var lastErr = ""
        for (model in models) {
            val url = k.base.trimEnd('/') + "/v1beta/models/$model:generateContent?key=" +
                java.net.URLEncoder.encode(k.key, "UTF-8")
            val body = when {
                model.startsWith("imagen") -> JSONObject()
                    .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
                    .put("parameters", JSONObject().put("sampleCount", 1))
                    .toString()
                else -> JSONObject()
                    .put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", "Generate an image: $prompt"))
                            )
                        )
                    )
                    .put(
                        "generationConfig",
                        JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                    )
                    .toString()
            }
            val (code, text) = httpJson(url, "POST", emptyMap(), body)
            if (code !in 200..299) {
                lastErr = "HTTP $code ${text.take(200)}"
                continue
            }
            val path = extractGeminiImage(ctx, text)
            if (path != null) return path
            lastErr = "No image bytes in $model response"
        }
        return "❌ Gemini image generate fail: $lastErr\n💡 Key mein Imagen/image model enable karo, ya OpenAI dall-e key use karo."
    }

    private fun extractGeminiImage(ctx: Context, text: String): String? {
        return try {
            val root = JSONObject(text)
            // imagen predictions
            val pred = root.optJSONArray("predictions")?.optJSONObject(0)
            if (pred != null) {
                val b64 = pred.optString("bytesBase64Encoded", "")
                if (b64.isNotBlank()) {
                    val file = File(outDir(ctx), "gen_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
                    return file.absolutePath
                }
            }
            // generateContent parts inlineData
            val cands = root.optJSONArray("candidates") ?: return null
            for (i in 0 until cands.length()) {
                val parts = cands.getJSONObject(i).optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    val p = parts.getJSONObject(j)
                    val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                    if (inline != null) {
                        val b64 = inline.optString("data", "")
                        if (b64.isNotBlank()) {
                            val file = File(outDir(ctx), "gen_${System.currentTimeMillis()}.png")
                            FileOutputStream(file).use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
                            return file.absolutePath
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Vision: image file padho */
    fun readImage(ctx: Context, path: String, question: String = "Is image mein kya hai? Detail mein batao."): String {
        val k = KeyStore.active(ctx)
            ?: return "❌ API key chahiye (Gemini vision best): api key gemini <key>"
        val f = File(path)
        if (!f.isFile) return "❌ File nahi mili: $path"
        return try {
            when (k.provider) {
                "Gemini" -> visionGemini(k, f, question)
                else -> visionOpenAI(k, f, question)
            }
        } catch (e: Exception) {
            "❌ Vision fail: ${e.message}"
        }
    }

    private fun fileToJpegBase64(f: File, maxSide: Int = 1280): Pair<String, String> {
        val bmp0 = BitmapFactory.decodeFile(f.absolutePath)
            ?: throw Exception("Image decode nahi hui")
        val w = bmp0.width
        val h = bmp0.height
        val scale = if (maxOf(w, h) > maxSide) maxSide.toFloat() / maxOf(w, h) else 1f
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp0, (w * scale).toInt(), (h * scale).toInt(), true)
        } else bmp0
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return Pair(b64, "image/jpeg")
    }

    private fun visionGemini(k: KeyStore.ApiKey, f: File, question: String): String {
        val model = if (k.model.isNotBlank() && !k.model.contains("imagen", true)) k.model else "gemini-2.0-flash"
        val url = k.base.trimEnd('/') + "/v1beta/models/$model:generateContent?key=" +
            java.net.URLEncoder.encode(k.key, "UTF-8")
        val (b64, mime) = fileToJpegBase64(f)
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", question))
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject().put("mime_type", mime).put("data", b64)
                                )
                            )
                    )
                )
            )
            .toString()
        val (code, text) = httpJson(url, "POST", emptyMap(), body)
        if (code !in 200..299) return "❌ Gemini vision HTTP $code: ${text.take(300)}"
        val parts = JSONObject(text).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return "❌ Empty vision response"
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
            if (t.isNotBlank()) sb.append(t)
        }
        return sb.toString().ifBlank { "❌ No text from vision" }
    }

    private fun visionOpenAI(k: KeyStore.ApiKey, f: File, question: String): String {
        val base = k.base.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        val (b64, mime) = fileToJpegBase64(f)
        val dataUrl = "data:$mime;base64,$b64"
        val body = JSONObject()
            .put("model", if (k.model.isNotBlank()) k.model else "gpt-4o-mini")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", question))
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", dataUrl))
                                )
                        )
                )
            )
            .toString()
        val (code, text) = httpJson(url, "POST", mapOf("Authorization" to "Bearer ${k.key}"), body)
        if (code !in 200..299) return "❌ OpenAI vision HTTP $code: ${text.take(300)}"
        return JSONObject(text).optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
            ?.ifBlank { "❌ Empty" } ?: "❌ Parse fail"
    }

    fun lastGenerated(ctx: Context): File? =
        outDir(ctx).listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.maxByOrNull { it.lastModified() }
}
