package com.antigravity.audioplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerampMenuSheet(
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenAudioChain: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Poweramp Logo Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "VIBE PLAYER HI-RES",
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // Quick Settings Pill Grid (Screenshot 5)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = onOpenAudioChain,
                    label = { Text("Гарнитура/Bluetooth", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
                SuggestionChip(
                    onClick = onOpenAudioChain,
                    label = { Text("AAudio выход", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = onOpenLibrary,
                    label = { Text("Библиотека", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
                SuggestionChip(
                    onClick = onOpenSettings,
                    label = { Text("Настройки", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            // Menu Items List
            MenuItemRow(icon = Icons.Default.Settings, title = "Настройки звука и вывода", onClick = onOpenSettings)
            MenuItemRow(icon = Icons.Default.Bluetooth, title = "Bluetooth / LDAC параметры", onClick = onOpenAudioChain)
            MenuItemRow(icon = Icons.Default.AccountTree, title = "Тракт обработки звука (Audio Chain)", onClick = onOpenAudioChain)
            MenuItemRow(icon = Icons.Default.HelpOutline, title = "Справка и помощь", onClick = onDismiss)
        }
    }
}

@Composable
private fun MenuItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
