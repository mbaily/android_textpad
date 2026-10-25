# Textpad server

The Debian server hosts the Android sync API, a desktop browser editor, and APK downloads behind Caddy on HTTPS port 9443. The backend serves plain HTTP on localhost only.

| Component | Address |
| --- | --- |
| Desktop editor | https://git23069.hopto.org:9443/android_textpad/editor/ |
| Android sync endpoint | `https://git23069.hopto.org:9443/android_textpad` |
| APK download page | https://git23069.hopto.org:9443/android_textpad/ |
| Latest APK | https://git23069.hopto.org:9443/android_textpad/textpad-latest.apk |
| Python CLI | https://git23069.hopto.org:9443/android_textpad/editor/static/textpad-sync.pyz |
| Vim 9 plugin | https://git23069.hopto.org:9443/android_textpad/editor/static/textpad-tasks-vim.zip |

The [Python CLI](../cli/README.md) works with tracked files in a normal local directory, independently of the browser and Android editors. Its foreground monitor observes saved files, defers suspicious truncations, and preserves local recovery snapshots. Server saves opt into an atomic revision precondition so a newer concurrent save is rejected. Deploying the server also tests and packages the dependency-free CLI archive, checksum, and usage guide.

The standalone [Vim 9 plugin](../vim/README.md) adds the uncompleted-tasks view to local Vim buffers with an assignable mapping. Deployment tests it using the build machine's Vim 9+ and publishes a native-package ZIP, generated help tags, checksum, and guide. Vim is a build/test dependency for publishing this plugin; no Vim component runs in the server service.

## Browser editor

Sign in with the API token. Retrieve it over SSH; credentials are generated on the server and are never included in this repository or an APK:

```sh
ssh <user>@<your-server> 'sudo cat /etc/android-textpad/api-token'
```

The editor supports desktop and mobile layouts. It lists the files stored in the server's SQLite database. Open files in tabs, create files, edit and rename them, search/replace, undo/redo, change font/wrapping/line-number settings, download saved bytes, and preview or restore older revisions. The sidebar toggle beside the tabs hides or shows the file list and remembers your choice in that browser. On phones, the tabs and horizontally scrollable toolbar stay above the software keyboard; tapping toolbar actions keeps the editor focused. **Connect Android** supplies the endpoint and API token for the app's sync settings.

The editor fixes the page body and disables page scrolling, with document scrolling contained inside CodeMirror, to reduce the initial iOS keyboard scroll-jump. VisualViewport sizing and positioning remain as a fallback when iOS still pans the visible area. The native keyboard animation needs verification on a physical iPhone.

The todo button adds `[ ]` to plain lines and cycles `[ ]` → `[X]` → `[C]` → `[ ]`, matching Android. `[C]` means cancelled; lowercase `[c]` and `[x]` and list bullets are accepted. The uncompleted-tasks view hides completed/cancelled rows except when needed as context for an open subtask. Hashtag and errands search's **Hide [X] / [C]** option excludes both closed states from open buffers, offline cached files, and server results. The existing API field `hide_completed` covers both states; task markers remain ordinary document text and require no database migration.

When no file is open, the entry screen lists up to 30 recent files. Files opened or saved in this browser appear first, followed by other synced files in last-updated order. Opening history survives reloads on this device; cached files can be reopened offline. New unsaved documents are not added until saved.

Saving creates a server revision available to synced devices. WebSocket notifications refresh a clean open browser tab when another client saves; a dirty tab keeps its edits and reports the newer server version. The last save accepted by the server wins, and all displaced revisions remain in history. Closing a dirty tab offers Save/Discard/Cancel; leaving the page warns about unsaved edits. Open tabs and unsaved buffers live only in that browser session; no drafts are persisted.

Browser operations access server copies only. They do not browse the Android device's local storage. Android still requires each saved local file to be explicitly linked for sync. There is no remote-delete API.

UTF-8, UTF-16 LE/BE, ASCII, BOMs, and existing LF/CRLF/CR or mixed terminators are supported. An unchanged save preserves the exact bytes. Binary/invalid text and files larger than 10 MiB are rejected. File names are metadata and never become server filesystem paths.

## Layout and services

