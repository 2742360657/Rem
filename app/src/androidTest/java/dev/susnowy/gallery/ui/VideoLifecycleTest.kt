package dev.susnowy.gallery.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.ui.components.VideoPlaybackLifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoLifecycleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun backgroundPausesAndSavesWhileManualPauseAndInactivePageStayPaused() {
        lateinit var player: ExoPlayer
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val active = mutableStateOf(true)
        var saved = 0
        var position = -1L
        rule.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.RESUMED
            player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        }
        try {
            rule.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                VideoPlaybackLifecycle(player, active.value) { saved++; position = player.currentPosition }
            } }
            rule.runOnIdle {
                assertTrue(player.playWhenReady)
                player.seekTo(12_000)
                owner.registry.currentState = Lifecycle.State.CREATED
                assertFalse(player.playWhenReady)
                assertEquals(1, saved)
                assertEquals(12_000L, position)
                owner.registry.currentState = Lifecycle.State.RESUMED
                assertTrue(player.playWhenReady)
                player.pause()
                owner.registry.currentState = Lifecycle.State.CREATED
                owner.registry.currentState = Lifecycle.State.RESUMED
                assertFalse(player.playWhenReady)
                active.value = false
            }
            rule.runOnIdle {
                owner.registry.currentState = Lifecycle.State.CREATED
                owner.registry.currentState = Lifecycle.State.RESUMED
                assertFalse(player.playWhenReady)
                owner.registry.currentState = Lifecycle.State.CREATED
                active.value = true
            }
            rule.runOnIdle {
                assertFalse(player.playWhenReady)
                owner.registry.currentState = Lifecycle.State.RESUMED
                assertTrue(player.playWhenReady)
                active.value = false
            }
            rule.waitForIdle()
        } finally { rule.runOnUiThread { player.release() } }
    }
}
