package com.antigravity.audioplayer.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.data.storage.Playlist
import com.antigravity.audioplayer.data.storage.PlaylistManager
import com.antigravity.audioplayer.ui.components.AlbumArtView
import com.antigravity.audioplayer.ui.components.QualityBadge
import kotlinx.coroutines.launch
import java.io.File

enum class LibrarySubTab(val label: String) {
    TRACKS("Все треки"),
    ALBUMS("Альбомы"),
    ARTISTS("Исполнители"),
    FOLDERS("Папки")
}

enum class LibraryCategoryType(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentColor: Color
) {
    ALL_TRACKS("Все треки", "Полная медиатека аудиофайлов", Icons.Default.MusicNote, Color(0xFF3A86FF)),
    HISTORY("История", "История прослушивания", Icons.Default.History, Color(0xFF4CC9F0)),
    RECENTLY_ADDED("Недавно добавленные", "Новые композиции по дате обнаружения", Icons.Default.AccessTime, Color(0xFF06D6A0)),
    ALBUMS("Альбомы", "Сетка альбомов и релизов", Icons.Default.Album, Color(0xFF8338EC)),
    ARTISTS("Исполнители", "Исполнители и группы", Icons.Default.Person, Color(0xFFFF006E)),
    FOLDERS("Папки", "Файловая структура и директории", Icons.Default.Folder, Color(0xFFFFB703)),
    PLAYLISTS("Плейлисты", "Избранное и списки воспроизведения", Icons.AutoMirrored.Filled.QueueMusic, Color(0xFFFB5607)),
    HIDDEN_TRACKS("Скрытые треки", "Скрытые из медиатеки аудиофайлы", Icons.Default.VisibilityOff, Color(0xFFE63946))
}

enum class SortMode(val label: String) {
    TITLE_AZ("По названию (A-Z)"),
    ARTIST_AZ("По артисту (A-Z)"),
    QUALITY("Hi-Res / Формат"),
    DURATION("По длительности")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    tracks: List<AudioFileMetadata>,
    allTracks: List<AudioFileMetadata> = tracks,
    currentTrack: AudioFileMetadata?,
    isLoading: Boolean,
    selectedSubTab: LibrarySubTab = LibrarySubTab.TRACKS,
    onSubTabChange: (LibrarySubTab) -> Unit = {},
    initialCategory: LibraryCategoryType? = null,
    onCategoryChange: (LibraryCategoryType?) -> Unit = {},
    onTrackSelect: (AudioFileMetadata, List<AudioFileMetadata>?) -> Unit,
    onAlbumSelect: (String, List<AudioFileMetadata>) -> Unit,
    onArtistSelect: (String, List<AudioFileMetadata>) -> Unit,
    onPickFolder: () -> Unit,
    onRescanLibrary: () -> Unit = {},
    onAddNavidromeServer: (String, String) -> Unit,
    onOpenPowerampMenu: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val pm = remember { PlaylistManager.getInstance(context) }
    val favoriteIds by pm.favoriteIds.collectAsState()
    val playlists by pm.playlists.collectAsState()
    val hiddenTrackIds by prefs.hiddenTrackIds.collectAsState()

    val hiddenTracks = remember(allTracks, hiddenTrackIds) { allTracks.filter { it.id in hiddenTrackIds } }
    val visibleTracks = remember(allTracks, hiddenTrackIds) { allTracks.filter { it.id !in hiddenTrackIds } }

    var activeCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    LaunchedEffect(initialCategory) {
        if (initialCategory != null) {
            activeCategory = initialCategory
        }
    }

    var selectedFolderDrilldown by remember { mutableStateOf<String?>(null) }
    var selectedPlaylistDrilldown by remember { mutableStateOf<Playlist?>(null) }
    var showFavoritesDrilldown by remember { mutableStateOf(false) }

    // Intercept back button to return from sub-screens to main category menu
    BackHandler(enabled = activeCategory != null) {
        if (selectedFolderDrilldown != null) {
            selectedFolderDrilldown = null
        } else if (selectedPlaylistDrilldown != null) {
            selectedPlaylistDrilldown = null
        } else if (showFavoritesDrilldown) {
            showFavoritesDrilldown = false
        } else {
            activeCategory = null
            onCategoryChange(null)
        }
    }

