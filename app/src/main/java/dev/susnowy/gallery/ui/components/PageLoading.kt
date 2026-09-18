package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.susnowy.gallery.media.ImagePage
import kotlinx.coroutines.CancellationException

class PageLoadState {
    var result by mutableStateOf<Result<List<ImagePage>>?>(null)
        internal set
    internal var attempt by mutableIntStateOf(0)
    fun retry() { result = null; attempt++ }
}

@Composable
fun rememberPageLoad(key: Any, load: suspend () -> List<ImagePage>): PageLoadState {
    val state = remember(key) { PageLoadState() }
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(state, state.attempt) {
        state.result = try { Result.success(currentLoad()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    return state
}

@Composable
fun PageLoadingStatus(state: PageLoadState, archive: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.result == null) {
            CircularProgressIndicator(color = Color.White)
            Text(if (archive) "正在准备压缩包与页列表，首次打开可能需要缓存文件" else "正在读取图片页列表", color = Color.White)
        } else {
            Text(state.result?.exceptionOrNull()?.let { "读取失败：${it.message ?: "未知错误"}" }
                ?: "没有可读取的图片页", color = Color.White)
            TextButton(onClick = state::retry) { Text("重试") }
        }
        TextButton(onClick = onBack) { Text(if (state.result == null) "取消打开" else "返回") }
    }
}
