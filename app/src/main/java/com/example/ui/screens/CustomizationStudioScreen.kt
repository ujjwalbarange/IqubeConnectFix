package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.ClickAction
import com.example.model.ElementType
import com.example.model.TargetSurface
import com.example.model.TemplateElement
import com.example.model.TileMetricType
import com.example.model.WidgetCustomTemplate
import com.example.model.WidgetGridSize
import com.example.model.WidgetThemePreset
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EnergeticCoral
import com.example.ui.theme.NeonMint
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCard
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.widget.CustomSurfaceCanvasRenderer
import com.example.widget.LiveSurfaceTelemetrySnapshot
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizationStudioScreen(
    activeTemplate: WidgetCustomTemplate,
    activeNotificationTemplate: WidgetCustomTemplate = WidgetCustomTemplate(
        id = "default_notif_style",
        name = "Cockpit Widget Notification",
        targetSurface = TargetSurface.STATUS_NOTIFICATION,
        elements = WidgetCustomTemplate.defaultElementsForSize(TargetSurface.STATUS_NOTIFICATION, WidgetGridSize.SIZE_4X2)
    ),
    activeTileTemplate: WidgetCustomTemplate = WidgetCustomTemplate(
        id = "default_tile_style",
        name = "Battery % + DTE Range Tile",
        targetSurface = TargetSurface.CONTROL_CENTER_TILE,
        elements = WidgetCustomTemplate.defaultElementsForSize(TargetSurface.CONTROL_CENTER_TILE, WidgetGridSize.SIZE_1X1)
    ),
    savedTemplates: List<WidgetCustomTemplate> = emptyList(),
    onSaveTemplate: (WidgetCustomTemplate, Boolean) -> Unit,
    onDeleteTemplate: (String) -> Unit = {},
    onImportCanvaImage: (Uri) -> String?,
    onBackupAllData: () -> Boolean,
    onRestoreAllData: () -> Boolean,
    backupFolderPath: String = "Documents/iQubeCockpit",
    currentBatteryPct: Int = 93,
    currentRangeKm: Double = 98.0,
    trueEcoKm: Double = 93.0,
    truePowerKm: Double = 70.0,
    localityName: String = "Dhantoli",
    isCharging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTargetSurface by remember { mutableStateOf(TargetSurface.APP_WIDGET) }

    var editingTemplate by remember(activeTemplate.id, activeNotificationTemplate.id, activeTileTemplate.id) {
        mutableStateOf(
            when (selectedTargetSurface) {
                TargetSurface.APP_WIDGET -> activeTemplate
                TargetSurface.STATUS_NOTIFICATION -> activeNotificationTemplate
                TargetSurface.CONTROL_CENTER_TILE -> activeTileTemplate
            }
        )
    }

    var selectedElementId by remember(editingTemplate.id) {
        mutableStateOf(editingTemplate.elements.firstOrNull()?.id)
    }

    // Dialog states for "+ Create New" (with size picker) and "+ Add Component"
    var showCreateNewDialog by remember { mutableStateOf(false) }
    var newStyleName by remember { mutableStateOf("") }
    var newStyleGridSize by remember { mutableStateOf(WidgetGridSize.SIZE_2X1) }
    var newStyleWidgetSlot by remember { mutableIntStateOf(1) }

    var showAddComponentDialog by remember { mutableStateOf(false) }

    val liveSnapshot: LiveSurfaceTelemetrySnapshot = remember(
        currentBatteryPct,
        currentRangeKm,
        trueEcoKm,
        truePowerKm,
        localityName,
        isCharging
    ) {
        LiveSurfaceTelemetrySnapshot(
            vehicleName = "TVSiQUBEJI573 Cockpit",
            batteryPct = currentBatteryPct,
            clusterRangeKm = currentRangeKm,
            trueEcoKm = trueEcoKm,
            truePowerKm = truePowerKm,
            speedKmh = 0,
            odometerKm = 35925.2,
            tripAKm = 12.4,
            tripBKm = 48.6,
            localityName = localityName,
            lastSeenFormatted = "Last seen 02:49 PM",
            isConnected = false,
            isConnecting = false,
            isCharging = isCharging,
            isDisconnectedPredicted = false,
            needsCalibration = false,
            timeTo80Str = "32m",
            timeToFullStr = "1h 15m",
            statusBadgeText = if (isCharging) "⚡ Charging" else "● Connected",
            statusBadgeColorHex = if (isCharging) "#00E5FF" else "#00E676"
        )
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val savedPath = onImportCanvaImage(uri)
            if (savedPath != null) {
                editingTemplate = editingTemplate.copy(
                    themePreset = WidgetThemePreset.CANVA_CUSTOM,
                    backgroundImagePath = savedPath
                )
                Toast.makeText(context, "Canva graphic imported into Documents/iQubeCockpit!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // "+ Create New" Modal Dialog with Size Picker (1x1, 2x1, 2x2, 3x1, 3x2, 4x1, 4x2, 4x3)
    if (showCreateNewDialog) {
        AlertDialog(
            onDismissRequest = { showCreateNewDialog = false },
            containerColor = SlateCard,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    text = when (selectedTargetSurface) {
                        TargetSurface.APP_WIDGET -> "Create New Home Screen Widget"
                        TargetSurface.STATUS_NOTIFICATION -> "Create New Widget-Style Notification"
                        TargetSurface.CONTROL_CENTER_TILE -> "Create New Control Center Tile Style"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = newStyleName,
                        onValueChange = { newStyleName = it },
                        label = { Text("Style Name") },
                        placeholder = {
                            Text(
                                when (selectedTargetSurface) {
                                    TargetSurface.APP_WIDGET -> "e.g. My ${newStyleGridSize.shortLabel} Widget"
                                    TargetSurface.STATUS_NOTIFICATION -> "e.g. Neon Telemetry Banner"
                                    TargetSurface.CONTROL_CENTER_TILE -> "e.g. Large % + Eco Tile"
                                }
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = SlateBorder
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("input_new_style_name")
                    )

                    if (selectedTargetSurface == TargetSurface.APP_WIDGET) {
                        Text(
                            text = "Select Widget Size (starts from 1×1, 2×1, up to 4×3):",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            WidgetGridSize.entries.forEach { sizeOption ->
                                val selected = newStyleGridSize == sizeOption
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        newStyleGridSize = sizeOption
                                        newStyleWidgetSlot = when {
                                            sizeOption == WidgetGridSize.SIZE_1X1 -> 2
                                            sizeOption == WidgetGridSize.SIZE_2X1 || sizeOption == WidgetGridSize.SIZE_3X1 -> 1
                                            else -> 3
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = "${sizeOption.shortLabel} (${sizeOption.title})",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                        selectedLabelColor = ElectricCyan
                                    ),
                                    modifier = Modifier.testTag("chip_grid_size_${sizeOption.name}")
                                )
                            }
                        }

                        Text(
                            text = "Assign to 'Add Widgets' Picker Slot:",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val slots = listOf(
                                0 to "Slot 1: Cockpit (4×2)",
                                1 to "Slot 2: Compact (2×1)",
                                2 to "Slot 3: Mini (1×1)",
                                3 to "Slot 4: Custom #1",
                                4 to "Slot 5: Custom #2"
                            )
                            slots.forEach { (slotIdx, slotLabel) ->
                                FilterChip(
                                    selected = newStyleWidgetSlot == slotIdx,
                                    onClick = { newStyleWidgetSlot = slotIdx },
                                    label = { Text(slotLabel, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NeonMint.copy(alpha = 0.2f),
                                        selectedLabelColor = NeonMint
                                    )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val defaultName = when (selectedTargetSurface) {
                            TargetSurface.APP_WIDGET -> "Custom ${newStyleGridSize.shortLabel} Widget"
                            TargetSurface.STATUS_NOTIFICATION -> "Custom Notification Banner"
                            TargetSurface.CONTROL_CENTER_TILE -> "Custom Control Tile"
                        }
                        val finalName = newStyleName.trim().ifEmpty { defaultName }
                        val created = WidgetCustomTemplate(
                            id = "custom_${selectedTargetSurface.name.lowercase()}_$now",
                            name = finalName,
                            targetSurface = selectedTargetSurface,
                            gridSize = if (selectedTargetSurface == TargetSurface.APP_WIDGET) newStyleGridSize else WidgetGridSize.SIZE_4X1,
                            themePreset = WidgetThemePreset.CYBER_NEON,
                            cornerRadiusDp = if (newStyleGridSize == WidgetGridSize.SIZE_1X1) 20 else 22,
                            borderWidthDp = 1.5f,
                            borderColorHex = "#00E5FF",
                            widgetSlotIndex = newStyleWidgetSlot,
                            useWidgetStyleNotification = true,
                            elements = WidgetCustomTemplate.defaultElementsForSize(
                                surface = selectedTargetSurface,
                                gridSize = if (selectedTargetSurface == TargetSurface.APP_WIDGET) newStyleGridSize else WidgetGridSize.SIZE_4X1
                            )
                        )
                        editingTemplate = created
                        selectedElementId = created.elements.firstOrNull()?.id
                        showCreateNewDialog = false
                        Toast.makeText(
                            context,
                            "Created '$finalName'! Edit on canvas and click Save.",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    modifier = Modifier.testTag("btn_confirm_create_new_style")
                ) {
                    Text("Create Default & Edit", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateNewDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // "+ Add Component" Modal Dialog
    if (showAddComponentDialog) {
        AlertDialog(
            onDismissRequest = { showAddComponentDialog = false },
            containerColor = SlateCard,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    text = "Add Telemetry Component to Canvas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Tap any component below to place it onto the canvas:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    ElementType.entries.forEach { elemType ->
                        Surface(
                            color = SlateCardElevated,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newId = "el_${elemType.name.lowercase()}_${System.currentTimeMillis() % 10000}"
                                    val defaultColor = when (elemType) {
                                        ElementType.VEHICLE_TITLE, ElementType.CHARGING_ETA_OR_TIME -> "#00E676"
                                        ElementType.LIVE_SPEED_KMH, ElementType.CUSTOM_TEXT, ElementType.DECORATIVE_PILL -> "#00E5FF"
                                        else -> "#FFFFFF"
                                    }
                                    val newElem = TemplateElement(
                                        id = newId,
                                        type = elemType,
                                        label = elemType.defaultLabel,
                                        xPercent = 12f,
                                        yPercent = 35f,
                                        fontSizeSp = elemType.defaultFontSizeSp,
                                        colorHex = defaultColor,
                                        isBold = elemType in listOf(ElementType.BATTERY_PERCENT, ElementType.VEHICLE_TITLE, ElementType.RANGE_KM),
                                        visible = true,
                                        customText = if (elemType == ElementType.CUSTOM_TEXT) "TVS iQube Electric" else ""
                                    )
                                    editingTemplate = editingTemplate.copy(
                                        elements = editingTemplate.elements + newElem
                                    )
                                    selectedElementId = newId
                                    showAddComponentDialog = false
                                }
                                .testTag("add_component_${elemType.name}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(elemType.defaultLabel, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Icon(Icons.Filled.Add, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddComponentDialog = false }) {
                    Text("Close", color = ElectricCyan)
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Target Surface Tabs (Home Widgets | Notification Style | Control Center Tile)
        TabRow(
            selectedTabIndex = selectedTargetSurface.ordinal,
            containerColor = SlateSurface,
            contentColor = ElectricCyan,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTargetSurface.ordinal]),
                    color = ElectricCyan
                )
            },
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            TargetSurface.entries.forEach { surface ->
                Tab(
                    selected = selectedTargetSurface == surface,
                    onClick = {
                        selectedTargetSurface = surface
                        val targetActive = when (surface) {
                            TargetSurface.APP_WIDGET -> activeTemplate
                            TargetSurface.STATUS_NOTIFICATION -> activeNotificationTemplate
                            TargetSurface.CONTROL_CENTER_TILE -> activeTileTemplate
                        }
                        editingTemplate = targetActive
                        selectedElementId = targetActive.elements.firstOrNull()?.id
                    },
                    text = {
                        Text(
                            text = surface.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selectedTargetSurface == surface) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("tab_surface_${surface.name}")
                )
            }
        }

        // 2. Currently Created Styles Gallery + "+ Create New" Button
        val templatesForCurrentSurface = remember(savedTemplates, selectedTargetSurface, activeTemplate, activeNotificationTemplate, activeTileTemplate) {
            val filtered = savedTemplates.filter { it.targetSurface == selectedTargetSurface }
            if (filtered.isNotEmpty()) filtered else listOf(
                when (selectedTargetSurface) {
                    TargetSurface.APP_WIDGET -> activeTemplate
                    TargetSurface.STATUS_NOTIFICATION -> activeNotificationTemplate
                    TargetSurface.CONTROL_CENTER_TILE -> activeTileTemplate
                }
            )
        }

        val currentActiveIdForSurface = when (selectedTargetSurface) {
            TargetSurface.APP_WIDGET -> activeTemplate.id
            TargetSurface.STATUS_NOTIFICATION -> activeNotificationTemplate.id
            TargetSurface.CONTROL_CENTER_TILE -> activeTileTemplate.id
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("created_styles_gallery_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Created ${selectedTargetSurface.title} (${templatesForCurrentSurface.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = when (selectedTargetSurface) {
                                TargetSurface.APP_WIDGET -> "Tap a created widget to edit, or click + Create New to pick size (1×1, 2×1, 4×2...)"
                                TargetSurface.STATUS_NOTIFICATION -> "Currently active Widget-Style Notification & saved presets"
                                TargetSurface.CONTROL_CENTER_TILE -> "Currently active Control Center Tile & parameter presets"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = {
                            newStyleName = ""
                            newStyleGridSize = WidgetGridSize.SIZE_2X1
                            showCreateNewDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("btn_create_new_style")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create New", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    templatesForCurrentSurface.forEach { item ->
                        val isEditingThis = editingTemplate.id == item.id
                        val isLiveActive = item.id == currentActiveIdForSurface
                        Surface(
                            color = if (isEditingThis) ElectricCyan.copy(alpha = 0.16f) else SlateCardElevated,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isEditingThis) 1.5.dp else 1.dp,
                                color = if (isEditingThis) ElectricCyan else if (isLiveActive) NeonMint.copy(alpha = 0.6f) else SlateBorder
                            ),
                            modifier = Modifier
                                .width(205.dp)
                                .clickable {
                                    editingTemplate = item
                                    selectedElementId = item.elements.firstOrNull()?.id
                                }
                                .testTag("style_card_${item.id}")
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = if (isLiveActive) NeonMint.copy(alpha = 0.2f) else SlateSurface,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (isLiveActive) "● ACTIVE" else item.gridSize.shortLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isLiveActive) NeonMint else ElectricCyan,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (templatesForCurrentSurface.size > 1) {
                                        IconButton(
                                            onClick = { onDeleteTemplate(item.id) },
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Delete Style",
                                                tint = EnergeticCoral.copy(alpha = 0.85f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when (item.targetSurface) {
                                        TargetSurface.APP_WIDGET -> "Size: ${item.gridSize.shortLabel} • Corner: ${item.cornerRadiusDp}dp"
                                        TargetSurface.STATUS_NOTIFICATION -> if (item.useWidgetStyleNotification) "Widget-Type Notification" else "Standard Text Notification"
                                        TargetSurface.CONTROL_CENTER_TILE -> "${item.tileConfig.topMetric.title} / ${item.tileConfig.bottomMetric.title}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Surface-Specific Editor & Preview
        if (selectedTargetSurface == TargetSurface.CONTROL_CENTER_TILE) {
            ControlCenterTileEditorSection(
                editingTemplate = editingTemplate,
                onUpdateTemplate = { editingTemplate = it },
                snapshot = liveSnapshot
            )
        } else {
            // Widget & Notification Style Size / Mode Header + Live Canvas
            if (selectedTargetSurface == TargetSurface.APP_WIDGET) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Widget Grid Size & Proportions:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Auto-fits any launcher resize",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonMint
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            WidgetGridSize.entries.forEach { gridSize ->
                                FilterChip(
                                    selected = editingTemplate.gridSize == gridSize,
                                    onClick = {
                                        val resetDefaultElements = WidgetCustomTemplate.defaultElementsForSize(
                                            TargetSurface.APP_WIDGET,
                                            gridSize
                                        )
                                        editingTemplate = editingTemplate.copy(
                                            gridSize = gridSize,
                                            elements = resetDefaultElements
                                        )
                                        selectedElementId = resetDefaultElements.firstOrNull()?.id
                                    },
                                    label = { Text(gridSize.shortLabel, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                        selectedLabelColor = ElectricCyan
                                    ),
                                    modifier = Modifier.testTag("size_selector_${gridSize.name}")
                                )
                            }
                        }
                    }
                }
            } else {
                // Notification Style Mode Toggle (Widget-Type Notification vs Simple Text)
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = NeonMint, modifier = Modifier.size(22.dp))
                            Column {
                                Text(
                                    text = "Widget-Type Rich Notification",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Renders your custom Studio canvas directly inside the Android notification shade with a live Connect/Disconnect action",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = editingTemplate.useWidgetStyleNotification,
                            onCheckedChange = { editingTemplate = editingTemplate.copy(useWidgetStyleNotification = it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonMint, checkedTrackColor = NeonMint.copy(alpha = 0.3f))
                        )
                    }
                }
            }

            // Live Interactive WYSIWYG Draggable Canvas
            val canvasAspectRatio = if (selectedTargetSurface == TargetSurface.STATUS_NOTIFICATION) {
                2.8f
            } else {
                editingTemplate.gridSize.aspectRatio
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCardElevated),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wysiwyg_preview_container")
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedTargetSurface == TargetSurface.STATUS_NOTIFICATION) {
                                "WIDGET-TYPE NOTIFICATION CANVAS"
                            } else {
                                "LIVE WIDGET CANVAS (${editingTemplate.gridSize.shortLabel})"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Drag components • Tap to style",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }

                    val borderColor = runCatching {
                        Color(android.graphics.Color.parseColor(editingTemplate.borderColorHex))
                    }.getOrDefault(ElectricCyan)

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val widthFraction = when {
                            selectedTargetSurface == TargetSurface.STATUS_NOTIFICATION -> 1f
                            editingTemplate.gridSize == WidgetGridSize.SIZE_1X1 -> 0.44f
                            editingTemplate.gridSize == WidgetGridSize.SIZE_2X1 || editingTemplate.gridSize == WidgetGridSize.SIZE_2X2 -> 0.72f
                            else -> 1f
                        }

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth(widthFraction)
                                .aspectRatio(canvasAspectRatio)
                                .clip(RoundedCornerShape(editingTemplate.cornerRadiusDp.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            runCatching { Color(android.graphics.Color.parseColor(editingTemplate.backgroundColorHex)) }.getOrDefault(Color(0xFF0B0F19)),
                                            runCatching { Color(android.graphics.Color.parseColor(editingTemplate.backgroundGradientEndHex)) }.getOrDefault(Color(0xFF0F172A))
                                        )
                                    )
                                )
                                .border(
                                    width = editingTemplate.borderWidthDp.dp,
                                    color = if (editingTemplate.borderWidthDp > 0.1f) borderColor else Color.Transparent,
                                    shape = RoundedCornerShape(editingTemplate.cornerRadiusDp.dp)
                                )
                                .testTag("interactive_drag_canvas")
                        ) {
                            val containerWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                            val containerHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                            if (!editingTemplate.backgroundImagePath.isNullOrBlank()) {
                                val file = File(editingTemplate.backgroundImagePath!!)
                                if (file.exists()) {
                                    AsyncImage(
                                        model = file,
                                        contentDescription = "Canva Background Graphic",
                                        contentScale = ContentScale.Crop,
                                        alpha = editingTemplate.backgroundImageOpacity,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            editingTemplate.elements.filter { it.visible }.forEach { element ->
                                val isSelected = element.id == selectedElementId
                                val elemColor = runCatching { Color(android.graphics.Color.parseColor(element.colorHex)) }.getOrDefault(Color.White)
                                val accentColor = runCatching { Color(android.graphics.Color.parseColor(editingTemplate.accentColorHex)) }.getOrDefault(ElectricCyan)

                                val xPx = (element.xPercent / 100f) * containerWidthPx
                                val yPx = (element.yPercent / 100f) * containerHeightPx

                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                                        .pointerInput(element.id, editingTemplate.id) {
                                            detectDragGestures(
                                                onDragStart = { selectedElementId = element.id },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    val currentElem = editingTemplate.elements.firstOrNull { it.id == element.id } ?: return@detectDragGestures
                                                    val currXPx = (currentElem.xPercent / 100f) * containerWidthPx
                                                    val currYPx = (currentElem.yPercent / 100f) * containerHeightPx
                                                    val newXPct = ((currXPx + dragAmount.x) / containerWidthPx * 100f).coerceIn(2f, 88f)
                                                    val newYPct = ((currYPx + dragAmount.y) / containerHeightPx * 100f).coerceIn(2f, 88f)
                                                    editingTemplate = editingTemplate.copy(
                                                        elements = editingTemplate.elements.map {
                                                            if (it.id == element.id) it.copy(xPercent = newXPct, yPercent = newYPct) else it
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                        .clickable { selectedElementId = element.id }
                                        .border(
                                            width = if (isSelected) 1.5.dp else 0.dp,
                                            color = if (isSelected) ElectricCyan else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    RenderElementPreview(
                                        element = element,
                                        batteryPct = currentBatteryPct,
                                        rangeKm = currentRangeKm,
                                        trueEcoKm = trueEcoKm,
                                        truePowerKm = truePowerKm,
                                        localityName = localityName,
                                        isCharging = isCharging,
                                        accentColor = accentColor,
                                        elemColor = elemColor
                                    )
                                }
                            }
                        }
                    }

                    // If Notification Style, also show the Action Bar footer preview (Connect button)
                    if (selectedTargetSurface == TargetSurface.STATUS_NOTIFICATION) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SlateSurface)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Battery: $currentBatteryPct% • Range: ${currentRangeKm.roundToInt()} km • 📍 $localityName",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = SlateCardElevated,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Connect",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 4. Border Roundness, Border Thickness, Theme & Canva Image Controls
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("theme_controls_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.RoundedCorner, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = "Border Roundness, Frame & Theme",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    // Border Corner Roundness Slider (0dp .. 48dp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Corner Roundness", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text("${editingTemplate.cornerRadiusDp} dp", style = MaterialTheme.typography.labelSmall, color = ElectricCyan, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = editingTemplate.cornerRadiusDp.toFloat(),
                                onValueChange = { editingTemplate = editingTemplate.copy(cornerRadiusDp = it.roundToInt()) },
                                valueRange = 0f..48f,
                                colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan),
                                modifier = Modifier.testTag("slider_corner_roundness")
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Border Thickness", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(String.format(Locale.US, "%.1f dp", editingTemplate.borderWidthDp), style = MaterialTheme.typography.labelSmall, color = NeonMint, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = editingTemplate.borderWidthDp,
                                onValueChange = { editingTemplate = editingTemplate.copy(borderWidthDp = it) },
                                valueRange = 0f..6f,
                                colors = SliderDefaults.colors(thumbColor = NeonMint, activeTrackColor = NeonMint),
                                modifier = Modifier.testTag("slider_border_width")
                            )
                        }
                    }

                    // Border Color Picker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Border Color:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        listOf("#283548", "#00E5FF", "#00E676", "#FFB300", "#FF5252", "#FFFFFF").forEach { hex ->
                            val col = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(
                                        width = if (editingTemplate.borderColorHex.equals(hex, ignoreCase = true)) 2.dp else 1.dp,
                                        color = if (editingTemplate.borderColorHex.equals(hex, ignoreCase = true)) Color.White else SlateBorder,
                                        shape = CircleShape
                                    )
                                    .clickable { editingTemplate = editingTemplate.copy(borderColorHex = hex) }
                            )
                        }
                    }

                    HorizontalDivider(color = SlateBorder)

                    // Background Theme Presets
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WidgetThemePreset.entries.forEach { preset ->
                            val isSelected = editingTemplate.themePreset == preset
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    editingTemplate = editingTemplate.copy(
                                        themePreset = preset,
                                        backgroundColorHex = preset.bgHex,
                                        backgroundGradientEndHex = preset.bgGradientEnd,
                                        accentColorHex = preset.accentHex,
                                        textColorHex = preset.textHex,
                                        borderColorHex = preset.borderHex
                                    )
                                },
                                label = { Text(preset.title, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = ElectricCyan
                                )
                            )
                        }
                    }

                    // Canva / Custom Graphic Import
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Canva / Custom Image Overlay", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(
                                text = if (editingTemplate.backgroundImagePath.isNullOrBlank()) "Import Canva template or background graphic" else "Custom image active",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!editingTemplate.backgroundImagePath.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = { editingTemplate = editingTemplate.copy(backgroundImagePath = null) }
                                ) {
                                    Text("Clear", color = EnergeticCoral, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Button(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SlateCardElevated),
                                modifier = Modifier.testTag("btn_import_canva")
                            ) {
                                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import Art", color = ElectricCyan)
                            }
                        }
                    }
                }
            }

            // 5. Canvas Components Manager (+ Add Component, Delete Component, Font Size, Position, Color)
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("element_palette_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Layers, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                            Text(
                                text = "Canvas Components (${editingTemplate.elements.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Button(
                            onClick = { showAddComponentDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonMint.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("btn_add_component_to_canvas")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = NeonMint, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Component", color = NeonMint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    val activeElement = editingTemplate.elements.firstOrNull { it.id == selectedElementId }

                    if (activeElement != null) {
                        Surface(
                            color = SlateCardElevated,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Selected: ${activeElement.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricCyan
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = {
                                                editingTemplate = editingTemplate.copy(
                                                    elements = editingTemplate.elements.map {
                                                        if (it.id == activeElement.id) it.copy(isBold = !it.isBold) else it
                                                    }
                                                )
                                            }
                                        ) {
                                            Icon(
                                                Icons.Filled.FormatBold,
                                                contentDescription = "Toggle Bold",
                                                tint = if (activeElement.isBold) NeonMint else TextMuted
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                editingTemplate = editingTemplate.copy(
                                                    elements = editingTemplate.elements.map {
                                                        if (it.id == activeElement.id) it.copy(visible = !it.visible) else it
                                                    }
                                                )
                                            }
                                        ) {
                                            Icon(
                                                if (activeElement.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                                contentDescription = "Toggle Visibility",
                                                tint = if (activeElement.visible) ElectricCyan else TextMuted
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val remaining = editingTemplate.elements.filterNot { it.id == activeElement.id }
                                                editingTemplate = editingTemplate.copy(elements = remaining)
                                                selectedElementId = remaining.firstOrNull()?.id
                                            },
                                            modifier = Modifier.testTag("btn_delete_selected_component")
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Delete Component from Canvas",
                                                tint = EnergeticCoral
                                            )
                                        }
                                    }
                                }

                                // Font Size Slider
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Component Size / Font", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        Text("${activeElement.fontSizeSp} sp", style = MaterialTheme.typography.labelSmall, color = ElectricCyan, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = activeElement.fontSizeSp.toFloat(),
                                        onValueChange = { newSp ->
                                            editingTemplate = editingTemplate.copy(
                                                elements = editingTemplate.elements.map {
                                                    if (it.id == activeElement.id) it.copy(fontSizeSp = newSp.roundToInt()) else it
                                                }
                                            )
                                        },
                                        valueRange = 6f..44f,
                                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                                    )
                                }

                                // X & Y Position Sliders
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("X Pos: ${activeElement.xPercent.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        Slider(
                                            value = activeElement.xPercent,
                                            onValueChange = { newX ->
                                                editingTemplate = editingTemplate.copy(
                                                    elements = editingTemplate.elements.map {
                                                        if (it.id == activeElement.id) it.copy(xPercent = newX) else it
                                                    }
                                                )
                                            },
                                            valueRange = 2f..88f,
                                            colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Y Pos: ${activeElement.yPercent.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        Slider(
                                            value = activeElement.yPercent,
                                            onValueChange = { newY ->
                                                editingTemplate = editingTemplate.copy(
                                                    elements = editingTemplate.elements.map {
                                                        if (it.id == activeElement.id) it.copy(yPercent = newY) else it
                                                    }
                                                )
                                            },
                                            valueRange = 2f..88f,
                                            colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                                        )
                                    }
                                }

                                // Component Color Picker
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Color:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    listOf("#FFFFFF", "#00E676", "#00E5FF", "#FFB300", "#FF5252", "#94A3B8").forEach { colorHex ->
                                        val col = Color(android.graphics.Color.parseColor(colorHex))
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(col)
                                                .border(
                                                    width = if (activeElement.colorHex.equals(colorHex, ignoreCase = true)) 2.dp else 0.dp,
                                                    color = Color.White,
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    editingTemplate = editingTemplate.copy(
                                                        elements = editingTemplate.elements.map {
                                                            if (it.id == activeElement.id) it.copy(colorHex = colorHex) else it
                                                        }
                                                    )
                                                }
                                        )
                                    }
                                }

                                if (activeElement.type == ElementType.CUSTOM_TEXT || activeElement.type == ElementType.VEHICLE_TITLE) {
                                    OutlinedTextField(
                                        value = activeElement.customText,
                                        onValueChange = { newTxt ->
                                            editingTemplate = editingTemplate.copy(
                                                elements = editingTemplate.elements.map {
                                                    if (it.id == activeElement.id) it.copy(customText = newTxt) else it
                                                }
                                            )
                                        },
                                        label = { Text("Custom Text Override") },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = ElectricCyan,
                                            unfocusedBorderColor = SlateBorder
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // Component List with quick select, visibility switch, and delete button
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        editingTemplate.elements.forEach { elem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (elem.id == selectedElementId) SlateCardElevated else Color.Transparent)
                                    .clickable { selectedElementId = elem.id }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                    val col = runCatching { Color(android.graphics.Color.parseColor(elem.colorHex)) }.getOrDefault(Color.White)
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(col))
                                    Text(
                                        text = "${elem.label} (${elem.fontSizeSp}sp)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (elem.id == selectedElementId) ElectricCyan else TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Switch(
                                        checked = elem.visible,
                                        onCheckedChange = { isVis ->
                                            editingTemplate = editingTemplate.copy(
                                                elements = editingTemplate.elements.map {
                                                    if (it.id == elem.id) it.copy(visible = isVis) else it
                                                }
                                            )
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = NeonMint, checkedTrackColor = NeonMint.copy(alpha = 0.3f))
                                    )
                                    IconButton(
                                        onClick = {
                                            val remaining = editingTemplate.elements.filterNot { it.id == elem.id }
                                            editingTemplate = editingTemplate.copy(elements = remaining)
                                            if (selectedElementId == elem.id) {
                                                selectedElementId = remaining.firstOrNull()?.id
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = EnergeticCoral.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Click Action Behavior Configuration
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("click_action_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                    Text("Action When Tapped", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                }

                ClickAction.entries.forEach { action ->
                    val isSelected = editingTemplate.clickAction == action
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) ElectricCyan.copy(alpha = 0.15f) else SlateCardElevated)
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) ElectricCyan else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { editingTemplate = editingTemplate.copy(clickAction = action) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(action.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (isSelected) ElectricCyan else TextPrimary)
                            Text(action.description, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        if (isSelected) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // 7. Save & Activate / Add to Home Screen & Shared Storage Backup Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonMint.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().testTag("shared_storage_permanence_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Save & Apply Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveTemplate(editingTemplate, false)
                            Toast.makeText(
                                context,
                                when (selectedTargetSurface) {
                                    TargetSurface.APP_WIDGET -> "Saved '${editingTemplate.name}'! Available in Android 'Add Widgets' picker."
                                    TargetSurface.STATUS_NOTIFICATION -> "Applied Widget-Type Notification Style '${editingTemplate.name}'!"
                                    TargetSurface.CONTROL_CENTER_TILE -> "Applied Control Center Tile Style '${editingTemplate.name}'!"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("btn_apply_template")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (selectedTargetSurface) {
                                TargetSurface.APP_WIDGET -> "Save & Enable Widget"
                                TargetSurface.STATUS_NOTIFICATION -> "Save Notification Style"
                                TargetSurface.CONTROL_CENTER_TILE -> "Save Tile Style"
                            },
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (selectedTargetSurface == TargetSurface.APP_WIDGET) {
                        Button(
                            onClick = {
                                onSaveTemplate(editingTemplate, true)
                                Toast.makeText(
                                    context,
                                    "Saved '${editingTemplate.name}' (${editingTemplate.gridSize.shortLabel}) & requested Home Screen Pin!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonMint),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("btn_pin_widget_home")
                        ) {
                            Icon(Icons.Filled.PushPin, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Save & Pin to Home",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                HorizontalDivider(color = SlateBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.FolderShared, contentDescription = null, tint = NeonMint, modifier = Modifier.size(20.dp))
                        Text("Shared Storage ($backupFolderPath)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Surface(color = NeonMint.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = "Survives Uninstall",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonMint,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val ok = onBackupAllData()
                            val msg = if (ok) "Backed up all templates & trips to $backupFolderPath!" else "Backup saved."
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SlateCardElevated),
                        modifier = Modifier.weight(1f).testTag("btn_backup_shared_storage")
                    ) {
                        Icon(Icons.Filled.Backup, contentDescription = null, tint = NeonMint, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Backup All", color = NeonMint, style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = {
                            val ok = onRestoreAllData()
                            val msg = if (ok) "Restored all templates & trips from $backupFolderPath!" else "Restored latest state."
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SlateCardElevated),
                        modifier = Modifier.weight(1f).testTag("btn_restore_shared_storage")
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore All", color = ElectricCyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlCenterTileEditorSection(
    editingTemplate: WidgetCustomTemplate,
    onUpdateTemplate: (WidgetCustomTemplate) -> Unit,
    snapshot: LiveSurfaceTelemetrySnapshot
) {
    val tileConfig = editingTemplate.tileConfig
    val renderedIconBitmap = remember(tileConfig, snapshot) {
        CustomSurfaceCanvasRenderer.renderControlCenterTileBitmap(tileConfig, snapshot)
    }
    val topLabelText = remember(tileConfig, snapshot) {
        CustomSurfaceCanvasRenderer.resolveTileMetricText(
            metric = tileConfig.topMetric,
            snapshot = snapshot,
            customText = tileConfig.customTopText
        )
    }
    val bottomLabelText = remember(tileConfig, snapshot) {
        if (tileConfig.bottomMetric != TileMetricType.NONE_SINGLE_LINE) {
            CustomSurfaceCanvasRenderer.resolveTileMetricText(
                metric = tileConfig.bottomMetric,
                snapshot = snapshot,
                customText = tileConfig.customBottomText
            )
        } else ""
    }
    val subtitleText = remember(tileConfig, snapshot) {
        if (tileConfig.subtitleMetric != TileMetricType.NONE_SINGLE_LINE) {
            CustomSurfaceCanvasRenderer.resolveTileMetricText(
                metric = tileConfig.subtitleMetric,
                snapshot = snapshot,
                customText = tileConfig.customBottomText
            )
        } else "True: ${snapshot.trueEcoKm.roundToInt()} km"
    }

    // Live Control Center Tile Preview Card
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCardElevated),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder),
        modifier = Modifier.fillMaxWidth().testTag("tile_live_preview_card")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE CONTROL CENTER / QUICK SETTINGS TILE PREVIEW",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Real-time 2-line icon bitmap",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Compact Circle Status Bar / Icon Preview
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF93B8F8)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = renderedIconBitmap.asImageBitmap(),
                            contentDescription = "Tile Dynamic Icon",
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    Text("Icon Badge", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }

                // Expanded Android Quick Settings Pill Preview
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF93B8F8))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        bitmap = renderedIconBitmap.asImageBitmap(),
                        contentDescription = "Tile Icon",
                        modifier = Modifier.size(36.dp)
                    )
                    Column {
                        Text(
                            text = if (bottomLabelText.isNotBlank()) "$topLabelText • $bottomLabelText" else topLabelText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0B1426),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1E293B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // Tile Parameter & Font Size Customization Controls
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("tile_parameters_card")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Tune, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                Text(
                    text = "Tile Telemetry Parameters & Font Sizes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 1. Top Line Parameter Selector
            Text("Top Line Icon & Label Parameter:", style = MaterialTheme.typography.labelMedium, color = ElectricCyan, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TileMetricType.entries.filter { it != TileMetricType.NONE_SINGLE_LINE }.forEach { metric ->
                    FilterChip(
                        selected = tileConfig.topMetric == metric,
                        onClick = {
                            onUpdateTemplate(
                                editingTemplate.copy(
                                    tileConfig = tileConfig.copy(topMetric = metric, labelMetric = metric)
                                )
                            )
                        },
                        label = { Text(metric.title, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("tile_top_metric_${metric.name}")
                    )
                }
            }

            // 2. Bottom Line Parameter Selector
            Text("Bottom Line Icon Parameter (or None for Single Large Line):", style = MaterialTheme.typography.labelMedium, color = NeonMint, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TileMetricType.entries.forEach { metric ->
                    FilterChip(
                        selected = tileConfig.bottomMetric == metric,
                        onClick = {
                            onUpdateTemplate(
                                editingTemplate.copy(
                                    tileConfig = tileConfig.copy(bottomMetric = metric)
                                )
                            )
                        },
                        label = { Text(metric.title, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NeonMint.copy(alpha = 0.22f),
                            selectedLabelColor = NeonMint
                        ),
                        modifier = Modifier.testTag("tile_bottom_metric_${metric.name}")
                    )
                }
            }

            // 3. Expanded Tile Subtitle Parameter Selector
            Text("Tile Secondary Subtitle Parameter:", style = MaterialTheme.typography.labelMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TileMetricType.entries.forEach { metric ->
                    FilterChip(
                        selected = tileConfig.subtitleMetric == metric,
                        onClick = {
                            onUpdateTemplate(
                                editingTemplate.copy(
                                    tileConfig = tileConfig.copy(subtitleMetric = metric)
                                )
                            )
                        },
                        label = { Text(metric.title, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = WarningAmber.copy(alpha = 0.22f),
                            selectedLabelColor = WarningAmber
                        )
                    )
                }
            }

            if (tileConfig.topMetric == TileMetricType.CUSTOM_TEXT ||
                tileConfig.bottomMetric == TileMetricType.CUSTOM_TEXT ||
                tileConfig.subtitleMetric == TileMetricType.CUSTOM_TEXT
            ) {
                OutlinedTextField(
                    value = tileConfig.customTopText,
                    onValueChange = { txt ->
                        onUpdateTemplate(
                            editingTemplate.copy(
                                tileConfig = tileConfig.copy(customTopText = txt, customBottomText = txt)
                            )
                        )
                    },
                    label = { Text("Custom Tile Text") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = SlateBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(color = SlateBorder)

            // 4. Top & Bottom Font Size Sliders
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Top Font Size", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text("${tileConfig.topFontSizePx.roundToInt()} px", style = MaterialTheme.typography.labelSmall, color = ElectricCyan, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = tileConfig.topFontSizePx,
                        onValueChange = { sz ->
                            onUpdateTemplate(
                                editingTemplate.copy(
                                    tileConfig = tileConfig.copy(topFontSizePx = sz)
                                )
                            )
                        },
                        valueRange = 26f..68f,
                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan),
                        modifier = Modifier.testTag("slider_tile_top_font")
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Bottom Font Size", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text("${tileConfig.bottomFontSizePx.roundToInt()} px", style = MaterialTheme.typography.labelSmall, color = NeonMint, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = tileConfig.bottomFontSizePx,
                        onValueChange = { sz ->
                            onUpdateTemplate(
                                editingTemplate.copy(
                                    tileConfig = tileConfig.copy(bottomFontSizePx = sz)
                                )
                            )
                        },
                        valueRange = 20f..54f,
                        colors = SliderDefaults.colors(thumbColor = NeonMint, activeTrackColor = NeonMint),
                        modifier = Modifier.testTag("slider_tile_bottom_font")
                    )
                }
            }

            // 5. Bold & Border Box Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bold Tile Typography", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Switch(
                        checked = tileConfig.isBold,
                        onCheckedChange = { b ->
                            onUpdateTemplate(editingTemplate.copy(tileConfig = tileConfig.copy(isBold = b)))
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan, checkedTrackColor = ElectricCyan.copy(alpha = 0.3f))
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Icon Border Box", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Switch(
                        checked = tileConfig.showBorderBox,
                        onCheckedChange = { b ->
                            onUpdateTemplate(editingTemplate.copy(tileConfig = tileConfig.copy(showBorderBox = b)))
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = NeonMint, checkedTrackColor = NeonMint.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderElementPreview(
    element: TemplateElement,
    batteryPct: Int,
    rangeKm: Double,
    trueEcoKm: Double,
    truePowerKm: Double,
    localityName: String,
    isCharging: Boolean,
    accentColor: Color,
    elemColor: Color
) {
    val fw = if (element.isBold) FontWeight.Bold else FontWeight.Normal
    val sz = element.fontSizeSp.sp

    when (element.type) {
        ElementType.VEHICLE_TITLE -> {
            Text(
                text = element.customText.ifBlank { "TVSiQUBEJI573 Cockpit" },
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.STATUS_BADGE -> {
            Text(
                text = if (isCharging) "⚡ Charging" else "● Connected",
                color = if (isCharging) WarningAmber else elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.BATTERY_PERCENT -> {
            Text(
                text = "${batteryPct}%",
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.BATTERY_BAR -> {
            LinearProgressIndicator(
                progress = { (batteryPct / 100f).coerceIn(0f, 1f) },
                color = accentColor,
                trackColor = Color.White.copy(alpha = 0.16f),
                modifier = Modifier
                    .width(120.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
        ElementType.RANGE_KM -> {
            Text(
                text = String.format(Locale.US, "DTE: %.0f km", rangeKm),
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.TRUE_RANGE_ECO_POWER -> {
            Text(
                text = String.format(Locale.US, "True: %.0f km (Eco) • %.0f km (Pwr)", trueEcoKm, truePowerKm),
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.CHARGING_ETA_OR_TIME -> {
            Text(
                text = "⚡ 80%: 32m • Full: 1h 15m",
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.LAST_PARKED_LOCATION -> {
            Text(
                text = "📍 $localityName",
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.LAST_SEEN_TIMESTAMP -> {
            Text(
                text = "Last seen 02:49 PM",
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.CUSTOM_TEXT -> {
            Text(
                text = element.customText.ifBlank { "Custom Tagline" },
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.LIVE_SPEED_KMH -> {
            Text(
                text = "0 km/h",
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.ODOMETER_KM -> {
            Text(
                text = "ODO: 35925.2 km",
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.TRIP_A_B_DISTANCE -> {
            Text(
                text = "Trip A: 12.4 km • B: 48.6 km",
                color = elemColor,
                fontSize = sz,
                fontWeight = fw,
                maxLines = 1
            )
        }
        ElementType.DECORATIVE_PILL -> {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(2.dp)
                    .background(elemColor)
            )
        }
    }
}
