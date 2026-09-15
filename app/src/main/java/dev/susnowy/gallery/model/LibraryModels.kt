package dev.susnowy.gallery.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val GALLERY_FORMAT = "gallery-library"
const val CURRENT_SCHEMA_VERSION = 1

@Serializable
data class PortableLibrary(
    val format: String = GALLERY_FORMAT,
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("library_id") val libraryId: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

sealed interface LibraryInspection {
    data object Missing : LibraryInspection
    data class Valid(val library: PortableLibrary) : LibraryInspection
    data class Unsupported(val schemaVersion: Int) : LibraryInspection
    data class Invalid(val reason: String) : LibraryInspection
}
