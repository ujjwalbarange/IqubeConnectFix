package com.example.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.ble.IqubeBleManager
import com.example.model.BleConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Android Quick Settings (Control Center) App Tile for glanceable TVS iQube battery SoC and range.
 * Displays live battery % and DTE range when connected/charging, and parked battery % when disconnected.
 * Tapping opens the app dashboard.
 */
class IqubeQuickSettingsTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val bleManager = IqubeBleManager.getInstance(applicationContext)

        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            combine(
                bleManager.connectionPhase,
                bleManager.packetParser.telemetryState,
                bleManager.lastSeenState,
                bleManager.trueRangeState
            ) { phase, tele, lastSeen, trueRange ->
                TileSnapshot(phase, tele, lastSeen, trueRange)
            }.collect { snapshot ->
                renderTile(snapshot)
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        super.onStopListening()
    }

    private fun renderTile(snapshot: TileSnapshot) {
        val tile = qsTile ?: return

        val isConnected = snapshot.phase in listOf(
            BleConnectionPhase.STREAMING_ACTIVE,
            BleConnectionPhase.REPLAYING_RIDING_LOG,
            BleConnectionPhase.REPLAYING_CHARGING_LOG
        )
        val isConnecting = snapshot.phase in listOf(
            BleConnectionPhase.SCANNING,
            BleConnectionPhase.CONNECTING_GATT,
            BleConnectionPhase.DISCOVERING_SERVICES,
            BleConnectionPhase.ENABLING_CCCD,
            BleConnectionPhase.NEGOTIATING_MTU,
            BleConnectionPhase.SENDING_HANDSHAKE
        )

        val tele = snapshot.tele
        val lastSeen = snapshot.lastSeen
        val trueRange = snapshot.trueRange

        val batteryPct = tele.batteryPercent ?: lastSeen?.batteryPct ?: 93
        val clusterRangeKm = tele.clusterRangeKm ?: lastSeen?.clusterRangeKm ?: 98.0
        val trueEcoKm = trueRange.trueEcoRangeKm ?: (batteryPct * trueRange.ecoKmPerPct)

        when {
            isConnected && tele.isCharging -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "iQube: ⚡ $batteryPct%"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = String.format(Locale.US, "Charging • %.0f km DTE", clusterRangeKm)
                }
            }
            isConnected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "iQube: $batteryPct%"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val spd = tele.speedKmh ?: 0
                    tile.subtitle = if (spd > 0) {
                        String.format(Locale.US, "%d km/h • %.0f km", spd, clusterRangeKm)
                    } else {
                        String.format(Locale.US, "%.0f km (True: %.0f)", clusterRangeKm, trueEcoKm)
                    }
                }
            }
            isConnecting -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "TVS iQube"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Connecting..."
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "iQube: $batteryPct%"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val loc = lastSeen?.locationName?.takeIf { it.isNotBlank() } ?: "Parked"
                    tile.subtitle = String.format(Locale.US, "%.0f km • %s", clusterRangeKm, loc)
                }
            }
        }

        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            runCatching { startActivity(intent) }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, IqubeQuickSettingsTileService::class.java)
                )
            }
        }
    }
}

private data class TileSnapshot(
    val phase: BleConnectionPhase,
    val tele: com.example.model.TelemetryState,
    val lastSeen: com.example.model.LastSeenScooterState?,
    val trueRange: com.example.model.TrueRangeCalculation
)
