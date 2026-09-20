package dev.susnowy.gallery.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.MediaType

/**
 * One media cell: a square thumbnail that opens the file.
 *
 * The model carries the tree's read grant with it because nothing has persisted that permission
 * for Coil, and without it the fetch fails with a security exception rather than showing a
 * thumbnail. Video stills come from Coil's `coil-video` decoder, so no frame is decoded during
 * the scan.
 */
@Composable
fun MediaCell(
    entry: Entry,
    thumbnail: ImageRequest?,
    onOpen: (Entry) -> Unit,
) {
    Surface(
        onClick = { onOpen(entry) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = entry.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            if (entry.mediaType == MediaType.VIDEO) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "视频",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                )
            }
        }
    }
}
