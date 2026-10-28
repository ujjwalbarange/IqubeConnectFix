package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ble.GoogleMapsNotificationListenerService
import com.example.model.ChargingAlertConfig
import com.example.model.ChargingAlertType
import com.example.model.ChargingMonitorState
import com.example.model.GoogleMapsListenerState
import com.example.model.IncomingCallRelayState
import com.example.model.IqubeBleSpec
import com.example.model.LastSeenScooterState
import com.example.model.MediaPlayerState
import com.example.model.MusicCommand
import com.example.model.NavigationManeuverState
import com.example.model.PhoneStatusState
import com.example.model.RideMode
import com.example.model.TelemetryState
import com.example.model.TripSubcommandConfig
import com.example.model.TrueRangeCalculation
import com.example.model.TurnTypeConstant
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EnergeticCoral
import com.example.ui.theme.NeonMint
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CockpitDashboardScreen(
    telemetry: TelemetryState,
    trueRange: TrueRangeCalculation,
    chargingMonitor: ChargingMonitorState,
    lastSeenState: LastSeenScooterState?,
    incomingCallState: IncomingCallRelayState,
    navigationState: NavigationManeuverState,
    selectedRangeMode: RideMode,
    detectedLocalityName: String,
    customLocationOverride: String,
    lastSyncedClusterLocation: String?,
    autoLocationSyncEnabled: Boolean,
    tripSubcommandConfig: TripSubcommandConfig,
    onSelectRangeMode: (RideMode) -> Unit,
    onSelectTripTab: (Int) -> Unit,
    onSwitchToTripAClick: () -> Unit,
    onSwitchToTripBClick: () -> Unit,
    onResetActiveTripClick: () -> Unit,
    onDumpLocationToClusterClick: () -> Unit,
    onCustomLocationOverrideChange: (String) -> Unit,
    onSetAutoLocationSync: (Boolean) -> Unit,
    onTriggerIncomingCallClick: (String) -> Unit,
    onTriggerOutgoingCallClick: (String) -> Unit = {},
    onTriggerMissedCallClick: (String) -> Unit = {},
    onEndIncomingCallClick: () -> Unit,
    onSendNavigationManeuver: (TurnTypeConstant, Int, Byte, String, Int) -> Unit,
    onToggleNavigationRouteSim: () -> Unit,
    onClearNavigationClick: () -> Unit = {},
    onSimulateChargingStepClick: () -> Unit,
    onToggleChargingClick: () -> Unit,
    riderGreetingName: String = "Rider",
    musicTrackTitle: String = "Midnight City",
    mediaPlayerState: MediaPlayerState = MediaPlayerState(),
    phoneStatusState: PhoneStatusState = PhoneStatusState(),
    onSendMusicTrackClick: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onSimulateHandlebarKeyClick: (MusicCommand) -> Unit = {},
    onSyncRiderGreetingClick: (String) -> Unit = {},
    onSendPhoneStatusRelayClick: () -> Unit = {},
    onSetPhoneBatteryOverride: (Int) -> Unit = {},
    onSyncClockNowClick: () -> Unit = {},
    googleMapsListenerState: GoogleMapsListenerState = GoogleMapsListenerState(),
    onSetGoogleMapsAutoForward: (Boolean) -> Unit = {},
    onSimulateGoogleMapsNotificationClick: () -> Unit = {},
    statusNotificationsEnabled: Boolean = true,
    onSetStatusNotificationsEnabled: (Boolean) -> Unit = {},
    autoReconnectEnabled: Boolean = true,
    onSetAutoReconnectEnabled: (Boolean) -> Unit = {},
    onRequestPhonePermissions: () -> Unit = {},
    chargingAlertConfig: ChargingAlertConfig = ChargingAlertConfig(),
    onSetChargingAlertEnabled: (Boolean) -> Unit = {},
    onSetChargingAlertTargetPct: (Int) -> Unit = {},
    onSetChargingAlertType: (ChargingAlertType) -> Unit = {},
    onStopChargingAlarm: () -> Unit = {},
    onTestChargingAlert: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isOfflineUsingLastSeen = telemetry.batteryPercent == null && lastSeenState != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 0A. ACTIVE INCOMING / OUTGOING CALL BANNER (When Active on Cluster 0x5B 0x43)
            AnimatedVisibility(visible = incomingCallState.isRinging) {
                IncomingCallActiveBanner(
                    incomingCallState = incomingCallState,
                    topLocalityName = lastSyncedClusterLocation ?: detectedLocalityName,
                    onEndCallClick = onEndIncomingCallClick
                )
            }

            // 0C. OFFLINE / DISCONNECTED "LAST SEEN" SNAPSHOT BANNER
            AnimatedVisibility(visible = isOfflineUsingLastSeen && lastSeenState != null) {
                if (lastSeenState != null) {
                    OfflineLastSeenSnapshotCard(
                        lastSeen = lastSeenState,
                        trueRange = trueRange
                    )
                }
            }

            // 1. HERO VISUALIZER: Dynamic Eco vs. Power Mode True Range Meter + Three-Way Comparison Card
            HeroBatteryAndTrueRangeCard(
                telemetry = telemetry,
                lastSeenState = lastSeenState,
                trueRange = trueRange,
                selectedRangeMode = selectedRangeMode,
                onSelectRangeMode = onSelectRangeMode,
                onToggleChargingClick = onToggleChargingClick
            )

            // 2. LIVE CHARGING MONITOR VIEW (Debounced 3-Frame Gate: 0x11 B5==0x01 && 0x13 B5==0x06 && speed==0)
            AnimatedVisibility(visible = telemetry.isCharging) {
                LiveChargingMonitorCard(
                    telemetry = telemetry,
                    chargingMonitor = chargingMonitor,
                    trueRange = trueRange,
                    onSimulateChargingStepClick = onSimulateChargingStepClick
                )
            }

            // 3. LIVE TELEMETRY STRIP: Speed + Odometer (0x10 Bytes 2..5) & Hardware Trip A / Trip B (0x11)
            LiveTelemetryStripSection(
                telemetry = telemetry,
                lastSeenState = lastSeenState,
                selectedRangeMode = selectedRangeMode,
                onSelectRangeMode = onSelectRangeMode,
                onSelectTripTab = onSelectTripTab
            )

            // 4. VERIFIED TRIP CONTROL DECK (Handle 0x0022: Switch to Trip A, Switch to Trip B, Reset Active Trip A/B)
            VerifiedTripControlsCard(
                tripSubcommandConfig = tripSubcommandConfig,
                onSwitchToTripAClick = onSwitchToTripAClick,
                onSwitchToTripBClick = onSwitchToTripBClick,
                onResetActiveTripClick = onResetActiveTripClick
            )

            // 5. TURN-BY-TURN NAVIGATION INJECTOR (`0x5B 0x4F` -> 400ms -> `0x5A/0x5B 0x4E`) + GOOGLE MAPS LISTENER
            TurnByTurnNavigationInjectorCard(
                navigationState = navigationState,
                googleMapsListenerState = googleMapsListenerState,
                onSendManeuver = onSendNavigationManeuver,
                onToggleRouteSimulation = onToggleNavigationRouteSim,
                onClearNavigation = onClearNavigationClick,
                onSetGoogleMapsAutoForward = onSetGoogleMapsAutoForward,
                onSimulateGoogleMapsNotificationClick = onSimulateGoogleMapsNotificationClick
            )

            // 6. LIVE CLUSTER LOCATION SYNC CARD (Top Location Line `0x5B 0x5C` vs Lower Cyan Banner `0x5B 0x43`/`0x5B 0x62`)
            ClusterLocationSyncCard(
                detectedLocalityName = detectedLocalityName,
                customLocationOverride = customLocationOverride,
                lastSyncedClusterLocation = lastSyncedClusterLocation,
                autoLocationSyncEnabled = autoLocationSyncEnabled,
                isCallRinging = incomingCallState.isRinging,
                activeCallerText = incomingCallState.clusterFormattedText,
                mediaPlayerState = mediaPlayerState,
                onCustomLocationOverrideChange = onCustomLocationOverrideChange,
                onSetAutoLocationSync = onSetAutoLocationSync,
                onDumpLocationNowClick = onDumpLocationToClusterClick
            )

            // 7. CALL TEST RELAY (Incoming Call `0x00`, Outgoing Call `0x04`, Missed Call `0x03`, End Call `0x02`)
            IncomingCallClusterRelayCard(
                incomingCallState = incomingCallState,
                detectedLocalityName = detectedLocalityName,
                onTriggerCall = onTriggerIncomingCallClick,
                onTriggerOutgoingCall = onTriggerOutgoingCallClick,
                onTriggerMissedCall = onTriggerMissedCallClick,
                onEndCall = onEndIncomingCallClick
            )

            // 8. MEDIA / MUSIC TRACK BANNER (`0x5B 0x62`) & HANDLEBAR SWITCHES (`0x5A 0x6B`) + PHONE STATUS (`0x5B 0x52 0x55`) + CLOCK (`0x5B 0x4A 0x44`) + RIDER GREETING (`0x5B 0x52`)
            ClusterMusicAndRiderGreetingCard(
                riderGreetingName = riderGreetingName,
                musicTrackTitle = musicTrackTitle,
                mediaPlayerState = mediaPlayerState,
                phoneStatusState = phoneStatusState,
                onSendMusicTrackClick = onSendMusicTrackClick,
                onSimulateHandlebarKeyClick = onSimulateHandlebarKeyClick,
                onSyncRiderGreetingClick = onSyncRiderGreetingClick,
                onSendPhoneStatusRelayClick = onSendPhoneStatusRelayClick,
                onSetPhoneBatteryOverride = onSetPhoneBatteryOverride,
                onSyncClockNowClick = onSyncClockNowClick
            )

            // 9. APP SETTINGS & BACKGROUND AUTOMATION CARD
            AppSettingsAndBackgroundSyncCard(
                statusNotificationsEnabled = statusNotificationsEnabled,
                onSetStatusNotificationsEnabled = onSetStatusNotificationsEnabled,
                autoReconnectEnabled = autoReconnectEnabled,
                onSetAutoReconnectEnabled = onSetAutoReconnectEnabled,
                onRequestPhonePermissions = onRequestPhonePermissions,
                chargingAlertConfig = chargingAlertConfig,
                onSetChargingAlertEnabled = onSetChargingAlertEnabled,
                onSetChargingAlertTargetPct = onSetChargingAlertTargetPct,
                onSetChargingAlertType = onSetChargingAlertType,
                onStopChargingAlarm = onStopChargingAlarm,
                onTestChargingAlert = onTestChargingAlert
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppSettingsAndBackgroundSyncCard(
    statusNotificationsEnabled: Boolean,
    onSetStatusNotificationsEnabled: (Boolean) -> Unit,
    autoReconnectEnabled: Boolean,
    onSetAutoReconnectEnabled: (Boolean) -> Unit,
    onRequestPhonePermissions: () -> Unit,
    chargingAlertConfig: ChargingAlertConfig,
    onSetChargingAlertEnabled: (Boolean) -> Unit,
    onSetChargingAlertTargetPct: (Int) -> Unit,
    onSetChargingAlertType: (ChargingAlertType) -> Unit,
    onStopChargingAlarm: () -> Unit,
    onTestChargingAlert: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "SETTINGS & BACKGROUND AUTOMATION",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ElectricCyan
        )

        // 1. Status & Disconnection Notifications Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateCardElevated)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Status & Connection Notifications",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Clean status notification showing live battery %, cluster range, and True Range when connected, or last seen time when disconnected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = statusNotificationsEnabled,
                onCheckedChange = onSetStatusNotificationsEnabled,
                modifier = Modifier.testTag("status_notification_switch")
            )
        }

        // 2. Persistent Auto-Reconnect Scanner Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateCardElevated)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Background Auto-Reconnect",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Automatically scans for and reconnects to your TVS iQube when you approach the vehicle, even when the screen is off or the app is minimized.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = autoReconnectEnabled,
                onCheckedChange = onSetAutoReconnectEnabled,
                modifier = Modifier.testTag("auto_reconnect_switch")
            )
        }

        // 3. Smart Battery Charging Target Alert
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateCardElevated)
                .border(1.dp, if (chargingAlertConfig.isAlarmRinging) EnergeticCoral else SlateBorder, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Active Alarm Banner when ringing
            if (chargingAlertConfig.isAlarmRinging) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnergeticCoral.copy(alpha = 0.2f))
                        .border(1.dp, EnergeticCoral, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Alarm,
                            contentDescription = "Alarm Active",
                            tint = EnergeticCoral,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🚨 Target Reached (${chargingAlertConfig.targetPct}%)!",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = EnergeticCoral
                        )
                    }
                    Button(
                        onClick = onStopChargingAlarm,
                        colors = ButtonDefaults.buttonColors(containerColor = EnergeticCoral),
                        modifier = Modifier.testTag("stop_charging_alarm_button")
                    ) {
                        Icon(Icons.Default.AlarmOff, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop Alarm", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "⚡ Charging Target SoC Alert",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Alerts you when the scooter charges to ${chargingAlertConfig.targetPct}% (80% optimal for battery lifespan).",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = chargingAlertConfig.isEnabled,
                    onCheckedChange = onSetChargingAlertEnabled,
                    modifier = Modifier.testTag("charging_alert_enabled_switch")
                )
            }

            if (chargingAlertConfig.isEnabled) {
                // Target SoC Chips
                Text(
                    text = "TARGET PERCENTAGE: ${chargingAlertConfig.targetPct}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        50 to "50%",
                        80 to "80% (Health)",
                        90 to "90%",
                        100 to "100% (Full)"
                    ).forEach { (pct, label) ->
                        val isSelected = chargingAlertConfig.targetPct == pct
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ElectricCyan.copy(alpha = 0.22f) else SlateSurface)
                                .border(1.dp, if (isSelected) ElectricCyan else SlateBorder, RoundedCornerShape(8.dp))
                                .clickable { onSetChargingAlertTargetPct(pct) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("charging_target_chip_$pct")
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) ElectricCyan else TextPrimary
                            )
                        }
                    }
                }

                Slider(
                    value = chargingAlertConfig.targetPct.toFloat(),
                    onValueChange = { onSetChargingAlertTargetPct(it.roundToInt()) },
                    valueRange = 50f..100f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = NeonMint
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("charging_target_slider")
                )

                // Alert Type Selector: Notification vs Alarm
                Text(
                    text = "ALERT TYPE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isNotification = chargingAlertConfig.alertType == ChargingAlertType.NOTIFICATION
                    val isAlarm = chargingAlertConfig.alertType == ChargingAlertType.ALARM

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isNotification) ElectricCyan.copy(alpha = 0.2f) else SlateSurface)
                            .border(1.dp, if (isNotification) ElectricCyan else SlateBorder, RoundedCornerShape(8.dp))
                            .clickable { onSetChargingAlertType(ChargingAlertType.NOTIFICATION) }
                            .padding(vertical = 10.dp, horizontal = 8.dp)
                            .testTag("alert_type_notification_chip"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "Notification",
                                tint = if (isNotification) ElectricCyan else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Notification Only",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isNotification) FontWeight.Bold else FontWeight.Normal,
                                color = if (isNotification) ElectricCyan else TextPrimary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isAlarm) EnergeticCoral.copy(alpha = 0.2f) else SlateSurface)
                            .border(1.dp, if (isAlarm) EnergeticCoral else SlateBorder, RoundedCornerShape(8.dp))
                            .clickable { onSetChargingAlertType(ChargingAlertType.ALARM) }
                            .padding(vertical = 10.dp, horizontal = 8.dp)
                            .testTag("alert_type_alarm_chip"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Sound Alarm",
                                tint = if (isAlarm) EnergeticCoral else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Sound Alarm",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isAlarm) FontWeight.Bold else FontWeight.Normal,
                                color = if (isAlarm) EnergeticCoral else TextPrimary
                            )
                        }
                    }
                }

                // Test Alert Button
                OutlinedButton(
                    onClick = onTestChargingAlert,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_charging_alert_button")
                ) {
                    Text(
                        text = "Test ${chargingAlertConfig.targetPct}% ${if (chargingAlertConfig.alertType == ChargingAlertType.ALARM) "Alarm" else "Notification"} Now",
                        color = ElectricCyan,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 4. Live Phone & Contact Alerts Permission
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateCardElevated)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "📞 Phone Call & Contact Alerts on Cluster",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = "When granted, incoming calls automatically display the caller's name on your scooter cluster banner in real-time, matching TVS SmartXonnect specifications.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Button(
                onClick = onRequestPhonePermissions,
                colors = ButtonDefaults.buttonColors(containerColor = NeonMint),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("grant_phone_permissions_button")
            ) {
                Text(
                    text = "Grant Phone & Contacts Permissions",
                    color = androidx.compose.ui.graphics.Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 5. Home Screen Cockpit Widget Information Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateCardElevated)
                .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "📱 Home Screen Cockpit Widget Available",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan
            )
            Text(
                text = "Add the iQube Cockpit Widget to your home screen! It displays live battery %, cluster DTE, True Range, and when charging, shows live charging speed, time until 80%, and time to full.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        // 6. Control Center / Quick Settings Tile Guide
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateCardElevated)
                .border(1.dp, NeonMint.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "🎛️ Control Center Quick Settings Tile Available",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = NeonMint
            )
            Text(
                text = "Swipe down twice from the top of your phone screen, tap the Edit (pencil) button, and drag the 'iQube Cockpit' tile into your active Quick Settings. You will see live battery % and range at a glance anytime!",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun IncomingCallActiveBanner(
    incomingCallState: IncomingCallRelayState,
    topLocalityName: String,
    onEndCallClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AmberWarning.copy(alpha = 0.18f))
            .border(1.5.dp, AmberWarning, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("incoming_call_active_banner"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = "Incoming Call Active",
                tint = AmberWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "INCOMING CALL: ${incomingCallState.extractedFirstName.uppercase()} (0x5B 0x43)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Top Pin [0x5C]: 📍 $topLocalityName  |  Lower Cyan Banner [0x43 @ 500ms]: \"${incomingCallState.clusterFormattedText}\" (#${incomingCallState.packetsTransmittedCount})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberWarning
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onEndCallClick,
            colors = ButtonDefaults.buttonColors(containerColor = EnergeticCoral, contentColor = SlateSurface),
            modifier = Modifier.testTag("banner_end_call_button")
        ) {
            Icon(Icons.Default.CallEnd, contentDescription = "End Call", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("End & Restore GPS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HandlebarMediaEventToastBanner(
    mediaPlayerState: MediaPlayerState
) {
    val cmd = mediaPlayerState.lastHandlebarCommand ?: return
    val timeFmt = remember(mediaPlayerState.lastHandlebarCommandTimestampMs) {
        mediaPlayerState.lastHandlebarCommandTimestampMs?.let {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it))
        } ?: "--:--:--"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ElectricCyan.copy(alpha = 0.16f))
            .border(1.dp, ElectricCyan, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("handlebar_media_toast_banner"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "Handlebar Switch Event",
                tint = ElectricCyan,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = String.format(Locale.US, "HANDLEBAR MEDIA KEY (0x5A 0x6B Byte[2]=0x%02X): %s", cmd.code, cmd.label.uppercase()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )
                Text(
                    text = "Now ${if (mediaPlayerState.isPlaying) "Playing" else "Paused"}: \"${mediaPlayerState.trackTitle}\" — ${mediaPlayerState.artistName} • Vol ${mediaPlayerState.volumePercent}% ($timeFmt)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun OfflineLastSeenSnapshotCard(
    lastSeen: LastSeenScooterState,
    trueRange: TrueRangeCalculation
) {
    val context = LocalContext.current
    val exactTimeStr = remember(lastSeen.timestampMs) {
        SimpleDateFormat("hh:mm a • dd MMM", Locale.US).format(Date(lastSeen.timestampMs))
    }
    val elapsedMinutes = max(0L, (System.currentTimeMillis() - lastSeen.timestampMs) / 60_000L)
    val relativeStr = when {
        elapsedMinutes == 0L -> "Just now"
        elapsedMinutes == 1L -> "1 min ago"
        elapsedMinutes < 60L -> "$elapsedMinutes mins ago"
        else -> "${elapsedMinutes / 60L}h ${elapsedMinutes % 60L}m ago"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SlateCardElevated)
            .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("offline_last_seen_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Offline Last Seen Snapshot",
                    tint = ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "OFFLINE LAST SEEN STATE (${lastSeen.vehicleId})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )
            }
            Text(
                text = "Last seen $relativeStr ($exactTimeStr)",
                style = MaterialTheme.typography.labelSmall,
                color = NeonMint
            )
        }

        Text(
            text = "Location: ${lastSeen.locationName} (${String.format(Locale.US, "%.4f, %.4f", lastSeen.latitude, lastSeen.longitude)})  •  Last SoC: ${lastSeen.batteryPct}%  •  ODO: ${String.format(Locale.US, "%,.1f km", lastSeen.odometerKm)}  •  Cluster BMS DTE: ${String.format(Locale.US, "%.1f km", lastSeen.clusterRangeKm)}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Expected True Range at ${lastSeen.batteryPct}%: Eco ${trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--"}  |  Power ${trueRange.truePowerRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--"}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = NeonMint,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    try {
                        val mapsUri = Uri.parse(
                            String.format(
                                Locale.US,
                                "https://www.google.com/maps?q=%.6f,%.6f",
                                lastSeen.latitude,
                                lastSeen.longitude
                            )
                        )
                        val intent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.testTag("find_my_scooter_maps_button")
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Find My Scooter on Maps",
                    tint = ElectricCyan,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Find My Scooter on Maps",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HeroBatteryAndTrueRangeCard(
    telemetry: TelemetryState,
    lastSeenState: LastSeenScooterState?,
    trueRange: TrueRangeCalculation,
    selectedRangeMode: RideMode,
    onSelectRangeMode: (RideMode) -> Unit,
    onToggleChargingClick: () -> Unit
) {
    val effectiveSoc = telemetry.batteryPercent ?: lastSeenState?.batteryPct
    val effectiveClusterRangeKm = telemetry.clusterRangeKm ?: lastSeenState?.clusterRangeKm
    val isLive = telemetry.batteryPercent != null
    val isPowerMode = selectedRangeMode == RideMode.POWER

    val displayedTrueRange = if (isPowerMode) {
        trueRange.truePowerRangeKm
    } else {
        trueRange.trueEcoRangeKm
    }

    // Dynamic Circular Gauge Percentage: scales with Eco (100%) vs Power (75% of Eco) when toggling mode!
    val modeScaleFactor = if (isPowerMode) IqubeBleSpec.POWER_TO_ECO_RATIO.toFloat() else 1.0f
    val targetSweep = if (effectiveSoc != null) {
        ((effectiveSoc / 100f) * modeScaleFactor * 260f).coerceIn(0f, 260f)
    } else 0f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "soc_arc_sweep"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "charging_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "charging_pulse_alpha"
    )

    val activeModeColor = if (isPowerMode) AmberWarning else NeonMint

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(SlateCardElevated, SlateSurface)
                )
            )
            .border(1.dp, if (telemetry.isCharging) NeonMint.copy(alpha = pulseAlpha) else SlateBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isLive) "BATTERY SoC (0x13) & DYNAMIC TRUE RANGE" else "LAST SEEN BATTERY SoC & EXPECTED RANGE",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (telemetry.isCharging) NeonMint.copy(alpha = 0.18f) else SlateSurface)
                    .border(
                        1.dp,
                        if (telemetry.isCharging) NeonMint.copy(alpha = pulseAlpha) else SlateBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(onClick = onToggleChargingClick)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("charging_state_pill"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Charging State",
                    tint = if (telemetry.isCharging) NeonMint else TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (telemetry.isCharging) {
                        "CHARGING ACTIVE (0x06 / 0x01)"
                    } else {
                        "TAP FOR CHARGE REPLAY"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (telemetry.isCharging) NeonMint else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive Eco / Power Mode Toggle Switch directly on the Range Meter Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateSurface)
                .border(1.dp, activeModeColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isPowerMode) "ACTIVE METER: TRUE POWER RANGE (0.75x)" else "ACTIVE METER: TRUE ECO RANGE (1.00x)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = activeModeColor
                )
                Text(
                    text = "Toggle switch to dynamically update gauge between Eco and Power mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ECO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (!isPowerMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (!isPowerMode) NeonMint else TextSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = isPowerMode,
                    onCheckedChange = { checked ->
                        onSelectRangeMode(if (checked) RideMode.POWER else RideMode.ECO)
                    },
                    modifier = Modifier.testTag("eco_power_mode_switch")
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "POWER",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isPowerMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isPowerMode) AmberWarning else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Circular Arc Gauge
        Box(
            modifier = Modifier.size(210.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 16.dp.toPx()
                val inset = strokeWidth / 2f + 8.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)

                drawArc(
                    color = SlateBorder,
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                if (animatedSweep > 0.5f) {
                    val arcBrush = if (isPowerMode) {
                        Brush.sweepGradient(colors = listOf(AmberWarning, EnergeticCoral, AmberWarning))
                    } else {
                        Brush.sweepGradient(colors = listOf(ElectricCyan, NeonMint, ElectricCyan))
                    }
                    drawArc(
                        brush = arcBrush,
                        startAngle = 140f,
                        sweepAngle = animatedSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = displayedTrueRange?.let { "${it.roundToInt()} km" } ?: "-- km",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (displayedTrueRange != null) activeModeColor else TextMuted,
                    modifier = Modifier.testTag("estimated_range_text")
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isPowerMode) "True Power Range" else "True Eco Range",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = activeModeColor,
                    modifier = Modifier.testTag("active_range_mode_label")
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = effectiveSoc?.let { "Battery SoC: $it %" } ?: "Battery SoC: -- %",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.testTag("battery_soc_text")
                )
                Text(
                    text = effectiveClusterRangeKm?.let {
                        String.format(Locale.US, "Cluster BMS DTE: %.1f km", it)
                    } ?: "Cluster BMS DTE: --.- km",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    modifier = Modifier.testTag("cluster_bms_dte_text")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Three-Way Side-by-Side Comparison Card: Scooter Cluster DTE (BMS) vs Learned True Eco vs Learned True Power
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Scooter Cluster DTE (BMS Estimate from 0x5A 0x13 Byte [3..4] / 10.0)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateSurface)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .testTag("three_way_bms_dte_card")
            ) {
                Text(
                    text = "CLUSTER DTE (BMS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = effectiveClusterRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "0x5A 0x13 B[3..4]/10",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            // 2. Learned True Eco Range
            val isEcoSelected = !isPowerMode
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isEcoSelected) NeonMint.copy(alpha = 0.16f) else SlateSurface)
                    .border(
                        1.dp,
                        if (isEcoSelected) NeonMint else SlateBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectRangeMode(RideMode.ECO) }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .testTag("true_range_eco_card")
            ) {
                Text(
                    text = "LEARNED TRUE ECO",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isEcoSelected) NeonMint else TextSecondary
                )
                Text(
                    text = trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = String.format(Locale.US, "%.2f km / 1%%", trueRange.ecoKmPerPct),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )
            }

            // 3. Learned True Power Range (75% of learned efficiency)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isPowerMode) AmberWarning.copy(alpha = 0.16f) else SlateSurface)
                    .border(
                        1.dp,
                        if (isPowerMode) AmberWarning else SlateBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectRangeMode(RideMode.POWER) }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .testTag("true_range_power_card")
            ) {
                Text(
                    text = "LEARNED POWER",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPowerMode) AmberWarning else TextSecondary
                )
                Text(
                    text = trueRange.truePowerRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = String.format(Locale.US, "%.2f km / 1%% (0.75x)", trueRange.powerKmPerPct),
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberWarning
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // True Range Data Source Badge + 0x13/0x11 Flags
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SlateSurface)
                .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = trueRange.calculationSourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (trueRange.isUsingLearnedEfficiency) NeonMint else ElectricCyan,
                modifier = Modifier.testTag("true_range_source_label")
            )
            val flag0x13 = telemetry.stateFlag0x13Byte5?.let { String.format("0x%02X", it) } ?: "--"
            val flag0x11 = telemetry.chargingFlag0x11Byte5?.let { String.format("0x%02X", it) } ?: "--"
            Text(
                text = "13[B5]:$flag0x13 • 11[B5]:$flag0x11",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (telemetry.isCharging) NeonMint else TextSecondary
            )
        }
    }
}

