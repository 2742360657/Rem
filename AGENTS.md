# Rem development memory

This file is the short, living handoff for any agent continuing work on Rem. It applies to the whole repository. Keep it current when a change alters product intent, portable data, safety rules, or a lesson learned. Detailed history belongs in `docs/DEV_LOG.md`; user-facing behavior belongs in `README.md` and `docs/USER_GUIDE.md`.

## Mission and current priority

Rem is an Android, local-first library for large image, comic, and video collections on removable storage. Media stays usable outside Rem. The portable `.gallery/` metadata travels with the Library, while the Android SQLite database and caches are disposable projections.

The current product priority is the ingestion and logical-content model, especially:

- put every new or uncertain user file into a useful **Inbox / 未处理** workflow;
- let ordinary images, videos, photo sets, and mixed image-video sets live naturally in the **图片 / 视频** area;
- make comics readable as standalone works or as manually editable ordered series;
- support non-destructive grouping, adding content, comparing editions, and merging duplicate-heavy sets;
- retain small derived previews for useful offline/unmounted-Library browsing; these previews are allowed but are never originals or metadata truth;
- postpone broad interaction redesign until these semantics are stable, while taking interaction cues from EhViewer and MT Manager: dense browsing, predictable back behavior, long-press actions, selection mode, and operations close to the content.

## Non-negotiable invariants

- Never move, rename, rewrite, merge, or delete media as a side effect of scanning, recognition, grouping, or changing a view.
- Physical changes require a previewed, explicit user action and the existing recoverable transaction pattern.
- `.gallery/` is the portable truth. The local database must remain rebuildable and must not be the only home of a user decision.
- Portable paths are slash-separated and relative to the Library root. Never persist Android URIs, drive letters, or absolute host paths.
- Existing `manual` field provenance wins. Automated recognition, providers, and agents must merge field by field and must not modify a manually locked field.
- A detector emits a suggestion plus evidence/confidence; it does not silently turn a guessed folder convention into truth.
- Duplicate detection may propose actions, but it never deletes automatically. Preserve a reversible route for every merge or cleanup.
- Account credentials, cookies, and tokens never enter the Library, logs, fixtures, or Git.
- Keep SAF round-trips proportional to the tree. Prefer one projected directory query, reuse locators and unchanged scan results, and hash large content only on demand.
- Never write a newer unsupported portable Schema. Back up portable metadata before migration or a high-risk batch operation.
- Small preview/cover derivatives are permitted. Keep device-private offline previews bounded and clearable; if a cover is intentionally made portable under `.gallery/`, distinguish it from disposable thumbnails and never treat either as the original media.

## Product vocabulary to converge on

The current `MediaItem` combines a physical source with a logical work. That is adequate for the preview version but is the main blocker for mixed sets and safe merging. Future design should separate these concepts before adding more folder-name heuristics:

- **Asset/source**: physical file, directory, archive, or archive entry, with path, size, times, format, and fingerprints.
- **Work**: the logical thing a user names, tags, favorites, reads, or watches.
- **Group**: an ordered or unordered set of members shown together. Useful group types include photo set, mixed image-video set, and manual collection. Membership should support a role such as page, image, video, bonus, cover, or alternate.
- **Series**: an optional ordered sequence of works/chapters/episodes. A member may have no number; manual ordering must remain possible.
- **Edition/release**: an acquired version of the same logical work. Two editions may be kept separately, viewed as a unified de-duplicated result, or explicitly consolidated without destroying either source by default.

Do not use `Series` as a substitute for “same author” or “same folder.” A creator shelf or smart filtered view can provide continuous browsing without pretending that every work by one author is one numbered series. Do not equate `ImageSet` with “comic”: an ordered photo set and a comic can share a reader primitive while belonging to different product surfaces.

The likely primary surfaces are:

1. **相册**: camera/system-photo timeline.
2. **图片 / 视频**: individual media plus photo sets and mixed groups, browsable by folder or logical group.
3. **漫画 / 阅读**: comics as standalone works or ordered series, with progress and reading-oriented presentation.

This is a design direction, not an implemented contract. Settle the portable model and migration before changing navigation labels.

## Inbox and recognition policy

- Discovery and classification are separate. First record what exists; then attach zero or more classification suggestions.
- Recognized media, ambiguous directory structures, and unsupported-but-user-visible files should remain reviewable in Inbox. They must not disappear merely because Android cannot decode them yet.
- Known internal/sidecar entries should not become standalone Inbox cards: `.gallery/**`, `.nomedia`, `.ehviewer`, `ComicInfo.xml`, downloader thumbnails, transaction files, and other explicitly registered sidecars.
- Prefer small, testable recognizer adapters. Current useful inputs include ComicInfo, JM numeric IDs under a JM-like root, EhViewer GID folders/markers, Pixiv IDs, common chapter/episode names, and downloader-specific parent folders.
- Folder names are evidence, not authority. `JM` in the current sample is user-created; numeric directory formats are not universally trustworthy.
- If Android-side recognition becomes fragile or expensive, keep the candidate in Inbox and let a local agent classify it later from portable evidence.

## Real Library observations (read-only audit, 2026-09-17)

These numbers describe the attached `E:/Rem-lib` sample and must not be hard-coded:

