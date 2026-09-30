package com.calldetector.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.calldetector.R
import com.calldetector.app.AppState
import com.calldetector.app.Prefs
import com.calldetector.core.DetectorConfig
import com.calldetector.core.Phase
import com.calldetector.service.MonitorService

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var durationText: TextView
    private lateinit var levelText: TextView
    private lateinit var micText: TextView
    private lateinit var sensitivityText: TextView
    private lateinit var stepSwitch: Switch
    private lateinit var batteryButton: Button
    private lateinit var noticeText: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            MonitorService.start(this)
        } else {
            Toast.makeText(this, R.string.toast_need_mic_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.loadInto(AppState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        durationText = findViewById(R.id.durationText)
        levelText = findViewById(R.id.levelText)
        micText = findViewById(R.id.micText)
        sensitivityText = findViewById(R.id.sensitivityText)
        stepSwitch = findViewById(R.id.stepSwitch)
        batteryButton = findViewById(R.id.batteryButton)
        noticeText = findViewById(R.id.noticeText)

        toggleButton.setOnClickListener { onToggleMonitoring() }
        findViewById<Button>(R.id.testButton).setOnClickListener { onTestAlert() }
        findViewById<Button>(R.id.diagnosticButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
        findViewById<Button>(R.id.sensitivityButton).setOnClickListener { showSensitivityDialog() }
        batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        stepSwitch.isChecked = prefs.stepExperimental
        stepSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            prefs.stepExperimental = checked
            AppState.stepExperimental = checked
        }

        noticeText.text = getString(R.string.main_privacy_notice)
    }

    override fun onResume() {
        super.onResume()
        if (AppState.phase == Phase.ALERTING) {
            startActivity(Intent(this, AlertActivity::class.java))
        }
        AppState.takePendingMessage()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        polling = true
        handler.post(pollRunnable)
        updateBatteryButtonVisibility()
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            if (polling) handler.postDelayed(this, 400)
        }
    }

    private fun refreshUi() {
        when (AppState.phase) {
            Phase.STOPPED -> {
                statusText.text = getString(R.string.status_stopped)
                toggleButton.text = getString(R.string.action_start_monitoring)
                durationText.text = ""
                levelText.text = ""
                micText.text = getString(R.string.mic_closed)
            }
            Phase.MONITORING -> {
                statusText.text = getString(R.string.status_monitoring)
                toggleButton.text = getString(R.string.action_stop_monitoring)
                val elapsedS = (android.os.SystemClock.elapsedRealtime() - AppState.monitorStartMs) / 1000
                durationText.text = getString(R.string.duration_format, elapsedS / 60, elapsedS % 60)
                val fi = AppState.lastFrame
                levelText.text = if (fi != null)
                    getString(R.string.level_format, fi.features.rmsDb, fi.out.floorDb)
                else getString(R.string.level_warming_up)
                micText.text = getString(R.string.mic_open_format, AppState.micSource)
            }
            Phase.ALERTING -> {
                statusText.text = getString(R.string.status_alerting)
                toggleButton.text = getString(R.string.action_stop_monitoring)
                startActivity(Intent(this, AlertActivity::class.java))
            }
        }
        toggleButton.isEnabled = AppState.phase != Phase.ALERTING
        val level = prefs.sensitivity
        sensitivityText.text = getString(R.string.sensitivity_format, DetectorConfig.LEVEL_NAMES[level])
    }

    private fun onToggleMonitoring() {
        when (AppState.phase) {
            Phase.STOPPED -> requestPermissionsAndStart()
            Phase.MONITORING -> MonitorService.stop(this)
            Phase.ALERTING -> { /* botão desabilitado neste estado */ }
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            MonitorService.start(this)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onTestAlert() {
        MonitorService.startTest(this)
    }

    private fun showSensitivityDialog() {
        val current = prefs.sensitivity
        AlertDialog.Builder(this)
            .setTitle(R.string.sensitivity_dialog_title)
            .setSingleChoiceItems(DetectorConfig.LEVEL_NAMES, current) { dialog, which ->
                prefs.sensitivity = which
                AppState.sensitivityLevel = which
                refreshUi()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateBatteryButtonVisibility() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryButton.visibility = if (ignoring) View.GONE else View.VISIBLE
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
