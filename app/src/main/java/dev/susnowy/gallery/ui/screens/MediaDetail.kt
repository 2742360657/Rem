package dev.susnowy.gallery.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.SingletonImageLoader
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.media.comicPreloadOrder
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.ui.GalleryViewModel
import dev.susnowy.gallery.ui.components.MetadataEditor
import dev.susnowy.gallery.ui.components.MediaGrid
import dev.susnowy.gallery.ui.components.MediaThumbnail
import dev.susnowy.gallery.ui.components.RightSidePanel
import dev.susnowy.gallery.ui.components.typeLabel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetail(
    item: MediaItem,
    browsingItems: List<MediaItem> = listOf(item),
    libraryWorks: List<MediaItem> = emptyList(),
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val mixedMembers = remember(item.id, browsingItems) {
        if (item.kind != MediaKind.IMAGE_SET || item.sourceKind != SourceKind.DIRECTORY) {
            emptyList()
        } else {
            browsingItems.filter { candidate ->
                candidate.libraryId == item.libraryId &&
                    (candidate.id == item.id ||
                        candidate.kind == MediaKind.VIDEO &&
                        candidate.relativePath.substringBeforeLast('/', "") == item.relativePath)
            }.distinctBy(MediaItem::id)
        }
    }
    if (mixedMembers.size > 1) {
        MixedMediaGroupDetail(
            primary = item,
            members = mixedMembers,
            viewModel = viewModel,
            onBack = onBack,
        )
        return
    }
    if (item.kind == MediaKind.IMAGE_SET) {
        ImageSetWorkDetail(
            item = item,
            libraryWorks = libraryWorks,
            viewModel = viewModel,
            onBack = onBack,
        )
        return
    }
    BackHandler(onBack = onBack)
    val sequence = remember(browsingItems, item.libraryId) {
        browsingItems.filter { it.libraryId == item.libraryId && !it.trashed }
            .ifEmpty { listOf(item) }
    }
    val initialPage = sequence.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { sequence.size }
    val currentItem = sequence.getOrNull(pagerState.currentPage) ?: item
    var showEditor by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var showDerivePage by remember { mutableStateOf(false) }

    LaunchedEffect(item.id, sequence) {
        val target = sequence.indexOfFirst { it.id == item.id }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState, sequence) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> sequence.getOrNull(page)?.let(viewModel::selectDetailItem) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentItem.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (currentItem.kind in setOf(
                            MediaKind.IMAGE_SET,
                            MediaKind.IMAGE,
                            MediaKind.PHOTO,
                            MediaKind.LIVE_PHOTO,
                        )
                    ) {
                        IconButton(onClick = {
                            if (currentItem.kind == MediaKind.IMAGE_SET) showDerivePage = true
                            else viewModel.deriveImage(currentItem)
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "复制派生")
                        }
                    }
                    IconButton(onClick = {
                        viewModel.setFavorite(currentItem, !currentItem.favorite)
                    }) {
                        Icon(
                            if (currentItem.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
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
            when {
                sequence.size > 1 ->
                    MediaPager(sequence, pagerState, viewModel, Modifier.weight(1f))
                currentItem.kind == MediaKind.VIDEO || currentItem.kind == MediaKind.PHOTO_VIDEO ->
                    VideoViewer(currentItem, currentItem.uri, viewModel, Modifier.weight(1f))
                else -> ZoomableImage(currentItem, viewModel, Modifier.weight(1f))
            }
            MetadataSummary(currentItem)
        }
    }

    if (showEditor) {
        MetadataEditor(
            item = currentItem,
            onDismiss = { showEditor = false },
            onSave = { title, authors, tags, collections, series, favorite, domain ->
                viewModel.saveMetadata(
                    currentItem,
                    title,
                    authors,
                    tags,
                    collections,
                    series,
                    favorite,
                    domain,
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
                    viewModel.setTrashed(currentItem, true)
                    confirmTrash = false
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("取消") } },
        )
    }
    if (showDerivePage) {
        var pageText by remember { mutableStateOf("1") }
        AlertDialog(
            onDismissRequest = { showDerivePage = false },
            title = { Text("复制漫画页为普通图片") },
            text = {
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(6) },
                    label = { Text("页码（1–${currentItem.pageCount ?: "?"}）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pageText.toIntOrNull()?.let { viewModel.derivePage(currentItem, it) }
                        showDerivePage = false
                    },
                    enabled = pageText.toIntOrNull()?.let {
                        it >= 1 && (currentItem.pageCount == null || it <= currentItem.pageCount)
                    } == true,
                ) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showDerivePage = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixedMediaGroupDetail(
    primary: MediaItem,
    members: List<MediaItem>,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    var openedId by rememberSaveable(primary.id) { mutableStateOf<String?>(null) }
    val opened = members.firstOrNull { it.id == openedId }
    if (opened != null) {
        MediaDetail(
            item = opened,
            browsingItems = listOf(opened),
            viewModel = viewModel,
            onBack = { openedId = null },
        )
        return
    }
    BackHandler(onBack = onBack)
    val videoCount = members.count { it.kind == MediaKind.VIDEO }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(primary.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
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
            Text(
                "同一目录 · ${primary.pageCount ?: 0} 张图片 · $videoCount 个视频",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            MediaGrid(
                items = members,
                viewModel = viewModel,
                onOpen = { openedId = it.id },
                modifier = Modifier.weight(1f),
                supportingText = { member ->
                    if (member.id == primary.id) "浏览全部图片" else "播放组内视频"
                },
            )
        }
    }
}

@Composable
private fun ImageSetWorkDetail(
    item: MediaItem,
    libraryWorks: List<MediaItem>,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    var reading by rememberSaveable(item.id) { mutableStateOf(false) }
    var comparing by rememberSaveable(item.id) { mutableStateOf(false) }
    if (reading) {
        BackHandler { reading = false }
        ImageSetReaderScreen(item = item, viewModel = viewModel, onBack = { reading = false })
    } else {
        BackHandler(onBack = onBack)
        ImageSetOverview(
            item = item,
            viewModel = viewModel,
            onBack = onBack,
            onRead = { reading = true },
            onCompare = { comparing = true },
        )
    }
    if (comparing) {
        EditionCompareDialog(
            left = item,
            candidates = libraryWorks.filter { candidate ->
                candidate.id != item.id &&
                    candidate.kind == MediaKind.IMAGE_SET &&
                    !candidate.trashed
            },
            viewModel = viewModel,
            onDismiss = { comparing = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageSetOverview(
    item: MediaItem,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onCompare: () -> Unit,
) {
    val progress by produceState<PlaybackProgress?>(initialValue = null, item.id, item.modifiedAt) {
        value = viewModel.progress(item)
    }
    var showTools by rememberSaveable(item.id) { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    val pageCount = item.pageCount ?: 0
    val readPage = progress?.page?.coerceAtLeast(0) ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("作品详情", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回作品列表")
                    }
                },
                actions = {
                    IconButton(onClick = onCompare) {
                        Icon(Icons.Rounded.Compare, contentDescription = "比较版本")
                    }
                    IconButton(onClick = { showEditor = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "编辑作品信息")
                    }
                    IconButton(onClick = { showTools = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "作品工具")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            MediaThumbnail(
                item = item,
                viewModel = viewModel,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            )
            Text(item.displayTitle, style = MaterialTheme.typography.headlineSmall)
            item.originalTitle?.takeIf { it.isNotBlank() && it != item.displayTitle }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.authors.isNotEmpty()) {
                Text("作者 · ${item.authors.joinToString(" · ")}", style = MaterialTheme.typography.titleSmall)
            }
            if (item.tags.isNotEmpty()) {
                Text(
                    item.tags.joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                buildString {
                    append(if (pageCount > 0) "$pageCount 页" else "页数待扫描")
                    item.series?.title?.let { append(" · $it") }
                    if (progress != null && pageCount > 0) append(" · 已读 ${readPage + 1}/$pageCount")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text(if (progress == null || readPage == 0) " 开始阅读" else " 继续阅读 · 第 ${readPage + 1} 页")
            }
            Text(
                "点按进入沉浸阅读；在阅读页长按任意页面可打开当前页操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    RightSidePanel(
        visible = showTools,
        title = "作品工具",
        onDismiss = { showTools = false },
    ) {
        Text(item.displayTitle, style = MaterialTheme.typography.titleMedium)
        Text(item.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(
            onClick = {
                showTools = false
                onRead()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Text(" 开始 / 继续阅读")
        }
        FilledTonalButton(
            onClick = {
                showTools = false
                showEditor = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Edit, contentDescription = null)
            Text(" 编辑标题、作者与标签")
        }
        FilledTonalButton(
            onClick = {
                viewModel.setFavorite(item, !item.favorite)
                showTools = false
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = null,
            )
            Text(if (item.favorite) " 取消收藏" else " 加入收藏")
        }
        TextButton(
            onClick = {
                showTools = false
                confirmTrash = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
            Text(" 移入回收站")
        }
        Text(
            "底层位置：Library / ${item.relativePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showEditor) {
        MetadataEditor(
            item = item,
            onDismiss = { showEditor = false },
            onSave = { title, authors, tags, collections, series, favorite, domain ->
                viewModel.saveMetadata(item, title, authors, tags, collections, series, favorite, domain)
                showEditor = false
            },
        )
    }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("移入回收站？") },
            text = { Text("仅添加逻辑删除标记，真实漫画目录或压缩包不会移动或删除。") },
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
private fun MediaPager(
    items: List<MediaItem>,
    pagerState: PagerState,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
) {
    var zoomedPage by remember(items) { mutableStateOf<Int?>(null) }
    LaunchedEffect(pagerState.currentPage) {
        if (zoomedPage != pagerState.currentPage) zoomedPage = null
    }
    Box(modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            key = { page -> items[page].id },
            userScrollEnabled = zoomedPage != pagerState.currentPage,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageItem = items[page]
            when (pageItem.kind) {
                MediaKind.VIDEO, MediaKind.PHOTO_VIDEO ->
                    VideoViewer(
                        pageItem,
                        pageItem.uri,
                        viewModel,
                        Modifier.fillMaxSize(),
                        active = page == pagerState.currentPage,
                    )
                MediaKind.IMAGE, MediaKind.PHOTO, MediaKind.LIVE_PHOTO ->
                    ZoomableImage(
                        item = pageItem,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        onZoomingChanged = { zooming ->
                            if (zooming) zoomedPage = page
                            else if (zoomedPage == page) zoomedPage = null
                        },
                    )
                MediaKind.IMAGE_SET -> Unit
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        ) {
            Text(
                "${pagerState.currentPage + 1} / ${items.size}",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    item: MediaItem,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
    onZoomingChanged: (Boolean) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomingChanged(scale > 1.01f) }
    DisposableEffect(Unit) {
        onDispose { onZoomingChanged(false) }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(item.uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        val shouldTransform = pointerCount >= 2 || scale > 1.01f
                        if (shouldTransform) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            val pan = event.calculatePan()
                            scale = nextScale
                            offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val oversizedResult by produceState<Result<android.graphics.Bitmap?>?>(
            initialValue = null,
            item.id,
            item.modifiedAt,
        ) {
            value = runCatching { viewModel.oversizedBitmap(item, item.relativePath) }
        }
        val oversizedBitmap = oversizedResult?.getOrNull()
        val imageModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        if (oversizedResult == null) {
            CircularProgressIndicator()
        } else if (oversizedBitmap != null) {
            Image(
                bitmap = oversizedBitmap!!.asImageBitmap(),
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )
        } else {
            AsyncImage(
                model = item.uri.toUri(),
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )
        }
    }
}

@Composable
private fun ImageSetReaderScreen(
    item: MediaItem,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val pages by produceState<Result<List<ImagePage>>?>(null, item.id, item.modifiedAt, item.coverPath) {
        value = runCatching { viewModel.pages(item) }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var currentPage by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var showDerivePage by remember { mutableStateOf(false) }
    var showOrderEditor by remember { mutableStateOf(false) }
    var showPageTools by remember { mutableStateOf(false) }
    val loadedPages = pages?.getOrNull().orEmpty()

    ImmersiveSystemBars(controlsVisible)
    LaunchedEffect(controlsVisible, showEditor, confirmTrash, showDerivePage, showOrderEditor, showPageTools) {
        if (controlsVisible && !showEditor && !confirmTrash && !showDerivePage && !showOrderEditor && !showPageTools) {
            delay(3_000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val current = pages) {
            null -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> current.fold(
                onSuccess = { result ->
                    if (result.isEmpty()) {
                        Text("没有可读取的图片页", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    } else {
                        ImageSetReader(
                            item = item,
                            pages = result,
                            viewModel = viewModel,
                            onToggleControls = { controlsVisible = !controlsVisible },
                            onPageChanged = { currentPage = it },
                            onLongPressPage = { page ->
                                currentPage = page
                                controlsVisible = true
                                showPageTools = true
                            },
                        )
                    }
                },
                onFailure = { error ->
                    Text(
                        "读取失败：${error.message.orEmpty()}",
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                },
            )
        }

        if (controlsVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White)
                    }
                    Text(
                        item.displayTitle,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showDerivePage = true }, enabled = loadedPages.isNotEmpty()) {
                        Icon(Icons.Rounded.ContentCopy, "复制当前页", tint = Color.White)
                    }
                    if (item.sourceKind == SourceKind.DIRECTORY) {
                        IconButton(onClick = { showOrderEditor = true }, enabled = loadedPages.size >= 2) {
                            Icon(Icons.Rounded.SwapVert, "调整页序", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { showEditor = true }) {
                        Icon(Icons.Rounded.Edit, "编辑元数据", tint = Color.White)
                    }
                    IconButton(onClick = { confirmTrash = true }) {
                        Icon(Icons.Rounded.DeleteOutline, "移入回收站", tint = Color.White)
                    }
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(14.dp),
            ) {
                Text(
                    if (loadedPages.isEmpty()) "—" else "${currentPage + 1} / ${loadedPages.size}",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    RightSidePanel(
        visible = showPageTools,
        title = "第 ${currentPage + 1} 页",
        onDismiss = { showPageTools = false },
    ) {
        Text(item.displayTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            loadedPages.getOrNull(currentPage)?.name.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = {
                viewModel.derivePage(item, currentPage + 1)
                showPageTools = false
            },
            enabled = loadedPages.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
            Text(" 复制当前页到分类图片")
        }
        FilledTonalButton(
            onClick = {
                showPageTools = false
                showEditor = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Edit, contentDescription = null)
            Text(" 编辑作品信息")
        }
        if (item.sourceKind == SourceKind.DIRECTORY) {
            FilledTonalButton(
                onClick = {
                    showPageTools = false
                    showOrderEditor = true
                },
                enabled = loadedPages.size >= 2,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.SwapVert, contentDescription = null)
                Text(" 调整页面顺序")
            }
        }
        Text(
            "短按页面显示或隐藏阅读控件；长按页面打开此菜单。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showEditor) {
        MetadataEditor(
            item = item,
            onDismiss = { showEditor = false },
            onSave = { title, authors, tags, collections, series, favorite, domain ->
                viewModel.saveMetadata(item, title, authors, tags, collections, series, favorite, domain)
                showEditor = false
            },
        )
    }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("移入回收站？") },
            text = { Text("仅添加逻辑删除标记，真实漫画目录或压缩包不会移动或删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTrashed(item, true)
                    confirmTrash = false
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("取消") } },
        )
    }
    if (showDerivePage) {
        var pageText by remember(currentPage, item.id) { mutableStateOf((currentPage + 1).toString()) }
        AlertDialog(
            onDismissRequest = { showDerivePage = false },
            title = { Text("复制漫画页为普通图片") },
            text = {
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(6) },
                    label = { Text("页码（1–${loadedPages.size}）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pageText.toIntOrNull()?.let { viewModel.derivePage(item, it) }
                        showDerivePage = false
                    },
                    enabled = pageText.toIntOrNull()?.let { it in 1..loadedPages.size } == true,
                ) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showDerivePage = false }) { Text("取消") } },
        )
    }
    if (showOrderEditor) {
        ImageSetOrderDialog(
            item = item,
            pages = loadedPages,
            viewModel = viewModel,
            onDismiss = { showOrderEditor = false },
            onSave = { ordered ->
                viewModel.reorderImageSet(item, ordered)
                showOrderEditor = false
            },
        )
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ImageSetReader(
    item: MediaItem,
    pages: List<ImagePage>,
    viewModel: GalleryViewModel,
    onToggleControls: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onLongPressPage: (Int) -> Unit,
) {
    val savedProgress by produceState(initialValue = 0, item.id) {
        value = viewModel.progress(item)?.page ?: 0
    }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    var zoomedPage by remember(item.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(pages.size, savedProgress) {
        if (savedProgress in pages.indices) listState.scrollToItem(savedProgress)
    }
    LaunchedEffect(listState, item.id, pages.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { page ->
                onPageChanged(page)
                viewModel.saveProgress(item, page = page, finished = page >= pages.lastIndex)
            }
    }
    LaunchedEffect(listState, item.id, item.modifiedAt, pages) {
        var previousFirst = listState.firstVisibleItemIndex
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: listState.firstVisibleItemIndex
            val last = visible.lastOrNull()?.index ?: first
            first to last
        }
            .distinctUntilChanged()
            .collectLatest { (first, last) ->
                val scrollingForward = first >= previousFirst
                previousFirst = first
                val preload = comicPreloadOrder(first, last, pages.size, scrollingForward)
                val permits = Semaphore(2)
                coroutineScope {
                    preload.map { index ->
                        async {
                            permits.withPermit {
                                val page = pages[index]
                                when {
                                    page.uri != null -> imageLoader.execute(
                                        comicPageImageRequest(context, item, page),
                                    )
                                    page.archiveEntry != null -> viewModel.archiveBitmap(
                                        item,
                                        page.archiveEntry,
                                        COMIC_PAGE_TARGET_WIDTH,
                                        COMIC_PAGE_TARGET_HEIGHT,
                                        archivePath = page.relativePath,
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
    }
    LazyColumn(
        state = listState,
        userScrollEnabled = zoomedPage == null,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(pages.size, key = { index ->
            pages[index].relativePath ?: pages[index].archiveEntry ?: pages[index].name
        }) { index ->
            val page = pages[index]
            val imageRequest = remember(
                context,
                item.id,
                item.modifiedAt,
                page.uri,
                page.relativePath,
            ) {
                page.uri?.let { comicPageImageRequest(context, item, page) }
            }
            ZoomableComicPage(
                pageKey = page.relativePath ?: page.archiveEntry ?: page.name,
                onTap = onToggleControls,
                onLongPress = { onLongPressPage(index) },
                onZoomingChanged = { zooming ->
                    if (zooming) zoomedPage = index else if (zoomedPage == index) zoomedPage = null
                },
            ) {
                when {
                    imageRequest != null -> SubcomposeAsyncImage(
                        model = imageRequest,
                        contentDescription = "第 ${index + 1} 页",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(color = Color.White) }
                        },
                        error = {
                            ComicPageLoadError(message = "第 ${index + 1} 页加载失败")
                        },
                        success = { SubcomposeAsyncImageContent() },
                    )
                    page.archiveEntry != null -> ArchiveComicPage(
                        item = item,
                        entryName = page.archiveEntry,
                        archivePath = page.relativePath,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

private fun comicPageImageRequest(
    context: Context,
    item: MediaItem,
    page: ImagePage,
): ImageRequest {
    val pageIdentity = page.relativePath ?: page.uri ?: page.name
    val cacheKey = "comic:${item.id}:${item.modifiedAt}:$pageIdentity"
    return ImageRequest.Builder(context)
        .data(requireNotNull(page.uri))
        .size(COMIC_PAGE_TARGET_WIDTH, COMIC_PAGE_TARGET_HEIGHT)
        .precision(Precision.INEXACT)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .build()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableComicPage(
    pageKey: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onZoomingChanged: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    var scale by remember(pageKey) { mutableFloatStateOf(1f) }
    var offset by remember(pageKey) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomingChanged(scale > 1.01f) }
    DisposableEffect(pageKey) { onDispose { onZoomingChanged(false) } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .pointerInput(pageKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        if (pointerCount >= 2 || scale > 1.01f) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            val nextOffset = if (nextScale <= 1.01f) Offset.Zero else offset + event.calculatePan()
                            scale = nextScale
                            offset = Offset(
                                nextOffset.x.coerceIn(-size.width * (scale - 1f), size.width * (scale - 1f)),
                                nextOffset.y.coerceIn(-size.height * (scale - 1f), size.height * (scale - 1f)),
                            )
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) { content() }
    }
}

@Composable
private fun ArchiveComicPage(
    item: MediaItem,
    entryName: String,
    archivePath: String?,
    viewModel: GalleryViewModel,
) {
    var retry by remember(item.id, entryName) { mutableIntStateOf(0) }
    Box(modifier = Modifier.fillMaxWidth()) {
        val result by produceState<Result<android.graphics.Bitmap?>?>(
            null,
            item.id,
            item.modifiedAt,
            entryName,
            archivePath,
            retry,
        ) {
            value = runCatching {
                viewModel.archiveBitmap(
                    item,
                    entryName,
                    COMIC_PAGE_TARGET_WIDTH,
                    COMIC_PAGE_TARGET_HEIGHT,
                    archivePath = archivePath,
                )
            }
        }
        val bitmap = result?.getOrNull()
        when {
            result == null -> Box(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Color.White) }
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entryName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> ComicPageLoadError(
                message = "此页无法解码",
                onRetry = { retry++ },
            )
        }
    }
}

@Composable
private fun ComicPageLoadError(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(24.dp),
    ) {
        Text(message, color = Color.White)
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("重试", color = Color.White) }
        }
    }
}

private const val COMIC_PAGE_TARGET_WIDTH = 1_440
private const val COMIC_PAGE_TARGET_HEIGHT = 6_000

@Composable
private fun ImageSetOrderDialog(
    item: MediaItem,
    pages: List<ImagePage>,
    viewModel: GalleryViewModel,
    onDismiss: () -> Unit,
    onSave: (List<ImagePage>) -> Unit,
) {
    var ordered by remember(item.id, pages) { mutableStateOf(pages) }
    var relocatingIndex by remember { mutableStateOf<Int?>(null) }
    var targetPage by remember { mutableStateOf("") }
    fun move(from: Int, to: Int) {
        if (from !in ordered.indices || to !in ordered.indices || from == to) return
        ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Column(Modifier.weight(1f)) {
                        Text("调整漫画页序", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "保存后会按当前顺序把底层文件重新编号；异常中断可自动回滚。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onSave(ordered) }, enabled = ordered != pages) {
                        Text("保存并编号")
                    }
                }
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        top = 8.dp,
                        end = 12.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = ordered,
                        key = { _, page -> page.relativePath ?: page.archiveEntry ?: page.name },
                    ) { index, page ->
                        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                            ) {
                                ReorderPageThumbnail(item, page, viewModel)
                                Column(Modifier.weight(1f)) {
                                    Text(page.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = {
                                        relocatingIndex = index
                                        targetPage = (index + 1).toString()
                                    }) { Text("第 ${index + 1} 页 · 移动到…") }
                                }
                                IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                                    Icon(Icons.Rounded.ArrowUpward, "上移")
                                }
                                IconButton(
                                    onClick = { move(index, index + 1) },
                                    enabled = index < ordered.lastIndex,
                                ) { Icon(Icons.Rounded.ArrowDownward, "下移") }
                            }
                        }
                    }
                }
            }
        }
    }
    relocatingIndex?.let { from ->
        AlertDialog(
            onDismissRequest = { relocatingIndex = null },
            title = { Text("移动页面") },
            text = {
                OutlinedTextField(
                    value = targetPage,
                    onValueChange = { targetPage = it.filter(Char::isDigit).take(6) },
                    label = { Text("目标页码（1–${ordered.size}）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        targetPage.toIntOrNull()?.let { move(from, it - 1) }
                        relocatingIndex = null
                    },
                    enabled = targetPage.toIntOrNull()?.let { it in 1..ordered.size } == true,
                ) { Text("移动") }
            },
            dismissButton = { TextButton(onClick = { relocatingIndex = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ReorderPageThumbnail(item: MediaItem, page: ImagePage, viewModel: GalleryViewModel) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(width = 76.dp, height = 96.dp),
    ) {
        when {
            page.uri != null -> AsyncImage(
                model = page.uri,
                contentDescription = page.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            page.archiveEntry != null -> {
                val bitmap by produceState<android.graphics.Bitmap?>(
                    null,
                    item.id,
                    page.archiveEntry,
                    page.relativePath,
                ) {
                    value = viewModel.archiveBitmap(
                        item,
                        page.archiveEntry,
                        320,
                        420,
                        archivePath = page.relativePath,
                    )
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = page.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveSystemBars(controlsVisible: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(view, activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldLightStatus = controller?.isAppearanceLightStatusBars
        val oldLightNavigation = controller?.isAppearanceLightNavigationBars
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            oldLightStatus?.let { controller?.isAppearanceLightStatusBars = it }
            oldLightNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
    LaunchedEffect(controlsVisible, activity, view) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (controlsVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun VideoViewer(
    item: MediaItem,
    uri: String,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val context = LocalContext.current
    val player = remember(item.id) { ExoPlayer.Builder(context).build() }
    val saved by produceState<Long?>(null, item.id) {
        value = viewModel.progress(item)?.positionMs ?: 0
    }
    LaunchedEffect(player, uri, saved) {
        val position = saved ?: return@LaunchedEffect
        player.setMediaItem(PlayerMediaItem.fromUri(uri.toUri()))
        player.prepare()
        if (position > 0) player.seekTo(position)
    }
    LaunchedEffect(player, active) {
        player.playWhenReady = active
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
            Text("${item.typeLabel()} · ${item.domain.displayLabel()}", style = MaterialTheme.typography.labelMedium)
            Text("位置：${item.relativePath}", style = MaterialTheme.typography.bodySmall)
            val time = item.capturedAt ?: item.modifiedAt
            if (time > 0) {
                Text(
                    "${if (item.capturedAt != null) "拍摄" else "修改"}时间：${DateFormat.getDateTimeInstance().format(Date(time))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (item.latitude != null && item.longitude != null) {
                Text(
                    "拍摄地点：%.5f, %.5f".format(item.latitude, item.longitude),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val details = buildList {
                if (item.authors.isNotEmpty()) add("作者：${item.authors.joinToString()}")
                if (item.tags.isNotEmpty()) add("标签：${item.tags.joinToString()}")
                if (item.collections.isNotEmpty()) add("Collection：${item.collections.joinToString()}")
                item.series?.let { add(it.displaySummary()) }
            }
            details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun SeriesRef.displaySummary(): String = buildString {
    append("系列：$title")
    val position = buildList {
        sortIndex?.let { add("顺序 $it") }
        volume?.let { add("卷 $it") }
        chapter?.let { add("章 $it") }
        season?.let { add("季 $it") }
        episode?.let { add("集 $it") }
    }
    if (position.isNotEmpty()) append(" · ${position.joinToString(" · ")}")
}

private fun MediaDomain.displayLabel(): String = when (this) {
    MediaDomain.ALBUM -> "相册"
    MediaDomain.CLASSIFIED -> "图片 / 视频"
    MediaDomain.WORKS -> "漫画 / 动漫"
}
