# Rem development memory

This file applies to the whole repository. It is the short operational handoff for a coding agent, not a duplicate product specification.

Read in this order:

1. `AGENTS.md` — constraints and current work;
2. `Gallery_Project_Guide.md` — product semantics and portable format;
3. `docs/ARCHITECTURE.md` — code that actually exists;
4. the latest entry in `docs/DEV_LOG.md`;
5. the relevant user-facing section in `docs/USER_GUIDE.md`.

## Mission and user preferences

Rem is an Android, local-first library for large image, comic, photo-set, and video collections on removable storage. Media remains ordinary user-owned files. Portable truth travels in `.gallery/`; Android databases, logs, enrichment queues, and previews are disposable projections.

Current priorities:

1. a clean portable logical model;
2. a complete Series/reader flow and predictable viewing gestures;
3. real-device testing with the large E-drive Library when the user chooses to run it;
4. interaction polish inspired by EhViewer and MT Manager.

The project is pre-release. Prefer the cleanest current design over compatibility layers for formats that were never stable. Schema v4 is current; v3 has one explicit backup-first conversion path only. Do not add support for older experiments unless the user explicitly asks.

Use Chinese commit messages.

Design philosophy (user-stated, load-bearing):

- The single source of truth is `.gallery/`; device databases, logs, enrichment queues, and previews are disposable. Every rule and default exists to serve the portable layer.
- Android does **basic, weak recognition** only. Its differentiators are **batch management and batch editing**: apply one decision across a whole selection, at a scale hand-driven work cannot match.
- A local Agent must stay able to organise a Library unaided, with fixed rules and its own fixed instruction document. Android must not grow a competing "smarter" recognizer.
- The load-bearing promise: **moving a Library, or opening it on another Android device, is fast and lossless.** Structure and human decisions appear from `.gallery/` alone, before any media is touched; missing or unreachable files must never destroy a Work, Edition, Group, Series membership, or decision.

## Non-negotiable safety

- Scanning, recognition, metadata edits, grouping, and view changes never move, rename, rewrite, merge, or delete media.
- Physical changes require an explicit preview, user confirmation, conflict checks, and the recoverable transaction pattern.
- `.gallery/` is portable truth. A user decision must not live only in SQLite.
- Portable paths use `/` and are relative to the Library root. Never persist Android URIs, drive letters, absolute paths, `.`, or `..`.
- `manual` field provenance wins. Automatic recognition, providers, and agents merge field by field.
- Detection produces a suggestion with evidence/confidence, never an irreversible classification.
- Duplicate or Edition comparison never deletes automatically.
- Credentials, cookies, and tokens never enter Library files, logs, fixtures, commits, or diagnostic exports.
- Keep SAF round-trips proportional to the tree; hash large content only for an explicit operation.
- Back up portable documents before Schema conversion or a high-risk batch operation.
- Never write an unknown newer Schema.
- Disposable previews are not originals and never enter portable truth.
- Preserve unrelated user changes. Do not use destructive Git commands.

## Current portable model

Schema v4 normalizes `.gallery/items/catalog.json`:

- **Asset** — physical file, directory, archive, or imported source;
- **Work** — user-facing logical content and editable metadata;
- **Edition** — one acquired version of a Work, referencing ordered Assets;
- **Group** — Works browsed together, such as a photo set or mixed image/video set;
- **Series** — ordered Works with optional sort, season/episode, or volume/chapter positions.

Relationships have one owner:

```text
Edition -> Work, Asset
Group   -> Work
Series  -> Work
State   -> Work
```

Do not use Series for “same author”, Group for edition identity, or path layout as permanent classification. A creator shelf can be a query without creating a relationship.

`.gallery/state/inbox.json` holds the user's Inbox decisions (`accepted`, `classified`, `ignored`, `handled`) keyed by Library-relative path plus `work_id` when the target is a Work. It is portable truth: deleting the device database must not bring ignored content back, and an old client that ignores the document still reads `catalog.json` and `state.json` safely.

