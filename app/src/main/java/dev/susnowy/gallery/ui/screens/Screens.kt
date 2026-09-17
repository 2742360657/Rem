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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.susnowy.gallery.BuildConfig
import dev.susnowy.gallery.importer.SystemMediaAccess
import dev.susnowy.gallery.importer.SystemMediaEntry
import dev.susnowy.gallery.importer.SystemMediaType
import dev.susnowy.gallery.importer.WorkImportKind
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.DiscoveredEntry
import dev.susnowy.gallery.model.DiscoveryReason
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.organizer.OrganizationPlan
import dev.susnowy.gallery.organizer.OrganizerTemplate
import dev.susnowy.gallery.ui.AppScreen
import dev.susnowy.gallery.ui.GalleryUiState
import dev.susnowy.gallery.ui.GalleryViewModel
import dev.susnowy.gallery.ui.MixedMediaPresentation
import dev.susnowy.gallery.ui.SeriesPresentation
import dev.susnowy.gallery.ui.SeriesShelf
import dev.susnowy.gallery.ui.components.MediaCard
import dev.susnowy.gallery.ui.components.MediaGrid
import dev.susnowy.gallery.ui.components.MediaThumbnail
import dev.susnowy.gallery.ui.components.RightSidePanel
import dev.susnowy.gallery.ui.components.label
import java.util.Locale

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
                "选择一个文件夹。Rem 只管理你明确接入的 Library，分类和进度会随文件夹一起移动。",
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
    val accepted = visible.filterNot(MediaItem::inInbox)
    when (state.screen) {
        AppScreen.MEDIA -> ClassifiedLibraryScreen(accepted, viewModel)
        AppScreen.WORKS -> WorksLibraryScreen(accepted, viewModel)
        AppScreen.LIBRARIES -> LibrariesScreen(state, viewModel, onChooseFolder)
        AppScreen.INBOX -> InboxScreen(
            items = visible.filter(MediaItem::inInbox),
            discoveries = state.discoveries,
            viewModel = viewModel,
        )
        AppScreen.PHOTOS -> PhotosScreen(
            items = accepted.filter { it.kind in PHOTO_KINDS }.sortedByDescending { it.capturedAt ?: it.modifiedAt },
            viewModel = viewModel,
        )
        AppScreen.SYSTEM_GALLERY -> SystemGalleryScreen(
            state = state,
            viewModel = viewModel,
            onRequestAccess = onRequestSystemMediaAccess,
            onFallbackPicker = onFallbackMediaPicker,
            onOpenAppSettings = onOpenAppSettings,
        )
        AppScreen.IMAGES -> ImagesScreen(accepted.filter { it.kind == MediaKind.IMAGE }, viewModel)
        AppScreen.IMAGE_SETS -> RememberingClassifiedMediaScreen(
            items = accepted.filter { it.kind == MediaKind.IMAGE_SET },
            viewModel = viewModel,
            rootDirectory = "ImageSets",
            emptyText = "包含多张图片的叶子目录和 ZIP/CBZ 会显示在这里",
        )
        AppScreen.VIDEOS -> RememberingClassifiedMediaScreen(
            items = accepted.filter { it.kind == MediaKind.VIDEO },
            viewModel = viewModel,
            rootDirectory = "Videos",
            emptyText = "Library 中的作品视频会显示在这里",
        )
        AppScreen.SERIES -> FacetScreen(accepted, Facet.SERIES, viewModel)
        AppScreen.COLLECTIONS -> FacetScreen(accepted, Facet.COLLECTION, viewModel)
        AppScreen.AUTHORS -> FacetScreen(accepted, Facet.AUTHOR, viewModel)
        AppScreen.TAGS -> FacetScreen(accepted, Facet.TAG, viewModel)
        AppScreen.SEARCH -> SearchScreen(
            items = state.allMedia.filter { item ->
                !item.trashed && !item.inInbox && state.libraries.any {
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
    val canImportImages = selectedMedia.isNotEmpty() &&
        selectedMedia.all { it.mediaType == SystemMediaType.IMAGE }
    val canImportVideos = selectedMedia.isNotEmpty() &&
        selectedMedia.all { it.mediaType == SystemMediaType.VIDEO }

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
                    "授权后可在 Rem 中按时间和来源查看本机照片、视频，再复制到当前 Library。不会移动或删除系统相册原文件。",
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
                    "相册媒体会进入 Photos 混合时间线；图片或视频作品会按原来源目录形成默认分类；漫画会按文件名自然排序。所有方式都只复制，不改动系统相册原文件。",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        viewModel.importSystemMedia(selectedMedia.map { Uri.parse(it.uri) })
                        selected = emptySet()
                        showImportChoices = false
                    }) { Text("导入为相册媒体") }
                    if (canImportImages) {
                        TextButton(onClick = {
                            viewModel.importSystemWorks(
                                selectedMedia.map { Uri.parse(it.uri) },
                                WorkImportKind.IMAGE,
                            )
                            selected = emptySet()
                            showImportChoices = false
                        }) { Text("导入为图片（按来源分类）") }
                    }
                    if (canImportVideos) {
                        TextButton(onClick = {
                            viewModel.importSystemWorks(
                                selectedMedia.map { Uri.parse(it.uri) },
                                WorkImportKind.VIDEO,
                            )
                            selected = emptySet()
                            showImportChoices = false
                        }) { Text("导入为视频（按来源分类）") }
                    }
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
                        }) { Text("导入为漫画/图集") }
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
            title = { Text("创建漫画/图集") },
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

