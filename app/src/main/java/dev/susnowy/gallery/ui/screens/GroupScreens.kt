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
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import dev.susnowy.gallery.model.GroupMemberRole
import dev.susnowy.gallery.model.MediaGroup
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.derivedGroupId
import dev.susnowy.gallery.ui.GalleryViewModel
import dev.susnowy.gallery.ui.MixedMediaPresentation
import dev.susnowy.gallery.ui.components.MediaThumbnail
import dev.susnowy.gallery.ui.components.REORDER_ROW_HEIGHT
import dev.susnowy.gallery.ui.components.ReorderableRow
import dev.susnowy.gallery.ui.components.rememberDragReorderState
import dev.susnowy.gallery.ui.components.WorkPickerDialog

/**
 * The "分组" tab: portable Groups first, then derived mixed folders that can be saved as one.
 *
 * A derived folder is only presentation — the scanner notices that an image set and some
 * videos live in the same directory. Saving it writes a real Group, after which the folder
 * stops being listed here and becomes editable.
 */
@Composable
fun GroupShelf(
    items: List<MediaItem>,
    groups: List<MediaGroup>,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<List<String>?>(null) }
    val derived = remember(items) { MixedMediaPresentation.groups(items) }
    val itemsById = remember(items) { items.associateBy(MediaItem::id) }
    val libraryId = items.firstOrNull()?.libraryId
    val unsavedDerived = remember(derived, groups, libraryId) {
        MixedMediaPresentation.unsavedGroups(derived, groups, libraryId)
    }
    if (groups.isEmpty() && unsavedDerived.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "同一目录中的图片集与视频会在这里形成派生分组；保存为 Group 后可以手动增删成员和调整顺序",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { picking = true }) { Text("新建分组") }
        }
        // The dialogs are the only path from the button to a real Group, so they have to be
        // composed for both layouts; keeping them inside this branch made the identical button in
        // the list below do nothing as soon as one group or derived folder existed.
        NewGroupDialogs(
            picking = picking,
            naming = naming,
            candidates = items,
            viewModel = viewModel,
            onPickingChange = { picking = it },
            onNamingChange = { naming = it },
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(key = "new-group") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "分组是“一起浏览”的关系，不会移动媒体",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { picking = true }) { Text("新建分组") }
            }
        }
        if (groups.isNotEmpty()) {
            item(key = "manual-header") {
                SectionHeader("手动分组 ${groups.size}", "成员顺序、封面和标题都写在 Library 里")
            }
            itemsIndexed(groups, key = { _, group -> group.id }) { _, group ->
                val members = group.memberIds.mapNotNull(itemsById::get)
                GroupRow(
                    title = group.title,
                    subtitle = groupSubtitle(members, prefix = null),
                    cover = itemsById[group.coverWorkId] ?: members.firstOrNull(),
                    viewModel = viewModel,
                    onClick = { viewModel.openGroup(group.id) },
                )
            }
        }
        if (unsavedDerived.isNotEmpty()) {
            item(key = "derived-header") {
                SectionHeader(
                    "目录派生 ${unsavedDerived.size}",
                    "扫描推断的图片/视频目录；保存为 Group 后才能手动编辑",
                )
            }
            itemsIndexed(unsavedDerived, key = { _, group -> group.key }) { _, group ->
                GroupRow(
                    title = group.primary.displayTitle,
                    subtitle = groupSubtitle(group.members, prefix = group.primary.relativePath),
                    cover = group.primary,
                    viewModel = viewModel,
                    onClick = { viewModel.open(group.primary, group.members) },
                    action = {
                        TextButton(
                            onClick = {
                                viewModel.saveDerivedGroup(
                                    primary = group.primary,
                                    title = group.primary.displayTitle,
                                    memberIds = group.members.map(MediaItem::id),
                                )
                            },
                        ) { Text("保存为 Group") }
                    },
                )
            }
        }
    }
    NewGroupDialogs(
        picking = picking,
        naming = naming,
        candidates = items,
        viewModel = viewModel,
        onPickingChange = { picking = it },
        onNamingChange = { naming = it },
    )
}

