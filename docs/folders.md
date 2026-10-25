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

An omitted `parent_id` defaults to the top level on creation and preserves the
current parent on update. File moves require explicit `folder_id`, including null
to return to top level. Missing IDs return HTTP 404, invalid names/cycles HTTP 400,
and duplicate sibling names or nonempty deletion HTTP 409.

The existing document list continues to return all files for compatibility.
Document and hashtag-result metadata include `folder_id`. Catalogs, contents, and
paths use flat arrays; queries/path construction avoid language recursion and
stored full-path length limits. This provides the models, persistence, server
operations, and client transport used by the sidebar.

## Browser sidebar

The sidebar displays a collapsible tree, with folders before files and alphabetical
ordering in each container. Selecting a folder sets the destination for the new
file/folder buttons; **Top level** selects the virtual root. Folder **⋯** menus
provide new file/subfolder, rename, move, and delete actions. File menus provide
open and move actions. Unsaved new files also appear in their intended folder.
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
