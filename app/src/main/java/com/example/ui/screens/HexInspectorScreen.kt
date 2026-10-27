package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ByteOffsetMapping
import com.example.model.IqubeBleSpec
import com.example.model.TelemetryState
import com.example.model.toAsciiPreview
import com.example.model.toHexString
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EnergeticCoral
import com.example.ui.theme.NeonMint
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HexInspectorScreen(
    telemetry: TelemetryState,
    byteOffsetMapping: ByteOffsetMapping,
    fragmentedSliceTestEnabled: Boolean,
    onUpdateMapping: (ByteOffsetMapping) -> Unit,
    onSetFragmentedSliceTest: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

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
            // 1. Exact GATT Service & Characteristic UUID Reference Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "VERIFIED GATT SERVICE & CHARACTERISTIC UUIDs (btsnoop_hci.log)",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonMint
                )
                Text(
                    text = "Advertised Name: ${IqubeBleSpec.ADVERTISED_BLE_NAME}  |  Vehicle ID (0x7D): ${telemetry.vehicleIdString ?: IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
                Text(
                    text = "Primary Service (0x001C..0xFFFF): ${IqubeBleSpec.SERVICE_UUID_SMARTXONNECT} (\"${IqubeBleSpec.SERVICE_UUID_ASCII}\")",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = "Notify (0x001E): 0x5354 (\"ST\")  •  Write (0x0022): 0x5352 (\"SR\")  •  Trailer: Byte 19 = 0xFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberWarning
                )
            }

            // 2. Live 20-Byte Hex & ASCII Stream Monitor (Handle 0x001E / 0x5354)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LIVE 20-BYTE UNFILTERED STREAM (0x5354 / HANDLE 0x001E)",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Malformed / Resynced Chunks: ${telemetry.malformedPacketCount} • Every frame ends with Byte 19 = 0xFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "8B+12B Slice Test",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Switch(
                            checked = fragmentedSliceTestEnabled,
                            onCheckedChange = onSetFragmentedSliceTest,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }

                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x10 • Speed (B2) + uint24 BE Odometer (B3..5 = ${telemetry.odometerKm?.let { String.format(Locale.US, "%,.1f km", it) } ?: "35,925.2 km"}, 4.7 Hz)",
                    packetCount = telemetry.packetCount0x10,
                    frame = telemetry.latestFrame0x10,
                    highlightByteIndex = byteOffsetMapping.odometerStartOffsetIn0x10,
                    accentColor = ElectricCyan
                )
                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x13 • SoC (B2=${telemetry.batteryPercent ?: "--"}%) + uint16 BE Range (B3..4=${telemetry.clusterRangeKm ?: "--"} km) + State Flag (B5=0x${telemetry.stateFlag0x13Byte5?.let { String.format("%02X", it) } ?: "00"})",
                    packetCount = telemetry.packetCount0x13,
                    frame = telemetry.latestFrame0x13,
                    highlightByteIndex = byteOffsetMapping.rangeStartOffsetIn0x13,
                    accentColor = NeonMint
                )
                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x11 • Trip A (B2..4=${telemetry.tripAKm ?: "--"} km) + Chg (B5=0x${telemetry.chargingFlag0x11Byte5?.let { String.format("%02X", it) } ?: "00"}) + SoC Mirror (B6=${telemetry.socMirror0x11 ?: "--"}%) + Trip B (B7..9=${telemetry.tripBKm ?: "--"} km)",
                    packetCount = telemetry.packetCount0x11,
                    frame = telemetry.latestFrame0x11,
                    highlightByteIndex = byteOffsetMapping.chargingFlagOffsetIn0x11,
                    accentColor = EnergeticCoral
                )
                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x7D • ASCII Vehicle Model & ID String (B2..18 = \"${telemetry.vehicleIdString ?: IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT}\", 3.1 Hz)",
                    packetCount = telemetry.packetCount0x7D,
                    frame = telemetry.latestFrame0x7D,
                    highlightByteIndex = 2,
                    accentColor = AmberWarning
                )
                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x6B • Handlebar Media Control Event (B2 = ${telemetry.lastMediaCommand?.let { "0x${String.format("%02X", it.code)} ${it.label}" } ?: "None"})",
                    packetCount = telemetry.packetCount0x6B,
                    frame = telemetry.latestFrame0x6B,
                    highlightByteIndex = 2,
                    accentColor = ElectricCyan
                )
            }

            // 3. Interactive Byte-Offset & Unfiltered Layout Mapper
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "UNFILTERED 20-BYTE PACKET OFFSET MAPPER",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonMint
                )

                ByteIndexSelectorRow(
                    label = "0x10 Total Odometer uint24 BE Start Byte (Default Byte 3: 05 7B 54 = 35,925.2 km)",
                    selectedIndex = byteOffsetMapping.odometerStartOffsetIn0x10,
                    validIndices = 3..15,
                    onSelect = { onUpdateMapping(byteOffsetMapping.copy(odometerStartOffsetIn0x10 = it)) }
                )

                ByteIndexSelectorRow(
                    label = "0x13 Cluster Projected Range uint16 BE Start Byte (Default Byte 3: 01 90 = 40.0 km)",
                    selectedIndex = byteOffsetMapping.rangeStartOffsetIn0x13,
                    validIndices = 3..16,
                    onSelect = { onUpdateMapping(byteOffsetMapping.copy(rangeStartOffsetIn0x13 = it)) }
                )

                ByteIndexSelectorRow(
                    label = "0x11 Hardware Trip A uint24 BE Start Byte (Default Byte 2: 00 00 03 = 0.3 km)",
                    selectedIndex = byteOffsetMapping.tripAStartOffsetIn0x11,
                    validIndices = 2..14,
                    onSelect = { onUpdateMapping(byteOffsetMapping.copy(tripAStartOffsetIn0x11 = it)) }
                )

                ByteIndexSelectorRow(
                    label = "0x11 Hardware Trip B uint24 BE Start Byte (Default Byte 7: 00 00 0A = 1.0 km)",
                    selectedIndex = byteOffsetMapping.tripBStartOffsetIn0x11,
                    validIndices = 5..15,
                    onSelect = { onUpdateMapping(byteOffsetMapping.copy(tripBStartOffsetIn0x11 = it)) }
                )
            }
        }
    }
}