The current Android runtime still consumes a `MediaItem` projection. `PortableMetadataStore` joins v4 entities into that projection and `PortableInboxStore` joins Inbox decisions; new portable behavior must update normalized entities first rather than reintroducing a serialized `items` array.

## Recognition and real Library facts

Discovery and classification stay separate. Unsupported user-visible entries remain reviewable in Inbox; known internals and sidecars do not become cards.

Useful signals include ComicInfo, stable downloader IDs, EhViewer markers, Pixiv IDs, episode/chapter naming, and parent folders. A folder named `JM` is not authoritative.

The read-only sample observed on 2026-09-17 was approximately 464.92 GiB and 76,517 files, including about 69,494 JPG, 2,984 WebP, 1,610 CBZ, 1,011 GIF, 796 PNG, 466 MP4, and 272 image-set directories with direct child videos. These values are evidence, never constants.

### Locating the real test Library

The removable SSD is mounted on Linux under `/run/media/<user>/<volume-label>/`; the label and user name are **not** stable, so never treat a path as fixed:

- current mount point: `/run/media/susnowy/闪迪-2T/` (volume label `闪迪-2T`);
- current Library root inside it: `/run/media/susnowy/闪迪-2T/Rem-lib` (`.gallery/`, `Comics/`, `Works/`);
- find it after a re-plug: `lsblk -f` or `ls /run/media/$USER/`, then look for `<mount>/Rem-lib/.gallery/library.json`.

On Android there is no path at all: the Library is always whatever directory the user grants through SAF, so scripts and docs must talk about the Library root, never a host path. Before any batch operation against the real Library, copy `.gallery/` off the drive (the drive is the only copy).

A second, always-available test target is the phone's own storage (`/storage/emulated/0/<user folder>/`), which can hold a small curated Library for repeatable checks while ADB stays connected.

## Implemented foundation

- Library identity, provider-exclusive initialization lease, Schema protection, backups, and generated Library guide;
- SAF storage abstraction with projected directory queries and cache invalidation;
- byte-free inventory followed by a resumable local enrichment queue;
- Inbox confirmation, weak recognizers, provenance, and visible unsupported/ambiguous discoveries;
- image, directory/ZIP/CBZ reader, video player, system album import, search, metadata editing, and progress;
- logical trash, protected permanent deletion, and recoverable Organizer/page-order transactions;
- derived Series shelf and mixed image/video presentation;
- bounded device-private offline previews;
- normalized Schema v4 plus idempotent v3-to-v4 conversion after snapshots;
- portable Inbox decisions in `.gallery/state/inbox.json` (accept, classify, ignore, handle, undo), mirrored into a disposable device index;
- editable Groups: explicit save of a derived mixed folder with a stable id, plus create/rename/member/order/cover/delete operations written through `catalog.json` and projected into a disposable `groups` table (database v8);
- batch Series editing: atomic `upsertSeries`/`deleteSeries`, rename, batch add/remove, reorder (`sort_index`) and numbering reset, with `field_sources.series = manual` stamped on every touched Work so recognition cannot re-assign it; projected into a disposable `series` table (database v9);
- card context actions for "add to Group", "add to Series" and Edition comparison, backed by the pure `MembershipRules` (order preserved, duplicates dropped, Works in another Series reported instead of moved);
- `SelectableMediaGrid` gives the image/video and works lists the same selection mode and batch actions (add to Group/Series, favourite, append metadata, trash), with `BatchMetadataDialog` shared instead of duplicated.
- recoverable portable-document commits: every previous revision uses a stable `.<name>.rem-backup` slot that the next read restores after a process stop, including Library identity inspection;
- repository-wide serialization for catalog/state/Inbox read-modify-write operations, with scanning reloading portable truth after the long inventory before projecting it into SQLite;
- an App-private `ArchiveCache` settings entry with size/clear, plus protection that keeps the archive currently being opened from evicting itself when it exceeds the nominal 512 MiB budget.

