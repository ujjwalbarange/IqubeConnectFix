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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
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
import com.example.model.RideMode
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedTripsHistoryScreen(
    savedTrips: List<SavedTripRecord>,
    activeTripSession: ActiveTripSession?,
    telemetry: TelemetryState,
    trueRange: TrueRangeCalculation,
    onLogSampleTripClick: () -> Unit,
    onDeleteTripClick: (String) -> Unit,
    onClearAllTripsClick: () -> Unit,
    onExportCsv: () -> String,
    onExportJson: () -> String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var exportedPreview by remember { mutableStateOf<String?>(null) }
    var exportedFormatLabel by remember { mutableStateOf("") }

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
                        Text("POWER RATE (0.75x)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
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
                        onDeleteClick = { onDeleteTripClick(trip.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedTripItemCard(
    trip: SavedTripRecord,
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

        // Go to Start Coordinate & Go to End Coordinate Google Maps Links
        val context = LocalContext.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { openCoordinatesOnMaps(context, trip.startLatitude, trip.startLongitude) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("trip_start_coord_button_${trip.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Go to Start Coordinate",
                    tint = ElectricCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.US, "Start (%.4f, %.4f)", trip.startLatitude, trip.startLongitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
            OutlinedButton(
                onClick = { openCoordinatesOnMaps(context, trip.endLatitude, trip.endLongitude) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("trip_end_coord_button_${trip.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Go to End Coordinate",
                    tint = NeonMint,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.US, "End (%.4f, %.4f)", trip.endLatitude, trip.endLongitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )
            }
        }
    }
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
