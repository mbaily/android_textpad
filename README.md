# Textpad for Android

A native, dark-by-default text editor for **Android 16 / API 36 and later**. The Debian server hosts APK downloads, the sync API, and a desktop browser editor for synced files.

<img src="docs/screenshots/home.png" width="260" alt="Textpad recent files on Android 16"> <img src="docs/screenshots/editor.png" width="260" alt="Textpad editing a Unicode text file">

## Download

[Download page](https://git23069.hopto.org/android_textpad/) · [Latest APK](https://git23069.hopto.org/android_textpad/textpad-latest.apk) · [Desktop editor](https://git23069.hopto.org/android_textpad/editor/)

[Python CLI client](cli/README.md) · [Download CLI](https://git23069.hopto.org/android_textpad/editor/static/textpad-sync.pyz)

The Python 3.11+ CLI syncs explicitly selected local files on Linux, Windows, and macOS, with SQLite mappings and recovery snapshots. Edit with any text editor and use `textpad-sync sync` or foreground `textpad-sync watch`. It holds suspicious blank/truncated saves and conflicts for explicit resolution. See the [setup, commands, and recovery guide](cli/README.md).

The [Vim 9 plugin](vim/README.md) provides an assignable toggle for the same uncompleted-tasks view. It displays open tasks with their headings/context and lets you edit visible lines; `:write` saves the complete source file, including hidden text. [Download the Vim plugin](https://git23069.hopto.org/android_textpad/editor/static/textpad-tasks-vim.zip).

The site uses the server's existing HTTPS certificate and is served from `/opt/android_textpad/deployment/`. Publish an updated APK with `./scripts/deploy-downloads.sh` after building it. See [server deployment instructions](server/README.md).

## Build and install

Open this directory in Android Studio, or use a JDK 17/21 and Android SDK platform 36 with build tools 36.0.0:

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
./scripts/build.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build script also finds the isolated toolchain installed for this workspace at `~/.cache/android-textpad/`. The Gradle wrapper pins Gradle 8.13 and Android Gradle Plugin 8.13.2. The debug APK is signed automatically. Keep the same debug keystore when building subsequent updates.

## Editor

- New, Open, Save, Save As, Save to existing file, recent files, read-only documents, and Android's standard file picker. The original selected file is edited in place.
- The entry screen shows up to 30 recent files for one-tap reopening. Android uses its saved file history; the browser remembers opened/saved files on this device and fills the list with recently updated synced files. File lists also work with the browser's existing offline cache.
- Multiple tabs, including multiple views of one file. Text and undo history are shared; selections and scrolling belong to each tab. Long-press a tab for its file location and details.
- Unsaved dots and Save/Discard/Cancel when closing a modified tab. No Quit action, persistent tabs, drafts, or crash recovery. Rotation preserves the live editing session. Process termination loses unsaved edits.
- Undo/redo, literal find/replace with case matching, replace all, selection/clipboard, indentation, word wrap, line-number toggles, font families and sizes.
- The todo button adds `[ ]` to plain text, then cycles `[ ]` (open) → `[X]` (completed) → `[C]` (cancelled) → `[ ]`. Android and browser editors accept lowercase `[x]`/`[c]`, indentation, and `- `, `* `, or `+ ` list bullets. For a mixed selection, each click advances the earliest state present: plain lines first, then open, completed, and cancelled tasks; blank lines are skipped when other lines are selected.
- Uncompleted tasks view keeps open tasks, their context and indented notes. Completed and cancelled tasks stay hidden unless needed as ancestors of an open subtask; visible closed tasks and other context are dimmed. It also keeps heading blocks made of a blank line, a nonblank title, and a line containing only dashes (with optional surrounding spaces or tabs) when their section contains an open task. A title at the start of the file does not need a preceding blank line. Tasks do not need to be indented. A section ends at the next divider heading or Markdown heading; completed, cancelled, and empty sections stay hidden. Task membership remains stable while editing until the view is toggled off/on.
- Hashtag and errands search offers **Hide [X] / [C]** to exclude completed and cancelled task lines, including list bullets, in both line and file results. This applies to open buffers, cached files, and server searches; hashtag discovery still includes tags from hidden tasks.
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

**More → Sync → Actions** contains settings, opt-in file linking, server browsing, manual sync, and a list of opted-in local files. Use endpoint `https://git23069.hopto.org/android_textpad` and the server API token. Version 0.1.2 and later also migrate a saved `https://git23069.hopto.org:8443` address automatically, preserving the token and file links. That old port serves a different application and returns HTTP 404 for sync requests. In older versions, correct the address under **Server settings** before enabling sync. Custom endpoints are preserved. The token is encrypted with an Android Keystore key. App configuration trusts user-installed CA certificates for `git23069.hopto.org` while retaining normal TLS hostname verification.

Only saved local files that are individually opted in participate. Links survive process restarts independently of tabs. Same-name candidates are ranked by bounded content similarity and require confirmation; thereafter a stable document ID links copies across different paths. First linking lets you choose the local or server version. Server browsing downloads to a local file through Save As before opening it for editing.

Sync runs on save, returning to the app, manual request, and WebSocket notifications while the app is visible. WorkManager checks opted-in saved files in the background on a 15-minute schedule (Android may delay it), with network constraints and exponential retries. Successful local saves also enqueue durable work so an interrupted upload can finish after the app exits. WebSockets close while the activity is stopped, and reconnecting checks for missed changes. Dirty buffers defer synchronization. Remote writes back up the displaced local file and briefly suspend editing during replacement. Offline edits upload with last-write-wins in server acceptance order. Stable request IDs allow retrying an interrupted upload without changing its acceptance order. Local deletion does not delete the server copy; inaccessible files are reported. Server revision browsing/restoration is included in the client.

The deployed SQLite service implements [the API contract](docs/sync-api.md), including revision retention and idempotent upload receipts. The desktop editor browses and edits server copies, with tabs, search/replace, undo/redo, downloads, and history restore. Committed saves trigger WebSocket change notifications across both server workers without periodic SQLite polling. The editor refreshes clean tabs, and preserves dirty buffers when another device saves. It reconnects when visible and uses a 30-second polling fallback if live updates are unavailable. It does not browse Android local storage.

Sign into the browser editor with the same API token used by Android. Retrieve it with `ssh <user>@<your-server> 'sudo cat /etc/android-textpad/api-token'`. **Connect Android** in the browser shows the connection settings. See [server operations and backups](server/README.md) for deployment, credentials, daily SQLite backups, and recovery.

## Validation

```sh
./scripts/build.sh
./scripts/build.sh :app:connectedDebugAndroidTest  # connected Android 16+ device/emulator
```

Core JVM tests cover strict encoding, byte-exact Unicode/BOM/newline round trips, 10 MiB limits, randomized edit/undo/line-index behavior, saved-state tracking, and overwrite/rollback failure paths. Android instrumentation covers native editor operations, actual file-descriptor reads/writes through a test content provider, duplicate tabs, activity recreation, read-only documents, external modification checks, 10 MiB editing/saving, and sync policy with an injected API.

The original `requirements.txt`, `questions.txt`, and `questions2.txt` remain the source of the agreed scope; [the consolidated requirements](docs/requirements.md) record implementation decisions.

Verified on an Android 16 emulator: **26 JVM tests and 14 instrumentation tests pass**, the debug APK signature verifies, and Android lint completes without errors. Manual checks using Android's real Downloads picker also verified byte-exact unchanged saves, Unicode editing/saving, Save As, backup revision opening, deliberate loss of unsaved edits after process termination, and reopening recent files using persisted permissions.

Server validation: **27 backend tests pass**, desktop Chrome workflows pass against isolated and deployed services, and the Android HTTPS client passes a live test of uploads, downloads, Unicode/UTF-16, history, authentication, and retry ordering. APK 0.1.3 adds live WebSocket updates and WorkManager background sync, retaining automatic migration of the old sync address and HTTP 404 guidance.
