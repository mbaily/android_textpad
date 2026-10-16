"""SQLite revisions and idempotent write receipts, committed in one transaction."""
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3

from .codec import decode

METADATA = "sequence,document_id,name,sha256,size,encoding,created_at"


class NotFound(ValueError):
    pass


class RequestConflict(ValueError):
    pass


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise ValueError("Invalid document, revision, or request identifier.")
    return value


def filename(value):
    if not isinstance(value, str) or not value.strip() or len(value) > 255 or value in {".", ".."} \
            or re.search(r"[/\\\x00-\x1f\x7f]", value):
        raise ValueError("Use a filename of 1–255 characters without slashes or control characters.")
    value.encode("utf-8", errors="strict")
    return value


def representation(row):
    return {"id": row["document_id"], "name": row["name"], "revision": str(row["sequence"]),
            "sha256": row["sha256"], "size": row["size"], "updated_at": row["created_at"],
            "encoding": row["encoding"]}


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
                    encoding TEXT NOT NULL, created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS revisions_document ON revisions(document_id, sequence DESC);
                CREATE TABLE IF NOT EXISTS receipts (
                    document_id TEXT NOT NULL, request_id TEXT NOT NULL,
                    request_hash TEXT NOT NULL, response TEXT NOT NULL,
                    PRIMARY KEY (document_id, request_id)
                );
                CREATE TABLE IF NOT EXISTS login_attempts (
                    address TEXT PRIMARY KEY, failures INTEGER NOT NULL, started REAL NOT NULL
                );
                PRAGMA user_version=1;
            """)

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

    def get(self, document_id, revision=None):
        identifier(document_id)
        with closing(self.connect()) as db:
            if revision is None:
                row = db.execute("""SELECT r.* FROM documents d JOIN revisions r ON d.current_revision=r.sequence
                                    WHERE d.id=?""", (document_id,)).fetchone()
            else:
                identifier(str(revision))
                row = db.execute("SELECT * FROM revisions WHERE document_id=? AND sequence=?", (document_id, revision)).fetchone()
            if row is None:
                raise NotFound("Document or revision not found.")
            return representation(row), bytes(row["content"])

    def revisions(self, document_id):
        identifier(document_id)
        with closing(self.connect()) as db:
            if db.execute("SELECT id FROM documents WHERE id=?", (document_id,)).fetchone() is None:
                raise NotFound("Document not found.")
            return [representation(row) for row in db.execute(
                f"SELECT {METADATA} FROM revisions WHERE document_id=? ORDER BY sequence DESC", (document_id,))]

    def put(self, document_id, name, content, base_revision, request_id, encoding_hint=None):
        identifier(document_id), identifier(request_id), filename(name)
        if base_revision is not None:
            identifier(str(base_revision))
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
                previous = db.execute("SELECT current_revision FROM documents WHERE id=?", (document_id,)).fetchone()
                db.execute("INSERT OR IGNORE INTO documents(id) VALUES (?)", (document_id,))
                created = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                cursor = db.execute("""INSERT INTO revisions(document_id,name,content,sha256,size,encoding,created_at)
                                       VALUES(?,?,?,?,?,?,?)""", (document_id,name,content,sha256,len(content),decoded.encoding,created))
                revision = str(cursor.lastrowid)
                db.execute("UPDATE documents SET current_revision=? WHERE id=?", (cursor.lastrowid, document_id))
                old_revision = str(previous[0]) if previous and previous[0] is not None else None
                response = {"id":document_id,"name":name,"revision":revision,"sha256":sha256,"size":len(content),
                            "updated_at":created,"encoding":decoded.encoding,"previous_revision":old_revision,
                            "replaced_newer_revision":old_revision is not None and old_revision != base_revision}
                db.execute("INSERT INTO receipts VALUES(?,?,?,?)", (document_id,request_id,request_hash,json.dumps(response)))
                db.commit()
            except BaseException:
                db.rollback()
                raise
        if self.on_commit is not None:
            self.on_commit()
        return response

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
