package com.antigravity.audioplayer.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    progressMs: Long,
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

    val pm = remember { PlaylistManager.getInstance(context) }
    val favoriteIds by pm.favoriteIds.collectAsState()
    val isFavorite = currentTrack?.id?.let { favoriteIds.contains(it) } ?: false

    var showOutputBottomSheet by remember { mutableStateOf(false) }
    var showSleepTimerModal by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val isSleepTimerActive by SleepTimerController.isActive.collectAsState()
    val sleepTimerRemainingSec by SleepTimerController.remainingSeconds.collectAsState()

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

    // Swipe-to-dismiss gesture
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dragThreshold = 130f

    val alphaAnim by animateFloatAsState(
        targetValue = (1f - (dragOffsetY / 500f)).coerceIn(0.2f, 1f),
        animationSpec = spring(), label = "alpha"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = (1f - dragOffsetY / 3000f).coerceIn(0.92f, 1f),
        animationSpec = spring(), label = "scale"
    )

    val draggableState = rememberDraggableState { delta ->
        if (delta > 0) dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Layer 1: Blurred Cover Artwork (configurable blur percentage)
        if (coverBitmap != null && isBlurBackground && blurRadiusDp > 0.dp) {
            Image(
                bitmap = coverBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadiusDp)
                    .alpha(0.5f),
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
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCollapsePlayer) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Свернуть", modifier = Modifier.size(34.dp))
                    }
                    Text("СЕЙЧАС ИГРАЕТ", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.size(48.dp))
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
                Spacer(modifier = Modifier.height(20.dp))
            }
        } else {
            val totalDurationMs = currentTrack.durationMs.coerceAtLeast(1L)
            val progressFraction = (progressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scaleAnim)
                    .alpha(alphaAnim)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = draggableState,
                        onDragStopped = { velocity ->
                            if (dragOffsetY > dragThreshold || velocity > 900f) {
                                dragOffsetY = 0f
                                onCollapsePlayer()
                            } else {
                                dragOffsetY = 0f
                            }
                        }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // ── TOP: Drag pill + header ──────────────────────────────────────
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Drag pill
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Collapse button
                            IconButton(onClick = onCollapsePlayer) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Свернуть",
                                    modifier = Modifier.size(30.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(0.8f)
                                )
                            }

                            // Center info / Sleep Timer Active Indicator
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
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
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "СЕЙЧАС ИГРАЕТ",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        letterSpacing = 1.2.sp
                                    )
                                }
                                Text(
                                    currentTrack.album,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Actions: Sleep Timer + Queue
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showSleepTimerModal = true }) {
                                    Icon(
                                        Icons.Default.Bedtime,
                                        contentDescription = "Таймер сна",
                                        tint = if (isSleepTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(onClick = { showQueueSheet = true; onOpenQueue() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = "Очередь",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    // ── ALBUM ART / LYRICS TOGGLE ────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .clip(RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showLyrics) {
                            LyricsView(
                                track = currentTrack,
                                progressMs = progressMs,
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

                    // ── TRACK INFO + QUALITY ─────────────────────────────────────────
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Title row with favorite heart
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = currentTrack.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Start
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = currentTrack.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onOpenArtist(currentTrack.artist) }
                                        .padding(vertical = 2.dp, horizontal = 2.dp)
                                )
                            }
                            // Favorite heart button
                            IconButton(
                                onClick = { pm.toggleFavorite(currentTrack.id) },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Избранное",
                                    tint = if (isFavorite) Color(0xFFE44D7B) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.size(26.dp)
                                )
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

                        // ── SEEK SLIDER ─────────────────────────────────────────────
                        var isDraggingSlider by remember { mutableStateOf(false) }
                        var dragFraction by remember { mutableFloatStateOf(0f) }
                        val activeSliderFraction = if (isDraggingSlider) dragFraction else progressFraction
                        val activeDisplayProgressMs = if (isDraggingSlider) (dragFraction * totalDurationMs).toLong() else progressMs

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
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                )
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatMs(activeDisplayProgressMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                                Text(formatMs(totalDurationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                        }

                        // ── MAIN PLAYBACK CONTROLS ──────────────────────────────────
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onToggleShuffle) {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (isShuffleOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(0.35f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(onClick = onPreviousTrack, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Пред", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                            }

                            FloatingActionButton(
                                onClick = onPlayPauseToggle,
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(68.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(38.dp)
                                )
                            }

                            IconButton(onClick = onNextTrack, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Default.SkipNext, contentDescription = "След", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                            }

                            IconButton(onClick = onToggleRepeat) {
                                Icon(
                                    imageVector = when (repeatMode) {
                                        RepeatMode.ONE -> Icons.Default.RepeatOne
                                        else -> Icons.Default.Repeat
                                    },
                                    contentDescription = "Повтор",
                                    tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(0.35f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // ── SECONDARY CONTROLS ROW ──────────────────────────────────
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Rewind 10s
                            IconButton(onClick = { onSeekTo((progressMs - 10000L).coerceAtLeast(0L)) }) {
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
                            IconButton(onClick = { onSeekTo((progressMs + 10000L).coerceAtMost(totalDurationMs)) }) {
                                Icon(Icons.Default.Forward10, contentDescription = "+10с", tint = MaterialTheme.colorScheme.onSurface.copy(0.6f), modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOutputBottomSheet) {
        AudioOutputBottomSheet(
            currentDacInfo = dacInfo,
            onDismissRequest = { showOutputBottomSheet = false }
        )
    }

    if (showSleepTimerModal) {
        SleepTimerModalSheet(
            onDismissRequest = { showSleepTimerModal = false },
            onPausePlayback = onPlayPauseToggle
        )
    }

    if (showQueueSheet) {
        val currentIndex = queueTracks.indexOfFirst { it.id == currentTrack?.id }.coerceAtLeast(0)
        QueueModalSheet(
            queueTracks = queueTracks,
            currentTrackIndex = currentIndex,
            isShuffleOn = isShuffleOn,
            repeatMode = repeatMode,
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat,
            onTrackSelect = { index ->
                queueTracks.getOrNull(index)?.let { onTrackSelect(it) }
                showQueueSheet = false
            },
            onDismiss = { showQueueSheet = false }
        )
    }
}



