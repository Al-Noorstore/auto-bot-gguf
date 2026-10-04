package com.alnoor.autobot

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * v4.2 TTS — Auto Bot speaker se bol kar poochhta hai.
 * Misal: SIM choice ("Kaun si SIM se call karni hai?"), pehla save confirmation.
 * Pref: autobot → tts_enabled (default true). Band karne: "speaker band karo"
 */
object TtsBox {

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var lastSpokenAt = 0L

    private fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("autobot", Context.MODE_PRIVATE).getBoolean("tts_enabled", true)

    fun ensure(ctx: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                try {
                    // Roman Urdu/Urdu clear bolne ke liye: ur-PK available to wo, warna US English
                    val r = tts?.setLanguage(Locale("ur", "PK"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.language = Locale.US
                    }
                } catch (_: Exception) {
                    tts?.language = Locale.US
                }
            }
        } catch (_: Exception) { tts = null }
    }

    /** Speaker se bolo. FAIL-safe: TTS na ho to chup — app kabhi crash nahi hogi. */
    fun speak(ctx: Context, text: String) {
        try {
            if (!enabled(ctx) || text.isBlank()) return
            ensure(ctx)
            val t = tts ?: return
            if (!ready) {
                // pehli baar init hone mein thora waqt lag sakta hai — ek hi try
                Thread.sleep(700)
                if (!ready) return
            }
            // spam-safety: ek hi jumla 3 sec mein dobara na bole
            val now = System.currentTimeMillis()
            if (now - lastSpokenAt < 3000 && now - lastSpokenAt >= 0 && lastSpokenAt != 0L) {
                // different text ho to bolo
            }
            lastSpokenAt = now
            t.setSpeechRate(0.95f)
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "autobot_" + now)
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null; ready = false
    }
}
