package com.alnoor.autobot

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * v3.3: Auto Bot Accessibility — screen padhna (aur aage click/scroll).
 * User khud enable karta hai: Settings → Accessibility → Auto Bot.
 */
class AutoBotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // abhi sirf service zinda rehna hai; capture minimal
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AutoBotAccessibilityService? = null

        fun isOn(): Boolean = instance != null

        /** screen ka saara visible text (recursive) */
        fun readScreen(): String {
            val svc = instance ?: return ""
            val root = try { svc.rootInActiveWindow } catch (e: Exception) { null } ?: return ""
            val out = StringBuilder()
            collect(root, out, 0)
            return out.toString().trim()
        }

        /** screen par text/desc se node dhoondo aur click karo */
        fun tapText(q: String): String {
            val svc = instance ?: return "OFF"
            val root = try { svc.rootInActiveWindow } catch (e: Exception) { null } ?: return "NO_WINDOW"
            val target = findNode(root, q) ?: return "NOT_FOUND"
            var node = target
            var hops = 0
            while (!node.isClickable && node.parent != null && hops < 8) { node = node.parent ?: break; hops++ }
            if (!node.isClickable) return "NOT_CLICKABLE"
            return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "OK" else "FAIL"
        }

        private fun findNode(n: AccessibilityNodeInfo, q: String, depth: Int = 0): AccessibilityNodeInfo? {
            if (depth > 25) return null
            val ql = q.trim().lowercase()
            n.text?.let { if (it.contains(ql, ignoreCase = true)) return n }
            n.contentDescription?.let { if (it.contains(ql, ignoreCase = true)) return n }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                findNode(c, q, depth + 1)?.let { return it }
            }
            return null
        }

        /** swipe gesture se scroll */
        fun scroll(down: Boolean): String {
            val svc = instance ?: return "OFF"
            val w = android.util.DisplayMetrics().let { svc.resources.displayMetrics }
            val cx = w.widthPixels / 2f
            val y1 = if (down) w.heightPixels * 0.75f else w.heightPixels * 0.25f
            val y2 = if (down) w.heightPixels * 0.25f else w.heightPixels * 0.75f
            val path = android.accessibilityservice.GestureDescription.StrokeDescription(cx, y1, cx, y2, 220)
            val ok = svc.dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(path).build(), null, null)
            return if (ok) "OK" else "FAIL"
        }

        fun goBack(): String {
            val svc = instance ?: return "OFF"
            return if (svc.performGlobalAction(GLOBAL_ACTION_BACK)) "OK" else "FAIL"
        }

        fun goHome(): String {
            val svc = instance ?: return "OFF"
            return if (svc.performGlobalAction(GLOBAL_ACTION_HOME)) "OK" else "FAIL"
        }

        private fun collect(n: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
            if (depth > 25) return
            n.text?.let { t -> val s = t.toString().trim(); if (s.isNotEmpty()) { if (out.isNotEmpty()) out.append('\n'); out.append(s) } }
            n.contentDescription?.let { t -> val s = t.toString().trim(); if (s.isNotEmpty() && s != n.text?.toString()) { if (out.isNotEmpty()) out.append('\n'); out.append(s) } }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                collect(c, out, depth + 1)
            }
        }
    }
}
