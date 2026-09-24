package com.alnoor.autobot

import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * OFFLINE BRAIN v2.9 — MULTILINGUAL (English / Roman Urdu / Urdu / Hindi / Hinglish)
 * Lightweight local intent detection — bina API key / bina model:
 *  - language detect + reply user ki language mein (jahan supported)
 *  - greetings (EN/RU/UR/HI), how-are-you
 *  - OPEN_APP / CLOSE_APP (app-name fuzzy match)
 *  - YOUTUBE_SEARCH / BROWSER_SEARCH
 *  - contact save (naam + number + relation, multilingual relations), duplicate check
 *  - CALL_CONTACT (relation + name, multiple match → user choice, conversation state)
 *  - END_CALL, SHOW_CONTACT (multiple → choice), CONTACT_EDIT (confirm)
 *  - DELETE_CONTACT → recycle bin, RESTORE_CONTACT, deleted contacts list
 *  - LOCK phone (device admin), unlock honest limitation
 *  - app-lock open, password vault (save-confirm ke saath, private)
 *  - short answers (yes/haan/jee/nahi/ok/cancel) conversation state se resolve
 *  - number extraction + normalization (00… → +…)
 * Hard-coded sentences nahi — intent categories, aliases, patterns, entity extraction,
 * relation matching, fuzzy match, conversation state. Chhota aur fast rehta hai.
 */
object OfflineBrain {

    // ================= LANGUAGE DETECTION =================
    private val URDU_RE = Regex("[\u0600-\u06FF]")
    private val HINDI_RE = Regex("[\u0900-\u097F]")

    private val RU_MARKERS = setOf(
        "karo", "kro", "kar", "krdo", "kardo", "hai", "haan", "nahi", "mera", "meri", "mere", "ko",
        "batao", "bata", "kholo", "khol", "band", "kaat", "kat", "chahiye", "banaya", "wala", "samajh",
        "bhai", "behen", "ammi", "abu", "mamo", "khala", "chacha", "dost", "salam", "assalam", "kese",
        "likho", "bolo", "rakho", "laga", "lao", "wapas", "hata", "badal", "dekh", "dhoondo", "dhundo"
    )
    private val EN_MARKERS = setOf(
        "the", "is", "are", "please", "call", "open", "close", "search", "save", "delete", "restore",
        "lock", "show", "tell", "number", "phone", "contact", "hello", "hi", "hey", "how", "you",
        "brother", "sister", "mother", "father", "uncle", "aunt", "cousin", "friend", "youtube",
        "browser", "google", "change", "edit", "update", "end", "hang", "start", "launch", "ring",
        "dial", "cut", "drop"
    )

    class Lang private constructor(val id: String) {
        companion object {
            val EN = Lang("en")          // English
            val RU = Lang("ru")          // Roman Urdu
            val UR = Lang("ur")          // Urdu script
            val HI = Lang("hi")          // Hindi (Devanagari)
            val MIX = Lang("mix")        // Hinglish / mixed
        }
    }

