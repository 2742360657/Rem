# Rem architecture

This document describes the implementation that exists now. Product semantics and acceptance criteria live in [PRODUCT](PRODUCT.md); portable contracts in [PORTABLE_FORMAT](PORTABLE_FORMAT.md); current verification and remaining work in [STATUS](STATUS.md); historical investigations in [DEV_LOG](DEV_LOG.md).

## Application shape

`createVideoPlayer` configures Media3 with USAGE_MEDIA / CONTENT_TYPE_MOVIE, automatic audio focus, and audio-becoming-noisy handling. The same factory is used by the viewer and the real-audio focus regression test.

`VideoPlaybackLifecycle` pauses on the owning lifecycle's ON_PAUSE and submits initialized playback progress through the existing repository path. ON_RESUME resumes only playback it paused; manual pauses remain paused. Inactive pager pages stay paused. This binding lives outside the fullscreen surface so swapping inline/fullscreen views does not reset its playback intent.

`MainActivity` handles orientation/screen-size configuration changes so entering video fullscreen does not recreate the player. Compose observes the new configuration for layout. `VideoPlayerSurface` attaches the same Player to its inline or fullscreen view, requests sensor landscape only while fullscreen, and restores the previous orientation on exit. Other configuration changes and process recreation still follow normal saved-state restoration.

Rem has one Android application module targeting Android 8.0+ and a Java 17 standalone `library-tool` module. The tool compiles the existing portable models and `portable` package directly from the App source tree; it does not fork the format or depend on Android storage/database classes.

- release application ID: `com.susnowy.rem`;
- debug application ID: `com.susnowy.rem.debug`;
- portable format identifier remains `gallery-library`;
- Schema v4 is current.

Debug and release can coexist. Device-local SQLite data, logs, previews, and SAF grants do not migrate between application IDs.

## Data ownership

`MediaContentService` keys decoded archive pages by Library ID, archive path, size, modified time, entry name and decode target dimensions. Virtual merged Editions resolve the referenced archive's metadata before both cache lookup and decoding; the containing Work's version does not identify another archive. This is a disposable memory cache, not portable metadata.

`MediaReadPriority` lets repository page loading and archive/oversized decoding preempt offline preview generation and the read phase of enrichment. Preempted reads retry after all foreground reads finish; caller cancellation never retries. Portable/index commits stay outside the retry block. Inventory waits before its next directory query; it does not restart a whole scan. Hashing and archive copying check cancellation between reads, not during a blocking Provider call. Coil's direct reads do not yet participate.

`ArchiveCache` separates its serialized copy lock from short cache lookup/commit locks. Cached ZIP opens and statistics do not wait on Provider copying. Clear waits for copying before removing files, preserving its existing semantics. Uncached requests still serialize; this is not a complete foreground-priority scheduler.

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
- `portable`: shared structural validation, JSON-tree Agent field edits and packaged instructions;
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

Batch information edits use `BatchMetadataEdit` with an opening selection snapshot. Target values and provenance are compared with freshly loaded portable metadata; any target conflict skips that Work. `saveBatchFields` changes only authors, tags, collections, domain and their provenance for existing Works, preserving source and relationship entities and unknown Work/catalog extensions. New Inbox Works are created with their initial source projection. Catalog commits precede Inbox decisions; libraries commit independently and the result reports partial failures. This is not a cross-document transaction or an external-writer lock.

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
- `DragReorderState` also carries the auto-scroll: while a row is dragged, `dragAutoScrollSpeed`/`dragAutoScrollDelta` (pure, unit-tested) scroll the enclosing `LazyListState` when the finger is held near an edge. The consumed scroll is fed into the logical drag distance, so rows crossed by auto-scroll participate in reordering instead of producing a visual-only displacement, while the finger position used for the edge speed moves only when the finger moves — auto-scroll must not slow itself down by pretending the finger drifted back to the middle.
- A swap changes the row's layout position; only the unconsumed drag remainder is applied as translation. Handle pointer input is keyed by the stable drag state, with updated index/callbacks, so reordering does not cancel the held gesture. Editor row spacing matches the fixed height used by the geometry.

