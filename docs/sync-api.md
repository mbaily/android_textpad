# Textpad sync API v1

This is the Android client's contract for the subsequent Debian 13/SQLite service. It is not an existing deployed API. All endpoints require HTTPS and `Authorization: Bearer <token>`. JSON is UTF-8. Maximum decoded document size is 10 MiB; responses are capped at 16 MiB by the client. Return 401/403 for bad credentials and suitable 4xx/5xx responses for failures. The client does not follow redirects or disable TLS verification.

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

IDs and revision identifiers contain 1–128 ASCII letters, digits, `_` or `-`. Clients generate UUID document IDs for new associations. `revision` is a string; a service-wide monotonically increasing SQLite integer represented as text is recommended. Filenames are metadata, never server filesystem paths. A content response adds `content_base64`, standard Base64 encoding of the exact file bytes including any BOM and line terminators. The client verifies its size and checksum before use.

## Endpoints

| Request | Response |
| --- | --- |
| `GET /api/v1/documents` | `{"documents": [metadata, ...]}` for current documents |
| `GET /api/v1/documents/{id}` | Current metadata plus `content_base64` |
| `PUT /api/v1/documents/{id}` | Metadata of the accepted revision |
| `GET /api/v1/documents/{id}/revisions` | `{"documents": [metadata, ...]}`, newest first, including current revision |
| `GET /api/v1/documents/{id}/revisions/{revision}` | That revision's metadata and `content_base64` |

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

Subsequent implementation should add explicit server database backup/restore instructions alongside retained document history. Revision history alone does not replace a backup of the SQLite database.
