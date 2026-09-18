package dev.susnowy.gallery.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import dev.susnowy.gallery.ui.components.ZoomPlacement
import dev.susnowy.gallery.ui.components.Zoomable
import dev.susnowy.gallery.ui.components.rememberZoomState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
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
import dev.susnowy.gallery.ui.chapterEndReached
import dev.susnowy.gallery.ui.chapterEndState
import dev.susnowy.gallery.ui.resumePageIndex
import dev.susnowy.gallery.ui.components.MetadataEditor
import dev.susnowy.gallery.ui.components.MediaGrid
import dev.susnowy.gallery.ui.components.EditionCompareDialog
import dev.susnowy.gallery.ui.components.MediaThumbnail
import dev.susnowy.gallery.ui.components.RightSidePanel
import dev.susnowy.gallery.ui.components.typeLabel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    readerQueue: List<MediaItem> = emptyList(),
    autoAdvanceChapters: Boolean = true,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    showMixedGroup: Boolean = true,
    onVisibleItem: (MediaItem) -> Unit = viewModel::selectDetailItem,
) {
    val mixedMembers = remember(item.id, libraryWorks, showMixedGroup) {
        if (!showMixedGroup || item.kind != MediaKind.IMAGE_SET || item.sourceKind != SourceKind.DIRECTORY) {
            emptyList()
        } else {
            libraryWorks.filter { candidate ->
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
            libraryWorks = libraryWorks,
            readerQueue = readerQueue,
            autoAdvanceChapters = autoAdvanceChapters,
            viewModel = viewModel,
            onBack = onBack,
        )
        return
    }
    if (item.kind == MediaKind.IMAGE_SET) {
        // The next chapter is only meaningful inside a reading queue: opening a single Work from
        // a search result must not silently turn into a series read.
        val chapterIndex = readerQueue.indexOfFirst { it.id == item.id }
        val nextChapter = readerQueue.getOrNull(chapterIndex + 1)
            ?.takeIf { chapterIndex >= 0 && it.id != item.id }
        ImageSetWorkDetail(
            item = item,
            libraryWorks = libraryWorks,
            nextChapter = nextChapter,
            autoAdvanceChapters = autoAdvanceChapters,
            viewModel = viewModel,
            onBack = onBack,
            onOpenNextChapter = { next -> viewModel.openChapter(next, readerQueue) },
        )
        return
    }
    if ((item.kind == MediaKind.VIDEO || item.kind == MediaKind.PHOTO_VIDEO) && readerQueue.size > 1) {
        val next = dev.susnowy.gallery.ui.nextSeriesItem(item, readerQueue)
        var ended by remember(item.libraryId, item.id) { mutableStateOf(false) }
        BackHandler(onBack = onBack)
        Scaffold(topBar = {
            TopAppBar(title = { Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回系列") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                VideoViewer(item, item.uri, viewModel, Modifier.weight(1f), onEnded = {
                    ended = true
                    if (autoAdvanceChapters && next != null) viewModel.openChapter(next, readerQueue)
                })
                dev.susnowy.gallery.ui.components.VideoSeriesControls(next, ended,
                    onNext = { next?.let { viewModel.openChapter(it, readerQueue) } }, onBack = onBack)
            }
        }
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
            .collect { page -> sequence.getOrNull(page)?.let(onVisibleItem) }
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
    libraryWorks: List<MediaItem>,
    readerQueue: List<MediaItem>,
    autoAdvanceChapters: Boolean,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    var openedId by rememberSaveable(primary.id) { mutableStateOf<String?>(null) }
    val opened = members.firstOrNull { it.id == openedId }
    if (opened != null) {
        // The Library list has to travel with the item: without it the detail screen cannot
        // offer "compare versions", because it would have no other source to pick from.
        MediaDetail(
            item = opened,
            browsingItems = listOf(opened),
            libraryWorks = libraryWorks,
            readerQueue = readerQueue,
            autoAdvanceChapters = autoAdvanceChapters,
            viewModel = viewModel,
            onBack = { openedId = null },
            showMixedGroup = false,
            onVisibleItem = {},
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
    nextChapter: MediaItem?,
    autoAdvanceChapters: Boolean,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onOpenNextChapter: (MediaItem) -> Unit,
) {
    val progressRevision by viewModel.progressRevision.collectAsState()
    val storedProgress by produceState<PlaybackProgress?>(
        initialValue = null,
        item.id,
        item.modifiedAt,
        progressRevision,
    ) {
        value = viewModel.progress(item)
    }
    var reading by rememberSaveable(item.id) { mutableStateOf(false) }
    var restartChapter by rememberSaveable(item.id) { mutableStateOf(false) }
    var comparing by rememberSaveable(item.id) { mutableStateOf(false) }
    // The resume page is resolved here, once, with the same rule the chapter list and the detail
    // page use; the reader itself only follows it.
    val readerStartPage = resumePageIndex(
        pageCount = item.pageCount ?: 0,
        progress = storedProgress,
        restart = restartChapter,
    )
    if (reading) {
        BackHandler { reading = false }
        ImageSetReaderScreen(
            item = item,
            viewModel = viewModel,
            onBack = { reading = false },
            nextChapter = nextChapter,
            autoAdvance = autoAdvanceChapters,
            startPage = readerStartPage,
            onOpenNextChapter = { next ->
                // Hand over to the next chapter and go back to its detail screen, so leaving the
                // reader lands on the chapter that is actually open.
                reading = false
                restartChapter = false
                onOpenNextChapter(next)
            },
        )
    } else {
        BackHandler(onBack = onBack)
        ImageSetOverview(
            item = item,
            nextChapter = nextChapter,
            viewModel = viewModel,
            onBack = onBack,
            onRead = { restart ->
                restartChapter = restart
                reading = true
            },
            onCompare = { comparing = true },
            onOpenNextChapter = onOpenNextChapter,
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
    nextChapter: MediaItem?,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onRead: (restart: Boolean) -> Unit,
    onCompare: () -> Unit,
    onOpenNextChapter: (MediaItem) -> Unit,
) {
    val progressRevision by viewModel.progressRevision.collectAsState()
    val progress by produceState<PlaybackProgress?>(
        initialValue = null,
        item.id,
        item.modifiedAt,
        progressRevision,
    ) {
        value = viewModel.progress(item)
    }
    var showTools by rememberSaveable(item.id) { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    val pageCount = item.pageCount ?: 0
    val readPage = progress?.page?.coerceAtLeast(0) ?: 0
    // "Not started" is decided by the portable open marker, not by the page index: page 1 is a
    // real position, so a chapter the reader already opened must not offer "开始阅读" again.
    val opened = progress?.opened == true
    val finished = progress?.finished == true

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
                    if (opened && pageCount > 0) append(" · 已读 ${readPage + 1}/$pageCount")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onRead(finished) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text(
                    when {
                        finished -> " 重新阅读本话"
                        !opened -> " 开始阅读"
                        else -> " 继续阅读 · 第 ${readPage + 1} 页"
                    },
                )
            }
            nextChapter?.let { next ->
                FilledTonalButton(
                    onClick = { onOpenNextChapter(next) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = null)
                    Text(" 下一话 · ${next.displayTitle}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
                onRead(false)
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
    val zoom = rememberZoomState()
    var intrinsic by remember(item.id) { mutableStateOf<Size?>(null) }
    LaunchedEffect(zoom.isZoomed) { onZoomingChanged(zoom.isZoomed) }
    DisposableEffect(Unit) { onDispose { onZoomingChanged(false) } }

    val oversizedResult by produceState<Result<android.graphics.Bitmap?>?>(
        initialValue = null,
        item.id,
        item.modifiedAt,
    ) {
        value = runCatching { viewModel.oversizedBitmap(item, item.relativePath) }
    }
    val oversizedBitmap = oversizedResult?.getOrNull()

    Zoomable(
        state = zoom,
        modifier = modifier,
        intrinsicSize = intrinsic,
    ) { contentModifier ->
        when {
            oversizedResult == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Color.White) }

            oversizedBitmap != null -> {
                LaunchedEffect(oversizedBitmap) {
                    intrinsic = Size(
                        oversizedBitmap.width.toFloat(),
                        oversizedBitmap.height.toFloat(),
                    )
                }
                Image(
                    bitmap = oversizedBitmap.asImageBitmap(),
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Fit,
                    modifier = contentModifier,
                )
            }

            else -> AsyncImage(
                model = item.uri.toUri(),
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Fit,
                modifier = contentModifier,
                onState = { state ->
                    state.painter?.intrinsicSize
                        ?.takeIf { it.isSpecified && it.width > 0f && it.height > 0f }
                        ?.let { size -> intrinsic = Size(size.width, size.height) }
                },
            )
        }
    }
}

@Composable
private fun ImageSetReaderScreen(
    item: MediaItem,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    nextChapter: MediaItem? = null,
    autoAdvance: Boolean = false,
    startPage: Int = 0,
    onOpenNextChapter: (MediaItem) -> Unit = {},
) {
    val pageLoad = dev.susnowy.gallery.ui.components.rememberPageLoad(
        listOf(item.libraryId, item.id, item.modifiedAt, item.coverPath),
    ) {
        viewModel.pages(item)
    }
    val pages = pageLoad.result
    var controlsVisible by remember { mutableStateOf(true) }
    var currentPage by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var showDerivePage by remember { mutableStateOf(false) }
    var showOrderEditor by remember { mutableStateOf(false) }
    var showPageTools by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var jumpRequest by remember(item.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    // Keep the saveable list holder outside the asynchronous success branch. During Activity
    // recreation `pages` is briefly null; constructing the holder only after loading completes
    // loses the saved index/offset and lets portable page-level progress pull the reader away
    // from its exact position.
    val readerListState = rememberLazyListState()
    var readerPositionInitialized by rememberSaveable(item.id) { mutableStateOf(false) }
    val loadedPages = pages?.getOrNull().orEmpty()

    ImmersiveSystemBars(controlsVisible)
    LaunchedEffect(controlsVisible, showEditor, confirmTrash, showDerivePage, showOrderEditor, showPageTools, showJump) {
        if (controlsVisible && !showEditor && !confirmTrash && !showDerivePage && !showOrderEditor && !showPageTools && !showJump) {
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
            null -> dev.susnowy.gallery.ui.components.PageLoadingStatus(pageLoad,
                item.sourceKind == SourceKind.ARCHIVE, onBack, Modifier.align(Alignment.Center))
            else -> current.fold(
                onSuccess = { result ->
                    if (result.isEmpty()) {
                        dev.susnowy.gallery.ui.components.PageLoadingStatus(pageLoad,
                            item.sourceKind == SourceKind.ARCHIVE, onBack, Modifier.align(Alignment.Center))
                    } else {
                        ImageSetReader(
                            item = item,
                            pages = result,
                            viewModel = viewModel,
                            onToggleControls = { controlsVisible = !controlsVisible },
                            onPageChanged = { currentPage = it },
                            onProgress = { page, finished -> viewModel.saveProgress(item, page = page, finished = finished) },
                            onLongPressPage = { page ->
                                currentPage = page
                                controlsVisible = true
                                showPageTools = true
                            },
                            nextChapter = nextChapter,
                            autoAdvance = autoAdvance,
                            startPage = startPage,
                            listState = readerListState,
                            jumpRequest = jumpRequest,
                            positionInitialized = readerPositionInitialized,
                            onPositionInitialized = { readerPositionInitialized = true },
                            onFinishChapter = onBack,
                            onOpenNextChapter = onOpenNextChapter,
                        )
                    }
                },
                onFailure = {
                    dev.susnowy.gallery.ui.components.PageLoadingStatus(pageLoad,
                        item.sourceKind == SourceKind.ARCHIVE, onBack, Modifier.align(Alignment.Center))
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
                TextButton(onClick = { showJump = true }, enabled = loadedPages.isNotEmpty()) {
                    Text(if (loadedPages.isEmpty()) "—" else "${currentPage + 1} / ${loadedPages.size} · 跳页", color = Color.White)
                }
            }
        }
    }

    if (showJump && loadedPages.isNotEmpty()) {
        dev.susnowy.gallery.ui.components.PositionJumpDialog(loadedPages.size, currentPage, "页", { showJump = false }) { page ->
            showJump = false
            jumpRequest = page to ((jumpRequest?.second ?: 0) + 1)
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
internal fun ImageSetReader(
    item: MediaItem,
    pages: List<ImagePage>,
    viewModel: GalleryViewModel,
    onToggleControls: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onProgress: (Int, Boolean) -> Unit,
    onLongPressPage: (Int) -> Unit,
    nextChapter: MediaItem? = null,
    autoAdvance: Boolean = false,
    startPage: Int = 0,
    listState: LazyListState,
    jumpRequest: Pair<Int, Int>? = null,
    positionInitialized: Boolean,
    onPositionInitialized: () -> Unit,
    onFinishChapter: () -> Unit,
    onOpenNextChapter: (MediaItem) -> Unit = {},
) {
    val restorePage by produceState<Int?>(initialValue = null, item.id, startPage) {
        value = startPage
    }
    val context = LocalContext.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    var zoomedPageKey by remember(item.id) { mutableStateOf<String?>(null) }
    var restored by remember(item.id) { mutableStateOf(false) }
    var appliedJump by remember(item.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    val positionReady = restored && appliedJump == jumpRequest
    var restoredIndex by remember(item.id) { mutableIntStateOf(0) }
    var restoredOffset by remember(item.id) { mutableIntStateOf(0) }
    var advancedAfterRestore by remember(item.id) { mutableStateOf(false) }
    var completedThisSession by remember(item.id) { mutableStateOf(false) }
    // Pressing the end-of-chapter entry is an explicit "I finished this chapter". It is the only
    // way to finish a chapter whose end is visible from the start, such as a single-page one.
    var chapterConfirmed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(pages.size, restorePage, jumpRequest) {
        if (pages.isEmpty()) return@LaunchedEffect
        val target = (jumpRequest?.first ?: restorePage)?.coerceIn(pages.indices) ?: return@LaunchedEffect
        restored = false
        advancedAfterRestore = false
        if (jumpRequest != null || !positionInitialized) {
            listState.scrollToItem(target)
        }
        // Near the end, a short target page cannot be the first visible item. Await layout,
        // not an impossible index equality; an initialized list keeps its saved pixel offset.
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        // Reached either way: on a first open the saved position, after a configuration change the
        // position the list already restored. Measuring after the list is settled is what matters.
        onPositionInitialized()
        restoredIndex = listState.firstVisibleItemIndex
        restoredOffset = listState.firstVisibleItemScrollOffset
        advancedAfterRestore = false
        appliedJump = jumpRequest
        restored = true
    }
    LaunchedEffect(listState, item.id, pages.size, positionReady) {
        if (!positionReady || pages.isEmpty()) return@LaunchedEffect
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }
            .distinctUntilChanged()
            .collect { (index, offset, scrolling) ->
                if (scrolling && (index > restoredIndex || index == restoredIndex && offset > restoredOffset)) {
                    advancedAfterRestore = true
                }
            }
    }
    LaunchedEffect(listState, item.id, pages.size, positionReady, completedThisSession) {
        if (!positionReady || pages.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { page ->
                val visiblePage = page.coerceIn(pages.indices)
                onPageChanged(visiblePage)
                onProgress(visiblePage, completedThisSession)
            }
    }
    // Completion requires forward scrolling to the physical end or explicit confirmation.
    // A restored/jumped-to end, or one already visible at opening, must never auto-advance.
    // `positionInitialized` is a key, not just an argument: a snapshotFlow block only re-evaluates
    // when snapshot state it read changes, so a settled flag arriving as a parameter would leave
    // the flow reporting the pre-restore value forever.
    LaunchedEffect(
        listState,
        pages.size,
        positionReady,
        advancedAfterRestore,
        completedThisSession,
        positionInitialized,
        chapterConfirmed,
    ) {
        if (!positionReady || pages.isEmpty() || completedThisSession) return@LaunchedEffect
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            chapterEndState(
                pageCount = pages.size,
                lastVisibleItemIndex = visible.lastOrNull()?.index ?: -1,
                canScrollForward = listState.canScrollForward,
                advancedAfterRestore = advancedAfterRestore,
                settled = positionInitialized,
                confirmed = chapterConfirmed,
            )
        }
            .distinctUntilChanged()
            .collect { state ->
                if (state.reachedEnd && !completedThisSession) {
                    completedThisSession = true
                    onProgress(pages.lastIndex, true)
                    when {
                        state.arrivedByScrolling && autoAdvance -> nextChapter?.let(onOpenNextChapter)
                        // Explicit confirmation: the reader asked to finish, so the final chapter
                        // returns to its overview and any other chapter continues.
                        !state.arrivedByScrolling && chapterConfirmed ->
                            nextChapter?.let(onOpenNextChapter) ?: onFinishChapter()
                    }
                }
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
        // Zooming locks the current page: the finger pans the page instead of scrolling the list.
        userScrollEnabled = zoomedPageKey == null,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(pages.size, key = { index ->
            pages[index].relativePath ?: pages[index].archiveEntry ?: pages[index].name
        }) { index ->
            val page = pages[index]
            val pageKey = page.relativePath ?: page.archiveEntry ?: page.name
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
                pageKey = pageKey,
                visibleHeight = visibleSliceOf(listState, index),
                onTap = onToggleControls,
                onLongPress = { onLongPressPage(index) },
                onZoomingChanged = { zooming ->
                    if (zooming) zoomedPageKey = pageKey
                    else if (zoomedPageKey == pageKey) zoomedPageKey = null
                },
            ) {
                when {
                    imageRequest != null -> SubcomposeAsyncImage(
                        model = imageRequest,
                        contentDescription = "第 ${index + 1} 页",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val imageState by painter.state.collectAsState()
                        when (imageState) {
                            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                            is AsyncImagePainter.State.Error -> ComicPageLoadError(message = "第 ${index + 1} 页加载失败")
                            // Empty precedes Loading, including on cache hits. A zero-height
                            // first measure lets LazyColumn clamp a jump against an empty book.
                            else -> Box(
                                modifier = Modifier.fillMaxWidth().height(240.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(color = Color.White) }
                        }
                    }
                    page.archiveEntry != null -> ArchiveComicPage(
                        item = item,
                        entryName = page.archiveEntry,
                        archivePath = page.relativePath,
                        viewModel = viewModel,
                    )
                }
            }
        }
        if (pages.isNotEmpty()) {
            item(key = "chapter-end") {
                ChapterEndFooter(
                    nextChapter = nextChapter,
                    onComplete = {
                        // The button is the explicit confirmation; the decision effect records it,
                        // so the state is written through one path instead of two.
                        chapterConfirmed = true
                    },
                )
            }
        }
    }
}

/**
 * End-of-chapter entry into the next chapter.
 *
 * A Series reads as one flow, so the end of a chapter must offer the next one without sending
 * the reader back to the shelf; the shelf remains the place to pick a different chapter.
 */
@Composable
private fun ChapterEndFooter(
    nextChapter: MediaItem?,
    onComplete: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text("本话已读完", color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (nextChapter != null) {
            Text(
                "下一话 · ${nextChapter.displayTitle}",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onComplete) {
            Text(if (nextChapter == null) "完成阅读" else "阅读下一话")
        }
    }
}

/**
 * How much of the page at [index] is inside the reading window.
 *
 * The zoom container needs it so a pan inside a long page stops at the edge of what the reader
 * is actually looking at instead of scrolling the page into empty space.
 */
private fun visibleSliceOf(state: LazyListState, index: Int): Float {
    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        ?: return 0f
    val viewportStart = state.layoutInfo.viewportStartOffset
    val viewportEnd = state.layoutInfo.viewportEndOffset
    val start = maxOf(info.offset, viewportStart)
    val end = minOf(info.offset + info.size, viewportEnd)
    return (end - start).coerceAtLeast(0).toFloat()
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

@Composable
private fun ZoomableComicPage(
    pageKey: String,
    visibleHeight: Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onZoomingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The comic page uses the same tested container as the single-image viewer; only the resting
    // placement differs. A page must fill the width, so it rests at "width filled" instead of
    // "whole frame visible", and a pinch or double tap zooms from there.
    val zoom = rememberZoomState(ZoomPlacement.WIDTH)
    zoom.visibleHeight = visibleHeight
    LaunchedEffect(zoom.isZoomed) { onZoomingChanged(zoom.isZoomed) }
    DisposableEffect(pageKey) { onDispose { onZoomingChanged(false) } }
    Zoomable(
        state = zoom,
        placement = ZoomPlacement.WIDTH,
        modifier = modifier.fillMaxWidth(),
        onTap = onTap,
        onLongPress = onLongPress,
    ) { contentModifier ->
        // Let the image report its own aspect ratio: the resting transform then fits the
        // measured width exactly, so continuous reading has no side gaps. Tap and long-press are
        // handled by the same detector in Zoomable; stacking another clickable detector here
        // used to toggle the controls twice for one physical tap.
        Box(
            modifier = contentModifier.onSizeChanged { size ->
                zoom.intrinsic = Size(size.width.toFloat(), size.height.toFloat())
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
    onEnded: () -> Unit = {},
) {
    val context = LocalContext.current
    // A Work can be known from `.gallery/` while its media is not on this device. ExoPlayer
    // would turn the empty source into a local file path, fail with ENOENT and leave a black
    // rectangle, so say what is actually true instead of pretending the player failed.
    if (uri.isBlank()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "媒体不在本机",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    val player = remember(item.libraryId, item.id, uri) { ExoPlayer.Builder(context).build() }
    val session = remember(player) { dev.susnowy.gallery.ui.VideoPlaybackSession() }
    val currentOnEnded by rememberUpdatedState(onEnded)
    val saved by produceState<PlaybackProgress?>(null, player) {
        value = viewModel.progress(item) ?: PlaybackProgress(itemId = item.id)
    }
    LaunchedEffect(player, uri, saved) {
        val progress = saved ?: return@LaunchedEffect
        session.restore(progress.finished)
        player.setMediaItem(PlayerMediaItem.fromUri(uri.toUri()))
        player.prepare()
        if (!progress.finished && progress.positionMs > 0) player.seekTo(progress.positionMs)
    }
    LaunchedEffect(player, active) {
        player.playWhenReady = active
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) session.playing()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    session.ended()
                    if (session.initialized) viewModel.saveProgress(item,
                        positionMs = player.currentPosition.coerceAtLeast(0), finished = session.finished)
                    if (session.finished) currentOnEnded()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            val position = player.currentPosition.coerceAtLeast(0)
            if (session.initialized) viewModel.saveProgress(
                item,
                positionMs = position,
                finished = session.finished,
            )
            player.removeListener(listener)
            player.release()
        }
    }
    dev.susnowy.gallery.ui.components.VideoPlayerSurface(player, modifier)
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
