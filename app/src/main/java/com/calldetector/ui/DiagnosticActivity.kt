package com.calldetector.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.calldetector.R
import com.calldetector.app.AppState
import com.calldetector.core.DetectorConfig
import com.calldetector.core.Phase
import java.util.Locale

/**
 * Tela de diagnóstico/desenvolvedor: mostra os números crus que o detector está vendo agora,
 * mais o histórico de eventos (detecções e rejeições, com o motivo). Útil para calibrar a
 * sensibilidade observando o app real, sem precisar ler logcat.
 */
class DiagnosticActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private lateinit var stateText: TextView
    private lateinit var featuresText: TextView
    private lateinit var scoreText: TextView
    private lateinit var configText: TextView
    private lateinit var micText: TextView
    private lateinit var eventsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)

        stateText = findViewById(R.id.diagStateText)
        featuresText = findViewById(R.id.diagFeaturesText)
        scoreText = findViewById(R.id.diagScoreText)
        configText = findViewById(R.id.diagConfigText)
        micText = findViewById(R.id.diagMicText)
        eventsText = findViewById(R.id.diagEventsText)

        findViewById<Button>(R.id.diagClearButton).setOnClickListener {
            AppState.clearEvents()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        polling = true
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            if (polling) handler.postDelayed(this, 250)
        }
    }

    private fun refresh() {
        val level = AppState.sensitivityLevel
        val cfg = DetectorConfig.forLevel(level, AppState.stepExperimental)
        configText.text = getString(R.string.diag_config_format, DetectorConfig.LEVEL_NAMES[level], cfg.describe())

        val phaseName = when (AppState.phase) {
            Phase.STOPPED -> getString(R.string.status_stopped)
            Phase.MONITORING -> getString(R.string.status_monitoring)
            Phase.ALERTING -> getString(R.string.status_alerting)
        }

        val fi = AppState.lastFrame
        if (fi == null) {
            stateText.text = getString(R.string.diag_no_data_format, phaseName)
            featuresText.text = ""
            scoreText.text = ""
        } else {
            val f = fi.features
            val o = fi.out
            stateText.text = getString(R.string.diag_state_format, phaseName, o.state.name)
            featuresText.text = String.format(
                Locale.getDefault(),
                "RMS: %.1f dBFS   Pico: %.1f dBFS   Crista: %.1f dB\nPiso de ruído: %.1f dBFS\nFreq. dominante: %.0f Hz   Centróide: %.0f Hz\nBanda telefônica: %.0f%%   Graves: %.0f%%   Agudos: %.0f%%\nPico/média espectral: %.1f dB   Fluxo: %.2f",
                f.rmsDb, f.peakDb, f.crestDb, o.floorDb, f.dominantHz, f.centroidHz,
                f.voiceRatio * 100, f.lowRatio * 100, f.highRatio * 100, f.peakinessDb, f.flux
            )
            scoreText.text = getString(
                R.string.diag_score_format, o.confidence, o.bestConfidence, cfg.threshold
            )
        }

        micText.text = getString(
            R.string.diag_mic_format,
            AppState.micSource,
            if (AppState.micOpen) getString(R.string.mic_state_open) else getString(R.string.mic_state_closed),
            AppState.framesProcessed,
            fi?.zeroRunMs ?: 0L
        )

        val events = AppState.eventsSnapshot()
        eventsText.text = if (events.isEmpty()) getString(R.string.diag_no_events)
        else events.joinToString("\n")
    }
}
