package dev.susnowy.gallery.ui

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

/** Tiny, self-generated AVC fixture; no downloaded media or private Library content. */
internal fun writeSyntheticVideo(file: File) {
    val width = 160
    val height = 120
    val frames = 30
    val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, 100_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, 10)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val codec = MediaCodec.createEncoderByType("video/avc")
    val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    var track = -1
    var frame = 0
    var inputEnded = false
    var outputEnded = false
    var samples = 0
    val info = MediaCodec.BufferInfo()
    val deadline = SystemClock.elapsedRealtime() + 20_000
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        while (!outputEnded) {
            check(SystemClock.elapsedRealtime() < deadline) { "Synthetic video encoding timed out" }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (frame == frames) {
                        codec.queueInputBuffer(index, 0, 0, frame * 100_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        val bytes = ByteArray(width * height * 3 / 2) { if (it < width * height) (32 + frame * 5).toByte() else 128.toByte() }
                        codec.getInputBuffer(index)!!.apply { clear(); put(bytes) }
                        codec.queueInputBuffer(index, 0, bytes.size, frame * 100_000L, 0)
                        frame++
                    }
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                else -> if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        val output = codec.getOutputBuffer(index)!!
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(track, output, info)
                        samples++
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        check(samples == frames) { "Encoded $samples of $frames frames" }
    } finally {
        codec.release()
        try { if (started) muxer.stop() } finally { muxer.release() }
    }
}
