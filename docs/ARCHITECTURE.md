# Gallery architecture

Gallery is an Android 8.0+ local-first media library. The project intentionally uses one Gradle application module and separates responsibilities by Kotlin package; physical module splitting can wait until build or ownership pressure justifies it.

## Data layers

1. Media bytes remain in the user-selected Storage Access Framework tree.
2. Portable truth lives under `.gallery/` in that tree. It contains the versioned Library identity, catalog overrides, progress, logical trash, imports, backups, and recoverable file transactions.
3. `gallery-index.db` is a device-local SQLite index. It stores SAF URIs and query-friendly projections and can be rebuilt from the Library.
4. Compose screens consume repository state. Thumbnail and decoder data never enters the portable Library.

Android URIs and mount paths are local-only. Every portable media path uses `/`-separated paths relative to the Library root.

## Packages

- `library`: initialization, identity, Schema v1, and generated Library guide.
- `storage`: the only layer that directly traverses or mutates SAF documents.
- `scanner`: nested-directory classification, content fingerprints, EXIF/video dates, ZIP/CBZ discovery, and Inbox candidates.
- `metadata`: portable catalog/state persistence, revision checks, ComicInfo, and provider contracts.
- `data`: local SQLite index and application repository.
- `media`: lazy folder/archive page access and sampled archive decoding.
- `organizer`: previewed, conflict-checked, journaled physical organization and interrupted-operation recovery.
- `importer` and `derive`: copy-only system media imports and explicit derived media operations.
- `ui`: Compose navigation, grids, readers, player, metadata editor, search, trash, and settings.

## Safety invariants

- Classification never moves files.
- Ordinary deletion only writes a logical trash entry.
- Permanent deletion re-resolves and validates the target first.
- Organizer copies and verifies before deleting a source and records every durable step.
- Schema and Organizer changes create portable metadata backups.
- A newer unsupported Library Schema is never written.
- Manual portable metadata wins over local and inferred metadata.
- Duplicate detection reports SHA-256 matches but never deletes or merges them.
- System media browsing uses read-only `MediaStore` access with full/partial/denied states; it never requests `MANAGE_EXTERNAL_STORAGE`.
- Forgetting a Library removes only its device-local index and persisted SAF grant; it never mutates the selected tree.
- Photos View selection is ephemeral UI state. Batch Author/Tag/Collection/favorite edits merge into one portable catalog write, and batch trash uses one portable state write before synchronizing the local index.
- Image and Photos viewers use a lazy horizontal pager. One-finger swipes page while unzoomed; once zoomed, the current image owns pan gestures until it returns to its base scale.
- A detail viewer keeps the originating grid/result order as its paging context, so Search and facet browsing do not leak into unrelated media.
- `SavedStateHandle` retains only small navigation keys (screen, query, selected item); large media collections are reconstructed from the local index instead of being placed in Android saved-state bundles.
- Every Library has a root `.nomedia` marker so Android media scanners ignore Library copies while Gallery continues to use SAF.
- System album imports preserve portable source-directory text but never persist Android content URIs in Library metadata.
- Organizer prunes only verified-empty directories below known Library media roots and never deletes the roots themselves.

## Build and verification

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The installable development APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Release output is minified but unsigned; production distribution needs an owner-provided signing configuration.
