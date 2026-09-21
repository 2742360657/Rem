package dev.susnowy.gallery.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.request.ImageRequest
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.storage.LibraryTree

/**
 * Builds the Coil model for one thumbnail.
 *
 * The data is the document URI exactly as `buildDocumentUriUsingTree` produces it. That matters:
 * a hand-joined `treeUri + "/" + path` string is not a URI the provider understands, and Coil
 * would fail to open it.
 *
 * No extra read grant is attached. Coil reads a `content://` URI through
 * `ContentResolver.openInputStream`, which is authorized by the persisted grant taken in
 * [LibraryTree.persistPermission] when the Library was attached.
 *
 * Nothing here may touch the provider: this runs while a frame is being composed, and a provider
 * round-trip on a USB volume put the main thread inside `childrenOf` for five seconds on a real
 * device — reported by the system as a hang, seen by the user as a white screen. [LibraryTree.uriFor]
 * is safe for exactly that reason.
 *
 * The returned lambda is remembered so the same request is handed back on every recomposition and
 * visible cells do not restart their loads while the list scrolls.
 */
@Composable
fun rememberThumbnails(context: Context, tree: LibraryTree?): (Entry) -> ImageRequest? {
    return remember(context, tree) {
        if (tree == null) {
            val none: (Entry) -> ImageRequest? = { null }
            none
        } else {
            val lookup: (Entry) -> ImageRequest? = { entry ->
                ImageRequest.Builder(context).data(tree.uriFor(entry.path)).build()
            }
            lookup
        }
    }
}
