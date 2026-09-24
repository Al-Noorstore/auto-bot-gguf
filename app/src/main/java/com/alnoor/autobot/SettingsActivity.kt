package com.alnoor.autobot

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * ⚙️ Settings — menu bar ka settings tab (v2.7).
 * 4 tabs: 🧠 Offline Brain | 🔑 API Keys | 🦙 Ollama | 🤖 Transformers
 * - Offline Brain: features card + greeting toggle
 * - API Keys: multiple keys add/select/delete/update + per-key ON toggle
 * - Ollama: downloaded offline models list + select (offline brain attach)
 * - Transformers: model list + download/delete/connect
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var tabBrain: LinearLayout
    private lateinit var tabKeys: LinearLayout
    private lateinit var tabOllama: LinearLayout
    private lateinit var tabModels: LinearLayout

    private val bg = Color.parseColor("#141414")
    private val card = Color.parseColor("#232323")
    private val accent = Color.parseColor("#ffd60a")
    private val textC = Color.parseColor("#ececec")
    private val muted = Color.parseColor("#9b9b9b")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "⚙️ Settings"
        val sv = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(28, 36, 28, 60)
        }
        sv.addView(root)
        setContentView(sv)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tabBtn(label: String, tag: String): Button {
            val b = Button(this).apply {
                text = label; setTag(tag)
                setTextColor(textC); setBackgroundColor(Color.parseColor("#1d1d1d"))
                textSize = 13f; setPadding(20, 26, 20, 26)
            }
            b.setOnClickListener { showTab(tag); }
            tabs.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return b
        }
        root.addView(tabs)
        tabBrain = tabView(); tabKeys = tabView(); tabOllama = tabView(); tabModels = tabView()
        root.addView(tabBrain); root.addView(tabKeys); root.addView(tabOllama); root.addView(tabModels)
        showTab("brain")
        buildBrainTab(); buildKeysTab(); buildOllamaTab(); buildModelsTab()
    }

    private fun tabView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 26, 0, 0)
    }

    private fun showTab(tag: String) {
        val map = mapOf("brain" to tabBrain, "keys" to tabKeys, "ollama" to tabOllama, "models" to tabModels)
        for ((k, v) in map) v.visibility = if (k == tag) View.VISIBLE else View.GONE
    }

    private fun heading(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text; setTextColor(accent); textSize = 19f
            setPadding(0, 10, 0, 14); gravity = Gravity.CENTER
        })
    }

    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(card); setPadding(28, 26, 28, 26)
    }

    private fun line(parent: LinearLayout, text: String, mutedColor: Boolean = false) {
        parent.addView(TextView(this).apply {
            this.text = text; setTextColor(if (mutedColor) muted else textC); textSize = 14f
            setPadding(0, 5, 0, 5)
        })
    }

    private fun btn(label: String, onClick: (Button) -> Unit): Button =
        Button(this).apply {
            text = label; setTextColor(Color.parseColor("#04222a"))
            setBackgroundColor(accent); textSize = 14f; setPadding(20, 26, 20, 26)
            setOnClickListener { onClick(this) }
        }

    // ---------------- TAB 1: OFFLINE BRAIN ----------------
    private fun buildBrainTab() {
        heading(tabBrain, "🧠 Offline Brain (Default)")
        val c = cardView()
        line(c, "Offline Brain wo AI hai jo bina API key, bina Ollama, bina internet — seedha phone ke andar chalta hai. Ye default brain hai.")
        line(c, "")
        line(c, "Ye kya kar sakta hai:", mutedColor = false)
        line(c, "• 📇 Contact save — naam + number + relation (bhai, behen, mamo, khala, uncle...)", mutedColor = true)
        line(c, "• 📞 Call — naam ya relation se (\"call my mamo\") + multiple match pe confirm", mutedColor = true)
        line(c, "• 🔴 Call end — \"call kat do\" ya chat ke red End Call button se", mutedColor = true)
        line(c, "• 🔒 Phone lock — \"lock my phone\" (unlock Android khud nahi deta)", mutedColor = true)
        line(c, "• 🔓 App lock + apps kholna — password vault ke saath (private)", mutedColor = true)
        line(c, "• ▶️ YouTube open/search, Google search", mutedColor = true)
        line(c, "• 📱 Transformer download — phone specs dekh kar suggest + approval", mutedColor = true)
        line(c, "• 👋 Greeting + identity (Wishal Noor ne banaya)", mutedColor = true)
        line(c, "")
        line(c, "Agar API key add ho to bada AI task pehle API key se hota hai — fail ho to dusri keys, phir Ollama offline, aur last mein offline brain khud jawab deta hai (kya karna hai bhi batata hai).", mutedColor = true)
        val sw = Switch(this).apply {
            text = "  👋 Greeting ON (chat mein assalam/hello ka jawab)"
            setTextColor(textC); textSize = 14f
            isChecked = getSharedPreferences("autobot", Context.MODE_PRIVATE).getBoolean("brain_greeting", true)
        }
        sw.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putBoolean("brain_greeting", on).apply()
            Toast.makeText(this, if (on) "Greeting ON" else "Greeting OFF", Toast.LENGTH_SHORT).show()
        }
        c.addView(sw)
        tabBrain.addView(c)
    }

    // ---------------- TAB 2: API KEYS ----------------
    private var nameIn: EditText? = null
    private var keyIn: EditText? = null

    private fun buildKeysTab() {
        heading(tabKeys, "🔑 API Keys")
        val add = cardView()
        line(add, "Nayi key add karo — AI ka naam + API key:")
        val ni = EditText(this).apply { hint = "Naam (e.g. Meri Gemini)"; setTextColor(textC); setHintTextColor(muted); textSize = 14f }
        val ki = EditText(this).apply { hint = "API key"; setTextColor(textC); setHintTextColor(muted); textSize = 14f; inputType = InputType.TYPE_CLASS_TEXT }
        add.addView(ni); add.addView(ki)
        nameIn = ni; keyIn = ki
        val b = btn("💾 Save") { saveKey() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 16
        b.layoutParams = lp
        add.addView(b)
        tabKeys.addView(add)
        tabKeys.addView(TextView(this).apply { text = "Saved keys:"; setTextColor(muted); textSize = 13f; setPadding(0, 22, 0, 8) })
        refreshKeys()
    }

    private fun saveKey() {
        val name = nameIn?.text.toString().trim()
        val key = keyIn?.text.toString().trim()
        if (name.isEmpty() || key.length < 10) { Toast.makeText(this, "Naam aur key dono likho", Toast.LENGTH_LONG).show(); return }
        val provider = detectProvider(key)
        val base = KeyStore.defaultBase(provider)
        val model = KeyStore.defaultModel(provider)
        KeyStore.add(this, KeyStore.ApiKey(provider, name, key, base, model, active = true))
        Toast.makeText(this, "✅ $name ($provider) save ho gayi — active bhi", Toast.LENGTH_LONG).show()
        nameIn?.setText(""); keyIn?.setText("")
        refreshKeys()
    }

    private fun detectProvider(key: String): String = when {
        key.startsWith("AQ.") || key.startsWith("AIza") -> "Gemini"
        key.startsWith("sk-or") -> "OpenRouter"
        key.startsWith("gsk_") -> "Groq"
        key.startsWith("sk-") -> "OpenAI"
        else -> "Custom"
    }

    private fun refreshKeys() {
        val holder = tabKeys.findViewWithTag<LinearLayout>("keysList") ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "keysList"
            tabKeys.addView(this)
        }
        holder.removeAllViews()
        val keys = KeyStore.load(this)
        if (keys.isEmpty()) { line(holder, "📭 Koi key nahi — upar se add karo.", mutedColor = true); return }
        for (k in keys) {
            val row = cardView().apply { setPadding(22, 18, 22, 18) }
            line(row, (if (k.active) "🟢" else "⚪") + " ${k.label}  [${k.provider}]" + (if (k.enabled) "" else "  (OFF)"))
            line(row, "Key: " + k.key.take(8) + "..." + k.key.takeLast(4), mutedColor = true)
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
            fun small(label: String, fn: () -> Unit) {
                val bb = Button(this).apply {
                    text = label; setTextColor(textC); setBackgroundColor(Color.parseColor("#333333"))
                    textSize = 12f; setPadding(14, 10, 14, 10)
                }
                bb.setOnClickListener { fn() }
                btns.addView(bb)
                (bb.layoutParams as LinearLayout.LayoutParams).rightMargin = 10
            }
            small(if (k.active) "✓ Active" else "Set Active") {
                KeyStore.setActive(this, k.label); refreshKeys()
            }
            small(if (k.enabled) "ON" else "OFF") {
                KeyStore.setEnabled(this, k.label, !k.enabled); refreshKeys()
            }
            small("🧪 Test") {
                Toast.makeText(this, "Testing...", Toast.LENGTH_SHORT).show()
                thread { val res = AIBrain.testKey(k); runOnUiThread { Toast.makeText(this, res, Toast.LENGTH_LONG).show() } }
            }
            small("✏️ Update") {
                val inp = EditText(this).apply { hint = "Nayi key"; setTextColor(textC); setHintTextColor(muted) }
                AlertDialog.Builder(this).setTitle("Update: ${k.label}").setView(inp)
                    .setPositiveButton("Save") { _, _ -> KeyStore.update(this, k.label, inp.text.toString()); refreshKeys() }
                    .setNegativeButton("Cancel", null).show()
            }
            small("🗑") {
                KeyStore.remove(this, k.label); refreshKeys()
            }
            row.addView(btns)
            holder.addView(row)
            (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = 14
        }
        line(holder, "💡 'ON/OFF' toggle = ye key AI tasks/fallback mein use hogi ya nahi. 'Set Active' = pehli pasand.", mutedColor = true)
    }

    // ---------------- TAB 3: OLLAMA (offline models) ----------------
    private fun buildOllamaTab() {
        heading(tabOllama, "🦙 Ollama Offline Models")
        val c = cardView()
        line(c, "Phone mein downloaded offline models — koi bhi select karo, offline brain us se attach ho jayega (jab koi API key kaam na kare).")
        line(c, "⚠️ Model ka engine chalana (llama.cpp) agle version mein aayega — filhal selection save hota hai aur brain fallback isko use karta hai.", mutedColor = true)
        line(c, "")
        tabOllama.addView(c)
        refreshOllama()
    }

    private fun refreshOllama() {
        val holder = tabOllama.findViewWithTag<LinearLayout>("ollList") ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "ollList"
            tabOllama.addView(this)
        }
        holder.removeAllViews()
        val cur = getSharedPreferences("autobot", Context.MODE_PRIVATE).getString("brain_offline_model", null)
        val models = ModelStore.downloaded(this)
        if (models.isEmpty()) { line(holder, "📭 Koi offline model download nahi hai. Transformers tab se download karo ya chat mein 'transformer download' likho.", mutedColor = true); return }
        for (m in models) {
            val name = m.removeSuffix(".gguf")
            val row = cardView().apply { setPadding(22, 18, 22, 18) }
            line(row, (if (cur != null && name.contains(cur, true)) "🟢" else "⚪") + " " + name)
            val bb = Button(this).apply {
                text = if (cur != null && name.contains(cur, true)) "✓ Connected" else "Connect"
                setTextColor(if (cur != null && name.contains(cur, true)) muted else Color.parseColor("#04222a"))
                setBackgroundColor(if (cur != null && name.contains(cur, true)) Color.parseColor("#333333") else accent)
                textSize = 13f
            }
            bb.setOnClickListener {
                getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putString("brain_offline_model", name).apply()
                refreshOllama()
            }
            row.addView(bb)
            holder.addView(row)
            (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = 14
        }
    }

    // ---------------- TAB 4: TRANSFORMERS ----------------
    private fun buildModelsTab() {
        heading(tabModels, "🤖 Transformers")
        line(tabModels, "Downloaded models Transformers/Ollama dono tabs mein dikhte hain. Yahan se download/delete:", mutedColor = true)
        for (m in ModelStore.presets) {
            val row = cardView().apply { setPadding(22, 18, 22, 18) }
            val has = ModelStore.downloaded(this).any { it.startsWith(m.file.removeSuffix(".gguf").split("-")[0]) }
            line(row, (if (has) "✅ " else "⬜ ") + "${m.name} (${m.size})")
            val bb = Button(this).apply {
                text = if (has) "🗑 Delete" else "⬇️ Download"
                setTextColor(if (has) textC else Color.parseColor("#04222a"))
                setBackgroundColor(if (has) Color.parseColor("#444444") else accent)
                textSize = 13f
            }
            bb.setOnClickListener {
                if (has) {
                    val f = java.io.File(ModelStore.dir(this), m.file)
                    if (f.exists()) f.delete()
                    Toast.makeText(this, "Deleted ${m.name}", Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this, "⬇️ ${m.name} download shuru... Settings wapas kholo progress ke liye chat dekho", Toast.LENGTH_LONG).show()
                    thread { ModelStore.download(this, m) { p -> runOnUiThread { } }; }
                    bb.text = "⏳ Downloading..."
                    bb.isEnabled = false
                }
            }
            row.addView(bb)
            tabModels.addView(row)
            (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = 14
        }
        line(tabModels, "💡 Chat mein bhi bol sakte ho: 'transformer download' — brain phone ke specs ke hisaab se suggest karega.", mutedColor = true)
    }
}
