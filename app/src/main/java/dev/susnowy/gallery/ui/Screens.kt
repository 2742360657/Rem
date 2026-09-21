package dev.susnowy.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import dev.susnowy.gallery.model.COLLECTION
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.Folder
import dev.susnowy.gallery.model.ViewMode
import kotlinx.coroutines.launch

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
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (state.visibleAlbum.isEmpty()) {
            EmptyHint(if (state.entries.isEmpty()) "相册里还没有可浏览的图片或视频" else "没有符合当前筛选的媒体")
        } else {
            MediaGrid(
                entries = state.visibleAlbum,
                thumbnail = thumbnail,
                onOpen = onOpen,
                viewMode = state.viewMode,
            )
        }
    }
}

/**
 * `画集/`: a tree of folders, with the search box and the order selector above them.
 *
 * A level lists its folders and its own files together, folders first. Showing one instead of the
 * other lost pictures: a project with a subfolder *and* files at its top level displayed the
 * subfolder and dropped the rest, so those files existed in the Library and nowhere on screen.
 */
@Composable
fun CollectionScreen(
    state: UiState,
    onSearch: (String) -> Unit,
    onOpenFolder: (Folder) -> Unit,
    onBack: () -> Unit,
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = state.folderRows
    val media = state.folderMedia
    // Asked outside the list's item builder: a composable cannot be invoked from a lambda that is
    // not itself composable, and the column count does not change while this list is on screen.
    val columns = gridColumns(state.viewMode)

    Column(Modifier.fillMaxSize()) {
        // Always present: at the top of the collection it names the section, and deeper down it is
        // the way back up. It used to appear only once a folder was open, which left the top level
        // with nothing saying where the user was.
        FolderHeader(breadcrumb = state.breadcrumb, onBack = onBack, onJump = onOpenFolder)
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

        if (rows.isEmpty() && media.isEmpty()) {
            EmptyHint(
                when {
                    state.currentFolder == null ->
                        if (state.folders.isEmpty()) "画集里还没有文件夹" else "没有匹配的文件夹"
                    state.filter != MediaFilter.ALL -> "没有符合当前筛选的媒体"
                    else -> "这个文件夹里没有媒体文件"
                },
            )
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(end = TRACK_WIDTH),
            ) {
                items(rows, key = { it.folder.path }) { row ->
                    FolderRowItem(row = row, onOpen = { onOpenFolder(row.folder) })
                }
                // The media is handed its own slice of the scroll rather than nested in another
                // scrollable, so it can be laid out for the width it actually gets and its cells
                // keep the same size they have on a level with no folders in it.
                val mediaStart = rows.size
                when (state.viewMode) {
                    ViewMode.LIST -> itemsIndexed(media, key = { _, entry -> entry.path }) { index, entry ->
                        MediaRowItem(
                            entry = entry,
                            thumbnail = thumbnail(entry),
                            onOpen = { onOpen(index) },
                        )
                    }
                    ViewMode.GRID, ViewMode.COMPACT -> {
                        itemsIndexed(
                            items = media.chunked(columns),
                            key = { _, row -> row.first().path },
                        ) { rowIndex, row ->
                            InlineMediaRow(
                                entries = row,
                                startIndex = rowIndex * columns,
                                now = mediaStart,
                                columns = columns,
                                viewMode = state.viewMode,
                                thumbnail = thumbnail,
                                onOpen = onOpen,
                            )
                        }
                    }
                }
            }
            if (rows.size + media.size > 1) {
                Scrollbar(
                    fraction = listFraction(listState, rows.size + media.size),
                    onJump = { fraction ->
                        scope.launch { jumpList(listState, rows.size + media.size, fraction) }
                    },
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
    }
}

/** One media file as a row, named for the collection's use of it. */
@Composable
private fun InlineMediaRow(
    entries: List<Entry>,
    startIndex: Int,
    now: Int,
    columns: Int,
    viewMode: ViewMode,
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
) {
    val gap = cellGap(viewMode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = gap / 2),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        entries.forEachIndexed { offset, entry ->
            // Weight rather than a measured width: a row inside a lazy list has no intrinsic
            // width to divide, and weight is what makes the last row line up with the full ones.
            Box(Modifier.weight(1f)) {
                MediaCell(
                    entry = entry,
                    thumbnail = thumbnail(entry),
                    // The index is into the folder's whole media list, not this row, so the viewer
                    // opens on the cell that was tapped and pages through the folder.
                    onOpen = { onOpen(now + startIndex + offset) },
                )
            }
        }
        // Empty slots keep the last row's cells the width of every other row's instead of
        // stretching two files across the screen.
        repeat(columns - entries.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** How many grid columns fit the screen; kept in step with the grid's own adaptive sizing. */
@Composable
private fun gridColumns(viewMode: ViewMode): Int {
    val width = LocalConfiguration.current.screenWidthDp.dp
    // The scrollbar reserves a strip on the right, so the usable width is not the screen's.
    val usable = width - TRACK_WIDTH - 16.dp
    return (usable / minCellWidth(viewMode)).toInt().coerceAtLeast(1)
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
        // At the top of the collection there is nowhere to go back to, so the button becomes the
        // title instead. It used to be shown regardless, offering a step up that did nothing.
        if (breadcrumb.isEmpty()) {
            Text(
                text = COLLECTION,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp),
            )
            return@Row
        }
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
