package com.antigravity.audioplayer.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.antigravity.audioplayer.audio.DacDetector
import com.antigravity.audioplayer.audio.DacOutputInfo
import com.antigravity.audioplayer.audio.SleepTimerController
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.LyricsSource
import com.antigravity.audioplayer.data.parser.LyricsParser
import com.antigravity.audioplayer.data.repository.LyricsRepository
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.data.storage.PlaylistManager
import androidx.compose.ui.graphics.graphicsLayer
import com.antigravity.audioplayer.ui.components.AddToPlaylistDialog
import com.antigravity.audioplayer.ui.components.AlbumArtView
import com.antigravity.audioplayer.ui.components.AudioOutputBottomSheet
import com.antigravity.audioplayer.ui.components.LyricsView
import com.antigravity.audioplayer.ui.components.QualityBadge
import com.antigravity.audioplayer.ui.components.QueueModalSheet
import com.antigravity.audioplayer.ui.components.RepeatMode
import com.antigravity.audioplayer.ui.components.SleepTimerModalSheet
import com.antigravity.audioplayer.ui.components.artworkCache
import com.antigravity.audioplayer.ui.components.loadTrackArtwork

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return String.format("%d:%02d", s / 60, s % 60)
}

@Composable
fun PlayerScreen(
    currentTrack: AudioFileMetadata?,
    isPlaying: Boolean,
    dacInfo: DacOutputInfo,
    showVisualizer: Boolean,
    isShuffleOn: Boolean,
    repeatMode: RepeatMode,
    queueTracks: List<AudioFileMetadata> = emptyList(),
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onTrackSelect: (AudioFileMetadata) -> Unit = {},
    onOpenAudioChain: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit = {},
    onOpenArtist: (String) -> Unit,
    onCollapsePlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onCollapsePlayer)

    val context = LocalContext.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val isBlurBackground by prefs.isBlurBackground.collectAsState()
    val blurRadiusPercent by prefs.blurRadiusPercent.collectAsState()
    val blurRadiusDp = if (isBlurBackground) ((blurRadiusPercent / 100f) * 40f).dp else 0.dp

    var coverBitmap by remember(currentTrack?.id) { mutableStateOf(currentTrack?.id?.let { artworkCache.get(it) }) }

    LaunchedEffect(currentTrack?.id) {
        if (currentTrack != null && coverBitmap == null) {
            coverBitmap = loadTrackArtwork(context, currentTrack)
        }
    }

    val coverImageBitmap = remember(coverBitmap) { coverBitmap?.asImageBitmap() }

    val pm = remember { PlaylistManager.getInstance(context) }
    val favoriteIds by pm.favoriteIds.collectAsState()
    val isFavorite = currentTrack?.id?.let { favoriteIds.contains(it) } ?: false

    var showOutputBottomSheet by remember { mutableStateOf(false) }
    var showSleepTimerModal by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val isSleepTimerActive by SleepTimerController.isActive.collectAsState()
    val sleepTimerRemainingSec by SleepTimerController.remainingSeconds.collectAsState()

    val configuration = LocalConfiguration.current
    val isSmallScreen = configuration.screenHeightDp < 700 || configuration.screenWidthDp < 380
    var showOverflowMenu by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val dismissOffsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 130.dp.toPx() }

    val albumArtScaleState = animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "albumArtScale"
    )

    fun handleDragDismiss(curOffset: Float, velocityY: Float) {
        if (curOffset > dismissThresholdPx || velocityY > 1000f) {
            onCollapsePlayer()
        } else {
            coroutineScope.launch {
                dismissOffsetY.animateTo(
                    0f,
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }
    }

    val topBarDragModifier = Modifier.pointerInput(Unit) {
        val velocityTracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = { velocityTracker.resetTracking() },
            onVerticalDrag = { change, dragAmount ->
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                if (dragAmount > 0 || dismissOffsetY.value > 0f) {
                    coroutineScope.launch {
                        val newOffset = (dismissOffsetY.value + dragAmount).coerceAtLeast(0f)
                        dismissOffsetY.snapTo(newOffset)
                    }
                }
            },
            onDragEnd = {
                val velocityY = velocityTracker.calculateVelocity().y
                handleDragDismiss(dismissOffsetY.value, velocityY)
            },
            onDragCancel = {
                coroutineScope.launch { dismissOffsetY.animateTo(0f) }
            }
        )
    }

    val lyricsFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && currentTrack != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val text = LyricsRepository.readUriText(context, uri)
            if (!text.isNullOrBlank()) {
                val parsed = LyricsParser.parse(text, LyricsSource.MANUAL)
                LyricsRepository.setManualLyrics(currentTrack.id, parsed)
                showLyrics = true
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, dismissOffsetY.value.roundToInt()) }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(showLyrics) {
                if (!showLyrics) {
                    val velocityTracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { velocityTracker.resetTracking() },
                        onVerticalDrag = { change, dragAmount ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (dragAmount > 0 || dismissOffsetY.value > 0f) {
                                coroutineScope.launch {
                                    val newOffset = (dismissOffsetY.value + dragAmount).coerceAtLeast(0f)
                                    dismissOffsetY.snapTo(newOffset)
                                }
                            }
                        },
                        onDragEnd = {
                            val velocityY = velocityTracker.calculateVelocity().y
                            handleDragDismiss(dismissOffsetY.value, velocityY)
                        },
                        onDragCancel = {
                            coroutineScope.launch { dismissOffsetY.animateTo(0f) }
                        }
                    )
                }
            }
    ) {
        // Layer 1: Blurred Cover Artwork (configurable blur percentage)
        if (coverImageBitmap != null && isBlurBackground && blurRadiusDp > 0.dp) {
            val bgModifier = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                Modifier
                    .fillMaxSize()
                    .blur(blurRadiusDp)
                    .alpha(0.5f)
            } else {
                Modifier
                    .fillMaxSize()
                    .alpha(0.32f)
            }
            Image(
                bitmap = coverImageBitmap,
                contentDescription = null,
                modifier = bgModifier,
                contentScale = ContentScale.Crop
            )
        }

        // Layer 2: Darkening Overlay for maximum legibility and contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        )

        // Layer 3: Foreground Player UI
        if (currentTrack == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .then(topBarDragModifier)
                        .padding(top = 4.dp, bottom = 8.dp)
                        .size(width = 38.dp, height = 4.5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(Color.White.copy(alpha = 0.38f))
                        .clickable { onCollapsePlayer() }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(topBarDragModifier)
                ) {
                    IconButton(
                        onClick = onCollapsePlayer,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Свернуть", modifier = Modifier.size(34.dp))
                    }
                    Text(
                        text = "СЕЙЧАС ИГРАЕТ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(horizontal = 56.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(0.4f), modifier = Modifier.size(80.dp))
                    Text("Нет выбранного трека", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Button(onClick = onCollapsePlayer, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("К библиотеке")
                    }
                }
                Spacer(modifier = Modifier.height(48.dp))
            }
        } else {
            val totalDurationMs = currentTrack.durationMs.coerceAtLeast(1L)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(
                        start = if (isSmallScreen) 16.dp else 22.dp,
                        end = if (isSmallScreen) 16.dp else 22.dp,
                        top = if (isSmallScreen) 4.dp else 8.dp,
                        bottom = if (isSmallScreen) 12.dp else 16.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 1. TOP: Drag Pill + Strictly Centered Header Bar ──────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .then(topBarDragModifier)
                            .padding(top = if (isSmallScreen) 2.dp else 4.dp, bottom = if (isSmallScreen) 2.dp else 4.dp)
                            .size(width = 38.dp, height = 4.5.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(Color.White.copy(alpha = 0.38f))
                            .clickable { onCollapsePlayer() }
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(topBarDragModifier)
                    ) {
                        // Left: Collapse button
                        IconButton(
                            onClick = onCollapsePlayer,
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Свернуть",
                                modifier = Modifier.size(if (isSmallScreen) 26.dp else 30.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(0.8f)
                            )
                        }

                        // Strictly Centered Title / Sleep Timer Indicator
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center)
                                .padding(horizontal = 88.dp)
                        ) {
                            if (isSleepTimerActive) {
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { showSleepTimerModal = true },
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Bedtime,
                                            contentDescription = "Таймер сна",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "Zzz ${SleepTimerController.formatRemainingTime(sleepTimerRemainingSec)}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "СЕЙЧАС ИГРАЕТ",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.2.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Text(
                                text = currentTrack.album,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Right Actions: Queue + 3-dots BottomSheet Menu
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            IconButton(onClick = onOpenQueue) {
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "Очередь",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (isSmallScreen) 20.dp else 22.dp)
                                )
                            }

                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Меню",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    modifier = Modifier.size(if (isSmallScreen) 22.dp else 24.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // ── 2. ALBUM ART / LYRICS (Strictly 1:1 Square Container) ─────────
                Box(
                    modifier = Modifier
                        .weight(3.2f, fill = false)
                        .padding(
                            horizontal = if (isSmallScreen) 22.dp else 14.dp,
                            vertical = if (isSmallScreen) 2.dp else 6.dp
                        )
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (showLyrics) {
                        LyricsView(
                            track = currentTrack,
                            isPlaying = isPlaying,
                            onSeekTo = onSeekTo,
                            onPickLyricsFile = {
                                lyricsFilePicker.launch(arrayOf("*/*", "text/plain", "application/octet-stream"))
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val s = albumArtScaleState.value
                                    scaleX = s
                                    scaleY = s
                                }
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { showLyrics = true }
                        ) {
                            AlbumArtView(
                                track = currentTrack,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 22
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(0.6f))

                // ── 3. TRACK INFO + QUALITY & OUTPUT BADGE ────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(if (isSmallScreen) 6.dp else 10.dp)
                ) {
                    // Title row with thumbnail (in lyrics mode) and favorite heart
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (showLyrics) {
                            Surface(
                                modifier = Modifier
                                    .size(if (isSmallScreen) 40.dp else 46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showLyrics = false },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                AlbumArtView(
                                    track = currentTrack,
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 10
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                            Text(
                                text = currentTrack.title,
                                fontSize = if (isSmallScreen) 18.sp else 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentTrack.artist,
                                fontSize = if (isSmallScreen) 13.sp else 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenArtist(currentTrack.artist) }
                                    .padding(vertical = 1.dp, horizontal = 2.dp)
                            )
                        }
                        // Action buttons: Add to playlist (on large screens) + Favorite heart
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!isSmallScreen) {
                                IconButton(
                                    onClick = { showAddToPlaylist = true },
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = "Добавить в плейлист",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { pm.toggleFavorite(currentTrack.id) },
                                modifier = Modifier.size(if (isSmallScreen) 38.dp else 42.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Избранное",
                                    tint = if (isFavorite) Color(0xFFE44D7B) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.size(if (isSmallScreen) 23.dp else 25.dp)
                                )
                            }
                        }
                    }

                    // Quality badge & Clickable Audio Device Output Switcher Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        QualityBadge(metadata = currentTrack)

                        // Clickable Device Badge -> Opens custom AudioOutputBottomSheet
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showOutputBottomSheet = true },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        dacInfo.isExternalDac -> Icons.Default.Usb
                                        dacInfo.deviceType.contains("Bluetooth", ignoreCase = true) -> Icons.Default.Bluetooth
                                        dacInfo.deviceType.contains("Headphone", ignoreCase = true) || dacInfo.deviceType.contains("Analog", ignoreCase = true) -> Icons.Default.Headphones
                                        else -> Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = "Аудиовыход",
                                    tint = if (dacInfo.isHiResSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = dacInfo.deviceName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = "Сменить вывод",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (isSmallScreen) 6.dp else 10.dp))

                // ── 4. SEEK SLIDER (isolated progress recomposition) ──────────────
                PlayerSeekSection(
                    totalDurationMs = totalDurationMs,
                    onSeekTo = onSeekTo
                )

                Spacer(modifier = Modifier.height(if (isSmallScreen) 8.dp else 12.dp))

                // ── 5. MAIN PLAYBACK CONTROLS ─────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle, modifier = if (isSmallScreen) Modifier.size(38.dp) else Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffleOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(0.35f),
                            modifier = Modifier.size(if (isSmallScreen) 20.dp else 22.dp)
                        )
                    }

                    IconButton(onClick = onPreviousTrack, modifier = Modifier.size(if (isSmallScreen) 44.dp else 52.dp)) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Пред", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(if (isSmallScreen) 30.dp else 36.dp))
                    }

                    FloatingActionButton(
                        onClick = onPlayPauseToggle,
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(if (isSmallScreen) 58.dp else 68.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(if (isSmallScreen) 32.dp else 38.dp)
                        )
                    }

                    IconButton(onClick = onNextTrack, modifier = Modifier.size(if (isSmallScreen) 44.dp else 52.dp)) {
                        Icon(Icons.Default.SkipNext, contentDescription = "След", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(if (isSmallScreen) 30.dp else 36.dp))
                    }

                    IconButton(onClick = onToggleRepeat, modifier = if (isSmallScreen) Modifier.size(38.dp) else Modifier.size(44.dp)) {
                        Icon(
                            imageVector = when (repeatMode) {
                                RepeatMode.ONE -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            },
                            contentDescription = "Повтор",
                            tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(0.35f),
                            modifier = Modifier.size(if (isSmallScreen) 20.dp else 22.dp)
                        )
                    }
                }

                // ── 6. SECONDARY CONTROLS ROW (Only on larger screens) ────────────
                if (!isSmallScreen) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind 10s
                        IconButton(onClick = {
                            val cur = com.antigravity.audioplayer.service.AudioPlayerService.progressMs.value
                            onSeekTo((cur - 10000L).coerceAtLeast(0L))
                        }) {
                            Icon(Icons.Default.Replay10, contentDescription = "−10с", tint = MaterialTheme.colorScheme.onSurface.copy(0.6f), modifier = Modifier.size(24.dp))
                        }

                        // EQ shortcut button
                        IconButton(onClick = onOpenEqualizer) {
                            Icon(
                                Icons.Default.Equalizer,
                                contentDescription = "Эквалайзер",
                                tint = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Lyrics button
                        IconButton(onClick = { showLyrics = !showLyrics }) {
                            Icon(
                                Icons.Default.Lyrics,
                                contentDescription = "Текст песни",
                                tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(0.6f),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Audio Chain info — subtle, minimal
                        IconButton(onClick = onOpenAudioChain) {
                            Icon(Icons.Default.Info, contentDescription = "Аудио тракт", tint = MaterialTheme.colorScheme.onSurface.copy(0.28f), modifier = Modifier.size(18.dp))
                        }

                        // Forward 10s
                        IconButton(onClick = {
                            val cur = com.antigravity.audioplayer.service.AudioPlayerService.progressMs.value
                            onSeekTo((cur + 10000L).coerceAtMost(totalDurationMs))
                        }) {
                            Icon(Icons.Default.Forward10, contentDescription = "+10с", tint = MaterialTheme.colorScheme.onSurface.copy(0.6f), modifier = Modifier.size(24.dp))
                        }
                    }
                }

                // Lift the entire bottom controls block 2-3x higher from the bottom edge
                Spacer(modifier = Modifier.weight(1.2f))
                Spacer(modifier = Modifier.height(if (isSmallScreen) 48.dp else 56.dp))
            }
        }
    }

    if (showOverflowMenu && currentTrack != null) {
        PlayerOptionsBottomSheet(
            currentTrack = currentTrack,
            showLyrics = showLyrics,
            isSleepTimerActive = isSleepTimerActive,
            sleepTimerRemainingSec = sleepTimerRemainingSec,
            dacInfo = dacInfo,
            onToggleLyrics = {
                showLyrics = !showLyrics
                showOverflowMenu = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
            },
            onOpenEqualizer = {
                showOverflowMenu = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
                onOpenEqualizer()
            },
            onOpenAudioChain = {
                showOverflowMenu = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
                onOpenAudioChain()
            },
            onOpenSleepTimer = {
                showOverflowMenu = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
                showSleepTimerModal = true
            },
            onAddToPlaylist = {
                showOverflowMenu = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
                showAddToPlaylist = true
            },
            onOpenAudioOutput = {
                showOverflowMenu = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
                showOutputBottomSheet = true
            },
            onDismissRequest = {
                showOverflowMenu = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
            }
        )
    }

    if (showOutputBottomSheet) {
        AudioOutputBottomSheet(
            currentDacInfo = dacInfo,
            onDismissRequest = {
                showOutputBottomSheet = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
            }
        )
    }

    if (showSleepTimerModal) {
        SleepTimerModalSheet(
            onDismissRequest = {
                showSleepTimerModal = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
            },
            onPausePlayback = onPlayPauseToggle
        )
    }

    if (showAddToPlaylist && currentTrack != null) {
        AddToPlaylistDialog(
            track = currentTrack,
            onDismissRequest = {
                showAddToPlaylist = false
                (context as? com.antigravity.audioplayer.MainActivity)?.hideSystemNavigationBar()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOptionsBottomSheet(
    currentTrack: AudioFileMetadata,
    showLyrics: Boolean,
    isSleepTimerActive: Boolean,
    sleepTimerRemainingSec: Long,
    dacInfo: DacOutputInfo,
    onToggleLyrics: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenAudioChain: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAudioOutput: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Track summary header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AlbumArtView(
                    track = currentTrack,
                    modifier = Modifier.size(44.dp),
                    cornerRadius = 10
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentTrack.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${currentTrack.artist} • ${currentTrack.album}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            PlayerOptionSheetRow(
                icon = Icons.Default.Lyrics,
                title = if (showLyrics) "Показать обложку альбома" else "Текст песни (LRC)",
                subtitle = if (showLyrics) "Вернуться к обложке" else "Синхронизированный текст",
                isHighlighted = showLyrics,
                onClick = onToggleLyrics
            )

            PlayerOptionSheetRow(
                icon = Icons.Default.Equalizer,
                title = "Эквалайзер и эффекты",
                subtitle = "Настройка частот и усиления баса",
                onClick = onOpenEqualizer
            )

            PlayerOptionSheetRow(
                icon = Icons.Default.Info,
                title = "Аудио тракт и сигнал",
                subtitle = "${currentTrack.format.name} • ${currentTrack.sampleRateKHzFormatted} / ${currentTrack.bitDepth}-бит",
                onClick = onOpenAudioChain
            )

            PlayerOptionSheetRow(
                icon = Icons.Default.Bedtime,
                title = "Таймер сна",
                subtitle = if (isSleepTimerActive) "Активен: Zzz ${SleepTimerController.formatRemainingTime(sleepTimerRemainingSec)}" else "Автоматическая остановка музыки",
                isHighlighted = isSleepTimerActive,
                onClick = onOpenSleepTimer
            )

            PlayerOptionSheetRow(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "Добавить в плейлист",
                subtitle = "Сохранить трек в пользовательский список",
                onClick = onAddToPlaylist
            )

            PlayerOptionSheetRow(
                icon = Icons.Default.Headphones,
                title = "Аудиовыход / ЦАП",
                subtitle = dacInfo.deviceName,
                onClick = onOpenAudioOutput
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PlayerOptionSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PlayerSeekSection(
    totalDurationMs: Long,
    onSeekTo: (Long) -> Unit
) {
    val progressMs by com.antigravity.audioplayer.service.AudioPlayerService.progressMs.collectAsState()
    val sliderInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isSliderDragged by sliderInteractionSource.collectIsDraggedAsState()
    val isSliderPressed by sliderInteractionSource.collectIsPressedAsState()
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val isActivelyScrubbing = isDraggingSlider && (isSliderDragged || isSliderPressed)
    val progressFraction = (progressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    val activeSliderFraction = if (isActivelyScrubbing) dragFraction else progressFraction

    val displaySeconds by remember(totalDurationMs) {
        derivedStateOf {
            val ms = if (isActivelyScrubbing) (dragFraction * totalDurationMs).toLong() else progressMs
            ms / 1000L
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = activeSliderFraction.coerceIn(0f, 1f),
            onValueChange = { fraction ->
                isDraggingSlider = true
                dragFraction = fraction
            },
            onValueChangeFinished = {
                val targetMs = (dragFraction * totalDurationMs).toLong().coerceIn(0L, totalDurationMs)
                onSeekTo(targetMs)
                isDraggingSlider = false
            },
            interactionSource = sliderInteractionSource,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(displaySeconds * 1000L), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            Text(formatMs(totalDurationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
}



