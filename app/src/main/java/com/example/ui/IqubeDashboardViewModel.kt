package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.ble.IqubeBleManager
import com.example.model.ActiveTripSession
import com.example.model.BleConnectionPhase
import com.example.model.ByteOffsetMapping
import com.example.model.ChargingMonitorState
import com.example.model.GoogleMapsListenerState
import com.example.model.GpsBleFusionSample
import com.example.model.IncomingCallRelayState
import com.example.model.IqubeBleSpec
import com.example.model.LastParkedLocation
import com.example.model.LastSeenScooterState
import com.example.model.MediaPlayerState
import com.example.model.MusicCommand
import com.example.model.NavigationManeuverState
import com.example.model.PhoneStatusState
import com.example.model.QueuedWriteCommand
import com.example.model.RideMode
import com.example.model.SavedTripRecord
import com.example.model.TelemetryState
import com.example.model.TripSubcommandConfig
import com.example.model.TrueRangeCalculation
import com.example.model.TurnTypeConstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashboardDestination(val route: String, val title: String) {
    COCKPIT("cockpit", "Cockpit"),
    SAVED_TRIPS("saved_trips", "Saved Trips"),
    COMMAND_STUDIO("command_studio", "Cluster Sync"),
    GPS_LOGGER("gps_logger", "GPS & Park"),
    HEX_INSPECTOR("hex_inspector", "Hex Inspector")
}

class IqubeDashboardViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = IqubeBleManager.getInstance(application.applicationContext)

    private val _currentDestination = MutableStateFlow(DashboardDestination.COCKPIT)
    val currentDestination: StateFlow<DashboardDestination> = _currentDestination.asStateFlow()

    val connectionPhase: StateFlow<BleConnectionPhase> = bleManager.connectionPhase
    val connectedDeviceName: StateFlow<String> = bleManager.connectedDeviceName
    val connectedDeviceMac: StateFlow<String> = bleManager.connectedDeviceMac
    val rssiDbm: StateFlow<Int?> = bleManager.rssiDbm
    val negotiatedMtu: StateFlow<Int?> = bleManager.negotiatedMtu
    val discoveredServiceUuid: StateFlow<String> = bleManager.discoveredServiceUuid
    val isLinkDegraded: StateFlow<Boolean> = bleManager.isLinkDegraded
    val linkWarningMessage: StateFlow<String?> = bleManager.linkWarningMessage
    val autoReconnectEnabled: StateFlow<Boolean> = bleManager.autoReconnectEnabled
    val statusNotificationsEnabled: StateFlow<Boolean> = bleManager.statusNotificationsEnabled
    val heartbeatEnabled: StateFlow<Boolean> = bleManager.heartbeatEnabled
    val autoLocationSyncEnabled: StateFlow<Boolean> = bleManager.autoLocationSyncEnabled
    val fragmentedSliceTestEnabled: StateFlow<Boolean> = bleManager.fragmentedSliceTestEnabled

    val telemetryState: StateFlow<TelemetryState> = bleManager.packetParser.telemetryState
    val byteOffsetMapping: StateFlow<ByteOffsetMapping> = bleManager.packetParser.byteOffsetMapping
    val tripSubcommandConfig: StateFlow<TripSubcommandConfig> = bleManager.tripSubcommandConfig

    // True Range, Charging Monitor, Last Seen State, Navigation, Media, Phone Status & Trip History StateFlows
    val trueRangeState: StateFlow<TrueRangeCalculation> = bleManager.trueRangeState
    val chargingMonitorState: StateFlow<ChargingMonitorState> = bleManager.chargingMonitorState
    val lastSeenState: StateFlow<LastSeenScooterState?> = bleManager.lastSeenState
    val incomingCallState: StateFlow<IncomingCallRelayState> = bleManager.incomingCallState
    val navigationState: StateFlow<NavigationManeuverState> = bleManager.navigationState
    val googleMapsListenerState: StateFlow<GoogleMapsListenerState> = bleManager.googleMapsListenerState
    val riderGreetingName: StateFlow<String> = bleManager.riderGreetingName
    val musicTrackTitle: StateFlow<String> = bleManager.musicTrackTitle
    val mediaPlayerState: StateFlow<MediaPlayerState> = bleManager.mediaPlayerState
    val phoneStatusState: StateFlow<PhoneStatusState> = bleManager.phoneStatusState
    val selectedRangeMode: StateFlow<RideMode> = bleManager.selectedRangeMode
    val savedTrips: StateFlow<List<SavedTripRecord>> = bleManager.tripRepository.savedTrips
    val activeTripSession: StateFlow<ActiveTripSession?> = bleManager.activeTripSession

    // Cluster Location Display (`0x5B 0x5C` + 17-byte ASCII locality + `0xFF`)
    val detectedLocalityName: StateFlow<String> = bleManager.detectedLocalityName
    val customLocationOverride: StateFlow<String> = bleManager.customLocationOverride
    val lastSyncedClusterLocation: StateFlow<String?> = bleManager.lastSyncedClusterLocation

    val commandHistory: StateFlow<List<QueuedWriteCommand>> = bleManager.commandDispatcher.commandHistory
    val queueDepth: StateFlow<Int> = bleManager.commandDispatcher.queueDepth

    val currentGpsSpeedKmh: StateFlow<Double?> = bleManager.currentGpsSpeedKmh
    val lastParkedLocation: StateFlow<LastParkedLocation?> = bleManager.lastParkedLocation
    val gpsFusionSamples: StateFlow<List<GpsBleFusionSample>> = bleManager.gpsFusionSamples

    fun navigateTo(destination: DashboardDestination) {
        _currentDestination.value = destination
    }

    fun startBleScanAndConnect() {
        bleManager.startBleScanAndConnect()
    }

    fun startRidingLogReplay() {
        bleManager.startBtsnoozHciLogReplay()
    }

    fun startChargingLogReplay() {
        bleManager.startChargingCaptureReplay()
    }

    fun disconnectBle() {
        bleManager.disconnectBle(userInitiated = true)
    }

    fun setActiveTripTab(tabIndex: Int) {
        bleManager.packetParser.setActiveTripTab(tabIndex)
    }

    // Verified 3 Trip Control Commands (5A F1 00 <subCmd> ... FF)
    fun sendSwitchToTripACommand() {
        bleManager.sendSwitchToTripACommand()
    }

    fun sendSwitchToTripBCommand() {
        bleManager.sendSwitchToTripBCommand()
    }

    fun sendResetActiveTripCommand() {
        bleManager.sendResetActiveTripCommand()
    }

    fun updateTripSubcommandConfig(config: TripSubcommandConfig) {
        bleManager.updateTripSubcommandConfig(config)
    }

    // Cluster Location Display (`0x5B 0x5C` + 17-byte ASCII Location Name + `0xFF`)
    fun dumpLocationToClusterNow(customText: String? = null) {
        bleManager.dumpLocationToClusterNow(customText)
    }

    fun setCustomLocationOverride(customText: String) {
        bleManager.setCustomLocationOverride(customText)
    }

    fun setAutoLocationSyncEnabled(enabled: Boolean) {
        bleManager.setAutoLocationSyncEnabled(enabled)
    }

    // Dedicated Caller ID Banner (`0x5B 0x43` + 500ms repeat timer) & Outgoing/Missed Call Relays
    fun triggerIncomingCallAlert(fullCallerName: String) {
        bleManager.triggerIncomingCallAlert(fullCallerName)
    }

    fun triggerOutgoingCallAlert(fullCallerName: String) {
        bleManager.triggerOutgoingCallAlert(fullCallerName)
    }

    fun triggerMissedCallAlert(fullCallerName: String) {
        bleManager.triggerMissedCallAlert(fullCallerName)
    }

    fun endIncomingCallAlert() {
        bleManager.endIncomingCallAlert()
    }

    // Turn-by-Turn Navigation Injector (`0x5B 0x4F` -> 400ms -> `0x5A/0x5B 0x4E`)
    fun sendNavigationManeuver(
        turn: TurnTypeConstant,
        distanceMeters: Int,
        unitByte: Byte = IqubeBleSpec.NAV_UNIT_METERS,
        streetName: String = "",
        etaMinutes: Int = navigationState.value.etaMinutes
    ) {
        bleManager.sendNavigationData(turn, distanceMeters, unitByte, streetName, etaMinutes)
    }

    fun toggleNavigationRouteSimulation() {
        bleManager.toggleNavigationRouteSimulation()
    }

    fun clearNavigationOnCluster() {
        bleManager.clearNavigationOnCluster()
    }

    fun setStatusNotificationsEnabled(enabled: Boolean) {
        bleManager.setStatusNotificationsEnabled(enabled)
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        bleManager.setAutoReconnectEnabled(enabled)
    }

    fun setGoogleMapsAutoForwardEnabled(enabled: Boolean) {
        bleManager.setGoogleMapsAutoForwardEnabled(enabled)
    }

    fun simulateGoogleMapsSampleNotification() {
        bleManager.simulateGoogleMapsSampleNotification()
    }

    // Music Track Banner (`0x5B 0x62`), Handlebar Key (`0x5A 0x6B`), Rider Greeting (`0x5B 0x52`), & Phone Status (`0x5B 0x52 0x55`)
    fun sendMusicTrackToCluster(songTitle: String, artistName: String = mediaPlayerState.value.artistName, isPlaying: Boolean = true) {
        bleManager.sendMusicTrackToCluster(songTitle, artistName, isPlaying)
    }

    fun simulateHandlebarMediaKey(command: MusicCommand) {
        bleManager.simulateHandlebarMediaKey(command)
    }

    fun syncRiderGreetingToCluster(riderName: String) {
        bleManager.syncRiderGreetingToCluster(riderName)
    }

    fun sendPhoneStatusRelayNow() {
        bleManager.sendPhoneStatusRelayNow()
    }

    fun setPhoneBatteryOverride(batteryPct: Int) {
        bleManager.setPhoneBatteryOverride(batteryPct, overrideEnabled = true)
    }

    fun syncClockToClusterNow() {
        bleManager.syncClockToClusterNow(isBackgroundHeartbeat = false)
    }

    // Charging Predictor Actions
    fun simulateLiveOnePercentChargingStep() {
        bleManager.simulateLiveOnePercentChargingStep()
    }

    fun setSelectedRangeMode(mode: RideMode) {
        bleManager.setSelectedRangeMode(mode)
    }

    // Saved Trips History & Export Actions
    fun logSampleVerifiedTrip() {
        bleManager.tripRepository.logSampleVerifiedTrip()
    }

    fun deleteSavedTrip(tripId: String) {
        bleManager.tripRepository.deleteTrip(tripId)
    }

    fun clearAllSavedTrips() {
        bleManager.tripRepository.clearAllTrips()
    }

    fun exportSavedTripsCsv(): String {
        return bleManager.tripRepository.exportTripsToCsv()
    }

    fun exportSavedTripsJson(): String {
        return bleManager.tripRepository.exportTripsToJson()
    }

    fun sendCustomHexFrame(label: String, hexString: String): Result<Unit> {
        val cleanHex = hexString.trim().replace("0x", "", ignoreCase = true)
            .split(Regex("[\\s,]+"))
            .filter { it.isNotEmpty() }

        if (cleanHex.size != 20) {
            return Result.failure(
                IllegalArgumentException("Expected 20 hex bytes separated by spaces (got ${cleanHex.size}).")
            )
        }
        val bytes = ByteArray(20)
        for (i in 0 until 20) {
            val parsed = cleanHex[i].toIntOrNull(16)
                ?: return Result.failure(IllegalArgumentException("Invalid hex byte '${cleanHex[i]}' at index $i."))
            if (parsed !in 0..255) {
                return Result.failure(IllegalArgumentException("Byte out of range at index $i."))
            }
            bytes[i] = parsed.toByte()
        }
        return bleManager.sendCustomVerifiedHexFrame(label, bytes)
    }

    fun updateByteOffsetMapping(newMapping: ByteOffsetMapping) {
        bleManager.packetParser.updateByteOffsetMapping(newMapping)
    }

    fun setAutoReconnect(enabled: Boolean) {
        bleManager.setAutoReconnect(enabled)
    }

    fun setHeartbeatEnabled(enabled: Boolean) {
        bleManager.setHeartbeatEnabled(enabled)
    }

    fun setFragmentedSliceTestEnabled(enabled: Boolean) {
        bleManager.setFragmentedSliceTestEnabled(enabled)
    }

    fun toggleReplayChargingState() {
        bleManager.toggleReplayChargingState()
    }

    fun startGpsTelemetryFusion() {
        bleManager.startGpsTelemetryFusion()
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT disconnect BLE here!
        // IqubeBleForegroundService keeps the Bluetooth connection and telemetry alive in the background
        // when the user minimizes the app, goes to Home screen, or switches to Google Maps.
    }
}
