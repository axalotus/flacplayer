package com.antigravity.audioplayer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.bluetooth.BluetoothDevice
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.antigravity.audioplayer.audio.DacDetector
import com.antigravity.audioplayer.audio.EqualizerController
import com.antigravity.audioplayer.audio.MediaNotificationManager
import com.antigravity.audioplayer.audio.NativeAudioEngine
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.AudioFormat
import com.antigravity.audioplayer.data.repository.AudioRepositoryImpl
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.service.AudioPlayerService
import com.antigravity.audioplayer.ui.components.RepeatMode
import com.antigravity.audioplayer.ui.screens.MainScreen
import com.antigravity.audioplayer.ui.theme.AntigravityPlayerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository = AudioRepositoryImpl()
    private lateinit var dacDetector: DacDetector
    private val equalizerController = EqualizerController()

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaNotificationManager.ACTION_PLAY -> AudioPlayerService.play()
                MediaNotificationManager.ACTION_PAUSE -> AudioPlayerService.pause()
                MediaNotificationManager.ACTION_NEXT -> AudioPlayerService.skipToNext()
                MediaNotificationManager.ACTION_PREV -> AudioPlayerService.skipToPrevious()
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    AudioPlayerService.pause()
                    dacDetector.updateDevice()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    AudioPlayerService.pause()
                    dacDetector.updateDevice()
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    dacDetector.updateDevice()
                }
            }
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dacDetector = DacDetector(this)

        NativeAudioEngine.initEngine()
        val savedEngineMode = LibraryPreferences.getInstance(this).audioEngineMode.value
        NativeAudioEngine.recreateStream(savedEngineMode)

        equalizerController.init(this)
        AudioPlayerService.init(this)
        AudioPlayerService.cleanLegacyAudioCache(this)

        val filter = IntentFilter().apply {
            addAction(MediaNotificationManager.ACTION_PLAY)
            addAction(MediaNotificationManager.ACTION_PAUSE)
            addAction(MediaNotificationManager.ACTION_NEXT)
            addAction(MediaNotificationManager.ACTION_PREV)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaActionReceiver, filter)
        }

        requestRequiredPermissions()

        setContent {
            val context = LocalContext.current
            val prefs = remember { LibraryPreferences.getInstance(context) }
            val themeMode by prefs.themeMode.collectAsState()

            AntigravityPlayerTheme(themeMode = themeMode) {
                val tracks by repository.tracksFlow.collectAsState(initial = emptyList())
                val isLoading by repository.isLoadingFlow.collectAsState(initial = true)
                val hiddenTrackIds by prefs.hiddenTrackIds.collectAsState()

                val visibleTracks = remember(tracks, hiddenTrackIds) {
                    tracks.filter { it.id !in hiddenTrackIds }
                }

                val currentTrack by AudioPlayerService.currentTrack.collectAsState()
                val activeQueue by AudioPlayerService.activeQueue.collectAsState()
                val currentTrackIndex by AudioPlayerService.currentTrackIndex.collectAsState()
                val isPlaying by AudioPlayerService.isPlaying.collectAsState()
                val progressMs by AudioPlayerService.progressMs.collectAsState()
                val isShuffleOn by AudioPlayerService.isShuffleOn.collectAsState()
                val repeatMode by AudioPlayerService.repeatMode.collectAsState()
                val dacInfo by dacDetector.activeDeviceFlow.collectAsState()
                var showVisualizer by remember { mutableStateOf(true) }

                LaunchedEffect(tracks) {
                    AudioPlayerService.updateAllLibraryTracks(tracks)
                }

                LaunchedEffect(visibleTracks) {
                    AudioPlayerService.setInitialTracks(visibleTracks)
                }

                MainScreen(
                    tracks = visibleTracks,
                    allTracks = tracks,
                    queueTracks = activeQueue,
                    currentTrack = currentTrack,
                    currentTrackIndex = currentTrackIndex,
                    isPlaying = isPlaying,
                    progressMs = progressMs,
                    dacInfo = dacInfo,
                    showVisualizer = showVisualizer,
                    isShuffleOn = isShuffleOn,
                    repeatMode = repeatMode,
                    isLoading = isLoading,
                    onToggleShuffle = { AudioPlayerService.toggleShuffle() },
                    onToggleRepeat = { AudioPlayerService.toggleRepeat() },
                    onToggleVisualizer = { showVisualizer = !showVisualizer },
                    eqController = equalizerController,
                    onTrackSelectIndex = { index ->
                        AudioPlayerService.playTrackAtIndex(index)
                    },
                    onTrackSelect = { selected, contextQueue ->
                        AudioPlayerService.playTrack(selected, contextQueue)
                    },
                    onPlayPauseToggle = {
                        AudioPlayerService.togglePlayPause()
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
                    },
                    onAddNavidromeServer = { serverUrl, user ->
                        lifecycleScopeLaunch {
                            repository.addNavidromeTrack(
                                applicationContext,
                                AudioFileMetadata(
                                    id = "navidrome_${System.currentTimeMillis()}",
                                    title = "Cloud Stream ($user)",
                                    artist = "Navidrome Hi-Res Server",
                                    album = "Navidrome Stream API Ready",
                                    durationMs = 420000,
                                    uri = android.net.Uri.parse("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3"),
                                    format = AudioFormat.FLAC,
                                    sampleRateHz = 96000,
                                    bitDepth = 24,
                                    bitrateKbps = 2400,
                                    isStream = true,
                                    streamServerUrl = serverUrl
                                )
                            )
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

    override fun onDestroy() {
        try {
            unregisterReceiver(mediaActionReceiver)
        } catch (e: Exception) {
            // Ignored if not registered
        }
        AudioPlayerService.release()
        dacDetector.release()
        equalizerController.release()
        super.onDestroy()
    }
}
