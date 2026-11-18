package com.zuna.player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.data.storage.PlaylistManager
import com.zuna.player.ui.components.*
import com.zuna.player.ui.theme.LocalFlacTheme

@Composable
fun HomeScreen(
    tracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    isLoading: Boolean,
    onPlayTrack: (AudioFileMetadata, List<AudioFileMetadata>) -> Unit,
    onOpenSectionTracks: (String, List<AudioFileMetadata>) -> Unit,
    onOpenTrackMenu: (AudioFileMetadata) -> Unit,
    bottomInsetPadding: androidx.compose.ui.unit.Dp
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val pm = remember { PlaylistManager.getInstance(context) }

    val recentlyPlayedIds by prefs.recentlyPlayedIds.collectAsState()
    val favoriteIds by pm.favoriteIds.collectAsState()
    val showQualityBadges by prefs.showQualityBadges.collectAsState()
    val compactMode by prefs.isCompactMode.collectAsState()

    val tracksById = remember(tracks) { tracks.associateBy { it.id } }

    val historyTracks = remember(recentlyPlayedIds, tracksById) {
        recentlyPlayedIds.mapNotNull { tracksById[it] }
    }
    val sortedByAddedTracks = remember(tracks) {
        tracks.sortedByDescending { it.dateAdded }
    }
    val recentlyAddedTracks = remember(sortedByAddedTracks) {
        sortedByAddedTracks.take(40)
    }
    val favoriteTracks = remember(tracks, favoriteIds) {
        tracks.filter { favoriteIds.contains(it.id) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 12.dp,
            bottom = bottomInsetPadding + 20.dp
        ),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item(key = "header") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Обзор",
                        color = tokens.fg0,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.6).sp
                    )
                    if (tracks.isNotEmpty()) {
                        Text(
                            text = "${tracks.size} треков",
                            color = tokens.fg2,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FlacPillButton(
                            text = "Микс треков",
                            icon = Icons.Rounded.Shuffle,
                            accentFill = true,
                            onClick = {
                                if (tracks.isNotEmpty()) {
                                    val shuffled = tracks.shuffled()
                                    onPlayTrack(shuffled.first(), shuffled)
                                }
                            }
                        )
                    }
                    item {
                        FlacPillButton(
                            text = "Случайный альбом",
                            icon = Icons.Rounded.Album,
                            onClick = {
                                val albums = tracks.groupBy {
                                    it.album.ifBlank { "Без альбома" } to it.artist.ifBlank { "Неизвестный" }
                                }.values.toList()
                                val picked = albums.randomOrNull()?.sortedBy { it.trackNumber }
                                if (!picked.isNullOrEmpty()) {
                                    onPlayTrack(picked.first(), picked)
                                }
                            }
                        )
                    }
                    item {
                        FlacPillButton(
                            text = "Случайный артист",
                            icon = Icons.Rounded.Person,
                            onClick = {
                                val artists = tracks.groupBy { it.artist.ifBlank { "Неизвестный" } }.values.toList()
                                val picked = artists.randomOrNull()
                                if (!picked.isNullOrEmpty()) {
                                    val queue = picked.shuffled()
                                    onPlayTrack(queue.first(), queue)
                                }
                            }
                        )
                    }
                }
            }
        }

        if (isLoading && tracks.isEmpty()) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Сканирование медиатеки...", color = tokens.fg2, fontSize = 14.sp)
                }
            }
        } else if (tracks.isEmpty()) {
            item(key = "empty") {
                FlacGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Медиатека пуста",
                            color = tokens.fg0,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Добавь папки с музыкой или просканируй устройство в Настройках → Система.",
                            color = tokens.fg2,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            if (historyTracks.isNotEmpty()) {
                item(key = "history") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlacSectionHeader(
                            title = "История прослушивания",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            onSeeAllClick = {
                                onOpenSectionTracks("История прослушивания", historyTracks)
                            }
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(historyTracks.take(18), key = { "hist_${it.id}" }) { track ->
                                HomeTrackSquareCard(
                                    track = track,
                                    isCurrent = track.id == currentTrack?.id,
                                    onClick = { onPlayTrack(track, historyTracks) },
                                    onMenu = { onOpenTrackMenu(track) }
                                )
                            }
                        }
                    }
                }
            }

            if (recentlyAddedTracks.isNotEmpty()) {
                item(key = "added") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlacSectionHeader(
                            title = "Недавно добавленные",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            onSeeAllClick = {
                                onOpenSectionTracks(
                                    "Недавно добавленные",
                                    sortedByAddedTracks
                                )
                            }
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            recentlyAddedTracks.take(4).forEach { track ->
                                TrackRow(
                                    track = track,
                                    isCurrent = track.id == currentTrack?.id,
                                    onClick = { onPlayTrack(track, recentlyAddedTracks) },
                                    onMoreClick = { onOpenTrackMenu(track) },
                                    showQualityBadge = showQualityBadges,
                                    compact = true
                                )
                            }
                        }
                    }
                }
            }

            if (favoriteTracks.isNotEmpty()) {
                item(key = "favorites") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlacSectionHeader(
                            title = "Избранное",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            onSeeAllClick = {
                                onOpenSectionTracks("Избранное", favoriteTracks)
                            }
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 6.dp)
                        ) {
                            favoriteTracks.take(6).forEach { track ->
                                TrackRow(
                                    track = track,
                                    isCurrent = track.id == currentTrack?.id,
                                    onClick = { onPlayTrack(track, favoriteTracks) },
                                    onMoreClick = { onOpenTrackMenu(track) },
                                    showQualityBadge = showQualityBadges,
                                    compact = compactMode
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTrackSquareCard(
    track: AudioFileMetadata,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    val panelTokens = remember(tokens) { tokens.forPanel() }
    val cardShape = RoundedCornerShape(tokens.radiusMd)
    val frostedBg = if (!isCurrent && tokens.config.panelBlurPx > 0) LocalFrostedBg.current else null
    val boxMod = if (isCurrent) {
        Modifier
            .width(134.dp)
            .clip(cardShape)
            .background(tokens.accent, cardShape)
            .border(1.dp, tokens.accent, cardShape)
    } else {
        Modifier
            .width(134.dp)
            .flacCardFrostedGlass(frostedBg = frostedBg, tintColor = tokens.cardBg, shape = cardShape)
            .border(1.dp, tokens.panelLine, cardShape)
    }
    val fgTitle = if (isCurrent) tokens.accentFg else panelTokens.fg0
    val fgSub = if (isCurrent) tokens.accentFg.copy(alpha = 0.78f) else panelTokens.fg2

    Column(
        modifier = boxMod
            .combinedClickable(
                onClick = onClick,
                onLongClick = onMenu
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        TrackArtwork(
            track = track,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = tokens.radiusSm,
            iconSize = 28.dp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = track.title,
                    color = fgTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = fgSub,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FlacIconButton(
                icon = Icons.Rounded.MoreVert,
                contentDescription = "Действия",
                onClick = onMenu,
                size = 22.dp,
                iconSize = 15.dp,
                tint = fgSub
            )
        }
    }
}
