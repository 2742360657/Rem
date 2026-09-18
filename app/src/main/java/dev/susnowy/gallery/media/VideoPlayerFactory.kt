package dev.susnowy.gallery.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/** Watching local media participates in Android audio focus and pauses when headphones disconnect. */
fun createVideoPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true,
    )
    .setHandleAudioBecomingNoisy(true)
    .build()
