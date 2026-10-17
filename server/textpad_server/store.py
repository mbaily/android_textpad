"""SQLite revisions and idempotent write receipts, committed in one transaction."""
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata

from .codec import decode, merge_3way

METADATA = "sequence,document_id,name,sha256,size,encoding,created_at,source"
RECENT_KEEP_COUNT = 10
RECENT_KEEP_SECONDS = 3600
HOURLY_KEEP_SECONDS = 24 * 3600
DAILY_KEEP_SECONDS = 14 * 24 * 3600
WEEKLY_KEEP_SECONDS = 56 * 24 * 3600
CONFLICT_KEEP_SECONDS = 30 * 24 * 3600


class NotFound(ValueError):
    pass


class RequestConflict(ValueError):
    pass


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise ValueError("Invalid document, revision, or request identifier.")
    return value


def infer_source(client_id=None, explicit_source=None):
    if explicit_source:
        return identifier(str(explicit_source))
    if client_id:
        cid = str(client_id).lower()
        if cid.startswith("web"):
            return "web"
        if cid.startswith("android"):
            return "android"
        if cid.startswith("ios"):
            return "ios"
    return "api"


def filename(value):
    if not isinstance(value, str) or not value.strip() or value in {".", ".."} \
            or re.search(r"[/\\\x00-\x1f\x7f]", value):
        raise ValueError("Use a filename of 1–255 characters without slashes or control characters.")
    value = unicodedata.normalize("NFC", value.strip())
    if not value or len(value) > 255 or value in {".", ".."}:
        raise ValueError("Use a filename of 1–255 characters without slashes or control characters.")
    value.encode("utf-8", errors="strict")
    return value


def representation(row):
    item = {"id": row["document_id"], "name": row["name"], "revision": str(row["sequence"]),
            "sha256": row["sha256"], "size": row["size"], "updated_at": row["created_at"],
            "encoding": row["encoding"]}
    if "source" in row.keys() and row["source"]:
        item["source"] = row["source"]
    return item


def parse_timestamp(value):
    try:
        dt = datetime.fromisoformat(value)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp()
    except (TypeError, ValueError):
        return 0.0


def time_bucket(timestamp, now):
    age = max(0.0, now - timestamp)
    if age <= RECENT_KEEP_SECONDS:
        return None
    if age <= HOURLY_KEEP_SECONDS:
        return f"H:{int(timestamp // 3600)}"
    if age <= DAILY_KEEP_SECONDS:
        return f"D:{int(timestamp // 86400)}"
    if age <= WEEKLY_KEEP_SECONDS:
        return f"W:{int(timestamp // 604800)}"
    dt = datetime.fromtimestamp(timestamp, tz=timezone.utc)
    return f"M:{dt.year:04d}-{dt.month:02d}"


def exponential_stride(distance):
    if distance <= RECENT_KEEP_COUNT:
        return 1
    if distance <= 26:
        return 2
    if distance <= 58:
        return 4
    if distance <= 122:
        return 8
    return 16


def select_pruned_sequences(revisions_newest_first, current_revision, protected_sequences=(), now=None):
    total = len(revisions_newest_first)
    if total <= RECENT_KEEP_COUNT:
        return []
    if now is None:
        now = datetime.now(timezone.utc).timestamp()
    keep = {int(current_revision)} if current_revision is not None else set()
    keep.update(int(seq) for seq in protected_sequences)
    seen_buckets = set()
    for distance, (sequence, created_at) in enumerate(revisions_newest_first):
        seq = int(sequence)
        ts = parse_timestamp(created_at) if isinstance(created_at, str) else float(created_at)
        bucket = time_bucket(ts, now)
        is_first_in_bucket = False
        if bucket is not None and bucket not in seen_buckets:
            seen_buckets.add(bucket)
            is_first_in_bucket = True
        rank = total - 1 - distance  # 0 for the very first revision of the document
        stride = exponential_stride(distance)
        if (
            distance < RECENT_KEEP_COUNT
            or rank == 0
            or (now - ts) <= RECENT_KEEP_SECONDS
            or is_first_in_bucket
            or (rank % stride == 0)
        ):
            keep.add(seq)
    return [int(seq) for seq, _ in revisions_newest_first if int(seq) not in keep]


