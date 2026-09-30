package com.calldetector.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Alarme sonoro + vibração. O som é SINTETIZADO aqui (dois tons alternados, estridentes), sem
 * depender de arquivos ou do toque padrão do aparelho. Usa o canal de ALARME (toca mesmo com o
 * celular no silencioso) e leva o volume de alarme ao máximo enquanto durar, restaurando depois.
 */
class AlertPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var track: AudioTrack? = null
    private var savedAlarmVolume = -1
    private var playing = false

    val isPlaying: Boolean get() = playing

    fun start() {
        if (playing) return
        playing = true
        raiseVolume()
        startSound()
        startVibration()
    }

    fun stop() {
        if (!playing) return
        playing = false
        try {
            track?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "track.stop: ${e.message}")
        }
        try {
            track?.release()
        } catch (e: Exception) {
            Log.w(TAG, "track.release: ${e.message}")
        }
        track = null
        try {
            vibrator()?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "vibrator.cancel: ${e.message}")
        }
        restoreVolume()
    }

    private fun raiseVolume() {
        try {
            savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (e: Exception) {
            // Ex.: SecurityException em alguns modos "Não perturbe". O alarme ainda toca no volume atual.
            Log.w(TAG, "não foi possível elevar o volume de alarme: ${e.message}")
        }
    }

    private fun restoreVolume() {
        if (savedAlarmVolume < 0) return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "não foi possível restaurar o volume: ${e.message}")
        }
        savedAlarmVolume = -1
    }

    private fun startSound() {
        try {
            val pcm = buildAlarmPcm()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1) // repete até stop()
            t.setVolume(1.0f)
            t.play()
            track = t
        } catch (e: Exception) {
            Log.e(TAG, "falha ao tocar o alarme", e)
        }
    }

    private fun vibrator() = appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v = vibrator() ?: return
            if (!v.hasVibrator()) return
            val pattern = longArrayOf(0, 700, 250, 700, 250, 1000, 600)
            val effect = VibrationEffect.createWaveform(pattern, 0) // 0 = repete do início
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            v.vibrate(effect, attrs)
        } catch (e: Exception) {
            Log.w(TAG, "falha ao vibrar: ${e.message}")
        }
    }

    /** ~2,7 s: 4 pares de bipes (880 Hz / 1320 Hz) + pausa. Onda com 3º harmônico = timbre estridente. */
    private fun buildAlarmPcm(): ShortArray {
        val beepMs = 180
        val gapMs = 40
        val pauseMs = 900
        val beepN = SAMPLE_RATE * beepMs / 1000
        val gapN = SAMPLE_RATE * gapMs / 1000
        val pauseN = SAMPLE_RATE * pauseMs / 1000
        val pairs = 4
        val total = pairs * 2 * (beepN + gapN) + pauseN
        val out = ShortArray(total)
        var pos = 0
        val freqs = doubleArrayOf(880.0, 1320.0)
        for (p in 0 until pairs) {
            for (fi in 0..1) {
                val f = freqs[fi]
                for (i in 0 until beepN) {
                    val t = i.toDouble() / SAMPLE_RATE
                    // envelope curto para evitar "clique" no início/fim de cada bipe
                    val edge = minOf(i, beepN - 1 - i).toDouble()
                    val env = minOf(1.0, edge / (SAMPLE_RATE * 0.004))
                    val s = sin(2.0 * PI * f * t) + 0.35 * sin(2.0 * PI * 3.0 * f * t)
                    out[pos + i] = (s / 1.35 * env * 0.95 * Short.MAX_VALUE).toInt().toShort()
                }
                pos += beepN + gapN
            }
        }
        return out
    }

    private companion object {
        const val TAG = "AlertPlayer"
        const val SAMPLE_RATE = 44100
    }
}
