package com.calldetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trava de segurança da máquina de estados exigida pelo usuário:
 *
 *   PARADO --[INICIAR]--> MONITORANDO --[evento]--> ALERTA --[DESLIGAR ALERTA]--> PARADO
 *
 * O teste mais importante aqui é [alertingNeverAutoReturnsToMonitoring]: se ele falhar, o app pode
 * voltar a gravar sozinho depois de um alerta, o que violaria o requisito central do projeto.
 */
class PhaseRulesTest {

    @Test
    fun stoppedCanStartMonitoring() {
        assertTrue(PhaseRules.allowed(Phase.STOPPED, Phase.MONITORING))
    }

    @Test
    fun stoppedCanOnlyGoToAlertingAsATest() {
        assertTrue(PhaseRules.allowed(Phase.STOPPED, Phase.ALERTING, test = true))
        assertFalse(PhaseRules.allowed(Phase.STOPPED, Phase.ALERTING, test = false))
    }

    @Test
    fun monitoringCanStopOrAlert() {
        assertTrue(PhaseRules.allowed(Phase.MONITORING, Phase.ALERTING))
        assertTrue(PhaseRules.allowed(Phase.MONITORING, Phase.STOPPED))
    }

    @Test
    fun alertingCanOnlyGoToStopped() {
        assertTrue(PhaseRules.allowed(Phase.ALERTING, Phase.STOPPED))
    }

    @Test
    fun alertingNeverAutoReturnsToMonitoring() {
        // Regra central do produto: nunca existe ALERTA -> MONITORANDO, com ou sem o atalho de teste.
        assertFalse(PhaseRules.allowed(Phase.ALERTING, Phase.MONITORING, test = false))
        assertFalse(PhaseRules.allowed(Phase.ALERTING, Phase.MONITORING, test = true))
    }

    @Test
    fun monitoringNeverGoesDirectlyBackToItselfAsANewTransition() {
        // (Permanecer no mesmo estado é tratado por quem chama, não por esta função.)
        assertFalse(PhaseRules.allowed(Phase.MONITORING, Phase.MONITORING))
    }
}
