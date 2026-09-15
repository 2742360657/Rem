package dev.susnowy.gallery.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.ui.GalleryViewModel
import dev.susnowy.gallery.ui.components.label
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetail(
    item: MediaItem,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.saveMetadata(
                            item,
                            item.displayTitle,
                            item.authors.joinToString(),
                            item.tags.joinToString(),
                            item.collections.joinToString(),
                            item.series?.title.orEmpty(),
                            item.series?.sortIndex?.toString().orEmpty(),
                            !item.favorite,
                        )
                    }) {
                        Icon(
                            if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "收藏",
                        )
                    }
                    IconButton(onClick = { showEditor = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "编辑元数据")
                    }
                    IconButton(onClick = { confirmTrash = true }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "移入回收站")
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
            when (item.kind) {
                MediaKind.IMAGE_SET -> ImageSetReader(item, viewModel, Modifier.weight(1f))
                MediaKind.VIDEO, MediaKind.PHOTO_VIDEO -> VideoViewer(item, item.uri, viewModel, Modifier.weight(1f))
                MediaKind.IMAGE, MediaKind.PHOTO, MediaKind.LIVE_PHOTO ->
                    ZoomableImage(item.uri, item.displayTitle, Modifier.weight(1f))
            }
            MetadataSummary(item)
        }
    }

    if (showEditor) {
        MetadataEditor(
            item = item,
            onDismiss = { showEditor = false },
            onSave = { title, authors, tags, collections, series, sortIndex, favorite ->
                viewModel.saveMetadata(
                    item,
                    title,
                    authors,
                    tags,
                    collections,
                    series,
                    sortIndex,
                    favorite,
                )
                showEditor = false
            },
        )
    }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("移入回收站？") },
            text = { Text("仅添加逻辑删除标记，真实文件不会移动或删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTrashed(item, true)
                    confirmTrash = false
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ZoomableImage(uri: String, description: String, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + offsetChange
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .transformable(transformState),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = Uri.parse(uri),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ImageSetReader(
    item: MediaItem,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
) {
    val pages by produceState<Result<List<ImagePage>>?>(null, item.id, item.modifiedAt) {
        value = runCatching { viewModel.pages(item) }
    }
    val savedProgress by produceState(initialValue = 0, item.id) {
        value = viewModel.progress(item)?.page ?: 0
    }
    when (val current = pages) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> current.fold(
            onSuccess = { loadedPages ->
                if (loadedPages.isEmpty()) {
                    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有可读取的图片页")
                    }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(loadedPages.size, savedProgress) {
                        if (savedProgress in loadedPages.indices) listState.scrollToItem(savedProgress)
                    }
                    LaunchedEffect(listState, item.id) {
                        snapshotFlow { listState.firstVisibleItemIndex }
                            .distinctUntilChanged()
                            .debounce(500)
                            .collect { page ->
                                viewModel.saveProgress(
                                    item,
                                    page = page,
                                    finished = page >= loadedPages.lastIndex,
                                )
                            }
                    }
                    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
                        items(loadedPages.size, key = { index -> loadedPages[index].name }) { index ->
                            val page = loadedPages[index]
                            if (page.uri != null) {
                                AsyncImage(
                                    model = page.uri,
                                    contentDescription = "第 ${index + 1} 页",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (page.archiveEntry != null) {
                                ArchivePage(item, page.archiveEntry, viewModel)
                            }
                            Text(
                                "${index + 1} / ${loadedPages.size}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                            )
                        }
                    }
                }
            },
            onFailure = { error ->
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("读取失败：${error.message.orEmpty()}")
                }
            },
        )
    }
}

@Composable
private fun ArchivePage(item: MediaItem, entryName: String, viewModel: GalleryViewModel) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, item.id, entryName, maxWidth) {
            value = viewModel.archiveBitmap(item, entryName, 1440, 3200)
        }
        if (bitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = entryName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun VideoViewer(
    item: MediaItem,
    uri: String,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(item.id) { ExoPlayer.Builder(context).build() }
    val saved by produceState<Long?>(null, item.id) {
        value = viewModel.progress(item)?.positionMs ?: 0
    }
    LaunchedEffect(player, uri, saved) {
        val position = saved ?: return@LaunchedEffect
        player.setMediaItem(PlayerMediaItem.fromUri(Uri.parse(uri)))
        player.prepare()
        if (position > 0) player.seekTo(position)
        player.playWhenReady = true
    }
    DisposableEffect(player) {
        onDispose {
            val position = player.currentPosition.coerceAtLeast(0)
            val duration = player.duration.coerceAtLeast(0)
            viewModel.saveProgress(
                item,
                positionMs = position,
                finished = duration > 0 && position >= duration - 5_000,
            )
            player.release()
        }
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },
        modifier = modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color.Black),
    )
}

@Composable
private fun MetadataSummary(item: MediaItem) {
    Surface(tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("${item.kind.label()} · ${item.relativePath}", style = MaterialTheme.typography.labelMedium)
            val details = buildList {
                if (item.authors.isNotEmpty()) add("作者：${item.authors.joinToString()}")
                if (item.tags.isNotEmpty()) add("标签：${item.tags.joinToString()}")
                if (item.collections.isNotEmpty()) add("Collection：${item.collections.joinToString()}")
                item.series?.let { add("系列：${it.title} · ${it.sortIndex}") }
            }
            details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun MetadataEditor(
    item: MediaItem,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, Boolean) -> Unit,
) {
    var title by remember(item.id) { mutableStateOf(item.displayTitle) }
    var authors by remember(item.id) { mutableStateOf(item.authors.joinToString()) }
    var tags by remember(item.id) { mutableStateOf(item.tags.joinToString()) }
    var collections by remember(item.id) { mutableStateOf(item.collections.joinToString()) }
    var series by remember(item.id) { mutableStateOf(item.series?.title.orEmpty()) }
    var sortIndex by remember(item.id) { mutableStateOf(item.series?.sortIndex?.toString().orEmpty()) }
    var favorite by remember(item.id) { mutableStateOf(item.favorite) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑便携元数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("显示标题") }, singleLine = true)
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
                onSave(title, authors, tags, collections, series, sortIndex, favorite)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
