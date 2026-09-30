package com.calldetector.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.calldetector.R
import com.calldetector.app.AppState
import com.calldetector.core.Phase
import com.calldetector.service.MonitorService

/**
 * Tela de alerta em tela cheia. Aparece por cima da tela de bloqueio (Full-Screen Intent).
 * Só sai desta tela quem toca em "DESLIGAR ALERTA" — o botão Voltar é ignorado de propósito,
 * do mesmo jeito que um alarme de despertador, para não encerrar o alerta sem querer.
 */
class AlertActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var reasonText: TextView

    private val watchdog = object : Runnable {
        override fun run() {
            if (AppState.phase != Phase.ALERTING) {
                finish()
            } else {
                handler.postDelayed(this, 400)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_alert)

        reasonText = findViewById(R.id.alertReasonText)
        findViewById<TextView>(R.id.alertTitleText).text =
            if (AppState.alertIsTest) getString(R.string.alert_title_test) else getString(R.string.alert_title)

        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            MonitorService.dismiss(this)
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Ignorado de propósito: só o botão DESLIGAR ALERTA encerra o alerta.
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reasonText.text = AppState.alertReason
        if (AppState.phase != Phase.ALERTING) {
            finish()
            return
        }
        handler.post(watchdog)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watchdog)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reasonText.text = AppState.alertReason
    }
}
