package com.calldetector.core

/**
 * Parâmetros do detector. TODOS os valores numéricos são pontos de partida derivados de simulação
 * e de conhecimento geral de acústica; precisam ser validados/ajustados no aparelho real com a
 * tela de Diagnóstico.
 */
class DetectorConfig(
    /** Confiança mínima (0..1) para disparar o alerta. */
    val threshold: Float,
    /** Nível mínimo do evento acima do piso de ruído (dB). */
    val minAboveFloorDb: Float,
    /** Subida mínima do nível em relação a ~1 s imediatamente anterior (dB). */
    val onsetRiseDb: Float,
    /** Duração mínima sustentada do evento (ms). Descarta batidas e cliques. */
    val minDurationMs: Int,
    /** Nível absoluto mínimo (dBFS). Evita "eventos" minúsculos quando o piso é quase digital-zero. */
    val absMinLevelDb: Float,
    /** Experimental: detectar degrau sustentado de ruído de fundo (ligação silenciosa com chiado). */
    val stepEnabled: Boolean = false,
    val stepDb: Float = 5f
) {
    fun describe(): String =
        "limiar %.2f | ≥ +%.0f dB sobre o piso | subida ≥ %.0f dB | duração ≥ %d ms | nível ≥ %.0f dBFS".format(
            threshold, minAboveFloorDb, onsetRiseDb, minDurationMs, absMinLevelDb
        )

    companion object {
        val LEVEL_NAMES = arrayOf("Muito baixa", "Baixa", "Média (recomendada)", "Alta", "Muito alta")
        const val DEFAULT_LEVEL = 2

        fun forLevel(level: Int, stepEnabled: Boolean = false): DetectorConfig =
            when (level.coerceIn(0, 4)) {
                0 -> DetectorConfig(0.80f, 24f, 14f, 300, -50f, stepEnabled)
                1 -> DetectorConfig(0.72f, 20f, 12f, 220, -55f, stepEnabled)
                2 -> DetectorConfig(0.62f, 16f, 10f, 160, -60f, stepEnabled)
                3 -> DetectorConfig(0.52f, 12f, 8f, 110, -65f, stepEnabled)
                else -> DetectorConfig(0.42f, 9f, 6f, 80, -70f, stepEnabled)
            }
    }
}
