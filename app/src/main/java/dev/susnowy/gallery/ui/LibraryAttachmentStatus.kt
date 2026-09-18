package dev.susnowy.gallery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class LibraryAttachmentState(val progress: String? = null, val error: String? = null)

/** Remains visible after returning from the picker, including on the empty-library screen. */
@Composable
fun LibraryAttachmentStatus(state: LibraryAttachmentState, onDismiss: () -> Unit, onChooseAgain: () -> Unit) {
    if (state.progress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在接入媒体库") },
            text = {
                Column {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(state.progress)
                }
            },
            confirmButton = {},
        )
    } else if (state.error != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("接入媒体库失败") },
            text = { Text(state.error) },
            confirmButton = { TextButton(onClick = onChooseAgain) { Text("重新选择目录") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
    }
}
