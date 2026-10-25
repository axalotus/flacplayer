package com.antigravity.audioplayer.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.LyricsData
import com.antigravity.audioplayer.data.model.LyricsSource
import com.antigravity.audioplayer.data.repository.LyricsLoadState
import com.antigravity.audioplayer.data.repository.LyricsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun LyricsView(
    track: AudioFileMetadata?,
    isPlaying: Boolean,
    onSeekTo: (Long) -> Unit,
    onPickLyricsFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lyricsData by LyricsRepository.currentLyrics.collectAsState()
    val loadState by LyricsRepository.loadState.collectAsState()
    val isLoading by LyricsRepository.isLoading.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var isOnlineSearching by remember { mutableStateOf(false) }
    val prefs = remember { com.antigravity.audioplayer.data.storage.LibraryPreferences.getInstance(context) }
    val verticalOffsetDp by prefs.lyricsVerticalOffsetDp.collectAsState()
    var showOffsetAdjustDialog by remember { mutableStateOf(false) }

    LaunchedEffect(track?.id) {
        LyricsRepository.loadLyricsForTrack(context, track)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading || loadState is LyricsLoadState.Loading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = "Поиск текста...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            lyricsData?.isInstrumental == true -> {
                // Instrumental state
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Text(
                        text = "Инструментальный трек",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "В этой композиции отсутствует вокальный текст (информация LRCLIB).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = onPickLyricsFile,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать файл вручную", fontSize = 12.sp)
                    }
                }
            }

            loadState is LyricsLoadState.NetworkError && lyricsData == null -> {
                // Network error state
                val errorMsg = (loadState as LyricsLoadState.NetworkError).message
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Text(
                        text = "Ошибка связи с сервисом",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onPickLyricsFile,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Файл", fontSize = 12.sp)
                        }

                        if (track != null) {
                            Button(
                                onClick = {
                                    if (!isOnlineSearching) {
                                        coroutineScope.launch {
                                            isOnlineSearching = true
                                            try {
                                                LyricsRepository.fetchOnlineLyricsForced(context, track)
                                            } finally {
                                                isOnlineSearching = false
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isOnlineSearching) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Повторить", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            lyricsData == null || (lyricsData?.lines?.isEmpty() == true && lyricsData?.plainText.isNullOrBlank()) -> {
                // Not found state
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Lyrics,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Text(
                        text = "Текст песни не найден",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "LRCLIB не содержит подходящего текста для этой композиции. Поместите .lrc файл рядом с аудио или выберите вручную.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onPickLyricsFile,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Файл", fontSize = 12.sp)
                        }

                        if (track != null) {
                            Button(
                                onClick = {
                                    if (!isOnlineSearching) {
                                        coroutineScope.launch {
                                            isOnlineSearching = true
                                            try {
                                                LyricsRepository.fetchOnlineLyricsForced(context, track)
                                            } finally {
                                                isOnlineSearching = false
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                if (isOnlineSearching) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isOnlineSearching) "Поиск..." else "Найти в LRCLIB", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            lyricsData!!.isSynced -> {
                val lines = lyricsData!!.lines
                val listState = rememberLazyListState()
                val isDragged by listState.interactionSource.collectIsDraggedAsState()

                // Track manual user interaction strictly via DragInteraction (not programmatic isScrollInProgress)
                var userInteracting by remember { mutableStateOf(false) }
                var lastInteractionTime by remember { mutableLongStateOf(0L) }

                LaunchedEffect(listState.interactionSource) {
                    listState.interactionSource.interactions.collect { interaction ->
                        when (interaction) {
                            is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                                userInteracting = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                            is androidx.compose.foundation.interaction.DragInteraction.Stop,
                            is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        }
                    }
                }

                // Idle timer: resume auto-scroll 3.5s after user stops dragging and fling completes
                LaunchedEffect(userInteracting, lastInteractionTime) {
                    if (userInteracting) {
                        while (true) {
                            delay(350)
                            val now = System.currentTimeMillis()
                            // Resume when user is not dragging, scroll (including fling) stopped, and 3.5s elapsed
                            if (!isDragged && !listState.isScrollInProgress && (now - lastInteractionTime >= 3500)) {
                                userInteracting = false
                                break
                            }
                        }
                    }
                }

                val isAutoScrollActive = !userInteracting
                val progressState = com.antigravity.audioplayer.service.AudioPlayerService.progressMs.collectAsState()

                // Compute active index strictly from current playback position using derivedStateOf
                val activeIndex by remember(lines) {
                    derivedStateOf {
                        val pos = progressState.value
                        var found = -1
                        for (i in lines.indices) {
                            if (pos >= lines[i].timestampMs) {
                                found = i
                            } else {
                                break
                            }
                        }
                        found
                    }
                }

                val density = LocalDensity.current
                val verticalOffsetPx = with(density) { verticalOffsetDp.dp.roundToPx() }

                // Centering function to keep active lyric dead-center in the window (accounting for user-configured offset)
                suspend fun centerActiveItem(smooth: Boolean = true) {
                    if (activeIndex !in lines.indices) return
                    var visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
                    if (visibleItem == null) {
                        listState.scrollToItem(activeIndex)
                        delay(20)
                        visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
                    }
                    val viewportHeight = listState.layoutInfo.viewportSize.height
                    if (visibleItem != null && viewportHeight > 0) {
                        val itemCenter = visibleItem.offset + visibleItem.size / 2
                        val targetCenter = (viewportHeight / 2) + verticalOffsetPx
                        val delta = itemCenter - targetCenter
                        if (abs(delta) > 2) {
                            if (smooth) {
                                listState.animateScrollBy(
                                    delta.toFloat(),
                                    tween<Float>(durationMillis = 320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                )
                            } else {
                                listState.scrollBy(delta.toFloat())
                            }
                        }
                    }
                }

                LaunchedEffect(activeIndex, isAutoScrollActive, verticalOffsetDp, track?.id) {
                    if (isAutoScrollActive && activeIndex >= 0 && activeIndex < lines.size) {
                        centerActiveItem(smooth = true)
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val halfHeightDp = maxHeight / 2
                    val topPadding = (halfHeightDp - 22.dp + verticalOffsetDp.dp).coerceAtLeast(30.dp)
                    val bottomPadding = (halfHeightDp - 22.dp - verticalOffsetDp.dp).coerceAtLeast(30.dp)

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header with format badge and tuning button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "СИНХРОНИЗИРОВАНО (LRC)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    lyricsData!!.source.label,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Surface(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { showOffsetAdjustDialog = true },
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = CircleShape
                                ) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = "Настройка высоты строки",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(4.dp).size(13.dp)
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(lines, key = { idx, line -> "line_${idx}_${line.timestampMs}" }) { index, line ->
                                val isActive = index == activeIndex

                                // Distance to active line (used for progressive blur & opacity)
                                val distance = if (isAutoScrollActive && activeIndex >= 0) {
                                    abs(index - activeIndex)
                                } else {
                                    0 // Blur disabled in manual scrolling mode!
                                }

                                val targetAlpha = when {
                                    !isAutoScrollActive -> if (isActive) 1.0f else 0.72f
                                    distance == 0 -> 1.0f
                                    distance == 1 -> 0.42f
                                    distance == 2 -> 0.26f
                                    else -> 0.14f
                                }

                                val targetScale = when {
                                    !isAutoScrollActive -> 1.0f
                                    distance == 0 -> 1.04f
                                    else -> 1.0f
                                }

                                val colorAnim by animateColorAsState(
                                    targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.75f),
                                    animationSpec = tween(durationMillis = 240),
                                    label = "lyricColor"
                                )
                                val scaleAnimState = animateFloatAsState(
                                    targetValue = targetScale,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    label = "lyricScale"
                                )
                                val alphaAnimState = animateFloatAsState(
                                    targetValue = targetAlpha,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    label = "lyricAlpha"
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            val s = scaleAnimState.value
                                            scaleX = s
                                            scaleY = s
                                            alpha = alphaAnimState.value
                                            transformOrigin = TransformOrigin(0f, 0.5f)
                                        }
                                        .clickable {
                                            userInteracting = false
                                            onSeekTo(line.timestampMs)
                                            coroutineScope.launch {
                                                centerActiveItem(smooth = true)
                                            }
                                        }
                                        .padding(vertical = 4.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = line.text,
                                        fontSize = if (isActive) 20.sp else 19.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = colorAnim,
                                        textAlign = TextAlign.Start,
                                        lineHeight = 28.sp
                                    )
                                }
                            }
                        }
                    }

                    // Floating re-sync button when in manual mode
                    AnimatedVisibility(
                        visible = !isAutoScrollActive && activeIndex >= 0,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        Surface(
                            onClick = {
                                userInteracting = false
                                lastInteractionTime = 0L
                                coroutineScope.launch {
                                    centerActiveItem(smooth = true)
                                }
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "К текущей строке",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                // Plain Text Mode
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ТЕКСТ ПЕСНИ (TXT)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            lyricsData!!.source.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    val plainLines = lyricsData!!.lines.ifEmpty {
                        lyricsData!!.plainText.lines().map { com.antigravity.audioplayer.data.model.LyricLine(0L, it) }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(plainLines) { _, line ->
                            Text(
                                text = line.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.White.copy(alpha = 0.85f),
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showOffsetAdjustDialog) {
        var tempOffset by remember(verticalOffsetDp) { mutableFloatStateOf(verticalOffsetDp.toFloat()) }
        AlertDialog(
            onDismissRequest = { showOffsetAdjustDialog = false },
            title = {
                Text("Высота строки текста", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when {
                            tempOffset.roundToInt() == 0 -> "Ровно по центру (0 dp)"
                            tempOffset.roundToInt() < 0 -> "Смещение вверх: ${-tempOffset.roundToInt()} dp"
                            else -> "Смещение вниз: +${tempOffset.roundToInt()} dp"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = tempOffset,
                        onValueChange = {
                            tempOffset = it
                            prefs.setLyricsVerticalOffsetDp(it.roundToInt())
                        },
                        valueRange = -120f..120f,
                        steps = 47
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Выше (-120)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        TextButton(
                            onClick = {
                                tempOffset = 0f
                                prefs.setLyricsVerticalOffsetDp(0)
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("В центр (0)", fontSize = 11.sp)
                        }
                        Text("Ниже (+120)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showOffsetAdjustDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Готово")
                }
            }
        )
    }
}
