package dev.susnowy.gallery.ui

import android.content.Intent
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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.VideoLibrary
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.susnowy.gallery.ui.screens.EmptyLibraryScreen
import dev.susnowy.gallery.ui.screens.GalleryScreenContent
import dev.susnowy.gallery.ui.screens.MediaDetail
import dev.susnowy.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.launch

private data class DrawerDestination(
    val screen: AppScreen,
    val icon: ImageVector,
)

private val destinations = listOf(
    DrawerDestination(AppScreen.HOME, Icons.Rounded.Home),
    DrawerDestination(AppScreen.LIBRARIES, Icons.Rounded.Folder),
    DrawerDestination(AppScreen.INBOX, Icons.Rounded.Inventory2),
    DrawerDestination(AppScreen.PHOTOS, Icons.Rounded.PhotoLibrary),
    DrawerDestination(AppScreen.IMAGES, Icons.Rounded.Image),
    DrawerDestination(AppScreen.IMAGE_SETS, Icons.Rounded.Collections),
    DrawerDestination(AppScreen.VIDEOS, Icons.Rounded.VideoLibrary),
    DrawerDestination(AppScreen.SERIES, Icons.Rounded.Movie),
    DrawerDestination(AppScreen.COLLECTIONS, Icons.Rounded.Source),
    DrawerDestination(AppScreen.AUTHORS, Icons.Rounded.Person),
    DrawerDestination(AppScreen.TAGS, Icons.Rounded.LocalOffer),
    DrawerDestination(AppScreen.SEARCH, Icons.Rounded.Search),
    DrawerDestination(AppScreen.TRASH, Icons.Rounded.DeleteOutline),
    DrawerDestination(AppScreen.ORGANIZER, Icons.Rounded.Source),
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
    var libraryMenuExpanded by remember { mutableStateOf(false) }
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

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    GalleryTheme {
        if (state.selectedItem != null) {
            MediaDetail(
                item = state.selectedItem!!,
                viewModel = viewModel,
                onBack = viewModel::closeDetail,
            )
            return@GalleryTheme
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
                            "Gallery",
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
                            if (index == 3 || index == 12) HorizontalDivider(Modifier.padding(vertical = 6.dp))
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
                                    Text(state.activeLibrary?.name ?: state.screen.title)
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
                floatingActionButton = {
                    if (state.screen == AppScreen.LIBRARIES || state.libraries.isEmpty()) {
                        ExtendedFloatingActionButton(
                            onClick = { folderPicker.launch(null) },
                            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            text = { Text("接入 Library") },
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
                        GalleryScreenContent(
                            state = state,
                            viewModel = viewModel,
                            onChooseFolder = { folderPicker.launch(null) },
                        )
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
                            }
                        }
                    }
                }
            }
        }
    }
}
