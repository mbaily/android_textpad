"""SQLite revisions and idempotent write receipts, committed in one transaction."""
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata

from .codec import InvalidText, decode, edited, merge_3way

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


class RevisionConflict(ValueError):
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


HASHTAG_RE = re.compile(r"(?:^|(?<=[\s(\[{\"']))#([A-Za-z_][A-Za-z0-9_-]*)(?=$|\s|[.,;:!?)\]}\"'])")
CLOSED_TASK_RE = re.compile(r"^[ \t]*(?:[-*+] )?\[[XxCc]\]")
TAG_TOKEN_RE = re.compile(r"#?([A-Za-z_][A-Za-z0-9_-]*)")


def normalize_hashtags(value):
    if value is None:
        return []
    if isinstance(value, str):
        tokens = TAG_TOKEN_RE.findall(value)
    elif isinstance(value, (list, tuple, set)):
        tokens = []
        for item in value:
            if not isinstance(item, str):
                raise ValueError("Hashtags must be strings.")
            tokens.extend(TAG_TOKEN_RE.findall(item))
    else:
        raise ValueError("Provide hashtags as a list or string.")
    seen = []
    seen_set = set()
    for token in tokens:
        norm = token.lower()
        if len(norm) <= 64 and norm not in seen_set:
            seen_set.add(norm)
            seen.append(norm)
    return seen


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
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL
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

    def _record_cursor(self, db, client_id, document_id, sequence):
        if client_id is None:
            return
        seq = int(sequence)
        now_ts = datetime.now(timezone.utc).timestamp()
        existing = db.execute(
            "SELECT revision, updated_at FROM device_cursors WHERE client_id=? AND document_id=?",
            (str(client_id), document_id),
        ).fetchone()
        if (
            existing is None
            or int(existing["revision"]) != seq
            or (now_ts - parse_timestamp(existing["updated_at"])) > 3600
        ):
            now_iso = datetime.fromtimestamp(now_ts, tz=timezone.utc).isoformat(timespec="microseconds")
            db.execute(
                "INSERT OR REPLACE INTO device_cursors VALUES(?,?,?,?)",
                (str(client_id), document_id, seq, now_iso),
            )

    def get_meta(self, document_id, revision=None, client_id=None):
        identifier(document_id)
        if client_id is not None:
            identifier(str(client_id))
        with closing(self.connect()) as db:
            columns = ",".join("r." + name for name in METADATA.split(","))
            if revision is None:
                row = db.execute(
                    f"SELECT {columns} FROM documents d JOIN revisions r ON d.current_revision=r.sequence WHERE d.id=?",
                    (document_id,),
                ).fetchone()
            else:
                identifier(str(revision))
                row = db.execute(
                    f"SELECT {METADATA} FROM revisions WHERE document_id=? AND sequence=?",
                    (document_id, revision),
                ).fetchone()
            if row is None:
                raise NotFound("Document or revision not found.")
            if revision is None:
                self._record_cursor(db, client_id, document_id, row["sequence"])
            return representation(row)

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
            if revision is None:
                self._record_cursor(db, client_id, document_id, row["sequence"])
            return representation(row), bytes(row["content"])

    def revisions(self, document_id):
        identifier(document_id)
        with closing(self.connect()) as db:
            if db.execute("SELECT id FROM documents WHERE id=?", (document_id,)).fetchone() is None:
                raise NotFound("Document not found.")
            return [representation(row) for row in db.execute(
                f"SELECT {METADATA} FROM revisions WHERE document_id=? ORDER BY sequence DESC", (document_id,))]

    def put(self, document_id, name, content, base_revision, request_id, encoding_hint=None, client_id=None, source=None,
            require_base_revision=False):
        identifier(document_id), identifier(request_id)
        name = filename(name)
        if base_revision is not None:
            identifier(str(base_revision))
        if not isinstance(require_base_revision, bool):
            raise ValueError("require_base_revision must be true or false.")
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
                current_revision = str(previous["sequence"]) if previous is not None else None
                if require_base_revision and current_revision != (str(base_revision) if base_revision is not None else None):
                    raise RevisionConflict("The server revision changed. Reconcile before saving again.")
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
                merged_name = name
                merged_encoding = decoded.encoding
                if (
                    rev_source != "restore"
                    and previous is not None
                    and base_revision is not None
                    and old_revision != str(base_revision)
                ):
                    base_row = db.execute(
                        "SELECT name, content, encoding FROM revisions WHERE document_id=? AND sequence=?",
                        (document_id, base_revision),
                    ).fetchone()
                    if base_row is not None:
                        base_decoded = decode(bytes(base_row["content"]), base_row["encoding"])
                        curr_decoded = decode(bytes(previous["content"]), previous["encoding"])
                        merged_name = (
                            previous["name"]
                            if (name == base_row["name"] and previous["name"] != base_row["name"])
                            else name
                        )
                        merged_encoding = (
                            previous["encoding"]
                            if (decoded.encoding == base_decoded.encoding and previous["encoding"] != base_decoded.encoding)
                            else decoded.encoding
                        )
                        merged_bom = (
                            curr_decoded.bom
                            if (decoded.bom == base_decoded.bom and curr_decoded.bom != base_decoded.bom)
                            else decoded.bom
                        )
                        if (
                            base_decoded.text != curr_decoded.text
                            and base_decoded.text != decoded.text
                            and curr_decoded.text != decoded.text
                        ):
                            merged_raw = merge_3way(
                                base_decoded,
                                curr_decoded,
                                decoded,
                                encoding=merged_encoding,
                                bom=merged_bom,
                            )
                        elif (
                            decoded.text == base_decoded.text
                            and curr_decoded.text != base_decoded.text
                            and (
                                name != base_row["name"]
                                or decoded.encoding != base_decoded.encoding
                                or decoded.bom != base_decoded.bom
                            )
                        ):
                            try:
                                merged_raw = edited(curr_decoded, curr_decoded.text, merged_encoding, merged_bom)
                            except InvalidText:
                                merged_raw = None
                        elif (
                            curr_decoded.text == base_decoded.text
                            and decoded.text != base_decoded.text
                            and (
                                previous["name"] != base_row["name"]
                                or previous["encoding"] != base_decoded.encoding
                                or curr_decoded.bom != base_decoded.bom
                            )
                        ):
                            format_base = (
                                curr_decoded
                                if (decoded.preferred == base_decoded.preferred and curr_decoded.preferred != base_decoded.preferred)
                                else decoded
                            )
                            try:
                                merged_raw = edited(format_base, decoded.text, merged_encoding, merged_bom)
                            except InvalidText:
                                merged_raw = None
                cursor = db.execute("""INSERT INTO revisions(document_id,name,content,sha256,size,encoding,created_at,source)
                                       VALUES(?,?,?,?,?,?,?,?)""", (document_id,name,content,sha256,len(content),decoded.encoding,created,rev_source))
                client_seq = cursor.lastrowid
                if merged_raw is not None:
                    merged_sha256 = hashlib.sha256(merged_raw).hexdigest()
                    merged_created = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                    merged_cursor = db.execute(
                        """INSERT INTO revisions(document_id,name,content,sha256,size,encoding,created_at,source)
                           VALUES(?,?,?,?,?,?,?,?)""",
                        (document_id, merged_name, merged_raw, merged_sha256, len(merged_raw), merged_encoding, merged_created, "merge"),
                    )
                    final_seq = merged_cursor.lastrowid
                    db.execute("UPDATE documents SET current_revision=? WHERE id=?", (final_seq, document_id))
                    response = {
                        "id": document_id,
                        "name": merged_name,
                        "revision": str(final_seq),
                        "sha256": merged_sha256,
                        "size": len(merged_raw),
                        "updated_at": merged_created,
                        "encoding": merged_encoding,
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
        cursor_rows = db.execute(
            "SELECT client_id, revision, updated_at FROM device_cursors WHERE document_id=?",
            (document_id,),
        ).fetchall()
        expired_clients = []
        for row in cursor_rows:
            updated_ts = parse_timestamp(row["updated_at"])
            if (now - updated_ts) <= CONFLICT_KEEP_SECONDS:
                try:
                    protected.add(int(row["revision"]))
                except (TypeError, ValueError):
                    pass
            else:
                expired_clients.append(row["client_id"])
        for cid in expired_clients:
            db.execute("DELETE FROM device_cursors WHERE client_id=? AND document_id=?", (cid, document_id))
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

    def get_errands(self):
        with closing(self.connect()) as db:
            row = db.execute("SELECT value, updated_at FROM settings WHERE key='errands'").fetchone()
            if row is None:
                return {"hashtags": [], "updated_at": ""}
            try:
                tags = normalize_hashtags(json.loads(row["value"]))
            except (TypeError, ValueError):
                tags = []
            return {"hashtags": tags, "updated_at": row["updated_at"]}

    def put_errands(self, hashtags):
        tags = normalize_hashtags(hashtags)
        encoded = json.dumps(tags)
        changed = False
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                existing = db.execute("SELECT value, updated_at FROM settings WHERE key='errands'").fetchone()
                if existing is not None and existing["value"] == encoded:
                    db.rollback()
                    return {"hashtags": tags, "updated_at": existing["updated_at"]}
                now_iso = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                db.execute(
                    "INSERT OR REPLACE INTO settings(key, value, updated_at) VALUES('errands', ?, ?)",
                    (encoded, now_iso),
                )
                db.commit()
                changed = True
            except BaseException:
                db.rollback()
                raise
        if changed and self.on_commit is not None:
            self.on_commit()
        return {"hashtags": tags, "updated_at": now_iso}

    def search_hashtags(self, tags=None, hide_completed=False, exclude_ids=()):
        targets = set(normalize_hashtags(tags)) if tags else set()
        excluded = {str(x) for x in (exclude_ids or ())}
        discovered = []
        discovered_set = set()
        matches = []
        with closing(self.connect()) as db:
            rows = db.execute(
                """SELECT r.sequence, r.document_id, r.name, r.content, r.encoding, r.created_at
                   FROM documents d JOIN revisions r ON d.current_revision=r.sequence
                   ORDER BY r.created_at DESC, r.sequence DESC"""
            ).fetchall()
        for row in rows:
            doc_id = row["document_id"]
            if doc_id in excluded:
                continue
            try:
                text = decode(bytes(row["content"]), row["encoding"]).text
            except InvalidText:
                continue
            for m in HASHTAG_RE.finditer(text):
                tag_norm = m.group(1).lower()
                if tag_norm not in discovered_set:
                    discovered_set.add(tag_norm)
                    discovered.append(tag_norm)
            if not targets:
                continue
            file_tags = []
            file_tags_set = set()
            line_matches = []
            offset = 0
            for idx, line in enumerate(text.split("\n"), start=1):
                if not hide_completed or not CLOSED_TASK_RE.match(line):
                    line_tags = []
                    line_tags_set = set()
                    for m in HASHTAG_RE.finditer(line):
                        found = m.group(1).lower()
                        if found in targets:
                            if found not in line_tags_set:
                                line_tags_set.add(found)
                                line_tags.append(found)
                            if found not in file_tags_set:
                                file_tags_set.add(found)
                                file_tags.append(found)
                    if line_tags:
                        line_matches.append({
                            "line_number": idx,
                            "offset": offset,
                            "text": line,
                            "matched_tags": line_tags,
                        })
                offset += len(line) + 1
            if line_matches:
                matches.append({
                    "id": doc_id,
                    "name": row["name"],
                    "revision": str(row["sequence"]),
                    "updated_at": row["created_at"],
                    "matched_tags": file_tags,
                    "lines": line_matches,
                })
        return {"all_hashtags": discovered, "results": matches}

    def remove_revision(self, document_id, revision):
        identifier(document_id), identifier(str(revision))
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
