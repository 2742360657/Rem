package dev.susnowy.gallery.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.susnowy.gallery.model.DiscoveredEntry
import dev.susnowy.gallery.model.DiscoveryReason
import dev.susnowy.gallery.model.InboxDisposition
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.ui.GalleryViewModel
import dev.susnowy.gallery.ui.components.MediaGrid

/**
 * Everything Inbox can show, already split by the portable decision behind it.
 *
 * Pending entries have no decision at all; the other lists are decided entries the user can
 * still undo. A decided entry never disappears without a way back.
 */
data class InboxContent(
    val pendingMedia: List<MediaItem> = emptyList(),
    val pendingDiscoveries: List<DiscoveredEntry> = emptyList(),
    val ignoredMedia: List<MediaItem> = emptyList(),
    val ignoredDiscoveries: List<DiscoveredEntry> = emptyList(),
    val handledDiscoveries: List<DiscoveredEntry> = emptyList(),
) {
    val isEmpty: Boolean
        get() = pendingMedia.isEmpty() && pendingDiscoveries.isEmpty() &&
            ignoredMedia.isEmpty() && ignoredDiscoveries.isEmpty() && handledDiscoveries.isEmpty()

    fun count(section: InboxSection): Int = when (section) {
        InboxSection.MEDIA -> pendingMedia.size
        InboxSection.OTHER -> pendingDiscoveries.size
        InboxSection.IGNORED -> ignoredMedia.size + ignoredDiscoveries.size
        InboxSection.HANDLED -> handledDiscoveries.size
    }
}

enum class InboxSection(val label: String) {
    MEDIA("媒体建议"),
    OTHER("其他待判断"),
    IGNORED("已忽略"),
    HANDLED("已处理"),
}

@Composable
fun InboxScreen(content: InboxContent, viewModel: GalleryViewModel) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selectedIgnored by remember { mutableStateOf(emptySet<String>()) }
    var section by rememberSaveable {
        mutableStateOf(
            when {
                content.pendingMedia.isNotEmpty() -> InboxSection.MEDIA
                content.pendingDiscoveries.isNotEmpty() -> InboxSection.OTHER
                content.ignoredMedia.isNotEmpty() || content.ignoredDiscoveries.isNotEmpty() ->
                    InboxSection.IGNORED
                else -> InboxSection.HANDLED
            },
        )
    }
    LaunchedEffect(content.pendingMedia.map(MediaItem::id)) {
        selected = selected.intersect(content.pendingMedia.mapTo(mutableSetOf(), MediaItem::id))
        if (content.pendingMedia.isEmpty()) selectionMode = false
    }
    LaunchedEffect(content.ignoredMedia.map(MediaItem::id)) {
        selectedIgnored = selectedIgnored.intersect(content.ignoredMedia.mapTo(mutableSetOf(), MediaItem::id))
    }
    LaunchedEffect(content.count(section)) {
        if (content.count(section) == 0) {
            section = InboxSection.entries.firstOrNull { content.count(it) > 0 } ?: section
        }
    }

    if (content.isEmpty) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("没有待处理内容", style = MaterialTheme.typography.titleMedium)
            Text(
                "新识别媒体、未知格式和结构不明确的目录会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InboxSection.entries.forEach { candidate ->
                FilterChip(
                    selected = section == candidate,
                    onClick = { section = candidate },
                    label = { Text("${candidate.label} ${content.count(candidate)}") },
                    enabled = content.count(candidate) > 0,
                )
            }
        }
        when (section) {
            InboxSection.MEDIA -> PendingMediaSection(
                content = content,
                viewModel = viewModel,
                selectionMode = selectionMode,
                onSelectionModeChange = { selectionMode = it },
                selected = selected,
                onSelectedChange = { selected = it },
            )
            InboxSection.OTHER -> PendingDiscoverySection(content, viewModel)
            InboxSection.IGNORED -> IgnoredSection(
                content = content,
                viewModel = viewModel,
                selected = selectedIgnored,
                onSelectedChange = { selectedIgnored = it },
            )
            InboxSection.HANDLED -> HandledSection(content, viewModel)
        }
    }
}

