package com.calldetector.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Geradores de sinal sintético para os testes de unidade, espelhando os mesmos sinais usados para
 * validar o algoritmo em Python (ver /proto/proto.py da fase de prototipagem) — silêncio, bipe,
 * "voz telefônica" (banda limitada), voz "ao vivo" (banda larga, simula o próprio usuário falando
 * perto do celular), batida de mesa e ruído de banda. Isto permite testar o [CallDetector] real
 * (Kotlin) sem precisar de um aparelho Android.
 */
object SignalGen {
    const val SR = 16000

    fun dbfs(x: DoubleArray, db: Double): DoubleArray {
        var sumSq = 0.0
        for (v in x) sumSq += v * v
        val rms = sqrt(sumSq / x.size) + 1e-12
        val target = Math.pow(10.0, db / 20.0) / rms
        return DoubleArray(x.size) { x[it] * target }
    }

    fun ambient(sec: Double, rnd: Random, whiteDb: Double = -82.0, rumbleDb: Double = -74.0): DoubleArray {
        val n = (sec * SR).toInt()
        val white = dbfs(DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) * gauss(rnd) }, whiteDb)
        val raw = DoubleArray(n) { gauss(rnd) }
        val k = 64
        val smoothed = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            acc += raw[i]
            if (i >= k) acc -= raw[i - k]
            smoothed[i] = acc / min(k, i + 1)
        }
        val rumble = dbfs(smoothed, rumbleDb)
        return DoubleArray(n) { white[it] + rumble[it] }
    }

    private fun gauss(rnd: Random): Double {
        // Box-Muller simples (não precisa ser perfeito, só precisa "parecer" ruído).
        val u1 = max(1e-12, rnd.nextDouble())
        val u2 = rnd.nextDouble()
        return sqrt(-2.0 * ln(u1)) * sin(2.0 * PI * u2)
    }

    fun fade(x: DoubleArray, ms: Double = 8.0): DoubleArray {
        val m = (ms * SR / 1000).toInt().coerceAtMost(x.size / 2)
        val out = x.copyOf()
        for (i in 0 until m) {
            val g = i.toDouble() / m
            out[i] = out[i] * g
            out[out.size - 1 - i] = out[out.size - 1 - i] * g
        }
        return out
    }

    fun beep(sec: Double, db: Double, freqs: DoubleArray = doubleArrayOf(1000.0, 2000.0), amps: DoubleArray = doubleArrayOf(1.0, 0.3)): DoubleArray {
        val n = (sec * SR).toInt()
        val x = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var s = 0.0
            for (j in freqs.indices) s += amps[j] * sin(2.0 * PI * freqs[j] * t)
            x[i] = s
        }
        return fade(dbfs(x, db))
    }

    /** hmin..hmax harmônicos de f0. hmin=3 => "telefônico" (grave cortado); hmin=1 => "ao vivo" (banda larga). */
    fun harmonic(sec: Double, db: Double, f0: Double, hmin: Int, hmax: Int, amHz: Double = 4.0): DoubleArray {
        val n = (sec * SR).toInt()
        val x = DoubleArray(n)
        var phaseAcc = 0.0
        val f0t = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            f0t[i] = f0 * (1 + 0.1 * sin(2.0 * PI * 0.7 * t))
        }
        val ph = DoubleArray(n)
        for (i in 0 until n) {
            phaseAcc += 2.0 * PI * f0t[i] / SR
            ph[i] = phaseAcc
        }
        for (k in hmin..hmax) {
            if (k * f0 * 1.1 >= SR / 2.0) continue
            for (i in 0 until n) x[i] = x[i] + (1.0 / k) * sin(k * ph[i])
        }
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = 0.55 + 0.45 * sin(2.0 * PI * amHz * t)
            x[i] = x[i] * env
        }
        return fade(dbfs(x, db), 15.0)
    }

    fun knock(db: Double, rnd: Random, tau: Double = 0.012): DoubleArray {
        val n = (0.15 * SR).toInt()
        val raw = DoubleArray(n) { gauss(rnd) }
        val lp = 800
        val k = max(1, SR / lp)
        val smoothed = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            acc += raw[i]
            if (i >= k) acc -= raw[i - k]
            smoothed[i] = acc / min(k, i + 1)
        }
        var peak = 1e-9
        val env = DoubleArray(n)
        for (i in 0 until n) {
            env[i] = smoothed[i] * exp(-i.toDouble() / SR / tau)
            peak = max(peak, kotlin.math.abs(env[i]))
        }
        val target = Math.pow(10.0, db / 20.0)
        return DoubleArray(n) { env[it] / peak * target }
    }

    fun bandNoise(sec: Double, db: Double, rnd: Random, lo: Double, hi: Double): DoubleArray {
        // Aproximação simples de ruído de banda: soma de senoides com fase aleatória espaçadas em [lo,hi].
        val n = (sec * SR).toInt()
        val x = DoubleArray(n)
        val steps = 40
        for (s in 0 until steps) {
            val f = lo + (hi - lo) * s / steps
            val phase = rnd.nextDouble(0.0, 2.0 * PI)
            for (i in 0 until n) x[i] = x[i] + sin(2.0 * PI * f * i / SR + phase)
        }
        return fade(dbfs(x, db))
    }

    fun place(base: DoubleArray, event: DoubleArray, atSec: Double): DoubleArray {
        val out = base.copyOf()
        val i0 = (atSec * SR).toInt()
        for (i in event.indices) {
            if (i0 + i >= out.size) break
            out[i0 + i] = out[i0 + i] + event[i]
        }
        return out
    }

    /** Converte para PCM16 (quantizado, como o microfone real entregaria). */
    fun toPcm16(x: DoubleArray): ShortArray =
        ShortArray(x.size) { i -> (x[i].coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort() }
}
