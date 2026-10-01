package com.alnoor.autobot

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * Client / lead research — public web search (Google + social).
 * Private accounts hack nahi; public search + profile links open.
 */
object ClientFinder {

    fun searchUrls(query: String, niche: String = ""): List<Pair<String, String>> {
        val q = listOf(query, niche).filter { it.isNotBlank() }.joinToString(" ").trim()
        val enc = URLEncoder.encode(q, "UTF-8")
        val encNiche = URLEncoder.encode("$q buyer OR customer OR wholesale", "UTF-8")
        return listOf(
            "Google" to "https://www.google.com/search?q=$enc",
            "LinkedIn" to "https://www.google.com/search?q=site:linkedin.com+$enc",
            "Facebook" to "https://www.google.com/search?q=site:facebook.com+$enc",
            "Instagram" to "https://www.google.com/search?q=site:instagram.com+$enc",
            "Twitter/X" to "https://www.google.com/search?q=site:twitter.com+OR+site:x.com+$enc",
            "Amazon buyers talk" to "https://www.google.com/search?q=$encNiche+site:amazon.com+OR+site:reddit.com",
            "Etsy" to "https://www.google.com/search?q=$enc+site:etsy.com",
            "eBay" to "https://www.google.com/search?q=$enc+site:ebay.com",
            "Email hints" to "https://www.google.com/search?q=%22$enc%22+%28%40gmail.com+OR+%40yahoo.com+OR+email+OR+contact%29"
        )
    }

    fun researchGuide(query: String, niche: String): String {
        val sb = StringBuilder()
        sb.append("🔍 *Client research: $query*\n")
        if (niche.isNotBlank()) sb.append("Niche / product: $niche\n")
        sb.append("\nPublic sources se dhoondho (private inbox access nahi):\n")
        searchUrls(query, niche).forEach { (name, _) -> sb.append("• $name\n") }
        sb.append("\n📌 Jo mile usay save karo:\n")
        sb.append("client add $query | phone +92... | email a@b.com | country PK | niche $niche | interest Amazon/Etsy\n")
        sb.append("\n🛒 E-com angle: unki posts/reviews se dekho kya product chahiye — phir physical contact / WA.\n")
        sb.append("💡 'client search $query fashion' → browser tabs khol dega.")
        return sb.toString()
    }

    fun openSearches(ctx: Context, query: String, niche: String, open: (String) -> Unit) {
        searchUrls(query, niche).take(5).forEach { (_, url) -> open(url) }
    }
}