## Series reading

- `ui.SeriesReading` is the pure reading state of one ordered chapter list: `SeriesChapter` (progress, finished, label), `SeriesReading.entry` for the "continue reading" rule (the most recently opened unfinished chapter wins, then the first unread one, then the first chapter again) and `nextAfter` for the chapter link.
- "Started" is decided by the portable first-open marker (`PlaybackProgress.openedAt`), not by the page index and not by the presence of a reading row: page 0 is a real position, so a chapter showing its first page is started, while a row left over from an older index without an open marker still means "untouched".
- Completion is the explicit portable `finished` flag, never an inference from restoring the last page. `resumePageIndex` is the single rule for the page a reader opens at — explicit restart, unopened and finished all start at page 1 — so the chapter list, the detail page and the reader cannot disagree.
- The chapter list is ordered by the Series' own reading order (`SeriesPresentation.orderEntries`), not by the work grid's current sort. A chapter that was just added has the newest modification time, and taking the grid order would misdirect both the numbering and "next chapter".
- Identity is the portable Series id everywhere: shelves, the editor target, the projection and the one-time v3 conversion all key on it, so two legitimately same-named Series cannot merge members, numbering or progress.
- Opening a Series shows `ui.screens.SeriesChapterList` instead of a work grid: every chapter with its position, numbering and reading state, a whole-series progress bar, a "continue reading" entry and a way into the series editor.
- `GalleryUiState.readerQueue` remembers the ordered Works the open reader belongs to, so "next chapter" means "the next entry of the list I opened from". Opening a single Work from search keeps a one-entry queue and therefore offers no chapter link; only the chapter list establishes a Series queue.
- The reader shows an end-of-chapter footer ("本话已读完 / 阅读下一话") and can advance automatically; completion and hand-over only fire after forward movement reaches the physical end, so restoring the last page does not complete or push the reader onwards. The final chapter's completion action returns to its overview.
- `model.ProgressRules` owns the write rule for reading progress, pure and unit-tested: a write stamped before the stored one is dropped (coroutine scheduling is not the order the user acted in), and the first-open stamp is preserved rather than refreshed on every page turn. `GalleryRepository.markOpened` records the open event separately from turning pages.
- Whether a chapter end continues into the next one is a device-local preference (`auto_advance_chapters`), not a portable decision.


## Library initialization and writes

`GalleryViewModel.attachment` tracks picker-return attachment stages independently of scan operations. Persistable read/write grants and directory-name queries run on IO; denied grants stop attachment explicitly. A ViewModel-owned dialog remains visible for pending work and errors (including the empty-library screen), and duplicate attachment jobs are ignored. Failures retain their message until dismissal or a new selection; device-specific failures still require actual provider evidence.

Initialization claims a provider-exclusive root lease before creating `.gallery/`. The lease carries a timestamp and expires after 15 minutes. `library.json` is the completion marker and is committed last.

`PortableDocumentWriter` stages every write with a unique name, moves the previous revision to the stable `.<name>.rem-backup` recovery path, then verifies the requested final path exists. A process stop between both renames is recovered by the next read (including `library.json` identity inspection); a live target wins over a stale recovery file. If a provider publishes a qualified ` (1)` copy or leaves the staging document visible, the writer refuses the commit and restores the previous revision.

A returned rename URI or Boolean alone is not treated as proof of commit.

All repository mutations of portable catalog/state/Inbox documents share one process-local mutex. This prevents two read-modify-write operations (for example playback progress and trash, or Group and metadata edits) from committing stale snapshots over each other. Accepted/classified Inbox actions commit Work metadata first and the Inbox decision second, so a catalog failure cannot hide an item whose manual classification was never saved.

Portable progress/trash is projected with `GalleryDatabase.replacePortableState`: one transaction clears stale device rows for the Library and writes only valid current portable entries. Progress writes carry an event timestamp assigned before coroutine dispatch; older events are rejected, and a repository revision signal makes active screens reload after a successful save.

