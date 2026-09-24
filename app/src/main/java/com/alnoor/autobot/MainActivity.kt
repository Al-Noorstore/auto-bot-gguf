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
            // v2.7: online sidebar mein Settings button
            try {
              var _sb = document.getElementById('sidebar');
              if (_sb && window.AutoBotNative && !document.getElementById('ab-settings-btn')) {
                var _sbB = document.createElement('button');
                _sbB.id = 'ab-settings-btn';
                _sbB.className = _sb.querySelector('.nav-item') ? _sb.querySelector('.nav-item').className : 'nav-item';
                _sbB.innerHTML = '⚙️ Settings';
                _sbB.onclick = function () { AutoBotNative.openSettings(); };
                _sb.appendChild(_sbB);
              }
            } catch (e) {}
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

    // shell engine: real Android sh, background mein bot bhi use karta hai
    private fun runShell(cmd: String, fromChat: Boolean = false, label: String = "$") {
        val sess = termActive()
        appendTermTo(sess, "\n$ $cmd\n")
        Thread {
            var out = ""
            try {
                if (cmd.trim().startsWith("py ")) { runPython(cmd.trim().substring(3).removeSurrounding("\""), fromChat); appendTermTo(sess, "$ "); return@Thread }
                if (cmd.trim().startsWith("pip install ")) { pipInstall(cmd.trim().substring(12), fromChat); appendTermTo(sess, "$ "); return@Thread }
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
                            val r = Shell.cmd(toRun).exec()
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
                        val p = ProcessBuilder("sh", "-c", toRun)
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
        return try {
            // step 1: search box khol kar naam likho
            waJs(t, """(function(){var b=document.querySelector('#side button[aria-label], #side span[data-icon=search]');if(!b)return 'nosearch';(b.closest('button')||b).click();return 'ok'})()""")
            Thread.sleep(700)
            val s1 = waJs(t, """(function(){var e=document.querySelector('#side div[contenteditable=true]');if(!e)return 'nobox';e.focus();e.textContent='""" + contact.replace("'", "") + """';e.dispatchEvent(new InputEvent('input',{bubbles:true}));return 'ok'})()""")
            if (!s1.contains("ok")) return "❌ Search box nahi mila ($s1)."
            Thread.sleep(1200)
            // step 2: pehla result kholo
            val s2 = waJs(t, """(function(){var items=document.querySelectorAll('#side div[role=listitem]');if(items.length==0)return 'noresult';items[0].click();return 'ok'})()""")
            if (!s2.contains("ok")) return "❌ Chat nahi mili: '$contact' ($s2). Naam spelling check karo."
            Thread.sleep(1200)
            // step 3: message likh kar send
            val s3 = waJs(t, """(function(){var e=document.querySelector('footer div[contenteditable=true]');if(!e)return 'nobox';e.focus();e.textContent='""" + message.replace("'", "") + """';e.dispatchEvent(new InputEvent('input',{bubbles:true}));var b=document.querySelector('footer button[aria-label*=end], span[data-icon=send]');if(!b)return 'nosend';(b.closest('button')||b).click();return 'sent'})()""")
            when {
                s3.contains("sent") -> "✅ '$contact' ko message bhej diya: $message"
                s3.contains("nobox") -> "❌ Message box nahi mila — chat khuli lagti nahi, dobara try karo."
                else -> "⚠️ Message likha tha lekin send button nahi mila ($s3) — khud Send dabao."
            }
        } catch (e: Exception) { "❌ Bhejne mein masla: " + e.message }
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
                    chatReply("🔑 Key connect karne ke liye key paste karo:\n• api key <key>   (Gemini/OpenAI/Groq/OpenRouter khud pehchan lunga)\n• ya: api key gemini <key>\n• Free Gemini key: aistudio.google.com/apikey\n\nSettings → API Keys se bhi add kar sakte ho.")
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
                val label = provider.split(" ")[0] + "-" + keyTok.takeLast(4)
                val k = KeyStore.ApiKey(provider, label, keyTok, KeyStore.defaultBase(provider), KeyStore.defaultModel(provider))
                // chat history mein poori key na rahe
                runOnUiThread { try { webView.evaluateJavascript("window.__lastLocalMsg='api key " + provider + " ****" + keyTok.takeLast(4) + "'", null) } catch (_: Exception) {} }
                chatReply(KeyStore.add(this, k) + "\n🔍 Test kar raha hoon...")
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
        var partial: String? = null
        for (ri in pm.queryIntentActivities(q, 0)) {
            val lbl = ri.loadLabel(pm).toString().lowercase()
            val pkg = ri.activityInfo.packageName
            if (lbl == n) return pkg
            if (partial == null && (lbl.contains(n) || (lbl.length >= 3 && n.contains(lbl)))) partial = pkg
        }
        return partial
    }

    private fun openAppByName(name: String): String {
        val n = name.trim().lowercase()
        val pkgMap = mapOf(
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
        var pkg: String? = pkgMap[n]
        // map wala package phone mein na ho (e.g. camera) to label se dhoondo
        if (pkg != null && packageManager.getLaunchIntentForPackage(pkg) == null) pkg = null
        if (pkg == null) pkg = findLaunchPackage(n)
        if (pkg == null) return "❌ App nahi mili: $name. Spelling check karo ya package naam do (e.g. open com.whatsapp)."
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return "❌ App installed nahi hai: $pkg"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            "✅ App khul gayi: $name"
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
        shellInit()
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
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
        appendTerm("Auto Bot Terminal v2.1 (" + PyEngine.brand + ") — real Android shell (sh)\nWorking dir: " + File(getExternalFilesDir(null), "work").absolutePath + "\nShell: ls, mkdir, echo, cat, rm, cp, mv, ps, df...\n" + PyEngine.pyHint + "\nChalo koi bhi command do!\n")

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

    // exact web UI: online → live site | offline → bundled copy (UI phir bhi poora dikhta hai)
    private fun loadSite() {
        if (isOnline()) webView.loadUrl(BASE)
        else {
            webView.loadUrl("file:///android_asset/index.html")
            Toast.makeText(this, "Offline mode — UI chalu hai, server ke features online hone pe chalein ge", Toast.LENGTH_LONG).show()
        }
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
        // ---------- OFFLINE BRAIN (v2.6): bina API key / bina model ke bhi ye commands chalete hain ----------
        try {
            val br = OfflineBrain.parse(this, low, msg)
            if (br != null) {
                for (a in br.actions) {
                    when (a.action) {
                        "call" -> runOnUiThread { inputPhone.setText(a.arg); autoCall() }
                        "endcall" -> runOnUiThread { endCallAction(false) }
                        "lock" -> runOnUiThread { lockPhone(false) }
                        "openapp" -> runOnUiThread { val r = openAppByName(a.arg); if (!r.startsWith("✅")) chatReply(r) }
                        "phonebook" -> runOnUiThread { brainSavePhonebook(a.arg, a.arg2) }
                        "closeapp" -> Thread { chatReply(closeAppByName(a.arg)) }.start()
                        "youtubesearch" -> runOnUiThread { openUrl("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(a.arg, "UTF-8")) }
                        "browsersearch" -> runOnUiThread { openUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(a.arg, "UTF-8")) }
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
            if (q.isBlank()) { chatReply("Sawal likho: ask <sawal>"); return true }
            chatReply("🤖 Soch raha hoon...")
            Thread {
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
            return true
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
                    else -> "🐙 GitHub commands:\n• github status — account info\n• github repo banao <naam> — naya private repo"
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
        return false
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

        @JavascriptInterface
        fun saveToPhoneBook(name: String, phone: String) {
            runOnUiThread {
                inputName.setText(name)
                inputPhone.setText(phone)
                saveContact()
            }
        }

        @JavascriptInterface
        fun callNumber(phone: String) {
            runOnUiThread {
                inputPhone.setText(phone)
                autoCall()
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
    private fun cleanPhone(): String {
        var p = inputPhone.text.toString().trim().replace(Regex("[^+\\d]"), "")
        if (p.startsWith("00")) p = "+" + p.substring(2)
        if (p.length in 10..12 && !p.startsWith("+")) p = "+$p"
        return p
    }

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
            if (results.isNotEmpty()) { Toast.makeText(this, "✅ $name phone ki contact book mein save ho gaya", Toast.LENGTH_LONG).show(); status("Saved: $name ($phone)") }
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
                override fun onRmsChanged(rmsdB: Float) {}
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
        try { webView.evaluateJavascript("(function(){window.__abVoiceOn=true; var m=document.getElementById('mic'); if(m){m.classList.add('rec'); m.title='Sun raha hoon... dabao band karne ke liye';}})()", null) } catch (_: Exception) {}
    }

    private fun voiceMicOff() {
        try { webView.evaluateJavascript("(function(){window.__abVoiceOn=false; var m=document.getElementById('mic'); if(m){m.classList.remove('rec'); m.title='Voice input (offline engine)';}})()", null) } catch (_: Exception) {}
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
