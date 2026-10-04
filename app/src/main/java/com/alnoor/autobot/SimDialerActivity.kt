package com.alnoor.autobot

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * v4.2: SIM Dialer page — menu bar → SIM Dialer.
 * User yahan default call SIM choose karta hai:
 *   SIM 1 / SIM 2 → bot bina pooche usi se call lagata hai
 *   Har baar poochho → har call par chat mein SIM 1/SIM 2 ka sawal + speaker
 * Chat se kabhi bhi override: "call Amir sim 1" / "sim 2 default"
 */
class SimDialerActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(24), pad(20), pad(24))
        }

        val title = TextView(this).apply {
            text = "📱 SIM Dialer"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "Default call SIM choose karo — Auto Bot usi se call lagayega. Chat mein kah do to us waqt ke liye override ho jayega."
            textSize = 14f
            setPadding(0, pad(8), 0, pad(16))
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad(16), 0, pad(24))
        }

        box.addView(title)
        box.addView(sub)

        val sims = SimDialer.listSims(this)
        val s1 = sims.firstOrNull { it.slot == 0 }?.label ?: "SIM 1"
        val s2 = sims.firstOrNull { it.slot == 1 }?.label ?: "SIM 2"

        fun bigBtn(label: String, tag: String): Button =
            Button(this).apply {
                text = label
                textSize = 16f
                setPadding(pad(12), pad(14), pad(12), pad(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad(10) }
                setOnClickListener {
                    when (tag) {
                        "0" -> SimDialer.setDefaultSlot(this@SimDialerActivity, 0)
                        "1" -> SimDialer.setDefaultSlot(this@SimDialerActivity, 1)
                        else -> SimDialer.setDefaultSlot(this@SimDialerActivity, SimDialer.ASK)
                    }
                    SimDialer.noteUserChoseDefault(this@SimDialerActivity)
                    refresh()
                }
            }

        box.addView(bigBtn("📱 $s1 — hamesha SIM 1 se call", "0"))
        box.addView(bigBtn("📱 $s2 — hamesha SIM 2 se call", "1"))
        box.addView(bigBtn("🔄 Har baar poochho (chat + speaker)", "ask"))

        val info = TextView(this).apply {
            text = "💡 Chat commands:\n• sim 1 default / sim 2 default\n• sim ask (har baar poochho)\n• call Amir sim 2 (ek waqt ka override)\n• wa call Amir / wa video Amir (WhatsApp call)\n\nBot aapki aadat note karta hai — zyada-tar jis SIM se call karte ho, default banane ki offer karta hai."
            textSize = 13f
            setPadding(0, pad(20), 0, pad(8))
        }
        box.addView(info)
        box.addView(status)

        scroll.addView(box)
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        val (u0, u1) = SimDialer.usage(this)
        status.text = "Abhi: ${SimDialer.defaultLabel(this)}\n\nAapki calls ab tak: SIM 1 = $u0, SIM 2 = $u1"
    }
}
