package dev.susnowy.gallery.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.request.ImageRequest
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.MediaType
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/**
 * The built-in viewer: one media at a time, swipeable across the list it was opened from.
 *
 * The list arrives whole, so paging left and right is paging through what the grid already showed.
 * The viewer never scans, never re-sorts and never writes to the Library.
 *
 * Three measured rules shape this code:
 *
 * 1. Image requests carry no explicit size. Asking for the original failed to decode large files;
 *    leaving the size to the image library makes it downsample. See docs/STATUS.md.
 * 2. A video is prepared only once the player actually holds a surface. Measured on device:
 *    preparing before the `SurfaceView` exists leaves the page black for good, while a page whose
 *    surface already existed — reached by swiping in — plays normally.
 * 3. The player lives as long as the foreground and is released when the viewer leaves the
 *    composition, which is what happens when the app goes to the background. A paused ExoPlayer
 *    still holds its audio track, so pausing alone is not silence. Rule 5.1.
 */
@Composable
fun ViewerScreen(
    request: ViewerRequest,
    fileUri: (Entry) -> Uri?,
    onOpenWith: (Entry) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    // One player for the whole viewer, created with the viewer and released when it leaves. It is
    // never stopped or cleared between pages: measured on device, tearing the codec down between
    // two videos raised `flush() is valid only at Executing states; currently at Released state`
    // from c2.qti.avc.decoder, and that page stayed black afterwards.
    var retryToken by remember { mutableIntStateOf(0) }
    var player by remember(retryToken) { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(retryToken) {
        val created = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            volume = 0f
        }
        player = created
        RemLog.info(SCOPE, "创建播放器")
        onDispose {
            RemLog.info(SCOPE, "查看器离开前台，释放播放器")
            created.release()
            player = null
        }
    }

    var muted by remember { mutableStateOf(true) }
    var failure by remember(retryToken) { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    var draggedTo by remember { mutableFloatStateOf(0f) }
    var seekable by remember(retryToken) { mutableStateOf(true) }
    var boundPage by remember(retryToken) { mutableIntStateOf(-1) }
    val viewAttachedState = remember { mutableStateOf(false) }
    /** Bumped once the surface has had time to settle, so binding runs exactly then. */
    var attachGeneration by remember { mutableIntStateOf(0) }
    var renderedFrame by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = request.index) { request.entries.size }
    val current = request.entries.getOrNull(pagerState.currentPage)
    val currentIsVideo = current?.mediaType == MediaType.VIDEO

    DisposableEffect(player, muted) {
        player?.volume = if (muted) 0f else 1f
        onDispose { }
    }

    DisposableEffect(player) {
        val active = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?"
                }
                duration = active.duration.takeIf { it > 0 }?.toInt() ?: 0
                RemLog.info(SCOPE, "state=$name duration=$duration frame=$renderedFrame")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                RemLog.info(SCOPE, "isPlaying=$isPlaying volume=${active.volume}")
            }

            override fun onRenderedFirstFrame() {
                renderedFrame = true
                RemLog.info(SCOPE, "首帧已渲染")
            }

            override fun onPlayerError(error: PlaybackException) {
                // A refused seek is not a playback failure: measured FLV plays but reports a read
                // position error when dragged. Keep playing, drop the bar, tell the logs.
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
                    RemLog.warn(SCOPE, "该视频无法拖动，隐藏进度条：${error.errorCodeName}")
                    seekable = false
                    return
                }
                val message = ViewerState.playerMessage(error.errorCode)
                RemLog.error(SCOPE, "视频播放失败 ${error.errorCodeName}：$message", error)
                failure = message
            }
        }
        active.addListener(listener)
        onDispose { active.removeListener(listener) }
    }

    // Rule 2, first half: nothing is prepared into the player until the video view is attached to
    // the window, and then only after a short settling delay. Measured on device: preparing into a
    // `SurfaceView` that has just been attached leaves the page black for good, while the same file
    // reached by swiping — where the surface already existed — plays normally.
    //
    // Nothing in this file clears the attach flag while a video page is on screen. An earlier
    // version reset it from an effect, and because effects restart at unpredictable moments around
    // the player's creation, that reset kept erasing the flag the view had just set: the same state
    // object read true inside the attach callback and false again 30 ms later, so a directly-tapped
    // video never bound at all.
    LaunchedEffect(currentIsVideo) {
        if (!currentIsVideo) {
            viewAttachedState.value = false
            return@LaunchedEffect
        }
        while (!viewAttachedState.value) {
            delay(16)
        }
        // The view is in the window; give the surface underneath it a moment to become valid.
        delay(120)
        attachGeneration++
    }

    // Rule 2, second half: if playback is running but no frame has ever been drawn, the output
    // surface never arrived. Re-preparing is the cheap retry; it is bounded so a file that simply
    // cannot render does not spin forever.
    LaunchedEffect(player, boundPage, renderedFrame) {
        val active = player ?: return@LaunchedEffect
        if (renderedFrame || boundPage < 0) return@LaunchedEffect
        delay(1500)
        if (renderedFrame) return@LaunchedEffect
        RemLog.warn(SCOPE, "第 $boundPage 页已开始播放但没有渲染出任何画面，重新准备一次")
        active.prepare()
    }

    // Bind only when the page has settled and its surface host is attached.
    LaunchedEffect(
        player,
        attachGeneration,
        pagerState.settledPage,
        pagerState.isScrollInProgress,
        retryToken,
    ) {
        val active = player ?: return@LaunchedEffect
        if (pagerState.isScrollInProgress || viewAttachedState.value.not()) return@LaunchedEffect
        val page = pagerState.settledPage
        if (boundPage == page) return@LaunchedEffect
        val target = request.entries.getOrNull(page)
        failure = null
        // Reset per video: measured on device, FLV turns seeking off after a refused seek, and
        // without this the next video inherited a disabled bar it had never earned.
        seekable = true
        val uri = target?.let(fileUri)
        when {
            target == null -> Unit
            // An image page only pauses the player: a paused player is silent, which is the rule,
            // and it keeps the codec alive for the next video.
            target.mediaType != MediaType.VIDEO -> {
                active.pause()
                boundPage = -1
                RemLog.info(SCOPE, "第 $page 页是图片，暂停播放器")
            }
            uri == null -> {
                boundPage = -1
                failure = "找不到这个文件，可能已被移动或删除"
            }
            else -> {
                active.setMediaItem(MediaItem.fromUri(uri))
                active.prepare()
                active.playWhenReady = true
                boundPage = page
                RemLog.info(SCOPE, "绑定第 $page 页视频 ${target.fileName}")
            }
        }
    }

    LaunchedEffect(player, playing, seeking) {
        val active = player ?: return@LaunchedEffect
        while (playing && !seeking) {
            position = active.currentPosition.toInt()
            duration = active.duration.takeIf { it > 0 }?.toInt() ?: duration
            delay(500)
        }
    }

    // The viewer is a place to look at one thing, so the system bars step aside while it is open.
    val window = (context as? ComponentActivity)?.window
    DisposableEffect(window) {
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            // One page on either side: smooth to swipe, cheap enough for very large files.
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val entry = request.entries.getOrNull(page)
            when (entry?.mediaType) {
                MediaType.IMAGE -> entry?.let(fileUri)?.let { uri ->
                    ZoomableAsyncImage(
                        // No explicit size on purpose: the image library downsamples, and
                        // requesting the original is what used to fail on very large images.
                        model = ImageRequest.Builder(context).data(uri).build(),
                        contentDescription = entry.fileName,
                        // Pinch to zoom, drag to pan, double-tap to zoom: the preset the rules ask
                        // for. While zoomed the image consumes the drag, so paging resumes at 1x.
                        gestures = EnabledZoomGestures.ZoomAndPan,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Only the settled page owns the surface. Two video pages sharing one player would
                // keep re-attaching the video output as the pager moves.
                MediaType.VIDEO -> if (page == pagerState.settledPage) {
                    VideoPage(
                        player = player,
                        onAttached = {
                            viewAttachedState.value = true
                        },
                        onDetached = {
                            viewAttachedState.value = false
                            // Leaving the page also forgets the binding, so coming back re-prepares
                            // instead of showing a surface that no longer has a producer.
                            boundPage = -1
                        },
                    )
                } else {
                    Box(Modifier.fillMaxSize())
                }
                null -> Unit
            }
        }

        ViewerTopBar(
            entry = current,
            index = pagerState.currentPage,
            count = request.entries.size,
            muted = muted,
            onToggleMute = { muted = !muted },
            onOpenWith = { current?.let(onOpenWith) },
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (currentIsVideo && failure == null) {
            player?.let { active ->
                VideoControls(
                    player = active,
                    playing = playing,
                    duration = duration,
                    position = position,
                    seekable = seekable,
                    seeking = seeking,
                    draggedTo = draggedTo,
                    onSeekStart = { seeking = true; draggedTo = it },
                    onSeekFinished = {
                        active.seekTo(draggedTo.toLong())
                        position = draggedTo.toInt()
                        seeking = false
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }

        failure?.let { message ->
            FailureNotice(
                message = message,
                // Recovery is a new player, not a new media item: a decoder that died cannot be
                // reused, and retrying on the same instance reproduced the black page.
                onRetry = {
                    RemLog.info(SCOPE, "用户重试：重建播放器")
                    retryToken++
                },
                onOpenWith = { current?.let(onOpenWith) },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * The video surface, plus the attachment signal the viewer waits for.
 *
 * `PlayerView` on its own gives no way to ask whether its output surface exists — `Player` exposes
 * `setVideoSurface` but no getter — so the signal comes from the view hierarchy instead: the page
 * reports when its view is attached to the window, and the viewer only feeds the player after that.
 */
@Composable
private fun VideoPage(
    player: ExoPlayer?,
    onAttached: () -> Unit,
    onDetached: () -> Unit,
) {
    if (player == null) {
        // Observable rather than a black rectangle: this only happens if the surface outlives the
        // player, which is a bug worth seeing instead of debugging blind.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("播放器已释放", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    AndroidView(
        factory = { context ->
            SurfaceHost(context) { attached ->
                if (attached) onAttached() else onDetached()
            }.apply {
                addView(
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { host ->
            val view = host.getChildAt(0) as PlayerView
            if (view.player !== player) view.player = player
        },
        onRelease = { host -> (host.getChildAt(0) as PlayerView).player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Reports when the video view is really in the window, which is when a surface can exist. */
private class SurfaceHost(
    context: Context,
    private val onAttachedChanged: (Boolean) -> Unit,
) : FrameLayout(context) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onAttachedChanged(true)
    }

    override fun onDetachedFromWindow() {
        onAttachedChanged(false)
        super.onDetachedFromWindow()
    }
}

/** Play/pause, progress and time. The bar disappears for containers that refuse to seek. */
@Composable
private fun VideoControls(
    player: ExoPlayer,
    playing: Boolean,
    duration: Int,
    position: Int,
    seekable: Boolean,
    seeking: Boolean,
    draggedTo: Float,
    onSeekStart: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { if (playing) player.pause() else player.play() }) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = Color.White,
            )
        }
        if (seekable && duration > 0) {
            Slider(
                value = if (seeking) draggedTo else position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = onSeekStart,
                onValueChangeFinished = onSeekFinished,
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(Modifier.weight(1f))
        }
        Text(
            text = "${clock(if (seeking) draggedTo.toInt() else position)} / ${clock(duration)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        if (!seekable) {
            Text(
                text = "该格式不支持拖动",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Where this is in the list, and the way out — including the fallback the rules always keep. */
@Composable
private fun ViewerTopBar(
    entry: Entry?,
    index: Int,
    count: Int,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onOpenWith: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "关闭查看器", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry?.fileName.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(entry?.positionLabel, ViewerState.positionLabel(count, index))
                    .joinToString(" · "),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (entry?.mediaType == MediaType.VIDEO) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Rounded.VolumeOff
                    else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = if (muted) "打开声音" else "静音",
                    tint = Color.White,
                )
            }
        }
        IconButton(onClick = onOpenWith) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "用其他应用打开", tint = Color.White)
        }
    }
}

/** A failure the user can see and act on — never a black screen, never a crash. */
@Composable
private fun FailureNotice(
    message: String,
    onRetry: () -> Unit,
    onOpenWith: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "可以重试，或用其他应用打开这个文件。",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Color.White)
                Text("重试", color = Color.White, modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onOpenWith) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = Color.White)
                Text("用其他应用打开", color = Color.White, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** `m:ss`, or `h:mm:ss` once a video is longer than an hour. */
private fun clock(millis: Int): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private const val SCOPE = "查看器"
