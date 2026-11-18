package com.zuna.player.ui.screens

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zuna.player.BuildConfig
import com.zuna.player.audio.DacDetector
import com.zuna.player.audio.DacOutputInfo
import com.zuna.player.audio.EqualizerController
import com.zuna.player.data.ota.OtaStatus
import com.zuna.player.data.ota.OtaUpdateManager
import com.zuna.player.data.storage.DESKTOP_THEME_PRESETS
import com.zuna.player.data.storage.FlacThemeConfig
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.data.theme.FlacThemePackageManager
import com.zuna.player.ui.components.*
import com.zuna.player.ui.theme.LocalFlacTheme
import com.zuna.player.ui.theme.LocalPowerSaver
import com.zuna.player.ui.theme.parseHexColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String) {
    CUSTOMIZATION("Кастомизация"),
    EQUALIZER("Звук"),
    SYSTEM("Система")
}

@Composable
fun SettingsScreen(
    eqController: EqualizerController,
    dacInfo: DacOutputInfo,
    hiddenCount: Int,
    duplicateEntries: List<DuplicateTrackEntry> = emptyList(),
    onPickFolder: () -> Unit,
    onRescanLibrary: () -> Unit,
    onOpenColorPicker: (ColorPickerRequest) -> Unit,
    bottomInsetPadding: Dp
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val themeConfig by prefs.themeConfig.collectAsState()
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(SettingsTab.CUSTOMIZATION) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = FlacThemePackageManager.exportThemeToUri(context, uri, themeConfig, "ZunaTheme")
                val msg = if (res.isSuccess) "Тема .zunatheme сохранена" else "Ошибка экспорта: ${res.exceptionOrNull()?.message}"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = FlacThemePackageManager.importThemeFromUri(context, uri, prefs)
                val msg = if (res.isSuccess) "Тема импортирована" else "Ошибка импорта: ${res.exceptionOrNull()?.message}"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pickAppBgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = FlacThemePackageManager.copyBackgroundUriToAssets(context, uri)
                res.onSuccess { (path, isVideo) ->
                    prefs.updateThemeConfig {
                        it.copy(appBgMode = "custom", appBgCustomPath = path, appBgIsVideo = isVideo)
                    }
                }.onFailure {
                    Toast.makeText(context, "Не удалось загрузить файл", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val pickFsBgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = FlacThemePackageManager.copyBackgroundUriToAssets(context, uri)
                res.onSuccess { (path, isVideo) ->
                    prefs.updateThemeConfig {
                        it.copy(fsBgMode = "custom", fsBgCustomPath = path, fsBgIsVideo = isVideo)
                    }
                }.onFailure {
                    Toast.makeText(context, "Не удалось загрузить файл", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var duplicatesExpanded by remember { mutableStateOf(false) }
    val hideDuplicates by prefs.hideDuplicateTracks.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Настройки",
                color = tokens.fg0,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SettingsTab.values().toList()) { tab ->
                    FlacPillButton(
                        text = tab.label,
                        active = tab == activeTab,
                        onClick = { activeTab = tab }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = bottomInsetPadding + 24.dp
            )
        ) {
            when (activeTab) {
                SettingsTab.CUSTOMIZATION -> {
                    themesSectionItems(
                        config = themeConfig,
                        onUpdate = { prefs.updateThemeConfig(it) },
                        onExport = { exportLauncher.launch("theme.zunatheme") },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onOpenAccentPicker = {
                            onOpenColorPicker(
                                ColorPickerRequest(
                                    title = "Акцентный цвет",
                                    initialHex = themeConfig.accentColor,
                                    swatches = listOf("#ffffff", "#ff3b5c", "#7dd3fc", "#f59e0b", "#34d399", "#a78bfa", "#fb7185"),
                                    onSelectHex = { hex -> prefs.updateThemeConfig { it.copy(accentColor = hex) } }
                                )
                            )
                        },
                        onOpenPanelPicker = {
                            onOpenColorPicker(
                                ColorPickerRequest(
                                    title = "Цвет панелей",
                                    initialHex = themeConfig.panelColor,
                                    swatches = listOf("#0a0a0a", "#121216", "#140b0e", "#0c131d", "#141008", "#081410", "#18181b"),
                                    onSelectHex = { hex -> prefs.updateThemeConfig { it.copy(panelColor = hex) } }
                                )
                            )
                        }
                    )
                    backgroundFlatSectionItems(
                        keyPrefix = "bg_app",
                        sectionTitle = "ФОН ГЛАВНОГО ЭКРАНА",
                        mode = themeConfig.appBgMode,
                        solidColor = themeConfig.appBgSolidColor,
                        colorMode = themeConfig.appBgColorMode,
                        customPath = themeConfig.appBgCustomPath,
                        isVideo = themeConfig.appBgIsVideo,
                        blurPx = themeConfig.appBgBlurPx,
                        dimPercent = themeConfig.appBgDimPercent,
                        onModeChange = { m -> prefs.updateThemeConfig { it.copy(appBgMode = m) } },
                        onPickSolidColor = {
                            onOpenColorPicker(
                                ColorPickerRequest(
                                    title = "Цвет фона экрана",
                                    initialHex = themeConfig.appBgSolidColor,
                                    swatches = listOf("#000000", "#0a0a0a", "#140b0e", "#0c131d", "#141008", "#081410", "#1a1625"),
                                    onSelectHex = { hex -> prefs.updateThemeConfig { it.copy(appBgSolidColor = hex) } }
                                )
                            )
                        },
                        onColorModeChange = { cm -> prefs.updateThemeConfig { it.copy(appBgColorMode = cm) } },
                        onPickFile = { pickAppBgLauncher.launch(arrayOf("image/*", "video/*")) },
                        onBlurChange = { b -> prefs.updateThemeConfig { it.copy(appBgBlurPx = b) } },
                        onDimChange = { d -> prefs.updateThemeConfig { it.copy(appBgDimPercent = d) } }
                    )
                    backgroundFlatSectionItems(
                        keyPrefix = "bg_fs",
                        sectionTitle = "ФОН ЭКРАНА ПЛЕЕРА",
                        mode = themeConfig.fsBgMode,
                        solidColor = themeConfig.fsBgSolidColor,
                        colorMode = themeConfig.fsBgColorMode,
                        customPath = themeConfig.fsBgCustomPath,
                        isVideo = themeConfig.fsBgIsVideo,
                        blurPx = themeConfig.fsBgBlurPx,
                        dimPercent = themeConfig.fsBgDimPercent,
                        onModeChange = { m -> prefs.updateThemeConfig { it.copy(fsBgMode = m) } },
                        onPickSolidColor = {
                            onOpenColorPicker(
                                ColorPickerRequest(
                                    title = "Цвет фона плеера",
                                    initialHex = themeConfig.fsBgSolidColor,
                                    swatches = listOf("#000000", "#0a0a0a", "#140b0e", "#0c131d", "#141008", "#081410", "#1a1625"),
                                    onSelectHex = { hex -> prefs.updateThemeConfig { it.copy(fsBgSolidColor = hex) } }
                                )
                            )
                        },
                        onColorModeChange = { cm -> prefs.updateThemeConfig { it.copy(fsBgColorMode = cm) } },
                        onPickFile = { pickFsBgLauncher.launch(arrayOf("image/*", "video/*")) },
                        onBlurChange = { b -> prefs.updateThemeConfig { it.copy(fsBgBlurPx = b) } },
                        onDimChange = { d -> prefs.updateThemeConfig { it.copy(fsBgDimPercent = d) } }
                    )
                }
                SettingsTab.EQUALIZER -> {
                    equalizerSectionItems(
                        eqController = eqController,
                        dacInfo = dacInfo,
                        prefs = prefs
                    )
                }
                SettingsTab.SYSTEM -> {
                    systemSectionItems(
                        prefs = prefs,
                        hiddenCount = hiddenCount,
                        hideDuplicates = hideDuplicates,
                        duplicatesExpanded = duplicatesExpanded,
                        onToggleDuplicatesExpanded = { duplicatesExpanded = !duplicatesExpanded },
                        duplicateEntries = duplicateEntries,
                        onPickFolder = onPickFolder,
                        onRescanLibrary = onRescanLibrary
                    )
                }
            }
        }
    }
}

private fun LazyListScope.themesSectionItems(
    config: FlacThemeConfig,
    onUpdate: ((FlacThemeConfig) -> FlacThemeConfig) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenAccentPicker: () -> Unit,
    onOpenPanelPicker: () -> Unit
) {
    item(key = "theme_presets_label") { SettingsSectionLabel("ПРЕСЕТЫ") }
    item(key = "theme_presets_row") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(DESKTOP_THEME_PRESETS, key = { it.id }) { preset ->
                val isActive = config.accentColor.equals(preset.accentColor, ignoreCase = true) &&
                    config.panelColor.equals(preset.panelColor, ignoreCase = true)
                FlacPillButton(
                    text = preset.name,
                    active = isActive,
                    onClick = {
                        onUpdate {
                            it.copy(
                                accentColor = preset.accentColor,
                                panelColor = preset.panelColor,
                                panelBrightness = preset.panelBrightness,
                                panelOpacity = preset.panelOpacity,
                                cardOpacity = preset.cardOpacity,
                                panelBlurPx = preset.panelBlurPx,
                                borderRadiusPx = preset.borderRadiusPx,
                                borderOpacity = preset.borderOpacity
                            )
                        }
                    }
                )
            }
        }
    }

    item(key = "theme_colors") {
        Column {
            SettingsSectionLabel("ЦВЕТА")
            SettingsColorRow(
                title = "Акцентный цвет",
                subtitle = "Кнопки, слайдеры и индикаторы",
                hex = config.accentColor,
                onClick = onOpenAccentPicker
            )
            SettingsDivider()
            SettingsColorRow(
                title = "Цвет панелей",
                subtitle = "Подложка меню, карточек и плеера",
                hex = config.panelColor,
                onClick = onOpenPanelPicker
            )
        }
    }

    item(key = "theme_surfaces_label") { SettingsSectionLabel("ПОВЕРХНОСТИ И ТЕКСТ") }
    item(key = "theme_font") {
        SettingsChoiceRow(label = "Шрифт") {
            FlacPillButton(
                text = "Sans",
                active = config.fontFamily != "mono",
                onClick = { onUpdate { it.copy(fontFamily = "jakarta") } }
            )
            FlacPillButton(
                text = "Mono",
                active = config.fontFamily == "mono",
                onClick = { onUpdate { it.copy(fontFamily = "mono") } }
            )
        }
        SettingsDivider()
    }
    item(key = "theme_panel_blur") {
        SettingsSliderRow(
            label = "Размытие меню и панелей",
            valueText = "${config.panelBlurPx} px",
            value = config.panelBlurPx.toFloat(),
            range = 0f..40f,
            onValueChange = { v -> onUpdate { it.copy(panelBlurPx = v.roundToInt()) } }
        )
        SettingsDivider()
    }
    item(key = "theme_panel_brightness") {
        SettingsSliderRow(
            label = "Яркость панелей",
            valueText = "${config.panelBrightness}%",
            value = config.panelBrightness.toFloat(),
            range = 40f..220f,
            onValueChange = { v -> onUpdate { it.copy(panelBrightness = v.roundToInt()) } }
        )
        SettingsDivider()
    }
    item(key = "theme_panel_opacity") {
        SettingsSliderRow(
            label = "Прозрачность панелей",
            valueText = "${config.panelOpacity}%",
            value = config.panelOpacity.toFloat(),
            range = 20f..100f,
            onValueChange = { v -> onUpdate { it.copy(panelOpacity = v.roundToInt()) } }
        )
        SettingsDivider()
    }
    item(key = "theme_card_opacity") {
        SettingsSliderRow(
            label = "Прозрачность карточек",
            valueText = "${config.cardOpacity}%",
            value = config.cardOpacity.toFloat(),
            range = 20f..100f,
            onValueChange = { v -> onUpdate { it.copy(cardOpacity = v.roundToInt()) } }
        )
        SettingsDivider()
    }
    item(key = "theme_radius") {
        SettingsSliderRow(
            label = "Скругление углов",
            valueText = "${config.borderRadiusPx} dp",
            value = config.borderRadiusPx.toFloat(),
            range = 2f..22f,
            onValueChange = { v -> onUpdate { it.copy(borderRadiusPx = v.roundToInt()) } }
        )
        SettingsDivider()
    }
    item(key = "theme_border_opacity") {
        SettingsSliderRow(
            label = "Контраст границ",
            valueText = "${config.borderOpacity}%",
            value = config.borderOpacity.toFloat(),
            range = 0f..35f,
            onValueChange = { v -> onUpdate { it.copy(borderOpacity = v.roundToInt()) } }
        )
        SettingsDivider()
    }
    item(key = "theme_lyrics_blur") {
        SettingsSliderRow(
            label = "Затухание неактивных строк",
            valueText = "${config.lyricsBlurIntensity}%",
            value = config.lyricsBlurIntensity.toFloat(),
            range = 0f..180f,
            onValueChange = { v -> onUpdate { it.copy(lyricsBlurIntensity = v.roundToInt()) } }
        )
    }

    item(key = "theme_share") {
        Column {
            SettingsSectionLabel("ОБМЕН ТЕМАМИ")
            SettingsActionRow(
                title = "Поделиться темой",
                subtitle = "Сохранить в .zunatheme для Desktop и Android",
                icon = Icons.Rounded.Upload,
                onClick = onExport
            )
            SettingsDivider()
            SettingsActionRow(
                title = "Импортировать тему",
                subtitle = "Открыть файл .zunatheme / .flactheme",
                icon = Icons.Rounded.Download,
                onClick = onImport
            )
        }
    }
}

