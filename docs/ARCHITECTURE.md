# Rem architecture

This document describes the implementation that exists now. Product semantics and roadmap live in `Gallery_Project_Guide.md`; historical investigations live in `docs/DEV_LOG.md`.

## Application shape

Rem is one Android application module targeting Android 8.0+. Kotlin packages separate responsibilities; extra Gradle modules are not justified yet.

- release application ID: `com.susnowy.rem`;
- debug application ID: `com.susnowy.rem.debug`;
- portable format identifier remains `gallery-library`;
- Schema v4 is current.

Debug and release can coexist. Device-local SQLite data, logs, previews, and SAF grants do not migrate between application IDs.

## Data ownership

```text
SAF media tree
  ├─ user media bytes
  └─ .gallery portable truth
         │
         ├─ projected into gallery-index.db
         └─ rendered by Compose
                  │
                  └─ disposable caches/previews
```

1. Media bytes remain in the selected SAF tree.
2. Portable user truth lives under `.gallery/`.
3. `gallery-index.db` stores query-oriented device projections, discoveries, URIs, and enrichment checkpoints.
4. Compose consumes repository state and local projections.
5. Decoded pages, Coil cache, diagnostics, and offline previews remain device-private.

## Portable Schema v4

### Documents

- `.gallery/library.json`: identity and current version;
- `.gallery/schema/v4.json`: machine-readable contract summary;
- `.gallery/items/catalog.json`: normalized logical catalog;
- `.gallery/state/state.json`: progress and logical trash keyed by `work_id`;
- `.gallery/state/inbox.json`: portable Inbox decisions keyed by relative path plus `work_id`;
- `.gallery/imports/*.json`: program-managed import and derivation manifests;
- `.gallery/transactions/*.json`: recoverable physical operations;
- `.gallery/backups/`: pre-migration and pre-operation snapshots;
- `GALLERY_LIBRARY.md`: generated instructions for an external organizing Agent.

### Catalog entities

`PortableAsset` owns physical source data. `PortableWork` owns editable metadata. `PortableEdition` joins a Work to ordered Assets. `PortableGroup` and `PortableSeries` point to Works.

`PortableCatalog.items` is a computed runtime projection for the current scanner/UI. It is not serialized. This lets the existing Android screens keep using `MediaItem` while portable storage no longer combines path, work metadata, editions, groups, and series into one record.

Every v4 load validates:

- unique entity IDs and Asset paths;
- relative, normalized portable paths;
- Edition → Work/Asset references;
- Work → preferred Edition reference;
- Group/Series → Work references;
- non-empty Edition sources;
- unique members inside each Series.

### Test-format conversion

v3 is converted once:

1. snapshot identity, catalog, state, guide, and v3 schema when present;
2. create one Asset, Work, and Edition for each former item;
3. normalize inline `SeriesRef` values into Series members;
4. rewrite state references from `item_id` to `work_id`;
5. write `schema/v4.json` and the new Library guide;
6. commit `library.json` as v4 last.

The converter is idempotent per document, so a stop after the catalog commit but before the state or identity commit can resume. Normal metadata reads accept only v4. Unknown newer versions are never written.

## Packages

- `library`: identity, initialization lease, Schema conversion, generated guide, atomic portable writer;
- `storage`: SAF traversal, mutation, locator reuse, and directory cache;
- `scanner`: inventory, classification candidates, enrichment, sidecar filtering;
- `metadata`: portable catalog/state/inbox storage, provenance, recognizers, ComicInfo;
- `data`: SQLite index and repository orchestration;
- `media`: lazy directory/archive pages, decoding, preload policy, offline previews;
- `ui`: Compose navigation, grids, viewers, editor, search, trash, settings;
- `organizer`: previewed and journaled physical layout changes;
- `importer`: copy-only system media import;
- `derive`: explicit copy-based derived media actions;
- `logging`: bounded, scrubbed diagnostics.

## Inbox decisions

`.gallery/state/inbox.json` (`PortableInboxStore`) records what the user decided about a surfaced path:

- `accepted` — the suggestion stands, the Work joins the normal views;
- `classified` — the user set the domain from Inbox; the Work's `domain` stays authoritative and the record only states what was chosen;
- `ignored` — keep the path out of both Inbox and the normal views without touching metadata;
- `handled` — a discovery target (unsupported file, ambiguous directory) needs no further work from Rem.

A decision is keyed by relative path and carries `work_id` when the target is a Work, so a rename or Organizer move still resolves; `PortableInboxStore.relocate` rewrites the path when the Organizer commits a move. Accepting or editing a Work writes a decision as part of the same operation, which is why an external Agent or a rebuilt index cannot silently return decided content to Inbox.

