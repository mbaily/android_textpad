# Textpad for Android

A native, dark-by-default text editor for **Android 16 / API 36 and later**. The Debian server hosts APK downloads, the sync API, and a desktop browser editor for synced files.

<img src="docs/screenshots/home.png" width="260" alt="Textpad recent files on Android 16"> <img src="docs/screenshots/editor.png" width="260" alt="Textpad editing a Unicode text file">

## Download

[Download page](https://git23069.hopto.org:9443/android_textpad/) · [Latest APK](https://git23069.hopto.org:9443/android_textpad/textpad-latest.apk) · [Desktop editor](https://git23069.hopto.org:9443/android_textpad/editor/)

[Python CLI client](cli/README.md) · [Download CLI](https://git23069.hopto.org:9443/android_textpad/editor/static/textpad-sync.pyz)

The Python 3.11+ CLI syncs explicitly selected local files on Linux, Windows, and macOS, with SQLite mappings and recovery snapshots. Edit with any text editor and use `textpad-sync sync` or foreground `textpad-sync watch`. Watch uses WebSocket notifications for live remote changes, with periodic reconciliation as a fallback. Both commands merge compatible concurrent edits using a three-way merge and hold overlapping conflicts or suspicious blank/truncated saves for explicit resolution. `textpad-sync errands` searches local/server files with the shared errands hashtags; it defaults to line matching with completed/cancelled tasks hidden. `errands --files` switches to file matching, and `errands tags` views or edits the shared hashtag list. See the [setup, commands, and recovery guide](cli/README.md).

The [Vim 9 plugin](vim/README.md) provides an assignable toggle for the same uncompleted-tasks view. It displays open tasks with their headings/context and lets you edit visible lines; `:write` saves the complete source file, including hidden text. [Download the Vim plugin](https://git23069.hopto.org:9443/android_textpad/editor/static/textpad-tasks-vim.zip).

The site uses the server's existing HTTPS certificate and is served from `/opt/android_textpad/deployment/`. Publish an updated APK with `./scripts/deploy-downloads.sh` after building it. See [server deployment instructions](server/README.md).

## Build and install

Open this directory in Android Studio, or use JDK 25 (LTS) and Android SDK platform 37.2 with build tools 37.0.0:

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-37.2' 'build-tools;37.0.0'
./scripts/build.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build script also finds the isolated toolchain installed for this workspace at `~/.cache/android-textpad/`. Both modules use the JDK 25 compiler toolchain and retain Java 17 source/bytecode compatibility. The Gradle wrapper pins Gradle 9.8.2 and Android Gradle Plugin 9.4.1. Compilation uses Android SDK 37.2 for OkHttp 5.5.0; the minimum and target SDK remain API 36 (Android 16). The debug APK is signed automatically. Keep the same debug keystore when building subsequent updates.

## Editor

- New, Open, Save, Save As, Save to existing file, recent files, read-only documents, and Android's standard file picker. The original selected file is edited in place.
- The entry screen shows up to 30 recent files for one-tap reopening. Android uses its saved file history; the browser remembers opened/saved files on this device and fills the list with recently updated synced files. File lists also work with the browser's existing offline cache.
- Multiple tabs, including multiple views of one file. Text and undo history are shared; selections and scrolling belong to each tab. Long-press a tab for its file location and details.
- Unsaved dots and Save/Discard/Cancel when closing a modified tab. No Quit action, persistent tabs, drafts, or crash recovery. Rotation preserves the live editing session. Process termination loses unsaved edits.
- Undo/redo, literal find/replace with case matching, replace all, selection/clipboard, indentation, word wrap, line-number toggles, font families and sizes.
- Android and browser read-only mode keeps a visible, repositionable cursor and provides a separate **Add one line** bar. Tap its thin chevron strip (10 px on the web, 13 dp on Android) to collapse or expand the panel; the choice is remembered, and collapsing keeps the current draft and indentation. Enter text and press **Add** or Enter to insert a whole line below the cursor's line, then continue from the new line. The bar copies that line's leading whitespace; its indent controls infer a step from nearby indentation changes, preserving tabs and falling back to four spaces (Android uses the configured indent size). Existing lines remain protected. Each addition is one undo step after leaving read-only mode. Input-bar drafts stay with their live tab, including Android rotation, but are not restored after process termination or browser reload. Files without write permission and the task-filtered view do not allow additions; use Save As or switch to the full view first.
- The todo button adds `[ ]` to plain text, then cycles `[ ]` (open) → `[X]` (completed) → `[C]` (cancelled) → `[ ]`. Android and browser editors accept lowercase `[x]`/`[c]`, indentation, and `- `, `* `, or `+ ` list bullets. For a mixed selection, each click advances the earliest state present: plain lines first, then open, completed, and cancelled tasks; blank lines are skipped when other lines are selected.
- Uncompleted tasks view keeps open tasks, their context and indented notes. Completed and cancelled tasks stay hidden unless needed as ancestors of an open subtask; visible closed tasks and other context are dimmed. It also keeps heading blocks made of a blank line, a nonblank title, and a line containing only dashes (with optional surrounding spaces or tabs) when their section contains an open task. A title at the start of the file does not need a preceding blank line. Tasks do not need to be indented. A section ends at the next divider heading or Markdown heading; completed, cancelled, and empty sections stay hidden. Task membership remains stable while editing until the view is toggled off/on.
- **Hashtags & Tag groups → Tag groups** lets you create any number of named hashtag collections alongside the built-in **Errands** group. The selector lists groups alphabetically, ignoring case. Use **New tag group**, **Rename group**, **Edit**, and **Delete group** to manage them; each group can contain any number of distinct hashtags. Group names must be unique, ignoring case, and contain 1–100 characters. The same hashtag can belong to several groups. Deleting a group keeps your documents and their hashtags. Android and the browser save groups locally, retain offline edits/deletions for retry, and sync each group independently through the server. Android also checks hashtag settings when starting or manually syncing without linked files.
- Hashtag and tag group search matches **any** hashtag in the selected group and defaults to **Hide [X] / [C]** to exclude completed and cancelled task lines, including list bullets, in both line and file results. Turn it off to include closed tasks. This applies to open buffers, cached files, and server searches; hashtag discovery still includes tags from hidden tasks. Android recycles hashtag chips and result rows so large groups do not create every view when opening the tab. Existing Errands hashtags remain available in the group selector and through the CLI's `errands` commands.
- The web sidebar shows a collapsible folder tree, with folders first and files alphabetically within each folder. Select a folder to create files there, or select **Top level** for files outside folders. The folder button creates a folder in the selected location. Each folder's **⋯** menu offers **New file**, **New subfolder**, **Rename folder**, **Move folder**, and **Delete folder**; file menus offer **Open file** and **Move to folder…**. The destination picker excludes a folder and its descendants, and nonempty folders must have their contents moved before deletion. Search reveals matching entries inside collapsed folders, and expansion is remembered. Folder management requires a connection; cached trees and files remain usable offline, including saving new files into existing folders for later synchronization. See the [folder model, migration, and API contract](docs/folders.md).
- The web **Files** tab opens a full-page folder browser with breadcrumbs, search, folder/file action menus, and selection for moving several saved files together. Open files return to editor tabs with unsaved edits, undo history, and scroll position preserved. Create files/folders, rename or move entries, and delete empty folders; saved-file renames preserve exact bytes and create a History entry. The current view and folder are remembered across reloads.
- Android's **Files** tab brings the shared library into the native app: browse nested folders with breadcrumbs and the folder navigator, search the current folder, create/rename/move files and folders, delete empty folders, and move several selected files atomically. Opening a shared file reuses its linked local file or prepares a private local copy for editing and sync. Opened copies and the cached catalog work offline; saved edits sync on reconnection. Folder management and new shared files require a connection. **Editor** returns to the current tab with text, undo, selection, and scrolling retained. **Save As** or **Download a copy** exports to phone storage. See [Android folder browsing](docs/folders.md#android-files-view).
- UTF-8 by default, ASCII, UTF-16 LE/BE, emoji, combining characters, RTL text. Existing BOMs and LF/CRLF/CR terminators, including mixed files, are preserved. Use **Open with encoding** for ambiguous BOM-less UTF-16. Binary/control data and invalid encodings are rejected rather than replaced silently.
- Files up to 10 MiB. Loads and disk operations run on an I/O executor. The editor uses Android's native text layout; large-file speed still depends on the device and wrapping.
- Optional foreground autosave: off by default; 30 seconds or 1, 2, 3, 5, 8, 10, 15 minutes. It saves existing writable documents; new files still need Save As. It does not keep the app executing in the background.

Shortcuts: Ctrl+N/O/S, Ctrl+Shift+S, Ctrl+F, Ctrl+Z, Ctrl+Shift+Z or Ctrl+Y, Ctrl+W, Tab/Shift+Tab for indentation. Android provides normal selection and clipboard shortcuts.

The standard **Save As** picker creates a new document and may suffix an existing filename. Use **Save to existing file** to select and confirm overwriting a particular existing document.

## Save protection and backups

Before an overwrite, the application re-reads the destination and compares its SHA-256 fingerprint with the opened/last-saved version. If it changed, you must confirm the overwrite. Existing contents are backed up before any truncation; the resulting contents are verified after writing. On failure, the app attempts and verifies rollback, retains your open edits, and reports the result.

Android document providers do not expose a universal atomic compare-and-swap operation. Another app can still race the final check/write. If a provider or storage failure also prevents rollback, recover the old bytes from **More → Backups**. There is no claim of atomic replacement across all providers.

Backups live in the app-private `files/backups/` directory, remain until manually deleted, and are removed when the app is uninstalled. Open a backup as a new document, then save it to a chosen location. Cloud/device backups of app data are excluded.

## Sync client

**More → Sync → Actions** contains settings, opt-in file linking, server browsing, manual sync, and a list of opted-in local files. Use endpoint `https://git23069.hopto.org:9443/android_textpad` and the server API token. Version 0.1.32 automatically migrates saved addresses from the retired Apache endpoint (`https://git23069.hopto.org/android_textpad`, including explicit port 443) and the older port-8443 endpoint to Caddy on port 9443. The token, file links, server revisions, and pending upload receipts are preserved. Install the APK as an update over the existing app, then save any unsaved edits and use **Sync now**. The yellow/amber dot means the file is not yet fully acknowledged by the server; it can also indicate unsaved edits. The old Apache endpoint now returns HTTP 410. Custom endpoints are preserved. The token is encrypted with an Android Keystore key. App configuration trusts user-installed CA certificates for `git23069.hopto.org` while retaining normal TLS hostname verification.

Only saved local files that are individually opted in participate. Links survive process restarts independently of tabs. Same-name candidates are ranked by bounded content similarity and require confirmation; thereafter a stable document ID links copies across different paths. First linking lets you choose the local or server version. Server browsing downloads to a local file through Save As before opening it for editing.

Sync runs on save, returning to the app, manual request, and WebSocket notifications while the app is visible. WorkManager checks opted-in saved files in the background on a 15-minute schedule (Android may delay it), with network constraints and exponential retries. Successful local saves also enqueue durable work so an interrupted upload can finish after the app exits. WebSockets close while the activity is stopped, and reconnecting checks for missed changes. Dirty buffers defer synchronization. Remote writes back up the displaced local file and briefly suspend editing during replacement. Offline edits upload with last-write-wins in server acceptance order. Stable request IDs allow retrying an interrupted upload without changing its acceptance order. Local deletion does not delete the server copy; inaccessible files are reported. Server revision browsing/restoration is included in the client.

The deployed SQLite service implements [the API contract](docs/sync-api.md), including revision retention and idempotent upload receipts. The desktop editor browses and edits server copies, with tabs, search/replace, undo/redo, downloads, and history restore. Committed saves trigger WebSocket change notifications across both server workers without periodic SQLite polling. The editor refreshes clean tabs, and preserves dirty buffers when another device saves. It reconnects when visible and uses a 30-second polling fallback if live updates are unavailable. It does not browse Android local storage.

Sign into the browser editor with the same API token used by Android. Retrieve it with `ssh <user>@<your-server> 'sudo cat /etc/android-textpad/api-token'`. **Connect Android** in the browser shows the connection settings. See [server operations and backups](server/README.md) for deployment, credentials, daily SQLite backups, and recovery.

## Validation

```sh
./scripts/build.sh
./scripts/build.sh :app:connectedDebugAndroidTest  # connected Android 16+ device/emulator
```

Core JVM tests cover strict encoding, byte-exact Unicode/BOM/newline round trips, 10 MiB limits, randomized edit/undo/line-index behavior, saved-state tracking, overwrite/rollback failure paths, and iterative folder navigation at 2,500 levels. Android instrumentation covers native editor operations, actual file-descriptor reads/writes through a test content provider, duplicate tabs, activity recreation, read-only documents, external modification checks, 10 MiB editing/saving, sync policy, and the native Files UI. Library checks cover nested folder CRUD, group moves, dirty linked tabs, managed local copies, offline reopening, stale catalog responses, and Back navigation.

For the real native HTTPS contract, install both debug APKs on an Android 16+ emulator and run:

```sh
PYTHONPATH=server ~/.cache/android-textpad/server-venv/bin/python server/tests/android_library_contract.py --serial emulator-5556
```

This creates a temporary server, database, token, and CA. The Android test verifies folder/file requests with TLS hostname verification enabled; it does not use production data.

The original `requirements.txt`, `questions.txt`, and `questions2.txt` remain the source of the agreed scope; [the consolidated requirements](docs/requirements.md) record implementation decisions.

Verified for Android 0.1.35: **62 JVM tests pass**, and the Android 16 emulator suite completes **37 cases with two opt-in checks skipped**. The isolated native HTTPS library contract passes separately, and Android lint completes without errors. Earlier manual checks using Android's real Downloads picker verified byte-exact unchanged saves, Unicode editing/saving, Save As, backup revision opening, deliberate loss of unsaved edits after process termination, and reopening recent files using persisted permissions.

Server validation includes **79 backend tests**, desktop Chrome workflows against isolated and deployed services, and Android HTTPS checks for uploads, downloads, Unicode/UTF-16, history, authentication, folder management, and retry ordering. WebSocket updates and WorkManager background sync retain automatic migration of the old sync address and HTTP 404 guidance.
