package com.antigravity.audioplayer.desktop.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.desktop.sync.DesktopSyncServer
import com.antigravity.audioplayer.desktop.theme.AccentOrange
import com.antigravity.audioplayer.desktop.theme.AccentPrimary
import com.antigravity.audioplayer.desktop.theme.ObsidianSurfaceVariant

@Composable
fun DesktopSyncScreen(
    modifier: Modifier = Modifier
) {
    val isRunning by DesktopSyncServer.isServerRunning.collectAsState()
    val serverIp by DesktopSyncServer.serverIp.collectAsState()
    val serverPort by DesktopSyncServer.serverPort.collectAsState()
    val pin by DesktopSyncServer.pairingPin.collectAsState()
    val syncMessage by DesktopSyncServer.lastSyncMessage.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp)
    ) {
        // ── HEADER ───────────────────────────────────────────────────────────
        Text(
            text = "Wi-Fi Синхронизация с телефоном",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Прямой обмен плейлистами, избранным, эквалайзером и треками по локальной сети",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── SERVER CARD ──────────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ObsidianSurfaceVariant,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = if (isRunning) AccentPrimary.copy(alpha = 0.2f) else Color.Red.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                if (isRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = if (isRunning) AccentPrimary else Color.Red,
                                modifier = Modifier.padding(8.dp).size(28.dp)
                            )
                        }

                        Column {
                            Text(
                                text = if (isRunning) "Сервер синхронизации активен" else "Сервер остановлен",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning) AccentPrimary else Color.Red
                            )
                            Text(
                                text = if (isRunning) "http://$serverIp:$serverPort" else "Нажмите «Запустить», чтобы подключить телефон",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (isRunning) DesktopSyncServer.stopServer() else DesktopSyncServer.startServer()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Color(0xFF333333) else AccentPrimary,
                            contentColor = if (isRunning) Color.White else Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRunning) "Остановить" else "Запустить сервер")
                    }
                }

                if (isRunning) {
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ПИН-код для сопряжения на телефоне:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = pin,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentPrimary,
                                letterSpacing = 8.sp
                            )
                        }

                        OutlinedButton(
                            onClick = { DesktopSyncServer.regeneratePin() },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Новый PIN")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── INSTRUCTIONS & CAPABILITIES ──────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1
            Surface(
                modifier = Modifier.weight(1f),
                color = ObsidianSurfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = AccentPrimary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Что синхронизируется:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("• Плейлисты и очередь", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Список «Избранные треки»", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Пользовательские пресеты эквалайзера", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Позиция воспроизведения (Handoff)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Card 2
            Surface(
                modifier = Modifier.weight(1f),
                color = ObsidianSurfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Icon(Icons.Default.Phonelink, contentDescription = null, tint = AccentOrange)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Как подключить телефон:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("1. Убедитесь, что ПК и телефон в одной Wi-Fi сети", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2. Откройте FlacPlayer на телефоне ➔ Настройки ➔ Синхронизация", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("3. Введите IP ($serverIp) и 4-значный ПИН ($pin)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── STATUS LOG ───────────────────────────────────────────────────────
        syncMessage?.let { msg ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                    Text(text = msg, fontSize = 13.sp, color = AccentPrimary)
                }
            }
        }
    }
}
