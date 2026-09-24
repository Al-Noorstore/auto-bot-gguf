package com.alnoor.autobot

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

// AutoBot PRO engine: Python 3.11 (Chaquopy) — lazy load, sirf py/pip command pe (low-RAM safe)
object PyEngine {
    val brand = "PRO"
    val hasPython = true
    val pyHint = "Python 3.11 BUILT-IN: 'py print(2+2)' | 'py import requests'\nPip: 'pip install <package>' (pure-python packages)"

    @Volatile private var starting = false
    private fun ensureStarted(ctx: Context, timeoutMs: Long = 15000): Boolean {
        if (Python.isStarted()) return true
        synchronized(this) {
            if (Python.isStarted()) return true
            if (starting) {
                val s = System.currentTimeMillis()
                while (starting && System.currentTimeMillis() - s < timeoutMs) Thread.sleep(150)
                return Python.isStarted()
            }
            starting = true
        }
        return try {
            Python.start(AndroidPlatform(ctx))
            starting = false
            true
        } catch (e: Throwable) {
            starting = false
            false
        }
    }

    fun runCode(ctx: Context, code: String, workdir: String?): String {
        if (!ensureStarted(ctx)) return "Python engine load nahi hui (RAM/storage kam hai) — low-RAM phone pe AutoBot LITE use karo"
        return try {
            Python.getInstance().getModule("runner").callAttr("run_code", code, workdir).toString()
        } catch (e: Exception) { "Python error: " + e.message }
    }

    fun pipInstall(ctx: Context, pkg: String, target: String): String {
        if (!ensureStarted(ctx)) return "Python engine load nahi hui (RAM/storage kam hai)"
        return try {
            Python.getInstance().getModule("runner").callAttr("pip_install", pkg, target).toString()
        } catch (e: Exception) { "pip error: " + e.message }
    }
}
