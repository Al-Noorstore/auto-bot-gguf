package com.alnoor.autobot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Auto Bot Accessibility — screen read / scroll / click / type (user enables in Settings).
 * UI nahi badalta; chat commands se control.
 */
class AutoBotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ---------- v4.3: App lock auto-apply — jab locked app khule, real PIN/password/pattern laga do ----------
        try {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString() ?: return
                val entry = AppLockVault.get(this, pkg) ?: AppLockVault.get(this, "app lock")
                if (entry != null && entry.enabled && !pkg.startsWith("com.alnoor.autobot")) {
                    val now = System.currentTimeMillis()
                    if (now - lastAutoApply > 5000) {
                        lastAutoApply = now
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try { autoApplyLock(pkg) } catch (_: Exception) {}
                        }, 900)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private var lastAutoApply = 0L

    // lock screen detect karke real credential apply karo
    private fun autoApplyLock(pkg: String) {
        val entry = AppLockVault.get(this, pkg) ?: AppLockVault.get(this, "app lock") ?: return
        val type = detectLockType()
        when {
            type == "pattern" && entry.type == "pattern" -> {
                val res = drawPattern(AppLockVault.secretToPattern(entry.secret))
                if (res == "OK") AppLockVault.resetFails(this, pkg)
            }
            (type == "pin" || type == "password") && entry.type != "pattern" -> {
                if (typeText(entry.secret) == "OK") {
                    var tapped = false
                    for (lbl in listOf("ok", "unlock", "submit", "done", "confirm", "enter")) {
                        if (tapText(lbl) == "OK") { tapped = true; break }
                    }
                    if (tapped) AppLockVault.resetFails(this, pkg)
                }
            }
        }
        // verify: 1.4s baad lock abhi bhi on hai? → fail
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (detectLockType() != "none") {
                    val fails = AppLockVault.bumpFail(this, pkg)
                    if (fails >= 2) onLockNeedsHelpStatic?.invoke(pkg, "old_lock_fail")
                } else {
                    AppLockVault.resetFails(this, pkg)
                }
            } catch (_: Exception) {}
        }, 1400)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AutoBotAccessibilityService? = null

        /** v4.3: MainActivity set karta hai — saved lock fail hone par user ko naya lock poochne ke liye */
        var onLockNeedsHelpStatic: ((String, String) -> Unit)? = null   // (pkg, reason)

        fun isOn(): Boolean = instance != null

        fun readScreen(): String {
            val svc = instance ?: return ""
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return ""
            val out = StringBuilder()
            collect(root, out, 0)
            return out.toString().trim()
        }

        /** Package of foreground window (e.g. com.android.chrome) */
        fun foregroundPackage(): String {
            val svc = instance ?: return ""
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return ""
            return root.packageName?.toString() ?: ""
        }

        // ---------- v4.3: lock-type detection + pattern drawing ----------
        /** Screen dekh kar batao: pattern lock hai, PIN, password, ya koi lock nahi */
        fun detectLockType(): String {
            val svc = instance ?: return "OFF"
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return "NO_WINDOW"
            if (findPatternRect(root) != null) return "pattern"
            val edit = findEditable(root) ?: return "none"
            val txt = readScreen().lowercase()
            return if (edit.isPassword) {
                if (txt.contains("pin") || txt.contains("पन") || txt.contains("पिन")) "pin" else "password"
            } else "password"
        }

        private fun findPatternRect(root: AccessibilityNodeInfo): Rect? {
            fun walk(n: AccessibilityNodeInfo, depth: Int): Rect? {
                if (depth > 30) return null
                val cn = n.className?.toString() ?: ""
                if (cn.contains("LockPatternView") || n.contentDescription?.toString()?.contains("pattern", true) == true) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 40 && r.height() > 40) return r
                }
                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                    walk(c, depth + 1)?.let { return it }
                }
                return null
            }
            return walk(root, 0)
        }

        /** Pattern draw karo — 3x3 dots (1-9) ke centers se gesture */
        fun drawPattern(seq: List<Int>): String {
            val svc = instance ?: return "OFF"
            if (seq.size < 3) return "SHORT"
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return "NO_WINDOW"
            val r = findPatternRect(root) ?: return "NO_PATTERN"
            val cw = r.width() / 3f
            val ch = r.height() / 3f
            val pts = ArrayList<Pair<Float, Float>>()
            for (n in seq) {
                if (n !in 1..9) return "BAD_SEQ"
                val idx = n - 1
                val cx = r.left + (idx % 3) * cw + cw / 2
                val cy = r.top + (idx / 3) * ch + ch / 2
                pts.add(cx to cy)
            }
            val path = Path()
            path.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
            val dur = 250L + 160L * pts.size
            val stroke = GestureDescription.StrokeDescription(path, 0, dur)
            val ok = svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            return if (ok) "OK" else "FAIL"
        }

        fun tapText(q: String): String {
            val svc = instance ?: return "OFF"
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return "NO_WINDOW"
            val target = findNode(root, q) ?: return "NOT_FOUND"
            var node: AccessibilityNodeInfo = target
            var hops = 0
            while (!node.isClickable && node.parent != null && hops < 8) {
                node = node.parent ?: break
                hops++
            }
            if (!node.isClickable) {
                // try gesture at node center
                val r = Rect()
                target.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    return tapXY(r.centerX().toFloat(), r.centerY().toFloat())
                }
                return "NOT_CLICKABLE"
            }
            return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "OK" else "FAIL"
        }

        fun tapXY(x: Float, y: Float): String {
            val svc = instance ?: return "OFF"
            val p = Path()
            p.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(p, 0, 50)
            val ok = svc.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), null, null
            )
            return if (ok) "OK" else "FAIL"
        }

        fun longPressText(q: String): String {
            val svc = instance ?: return "OFF"
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return "NO_WINDOW"
            val target = findNode(root, q) ?: return "NOT_FOUND"
            val r = Rect()
            target.getBoundsInScreen(r)
            val p = Path()
            p.moveTo(r.centerX().toFloat(), r.centerY().toFloat())
            val stroke = GestureDescription.StrokeDescription(p, 0, 600)
            val ok = svc.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), null, null
            )
            return if (ok) "OK" else "FAIL"
        }

        fun scroll(down: Boolean): String {
            val svc = instance ?: return "OFF"
            val w = svc.resources.displayMetrics
            val cx = w.widthPixels / 2f
            val y1 = if (down) w.heightPixels * 0.75f else w.heightPixels * 0.25f
            val y2 = if (down) w.heightPixels * 0.25f else w.heightPixels * 0.75f
            val p = Path()
            p.moveTo(cx, y1)
            p.lineTo(cx, y2)
            val stroke = GestureDescription.StrokeDescription(p, 0, 220)
            val ok = svc.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), null, null
            )
            return if (ok) "OK" else "FAIL"
        }

        fun swipeHorizontal(left: Boolean): String {
            val svc = instance ?: return "OFF"
            val w = svc.resources.displayMetrics
            val cy = w.heightPixels / 2f
            val x1 = if (left) w.widthPixels * 0.8f else w.widthPixels * 0.2f
            val x2 = if (left) w.widthPixels * 0.2f else w.widthPixels * 0.8f
            val p = Path()
            p.moveTo(x1, cy)
            p.lineTo(x2, cy)
            val stroke = GestureDescription.StrokeDescription(p, 0, 250)
            val ok = svc.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), null, null
            )
            return if (ok) "OK" else "FAIL"
        }

        /** Focused / editable field mein text set */
        fun typeText(text: String): String {
            val svc = instance ?: return "OFF"
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return "NO_WINDOW"
            val edit = findEditable(root) ?: return "NO_EDIT"
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "OK" else "FAIL"
        }

        fun goBack(): String {
            val svc = instance ?: return "OFF"
            return if (svc.performGlobalAction(GLOBAL_ACTION_BACK)) "OK" else "FAIL"
        }

        fun goHome(): String {
            val svc = instance ?: return "OFF"
            return if (svc.performGlobalAction(GLOBAL_ACTION_HOME)) "OK" else "FAIL"
        }

        fun recents(): String {
            val svc = instance ?: return "OFF"
            return if (svc.performGlobalAction(GLOBAL_ACTION_RECENTS)) "OK" else "FAIL"
        }

        fun notifications(): String {
            val svc = instance ?: return "OFF"
            return if (svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) "OK" else "FAIL"
        }

        private fun findEditable(n: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
            if (depth > 25) return null
            if (n.isEditable || n.className?.toString()?.contains("EditText") == true) return n
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                findEditable(c, depth + 1)?.let { return it }
            }
            return null
        }

        private fun findNode(n: AccessibilityNodeInfo, q: String, depth: Int = 0): AccessibilityNodeInfo? {
            if (depth > 25) return null
            val ql = q.trim().lowercase()
            n.text?.let { if (it.contains(ql, ignoreCase = true)) return n }
            n.contentDescription?.let { if (it.contains(ql, ignoreCase = true)) return n }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                findNode(c, q, depth + 1)?.let { return it }
            }
            return null
        }

        private fun collect(n: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
            if (depth > 25) return
            n.text?.let { t ->
                val s = t.toString().trim()
                if (s.isNotEmpty()) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(s)
                }
            }
            n.contentDescription?.let { t ->
                val s = t.toString().trim()
                if (s.isNotEmpty() && s != n.text?.toString()) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(s)
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                collect(c, out, depth + 1)
            }
        }
    }
}
