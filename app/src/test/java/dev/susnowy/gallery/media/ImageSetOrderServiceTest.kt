package dev.susnowy.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSetOrderServiceTest {
    @Test
    fun numbersPagesWithStablePaddingAndPreservesExtensions() {
        assertEquals(
            listOf("0001.jpg", "0002.png", "0003.webp"),
            ImageSetOrderService.numberedPageNames(listOf("10.jpg", "cover.png", "002.webp")),
        )
    }

    @Test
    fun keepsExtensionlessPagesExtensionless() {
        assertEquals(
            listOf("0001", "0002.jpeg"),
            ImageSetOrderService.numberedPageNames(listOf("scan", "page.jpeg")),
        )
    }
}
