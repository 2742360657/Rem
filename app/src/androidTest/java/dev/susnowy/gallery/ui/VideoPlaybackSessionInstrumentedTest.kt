package dev.susnowy.gallery.ui

import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackSessionInstrumentedTest {
    @Test fun shortMediaCompletesOnlyAfterPlaybackEnds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "completion-test.wav")
        val samples = 8000
        val dataBytes = samples * 2
        val wave = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(dataBytes)
        file.writeBytes(wave.array())
        val ready = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val session = VideoPlaybackSession()
        lateinit var player: ExoPlayer
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build()
                session.restore(false)
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) session.playing()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                        if (playbackState == Player.STATE_ENDED) {
                            session.ended()
                            ended.countDown()
                        }
                    }
                })
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                player.prepare()
            }
            assertTrue("Player did not prepare", ready.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertFalse(session.finished)
                assertTrue(player.duration in 1..4999)
                player.play()
            }
            assertTrue("Player did not finish", ended.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertTrue(session.finished) }
        } finally {
            instrumentation.runOnMainSync { player.release() }
            file.delete()
        }
    }
}