## State invariants worth stating before writing UI

- A UI action is planned against a snapshot. On commit, re-read the row and merge **only the fields the action changed**; provenance is recomputed from the row that is on disk, so an older snapshot can never delete a `manual` lock that appeared meanwhile.
- `.gallery/` is the complete truth. Rebuilding the device index replaces the projection (clearing entries the document no longer has) instead of only upserting into it, in one transaction.
- Identity is the stable portable id. Titles are for display and sorting only: never match, merge, project, edit or navigate by title.
- Async progress writes carry a stamp chosen **before** the task is queued, and the repository drops a write older than the stored one. A successful save is observable through a revision flow, so the current screen refreshes without being re-entered.
- "Opened" and "finished" are facts separate from "which page is showing": page 0 is a real position, restoring the last page is not completion, and re-reading a finished chapter starts at its first page.
- One gesture layer owns tap, double tap, long press and pinch. Rebuilding a container must not erase a measurement that container produced.
- Auto-scroll changes the logical order, not only pixels: the distance the list actually consumed feeds the same arithmetic as a finger drag, and the finger position used for the edge speed moves only when the finger moves.
- Evidence is graded: verified by test/emulator, inferred from code, or still needing a real device. Documentation may only claim the first kind.
- A portable entity that a user action creates has to reach the device projection in the same action. Only mirroring it after a scan leaves the UI describing a series, group or state that the catalog already owns.
- `snapshotFlow` re-runs only when snapshot state it read changes. A value that arrives as a parameter (a settled flag, an initialised flag) must also be a key of the surrounding `LaunchedEffect`, or the flow keeps reporting the value it started with.
- A state that drives a destructive or irreversible outcome (permanent deletion, physical move) must not be reachable from a stale screen: clear it when its owner changes, and make the UI text match what the code actually does.

## Known gaps

- 2026-09-18 readiness pass: startup trash auto-purge removed. Retention is a review reminder only; current/all-Library trash is explicit. Permanent deletion validates the confirmed row and portable state, refuses shared/nested/multi-source deletion, backs up metadata, and journals per-source completion. Explicit retry resumes remaining work; it never restores deleted bytes or deletes a replacement at an already completed path. A partially deleted directory with changed size is refused for manual review.
- Group/Series drafts now survive configuration restoration and back asks to discard rather than saving silently. Detail/editor error messages are visible. Organizer preview generations prevent late results crossing Library/template changes; long UI jobs reject duplicate starts until cancellation completes.
- Drag geometry correction: the new layout position already includes completed swaps, so visual translation is the unconsumed remainder, not cumulative travel. Pointer input must not be keyed by the mutable index. Keep fixed row geometry free of extra external spacing. The former cumulative-offset unit assertions were wrong; see the current DEV_LOG entry. A complete Compose pointer stream now verifies multi-row dragging and actual edge scrolling on API 36; sustained real-finger behavior still needs a phone.
- Readiness-pass verification: 279 unit tests and 25 API 36 emulator instrumented tests passed; lint has 0 errors, debug and test APK builds pass. The full removable Library was not accessed.
- SAF output close invalidates the affected cached entry and parent listing even after partial write failure. Bulk writes must remain bulk when wrapping streams. The test Provider reports real backing-file sizes/timestamps and removes fixture bytes on reset/delete.

