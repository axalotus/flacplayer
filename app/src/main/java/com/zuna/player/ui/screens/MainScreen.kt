package com.zuna.player.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.zuna.player.audio.DacOutputInfo
import com.zuna.player.audio.EqualizerController
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.data.storage.PlaylistManager
import com.zuna.player.service.AudioPlayerService
import com.zuna.player.ui.components.*
import com.zuna.player.ui.theme.LocalFlacTheme
import com.zuna.player.ui.theme.LocalPowerSaver

enum class MainTab(val icon: ImageVector, val label: String) {
    HOME(Icons.Rounded.Home, "Обзор"),
    LIBRARY(Icons.Rounded.LibraryMusic, "Медиатека"),
    SEARCH(Icons.Rounded.Search, "Поиск"),
    SETTINGS(Icons.Rounded.Tune, "Настройки")
}

private sealed class DetailRoute {
    data class Album(val albumName: String, val artistName: String) : DetailRoute()
    data class Artist(val artistName: String) : DetailRoute()
    data class PlaylistDetail(val playlistId: String) : DetailRoute()
    data class SectionTracks(val title: String, val trackIds: List<String>) : DetailRoute()
}

@Composable
fun MainScreen(
    tracks: List<AudioFileMetadata>,
    allTracks: List<AudioFileMetadata>,
    duplicateEntries: List<DuplicateTrackEntry> = emptyList(),
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
    onStopPlayback: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPickFolder: () -> Unit,
    onRescanLibrary: () -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val powerSaver = LocalPowerSaver.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val pm = remember { PlaylistManager.getInstance(context) }

    val favoriteIds by pm.favoriteIds.collectAsState()
    val hiddenIds by prefs.hiddenTrackIds.collectAsState()
    val playlists by pm.playlists.collectAsState()

    var activeTab by remember { mutableStateOf(MainTab.HOME) }
    var visitedTabs by remember { mutableStateOf(setOf(MainTab.HOME)) }
    LaunchedEffect(activeTab) {
        if (!visitedTabs.contains(activeTab)) {
            visitedTabs = visitedTabs + activeTab
        }
    }
    var libraryCategory by remember { mutableStateOf(LibraryCategoryType.ALL_TRACKS) }
    var detailRoute by remember { mutableStateOf<DetailRoute?>(null) }
    var isPlayerExpanded by remember { mutableStateOf(false) }

    var menuTrack by remember { mutableStateOf<AudioFileMetadata?>(null) }
    var playlistTargetTrack by remember { mutableStateOf<AudioFileMetadata?>(null) }
    var infoTargetTrack by remember { mutableStateOf<AudioFileMetadata?>(null) }
    var showQueueOverlay by remember { mutableStateOf(false) }
    var showSleepOverlay by remember { mutableStateOf(false) }
    var colorPickerRequest by remember { mutableStateOf<ColorPickerRequest?>(null) }

    val hasAnyOverlay = menuTrack != null ||
        playlistTargetTrack != null ||
        infoTargetTrack != null ||
        showQueueOverlay ||
        showSleepOverlay ||
        colorPickerRequest != null

    BackHandler(
        enabled = hasAnyOverlay || isPlayerExpanded || detailRoute != null || activeTab != MainTab.HOME
    ) {
        when {
            colorPickerRequest != null -> colorPickerRequest = null
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

    // Dynamically measure dock height (above navigation bars) so fontScale > 1.0 never clips or overlaps
    val density = LocalDensity.current
    val navInsetBottomPx = WindowInsets.navigationBars.getBottom(density)
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val navReservedHeight = if (dockHeightPx > 0) {
        with(density) { (dockHeightPx - navInsetBottomPx).coerceAtLeast(0).toDp() } + 8.dp
    } else {
        if (currentTrack != null) 124.dp else 58.dp
    }

    val expandProgress = remember { Animatable(0f) }
    val playerSwipeOffset = remember { Animatable(0f) }
    val playerSwipeDragRaw = remember { mutableFloatStateOf(0f) }
    var miniPlayerBounds by remember { mutableStateOf(Rect.Zero) }
    var navBarHeightPx by remember { mutableIntStateOf(0) }

    val showFullscreenPlayer = isPlayerExpanded && currentTrack != null
    LaunchedEffect(showFullscreenPlayer, powerSaver.reduceAnimations) {
        if (showFullscreenPlayer) {
            playerSwipeDragRaw.floatValue = 0f
            if (playerSwipeOffset.value != 0f) {
                if (powerSaver.reduceAnimations) playerSwipeOffset.snapTo(0f)
                else launch { playerSwipeOffset.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
            }
            if (powerSaver.reduceAnimations) {
                expandProgress.snapTo(1f)
            } else {
                expandProgress.animateTo(
                    1f,
                    tween(durationMillis = 360, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
                )
            }
        } else {
            val totalCur = (playerSwipeOffset.value + playerSwipeDragRaw.floatValue).coerceAtLeast(0f)
            playerSwipeDragRaw.floatValue = 0f
            playerSwipeOffset.snapTo(totalCur)
            if (powerSaver.reduceAnimations) {
                expandProgress.snapTo(0f)
            } else {
                expandProgress.animateTo(
                    0f,
                    tween(durationMillis = 290, easing = FastOutSlowInEasing)
                )
            }
            playerSwipeOffset.snapTo(0f)
        }
    }

    val view = LocalView.current
    val playerTokens = remember(tokens) {
        tokens.forBackdrop(tokens.config.fsBgMode, tokens.config.fsBgSolidColor)
    }
    val playerVisualActive = isPlayerExpanded || expandProgress.value > 0.35f
    val lightStatusBars = if (playerVisualActive) playerTokens.isLightCanvas else tokens.isLightCanvas
    val lightNavBars = if (playerVisualActive) playerTokens.isLightCanvas else tokens.isLightPanel
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = lightStatusBars
                isAppearanceLightNavigationBars = lightNavBars
            }
        }
    }

    val screenBackdrop = rememberFlacBackdropState()
    val playerOverlayBackdrop = rememberFlacBackdropState()
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }

    val embeddedCovers by prefs.isEmbeddedCoversEnabled.collectAsState()
    val artGen by artworkGeneration.collectAsState()

    val frostedBmp by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = "${tokens.config.appBgMode}|${tokens.config.appBgCustomPath}|${tokens.config.appBgIsVideo}|$embeddedCovers|$artGen",
        key2 = currentTrack?.id,
        key3 = "${tokens.config.appBgBlurPx}|${tokens.config.panelBlurPx}|${tokens.config.appBgColorMode}"
    ) {
        value = if (tokens.config.panelBlurPx <= 0 || (!embeddedCovers && tokens.config.appBgMode == "cover")) {
            null
        } else {
            getPanelFrostedBg(
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
                currentTrack = currentTrack,
                isActive = expandProgress.value < 0.99f || (playerSwipeOffset.value + playerSwipeDragRaw.floatValue) > 0.5f,
                solidColor = tokens.config.appBgSolidColor
            )

            // Tab content recorded alone into screenBackdrop (keep modifier always attached when panelBlurPx > 0
            // so exiting fullscreen never drops or delays panel blur)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (tokens.config.panelBlurPx > 0) {
                            Modifier.flacBackdropSource(screenBackdrop)
                        } else Modifier
                    )
            ) {
                // Keep visited tabs alive in composition; unplaced tabs skip drawing & pointer routing
                // while retaining scroll positions and remembered lists for instant 1-frame switching.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    val showTabs = (detailRoute == null)
                    MainTab.values().forEach { tab ->
                        if (tab in visitedTabs) {
                            key(tab) {
                                val visibleTab = showTabs && activeTab == tab
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints)
                                            layout(placeable.width, placeable.height) {
                                                if (visibleTab) {
                                                    placeable.place(0, 0)
                                                }
                                            }
                                        }
                                ) {
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
                                            onOpenAlbum = { album, artist -> detailRoute = DetailRoute.Album(album, artist) },
                                            onOpenArtist = { artist -> detailRoute = DetailRoute.Artist(artist) },
                                            onOpenPlaylist = { pl -> detailRoute = DetailRoute.PlaylistDetail(pl.id) },
                                            onOpenSectionTracks = { title, list ->
                                                detailRoute = DetailRoute.SectionTracks(title, list.map { it.id })
                                            },
                                            onOpenTrackMenu = { menuTrack = it },
                                            onRescanLibrary = onRescanLibrary,
                                            bottomInsetPadding = navReservedHeight
                                        )
                                        MainTab.SEARCH -> SearchScreen(
                                            tracks = tracks,
                                            currentTrack = currentTrack,
                                            onPlayTrack = onTrackSelect,
                                            onOpenAlbum = { album, artist -> detailRoute = DetailRoute.Album(album, artist) },
                                            onOpenArtist = { artist -> detailRoute = DetailRoute.Artist(artist) },
                                            onOpenTrackMenu = { menuTrack = it },
                                            bottomInsetPadding = navReservedHeight
                                        )
                                        MainTab.SETTINGS -> SettingsScreen(
                                            eqController = eqController,
                                            dacInfo = dacInfo,
                                            hiddenCount = hiddenIds.size,
                                            duplicateEntries = duplicateEntries,
                                            onPickFolder = onPickFolder,
                                            onRescanLibrary = onRescanLibrary,
                                            onOpenColorPicker = { req -> colorPickerRequest = req },
                                            bottomInsetPadding = navReservedHeight
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = detailRoute,
                        transitionSpec = {
                            if (targetState != null && initialState == null) {
                                (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 5 } +
                                    fadeIn(tween(200))) togetherWith fadeOut(tween(120))
                            } else if (targetState == null && initialState != null) {
                                fadeIn(tween(120)) togetherWith
                                    (slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 5 } +
                                        fadeOut(tween(160)))
                            } else {
                                fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                            }
                        },
                        label = "detailRouteTransition"
                    ) { activeDetail ->
                        if (activeDetail != null) {
                            when (activeDetail) {
                                is DetailRoute.Album -> {
                                    val albumTracks = remember(tracks, activeDetail.albumName, activeDetail.artistName) {
                                        tracks.filter {
                                            it.album.ifBlank { "Без альбома" } == activeDetail.albumName &&
                                                it.artist.ifBlank { "Неизвестный" } == activeDetail.artistName
                                        }.sortedBy { it.trackNumber }
                                    }
                                    TrackCollectionDetailView(
                                        title = activeDetail.albumName,
                                        subtitle = activeDetail.artistName,
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
                        }
                    }
                }
            }

            // Bottom Dock (Mini-Player + Edge-to-Edge Compact Nav Bar) sampling screenBackdrop
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { dockHeightPx = it.height },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentTrack != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .onGloballyPositioned { coords ->
                                if (expandProgress.value < 0.02f) {
                                    val pos = coords.positionInRoot()
                                    val left = pos.x - rootOffset.x
                                    val top = pos.y - rootOffset.y
                                    val next = Rect(
                                        left = left,
                                        top = top,
                                        right = left + coords.size.width,
                                        bottom = top + coords.size.height
                                    )
                                    if (miniPlayerBounds != next) {
                                        miniPlayerBounds = next
                                    }
                                }
                            }
                            .graphicsLayer {
                                val p = expandProgress.value
                                alpha = (1f - p * 2.8f).coerceIn(0f, 1f)
                                val s = 1f + p * 0.04f
                                scaleX = s
                                scaleY = s
                                translationY = -p * 14.dp.toPx()
                            }
                    ) {
                        FloatingMiniPlayer(
                            track = currentTrack,
                            isPlaying = isPlaying,
                            onExpand = {
                                if (expandProgress.value < 0.4f) {
                                    isPlayerExpanded = true
                                }
                            },
                            onPlayPause = onPlayPauseToggle,
                            onNext = onNextTrack,
                            onPrevious = onPreviousTrack
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { navBarHeightPx = it.height }
                        .graphicsLayer {
                            val p = expandProgress.value
                            val h = rootSize.height.toFloat().coerceAtLeast(1f)
                            val dragFrac = if (isPlayerExpanded) {
                                ((playerSwipeOffset.value + playerSwipeDragRaw.floatValue).coerceAtLeast(0f) / (h * 0.45f)).coerceIn(0f, 1f)
                            } else 0f
                            val hideP = (p * (1f - dragFrac * 0.65f)).coerceIn(0f, 1f)
                            translationY = navBarHeightPx.toFloat() * hideP
                            alpha = (1f - hideP * 1.25f).coerceIn(0f, 1f)
                        }
                ) {
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
            }

        // Smooth backdrop dim + Container-transform Fullscreen Player rising out of Mini-Player
        if ((isPlayerExpanded || expandProgress.value > 0.001f) && currentTrack != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val h = size.height.coerceAtLeast(1f)
                        val dragFrac = if (isPlayerExpanded) {
                            ((playerSwipeOffset.value + playerSwipeDragRaw.floatValue).coerceAtLeast(0f) / (h * 0.45f)).coerceIn(0f, 1f)
                        } else 0f
                        alpha = (expandProgress.value * (1f - dragFrac * 0.5f) * 0.52f).coerceIn(0f, 0.52f)
                    }
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = isPlayerExpanded
                    ) {}
            )

            PlayerOverlay(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                isShuffleOn = isShuffleOn,
                repeatMode = repeatMode,
                isFavorite = favoriteIds.contains(currentTrack.id),
                isExpanded = isPlayerExpanded,
                expandProgress = expandProgress,
                swipeOffset = playerSwipeOffset,
                swipeDragRaw = playerSwipeDragRaw,
                miniPlayerBounds = miniPlayerBounds,
                backdropSourceState = playerOverlayBackdrop,
                recordBackdrop = hasAnyOverlay && tokens.config.panelBlurPx > 0,
                onCollapse = { isPlayerExpanded = false },
                onPlayPauseToggle = onPlayPauseToggle,
                onStopPlayback = onStopPlayback,
                onNextTrack = onNextTrack,
                onPreviousTrack = onPreviousTrack,
                onSeekTo = onSeekTo,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onToggleFavorite = {
                    pm.toggleFavorite(currentTrack.id)
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
        CompositionLocalProvider(
            LocalFlacTheme provides if (isPlayerExpanded) playerTokens else tokens,
            LocalScreenBackdrop provides if (isPlayerExpanded) playerOverlayBackdrop else screenBackdrop,
            LocalFrostedBg provides if (isPlayerExpanded) null else frostedInfo
        ) {
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
            onGoToAlbum = { album, artist ->
                isPlayerExpanded = false
                detailRoute = DetailRoute.Album(album, artist)
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

        ColorPickerBottomSheet(
            request = colorPickerRequest,
            onDismiss = { colorPickerRequest = null }
        )
        }
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
    val rootTokens = LocalFlacTheme.current
    val tokens = remember(rootTokens) { rootTokens.forPanel() }
    val shape = RoundedCornerShape(tokens.radiusMd)
    val backdrop = LocalScreenBackdrop.current
    val frostedBg = LocalFrostedBg.current
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    var rawDragX by remember { mutableFloatStateOf(0f) }
    var rawDragY by remember { mutableFloatStateOf(0f) }

    CompositionLocalProvider(LocalFlacTheme provides tokens) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val curX = rawDragX + dragX.value
                val curY = rawDragY + dragY.value
                translationX = curX
                translationY = curY * 0.45f
                alpha = 1f - (abs(curX) / 420f).coerceIn(0f, 0.35f)
            }
            .flacGlassBackdrop(
                source = backdrop,
                frostedBg = frostedBg,
                blurRadius = tokens.config.panelBlurPx.dp,
                tintColor = tokens.bg1,
                shape = shape
            )
            .border(1.dp, tokens.lineStrong, shape)
            .pointerInput(track.id) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lockedAxis = 0 // 0 = none, 1 = horizontal, 2 = vertical
                    var totalDx = 0f
                    var totalDy = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y

                        if (lockedAxis == 0) {
                            if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                                lockedAxis = if (abs(totalDx) >= abs(totalDy)) 1 else 2
                            }
                        }

                        if (lockedAxis == 1) {
                            change.consume()
                            rawDragX = (rawDragX + delta.x).coerceIn(-180f - dragX.value, 180f - dragX.value)
                        } else if (lockedAxis == 2) {
                            change.consume()
                            rawDragY = (rawDragY + delta.y).coerceIn(-120f - dragY.value, -dragY.value)
                        }
                    }

                    val finalX = (rawDragX + dragX.value).coerceIn(-180f, 180f)
                    val finalY = (rawDragY + dragY.value).coerceIn(-120f, 0f)
                    rawDragX = 0f
                    rawDragY = 0f

                    if (lockedAxis == 1) {
                        if (finalX < -70f) onNext()
                        else if (finalX > 70f) onPrevious()
                    } else if (lockedAxis == 2) {
                        if (finalY < -45f) onExpand()
                    }
                    scope.launch {
                        dragX.snapTo(finalX)
                        dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                    scope.launch {
                        dragY.snapTo(finalY)
                        dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                }
            }
            .clickable(onClick = onExpand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TrackArtwork(
                track = track,
                modifier = Modifier.size(38.dp),
                cornerRadius = tokens.radiusSm,
                iconSize = 17.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = tokens.fg0,
                    fontSize = 13.sp,
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
                    .size(32.dp)
                    .clip(RoundedCornerShape(tokens.radiusSm))
                    .background(tokens.accent)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = tokens.accentFg,
                    modifier = Modifier.size(18.dp)
                )
            }
            FlacIconButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = "Следующий",
                onClick = onNext,
                size = 32.dp,
                iconSize = 19.dp,
                tint = tokens.fg1
            )
        }
        MiniIsolatedProgressBar(track = track)
    }
    }
}

