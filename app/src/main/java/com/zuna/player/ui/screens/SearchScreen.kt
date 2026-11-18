package com.zuna.player.ui.screens

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
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.ui.components.*
import com.zuna.player.ui.theme.LocalFlacTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SearchFilter(val label: String) {
    ALL("Все"),
    TRACKS("Треки"),
    ARTISTS("Артисты"),
    ALBUMS("Альбомы")
}

@Composable
fun SearchScreen(
    tracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    onPlayTrack: (AudioFileMetadata, List<AudioFileMetadata>) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
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

    val searchIndexBundle by produceState(
        initialValue = Triple(
            emptyList<IndexedSearchTrack>(),
            emptyList<IndexedSearchAlbum>(),
            emptyList<IndexedSearchArtist>()
        ),
        key1 = tracks
    ) {
        value = withContext(Dispatchers.Default) {
            val sTracks = tracks.map { t ->
                IndexedSearchTrack(
                    track = t,
                    titleLower = t.title.lowercase(),
                    artistLower = t.artist.lowercase(),
                    albumLower = t.album.lowercase()
                )
            }
            val sAlbums = tracks.groupBy {
                it.album.ifBlank { "Без альбома" } to it.artist.ifBlank { "Неизвестный" }
            }.map { (key, list) ->
                IndexedSearchAlbum(
                    group = AlbumGroup(
                        name = key.first,
                        artist = key.second,
                        tracks = list,
                        representativeTrack = list.first()
                    ),
                    nameLower = key.first.lowercase(),
                    artistLower = key.second.lowercase()
                )
            }
            val sArtists = tracks.groupBy { it.artist.ifBlank { "Неизвестный" } }
                .map { (artist, list) ->
                    IndexedSearchArtist(
                        group = ArtistGroup(
                            name = artist,
                            tracks = list,
                            albumCount = list.map { it.album }.distinct().size,
                            representativeTrack = list.first()
                        ),
                        nameLower = artist.lowercase()
                    )
                }
            Triple(sTracks, sAlbums, sArtists)
        }
    }
    val searchIndex = searchIndexBundle.first
    val allIndexedAlbums = searchIndexBundle.second
    val allIndexedArtists = searchIndexBundle.third

    val normalized = query.trim().lowercase()

    val matchingTracks = remember(searchIndex, normalized) {
        if (normalized.isEmpty()) emptyList()
        else searchIndex.mapNotNull { entry ->
            if (entry.titleLower.contains(normalized) ||
                entry.artistLower.contains(normalized) ||
                entry.albumLower.contains(normalized)
            ) entry.track else null
        }
    }

    val matchingAlbums = remember(allIndexedAlbums, normalized) {
        if (normalized.isEmpty()) emptyList()
        else allIndexedAlbums.mapNotNull { entry ->
            if (entry.nameLower.contains(normalized) || entry.artistLower.contains(normalized)) {
                entry.group
            } else null
        }
    }

    val matchingArtists = remember(allIndexedArtists, normalized) {
        if (normalized.isEmpty()) emptyList()
        else allIndexedArtists.mapNotNull { entry ->
            if (entry.nameLower.contains(normalized)) entry.group else null
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
                    .border(1.dp, tokens.panelLineStrong, RoundedCornerShape(tokens.radiusMd))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = tokens.panelFg2,
                    modifier = Modifier.size(19.dp)
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Треки, альбомы, исполнители...",
                            color = tokens.panelFg2,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = tokens.panelFg0,
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
                        tint = tokens.panelFg2
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
                            items(matchingAlbums.take(12), key = { "${it.name}\u0000${it.artist}" }) { album ->
                                SearchCompactCard(
                                    track = album.representativeTrack,
                                    title = album.name,
                                    subtitle = album.artist,
                                    onClick = { onOpenAlbum(album.name, album.artist) }
                                )
                            }
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
    val panelTokens = remember(tokens) { tokens.forPanel() }
    val cardShape = remember(tokens.radiusMd) { RoundedCornerShape(tokens.radiusMd) }
    val frostedBg = if (tokens.config.panelBlurPx > 0) LocalFrostedBg.current else null
    Column(
        modifier = Modifier
            .width(128.dp)
            .flacCardFrostedGlass(frostedBg = frostedBg, tintColor = tokens.cardBg, shape = cardShape)
            .border(1.dp, tokens.panelLine, cardShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TrackArtwork(
            track = track,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            cornerRadius = tokens.radiusSm,
            iconSize = 26.dp
        )
        Text(
            text = title,
            color = panelTokens.fg0,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            color = panelTokens.fg2,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class IndexedSearchTrack(
    val track: AudioFileMetadata,
    val titleLower: String,
    val artistLower: String,
    val albumLower: String
)

private data class IndexedSearchAlbum(
    val group: AlbumGroup,
    val nameLower: String,
    val artistLower: String
)

private data class IndexedSearchArtist(
    val group: ArtistGroup,
    val nameLower: String
)
