package com.calldetector.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Calibração OPCIONAL. Guarda apenas números (formato espectral em 12 bandas e um nível típico),
 * nunca áudio. Cada "perfil" descreve um tipo de som do headset (ex.: bipe de conexão, voz de teste).
 * O detector usa o perfil mais parecido: quanto mais o evento se parece com algum perfil, maior a
 * confiança; sons de formato diferente de todos os perfis são penalizados (até 50%).
 */
class Calibration(val profiles: List<FloatArray>, val levelDb: Float) {

    /** Melhor similaridade de cosseno (−1..1) entre o formato espectral [shape] e os perfis. */
    fun matchScore(shape: FloatArray): Float {
        var best = -1f
        for (p in profiles) best = max(best, cosine(shape, p))
        return best
    }

    fun withProfile(profile: FloatArray, level: Float): Calibration =
        Calibration((profiles + listOf(profile)).takeLast(6), min(levelDb, level))

    fun serialize(): String =
        levelDb.toString() + ";" + profiles.joinToString("|") { p -> p.joinToString(",") }

    companion object {
        fun parse(s: String?): Calibration? {
            if (s.isNullOrBlank()) return null
            return try {
                val parts = s.split(";")
                val level = parts[0].toFloat()
                val profiles = parts[1].split("|").map { chunk ->
                    chunk.split(",").map { it.toFloat() }.toFloatArray()
                }
                if (profiles.isEmpty() || profiles.any { it.size != NUM_BANDS }) null
                else Calibration(profiles, level)
            } catch (e: Exception) {
                null
            }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            return (dot / (sqrt(na) * sqrt(nb) + 1e-9)).toFloat()
        }

        /** Perfil (formato espectral centrado + nível mediano) a partir de quadros ATIVOS. */
        fun profileFrom(frames: List<FrameFeatures>): Pair<FloatArray, Float>? {
            if (frames.size < 20) return null
            val acc = FloatArray(NUM_BANDS)
            for (f in frames) {
                val m = f.bandDb.average().toFloat()
                for (i in 0 until NUM_BANDS) acc[i] = acc[i] + (f.bandDb[i] - m)
            }
            val n = frames.size.toFloat()
            for (i in acc.indices) acc[i] = acc[i] / n
            val levels = frames.map { it.rmsDb }.sorted()
            return Pair(acc, levels[levels.size / 2])
        }
    }
}
