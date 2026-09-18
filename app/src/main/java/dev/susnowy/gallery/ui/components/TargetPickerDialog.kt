package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One selectable destination for a batch action. */
data class TargetOption(
    val id: String,
    val title: String,
    val subtitle: String = "",
)

/**
 * Single-select picker for an existing Group or Series.
 *
 * Used by the batch actions on a media card: the caller owns what "add" means and commits it
 * as one portable write, so this dialog only reports the chosen target.
 */
@Composable
fun TargetPickerDialog(
    title: String,
    options: List<TargetOption>,
    onDismiss: () -> Unit,
    onPick: (TargetOption) -> Unit,
    emptyText: String = "还没有可选择的项目",
) {
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val shown = remember(options, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            options
        } else {
            options.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.subtitle.contains(trimmed, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (options.size > 6) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    if (shown.isEmpty()) emptyText else "共 ${shown.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                ListPositionButton(listState, shown.size)
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(shown, key = { _, option -> option.id }) { _, option ->
                        ListItem(
                            headlineContent = {
                                Text(option.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                if (option.subtitle.isNotBlank()) {
                                    Text(
                                        option.subtitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onPick(option) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
