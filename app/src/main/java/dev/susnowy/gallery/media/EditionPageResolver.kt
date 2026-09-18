package dev.susnowy.gallery.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Resolve one listing per parent, without blocking the caller's UI dispatcher. */
suspend fun resolveEditionPages(
    plan: List<ImagePage>,
    listDirectory: (String) -> Map<String, String>,
): List<ImagePage> = withContext(Dispatchers.IO) {
    val parents = plan.mapNotNull { page ->
        page.relativePath?.takeIf { page.archiveEntry == null }?.substringBeforeLast('/', "")
    }.distinct()
    val listings = parents.associateWith { parent ->
        coroutineContext.ensureActive()
        listDirectory(parent)
    }
    plan.map { page ->
        coroutineContext.ensureActive()
        val path = page.relativePath
        if (page.archiveEntry != null || path == null) page
        else page.copy(uri = listings[path.substringBeforeLast('/', "")]?.get(path))
    }
}
