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
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.model.BleConnectionPhase
import com.example.tile.IqubeQuickSettingsTileService
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
import kotlin.math.roundToInt

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
        val bleManager = IqubeBleManager.getInstance(applicationContext)
        val tele = bleManager.packetParser.telemetryState.value
        val lastSeen = bleManager.lastSeenState.value
        val batPct = tele.batteryPercent ?: lastSeen?.batteryPct ?: 93
        val rangeKm = (tele.clusterRangeKm ?: lastSeen?.clusterRangeKm ?: 98.0).roundToInt()

        val initialNotification = buildNotification(
            title = "TVS iQube Cockpit",
            contentText = "Active background connection • Ready for telemetry",
            isRiding = false,
            isConnected = false,
            batteryPct = batPct,
            rangeKm = rangeKm,
            isCharging = false
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var serviceType = 0
                val hasBtConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else true
                val hasLocation = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (hasBtConnect) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                if (hasLocation) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }

                if (serviceType != 0) {
                    startForeground(NOTIFICATION_ID, initialNotification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
                }
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (_: Exception) {
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
                bleManager.autoReconnectEnabled,
                bleManager.chargingMonitorState
            ) { values ->
                val phase = values[0] as BleConnectionPhase
                val devName = values[1] as String
                val tele = values[2] as com.example.model.TelemetryState
                val nav = values[3] as com.example.model.NavigationManeuverState
                val parked = values[4] as? com.example.model.LastParkedLocation
                val trueRange = values[5] as com.example.model.TrueRangeCalculation
                val notifEnabled = values[6] as Boolean
                val autoReconnect = values[7] as Boolean
                val charging = values[8] as com.example.model.ChargingMonitorState
                ForegroundStateSnapshot(phase, devName, tele, nav, parked, trueRange, notifEnabled, autoReconnect, charging)
            }.collect { state ->
                val isConnected = state.phase in listOf(
                    BleConnectionPhase.STREAMING_ACTIVE,
                    BleConnectionPhase.REPLAYING_RIDING_LOG,
                    BleConnectionPhase.REPLAYING_CHARGING_LOG
                )

                val currentBatPct = state.tele.batteryPercent
                    ?: state.charging.currentBatteryPct
                    ?: state.parked?.batteryPercentAtPark
                    ?: bleManager.lastSeenState.value?.batteryPct
                    ?: 93
                val currentRangeKm = (
                    state.tele.clusterRangeKm
                        ?: state.charging.clusterRangeKm
                        ?: state.parked?.rangeKmAtPark
                        ?: bleManager.lastSeenState.value?.clusterRangeKm
                        ?: 98.0
                    ).roundToInt()
                val isChargingNow = (isConnected && state.tele.isCharging) || state.charging.isCharging

                // Keep Quick Settings Control Center tile updated with latest battery % & range km
                IqubeQuickSettingsTileService.requestTileUpdate(applicationContext)

                if (!state.notifEnabled) {
                    updateNotification(
                        title = "iQube Cockpit",
                        contentText = when {
                            state.charging.isDisconnectedPredicted -> "Offline predicted charging ($currentBatPct%) • Needs Calibration"
                            isConnected -> "Connected in background"
                            else -> "Background service running"
                        },
                        subText = when {
                            state.charging.needsCalibration -> "Needs Calibration"
                            isConnected -> "Connected"
                            else -> "Idle"
                        },
                        isRiding = (state.tele.speedKmh ?: 0) > 0,
                        isConnected = isConnected,
                        batteryPct = currentBatPct,
                        rangeKm = currentRangeKm,
                        isCharging = isChargingNow
                    )
                } else when (state.phase) {
                    BleConnectionPhase.STREAMING_ACTIVE,
                    BleConnectionPhase.REPLAYING_RIDING_LOG,
                    BleConnectionPhase.REPLAYING_CHARGING_LOG -> {
                        val bat = state.tele.batteryPercent?.let { "$it%" } ?: "--%"
                        val dte = state.tele.clusterRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                        val trueEco = state.trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                        val truePwr = state.trueRange.truePowerRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                        val spd = state.tele.speedKmh?.let { "${it} km/h" } ?: "0 km/h"

                        if (state.tele.isCharging) {
                            val charging = state.charging
                            val to80 = charging.remainingSecondsTo80?.let {
                                val m = (it / 60).coerceAtLeast(0)
                                if (m == 0L) "Reached" else if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
                            } ?: "Estimating"
                            val to100 = charging.remainingSecondsTo100?.let {
                                val m = (it / 60).coerceAtLeast(1)
                                if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
                            } ?: "Estimating"

                            updateNotification(
                                title = "TVS iQube Charging • $bat",
                                contentText = "Range: $dte (True: $trueEco) • To 80%: $to80 • Full: $to100",
                                subText = "Charging Active",
                                isRiding = false,
                                isConnected = true,
                                batteryPct = currentBatPct,
                                rangeKm = currentRangeKm,
                                isCharging = true
                            )
                        } else {
                            updateNotification(
                                title = "TVS iQube Connected • $spd",
                                contentText = "Battery: $bat • Range: $dte (True Eco: $trueEco • Pwr: $truePwr)",
                                subText = "Live Cockpit",
                                isRiding = (state.tele.speedKmh ?: 0) > 0,
                                isConnected = true,
                                batteryPct = currentBatPct,
                                rangeKm = currentRangeKm,
                                isCharging = false
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
                            subText = if (state.charging.needsCalibration) "Needs Calibration" else "Connecting",
                            isRiding = false,
                            isConnected = false,
                            batteryPct = currentBatPct,
                            rangeKm = currentRangeKm,
                            isCharging = isChargingNow
                        )
                    }
                    BleConnectionPhase.DISCONNECTED,
                    BleConnectionPhase.RECONNECTING_BACKOFF -> {
                        if (state.charging.isDisconnectedPredicted || state.charging.isCharging) {
                            val charging = state.charging
                            val bat = "${charging.currentBatteryPct ?: currentBatPct}%"
                            val trueEco = state.trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                            val to80 = charging.remainingSecondsTo80?.let {
                                val m = (it / 60).coerceAtLeast(0)
                                if (m == 0L) "Reached" else if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
                            } ?: "Estimating"
                            val to100 = charging.remainingSecondsTo100?.let {
                                val m = (it / 60).coerceAtLeast(1)
                                if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
                            } ?: "Estimating"

                            updateNotification(
                                title = "⚡ TVS iQube Charging (Predicted) • $bat",
                                contentText = "True Range: $trueEco • To 80%: $to80 • Full: $to100 • ⚠️ Needs Calibration",
                                subText = "Needs Calibration",
                                isRiding = false,
                                isConnected = false,
                                batteryPct = currentBatPct,
                                rangeKm = currentRangeKm,
                                isCharging = true
                            )
                        } else {
                            val lastSeenStr = state.parked?.timestampMs?.let {
                                timeFormat.format(java.util.Date(it))
                            } ?: "Recent"
                            val bat = state.charging.currentBatteryPct?.let { "$it%" }
                                ?: state.parked?.batteryPercentAtPark?.let { "$it%" }
                                ?: state.tele.batteryPercent?.let { "$it%" }
                                ?: "--%"
                            val dte = state.parked?.rangeKmAtPark?.let { String.format(Locale.US, "%.0f km", it) }
                                ?: state.tele.clusterRangeKm?.let { String.format(Locale.US, "%.0f km", it) }
                                ?: "--"
                            val trueEco = state.trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"
                            val loc = state.parked?.locationName?.takeIf { it.isNotBlank() } ?: "Parked Location"
                            val calSuffix = if (state.charging.needsCalibration) " • ⚠️ Needs Calibration" else ""

                            updateNotification(
                                title = "TVS iQube Disconnected • Last seen $lastSeenStr",
                                contentText = "Battery: $bat • Range: $dte (True: $trueEco) • 📍 $loc$calSuffix",
                                subText = when {
                                    state.charging.needsCalibration -> "Needs Calibration"
                                    state.autoReconnect -> "Scanning to reconnect..."
                                    else -> "Disconnected"
                                },
                                isRiding = false,
                                isConnected = false,
                                batteryPct = currentBatPct,
                                rangeKm = currentRangeKm,
                                isCharging = false
                            )

                            if (!state.autoReconnect && !state.charging.isDisconnectedPredicted && !state.charging.isCharging) {
                                delay(4000L)
                                if (bleManager.connectionPhase.value == BleConnectionPhase.DISCONNECTED &&
                                    !bleManager.autoReconnectEnabled.value &&
                                    !bleManager.chargingMonitorState.value.isCharging
                                ) {
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                            }
                        }
                    }
                    else -> {
                        updateNotification(
                            title = "iQube Link: ${state.phase.name}",
                            contentText = "Establishing encrypted Bluetooth channel...",
                            subText = "Pairing",
                            isRiding = false,
                            isConnected = false,
                            batteryPct = currentBatPct,
                            rangeKm = currentRangeKm,
                            isCharging = isChargingNow
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
        isConnected: Boolean,
        batteryPct: Int = 93,
        rangeKm: Int = 98,
        isCharging: Boolean = false
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(title, contentText, subText, isRiding, isConnected, batteryPct, rangeKm, isCharging)
            )
        }
    }

    private fun buildNotification(
        title: String,
        contentText: String,
        subText: String = "Connected",
        isRiding: Boolean = false,
        isConnected: Boolean = true,
        batteryPct: Int = 93,
        rangeKm: Int = 98,
        isCharging: Boolean = false
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

        val dynamicBitmap = IqubeQuickSettingsTileService.createBatteryRangeTileBitmap(
            batteryPct = batteryPct.coerceIn(0, 100),
            rangeKm = rangeKm.coerceAtLeast(0),
            isCharging = isCharging
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(IconCompat.createWithBitmap(dynamicBitmap))
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
                "TVS iQube Status & Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live scooter battery SoC, range, and connected status"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val alertChannel = NotificationChannel(
                CHARGING_ALERT_CHANNEL_ID,
                "TVS iQube Charging Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Audible alarm and notifications when battery reaches target charging SoC"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
            manager?.createNotificationChannel(alertChannel)
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
        const val CHARGING_ALERT_CHANNEL_ID = "iqube_charging_alert_channel"
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
    val autoReconnect: Boolean,
    val charging: com.example.model.ChargingMonitorState
)
