package com.zuna.player.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zuna.player.audio.SleepTimerController
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.PlaylistManager
import com.zuna.player.ui.theme.LocalFlacTheme
import com.zuna.player.ui.theme.contrastForeground
import com.zuna.player.ui.theme.parseHexColor
import kotlin.math.roundToInt

@Composable
fun FlacOverlayFrame(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalFlacTheme.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(140))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            val shape = RoundedCornerShape(tokens.radiusLg)
            val panelTokens = remember(tokens) { tokens.forPanel() }
            val screenBackdrop = LocalScreenBackdrop.current
            val frostedBg = LocalFrostedBg.current
            CompositionLocalProvider(LocalFlacTheme provides panelTokens) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateEnterExit(
                            enter = slideInVertically(tween(180)) { it / 2 },
                            exit = slideOutVertically(tween(140)) { it / 2 }
                        )
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .flacGlassBackdrop(
                            source = screenBackdrop,
                            frostedBg = frostedBg,
                            blurRadius = tokens.config.panelBlurPx.dp,
                            tintColor = tokens.bg1,
                            shape = shape
                        )
                        .border(1.dp, panelTokens.lineStrong, shape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun TrackOptionsOverlay(
    track: AudioFileMetadata?,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: (AudioFileMetadata) -> Unit,
    onAddToQueue: (AudioFileMetadata) -> Unit,
    onToggleFavorite: (AudioFileMetadata) -> Unit,
    onAddToPlaylist: (AudioFileMetadata) -> Unit,
    onGoToAlbum: (String, String) -> Unit,
    onGoToArtist: (String) -> Unit,
    onShowInfo: (AudioFileMetadata) -> Unit,
    onHideTrack: (AudioFileMetadata) -> Unit
) {
    val tokens = LocalFlacTheme.current
    FlacOverlayFrame(visible = track != null, onDismiss = onDismiss) {
        if (track == null) return@FlacOverlayFrame
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TrackArtwork(
                track = track,
                modifier = Modifier.size(48.dp),
                cornerRadius = tokens.radiusSm
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = tokens.fg0,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = if (track.album.isNotBlank()) "${track.artist} • ${track.album}" else track.artist
                Text(
                    text = sub,
                    color = tokens.fg2,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FlacIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Закрыть",
                onClick = onDismiss,
                size = 32.dp,
                tint = tokens.fg2
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(tokens.line))
        Spacer(modifier = Modifier.height(8.dp))

        OverlayActionRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Играть следующим") {
            onPlayNext(track); onDismiss()
        }
        OverlayActionRow(Icons.AutoMirrored.Rounded.QueueMusic, "Добавить в очередь") {
            onAddToQueue(track); onDismiss()
        }
        OverlayActionRow(
            icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            label = if (isFavorite) "Убрать из избранного" else "В избранное",
            accent = isFavorite
        ) {
            onToggleFavorite(track); onDismiss()
        }
        OverlayActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Добавить в плейлист") {
            onAddToPlaylist(track)
        }
        if (track.album.isNotBlank()) {
            OverlayActionRow(Icons.Rounded.Album, "Перейти к альбому") {
                onGoToAlbum(track.album, track.artist.ifBlank { "Неизвестный" }); onDismiss()
            }
        }
        if (track.artist.isNotBlank()) {
            OverlayActionRow(Icons.Rounded.Person, "Перейти к исполнителю") {
                onGoToArtist(track.artist); onDismiss()
            }
        }
        OverlayActionRow(Icons.Rounded.Info, "Информация о файле") {
            onShowInfo(track)
        }
        OverlayActionRow(Icons.Rounded.VisibilityOff, "Скрыть трек из библиотеки") {
            onHideTrack(track); onDismiss()
        }
    }
}

