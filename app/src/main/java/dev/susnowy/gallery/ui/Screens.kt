package dev.susnowy.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.Folder
import dev.susnowy.gallery.model.SortMode
import kotlinx.coroutines.launch

/** The image/video/both selector shared by both sections. */
@Composable
fun FilterRow(
    selected: MediaFilter,
    onSelect: (MediaFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaFilter.entries.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option.title) },
            )
        }
    }
}

/** A centred explanation for a list that has nothing to show. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `相册/`: every file in capture order, newest first. One fixed order, so no sort selector. */
@Composable
fun AlbumScreen(
    state: UiState,
    onSelectFilter: (MediaFilter) -> Unit,
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        FilterRow(selected = state.filter, onSelect = onSelectFilter)
        if (state.visibleAlbum.isEmpty()) {
            EmptyHint(if (state.entries.isEmpty()) "相册里还没有可浏览的图片或视频" else "没有符合当前筛选的媒体")
        } else {
            MediaGrid(entries = state.visibleAlbum, thumbnail = thumbnail, onOpen = onOpen)
        }
    }
}

/**
 * `画集/`: a tree of folders, with the search box and the order selector above them.
 *
 * A level shows its folders when it has any and its own media when it has none — the two never
 * share a level, which is the agreed rule and the reason this screen branches on `folderRows`.
 */
@Composable
fun CollectionScreen(
    state: UiState,
    onSearch: (String) -> Unit,
    onSelectFilter: (MediaFilter) -> Unit,
    onSelectSort: (SortMode) -> Unit,
    onOpenFolder: (Folder) -> Unit,
    onBack: () -> Unit,
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = state.folderRows
    val media = state.folderMedia
    val folder = state.currentFolder

    Column(Modifier.fillMaxSize()) {
        if (folder != null) {
            FolderHeader(breadcrumb = state.breadcrumb, onBack = onBack, onJump = onOpenFolder)
        }
        OutlinedTextField(
            value = state.search,
            onValueChange = onSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("搜索作者或文件夹名称") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        )
        FilterRow(selected = state.filter, onSelect = onSelectFilter)
        SortRow(selected = state.sortMode, onSelect = onSelectSort)

        when {
            rows.isNotEmpty() -> Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(end = TRACK_WIDTH),
                ) {
                    items(rows, key = { it.folder.path }) { row ->
                        FolderRowItem(row = row, onOpen = { onOpenFolder(row.folder) })
                    }
                }
                if (rows.size > 1) {
                    Scrollbar(
                        fraction = listFraction(listState, rows.size),
                        onJump = { fraction -> scope.launch { jumpList(listState, rows.size, fraction) } },
                        labelAt = { fraction ->
                            val row = rows.getOrNull(targetIndex(rows.size, fraction))
                            ScrollLabel(
                                primary = row?.folder?.name.orEmpty(),
                                secondary = row?.folder?.let(::authorOf).orEmpty(),
                            )
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            media.isNotEmpty() -> MediaGrid(entries = media, thumbnail = thumbnail, onOpen = onOpen)
            folder != null -> EmptyHint(
                if (state.filter == MediaFilter.ALL) "这个文件夹里没有媒体文件" else "没有符合当前筛选的媒体",
            )
            else -> EmptyHint(if (state.folders.isEmpty()) "画集里还没有文件夹" else "没有匹配的文件夹")
        }
    }
}

/** Where the user is in the tree, and the way back up. Every crumb is a jump target. */
@Composable
private fun FolderHeader(
    breadcrumb: List<Folder>,
    onBack: () -> Unit,
    onJump: (Folder) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回上一级")
        }
        breadcrumb.forEachIndexed { index, folder ->
            if (index > 0) {
                Text("›", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onJump(folder) }) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    color = if (index == breadcrumb.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

/** The collection's order selector. Remembered, because it is how one person reads a library. */
@Composable
fun SortRow(
    selected: SortMode,
    onSelect: (SortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "排序",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SortMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.title) },
            )
        }
    }
}

/** One folder row: the name, what is inside, and a chevron that says it opens. */
@Composable
private fun FolderRowItem(row: FolderRow, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = row.folder.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.detail(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
