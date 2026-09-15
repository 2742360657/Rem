package dev.susnowy.gallery.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        AppScreen.PHOTOS -> MediaCollectionScreen(
            items = visible.filter { it.kind in PHOTO_KINDS }.sortedByDescending { it.capturedAt ?: it.modifiedAt },
            viewModel = viewModel,
            emptyText = "从系统相册导入，或在 Library 的 Photos 目录中放入媒体",
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
        AppScreen.SEARCH -> SearchScreen(visible, state.searchQuery, viewModel)
        AppScreen.TRASH -> TrashScreen(state.media.filter(MediaItem::trashed), viewModel)
        AppScreen.ORGANIZER -> OrganizerScreen(viewModel)
        AppScreen.SETTINGS -> SettingsScreen(state, viewModel)
    }
}

@Composable
private fun HomeScreen(items: List<MediaItem>, viewModel: GalleryViewModel) {
    val inbox = items.count(MediaItem::inInbox)
    val imageSets = items.count { it.kind == MediaKind.IMAGE_SET }
    val videos = items.count { it.kind == MediaKind.VIDEO }
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
                item { StatCard("全部内容", items.size.toString()) }
                item { StatCard("待整理", inbox.toString()) }
                item { StatCard("ImageSet", imageSets.toString()) }
                item { StatCard("视频", videos.toString()) }
            }
        }
        if (items.isNotEmpty()) {
            item { Text("最近内容", style = MaterialTheme.typography.titleLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items.sortedByDescending(MediaItem::modifiedAt).take(12), key = MediaItem::id) { item ->
                        MediaCard(
                            item = item,
                            viewModel = viewModel,
                            onClick = { viewModel.open(item) },
                            modifier = Modifier.width(170.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(modifier = Modifier.width(150.dp)) {
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
                            TextButton(onClick = { viewModel.forgetLibrary(library.libraryId) }) {
                                Text("移除登记")
                            }
                        }
                    },
                )
            }
        }
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
        MediaGrid(items = items, viewModel = viewModel, onOpen = viewModel::open)
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
            MediaGrid(items, viewModel, viewModel::open, Modifier.weight(1f))
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
    onDismiss: () -> Unit,
    onCreate: (List<String>, String) -> Unit,
) {
    var title by remember { mutableStateOf("新 ImageSet") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
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
        MediaGrid(filtered, viewModel, viewModel::open, Modifier.weight(1f))
    }
}

@Composable
private fun SearchScreen(items: List<MediaItem>, query: String, viewModel: GalleryViewModel) {
    var kind by remember { mutableStateOf<MediaKind?>(null) }
    val normalized = query.trim()
    val filtered = items.filter { item ->
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
                FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("全部") })
            }
            items(MediaKind.entries) { value ->
                FilterChip(
                    selected = kind == value,
                    onClick = { kind = value },
                    label = { Text(value.name.lowercase()) },
                )
            }
        }
        Text("${filtered.size} 个结果", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        MediaGrid(filtered, viewModel, viewModel::open, Modifier.weight(1f))
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
    var daysText by remember(state.trashRetentionDays) { mutableStateOf(state.trashRetentionDays.toString()) }
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
            OutlinedTextField(
                value = daysText,
                onValueChange = { daysText = it.filter(Char::isDigit).take(4) },
                label = { Text("回收站保留天数") },
                supportingText = { Text("到期内容仍需经过身份验证后才会真删除") },
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