private enum class InboxSection(val label: String) { MEDIA("媒体建议"), OTHER("其他待判断") }

@Composable
private fun InboxScreen(
    items: List<MediaItem>,
    discoveries: List<DiscoveredEntry>,
    viewModel: GalleryViewModel,
) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var section by rememberSaveable {
        mutableStateOf(if (items.isNotEmpty()) InboxSection.MEDIA else InboxSection.OTHER)
    }
    LaunchedEffect(items.map(MediaItem::id)) {
        selected = selected.intersect(items.mapTo(mutableSetOf(), MediaItem::id))
        if (items.isEmpty()) selectionMode = false
        if (items.isEmpty() && discoveries.isNotEmpty()) section = InboxSection.OTHER
    }
    LaunchedEffect(discoveries.map(DiscoveredEntry::id)) {
        if (discoveries.isEmpty() && items.isNotEmpty()) section = InboxSection.MEDIA
    }
    if (items.isEmpty() && discoveries.isEmpty()) {
        MediaCollectionScreen(
            items = emptyList(),
            viewModel = viewModel,
            emptyText = "没有待处理内容",
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = section == InboxSection.MEDIA,
                onClick = { section = InboxSection.MEDIA },
                label = { Text("媒体建议 ${items.size}") },
                enabled = items.isNotEmpty(),
            )
            FilterChip(
                selected = section == InboxSection.OTHER,
                onClick = { section = InboxSection.OTHER },
                label = { Text("其他待判断 ${discoveries.size}") },
                enabled = discoveries.isNotEmpty(),
            )
        }
        if (section == InboxSection.OTHER) {
            Text(
                "这些路径已被发现，但当前不能安全分类或打开。Rem 不会移动或伪装它们，可交由 Agent 或后续版本处理。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(discoveries, key = DiscoveredEntry::id) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                entry.relativePath.substringAfterLast('/'),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    when (entry.reason) {
                                        DiscoveryReason.UNSUPPORTED_FILE -> "当前版本不支持此文件格式"
                                        DiscoveryReason.AMBIGUOUS_DIRECTORY -> "目录结构不明确，需要确认作品边界"
                                    },
                                )
                                Text(
                                    entry.relativePath,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (entry.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            if (!entry.isDirectory && entry.size > 0) Text(entry.size.formatBytes())
                        },
                    )
                }
            }
            return@Column
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${items.size} 项待处理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "扫描结果只是建议；编辑会保存人工值，直接接受仍保留自动来源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectionMode) {
                TextButton(onClick = {
                    selected = if (selected.size == items.size) emptySet() else items.mapTo(mutableSetOf(), MediaItem::id)
                }) {
                    Text(if (selected.size == items.size) "取消全选" else "全选")
                }
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        viewModel.acceptSuggestions(items.filter { it.id in selected })
                        selected = emptySet()
                        selectionMode = false
                    },
                ) { Text("接受 ${selected.size}") }
            } else {
                TextButton(onClick = { selectionMode = true }) { Text("选择") }
            }
        }
        MediaGrid(
            items = items,
            viewModel = viewModel,
            onOpen = { viewModel.open(it, items) },
            modifier = Modifier.weight(1f),
            selectionEnabled = true,
            selectionMode = selectionMode,
            selectedIds = selected,
            onSelectionToggle = { item ->
                selected = if (item.id in selected) selected - item.id else selected + item.id
            },
        )
    }
}

