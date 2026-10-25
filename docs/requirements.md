# Agreed scope

## Android application, first

- Minimum Android 16 (API 36), dark theme by default, debug-signed APK for sideloading.
- Tabs with a dot for unsaved changes. Same file can have several tabs backed by one text/history buffer; selections and scrolling remain independent.
- New, Open, Save, Save As, recent files, read-only handling, and default Android file access through the Storage Access Framework. Opened local documents are edited in place.
- In Android and browser read-only mode, keep the cursor visible and movable and offer a separate single-line input bar. A 10 px web / 13 dp Android chevron strip collapses or expands the panel, remembers that preference, and preserves the live draft and indentation while collapsed. Retain an accessible Add one line label and tooltip without a visible text header or extra collapsed padding. Enable date, time, and dividing-line insertion while the input bar is available and expanded, targeting the draft cursor/selection and keeping input focus. Enable task-status toggling under the same conditions, applying the editor marker cycle to the draft line while preserving whitespace, list bullets, selection, and input focus. Keep the existing document protected from direct typing; apply draft text only when Add is pressed. If the cursor's line is empty or contains only spaces/tabs, fill that line without adding a newline; otherwise insert below its whole line. Preserve existing terminators and other lines, inherit indentation, and move the cursor to the result for repeated additions. On panel expansion, cursor-line changes, and after an addition, continue a recognized current-line task with an open `[ ] ` marker and the same list bullet and whitespace. Recognize open, completed, and cancelled task states (including lowercase). Refresh only empty or untouched suggested drafts; preserve typed drafts and allow suggestions to be cleared while editing. Infer indent-control steps from nearby nonblank lines, preserve tabs, and fall back to the configured size (four spaces in the browser). Reject multiline paste, protect existing text, and record each addition as one undoable edit. Keep drafts with live tabs. Respect files without write permission and the task view's existing prohibition on adding/deleting lines.
- Include a trash icon immediately before the line composer's two indent buttons on Android and web. Confirm before deleting the entire cursor line, including one adjacent separator. Keep read-only mode, the panel expanded, and the existing input draft after deletion; place the cursor at the next line's start, or the preceding line's end at EOF. Treat each deletion as one undoable edit and preserve other lines' exact terminators. Cancel must not modify the document. Invalidate confirmation if the document or target cursor changes. Disable deletion for empty documents, files without write permission, and task-filtered views.
- Save/Discard/Cancel on closing unsaved tabs. Normal Android navigation; no application Quit button and no guaranteed dialog when removing the task from Recents.
- No tab/session restoration, persistent unsaved drafts, or crash recovery after process death. Screen rotation and other configuration changes retain in-memory text; normal task switching retains it while the process survives.
- Manual save; autosave optional and off by default. Available intervals: 30 seconds; 1, 2, 3, 5, 8, 10, 15 minutes. Timers operate while the application is in the foreground.
- Undo/redo, find/replace, selection/clipboard, wrap and line-number toggles, customizable font/size, toolbar indentation. Syntax highlighting deferred.
- Android and browser todo buttons add `[ ]` to plain lines and cycle open `[ ]` → completed `[X]` → cancelled `[C]` → open `[ ]`, preserving indentation and list bullets. Recognize lowercase `[x]` and `[c]`. Bulk selections advance plain lines first, then open, completed, and cancelled tasks, skipping blank lines when nonblank lines are selected.
- Open-task views exclude completed/cancelled tasks and their notes unless needed as context for open descendants. Hashtag/errands **Hide [X] / [C]** excludes both closed states consistently across local, cached, and server searches.
- User-defined **Tag groups** alongside Errands, with no group-count or hashtag-count cap. Display groups alphabetically, ignoring case; support creating, renaming, editing hashtags, and deleting groups. Groups search for any of their hashtags using the existing line/file modes and closed-task filter. Persist groups locally, retain offline edits/deletions, and sync groups individually between Android and the browser without overwriting unrelated groups.
- UTF-8 default; ASCII and UTF-16 LE/BE supported. Emoji and other Unicode text supported. Preserve BOMs and line endings, and preserve the exact bytes of unchanged supported files.
- Maximum file size 10 MiB; reject binary files, invalid encodings and unrepresentable saves. Ambiguous BOM-less encodings can be chosen explicitly when opening.
- Before overwriting, check for changes since opening/last save. Save failure never silently drops open edits; prior contents are backed up before overwriting. Backups can be opened/restored or explicitly deleted.

## Synchronization

- Logical folders in the shared library contain files and other folders with no configured nesting limit. Use stable IDs and nullable parent/folder references for top-level entries. Preserve existing files and content history, reject cycles and missing parents, and reject deletion of nonempty folders. The web sidebar provides a collapsible, alphabetically ordered tree, folder/file creation, folder rename/move/delete, and file moves. Preserve unsaved buffers during moves and initial folder placement through offline file saves; keep folder mutations online, with cached browsing offline.
- A full-page web Files view provides breadcrumbs, current-folder search, alphabetically sorted folders/files, creation and action menus, and atomic moves of multiple selected saved files. Saved-file renames preserve exact bytes, use conditional revisions, and retain name changes in History. Switching between browsing and editing retains open tabs, unsaved text, undo history, cursor, and scroll position. Remember the browser view and folder across reloads; support cached browsing offline.
- Android provides a native full-page Files view for the same shared library, with breadcrumbs/folder navigation, search, folder CRUD, saved-file rename, and atomic multi-file moves. Reuse linked local files or prepare stable app-private local copies for editing, saving, backups, and sync. Retain the catalog and selected folder per server for offline browsing. Opening an unlinked shared file opts its local copy into synchronization; the existing per-file disable control remains available. Metadata changes preserve dirty open buffers, and configuration changes preserve browser selection and editing state. Creation and folder management require a connection; saved local edits remain usable offline.
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
- Merge compatible concurrent saved edits using their shared base; preserve formatting and recovery snapshots, and require explicit resolution for overlapping edits or incompatible metadata changes. Hold suspicious blank/large-deletion saves, including combined deletions from both sides.
- Use an opt-in atomic server revision precondition for CLI saves, while keeping existing Android/browser save behavior.
- Preserve retry receipts across interruptions; retain snapshots before replacements and do not propagate local deletion as server deletion.

## Vim plugin

- Compatible with Vim 9.0+, with a toggle command and a user-assignable mapping; no default keystroke.
- Match the uncompleted-task, ancestor, note, Markdown-heading, and divider-heading rules of the app/browser views.
- Use an editable filtered projection while retaining the full source buffer; write the complete file through Vim's normal save path.
- Keep line structure changes in the full view, preserve edits on mapping conflicts, and return to the corresponding real cursor line when disabling the view.
