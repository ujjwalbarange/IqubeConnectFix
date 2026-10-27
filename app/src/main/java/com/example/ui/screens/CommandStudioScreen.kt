package com.example.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.model.IqubeBleSpec
import com.example.model.QueuedWriteCommand
import com.example.model.TripSubcommandConfig
import com.example.model.WriteCommandStatus
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
import java.nio.charset.StandardCharsets
import java.util.Locale

@Composable
fun CommandStudioScreen(
    commandHistory: List<QueuedWriteCommand>,
    queueDepth: Int,
    heartbeatEnabled: Boolean,
    autoLocationSyncEnabled: Boolean,
    detectedLocalityName: String,
    customLocationOverride: String,
    lastSyncedClusterLocation: String?,
    tripSubcommandConfig: TripSubcommandConfig,
    onSetHeartbeatEnabled: (Boolean) -> Unit,
    onSetAutoLocationSync: (Boolean) -> Unit,
    onCustomLocationOverrideChange: (String) -> Unit,
    onDumpLocationToCluster: (String?) -> Unit,
    onUpdateTripSubcommandConfig: (TripSubcommandConfig) -> Unit,
    onSwitchToTripAClick: () -> Unit,
    onSwitchToTripBClick: () -> Unit,
    onResetActiveTripClick: () -> Unit,
    onSendCustomHex: (String, String) -> Result<Unit>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    var tripAHex by remember(tripSubcommandConfig.switchTripAByte) {
        mutableStateOf(String.format(Locale.US, "%02X", tripSubcommandConfig.switchTripAByte))
    }
    var tripBHex by remember(tripSubcommandConfig.switchTripBByte) {
        mutableStateOf(String.format(Locale.US, "%02X", tripSubcommandConfig.switchTripBByte))
    }
    var resetHex by remember(tripSubcommandConfig.resetActiveTripByte) {
        mutableStateOf(String.format(Locale.US, "%02X", tripSubcommandConfig.resetActiveTripByte))
    }

    var customHexInput by remember {
        mutableStateOf("5A F1 00 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 FF")
    }
    var customHexError by remember { mutableStateOf<String?>(null) }

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
            // 1. Live Cluster Location Display Synchronizer (`0x5B 0x5C` + 17B ASCII String + `0xFF`)
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
                            text = "LIVE CLUSTER LOCATION DISPLAY (0x5B 0x5C + 17B ASCII + 0xFF)",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Unfiltered Frame Rule: Byte 0=0x5B ('['), Byte 1=0x5C ('\\'), Bytes 2..18 = 17-char ASCII locality padded with 0x00, Byte 19=0xFF.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
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

                val activeText = customLocationOverride.ifBlank { detectedLocalityName }
                    .take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
                val previewBytes = remember(activeText) {
                    val pkt = ByteArray(20)
                    pkt[0] = 0x5B
                    pkt[1] = 0x5C
                    val ascii = activeText.replace(Regex("[^\\x20-\\x7E]"), "")
                        .trim()
                        .take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
                        .toByteArray(StandardCharsets.US_ASCII)
                    System.arraycopy(ascii, 0, pkt, 2, minOf(ascii.size, IqubeBleSpec.MAX_LOCATION_ASCII_BYTES))
                    pkt[19] = 0xFF.toByte()
                    pkt
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SlateCardElevated)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Nominatim Reverse-Geocoded Locality: \"$detectedLocalityName\"  |  Last Sent: \"${lastSyncedClusterLocation ?: "--"}\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonMint
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "20B Packet Hex (Byte 19=0xFF): ${previewBytes.toHexString()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    Text(
                        text = "ASCII Preview: ${previewBytes.toAsciiPreview()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary
                    )
                }

                OutlinedTextField(
                    value = customLocationOverride,
                    onValueChange = { onCustomLocationOverrideChange(it.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)) },
                    label = { Text("Custom 17-Character Location Text Override (Bytes 2..18, Byte 19=0xFF)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_location_override_input")
                )

                Button(
                    onClick = { onDumpLocationToCluster(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_dump_location_button")
                ) {
                    Text(
                        text = "Dump Current Location to Cluster (\"$activeText\" + 0xFF)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 2. Verified Trip Control Buttons & Subcommand Byte Configurator (5A F1 00 <subCmd> ... FF)
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
                    text = "VERIFIED TRIP CONTROL PAYLOADS (0x5352 / HANDLE 0x0022 • 0x5A 0xF1 0x00 … 0xFF)",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonMint
                )
                Text(
                    text = "Hardware verified: Switch to Trip A (0x03), Switch to Trip B (0x01), Reset Active Trip A/B (0x02). Every packet ends with Byte 19 = 0xFF.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tripAHex,
                        onValueChange = { v ->
                            tripAHex = v.take(2)
                            v.toIntOrNull(16)?.let {
                                onUpdateTripSubcommandConfig(tripSubcommandConfig.copy(switchTripAByte = it and 0xFF))
                            }
                        },
                        label = { Text("Trip A Byte (Hex)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tripBHex,
                        onValueChange = { v ->
                            tripBHex = v.take(2)
                            v.toIntOrNull(16)?.let {
                                onUpdateTripSubcommandConfig(tripSubcommandConfig.copy(switchTripBByte = it and 0xFF))
                            }
                        },
                        label = { Text("Trip B Byte (Hex)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = resetHex,
                        onValueChange = { v ->
                            resetHex = v.take(2)
                            v.toIntOrNull(16)?.let {
                                onUpdateTripSubcommandConfig(tripSubcommandConfig.copy(resetActiveTripByte = it and 0xFF))
                            }
                        },
                        label = { Text("Reset A/B (Hex)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSwitchToTripAClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMint, contentColor = SlateSurface),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Switch to Trip A", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onSwitchToTripBClick,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Switch to Trip B", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onResetActiveTripClick,
                        colors = ButtonDefaults.buttonColors(containerColor = EnergeticCoral, contentColor = SlateSurface),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset Active (A/B)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 3. Guarded 20-Byte Hex Command Builder & Protocol Validator (Byte 19 = 0xFF)
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
                    text = "GUARDED 20-BYTE HEX INJECTOR (MANDATORY BYTE 19 = 0xFF)",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberWarning
                )
                Text(
                    text = "Enforces 20-byte length, valid 0x5A or 0x5B ([RU, [JD, [\\, []C) prefix, and mandatory Byte 19 = 0xFF trailer before GATT write on 0x5352.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = customHexInput,
                    onValueChange = {
                        customHexInput = it
                        customHexError = null
                    },
                    label = { Text("20-Byte Hex Payload (Space-separated, Byte 19 = FF)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_hex_input")
                )

                if (customHexError != null) {
                    Text(
                        text = customHexError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EnergeticCoral
                    )
                }

                Button(
                    onClick = {
                        val res = onSendCustomHex("Custom 20B Hex Frame", customHexInput)
                        customHexError = res.exceptionOrNull()?.message
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = SlateSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("send_custom_hex_button")
                ) {
                    Text(
                        text = "Validate & Enqueue 20-Byte Frame (0x5352)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 4. Serialized FIFO Write Queue Monitor & Heartbeat Toggle
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
                    Column {
                        Text(
                            text = "SERIALIZED FIFO WRITE QUEUE (0x5352 / HANDLE 0x0022)",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Pending Queue Depth: $queueDepth • 500ms ATT Write Response (0x13) Guard",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "[JD 1.8s Clock",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = heartbeatEnabled,
                            onCheckedChange = onSetHeartbeatEnabled
                        )
                    }
                }

                if (commandHistory.isEmpty()) {
                    Text(
                        text = "No outgoing commands dispatched yet. Trigger an action above or start Ride / Charge Replay.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    commandHistory.take(12).forEach { cmd ->
                        val badgeColor = when (cmd.status) {
                            WriteCommandStatus.ACK_RECEIVED -> NeonMint
                            WriteCommandStatus.AWAITING_ACK, WriteCommandStatus.QUEUED -> AmberWarning
                            WriteCommandStatus.TIMEOUT_500MS,
                            WriteCommandStatus.REJECTED_FRAMING,
                            WriteCommandStatus.DISCONNECTED_ERROR -> EnergeticCoral
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SlateCardElevated)
                                .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "#${cmd.id} ${cmd.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${cmd.status.name}${cmd.ackLatencyMs?.let { " (${it}ms)" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "HEX: ${cmd.payload.toHexString()}  |  ASCII: ${cmd.payload.toAsciiPreview()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                            if (cmd.errorDetail != null) {
                                Text(
                                    text = cmd.errorDetail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EnergeticCoral
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
