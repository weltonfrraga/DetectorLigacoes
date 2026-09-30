package com.calldetector.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val NUM_BANDS = 12

private val BAND_EDGES_HZ = floatArrayOf(
    100f, 200f, 300f, 450f, 650f, 900f, 1250f, 1700f, 2300f, 3000f, 3800f, 5000f, 6500f
)

/** Características de UM quadro de áudio (~32 ms a 16 kHz). */
class FrameFeatures(
    /** Nível RMS em dBFS (0 dBFS = onda quadrada de escala cheia). */
    val rmsDb: Float,
    val peakDb: Float,
    /** Fator de crista (pico − RMS, dB). Batidas/estalos têm crista alta. */
    val crestDb: Float,
    /** Fração da energia abaixo de 250 Hz. */
    val lowRatio: Float,
    /** Fração da energia entre 300 e 3400 Hz (banda telefônica). */
    val voiceRatio: Float,
    /** Fração da energia acima de 3400 Hz. */
    val highRatio: Float,
    /** Pico/média do espectro em 200–4000 Hz (dB). Tons e voz ≈ 13–19 dB; ruído ≈ 7–8 dB. */
    val peakinessDb: Float,
    val dominantHz: Float,
    val centroidHz: Float,
    /** Fluxo espectral: soma das subidas positivas por banda vs. quadro anterior (dB, média). */
    val flux: Float,
    /** Densidade de potência (dB) por banda, NUM_BANDS bandas. */
    val bandDb: FloatArray
)

/** Extrai as características de cada quadro. Sem alocação de buffers por chamada (exceto o resultado). */
class FrameAnalyzer(val sampleRate: Int) {
    /** Tamanho do quadro = FFT (potência de 2 ≥ 20 ms). 512 amostras = 32 ms a 16 kHz. */
    val fftSize: Int = nextPow2((sampleRate * 0.02f).toInt())
    val frameMs: Float = fftSize * 1000f / sampleRate

    private val n2 = fftSize / 2
    private val binHz = sampleRate.toFloat() / fftSize
    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val power = FloatArray(n2 + 1)
    private val prevBandDb = FloatArray(NUM_BANDS)
    private var havePrev = false

    private fun bin(hz: Float): Int = (hz / binHz).roundToInt().coerceIn(1, n2)

    private val kLow = bin(250f)
    private val kVoiceLo = bin(300f)
    private val kVoiceHi = bin(3400f)
    private val kStructLo = bin(200f)
    private val kStructHi = min(bin(4000f), n2)
    private val kCentLo = bin(100f)
    private val edges = IntArray(BAND_EDGES_HZ.size)

    init {
        for (i in edges.indices) {
            var b = bin(BAND_EDGES_HZ[i])
            if (i > 0 && b <= edges[i - 1]) b = edges[i - 1] + 1
            edges[i] = min(b, n2)
        }
    }

    /** `samples` precisa ter exatamente [fftSize] amostras PCM 16 bits. */
    fun analyze(samples: ShortArray): FrameFeatures {
        val n = fftSize
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble()
        val mean = (sum / n).toFloat()

        var sumSq = 0.0
        var peak = 0f
        for (i in 0 until n) {
            val x = (samples[i].toFloat() - mean) / 32768f
            val a = abs(x)
            if (a > peak) peak = a
            sumSq += (x * x).toDouble()
            re[i] = x * window[i]
            im[i] = 0f
        }
        val rms = sqrt(sumSq / n).toFloat()
        val rmsDb = max(20f * log10(max(rms, 1e-6f)), -100f)
        val peakDb = max(20f * log10(max(peak, 1e-6f)), -100f)

        fft.transform(re, im)
        for (k in 0..n2) power[k] = re[k] * re[k] + im[k] * im[k]

        var total = 0.0
        for (k in 1..n2) total += power[k]
        total += 1e-12
        var low = 0.0
        for (k in 1 until kLow) low += power[k]
        var voice = 0.0
        for (k in kVoiceLo..kVoiceHi) voice += power[k]
        var high = 0.0
        for (k in (kVoiceHi + 1)..n2) high += power[k]

        // Pico / média em 200–4000 Hz ("peakiness") + frequência dominante
        var maxP = 0f
        var maxK = kStructLo
        var arith = 0.0
        for (k in kStructLo..kStructHi) {
            val p = power[k]
            arith += p
            if (p > maxP) {
                maxP = p
                maxK = k
            }
        }
        val cnt = kStructHi - kStructLo + 1
        val meanP = arith / cnt
        val peakinessDb = (10.0 * log10((maxP.toDouble() + 1e-12) / (meanP + 1e-12))).toFloat()

        var domHz = maxK * binHz
        if (maxK > 1 && maxK < n2) {
            val la = ln(power[maxK - 1] + 1e-12f)
            val lb = ln(power[maxK] + 1e-12f)
            val lc = ln(power[maxK + 1] + 1e-12f)
            val den = la - 2f * lb + lc
            if (den < -1e-6f) {
                domHz = (maxK + (0.5f * (la - lc) / den).coerceIn(-0.5f, 0.5f)) * binHz
            }
        }

        var num = 0.0
        var den2 = 0.0
        for (k in kCentLo..n2) {
            val p = power[k].toDouble()
            num += k * binHz * p
            den2 += p
        }
        val centroid = if (den2 > 1e-12) (num / den2).toFloat() else 0f

        // Bandas + fluxo espectral
        val bandDb = FloatArray(NUM_BANDS)
        var flux = 0f
        for (b in 0 until NUM_BANDS) {
            val lo = edges[b]
            val hi = edges[b + 1]
            var s = 0.0
            for (k in lo until hi) s += power[k]
            val c = max(1, hi - lo)
            bandDb[b] = (10.0 * log10(s / c + 1e-12)).toFloat()
            if (havePrev) flux += max(0f, bandDb[b] - prevBandDb[b])
        }
        flux = if (havePrev) flux / NUM_BANDS else 0f
        for (b in 0 until NUM_BANDS) prevBandDb[b] = bandDb[b]
        havePrev = true

        return FrameFeatures(
            rmsDb = rmsDb,
            peakDb = peakDb,
            crestDb = peakDb - rmsDb,
            lowRatio = (low / total).toFloat(),
            voiceRatio = (voice / total).toFloat(),
            highRatio = (high / total).toFloat(),
            peakinessDb = peakinessDb,
            dominantHz = domHz,
            centroidHz = centroid,
            flux = flux,
            bandDb = bandDb
        )
    }

    /** Zera o estado entre quadros (fluxo espectral). */
    fun reset() {
        havePrev = false
    }
}

private fun nextPow2(v: Int): Int {
    var p = 2
    while (p < v) p = p shl 1
    return p
}
