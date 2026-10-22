package com.antigravity.audioplayer.desktop.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.desktop.audio.DesktopAudioPlayer
import com.antigravity.audioplayer.desktop.model.DesktopTrack
import com.antigravity.audioplayer.desktop.repository.DesktopLibraryRepository
import com.antigravity.audioplayer.desktop.theme.AccentOrange
import com.antigravity.audioplayer.desktop.theme.AccentPink
import com.antigravity.audioplayer.desktop.theme.AccentPrimary
import com.antigravity.audioplayer.desktop.theme.ObsidianSurfaceVariant
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopLibraryScreen(
    modifier: Modifier = Modifier
) {
    val tracks by DesktopLibraryRepository.tracks.collectAsState()
    val favoriteIds by DesktopLibraryRepository.favoriteIds.collectAsState()
    val folders by DesktopLibraryRepository.folders.collectAsState()
    val currentTrack by DesktopAudioPlayer.currentTrack.collectAsState()
    val isPlaying by DesktopAudioPlayer.isPlaying.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Все треки") } // "Все треки", "Избранное", "Hi-Res"
    val scope = rememberCoroutineScope()

    val filteredTracks = remember(tracks, searchQuery, selectedFilter, favoriteIds) {
        tracks.filter { track ->
            val matchSearch = searchQuery.isBlank() ||
                    track.title.contains(searchQuery, ignoreCase = true) ||
                    track.artist.contains(searchQuery, ignoreCase = true) ||
                    track.album.contains(searchQuery, ignoreCase = true)

            val matchFilter = when (selectedFilter) {
                "Избранное" -> favoriteIds.contains(track.id)
                "Hi-Res" -> track.isHiRes
                else -> true
            }

            matchSearch && matchFilter
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp)
    ) {
        // ── TOP HEADER & ACTIONS ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Медиатека",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Всего треков: ${tracks.size} • Папок: ${folders.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Add Folder Button
                Button(
                    onClick = {
                        val chooser = JFileChooser()
                        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        chooser.dialogTitle = "Выберите папку с музыкой (FLAC, MP3, WAV)"
                        val result = chooser.showOpenDialog(null)
                        if (result == JFileChooser.APPROVE_OPTION) {
                            val selectedDir = chooser.selectedFile
                            scope.launch {
                                DesktopLibraryRepository.scanFolder(selectedDir)
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Добавить папку")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── SEARCH & FILTER TABS ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск трека, артиста или альбома...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Все треки", "Избранное", "Hi-Res").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── TRACKS TABLE HEADER ──────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            color = ObsidianSurfaceVariant,
            shape = RoundedCornerShape(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#", modifier = Modifier.width(36.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Название & Исполнитель", modifier = Modifier.weight(3f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Альбом", modifier = Modifier.weight(2f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Качество", modifier = Modifier.weight(1.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Длительность", modifier = Modifier.width(80.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(48.dp)) // Actions
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── TRACKS LIST ──────────────────────────────────────────────────────
        if (filteredTracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (tracks.isEmpty()) "Медиатека пуста" else "Ничего не найдено",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (tracks.isEmpty()) "Нажмите «Добавить папку», чтобы просканировать ваши FLAC и MP3 файлы" else "Попробуйте изменить поисковый запрос",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(filteredTracks, key = { _, track -> track.id }) { index, track ->
                    val isCurrent = currentTrack?.id == track.id
                    val isFav = favoriteIds.contains(track.id)

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                DesktopAudioPlayer.setPlaylist(filteredTracks)
                                DesktopAudioPlayer.playTrack(track)
                            },
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Track Number or Equalizer Waves
                            Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                                if (isCurrent && isPlaying) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = "Играет",
                                        tint = AccentPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 13.sp,
                                        color = if (isCurrent) AccentPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Title & Artist
                            Column(modifier = Modifier.weight(3f)) {
                                Text(
                                    text = track.title,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) AccentPrimary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Album
                            Text(
                                text = track.album,
                                modifier = Modifier.weight(2f),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Quality Badge
                            Row(
                                modifier = Modifier.weight(1.5f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = if (track.isHiRes) AccentOrange.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "${track.format} • ${track.sampleRateKHzFormatted}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (track.isHiRes) AccentOrange else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Duration
                            Text(
                                text = track.durationFormatted,
                                modifier = Modifier.width(80.dp),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Favorite Button
                            IconButton(
                                onClick = { DesktopLibraryRepository.toggleFavorite(track.id) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Избранное",
                                    tint = if (isFav) AccentPink else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
