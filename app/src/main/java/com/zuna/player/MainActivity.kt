package com.zuna.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.zuna.player.audio.DacDetector
import com.zuna.player.audio.EqualizerController
import com.zuna.player.data.repository.AudioRepositoryImpl
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.data.storage.computeEffectivePowerSaver
import com.zuna.player.service.AudioPlayerService
import com.zuna.player.ui.components.DuplicateTrackEntry
import com.zuna.player.ui.components.deduplicateTracks
import com.zuna.player.ui.screens.MainScreen
import com.zuna.player.ui.theme.AntigravityPlayerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository = AudioRepositoryImpl()
    private lateinit var dacDetector: DacDetector
    private val equalizerController = EqualizerController()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        lifecycleScopeLaunch {
            repository.loadTracks(applicationContext)
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            lifecycleScopeLaunch {
                repository.scanCustomFolder(applicationContext, uri)
            }
        }
    }

    private fun lifecycleScopeLaunch(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    override fun onStart() {
        super.onStart()
        AudioPlayerService.setUiForeground(true)
    }

    override fun onStop() {
        AudioPlayerService.setUiForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        dacDetector = DacDetector.getInstance(applicationContext)

        AudioPlayerService.init(applicationContext)
        equalizerController.init(applicationContext)

        requestRequiredPermissions()

        setContent {
            val context = LocalContext.current
            val appPrefs = remember { LibraryPreferences.getInstance(context) }
            val themeConfig by appPrefs.themeConfig.collectAsState()
            val powerSaverCfg by appPrefs.powerSaverConfig.collectAsState()

            val powerManager = remember(context) {
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            }
            var isSystemPowerSave by remember { mutableStateOf(powerManager?.isPowerSaveMode == true) }
            var isLowBattery by remember { mutableStateOf(false) }

            DisposableEffect(context) {
                val readBattery: (Intent?) -> Unit = { intent ->
                    if (intent != null) {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                        if (level >= 0) {
                            val pct = (level * 100) / scale
                            isLowBattery = plugged == 0 && pct <= 20
                        }
                    }
                }
                val stickyFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                readBattery(context.registerReceiver(null, stickyFilter))

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        when (intent?.action) {
                            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                                isSystemPowerSave = powerManager?.isPowerSaveMode == true
                            }
                            Intent.ACTION_BATTERY_CHANGED -> {
                                readBattery(intent)
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                onDispose {
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
            }

            val effectivePowerSaver = remember(powerSaverCfg, isSystemPowerSave, isLowBattery) {
                computeEffectivePowerSaver(powerSaverCfg, isSystemPowerSave, isLowBattery).also {
                    appPrefs.effectiveLimitUiRefresh = it.limitUiRefresh
                }
            }

            AntigravityPlayerTheme(themeConfig = themeConfig, powerSaver = effectivePowerSaver) {
                val tracks by repository.tracksFlow.collectAsState(initial = emptyList())
                val isLoading by repository.isLoadingFlow.collectAsState(initial = true)
                val hiddenTrackIds by appPrefs.hiddenTrackIds.collectAsState()
                val hideDuplicateTracks by appPrefs.hideDuplicateTracks.collectAsState()
                val forceVisibleDuplicateIds by appPrefs.forceVisibleDuplicateIds.collectAsState()

                val nonManuallyHiddenTracks = remember(tracks, hiddenTrackIds) {
                    tracks.filter { it.id !in hiddenTrackIds }
                }
                val dedupResult = remember(nonManuallyHiddenTracks, forceVisibleDuplicateIds) {
                    deduplicateTracks(nonManuallyHiddenTracks, forceVisibleDuplicateIds)
                }
                val visibleTracks = remember(nonManuallyHiddenTracks, dedupResult, hideDuplicateTracks) {
                    if (hideDuplicateTracks) dedupResult.visibleTracks else nonManuallyHiddenTracks
                }
                val duplicateEntries: List<DuplicateTrackEntry> = remember(dedupResult, hideDuplicateTracks) {
                    if (hideDuplicateTracks) dedupResult.duplicateEntries else emptyList()
                }

                val currentTrack by AudioPlayerService.currentTrack.collectAsState()
                val activeQueue by AudioPlayerService.activeQueue.collectAsState()
                val currentTrackIndex by AudioPlayerService.currentTrackIndex.collectAsState()
                val isPlaying by AudioPlayerService.isPlaying.collectAsState()
                val isShuffleOn by AudioPlayerService.isShuffleOn.collectAsState()
                val repeatMode by AudioPlayerService.repeatMode.collectAsState()
                val dacInfo by dacDetector.activeDeviceFlow.collectAsState()

                LaunchedEffect(tracks) {
                    AudioPlayerService.updateAllLibraryTracks(tracks)
                }

                LaunchedEffect(visibleTracks) {
                    AudioPlayerService.setInitialTracks(visibleTracks)
                }

                MainScreen(
                    tracks = visibleTracks,
                    allTracks = tracks,
                    duplicateEntries = duplicateEntries,
                    queueTracks = activeQueue,
                    currentTrack = currentTrack,
                    currentTrackIndex = currentTrackIndex,
                    isPlaying = isPlaying,
                    dacInfo = dacInfo,
                    isShuffleOn = isShuffleOn,
                    repeatMode = repeatMode,
                    isLoading = isLoading,
                    onToggleShuffle = { AudioPlayerService.toggleShuffle() },
                    onToggleRepeat = { AudioPlayerService.toggleRepeat() },
                    eqController = equalizerController,
                    onTrackSelect = { selected, contextQueue ->
                        AudioPlayerService.playTrack(selected, contextQueue)
                    },
                    onPlayPauseToggle = {
                        AudioPlayerService.togglePlayPause()
                    },
                    onStopPlayback = {
                        AudioPlayerService.stopPlayback()
                    },
                    onNextTrack = {
                        AudioPlayerService.skipToNext()
                    },
                    onPreviousTrack = {
                        AudioPlayerService.skipToPrevious()
                    },
                    onSeekTo = { newPos ->
                        AudioPlayerService.seekTo(newPos)
                    },
                    onPickFolder = {
                        folderPickerLauncher.launch(null)
                    },
                    onRescanLibrary = {
                        lifecycleScopeLaunch {
                            val count = repository.rescanLibrary(applicationContext)
                            android.widget.Toast.makeText(
                                applicationContext,
                                "Библиотека обновлена: найдено $count треков",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            lifecycleScopeLaunch {
                repository.loadTracks(applicationContext)
            }
        }
    }
}
