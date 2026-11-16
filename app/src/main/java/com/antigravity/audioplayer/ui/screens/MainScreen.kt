package com.antigravity.audioplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.audio.DacOutputInfo
import com.antigravity.audioplayer.audio.EqualizerController
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.data.storage.PlaylistManager
import com.antigravity.audioplayer.service.AudioPlayerService
import com.antigravity.audioplayer.ui.components.*
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme

enum class MainTab(val icon: ImageVector, val label: String) {
    HOME(Icons.Rounded.Home, "Обзор"),
    LIBRARY(Icons.Rounded.LibraryMusic, "Медиатека"),
    SEARCH(Icons.Rounded.Search, "Поиск"),
    SETTINGS(Icons.Rounded.Tune, "Настройки")
}

private sealed class DetailRoute {
    data class Album(val albumName: String) : DetailRoute()
    data class Artist(val artistName: String) : DetailRoute()
    data class PlaylistDetail(val playlistId: String) : DetailRoute()
    data class SectionTracks(val title: String, val trackIds: List<String>) : DetailRoute()
}

@Composable
fun MainScreen(
    tracks: List<AudioFileMetadata>,
    allTracks: List<AudioFileMetadata>,
    queueTracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    currentTrackIndex: Int,
    isPlaying: Boolean,
    dacInfo: DacOutputInfo,
    isShuffleOn: Boolean,
    repeatMode: RepeatMode,
    isLoading: Boolean,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    eqController: EqualizerController,
    onTrackSelect: (AudioFileMetadata, List<AudioFileMetadata>) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPickFolder: () -> Unit,
    onRescanLibrary: () -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val pm = remember { PlaylistManager.getInstance(context) }

    val favoriteIds by pm.favoriteIds.collectAsState()
    val hiddenIds by prefs.hiddenTrackIds.collectAsState()
    val playlists by pm.playlists.collectAsState()

    var activeTab by remember { mutableStateOf(MainTab.HOME) }
    var libraryCategory by remember { mutableStateOf(LibraryCategoryType.ALL_TRACKS) }
    var detailRoute by remember { mutableStateOf<DetailRoute?>(null) }
    var isPlayerExpanded by remember { mutableStateOf(false) }

    var menuTrack by remember { mutableStateOf<AudioFileMetadata?>(null) }
    var playlistTargetTrack by remember { mutableStateOf<AudioFileMetadata?>(null) }
    var infoTargetTrack by remember { mutableStateOf<AudioFileMetadata?>(null) }
    var showQueueOverlay by remember { mutableStateOf(false) }
    var showSleepOverlay by remember { mutableStateOf(false) }

    val hasAnyOverlay = menuTrack != null ||
        playlistTargetTrack != null ||
        infoTargetTrack != null ||
        showQueueOverlay ||
        showSleepOverlay

    BackHandler(
        enabled = hasAnyOverlay || isPlayerExpanded || detailRoute != null || activeTab != MainTab.HOME
    ) {
        when {
            menuTrack != null -> menuTrack = null
            playlistTargetTrack != null -> playlistTargetTrack = null
            infoTargetTrack != null -> infoTargetTrack = null
            showQueueOverlay -> showQueueOverlay = false
            showSleepOverlay -> showSleepOverlay = false
            isPlayerExpanded -> isPlayerExpanded = false
            detailRoute != null -> detailRoute = null
            activeTab != MainTab.HOME -> activeTab = MainTab.HOME
        }
    }

    val tracksById = remember(tracks) { tracks.associateBy { it.id } }

    // Space needed above bottom nav + floating mini player
    val navReservedHeight = if (currentTrack != null) 144.dp else 74.dp

    val screenBackdrop = rememberFlacBackdropState()
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }

    val frostedBmp by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = "${tokens.config.appBgMode}|${tokens.config.appBgCustomPath}|${tokens.config.appBgIsVideo}",
        key2 = currentTrack?.id,
        key3 = "${tokens.config.appBgBlurPx}|${tokens.config.panelBlurPx}|${tokens.config.appBgColorMode}"
    ) {
        value = getPanelFrostedBg(
            context = context,
            mode = tokens.config.appBgMode,
            customPath = tokens.config.appBgCustomPath,
            isVideo = tokens.config.appBgIsVideo,
            bgBlurPx = tokens.config.appBgBlurPx,
            panelBlurPx = tokens.config.panelBlurPx,
            monochrome = tokens.config.appBgColorMode != "color",
            track = currentTrack
        )?.asImageBitmap()
    }

    val frostedInfo = remember(frostedBmp, rootSize, rootOffset, tokens.config.appBgDimPercent, tokens.config.appBgMode) {
        FrostedBgInfo(
            bitmap = frostedBmp,
            rootWidthPx = rootSize.width,
            rootHeightPx = rootSize.height,
            rootOffset = rootOffset,
            dimAlpha = if (tokens.config.appBgMode != "none") (tokens.config.appBgDimPercent.coerceIn(15, 95)) / 100f else 0f
        )
    }

    CompositionLocalProvider(
        LocalFrostedBg provides frostedInfo,
        LocalScreenBackdrop provides screenBackdrop
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { rootSize = it }
                .onGloballyPositioned { rootOffset = it.positionInRoot() }
        ) {
            // Isolated App Background Layer OUTSIDE screenBackdrop so scrolling never re-blurs wallpaper
            FlacBackgroundLayer(
                mode = tokens.config.appBgMode,
                colorMode = tokens.config.appBgColorMode,
                customPath = tokens.config.appBgCustomPath,
                isVideo = tokens.config.appBgIsVideo,
                blurPx = tokens.config.appBgBlurPx,
                dimPercent = tokens.config.appBgDimPercent,
                currentTrack = currentTrack
            )

            // Tab content recorded alone into screenBackdrop (when dock/overlays visible)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!isPlayerExpanded && tokens.config.panelBlurPx > 0) {
                            Modifier.flacBackdropSource(screenBackdrop)
                        } else Modifier
                    )
            ) {
                // Active Tab or Detail Content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
            val activeDetail = detailRoute
            if (activeDetail != null) {
                when (activeDetail) {
                    is DetailRoute.Album -> {
                        val albumTracks = remember(tracks, activeDetail.albumName) {
                            tracks.filter {
                                it.album.ifBlank { "Без альбома" } == activeDetail.albumName
                            }.sortedBy { it.trackNumber }
                        }
                        TrackCollectionDetailView(
                            title = activeDetail.albumName,
                            subtitle = albumTracks.firstOrNull()?.artist,
                            tracks = albumTracks,
                            heroTrack = albumTracks.firstOrNull(),
                            currentTrack = currentTrack,
                            onBack = { detailRoute = null },
                            onPlayTrack = onTrackSelect,
                            onOpenTrackMenu = { menuTrack = it },
                            bottomInsetPadding = navReservedHeight
                        )
                    }
                    is DetailRoute.Artist -> {
                        val artistTracks = remember(tracks, activeDetail.artistName) {
                            tracks.filter {
                                it.artist.ifBlank { "Неизвестный" } == activeDetail.artistName
                            }.sortedBy { it.title.lowercase() }
                        }
                        TrackCollectionDetailView(
                            title = activeDetail.artistName,
                            subtitle = "${artistTracks.map { it.album }.distinct().size} альбомов",
                            tracks = artistTracks,
                            heroTrack = artistTracks.firstOrNull(),
                            currentTrack = currentTrack,
                            onBack = { detailRoute = null },
                            onPlayTrack = onTrackSelect,
                            onOpenTrackMenu = { menuTrack = it },
                            bottomInsetPadding = navReservedHeight
                        )
                    }
                    is DetailRoute.PlaylistDetail -> {
                        val pl = playlists.firstOrNull { it.id == activeDetail.playlistId }
                        val plTracks = remember(pl, tracksById) {
                            pl?.trackIds?.mapNotNull { tracksById[it] }.orEmpty()
                        }
                        TrackCollectionDetailView(
                            title = pl?.name ?: "Плейлист",
                            subtitle = null,
                            tracks = plTracks,
                            heroTrack = plTracks.firstOrNull(),
                            currentTrack = currentTrack,
                            onBack = { detailRoute = null },
                            onPlayTrack = onTrackSelect,
                            onOpenTrackMenu = { menuTrack = it },
                            bottomInsetPadding = navReservedHeight
                        )
                    }
                    is DetailRoute.SectionTracks -> {
                        val sectionTracks = remember(activeDetail.trackIds, tracksById) {
                            activeDetail.trackIds.mapNotNull { tracksById[it] }
                        }
                        TrackCollectionDetailView(
                            title = activeDetail.title,
                            subtitle = null,
                            tracks = sectionTracks,
                            heroTrack = sectionTracks.firstOrNull(),
                            currentTrack = currentTrack,
                            onBack = { detailRoute = null },
                            onPlayTrack = onTrackSelect,
                            onOpenTrackMenu = { menuTrack = it },
                            bottomInsetPadding = navReservedHeight
                        )
                    }
                }
            } else {
                AnimatedContent(
                    targetState = activeTab,
                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(110)) },
                    label = "tabSwitch"
                ) { tab ->
                    when (tab) {
                        MainTab.HOME -> HomeScreen(
                            tracks = tracks,
                            currentTrack = currentTrack,
                            isLoading = isLoading,
                            onPlayTrack = onTrackSelect,
                            onOpenSectionTracks = { title, list ->
                                detailRoute = DetailRoute.SectionTracks(title, list.map { it.id })
                            },
                            onOpenTrackMenu = { menuTrack = it },
                            bottomInsetPadding = navReservedHeight
                        )
                        MainTab.LIBRARY -> LibraryScreen(
                            tracks = tracks,
                            currentTrack = currentTrack,
                            selectedCategory = libraryCategory,
                            onCategoryChange = { libraryCategory = it },
                            onPlayTrack = onTrackSelect,
                            onOpenAlbum = { album -> detailRoute = DetailRoute.Album(album) },
                            onOpenArtist = { artist -> detailRoute = DetailRoute.Artist(artist) },
                            onOpenPlaylist = { pl -> detailRoute = DetailRoute.PlaylistDetail(pl.id) },
                            onOpenSectionTracks = { title, list ->
                                detailRoute = DetailRoute.SectionTracks(title, list.map { it.id })
                            },
                            onOpenTrackMenu = { menuTrack = it },
                            bottomInsetPadding = navReservedHeight
                        )
                        MainTab.SEARCH -> SearchScreen(
                            tracks = tracks,
                            currentTrack = currentTrack,
                            onPlayTrack = onTrackSelect,
                            onOpenAlbum = { album -> detailRoute = DetailRoute.Album(album) },
                            onOpenArtist = { artist -> detailRoute = DetailRoute.Artist(artist) },
                            onOpenTrackMenu = { menuTrack = it },
                            bottomInsetPadding = navReservedHeight
                        )
                        MainTab.SETTINGS -> SettingsScreen(
                            eqController = eqController,
                            dacInfo = dacInfo,
                            hiddenCount = hiddenIds.size,
                            onPickFolder = onPickFolder,
                            onRescanLibrary = onRescanLibrary,
                            bottomInsetPadding = navReservedHeight
                        )
                    }
                }
            }
            }
            }

            // Bottom Floating Dock (Mini-Player + Icon-Only Nav Bar) sampling screenBackdrop
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentTrack != null) {
                    FloatingMiniPlayer(
                        track = currentTrack,
                        isPlaying = isPlaying,
                        onExpand = { isPlayerExpanded = true },
                        onPlayPause = onPlayPauseToggle,
                        onNext = onNextTrack,
                        onPrevious = onPreviousTrack
                    )
                }

                IconOnlyNavBar(
                    activeTab = activeTab,
                    onSelectTab = { next ->
                        if (next == activeTab) {
                            detailRoute = null
                        } else {
                            detailRoute = null
                            activeTab = next
                        }
                    }
                )
            }

        // Fullscreen Player Overlay
        AnimatedVisibility(
            visible = isPlayerExpanded && currentTrack != null,
            enter = slideInVertically(tween(240)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160))
        ) {
            PlayerOverlay(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                isShuffleOn = isShuffleOn,
                repeatMode = repeatMode,
                isFavorite = currentTrack != null && favoriteIds.contains(currentTrack.id),
                onCollapse = { isPlayerExpanded = false },
                onPlayPauseToggle = onPlayPauseToggle,
                onNextTrack = onNextTrack,
                onPreviousTrack = onPreviousTrack,
                onSeekTo = onSeekTo,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onToggleFavorite = {
                    currentTrack?.let { pm.toggleFavorite(it.id) }
                },
                onOpenQueue = { showQueueOverlay = true },
                onOpenPlaylistPicker = { playlistTargetTrack = currentTrack },
                onOpenSleepTimer = { showSleepOverlay = true },
                onOpenTrackMenu = { menuTrack = currentTrack },
                onGoToArtist = { artist ->
                    isPlayerExpanded = false
                    detailRoute = DetailRoute.Artist(artist)
                }
            )
        }

        // Clean Glass Overlays (Track options, Playlist picker, Queue, Sleep timer, Info)
        TrackOptionsOverlay(
            track = menuTrack,
            isFavorite = menuTrack != null && favoriteIds.contains(menuTrack!!.id),
            onDismiss = { menuTrack = null },
            onPlayNext = { AudioPlayerService.playNext(it) },
            onAddToQueue = { AudioPlayerService.addToQueue(it) },
            onToggleFavorite = { pm.toggleFavorite(it.id) },
            onAddToPlaylist = { track ->
                menuTrack = null
                playlistTargetTrack = track
            },
            onGoToAlbum = { album ->
                isPlayerExpanded = false
                detailRoute = DetailRoute.Album(album)
            },
            onGoToArtist = { artist ->
                isPlayerExpanded = false
                detailRoute = DetailRoute.Artist(artist)
            },
            onShowInfo = { track ->
                menuTrack = null
                infoTargetTrack = track
            },
            onHideTrack = { prefs.hideTrack(it.id) }
        )

        PlaylistPickerOverlay(
            track = playlistTargetTrack,
            onDismiss = { playlistTargetTrack = null }
        )

        QueueOverlay(
            visible = showQueueOverlay,
            queueTracks = queueTracks,
            currentIndex = currentTrackIndex,
            repeatMode = repeatMode,
            isShuffleOn = isShuffleOn,
            onDismiss = { showQueueOverlay = false },
            onPlayIndex = { idx ->
                AudioPlayerService.playTrackAtIndex(idx, queueTracks, startPlayback = true)
            },
            onRemoveIndex = { idx ->
                AudioPlayerService.removeFromQueue(idx)
            },
            onClearUpcoming = { AudioPlayerService.clearUpcomingQueue() },
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat
        )

        SleepTimerOverlay(
            visible = showSleepOverlay,
            onDismiss = { showSleepOverlay = false },
            onTimerExpired = {
                if (AudioPlayerService.isPlaying.value) {
                    AudioPlayerService.pause()
                }
            }
        )

        TrackInfoOverlay(
            track = infoTargetTrack,
            onDismiss = { infoTargetTrack = null }
        )
        }
    }
}

