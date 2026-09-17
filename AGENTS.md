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
2. portable Inbox decisions;
3. editable image/video Groups;
4. first-class Series editing;
5. non-destructive Edition comparison and merge;
6. real-device testing with the large E-drive Library;
7. interaction polish inspired by EhViewer and MT Manager.

The project is pre-release. Prefer the cleanest current design over compatibility layers for formats that were never stable. Schema v4 is current; v3 has one explicit backup-first conversion path only. Do not add support for older experiments unless the user explicitly asks.

Use Chinese commit messages.

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

The current Android runtime still consumes a `MediaItem` projection. `PortableMetadataStore` joins v4 entities into that projection; new portable behavior must update normalized entities first rather than reintroducing a serialized `items` array.

## Recognition and real Library facts

Discovery and classification stay separate. Unsupported user-visible entries remain reviewable in Inbox; known internals and sidecars do not become cards.

Useful signals include ComicInfo, stable downloader IDs, EhViewer markers, Pixiv IDs, episode/chapter naming, and parent folders. A folder named `JM` is not authoritative.

The read-only sample observed on 2026-09-17 was approximately 464.92 GiB and 76,517 files, including about 69,494 JPG, 2,984 WebP, 1,610 CBZ, 1,011 GIF, 796 PNG, 466 MP4, and 272 image-set directories with direct child videos. These values are evidence, never constants.

## Implemented foundation

- Library identity, provider-exclusive initialization lease, Schema protection, backups, and generated Library guide;
- SAF storage abstraction with projected directory queries and cache invalidation;
- byte-free inventory followed by a resumable local enrichment queue;
- Inbox confirmation, weak recognizers, provenance, and visible unsupported/ambiguous discoveries;
- image, directory/ZIP/CBZ reader, video player, system album import, search, metadata editing, and progress;
- logical trash, protected permanent deletion, and recoverable Organizer/page-order transactions;
- derived Series shelf and mixed image/video presentation;
- bounded device-private offline previews;
- normalized Schema v4 plus idempotent v3-to-v4 conversion after snapshots.

## Known gaps

- Inbox ignore/handled/manual-classification decisions are not yet portable.
- Derived mixed groups cannot yet be saved or manually edited as Group entities.
- Group membership, cover, role, and order have no UI.
- Series entities exist portably, but batch member editing and drag reordering are unfinished.
- Edition comparison, page-level hashes, virtual merge, and recoverable cleanup UI are unfinished.
- Initial inventory is still one atomic traversal; only enrichment is resumable.
- `refreshFromDatabase()` still materializes the full media table.
- Real E-drive scanning and mass video-preview behavior have not been validated with the latest build.
- A few decoder formats can display through Coil but cannot generate the BitmapFactory-based offline JPEG.

## Next implementation order

1. Add portable Inbox decisions with explicit evidence and confidence.
2. Add repository operations and UI for Group create/edit/member order/cover.
3. Offer saving a derived mixed folder as an explicit Group.
4. Add atomic Series member editing and reordering.
5. Add scoped Edition comparison and virtual merge preview.
6. Test the current build against the actual removable Library, then decide whether inventory checkpoints and database paging are required.
7. Polish density, selection, long-press actions, contextual tools, and back behavior.

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

- Removable-storage performance is dominated by provider/Binder query count, not only bytes.
- `DocumentFile` convenience calls can hide repeated queries.
- A provider may qualify a conflicting name with ` (1)`; a successful rename result does not prove the requested path was committed.
- Library identity must be committed last and initialization needs an expiring exclusive lease.
- A temporarily unreadable subtree is not proof that its indexed contents were deleted.
- Fake providers must reproduce real conflict semantics.
- Logs need URI/path scrubbing and remain device-private.
- Every intermediate state of a migration or physical transaction must be attachable or safely resumable.
- Tests have repeatedly found storage bugs faster than inspection alone.
