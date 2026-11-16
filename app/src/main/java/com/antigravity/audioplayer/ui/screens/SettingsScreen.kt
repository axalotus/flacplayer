package com.antigravity.audioplayer.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.BuildConfig
import com.antigravity.audioplayer.audio.DacDetector
import com.antigravity.audioplayer.audio.DacOutputInfo
import com.antigravity.audioplayer.audio.EqualizerController
import com.antigravity.audioplayer.data.ota.OtaStatus
import com.antigravity.audioplayer.data.ota.OtaUpdateManager
import com.antigravity.audioplayer.data.storage.DESKTOP_THEME_PRESETS
import com.antigravity.audioplayer.data.storage.FlacThemeConfig
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.data.theme.FlacThemePackageManager
import com.antigravity.audioplayer.ui.components.*
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme
import com.antigravity.audioplayer.ui.theme.parseHexColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String) {
    THEMES("Темы"),
    BACKGROUNDS("Фоны"),
    EQUALIZER("Эквалайзер"),
    SYSTEM("Система")
}

@Composable
fun SettingsScreen(
    eqController: EqualizerController,
    dacInfo: DacOutputInfo,
    hiddenCount: Int,
    onPickFolder: () -> Unit,
    onRescanLibrary: () -> Unit,
    bottomInsetPadding: Dp
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val themeConfig by prefs.themeConfig.collectAsState()
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(SettingsTab.THEMES) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = FlacThemePackageManager.exportThemeToUri(context, uri, themeConfig, "FlacTheme")
                val msg = if (res.isSuccess) "Тема .flactheme сохранена" else "Ошибка экспорта: ${res.exceptionOrNull()?.message}"
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
                start = 16.dp,
                end = 16.dp,
                top = 6.dp,
                bottom = bottomInsetPadding + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (activeTab) {
                SettingsTab.THEMES -> {
                    item(key = "presets_bundle") {
                        ThemesSection(
                            config = themeConfig,
                            onUpdate = { prefs.updateThemeConfig(it) },
                            onExport = { exportLauncher.launch("theme.flactheme") },
                            onImport = { importLauncher.launch(arrayOf("*/*")) }
                        )
                    }
                }
                SettingsTab.BACKGROUNDS -> {
                    item(key = "bg_app") {
                        BackgroundSlotCard(
                            title = "Фон главного экрана",
                            mode = themeConfig.appBgMode,
                            colorMode = themeConfig.appBgColorMode,
                            customPath = themeConfig.appBgCustomPath,
                            isVideo = themeConfig.appBgIsVideo,
                            blurPx = themeConfig.appBgBlurPx,
                            dimPercent = themeConfig.appBgDimPercent,
                            onModeChange = { m -> prefs.updateThemeConfig { it.copy(appBgMode = m) } },
                            onColorModeChange = { cm -> prefs.updateThemeConfig { it.copy(appBgColorMode = cm) } },
                            onPickFile = { pickAppBgLauncher.launch(arrayOf("image/*", "video/*")) },
                            onBlurChange = { b -> prefs.updateThemeConfig { it.copy(appBgBlurPx = b) } },
                            onDimChange = { d -> prefs.updateThemeConfig { it.copy(appBgDimPercent = d) } }
                        )
                    }
                    item(key = "bg_fs") {
                        BackgroundSlotCard(
                            title = "Фон плеера",
                            mode = themeConfig.fsBgMode,
                            colorMode = themeConfig.fsBgColorMode,
                            customPath = themeConfig.fsBgCustomPath,
                            isVideo = themeConfig.fsBgIsVideo,
                            blurPx = themeConfig.fsBgBlurPx,
                            dimPercent = themeConfig.fsBgDimPercent,
                            onModeChange = { m -> prefs.updateThemeConfig { it.copy(fsBgMode = m) } },
                            onColorModeChange = { cm -> prefs.updateThemeConfig { it.copy(fsBgColorMode = cm) } },
                            onPickFile = { pickFsBgLauncher.launch(arrayOf("image/*", "video/*")) },
                            onBlurChange = { b -> prefs.updateThemeConfig { it.copy(fsBgBlurPx = b) } },
                            onDimChange = { d -> prefs.updateThemeConfig { it.copy(fsBgDimPercent = d) } }
                        )
                    }
                }
                SettingsTab.EQUALIZER -> {
                    item(key = "eq_section") {
                        EqualizerSection(
                            eqController = eqController,
                            dacInfo = dacInfo,
                            prefs = prefs
                        )
                    }
                }
                SettingsTab.SYSTEM -> {
                    item(key = "system_section") {
                        SystemSection(
                            prefs = prefs,
                            hiddenCount = hiddenCount,
                            onPickFolder = onPickFolder,
                            onRescanLibrary = onRescanLibrary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemesSection(
    config: FlacThemeConfig,
    onUpdate: ((FlacThemeConfig) -> FlacThemeConfig) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlacGlassCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Обмен темами (.flactheme)", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Совместимый с Desktop архив с цветами, радиусами, настройками текста и загруженным фоном.",
                    color = tokens.fg2,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlacPillButton(
                        text = "Поделиться темой",
                        icon = Icons.Rounded.Upload,
                        accentFill = true,
                        onClick = onExport
                    )
                    FlacPillButton(
                        text = "Импорт",
                        icon = Icons.Rounded.Download,
                        onClick = onImport
                    )
                }
            }
        }

        FlacGlassCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Пресеты", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }

        FlacGlassCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Цвета и поверхности", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

                ColorSwatchRow(
                    label = "Акцент",
                    hex = config.accentColor,
                    swatches = listOf("#ffffff", "#ff3b5c", "#7dd3fc", "#f59e0b", "#34d399", "#a78bfa"),
                    onSelect = { c -> onUpdate { it.copy(accentColor = c) } }
                )

                ColorSwatchRow(
                    label = "Цвет панелей",
                    hex = config.panelColor,
                    swatches = listOf("#0a0a0a", "#121216", "#140b0e", "#0c131d", "#141008", "#081410"),
                    onSelect = { c -> onUpdate { it.copy(panelColor = c) } }
                )

                SliderSettingRow(
                    label = "Размытие меню и панелей",
                    valueText = "${config.panelBlurPx} px",
                    value = config.panelBlurPx.toFloat(),
                    range = 0f..40f,
                    onValueChange = { v -> onUpdate { it.copy(panelBlurPx = v.roundToInt()) } }
                )
                SliderSettingRow(
                    label = "Яркость панелей",
                    valueText = "${config.panelBrightness}%",
                    value = config.panelBrightness.toFloat(),
                    range = 40f..220f,
                    onValueChange = { v -> onUpdate { it.copy(panelBrightness = v.roundToInt()) } }
                )
                SliderSettingRow(
                    label = "Прозрачность панелей",
                    valueText = "${config.panelOpacity}%",
                    value = config.panelOpacity.toFloat(),
                    range = 20f..100f,
                    onValueChange = { v -> onUpdate { it.copy(panelOpacity = v.roundToInt()) } }
                )
                SliderSettingRow(
                    label = "Прозрачность карточек",
                    valueText = "${config.cardOpacity}%",
                    value = config.cardOpacity.toFloat(),
                    range = 20f..100f,
                    onValueChange = { v -> onUpdate { it.copy(cardOpacity = v.roundToInt()) } }
                )
                SliderSettingRow(
                    label = "Скругление углов",
                    valueText = "${config.borderRadiusPx} dp",
                    value = config.borderRadiusPx.toFloat(),
                    range = 2f..22f,
                    onValueChange = { v -> onUpdate { it.copy(borderRadiusPx = v.roundToInt()) } }
                )
                SliderSettingRow(
                    label = "Контраст границ",
                    valueText = "${config.borderOpacity}%",
                    value = config.borderOpacity.toFloat(),
                    range = 0f..35f,
                    onValueChange = { v -> onUpdate { it.copy(borderOpacity = v.roundToInt()) } }
                )
                SliderSettingRow(
                    label = "Затухание неактивных строк",
                    valueText = "${config.lyricsBlurIntensity}%",
                    value = config.lyricsBlurIntensity.toFloat(),
                    range = 0f..180f,
                    onValueChange = { v -> onUpdate { it.copy(lyricsBlurIntensity = v.roundToInt()) } }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Шрифт", color = tokens.fg1, fontSize = 13.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }
        }
    }
}

@Composable
private fun ColorSwatchRow(
    label: String,
    hex: String,
    swatches: List<String>,
    onSelect: (String) -> Unit
) {
    val tokens = LocalFlacTheme.current
    var hexInput by remember(hex) { mutableStateOf(hex) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = tokens.fg1, fontSize = 13.5.sp)
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .clip(RoundedCornerShape(tokens.radiusSm))
                    .background(tokens.bg2)
                    .border(1.dp, tokens.line, RoundedCornerShape(tokens.radiusSm))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                BasicTextField(
                    value = hexInput,
                    onValueChange = { next ->
                        hexInput = next
                        val clean = next.trim()
                        if (clean.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
                            onSelect(clean.lowercase())
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = tokens.fg0, fontSize = 12.sp, fontFamily = tokens.fontFamily),
                    cursorBrush = SolidColor(tokens.accent)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            swatches.forEach { sw ->
                val color = parseHexColor(sw, Color.White)
                val selected = hex.equals(sw, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selected) 2.5.dp else 1.dp,
                            color = if (selected) tokens.fg0 else tokens.lineStrong,
                            shape = CircleShape
                        )
                        .clickable { onSelect(sw) }
                )
            }
        }
    }
}

