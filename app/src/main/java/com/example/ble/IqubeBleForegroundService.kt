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
import java.text.SimpleDateFormat
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
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            combine(
                bleManager.connectionPhase,
                bleManager.connectedDeviceName,
                bleManager.packetParser.telemetryState,
                bleManager.navigationState,
                bleManager.lastParkedLocation,
                bleManager.trueRangeState,
                bleManager.statusNotificationsEnabled,
                bleManager.autoReconnectEnabled
            ) { values ->
                val phase = values[0] as BleConnectionPhase
                val devName = values[1] as String
                val tele = values[2] as com.example.model.TelemetryState
                val nav = values[3] as com.example.model.NavigationManeuverState
                val parked = values[4] as? com.example.model.LastParkedLocation
                val trueRange = values[5] as com.example.model.TrueRangeCalculation
                val notifEnabled = values[6] as Boolean
                val autoReconnect = values[7] as Boolean
                ForegroundStateSnapshot(phase, devName, tele, nav, parked, trueRange, notifEnabled, autoReconnect)
            }.collect { state ->
                val isConnected = state.phase in listOf(
                    BleConnectionPhase.STREAMING_ACTIVE,
                    BleConnectionPhase.REPLAYING_RIDING_LOG,
                    BleConnectionPhase.REPLAYING_CHARGING_LOG
                )

                if (!state.notifEnabled) {
                    updateNotification(
                        title = "iQube Cockpit",
                        contentText = if (isConnected) "Connected in background" else "Background service running",
                        subText = if (isConnected) "Connected" else "Idle",
                        isRiding = (state.tele.speedKmh ?: 0) > 0,
                        isConnected = isConnected
                    )
                } else when (state.phase) {
                    BleConnectionPhase.STREAMING_ACTIVE,
                    BleConnectionPhase.REPLAYING_RIDING_LOG,
                    BleConnectionPhase.REPLAYING_CHARGING_LOG -> {
                        val isNavActive = state.nav.distanceMeters > 0 && state.nav.streetName.isNotBlank()
                        if (isNavActive) {
                            updateNotification(
                                title = "iQube Navigation • ${state.nav.etaMinutes}m left",
                                contentText = "${state.nav.selectedTurn.symbol} ${state.nav.selectedTurn.title} in ${state.nav.distanceMeters}m • ${state.nav.streetName}",
                                subText = "Navigation",
                                isRiding = (state.tele.speedKmh ?: 0) > 0,
                                isConnected = true
                            )
                        } else {
                            val bat = state.tele.batteryPercent?.let { "$it%" } ?: "--%"
                            val dte = state.tele.clusterRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                            val trueEco = state.trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                            val truePwr = state.trueRange.truePowerRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                            val spd = state.tele.speedKmh?.let { "${it} km/h" } ?: "0 km/h"
                            updateNotification(
                                title = "TVS iQube Connected • $spd",
                                contentText = "Battery: $bat • Range: $dte (True Eco: $trueEco • Pwr: $truePwr)",
                                subText = "Live Cockpit",
                                isRiding = (state.tele.speedKmh ?: 0) > 0,
                                isConnected = true
                            )
                        }
                    }
                    BleConnectionPhase.CONNECTING_GATT,
                    BleConnectionPhase.SCANNING,
                    BleConnectionPhase.DISCOVERING_SERVICES,
                    BleConnectionPhase.ENABLING_CCCD,
                    BleConnectionPhase.NEGOTIATING_MTU,
                    BleConnectionPhase.SENDING_HANDSHAKE -> {
                        updateNotification(
                            title = "Connecting to TVS iQube...",
                            contentText = "Scanning & linking ${state.devName} in background",
                            subText = "Connecting",
                            isRiding = false,
                            isConnected = false
                        )
                    }
                    BleConnectionPhase.DISCONNECTED -> {
                        val lastSeenStr = state.parked?.timestampMs?.let {
                            timeFormat.format(java.util.Date(it))
                        } ?: "Recent"
                        val bat = state.parked?.batteryPercentAtPark?.let { "$it%" } ?: state.tele.batteryPercent?.let { "$it%" } ?: "--%"
                        val dte = state.parked?.rangeKmAtPark?.let { String.format(Locale.US, "%.0f km", it) } ?: state.tele.clusterRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                        val trueEco = state.trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                        val loc = state.parked?.locationName?.takeIf { it.isNotBlank() } ?: "Parked Location"

                        updateNotification(
                            title = "TVS iQube Disconnected • Last seen $lastSeenStr",
                            contentText = "Battery: $bat • Range: $dte (True: $trueEco) • 📍 $loc",
                            subText = if (state.autoReconnect) "Scanning to reconnect..." else "Disconnected",
                            isRiding = false,
                            isConnected = false
                        )

                        if (!state.autoReconnect) {
                            delay(4000L)
                            if (bleManager.connectionPhase.value == BleConnectionPhase.DISCONNECTED &&
                                !bleManager.autoReconnectEnabled.value
                            ) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
                    else -> {
                        updateNotification(
                            title = "iQube Link: ${state.phase.name}",
                            contentText = "Establishing encrypted Bluetooth channel...",
                            subText = "Pairing",
                            isRiding = false,
                            isConnected = false
                        )
                    }
                }
            }
        }
    }

    private fun updateNotification(
        title: String,
        contentText: String,
        subText: String,
        isRiding: Boolean,
        isConnected: Boolean
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, contentText, subText, isRiding, isConnected))
        }
    }

    private fun buildNotification(
        title: String,
        contentText: String,
        subText: String = "Connected",
        isRiding: Boolean = false,
        isConnected: Boolean = true
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isConnected) {
            val disconnectIntent = Intent(this, IqubeBleForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            val disconnectPendingIntent = PendingIntent.getService(
                this,
                1,
                disconnectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPendingIntent
            )
        }

        return builder.build()
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

private data class ForegroundStateSnapshot(
    val phase: BleConnectionPhase,
    val devName: String,
    val tele: com.example.model.TelemetryState,
    val nav: com.example.model.NavigationManeuverState,
    val parked: com.example.model.LastParkedLocation?,
    val trueRange: com.example.model.TrueRangeCalculation,
    val notifEnabled: Boolean,
    val autoReconnect: Boolean
)
