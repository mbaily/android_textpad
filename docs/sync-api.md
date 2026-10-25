# Textpad sync API v1

The Debian 13/SQLite service implements this Android client contract at `https://git23069.hopto.org/android_textpad`. All endpoints require HTTPS and `Authorization: Bearer <token>`. JSON is UTF-8. Maximum decoded document size is 10 MiB; responses are capped at 16 MiB by the client. Return 401/403 for bad credentials and suitable 4xx/5xx responses for failures. The client does not follow redirects or disable TLS verification.

## Representation

```json
{
  "id": "f6772535-e721-4594-87eb-bd82105769e0",
  "name": "notes.txt",
  "revision": "42",
  "sha256": "64 lowercase hex characters computed over the exact file bytes",
  "size": 1234
}
```

IDs and revision identifiers contain 1–128 ASCII letters, digits, `_` or `-`. Clients generate UUID document IDs for new associations. `revision` is a string; the service uses a monotonically increasing SQLite integer represented as text. Filenames are metadata, never server filesystem paths. A content response adds `content_base64`, standard Base64 encoding of the exact file bytes including any BOM and line terminators. The client verifies its size and checksum before use.

## Endpoints

| Request | Response |
| --- | --- |
| `GET /api/v1/capabilities` | `{"conditional_put": true, "max_document_bytes": 10485760}` |
| `GET /api/v1/documents` | `{"documents": [metadata, ...]}` for current documents |
| `GET /api/v1/documents/{id}` | Current metadata plus `content_base64` |
| `PUT /api/v1/documents/{id}` | Metadata of the accepted revision |
| `GET /api/v1/documents/{id}/revisions` | `{"documents": [metadata, ...]}`, newest first, including current revision |
| `GET /api/v1/documents/{id}/revisions/{revision}` | That revision's metadata and `content_base64` |
| `GET /api/v1/errands` | `{"hashtags": ["chemist", ...], "updated_at": "..."}` |
| `PUT /api/v1/errands` | Saves `{"hashtags": ["chemist", ...]}` and returns the normalized list and timestamp |
| `GET /api/v1/tag-groups` | `{"groups": [{"id": "...", "name": "Home", "hashtags": ["calls"], "updated_at": "..."}, ...]}`, alphabetically by name, ignoring case |
| `PUT /api/v1/tag-groups/{id}` | Creates or replaces one group from `{"name": "Home", "hashtags": ["calls"]}` and returns its full representation |
| `DELETE /api/v1/tag-groups/{id}` | Deletes one group; returns `{"deleted": true}`, including when it was already absent |
| `POST /api/v1/hashtags` | Hashtag discovery and matching lines across current server documents |

Errands hashtags are shared settings across clients, with last-write-wins saves. Tags are case-insensitive, deduplicated, and stored without `#`; lists accept optional prefixes and whitespace/comma-separated strings, with each tag limited to 64 characters. Errands settings and search queries have no tag-count cap; every distinct valid tag is retained. Saving an unchanged list preserves its timestamp. Older servers silently retained only the first 200 distinct tags. CLI version 0.1.4 and later verifies that a save acknowledges the complete normalized list; if it differs, the CLI reports failure and retains the pending local list for retry after updating the server.

Tag groups use stable client-generated UUIDs and independent last-write-wins saves. There is no group-count or hashtag-count cap. Names are trimmed, normalized to NFC, and limited to 1–100 characters without control characters. Names must be unique, ignoring case (HTTP 409 on duplicates); **Errands** is reserved for the existing built-in group and continues to use `/errands`. Hashtags follow the same normalization rules as Errands. Unchanged saves preserve their timestamps. Deleting a group never changes document contents. The matching browser endpoints are `/editor/api/tag-groups` and `/editor/api/tag-groups/{id}`, with session authentication and CSRF protection on writes/deletes.

Android and browser caches retain pending edits and deletion markers while offline. They send only changed groups, then merge the current server collection with any still-pending local changes. A response acknowledges only the exact local change sent, preserving edits made while a request is in flight. Clean cached groups absent from the current server collection are removed locally. Name conflicts or failed writes retain the local group for correction/retry. The CLI's existing Errands endpoints and commands are unchanged.

Hashtag search accepts `{"tags": ["chemist", "groceries"], "hide_completed": true, "exclude_ids": ["local-document-id"]}`. Tags use OR matching. `hide_completed` excludes both completed `[X]` and cancelled `[C]` task lines, including lowercase markers, indentation, and list bullets. Its API default is false; the CLI explicitly sends true by default. `exclude_ids` lets a client search its saved local copies without duplicating server matches. The response contains `all_hashtags` (including tags on closed tasks) and `results`, each with `id`, `name`, `revision`, `updated_at`, `matched_tags`, and `lines`. Each line contains `line_number` (one-based), `offset` (in decoded text with LF newlines), full `text`, and `matched_tags`. Files with no visible matching lines are omitted. Empty `tags` discovers hashtags without returning matches. The browser route `/editor/api/hashtags` provides the same search with session/CSRF authentication.

A PUT body contains:

```json
{
  "name": "notes.txt",
  "content_base64": "aGVsbG8K",
  "base_revision": "41",
  "request_id": "7bfd440d-6123-4496-95a2-f9679aa4d2a5"
}
```

For a new document, `base_revision` is null. It is informational, not a conflict rejection condition: the latest accepted write becomes current even if the client edited offline from an older base. Keep the displaced current version in history within the same SQLite transaction. Validate text encodings, names, sizes and request identifiers before accepting a revision.

