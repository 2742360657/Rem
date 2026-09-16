package dev.susnowy.gallery.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.susnowy.gallery.importer.SystemMediaAccess
import dev.susnowy.gallery.importer.SystemMediaEntry
import dev.susnowy.gallery.importer.SystemMediaType
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.organizer.OrganizationPlan
import dev.susnowy.gallery.organizer.OrganizerTemplate
import dev.susnowy.gallery.ui.AppScreen
import dev.susnowy.gallery.ui.GalleryUiState
import dev.susnowy.gallery.ui.GalleryViewModel
import dev.susnowy.gallery.ui.components.MediaCard
import dev.susnowy.gallery.ui.components.MediaGrid
import dev.susnowy.gallery.ui.components.label

@Composable
fun EmptyLibraryScreen(onChooseFolder: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("建立你的便携媒体库", style = MaterialTheme.typography.headlineMedium)
            Text(
                "选择一个文件夹。Gallery 只管理你明确接入的 Library，分类和进度会随文件夹一起移动。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onChooseFolder) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("选择文件夹")
            }
        }
    }
}

@Composable
fun GalleryScreenContent(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onChooseFolder: () -> Unit,
    onRequestSystemMediaAccess: () -> Unit,
    onFallbackMediaPicker: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val visible = state.media.filterNot(MediaItem::trashed)
    when (state.screen) {
        AppScreen.HOME -> HomeScreen(visible, viewModel)
        AppScreen.LIBRARIES -> LibrariesScreen(state, viewModel, onChooseFolder)
        AppScreen.INBOX -> MediaCollectionScreen(
            items = visible.filter(MediaItem::inInbox),
            viewModel = viewModel,
            emptyText = "扫描到的新内容会出现在这里",
        )
        AppScreen.PHOTOS -> PhotosScreen(
            items = visible.filter { it.kind in PHOTO_KINDS }.sortedByDescending { it.capturedAt ?: it.modifiedAt },
            viewModel = viewModel,
        )
        AppScreen.SYSTEM_GALLERY -> SystemGalleryScreen(
            state = state,
            viewModel = viewModel,
            onRequestAccess = onRequestSystemMediaAccess,
            onFallbackPicker = onFallbackMediaPicker,
            onOpenAppSettings = onOpenAppSettings,
        )
        AppScreen.IMAGES -> ImagesScreen(visible.filter { it.kind == MediaKind.IMAGE }, viewModel)
        AppScreen.IMAGE_SETS -> MediaCollectionScreen(
            items = visible.filter { it.kind == MediaKind.IMAGE_SET },
            viewModel = viewModel,
            emptyText = "包含多张图片的叶子目录和 ZIP/CBZ 会显示在这里",
        )
        AppScreen.VIDEOS -> MediaCollectionScreen(
            items = visible.filter { it.kind == MediaKind.VIDEO },
            viewModel = viewModel,
            emptyText = "Library 中的作品视频会显示在这里",
        )
        AppScreen.SERIES -> FacetScreen(visible, Facet.SERIES, viewModel)
        AppScreen.COLLECTIONS -> FacetScreen(visible, Facet.COLLECTION, viewModel)
        AppScreen.AUTHORS -> FacetScreen(visible, Facet.AUTHOR, viewModel)
        AppScreen.TAGS -> FacetScreen(visible, Facet.TAG, viewModel)
        AppScreen.SEARCH -> SearchScreen(
            items = state.allMedia.filter { item ->
                !item.trashed && state.libraries.any {
                    it.libraryId == item.libraryId && it.permissionState == PermissionState.AVAILABLE
                }
            },
            activeLibraryId = state.activeLibraryId,
            query = state.searchQuery,
            viewModel = viewModel,
        )
        AppScreen.TRASH -> TrashScreen(state.media.filter(MediaItem::trashed), viewModel)
        AppScreen.ORGANIZER -> OrganizerScreen(viewModel)
        AppScreen.SETTINGS -> SettingsScreen(state, viewModel)
    }
}

