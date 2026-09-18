package dev.susnowy.gallery.ui.components

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player

/** Resume only playback that this binding paused, preserving a user's own pause. */
@Composable
fun VideoPlaybackLifecycle(player: Player, active: Boolean, onBackground: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val save by rememberUpdatedState(onBackground)
    DisposableEffect(player, lifecycle, active) {
        var resume = active
        var foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        player.playWhenReady = active && foreground
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    if (active && resume) player.play()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (foreground) resume = active && player.playWhenReady
                    foreground = false
                    player.pause()
                    save()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.pause()
        }
    }
}
