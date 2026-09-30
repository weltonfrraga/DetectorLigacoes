package com.calldetector.app

import com.calldetector.core.FrameInfo
import com.calldetector.core.Phase
import com.calldetector.core.PhaseRules
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Estado compartilhado entre o serviço (que detecta) e as telas (que só observam).
 * Tudo vive na memória do processo: nada disto é gravado em disco (privacidade).
 */
object AppState {
    private val lock = Any()

    @Volatile var phase: Phase = Phase.STOPPED
        private set

    /** true se o alerta atual veio do botão TESTAR ALERTA. */
    @Volatile var alertIsTest: Boolean = false
        private set

    /** SystemClock.elapsedRealtime() do início do monitoramento (0 se parado). */
    @Volatile var monitorStartMs: Long = 0L

    @Volatile var alertReason: String = ""

    @Volatile var micSource: String = "-"
    @Volatile var micOpen: Boolean = false
    @Volatile var micSilenced: Boolean = false

    /** Sensibilidade 0..4 (lida a cada quadro pelo detector; pode mudar durante o monitoramento). */
    @Volatile var sensitivityLevel: Int = 2

    /** Modo experimental de "aumento sustentado de ruído". */
    @Volatile var stepExperimental: Boolean = false

    /** Último quadro analisado (para tela principal e Diagnóstico). */
    @Volatile var lastFrame: FrameInfo? = null

    @Volatile var framesProcessed: Long = 0L

    /** Mensagem de erro/aviso pendente para a interface mostrar uma vez. */
    @Volatile var pendingMessage: String? = null

    private val events = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Aplica uma transição respeitando [PhaseRules]. Devolve false se for proibida. */
    fun transition(to: Phase, test: Boolean = false): Boolean = synchronized(lock) {
        if (to == phase) return true
        if (!PhaseRules.allowed(phase, to, test)) return false
        phase = to
        alertIsTest = to == Phase.ALERTING && test
        if (to == Phase.STOPPED) {
            monitorStartMs = 0L
            micOpen = false
            micSilenced = false
            lastFrame = null
        }
        true
    }

    fun log(message: String) {
        synchronized(events) {
            events.addFirst(timeFmt.format(Date()) + "  " + message)
            while (events.size > MAX_EVENTS) events.removeLast()
        }
    }

    fun eventsSnapshot(): List<String> = synchronized(events) { events.toList() }

    fun clearEvents() = synchronized(events) { events.clear() }

    fun takePendingMessage(): String? = synchronized(lock) {
        val m = pendingMessage
        pendingMessage = null
        m
    }

    private const val MAX_EVENTS = 80
}