## Scanning

Scanning has two phases.

### Inventory

The inventory traverses directories using one projected query per directory. It classifies paths and commits a usable media/discovery index without reading media payloads.

The initial inventory is still one atomic traversal. If killed before its commit, it restarts. An unreadable subtree protects its previous indexed rows and is not treated as deletion.

Inventory deliberately does not hold the portable-write mutex for the long directory traversal. After traversal finishes, the repository reloads catalog/state/Inbox and holds the mutex only while projecting that fresh snapshot into SQLite. An edit made during inventory is included; an edit that starts during projection waits instead of being overwritten by stale local rows.

### Enrichment

Hashes, archive manifests, ComicInfo, capture time, and coordinates enter a local `scan_enrichment` queue. Small batches atomically update media rows and checkpoints. A stopped process repeats at most the uncommitted batch.

An unchanged file reuses completed enrichment only when size and modified time still match. Changed content is reopened. Automatic hashes stop above 64 MiB unless a user-selected operation requires them.

Reading media bytes happens outside the portable-write mutex, so a long batch never blocks a metadata edit or a trash action. The batch is therefore committed by **merging into the row read at commit time** (`MediaItem.mergeEnrichment`, pure and unit-tested): `manual` fields are never touched, everything else is filled from the recognition result, and the batch re-reads its rows in one query (`GalleryDatabase.mediaItems`). `updateMedia(previous, edited)` applies the same rule in the other direction (`mergeEdit`): the editor baseline identifies the fields the user actually changed, while values and provenance re-read at commit time are preserved for untouched fields. Path-derived recognition for a directory image set is recomputed on every scan because it reads no media bytes; only the byte-level ComicInfo read is skipped while the directory is known to be unchanged.


## Runtime presentation

- `MediaItem` is the current SQLite/UI projection of a preferred Edition and its Work.
- The Series shelf is derived from normalized Series projected back to `SeriesRef`, grouped strictly by stable Series ID (equal titles do not imply equal identity), and opening a shelf shows its chapter list rather than a plain work grid.
- Groups are read from the device projection of `catalog.json`; derived mixed folders remain presentation-only until the user saves one.
- Search and facet viewers retain their originating result order.
- Large UI collections are reconstructed from the local index rather than stored in Android saved state.

## Edition comparison and virtual merge

