package com.antigravity.audioplayer.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.audio.NativeAudioEngine
import com.antigravity.audioplayer.audio.SleepTimerController
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.BuildConfig
import com.antigravity.audioplayer.data.ota.OtaUpdateManager
import com.antigravity.audioplayer.data.ota.OtaStatus
import com.antigravity.audioplayer.ui.components.clearArtworkCache
import kotlinx.coroutines.launch

enum class SettingsSection {
    NONE, INTERFACE, SOUND, SLEEP_TIMER, VISUALIZATION, BACKGROUND, COVERS, LYRICS, LIBRARY, BLUETOOTH, UPDATES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenAudioChain: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onRescanLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    var activeSection by remember { mutableStateOf(SettingsSection.NONE) }

    // Sound Settings State
    var crossfadeSeconds by remember { mutableIntStateOf(0) }
    var replayGain by remember { mutableStateOf(false) }
    var gapless by remember { mutableStateOf(true) }
    val audioEngineMode by prefs.audioEngineMode.collectAsState()

    // Interface Settings State
    val themeMode by prefs.themeMode.collectAsState()
    var showTrackNumbers by remember { mutableStateOf(false) }
    var compactMode by remember { mutableStateOf(false) }

    // Background / Covers State
    val isBlurBackground by prefs.isBlurBackground.collectAsState()
    val blurRadiusPercent by prefs.blurRadiusPercent.collectAsState()
    val isEmbeddedCoversEnabled by prefs.isEmbeddedCoversEnabled.collectAsState()

    // Bluetooth Settings State
    var autoPauseOnDisconnect by remember { mutableStateOf(true) }
    var autoResumeOnConnect by remember { mutableStateOf(true) }

