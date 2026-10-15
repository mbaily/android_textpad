# Agreed scope

## Android application, first

- Minimum Android 16 (API 36), dark theme by default, debug-signed APK for sideloading.
- Tabs with a dot for unsaved changes. Same file can have several tabs backed by one text/history buffer; selections and scrolling remain independent.
- New, Open, Save, Save As, recent files, read-only handling, and default Android file access through the Storage Access Framework. Opened local documents are edited in place.
- Save/Discard/Cancel on closing unsaved tabs. Normal Android navigation; no application Quit button and no guaranteed dialog when removing the task from Recents.
- No tab/session restoration, persistent unsaved drafts, or crash recovery after process death. Screen rotation and other configuration changes retain in-memory text; normal task switching retains it while the process survives.
- Manual save; autosave optional and off by default. Available intervals: 30 seconds; 1, 2, 3, 5, 8, 10, 15 minutes. Timers operate while the application is in the foreground.
- Undo/redo, find/replace, selection/clipboard, wrap and line-number toggles, customizable font/size, toolbar indentation. Syntax highlighting deferred.
- UTF-8 default; ASCII and UTF-16 LE/BE supported. Emoji and other Unicode text supported. Preserve BOMs and line endings, and preserve the exact bytes of unchanged supported files.
- Maximum file size 10 MiB; reject binary files, invalid encodings and unrepresentable saves. Ambiguous BOM-less encodings can be chosen explicitly when opening.
- Before overwriting, check for changes since opening/last save. Save failure never silently drops open edits; prior contents are backed up before overwriting. Backups can be opened/restored or explicitly deleted.

## Synchronization

- Two-way synchronization across devices; all editing and synchronization operates on saved local files. No draft upload or remote-only editing.
- Explicit participation flag for each local file on each device. Disabled files are never downloaded over or uploaded. Persist sync metadata without restoring tabs.
- Identical filenames and similar contents suggest an association. User confirms once; a permanent server document ID identifies future revisions, independent of filesystem paths.
- First association chooses the local saved contents or the server contents. Server browsing can download to a user-chosen local document.
- Sync on save, returning to the foreground, or manual request. Remain fully usable offline; expose pending and failure status.
- Last-write-wins according to server acceptance order, with displaced contents retained as backups/revisions. Repeated attempts for one interrupted upload use the same idempotency key.
- Never silently overwrite unsaved buffers. Defer remote replacement until edits have been saved or discarded.
- Local deletion does not delete the server copy. Retain backups/revisions until manually removed. Previous server revisions can be restored by creating a new current revision.

## Debian service, next

- Debian 13 on `git23069.hopto.org`; single user initially.
- SQLite stores document contents, metadata, revision history, and upload receipts.
- HTTPS with a server certificate signed using the existing CA under `/etc/ssl/nucbox3/nucbox3-ca` on the server. That is a server-side path; no CA private key belongs in the Android project.
- Client devices already have the root CA installed. The Android APK includes explicit trust configuration for that host and uses a separately configured shared API token.
- Initial client address uses port 8443 as a configurable development convention, pending actual service configuration.
- The API contract is documented separately. No server installation, remote changes, or certificate generation has been performed as part of the Android implementation.
