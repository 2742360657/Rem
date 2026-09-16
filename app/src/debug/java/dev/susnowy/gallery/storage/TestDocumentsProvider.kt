package dev.susnowy.gallery.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Small, stateful DocumentsProvider used by connected tests.
 *
 * It deliberately qualifies colliding names like common removable-storage providers
 * and can fail one child query, allowing the SAF adapter to be exercised through the
 * real ContentResolver/DocumentsContract Binder path instead of mocks.
 */
class TestDocumentsProvider : DocumentsProvider() {
    private data class Node(
        val id: String,
        val parentId: String?,
        var name: String,
        val mimeType: String,
        val modifiedAt: Long = System.currentTimeMillis(),
    )

    private val nodes = linkedMapOf<String, Node>()
    private val childQueries = mutableMapOf<String, Int>()
    private var sequence = 0
    private var failChildrenOf: String? = null

    override fun onCreate(): Boolean {
        reset()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: ROOT_COLUMNS
        return MatrixCursor(columns).apply {
            addRow(Array<Any?>(columns.size) { index ->
                when (columns[index]) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                    DocumentsContract.Root.COLUMN_TITLE -> "Rem SAF test"
                    DocumentsContract.Root.COLUMN_FLAGS ->
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                            DocumentsContract.Root.FLAG_LOCAL_ONLY
                    DocumentsContract.Root.COLUMN_MIME_TYPES -> "*/*"
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> 1_000_000_000L
                    else -> null
                }
            })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        return documentsCursor(projection, listOf(node))
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        nodes[parentDocumentId] ?: throw FileNotFoundException(parentDocumentId)
        childQueries[parentDocumentId] = (childQueries[parentDocumentId] ?: 0) + 1
        if (failChildrenOf == parentDocumentId) {
            throw FileNotFoundException("Injected child-query failure: $parentDocumentId")
        }
        return documentsCursor(
            projection,
            nodes.values.filter { it.parentId == parentDocumentId },
        )
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = nodes[parentDocumentId] ?: throw FileNotFoundException(parentDocumentId)
        if (parent.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            throw FileNotFoundException("Not a directory: $parentDocumentId")
        }
        val actualName = availableName(parentDocumentId, displayName)
        val id = "node-${++sequence}"
        nodes[id] = Node(id, parentDocumentId, actualName, mimeType)
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        node.name = availableName(node.parentId, displayName, exceptId = documentId)
        return documentId
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current = nodes[documentId]?.parentId
        while (current != null) {
            if (current == parentDocumentId) return true
            current = nodes[current]?.parentId
        }
        return false
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_ID || documentId !in nodes) throw FileNotFoundException(documentId)
        val descendants = generateSequence(setOf(documentId)) { parents ->
            nodes.values.filterTo(mutableSetOf()) { it.parentId in parents }.map(Node::id).toSet()
                .takeIf(Set<String>::isNotEmpty)
        }.flatten().toSet()
        descendants.forEach(nodes::remove)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        if (node.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            throw FileNotFoundException("Directory: $documentId")
        }
        val file = File(requireNotNull(context).cacheDir, "rem-provider-$documentId")
        if (!file.exists()) file.createNewFile()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_RESET -> Bundle().also { reset() }
        METHOD_SEED_DIRECTORY -> Bundle().apply {
            putString(RESULT_ID, seedDirectory(requireNotNull(extras?.getString(ARG_PATH))))
        }
        METHOD_FAIL_CHILDREN -> Bundle().also { failChildrenOf = arg }
        METHOD_CHILD_QUERY_COUNT -> Bundle().apply {
            putInt(RESULT_COUNT, childQueries[arg] ?: 0)
        }
        else -> super.call(method, arg, extras)
    }

    private fun reset() {
        nodes.clear()
        nodes[ROOT_ID] = Node(
            id = ROOT_ID,
            parentId = null,
            name = "root",
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        )
        childQueries.clear()
        failChildrenOf = null
        sequence = 0
    }

    private fun seedDirectory(path: String): String {
        var parentId = ROOT_ID
        path.replace('\\', '/').trim('/').split('/').filter(String::isNotBlank).forEach { name ->
            val existing = nodes.values.firstOrNull { it.parentId == parentId && it.name == name }
            parentId = existing?.id ?: createDocument(
                parentId,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        }
        return parentId
    }

    private fun availableName(parentId: String?, requested: String, exceptId: String? = null): String {
        val occupied = nodes.values
            .filter { it.parentId == parentId && it.id != exceptId }
            .mapTo(mutableSetOf()) { it.name.lowercase() }
        if (requested.lowercase() !in occupied) return requested

        val dot = requested.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { requested.substring(0, it) } ?: requested
        val extension = dot?.let { requested.substring(it) }.orEmpty()
        var suffix = 1
        while (true) {
            val candidate = "$stem ($suffix)$extension"
            if (candidate.lowercase() !in occupied) return candidate
            suffix++
        }
    }

    private fun documentsCursor(
        projection: Array<out String>?,
        selected: List<Node>,
    ): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: DOCUMENT_COLUMNS
        return MatrixCursor(columns).apply {
            selected.forEach { node ->
                addRow(Array<Any?>(columns.size) { index ->
                    when (columns[index]) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mimeType
                        DocumentsContract.Document.COLUMN_SIZE -> 0L
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> node.modifiedAt
                        DocumentsContract.Document.COLUMN_FLAGS ->
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                                if (node.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                                } else {
                                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                                }
                        else -> null
                    }
                })
            }
        }
    }

    companion object {
        const val AUTHORITY = "dev.susnowy.gallery.test.documents"
        const val ROOT_ID = "root"
        const val METHOD_RESET = "reset"
        const val METHOD_SEED_DIRECTORY = "seed-directory"
        const val METHOD_FAIL_CHILDREN = "fail-children"
        const val METHOD_CHILD_QUERY_COUNT = "child-query-count"
        const val ARG_PATH = "path"
        const val RESULT_ID = "id"
        const val RESULT_COUNT = "count"

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