| Location | Purpose |
| --- | --- |
| `/opt/android_textpad/server` | Symlink to the active server release |
| `/opt/android_textpad/server-releases/` | Retained application releases, each with its Python environment |
| `/opt/android_textpad/deployment` | Symlink to the published APK download site |
| `/opt/android_textpad/releases/` | Retained APK download releases |
| `/opt/android_textpad/config-backups/` | Root-only deployment/configuration snapshots |
| `/srv/textpad-data/config/server.env` | Service paths and public URL |
| `/srv/textpad-data/config/api-token` | API and default browser credential, root/textpad readable |
| `/srv/textpad-data/config/session-key` | Browser cookie signing key |
| `/srv/textpad-data/textpad.sqlite3` | Current documents, exact bytes, revisions, and upload receipts |
| `/srv/textpad-data/backups/` | Consistent SQLite backups, retained until manually removed |

Since 25 October 2026 the live database, server settings, unchanged API token,
session key and existing backups all live under
`/srv/textpad-data/`. `/etc/android-textpad` is a
compatibility link to its `config/` directory. Both systemd services use
`storage.conf` drop-ins selecting the canonical environment file and writable
root; the backup service explicitly passes `backup --directory` to the new
`backups/` directory. Restore those drop-ins with the data/configuration.
Reference copies are retained in `config/systemd/`.

`TEXTPAD_DATABASE`, `TEXTPAD_TOKEN_FILE`, `TEXTPAD_SESSION_FILE` and optional
`TEXTPAD_BROWSER_PASSWORD_FILE` select the corresponding server files. The
installer reads the configured database filename when preparing directories and
its pre-deployment backup. The administrative CLI still needs an explicit
`backup --directory` when overriding its built-in backup default. Without
configuration, runtime defaults still use `/opt/android_textpad/data` and
`/etc/android-textpad`. Browser and Android device storage remains on each client.

`android-textpad.service` runs Gunicorn as the dedicated `textpad` account, listening on `127.0.0.1:8768` with two worker processes and 16 threads per worker. WebSocket connections each occupy a thread, suitable for this private deployment. Caddy strips `/android_textpad` and proxies editor/API requests, including both authenticated WebSocket paths, over plain HTTP. It serves the APK download site directly from `/opt/android_textpad/deployment`. The former Apache HTTPS endpoint is retired and returns HTTP 410 for Textpad paths. No new public port is opened. The existing certificate and private root CA are reused; clients must trust that CA and retain hostname verification. Port 8443 belongs to another application.

Browser sessions use Secure/HttpOnly/SameSite cookies scoped to the editor, CSRF protection, a 12-hour lifetime, login throttling, and a content security policy. Android endpoints always require a bearer token, independently of browser cookies. A separate browser credential can be enabled by adding `TEXTPAD_BROWSER_PASSWORD_FILE=/etc/android-textpad/browser-password` to `server.env` and creating that file with at least 24 characters and the same ownership/mode as `api-token`. Restart the service after credential changes; existing browser sessions become invalid when their credential or signing key changes.

```sh
sudo systemctl status android-textpad.service
sudo journalctl -u android-textpad.service -n 50 --no-pager
sudo systemctl status android-textpad-backup.timer
```

## Deploy the server

The build requires Node/npm, Python 3.13 with venv support, SSH/scp/rsync, and the existing deployment account's passwordless sudo. The target uses Debian's `/usr/bin/python3` (3.13); a separate `/usr/local/bin/python3` must not be used for its environment. All runtime assets are served locally; no CDN is required. Gunicorn must use `--config python:textpad_server.gunicorn_config` as in the supplied systemd unit. That configuration preloads the app, creates the cross-worker wake-up counters before forking, and starts a listener in each worker. Use a full service restart for code or worker-count changes; no additional broker, service, SQLite extension, or database schema change is needed.

```sh
/usr/bin/python3 -m venv ~/.cache/android-textpad/server-venv
~/.cache/android-textpad/server-venv/bin/pip install -r server/requirements.lock
./scripts/deploy-server.sh
```

Set `TEXTPAD_BUILD_PYTHON` for another Python 3.13 environment. The script installs the locked frontend packages, builds the editor bundle, runs backend tests, downloads the pinned Python wheels, and stages the result over SSH. The installer preserves credentials and data, takes an online database backup, checks startup as the service user, atomically switches the application release, checks Caddy and Apache syntax, and verifies the public login page using the existing CA. It restores the preceding application/configuration if activation fails. Deployments share a host-local lock with APK publication.

