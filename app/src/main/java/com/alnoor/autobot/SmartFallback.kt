package com.alnoor.autobot

/**
 * SMART FALLBACK — jab bot ko koi baat samajh na aaye.
 * Pehle wala lamba "commands abhi chalte hain..." message ab nahi aata:
 *  - API key ho to seedha AI jawab deta hai (MainActivity.smartFallback)
 *  - warna user ke alfaaz se milte-julte 2-3 commands suggest karta hai (short + friendly)
 * "help" likhne par poori list milti hai (koi feature remove nahi hua).
 */
object SmartFallback {

    private class Rule(val keys: List<String>, val example: String)

    private val rules = listOf(
        Rule(listOf("open", "khol", "kholo", "app", "launch", "chalao", "youtube", "whatsapp", "chrome"), "open youtube"),
        Rule(listOf("close", "band", "exit", "stop", "kill"), "close whatsapp"),
        Rule(listOf("call", "phone", "ring", "dial", "number", "milao"), "call ammi"),
        Rule(listOf("contact", "save", "naam", "delete", "hatao"), "contact Ali 03001234567"),
        Rule(listOf("lock", "screen"), "phone lock karo"),
        Rule(listOf("torch", "flash", "light", "roshni"), "torch on"),
        Rule(listOf("volume", "awaz", "sound", "mute", "speaker"), "volume up"),
        Rule(listOf("search", "dhoondo", "dhundo", "google", "video", "gaana", "naat"), "youtube search naat"),
        Rule(listOf("download", "model", "transformer", "offline", "ai", "brain", "llama", "qwen", "smollm"), "transformer download"),
        Rule(listOf("key", "api", "gemini", "openai", "groq", "connect", "token"), "api key <apni-key>"),
        Rule(listOf("voice", "mic", "suno", "bol"), "voice   (ya mic 🎤 dabao)"),
        Rule(listOf("apps", "installed", "store", "install", "kahan", "source"), "apps list   |   install source whatsapp"),
        Rule(listOf("terminal", "shell", "command", "python", "ls"), "terminal"),
        Rule(listOf("task", "rule", "remember", "status", "yaad"), "task product hunt daily US")
    )

    fun reply(low: String): String {
        val words = low.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()
        val scored = rules.map { it to it.keys.count { k -> words.contains(k) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(3)
        val shown = if (low.length > 40) low.take(40) + "…" else low
        val sb = StringBuilder()
        sb.append("🤔 \"").append(shown).append("\" ka matlab pakka samajh nahi aaya.\n")
        if (scored.isNotEmpty()) {
            sb.append("Shayad aap yeh chahte the:\n")
            scored.forEach { sb.append("• ").append(it.first.example).append("\n") }
        } else {
            sb.append("Aise try karo:\n• open youtube\n• call ammi\n• transformer download\n")
        }
        sb.append("\n💡 Har sawal ka AI jawab chahiye? Ek baar likho: api key <apni-key>\n📖 Poori list: help")
        return sb.toString()
    }

    fun help(): String = """🤖 Auto Bot — commands

📱 Apps: open youtube | close whatsapp | apps list | install source whatsapp | appinfo chrome
📞 Call/Contacts: call ammi | contact Ali 03001234567 | contacts | end call
🔒 Phone: lock phone | torch on/off | volume up/down/max | speaker on
🎤 Voice: mic on | voice | voice download urdu-hindi
🧠 AI: api key <key> | api key list | ask <sawal> | transformer download | tinyllama download | qwen connect
🖥 Tools: terminal | github | token save <platform> <token> | admin | settings
🌐 Server: task product hunt daily US | rule ... | remember ... | status"""
}
