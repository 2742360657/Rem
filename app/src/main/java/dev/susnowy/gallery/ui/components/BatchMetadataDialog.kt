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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * Append-only batch metadata editor, shared by every selection toolbar.
 *
 * Values are appended to what each item already has; nothing here overwrites an existing
 * field, and the caller decides which items are affected.
 */
@Composable
fun BatchMetadataDialog(
    count: Int,
    onDismiss: () -> Unit,
    onSave: (authors: String, tags: String, collections: String) -> Unit,
) {
    var authors by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var collections by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量编辑 $count 项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入内容会追加到现有元数据，不会覆盖每项已有的值。")
                OutlinedTextField(
                    value = authors,
                    onValueChange = { authors = it },
                    label = { Text("添加作者（逗号分隔）") },
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("添加 Tag（逗号分隔）") },
                )
                OutlinedTextField(
                    value = collections,
                    onValueChange = { collections = it },
                    label = { Text("添加 Collection（逗号分隔）") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = authors.isNotBlank() || tags.isNotBlank() || collections.isNotBlank(),
                onClick = { onSave(authors, tags, collections) },
            ) { Text("追加到所选") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
