# Logical folder model

Folders organize the shared library without changing local document-provider URIs,
CLI paths, server document IDs, or saved bytes. Each folder contains files and other
folders. Each entry has at most one parent. There is no configured depth or folder
count limit; practical resource limits still apply.

| Entity | Fields |
| --- | --- |
| Folder | `id`, `name`, nullable `parent_id`, `created_at`, `updated_at` |
| Existing document | Existing fields plus nullable `folder_id` |

Null places an entry at the top level, a virtual container with no reserved ID or
name. IDs remain stable on rename/move. Paths are derived from parent links rather
than used as identities. Python and Android have immutable `Folder` models;
Android remote files and CLI `Document` metadata carry the nullable folder ID,
including compatibility with older servers that omit it.

Folder names follow filename rules: trimmed, NFC-normalized, 1–255 Unicode
characters, without slashes, ASCII control characters, `.` or `..`. Names must be
unique among sibling folders, ignoring case. The same name can appear under
different parents. Existing duplicate file names remain valid; IDs identify files.

## SQLite storage and integrity

`folders.parent_id` is a nullable foreign key to `folders.id`.
`documents.folder_id` also references `folders.id`. Both restrict deletion. Indexes
cover parent lookup and file membership. A stored case-folded `name_key` and unique
`(coalesce(parent_id, ''), name_key)` index enforce sibling folder-name uniqueness
for normalized writes. Database triggers reject cycles and changes to folder IDs;
foreign keys reject orphaned folders and files. Write transactions serialize
concurrent moves, including attempts to move two folders into each other.

Deletion requires an empty folder and never deletes or relocates contents
automatically. Retrying deletion of an absent folder succeeds. Startup adds the
table, indexes, triggers, and nullable document column in a serialized transaction.
Existing files start at the top level; bytes, hashes, revisions, settings, and
upload receipts are preserved. These additive changes retain `user_version=1`, so
older server releases can still open the database during deployment rollback.
SQLite backups include the folder table and links.

Moving a file changes only its current folder reference and creates no content
revision. New files can provide an initial `folder_id` in their content-save request;
the file, contents, folder link, and retry receipt are committed atomically. A
missing folder rejects the entire creation. The receipt fingerprint includes a
non-null initial folder, so a request ID cannot be reused with a different folder.
Existing-file content saves, merges, file renames, and history restoration preserve
membership. Historical file responses report the current folder. Upload receipts
retain their originally acknowledged metadata; replay never changes placement.
Fetch the current document for its latest folder.

Folder changes and file moves advance a persisted library change counter and notify
clients after commit. Unchanged/rejected operations do not notify. Document ETags
include a non-null folder ID, invalidating cached metadata on a move even when the
content revision stays unchanged.

## API foundation

Both `/api/v1` (bearer token) and `/editor/api` (session and CSRF on writes) provide:

| Request | Behavior |
| --- | --- |
| `GET /folders` | Flat `{"folders": [...]}` catalog with parent IDs, sorted by name ignoring case |
| `GET /folders?parent_id=ID` | Immediate child folders |
| `GET /folders?parent_id=` | Top-level folders |
| `GET /folders/{id}` | Folder metadata |
| `GET /folders/{id}/contents` | `{"folder": {...}, "folders": [...], "documents": [...]}` for immediate contents |
| `GET /library/root` | Top-level contents, with `"folder": null` |
| `GET /folders/{id}/path` | Flat ancestor array, from top to bottom |
| `PUT /folders/{id}` | Create/update `{"name": "Notes", "parent_id": "..."}`; null moves to top level |
| `DELETE /folders/{id}` | Delete an empty folder; `{"deleted": true}` |
| `PUT /documents/{id}/folder` | Move using `{"folder_id": "..."}` or `{"folder_id": null}` |
| `POST /library/move` | Move `{"document_ids": ["..."], "folder_id": "..."}` atomically; null means top level |
| `PUT /documents/{id}/name` | Rename using `{"name": "Notes.txt", "base_revision": "...", "request_id": "..."}` |

An omitted `parent_id` defaults to the top level on creation and preserves the
current parent on update. File moves require explicit `folder_id`, including null
to return to top level. Missing IDs return HTTP 404, invalid names/cycles HTTP 400,
and duplicate sibling names or nonempty deletion HTTP 409.

The existing document list continues to return all files for compatibility.
Document and hashtag-result metadata include `folder_id`. Catalogs, contents, and
paths use flat arrays; queries/path construction avoid language recursion and
stored full-path length limits. This provides the models, persistence, server
operations, and client transport used by the sidebar.

