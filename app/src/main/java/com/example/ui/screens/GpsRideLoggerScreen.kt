package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.GpsBleFusionSample
import com.example.model.LastParkedLocation
import com.example.model.TelemetryState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
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
fun GpsRideLoggerScreen(
    telemetry: TelemetryState,
    currentGpsSpeedKmh: Double?,
    detectedLocalityName: String,
    lastParkedLocation: LastParkedLocation?,
    gpsFusionSamples: List<GpsBleFusionSample>,
    autoReconnectEnabled: Boolean,
    onSetAutoReconnect: (Boolean) -> Unit,
    onStartGpsTracking: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var gpsPermissionGranted by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        gpsPermissionGranted = granted
        if (granted) {
            onStartGpsTracking()
        }
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
            // 1. Automatic "Last Parked Location" Pin Card (Saved on BLE Disconnect)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Last Parked Pin",
                            tint = NeonMint
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AUTOMATIC LAST PARKED PIN (ON BLE DISCONNECT)",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonMint
                        )
                    }
                    Text(
                        text = "Locality: $detectedLocalityName",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                }

                if (lastParkedLocation == null) {
                    Text(
                        text = "No parked event recorded in this session yet. When an active BLE connection or HCI Log Replay disconnects, the scooter's reverse-geocoded locality, GPS coordinates, Battery SoC %, and True Range are automatically latched here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    val timeFmt = remember(lastParkedLocation.timestampMs) {
                        SimpleDateFormat("HH:mm:ss • dd MMM yyyy", Locale.US)
                            .format(Date(lastParkedLocation.timestampMs))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateCardElevated)
                            .border(1.dp, NeonMint.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${lastParkedLocation.locationName}  •  LAT ${String.format(Locale.US, "%.5f", lastParkedLocation.latitude)}  LON ${String.format(Locale.US, "%.5f", lastParkedLocation.longitude)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Parked at $timeFmt (±${lastParkedLocation.accuracyMeters.toInt()}m GPS accuracy)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "SoC: ${lastParkedLocation.batteryPercentAtPark?.let { "$it%" } ?: "--%"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = ElectricCyan
                            )
                            Text(
                                text = "True Range: ${lastParkedLocation.rangeKmAtPark?.let { String.format(Locale.US, "%.1f km", it) } ?: "-- km"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = NeonMint
                            )
                            Text(
                                text = "ODO: ${lastParkedLocation.odometerKmAtPark?.let { String.format(Locale.US, "%,.1f km", it) } ?: "-- km"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = AmberWarning
                            )
                        }
                    }
                }
            }

            // 2. Wheel Speed (0x5A 0x10) vs. Phone GPS Speed Calibration Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "WHEEL SPEED (0x5A 0x10) VS. GPS CALIBRATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateCardElevated)
                            .padding(12.dp)
                    ) {
                        Text("BLE WHEEL (0x10)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = telemetry.speedKmh?.let { "$it km/h" } ?: "-- km/h",
                            style = MaterialTheme.typography.headlineMedium,
                            color = ElectricCyan
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateCardElevated)
                            .padding(12.dp)
                    ) {
                        Text("PHONE GPS SPEED", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            text = currentGpsSpeedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "--.- km/h",
                            style = MaterialTheme.typography.headlineMedium,
                            color = NeonMint
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateCardElevated)
                            .padding(12.dp)
                    ) {
                        Text("CLUSTER BIAS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        val delta = if (telemetry.speedKmh != null && currentGpsSpeedKmh != null) {
                            telemetry.speedKmh - currentGpsSpeedKmh
                        } else null
                        Text(
                            text = delta?.let { String.format(Locale.US, "%+.1f km/h", it) } ?: "--.- km/h",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AmberWarning
                        )
                    }
                }

                Button(
                    onClick = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("enable_gps_fusion_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Enable GPS Fusion"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (gpsPermissionGranted) "Refresh GPS + Nominatim Locality Listener" else "Enable Phone GPS + Nominatim Reverse Geocoding",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 3. Connection Lifecycle & Auto-Reconnect Configuration
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "EXPONENTIAL BACKOFF AUTO-RECONNECT",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Matches Android HCI_LE_Extended_Create_Connection 30s cancel guard & GATT 133 clean close.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = autoReconnectEnabled,
                        onCheckedChange = onSetAutoReconnect
                    )
                }
            }

            // 4. Recent GPS + BLE Fusion Telemetry Log
            if (gpsFusionSamples.isNotEmpty()) {
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
                        text = "FUSED GPS + BLE RIDE SAMPLES",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    gpsFusionSamples.take(8).forEach { sample ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SlateCardElevated)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "BLE: ${sample.wheelSpeedKmh ?: "--"} km/h | GPS: ${sample.gpsSpeedKmh} km/h",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "${sample.localityName} • SoC: ${sample.batteryPercent ?: "--"}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }
        }
    }
}
