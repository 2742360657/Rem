package dev.susnowy.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The settings tab.
 *
 * Deliberately short. Rem has no preferences of its own, so this is a way into the log plus the
 * facts about the attached Library, which are more useful on screen than buried in a log file.
 *
 * The switch owns only the Library root `.nomedia` marker. It does not delete existing media rows
 * from MediaStore, and the system gallery may keep already-indexed items visible until it rescans.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    onHideFromSystemGallery: (Boolean) -> Unit,
    onOpenLog: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingSwitch(
            title = "从系统相册隐藏",
            description = if (state.attached) {
                "创建或移除 Library/.nomedia。只影响后续媒体索引，不会删除原始文件或强制清除已有系统相册记录。"
            } else {
                "尚未接入 Library。接入后可用，届时在 Library 根目录创建或移除 .nomedia。"
            },
            checked = state.hideFromSystemGallery,
            // With no Library there is nowhere to write, and a switch that silently does nothing
            // is worse than one that plainly cannot be moved.
            enabled = state.attached && !state.markerBusy,
            onCheckedChange = onHideFromSystemGallery,
        )
        HorizontalDivider()

        SettingRow(
            title = "运行日志",
            description = "记录接入、扫描和打开文件的过程，可分享出来排查问题",
            onClick = onOpenLog,
        )
        HorizontalDivider()

        Spacer(Modifier.height(8.dp))
        Text(
            text = "当前 Library",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ReadOnlyRow("名称", state.libraryName.ifEmpty { "未接入" })
        ReadOnlyRow("目录", state.treeUri?.lastPathSegment?.replace("%3A", "：") ?: "—")
        ReadOnlyRow("媒体", "${state.album.size} 个相册文件 · ${state.projects.size} 个项目")

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Rem 0.0.4 · 只读取 Library，不改动任何媒体文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingRow(title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
