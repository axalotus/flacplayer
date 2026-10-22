package com.antigravity.audioplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import com.antigravity.audioplayer.audio.DacOutputInfo
import com.antigravity.audioplayer.audio.EqualizerController
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.data.storage.ThemeMode
import com.antigravity.audioplayer.ui.components.AudioChainModalSheet
import com.antigravity.audioplayer.ui.components.MiniPlayerBar
import com.antigravity.audioplayer.ui.components.QueueModalSheet
import com.antigravity.audioplayer.ui.components.RepeatMode

enum class MainTab {
    HOME, LIBRARY, PLAYLISTS, SEARCH, SETTINGS
}

@Composable
fun MainScreen(
    tracks: List<AudioFileMetadata>,
    allTracks: List<AudioFileMetadata> = tracks,
    queueTracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    currentTrackIndex: Int,
    isPlaying: Boolean,
    progressMs: Long,
    dacInfo: DacOutputInfo,
    showVisualizer: Boolean,
    isShuffleOn: Boolean,
    repeatMode: RepeatMode,
    isLoading: Boolean,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleVisualizer: () -> Unit,
    eqController: EqualizerController,
    onTrackSelectIndex: (Int) -> Unit,
    onTrackSelect: (AudioFileMetadata, List<AudioFileMetadata>?) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPickFolder: () -> Unit,
    onRescanLibrary: () -> Unit,
    onAddNavidromeServer: (String, String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(MainTab.HOME) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var showQueueModal by remember { mutableStateOf(false) }
    var showAudioChainModal by remember { mutableStateOf(false) }
    // EQ screen overlay (triggered from Settings)
    var showEqualizer by remember { mutableStateOf(false) }

    // Full-screen Album & Artist Details
    var selectedAlbumName by remember { mutableStateOf<String?>(null) }
    var selectedAlbumTracks by remember { mutableStateOf<List<AudioFileMetadata>>(emptyList()) }
    var selectedArtistName by remember { mutableStateOf<String?>(null) }
    var selectedArtistTracks by remember { mutableStateOf<List<AudioFileMetadata>>(emptyList()) }

    var selectedLibraryCategory by remember { mutableStateOf<LibraryCategoryType?>(null) }

    val isEqActive by eqController.isEnabled.collectAsState()
    val eqPresetName by eqController.selectedPresetName.collectAsState()
    val bassBoostVal by eqController.bassBoostStrength.collectAsState()

    val context = LocalContext.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val themeMode by prefs.themeMode.collectAsState()
    val audioEngineMode by prefs.audioEngineMode.collectAsState()

    var showExitDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (selectedAlbumName != null) selectedAlbumName = null
        else if (selectedArtistName != null) selectedArtistName = null
        else if (showEqualizer) showEqualizer = false
        else if (showAudioChainModal) showAudioChainModal = false
        else if (showQueueModal) showQueueModal = false
        else if (isPlayerExpanded) isPlayerExpanded = false
        else if (selectedLibraryCategory != null) selectedLibraryCategory = null
        else showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Выход") },
            text = { Text("Вы действительно хотите выйти из приложения?") },
            confirmButton = {
                TextButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                    Text("Выйти", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Отмена") }
            },
            containerColor = if (themeMode == ThemeMode.AMOLED) Color(0xFF121212) else AlertDialogDefaults.containerColor
        )
    }

    val isAnyOverlayOpen = isPlayerExpanded || showEqualizer || selectedAlbumName != null || selectedArtistName != null

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = if (themeMode == ThemeMode.AMOLED) Color.Black else MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    // Mini-Player (only when collapsed and track exists)
                    if (!isAnyOverlayOpen && currentTrack != null) {
                        MiniPlayerBar(
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            progressMs = progressMs,
                            onPlayPauseToggle = onPlayPauseToggle,
                            onNextTrack = onNextTrack,
                            onClickExpand = { isPlayerExpanded = true }
                        )
                    }

                    // Bottom Navigation Bar: Home | Library | Playlists | Search | Settings
                    NavigationBar(
                        containerColor = if (themeMode == ThemeMode.AMOLED) Color.Black else MaterialTheme.colorScheme.surface
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == MainTab.HOME,
                            alwaysShowLabel = false,
                            onClick = {
                                selectedTab = MainTab.HOME
                                isPlayerExpanded = false
                                showEqualizer = false
                                selectedAlbumName = null
                                selectedArtistName = null
                            },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Главная") },
                            label = { Text("Главная", fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )

                        NavigationBarItem(
                            selected = selectedTab == MainTab.LIBRARY,
                            alwaysShowLabel = false,
                            onClick = {
                                selectedTab = MainTab.LIBRARY
                                selectedLibraryCategory = null
                                isPlayerExpanded = false
                                showEqualizer = false
                                selectedAlbumName = null
                                selectedArtistName = null
                            },
                            icon = { Icon(Icons.Default.GridView, contentDescription = "Библиотека") },
                            label = { Text("Библиотека", fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )

                        NavigationBarItem(
                            selected = selectedTab == MainTab.PLAYLISTS,
                            alwaysShowLabel = false,
                            onClick = {
                                selectedTab = MainTab.PLAYLISTS
                                isPlayerExpanded = false
                                showEqualizer = false
                                selectedAlbumName = null
                                selectedArtistName = null
                            },
                            icon = { Icon(Icons.Default.Favorite, contentDescription = "Избранное") },
                            label = { Text("Избранное", fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )

                        NavigationBarItem(
                            selected = selectedTab == MainTab.SEARCH,
                            alwaysShowLabel = false,
                            onClick = {
                                selectedTab = MainTab.SEARCH
                                isPlayerExpanded = false
                                showEqualizer = false
                                selectedAlbumName = null
                                selectedArtistName = null
                            },
                            icon = { Icon(Icons.Default.Search, contentDescription = "Поиск") },
                            label = { Text("Поиск", fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )

                        NavigationBarItem(
                            selected = selectedTab == MainTab.SETTINGS || showEqualizer,
                            alwaysShowLabel = false,
                            onClick = {
                                selectedTab = MainTab.SETTINGS
                                isPlayerExpanded = false
                                showEqualizer = false
                                selectedAlbumName = null
                                selectedArtistName = null
                            },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                            label = { Text("Настройки", fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (selectedTab) {
                    MainTab.HOME -> HomeScreen(
                        tracks = tracks,
                        currentTrack = currentTrack,
                        onTrackSelect = onTrackSelect,
                        onAlbumSelect = { albumName, albumTracks ->
                            selectedAlbumName = albumName
                            selectedAlbumTracks = albumTracks
                        },
                        onArtistSelect = { artistName, artistTracks ->
                            selectedArtistName = artistName
                            selectedArtistTracks = artistTracks
                        },
                        onNavigateToCategory = { category ->
                            selectedLibraryCategory = category
                            selectedTab = MainTab.LIBRARY
                        },
                        onOpenSearch = { selectedTab = MainTab.SEARCH }
                    )

                    MainTab.LIBRARY -> LibraryScreen(
                        tracks = tracks,
                        allTracks = allTracks,
                        currentTrack = currentTrack,
                        isLoading = isLoading,
                        initialCategory = selectedLibraryCategory,
                        onCategoryChange = { selectedLibraryCategory = it },
                        onTrackSelect = onTrackSelect,
                        onAlbumSelect = { albumName, albumTracks ->
                            selectedAlbumName = albumName
                            selectedAlbumTracks = albumTracks
                        },
                        onArtistSelect = { artistName, artistTracks ->
                            selectedArtistName = artistName
                            selectedArtistTracks = artistTracks
                        },
                        onPickFolder = onPickFolder,
                        onRescanLibrary = onRescanLibrary,
                        onAddNavidromeServer = onAddNavidromeServer,
                        onOpenPowerampMenu = { selectedTab = MainTab.SETTINGS },
                        onOpenSearch = { selectedTab = MainTab.SEARCH }
                    )

                    MainTab.PLAYLISTS -> PlaylistsScreen(
                        allTracks = tracks,
                        currentTrack = currentTrack,
                        onTrackSelect = onTrackSelect
                    )

                    MainTab.SEARCH -> SearchScreen(
                        tracks = tracks,
                        currentTrack = currentTrack,
                        onTrackSelect = onTrackSelect,
                        onBack = { showExitDialog = true }
                    )

                    MainTab.SETTINGS -> SettingsScreen(
                        onBack = { showExitDialog = true },
                        onPickFolder = onPickFolder,
                        onOpenAudioChain = { showAudioChainModal = true },
                        onOpenEqualizer = { showEqualizer = true },
                        onRescanLibrary = onRescanLibrary
                    )
                }
            }
        }

        // ── Full-Screen Artist Details ──────────────────────────────────────
        AnimatedVisibility(
            visible = selectedArtistName != null,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            if (selectedArtistName != null) {
                ArtistDetailScreen(
                    artistName = selectedArtistName!!,
                    tracks = selectedArtistTracks,
                    currentTrackId = currentTrack?.id,
                    onTrackSelect = { tr -> onTrackSelect(tr, selectedArtistTracks) },
                    onAlbumSelect = { albumName, albumTracks ->
                        selectedAlbumName = albumName
                        selectedAlbumTracks = albumTracks
                    },
                    onPlayAll = {
                        selectedArtistTracks.firstOrNull()?.let { onTrackSelect(it, selectedArtistTracks) }
                    },
                    onShufflePlay = {
                        val shuffled = selectedArtistTracks.shuffled(kotlin.random.Random(System.currentTimeMillis()))
                        shuffled.firstOrNull()?.let {
                            com.antigravity.audioplayer.service.AudioPlayerService.setShuffle(true)
                            onTrackSelect(it, shuffled)
                        }
                    },
                    onBack = { selectedArtistName = null }
                )
            }
        }

        // ── Full-Screen Album Details ───────────────────────────────────────
        AnimatedVisibility(
            visible = selectedAlbumName != null,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            if (selectedAlbumName != null) {
                AlbumDetailScreen(
                    albumName = selectedAlbumName!!,
                    artistName = selectedAlbumTracks.firstOrNull()?.artist ?: "Various Artists",
                    tracks = selectedAlbumTracks,
                    currentTrackId = currentTrack?.id,
                    onTrackSelect = { tr -> onTrackSelect(tr, selectedAlbumTracks) },
                    onPlayAll = {
                        val sorted = selectedAlbumTracks.sortedWith(
                            compareBy<AudioFileMetadata> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                                .thenBy { it.title.lowercase() }
                        )
                        sorted.firstOrNull()?.let { onTrackSelect(it, sorted) }
                    },
                    onShufflePlay = {
                        val shuffled = selectedAlbumTracks.shuffled(kotlin.random.Random(System.currentTimeMillis()))
                        shuffled.firstOrNull()?.let {
                            com.antigravity.audioplayer.service.AudioPlayerService.setShuffle(true)
                            onTrackSelect(it, shuffled)
                        }
                    },
                    onBack = { selectedAlbumName = null }
                )
            }
        }

        // ── Full-Screen Player ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = isPlayerExpanded,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 280,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 240,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerScreen(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                progressMs = progressMs,
                dacInfo = dacInfo,
                showVisualizer = showVisualizer,
                isShuffleOn = isShuffleOn,
                repeatMode = repeatMode,
                queueTracks = queueTracks,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onToggleVisualizer = onToggleVisualizer,
                onPlayPauseToggle = onPlayPauseToggle,
                onNextTrack = onNextTrack,
                onPreviousTrack = onPreviousTrack,
                onSeekTo = onSeekTo,
                onTrackSelect = { tr -> onTrackSelect(tr, queueTracks) },
                onOpenAudioChain = { showAudioChainModal = true },
                onOpenEqualizer = {
                    isPlayerExpanded = false
                    selectedTab = MainTab.SETTINGS
                    showEqualizer = true
                },
                onOpenQueue = { showQueueModal = true },
                onOpenArtist = { artistName ->
                    isPlayerExpanded = false
                    selectedArtistName = artistName
                    selectedArtistTracks = tracks.filter { it.artist.equals(artistName, ignoreCase = true) }
                },
                onCollapsePlayer = { isPlayerExpanded = false }
            )
        }

        // ── Equalizer Overlay (from Settings) ──────────────────────────────
        AnimatedVisibility(
            visible = showEqualizer,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                EqualizerScreen(
                    eqController = eqController,
                    onBack = { showEqualizer = false }
                )
            }
        }

        // ── Queue Modal ─────────────────────────────────────────────────────
        if (showQueueModal) {
            QueueModalSheet(
                queueTracks = queueTracks,
                currentTrackIndex = currentTrackIndex,
                isShuffleOn = isShuffleOn,
                repeatMode = repeatMode,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onTrackSelect = { index ->
                    onTrackSelectIndex(index)
                    showQueueModal = false
                },
                onDismiss = { showQueueModal = false }
            )
        }

        // ── Audio Chain Modal (only from player info button or settings) ────
        if (showAudioChainModal && currentTrack != null) {
            AudioChainModalSheet(
                metadata = currentTrack,
                dacInfo = dacInfo,
                isEqActive = isEqActive,
                eqPresetName = eqPresetName,
                bassBoostVal = bassBoostVal,
                engineMode = audioEngineMode,
                onDismiss = { showAudioChainModal = false }
            )
        }
    }
}