@Composable
private fun SystemGalleryScreen(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onRequestAccess: () -> Unit,
    onFallbackPicker: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var showImportChoices by remember { mutableStateOf(false) }
    var showImageSetTitle by remember { mutableStateOf(false) }
    var imageSetTitle by remember { mutableStateOf("导入图集") }
    val availableUris = remember(state.systemMedia) { state.systemMedia.mapTo(mutableSetOf()) { it.uri } }
    LaunchedEffect(availableUris) { selected = selected.intersect(availableUris) }

    val sources = remember(state.systemMedia) {
        state.systemMedia.map(SystemMediaEntry::sourceLabel).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val visible = remember(state.systemMedia, sourceFilter) {
        state.systemMedia.filter { sourceFilter == null || it.sourceLabel() == sourceFilter }
    }
    val selectedMedia = state.systemMedia.filter { it.uri in selected }
    val canCreateImageSet = selectedMedia.size >= 2 &&
        selectedMedia.all { it.mediaType == SystemMediaType.IMAGE }

    BackHandler(enabled = selected.isNotEmpty() && !showImportChoices && !showImageSetTitle) {
        selected = emptySet()
    }

    if (state.systemMediaAccess == SystemMediaAccess.NONE) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(28.dp),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                Text("浏览系统相册", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "授权后可在 Gallery 中按时间和来源查看本机照片、视频，再复制到当前 Library。不会移动或删除系统相册原文件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRequestAccess) { Text("授权访问照片和视频") }
                OutlinedButton(onClick = onFallbackPicker) { Text("仅使用系统选择器") }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (state.systemMediaAccess) {
                    SystemMediaAccess.FULL -> "已获得系统相册访问权限"
                    SystemMediaAccess.PARTIAL -> "当前仅显示系统授权的媒体（可能只含图片、视频或指定项目）"
                    SystemMediaAccess.NONE -> "未授权"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${state.systemMedia.size} 项可访问媒体 · 导入会复制原始文件并保留来源目录关系",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::refreshSystemMedia) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("刷新")
                }
                OutlinedButton(
                    onClick = if (state.systemMediaAccess == SystemMediaAccess.PARTIAL) {
                        onRequestAccess
                    } else {
                        onOpenAppSettings
                    },
                ) {
                    Text(
                        if (state.systemMediaAccess == SystemMediaAccess.PARTIAL) {
                            "调整授权范围"
                        } else {
                            "系统权限设置"
                        },
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = sourceFilter == null,
                    onClick = { sourceFilter = null },
                    label = { Text("全部") },
                )
            }
            items(sources, key = { it }) { source ->
                FilterChip(
                    selected = sourceFilter == source,
                    onClick = { sourceFilter = source },
                    label = { Text(source, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { selected = selected + visible.map(SystemMediaEntry::uri) }) {
                Text("全选当前 ${visible.size} 项")
            }
            if (selected.isNotEmpty()) {
                TextButton(onClick = { selected = emptySet() }) { Text("清空") }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { showImportChoices = true },
                enabled = selected.isNotEmpty() && state.operation == null,
            ) { Text("导入 ${selected.size} 项") }
        }

        when {
            state.systemMediaLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当前授权范围内没有可访问的照片或视频")
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                gridItems(visible, key = SystemMediaEntry::uri) { media ->
                    val checked = media.uri in selected
                    Card(onClick = {
                        selected = if (checked) selected - media.uri else selected + media.uri
                    }) {
                        Box {
                            AsyncImage(
                                model = media.uri,
                                contentDescription = media.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                            )
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { value ->
                                    selected = if (value) selected + media.uri else selected - media.uri
                                },
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                        Text(
                            media.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Text(
                            if (media.mediaType == SystemMediaType.VIDEO) "视频 · ${media.sourceLabel()}" else media.sourceLabel(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (showImportChoices) {
        AlertDialog(
            onDismissRequest = { showImportChoices = false },
            title = { Text("选择导入语义") },
            text = {
                Text(
                    "相册媒体会进入 Photos 时间线；ImageSet 会按文件名自然排序，作为一本漫画或图集阅读。两种方式都只复制，不改动系统相册原文件。",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        viewModel.importSystemMedia(selectedMedia.map { Uri.parse(it.uri) })
                        selected = emptySet()
                        showImportChoices = false
                    }) { Text("导入为相册媒体") }
                    if (canCreateImageSet) {
                        TextButton(onClick = {
                            imageSetTitle = selectedMedia.map(SystemMediaEntry::sourceLabel)
                                .distinct()
                                .singleOrNull()
                                ?.substringAfterLast('/')
                                ?.takeIf(String::isNotBlank)
                                ?: "导入图集"
                            showImportChoices = false
                            showImageSetTitle = true
                        }) { Text("导入为 ImageSet") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportChoices = false }) { Text("取消") }
            },
        )
    }

    if (showImageSetTitle) {
        AlertDialog(
            onDismissRequest = { showImageSetTitle = false },
            title = { Text("创建 ImageSet") },
            text = {
                OutlinedTextField(
                    value = imageSetTitle,
                    onValueChange = { imageSetTitle = it },
                    label = { Text("漫画或图集标题") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importSystemImageSet(
                            selectedMedia.map { Uri.parse(it.uri) },
                            imageSetTitle,
                        )
                        selected = emptySet()
                        showImageSetTitle = false
                    },
                    enabled = imageSetTitle.isNotBlank(),
                ) { Text("复制并创建") }
            },
            dismissButton = {
                TextButton(onClick = { showImageSetTitle = false }) { Text("取消") }
            },
        )
    }
}

private fun SystemMediaEntry.sourceLabel(): String = sourcePath.ifBlank { bucketName.ifBlank { "未分类" } }

@Composable
private fun HomeScreen(items: List<MediaItem>, viewModel: GalleryViewModel) {
    val inbox = items.count(MediaItem::inInbox)
    val imageSets = items.count { it.kind == MediaKind.IMAGE_SET }
    val videos = items.count { it.kind == MediaKind.VIDEO }
    val recent = remember(items) { items.sortedByDescending(MediaItem::modifiedAt).take(12) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text("你的 Library", style = MaterialTheme.typography.headlineLarge)
            Text(
                "真实文件保持原样，分类与进度跟随 Library。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { StatCard("全部内容", items.size.toString()) { viewModel.navigate(AppScreen.SEARCH) } }
                item { StatCard("待整理", inbox.toString()) { viewModel.navigate(AppScreen.INBOX) } }
                item { StatCard("ImageSet", imageSets.toString()) { viewModel.navigate(AppScreen.IMAGE_SETS) } }
                item { StatCard("视频", videos.toString()) { viewModel.navigate(AppScreen.VIDEOS) } }
            }
        }
        if (items.isNotEmpty()) {
            item { Text("最近内容", style = MaterialTheme.typography.titleLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = MediaItem::id) { item ->
                        MediaCard(
                            item = item,
                            viewModel = viewModel,
                            onClick = { viewModel.open(item, recent) },
                            modifier = Modifier.width(170.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(150.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibrariesScreen(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onChooseFolder: () -> Unit,
) {
    var pendingForget by remember { mutableStateOf<dev.susnowy.gallery.model.LibraryRegistration?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Button(onClick = onChooseFolder) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("接入另一个 Library")
            }
        }
        items(state.libraries, key = { it.libraryId }) { library ->
            Card(
                onClick = { viewModel.selectLibrary(library.libraryId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text(library.name) },
                    supportingContent = {
                        Text(
                            when (library.permissionState) {
                                PermissionState.AVAILABLE -> "已连接 · Schema v${library.schemaVersion}"
                                PermissionState.OFFLINE -> "介质离线"
                                PermissionState.REVOKED -> "目录权限已失效"
                            },
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (library.permissionState == PermissionState.AVAILABLE) {
                                Icons.Rounded.CheckCircle
                            } else Icons.Rounded.Folder,
                            contentDescription = null,
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { viewModel.scan(library.libraryId) }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "扫描")
                            }
                            TextButton(onClick = { pendingForget = library }) {
                                Text("移除登记")
                            }
                        }
                    },
                )
            }
        }
    }
    pendingForget?.let { library ->
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text("移除 ${library.name}？") },
            text = {
                Text("只移除本机索引并释放 Android 目录授权；Library 中的媒体、.gallery 元数据和目录结构都不会删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetLibrary(library.libraryId)
                    pendingForget = null
                }) { Text("移除登记") }
            },
            dismissButton = { TextButton(onClick = { pendingForget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MediaCollectionScreen(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    emptyText: String,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Inbox, contentDescription = null)
                Text(emptyText, modifier = Modifier.padding(20.dp))
            }
        }
    } else {
        MediaGrid(items = items, viewModel = viewModel, onOpen = { viewModel.open(it, items) })
    }
}

@Composable
private fun PhotosScreen(items: List<MediaItem>, viewModel: GalleryViewModel) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showBatchEditor by remember { mutableStateOf(false) }
    var confirmBatchTrash by remember { mutableStateOf(false) }
    val availableIds = remember(items) { items.mapTo(mutableSetOf(), MediaItem::id) }
    LaunchedEffect(availableIds) { selected = selected.intersect(availableIds) }
    val selectedItems = items.filter { it.id in selected }
    val allSelectedAreImages = selectedItems.size >= 2 && selectedItems.all { it.kind == MediaKind.PHOTO }

    BackHandler(enabled = selectionMode && !showCreateDialog && !showBatchEditor && !confirmBatchTrash) {
        selectionMode = false
        selected = emptySet()
    }

    Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                IconButton(onClick = {
                    selectionMode = false
                    selected = emptySet()
                }) { Icon(Icons.Rounded.Close, contentDescription = "退出选择") }
                Text("已选择 ${selected.size} 项", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selected = if (selected.size == items.size) emptySet() else availableIds
                }) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = null)
                    Text(if (selected.size == items.size) "清空" else "全选")
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val removeFavorite = selectedItems.isNotEmpty() && selectedItems.all(MediaItem::favorite)
                    FilledTonalButton(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            viewModel.setBatchFavorite(selected, !removeFavorite)
                            selected = emptySet()
                            selectionMode = false
                        },
                    ) {
                        Icon(
                            if (removeFavorite) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite,
                            contentDescription = null,
                        )
                        Text(if (removeFavorite) "取消收藏" else "收藏")
                    }
                }
                item {
                    FilledTonalButton(
                        enabled = selected.isNotEmpty(),
                        onClick = { showBatchEditor = true },
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                        Text("编辑分类")
                    }
                }
                item {
                    FilledTonalButton(
                        enabled = allSelectedAreImages,
                        onClick = { showCreateDialog = true },
                    ) {
                        Icon(Icons.Rounded.Collections, contentDescription = null)
                        Text("派生 ImageSet")
                    }
                }
                item {
                    FilledTonalButton(
                        enabled = selected.isNotEmpty(),
                        onClick = { confirmBatchTrash = true },
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                        Text("回收站")
                    }
                }
            }
        } else if (items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    "按拍摄时间排列 · 点按查看，长按多选",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selectionMode = true }) { Text("选择") }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("从系统相册导入，或在 Library 的 Photos 目录中放入媒体")
            }
        } else {
            MediaGrid(
                items = items,
                viewModel = viewModel,
                onOpen = { viewModel.open(it, items) },
                modifier = Modifier.weight(1f),
                compact = true,
                selectionEnabled = true,
                selectionMode = selectionMode,
                selectedIds = selected,
                onSelectionToggle = { item ->
                    selectionMode = true
                    selected = if (item.id in selected) selected - item.id else selected + item.id
                },
            )
        }
    }
    if (showCreateDialog) {
        CreateImageSetDialog(
            items = selectedItems,
            initialSelected = selected,
            onDismiss = { showCreateDialog = false },
            onCreate = { selectedIds, title ->
                viewModel.createImageSet(selectedIds, title)
                showCreateDialog = false
                selectionMode = false
                selected = emptySet()
            },
        )
    }
    if (showBatchEditor) {
        BatchMetadataDialog(
            count = selected.size,
            onDismiss = { showBatchEditor = false },
            onSave = { authors, tags, collections ->
                viewModel.addBatchMetadata(selected, authors, tags, collections)
                showBatchEditor = false
                selectionMode = false
                selected = emptySet()
            },
        )
    }
    if (confirmBatchTrash) {
        AlertDialog(
            onDismissRequest = { confirmBatchTrash = false },
            title = { Text("将 ${selected.size} 项移入回收站？") },
            text = { Text("只写入逻辑回收站状态，真实文件不会移动或删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTrashedBatch(selected)
                    confirmBatchTrash = false
                    selectionMode = false
                    selected = emptySet()
                }) { Text("移入回收站") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchTrash = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ImagesScreen(items: List<MediaItem>, viewModel: GalleryViewModel) {
    var showCreateDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (items.size >= 2) {
            FilledTonalButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("从多张图片创建 ImageSet") }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Library 中的独立图片会显示在这里")
            }
        } else {
            MediaGrid(items, viewModel, { viewModel.open(it, items) }, Modifier.weight(1f))
        }
    }
    if (showCreateDialog) {
        CreateImageSetDialog(
            items = items,
            onDismiss = { showCreateDialog = false },
            onCreate = { selected, title ->
                viewModel.createImageSet(selected, title)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun CreateImageSetDialog(
    items: List<MediaItem>,
    initialSelected: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onCreate: (List<String>, String) -> Unit,
) {
    var title by remember { mutableStateOf("新 ImageSet") }
    var selected by remember(items, initialSelected) {
        mutableStateOf(initialSelected.intersect(items.mapTo(mutableSetOf(), MediaItem::id)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建 ImageSet") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { selected = items.mapTo(mutableSetOf(), MediaItem::id) }) {
                        Text("全选 ${items.size} 项")
                    }
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                    }
                    Text("已选 ${selected.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(items, key = MediaItem::id) { item ->
                        ListItem(
                            headlineContent = {
                                Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = item.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + item.id else selected - item.id
                                    },
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(selected.toList(), title) },
                enabled = selected.size >= 2 && title.isNotBlank(),
            ) { Text("复制并创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BatchMetadataDialog(
    count: Int,
    onDismiss: () -> Unit,
    onSave: (authors: String, tags: String, collections: String) -> Unit,
) {
    var authors by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var collections by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量编辑 $count 项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入内容会追加到现有元数据，不会覆盖每张照片已有的值。")
                OutlinedTextField(
                    value = authors,
                    onValueChange = { authors = it },
                    label = { Text("添加作者（逗号分隔）") },
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("添加 Tag（逗号分隔）") },
                )
                OutlinedTextField(
                    value = collections,
                    onValueChange = { collections = it },
                    label = { Text("添加 Collection（逗号分隔）") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = authors.isNotBlank() || tags.isNotBlank() || collections.isNotBlank(),
                onClick = { onSave(authors, tags, collections) },
            ) { Text("追加到所选") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private enum class Facet(val emptyText: String) {
    SERIES("编辑作品元数据后，系列会显示在这里"),
    COLLECTION("还没有 Collection"),
    AUTHOR("还没有作者"),
    TAG("还没有标签"),
}

@Composable
private fun FacetScreen(items: List<MediaItem>, facet: Facet, viewModel: GalleryViewModel) {
    val values = remember(items, facet) {
        items.flatMap { item ->
            when (facet) {
                Facet.SERIES -> listOfNotNull(item.series?.title)
                Facet.COLLECTION -> item.collections
                Facet.AUTHOR -> item.authors
                Facet.TAG -> item.tags
            }
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    var selected by remember(facet, values) { mutableStateOf(values.firstOrNull()) }
    if (values.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(facet.emptyText) }
        return
    }
    val filtered = items.filter { item ->
        when (facet) {
            Facet.SERIES -> item.series?.title == selected
            Facet.COLLECTION -> selected in item.collections
            Facet.AUTHOR -> selected in item.authors
            Facet.TAG -> selected in item.tags
        }
    }.sortedBy { it.series?.sortIndex ?: 0.0 }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(values) { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { selected = value },
                    label = { Text(value) },
                )
            }
        }
        MediaGrid(filtered, viewModel, { viewModel.open(it, filtered) }, Modifier.weight(1f))
    }
}

@Composable
private fun SearchScreen(
    items: List<MediaItem>,
    activeLibraryId: String?,
    query: String,
    viewModel: GalleryViewModel,
) {
    var kind by remember { mutableStateOf<MediaKind?>(null) }
    var allLibraries by remember { mutableStateOf(true) }
    val normalized = query.trim()
    val filtered = items.filter { item ->
        (allLibraries || item.libraryId == activeLibraryId) &&
            (kind == null || item.kind == kind) && (normalized.isBlank() || listOf(
            item.displayTitle,
            item.originalTitle.orEmpty(),
            item.relativePath,
            item.series?.title.orEmpty(),
            item.authors.joinToString(),
            item.tags.joinToString(),
            item.collections.joinToString(),
        ).any { it.contains(normalized, ignoreCase = true) })
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::updateSearch,
            label = { Text("标题、作者、标签、系列、路径") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = allLibraries,
                    onClick = { allLibraries = !allLibraries },
                    label = { Text(if (allLibraries) "所有在线 Library" else "当前 Library") },
                )
            }
            item {
                FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("全部") })
            }
            items(MediaKind.entries) { value ->
                FilterChip(
                    selected = kind == value,
                    onClick = { kind = value },
                    label = { Text(value.label()) },
                )
            }
        }
        Text("${filtered.size} 个结果", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        MediaGrid(filtered, viewModel, { viewModel.open(it, filtered) }, Modifier.weight(1f))
    }
}

@Composable
private fun TrashScreen(items: List<MediaItem>, viewModel: GalleryViewModel) {
    var pendingPurge by remember { mutableStateOf<MediaItem?>(null) }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("回收站为空。普通删除不会移动或删除真实文件。")
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
            items(items, key = MediaItem::id) { item ->
                ListItem(
                    headlineContent = { Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { viewModel.setTrashed(item, false) }) {
                                Icon(Icons.Rounded.Restore, contentDescription = "恢复")
                            }
                            IconButton(onClick = { pendingPurge = item }) {
                                Icon(Icons.Rounded.DeleteForever, contentDescription = "永久删除")
                            }
                        }
                    },
                )
            }
        }
    }
    pendingPurge?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("永久删除？") },
            text = { Text("这会真实删除 ${item.relativePath}，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purge(item)
                    pendingPurge = null
                }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { pendingPurge = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun OrganizerScreen(viewModel: GalleryViewModel) {
    val plan by viewModel.organizationPlan.collectAsStateWithLifecycle()
    var template by remember { mutableStateOf(OrganizerTemplate.AUTHOR_FIRST) }
    var confirmExecution by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("显式整理真实文件", style = MaterialTheme.typography.headlineSmall)
            Text(
                "先生成只读计划并检查冲突。只有确认后才会建立事务、复制、校验并删除源路径。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrganizerTemplate.entries.forEach { value ->
                    FilterChip(
                        selected = template == value,
                        onClick = {
                            template = value
                            viewModel.clearOrganizationPlan()
                        },
                        label = { Text(value.label) },
                    )
                }
            }
            Button(onClick = { viewModel.previewOrganization(template) }) {
                Text("生成预览")
            }
        }
        if (plan == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("尚未生成计划")
            }
        } else {
            OrganizationPlanView(
                plan = plan!!,
                onExecute = { confirmExecution = true },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (confirmExecution && plan != null) {
        AlertDialog(
            onDismissRequest = { confirmExecution = false },
            title = { Text("执行整理事务？") },
            text = {
                Text("将移动 ${plan!!.executableSteps.size} 项真实媒体。执行前会备份 .gallery 元数据，并为每一步写入恢复日志。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.executeOrganization(plan!!)
                    confirmExecution = false
                }) { Text("确认执行") }
            },
            dismissButton = { TextButton(onClick = { confirmExecution = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun OrganizationPlanView(
    plan: OrganizationPlan,
    onExecute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${plan.steps.size} 个变更 · ${plan.totalBytes.formatBytes()}")
                    if (plan.hasConflicts) {
                        Text(
                            "存在冲突，解决前不能执行",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Button(
                            onClick = onExecute,
                            enabled = plan.steps.isNotEmpty(),
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("执行计划") }
                    }
                }
            }
        }
        items(plan.steps, key = { it.item.id }) { step ->
            ListItem(
                headlineContent = { Text(step.item.displayTitle) },
                supportingContent = {
                    Column {
                        Text(step.source, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("→ ${step.target}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        step.conflict?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsScreen(state: GalleryUiState, viewModel: GalleryViewModel) {
    var daysText by remember(state.trashRetentionDays) {
        mutableStateOf(state.trashRetentionDays.takeIf { it > 0 }?.toString().orEmpty())
    }
    val duplicateGroups by viewModel.duplicateGroups.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            ListItem(
                headlineContent = { Text("接入后自动扫描") },
                supportingContent = { Text("扫描只更新索引，不移动媒体文件") },
                trailingContent = {
                    Switch(checked = state.autoScan, onCheckedChange = viewModel::setAutoScan)
                },
            )
        }
        item {
            Text("回收站保留期限", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90, 0).forEach { days ->
                    FilterChip(
                        selected = state.trashRetentionDays == days,
                        onClick = {
                            viewModel.setRetentionDays(days)
                            daysText = days.takeIf { it > 0 }?.toString().orEmpty()
                        },
                        label = { Text(if (days == 0) "永久" else "$days 天") },
                    )
                }
            }
            OutlinedTextField(
                value = daysText,
                onValueChange = { daysText = it.filter(Char::isDigit).take(4) },
                label = { Text("回收站保留天数") },
                supportingText = { Text("自定义天数；到期内容仍需经过身份验证后才会真删除") },
                singleLine = true,
            )
            FilledTonalButton(
                onClick = { daysText.toIntOrNull()?.let(viewModel::setRetentionDays) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("保存") }
        }
        item {
            OutlinedButton(onClick = viewModel::rebuildIndex) { Text("从 Library 重建本机索引") }
        }
        item {
            OutlinedButton(onClick = viewModel::findDuplicates) { Text("检测重复内容") }
            Text(
                "只计算和展示，不自动去重。主动派生的相同内容可以继续并存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        duplicateGroups.forEachIndexed { index, group ->
            item(key = "duplicate-$index") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("重复组 ${index + 1}", fontWeight = FontWeight.SemiBold)
                        group.forEach { item ->
                            Text(item.relativePath, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Text("Gallery 1.0", style = MaterialTheme.typography.titleMedium)
            Text(
                "本机索引和缩略图只是缓存；Library 中的 .gallery 元数据才是跨设备状态来源。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PHOTO_KINDS = setOf(MediaKind.PHOTO, MediaKind.PHOTO_VIDEO, MediaKind.LIVE_PHOTO)

private fun Long.formatBytes(): String = when {
    this >= 1_073_741_824 -> "%.1f GB".format(this / 1_073_741_824.0)
    this >= 1_048_576 -> "%.1f MB".format(this / 1_048_576.0)
    this >= 1_024 -> "%.1f KB".format(this / 1_024.0)
    else -> "$this B"
}
