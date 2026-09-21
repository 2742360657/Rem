package dev.susnowy.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.style.TextOverflow
import dev.susnowy.gallery.model.SortMode
import dev.susnowy.gallery.model.ViewMode
import dev.susnowy.gallery.ui.theme.RemTheme

/**
 * The whole app: one screen, two sections, and one project drill-down.
 *
 * There is no navigation library because there is nothing to navigate: the rules cap this at the
 * album, the collection, and one project inside it, and every "page" is a branch in this function.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemApp(viewModel: RemViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var violationsExpanded by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    // What the viewer is showing, if anything. Every action that changes which Library is loaded
    // clears it first: the list it pages through belongs to the Library that was on screen.
    var viewerRequest by remember { mutableStateOf<ViewerRequest?>(null) }
    // Two drawers with two jobs: content on the left, the Library itself on the right. Neither is
    // reachable by an accidental swipe in the wrong direction, so the gestures stay predictable.
    var libraryMenuOpen by remember { mutableStateOf(false) }
    val contentDrawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::attach)
    }
    // The scan's own tree, so the listing and URI caches behind it are already warm. Building a
    // second one here meant a provider round-trip per visible cell while composing.
    // Read from the state, not from a plain accessor: the tree is built on a background thread
    // when the Library opens, and reading it outside composition state caches the null it had
    // first, leaving every thumbnail empty.
    val thumbnails = rememberThumbnails(context, state.tree)

    // A removable volume answers with an empty listing for a moment after it is mounted. Watching
    // for that moment means inserting the drive is enough — no detach and re-attach.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshIfVolumeAppeared()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    RemTheme {
        if (logOpen) {
            BackHandler { logOpen = false }
            LogScreen(onBack = { logOpen = false })
            return@RemTheme
        }
        val openViewer = viewerRequest
        if (openViewer != null) {
            BackHandler { viewerRequest = null }
            ViewerScreen(
                request = openViewer,
                fileUri = viewModel::documentUri,
                onOpenWith = viewModel::open,
                onClose = { viewerRequest = null },
            )
            return@RemTheme
        }
        if (!state.attached) {
            AttachLibraryScreen(onChoose = { picker.launch(null) })
            return@RemTheme
        }
        val insideFolder = state.currentFolder != null
        BackHandler(enabled = insideFolder) { viewModel.closeFolder() }

        // One place for everything that is not navigation: sections, filters, order, layout and the
        // Library actions. They used to sit as rows of chips above every screen, which cost the
        // screen space they were meant to help with.
        ModalNavigationDrawer(
            drawerState = contentDrawer,
            // Reachable from anywhere. Swiping was disabled inside a collection folder, which meant
            // the sidebar could not be opened at all while walking the tree — the one place its
            // order and view choices matter most. The viewer is a separate full-screen surface, so
            // there is no paging gesture here for the drawer to fight with.
            gesturesEnabled = true,
            drawerContent = {
                ContentSidebar(
                    state = state,
                    onSelectFilter = viewModel::selectFilter,
                    onSelectSort = viewModel::selectSortMode,
                    onToggleDirection = viewModel::toggleSortDirection,
                    onSelectViewMode = viewModel::selectViewMode,
                )
            },
        ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(state.libraryName.ifEmpty { "Rem" }) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { contentDrawer.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "侧边栏")
                        }
                    },
                    actions = {
                        if (state.violations.isNotEmpty()) {
                            IconButton(onClick = { violationsExpanded = true }) {
                                Icon(Icons.Rounded.ErrorOutline, contentDescription = "文件规范问题")
                            }
                        }
                        IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描")
                        }
                        Box {
                            IconButton(onClick = { libraryMenuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "库操作")
                            }
                            LibraryMenu(
                                expanded = libraryMenuOpen,
                                onDismiss = { libraryMenuOpen = false },
                                state = state,
                                onChangeLibrary = { libraryMenuOpen = false; picker.launch(null) },
                                onDetachLibrary = { libraryMenuOpen = false; viewModel.detach() },
                                onRebuildIndex = { libraryMenuOpen = false; viewModel.rebuildIndex() },
                                onClearThumbnails = { libraryMenuOpen = false; viewModel.clearThumbnails() },
                                onOpenLog = { libraryMenuOpen = false; logOpen = true },
                            )
                        }
                    },
                )
            },
            bottomBar = {
                // Hidden while a folder is open: the drill-down has its own breadcrumb, and a tab
                // bar underneath it would suggest the folder is a fourth destination.
                if (state.currentFolder == null) {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == state.tab,
                                onClick = { viewModel.selectTab(tab) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.title) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (state.refreshing) {
                    ScanningBar(progress = state.scanProgress)
                } else {
                    // The Library is already usable; this only says that capture times are still
                    // arriving, so a list ordered by modification time today will reorder itself.
                    val pending = state.metadataPending
                    if (pending != null && pending > 0) {
                        Text(
                            text = "正在后台读取拍摄时间，剩余 $pending 个文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
                when {
                    state.tab == Tab.ALBUM -> AlbumScreen(
                        state = state,
                        thumbnail = thumbnails,
                        onOpen = { index ->
                            // The viewer pages through exactly what the grid shows, filter included.
                            ViewerState.request(state.visibleAlbum, index)?.let { viewerRequest = it }
                        },
                    )
                    state.tab == Tab.COLLECTION -> CollectionScreen(
                        state = state,
                        onSearch = viewModel::search,
                        onOpenFolder = { viewModel.openFolder(it.path) },
                        onBack = viewModel::closeFolder,
                        thumbnail = thumbnails,
                        onOpen = { index ->
                            // Only the folder being browsed: the viewer never pages across folders.
                            ViewerState.request(state.folderMedia, index)?.let { viewerRequest = it }
                        },
                    )
                    else -> SettingsScreen(
                        state = state,
                        onHideFromSystemGallery = viewModel::setHideFromSystemGallery,
                        onOpenLog = { logOpen = true },
                    )
                }
            }
        }
        }
        if (violationsExpanded) {
            ViolationsDialog(
                violations = state.violations,
                onDismiss = {
                    violationsExpanded = false
                    viewModel.dismissViolations()
                },
            )
        }
    }
}

/** Shown until a Library is attached; there is nothing else this app can do without one. */
@Composable
private fun AttachLibraryScreen(onChoose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "选择一个 Library 文件夹",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "需要包含「相册」和「画集」的文件夹。Rem 只读取，不会改动任何文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            TextButton(onClick = onChoose) { Text("接入 Library") }
        }
    }
}

