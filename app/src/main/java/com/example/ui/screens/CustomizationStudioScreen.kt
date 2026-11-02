package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.ClickAction
import com.example.model.ElementType
import com.example.model.TargetSurface
import com.example.model.TemplateElement
import com.example.model.WidgetCustomTemplate
import com.example.model.WidgetThemePreset
import com.example.ui.theme.ElectricCyan
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
import java.io.File
import kotlin.math.roundToInt

enum class PreviewSizeMode(val title: String, val aspectRatio: Float) {
    COMPACT("Compact 2x1", 2.2f),
    STANDARD("Standard 3x2", 1.5f),
    EXPANDED("Expanded 4x2", 1.8f),
    BANNER("Full Banner 4x3", 1.33f)
}

@Composable
fun CustomizationStudioScreen(
    activeTemplate: WidgetCustomTemplate,
    onSaveTemplate: (WidgetCustomTemplate) -> Unit,
    onImportCanvaImage: (Uri) -> String?,
    onBackupAllData: () -> Boolean,
    onRestoreAllData: () -> Boolean,
    backupFolderPath: String = "Documents/iQubeCockpit",
    currentBatteryPct: Int = 87,
    currentRangeKm: Double = 92.0,
    trueEcoKm: Double = 87.0,
    truePowerKm: Double = 65.3,
    localityName: String = "Medical College Rd",
    isCharging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var editingTemplate by remember(activeTemplate.id) { mutableStateOf(activeTemplate) }
    var selectedTargetSurface by remember { mutableStateOf(activeTemplate.targetSurface) }
    var selectedSizeMode by remember { mutableStateOf(PreviewSizeMode.STANDARD) }
    var selectedElementId by remember { mutableStateOf<String?>(null) }
    var showColorPaletteDialog by remember { mutableStateOf(false) }

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
                Toast.makeText(context, "Canva graphic imported to Documents/iQubeCockpit!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Studio Header
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("studio_header_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Widgets, contentDescription = "Studio", tint = ElectricCyan, modifier = Modifier.size(26.dp))
                        Text(
                            text = "Customization Studio Sandbox",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Surface(
                        color = NeonMint.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "WYSIWYG Live Drag",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonMint,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Text(
                    text = "Live interactive editor for Android Widget, Control Center Tile & Notification. Drag elements freely, import Canva graphics, pick color themes, and save permanently into Android Documents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Target Surface Tabs (Widget vs Tile vs Notification)
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
                        editingTemplate = editingTemplate.copy(targetSurface = surface)
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

        // Preview Size Mode Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Preview:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            PreviewSizeMode.entries.forEach { sizeMode ->
                FilterChip(
                    selected = selectedSizeMode == sizeMode,
                    onClick = { selectedSizeMode = sizeMode },
                    label = { Text(sizeMode.title, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                        selectedLabelColor = ElectricCyan
                    )
                )
            }
        }

        // Live Interactive WYSIWYG Sandbox Preview Container
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCardElevated),
            shape = RoundedCornerShape(editingTemplate.cornerRadiusDp.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, SlateBorder),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wysiwyg_preview_container")
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE DRAGGABLE PREVIEW (${selectedTargetSurface.title})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap & drag element to position",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(selectedSizeMode.aspectRatio)
                        .clip(RoundedCornerShape(editingTemplate.cornerRadiusDp.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    runCatching { Color(android.graphics.Color.parseColor(editingTemplate.backgroundColorHex)) }.getOrDefault(Color(0xFF0B0F19)),
                                    runCatching { Color(android.graphics.Color.parseColor(editingTemplate.backgroundGradientEndHex)) }.getOrDefault(Color(0xFF0F172A))
                                )
                            )
                        )
                        .testTag("interactive_drag_canvas")
                ) {
                    val containerWidthPx = constraints.maxWidth.toFloat()
                    val containerHeightPx = constraints.maxHeight.toFloat()

                    // Optional Imported Canva Image Layer
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

                    // Render Draggable Elements
                    editingTemplate.elements.filter { it.visible }.forEach { element ->
                        val isSelected = element.id == selectedElementId
                        val elemColor = runCatching { Color(android.graphics.Color.parseColor(element.colorHex)) }.getOrDefault(Color.White)
                        val accentColor = runCatching { Color(android.graphics.Color.parseColor(editingTemplate.accentColorHex)) }.getOrDefault(ElectricCyan)

                        val xPx = (element.xPercent / 100f) * containerWidthPx
                        val yPx = (element.yPercent / 100f) * containerHeightPx

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                                .pointerInput(element.id) {
                                    detectDragGestures(
                                        onDragStart = { selectedElementId = element.id },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val newXPct = ((xPx + dragAmount.x) / containerWidthPx * 100f).coerceIn(0f, 92f)
                                            val newYPct = ((yPx + dragAmount.y) / containerHeightPx * 100f).coerceIn(0f, 90f)
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
        }

        // Background Theme & Canva Image Dump Controls
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("theme_controls_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Palette, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        Text("Theme & Background Presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }

                // Theme Presets Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                                    textColorHex = preset.textHex
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

                Divider(color = SlateBorder)

                // Canva / Custom Graphic Import Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Canva / Graphic Dump", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(
                            text = if (editingTemplate.backgroundImagePath.isNullOrBlank()) "Import local Canva template or PNG art" else "Custom art imported into Documents/iQubeCockpit",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
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

                if (!editingTemplate.backgroundImagePath.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Image Opacity Overlay", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text("${(editingTemplate.backgroundImageOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                        }
                        Slider(
                            value = editingTemplate.backgroundImageOpacity,
                            onValueChange = { editingTemplate = editingTemplate.copy(backgroundImageOpacity = it) },
                            valueRange = 0.1f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                        )
                    }
                }
            }
        }

        // Draggable Element Fine-Tuning & Styling
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("element_palette_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Layers, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                    Text("Component Elements & Placement", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                }

                val activeElement = editingTemplate.elements.firstOrNull { it.id == selectedElementId }

                if (activeElement != null) {
                    Surface(
                        color = SlateCardElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Editing: ${activeElement.label}",
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
                                }
                            }

                            // Font size slider
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Font Size", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text("${activeElement.fontSizeSp} sp", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
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
                                    valueRange = 10f..38f,
                                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                                )
                            }

                            // X and Y Coordinate controls
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Horizontal: ${activeElement.xPercent.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Slider(
                                        value = activeElement.xPercent,
                                        onValueChange = { newX ->
                                            editingTemplate = editingTemplate.copy(
                                                elements = editingTemplate.elements.map {
                                                    if (it.id == activeElement.id) it.copy(xPercent = newX) else it
                                                }
                                            )
                                        },
                                        valueRange = 0f..90f,
                                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Vertical: ${activeElement.yPercent.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Slider(
                                        value = activeElement.yPercent,
                                        onValueChange = { newY ->
                                            editingTemplate = editingTemplate.copy(
                                                elements = editingTemplate.elements.map {
                                                    if (it.id == activeElement.id) it.copy(yPercent = newY) else it
                                                }
                                            )
                                        },
                                        valueRange = 0f..90f,
                                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                                    )
                                }
                            }

                            // Color picker chips for selected element
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

                            if (activeElement.type == ElementType.CUSTOM_TEXT) {
                                OutlinedTextField(
                                    value = activeElement.customText,
                                    onValueChange = { newTxt ->
                                        editingTemplate = editingTemplate.copy(
                                            elements = editingTemplate.elements.map {
                                                if (it.id == activeElement.id) it.copy(customText = newTxt) else it
                                            }
                                        )
                                    },
                                    label = { Text("Custom Accent Text") },
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

                // Element Toggle List
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val col = runCatching { Color(android.graphics.Color.parseColor(elem.colorHex)) }.getOrDefault(Color.White)
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(col))
                                Text(elem.label, style = MaterialTheme.typography.bodySmall, color = if (elem.id == selectedElementId) ElectricCyan else TextPrimary)
                            }
                            Switch(
                                checked = elem.visible,
                                onCheckedChange = { isVis ->
                                    editingTemplate = editingTemplate.copy(
                                        elements = editingTemplate.elements.map {
                                            if (it.id == elem.id) it.copy(visible = isVis) else it
                                        }
                                    )
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = NeonMint, checkedTrackColor = NeonMint.copy(alpha = 0.3f)),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // Click Action Behavior Configuration
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("click_action_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                    Text("Interaction On Click", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
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

        // Shared Storage & Android Media/Documents Permanence Bar
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonMint.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().testTag("shared_storage_permanence_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.FolderShared, contentDescription = null, tint = NeonMint, modifier = Modifier.size(22.dp))
                        Text("Permanent Local Storage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
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

                Text(
                    text = "Saved in shared directory: $backupFolderPath/\nAll trips, charging sessions, custom templates, and imported Canva images remain intact even if the app is deleted and reinstalled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val ok = onBackupAllData()
                            val msg = if (ok) "Successfully backed up all data to $backupFolderPath!" else "Backup completed."
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
                            val msg = if (ok) "Restored all trips & templates from $backupFolderPath!" else "No prior backup found."
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

                // Apply to Live Widget & Save Template Button
                Button(
                    onClick = {
                        onSaveTemplate(editingTemplate)
                        Toast.makeText(context, "Applied to Live Widget, Tile & Notification!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_apply_template")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Apply to Live Widget, Tile & Notification",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
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
        ElementType.BATTERY_PERCENT -> {
            Text(
                text = if (isCharging) "⚡$batteryPct%" else "$batteryPct%",
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = sz, fontWeight = fw),
                color = elemColor
            )
        }
        ElementType.BATTERY_BAR -> {
            LinearProgressIndicator(
                progress = { batteryPct / 100f },
                color = if (batteryPct > 20) NeonMint else WarningAmber,
                trackColor = SlateBorder,
                modifier = Modifier.width(100.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
            )
        }
        ElementType.RANGE_KM -> {
            Text(
                text = "${rangeKm.roundToInt()} km DTE",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = sz, fontWeight = fw),
                color = elemColor
            )
        }
        ElementType.TRUE_RANGE_ECO_POWER -> {
            Text(
                text = "True: ${trueEcoKm.roundToInt()} km (Eco) • ${truePowerKm.roundToInt()} km (Pwr)",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = sz, fontWeight = fw),
                color = elemColor
            )
        }
        ElementType.STATUS_BADGE -> {
            Surface(
                color = if (isCharging) ElectricCyan.copy(alpha = 0.2f) else NeonMint.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = if (isCharging) "⚡ Charging Active" else "● Connected Live",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = sz, fontWeight = fw),
                    color = if (isCharging) ElectricCyan else NeonMint,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        ElementType.VEHICLE_TITLE -> {
            Text(
                text = "TVS iQube Cockpit",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = sz, fontWeight = fw),
                color = elemColor
            )
        }
        ElementType.CHARGING_ETA_OR_TIME -> {
            Text(
                text = if (isCharging) "To 80%: 42m • Full: 1h 15m" else "Battery Health: 99%",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = sz, fontWeight = fw),
                color = elemColor
            )
        }
        ElementType.LAST_PARKED_LOCATION -> {
            Text(
                text = "📍 $localityName",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = sz, fontWeight = fw),
                color = elemColor
            )
        }
        ElementType.CUSTOM_TEXT -> {
            Text(
                text = element.customText.ifBlank { "Smart Electric Mobility" },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = sz, fontWeight = fw),
                color = elemColor
            )
        }
    }
}
