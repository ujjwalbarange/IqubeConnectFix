package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import com.example.model.ActiveTripSession
import com.example.model.BleConnectionPhase
import com.example.model.ChargingMonitorState
import com.example.model.GpsBleFusionSample
import com.example.model.IncomingCallRelayState
import com.example.model.IqubeBleSpec
import com.example.model.LastParkedLocation
import com.example.model.LastSeenScooterState
import com.example.model.MediaPlayerState
import com.example.model.MusicCommand
import com.example.model.NavigationManeuverState
import com.example.model.PhoneStatusState
import com.example.model.RideMode
import com.example.model.SavedChargingSession
import com.example.model.SavedTripRecord
import com.example.model.TripSubcommandConfig
import com.example.model.TrueRangeCalculation
import com.example.model.TurnTypeConstant
import com.example.model.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full Lifecycle BLE GATT, Unfiltered 20-Byte Telemetry Controller, Live Charging Predictor,
 * Turn-by-Turn Navigation Injector, Incoming Call Cluster Relay (`0x5B 0x43`),
 * Media Track & Handlebar Switch Controller (`0x5B 0x62` / `0x5A 0x6B`),
 * Phone Status & Clock Synchronizer (`0x5B 0x52 0x55` / `0x5B 0x4A 0x44`),
 * Offline Last-Seen Tracker & Trip Logger.
 */