The device index (database v7) mirrors decisions into `media.inbox_disposition` and `discoveries.disposition`. `applyInboxDecisions` rewrites those columns from the portable document, so the document stays the single source of truth; `media.in_inbox` remains the pending flag and `discoveries` rows keep their disposition across rescans (`replaceDiscoveries` preserves it).

## Groups

A Group is "Works browsed together". It is edited only through explicit user actions; a scan never creates, reorders or deletes one.

- `PortableMetadataStore.upsertGroup` creates or replaces a Group in one atomic catalog write (`revision` conflict checked, members deduplicated, title trimmed, references validated). `deleteGroup` removes only the relationship.
- Order is the member list itself: the repository maps list position to `PortableGroupMember.sortIndex`, and `MediaGroup.membersInOrder()` reads it back.
- Saving a derived mixed folder uses `derivedGroupId(libraryId, primaryWorkId)`, so the same folder saved twice updates one Group instead of creating a second.
- Database v8 projects Groups into a `groups` table (`members_json`, disposable). `syncGroups` rewrites it from the catalog after attach and scan, and `upsertGroup`/`deleteGroupRow` keep single edits cheap; nothing in the table is a user decision.
- UI: the `图片 / 视频 → 分组` tab lists saved Groups first and unfiled derived folders second (with "保存为 Group"), and `GroupDetail` edits title, membership, order and cover locally until one explicit save.

Derived folder groups stay presentation-only inference until saved; after that the folder is no longer listed as a candidate and the Group is the user's.

## Series

- `PortableMetadataStore.upsertSeries` replaces one Series in a single catalog write (`revision` conflict checked, members deduplicated and ordered by `sort_index`), and `deleteSeries` removes the entity only.
- Membership edits stamp `field_sources.series = manual` on every touched Work inside the same write. The scanner prefers a manual series decision, so a reorder or removal cannot be undone by folder-name recognition on the next scan — including for Works that just left the series, which keep a manual "no series" decision.
- `GalleryRepository.saveSeries` maps list position to `sort_index`, preserves season/episode/volume/chapter unless the user clears them, then rewrites `series_json` on all touched media rows (`applySeriesAssignment`), clearing the assignment for Works that left.
- Database v9 projects Series into a `series` table (`members_json`, disposable), rebuilt from the catalog after attach and scan; `MediaSeries` is what the editor edits.
- UI: `漫画 / 阅读 → 系列书架 → 编辑系列` (`SeriesEditor`) does rename, batch add/remove, reordering and numbering reset, committed by one explicit save. The picker is the shared `WorkPickerDialog`.
- `ui.components.DragReorder` provides long-press drag reordering for fixed-height rows (`ReorderableRow`): the step arithmetic (`reorderStep`) is pure and unit-tested, the gesture only edits the editor's local list, and the up/down plus move-to-index paths stay available and produce the same order.

## Library initialization and writes

Initialization claims a provider-exclusive root lease before creating `.gallery/`. The lease carries a timestamp and expires after 15 minutes. `library.json` is the completion marker and is committed last.

`PortableDocumentWriter` stages every write with a unique name, temporarily moves the previous revision aside, then verifies the requested final path exists. If a provider publishes a qualified ` (1)` copy or leaves the staging document visible, the writer removes the refused result and restores the previous revision.

A returned rename URI or Boolean alone is not treated as proof of commit.

## Scanning

Scanning has two phases.

### Inventory

The inventory traverses directories using one projected query per directory. It classifies paths and commits a usable media/discovery index without reading media payloads.

The initial inventory is still one atomic traversal. If killed before its commit, it restarts. An unreadable subtree protects its previous indexed rows and is not treated as deletion.

### Enrichment

Hashes, archive manifests, ComicInfo, capture time, and coordinates enter a local `scan_enrichment` queue. Small batches atomically update media rows and checkpoints. A stopped process repeats at most the uncommitted batch.

An unchanged file reuses completed enrichment only when size and modified time still match. Changed content is reopened. Automatic hashes stop above 64 MiB unless a user-selected operation requires them.

## Runtime presentation

- `MediaItem` is the current SQLite/UI projection of a preferred Edition and its Work.
- The Series shelf is derived from normalized Series projected back to `SeriesRef`.
- Groups are read from the device projection of `catalog.json`; derived mixed folders remain presentation-only until the user saves one.
- Search and facet viewers retain their originating result order.
- Large UI collections are reconstructed from the local index rather than stored in Android saved state.

## Edition comparison and virtual merge

