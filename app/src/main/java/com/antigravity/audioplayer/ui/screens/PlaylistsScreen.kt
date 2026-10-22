package com.antigravity.audioplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.Playlist
import com.antigravity.audioplayer.data.storage.PlaylistManager
import com.antigravity.audioplayer.ui.components.AlbumArtView
import com.antigravity.audioplayer.ui.components.QualityBadge

enum class FavPlayTab { FAVORITES, PLAYLISTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    allTracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    onTrackSelect: (AudioFileMetadata, List<AudioFileMetadata>?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pm = remember { PlaylistManager.getInstance(context) }
    val favoriteIds by pm.favoriteIds.collectAsState()
    val playlists by pm.playlists.collectAsState()

    var selectedTab by remember { mutableStateOf(FavPlayTab.FAVORITES) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    // Currently opened playlist
    var openPlaylistId by remember { mutableStateOf<String?>(null) }

    val favoriteTracks = remember(favoriteIds, allTracks) {
        allTracks.filter { favoriteIds.contains(it.id) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (selectedTab == FavPlayTab.FAVORITES) Icons.Default.Favorite else Icons.Default.PlaylistPlay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column {
                    Text(
                        text = if (selectedTab == FavPlayTab.FAVORITES) "Избранное" else "Плейлисты",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedTab == FavPlayTab.FAVORITES) "${favoriteTracks.size} треков"
                               else "${playlists.size} плейлистов",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            if (selectedTab == FavPlayTab.PLAYLISTS) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Создать плейлист", modifier = Modifier.size(20.dp))
                }
            }
        }

        // Tabs: Избранное / Плейлисты
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            Tab(
                selected = selectedTab == FavPlayTab.FAVORITES,
                onClick = { selectedTab = FavPlayTab.FAVORITES },
                icon = { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text = { Text("Избранное", fontWeight = if (selectedTab == FavPlayTab.FAVORITES) FontWeight.Bold else FontWeight.Normal) }
            )
            Tab(
                selected = selectedTab == FavPlayTab.PLAYLISTS,
                onClick = { selectedTab = FavPlayTab.PLAYLISTS },
                icon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text = { Text("Плейлисты", fontWeight = if (selectedTab == FavPlayTab.PLAYLISTS) FontWeight.Bold else FontWeight.Normal) }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        when (selectedTab) {
            FavPlayTab.FAVORITES -> {
                if (favoriteTracks.isEmpty()) {
                    EmptyStateMessage(
                        icon = Icons.Default.FavoriteBorder,
                        title = "Нет избранных треков",
                        subtitle = "Нажмите ❤️ на треке чтобы добавить в избранное"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(favoriteTracks, key = { it.id }) { track ->
                            FavoriteTrackItem(
                                track = track,
                                isPlaying = currentTrack?.id == track.id,
                                onClick = { onTrackSelect(track, favoriteTracks) },
                                onUnfavorite = { pm.toggleFavorite(track.id) }
                            )
                        }
                    }
                }
            }

            FavPlayTab.PLAYLISTS -> {
                if (playlists.isEmpty()) {
                    EmptyStateMessage(
                        icon = Icons.Default.PlaylistAdd,
                        title = "Нет плейлистов",
                        subtitle = "Нажмите + чтобы создать первый плейлист"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            val playlistTracks = allTracks.filter { playlist.trackIds.contains(it.id) }
                            PlaylistCard(
                                playlist = playlist,
                                tracks = playlistTracks,
                                onClick = { openPlaylistId = playlist.id },
                                onDelete = { pm.deletePlaylist(playlist.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Create Playlist Dialog ──────────────────────────────────────────────
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                newPlaylistName = ""
            },
            title = { Text("Новый плейлист") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Название плейлиста") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            pm.createPlaylist(newPlaylistName)
                        }
                        showCreateDialog = false
                        newPlaylistName = ""
                    }
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateDialog = false
                    newPlaylistName = ""
                }) {
                    Text("Отмена")
                }
            }
        )
    }

    // ── Opened Playlist Detail ──────────────────────────────────────────────
    val openPlaylist = playlists.find { it.id == openPlaylistId }
    if (openPlaylist != null) {
        val playlistTracks = allTracks.filter { openPlaylist.trackIds.contains(it.id) }
        ModalBottomSheet(
            onDismissRequest = { openPlaylistId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Playlist header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtView(
                            track = playlistTracks.firstOrNull(),
                            modifier = Modifier.size(56.dp),
                            cornerRadius = 12
                        )
                        Column {
                            Text(openPlaylist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${playlistTracks.size} треков", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { openPlaylistId = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { playlistTracks.firstOrNull()?.let { onTrackSelect(it, playlistTracks) }; openPlaylistId = null },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Играть")
                    }
                    OutlinedButton(
                        onClick = {
                            val shuffled = playlistTracks.shuffled(kotlin.random.Random(System.currentTimeMillis()))
                            shuffled.firstOrNull()?.let {
                                com.antigravity.audioplayer.service.AudioPlayerService.setShuffle(true)
                                onTrackSelect(it, shuffled)
                            }
                            openPlaylistId = null
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Перемешать")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (playlistTracks.isEmpty()) {
                    Text(
                        "Плейлист пуст. Добавьте треки из библиотеки.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(playlistTracks, key = { it.id }) { track ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTrackSelect(track, playlistTracks); openPlaylistId = null },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (currentTrack?.id == track.id)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AlbumArtView(track = track, modifier = Modifier.size(40.dp), cornerRadius = 8)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artist, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(
                                        onClick = { pm.removeTrackFromPlaylist(openPlaylist.id, track.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "Убрать", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteTrackItem(
    track: AudioFileMetadata,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onUnfavorite: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AlbumArtView(track = track, modifier = Modifier.size(46.dp), cornerRadius = 10)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = track.artist,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            QualityBadge(metadata = track)
            IconButton(onClick = onUnfavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = "Убрать из избранного",
                    tint = Color(0xFFE44D7B),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    tracks: List<AudioFileMetadata>,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.size(56.dp)) {
                AlbumArtView(track = tracks.getOrNull(0), modifier = Modifier.size(56.dp), cornerRadius = 12)
                if (tracks.size >= 4) {
                    // Show small overlay for second track art
                    AlbumArtView(
                        track = tracks.getOrNull(1),
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.BottomEnd)
                            .padding(0.dp),
                        cornerRadius = 5
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${tracks.size} треков",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить плейлист?") },
            text = { Text("«${playlist.name}» будет удалён безвозвратно.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun EmptyStateMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
}
