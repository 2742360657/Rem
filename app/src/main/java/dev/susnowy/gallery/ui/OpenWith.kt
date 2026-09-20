package dev.susnowy.gallery.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.susnowy.gallery.model.MediaType

/**
 * Hands a file to whichever app the system has registered for its type.
 *
 * This is no longer the whole "open" path — the built-in viewer handles the tap — but it stays on
 * every surface as an explicit action and as the fallback when the viewer cannot decode a file.
 * The read grant rides along on the intent because the receiving app has no access to the Library
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
        // The grant was revoked between the scan and the tap; reporting that nothing can open it is
        // still the honest answer.
        Outcome.NoHandler
    }
}
