package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.ui.GalleryViewModel

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("这里还没有内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(142.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items, key = MediaItem::id) { item ->
            MediaCard(item = item, viewModel = viewModel, onClick = { onOpen(item) })
        }
    }
}

@Composable
fun MediaCard(
    item: MediaItem,
    viewModel: GalleryViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnail by produceState<String?>(initialValue = directPreview(item), item.id, item.coverPath) {
        if (value == null && item.coverPath != null) {
            value = runCatching { viewModel.resolvePath(item, item.coverPath) }.getOrNull()
        }
    }
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.9f),
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.kind.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
        }
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
                    item.kind.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.pageCount?.let {
                    Text("$it 页", style = MaterialTheme.typography.labelSmall)
                }
                if (item.needsRepair) {
                    Text(
                        "需修复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
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
    MediaKind.IMAGE_SET -> "ImageSet"
    MediaKind.VIDEO -> "视频"
    MediaKind.PHOTO -> "照片"
    MediaKind.PHOTO_VIDEO -> "相册视频"
    MediaKind.LIVE_PHOTO -> "实况照片"
}

private fun MediaKind.icon(): ImageVector = when (this) {
    MediaKind.IMAGE_SET -> Icons.Rounded.Collections
    MediaKind.VIDEO, MediaKind.PHOTO_VIDEO -> Icons.Rounded.Movie
    MediaKind.IMAGE, MediaKind.PHOTO, MediaKind.LIVE_PHOTO -> Icons.Rounded.Photo
}
