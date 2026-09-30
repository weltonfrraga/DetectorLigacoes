package com.calldetector.core

/**
 * Máquina de estados do aplicativo:
 *
 *   PARADO ──[INICIAR]──▶ MONITORANDO ──[evento de ligação]──▶ ALERTA ──[DESLIGAR ALERTA]──▶ PARADO
 *
 * NÃO existe a transição ALERTA → MONITORANDO (o ciclo termina quando o alerta é desligado).
 * O modo de teste é o único atalho: PARADO → ALERTA (simula a detecção), e também termina em PARADO.
 */
enum class Phase { STOPPED, MONITORING, ALERTING }

object PhaseRules {
    fun allowed(from: Phase, to: Phase, test: Boolean = false): Boolean = when (from) {
        Phase.STOPPED -> to == Phase.MONITORING || (to == Phase.ALERTING && test)
        Phase.MONITORING -> to == Phase.ALERTING || to == Phase.STOPPED
        Phase.ALERTING -> to == Phase.STOPPED
    }
}
