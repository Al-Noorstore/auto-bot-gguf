package com.alnoor.autobot

import android.Manifest
import com.topjohnwu.superuser.Shell
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.telecom.TelecomManager
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CrashLogger(private val ctx: Context) : Thread.UncaughtExceptionHandler {
    private val prev = Thread.getDefaultUncaughtExceptionHandler()
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val log = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) + "\n" + e::class.java.name + ": " + e.message + "\n" +
                e.stackTraceToString().take(4000) + "\n\n"
            java.io.File(ctx.getExternalFilesDir(null), "crash_log.txt").appendText(log)
        } catch (_: Exception) {}
        prev?.uncaughtException(t, e)
    }
}

class MainActivity : AppCompatActivity() {

    private val BASE = "https://auto-bot-al-noor-stores-projects.vercel.app"

    // Universal post-load fix (online external site + offline bundled copy dono pe chalta hai):
    // (1) "Native mode ON" banner ko full-height layout-breaking block bug se bachao.
    // (2) Chat history online<->offline dono taraf sync — AutoBotNative.syncHistory/getSharedHistory se.
    private val PAGE_FIX_JS = """
        (function(){
          try {
            // "Native mode ON" banner ab bilkul hide — feature (native bridge) bina banner ke bhi chalta hai
            document.querySelectorAll('body > div[style*="0ea5b7"]').forEach(function(b){ b.style.display='none'; });
            // Purchase disclaimer bar hide — iske jagah sirf jab purchase-related baat ho tab chat mein reminder
            var _disc = document.getElementById('disclaimer');
            if (_disc) _disc.style.display = 'none';
            if (!window.__abPurchaseWatch) {
              window.__abPurchaseWatch = true;
              var _msgs = document.getElementById('msgs');
              var _lastWarn = 0;
              var _rx = /\b(buy|purchase|order|kharid|khareed|checkout|payment|pay\s*now)\b/i;
              function _maybeWarn(text) {
                if (!text || !_rx.test(text)) return;
                var now = Date.now();
                if (now - _lastWarn < 15000) return;
                _lastWarn = now;
                if (!_msgs) return;
                var d = document.createElement('div');
                d.className = 'msg bot';
                d.innerHTML = '<div class="bubble" style="color:var(--muted);font-size:13px">⚠️ Purchases hamesha owner approval ke baad hoti hain.</div>';
                _msgs.appendChild(d);
                d.scrollIntoView({block:'end'});
              }
              if (_msgs) {
                new MutationObserver(function(muts){
                  muts.forEach(function(m){
                    m.addedNodes.forEach(function(n){
                      if (n.nodeType === 1) _maybeWarn(n.textContent || '');
                    });
                  });
                }).observe(_msgs, { childList: true });
              }
            }
            // v2.8: online/live page send() ko Native Offline Brain se connect karo.
            if (window.AutoBotNative && typeof window.send === 'function' && !window.__abNativeSendPatched) {
              window.__abNativeSendPatched = true;
              var _abOriginalSend = window.send;
              window.send = async function () {
                try {
                  var _inp = document.getElementById('msg');
                  var _m = _inp ? (_inp.value || '').trim() : '';
                  if (_m && AutoBotNative.handleChatCommand(_m)) {
                    window.__lastLocalMsg = _m;
                    _inp.value = '';
                    if (typeof autoGrow === 'function') autoGrow();
                    if (typeof setSend === 'function') setSend();
                    var _empty = document.getElementById('empty'); if (_empty) _empty.style.display = 'none';
                    if (typeof bubble === 'function') bubble('user', _m);
                    if (typeof typing === 'function') typing();
                    return;
                  }
                } catch (e) {}
                return _abOriginalSend.apply(this, arguments);
              };
            }
            // v3.2.1: legacy duplicate-Settings injector removed — bundled UI already adds Terminal/Native Powers/Settings once
            // v2.6 offline-brain buttons (online site pe bhi)
            if (!window.__localBotReplyEx) {
              window.__localBotReplyEx = function (t, btns) {
                var tp = document.getElementById('typing'); if (tp) tp.remove();
                var msgs = document.getElementById('msgs'); if (!msgs) return;
                var d = document.createElement('div'); d.className = 'msg bot';
                function e(x) { var z = document.createElement('div'); z.textContent = x; return z.innerHTML; }
                var av = (typeof LOGO_SVG !== 'undefined') ? LOGO_SVG : '';
                var html = '<span class="av">' + av + '</span><div class="bubble">' + e(t);
                (btns || []).forEach(function (b) {
                  var col = (b.action === 'endcall') ? '#ef4146' : '#10a37f';
                  html += '<div style="margin-top:8px"><button style="background:' + col + ';color:#fff;border:none;border-radius:20px;padding:9px 18px;font-size:14px;font-weight:600;cursor:pointer" onclick="abBtn(this)" data-action="' + e(b.action) + '" data-phone="' + e(b.phone || '') + '">' + e(b.label) + '</button></div>';
                });
                html += '</div>';
                d.innerHTML = html;
                msgs.appendChild(d);
                var v = document.getElementById('view'); if (v) v.scrollTop = v.scrollHeight;
                if (typeof chatHist !== 'undefined' && typeof saveChat === 'function') { chatHist.push({ u: window.__lastLocalMsg || '', b: t }); saveChat(); }
              };
              window.abBtn = function (el) {
                if (!window.AutoBotNative) return;
                var a = el.getAttribute('data-action'), ph = el.getAttribute('data-phone');
                if (a === 'call') AutoBotNative.callNumber(ph);
                else if (a === 'endcall') AutoBotNative.endCall();
                else if (a === 'wa') AutoBotNative.openWhatsApp(ph);
                else if (a === 'dlmodel' && AutoBotNative.downloadModel) AutoBotNative.downloadModel(ph);
                else if (a === 'dlvoice' && AutoBotNative.downloadVoiceModel) AutoBotNative.downloadVoiceModel(ph);
                el.disabled = true; el.style.opacity = '0.5';
              };
            }
            if (window.AutoBotNative && !window.__abSyncPatched) {
              window.__abSyncPatched = true;
              var origSetItem = localStorage.setItem.bind(localStorage);
              localStorage.setItem = function(k, v) {
                origSetItem(k, v);
                if (k === 'ab_chat') { try { AutoBotNative.syncHistory(v); } catch(e) {} }
              };
              if (!sessionStorage.getItem('ab_hydrated')) {
                sessionStorage.setItem('ab_hydrated', '1');
                var mineRaw = localStorage.getItem('ab_chat');
                var mineArr = mineRaw ? JSON.parse(mineRaw) : [];
                if (mineArr.length === 0) {
                  var sharedRaw = AutoBotNative.getSharedHistory();
                  var sharedArr = sharedRaw ? JSON.parse(sharedRaw) : [];
                  if (sharedArr.length > 0) { localStorage.setItem('ab_chat', JSON.stringify(sharedArr)); location.reload(); }
                }
              }
            }
            // SMART FALLBACK: server ka lamba help-message ki jagah smart jawab
            if (window.AutoBotNative && typeof window.bubble === 'function' && !window.__abBubblePatched) {
              window.__abBubblePatched = true;
              var _abOB = window.bubble;
              window.bubble = function (role, text) {
                try {
                  if (role === 'bot' && typeof text === 'string' && text.indexOf('Ye commands abhi chalte hain') >= 0 && AutoBotNative.smartFallback) {
                    var _sf = AutoBotNative.smartFallback(window.__lastLocalMsg || '');
                    if (!_sf) { return; }
                    text = _sf;
                  }
                } catch (e) {}
                return _abOB.call(this, role, text);
              };
            }
            // NEON DOWNLOAD BAR: chat mein live progress (0% -> 100%)
            window.__abBar = function (id, label, pct, err) {
              try {
                var msgs = document.getElementById('msgs'); if (!msgs) return;
                var el = document.getElementById('abbar-' + id);
                if (!el) {
                  var tp = document.getElementById('typing'); if (tp) tp.remove();
                  el = document.createElement('div'); el.id = 'abbar-' + id; el.className = 'msg bot';
                  el.innerHTML = '<div class="bubble" style="min-width:240px">' +
                    '<div class="abbar-l" style="font-size:14px;margin-bottom:8px"></div>' +
                    '<div style="background:#0b1220;border-radius:12px;height:16px;overflow:hidden;border:1px solid #1e293b">' +
                    '<div class="abbar-f" style="height:100%;width:0%;border-radius:12px;background:linear-gradient(90deg,#00f0ff,#39ff14);box-shadow:0 0 10px #39ff14,0 0 18px #00f0ff;transition:width .35s ease"></div></div>' +
                    '<div class="abbar-p" style="text-align:right;font-weight:700;font-size:13px;margin-top:6px;color:#00a896">0%</div></div>';
                  msgs.appendChild(el);
                }
                el.querySelector('.abbar-l').textContent = label;
                var f = el.querySelector('.abbar-f'), p = el.querySelector('.abbar-p');
                if (pct >= 0) { f.style.width = pct + '%'; p.textContent = pct + '%'; }
                if (err) { f.style.background = '#ff3b5c'; f.style.boxShadow = '0 0 10px #ff3b5c'; p.style.color = '#ff3b5c'; }
                var v = document.getElementById('view'); if (v) v.scrollTop = v.scrollHeight;
              } catch (e) {}
            };
            // v3.0 FIX: mic hamesha native engine se (Vosk model ho to offline, warna Google speech).
            // WebView mein Web Speech API hota hi nahi, is liye page ka micToggle replace kiya.
            try {
              if (window.AutoBotNative) {
                window.__abMicClick = function () {
                  var now = Date.now();
                  if (window.__abMicLast && now - window.__abMicLast < 700) return;
                  window.__abMicLast = now;
                  try {
                    if (window.__abVoiceOn) { AutoBotNative.stopVoice(); }
                    else { AutoBotNative.startVoice(); }
                  } catch (e) {}
                };
                window.micToggle = window.__abMicClick;
                var _mc = document.getElementById('mic');
                if (_mc) {
                  _mc.title = 'Voice input';
                  if (!_mc.__abVoicePatched) {
                    _mc.__abVoicePatched = true;
                    _mc.addEventListener('click', function (ev) {
                      ev.stopImmediatePropagation(); ev.preventDefault();
                      window.__abMicClick();
                    }, true);
                  }
                }
              }
            } catch (e) {}
          } catch (e) {}
        })();
    """
    private val REQ_CALL = 101
    private val REQ_CONTACTS = 102
    private val REQ_ENDCALL = 103
    private val REQ_MIC = 104
    private var pendingBrainSave: Pair<String, String>? = null

    private lateinit var webView: WebView
    private lateinit var nativeScreen: View
    private lateinit var inputName: EditText
    private lateinit var inputPhone: EditText
    private lateinit var statusText: TextView
    private var pendingCall = false
    private lateinit var terminalScreen: View
    // v3.0: BROWSER — multi-tab in-app WebView
    private lateinit var browserScreen: View
    private lateinit var webHost: android.widget.FrameLayout
    private lateinit var webTabsBar: LinearLayout
    private class WebTab(val id: Int, val wv: WebView)
    private val webTabs = ArrayList<WebTab>()
    private var webSeq = 0
    private var webActiveId = -1
    private lateinit var termOut: TextView
    private lateinit var termScroll: ScrollView
    private lateinit var termIn: EditText
    // v3.0: terminal TABS — har tab apna session (Termux style)
    private class TermSession(val id: Int) { val buf = StringBuilder(); var cwd: String? = null }
    private val termSessions = ArrayList<TermSession>()
    private var termSeq = 0
    private var termActiveId = -1
    private lateinit var termTabs: LinearLayout

    private fun termActive(): TermSession =
        termSessions.firstOrNull { it.id == termActiveId } ?: termSessions.firstOrNull() ?: termNewSession()

    private fun termNewSession(): TermSession {
        val sess = TermSession(++termSeq)
        sess.buf.append("Auto Bot Terminal v3.0 — sh (tab $termSeq)\n$ ")
        termSessions.add(sess)
        termActiveId = sess.id
        renderTabs()
        renderTerm()
        return sess
    }

    private fun termCloseSession(id: Int) {
        val idx = termSessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        termSessions.removeAt(idx)
        if (termSessions.isEmpty()) { termNewSession(); return }
        if (termActiveId == id) termActiveId = termSessions[maxOf(0, idx - 1)].id
        renderTabs()
        renderTerm()
    }

