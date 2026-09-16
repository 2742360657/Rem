# Rem architecture

Rem is an Android 8.0+ local-first media library. The project intentionally uses one Gradle application module and separates responsibilities by Kotlin package; physical module splitting can wait until build or ownership pressure justifies it.

The public product name is Rem and its release application ID is `com.susnowy.rem`. It installs alongside the legacy `dev.susnowy.gallery` development build, so device-local indexes and SAF grants do not migrate automatically. Kotlin package names, `.gallery/`, and Gallery portable-schema identifiers remain stable Library compatibility contracts.

## Data layers

1. Media bytes remain in the user-selected Storage Access Framework tree.
2. Portable truth lives under `.gallery/` in that tree. It contains the versioned Library identity, catalog overrides, progress, logical trash, imports, backups, and recoverable file transactions.
3. `gallery-index.db` is a device-local SQLite index. It stores SAF URIs and query-friendly projections and can be rebuilt from the Library.
4. Every item has a portable `domain`: `album`, `classified`, or `works`. Compose exposes these as exactly three primary destinations; file type and product surface are not conflated.
5. Compose screens consume repository state. Thumbnail and decoder data never enters the portable Library.

Android URIs and mount paths are local-only. Every portable media path uses `/`-separated paths relative to the Library root.

## Packages

- `library`: initialization, identity, additive Schema v3 migration, and generated Library guide.
- `storage`: the only layer that directly traverses or mutates SAF documents.
- `scanner`: nested-directory classification, content fingerprints, EXIF/video dates, ZIP/CBZ discovery, and Inbox candidates.
- `metadata`: portable catalog/state persistence, revision checks, ComicInfo, and provider contracts.
- `data`: local SQLite index and application repository.
- `media`: lazy folder/archive access, sampled archive decoding, a bounded decoded-page cache, and direction-aware comic preloading.
- `organizer`: previewed, conflict-checked, journaled physical organization and interrupted-operation recovery.
- `importer` and `derive`: copy-only system media imports and explicit derived media operations.
- `logging`: the device-side record used to investigate a problem reported from a phone.
- `ui`: Compose navigation, grids, readers, player, metadata editor, search, trash, and settings.

## SAF access cost

Every `DocumentsContract` query is a Binder round-trip, and on a removable Library the
provider behind it is a USB device, so the number of round-trips — not the number of
bytes — dominates scan time. Three rules keep that count proportional to the tree:

- A directory is listed with one projection query returning every column, because
  `DocumentFile` answers `name`, `type`, `isDirectory`, `length()` and `lastModified()`
  with five separate queries per child.
- `DocumentTreeStorage` memoizes path-to-document resolution and directory listings, so a
  path is never walked from the tree root twice. Mutations invalidate only the affected
  subtree.
- Documents carry their provider locator, and readers prefer it, so a read never
  re-resolves a path it was already handed.

A rescan compares size and modified time against the indexed record and reuses the
recorded fingerprint and capture metadata. Only a changed file is opened, so an unchanged
Library performs no content reads at all. A size or timestamp difference always re-reads,
because keeping a stale fingerprint would silently mis-merge metadata.

## Safety invariants

- Classification never moves files.
- Ordinary deletion only writes a logical trash entry.
- Permanent deletion re-resolves and validates the target first.
- Organizer copies and verifies before deleting a source and records every durable step.
- Schema and Organizer changes create portable metadata backups.
- A newer unsupported Library Schema is never written.
- Manual portable metadata wins over local and inferred metadata.
- Album media stays metadata-light, classified media uses physical folders as its hierarchy, and author/tag/series facets are confined to works.
- Duplicate detection reports SHA-256 matches but never deletes or merges them.
- System media browsing uses read-only `MediaStore` access with full/partial/denied states; it never requests `MANAGE_EXTERNAL_STORAGE`.
- Forgetting a Library removes only its device-local index and persisted SAF grant; it never mutates the selected tree.
- Photos View selection is ephemeral UI state. Batch Author/Tag/Collection/favorite edits merge into one portable catalog write, and batch trash uses one portable state write before synchronizing the local index.
- Image and Photos viewers use a lazy horizontal pager. One-finger swipes page while unzoomed; once zoomed, the current image owns pan gestures until it returns to its base scale.
- The comic reader decodes directory pages against a 1440 × 6000 pixel budget, preloads five pages in the scroll direction plus two behind, and cancels stale preloads after a fast jump. Coil may use up to 25% of the app heap for decoded images and 768 MB of disposable disk cache; ZIP/CBZ pages additionally use a bounded 64 MB bitmap cache. None of these caches enter the portable Library.
- A detail viewer keeps the originating grid/result order as its paging context, so Search and facet browsing do not leak into unrelated media.
- `SavedStateHandle` retains only small navigation keys (screen, query, selected item); large media collections are reconstructed from the local index instead of being placed in Android saved-state bundles.
- Every Library has a root `.nomedia` marker so Android media scanners ignore Library copies while Rem continues to use SAF.
- System album imports preserve portable source-directory text but never persist Android content URIs in Library metadata.
- Organizer prunes only verified-empty directories below known Library media roots and never deletes the roots themselves.
- Initializing a Library claims an atomic lock and commits its identity last, so two concurrent attaches cannot produce two identities in one directory.
- A portable document is only committed once it is addressable under exactly the requested path. A provider that publishes a qualified copy instead of replacing the target gets that duplicate removed and the previous revision restored.
- Diagnostics are written to the app's private files directory, never into the Library, and are reduced before writing: content URIs and host filesystem paths are stripped, so account material and volume ids cannot leave the device through an exported log.

## Build and verification

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The installable development APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Release signing is loaded from an external properties file (`T:/jks/keystore.properties` by default) so no key or password enters Git. The owner must preserve the same release key for every later update. `assembleRelease` also copies the R8 mapping to `dist/Rem-<version>-mapping.txt`, which is what makes a crash report from a user's device readable after `build/` is cleaned.