private fun LazyListScope.backgroundFlatSectionItems(
    keyPrefix: String,
    sectionTitle: String,
    mode: String,
    solidColor: String,
    colorMode: String,
    customPath: String,
    isVideo: Boolean,
    blurPx: Int,
    dimPercent: Int,
    onModeChange: (String) -> Unit,
    onPickSolidColor: () -> Unit,
    onColorModeChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onBlurChange: (Int) -> Unit,
    onDimChange: (Int) -> Unit
) {
    item(key = "${keyPrefix}_header") {
        Column {
            SettingsSectionLabel(sectionTitle)
            SettingsChoiceRow(label = "Источник") {
                FlacPillButton("Цвет", active = mode == "none", onClick = { onModeChange("none") })
                FlacPillButton("Обложка", active = mode == "cover", onClick = { onModeChange("cover") })
                FlacPillButton("Файл", active = mode == "custom", onClick = {
                    if (customPath.isBlank()) onPickFile() else onModeChange("custom")
                })
            }

            if (mode == "none") {
                SettingsDivider()
                SettingsColorRow(
                    title = "Цвет фона",
                    subtitle = "Однотонная подложка экрана",
                    hex = solidColor,
                    onClick = onPickSolidColor
                )
            }

            if (mode == "custom") {
                SettingsDivider()
                SettingsActionRow(
                    title = "Выбрать фото или видео",
                    subtitle = when {
                        customPath.isBlank() -> "Файл не выбран"
                        isVideo -> "Видео фон загружен"
                        else -> "Изображение загружено"
                    },
                    icon = Icons.Rounded.Image,
                    onClick = onPickFile
                )
            }
        }
    }

    if (mode != "none") {
        val isVideoCustom = mode == "custom" && isVideo
        item(key = "${keyPrefix}_shade") {
            SettingsDivider()
            SettingsChoiceRow(label = "Оттенок фона") {
                FlacPillButton(
                    text = "Монохром",
                    active = colorMode != "color",
                    onClick = { onColorModeChange("mono") }
                )
                FlacPillButton(
                    text = "Цветной",
                    active = colorMode == "color",
                    onClick = { onColorModeChange("color") }
                )
            }
        }
        if (!isVideoCustom || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item(key = "${keyPrefix}_blur") {
                SettingsDivider()
                SettingsSliderRow(
                    label = "Размытие",
                    valueText = "$blurPx",
                    value = blurPx.toFloat(),
                    range = 0f..90f,
                    onValueChange = { onBlurChange(it.roundToInt()) }
                )
            }
        }
        item(key = "${keyPrefix}_dim") {
            SettingsDivider()
            SettingsSliderRow(
                label = "Затемнение",
                valueText = "$dimPercent%",
                value = dimPercent.toFloat(),
                range = 15f..95f,
                onValueChange = { onDimChange(it.roundToInt()) }
            )
        }
    }
}

