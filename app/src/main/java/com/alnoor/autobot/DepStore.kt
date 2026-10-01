package com.alnoor.autobot

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Terminal dependency store — app-private bin/ (Termux pkg nahi, lekin useful tools download).
 * Chat / terminal: dep list | dep install busybox | dep install url <url> <name> | dep remove <name>
 * Har shell command se pehle PATH mein bin/ add hota hai taake Auto Bot + user dono use karein.
 */
object DepStore {

    data class Catalog(
        val id: String,
        val name: String,
        val desc: String,
        val sizeHint: String,
        /** abi prefix -> direct download URL */
        val urls: Map<String, String>
    )

    /** Curated tools — static binaries jo sandbox mein chal sakte hain */
    val catalog: List<Catalog> = listOf(
        Catalog(
            id = "busybox",
            name = "BusyBox",
            desc = "ls, grep, wget, tar, vi… ek binary mein (Termux-lite tools)",
            sizeHint = "~1 MB",
            urls = mapOf(
                "arm64" to "https://busybox.net/downloads/binaries/1.31.0-defconfig-multiarch-musl/busybox-armv8l",
                "arm" to "https://busybox.net/downloads/binaries/1.31.0-defconfig-multiarch-musl/busybox-armv7l",
                "x86_64" to "https://busybox.net/downloads/binaries/1.31.0-defconfig-multiarch-musl/busybox-x86_64"
            )
        ),
        Catalog(
            id = "jq",
            name = "jq",
            desc = "JSON parse/filter (API responses ke liye)",
            sizeHint = "~1–3 MB",
            urls = mapOf(
                "arm64" to "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64",
                "arm" to "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-armel",
                "x86_64" to "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-amd64"
            )
        )
    )

    fun binDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "bin").apply { mkdirs() }

    fun pathPrefix(ctx: Context): String = binDir(ctx).absolutePath

    /** Shell se pehle: export PATH=bin:$PATH */
    fun pathExport(ctx: Context): String {
        val p = pathPrefix(ctx)
        return "export PATH=\"$p:\$PATH\""
    }

    fun abiKey(): String {
        val abis = Build.SUPPORTED_ABIS ?: arrayOf(Build.CPU_ABI ?: "armeabi-v7a")
        for (a in abis) {
            val x = a.lowercase()
            when {
                x.contains("arm64") || x.contains("aarch64") -> return "arm64"
                x.contains("armeabi") || x == "armv7l" || x == "arm" -> return "arm"
                x.contains("x86_64") || x.contains("amd64") -> return "x86_64"
            }
        }
        return "arm64"
    }

    fun installed(ctx: Context): List<String> =
        binDir(ctx).listFiles()?.filter { it.isFile && it.canExecute() }?.map { it.name }?.sorted()
            ?: emptyList()

    fun listText(ctx: Context): String {
        val inst = installed(ctx).toSet()
        val sb = StringBuilder("📦 *Terminal dependencies*\nBin: ${binDir(ctx).absolutePath}\nABI: ${abiKey()}\n\n")
        sb.append("Catalog:\n")
        catalog.forEach { c ->
            val mark = if (c.id in inst || c.name.lowercase() in inst) " ✅" else ""
            sb.append("• ${c.id}$mark — ${c.desc} (${c.sizeHint})\n")
        }
        sb.append("\nInstalled: ${if (inst.isEmpty()) "— koi nahi" else inst.joinToString()}\n")
        sb.append("\n💡 dep install busybox\n💡 dep install jq\n💡 dep install url <https://...> <name>\n💡 dep remove <name>\n")
        sb.append("💡 BusyBox ke baad: busybox ls, busybox wget, busybox tar…\n")
        sb.append("💡 PRO: pip install <pkg> (pure-Python)")
        return sb.toString()
    }

    fun findCatalog(id: String): Catalog? {
        val q = id.trim().lowercase()
        return catalog.firstOrNull { it.id == q || it.name.lowercase() == q }
    }

    /** Download catalog item; onMsg = progress lines */
    fun install(ctx: Context, id: String, onMsg: ((String) -> Unit)? = null): String {
        val c = findCatalog(id) ?: return "❌ Unknown dep '$id'. 'dep list' dekho."
        val abi = abiKey()
        val url = c.urls[abi] ?: c.urls["arm64"] ?: c.urls.values.firstOrNull()
            ?: return "❌ Is ABI ($abi) ke liye URL nahi."
        return installFromUrl(ctx, url, c.id, onMsg)
    }

    fun installFromUrl(ctx: Context, url: String, name: String, onMsg: ((String) -> Unit)? = null): String {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._+-]"), "_").ifBlank { "tool" }
        val target = File(binDir(ctx), safe)
        val part = File(target.absolutePath + ".part")
        onMsg?.invoke("⬇️ Downloading $safe …")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AutoBot-DepStore/1.0")
            val code = conn.responseCode
            if (code !in 200..299) {
                return "❌ Download fail HTTP $code — URL check karo / internet on rakho."
            }
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { inp ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct && pct % 20 == 0) {
                                lastPct = pct
                                onMsg?.invoke("⬇️ $safe: $pct%")
                            }
                        }
                    }
                }
            }
            if (!part.isFile || part.length() < 100) {
                part.delete()
                return "❌ File too small / empty."
            }
            // Reject obvious HTML error pages
            part.inputStream().use { ins ->
                val head = ByteArray(64)
                val n = ins.read(head)
                val s = if (n > 0) String(head, 0, n) else ""
                if (s.contains("<html", true) || s.contains("<!DOCTYPE", true)) {
                    part.delete()
                    return "❌ Server ne HTML diya (direct binary URL chahiye)."
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            target.setExecutable(true, false)
            // busybox: install applets as symlinks optional — user runs "busybox ls"
            onMsg?.invoke("✅ Installed: ${target.absolutePath} (${target.length()} bytes)")
            "✅ $safe install ho gaya (${target.length() / 1024} KB)\nPath: ${target.absolutePath}\nChalane ke liye: $safe …  ya busybox ls"
        } catch (e: Exception) {
            part.delete()
            "❌ Install fail: ${e.message}"
        }
    }

    fun remove(ctx: Context, name: String): String {
        val f = File(binDir(ctx), name.trim())
        return if (f.isFile && f.delete()) "🗑 Hata diya: ${f.name}"
        else "❌ File nahi mili: $name — 'dep list'"
    }
}