The canonical routes are maintained from `server/caddy/textpad.caddy` inside `/etc/caddy/Caddyfile`. The installer replaces only its marked Textpad block before the existing fallback, validates Caddy and reloads it, and restores the prior Caddy configuration and public URL on activation failure. The `/etc/apache2/android-textpad-server.conf` include rejects retired Textpad URLs with HTTP 410. Apache no longer proxies the API/editor or serves the APK download directory. Other sites and services retain their configuration.

Browser storage is separate at the new port-9443 origin. Saved server documents and credentials are retained. Android and CLI clients must use `https://git23069.hopto.org:9443/android_textpad`.

After successful activation and all health checks, each installer keeps the three
newest releases of its own component. The active and immediately preceding
release are always protected, including when rolling forward from an older
release. Configuration snapshots belonging to removed releases expire with
them. Failed activation restores the previous release and does not prune
history. Cleanup errors are reported after activation and do not undo a healthy
deployment.

Each retained server release needs its own Python environment for reliable
rollback. The deployment packages only the server, compiled browser assets and
pinned Python requirements; build tools and wheels stay outside `/opt`.
The APK installer stores the stable and versioned download URLs as hard links
to the same bytes. Existing older releases are reclaimed on the next successful
deployment of the corresponding component. The two installers share a lock,
so release cleanup cannot race another Textpad deployment.

Retention never removes `/srv/textpad-data`, its database backups, or
credentials in `/etc/android-textpad`. Those backups still need their separate
backup and retention policy.

Generated `editor.js`, npm packages, virtual environments, databases, wheel packages, and APKs are ignored by Git. Source lives in `textpad_server/`, `frontend/`, and `deploy/`; frontend third-party notices are published alongside the bundle.

## Database backups and recovery

The daily `android-textpad-backup.timer` uses SQLite's online backup API, including committed WAL data. Backups and revisions have no automatic expiration. Trigger an additional backup before maintenance:

```sh
sudo systemctl start android-textpad-backup.service
sudo ls -l /srv/textpad-data/backups/
```

To restore a selected backup, stop the service **and backup timer**, retain the current database and its `-wal`/`-shm` files together in a separate recovery directory, then install the selected backup as `/srv/textpad-data/textpad.sqlite3` owned by `textpad:textpad`, mode `0600`. Do not leave old WAL/SHM files beside the restored database. Check `PRAGMA integrity_check` on the selected backup before installation, then start the service and timer. Database restoration restores documents, history, and retry receipts to the backup's point in time; `/etc/android-textpad/` credentials are separate. Copy database backups and credentials to your normal protected backup location as needed.

Historical revisions can be removed explicitly by an administrator; the current revision is protected and idempotency receipts are retained:

```sh
sudo -u textpad sh -c 'cd /opt/android_textpad/server && .venv/bin/python -m textpad_server remove-revision DOCUMENT_ID REVISION'
```

There is no background history cleanup. The [sync contract](../docs/sync-api.md) describes transaction and retry semantics.

## Publish the APK

```sh
./scripts/build.sh
./scripts/deploy-downloads.sh
```

`package-downloads.py` verifies the APK signature/ZIP, reads Android build metadata, and generates `deployment/` with the page, stable and fingerprinted APKs, SHA-256 files, and `release.json`. Keep the same Android debug signing key for updates. An alternate debug APK can be selected with `--apk /path/to/app-debug.apk`, with matching `output-metadata.json` alongside it.

The download installer validates files and hashes, creates a complete release, saves Apache configuration, and atomically switches `/opt/android_textpad/deployment`. It checks the served page, metadata, and APK hash through Caddy on port 9443 using CA-verified HTTPS and rolls back on failure. Caddy serves APKs as attachments, disables directory listing, and revalidates stable URLs. The Textpad HTTP path redirects to HTTPS.

## Local verification

```sh
PYTHONPATH=server ~/.cache/android-textpad/server-venv/bin/python -m unittest discover -s server/tests -v
npm ci --prefix server/frontend
npm test --prefix server/frontend
npm run build --prefix server/frontend
# Requires Playwright and Chrome in the selected Python environment:
PYTHONPATH=server python3 server/tests/browser_smoke.py
PYTHONPATH=server python3 server/tests/browser_tasks_smoke.py
PYTHONPATH=server python3 server/tests/browser_errands_smoke.py
PYTHONPATH=server python3 server/tests/browser_recents_smoke.py
PYTHONPATH=server python3 server/tests/browser_keyboard_smoke.py
```

