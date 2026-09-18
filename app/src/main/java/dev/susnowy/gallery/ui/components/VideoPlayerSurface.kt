package dev.susnowy.gallery.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/** The same player is attached to exactly one view across fullscreen changes. */
@Composable
fun VideoPlayerSurface(player: Player, modifier: Modifier = Modifier) {
    var fullscreen by remember(player) { mutableStateOf(false) }
    val activity = LocalContext.current.videoActivity()
    if (fullscreen) {
        DisposableEffect(activity) {
            val previous = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose { previous?.let { activity.requestedOrientation = it } }
        }
        Box(modifier.background(Color.Black))
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        )) {
            val view = LocalView.current
            DisposableEffect(view) {
                val window = (view.parent as? DialogWindowProvider)?.window
                val controller = window?.let { WindowCompat.getInsetsController(it, view) }
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
                onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
            }
            PlayerSurface(player, Modifier.fillMaxSize(), true) { fullscreen = false }
        }
    } else {
        PlayerSurface(player, modifier, false) { fullscreen = true }
    }
}

@Composable
private fun PlayerSurface(player: Player, modifier: Modifier, fullscreen: Boolean, onToggle: () -> Unit) {
    Box(modifier.fillMaxWidth().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; keepScreenOn = true } },
            update = { it.player = player },
            onRelease = { it.player = null; it.keepScreenOn = false },
            modifier = Modifier.fillMaxSize(),
        )
        TextButton(onClick = onToggle, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
            Text(if (fullscreen) "退出全屏" else "横屏全屏", color = Color.White)
        }
    }
}

private tailrec fun Context.videoActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.videoActivity()
    else -> null
}
