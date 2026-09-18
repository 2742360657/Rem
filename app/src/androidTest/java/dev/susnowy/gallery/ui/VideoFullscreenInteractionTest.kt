package dev.susnowy.gallery.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dev.susnowy.gallery.MainActivity
import dev.susnowy.gallery.ui.components.VideoPlayerSurface
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoFullscreenInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun activityStopPausesAndResumePreservesManualPause() {
        lateinit var player: ExoPlayer
        var savedPosition = -1L
        rule.activityRule.scenario.onActivity { activity ->
            player = ExoPlayer.Builder(activity).build()
            player.setMediaItem(MediaItem.fromUri("content://fixture/video"))
            player.seekTo(8_765)
            activity.setContent {
                dev.susnowy.gallery.ui.components.VideoPlaybackLifecycle(player, true) {
                    savedPosition = player.currentPosition
                }
            }
        }
        rule.waitForIdle()
        try {
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertFalse(player.playWhenReady)
                assertEquals(8_765L, savedPosition)
            }
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            rule.runOnIdle {
                assertTrue(player.playWhenReady)
                player.pause()
            }
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            rule.runOnIdle { assertFalse(player.playWhenReady) }
        } finally {
            rule.activityRule.scenario.onActivity { activity ->
                activity.setContent {}
            }
            rule.waitForIdle()
            rule.runOnIdle { player.release() }
        }
    }

    @Test fun fullscreenKeepsPlayerPositionAndRestoresOrientation() {
        val original = rule.activity
        var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        lateinit var player: ExoPlayer
        rule.activityRule.scenario.onActivity { activity ->
            orientation = activity.requestedOrientation
            player = ExoPlayer.Builder(activity).build()
            player.setMediaItem(MediaItem.fromUri("content://fixture/video"))
            player.seekTo(12_345)
            activity.setContent { MaterialTheme { VideoPlayerSurface(player, Modifier.fillMaxSize()) } }
        }
        try {
            rule.onNodeWithText("横屏全屏").performClick()
            rule.onNodeWithText("退出全屏").assertIsDisplayed()
            rule.waitUntil(10_000) {
                rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            rule.runOnIdle {
                assertSame(original, rule.activity)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, rule.activity.requestedOrientation)
                assertEquals(12_345L, player.currentPosition)
            }
            rule.onNodeWithText("退出全屏").performClick()
            rule.onNodeWithText("横屏全屏").assertIsDisplayed()
            rule.runOnIdle {
                assertEquals(orientation, rule.activity.requestedOrientation)
                assertEquals(12_345L, player.currentPosition)
            }
        } finally {
            rule.activityRule.scenario.onActivity { activity ->
                activity.setContent {}
                player.release()
                activity.requestedOrientation = orientation
            }
        }
    }
}
