package com.example.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.model.BleConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Persistent Foreground Service that keeps the BLE GATT connection, periodic telemetry sync,
 * GPS ride logger, and Google Maps Turn-by-Turn navigation relay alive in the background
 * when the user closes the app, minimizes it, switches to Google Maps, or turns off the screen.
 */
class IqubeBleForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            val bleManager = IqubeBleManager.getInstance(applicationContext)
            bleManager.disconnectBle(userInitiated = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        observeBleManagerAndKeepAlive()

        return START_STICKY
    }

    private fun startInForeground() {
        val initialNotification = buildNotification(
            title = "TVS iQube Connected",
            contentText = "Active background connection • Ready for navigation",
            isRiding = false
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, initialNotification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            // Fallback for security restrictions
            runCatching { startForeground(NOTIFICATION_ID, initialNotification) }
        }
    }

    private fun observeBleManagerAndKeepAlive() {
        val bleManager = IqubeBleManager.getInstance(applicationContext)
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            combine(
                bleManager.connectionPhase,
                bleManager.connectedDeviceName,
                bleManager.packetParser.telemetryState,
                bleManager.navigationState
            ) { phase, devName, tele, nav ->
                Quadruple(phase, devName, tele, nav)
            }.collect { (phase, devName, tele, nav) ->
                when (phase) {
                    BleConnectionPhase.STREAMING_ACTIVE,
                    BleConnectionPhase.REPLAYING_RIDING_LOG,
                    BleConnectionPhase.REPLAYING_CHARGING_LOG -> {
                        val isNavActive = nav.distanceMeters > 0 && nav.streetName.isNotBlank()
                        val content = if (isNavActive) {
                            "${nav.selectedTurn.symbol} ${nav.selectedTurn.title} in ${nav.distanceMeters}m • ETA ${nav.etaMinutes}m"
                        } else {
                            val bat = tele.batteryPercent?.let { "$it%" } ?: "--%"
                            val spd = tele.speedKmh?.let { "${it} km/h" } ?: "0 km/h"
                            val odo = tele.odometerKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--"
                            "Battery: $bat • Speed: $spd • ODO: $odo"
                        }
                        updateNotification(
                            title = "iQube: $devName",
                            contentText = content,
                            isRiding = (tele.speedKmh ?: 0) > 0
                        )
                    }
                    BleConnectionPhase.CONNECTING_GATT,
                    BleConnectionPhase.SCANNING,
                    BleConnectionPhase.DISCOVERING_SERVICES,
                    BleConnectionPhase.ENABLING_CCCD,
                    BleConnectionPhase.NEGOTIATING_MTU,
                    BleConnectionPhase.SENDING_HANDSHAKE -> {
                        updateNotification(
                            title = "Connecting to TVS iQube...",
                            contentText = "Scanning & linking TVSiQUBEJI573 in background",
                            isRiding = false
                        )
                    }
                    BleConnectionPhase.DISCONNECTED -> {
                        // Allow brief grace period for auto-reconnect before stopping foreground service
                        delay(4000L)
                        if (bleManager.connectionPhase.value == BleConnectionPhase.DISCONNECTED &&
                            !bleManager.autoReconnectEnabled.value
                        ) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                    else -> {
                        updateNotification(
                            title = "iQube Link: ${phase.name}",
                            contentText = "Establishing encrypted Bluetooth channel...",
                            isRiding = false
                        )
                    }
                }
            }
        }
    }

    private fun updateNotification(title: String, contentText: String, isRiding: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, contentText, isRiding))
        }
    }

    private fun buildNotification(title: String, contentText: String, isRiding: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, IqubeBleForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText("Connected")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TVS iQube Background Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth connection and Google Maps navigation sync active in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IqubeCockpit::BleLinkWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            stateObserverJob?.cancel()
            serviceScope.cancel()
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "iqube_ble_foreground_channel"
        const val NOTIFICATION_ID = 4096
        const val ACTION_STOP_SERVICE = "com.example.ble.ACTION_STOP_SERVICE"

        fun startService(context: Context) {
            val intent = Intent(context, IqubeBleForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                runCatching { context.startService(intent) }
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, IqubeBleForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            runCatching { context.startService(intent) }
        }

        fun isBatteryOptimizationIgnored(context: Context): Boolean {
            return runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            }.getOrDefault(false)
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
