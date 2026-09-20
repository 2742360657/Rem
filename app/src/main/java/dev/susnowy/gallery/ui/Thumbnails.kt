package dev.susnowy.gallery.ui

import android.content.Context
import android.net.Uri
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
 * The returned lambda is remembered so the same request is handed back on every recomposition and
 * visible cells do not restart their loads while the list scrolls.
 */
@Composable
fun rememberThumbnails(context: Context, treeUri: Uri?): (Entry) -> ImageRequest? {
    return remember(context, treeUri) {
        if (treeUri == null) {
            val none: (Entry) -> ImageRequest? = { null }
            none
        } else {
            // One tree for the whole list: rebuilding it per cell repeats a round-trip per image.
            val tree = LibraryTree(context, treeUri)
            val lookup: (Entry) -> ImageRequest? = { entry ->
                ImageRequest.Builder(context).data(tree.documentUri(entry.path)).build()
            }
            lookup
        }
    }
}
