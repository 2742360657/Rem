package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.ui.GalleryViewModel

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selectionEnabled: Boolean = false,
    selectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onSelectionToggle: (MediaItem) -> Unit = {},
    quickActionsEnabled: Boolean = true,
    supportingText: (MediaItem) -> String? = { null },
) {
    var actionItem by remember { mutableStateOf<MediaItem?>(null) }
    var editItem by remember { mutableStateOf<MediaItem?>(null) }
    var trashItem by remember { mutableStateOf<MediaItem?>(null) }
    var groupPickerFor by remember { mutableStateOf<MediaItem?>(null) }
    var seriesPickerFor by remember { mutableStateOf<MediaItem?>(null) }
    var compareItem by remember { mutableStateOf<MediaItem?>(null) }
    // Groups and Series are needed to offer "add to …" straight from a card; the state is
    // collected once per grid, not per card.
    val libraryState by viewModel.uiState.collectAsStateWithLifecycle()
    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("这里还没有内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (compact) 92.dp else 142.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = if (compact) {
                androidx.compose.foundation.layout.PaddingValues(
                    start = 3.dp,
                    top = 3.dp,
                    end = 3.dp,
                    bottom = 112.dp,
                )
            } else {
                androidx.compose.foundation.layout.PaddingValues(16.dp)
            },
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 14.dp),
        ) {
            items(items, key = MediaItem::id) { item ->
                MediaCard(
                    item = item,
                    viewModel = viewModel,
                    onClick = {
                        if (selectionMode) onSelectionToggle(item) else onOpen(item)
                    },
                    onLongClick = when {
                        selectionMode && selectionEnabled -> ({ onSelectionToggle(item) })
                        quickActionsEnabled -> ({ actionItem = item })
                        selectionEnabled -> ({ onSelectionToggle(item) })
                        else -> null
                    },
                    selected = item.id in selectedIds,
                    compact = compact,
                    supportingText = supportingText(item),
                )
            }
        }
    }
    actionItem?.let { item ->
        RightSidePanel(
            visible = true,
            title = when {
                item.kind == MediaKind.IMAGE_SET && item.domain == MediaDomain.CLASSIFIED -> "图集操作"
                item.kind == MediaKind.IMAGE_SET -> "作品操作"
                else -> "媒体操作"
            },
            onDismiss = { actionItem = null },
        ) {
            MediaThumbnail(
                item = item,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop,
            )
            Text(item.displayTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                item.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            FilledTonalButton(
                onClick = {
                    actionItem = null
                    onOpen(item)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.OpenInFull, contentDescription = null)
                Text(
                    when {
                        item.kind == MediaKind.IMAGE_SET && item.domain == MediaDomain.CLASSIFIED -> " 查看分组"
                        item.kind == MediaKind.IMAGE_SET -> " 查看作品详情"
                        else -> " 打开"
                    },
                )
            }
            if (item.inInbox) {
                FilledTonalButton(
                    onClick = {
                        viewModel.acceptSuggestions(listOf(item))
                        actionItem = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    Text(" 接受识别建议")
                }
            }
            FilledTonalButton(
                onClick = {
                    actionItem = null
                    editItem = item
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = null)
                Text(" 编辑信息")
            }
            FilledTonalButton(
                onClick = {
                    viewModel.setFavorite(item, !item.favorite)
                    actionItem = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Favorite, contentDescription = null)
                Text(if (item.favorite) " 取消收藏" else " 加入收藏")
            }
            if (item.kind in setOf(MediaKind.IMAGE, MediaKind.PHOTO, MediaKind.LIVE_PHOTO)) {
                FilledTonalButton(
                    onClick = {
                        viewModel.deriveImage(item)
                        actionItem = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Text(" 复制到分类图片")
                }
            }
            if (item.kind in setOf(MediaKind.IMAGE_SET, MediaKind.VIDEO, MediaKind.IMAGE)) {
                FilledTonalButton(
                    onClick = {
                        actionItem = null
                        groupPickerFor = item
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Collections, contentDescription = null)
                    Text(" 加入分组…")
                }
            }
            if (item.kind in setOf(MediaKind.IMAGE_SET, MediaKind.VIDEO)) {
                FilledTonalButton(
                    onClick = {
                        actionItem = null
                        seriesPickerFor = item
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.FormatListNumbered, contentDescription = null)
                    Text(" 加入系列…")
                }
            }
            if (item.kind == MediaKind.IMAGE_SET) {
                FilledTonalButton(
                    onClick = {
                        actionItem = null
                        compareItem = item
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Compare, contentDescription = null)
                    Text(" 比较版本…")
                }
            }
            if (selectionEnabled) {
                FilledTonalButton(
                    onClick = {
                        actionItem = null
                        onSelectionToggle(item)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = null)
                    Text(" 进入多选")
                }
            }
            TextButton(
                onClick = {
                    actionItem = null
                    trashItem = item
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                Text(" 移入回收站")
            }
        }
    }
    editItem?.let { item ->
        MetadataEditor(
            item = item,
            onDismiss = { editItem = null },
            onSave = { title, authors, tags, collections, series, favorite, domain ->
                viewModel.saveMetadata(
                    item,
                    title,
                    authors,
                    tags,
                    collections,
                    series,
                    favorite,
                    domain,
                )
                editItem = null
            },
        )
    }
    trashItem?.let { item ->
        AlertDialog(
            onDismissRequest = { trashItem = null },
            title = { Text("移入回收站？") },
            text = { Text("只写入逻辑回收站状态，真实文件不会立即删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTrashed(item, true)
                    trashItem = null
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { trashItem = null }) { Text("取消") } },
        )
    }
    groupPickerFor?.let { item ->
        TargetPickerDialog(
            title = "把《${item.displayTitle}》加入分组",
            emptyText = "还没有分组；可以在“图片 / 视频 → 分组”里新建",
            options = libraryState.groups.map { group ->
                TargetOption(
                    id = group.id,
                    title = group.title,
                    subtitle = "${group.memberIds.size} 个成员",
                )
            },
            onDismiss = { groupPickerFor = null },
            onPick = { option ->
                groupPickerFor = null
                libraryState.groups.firstOrNull { it.id == option.id }
                    ?.let { group -> viewModel.addToGroup(group, listOf(item)) }
            },
        )
    }
    seriesPickerFor?.let { item ->
        TargetPickerDialog(
            title = "把《${item.displayTitle}》加入系列",
            emptyText = "还没有系列；可以在作品信息里填写系列标题",
            options = libraryState.series.map { series ->
                TargetOption(
                    id = series.id,
                    title = series.title,
                    subtitle = "${series.memberIds.size} 部作品",
                )
            },
            onDismiss = { seriesPickerFor = null },
            onPick = { option ->
                seriesPickerFor = null
                libraryState.series.firstOrNull { it.id == option.id }
                    ?.let { series -> viewModel.addToSeries(series, listOf(item)) }
            },
        )
    }
    compareItem?.let { item ->
        EditionCompareDialog(
            left = item,
            candidates = libraryState.allMedia.filter { candidate ->
                candidate.id != item.id &&
                    candidate.kind == MediaKind.IMAGE_SET &&
                    !candidate.trashed &&
                    !candidate.inInbox
            },
            viewModel = viewModel,
            onDismiss = { compareItem = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    item: MediaItem,
    viewModel: GalleryViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    compact: Boolean = false,
    supportingText: String? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                } else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (compact) 1f else 0.9f),
        ) {
            MediaThumbnail(
                item = item,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.favorite) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = "收藏",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(5.dp),
                    )
                }
            }
            if (selected) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "已选择",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            if (compact && (item.kind == MediaKind.VIDEO || item.kind == MediaKind.PHOTO_VIDEO)) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp),
                ) {
                    Icon(
                        Icons.Rounded.Movie,
                        contentDescription = "视频",
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(3.dp),
                    )
                }
            }
        }
        if (!compact) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    item.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.typeLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    item.pageCount?.let {
                        Text("$it 页", style = MaterialTheme.typography.labelSmall)
                    }
                    if (item.missingMedia) {
                        // Normal state after a move, a partial copy, or on another device: the Work
                        // and its decisions are intact, only the bytes are elsewhere.
                        Text(
                            "媒体不在本机",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (item.needsRepair) {
                        Text(
                            "需修复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                supportingText?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.domain == MediaDomain.WORKS) {
                    item.authors.takeIf { it.isNotEmpty() }?.let { authors ->
                        Text(
                            authors.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val descriptors = buildList {
                        item.series?.title?.let(::add)
                        addAll(item.tags.take(2))
                    }
                    if (descriptors.isNotEmpty()) {
                        Text(
                            descriptors.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MediaThumbnail(
    item: MediaItem,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val offlinePreview by produceState<java.io.File?>(
        initialValue = null,
        item.id,
        item.modifiedAt,
        item.coverPath,
    ) {
        value = runCatching { viewModel.offlinePreview(item) }.getOrNull()
    }
    val thumbnail by produceState<String?>(initialValue = directPreview(item), item.id, item.coverPath) {
        if (value == null && item.coverPath != null) {
            value = runCatching { viewModel.resolvePath(item, item.coverPath) }.getOrNull()
        }
    }
    val thumbnailRequest = remember(context, offlinePreview, thumbnail) {
        (offlinePreview ?: thumbnail)?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(640, 640)
                .precision(Precision.INEXACT)
                .build()
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            thumbnailRequest != null -> AsyncImage(
                model = thumbnailRequest,
                contentDescription = item.displayTitle,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Icon(
                imageVector = item.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun directPreview(item: MediaItem): String? = when {
    item.kind == MediaKind.IMAGE || item.kind == MediaKind.PHOTO || item.kind == MediaKind.LIVE_PHOTO -> item.uri
    item.kind == MediaKind.VIDEO || item.kind == MediaKind.PHOTO_VIDEO -> item.uri
    item.kind == MediaKind.IMAGE_SET && item.sourceKind == SourceKind.FILE -> item.uri
    else -> null
}

fun MediaKind.label(): String = when (this) {
    MediaKind.IMAGE -> "图片"
    MediaKind.IMAGE_SET -> "漫画"
    MediaKind.VIDEO -> "视频"
    MediaKind.PHOTO -> "照片"
    MediaKind.PHOTO_VIDEO -> "相册视频"
    MediaKind.LIVE_PHOTO -> "实况照片"
}

fun MediaItem.typeLabel(): String = when {
    kind == MediaKind.IMAGE_SET && domain == MediaDomain.CLASSIFIED -> "图集"
    else -> kind.label()
}

private fun MediaKind.icon(): ImageVector = when (this) {
    MediaKind.IMAGE_SET -> Icons.Rounded.Collections
    MediaKind.VIDEO, MediaKind.PHOTO_VIDEO -> Icons.Rounded.Movie
    MediaKind.IMAGE, MediaKind.PHOTO, MediaKind.LIVE_PHOTO -> Icons.Rounded.Photo
}
