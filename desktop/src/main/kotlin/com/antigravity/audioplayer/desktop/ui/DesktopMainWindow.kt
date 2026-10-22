package com.antigravity.audioplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.desktop.repository.DesktopLibraryRepository
import com.antigravity.audioplayer.desktop.theme.AccentPrimary
import com.antigravity.audioplayer.desktop.theme.ObsidianBackground
import com.antigravity.audioplayer.desktop.theme.ObsidianSurface
import com.antigravity.audioplayer.desktop.ui.components.DesktopPlayerBar
import com.antigravity.audioplayer.desktop.ui.screens.DesktopEqualizerScreen
import com.antigravity.audioplayer.desktop.ui.screens.DesktopLibraryScreen
import com.antigravity.audioplayer.desktop.ui.screens.DesktopLyricsScreen
import com.antigravity.audioplayer.desktop.ui.screens.DesktopSyncScreen

enum class DesktopNavTab(val title: String, val icon: ImageVector) {
    LIBRARY("Медиатека", Icons.Default.LibraryMusic),
    LYRICS("Тексты песен", Icons.Default.Lyrics),
    EQUALIZER("Эквалайзер", Icons.Default.Equalizer),
    SYNC("Синхронизация", Icons.Default.Sync)
}

@Composable
fun DesktopMainWindow() {
    var activeTab by remember { mutableStateOf(DesktopNavTab.LIBRARY) }
    val folders by DesktopLibraryRepository.folders.collectAsState()

    Scaffold(
        bottomBar = {
            DesktopPlayerBar(
                onOpenLyrics = { activeTab = DesktopNavTab.LYRICS },
                onOpenEqualizer = { activeTab = DesktopNavTab.EQUALIZER }
            )
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(ObsidianBackground)
        ) {
            // ── SIDEBAR NAVIGATION ───────────────────────────────────────────
            Surface(
                modifier = Modifier.width(240.dp).fillMaxHeight(),
                color = ObsidianSurface
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        // Brand Logo & Title
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = AccentPrimary,
                                    modifier = Modifier.padding(6.dp).size(22.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = "FlacPlayer PC",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Hi-Res Audio Engine",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AccentPrimary
                                )
                            }
                        }

                        // Navigation Tabs
                        DesktopNavTab.values().forEach { tab ->
                            val isSelected = activeTab == tab
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { activeTab = tab },
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        tab.icon,
                                        contentDescription = tab.title,
                                        tint = if (isSelected) AccentPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = tab.title,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AccentPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Folders list in sidebar
                        Text(
                            text = "ПАПКИ С МУЗЫКОЙ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        folders.forEach { folderPath ->
                            val folderName = folderPath.substringAfterLast(java.io.File.separator)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = folderName,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }

                                IconButton(
                                    onClick = { DesktopLibraryRepository.removeFolder(folderPath) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Bottom info in sidebar
                    Text(
                        text = "FlacPlayer Desktop v1.00\nFLAC • MP3 • WAV • DSP",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // ── MAIN CONTENT AREA ────────────────────────────────────────────
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                when (activeTab) {
                    DesktopNavTab.LIBRARY -> DesktopLibraryScreen()
                    DesktopNavTab.LYRICS -> DesktopLyricsScreen()
                    DesktopNavTab.EQUALIZER -> DesktopEqualizerScreen()
                    DesktopNavTab.SYNC -> DesktopSyncScreen()
                }
            }
        }
    }
}