- 2026-09-18 readiness pass: startup trash auto-purge removed. Retention is a review reminder only; current/all-Library trash is explicit. Permanent deletion validates the confirmed row and portable state, refuses shared/nested/multi-source deletion, backs up metadata, and journals per-source completion. Explicit retry resumes remaining work; it never restores deleted bytes or deletes a replacement at an already completed path. A partially deleted directory with changed size is refused for manual review.
- Group/Series drafts now survive configuration restoration and back asks to discard rather than saving silently. Detail/editor error messages are visible. Organizer preview generations prevent late results crossing Library/template changes; long UI jobs reject duplicate starts until cancellation completes.
- Drag geometry correction: the new layout position already includes completed swaps, so visual translation is the unconsumed remainder, not cumulative travel. Pointer input must not be keyed by the mutable index. Keep fixed row geometry free of extra external spacing. The former cumulative-offset unit assertions were wrong; see the current DEV_LOG entry.
- SAF output close invalidates the affected cached entry and parent listing even after partial write failure. Bulk writes must remain bulk when wrapping streams. The test Provider reports real backing-file sizes/timestamps and removes fixture bytes on reset/delete.

- Edition comparison and virtual merge are wired end to end (quick/deep comparison, report, page-plan Edition, `.gallery/imports/` evidence, reader follows the plan) and were walked through on a device on 2026-09-17; pixel-level (re-encode) matching is deliberately not implemented.
- Initial inventory is still one atomic traversal; only enrichment is resumable.
- `refreshFromDatabase()` still materializes the full media table.
- Real E-drive scanning and mass video-preview behavior have not been validated with the latest build.
- A few decoder formats can display through Coil but cannot generate the BitmapFactory-based offline JPEG.
- Archive cache size/clear was walked through on the Android 16 emulator on 2026-09-18 (16-byte private fixture: 1 file/16 B -> clear -> 0/0 with snackbar). The real-device feel of long page-by-page archive reading is still unverified.
- Device walkthroughs done on 2026-09-17 (Xiaomi 23127PN0CC, curated Library on phone storage): Inbox accept, derived-Group save, Group reorder, Series reorder with `manual` stamping, card actions, batch add-to-Series/add-to-Group, deep comparison, virtual merge, and reading the merged plan. Those runs found and fixed the missing `@Serializable` on projection types, the empty comparison candidate list and the unreachable merge button.
- Also verified on a device (2026-09-17): Series drag handle, "clear numbering" (positions cleared, manual order kept) and rename.
- Also verified on a device (2026-09-17, second pass): the Group editor's drag / set-cover / remove-member / rename (all in one save) and Series "remove member".
- Verified on the emulator (Android 16 AVD, 2026-09-18): archive page decoding after the `ArchiveCache` ownership fix; the zoom container through injected gestures (`pinch`, `doubleClick`, `swipe`), including the width-filled comic placement; and the full Series flow on a 5-chapter fixture — chapter list with per-chapter progress, continue entry, automatic advance into the next chapter, end-of-chapter footer when advance is off, and a directory ImageSet chapter that used to fall out of its series.
- Enrichment now commits by merging into the row re-read at commit time (`MediaItem.mergeEnrichment`) with media I/O outside the portable write mutex, and `updateMedia` merges the other way (`mergeEdit`), so a finished batch can no longer roll the projection back over an edit made while it ran. Locked by `EnrichmentMergeTest` and the batch re-read case in `GalleryDatabaseEnrichmentInstrumentedTest`.
- Still unverified on a device: drag auto-scroll on very long lists (implemented and unit-tested for the geometry, felt only on the emulator), and the full ~465 GiB E-drive inventory, which is the one step that needs the drive attached to the phone (the user has decided not to run it on the phone for now).

## Next implementation order

