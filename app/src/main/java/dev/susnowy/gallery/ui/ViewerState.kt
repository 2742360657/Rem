package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.Entry

/**
 * One media opened in the built-in viewer, together with the list it was opened from.
 *
 * The list is the one the grid was showing — already filtered and already ordered — and it is
 * copied here rather than recomputed. The viewer therefore cannot disagree with the grid about
 * order, and a background rescan that lands while the viewer is open cannot reorder the pages
 * under the user's finger.
 */
data class ViewerRequest(val entries: List<Entry>, val index: Int)

object ViewerState {

    /** A list with nothing to show never opens a viewer, however the tap index arrived. */
    fun request(entries: List<Entry>, index: Int): ViewerRequest? =
        if (entries.isEmpty()) null else ViewerRequest(entries, clampIndex(entries.size, index))

    /** Keeps a tap index inside the list: a stale index shows the nearest page instead of crashing. */
    fun clampIndex(size: Int, index: Int): Int = when {
        size <= 0 -> 0
        index < 0 -> 0
        index > size - 1 -> size - 1
        else -> index
    }

    /** The previous or next page, or null at either end of the list. */
    fun neighbour(size: Int, index: Int, step: Int): Int? =
        (index + step).takeIf { it in 0 until size }

    /** `3 / 27` — where the viewer is, and how much list is left on either side. */
    fun positionLabel(size: Int, index: Int): String = "${clampIndex(size, index) + 1} / $size"

    /**
     * What the player must do for the current lifecycle state and page.
     *
     * Losing the foreground releases the player outright instead of pausing it: a paused ExoPlayer
     * still holds its audio track, and the rules demand silence in the background, not a paused
     * UI. `hasPlayer` is passed in because the decision differs between the first resume and a
     * resume after the player was already built.
     *
     * Returning to the foreground builds a fresh player and does not play, which is what «返回该
     * 视频时停留在原位置且保持暂停» asks for once the released player has dropped its position.
     */
    enum class PlayerAction { CREATE, RELEASE, PAUSE, NONE }

    fun playerAction(appInForeground: Boolean, onVideoPage: Boolean, hasPlayer: Boolean): PlayerAction = when {
        !appInForeground -> if (hasPlayer) PlayerAction.RELEASE else PlayerAction.NONE
        !hasPlayer -> PlayerAction.CREATE
        !onVideoPage -> PlayerAction.PAUSE
        else -> PlayerAction.NONE
    }

    /**
     * Turns a `PlaybackException` error code into something a person can act on.
     *
     * Every branch names a real cause; the rules forbid a silent black screen, and a bare «播放失败»
     * would not tell the user that another app might still open the file.
     */
    fun playerMessage(errorCode: Int): String = when (errorCode) {
        3003, 3004 -> "无法解析这个视频容器，Rem 不能播放"
        4001, 4002, 4003, 4004, 4005, 4006 -> "这个视频的编码本机无法解码"
        2005, 2006 -> "读不到这个文件，可能权限已被收回"
        2008 -> "文件内容不完整，无法播放"
        else -> "播放失败（错误码 $errorCode）"
    }

    /**
     * What a failed seek means.
     *
     * A container can play end to end and still refuse to seek — FLV is the measured example — so
     * the failure is not an error to report: it turns the progress bar off for that video and
     * leaves playback running.
     */
    enum class SeekOutcome { DisableSeeking }

    fun afterSeekFailure(): SeekOutcome = SeekOutcome.DisableSeeking
}