@Composable
private fun BackgroundSlotCard(
    title: String,
    mode: String,
    colorMode: String,
    customPath: String,
    isVideo: Boolean,
    blurPx: Int,
    dimPercent: Int,
    onModeChange: (String) -> Unit,
    onColorModeChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onBlurChange: (Int) -> Unit,
    onDimChange: (Int) -> Unit
) {
    val tokens = LocalFlacTheme.current
    FlacGlassCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlacPillButton("Чёрный", active = mode == "none", onClick = { onModeChange("none") })
                FlacPillButton("Обложка", active = mode == "cover", onClick = { onModeChange("cover") })
                FlacPillButton("Файл", active = mode == "custom", onClick = {
                    if (customPath.isBlank()) onPickFile() else onModeChange("custom")
                })
            }

            if (mode == "custom") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            customPath.isBlank() -> "Файл не выбран"
                            isVideo -> "Видео фон загружен"
                            else -> "Изображение загружено"
                        },
                        color = tokens.fg2,
                        fontSize = 12.5.sp
                    )
                    FlacPillButton(text = "Выбрать фото/видео", onClick = onPickFile)
                }
            }

            if (mode != "none") {
                if (!(mode == "custom" && isVideo)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Оттенок фона", color = tokens.fg1, fontSize = 13.5.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    SliderSettingRow(
                        label = "Размытие",
                        valueText = "$blurPx",
                        value = blurPx.toFloat(),
                        range = 0f..90f,
                        onValueChange = { onBlurChange(it.roundToInt()) }
                    )
                }

                SliderSettingRow(
                    label = "Затемнение",
                    valueText = "$dimPercent%",
                    value = dimPercent.toFloat(),
                    range = 15f..95f,
                    onValueChange = { onDimChange(it.roundToInt()) }
                )
            }
        }
    }
}