Group moves validate the entire selection before writing, preserve file revisions,
and emit one change notification. Missing files/folders reject the entire move.
File renames make a normal history revision with the exact saved bytes and current
folder. The required base revision rejects concurrent content saves with HTTP 409;
request receipts allow an acknowledged rename to be retried without undoing newer
edits. No schema change is needed for either operation.

## Full-page Files view

Choose the **Files** tab or **Browse files** on the recent-files screen to manage
the library in the main workspace. The sidebar tree navigates folders; clickable
breadcrumbs and **Up** navigate their parents. Contents show folders first, then
files, alphabetically. Search filters names in the current folder. The view and
current folder are remembered across reloads, and cached browsing works offline.

Use **New file**, **New folder**, **Folder actions**, and each row's **⋯** menu to
open, rename, move, or manage entries. Only empty folders can be deleted. File
checkboxes and **Select all** support moving several saved files together. Files
with pending saves must finish syncing before being moved or renamed. Unsaved new
files can be renamed or moved individually. Saved-file rename and folder changes
require a connection.

Opening a file returns to its editor tab. **Back to editor** returns to the active
tab or recent files. Browsing keeps the editor mounted, preserving open tabs,
unsaved text, undo/redo, cursor, and scroll position. Moving or renaming saved files
never saves an unsaved editor buffer. Live updates keep the browser current without
switching views. File deletion is not offered.

## Browser sidebar

The sidebar displays a collapsible tree, with folders before files and alphabetical
ordering in each container. Selecting a folder sets the destination for the new
file/folder buttons; **Top level** selects the virtual root. Folder **⋯** menus
provide new file/subfolder, rename, move, and delete actions. File menus provide
open, rename, and move actions. Unsaved new files also appear in their intended folder.
The move dialog browses immediate subfolders, with **Up one level**, **Top level**,
and **Move here** controls. Folder moves exclude the source and its descendants.
Deletion also checks local unsaved files before the server's nonempty check.

Filtering reveals matching entries and their ancestors without changing saved
expansion state. Rendering uses flat rows and iterative traversal rather than
recursive components. Folder selections and expansion are remembered in this
browser; IndexedDB stores the last folder catalog alongside cached files. A live
remote move updates cached membership even when the content revision is unchanged.

Folder creation/rename/move/deletion and saved-file moves require a connection.
Existing cached folders/files can be browsed offline; new files can be saved offline
in an existing folder, with their initial membership retained in the durable
outbox and applied atomically on reconnect. Pending or currently saving files must
finish synchronization before moving. Moving a saved file with unsaved text edits
preserves its buffer and history. The IndexedDB upgrade retains existing cached
documents and pending saves.

## Android Files view

The native **Files** tab and **Browse shared files and folders** entry-screen button
open the same logical library as the web editor. **Sync → Browse server files**
also opens this view. Breadcrumbs, **Folders**, and Android Back navigate the
hierarchy; **Editor** returns to editing. Current-folder search and alphabetical
folder/file ordering support large catalogs through recycled native rows. Folder
ancestry and illegal move destinations are computed iteratively, without a depth
limit.

Use **New folder**, **New file**, **Actions**, or each row's **More** menu to create,
rename, move, and delete empty folders. File checkboxes, **Select all**, and **Move**
use the atomic group-move API. File menus also provide downloads and revision
history. Folder changes, saved-file renames/moves, and creation of new shared files
require a connection. Catalog reads run independently of content synchronization;
pending uploads never block browsing. Live notifications refresh an open library,
including when no local files have been linked yet.

Opening a file reuses its existing linked local tab/file when available. Otherwise
Textpad downloads and verifies the bytes, creates an app-private saved copy with a
stable server-ID-based URI, and links it for normal saved-file synchronization.
These copies retain encoding, BOMs, line endings, backups, and offline editing.
**Save As** or **Download a copy** exports a copy through Android's document picker.
Shared folders organize the server library; phone-storage locations continue to be
managed through Android's file controls. Renaming a shared file updates its managed
copy's display name while independently chosen phone filenames retain their local
names.

The last folder and an atomic catalog snapshot are saved per server address.
Previously opened local copies and cached folders remain available offline. New
shared files are created atomically in their selected folder, then opened locally;
text edits upload only after a local save. A missing destination rejects creation.
Files with pending upload receipts or active saves must finish syncing before a
library move or rename. Dirty editor buffers can remain open during metadata
changes, with their text and undo history intact. The Files view and selection
survive activity recreation; unsaved text still has the existing process-lifetime
limitations.
