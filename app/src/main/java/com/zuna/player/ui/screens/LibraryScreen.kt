package com.zuna.player.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.data.storage.Playlist
import com.zuna.player.data.storage.PlaylistManager
import com.zuna.player.ui.components.*
import com.zuna.player.ui.theme.LocalFlacTheme

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
    onOpenAlbum: (String, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSectionTracks: (String, List<AudioFileMetadata>) -> Unit,
    onOpenTrackMenu: (AudioFileMetadata) -> Unit,
    onRescanLibrary: () -> Unit,
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
    val historyTracks by remember(recentlyPlayedIds, tracksById) {
        lazy(LazyThreadSafetyMode.NONE) { recentlyPlayedIds.mapNotNull { tracksById[it] } }
    }
    val recentlyAddedTracks by remember(tracks) {
        lazy(LazyThreadSafetyMode.NONE) { tracks.sortedByDescending { it.dateAdded } }
    }

    val sortedTracks by remember(tracks, sortOrder) {
        lazy(LazyThreadSafetyMode.NONE) {
            when (sortOrder) {
                TrackSortOrder.TITLE -> tracks.sortedBy { it.title.lowercase() }
                TrackSortOrder.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
                TrackSortOrder.RECENT -> tracks.sortedByDescending { it.dateAdded }
            }
        }
    }

    val albums by remember(tracks) {
        lazy(LazyThreadSafetyMode.NONE) {
            tracks.groupBy {
                it.album.ifBlank { "Без альбома" } to it.artist.ifBlank { "Неизвестный" }
            }
                .map { (key, list) ->
                    val sorted = list.sortedBy { it.trackNumber }
                    AlbumGroup(
                        name = key.first,
                        artist = key.second,
                        tracks = sorted,
                        representativeTrack = sorted.first()
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
    }

    val artists by remember(tracks) {
        lazy(LazyThreadSafetyMode.NONE) {
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
    }

    val folders by remember(tracks) {
        lazy(LazyThreadSafetyMode.NONE) {
            tracks.groupBy { track -> extractFolderDisplayAndKey(track.uri) }
                .map { (nameAndKey, list) ->
                    FolderGroup(name = nameAndKey.first, pathKey = nameAndKey.second, tracks = list)
                }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.pathKey.lowercase() }))
        }
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
                        letterSpacing = (-0.6).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedCategory == LibraryCategoryType.ALL_TRACKS && tracks.isNotEmpty()) {
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
                        FlacIconButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = "Пересканировать медиатеку",
                            onClick = onRescanLibrary,
                            size = 36.dp,
                            iconSize = 20.dp,
                            tint = tokens.fg0,
                            backgroundColor = tokens.cardBg,
                            borderColor = tokens.line
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
                        verticalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 6.dp)
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
                        itemsIndexed(
                            items = sortedTracks,
                            key = { _, t -> t.id },
                            contentType = { _, _ -> "track" }
                        ) { idx, track ->
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
                        items(albums, key = { "${it.name}\u0000${it.artist}" }) { album ->
                            GridGroupCard(
                                representativeTrack = album.representativeTrack,
                                title = album.name,
                                subtitle = "${album.artist} • ${album.tracks.size}",
                                onClick = { onOpenAlbum(album.name, album.artist) }
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
                    val panelTokens = remember(tokens) { tokens.forPanel() }
                    val cardShape = remember(tokens.radiusMd) { RoundedCornerShape(tokens.radiusMd) }
                    val frostedBg = if (tokens.config.panelBlurPx > 0) LocalFrostedBg.current else null
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 6.dp,
                            bottom = bottomInsetPadding + 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item(key = "fav_card") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .flacCardFrostedGlass(frostedBg = frostedBg, tintColor = tokens.cardBg, shape = cardShape)
                                    .border(1.dp, tokens.panelLine, cardShape)
                                    .clickable { onOpenSectionTracks("Избранное", favoriteTracks) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(tokens.accent.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Favorite,
                                        contentDescription = null,
                                        tint = tokens.accent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text("Избранное", color = panelTokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text("${favoriteTracks.size} треков", color = panelTokens.fg2, fontSize = 12.5.sp)
                                }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = panelTokens.fg2, modifier = Modifier.size(20.dp))
                            }
                        }

                        items(playlists, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .flacCardFrostedGlass(frostedBg = frostedBg, tintColor = tokens.cardBg, shape = cardShape)
                                    .border(1.dp, tokens.panelLine, cardShape)
                                    .clickable { onOpenPlaylist(pl) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val firstTrack = remember(pl.trackIds, tracksById) {
                                    pl.trackIds.firstNotNullOfOrNull { tracksById[it] }
                                }
                                if (firstTrack != null) {
                                    TrackArtwork(
                                        track = firstTrack,
                                        modifier = Modifier.size(50.dp),
                                        cornerRadius = 10.dp
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(tokens.bg2),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = panelTokens.fg2)
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(pl.name, color = panelTokens.fg0, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text("${pl.trackIds.size} треков", color = panelTokens.fg2, fontSize = 12.5.sp)
                                }
                                FlacIconButton(
                                    icon = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Удалить плейлист",
                                    onClick = { pm.deletePlaylist(pl.id) },
                                    size = 32.dp,
                                    iconSize = 18.dp,
                                    tint = panelTokens.fg2
                                )
                            }
                        }
                    }
                }

                LibraryCategoryType.FOLDERS -> {
                    val panelTokens = remember(tokens) { tokens.forPanel() }
                    val cardShape = remember(tokens.radiusMd) { RoundedCornerShape(tokens.radiusMd) }
                    val frostedBg = if (tokens.config.panelBlurPx > 0) LocalFrostedBg.current else null
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 6.dp,
                            bottom = bottomInsetPadding + 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(folders, key = { it.pathKey }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .flacCardFrostedGlass(frostedBg = frostedBg, tintColor = tokens.cardBg, shape = cardShape)
                                    .border(1.dp, tokens.panelLine, cardShape)
                                    .clickable { onOpenSectionTracks(folder.name, folder.tracks) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(tokens.bg2),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = panelTokens.fg1)
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = folder.name,
                                        color = panelTokens.fg0,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (folder.pathKey != folder.name && !folder.pathKey.startsWith("media_") && !folder.pathKey.startsWith("local_")) {
                                            "${folder.tracks.size} треков • ${folder.pathKey}"
                                        } else {
                                            "${folder.tracks.size} треков"
                                        },
                                        color = panelTokens.fg2,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = panelTokens.fg2, modifier = Modifier.size(20.dp))
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
        LaunchedEffect(showCreatePlaylist) {
            if (!showCreatePlaylist) {
                draftPlaylistName = ""
            }
        }
        BackHandler(enabled = showCreatePlaylist) {
            showCreatePlaylist = false
        }
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
            verticalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 6.dp)
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
    val panelTokens = remember(tokens) { tokens.forPanel() }
    val cardShape = remember(tokens.radiusMd) { RoundedCornerShape(tokens.radiusMd) }
    val frostedBg = if (tokens.config.panelBlurPx > 0) LocalFrostedBg.current else null
    Column(
        modifier = Modifier
            .flacCardFrostedGlass(frostedBg = frostedBg, tintColor = tokens.cardBg, shape = cardShape)
            .border(1.dp, tokens.panelLine, cardShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
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
                color = panelTokens.fg0,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = panelTokens.fg2,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun extractFolderDisplayAndKey(uri: Uri): Pair<String, String> {
    if (uri.scheme == "content" && uri.authority == "media") {
        return "Локальные файлы" to "media_store_root"
    }
    val rawPath = Uri.decode(uri.path ?: uri.toString())
    val normalized = if (uri.scheme == "content" && rawPath.contains("/document/")) {
        val docPart = rawPath.substringAfterLast("/document/")
        docPart.substringAfter(':', docPart)
    } else if (uri.scheme == "content") {
        val lastSeg = Uri.decode(uri.lastPathSegment ?: "")
        if (lastSeg.contains('/')) lastSeg.substringAfter(':', lastSeg) else rawPath
    } else {
        rawPath
    }
    val dirPath = normalized.substringBeforeLast('/', "").trimEnd('/')
    val cleanDir = dirPath.substringAfter(':', dirPath)
    val leafName = cleanDir.substringAfterLast('/', "").ifBlank { "Локальные файлы" }
    val key = cleanDir.ifBlank { "local_root" }
    return leafName to key
}
