package dev.susnowy.gallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaSeries
import dev.susnowy.gallery.ui.GalleryViewModel
import dev.susnowy.gallery.ui.components.MediaThumbnail
import dev.susnowy.gallery.ui.components.REORDER_ROW_HEIGHT
import dev.susnowy.gallery.ui.components.ReorderableRow
import dev.susnowy.gallery.ui.components.rememberDragReorderState
import dev.susnowy.gallery.ui.components.WorkPickerDialog

/**
 * Series editor: rename, batch add/remove members and arrange reading order.
 *
 * The reading order is the member list order, and one explicit save writes the whole series
 * (order, membership, title) plus `series = manual` on every touched Work. Numbering
 * (season/episode/volume/chapter) is preserved unless the user clears it here, so a pure
 * reorder never destroys position information.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesEditor(
    series: MediaSeries,
    works: List<MediaItem>,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val worksById = remember(works) { works.associateBy(MediaItem::id) }
    var memberIds by remember(series.id, series.revision) { mutableStateOf(series.memberIds) }
    var clearPositions by remember(series.id, series.revision) { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var relocateIndex by remember { mutableStateOf<Int?>(null) }
    val dragState = rememberDragReorderState(REORDER_ROW_HEIGHT)
    val listState = rememberLazyListState()
    dragState.itemCount = memberIds.size
    fun move(from: Int, to: Int) {
        memberIds = memberIds.toMutableList().apply { add(to, removeAt(from)) }
    }
    val dirty = memberIds != series.memberIds || clearPositions

    fun save() {
        viewModel.saveSeries(
            series = series,
            memberIds = memberIds,
            clearPositions = clearPositions,
        )
    }

    BackHandler(enabled = true) {
        if (dirty) save()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(series.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${memberIds.size} 部作品" + if (dirty) " · 有未保存修改" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (dirty) save()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回系列书架")
                    }
                },
                actions = {
                    IconButton(onClick = { renameOpen = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "重命名系列")
                    }
                    IconButton(onClick = { addOpen = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加成员")
                    }
                    IconButton(onClick = { deleteOpen = true }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除系列")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                "列表顺序就是阅读顺序。保存只写入系列关系和编号，不会移动、重命名或删除任何媒体。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val anyNumbered = memberIds.any { memberId ->
                val reference = worksById[memberId]?.series
                reference?.season != null || reference?.episode != null ||
                    reference?.volume != null || reference?.chapter != null
            }
            if (anyNumbered) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        if (clearPositions) "保存后清空季/集/卷/章编号" else "保留现有季/集/卷/章编号",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { clearPositions = !clearPositions }) {
                        Text(if (clearPositions) "改为保留" else "清空编号")
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                itemsIndexed(memberIds, key = { _, id -> id }) { index, workId ->
                    val item = worksById[workId]
                    ReorderableRow(
                        index = index,
                        state = dragState,
                        onMove = ::move,
                        listState = listState,
                        leading = { if (item != null) MediaThumbnail(item, viewModel, Modifier.fillMaxSize()) },
                        onClick = {
                            item?.let { viewModel.openChapter(it, memberIds.mapNotNull(worksById::get)) }
                        },
                        headline = {
                            Text(
                                item?.displayTitle ?: workId,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supporting = {
                            Text(
                                buildString {
                                    append("${index + 1}. ")
                                    append(series.positionLabel(workId) ?: "未编号")
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    enabled = index > 0,
                                    onClick = { move(index, index - 1) },
                                ) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "上移")
                                }
                                IconButton(
                                    enabled = index < memberIds.lastIndex,
                                    onClick = { move(index, index + 1) },
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "下移")
                                }
                                IconButton(onClick = { relocateIndex = index }) {
                                    Icon(
                                        Icons.Rounded.FormatListNumbered,
                                        contentDescription = "移动到指定序号",
                                    )
                                }
                                IconButton(onClick = { memberIds = memberIds - workId }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "移出系列")
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
            if (dirty) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "顺序或成员已修改",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                memberIds = series.memberIds
                                clearPositions = false
                            },
                        ) { Text("放弃") }
                        Button(onClick = { save() }) { Text("保存") }
                    }
                }
            }
        }
    }

    if (renameOpen) {
        SeriesRenameDialog(
            current = series.title,
            onDismiss = { renameOpen = false },
            onConfirm = { title ->
                renameOpen = false
                viewModel.saveSeries(series, title = title, memberIds = memberIds, clearPositions = clearPositions)
            },
        )
    }
    if (addOpen) {
        WorkPickerDialog(
            candidates = works.filter { it.id !in memberIds },
            title = "添加系列成员",
            onDismiss = { addOpen = false },
            onConfirm = { picked ->
                addOpen = false
                memberIds = memberIds + picked
            },
        )
    }
    relocateIndex?.let { index ->
        RelocateMemberDialog(
            current = index,
            total = memberIds.size,
            onDismiss = { relocateIndex = null },
            onConfirm = { target ->
                relocateIndex = null
                memberIds = memberIds.toMutableList().apply {
                    val moved = removeAt(index)
                    add(target.coerceIn(0, size), moved)
                }
            },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除系列？") },
            text = { Text("只删除系列关系与编号；作品、版本和媒体原文件都保持不变。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    viewModel.deleteSeries(series)
                    onBack()
                }) { Text("删除系列") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SeriesRenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名系列") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("系列标题") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title.trim()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RelocateMemberDialog(
    current: Int,
    total: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var target by remember(current) { mutableStateOf((current + 1).toString()) }
    val parsed = target.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到指定序号") },
        text = {
            Column {
                Text(
                    "把第 ${current + 1} 部作品移动到第 N 位（1 – $total）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it.filter(Char::isDigit).take(5) },
                    label = { Text("目标序号") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && parsed in 1..total,
                onClick = { parsed?.let { onConfirm(it - 1) } },
            ) { Text("移动") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
