package com.example.ble

import android.content.Context
import android.location.Geocoder
import com.example.model.ChargingMonitorState
import com.example.model.IqubeBleSpec
import com.example.model.LastSeenScooterState
import com.example.model.RideMode
import com.example.model.SavedChargingSession
import com.example.model.SavedTripRecord
import com.example.model.TrueRangeCalculation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Manages:
 * 1. Persistent Connect-to-Disconnect Trip History & Charging Sessions (stored in SharedPreferences JSON)
 * 2. Offline / Disconnected `LastSeenScooterState` persistence (Last Seen Time, Battery %, Odometer, Location, & Expected True Range)
 * 3. True Range Calculation Engine (Eco Mode `1.00 km/%` & Power Mode `0.75 km/%` baseline + learned efficiency)
 * 4. 3-Tier Charging Time Predictor (`115.7s / +1%` verified capture rate & `123.75s / +1%` 2h 45m 0–80% default -> Session History -> Live `+1%` Step Timing)
 * 5. Reverse Geocoding via OpenStreetMap Nominatim (`https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=...&lon=...`)
 *    for the 17-character cluster locality name (`Bytes 2..18`, with `Byte 19 = 0xFF`).
 */
class TrueRangeAndTripRepository(private val context: Context?) {

    companion object {
        private const val PREFS_NAME = "iqube_saved_trips_store"
        private const val KEY_SAVED_TRIPS_JSON = "saved_trips_json_v1"
        private const val KEY_SAVED_CHARGING_JSON = "saved_charging_sessions_v1"
        private const val KEY_LAST_SEEN_STATE_JSON = "last_seen_scooter_state_v1"

        /**
         * Pure, unit-testable True Range calculation function blending Saved Previous Trips
         * and the Current Active Trip. Also computes Expected True Range when offline using Last Seen Battery %.
         */
        fun computeTrueRange(
            currentBatteryPct: Int?,
            savedTrips: List<SavedTripRecord>,
            activeTripDistanceKm: Double = 0.0,
            activeTripStartBatteryPct: Int? = null,
            activeTripMode: RideMode = RideMode.ECO
        ): TrueRangeCalculation {
            // Rolling 3-Trip window where distanceKm >= 1.0 && batteryUsedPct >= 1
            val qualifyingTrips = savedTrips
                .filter { it.distanceKm >= 1.0 && it.batteryUsedPct >= 1 }
                .take(3)

            var histEcoKm = 0.0
            var histEcoBatteryPct = 0
            var histPowerKm = 0.0
            var histPowerBatteryPct = 0

            for (trip in qualifyingTrips) {
                if (trip.ecoDistanceKm > 0.0 || trip.powerDistanceKm > 0.0) {
                    histEcoKm += trip.ecoDistanceKm
                    histEcoBatteryPct += trip.ecoBatteryUsedPct
                    histPowerKm += trip.powerDistanceKm
                    histPowerBatteryPct += trip.powerBatteryUsedPct
                } else {
                    if (trip.rideMode == RideMode.POWER) {
                        histPowerKm += trip.distanceKm
                        histPowerBatteryPct += trip.batteryUsedPct
                    } else {
                        histEcoKm += trip.distanceKm
                        histEcoBatteryPct += trip.batteryUsedPct
                    }
                }
            }

            val currBatteryDropPct = if (activeTripStartBatteryPct != null && currentBatteryPct != null) {
                max(0, activeTripStartBatteryPct - currentBatteryPct)
            } else 0

            var liveEcoKm = 0.0
            var liveEcoBatt = 0
            var livePowerKm = 0.0
            var livePowerBatt = 0

            if (currBatteryDropPct >= 1 && activeTripDistanceKm > 0.0) {
                if (activeTripMode == RideMode.POWER) {
                    livePowerKm = activeTripDistanceKm
                    livePowerBatt = currBatteryDropPct
                } else {
                    liveEcoKm = activeTripDistanceKm
                    liveEcoBatt = currBatteryDropPct
                }
            }

            val totalEcoKm = histEcoKm + liveEcoKm
            val totalEcoBatt = histEcoBatteryPct + liveEcoBatt
            val totalPowerKm = histPowerKm + livePowerKm
            val totalPowerBatt = histPowerBatteryPct + livePowerBatt

            val isEcoLearned = totalEcoBatt >= 1 && totalEcoKm > 0.0
            val isPowerLearned = totalPowerBatt >= 1 && totalPowerKm > 0.0

            val ecoKmPerPct: Double = if (isEcoLearned) {
                ((totalEcoKm / totalEcoBatt.toDouble()).coerceIn(0.50, 1.60) * 100.0).roundToInt() / 100.0
            } else {
                IqubeBleSpec.DEFAULT_ECO_KM_PER_PCT
            }

            val powerKmPerPct: Double = if (isPowerLearned) {
                ((totalPowerKm / totalPowerBatt.toDouble()).coerceIn(0.35, 1.30) * 100.0).roundToInt() / 100.0
            } else if (isEcoLearned) {
                ((ecoKmPerPct * IqubeBleSpec.POWER_TO_ECO_RATIO) * 100.0).roundToInt() / 100.0
            } else {
                IqubeBleSpec.DEFAULT_POWER_KM_PER_PCT
            }

            val totalRawKm = qualifyingTrips.sumOf { it.distanceKm } +
                (if (currBatteryDropPct >= 1) activeTripDistanceKm else 0.0)
            val totalBatteryPctUsed = qualifyingTrips.sumOf { it.batteryUsedPct } +
                (if (currBatteryDropPct >= 1) currBatteryDropPct else 0)

            val sourceLabel = when {
                isPowerLearned && isEcoLearned -> String.format(
                    Locale.US,
                    "Learned Eco (%.2f km/%% from %.1f km) & Power (%.2f km/%% from %.1f km)",
                    ecoKmPerPct, totalEcoKm, powerKmPerPct, totalPowerKm
                )
                isEcoLearned -> String.format(
                    Locale.US,
                    "Learned Eco (%.2f km/%% from %.1f km) • Default Power Ratio",
                    ecoKmPerPct, totalEcoKm
                )
                isPowerLearned -> String.format(
                    Locale.US,
                    "Learned Power (%.2f km/%% from %.1f km) • Default Eco Baseline",
                    powerKmPerPct, totalPowerKm
                )
                else -> "Default Baseline (Eco 1.00 km/% • Power 0.75 km/%)"
            }

            val trueEcoRange = currentBatteryPct?.let {
                ((it * ecoKmPerPct) * 10.0).roundToInt() / 10.0
            }
            val truePowerRange = currentBatteryPct?.let {
                ((it * powerKmPerPct) * 10.0).roundToInt() / 10.0
            }

            return TrueRangeCalculation(
                trueEcoRangeKm = trueEcoRange,
                truePowerRangeKm = truePowerRange,
                ecoKmPerPct = ecoKmPerPct,
                powerKmPerPct = powerKmPerPct,
                totalDistanceLearnedKm = (totalRawKm * 100.0).roundToInt() / 100.0,
                totalBatteryUsedLearnedPct = totalBatteryPctUsed,
                contributingTripsCount = qualifyingTrips.size + (if (currBatteryDropPct >= 1) 1 else 0),
                isUsingLearnedEfficiency = isEcoLearned || isPowerLearned,
                calculationSourceLabel = sourceLabel
            )
        }

        /**
         * Pure, unit-testable 3-Tier Charging Time Predictor & Interval Analyzer:
         * - Blends Previous Charging Intervals (`savedChargingSessions`, rolling window of up to 5 qualifying intervals)
         *   with Current Charging Session (`liveStepDurationsSec`) to learn both:
         *   1) `0–80%` Bulk (CC) Charging Rate (`learned0To80SecPerPct`, default `123.75s / 1%` = `2h 45m`)
         *   2) `80–100%` Taper (CV) Charging Rate (`learned80To100SecPerPct`, default `180.0s / 1%` = `1h 00m`)
         * - Supports Offline Predicted Charging when the scooter is turned off / disconnected while charging,
         *   stepping battery `%` according to the predicted rate and flagging `needsCalibration = true`.
         */
        fun computeChargingMonitorState(
            isCharging: Boolean,
            currentBatteryPct: Int?,
            clusterRangeKm: Double?,
            sessionStartBatteryPct: Int?,
            liveStepDurationsSec: List<Double>,
            savedChargingSessions: List<SavedChargingSession>,
            isDisconnectedPredicted: Boolean = false,
            needsCalibration: Boolean = false,
            calibrationStatusLabel: String = "Calibrated (Live BMS)",
            nextPercentProgressFraction: Float = 0f,
            disconnectStartBatteryPct: Int? = null
        ): ChargingMonitorState {
            // Rolling 5-Session window where pctGained >= 1 && durationSeconds >= 45
            val qualifyingHistory = savedChargingSessions
                .filter { it.pctGained >= 1 && it.durationSeconds >= 45L }
                .take(5)

            var histCcPct = 0
            var histCcSec = 0.0
            var histCvPct = 0
            var histCvSec = 0.0

            for (session in qualifyingHistory) {
                if (session.cc0To80PctGained > 0 || session.cv80To100PctGained > 0) {
                    if (session.cc0To80PctGained > 0 && session.cc0To80SecondsPerPct > 0.0) {
                        histCcPct += session.cc0To80PctGained
                        histCcSec += session.cc0To80PctGained * session.cc0To80SecondsPerPct
                    }
                    if (session.cv80To100PctGained > 0 && session.cv80To100SecondsPerPct > 0.0) {
                        histCvPct += session.cv80To100PctGained
                        histCvSec += session.cv80To100PctGained * session.cv80To100SecondsPerPct
                    }
                } else {
                    val start = session.startPct.coerceIn(0, 100)
                    val end = session.endPct.coerceIn(start, 100)
                    val ccGained = max(0, minOf(80, end) - minOf(80, start))
                    val cvGained = max(0, end - max(80, start))
                    if (ccGained > 0 && cvGained == 0) {
                        histCcPct += ccGained
                        histCcSec += session.durationSeconds.toDouble()
                    } else if (cvGained > 0 && ccGained == 0) {
                        histCvPct += cvGained
                        histCvSec += session.durationSeconds.toDouble()
                    } else if (ccGained + cvGained > 0) {
                        // Split total duration proportionally to default 123.75 vs 180 weight
                        val ccWeight = ccGained * IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT
                        val cvWeight = cvGained * IqubeBleSpec.DEFAULT_80_TO_100_SEC_PER_PCT
                        val totalWeight = (ccWeight + cvWeight).coerceAtLeast(1.0)
                        histCcPct += ccGained
                        histCcSec += session.durationSeconds * (ccWeight / totalWeight)
                        histCvPct += cvGained
                        histCvSec += session.durationSeconds * (cvWeight / totalWeight)
                    }
                }
            }

            // Tier 3: Live +1% Step Timing in current charging session
            val validLiveSteps = liveStepDurationsSec.filter { it in 45.0..280.0 }
            val isLiveStepTimed = validLiveSteps.isNotEmpty()
            val liveMeasuredSecPerPct = if (isLiveStepTimed) {
                (validLiveSteps.average() * 10.0).roundToInt() / 10.0
            } else null

            // Blend current session's live steps into the appropriate CC (0-80%) or CV (80-100%) bucket
            var liveCcPct = 0
            var liveCcSec = 0.0
            var liveCvPct = 0
            var liveCvSec = 0.0
            if (isLiveStepTimed) {
                val startSoc = sessionStartBatteryPct ?: ((currentBatteryPct ?: 50) - validLiveSteps.size)
                validLiveSteps.forEachIndexed { index, stepSec ->
                    val stepTargetSoc = startSoc + index + 1
                    if (stepTargetSoc <= 80) {
                        liveCcPct += 1
                        liveCcSec += stepSec
                    } else {
                        liveCvPct += 1
                        liveCvSec += stepSec
                    }
                }
            }

            val totalCcPct = histCcPct + liveCcPct
            val totalCcSec = histCcSec + liveCcSec
            val totalCvPct = histCvPct + liveCvPct
            val totalCvSec = histCvSec + liveCvSec

            val isCcLearned = totalCcPct >= 1 && totalCcSec > 0.0
            val isCvLearned = totalCvPct >= 1 && totalCvSec > 0.0

            val learned0To80SecPerPct = if (isCcLearned) {
                ((totalCcSec / totalCcPct.toDouble()).coerceIn(60.0, 240.0) * 10.0).roundToInt() / 10.0
            } else {
                IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT // 123.75s per 1% (2h 45m for 0-80%)
            }

            val learned80To100SecPerPct = if (isCvLearned) {
                ((totalCvSec / totalCvPct.toDouble()).coerceIn(90.0, 300.0) * 10.0).roundToInt() / 10.0
            } else {
                IqubeBleSpec.DEFAULT_80_TO_100_SEC_PER_PCT // 180.0s per 1% (80->100%)
            }

            val baselineSecPerPct = if (histCcPct >= 1 && histCcSec > 0.0) {
                (histCcSec / histCcPct.toDouble()).coerceIn(60.0, 240.0)
            } else {
                IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT
            }

            val currentInTaperPhase = (currentBatteryPct ?: 0) >= 80
            val activeSecPerPct = when {
                isLiveStepTimed && liveMeasuredSecPerPct != null -> liveMeasuredSecPerPct
                currentInTaperPhase -> learned80To100SecPerPct
                else -> learned0To80SecPerPct
            }

            val pctPerHour = ((3600.0 / activeSecPerPct) * 10.0).roundToInt() / 10.0

            val remSecTo80 = currentBatteryPct?.let { soc ->
                (max(0, 80 - soc) * learned0To80SecPerPct).roundToLong()
            }
            val remSecTo100 = currentBatteryPct?.let { soc ->
                val pctTo80 = max(0, 80 - soc)
                val pct80To100 = if (soc < 80) 20 else max(0, 100 - soc)
                (pctTo80 * learned0To80SecPerPct + pct80To100 * learned80To100SecPerPct).roundToLong()
            }

            val baselineRemSecTo80 = currentBatteryPct?.let { soc ->
                (max(0, 80 - soc) * baselineSecPerPct).roundToLong()
            }
            val baselineRemSecTo100 = currentBatteryPct?.let { soc ->
                val pctTo80 = max(0, 80 - soc)
                val pct80To100 = if (soc < 80) 20 else max(0, 100 - soc)
                (pctTo80 * baselineSecPerPct + pct80To100 * IqubeBleSpec.DEFAULT_80_TO_100_SEC_PER_PCT).roundToLong()
            }

            val true0To80TotalSec = (80 * learned0To80SecPerPct).roundToLong()
            val true0To100TotalSec = (80 * learned0To80SecPerPct + 20 * learned80To100SecPerPct).roundToLong()

            val pctGained = if (sessionStartBatteryPct != null && currentBatteryPct != null) {
                max(0, currentBatteryPct - sessionStartBatteryPct)
            } else 0

            // TVS iQube 3.04 kWh usable pack: ~0.0304 kWh per 1% SoC
            val estimatedKwhAdded = ((pctGained * 0.0304) * 100.0).roundToInt() / 100.0
            val totalLearnedPct = totalCcPct + totalCvPct
            val contributingSessions = qualifyingHistory.size + (if (isLiveStepTimed) 1 else 0)

            val modeLabel = when {
                isDisconnectedPredicted -> String.format(
                    Locale.US,
                    "Offline Predicted Charging (%.1fs/+1%% • +%.1f%%/hr) • Needs Calibration",
                    activeSecPerPct,
                    pctPerHour
                )
                isLiveStepTimed && qualifyingHistory.isNotEmpty() -> String.format(
                    Locale.US,
                    "Blended Live + %d Past Interval(s) (0–80%%: %.1fs/%% • 80–100%%: %.1fs/%%)",
                    qualifyingHistory.size,
                    learned0To80SecPerPct,
                    learned80To100SecPerPct
                )
                isLiveStepTimed -> String.format(
                    Locale.US,
                    "Live 1%% Step Timing (%.1fs / +1%% • +%.1f%%/hr)",
                    activeSecPerPct,
                    pctPerHour
                )
                qualifyingHistory.isNotEmpty() -> String.format(
                    Locale.US,
                    "Learned from %d Interval(s) (0–80%%: %.1fs/%% • 80–100%%: %.1fs/%%)",
                    qualifyingHistory.size,
                    learned0To80SecPerPct,
                    learned80To100SecPerPct
                )
                else -> "Default Baseline (2h 45m 0–80% = 123.8s/% • 80–100% = 180s/%)"
            }

            val true0To80Min = (true0To80TotalSec / 60.0).roundToInt()
            val true0To100Min = (true0To100TotalSec / 60.0).roundToInt()
            val speedDeltaPct = (((IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT - learned0To80SecPerPct) /
                IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT) * 100.0).roundToInt()
            val speedComparison = when {
                speedDeltaPct >= 2 -> "${speedDeltaPct}% faster than 650W factory baseline"
                speedDeltaPct <= -2 -> "${-speedDeltaPct}% slower than 650W factory baseline"
                else -> "matching 650W factory curve"
            }

            val insightText = buildString {
                append(
                    String.format(
                        Locale.US,
                        "True 0→80%%: %dh %02dm (%.1fs/%%) • True 0→100%%: %dh %02dm (80–100%% taper %.1fs/%%) — %s.",
                        true0To80Min / 60,
                        true0To80Min % 60,
                        learned0To80SecPerPct,
                        true0To100Min / 60,
                        true0To100Min % 60,
                        learned80To100SecPerPct,
                        speedComparison
                    )
                )
                if (isDisconnectedPredicted || needsCalibration) {
                    append(" Scooter BLE disconnected while charging: advancing SoC via learned curve (Needs Calibration on reconnect).")
                } else if (contributingSessions > 0) {
                    append(" Analyzed across $contributingSessions interval(s) (+$totalLearnedPct% total SoC learned).")
                }
            }

            return ChargingMonitorState(
                isCharging = isCharging,
                currentBatteryPct = currentBatteryPct,
                clusterRangeKm = clusterRangeKm,
                sessionStartBatteryPct = sessionStartBatteryPct,
                pctGainedThisSession = pctGained,
                activeSecondsPerPct = (activeSecPerPct * 100.0).roundToInt() / 100.0,
                baselineSecondsPerPct = (baselineSecPerPct * 100.0).roundToInt() / 100.0,
                liveMeasuredSecondsPerPct = liveMeasuredSecPerPct,
                chargingSpeedPctPerHour = pctPerHour,
                remainingSecondsTo80 = remSecTo80,
                remainingSecondsTo100 = remSecTo100,
                baselineRemainingSecondsTo80 = baselineRemSecTo80,
                baselineRemainingSecondsTo100 = baselineRemSecTo100,
                predictionModeLabel = modeLabel,
                isUsingLiveStepTiming = isLiveStepTimed,
                completedStepsCount = validLiveSteps.size,
                isDisconnectedPredicted = isDisconnectedPredicted,
                needsCalibration = needsCalibration,
                calibrationStatusLabel = calibrationStatusLabel,
                nextPercentProgressFraction = nextPercentProgressFraction.coerceIn(0f, 1f),
                disconnectStartBatteryPct = disconnectStartBatteryPct,
                learned0To80SecPerPct = learned0To80SecPerPct,
                learned80To100SecPerPct = learned80To100SecPerPct,
                true0To80TotalSeconds = true0To80TotalSec,
                true0To100TotalSeconds = true0To100TotalSec,
                contributingSessionsCount = contributingSessions,
                totalPctLearned = totalLearnedPct,
                estimatedKwhAddedThisSession = estimatedKwhAdded,
                chargingInsightText = insightText
            )
        }
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _savedTrips = MutableStateFlow<List<SavedTripRecord>>(loadTripsFromStorage())
    val savedTrips: StateFlow<List<SavedTripRecord>> = _savedTrips.asStateFlow()

    private val _savedChargingSessions = MutableStateFlow<List<SavedChargingSession>>(loadChargingSessionsFromStorage())
    val savedChargingSessions: StateFlow<List<SavedChargingSession>> = _savedChargingSessions.asStateFlow()

    private val _lastSeenState = MutableStateFlow<LastSeenScooterState?>(loadLastSeenStateFromStorage())
    val lastSeenState: StateFlow<LastSeenScooterState?> = _lastSeenState.asStateFlow()

    fun loadRiderName(): String {
        return prefs?.getString("rider_greeting_name_v1", "Rider")?.ifBlank { "Rider" } ?: "Rider"
    }

    fun saveRiderName(name: String) {
        val cleaned = name.replace(Regex("[^\\x20-\\x7E]"), "").trim().ifEmpty { "Rider" }.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
        prefs?.edit()?.putString("rider_greeting_name_v1", cleaned)?.apply()
    }

    fun saveCompletedTrip(trip: SavedTripRecord) {
        val updated = listOf(trip) + _savedTrips.value
        _savedTrips.value = updated
        persistTripsToStorage(updated)
    }

    fun deleteTrip(tripId: String) {
        val updated = _savedTrips.value.filterNot { it.id == tripId }
        _savedTrips.value = updated
        persistTripsToStorage(updated)
    }

    fun clearAllTrips() {
        _savedTrips.value = emptyList()
        persistTripsToStorage(emptyList())
    }

    fun saveChargingSession(session: SavedChargingSession) {
        val updated = listOf(session) + _savedChargingSessions.value
        _savedChargingSessions.value = updated
        persistChargingSessionsToStorage(updated)
    }

    fun deleteChargingSession(sessionId: String) {
        val updated = _savedChargingSessions.value.filterNot { it.id == sessionId }
        _savedChargingSessions.value = updated
        persistChargingSessionsToStorage(updated)
    }

    fun clearAllChargingSessions() {
        _savedChargingSessions.value = emptyList()
        persistChargingSessionsToStorage(emptyList())
    }

    /**
     * Logs a sample verified charging interval (e.g., 38% -> 82% across CC bulk + CV taper)
     * so users can immediately inspect Charging Interval Analysis & True Charging Time insights.
     */
    fun logSampleChargingSession(
        startPct: Int = 38,
        endPct: Int = 82,
        ccSecPerPct: Double = 115.7,
        cvSecPerPct: Double = 172.0,
        wasOfflinePredicted: Boolean = false
    ): SavedChargingSession {
        val now = System.currentTimeMillis()
        val gained = max(1, endPct - startPct)
        val ccGained = max(0, minOf(80, endPct) - minOf(80, startPct))
        val cvGained = max(0, endPct - max(80, startPct))
        val durationSec = (ccGained * ccSecPerPct + cvGained * cvSecPerPct).roundToLong().coerceAtLeast(60L)
        val avgSecPerPct = ((durationSec.toDouble() / gained.toDouble()) * 10.0).roundToInt() / 10.0
        val kwhAdded = ((gained * 0.0304) * 100.0).roundToInt() / 100.0
        val ecoKmPerPct = computeTrueRange(endPct, _savedTrips.value).ecoKmPerPct
        val ecoRangeAdded = ((gained * ecoKmPerPct) * 10.0).roundToInt() / 10.0

        val session = SavedChargingSession(
            id = "chg_$now",
            startTimeMs = now - durationSec * 1000L,
            endTimeMs = now,
            startPct = startPct,
            endPct = endPct,
            pctGained = gained,
            durationSeconds = durationSec,
            avgSecondsPerPct = avgSecPerPct,
            cc0To80PctGained = ccGained,
            cc0To80SecondsPerPct = if (ccGained > 0) ccSecPerPct else 0.0,
            cv80To100PctGained = cvGained,
            cv80To100SecondsPerPct = if (cvGained > 0) cvSecPerPct else 0.0,
            estimatedKwhAdded = kwhAdded,
            trueEcoRangeAddedKm = ecoRangeAdded,
            wasOfflinePredicted = wasOfflinePredicted,
            calibrationDeltaPct = 0,
            sessionSource = if (wasOfflinePredicted) "Offline Predicted + Calibrated" else "Verified 650W Charger Session"
        )
        saveChargingSession(session)
        return session
    }

    fun saveLastSeenState(state: LastSeenScooterState) {
        _lastSeenState.value = state
        persistLastSeenStateToStorage(state)
    }

    /**
     * Logs a realistic sample Connect-to-Disconnect trip so the user can immediately test
     * the True Range Learning math and CSV/JSON export.
     */
    fun logSampleVerifiedTrip(
        distanceKm: Double = 4.60,
        startSoc: Int = 95,
        endSoc: Int = 90,
        rideMode: RideMode = RideMode.ECO,
        startLocality: String = "Sonegaon, Nagpur",
        endLocality: String = "Sitabuldi, Nagpur"
    ): SavedTripRecord {
        val now = System.currentTimeMillis()
        val durationSec = 680L
        val usedPct = max(0, startSoc - endSoc)
        val eff = if (usedPct > 0) {
            ((distanceKm / usedPct.toDouble()) * 100.0).roundToInt() / 100.0
        } else IqubeBleSpec.DEFAULT_ECO_KM_PER_PCT

        val sample = SavedTripRecord(
            id = "trip_$now",
            startTimeMs = now - durationSec * 1000L,
            endTimeMs = now,
            durationSeconds = durationSec,
            startLocationName = startLocality,
            startLatitude = 21.0922,
            startLongitude = 79.0560,
            endLocationName = endLocality,
            endLatitude = 21.1431,
            endLongitude = 79.0806,
            distanceKm = (distanceKm * 100.0).roundToInt() / 100.0,
            startOdometerKm = 35920.6,
            endOdometerKm = 35925.2,
            startBatteryPct = startSoc,
            endBatteryPct = endSoc,
            batteryUsedPct = usedPct,
            peakSpeedKmh = 52,
            avgSpeedKmh = ((distanceKm / (durationSec / 3600.0)) * 10.0).roundToInt() / 10.0,
            rideMode = rideMode,
            efficiencyKmPerPct = eff,
            disconnectReason = "Verified Sample Trip"
        )
        saveCompletedTrip(sample)
        return sample
    }

    /**
     * Reverse-geocodes GPS coordinates via OpenStreetMap Nominatim
     * (`https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}`)
     * to extract a concise suburb/locality/road name (up to 17 ASCII chars for `0x5B 0x5C ... 0xFF` cluster display).
     */
    suspend fun reverseGeocodeLocality(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        try {
            val urlStr = String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=16&addressdetails=1",
                lat,
                lon
            )
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "TVSiQubeCockpitApp/1.0 (OpenSource BLE Telemetry)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(body)
                val address = json.optJSONObject("address")
                if (address != null) {
                    val candidate = listOf(
                        address.optString("suburb"),
                        address.optString("neighbourhood"),
                        address.optString("road"),
                        address.optString("village"),
                        address.optString("town"),
                        address.optString("city_district"),
                        address.optString("city")
                    ).firstOrNull { it.isNotBlank() }
                    if (!candidate.isNullOrBlank()) {
                        return@withContext sanitizeClusterAscii(candidate)
                    }
                }
                val nameField = json.optString("name")
                if (nameField.isNotBlank()) {
                    return@withContext sanitizeClusterAscii(nameField)
                }
            }
        } catch (_: Exception) {
        }

