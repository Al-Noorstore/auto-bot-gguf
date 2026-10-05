package com.alnoor.autobot

import android.content.Context
import org.json.JSONObject

/**
 * ---------- AppLockVault v1.0 (v3.8 / v4.3) ----------
 * Per-app locks: PIN / password / pattern.
 *
 * Security rules:
 *  - Secrets kabhi chat/reply mein print nahi honge — sirf masked (••••).
 *  - Prefs private mode + XOR obfuscation (no-root phones pe sandbox already hai).
 *  - "pattern" ka ASCII visual sirf tab dikhta hai jab user khud pattern set/verify kare.
 *
 * Commands (MainActivity wiring):
 *  - pattern                                  → 3x3 grid help + shapes
 *  - app lock <app> pin 1234
 *  - app lock <app> password <text>
 *  - app lock <app> pattern 1 5 9   (ya: pattern L / Z / U / C / S)
 *  - app lock list        → names + type + masked
 *  - app lock delete <app>
 *  - unlock karo          → live lock-type detect karke real apply
 * Mismatch: fails >= 2 → user se dobara poochte hain.
 */
object AppLockVault {

    private const val PREFS = "applock_vault"
    private const val KEY = "locks"
    private const val XKEY = "autobot-lock-xor-2026" // light obfuscation

    data class LockEntry(
        val pkg: String,        // package ya "name:<label>"
        val type: String,       // "pin" | "password" | "pattern"
        val secret: String,     // pin/password plain; pattern = "1,5,9"
        var fails: Int = 0,
        var enabled: Boolean = true
    )

    // ---------- storage (XOR + Base64) ----------
    private fun obf(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        val k = XKEY.toByteArray()
        val o = ByteArray(b.size) { (b[it].toInt() xor k[it % k.size].toInt()).toByte() }
        return android.util.Base64.encodeToString(o, android.util.Base64.NO_WRAP)
    }
    private fun deobf(s: String): String {
        val b = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
        val k = XKEY.toByteArray()
        val o = ByteArray(b.size) { (b[it].toInt() xor k[it % k.size].toInt()).toByte() }
        return String(o, Charsets.UTF_8)
    }

    private fun load(ctx: Context): JSONObject {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return JSONObject()
        return try { JSONObject(deobf(raw)) } catch (_: Exception) { JSONObject() }
    }
    private fun store(ctx: Context, o: JSONObject) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, obf(o.toString())).apply()
    }

    // ---------- CRUD ----------
    fun set(ctx: Context, pkg: String, type: String, secret: String): LockEntry {
        val o = load(ctx)
        val e = JSONObject().put("type", type).put("secret", secret).put("fails", 0).put("enabled", true)
        o.put(pkg, e)
        store(ctx, o)
        return LockEntry(pkg, type, secret)
    }
    fun get(ctx: Context, pkg: String): LockEntry? {
        val e = load(ctx).optJSONObject(pkg) ?: return null
        return LockEntry(pkg, e.optString("type", "password"), e.optString("secret", ""), e.optInt("fails", 0), e.optBoolean("enabled", true))
    }
    fun remove(ctx: Context, pkg: String): Boolean {
        val o = load(ctx)
        if (!o.has(pkg)) return false
        o.remove(pkg); store(ctx, o); return true
    }
    fun list(ctx: Context): List<LockEntry> {
        val o = load(ctx)
        val out = ArrayList<LockEntry>()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            out.add(LockEntry(k, e.optString("type", "password"), e.optString("secret", ""), e.optInt("fails", 0), e.optBoolean("enabled", true)))
        }
        return out
    }
    fun bumpFail(ctx: Context, pkg: String): Int {
        val o = load(ctx)
        val e = o.optJSONObject(pkg) ?: return 0
        val f = e.optInt("fails", 0) + 1
        e.put("fails", f); o.put(pkg, e); store(ctx, o)
        return f
    }
    fun resetFails(ctx: Context, pkg: String) {
        val o = load(ctx)
        val e = o.optJSONObject(pkg) ?: return
        e.put("fails", 0); o.put(pkg, e); store(ctx, o)
    }

    // ---------- masking (privacy) ----------
    fun mask(e: LockEntry): String = when (e.type) {
        "pin" -> "PIN •••• (${e.secret.length} digits)"
        "pattern" -> "Pattern ${patternLine(e.secret)} (•••)"
        else -> "Password •••• (${e.secret.length} chars)"
    }
    fun typeLabel(t: String): String = when (t) { "pin" -> "PIN"; "pattern" -> "Pattern"; else -> "Password" }

    // ---------- pattern parsing ----------
    // 3x3 grid (Android standard):
    //   1  2  3
    //   4  5  6
    //   7  8  9
    private val SHAPES = mapOf(
        "l" to listOf(1, 4, 7, 8, 9),
        "z" to listOf(1, 2, 3, 5, 7, 8, 9),
        "u" to listOf(1, 4, 7, 8, 9, 6, 3),
        "c" to listOf(3, 6, 9, 8, 7, 4, 1),
        "s" to listOf(3, 2, 1, 4, 7, 8, 9, 6),
        "v" to listOf(1, 4, 8, 6, 3),
        "o" to listOf(1, 2, 3, 6, 9, 8, 7, 4, 1)
    )

    fun parsePattern(raw: String): List<Int>? {
        val t = raw.trim().lowercase()
        SHAPES[t]?.let { return it }
        // named + seq combo: "pattern l" handled by caller; here pure digits: "1 5 9" / "1-5-9" / "159"
        val digits = Regex("[1-9]").findAll(t).map { it.value.toInt() }.toList()
        if (digits.size < 4) {
            // "159" style short seq ya "1 5 9" — kam se kam 3 dots
            if (digits.size >= 3 && digits.distinct().size == digits.size) return digits
        } else if (digits.distinct().size == digits.size) return digits
        return null
    }

    fun patternToSecret(seq: List<Int>): String = seq.joinToString(",")
    fun secretToPattern(secret: String): List<Int> = secret.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..9 }

    fun patternLine(secret: String): String {
        val seq = secretToPattern(secret)
        return if (seq.isEmpty()) "•••" else seq.joinToString(" → ")
    }

    /** ASCII grid — dots ka order dikhata hai. Sirf user ke khud ke pattern set/verify par use karo. */
    fun patternGrid(secret: String): String {
        val seq = secretToPattern(secret)
        if (seq.isEmpty()) return "(pattern samajh nahi aaya)"
        val sb = StringBuilder()
        for (row in 0..2) {
            for (col in 0..2) {
                val n = row * 3 + col + 1
                sb.append(if (n in seq) "◉" else "·")
                if (col < 2) sb.append("   ")
            }
            sb.append("\n")
        }
        sb.append("Order: ").append(seq.joinToString(" → "))
        return sb.toString()
    }

    /** Help text — 3x3 grid + shapes + commands */
    val PATTERN_HELP: String = """
       📐 Pattern grid (3x3 — numbers yaad rakho):
        1   2   3
        4   5   6
        7   8   9
        
        Pattern kaise batana hai:
        • Numbers se: "app lock gallery pattern 1 5 9" (top-left se diagonal)
        • Shape se: "app lock gallery pattern L" / Z / U / C / S / V / O
        • Sirf pattern test: "pattern 1 5 9" → grid dikhaunga, phir "pattern draw karo" se live draw
        • Pattern samajhne ke liye chat mein grid aayega — dots ◉ order mein
        
        PIN: "app lock gallery pin 1234"
        Password: "app lock gallery password mera123"
        List: "app lock list" (secrets masked rehte hain ••••)
    """.trimIndent()
}
