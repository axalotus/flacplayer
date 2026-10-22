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
                            text = "Audio Signal Chain",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Poweramp-Style Sound Pipeline",
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
            ChainStageItem(
                stepNumber = 1,
                title = "Source File",
                subtitle = "${metadata.format.name} Lossless Media",
                icon = Icons.Default.AudioFile,
                accentColor = MaterialTheme.colorScheme.primary,
                details = listOf(
                    "Format" to metadata.format.name,
                    "Bit Depth" to "${metadata.bitDepth}-bit",
                    "Sample Rate" to metadata.sampleRateKHzFormatted,
                    "Bitrate" to "${metadata.bitrateKbps} kbps"
                ),
                isHiRes = metadata.bitDepth >= 24 || metadata.sampleRateHz >= 88200
            )

            ConnectorLine(color = MaterialTheme.colorScheme.primary)

            // Stage 2: Decoder Engine
            ChainStageItem(
                stepNumber = 2,
                title = "Audio Decoder",
                subtitle = if (metadata.format == com.antigravity.audioplayer.data.model.AudioFormat.FLAC) "Media3 Native FLAC Decoder" else "Android MediaCodec Engine",
                icon = Icons.Default.Memory,
                accentColor = MaterialTheme.colorScheme.secondary,
                details = listOf(
                    "Decoder Engine" to "Media3 High-Fidelity",
                    "Output Format" to "PCM Uncompressed",
                    "Channels" to if (metadata.channels == 2) "Stereo 2.0" else "Mono 1.0"
                )
            )

            ConnectorLine(color = MaterialTheme.colorScheme.secondary)

            // Stage 3: Resampler / DSP Processing
            ChainStageItem(
                stepNumber = 3,
                title = "DSP & Resampler",
                subtitle = "32-Bit Float High-Precision Pipeline",
                icon = Icons.Default.Tune,
                accentColor = MaterialTheme.colorScheme.tertiary,
                details = listOf(
                    "Internal Precision" to "32-bit Floating Point",
                    "Processing Rate" to metadata.sampleRateKHzFormatted,
                    "Resampler Mode" to "Bit-Perfect Passthrough"
                ),
                isHiRes = true
            )

            ConnectorLine(color = MaterialTheme.colorScheme.tertiary)

            // Stage 4: Equalizer & Effects
            ChainStageItem(
                stepNumber = 4,
                title = "10-Band EQ & FX Engine",
                subtitle = if (isEqActive) "Active Preset: $eqPresetName" else "Bypassed / Linear",
                icon = Icons.Default.Equalizer,
                accentColor = if (isEqActive) MaterialTheme.colorScheme.primary else Color.Gray,
                details = listOf(
                    "Equalizer State" to if (isEqActive) "ENABLED ($eqPresetName)" else "DISABLED",
                    "Bass Boost" to if (bassBoostVal > 0) "+${bassBoostVal / 10}% Gain" else "Off",
                    "DSP Bands" to "10 Bands (31Hz - 16kHz)"
                )
            )

            ConnectorLine(color = MaterialTheme.colorScheme.primary)

            // Stage 5: Audio Output Driver
            ChainStageItem(
                stepNumber = 5,
                title = "Output Driver",
                subtitle = "AAudio Low-Latency Engine",
                icon = Icons.Default.Speed,
                accentColor = MaterialTheme.colorScheme.secondary,
                details = listOf(
                    "Audio API" to "AAudio / AudioTrack Direct",
                    "Performance Mode" to "Low Latency High-Res",
                    "Buffer Size" to "192 frames (Optimal)"
                )
            )

            ConnectorLine(color = MaterialTheme.colorScheme.secondary)

            // Stage 6: Destination DAC & Headphones (Fixed Text Layout)
            ChainStageItem(
                stepNumber = 6,
                title = "Output Device / ЦАП",
                subtitle = dacInfo.deviceName,
                icon = if (dacInfo.isExternalDac) Icons.Default.Usb else if (dacInfo.deviceType.contains("Bluetooth")) Icons.Default.Bluetooth else Icons.Default.Headphones,
                accentColor = if (dacInfo.isHiResSupported) Color(0xFFFFB703) else MaterialTheme.colorScheme.primary,
                details = listOf(
                    "Hardware Target" to dacInfo.deviceName,
                    "Interface Type" to dacInfo.connectionType,
                    "DAC Support" to if (dacInfo.isExternalDac) "External Hi-Res DAC Detected" else "Standard Audio Sink"
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