    private fun renderTerm() {
        val sess = termSessions.firstOrNull { it.id == termActiveId } ?: return
        termOut.text = sess.buf.toString()
        termScroll.post { termScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun renderTabs() {
        termTabs.removeAllViews()
        for (sess in termSessions) {
            val chip = LinearLayout(this)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.setPadding(14, 6, 8, 6)
            val name = TextView(this)
            name.text = "sh ${termSessions.indexOf(sess) + 1}"
            name.textSize = 13f
            name.typeface = android.graphics.Typeface.MONOSPACE
            name.setTextColor(if (sess.id == termActiveId) -0x1 else -0x555556)
            name.setOnClickListener { termActiveId = sess.id; renderTabs(); renderTerm() }
            val x = TextView(this)
            x.text = "  ✕"
            x.textSize = 13f
            x.setTextColor(-0x1c9fd0)
            x.setOnClickListener { termCloseSession(sess.id) }
            chip.addView(name)
            chip.addView(x)
            termTabs.addView(chip)
        }
    }

    // ---------- Python 3.11 engine (APK ke andar bundled) ----------
    private var pipDir: String = ""
    private var currentProject: File = File("")
    private var pendingCallPhone: String? = null
    private var pendingCallName: String? = null
    private var pendingCallKind: String = "sim" // sim | wa | wavideo


    private fun runPython(code: String, fromChat: Boolean = false) {
        appendTerm("\n>>> $code\n")
        Thread {
            var out = ""
            try {
                if (pipDir.isEmpty()) pipDir = File(getExternalFilesDir(null), "pip").absolutePath
                val wd = if (currentProject.exists()) currentProject.absolutePath else null
                out = PyEngine.runCode(applicationContext, code, wd)
            } catch (e: Exception) { out = "Python error: " + e.message }
            val res = out.trim().take(3000)
            runOnUiThread {
                appendTerm(res + "\n")
                if (fromChat) chatReply(res)
            }
        }.start()
    }

    private fun pipInstall(pkg: String, fromChat: Boolean = false) {
        appendTerm("\n$ pip install $pkg\n")
        Thread {
            var out = ""
            try {
                val target = if (currentProject.exists()) File(currentProject, "libs").absolutePath
                            else File(getExternalFilesDir(null), "pip").absolutePath
                out = PyEngine.pipInstall(applicationContext, pkg.trim(), target)
            } catch (e: Exception) { out = "pip error: " + e.message }
            val res = out.trim().take(3000)
            runOnUiThread {
                appendTerm(res + "\n")
                if (fromChat) chatReply(res)
            }
        }.start()
    }

    // v3.0.1: libsu MAIN shell (Shell.getShell) — libsu ka officially supported persistent shell.
    // Root mile to root, warna sh. Har command isi ek zinda shell mein chalti hai.
    private fun shellInit() {
        try {
            Shell.setDefaultBuilder(
                Shell.Builder.create().setTimeout(10)
            )
        } catch (_: Exception) {}
    }

    private fun mainShell(): com.topjohnwu.superuser.Shell? = try {
        Shell.getShell()
    } catch (e: Exception) { null }

    private fun homeDir(): File = File(getExternalFilesDir(null), "work").apply { mkdirs() }

    // single-quote escape for cd paths
    private fun shQuote(p: String): String = "'" + p.replace("'", "'\\''") + "'"

    // resolve tab cwd: default = app work dir
    private fun sessCwd(sess: TermSession): String = sess.cwd ?: homeDir().absolutePath
    private fun rootAvailable(): Boolean = try { Shell.isAppGrantedRoot() == true } catch (e: Exception) { false }


    /** dep list | dep install X | dep install url URL name | dep remove X */
    private fun handleDepCommand(trimmed: String, fromChat: Boolean, sess: TermSession): Boolean {
        val low = trimmed.lowercase()
        if (low != "dep" && low != "dep list" && low != "deps" && !low.startsWith("dep ")) return false
        Thread {
            val msg = try {
                when {
                    low == "dep" || low == "dep list" || low == "deps" || low == "dep help" ->
                        DepStore.listText(this)
                    low.startsWith("dep install url ") -> {
                        val rest = trimmed.substring(16).trim().split(Regex("\\s+"))
                        if (rest.size < 2) "❌ Usage: dep install url <https://...> <name>"
                        else DepStore.installFromUrl(this, rest[0], rest[1]) { p ->
                            runOnUiThread { appendTermTo(sess, p + "\n") }
                        }
                    }
                    low.startsWith("dep install ") -> {
                        val id = trimmed.substring(12).trim()
                        DepStore.install(this, id) { p ->
                            runOnUiThread { appendTermTo(sess, p + "\n") }
                        }
                    }
                    low.startsWith("dep remove ") || low.startsWith("dep uninstall ") || low.startsWith("dep delete ") -> {
                        val id = low.substringAfter(" ").substringAfter(" ").trim().ifBlank {
                            trimmed.substringAfter(" ").substringAfter(" ").trim()
                        }
                        DepStore.remove(this, id.ifBlank { trimmed.substringAfterLast(" ").trim() })
                    }
                    else -> DepStore.listText(this)
                }
            } catch (e: Exception) {
                "❌ Dep error: ${e.message}"
            }
            runOnUiThread {
                appendTermTo(sess, msg + "\n")
                if (fromChat) chatReply(msg)
            }
        }.start()
        return true
    }

    // shell engine: real Android sh, background mein bot bhi use karta hai
    private fun runShell(cmd: String, fromChat: Boolean = false, label: String = "$") {
        val sess = termActive()
        appendTermTo(sess, "\n$ $cmd\n")
        Thread {
            var out = ""
            try {
                if (cmd.trim().startsWith("py ")) { runPython(cmd.trim().substring(3).removeSurrounding("\""), fromChat); appendTermTo(sess, "$ "); return@Thread }
                if (cmd.trim().startsWith("pip install ")) { pipInstall(cmd.trim().substring(12), fromChat); appendTermTo(sess, "$ "); return@Thread }
                val depHandled = handleDepCommand(cmd.trim(), fromChat, sess)
                if (depHandled) { appendTermTo(sess, "$ "); return@Thread }
                if (cmd.trim() == "root" || cmd.trim() == "su" || cmd.trim() == "whoami") {
                    val granted = rootAvailable()
                    appendTerm(if (granted) "[ROOT] \u2705 Root MILA \u2014 ab commands root (su) shell se chalenge. Full access!" else "[ROOT] \u274C Root nahi \u2014 normal sh shell (app sandbox). Root commands nahi chalenge.")
                    return@Thread
                }
                val cwd = sessCwd(sess)
                var newCwd: String? = null
                var toRun = cmd
                val trimmed = cmd.trim()
                val isCd = trimmed == "cd" || trimmed.startsWith("cd ")
                if (trimmed == "diag") {
                    // shell health check
                    val sh = mainShell()
                    val sb = StringBuilder()
                    sb.append("== SHELL DIAG ==\n")
                    sb.append("libsu main shell: ").append(if (sh != null) "OK" else "FAIL (ProcessBuilder fallback active)").append("\n")
                    if (sh != null) {
                        val st = try { sh.status } catch (e: Exception) { -99 }
                        sb.append("shell type: ").append(if (st == com.topjohnwu.superuser.Shell.ROOT_SHELL) "ROOT (su)" else if (st == com.topjohnwu.superuser.Shell.NON_ROOT_SHELL) "non-root (sh)" else "unknown($st)").append("\n")
                        val t1 = Shell.cmd("echo test123").exec()
                        sb.append("echo test123 -> ").append(t1.out.joinToString(" ").ifBlank { "NO OUTPUT (code ${t1.code})" }).append("\n")
                        val t2 = Shell.cmd("pwd").exec()
                        sb.append("pwd -> ").append(t2.out.joinToString(" ").ifBlank { "NO OUTPUT (code ${t2.code})" }).append("\n")
                        val t3 = Shell.cmd("echo \$PATH").exec()
                        sb.append("PATH -> ").append(t3.out.joinToString(" ")).append("\n")
                    }
                    sb.append("tab cwd: ").append(cwd)
                    out = sb.toString()
                } else {
                    if (isCd) {
                        val target = trimmed.removePrefix("cd").trim().ifEmpty { homeDir().absolutePath }
                        toRun = "cd ${shQuote(target)} && echo __PWD__" + "\$(pwd)"
                    } else if (cwd != homeDir().absolutePath) {
                        toRun = "cd ${shQuote(cwd)} && { ${cmd}; }"
                    }
                    val sh = mainShell()
                    if (sh != null) {
                        try {
                            val r = Shell.cmd(DepStore.pathExport(this@MainActivity) + "; cd " + shQuote(cwd) + "; " + toRun).exec()
                            var o = r.out.joinToString("\n")
                            val e = r.err.joinToString("\n")
                            if (isCd) {
                                val m = Regex("__PWD__(.*)").findAll(o).lastOrNull()
                                if (m != null) {
                                    newCwd = m.groupValues[1]
                                    o = o.substringBefore("__PWD__").trimEnd()
                                }
                            }
                            out = buildString {
                                if (o.isNotBlank()) append(o)
                                if (e.isNotBlank()) append(if (isNotEmpty()) "\n" else "").append(e)
                                if (isEmpty()) append(if (r.isSuccess) "(no output, exit ok)" else "(exit ${r.code})")
                            }
                        } catch (e: Exception) { out = "Error: " + e.message }
                    } else {
                        val wrapped = DepStore.pathExport(this@MainActivity) + "; " + toRun
                        val p = ProcessBuilder("sh", "-c", wrapped)
                            .directory(File(cwd))
                            .redirectErrorStream(true)
                            .start()
                        val reader = BufferedReader(InputStreamReader(p.inputStream))
                        val sb = StringBuilder()
                        var line: String? = reader.readLine()
                        var count = 0
                        while (line != null && count < 500) { sb.append(line).append("\n"); line = reader.readLine(); count++ }
                        try { p.waitFor() } catch (e: Exception) {}
                        out = sb.toString().ifBlank { "(no output)" }
                        reader.close(); p.destroy()
                    }
                }
                if (isCd && newCwd != null) sess.cwd = newCwd
            } catch (e: Exception) { out = "Error: " + e.message }
            val res = out.trim().take(3000)
            runOnUiThread {
                appendTermTo(sess, res + "\n$ ")
                if (fromChat) chatReply(if (res.isEmpty() || res == "(no output, exit ok)") "✅ Command chal gaya: $cmd" else "\n$res")
            }
        }.start()
    }

    private fun appendTermTo(sess: TermSession, text: String) {
        sess.buf.append(text)
        if (sess.id == termActiveId) {
            runOnUiThread {
                termOut.text = sess.buf.toString()
                termScroll.post { termScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun appendTerm(text: String) {
        appendTermTo(termActive(), text)
    }

    /** Temporary Thinking… row — answer aate hi HTML se remove */
    private fun showThinking() {
        runOnUiThread {
            try {
                webView.evaluateJavascript(
                    "(function(){if(typeof typing==='function')typing();})()",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    private fun thinkingStatus(msg: String) {
        runOnUiThread {
            try {
                webView.evaluateJavascript(
                    "(function(){var el=document.querySelector('#typing .th-text'); if(el) el.textContent=" +
                        org.json.JSONObject.quote(msg) + "; else if(typeof typing==='function')typing();})()",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    private fun chatReply(text: String) {
        // JS bridge WebView thread par hota hai; evaluateJavascript UI thread par zaroori hai.
        runOnUiThread {
            try { webView.evaluateJavascript("window.__localBotReply(" + JSONObject.quote(text) + ")", null) }
            catch (e: Exception) { appendTerm("[chat-reply-fail]\n" + text) }
        }
    }

    // ---------- v3.0: BROWSER (multi-tab) ----------
    private fun webActive(): WebTab? = webTabs.firstOrNull { it.id == webActiveId }

    private fun showBrowser(show: Boolean) {
        browserScreen.visibility = if (show) View.VISIBLE else View.GONE
        if (show && webTabs.isEmpty()) webNewTab("https://www.google.com")
    }

    private fun webNewTab(url: String): WebTab {
        val id = ++webSeq
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = WebViewClient()
        wv.loadUrl(if (url.startsWith("http")) url else "https://$url")
        val tab = WebTab(id, wv)
        webTabs.add(tab)
        webActiveId = id
        webHost.removeAllViews()
        webHost.addView(wv, android.widget.FrameLayout.LayoutParams(-1, -1))
        webRenderTabs()
        return tab
    }

    private fun webSwitch(id: Int) {
        webActiveId = id
        webHost.removeAllViews()
        webActive()?.let { webHost.addView(it.wv, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        webRenderTabs()
    }

    private fun webCloseActive() {
        val idx = webTabs.indexOfFirst { it.id == webActiveId }
        if (idx < 0) return
        try { webTabs[idx].wv.destroy() } catch (_: Exception) {}
        webTabs.removeAt(idx)
        webHost.removeAllViews()
        if (webTabs.isEmpty()) { showBrowser(false); return }
        webSwitch(webTabs[maxOf(0, idx - 1)].id)
    }

    private fun webRenderTabs() {
        webTabsBar.removeAllViews()
        for (tab in webTabs) {
            val chip = LinearLayout(this)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.setPadding(14, 6, 8, 6)
            val name = TextView(this)
            name.text = "🌐 ${webTabs.indexOf(tab) + 1}"
            name.textSize = 13f
            name.setTextColor(if (tab.id == webActiveId) -0x1 else -0x555556)
            name.setOnClickListener { webSwitch(tab.id) }
            val x = TextView(this)
            x.text = "  ✕"
            x.textSize = 13f
            x.setTextColor(-0x1c9fd0)
            x.setOnClickListener { webActiveId = tab.id; webCloseActive() }
            chip.addView(name)
            chip.addView(x)
            webTabsBar.addView(chip)
        }
    }

    // ---------- v3.0: WHATSAPP WEB automation (browser tab mein, QR ek dafa scan) ----------
    private fun waWebTab(): WebTab? = webTabs.firstOrNull { it.wv.url?.contains("web.whatsapp.com") == true }

    private fun waJs(tab: WebTab, js: String, timeoutMs: Long = 5000): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var res = ""
        runOnUiThread { tab.wv.evaluateJavascript(js) { r -> res = r ?: ""; latch.countDown() } }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return res
    }

    private fun waReadChats(): String {
        val t = waWebTab() ?: return "❌ WhatsApp Web tab nahi khuli. Pehle 'whatsapp web' likho aur QR scan karo."
        return try {
            val js = """(function(){var items=document.querySelectorAll('#side div[role=listitem]');var out=[];for(var i=0;i<Math.min(items.length,8);i++){var t=items[i].innerText.split(String.fromCharCode(10)).slice(0,2).join(' — ');out.push((i+1)+'. '+t)}return out.join(String.fromCharCode(10))||'Chats load nahi hue — QR scan karke thora wait karo, phir dobara "wa padho" bolo'})()"""
            val raw = waJs(t, js)
            (if (raw.length >= 2) raw.substring(1, raw.length - 1) else raw)
                .replace("\\n", "\n")
        } catch (e: Exception) { "❌ Padhne mein masla: " + e.message }
    }

    private fun waSend(contact: String, message: String): String {
        val t = waWebTab() ?: return "❌ WhatsApp Web tab nahi khuli. Pehle 'whatsapp web' likho aur QR scan karo."
        val cSafe = contact.replace("\\", " ").replace("'", "").replace("\"", "").trim()
        val mSafe = message.replace("\\", " ").replace("'", "\\'").replace("\"", "\\\"")
        return try {
            waJs(t, """(function(){var b=document.querySelector('#side button[aria-label], #side span[data-icon=search]');if(!b)return 'nosearch';(b.closest('button')||b).click();return 'ok'})()""")
            Thread.sleep(700)
            val s1 = waJs(t, """(function(){var e=document.querySelector('#side div[contenteditable=true]');if(!e)return 'nobox';e.focus();e.textContent='""" + cSafe + """';e.dispatchEvent(new InputEvent('input',{bubbles:true}));return 'ok'})()""")
            if (!s1.contains("ok")) return "❌ Search box nahi mila ($s1)."
            Thread.sleep(1400)
            // Open result whose title matches contact (sirf usi user ko)
            val s2 = waJs(t, """(function(){var q='""" + cSafe.lowercase() + """';var items=document.querySelectorAll('#side div[role=listitem]');if(items.length==0)return 'noresult';for(var i=0;i<items.length;i++){var tx=(items[i].innerText||'').toLowerCase();if(tx.indexOf(q)>=0){items[i].click();return 'ok:'+i}}items[0].click();return 'ok0'})()""")
            if (s2.contains("noresult")) return "❌ Chat nahi mili: '$contact'. Naam bilkul sahi likho."
            Thread.sleep(1200)
            // Verify header / conversation title contains contact
            val hdr = waJs(t, """(function(){var h=document.querySelector('#main header')||document.querySelector('header');return (h&&h.innerText)||''})()""")
            val hdrClean = hdr.replace("\"", "").lowercase()
            if (cSafe.lowercase().length >= 2 && !hdrClean.contains(cSafe.lowercase().take(minOf(8, cSafe.length)))) {
                return "⛔ Safety: open chat header mein '$contact' confirm nahi hua — message NAHI bheja. Sahi naam se dobara try karo.\nHeader: ${hdr.take(80)}"
            }
            val s3 = waJs(t, """(function(){var e=document.querySelector('footer div[contenteditable=true]');if(!e)return 'nobox';e.focus();e.textContent='""" + mSafe + """';e.dispatchEvent(new InputEvent('input',{bubbles:true}));var b=document.querySelector('footer button[aria-label*=end], span[data-icon=send], span[data-icon="send"]');if(!b)return 'nosend';(b.closest('button')||b).click();return 'sent'})()""")
            when {
                s3.contains("sent") -> "✅ Sirf '$contact' ko message bhej diya:\n$message"
                s3.contains("nobox") -> "❌ Message box nahi mila — chat khuli nahi."
                else -> "⚠️ Message box OK lekin send button nahi mila ($s3)."
            }
        } catch (e: Exception) { "❌ Bhejne mein masla: " + e.message }
    }


    /** Contact name → phonebook number, phir WA intent; warna WA Web by name */
    private fun waMessageToPerson(nameOrPhone: String, message: String) {
        Thread {
            val q = nameOrPhone.trim()
            val looksPhone = q.replace("+", "").replace(" ", "").all { it.isDigit() } && q.length >= 8
            var result: String
            if (looksPhone) {
                result = try {
                    val url = "https://wa.me/" + q.filter { it.isDigit() } + "?text=" + java.net.URLEncoder.encode(message, "UTF-8")
                    runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    "✅ WhatsApp khula: $q — Send confirm karo."
                } catch (e: Exception) { "❌ WA open fail: ${e.message}" }
            } else {
                val matches = if (Phonebook.hasPermission(this)) Phonebook.findByName(this, q) else emptyList()
                when {
                    matches.size == 1 -> {
                        val num = matches[0].number.filter { it.isDigit() || it == '+' }
                        result = try {
                            val url = "https://wa.me/" + num.filter { it.isDigit() } + "?text=" + java.net.URLEncoder.encode(message, "UTF-8")
                            runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            "✅ ${matches[0].name} (${matches[0].number}) — WhatsApp khula, sirf inhe message.\nSend dabao."
                        } catch (e: Exception) { "❌ ${e.message}" }
                    }
                    matches.size > 1 -> {
                        result = "📇 Kai contacts mile — number choose karo:\n" +
                            matches.mapIndexed { i, e -> "${i + 1}. ${e.name} — ${e.number}" }.joinToString("\n") +
                            "\n\nPhir: wa bhejo ${matches[0].number} | $message"
                    }
                    waWebTab() != null -> result = waSend(q, message)
                    else -> result = "❌ '$q' phonebook mein nahi.\n• contacts permission / naam check\n• ya: wa bhejo +92... | message\n• ya: whatsapp web + QR, phir naam se"
                }
            }
            runOnUiThread { chatReply(result) }
        }.start()
    }


    private fun shareImageWhatsApp(path: String, phoneHint: String? = null): String {
        val f = java.io.File(path)
        if (!f.isFile) return "❌ Image nahi mili: $path"
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, packageName + ".fileprovider", f
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.whatsapp")
            }
            startActivity(send)
            "✅ WhatsApp share khula — contact choose karke image bhejo." +
                (if (!phoneHint.isNullOrBlank()) "\n(Target hint: $phoneHint)" else "")
        } catch (e: Exception) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, packageName + ".fileprovider", f
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Image bhejo"))
                "✅ Share sheet khuli (WhatsApp choose karo)."
            } catch (e2: Exception) {
                "❌ Image share fail: ${e2.message}"
            }
        }
    }

    // ---------- v3.0: NOTIFY numbers (WhatsApp pe notification) ----------
    private fun notifyList(): ArrayList<String> {
        val out = ArrayList<String>()
        try { val a = org.json.JSONArray(getSharedPreferences("autobot", Context.MODE_PRIVATE).getString("bot_notify_numbers", "[]")); for (i in 0 until a.length()) out.add(a.getString(i)) } catch (_: Exception) {}
        return out
    }
    private fun notifySave(list: ArrayList<String>) {
        getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putString("bot_notify_numbers", org.json.JSONArray(list).toString()).apply()
    }

    private fun showTerminal(show: Boolean) {
        terminalScreen.visibility = if (show) View.VISIBLE else View.GONE
        if (show && termSessions.isEmpty()) termNewSession()
    }

    // ---------- NEON DOWNLOAD BAR helpers ----------
    private fun barJs(id: String, label: String, pct: Int, err: Boolean = false) {
        runOnUiThread {
            try { webView.evaluateJavascript("window.__abBar&&window.__abBar(" + JSONObject.quote(id) + "," + JSONObject.quote(label) + "," + pct + "," + err + ")", null) } catch (_: Exception) {}
        }
    }

    private fun voiceDownloadWithBar(m: SpeechEngine.VoiceModel) {
        val id = "v" + System.currentTimeMillis()
        val label = "⬇️ ${m.display} (${m.size})"
        barJs(id, label, 0)
        var last = -1
        SpeechEngine.pctListener = { p -> if (p != last) { last = p; barJs(id, label, p.coerceAtMost(99)) } }
        Thread {
            val res = SpeechEngine.download(this, m) { p -> runOnUiThread { status(p) } }
            SpeechEngine.pctListener = null
            val ok = res.startsWith("✅")
            barJs(id, (if (ok) "✅ " else "❌ ") + m.display, if (ok) 100 else -1, !ok)
            chatReply(res)
        }.start()
    }

    private fun modelDownloadWithBar(m: ModelStore.Model) {
        val id = "m" + System.currentTimeMillis()
        val label = "⬇️ ${m.name} (${m.size})"
        barJs(id, label, 0)
        var last = -1
        ModelStore.pctListener = { p -> if (p != last) { last = p; barJs(id, label, p.coerceAtMost(99)) } }
        Thread {
            val res = ModelStore.download(this, m) { p -> appendTerm(p) }
            ModelStore.pctListener = null
            val ok = res.startsWith("✅")
            barJs(id, (if (ok) "✅ " else "❌ ") + m.name, if (ok) 100 else -1, !ok)
            appendTerm(res)
            chatReply(res)
        }.start()
    }

    // ---------- NEW: API key chat se ----------
    private fun handleApiKeyCommand(msg: String) {
        val parts = msg.trim().split(Regex("\\s+"))
        val skip = if (parts[0].lowercase() == "apikey" || parts[0].lowercase() == "api-key") 1 else 2
        val rest = parts.drop(skip)
        val sub = rest.firstOrNull()?.lowercase() ?: ""
        val keys = KeyStore.load(this)
        fun masked(k: String) = if (k.length <= 8) "****" else k.take(4) + "…" + k.takeLast(4)
        when {
            sub == "list" || sub == "keys" -> {
                chatReply(if (keys.isEmpty()) "🔑 Koi key nahi. Likho: api key <apni-key>"
                else "🔑 Saved keys:\n" + keys.joinToString("\n") { (if (it.active) "🟢 " else "⚪ ") + it.label + " [" + it.provider + "] " + masked(it.key) + (if (!it.enabled) " (OFF)" else "") } +
                        "\n\napi key use <label> | api key delete <label> | api key test")
            }
            sub == "delete" || sub == "hatao" || sub == "remove" -> {
                val lb = rest.drop(1).firstOrNull() ?: ""
                chatReply(if (keys.any { it.label == lb }) KeyStore.remove(this, lb) else "❌ Label nahi mila. 'api key list' se label dekho.")
            }
            sub == "use" || sub == "active" -> {
                val lb = rest.drop(1).firstOrNull() ?: ""
                chatReply(KeyStore.setActive(this, lb))
            }
            sub == "test" -> {
                val k = KeyStore.active(this)
                if (k == null) chatReply("❌ Koi active key nahi.") else { chatReply("🔍 Test kar raha hoon..."); Thread { chatReply("🔑 ${k.label}: " + AIBrain.testKey(k)) }.start() }
            }
            else -> {
                val keyTok = rest.firstOrNull { it.length >= 16 }
                if (keyTok == null) {
                    chatReply("🔑 Key connect karne ke liye key paste karo:\n• api key <key>   (Gemini/OpenAI/Groq/OpenRouter khud pehchan lunga)\n• ya: api key gemini <key>\n• model bhi: api key gemini 2.5 flash <key>\n• Free Gemini key: aistudio.google.com/apikey\n\nKey aur naam ek sath bhi chalega — sab pehchan lunga. Key save hote hi foran test hoti hai (real-time).")
                    return
                }
                val wordProv = rest.firstNotNullOfOrNull { w ->
                    if (w === keyTok || w.length < 3) null else KeyStore.providers.firstOrNull { p -> p.lowercase().startsWith(w.lowercase()) }
                }
                val provider = wordProv ?: when {
                    keyTok.startsWith("AIza") -> "Gemini"
                    keyTok.startsWith("sk-or-") -> "OpenRouter"
                    keyTok.startsWith("gsk_") -> "Groq"
                    keyTok.startsWith("sk-") -> "OpenAI"
                    else -> null
                }
                if (provider == null) { chatReply("❓ Provider samajh nahi aaya. Likho: api key gemini <key>\nOptions: " + KeyStore.providers.joinToString(", ")); return }
                // v3.8: model naam bhi message mein ho to pehchan lo
                // "api key gemini 2.5 flash AIza..." → model gemini-2.5-flash
                // "api key openrouter google/gemini-2.0-flash-001 sk-or-..." → model google/gemini-2.0-flash-001
                val provPrefix = provider.lowercase().replace(" ", "")
                val filler = Regex("^(key|apikey|api-key|add|karo|kro|krdo|kardo|please|hai|ye|this|is|wali|model|naam)$")
                val modelWords = rest.filter { w -> w !== keyTok && w.length >= 2 && !w.lowercase().startsWith(provPrefix.substringBefore(' ')) && !filler.containsMatchIn(w.lowercase()) }
                val model = when {
                    modelWords.any { it.contains("/") || (it.contains("-") && it.length > 6) } -> modelWords.first { it.contains("/") || (it.contains("-") && it.length > 6) }
                    modelWords.isNotEmpty() -> provPrefix.split(" ")[0] + "-" + modelWords.joinToString("-") { it.lowercase() }
                    else -> KeyStore.defaultModel(provider)
                }
                val label = provider.split(" ")[0] + "-" + keyTok.takeLast(4)
                val k = KeyStore.ApiKey(provider, label, keyTok, KeyStore.defaultBase(provider), model)
                // chat history mein poori key na rahe
                runOnUiThread { try { webView.evaluateJavascript("window.__lastLocalMsg='api key " + provider + " ****" + keyTok.takeLast(4) + "'", null) } catch (_: Exception) {} }
                chatReply(KeyStore.add(this, k) + "\n🤖 Model: $model\n🔍 Foran test kar raha hoon (real-time)...")
                Thread { chatReply("🔑 $label: " + AIBrain.testKey(k) + "\nAb koi bhi sawal likho ya 'ask <sawal>' — AI jawab dega.") }.start()
            }
        }
    }

    // ---------- NEW: apps list + install source ----------
    private fun launchableApps(): List<Pair<String, String>> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun appsListReply(filter: String): String {
        val pm = packageManager
        val all = launchableApps()
        val list = if (filter.isBlank()) all else all.filter { it.first.lowercase().contains(filter) || it.second.lowercase().contains(filter) }
        if (list.isEmpty()) return "❌ '$filter' naam ki koi app nahi mili."
        val userCount = all.count { try { (pm.getApplicationInfo(it.second, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 } catch (_: Exception) { true } }
        val head = if (filter.isBlank()) "📱 ${all.size} apps (${userCount} aapki install ki hui, ${all.size - userCount} system):\n\n" else "📱 ${list.size} match:\n\n"
        val shown = list.take(80).joinToString("\n") { "• " + it.first }
        return head + shown + (if (list.size > 80) "\n… aur ${list.size - 80}" else "") + "\n\nApp ka source: install source <naam>"
    }

    private fun installSourceReply(name: String): String {
        val pkg = findLaunchPackage(name) ?: return "❌ App nahi mili: $name"
        val pm = packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { name }
        val installer: String? = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
            else pm.getInstallerPackageName(pkg)
        } catch (_: Exception) { null }
        val src = when (installer) {
            "com.android.vending" -> "Google Play Store"
            "com.sec.android.app.samsungapps" -> "Samsung Galaxy Store"
            "com.xiaomi.mipicks", "com.xiaomi.market" -> "Xiaomi GetApps"
            "com.huawei.appmarket" -> "Huawei AppGallery"
            "com.amazon.venezia" -> "Amazon Appstore"
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.miui.packageinstaller" -> "APK file (manual / sideload)"
            null -> "Unknown (adb / system app / source chhupa hua)"
            else -> installer
        }
        val pi = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { null }
        val date = pi?.let { java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).format(java.util.Date(it.firstInstallTime)) } ?: "?"
        return "📦 $label ($pkg)\n📥 Source: $src\n🔢 Version: ${pi?.versionName ?: "?"}\n📅 Pehli install: $date"
    }

    // FIX: launcher apps se label match (Android 11+ safe) — YouTube, WhatsApp, koi bhi app
    private fun findLaunchPackage(name: String): String? {
        val n = name.trim().lowercase()
        if (n.isBlank()) return null
        if (n.contains(".") && !n.contains(" ")) return n
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val query = n.replace(Regex("\\s+"), " ").trim()
        val compact = query.replace(" ", "")
        var best: String? = null
        var bestScore = 0
        for (ri in pm.queryIntentActivities(q, 0)) {
            val lbl = ri.loadLabel(pm).toString().lowercase().trim()
            val lblC = lbl.replace(" ", "")
            val pkg = ri.activityInfo.packageName
            var score = 0
            if (lbl == query || lblC == compact) score = 100
            else if (lbl.startsWith(query) || lblC.startsWith(compact)) score = 80
            else if (lbl.contains(query) || lblC.contains(compact)) score = 60
            else if (query.length >= 3 && (query.contains(lbl) || compact.contains(lblC))) score = 40
            if (score > bestScore) { bestScore = score; best = pkg }
        }
        return if (bestScore >= 40) best else null
    }


    /** Phonebook + MemoryVault clients + OfflineBrain contacts */
    private fun resolveCallTargets(query: String): List<Pair<String, String>> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val out = linkedMapOf<String, String>() // phone -> name
        try {
            Phonebook.findByName(this, q, 12).forEach { out[it.number] = it.name }
        } catch (_: Exception) {}
        try {
            MemoryVault.findByName(this, q).forEach {
                if (it.phone.isNotBlank()) out[it.phone.filter { ch -> ch.isDigit() || ch == '+' }] = it.name
            }
        } catch (_: Exception) {}
        // relation-style: "bai" / "bhai" → all names containing bai/bhai
        val low = q.lowercase()
        if (low in listOf("bai", "bhai", "brother") || low.endsWith(" bai") || low.endsWith(" bhai")) {
            try {
                Phonebook.all(this, 200).forEach { e ->
                    val n = e.name.lowercase()
                    if (n.contains("bai") || n.contains("bhai") || n.contains("brother")) {
                        if (low == "bai" || low == "bhai" || low == "brother" || n.contains(low)) {
                            out[e.number] = e.name
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        // exact-ish: prefer names that contain full query as whole
        val list = out.map { (ph, nm) -> nm to ph }
        val exact = list.filter { it.first.equals(q, true) }
        if (exact.isNotEmpty()) return exact
        val starts = list.filter { it.first.lowercase().startsWith(q.lowercase()) }
        if (starts.size == 1) return starts
        // unique contains
        val contains = list.filter { it.first.lowercase().contains(q.lowercase()) }
        if (contains.size == 1) return contains
        return if (contains.isNotEmpty()) contains else list
    }

    private fun startSmartCall(name: String, phone: String, forcedSim: Int?) {
        // v4.2 priority: chat mein bola (forcedSim) > contact ki aadat (learned) > default setting > poochho
        val learned = SimDialer.contactSim(this, phone)
        val slot = forcedSim ?: learned ?: SimDialer.defaultSlot(this).takeIf { it >= 0 }
        if (slot != null) {
            SimDialer.noteCall(this, slot)
            SimDialer.noteContactCall(this, phone, slot)
            val src = when {
                forcedSim != null -> "(aap ne chat mein bola)"
                learned == slot -> "(aadat: $name ko aap zyada-tar isi SIM se call karte ho)"
                else -> "(default setting)"
            }
            chatReply(SimDialer.placeCall(this, phone, slot) + "\n👤 $name • $phone\n($src)")
            showSimSuggestion()
            return
        }
        // ask SIM — chat buttons + text/mic
        pendingCallPhone = phone
        pendingCallName = name
        pendingCallKind = "sim"
        val sims = SimDialer.listSims(this)
        val s1 = sims.firstOrNull { it.slot == 0 }?.label ?: "SIM 1"
        val s2 = sims.firstOrNull { it.slot == 1 }?.label ?: "SIM 2"
        TtsBox.speak(this, "$name ko kaun si SIM se call karni hai? SIM 1, ya SIM 2?")
        chatReplyEx(
            "📞 **$name**\n📱 $phone\n\nKaun si SIM se call?",
            org.json.JSONArray()
                .put(org.json.JSONObject().put("label", "📱 $s1").put("action", "simcall").put("phone", "0|$phone"))
                .put(org.json.JSONObject().put("label", "📱 $s2").put("action", "simcall").put("phone", "1|$phone"))
                .put(org.json.JSONObject().put("label", "❎ Cancel").put("action", "simcancel").put("phone", ""))
                .toString()
        )
    }

    private fun openAppByName(name: String): String {
        val raw = name.trim().lowercase()
        val n = raw.replace(Regex("\\s+"), " ").trim()
        val compact = n.replace(" ", "")
        val pkgMap = mapOf(
            "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome", "browser" to "com.android.chrome",
            "gmail" to "com.google.android.gm", "email" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps", "playstore" to "com.android.vending",
            "play store" to "com.android.vending", "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos", "camera" to "com.android.camera2",
            "facebook" to "com.facebook.katana", "instagram" to "com.instagram.android",
            "tiktok" to "com.zhiliaoapp.musically", "tik tok" to "com.zhiliaoapp.musically",
            "spotify" to "com.spotify.music", "telegram" to "org.telegram.messenger",
            "settings" to "com.android.settings",
            "notepad" to "com.miui.notes", "notes" to "com.miui.notes", "note" to "com.miui.notes",
            "note pad" to "com.miui.notes", "notebook" to "com.miui.notes"
        )
        // also try other common note packages via label search
        var pkg: String? = pkgMap[n] ?: pkgMap[compact]
        if (pkg != null && packageManager.getLaunchIntentForPackage(pkg) == null) pkg = null
        // alternate TikTok package
        if (pkg == null && (compact == "tiktok" || n == "tik tok")) {
            for (alt in listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.tiktok.lite.go")) {
                if (packageManager.getLaunchIntentForPackage(alt) != null) { pkg = alt; break }
            }
        }
        if (pkg == null) pkg = findLaunchPackage(n) ?: findLaunchPackage(compact)
        if (pkg == null && (compact.contains("note") || n.contains("note"))) {
            pkg = findLaunchPackage("notes") ?: findLaunchPackage("note") ?: findLaunchPackage("notepad")
        }
        if (pkg == null) return "❌ App nahi mili: $name. Spelling check karo ya package naam do (e.g. open com.whatsapp)."
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return "❌ App installed nahi hai: $pkg"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { name }
            "✅ Opening $label."
        } catch (e: Exception) { "❌ App open fail: " + e.message }
    }

    private fun openUrl(url: String) { try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))); } catch (e: Exception) {} }

    // ---------- v2.9: app band karna (background process + home) ----------
    private fun closeAppByName(name: String): String {
        val n = name.trim().lowercase()
        val pkgMap = mapOf(
            "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome", "browser" to "com.android.chrome",
            "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
            "facebook" to "com.facebook.katana", "instagram" to "com.instagram.android",
            "tiktok" to "com.zhiliaoapp.musically", "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger"
        )
        var pkg: String? = pkgMap[n]
        if (pkg != null && packageManager.getLaunchIntentForPackage(pkg) == null) pkg = null
        if (pkg == null) pkg = findLaunchPackage(n)
        if (pkg == null) return "❌ App nahi mili: $name"
        if (pkg == packageName) return "❌ Main khud ko band nahi kar sakta."
        return try {
            // 1) ROOT ho to asli force-stop
            val rooted = try { Shell.isAppGrantedRoot() == true } catch (_: Throwable) { false }
            if (rooted) {
                Shell.cmd("am force-stop $pkg").exec()
                return "✅ $name force-stop ho gaya (root)."
            }
            // 2) Non-root: sirf background/cached process khatam hota hai
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg)
            "✅ $name ka background process band kar diya.\nℹ️ Android bina root ke foreground app ko force-stop nahi karne deta. Agar abhi bhi chal rahi ho to App Info se 'Force stop' dabao: \"appinfo $name\" likho."
        } catch (e: Exception) { "❌ App close fail: " + e.message }
    }

    // App Info screen kholna (Force stop button wahan hota hai)
    private fun openAppInfo(name: String): String {
        val pkg = findLaunchPackage(name) ?: return "❌ App nahi mili: $name"
        return try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "⚙️ $name ki App Info khol di — 'Force stop' dabao."
        } catch (e: Exception) { "❌ App Info fail: " + e.message }
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        // v4.3: agar saved lock purana ho gaya (2 fail) → user se naya poocho
        AutoBotAccessibilityService.onLockNeedsHelpStatic = { pkg, _ ->
            runOnUiThread {
                val nm = pkg.removePrefix("name:").removePrefix("com.")
                chatReply("⚠️ Sir, apka lock change lagta hai ($nm — 2 baar try fail). Kindly Auto Bot ko dobara bata dein:\n• app lock $nm pin <naya pin>\n• app lock $nm password <naya password>\n• app lock $nm pattern <naya pattern>  ('pattern' likho grid ke liye)\nPurana bot ne yaad rakha tha, ab naya chahiye.")
            }
        }
        shellInit()
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
        // v3.7: STARTUP PRELOAD — GGUF model background mein ready ho jata hai,
        // tak ke pehla sawal 2-3 min ki silence ke bajaye foran jawab de (LITE par silently skip)
        Thread { try { GgufEngine.prepare(this) { } } catch (_: Throwable) {} }.start()
        // SAB SE PEHLE: pichle crash ka log dikha do (app crash ho to bhi next launch pe yahan aayenge)
        try {
            val lf = File(getExternalFilesDir(null), "crash_log.txt")
            if (lf.exists() && lf.length() > 0) {
                val txt = lf.readText().take(3000)
                android.app.AlertDialog.Builder(this)
                    .setTitle("⚠️ Pichla Crash Report")
                    .setMessage(txt + "\n\nYe log Auto Bot server ko bhi bhej diya gaya hai.")
                    .setPositiveButton("Theek hai") { d, _ -> d.dismiss() }
                    .setNegativeButton("Share karo") { _, _ ->
                        try {
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Auto Bot crash log:\n\n$txt") }
                            startActivity(Intent.createChooser(send, "Crash log share karo"))
                        } catch (_: Exception) {}
                    }
                    .show()
                // server ko bhi bhej do (dono URLs, jo chale)
                val savedLog = txt
                Thread {
                    for (u in listOf(BASE + "/api/crash", "https://auto-bot-srv-al-noor-stores-projects.vercel.app/api/crash")) {
                        try {
                            val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"; conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.connectTimeout = 8000; conn.readTimeout = 8000
                            val payload = org.json.JSONObject().put("log", savedLog)
                                .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " Android " + android.os.Build.VERSION.RELEASE)
                            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                            conn.responseCode
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }
                }.start()
                lf.writeText("") // ek baar dikhane ke baad log khali (naya crash fresh aayega)
            }
        } catch (_: Exception) {}
        setContentView(R.layout.activity_main)
        // Python engine yahan load NAHI karte — kam-RAM phones (jaise Redmi 9C) pe ye
        // main-thread launch crash ka sab se bara sabab tha. Ab lazy + background thread mein.

        webView = findViewById(R.id.webView)
        nativeScreen = findViewById(R.id.nativeScreen)
        inputName = findViewById(R.id.inputName)
        inputPhone = findViewById(R.id.inputPhone)
        statusText = findViewById(R.id.statusText)
        terminalScreen = findViewById(R.id.terminalScreen)
        browserScreen = findViewById(R.id.browserScreen)
        webHost = findViewById(R.id.webHost)
        webTabsBar = findViewById(R.id.webTabs)
        findViewById<Button>(R.id.btnWebAdd).setOnClickListener { webNewTab("https://www.google.com") }
        findViewById<Button>(R.id.btnWebClose).setOnClickListener { showBrowser(false) }
        findViewById<Button>(R.id.btnWebGo).setOnClickListener {
            val q = findViewById<EditText>(R.id.webIn).text.toString().trim()
            if (q.isBlank()) return@setOnClickListener
            val t = webActive()
            if (t == null) webNewTab(q) else t.wv.loadUrl(if (q.startsWith("http")) q else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
        }
        termOut = findViewById(R.id.termOut)
        termScroll = findViewById(R.id.termScroll)
        termIn = findViewById(R.id.termIn)
        findViewById<Button>(R.id.btnTermRun).setOnClickListener { runShell(termIn.text.toString().trim()); termIn.setText("") }
        termTabs = findViewById(R.id.termTabs)
        findViewById<Button>(R.id.btnTermAdd).setOnClickListener { termNewSession() }
        findViewById<Button>(R.id.btnTermCopy).setOnClickListener {
            val txt = termActive().buf.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", txt))
            Toast.makeText(this, "⧉ Terminal ka pura text copy ho gaya", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTermClear).setOnClickListener {
            val sess = termActive()
            sess.buf.setLength(0)
            sess.buf.append("$ ")
            renderTerm()
        }
        findViewById<Button>(R.id.btnTermClose).setOnClickListener { showTerminal(false) }
        appendTerm("Auto Bot Terminal v2.2 (" + PyEngine.brand + ") — real Android shell (sh)\nWorking dir: " + File(getExternalFilesDir(null), "work").absolutePath + "\nBin (deps): " + DepStore.binDir(this).absolutePath + "\nShell: ls, mkdir, cat, ps, df… | dep list | dep install busybox\n" + PyEngine.pyHint + "\nChalo koi bhi command do!\n")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            setSupportZoom(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.addJavascriptInterface(NativeBridge(), "AutoBotNative")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(PAGE_FIX_JS, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                return if (u.startsWith("tel:") || u.startsWith("https://wa.me") || u.startsWith("mailto:")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    true
                } else false
            }
            // purane WebView renderer crash → app zinda rahe
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                runOnUiThread {
                    try {
                        appendTerm("\n⚠️ WebView renderer crash hua tha — recover ho gaya.\n(Asli fix site pe ho chuka hai, site dobara load karo)\n")
                        showTerminal(true)
                    } catch (_: Exception) {}
                }
                return true
            }
        }

        findViewById<Button>(R.id.btnCloseNative).setOnClickListener { showNative(false) }
        findViewById<Button>(R.id.btnSaveContact).setOnClickListener { saveContact() }
        findViewById<Button>(R.id.btnAutoCall).setOnClickListener { autoCall() }
        findViewById<Button>(R.id.btnWhatsApp).setOnClickListener { openWhatsApp() }
        findViewById<Button>(R.id.btnShareCrash).setOnClickListener {
            try {
                val txt = File(getExternalFilesDir(null), "crash_log.txt").let { if (it.exists()) it.readText().take(4000) else "(koi crash log nahi — app sahi chal raha hai)" }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Auto Bot crash log:\n\n$txt")
                }
                startActivity(Intent.createChooser(send, "Crash log share karo"))
            } catch (e: Exception) { Toast.makeText(this, "Share fail: " + e.message, Toast.LENGTH_SHORT).show() }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadSite()
        }

        // pichle crash ka log? → terminal pe dikha do + server pe bhej do
        try {
            val logFile = File(getExternalFilesDir(null), "crash_log.txt")
            if (logFile.exists()) {
                val txt = logFile.readText().take(2500)
                appendTerm("\n⚠️ PICHLE CRASH KA LOG:\n" + txt + "\n⚠️ (ye log Auto Bot server ko bhi bheja gaya hai)\n")
                Thread {
                    try {
                        val conn = java.net.URL(BASE + "/api/crash").openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"; conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.connectTimeout = 10000; conn.readTimeout = 10000
                        val payload = org.json.JSONObject().put("log", logFile.readText().take(8000))
                            .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " (Android " + android.os.Build.VERSION.RELEASE + ")")
                        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                        val code = conn.responseCode
                        runOnUiThread { appendTerm("\n📨 Crash log server ko bhej diya (HTTP $code)\n") }
                        conn.disconnect()
                    } catch (e: Exception) {
                        runOnUiThread { appendTerm("\n❌ Crash log server nahi gaya: " + e.message + "\n") }
                    }
                }.start()
            }
        } catch (e: Exception) { appendTerm("\n(crash log read fail: " + e.message + ")\n") }
    }

    // ---------- v3.2: GitHub build/APK download helpers ----------
    private fun ghDownload(url: String, token: String): ByteArray? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 180000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode !in 200..299) { conn.disconnect(); null }
        else { val b = conn.inputStream.use { it.readBytes() }; conn.disconnect(); b }
    } catch (e: Exception) { null }

    private fun extractApks(zip: ByteArray, repo: String): List<String> {
        val out = ArrayList<String>()
        try {
            val zin = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip))
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(".apk")) {
                    val bytes = zin.readBytes()
                    val p = saveToDownloads(repo + "-" + e.name.substringAfterLast('/'), bytes)
                    if (p != null) out.add(p)
                }
                e = zin.nextEntry
            }
            zin.close()
        } catch (e: Exception) { }
        return out
    }

    private fun saveToDownloads(name: String, bytes: ByteArray): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues()
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/AutoBotBuilds")
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) null
            else { contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; "Download/AutoBotBuilds/$name" }
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "AutoBotBuilds")
            dir.mkdirs()
            val fl = java.io.File(dir, name)
            fl.writeBytes(bytes)
            "Download/AutoBotBuilds/$fl.name"
        }
    } catch (e: Exception) { null }

    // v3.1.2: UI hamesha bundled se (APK ke saath update hoti hai, website purani ho to bhi)
    // server features (tasks/contacts/chat proxy) direct CORS ke through chalte hain
    private fun loadSite() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetworkInfo
            n != null && n.isConnected
        } catch (e: Exception) { true } // permission/network fail ho to bhi site load karne ki koshish karo — crash kabhi nahi
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun runCommand(low: String, msg: String): Boolean {
        // ---------- v3.8: AI ROUTE — brain se PEHLE (taake "qwen se jawab do" download-intent mein na fas jaye) ----------
        val isKeyCmd = low.startsWith("api key") || low.startsWith("apikey") || low.startsWith("api-key") || low.startsWith("key add") || low.startsWith("api keys")
        if (!isKeyCmd) {
            val neg = Regex("\\b(mat|nahi|na|band|off|hatao)\\b").containsMatchIn(low)
            val wantApi = !neg && Regex("\\b(api|apikey|api\\s*key|online)\\b").containsMatchIn(low) && Regex("\\b(jawab|jawabo|answer|reply|baat|use)\\b").containsMatchIn(low)
            val wantQwen = (Regex("\\b(qwen|gguf|offline|local)\\b").containsMatchIn(low) && Regex("\\b(jawab|answer|reply|baat|use)\\b").containsMatchIn(low) && !neg) ||
                (Regex("\\bapi\\b").containsMatchIn(low) && neg && Regex("\\b(jawab|answer|reply|baat)\\b").containsMatchIn(low))
            val wantAuto = Regex("\\b(auto|khud)\\b").containsMatchIn(low) && Regex("\\b(route|mode|decide|jabab|jawab)\\b").containsMatchIn(low)
            when {
                wantQwen -> { getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putString("ai_route", "qwen").apply(); chatReply("🧠 Qwen mode ON — ab jawab offline Qwen se aayenge (API key ignore).\n"+ if (KeyStore.active(this) != null) "(Wapas API ke liye: api se jawab do)" else ""); return true }
                wantApi -> { getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putString("ai_route", "api").apply(); chatReply("🔑 API mode ON — ab jawab API key se aayenge.\n(Qwen ke liye: qwen se jawab do | dono ke liye: auto mode)"); return true }
                wantAuto -> { getSharedPreferences("autobot", Context.MODE_PRIVATE).edit().putString("ai_route", "auto").apply(); chatReply("🤖 Auto mode ON — API key ho to API, warna offline Qwen, warna dono try karunga."); return true }
            }
        }
        // ---------- OFFLINE BRAIN (v2.6): bina API key / bina model ke bhi ye commands chalete hain ----------
        try {
            val br = OfflineBrain.parse(this, low, msg)
            if (br != null) {
                for (a in br.actions) {
                    when (a.action) {
                        "call" -> runOnUiThread {
                            // v4.2: arg2 = "Name|sim" — sim aware smart call (default/learned/ask + buttons)
                            val parts = a.arg2.split("|")
                            val nm = parts.getOrNull(0)?.trim().orEmpty().ifBlank { a.arg }
                            val fs = parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 0..1 }
                            startSmartCall(nm, a.arg, fs)
                        }
                        "wacall" -> runOnUiThread {
                            // v4.2: WhatsApp voice/video call (arg2 = "Name|video|voice")
                            val parts = a.arg2.split("|")
                            val nm = parts.getOrNull(0)?.trim().orEmpty().ifBlank { a.arg }
                            val video = parts.getOrNull(1) == "video"
                            chatReply(SimDialer.whatsAppCall(this@MainActivity, a.arg, video) + "\n👤 $nm" + if (video) " • video call" else "")
                        }
                        "drawpattern" -> runOnUiThread {
                            // v4.3: pattern lock draw karo (arg = "1,5,9")
                            if (AutoBotAccessibilityService.isOn()) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    try {
                                        val seq = a.arg.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..9 }
                                        val r2 = AutoBotAccessibilityService.drawPattern(seq)
                                        if (r2 == "OK") chatReply("🔓 Pattern draw ho gaya.")
                                        else chatReply("⚠️ Pattern draw nahi hua ($r2) — lock screen khuli honi chahiye.")
                                    } catch (_: Exception) {}
                                }, 1500)
                            } else chatReply("⚠️ Pattern draw ke liye Accessibility ON chahiye.")
                        }
                        "typepw" -> runOnUiThread {
                            // v4.2: app lock auto-unlock — accessibility se password type karo
                            if (AutoBotAccessibilityService.isOn()) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    try {
                                        val r1 = AutoBotAccessibilityService.typeText(a.arg)
                                        if (r1 == "OK") {
                                            var tapped = false
                                            for (lbl in listOf("ok", "unlock", "submit", "done", "confirm", "تأكيد", "ठीक")) {
                                                if (AutoBotAccessibilityService.tapText(lbl) == "OK") { tapped = true; break }
                                            }
                                            if (!tapped) AutoBotAccessibilityService.tapText("enter")
                                            chatReply("🔓 App lock mein password type kar diya" + if (tapped) " + button dabaya." else " — Enter khud dabana padega.")
                                        } else chatReply("⚠️ Password box nahi mila on screen. Screen parho se dekho, ya khud type karo: " + a.arg)
                                    } catch (_: Exception) {}
                                }, 1500)
                            } else chatReply("⚠️ Password type karne ke liye Accessibility ON chahiye.\n" + a.arg + "\n(password yahan se copy kar lo)")
                        }
                        "endcall" -> runOnUiThread { endCallAction(false) }
                        "lock" -> runOnUiThread { lockPhone(false) }
                        "openapp" -> runOnUiThread { val r = openAppByName(a.arg); if (!r.startsWith("✅")) chatReply(r) }
                        "phonebook" -> runOnUiThread { brainSavePhonebook(a.arg, a.arg2) }
                        "closeapp" -> Thread { chatReply(closeAppByName(a.arg)) }.start()
                        "youtubesearch" -> runOnUiThread { openUrl("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(a.arg, "UTF-8")) }
                        "browsersearch" -> runOnUiThread { openUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(a.arg, "UTF-8")) }
                        "browsernewtabsearch" -> runOnUiThread {
                            val url = if (a.arg.isBlank()) "https://www.google.com" else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(a.arg, "UTF-8")
                            showBrowser(true); webNewTab(url)
                        }
                        "browsersametabsearch" -> runOnUiThread {
                            val url = if (a.arg.isBlank()) "https://www.google.com" else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(a.arg, "UTF-8")
                            showBrowser(true); val t = webActive(); if (t != null) t.wv.loadUrl(url) else webNewTab(url)
                        }
                        "closetab" -> runOnUiThread { if (webActive() != null) webCloseActive() else chatReply("🌐 Koi tab khuli nahi hai.") }
                        "openurl" -> runOnUiThread { openUrl(a.arg) }
                        "torch" -> runOnUiThread { torchSet(true) }
                        "torchoff" -> runOnUiThread { torchSet(false) }
                        "volup" -> runOnUiThread { volumeAdj(android.media.AudioManager.ADJUST_RAISE) }
                        "voldown" -> runOnUiThread { volumeAdj(android.media.AudioManager.ADJUST_LOWER) }
                        "volmax" -> runOnUiThread { volumeMax() }
                        "volmute" -> runOnUiThread { volumeAdj(android.media.AudioManager.ADJUST_MUTE) }
                        "speaker" -> runOnUiThread { chatReply(speakerSet(true)) }
                        "speakeroff" -> runOnUiThread { chatReply(speakerSet(false)) }
                        "dlmodel" -> {
                            val m = ModelStore.find(a.arg)
                            if (m == null) chatReply("❌ Model samajh nahi aaya: ${a.arg}")
                            else { webView.postDelayed({ modelDownloadWithBar(m) }, 350) }
                        }
                    }
                }
                chatReplyEx(br.text, br.buttonsJson())
                return true
            }
        } catch (e: Exception) { /* brain fail = normal flow */ }
        if (low == "admin" || low == "admin panel") { runOnUiThread { startActivity(Intent(this, AdminPanelActivity::class.java)) }; chatReply("🛡️ Admin Panel khul gaya — API keys, Ollama, models sab wahan."); return true }
        if (low == "settings" || low == "setting" || low == "⚙️" || low.contains("setting khol") || low.contains("settings khol")) { runOnUiThread { startActivity(Intent(this, SettingsActivity::class.java)) }; chatReply("⚙️ Settings khul gaya — Offline Brain, API Keys, Ollama, Transformers tabs wahan hain."); return true }
        if (low.startsWith("appinfo ") || low.startsWith("app info ")) { val nm = low.substringAfter("info ").trim(); runOnUiThread { chatReply(openAppInfo(nm)) }; return true }
        // ---------- NEW: help / API key / apps list / install source ----------
        if (low == "help" || low == "commands" || low == "command list" || low == "madad") { chatReply(SmartFallback.help()); return true }
        if (low.startsWith("api key") || low.startsWith("apikey") || low.startsWith("api-key") || low.startsWith("key add")) { handleApiKeyCommand(msg); return true }
        if (low == "apps" || low == "apps list" || low == "app list" || low == "my apps" || low == "meri apps" || low == "installed apps" || low.startsWith("apps list ") || low.startsWith("installed apps ")) {
            val f = low.removePrefix("installed apps").removePrefix("apps list").trim().let { if (it == "apps" || it == "my apps" || it == "meri apps" || it == "app list") "" else it }
            chatReply(appsListReply(f)); return true
        }
        run {
            val nm = Regex("^(?:install source|installed from|install source of|source of)\\s+(.+)$").find(low)?.groupValues?.get(1)
                ?: Regex("^(.+?)\\s+(?:kahan se|kahan say|kis se|kis store se|konsi store se|which store|from where)\\b.*$").find(low)?.groupValues?.get(1)?.takeIf { low.contains("install") || low.contains("download") || low.contains("store") }
            if (nm != null && nm.isNotBlank()) { chatReply(installSourceReply(nm.trim())); return true }
        }
        // ---------- v3.0: OFFLINE VOICE commands ----------
        if (low == "voice" || low == "mic" || low == "awaz" || low == "voice status" || low == "mic status" || low.contains("voice setup")) { chatReply(SpeechEngine.status(this)); return true }
        if (low.startsWith("voice download") || low.startsWith("mic download")) {
            val name = msg.substring(msg.indexOf("download") + 8).trim()
            val m = SpeechEngine.find(name)
            if (m == null) { chatReply("❌ Voice model nahi mila: '$name'. Options: ${SpeechEngine.MODELS.joinToString { it.name }}"); return true }
            runOnUiThread { voiceDownloadWithBar(m) }
            return true
        }
        if (low.startsWith("voice use") || low.startsWith("mic use") || low.startsWith("voice select")) {
            val name = when {
                low.startsWith("voice select") -> low.substringAfter("select ").trim()
                else -> low.substringAfter("use ").trim()
            }
            chatReply(SpeechEngine.setActive(this, name)); return true
        }
        if (low == "voice delete" || low.startsWith("voice delete ") || low.startsWith("mic delete")) {
            val name = msg.substring(msg.indexOf("delete") + 6).trim()
            chatReply(if (name.isBlank()) "❌ Model naam bolo: 'voice delete english' ya 'voice delete urdu-hindi'" else SpeechEngine.delete(this, name)); return true
        }
        if (low == "mic on" || low == "voice on" || low == "suno" || low == "sunno" || low == "sun" || low == "mic start" || low == "voice start") { runOnUiThread { startVoiceCommand() }; return true }
        if (low == "mic off" || low == "voice off" || low == "mic stop" || low == "voice stop" || low == "bas" || low == "chup") { runOnUiThread { voiceStopAll() }; chatReply("🎤 Voice band."); return true }
        if (low.startsWith("ask ")) {
            val q = msg.substring(4).trim()
            if (q.isBlank()) { chatReply("Sawal likho: ask <sawal>  (ya seedha sawal likho — 'ask' ki zarurat nahi)"); return true }
            aiAnswer(q); return true
        }
        if (low.startsWith("transformer")) {
            val rest = low.removePrefix("transformer").trim()
            when {
                rest.isEmpty() || rest == "list" -> appendTerm(ModelStore.list(this))
                rest.startsWith("download") -> {
                    val m = ModelStore.find(rest.removePrefix("download").trim())
                    if (m == null) { appendTerm("❌ Model samajh nahi aaya — 'transformer list' likho."); return true }
                    runOnUiThread { modelDownloadWithBar(m) }
                }
                rest.startsWith("delete") -> {
                    val m = ModelStore.find(rest.removePrefix("delete").trim())
                    if (m == null) { appendTerm("❌ Model samajh nahi aaya — 'transformer list' likho."); return true }
                    appendTerm(ModelStore.delete(this, m))
                }
                else -> appendTerm(ModelStore.list(this))
            }
            return true
        }
        // v3.5 GGUF ENGINE commands
        // ---------- v3.9: model list / switch (smol, qwen, downloaded) ----------
        if (low == "model list" || low == "models" || low == "model" || low == "offline models") {
            Thread {
                val have = GgufEngine.availableModels(this)
                val pref = GgufEngine.preferredModel(this)
                val sb = StringBuilder("📦 *Offline models*\n")
                if (have.isEmpty()) sb.append("— koi extract/download nahi. APK mein SmolLM bundled hai — 'gguf test' se extract hoga.\n")
                else have.forEachIndexed { i, (name, sz) ->
                    val mark = when {
                        pref != null && name.contains(pref.removeSuffix(".gguf"), ignoreCase = true) -> " 👉 preferred"
                        GgufEngine.isReady() && GgufEngine.status(this).contains(name) -> " 🟢 loaded"
                        else -> ""
                    }
                    sb.append("${i + 1}. ${name.removeSuffix(".gguf")} (${sz / 1048576}MB)$mark\n")
                }
                sb.append("\n💡 Switch: model use smol | model use qwen | model use 1\n")
                sb.append("💡 Auto: model auto\n")
                sb.append("💡 Download: transformer download 1 (SmolLM) | 2 (Qwen)\n")
                sb.append("\n").append(GgufEngine.suggestModel(this))
                runOnUiThread { chatReply(sb.toString()) }
            }.start()
            return true
        }
        if (low.startsWith("model use ") || low.startsWith("switch model ") || low.startsWith("model switch ") ||
            low.startsWith("use model ") || low == "smol kar do" || low == "smollm kar do" ||
            low == "qwen kar do" || low == "switch smol" || low == "switch qwen" ||
            low == "smol use karo" || low == "qwen use karo") {
            val arg = when {
                low == "smol kar do" || low == "smollm kar do" || low == "switch smol" || low == "smol use karo" -> "smol"
                low == "qwen kar do" || low == "switch qwen" || low == "qwen use karo" -> "qwen"
                low.startsWith("model use ") -> low.removePrefix("model use ").trim()
                low.startsWith("switch model ") -> low.removePrefix("switch model ").trim()
                low.startsWith("model switch ") -> low.removePrefix("model switch ").trim()
                low.startsWith("use model ") -> low.removePrefix("use model ").trim()
                else -> ""
            }
            if (arg.isBlank() || arg == "auto" || arg == "default") {
                GgufEngine.setPreferredModel(this, null)
                chatReply("🔄 Model auto — device RAM ke mutabiq choose hoga.\n" + GgufEngine.suggestModel(this))
                return true
            }
            Thread {
                // resolve name → file
                val have = GgufEngine.availableModels(this)
                val byIndex = arg.toIntOrNull()?.let { n -> have.getOrNull(n - 1)?.first }
                val q = arg.lowercase().replace(" ", "")
                val match = byIndex ?: have.firstOrNull { (n, _) ->
                    val nn = n.lowercase().removeSuffix(".gguf")
                    nn.contains(q) || q.contains(nn.take(6)) ||
                        (q.startsWith("smol") && nn.contains("smol")) ||
                        (q.startsWith("qwen") && nn.contains("qwen")) ||
                        (q.startsWith("tiny") && nn.contains("tiny"))
                }?.first
                if (match == null) {
                    // try ModelStore presets for download hint
                    val preset = ModelStore.find(arg)
                    runOnUiThread {
                        chatReply(
                            "❌ Model '$arg' phone pe nahi mila.\n" +
                                (if (preset != null) "Download: transformer download ${ModelStore.presets.indexOf(preset) + 1}\n" else "") +
                                "List: model list"
                        )
                    }
                    return@Thread
                }
                GgufEngine.setPreferredModel(this, match)
                GgufEngine.unload()
                runOnUiThread { chatReply("🔄 Preferred: $match\n⏳ Load ho raha hai...") }
                val ok = GgufEngine.prepare(this) { p -> runOnUiThread { chatReply(p) } }
                runOnUiThread {
                    chatReply(if (ok) "✅ Ab '$match' active hai — sawal likho." else "⚠️ Set hua lekin load fail — 'gguf test' chalao.\n${GgufEngine.lastError() ?: ""}")
                }
            }.start()
            return true
        }
        if (low == "model auto" || low == "auto model") {
            GgufEngine.setPreferredModel(this, null)
            chatReply("🔄 Model auto mode — RAM ke mutabiq.\n" + GgufEngine.suggestModel(this))
            return true
        }
        if (low == "api keys" || low == "api status" || low == "keys status" || low == "meri keys" || low == "key status") {
            val keys = KeyStore.load(this)
            if (keys.isEmpty()) {
                chatReply("🔑 Koi API key attach nahi.\nLikho: api key <key>\n(Free Gemini: aistudio.google.com/apikey)")
            } else {
                val sb = StringBuilder("🔑 *Attached API keys*\n")
                keys.forEach {
                    val st = when {
                        it.active && it.enabled -> "🟢 ACTIVE"
                        it.enabled -> "⚪ on"
                        else -> "⏸ off"
                    }
                    sb.append("• ${it.label} [${it.provider}] $st\n  model: ${it.model.ifBlank { "default" }}\n")
                }
                sb.append("\n💡 api key use <label> | api key test | api key delete <label>")
                chatReply(sb.toString())
            }
            return true
        }
        if (low == "gguf status" || low == "engine status" || low == "gguf" || low == "engine") {
            Thread { val st = GgufEngine.status(this); runOnUiThread { appendTerm(st) } }.start(); return true
        }
        if (low == "gguf on" || low == "engine on") { GgufEngine.setEnabled(this, true); chatReply("🧠 GGUF engine ON — offline 'ask' ab GGUF se chalega (model ho to)."); return true }
        if (low == "gguf off" || low == "engine off") { GgufEngine.setEnabled(this, false); chatReply("⚪ GGUF engine OFF — ab API keys use hongi."); return true }
        if (low == "gguf unload" || low == "engine unload") { Thread { GgufEngine.unload() }.start(); chatReply("🧠 Model memory se free hua. Agli baar 'ask' par dobara load hoga."); return true }
        // ---------- v3.9: gguf test — step-by-step self diagnosis (low-RAM aware) ----------
        if (low == "gguf test" || low == "engine test" || low == "gguf check" || low == "gguf diag") {
            chatReply("🧠 GGUF self-test shuru... (1–2 min lag sakta hai, app band mat karo)")
            Thread {
                val sb = StringBuilder("🧠 *GGUF SELF-TEST v3.9*\n")
                sb.append("1. Native lib: ${if (LlamaBridge.available) "✅ loaded" else "❌ load nahi (build/ABI masla)"}\n")
                sb.append("2. Engine: ${if (GgufEngine.enabled(this)) "✅ ON" else "⚪ OFF ('gguf on' likho)"}\n")
                sb.append("3. Model asset: ${if (GgufEngine.modelPresent(this)) "✅ maujood" else "❌ nahi (LITE: 'transformer download 2')"}\n")
                val t0 = System.currentTimeMillis()
                val ready = GgufEngine.prepare(this) { p -> runOnUiThread { chatReply(p) } }
                val prepSec = (System.currentTimeMillis() - t0) / 1000.0
                sb.append("4. Load: ${if (ready) "✅ ${"%.0f".format(prepSec)}s" else "❌ FAIL"}\n")
                if (!ready) {
                    val err = GgufEngine.lastError()
                    if (err != null) sb.append("   ↳ Error: $err\n")
                    sb.append("\n💡 Tips:\n")
                    sb.append("• Background apps band karo (free RAM 800MB+)\n")
                    sb.append("• Storage mein 500MB free rakho\n")
                    sb.append("• App force-stop karke dobara kholo\n")
                    sb.append("• Phir 'gguf test' dobara chalao\n")
                }
                if (ready) {
                    val t1 = System.currentTimeMillis()
                    val out = GgufEngine.ask(this, "Reply in one word: 2+2 is?")
                    val gs = (System.currentTimeMillis() - t1) / 1000.0
                    if (out != null) {
                        sb.append("5. Generate: ✅ ${"%.1f".format(gs)}s\n")
                        sb.append("\n💬 Sample jawab: ").append(out.substringAfter('\n').trim().take(100))
                        sb.append("\n\n✅ Engine bilkul theek — ab koi bhi sawal seedha likho.")
                    } else {
                        sb.append("5. Generate: ❌ FAIL (load hua lekin jawab nahi bana)\n")
                        val err = GgufEngine.lastError()
                        if (err != null) sb.append("   ↳ Error: $err\n")
                    }
                }
                runOnUiThread { chatReply(sb.toString()) }
            }.start()
            return true
        }
        if (low == "keys" || low == "key list") { appendTerm(KeyStore.load(this).joinToString("\n") { (if (it.active) "🟢 " else "⚪ ") + it.label + " [" + it.provider + "]" }.ifBlank { "❌ Koi key nahi — 'admin' likho aur key add karo." }); return true }
        if (low == "terminal" || low == "open terminal") { runOnUiThread { showTerminal(true) }; chatReply("🖥 Terminal khul gaya — screen pe command likho."); return true }
        if (low.contains("full storage") || low.contains("storage full") || low.contains("sab files") || low.contains("all files")) {
            runOnUiThread {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val i = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + this.getPackageName()))
                        startActivity(i)
                        chatReply("📁 All-files access ki screen khul gayi — W9 ko ON karo. Phir /sdcard ke sab files (terminal se) mil jayenge.")
                    } else chatReply("📁 Android 11 se neeche ye permission ki zaroorat nahi — /sdcard already accessible hai.")
                } catch (e: Exception) { chatReply("❌ Settings screen nahi khuli: " + e.message) }
            }
            return true
        }
        // ---------- v3.0: TOKEN VAULT (kisi bhi platform ka access token) ----------
        if (low.startsWith("token save") || low.startsWith("token add")) {
            val parts = msg.trim().split(" ", limit = 4)
            if (parts.size < 4) { chatReply("🔧 Format: token save <platform> <token>\nMisal: token save github ghp_xxx123"); return true }
            val name = parts[2]
            val value = parts.subList(3, parts.size).joinToString(" ")
            TokenVault.save(this, name, value)
            chatReply("🔑 Token save ho gaya: $name → " + TokenVault.masked(value))
            return true
        }
        if (low == "token list" || low == "tokens") {
            val m = TokenVault.list(this)
            if (m.isEmpty()) { chatReply("🔑 Koi token save nahi.\nFormat: token save <platform> <token>\nGitHub, Vercel, YouTube... jo bhi platform token deta hai."); return true }
            chatReply("🔑 Saved tokens:\n" + m.keys.sorted().joinToString("\n") { "• $it → " + TokenVault.masked(m[it] ?: "") })
            return true
        }
        if (low.startsWith("token delete") || low.startsWith("token hatao")) {
            val name = msg.trim().split(" ").lastOrNull() ?: ""
            chatReply(if (TokenVault.delete(this, name)) "🗑️ Token delete: $name" else "❌ Token nahi mila: $name")
            return true
        }

        // ---------- v3.3: ACCESSIBILITY ----------
        val accSteps = "♿ Accessibility abhi OFF hai. On karne ke steps:\n1. Phone Settings kholo\n2. Accessibility / Accessibility kholo\n3. 'Auto Bot' ya 'Auto Bot Accessibility' tap karo\n4. On kar ke Allow karo\n(Tip: menu mein ♿ Accessibility page se seedha settings khul jayega)\n\nOn hone ke baad wahi task dobara bolo."
        if (low == "accessibility" || low == "accessibility status" || low == "accessibility on" || low.startsWith("accessibility ")) {
            val on = AutoBotAccessibilityService.isOn()
            chatReply(if (on) "♿ Accessibility ON hai — Auto Bot screen padh sakta hai.\nTask do: 'screen parho'" else accSteps)
            return true
        }
        // ============================== v4.3: APP LOCK VAULT (pin / password / pattern) ==============================

        // app-name → package resolver (AppLockVault ke liye)
        val appLockResolver = { name: String ->
            val n = name.trim().lowercase()
            val m = mapOf(
                "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
                "chrome" to "com.android.chrome", "browser" to "com.android.chrome",
                "gmail" to "com.google.android.gm", "email" to "com.google.android.gm",
                "maps" to "com.google.android.apps.maps", "playstore" to "com.android.vending",
                "play store" to "com.android.vending", "photos" to "com.google.android.apps.photos",
                "gallery" to "com.google.android.apps.photos", "camera" to "com.android.camera2",
                "facebook" to "com.facebook.katana", "instagram" to "com.instagram.android",
                "tiktok" to "com.zhiliaoapp.musically", "spotify" to "com.spotify.music",
                "telegram" to "org.telegram.messenger", "settings" to "com.android.settings"
            )
            var pkg = m[n]
            if (pkg != null && packageManager.getLaunchIntentForPackage(pkg) == null) pkg = null
            pkg ?: findLaunchPackage(n) ?: if (n.startsWith("com.")) n else null ?: "name:$n"
        }

        // ---- pattern help / grid ----
        if (low == "pattern" || low == "pattern batao" || low == "pattern help" || low.contains("pattern samjhao")) {
            chatReply(AppLockVault.PATTERN_HELP)
            return true
        }
        // ---- pattern test/verify: "pattern 1 5 9" ya "pattern L" → grid dikha do ----
        if (low.startsWith("pattern ")) {
            val raw = low.substringAfter("pattern ").trim().removePrefix("karo ").trim()
            if (raw.isNotEmpty()) {
                val seq = AppLockVault.parsePattern(raw)
                if (seq == null) { chatReply("❌ Pattern samajh nahi aaya. 1-9 dots hote hain:\n" + AppLockVault.PATTERN_HELP); return true }
                chatReply("📐 Aapka pattern:\n" + AppLockVault.patternGrid(AppLockVault.patternToSecret(seq)))
                return true
            }
        }
        // ---- live pattern draw: "pattern draw karo" ----
        if (low.contains("pattern draw") || low.contains("pattern banao") || low.contains("draw pattern")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            val res = AutoBotAccessibilityService.detectLockType()
            if (res != "pattern") { chatReply("⚠️ Screen par pattern lock nahi mila (detected: $res). Pehle locked app kholo jisme pattern hai."); return true }
            val fg = AutoBotAccessibilityService.foregroundPackage()
            val entry = AppLockVault.get(this, fg) ?: AppLockVault.get(this, "app lock")
            if (entry == null || entry.type != "pattern") { chatReply("⚠️ Pattern saved nahi. Pehle: app lock <app> pattern 1 5 9"); return true }
            chatReply("🖐 Pattern draw kar raha hoon...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val r2 = AutoBotAccessibilityService.drawPattern(AppLockVault.secretToPattern(entry.secret))
                if (r2 == "OK") { AppLockVault.resetFails(this, fg); chatReply("🔓 Pattern draw ho gaya.") }
                else chatReply("❌ Pattern draw fail ($r2). Lock screen khuli honi chahiye.")
            }, 700)
            return true
        }

        // ---- app lock save/delete/list ----
        val appLockM = Regex("^app lock (.+)$").find(low)?.groupValues?.get(1)
        if (appLockM != null) {
            val rest = appLockM.trim()
            when {
                rest == "list" || rest == "list karo" || rest == "sab" -> {
                    val locks = AppLockVault.list(this)
                    if (locks.isEmpty()) chatReply("🔐 Koi app lock saved nahi.\nSave: app lock gallery pin 1234\nYa: app lock whatsapp pattern 1 5 9")
                    else chatReply("🔐 Saved app locks:\n" + locks.joinToString("\n") { "• ${it.pkg.removePrefix("name:")} — ${AppLockVault.mask(it)}" + if (it.fails > 0) " ⚠️ ${it.fails} fail" else "" })
                    return true
                }
                rest.startsWith("delete ") || rest.startsWith("hatao ") || rest.startsWith("remove ") -> {
                    val app = rest.substringAfter(" ").trim()
                    val pkg = appLockResolver(app)
                    val ok = AppLockVault.remove(this, pkg) || AppLockVault.remove(this, "name:${app.lowercase()}")
                    chatReply(if (ok) "🗑 App lock delete: ${app}\n(ye lock ab bot laga kar nahi kholta)" else "❌ '$app' ka lock saved nahi tha.")
                    return true
                }
                else -> {
                    // app lock <app> pin <digits> / password <text> / pattern <seq>
                    val m2 = Regex("^(.+?)\\s+(pin|password|pass|pattern|pin number)\\s+(.+)$").find(rest)
                    if (m2 == null) { chatReply("🔐 Format:\n• app lock gallery pin 1234\n• app lock gallery password mera123\n• app lock gallery pattern 1 5 9 (ya pattern L)\n• app lock list | app lock delete gallery\n\n" + AppLockVault.PATTERN_HELP); return true }
                    val (app, typeRaw, secretRaw) = m2.destructured
                    val type = when { typeRaw.startsWith("pin") -> "pin"; typeRaw.startsWith("pattern") -> "pattern"; else -> "password" }
                    var secret = secretRaw.trim()
                    if (type == "pattern") {
                        val seq = AppLockVault.parsePattern(secret)
                        if (seq == null) { chatReply("❌ Pattern samajh nahi aaya.\n" + AppLockVault.PATTERN_HELP); return true }
                        secret = AppLockVault.patternToSecret(seq)
                    }
                    if (type == "pin" && !Regex("^\\d{3,8}$").matches(secret)) { chatReply("❌ PIN sirf 3-8 digits hona chahiye. Tumne likha: ••••"); return true }
                    val pkg = appLockResolver(app.trim())
                    AppLockVault.set(this, pkg, type, secret)
                    OfflineBrain.vaultSave(this, "app lock", if (type == "pattern") "(pattern: $secret)" else secret)
                    chatReply("✅ ${app.trim().replaceFirstChar { it.uppercase() }} ka lock save ho gaya (${AppLockVault.typeLabel(type)}).\n🔒 Ab jab wo app khulegi, bot khud lock detect karke real PIN/password/pattern laga dega.\n⚠️ Secret kabhi chat mein wapas nahi likha jayega — safety ke liye.")
                    if (type == "pattern") chatReply("📐 Confirm karo — tumhara pattern:\n" + AppLockVault.patternGrid(secret))
                    return true
                }
            }
        }

        // ---- upgraded UNLOCK: live lock-type detect → real apply → verify → re-ask ----
        if (low == "unlock" || low == "unlock karo" || low.contains("password laga do") || low.contains("password daal do") || low.contains("password type karo") || low.contains("pin laga do") || low.contains("pin daal do") || low.contains("pattern laga do") || low.contains("lock khol do")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            val fg = AutoBotAccessibilityService.foregroundPackage()
            val entry = AppLockVault.get(this, fg) ?: AppLockVault.get(this, "app lock") ?: OfflineBrain.vaultGet(this, "app lock")?.let { pw ->
                AppLockVault.LockEntry("app lock", if (pw.startsWith("(pattern:")) "pattern" else "password", pw.removePrefix("(pattern: ").removeSuffix(")"))
            }
            if (entry == null) { chatReply("⚠️ Koi lock saved nahi. Save karo:\n• app lock gallery pin 1234\n• app lock whatsapp pattern 1 5 9\n• ya: app lock ka password <password>"); return true }
            val det = AutoBotAccessibilityService.detectLockType()
            when {
                det == "pattern" -> {
                    if (entry.type != "pattern") { chatReply("⚠️ Screen par PATTERN lock hai, tumne ${AppLockVault.typeLabel(entry.type)} save karwaya hai. Batao: app lock <app> pattern 1 5 9 (grid ke liye 'pattern' likho)"); return true }
                    chatReply("🖐 Pattern draw kar raha hoon...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val r2 = AutoBotAccessibilityService.drawPattern(AppLockVault.secretToPattern(entry.secret))
                        chatReply(if (r2 == "OK") "🔓 Pattern draw ho gaya." else "❌ Pattern draw fail ($r2)")
                    }, 500)
                }
                det == "pin" || det == "password" -> {
                    if (entry.type == "pattern") { chatReply("⚠️ Screen par ${AppLockVault.typeLabel(det)} ka box hai, tumne pattern save karwaya hai. Batao: app lock <app> ${if (det == "pin") "pin 1234" else "password xyz"}"); return true }
                    val r1 = AutoBotAccessibilityService.typeText(entry.secret)
                    if (r1 == "OK") {
                        var tapped = false
                        for (lbl in listOf("ok", "unlock", "submit", "done", "confirm", "enter")) {
                            if (AutoBotAccessibilityService.tapText(lbl) == "OK") { tapped = true; break }
                        }
                        if (!tapped) AutoBotAccessibilityService.tapText("enter")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val still = AutoBotAccessibilityService.detectLockType()
                            if (still == "none") { AppLockVault.resetFails(this, entry.pkg); chatReply("🔓 Unlock ho gaya ✓") }
                            else {
                                val fails = AppLockVault.bumpFail(this, entry.pkg)
                                if (fails >= 2) chatReply("⚠️ Sir, lagta hai apka lock change ho gaya hai (2 baar fail). Kindly Auto Bot ko naya lock bata dein:\n• app lock <app> pin <naya pin>\n• app lock <app> password <naya password>\n• app lock <app> pattern <naya pattern>\nPurana: ${AppLockVault.mask(entry)}")
                                else chatReply("🤔 Lock abhi bhi on hai — ek aur baar try kar raha hoon ya khud check karo. Agar PIN change hua hai to batao: app lock <app> pin <naya>")
                            }
                        }, 1500)
                        chatReply("🔓 ${if (entry.type == "pin") "PIN" else "Password"} type kar diya" + if (tapped) " + button dabaya." else " — Enter khud dabao.")
                    } else chatReply("⚠️ Screen par password/PIN box nahi mila (detected: $det). Pehle wo app kholo jis ka lock hai, phir 'unlock karo' bolo.")
                }
                else -> chatReply("⚠️ Screen par koi lock nahi mila ($det). Pehle locked app kholo, phir 'unlock karo' bolo.")
            }
            return true
        }
        if (low.contains("screen parho") || low.contains("screen padho") || low.contains("read screen") || low.contains("screen text") || low.contains("screen read")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            val txt = AutoBotAccessibilityService.readScreen()
            chatReply(if (txt.isBlank()) "📭 Screen par kuch readable text nahi mila — app khuli honi chahiye." else "📱 Screen text:\n" + txt.take(2500))
            return true
        }

                // ---------- v3.4: accessibility automation (tap/scroll/back/home) ----------
        val tapTarget = Regex("^tap\\s+(.+?)\\s*(?:karo|kro|do)*\\s*$").find(low)?.groupValues?.get(1)
            ?: Regex("^(.+?)\\s+(?:ko\\s+)?(?:dabao|daba|click\\s+karo|press\\s+karo)\\s*(?:karo|kro|do|de)*\\s*$").find(low)?.groupValues?.get(1)?.takeIf { low.contains("dabao") || low.contains("daba ") || low.contains("click karo") || low.contains("press karo") }
        if (tapTarget != null && !low.contains("call") && !low.startsWith("tap xy") && !low.startsWith("click xy")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            val res = AutoBotAccessibilityService.tapText(tapTarget)
            chatReply(when (res) {
                "OK" -> "👆 Tap ho gaya: $tapTarget"
                "NOT_FOUND" -> "❌ Screen par '$tapTarget' nahi mila. 'screen parho' se dekho kya likha hai."
                "NOT_CLICKABLE" -> "❌ '$tapTarget' tap-able nahi hai."
                "NO_WINDOW" -> "❌ Koi app khuli nahi lag rahi."
                else -> "❌ Tap fail: " + res
            })
            return true
        }
        if (low.startsWith("scroll") || low.contains("scroll karo") || low.contains("scroll kar")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            val up = low.contains("up") || low.contains("upar") || low.contains("wapis") || low.contains("peeche")
            val res = AutoBotAccessibilityService.scroll(!up)
            chatReply(if (res == "OK") "👇/👆 Scroll " + (if (up) "up" else "down") + " ho gaya." else "❌ Scroll fail: $res")
            return true
        }
        if (low == "back" || low.contains("back jao") || low.contains("wapis jao") || low.contains("peeche jao")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            chatReply(if (AutoBotAccessibilityService.goBack() == "OK") "⬅️ Wapis." else "❌ Back fail.")
            return true
        }
        if (low == "home" || low.contains("home jao") || low.contains("home screen")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            chatReply(if (AutoBotAccessibilityService.goHome() == "OK") "🏠 Home." else "❌ Home fail.")
            return true
        }


        // ---------- v3.9.3: contacts + clients + daily WA + research ----------
        if (low == "contacts" || low == "contact list" || low == "phonebook" || low.startsWith("contacts ")) {
            val q = if (low.startsWith("contacts ")) msg.substringAfter(" ").trim() else ""
            if (!Phonebook.hasPermission(this)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
                chatReply("📇 Contacts permission maangi — Allow ke baad dobara 'contacts' likho.")
            } else chatReply(Phonebook.listText(this, q.ifBlank { null }))
            return true
        }
        if (low.startsWith("mera number ") || low.startsWith("my number ") || low.startsWith("remember my number ")) {
            val n = msg.substringAfter("number ").trim()
            if (n.length < 8) chatReply("📞 Number do: mera number +923001234567")
            else {
                MemoryVault.setMyNumber(this, n)
                chatReply("✅ Mera number yaad: ${MemoryVault.myNumber(this)}\nRoz report isi pe WhatsApp se ja sakti hai.")
            }
            return true
        }
        if (low == "mera number" || low == "my number") {
            val n = MemoryVault.myNumber(this)
            chatReply(if (n.isNullOrBlank()) "📞 Abhi save nahi. Likho: mera number +92..." else "📞 Mera number: $n")
            return true
        }
        if (low.startsWith("wa bhejo ") || low.startsWith("wa send ") || low.startsWith("whatsapp bhejo ") || low.startsWith("message bhejo ")) {
            // already may exist — skip if duplicate handler later; this path enhances name→phonebook
        }
        if (low.startsWith("client add ") || low.startsWith("lead add ") || low.startsWith("supplier add ")) {
            val isSupplier = low.startsWith("supplier add ")
            val body = msg.substringAfter("add ").trim()
            val parts = body.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                chatReply("Usage:\nclient add Ali | phone +92... | email a@b.com | country PK | niche fashion\nsupplier add FactoryCo | phone +86... | country CN | niche kitchen")
                return true
            }
            var name = parts[0]; var phone = ""; var email = ""; var country = ""; var niche = ""; var interest = ""; var profile = ""; var source = "manual"; var notes = ""; var role = if (isSupplier) "supplier" else "client"
            for (p in parts) {
                val pl = p.lowercase()
                when {
                    pl.startsWith("phone ") || pl.startsWith("number ") -> phone = p.substringAfter(" ").trim()
                    pl.startsWith("email ") || pl.startsWith("mail ") -> email = p.substringAfter(" ").trim()
                    pl.startsWith("country ") -> country = p.substringAfter(" ").trim()
                    pl.startsWith("niche ") -> niche = p.substringAfter(" ").trim()
                    pl.startsWith("interest ") || pl.startsWith("buy ") -> interest = p.substringAfter(" ").trim()
                    pl.startsWith("profile ") || pl.startsWith("link ") -> profile = p.substringAfter(" ").trim()
                    pl.startsWith("source ") -> source = p.substringAfter(" ").trim()
                    pl.startsWith("note ") || pl.startsWith("notes ") -> notes = p.substringAfter(" ").trim()
                    pl.startsWith("role ") -> role = p.substringAfter(" ").trim().lowercase().ifBlank { role }
                    p == parts[0] -> name = p
                }
            }
            val pid = ProjectStore.activeId(this) ?: ""
            val dup = MemoryVault.findDuplicates(this, name, phone, pid)
            val c = MemoryVault.Client(name, phone, email, profile, country, niche, interest, source, notes, role, pid)
            if (dup.byName != null || dup.byPhone != null) {
                val d = dup.byName ?: dup.byPhone!!
                val why = buildString {
                    if (dup.byName != null) append("same name \"${dup.byName!!.name}\"")
                    if (dup.byPhone != null) {
                        if (isNotEmpty()) append(" + ")
                        append("same number ${dup.byPhone!!.phone}")
                    }
                }
                val json = org.json.JSONObject()
                    .put("name", c.name).put("phone", c.phone).put("email", c.email)
                    .put("profile", c.profile).put("country", c.country).put("niche", c.niche)
                    .put("interest", c.interest).put("source", c.source).put("notes", c.notes)
                    .put("role", c.role).put("projectId", c.projectId).toString()
                MemoryVault.setPendingDup(this, json)
                chatReply("⚠️ Duplicate lagta hai ($why).\nPurana: ${d.name} | ${d.phone} | ${d.role}\nNaya save / overwrite? Likho: *haan* ya *nahi*")
                return true
            }
            MemoryVault.addClient(this, c)
            chatReply("✅ ${c.role} save: ${c.name}\n📞 ${c.phone.ifBlank { "—" }}\nProject: ${if (pid.isEmpty()) "global" else "active"}")
            return true
        }
        if (low == "haan" || low == "han" || low == "yes" || low == "confirm") {
            val raw = MemoryVault.getPendingDup(this)
            if (raw != null) {
                try {
                    val o = org.json.JSONObject(raw)
                    val c = MemoryVault.Client(
                        o.optString("name"), o.optString("phone"), o.optString("email"),
                        o.optString("profile"), o.optString("country"), o.optString("niche"),
                        o.optString("interest"), o.optString("source"), o.optString("notes"),
                        o.optString("role", "client"), o.optString("projectId")
                    )
                    MemoryVault.addClient(this, c, force = true)
                    MemoryVault.clearPendingDup(this)
                    chatReply("✅ Overwrite save: ${c.name} (${c.role})")
                } catch (e: Exception) { chatReply("❌ Confirm fail: ${e.message}") }
                return true
            }
        }
        if (low == "nahi" || low == "no" || low == "cancel") {
            if (MemoryVault.getPendingDup(this) != null) {
                MemoryVault.clearPendingDup(this)
                chatReply("❎ Duplicate save cancel.")
                return true
            }
        }
        if (low == "clients" || low == "client list" || low == "leads") {
            chatReply(MemoryVault.clientsReport(this) + "\n\n💡 Fields: client phone Name | +92...\nclient email Name | a@b.com\nclient link Name | https://...\nclient country Name | PK\nclient niche Name | fashion\nclient interest Name | Amazon bags")
            return true
        }
        // client <field> Name | value
        val clientField = Regex("^client\\s+(phone|number|email|mail|link|profile|post|country|niche|interest|note|notes)\\s+(.+)$", RegexOption.IGNORE_CASE).find(msg.trim())
        if (clientField != null) {
            val field = clientField.groupValues[1].lowercase()
            val rest = clientField.groupValues[2]
            val parts = rest.split("|", limit = 2).map { it.trim() }
            if (parts.size < 2) {
                chatReply("Usage: client $field Ali | value")
                return true
            }
            val name = parts[0]
            val value = parts[1]
            val existing = MemoryVault.clients(this).firstOrNull { it.name.equals(name, true) }
            val base = existing ?: MemoryVault.Client(name = name)
            val updated = when (field) {
                "phone", "number" -> base.copy(phone = value, updated = System.currentTimeMillis())
                "email", "mail" -> base.copy(email = value, updated = System.currentTimeMillis())
                "link", "profile", "post" -> base.copy(profile = value, updated = System.currentTimeMillis())
                "country" -> base.copy(country = value, updated = System.currentTimeMillis())
                "niche" -> base.copy(niche = value, updated = System.currentTimeMillis())
                "interest" -> base.copy(interest = value, updated = System.currentTimeMillis())
                "note", "notes" -> base.copy(notes = value, updated = System.currentTimeMillis())
                else -> base
            }
            MemoryVault.addClient(this, updated)
            chatReply("✅ ${updated.name}\n📞 ${updated.phone.ifBlank { "—" }}\n📧 ${updated.email.ifBlank { "—" }}\n🔗 ${updated.profile.ifBlank { "—" }}\n🌍 ${updated.country.ifBlank { "—" }}\n🏷 ${updated.niche.ifBlank { "—" }}\n🛒 ${updated.interest.ifBlank { "—" }}")
            return true
        }
        if (low.startsWith("client search ") || low.startsWith("find client ") || low.startsWith("research ")) {
            val rest = msg.substringAfter(" ").trim().let {
                when {
                    low.startsWith("client search ") -> msg.substringAfter("search ").trim()
                    low.startsWith("find client ") -> msg.substringAfter("client ").trim()
                    else -> msg.substringAfter("research ").trim()
                }
            }
            val bits = rest.split(Regex("\\s+"), limit = 2)
            val q = bits.getOrNull(0) ?: rest
            val niche = bits.getOrNull(1) ?: ""
            chatReply(ClientFinder.researchGuide(q, niche))
            runOnUiThread {
                showBrowser(true)
                ClientFinder.searchUrls(q, niche).take(4).forEach { (_, url) -> webNewTab(url) }
            }
            return true
        }

        // ---------- images: generate / read / WA ----------
        if (low.startsWith("image generate ") || low.startsWith("generate image ") || low.startsWith("img gen ")) {
            val prompt = when {
                low.startsWith("image generate ") -> msg.substringAfter("generate ").trim()
                low.startsWith("generate image ") -> msg.substringAfter("image ").trim()
                else -> msg.substringAfter("gen ").trim()
            }
            chatReply("🎨 Image bana raha hoon...")
            Thread {
                val path = ImageBrain.generate(this, prompt)
                runOnUiThread {
                    if (path.startsWith("❌")) chatReply(path)
                    else chatReply("✅ Image save:\n$path\n💡 wa image  — WhatsApp pe bhejo\n💡 image padho — AI se padho")
                }
            }.start()
            return true
        }
        if (low.startsWith("image padho") || low.startsWith("image read") || low.startsWith("read image") || low.startsWith("vision ")) {
            val rest = when {
                low.startsWith("vision ") -> msg.substringAfter("vision ").trim()
                low.startsWith("image padho") -> msg.substringAfter("padho").trim().removePrefix(" ").trim()
                low.startsWith("image read") -> msg.substringAfter("read").trim().removePrefix(" ").trim()
                else -> msg.substringAfter("image").trim()
            }
            val pathPart: String
            val question: String
            if (rest.contains("|")) {
                val p = rest.split("|", limit = 2)
                pathPart = p[0].trim()
                question = p[1].trim().ifBlank { "Is image mein kya hai?" }
            } else {
                pathPart = rest
                question = "Is image / screenshot mein kya likha aur dikh raha hai? Detail mein batao."
            }
            val file = when {
                pathPart.isNotBlank() && java.io.File(pathPart).isFile -> java.io.File(pathPart)
                else -> ImageBrain.lastGenerated(this)
            }
            if (file == null) {
                chatReply("❌ Image path do ya pehle generate karo.\nimage padho /sdcard/.../pic.jpg | ye kya hai?")
                return true
            }
            chatReply("👁 Image padh raha hoon...")
            Thread {
                val ans = ImageBrain.readImage(this, file.absolutePath, question)
                runOnUiThread { chatReply(ans) }
            }.start()
            return true
        }
        if (low == "wa image" || low.startsWith("wa image ") || low.startsWith("whatsapp image ") || low.startsWith("send image ")) {
            val rest = when {
                low == "wa image" -> ""
                low.startsWith("wa image ") -> msg.substringAfter("image ").trim()
                low.startsWith("whatsapp image ") -> msg.substringAfter("image ").trim()
                else -> msg.substringAfter("image ").trim()
            }
            val path = if (rest.isNotBlank() && java.io.File(rest).isFile) rest
            else ImageBrain.lastGenerated(this)?.absolutePath
            if (path == null) {
                chatReply("❌ Pehle image generate karo ya path do:\nwa image /path/to.jpg")
                return true
            }
            runOnUiThread { chatReply(shareImageWhatsApp(path)) }
            return true
        }
        if (low == "last image" || low == "image last") {
            val f = ImageBrain.lastGenerated(this)
            chatReply(if (f != null) "🖼 ${f.absolutePath}" else "❌ Koi generated image nahi")
            return true
        }


        if (low.startsWith("web search ") || low.startsWith("google ") || low.startsWith("search web ")) {
            val q = msg.substringAfter(" ").trim().let {
                if (low.startsWith("web search ")) msg.substringAfter("search ").trim()
                else if (low.startsWith("search web ")) msg.substringAfter("web ").trim()
                else msg.substringAfter(" ").trim()
            }
            val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
            runOnUiThread { showBrowser(true); webNewTab(url) }
            chatReply("🔎 Web search: $q")
            return true
        }
        if (low.contains("roz") && (low.contains("client") || low.contains("report")) && (low.contains("whatsapp") || low.contains("wa ") || low.contains("number"))) {
            // Roz mujhe mere number par client info WhatsApp
            val my = MemoryVault.myNumber(this)
            if (my.isNullOrBlank()) {
                chatReply("📞 Pehle apna number save karo:\nmera number +923001234567\nPhir dobara bolo: roz client report WhatsApp")
                return true
            }
            MemoryVault.setDailyReport(this, true, 9, 0)
            AlarmEngine.schedule(this, 9, 0, true, "AUTO_CLIENT_REPORT")
            chatReply("✅ Roz 9:00 AM client report WhatsApp pe ($my) open hogi.\nClients: ${MemoryVault.clients(this).size}\nBand: daily report off")
            return true
        }
        if (low == "daily report off" || low == "roz report band") {
            MemoryVault.setDailyReport(this, false)
            chatReply("⏹ Daily WhatsApp client report OFF")
            return true
        }
        if (low == "daily report on" || low == "roz report on") {
            val my = MemoryVault.myNumber(this)
            if (my.isNullOrBlank()) { chatReply("Pehle: mera number +92..."); return true }
            MemoryVault.setDailyReport(this, true, 9, 0)
            AlarmEngine.schedule(this, 9, 0, true, "AUTO_CLIENT_REPORT")
            chatReply("✅ Daily report ON — 9 AM WhatsApp ($my)")
            return true
        }
        if (low == "daily report now" || low == "client report ab") {
            val msg = MemoryVault.buildDailyMessage(this)
            val my = MemoryVault.myNumber(this)
            if (!my.isNullOrBlank()) {
                try {
                    val url = "https://wa.me/" + my.filter { it.isDigit() } + "?text=" + java.net.URLEncoder.encode(msg.take(3500), "UTF-8")
                    runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    chatReply("📤 Report WhatsApp pe khuli — Send dabao.")
                } catch (e: Exception) { chatReply(msg) }
            } else chatReply(msg + "\n\n💡 mera number +92... save karo taake seedha WA khule.")
            return true
        }
        // Message / call saved client or supplier
        if (low.startsWith("message client ") || low.startsWith("message supplier ") ||
            low.startsWith("msg client ") || low.startsWith("msg supplier ") ||
            low.startsWith("client message ") || low.startsWith("supplier message ")) {
            val toSupplier = low.contains("supplier")
            val rest = msg.substringAfter(" ").substringAfter(" ").trim()
            val parts = rest.split("|", limit = 2).map { it.trim() }
            if (parts.size < 2) {
                chatReply("Usage: message client Ali | Hello\nmessage supplier Factory | Need quote")
                return true
            }
            val pid = ProjectStore.activeId(this)
            val hits = MemoryVault.findByName(this, parts[0], pid).filter {
                if (toSupplier) it.role == "supplier" else it.role != "supplier" || true
            }
            val target = hits.firstOrNull() ?: MemoryVault.findByName(this, parts[0]).firstOrNull()
            if (target == null) chatReply("❌ '${parts[0]}' save nahi. Pehle client/supplier add karo.")
            else if (target.phone.isBlank()) chatReply("❌ ${target.name} ka number nahi. client phone ${target.name} | +92...")
            else waMessageToPerson(target.phone, parts[1])
            return true
        }
        if (low.startsWith("call client ") || low.startsWith("call supplier ") ||
            (low.startsWith("call ") && !low.startsWith("call me"))) {
            val q = msg.substringAfter("call ").trim().removePrefix("client ").removePrefix("supplier ").trim()
            if (q.isBlank()) { chatReply("Usage: call client Ali"); return true }
            val pid = ProjectStore.activeId(this)
            var target = MemoryVault.findByName(this, q, pid).firstOrNull()
            if (target == null) target = MemoryVault.findByName(this, q).firstOrNull()
            val num = target?.phone?.ifBlank { null } ?: q
            if (num.filter { it.isDigit() }.length < 8) {
                chatReply("❌ Number/name nahi mila: $q")
                return true
            }
            runOnUiThread {
                try {
                    val u = Uri.parse("tel:" + num.filter { it.isDigit() || it == '+' })
                    startActivity(Intent(Intent.ACTION_DIAL, u))
                    chatReply("📞 Dial: ${target?.name ?: num}")
                } catch (e: Exception) { chatReply("❌ Call fail: ${e.message}") }
            }
            return true
        }
        // Project-scoped memory (mix nahi hota)
        if (low.startsWith("remember ") || low.startsWith("yaad ") || low.startsWith("project memory ")) {
            val rest = when {
                low.startsWith("project memory ") -> msg.substringAfter("memory ").trim()
                low.startsWith("yaad ") -> msg.substringAfter("yaad ").trim()
                else -> msg.substringAfter("remember ").trim()
            }
            if (rest.contains("|")) {
                val (k, v) = rest.split("|", limit = 2).map { it.trim() }
                if (ProjectStore.active(this) == null) chatReply("❌ Pehle: project open myapp")
                else {
                    ProjectStore.memorySet(this, k, v)
                    chatReply("🧠 Yaad (sirf is project): $k = $v")
                }
            } else {
                chatReply(ProjectStore.memoryAll(this) + "\n💡 remember key | value")
            }
            return true
        }
        if (low == "project memory" || low == "memory") {
            chatReply(ProjectStore.memoryAll(this)); return true
        }
        // Coding / files inside active project only
        if (low.startsWith("code ") || low.startsWith("write file ") || low.startsWith("create file ")) {
            val act = ProjectStore.active(this)
            val dir = when {
                act != null -> File(act.path)
                currentProject.exists() -> currentProject
                else -> File(getExternalFilesDir(null), "work/default").apply { mkdirs() }
            }
            val rest = msg.substringAfter(" ").trim().let {
                if (low.startsWith("write file ") || low.startsWith("create file ")) msg.substringAfter("file ").trim()
                else msg.substringAfter("code ").trim()
            }
            // code main.py | print("hi")
            val parts = rest.split("|", limit = 2).map { it.trim() }
            if (parts.size < 2) {
                chatReply("Usage: code main.py | print(\"hello\")\nActive project folder: ${dir.absolutePath}")
                return true
            }
            val fname = parts[0].replace("..", "").replace("/", "_").replace("\\", "_")
            val content = parts[1]
            try {
                val f = File(dir, fname)
                f.parentFile?.mkdirs()
                f.writeText(content)
                chatReply("📄 File: ${f.absolutePath}\n(${content.length} chars)\nProject mix nahi — sirf is folder mein.")
            } catch (e: Exception) { chatReply("❌ Write fail: ${e.message}") }
            return true
        }
        if (low.startsWith("mkdir ") || low == "folder list" || low.startsWith("folder ")) {
            val act = ProjectStore.active(this)
            val root = when {
                act != null -> File(act.path)
                currentProject.exists() -> currentProject
                else -> File(getExternalFilesDir(null), "work").apply { mkdirs() }
            }
            if (low == "folder list" || low == "folder") {
                val files = root.listFiles()?.joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.name } ?: "(empty)"
                chatReply("📂 ${root.absolutePath}\n$files")
                return true
            }
            if (low.startsWith("mkdir ")) {
                val name = msg.substringAfter("mkdir ").trim().replace("..", "")
                val f = File(root, name)
                val ok = f.mkdirs()
                chatReply(if (ok || f.isDirectory) "📁 ${f.absolutePath}" else "❌ mkdir fail")
                return true
            }
        }


        // ---------- v3.9.2: extra screen / automation ----------
        if (low.startsWith("type ") || low.startsWith("likho ") || low.startsWith("write ")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            val text = msg.substringAfter(" ").trim()
            val res = AutoBotAccessibilityService.typeText(text)
            chatReply(when (res) {
                "OK" -> "⌨️ Typed: $text"
                "NO_EDIT" -> "❌ Koi text field focus nahi — pehle field tap karo."
                "OFF" -> accSteps
                else -> "❌ Type fail ($res)"
            })
            return true
        }
        if (low.startsWith("tap xy ") || low.startsWith("click xy ")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            val parts = low.substringAfter("xy ").trim().split(Regex("\\s+"))
            if (parts.size < 2) { chatReply("Usage: tap xy 500 800"); return true }
            val x = parts[0].toFloatOrNull(); val y = parts[1].toFloatOrNull()
            if (x == null || y == null) { chatReply("❌ Numbers chahiye: tap xy 500 800"); return true }
            chatReply(if (AutoBotAccessibilityService.tapXY(x, y) == "OK") "👆 Tap ($x,$y)" else "❌ Tap fail")
            return true
        }
        if (low.contains("swipe left") || low == "left swipe") {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            chatReply(if (AutoBotAccessibilityService.swipeHorizontal(true) == "OK") "👈 Swipe left" else "❌ Fail")
            return true
        }
        if (low.contains("swipe right") || low == "right swipe") {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            chatReply(if (AutoBotAccessibilityService.swipeHorizontal(false) == "OK") "👉 Swipe right" else "❌ Fail")
            return true
        }
        if (low == "recents" || low.contains("recent apps")) {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            chatReply(if (AutoBotAccessibilityService.recents() == "OK") "🗂 Recents" else "❌ Fail")
            return true
        }
        if (low == "screen info" || low == "kaunsi app" || low == "which app") {
            if (!AutoBotAccessibilityService.isOn()) { chatReply(accSteps); return true }
            val pkg = AutoBotAccessibilityService.foregroundPackage()
            chatReply("📱 Foreground: ${pkg.ifBlank { "unknown" }}")
            return true
        }
        // Projects
        if (low == "project list" || low == "projects" || low == "project status") {
            chatReply(ProjectStore.status(this)); return true
        }
        if (low.startsWith("project new ") || low.startsWith("project create ")) {
            val name = msg.substringAfter(" ").substringAfter(" ").trim()
            if (name.isBlank()) { chatReply("📁 Naam do: project new myapp"); return true }
            val p = ProjectStore.create(this, name)
            currentProject = File(p.path)
            chatReply("📁 Project '${p.name}' ready + active\n${p.path}\nTerminal/py isi folder mein chalega.")
            return true
        }
        if (low.startsWith("project open ") || low.startsWith("project switch ")) {
            val q = msg.substringAfter(" ").substringAfter(" ").trim()
            val p = ProjectStore.open(this, q)
            if (p == null) chatReply("❌ Project nahi mila. 'project list'")
            else {
                currentProject = File(p.path)
                chatReply("👉 Active project: ${p.name}\n${p.path}")
            }
            return true
        }
        if (low.startsWith("project note ")) {
            val note = msg.substringAfter("note ").trim()
            if (ProjectStore.note(this, note)) chatReply("📝 Note save (active project)")
            else chatReply("❌ Pehle 'project open' karo")
            return true
        }
        // In-app browser tabs
        if (low == "tabs" || low == "tab list" || low == "browser tabs") {
            if (webTabs.isEmpty()) chatReply("🌐 Koi in-app tab nahi — 'browser' ya 'chrome google.com'")
            else {
                val sb = StringBuilder("🌐 *In-app tabs* (active #${webTabs.indexOfFirst { it.id == webActiveId } + 1})\n")
                webTabs.forEachIndexed { i, tab ->
                    val u = try { tab.wv.url } catch (_: Exception) { null } ?: ""
                    val mark = if (tab.id == webActiveId) " 👉" else ""
                    sb.append("${i + 1}. ${u.take(50)}$mark\n")
                }
                sb.append("\n💡 tab 2 | tab new <url> | tab band | tab close all")
                chatReply(sb.toString())
            }
            return true
        }
        if (low.startsWith("tab new ") || low == "new tab" || low == "tab new") {
            val url = if (low.startsWith("tab new ")) msg.substringAfter("new ").trim() else "https://www.google.com"
            val u = if (url.startsWith("http")) url else "https://$url"
            runOnUiThread { showBrowser(true); webNewTab(u) }
            chatReply("➕ Naya tab: $u")
            return true
        }
        if (Regex("^tab\\s+\\d+$").matches(low)) {
            val n = low.substringAfter("tab ").trim().toIntOrNull() ?: 0
            if (n < 1 || n > webTabs.size) chatReply("❌ Tab $n nahi. 'tabs' dekho.")
            else {
                runOnUiThread { showBrowser(true); webSwitch(webTabs[n - 1].id) }
                chatReply("👉 Tab $n active — ab isi tab pe kaam.")
            }
            return true
        }
        if (low == "tab band" || low == "tab close" || low == "close tab") {
            runOnUiThread { webCloseActive() }
            chatReply("🗑 Active tab band.")
            return true
        }
        if (low == "tab close all" || low == "close all tabs") {
            runOnUiThread {
                while (webTabs.isNotEmpty()) webCloseActive()
            }
            chatReply("🗑 Saari in-app tabs band.")
            return true
        }
        if (low.startsWith("chrome youtube") || low == "youtube chrome" || low.startsWith("yt chrome")) {
            val q = msg.substringAfter("youtube").substringAfter("chrome").trim().ifBlank { msg.substringAfter("yt chrome").trim() }
            val url = if (q.isBlank()) "https://www.youtube.com"
            else "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8")
            runOnUiThread { showBrowser(true); webNewTab(url) }
            chatReply("▶️ Chrome-tab (in-app) YouTube: ${q.ifBlank { "home" }}")
            return true
        }
        if (low == "whatsapp web" || low == "wa web") {
            runOnUiThread { showBrowser(true); webNewTab("https://web.whatsapp.com") }
            chatReply("💬 WhatsApp Web tab khuli — ek dafa QR scan karo. Phir:\n• wa padho\n• wa bhejo <naam> | <message>\nSirf usi contact ko jayega jiska naam match kare.")
            return true
        }
        if (low == "wa padho" || low == "wa read" || low == "whatsapp padho") {
            Thread { val r = waReadChats(); runOnUiThread { chatReply(r) } }.start(); return true
        }
        if (low.startsWith("wa bhejo ") || low.startsWith("wa send ") || low.startsWith("whatsapp bhejo ") || low.startsWith("message bhejo ")) {
            val rest = when {
                low.startsWith("whatsapp bhejo ") -> msg.substringAfter("bhejo ").trim()
                low.startsWith("message bhejo ") -> msg.substringAfter("bhejo ").trim()
                else -> msg.substringAfter(" ").substringAfter(" ").trim()
            }
            val parts = rest.split("|", limit = 2).map { it.trim() }
            if (parts.size < 2) {
                chatReply("Usage: wa bhejo Ali | Assalam o alaikum\nPhonebook naam pehchaanega; sirf usi number pe WA khulega.")
                return true
            }
            ProjectStore.setLastTask(this, "wa:${parts[0]}")
            waMessageToPerson(parts[0], parts[1])
            return true
        }
        if (low.startsWith("task ") || low.startsWith("automation ")) {
            val task = msg.substringAfter(" ").trim()
            ProjectStore.setLastTask(this, task)
            chatReply("📋 Task yaad: $task\nAb steps bolo: screen parho → tap … → type …\nYa: wa bhejo Name | msg")
            return true
        }


        // ---------- v3.4: chrome mein direct kholo ----------
        val chromeM = Regex("^\\s*chrome\\s+(.+?)\\s*(?:kholo|khol|karo|kro|do)*\\s*$").find(low)?.groupValues?.get(1)
        if (chromeM != null) {
            val q = chromeM.trim()
            if (q.isNotBlank() && q !in setOf("kholo", "khol", "karo", "open", "band")) {
                val url = if (q.startsWith("http") || q.contains(".") && !q.contains(" ")) q
                           else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                try {
                    val it2 = Intent(Intent.ACTION_VIEW, Uri.parse(if (url.startsWith("http")) url else "https://$url"))
                    it2.setPackage("com.android.chrome")
                    it2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it2)
                    chatReply("🌐 Chrome mein khul gaya: $q")
                } catch (e: Exception) { openUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")); chatReply("🌐 Chrome nahi mila — default browser mein search khol diya: $q") }
                return true
            }
        }


        
        // ---------- v3.9.5: project → GitHub push / APK / AAB (active project only) ----------
        if (low == "github push" || low.startsWith("github push ") || low == "only push" || low == "sirf push") {
            val token = TokenVault.get(this, "github")
            if (token == null) { chatReply("🔑 token save github ghp_xxxx (repo + workflow)"); return true }
            if (low.startsWith("github push zip") || low.startsWith("push zip")) { /* handled below */ }
            else {
                val dir = GitHubSync.activeProjectDir(this) ?: if (currentProject.exists()) currentProject else null
                if (dir == null) { chatReply("❌ project open <name> pehle"); return true }
                val repoName = GitHubSync.safeRepoName(
                    low.removePrefix("github push").trim().ifBlank { dir.name }.split(" ").first()
                )
                chatReply("📤 Sirf push — project: ${dir.name} → $repoName")
                Thread {
                    val (ok, full) = GitHubSync.ensureRepo(token, repoName, true)
                    if (!ok) { runOnUiThread { chatReply(full) }; return@Thread }
                    val pr = GitHubSync.pushFolder(token, full, dir)
                    if (pr.fail > 0) GitHubSync.setPendingError(this, pr.message)
                    runOnUiThread {
                        chatReply(pr.message + if (pr.fail > 0) "\n\n⚠️ Errors hain. Fix chahiye? *haan fix* / *nahi*" else "")
                    }
                }.start()
                return true
            }
        }




        if (low == "fastlane" || low == "fastlane help" || low == "fastlane setup") {
            chatReply("""🚀 *Fastlane automation (GitHub Actions)*

*Setup files bot push karega:*
• Gemfile
• fastlane/Fastfile
• fastlane/Appfile
• .github/workflows/fastlane-ios.yml ya fastlane-android.yml

*Commands*
```
fastlane ios     — iOS Fastlane + build
fastlane android — Android Fastlane + APK
fastlane setup ios
fastlane setup android
```

*GitHub Secrets (optional)*
iOS: IOS_CERTIFICATE_BASE64, IOS_CERTIFICATE_PASSWORD, IOS_PROVISION_PROFILE_BASE64, IOS_TEAM_ID
iOS TestFlight: APP_STORE_CONNECT_API_KEY_ID, APP_STORE_CONNECT_API_ISSUER_ID
Android Play: PLAY_STORE_JSON_KEY (service account JSON)

*Flow*
project open myapp → token save github ghp_… → fastlane ios / fastlane android
""")
            return true
        }
        if (low == "fastlane ios" || low == "fastlane setup ios" || low.startsWith("fastlane ios") ||
            low == "fastlane android" || low == "fastlane setup android" || low.startsWith("fastlane android")) {
            val token = TokenVault.get(this, "github")
            if (token == null) { chatReply("🔑 token save github ghp_xxxx (repo + workflow)"); return true }
            val dir = GitHubSync.activeProjectDir(this) ?: if (currentProject.exists()) currentProject else null
            if (dir == null) { chatReply("❌ project open <name>"); return true }
            val platform = if (low.contains("ios")) "ios" else "android"
            val repoName = GitHubSync.safeRepoName(dir.name)
            chatReply("🚀 Fastlane ($platform) — project ${dir.name} → GitHub...")
            Thread {
                val (ok, full) = GitHubSync.ensureRepo(token, repoName, true)
                if (!ok) { runOnUiThread { chatReply(full) }; return@Thread }
                val pr = GitHubSync.pushFolder(token, full, dir)
                val fl = GitHubSync.ensureFastlane(token, full, platform)
                val tr = GitHubSync.triggerFastlane(token, full, platform)
                ProjectStore.setLastTask(this, "fastlane:$platform:$full")
                runOnUiThread {
                    chatReply("${pr.message}\n\n$fl\n\n$tr\n\n⏳ Actions: https://github.com/$full/actions\nDownload: apk download / ipa download")
                }
            }.start()
            return true
        }

        if (low == "ios signing" || low == "ios certificate" || low == "apple signing" ||
            low == "ipa signing" || low.contains("signing setup") || low == "ios help") {
            chatReply("""🍎 *iOS / Apple signing setup*

*1. Apple Developer (developer.apple.com)*
• Membership active
• Certificates → create *Apple Distribution* (or Development) → download .cer → Keychain → export *Login* cert as .p12
• Profiles → App Store / Ad Hoc → select App ID + cert → download .mobileprovision

*2. Base64 (Mac Terminal)*
```
base64 -i Certificates.p12 | pbcopy
base64 -i profile.mobileprovision | pbcopy
```

*3. GitHub repo → Settings → Secrets → Actions* — add:
• `IOS_CERTIFICATE_BASE64` = p12 base64
• `IOS_CERTIFICATE_PASSWORD` = p12 password
• `IOS_PROVISION_PROFILE_BASE64` = mobileprovision base64
• `IOS_TEAM_ID` = Team ID (optional)
• `IOS_KEYCHAIN_PASSWORD` = any temp password (optional)

*4. Auto Bot*
```
project open myios
token save github ghp_...
ipa banao
ipa download
```

⚠️ Secrets ke baghair: unsigned IPA / build-only (device install limited).
✅ Secrets ke sath: signed IPA (Ad Hoc / distribution profile ke mutabiq).
""")
            return true
        }

        if (low == "ipa banao" || low == "ios banao" || low == "iphone app banao" ||
            low.startsWith("ipa banao ") || low.startsWith("ios banao ") ||
            low.contains("project ka ipa") || low.contains("iphone app") ||
            low.startsWith("github ipa") || low.startsWith("github ios")) {
            val token = TokenVault.get(this, "github")
            if (token == null) { chatReply("🔑 token save github ghp_xxxx (repo + workflow)"); return true }
            val dir = GitHubSync.activeProjectDir(this) ?: if (currentProject.exists()) currentProject else null
            if (dir == null) { chatReply("❌ Active project: project open <name>"); return true }
            val repoName = GitHubSync.safeRepoName(dir.name)
            chatReply("🍎 Project: ${dir.absolutePath}\n→ GitHub + iOS IPA (macOS Actions)...\n(Xcode / Flutter / React Native)\nSigning: GitHub Secrets (IOS_CERTIFICATE_*) — detail: ios signing")
            Thread {
                val (ok, full) = GitHubSync.ensureRepo(token, repoName, true)
                if (!ok) { runOnUiThread { chatReply(full) }; return@Thread }
                val pr = GitHubSync.pushFolder(token, full, dir)
                if (pr.fail > 0) {
                    GitHubSync.setPendingError(this, pr.message)
                    runOnUiThread { chatReply(pr.message + "\n\n⛔ *haan fix* ya *build phir bhi*") }
                    return@Thread
                }
                val wf = GitHubSync.ensureIosWorkflow(token, full)
                val tr = GitHubSync.triggerIosWorkflow(token, full)
                ProjectStore.setLastTask(this, "github-ios:$full")
                runOnUiThread {
                    chatReply("${pr.message}\n$wf\n$tr\n\n⏳ 10–25 min baad:\n• ipa download\nActions: https://github.com/$full/actions")
                }
            }.start()
            return true
        }

        if (low == "exe banao" || low == "exe bana" || low.startsWith("exe banao ") ||
            low.contains("project ka exe") || low == "mere project ka exe banao" ||
            low.startsWith("github exe") || low.contains("windows exe")) {
            val token = TokenVault.get(this, "github")
            if (token == null) { chatReply("🔑 token save github ghp_xxxx (repo + workflow)"); return true }
            val dir = GitHubSync.activeProjectDir(this) ?: if (currentProject.exists()) currentProject else null
            if (dir == null) { chatReply("❌ Active project: project open <name>"); return true }
            val repoName = GitHubSync.safeRepoName(dir.name)
            chatReply("💻 Project: ${dir.absolutePath}\n→ GitHub + Windows EXE Actions build...\n(Electron / .NET / Python / Go)")
            Thread {
                val (ok, full) = GitHubSync.ensureRepo(token, repoName, true)
                if (!ok) { runOnUiThread { chatReply(full) }; return@Thread }
                val pr = GitHubSync.pushFolder(token, full, dir)
                if (pr.fail > 0) {
                    GitHubSync.setPendingError(this, pr.message)
                    runOnUiThread {
                        chatReply(pr.message + "\n\n⛔ Errors. *haan fix*  ya  *build phir bhi*")
                    }
                    return@Thread
                }
                val wf = GitHubSync.ensureWindowsWorkflow(token, full)
                val tr = GitHubSync.triggerWindowsWorkflow(token, full)
                ProjectStore.setLastTask(this, "github-exe:$full")
                runOnUiThread {
                    chatReply("${pr.message}\n$wf\n$tr\n\n⏳ 5–20 min baad:\n• exe download\nActions: https://github.com/$full/actions")
                }
            }.start()
            return true
        }

        if (low == "apk banao" || low == "apk bana" || low.startsWith("apk banao ") ||
            low.contains("project ka apk") || low == "mere project ka apk banao" ||
            low.startsWith("github apk") || low == "aab banao" || low.startsWith("aab banao") ||
            low.contains("project ka aab") || low.startsWith("github aab")) {
            val token = TokenVault.get(this, "github")
            if (token == null) { chatReply("🔑 token save github ghp_xxxx (repo + workflow)"); return true }
            val dir = GitHubSync.activeProjectDir(this) ?: if (currentProject.exists()) currentProject else null
            if (dir == null) { chatReply("❌ Active project: project open <name>"); return true }
            val wantAab = low.contains("aab")
            val wantExe = low.contains("exe")
            val wantIpa = low.contains("ipa") || low.contains("ios")
            val repoName = GitHubSync.safeRepoName(dir.name)
            chatReply("🐙 Project: ${dir.absolutePath}\n→ GitHub + ${if (wantAab) "AAB" else "APK"} build...")
            Thread {
                val (ok, full) = GitHubSync.ensureRepo(token, repoName, true)
                if (!ok) { runOnUiThread { chatReply(full) }; return@Thread }
                val pr = GitHubSync.pushFolder(token, full, dir)
                if (pr.fail > 0) {
                    GitHubSync.setPendingError(this, pr.message)
                    runOnUiThread {
                        chatReply(pr.message + "\n\n⛔ Build se pehle errors fix? *haan fix*  ya  phir bhi build: *build phir bhi*")
                    }
                    return@Thread
                }
                val wf = GitHubSync.ensureAndroidWorkflow(token, full, withAab = wantAab)
                val tr = GitHubSync.triggerWorkflow(token, full)
                ProjectStore.setLastTask(this, "github:$full")
                runOnUiThread {
                    chatReply("${pr.message}\n$wf\n$tr\n\n⏳ 5–15 min baad:\n• apk download\n• aab download\nActions: https://github.com/$full/actions")
                }
            }.start()
            return true
        }
        if (low == "build phir bhi" || low == "phir bhi build") {
            val token = TokenVault.get(this, "github") ?: run { chatReply("🔑 token missing"); return true }
            val dir = GitHubSync.activeProjectDir(this) ?: run { chatReply("❌ no project"); return true }
            val full = "${GitHubSync.login(token)}/${GitHubSync.safeRepoName(dir.name)}"
            chatReply("▶️ Workflow dobara...")
            Thread {
                GitHubSync.ensureAndroidWorkflow(token, full, true)
                val tr = GitHubSync.triggerWorkflow(token, full)
                runOnUiThread { chatReply(tr) }
            }.start()
            return true
        }
        if (low == "haan fix" || low == "fix karo" || low == "error fix") {
            val err = GitHubSync.getPendingError(this)
            if (err.isNullOrBlank()) { chatReply("Koi pending GitHub error nahi."); return true }
            chatReply("🛠 Error report (approval ke baad aap changes maang sakte ho):\n\n$err\n\nAb bolo kya change karna hai, misal:\ncode MainActivity.kt | ...\nya detail likho — main us file mein edit suggest/apply karunga.")
            return true
        }
        if (low == "apk download" || low == "aab download" || low == "exe download" || low == "ipa download" || low == "ios download" ||
            low.startsWith("apk download") || low.startsWith("aab download") || low.startsWith("exe download") || low.startsWith("ipa download") ||
            low == "download apk" || low == "download aab" || low == "download exe" || low == "download ipa") {
            val token = TokenVault.get(this, "github")
            if (token == null) { chatReply("🔑 token save github ..."); return true }
            val dir = GitHubSync.activeProjectDir(this)
            val repoName = GitHubSync.safeRepoName(dir?.name ?: "app")
            val user = GitHubSync.login(token)
            if (user == null) { chatReply("❌ GitHub login fail"); return true }
            val full = "$user/$repoName"
            val wantAab = low.contains("aab")
            val wantExe = low.contains("exe")
            val wantIpa = low.contains("ipa") || low.contains("ios")
            chatReply("📥 GitHub se ${when { wantIpa -> "IPA"; wantExe -> "EXE"; wantAab -> "AAB"; else -> "APK" }} artifact dhoondh raha hoon...")
            Thread {
                val arts = GitHubSync.latestArtifacts(token, full)
                val match = arts.firstOrNull {
                    val n = it.first.lowercase()
                    when {
                        wantIpa -> n.contains("ipa") || n.contains("ios")
                        wantExe -> n.contains("exe") || n.contains("windows")
                        wantAab -> n.contains("aab") || n.contains("bundle")
                        else -> n.contains("apk") || n.contains("debug") || n.contains("app")
                    }
                } ?: arts.firstOrNull()
                if (match == null) {
                    runOnUiThread { chatReply("❌ Artifact nahi — pehle build complete ho (Actions). Phir dobara apk download") }
                    return@Thread
                }
                val zip = File(getExternalFilesDir(null), "images/artifact_${match.second}.zip")
                val dl = GitHubSync.downloadArtifactZip(token, match.third, zip)
                if (dl.startsWith("❌")) { runOnUiThread { chatReply(dl) }; return@Thread }
                val outDir = File(getExternalFilesDir(null), "images/builds").apply { mkdirs() }
                val files = GitHubSync.extractBuildProduct(zip, outDir)
                if (files.isEmpty()) {
                    runOnUiThread { chatReply("⚠️ Zip mila lekin andar .apk/.aab nahi.\n$dl") }
                    return@Thread
                }
                runOnUiThread {
                    for (f in files) {
                        chatReplyEx(
                            "📦 ${f.name} (${f.length() / 1024} KB)\n${f.absolutePath}",
                            org.json.JSONArray().put(
                                org.json.JSONObject()
                                    .put("label", "⬇️ Download ${f.name}")
                                    .put("action", "dlfile")
                                    .put("phone", f.absolutePath)
                            ).toString()
                        )
                    }
                }
            }.start()
            return true
        }
        if (low.startsWith("github push zip ") || low.startsWith("push zip ")) {
            val token = TokenVault.get(this, "github")
            if (token == null) { chatReply("🔑 token save github ghp_..."); return true }
            val rest = msg.substringAfter("zip ").trim()
            val parts = rest.split(Regex("\\s+"), limit = 2)
            val zipPath = parts.getOrNull(0) ?: ""
            val repoName = GitHubSync.safeRepoName(parts.getOrNull(1) ?: File(zipPath).nameWithoutExtension)
            if (zipPath.isBlank()) { chatReply("Usage: github push zip /path/app.zip [repo]"); return true }
            val dest = File(getExternalFilesDir(null), "work/_zip_" + repoName).apply { deleteRecursively(); mkdirs() }
            chatReply("📦 Zip → GitHub ($repoName)...")
            Thread {
                val uz = GitHubSync.unzipTo(zipPath, dest)
                if (uz.startsWith("❌")) { runOnUiThread { chatReply(uz) }; return@Thread }
                val (ok, fullOrErr) = GitHubSync.ensureRepo(token, repoName, true)
                if (!ok) { runOnUiThread { chatReply(fullOrErr) }; return@Thread }
                val pr = GitHubSync.pushFolder(token, fullOrErr, dest)
                if (pr.fail > 0) GitHubSync.setPendingError(this, pr.message)
                runOnUiThread { chatReply("$uz\n${pr.message}") }
            }.start()
            return true
        }

