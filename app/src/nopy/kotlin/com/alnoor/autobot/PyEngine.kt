package com.alnoor.autobot

// AutoBot LITE engine: Python nahi — chhota APK, low-RAM phones (Redmi 9C etc) ke liye stable
object PyEngine {
    val brand = "LITE"
    val hasPython = false
    val pyHint = "LITE build: Python/pip NAHI hai — shell commands ('run ...', 'cmd ...') sab chalengi. Python ke liye AutoBot PRO APK install karo."

    fun runCode(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context,
                @Suppress("UNUSED_PARAMETER") code: String,
                @Suppress("UNUSED_PARAMETER") workdir: String?): String =
        "Ye AutoBot LITE hai — Python isi version mein nahi hai. Shell commands sab chalengi ('run ...', 'cmd ...'), Python/pip ke liye AutoBot PRO APK install karo."

    fun pipInstall(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context,
                   @Suppress("UNUSED_PARAMETER") pkg: String,
                   @Suppress("UNUSED_PARAMETER") target: String): String =
        "pip sirf AutoBot PRO mein hai. LITE mein shell commands ('run ...', 'cmd ...') chalengi."
}
