package com.calldetector.app

import android.content.Context
import com.calldetector.core.Calibration
import com.calldetector.core.DetectorConfig

/**
 * Preferências do usuário (SharedPreferences privado do app). Guarda apenas configurações e,
 * opcionalmente, o perfil numérico de calibração — nunca áudio.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("call_detector", Context.MODE_PRIVATE)

    var sensitivity: Int
        get() = sp.getInt(KEY_SENS, DetectorConfig.DEFAULT_LEVEL).coerceIn(0, 4)
        set(v) = sp.edit().putInt(KEY_SENS, v.coerceIn(0, 4)).apply()

    var stepExperimental: Boolean
        get() = sp.getBoolean(KEY_STEP, false)
        set(v) = sp.edit().putBoolean(KEY_STEP, v).apply()

    var calibration: Calibration?
        get() = Calibration.parse(sp.getString(KEY_CAL, null))
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove(KEY_CAL) else e.putString(KEY_CAL, v.serialize())
            e.apply()
        }

    /** Copia as preferências para o [AppState] (chamado ao abrir o app e ao iniciar o serviço). */
    fun loadInto(state: AppState) {
        state.sensitivityLevel = sensitivity
        state.stepExperimental = stepExperimental
    }

    private companion object {
        const val KEY_SENS = "sensitivity"
        const val KEY_STEP = "step_experimental"
        const val KEY_CAL = "calibration"
    }
}
