package dev.susnowy.gallery.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import dev.susnowy.gallery.model.*

@Composable
fun RelationRemovalDialog(
    items: List<MediaItem>,
    groups: List<MediaGroup>,
    series: List<MediaSeries>,
    onDismiss: () -> Unit,
    onConfirm: (RelationRemoval) -> Unit,
) {
    // Bind both selection and target revision before showing the confirmation.
    val selectionKey = items.map { it.libraryId to it.id }.toSet()
    val choices = remember(selectionKey) {
        groups.mapNotNull { group ->
            val ids = items.filter { it.libraryId == group.libraryId && it.id in group.memberIds }.map { it.id }.toSet()
            ids.takeIf { it.isNotEmpty() }?.let { RelationRemoval(group.libraryId, group.id, group.title, false, group.revision, it) }
        } + series.mapNotNull { sequence ->
            val ids = items.filter { it.libraryId == sequence.libraryId && it.id in sequence.memberIds }.map { it.id }.toSet()
            ids.takeIf { it.isNotEmpty() }?.let { RelationRemoval(sequence.libraryId, sequence.id, sequence.title, true, sequence.revision, it) }
        }
    }
    var target by remember(selectionKey) { mutableStateOf<RelationRemoval?>(null) }
    val selected = target
    if (selected == null) {
        TargetPickerDialog(
            title = "选择要移出的关系",
            emptyText = "所选作品没有可移出的分组或系列",
            options = choices.mapIndexed { index, choice ->
                TargetOption(index.toString(), choice.title, "${if (choice.isSeries) "系列" else "分组"} · 移出 ${choice.workIds.size} 项")
            },
            onDismiss = onDismiss,
            onPick = { target = choices[it.id.toInt()] },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("从 ${selected.title} 移出 ${selected.workIds.size} 项？") },
            text = { Text("只解除这一个${if (selected.isSeries) "系列" else "分组"}的成员关系。作品、文件和其他关系保留；移空后保留空关系。") },
            confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("移出") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}
