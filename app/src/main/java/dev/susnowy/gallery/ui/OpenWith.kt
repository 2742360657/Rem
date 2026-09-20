package dev.susnowy.gallery.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.susnowy.gallery.model.MediaType

/**
 * Hands a file to whichever app the system has registered for its type.
 *
 * Rem deliberately ships no viewer or player of its own, so this is the whole "open" path. The
 * read grant is attached to the intent because the receiving app has no access to the Library
 * tree on its own, and an app that cannot read the URI would otherwise open on a blank screen.
 */
object OpenWith {

    sealed interface Outcome {
        data object Launched : Outcome
        data object NoHandler : Outcome
    }

    fun launch(context: Context, uri: Uri, type: MediaType): Outcome = try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (type == MediaType.IMAGE) "image/*" else "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Outcome.Launched
    } catch (_: ActivityNotFoundException) {
        Outcome.NoHandler
    } catch (_: SecurityException) {
        // The grant was revoked between the scan and the tap; reporting "nothing can open it"
        // is the honest answer, since Rem has no fallback viewer.
        Outcome.NoHandler
    }
}