@Composable
private fun HexFrameInspectorRow(
    opcodeTitle: String,
    packetCount: Int,
    frame: ByteArray?,
    highlightByteIndex: Int,
    accentColor: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SlateCardElevated)
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = opcodeTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$packetCount pkts",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (frame == null) {
            Text(
                text = "-- NO PACKET RECEIVED YET (AWAITING 0x5354 STREAM) --",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        } else {
            val safeIdx = highlightByteIndex.coerceIn(0, frame.size - 1)
            Text(
                text = "HEX: ${frame.toHexString()}",
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
            Text(
                text = "ASCII: ${frame.toAsciiPreview()}   |   Byte[$safeIdx] = 0x${String.format("%02X", frame[safeIdx].toInt() and 0xFF)}   |   Byte[19] = 0x${String.format("%02X", frame[19].toInt() and 0xFF)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByteIndexSelectorRow(
    label: String,
    selectedIndex: Int,
    validIndices: IntRange,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (idx in validIndices) {
                val selected = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) ElectricCyan.copy(alpha = 0.22f) else SlateCardElevated)
                        .border(
                            1.dp,
                            if (selected) ElectricCyan else SlateBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelect(idx) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                        .testTag("byte_offset_${label.take(4)}_$idx")
                ) {
                    Text(
                        text = "B$idx",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) ElectricCyan else TextSecondary
                    )
                }
            }
        }
    }
}
