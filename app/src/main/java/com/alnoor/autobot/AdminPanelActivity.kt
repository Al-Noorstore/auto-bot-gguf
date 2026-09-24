package com.alnoor.autobot

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * 🛡️ Admin Panel — chat mein "admin" likhne se khulta hai.
 * Beginner friendly: API keys add/test/use, Ollama set, transformer models download.
 */
class AdminPanelActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var keysBox: LinearLayout
    private lateinit var modelsBox: LinearLayout
    private lateinit var ollamaInput: EditText
    private lateinit var ollamaResult: TextView

    private val bg = Color.parseColor("#141414")
    private val card = Color.parseColor("#232323")
    private val accent = Color.parseColor("#ffd60a")
    private val textC = Color.parseColor("#ececec")
    private val muted = Color.parseColor("#9b9b9b")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 50, 40, 80)
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "🛡️ Admin Panel"
            textSize = 24f; setTextColor(accent); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(title)
        root.addView(sub("API keys, Ollama, offline models — sab yahan se."))

        val back = Button(this).apply {
            text = "← Wapas chat pe"
            setOnClickListener { finish() }
        }
        style(back)
        root.addView(back)

        // ---------- Section: AI API Keys ----------
        root.addView(section("🔑 AI API Keys — koi bhi AI"))
        root.addView(sub("Neeche '+ Nai API key' se Gemini/OpenAI/Groq/any key add karo. Chat mein 'ask <sawal>' likho to active key jawab degi."))
        keysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keysBox)
        val addKey = Button(this).apply { text = "+ Nai API key add karo" }
        style(addKey)
        addKey.setOnClickListener { showAddKeyDialog() }
        root.addView(addKey)

        // ---------- Section: Ollama ----------
        root.addView(section("🖥 Offline AI — Ollama (PC ya Cloud)"))
        root.addView(sub("PC pe Ollama chal raha ho to phone aur PC ek WiFi pe rakho, PC mein 'OLLAMA_HOST=0.0.0.0 ollama serve' chalao, phir PC ka IP yahan likho."))
        ollamaInput = EditText(this).apply {
            hint = "http://192.168.1.5:11434"
            setTextColor(textC); setHintTextColor(muted); setSingleLine()
        }
        root.addView(ollamaInput)
        val ollamaBtn = Button(this).apply { text = "Check karo & save karo" }
        style(ollamaBtn)
        ollamaBtn.setOnClickListener { checkOllama() }
        root.addView(ollamaBtn)
        ollamaResult = TextView(this).apply { textSize = 13f; setTextColor(muted); setPadding(0, 8, 0, 8) }
        root.addView(ollamaResult)

        // ---------- Section: Transformer models ----------
        root.addView(section("📦 Transformer Models — offline download"))
        root.addView(sub("Chhote AI models jo app ke andar save hote hain (internet ke bina bhi rehte hain). Chat se bhi: 'transformer download 1'"))
        modelsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modelsBox)

        refreshKeys()
        refreshModels()
        loadOllama()
    }

    // ---------- UI helpers ----------
    private fun style(b: Button) {
        b.setTextColor(Color.parseColor("#111111"))
        b.setBackgroundColor(accent)
        b.setPadding(24, 20, 24, 20)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 20
        b.layoutParams = lp
    }

    private fun section(t: String): TextView = TextView(this).apply {
        text = t; textSize = 17f; setTextColor(accent); typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, 48, 0, 6)
    }

    private fun sub(t: String): TextView = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, 8)
    }

    private fun cardView(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card)
            setPadding(28, 24, 28, 24)
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 16
        c.layoutParams = lp
        return c
    }

    private fun smallBtn(t: String): Button = Button(this).apply {
        text = t; textSize = 12f; setTextColor(Color.parseColor("#111111"))
        setBackgroundColor(accent)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(8, 0, 8, 0)
        layoutParams = lp
    }

    // ---------- Keys ----------
    private fun refreshKeys() {
        keysBox.removeAllViews()
        val keys = KeyStore.load(this)
        if (keys.isEmpty()) {
            keysBox.addView(sub("Abhi koi key nahi hai. Upar '+ Nai API key' dabao."))
            return
        }
        for (k in keys) {
            val c = cardView()
            val masked = if (k.key.length > 8) k.key.take(4) + "..." + k.key.takeLast(3) else "(no key)"
            val head = TextView(this).apply {
                text = (if (k.active) "🟢 " else "⚪ ") + k.label + "  [" + k.provider + "]"
                textSize = 15f; setTextColor(textC); typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            c.addView(head)
            c.addView(sub("Key: $masked | Model: ${k.model.ifBlank { "-" }}\n${k.base}"))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val test = smallBtn("Test karo")
            val use = smallBtn(if (k.active) "Active ✓" else "Use karo")
            val del = smallBtn("Delete")
            test.setOnClickListener {
                ollamaResult.text = ""
                val pd = ProgressDialog(this).apply {
                    setMessage("Key test ho rahi hai...")
                    show()
                }
                val act = this
                thread {
                    val res = AIBrain.testKey(k)
                    runOnUiThread { pd.dismiss(); Toast.makeText(act, res, Toast.LENGTH_LONG).show() }
                }
            }
            use.setOnClickListener {
                Toast.makeText(this, KeyStore.setActive(this, k.label), Toast.LENGTH_SHORT).show()
                refreshKeys()
            }
            del.setOnClickListener {
                Toast.makeText(this, KeyStore.remove(this, k.label), Toast.LENGTH_SHORT).show()
                refreshKeys()
            }
            row.addView(test); row.addView(use); row.addView(del)
            c.addView(row)
            keysBox.addView(c)
        }
    }

    private fun showAddKeyDialog() {
        val pad = 40
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val providerLabel = TextView(this).apply { text = "Kaun sa AI?"; setTextColor(textC) }
        val spinner = Spinner(this)
        spinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, KeyStore.providers)
        val nameIn = EditText(this).apply { hint = "Naam (e.g. Meri Gemini key)"; setTextColor(textC); setHintTextColor(muted); setSingleLine() }
        val keyIn = EditText(this).apply { hint = "API key paste karo"; setTextColor(textC); setHintTextColor(muted); setSingleLine() }
        val baseIn = EditText(this).apply { hint = "URL (auto — badalna ho to)"; setTextColor(textC); setHintTextColor(muted); setSingleLine() }
        val modelIn = EditText(this).apply { hint = "Model (auto — badalna ho to)"; setTextColor(textC); setHintTextColor(muted); setSingleLine() }
        fun applyDefaults(pos: Int) {
            val p = KeyStore.providers[pos]
            baseIn.setText(KeyStore.defaultBase(p))
            modelIn.setText(KeyStore.defaultModel(p))
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) = applyDefaults(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }
        applyDefaults(0)
        box.addView(providerLabel); box.addView(spinner)
        box.addView(nameIn); box.addView(keyIn); box.addView(baseIn); box.addView(modelIn)
        AlertDialog.Builder(this)
            .setTitle("Nai API key")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val provider = spinner.selectedItem.toString()
                val label = nameIn.text.toString().trim().ifBlank { provider }
                val key = keyIn.text.toString().trim()
                if (key.isBlank() && provider != "Ollama (PC/Local)") {
                    Toast.makeText(this, "❌ Key khali hai — pehle key paste karo", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val msg = KeyStore.add(this, KeyStore.ApiKey(
                    provider, label, key,
                    baseIn.text.toString().trim().ifBlank { KeyStore.defaultBase(provider) },
                    modelIn.text.toString().trim().ifBlank { KeyStore.defaultModel(provider) }
                ))
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshKeys()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Ollama ----------
    private fun loadOllama() {
        val f = getSharedPreferences("autobot", Context.MODE_PRIVATE)
        ollamaInput.setText(f.getString("ollama_base", ""))
    }

    private fun checkOllama() {
        val base = ollamaInput.text.toString().trim()
        if (base.isBlank()) { Toast.makeText(this, "Pehle IP/URL likho", Toast.LENGTH_SHORT).show(); return }
        val k = KeyStore.ApiKey("Ollama (PC/Local)", "Ollama-$base", "", base, "llama3.2", false)
        ollamaResult.text = "Checking..."
        thread {
            val res = AIBrain.testKey(k)
            runOnUiThread {
                ollamaResult.text = res
                if (res.startsWith("✅")) {
                    getSharedPreferences("autobot", Context.MODE_PRIVATE)
                        .edit().putString("ollama_base", base).apply()
                    // Ollama key ke roop mein bhi save kar do (Use karo se active hoga)
                    val existing = KeyStore.load(this).any { it.label == "Ollama-$base" }
                    if (!existing) {
                        KeyStore.add(this, k)
                        refreshKeys()
                    }
                }
            }
        }
    }

    // ---------- Models ----------
    private fun refreshModels() {
        modelsBox.removeAllViews()
        val dl = ModelStore.downloaded(this)
        ModelStore.presets.forEachIndexed { i, m ->
            val c = cardView()
            val head = TextView(this).apply {
                text = "${i + 1}. ${m.name}  (${m.size})"
                textSize = 15f; setTextColor(textC); typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            c.addView(head)
            c.addView(sub(if (dl.contains(m.file)) "✅ Downloaded — files/models/${m.file}" else "Download nahi hua"))
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 30)
            }
            c.addView(bar)
            val btn = smallBtn(if (dl.contains(m.file)) "Delete" else "Download")
            btn.setOnClickListener {
                if (dl.contains(m.file)) {
                    Toast.makeText(this, ModelStore.delete(this, m), Toast.LENGTH_SHORT).show()
                    refreshModels()
                } else {
                    btn.isEnabled = false
                    bar.visibility = View.VISIBLE
                    thread {
                        val res = ModelStore.download(this, m) { p ->
                            val pct = Regex("(\\d+)%").find(p)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            runOnUiThread { bar.progress = pct }
                        }
                        runOnUiThread {
                            bar.visibility = View.GONE
                            btn.isEnabled = true
                            Toast.makeText(this, res, Toast.LENGTH_LONG).show()
                            refreshModels()
                        }
                    }
                }
            }
            c.addView(btn)
            modelsBox.addView(c)
        }
    }
}
