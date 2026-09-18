package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun PositionJumpDialog(count: Int, current: Int, unit: String, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    var value by remember(count) { mutableStateOf((current + 1).toString()) }
    val position = value.toIntOrNull()?.takeIf { it in 1..count }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到第几$unit") },
        text = { Column {
            OutlinedTextField(value, { value = it }, label = { Text("位置（1–$count）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = { value = "1" }) { Text("首$unit") }
            TextButton(onClick = { value = count.toString() }) { Text("末$unit") }
        } },
        confirmButton = { TextButton(enabled = position != null, onClick = { position?.let { onJump(it - 1) } }) { Text("跳转") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
