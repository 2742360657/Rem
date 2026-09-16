package dev.susnowy.gallery.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.ui.screens.EmptyLibraryScreen
import dev.susnowy.gallery.ui.screens.GalleryScreenContent
import dev.susnowy.gallery.ui.screens.MediaDetail
import dev.susnowy.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.launch

private data class DrawerDestination(
    val screen: AppScreen,
    val icon: ImageVector,
)

private val primaryDestinations = listOf(
    DrawerDestination(AppScreen.PHOTOS, Icons.Rounded.PhotoLibrary),
    DrawerDestination(AppScreen.MEDIA, Icons.Rounded.Folder),
    DrawerDestination(AppScreen.WORKS, Icons.Rounded.Collections),
)

private val destinations = listOf(
    DrawerDestination(AppScreen.LIBRARIES, Icons.Rounded.Folder),
    DrawerDestination(AppScreen.INBOX, Icons.Rounded.Inventory2),
    DrawerDestination(AppScreen.SEARCH, Icons.Rounded.Search),
    DrawerDestination(AppScreen.ORGANIZER, Icons.Rounded.Source),
    DrawerDestination(AppScreen.TRASH, Icons.Rounded.DeleteOutline),
    DrawerDestination(AppScreen.SETTINGS, Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp(viewModel: GalleryViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val screenStateHolder = rememberSaveableStateHolder()
    var libraryMenuExpanded by remember { mutableStateOf(false) }
    var lastExitBackAt by remember { mutableLongStateOf(0L) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.attachTree(uri)
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importSystemMedia(uris)
    }
    val systemMediaPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onSystemMediaPermissionResult()
    }

    LifecycleResumeEffect(state.screen) {
        if (state.screen == AppScreen.SYSTEM_GALLERY) viewModel.refreshSystemMedia()
        onPauseOrDispose { }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    GalleryTheme {
        if (state.selectedItem != null) {
            val selected = state.selectedItem!!
            val contextualItems = state.detailItemIds.mapNotNull { id ->
                state.allMedia.firstOrNull { it.id == id }
            }.filter { it.libraryId == selected.libraryId && !it.trashed }
            val browsingItems = contextualItems.ifEmpty {
                when (selected.kind) {
                    MediaKind.PHOTO, MediaKind.PHOTO_VIDEO, MediaKind.LIVE_PHOTO -> state.media
                        .filter {
                            !it.trashed && it.kind in setOf(
                                MediaKind.PHOTO,
                                MediaKind.PHOTO_VIDEO,
                                MediaKind.LIVE_PHOTO,
                            )
                        }
                        .sortedByDescending { it.capturedAt ?: it.modifiedAt }
                    MediaKind.IMAGE -> state.media.filter { !it.trashed && it.kind == MediaKind.IMAGE }
                    else -> listOf(selected)
                }
            }
            MediaDetail(
                item = selected,
                browsingItems = browsingItems,
                viewModel = viewModel,
                onBack = viewModel::closeDetail,
            )
            return@GalleryTheme
        }

        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
        BackHandler(enabled = drawerState.isClosed) {
            if (state.screen !in primaryDestinations.map(DrawerDestination::screen)) {
                viewModel.navigate(AppScreen.PHOTOS)
                lastExitBackAt = 0L
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now - lastExitBackAt <= EXIT_CONFIRM_WINDOW_MS) {
                    (context as? Activity)?.finish()
                } else {
                    lastExitBackAt = now
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar("再按一次返回退出 Gallery")
                    }
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = state.libraries.isNotEmpty(),
            drawerContent = {
                ModalDrawerSheet {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            "库与管理",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        )
                        Text(
                            state.activeLibrary?.name ?: "未接入 Library",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        destinations.forEachIndexed { index, destination ->
                            if (index == 3) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            NavigationDrawerItem(
                                label = { Text(destination.screen.title) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                selected = state.screen == destination.screen,
                                onClick = {
                                    viewModel.navigate(destination.screen)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Box {
                                TextButton(onClick = { libraryMenuExpanded = true }) {
                                    Column {
                                        Text(state.screen.title)
                                        state.activeLibrary?.let { library ->
                                            Text(
                                                library.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                DropdownMenu(
                                    expanded = libraryMenuExpanded,
                                    onDismissRequest = { libraryMenuExpanded = false },
                                ) {
                                    state.libraries.forEach { library ->
                                        DropdownMenuItem(
                                            text = { Text(library.name) },
                                            onClick = {
                                                viewModel.selectLibrary(library.libraryId)
                                                libraryMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            if (state.libraries.isNotEmpty()) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Rounded.Menu, contentDescription = "菜单")
                                }
                            }
                        },
                        actions = {
                            state.activeLibrary?.let {
                                IconButton(onClick = { viewModel.scan() }, enabled = state.operation == null) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "扫描")
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (state.libraries.isNotEmpty()) {
                        NavigationBar {
                            primaryDestinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = state.screen == destination.screen,
                                    onClick = { viewModel.navigate(destination.screen) },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    label = { Text(destination.screen.title) },
                                )
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (state.screen == AppScreen.LIBRARIES || state.libraries.isEmpty()) {
                        ExtendedFloatingActionButton(
                            onClick = { folderPicker.launch(null) },
                            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            text = { Text("接入 Library") },
                        )
                    } else if (state.screen == AppScreen.PHOTOS) {
                        ExtendedFloatingActionButton(
                            onClick = { viewModel.navigate(AppScreen.SYSTEM_GALLERY) },
                            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            text = { Text("打开系统相册") },
                        )
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (state.libraries.isEmpty()) {
                        EmptyLibraryScreen(onChooseFolder = { folderPicker.launch(null) })
                    } else {
                        screenStateHolder.SaveableStateProvider(
                            key = "${state.activeLibraryId}:${state.screen.name}",
                        ) {
                            GalleryScreenContent(
                                state = state,
                                viewModel = viewModel,
                                onChooseFolder = { folderPicker.launch(null) },
                                onRequestSystemMediaAccess = {
                                    systemMediaPermission.launch(systemMediaPermissions())
                                },
                                onFallbackMediaPicker = {
                                    mediaPicker.launch(arrayOf("image/*", "video/*"))
                                },
                                onOpenAppSettings = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    state.operation?.let { operation ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text(operation, modifier = Modifier.padding(top = 8.dp))
                                TextButton(onClick = viewModel::cancelLongOperation) { Text("取消") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun systemMediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
