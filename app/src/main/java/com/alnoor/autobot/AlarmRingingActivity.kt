package com.alnoor.autobot

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v3.0 — Alarm ringing screen: loud alarm sound, Snooze 5 min, Stop.
 * Screen lock ke upar bhi khulta hai (showWhenLocked + turnScreenOn).
 */
class AlarmRingingActivity : Activity() {

    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra("label") ?: "Alarm"
        val id = intent.getIntExtra("id", 0)

        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true)
        else window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(-0x1000000)
        root.setPadding(48, 96, 48, 96)

        val title = TextView(this)
        title.text = "⏰ $label"
        title.textSize = 32f
        title.setTextColor(-0x1)
        title.setPadding(0, 0, 0, 48)
        root.addView(title)

        val stopBtn = Button(this)
        stopBtn.text = "🔴 Band karo"
        stopBtn.textSize = 18f
        stopBtn.setOnClickListener {
            stopAlarm()
            finish()
        }
        root.addView(stopBtn)

        val snoozeBtn = Button(this)
        snoozeBtn.text = "😴 Snooze 5 min"
        snoozeBtn.textSize = 18f
        snoozeBtn.setOnClickListener {
            stopAlarm()
            AlarmEngine.scheduleTimer(this, 5 * 60, "Snooze")
            finish()
        }
        root.addView(snoozeBtn)

        setContentView(root)
        playAlarm()
    }

    private fun playAlarm() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM) / 2, 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer()
            player?.setDataSource(this, uri)
            player?.setAudioStreamType(AudioManager.STREAM_ALARM)
            player?.isLooping = true
            player?.prepare()
            player?.start()
        } catch (e: Exception) {
            try { player?.start() } catch (_: Exception) {}
        }
    }

    private fun stopAlarm() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }
}
