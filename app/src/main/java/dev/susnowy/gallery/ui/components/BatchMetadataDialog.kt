package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.Modifier
import dev.susnowy.gallery.model.*
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * Shared editor: selection and field baselines stay fixed while the dialog is open.
 */
@Composable
fun BatchMetadataDialog(
    items: List<MediaItem>,
    onDismiss: () -> Unit,
    onSave: (List<MediaItem>, BatchMetadataEdit) -> Unit,
) {
    val baseline = remember { items.toList() }
    var authors by remember { mutableStateOf(BatchListEdit()) }
    var tags by remember { mutableStateOf(BatchListEdit()) }
    var collections by remember { mutableStateOf(BatchListEdit()) }
    var domain by remember { mutableStateOf<MediaDomain?>(null) }
    var domainMenu by remember { mutableStateOf(false) }
    val edit = BatchMetadataEdit(authors, tags, collections, domain)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量编辑 ${baseline.size} 项") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${baseline.map { it.libraryId }.distinct().size} 个库分别保存。未选择的字段保持原值；并发冲突的作品跳过并报告。")
                BatchListField("作者", authors) { authors = it }
                BatchListField("Tag", tags) { tags = it }
                BatchListField("Collection", collections) { collections = it }
                TextButton(onClick = { domainMenu = true }) { Text("归属：${domain?.batchLabel() ?: "保持"}") }
                DropdownMenu(expanded = domainMenu, onDismissRequest = { domainMenu = false }) {
                    DropdownMenuItem(text = { Text("保持") }, onClick = { domain = null; domainMenu = false })
                    MediaDomain.entries.forEach { value ->
                        DropdownMenuItem(text = { Text(value.batchLabel()) }, onClick = { domain = value; domainMenu = false })
                    }
                }
                if (listOf(authors, tags, collections).any { it.mode == BatchListMode.CLEAR }) {
                    Text("清空会删除所选字段的全部值，并记录为人工决定。")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = edit.active && baseline.isNotEmpty(),
                onClick = { onSave(baseline, edit) },
            ) { Text("应用到所选") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BatchListField(label: String, edit: BatchListEdit, onChange: (BatchListEdit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column {
        TextButton(onClick = { expanded = true }) { Text("$label：${edit.mode.batchLabel()}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BatchListMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(mode.batchLabel()) }, onClick = { onChange(edit.copy(mode = mode)); expanded = false })
            }
        }
        if (edit.mode == BatchListMode.APPEND || edit.mode == BatchListMode.REPLACE) {
            OutlinedTextField(value = text, onValueChange = {
                text = it
                onChange(edit.copy(values = it.split(',', '，', ';', '；')))
            }, label = { Text("$label（逗号分隔）") })
        }
    }
}

private fun BatchListMode.batchLabel() = when (this) {
    BatchListMode.KEEP -> "保持"
    BatchListMode.APPEND -> "追加"
    BatchListMode.REPLACE -> "替换"
    BatchListMode.CLEAR -> "清空"
}
private fun MediaDomain.batchLabel() = when (this) {
    MediaDomain.ALBUM -> "相册"
    MediaDomain.CLASSIFIED -> "图片 / 视频"
    MediaDomain.WORKS -> "漫画 / 阅读"
}
