# Textpad sync CLI

A Python 3.11+ terminal client for Linux, Windows, and macOS. Sync selected files in a normal filesystem directory, edit them with your preferred editor, and run a foreground file monitor when useful. The client has no runtime dependencies beyond Python's standard library.

[Download textpad-sync.pyz](https://git23069.hopto.org/android_textpad/editor/static/textpad-sync.pyz) · [SHA-256](https://git23069.hopto.org/android_textpad/editor/static/textpad-sync.pyz.sha256) · [Online guide](https://git23069.hopto.org/android_textpad/editor/static/textpad-sync-README.txt)

## Run or install

Download the `.pyz` archive outside your sync directory, then run:

```sh
python3 /path/to/textpad-sync.pyz --help
```

On Windows, use `py -3` instead of `python3`. The same archive works on all three platforms. No executable bundler, background service, editor plugin, or Python package installation is required.

Alternatively, from this repository, install the console command in a virtual environment:

```sh
python3 -m venv .venv-cli
.venv-cli/bin/python -m pip install ./cli
.venv-cli/bin/textpad-sync --help
```

Windows equivalents are `.venv-cli\Scripts\python -m pip install .\cli` and `.venv-cli\Scripts\textpad-sync`. Examples below use the installed `textpad-sync` command; substitute `python3 /path/to/textpad-sync.pyz` or `py -3 C:\path\textpad-sync.pyz` when using the archive.

## Set up a directory

```sh
textpad-sync init ~/Documents/Textpad --server https://git23069.hopto.org/android_textpad
cd ~/Documents/Textpad
textpad-sync remote
```

`init` checks server access and prompts for the API token without echoing it. It creates `.textpad/state.sqlite3` for mappings, acknowledged contents, exact pending upload payloads, and recovery snapshots. The token is stored separately in `.textpad/token`. The state directory has mode `0700` and the token/database have mode `0600` on Unix. On Windows, keep the directory in your private user profile with suitable Windows permissions. Do not share or commit `.textpad`.

You can supply `--token-file /private/path/token` to copy a token, or set `TEXTPAD_TOKEN` in your environment. When `init` uses only the environment variable, it does not persist that token; supply it again for subsequent network commands. Existing workspace configuration is never overwritten by `init`.

If the private server CA is not trusted by your Python installation, add `--ca-file /path/to/root-ca.pem` during `init`. The PEM file is copied into the private state directory; certificate chain and hostname verification remain enabled. There is no insecure TLS option, embedded URL credential, or token command-line argument. HTTP and redirects are refused.

All file arguments are relative to the workspace root. Run in that directory, let the CLI discover it from a subdirectory, or use `textpad-sync -C /path/to/workspace COMMAND ...`. Absolute paths inside the workspace are also accepted. Paths outside it, `.textpad` contents, symlinks, special files, and Windows reparse points are excluded.

## Add local files or download server files

```sh
textpad-sync add notes.txt projects/tasks.txt
textpad-sync status
textpad-sync sync
```

Only explicitly tracked files sync; unrelated files and editor swap/temporary files are ignored. `add` records the initial contents and a new permanent server document ID. It works offline and sends no upload until `sync` or `watch` runs. Matching names alone never silently link different documents.

To work with an existing server document, copy its ID from `remote`:

```sh
textpad-sync get DOCUMENT_ID
textpad-sync get ANOTHER_DOCUMENT_ID projects/local-name.txt
```

`get` creates and tracks a new file without overwriting an existing destination. Server filenames are display metadata; a remote rename changes the displayed server name, and does not move your local path. The same server document can be tracked only once in a workspace. If two server documents have identical names, supply different explicit local paths.

To link an existing local file instead of downloading a new file:

```sh
textpad-sync add notes.txt --document DOCUMENT_ID
```

The current server version becomes the comparison baseline and is backed up locally. If the local bytes differ, the next sync treats them as a local edit and proposes uploading them, subject to truncation and concurrent-change checks. Use `get` when you want the server's contents to start with.

Files are transferred as exact bytes, without rewriting encodings, BOMs, or line endings. UTF-8, UTF-16 with a BOM, and the server's ASCII-oriented BOM-less UTF-16 detection are supported; unsupported/invalid text is held rather than converted. Maximum file size is 10 MiB.

## Sync and monitor saves

```sh
textpad-sync sync
textpad-sync sync notes.txt
textpad-sync watch
```

`sync` reconciles both directions. A local-only change uploads; a remote-only change downloads after backing up the displaced local bytes. If both sides changed, it keeps the local file and reports a conflict. Saves use an atomic revision precondition on the server: a newer remote revision arriving between the client's check and upload is rejected rather than overwritten. Android and browser save behavior is unchanged.

`watch` stays attached to your terminal until Ctrl+C. It checks tracked file metadata every 0.5 seconds, waits for two seconds of stability after a local change, then treats the saved bytes as an edit. This handles editors that truncate/rewrite a file or save by replacing its inode. It also reconciles remote changes every 30 seconds. It does not install or manage a service.

```sh
textpad-sync watch --settle 3 --interval 0.5 --remote-interval 15
```

The monitor retries network failures on subsequent reconciliation passes. Each uncertain upload's request ID, name, base revision, and exact bytes are persisted before the request. After a crash or lost response, it retries that same payload before processing newer edits, without replaying an older save ahead of a newer server save. Removing a mapping with an unacknowledged upload is refused until sync settles it.

Only saved disk contents are visible to the CLI. It cannot inspect an external editor's unsaved buffers or tell whether an otherwise plausible rewrite was intentional. Pause watch before keeping an old unsaved buffer open while accepting remote changes. Local replacements compare the saved file again immediately before atomic replacement and retain snapshots, but a portable filesystem has no compare-and-swap operation against an editor writing at the exact same instant. These are recovery safeguards, not a guarantee against every possible editor race or storage failure.

## Blank files and suspicious deletions

Automatic and manual sync both hold a change when it:

- Replaces nonblank content with empty/whitespace-only content.
- Removes more than 75% of the characters from a document containing at least 100 characters.
- Removes more than 75% of the lines from a document containing at least 20 lines.

The checks apply to downloads as well as uploads, and protect the initial `add` snapshot even before the first upload. A deliberately new empty file is allowed. Held versions are retained in local snapshots, while the last good counterpart remains intact. These are conservative heuristics; history and snapshots also retain complete observed versions.

For an intentional local deletion, explicitly name the affected file:

```sh
textpad-sync sync notes.txt --allow-large-delete
```

This override is unavailable in `watch`. For a held server deletion, inspect history and choose it explicitly:

```sh
textpad-sync history notes.txt
textpad-sync resolve notes.txt --use remote
```

## Conflicts and recovery

```sh
textpad-sync status
textpad-sync history notes.txt
textpad-sync backups notes.txt
textpad-sync download DOCUMENT_ID inspection.txt --revision REVISION
textpad-sync recover SNAPSHOT_ID --to recovered-notes.txt
```

`status` and `backups` work offline. `status` shows local state and the last sync issue; its `synced` state means the file matches the last acknowledged version, not that a new remote check has happened. `remote`, `status`, `history`, and `backups` accept `--json`.

Recovery/download commands create a new, untracked inspection file and refuse to overwrite an existing file. Local snapshots are full-content, deduplicated by document/name/checksum, and have no automatic expiry. They remain available through `backups` after a file leaves the sync list. Keep `.textpad` with your normal private backups. Losing it loses the mapping/retry/recovery state.

After reviewing the copies, choose which saved version to keep:

```sh
textpad-sync resolve notes.txt --use local
textpad-sync resolve notes.txt --use remote
```

Both saved copies are retained in snapshots before resolution. Choosing local makes a conditional server save; another remote save can still cause a fresh conflict. If the local choice is a suspicious deletion, add `--allow-large-delete`. Choosing remote explicitly accepts its contents, including an empty version. To restore an older revision to the shared document, download/recover it for inspection, copy its contents into the tracked file using your editor, then sync that file.

## Names and the sync list

```sh
textpad-sync rename notes.txt 'Display name on server.txt'
textpad-sync sync notes.txt

# Move the file yourself with your file manager or OS command, then update its mapping:
textpad-sync relocate notes.txt projects/notes.txt

textpad-sync remove projects/notes.txt
```

`relocate` requires that the old path is absent and the new local file exists. It preserves the server document ID, baseline, and pending receipts. `remove` only stops tracking. It never deletes a local file, server document, or recovery snapshot. Deleting a tracked file externally reports it missing and does not delete its server counterpart or recreate the local file automatically. There is no server document-delete API.

Exit codes: `0` for success, `1` for an operational/configuration failure, `2` when a sync completed with held/conflicting/missing files, and `130` when stopped with Ctrl+C.

## Build and verify

From the repository root:

```sh
PYTHONPATH=cli:server /path/to/server-venv/bin/python -m unittest discover -s cli/tests -v
python3 scripts/package-cli.py
python3 server/textpad_server/static/textpad-sync.pyz --help
# Optional: packaged setup/watch/stop and transfers over verified local HTTPS (requires openssl):
PYTHONPATH=cli:server /path/to/server-venv/bin/python cli/tests/https_smoke.py
```

The integration tests use an isolated temporary server/database and normal HTTP sockets on loopback; production client URLs require HTTPS. Tests cover exact-byte transfers, conflict races, durable retries/restarts, blank/truncated saves, editor replacement saves, recovery, permissions, path checks, and offline commands. Native runtime verification was performed on Linux; Windows/macOS use the same standard-library code and platform file-lock branches and still need native smoke testing.

`./scripts/deploy-server.sh` runs these tests and packages/publishes the archive and guide alongside the server. `package-cli.py` writes deterministic archives and a SHA-256 file; generated downloads are ignored by Git.