    val lyricsFolderUri by prefs.lyricsFolderUri.collectAsState()
    val isOnlineLyricsEnabled by prefs.isOnlineLyricsEnabled.collectAsState()
    val lyricsFolderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            prefs.setLyricsFolderUri(uri)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (themeMode == com.antigravity.audioplayer.data.storage.ThemeMode.AMOLED) Color.Black else MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Column {
                    Text(
                        text = "Настройки",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "FlacPlayer Hi-Res Player • v${BuildConfig.VERSION_NAME}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // 1. Интерфейс
                SettingsCategoryCard(
                    title = "Интерфейс",
                    subtitle = "Скин: ${
                        when (themeMode) {
                            com.antigravity.audioplayer.data.storage.ThemeMode.AMOLED -> "Pitch Black"
                            com.antigravity.audioplayer.data.storage.ThemeMode.LIGHT -> "Светлая"
                            else -> "Тёмная"
                        }
                    }, Список, Номера треков",
                    icon = Icons.Default.Palette,
                    accentColor = Color(0xFF8E9AAF),
                    onClick = { activeSection = SettingsSection.INTERFACE }
                )

                // 2. Звук
                SettingsCategoryCard(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    title = "Звук и вывод",
                    subtitle = "Движок: ${when(audioEngineMode) { 1 -> "OpenSL ES"; 2 -> "Direct Hi-Res"; else -> "AAudio Low Latency" }} • Кроссфейд: ${crossfadeSeconds.toInt()}s",
                    accentColor = Color(0xFFE07A5F),
                    onClick = { activeSection = SettingsSection.SOUND }
                )

                // 3. Таймер сна
                val isTimerActive by SleepTimerController.isActive.collectAsState()
                val remainingSec by SleepTimerController.remainingSeconds.collectAsState()
                SettingsCategoryCard(
                    icon = Icons.Default.Bedtime,
                    title = "Таймер сна",
                    subtitle = if (isTimerActive) "Активен: осталось ${SleepTimerController.formatRemainingTime(remainingSec)}" else "Выключен • 15, 30, 60, 90 мин",
                    accentColor = Color(0xFF4CC9F0),
                    onClick = { activeSection = SettingsSection.SLEEP_TIMER }
                )

                // 3. Эквалайзер
                SettingsCategoryCard(
                    icon = Icons.Default.Equalizer,
                    title = "10-Полосный Эквалайзер",
                    subtitle = "DSP пресеты, Bass Boost, ручная настройка полос",
                    accentColor = Color(0xFF6B5CE7),
                    onClick = onOpenEqualizer
                )

                // 4. Визуализация
                SettingsCategoryCard(
                    icon = Icons.Default.GraphicEq,
                    title = "Визуализация",
                    subtitle = "Чувствительность волны, стиль визуализатора",
                    accentColor = Color(0xFF9B5DE5),
                    onClick = { activeSection = SettingsSection.VISUALIZATION }
                )

                // 5. Фон
                SettingsCategoryCard(
                    icon = Icons.Default.Wallpaper,
                    title = "Фон плеера",
                    subtitle = if (isBlurBackground) "Размытие обложки вкл ($blurRadiusPercent%)" else "Обычный тёмный фон",
                    accentColor = Color(0xFF00BBF9),
                    onClick = { activeSection = SettingsSection.BACKGROUND }
                )

                // 6. Обложки
                SettingsCategoryCard(
                    icon = Icons.Default.Image,
                    title = "Обложки треков",
                    subtitle = if (isEmbeddedCoversEnabled) "Встроенные обложки включены" else "Обложки выключены (заглушка)",
                    accentColor = Color(0xFF00F5D4),
                    onClick = { activeSection = SettingsSection.COVERS }
                )

                // 7. Тексты песен
                SettingsCategoryCard(
                    icon = Icons.Default.Lyrics,
                    title = "Тексты песен (Lyrics)",
                    subtitle = if (lyricsFolderUri != null) "Папка текстов: ${android.net.Uri.parse(lyricsFolderUri).lastPathSegment ?: "Выбрана"}" else "Синхронизация .lrc, .txt, теги",
                    accentColor = Color(0xFF06D6A0),
                    onClick = { activeSection = SettingsSection.LYRICS }
                )

                // 8. Библиотека
                SettingsCategoryCard(
                    icon = Icons.Default.FolderSpecial,
                    title = "Медиатека",
                    subtitle = "Папок: ${prefs.getSavedFolderUris().size} • Нажмите для управления",
                    accentColor = Color(0xFFF15BB5),
                    onClick = { activeSection = SettingsSection.LIBRARY }
                )

                // 8. Гарнитура/Bluetooth
                SettingsCategoryCard(
                    icon = Icons.Default.Headphones,
                    title = "Гарнитура / Bluetooth",
                    subtitle = "Авто-пауза: ${if (autoPauseOnDisconnect) "вкл" else "выкл"} • Возобновление: ${if (autoResumeOnConnect) "вкл" else "выкл"}",
                    accentColor = Color(0xFFF72585),
                    onClick = { activeSection = SettingsSection.BLUETOOTH }
                )

                // 9. Обновления (OTA)
                val otaStatus by OtaUpdateManager.status.collectAsState()
                SettingsCategoryCard(
                    icon = Icons.Default.SystemUpdate,
                    title = "Обновления (OTA)",
                    subtitle = when (otaStatus) {
                        is OtaStatus.UpdateAvailable -> "Доступна версия ${(otaStatus as OtaStatus.UpdateAvailable).release.versionName} • Нажмите для загрузки"
                        is OtaStatus.Downloading -> "Загрузка обновления (${(otaStatus as OtaStatus.Downloading).progressPercent}%)"
                        is OtaStatus.ReadyToInstall -> "Обновление готово к установке!"
                        else -> "GitHub Releases • Текущая версия: v${BuildConfig.VERSION_NAME}"
                    },
                    accentColor = Color(0xFF4CAF50),
                    onClick = { activeSection = SettingsSection.UPDATES }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // NDK Engine Badge
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "FlacPlayer v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Custom NDK C++ Engine • FLAC, MP3, WAV • 10-Band DSP",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Modal Bottom Sheet for Active Settings Sub-Section
    if (activeSection != SettingsSection.NONE) {
        ModalBottomSheet(
            onDismissRequest = { activeSection = SettingsSection.NONE },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .padding(bottom = 36.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (activeSection) {

                    // ── SOUND ─────────────────────────────────────────────────
                    SettingsSection.SOUND -> {
                        Text("Звук и вывод", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        Text("Аудиодвижок:", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        listOf(
                            Triple("AAudio (Low Latency Direct)", "Минимальная задержка, Android 8+ (рекомендуется)", 0),
                            Triple("AudioTrack OpenSL ES", "Максимальная совместимость", 1),
                            Triple("Direct Hi-Res DAC Passthrough", "Без передискретизации, прямое PCM для внешних ЦАП", 2)
                        ).forEach { (mode, desc, modeIndex) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        prefs.setAudioEngineMode(modeIndex)
                                        NativeAudioEngine.recreateStream(modeIndex)
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = audioEngineMode == modeIndex,
                                    onClick = {
                                        prefs.setAudioEngineMode(modeIndex)
                                        NativeAudioEngine.recreateStream(modeIndex)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = mode,
                                        fontSize = 14.sp,
                                        fontWeight = if (audioEngineMode == modeIndex) FontWeight.Bold else FontWeight.Normal,
                                        color = if (audioEngineMode == modeIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = desc,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        Text("Кроссфейд: ${crossfadeSeconds} сек", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = crossfadeSeconds.toFloat(),
                            onValueChange = { crossfadeSeconds = it.toInt() },
                            valueRange = 0f..10f,
                            steps = 9
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ReplayGain", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Выравнивание громкости треков", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(checked = replayGain, onCheckedChange = { replayGain = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Гапплесс (Gapless)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Плавный переход между треками без пауз", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(checked = gapless, onCheckedChange = { gapless = it })
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Color(0xFF00F5D4), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Фоновое воспроизведение", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Text(
                                    "Включен системный Foreground Service + WakeLock для предотвращения выгрузки плеера из RAM и сна процессора.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            try {
                                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Настройки батареи (Без ограничений)", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // ── SLEEP TIMER ───────────────────────────────────────────
                    SettingsSection.SLEEP_TIMER -> {
                        val isTimerActive by SleepTimerController.isActive.collectAsState()
                        val remainingSec by SleepTimerController.remainingSeconds.collectAsState()

                        Text("Таймер сна", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        if (isTimerActive) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "Оставшееся время:",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                        Text(
                                            text = SleepTimerController.formatRemainingTime(remainingSec),
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            SleepTimerController.cancelSleepTimer()
                                            android.widget.Toast.makeText(context, "Таймер сна выключен", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Отключить")
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Установить таймер автоматической остановки музыки:",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )

                        listOf(15L, 30L, 45L, 60L, 90L, 120L).forEach { minutes ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        SleepTimerController.startSleepTimer(minutes) {
                                            // Paused
                                        }
                                        android.widget.Toast.makeText(context, "Музыка остановится через $minutes мин", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "$minutes минут",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // ── LIBRARY ───────────────────────────────────────────────
                    SettingsSection.LIBRARY -> {
                        Text("Медиатека", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        val savedFolders = prefs.getSavedFolderUris()
                        if (savedFolders.isNotEmpty()) {
                            Text(
                                text = "Добавленные папки (${savedFolders.size}):",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            savedFolders.forEachIndexed { i, uri ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = uri.lastPathSegment ?: "Папка ${i + 1}",
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        }

                        Button(
                            onClick = {
                                onPickFolder()
                                activeSection = SettingsSection.NONE
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Добавить папку с музыкой")
                        }

                        Button(
                            onClick = {
                                onRescanLibrary()
                                activeSection = SettingsSection.NONE
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Пересканировать медиатеку")
                        }
                    }

                    // ── BLUETOOTH ─────────────────────────────────────────────
                    SettingsSection.BLUETOOTH -> {
                        Text("Гарнитура и Bluetooth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        SettingsToggleRow(
                            title = "Пауза при отключении наушников",
                            subtitle = "Автоматически ставить воспроизведение на паузу при разрыве соединения",
                            checked = autoPauseOnDisconnect,
                            onCheckedChange = { autoPauseOnDisconnect = it }
                        )
                        SettingsToggleRow(
                            title = "Возобновление при подключении",
                            subtitle = "Продолжать воспроизведение при повторном подключении Bluetooth",
                            checked = autoResumeOnConnect,
                            onCheckedChange = { autoResumeOnConnect = it }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "ℹ️ Bluetooth Кодеки (LDAC / aptX HD / AAC / SBC)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Выбор Bluetooth кодека управляется системным Bluetooth-стеком Android. Для включения максимального битрейта LDAC 990 kbps перейдите в «Настройки телефона → Параметры разработчика → Аудиокодек Bluetooth».",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }

                    // ── INTERFACE ─────────────────────────────────────────────
                    SettingsSection.INTERFACE -> {
                        Text("Оформление интерфейса", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        SettingsDropdownRow(
                            title = "Тема оформления",
                            subtitle = "Светлая, Тёмная или Pitch Black для OLED",
                            options = listOf("Светлая", "Тёмная", "AMOLED Pitch Black"),
                            selectedOption = when (themeMode) {
                                com.antigravity.audioplayer.data.storage.ThemeMode.LIGHT -> "Светлая"
                                com.antigravity.audioplayer.data.storage.ThemeMode.DARK -> "Тёмная"
                                com.antigravity.audioplayer.data.storage.ThemeMode.AMOLED -> "AMOLED Pitch Black"
                            },
                            onOptionSelected = {
                                val newMode = when (it) {
                                    "Светлая" -> com.antigravity.audioplayer.data.storage.ThemeMode.LIGHT
                                    "AMOLED Pitch Black" -> com.antigravity.audioplayer.data.storage.ThemeMode.AMOLED
                                    else -> com.antigravity.audioplayer.data.storage.ThemeMode.DARK
                                }
                                prefs.setThemeMode(newMode)
                            }
                        )

                        SettingsToggleRow(
                            title = "Показывать номера треков",
                            subtitle = "Отображать номер трека в альбоме",
                            checked = showTrackNumbers,
                            onCheckedChange = { showTrackNumbers = it }
                        )
                        SettingsToggleRow(
                            title = "Компактный список треков",
                            subtitle = "Меньший размер строк для больших библиотек",
                            checked = compactMode,
                            onCheckedChange = { compactMode = it }
                        )
                    }

                    // ── BACKGROUND ────────────────────────────────────────────
                    SettingsSection.BACKGROUND -> {
                        Text("Фон плеера", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        SettingsToggleRow(
                            title = "Размытие обложки на фоне",
                            subtitle = "Показывать обложку трека в размытом фоне плеера",
                            checked = isBlurBackground,
                            onCheckedChange = { prefs.setBlurBackground(it) }
                        )

                        if (isBlurBackground) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Степень размытия", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("$blurRadiusPercent%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(
                                value = blurRadiusPercent.toFloat(),
                                onValueChange = { prefs.setBlurRadiusPercent(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 19,
                                enabled = isBlurBackground
                            )
                        }
                    }

                    // ── COVERS ────────────────────────────────────────────────
                    SettingsSection.COVERS -> {
                        Text("Обложки треков", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        SettingsToggleRow(
                            title = "Встроенные обложки",
                            subtitle = "Отображать картинки из ID3/FLAC тегов аудиофайлов (если выключено — заглушка)",
                            checked = isEmbeddedCoversEnabled,
                            onCheckedChange = { prefs.setEmbeddedCoversEnabled(it) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                clearArtworkCache(context)
                                android.widget.Toast.makeText(context, "Кэш обложек успешно очищен", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Очистить кэш обложек", color = MaterialTheme.colorScheme.error)
                        }
                    }

                    // ── VISUALIZATION ─────────────────────────────────────────
                    SettingsSection.VISUALIZATION -> {
                        Text("Визуализация", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        Text(
                            "Визуализатор удалён для максимальной производительности прокрутки. Эквалайзер доступен в разделе «Эквалайзер».",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Equalizer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column {
                                    Text("10-полосный эквалайзер", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Доступен во вкладке «Эквалайзер»", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }

                    // ── LYRICS ────────────────────────────────────────────────
                    SettingsSection.LYRICS -> {
                        Text("Тексты песен (Lyrics)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Порядок поиска текстов песен:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                Text("1. Встроенные теги трека (FLAC Vorbis / ID3 USLT / M4A)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text("2. Файлы .lrc и .txt рядом с аудиофайлом в папке музыки", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text("3. Выделенная папка с текстами песен (настроена ниже)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text("4. Автоматический онлайн-поиск через LRCLIB (если включён)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        // LRCLIB Auto-fetch Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Автопоиск в LRCLIB", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    "Автоматически искать синхронизированные (.lrc) и обычные тексты в открытой базе LRCLIB",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                            Switch(
                                checked = isOnlineLyricsEnabled,
                                onCheckedChange = { prefs.setOnlineLyricsEnabled(it) }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        Text("Выделенная папка с текстами:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (lyricsFolderUri != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Папка активна", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                        Text(
                                            android.net.Uri.parse(lyricsFolderUri).lastPathSegment ?: lyricsFolderUri!!,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { prefs.setLyricsFolderUri(null) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Сбросить", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { lyricsFolderPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (lyricsFolderUri != null) "Изменить папку текстов" else "Выбрать папку с текстами (.lrc / .txt)")
                        }
                    }

                    // ── OTA UPDATES ──────────────────────────────────────────
                    SettingsSection.UPDATES -> {
                        val coroutineScope = rememberCoroutineScope()
                        val currentOtaStatus by OtaUpdateManager.status.collectAsState()
                        val repoName by prefs.otaGithubRepo.collectAsState()
                        val token by prefs.otaGithubToken.collectAsState()
                        val autoCheck by prefs.otaAutoCheck.collectAsState()

                        var repoInput by remember(repoName) { mutableStateOf(repoName) }
                        var tokenInput by remember(token) { mutableStateOf(token) }
                        var showTokenField by remember { mutableStateOf(token.isNotBlank()) }

                        LaunchedEffect(Unit) {
                            if (autoCheck && currentOtaStatus is OtaStatus.Idle) {
                                OtaUpdateManager.checkForUpdates(context)
                            }
                        }

                        Text("Обновления (OTA)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        // Current version info card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Текущая версия приложения", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    Text("v${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Android,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(8.dp).size(22.dp)
                                    )
                                }
                            }
                        }

                        // Status & Action Area
                        when (val st = currentOtaStatus) {
                            is OtaStatus.Idle -> {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            OtaUpdateManager.checkForUpdates(context)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Проверить обновления на GitHub")
                                }
                            }

                            is OtaStatus.Checking -> {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                        Text("Проверка релизов на GitHub...", fontSize = 13.sp)
                                    }
                                }
                            }

                            is OtaStatus.UpToDate -> {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.12f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("У вас установлена последняя версия", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Обновлений не требуется", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                        }
                                        IconButton(onClick = {
                                            coroutineScope.launch {
                                                OtaUpdateManager.checkForUpdates(context)
                                            }
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Проверить снова", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }

                            is OtaStatus.UpdateAvailable -> {
                                val rel = st.release
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("Доступна новая версия!", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                                Text("v${rel.versionName} • ${rel.title}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    "NEW",
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }

                                        if (rel.fileSize > 0) {
                                            val mb = rel.fileSize / (1024 * 1024)
                                            val kb = (rel.fileSize % (1024 * 1024)) / 100000
                                            Text(
                                                "Размер файла: $mb.$kb МБ",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                            )
                                        }

                                        if (rel.description.isNotBlank()) {
                                            Text(
                                                rel.description,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                                maxLines = 6
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val res = OtaUpdateManager.downloadUpdate(context, rel)
                                                    res.onSuccess { apkFile ->
                                                        OtaUpdateManager.installApk(context, apkFile)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Скачать и установить APK")
                                        }
                                    }
                                }
                            }

                            is OtaStatus.Downloading -> {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text("Скачивание обновления APK...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        LinearProgressIndicator(
                                            progress = { st.progressPercent / 100f },
                                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("${st.progressPercent}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            val mbDownloaded = st.downloadedBytes / (1024 * 1024)
                                            val mbTotal = st.totalBytes / (1024 * 1024)
                                            Text("$mbDownloaded МБ / $mbTotal МБ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                        }
                                    }
                                }
                            }

                            is OtaStatus.ReadyToInstall -> {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                                            Text("APK успешно скачан!", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                        Text("Нажмите кнопку ниже, чтобы открыть установщик Android.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                        Button(
                                            onClick = {
                                                OtaUpdateManager.installApk(context, st.apkFile)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Установить сейчас")
                                        }
                                    }
                                }
                            }

                            is OtaStatus.Error -> {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                            Text("Ошибка проверки", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                        Text(st.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    OtaUpdateManager.checkForUpdates(context)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Повторить попытку", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        // GitHub Configuration section
                        Text("Настройки репозитория GitHub:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                        OutlinedTextField(
                            value = repoInput,
                            onValueChange = {
                                repoInput = it
                                prefs.setOtaGithubRepo(it)
                            },
                            label = { Text("GitHub Репозиторий (owner/repo)") },
                            placeholder = { Text("debil/flacplayer") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Приватный репозиторий (Personal Token)", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            TextButton(onClick = { showTokenField = !showTokenField }) {
                                Text(if (showTokenField) "Скрыть токен" else "Указать токен", fontSize = 12.sp)
                            }
                        }

                        if (showTokenField) {
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = {
                                    tokenInput = it
                                    prefs.setOtaGithubToken(it)
                                },
                                label = { Text("GitHub Token (PAT)") },
                                placeholder = { Text("ghp_xxxxxxxxxxxxxxxxxxxx") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            Text(
                                "Необходим для скачивания релизов из приватного репозитория. Создаётся на GitHub: Settings -> Developer Settings -> Personal access tokens с правом 'repo'.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }

                        SettingsToggleRow(
                            title = "Автопроверка при открытии",
                            subtitle = "Искать обновления при открытии экрана настроек",
                            checked = autoCheck,
                            onCheckedChange = { prefs.setOtaAutoCheck(it) }
                        )
                    }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun SettingsDropdownRow(
    title: String,
    subtitle: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.padding(end = 48.dp)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = selectedOption,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                color = accentColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = title, tint = accentColor, modifier = Modifier.size(24.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}
