package dev.susnowy.gallery.ui

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.susnowy.gallery.MainActivity
import dev.susnowy.gallery.media.createVideoPlayer
import dev.susnowy.gallery.ui.components.VideoPlayerSurface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoDecodeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun syntheticAvcRendersAndEndsThroughApplicationPlayerSurface() {
        val file = File(rule.activity.cacheDir, "synthetic-video.mp4")
        lateinit var player: ExoPlayer
        var created = false
        val frame = CountDownLatch(1)
        val ended = CountDownLatch(1)
        try {
            writeSyntheticVideo(file)
            rule.activityRule.scenario.onActivity { activity ->
                player = createVideoPlayer(activity)
                created = true
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { frame.countDown() }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) ended.countDown()
                    }
                })
                activity.setContent { MaterialTheme { VideoPlayerSurface(player, Modifier.fillMaxSize()) } }
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
            }
            rule.waitForIdle()
            assertTrue("No video frame rendered", frame.await(15, TimeUnit.SECONDS))
            rule.runOnIdle { player.seekTo(1_000) }
            rule.onNodeWithText("横屏全屏").performClick()
            rule.waitUntil(10_000) {
                rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            rule.runOnIdle {
                assertEquals(1_000L, player.currentPosition)
                assertNull(player.playerError)
                player.play()
            }
            assertTrue("Video did not reach end", ended.await(15, TimeUnit.SECONDS))
            rule.onNodeWithText("退出全屏").performClick()
            rule.runOnIdle {
                assertNull(player.playerError)
                assertEquals(160, player.videoSize.width)
                assertEquals(120, player.videoSize.height)
                assertTrue(player.currentPosition >= 2_000)
            }
        } finally {
            rule.activityRule.scenario.onActivity { it.setContent {} }
            rule.waitForIdle()
            if (created) rule.runOnIdle { player.release() }
            file.delete()
        }
    }
}
