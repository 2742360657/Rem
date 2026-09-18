package dev.susnowy.gallery.ui

import android.provider.DocumentsContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.storage.TestDocumentsProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryAttachmentInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun deniedPersistablePermissionShowsDurableErrorWithoutRegisteringLibrary() {
        val app = ApplicationProvider.getApplicationContext<GalleryApplication>()
        val before = app.repository.libraries.value
        lateinit var vm: GalleryViewModel
        rule.runOnUiThread { vm = GalleryViewModel(app, SavedStateHandle()) }
        rule.setContent {
            val state by vm.attachment.collectAsState()
            MaterialTheme { LibraryAttachmentStatus(state, vm::dismissAttachmentError, {}) }
        }
        // A URI for which the system picker did not grant persistence must not be swallowed.
        val uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "ungranted-root")
        rule.runOnIdle { vm.attachTree(uri) }
        rule.waitUntil(5_000) { vm.attachment.value.error != null }
        rule.onNodeWithText("接入媒体库失败").assertIsDisplayed()
        rule.onNodeWithText("无法保留目录读写授权，请重新选择可读写的目录并允许访问").assertIsDisplayed()
        rule.runOnIdle { assertEquals(before, app.repository.libraries.value) }
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithText("接入媒体库失败").assertDoesNotExist()
    }

    @Test fun progressAndRetryAreVisibleOnTheirOwnWithoutLibraryContent() {
        val state = mutableStateOf(LibraryAttachmentState(progress = "正在读取或建立媒体库…"))
        var retries = 0
        rule.setContent {
            MaterialTheme {
                LibraryAttachmentStatus(state.value, { state.value = LibraryAttachmentState() }, { retries++ })
            }
        }
        rule.onNodeWithText("正在接入媒体库").assertIsDisplayed()
        rule.onNodeWithText("正在读取或建立媒体库…").assertIsDisplayed()
        rule.runOnIdle { state.value = LibraryAttachmentState(error = "所选存储已断开") }
        rule.onNodeWithText("所选存储已断开").assertIsDisplayed()
        rule.onNodeWithText("重新选择目录").performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }
}
