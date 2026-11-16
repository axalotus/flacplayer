package com.antigravity.audioplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.audio.SleepTimerController
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.PlaylistManager
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme

@Composable
fun FlacOverlayFrame(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalFlacTheme.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
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
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut()
            ) {
                val shape = RoundedCornerShape(tokens.radiusLg)
                val screenBackdrop = LocalScreenBackdrop.current
                val frostedBg = LocalFrostedBg.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .flacGlassBackdrop(
                            source = screenBackdrop,
                            frostedBg = frostedBg,
                            blurRadius = tokens.config.panelBlurPx.dp,
                            tintColor = tokens.bg1,
                            shape = shape
                        )
                        .border(1.dp, tokens.lineStrong, shape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
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
    onGoToAlbum: (String) -> Unit,
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
                Text(
                    text = "${track.artist} • ${track.album}",
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
                onGoToAlbum(track.album); onDismiss()
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
            LazyColumn(
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
