package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.ui.ViewerState.PlayerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerStateTest {

    private fun entry(name: String) = Entry(path = "相册/$name.jpg", size = 1, modified = 0)

    private val list = listOf(entry("a"), entry("b"), entry("c"))

    @Test
    fun `an empty list never opens the viewer`() {
        assertNull(ViewerState.request(emptyList(), 0))
        assertNull(ViewerState.request(emptyList(), 7))
    }

    @Test
    fun `a stale tap index shows the nearest page instead of failing`() {
        assertEquals(2, ViewerState.request(list, 99)?.index)
        assertEquals(0, ViewerState.request(list, -4)?.index)
        assertEquals(1, ViewerState.request(list, 1)?.index)
    }

    @Test
    fun `the viewer opens from the tapped position`() {
        val request = ViewerState.request(list, 2)
        assertEquals("c.jpg", request?.entries?.get(request.index)?.fileName)
    }

    @Test
    fun `neighbours stop at both ends`() {
        assertEquals(1, ViewerState.neighbour(3, 0, 1))
        assertEquals(0, ViewerState.neighbour(3, 1, -1))
        assertNull(ViewerState.neighbour(3, 0, -1))
        assertNull(ViewerState.neighbour(3, 2, 1))
    }

    @Test
    fun `the position label is one-based and clamped`() {
        assertEquals("1 / 3", ViewerState.positionLabel(3, 0))
        assertEquals("3 / 3", ViewerState.positionLabel(3, 2))
        assertEquals("3 / 3", ViewerState.positionLabel(3, 42))
    }

    @Test
    fun `losing the foreground releases a player instead of pausing it`() {
        assertEquals(
            PlayerAction.RELEASE,
            ViewerState.playerAction(appInForeground = false, onVideoPage = true, hasPlayer = true),
        )
        assertEquals(
            PlayerAction.RELEASE,
            ViewerState.playerAction(appInForeground = false, onVideoPage = false, hasPlayer = true),
        )
        assertEquals(
            PlayerAction.NONE,
            ViewerState.playerAction(appInForeground = false, onVideoPage = true, hasPlayer = false),
        )
    }

    @Test
    fun `returning to the foreground rebuilds the player and does not play`() {
        assertEquals(
            PlayerAction.CREATE,
            ViewerState.playerAction(appInForeground = true, onVideoPage = true, hasPlayer = false),
        )
    }

    @Test
    fun `swiping from a video to an image pauses the shared player`() {
        assertEquals(
            PlayerAction.PAUSE,
            ViewerState.playerAction(appInForeground = true, onVideoPage = false, hasPlayer = true),
        )
        assertEquals(
            PlayerAction.NONE,
            ViewerState.playerAction(appInForeground = true, onVideoPage = true, hasPlayer = true),
        )
    }

    @Test
    fun `playback failures name a cause instead of going black`() {
        // WMV measured on device: the container itself is refused.
        assertEquals("无法解析这个视频容器，Rem 不能播放", ViewerState.playerMessage(3003))
        assertEquals("这个视频的编码本机无法解码", ViewerState.playerMessage(4003))
        assertEquals("读不到这个文件，可能权限已被收回", ViewerState.playerMessage(2006))
        assertEquals("播放失败（错误码 1000）", ViewerState.playerMessage(1000))
    }

    @Test
    fun `a refused seek disables the bar rather than reporting an error`() {
        assertEquals(ViewerState.SeekOutcome.DisableSeeking, ViewerState.afterSeekFailure())
    }
}