@Composable
private fun EqualizerSection(
    eqController: EqualizerController,
    dacInfo: DacOutputInfo,
    prefs: LibraryPreferences
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current

    val isEnabled by eqController.isEnabled.collectAsState()
    val bands by eqController.bands.collectAsState()
    val bassBoost by eqController.bassBoostStrength.collectAsState()
    val presets by eqController.allPresets.collectAsState()
    val selectedPreset by eqController.selectedPresetName.collectAsState()

    val audioMode by prefs.audioEngineMode.collectAsState()
    val safeBuffer by prefs.isSafeBufferMode.collectAsState()
    val gapless by prefs.isGaplessEnabled.collectAsState()
    val replayGain by prefs.isReplayGainEnabled.collectAsState()
    val crossfade by prefs.crossfadeSeconds.collectAsState()
    val autoPause by prefs.isAutoPauseOnDisconnect.collectAsState()
    val autoResume by prefs.isAutoResumeOnConnect.collectAsState()

    var newPresetName by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlacGlassCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = dacInfo.deviceName,
                        color = tokens.fg0,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val maxRate = dacInfo.sampleRates.maxOrNull() ?: 48000
                    Text(
                        text = "${dacInfo.connectionType} • ${maxRate / 1000} kHz",
                        color = tokens.fg2,
                        fontSize = 12.sp
                    )
                }
                FlacPillButton(
                    text = "Выход",
                    icon = Icons.Rounded.Headphones,
                    onClick = { DacDetector.launchMediaOutputSwitcher(context) }
                )
            }
        }

        FlacGlassCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("10-полосный эквалайзер", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    FlacToggle(checked = isEnabled, onCheckedChange = { eqController.setEnabled(it) })
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets, key = { it.name }) { preset ->
                        FlacPillButton(
                            text = preset.name,
                            active = preset.name == selectedPreset,
                            onClick = { eqController.applyPreset(preset) }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .padding(vertical = 4.dp),
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

                SliderSettingRow(
                    label = "Усиление баса",
                    valueText = "${(bassBoost / 10)}%",
                    value = bassBoost.toFloat(),
                    range = 0f..1000f,
                    onValueChange = { eqController.setBassBoost(it.roundToInt()) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(tokens.radiusSm))
                            .background(tokens.bg2)
                            .border(1.dp, tokens.line, RoundedCornerShape(tokens.radiusSm))
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        if (newPresetName.isEmpty()) {
                            Text("Сохранить как пресет...", color = tokens.fg2, fontSize = 12.5.sp)
                        }
                        BasicTextField(
                            value = newPresetName,
                            onValueChange = { newPresetName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = tokens.fg0, fontSize = 12.5.sp),
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

        FlacGlassCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Аудиодвижок (C++ Oboe)", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlacPillButton("AAudio", active = audioMode == 0, onClick = { prefs.setAudioEngineMode(0) })
                    FlacPillButton("OpenSL ES", active = audioMode == 1, onClick = { prefs.setAudioEngineMode(1) })
                    FlacPillButton("Hi-Res Direct", active = audioMode == 2, onClick = { prefs.setAudioEngineMode(2) })
                }
                ToggleSettingRow("Безопасный буфер", safeBuffer) { prefs.setSafeBufferMode(it) }
                ToggleSettingRow("Без пауз (Gapless)", gapless) { prefs.setGaplessEnabled(it) }
                ToggleSettingRow("ReplayGain", replayGain) { prefs.setReplayGainEnabled(it) }
                SliderSettingRow(
                    label = "Кроссфейд",
                    valueText = if (crossfade == 0) "Выкл" else "${crossfade} с",
                    value = crossfade.toFloat(),
                    range = 0f..10f,
                    onValueChange = { prefs.setCrossfadeSeconds(it.roundToInt()) }
                )
                ToggleSettingRow("Пауза при отключении наушников", autoPause) { prefs.setAutoPauseOnDisconnect(it) }
                ToggleSettingRow("Возобновлять при подключении", autoResume) { prefs.setAutoResumeOnConnect(it) }
            }
        }
    }
}