private fun LazyListScope.equalizerSectionItems(
    eqController: EqualizerController,
    dacInfo: DacOutputInfo,
    prefs: LibraryPreferences
) {
    item(key = "eq_output") {
        val context = LocalContext.current
        val tokens = LocalFlacTheme.current
        val audioMode by prefs.audioEngineMode.collectAsState()
        val maxRate = dacInfo.sampleRates.maxOrNull() ?: 48000

        Column {
            SettingsSectionLabel("ВЫВОД ЗВУКА")
            SettingsActionRow(
                title = dacInfo.deviceName,
                subtitle = "${dacInfo.connectionType} • ${maxRate / 1000} kHz",
                icon = Icons.Rounded.Headphones,
                onClick = { DacDetector.launchMediaOutputSwitcher(context) }
            )
            SettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Аудиодвижок", color = tokens.fg0, fontSize = 14.5.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FlacPillButton("AAudio", active = audioMode == 0, onClick = { prefs.setAudioEngineMode(0) })
                    }
                    item {
                        FlacPillButton("OpenSL ES", active = audioMode == 1, onClick = { prefs.setAudioEngineMode(1) })
                    }
                    item {
                        FlacPillButton("Hi-Res Direct", active = audioMode == 2, onClick = { prefs.setAudioEngineMode(2) })
                    }
                }
            }
        }
    }

    item(key = "eq_toggle") {
        val isEnabled by eqController.isEnabled.collectAsState()
        Column {
            SettingsSectionLabel("10-ПОЛОСНЫЙ ЭКВАЛАЙЗЕР")
            SettingsToggleRow("Включить эквалайзер", isEnabled) { eqController.setEnabled(it) }
        }
    }

    item(key = "eq_presets") {
        val tokens = LocalFlacTheme.current
        val presets by eqController.allPresets.collectAsState()
        val selectedPreset by eqController.selectedPresetName.collectAsState()

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presets, key = { it.name }) { preset ->
                val active = preset.name == selectedPreset
                if (preset.isCustom) {
                    val fg = if (active) tokens.accentFg else tokens.fg1
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (active) tokens.accent else tokens.cardBg, CircleShape)
                            .border(1.dp, if (active) tokens.accent else tokens.line, CircleShape)
                            .clickable { eqController.applyPreset(preset) }
                            .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = preset.name,
                            color = fg,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        FlacIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "Удалить пресет",
                            onClick = { eqController.deleteCustomPreset(preset) },
                            size = 20.dp,
                            iconSize = 14.dp,
                            tint = fg
                        )
                    }
                } else {
                    FlacPillButton(
                        text = preset.name,
                        active = active,
                        onClick = { eqController.applyPreset(preset) }
                    )
                }
            }
        }
    }

    item(key = "eq_bands") {
        val tokens = LocalFlacTheme.current
        val bands by eqController.bands.collectAsState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(tokens.radiusMd))
                .background(tokens.cardBg.copy(alpha = 0.65f))
                .border(1.dp, tokens.line, RoundedCornerShape(tokens.radiusMd))
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .height(178.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            bands.forEach { band ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "%+.0f".format(band.gainDb),
                        color = tokens.fg2,
                        fontSize = 10.sp
                    )
                    FlacVerticalSlider(
                        value = band.gainDb,
                        valueRange = band.minDb..band.maxDb,
                        onValueChange = { eqController.setBandGain(band.id, it) },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = band.formattedFreq,
                        color = tokens.fg1,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }

    item(key = "eq_bass_save") {
        val tokens = LocalFlacTheme.current
        val bassBoost by eqController.bassBoostStrength.collectAsState()
        var newPresetName by remember { mutableStateOf("") }

        Column {
            SettingsSliderRow(
                label = "Усиление баса",
                valueText = "${bassBoost / 10}%",
                value = bassBoost.toFloat(),
                range = 0f..1000f,
                onValueChange = { eqController.setBassBoost(it.roundToInt()) }
            )
            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(tokens.radiusSm))
                        .background(tokens.bg2)
                        .border(1.dp, tokens.panelLine, RoundedCornerShape(tokens.radiusSm))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (newPresetName.isEmpty()) {
                        Text("Сохранить как пресет...", color = tokens.panelFg2, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        singleLine = true,
                        textStyle = TextStyle(color = tokens.panelFg0, fontSize = 13.sp, fontFamily = tokens.fontFamily),
                        cursorBrush = SolidColor(tokens.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                FlacPillButton(
                    text = "Сохранить",
                    accentFill = newPresetName.isNotBlank(),
                    onClick = {
                        val name = newPresetName.trim()
                        if (name.isNotEmpty() && eqController.saveCustomPreset(name)) {
                            newPresetName = ""
                        }
                    }
                )
            }
        }
    }

    item(key = "eq_playback") {
        val safeBuffer by prefs.isSafeBufferMode.collectAsState()
        val gapless by prefs.isGaplessEnabled.collectAsState()
        val replayGain by prefs.isReplayGainEnabled.collectAsState()
        val crossfade by prefs.crossfadeSeconds.collectAsState()
        val autoPause by prefs.isAutoPauseOnDisconnect.collectAsState()
        val autoResume by prefs.isAutoResumeOnConnect.collectAsState()

        Column {
            SettingsSectionLabel("ВОСПРОИЗВЕДЕНИЕ")
            SettingsToggleRow("Безопасный буфер", safeBuffer) { prefs.setSafeBufferMode(it) }
            SettingsDivider()
            SettingsToggleRow("Без пауз (Gapless)", gapless) { prefs.setGaplessEnabled(it) }
            SettingsDivider()
            SettingsToggleRow("ReplayGain", replayGain) { prefs.setReplayGainEnabled(it) }
            SettingsDivider()
            SettingsSliderRow(
                label = "Кроссфейд",
                valueText = if (crossfade == 0) "Выкл" else "${crossfade} с",
                value = crossfade.toFloat(),
                range = 0f..10f,
                onValueChange = { prefs.setCrossfadeSeconds(it.roundToInt()) }
            )
            SettingsDivider()
            SettingsToggleRow("Пауза при отключении наушников", autoPause) { prefs.setAutoPauseOnDisconnect(it) }
            SettingsDivider()
            SettingsToggleRow("Возобновлять при подключении", autoResume) { prefs.setAutoResumeOnConnect(it) }
        }
    }
}

private fun LazyListScope.systemSectionItems(
    prefs: LibraryPreferences,
    hiddenCount: Int,
    hideDuplicates: Boolean,
    duplicatesExpanded: Boolean,
    onToggleDuplicatesExpanded: () -> Unit,
    duplicateEntries: List<DuplicateTrackEntry>,
    onPickFolder: () -> Unit,
    onRescanLibrary: () -> Unit
) {
    item(key = "sys_library") {
        val context = LocalContext.current
        Column {
            SettingsSectionLabel("МЕДИАТЕКА")
            SettingsActionRow(
                title = "Добавить папку с музыкой",
                icon = Icons.Rounded.CreateNewFolder,
                onClick = onPickFolder
            )
            SettingsDivider()
            SettingsActionRow(
                title = "Сканировать медиатеку",
                icon = Icons.Rounded.Refresh,
                onClick = onRescanLibrary
            )
            SettingsDivider()
            SettingsActionRow(
                title = "Очистить кэш обложек",
                icon = Icons.Rounded.DeleteOutline,
                onClick = {
                    clearArtworkCache(context)
                    Toast.makeText(context, "Кэш обложек очищен", Toast.LENGTH_SHORT).show()
                }
            )
            if (hiddenCount > 0) {
                SettingsDivider()
                SettingsActionRow(
                    title = "Вернуть скрытые треки",
                    subtitle = "$hiddenCount скрыто из библиотеки",
                    icon = Icons.Rounded.Visibility,
                    onClick = { prefs.unhideAllTracks() }
                )
            }
            SettingsDivider()
            SettingsToggleRow("Скрывать одинаковые треки", hideDuplicates) { prefs.setHideDuplicateTracks(it) }
        }
    }

    if (hideDuplicates) {
        if (duplicateEntries.isNotEmpty()) {
            item(key = "sys_dup_header") {
                val tokens = LocalFlacTheme.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleDuplicatesExpanded)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Скрытые дубликаты (${duplicateEntries.size})",
                        color = tokens.fg1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (duplicatesExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = tokens.fg2,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            if (duplicatesExpanded) {
                items(duplicateEntries, key = { "dup_${it.track.id}" }) { entry ->
                    val tokens = LocalFlacTheme.current
                    val fileName = remember(entry.track.uri) {
                        Uri.decode(entry.track.uri.lastPathSegment ?: "")
                            .substringAfterLast('/')
                            .substringAfterLast(':')
                            .ifBlank { entry.track.uri.toString() }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(tokens.radiusSm))
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TrackArtwork(
                            track = entry.track,
                            modifier = Modifier.size(40.dp),
                            cornerRadius = 9.dp,
                            iconSize = 16.dp
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = entry.track.title,
                                color = tokens.fg0,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${entry.track.artist} • ${entry.track.qualityBadgeText}",
                                color = tokens.fg2,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = fileName,
                                color = tokens.fg2.copy(alpha = 0.78f),
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FlacIconButton(
                            icon = Icons.Rounded.Visibility,
                            contentDescription = "Показать в библиотеке",
                            onClick = { prefs.allowDuplicateTrack(entry.track.id) },
                            size = 32.dp,
                            iconSize = 18.dp,
                            tint = tokens.fg1
                        )
                    }
                }
            }
        } else {
            item(key = "sys_dup_empty") {
                val tokens = LocalFlacTheme.current
                Text(
                    text = "Одинаковые треки не найдены",
                    color = tokens.fg2,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        item(key = "sys_dup_reset") {
            val forceVisibleDuplicateIds by prefs.forceVisibleDuplicateIds.collectAsState()
            if (forceVisibleDuplicateIds.isNotEmpty()) {
                Column {
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Снова скрывать все дубликаты",
                        subtitle = "${forceVisibleDuplicateIds.size} показано вручную",
                        icon = Icons.Rounded.VisibilityOff,
                        onClick = { prefs.resetAllowedDuplicateTracks() }
                    )
                }
            }
        }
    }

    item(key = "sys_ui_lyrics") {
        val compactMode by prefs.isCompactMode.collectAsState()
        val showTrackNumbers by prefs.isShowTrackNumbers.collectAsState()
        val showQualityBadges by prefs.showQualityBadges.collectAsState()
        val onlineLyrics by prefs.isOnlineLyricsEnabled.collectAsState()
        val onlineCovers by prefs.isOnlineCoversEnabled.collectAsState()

        Column {
            SettingsSectionLabel("ИНТЕРФЕЙС И ТЕКСТЫ")
            SettingsToggleRow("Компактный список треков", compactMode) { prefs.setCompactMode(it) }
            SettingsDivider()
            SettingsToggleRow("Номера треков в списке", showTrackNumbers) { prefs.setShowTrackNumbers(it) }
            SettingsDivider()
            SettingsToggleRow("Бейджи качества (FLAC / кГц)", showQualityBadges) { prefs.setShowQualityBadges(it) }
            SettingsDivider()
            SettingsToggleRow("Онлайн-поиск текстов (LRCLIB)", onlineLyrics) { prefs.setOnlineLyricsEnabled(it) }
            SettingsDivider()
            SettingsToggleRow("Автоподгрузка обложек (онлайн)", onlineCovers) { enabled ->
                prefs.setOnlineCoversEnabled(enabled)
                refreshArtworkCacheGeneration()
            }
        }
    }

    item(key = "sys_power_saver") {
        val tokens = LocalFlacTheme.current
        val psConfig by prefs.powerSaverConfig.collectAsState()
        val effPs = LocalPowerSaver.current

        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ЭНЕРГОСБЕРЕЖЕНИЕ",
                    color = tokens.fg2,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                val badgeText = when {
                    effPs.mode == "battery_saver" || effPs.autoTriggered -> "Battery Saver"
                    effPs.mode == "balanced" -> "Balanced"
                    effPs.active -> "Настроено"
                    else -> "Standard"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (effPs.active) tokens.accent else tokens.bg2)
                        .border(1.dp, if (effPs.active) Color.Transparent else tokens.lineStrong, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = if (effPs.active) tokens.accentFg else tokens.fg1,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                text = "Аудиотракт, частота дискретизации, Gapless и Bit-perfect не затрагиваются.",
                color = tokens.fg2,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            PowerModeOptionRow(
                title = "Выключено (Standard)",
                subtitle = "Весь визуальный шик и полный blur",
                selected = psConfig.mode == "standard",
                onClick = { prefs.setPowerSaverMode("standard") }
            )
            SettingsDivider()
            PowerModeOptionRow(
                title = "Balanced",
                subtitle = "Упрощённый blur, минимум анимаций, редкий тикер",
                selected = psConfig.mode == "balanced",
                onClick = { prefs.setPowerSaverMode("balanced") }
            )
            SettingsDivider()
            PowerModeOptionRow(
                title = "Автоматически при низком заряде",
                subtitle = "По системному режиму Android или заряде ≤ 20%",
                selected = psConfig.mode == "auto",
                onClick = { prefs.setPowerSaverMode("auto") }
            )
            SettingsDivider()
            PowerModeOptionRow(
                title = "Всегда включено (Battery Saver)",
                subtitle = "Минимум эффектов, статичный фон, без визуализатора",
                selected = psConfig.mode == "battery_saver",
                onClick = { prefs.setPowerSaverMode("battery_saver") }
            )
            SettingsDivider()
            SettingsSectionLabel("СНИЖЕНИЕ НАГРУЗКИ (РУЧНОЕ УПРАВЛЕНИЕ)")
            SettingsToggleRow("Отключить тяжёлый blur", psConfig.disableBlur) { enabled ->
                prefs.updatePowerSaverConfig { it.copy(disableBlur = enabled) }
            }
            SettingsDivider()
            SettingsToggleRow("Уменьшить анимации и переходы", psConfig.reduceAnimations) { enabled ->
                prefs.updatePowerSaverConfig { it.copy(reduceAnimations = enabled) }
            }
            SettingsDivider()
            SettingsToggleRow("Отключить динамический фон", psConfig.disableDynamicBg) { enabled ->
                prefs.updatePowerSaverConfig { it.copy(disableDynamicBg = enabled) }
            }
            SettingsDivider()
            SettingsToggleRow("Отключить визуализатор", psConfig.disableVisualizer) { enabled ->
                prefs.updatePowerSaverConfig { it.copy(disableVisualizer = enabled) }
            }
            SettingsDivider()
            SettingsToggleRow("Ограничить частоту обновления UI", psConfig.limitUiRefresh) { enabled ->
                prefs.updatePowerSaverConfig { it.copy(limitUiRefresh = enabled) }
            }
            SettingsDivider()
            SettingsToggleRow("Кэшированные миниатюры обложек", psConfig.useThumbCovers) { enabled ->
                prefs.updatePowerSaverConfig { it.copy(useThumbCovers = enabled) }
            }
        }
    }

    item(key = "sys_about") {
        val context = LocalContext.current
        val tokens = LocalFlacTheme.current
        val scope = rememberCoroutineScope()
        val otaStatus by OtaUpdateManager.status.collectAsState()

        Column {
            SettingsSectionLabel("О ПРИЛОЖЕНИИ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Zuna", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Версия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
                        color = tokens.fg2,
                        fontSize = 12.sp
                    )
                    if (otaStatus is OtaStatus.UpToDate) {
                        Text("Установлена актуальная версия", color = tokens.fg2, fontSize = 12.sp)
                    } else if (otaStatus is OtaStatus.Error) {
                        Text((otaStatus as OtaStatus.Error).message, color = tokens.fg2, fontSize = 12.sp)
                    }
                }
                when (val st = otaStatus) {
                    is OtaStatus.Checking -> Text("Проверка...", color = tokens.fg2, fontSize = 12.5.sp)
                    is OtaStatus.Downloading -> Text("${st.progressPercent}%", color = tokens.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    is OtaStatus.UpdateAvailable -> {
                        FlacPillButton(
                            text = "Скачать ${st.release.versionName}",
                            accentFill = true,
                            onClick = { scope.launch { OtaUpdateManager.downloadUpdate(context, st.release) } }
                        )
                    }
                    is OtaStatus.ReadyToInstall -> {
                        FlacPillButton(
                            text = "Установить",
                            accentFill = true,
                            onClick = { OtaUpdateManager.installApk(context, st.apkFile) }
                        )
                    }
                    else -> {
                        FlacPillButton(
                            text = "Обновления",
                            icon = Icons.Rounded.SystemUpdate,
                            onClick = { scope.launch { OtaUpdateManager.checkForUpdates(context) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(title: String) {
    val tokens = LocalFlacTheme.current
    Text(
        text = title,
        color = tokens.fg2,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsDivider() {
    val tokens = LocalFlacTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.5.dp)
            .background(tokens.line.copy(alpha = 0.6f))
    )
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tokens.fg1,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = tokens.fg0,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = tokens.fg2,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = tokens.fg2,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun SettingsColorRow(
    title: String,
    subtitle: String,
    hex: String,
    onClick: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    val color = parseHexColor(hex, Color.White)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, color = tokens.fg0, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = tokens.fg2, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, tokens.lineStrong, CircleShape)
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = tokens.fg2,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun PowerModeOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) tokens.accent else tokens.fg2,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, color = tokens.fg0, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = tokens.fg2, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoiceRow(
    label: String,
    content: @Composable () -> Unit
) {
    val tokens = LocalFlacTheme.current
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = tokens.fg0,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = 12.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = { content() }
        )
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = LocalFlacTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = tokens.fg0,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        FlacToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val tokens = LocalFlacTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = tokens.fg0, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(valueText, color = tokens.fg1, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        FlacFlatSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