@Composable
private fun LiveChargingMonitorCard(
    telemetry: TelemetryState,
    chargingMonitor: ChargingMonitorState,
    trueRange: TrueRangeCalculation,
    onSimulateChargingStepClick: () -> Unit
) {
    val etaFmt = remember { SimpleDateFormat("hh:mm a", Locale.US) }
    val nowMs = System.currentTimeMillis()

    val eta80Clock = chargingMonitor.remainingSecondsTo80?.let {
        etaFmt.format(Date(nowMs + it * 1000L))
    } ?: "--:--"
    val eta100Clock = chargingMonitor.remainingSecondsTo100?.let {
        etaFmt.format(Date(nowMs + it * 1000L))
    } ?: "--:--"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(1.5.dp, NeonMint.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .testTag("live_charging_monitor_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Charging Active",
                    tint = NeonMint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "CHARGING ACTIVE (3-FRAME DEBOUNCED) • 3-TIER TIME PREDICTOR",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeonMint
                    )
                    Text(
                        text = chargingMonitor.predictionModeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chargingMonitor.isUsingLiveStepTiming) ElectricCyan else AmberWarning
                    )
                }
            }
            OutlinedButton(
                onClick = onSimulateChargingStepClick,
                modifier = Modifier.testTag("simulate_1pct_step_button")
            ) {
                Text("+1% Step (115.7s)", color = NeonMint, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Live SoC, Cluster Range (0x13), True Eco Range & Speed (+31.1%/hr = 115.7s/%)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .padding(10.dp)
            ) {
                Text("LIVE SoC & GAIN", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(
                    text = "${chargingMonitor.currentBatteryPct ?: "--"}% (+${chargingMonitor.pctGainedThisSession}%)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeonMint
                )
                Text(
                    text = "0x11 Mirror: ${telemetry.socMirror0x11 ?: "--"}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .padding(10.dp)
            ) {
                Text("CLUSTER vs TRUE ECO", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(
                    text = "${telemetry.clusterRangeKm?.let { String.format(Locale.US, "%.1f", it) } ?: "--"} / ${trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.1f", it) } ?: "--"} km",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )
                Text(
                    text = "0x13 B3..4 vs True Eco",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .padding(10.dp)
            ) {
                Text("CHARGE SPEED", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(
                    text = "+${chargingMonitor.chargingSpeedPctPerHour}% / hr",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AmberWarning
                )
                Text(
                    text = "${chargingMonitor.activeSecondsPerPct}s per +1%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        // Countdown & Clock ETAs for Estimated Time to 80% and Estimated Time to 100%
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "ESTIMATED TIME TO 80%",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = formatDurationSeconds(chargingMonitor.remainingSecondsTo80),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.testTag("charging_eta_80_text")
                )
                Text(
                    text = "80% at $eta80Clock  •  Base: ${formatDurationSeconds(chargingMonitor.baselineRemainingSecondsTo80)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, NeonMint.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "ESTIMATED TIME TO FULL (100%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )
                Text(
                    text = formatDurationSeconds(chargingMonitor.remainingSecondsTo100),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.testTag("charging_eta_100_text")
                )
                Text(
                    text = "Full at $eta100Clock  •  Base: ${formatDurationSeconds(chargingMonitor.baselineRemainingSecondsTo100)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

private fun formatDurationSeconds(seconds: Long?): String {
    if (seconds == null) return "--h --m"
    if (seconds <= 0L) return "REACHED"
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%dh %02dm", hrs, mins)
    } else {
        String.format(Locale.US, "%dm %02ds", mins, secs)
    }
}

@Composable
private fun LiveTelemetryStripSection(
    telemetry: TelemetryState,
    lastSeenState: LastSeenScooterState?,
    selectedRangeMode: RideMode,
    onSelectRangeMode: (RideMode) -> Unit,
    onSelectTripTab: (Int) -> Unit
) {
    val effectiveOdoKm = telemetry.odometerKm ?: lastSeenState?.odometerKm
    val effectiveTripAKm = telemetry.tripAKm ?: lastSeenState?.tripAKm
    val effectiveTripBKm = telemetry.tripBKm ?: lastSeenState?.tripBKm

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "UNFILTERED 20-BYTE TELEMETRY (0x10 SPEED+ODO • 0x11 TRIPS+CHG • 0x7D ID)",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Card: Real-Time Speedometer & Total Hardware Odometer (0x5A 0x10 Bytes 2..5)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Wheel Speed",
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SPEED (0x10 B2)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "PEAK ${telemetry.peakSpeedKmh} km/h",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (telemetry.peakSpeedKmh >= 45) EnergeticCoral else NeonMint
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = telemetry.speedKmh?.toString() ?: "0",
                            style = MaterialTheme.typography.displayLarge,
                            color = if ((telemetry.speedKmh ?: 0) >= 48) EnergeticCoral else TextPrimary,
                            modifier = Modifier.testTag("live_speed_text")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "km/h",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Quick Eco / Power pill on Speedometer Card
                    val isPower = selectedRangeMode == RideMode.POWER
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isPower) AmberWarning.copy(alpha = 0.18f) else NeonMint.copy(alpha = 0.18f))
                            .border(1.dp, if (isPower) AmberWarning else NeonMint, RoundedCornerShape(8.dp))
                            .clickable { onSelectRangeMode(if (isPower) RideMode.ECO else RideMode.POWER) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("speedometer_mode_pill")
                    ) {
                        Text(
                            text = if (isPower) "POWER" else "ECO",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isPower) AmberWarning else NeonMint
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val accelText = telemetry.accelerationMs2?.let {
                    String.format(Locale.US, "%+.2f m/s²", it)
                } ?: "0.00 m/s²"
                Text(
                    text = "ACCEL: $accelText • ID: ${telemetry.vehicleIdString ?: IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateCardElevated)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "0–30: ${telemetry.sprint0To30Sec?.let { "${it}s" } ?: "--.-s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    Text(
                        text = "0–40: ${telemetry.sprint0To40Sec?.let { "${it}s" } ?: "--.-s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonMint
                    )
                }
            }

            // Right Card: Switchable Trip A (0x11 B2..4) / Trip B (0x11 B7..9) / Total ODO (0x10 B3..5 = 35,925.2 km)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                val tabs = listOf("TRIP A", "TRIP B", "ODO", "SUB-M")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tabs.forEachIndexed { idx, title ->
                        val selected = telemetry.activeTripTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) ElectricCyan.copy(alpha = 0.2f) else SlateCardElevated)
                                .border(
                                    1.dp,
                                    if (selected) ElectricCyan else SlateBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onSelectTripTab(idx) }
                                .padding(vertical = 4.dp)
                                .testTag("trip_tab_$idx"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) ElectricCyan else TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val displayedDistanceText = when (telemetry.activeTripTab) {
                    0 -> effectiveTripAKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km"
                    1 -> effectiveTripBKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km"
                    2 -> effectiveOdoKm?.let { String.format(Locale.US, "%,.1f km", it) } ?: "--.- km"
                    else -> String.format(Locale.US, "%.1f m", telemetry.integratedDistanceMeters)
                }

                val subLabelText = when (telemetry.activeTripTab) {
                    0 -> "Hardware Trip A (0x11 B2..4)  •  Trip B: ${effectiveTripBKm?.let { "$it km" } ?: "--"}"
                    1 -> "Hardware Trip B (0x11 B7..9)  •  Trip A: ${effectiveTripAKm?.let { "$it km" } ?: "--"}"
                    2 -> "Total Hardware Odometer (0x10 B3..5 uint24 BE)"
                    else -> "Integrated v(t)*dt (Cluster Step: ${String.format(Locale.US, "%.1f km", telemetry.clusterIntegratedStepKm)})"
                }

                Text(
                    text = displayedDistanceText,
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    modifier = Modifier.testTag("active_trip_distance_text")
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = subLabelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                val isOdoSynced = telemetry.lastOdometerSyncMs != null &&
                    (System.currentTimeMillis() - telemetry.lastOdometerSyncMs < 2200L)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateCardElevated)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ODO (0x10 B3..5): ${effectiveOdoKm?.let { String.format(Locale.US, "%,.1f km", it) } ?: "-- km"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isOdoSynced) NeonMint else TextMuted)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "24B BE",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOdoSynced) NeonMint else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerifiedTripControlsCard(
    tripSubcommandConfig: TripSubcommandConfig,
    onSwitchToTripAClick: () -> Unit,
    onSwitchToTripBClick: () -> Unit,
    onResetActiveTripClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VERIFIED TRIP CONTROLS (0x5352 / HANDLE 0x0022 • 5A F1 00 … FF)",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = "BYTE 19 = 0xFF",
                style = MaterialTheme.typography.labelSmall,
                color = NeonMint
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2
        ) {
            CommandShortcutButton(
                title = "Switch to Trip A",
                subtitle = String.format(Locale.US, "5A F1 00 %02X … FF", tripSubcommandConfig.switchTripAByte),
                icon = Icons.Default.SwapHoriz,
                accentColor = NeonMint,
                testTag = "cmd_switch_trip_a_button",
                onClick = onSwitchToTripAClick,
                modifier = Modifier.weight(1f)
            )
            CommandShortcutButton(
                title = "Switch to Trip B",
                subtitle = String.format(Locale.US, "5A F1 00 %02X … FF", tripSubcommandConfig.switchTripBByte),
                icon = Icons.Default.SwapHoriz,
                accentColor = ElectricCyan,
                testTag = "cmd_switch_trip_b_button",
                onClick = onSwitchToTripBClick,
                modifier = Modifier.weight(1f)
            )
        }

        CommandShortcutButton(
            title = "Reset Active Trip (A/B)",
            subtitle = String.format(
                Locale.US,
                "5A F1 00 %02X … FF (Resets whichever trip is currently active on scooter display)",
                tripSubcommandConfig.resetActiveTripByte
            ),
            icon = Icons.Default.Refresh,
            accentColor = EnergeticCoral,
            testTag = "cmd_reset_active_trip_button",
            onClick = onResetActiveTripClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ClusterLocationSyncCard(
    detectedLocalityName: String,
    customLocationOverride: String,
    lastSyncedClusterLocation: String?,
    autoLocationSyncEnabled: Boolean,
    isCallRinging: Boolean,
    activeCallerText: String,
    mediaPlayerState: MediaPlayerState,
    onCustomLocationOverrideChange: (String) -> Unit,
    onSetAutoLocationSync: (Boolean) -> Unit,
    onDumpLocationNowClick: () -> Unit
) {
    val topLocationPinText = (lastSyncedClusterLocation ?: customLocationOverride.ifBlank { detectedLocalityName }).ifBlank { "Dhantoli" }
    val lowerCyanBannerText = when {
        isCallRinging -> "📞 CALL: $activeCallerText (0x5B 0x43 @ 500ms)"
        mediaPlayerState.isPlaying -> "🎵 ${mediaPlayerState.trackTitle} (0x5B 0x62)"
        else -> "Standby (Caller 0x43 / Music 0x62)"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Cluster Location Sync",
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "INDEPENDENT CLUSTER DISPLAY ZONES (TOP 0x5C PIN vs LOWER CYAN BANNER)",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                    Text(
                        text = "Locality: \"$detectedLocalityName\"${if (isCallRinging) " • Auto-Sync Paused While Ringing" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Auto 10s",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = autoLocationSyncEnabled,
                    onCheckedChange = onSetAutoLocationSync
                )
            }
        }

        // Visual Two-Zone Cluster Display Preview (Top Location Line 0x5B 0x5C + Lower Cyan Banner 0x5B 0x43 / 0x62)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, NeonMint.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text("TOP LOCATION PIN (0x5B 0x5C)", style = MaterialTheme.typography.labelSmall, color = NeonMint)
                Text(
                    text = "📍 $topLocationPinText",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.testTag("cluster_top_location_pin_text")
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElectricCyan.copy(alpha = 0.14f))
                    .border(1.dp, ElectricCyan, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text("LOWER CYAN BANNER (0x43 / 0x62)", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                Text(
                    text = lowerCyanBannerText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.testTag("cluster_lower_cyan_banner_text")
                )
            }
        }

        OutlinedTextField(
            value = customLocationOverride,
            onValueChange = { onCustomLocationOverrideChange(it.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)) },
            label = {
                Text("Custom 17-Char Cluster Location Override (Bytes 2..18, Byte 19=0xFF)")
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cockpit_location_override_input")
        )

        Button(
            onClick = onDumpLocationNowClick,
            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cmd_dump_location_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Dump Current Location to Cluster",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            val targetName = customLocationOverride.ifBlank { detectedLocalityName }.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
            Text(
                text = "Dump Current Location to Cluster (\"$targetName\" + 0xFF)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnByTurnNavigationInjectorCard(
    navigationState: NavigationManeuverState,
    googleMapsListenerState: GoogleMapsListenerState = GoogleMapsListenerState(),
    onSendManeuver: (TurnTypeConstant, Int, Byte, String, Int) -> Unit,
    onToggleRouteSimulation: () -> Unit,
    onClearNavigation: () -> Unit = {},
    onSetGoogleMapsAutoForward: (Boolean) -> Unit = {},
    onSimulateGoogleMapsNotificationClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val isNotifAccessGranted = remember(googleMapsListenerState.isServiceConnected, googleMapsListenerState.dispatchedUpdateCount) {
        GoogleMapsNotificationListenerService.isNotificationAccessGranted(context)
    }
    var selectedTurn by remember(navigationState.selectedTurn) { mutableStateOf(navigationState.selectedTurn) }
    var distanceInput by remember(navigationState.distanceMeters) { mutableStateOf(navigationState.distanceMeters.toString()) }
    var distanceSliderVal by remember(navigationState.distanceMeters) {
        mutableFloatStateOf(navigationState.distanceMeters.coerceIn(0, 5000).toFloat())
    }
    var etaInput by remember(navigationState.etaMinutes) { mutableStateOf(navigationState.etaMinutes.toString()) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val quickManeuvers = remember {
        listOf(
            TurnTypeConstant.TURN_LEFT,
            TurnTypeConstant.TURN_RIGHT,
            TurnTypeConstant.CONTINUE_STRAIGHT,
            TurnTypeConstant.ROUNDABOUT,
            TurnTypeConstant.U_TURN,
            TurnTypeConstant.TURN_SHARPLY_RIGHT,
            TurnTypeConstant.TURN_SLIGHTLY_LEFT,
            TurnTypeConstant.DESTINATION_REACHED
        )
    }

    val currentDistMeters = distanceInput.toIntOrNull() ?: distanceSliderVal.roundToInt()
    val isAutoKmScaled = currentDistMeters >= 1000
    val formattedDistanceLabel = if (isAutoKmScaled) {
        String.format(Locale.US, "%.1f km (%d m in B2..3)", currentDistMeters / 1000.0, currentDistMeters)
    } else {
        String.format(Locale.US, "%d meters (B2..3)", currentDistMeters)
    }
    val currentEtaMins = (etaInput.toIntOrNull() ?: 12).coerceIn(0, 1439)
    val etaHrsDisplay = currentEtaMins / 60
    val etaMinsDisplay = currentEtaMins % 60

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(
                1.dp,
                if (navigationState.isRouteSimulationRunning || googleMapsListenerState.isActiveNavigationOngoing) NeonMint else SlateBorder,
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
            .testTag("navigation_injector_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = "Turn-by-Turn Navigation Injector",
                    tint = NeonMint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "TURN-BY-TURN NAVIGATION (0x5B 0x4F → 400ms → 0x5A/0x5B 0x4E)",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonMint
                    )
                    Text(
                        text = "B2..3 Dist (${currentDistMeters}m) • B4..5 ETA (${currentEtaMins}m = ${etaHrsDisplay}h ${etaMinsDisplay}m) • B9 Picto (0x${String.format("%02X", selectedTurn.code)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            OutlinedButton(
                onClick = onToggleRouteSimulation,
                modifier = Modifier.testTag("toggle_nav_route_sim_button")
            ) {
                Text(
                    text = if (navigationState.isRouteSimulationRunning) {
                        "Stop 5-Step Sim (${navigationState.currentRouteStepIndex}/${navigationState.totalRouteSteps})"
                    } else {
                        "Simulate 5-Step Route"
                    },
                    color = if (navigationState.isRouteSimulationRunning) EnergeticCoral else NeonMint,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Live Google Maps NotificationListenerService Bridge Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateCardElevated)
                .border(
                    1.dp,
                    if (googleMapsListenerState.isActiveNavigationOngoing) NeonMint else ElectricCyan.copy(alpha = 0.45f),
                    RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
                .testTag("google_maps_listener_panel"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GOOGLE MAPS AUTO-SYNC (com.google.android.apps.maps)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    Text(
                        text = googleMapsListenerState.lastStatusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (googleMapsListenerState.isActiveNavigationOngoing) NeonMint else TextSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Auto Relay",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = googleMapsListenerState.isAutoForwardEnabled,
                        onCheckedChange = onSetGoogleMapsAutoForward,
                        modifier = Modifier.testTag("gmaps_auto_forward_switch")
                    )
                }
            }

            val parsedNav = googleMapsListenerState.lastParsedNavigation
            if (parsedNav != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1.2f)) {
                        Text("1. DIRECTION", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = "${parsedNav.direction.symbol} ${parsedNav.direction.title} (${parsedNav.direction.hexLabel})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = NeonMint
                        )
                    }
                    Column(modifier = Modifier.weight(0.8f)) {
                        Text("2. DISTANCE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = "${parsedNav.distanceMeters} m",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                    }
                    Column(modifier = Modifier.weight(0.8f)) {
                        Text("3. ETA DURATION", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = "${parsedNav.etaHours}h ${parsedNav.etaMinutes}m",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = AmberWarning
                        )
                    }
                }
            }

            if (!isNotifAccessGranted) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AmberWarning.copy(alpha = 0.15f))
                        .border(1.dp, AmberWarning, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⚠️ RESTRICTED SETTING DETECTED (Android 13+)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AmberWarning
                    )
                    Text(
                        text = "If \"Allow notification access\" is greyed out with \"Controlled by restricted setting\", follow these 3 quick steps:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "1. Tap \"1. Open App Info\" below.\n2. Tap the ⋮ (Three Dots) menu in the top-right corner.\n3. Tap \"Allow restricted settings\" and confirm with fingerprint/PIN.\n4. Return here, tap \"2. Open Notification Access\", and flip the toggle ON!",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = SlateSurface),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_app_info_restricted_button")
                        ) {
                            Text("1. Open App Info (⋮ to Allow)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("grant_notification_access_button")
                        ) {
                            Text("2. Open Notification Access", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Background Keep-Alive & Battery Optimization Status Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SlateSurface.copy(alpha = 0.6f))
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BACKGROUND PROCESS: FOREGROUND SERVICE ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = NeonMint
                    )
                    Text(
                        text = "App stays connected when on home screen or in Google Maps",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            runCatching {
                                val altIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(altIntent)
                            }
                        }
                    },
                    modifier = Modifier.testTag("battery_optimization_button")
                ) {
                    Text("Disable Battery Limits", color = ElectricCyan, style = MaterialTheme.typography.labelSmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSimulateGoogleMapsNotificationClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("simulate_gmaps_notification_button")
                ) {
                    Text(
                        text = "Test Google Maps Notification Parser (Simulate Step)",
                        color = ElectricCyan,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Full 66-Maneuver Dropdown Selector + Quick Maneuver Chips
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { dropdownExpanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nav_maneuver_dropdown_button")
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Maneuver Icon: %s %s (Byte[9] = 0x%02X) — Tap to Choose All %d Turns",
                        selectedTurn.symbol,
                        selectedTurn.title,
                        selectedTurn.code,
                        TurnTypeConstant.entries.size
                    ),
                    color = ElectricCyan,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                TurnTypeConstant.entries.forEach { turnItem ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                String.format(
                                    Locale.US,
                                    "%s %s (0x%02X)",
                                    turnItem.symbol,
                                    turnItem.title,
                                    turnItem.code
                                )
                            )
                        },
                        onClick = {
                            selectedTurn = turnItem
                            dropdownExpanded = false
                            val dist = distanceInput.toIntOrNull() ?: 250
                            onSendManeuver(turnItem, dist, IqubeBleSpec.NAV_UNIT_METERS, "", currentEtaMins)
                        }
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickManeuvers.forEach { maneuver ->
                val isSelected = selectedTurn == maneuver
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) NeonMint.copy(alpha = 0.22f) else SlateCardElevated)
                        .border(1.dp, if (isSelected) NeonMint else SlateBorder, RoundedCornerShape(8.dp))
                        .clickable {
                            selectedTurn = maneuver
                            val dist = distanceInput.toIntOrNull() ?: 250
                            onSendManeuver(maneuver, dist, IqubeBleSpec.NAV_UNIT_METERS, "", currentEtaMins)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("nav_maneuver_chip_${maneuver.code}")
                ) {
                    Text(
                        text = String.format(Locale.US, "%s %s (0x%02X)", maneuver.symbol, maneuver.title, maneuver.code),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) NeonMint else TextPrimary
                    )
                }
            }
        }

        // Distance Slider (0 to 5,000 meters) with dynamic meters/km display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SlateCardElevated)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DISTANCE SLIDER (0–5,000m): $formattedDistanceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isAutoKmScaled) AmberWarning else NeonMint
                )
                Text(
                    text = "ETA: ${etaHrsDisplay}h ${etaMinsDisplay}m (B4..5=${currentEtaMins}m)",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
            Slider(
                value = distanceSliderVal,
                onValueChange = { newVal ->
                    distanceSliderVal = newVal
                    distanceInput = ((newVal / 10f).roundToInt() * 10).toString()
                },
                valueRange = 0f..5000f,
                colors = SliderDefaults.colors(
                    thumbColor = NeonMint,
                    activeTrackColor = ElectricCyan
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nav_distance_slider")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = distanceInput,
                onValueChange = {
                    distanceInput = it.filter { ch -> ch.isDigit() }.take(5)
                    distanceInput.toIntOrNull()?.let { d ->
                        distanceSliderVal = d.coerceIn(0, 5000).toFloat()
                    }
                },
                label = { Text("Distance to Turn (m)") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("nav_distance_input")
            )
            OutlinedTextField(
                value = etaInput,
                onValueChange = { etaInput = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("Total ETA (mins)") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("nav_eta_input")
            )
        }

        if (!navigationState.lastPacket1Hex.isNullOrBlank() && !navigationState.lastPacket2Hex.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .padding(10.dp)
            ) {
                Text(
                    text = "Pkt 1 (0x5B 0x4F): ${navigationState.lastPacket1Hex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = "Pkt 2 (0x5A/0x5B 0x4E +400ms): ${navigationState.lastPacket2Hex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val dist = distanceInput.toIntOrNull() ?: 250
                    onSendManeuver(selectedTurn, dist, IqubeBleSpec.NAV_UNIT_METERS, "", currentEtaMins)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonMint, contentColor = SlateSurface),
                modifier = Modifier
                    .weight(1.4f)
                    .testTag("send_nav_sequence_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Maneuver to Cluster", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "Send Maneuver: %s (0x%02X) • %s • ETA %dm",
                        selectedTurn.title,
                        selectedTurn.code,
                        if (currentDistMeters >= 1000) String.format(Locale.US, "%.1fkm", currentDistMeters / 1000.0) else "${currentDistMeters}m",
                        currentEtaMins
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = onClearNavigation,
                modifier = Modifier
                    .weight(0.6f)
                    .testTag("clear_nav_button")
            ) {
                Text(
                    text = "End Nav",
                    color = EnergeticCoral,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncomingCallClusterRelayCard(
    incomingCallState: IncomingCallRelayState,
    detectedLocalityName: String,
    onTriggerCall: (String) -> Unit,
    onTriggerOutgoingCall: (String) -> Unit,
    onTriggerMissedCall: (String) -> Unit,
    onEndCall: () -> Unit
) {
    var callerInput by remember { mutableStateOf(incomingCallState.fullCallerName) }
    val extractedFirst = remember(callerInput) {
        callerInput.trim().split(Regex("\\s+")).firstOrNull()?.replace(Regex("[^\\x20-\\x7E]"), "")?.ifBlank { "Caller" } ?: "Caller"
    }
    val previewClusterText = remember(extractedFirst) {
        extractedFirst.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(
                1.dp,
                if (incomingCallState.isRinging) AmberWarning else SlateBorder,
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
            .testTag("incoming_call_relay_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call & Alert Banner Relay",
                    tint = AmberWarning,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "CALL RELAY TESTER (INCOMING 0x00 • OUTGOING 0x04 • MISSED 0x03)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberWarning
                    )
                    Text(
                        text = "Active Banner: \"${incomingCallState.clusterFormattedText}\" (State 0x${String.format("%02X", incomingCallState.callStateByte)}) • Restores \"$detectedLocalityName\" on End",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        OutlinedTextField(
            value = callerInput,
            onValueChange = { callerInput = it },
            label = { Text("Caller Name (Incoming: '$previewClusterText' • Outgoing: 'Call: $previewClusterText' • Missed: 'Miss: $previewClusterText')") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("caller_name_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onTriggerCall(callerInput) },
                colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = SlateSurface),
                modifier = Modifier
                    .weight(1f)
                    .testTag("trigger_call_alert_button")
            ) {
                Icon(Icons.Default.Call, contentDescription = "Incoming Call", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Incoming (\"$previewClusterText\")",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = { onTriggerOutgoingCall(callerInput) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
                modifier = Modifier
                    .weight(1f)
                    .testTag("trigger_outgoing_call_button")
            ) {
                Icon(Icons.Default.Call, contentDescription = "Outgoing Call", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Outgoing (\"Call: $previewClusterText\")",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onTriggerMissedCall(callerInput) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("trigger_missed_call_button")
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "Missed Call", tint = EnergeticCoral, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Test Missed Call (\"Miss: $previewClusterText\")",
                    color = EnergeticCoral,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onEndCall,
                colors = ButtonDefaults.buttonColors(containerColor = EnergeticCoral, contentColor = SlateSurface),
                modifier = Modifier
                    .weight(1f)
                    .testTag("end_call_alert_button")
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "End Call", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "End / Clear Call (0x02)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CommandShortcutButton(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(SlateCardElevated)
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClusterMusicAndRiderGreetingCard(
    riderGreetingName: String,
    musicTrackTitle: String,
    mediaPlayerState: MediaPlayerState,
    phoneStatusState: PhoneStatusState,
    onSendMusicTrackClick: (String, String, Boolean) -> Unit,
    onSimulateHandlebarKeyClick: (MusicCommand) -> Unit,
    onSyncRiderGreetingClick: (String) -> Unit,
    onSendPhoneStatusRelayClick: () -> Unit,
    onSetPhoneBatteryOverride: (Int) -> Unit,
    onSyncClockNowClick: () -> Unit
) {
    var riderInput by remember(riderGreetingName) { mutableStateOf(riderGreetingName) }
    var songInput by remember(mediaPlayerState.trackTitle, musicTrackTitle) {
        mutableStateOf(mediaPlayerState.trackTitle.ifBlank { musicTrackTitle })
    }
    var batterySliderVal by remember(phoneStatusState.phoneBatteryPct) {
        mutableFloatStateOf(phoneStatusState.phoneBatteryPct.toFloat())
    }
    val livePct = batterySliderVal.roundToInt()
    val liveBars = IqubeBleSpec.computePhoneBatteryBars(livePct)

    val lastClockStr = remember(phoneStatusState.lastClockSyncMs) {
        phoneStatusState.lastClockSyncMs?.let {
            SimpleDateFormat("HH:mm:ss • dd/MM/yy", Locale.US).format(Date(it))
        } ?: "Ready to Sync"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
            .padding(16.dp)
            .testTag("music_rider_greeting_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section A: Simple Song Name in Cluster Banner (0x5B 0x5C + 0x5B 0x62 — NO INCOMING TAG)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "SIMPLE SONG NAME IN CLUSTER BANNER (0x5B 0x5C • 0x5B 0x62 — NO INCOMING TAG)",
                style = MaterialTheme.typography.labelMedium,
                color = NeonMint
            )
            Text(
                text = "Displays simple song title text directly in the cluster banner without triggering Incoming Call (0x43)",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        OutlinedTextField(
            value = songInput,
            onValueChange = { songInput = it.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES) },
            label = { Text("Simple Song Name in Banner (max 17 chars, e.g. \"Midnight City\")") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("music_track_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onSendMusicTrackClick(songInput, "", true) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonMint, contentColor = SlateSurface),
                modifier = Modifier
                    .weight(1.2f)
                    .testTag("send_music_track_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Show Song Name in Banner", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Show Song in Banner (\"${songInput.take(17)}\")",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = { onSendMusicTrackClick(songInput, "", false) },
                modifier = Modifier
                    .weight(0.8f)
                    .testTag("clear_music_banner_button")
            ) {
                Text(
                    text = "Restore Location",
                    color = ElectricCyan,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Section B: Phone Battery & Status Tray Sync (0x5B 0x4A Byte[2]=(Sig<<4)|Batt & Byte[6..9]=12h AM/PM Clock)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateCardElevated)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "PHONE BATTERY BARS (0x5B 0x4A Byte[2]=(Sig<<4)|Batt) & 12H AM/PM CLOCK SYNC",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan
            )
            Text(
                text = "Phone Battery: $livePct% → $liveBars/4 Bars (Byte[10]=0 MissedCalls)  |  Clock: Synced ($lastClockStr)",
                style = MaterialTheme.typography.labelSmall,
                color = NeonMint
            )

            // Quick Battery Bar Test Chips (10% = 0/4, 20% = 1/4, 45% = 2/4, 70% = 3/4, 90% = 4/4)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(10 to "10% (0/4)", 20 to "20% (1/4)", 45 to "45% (2/4)", 70 to "70% (3/4)", 90 to "90% (4/4)").forEach { (pct, label) ->
                    val isSelected = livePct == pct
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) ElectricCyan.copy(alpha = 0.22f) else SlateSurface)
                            .border(1.dp, if (isSelected) ElectricCyan else SlateBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                batterySliderVal = pct.toFloat()
                                onSetPhoneBatteryOverride(pct)
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                            .testTag("battery_preset_chip_$pct")
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) ElectricCyan else TextPrimary
                        )
                    }
                }
            }

            Slider(
                value = batterySliderVal,
                onValueChange = { v ->
                    batterySliderVal = v
                },
                onValueChangeFinished = {
                    onSetPhoneBatteryOverride(batterySliderVal.roundToInt())
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = ElectricCyan,
                    activeTrackColor = NeonMint
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("phone_battery_override_slider")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSendPhoneStatusRelayClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sync_phone_status_button")
                ) {
                    Text(
                        text = "Sync Phone ($livePct% • $liveBars/4 Bars)",
                        color = NeonMint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = onSyncClockNowClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sync_clock_now_button")
                ) {
                    Icon(Icons.Default.AccessTime, contentDescription = "Sync Clock", tint = ElectricCyan, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Sync Clock Now",
                        color = ElectricCyan,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Section C: Editable Rider Greeting Name (0x5B 0x52)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = riderInput,
                onValueChange = { riderInput = it.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES) },
                label = { Text("Rider Greeting Name (0x5B 0x52 — Welcome, <Name>)") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("rider_greeting_input")
            )
            Button(
                onClick = { onSyncRiderGreetingClick(riderInput) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
                modifier = Modifier.testTag("sync_rider_greeting_button")
            ) {
                Text("Save & Sync", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}
