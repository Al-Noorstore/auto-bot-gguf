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
