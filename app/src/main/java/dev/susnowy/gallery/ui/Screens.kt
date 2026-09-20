package dev.susnowy.gallery.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.Project
import kotlinx.coroutines.launch

/** The image/video/both selector shared by both sections. */
@Composable
fun FilterRow(
    selected: MediaFilter,
    onSelect: (MediaFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaFilter.entries.forEach { option ->
            androidx.compose.material3.FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option.title) },
            )
        }
    }
}

/** A centred explanation for a list that has nothing to show. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `相册/`: every file in capture order, newest first. */
@Composable
fun AlbumScreen(
    state: UiState,
    onSelectFilter: (MediaFilter) -> Unit,
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        FilterRow(selected = state.filter, onSelect = onSelectFilter)
        if (state.visibleAlbum.isEmpty()) {
            EmptyHint(if (state.album.isEmpty()) "相册里还没有可浏览的图片或视频" else "没有符合当前筛选的媒体")
        } else {
            MediaGrid(entries = state.visibleAlbum, thumbnail = thumbnail, onOpen = onOpen)
        }
    }
}

/** `画集/`: the project folders, with the author/project search box above them. */
@Composable
fun CollectionScreen(
    state: UiState,
    onSearch: (String) -> Unit,
    onSelectFilter: (MediaFilter) -> Unit,
    onOpenProject: (Project) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val projects = state.visibleProjects

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.search,
            onValueChange = onSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("搜索作者或项目名称") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        )
        FilterRow(selected = state.filter, onSelect = onSelectFilter)
        if (projects.isEmpty()) {
            EmptyHint(if (state.projects.isEmpty()) "画集里还没有项目文件夹" else "没有匹配的项目")
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(end = TRACK_WIDTH),
                ) {
                    items(projects, key = Project::folder) { project ->
                        ProjectRow(
                            name = project.name,
                            detail = detailOf(project),
                            onOpen = { onOpenProject(project) },
                        )
                    }
                }
                if (projects.size > 1) {
                    Scrollbar(
                        fraction = listFraction(listState, projects.size),
                        onJump = { fraction -> scope.launch { jumpList(listState, projects.size, fraction) } },
                        labelAt = { fraction ->
                            val project = projects.getOrNull(targetIndex(projects.size, fraction))
                            ScrollLabel(
                                primary = project?.name.orEmpty(),
                                secondary = project?.author.orEmpty(),
                            )
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

/** One project's files, numbered, with a way back to the project list. */
@Composable
fun ProjectScreen(
    project: Project,
    thumbnail: (Entry) -> ImageRequest?,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回画集")
            }
            Column {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${project.author} · ${detailOf(project)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (project.entries.isEmpty()) {
            EmptyHint("这个项目里还没有可浏览的媒体")
        } else {
            MediaGrid(entries = project.entries, thumbnail = thumbnail, onOpen = onOpen)
        }
    }
}

/** `作者 · 12 张图片 3 个视频`, plus a warning when the folder breaks the naming rule. */
private fun detailOf(project: Project): String = buildString {
    append("${project.imageCount} 张图片 · ${project.videoCount} 个视频")
    if (project.offRuleCount > 0) append(" · ${project.offRuleCount} 个文件命名不规范")
}
