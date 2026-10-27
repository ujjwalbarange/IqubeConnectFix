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
            var histEcoEquivKm = 0.0
            var histBatteryPct = 0

            for (trip in qualifyingTrips) {
                val ecoNormalizedTripKm = if (trip.rideMode == RideMode.POWER) {
                    trip.distanceKm / IqubeBleSpec.POWER_TO_ECO_RATIO
                } else {
                    trip.distanceKm
                }
                histEcoEquivKm += ecoNormalizedTripKm
                histBatteryPct += trip.batteryUsedPct
            }

            val currBatteryDropPct = if (activeTripStartBatteryPct != null && currentBatteryPct != null) {
                max(0, activeTripStartBatteryPct - currentBatteryPct)
            } else 0

            val currEcoEquivKm = if (currBatteryDropPct >= 1 && activeTripDistanceKm > 0.0) {
                if (activeTripMode == RideMode.POWER) {
                    activeTripDistanceKm / IqubeBleSpec.POWER_TO_ECO_RATIO
                } else {
                    activeTripDistanceKm
                }
            } else 0.0

            val totalEcoEquivKm = histEcoEquivKm + currEcoEquivKm
            val totalRawKm = qualifyingTrips.sumOf { it.distanceKm } +
                (if (currBatteryDropPct >= 1) activeTripDistanceKm else 0.0)
            val totalBatteryPctUsed = histBatteryPct + (if (currEcoEquivKm > 0.0) currBatteryDropPct else 0)

            val isLearned = totalBatteryPctUsed >= 1 && totalEcoEquivKm > 0.0
            val ecoKmPerPct: Double
            val powerKmPerPct: Double
            val sourceLabel: String

            if (!isLearned) {
                ecoKmPerPct = IqubeBleSpec.DEFAULT_ECO_KM_PER_PCT     // 1.00 km / 1%
                powerKmPerPct = IqubeBleSpec.DEFAULT_POWER_KM_PER_PCT // 0.75 km / 1%
                sourceLabel = "Default Baseline (Eco 1.00 km/% • Power 0.75 km/%)"
            } else {
                val rawEcoRate = (totalEcoEquivKm / totalBatteryPctUsed.toDouble()).coerceIn(0.60, 1.50)
                ecoKmPerPct = (rawEcoRate * 100.0).roundToInt() / 100.0
                powerKmPerPct = ((ecoKmPerPct * IqubeBleSpec.POWER_TO_ECO_RATIO) * 100.0).roundToInt() / 100.0
                val tripCount = qualifyingTrips.size + (if (currEcoEquivKm > 0.0) 1 else 0)
                sourceLabel = String.format(
                    Locale.US,
                    "Rolling %d-Trip + Live Efficiency (%.2f km / %d%% used)",
                    tripCount,
                    totalRawKm,
                    totalBatteryPctUsed
                )
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
                contributingTripsCount = qualifyingTrips.size + (if (currEcoEquivKm > 0.0) 1 else 0),
                isUsingLearnedEfficiency = isLearned,
                calculationSourceLabel = sourceLabel
            )
        }

        /**
         * Pure, unit-testable 3-Tier Charging Time Predictor:
         * - Tier 1: Default Baseline (`123.75s / 1%` = `2 hr 45 min` for `0–80%`, plus `115.7s / 1%` = `+31.1%/hr` verified capture reference)
         * - Tier 2: Historical Charging Sessions average (`savedChargingSessions`)
         * - Tier 3: Live Connected `+1%` Step Timing (`liveStepDurationsSec` measured during the active session)
         */
        fun computeChargingMonitorState(
            isCharging: Boolean,
            currentBatteryPct: Int?,
            clusterRangeKm: Double?,
            sessionStartBatteryPct: Int?,
            liveStepDurationsSec: List<Double>,
            savedChargingSessions: List<SavedChargingSession>
        ): ChargingMonitorState {
            // Tier 1 & Tier 2: Compute Baseline (Default 123.75s/% for 2h45m 0-80%, or Historical Sessions average)
            val qualifyingHistory = savedChargingSessions.filter { it.pctGained >= 1 && it.durationSeconds >= 60L }
            val totalHistSec = qualifyingHistory.sumOf { it.durationSeconds }.toDouble()
            val totalHistPct = qualifyingHistory.sumOf { it.pctGained }

            val baselineSecPerPct = if (totalHistPct >= 1 && totalHistSec > 0.0) {
                (totalHistSec / totalHistPct.toDouble()).coerceIn(60.0, 240.0)
            } else {
                IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT // 123.75s per 1% (2h 45m for 0-80%)
            }

            // Tier 3: Live +1% Step Timing in current charging session
            val validLiveSteps = liveStepDurationsSec.filter { it in 50.0..260.0 }
            val isLiveStepTimed = validLiveSteps.isNotEmpty()
            val liveMeasuredSecPerPct = if (isLiveStepTimed) {
                (validLiveSteps.average() * 10.0).roundToInt() / 10.0
            } else null

            val activeSecPerPct = liveMeasuredSecPerPct ?: baselineSecPerPct
            val pctPerHour = ((3600.0 / activeSecPerPct) * 10.0).roundToInt() / 10.0

            val taperSecPerPct = IqubeBleSpec.DEFAULT_80_TO_100_SEC_PER_PCT // 180.0s per 1% (80->100%)

            val remSecTo80 = currentBatteryPct?.let { soc ->
                (max(0, 80 - soc) * activeSecPerPct).roundToLong()
            }
            val remSecTo100 = currentBatteryPct?.let { soc ->
                val pctTo80 = max(0, 80 - soc)
                val pct80To100 = if (soc < 80) 20 else max(0, 100 - soc)
                (pctTo80 * activeSecPerPct + pct80To100 * taperSecPerPct).roundToLong()
            }

            val baselineRemSecTo80 = currentBatteryPct?.let { soc ->
                (max(0, 80 - soc) * baselineSecPerPct).roundToLong()
            }
            val baselineRemSecTo100 = currentBatteryPct?.let { soc ->
                val pctTo80 = max(0, 80 - soc)
                val pct80To100 = if (soc < 80) 20 else max(0, 100 - soc)
                (pctTo80 * baselineSecPerPct + pct80To100 * taperSecPerPct).roundToLong()
            }

            val pctGained = if (sessionStartBatteryPct != null && currentBatteryPct != null) {
                max(0, currentBatteryPct - sessionStartBatteryPct)
            } else 0

            val modeLabel = when {
                isLiveStepTimed -> String.format(
                    Locale.US,
                    "Live 1%% Step Timing (%.1fs / +1%% • +%.1f%%/hr)",
                    activeSecPerPct,
                    pctPerHour
                )
                totalHistPct >= 1 -> String.format(
                    Locale.US,
                    "Historical Session Baseline (%.1fs / +1%% from %d session(s))",
                    baselineSecPerPct,
                    qualifyingHistory.size
                )
                else -> "Default Baseline (2h 45m 0–80% = 123.8s/% • 80–100% = 180s/%)"
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
                completedStepsCount = validLiveSteps.size
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
                        disconnectReason = obj.optString("disconnectReason", "Disconnected")
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
        }
    }

    private fun loadChargingSessionsFromStorage(): List<SavedChargingSession> {
        val raw = prefs?.getString(KEY_SAVED_CHARGING_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<SavedChargingSession>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SavedChargingSession(
                        id = obj.optString("id", "chg_$i"),
                        startTimeMs = obj.optLong("startTimeMs", 0L),
                        endTimeMs = obj.optLong("endTimeMs", 0L),
                        startPct = obj.optInt("startPct", 38),
                        endPct = obj.optInt("endPct", 42),
                        pctGained = obj.optInt("pctGained", 4),
                        durationSeconds = obj.optLong("durationSeconds", 463L),
                        avgSecondsPerPct = obj.optDouble("avgSecondsPerPct", 115.7)
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
                vehicleId = obj.optString("vehicleId", IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT)
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
        }
        prefs?.edit()?.putString(KEY_LAST_SEEN_STATE_JSON, obj.toString())?.apply()
    }
}
