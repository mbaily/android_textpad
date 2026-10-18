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
- Sync on save, returning to the foreground, manual request, and authenticated WebSocket change notifications while open. Remain fully usable offline; expose pending and failure status.
- Android WorkManager persists periodic network-constrained background checks (15-minute interval, subject to Android scheduling) and offline retries with exponential backoff. Disable jobs when no files participate or no token is configured.
- Close Android/browser WebSockets when no longer visible, reconnect with backoff, and reconcile on reconnect to recover missed events. File contents continue to transfer over HTTPS; notifications carry only a change cursor. No Firebase dependency.
- Last-write-wins according to server acceptance order, with displaced contents retained as backups/revisions. Repeated attempts for one interrupted upload use the same idempotency key.
- Never silently overwrite unsaved buffers. Defer remote replacement until edits have been saved or discarded.
- The desktop browser editor can edit server documents directly. The saved-local-file requirement applies to Android clients.
- Local deletion does not delete the server copy. Retain backups/revisions until manually removed. Previous server revisions can be restored by creating a new current revision.

## Debian service and desktop browser editor

- Debian 13 on `git23069.hopto.org`; single user initially.
- SQLite stores document contents, metadata, revision history, and upload receipts.
- Server notifications are triggered after successful commits using a shared signal across server workers. No one-second SQLite polling; coalesce change hints so clients fetch the latest saved state. Worker exits and client reconnects trigger catch-up checks.
- HTTPS with a server certificate signed using the existing CA under `/etc/ssl/nucbox3/nucbox3-ca` on the server. That is a server-side path; no CA private key belongs in the Android project.
- Client devices already have the root CA installed. The Android APK includes explicit trust configuration for that host and uses a separately configured shared API token.
- The download site uses the existing Apache HTTPS host under `/android_textpad/`; the editor is at `/android_textpad/editor/` and the sync endpoint is `https://git23069.hopto.org/android_textpad`.
- Desktop/laptop browser UI, primarily Chrome, to browse, view, create, and edit server-side synced documents. It does not expose or browse Android device storage.
- Browser editing includes tabs, unsaved indicators, save, undo/redo, find/replace, indentation, Unicode, line numbers, wrap/font preferences, and revision viewing/restoration.
- Retain unsaved browser buffers when remote revisions arrive. Reload clean buffers, show changes elsewhere for dirty buffers, and retain displaced revisions when saving.
- Single-user browser sign-in uses the configured shared API token unless a separate password is explicitly selected.
- The API contract is documented separately. SQLite data and credentials remain outside the public deployment directory.

## Python filesystem CLI

- Linux, Windows, and macOS terminal client using Python 3.11+ with no runtime package dependencies.
- Explicitly add/remove local files, download/link existing server documents, inspect status/history, sync on demand, and edit files with a separate editor.
- Store mappings, exact acknowledged and pending contents, and retained recovery snapshots in a private SQLite workspace.
- Foreground terminal monitor with file stability checks; no systemd service. Observe saved local edits and periodically check for server changes.
- Hold suspicious blank/large-deletion saves, preserve exact bytes, and require explicit conflict resolution when both copies change.
- Use an opt-in atomic server revision precondition for CLI saves, while keeping existing Android/browser save behavior.
- Preserve retry receipts across interruptions; retain snapshots before replacements and do not propagate local deletion as server deletion.