class IqubeBleManager(
    private val context: Context,
    val packetParser: IqubePacketParser = IqubePacketParser(),
    val tripRepository: TrueRangeAndTripRepository = TrueRangeAndTripRepository(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var scanJob: Job? = null
    private var replayJob: Job? = null
    private var heartbeatJob: Job? = null
    private var locationSyncJob: Job? = null
    private var phoneStatusKeepaliveJob: Job? = null
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var callAutoClearJob: Job? = null
    private var callerRepeatJob: Job? = null
    private var navSequenceJob: Job? = null
    private var navigationSimJob: Job? = null
    private var reconnectAttemptCount = 0

    // Connection & Link StateFlows
    private val _connectionPhase = MutableStateFlow(BleConnectionPhase.DISCONNECTED)
    val connectionPhase: StateFlow<BleConnectionPhase> = _connectionPhase.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow(IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT)
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceMac = MutableStateFlow(IqubeBleSpec.TARGET_MAC_ADDRESS)
    val connectedDeviceMac: StateFlow<String> = _connectedDeviceMac.asStateFlow()

    private val _rssiDbm = MutableStateFlow<Int?>(null)
    val rssiDbm: StateFlow<Int?> = _rssiDbm.asStateFlow()

    private val _negotiatedMtu = MutableStateFlow<Int?>(null)
    val negotiatedMtu: StateFlow<Int?> = _negotiatedMtu.asStateFlow()

    private val _discoveredServiceUuid = MutableStateFlow(IqubeBleSpec.SERVICE_UUID_SMARTXONNECT)
    val discoveredServiceUuid: StateFlow<String> = _discoveredServiceUuid.asStateFlow()

    private val _isLinkDegraded = MutableStateFlow(false)
    val isLinkDegraded: StateFlow<Boolean> = _isLinkDegraded.asStateFlow()

    private val _linkWarningMessage = MutableStateFlow<String?>(null)
    val linkWarningMessage: StateFlow<String?> = _linkWarningMessage.asStateFlow()

    private val _autoReconnectEnabled = MutableStateFlow(true)
    val autoReconnectEnabled: StateFlow<Boolean> = _autoReconnectEnabled.asStateFlow()

    private val _heartbeatEnabled = MutableStateFlow(true)
    val heartbeatEnabled: StateFlow<Boolean> = _heartbeatEnabled.asStateFlow()

    private val _autoLocationSyncEnabled = MutableStateFlow(true)
    val autoLocationSyncEnabled: StateFlow<Boolean> = _autoLocationSyncEnabled.asStateFlow()

    private val _fragmentedSliceTestEnabled = MutableStateFlow(false)
    val fragmentedSliceTestEnabled: StateFlow<Boolean> = _fragmentedSliceTestEnabled.asStateFlow()

    // Verified Trip Subcommand Configuration (`5A F1 00 <subCmd> ... FF`)
    private val _tripSubcommandConfig = MutableStateFlow(TripSubcommandConfig())
    val tripSubcommandConfig: StateFlow<TripSubcommandConfig> = _tripSubcommandConfig.asStateFlow()

    // Cluster Location Display (`0x5B 0x5C` + 17-byte ASCII locality + `0xFF`)
    private val _detectedLocalityName = MutableStateFlow("Dhantoli")
    val detectedLocalityName: StateFlow<String> = _detectedLocalityName.asStateFlow()

    private val _customLocationOverride = MutableStateFlow("")
    val customLocationOverride: StateFlow<String> = _customLocationOverride.asStateFlow()

    private val _lastSyncedClusterLocation = MutableStateFlow<String?>(null)
    val lastSyncedClusterLocation: StateFlow<String?> = _lastSyncedClusterLocation.asStateFlow()

    // Incoming Call Relay State (`1st Name` on `0x5B 0x43 ... 0xFF` + 500ms repeat timer)
    private val _incomingCallState = MutableStateFlow(IncomingCallRelayState())
    val incomingCallState: StateFlow<IncomingCallRelayState> = _incomingCallState.asStateFlow()

    // Turn-by-Turn Navigation Injector State (`0x5B 0x49` -> 400ms -> `0x5B 0x4E`)
    private val _navigationState = MutableStateFlow(NavigationManeuverState())
    val navigationState: StateFlow<NavigationManeuverState> = _navigationState.asStateFlow()

    // Rider Greeting Name (`0x5B 0x52`) & Media Player / Track Banner (`0x5B 0x62` & `0x5A 0x6B`)
    private val _riderGreetingName = MutableStateFlow(tripRepository.loadRiderName())
    val riderGreetingName: StateFlow<String> = _riderGreetingName.asStateFlow()

    private data class PlaylistTrack(val title: String, val artist: String)
    private val playlist = listOf(
        PlaylistTrack("Midnight City", "M83"),
        PlaylistTrack("Nightcall", "Kavinsky"),
        PlaylistTrack("Blinding Lights", "The Weeknd"),
        PlaylistTrack("Starboy", "Daft Punk"),
        PlaylistTrack("Turbo Lover", "Judas Priest")
    )

    private val _mediaPlayerState = MutableStateFlow(MediaPlayerState())
    val mediaPlayerState: StateFlow<MediaPlayerState> = _mediaPlayerState.asStateFlow()

    private val _musicTrackTitle = MutableStateFlow("Midnight City")
    val musicTrackTitle: StateFlow<String> = _musicTrackTitle.asStateFlow()

    // Phone Battery & Clock Status State (`0x5B 0x52 0x55` & `0x5B 0x4A 0x44`)
    private val _phoneStatusState = MutableStateFlow(
        PhoneStatusState(phoneBatteryPct = readHardwarePhoneBatteryPercent())
    )
    val phoneStatusState: StateFlow<PhoneStatusState> = _phoneStatusState.asStateFlow()

    // Active Connect-to-Disconnect Trip Session
    private val _activeTripSession = MutableStateFlow<ActiveTripSession?>(null)
    val activeTripSession: StateFlow<ActiveTripSession?> = _activeTripSession.asStateFlow()

    // Active Charging Session & 1% Step Timing Tracking
    private var chargingSessionStartMs: Long? = null
    private val _chargingSessionStartSoc = MutableStateFlow<Int?>(null)
    private var lastChargingSocValue: Int? = null
    private var lastChargingSocStepTimestampMs: Long? = null
    private var firstChargingTickSeen: Boolean = false
    private val _liveChargingStepDurationsSec = MutableStateFlow<List<Double>>(emptyList())

    // Selected Range Display Mode on Cockpit (Eco vs Power)
    private val _selectedRangeMode = MutableStateFlow(RideMode.ECO)
    val selectedRangeMode: StateFlow<RideMode> = _selectedRangeMode.asStateFlow()

    // Replay state for Riding (`btsnooz_hci.log`) and Charging (`btsnoop_hci.log`)
    private var replayTripATenths = HciLogReplayDataset.CHARGING_CAPTURE_TRIP_A_TENTHS_KM // 0.3 km
    private var replayTripBTenths = HciLogReplayDataset.CHARGING_CAPTURE_TRIP_B_TENTHS_KM // 1.0 km
    private var replayOdoTenths = HciLogReplayDataset.CHARGING_CAPTURE_ODO_TENTHS_KM      // 359252 = 35,925.2 km
    private var replayBatterySoc = 96
    private var replayClusterRangeTenths = 1010 // 101.0 km (0x03F2 at 96% SoC)
    private var replayCharging = false

    // GPS + BLE Ride Logger & Last Parked State
    private val _currentGpsSpeedKmh = MutableStateFlow<Double?>(null)
    val currentGpsSpeedKmh: StateFlow<Double?> = _currentGpsSpeedKmh.asStateFlow()

    private val _lastKnownLocation = MutableStateFlow<Location?>(null)
    val lastKnownLocation: StateFlow<Location?> = _lastKnownLocation.asStateFlow()

    private val _lastParkedLocation = MutableStateFlow<LastParkedLocation?>(null)
    val lastParkedLocation: StateFlow<LastParkedLocation?> = _lastParkedLocation.asStateFlow()

    private val _gpsFusionSamples = MutableStateFlow<List<GpsBleFusionSample>>(emptyList())
    val gpsFusionSamples: StateFlow<List<GpsBleFusionSample>> = _gpsFusionSamples.asStateFlow()

    val lastSeenState: StateFlow<LastSeenScooterState?> = tripRepository.lastSeenState

    // Reactive True Range Calculation Engine combining live telemetry (or offline Last Seen battery %), savedTrips, and activeTripSession
    val trueRangeState: StateFlow<TrueRangeCalculation> = combine(
        packetParser.telemetryState,
        tripRepository.savedTrips,
        _activeTripSession,
        tripRepository.lastSeenState
    ) { tele, savedTrips, activeSession, lastSeen ->
        val integratedKm = tele.integratedDistanceMeters / 1000.0
        val odoDeltaKm = if (activeSession?.startOdometerKm != null && tele.odometerKm != null) {
            max(0.0, tele.odometerKm - activeSession.startOdometerKm)
        } else 0.0
        val activeDistKm = max(integratedKm, odoDeltaKm)
        // Use live battery % when connected, or fallback to Last Seen battery % when offline
        val effectiveBatteryPct = tele.batteryPercent ?: lastSeen?.batteryPct

        TrueRangeAndTripRepository.computeTrueRange(
            currentBatteryPct = effectiveBatteryPct,
            savedTrips = savedTrips,
            activeTripDistanceKm = activeDistKm,
            activeTripStartBatteryPct = activeSession?.startBatteryPct,
            activeTripMode = tele.rideMode ?: RideMode.ECO
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = TrueRangeAndTripRepository.computeTrueRange(
            currentBatteryPct = tripRepository.lastSeenState.value?.batteryPct,
            savedTrips = tripRepository.savedTrips.value
        )
    )

    // Reactive 3-Tier Charging Monitor State
    val chargingMonitorState: StateFlow<ChargingMonitorState> = combine(
        packetParser.telemetryState,
        _chargingSessionStartSoc,
        _liveChargingStepDurationsSec,
        tripRepository.savedChargingSessions
    ) { tele, startSoc, liveSteps, savedSessions ->
        TrueRangeAndTripRepository.computeChargingMonitorState(
            isCharging = tele.isCharging,
            currentBatteryPct = tele.batteryPercent,
            clusterRangeKm = tele.clusterRangeKm,
            sessionStartBatteryPct = startSoc,
            liveStepDurationsSec = liveSteps,
            savedChargingSessions = savedSessions
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = TrueRangeAndTripRepository.computeChargingMonitorState(
            isCharging = false,
            currentBatteryPct = null,
            clusterRangeKm = null,
            sessionStartBatteryPct = null,
            liveStepDurationsSec = emptyList(),
            savedChargingSessions = tripRepository.savedChargingSessions.value
        )
    )

    val commandDispatcher = IqubeCommandDispatcher(
        scope = scope,
        performGattWrite = ::performHardwareOrReplayWrite,
        onWriteAckTimeout = ::onWriteAckTimeoutDetected
    )

    // BroadcastReceiver for live smartphone battery levelchange and chargingchange events
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isPluggedCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0 && scale > 0) {
                    val pct = ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
                    val prev = _phoneStatusState.value
                    if (!prev.isBatteryOverrideEnabled) {
                        val changed = prev.phoneBatteryPct != pct || prev.isPhoneCharging != isPluggedCharging
                        _phoneStatusState.update {
                            it.copy(phoneBatteryPct = pct, isPhoneCharging = isPluggedCharging)
                        }
                        if (changed && isActivelyConnectedOrReplaying()) {
                            sendPhoneStatusRelayNow()
                        }
                    }
                }
            }
        }
    }

    init {
        // Wire Handlebar Media Control listener (`0x5A 0x6B`)
        packetParser.onMediaCommandReceived = { cmd, ts ->
            handleIncomingMediaCommand(cmd, ts)
        }

        // Register battery broadcast receiver
        try {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
        }

        scope.launch {
            packetParser.telemetryState.collect { tele ->
                // 1. Dynamically update header Vehicle Identifier string from 0x5A 0x7D ("TVSiQUBEJI573")
                if (!tele.vehicleIdString.isNullOrBlank()) {
                    _connectedDeviceName.value = tele.vehicleIdString
                }

                // 2. Track live +1% Charging Step Timing when charging is active (0x11 Byte 5 == 0x01 and 0x13 Byte 5 == 0x06)
                val now = System.currentTimeMillis()
                if (tele.isCharging && tele.batteryPercent != null) {
                    val soc = tele.batteryPercent
                    if (chargingSessionStartMs == null) {
                        chargingSessionStartMs = now
                        _chargingSessionStartSoc.value = soc
                        lastChargingSocValue = soc
                        lastChargingSocStepTimestampMs = now
                        firstChargingTickSeen = false
                    } else {
                        val prevSoc = lastChargingSocValue
                        val prevStepMs = lastChargingSocStepTimestampMs
                        if (prevSoc != null && prevStepMs != null && soc > prevSoc) {
                            val deltaSec = (now - prevStepMs) / 1000.0
                            val pctDiff = soc - prevSoc
                            val secPerPct = deltaSec / pctDiff.toDouble()
                            // Accept if full step >= 60s, or after 1st boundary tick
                            if (secPerPct >= 60.0 || firstChargingTickSeen) {
                                _liveChargingStepDurationsSec.update {
                                    (it + secPerPct.coerceIn(60.0, 240.0)).takeLast(10)
                                }
                            }
                            firstChargingTickSeen = true
                            lastChargingSocValue = soc
                            lastChargingSocStepTimestampMs = now
                        }
                    }
                }

                // 3. Persist Last Seen Scooter State on telemetry updates
                if (tele.batteryPercent != null && tele.odometerKm != null) {
                    val loc = _lastKnownLocation.value
                    tripRepository.saveLastSeenState(
                        LastSeenScooterState(
                            timestampMs = now,
                            batteryPct = tele.batteryPercent,
                            odometerKm = tele.odometerKm,
                            clusterRangeKm = tele.clusterRangeKm ?: (tele.batteryPercent * 1.0),
                            tripAKm = tele.tripAKm ?: 0.3,
                            tripBKm = tele.tripBKm ?: 1.0,
                            locationName = _detectedLocalityName.value.ifBlank { "Dhantoli" },
                            latitude = loc?.latitude ?: 21.1358,
                            longitude = loc?.longitude ?: 79.0790,
                            wasCharging = tele.isCharging,
                            vehicleId = tele.vehicleIdString ?: IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT
                        )
                    )
                }

                // 4. Update active Connect-to-Disconnect Trip Session
                val currentSession = _activeTripSession.value ?: return@collect
                val speed = tele.speedKmh ?: 0
                val updatedStartOdo = currentSession.startOdometerKm ?: tele.odometerKm
                val updatedStartSoc = currentSession.startBatteryPct ?: tele.batteryPercent
                val updatedMode = if (tele.rideMode == RideMode.POWER) RideMode.POWER else currentSession.dominantRideMode

                _activeTripSession.value = currentSession.copy(
                    startOdometerKm = updatedStartOdo,
                    startBatteryPct = updatedStartSoc,
                    speedSamplesSum = currentSession.speedSamplesSum + speed,
                    speedSamplesCount = currentSession.speedSamplesCount + (if (tele.speedKmh != null) 1 else 0),
                    peakSpeedKmh = max(currentSession.peakSpeedKmh, tele.peakSpeedKmh),
                    dominantRideMode = updatedMode
                )
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _lastKnownLocation.value = location
            val gpsKmh = if (location.hasSpeed()) {
                ((location.speed * 3.6) * 10.0).roundToInt() / 10.0
            } else 0.0
            _currentGpsSpeedKmh.value = gpsKmh

            scope.launch {
                val locality = tripRepository.reverseGeocodeLocality(location.latitude, location.longitude)
                val previousLocality = _detectedLocalityName.value
                _detectedLocalityName.value = locality

                val isConnected = isActivelyConnectedOrReplaying()
                // Only auto-push GPS locality when no incoming call is actively ringing on the cluster
                if (isConnected && _autoLocationSyncEnabled.value && !_incomingCallState.value.isRinging &&
                    _customLocationOverride.value.isBlank() && locality != previousLocality
                ) {
                    dumpLocationToClusterNow(locality)
                }

                val tele = packetParser.telemetryState.value
                val wheelSpeed = tele.speedKmh
                val sample = GpsBleFusionSample(
                    timestampMs = System.currentTimeMillis(),
                    wheelSpeedKmh = wheelSpeed,
                    gpsSpeedKmh = gpsKmh,
                    deltaKmh = if (wheelSpeed != null) ((wheelSpeed - gpsKmh) * 10.0).roundToInt() / 10.0 else null,
                    batteryPercent = tele.batteryPercent,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    localityName = locality
                )
                _gpsFusionSamples.update { (listOf(sample) + it).take(35) }
            }
        }
    }

    private fun isActivelyConnectedOrReplaying(): Boolean {
        val phase = _connectionPhase.value
        return phase == BleConnectionPhase.STREAMING_ACTIVE ||
            phase == BleConnectionPhase.REPLAYING_RIDING_LOG ||
            phase == BleConnectionPhase.REPLAYING_CHARGING_LOG
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnectEnabled.value = enabled
        if (!enabled) {
            reconnectJob?.cancel()
        }
    }

    fun setHeartbeatEnabled(enabled: Boolean) {
        _heartbeatEnabled.value = enabled
    }

    fun setAutoLocationSyncEnabled(enabled: Boolean) {
        _autoLocationSyncEnabled.value = enabled
    }

    fun setCustomLocationOverride(customText: String) {
        _customLocationOverride.value = customText.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
    }

    fun setSelectedRangeMode(mode: RideMode) {
        _selectedRangeMode.value = mode
    }

    fun updateTripSubcommandConfig(config: TripSubcommandConfig) {
        _tripSubcommandConfig.value = config
    }

    fun setFragmentedSliceTestEnabled(enabled: Boolean) {
        _fragmentedSliceTestEnabled.value = enabled
    }

    fun toggleReplayChargingState() {
        if (_connectionPhase.value == BleConnectionPhase.REPLAYING_CHARGING_LOG) {
            startBtsnoozHciLogReplay()
        } else {
            startChargingCaptureReplay()
        }
    }

    /**
     * Executes the full BLE Connection Handshake Synchronization Sequence:
     * 1. Phone Battery & Network Status Bar (`0x5B 0x52 0x55`, e.g. 85% + 4/4 signal bars)
     * 2. 24-Hour Local Clock & Date Sync (`0x5B 0x4A 0x44`)
     * 3. Editable Rider Greeting Banner (`0x5B 0x52`, e.g. `"Rider"`)
     * 4. Top-Line GPS Locality Pin (`0x5B 0x5C`, e.g. `"Dhantoli"`)
     */
    private fun performConnectionHandshakeSequence() {
        sendPhoneStatusRelayNow()
        syncClockToClusterNow()
        syncRiderGreetingToCluster(_riderGreetingName.value)
        dumpLocationToClusterNow()
    }

    /**
     * Dedicated Caller ID Banner (`0x5B 0x43` / `DATA_ID_CALLER_43` & `0x5B 0x5D 0x43`).
     * Extracts caller's 1st Name (e.g. `"Rahul"` from `"Rahul Sharma"`), sends `callState = 0x00` (Ringing)
     * and `[0x5B, 0x43, ...utf8(firstName), ...0x00, 0xFF]`, and starts a 500 ms periodic repeat timer
     * (`iQubeCallerDataSendTimer`) while ringing so background GPS location writes (`0x5B 0x5C`) are paused
     * and the top location pin remains independent.
     */
    fun triggerIncomingCallAlert(fullCallerName: String) {
        val cleaned = fullCallerName.trim().ifBlank { "Rahul Sharma" }
        val firstName = cleaned.split(Regex("\\s+")).firstOrNull()?.replace(Regex("[^\\x20-\\x7E]"), "")?.ifBlank { "Caller" } ?: "Caller"
        val clusterCallText = firstName.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)

        _incomingCallState.value = IncomingCallRelayState(
            isRinging = true,
            fullCallerName = cleaned,
            extractedFirstName = firstName,
            clusterFormattedText = clusterCallText,
            callStateByte = IqubeBleSpec.CALL_STATE_RINGING.toInt(),
            packetsTransmittedCount = 1,
            ringStartTimeMs = System.currentTimeMillis()
        )

        // 1. Send Call State Ringing (`0x5B 0x5D 0x43 0x00 ... 0xFF`)
        commandDispatcher.enqueueCommand(
            label = "Call State Ringing (5B 5D 43 00 … FF)",
            rawPayload = commandDispatcher.buildCallStatePacket(IqubeBleSpec.CALL_STATE_RINGING)
        )
        // 2. Send Dedicated Caller ID Frame (`0x5B 0x43 <17B firstName> 0xFF`)
        val callerPayload = commandDispatcher.buildCallerPacket0x43(cleaned)
        commandDispatcher.enqueueCommand(
            label = "Caller ID [C $clusterCallText (5B 43 … FF)",
            rawPayload = callerPayload
        )

        // 3. Run 500 ms periodic timer (`iQubeCallerDataSendTimer`) while ringing
        callerRepeatJob?.cancel()
        callerRepeatJob = scope.launch {
            while (isActive && _incomingCallState.value.isRinging) {
                delay(IqubeBleSpec.CALLER_REPEAT_TIMER_MS)
                if (_incomingCallState.value.isRinging && commandDispatcher.queueDepth.value <= 2) {
                    commandDispatcher.enqueueCommand(
                        label = "Caller Repeat 500ms [C $clusterCallText",
                        rawPayload = commandDispatcher.buildCallerPacket0x43(cleaned)
                    )
                    _incomingCallState.update {
                        it.copy(packetsTransmittedCount = it.packetsTransmittedCount + 1)
                    }
                }
            }
        }

        // Auto-clear after 15 seconds if not manually ended, restoring live GPS locality
        callAutoClearJob?.cancel()
        callAutoClearJob = scope.launch {
            delay(15_000L)
            if (_incomingCallState.value.isRinging) {
                endIncomingCallAlert()
            }
        }
    }

    /**
     * Ends the active incoming call alert, cancels the 500 ms `iQubeCallerDataSendTimer`,
     * sends `callState = 0x02` (`Ended`), and immediately restores the live GPS locality (`0x5B 0x5C ... 0xFF`).
     */
    fun endIncomingCallAlert(callStateByte: Byte = IqubeBleSpec.CALL_STATE_ENDED) {
        callerRepeatJob?.cancel()
        callAutoClearJob?.cancel()
        val prev = _incomingCallState.value
        _incomingCallState.value = prev.copy(
            isRinging = false,
            callStateByte = callStateByte.toInt()
        )
        commandDispatcher.enqueueCommand(
            label = "Call State Ended (5B 5D 43 02 … FF)",
            rawPayload = commandDispatcher.buildCallStatePacket(callStateByte)
        )
        dumpLocationToClusterNow()
    }

    /**
     * Turn-by-Turn Navigation 2-Packet Sequence (`sendNavigationData` from `BleEngine.smali`):
     * 1. Packet 1 (`0x5B 0x49`): Maneuver icon (`TurnTypeConstants.smali` `0x00..0x48`), uint16 BE distance,
     *    unit (`0x01`=m, `0x02`=0.1 km), and capped ETA (`Byte [6] = hrs`, `Byte [7] = mins`).
     * 2. Pause `400 ms` (`0x190` ms).
     * 3. Packet 2 (`0x5B 0x4E`): `"NA"` (`0x4E 0x41`) + 15-byte street name (or `0x00 0x00` + destination if `turnCode == 0x08`).
     */
    fun sendNavigationData(
        turn: TurnTypeConstant,
        distanceMeters: Int,
        unitByte: Byte = IqubeBleSpec.NAV_UNIT_METERS,
        streetName: String,
        etaMinutes: Int = _navigationState.value.etaMinutes
    ) {
        val cleanStreet = streetName.trim().ifBlank { "Wardha Rd" }.take(IqubeBleSpec.MAX_NAV_STREET_ASCII_BYTES)
        val clampedDist = distanceMeters.coerceIn(0, 65535)
        val safeEta = etaMinutes.coerceIn(0, 1439)
        val unitInt = unitByte.toInt() and 0xFF
        val pkt1 = commandDispatcher.buildNavigationManeuverPacket1(turn.code, clampedDist, unitInt, safeEta)
        val pkt2 = commandDispatcher.buildNavigationStreetPacket2(turn.code, cleanStreet)

        _navigationState.update {
            it.copy(
                selectedTurn = turn,
                distanceMeters = clampedDist,
                unitByte = pkt1[5].toInt() and 0xFF,
                etaMinutes = safeEta,
                streetName = cleanStreet,
                lastPacket1Hex = pkt1.toHexString(),
                lastPacket2Hex = pkt2.toHexString(),
                lastSentTimestampMs = System.currentTimeMillis()
            )
        }

        navSequenceJob?.cancel()
        navSequenceJob = scope.launch {
            commandDispatcher.enqueueCommand(
                label = String.format("Nav Pkt1 [I %s (%dm, ETA %dm)", turn.title, clampedDist, safeEta),
                rawPayload = pkt1
            )
            delay(IqubeBleSpec.NAVIGATION_INTER_PACKET_DELAY_MS) // 400 ms (0x190 ms)
            commandDispatcher.enqueueCommand(
                label = if (turn == TurnTypeConstant.DESTINATION_REACHED) "Nav Pkt2 [N Dest $cleanStreet" else "Nav Pkt2 [NNA $cleanStreet",
                rawPayload = pkt2
            )
        }
    }

    /**
     * Toggles a 5-Step Turn-by-Turn Navigation route simulation that streams live distance
     * countdowns, automatic `m` (`0x01`) vs `0.1 km` (`0x02`) unit scaling, and maneuver transitions onto the cluster.
     */
    fun toggleNavigationRouteSimulation() {
        if (_navigationState.value.isRouteSimulationRunning) {
            stopNavigationRouteSimulation()
            return
        }
        _navigationState.update { it.copy(isRouteSimulationRunning = true, currentRouteStepIndex = 0, totalRouteSteps = 5) }
        navigationSimJob?.cancel()
        navigationSimJob = scope.launch {
            data class SimStep(val turn: TurnTypeConstant, val distMeters: Int, val street: String, val etaMin: Int)
            val routeSteps = listOf(
                SimStep(TurnTypeConstant.CONTINUE_STRAIGHT, 1200, "Wardha Rd", 12),
                SimStep(TurnTypeConstant.CONTINUE_STRAIGHT, 800, "Wardha Rd", 9),
                SimStep(TurnTypeConstant.TURN_RIGHT, 350, "MG Road", 5),
                SimStep(TurnTypeConstant.TURN_LEFT, 150, "Sonegaon Rd", 2),
                SimStep(TurnTypeConstant.DESTINATION_REACHED, 0, "Sonegaon Hub", 0)
            )
            var idx = 0
            while (isActive && _navigationState.value.isRouteSimulationRunning) {
                val stepIdx = idx % routeSteps.size
                val step = routeSteps[stepIdx]
                _navigationState.update { it.copy(currentRouteStepIndex = stepIdx + 1, totalRouteSteps = routeSteps.size) }
                sendNavigationData(
                    turn = step.turn,
                    distanceMeters = step.distMeters,
                    unitByte = IqubeBleSpec.NAV_UNIT_METERS,
                    streetName = step.street,
                    etaMinutes = step.etaMin
                )
                idx++
                delay(2800L)
            }
        }
    }

    fun stopNavigationRouteSimulation() {
        navigationSimJob?.cancel()
        _navigationState.update { it.copy(isRouteSimulationRunning = false) }
    }

    /**
     * Simulates an instant `+1%` charging step (with the verified `115.7s` step timing)
     * so the user can test the Tier 1/2 Baseline -> Tier 3 Live `1%` Step Timing transition on demand.
     */
    fun simulateLiveOnePercentChargingStep() {
        val currentSoc = (packetParser.telemetryState.value.batteryPercent ?: replayBatterySoc).coerceIn(1, 99)
        val nextSoc = (currentSoc + 1).coerceAtMost(100)
        replayBatterySoc = nextSoc
        replayClusterRangeTenths = (nextSoc * 10.7).roundToInt() // ~40.0 km at 38% -> ~45.0 km at 42%
        replayCharging = true

        if (_chargingSessionStartSoc.value == null) {
            _chargingSessionStartSoc.value = currentSoc
            chargingSessionStartMs = System.currentTimeMillis() - 115_700L
        }
        _liveChargingStepDurationsSec.update {
            (it + IqubeBleSpec.VERIFIED_CAPTURE_SEC_PER_PCT).takeLast(10)
        }

        feedFrameToParser(HciLogReplayDataset.buildSpeedOdometerFrame0x10(0, replayOdoTenths))
        repeat(IqubeBleSpec.CHARGING_DEBOUNCE_FRAMES) {
            feedFrameToParser(
                HciLogReplayDataset.buildBatteryRangeFrame0x13(
                    socPercent = nextSoc,
                    clusterRangeTenthsKm = replayClusterRangeTenths,
                    isCharging = true
                )
            )
            feedFrameToParser(
                HciLogReplayDataset.buildTripsAndChargingFrame0x11(
                    tripATenthsKm = replayTripATenths,
                    tripBTenthsKm = replayTripBTenths,
                    isChargingActive = true,
                    socMirrorPercent = nextSoc
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startGpsTelemetryFusion() {
        try {
            val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val providers = locManager.getProviders(true)
            for (provider in providers) {
                locManager.getLastKnownLocation(provider)?.let { loc ->
                    if (_lastKnownLocation.value == null) {
                        _lastKnownLocation.value = loc
                        scope.launch {
                            _detectedLocalityName.value =
                                tripRepository.reverseGeocodeLocality(loc.latitude, loc.longitude)
                        }
                    }
                }
            }
            if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    2f,
                    locationListener,
                    Looper.getMainLooper()
                )
            } else if (locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    5f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    /**
     * Initiates real BLE scan & GATT connection to TVS iQube (`TVSiQUBECJI573` / `F8:55:48:72:96:DA` / `5456534d-5647-5341-5342-454e544f5251`).
     */
    @SuppressLint("MissingPermission")
    fun startBleScanAndConnect() {
        stopReplayModeIfNeeded()
        reconnectJob?.cancel()
        startGpsTelemetryFusion()

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _linkWarningMessage.value = "Bluetooth adapter is disabled or unavailable on this device."
            _connectionPhase.value = BleConnectionPhase.DISCONNECTED
            return
        }

        _isLinkDegraded.value = false
        _linkWarningMessage.value = null
        _connectionPhase.value = BleConnectionPhase.SCANNING

        try {
            val directDevice = adapter.getRemoteDevice(IqubeBleSpec.TARGET_MAC_ADDRESS)
            if (directDevice != null && directDevice.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                connectToGattDevice(directDevice)
                return
            }
        } catch (_: Exception) {
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _linkWarningMessage.value = "BLE Scanner unavailable. Check Bluetooth state."
            _connectionPhase.value = BleConnectionPhase.DISCONNECTED
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                scanner.startScan(emptyList<ScanFilter>(), scanSettings, bleScanCallback)
                delay(15_000L)
                if (_connectionPhase.value == BleConnectionPhase.SCANNING) {
                    scanner.stopScan(bleScanCallback)
                    _linkWarningMessage.value =
                        "No TVS iQube (`${IqubeBleSpec.ADVERTISED_BLE_NAME}` / `${IqubeBleSpec.TARGET_MAC_ADDRESS}`) found in range. Tap 'Ride Replay' or 'Charge Replay' to inspect captured scooter frames."
                    _connectionPhase.value = BleConnectionPhase.DISCONNECTED
                }
            } catch (_: SecurityException) {
                _linkWarningMessage.value = "Bluetooth scan permission required."
                _connectionPhase.value = BleConnectionPhase.DISCONNECTED
            } catch (e: Exception) {
                _linkWarningMessage.value = "BLE Scan error: ${e.localizedMessage}"
                _connectionPhase.value = BleConnectionPhase.DISCONNECTED
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val mac = device.address ?: ""
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty()

            val matchesTarget = mac.equals(IqubeBleSpec.TARGET_MAC_ADDRESS, ignoreCase = true) ||
                name.startsWith(IqubeBleSpec.NAME_FILTER_PREFIX, ignoreCase = true) ||
                name.contains("iQube", ignoreCase = true) ||
                serviceUuids.contains(IqubeBleSpec.SERVICE_UUID_SMARTXONNECT.lowercase())

            if (matchesTarget && _connectionPhase.value == BleConnectionPhase.SCANNING) {
                try {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                } catch (_: Exception) {
                }
                scanJob?.cancel()
                _rssiDbm.value = result.rssi
                connectToGattDevice(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToGattDevice(device: BluetoothDevice) {
        closeGattCleanly()
        _connectedDeviceMac.value = device.address ?: IqubeBleSpec.TARGET_MAC_ADDRESS
        _connectedDeviceName.value = device.name ?: IqubeBleSpec.ADVERTISED_BLE_NAME
        _connectionPhase.value = BleConnectionPhase.CONNECTING_GATT

        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                reconnectAttemptCount = 0
                _isLinkDegraded.value = false
                _linkWarningMessage.value = null
                _connectionPhase.value = BleConnectionPhase.DISCOVERING_SERVICES
                gatt.readRemoteRssi()
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val isSupervisionTimeout = (status == 0x08)
                handleHardwareDisconnect(
                    reason = if (isSupervisionTimeout) {
                        "Timeout 0x08 (Supervision Timeout)"
                    } else {
                        "GATT Disconnect (0x${Integer.toHexString(status)})"
                    }
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleHardwareDisconnect("Service discovery failed (0x${Integer.toHexString(status)})")
                return
            }

            var foundNotify: BluetoothGattCharacteristic? = null
            var foundWrite: BluetoothGattCharacteristic? = null

            val targetServiceUuid = runCatching { UUID.fromString(_discoveredServiceUuid.value) }.getOrNull()
            val primaryService = targetServiceUuid?.let { gatt.getService(it) }

            if (primaryService != null) {
                foundNotify = primaryService.getCharacteristic(UUID.fromString(IqubeBleSpec.CHAR_UUID_TELEMETRY_NOTIFY))
                foundWrite = primaryService.getCharacteristic(UUID.fromString(IqubeBleSpec.CHAR_UUID_COMMAND_WRITE))
            }

            if (foundNotify == null || foundWrite == null) {
                for (service in gatt.services) {
                    val chars = service.characteristics
                    val notifyCandidate = chars.firstOrNull { c ->
                        c.uuid.toString().equals(IqubeBleSpec.CHAR_UUID_TELEMETRY_NOTIFY, ignoreCase = true) ||
                            ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 &&
                                c.getDescriptor(UUID.fromString(IqubeBleSpec.DESCRIPTOR_UUID_CCCD)) != null)
                    }
                    val writeCandidate = chars.firstOrNull { c ->
                        c.uuid.toString().equals(IqubeBleSpec.CHAR_UUID_COMMAND_WRITE, ignoreCase = true) ||
                            (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    }
                    if (notifyCandidate != null && writeCandidate != null) {
                        foundNotify = notifyCandidate
                        foundWrite = writeCandidate
                        _discoveredServiceUuid.value = service.uuid.toString()
                        break
                    }
                }
            }

            if (foundNotify == null || foundWrite == null) {
                handleHardwareDisconnect("SmartXonnect Notify (0x5354) / Write (0x5352) characteristics not found")
                return
            }

            notifyCharacteristic = foundNotify
            writeCharacteristic = foundWrite

            _connectionPhase.value = BleConnectionPhase.ENABLING_CCCD
            gatt.setCharacteristicNotification(foundNotify, true)
            val cccd = foundNotify.getDescriptor(UUID.fromString(IqubeBleSpec.DESCRIPTOR_UUID_CCCD))
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            } else {
                _connectionPhase.value = BleConnectionPhase.NEGOTIATING_MTU
                gatt.requestMtu(IqubeBleSpec.REQUESTED_MTU)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid.toString().equals(IqubeBleSpec.DESCRIPTOR_UUID_CCCD, ignoreCase = true)) {
                _connectionPhase.value = BleConnectionPhase.NEGOTIATING_MTU
                gatt.requestMtu(IqubeBleSpec.REQUESTED_MTU)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            _negotiatedMtu.value = if (status == BluetoothGatt.GATT_SUCCESS) mtu else IqubeBleSpec.EXPECTED_NEGOTIATED_MTU
            _connectionPhase.value = BleConnectionPhase.SENDING_HANDSHAKE
            scope.launch {
                beginActiveTripRecordingSession()
                delay(450L)
                _connectionPhase.value = BleConnectionPhase.STREAMING_ACTIVE
                performConnectionHandshakeSequence()
                startPeriodicSyncAndWatchdog()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingTelemetryBytes(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            handleIncomingTelemetryBytes(bytes)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            commandDispatcher.onWriteResponseReceived(status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssiDbm.value = rssi
            }
        }
    }

    private fun handleIncomingTelemetryBytes(bytes: ByteArray) {
        _isLinkDegraded.value = false
        _linkWarningMessage.value = null
        packetParser.ingestRawChunk(bytes, System.currentTimeMillis())
    }

    /**
     * Built-in Riding Replay Mode (`btsnooz_hci.log`):
     * Streams the 5 throttle bursts (`11 km/h`, `20 km/h`, `10 km/h`, `52 km/h`, `14 km/h`),
     * `96%` battery (`0x03F2 = 101.0 km`), `35,925.2 km` hardware odometer (`0x5A 0x10 Bytes 3..5 = 05 7B 54`),
     * `0.3 km` Trip A & `1.0 km` Trip B (`0x5A 0x11`), and `"TVSiQUBEJI573"` (`0x5A 0x7D`).
     */
    fun startBtsnoozHciLogReplay() {
        disconnectBle(userInitiated = true)
        packetParser.resetIntegratedSubMeterTrip()
        startGpsTelemetryFusion()

        scope.launch {
            _connectedDeviceName.value = IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT
            _connectedDeviceMac.value = IqubeBleSpec.TARGET_MAC_ADDRESS
            _rssiDbm.value = -58
            _isLinkDegraded.value = false
            _linkWarningMessage.value = null
            replayOdoTenths = HciLogReplayDataset.CHARGING_CAPTURE_ODO_TENTHS_KM // 359252 = 35,925.2 km
            replayTripATenths = HciLogReplayDataset.CHARGING_CAPTURE_TRIP_A_TENTHS_KM // 3 = 0.3 km
            replayTripBTenths = HciLogReplayDataset.CHARGING_CAPTURE_TRIP_B_TENTHS_KM // 10 = 1.0 km
            replayBatterySoc = 96
            replayClusterRangeTenths = 1010 // 101.0 km (0x03F2 BMS DTE at 96% SoC)
            replayCharging = false

            _connectionPhase.value = BleConnectionPhase.ENABLING_CCCD
            delay(100L)
            _connectionPhase.value = BleConnectionPhase.NEGOTIATING_MTU
            _negotiatedMtu.value = IqubeBleSpec.EXPECTED_NEGOTIATED_MTU
            delay(100L)
            _connectionPhase.value = BleConnectionPhase.SENDING_HANDSHAKE
            delay(120L)
            _connectionPhase.value = BleConnectionPhase.REPLAYING_RIDING_LOG

            // Emit initial unfiltered 20-byte frames
            feedFrameToParser(HciLogReplayDataset.buildSpeedOdometerFrame0x10(0, replayOdoTenths))
            feedFrameToParser(HciLogReplayDataset.buildBatteryRangeFrame0x13(replayBatterySoc, replayClusterRangeTenths, false))
            feedFrameToParser(HciLogReplayDataset.buildVehicleIdentityFrame0x7D())
            feedFrameToParser(
                HciLogReplayDataset.buildTripsAndChargingFrame0x11(
                    tripATenthsKm = replayTripATenths,
                    tripBTenthsKm = replayTripBTenths,
                    isChargingActive = false,
                    socMirrorPercent = replayBatterySoc
                )
            )
            beginActiveTripRecordingSession()
            performConnectionHandshakeSequence()
            startPeriodicSyncAndWatchdog()

            replayJob?.cancel()
            replayJob = scope.launch {
                val speedProfile = HciLogReplayDataset.CAPTURED_SPEED_PROFILE_KMH
                var tick = 0
                while (isActive && _connectionPhase.value == BleConnectionPhase.REPLAYING_RIDING_LOG) {
                    val speed = speedProfile[tick % speedProfile.size]
                    val integratedKm = packetParser.telemetryState.value.integratedDistanceMeters / 1000.0
                    val addedTenths = (integratedKm * 10.0).toInt()

                    // 1. 0x5A 0x10 Speed + Total Hardware Odometer (4.7 Hz = 212ms)
                    feedFrameToParser(
                        HciLogReplayDataset.buildSpeedOdometerFrame0x10(
                            speedKmh = speed,
                            totalOdometerTenthsKm = replayOdoTenths + addedTenths
                        )
                    )

                    // 2. 0x5A 0x13 Battery + Cluster Range & 0x5A 0x7D Vehicle ID (3.1 Hz)
                    if (tick % 2 == 0) {
                        val dynamicSoc = max(85, replayBatterySoc - (addedTenths / 10))
                        // When speed >= 38 km/h (Power sprint), simulate cluster BMS DTE drop to Power mode ratio (~0.80 km/%)
                        val bmsFactor = if (speed >= 38) 8.0 else 10.5
                        val dynamicRangeTenths = (dynamicSoc * bmsFactor).roundToInt()
                        feedFrameToParser(
                            HciLogReplayDataset.buildBatteryRangeFrame0x13(
                                socPercent = dynamicSoc,
                                clusterRangeTenthsKm = dynamicRangeTenths,
                                isCharging = false
                            )
                        )
                        feedFrameToParser(HciLogReplayDataset.buildVehicleIdentityFrame0x7D())
                    }

                    // 3. 0x5A 0x11 Trip A, Trip B & Charging Active Flag (0.5 Hz = ~2.02s)
                    if (tick % 10 == 0 && tick > 0) {
                        feedFrameToParser(
                            HciLogReplayDataset.buildTripsAndChargingFrame0x11(
                                tripATenthsKm = replayTripATenths + addedTenths,
                                tripBTenthsKm = replayTripBTenths + addedTenths,
                                isChargingActive = false,
                                socMirrorPercent = replayBatterySoc
                            )
                        )
                    }

                    _rssiDbm.value = -58 - (tick % 5)
                    tick++
                    delay(212L)
                }
            }
        }
    }

    /**
     * Built-in Charging Capture Replay Mode (`btsnoop_hci.log`):
     * Streams the unfiltered charging capture (`38% -> 42%` SoC, `40.0 -> 45.0 km` cluster range,
     * `0x5A 0x13 Byte 5 = 0x06`, `0x5A 0x11 Byte 5 = 0x01`, `0x5A 0x10` odometer `35,925.2 km`,
     * and `"TVSiQUBEJI573"` in `0x5A 0x7D`).
     */
    fun startChargingCaptureReplay() {
        disconnectBle(userInitiated = true)
        packetParser.resetIntegratedSubMeterTrip()
        startGpsTelemetryFusion()

        scope.launch {
            _connectedDeviceName.value = IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT
            _connectedDeviceMac.value = IqubeBleSpec.TARGET_MAC_ADDRESS
            _rssiDbm.value = -54
            _isLinkDegraded.value = false
            _linkWarningMessage.value = null

            val steps = HciLogReplayDataset.CHARGING_CAPTURE_STEPS
            var stepIndex = 0
            replayOdoTenths = HciLogReplayDataset.CHARGING_CAPTURE_ODO_TENTHS_KM // 359252 = 35,925.2 km
            replayTripATenths = HciLogReplayDataset.CHARGING_CAPTURE_TRIP_A_TENTHS_KM // 0.3 km
            replayTripBTenths = HciLogReplayDataset.CHARGING_CAPTURE_TRIP_B_TENTHS_KM // 1.0 km
            replayBatterySoc = steps[0].socPercent // 38%
            replayClusterRangeTenths = steps[0].clusterRangeTenthsKm // 400 = 40.0 km
            replayCharging = true

            chargingSessionStartMs = System.currentTimeMillis()
            _chargingSessionStartSoc.value = replayBatterySoc
            lastChargingSocValue = replayBatterySoc
            lastChargingSocStepTimestampMs = System.currentTimeMillis()
            firstChargingTickSeen = false
            _liveChargingStepDurationsSec.value = emptyList()

            _connectionPhase.value = BleConnectionPhase.ENABLING_CCCD
            delay(100L)
            _connectionPhase.value = BleConnectionPhase.NEGOTIATING_MTU
            _negotiatedMtu.value = IqubeBleSpec.EXPECTED_NEGOTIATED_MTU
            delay(100L)
            _connectionPhase.value = BleConnectionPhase.SENDING_HANDSHAKE
            delay(120L)
            _connectionPhase.value = BleConnectionPhase.REPLAYING_CHARGING_LOG

            // Initial unfiltered charging packets at 38% SoC (satisfies 3-frame debounce gate with speed=0)
            feedFrameToParser(HciLogReplayDataset.buildSpeedOdometerFrame0x10(0, replayOdoTenths))
            feedFrameToParser(HciLogReplayDataset.buildVehicleIdentityFrame0x7D())
            repeat(IqubeBleSpec.CHARGING_DEBOUNCE_FRAMES) {
                feedFrameToParser(HciLogReplayDataset.buildBatteryRangeFrame0x13(replayBatterySoc, replayClusterRangeTenths, true))
                feedFrameToParser(
                    HciLogReplayDataset.buildTripsAndChargingFrame0x11(
                        tripATenthsKm = replayTripATenths,
                        tripBTenthsKm = replayTripBTenths,
                        isChargingActive = true,
                        socMirrorPercent = replayBatterySoc
                    )
                )
            }
            performConnectionHandshakeSequence()
            startPeriodicSyncAndWatchdog()

            replayJob?.cancel()
            replayJob = scope.launch {
                var tick = 0
                while (isActive && _connectionPhase.value == BleConnectionPhase.REPLAYING_CHARGING_LOG) {
                    if (tick > 0 && tick % 22 == 0 && stepIndex < steps.size - 1) {
                        stepIndex++
                        val nextStep = steps[stepIndex]
                        replayBatterySoc = nextStep.socPercent
                        replayClusterRangeTenths = nextStep.clusterRangeTenthsKm
                        nextStep.measuredStepDurationSec?.let { measuredSec ->
                            _liveChargingStepDurationsSec.update { (it + measuredSec).takeLast(10) }
                        }
                    }

                    // 1. 0x5A 0x10 Speed = 0 km/h, Odometer = 35,925.2 km (05 7B 54)
                    feedFrameToParser(HciLogReplayDataset.buildSpeedOdometerFrame0x10(0, replayOdoTenths))

                    // 2. 0x5A 0x13 Battery SoC (38..42%) + Cluster Range (40.0..45.0 km) + Byte 5 = 0x06
                    if (tick % 2 == 0) {
                        feedFrameToParser(
                            HciLogReplayDataset.buildBatteryRangeFrame0x13(
                                socPercent = replayBatterySoc,
                                clusterRangeTenthsKm = replayClusterRangeTenths,
                                isCharging = true
                            )
                        )
                        feedFrameToParser(HciLogReplayDataset.buildVehicleIdentityFrame0x7D())
                    }

                    // 3. 0x5A 0x11 Trip A (0.3 km), Byte 5 = 0x01 (Charging), Byte 6 = SoC Mirror, Trip B (1.0 km)
                    if (tick % 8 == 0) {
                        feedFrameToParser(
                            HciLogReplayDataset.buildTripsAndChargingFrame0x11(
                                tripATenthsKm = replayTripATenths,
                                tripBTenthsKm = replayTripBTenths,
                                isChargingActive = true,
                                socMirrorPercent = replayBatterySoc
                            )
                        )
                    }

                    _rssiDbm.value = -54 - (tick % 4)
                    tick++
                    delay(212L)
                }
            }
        }
    }

    private fun feedFrameToParser(frame20B: ByteArray) {
        val now = System.currentTimeMillis()
        if (_fragmentedSliceTestEnabled.value && frame20B.size == 20) {
            val slice1 = frame20B.copyOfRange(0, 8)
            val slice2 = frame20B.copyOfRange(8, 20)
            packetParser.ingestRawChunk(slice1, now)
            packetParser.ingestRawChunk(slice2, now)
        } else {
            packetParser.ingestRawChunk(frame20B, now)
        }
    }

    /**
     * Starts the periodic `[JD` clock sync heartbeat (`1.8s`), the automatic `0x5B 0x5C` Location Sync
     * loop (`10s`), the `60s` Phone Status Bar keepalive (`0x5B 0x52 0x55`), and the `2,500 ms` Link Watchdog.
     */
    @SuppressLint("MissingPermission")
    private fun startPeriodicSyncAndWatchdog() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(IqubeBleSpec.HEARTBEAT_INTERVAL_MS)
                if (isActivelyConnectedOrReplaying() && _heartbeatEnabled.value && !_isLinkDegraded.value) {
                    syncClockToClusterNow(isBackgroundHeartbeat = true)
                }
                if (_connectionPhase.value == BleConnectionPhase.STREAMING_ACTIVE) {
                    try {
                        activeGatt?.readRemoteRssi()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        locationSyncJob?.cancel()
        locationSyncJob = scope.launch {
            while (isActive) {
                delay(IqubeBleSpec.LOCATION_SYNC_INTERVAL_MS)
                // Pause automatic GPS location dumps while an incoming call is ringing on the cluster
                if (isActivelyConnectedOrReplaying() && _autoLocationSyncEnabled.value &&
                    !_incomingCallState.value.isRinging && !_isLinkDegraded.value
                ) {
                    dumpLocationToClusterNow()
                }
            }
        }

        phoneStatusKeepaliveJob?.cancel()
        phoneStatusKeepaliveJob = scope.launch {
            while (isActive) {
                delay(IqubeBleSpec.PHONE_STATUS_SYNC_INTERVAL_MS)
                if (isActivelyConnectedOrReplaying() && !_isLinkDegraded.value) {
                    sendPhoneStatusRelayNow()
                }
            }
        }

        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(500L)
                if (isActivelyConnectedOrReplaying()) {
                    val lastRx = packetParser.telemetryState.value.lastPacketTimestampMs
                    if (lastRx != null) {
                        val silenceMs = System.currentTimeMillis() - lastRx
                        if (silenceMs > IqubeBleSpec.LINK_WATCHDOG_DEGRADED_MS) {
                            _isLinkDegraded.value = true
                            _linkWarningMessage.value =
                                "Link Degraded (${silenceMs}ms silence) — Supervision Timeout (0x08) Imminent. Heartbeats paused."
                        }
                    }
                }
            }
        }
    }

    private fun onWriteAckTimeoutDetected() {
        _isLinkDegraded.value = true
        _linkWarningMessage.value =
            "ATT Write Request (0x12) unacknowledged >500ms (Packet #1676 condition). Heartbeats paused."
    }

    @SuppressLint("MissingPermission")
    private suspend fun performHardwareOrReplayWrite(payload: ByteArray): Boolean {
        val phase = _connectionPhase.value
        if (phase == BleConnectionPhase.REPLAYING_RIDING_LOG || phase == BleConnectionPhase.REPLAYING_CHARGING_LOG) {
            scope.launch {
                delay(28L)
                val cfg = _tripSubcommandConfig.value
                if (payload.size == 20 && payload[0] == IqubeBleSpec.HEADER_TELEMETRY && payload[1] == 0xF1.toByte()) {
                    val subCmd = payload[3].toInt() and 0xFF
                    when (subCmd) {
                        cfg.switchTripAByte -> {
                            packetParser.setActiveTripTab(0)
                        }
                        cfg.switchTripBByte -> {
                            packetParser.setActiveTripTab(1)
                        }
                        cfg.resetActiveTripByte -> {
                            val currentTab = packetParser.telemetryState.value.activeTripTab
                            if (currentTab == 1) {
                                replayTripBTenths = 0
                            } else {
                                replayTripATenths = 0
                            }
                            packetParser.resetIntegratedSubMeterTrip()
                            feedFrameToParser(
                                HciLogReplayDataset.buildTripsAndChargingFrame0x11(
                                    tripATenthsKm = replayTripATenths,
                                    tripBTenthsKm = replayTripBTenths,
                                    isChargingActive = replayCharging,
                                    socMirrorPercent = replayBatterySoc
                                )
                            )
                        }
                    }
                }
                commandDispatcher.onWriteResponseReceived(0)
            }
            return true
        }

        val gatt = activeGatt ?: return false
        val writeChar = writeCharacteristic ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(
                    writeChar,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                res == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                writeChar.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(writeChar)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Button 1: Switch to Trip A (`[0x5A, 0xF1, 0x00, subCmdA, 0, ..., 0, 0xFF]`).
     */
    fun sendSwitchToTripACommand() {
        val subCmd = _tripSubcommandConfig.value.switchTripAByte.toByte()
        packetParser.setActiveTripTab(0)
        commandDispatcher.enqueueCommand(
            label = String.format("Switch to Trip A (5A F1 00 %02X … FF)", subCmd.toInt() and 0xFF),
            rawPayload = commandDispatcher.buildTripControlFrame(subCmd)
        )
    }

    /**
     * Button 2: Switch to Trip B (`[0x5A, 0xF1, 0x00, subCmdB, 0, ..., 0, 0xFF]`).
     */
    fun sendSwitchToTripBCommand() {
        val subCmd = _tripSubcommandConfig.value.switchTripBByte.toByte()
        packetParser.setActiveTripTab(1)
        commandDispatcher.enqueueCommand(
            label = String.format("Switch to Trip B (5A F1 00 %02X … FF)", subCmd.toInt() and 0xFF),
            rawPayload = commandDispatcher.buildTripControlFrame(subCmd)
        )
    }

    /**
     * Button 3: Reset Active Trip (A/B) (`[0x5A, 0xF1, 0x00, subCmdReset, 0, ..., 0, 0xFF]`).
     */
    fun sendResetActiveTripCommand() {
        val subCmd = _tripSubcommandConfig.value.resetActiveTripByte.toByte()
        commandDispatcher.enqueueCommand(
            label = String.format("Reset Active Trip A/B (5A F1 00 %02X … FF)", subCmd.toInt() and 0xFF),
            rawPayload = commandDispatcher.buildTripControlFrame(subCmd)
        )
    }

    /**
     * Builds and enqueues the 20-byte Location Display Packet (`[0x5B, 0x5C, ...17B ASCII, 0xFF]`)
     * to Handle 0x0022 (`0x5352`).
     */
    fun dumpLocationToClusterNow(explicitLocationText: String? = null) {
        val maxLen = IqubeBleSpec.MAX_LOCATION_ASCII_BYTES
        val effectiveLocation = when {
            !explicitLocationText.isNullOrBlank() -> explicitLocationText.trim().take(maxLen)
            _customLocationOverride.value.isNotBlank() -> _customLocationOverride.value.trim().take(maxLen)
            else -> _detectedLocalityName.value.trim().take(maxLen).ifEmpty { "Dhantoli" }
        }
        _lastSyncedClusterLocation.value = effectiveLocation
        val payload = commandDispatcher.buildClusterLocationPacket(effectiveLocation)
        commandDispatcher.enqueueCommand(
            label = "Cluster Location [\\$effectiveLocation (0xFF)",
            rawPayload = payload
        )
    }

    private fun readHardwarePhoneBatteryPercent(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
            if (pct in 1..100) pct else 85
        } catch (_: Exception) {
            85
        }
    }

    fun setPhoneBatteryOverride(batteryPct: Int, overrideEnabled: Boolean = true) {
        val clamped = batteryPct.coerceIn(0, 100)
        _phoneStatusState.update {
            it.copy(
                phoneBatteryPct = clamped,
                isBatteryOverrideEnabled = overrideEnabled
            )
        }
        sendPhoneStatusRelayNow()
    }

    /**
     * Transmits `0x5B 0x52 0x55` (`[0x5B, 0x52, 0x55, phoneBatteryPct, signalBars, 0x01, 0..0, 0xFF]`)
     * to synchronize the cluster's top status tray battery and cellular bars.
     */
    fun sendPhoneStatusRelayNow(signalBars: Int = _phoneStatusState.value.signalBars) {
        val currentState = _phoneStatusState.value
        val batteryPct = if (currentState.isBatteryOverrideEnabled) {
            currentState.phoneBatteryPct
        } else {
            readHardwarePhoneBatteryPercent()
        }
        val now = System.currentTimeMillis()
        _phoneStatusState.update {
            it.copy(
                phoneBatteryPct = batteryPct,
                signalBars = signalBars.coerceIn(0, 4),
                lastPhoneStatusSyncMs = now
            )
        }
        commandDispatcher.enqueueCommand(
            label = "Phone Status [RU (${batteryPct}% • ${signalBars}/4 bars)",
            rawPayload = commandDispatcher.buildPhoneStatusPacket(batteryPct, signalBars)
        )
    }

    /**
     * Transmits `0x5B 0x4A 0x44` (`[0x5B, 0x4A, 0x44, hh, mm, ss, 0x01, DD, MM, YY, 0..0, 0xFF]`)
     * to synchronize the cluster's 24-hour local RTC clock & date.
     */
    fun syncClockToClusterNow(isBackgroundHeartbeat: Boolean = false) {
        val now = System.currentTimeMillis()
        _phoneStatusState.update { it.copy(lastClockSyncMs = now) }
        commandDispatcher.enqueueCommand(
            label = if (isBackgroundHeartbeat) "Clock Heartbeat [JD (24h Sync)" else "Clock & Date Sync [JD (0x5B 0x4A 0x44)",
            rawPayload = commandDispatcher.buildClockSyncPacket()
        )
    }

    /**
     * Transmits `0x5B 0x62` (`SONG_NAME_PACKET`) when playing, or restores caller/locality banner when paused.
     */
    fun sendMusicTrackToCluster(songTitle: String, artistName: String = _mediaPlayerState.value.artistName, isPlaying: Boolean = true) {
        val cleaned = songTitle.replace(Regex("[^\\x20-\\x7E]"), "").trim().ifEmpty { "Midnight City" }.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
        _musicTrackTitle.value = cleaned
        val pkt = commandDispatcher.buildMusicTrackPacket(cleaned)
        _mediaPlayerState.update {
            it.copy(
                trackTitle = cleaned,
                artistName = artistName.ifBlank { "M83" },
                isPlaying = isPlaying,
                lastSentBannerHex = pkt.toHexString()
            )
        }
        if (isPlaying) {
            commandDispatcher.enqueueCommand(
                label = "Music Track [b $cleaned (0x5B 0x62)",
                rawPayload = pkt
            )
        } else {
            // Restore active caller banner or GPS locality banner when music pauses
            if (_incomingCallState.value.isRinging) {
                commandDispatcher.enqueueCommand(
                    label = "Restore Caller [C ${_incomingCallState.value.clusterFormattedText}",
                    rawPayload = commandDispatcher.buildCallerPacket0x43(_incomingCallState.value.fullCallerName)
                )
            } else {
                dumpLocationToClusterNow()
            }
        }
    }

    /**
     * Handles physical handlebar media switches (`0x5A 0x6B`) or simulated handlebar triggers.
     */
    private fun handleIncomingMediaCommand(cmd: MusicCommand, timestampMs: Long) {
        val current = _mediaPlayerState.value
        when (cmd) {
            MusicCommand.PLAY -> {
                sendMusicTrackToCluster(current.trackTitle, current.artistName, isPlaying = true)
            }
            MusicCommand.PAUSE -> {
                sendMusicTrackToCluster(current.trackTitle, current.artistName, isPlaying = false)
            }
            MusicCommand.PLAY_PAUSE_TOGGLE -> {
                val nextPlaying = !current.isPlaying
                sendMusicTrackToCluster(current.trackTitle, current.artistName, isPlaying = nextPlaying)
            }
            MusicCommand.NEXT_TRACK -> {
                val nextIdx = (current.playlistIndex + 1) % playlist.size
                val track = playlist[nextIdx]
                _mediaPlayerState.update { it.copy(playlistIndex = nextIdx) }
                sendMusicTrackToCluster(track.title, track.artist, isPlaying = true)
            }
            MusicCommand.PREVIOUS_TRACK -> {
                val prevIdx = if (current.playlistIndex - 1 < 0) playlist.size - 1 else current.playlistIndex - 1
                val track = playlist[prevIdx]
                _mediaPlayerState.update { it.copy(playlistIndex = prevIdx) }
                sendMusicTrackToCluster(track.title, track.artist, isPlaying = true)
            }
            MusicCommand.VOLUME_UP -> {
                val newVol = (current.volumePercent + 10).coerceAtMost(100)
                _mediaPlayerState.update { it.copy(volumePercent = newVol) }
            }
            MusicCommand.VOLUME_DOWN -> {
                val newVol = (current.volumePercent - 10).coerceAtLeast(0)
                _mediaPlayerState.update { it.copy(volumePercent = newVol) }
            }
        }
        _mediaPlayerState.update {
            it.copy(
                lastHandlebarCommand = cmd,
                lastHandlebarCommandTimestampMs = timestampMs
            )
        }
    }

    /**
     * Injects a `0x5A 0x6B` Handlebar Media Control frame into `packetParser` so the entire
     * incoming parser + media player pipeline executes identically to hardware.
     */
    fun simulateHandlebarMediaKey(command: MusicCommand) {
        val frame = HciLogReplayDataset.buildMediaControlFrame0x6B(command.code)
        feedFrameToParser(frame)
    }

    fun syncRiderGreetingToCluster(riderName: String) {
        val cleaned = riderName.replace(Regex("[^\\x20-\\x7E]"), "").trim().ifEmpty { "Rider" }.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
        _riderGreetingName.value = cleaned
        tripRepository.saveRiderName(cleaned)
        commandDispatcher.enqueueCommand(
            label = "Rider Greeting [R $cleaned (0x5B 0x52)",
            rawPayload = commandDispatcher.buildRiderNamePacket(cleaned)
        )
    }

    fun sendCustomVerifiedHexFrame(label: String, payload: ByteArray): Result<Unit> {
        return commandDispatcher.enqueueCommand(label, payload).map { }
    }

    private fun beginActiveTripRecordingSession() {
        val tele = packetParser.telemetryState.value
        val loc = _lastKnownLocation.value
        val lat = loc?.latitude ?: 21.1358
        val lon = loc?.longitude ?: 79.0790
        val locality = _detectedLocalityName.value.ifBlank { "Dhantoli" }

        _activeTripSession.value = ActiveTripSession(
            startTimeMs = System.currentTimeMillis(),
            startLocationName = locality,
            startLatitude = lat,
            startLongitude = lon,
            startOdometerKm = tele.odometerKm,
            startBatteryPct = tele.batteryPercent
        )
    }

    private fun finalizeSessionOnDisconnect(disconnectReason: String) {
        val now = System.currentTimeMillis()
        val tele = packetParser.telemetryState.value

        // 1. Finalize Charging Session if charging occurred with >= 1% gain
        val startChgMs = chargingSessionStartMs
        val startChgSoc = _chargingSessionStartSoc.value
        val endChgSoc = tele.batteryPercent
        if (startChgMs != null && startChgSoc != null && endChgSoc != null && endChgSoc > startChgSoc) {
            val pctGained = endChgSoc - startChgSoc
            val liveSteps = _liveChargingStepDurationsSec.value
            val avgSecPerPct = if (liveSteps.isNotEmpty()) {
                liveSteps.average()
            } else {
                max(60.0, ((now - startChgMs) / 1000.0) / pctGained.toDouble())
            }
            val durationSec = (pctGained * avgSecPerPct).toLong().coerceAtLeast(60L)
            tripRepository.saveChargingSession(
                SavedChargingSession(
                    id = "chg_$now",
                    startTimeMs = startChgMs,
                    endTimeMs = now,
                    startPct = startChgSoc,
                    endPct = endChgSoc,
                    pctGained = pctGained,
                    durationSeconds = durationSec,
                    avgSecondsPerPct = (avgSecPerPct * 10.0).roundToInt() / 10.0
                )
            )
        }
        chargingSessionStartMs = null
        _chargingSessionStartSoc.value = null
        lastChargingSocValue = null
        lastChargingSocStepTimestampMs = null
        firstChargingTickSeen = false
        _liveChargingStepDurationsSec.value = emptyList()

        // 2. Finalize Riding Trip Session (commit if distanceKm >= 0.1 OR durationSec >= 30 OR distanceKm > 0)
        val session = _activeTripSession.value ?: return
        _activeTripSession.value = null

        val durationSec = max(1L, (now - session.startTimeMs) / 1000L)
        val loc = _lastKnownLocation.value
        val endLat = loc?.latitude ?: session.startLatitude
        val endLon = loc?.longitude ?: session.startLongitude
        val endLocationName = _detectedLocalityName.value.ifBlank { session.startLocationName }

        val startOdo = session.startOdometerKm ?: tele.odometerKm ?: 35925.2
        val endOdo = tele.odometerKm ?: startOdo
        val odoDeltaKm = max(0.0, endOdo - startOdo)
        val subMeterKm = tele.integratedDistanceMeters / 1000.0
        val distanceKm = ((max(subMeterKm, odoDeltaKm)) * 100.0).roundToInt() / 100.0

        val startSoc = session.startBatteryPct ?: tele.batteryPercent ?: 91
        val endSoc = tele.batteryPercent ?: startSoc
        val batteryUsed = max(0, startSoc - endSoc)

        val avgSpeed = if (session.speedSamplesCount > 0) {
            ((session.speedSamplesSum.toDouble() / session.speedSamplesCount) * 10.0).roundToInt() / 10.0
        } else 0.0

        val efficiency = if (batteryUsed >= 1 && distanceKm > 0.0) {
            ((distanceKm / batteryUsed.toDouble()) * 100.0).roundToInt() / 100.0
        } else if (session.dominantRideMode == RideMode.POWER) {
            IqubeBleSpec.DEFAULT_POWER_KM_PER_PCT
        } else {
            IqubeBleSpec.DEFAULT_ECO_KM_PER_PCT
        }

        if (!tele.isCharging && (distanceKm >= 0.1 || durationSec >= 30L || distanceKm > 0.0 || (tele.speedKmh ?: 0) > 0)) {
            val savedRecord = SavedTripRecord(
                id = "trip_$now",
                startTimeMs = session.startTimeMs,
                endTimeMs = now,
                durationSeconds = durationSec,
                startLocationName = session.startLocationName,
                startLatitude = session.startLatitude,
                startLongitude = session.startLongitude,
                endLocationName = endLocationName,
                endLatitude = endLat,
                endLongitude = endLon,
                distanceKm = distanceKm,
                startOdometerKm = (startOdo * 10.0).roundToInt() / 10.0,
                endOdometerKm = (endOdo * 10.0).roundToInt() / 10.0,
                startBatteryPct = startSoc,
                endBatteryPct = endSoc,
                batteryUsedPct = batteryUsed,
                peakSpeedKmh = max(session.peakSpeedKmh, tele.peakSpeedKmh),
                avgSpeedKmh = avgSpeed,
                rideMode = session.dominantRideMode,
                efficiencyKmPerPct = efficiency,
                disconnectReason = disconnectReason
            )
            tripRepository.saveCompletedTrip(savedRecord)
        }
    }

    private fun handleHardwareDisconnect(reason: String) {
        captureLastParkedLocationOnDisconnect()
        finalizeSessionOnDisconnect(reason)
        closeGattCleanly()
        commandDispatcher.clearPendingQueueOnDisconnect()
        packetParser.clearTelemetryOnDisconnect()
        _rssiDbm.value = null
        _negotiatedMtu.value = null
        _linkWarningMessage.value = reason

        if (_autoReconnectEnabled.value) {
            scheduleExponentialBackoffReconnect()
        } else {
            _connectionPhase.value = BleConnectionPhase.DISCONNECTED
        }
    }

    private fun scheduleExponentialBackoffReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _connectionPhase.value = BleConnectionPhase.RECONNECTING_BACKOFF
            val attempt = ++reconnectAttemptCount
            val backoffMs = min(30_000L, 1500L * (1L shl min(attempt, 4)))
            _linkWarningMessage.value =
                "Link lost. Auto-reconnect attempt #$attempt in ${backoffMs / 1000.0}s (30s HCI cancel guard)…"
            delay(backoffMs)
            if (isActive && _autoReconnectEnabled.value) {
                startBleScanAndConnect()
            }
        }
    }

    private fun captureLastParkedLocationOnDisconnect() {
        val tele = packetParser.telemetryState.value
        val loc = _lastKnownLocation.value
        val lat = loc?.latitude ?: 21.1358
        val lon = loc?.longitude ?: 79.0790
        val acc = loc?.accuracy ?: 10.0f
        val trueEco = trueRangeState.value.trueEcoRangeKm

        if (tele.batteryPercent != null || tele.speedKmh != null || tele.odometerKm != null) {
            val locality = _detectedLocalityName.value.ifBlank { "Dhantoli" }
            _lastParkedLocation.value = LastParkedLocation(
                locationName = locality,
                latitude = lat,
                longitude = lon,
                accuracyMeters = acc,
                timestampMs = System.currentTimeMillis(),
                batteryPercentAtPark = tele.batteryPercent,
                rangeKmAtPark = trueEco,
                odometerKmAtPark = tele.odometerKm
            )
            if (tele.batteryPercent != null) {
                tripRepository.saveLastSeenState(
                    LastSeenScooterState(
                        timestampMs = System.currentTimeMillis(),
                        batteryPct = tele.batteryPercent,
                        odometerKm = tele.odometerKm ?: 35925.2,
                        clusterRangeKm = tele.clusterRangeKm ?: (tele.batteryPercent * 1.0),
                        tripAKm = tele.tripAKm ?: 0.3,
                        tripBKm = tele.tripBKm ?: 1.0,
                        locationName = locality,
                        latitude = lat,
                        longitude = lon,
                        wasCharging = tele.isCharging,
                        vehicleId = tele.vehicleIdString ?: IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT
                    )
                )
            }
        }
    }

    private fun stopReplayModeIfNeeded() {
        replayJob?.cancel()
        replayJob = null
        heartbeatJob?.cancel()
        locationSyncJob?.cancel()
        phoneStatusKeepaliveJob?.cancel()
        watchdogJob?.cancel()
        callerRepeatJob?.cancel()
        stopNavigationRouteSimulation()
    }

    fun disconnectBle(userInitiated: Boolean = true) {
        if (userInitiated) {
            reconnectJob?.cancel()
            scanJob?.cancel()
        }
        captureLastParkedLocationOnDisconnect()
        finalizeSessionOnDisconnect(if (userInitiated) "Manual Disconnect" else "BLE Link Lost")
        stopReplayModeIfNeeded()
        closeGattCleanly()
        commandDispatcher.clearPendingQueueOnDisconnect()
        packetParser.clearTelemetryOnDisconnect()
        _rssiDbm.value = null
        _negotiatedMtu.value = null
        _isLinkDegraded.value = false
        _connectionPhase.value = BleConnectionPhase.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun closeGattCleanly() {
        try {
            activeGatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            activeGatt?.close()
        } catch (_: Exception) {
        }
        activeGatt = null
        notifyCharacteristic = null
        writeCharacteristic = null
    }
}
