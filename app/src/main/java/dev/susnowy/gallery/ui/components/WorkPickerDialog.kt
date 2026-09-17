package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.susnowy.gallery.model.MediaItem

/**
 * Multi-select picker over the Library's Works, shared by the Group and Series editors.
 *
 * It only collects a selection: the caller decides what a confirmed pick means, and every
 * caller commits it as one explicit portable write.
 */
@Composable
fun WorkPickerDialog(
    candidates: List<MediaItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    title: String = "添加成员",
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptyList<String>()) }
    val shown = remember(candidates, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            candidates
        } else {
            candidates.filter { item ->
                item.displayTitle.contains(trimmed, ignoreCase = true) ||
                    item.relativePath.contains(trimmed, ignoreCase = true) ||
                    item.authors.any { it.contains(trimmed, ignoreCase = true) }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索标题、路径或作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (shown.isEmpty()) {
                        "没有匹配的作品"
                    } else {
                        "显示 ${shown.size} 项，已选 ${picked.size} 项；确认后仍需点“保存”才会写入 Library。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(shown, key = { _, item -> item.id }) { _, item ->
                        ListItem(
                            headlineContent = {
                                Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    item.relativePath,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = item.id in picked,
                                    onCheckedChange = { checked ->
                                        picked = if (checked) picked + item.id else picked - item.id
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                picked = if (item.id in picked) picked - item.id else picked + item.id
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = { onConfirm(picked) },
            ) { Text("选择 ${picked.size} 项") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