@Composable
private fun MiniIsolatedProgressBar(track: AudioFileMetadata) {
    val tokens = LocalFlacTheme.current
    val progressMs by AudioPlayerService.progressMs.collectAsState()
    val serviceDurationMs by AudioPlayerService.durationMs.collectAsState()
    val duration = (serviceDurationMs.takeIf { it > 0L } ?: track.durationMs).coerceAtLeast(1L)
    val fraction = (progressMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.5.dp)
    ) {
        drawRect(color = tokens.line)
        val fillW = size.width * fraction
        if (fillW > 0f) {
            drawRect(
                color = tokens.accent,
                size = Size(fillW, size.height)
            )
        }
    }
}

@Composable
private fun IconOnlyNavBar(
    activeTab: MainTab,
    onSelectTab: (MainTab) -> Unit
) {
    val rootTokens = LocalFlacTheme.current
    val tokens = remember(rootTokens) { rootTokens.forPanel() }
    val backdrop = LocalScreenBackdrop.current
    val frostedBg = LocalFrostedBg.current

    CompositionLocalProvider(LocalFlacTheme provides tokens) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .flacGlassBackdrop(
                source = backdrop,
                frostedBg = frostedBg,
                blurRadius = tokens.config.panelBlurPx.dp,
                tintColor = tokens.bg1,
                shape = RectangleShape
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.line)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(50.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.values().forEach { tab ->
                val selected = tab == activeTab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) tokens.accent else tokens.fg2,
                        modifier = Modifier.size(25.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (selected) tokens.accent else Color.Transparent)
                    )
                }
            }
        }
    }
    }
}
