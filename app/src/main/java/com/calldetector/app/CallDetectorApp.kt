package com.calldetector.app

import android.app.Application
import com.calldetector.service.Notifications

class CallDetectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Prefs(this).loadInto(AppState)
        // Ao (re)iniciar o processo o app SEMPRE começa em PARADO — nunca herda um estado antigo
        // nem religa o microfone sozinho, mesmo que o sistema tenha matado o processo durante um
        // monitoramento anterior. Isto está de acordo com a regra de que o app só volta a monitorar
        // se o usuário tocar em "INICIAR MONITORAMENTO" de novo.
        AppState.log("App iniciado")
    }
}