@Composable
private fun OverlayActionRow(
    icon: ImageVector,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    val fg = if (accent) tokens.accent else tokens.fg0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (accent) tokens.accent else tokens.fg1,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun PlaylistPickerOverlay(
    track: AudioFileMetadata?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val pm = remember { PlaylistManager.getInstance(context) }
    val playlists by pm.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(track?.id) {
        newName = ""
    }

    FlacOverlayFrame(visible = track != null, onDismiss = onDismiss) {
        if (track == null) return@FlacOverlayFrame
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Добавить в плейлист",
                color = tokens.fg0,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            FlacIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Закрыть",
                onClick = onDismiss,
                size = 32.dp,
                tint = tokens.fg2
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

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
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (newName.isEmpty()) {
                    Text("Новый плейлист...", color = tokens.fg2, fontSize = 13.5.sp)
                }
                BasicTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    textStyle = TextStyle(color = tokens.fg0, fontSize = 13.5.sp),
                    cursorBrush = SolidColor(tokens.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            FlacPillButton(
                text = "Создать",
                accentFill = newName.isNotBlank(),
                onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty()) {
                        val created = pm.createPlaylist(trimmed)
                        pm.addTrackToPlaylist(created.id, track.id)
                        newName = ""
                        onDismiss()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        if (playlists.isEmpty()) {
            Text(
                text = "Пока нет пользовательских плейлистов",
                color = tokens.fg2,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(playlists, key = { _, p -> p.id }) { _, pl ->
                    val already = pl.trackIds.contains(track.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(tokens.radiusSm))
                            .background(if (already) tokens.accent.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable {
                                if (!already) pm.addTrackToPlaylist(pl.id, track.id)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, color = tokens.fg0, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${pl.trackIds.size} треков", color = tokens.fg2, fontSize = 11.5.sp)
                        }
                        if (already) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QueueOverlay(
    visible: Boolean,
    queueTracks: List<AudioFileMetadata>,
    currentIndex: Int,
    repeatMode: RepeatMode,
    isShuffleOn: Boolean,
    onDismiss: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onClearUpcoming: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    FlacOverlayFrame(visible = visible, onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Очередь (${queueTracks.size})",
                color = tokens.fg0,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FlacIconButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "Перемешать",
                    onClick = onToggleShuffle,
                    size = 32.dp,
                    tint = if (isShuffleOn) tokens.accent else tokens.fg2
                )
                FlacIconButton(
                    icon = if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "Повтор",
                    onClick = onToggleRepeat,
                    size = 32.dp,
                    tint = if (repeatMode != RepeatMode.OFF) tokens.accent else tokens.fg2
                )
                if (queueTracks.size > 1) {
                    FlacIconButton(
                        icon = Icons.Rounded.ClearAll,
                        contentDescription = "Очистить далее",
                        onClick = onClearUpcoming,
                        size = 32.dp,
                        tint = tokens.fg2
                    )
                }
                FlacIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Закрыть",
                    onClick = onDismiss,
                    size = 32.dp,
                    tint = tokens.fg2
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        if (queueTracks.isEmpty()) {
            Text("Очередь пуста", color = tokens.fg2, fontSize = 13.5.sp, modifier = Modifier.padding(vertical = 18.dp))
        } else {
            val initialIdx = if (currentIndex in queueTracks.indices) (currentIndex - 1).coerceAtLeast(0) else 0
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIdx)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(queueTracks, key = { idx, t -> "${idx}_${t.id}" }) { idx, track ->
                    val isCurrent = idx == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(tokens.radiusSm))
                            .background(if (isCurrent) tokens.accent.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { onPlayIndex(idx) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TrackArtwork(track = track, modifier = Modifier.size(38.dp), cornerRadius = tokens.radiusSm)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = if (isCurrent) tokens.accent else tokens.fg0,
                                fontSize = 13.5.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
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
                        FlacIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "Убрать",
                            onClick = { onRemoveIndex(idx) },
                            size = 28.dp,
                            iconSize = 16.dp,
                            tint = tokens.fg2
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SleepTimerOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onTimerExpired: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    val isActive by SleepTimerController.isActive.collectAsState()
    val remaining by SleepTimerController.remainingSeconds.collectAsState()

    FlacOverlayFrame(visible = visible, onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Таймер сна",
                color = tokens.fg0,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            FlacIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Закрыть",
                onClick = onDismiss,
                size = 32.dp,
                tint = tokens.fg2
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (isActive) {
            Text(
                text = "Остановится через ${SleepTimerController.formatRemainingTime(remaining)}",
                color = tokens.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(15L, 30L, 45L, 60L).forEach { mins ->
                FlacPillButton(
                    text = "$mins м",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        SleepTimerController.startSleepTimer(mins, onTimerExpired)
                        onDismiss()
                    }
                )
            }
        }
        if (isActive) {
            Spacer(modifier = Modifier.height(10.dp))
            FlacPillButton(
                text = "Отключить таймер",
                accentFill = true,
                onClick = {
                    SleepTimerController.cancelSleepTimer()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
fun TrackInfoOverlay(
    track: AudioFileMetadata?,
    onDismiss: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    FlacOverlayFrame(visible = track != null, onDismiss = onDismiss) {
        if (track == null) return@FlacOverlayFrame
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("О файле", color = tokens.fg0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            FlacIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Закрыть",
                onClick = onDismiss,
                size = 32.dp,
                tint = tokens.fg2
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        InfoRow("Название", track.title)
        InfoRow("Исполнитель", track.artist)
        InfoRow("Альбом", track.album.ifBlank { "—" })
        InfoRow("Формат", track.format.name)
        InfoRow("Частота / Битность", "${track.sampleRateHz} Hz • ${track.bitDepth}-bit • ${track.bitrateKbps} kbps")
        InfoRow("Длительность", formatDurationMs(track.durationMs))
        InfoRow("Источник", track.uri.toString())
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val tokens = LocalFlacTheme.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = tokens.fg2, fontSize = 11.sp)
        Text(value, color = tokens.fg0, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

data class ColorPickerRequest(
    val title: String,
    val initialHex: String,
    val swatches: List<String>,
    val onSelectHex: (String) -> Unit
)

@Composable
fun ColorPickerBottomSheet(
    request: ColorPickerRequest?,
    onDismiss: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var bri by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(request?.title, request?.initialHex) {
        if (request != null) {
            val c = parseHexColor(request.initialHex, Color.White)
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(c.toArgb(), hsv)
            hue = hsv[0]
            sat = hsv[1]
            bri = hsv[2]
        }
    }

    fun emitHsv(h: Float, s: Float, b: Float) {
        val argb = AndroidColor.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), b.coerceIn(0f, 1f)))
        request?.onSelectHex("#%06x".format(argb and 0xFFFFFF))
    }

    val previewColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, bri)))
    val previewHex = "#%06x".format(previewColor.toArgb() and 0xFFFFFF)

    FlacOverlayFrame(visible = request != null, onDismiss = onDismiss) {
        if (request == null) return@FlacOverlayFrame
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(previewColor)
                        .border(1.dp, tokens.lineStrong, CircleShape)
                )
                Column {
                    Text(request.title, color = tokens.fg0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(previewHex.uppercase(), color = tokens.fg2, fontSize = 11.5.sp)
                }
            }
            FlacIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Закрыть",
                onClick = onDismiss,
                size = 32.dp,
                tint = tokens.fg2
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            request.swatches.forEach { sw ->
                val color = parseHexColor(sw, Color.White)
                val selected = previewHex.equals(sw, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selected) 2.5.dp else 1.dp,
                            color = if (selected) tokens.fg0 else tokens.lineStrong,
                            shape = CircleShape
                        )
                        .clickable {
                            val hsv = FloatArray(3)
                            AndroidColor.colorToHSV(color.toArgb(), hsv)
                            hue = hsv[0]
                            sat = hsv[1]
                            bri = hsv[2]
                            request.onSelectHex(sw.lowercase())
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = contrastForeground(color),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val hueColors = remember {
            listOf(
                Color(0xFFFF0000),
                Color(0xFFFFFF00),
                Color(0xFF00FF00),
                Color(0xFF00FFFF),
                Color(0xFF0000FF),
                Color(0xFFFF00FF),
                Color(0xFFFF0000)
            )
        }
        val pureHueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        val grayAtBri = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 0f, bri)))
        val fullAtBri = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, bri)))

        HsvGradientSlider(
            label = "Оттенок",
            valueText = "${hue.roundToInt()}°",
            fraction = (hue / 360f).coerceIn(0f, 1f),
            colors = hueColors,
            thumbColor = pureHueColor,
            onFractionChange = { f ->
                hue = (f * 360f).coerceIn(0f, 360f)
                emitHsv(hue, sat, bri)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        HsvGradientSlider(
            label = "Насыщенность",
            valueText = "${(sat * 100f).roundToInt()}%",
            fraction = sat.coerceIn(0f, 1f),
            colors = listOf(grayAtBri, fullAtBri),
            thumbColor = previewColor,
            onFractionChange = { f ->
                sat = f.coerceIn(0f, 1f)
                emitHsv(hue, sat, bri)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        HsvGradientSlider(
            label = "Яркость",
            valueText = "${(bri * 100f).roundToInt()}%",
            fraction = bri.coerceIn(0f, 1f),
            colors = listOf(Color.Black, Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, 1f)))),
            thumbColor = previewColor,
            onFractionChange = { f ->
                bri = f.coerceIn(0f, 1f)
                emitHsv(hue, sat, bri)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        FlacPillButton(
            text = "Готово",
            accentFill = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = onDismiss
        )
    }
}

@Composable
private fun HsvGradientSlider(
    label: String,
    valueText: String,
    fraction: Float,
    colors: List<Color>,
    thumbColor: Color,
    onFractionChange: (Float) -> Unit
) {
    val tokens = LocalFlacTheme.current
    var widthPx by remember { mutableIntStateOf(1) }
    val latestOnChange by rememberUpdatedState(onFractionChange)

    fun pxToFrac(x: Float): Float = (x / widthPx.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = tokens.fg1, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            Text(valueText, color = tokens.fg0, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> latestOnChange(pxToFrac(offset.x)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> latestOnChange(pxToFrac(offset.x)) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            latestOnChange(pxToFrac(change.position.x))
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
                val trackH = 10.dp.toPx()
                val topY = (size.height - trackH) / 2f
                val corner = trackH / 2f
                drawRoundRect(
                    brush = Brush.horizontalGradient(colors),
                    topLeft = Offset(0f, topY),
                    size = Size(size.width, trackH),
                    cornerRadius = CornerRadius(corner, corner)
                )
                val tRad = 8.dp.toPx()
                val cx = (size.width * fraction).coerceIn(tRad, (size.width - tRad).coerceAtLeast(tRad))
                val cy = size.height / 2f
                drawCircle(color = Color.White, radius = tRad + 1.5.dp.toPx(), center = Offset(cx, cy))
                drawCircle(color = thumbColor, radius = tRad, center = Offset(cx, cy))
            }
        }
    }
}