@Composable
private fun NewGroupDialogs(
    picking: Boolean,
    naming: List<String>?,
    candidates: List<MediaItem>,
    viewModel: GalleryViewModel,
    onPickingChange: (Boolean) -> Unit,
    onNamingChange: (List<String>?) -> Unit,
) {
    if (picking) {
        WorkPickerDialog(
            candidates = candidates,
            onDismiss = { onPickingChange(false) },
            onConfirm = { picked ->
                onPickingChange(false)
                onNamingChange(picked)
            },
        )
    }
    naming?.let { picked ->
        GroupRenameDialog(
            current = "",
            onDismiss = { onNamingChange(null) },
            onConfirm = { title ->
                onNamingChange(null)
                viewModel.createGroup(title, picked)
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    cover: MediaItem?,
    viewModel: GalleryViewModel,
    onClick: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    MediaThumbnail(item = cover, viewModel = viewModel, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.Star, contentDescription = null)
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.invoke()
        }
    }
    HorizontalDivider()
}

private fun groupSubtitle(members: List<MediaItem>, prefix: String?): String = buildString {
    prefix?.takeIf(String::isNotBlank)?.let { append("$it · ") }
    val images = members.count { it.kind == MediaKind.IMAGE || it.kind == MediaKind.IMAGE_SET }
    val videos = members.count { it.kind == MediaKind.VIDEO }
    append("${members.size} 个成员")
    if (images > 0) append(" · $images 张图片入口")
    if (videos > 0) append(" · $videos 个视频")
}

/**
 * Group editor.
 *
 * Title, membership, order and cover are edited locally and committed by one explicit save,
 * because every group write rewrites the portable catalog. Editing never moves media: the
 * member list is a relationship between Works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetail(
    group: MediaGroup,
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val itemsById = remember(items) { items.associateBy(MediaItem::id) }
    var memberIds by androidx.compose.runtime.saveable.rememberSaveable(group.id, group.revision) { mutableStateOf(group.memberIds) }
    var coverWorkId by androidx.compose.runtime.saveable.rememberSaveable(group.id, group.revision) { mutableStateOf(group.coverWorkId) }
    var confirmDiscard by remember(group.id) { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val dirty = memberIds != group.memberIds || coverWorkId != group.coverWorkId
    val dragState = rememberDragReorderState(REORDER_ROW_HEIGHT)
    val listState = rememberLazyListState()
    dragState.itemCount = memberIds.size
    fun move(from: Int, to: Int) {
        memberIds = memberIds.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun requestBack() {
        if (dirty) confirmDiscard = true else onBack()
    }
    BackHandler(enabled = !showPicker && !renameOpen && !deleteOpen && !confirmDiscard) {
        requestBack()
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回不会自动保存。可以继续编辑并点保存。") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${memberIds.size} 个成员" + if (dirty) " · 有未保存修改" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { renameOpen = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "重命名")
                    }
                    IconButton(onClick = { showPicker = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加成员")
                    }
                    IconButton(onClick = { deleteOpen = true }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除分组")
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
                "拖动顺序请用每行的上下箭头；保存会写入 Library，不会移动或重命名任何媒体。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            dev.susnowy.gallery.ui.components.ListPositionButton(listState, memberIds.size)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                itemsIndexed(memberIds, key = { _, id -> id }) { index, memberId ->
                    val item = itemsById[memberId]
                    ReorderableRow(
                        index = index,
                        state = dragState,
                        onMove = ::move,
                        listState = listState,
                        leading = { if (item != null) MediaThumbnail(item, viewModel, Modifier.fillMaxSize()) },
                        onClick = {
                            item?.let { viewModel.open(it, memberIds.mapNotNull(itemsById::get)) }
                        },
                        headline = {
                            Text(
                                item?.displayTitle ?: memberId,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supporting = {
                            Text(
                                buildString {
                                    append("${index + 1}. ")
                                    append(item?.relativePath ?: "成员不在当前索引中")
                                    if (memberId == coverWorkId) append(" · 当前封面")
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
                                IconButton(onClick = { coverWorkId = memberId }) {
                                    Icon(
                                        if (memberId == coverWorkId) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        contentDescription = "设为封面",
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        memberIds = memberIds - memberId
                                        if (coverWorkId == memberId) coverWorkId = memberIds.firstOrNull()
                                    },
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "移出分组")
                                }
                            }
                        },
                    )
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
                            "成员、顺序或封面已修改",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                memberIds = group.memberIds
                                coverWorkId = group.coverWorkId
                            },
                        ) { Text("放弃") }
                        Button(
                            onClick = { viewModel.updateGroup(group, memberIds, coverWorkId = coverWorkId) },
                        ) { Text("保存") }
                    }
                }
            }
        }
    }

    if (showPicker) {
        WorkPickerDialog(
            candidates = items.filter { it.id !in memberIds },
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                memberIds = memberIds + picked
                if (coverWorkId == null) coverWorkId = memberIds.firstOrNull()
                showPicker = false
            },
        )
    }
    if (renameOpen) {
        GroupRenameDialog(
            current = group.title,
            onDismiss = { renameOpen = false },
            onConfirm = { title ->
                renameOpen = false
                viewModel.updateGroup(group, memberIds, title = title, coverWorkId = coverWorkId)
            },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除分组？") },
            text = { Text("只删除“一起浏览”的关系；作品、版本和媒体原文件都保持不变。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    viewModel.deleteGroup(group)
                    onBack()
                }) { Text("删除分组") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun GroupRenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名分组") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("分组标题") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Short label for a member role, used by the editor and the member list. */
fun GroupMemberRole.label(): String = when (this) {
    GroupMemberRole.ITEM -> "成员"
    GroupMemberRole.IMAGE -> "图片"
    GroupMemberRole.VIDEO -> "视频"
    GroupMemberRole.BONUS -> "附赠"
    GroupMemberRole.COVER -> "封面"
}