    fun detectLang(msg: String): Lang {
        if (URDU_RE.containsMatchIn(msg)) return Lang.UR
        if (HINDI_RE.containsMatchIn(msg)) return Lang.HI
        val words = msg.lowercase().split(Regex("[\\s]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return Lang.EN
        var ru = 0; var en = 0
        for (w in words) {
            val clean = w.replace(Regex("[^a-z]"), "")
            if (clean in RU_MARKERS) ru++
            else if (clean in EN_MARKERS) en++
        }
        return when {
            ru > 0 && en > 0 -> Lang.MIX
            ru > 0 -> Lang.RU
            else -> Lang.EN
        }
    }

    /** Language-matched reply: default ru (Roman Urdu) — natural for mixed users. */
    private fun t(lang: Lang, en: String, ru: String, ur: String = ru, hi: String = ru): String = when (lang) {
        Lang.EN -> en; Lang.UR -> ur; Lang.HI -> hi; else -> ru
    }

    // ================= RELATIONS (multilingual alias → canonical) =================
    val REL_MAP: Map<String, String> = mapOf(
        // mother
        "ammi" to "ammi", "ami" to "ammi", "ummi" to "ammi", "amma" to "ammi", "walida" to "ammi",
        "mother" to "ammi", "mom" to "ammi", "mum" to "ammi", "mummy" to "ammi", "maa" to "ammi",
        "امی" to "ammi", "والدہ" to "ammi",
        "माँ" to "ammi", "मां" to "ammi", "मम्मी" to "ammi", "माता" to "ammi",
        // father
        "abu" to "abu", "abbu" to "abu", "walid" to "abu", "father" to "abu", "dad" to "abu",
        "daddy" to "abu", "papa" to "abu", "baba" to "abu",
        "ابو" to "abu", "والد" to "abu", "पापा" to "abu", "पिता" to "abu", "डैड" to "abu",
        // brother
        "bhai" to "bhai", "bhaijaan" to "bhai", "bhaijan" to "bhai", "bhaiya" to "bhai",
        "bhaiyya" to "bhai", "bhayya" to "bhai", "brother" to "bhai",
        "भाई" to "bhai", "भैया" to "bhai", "بھائی" to "bhai",
        // sister
        "behen" to "behen", "behn" to "behen", "ben" to "behen", "sister" to "behen",
        "apa" to "behen", "aapa" to "behen", "baji" to "behen",
        "बहन" to "behen", "बहिन" to "behen", "दीदी" to "behen", "अपा" to "behen",
        // mama (mother's brother)
        "mamo" to "mamo", "mamoo" to "mamo", "mamu" to "mamo", "mamun" to "mamo", "mama" to "mamo",
        "mamaji" to "mamo", "ماموں" to "mamo", "मामा" to "mamo", "मामी" to "mamo",
        // khala (mother's sister)
        "khala" to "khala", "khalaji" to "khala", "khalu" to "khala", "mausi" to "khala",
        "خالہ" to "khala", "मौसी" to "khala",
        // chacha (father's brother)
        "chacha" to "chacha", "chachu" to "chacha", "chachaji" to "chacha",
        "چچا" to "chacha", "चाचा" to "chacha",
        // taya
        "taya" to "taya", "tayaji" to "taya", "تایا" to "taya", "ताया" to "taya", "ताऊ" to "taya",
        // phuppo
        "phuppo" to "phuppo", "phupho" to "phuppo", "phopo" to "phuppo", "phuphi" to "phuppo",
        "bua" to "phuppo", "बुआ" to "phuppo",
        // grandparents
        "nana" to "nana", "nani" to "nana", "نانا" to "nana", "नाना" to "nana", "नानी" to "nana",
        "dada" to "dada", "dadi" to "dada", "دادا" to "dada", "दादा" to "dada", "दादी" to "dada",
        // cousin / uncle / aunt (generic)
        "cousin" to "cousin", "cousinbhai" to "cousin", "cousinsister" to "cousin",
        "uncle" to "uncle", "unkal" to "uncle", "अंकल" to "uncle",
        "aunt" to "aunty", "aunty" to "aunty", "auntyji" to "aunty", "आंटी" to "aunty",
        // friend
        "dost" to "dost", "friend" to "dost", "दोस्त" to "dost", "यार" to "dost",
        // spouse
        "biwi" to "biwi", "wife" to "biwi", "पत्नी" to "biwi",
        "shohar" to "shohar", "husband" to "shohar", "पति" to "shohar",
        // children
        "beta" to "beta", "son" to "beta", "बेटा" to "beta",
        "beti" to "beti", "daughter" to "beti", "बेटी" to "beti",
        // nephews / nieces
        "bhanja" to "bhanja", "bhatije" to "bhanja", "nephew" to "bhanja",
        "bhanji" to "bhanji", "bhatiji" to "bhanji", "niece" to "bhanji",
        // bhabhi
        "bhabhi" to "bhabhi", "भाभी" to "bhabhi"
    )

    // bigram relations (bara bhai / chota bhai / older brother / younger sister...)
    private val REL_BIGRAMS = mapOf(
        "bara bhai" to "bara bhai", "baray bhai" to "bara bhai", "bare bhai" to "bara bhai",
        "bada bhai" to "bara bhai", "barra bhai" to "bara bhai", "older brother" to "bara bhai",
        "elder brother" to "bara bhai", "बड़े भाई" to "bara bhai",
        "chota bhai" to "chota bhai", "chhota bhai" to "chota bhai", "chote bhai" to "chota bhai",
        "younger brother" to "chota bhai", "छोटे भाई" to "chota bhai",
        "bari behen" to "bari behen", "bari behn" to "bari behen", "older sister" to "bari behen",
        "elder sister" to "bari behen", "बड़ी बहन" to "bari behen", "बड़ी दीदी" to "bari behen",
        "choti behen" to "choti behen", "chhoti behen" to "choti behen", "chhoti behn" to "choti behen",
        "younger sister" to "choti behen", "छोटी बहन" to "choti behen"
    )

    val REL_WORDS: Array<String> = REL_MAP.keys.toTypedArray()

    val STOP_WORDS = setOf(
        "ka", "ki", "ke", "ko", "hai", "ha", "hain", "he", "ye", "yeh", "is", "iss", "mera",
        "meri", "mere", "my", "the", "save", "karo", "kro", "karna", "krdo", "kardo", "do",
        "de", "dijiye", "please", "number", "nomber", "num", "no", "contact", "contacts", "add",
        "ek", "aik", "hi", "wale", "wali", "phone", "mobile", "par", "pe", "per", "in", "on", "of",
        "and", "aur", "bhi", "to", "toh", "se", "vala", "wala", "relation", "call", "calling", "dial",
        "saved", "name", "naam", "kis", "kaun", "sab", "abhi", "ab", "jaldi", "number:",
        "yes", "haan", "han", "jee", "ji", "nahi", "nahin", "okay", "ok", "cancel", "kar", "rehne",
        "confirm"
    )

    // verb dictionaries (multilingual)
    val END_CALL_WORDS = arrayOf("end", "cut", "kat", "kato", "kaat", "drop", "band", "close", "rok",
        "काट", "काटो", "कट", "बंद", "रोक", "کاٹ", "کاٹو", "بند", "روک")
    val TELL_WORDS = arrayOf("batao", "bata", "bataa", "batado", "tell", "show", "dikha", "dikhaao",
        "dikhao", "dikhado", "kya", "what", "kaunsa", "which", "बताओ", "बता", "दिखाओ", "بتاؤ", "بتا")
    val SAVE_WORDS = arrayOf("save", "store", "yaad", "rakh", "rakho", "sambhal", "sambhalo",
        "सेव", "जोड़ो")
    val OPEN_WORDS = arrayOf("open", "khol", "kholo", "khola", "launch", "chalu", "chalao", "start",
        "खोलो", "खोल", "शुरू", "کھولو", "کھول")
    val CLOSE_WORDS = arrayOf("close", "band", "exit", "stop", "quit", "बंद", "क्लोज़", "بند")
    val SEARCH_WORDS = arrayOf("search", "dhoondo", "dhoond", "dhundo", "find", "dekho", "dekh",
        "laga", "lagao", "सर्च", "खोजो", "ढूंढो", "سرچ", "دھوندو")
    val DELETE_WORDS = arrayOf("delete", "hata", "hatao", "hatado", "remove", "nikalo",
        "निकालो", "हटाओ", "डिलीट", "ہٹاؤ", "ڈیلیٹ")
    val RESTORE_WORDS = arrayOf("restore", "wapas", "wapis", "recover", "वापस", "लाओ", "واپس", "لاؤ")
    val EDIT_WORDS = arrayOf("change", "badal", "badlo", "badaldo", "edit", "update",
        "बदलो", "बदल", "अपडेट", "بدلو", "اپڈیٹ")
    val NUMBER_WORDS = arrayOf("number", "nomber", "nmb", "नंबर", "نمبر", "نیمبر")
    val CALL_WORDS = arrayOf("call", "dial", "ring", "phone", "calling", "कॉल", "फोन", "کال", "فون")

    // short answers (multilingual)
    private val YES_WORDS = setOf("yes", "haan", "han", "jee", "jihaan", "ok", "okay", "sahi",
        "confirm", "thik", "theek", "karo", "kro", "krdo", "kardo", "हां", "हाँ", "जी", "ठीक",
        "करो", "ہاں", "کر", "کرو")
    private val NO_WORDS = setOf("no", "nahi", "nahin", "na", "cancel", "rehne", "rehnedo", "mat",
        "नहीं", "ना", "मत", "नही", "منہ", "نہیں")

    private val GREET_BARE = setOf(
        "hi", "hello", "hey", "hy", "helo", "salam", "salaam", "assalam", "aoa", "assalamualaikum",
        "assalam o alaikum", "assalam alaikum", "asalam o alakum", "hello bot", "hi bot", "salam bot",
        "namaste", "namaskar", "السلام علیکم", "سلام", "نمستے", "नमस्ते", "नमस्कार", "hello ji"
    )
    private val HOW_ARE_YOU = arrayOf("how are you", "kese ho", "kaise ho", "kya haal", "kia haal",
        "kaisa hai", "kaisi ho", "how r u", "کیسے ہو", "کیا حال", "कैसे हो", "क्या हाल")

    class BrainContact(val name: String, val phone: String, val relation: String?)

    class BrainButton(val label: String, val action: String, val phone: String = "")
    class BrainAction(val action: String, val arg: String = "", val arg2: String = "")

    class BrainReply {
        var text: String = ""
        val buttons = ArrayList<BrainButton>()
        val actions = ArrayList<BrainAction>()
        fun buttonsJson(): String {
            val arr = JSONArray()
            for (b in buttons) {
                val o = JSONObject()
                o.put("label", b.label); o.put("action", b.action); o.put("phone", b.phone)
                arr.put(o)
            }
            return arr.toString()
        }
    }

    // ================= CONVERSATION STATE =================
    private class Pending {
        var kind = ""                     // choice | save | relation | delete | editnum | pw
        var contacts: List<BrainContact> = emptyList()
        var purpose = "call"              // call | show
        var contact: BrainContact? = null
        var name = ""
        var phone = ""
        var relation: String? = null
        var label = ""
        var value = ""
    }
    private var pending: Pending? = null

    // ================= STORAGE =================
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE)

    fun loadContacts(ctx: Context): MutableList<BrainContact> {
        val list = ArrayList<BrainContact>()
        try {
            val arr = JSONArray(prefs(ctx).getString("brain_contacts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rel = if (o.has("relation") && !o.isNull("relation")) o.optString("relation") else null
                list.add(BrainContact(o.optString("name"), o.optString("phone"), rel))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveContacts(ctx: Context, list: List<BrainContact>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("name", x.name); o.put("phone", x.phone)
            if (x.relation != null) o.put("relation", x.relation) else o.put("relation", JSONObject.NULL)
            arr.put(o)
        }
        prefs(ctx).edit().putString("brain_contacts", arr.toString()).apply()
    }

    private fun storeContact(ctx: Context, c: BrainContact) {
        val list = loadContacts(ctx)
        val tail = c.phone.replace(Regex("[^\\d]"), "").takeLast(8)
        list.removeAll { it.phone.replace(Regex("[^\\d]"), "").takeLast(8) == tail }
        list.add(c)
        saveContacts(ctx, list)
    }

    // ---------- recycle bin (deleted contacts) ----------
    private fun loadBin(ctx: Context): MutableList<BrainContact> {
        val list = ArrayList<BrainContact>()
        try {
            val arr = JSONArray(prefs(ctx).getString("brain_bin", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rel = if (o.has("relation") && !o.isNull("relation")) o.optString("relation") else null
                list.add(BrainContact(o.optString("name"), o.optString("phone"), rel))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveBin(ctx: Context, list: List<BrainContact>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("name", x.name); o.put("phone", x.phone)
            if (x.relation != null) o.put("relation", x.relation) else o.put("relation", JSONObject.NULL)
            arr.put(o)
        }
        prefs(ctx).edit().putString("brain_bin", arr.toString()).apply()
    }

    // ---------- password vault (private, sirf isi phone mein) ----------
    fun vaultSave(ctx: Context, label: String, value: String) {
        try {
            val o = JSONObject(prefs(ctx).getString("brain_vault", "{}"))
            o.put(label.trim().lowercase(), value)
            prefs(ctx).edit().putString("brain_vault", o.toString()).apply()
        } catch (_: Exception) {}
    }

    fun vaultGet(ctx: Context, label: String): String? {
        return try {
            val o = JSONObject(prefs(ctx).getString("brain_vault", "{}"))
            val key = label.trim().lowercase()
            if (o.has(key)) o.getString(key)
            else {
                for (k in o.keys()) if (k.contains(key) || key.contains(k)) return o.getString(k)
                null
            }
        } catch (_: Exception) { null }
    }

    fun vaultAll(ctx: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val o = JSONObject(prefs(ctx).getString("brain_vault", "{}"))
            for (k in o.keys()) out.add(k)
        } catch (_: Exception) {}
        return out
    }

    // ================= PARSING HELPERS =================

    /** Phone number normalize: 00XX → +XX, 10-12 digit → +XX */
    fun normalizePhone(p: String): String {
        var n = p.trim().replace(Regex("[^\\d+]"), "")
        if (n.startsWith("00")) n = "+" + n.substring(2)
        if (n.startsWith("+")) return n
        if (n.length in 10..12) return "+$n"
        return n
    }

    fun extractPhone(text: String): String? {
        val m = Regex("(\\+?\\d[\\d\\s\\-]{7,14}\\d)").find(text) ?: return null
        val clean = m.groupValues[1].replace(Regex("[\\s\\-]"), "")
        return if (clean.replace("+", "").length >= 10) clean else null
    }

    /** relation alias → canonical (single token ya bigram). null = koi relation nahi. */
    fun relationOf(low: String): String? {
        for ((bg, canon) in REL_BIGRAMS) if (low.contains(bg)) return canon
        for (w in low.split(Regex("[\\s]+"))) {
            val canon = REL_MAP[w]
            if (canon != null) return canon
        }
        return null
    }

    private fun extractName(words: List<String>): String {
        val junk = HashSet(STOP_WORDS)
        junk.addAll(REL_MAP.keys)
        junk.addAll(listOf("hai", "hu", "ho", "hun", "hu", "walid", "walida", "se", "se"))
        val out = ArrayList<String>()
        for (w in words) {
            if (w.length < 2) continue
            if (w.any { it.isDigit() || it == '+' }) continue
            if (w in junk) continue
            if (REL_BIGRAMS.keys.any { it.split(" ").contains(w) }) continue
            out.add(w)
        }
        return out.take(2).joinToString(" ")
    }

    /** call target: "call X" | "X ko call/phone karo" | "ring my father" | "X ko phone laga do" */
    private fun beforeVerbTarget(low: String): String? {
        val patterns = listOf(
            Regex("^(?:call|dial|ring|کال|فون|कॉल)\\s+(?:my|mere|meri|meray)?\\s*(.+?)\\s*(?:ko|ka|karo|kro|krdo|kardo|please|abhi|now|jaldi|laga|lagao|do|de|dy|kariye)*\\s*$"),
            Regex("^(.+?)\\s+(?:ko)?\\s*(?:call|phone|dial|ring|کال|कॉल|फोन|فون)\\s*(?:laga|lagaam|lagao|karo|kro|kardo|krdo|do|de|dy|karna|karni|krna)*\\s*(?:karo|kro|krdo|kardo|do|de|dy|please|abhi|jaldi)*\\s*$")
        )
        for (p in patterns) {
            val m = p.find(low)
            if (m != null) {
                val g = m.groupValues[1].trim()
                    .replace(Regex("\\s+(?:karo|kro|krdo|kardo|please|abhi|now|jaldi)+\\s*$"), "")
                    .replace(Regex("\\s+(?:ko|ka)+\\s*$"), "").trim()
                if (g.isNotBlank() && g !in listOf("my", "mere", "meri", "meray")) return g
            }
        }
        return null
    }

    /** resolve name/relation string → matching contacts (name + relation dono fuzzy). */
    fun resolve(contacts: List<BrainContact>, target: String): List<BrainContact> {
        val words = target.split(Regex("[\\s]+")).filter { it.length > 1 && it !in STOP_WORDS && it !in listOf("mera", "meri", "my", "ko", "karo", "ka", "ki") }
        if (words.isEmpty()) return emptyList()
        val rel = relationOf(target)
        val nameWords = words.filter { REL_MAP[it] == null && !REL_BIGRAMS.containsKey(it) }
        val nameMatch = { c: BrainContact -> nameWords.any { c.name.lowercase().contains(it.lowercase()) } }
        val relMatch = { c: BrainContact -> rel != null && c.relation != null && (c.relation!!.lowercase() == rel.lowercase() || c.relation!!.lowercase().contains(rel.lowercase()) || rel.lowercase().contains(c.relation!!.lowercase())) }
        if (nameWords.isNotEmpty()) {
            val both = contacts.filter { nameMatch(it) && (rel == null || relMatch(it)) }
            if (both.isNotEmpty()) return both
            val nm = contacts.filter { nameMatch(it) }
            if (nm.isNotEmpty()) return nm
        }
        if (rel != null) {
            val rm = contacts.filter { relMatch(it) }
            if (rm.isNotEmpty()) return rm
        }
        return emptyList()
    }

    private fun cleanQuery(q: String): String = q.trim().replace(Regex("[?.!]+"), "").trim()

    // ================= SHORT ANSWERS =================
    private fun isYes(low: String): Boolean {
        val bare = low.replace(Regex("[^\\p{L}\\p{M} ]"), "").trim().trim()
        return bare in YES_WORDS || (YES_WORDS.any { it.length > 4 && bare == it } )
    }
    private fun isNo(low: String): Boolean {
        val bare = low.replace(Regex("[^\\p{L}\\p{M} ]"), "").trim().trim()
        return bare in NO_WORDS || (NO_WORDS.any { it.length > 4 && bare == it })
    }
    private fun pickNumber(low: String, max: Int): Int? {
        val m = Regex("\\b(\\d{1,2})\\b").find(low) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return if (n in 1..max) n else null
    }

    /**
     * Pending conversation state resolve karta hai. true = reply ready.
     */
    private fun handlePending(ctx: Context, lang: Lang, low: String, r: BrainReply): Boolean {
        val p = pending ?: return false
        val isYesW = isYes(low); val isNoW = isNo(low); val num = pickNumber(low, p.contacts.size)
        val bare = low.replace(Regex("[^\\p{L}\\p{M}\\d ]"), "").trim().trim()
        val looksNewCommand = beforeVerbTarget(low) != null || extractPhone(low) != null

        when (p.kind) {
            "choice" -> {
                if (isNoW) { pending = null; r.text = t(lang, "Okay, cancelled.", "Theek hai, cancel."); return true }
                var pick: BrainContact? = null
                if (num != null && p.contacts.isNotEmpty()) pick = p.contacts[num - 1]
                else if (bare.isNotBlank() && p.contacts.isNotEmpty()) {
                    pick = p.contacts.firstOrNull { c -> c.name.lowercase().contains(bare.lowercase()) }
                        ?: resolve(p.contacts, bare).firstOrNull()
                }
                if (pick == null && looksNewCommand) {
                    val t0 = beforeVerbTarget(low)!!
                    pick = resolve(p.contacts, t0).firstOrNull()
                }
                if (pick != null) {
                    pending = null
                    if (p.purpose == "show") {
                        r.text = "📱 ${pick.name}" + (if (pick.relation != null) " (${pick.relation})" else "") + " ka number: ${pick.phone}"
                        r.buttons.add(BrainButton("📞 Call ${pick.name}", "call", pick.phone))
                        r.buttons.add(BrainButton("💬 WhatsApp", "wa", pick.phone))
                    } else {
                        val rel = if (pick.relation != null) " (${pick.relation})" else ""
                        r.text = "📞 ${pick.name}$rel ko call laga raha hoon: ${pick.phone}"
                        r.actions.add(BrainAction("call", pick.phone))
                    }
                    return true
                }
                // user ne kuch aur kaha — pending clear, naya message normal parse hoga
                pending = null
                return false
            }
            "save" -> {
                if (isYesW) {
                    pending = null
                    val c = BrainContact(p.name, normalizePhone(p.phone), p.relation)
                    storeContact(ctx, c)
                    val relTxt = if (p.relation != null) " | Relation: ${p.relation}" else " | Relation: (optional)"
                    r.text = "✅ Save ho gaya!\n👤 ${p.name} — ${normalizePhone(p.phone)}$relTxt\nAb \"${if (p.relation != null) p.relation + " " else ""}${p.name} ko call karo\" bolo."
                    r.actions.add(BrainAction("phonebook", p.name, normalizePhone(p.phone)))
                    return true
                }
                if (isNoW) { pending = null; r.text = t(lang, "Okay, not saved.", "Theek hai, save nahi kiya."); return true }
                pending = null
                return false
            }
            "relation" -> {
                val rel = relationOf(low)
                if (rel == null) {
                    // user ne koi aur command bola — pending chhodo, normal parse
                    if (beforeVerbTarget(low) != null || extractPhone(low) != null || low.contains("khol") || low.contains("open") || low.contains("lock")) {
                        pending = null
                        return false
                    }
                    r.text = "Relation samajh nahi aaya. Bhai, behen, mamo, khala, chacha, uncle, dost...?"
                    return true
                }
                pending = null
                val c = BrainContact(p.name, normalizePhone(p.phone), rel)
                val contacts = loadContacts(ctx)
                val tail = c.phone.replace(Regex("[^\\d]"), "").takeLast(8)
                val dup = contacts.any { it.phone.replace(Regex("[^\\d]"), "").takeLast(8) == tail }
                if (dup) {
                    pending = Pending().apply { kind = "save"; name = c.name; phone = c.phone; relation = rel }
                    r.text = "⚠️ Ye number pehle se kisi aur naam se saved hai. \"${c.name}\" ke naam se update karun?\n[haan / nahi]"
                    return true
                }
                storeContact(ctx, c)
                r.text = "✅ Save ho gaya!\n👤 ${c.name} — ${c.phone} | Relation: $rel\n\"$rel ko call karo\" bolo to call laga dunga."
                r.actions.add(BrainAction("phonebook", c.name, c.phone))
                return true
            }
            "delete" -> {
                if (isYesW) {
                    pending = null
                    val c = p.contact
                    if (c == null) return false
                    val list = loadContacts(ctx)
                    list.removeAll { it.name == c.name && it.phone == c.phone }
                    saveContacts(ctx, list)
                    val bin = loadBin(ctx); bin.add(c); saveBin(ctx, bin)
                    r.text = "🗑️ ${c.name} delete ho gaya (recycle bin mein — \"${c.name} ko wapas lao\" se restore hoga)."
                    return true
                }
                if (isNoW) { pending = null; r.text = t(lang, "Okay, not deleted.", "Theek hai, delete nahi kiya."); return true }
                pending = null
                return false
            }
            "editnum" -> {
                val phoneNew = extractPhone(low)
                val c = p.contact
                if (c != null && phoneNew != null) {
                    val list = loadContacts(ctx)
                    val idx = list.indexOfFirst { it.name == c.name && it.phone == c.phone }
                    if (idx >= 0) {
                        list[idx] = BrainContact(c.name, normalizePhone(phoneNew), c.relation)
                        saveContacts(ctx, list)
                    }
                    pending = null
                    r.text = "✅ ${c.name} ka number update ho gaya: ${normalizePhone(phoneNew)}"
                    return true
                }
                if (isNoW) { pending = null; r.text = t(lang, "Okay, cancelled.", "Theek hai, cancel."); return true }
                return true // naya number ka intezaar
            }
            "pw" -> {
                if (isYesW) {
                    pending = null
                    vaultSave(ctx, p.label, p.value)
                    r.text = "🔒 Password securely save ho gaya: \"${p.label}\" (private, sirf isi phone).\nBatana ho to: \"${p.label} ka password batao\""
                    return true
                }
                if (isNoW) { pending = null; r.text = t(lang, "Okay, not saved.", "Theek hai, save nahi kiya — use once."); return true }
                pending = null
                return false
            }
        }
        pending = null
        return false
    }

    // ================= MAIN PARSER =================
    /**
     * Main parser. null return = brain ne handle nahi kiya → AI/normal chat flow chalega.
     */
    fun parse(ctx: Context, low: String, msg: String): BrainReply? {
        val words = low.split(Regex("[\\s]+")).filter { it.isNotBlank() }
        val lang = detectLang(msg)
        val r = BrainReply()

        // ---------- 0) pending conversation state ----------
        if (pending != null && handlePending(ctx, lang, low, r)) return r

        // ---------- 0b) MODEL NAAM se seedha download ("Tiny Llama-1.1B", "smollm2 download karo", "qwen") ----------
        run {
            val norm = low.replace(Regex("[^a-z0-9]"), "")
            val hit = ModelStore.presets.firstOrNull { p ->
                val alias = when {
                    p.name.startsWith("SmolLM") -> "smollm"
                    p.name.startsWith("Qwen") -> "qwen"
                    p.name.startsWith("TinyLlama") -> "tinyllama"
                    else -> p.name.lowercase().replace(Regex("[^a-z0-9]"), "")
                }
                norm.contains(alias)
            }
            val dlWord = Regex("download|downlad|\\bdl\\b|\\blana\\b|\\blao\\b|chahiye|install").containsMatchIn(low)
            val otherWord = Regex("connect|laga|\\buse\\b|select|delete|hata|remove|\\blist\\b").containsMatchIn(low)
            if (hit != null && !otherWord && (dlWord || words.size <= 4)) {
                val have = ModelStore.downloaded(ctx).contains(hit.file)
                val need = Regex("\\d+").find(hit.size)?.value?.toLongOrNull() ?: 0L
                val freeMb = try { android.os.StatFs(ModelStore.dir(ctx).path).availableBytes / 1048576 } catch (e: Exception) { Long.MAX_VALUE }
                if (have) {
                    r.text = "✅ ${hit.name} pehle se download hai. Connect ke liye likho: \"${hit.name} connect\""
                } else if (freeMb < need * 13 / 10) {
                    r.text = "❌ Storage kam hai — ${hit.name} ko ~${need}MB chahiye, khali sirf ${freeMb}MB hai. Jagah khali karo ya halka model (SmolLM2-135M) lo."
                } else {
                    r.text = "⬇️ ${hit.name} (${hit.size}) download shuru — neeche live progress bar dekho."
                    r.actions.add(BrainAction("dlmodel", hit.name))
                }
                return r
            }
        }

        // ---------- 1) GREETING ----------
        val bare = low.replace(Regex("[^\\p{L}\\p{M} ]"), "").trim()
        val isGreet = bare in GREET_BARE || words.all { it.replace(Regex("[^\\p{L}\\p{M}]"), "") in GREET_BARE }
        val howAreYou = HOW_ARE_YOU.any { bare.contains(it) } && bare.split(Regex("[\\s]+")).size <= 5
        if ((isGreet || howAreYou) && prefs(ctx).getBoolean("brain_greeting", true)) {
            r.text = if (howAreYou) t(lang,
                "I'm good! How can I help you?",
                "Main theek hoon. Aap ko kis cheez mein help chahiye?")
            else if (low.contains("salam") || low.contains("assalam") || low.contains("سلام")) {
                t(lang, "Wa Alaikum Assalam! How can I help?", "Wa Alaikum Assalam! Kya help karun?")
            } else if (lang == Lang.HI) "नमस्ते! 👋 बताइए, क्या मदद करूं?"
            else if (lang == Lang.EN) "Hi! How can I help?"
            else "Hello! 👋 Kya help karun?"
            return r
        }

        // ---------- 2) UNLOCK phone: honest limitation ----------
        val unlockIntent = low.contains("unlock") || ((low.contains("khol") || low.contains("open") || low.contains("کھول") || low.contains("खोल")) && low.contains("lock") && (low.contains("phone") || low.contains("mobile") || low.contains("screen")) && !low.contains("app"))
        if (unlockIntent) {
            r.text = "🔒 Unlock Android security ki wajah se koi bhi app khud nahi kar sakta — PIN/password/fingerprint aap khud daloge. Main phone LOCK kar sakta hoon, unlock aap khud karoge.\n\nApp-lock app kholni ho to: \"app lock kholo\" bolo (saved password bhi bata dunga)."
            return r
        }

        // ---------- 3) LOCK phone ----------
        val lockWord = low.contains("lock") || low.contains("लॉक") || low.contains("لاک")
        val deviceWord = low.contains("phone") || low.contains("mobile") || low.contains("screen") || low.contains("मोबाइल") || low.contains("मोबिल") || low.contains("فون") || low.contains("موبائل") || low == "lock"
        if (lockWord && deviceWord && !low.contains("app") && !low.contains("unlock") && !low.contains("khol") && !low.contains("open")) {
            r.text = t(lang, "🔒 Locking your phone.", "🔒 Phone lock kar raha hoon.")
            r.actions.add(BrainAction("lock"))
            return r
        }

        // ---------- 4) END call ----------
        val hasCallWord = CALL_WORDS.any { words.contains(it) } || low.contains("call") || low.contains("phone") || low.contains("کال") || low.contains("कॉल") || low.contains("फोन") || low.contains("فون")
        val endHit = END_CALL_WORDS.any { words.contains(it) }
        val bareEnd = low in setOf("drop", "drop do", "drop karo", "kat", "kat do", "kato", "kaat do", "cut", "cut do", "call kato", "phone kat do", "call kaat do")
        if (endHit && (hasCallWord || bareEnd) && !lockWord) {
            r.text = t(lang, "🔴 Ending the call...", "🔴 Call khatam kar raha hoon...")
            r.actions.add(BrainAction("endcall"))
            r.buttons.add(BrainButton("🔴 End Call", "endcall"))
            return r
        }

        // ---------- v3.0: TORCH (flashlight) ----------
        val torchWord = low.contains("torch") || low.contains("flash") || low.contains("flashlight") || low.contains("ٹارچ") || low.contains("फ़्लैश") || low.contains("लालटेन")
        if (torchWord) {
            val off = low.contains("off") || low.contains("band") || low.contains("بند") || low.contains("बंद") || low.contains("close") || low.contains("band") || low.contains("karo band")
            r.text = if (off) t(lang, "🔇 Torch off.", "🔇 Torch band kar raha hoon.")
            else t(lang, "🔦 Torch on.", "🔦 Torch jala raha hoon.")
            r.actions.add(BrainAction(if (off) "torchoff" else "torch"))
            return r
        }

        // ---------- v3.0: SPEAKER (call ke dor) ----------
        if (low.contains("speaker") || low.contains("spkr")) {
            val off = low.contains("off") || low.contains("band") || low.contains("بند") || low.contains("बंद") || low.contains("close")
            r.text = if (off) "🔇 Speaker band kar raha hoon." else "📢 Speaker on kar raha hoon."
            r.actions.add(BrainAction(if (off) "speakeroff" else "speaker"))
            return r
        }

        // ---------- v3.0: VOLUME ----------
        val volWord = low.contains("volume") || low.contains("awaaz") || low.contains("awaz") || low.contains("awaz ka") || low.contains("आवाज़")
        val volAdj = low.contains("kam") || low.contains("down") || low.contains("low") || low.contains("neechay") || low.contains("कम") ||
            low.contains("badh") || low.contains("zyada") || low.contains("up") || low.contains("high") || low.contains("upar") || low.contains("बढ़ा") ||
            low.contains("full") || low.contains("max") || low.contains("poora") || low.contains("mute") || low.contains("khamosh") || low.contains("silence")
        if (volWord && volAdj && !low.contains("mic") && !low.contains("voice")) {
            val act = when {
                low.contains("mute") || low.contains("khamosh") || low.contains("silence") -> "volmute"
                low.contains("full") || low.contains("max") || low.contains("poora") -> "volmax"
                low.contains("badh") || low.contains("zyada") || low.contains("up") || low.contains("high") || low.contains("upar") || low.contains("बढ़ा") -> "volup"
                else -> "voldown"
            }
            r.text = "🔊 Volume adjust kar raha hoon."
            r.actions.add(BrainAction(act))
            return r
        }

        // ---------- v3.0: BATTERY ----------
        if ((low.contains("battery") || low.contains("batri") || low.contains("charge kitna") || low.contains("charge kitni") || low.contains("बैटरी") || low.contains("چارج")) && (TELL_WORDS.any { words.contains(it) } || low.contains("kitna") || low.contains("kitni") || low.contains("how") || low.contains("status"))) {
            return try {
                val batt = ctx.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                if (level < 0) { r.text = "❓ Battery status nahi mila."; r }
                else {
                    val pct = level * 100 / scale
                    r.text = "🔋 Battery: $pct%" + (if (charging) " (charging hai ⚡)" else "")
                    r
                }
            } catch (e: Exception) { r.text = "❓ Battery status nahi mila."; r }
        }

        // ---------- v3.0: TIME / DATE ----------
        val timeAsk = (low.contains("time") || low.contains("waqt") || low.contains("kitne baje") || low.contains("bajay") || low.contains("baje hai") || low.contains("समय") || low.contains("कितने बजे") || low.contains("وقت")) &&
            (TELL_WORDS.any { words.contains(it) } || low.contains("kya") || low.contains("kitne") || low.contains("what"))
        if (timeAsk) {
            val now = Date()
            val tm = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
            val dt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            r.text = "🕒 Ab waqt: $tm\n📅 $dt"
            return r
        }

        // ---------- v3.0: ALARM / TIMER ----------
        val hasAlarmWord = low.contains("alarm") || low.contains("timer") || low.contains("घड़ी") || low.contains("الارم") || low.contains("ٹائمر")
        if (hasAlarmWord) {
            // list
            if (TELL_WORDS.any { words.contains(it) } || low.contains("list") || low == "alarms" || low == "alarm" || low.contains("kaunse") || low.contains("kitne")) {
                if (!low.contains("lagao") && !low.contains("set") && !low.contains("lagana") && !low.contains("ka alarm")) {
                    r.text = AlarmEngine.listText(ctx); return r
                }
            }
            // remove/cancel
            val removing = low.contains("hatao") || low.contains("hata") || low.contains("cancel") || low.contains("remove") || low.contains("off") || low.contains("band") || low.contains("nikalo") || low.contains("del")
            if (removing) {
                if (low.contains("sab") || low.contains("all") || low.contains("sare") || low.contains("کل")) {
                    val n = AlarmEngine.cancelAll(ctx)
                    r.text = if (n > 0) "🗑️ Sab $n alarms hata diye." else "📭 Koi alarm set nahi tha."
                } else {
                    val alarms = AlarmEngine.list(ctx)
                    val last = alarms.lastOrNull()
                    if (last == null) r.text = "📭 Koi alarm set nahi tha."
                    else { AlarmEngine.cancel(ctx, last.id); r.text = "🗑️ Alarm hata diya: ${AlarmEngine.pretty(last)}" }
                }
                return r
            }
            // timer: "5 minute ka alarm/timer"
            val dur = Regex("(\\d{1,3})\\s*(minute|minutes|min|minit|second|seconds|sec|hour|hours|hr|ghanta|ghante|ghantay)").find(low)
            if (dur != null) {
                val n = dur.groupValues[1].toInt()
                val unit = dur.groupValues[2]
                val secs = when {
                    unit.startsWith("sec") -> n.toLong()
                    unit.startsWith("ghant") || unit.startsWith("hr") || unit.startsWith("hour") -> n * 3600L
                    else -> n * 60L
                }
                if (secs in 5..86400) {
                    val a = AlarmEngine.scheduleTimer(ctx, secs, "$n $unit ka timer")
                    val mins = secs / 60
                    r.text = "⏰ Timer set: $n $unit" + (if (secs >= 60 && secs % 60 == 0L) " ($mins min)" else "") + ".\nWaqt hone par loud alarm bajega — Snooze/Stop ke saath."
                    return r
                }
            }
            // time alarm: "alarm lagao 6 baje" / "subah 6:30 ka alarm" / "roz ka alarm 7 baje"
            val mHMS = Regex("(\\d{1,2})[:.](\\d{2})").find(low)
            val mH = Regex("\\b(\\d{1,2})\\s*(?:baje|bje|am|pm|o.?clock|bajar|بجے|बजे)").find(low)
            var hour: Int? = null
            var minute = 0
            if (mHMS != null) { hour = mHMS.groupValues[1].toIntOrNull(); minute = mHMS.groupValues[2].toIntOrNull() ?: 0 }
            else if (mH != null) hour = mH.groupValues[1].toIntOrNull()
            if (hour != null && hour in 0..23 && minute in 0..59) {
                var h = hour
                var pm: Boolean? = null
                if (low.contains("pm")) pm = true else if (low.contains("am")) pm = false
                if (low.contains("shaam") || low.contains("sham") || low.contains("raat") || low.contains("evening") || low.contains("night")) pm = true
                if (low.contains("subah") || low.contains("morning")) pm = false
                if (pm == true && h in 1..11) h += 12
                if (pm == false && h == 12) h = 0
                val daily = low.contains("roz") || low.contains("daily") || low.contains("har din") || low.contains("rozana") || low.contains("everyday") || low.contains("every day") || low.contains("हर रोज़") || low.contains("روز")
                val a = AlarmEngine.schedule(ctx, h, minute, daily, if (daily) "Roz ka alarm" else "Alarm")
                r.text = "⏰ Alarm set: " + AlarmEngine.pretty(a) + "\n" + (if (daily) "Har roz is waqt bajega. Phone restart ho to bhi re-set ho jata hai." else "Aaj ya kal (jo pehle aaye) is waqt bajega.")
                return r
            }
            // alarm word tha lekin time samajh nahi aaya
            r.text = "⏰ Time samajh nahi aaya. Examples:\n• \"alarm lagao 6 baje\"\n• \"subah 6:30 ka alarm\"\n• \"roz ka alarm 7 baje\"\n• \"5 minute ka alarm\" (timer)\n• \"alarm list\" • \"alarm hatao\""
            return r
        }

        // ---------- 5) CLOSE app ----------
        val closeHit = CLOSE_WORDS.any { words.contains(it) }
        if (closeHit && !low.contains("call") && !lockWord) {
            val target = Regex("^(?:close|exit|stop|quit|band|बंद|बंद)\\s+(?:the\\s+|meri\\s+|mere\\s+|my\\s+)?(.+?)(?:\\s+(?:app|karo|kro|krdo|kardo|kar|do|de|dy))*\\s*$").find(low)?.groupValues?.get(1)
                ?: Regex("^(.+?)\\s+(?:ko\\s+)?(?:band|close|बंद|بند)\\s*(?:karo|kro|krdo|kardo|do|de|dy)?\\s*(?:hai|ha)?\\s*$").find(low)?.groupValues?.get(1)
            if (target != null && target.isNotBlank() && target.trim() !in listOf("karo", "kro", "do", "kar", "")) {
                r.text = t(lang, "🔇 Closing ${target.trim()}.", "🔇 ${target.trim()} band kar raha hoon.")
                r.actions.add(BrainAction("closeapp", target.trim()))
                return r
            }
        }

        // ---------- 6) YOUTUBE search ----------
        if (low.contains("youtube") || low.contains("یوٹیوب") || low.contains("यूट्यूब")) {
            // v3.0: "play 2" jaisi command MainActivity ke paas jaye (number-wise play)
            if (low.contains("play")) return r
            val searchHit = SEARCH_WORDS.any { words.contains(it) } || low.contains("search")
            if (searchHit) {
                var q = low
                    .replace(Regex("^(?:on\\s+)?(?:youtube|یوٹیوب|यूट्यूब)\\s+(?:par|pe|per|mein|main|پر|पर|में|میں)\\s+"), "")
                    .replace(Regex("^(?:youtube|یوٹیوب|यूट्यूब)\\s+(?:par|pe|per|پर|पर)\\s+"), "")
                    .replace(Regex("^(?:search|dhoondo|dhundo|dhoond|find|dekh|dekho|laga|lagao)\\s+(?:youtube|یوٹیوب|यूट्यूब)\\s+(?:par|pe|per|پर|पर)?\\s*(?:for|ke liye|k liye)?\\s*"), "")
                    .replace(Regex("^(?:youtube|یوٹیوب|यूट्यूब)\\s+(?:search|سرچ|सर्च)\\s+"), "")
                    .replace(Regex("\\s+(?:search|dhoondo|dhundo|dhoond|laga|lagao|chalao|karo|kro|krdo|kardo|do|de|dy|dikhao|dikha|hai|ha|jao|mein|main|par|pe|per|on|youtube|پर|पर|में|میں)+\\s*$"), "")
                    .replace(Regex("^(?:search|find|سرچ|सर्च)\\s+"), "").trim()
                if (q.isNotBlank()) {
                    r.text = t(lang, "▶️ Searching YouTube: \"$q\"", "▶️ YouTube par search kar raha hoon: \"$q\"")
                    r.actions.add(BrainAction("youtubesearch", cleanQuery(q)))
                    return r
                }
            }
        }

        // ---------- 7) BROWSER / CHROME search + TAB control ----------
        // Keyword yahan kahin bhi ho sakta hai -- shuru, beech, aakhir -- sab jagah pehchana jata hai.
        val browserWord = words.any { it in setOf("browser", "chrome", "google", "web", "\u0628\u0631\u0627\u0624\u0632\u0631", "\u0917\u0942\u0917\u0932", "\u092c\u094d\u0930\u093e\u0909\u091c\u0930") }
        val searchWord = SEARCH_WORDS.any { words.contains(it) } || words.contains("search")
        val hasTabWord = words.contains("tab") || words.contains("tabs")
        val newTabWord = hasTabWord && words.any { it in setOf("new", "naya", "nayi", "naye") }
        val sameTabWord = hasTabWord && words.any { it in setOf("same", "wahi", "isi", "usi") }
        val closeTabWord = hasTabWord && words.any { it in setOf("band", "bandh", "close", "\u0628\u0646\u062f", "\u092c\u0902\u0926") } && !searchWord && !newTabWord && !sameTabWord

        // 7a) sirf tab close/band -- koi search nahi ("tab band karo" / "band karo tab" / "close tab", order matters nahi)
        if (closeTabWord) {
            r.text = t(lang, "Closing active tab.", "Active tab band kar raha hoon.")
            r.actions.add(BrainAction("closetab", ""))
            return r
        }

        // 7b) browser/chrome + search -- query keyword ke ird-gird kisi bhi jagah ho sakti hai
        if (browserWord && searchWord) {
            val skip = setOf(
                "open", "kholo", "khol", "launch", "start", "chalu", "chalao",
                "browser", "chrome", "google", "web", "\u0628\u0631\u0627\u0624\u0632\u0631", "\u0917\u0942\u0917\u0932", "\u092c\u094d\u0930\u093e\u0909\u091c\u0930",
                "search", "dhoondo", "dhundo", "dhoond", "find", "dekho", "dekh", "laga", "lagao", "\u0633\u0631\u0686", "\u0938\u0930\u094d\u091a",
                "at", "in", "on", "mein", "main", "par", "pe", "per", "\u0645\u06cc\u06ba", "\u067e\u0631", "\u092e\u0947\u0902", "\u092a\u0930",
                "new", "naya", "nayi", "naye", "same", "wahi", "isi", "usi", "tab", "tabs",
                "karo", "kro", "krdo", "kardo", "kar", "do", "de", "dy", "please", "na", "jaldi", "abhi", "hai", "ha",
                "and", "aur", "the", "a", "an", "for", "ke", "liye", "k"
            )
            val q = words.filter { it.replace(Regex("[^a-z0-9\u0900-\u097F\u0600-\u06FF]"), "") !in skip }.joinToString(" ").trim()
            if (q.isNotBlank()) {
                val where = if (newTabWord) "naye tab mein" else if (sameTabWord) "isi tab mein" else "browser mein"
                r.text = t(lang, "Searching: \"$q\"", "\"$q\" search kar raha hoon ($where).")
                r.actions.add(BrainAction(if (newTabWord) "browsernewtabsearch" else if (sameTabWord) "browsersametabsearch" else "browsersearch", cleanQuery(q)))
                return r
            }
        }

        // 7c) sirf browser/chrome kholna, tab directive ke saath -- bina search query
        if (browserWord && OPEN_WORDS.any { words.contains(it) } && !searchWord) {
            if (newTabWord) { r.text = t(lang, "Opening new tab.", "Nayi tab khol raha hoon."); r.actions.add(BrainAction("browsernewtabsearch", "")); return r }
            if (sameTabWord) { r.text = t(lang, "Reusing current tab.", "Isi tab mein khol raha hoon."); r.actions.add(BrainAction("browsersametabsearch", "")); return r }
        }

        // ---------- 8) SAVE contact (naam + number + relation, multilingual) ----------
        val phone = extractPhone(msg)
        val saveHit = SAVE_WORDS.any { words.contains(it) } || low.contains("save")
        if (phone != null && (saveHit || relationOf(low) != null)) {
            val rel = relationOf(low)
            var name = extractName(words)
            // "save <naam> <number>" classic format bhi chalta rahe
            if (name.isBlank()) {
                val m = Regex("(?:save|add|store)\\s+([\\p{L}\\p{M}]+)").find(low)
                name = m?.groupValues?.get(1) ?: ""
            }
            if (name.isBlank()) {
                r.text = "📞 Number mila ($phone) lekin naam samajh nahi aaya.\nLikho: \"save Ali $phone\" (relation optional: bhai/behen/mamo...)"
                return r
            }
            val relTxt = if (rel != null) " | Relation: $rel" else " | Relation: (optional — bataya nahi)"
            r.text = "✅ Contact save ho gaya!\n👤 $name — ${normalizePhone(phone)}$relTxt\nAb \"${if (rel != null) rel + " " else ""}$name ko call karo\" bolo to call laga dunga."
            r.actions.add(BrainAction("phonebook", name, normalizePhone(phone)))
            storeContact(ctx, BrainContact(name, normalizePhone(phone), rel))
            return r
        }

        // "Hameed mera mamo hai" — relation statement (number ke bina)
        val relOnly = relationOf(low)
        if (relOnly != null && Regex("(?:hai|hain|ha|he|है|हैं|ہے|ہیں)\\s*$").containsMatchIn(low) && !low.contains("number") && !low.contains("call") && !NUMBER_WORDS.any { words.contains(it) } && !low.contains("کال") && !low.contains("कॉल") && !saveHit) {
            val nm = Regex("^\\s*([\\p{L}\\p{M}]+)\\s+").find(low)?.groupValues?.get(1) ?: ""
            if (nm.isNotBlank() && REL_MAP[nm] == null) {
                val contacts = loadContacts(ctx)
                val hits = contacts.filter { it.name.lowercase().contains(nm.lowercase()) }
                when {
                    hits.size == 1 -> {
                        val old = hits[0]
                        val idx = contacts.indexOfFirst { it.name == old.name && it.phone == old.phone }
                        if (idx >= 0) {
                            contacts[idx] = BrainContact(old.name, old.phone, relOnly)
                            saveContacts(ctx, contacts)
                            r.text = "✅ ${old.name} ka relation save ho gaya: *$relOnly*"
                            return r
                        }
                    }
                    hits.isEmpty() -> {
                        r.text = "👤 $nm — $relOnly, samajh gaya! Lekin uska number saved nahi hai.\n\"save $nm <number> $relOnly\" likho to poora contact ban jayega."
                        return r
                    }
                }
            }
        }

        // ---------- 9) PASSWORD safety: "ye password save karo" → confirm ----------
        val pwBare = Regex("(?:password|passcode|पासवर्ड)\\s*(?:ye|yeh|this|hai|ha|it is|is)?\\s*[:：=]\\s*(\\S.{0,49}?)\\s*$").find(low)
        if (pwBare != null) {
            val value = pwBare.groupValues[1].trim()
            if (value.length >= 3) {
                val label = Regex("^([\\p{L}\\p{M} ]{2,20}?)\\s*(?:ka|ki|ke)?\\s*(?:password|pass|pin|passcode|पासवर्ड)").find(low)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it !in listOf("ye", "yeh", "this", "is", "app") } ?: "app"
                pending = Pending().apply { kind = "pw"; this.label = label; this.value = value }
                r.text = "🔒 Password mila. Securely save karun future ke liye? (Private storage — sirf isi phone mein.)\n[haan = save / nahi = use once]"
                return r
            }
        }
        val pwSaveStmt = Regex("^(?:ye|yeh|this|is|ये|यह)\\s*(?:hi\\s+)?([\\p{L}\\p{M} ]{2,20}?)?\\s*(?:ka|ki|ke)?\\s*(?:password|pass|pin|passcode|पासवर्ड)\\s*(?:ye|yeh|this|is|hai|ha)?\\s*(?:save|store|yaad|rakho|rakh|lo|lena|سیو|सेव)?\\s*(?:karo|kro|krdo|kardo|kar do|do|de|dy)?\\s*(?:hai|ha)?\\s*(\\S.{0,49}?)\\s*$").find(low)
        if (pwSaveStmt != null) {
            val value = pwSaveStmt.groupValues[2].trim()
            if (value.length >= 3) {
                val label = pwSaveStmt.groupValues[1].trim().takeIf { it.isNotBlank() && it !in listOf("ye", "yeh", "this", "is", "app") } ?: "app"
                pending = Pending().apply { kind = "pw"; this.label = label; this.value = value }
                r.text = "🔒 Password mila. Securely save karun? (Private — sirf isi phone mein rehta hai.)\n[haan / nahi]"
                return r
            }
        }

        // ---------- 10) CALL by name/relation (ya seedha number) ----------
        val target = beforeVerbTarget(low)
        if (target != null && !lockWord && CLOSE_WORDS.none { words.contains(it) }) {
            val dphone = extractPhone(target)
            if (dphone != null) {
                r.text = "📞 Number mil gaya: ${normalizePhone(dphone)} — call laga raha hoon."
                r.actions.add(BrainAction("call", normalizePhone(dphone)))
                r.buttons.add(BrainButton("🔴 End Call", "endcall"))
                return r
            }
            val contacts = loadContacts(ctx)
            val hits = resolve(contacts, target)
            when {
                hits.isEmpty() -> {
                    r.text = t(lang,
                        "\"$target\" is not saved yet. Say: \"save $target <number>\"",
                        "\"$target\" saved nahi mila.\nSave karo: \"save $target <number>\" (relation optional: bhai/behen/mamo...)\nSaved list: \"contacts\"")
                }
                hits.size == 1 -> {
                    val c = hits[0]
                    val rel = if (c.relation != null) " (${c.relation})" else ""
                    r.text = "📞 ${c.name}$rel ko call laga raha hoon: ${c.phone}"
                    r.actions.add(BrainAction("call", c.phone))
                    r.buttons.add(BrainButton("🔴 End Call", "endcall"))
                }
                else -> {
                    pending = Pending().apply { kind = "choice"; this.contacts = hits; purpose = "call" }
                    val names = hits.mapIndexed { i, c -> "${i + 1}. ${c.name}" + (if (c.relation != null) " (${c.relation})" else "") }
                    r.text = "🤔 ${hits.size} log mile:\n${names.joinToString("\n")}\nKis ko call karna hai? Naam ya number bolo."
                }
            }
            return r
        }

        // ---------- 11) DELETE contact ----------
        val delHit = DELETE_WORDS.any { words.contains(it) }
        if (delHit && !low.contains("call") && (words.contains("contact") || NUMBER_WORDS.any { words.contains(it) } || relationOf(low) != null || deleteTargetName(low) != null)) {
            val nm = deleteTargetName(low) ?: extractName(words.filterNot { DELETE_WORDS.contains(it) })
            val contacts = loadContacts(ctx)
            val hits = if (nm.isBlank()) emptyList() else resolve(contacts, nm)
            when {
                hits.isEmpty() -> { r.text = "❓ \"${if (nm.isBlank()) "?" else nm}\" saved nahi mila."; return r }
                hits.size == 1 -> {
                    pending = Pending().apply { kind = "delete"; contact = hits[0] }
                    r.text = "🗑️ ${hits[0].name} — ${hits[0].phone} ko delete karun? (Recycle bin mein jayega — wapas la sakte ho)\n[haan / nahi]"
                }
                else -> {
                    val names = hits.mapIndexed { i, c -> "${i + 1}. ${c.name}" + (if (c.relation != null) " (${c.relation})" else "") }
                    r.text = "🤔 ${hits.size} milay:\n${names.joinToString("\n")}\nKis ko delete karna hai? Naam bolo."
                }
            }
            return r
        }

        // ---------- 12) RESTORE / recycle bin ----------
        val binHit = RESTORE_WORDS.any { words.contains(it) } || low.contains("restore") || low.contains("wapas")
        val binShow = (low.contains("deleted") || low.contains("delete")) && (words.contains("contacts") || low.contains("list") || low.contains("dikhao") || low.contains("show") || low.contains("batao"))
        if (binShow && !delHit) {
            val bin = loadBin(ctx)
            if (bin.isEmpty()) { r.text = "📭 Recycle bin khali hai — koi deleted contact nahi."; return r }
            r.text = "🗑️ Deleted contacts (recycle bin):\n" + bin.mapIndexed { i, c -> "${i + 1}. ${c.name}" + (if (c.relation != null) " (${c.relation})" else "") + " — ${c.phone}" }.joinToString("\n") + "\nRestore: \"${bin[0].name} ko wapas lao\""
            return r
        }
        if (binHit && !low.contains("call")) {
            val nm = extractName(words.filterNot { RESTORE_WORDS.contains(it) || it == "ko" })
            val bin = loadBin(ctx)
            if (bin.isEmpty()) { r.text = "📭 Recycle bin khali hai."; return r }
            if (nm.isBlank()) {
                r.text = "🗑️ Recycle bin mein ye hain:\n" + bin.mapIndexed { i, c -> "${i + 1}. ${c.name} — ${c.phone}" }.joinToString("\n") + "\nNaam bolo to wapas la dunga."
                return r
            }
            val hits = bin.filter { it.name.lowercase().contains(nm.lowercase()) }
            if (hits.isEmpty()) { r.text = "❓ Recycle bin mein \"$nm\" nahi hai."; return r }
            val c = hits[0]
            val newBin = bin.filterNot { it.name == c.name && it.phone == c.phone }
            saveBin(ctx, newBin)
            val list = loadContacts(ctx); list.add(c); saveContacts(ctx, list)
            r.text = "✅ ${c.name} wapas restore ho gaya: ${c.phone}"
            return r
        }

        // ---------- 13) CONTACT EDIT (number change) ----------
        val editHit = EDIT_WORDS.any { words.contains(it) } || low.contains("change") || low.contains("update")
        if (editHit && (NUMBER_WORDS.any { words.contains(it) } || low.contains("number"))) {
            val newPhone = extractPhone(low)
            val nm = Regex("^(.+?)\\s+(?:ka|ki)\\s+(?:number|nomber|nmb|नंबर|نمبر)").find(low)?.groupValues?.get(1)?.trim()
                ?: extractName(words.filterNot { EDIT_WORDS.contains(it) || NUMBER_WORDS.contains(it) || it == "ka" || it == "ki" })
            if (nm.isNotBlank()) {
                val contacts = loadContacts(ctx)
                val hits = resolve(contacts, nm)
                when {
                    hits.isEmpty() -> { r.text = "❓ \"$nm\" saved nahi mila."; return r }
                    hits.size == 1 -> {
                        val c = hits[0]
                        if (newPhone != null && c.phone != normalizePhone(newPhone)) {
                            val idx = contacts.indexOfFirst { it.name == c.name && it.phone == c.phone }
                            if (idx >= 0) {
                                contacts[idx] = BrainContact(c.name, normalizePhone(newPhone), c.relation)
                                saveContacts(ctx, contacts)
                                r.text = "✅ ${c.name} ka number update ho gaya: ${normalizePhone(newPhone)}"
                                return r
                            }
                        }
                        pending = Pending().apply { kind = "editnum"; contact = c }
                        r.text = "✏️ ${c.name} ka purana number: ${c.phone}\nNaya number likho (ya \"nahi\" se cancel)."
                        return r
                    }
                    else -> {
                        val names = hits.mapIndexed { i, c -> "${i + 1}. ${c.name}" + (if (c.relation != null) " (${c.relation})" else "") }
                        r.text = "🤔 ${hits.size} milay:\n${names.joinToString("\n")}\nKis ka number badalna hai? Naam bolo."
                        return r
                    }
                }
            }
        }

        // ---------- 14) TELL number ----------
        if ((NUMBER_WORDS.any { low.contains(it) }) && TELL_WORDS.any { words.contains(it) }) {
            val m1 = Regex("^(.+?)\\s+(?:ka|ki)\\s+(?:number|nomber|nmb|नंबर|نمبر)").find(low)?.groupValues?.get(1)
            val m2 = Regex("(?:tell|show|batao|bata|dikha|dikhao|बताओ|दिखाओ)\\s+(?:me\\s+)?(?:mere|meri|my)?\\s*(.+?)\\s+(?:ka|ki)?\\s*(?:number|nomber|nmb|नंबर|نمبر)").find(low)?.groupValues?.get(1)
            val m3 = Regex("(.+?)\\s+(?:number|nomber|nmb|नंबर|نمبر)").find(low)?.groupValues?.get(1)
            val t0 = (m1 ?: m2 ?: m3 ?: "").trim()
            val contacts = loadContacts(ctx)
            if (t0.isBlank()) {
                r.text = if (contacts.isEmpty()) "📭 Abhi koi contact save nahi. Bolo: \"save Ali 03001234567 bhai\""
                else "📇 Saved contacts (${contacts.size}):\n" + contacts.joinToString("\n") { "• ${it.name}" + (if (it.relation != null) " (${it.relation})" else "") + " — ${it.phone}" }
                return r
            }
            val hits = resolve(contacts, t0)
            when {
                hits.isEmpty() -> r.text = t(lang, "\"$t0\" is not saved. Say: \"save $t0 <number>\"", "\"$t0\" ka number saved nahi hai. Save karo: \"save $t0 <number> <relation>\"")
                hits.size == 1 -> {
                    val c = hits[0]
                    val rel = if (c.relation != null) " (${c.relation})" else ""
                    r.text = "📱 ${c.name}$rel ka number: ${c.phone}"
                    r.buttons.add(BrainButton("📞 Call ${c.name}", "call", c.phone))
                    r.buttons.add(BrainButton("💬 WhatsApp", "wa", c.phone))
                }
                else -> {
                    pending = Pending().apply { kind = "choice"; this.contacts = hits; purpose = "show" }
                    val names = hits.mapIndexed { i, c -> "${i + 1}. ${c.name}" + (if (c.relation != null) " (${c.relation})" else "") }
                    r.text = "🤔 ${hits.size} log mile:\n${names.joinToString("\n")}\nKis ka number chahiye? Naam ya number bolo."
                }
            }
            return r
        }

        // ---------- 15) APP LOCK open ----------
        if (low.contains("app") && lockWord && OPEN_WORDS.any { words.contains(it) } && !low.contains("phone") && !low.contains("screen")) {
            val pw = vaultGet(ctx, "app lock")
            r.actions.add(BrainAction("openapp", "app lock"))
            r.text = if (pw != null) "🔓 App Lock app khol raha hoon.\n🔑 Password (aapne save karwaya tha): $pw\n⚠️ Note: dusri app ke password box mein main khud type nahi kar sakta (Android security) — password yahan se copy kar lo."
            else "🔓 App Lock app khol raha hoon.\n⚠️ App lock ka password abhi save nahi. Bolo: \"app lock ka password <password>\" — private save ho jayega (sirf isi phone pe)."
            return r
        }

        // ---------- 16) PASSWORD save (classic "<label> ka password <value>") ----------
        val savePw = Regex("^\\s*(.{2,30}?)\\s+(?:ka|ki)\\s+(?:password|pass|pin|passcode|पासवर्ड)[\\s:：]+(\\S.{0,49}?)(?:\\s+(?:hai|ha|hain|he))*\\s*$").find(low)
        if (savePw != null) {
            val label = savePw.groupValues[1].trim()
            val value = savePw.groupValues[2].trim()
            val isAsk = TELL_WORDS.any { words.contains(it) } || low.contains("kya hai")
            if (!isAsk && label.isNotBlank() && value.length >= 3) {
                pending = Pending().apply { kind = "pw"; this.label = label; this.value = value }
                r.text = "🔒 Password mila. Securely save karun? (Private — sirf isi phone mein)\n[haan / nahi]"
                return r
            }
        }

        // ---------- 17) PASSWORD tell ----------
        if ((low.contains("password") || low.contains("passcode") || low.contains(" pin") || low.contains("पासवर्ड")) && (TELL_WORDS.any { words.contains(it) } || low.contains("kya hai") || low.contains("what"))) {
            val lm = Regex("(.{2,30}?)\\s+(?:ka|ki)\\s+(?:password|pass|pin|passcode|पासवर्ड)").find(low)?.groupValues?.get(1)
                ?: (if (low.contains("app lock")) "app lock" else null)
            val label = lm?.trim()
            if (!label.isNullOrBlank()) {
                val pw = vaultGet(ctx, label)
                r.text = if (pw != null) "🔑 $label ka password: $pw\n(Private — sirf aapke phone mein.)"
                else "❓ $label ka password saved nahi hai. Pehle bolo: \"$label ka password <password>\""
            } else {
                val all = vaultAll(ctx)
                r.text = if (all.isEmpty()) "❓ Koi password save nahi hua. Bolo: \"<kis cheez> ka password <password>\" — private save ho jayega."
                else "🔒 Saved passwords (naam): ${all.joinToString(", ")}\nKis ka chahiye? Naam bolo."
            }
            return r
        }

        // ---------- 18) OPEN app (multilingual) ----------
        val openHit = OPEN_WORDS.any { words.contains(it) } || low.startsWith("open")
        if (openHit && !lockWord && !low.contains("call") && NUMBER_WORDS.none { words.contains(it) }) {
            val openTarget = Regex("^(?:open|launch|start|khol|kholo|खोलो|कोलो|कोलो)\\s+(?:my|meri|mere|the)?\\s*(.+?)\\s*$").find(low)?.groupValues?.get(1)
                ?: Regex("^(.+?)\\s+(?:kholo|khol do|khol|chalao|chalu karo|open karo|open kar do|खोलो|खोल दो)\\s*$").find(low)?.groupValues?.get(1)
            if (openTarget != null && openTarget.isNotBlank()) {
                val ot = openTarget.trim()
                    .replace(Regex("\\s+(?:karo|kro|krdo|kardo|do|de|dy|please|na|jaldi|abhi|hai)+\\s*$"), "")
                    .replace(Regex("\\s+(?:ka|ki)\\s+$"), "").trim()
                if (ot.isNotBlank() && ot !in listOf("karo", "kro", "do", "kar") && ot !in setOf("terminal", "admin", "settings", "setting", "app")) {
                    if (ot.startsWith("http")) {
                        r.text = "🌐 Khol raha hoon: $ot"
                        r.actions.add(BrainAction("openurl", ot))
                        return r
                    }
                    r.text = t(lang, "✅ Opening $ot.", "📂 $ot khol raha hoon.")
                    r.actions.add(BrainAction("openapp", ot))
                    return r
                }
            }
        }

        // ---------- 19) IDENTITY — kis ne banaya ----------
        if (Regex("kis\\s*ne|who\\s*(?:made|created|built|build|developed|develop)|developer|creator|malik|banaya|banaua|banaiya|किसने|बनाया|کس نے|بنایا").find(low) != null && Regex("banaya|banaua|made|created|built|build|developed|developer|creator|malik|बनाया|بنایا").find(low) != null) {
            val personalQ = Regex("personal|private|family|biwi|wife|umar|age|address|phone\\s*number|number|kahan|where|reet|detail|salary|paisa|money").find(low) != null
            r.text = if (personalQ) "🤖 Mujhe Wishal Noor ne banaya hai — lekin unki personal details ke bare mein mujhe kuch pata nahi (na number, na address, na family). Ye main share nahi kar sakta."
            else "🤖 Mujhe Wishal Noor ne banaya hai. Main Auto Bot hoon — aap ka apna offline assistant."
            return r
        }
        if (Regex("wishal|noor").find(low) != null && Regex("personal|private|detail|bare mein|about").find(low) != null) {
            r.text = "🤖 Wishal Noor mere creator hain — unki personal details ke bare mein mujhe pata nahi."
            return r
        }

        // ---------- 20) TRANSFORMER download (specs suggest + approval) ----------
        if ((low.contains("transformer") || low.contains("model")) && (low.contains("download") || low.contains("downlad") || low.contains("dl") || low.contains("chahiye") || low.contains("lana"))) {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val ramGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
            val (sug, why) = when {
                ramGb < 3 -> Pair("SmolLM2-135M", "aapke phone ki RAM ${"%.1f".format(ramGb)}GB hai — sabse halka model smooth chalega")
                ramGb < 6 -> Pair("Qwen2.5-0.5B", "aapke phone ki RAM ${"%.1f".format(ramGb)}GB hai — beech ka model best balance hai")
                else -> Pair("TinyLlama-1.1B", "aapke phone ki RAM ${"%.1f".format(ramGb)}GB hai — sabse smart wala le sakta ho")
            }
            val models = ModelStore.downloaded(ctx)
            val have = models.joinToString(", ") { it.removeSuffix(".gguf") }
            r.text = "📦 Transformer download!\n\n📱 Phone specs dekh kar mera suggestion: $sug — kyunke $why.\n\nOptions:\n• SmolLM2-135M (~145MB, sabse halka)\n• Qwen2.5-0.5B (~400MB, balanced)\n• TinyLlama-1.1B (~670MB, sabse smart)" +
                (if (models.isNotEmpty()) "\n\n✅ Already downloaded: $have" else "") +
                "\n\nDownload karun? Neeche button dabao ya naam bolo (e.g. \"smollm2 download karo\")."
            try {
                val fmb = android.os.StatFs(ModelStore.dir(ctx).path).availableBytes / 1048576
                r.text += "\n💾 Free storage: " + (if (fmb >= 1024) "%.1f GB".format(fmb / 1024.0) else "$fmb MB")
            } catch (_: Exception) {}
            r.buttons.add(BrainButton("⬇️ Download $sug", "dlmodel", sug))
            return r
        }

        // ---------- 21) OLLAMA offline connect / model select ----------
        if (Regex("connect|laga|lagao|use|select").find(low) != null && (low.contains("ollama") || low.contains("offline") || low.contains("model") || low.contains("transformer")) && Regex("connect|laga|lagao").find(low) != null) {
            val models = ModelStore.downloaded(ctx)
            if (models.isEmpty()) {
                r.text = "📭 Abhi koi offline model download nahi hai.\n📱 'transformer download' bolo — main phone ke specs ke hisaab se best model suggest karunga, approval ke baad download ho jayega."
            } else {
                val named = ModelStore.presets.firstOrNull { m -> low.replace(" ", "").contains(m.name.lowercase().replace(" ", "").replace("-", "")) || m.name.lowercase().split("-")[0].let { low.contains(it.lowercase()) } }
                if (named != null && models.any { it.startsWith(named.file.removeSuffix(".gguf").split("-")[0]) }) {
                    prefs(ctx).edit().putString("brain_offline_model", named.name).apply()
                    r.text = "✅ Offline model connected: ${named.name}\n(Settings → Ollama tab se kabhi bhi change karo)"
                } else {
                    val list = models.joinToString("\n") { "• " + it.removeSuffix(".gguf") }
                    r.text = "🔌 Ye offline models aapke phone mein downloaded hain — kaunsa connect karun? Naam bolo:\n$list\n\n(Settings → Ollama tab se bhi select kar sakte ho)"
                }
            }
            return r
        }

        // ---------- 22) CONTACTS list ----------
        if (low == "contacts" || low == "contact list" || low == "my contacts" || low == "meray contacts" || low == "mere contacts" || (low == "saved contacts")) {
            val contacts = loadContacts(ctx)
            r.text = if (contacts.isEmpty()) "📭 Abhi koi contact save nahi. Bolo: \"save Ali 03001234567 bhai\""
            else "📇 Saved contacts (${contacts.size}):\n" + contacts.joinToString("\n") { "• ${it.name}" + (if (it.relation != null) " (${it.relation})" else "") + " — ${it.phone}" }
            return r
        }

        return null // handle nahi hua → AI/normal flow (fallback chain: Offline Brain → Transformer → Ollama → API)
    }

    /** delete ke liye naam target: "ahmed ko delete karo" | "delete ahmed" */
    private fun deleteTargetName(low: String): String? {
        val p1 = Regex("^(?:delete|remove|hata|hatao|hatado|ڈیلیٹ|ہٹاؤ|डिलीट|हटाओ|निकालो)\\s+(?:my|mere|meri|meray)?\\s*(.+?)\\s*(?:ko|contact|number|hai)*\\s*$").find(low)?.groupValues?.get(1)
        if (p1 != null && p1.isNotBlank() && p1 !in listOf("ye", "yeh", "this", "is", "contact", "number")) return p1.trim()
        val p2 = Regex("^(.+?)\\s+(?:ko\\s+)?(?:delete|remove|ڈیلیٹ|ہٹاؤ|डिलीट|हटाओ)\\s*(?:karo|kro|krdo|kardo|do|de|dy)?\\s*$").find(low)?.groupValues?.get(1)
        if (p2 != null && p2.isNotBlank() && p2 !in listOf("ye", "yeh", "this", "is", "contact", "number")) return p2.trim()
        return null
    }
}
