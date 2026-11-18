package com.zuna.player.audio

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class DacOutputInfo(
    val deviceId: Int = 0,
    val deviceName: String,
    val deviceType: String,
    val isExternalDac: Boolean,
    val isHiResSupported: Boolean,
    val connectionType: String, // USB, Bluetooth, Wired, Internal
    val sampleRates: List<Int>,
    val channelCounts: List<Int>
)

@Stable
class DacDetector private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isCallbackRegistered = false

    // User's explicitly selected device ID (null = automatic routing by OS / highest priority)
    private var userSelectedDeviceId: Int? = null

    private val _activeDeviceFlow = MutableStateFlow(detectActiveOutputDevice())
    val activeDeviceFlow: StateFlow<DacOutputInfo> = _activeDeviceFlow.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateDevice()
            if (userSelectedDeviceId == null) {
                com.zuna.player.service.AudioPlayerService.refreshAudioRouteForOpenSL()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // If the currently selected device was disconnected, reset to default speaker
            if (userSelectedDeviceId != null && removedDevices != null) {
                val wasRemoved = removedDevices.any { it.id == userSelectedDeviceId }
                if (wasRemoved) {
                    userSelectedDeviceId = null
                    NativeAudioEngine.setAudioDeviceId(0)
                }
            } else if (userSelectedDeviceId == null) {
                com.zuna.player.service.AudioPlayerService.refreshAudioRouteForOpenSL()
            }
            updateDevice()
        }
    }

    init {
        ensureCallbackRegistered()
    }

    @Synchronized
    fun ensureCallbackRegistered() {
        if (!isCallbackRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
                isCallbackRegistered = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    fun release() {
        if (isCallbackRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isCallbackRegistered = false
            }
        }
    }

    /**
     * User explicitly selected an audio output device in AudioOutputBottomSheet
     */
    fun selectOutputDevice(device: AudioDeviceInfo?) {
        if (device == null || device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            // Explicitly selected speaker
            val speakerDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else null
            val id = speakerDevice?.id ?: 0
            userSelectedDeviceId = id
            NativeAudioEngine.setAudioDeviceId(id)
            _activeDeviceFlow.value = createSpeakerDacInfo(id)
        } else {
            // Selected Bluetooth / USB / Wired device
            userSelectedDeviceId = device.id
            NativeAudioEngine.setAudioDeviceId(device.id)
            _activeDeviceFlow.value = createDacInfoForDevice(device)
        }
    }

    fun updateDevice() {
        _activeDeviceFlow.value = detectActiveOutputDevice()
    }

    fun detectActiveOutputDevice(): DacOutputInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            // If user has an active manual selection and that device is still connected
            val currentSelectedId = userSelectedDeviceId
            if (currentSelectedId != null) {
                val matched = devices.firstOrNull { it.id == currentSelectedId }
                if (matched != null) {
                    return if (matched.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        createSpeakerDacInfo(matched.id)
                    } else {
                        createDacInfoForDevice(matched)
                    }
                } else {
                    // Device was disconnected! Reset selection to speaker
                    userSelectedDeviceId = null
                    NativeAudioEngine.setAudioDeviceId(0)
                }
            }
            
            // Automatic priority detection:
            // 1. USB DAC
            for (device in devices) {
                val type = device.type
                val isUsb = type == AudioDeviceInfo.TYPE_USB_DEVICE || 
                             type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                             (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_USB_ACCESSORY)
                if (isUsb) {
                    return createDacInfoForDevice(device)
                }
            }

            // 2. Bluetooth Headset / A2DP (excluding SCO)
            for (device in devices) {
                val type = device.type
                val isBluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                  (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                                  (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_SPEAKER) ||
                                  (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
                if (isBluetooth) {
                    return createDacInfoForDevice(device)
                }
            }

            // 3. Wired Headset / AUX
            for (device in devices) {
                val type = device.type
                val isWired = type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                              type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                              type == AudioDeviceInfo.TYPE_AUX_LINE ||
                              type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                              type == AudioDeviceInfo.TYPE_LINE_DIGITAL
                if (isWired) {
                    return createDacInfoForDevice(device)
                }
            }

            // 4. Built-in Speaker
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            return createSpeakerDacInfo(speaker?.id ?: 0)
        }

        return createSpeakerDacInfo(0)
    }

    private fun createSpeakerDacInfo(id: Int): DacOutputInfo {
        return DacOutputInfo(
            deviceId = id,
            deviceName = "Динамик телефона",
            deviceType = "Internal Audio",
            isExternalDac = false,
            isHiResSupported = false,
            connectionType = "Встроенный динамик смартфона",
            sampleRates = listOf(44100, 48000),
            channelCounts = listOf(2)
        )
    }

    private fun createDacInfoForDevice(device: AudioDeviceInfo): DacOutputInfo {
        val type = device.type
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() }

        val isUsb = type == AudioDeviceInfo.TYPE_USB_DEVICE || 
                     type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                     (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_USB_ACCESSORY)
        if (isUsb) {
            val sampleRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.sampleRates.toList().ifEmpty { listOf(44100, 48000, 96000, 192000) }
            } else listOf(44100, 48000)
            return DacOutputInfo(
                deviceId = device.id,
                deviceName = name ?: "Внешний USB ЦАП",
                deviceType = "USB Direct DAC",
                isExternalDac = true,
                isHiResSupported = true,
                connectionType = "USB Audio Class 2.0 (Direct PCM)",
                sampleRates = sampleRates,
                channelCounts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.channelCounts.toList().ifEmpty { listOf(2) } else listOf(2)
            )
        }

        val isBluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                          (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                          (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        if (isBluetooth) {
            return DacOutputInfo(
                deviceId = device.id,
                deviceName = name ?: "Bluetooth-наушники",
                deviceType = "Bluetooth Audio",
                isExternalDac = false,
                isHiResSupported = true,
                connectionType = "Bluetooth A2DP (Media Stream)",
                sampleRates = listOf(44100, 48000, 96000),
                channelCounts = listOf(2)
            )
        }

        // Wired
        return DacOutputInfo(
            deviceId = device.id,
            deviceName = name ?: "Наушники (3.5мм)",
            deviceType = "Analog Headphone Out",
            isExternalDac = false,
            isHiResSupported = true,
            connectionType = "Direct 3.5mm Analog Output",
            sampleRates = listOf(44100, 48000, 96000),
            channelCounts = listOf(2)
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: DacDetector? = null

        fun getInstance(context: Context): DacDetector {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: DacDetector(context.applicationContext).also { INSTANCE = it }
            }
            instance.ensureCallbackRegistered()
            return instance
        }

        /**
         * Launches the system Media Output Switcher dialog on Android 11+ (API 30+),
         * or falls back to Bluetooth / Sound settings.
         */
        fun launchMediaOutputSwitcher(context: Context) {
            var launched = false

            // Strategy 1: Android 11+ (API 30+) Settings Panel ACTION_MEDIA_OUTPUT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent("android.settings.panel.action.MEDIA_OUTPUT").apply {
                        putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                        putExtra("extra_package_name", context.packageName)
                        putExtra("android.provider.extra.PACKAGE_NAME", context.packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    launched = true
                } catch (_: Exception) {}
            }

            // Strategy 2: com.android.settings.panel.action.MEDIA_OUTPUT
            if (!launched) {
                try {
                    val intent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                        putExtra("extra_package_name", context.packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    launched = true
                } catch (_: Exception) {}
            }

            // Strategy 3: Direct Bluetooth Settings Picker
            if (!launched) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    launched = true
                } catch (_: Exception) {}
            }

            // Strategy 4: Sound Settings
            if (!launched) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }
}
