package dev.susnowy.gallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaSeries
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.ui.GalleryViewModel
import dev.susnowy.gallery.ui.SeriesChapter
import dev.susnowy.gallery.ui.SeriesReading
import dev.susnowy.gallery.ui.components.MediaThumbnail

/**
 * The chapter list of one Series.
 *
 * A series is one entry point: this screen shows every chapter with its reading state and
 * offers "continue reading" instead of dropping the reader into a grid of works. Opening a
 * chapter keeps the list as the reader's context, which is what lets the reader hand over to
 * the next chapter at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesChapterList(
    title: String,
    chapters: List<MediaItem>,
    series: MediaSeries?,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onOpenChapter: (chapter: MediaItem, ordered: List<MediaItem>) -> Unit,
    onEditSeries: () -> Unit,
) {
    val ordered = remember(chapters) { chapters }
    val progress by produceState<Map<String, PlaybackProgress>>(
        initialValue = emptyMap(),
        ordered.map(MediaItem::id),
        ordered.map(MediaItem::modifiedAt),
    ) {
        value = runCatching { viewModel.progressFor(ordered) }.getOrDefault(emptyMap())
    }
    val entries = remember(ordered, progress) { SeriesReading.chapters(ordered, progress) }
    val entry = remember(entries) { SeriesReading.entry(entries) }
    val summary = remember(entries) { SeriesReading.summarize(entries) }
    var autoAdvance by remember { mutableStateOf(viewModel.autoAdvanceChaptersEnabled) }

    BackHandler(enabled = true) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            summary.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回系列书架")
                    }
                },
                actions = {
                    if (series != null) {
                        IconButton(onClick = onEditSeries) {
                            Icon(Icons.Rounded.Edit, contentDescription = "编辑系列")
                        }
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
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Button(
                        enabled = entry.enabled,
                        onClick = { entry.chapter?.let { onOpenChapter(it.item, ordered) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text(" ${entry.label}")
                    }
                    if (summary.total > 1) {
                        LinearProgressIndicator(
                            progress = { summary.finished.toFloat() / summary.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("读完一话后自动进入下一话", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关闭时会停在话末，仍可手动翻回；不会跳过任何一话。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoAdvance,
                            onCheckedChange = {
                                autoAdvance = it
                                viewModel.setAutoAdvanceChapters(it)
                            },
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.item.id }) { index, chapter ->
                    ChapterRow(
                        chapter = chapter,
                        title = series?.positionLabel(chapter.item.id),
                        viewModel = viewModel,
                        onClick = { onOpenChapter(chapter.item, ordered) },
                    )
                    if (index != entries.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: SeriesChapter,
    title: String?,
    viewModel: GalleryViewModel,
    onClick: () -> Unit,
) {
    val item = chapter.item
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { MediaThumbnail(item, viewModel, Modifier.fillMaxSize()) }
        },
        headlineContent = {
            Text(
                "${chapter.position + 1}. ${title ?: item.displayTitle}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (chapter.started && !chapter.finished) FontWeight.SemiBold else null,
            )
        },
        supportingContent = {
            Text(
                listOfNotNull(
                    title?.let { item.displayTitle },
                    chapter.progressLabel(),
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            if (chapter.finished) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "已读完",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "${chapter.position + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
