package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.BleConnectionPhase
import com.example.model.PhoneStatusState
import com.example.model.RideMode
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EnergeticCoral
import com.example.ui.theme.NeonMint
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TopConnectionHeader(
    connectionPhase: BleConnectionPhase,
    deviceName: String,
    deviceMac: String,
    rssiDbm: Int?,
    negotiatedMtu: Int?,
    isIgnitionUnlocked: Boolean?,
    rideMode: RideMode?,
    isCharging: Boolean,
    isLinkDegraded: Boolean,
    linkWarningMessage: String?,
    riderGreetingName: String = "Rider",
    phoneStatusState: PhoneStatusState = PhoneStatusState(),
    onConnectBleClick: () -> Unit,
    onReplayRidingClick: () -> Unit,
    onReplayChargingClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = connectionPhase == BleConnectionPhase.STREAMING_ACTIVE ||
        connectionPhase == BleConnectionPhase.REPLAYING_RIDING_LOG ||
        connectionPhase == BleConnectionPhase.REPLAYING_CHARGING_LOG

    val statusDotColor = when {
        isLinkDegraded -> EnergeticCoral
        connectionPhase == BleConnectionPhase.STREAMING_ACTIVE -> NeonMint
        connectionPhase == BleConnectionPhase.REPLAYING_CHARGING_LOG -> NeonMint
        connectionPhase == BleConnectionPhase.REPLAYING_RIDING_LOG -> ElectricCyan
        connectionPhase == BleConnectionPhase.DISCONNECTED -> TextSecondary
        else -> AmberWarning
    }

    val statusLabel = when (connectionPhase) {
        BleConnectionPhase.DISCONNECTED -> "OFFLINE"
        BleConnectionPhase.SCANNING -> "SCANNING…"
        BleConnectionPhase.CONNECTING_GATT -> "CONNECTING…"
        BleConnectionPhase.DISCOVERING_SERVICES -> "SERVICES…"
        BleConnectionPhase.ENABLING_CCCD -> "CCCD 0x5354…"
        BleConnectionPhase.NEGOTIATING_MTU -> "MTU ${negotiatedMtu ?: 247}…"
        BleConnectionPhase.SENDING_HANDSHAKE -> "HANDSHAKE…"
        BleConnectionPhase.STREAMING_ACTIVE -> if (isCharging) "CHARGING" else "CONNECTED"
        BleConnectionPhase.REPLAYING_RIDING_LOG -> "RIDE REPLAY"
        BleConnectionPhase.REPLAYING_CHARGING_LOG -> "CHARGE REPLAY"
        BleConnectionPhase.RECONNECTING_BACKOFF -> "RECONNECTING…"
    }

    val clockTimeFormatted = remember(phoneStatusState.lastClockSyncMs) {
        phoneStatusState.lastClockSyncMs?.let {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateSurface)
            .border(
                width = 1.dp,
                color = SlateBorder,
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Scooter Identity (from 0x5A 0x7D "TVSiQUBEJI573"), Rider Welcome & MAC
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusDotColor.copy(alpha = 0.14f))
                        .border(1.dp, statusDotColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isActive -> Icons.Default.BluetoothConnected
                            connectionPhase == BleConnectionPhase.DISCONNECTED -> Icons.Default.BluetoothDisabled
                            else -> Icons.Default.Bluetooth
                        },
                        contentDescription = "BLE Connection Status",
                        tint = statusDotColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Welcome, ${riderGreetingName.ifBlank { "Rider" }}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = NeonMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("header_rider_welcome_text")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• $deviceName",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("header_vehicle_id_text")
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        val lockIcon = if (isIgnitionUnlocked == true) Icons.Default.LockOpen else Icons.Default.Lock
                        val lockTint = if (isIgnitionUnlocked == true) NeonMint else TextSecondary
                        Icon(
                            imageVector = lockIcon,
                            contentDescription = if (isIgnitionUnlocked == true) "Ignition Unlocked" else "Ignition Locked",
                            tint = lockTint,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = "$deviceMac • ${rssiDbm?.let { "$it dBm" } ?: "-- dBm"} • 0x5354/0x5352",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right: Single-line Connection Status Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, statusDotColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusDotColor,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Compact single-line scrollable Action Bar: Scan BLE, Ride Replay, Charge Replay, Clock Sync Badge, Phone Status Badge, Mode Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (connectionPhase == BleConnectionPhase.DISCONNECTED ||
                connectionPhase == BleConnectionPhase.RECONNECTING_BACKOFF
            ) {
                HeaderActionButton(
                    label = "Scan BLE",
                    icon = Icons.Default.Bluetooth,
                    accentColor = NeonMint,
                    testTag = "connect_ble_button",
                    onClick = onConnectBleClick
                )
                HeaderActionButton(
                    label = "Ride Replay (96%)",
                    icon = Icons.Default.PlayArrow,
                    accentColor = ElectricCyan,
                    testTag = "replay_hci_button",
                    onClick = onReplayRidingClick
                )
                HeaderActionButton(
                    label = "Charge Replay (38→42%)",
                    icon = Icons.Default.Bolt,
                    accentColor = AmberWarning,
                    testTag = "replay_charging_button",
                    onClick = onReplayChargingClick
                )
            } else {
                HeaderActionButton(
                    label = if (connectionPhase == BleConnectionPhase.STREAMING_ACTIVE) "Disconnect" else "Stop Replay",
                    icon = Icons.Default.Stop,
                    accentColor = EnergeticCoral,
                    testTag = "disconnect_ble_button",
                    onClick = onDisconnectClick
                )
                if (connectionPhase != BleConnectionPhase.REPLAYING_RIDING_LOG) {
                    HeaderActionButton(
                        label = "Ride Replay",
                        icon = Icons.Default.PlayArrow,
                        accentColor = ElectricCyan,
                        testTag = "switch_to_replay_button",
                        onClick = onReplayRidingClick
                    )
                }
                if (connectionPhase != BleConnectionPhase.REPLAYING_CHARGING_LOG) {
                    HeaderActionButton(
                        label = "Charge Replay",
                        icon = Icons.Default.Bolt,
                        accentColor = AmberWarning,
                        testTag = "switch_to_charging_replay_button",
                        onClick = onReplayChargingClick
                    )
                }
            }

            // Cluster Clock Sync Badge (0x5B 0x4A 0x44)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SlateCardElevated)
                    .border(
                        1.dp,
                        if (clockTimeFormatted != null) NeonMint.copy(alpha = 0.45f) else SlateBorder,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag("cluster_clock_synced_badge"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = "Cluster Clock Sync",
                    tint = if (clockTimeFormatted != null) NeonMint else TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (clockTimeFormatted != null) "Cluster Clock: Synced ($clockTimeFormatted)" else "Cluster Clock: Standby",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (clockTimeFormatted != null) NeonMint else TextSecondary,
                    maxLines = 1
                )
            }

            // Phone Battery & Status Tray Badge (0x5B 0x52 0x55)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag("phone_status_tray_badge"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryFull,
                    contentDescription = "Phone Battery Status",
                    tint = ElectricCyan,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Phone [RU: ${phoneStatusState.phoneBatteryPct}% • ${phoneStatusState.phoneBatteryBars}/4 Bars",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan,
                    maxLines = 1
                )
            }

            // Live State Badge (Charging 0x06/0x01 vs Ride Mode)
            val modeText = if (isCharging) "CHARGING" else (rideMode?.label ?: "ECO MODE")
            val modeColor = when {
                isCharging -> NeonMint
                rideMode == RideMode.POWER -> EnergeticCoral
                rideMode == RideMode.ECO -> ElectricCyan
                else -> TextSecondary
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, modeColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = modeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = modeColor,
                    maxLines = 1
                )
            }
        }

        // Link Watchdog / Supervision Timeout (0x08) Warning Banner
        AnimatedVisibility(visible = linkWarningMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (isLinkDegraded) EnergeticCoral.copy(alpha = 0.16f) else AmberWarning.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isLinkDegraded) EnergeticCoral.copy(alpha = 0.5f) else AmberWarning.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Link Warning",
                        tint = if (isLinkDegraded) EnergeticCoral else AmberWarning,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = linkWarningMessage.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.15f))
            .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = accentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            maxLines = 1
        )
    }
}