- about 464.92 GiB and 76,517 non-metadata files;
- 69,494 JPG, 2,984 WebP, 1,610 CBZ, 1,011 GIF, 796 PNG, and 466 MP4 files;
- 272 indexed image-set directories also contain videos, totaling 320 child videos;
- `downloads/` currently includes 1,566 CBZ files from a downloader hierarchy;
- `JM/` contains numeric directories plus numeric ZIP files; `eh/` contains GID-title directories, only some of which carry `.ehviewer` markers;
- the current portable catalog has 1,383 valid-path items (931 image sets and 452 videos), all in `works`, assigned to 8 manually locked series.

The sample proves that the Library contains app-generated metadata, manually arranged folders, downloader output, archives, sidecars, image-only works, video-only works, and mixed image-video works. Preserve that heterogeneity rather than forcing a new physical layout.

## Known gaps in the current implementation

- A mixed leaf directory is emitted as one `IMAGE_SET` plus independent video items. The shared parent/series is not an explicit logical relationship.
- `IMAGE_SET` currently implies `WORKS`, while directories below `Images/` are deliberately prevented from becoming image sets. This blocks photo-set grouping in 图片 / 视频.
- `SeriesRef` is embedded in each item. There is no portable series membership document, no explicit group membership, and no edition relationship.
- Unknown extensions are ignored instead of becoming reviewable discovery records. Sidecars and unsupported user files therefore need separate handling.
- Automatic hashes stop above 64 MiB, and directory fingerprints describe structure rather than byte-identical pages. Current duplicate detection cannot merge two image-set editions page by page.
- Inbox acceptance is represented indirectly by the presence of portable item metadata. Any new review-state design must remain portable and survive index rebuilds.
- The current Works screen is a flat item grid with facet filtering. Series is a sort/filter property, not a first-class series presentation.

## Implemented foundation

- New recognized media stays exclusively in Inbox until the user edits it or explicitly accepts the suggestion.
- Inbox supports single-item and multi-select acceptance. Acceptance persists automatic provenance; only changed fields become `manual`.
- ComicInfo and filename/path recognizers now retain field provenance through merge.
- The sample's `eh/` alias, numeric archives below a JM-like root, and numbered CBZ files below a series folder are recognized as Inbox suggestions.

## Preferred implementation path

Do not start with a broad UI rewrite.

1. **Freeze fixtures and decisions**: add anonymized/minimal test trees for mixed sets, nested works, downloader CBZ, JM archives, EhViewer aliases, unsupported files, and two overlapping editions. Write the target portable model and migration rules first.
2. **Separate discovery from classification**: introduce discovered-entry records, explicit ignore/sidecar rules, suggestion evidence/confidence, and a durable review state. Preserve current scan performance characteristics.
3. **Add portable logical relationships**: evolve the Schema additively to first-class works/groups/series/editions or an equivalent normalized design. Keep existing v3 item IDs and manual fields stable during migration.
4. **Build 图片 / 视频 grouping**: show folders and logical groups; a mixed group opens once and exposes its images and videos together. Grouping must not require moving files.
5. **Build comic series presentation**: series shelf -> ordered entries -> work detail -> reader. Allow optional volume/chapter/episode values plus drag/manual order.
6. **Add merge and update flows**: compare two editions, calculate hashes only for the selected scope, classify exact duplicates versus unique additions, preview a virtual merge, and offer physical cleanup only as a separate recoverable transaction.
7. **Polish interaction**: tighten density, selection, long-press menus, contextual tools, and back-stack behavior using real-device sessions. Do not let navigation work mask missing data semantics.

Offline preview work should accompany the model/UI phase: define a strict pixel/byte budget, device-private retention and purge behavior, and whether an explicitly selected cover is also copied into portable `.gallery` metadata. Unplugging a Library must not make its catalog unintelligible, but cached previews must remain disposable.

Each phase needs unit tests for inference and migration plus a real SAF/device check when storage-provider behavior matters.

## Development workflow

Before changing code:

1. Read this file, `README.md`, `docs/ARCHITECTURE.md`, the relevant section of `Gallery_Project_Guide.md`, and the latest `docs/DEV_LOG.md` entry.
2. Run `git status --short`, inspect recent commits, and preserve unrelated user changes.
3. State one bounded goal and the portable-data impact. If a Schema change is involved, specify compatibility, backup, rollback, and rebuild behavior first.

Before handing off:

1. Run focused tests, then the proportionate regression set. The usual baseline is:

   ```powershell
   .\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
   ```

2. Inspect `git diff`; do not hide warnings or silently regenerate user data.
3. Update `docs/DEV_LOG.md` with symptom, evidence, root cause, change, verification, and remaining risk. Update this file only when the durable guidance changed.
4. Report separately what was implemented, what was only inferred, and what still needs a device or real Library check.

## Lessons already paid for

- Removable-storage performance is dominated by provider/Binder query count, not just bytes read.
- `DocumentFile` convenience calls can hide repeated queries; provider behavior must be tested through `ContentResolver` semantics.
- A provider may qualify a conflicting name with ` (1)` instead of overwriting. A successful rename return value is not proof that the requested path was committed.
- Library initialization needs a provider-exclusive, expiring lease and must commit identity last.
- A transient unreadable directory is not proof that its indexed contents were deleted.
- Fake providers and fixtures must reproduce real conflict semantics or they can validate the wrong behavior.
- Diagnostic text and exception summaries both require path/URI scrubbing; logs stay device-local because storage failure is itself a diagnostic scenario.
- Before reorganizing metadata or files, verify that every intermediate state can still be attached, scanned, and recovered.
- Tests have repeatedly exposed design bugs faster than inspection alone. Add the failing case before fixing a storage or migration defect.