/**
 * The scan line: a bar plus how far the pass has got.
 *
 * A real count matters here. A first pass over a Library on a removable volume runs for minutes,
 * and a bare indeterminate bar looks identical to a frozen app.
 */
@Composable
private fun ScanningBar(progress: ScanProgress?) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            text = progress?.takeIf { it.total > 0 }?.let { "正在扫描 ${it.scanned} / ${it.total}" }
                ?: "正在读取目录…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

/** Lists what does not follow the file rules. Rem reports these; it never corrects them. */
@Composable
private fun ViolationsDialog(violations: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件规范问题（${violations.size}）") },
        text = {
            LazyColumn {
                items(violations) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/**
 * Everything that is not navigation, in one place.
 *
 * The sections are the primary axis and stay at the top; filters, order and layout apply to whatever
 * is on screen and sit under them; the Library actions are last, because they are the ones that
 * change what the app is even looking at.
 */
@Composable
private fun ContentSidebar(
    state: UiState,
    onSelectFilter: (MediaFilter) -> Unit,
    onSelectSort: (SortMode) -> Unit,
    onToggleDirection: () -> Unit,
    onSelectViewMode: (ViewMode) -> Unit,
) {
    // What the sidebar offers follows where the user is. It used to show every control everywhere,
    // which promised things that were not true: an order selector in the album, whose order is
    // fixed at capture time, and a sort that reads as "sort these files" while the level on screen
    // is a list of folders.
    val inAlbum = state.tab == Tab.ALBUM
    val inFolder = state.currentFolder != null
    // Inside a folder the files are what is being ordered, so every order applies. At the
    // collection's top level the rows are folders, and only their own two orders mean anything.
    val sortOptions = if (inFolder) {
        SortMode.entries
    } else {
        listOf(SortMode.NAME, SortMode.SEQUENCE)
    }

    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = state.libraryName.ifEmpty { "Rem" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${state.entries.size} 个媒体文件 · ${state.folders.size} 个文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            )

            SidebarLabel("显示")
            SidebarChips(
                options = MediaFilter.entries.map { it to it.title },
                selected = state.filter,
                onSelect = onSelectFilter,
            )

            if (!inAlbum) {
                SidebarLabel("排序")
                SidebarChips(
                    options = sortOptions.map { it to it.title },
                    selected = state.sortMode,
                    onSelect = onSelectSort,
                )
                Row(
                    modifier = Modifier.padding(start = 24.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.sortAscending,
                        onClick = onToggleDirection,
                        label = { Text(if (state.sortAscending) "升序 ↑" else "降序 ↓") },
                    )
                }
                Text(
                    text = if (inFolder) {
                        "排序作用于当前文件夹里的文件。"
                    } else {
                        "在画集顶层，排序作用于项目文件夹。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
                )
            } else {
                // The album's order is fixed at capture time, so there is nothing to choose — but
                // its direction is real, and hiding the control with the rest would have taken away
                // a switch that works.
                SidebarLabel("排序")
                Row(
                    modifier = Modifier.padding(start = 24.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.sortAscending,
                        onClick = onToggleDirection,
                        label = { Text(if (state.sortAscending) "升序 ↑" else "降序 ↓") },
                    )
                }
                Text(
                    text = "相册固定按拍摄时间排列，这里只切换方向。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
                )
            }

            SidebarLabel("视图")
            SidebarChips(
                options = ViewMode.entries.map { it to it.title },
                selected = state.viewMode,
                onSelect = onSelectViewMode,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SidebarLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** A wrapped row of choices. Wrapping matters: five orders do not fit on one phone line. */
@Composable
private fun <T> SidebarChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * What applies to the Library rather than to the current view.
 *
 * A menu anchored to its button, not a drawer: the content choices already own the left edge, and
 * these are occasional, deliberate actions rather than something to browse.
 */
@Composable
private fun LibraryMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: UiState,
    onChangeLibrary: () -> Unit,
    onDetachLibrary: () -> Unit,
    onRebuildIndex: () -> Unit,
    onClearThumbnails: () -> Unit,
    onOpenLog: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            text = state.libraryName.ifEmpty { "未接入 Library" },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            text = "${state.entries.size} 个文件 · ${state.folders.size} 个文件夹" +
                (state.thumbnailBytes?.let { " · 缩略图 ${humanSize(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("重建索引") },
            enabled = state.attached && !state.refreshing,
            onClick = onRebuildIndex,
        )
        DropdownMenuItem(
            text = { Text("清空缩略图缓存") },
            enabled = state.attached,
            onClick = onClearThumbnails,
        )
        DropdownMenuItem(text = { Text("运行日志") }, onClick = onOpenLog)
        HorizontalDivider()
        DropdownMenuItem(text = { Text("更换 Library") }, onClick = onChangeLibrary)
        DropdownMenuItem(text = { Text("断开 Library") }, onClick = onDetachLibrary)
    }
}
