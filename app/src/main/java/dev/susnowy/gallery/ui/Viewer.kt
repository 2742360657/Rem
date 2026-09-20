package dev.susnowy.gallery.ui

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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * Two measured rules shape this code more than the layout does:
 *
 * 1. Image requests carry no explicit size. Asking for the original used to fail to decode large
 *    files; leaving the size to the image library makes it downsample. See docs/STATUS.md.
 * 2. The video player is one instance that lives as long as the foreground. It is released when the
 *    viewer leaves the composition — which is exactly what happens when the app goes to the
 *    background — because a paused ExoPlayer still holds its audio track. Rule 5.1.
 */
@Composable
fun ViewerScreen(
    request: ViewerRequest,
    fileUri: (Entry) -> Uri?,
    onOpenWith: (Entry) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Built when the viewer enters the foreground and released when it leaves, so no player can
    // outlive the screen that owns it. `retryToken` rebuilds it after a playback failure.
    var retryToken by remember { mutableIntStateOf(0) }
    val player = remember(lifecycleOwner, retryToken) {
        ExoPlayer.Builder(context).build().apply { volume = 0f }
    }
    DisposableEffect(player) {
        onDispose {
            RemLog.info(SCOPE, "查看器离开前台，释放播放器")
            player.release()
        }
    }

    var muted by remember { mutableStateOf(true) }
    DisposableEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
        onDispose { }
    }

    var failure by remember(player) { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    var draggedTo by remember { mutableFloatStateOf(0f) }
    var seekable by remember(player) { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                duration = player.duration.takeIf { it > 0 }?.toInt() ?: 0
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = player.duration.takeIf { it > 0 }?.toInt() ?: 0
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
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val pagerState = rememberPagerState(initialPage = request.index) { request.entries.size }
    val current = request.entries.getOrNull(pagerState.currentPage)

    // Only the page on screen plays; swiping to an image pauses the shared player instead of
    // leaving a video talking over a photo.
    LaunchedEffect(player, pagerState.currentPage, retryToken) {
        val target = request.entries.getOrNull(pagerState.currentPage)
        failure = null
        seekable = true
        val uri = target?.let(fileUri)
        when {
            target == null -> Unit
            target.mediaType != MediaType.VIDEO -> player.pause()
            // A path the provider can no longer resolve — the file moved, or the grant went away —
            // is a visible failure with the system-app fallback, not an empty player.
            uri == null -> failure = "找不到这个文件，可能已被移动或删除"
            else -> {
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    LaunchedEffect(player, playing, seeking) {
        while (playing && !seeking) {
            position = player.currentPosition.toInt()
            duration = player.duration.takeIf { it > 0 }?.toInt() ?: duration
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
                MediaType.VIDEO -> VideoPage(player = player)
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

        if (current?.mediaType == MediaType.VIDEO && failure == null) {
            VideoControls(
                player = player,
                playing = playing,
                duration = duration,
                position = position,
                seekable = seekable,
                seeking = seeking,
                draggedTo = draggedTo,
                onSeekStart = { seeking = true; draggedTo = it },
                onSeekFinished = {
                    player.seekTo(draggedTo.toLong())
                    position = draggedTo.toInt()
                    seeking = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        failure?.let { message ->
            FailureNotice(
                message = message,
                onRetry = { retryToken++ },
                onOpenWith = { current?.let(onOpenWith) },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** The video surface. The player belongs to the viewer, not to this page. */
@Composable
private fun VideoPage(player: ExoPlayer) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
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
                    imageVector = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
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
