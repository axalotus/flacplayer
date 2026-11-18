package com.zuna.player.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.ui.components.*
import com.zuna.player.ui.theme.LocalFlacTheme

@Composable
fun TrackCollectionDetailView(
    title: String,
    subtitle: String?,
    tracks: List<AudioFileMetadata>,
    heroTrack: AudioFileMetadata?,
    currentTrack: AudioFileMetadata?,
    onBack: () -> Unit,
    onPlayTrack: (AudioFileMetadata, List<AudioFileMetadata>) -> Unit,
    onOpenTrackMenu: (AudioFileMetadata) -> Unit,
    bottomInsetPadding: Dp
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val showQualityBadges by prefs.showQualityBadges.collectAsState()
    val compactMode by prefs.isCompactMode.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = bottomInsetPadding + 20.dp
        ),
        verticalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 6.dp)
    ) {
        item(key = "detail_top") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FlacIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Назад",
                    onClick = onBack,
                    size = 38.dp,
                    backgroundColor = tokens.cardBg,
                    borderColor = tokens.line
                )

                FlacGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val panelTokens = LocalFlacTheme.current
                        if (heroTrack != null) {
                            TrackArtwork(
                                track = heroTrack,
                                modifier = Modifier.size(92.dp),
                                cornerRadius = panelTokens.radiusSm,
                                iconSize = 32.dp
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = title,
                                color = panelTokens.fg0,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    color = panelTokens.fg2,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${tracks.size} треков",
                                color = panelTokens.fg2,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (tracks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlacPillButton(
                            text = "Слушать",
                            icon = Icons.Rounded.PlayArrow,
                            accentFill = true,
                            onClick = { onPlayTrack(tracks.first(), tracks) }
                        )
                        FlacPillButton(
                            text = "Перемешать",
                            icon = Icons.Rounded.Shuffle,
                            onClick = {
                                val shuffled = tracks.shuffled()
                                onPlayTrack(shuffled.first(), shuffled)
                            }
                        )
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            item(key = "detail_empty") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Список пуст", color = tokens.fg2, fontSize = 14.sp)
                }
            }
        } else {
            itemsIndexed(tracks, key = { idx, t -> "${idx}_${t.id}" }) { idx, track ->
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrack?.id,
                        onClick = { onPlayTrack(track, tracks) },
                        onMoreClick = { onOpenTrackMenu(track) },
                        indexNumber = idx + 1,
                        showQualityBadge = showQualityBadges,
                        compact = compactMode
                    )
                }
            }
        }
    }
}
