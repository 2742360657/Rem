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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
    var menuExpanded by remember { mutableStateOf(false) }
    var violationsExpanded by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::attach)
    }
    val thumbnails = rememberThumbnails(context, state.treeUri)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    RemTheme {
        if (!state.attached) {
            AttachLibraryScreen(onChoose = { picker.launch(null) })
            return@RemTheme
        }
        val project = state.currentProject
        BackHandler(enabled = project != null) { viewModel.closeProject() }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(state.libraryName.ifEmpty { "Rem" }) },
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
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("更换 Library") },
                                    onClick = {
                                        menuExpanded = false
                                        picker.launch(null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("断开 Library") },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.detach()
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (state.refreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                when {
                    project != null -> ProjectScreen(
                        project = project,
                        thumbnail = thumbnails,
                        onOpen = viewModel::open,
                        onBack = viewModel::closeProject,
                    )
                    else -> {
                        TabRow(selectedTabIndex = state.tab.ordinal) {
                            Tab.entries.forEach { tab ->
                                Tab(
                                    selected = tab == state.tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    text = { Text(tab.title) },
                                )
                            }
                        }
                        when (state.tab) {
                            Tab.ALBUM -> AlbumScreen(
                                state = state,
                                onSelectFilter = viewModel::selectFilter,
                                thumbnail = thumbnails,
                                onOpen = viewModel::open,
                            )
                            Tab.COLLECTION -> CollectionScreen(
                                state = state,
                                onSearch = viewModel::search,
                                onSelectFilter = viewModel::selectFilter,
                                onOpenProject = { viewModel.openProject(it.folder) },
                            )
                        }
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
