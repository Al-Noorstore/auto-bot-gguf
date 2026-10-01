package com.alnoor.autobot

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Phone ke saved contacts padho (READ_CONTACTS). */
object Phonebook {

    data class Entry(val name: String, val number: String)

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun all(ctx: Context, limit: Int = 500): List<Entry> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<Entry>()
        try {
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            ) ?: return emptyList()
            cur.use {
                val ni = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && out.size < limit) {
                    val name = if (ni >= 0) it.getString(ni) ?: continue else continue
                    val num = if (pi >= 0) it.getString(pi)?.replace(" ", "")?.replace("-", "") ?: continue else continue
                    if (name.isNotBlank() && num.length >= 7) out.add(Entry(name.trim(), num))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Naam se fuzzy match — exact / startsWith / contains */
    fun findByName(ctx: Context, query: String, max: Int = 8): List<Entry> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val all = all(ctx)
        val exact = all.filter { it.name.lowercase() == q }
        if (exact.isNotEmpty()) return exact.take(max)
        val start = all.filter { it.name.lowercase().startsWith(q) }
        if (start.isNotEmpty()) return start.take(max)
        return all.filter { it.name.lowercase().contains(q) }.take(max)
    }

    fun listText(ctx: Context, q: String? = null): String {
        if (!hasPermission(ctx)) return "❌ Contacts permission nahi. Settings se allow karo, phir 'contacts' likho."
        val list = if (q.isNullOrBlank()) all(ctx, 40) else findByName(ctx, q, 20)
        if (list.isEmpty()) return if (q.isNullOrBlank()) "📭 Phone book khali / koi contact nahi." else "❌ '$q' se match nahi."
        val sb = StringBuilder("📇 *Phone contacts*\n")
        list.forEachIndexed { i, e -> sb.append("${i + 1}. ${e.name} — ${e.number}\n") }
        if (q.isNullOrBlank() && all(ctx).size > 40) sb.append("… aur bhi hain. Search: contacts Ali\n")
        return sb.toString()
    }
}
