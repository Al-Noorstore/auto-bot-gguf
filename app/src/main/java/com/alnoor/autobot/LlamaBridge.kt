package com.alnoor.autobot

/**
 * LlamaBridge — llama.cpp JNI bridge (libautobot-jni.so).
 * CI mein llama.cpp v0.5.0 se build hoti hai; load fail ho to GgufEngine handle karta hai.
 */
object LlamaBridge {

    @Volatile var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("autobot-jni"); true
        } catch (e: UnsatisfiedLinkError) { false }
    }

    fun load(path: String, ctxSize: Int = 2048, threads: Int = 0): Boolean =
        available && nativeLoad(path, ctxSize, threads)

    fun isLoaded(): Boolean = available && nativeIsLoaded()

    fun free() { if (available) nativeFree() }

    /** native layer ka aakhri error — deep diagnosis */
    fun lastError(): String = if (available) try { nativeLastError() } catch (_: Exception) { "?" } else "native lib missing"

    /** blocking call — background thread se bulao */
    fun generate(prompt: String, maxTokens: Int = 200, temp: Float = 0.7f, topP: Float = 0.9f, topK: Int = 40): String =
        if (available) nativeGenerate(prompt, maxTokens, temp, topP, topK) else ""

    private external fun nativeLoad(path: String, ctxSize: Int, threads: Int): Boolean
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeFree()
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temp: Float, topP: Float, topK: Int): String
    private external fun nativeLastError(): String
}
