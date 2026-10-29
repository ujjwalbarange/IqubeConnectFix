package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ActiveTripSession
import com.example.model.ChargingMonitorState
import com.example.model.RideMode
import com.example.model.SavedChargingSession
import com.example.model.SavedTripRecord
import com.example.model.TelemetryState
import com.example.model.TrueRangeCalculation
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
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedTripsHistoryScreen(
    savedTrips: List<SavedTripRecord>,
    savedChargingSessions: List<SavedChargingSession> = emptyList(),
    chargingMonitor: ChargingMonitorState = ChargingMonitorState(),
    activeTripSession: ActiveTripSession?,
    telemetry: TelemetryState,
    trueRange: TrueRangeCalculation,
    onLogSampleTripClick: () -> Unit,
    onDeleteTripClick: (String) -> Unit,
    onClearAllTripsClick: () -> Unit,
    onLogSampleChargingSessionClick: () -> Unit = {},
    onDeleteChargingSessionClick: (String) -> Unit = {},
    onClearAllChargingSessionsClick: () -> Unit = {},
    onExportCsv: () -> String,
    onExportJson: () -> String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var exportedPreview by remember { mutableStateOf<String?>(null) }
    var exportedFormatLabel by remember { mutableStateOf("") }
    var selectedTripForDetails by remember { mutableStateOf<SavedTripRecord?>(null) }
    var selectedChargeForDetails by remember { mutableStateOf<SavedChargingSession?>(null) }

    if (selectedTripForDetails != null) {
        TripDetailsDialog(
            trip = selectedTripForDetails!!,
            onDismiss = { selectedTripForDetails = null },
            onDeleteClick = {
                onDeleteTripClick(selectedTripForDetails!!.id)
                selectedTripForDetails = null
            }
        )
    }

    if (selectedChargeForDetails != null) {
        ChargingSessionDetailsDialog(
            session = selectedChargeForDetails!!,
            chargingMonitor = chargingMonitor,
            onDismiss = { selectedChargeForDetails = null },
            onDeleteClick = {
                onDeleteChargingSessionClick(selectedChargeForDetails!!.id)
                selectedChargeForDetails = null
            }
        )
    }

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
            // 1. True Range Efficiency Learning Summary Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "TRUE RANGE EFFICIENCY ENGINE (ECO VS. POWER)",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonMint
                )
                Text(
                    text = trueRange.calculationSourceLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateCardElevated)
                            .padding(10.dp)
                    ) {
                        Text("ECO RATE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = String.format(Locale.US, "%.2f km / 1%%", trueRange.ecoKmPerPct),
                            style = MaterialTheme.typography.titleLarge,
                            color = NeonMint
                        )
                        Text(
                            text = trueRange.trueEcoRangeKm?.let { "Range: $it km" } ?: "100% = ${(trueRange.ecoKmPerPct * 100).toInt()} km",
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
                        Text("POWER RATE (LEARNED)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = String.format(Locale.US, "%.2f km / 1%%", trueRange.powerKmPerPct),
                            style = MaterialTheme.typography.titleLarge,
                            color = EnergeticCoral
                        )
                        Text(
                            text = trueRange.truePowerRangeKm?.let { "Range: $it km" } ?: "100% = ${(trueRange.powerKmPerPct * 100).toInt()} km",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // 1B. True Charging Time & Interval Analysis Engine Card (Like Trips)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, AmberWarning.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ TRUE CHARGING TIME & INTERVAL LEARNING ENGINE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AmberWarning
                    )
                    if (savedChargingSessions.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearAllChargingSessionsClick,
                            modifier = Modifier.testTag("clear_all_charging_sessions_button")
                        ) {
                            Text("Clear Charges", color = EnergeticCoral, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Text(
                    text = chargingMonitor.predictionModeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val true0To80Hrs = chargingMonitor.true0To80TotalSeconds / 3600
                    val true0To80Mins = (chargingMonitor.true0To80TotalSeconds % 3600) / 60
                    val true0To100Hrs = chargingMonitor.true0To100TotalSeconds / 3600
                    val true0To100Mins = (chargingMonitor.true0To100TotalSeconds % 3600) / 60

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateCardElevated)
                            .padding(10.dp)
                    ) {
                        Text("BULK CC (0→80%)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = "${true0To80Hrs}h ${true0To80Mins}m",
                            style = MaterialTheme.typography.titleLarge,
                            color = NeonMint
                        )
                        Text(
                            text = "${chargingMonitor.learned0To80SecPerPct.roundToInt()}s / 1% SoC",
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
                        Text("FULL CC+CV (0→100%)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = "${true0To100Hrs}h ${true0To100Mins}m",
                            style = MaterialTheme.typography.titleLarge,
                            color = AmberWarning
                        )
                        Text(
                            text = "80–100% Taper: ${chargingMonitor.learned80To100SecPerPct.roundToInt()}s/1%",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Text(
                    text = chargingMonitor.chargingInsightText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Button(
                    onClick = onLogSampleChargingSessionClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = SlateSurface),
                    modifier = Modifier.testTag("log_sample_charging_session_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Log Sample Charge", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Log Sample Charge Interval (38% → 82%)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }

                if (savedChargingSessions.isNotEmpty()) {
                    Text(
                        text = "RECORDED CHARGING INTERVALS (${savedChargingSessions.size}) — TAP FOR INSIGHTS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    savedChargingSessions.forEach { session ->
                        SavedChargingSessionItemCard(
                            session = session,
                            onSessionClick = { selectedChargeForDetails = session },
                            onDeleteClick = { onDeleteChargingSessionClick(session.id) }
                        )
                    }
                }
            }

            // 2. Currently Active Connect-to-Disconnect Trip Session (if connected)
            if (activeTripSession != null) {
                val integratedKm = telemetry.integratedDistanceMeters / 1000.0
                val odoDeltaKm = if (activeTripSession.startOdometerKm != null && telemetry.odometerKm != null) {
                    max(0.0, telemetry.odometerKm - activeTripSession.startOdometerKm)
                } else 0.0
                val liveDistKm = max(integratedKm, odoDeltaKm)
                val usedPct = if (activeTripSession.startBatteryPct != null && telemetry.batteryPercent != null) {
                    max(0, activeTripSession.startBatteryPct - telemetry.batteryPercent)
                } else 0

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SlateCardElevated)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "● RECORDING ACTIVE TRIP SESSION (CONNECT → DISCONNECT)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    Text(
                        text = "Start: ${activeTripSession.startLocationName} (${String.format(Locale.US, "%.4f, %.4f", activeTripSession.startLatitude, activeTripSession.startLongitude)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Distance: %.2f km  •  SoC: %s%% → %s%% (-%d%%)  •  ODO: %s km",
                            liveDistKm,
                            activeTripSession.startBatteryPct?.toString() ?: "--",
                            telemetry.batteryPercent?.toString() ?: "--",
                            usedPct,
                            telemetry.odometerKm?.let { String.format(Locale.US, "%,.1f", it) } ?: "--"
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonMint
                    )
                }
            }

            // 3. Controls: Log Sample Trip, Export CSV, Export JSON, Clear All
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SAVED TRIPS HISTORY (${savedTrips.size} RECORDED)",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                    if (savedTrips.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearAllTripsClick,
                            modifier = Modifier.testTag("clear_all_trips_button")
                        ) {
                            Text("Clear All", color = EnergeticCoral, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onLogSampleTripClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMint, contentColor = SlateSurface),
                        modifier = Modifier.testTag("log_sample_trip_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Log Sample Trip", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Log Test Trip (4.6 km / 5%)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val csv = onExportCsv()
                            exportedFormatLabel = "CSV Export (Copied to Clipboard)"
                            exportedPreview = csv
                            clipboardManager.setText(AnnotatedString(csv))
                            shareTextIntent(context, "iQube_Trips.csv", csv)
                        },
                        modifier = Modifier.testTag("export_csv_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export CSV", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export CSV", color = ElectricCyan, style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            val json = onExportJson()
                            exportedFormatLabel = "JSON Export (Copied to Clipboard)"
                            exportedPreview = json
                            clipboardManager.setText(AnnotatedString(json))
                            shareTextIntent(context, "iQube_Trips.json", json)
                        },
                        modifier = Modifier.testTag("export_json_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export JSON", tint = AmberWarning, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export JSON", color = AmberWarning, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (exportedPreview != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateCardElevated)
                            .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = exportedFormatLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonMint
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = exportedPreview?.take(600).orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                    }
                }
            }

            // 4. Saved Trips List
            if (savedTrips.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SlateSurface)
                        .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No Saved Connect-to-Disconnect Trips Yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Trips are automatically recorded from the moment BLE connects (or HCI Replay starts) and saved here when BLE disconnects. Tap 'Log Test Trip' above to test the True Range learning engine immediately.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                savedTrips.forEach { trip ->
                    SavedTripItemCard(
                        trip = trip,
                        onTripClick = { selectedTripForDetails = trip },
                        onDeleteClick = { onDeleteTripClick(trip.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedChargingSessionItemCard(
    session: SavedChargingSession,
    onSessionClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val dateFmt = remember(session.startTimeMs) {
        SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.US).format(Date(session.startTimeMs))
    }
    val durationMin = session.durationSeconds / 60

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SlateCardElevated)
            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            .clickable { onSessionClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "⚡ ${session.startPct}% → ${session.endPct}% (+${session.pctGained}% SoC)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = NeonMint
                )
                Text(
                    text = "$dateFmt • ${durationMin}m • ${session.sessionSource}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Charging Session",
                    tint = EnergeticCoral
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Pace: ${session.avgSecondsPerPct.roundToInt()}s / 1%",
                style = MaterialTheme.typography.labelSmall,
                color = AmberWarning
            )
            Text(
                text = String.format(Locale.US, "+%.2f kWh", session.estimatedKwhAdded),
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
            Text(
                text = String.format(Locale.US, "+%.1f km Range", session.trueEcoRangeAddedKm),
                style = MaterialTheme.typography.labelSmall,
                color = NeonMint
            )
        }
    }
}

@Composable
private fun ChargingSessionDetailsDialog(
    session: SavedChargingSession,
    chargingMonitor: ChargingMonitorState,
    onDismiss: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val dateFmt = remember(session.startTimeMs) {
        SimpleDateFormat("dd MMMM yyyy, hh:mm:ss a", Locale.US).format(Date(session.startTimeMs))
    }
    val durationMin = session.durationSeconds / 60
    val durationSecRem = session.durationSeconds % 60
    val projected0To80Min = (session.cc0To80SecondsPerPct * 80.0 / 60.0).roundToInt()
    val projected0To100Min = ((session.cc0To80SecondsPerPct * 80.0 + session.cv80To100SecondsPerPct * 20.0) / 60.0).roundToInt()
    val calDelta = session.calibrationDeltaPct ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Charging Interval & True Time",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateCardElevated)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("INTERVAL SUMMARY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AmberWarning)
                    Text(
                        text = "⚡ SoC Gained: ${session.startPct}% → ${session.endPct}% (+${session.pctGained}%)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeonMint
                    )
                    Text("📅 Started: $dateFmt", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text("⏱ Duration: ${durationMin}m ${durationSecRem}s • Avg Pace: ${session.avgSecondsPerPct.roundToInt()}s / 1%", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text("🔌 Source: ${session.sessionSource}", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateCardElevated)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("CC BULK (0–80%) VS CV TAPER (80–100%) ANALYSIS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ElectricCyan)
                    Text(
                        text = "• Bulk CC (0–80%): +${session.cc0To80PctGained}% at ${session.cc0To80SecondsPerPct.roundToInt()}s / 1%",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonMint
                    )
                    Text(
                        text = "• Taper CV (80–100%): +${session.cv80To100PctGained}% at ${session.cv80To100SecondsPerPct.roundToInt()}s / 1%",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberWarning
                    )
                    Text(
                        text = "• Projected True 0→80% Time: ${projected0To80Min / 60}h ${projected0To80Min % 60}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "• Projected True 0→100% Time: ${projected0To100Min / 60}h ${projected0To100Min % 60}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateCardElevated)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("ENERGY & TRUE RANGE ADDED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = NeonMint)
                    Text(
                        text = String.format(Locale.US, "🔋 Pack Energy Added: +%.2f kWh (3.4 kWh pack)", session.estimatedKwhAdded),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = String.format(Locale.US, "🛣 True Eco Range Added: +%.1f km", session.trueEcoRangeAddedKm),
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonMint
                    )
                    if (session.wasOfflinePredicted) {
                        Text(
                            text = "⚠️ Included Offline Predicted Charging (Calibration Delta: ${if (calDelta >= 0) "+" else ""}$calDelta%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning
                        )
                    }
                    Text(
                        text = chargingMonitor.chargingInsightText,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
            ) {
                Text("Close", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDeleteClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EnergeticCoral)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete Interval")
            }
        },
        containerColor = SlateSurface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun SavedTripItemCard(
    trip: SavedTripRecord,
    onTripClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val dateFmt = remember(trip.startTimeMs) {
        SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.US).format(Date(trip.startTimeMs))
    }
    val endFmt = remember(trip.endTimeMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(trip.endTimeMs))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
            .clickable { onTripClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${trip.startLocationName} → ${trip.endLocationName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "$dateFmt to $endFmt (${trip.durationSeconds}s) • ${trip.disconnectReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Saved Trip",
                    tint = EnergeticCoral
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(Locale.US, "DIST: %.2f km", trip.distanceKm),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan
            )
            Text(
                text = "BAT: ${trip.startBatteryPct}% → ${trip.endBatteryPct}% (-${trip.batteryUsedPct}%)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = NeonMint
            )
            Text(
                text = String.format(Locale.US, "EFF: %.2f km/%%", trip.efficiencyKmPerPct),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = AmberWarning
            )
        }

        // Eco vs Power Mode Breakdown row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SlateCardElevated)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val ecoEff = if (trip.ecoBatteryUsedPct > 0) String.format(Locale.US, "%.2f km/%%", trip.ecoDistanceKm / trip.ecoBatteryUsedPct.toDouble()) else "1.00 km/%"
            val pwrEff = if (trip.powerBatteryUsedPct > 0) String.format(Locale.US, "%.2f km/%%", trip.powerDistanceKm / trip.powerBatteryUsedPct.toDouble()) else "0.75 km/%"

            Text(
                text = "🌱 Eco: ${String.format(Locale.US, "%.1f", trip.ecoDistanceKm)} km (-${trip.ecoBatteryUsedPct}%) • $ecoEff",
                style = MaterialTheme.typography.labelSmall,
                color = NeonMint
            )
            Text(
                text = "⚡ Pwr: ${String.format(Locale.US, "%.1f", trip.powerDistanceKm)} km (-${trip.powerBatteryUsedPct}%) • $pwrEff",
                style = MaterialTheme.typography.labelSmall,
                color = AmberWarning
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SlateCardElevated)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(Locale.US, "ODO: %,.1f → %,.1f km", trip.startOdometerKm, trip.endOdometerKm),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "PEAK ${trip.peakSpeedKmh} km/h • AVG ${trip.avgSpeedKmh} km/h • ${trip.rideMode.name}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ℹ️ Tap card for full details & 100% True Range",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
            Text(
                text = "100% True Range: ${String.format(Locale.US, "%.1f km", trip.efficiencyKmPerPct * 100.0)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = NeonMint
            )
        }
    }
}

@Composable
private fun TripDetailsDialog(
    trip: SavedTripRecord,
    onDismiss: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    val dateFmt = remember(trip.startTimeMs) {
        SimpleDateFormat("dd MMMM yyyy, hh:mm:ss a", Locale.US).format(Date(trip.startTimeMs))
    }
    val endFmt = remember(trip.endTimeMs) {
        SimpleDateFormat("hh:mm:ss a", Locale.US).format(Date(trip.endTimeMs))
    }
    val durationMin = trip.durationSeconds / 60
    val durationSecRem = trip.durationSeconds % 60
    val projected100PctTrueRange = String.format(Locale.US, "%.1f", trip.efficiencyKmPerPct * 100.0)

    val ecoEff = if (trip.ecoBatteryUsedPct > 0 && trip.ecoDistanceKm > 0.0) {
        trip.ecoDistanceKm / trip.ecoBatteryUsedPct.toDouble()
    } else 1.00
    val projectedEco100Pct = String.format(Locale.US, "%.1f", ecoEff * 100.0)

    val powerEff = if (trip.powerBatteryUsedPct > 0 && trip.powerDistanceKm > 0.0) {
        trip.powerDistanceKm / trip.powerBatteryUsedPct.toDouble()
    } else 0.75
    val projectedPower100Pct = String.format(Locale.US, "%.1f", powerEff * 100.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Trip Details & True Range",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Route Banner
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateCardElevated)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ROUTE & DURATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    Text(
                        text = "📍 Started: ${trip.startLocationName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "🏁 Stopped: ${trip.endLocationName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "📅 $dateFmt to $endFmt",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "⏱ Duration: ${durationMin}m ${durationSecRem}s • Reason: ${trip.disconnectReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { openCoordinatesOnMaps(context, trip.startLatitude, trip.startLongitude) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start Point", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                        }
                        OutlinedButton(
                            onClick = { openCoordinatesOnMaps(context, trip.endLatitude, trip.endLongitude) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = NeonMint, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop Point", style = MaterialTheme.typography.labelSmall, color = NeonMint)
                        }
                    }
                }

                // Overall Ride Efficiency & 100% Projected True Range
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateCardElevated)
                        .border(1.dp, NeonMint.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "RIDE EFFICIENCY & PROJECTED TRUE RANGE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = NeonMint
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = String.format(Locale.US, "%.2f km", trip.distanceKm),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan
                            )
                            Text("Distance", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Column {
                            Text(
                                text = "-${trip.batteryUsedPct}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = EnergeticCoral
                            )
                            Text("${trip.startBatteryPct}% → ${trip.endBatteryPct}%", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Column {
                            Text(
                                text = String.format(Locale.US, "%.2f km/%%", trip.efficiencyKmPerPct),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = AmberWarning
                            )
                            Text("Efficiency", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "🎯 Projected True Range at 100% SoC: $projected100PctTrueRange km",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeonMint
                    )
                }

                // Mode Breakdown: Eco Mode vs Power Mode
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateCardElevated)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "LEARNED MODE BREAKDOWN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    // Eco Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🌱 Eco Mode:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = NeonMint)
                        Text(
                            "${String.format(Locale.US, "%.2f", trip.ecoDistanceKm)} km • -${trip.ecoBatteryUsedPct}% SoC",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "• Eco Efficiency: ${String.format(Locale.US, "%.2f", ecoEff)} km/% → 100% Range: $projectedEco100Pct km",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // Power Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("⚡ Power Mode:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AmberWarning)
                        Text(
                            "${String.format(Locale.US, "%.2f", trip.powerDistanceKm)} km • -${trip.powerBatteryUsedPct}% SoC",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "• Power Efficiency: ${String.format(Locale.US, "%.2f", powerEff)} km/% → 100% Range: $projectedPower100Pct km",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                // Speed & Odometer Telemetry
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateCardElevated)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "TELEMETRY & ODOMETER",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    Text(
                        text = String.format(Locale.US, "ODO: %,.1f km → %,.1f km", trip.startOdometerKm, trip.endOdometerKm),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Peak Speed: ${trip.peakSpeedKmh} km/h • Avg Speed: ${trip.avgSpeedKmh} km/h",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Dominant Ride Mode: ${trip.rideMode.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
            ) {
                Text("Close", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDeleteClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EnergeticCoral)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete Trip")
            }
        },
        containerColor = SlateSurface,
        shape = RoundedCornerShape(20.dp)
    )
}

private fun openCoordinatesOnMaps(context: android.content.Context, lat: Double, lon: Double) {
    try {
        val mapsUri = Uri.parse(
            String.format(Locale.US, "https://www.google.com/maps?q=%.6f,%.6f", lat, lon)
        )
        val intent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun shareTextIntent(context: android.content.Context, title: String, content: String) {
    try {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        val chooser = Intent.createChooser(sendIntent, "Export $title").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (_: Exception) {
    }
}
