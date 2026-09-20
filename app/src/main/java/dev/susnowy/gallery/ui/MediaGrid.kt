package dev.susnowy.gallery.ui

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

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = TRACK_WIDTH, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