private enum class ClassifiedType(val label: String) { GROUPS("分组"), IMAGES("图片"), VIDEOS("视频") }

@Composable
private fun ClassifiedLibraryScreen(items: List<MediaItem>, viewModel: GalleryViewModel) {
    val classified = remember(items) { items.filter { it.domain == MediaDomain.CLASSIFIED } }
    val groups = remember(classified) { MixedMediaPresentation.groups(classified) }
    val groupsByPrimaryId = remember(groups) { groups.associateBy { it.primary.id } }
    val groupedVideoIds = remember(groups) {
        groups.flatMapTo(mutableSetOf()) { group -> group.videos.map(MediaItem::id) }
    }
    val imageCount = classified.count { it.kind == MediaKind.IMAGE }
    val videoCount = classified.count { it.kind == MediaKind.VIDEO && it.id !in groupedVideoIds }
    var type by rememberSaveable { mutableStateOf(ClassifiedType.GROUPS) }
    var groupPath by rememberSaveable { mutableStateOf<String?>(null) }
    var imagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var videoPath by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(groups.isEmpty()) {
        if (groups.isEmpty() && type == ClassifiedType.GROUPS) type = ClassifiedType.IMAGES
    }
    val shown = remember(classified, groups, groupedVideoIds, type) {
        classified.filter {
            when (type) {
                ClassifiedType.GROUPS -> it.id in groupsByPrimaryId
                ClassifiedType.IMAGES -> it.kind == MediaKind.IMAGE
                ClassifiedType.VIDEOS -> it.kind == MediaKind.VIDEO && it.id !in groupedVideoIds
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            FilterChip(
                selected = type == ClassifiedType.GROUPS,
                onClick = { type = ClassifiedType.GROUPS },
                label = { Text("分组 ${groups.size}") },
                enabled = groups.isNotEmpty(),
            )
            FilterChip(
                selected = type == ClassifiedType.IMAGES,
                onClick = { type = ClassifiedType.IMAGES },
                label = { Text("图片 $imageCount") },
            )
            FilterChip(
                selected = type == ClassifiedType.VIDEOS,
                onClick = { type = ClassifiedType.VIDEOS },
                label = { Text("视频 $videoCount") },
            )
        }
        ClassifiedMediaScreen(
            items = shown,
            viewModel = viewModel,
            rootDirectory = when (type) {
                ClassifiedType.GROUPS -> "Mixed"
                ClassifiedType.IMAGES -> "Images"
                ClassifiedType.VIDEOS -> "Videos"
            },
            emptyText = when (type) {
                ClassifiedType.GROUPS -> "同一目录中的图片集与视频会合并为一个浏览入口"
                ClassifiedType.IMAGES -> "这里按真实目录显示图片，不添加作者或标签层级"
                ClassifiedType.VIDEOS -> "这里按真实目录显示普通视频；动漫和影视作品在“漫画 / 动漫”中"
            },
            currentPath = when (type) {
                ClassifiedType.GROUPS -> groupPath
                ClassifiedType.IMAGES -> imagePath
                ClassifiedType.VIDEOS -> videoPath
            },
            onPathChange = { path ->
                when (type) {
                    ClassifiedType.GROUPS -> groupPath = path
                    ClassifiedType.IMAGES -> imagePath = path
                    ClassifiedType.VIDEOS -> videoPath = path
                }
            },
            browsingItemsFor = { item, visible -> groupsByPrimaryId[item.id]?.members ?: visible },
            supportingText = { item ->
                groupsByPrimaryId[item.id]?.let { group ->
                    "${item.pageCount ?: 0} 张图片 · ${group.videos.size} 个视频"
                }
            },
            compact = type != ClassifiedType.GROUPS,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class WorkType(val label: String) { COMICS("漫画 / 写真"), ANIME("动漫 / 影视") }
private enum class WorkFacet(val label: String) { ALL("全部"), AUTHOR("作者"), TAG("标签"), SERIES("系列") }
private enum class WorkSort(val label: String) { RECENT("最近加入"), TITLE("标题"), CREATOR("作者"), SERIES("系列顺序") }
private enum class WorkPresentation { SERIES, WORKS }

@Composable
private fun WorksLibraryScreen(items: List<MediaItem>, viewModel: GalleryViewModel) {
    val works = remember(items) { items.filter { it.domain == MediaDomain.WORKS } }
    var type by rememberSaveable { mutableStateOf(WorkType.COMICS) }
    var facet by rememberSaveable { mutableStateOf(WorkFacet.ALL) }
    var selectedFacet by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(WorkSort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var presentation by rememberSaveable { mutableStateOf(WorkPresentation.SERIES) }
    var selectedSeriesKey by rememberSaveable { mutableStateOf<String?>(null) }
    val typed = remember(works, type) {
        works.filter {
            when (type) {
                WorkType.COMICS -> it.kind == MediaKind.IMAGE_SET
                WorkType.ANIME -> it.kind == MediaKind.VIDEO
            }
        }
    }
    val facetValues = remember(typed, facet) {
        typed.flatMap { item ->
            when (facet) {
                WorkFacet.ALL -> emptyList()
                WorkFacet.AUTHOR -> item.authors
                WorkFacet.TAG -> item.tags
                WorkFacet.SERIES -> listOfNotNull(item.series?.title)
            }
        }.distinctBy { it.lowercase(Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    LaunchedEffect(facet, facetValues) {
        if (selectedFacet !in facetValues) selectedFacet = null
    }
    val shown = remember(typed, facet, selectedFacet, query, sort) {
        typed.asSequence()
            .filter { item ->
                selectedFacet == null || when (facet) {
                    WorkFacet.ALL -> true
                    WorkFacet.AUTHOR -> selectedFacet in item.authors
                    WorkFacet.TAG -> selectedFacet in item.tags
                    WorkFacet.SERIES -> item.series?.title?.equals(selectedFacet, ignoreCase = true) == true
                }
            }
            .filter { item ->
                query.isBlank() || listOf(
                    item.displayTitle,
                    item.originalTitle.orEmpty(),
                    item.authors.joinToString(),
                    item.tags.joinToString(),
                    item.series?.title.orEmpty(),
                ).any { it.contains(query.trim(), ignoreCase = true) }
            }
            .let { sequence ->
                when (sort) {
                    WorkSort.RECENT -> sequence.sortedByDescending(MediaItem::modifiedAt)
                    WorkSort.TITLE -> sequence.sortedBy(MediaItem::displayTitle)
                    WorkSort.CREATOR -> sequence.sortedBy { it.authors.firstOrNull().orEmpty() }
                    WorkSort.SERIES -> SeriesPresentation.shelves(sequence.toList())
                        .flatMap(SeriesShelf::items)
                        .asSequence()
                }
            }.toList()
    }
    val seriesShelves = remember(shown) { SeriesPresentation.shelves(shown) }
    val selectedSeries = remember(seriesShelves, selectedSeriesKey) {
        seriesShelves.firstOrNull { it.key == selectedSeriesKey }
    }
    LaunchedEffect(seriesShelves.map(SeriesShelf::key), presentation) {
        if (presentation != WorkPresentation.SERIES || selectedSeriesKey !in seriesShelves.map(SeriesShelf::key)) {
            selectedSeriesKey = null
        }
    }
    BackHandler(enabled = selectedSeriesKey != null) { selectedSeriesKey = null }
    fun clearSearch() {
        query = ""
        facet = WorkFacet.ALL
        selectedFacet = null
        sort = WorkSort.RECENT
        selectedSeriesKey = null
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            WorkType.entries.forEach { value ->
                FilterChip(
                    selected = type == value,
                    onClick = {
                        type = value
                        selectedFacet = null
                        selectedSeriesKey = null
                    },
                    label = { Text("${value.label} ${works.count { if (value == WorkType.COMICS) it.kind == MediaKind.IMAGE_SET else it.kind == MediaKind.VIDEO }}") },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showTools = true }) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索与筛选")
            }
        }
        if (selectedSeries == null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                FilterChip(
                    selected = presentation == WorkPresentation.SERIES,
                    onClick = { presentation = WorkPresentation.SERIES },
                    label = { Text("系列 ${seriesShelves.count { !it.isUnassigned }}") },
                )
                FilterChip(
                    selected = presentation == WorkPresentation.WORKS,
                    onClick = {
                        presentation = WorkPresentation.WORKS
                        selectedSeriesKey = null
                    },
                    label = { Text("全部作品 ${shown.size}") },
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                IconButton(onClick = { selectedSeriesKey = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回系列书架")
                }
                Column {
                    Text(selectedSeries.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${selectedSeries.items.size} 部作品 · 按系列顺序",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (query.isNotBlank() || selectedFacet != null || facet != WorkFacet.ALL || sort != WorkSort.RECENT) {
            Text(
                "已启用搜索或筛选 · ${shown.size} 个结果",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
        when {
            selectedSeries != null -> MediaGrid(
                selectedSeries.items,
                viewModel,
                { viewModel.open(it, selectedSeries.items) },
                Modifier.weight(1f),
            )
            presentation == WorkPresentation.SERIES -> SeriesShelfGrid(
                shelves = seriesShelves,
                viewModel = viewModel,
                onOpen = { selectedSeriesKey = it.key },
                modifier = Modifier.weight(1f),
            )
            else -> {
                Text(
                    "${shown.size} 部作品",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                MediaGrid(shown, viewModel, { viewModel.open(it, shown) }, Modifier.weight(1f))
            }
        }
    }
    RightSidePanel(
        visible = showTools,
        title = "搜索、索引与排序",
        onDismiss = { showTools = false },
    ) {
        Text(
            "筛选仅影响当前作品页，不会修改文件或元数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            label = { Text("标题、作者、标签或系列") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("索引", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WorkFacet.entries) { value ->
                FilterChip(
                    selected = facet == value,
                    onClick = {
                        facet = value
                        selectedFacet = null
                    },
                    label = { Text(value.label) },
                )
            }
        }
        if (facetValues.isNotEmpty()) {
            Text("${facet.label}值", style = MaterialTheme.typography.titleSmall)
            FilterChip(
                selected = selectedFacet == null,
                onClick = { selectedFacet = null },
                label = { Text("全部") },
            )
            facetValues.take(MAX_CONTEXT_FACETS).forEach { value ->
                FilterChip(
                    selected = selectedFacet == value,
                    onClick = { selectedFacet = value },
                    label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (facetValues.size > MAX_CONTEXT_FACETS) {
                Text(
                    "索引较多，仅显示前 $MAX_CONTEXT_FACETS 项；可直接在上方搜索。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("排序", style = MaterialTheme.typography.titleSmall)
        WorkSort.entries.forEach { value ->
            FilterChip(
                selected = sort == value,
                onClick = { sort = value },
                label = { Text(value.label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(onClick = ::clearSearch, modifier = Modifier.fillMaxWidth()) {
            Text("清除搜索与筛选")
        }
    }
}

@Composable
private fun SeriesShelfGrid(
    shelves: List<SeriesShelf>,
    viewModel: GalleryViewModel,
    onOpen: (SeriesShelf) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (shelves.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("这里还没有系列或单篇作品", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(154.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        gridItems(shelves, key = SeriesShelf::key) { shelf ->
            Card(onClick = { onOpen(shelf) }) {
                shelf.items.firstOrNull()?.let { cover ->
                    MediaThumbnail(
                        item = cover,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.9f),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        shelf.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (shelf.isUnassigned) {
                            "${shelf.items.size} 部独立作品"
                        } else {
                            "${shelf.items.size} 部 · 可手动设置顺序"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassifiedMediaScreen(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    rootDirectory: String,
    emptyText: String,
    modifier: Modifier = Modifier,
    currentPath: String? = null,
    onPathChange: (String?) -> Unit = {},
    browsingItemsFor: (MediaItem, List<MediaItem>) -> List<MediaItem> = { _, visible -> visible },
    supportingText: (MediaItem) -> String? = { null },
    compact: Boolean = true,
) {
    var showTools by rememberSaveable(rootDirectory) { mutableStateOf(false) }
    val classifications = remember(items, rootDirectory) {
        items.associateWith { item -> item.classificationPaths(rootDirectory) }
    }
    val childFolders = remember(classifications, currentPath) {
        val prefix = currentPath?.let { "$it/" }.orEmpty()
        classifications.values.flatten().mapNotNull { path ->
            when {
                currentPath == null -> path.substringBefore('/').takeIf(String::isNotBlank)
                path.startsWith(prefix) -> path.removePrefix(prefix).substringBefore('/')
                    .takeIf(String::isNotBlank)
                else -> null
            }
        }.distinct()
            .filterNot { currentPath == null && it == "未分类" }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val visibleItems = remember(items, classifications, currentPath) {
        val target = currentPath ?: "未分类"
        items.filter { item -> target in classifications.getValue(item) }
    }

    BackHandler(enabled = currentPath != null) {
        onPathChange(currentPath?.substringBeforeLast('/', "")?.takeIf(String::isNotBlank))
    }

    Column(modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            LazyRow(
                contentPadding = PaddingValues(start = 12.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                item {
                    FilterChip(
                        selected = currentPath == null,
                        onClick = { onPathChange(null) },
                        label = { Text("全部目录 ${items.size}") },
                    )
                }
                currentPath?.let { path ->
                    path.split('/').forEachIndexed { index, segment ->
                        val target = path.split('/').take(index + 1).joinToString("/")
                        item(target) {
                            FilterChip(
                                selected = index == path.count { it == '/' },
                                onClick = { onPathChange(target) },
                                label = { Text(segment) },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { showTools = true }) {
                Icon(Icons.Rounded.Tune, contentDescription = "目录工具")
            }
        }
        if (childFolders.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(childFolders, key = { "folder:$currentPath/$it" }) { folder ->
                    val path = listOfNotNull(currentPath, folder).joinToString("/")
                    val count = classifications.count { (_, paths) ->
                        paths.any { it == path || it.startsWith("$path/") }
                    }
                    FilterChip(
                        selected = false,
                        onClick = { onPathChange(path) },
                        leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                        label = { Text("$folder · $count") },
                    )
                }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, modifier = Modifier.padding(20.dp))
            }
        } else if (visibleItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (childFolders.isEmpty()) "这个目录还没有媒体" else "从上方选择文件夹")
            }
        } else {
            MediaGrid(
                items = visibleItems,
                viewModel = viewModel,
                onOpen = { item -> viewModel.open(item, browsingItemsFor(item, visibleItems)) },
                modifier = Modifier.weight(1f),
                compact = compact,
                supportingText = supportingText,
            )
        }
    }
    RightSidePanel(
        visible = showTools,
        title = "目录与视图",
        onDismiss = { showTools = false },
    ) {
        Text("当前路径", style = MaterialTheme.typography.labelLarge)
        Text(
            currentPath?.let { "Library / $it" } ?: "Library / 全部目录",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${visibleItems.size} 项媒体 · ${childFolders.size} 个子文件夹",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = { onPathChange(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Home, contentDescription = null)
            Text(" 回到全部目录")
        }
        if (currentPath != null) {
            FilledTonalButton(
                onClick = {
                    onPathChange(currentPath?.substringBeforeLast('/', "")?.takeIf(String::isNotBlank))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Text(" 返回上一级")
            }
        }
        if (childFolders.isNotEmpty()) {
            Text("子文件夹", style = MaterialTheme.typography.titleSmall)
            childFolders.forEach { folder ->
                val path = listOfNotNull(currentPath, folder).joinToString("/")
                val count = classifications.count { (_, paths) ->
                    paths.any { it == path || it.startsWith("$path/") }
                }
                FilledTonalButton(
                    onClick = {
                        onPathChange(path)
                        showTools = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null)
                    Text(" $folder · $count", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            "点按打开；长按任意缩略图可编辑信息、收藏、复制或移入回收站。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun MediaItem.classificationPaths(rootDirectory: String): List<String> {
    val parent = relativePath.substringBeforeLast('/', "")
    val segments = parent.replace('\\', '/').trim('/').split('/').filter(String::isNotBlank)
    val relativeSegments = when {
        segments.firstOrNull().equals(rootDirectory, ignoreCase = true) -> segments.drop(1)
        segments.firstOrNull().equals("Media", ignoreCase = true) &&
            segments.getOrNull(1).equals(rootDirectory, ignoreCase = true) -> segments.drop(2)
        else -> segments
    }
    val relativeParent = relativeSegments.joinToString("/").ifBlank { "未分类" }
    return listOf(relativeParent.ifBlank { "未分类" })
}

private enum class AlbumFilter(val label: String) { ALL("全部"), IMAGES("照片"), VIDEOS("视频"), LIVE("实况") }
private enum class AlbumOrder(val label: String) { NEWEST("最新优先"), OLDEST("最早优先") }

@Composable
private fun PhotosScreen(items: List<MediaItem>, viewModel: GalleryViewModel) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showBatchEditor by remember { mutableStateOf(false) }
    var confirmBatchTrash by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(AlbumFilter.ALL) }
    var order by rememberSaveable { mutableStateOf(AlbumOrder.NEWEST) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    val shown = remember(items, filter, order) {
        items.asSequence().filter { item ->
            when (filter) {
                AlbumFilter.ALL -> true
                AlbumFilter.IMAGES -> item.kind == MediaKind.PHOTO
                AlbumFilter.VIDEOS -> item.kind == MediaKind.PHOTO_VIDEO
                AlbumFilter.LIVE -> item.kind == MediaKind.LIVE_PHOTO
            }
        }.let { sequence ->
            when (order) {
                AlbumOrder.NEWEST -> sequence.sortedByDescending { it.capturedAt ?: it.modifiedAt }
                AlbumOrder.OLDEST -> sequence.sortedBy { it.capturedAt ?: it.modifiedAt }
            }
        }.toList()
    }
    val availableIds = remember(shown) { shown.mapTo(mutableSetOf(), MediaItem::id) }
    LaunchedEffect(availableIds) { selected = selected.intersect(availableIds) }
    val selectedItems = shown.filter { it.id in selected }
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
                    selected = if (selected.size == shown.size) emptySet() else availableIds
                }) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = null)
                    Text(if (selected.size == shown.size) "清空" else "全选")
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
                        Text("派生漫画/图集")
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
                    "${order.label} · ${filter.label} ${shown.size} 项 · 长按打开操作",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showTools = true }) {
                    Icon(Icons.Rounded.Tune, contentDescription = "相册视图选项")
                }
                TextButton(onClick = { selectionMode = true }) { Text("选择") }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("从系统相册导入，或在 Library 的 Photos 目录中放入媒体")
            }
        } else {
            MediaGrid(
                items = shown,
                viewModel = viewModel,
                onOpen = { viewModel.open(it, shown) },
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
    RightSidePanel(
        visible = showTools,
        title = "相册视图",
        onDismiss = { showTools = false },
    ) {
        Text("媒体类型", style = MaterialTheme.typography.titleSmall)
        AlbumFilter.entries.forEach { value ->
            FilterChip(
                selected = filter == value,
                onClick = { filter = value },
                label = { Text(value.label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("时间顺序", style = MaterialTheme.typography.titleSmall)
        AlbumOrder.entries.forEach { value ->
            FilterChip(
                selected = order == value,
                onClick = { order = value },
                label = { Text(value.label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "相册保持纯净，只按拍摄时间与媒体类型浏览；长按项目可编辑标题、收藏或进入多选。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            ) { Text("从多张图片创建漫画/图集") }
        }
        RememberingClassifiedMediaScreen(
            items = items,
            viewModel = viewModel,
            rootDirectory = "Images",
            emptyText = "Library 中的独立图片会显示在这里",
            modifier = Modifier.weight(1f),
        )
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
private fun RememberingClassifiedMediaScreen(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    rootDirectory: String,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    var currentPath by rememberSaveable(rootDirectory) { mutableStateOf<String?>(null) }
    ClassifiedMediaScreen(
        items = items,
        viewModel = viewModel,
        rootDirectory = rootDirectory,
        emptyText = emptyText,
        currentPath = currentPath,
        onPathChange = { currentPath = it },
        modifier = modifier,
    )
}

@Composable
private fun CreateImageSetDialog(
    items: List<MediaItem>,
    initialSelected: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onCreate: (List<String>, String) -> Unit,
) {
    var title by remember { mutableStateOf("新漫画/图集") }
    var selected by remember(items, initialSelected) {
        mutableStateOf(initialSelected.intersect(items.mapTo(mutableSetOf(), MediaItem::id)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建漫画/图集") },
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
        }.distinctBy { it.lowercase(Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    var selected by remember(facet, values) { mutableStateOf(values.firstOrNull()) }
    if (values.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(facet.emptyText) }
        return
    }
    val matching = items.filter { item ->
        when (facet) {
            Facet.SERIES -> item.series?.title?.equals(selected, ignoreCase = true) == true
            Facet.COLLECTION -> selected in item.collections
            Facet.AUTHOR -> selected in item.authors
            Facet.TAG -> selected in item.tags
        }
    }
    val filtered = if (facet == Facet.SERIES) {
        SeriesPresentation.orderEntries(matching)
    } else {
        matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, MediaItem::displayTitle))
    }
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
                    leadingContent = {
                        MediaThumbnail(
                            item = item,
                            viewModel = viewModel,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(MaterialTheme.shapes.medium),
                        )
                    },
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
    val offlinePreviewStats by viewModel.offlinePreviewStats.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var diagnosticsText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshOfflinePreviewStats() }
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
            Text("离线小预览", style = MaterialTheme.typography.titleMedium)
            Text(
                "已保存 ${offlinePreviewStats.files} 张 · ${offlinePreviewStats.bytes.formatBytes()}。" +
                    "浏览缩略图时按需生成，Library 拔出后仍可用于辨认内容；不含原图，也不写入移动介质。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = viewModel::clearOfflinePreviews,
                enabled = offlinePreviewStats.files > 0,
            ) { Text("清除离线预览") }
        }
        item {
            Text("诊断日志", style = MaterialTheme.typography.titleMedium)
            Text(
                "日志只存放在本机 App 私有目录，不写入 Library，卸载即清除；" +
                    "默认只记录警告与错误，导出时账号信息和磁盘路径会被隐去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { diagnosticsText = RemLog.tail(context) }) { Text("查看最近日志") }
                OutlinedButton(onClick = { RemLog.share(context) }) { Text("导出") }
            }
            TextButton(onClick = { RemLog.clear(context) }) { Text("清除日志") }
        }
        item {
            Text("Rem ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text(
                "本机索引和缩略图只是缓存；Library 中的 .gallery 元数据才是跨设备状态来源。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    diagnosticsText?.let { content ->
        AlertDialog(
            onDismissRequest = { diagnosticsText = null },
            title = { Text("最近日志") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(
                            content.ifBlank { "暂无日志记录。" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { diagnosticsText = null }) { Text("关闭") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        RemLog.share(context)
                        diagnosticsText = null
                    },
                ) { Text("导出") }
            },
        )
    }
}

private val PHOTO_KINDS = setOf(MediaKind.PHOTO, MediaKind.PHOTO_VIDEO, MediaKind.LIVE_PHOTO)
private const val MAX_CONTEXT_FACETS = 80

private fun Long.formatBytes(): String = when {
    this >= 1_073_741_824 -> "%.1f GB".format(this / 1_073_741_824.0)
    this >= 1_048_576 -> "%.1f MB".format(this / 1_048_576.0)
    this >= 1_024 -> "%.1f KB".format(this / 1_024.0)
    else -> "$this B"
}
