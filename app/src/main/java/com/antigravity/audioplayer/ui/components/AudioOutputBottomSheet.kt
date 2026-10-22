package com.antigravity.audioplayer.ui.components

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.audio.DacDetector
import com.antigravity.audioplayer.audio.DacOutputInfo
import com.antigravity.audioplayer.audio.NativeAudioEngine

data class AudioOutputItem(
    val id: Int,
    val name: String,
    val typeDescription: String,
    val icon: ImageVector,
    val isSelected: Boolean,
    val rawDevice: AudioDeviceInfo?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputBottomSheet(
    currentDacInfo: DacOutputInfo,
    onDismissRequest: () -> Unit,
    onDeviceSelected: () -> Unit = {}
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val outputDevices = remember(currentDacInfo) {
        getAvailableOutputDevices(context, audioManager, currentDacInfo)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Вывод звука",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Прямое воспроизведение медиа-потока",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.SpeakerGroup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Devices list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(outputDevices, key = { it.id }) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                DacDetector.getInstance(context).selectOutputDevice(item.rawDevice)
                                onDeviceSelected()
                                onDismissRequest()
                            },
                        color = if (item.isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (item.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = if (item.isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontSize = 15.sp,
                                    fontWeight = if (item.isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (item.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.typeDescription,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            if (item.isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Активно",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Option to open system Bluetooth settings for pairing new devices
            OutlinedButton(
                onClick = {
                    DacDetector.launchMediaOutputSwitcher(context)
                    onDismissRequest()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Подключить новое устройство", fontSize = 13.sp)
            }
        }
    }
}

private fun getAvailableOutputDevices(
    context: Context,
    audioManager: AudioManager,
    currentDacInfo: DacOutputInfo
): List<AudioOutputItem> {
    val items = mutableListOf<AudioOutputItem>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val rawDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        
        // Exclude telephony, SCO (voice calls), earpieces, and non-audio sinks
        val excludedTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_FM,
            AudioDeviceInfo.TYPE_FM_TUNER,
            AudioDeviceInfo.TYPE_TV_TUNER,
            AudioDeviceInfo.TYPE_IP,
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX
        )

        // Filter and deduplicate by hardware address / type + name
        val mediaDevices = rawDevices
            .filter { it.isSink && it.type !in excludedTypes }
            .distinctBy { device ->
                val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else null
                if (!address.isNullOrBlank()) address else "${device.type}_${device.productName}"
            }

        // 1. Bluetooth devices (A2DP / BLE Audio)
        for (device in mediaDevices) {
            val type = device.type
            val isBluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                              (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                              (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_SPEAKER) ||
                              (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
            if (isBluetooth) {
                val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth-наушники"
                val isSelected = (currentDacInfo.deviceId == device.id) || 
                                 (currentDacInfo.deviceId == 0 && currentDacInfo.deviceType.contains("Bluetooth") && currentDacInfo.deviceName == name)
                items.add(
                    AudioOutputItem(
                        id = device.id,
                        name = name,
                        typeDescription = "Bluetooth A2DP (Media Stream)",
                        icon = Icons.Default.Bluetooth,
                        isSelected = isSelected,
                        rawDevice = device
                    )
                )
            }
        }

        // 2. USB DAC
        for (device in mediaDevices) {
            val type = device.type
            val isUsb = type == AudioDeviceInfo.TYPE_USB_DEVICE || 
                         type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                         (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_USB_ACCESSORY)
            if (isUsb) {
                val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Внешний USB ЦАП"
                val isSelected = (currentDacInfo.deviceId == device.id) ||
                                 (currentDacInfo.deviceId == 0 && currentDacInfo.isExternalDac)
                items.add(
                    AudioOutputItem(
                        id = device.id,
                        name = name,
                        typeDescription = "USB Direct DAC (Bit-Perfect)",
                        icon = Icons.Default.Usb,
                        isSelected = isSelected,
                        rawDevice = device
                    )
                )
            }
        }

        // 3. Wired Headphones / AUX
        for (device in mediaDevices) {
            val type = device.type
            val isWired = type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                          type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                          type == AudioDeviceInfo.TYPE_AUX_LINE ||
                          type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                          type == AudioDeviceInfo.TYPE_LINE_DIGITAL
            if (isWired) {
                val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Наушники (3.5мм)"
                val isSelected = (currentDacInfo.deviceId == device.id) ||
                                 (currentDacInfo.deviceId == 0 && currentDacInfo.deviceType.contains("Analog"))
                items.add(
                    AudioOutputItem(
                        id = device.id,
                        name = name,
                        typeDescription = "Аналоговый разъём 3.5мм",
                        icon = Icons.Default.Headphones,
                        isSelected = isSelected,
                        rawDevice = device
                    )
                )
            }
        }

        // 4. Built-in Speaker (Always present as primary media speaker)
        val speakerDevice = mediaDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val isSpeakerSelected = (speakerDevice != null && currentDacInfo.deviceId == speakerDevice.id) ||
                                (currentDacInfo.deviceId == 0 && currentDacInfo.deviceName.contains("Динамик")) ||
                                items.none { it.isSelected }
        items.add(
            AudioOutputItem(
                id = speakerDevice?.id ?: 0,
                name = "Динамик телефона",
                typeDescription = "Встроенная стерео-акустика",
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                isSelected = isSpeakerSelected,
                rawDevice = speakerDevice
            )
        )
    } else {
        items.add(
            AudioOutputItem(
                id = 0,
                name = "Динамик телефона",
                typeDescription = "Системный вывод по умолчанию",
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                isSelected = true,
                rawDevice = null
            )
        )
    }

    return items
}

private fun routeAudioToDevice(item: AudioOutputItem) {
    try {
        // Purely route through Oboe AudioStreamBuilder::setDeviceId without touching AudioManager communication mode
        NativeAudioEngine.setAudioDeviceId(item.id)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