class Store:
    def __init__(self, path, on_commit=None):
        self.path = str(path)
        self.on_commit = on_commit
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with closing(self.connect()) as db:
            if db.execute("PRAGMA user_version").fetchone()[0] > 1:
                raise RuntimeError("This database was created by a newer Textpad server.")
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY, current_revision INTEGER
                );
                CREATE TABLE IF NOT EXISTS revisions (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    document_id TEXT NOT NULL REFERENCES documents(id),
                    name TEXT NOT NULL, content BLOB NOT NULL,
                    sha256 TEXT NOT NULL, size INTEGER NOT NULL,
                    encoding TEXT NOT NULL, created_at TEXT NOT NULL,
                    source TEXT NOT NULL DEFAULT ''
                );
                CREATE INDEX IF NOT EXISTS revisions_document ON revisions(document_id, sequence DESC);
                CREATE TABLE IF NOT EXISTS receipts (
                    document_id TEXT NOT NULL, request_id TEXT NOT NULL,
                    request_hash TEXT NOT NULL, response TEXT NOT NULL,
                    PRIMARY KEY (document_id, request_id)
                );
                CREATE TABLE IF NOT EXISTS device_cursors (
                    client_id TEXT NOT NULL, document_id TEXT NOT NULL,
                    revision INTEGER NOT NULL, updated_at TEXT NOT NULL,
                    PRIMARY KEY (client_id, document_id)
                );
                CREATE TABLE IF NOT EXISTS login_attempts (
                    address TEXT PRIMARY KEY, failures INTEGER NOT NULL, started REAL NOT NULL
                );
                PRAGMA user_version=1;
            """)
            cols = {row[1] for row in db.execute("PRAGMA table_info(revisions)")}
            if "source" not in cols:
                db.execute("ALTER TABLE revisions ADD COLUMN source TEXT NOT NULL DEFAULT ''")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=20, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA synchronous=FULL")
        return db

    def list(self):
        with closing(self.connect()) as db:
            columns = ",".join("r." + name for name in METADATA.split(","))
            rows = db.execute(f"""SELECT {columns} FROM documents d JOIN revisions r ON d.current_revision=r.sequence
                                  ORDER BY r.created_at DESC,r.sequence DESC""").fetchall()
            return [representation(row) for row in rows]

    def get(self, document_id, revision=None, client_id=None):
        identifier(document_id)
        if client_id is not None:
            identifier(str(client_id))
        with closing(self.connect()) as db:
            if revision is None:
                row = db.execute("""SELECT r.* FROM documents d JOIN revisions r ON d.current_revision=r.sequence
                                    WHERE d.id=?""", (document_id,)).fetchone()
            else:
                identifier(str(revision))
                row = db.execute("SELECT * FROM revisions WHERE document_id=? AND sequence=?", (document_id, revision)).fetchone()
            if row is None:
                raise NotFound("Document or revision not found.")
            if client_id is not None and revision is None:
                now_iso = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                db.execute(
                    "INSERT OR REPLACE INTO device_cursors VALUES(?,?,?,?)",
                    (str(client_id), document_id, int(row["sequence"]), now_iso),
                )
            return representation(row), bytes(row["content"])

    def revisions(self, document_id):
        identifier(document_id)
        with closing(self.connect()) as db:
            if db.execute("SELECT id FROM documents WHERE id=?", (document_id,)).fetchone() is None:
                raise NotFound("Document not found.")
            return [representation(row) for row in db.execute(
                f"SELECT {METADATA} FROM revisions WHERE document_id=? ORDER BY sequence DESC", (document_id,))]

    def put(self, document_id, name, content, base_revision, request_id, encoding_hint=None, client_id=None, source=None):
        identifier(document_id), identifier(request_id)
        name = filename(name)
        if base_revision is not None:
            identifier(str(base_revision))
        if client_id is not None:
            identifier(str(client_id))
        rev_source = infer_source(client_id, source)
        decoded = decode(content, encoding_hint)
        sha256 = hashlib.sha256(content).hexdigest()
        request_hash = hashlib.sha256(name.encode() + b"\0" + content).hexdigest()
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                receipt = db.execute("SELECT * FROM receipts WHERE document_id=? AND request_id=?", (document_id, request_id)).fetchone()
                if receipt is not None:
                    if receipt["request_hash"] != request_hash:
                        raise RequestConflict("This request ID was already used for different contents or a different filename.")
                    db.rollback()
                    return json.loads(receipt["response"])
                previous = db.execute(
                    """SELECT r.sequence, r.name, r.content, r.sha256, r.size, r.encoding, r.created_at, r.source
                       FROM documents d JOIN revisions r ON d.current_revision=r.sequence
                       WHERE d.id=?""",
                    (document_id,),
                ).fetchone()
                if (
                    previous is not None
                    and previous["name"] == name
                    and previous["encoding"] == decoded.encoding
                    and previous["sha256"] == sha256
                    and bytes(previous["content"]) == content
                ):
                    revision = str(previous["sequence"])
                    response = {
                        "id": document_id,
                        "name": name,
                        "revision": revision,
                        "sha256": sha256,
                        "size": previous["size"],
                        "updated_at": previous["created_at"],
                        "encoding": previous["encoding"],
                        "source": previous["source"] or rev_source,
                        "previous_revision": revision,
                        "replaced_newer_revision": False,
                    }
                    if client_id is not None:
                        now_iso = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                        db.execute(
                            "INSERT OR REPLACE INTO device_cursors VALUES(?,?,?,?)",
                            (str(client_id), document_id, int(previous["sequence"]), now_iso),
                        )
                    db.execute("INSERT INTO receipts VALUES(?,?,?,?)", (document_id, request_id, request_hash, json.dumps(response)))
                    db.commit()
                    return response
                db.execute("INSERT OR IGNORE INTO documents(id) VALUES (?)", (document_id,))
                created = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                old_revision = str(previous["sequence"]) if previous is not None else None
                merged_raw = None
                if previous is not None and base_revision is not None and old_revision != str(base_revision):
                    base_row = db.execute(
                        "SELECT content, encoding FROM revisions WHERE document_id=? AND sequence=?",
                        (document_id, base_revision),
                    ).fetchone()
                    if base_row is not None:
                        merged_raw = merge_3way(
                            decode(bytes(base_row["content"]), base_row["encoding"]),
                            decode(bytes(previous["content"]), previous["encoding"]),
                            decoded,
                        )
                cursor = db.execute("""INSERT INTO revisions(document_id,name,content,sha256,size,encoding,created_at,source)
                                       VALUES(?,?,?,?,?,?,?,?)""", (document_id,name,content,sha256,len(content),decoded.encoding,created,rev_source))
                client_seq = cursor.lastrowid
                if merged_raw is not None:
                    merged_sha256 = hashlib.sha256(merged_raw).hexdigest()
                    merged_created = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                    merged_cursor = db.execute(
                        """INSERT INTO revisions(document_id,name,content,sha256,size,encoding,created_at,source)
                           VALUES(?,?,?,?,?,?,?,?)""",
                        (document_id, name, merged_raw, merged_sha256, len(merged_raw), decoded.encoding, merged_created, "merge"),
                    )
                    final_seq = merged_cursor.lastrowid
                    db.execute("UPDATE documents SET current_revision=? WHERE id=?", (final_seq, document_id))
                    response = {
                        "id": document_id,
                        "name": name,
                        "revision": str(final_seq),
                        "sha256": merged_sha256,
                        "size": len(merged_raw),
                        "updated_at": merged_created,
                        "encoding": decoded.encoding,
                        "source": "merge",
                        "previous_revision": old_revision,
                        "client_revision": str(client_seq),
                        "replaced_newer_revision": False,
                        "merged_newer_revision": True,
                    }
                else:
                    final_seq = client_seq
                    db.execute("UPDATE documents SET current_revision=? WHERE id=?", (final_seq, document_id))
                    response = {
                        "id": document_id,
                        "name": name,
                        "revision": str(final_seq),
                        "sha256": sha256,
                        "size": len(content),
                        "updated_at": created,
                        "encoding": decoded.encoding,
                        "source": rev_source,
                        "previous_revision": old_revision,
                        "replaced_newer_revision": old_revision is not None and old_revision != base_revision,
                    }
                if client_id is not None:
                    db.execute(
                        "INSERT OR REPLACE INTO device_cursors VALUES(?,?,?,?)",
                        (str(client_id), document_id, int(final_seq), response["updated_at"]),
                    )
                db.execute("INSERT INTO receipts VALUES(?,?,?,?)", (document_id,request_id,request_hash,json.dumps(response)))
                self._prune_document(db, document_id, final_seq)
                db.commit()
            except BaseException:
                db.rollback()
                raise
        if self.on_commit is not None:
            self.on_commit()
        return response

    def _protected_conflict_sequences(self, db, document_id, now):
        protected = set()
        rows = db.execute("SELECT response FROM receipts WHERE document_id=?", (document_id,)).fetchall()
        for row in rows:
            try:
                data = json.loads(row["response"])
            except (TypeError, ValueError):
                continue
            if data.get("replaced_newer_revision") or data.get("merged_newer_revision"):
                updated_ts = parse_timestamp(data.get("updated_at", ""))
                if (now - updated_ts) <= CONFLICT_KEEP_SECONDS:
                    for key in ("previous_revision", "client_revision"):
                        if data.get(key) is not None:
                            try:
                                protected.add(int(data[key]))
                            except (TypeError, ValueError):
                                pass
        cursor_rows = db.execute("SELECT revision, updated_at FROM device_cursors WHERE document_id=?", (document_id,)).fetchall()
        for row in cursor_rows:
            updated_ts = parse_timestamp(row["updated_at"])
            if (now - updated_ts) <= CONFLICT_KEEP_SECONDS:
                try:
                    protected.add(int(row["revision"]))
                except (TypeError, ValueError):
                    pass
        return protected

    def _prune_document(self, db, document_id, current_revision=None, now=None):
        if current_revision is None:
            doc_row = db.execute("SELECT current_revision FROM documents WHERE id=?", (document_id,)).fetchone()
            if doc_row is None:
                raise NotFound("Document not found.")
            current_revision = doc_row["current_revision"]
        rows = db.execute(
            "SELECT sequence, created_at FROM revisions WHERE document_id=? ORDER BY sequence DESC",
            (document_id,),
        ).fetchall()
        if len(rows) <= RECENT_KEEP_COUNT:
            return 0
        if now is None:
            now = datetime.now(timezone.utc).timestamp()
        protected = self._protected_conflict_sequences(db, document_id, now)
        revisions = [(row["sequence"], row["created_at"]) for row in rows]
        to_delete = select_pruned_sequences(revisions, current_revision, protected, now)
        if not to_delete:
            return 0
        placeholders = ",".join("?" for _ in to_delete)
        result = db.execute(
            f"DELETE FROM revisions WHERE document_id=? AND sequence IN ({placeholders})",
            (document_id, *to_delete),
        )
        return result.rowcount

    def prune(self, document_id=None, now=None):
        if document_id is not None:
            identifier(document_id)
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                if document_id is not None:
                    removed = self._prune_document(db, document_id, now=now)
                else:
                    removed = 0
                    docs = db.execute("SELECT id, current_revision FROM documents").fetchall()
                    for doc in docs:
                        removed += self._prune_document(db, doc["id"], doc["current_revision"], now=now)
                db.commit()
                return removed
            except BaseException:
                db.rollback()
                raise

    def has_receipt(self, document_id, request_id):
        identifier(document_id), identifier(request_id)
        with closing(self.connect()) as db:
            return db.execute("SELECT 1 FROM receipts WHERE document_id=? AND request_id=?", (document_id,request_id)).fetchone() is not None

    def change_cursor(self):
        # The high-water mark survives history removal and works across Gunicorn workers.
        with closing(self.connect()) as db:
            row = db.execute("SELECT seq FROM sqlite_sequence WHERE name='revisions'").fetchone()
            return str(row[0]) if row else "0"

    def backup(self, destination):
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            raise ValueError("The backup destination already exists.")
        with closing(self.connect()) as source, closing(sqlite3.connect(destination)) as target:
            source.backup(target)
        destination.chmod(0o600)

    def remove_revision(self, document_id, revision):
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                current = db.execute("SELECT current_revision FROM documents WHERE id=?", (document_id,)).fetchone()
                if current and str(current[0]) == str(revision):
                    raise ValueError("The current revision cannot be removed.")
                result = db.execute("DELETE FROM revisions WHERE document_id=? AND sequence=?", (document_id,revision))
                if result.rowcount == 0:
                    raise NotFound("Revision not found.")
                db.commit()  # Upload receipts intentionally outlive deleted history.
            except BaseException:
                db.rollback()
                raise
