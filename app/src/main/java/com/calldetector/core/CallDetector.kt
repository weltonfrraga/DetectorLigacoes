package com.calldetector.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Estados internos do detector (não confundir com [Phase], que é o estado do aplicativo). */
enum class DetectorState { WARMUP, IDLE, CANDIDATE, STEP_WATCH, DETECTED }

/** Decomposição da pontuação de um evento — é o "motivo da classificação" mostrado no Diagnóstico. */
class Breakdown(
    val level: Float,
    val onset: Float,
    val sustain: Float,
    val band: Float,
    val structure: Float,
    val impulsive: Float,
    val gate: Float,
    val calibMatch: Float?,
    val total: Float,
    val aboveDb: Float,
    val riseDb: Float,
    val lowRatio: Float,
    val peakinessDb: Float
) {
    fun describe(): String {
        val cal = if (calibMatch == null) "" else " calib=" + fmt(calibMatch)
        return "nível=${fmt(level)} subida=${fmt(onset)} duração=${fmt(sustain)} " +
            "banda=${fmt(band)} estrutura=${fmt(structure)} impulso=${fmt(impulsive)} " +
            "gate=${fmt(gate)}$cal | +${"%.1f".format(Locale.US, aboveDb)} dB, " +
            "subida ${"%.1f".format(Locale.US, riseDb)} dB, graves ${fmt(lowRatio)}, " +
            "pico/média ${"%.1f".format(Locale.US, peakinessDb)} dB → ${fmt(total)}"
    }

    /** Frase curta apontando o critério mais fraco (por que a confiança ficou baixa). */
    fun weakness(): String {
        if (impulsive > 0.5f) return "som impulsivo (batida/estalo)"
        if (band < 0.3f) return "energia fora da banda telefônica (muito grave ou sem conteúdo em 300–3400 Hz)"
        if (structure < 0.3f) return "espectro largo/sem picos (ruído, não tom nem voz estruturada)"
        if (level < 0.3f) return "pouco acima do ruído de fundo"
        if (onset < 0.3f) return "sem subida brusca (som já vinha aumentando/estava presente)"
        if (sustain < 0.5f) return "duração curta"
        return "conjunto de critérios abaixo do limiar"
    }
}

/** Detecção confirmada. */
class Detection(val kind: String, val confidence: Float, val breakdown: Breakdown?)

/** Resultado do processamento de UM quadro. */
class DetectorOutput(
    val state: DetectorState,
    /** Piso de ruído estimado (dBFS). */
    val floorDb: Float,
    /** Confiança do candidato em andamento (0 se não houver). */
    val confidence: Float,
    /** Maior confiança vista no candidato atual/último. */
    val bestConfidence: Float,
    /** Não-nulo apenas no quadro em que o detector dispara. */
    val detection: Detection?,
    /** Mensagem de diagnóstico (rejeições, etc.). */
    val note: String?
)

internal fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

/**
 * Detector de "início de evento acústico compatível com chamada recebida no headset".
 *
 * NÃO reconhece fala nem palavras. Ele mede, quadro a quadro (~32 ms):
 *  1. nível (RMS) em relação a um piso de ruído adaptativo;
 *  2. subida brusca (onset) em relação a ~1 s imediatamente anterior;
 *  3. duração sustentada (descarta batidas e cliques);
 *  4. razão de energia na banda telefônica (300–3400 Hz) — áudio de headset/telefonia é limitado
 *     a essa banda, voz ao vivo perto do microfone tem muito mais graves e agudos;
 *  5. estrutura espectral (picos de tons/harmônicos vs. ruído largo);
 *  6. fator de crista (batidas/estalos têm pico muito acima do RMS).
 *
 * Um "candidato" nasce quando nível+subida passam nos mínimos; é confirmado assim que a pontuação
 * combinada atinge o limiar (latência típica: 150–400 ms) e rejeitado se o som acaba, é curto
 * demais ou não convence em 1,5 s. Depois de disparar, o detector para de processar (DETECTED).
 *
 * Não é thread-safe: use sempre da mesma thread (a thread de áudio).
 */
