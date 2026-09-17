# Rem architecture

Rem is an Android 8.0+ local-first media library. The project intentionally uses one Gradle application module and separates responsibilities by Kotlin package; physical module splitting can wait until build or ownership pressure justifies it.

The public product name is Rem and its release application ID is `com.susnowy.rem`. Debug builds use `com.susnowy.rem.debug`, so development and release signatures can coexist without replacing each other. Both install alongside the legacy `dev.susnowy.gallery` development build; device-local indexes and SAF grants do not migrate automatically between application IDs. Kotlin package names, `.gallery/`, and Gallery portable-schema identifiers remain stable Library compatibility contracts.

## Data layers

1. Media bytes remain in the user-selected Storage Access Framework tree.
2. Portable truth lives under `.gallery/` in that tree. It contains the versioned Library identity, catalog overrides, progress, logical trash, imports, backups, and recoverable file transactions.
3. `gallery-index.db` is a device-local SQLite index. It stores SAF URIs, query-friendly media projections, rebuildable discovery records for unsupported files or ambiguous directories, and the disposable `scan_enrichment` resume queue. It can be rebuilt from the Library; neither a discovery row nor an enrichment checkpoint is portable truth.
4. Every item has a portable `domain`: `album`, `classified`, or `works`. Compose exposes these as exactly three primary destinations; file type and product surface are not conflated.
5. Compose screens consume repository state. Thumbnail and decoder data never enters the portable Library.

`OfflinePreviewStore` lazily derives a 512 px JPEG only for a card that is actually requested. Files live under the app's `noBackupFilesDir`, are keyed by Library/item identity plus source modification state, and are capped at 256 MiB / 20,000 entries with a 90% low-water trim. They are an offline recognition aid, not metadata truth or an original-media backup; Settings can clear them without affecting the Library.

The current series shelf is a presentation derived from each item's portable `SeriesRef`; it does not introduce a second source of truth. It groups legacy same-title references case-insensitively for display, orders explicit `sort_index` first and otherwise falls back to season/episode or volume/chapter, and keeps unassigned works visible. A future normalized series document must migrate these inline references explicitly rather than silently treating the derived shelf as portable truth.

Mixed image/video folders currently use the same deliberately derived approach. The scanner retains one directory-backed `IMAGE_SET` and independent direct-child `VIDEO` rows; `MixedMediaPresentation` joins them by Library and physical parent only for the 图片 / 视频 UI. Group videos are hidden from the separate video tab while the group is being presented, but no membership is written to `.gallery/`. A future portable Group/Edition model must replace this inference for manual membership, cross-directory grouping, edition comparison, and merge decisions without changing or deleting source media.

Android URIs and mount paths are local-only. Every portable media path uses `/`-separated paths relative to the Library root.

## Packages

- `library`: initialization, identity, additive Schema v3 migration, and generated Library guide.
- `storage`: the only layer that directly traverses or mutates SAF documents.
- `scanner`: nested-directory discovery and classification, explicit sidecar/internal ignore rules, content fingerprints, EXIF/video dates, ZIP/CBZ inspection, and Inbox candidates.
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
  path is never walked from the tree root twice. A mutation invalidates the affected
  subtree and its direct parent's listing while retaining resolved ancestors and unrelated
  branches. A provider returning no cursor is an I/O failure, never a cached empty directory.
- Documents carry their provider locator, and readers prefer it, so a read never
  re-resolves a path it was already handed.

Scanning has two phases. The inventory phase recursively lists the tree, classifies paths,
and commits a usable media/discovery index without opening media payloads. Byte-level work
(small-file hashes, ZIP/CBZ manifests and ComicInfo, and album capture metadata) is queued in
`scan_enrichment` and committed in small batches. The queue is local and rebuildable; after a
process restart the app continues its remaining rows without repeating completed byte reads.

A rescan compares size and modified time against an indexed row whose enrichment checkpoint
is complete and reuses its fingerprint, capture metadata, and archive page count. Only a
changed or unfinished file is opened; changed ZIP/CBZ files are traversed for page count and
ComicInfo. Directory fingerprints include page modification times. A size or timestamp
difference always re-reads because keeping a stale fingerprint would silently mis-merge
metadata.

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
- Initializing a Library claims a root-level provider-exclusive lease and commits its identity last, so two concurrent attaches cannot create separate `.gallery` directories or identities. The lease is released after commit; a lease left by a stopped process expires after 15 minutes.
- A portable document is only committed once it is addressable under exactly the requested path. A provider that publishes a qualified copy instead of replacing the target gets that duplicate removed and the previous revision restored.
- Diagnostics are written to the app's private files directory, never into the Library, and are reduced before writing: complete messages and exception summaries have content URIs and host filesystem paths stripped. Export waits for queued writes and grants read-only FileProvider URIs through the system share sheet.
- A scan reports directory, entry, and candidate counts while it runs. A failed directory query marks the scan incomplete and protects that subtree's previous local rows; temporary provider failure is not treated as media deletion.
- The inventory commit and every enrichment batch update media rows and local resume state atomically. Killing the process during enrichment can repeat at most the uncommitted batch; it cannot turn a partial deep scan into a completed checkpoint. The initial tree inventory itself is still one atomic pass and may repeat if killed before its first commit.
- Unsupported user-visible files and structurally ambiguous directories remain in a separate local discovery index and appear under Inbox. Known internal files and registered sidecars are ignored. Discovery alone never creates portable metadata or claims that Android can decode the path.

## Build and verification

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
.\gradlew.bat assembleDebugAndroidTest connectedDebugAndroidTest
```

The installable development APK is generated at `app/build/outputs/apk/debug/app-debug.apk` with application ID `com.susnowy.rem.debug`. A debug-only `DocumentsProvider` plus connected tests exercise tree queries, provider-qualified names, scoped cache invalidation, query failures, and full portable-Library initialization through the real `ContentResolver` path; neither the provider nor its tests enter release builds. If Gradle's unified test-platform report dependencies are unavailable, build `assembleDebugAndroidTest`, install both APKs with ADB, and invoke `com.susnowy.rem.debug.test/androidx.test.runner.AndroidJUnitRunner` directly.

Release signing is loaded from an external properties file (`T:/jks/keystore.properties` by default) so no key or password enters Git. The owner must preserve the same release key for every later update. `assembleRelease` also copies the R8 mapping to `dist/Rem-<version>-mapping.txt`, which is what makes a crash report from a user's device readable after `build/` is cleaned.