// ---------- v3.0: GITHUB (token se) ----------
        if (low == "github" || low.startsWith("github ")) {
            val token = TokenVault.get(this, "github")
            if (token == null) { chatReply("🔑 GitHub token nahi hai. Pehle:\ntoken save github <aap-ka-personal-access-token>\n(token GitHub → Settings → Developer settings → Personal access tokens se milta hai)"); return true }
            chatReply("🐙 GitHub se baat kar raha hoon...")
            Thread {
                val hdr = mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json")
                val rest = low.removePrefix("github").trim()
                val reply = when {
                    rest.isEmpty() || rest == "status" || rest == "who" -> {
                        val (c, t) = TokenVault.http("GET", "https://api.github.com/user", hdr, null)
                        if (c in 200..299) {
                            val o = JSONObject(t)
                            "👤 GitHub: ${o.optString("login")} — repos: ${o.optInt("public_repos")}, plan: ${o.optJSONObject("plan")?.optString("name") ?: "-"}\nCommands: github repo banao <naam> • github status"
                        } else "❌ GitHub error (HTTP $c): ${t.take(200)}"
                    }
                    rest.startsWith("repo banao") || rest.startsWith("repo create") || rest.startsWith("repo bana ") -> {
                        val name = rest.split(" ").lastOrNull { it.isNotBlank() } ?: ""
                        if (name.isBlank()) "❌ Repo ka naam bolo: github repo banao myproject"
                        else {
                            val (c, t) = TokenVault.http("POST", "https://api.github.com/user/repos", hdr, JSONObject().put("name", name).put("private", true).toString())
                            if (c in 200..299) {
                                val o = JSONObject(t)
                                "✅ Repo ban gaya (private): ${o.optString("html_url")}\nClone: git clone ${o.optString("clone_url")}"
                            } else "❌ GitHub error (HTTP $c): ${t.take(300)}"
                        }
                    }
                    rest.startsWith("build ") || rest == "build" -> {
                        val repo = rest.removePrefix("build").trim()
                        if (repo.isBlank()) "❌ Repo ka naam bolo: github build auto-bot-mobile"
                        else {
                            val (uc, ut) = TokenVault.http("GET", "https://api.github.com/user", hdr, null)
                            val login = if (uc in 200..299) JSONObject(ut).optString("login") else ""
                            val full = if (repo.contains("/")) repo else "$login/$repo"
                            if (login.isBlank()) "❌ Token kaam nahi kar raha (HTTP $uc)"
                            else {
                                val (wc, wt) = TokenVault.http("GET", "https://api.github.com/repos/$full/actions/workflows", hdr, null)
                                val wfs = if (wc in 200..299) JSONObject(wt).optJSONArray("workflows") ?: JSONArray() else JSONArray()
                                if (wc !in 200..299) "❌ Repo nahi mila ya access nahi: $full (HTTP $wc)"
                                else if (wfs.length() == 0) "❌ $full mein koi Actions workflow nahi"
                                else {
                                    val wfName = wfs.getJSONObject(0).optString("name")
                                    val wfId = wfs.getJSONObject(0).optInt("id")
                                    val (rc, rt) = TokenVault.http("GET", "https://api.github.com/repos/$full", hdr, null)
                                    val branch = if (rc in 200..299) JSONObject(rt).optString("default_branch", "main") else "main"
                                    val (dc, _) = TokenVault.http("POST", "https://api.github.com/repos/$full/actions/workflows/$wfId/dispatches", hdr, JSONObject().put("ref", branch).toString())
                                    if (dc != 202) "❌ Workflow start nahi hua (HTTP $dc) — workflow mein 'workflow_dispatch' trigger chahiye"
                                    else {
                                        runOnUiThread { chatReply("🚀 '$wfName' chal raha hai ($full @ $branch)...\nBuild mein 5-10 min lagte hain, main wait kar raha hoon.") }
                                        var conc = ""
                                        var runId = 0L
                                        val t0 = System.currentTimeMillis()
                                        while (System.currentTimeMillis() - t0 < 25 * 60 * 1000L) {
                                            Thread.sleep(12000)
                                            val (pc, pt) = TokenVault.http("GET", "https://api.github.com/repos/$full/actions/runs?per_page=1&event=workflow_dispatch", hdr, null)
                                            if (pc in 200..299) {
                                                val rs = JSONObject(pt).optJSONArray("workflow_runs") ?: JSONArray()
                                                if (rs.length() > 0) {
                                                    val r = rs.getJSONObject(0)
                                                    if (r.optString("status") == "completed") { conc = r.optString("conclusion"); runId = r.optLong("id"); break }
                                                }
                                            }
                                        }
                                        if (conc.isEmpty()) "⏳ Build abhi chal raha hai (25 min+). Baad mein 'github apk $repo' se APKs le lena."
                                        else if (conc != "success") "❌ Build fail hua ($conc). Log: https://github.com/$full/actions"
                                        else {
                                            runOnUiThread { chatReply("✅ Build green! APKs phone mein save kar raha hoon...") }
                                            val (ac, at) = TokenVault.http("GET", "https://api.github.com/repos/$full/actions/runs/$runId/artifacts", hdr, null)
                                            val arts = if (ac in 200..299) JSONObject(at).optJSONArray("artifacts") ?: JSONArray() else JSONArray()
                                            if (arts.length() == 0) "✅ Build ho gaya par koi artifact (APK) nahi bana."
                                            else {
                                                val saved = StringBuilder()
                                                for (i in 0 until arts.length()) {
                                                    val a = arts.getJSONObject(i)
                                                    val zip = ghDownload("https://api.github.com/repos/$full/actions/artifacts/" + a.optInt("id") + "/zip", token)
                                                    if (zip != null) for (p in extractApks(zip, repo)) saved.append("📥 ").append(p).append("\n")
                                                }
                                                if (saved.isBlank()) "❌ APK download fail. Baad mein 'github apk $repo' try karo."
                                                else "✅ Build complete! Phone mein save:\n$saved(File manager → Download → AutoBotBuilds)"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    rest.startsWith("apk ") || rest.startsWith("zip ") -> {
                        val repo = rest.removePrefix(if (rest.startsWith("apk ")) "apk" else "zip").trim()
                        if (repo.isBlank()) "❌ Repo ka naam bolo: github apk auto-bot-mobile"
                        else {
                            val (uc, ut) = TokenVault.http("GET", "https://api.github.com/user", hdr, null)
                            val login = if (uc in 200..299) JSONObject(ut).optString("login") else ""
                            val full = if (repo.contains("/")) repo else "$login/$repo"
                            val (rc, rt) = TokenVault.http("GET", "https://api.github.com/repos/$full/actions/runs?per_page=1&status=success", hdr, null)
                            if (rc !in 200..299) "❌ Repo nahi mila: $full (HTTP $rc)"
                            else {
                                val runs = JSONObject(rt).optJSONArray("workflow_runs") ?: JSONArray()
                                if (runs.length() == 0) "❌ $full mein koi successful build nahi"
                                else {
                                    runOnUiThread { chatReply("📥 Latest successful build ki files la raha hoon...") }
                                    val runId = runs.getJSONObject(0).optLong("id")
                                    val (ac, at) = TokenVault.http("GET", "https://api.github.com/repos/$full/actions/runs/$runId/artifacts", hdr, null)
                                    val arts = if (ac in 200..299) JSONObject(at).optJSONArray("artifacts") ?: JSONArray() else JSONArray()
                                    if (arts.length() == 0) "❌ Us build mein koi artifact nahi"
                                    else {
                                        val saved = StringBuilder()
                                        for (i in 0 until arts.length()) {
                                            val a = arts.getJSONObject(i)
                                            val zip = ghDownload("https://api.github.com/repos/$full/actions/artifacts/" + a.optInt("id") + "/zip", token)
                                            if (zip != null) for (p in extractApks(zip, repo)) saved.append("📥 ").append(p).append("\n")
                                        }
                                        if (saved.isBlank()) "❌ Download fail. Internet/token check karo."
                                        else "✅ Save ho gaya:\n$saved(File manager → Download → AutoBotBuilds)"
                                    }
                                }
                            }
                        }
                    }
                    rest.startsWith("runs ") -> {
                        val repo = rest.removePrefix("runs").trim()
                        if (repo.isBlank()) "❌ Repo ka naam bolo: github runs auto-bot-mobile"
                        else {
                            val (uc, ut) = TokenVault.http("GET", "https://api.github.com/user", hdr, null)
                            val login = if (uc in 200..299) JSONObject(ut).optString("login") else ""
                            val full = if (repo.contains("/")) repo else "$login/$repo"
                            val (rc, rt) = TokenVault.http("GET", "https://api.github.com/repos/$full/actions/runs?per_page=3", hdr, null)
                            if (rc !in 200..299) "❌ Repo nahi mila: $full (HTTP $rc)"
                            else {
                                val runs = JSONObject(rt).optJSONArray("workflow_runs") ?: JSONArray()
                                if (runs.length() == 0) "📭 $full mein abhi koi run nahi"
                                else {
                                    val sb = StringBuilder("📋 $full — last runs:\n")
                                    for (i in 0 until runs.length()) {
                                        val r = runs.getJSONObject(i)
                                        val st = if (r.optString("status") == "completed") r.optString("conclusion") else r.optString("status") + " (chal raha)"
                                        sb.append("• ").append(r.optString("name")).append(" — ").append(st).append(" — ").append(r.optString("created_at").take(16).replace("T", " ")).append("\n")
                                    }
                                    sb.toString()
                                }
                            }
                        }
                    }
                    else -> "🐙 GitHub commands:\n• github status — account info\n• github repo banao <naam> — naya private repo\n• github build <repo> — Actions se APK build + phone mein save\n• github apk <repo> — last build ki APKs phone mein\n• github runs <repo> — build status"
                }
                runOnUiThread { chatReply(reply) }
            }.start()
            return true
        }

        // ---------- v3.0: YOUTUBE search + "play 2nd video" ----------
        if (low.startsWith("youtube ") || low.startsWith("yt ")) {
            val rest = msg.trim().substring(low.split(" ")[0].length).trim()
            if (rest.isBlank()) { chatReply("▶️ Kya search karoon? Misal: youtube lofi beats play 2"); return true }
            val playN = Regex("play (\\d+)").find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val autoPlay = playN == 0 && rest.startsWith("play ")
            val query = rest.replace(Regex("[ ]*play \\d+"), "").replace(Regex("^play "), "").replace(Regex("^(pe|par|on) "), "").trim()
            val key = TokenVault.get(this, "youtube")
            if (key == null) {
                runOnUiThread {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8")))) } catch (_: Exception) {}
                }
                chatReply("▶️ YouTube search khul gaya.\n⚠️ Number-wise play ke liye YouTube API key chahiye:\ntoken save youtube <api-key> (console.cloud.google.com se free)")
                return true
            }
            chatReply("⏳ YouTube search kar raha hoon...")
            Thread {
                val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=5&q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&key=" + key
                val (c, t) = TokenVault.http("GET", url, emptyMap(), null)
                val reply = if (c in 200..299) {
                    try {
                        val items = JSONObject(t).optJSONArray("items") ?: org.json.JSONArray()
                        val sb = StringBuilder("▶️ \"$query\" — results:\n")
                        for (i in 0 until items.length()) {
                            val it = items.getJSONObject(i)
                            sb.append("${i + 1}. ${it.getJSONObject("snippet").optString("title")}\n")
                        }
                        val wantN = if (autoPlay) 1 else playN
                        if (wantN in 1..items.length()) {
                            val vid = items.getJSONObject(wantN - 1).optJSONObject("id")?.optString("videoId") ?: ""
                            if (vid.isNotBlank()) {
                                runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$vid"))) } catch (_: Exception) {} }
                                sb.append("\n🎬 Video $playN chala raha hoon!")
                            }
                        } else sb.append("\nBolo: youtube $query play 2 — doosra video chalega")
                        sb.toString()
                    } catch (e: Exception) { "❌ YouTube parse fail: " + e.message }
                } else "❌ YouTube error (HTTP $c): ${t.take(200)}\nKey sahi hai? token save youtube <key>"
                runOnUiThread { chatReply(reply) }
            }.start()
            return true
        }

        // ---------- v3.0: WHATSAPP pre-filled message ----------
        if (low.startsWith("whatsapp ") || low.startsWith("wa ")) {
            val rest = msg.trim().substring(msg.trim().indexOf(' ') + 1).trim()
            val num = Regex("(\\+?\\d{8,15})").find(rest)?.groupValues?.get(1) ?: ""
            val text = rest.removePrefix(num).trim().ifBlank { "Hello!" }
            if (num.isBlank()) { chatReply("💬 Number chahiye. Misal:\nwhatsapp 923001234567 Assalam o Alaikum, kal milte hain"); return true }
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + java.net.URLEncoder.encode(text, "UTF-8"))))
                    chatReply("💬 WhatsApp chat khul gayi ($num) — message bhar chuka hai, bas Send dabao.")
                } catch (e: Exception) { chatReply("❌ WhatsApp nahi khula: " + e.message) }
            }
            return true
        }

        // ---------- v3.0: BROWSER commands ----------
        if (low == "browser" || low == "browser kholo" || low == "open browser") { runOnUiThread { showBrowser(true) }; chatReply("🌐 Browser khul gaya — tabs: '+' se naye, ✕ se band.\nChat se: web <search> • tabs • tab band • page text"); return true }
        if (low.startsWith("web ")) {
            val q = msg.trim().substring(4).trim()
            runOnUiThread { showBrowser(true); webNewTab("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")) }
            chatReply("🌐 Nayi tab khul gayi: $q")
            return true
        }
        if (low == "tabs" || low == "tab list" || low == "kitni tab") {
            if (webTabs.isEmpty()) { chatReply("🌐 Koi tab nahi khuli. 'browser' likho."); return true }
            chatReply("🌐 Tabs (${webTabs.size}):\n" + webTabs.mapIndexed { i, t -> "${i + 1}. ${if (t.id == webActiveId) "[active] " else ""}${t.wv.url?.take(60) ?: ""}" }.joinToString("\n") + "\n\n'tab 2' se switch, 'tab band' se active band")
            return true
        }
        if (Regex("^tab \\d+$").matches(low)) {
            val n = low.split(" ")[1].toInt()
            val t = webTabs.getOrNull(n - 1)
            if (t == null) chatReply("❌ Tab $n nahi hai — 'tabs' likho.")
            else runOnUiThread { webSwitch(t.id) }
            runOnUiThread { chatReply(if (t != null) "✅ Tab $n active." else "") }
            return true
        }
        if (low.contains("tab band") || low.contains("tab close") || low.contains("close tab")) { runOnUiThread { webCloseActive() }; chatReply("✅ Active tab band."); return true }
        if (low == "page text" || low.contains("page padho") || low.contains("page ka text")) {
            val t = webActive()
            if (t == null) { chatReply("🌐 Browser mein koi tab nahi khuli — pehle 'browser' likho."); return true }
            runOnUiThread {
                t.wv.evaluateJavascript("(document.body?document.body.innerText:'').substring(0,2500)") { r ->
                    chatReply("🌐 Page ka text:\n" + r.removeSurrounding("\""))
                }
            }
            return true
        }

        // ---------- v3.0: WHATSAPP WEB commands ----------
        if (low == "whatsapp web" || low == "wa web" || low.contains("whatsapp scan") || low.contains("wa scan")) {
            runOnUiThread { showBrowser(true); webNewTab("https://web.whatsapp.com") }
            chatReply("💬 WhatsApp Web khul gayi browser mein.\n1. Phone ke WhatsApp → Linked devices → QR scan karo (sirf pehli dafa)\n2. Session browser mein save rahega, agli baar khud khulega\n3. Phir bolo: wa padho (chats) ya wa bhejo <naam> <message>")
            return true
        }
        if (low == "wa padho" || low == "whatsapp padho" || low.contains("wa messages") || low.contains("wa chats")) {
            chatReply("⏳ WhatsApp chats padh raha hoon...")
            Thread { val res = waReadChats(); runOnUiThread { chatReply("💬 WhatsApp chats:\n" + res) } }.start()
            return true
        }
        if (low.startsWith("wa bhejo") || low.startsWith("wa send")) {
            val rest = msg.trim().removePrefix("wa").trim().removePrefix("send").trim().removePrefix("bhejo").trim()
            val contact = rest.split(" ").firstOrNull() ?: ""
            val message = rest.substringAfter(contact, "").trim()
            if (contact.isBlank() || message.isBlank()) { chatReply("💬 Format: wa bhejo <naam> <message>\nMisal: wa bhejo Wishal yaar kal milte hain"); return true }
            chatReply("⏳ $contact ko message bhej raha hoon...")
            Thread { val res = waSend(contact, message); runOnUiThread { chatReply(res) } }.start()
            return true
        }

        // ---------- v3.0: NOTIFY (numbers add/change/delete + notification bhejo) ----------
        if (low.startsWith("notify add") || low.startsWith("notify number") || low.startsWith("notify save")) {
            val num = Regex("(\\+?\\d{8,15})").find(msg)?.groupValues?.get(1) ?: ""
            if (num.isBlank()) { chatReply("🔔 Number bolo: notify add 923001234567"); return true }
            val l = notifyList()
            if (!l.contains(num)) l.add(num)
            notifySave(l)
            chatReply("🔔 Notification number save: $num (ab total ${l.size})\nW9 important cheezain in numbers ko WhatsApp pe bhejega.")
            return true
        }
        if (low == "notify list" || low == "notify numbers") {
            val l = notifyList()
            chatReply(if (l.isEmpty()) "🔔 Koi notify number nahi. 'notify add 923001234567' se jodo." else "🔔 Notify numbers:\n" + l.joinToString("\n") { "• $it" } + "\n\n'notify delete <number>' se hatao.")
            return true
        }
        if (low.startsWith("notify delete") || low.startsWith("notify remove")) {
            val num = Regex("(\\+?\\d{8,15})").find(msg)?.groupValues?.get(1) ?: ""
            val l = notifyList()
            l.remove(num)
            notifySave(l)
            chatReply(if (num.isBlank()) "❌ Number bolo: notify delete 923001234567" else "🗑️ Hata diya: $num (baqi: ${l.size})")
            return true
        }
        if (low.startsWith("notify ") && !low.contains("add") && !low.contains("delete") && !low.contains("list") && !low.contains("number") && !low.contains("save") && !low.contains("remove")) {
            val text = msg.trim().substring(msg.trim().lowercase().indexOf("notify") + 7).trim()
            if (text.isBlank()) { chatReply("🔔 Kya bhejna hai? Misal: notify light band karo yaad dila dena"); return true }
            val nums = notifyList()
            if (nums.isEmpty()) { chatReply("🔔 Pehle number jodo: notify add 923001234567"); return true }
            chatReply("⏳ ${nums.size} number/numbers pe WhatsApp notification bhej raha hoon...")
            Thread {
                val t = waWebTab()
                if (t != null) {
                    val results = nums.map { n -> waSend(n, text) }
                    runOnUiThread { chatReply(results.joinToString("\n")) }
                } else {
                    runOnUiThread {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${nums[0]}?text=" + java.net.URLEncoder.encode(text, "UTF-8")))) } catch (_: Exception) {}
                        chatReply("⚠️ WhatsApp Web nahi khuli thi, is liye pehla number khul kar message bhar diya — khud Send dabana.\nPoori automation ke liye pehle 'whatsapp web' likh kar QR scan karo.")
                    }
                }
            }.start()
            return true
        }


        if (low == "dep" || low == "dep list" || low == "deps" || low.startsWith("dep ")) {
            handleDepCommand(msg.trim(), fromChat = true, termActive()); return true
        }
        if (low.startsWith("run ")) { runShell(msg.substring(4).trim(), fromChat = true); chatReply("⏳ Command chal raha hai terminal mein..."); return true }
        if (low.startsWith("python ") || low.startsWith("py ")) { runPython(msg.substring(low.indexOf(' ') + 1).trim(), fromChat = true); return true }
        if (low.startsWith("project ")) {
            val name = msg.substring(8).trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
            currentProject = File(getExternalFilesDir(null), "work/" + name).apply { mkdirs() }
            File(currentProject, "libs").mkdirs()
            val proj = if (name.isEmpty()) "main" else name
            chatReply("📁 Project '" + proj + "' ready!\nPath: " + currentProject.absolutePath + "\nAb 'py ...' isi project mein chalega, aur 'pip install <pkg>' isi ke libs/ mein install hoga."); return true
        }
        if (low.startsWith("pip install ")) { pipInstall(msg.substring(12).trim(), fromChat = true); chatReply("⏳ pip install chal raha hai..."); return true }
        if (low.startsWith("cmd ")) { runShell(msg.substring(4).trim(), fromChat = true); chatReply("⏳ Command chal raha hai terminal mein..."); return true }

                
        // ---------- v3.9.7: SIM dialer + smart call + WA call ----------
        if (low == "sim dialer" || low == "sim settings" || low == "sim page" || low == "sim status") {
            chatReply(SimDialer.statusText(this)); return true
        }
        if (low == "sim 1 default" || low == "default sim 1" || low == "always sim 1") {
            SimDialer.setDefaultSlot(this, 0); SimDialer.noteUserChoseDefault(this)
            chatReply("✅ Default call SIM: **SIM 1**\nAb bina pooche SIM 1 se call (override: call Name sim 2)\nSettings: menu → SIM Dialer")
            return true
        }
        if (low == "sim 2 default" || low == "default sim 2" || low == "always sim 2") {
            SimDialer.setDefaultSlot(this, 1); SimDialer.noteUserChoseDefault(this)
            chatReply("✅ Default call SIM: **SIM 2**\nAb bina pooche SIM 2 se call (override: call Name sim 1)\nSettings: menu → SIM Dialer")
            return true
        }
        if (low == "sim ask" || low == "default sim ask" || low == "sim poochho") {
            SimDialer.setDefaultSlot(this, SimDialer.ASK); SimDialer.noteUserChoseDefault(this)
            chatReply("✅ Default: har call pe SIM 1 / SIM 2 poochhunga (chat + speaker)")
            return true
        }
        // pending SIM choice after "call Name"
        if (pendingCallPhone != null && (low == "sim 1" || low == "sim one" || low == "1" || low == "sim 2" || low == "sim two" || low == "2" || low == "cancel" || low.contains("sim 1") || low.contains("sim 2"))) {
            if (low == "cancel" || low.contains("cancel") || low.contains("mat karo")) {
                pendingCallPhone = null; pendingCallName = null
                chatReply("❎ Call cancel.")
                return true
            }
            val slot = SimDialer.parseSimFromText(low) ?: if (low.trim() == "1") 0 else if (low.trim() == "2") 1 else null
            if (slot == null) {
                chatReply("SIM 1, SIM 2, ya cancel likho / bolo")
                return true
            }
            val ph = pendingCallPhone!!
            val nm = pendingCallName ?: ph
            pendingCallPhone = null; pendingCallName = null
            val r = SimDialer.placeCall(this, ph, slot)
            SimDialer.noteCall(this, slot)                 // v4.2: aadat note
            SimDialer.noteContactCall(this, ph, slot)
            chatReply("$r\n👤 $nm")
            showSimSuggestion()
            return true
        }
        // WhatsApp voice / video call
        if (low.startsWith("wa call ") || low.startsWith("whatsapp call ") || low.startsWith("wa voice ") ||
            low.startsWith("wa video ") || low.startsWith("whatsapp video ") || low.startsWith("wa video call ") ||
            (low.contains("whatsapp") && (low.contains("call") || low.contains("video"))) ||
            (low.contains("wa ") && low.contains("call"))) {
            val video = low.contains("video")
            var target = msg
            for (p in listOf("whatsapp video call", "wa video call", "whatsapp call", "wa voice call", "wa video", "wa call", "whatsapp video", "whatsapp")) {
                if (low.startsWith(p)) { target = msg.substring(p.length).trim(); break }
            }
            target = target.replace(Regex("(?i)\\s*(ko|pr|pe|par)?\\s*(call|kro|karo|do)?\\s*$"), "").trim()
            val phone = Regex("(\\+?\\d[\\d\\s-]{6,}\\d)").find(target)?.value?.replace(Regex("[\\s-]"), "")
            val hits = if (phone != null) emptyList() else resolveCallTargets(target)
            when {
                phone != null -> chatReply(SimDialer.whatsAppCall(this, phone, video))
                hits.size == 1 -> chatReply(SimDialer.whatsAppCall(this, hits[0].second, video) + "\n👤 ${hits[0].first}")
                hits.size > 1 -> {
                    val sb = StringBuilder("📞 Kai contacts — kis pe ${if (video) "video" else "WA"} call?\n")
                    hits.take(8).forEachIndexed { i, p -> sb.append("${i + 1}. ${p.first} — ${p.second}\n") }
                    chatReply(sb.toString() + "Number ya poora naam likho.")
                }
                else -> chatReply("❌ Contact/number nahi mila: $target\nMisal: wa call Rizwan Bai")
            }
            return true
        }
        // Normal SIM call by name/number
        if (low.startsWith("call ") || low.endsWith(" ko call karo") || low.endsWith(" ko call kro") ||
            low.endsWith(" ko call") || Regex("(?i).+\\s+ko\\s+call").containsMatchIn(low) ||
            (low.contains("call") && !low.contains("whatsapp") && !low.startsWith("wa ") && !low.contains("github"))) {
            var target = when {
                low.startsWith("call ") -> msg.substring(5).trim()
                else -> msg.replace(Regex("(?i)\\s*ko\\s*call\\s*(karo|kro|do)?\\s*$"), "").replace(Regex("(?i)^call\\s*"), "").trim()
            }
            val forcedSim = SimDialer.parseSimFromText(target)
            target = target.replace(Regex("(?i)\\s*sim\\s*[12one twoekdo]+\\s*"), " ").trim()
            val phoneDirect = Regex("(\\+?\\d[\\d\\s-]{6,}\\d)").find(target)?.value?.replace(Regex("[\\s-]"), "")
            val hits = if (phoneDirect != null) listOf(target to phoneDirect) else resolveCallTargets(target)
            when {
                hits.isEmpty() && phoneDirect == null -> chatReply("❌ Koi match nahi: $target\ncontacts / client list dekho")
                hits.size > 1 -> {
                    val sb = StringBuilder("📞 Multiple matches — kis ko call?\n")
                    hits.take(10).forEachIndexed { i, p -> sb.append("${i + 1}. ${p.first} — ${p.second}\n") }
                    chatReply(sb.append("Poora naam ya number likho (e.g. call Rizwan Bai)").toString())
                }
                else -> {
                    val (nm, ph) = if (phoneDirect != null) (phoneDirect to phoneDirect) else hits[0]
                    startSmartCall(nm, ph, forcedSim)
                }
            }
            return true
        }


        // ---------- v3.9.6: reliable commands BEFORE weak offline LLM ----------
        // Contact / number save (do not send to SmolLM)
        if (low.contains("number save") || low.contains("number save karo") ||
            (low.contains("save karo") && Regex("\\d{7,}").containsMatchIn(low)) ||
            low.startsWith("save number") || low.startsWith("contact save") ||
            Regex("(yeh|ye|this)?\\s*number\\s*save").containsMatchIn(low)) {
            val phone = Regex("(\\+?\\d[\\d\\s-]{6,}\\d)").find(msg)?.value?.replace(Regex("[\\s-]"), "") ?: ""
            if (phone.length < 7) {
                chatReply("📞 Number nahi mila. Misal:\nsave number 03221234567 Ali")
                return true
            }
            val nameGuess = msg.replace(Regex("(?i)(yeh|ye|this)?\\s*number\\s*save\\s*(karo)?"), " ")
                .replace(phone, " ").replace(Regex("\\d"), " ")
                .replace(Regex("\\s+"), " ").trim()
                .ifBlank { "Saved" }
            try {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                    putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, phone)
                    putExtra(android.provider.ContactsContract.Intents.Insert.NAME, nameGuess)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                chatReply("📇 Contacts app khuli — number: $phone\nNaam: $nameGuess\nSave dabao.")
                askSimAfterSave()   // v4.2: buttons + speaker wala poora flow
            } catch (e: Exception) {
                chatReply("❌ Contact save UI nahi khuli: ${e.message}\nManually save: $phone")
            }
            return true
        }
        // Story / creative writing — better prompt path; avoid accessibility false match
        if (Regex("(?i)(story|kahani|story\\s*likho|story\\s*lokho|write\\s*story|kahani\\s*likho)").containsMatchIn(low)
            && !low.startsWith("open ") && !low.contains("accessibility")) {
            val topic = msg.replace(Regex("(?i)(write\\s*)?(story|kahani)\\s*(likho|lokho|on|pr|pe)?"), " ")
                .replace(Regex("\\s+"), " ").trim()
                .ifBlank { "a kind teacher" }
            showThinking()
            Thread {
                val q = "Write a short complete story (8-12 lines) about: $topic. " +
                    "Use simple English or Roman Urdu if user mixed languages. No word loops. Clear beginning, middle, end."
                val ans = try { AIBrain.ask(this@MainActivity, q) } catch (_: Exception) { null }
                    ?: try { GgufEngine.ask(this@MainActivity, q) } catch (_: Exception) { null }
                    ?: "❌ Story abhi nahi bani. API key add karo ya stronger model: model list"
                runOnUiThread { chatReply(ans) }
            }.start()
            return true
        }


        if (low.startsWith("open ")) {
            val target = msg.substring(5).trim()
            return if (target.startsWith("http")) { runOnUiThread { openUrl(target) }; chatReply("🌐 Khol diya: $target"); true }
            else { val r = openAppByName(target); chatReply(r); true }
        }
        if (low.startsWith("youtube ") || low.startsWith("play ")) {
            val q = msg.substring(low.indexOf(' ') + 1).trim()
            runOnUiThread { openUrl("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8")) }
            chatReply("▶️ YouTube pe khel raha hoon: \"$q\" — pehla video kholne ke liye bola jao to \"play now\" likho."); return true
        }
        if (low == "play" || low == "play now") { runOnUiThread { openUrl("https://www.youtube.com") }; chatReply("▶️ YouTube khul gaya."); return true }
        if (low.startsWith("search ")) {
            val q = msg.substring(7).trim()
            runOnUiThread { openUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")) }
            chatReply("🔍 Google pe search khol diya: $q"); return true
        }
        if (low.startsWith("call ")) { runOnUiThread { inputPhone.setText(msg.substring(5).trim()); autoCall() }; chatReply("📞 Call kar raha hoon..."); return true }
        if (low.startsWith("wa ") || low.startsWith("whatsapp ")) {
            val q = msg.substring(low.indexOf(' ') + 1).trim()
            runOnUiThread { openUrl("https://wa.me/" + q.replace(Regex("[^0-9]"), "")) }
            chatReply("💬 WhatsApp chat khul rahi hai..."); return true
        }
        if (low.startsWith("mkdir ")) { runShell("mkdir -p " + msg.substring(6).trim(), fromChat = true); chatReply("📁 Folder ban raha hai..."); return true }
        if (low.startsWith("file ")) { runShell("touch " + msg.substring(5).trim(), fromChat = true); chatReply("📄 File ban rahi hai..."); return true }

        // ---------- v3.6 DIRECT AI: koi command match nahi hua + AI available ----------
        // 'ask' likhne ki zarurat nahi — user seedha koi bhi sawal/baat likhe, Qwen samajh lega.
        val dq = msg.trim()
        if (dq.length >= 2 && AIBrain.aiAvailable(this)) { aiAnswer(dq); return true }
        return false
    }

    /** v3.6: AI jawab (GGUF offline sabse pehle) — 'ask' aur direct-chat dono isi se */
    private fun aiAnswer(q: String) {
        showThinking()
        Thread {
            // v3.7: offline model ready nahi to progress messages ke saath pehle prepare karo
            try {
                if (GgufEngine.enabled(this) && GgufEngine.modelPresent(this) && !GgufEngine.isReady() && !GgufEngine.isLoading())
                    GgufEngine.prepare(this) { p -> thinkingStatus(p.take(80)) }
            } catch (_: Throwable) {}
            val ans = AIBrain.ask(this, q)
            runOnUiThread {
                chatReply(ans)
                // v3.0 Command Bridge: AI ke jawab mein sh code block ho to terminal pe chala do
                try {
                    val blocks = Regex("```(?:sh|bash|shell)?[ \\t]*\\n([\\s\\S]*?)```").findAll(ans)
                        .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
                    for (b in blocks) {
                        val bad = listOf("rm -rf", "m" + "kfs", "dd if=", "> /system")
                        val danger = bad.any { b.contains(it) }
                        if (danger) chatReply("⚠️ AI ne ye command di lekin W9 ne nahi chalaya (khatarnak laga):\n$b")
                        else runShell(b, fromChat = true)
                    }
                } catch (_: Exception) {}
            }
        }.start()
    }

    override fun onBackPressed() {
        if (terminalScreen.visibility == View.VISIBLE) { showTerminal(false); return }
        if (browserScreen.visibility == View.VISIBLE) { val t = webActive(); if (t != null && t.wv.canGoBack()) t.wv.goBack() else showBrowser(false); return }
        if (nativeScreen.visibility == View.VISIBLE) { showNative(false); return }
        if (webView.canGoBack()) { webView.goBack(); return }
        super.onBackPressed()
    }

    private fun showNative(show: Boolean) {
        nativeScreen.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------- JS bridge (website se native calls) ----------
    inner class NativeBridge {
        @JavascriptInterface
        fun openNativePanel() { runOnUiThread { showNative(true) } }

        // Server ka lamba "commands abhi chalte hain" message ki jagah smart jawab (page ka bubble() isse poochta hai)
        @JavascriptInterface
        fun smartFallback(msg: String): String {
            val m = msg.trim()
            val hasKey = try { KeyStore.load(this@MainActivity).any { it.enabled && it.key.isNotBlank() } } catch (_: Exception) { false }
            if (hasKey && m.isNotBlank()) { runCommand("ask " + m.lowercase(), "ask $m"); return "" }
            return SmartFallback.reply(m.lowercase())
        }

        @JavascriptInterface
        fun openTerminal() { runOnUiThread { showTerminal(true) } }

        // v3.1: chat copy buttons — clipboard + toast
        @JavascriptInterface
        fun copyToClipboard(text: String) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Auto Bot", text))
                runOnUiThread { Toast.makeText(this@MainActivity, "✓ Copied", Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) {}
        }

        // website chat se local commands — ye bot ko powerful banata hai
        @JavascriptInterface
        fun handleChatCommand(msg: String): Boolean {
            val m = msg.trim()
            val low = m.lowercase()
            return runCommand(low, m)
        }

        @JavascriptInterface
        fun appStatus(): String = "AutoBot " + PyEngine.brand + " (v" + BuildConfig.VERSION_NAME + ") — online: ${isOnline()}"

        // v3.1: sidebar footer real version (auto-updates har build ke saath)
        @JavascriptInterface
        fun getAppVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun saveToPhoneBook(name: String, phone: String) {
            runOnUiThread {
                inputName.setText(name)
                inputPhone.setText(phone)
                saveContact()
            }
        }

        @JavascriptInterface
        fun simCall(payload: String) {
            runOnUiThread {
                // payload: "0|+92..." or "1|..."
                val parts = payload.split("|", limit = 2)
                val slot = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val ph = parts.getOrNull(1) ?: return@runOnUiThread
                pendingCallPhone = null
                pendingCallName = null
                val res = SimDialer.placeCall(this@MainActivity, ph, slot)
                SimDialer.noteCall(this@MainActivity, slot)            // v4.2: aadat note
                SimDialer.noteContactCall(this@MainActivity, ph, slot)
                chatReply(res)
                showSimSuggestion()
            }
        }

        @JavascriptInterface
        fun openSimDialer() {
            runOnUiThread {
                try { startActivity(Intent(this@MainActivity, SimDialerActivity::class.java)) }
                catch (_: Exception) { chatReply(SimDialer.statusText(this@MainActivity)) }
            }
        }

        @android.webkit.JavascriptInterface
        fun callNumber(phone: String) {
            runOnUiThread {
                try {
                    startSmartCall(phone, phone, null)
                } catch (_: Exception) {
                    try { inputPhone.setText(phone); autoCall() } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun openAdminPanel() {
            runOnUiThread { startActivity(Intent(this@MainActivity, AdminPanelActivity::class.java)) }
        }

        @JavascriptInterface
        fun syncHistory(json: String) {
            try {
                getSharedPreferences("autobot", Context.MODE_PRIVATE).edit()
                    .putString("shared_chat_cache", json).apply()
            } catch (e: Exception) { /* ignore */ }
        }

        @JavascriptInterface
        fun getSharedHistory(): String {
            return try {
                getSharedPreferences("autobot", Context.MODE_PRIVATE).getString("shared_chat_cache", "[]") ?: "[]"
            } catch (e: Exception) { "[]" }
        }

        @JavascriptInterface
        fun endCall() { runOnUiThread { endCallAction(false) } }

        @JavascriptInterface
        fun openSettings() { runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } }

        // ---------- v3.3: accessibility bridge ----------
        @JavascriptInterface
        fun isAccessibilityOn(): Boolean = AutoBotAccessibilityService.isOn()

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        @JavascriptInterface
        fun readScreen(): String = AutoBotAccessibilityService.readScreen()

        @JavascriptInterface
        fun downloadModel(name: String) {
            val m = ModelStore.find(name)
            if (m == null) { runOnUiThread { chatReply("❌ Model samajh nahi aaya: $name") }; return }
            runOnUiThread { modelDownloadWithBar(m) }
        }

        @JavascriptInterface
        fun lockPhone() { runOnUiThread { lockPhone(false) } }

        @JavascriptInterface
        fun openWhatsApp(phone: String) {
            runOnUiThread {
                inputPhone.setText(phone)
                openWhatsApp()
            }
        }

        // ---------- v3.0: offline voice ----------
        @JavascriptInterface
        fun voiceReady(): Boolean = SpeechEngine.ready(this@MainActivity)

        @JavascriptInterface
        fun startVoice() { runOnUiThread { startVoiceCommand() } }

        @android.webkit.JavascriptInterface
        fun openLocalFile(path: String) {
            runOnUiThread {
                try {
                    val f = java.io.File(path)
                    if (!f.isFile) { chatReply("❌ File nahi: $path"); return@runOnUiThread }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, packageName + ".fileprovider", f
                    )
                    val mime = when (f.extension.lowercase()) {
                        "apk" -> "application/vnd.android.package-archive"
                        "aab" -> "application/octet-stream"
                        "exe" -> "application/vnd.microsoft.portable-executable"
                        "ipa" -> "application/octet-stream"
                        "png", "jpg", "jpeg", "webp" -> "image/*"
                        "zip" -> "application/zip"
                        else -> "*/*"
                    }
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { startActivity(i) } catch (_: Exception) {
                        val send = Intent(Intent.ACTION_SEND).setType(mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "File"))
                    }
                    chatReply("📁 ${f.name} — open/share sheet")
                } catch (e: Exception) { chatReply("❌ Open fail: ${e.message}") }
            }
        }


        @JavascriptInterface
        fun stopVoice() {
            runOnUiThread { voiceStopAll() }
            chatReply("🎤 Voice band.")
        }

        @JavascriptInterface
        fun voiceStatus(): String = SpeechEngine.status(this@MainActivity)

        @JavascriptInterface
        fun downloadVoiceModel(name: String) {
            val m = SpeechEngine.find(name)
            if (m == null) { runOnUiThread { chatReply("❌ Voice model nahi mila: $name — 'voice' likho options ke liye.") }; return }
            runOnUiThread { voiceDownloadWithBar(m) }
        }
    }

    // ---------- native powers ----------
    private fun cleanPhone(): String = OfflineBrain.normalizePhone(inputPhone.text.toString())

    private fun validPhone(): Boolean {
        val p = cleanPhone()
        if (p.length < 8) { Toast.makeText(this, "Pehle number likho", Toast.LENGTH_SHORT).show(); status("Error: number missing"); return false }
        return true
    }

    private fun saveContact() {
        val name = inputName.text.toString().trim()
        val phone = cleanPhone()
        if (name.isEmpty() || phone.length < 8) { Toast.makeText(this, "Naam aur number dono likho", Toast.LENGTH_SHORT).show(); status("Error: naam/number missing"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            doSaveContact(name, phone)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_CONTACTS), REQ_CONTACTS)
        }
    }

    private fun isDuplicate(phone: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val target = phone.replace(Regex("[^\\d]"), "")
        if (target.length < 8) return false
        val tail = target.takeLast(8)
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val existing = c.getString(0)?.replace(Regex("[^\\d]"), "") ?: continue
                if (existing.length >= 8 && existing.takeLast(8) == tail) return true
            }
        }
        return false
    }

    private fun doSaveContact(name: String, phone: String) {
        try {
            if (isDuplicate(phone)) { Toast.makeText(this, "Ye number pehle se saved hai", Toast.LENGTH_LONG).show(); status("Duplicate: $name"); return }
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?).build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build())
            val results = contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            if (results.isNotEmpty()) {
                Toast.makeText(this, "✅ $name phone ki contact book mein save ho gaya", Toast.LENGTH_LONG).show(); status("Saved: $name ($phone)")
                askSimAfterSave()   // v4.2: pehla save → "call kis sim se?"
            }
            else { Toast.makeText(this, "Save fail", Toast.LENGTH_SHORT).show(); status("Save fail") }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show(); status("Error: ${e.message}")
        }
    }

    private fun autoCall() {
        if (!validPhone()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) doCall()
        else { pendingCall = true; ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL) }
    }

    private fun doCall() {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + cleanPhone())))
            status("Calling: " + cleanPhone())
        } catch (e: Exception) { Toast.makeText(this, "Call fail: ${e.message}", Toast.LENGTH_SHORT).show(); status("Call fail") }
    }

    private fun openWhatsApp() {
        if (!validPhone()) return
        val num = cleanPhone().replace("+", "")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num")))
            status("WhatsApp: $num")
        } catch (e: Exception) { Toast.makeText(this, "WhatsApp fail: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission chahiye — Settings se allow karo", Toast.LENGTH_LONG).show(); status("Permission denied"); return
        }
        when (requestCode) {
            REQ_CALL -> doCall()
            REQ_CONTACTS -> { pendingBrainSave?.let { doSaveContact(it.first, it.second); pendingBrainSave = null } ?: saveContact() }
            REQ_ENDCALL -> doEndCall()
            REQ_MIC -> startVoiceAfterPermission()
        }
    }

    // ---------- v2.6: END CALL (TelecomManager, Android 9+) ----------
    private fun endCallAction(fromChat: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 28) {
            if (fromChat) chatReply("❌ Android 9 se purane phone pe programmatically call end nahi hoti — screen se kat kar do.")
            else Toast.makeText(this, "Android 9+ chahiye call end ke liye", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, "android.permission.ANSWER_PHONE_CALLS") == PackageManager.PERMISSION_GRANTED) doEndCall()
        else ActivityCompat.requestPermissions(this, arrayOf("android.permission.ANSWER_PHONE_CALLS"), REQ_ENDCALL)
    }

    private fun doEndCall() {
        var ok = false
        try {
            val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
            ok = tm.endCall()
        } catch (e: Exception) { ok = false }
        status(if (ok) "🔴 Call ended" else "Koi active call nahi mili")
        appendTerm(if (ok) "\n🔴 (call ended by bot)\n" else "\n(call end: koi active call nahi mili)\n")
    }

    // ---------- v2.6: LOCK PHONE (device admin, ek baar activate) ----------
    private fun lockPhone(fromChat: Boolean) {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val comp = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(comp)) {
            dpm.lockNow()
            if (fromChat) chatReply("🔒 Phone lock ho gaya.")
            else status("🔒 Phone locked")
        } else {
            runOnUiThread {
                try {
                    val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Auto Bot ko 'lock my phone' command se phone lock karne ki permission chahiye. Sirf lock — koi aur power nahi.")
                    }
                    startActivity(i)
                } catch (e: Exception) { Toast.makeText(this, "Admin activate fail: " + e.message, Toast.LENGTH_LONG).show() }
            }
            if (fromChat) chatReply("🔐 Pehli baar device admin permission chahiye — screen pe 'Activate' dabao (sirf ek baar). Phir dobara 'lock my phone' bolo.")
        }
    }

    // ---------- v3.0: speaker (call ke dor) ----------
    private fun speakerSet(on: Boolean): String {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val dev = am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (dev != null) am.setCommunicationDevice(dev) else return "❌ Is phone mein speaker device nahi mila."
                } else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = on
            }
            if (on) "📢 Speaker on! (Call chal rahi ho to awaas ab speaker se aayegi.)" else "🔇 Speaker band. Ab earpiece se."
        } catch (e: Exception) { "❌ Speaker fail: " + e.message }
    }

    // ---------- v3.0: torch (flashlight) ----------
    private var torchOnNow = false
    private fun torchSet(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: cm.cameraIdList.firstOrNull()
            if (id == null) { chatReply("❌ Torch (flash) is phone mein available nahi."); return }
            cm.setTorchMode(id, on)
            torchOnNow = on
        } catch (e: Exception) { chatReply("❌ Torch fail: " + e.message) }
    }

    // ---------- v3.0: volume ----------
    private fun audio(): android.media.AudioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private fun volumeAdj(dir: Int) {
        try { audio().adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0) } catch (e: Exception) {}
        if (dir == android.media.AudioManager.ADJUST_MUTE) chatReply("🔇 Volume mute kar diya.")
    }
    private fun volumeMax() {
        try {
            val am = audio()
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0)
            chatReply("🔊 Volume full kar diya.")
        } catch (e: Exception) { chatReply("❌ Volume fail: " + e.message) }
    }

    // ---------- v3.0: OFFLINE VOICE (Vosk) — mic = typing ----------
    private fun micPermitted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // FIX: pehle permission maango (model ho ya na ho). Vosk model ho to offline, warna phone ka Google speech.
    private fun startVoiceCommand() {
        if (!micPermitted()) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return }
        startVoiceAfterPermission()
    }

    private fun startVoiceAfterPermission() {
        if (SpeechEngine.ready(this)) startVoiceForReal() else startSystemSpeech()
    }

    private var sysRecognizer: android.speech.SpeechRecognizer? = null
    private val SYS_VOICE_LANG = "en-IN" // Urdu ke liye "ur-PK" kar sakte ho

    private fun setInputText(t: String) {
        try { webView.evaluateJavascript("(function(){var i=document.getElementById('msg'); if(i){i.value=" + JSONObject.quote(t) + ";} if(typeof autoGrow==='function')autoGrow(); if(typeof setSend==='function')setSend();})()", null) } catch (_: Exception) {}
    }

    private fun stopSystemSpeech() {
        try { sysRecognizer?.stopListening() } catch (_: Exception) {}
        try { sysRecognizer?.destroy() } catch (_: Exception) {}
        sysRecognizer = null
    }

    private fun voiceStopAll() {
        SpeechEngine.stop()
        stopSystemSpeech()
        voiceMicOff()
    }

    private fun startSystemSpeech() {
        try {
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
                chatReply("❌ Is phone mein speech service (Google app) nahi mili.\n" + SpeechEngine.status(this))
                return
            }
            stopSystemSpeech()
            val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            sysRecognizer = sr
            sr.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { voiceMicOn(); status("🎤 Sun raha hoon... bolo!") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { runOnUiThread { voiceMicLevel(rmsdB) } }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    voiceMicOff()
                    val why = when (error) {
                        android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Awaz sunai nahi di — dobara mic dabao aur bolo."
                        android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Internet nahi — offline ke liye likho: voice download urdu-hindi"
                        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission nahi — Settings se allow karo."
                        android.speech.SpeechRecognizer.ERROR_AUDIO -> "Mic record nahi ho raha (koi dusri app mic use kar rahi hai?)."
                        else -> "Mic error code $error"
                    }
                    chatReply("🎤 $why")
                }
                override fun onResults(results: Bundle?) {
                    voiceMicOff()
                    val t = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotEmpty()) sendAsTyped(t) else chatReply("🎤 Kuch samajh nahi aaya — dobara try karo.")
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotEmpty()) setInputText(t)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, SYS_VOICE_LANG)
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            sr.startListening(i)
            voiceMicOn()
        } catch (e: Exception) {
            voiceMicOff()
            chatReply("❌ Mic start fail: " + e.message)
        }
    }

    private fun startVoiceForReal() {
        val res = SpeechEngine.start(this,
            onPartial = { p ->
                runOnUiThread {
                    try { webView.evaluateJavascript("(function(){var i=document.getElementById('msg'); if(i){i.value=" + JSONObject.quote(p) + ";} if(typeof autoGrow==='function')autoGrow(); if(typeof setSend==='function')setSend();})()", null) } catch (_: Exception) {}
                }
            },
            onFinal = { f ->
                runOnUiThread { sendAsTyped(f) }
            },
            onFail = { e ->
                runOnUiThread {
                    voiceMicOff()
                    chatReply("🎤 Voice error: $e")
                }
            })
        if (res.startsWith("🎤")) {
            voiceMicOn()
        }
        chatReply(res)
    }

    private fun sendAsTyped(text: String) {
        try {
            webView.evaluateJavascript(
                "(function(){var i=document.getElementById('msg'); if(i){i.value=" + JSONObject.quote(text) + ";} if(typeof autoGrow==='function')autoGrow(); if(typeof setSend==='function')setSend(); if(typeof send==='function'){send();}})()",
                null)
        } catch (e: Exception) { chatReply(text) }
    }

    private fun voiceMicOn() {
        try {
            webView.evaluateJavascript(
                "(function(){if(window.setMicListening)setMicListening(true);else{window.__abVoiceOn=true;var m=document.getElementById('mic');if(m)m.classList.add('rec');}})()",
                null
            )
        } catch (_: Exception) {}
    }

    private fun voiceMicOff() {
        try {
            webView.evaluateJavascript(
                "(function(){if(window.setMicListening)setMicListening(false);else{window.__abVoiceOn=false;var m=document.getElementById('mic');if(m)m.classList.remove('rec');}})()",
                null
            )
        } catch (_: Exception) {}
    }

    private fun voiceMicLevel(rmsdB: Float) {
        // Map Android rms (~ -2..10) to 0..10 for UI bars
        val level = ((rmsdB + 2f) / 1.2f).coerceIn(0f, 10f)
        try {
            webView.evaluateJavascript(
                "(function(){if(window.setMicLevel)setMicLevel($level);})()",
                null
            )
        } catch (_: Exception) {}
    }

    // ---------- v4.2: pehla contact save → "call kis SIM se?" ----------
    private fun askSimAfterSave() {
        if (SimDialer.defaultSlot(this) != SimDialer.ASK) return
        if (SimDialer.prefs(this).getBoolean("asked_sim_once", false)) return
        SimDialer.prefs(this).edit().putBoolean("asked_sim_once", true).apply()
        val sims = SimDialer.listSims(this)
        val s1 = sims.firstOrNull { it.slot == 0 }?.label ?: "SIM 1"
        val s2 = sims.firstOrNull { it.slot == 1 }?.label ?: "SIM 2"
        TtsBox.speak(this, "Contact save ho gaya! Ab call kis SIM se karni hai? SIM 1, ya SIM 2?")
        chatReplyEx(
            "📱 **Pehla contact save ho gaya!** ✅\nAb calls kis SIM se karni hain by default?",
            org.json.JSONArray()
                .put(org.json.JSONObject().put("label", "📱 $s1").put("action", "simset").put("phone", "sim 1 default"))
                .put(org.json.JSONObject().put("label", "📱 $s2").put("action", "simset").put("phone", "sim 2 default"))
                .put(org.json.JSONObject().put("label", "🔄 Har baar poochho").put("action", "simset").put("phone", "sim ask"))
                .toString()
        )
    }

    // ---------- v4.2: aadat-based default SIM suggestion (user approval ke saath) ----------
    private fun showSimSuggestion() {
        val lead = SimDialer.suggestSlot(this) ?: return
        SimDialer.noteSuggestShown(this)
        val (u0, u1) = SimDialer.usage(this)
        val total = u0 + u1
        val leadU = if (lead == 0) u0 else u1
        TtsBox.speak(this, "Maine note kiya, aap zyada tar SIM ${lead + 1} se call karte hain. Kya SIM ${lead + 1} default bana doon?")
        chatReplyEx(
            "📊 **Aapki aadat note ki hai**\nZyada-tar **SIM ${lead + 1}** se call karte ho ($leadU me $total).\nSIM ${lead + 1} ko default bana doon? (phir har call pe nahi poochunga)",
            org.json.JSONArray()
                .put(org.json.JSONObject().put("label", "✅ Haan, SIM ${lead + 1} default").put("action", "simset").put("phone", "sim ${lead + 1} default"))
                .put(org.json.JSONObject().put("label", "❌ Nahi").put("action", "simcancel").put("phone", ""))
                .toString()
        )
    }

    // ---------- v2.6: brain contact → phone contact book bhi ----------
    private fun brainSavePhonebook(name: String, phone: String) {
        if (name.isBlank() || phone.length < 8) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) doSaveContact(name, phone)
        else { pendingBrainSave = Pair(name, phone); ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_CONTACTS), REQ_CONTACTS) }
    }

    // ---------- v2.6: chat reply with buttons ----------
    private fun chatReplyEx(text: String, buttonsJson: String) {
        runOnUiThread {
            try {
                webView.evaluateJavascript(
                    "window.__localBotReplyEx && window.__localBotReplyEx(" + JSONObject.quote(text) + "," + buttonsJson + ")",
                    null
                )
            } catch (e: Exception) { chatReply(text) }
        }
    }

    private fun status(msg: String) { statusText.text = msg }
}
