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
 * A "hide from the system gallery" switch existed here and was removed. It wrote `.nomedia` and
 * asked the media scanner to re-read the Library, and neither step works on HyperOS 3 (Android 16):
 * a freshly created `.nomedia` directory is still indexed, existing rows are never removed, and
 * the Xiaomi gallery aggregates media from its own scan regardless. Hiding an album there is a
 * Xiaomi feature reached from the gallery itself, not something an app can request through the
 * standard APIs.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    onOpenLog: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
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
