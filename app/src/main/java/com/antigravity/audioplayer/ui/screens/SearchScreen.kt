package com.antigravity.audioplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.ui.components.*
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme

private enum class SearchFilter(val label: String) {
    ALL("Все"),
    TRACKS("Треки"),
    ALBUMS("Альбомы"),
    ARTISTS("Артисты")
}

@Composable
fun SearchScreen(
    tracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    onPlayTrack: (AudioFileMetadata, List<AudioFileMetadata>) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenTrackMenu: (AudioFileMetadata) -> Unit,
    bottomInsetPadding: Dp
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val prefs = remember { LibraryPreferences.getInstance(context) }

    val recentSearchedIds by prefs.recentSearchedTrackIds.collectAsState()
    val showQualityBadges by prefs.showQualityBadges.collectAsState()
    val compactMode by prefs.isCompactMode.collectAsState()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }

    val tracksById = remember(tracks) { tracks.associateBy { it.id } }
    val recentSearchedTracks = remember(recentSearchedIds, tracksById) {
        recentSearchedIds.mapNotNull { tracksById[it] }
    }

    val normalized = query.trim().lowercase()

    val matchingTracks = remember(tracks, normalized) {
        if (normalized.isEmpty()) emptyList()
        else tracks.filter {
            it.title.lowercase().contains(normalized) ||
                it.artist.lowercase().contains(normalized) ||
                it.album.lowercase().contains(normalized)
        }
    }

    val matchingAlbums = remember(tracks, normalized) {
        if (normalized.isEmpty()) emptyList()
        else tracks.groupBy { it.album.ifBlank { "Без альбома" } }
            .filter { (album, list) ->
                album.lowercase().contains(normalized) ||
                    list.any { it.artist.lowercase().contains(normalized) }
            }
            .map { (album, list) ->
                AlbumGroup(
                    name = album,
                    artist = list.firstOrNull()?.artist ?: "Неизвестный",
                    tracks = list,
                    representativeTrack = list.first()
                )
            }
    }

    val matchingArtists = remember(tracks, normalized) {
        if (normalized.isEmpty()) emptyList()
        else tracks.groupBy { it.artist.ifBlank { "Неизвестный" } }
            .filter { (artist, _) -> artist.lowercase().contains(normalized) }
            .map { (artist, list) ->
                ArtistGroup(
                    name = artist,
                    tracks = list,
                    albumCount = list.map { it.album }.distinct().size,
                    representativeTrack = list.first()
                )
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Поиск",
                color = tokens.fg0,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(tokens.radiusMd))
                    .background(tokens.cardBg, RoundedCornerShape(tokens.radiusMd))
                    .border(1.dp, tokens.lineStrong, RoundedCornerShape(tokens.radiusMd))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = tokens.fg2,
                    modifier = Modifier.size(19.dp)
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Треки, альбомы, исполнители...",
                            color = tokens.fg2,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = tokens.fg0,
                            fontSize = 14.5.sp,
                            fontFamily = tokens.fontFamily
                        ),
                        cursorBrush = SolidColor(tokens.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    FlacIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Очистить",
                        onClick = { query = "" },
                        size = 24.dp,
                        iconSize = 16.dp,
                        tint = tokens.fg2
                    )
                }
            }

            if (normalized.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(SearchFilter.values().toList()) { item ->
                        FlacPillButton(
                            text = item.label,
                            active = item == filter,
                            onClick = { filter = item }
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = bottomInsetPadding + 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (normalized.isEmpty()) {
                item(key = "recent_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Недавно найденные",
                            color = tokens.fg0,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (recentSearchedTracks.isNotEmpty()) {
                            Text(
                                text = "Очистить",
                                color = tokens.fg2,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { prefs.clearRecentSearchedTracks() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (recentSearchedTracks.isEmpty()) {
                    item(key = "recent_empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = tokens.fg2.copy(alpha = 0.6f),
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = "Здесь появятся треки, которые ты открывал из поиска",
                                color = tokens.fg2,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(recentSearchedTracks, key = { "rec_${it.id}" }) { track ->
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            TrackRow(
                                track = track,
                                isCurrent = track.id == currentTrack?.id,
                                onClick = {
                                    prefs.recordTrackSearched(track.id)
                                    onPlayTrack(track, recentSearchedTracks)
                                },
                                onMoreClick = { onOpenTrackMenu(track) },
                                showQualityBadge = showQualityBadges,
                                compact = compactMode
                            )
                        }
                    }
                }
            } else {
                val showArtists = (filter == SearchFilter.ALL || filter == SearchFilter.ARTISTS) && matchingArtists.isNotEmpty()
                val showAlbums = (filter == SearchFilter.ALL || filter == SearchFilter.ALBUMS) && matchingAlbums.isNotEmpty()
                val showTracks = (filter == SearchFilter.ALL || filter == SearchFilter.TRACKS) && matchingTracks.isNotEmpty()

                if (!showArtists && !showAlbums && !showTracks) {
                    item(key = "no_results") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Ничего не найдено", color = tokens.fg2, fontSize = 14.sp)
                        }
                    }
                }

                if (showArtists) {
                    item(key = "artists_label") {
                        Text(
                            text = "Исполнители",
                            color = tokens.fg2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    item(key = "artists_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(matchingArtists.take(12), key = { it.name }) { artist ->
                                SearchCompactCard(
                                    track = artist.representativeTrack,
                                    title = artist.name,
                                    subtitle = "${artist.tracks.size} тр.",
                                    onClick = { onOpenArtist(artist.name) }
                                )
                            }
                        }
                    }
                }

                if (showAlbums) {
                    item(key = "albums_label") {
                        Text(
                            text = "Альбомы",
                            color = tokens.fg2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    item(key = "albums_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(matchingAlbums.take(12), key = { it.name }) { album ->
                                SearchCompactCard(
                                    track = album.representativeTrack,
                                    title = album.name,
                                    subtitle = album.artist,
                                    onClick = { onOpenAlbum(album.name) }
                                )
                            }
                        }
                    }
                }

                if (showTracks) {
                    item(key = "tracks_label") {
                        Text(
                            text = "Треки",
                            color = tokens.fg2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    items(matchingTracks, key = { "res_${it.id}" }) { track ->
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            TrackRow(
                                track = track,
                                isCurrent = track.id == currentTrack?.id,
                                onClick = {
                                    prefs.recordTrackSearched(track.id)
                                    onPlayTrack(track, matchingTracks)
                                },
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

@Composable
private fun SearchCompactCard(
    track: AudioFileMetadata,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    FlacGlassCard(
        modifier = Modifier.width(120.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TrackArtwork(
                track = track,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                cornerRadius = tokens.radiusSm
            )
            Text(
                text = title,
                color = tokens.fg0,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = tokens.fg2,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