@Composable
private fun FloatingMiniPlayer(
    track: AudioFileMetadata,
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    val shape = RoundedCornerShape(tokens.radiusMd)
    val backdrop = LocalScreenBackdrop.current
    val frostedBg = LocalFrostedBg.current
    var hAccum by remember { mutableFloatStateOf(0f) }
    var vAccum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .flacGlassBackdrop(
                source = backdrop,
                frostedBg = frostedBg,
                blurRadius = tokens.config.panelBlurPx.dp,
                tintColor = tokens.bg1,
                shape = shape
            )
            .border(1.dp, tokens.lineStrong, shape)
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragStart = { hAccum = 0f },
                    onHorizontalDrag = { _, dragAmount -> hAccum += dragAmount },
                    onDragEnd = {
                        if (hAccum < -65f) onNext()
                        else if (hAccum > 65f) onPrevious()
                        hAccum = 0f
                    }
                )
            }
            .pointerInput(track.id) {
                detectVerticalDragGestures(
                    onDragStart = { vAccum = 0f },
                    onVerticalDrag = { _, dragAmount -> vAccum += dragAmount },
                    onDragEnd = {
                        if (vAccum < -45f) onExpand()
                        vAccum = 0f
                    }
                )
            }
            .clickable(onClick = onExpand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TrackArtwork(
                track = track,
                modifier = Modifier.size(40.dp),
                cornerRadius = tokens.radiusSm,
                iconSize = 18.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = tokens.fg0,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = tokens.fg2,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(tokens.radiusSm))
                    .background(tokens.accent)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = tokens.accentFg,
                    modifier = Modifier.size(19.dp)
                )
            }
            FlacIconButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = "Следующий",
                onClick = onNext,
                size = 34.dp,
                iconSize = 20.dp,
                tint = tokens.fg1
            )
        }
        MiniIsolatedProgressBar(track = track)
    }
}

