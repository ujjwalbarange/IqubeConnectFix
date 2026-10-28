package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.DashboardDestination
import com.example.ui.IqubeDashboardViewModel
import com.example.ui.components.TopConnectionHeader
import com.example.ui.screens.CockpitDashboardScreen
import com.example.ui.screens.CommandStudioScreen
import com.example.ui.screens.GpsRideLoggerScreen
import com.example.ui.screens.HexInspectorScreen
import com.example.ui.screens.SavedTripsHistoryScreen
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                IqubeCompanionApp()
            }
        }
    }
}

@Composable
fun IqubeCompanionApp(
    viewModel: IqubeDashboardViewModel = viewModel()
) {
    val currentDestination by viewModel.currentDestination.collectAsStateWithLifecycle()
    val connectionPhase by viewModel.connectionPhase.collectAsStateWithLifecycle()
    val deviceName by viewModel.connectedDeviceName.collectAsStateWithLifecycle()
    val deviceMac by viewModel.connectedDeviceMac.collectAsStateWithLifecycle()
    val rssiDbm by viewModel.rssiDbm.collectAsStateWithLifecycle()
    val negotiatedMtu by viewModel.negotiatedMtu.collectAsStateWithLifecycle()
    val isLinkDegraded by viewModel.isLinkDegraded.collectAsStateWithLifecycle()
    val linkWarningMessage by viewModel.linkWarningMessage.collectAsStateWithLifecycle()
    val autoReconnectEnabled by viewModel.autoReconnectEnabled.collectAsStateWithLifecycle()
    val heartbeatEnabled by viewModel.heartbeatEnabled.collectAsStateWithLifecycle()
    val autoLocationSyncEnabled by viewModel.autoLocationSyncEnabled.collectAsStateWithLifecycle()
    val fragmentedSliceTestEnabled by viewModel.fragmentedSliceTestEnabled.collectAsStateWithLifecycle()

    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
    val byteOffsetMapping by viewModel.byteOffsetMapping.collectAsStateWithLifecycle()
    val tripSubcommandConfig by viewModel.tripSubcommandConfig.collectAsStateWithLifecycle()
    val trueRange by viewModel.trueRangeState.collectAsStateWithLifecycle()
    val chargingMonitor by viewModel.chargingMonitorState.collectAsStateWithLifecycle()
    val lastSeenState by viewModel.lastSeenState.collectAsStateWithLifecycle()
    val incomingCallState by viewModel.incomingCallState.collectAsStateWithLifecycle()
    val navigationState by viewModel.navigationState.collectAsStateWithLifecycle()
    val riderGreetingName by viewModel.riderGreetingName.collectAsStateWithLifecycle()
    val musicTrackTitle by viewModel.musicTrackTitle.collectAsStateWithLifecycle()
    val mediaPlayerState by viewModel.mediaPlayerState.collectAsStateWithLifecycle()
    val phoneStatusState by viewModel.phoneStatusState.collectAsStateWithLifecycle()
    val selectedRangeMode by viewModel.selectedRangeMode.collectAsStateWithLifecycle()
    val savedTrips by viewModel.savedTrips.collectAsStateWithLifecycle()
    val activeTripSession by viewModel.activeTripSession.collectAsStateWithLifecycle()

    val detectedLocalityName by viewModel.detectedLocalityName.collectAsStateWithLifecycle()
    val customLocationOverride by viewModel.customLocationOverride.collectAsStateWithLifecycle()
    val lastSyncedClusterLocation by viewModel.lastSyncedClusterLocation.collectAsStateWithLifecycle()

    val commandHistory by viewModel.commandHistory.collectAsStateWithLifecycle()
    val queueDepth by viewModel.queueDepth.collectAsStateWithLifecycle()

    val currentGpsSpeedKmh by viewModel.currentGpsSpeedKmh.collectAsStateWithLifecycle()
    val lastParkedLocation by viewModel.lastParkedLocation.collectAsStateWithLifecycle()
    val gpsFusionSamples by viewModel.gpsFusionSamples.collectAsStateWithLifecycle()

    val blePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.startBleScanAndConnect()
    }

    val triggerBleScanWithPermissions = {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        blePermissionsLauncher.launch(requiredPermissions)
    }

    BackHandler(enabled = currentDestination != DashboardDestination.COCKPIT) {
        viewModel.navigateTo(DashboardDestination.COCKPIT)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        val isWideScreen = maxWidth >= 640.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!isWideScreen) {
                    IqubeBottomNavigationBar(
                        currentDestination = currentDestination,
                        onSelectDestination = viewModel::navigateTo
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    IqubeSideNavigationRail(
                        currentDestination = currentDestination,
                        onSelectDestination = viewModel::navigateTo
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    TopConnectionHeader(
                        connectionPhase = connectionPhase,
                        deviceName = telemetry.vehicleIdString ?: deviceName,
                        deviceMac = deviceMac,
                        rssiDbm = rssiDbm,
                        negotiatedMtu = negotiatedMtu,
                        isIgnitionUnlocked = telemetry.isIgnitionUnlocked,
                        rideMode = telemetry.rideMode,
                        isCharging = telemetry.isCharging,
                        isLinkDegraded = isLinkDegraded,
                        linkWarningMessage = linkWarningMessage,
                        riderGreetingName = riderGreetingName,
                        phoneStatusState = phoneStatusState,
                        onConnectBleClick = triggerBleScanWithPermissions,
                        onReplayRidingClick = viewModel::startRidingLogReplay,
                        onReplayChargingClick = viewModel::startChargingLogReplay,
                        onDisconnectClick = viewModel::disconnectBle
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        when (currentDestination) {
                            DashboardDestination.COCKPIT -> {
                                CockpitDashboardScreen(
                                    telemetry = telemetry,
                                    trueRange = trueRange,
                                    chargingMonitor = chargingMonitor,
                                    lastSeenState = lastSeenState,
                                    incomingCallState = incomingCallState,
                                    navigationState = navigationState,
                                    selectedRangeMode = selectedRangeMode,
                                    detectedLocalityName = detectedLocalityName,
                                    customLocationOverride = customLocationOverride,
                                    lastSyncedClusterLocation = lastSyncedClusterLocation,
                                    autoLocationSyncEnabled = autoLocationSyncEnabled,
                                    tripSubcommandConfig = tripSubcommandConfig,
                                    onSelectRangeMode = viewModel::setSelectedRangeMode,
                                    onSelectTripTab = viewModel::setActiveTripTab,
                                    onSwitchToTripAClick = viewModel::sendSwitchToTripACommand,
                                    onSwitchToTripBClick = viewModel::sendSwitchToTripBCommand,
                                    onResetActiveTripClick = viewModel::sendResetActiveTripCommand,
                                    onDumpLocationToClusterClick = { viewModel.dumpLocationToClusterNow(null) },
                                    onCustomLocationOverrideChange = viewModel::setCustomLocationOverride,
                                    onSetAutoLocationSync = viewModel::setAutoLocationSyncEnabled,
                                    onTriggerIncomingCallClick = viewModel::triggerIncomingCallAlert,
                                    onTriggerOutgoingCallClick = viewModel::triggerOutgoingCallAlert,
                                    onTriggerMissedCallClick = viewModel::triggerMissedCallAlert,
                                    onEndIncomingCallClick = { viewModel.endIncomingCallAlert() },
                                    onSendNavigationManeuver = viewModel::sendNavigationManeuver,
                                    onToggleNavigationRouteSim = viewModel::toggleNavigationRouteSimulation,
                                    onClearNavigationClick = viewModel::clearNavigationOnCluster,
                                    onSimulateChargingStepClick = viewModel::simulateLiveOnePercentChargingStep,
                                    onToggleChargingClick = viewModel::toggleReplayChargingState,
                                    riderGreetingName = riderGreetingName,
                                    musicTrackTitle = musicTrackTitle,
                                    mediaPlayerState = mediaPlayerState,
                                    phoneStatusState = phoneStatusState,
                                    onSendMusicTrackClick = viewModel::sendMusicTrackToCluster,
                                    onSimulateHandlebarKeyClick = viewModel::simulateHandlebarMediaKey,
                                    onSyncRiderGreetingClick = viewModel::syncRiderGreetingToCluster,
                                    onSendPhoneStatusRelayClick = viewModel::sendPhoneStatusRelayNow,
                                    onSetPhoneBatteryOverride = viewModel::setPhoneBatteryOverride,
                                    onSyncClockNowClick = viewModel::syncClockToClusterNow
                                )
                            }
                            DashboardDestination.SAVED_TRIPS -> {
                                SavedTripsHistoryScreen(
                                    savedTrips = savedTrips,
                                    activeTripSession = activeTripSession,
                                    telemetry = telemetry,
                                    trueRange = trueRange,
                                    onLogSampleTripClick = viewModel::logSampleVerifiedTrip,
                                    onDeleteTripClick = viewModel::deleteSavedTrip,
                                    onClearAllTripsClick = viewModel::clearAllSavedTrips,
                                    onExportCsv = viewModel::exportSavedTripsCsv,
                                    onExportJson = viewModel::exportSavedTripsJson
                                )
                            }
                            DashboardDestination.COMMAND_STUDIO -> {
                                CommandStudioScreen(
                                    commandHistory = commandHistory,
                                    queueDepth = queueDepth,
                                    heartbeatEnabled = heartbeatEnabled,
                                    autoLocationSyncEnabled = autoLocationSyncEnabled,
                                    detectedLocalityName = detectedLocalityName,
                                    customLocationOverride = customLocationOverride,
                                    lastSyncedClusterLocation = lastSyncedClusterLocation,
                                    tripSubcommandConfig = tripSubcommandConfig,
                                    onSetHeartbeatEnabled = viewModel::setHeartbeatEnabled,
                                    onSetAutoLocationSync = viewModel::setAutoLocationSyncEnabled,
                                    onCustomLocationOverrideChange = viewModel::setCustomLocationOverride,
                                    onDumpLocationToCluster = viewModel::dumpLocationToClusterNow,
                                    onUpdateTripSubcommandConfig = viewModel::updateTripSubcommandConfig,
                                    onSwitchToTripAClick = viewModel::sendSwitchToTripACommand,
                                    onSwitchToTripBClick = viewModel::sendSwitchToTripBCommand,
                                    onResetActiveTripClick = viewModel::sendResetActiveTripCommand,
                                    onSendCustomHex = viewModel::sendCustomHexFrame
                                )
                            }
                            DashboardDestination.GPS_LOGGER -> {
                                GpsRideLoggerScreen(
                                    telemetry = telemetry,
                                    currentGpsSpeedKmh = currentGpsSpeedKmh,
                                    detectedLocalityName = detectedLocalityName,
                                    lastParkedLocation = lastParkedLocation,
                                    gpsFusionSamples = gpsFusionSamples,
                                    autoReconnectEnabled = autoReconnectEnabled,
                                    onSetAutoReconnect = viewModel::setAutoReconnect,
                                    onStartGpsTracking = viewModel::startGpsTelemetryFusion
                                )
                            }
                            DashboardDestination.HEX_INSPECTOR -> {
                                HexInspectorScreen(
                                    telemetry = telemetry,
                                    byteOffsetMapping = byteOffsetMapping,
                                    fragmentedSliceTestEnabled = fragmentedSliceTestEnabled,
                                    onUpdateMapping = viewModel::updateByteOffsetMapping,
                                    onSetFragmentedSliceTest = viewModel::setFragmentedSliceTestEnabled
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IqubeBottomNavigationBar(
    currentDestination: DashboardDestination,
    onSelectDestination: (DashboardDestination) -> Unit
) {
    NavigationBar(
        containerColor = SlateSurface,
        contentColor = TextPrimary
    ) {
        DashboardDestination.entries.forEach { dest ->
            val selected = currentDestination == dest
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectDestination(dest) },
                icon = {
                    Icon(
                        imageVector = destinationIcon(dest, selected),
                        contentDescription = dest.title
                    )
                },
                label = { Text(dest.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ElectricCyan,
                    selectedTextColor = ElectricCyan,
                    indicatorColor = SlateCardElevated,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                ),
                modifier = Modifier.testTag("nav_tab_${dest.route}")
            )
        }
    }
}

@Composable
private fun IqubeSideNavigationRail(
    currentDestination: DashboardDestination,
    onSelectDestination: (DashboardDestination) -> Unit
) {
    NavigationRail(
        containerColor = SlateSurface,
        contentColor = TextPrimary,
        modifier = Modifier.fillMaxHeight()
    ) {
        DashboardDestination.entries.forEach { dest ->
            val selected = currentDestination == dest
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectDestination(dest) },
                icon = {
                    Icon(
                        imageVector = destinationIcon(dest, selected),
                        contentDescription = dest.title
                    )
                },
                label = { Text(dest.title) },
                modifier = Modifier.testTag("rail_tab_${dest.route}")
            )
        }
    }
}

private fun destinationIcon(dest: DashboardDestination, selected: Boolean) = when (dest) {
    DashboardDestination.COCKPIT -> if (selected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
    DashboardDestination.SAVED_TRIPS -> if (selected) Icons.Filled.History else Icons.Outlined.History
    DashboardDestination.COMMAND_STUDIO -> if (selected) Icons.Filled.Tune else Icons.Outlined.Tune
    DashboardDestination.GPS_LOGGER -> if (selected) Icons.Filled.Explore else Icons.Outlined.Explore
    DashboardDestination.HEX_INSPECTOR -> if (selected) Icons.Filled.Code else Icons.Outlined.Code
}