class CallDetector(
    initialConfig: DetectorConfig,
    private val frameMs: Float,
    private var calibration: Calibration? = null
) {
    var state: DetectorState = DetectorState.WARMUP
        private set
    var config: DetectorConfig = initialConfig
        private set

    private val warmFrames = ceil(WARMUP_MS / frameMs).toInt()
    private val upAlpha = 1f - exp(-frameMs / 12000f)   // piso sobe devagar (τ = 12 s)
    private val downAlpha = 1f - exp(-frameMs / 600f)   // piso desce rápido (τ = 0,6 s)
    private val avgAlpha = 1f - exp(-frameMs / 300f)    // média curta do nível (τ = 0,3 s)

    private var needFrames = 2
    private var minLevel = -200f

    private var floor = -100f
    private var avg = -100f
    private var floorInit = false
    private var warmCount = 0

    private val hist = FloatArray(HIST_SIZE)
    private var histN = 0

    // ---- estado do candidato ----
    private var cFrames = 0
    private var cActive = 0
    private var cIdle = 0
    private var cFloor = 0f
    private var cRise = 0f
    private var sLevel = 0f
    private var sVoice = 0f
    private var sLow = 0f
    private var sHigh = 0f
    private var sPeaky = 0f
    private var sCrest = 0f
    private val sShape = FloatArray(NUM_BANDS)
    private var bestConf = 0f
    private var lastBreakdown: Breakdown? = null

    // ---- modo experimental "degrau de ruído" ----
    private var stepFrames = 0
    private var stepFloor = 0f
    private var stepDev = 0f

    // ---- saída do quadro corrente ----
    private var pendNote: String? = null
    private var pendConf = 0f
    private var pendDet: Detection? = null

    init {
        applyConfig()
    }

    private fun applyConfig() {
        needFrames = max(2, ceil(config.minDurationMs / frameMs).toInt())
        minLevel = config.absMinLevelDb
    }

    /** Troca a sensibilidade sem reiniciar o aprendizado do piso de ruído. */
    fun setConfig(newConfig: DetectorConfig) {
        config = newConfig
        applyConfig()
    }

    fun setCalibration(c: Calibration?) {
        calibration = c
    }

    /** Recomeça o aprendizado do ruído de fundo (usado quando o microfone volta de um silenciamento). */
    fun reset() {
        state = DetectorState.WARMUP
        warmCount = 0
        floorInit = false
        histN = 0
        bestConf = 0f
        lastBreakdown = null
    }

    fun process(f: FrameFeatures): DetectorOutput {
        pendNote = null
        pendConf = 0f
        pendDet = null
        if (state == DetectorState.DETECTED) return output()

        val db = f.rmsDb
        if (!floorInit) {
            floor = db
            avg = db
            floorInit = true
        }

        when (state) {
            DetectorState.WARMUP -> {
                // Nos primeiros ~2 s só aprende o ruído de fundo.
                floor += (db - floor) * 0.08f
                avg += (db - avg) * avgAlpha
                warmCount++
                if (warmCount >= warmFrames) state = DetectorState.IDLE
            }
            DetectorState.CANDIDATE -> stepCandidate(f)
            else -> stepIdle(f)
        }

        pushHist(db)
        return output()
    }

    private fun stepIdle(f: FrameFeatures) {
        val db = f.rmsDb
        avg += (db - avg) * avgAlpha
        val eff = max(floor, -85f)
        val above = db - eff
        var started = false
        if (above >= config.minAboveFloorDb && db >= minLevel && histN >= 11) {
            val rise = db - preMean()
            if (rise >= config.onsetRiseDb) {
                startCandidate(f, eff, rise)
                started = true
            }
        }
        if (!started) {
            if (config.stepEnabled) stepLogic(db, eff)
            if (state != DetectorState.STEP_WATCH && state != DetectorState.DETECTED) {
                // Piso assimétrico: desce rápido, sobe devagar (evento real não "vira" ruído de fundo).
                val a = if (db < floor) downAlpha else upAlpha
                floor += (db - floor) * a
            }
        }
    }

    private fun startCandidate(f: FrameFeatures, eff: Float, rise: Float) {
        state = DetectorState.CANDIDATE
        cFrames = 1
        cActive = 1
        cIdle = 0
        cFloor = eff
        cRise = rise
        sLevel = 0f
        sVoice = 0f
        sLow = 0f
        sHigh = 0f
        sPeaky = 0f
        sCrest = 0f
        java.util.Arrays.fill(sShape, 0f)
        bestConf = 0f
        lastBreakdown = null
        accumulate(f)
    }

    private fun accumulate(f: FrameFeatures) {
        sLevel += f.rmsDb
        sVoice += f.voiceRatio
        sLow += f.lowRatio
        sHigh += f.highRatio
        sPeaky += f.peakinessDb
        // Crista: só os 3 primeiros quadros ativos (o início do evento é o que distingue batida de tom).
        if (cActive <= 3) sCrest += f.crestDb / 3f
        var mean = 0f
        for (v in f.bandDb) mean += v
        mean /= NUM_BANDS
        for (i in 0 until NUM_BANDS) sShape[i] += f.bandDb[i] - mean
    }

    private fun stepCandidate(f: FrameFeatures) {
        val db = f.rmsDb
        cFrames++
        val active = db >= cFloor + max(config.minAboveFloorDb - 6f, 4f) && db >= minLevel - 6f
        if (active) {
            cActive++
            cIdle = 0
            accumulate(f)
        } else {
            cIdle++
        }
        val elapsedMs = cFrames * frameMs

        if (cIdle >= 2) {
            // O som acabou.
            pendNote = if (cActive < needFrames) {
                "rejeitado: som curto demais (${(cActive * frameMs).toInt()} ms < ${config.minDurationMs} ms)"
            } else {
                rejectMessage()
            }
            state = DetectorState.IDLE
            return
        }

        if (cActive >= needFrames) {
            val bk = evaluate()
            bestConf = max(bestConf, bk.total)
            pendConf = bk.total
            lastBreakdown = bk
            if (bk.total >= config.threshold) {
                pendDet = Detection("evento acústico", bk.total, bk)
                state = DetectorState.DETECTED
                return
            }
            if (elapsedMs >= CANDIDATE_TIMEOUT_MS) {
                pendNote = rejectMessage()
                state = DetectorState.IDLE
            }
        } else if (elapsedMs >= CANDIDATE_TIMEOUT_MS) {
            pendNote = "rejeitado: não se sustentou (tempo esgotado)"
            state = DetectorState.IDLE
        }
    }

    private fun rejectMessage(): String {
        val bk = lastBreakdown
        val why = if (bk != null) " — ${bk.weakness()} [${bk.describe()}]" else ""
        return "rejeitado: confiança máx ${fmt(bestConf)} < limiar ${fmt(config.threshold)}$why"
    }

    private fun evaluate(): Breakdown {
        val n = max(1, cActive).toFloat()
        val meanAbove = sLevel / n - cFloor
        val level = clamp01(0.5f + (meanAbove - config.minAboveFloorDb) / 24f)
        val onset = clamp01(0.5f + (cRise - config.onsetRiseDb) / 20f)
        val sustainedFraction = cActive.toFloat() / max(1, cFrames)
        val sustain = clamp01(cActive * frameMs / (2f * config.minDurationMs)) * sustainedFraction

        val voice = sVoice / n
        val low = sLow / n
        val high = sHigh / n
        val band = clamp01((voice + 0.5f * high - 0.20f) / 0.40f) *
            (1f - 0.8f * clamp01((low - 0.40f) / 0.35f))

        val peaky = sPeaky / n
        val structure = clamp01((peaky - 9f) / 4f)
        val impulsive = clamp01((sCrest - 17f) / 7f)

        val base = 0.2f * (level + onset + sustain + band + structure)
        val gate = 0.35f + 0.65f * sqrt(min(band, structure))

        // Calibração OPCIONAL: apenas ajuste suave (0,85 … 1,15). Nunca "zera" um evento que não se
        // pareça com os perfis gravados — evita falso-negativo se o som real for um pouco diferente.
        var factor = 1f
        var match: Float? = null
        val cal = calibration
        if (cal != null) {
            val shape = FloatArray(NUM_BANDS) { sShape[it] / n }
            val cs = cal.matchScore(shape)
            val m = clamp01((cs - 0.5f) / 0.45f)
            match = m
            factor = 0.85f + 0.30f * m
        }

        val total = clamp01(base * gate * (1f - 0.7f * impulsive) * factor)
        return Breakdown(
            level, onset, sustain, band, structure, impulsive, gate, match, total,
            meanAbove, cRise, low, peaky
        )
    }

    /**
     * EXPERIMENTAL (desligado por padrão): ligação "silenciosa". Se o ruído de fundo sobe de forma
     * ESTÁVEL por ≥ 4 s (chiado de linha do headset), dispara. Tende a errar com mudanças reais de
     * ambiente (geladeira, ar-condicionado, TV). Critério próprio, não usa o limiar de confiança.
     */
    private fun stepLogic(db: Float, eff: Float) {
        if (state == DetectorState.IDLE) {
            if (avg - eff >= config.stepDb) {
                state = DetectorState.STEP_WATCH
                stepFrames = 0
                stepFloor = eff
                stepDev = 0f
            }
        } else {
            stepFrames++
            stepDev += (abs(db - avg) - stepDev) * 0.05f
            if (avg - stepFloor < 3f) {
                state = DetectorState.IDLE
                return
            }
            if (stepFrames * frameMs >= STEP_HOLD_MS) {
                if (stepDev <= 2.5f) {
                    pendDet = Detection("aumento sustentado de ruído", 0.5f, null)
                    pendConf = 0.5f
                    state = DetectorState.DETECTED
                } else {
                    state = DetectorState.IDLE
                }
            }
        }
    }

    /** Média do nível nos quadros anteriores, excluindo os 3 mais recentes (~100 ms). */
    private fun preMean(): Float {
        val n = histN - 3
        if (n <= 0) return -100f
        var s = 0f
        for (i in 0 until n) s += hist[i]
        return s / n
    }

    private fun pushHist(v: Float) {
        if (histN < HIST_SIZE) {
            hist[histN++] = v
        } else {
            System.arraycopy(hist, 1, hist, 0, HIST_SIZE - 1)
            hist[HIST_SIZE - 1] = v
        }
    }

    private fun output(): DetectorOutput =
        DetectorOutput(state, floor, pendConf, bestConf, pendDet, pendNote)

    private companion object {
        const val WARMUP_MS = 2000f
        const val HIST_SIZE = 40
        const val CANDIDATE_TIMEOUT_MS = 1500f
        const val STEP_HOLD_MS = 4000f
    }
}
