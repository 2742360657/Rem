package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.susnowy.gallery.compare.EditionComparisonReport
import dev.susnowy.gallery.compare.PageMatchKind
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.ui.GalleryViewModel

/**
 * Compares two sources page by page and can write a virtual merged Edition.
 *
 * The dialog is explicit about cost: the quick pass reads nothing, the deep pass reads each
 * source exactly once, and either can be cancelled. Writing the merged version only records a
 * reading order — no file is copied, moved, rewritten or deleted.
 */
@Composable
fun EditionCompareDialog(
    left: MediaItem,
    candidates: List<MediaItem>,
    viewModel: GalleryViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.comparison.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<MediaItem?>(null) }
    var confirmMerge by remember { mutableStateOf(false) }
    val report = state.report

    AlertDialog(
        onDismissRequest = {
            viewModel.clearComparison()
            onDismiss()
        },
        title = { Text("比较版本") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "左侧：${left.displayTitle}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "把同一部作品的两个来源比较后，可以只保留一个虚拟合并版本；来源文件不会被修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                when {
                    state.running -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(state.progress, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = viewModel::cancelComparison) { Text("取消比较") }
                    }
                    report != null -> ComparisonReportBody(
                        report = report,
                        message = state.message,
                        confirmMerge = confirmMerge,
                        onConfirmMergeChange = { confirmMerge = it },
                        onMerge = { viewModel.createMergedEdition(left, report) },
                    )
                    else -> {
                        state.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        SourcePicker(
                            candidates = candidates,
                            selected = target,
                            onSelect = { target = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            val selected = target
            if (!state.running && report == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = selected != null,
                        onClick = { selected?.let { viewModel.startComparison(left, it, deep = false) } },
                    ) { Text("快速比较") }
                    Button(
                        enabled = selected != null,
                        onClick = { selected?.let { viewModel.startComparison(left, it, deep = true) } },
                    ) { Text("深度比较") }
                }
            } else {
                TextButton(onClick = {
                    viewModel.clearComparison()
                    onDismiss()
                }) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun ComparisonReportBody(
    report: EditionComparisonReport,
    message: String?,
    confirmMerge: Boolean,
    onConfirmMergeChange: (Boolean) -> Unit,
    onMerge: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(report.summary, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (report.deep) {
                "两个来源都已按页读取一次并计算内容哈希。"
            } else {
                "未读取内容：同名的页码只标记为疑似重复，不能据此认定重复。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        SourceCostLine("左", report.left.label, report.left.pageCount, report.left.bytesRead, report.left.durationMs)
        SourceCostLine("右", report.right.label, report.right.pageCount, report.right.bytesRead, report.right.durationMs)
        if (report.conflictingCount > 0) {
            Text(
                "同名但内容不同 ${report.conflictingCount} 页：合并时会保留左侧版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            val left = report.left.pages
            val right = report.right.pages
            itemsIndexed(report.matches, key = { _, match -> "m-${match.leftIndex}" }) { _, match ->
                val kind = when (match.kind) {
                    PageMatchKind.IDENTICAL -> "完全重复"
                    PageMatchKind.SAME_NAME_DIFFERENT_CONTENT -> "同名 · 内容不同"
                    PageMatchKind.SIZE_MATCH_UNVERIFIED -> "疑似重复（未读内容）"
                }
                ListItem(
                    headlineContent = {
                        Text(
                            "第 ${match.leftIndex + 1} 页 ↔ 第 ${match.rightIndex + 1} 页",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            "${left[match.leftIndex].name} · $kind",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            itemsIndexed(report.leftOnly, key = { _, index -> "l-$index" }) { _, index ->
                ListItem(
                    headlineContent = { Text("仅左侧：${left[index].name}", maxLines = 1) },
                    supportingContent = {
                        Text(
                            "第 ${index + 1} 页 · ${left[index].sizeBytes} 字节",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
            itemsIndexed(report.rightOnly, key = { _, index -> "r-$index" }) { _, index ->
                ListItem(
                    headlineContent = { Text("仅右侧：${right[index].name}", maxLines = 1) },
                    supportingContent = {
                        Text(
                            "第 ${index + 1} 页 · ${right[index].sizeBytes} 字节",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (confirmMerge) {
            Text(
                "生成合并版本会在左侧作品上新增一个“页计划”版本并设为默认：阅读时按合并顺序读页，" +
                    "两个来源的原有版本与文件都保持不变。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onConfirmMergeChange(false) }) { Text("取消") }
                Button(onClick = {
                    onConfirmMergeChange(false)
                    onMerge()
                }) { Text("确认生成") }
            }
        } else {
            OutlinedButton(onClick = { onConfirmMergeChange(true) }) { Text("生成虚拟合并版本") }
        }
    }
}

@Composable
private fun SourceCostLine(prefix: String, label: String, pages: Int, bytes: Long, durationMs: Long) {
    Text(
        "$prefix：$label · $pages 页 · 读取 ${bytes / 1024 / 1024} MB · ${durationMs / 1000} 秒",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SourcePicker(
    candidates: List<MediaItem>,
    selected: MediaItem?,
    onSelect: (MediaItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(candidates, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            candidates
        } else {
            candidates.filter {
                it.displayTitle.contains(trimmed, ignoreCase = true) ||
                    it.relativePath.contains(trimmed, ignoreCase = true) ||
                    it.authors.any { author -> author.contains(trimmed, ignoreCase = true) }
            }
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("搜索另一个来源") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        if (shown.isEmpty()) {
            "没有可比较的其他作品"
        } else {
            "选择要与「${selected?.displayTitle ?: "…"}」比较的来源（${shown.size} 项）"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
        itemsIndexed(shown, key = { _, item -> item.id }) { _, item ->
            Surface(
                tonalElevation = if (item.id == selected?.id) 3.dp else 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(item) },
            ) {
                ListItem(
                    headlineContent = {
                        Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            buildString {
                                append(item.relativePath)
                                val pages = item.pageCount
                                if (pages != null) append(" · $pages 页")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        }
    }
}