        try {
            if (context != null && Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.US)
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocation(lat, lon, 1)
                val first = results?.firstOrNull()
                if (first != null) {
                    val candidate = first.subLocality ?: first.thoroughfare ?: first.locality
                    if (!candidate.isNullOrBlank()) {
                        return@withContext sanitizeClusterAscii(candidate)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return@withContext String.format(Locale.US, "%.3f,%.3f", lat, lon).take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
    }

    private fun sanitizeClusterAscii(raw: String): String {
        return raw.replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
            .take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
            .ifEmpty { "Sonegaon" }
    }

    fun exportTripsToCsv(trips: List<SavedTripRecord> = _savedTrips.value): String {
        val sb = StringBuilder()
        sb.appendLine(
            "id,start_time_iso,end_time_iso,duration_sec,start_location,start_lat,start_lon,end_location,end_lat,end_lon,distance_km,start_odo_km,end_odo_km,start_battery_pct,end_battery_pct,battery_used_pct,peak_speed_kmh,avg_speed_kmh,ride_mode,efficiency_km_per_pct,disconnect_reason"
        )
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        for (t in trips) {
            sb.appendLine(
                listOf(
                    t.id,
                    isoFmt.format(Date(t.startTimeMs)),
                    isoFmt.format(Date(t.endTimeMs)),
                    t.durationSeconds,
                    "\"${t.startLocationName.replace("\"", "'")}\"",
                    String.format(Locale.US, "%.5f", t.startLatitude),
                    String.format(Locale.US, "%.5f", t.startLongitude),
                    "\"${t.endLocationName.replace("\"", "'")}\"",
                    String.format(Locale.US, "%.5f", t.endLatitude),
                    String.format(Locale.US, "%.5f", t.endLongitude),
                    String.format(Locale.US, "%.2f", t.distanceKm),
                    String.format(Locale.US, "%.1f", t.startOdometerKm),
                    String.format(Locale.US, "%.1f", t.endOdometerKm),
                    t.startBatteryPct,
                    t.endBatteryPct,
                    t.batteryUsedPct,
                    t.peakSpeedKmh,
                    String.format(Locale.US, "%.1f", t.avgSpeedKmh),
                    t.rideMode.name,
                    String.format(Locale.US, "%.2f", t.efficiencyKmPerPct),
                    "\"${t.disconnectReason.replace("\"", "'")}\""
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    fun exportTripsToJson(trips: List<SavedTripRecord> = _savedTrips.value): String {
        val arr = JSONArray()
        for (t in trips) {
            arr.put(tripToJsonObject(t))
        }
        return arr.toString(2)
    }

