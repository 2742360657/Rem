package dev.susnowy.gallery.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val GALLERY_FORMAT = "gallery-library"

/**
 * v4 is the first normalized pre-release format. Physical assets, logical works,
 * editions, browsing groups, and ordered series have one portable source of truth.
 */
const val CURRENT_SCHEMA_VERSION = 4

/** Raised instead of writing to a Library that declares a newer Schema. */
class UnsupportedSchemaException(message: String) : IllegalStateException(message)

@Serializable
data class PortableLibrary(
    val format: String = GALLERY_FORMAT,
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("library_id") val libraryId: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

enum class PermissionState { AVAILABLE, OFFLINE, REVOKED }

data class LibraryRegistration(
    val libraryId: String,
    val name: String,
    val treeUri: String,
    val permissionState: PermissionState,
    val schemaVersion: Int,
    val lastScanAt: Long? = null,
)

sealed interface LibraryInspection {
    data object Missing : LibraryInspection
    data class Valid(val library: PortableLibrary) : LibraryInspection
    data class Unsupported(val schemaVersion: Int) : LibraryInspection
    data class Invalid(val reason: String) : LibraryInspection
}
