package com.calldetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.calldetector.R
import com.calldetector.ui.AlertActivity
import com.calldetector.ui.MainActivity

/** Canais e notificações do app. */
object Notifications {
    const val CH_MONITOR = "monitor"
    const val CH_ALERT = "alert"
    const val CH_STATUS = "status"

    const val ID_FGS = 1
    const val ID_ALERT = 2
    const val ID_PROBLEM = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val monitor = NotificationChannel(
            CH_MONITOR, ctx.getString(R.string.ch_monitor_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = ctx.getString(R.string.ch_monitor_desc)
            setShowBadge(false)
        }

        // Alerta: importância ALTA (necessária para o Full-Screen Intent). O som e a vibração são
        // feitos pelo AlertPlayer (canal de alarme), então o canal em si fica mudo para não duplicar.
        val alert = NotificationChannel(
            CH_ALERT, ctx.getString(R.string.ch_alert_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ctx.getString(R.string.ch_alert_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        val status = NotificationChannel(
            CH_STATUS, ctx.getString(R.string.ch_status_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ctx.getString(R.string.ch_status_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        nm.createNotificationChannels(listOf(monitor, alert, status))
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openMain(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 10, i, flags())
    }

    private fun openAlert(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 12, i, flags())
    }

    private fun serviceAction(ctx: Context, action: String, requestCode: Int): PendingIntent {
        val i = Intent(ctx, MonitorService::class.java).setAction(action)
        return PendingIntent.getService(ctx, requestCode, i, flags())
    }

    /** Notificação persistente do estado MONITORANDO. */
    fun monitoring(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_monitoring_title))
            .setContentText(ctx.getString(R.string.notif_monitoring_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openMain(ctx))
            .addAction(0, ctx.getString(R.string.action_stop), serviceAction(ctx, MonitorService.ACTION_STOP, 11))
            .build()

    /** Notificação persistente do serviço durante o ALERTA (a do Full-Screen Intent é [alertFullScreen]). */
    fun alertingForeground(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_alert_fgs_title))
            .setContentText(ctx.getString(R.string.notif_alert_fgs_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAlert(ctx))
            .addAction(0, ctx.getString(R.string.action_dismiss), serviceAction(ctx, MonitorService.ACTION_DISMISS, 13))
            .build()

    /** Notificação de tela cheia: acorda a tela e abre a AlertActivity sobre a tela de bloqueio. */
    fun alertFullScreen(ctx: Context, reason: String): Notification =
        NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.alert_title))
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAlert(ctx))
            .setFullScreenIntent(openAlert(ctx), true)
            .addAction(0, ctx.getString(R.string.action_dismiss), serviceAction(ctx, MonitorService.ACTION_DISMISS, 13))
            .build()

    /** Aviso de que o monitoramento foi interrompido por um problema (o usuário precisa saber!). */
    fun problem(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_problem_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openMain(ctx))
            .build()
}