@Composable
private fun SystemSection(
    prefs: LibraryPreferences,
    hiddenCount: Int,
    onPickFolder: () -> Unit,
    onRescanLibrary: () -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val scope = rememberCoroutineScope()

    val compactMode by prefs.isCompactMode.collectAsState()
    val showTrackNumbers by prefs.isShowTrackNumbers.collectAsState()
    val showQualityBadges by prefs.showQualityBadges.collectAsState()
    val embeddedCovers by prefs.isEmbeddedCoversEnabled.collectAsState()
    val onlineLyrics by prefs.isOnlineLyricsEnabled.collectAsState()
    val otaStatus by OtaUpdateManager.status.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlacGlassCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Библиотека", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlacPillButton(
                        text = "Добавить папку",
                        icon = Icons.Rounded.CreateNewFolder,
                        accentFill = true,
                        onClick = onPickFolder
                    )
                    FlacPillButton(
                        text = "Сканировать",
                        icon = Icons.Rounded.Refresh,
                        onClick = onRescanLibrary
                    )
                }
                ToggleSettingRow("Компактный список треков", compactMode) { prefs.setCompactMode(it) }
                ToggleSettingRow("Номера треков в списке", showTrackNumbers) { prefs.setShowTrackNumbers(it) }
                ToggleSettingRow("Бейджи качества (FLAC / кГц)", showQualityBadges) { prefs.setShowQualityBadges(it) }
                ToggleSettingRow("Встроенные обложки", embeddedCovers) { prefs.setEmbeddedCoversEnabled(it) }
                ToggleSettingRow("Онлайн-поиск текстов (LRCLIB)", onlineLyrics) { prefs.setOnlineLyricsEnabled(it) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlacPillButton(
                        text = "Очистить кэш обложек",
                        onClick = {
                            clearArtworkCache(context)
                            Toast.makeText(context, "Кэш обложек очищен", Toast.LENGTH_SHORT).show()
                        }
                    )
                    if (hiddenCount > 0) {
                        FlacPillButton(
                            text = "Вернуть скрытые ($hiddenCount)",
                            onClick = { prefs.unhideAllTracks() }
                        )
                    }
                }
            }
        }

        FlacGlassCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("FlacPlayer", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Версия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
                            color = tokens.fg2,
                            fontSize = 12.sp
                        )
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
                if (otaStatus is OtaStatus.UpToDate) {
                    Text("Установлена актуальная версия", color = tokens.fg2, fontSize = 12.sp)
                } else if (otaStatus is OtaStatus.Error) {
                    Text((otaStatus as OtaStatus.Error).message, color = tokens.fg2, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = LocalFlacTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = tokens.fg1,
            fontSize = 13.5.sp,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        FlacToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderSettingRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val tokens = LocalFlacTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = tokens.fg1, fontSize = 13.5.sp)
            Text(valueText, color = tokens.fg0, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        FlacFlatSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
