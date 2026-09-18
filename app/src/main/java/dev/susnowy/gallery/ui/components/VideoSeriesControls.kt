package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.susnowy.gallery.model.MediaItem

@Composable
fun VideoSeriesControls(next: MediaItem?, ended: Boolean, onNext: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (ended) Text(if (next == null) "本系列播放结束" else "本集播放结束")
        if (next != null) {
            TextButton(onClick = onNext) { Text("下一项 · ${next.displayTitle}") }
        }
        TextButton(onClick = onBack) { Text("返回系列") }
    }
}