@Composable
private fun PendingMediaSection(
    content: InboxContent,
    viewModel: GalleryViewModel,
    selectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
) {
    val items = content.pendingMedia
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${items.size} 项待处理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "扫描结果只是建议；接受会保留自动来源，编辑会写入人工值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectionMode) {
                TextButton(onClick = {
                    onSelectedChange(
                        if (selected.size == items.size) {
                            emptySet()
                        } else {
                            items.mapTo(mutableSetOf(), MediaItem::id)
                        },
                    )
                }) {
                    Text(if (selected.size == items.size) "取消全选" else "全选")
                }
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        viewModel.acceptSuggestions(items.filter { it.id in selected })
                        onSelectedChange(emptySet())
                        onSelectionModeChange(false)
                    },
                ) { Text("接受 ${selected.size}") }
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        viewModel.decideInbox(
                            items = items.filter { it.id in selected },
                            disposition = InboxDisposition.IGNORED,
                        )
                        onSelectedChange(emptySet())
                        onSelectionModeChange(false)
                    },
                ) { Text("忽略") }
            } else {
                TextButton(onClick = { onSelectionModeChange(true) }) { Text("选择") }
            }
        }
        MediaGrid(
            items = items,
            viewModel = viewModel,
            onOpen = { viewModel.open(it, items) },
            modifier = Modifier.weight(1f),
            selectionEnabled = true,
            selectionMode = selectionMode,
            selectedIds = selected,
            onSelectionToggle = { item ->
                onSelectedChange(if (item.id in selected) selected - item.id else selected + item.id)
            },
        )
    }
}

@Composable
private fun PendingDiscoverySection(content: InboxContent, viewModel: GalleryViewModel) {
    val entries = content.pendingDiscoveries
    Column(Modifier.fillMaxSize()) {
        Text(
            "这些路径已被发现，但当前不能安全分类或打开。忽略或标记已处理后不会移动、删除或伪装它们，" +
                "决定会写入 Library，可在“已忽略 / 已处理”中撤销。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(entries, key = DiscoveredEntry::id) { entry ->
                DiscoveryRow(entry) {
                    TextButton(
                        onClick = {
                            viewModel.decideInbox(
                                discoveries = listOf(entry),
                                disposition = InboxDisposition.HANDLED,
                            )
                        },
                    ) { Text("已处理") }
                    TextButton(
                        onClick = {
                            viewModel.decideInbox(
                                discoveries = listOf(entry),
                                disposition = InboxDisposition.IGNORED,
                            )
                        },
                    ) { Text("忽略") }
                }
            }
        }
    }
}

@Composable
private fun IgnoredSection(
    content: InboxContent,
    viewModel: GalleryViewModel,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "被忽略的内容仍在原位置，只是不再出现在普通视图。撤销后按扫描结果重新决定归属。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (content.ignoredMedia.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "媒体 ${content.ignoredMedia.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = {
                    onSelectedChange(
                        if (selected.size == content.ignoredMedia.size) {
                            emptySet()
                        } else {
                            content.ignoredMedia.mapTo(mutableSetOf(), MediaItem::id)
                        },
                    )
                }) {
                    Text(if (selected.size == content.ignoredMedia.size) "取消全选" else "全选")
                }
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        viewModel.undoInboxDecision(
                            items = content.ignoredMedia.filter { it.id in selected },
                        )
                        onSelectedChange(emptySet())
                    },
                ) { Text("撤销 ${selected.size}") }
            }
            MediaGrid(
                items = content.ignoredMedia,
                viewModel = viewModel,
                onOpen = { viewModel.open(it, content.ignoredMedia) },
                modifier = Modifier.weight(1f, fill = content.ignoredDiscoveries.isEmpty()),
                selectionEnabled = true,
                selectionMode = true,
                selectedIds = selected,
                onSelectionToggle = { item ->
                    onSelectedChange(if (item.id in selected) selected - item.id else selected + item.id)
                },
            )
        }
        if (content.ignoredDiscoveries.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = content.ignoredMedia.isEmpty()),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(content.ignoredDiscoveries, key = DiscoveredEntry::id) { entry ->
                    DiscoveryRow(entry) {
                        TextButton(
                            onClick = { viewModel.undoInboxDecision(discoveries = listOf(entry)) },
                        ) { Text("撤销") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HandledSection(content: InboxContent, viewModel: GalleryViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "这些路径已经由你确认无需 Rem 处理；撤销后会重新回到“其他待判断”。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(content.handledDiscoveries, key = DiscoveredEntry::id) { entry ->
                DiscoveryRow(entry) {
                    TextButton(
                        onClick = { viewModel.undoInboxDecision(discoveries = listOf(entry)) },
                    ) { Text("撤销") }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryRow(entry: DiscoveredEntry, actions: @Composable () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                entry.relativePath.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    when (entry.reason) {
                        DiscoveryReason.UNSUPPORTED_FILE -> "当前版本不支持此文件格式"
                        DiscoveryReason.AMBIGUOUS_DIRECTORY -> "目录结构不明确，需要确认作品边界"
                    },
                )
                Text(
                    entry.relativePath,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        leadingContent = {
            Icon(
                if (entry.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
            )
        },
        trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { actions() } },
    )
}