1. When the user is ready, test the current build against the actual removable Library (device + real E-drive), then decide whether inventory checkpoints and database paging are required. This is the only remaining item that needs the drive attached to the phone.
2. Continue interaction polish: density options, back behaviour, and reading-readiness details surfaced by real use (for example knowing a page's aspect ratio before it loads, so a comic page does not resize once decoded).
3. Validate the reader and reader-adjacent flows on the real device with the removable Library attached.

Do not start a broad UI rewrite before portable semantics are usable.

## Working procedure

Before editing:

1. Run `git status --short`, inspect recent commits, and identify unrelated changes.
2. State one bounded goal and whether it changes portable data.
3. For a Schema change, define backup, failure, retry, rollback, and newer-version refusal before writing code.
4. Add or update a failing test for storage, migration, or data-loss defects.

Before handoff:

1. Run focused tests, then normally. This repository is developed on both Windows and Linux, so
   detect the current host instead of assuming one platform:

   Windows (PowerShell):

   ```powershell
   .\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
   ```

   Linux/macOS (the wrapper keeps its executable bit; `bash gradlew` is equivalent):

   ```bash
   ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
   ```

2. Run device tests when SAF, provider behavior, migration, decoding, or navigation changed.
3. Inspect `git diff --check`, staged scope, and final status.
4. Update only the documents whose responsibility changed:
   - product/format decision → `Gallery_Project_Guide.md`;
   - implementation architecture → `docs/ARCHITECTURE.md`;
   - current user behavior → `docs/USER_GUIDE.md`;
   - evidence and remaining risk → `docs/DEV_LOG.md`;
   - user-visible release change → `CHANGELOG.md`;
   - durable agent rule/current status → this file.
5. Report implementation, inference, and unverified real-device behavior separately.

## Lessons already paid for

- Emulator UI walkthroughs must swipe a long distance slowly: a short or fast synthetic swipe is
  often not recognised as a scroll. `uiautomator dump` only exports what is currently on screen, so a
  "missing" element usually means "not scrolled to" — enumerate screen by screen before concluding a
  rendering defect.
- `adb push` truncates the last code point of a non-ASCII destination directory name
  (`218.花柒Hana` becomes `218.花柒H`). Create the parent directory on the device first and push into
  it, or rename from inside the guest shell. The media files themselves are unaffected.
- A Catalog-only Work (present in `.gallery/`, no local media) must enter the device projection with
  `needsRepair = true` and `size = 0`. Dropping it silently deletes Work, Edition, Group and Series
  decisions from the UI even though the portable documents are intact.- Removable-storage performance is dominated by provider/Binder query count, not only bytes.
- `DocumentFile` convenience calls can hide repeated queries.
- A provider may qualify a conflicting name with ` (1)`; a successful rename result does not prove the requested path was committed.
- A unique temporary name is not enough for crash recovery: the old live revision needs a stable, discoverable recovery path, and every read path (especially `library.json` inspection) must use it.
- Library identity must be committed last and initialization needs an expiring exclusive lease.
- A temporarily unreadable subtree is not proof that its indexed contents were deleted.
- Fake providers must reproduce real conflict semantics.
- Logs need URI/path scrubbing and remain device-private.
- Every intermediate state of a migration or physical transaction must be attachable or safely resumable.
- Tests have repeatedly found storage bugs faster than inspection alone.
- The current phone and removable drive can reach roughly 360 MB/s for a large sequential video copy. Do not use the earlier 20 MB/s estimate as a hardware limit: full-Library work can still be dominated by SAF/provider round-trips, many small files, archive handling and hashing. Whole-payload operations such as deep duplicate hashing must therefore remain opt-in, scoped, cancellable and cached.
- Archive reading goes through `ArchiveCache` + `ZipFile` (central directory): `ZipInputStream` cannot seek and rejects STORED entries that carry an extended data descriptor, which is a layout real downloaders produce. Cache copies are keyed by (library, path, size, modified time), budgeted (512 MiB) and trimmed oldest-first; the file currently being opened must be passed as the protected entry so an oversized archive remains readable.
- The archive cache is a **required** constructor dependency of every reader service, and the repository is its only owner. An optional/second instance looks harmless but silently degrades to the streaming fallback — that is exactly how "a comic opens as 此页无法解码" shipped. When adding a service that reads archives, take `ArchiveCache` as a parameter instead of defaulting it.
- Streams handed to `BitmapFactory` must be markable: wrap `ZipFile.getInputStream` and SAF streams in `BufferedInputStream` (or read the entry into memory) because the bounds probe rewinds the stream. A page that cannot decode must log the entry and the reason, not just fail silently in the UI.
