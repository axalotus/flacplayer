package com.antigravity.audioplayer.desktop.audio

import com.antigravity.audioplayer.desktop.model.EqPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.*

object DesktopEqualizer {

    val BAND_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    val STOCK_PRESETS = listOf(
        EqPreset("Flat (Плоский)", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Bass Boost (Усиление баса)", listOf(5.5f, 4.5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 1f, 1.5f)),
        EqPreset("Deep Sub-Bass (Глубокий саб)", listOf(7f, 5.5f, 3f, 0f, -1f, -1f, 0f, 0.5f, 1.5f, 2f)),
        EqPreset("Vocal Boost (Вокал)", listOf(-1.5f, -1f, 0f, 1.5f, 3.5f, 4f, 3.5f, 2f, 1f, 0f)),
        EqPreset("Electronic (Электроника)", listOf(4.5f, 4f, 2f, 0f, -1.5f, 1.5f, 2f, 3f, 4f, 4.5f)),
        EqPreset("Rock (Рок)", listOf(4.5f, 3f, 1.5f, -0.5f, -1.5f, -0.5f, 2f, 3.5f, 4f, 4.5f)),
        EqPreset("Metal (Метал)", listOf(5f, 3.5f, 1f, -1f, -2.5f, -1f, 1.5f, 4f, 5f, 5.5f)),
        EqPreset("Hip-Hop (Хип-хоп)", listOf(6f, 5f, 2.5f, 0.5f, -1f, -0.5f, 1.5f, 2f, 3f, 3.5f)),
        EqPreset("Jazz (Джаз)", listOf(3f, 2f, 1f, 1.5f, -1f, -1f, 0.5f, 1.5f, 3f, 3.5f)),
        EqPreset("Acoustic (Акустика)", listOf(3.5f, 2.5f, 1f, 1f, 1.5f, 1.5f, 2.5f, 3.5f, 4f, 3f)),
        EqPreset("Pop (Поп)", listOf(-1f, 1f, 3f, 3.5f, 3.5f, 2f, -0.5f, 1.5f, 3f, 3.5f)),
        EqPreset("Classical (Классика)", listOf(4f, 3f, 2.5f, 2f, -1f, -1f, 0f, 2f, 3f, 3.5f)),
        EqPreset("R&B (R&B)", listOf(5f, 4.5f, 2.5f, -1f, -1.5f, 1.5f, 2.5f, 3f, 3.5f, 4f)),
        EqPreset("Dance (Танцевальная)", listOf(5.5f, 4.5f, 2f, 0f, 0f, 2f, 3f, 3.5f, 4f, 3f)),
        EqPreset("Lounge (Лаунж)", listOf(3f, 2.5f, 1.5f, 0f, -1f, -0.5f, 1f, 2f, 2.5f, 2f)),
        EqPreset("Night Mode (Ночной режим)", listOf(-3f, -2f, -1f, 0f, 1f, 1.5f, 1f, 0f, -2f, -3.5f)),
        EqPreset("Treble Boost (Чёткие ВЧ)", listOf(-2f, -1.5f, -1f, 0f, 0.5f, 1.5f, 3f, 4.5f, 6f, 7f)),
        EqPreset("Warm Analog (Теплый аналог)", listOf(3.5f, 3f, 2.5f, 1.5f, 0.5f, -0.5f, -1f, -1.5f, -2.5f, -3.5f)),
        EqPreset("Studio Monitor (Студийный)", listOf(0f, 0.5f, 0f, -0.5f, 0f, 0.5f, 0f, -0.5f, 0f, 0.5f)),
        EqPreset("Spatial 3D (Пространственный)", listOf(2.5f, 1.5f, -1f, -1.5f, 0f, 1.5f, 3f, 4.5f, 5f, 4f)),
        EqPreset("Crystal Audio (Кристальный)", listOf(1.5f, 0.5f, -0.5f, 0f, 1f, 2.5f, 4f, 5.5f, 6.5f, 7f)),
        EqPreset("Speech / Podcast (Подкасты)", listOf(-4f, -2f, 0f, 2.5f, 4.5f, 4f, 3f, 1.5f, -1f, -3f))
    )

    private val _currentGains = MutableStateFlow(FloatArray(10) { 0f })
    val currentGains = _currentGains.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled = _isEnabled.asStateFlow()

    private val _selectedPresetName = MutableStateFlow("Flat (Плоский)")
    val selectedPresetName = _selectedPresetName.asStateFlow()

    private val _customPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val customPresets = _customPresets.asStateFlow()

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val presetsFile: File
        get() {
            val dir = File(System.getProperty("user.home"), ".flacplayer")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "custom_presets.json")
        }

    init {
        loadCustomPresets()
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 10) {
            val copy = _currentGains.value.copyOf()
            copy[bandIndex] = gainDb.coerceIn(-12f, 12f)
            _currentGains.value = copy
            _selectedPresetName.value = "Пользовательский"
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    fun applyPreset(preset: EqPreset) {
        val gains = FloatArray(10) { i -> preset.gains.getOrElse(i) { 0f } }
        _currentGains.value = gains
        _selectedPresetName.value = preset.name
    }

    fun saveCustomPreset(name: String) {
        val gainsList = _currentGains.value.toList()
        val newPreset = EqPreset(name, gainsList, isCustom = true)
        val updated = _customPresets.value.filter { it.name != name } + newPreset
        _customPresets.value = updated
        _selectedPresetName.value = name
        persistCustomPresets(updated)
    }

    fun deleteCustomPreset(name: String) {
        val updated = _customPresets.value.filter { it.name != name }
        _customPresets.value = updated
        if (_selectedPresetName.value == name) {
            applyPreset(STOCK_PRESETS.first())
        }
        persistCustomPresets(updated)
    }

    private fun loadCustomPresets() {
        try {
            if (presetsFile.exists()) {
                val content = presetsFile.readText()
                val list = json.decodeFromString<List<EqPreset>>(content)
                _customPresets.value = list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistCustomPresets(list: List<EqPreset>) {
        try {
            val content = json.encodeToString(list)
            presetsFile.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
