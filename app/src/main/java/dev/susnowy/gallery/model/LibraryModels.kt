package dev.susnowy.gallery.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val GALLERY_FORMAT = "gallery-library"

/**
 * v1 → v2 adds per-field provenance (`field_sources`) to catalog items so that
 * automatic metadata can never silently overwrite a manual edit.
 * v2 → v3 adds an explicit media domain. This keeps the three product surfaces
 * (album, classified folders, and works) independent of mutable path names.
 */
const val CURRENT_SCHEMA_VERSION = 3

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
