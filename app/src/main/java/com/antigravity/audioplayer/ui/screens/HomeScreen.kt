package com.antigravity.audioplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.data.storage.PlaylistManager
import com.antigravity.audioplayer.ui.components.*
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme

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
    val recentlyAddedTracks = remember(tracks) {
        tracks.sortedByDescending { it.dateAdded }.take(40)
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
                                val albums = tracks.groupBy { it.album.ifBlank { "Без альбома" } }.values.toList()
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
                            count = historyTracks.size,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            onSeeAllClick = {
                                onOpenSectionTracks("История прослушивания", historyTracks)
                            }
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlacSectionHeader(
                            title = "Недавно добавленные",
                            count = recentlyAddedTracks.size,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            onSeeAllClick = {
                                onOpenSectionTracks(
                                    "Недавно добавленные",
                                    tracks.sortedByDescending { it.dateAdded }
                                )
                            }
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(recentlyAddedTracks.take(18), key = { "add_${it.id}" }) { track ->
                                HomeTrackSquareCard(
                                    track = track,
                                    isCurrent = track.id == currentTrack?.id,
                                    onClick = { onPlayTrack(track, recentlyAddedTracks) },
                                    onMenu = { onOpenTrackMenu(track) }
                                )
                            }
                        }
                    }
                }
            }

            if (favoriteTracks.isNotEmpty()) {
                item(key = "favorites_header") {
                    FlacSectionHeader(
                        title = "Избранное",
                        count = favoriteTracks.size,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onSeeAllClick = {
                            onOpenSectionTracks("Избранное", favoriteTracks)
                        }
                    )
                }
                items(favoriteTracks.take(6), key = { "fav_${it.id}" }) { track ->
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
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

@Composable
private fun HomeTrackSquareCard(
    track: AudioFileMetadata,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    val shape = RoundedCornerShape(tokens.radiusMd)
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(shape)
            .background(if (isCurrent) tokens.accent.copy(alpha = 0.12f) else tokens.cardBg, shape)
            .border(
                width = 1.dp,
                color = if (isCurrent) tokens.accent.copy(alpha = 0.65f) else tokens.line,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            TrackArtwork(
                track = track,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = tokens.radiusSm,
                iconSize = 28.dp
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = track.title,
                color = if (isCurrent) tokens.accent else tokens.fg0,
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
    }
}
