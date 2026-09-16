package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind

@Composable
fun MetadataEditor(
    item: MediaItem,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, Boolean, MediaDomain) -> Unit,
) {
    var title by remember(item.id) { mutableStateOf(item.displayTitle) }
    var authors by remember(item.id) { mutableStateOf(item.authors.joinToString()) }
    var tags by remember(item.id) { mutableStateOf(item.tags.joinToString()) }
    var collections by remember(item.id) { mutableStateOf(item.collections.joinToString()) }
    var series by remember(item.id) { mutableStateOf(item.series?.title.orEmpty()) }
    var sortIndex by remember(item.id) { mutableStateOf(item.series?.sortIndex?.toString().orEmpty()) }
    var favorite by remember(item.id) { mutableStateOf(item.favorite) }
    var domain by remember(item.id) { mutableStateOf(item.domain) }
    val showWorkFields = domain == MediaDomain.WORKS || item.kind == MediaKind.IMAGE_SET
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (showWorkFields) "编辑作品信息" else "编辑媒体信息") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.relativePath, style = MaterialTheme.typography.bodySmall)
                if (item.kind == MediaKind.VIDEO && item.domain != MediaDomain.ALBUM) {
                    Text("显示位置", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = domain == MediaDomain.CLASSIFIED,
                            onClick = { domain = MediaDomain.CLASSIFIED },
                            label = { Text("图片 / 视频") },
                        )
                        FilterChip(
                            selected = domain == MediaDomain.WORKS,
                            onClick = { domain = MediaDomain.WORKS },
                            label = { Text("漫画 / 动漫") },
                        )
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("显示标题") }, singleLine = true)
                if (showWorkFields) {
                    OutlinedTextField(authors, { authors = it }, label = { Text("作者（逗号分隔）") }, singleLine = true)
                    OutlinedTextField(tags, { tags = it }, label = { Text("标签（支持 namespace）") }, singleLine = true)
                    OutlinedTextField(collections, { collections = it }, label = { Text("Collection") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            series,
                            { series = it },
                            label = { Text("系列") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            sortIndex,
                            { sortIndex = it },
                            label = { Text("排序") },
                            modifier = Modifier.weight(0.5f),
                            singleLine = true,
                        )
                    }
                } else {
                    Text(
                        "普通相册和分类媒体保持简洁；作者、标签和系列仅在作品库使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { favorite = !favorite }) {
                    Icon(
                        if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                    )
                    Text(if (favorite) " 已收藏" else " 加入收藏")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title, authors, tags, collections, series, sortIndex, favorite, domain)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
