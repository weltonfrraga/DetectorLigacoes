package com.calldetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Testes de comportamento do [CallDetector] usando os mesmos tipos de sinal sintético usados para
 * validar o algoritmo em Python durante a prototipagem (ver README, seção "Como o detector decide").
 *
 * Estes testes são QUALITATIVOS (detecta / não detecta), não comparam números exatos de confiança:
 * o gerador de sinal em Kotlin (sem FFT/numpy) é só uma aproximação do gerador em Python, então os
 * valores de confiança variam um pouco — o que importa é o comportamento permanecer correto.
 */
class CallDetectorTest {

    private data class Result(val detected: Boolean, val atSec: Double, val confidence: Float)

    /** Roda o pipeline real (FrameAnalyzer + CallDetector) sobre um sinal PCM completo. */
    private fun run(pcm: DoubleArray, level: Int = 2, stepEnabled: Boolean = false): Result {
        val fa = FrameAnalyzer(SignalGen.SR)
        val det = CallDetector(DetectorConfig.forLevel(level, stepEnabled), fa.frameMs)
        val samples = SignalGen.toPcm16(pcm)
        var i = 0
        while (i + fa.fftSize <= samples.size) {
            val frame = samples.copyOfRange(i, i + fa.fftSize)
            val features = fa.analyze(frame)
            val out = det.process(features)
            val detection = out.detection
            if (detection != null) {
                return Result(true, i.toDouble() / SignalGen.SR, detection.confidence)
            }
            i += fa.fftSize
        }
        return Result(false, -1.0, 0f)
    }

    @Test
    fun silenceNeverDetects() {
        val rnd = Random(1)
        val sig = SignalGen.ambient(20.0, rnd, whiteDb = -90.0, rumbleDb = -85.0)
        val r = run(sig)
        assertFalse("silêncio não deveria disparar o alerta", r.detected)
    }

    @Test
    fun strongBeepIsDetected() {
        // Proxy para um tom de handshake/toque do headset: curto, tonal, bem acima do ruído.
        val rnd = Random(2)
        val sig = SignalGen.place(SignalGen.ambient(10.0, rnd), SignalGen.beep(0.5, -28.0), 5.0)
        val r = run(sig)
        assertTrue("bipe tonal forte deveria ser detectado", r.detected)
    }

    @Test
    fun phoneBandVoiceIsDetected() {
        // Harmônicos 3..25 de 120 Hz = energia concentrada na banda telefônica (300-3400 Hz),
        // simulando a voz de quem liga, tal como reproduzida pelo alto-falante do headset.
        val rnd = Random(3)
        val sig = SignalGen.place(SignalGen.ambient(10.0, rnd), SignalGen.harmonic(1.5, -34.0, 120.0, 3, 25), 5.0)
        val r = run(sig)
        assertTrue("voz em banda telefônica deveria ser detectada", r.detected)
    }

    @Test
    fun liveWidebandVoiceIsNotDetected() {
        // Harmônicos 1..40 = banda larga (muito grave + muito agudo), como a própria voz do
        // usuário captada de perto — NÃO deve disparar (é o principal falso positivo a evitar).
        val rnd = Random(4)
        val sig = SignalGen.place(SignalGen.ambient(10.0, rnd), SignalGen.harmonic(1.5, -30.0, 120.0, 1, 40), 5.0)
        val r = run(sig)
        assertFalse("voz ao vivo (banda larga) perto do microfone não deveria disparar", r.detected)
    }

    @Test
    fun tableKnockIsRejectedByDuration() {
        val rnd = Random(5)
        val sig = SignalGen.place(SignalGen.ambient(10.0, rnd), SignalGen.knock(-15.0, rnd), 5.0)
        val r = run(sig)
        assertFalse("batida na mesa (curta, impulsiva) não deveria disparar", r.detected)
    }

    @Test
    fun repeatedKnocksNeverDetect() {
        val rnd = Random(6)
        var sig = SignalGen.ambient(14.0, rnd)
        for (t in 1..10) sig = SignalGen.place(sig, SignalGen.knock(-14.0, rnd), t.toDouble())
        val r = run(sig)
        assertFalse("uma sequência de batidas não deveria disparar", r.detected)
    }

    @Test
    fun sustainedTrafficNoiseAtRecommendedLevelIsNotDetected() {
        // Ruído grave contínuo (tipo trânsito) começando DEPOIS do aquecimento do piso de ruído.
        val rnd = Random(7)
        val sig = SignalGen.place(SignalGen.ambient(12.0, rnd), SignalGen.bandNoise(4.0, -42.0, rnd, 40.0, 350.0), 5.0)
        val r = run(sig, level = 2)
        assertFalse("ruído grave sustentado não deveria disparar na sensibilidade recomendada", r.detected)
    }

    @Test
    fun notificationChimeIsDetected_knownFalsePositive() {
        // Limitação DOCUMENTADA (ver README): o chime de notificação do próprio celular é
        // acusticamente parecido com um tom curto de handshake do headset. Este teste registra o
        // comportamento atual (detecta) para que a limitação não seja "perdida" silenciosamente.
        val rnd = Random(8)
        val sig = SignalGen.place(
            SignalGen.ambient(10.0, rnd),
            SignalGen.beep(0.5, -22.0, doubleArrayOf(1300.0, 1700.0), doubleArrayOf(1.0, 0.8)),
            5.0
        )
        val r = run(sig)
        assertTrue(
            "limitação conhecida: chime de notificação é confundido com handshake (ver README) — " +
                "se este teste passar a falhar, o algoritmo pode ter melhorado; atualize o README",
            r.detected
        )
    }

    @Test
    fun sensitivityThresholdsDecreaseMonotonically() {
        var prev = 1.01f
        for (level in 0..4) {
            val thr = DetectorConfig.forLevel(level).threshold
            assertTrue("limiar do nível $level deveria ser menor que o do nível anterior", thr < prev)
            prev = thr
        }
    }
}
