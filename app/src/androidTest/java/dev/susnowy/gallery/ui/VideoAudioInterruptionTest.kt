package dev.susnowy.gallery.ui

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import dev.susnowy.gallery.media.createVideoPlayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VideoAudioInterruptionTest {
    @Test fun permanentAudioFocusLossPausesActualPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "audio-interruption.wav")
        val bytes = 8000 * 2 * 30
        val wave = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes)
        file.writeBytes(wave.array())
        val playing = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val interruption = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener { }
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .build()
        lateinit var player: ExoPlayer
        var created = false
        try {
            instrumentation.runOnMainSync {
                player = createVideoPlayer(context)
                created = true
                assertEquals(C.USAGE_MEDIA, player.audioAttributes.usage)
                assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, player.audioAttributes.contentType)
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) playing.countDown()
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                            paused.countDown()
                        }
                    }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.play()
            }
            assertTrue("Playback did not start", playing.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(interruption))
            }
            assertTrue("Focus loss did not pause playback", paused.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertFalse(player.playWhenReady)
                assertFalse(player.isPlaying)
            }
        } finally {
            instrumentation.runOnMainSync { audio.abandonAudioFocusRequest(interruption); if (created) player.release() }
            file.delete()
        }
    }
}
