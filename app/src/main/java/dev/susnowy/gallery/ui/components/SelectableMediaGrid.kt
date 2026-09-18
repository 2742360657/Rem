package dev.susnowy.gallery.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.ui.GalleryViewModel

/**
 * A media grid with selection mode and the Library's batch actions.
 *
 * Shared by the image/video and works views so batch behaviour is identical everywhere:
 * selection only ever collects ids, every action is one repository call, and the portable
 * write happens inside the repository (never here). Long-press still opens the per-card panel
 * when selection mode is off.
 */
@Composable
fun SelectableMediaGrid(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    supportingText: (MediaItem) -> String? = { null },
    showSelectButton: Boolean = true,
) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = GridSelectionSaver) { mutableStateOf(emptySet<String>()) }
    var showBatchEditor by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var groupPicker by remember { mutableStateOf(false) }
    var seriesPicker by remember { mutableStateOf(false) }
    var removeRelation by remember { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedItems = remember(items, selected) { items.filter { it.id in selected } }
    val canJoinGroup = selectedItems.any { it.kind in GROUPABLE_KINDS }
    val canJoinSeries = selectedItems.any { it.kind in SERIES_KINDS }

    LaunchedEffect(state.activeLibraryId) {
        removeRelation = false
        confirmTrash = false
        groupPicker = false
        seriesPicker = false
        showBatchEditor = false
        selected = emptySet()
    }

    BackHandler(enabled = selectionMode && !showBatchEditor && !confirmTrash && !groupPicker && !seriesPicker && !removeRelation) {
        selectionMode = false
        selected = emptySet()
    }
    // A grid whose items changed (a scan, a filter) must not keep ids that are gone.
    LaunchedEffect(items) {
        selected = selected.intersect(items.mapTo(mutableSetOf(), MediaItem::id))
    }

    Column(modifier.fillMaxWidth()) {
        if (selectionMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                Text("已选 ${selected.size} / ${items.size}", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selected = if (selected.size == items.size) {
                        emptySet()
                    } else {
                        items.mapTo(mutableSetOf(), MediaItem::id)
                    }
                }) { Text(if (selected.size == items.size) "取消全选" else "全选") }
                TextButton(onClick = { selectionMode = false; selected = emptySet() }) { Text("完成") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                FilledTonalButton(enabled = canJoinGroup, onClick = { groupPicker = true }) {
                    Icon(Icons.Rounded.Collections, contentDescription = null)
                    Text(" 加入分组")
                }
                FilledTonalButton(enabled = canJoinSeries, onClick = { seriesPicker = true }) {
                    Icon(Icons.Rounded.FormatListNumbered, contentDescription = null)
                    Text(" 加入系列")
                }
                FilledTonalButton(enabled = selected.isNotEmpty(), onClick = { removeRelation = true }) {
                    Text("移出关系")
                }
                FilledTonalButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { viewModel.setBatchFavorite(selected, true) },
                ) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null)
                    Text(" 收藏")
                }
                FilledTonalButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { showBatchEditor = true },
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Text(" 批量信息")
                }
                FilledTonalButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { confirmTrash = true },
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Text(" 回收站")
                }
            }
        } else if (showSelectButton && items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "${items.size} 项 · 长按打开操作",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selectionMode = true }) { Text("选择") }
            }
        }
        MediaGrid(
            items = items,
            viewModel = viewModel,
            onOpen = onOpen,
            modifier = Modifier.weight(1f),
            compact = compact,
            selectionEnabled = true,
            selectionMode = selectionMode,
            selectedIds = selected,
            onSelectionToggle = { item ->
                selectionMode = true
                selected = if (item.id in selected) selected - item.id else selected + item.id
            },
            supportingText = supportingText,
        )
    }

    if (removeRelation) {
        RelationRemovalDialog(selectedItems, state.groups, state.series,
            onDismiss = { removeRelation = false },
            onConfirm = { request ->
                viewModel.removeRelationMembers(request)
                removeRelation = false
                selectionMode = false
                selected = emptySet()
            })
    }
    if (showBatchEditor) {
        BatchMetadataDialog(
            items = selectedItems,
            onDismiss = { showBatchEditor = false },
            onSave = { baseline, edit ->
                viewModel.editBatchMetadata(baseline, edit)
                showBatchEditor = false
                selectionMode = false
                selected = emptySet()
            },
        )
    }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("将 ${selected.size} 项移入回收站？") },
            text = { Text("只写入逻辑回收站状态，真实文件不会移动或删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTrashedBatch(selected)
                    confirmTrash = false
                    selectionMode = false
                    selected = emptySet()
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("取消") } },
        )
    }
    if (groupPicker) {
        TargetPickerDialog(
            title = "把选中的 ${selectedItems.count { it.kind in GROUPABLE_KINDS }} 项加入分组",
            emptyText = "还没有分组；可以在“图片 / 视频 → 分组”里新建",
            options = state.groups.map { group ->
                TargetOption(group.id, group.title, "${group.memberIds.size} 个成员")
            },
            onDismiss = { groupPicker = false },
            onPick = { option ->
                groupPicker = false
                state.groups.firstOrNull { it.id == option.id }?.let { group ->
                    viewModel.addToGroup(group, selectedItems.filter { it.kind in GROUPABLE_KINDS })
                }
                selectionMode = false
                selected = emptySet()
            },
        )
    }
    if (seriesPicker) {
        TargetPickerDialog(
            title = "把选中的 ${selectedItems.count { it.kind in SERIES_KINDS }} 项加入系列",
            emptyText = "还没有系列；可以在作品信息里填写系列标题",
            options = state.series.map { series ->
                TargetOption(series.id, series.title, "${series.memberIds.size} 部作品")
            },
            onDismiss = { seriesPicker = false },
            onPick = { option ->
                seriesPicker = false
                state.series.firstOrNull { it.id == option.id }?.let { series ->
                    viewModel.addToSeries(series, selectedItems.filter { it.kind in SERIES_KINDS })
                }
                selectionMode = false
                selected = emptySet()
            },
        )
    }
}

private val GROUPABLE_KINDS = setOf(MediaKind.IMAGE_SET, MediaKind.VIDEO, MediaKind.IMAGE)
private val GridSelectionSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) }, restore = { it.toSet() },
)
private val SERIES_KINDS = setOf(MediaKind.IMAGE_SET, MediaKind.VIDEO)
