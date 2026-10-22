package com.antigravity.audioplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.audio.EqBand
import com.antigravity.audioplayer.audio.EqualizerController

@Composable
fun EqualizerScreen(
    eqController: EqualizerController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bands by eqController.bands.collectAsState()
    val isEnabled by eqController.isEnabled.collectAsState()
    val bassBoostStrength by eqController.bassBoostStrength.collectAsState()
    val selectedPreset by eqController.selectedPresetName.collectAsState()
    val allPresets by eqController.allPresets.collectAsState()
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    val context = LocalContext.current

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // ── HEADER ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "10-Полосный Эквалайзер",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "DSP Audio Processor",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // ON/OFF toggle
            val bgColor by animateColorAsState(
                targetValue = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                label = "eq_toggle"
            )
            Surface(
                onClick = { eqController.setEnabled(!isEnabled) },
                color = bgColor,
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = "EQ On/Off",
                        tint = if (isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { eqController.applyPreset(eqController.presets.first()) }) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Сброс", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        // ── CURVE DISPLAY ────────────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(90.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (bands.isEmpty()) return@Canvas
                val w = size.width
                val h = size.height
                val midY = h / 2f

                // Center line
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1f
                )

                // Build smooth curve
                val pts = bands.mapIndexed { i, band ->
                    val x = (i.toFloat() / (bands.size - 1).coerceAtLeast(1)) * w
                    val norm = band.gainDb.coerceIn(-12f, 12f) / 12f
                    val y = midY - norm * (h / 2f - 6f)
                    Offset(x, y)
                }

                if (pts.size >= 2) {
                    // Filled area
                    val fillPath = Path().apply {
                        moveTo(pts.first().x, midY)
                        pts.forEach { lineTo(it.x, it.y) }
                        lineTo(pts.last().x, midY)
                        close()
                    }
                    drawPath(fillPath, color = primary.copy(alpha = 0.18f))

                    // Stroke curve
                    val curvePath = Path().apply {
                        moveTo(pts.first().x, pts.first().y)
                        for (i in 1 until pts.size) {
                            val prev = pts[i - 1]
                            val curr = pts[i]
                            val cx = (prev.x + curr.x) / 2f
                            cubicTo(cx, prev.y, cx, curr.y, curr.x, curr.y)
                        }
                    }
                    drawPath(
                        curvePath,
                        color = if (isEnabled) primary else Color.Gray,
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Points
                    pts.forEach { pt ->
                        drawCircle(color = if (isEnabled) primary else Color.Gray, radius = 3f, center = pt)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── PRESETS HEADER & SAVE BUTTON ────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Пресеты звучания",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(
                onClick = {
                    newPresetName = if (selectedPreset == "Custom" || selectedPreset.isBlank()) "Мой пресет" else selectedPreset
                    showSavePresetDialog = true
                },
                enabled = isEnabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Сохранить свой", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ── PRESET CHIPS ────────────────────────────────────────────────────
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(allPresets, key = { it.name + "_" + it.isCustom }) { preset ->
                val isSel = preset.name == selectedPreset
                FilterChip(
                    selected = isSel,
                    onClick = { eqController.applyPreset(preset) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                preset.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                            if (preset.isCustom) {
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { eqController.deleteCustomPreset(preset) },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Удалить пресет",
                                        tint = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    },
                    enabled = isEnabled,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 10 VERTICAL BAND SLIDERS ─────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                bands.forEach { band ->
                    VerticalEqBand(
                        band = band,
                        enabled = isEnabled,
                        primaryColor = primary,
                        onGainChange = { eqController.setBandGain(band.id, it) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── BASS BOOST ────────────────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "BASS BOOST",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        "${bassBoostStrength / 10}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Slider(
                    value = bassBoostStrength.toFloat(),
                    onValueChange = { eqController.setBassBoost(it.toInt()) },
                    valueRange = 0f..1000f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.secondary,
                        activeTrackColor = MaterialTheme.colorScheme.secondary,
                        inactiveTrackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                    )
                )
            }
        }

        if (showSavePresetDialog) {
            AlertDialog(
                onDismissRequest = { showSavePresetDialog = false },
                title = { Text("Сохранить пресет") },
                text = {
                    Column {
                        Text("Введите название для текущей настройки эквалайзера:", fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newPresetName,
                            onValueChange = { newPresetName = it },
                            singleLine = true,
                            placeholder = { Text("Например: Deep Bass, Studio...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newPresetName.isNotBlank()) {
                                val saved = eqController.saveCustomPreset(newPresetName)
                                if (saved) {
                                    android.widget.Toast.makeText(context, "Пресет '$newPresetName' сохранен", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                showSavePresetDialog = false
                            }
                        }
                    ) {
                        Text("Сохранить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSavePresetDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

/** Custom vertical EQ band slider using Canvas + touch */
@Composable
private fun VerticalEqBand(
    band: EqBand,
    enabled: Boolean,
    primaryColor: Color,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var heightPx by remember { mutableFloatStateOf(0f) }
    val sliderHeightDp = 140.dp
    val gainNorm = (band.gainDb + 12f) / 24f  // 0..1

    val activeColor = if (band.gainDb > 0) primaryColor
                      else if (band.gainDb < 0) MaterialTheme.colorScheme.error
                      else Color.Gray

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Gain value label
        Text(
            text = if (band.gainDb == 0f) "0" else if (band.gainDb > 0) "+${String.format("%.1f", band.gainDb)}" else String.format("%.1f", band.gainDb),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) activeColor.copy(alpha = 0.9f) else Color.Gray,
            lineHeight = 10.sp
        )

        // Custom vertical slider track
        Box(
            modifier = Modifier
                .width(26.dp)
                .height(sliderHeightDp)
                .clip(RoundedCornerShape(13.dp))
                .onGloballyPositioned { heightPx = it.size.height.toFloat() }
                .pointerInput(enabled, heightPx) {
                    if (!enabled || heightPx <= 0f) return@pointerInput
                    var currentGain = band.gainDb
                    detectVerticalDragGestures(
                        onDragStart = { currentGain = band.gainDb },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val delta = -dragAmount / heightPx * 24f
                            currentGain = (currentGain + delta).coerceIn(-12f, 12f)
                            onGainChange(currentGain)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val trackTop = 6f
                val trackBottom = size.height - 6f
                val trackH = trackBottom - trackTop
                val midY = size.height / 2f

                // Background track
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(cx, trackTop),
                    end = Offset(cx, trackBottom),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )

                // Center zero marker
                drawLine(
                    color = Color.White.copy(alpha = 0.2f),
                    start = Offset(cx - 5f, midY),
                    end = Offset(cx + 5f, midY),
                    strokeWidth = 1.5f
                )

                if (enabled) {
                    // Thumb position (top = max, bottom = min)
                    val thumbY = trackTop + (1f - gainNorm) * trackH
                    val activeTop = minOf(thumbY, midY)
                    val activeBot = maxOf(thumbY, midY)

                    // Active fill
                    drawLine(
                        color = activeColor.copy(alpha = 0.7f),
                        start = Offset(cx, activeTop),
                        end = Offset(cx, activeBot),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )

                    // Thumb
                    drawCircle(
                        color = activeColor,
                        radius = 14f,
                        center = Offset(cx, thumbY.coerceIn(trackTop, trackBottom))
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = 5f,
                        center = Offset(cx, thumbY.coerceIn(trackTop, trackBottom))
                    )
                }
            }
        }

        // Frequency label
        Text(
            text = band.formattedFreq,
            fontSize = 8.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            lineHeight = 10.sp
        )
    }
}
