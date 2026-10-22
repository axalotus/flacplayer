package com.antigravity.audioplayer.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.desktop.audio.DesktopAudioPlayer
import com.antigravity.audioplayer.desktop.lyrics.DesktopLyrics
import com.antigravity.audioplayer.desktop.model.LyricsData
import com.antigravity.audioplayer.desktop.repository.DesktopLibraryRepository
import com.antigravity.audioplayer.desktop.theme.AccentPrimary
import com.antigravity.audioplayer.desktop.theme.ObsidianSurfaceVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopLyricsScreen(
    modifier: Modifier = Modifier
) {
    val currentTrack by DesktopAudioPlayer.currentTrack.collectAsState()
    val progressMs by DesktopAudioPlayer.progressMs.collectAsState()
    val isPlaying by DesktopAudioPlayer.isPlaying.collectAsState()
    val customLyricsDirStr by DesktopLibraryRepository.customLyricsDir.collectAsState()

    var lyricsData by remember { mutableStateOf<LyricsData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Reload lyrics when track changes
    LaunchedEffect(currentTrack?.id) {
        val track = currentTrack
        if (track != null) {
            isLoading = true
            val dir = customLyricsDirStr?.let { File(it) }
            lyricsData = DesktopLyrics.getLyrics(track, dir)
            isLoading = false
        } else {
            lyricsData = null
            isLoading = false
        }
    }

    // Active line calculation for synced LRC
    val lines = lyricsData?.lines ?: emptyList()
    val isSynced = lyricsData?.isSynced == true && lines.isNotEmpty()

    val activeIndex = remember(progressMs, lines) {
        if (!isSynced) -1
        else {
            var index = -1
            for (i in lines.indices) {
                if (progressMs >= lines[i].timestampMs) {
                    index = i
                } else {
                    break
                }
            }
            index
        }
    }

    // Auto-scroll to active index
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && isSynced && !listState.isScrollInProgress) {
            scope.launch {
                val target = (activeIndex - 3).coerceAtLeast(0)
                listState.animateScrollToItem(target)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp)
    ) {
        // ── TOP HEADER ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Текст песни (Lyrics)",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (currentTrack != null) "${currentTrack?.artist} — ${currentTrack?.title}" else "Трек не выбран",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                lyricsData?.let { data ->
                    Surface(
                        color = ObsidianSurfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${if (data.isSynced) "Караоке LRC" else "Обычный текст"} • ${data.source}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        val chooser = JFileChooser()
                        chooser.dialogTitle = "Выберите файл .lrc или .txt"
                        val result = chooser.showOpenDialog(null)
                        if (result == JFileChooser.APPROVE_OPTION) {
                            val f = chooser.selectedFile
                            val text = DesktopLyrics.decodeText(f.readBytes())
                            lyricsData = DesktopLyrics.parse(text, "Вручную выбран: ${f.name}")
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Выбрать файл .lrc / .txt")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── CONTENT ──────────────────────────────────────────────────────────
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentPrimary)
                }
            }

            lyricsData == null || (lines.isEmpty() && lyricsData?.plainText.isNullOrBlank()) -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Lyrics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (currentTrack == null) "Выберите трек" else "Текст не найден",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Положите файл .lrc рядом с треком или выберите его вручную",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            isSynced -> {
                // Synced Karaoke List
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(lines) { index, line ->
                        val isActive = index == activeIndex
                        val isPassed = index < activeIndex

                        val textColor by animateColorAsState(
                            targetValue = when {
                                isActive -> Color.White
                                isPassed -> Color.White.copy(alpha = 0.5f)
                                else -> Color.White.copy(alpha = 0.3f)
                            },
                            animationSpec = spring(),
                            label = "textColor"
                        )

                        val scale by animateFloatAsState(
                            targetValue = if (isActive) 1.08f else 1.0f,
                            animationSpec = spring(),
                            label = "scale"
                        )

                        Text(
                            text = line.text.ifBlank { "• • •" },
                            fontSize = if (isActive) 24.sp else 18.sp,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                            color = textColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(scale)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    DesktopAudioPlayer.seekTo(line.timestampMs)
                                }
                                .padding(vertical = 8.dp, horizontal = 24.dp)
                        )
                    }
                }
            }

            else -> {
                // Plain Text
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            text = line.text,
                            fontSize = 18.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        }
    }
}
