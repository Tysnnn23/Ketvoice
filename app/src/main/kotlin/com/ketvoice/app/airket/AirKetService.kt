package com.ketvoice.app.airket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ketvoice.app.data.AppText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps an Air Ket transfer alive when the screen goes off or the app is left (7.0): a foreground service of type
 * dataSync with a notification (progress, speed, « Annuler »), a partial wake lock and a Wi-Fi lock, all released
 * when the transfer ends. The locks are never held while no transfer runs.
 */
class AirKetService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { AirKet.cancel(); stopSelf(); return START_NOT_STICKY }
        enterForeground(notification(AirKet.ongoing.value))
        hold()
        if (!watching) {
            watching = true
            scope.launch {
                AirKet.ongoing.collect { state ->
                    if (state == null) stopSelf() else getSystemService(NotificationManager::class.java).notify(ID, notification(state))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun hold() {
        if (wake == null) wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ketvoice:AirKet")
            ?.apply { setReferenceCounted(false); acquire(WAKE_LIMIT_MS) }
        if (wifi == null) wifi = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Ketvoice:AirKet")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun notification(state: AirKetOngoing?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, AppText.t("Air Ket"), NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 0, Intent(this, AirKetService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val title = AppText.t(if (state?.sending == true) "Air Ket · envoi" else "Air Ket · réception")
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(com.ketvoice.app.R.drawable.ic_notification_airket)
            .setContentTitle(title).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, AppText.t("Annuler"), cancel)
        if (state == null) return builder.setProgress(0, 0, true).build()
        val speed = if (state.bytesPerSecond > 0) " · " + AirKetFormat.speed(state.bytesPerSecond) else ""
        return if (state.total > 0) builder.setContentText(AirKetFormat.size(state.done) + " / " + AirKetFormat.size(state.total) + speed)
            .setProgress(1000, (state.done * 1000 / state.total).toInt().coerceIn(0, 1000), false).build()
        else builder.setContentText(AirKetFormat.size(state.done) + speed).setProgress(0, 0, true).build()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "airket"
        private const val ID = 7_007
        private const val ACTION_CANCEL = "com.ketvoice.app.airket.CANCEL"
        /** A transfer that runs longer than this is almost certainly stuck: the lock is not held for ever. */
        private const val WAKE_LIMIT_MS = 6L * 3600_000

        fun start(context: Context) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, AirKetService::class.java)) }
        }
    }
}
