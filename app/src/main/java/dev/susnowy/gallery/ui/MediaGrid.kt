package dev.susnowy.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.ViewMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import dev.susnowy.gallery.model.Entry
import kotlinx.coroutines.launch

/**
 * A media list rendered as a grid, with the right-edge slider beside it.
 *
 * An adaptive grid is the one layout that works for a mixed image/video folder on both a phone
 * and a tablet, and lazy rows keep a ten-thousand-file album from paying for anything off screen.
 */
@Composable
fun MediaGrid(
    entries: List<Entry>,
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewMode: ViewMode = ViewMode.GRID,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    /**
     * The label the scrollbar shows. It is derived from the same index the jump lands on, so the
     * text always names the file the thumb skipped to.
     */
    fun labelAt(fraction: Float): ScrollLabel {
        val entry = entries.getOrNull(targetIndex(entries.size, fraction)) ?: return ScrollLabel("")
        // A collection file is identified by its number, an album file by when it was taken and
        // where. Both fit the same two-line bubble.
        return if (entry.sequence != null) {
            ScrollLabel(entry.positionLabel, "第 ${entry.sequence} 项")
        } else {
            ScrollLabel(entry.positionLabel, entry.place?.label.orEmpty())
        }
    }

    if (viewMode == ViewMode.LIST) {
        MediaList(
            entries = entries,
            thumbnail = thumbnail,
            onOpen = onOpen,
            modifier = modifier,
            labelAt = ::labelAt,
        )
        return
    }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCellWidth(viewMode)),
            state = gridState,
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = TRACK_WIDTH, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(cellGap(viewMode)),
            verticalArrangement = Arrangement.spacedBy(cellGap(viewMode)),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
                // The index travels with the tap: the viewer opens on this cell and pages through
                // the very list the grid is showing, without re-deriving either.
                MediaCell(
                    entry = entry,
                    thumbnail = thumbnail(entry),
                    // The index travels with the tap so the viewer opens on this cell and pages
                    // through the same list the grid is showing, without re-deriving either.
                    onOpen = { onOpen(index) },
                )
            }
        }
        if (entries.size > 1) {
            Scrollbar(
                fraction = gridFraction(gridState, entries.size),
                onJump = { fraction -> scope.launch { jumpGrid(gridState, entries.size, fraction) } },
                labelAt = ::labelAt,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * The same list as rows: one thumbnail, the file name, and the time or number.
 *
 * A row is easier to scan when the names differ only at the end, and it fits far more files on a
 * screen than a grid of squares does — which is what someone looking for one file actually wants.
 */
@Composable
private fun MediaList(
    entries: List<Entry>,
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
    labelAt: (Float) -> ScrollLabel,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(end = TRACK_WIDTH),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
                MediaRowItem(
                    entry = entry,
                    thumbnail = thumbnail(entry),
                    onOpen = { onOpen(index) },
                )
            }
        }
        if (entries.size > 1) {
            Scrollbar(
                fraction = listFraction(listState, entries.size),
                onJump = { fraction -> scope.launch { jumpList(listState, entries.size, fraction) } },
                labelAt = labelAt,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * One media file as a row.
 *
 * Shared with the collection, which lists a folder's files under its folder rows: a file has to
 * look the same whether it is alone in its folder or sitting below subfolders.
 */
@Composable
internal fun MediaRowItem(entry: Entry, thumbnail: ImageRequest?, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (entry.mediaType == MediaType.VIDEO) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "视频",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    entry.positionLabel,
                    entry.place?.label,
                    humanSize(entry.size),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * How wide a grid cell may get before another column is added.
 *
 * One decision, used by every grid: the album, a collection folder, and the media inside a folder
 * that also holds folders. Two of those scroll inside another list, so they size their cells
 * themselves, and the number has to agree with [MediaGrid]'s or the same folder would look
 * different depending on whether it happened to contain a subfolder.
 */
internal fun minCellWidth(viewMode: ViewMode): Dp =
    if (viewMode == ViewMode.COMPACT) 72.dp else 108.dp

/** The gap between cells; the compact grid tightens it as well as the cells. */
internal fun cellGap(viewMode: ViewMode): Dp =
    if (viewMode == ViewMode.COMPACT) 2.dp else 6.dp

/** `1.2 MB` — a size a person can compare at a glance. */
fun humanSize(bytes: Long): String = when {    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** One row in the collection's project list. */
@Composable
fun ProjectRow(
    name: String,
    detail: String,
    onOpen: () -> Unit,
) {
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