Filesystem clients can opt into stricter writes by adding `"require_base_revision": true`. After checking an existing retry receipt, the server compares `base_revision` against the current revision inside the same `BEGIN IMMEDIATE` transaction as the save. A mismatch returns HTTP 409 with `"code": "revision_conflict"` and creates neither a revision nor a receipt. A null base requires a nonexistent document. A previously accepted request still returns its original receipt even if the server has since advanced. The flag must be a JSON boolean. Omitting it retains existing Android/browser behavior. Clients that depend on this guard must verify `conditional_put` before saving, rather than assuming an older server understands the flag.

The [Python CLI](../cli/README.md) uses this opt-in guard, exact persisted retry payloads, full-content SQLite recovery snapshots, file stability checks, local three-way merging of compatible concurrent edits, explicit resolution of overlapping conflicts, and holds on suspicious blank/large-deletion saves. Merged uploads are conditional on the exact remote revision used in the merge; the CLI does not use the server's last-write-wins fallback. It never propagates local file deletion as a server deletion.

`(document_id, request_id)` is an idempotency key. Persist its receipt atomically with accepting the revision. A retry returns the original accepted metadata without creating another revision or moving that old write ahead of newer writes. Reusing a request ID with different content must be rejected. Do not discard idempotency receipts merely because a revision was manually removed.

Downloading/restoring an old server revision is followed by a normal PUT with a new request ID, making that content the latest accepted revision while preserving its predecessor. The client has no remote-delete operation; local deletion never propagates to the server.

## Client reconciliation

Each opted-in local URI stores a stable document ID, last acknowledged server revision and hash, and a pending upload request ID/hash when needed. URI permissions and this mapping persist; editor buffers do not.

1. Defer files with unsaved editor buffers or active saves. Report inaccessible/deleted files without propagating deletion.
2. Read the saved local bytes. If an open document has changed externally, report that it must be reloaded before syncing.
3. If the local hash differs from the last acknowledged hash (or no baseline exists), upload the saved local bytes. This write takes the next server acceptance position.
4. Otherwise, if the server hash differs, download, validate and back up/replace the local file, provided it remains opted in and has no unsaved buffer.
5. Store the acknowledged server revision/hash only after the relevant transfer succeeds. Never mark the editor clean for an unverified failed local write.

Choosing **Use server version** for first linking sets the initial baseline to the local saved hash so the first reconciliation can pull. Choosing **Use local version** uses the remote hash as baseline so differing local contents upload.

The service creates daily consistent SQLite backups and retains them until manually removed. See [server backup and recovery instructions](../server/README.md#database-backups-and-recovery). Revision history and upload receipts are stored in the same database.

## Desktop browser API

The browser editor is at `/editor/` under the public endpoint. Its `/editor/api/` routes use a signed login session and CSRF validation for writes, independently of the Android bearer-token API. It lists current documents, returns decoded text and file-format metadata, accepts edited text with a base revision and request ID, previews history, restores old bytes through a new revision, and downloads saved file bytes. Browser edits and Android uploads share the same SQLite write transaction, revision sequence, and last-write-wins policy. Desktop saves preserve existing encodings, BOMs, and line terminators. Browser file names never address Android paths or arbitrary server files.

## Change notifications

`wss://git23069.hopto.org/android_textpad/api/v1/events` authenticates the WebSocket upgrade with the same bearer header as Android HTTPS requests. The browser uses `/editor/api/events` with its signed cookie and a mandatory matching Origin. Tokens are never passed in a URL.

On connection the server sends `{"type":"ready","cursor":"42"}`; subsequent committed revisions or tag group changes produce `{"type":"changed","cursor":"43"}`. The cursor is a monotonically increasing numeric string, combining the SQLite revision sequence high-water mark and a persisted counter of tag group changes across server processes. Clients treat it as a change hint and re-fetch current state. Notifications contain no document text, names, or credentials. Retrying an accepted request or saving an unchanged group creates no new notification.

Clients reconcile through the existing HTTPS API after every ready/change event and after reconnecting. Notifications are hints, not a replacement for revision/checksum validation or durable upload receipts. Full reconciliation on reconnect covers writes missed while disconnected, including across server restarts or restoring a database. Unsaved buffers still defer pulls.

CLI `watch` also subscribes to the bearer-authenticated endpoint. It retains change hints while tracked files settle, keeps all file/database reconciliation on its main thread, and reconnects with backoff. Its periodic HTTPS reconciliation continues every 30 seconds by default as a fallback; live notifications do not wait for that interval. The notification connection uses the workspace's TLS trust settings, including an explicit invocation's `--insecure` choice.

After a new revision commits successfully, the server broadcasts a shared wake-up signal to both worker processes. WebSocket senders block while idle; on a signal they read the latest committed SQLite cursor and send a change hint. Rapid saves can coalesce into one notification, and a change arriving during delivery schedules another check. There is no timed SQLite change polling. A worker exit also signals surviving workers, covering an exit after commit but before publication. The server sends WebSocket ping frames every 25 seconds. Android/browser connections close when the UI is hidden and reconnect with backoff when active. Android WorkManager separately performs background reconciliation with network constraints, a 15-minute periodic interval, and exponential retry backoff. The scheduler may delay work during Doze or other system restrictions; this does not promise immediate background delivery.