The Chrome smoke test starts an isolated temporary server/database, verifies live WebSocket updates without manual refresh, and exercises editing, Unicode/newline preservation, remote updates with clean/dirty buffers, revision restore, tabs, undo/redo, search, settings, rename, downloads, close confirmation, and logout. Backend tests cover the API contract, authentication/CSRF, byte-exact encodings, 10 MiB boundaries, concurrent writes, idempotent retries, history, and online backups. Event tests also verify zero periodic SQLite reads while idle, notification only after commit, rollback/retry suppression, coalescing, saves during delivery, disconnect cleanup, and real two-worker delivery/replacement under Gunicorn.

The task-view smoke test checks filtering, dimmed context, divider headings above unindented tasks and at the start of a file, line-edit restrictions, tab switching, empty results, keeping the cursor visible when returning to the full document, and saving edits while preserving hidden text and CRLF line endings. JavaScript unit tests also cover the heading recognition rule, including whitespace, divider lengths, section boundaries, and hiding sections without open tasks.

The recent-file smoke test checks reopening from the entry screen, order and persistence across reloads, renamed files, mobile layout, offline reopening, and starting a new file from an empty list.

The keyboard smoke test requires both Chrome and Playwright WebKit. It checks scrolling through a long document without moving the page or top controls, then simulates the keyboard resizing the visible area with and without a viewport pan. It also checks visible tabs/toolbar, horizontal scrolling, touch actions without losing editor focus, cursor visibility, rotation, dismissal/reopening, and the fallback without VisualViewport. Headless browsers cannot open the native iOS keyboard, so a physical iPhone check remains useful.

The errands pane scrolls as a whole, with its hashtag list, match mode, and Hide filter inside **Errands settings**, collapsed by default. Its browser check covers a 950-tag list, wheel scrolling, result navigation, settings editing, and desktop, tablet, mobile, and short viewports.

The **Tag groups** selector includes Errands and user-created groups in alphabetical order. Groups support creation, rename, hashtag editing, and deletion with local offline persistence and per-group synchronization. `PYTHONPATH=server python3 server/tests/browser_tag_groups_smoke.py` checks 950-tag groups, alphabetical CRUD, line/file matching, closed-task filtering, live remote creation/deletion, offline edits/deletions across reloads, reconnection retries, and the mobile selector layout.

`LiveSyncInstrumentedTest` is an optional real Android-to-HTTPS contract test. Normal runs skip it. To opt in, supply `files/live-sync-test.json` (token and a fresh disposable `document_id`) and `files/live-sync-ca.crt` to the test application's private directory, then run that class with instrumentation argument `liveTextpadServer=true`. It trusts only the supplied public CA for that test, preserves hostname verification, checks Unicode/UTF-16 bytes, history and retry ordering, automatic WebSocket pulls, deferred dirty buffers, and a real HTTPS background-worker pull, and removes its local credential files afterward. Remove only its known disposable document from the server after verification.

## Live updates and background work

Android APK 0.1.3 opens an authenticated WebSocket while its activity is visible and synced files are configured. The desktop editor opens a cookie-authenticated socket while visible. Both reconcile current server revisions on initial connection and after reconnecting, and close sockets when hidden. The browser uses a 30-second polling fallback when its socket is unavailable. Incoming changes retain the same dirty-buffer protections as manual sync.

The event feed wakes after successful commits, using shared Linux event counters created before Gunicorn forks its workers. Each worker blocks on its wake-up counter and notifies its local WebSocket senders; idle sockets perform no periodic SQLite queries. On a wake-up, senders read the current committed revision cursor. Bursts coalesce, so clients fetch the latest saved state without replaying every intermediate save. A worker exit wakes surviving workers too, including when a process exits after commit but before signalling. Only change hints travel through the socket; file transfers, checksums, revision history, and upload receipts remain on the HTTPS API. Ping frames and reconnect backoff handle broken connections.

On Android, a unique WorkManager periodic job checks opted-in files with a network constraint every 15 minutes, subject to Android's power/scheduling limits. Saves enqueue durable one-time work, and failed foreground transfers enqueue a deduplicated retry job with exponential backoff. Jobs share the editor's serialized I/O queue and are cancelled when no files are opted in or the token is removed. Unsaved buffers, disabled files, and displaced-file backups retain their existing behavior. No Firebase configuration or Google Play services are required.
