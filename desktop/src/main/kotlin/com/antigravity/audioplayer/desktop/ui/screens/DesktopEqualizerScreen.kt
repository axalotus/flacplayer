package com.antigravity.audioplayer.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.desktop.audio.DesktopEqualizer
import com.antigravity.audioplayer.desktop.theme.AccentPrimary
import com.antigravity.audioplayer.desktop.theme.ObsidianSurfaceVariant

@Composable
fun DesktopEqualizerScreen(
    modifier: Modifier = Modifier
) {
    val gains by DesktopEqualizer.currentGains.collectAsState()
    val isEnabled by DesktopEqualizer.isEnabled.collectAsState()
    val selectedPresetName by DesktopEqualizer.selectedPresetName.collectAsState()
    val customPresets by DesktopEqualizer.customPresets.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    var presetDropdownExpanded by remember { mutableStateOf(false) }

    val allPresets = remember(customPresets) {
        DesktopEqualizer.STOCK_PRESETS + customPresets
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp)
    ) {
        // ── TOP HEADER ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "10-полосный DSP Эквалайзер",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Параметрическая IIR фильтрация с точностью до 0.1 dB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Включен:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { DesktopEqualizer.setEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = AccentPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── PRESETS BAR ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Preset Dropdown Box
            Box {
                OutlinedButton(
                    onClick = { presetDropdownExpanded = true },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Пресет: $selectedPresetName")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }

                DropdownMenu(
                    expanded = presetDropdownExpanded,
                    onDismissRequest = { presetDropdownExpanded = false }
                ) {
                    allPresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name, fontWeight = if (preset.name == selectedPresetName) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                DesktopEqualizer.applyPreset(preset)
                                presetDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Reset to 0 dB
                OutlinedButton(
                    onClick = {
                        DesktopEqualizer.applyPreset(DesktopEqualizer.STOCK_PRESETS.first())
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Сброс (0 dB)")
                }

                // Save Custom Preset
                Button(
                    onClick = {
                        newPresetName = ""
                        showSaveDialog = true
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Сохранить пресет")
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // ── 10 EQUALIZER SLIDERS ─────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = ObsidianSurfaceVariant,
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val freqLabels = listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")

                for (i in 0 until 10) {
                    val gain = gains[i]

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxHeight().weight(1f)
                    ) {
                        // dB Readout
                        Text(
                            text = String.format("%+.1f dB", gain),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (gain != 0f) AccentPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Vertical Slider (using Slider rotated or Column height)
                        Box(
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Slider(
                                value = gain,
                                onValueChange = { newGain ->
                                    DesktopEqualizer.setBandGain(i, newGain)
                                },
                                valueRange = -12f..12f,
                                modifier = Modifier.height(180.dp),
                                enabled = isEnabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = AccentPrimary,
                                    activeTrackColor = AccentPrimary,
                                    inactiveTrackColor = Color(0xFF333333)
                                )
                            )
                        }

                        // Frequency Label
                        Text(
                            text = freqLabels[i],
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    // ── SAVE PRESET DIALOG ───────────────────────────────────────────────────
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Сохранить пресет эквалайзера") },
            text = {
                Column {
                    Text("Введите название для вашего пресета:", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        placeholder = { Text("Мой пресет") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPresetName.isNotBlank()) {
                            DesktopEqualizer.saveCustomPreset(newPresetName.trim())
                            showSaveDialog = false
                        }
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