- `media.PageManifestService` turns one source (a Work's preferred Edition, resolved to pages) into an ordered `SourceManifest`. Directories cost one listing (plus one read per page only when hashing); archives cost exactly one sequential pass, because `ZipInputStream` cannot seek — hashing during that pass adds no I/O; single files cost one entry. Progress and cancellation are cooperatively checked per entry, and a session LRU caches manifests by (library, path, size, modified time, hashed).
- `compare.PageComparison` is pure: it matches by content hash first and by normalized page name second, and never calls a same-name-same-size page "identical" when bytes were not read. `compare.MergePlan` builds the virtual reading order (left order preserved, right-only pages inserted before the next shared page). Both are unit-tested without Android.
- `compare.MergeManifest` records the evidence of a merge and is written to `.gallery/imports/merge-<editionId>.json` (program-managed, never user truth).
- `PortableMetadataStore.upsertEdition` writes a page-plan Edition in one atomic catalog write and can set it as the Work's preferred Edition in the same write. `GalleryRepository.createMergedEdition` derives a stable Edition id from (library, target Work, both sources), so re-merging updates one Edition; sources are never modified and nothing is deleted.
- Device cost is surfaced, not hidden: the quick comparison reads no page bytes at all, and the deep comparison reads each source once.
- `media.editionPlanPages` turns a page plan into reader pages (`relativePath` = the file that holds the page, `archiveEntry` = entry inside an archive) and returns null for whole-container members, which makes the Work fall back to the single-source reader.
- The repository keeps the reading order of page-plan Editions in memory, keyed per Library, refreshed whenever the catalog is loaded and published right after a merge; `pages(item)` resolves plan pages' URIs with one directory listing per parent instead of one lookup per page.
- `MediaContentService.decodeArchivePage` takes an explicit archive path, so a merged plan's pages can come from different archives; the reader passes the page's own container at every decode site.
- UI: `EditionCompareDialog` (from the image-set detail screen) picks a second source, runs a quick or deep comparison with progress and cancellation, renders the report, and writes the merged Edition after an explicit confirmation.

## Card actions and shared dialogs

- `ui.components.MediaGrid` owns the long-press action panel. A card can open the item, accept a suggestion, edit, favourite, derive a copy, move to trash, and now also "add to Group", "add to Series" and open the Edition comparison. The grid collects the UI state once (not per card) so the pickers can list the Library's Groups and Series.
- `ui.components.TargetPickerDialog` is the shared single-select picker for a Group or Series target; `ui.components.EditionCompareDialog` moved here from the screens package so components do not depend on screens.
- `model.MembershipRules` holds the pure append/skip rules behind those actions (order preserved, duplicates dropped, Works in another Series reported rather than moved), so what a batch action writes is unit-tested.

## Media and cache budgets

- directory/archive pages are loaded lazily;
- comic pages use a 1440 × 6000 pixel decode budget;
- preload keeps five pages ahead and two behind, cancelling stale work;
- ZIP/CBZ decoded pages use a bounded 64 MiB bitmap cache;
- Coil may use up to 25% of heap and 768 MiB disposable disk cache;
- offline previews are 512 px JPEG files under `noBackupFilesDir`, capped at 256 MiB / 20,000 entries and trimmed to a 90% low-water mark.

No cache is portable truth.

## Safety invariants

- classification and logical relationships never move files;
- ordinary deletion writes portable logical trash;
- permanent deletion re-resolves the path and verifies current identity/size;
- an Inbox decision is written to `.gallery/state/inbox.json` before the device index mirrors it, and no disposition deletes or moves media;
- Organizer copies and verifies before deleting a source;
- page reordering and Organizer operations have recovery journals;
- manual provenance wins over automatic metadata;
- system media access is read-only until the user chooses a copy import;
- forgetting a Library removes only local registration, index, and SAF grant;
- a failed provider query never becomes a cached empty directory;
- diagnostics strip content URIs and host paths before writing;
- `.nomedia` prevents Library copies from being duplicated into the system album.

## Known scaling boundary

The scanner avoids repeated provider queries and byte reads, but `refreshFromDatabase()` still materializes all media rows. Real testing with the approximately 76,000-file sample determines whether the next change should be top-level inventory checkpoints, paged database queries, or both.

## Verification baseline

For ordinary changes, pick the command that matches the current host — this repository is developed on both Windows and Linux:

Windows (PowerShell):

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Linux/macOS (the wrapper keeps its executable bit; `bash gradlew` is equivalent):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Run AndroidJUnitRunner on a real device whenever SAF provider behavior, Schema conversion, media decoding, or navigation lifecycle changes. A successful emulator or empty-volume launch is not evidence of large removable-Library performance.
