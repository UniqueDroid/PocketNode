package com.pocketnode.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pocketnode.server.R
import com.pocketnode.server.container
import com.pocketnode.server.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hält den Server im Vordergrund am Leben. Startet das System den Dienst neu (`intent == null`),
 * liest der [ServerController] die gespeicherten Einstellungen.
 */
class PocketNodeService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statusJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val controller = container.controller
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch {
                container.settingsStore.update { it.copy(serverWanted = false) }
                controller.stop()
                ServiceCompat.stopForeground(this@PocketNodeService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_starting)),
            // Den Typ specialUse gibt es erst ab Android 14.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        acquireLocks()
        if (statusJob == null) {
            statusJob = lifecycleScope.launch {
                controller.status.collect { status ->
                    val address = container.deviceInfo.snapshot().addresses.firstOrNull()?.ip
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(notificationText(status, address, texts())))
                }
            }
        }
        lifecycleScope.launch { controller.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun texts() = NotificationTexts(
        stopped = getString(R.string.notification_stopped),
        starting = getString(R.string.notification_starting),
        running = { address, model, accelerator -> getString(R.string.notification_running, address, model, accelerator) },
        noModel = getString(R.string.notification_no_model),
        loading = { model -> getString(R.string.notification_loading, model) },
        failed = { reason -> getString(R.string.notification_failed, reason) },
    )

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketNode:Wake")
                // Ohne Zeitlimit: Der Server soll laufen, bis der Nutzer ihn stoppt. Freigabe in onDestroy.
                .apply { setReferenceCounted(false); @Suppress("WakelockTimeout") acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PocketNode:Wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PocketNodeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.pocketnode.server.START"
        const val ACTION_STOP = "com.pocketnode.server.STOP"
        private const val CHANNEL_ID = "pocketnode_server"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PocketNodeService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PocketNodeService::class.java).setAction(ACTION_STOP))
        }
    }
}
