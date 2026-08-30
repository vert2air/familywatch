package com.example.familywatch

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object AlarmPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrating = false
    private val handler = Handler(Looper.getMainLooper())

    // OSの「無限リピート」機能に任せず、自前でループさせる。
    // こうすることで、機種依存でcancel()が効きにくい場合でも、
    // 次のループが来る前に確実に止められる。
    private val vibrateLoop = object : Runnable {
        override fun run() {
            if (!vibrating) return
            fireBuzz()
            handler.postDelayed(this, 1500)
        }
    }

    fun start(context: Context) {
        if (mediaPlayer != null) return // 既に鳴動中

        try {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_ALARM
            ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // 再生に失敗しても監視自体は続行
        }

        vibrating = true
        this.appContext = context.applicationContext
        handler.post(vibrateLoop)
    }

    private var appContext: Context? = null

    private fun fireBuzz() {
        val context = appContext ?: return
        val pattern = longArrayOf(0, 800, 400, 800)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {
            // バイブが失敗してもアラーム音は継続させる
        }
    }

    fun stop(context: Context) {
        mediaPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null

        vibrating = false
        handler.removeCallbacks(vibrateLoop)

        // 念のため、現在進行中のバイブも明示的にキャンセルしておく
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.cancel()
            }
        } catch (_: Exception) {
        }
    }
}
