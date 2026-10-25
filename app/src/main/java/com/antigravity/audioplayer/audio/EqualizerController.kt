package com.antigravity.audioplayer.audio

import android.content.Context
import android.util.Log
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class EqBand(
    val id: Int,
    val centerFreqHz: Int,
    val gainDb: Float, // Range e.g. -12.0 dB to +12.0 dB
    val minDb: Float = -12.0f,
    val maxDb: Float = 12.0f
) {
    val formattedFreq: String
        get() = if (centerFreqHz >= 1000) "${centerFreqHz / 1000}k" else "${centerFreqHz}Hz"
}

@Immutable
data class EqPreset(
    val name: String,
    val bandGainsDb: List<Float>,
    val bassBoostStrength: Int = 0,
    val isCustom: Boolean = false
)

@Stable
class EqualizerController {

    private val tag = "EqualizerController"
    private var appContext: Context? = null

    // Default 10-band equalizer frequencies
    private val defaultCenterFreqs = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    private val _bands = MutableStateFlow<List<EqBand>>(emptyList())
    val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(350) // 0 .. 1000
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    private val _selectedPresetName = MutableStateFlow("Flat")
    val selectedPresetName: StateFlow<String> = _selectedPresetName.asStateFlow()

    val stockPresets = listOf(
        EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0),
        EqPreset("Bass Boost", listOf(7.5f, 6.0f, 4.5f, 2.0f, 0f, -1.0f, 0f, 1.5f, 3.0f, 4.0f), 700),
        EqPreset("Bass Reducer", listOf(-6.0f, -5.0f, -3.5f, -2.0f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.0f), 0),
        EqPreset("Rock", listOf(5.0f, 3.5f, 2.0f, -1.0f, -2.0f, 0f, 2.5f, 4.0f, 5.5f, 6.0f), 400),
        EqPreset("Metal", listOf(6.0f, 4.5f, 2.0f, 0f, -2.5f, -1.0f, 2.0f, 4.5f, 6.5f, 7.0f), 500),
        EqPreset("Pop", listOf(-1.0f, 1.0f, 3.0f, 4.5f, 3.5f, 1.0f, -1.0f, -2.0f, 2.0f, 4.0f), 250),
        EqPreset("Hip-Hop", listOf(6.5f, 5.5f, 3.0f, 1.0f, -1.0f, 0f, 1.5f, -1.0f, 2.5f, 3.5f), 650),
        EqPreset("Electronic", listOf(5.5f, 4.0f, 1.5f, 0f, -2.0f, 2.0f, 1.5f, 3.0f, 4.5f, 5.5f), 600),
        EqPreset("Techno", listOf(6.0f, 5.0f, 2.5f, 0f, -1.5f, 1.0f, 2.5f, 3.5f, 5.0f, 5.5f), 600),
        EqPreset("Dance", listOf(4.0f, 6.0f, 3.0f, 0f, 2.0f, 3.0f, 4.0f, 3.0f, 2.0f, 0f), 550),
        EqPreset("Club", listOf(0f, 0f, 2.0f, 3.5f, 3.5f, 3.5f, 2.0f, 0f, 0f, 0f), 450),
        EqPreset("Jazz", listOf(3.0f, 2.0f, 1.0f, 2.0f, -1.5f, -1.5f, 0f, 1.5f, 3.0f, 4.5f), 300),
        EqPreset("Classical", listOf(5.0f, 4.0f, 3.0f, 2.0f, -1.0f, -1.0f, 0f, 2.0f, 3.5f, 4.0f), 100),
        EqPreset("Acoustic", listOf(4.5f, 3.5f, 1.5f, 1.0f, 2.0f, 2.0f, 3.5f, 4.0f, 3.5f, 2.5f), 200),
        EqPreset("Vocal Clarity", listOf(-2.0f, -1.0f, 0f, 2.5f, 4.0f, 5.0f, 4.0f, 2.0f, 0f, -2.0f), 150),
        EqPreset("Vocal Booster", listOf(-1.5f, -2.0f, -1.0f, 1.5f, 3.5f, 4.5f, 3.5f, 1.5f, 0f, -1.0f), 100),
        EqPreset("Deep", listOf(4.5f, 3.5f, 2.0f, 1.0f, 2.5f, 2.0f, 1.0f, -1.5f, -2.5f, -3.5f), 500),
        EqPreset("Lounge", listOf(-3.0f, -1.5f, 0f, 2.0f, 4.0f, 3.0f, 1.0f, -1.0f, 2.0f, 1.5f), 350),
        EqPreset("R&B", listOf(3.0f, 7.0f, 5.5f, 1.5f, -2.0f, -1.5f, 2.0f, 3.0f, 3.5f, 4.0f), 500),
        EqPreset("Treble Booster", listOf(-3.0f, -2.0f, -1.0f, 0f, 1.0f, 2.5f, 4.5f, 6.0f, 7.5f, 8.5f), 0),
        EqPreset("Treble Reducer", listOf(1.0f, 1.0f, 0.5f, 0f, -1.0f, -2.0f, -3.5f, -5.0f, -6.5f, -8.0f), 200),
        EqPreset("Live", listOf(-2.5f, 0f, 2.0f, 3.0f, 3.5f, 3.5f, 2.5f, 2.0f, 2.0f, 1.5f), 250)
    )

    private val _customPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val customPresets: StateFlow<List<EqPreset>> = _customPresets.asStateFlow()

    private val _allPresets = MutableStateFlow<List<EqPreset>>(stockPresets)
    val allPresets: StateFlow<List<EqPreset>> = _allPresets.asStateFlow()

    // Backward compatibility property
    val presets: List<EqPreset> get() = _allPresets.value

    init {
        initDefaultBands()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = LibraryPreferences.getInstance(context)
        
        loadCustomPresets(prefs)

        val savedEnabled = prefs.getEqualizerEnabled()
        val savedBass = prefs.getEqualizerBassBoost()
        val savedPreset = prefs.getEqualizerPresetName()
        val savedGains = prefs.getEqualizerBandGains()

        _isEnabled.value = savedEnabled
        _bassBoostStrength.value = savedBass
        _selectedPresetName.value = savedPreset

        if (savedGains != null && savedGains.size == defaultCenterFreqs.size) {
            _bands.value = defaultCenterFreqs.mapIndexed { index, freq ->
                EqBand(id = index, centerFreqHz = freq, gainDb = savedGains[index].coerceIn(-12f, 12f))
            }
        } else {
            initDefaultBands()
        }

        syncToEngine()
        Log.d(tag, "EqualizerController initialized & restored: enabled=$savedEnabled, bass=$savedBass, preset=$savedPreset")
    }

    private fun loadCustomPresets(prefs: LibraryPreferences) {
        try {
            val jsonStr = prefs.getCustomEqPresetsJson()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<EqPreset>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val bass = obj.optInt("bass", 350)
                val gainsArray = obj.getJSONArray("gains")
                val gains = mutableListOf<Float>()
                for (j in 0 until gainsArray.length()) {
                    gains.add(gainsArray.getDouble(j).toFloat())
                }
                list.add(EqPreset(name = name, bandGainsDb = gains, bassBoostStrength = bass, isCustom = true))
            }
            _customPresets.value = list
            updateAllPresets()
        } catch (e: Exception) {
            Log.e(tag, "Error loading custom presets", e)
        }
    }

    private fun persistCustomPresets() {
        appContext?.let { ctx ->
            try {
                val array = JSONArray()
                for (preset in _customPresets.value) {
                    val obj = JSONObject().apply {
                        put("name", preset.name)
                        put("bass", preset.bassBoostStrength)
                        val gainsArray = JSONArray()
                        preset.bandGainsDb.forEach { gainsArray.put(it.toDouble()) }
                        put("gains", gainsArray)
                    }
                    array.put(obj)
                }
                LibraryPreferences.getInstance(ctx).saveCustomEqPresets(array.toString())
            } catch (e: Exception) {
                Log.e(tag, "Error saving custom presets", e)
            }
        }
    }

    private fun updateAllPresets() {
        _allPresets.value = stockPresets + _customPresets.value
    }

    fun saveCustomPreset(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        
        val currentGains = _bands.value.map { it.gainDb }
        val currentBass = _bassBoostStrength.value
        val newPreset = EqPreset(name = trimmed, bandGainsDb = currentGains, bassBoostStrength = currentBass, isCustom = true)

        val updated = _customPresets.value.filter { !it.name.equals(trimmed, ignoreCase = true) }.toMutableList()
        updated.add(0, newPreset)
        _customPresets.value = updated
        updateAllPresets()
        persistCustomPresets()
        _selectedPresetName.value = trimmed
        saveCurrentState()
        return true
    }

    fun deleteCustomPreset(preset: EqPreset) {
        if (!preset.isCustom) return
        val updated = _customPresets.value.filter { it.name != preset.name }
        _customPresets.value = updated
        updateAllPresets()
        persistCustomPresets()

        if (_selectedPresetName.value == preset.name) {
            _selectedPresetName.value = "Custom"
            saveCurrentState()
        }
    }

    private fun initDefaultBands() {
        _bands.value = defaultCenterFreqs.mapIndexed { index, freq ->
            EqBand(id = index, centerFreqHz = freq, gainDb = 0f)
        }
    }

    fun syncToEngine() {
        try {
            NativeAudioEngine.setEqEnabled(_isEnabled.value)
            NativeAudioEngine.setBassBoost(_bassBoostStrength.value / 1000f)
            _bands.value.forEach { band ->
                NativeAudioEngine.setBandGain(band.id, band.gainDb)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to sync equalizer to native engine", e)
        }
    }

    private fun saveCurrentState() {
        appContext?.let { ctx ->
            val prefs = LibraryPreferences.getInstance(ctx)
            prefs.saveEqualizerState(
                isEnabled = _isEnabled.value,
                bassBoostStrength = _bassBoostStrength.value,
                presetName = _selectedPresetName.value,
                bandGains = _bands.value.map { it.gainDb }
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        try {
            NativeAudioEngine.setEqEnabled(enabled)
        } catch (e: Exception) {
            Log.w(tag, "Failed to set equalizer enabled state", e)
        }
        saveCurrentState()
    }

    fun setBandGain(bandId: Int, gainDb: Float) {
        val updated = _bands.value.map { band ->
            if (band.id == bandId) band.copy(gainDb = gainDb.coerceIn(-12f, 12f)) else band
        }
        _bands.value = updated
        _selectedPresetName.value = "Custom"
        try {
            NativeAudioEngine.setBandGain(bandId, gainDb.coerceIn(-12f, 12f))
        } catch (e: Exception) {
            Log.w(tag, "Failed to set band gain", e)
        }
        saveCurrentState()
    }

    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _bassBoostStrength.value = clamped
        try {
            NativeAudioEngine.setBassBoost(clamped / 1000f)
        } catch (e: Exception) {
            Log.w(tag, "Failed to set bass boost strength", e)
        }
        saveCurrentState()
    }

    fun applyPreset(preset: EqPreset) {
        _selectedPresetName.value = preset.name
        val clampedBass = preset.bassBoostStrength.coerceIn(0, 1000)
        _bassBoostStrength.value = clampedBass

        val updated = _bands.value.mapIndexed { index, band ->
            val gain = preset.bandGainsDb.getOrElse(index) { 0f }.coerceIn(-12f, 12f)
            band.copy(gainDb = gain)
        }
        _bands.value = updated
        syncToEngine()
        saveCurrentState()
    }

    fun release() {
        // Obsolete
    }
}
