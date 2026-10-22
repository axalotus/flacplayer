package com.antigravity.audioplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.audio.DacOutputInfo
import com.antigravity.audioplayer.data.model.AudioFileMetadata

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioChainModalSheet(
    metadata: AudioFileMetadata,
    dacInfo: DacOutputInfo,
    isEqActive: Boolean,
    eqPresetName: String,
    bassBoostVal: Int,
    engineMode: Int = 0,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = "Audio Chain",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "Тракт аудиосигнала",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "C++ NDK Engine • Oboe • Float32 Pipeline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stage 1: Source Audio File
            val isSourceHiRes = metadata.bitDepth >= 24 || metadata.sampleRateHz >= 88200
            ChainStageItem(
                stepNumber = 1,
                title = "Исходный файл",
                subtitle = "${metadata.format.name} (${if (isSourceHiRes) "Hi-Res Audio" else "Standard Audio"})",
                icon = Icons.Default.AudioFile,
                accentColor = MaterialTheme.colorScheme.primary,
                details = listOf(
                    "Формат" to metadata.format.name,
                    "Разрядность" to if (metadata.bitDepth > 0) "${metadata.bitDepth}-бит" else "Недоступно",
                    "Частота дискретизации" to if (metadata.sampleRateHz > 0) metadata.sampleRateKHzFormatted else "Недоступно",
                    "Битрейт" to if (metadata.bitrateKbps > 0) "${metadata.bitrateKbps} кбит/с" else "VBR / Неизвестно",
                    "Каналы" to when (metadata.channels) {
                        2 -> "Стерео (2.0)"
                        1 -> "Моно (1.0)"
                        0 -> "Неизвестно"
                        else -> "${metadata.channels} кан."
                    }
                ),
                isHiRes = isSourceHiRes
            )

            ConnectorLine(color = MaterialTheme.colorScheme.primary)

            // Stage 2: C++ NDK Audio Decoder
            val decoderName = when (metadata.format) {
                com.antigravity.audioplayer.data.model.AudioFormat.FLAC -> "dr_flac v0.12 (David Reid)"
                com.antigravity.audioplayer.data.model.AudioFormat.WAV -> "dr_wav v0.13 (David Reid)"
                com.antigravity.audioplayer.data.model.AudioFormat.MP3 -> "minimp3_ex (Lieff)"
                else -> "Android NDK Decoder Engine"
            }
            ChainStageItem(
                stepNumber = 2,
                title = "C++ Аудиодекодер",
                subtitle = decoderName,
                icon = Icons.Default.Memory,
                accentColor = MaterialTheme.colorScheme.secondary,
                details = listOf(
                    "Движок декодирования" to decoderName,
                    "Выходной формат" to "IEEE 754 32-bit Float PCM",
                    "Каналы декодера" to when (metadata.channels) {
                        2 -> "Стерео (2.0)"
                        1 -> "Моно (1.0)"
                        else -> "${metadata.channels} кан."
                    },
                    "Частота дискретизации" to if (metadata.sampleRateHz > 0) metadata.sampleRateKHzFormatted else "Исходная"
                )
            )

            ConnectorLine(color = MaterialTheme.colorScheme.secondary)

            // Stage 3: Ring Buffer & Precision
            ChainStageItem(
                stepNumber = 3,
                title = "Кольцевой буфер и точность",
                subtitle = "Lock-Free 384k Buffer • 32-Bit Float",
                icon = Icons.Default.Tune,
                accentColor = MaterialTheme.colorScheme.tertiary,
                details = listOf(
                    "Внутренняя точность" to "32-bit Floating Point (IEEE 754)",
                    "Кольцевой буфер" to "384 000 сэмплов (Lock-Free)",
                    "Ресемплинг" to if (engineMode == 2) "Отключён (Native Direct)" else "Системный (при необходимости)",
                    "Потери разрядности" to "Отсутствуют (32-bit Float)"
                ),
                isHiRes = isSourceHiRes
            )

            ConnectorLine(color = MaterialTheme.colorScheme.tertiary)

            // Stage 4: Equalizer & Effects
            ChainStageItem(
                stepNumber = 4,
                title = "10-полосный эквалайзер и DSP",
                subtitle = if (isEqActive) "Активен пресет: $eqPresetName" else "Отключён (Bypass / Линейный)",
                icon = Icons.Default.Equalizer,
                accentColor = if (isEqActive) MaterialTheme.colorScheme.primary else Color.Gray,
                details = listOf(
                    "Статус EQ" to if (isEqActive) "ВКЛЮЧЁН ($eqPresetName)" else "ОТКЛЮЧЁН (Bypass)",
                    "Усиление баса" to if (bassBoostVal > 0) "+${bassBoostVal / 10}% усиление" else "Выключено",
                    "Фильтры" to "10 полос Biquad IIR (31 Гц – 16 кГц)",
                    "DSP движок" to "In-place Float32 DSP в C++"
                )
            )

            ConnectorLine(color = MaterialTheme.colorScheme.primary)

            // Stage 5: Audio Output Driver (Oboe C++)
            val driverTitle = when (engineMode) {
                1 -> "OpenSL ES Engine"
                2 -> "AAudio Direct Hi-Res (No SRC)"
                else -> "AAudio Low-Latency Engine"
            }
            ChainStageItem(
                stepNumber = 5,
                title = "Драйвер вывода звука",
                subtitle = driverTitle,
                icon = Icons.Default.Speed,
                accentColor = MaterialTheme.colorScheme.secondary,
                details = listOf(
                    "Аудио API" to when (engineMode) {
                        1 -> "OpenSL ES (Oboe NDK)"
                        2 -> "AAudio Hi-Res (Oboe NDK)"
                        else -> "AAudio Low-Latency (Oboe NDK)"
                    },
                    "Режим производительности" to if (engineMode == 1) "Standard" else "Low Latency",
                    "Режим разделения" to "Shared (AudioFlinger)",
                    "SRC (ресемплинг)" to if (engineMode == 2) "None (Exact Sample Rate)" else "Optimal"
                )
            )

            ConnectorLine(color = MaterialTheme.colorScheme.secondary)

            // Stage 6: Destination DAC & Headphones
            ChainStageItem(
                stepNumber = 6,
                title = "Устройство вывода / ЦАП",
                subtitle = dacInfo.deviceName.ifBlank { "Системный вывод" },
                icon = if (dacInfo.isExternalDac) Icons.Default.Usb else if (dacInfo.deviceType.contains("Bluetooth", ignoreCase = true)) Icons.Default.Bluetooth else Icons.Default.Headphones,
                accentColor = if (dacInfo.isHiResSupported) Color(0xFFFFB703) else MaterialTheme.colorScheme.primary,
                details = listOf(
                    "Целевое устройство" to dacInfo.deviceName.ifBlank { "Системный аудиовыход" },
                    "Интерфейс" to dacInfo.connectionType.ifBlank { "Неизвестно" },
                    "Поддержка ЦАП" to if (dacInfo.isExternalDac) "Внешний Hi-Res USB ЦАП" else if (dacInfo.isHiResSupported) "Поддерживает Hi-Res" else "Стандартный аудиовыход"
                ),
                isHiRes = dacInfo.isHiResSupported
            )
        }
    }
}

@Composable
private fun ChainStageItem(
    stepNumber: Int,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    details: List<Pair<String, String>>,
    isHiRes: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accentColor.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(accentColor.copy(alpha = 0.5f), accentColor.copy(alpha = 0.15f))
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "STAGE $stepNumber: $title",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                            if (isHiRes) {
                                Surface(
                                    color = Color(0xFFFFB703).copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "HI-RES",
                                        color = Color(0xFFFFB703),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            HorizontalDivider(color = accentColor.copy(alpha = 0.15f))

            // Clean vertical list for detail key-values so long text never wraps character-by-character
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                details.forEach { (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = key,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = value,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectorLine(color: Color) {
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(14.dp)
            .background(
                Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.8f), color.copy(alpha = 0.2f))
                )
            )
    )
}