    private fun loadTripsFromStorage(): List<SavedTripRecord> {
        val raw = prefs?.getString(KEY_SAVED_TRIPS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<SavedTripRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SavedTripRecord(
                        id = obj.optString("id", "trip_$i"),
                        startTimeMs = obj.optLong("startTimeMs", 0L),
                        endTimeMs = obj.optLong("endTimeMs", 0L),
                        durationSeconds = obj.optLong("durationSeconds", 0L),
                        startLocationName = obj.optString("startLocationName", "Unknown"),
                        startLatitude = obj.optDouble("startLatitude", 0.0),
                        startLongitude = obj.optDouble("startLongitude", 0.0),
                        endLocationName = obj.optString("endLocationName", "Unknown"),
                        endLatitude = obj.optDouble("endLatitude", 0.0),
                        endLongitude = obj.optDouble("endLongitude", 0.0),
                        distanceKm = obj.optDouble("distanceKm", 0.0),
                        startOdometerKm = obj.optDouble("startOdometerKm", 35925.2),
                        endOdometerKm = obj.optDouble("endOdometerKm", 35925.2),
                        startBatteryPct = obj.optInt("startBatteryPct", 91),
                        endBatteryPct = obj.optInt("endBatteryPct", 91),
                        batteryUsedPct = obj.optInt("batteryUsedPct", 0),
                        peakSpeedKmh = obj.optInt("peakSpeedKmh", 0),
                        avgSpeedKmh = obj.optDouble("avgSpeedKmh", 0.0),
                        rideMode = runCatching {
                            RideMode.valueOf(obj.optString("rideMode", RideMode.ECO.name))
                        }.getOrDefault(RideMode.ECO),
                        efficiencyKmPerPct = obj.optDouble("efficiencyKmPerPct", 1.0),
                        disconnectReason = obj.optString("disconnectReason", "Disconnected"),
                        ecoDistanceKm = obj.optDouble("ecoDistanceKm", 0.0),
                        powerDistanceKm = obj.optDouble("powerDistanceKm", 0.0),
                        ecoBatteryUsedPct = obj.optInt("ecoBatteryUsedPct", 0),
                        powerBatteryUsedPct = obj.optInt("powerBatteryUsedPct", 0)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistTripsToStorage(trips: List<SavedTripRecord>) {
        val arr = JSONArray()
        for (t in trips) {
            arr.put(tripToJsonObject(t))
        }
        prefs?.edit()?.putString(KEY_SAVED_TRIPS_JSON, arr.toString())?.apply()
    }

    private fun tripToJsonObject(t: SavedTripRecord): JSONObject {
        return JSONObject().apply {
            put("id", t.id)
            put("startTimeMs", t.startTimeMs)
            put("endTimeMs", t.endTimeMs)
            put("durationSeconds", t.durationSeconds)
            put("startLocationName", t.startLocationName)
            put("startLatitude", t.startLatitude)
            put("startLongitude", t.startLongitude)
            put("endLocationName", t.endLocationName)
            put("endLatitude", t.endLatitude)
            put("endLongitude", t.endLongitude)
            put("distanceKm", t.distanceKm)
            put("startOdometerKm", t.startOdometerKm)
            put("endOdometerKm", t.endOdometerKm)
            put("startBatteryPct", t.startBatteryPct)
            put("endBatteryPct", t.endBatteryPct)
            put("batteryUsedPct", t.batteryUsedPct)
            put("peakSpeedKmh", t.peakSpeedKmh)
            put("avgSpeedKmh", t.avgSpeedKmh)
            put("rideMode", t.rideMode.name)
            put("efficiencyKmPerPct", t.efficiencyKmPerPct)
            put("disconnectReason", t.disconnectReason)
            put("ecoDistanceKm", t.ecoDistanceKm)
            put("powerDistanceKm", t.powerDistanceKm)
            put("ecoBatteryUsedPct", t.ecoBatteryUsedPct)
            put("powerBatteryUsedPct", t.powerBatteryUsedPct)
        }
    }

    private fun loadChargingSessionsFromStorage(): List<SavedChargingSession> {
        val raw = prefs?.getString(KEY_SAVED_CHARGING_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<SavedChargingSession>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val startPct = obj.optInt("startPct", 38)
                val endPct = obj.optInt("endPct", 42)
                val pctGained = obj.optInt("pctGained", max(1, endPct - startPct))
                val durationSec = obj.optLong("durationSeconds", 463L)
                val avgSecPerPct = obj.optDouble("avgSecondsPerPct", 115.7)
                val defaultCcGained = max(0, minOf(80, endPct) - minOf(80, startPct))
                val defaultCvGained = max(0, endPct - max(80, startPct))
                list.add(
                    SavedChargingSession(
                        id = obj.optString("id", "chg_$i"),
                        startTimeMs = obj.optLong("startTimeMs", 0L),
                        endTimeMs = obj.optLong("endTimeMs", 0L),
                        startPct = startPct,
                        endPct = endPct,
                        pctGained = pctGained,
                        durationSeconds = durationSec,
                        avgSecondsPerPct = avgSecPerPct,
                        cc0To80PctGained = obj.optInt("cc0To80PctGained", defaultCcGained),
                        cc0To80SecondsPerPct = obj.optDouble("cc0To80SecondsPerPct", if (defaultCcGained > 0) avgSecPerPct else 0.0),
                        cv80To100PctGained = obj.optInt("cv80To100PctGained", defaultCvGained),
                        cv80To100SecondsPerPct = obj.optDouble("cv80To100SecondsPerPct", if (defaultCvGained > 0) avgSecPerPct else 0.0),
                        estimatedKwhAdded = obj.optDouble("estimatedKwhAdded", ((pctGained * 0.0304) * 100.0).roundToInt() / 100.0),
                        trueEcoRangeAddedKm = obj.optDouble("trueEcoRangeAddedKm", pctGained.toDouble()),
                        wasOfflinePredicted = obj.optBoolean("wasOfflinePredicted", false),
                        calibrationDeltaPct = obj.optInt("calibrationDeltaPct", 0),
                        sessionSource = obj.optString("sessionSource", "Connected BLE Session")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistChargingSessionsToStorage(sessions: List<SavedChargingSession>) {
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("startTimeMs", s.startTimeMs)
                    put("endTimeMs", s.endTimeMs)
                    put("startPct", s.startPct)
                    put("endPct", s.endPct)
                    put("pctGained", s.pctGained)
                    put("durationSeconds", s.durationSeconds)
                    put("avgSecondsPerPct", s.avgSecondsPerPct)
                    put("cc0To80PctGained", s.cc0To80PctGained)
                    put("cc0To80SecondsPerPct", s.cc0To80SecondsPerPct)
                    put("cv80To100PctGained", s.cv80To100PctGained)
                    put("cv80To100SecondsPerPct", s.cv80To100SecondsPerPct)
                    put("estimatedKwhAdded", s.estimatedKwhAdded)
                    put("trueEcoRangeAddedKm", s.trueEcoRangeAddedKm)
                    put("wasOfflinePredicted", s.wasOfflinePredicted)
                    put("calibrationDeltaPct", s.calibrationDeltaPct)
                    put("sessionSource", s.sessionSource)
                }
            )
        }
        prefs?.edit()?.putString(KEY_SAVED_CHARGING_JSON, arr.toString())?.apply()
    }

    private fun loadLastSeenStateFromStorage(): LastSeenScooterState? {
        val raw = prefs?.getString(KEY_LAST_SEEN_STATE_JSON, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            LastSeenScooterState(
                timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                batteryPct = obj.optInt("batteryPct", 42),
                odometerKm = obj.optDouble("odometerKm", 35925.2),
                clusterRangeKm = obj.optDouble("clusterRangeKm", 45.0),
                tripAKm = obj.optDouble("tripAKm", 0.3),
                tripBKm = obj.optDouble("tripBKm", 1.0),
                locationName = obj.optString("locationName", "Sonegaon"),
                latitude = obj.optDouble("latitude", 21.0922),
                longitude = obj.optDouble("longitude", 79.0560),
                wasCharging = obj.optBoolean("wasCharging", false),
                vehicleId = obj.optString("vehicleId", IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT),
                needsCalibration = obj.optBoolean("needsCalibration", false),
                isOfflinePredictedCharging = obj.optBoolean("isOfflinePredictedCharging", false)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun persistLastSeenStateToStorage(state: LastSeenScooterState) {
        val obj = JSONObject().apply {
            put("timestampMs", state.timestampMs)
            put("batteryPct", state.batteryPct)
            put("odometerKm", state.odometerKm)
            put("clusterRangeKm", state.clusterRangeKm)
            put("tripAKm", state.tripAKm)
            put("tripBKm", state.tripBKm)
            put("locationName", state.locationName)
            put("latitude", state.latitude)
            put("longitude", state.longitude)
            put("wasCharging", state.wasCharging)
            put("vehicleId", state.vehicleId)
            put("needsCalibration", state.needsCalibration)
            put("isOfflinePredictedCharging", state.isOfflinePredictedCharging)
        }
        prefs?.edit()?.putString(KEY_LAST_SEEN_STATE_JSON, obj.toString())?.apply()
    }
}
