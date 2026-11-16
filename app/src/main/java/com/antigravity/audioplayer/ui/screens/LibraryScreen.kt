package com.antigravity.audioplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
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
import com.antigravity.audioplayer.data.storage.Playlist
import com.antigravity.audioplayer.data.storage.PlaylistManager
import com.antigravity.audioplayer.ui.components.*
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme

enum class LibraryCategoryType(val label: String) {
    ALL_TRACKS("Треки"),
    ALBUMS("Альбомы"),
    ARTISTS("Артисты"),
    PLAYLISTS("Плейлисты"),
    FOLDERS("Папки"),
    HISTORY("История"),
    RECENTLY_ADDED("Недавние")
}

private enum class TrackSortOrder(val label: String) {
    TITLE("По названию"),
    ARTIST("По артисту"),
    RECENT("Недавние")
}

@Composable
fun LibraryScreen(
    tracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    selectedCategory: LibraryCategoryType,
    onCategoryChange: (LibraryCategoryType) -> Unit,
    onPlayTrack: (AudioFileMetadata, List<AudioFileMetadata>) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSectionTracks: (String, List<AudioFileMetadata>) -> Unit,
    onOpenTrackMenu: (AudioFileMetadata) -> Unit,
    bottomInsetPadding: Dp
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val pm = remember { PlaylistManager.getInstance(context) }

    val playlists by pm.playlists.collectAsState()
    val favoriteIds by pm.favoriteIds.collectAsState()
    val recentlyPlayedIds by prefs.recentlyPlayedIds.collectAsState()
    val showQualityBadges by prefs.showQualityBadges.collectAsState()
    val showTrackNumbers by prefs.isShowTrackNumbers.collectAsState()
    val compactMode by prefs.isCompactMode.collectAsState()

    var sortOrder by remember { mutableStateOf(TrackSortOrder.TITLE) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    val tracksById = remember(tracks) { tracks.associateBy { it.id } }
    val historyTracks = remember(recentlyPlayedIds, tracksById) {
        recentlyPlayedIds.mapNotNull { tracksById[it] }
    }
    val recentlyAddedTracks = remember(tracks) {
        tracks.sortedByDescending { it.dateAdded }
    }

    val sortedTracks = remember(tracks, sortOrder) {
        when (sortOrder) {
            TrackSortOrder.TITLE -> tracks.sortedBy { it.title.lowercase() }
            TrackSortOrder.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
            TrackSortOrder.RECENT -> tracks.sortedByDescending { it.dateAdded }
        }
    }

    val albums = remember(tracks) {
        tracks.groupBy { it.album.ifBlank { "Без альбома" } }
            .map { (name, list) ->
                val sorted = list.sortedBy { it.trackNumber }
                AlbumGroup(
                    name = name,
                    artist = sorted.firstOrNull()?.artist ?: "Неизвестный",
                    tracks = sorted,
                    representativeTrack = sorted.first()
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    val artists = remember(tracks) {
        tracks.groupBy { it.artist.ifBlank { "Неизвестный" } }
            .map { (name, list) ->
                ArtistGroup(
                    name = name,
                    tracks = list.sortedBy { it.title.lowercase() },
                    albumCount = list.map { it.album }.distinct().size,
                    representativeTrack = list.first()
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    val folders = remember(tracks) {
        tracks.groupBy { track ->
            val p = track.uri.path ?: track.uri.toString()
            val dir = p.substringBeforeLast('/', "")
            val seg = dir.substringAfterLast('/', "").ifBlank { "Локальные файлы" }
            seg
        }.map { (name, list) ->
            FolderGroup(name = name, pathKey = name, tracks = list)
        }.sortedBy { it.name.lowercase() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Медиатека",
                        color = tokens.fg0,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.6).sp
                    )
                    if (selectedCategory == LibraryCategoryType.ALL_TRACKS && sortedTracks.isNotEmpty()) {
                        FlacPillButton(
                            text = sortOrder.label,
                            icon = Icons.AutoMirrored.Rounded.Sort,
                            onClick = {
                                sortOrder = when (sortOrder) {
                                    TrackSortOrder.TITLE -> TrackSortOrder.ARTIST
                                    TrackSortOrder.ARTIST -> TrackSortOrder.RECENT
                                    TrackSortOrder.RECENT -> TrackSortOrder.TITLE
                                }
                            }
                        )
                    } else if (selectedCategory == LibraryCategoryType.PLAYLISTS) {
                        FlacPillButton(
                            text = "Создать",
                            icon = Icons.Rounded.Add,
                            accentFill = true,
                            onClick = { showCreatePlaylist = true }
                        )
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(LibraryCategoryType.values().toList()) { category ->
                        FlacPillButton(
                            text = category.label,
                            active = category == selectedCategory,
                            onClick = { onCategoryChange(category) }
                        )
                    }
                }
            }

            when (selectedCategory) {
                LibraryCategoryType.ALL_TRACKS -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 4.dp,
                            bottom = bottomInsetPadding + 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (sortedTracks.isNotEmpty()) {
                            item(key = "tracks_actions") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FlacPillButton(
                                        text = "Играть всё",
                                        icon = Icons.Rounded.PlayArrow,
                                        accentFill = true,
                                        onClick = { onPlayTrack(sortedTracks.first(), sortedTracks) }
                                    )
                                    FlacPillButton(
                                        text = "Перемешать",
                                        icon = Icons.Rounded.Shuffle,
                                        onClick = {
                                            val q = sortedTracks.shuffled()
                                            onPlayTrack(q.first(), q)
                                        }
                                    )
                                }
                            }
                        }
                        itemsIndexed(sortedTracks, key = { _, t -> t.id }) { idx, track ->
                            TrackRow(
                                track = track,
                                isCurrent = track.id == currentTrack?.id,
                                onClick = { onPlayTrack(track, sortedTracks) },
                                onMoreClick = { onOpenTrackMenu(track) },
                                indexNumber = if (showTrackNumbers) idx + 1 else null,
                                showQualityBadge = showQualityBadges,
                                compact = compactMode
                            )
                        }
                    }
                }

                LibraryCategoryType.ALBUMS -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = bottomInsetPadding + 20.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(albums, key = { it.name }) { album ->
                            GridGroupCard(
                                representativeTrack = album.representativeTrack,
                                title = album.name,
                                subtitle = "${album.artist} • ${album.tracks.size}",
                                onClick = { onOpenAlbum(album.name) }
                            )
                        }
                    }
                }

                LibraryCategoryType.ARTISTS -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = bottomInsetPadding + 20.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(artists, key = { it.name }) { artist ->
                            GridGroupCard(
                                representativeTrack = artist.representativeTrack,
                                title = artist.name,
                                subtitle = "${artist.albumCount} алб. • ${artist.tracks.size} тр.",
                                onClick = { onOpenArtist(artist.name) }
                            )
                        }
                    }
                }

                LibraryCategoryType.PLAYLISTS -> {
                    val favoriteTracks = remember(tracks, favoriteIds) {
                        tracks.filter { favoriteIds.contains(it.id) }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = bottomInsetPadding + 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "fav_card") {
                            FlacGlassCard(
                                onClick = { onOpenSectionTracks("Избранное", favoriteTracks) }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(RoundedCornerShape(tokens.radiusSm))
                                            .background(tokens.accent.copy(alpha = 0.16f))
                                            .border(1.dp, tokens.accent.copy(alpha = 0.45f), RoundedCornerShape(tokens.radiusSm)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Favorite,
                                            contentDescription = null,
                                            tint = tokens.accent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Избранное", color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${favoriteTracks.size} треков", color = tokens.fg2, fontSize = 12.sp)
                                    }
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tokens.fg2)
                                }
                            }
                        }

                        items(playlists, key = { it.id }) { pl ->
                            FlacGlassCard(onClick = { onOpenPlaylist(pl) }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val firstTrack = remember(pl.trackIds, tracks) {
                                        tracks.firstOrNull { pl.trackIds.contains(it.id) }
                                    }
                                    if (firstTrack != null) {
                                        TrackArtwork(
                                            track = firstTrack,
                                            modifier = Modifier.size(46.dp),
                                            cornerRadius = tokens.radiusSm
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(tokens.radiusSm))
                                                .background(tokens.bg2)
                                                .border(1.dp, tokens.line, RoundedCornerShape(tokens.radiusSm)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = tokens.fg2)
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pl.name, color = tokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${pl.trackIds.size} треков", color = tokens.fg2, fontSize = 12.sp)
                                    }
                                    FlacIconButton(
                                        icon = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Удалить плейлист",
                                        onClick = { pm.deletePlaylist(pl.id) },
                                        size = 34.dp,
                                        tint = tokens.fg2
                                    )
                                }
                            }
                        }
                    }
                }

                LibraryCategoryType.FOLDERS -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = bottomInsetPadding + 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(folders, key = { it.pathKey }) { folder ->
                            FlacGlassCard(
                                onClick = { onOpenSectionTracks(folder.name, folder.tracks) }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(tokens.radiusSm))
                                            .background(tokens.bg2)
                                            .border(1.dp, tokens.line, RoundedCornerShape(tokens.radiusSm)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = tokens.fg1)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.name,
                                            color = tokens.fg0,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${folder.tracks.size} треков",
                                            color = tokens.fg2,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tokens.fg2)
                                }
                            }
                        }
                    }
                }

                LibraryCategoryType.HISTORY -> {
                    SimpleTrackCategoryList(
                        tracks = historyTracks,
                        emptyText = "История прослушивания пока пуста",
                        currentTrack = currentTrack,
                        showQualityBadges = showQualityBadges,
                        compactMode = compactMode,
                        onPlayTrack = onPlayTrack,
                        onOpenTrackMenu = onOpenTrackMenu,
                        bottomInsetPadding = bottomInsetPadding
                    )
                }

                LibraryCategoryType.RECENTLY_ADDED -> {
                    SimpleTrackCategoryList(
                        tracks = recentlyAddedTracks,
                        emptyText = "Нет добавленных треков",
                        currentTrack = currentTrack,
                        showQualityBadges = showQualityBadges,
                        compactMode = compactMode,
                        onPlayTrack = onPlayTrack,
                        onOpenTrackMenu = onOpenTrackMenu,
                        bottomInsetPadding = bottomInsetPadding
                    )
                }
            }
        }

        var draftPlaylistName by remember { mutableStateOf("") }
        FlacOverlayFrame(
            visible = showCreatePlaylist,
            onDismiss = { showCreatePlaylist = false }
        ) {
            Text("Новый плейлист", color = tokens.fg0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.radiusSm))
                    .background(tokens.bg2)
                    .border(1.dp, tokens.line, RoundedCornerShape(tokens.radiusSm))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (draftPlaylistName.isEmpty()) {
                    Text("Название плейлиста...", color = tokens.fg2, fontSize = 14.sp)
                }
                BasicTextField(
                    value = draftPlaylistName,
                    onValueChange = { draftPlaylistName = it },
                    singleLine = true,
                    textStyle = TextStyle(color = tokens.fg0, fontSize = 14.sp),
                    cursorBrush = SolidColor(tokens.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlacPillButton(text = "Отмена", onClick = { showCreatePlaylist = false })
                Spacer(modifier = Modifier.width(8.dp))
                FlacPillButton(
                    text = "Создать",
                    accentFill = true,
                    onClick = {
                        val trimmed = draftPlaylistName.trim()
                        if (trimmed.isNotEmpty()) {
                            pm.createPlaylist(trimmed)
                            draftPlaylistName = ""
                            showCreatePlaylist = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SimpleTrackCategoryList(
    tracks: List<AudioFileMetadata>,
    emptyText: String,
    currentTrack: AudioFileMetadata?,
    showQualityBadges: Boolean,
    compactMode: Boolean,
    onPlayTrack: (AudioFileMetadata, List<AudioFileMetadata>) -> Unit,
    onOpenTrackMenu: (AudioFileMetadata) -> Unit,
    bottomInsetPadding: Dp
) {
    val tokens = LocalFlacTheme.current
    if (tracks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(emptyText, color = tokens.fg2, fontSize = 14.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = bottomInsetPadding + 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(tracks, key = { idx, t -> "${idx}_${t.id}" }) { _, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == currentTrack?.id,
                    onClick = { onPlayTrack(track, tracks) },
                    onMoreClick = { onOpenTrackMenu(track) },
                    showQualityBadge = showQualityBadges,
                    compact = compactMode
                )
            }
        }
    }
}

@Composable
private fun GridGroupCard(
    representativeTrack: AudioFileMetadata,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    FlacGlassCard(onClick = onClick) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TrackArtwork(
                track = representativeTrack,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                cornerRadius = tokens.radiusSm,
                iconSize = 32.dp
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = tokens.fg0,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = tokens.fg2,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
