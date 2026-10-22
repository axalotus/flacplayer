package com.antigravity.audioplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.AudioFormat
import com.antigravity.audioplayer.ui.components.AlbumArtView
import com.antigravity.audioplayer.ui.components.QualityBadge

@Composable
fun HomeScreen(
    tracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    onTrackSelect: (AudioFileMetadata, List<AudioFileMetadata>?) -> Unit,
    onAlbumSelect: (String, List<AudioFileMetadata>) -> Unit,
    onArtistSelect: (String, List<AudioFileMetadata>) -> Unit,
    onNavigateToLibrary: (LibrarySubTab) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { com.antigravity.audioplayer.data.storage.LibraryPreferences.getInstance(context) }
    val recentlyPlayedIds by prefs.recentlyPlayedIds.collectAsState()

    val albums = remember(tracks) { tracks.groupBy { it.album }.entries.toList() }
    val artists = remember(tracks) { tracks.groupBy { it.artist }.entries.toList() }
    val hiResTracks = remember(tracks) { tracks.filter { it.format == AudioFormat.FLAC || it.format == AudioFormat.WAV || it.bitDepth >= 24 } }
    val recentAddedTracks = remember(tracks) { tracks.sortedByDescending { it.dateAdded }.take(20) }
    val recentlyPlayedTracks = remember(tracks, recentlyPlayedIds) {
        recentlyPlayedIds.mapNotNull { id -> tracks.find { it.id == id } }.take(15)
    }
    val rediscoverTracks = remember(tracks) { tracks.shuffled().take(12) }
    val randomArtists = remember(artists) { artists.shuffled().take(10) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 90.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // ── TOP HEADER: App Brand & Search ──────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "FlacPlayer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Hi-Res Audio Engine • v1.16",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSearch,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Поиск",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── EMPTY STATE OR QUICK MIX CARDS ──────────────────────────────────
        if (tracks.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Медиатека пуста",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Выберите папку с вашей музыкой (FLAC, WAV, MP3), чтобы начать воспроизведение.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            // ── QUICK MIX CARDS ─────────────────────────────────────────────────
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        MixChipCard(
                            title = "Микс треков",
                            subtitle = "${tracks.size} песен",
                            icon = Icons.Default.Shuffle,
                            gradientColors = listOf(Color(0xFF3A7BD5), Color(0xFF3A6073)),
                            onClick = {
                                if (tracks.isNotEmpty()) {
                                    val shuffledQueue = tracks.shuffled(kotlin.random.Random(System.currentTimeMillis()))
                                    com.antigravity.audioplayer.service.AudioPlayerService.setShuffle(true)
                                    onTrackSelect(shuffledQueue.first(), shuffledQueue)
                                }
                            }
                        )
                    }
                    item {
                        MixChipCard(
                            title = "Случайный альбом",
                            subtitle = "${albums.size} альбомов",
                            icon = Icons.Default.Album,
                            gradientColors = listOf(Color(0xFF8A2387), Color(0xFFE94057)),
                            onClick = {
                                if (albums.isNotEmpty()) {
                                    val randomAlbum = albums.random()
                                    onAlbumSelect(randomAlbum.key, randomAlbum.value)
                                }
                            }
                        )
                    }
                    item {
                        MixChipCard(
                            title = "Hi-Res / Lossless",
                            subtitle = "${hiResTracks.size} Master",
                            icon = Icons.Default.Star,
                            gradientColors = listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
                            onClick = {
                                if (hiResTracks.isNotEmpty()) {
                                    val shuffledHiRes = hiResTracks.shuffled(kotlin.random.Random(System.currentTimeMillis()))
                                    com.antigravity.audioplayer.service.AudioPlayerService.setShuffle(true)
                                    onTrackSelect(shuffledHiRes.first(), shuffledHiRes)
                                } else if (tracks.isNotEmpty()) {
                                    val shuffledTracks = tracks.shuffled(kotlin.random.Random(System.currentTimeMillis()))
                                    com.antigravity.audioplayer.service.AudioPlayerService.setShuffle(true)
                                    onTrackSelect(shuffledTracks.first(), shuffledTracks)
                                }
                            }
                        )
                    }
                }
            }

            // ── SECTION: ИСТОРИЯ ПРОСЛУШИВАНИЯ (если есть) ──────────────────────
            if (recentlyPlayedTracks.isNotEmpty()) {
                item {
                    DashboardSection(
                        title = "История прослушивания",
                        countText = "${recentlyPlayedTracks.size}",
                        onClickHeader = { onNavigateToLibrary(LibrarySubTab.TRACKS) }
                    ) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(recentlyPlayedTracks, key = { "history_${it.id}" }) { track ->
                                val isPlaying = track.id == currentTrack?.id
                                TrackCard(
                                    track = track,
                                    isPlaying = isPlaying,
                                    onClick = { onTrackSelect(track, recentlyPlayedTracks) }
                                )
                            }
                        }
                    }
                }
            }

            // ── SECTION 1: НЕДАВНО ДОБАВЛЕННЫЕ (Честная дата) ───────────────────
            item {
                DashboardSection(
                    title = "Недавно добавленные",
                    countText = "${recentAddedTracks.size}",
                    onClickHeader = { onNavigateToLibrary(LibrarySubTab.TRACKS) }
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recentAddedTracks, key = { "recent_${it.id}" }) { track ->
                            val isPlaying = track.id == currentTrack?.id
                            TrackCard(
                                track = track,
                                isPlaying = isPlaying,
                                onClick = { onTrackSelect(track, recentAddedTracks) }
                            )
                        }
                    }
                }
            }

            // ── SECTION 2: ОТКРОЙТЕ ЗАНОВО ──────────────────────────────────────
            item {
                DashboardSection(
                    title = "Откройте заново",
                    countText = "Микс",
                    onClickHeader = { onNavigateToLibrary(LibrarySubTab.TRACKS) }
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(rediscoverTracks, key = { it.id }) { track ->
                            val isPlaying = track.id == currentTrack?.id
                            TrackCard(
                                track = track,
                                isPlaying = isPlaying,
                                onClick = { onTrackSelect(track, rediscoverTracks) }
                            )
                        }
                    }
                }
            }

            // ── SECTION 3: СЛУЧАЙНЫЕ ИСПОЛНИТЕЛИ ────────────────────────────────
            item {
                DashboardSection(
                    title = "Исполнители",
                    countText = "${artists.size}",
                    onClickHeader = { onNavigateToLibrary(LibrarySubTab.ARTISTS) }
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(randomArtists, key = { it.key }) { (artistName, artistTracks) ->
                            ArtistCircleCard(
                                artistName = artistName,
                                sampleTrack = artistTracks.firstOrNull(),
                                trackCount = artistTracks.size,
                                onClick = { onArtistSelect(artistName, artistTracks) }
                            )
                        }
                    }
                }
            }

            // ── SECTION 4: АЛЬБОМЫ ──────────────────────────────────────────────
            item {
                DashboardSection(
                    title = "Альбомы",
                    countText = "${albums.size}",
                    onClickHeader = { onNavigateToLibrary(LibrarySubTab.ALBUMS) }
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(albums.take(12), key = { it.key }) { (albumName, albumTracks) ->
                            AlbumCard(
                                albumName = albumName,
                                artistName = albumTracks.firstOrNull()?.artist ?: "",
                                sampleTrack = albumTracks.firstOrNull(),
                                trackCount = albumTracks.size,
                                onClick = { onAlbumSelect(albumName, albumTracks) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardSection(
    title: String,
    countText: String,
    onClickHeader: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .then(
                    if (onClickHeader != null) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onClickHeader)
                    } else Modifier
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (onClickHeader != null) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Перейти",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Surface(
                color = if (onClickHeader != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = countText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (onClickHeader != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun MixChipCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .width(150.dp)
            .height(76.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradientColors))
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: AudioFileMetadata,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.width(136.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AlbumArtView(
                    track = track,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 12
                )
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = track.title,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            QualityBadge(metadata = track)
        }
    }
}

@Composable
private fun AlbumCard(
    albumName: String,
    artistName: String,
    sampleTrack: AudioFileMetadata?,
    trackCount: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.width(136.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            AlbumArtView(
                track = sampleTrack,
                modifier = Modifier.size(120.dp),
                cornerRadius = 12
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = albumName,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$artistName • $trackCount",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ArtistCircleCard(
    artistName: String,
    sampleTrack: AudioFileMetadata?,
    trackCount: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(90.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumArtView(
            track = sampleTrack,
            modifier = Modifier.size(80.dp),
            cornerRadius = 40
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artistName,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$trackCount треков",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}