@Composable
private fun MiniIsolatedProgressBar(track: AudioFileMetadata) {
    val tokens = LocalFlacTheme.current
    val progressMs by AudioPlayerService.progressMs.collectAsState()
    val serviceDurationMs by AudioPlayerService.durationMs.collectAsState()
    val duration = (serviceDurationMs.takeIf { it > 0L } ?: track.durationMs).coerceAtLeast(1L)
    val fraction = (progressMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(tokens.line)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(tokens.accent)
        )
    }
}

@Composable
private fun IconOnlyNavBar(
    activeTab: MainTab,
    onSelectTab: (MainTab) -> Unit
) {
    val tokens = LocalFlacTheme.current
    val shape = RoundedCornerShape(tokens.radiusLg)
    val backdrop = LocalScreenBackdrop.current
    val frostedBg = LocalFrostedBg.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .flacGlassBackdrop(
                source = backdrop,
                frostedBg = frostedBg,
                blurRadius = tokens.config.panelBlurPx.dp,
                tintColor = tokens.bg1,
                shape = shape
            )
            .border(1.dp, tokens.line, shape)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainTab.values().forEach { tab ->
            val selected = tab == activeTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .clip(RoundedCornerShape(tokens.radiusMd))
                    .background(
                        if (selected) tokens.accent.copy(alpha = 0.14f)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onSelectTab(tab) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) tokens.accent else tokens.fg2,
                    modifier = Modifier.size(23.dp)
                )
            }
        }
    }
}