    // Calculations for Category Badges
    val uniqueAlbumsCount = remember(visibleTracks) { visibleTracks.map { it.album }.distinct().size }
    val uniqueArtistsCount = remember(visibleTracks) { visibleTracks.map { it.artist }.distinct().size }
    val folderGroups = remember(visibleTracks) {
        visibleTracks.groupBy { track ->
            val path = track.uri.path ?: ""
            val f = File(path)
            f.parentFile?.absolutePath ?: "Основная память"
        }
    }
    val uniqueFoldersCount = folderGroups.size
    val totalPlaylistsCount = playlists.size + (if (favoriteIds.isNotEmpty()) 1 else 0)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (activeCategory == null) {
            // ── 1. MODULAR CATEGORIES MENU ──────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                // Top App Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Библиотека",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "${visibleTracks.size} треков",
                                fontSize = 11.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Search, contentDescription = "Поиск", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onRescanLibrary, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Пересканировать медиатеку", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onPickFolder, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "Папки", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onOpenPowerampMenu, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Vertical List of Categories
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    items(LibraryCategoryType.values()) { category ->
                        val countLabel = when (category) {
                            LibraryCategoryType.ALL_TRACKS -> "${visibleTracks.size}"
                            LibraryCategoryType.HISTORY -> "${prefs.recentlyPlayedIds.value.size}"
                            LibraryCategoryType.RECENTLY_ADDED -> "${visibleTracks.size}"
                            LibraryCategoryType.FOLDERS -> "$uniqueFoldersCount"
                            LibraryCategoryType.ALBUMS -> "$uniqueAlbumsCount"
                            LibraryCategoryType.ARTISTS -> "$uniqueArtistsCount"
                            LibraryCategoryType.PLAYLISTS -> "$totalPlaylistsCount"
                            LibraryCategoryType.HIDDEN_TRACKS -> "${hiddenTracks.size}"
                        }

                        ModularCategoryRow(
                            category = category,
                            countText = countLabel,
                            onClick = {
                                activeCategory = category
                                onCategoryChange(category)
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(30.dp))
                    }
                }
            }
        } else {
            // ── 2. FULL-SCREEN CATEGORY SUB-SCREENS ──────────────────────────
            when (activeCategory!!) {

                // ── ALL TRACKS ──────────────────────────────────────────────
                LibraryCategoryType.ALL_TRACKS -> {
                    AllTracksSubScreen(
                        tracks = visibleTracks,
                        currentTrack = currentTrack,
                        favoriteIds = favoriteIds,
                        playlists = playlists,
                        onBack = { activeCategory = null; onCategoryChange(null) },
                        onTrackSelect = onTrackSelect,
                        onFavoriteToggle = { pm.toggleFavorite(it) },
                        onAddToPlaylist = { pId, tId -> pm.addTrackToPlaylist(pId, tId) },
                        onHideTrack = { trackId ->
                            prefs.hideTrack(trackId)
                            android.widget.Toast.makeText(context, "Трек скрыт из медиатеки", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onOpenSearch = onOpenSearch
                    )
                }

                // ── HISTORY ─────────────────────────────────────────────────
                LibraryCategoryType.HISTORY -> {
                    val historyIds by prefs.recentlyPlayedIds.collectAsState()
                    val historyTracks = remember(visibleTracks, historyIds) {
                        historyIds.mapNotNull { id -> visibleTracks.find { it.id == id } }
                    }
                    TrackListDrilldownSubScreen(
                        title = "История прослушивания",
                        subtitle = "${historyTracks.size} недавно прослушанных треков",
                        tracks = historyTracks,
                        currentTrack = currentTrack,
                        favoriteIds = favoriteIds,
                        playlists = playlists,
                        onBack = { activeCategory = null; onCategoryChange(null) },
                        onTrackSelect = { tr, q -> onTrackSelect(tr, q ?: historyTracks) },
                        onFavoriteToggle = { pm.toggleFavorite(it) },
                        onAddToPlaylist = { pId, tId -> pm.addTrackToPlaylist(pId, tId) },
                        onHideTrack = { trackId ->
                            prefs.hideTrack(trackId)
                            android.widget.Toast.makeText(context, "Трек скрыт из медиатеки", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                // ── RECENTLY ADDED ──────────────────────────────────────────
                LibraryCategoryType.RECENTLY_ADDED -> {
                    val recentTracks = remember(visibleTracks) {
                        visibleTracks.sortedByDescending { it.dateAdded }
                    }
                    TrackListDrilldownSubScreen(
                        title = "Недавно добавленные",
                        subtitle = "${recentTracks.size} композиций по дате обнаружения",
                        tracks = recentTracks,
                        currentTrack = currentTrack,
                        favoriteIds = favoriteIds,
                        playlists = playlists,
                        onBack = { activeCategory = null; onCategoryChange(null) },
                        onTrackSelect = { tr, q -> onTrackSelect(tr, q ?: recentTracks) },
                        onFavoriteToggle = { pm.toggleFavorite(it) },
                        onAddToPlaylist = { pId, tId -> pm.addTrackToPlaylist(pId, tId) },
                        onHideTrack = { trackId ->
                            prefs.hideTrack(trackId)
                            android.widget.Toast.makeText(context, "Трек скрыт из медиатеки", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                // ── FOLDERS ─────────────────────────────────────────────────
                LibraryCategoryType.FOLDERS -> {
                    if (selectedFolderDrilldown == null) {
                        FoldersSubScreen(
                            folderGroups = folderGroups,
                            onBack = { activeCategory = null },
                            onSelectFolder = { selectedFolderDrilldown = it },
                            onPickFolder = onPickFolder
                        )
                    } else {
                        val folderTracks = folderGroups[selectedFolderDrilldown] ?: emptyList()
                        val folderName = selectedFolderDrilldown!!.substringAfterLast(File.separator).takeIf { it.isNotBlank() } ?: selectedFolderDrilldown!!
                        TrackListDrilldownSubScreen(
                            title = folderName,
                            subtitle = "${folderTracks.size} треков • $selectedFolderDrilldown",
                            tracks = folderTracks,
                            currentTrack = currentTrack,
                            favoriteIds = favoriteIds,
                            playlists = playlists,
                            onBack = { selectedFolderDrilldown = null },
                            onTrackSelect = { tr, q -> onTrackSelect(tr, q ?: folderTracks) },
                            onFavoriteToggle = { pm.toggleFavorite(it) },
                            onAddToPlaylist = { pId, tId -> pm.addTrackToPlaylist(pId, tId) },
                            onHideTrack = { trackId ->
                                prefs.hideTrack(trackId)
                                android.widget.Toast.makeText(context, "Трек скрыт из медиатеки", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                // ── ALBUMS ──────────────────────────────────────────────────
                LibraryCategoryType.ALBUMS -> {
                    AlbumsSubScreen(
                        tracks = visibleTracks,
                        onBack = { activeCategory = null },
                        onAlbumSelect = onAlbumSelect
                    )
                }

                // ── ARTISTS ─────────────────────────────────────────────────
                LibraryCategoryType.ARTISTS -> {
                    ArtistsSubScreen(
                        tracks = visibleTracks,
                        onBack = { activeCategory = null },
                        onArtistSelect = onArtistSelect
                    )
                }

                // ── PLAYLISTS ───────────────────────────────────────────────
                LibraryCategoryType.PLAYLISTS -> {
                    if (showFavoritesDrilldown) {
                        val favTracks = visibleTracks.filter { favoriteIds.contains(it.id) }
                        TrackListDrilldownSubScreen(
                            title = "Избранное",
                            subtitle = "${favTracks.size} любимых треков",
                            tracks = favTracks,
                            currentTrack = currentTrack,
                            favoriteIds = favoriteIds,
                            playlists = playlists,
                            onBack = { showFavoritesDrilldown = false },
                            onTrackSelect = { tr, q -> onTrackSelect(tr, q ?: favTracks) },
                            onFavoriteToggle = { pm.toggleFavorite(it) },
                            onAddToPlaylist = { pId, tId -> pm.addTrackToPlaylist(pId, tId) },
                            onHideTrack = { trackId ->
                                prefs.hideTrack(trackId)
                                android.widget.Toast.makeText(context, "Трек скрыт из медиатеки", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else if (selectedPlaylistDrilldown != null) {
                        val pl = selectedPlaylistDrilldown!!
                        val plTracks = visibleTracks.filter { pl.trackIds.contains(it.id) }
                        TrackListDrilldownSubScreen(
                            title = pl.name,
                            subtitle = "${plTracks.size} треков в плейлисте",
                            tracks = plTracks,
                            currentTrack = currentTrack,
                            favoriteIds = favoriteIds,
                            playlists = playlists,
                            onBack = { selectedPlaylistDrilldown = null },
                            onTrackSelect = { tr, q -> onTrackSelect(tr, q ?: plTracks) },
                            onFavoriteToggle = { pm.toggleFavorite(it) },
                            onAddToPlaylist = { pId, tId -> pm.addTrackToPlaylist(pId, tId) },
                            onHideTrack = { trackId ->
                                prefs.hideTrack(trackId)
                                android.widget.Toast.makeText(context, "Трек скрыт из медиатеки", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        PlaylistsCategorySubScreen(
                            playlists = playlists,
                            favoriteCount = favoriteIds.size,
                            onBack = { activeCategory = null },
                            onOpenFavorites = { showFavoritesDrilldown = true },
                            onOpenPlaylist = { selectedPlaylistDrilldown = it }
                        )
                    }
                }

                // ── HIDDEN TRACKS ───────────────────────────────────────────
                LibraryCategoryType.HIDDEN_TRACKS -> {
                    HiddenTracksSubScreen(
                        hiddenTracks = hiddenTracks,
                        onBack = { activeCategory = null },
                        onUnhideTrack = { trackId ->
                            prefs.unhideTrack(trackId)
                            android.widget.Toast.makeText(context, "Трек возвращен в медиатеку", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onUnhideAll = {
                            prefs.unhideAllTracks()
                            android.widget.Toast.makeText(context, "Все треки возвращены в медиатеку", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MODULAR CATEGORY ROW
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ModularCategoryRow(
    category: LibraryCategoryType,
    countText: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Icon Pill
            Surface(
                shape = CircleShape,
                color = category.accentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = null,
                        tint = category.accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Title & Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = category.subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Count Badge
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = countText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SUB-SCREEN: ALL TRACKS
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllTracksSubScreen(
    tracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    favoriteIds: Set<String>,
    playlists: List<Playlist>,
    onBack: () -> Unit,
    onTrackSelect: (AudioFileMetadata, List<AudioFileMetadata>?) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
    onHideTrack: (String) -> Unit,
    onOpenSearch: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val isCompactMode by prefs.isCompactMode.collectAsState()
    val isShowTrackNumbers by prefs.isShowTrackNumbers.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedSortMode by remember { mutableStateOf(SortMode.TITLE_AZ) }
    var showSortDropdown by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var activeScrubLetter by remember { mutableStateOf<String?>(null) }
    val alphabetList = remember {
        listOf("^", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#")
    }

    val filteredTracks = remember(tracks, searchQuery, selectedSortMode) {
        val list = tracks.filter { track ->
            track.title.contains(searchQuery, ignoreCase = true) ||
            track.artist.contains(searchQuery, ignoreCase = true) ||
            track.album.contains(searchQuery, ignoreCase = true)
        }
        when (selectedSortMode) {
            SortMode.TITLE_AZ -> list.sortedBy { it.title.lowercase() }
            SortMode.ARTIST_AZ -> list.sortedBy { it.artist.lowercase() }
            SortMode.QUALITY -> list.sortedByDescending { it.audioQualityType.ordinal }
            SortMode.DURATION -> list.sortedByDescending { it.durationMs }
        }
    }

    val scrollToLetter: (String) -> Unit = { letter ->
        if (letter == "^") {
            coroutineScope.launch { listState.scrollToItem(0) }
        } else {
            val targetIndex = filteredTracks.indexOfFirst { track ->
                val firstChar = track.title.trim().take(1).uppercase()
                if (letter == "#") (firstChar.firstOrNull()?.isLetter() != true) else firstChar == letter
            }
            if (targetIndex != -1) {
                coroutineScope.launch { listState.scrollToItem(targetIndex) }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            title = {
                Column {
                    Text("Все треки", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${filteredTracks.size} треков", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            },
            actions = {
                IconButton(onClick = {
                    if (filteredTracks.isNotEmpty()) {
                        val shuffled = filteredTracks.shuffled(kotlin.random.Random(System.currentTimeMillis()))
                        com.antigravity.audioplayer.service.AudioPlayerService.setShuffle(true)
                        onTrackSelect(shuffled.first(), shuffled)
                    }
                }) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Перемешать")
                }
                IconButton(onClick = { if (filteredTracks.isNotEmpty()) onTrackSelect(filteredTracks.first(), filteredTracks) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Играть всё", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Поиск")
                }
                Box {
                    IconButton(onClick = { showSortDropdown = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Сортировка")
                    }
                    DropdownMenu(expanded = showSortDropdown, onDismissRequest = { showSortDropdown = false }) {
                        SortMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label, fontWeight = if (selectedSortMode == mode) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { selectedSortMode = mode; showSortDropdown = false },
                                leadingIcon = { if (selectedSortMode == mode) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            )
                        }
                    }
                }
            }
        )

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 26.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (isCompactMode) 3.dp else 6.dp)
            ) {
                itemsIndexed(filteredTracks, key = { _, it -> it.id }) { index, track ->
                    val isPlayingCurrent = currentTrack?.id == track.id
                    TrackListItem(
                        track = track,
                        isSelected = isPlayingCurrent,
                        isFavorite = favoriteIds.contains(track.id),
                        playlists = playlists,
                        showTrackNumber = isShowTrackNumbers,
                        compactMode = isCompactMode,
                        displayIndex = index + 1,
                        onFavoriteToggle = { onFavoriteToggle(track.id) },
                        onAddToPlaylist = { pId -> onAddToPlaylist(pId, track.id) },
                        onHideTrack = { onHideTrack(track.id) },
                        onClick = { onTrackSelect(track, filteredTracks) }
                    )
                }
            }

            // TouchBar A-Z Indexer (strictly bounded above bottom bars with 1:1 proportional touch mapping)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = 6.dp, bottom = 18.dp)
                    .width(22.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(vertical = 4.dp)
                    .pointerInput(alphabetList) {
                        fun selectLetterAtY(y: Float) {
                            val h = size.height.toFloat()
                            if (h > 0f) {
                                val fraction = (y / h).coerceIn(0f, 0.9999f)
                                val index = (fraction * alphabetList.size).toInt().coerceIn(0, alphabetList.lastIndex)
                                val letter = alphabetList[index]
                                if (activeScrubLetter != letter) {
                                    activeScrubLetter = letter
                                    scrollToLetter(letter)
                                }
                            }
                        }
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                selectLetterAtY(offset.y)
                            },
                            onDragEnd = { activeScrubLetter = null },
                            onDragCancel = { activeScrubLetter = null },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                selectLetterAtY(change.position.y)
                            }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                alphabetList.forEach { letter ->
                    val isHovered = activeScrubLetter == letter
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = letter,
                            fontSize = if (isHovered) 10.sp else 8.sp,
                            lineHeight = 8.sp,
                            fontWeight = if (isHovered) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isHovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            style = LocalTextStyle.current.copy(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
            }

            if (activeScrubLetter != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(80.dp),
                        shadowElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(activeScrubLetter!!, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SUB-SCREEN: FOLDERS
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldersSubScreen(
    folderGroups: Map<String, List<AudioFileMetadata>>,
    onBack: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onPickFolder: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            title = {
                Column {
                    Text("Папки с музыкой", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${folderGroups.size} директорий", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            },
            actions = {
                IconButton(onClick = onPickFolder) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Добавить папку")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(folderGroups.entries.toList()) { entry ->
                val folderPath = entry.key
                val fTracks = entry.value
                val folderName = folderPath.substringAfterLast(File.separator).takeIf { it.isNotBlank() } ?: folderPath

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectFolder(folderPath) },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFFFB703).copy(alpha = 0.15f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFB703), modifier = Modifier.size(22.dp))
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = folderName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = folderPath,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${fTracks.size} тр.",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }

                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SUB-SCREEN: ALBUMS GRID
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsSubScreen(
    tracks: List<AudioFileMetadata>,
    onBack: () -> Unit,
    onAlbumSelect: (String, List<AudioFileMetadata>) -> Unit
) {
    val albumsList = remember(tracks) { tracks.groupBy { it.album }.entries.toList() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            title = {
                Column {
                    Text("Альбомы", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${albumsList.size} альбомов", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(albumsList) { entry ->
                AlbumGridItem(
                    albumName = entry.key,
                    tracks = entry.value,
                    onClick = { onAlbumSelect(entry.key, entry.value) }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SUB-SCREEN: ARTISTS LIST
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistsSubScreen(
    tracks: List<AudioFileMetadata>,
    onBack: () -> Unit,
    onArtistSelect: (String, List<AudioFileMetadata>) -> Unit
) {
    val artistsList = remember(tracks) { tracks.groupBy { it.artist }.entries.toList() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            title = {
                Column {
                    Text("Исполнители", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${artistsList.size} исполнителей", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(artistsList) { entry ->
                ArtistListItem(
                    artistName = entry.key,
                    tracks = entry.value,
                    onClick = { onArtistSelect(entry.key, entry.value) }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SUB-SCREEN: PLAYLISTS
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsCategorySubScreen(
    playlists: List<Playlist>,
    favoriteCount: Int,
    onBack: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            title = {
                Column {
                    Text("Плейлисты", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${playlists.size + 1} списков", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Favorites Row
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenFavorites),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFE44D7B).copy(alpha = 0.2f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFE44D7B), modifier = Modifier.size(22.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Избранное", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("$favoriteCount треков", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                }
            }

            // User Playlists
            items(playlists) { pl ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenPlaylist(pl) },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("${pl.trackIds.size} треков", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GENERIC TRACK LIST DRILLDOWN SUB-SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackListDrilldownSubScreen(
    title: String,
    subtitle: String,
    tracks: List<AudioFileMetadata>,
    currentTrack: AudioFileMetadata?,
    favoriteIds: Set<String>,
    playlists: List<Playlist>,
    onBack: () -> Unit,
    onTrackSelect: (AudioFileMetadata, List<AudioFileMetadata>?) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
    onHideTrack: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val isCompactMode by prefs.isCompactMode.collectAsState()
    val isShowTrackNumbers by prefs.isShowTrackNumbers.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            title = {
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            actions = {
                IconButton(onClick = {
                    if (tracks.isNotEmpty()) {
                        val shuffled = tracks.shuffled(kotlin.random.Random(System.currentTimeMillis()))
                        com.antigravity.audioplayer.service.AudioPlayerService.setShuffle(true)
                        onTrackSelect(shuffled.first(), shuffled)
                    }
                }) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Перемешать")
                }
                IconButton(onClick = { if (tracks.isNotEmpty()) onTrackSelect(tracks.first(), tracks) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Играть всё", tint = MaterialTheme.colorScheme.primary)
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (isCompactMode) 3.dp else 6.dp)
        ) {
            itemsIndexed(tracks, key = { _, it -> it.id }) { index, track ->
                val isPlayingCurrent = currentTrack?.id == track.id
                TrackListItem(
                    track = track,
                    isSelected = isPlayingCurrent,
                    isFavorite = favoriteIds.contains(track.id),
                    playlists = playlists,
                    showTrackNumber = isShowTrackNumbers,
                    compactMode = isCompactMode,
                    displayIndex = index + 1,
                    onFavoriteToggle = { onFavoriteToggle(track.id) },
                    onAddToPlaylist = { pId -> onAddToPlaylist(pId, track.id) },
                    onHideTrack = { onHideTrack(track.id) },
                    onClick = { onTrackSelect(track, tracks) }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REUSABLE TRACK, ALBUM & ARTIST ITEMS
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TrackListItem(
    track: AudioFileMetadata,
    isSelected: Boolean,
    isFavorite: Boolean,
    playlists: List<Playlist>,
    showTrackNumber: Boolean = false,
    compactMode: Boolean = false,
    displayIndex: Int? = null,
    onFavoriteToggle: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onHideTrack: () -> Unit = {},
    onClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val trackNum = if (track.trackNumber > 0) track.trackNumber else displayIndex

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(if (compactMode) 10.dp else 14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = if (compactMode) 3.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compactMode) 8.dp else 10.dp)
        ) {
            if (showTrackNumber && trackNum != null && trackNum > 0) {
                Text(
                    text = String.format("%02d", trackNum),
                    fontSize = if (compactMode) 11.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.width(if (compactMode) 22.dp else 26.dp)
                )
            }

            AlbumArtView(
                track = track,
                modifier = Modifier.size(if (compactMode) 34.dp else 46.dp),
                cornerRadius = if (compactMode) 7 else 10
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compactMode) 13.sp else 14.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!compactMode) {
                    Spacer(modifier = Modifier.height(1.dp))
                }
                Text(
                    text = "${track.artist} • ${track.album}",
                    fontSize = if (compactMode) 10.sp else 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(if (compactMode) 1.dp else 3.dp)) {
                QualityBadge(metadata = track)
                Text(formatMs(track.durationMs), fontSize = if (compactMode) 9.sp else 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }

            // Favorite + context menu
            @Composable
            fun actionButtons() {
                val btnSize = if (compactMode) 28.dp else 30.dp
                val iconSize = if (compactMode) 15.dp else 16.dp
                IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(btnSize)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Избранное",
                        tint = if (isFavorite) Color(0xFFE44D7B) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(iconSize)
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(btnSize)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Ещё", modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Скрыть трек", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onHideTrack()
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        if (playlists.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Создайте плейлист во вкладке Избранное", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) },
                                onClick = { showMenu = false }
                            )
                        } else {
                            Text(
                                "  Добавить в плейлист:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            playlists.forEach { pl ->
                                DropdownMenuItem(
                                    text = { Text(pl.name) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = { onAddToPlaylist(pl.id); showMenu = false }
                                )
                            }
                        }
                    }
                }
            }

            if (compactMode) {
                Row(verticalAlignment = Alignment.CenterVertically) { actionButtons() }
            } else {
                Column { actionButtons() }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SUB-SCREEN: HIDDEN TRACKS
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenTracksSubScreen(
    hiddenTracks: List<AudioFileMetadata>,
    onBack: () -> Unit,
    onUnhideTrack: (String) -> Unit,
    onUnhideAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            title = {
                Column {
                    Text("Скрытые треки", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${hiddenTracks.size} скрытых песен", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            },
            actions = {
                if (hiddenTracks.isNotEmpty()) {
                    TextButton(onClick = onUnhideAll) {
                        Text("Вернуть все", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        )

        if (hiddenTracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Нет скрытых треков",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Скрывайте ненужные треки через контекстное меню (3 точки) у любой песни в библиотеке.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp, top = 4.dp)
            ) {
                items(hiddenTracks, key = { "hidden_${it.id}" }) { track ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AlbumArtView(track = track, modifier = Modifier.size(44.dp), cornerRadius = 10)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${track.artist} • ${track.album}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { onUnhideTrack(track.id) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Показать", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGridItem(
    albumName: String,
    tracks: List<AudioFileMetadata>,
    onClick: () -> Unit
) {
    val sampleTrack = tracks.firstOrNull()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            AlbumArtView(
                track = sampleTrack,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                cornerRadius = 10
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = albumName,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${tracks.size} треков",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ArtistListItem(
    artistName: String,
    tracks: List<AudioFileMetadata>,
    onClick: () -> Unit
) {
    val sampleTrack = tracks.firstOrNull()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AlbumArtView(
                track = sampleTrack,
                modifier = Modifier.size(44.dp),
                cornerRadius = 22
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artistName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${tracks.size} Песен",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
