package com.calldetector.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.calldetector.app.AppState
import com.calldetector.app.Prefs
import com.calldetector.audio.AudioCapture
import com.calldetector.core.CallDetector
import com.calldetector.core.DetectorConfig
import com.calldetector.core.FrameAnalyzer
import com.calldetector.core.FrameInfo
import com.calldetector.core.Phase

/**
 * Único serviço do app. Dono do microfone, do detector e do alarme.
 *
 * Ciclo de vida ligado exatamente à máquina de estados exigida:
 *  ACTION_START_MONITORING  → abre o microfone, começa a analisar (PARADO → MONITORANDO)
 *  (detecção real)          → fecha o microfone e dispara o alerta (MONITORANDO → ALERTA)
 *  ACTION_START_TEST        → dispara o alerta sem usar o microfone (PARADO → ALERTA) ou,
 *                              se já estiver monitorando, encerra a captura e dispara (idem detecção real)
 *  ACTION_DISMISS           → para o alarme e encerra tudo (ALERTA → PARADO)
 *  ACTION_STOP              → encerra tudo a partir de qualquer estado ativo (→ PARADO)
 *
 * Nunca existe transição automática de ALERTA para MONITORANDO.
 */
class MonitorService : Service() {

    private lateinit var prefs: Prefs
    private var audioCapture: AudioCapture? = null
    private var detector: CallDetector? = null
    private var analyzer: FrameAnalyzer? = null
    private var alertPlayer: AlertPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var zeroRunMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> handleStartMonitoring()
            ACTION_START_TEST -> handleStartTest()
            ACTION_DISMISS -> handleDismiss()
            ACTION_STOP -> handleStop()
            else -> { /* recriação pelo sistema sem ação: não faz nada, sem auto-reinício */ }
        }
        // START_NOT_STICKY: se o sistema matar o serviço, ele NÃO deve voltar sozinho — condiz com
        // a regra de que o monitoramento só recomeça se o usuário tocar em INICIAR de novo.
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- iniciar monitoramento

    private fun handleStartMonitoring() {
        if (AppState.phase != Phase.STOPPED) return

        val fa = FrameAnalyzer(SAMPLE_RATE)
        analyzer = fa
        val level = prefs.sensitivity
        val cfg = DetectorConfig.forLevel(level, prefs.stepExperimental)
        val det = CallDetector(cfg, fa.frameMs, prefs.calibration)
        detector = det

        val capture = AudioCapture(SAMPLE_RATE, fa.fftSize, object : AudioCapture.Listener {
            override fun onFrame(samples: ShortArray): Boolean = onAudioFrame(samples)
            override fun onError(message: String) = onAudioError(message)
        })

        if (!AppState.transition(Phase.MONITORING)) return

        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_FGS, Notifications.monitoring(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            failStart("Não foi possível iniciar o serviço em primeiro plano: ${e.message}")
            return
        }

        if (!capture.start()) {
            failStart("Não foi possível abrir o microfone (pode estar em uso por outro app).")
            return
        }
        audioCapture = capture
        acquireWakeLock()

        AppState.micSource = capture.sourceName
        AppState.micOpen = true
        AppState.micSilenced = false
        AppState.monitorStartMs = SystemClock.elapsedRealtime()
        AppState.framesProcessed = 0
        AppState.sensitivityLevel = level
        AppState.log("Monitoramento iniciado — sensibilidade '${DetectorConfig.LEVEL_NAMES[level]}', fonte ${capture.sourceName}")
    }

    private fun failStart(reason: String) {
        AppState.transition(Phase.STOPPED)
        NotificationManagerCompat.from(this).notify(Notifications.ID_PROBLEM, Notifications.problem(this, reason))
        AppState.pendingMessage = reason
        AppState.log("Falha ao iniciar: $reason")
        stopForegroundCompat()
        stopSelf()
    }

    /** Chamado NA THREAD DE ÁUDIO por [AudioCapture]. Deve ser rápido e não bloquear. */
    private fun onAudioFrame(samples: ShortArray): Boolean {
        val fa = analyzer ?: return false
        val det = detector ?: return false

        // Sensibilidade pode ter sido alterada na tela principal durante o monitoramento.
        val level = AppState.sensitivityLevel
        if (det.config.threshold != DetectorConfig.forLevel(level, AppState.stepExperimental).threshold ||
            det.config.stepEnabled != AppState.stepExperimental
        ) {
            det.setConfig(DetectorConfig.forLevel(level, AppState.stepExperimental))
        }

        var allZero = true
        for (s in samples) if (s.toInt() != 0) { allZero = false; break }
        zeroRunMs = if (allZero) zeroRunMs + fa.frameMs.toLong() else 0L

        val features = fa.analyze(samples)
        val out = det.process(features)
        AppState.framesProcessed++
        AppState.lastFrame = FrameInfo(features, out, zeroRunMs)
        if (out.note != null) AppState.log(out.note)

        val detection = out.detection
        if (detection != null) {
            val breakdown = detection.breakdown
            val reason = if (breakdown != null)
                "Evento detectado (confiança ${com.calldetector.core.fmt(detection.confidence)}) — ${breakdown.describe()}"
            else
                "Evento detectado: ${detection.kind}"
            AppState.log(reason)
            mainHandler.post { onDetected(reason) }
            return false // encerra a captura JÁ (thread de áudio libera o microfone em seguida)
        }
        return true
    }

    private fun onAudioError(message: String) {
        mainHandler.post {
            if (AppState.phase == Phase.MONITORING) {
                failStart("O microfone parou de funcionar: $message")
            }
        }
    }

    /** Roda na main thread: uma detecção REAL aconteceu durante o monitoramento. */
    private fun onDetected(reason: String) {
        if (AppState.phase != Phase.MONITORING) return
        releaseWakeLock()
        AppState.micOpen = false
        if (!AppState.transition(Phase.ALERTING, test = false)) return
        AppState.alertReason = reason
        fireAlert()
    }

    // ---------------------------------------------------------------- teste manual

    private fun handleStartTest() {
        when (AppState.phase) {
            Phase.STOPPED -> {
                if (!AppState.transition(Phase.ALERTING, test = true)) return
                AppState.alertReason = getString(com.calldetector.R.string.alert_reason_test)
                AppState.log("Teste manual do alerta (a partir de PARADO)")
                fireAlert()
            }
            Phase.MONITORING -> {
                // Mesmo caminho de uma detecção real: para a captura e dispara o alerta de teste.
                audioCapture?.stopAndJoin()
                audioCapture = null
                releaseWakeLock()
                AppState.micOpen = false
                if (!AppState.transition(Phase.ALERTING, test = true)) return
                AppState.alertReason = getString(com.calldetector.R.string.alert_reason_test)
                AppState.log("Teste manual do alerta (monitoramento interrompido para o teste)")
                fireAlert()
            }
            Phase.ALERTING -> { /* já em alerta, ignora novo pedido de teste */ }
        }
    }

    private fun fireAlert() {
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_FGS, Notifications.alertingForeground(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (_: Exception) {
            // Se o FGS já estava rodando (vindo do monitoramento) isto apenas atualiza a notificação.
        }
        val player = AlertPlayer(this)
        alertPlayer = player
        player.start()
        NotificationManagerCompat.from(this)
            .notify(Notifications.ID_ALERT, Notifications.alertFullScreen(this, AppState.alertReason))
    }

    // ---------------------------------------------------------------- desligar alerta / parar

    private fun handleDismiss() {
        if (AppState.phase != Phase.ALERTING) return
        AppState.log("Alerta desligado pelo usuário")
        fullStop()
    }

    private fun handleStop() {
        if (AppState.phase == Phase.STOPPED) return
        AppState.log("Monitoramento parado pelo usuário")
        fullStop()
    }

    /** Libera TUDO (microfone, alarme, wake lock) e volta a PARADO. Nunca reinicia sozinho. */
    private fun fullStop() {
        audioCapture?.stopAndJoin()
        audioCapture = null
        alertPlayer?.stop()
        alertPlayer = null
        releaseWakeLock()
        AppState.micOpen = false
        AppState.transition(Phase.STOPPED)
        NotificationManagerCompat.from(this).cancel(Notifications.ID_ALERT)
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        try {
            ServiceCompat.stopForeground(this, Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallDetector:monitoring")
            wl.setReferenceCounted(false)
            wl.acquire(MAX_WAKE_LOCK_MS)
            wakeLock = wl
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        audioCapture?.stopAndJoin()
        alertPlayer?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_MONITORING = "com.calldetector.action.START_MONITORING"
        const val ACTION_START_TEST = "com.calldetector.action.START_TEST"
        const val ACTION_DISMISS = "com.calldetector.action.DISMISS"
        const val ACTION_STOP = "com.calldetector.action.STOP"

        /** 16 kHz mono: cobre bem a banda telefônica (até 8 kHz) usando pouca CPU/bateria. */
        const val SAMPLE_RATE = 16000

        /** Limite de segurança do wake lock parcial (renovado a cada novo quadro seria caro demais;
         * na prática o serviço em foreground já é suficiente, isto é apenas uma rede de proteção). */
        const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1000L // 6 horas

        fun start(ctx: Context) = ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_START_MONITORING))
        fun startTest(ctx: Context) = ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_START_TEST))
        fun dismiss(ctx: Context) = ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_DISMISS))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
    }
}
