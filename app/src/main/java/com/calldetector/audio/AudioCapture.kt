package com.calldetector.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Captura de microfone em thread própria. Entrega quadros de EXATAMENTE [frameSize] amostras
 * (PCM 16 bits, mono) ao [listener]. Nada é gravado em disco nem enviado para fora: cada quadro é
 * analisado e descartado.
 *
 * Fonte de áudio: tenta UNPROCESSED (sem AGC/redução de ruído, que distorceriam o nível), depois
 * VOICE_RECOGNITION e por fim MIC. A fonte realmente usada fica em [sourceName] (aparece no Diagnóstico).
 */
class AudioCapture(
    private val sampleRate: Int,
    private val frameSize: Int,
    private val listener: Listener
) {
    interface Listener {
        /** Chamado na thread de áudio. Devolva `false` para encerrar a captura imediatamente. */
        fun onFrame(samples: ShortArray): Boolean

        /** Chamado na thread de áudio se a captura falhar (ex.: microfone ocupado). */
        fun onError(message: String)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = ArrayList<android.media.audiofx.AudioEffect>()

    @Volatile var sourceName: String = "-"
        private set

    /** true enquanto o AudioRecord estiver aberto (microfone em uso pelo app). */
    @Volatile var micOpen: Boolean = false
        private set

    /** Abre o microfone e inicia a thread. Retorna false se não conseguiu abrir. */
    @SuppressLint("MissingPermission") // a permissão é verificada antes, no serviço
    fun start(): Boolean {
        if (running) return true
        val rec = openRecord() ?: return false
        record = rec
        disableEffects(rec)
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording falhou", e)
            rec.release()
            record = null
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            record = null
            return false
        }
        micOpen = true
        running = true
        thread = Thread({ loop(rec) }, "audio-capture").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return true
    }

    /** Pede parada e espera a thread terminar (o microfone é liberado antes de retornar). */
    fun stopAndJoin(timeoutMs: Long = 1500) {
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(timeoutMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize inválido: $minBuf")
            return null
        }
        val bufBytes = maxOf(minBuf, frameSize * 2 * 8)
        val sources = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC"
        )
        for ((src, name) in sources) {
            try {
                val rec = AudioRecord(
                    src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    sourceName = name
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w(TAG, "fonte $name indisponível: ${e.message}")
            }
        }
        return null
    }

    /** Desliga AGC / redução de ruído / cancelamento de eco se o aparelho aplicar por cima da fonte. */
    private fun disableEffects(rec: AudioRecord) {
        val id = rec.audioSessionId
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(id)?.let { it.enabled = false; effects.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AGC indisponível: ${e.message}")
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(id)?.let { it.enabled = false; effects.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "redução de ruído indisponível: ${e.message}")
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(id)?.let { it.enabled = false; effects.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancelamento de eco indisponível: ${e.message}")
        }
    }

    private fun releaseEffects() {
        for (e in effects) {
            try {
                e.release()
            } catch (ex: Exception) {
                Log.w(TAG, "release efeito: ${ex.message}")
            }
        }
        effects.clear()
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(frameSize)
        try {
            while (running) {
                var got = 0
                while (got < frameSize && running) {
                    val n = rec.read(buf, got, frameSize - got, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        listener.onError("Falha ao ler o microfone (código $n)")
                        running = false
                        return
                    }
                    got += n
                }
                if (!running) break
                if (!listener.onFrame(buf)) {
                    running = false
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "erro na thread de áudio", e)
            listener.onError("Erro no microfone: ${e.message}")
        } finally {
            // O microfone é liberado AQUI, na própria thread, antes de qualquer alarme tocar.
            try {
                rec.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stop(): ${e.message}")
            }
            releaseEffects()
            rec.release()
            micOpen = false
            running = false
        }
    }

    private companion object {
        const val TAG = "AudioCapture"
    }
}