- `media.PageManifestService` turns one source (a Work's preferred Edition, resolved to pages) into an ordered `SourceManifest`. Directories cost one listing (plus one read per page only when hashing); archives reuse the `ArchiveCache` copy, enumerate its central directory once and read each page once only when hashing; single files cost one entry. Progress and cancellation are cooperatively checked per entry, and a session LRU caches manifests by (library, path, size, modified time, hashed).
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

## Selection and batch actions

- `ui.components.SelectableMediaGrid` wraps a grid with selection mode and the Library's batch actions (add to Group, add to Series, favourite, append metadata, trash). The image/video and works views both use it, so batch behaviour cannot drift between screens; the album screen keeps its own toolbar because it also offers image-set derivation. Suppressing `showSelectButton` removes the entry point, not just the button: a view that passes `false` has no way to start a selection, so the works list must keep it enabled.
- The grid owns only ids: every action is one repository call (one portable write), and long-press still opens the per-card panel while selection mode is off. Back exits selection before it leaves the screen.
- `ui.components.BatchMetadataDialog` is the single append-only metadata editor shared by all selection toolbars.

## Archive access

- The primary archive path goes through `media.ArchiveCache`: the SAF document is copied once into the app cache directory and opened with `ZipFile`, whose central-directory walk accepts every ZIP layout. `ZipInputStream` rejects archives whose entries are stored uncompressed with an extended data descriptor (`only DEFLATED entries can have EXT descriptor`) — a layout real downloaders produce — which used to cost page counts, page lists and ComicInfo for those files.
- Cache keys carry (library, relative path, size, modified time), so changed content is never reused; copies are written to a `.part` file and renamed; the budget defaults to 512 MiB and trims oldest-use-first to a 90% low-water mark (`archiveEvictions`, pure and unit-tested). The file currently being opened is protected from its own trim, so a single archive larger than the budget remains readable while older copies are evicted.
- Callers keep a streaming fallback for when no cached copy is available. `ComicInfoReader.inspectArchive(zip, …)`, `readArchivePages(zip, …)` and `MediaContentService`'s entry listing/decoding prefer the cached copy; a merged page plan passes the archive it actually references.
- Because a cached copy is randomly accessible, reaching page N no longer reads the rest of the archive, and reading consecutive pages no longer re-reads the whole file per page.
- `ArchiveCache.stats/clear` are serialized with cache writes and exposed through repository/ViewModel to “设置 → 压缩包阅读缓存”. Clearing affects only the App-private cache.

## Reader interaction

- The reader preserves an initialized LazyListState including pixel offset. Explicit jumps establish a fresh completion baseline and accept the list's actual clamped position near the end. Only active scrolling past the baseline or explicit confirmation can finish; image remeasurement alone cannot. Coil's initial Empty state keeps the same placeholder as Loading so a jump cannot be clamped against zero-height pages.

- `ui.components.ZoomMath` is the pure geometry of a viewer: clamping (a fitted dimension is pinned to the centre, an overflowing one may move only within its overflow), centroid-anchored zoom (the content under the fingers stays there), double-tap targets, fitted sizing, the resting transform per placement, and the visible-slice pan rule. Unit tested without a device.
- Two placements share it: `ZoomPlacement.FIT` (a photo rests with its whole frame visible) and `ZoomPlacement.WIDTH` (a comic page rests filling the window width, anchored at the top). The resting scale is therefore not always 1, and `ZoomState.isZoomed` compares against the resting scale of the current placement instead of a literal 1.
- Continuous comic reading puts every page in a scrolling list, so a page can be several times taller than the window. `panWithinWindow` clamps a pan to the part of the page that is currently on screen (`visibleSliceOf` derives it from `LazyListState.layoutInfo`), which is what stops a zoomed page from being dragged into empty space above or below the visible slice.
- `ui.components.Zoomable` wraps the geometry in Compose. Its gesture loop only takes over once a second finger is down or the content is already zoomed, so while at rest a vertical drag stays with the surrounding list and continuous reading keeps working; it consumes changes only when it actually transforms. The backdrop is black and the content box is sized by the placement, which is what makes clamping exact.
- `ZoomableInteractionInstrumentedTest` injects real gestures (`pinch`, `doubleClick`, `swipe`) on a device: fitted content must not pan, double tap must toggle, a pinch must zoom within bounds, a zoomed pan must stay inside the viewport, and — for a width-filled page — resting must not pan, double tap must toggle, and a zoomed pan must stay inside the visible slice. adb's `input` cannot produce a double tap or a pinch (each call starts a process), which is why these are Compose gesture tests.


## Media and cache budgets

- directory/archive pages are loaded lazily;
- comic pages use a 1440 × 6000 pixel decode budget;
- preload keeps five pages ahead and two behind, cancelling stale work;
- ZIP/CBZ decoded pages use a bounded 64 MiB bitmap cache;
- Coil may use up to 25% of heap and 768 MiB disposable disk cache;
- offline previews are 512 px JPEG files under `noBackupFilesDir`, capped at 256 MiB / 20,000 entries and trimmed to a 90% low-water mark.

No cache is portable truth.

## Safety invariants

`TrashRules` validates the confirmed trash event and all Edition source references, including nested and secondary paths. `PermanentDeletion` snapshots source sizes/timestamps in a versioned `purge-*.json` journal through `PortableDocumentWriter`. Explicit retry skips completed sources and resumes metadata cleanup; changed remaining sources are refused. Metadata is backed up before the first deletion. No startup task performs permanent deletion. Trash retention settings control review badges only.

Editor drafts survive configuration restoration; leaving a dirty Group/Series asks whether to discard instead of saving implicitly. Organizer preview requests have generations so results cannot repopulate a cleared or switched Library plan. Long-running UI jobs reject duplicate starts while a previous job is active or cancelling. Detail/editor surfaces share the snackbar host so failures remain visible.

- classification and logical relationships never move files;
- ordinary deletion writes portable logical trash;
- permanent deletion re-resolves the path and verifies current identity/size;
- accepted/classified Work metadata is committed before its Inbox decision; every decision is committed before the device index mirrors it, and no disposition deletes or moves media;
- Organizer copies and verifies before deleting a source;
- page reordering and Organizer operations have recovery journals;
- manual provenance wins over automatic metadata;
- system media access is read-only until the user chooses a copy import;
- forgetting a Library removes only local registration, index, and SAF grant;
- a failed provider query never becomes a cached empty directory;
- a Work known from `.gallery/` whose media is not on this device stays in the projection as a
  reachable item (`missing_media`), never as an error (`needs_repair`): a move, a partial copy, or
  another device must not read as damage, and must not delete a Work, Edition, Group, or Series;
- diagnostics strip content URIs and host paths before writing;
- `.nomedia` prevents Library copies from being duplicated into the system album.

## Known scaling boundary

`DocumentTreeStorage.openOutput` invalidates the written path and its parent listing on close, including failure, while retaining unrelated subtree caches. Its stream wrapper delegates bulk writes directly. Detail navigation builds one ID lookup for the current media snapshot instead of searching the full table for every contextual ID.

The scanner avoids repeated provider queries and byte reads, but `refreshFromDatabase()` still materializes all media rows. Inventory checkpoints and paging remain open implementation choices; sample scale and evidence are maintained only in [STATUS](STATUS.md).

## Engineering constraints retained from prior investigations

- Every service reading archives takes the repository-owned `ArchiveCache` as a required dependency. An optional or second cache can silently fall back to streaming and break supported archive layouts.
- Wrap SAF and `ZipFile` entry streams in `BufferedInputStream` for BitmapFactory bounds probes, which need rewind support; failed decoding logs the entry and reason through scrubbed diagnostics.
- One gesture layer owns tap, double tap, long press and pinch. Rebuilding a container must not erase a measurement produced by that container.
- `snapshotFlow` observes snapshot state reads, not plain captured parameters. Settled/initialized parameters affecting its result must also key the surrounding `LaunchedEffect`.
- Initialization has an exclusive expiring lease and commits identity last. The stable recovery slot is required on every read path, including Library identity inspection; provider-qualified names are not successful commits.
- Fake providers must reproduce naming conflicts and backing sizes/timestamps; output stream wrappers retain bulk writes and invalidate caches even after partial failure.
- Detail/reader code must retain catalog-only rows and guard empty video sources before creating a player. The current projection separates `missingMedia` from `needsRepair`; it does not serialize either into catalog truth.

## Generated Library instructions

`prepareLibraryAgentResources` packages LIBRARY_AGENT and PORTABLE_FORMAT into one UTF-8 resource for both the APK and desktop distribution, converting Markdown links to plain labels. `PortableLibraryManager.libraryGuide` adds Library identity; `schemaDocument` still embeds the v4 summary. Ordinary adoption retains user guides; conversion rewrites its backed-up guide. Desktop `export-guide` creates a separate file for explicit review and merging.

`library-tool` validates v4 identity, catalog and optional state/Inbox, then edits only existing Work fields through a JSON tree to preserve unknown extensions. Manual fields (including empty values) and stable source tags are protected. SHA-256 binds the catalog plan; all loaded documents are rechecked after an external backup and before commit. A host-local file lock supplements the required single-writer workflow; it is not a cross-device lock. Catalog commits use staging, forced file writes, atomic rename and the same stable recovery-slot naming as Android. Recovery is explicit; physical transaction JSON blocks editing. New entities, relationship changes, media operations and multi-document transactions are outside this tool's scope.

Shared validation requires a Group cover to be a member. Removing the previously selected cover member through `upsertGroup` clears that selection in the same action; an unrelated non-member cover is rejected.

## Verification commands

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
