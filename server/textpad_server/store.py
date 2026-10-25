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
from .models import Folder, filename, identifier

METADATA = "sequence,document_id,name,sha256,size,encoding,created_at,source"
# Folder tables/columns are additive, so existing server releases can still open
# this database during a deployment rollback, just as with the source column.
SCHEMA_VERSION = 1
UNCHANGED = object()
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
            "encoding": row["encoding"], "folder_id": row["folder_id"]}
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
            if db.execute("PRAGMA user_version").fetchone()[0] > SCHEMA_VERSION:
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
            """)
            self._migrate(db)

    @staticmethod
    def _migrate(db):
        # Serialize upgrades across workers and roll back every schema change on failure.
        db.execute("BEGIN IMMEDIATE")
        try:
            if db.execute("PRAGMA user_version").fetchone()[0] > SCHEMA_VERSION:
                raise RuntimeError("This database was created by a newer Textpad server.")
            cols = {row[1] for row in db.execute("PRAGMA table_info(revisions)")}
            if "source" not in cols:
                db.execute("ALTER TABLE revisions ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            db.execute("""CREATE TABLE IF NOT EXISTS folders (
                id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),
                name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 255),
                name_key TEXT NOT NULL,
                parent_id TEXT REFERENCES folders(id) ON DELETE RESTRICT,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                CHECK(parent_id IS NULL OR parent_id <> id)
            )""")
            cols = {row[1] for row in db.execute("PRAGMA table_info(documents)")}
            if "folder_id" not in cols:
                db.execute("ALTER TABLE documents ADD COLUMN folder_id TEXT REFERENCES folders(id) ON DELETE RESTRICT")
            db.execute("CREATE INDEX IF NOT EXISTS folders_parent ON folders(parent_id)")
            db.execute("CREATE INDEX IF NOT EXISTS documents_folder ON documents(folder_id)")
            db.execute("""CREATE UNIQUE INDEX IF NOT EXISTS folders_sibling_name
                          ON folders(coalesce(parent_id, ''), name_key)""")
            # Traverse parent links rather than materializing paths or limiting tree depth.
            for event in ("INSERT", "UPDATE OF parent_id"):
                suffix = "insert" if event == "INSERT" else "update"
                db.execute(f"""CREATE TRIGGER IF NOT EXISTS folders_no_cycles_{suffix}
                    BEFORE {event} ON folders WHEN NEW.parent_id IS NOT NULL
                    BEGIN
                        SELECT RAISE(ABORT, 'Folder hierarchy cannot contain cycles.') WHERE EXISTS (
                            WITH RECURSIVE ancestors(id) AS (
                                SELECT NEW.parent_id UNION
                                SELECT f.parent_id FROM folders f JOIN ancestors a ON f.id=a.id
                                WHERE f.parent_id IS NOT NULL
                            ) SELECT 1 FROM ancestors WHERE id=NEW.id
                        );
                    END""")
            db.execute("""CREATE TRIGGER IF NOT EXISTS folders_stable_id BEFORE UPDATE OF id ON folders
                          WHEN NEW.id <> OLD.id BEGIN SELECT RAISE(ABORT, 'Folder IDs cannot change.'); END""")
            db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
            db.commit()
        except BaseException:
            db.rollback()
            raise

    def connect(self):
        db = sqlite3.connect(self.path, timeout=20, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA synchronous=FULL")
        return db

    def list(self):
        with closing(self.connect()) as db:
            columns = ",".join("r." + name for name in METADATA.split(","))
            rows = db.execute(f"""SELECT {columns},d.folder_id FROM documents d JOIN revisions r ON d.current_revision=r.sequence
                                  ORDER BY r.created_at DESC,r.sequence DESC""").fetchall()
            return [representation(row) for row in rows]

    @staticmethod
    def _folder(db, folder_id):
        row = db.execute("SELECT * FROM folders WHERE id=?", (folder_id,)).fetchone()
        if row is None:
            raise NotFound("Folder not found.")
        return Folder.from_row(row)

    def folder(self, folder_id):
        identifier(folder_id)
        with closing(self.connect()) as db:
            return self._folder(db, folder_id)

    def folders(self, parent_id=UNCHANGED):
        with closing(self.connect()) as db:
            if parent_id is UNCHANGED:
                rows = db.execute("SELECT * FROM folders").fetchall()
            else:
                if parent_id is not None:
                    identifier(parent_id)
                    self._folder(db, parent_id)
                rows = db.execute("SELECT * FROM folders WHERE parent_id IS ?", (parent_id,)).fetchall()
        return sorted((Folder.from_row(row) for row in rows), key=lambda folder: (folder.name.casefold(), folder.id))

    def folder_path(self, folder_id):
        """Return root-to-leaf ancestors without Python recursion or stored path limits."""
        identifier(folder_id)
        with closing(self.connect()) as db:
            rows = db.execute("""WITH RECURSIVE ancestors(id) AS (
                SELECT ? UNION SELECT f.parent_id FROM folders f JOIN ancestors a ON f.id=a.id
                WHERE f.parent_id IS NOT NULL
            ) SELECT f.* FROM folders f JOIN ancestors a ON f.id=a.id""", (folder_id,)).fetchall()
        by_id = {row["id"]: Folder.from_row(row) for row in rows}
        if folder_id not in by_id:
            raise NotFound("Folder not found.")
        path, seen = [], set()
        while folder_id is not None:
            if folder_id in seen or folder_id not in by_id:
                raise ValueError("Invalid folder hierarchy.")
            seen.add(folder_id)
            folder = by_id[folder_id]
            path.append(folder)
            folder_id = folder.parent_id
        return list(reversed(path))

    def folder_contents(self, folder_id=None):
        if folder_id is not None:
            identifier(folder_id)
        with closing(self.connect()) as db:
            db.execute("BEGIN")
            folder = self._folder(db, folder_id).to_dict() if folder_id is not None else None
            children = [Folder.from_row(row) for row in db.execute("SELECT * FROM folders WHERE parent_id IS ?", (folder_id,))]
            columns = ",".join("r." + name for name in METADATA.split(","))
            documents = [representation(row) for row in db.execute(f"""SELECT {columns}, d.folder_id
                FROM documents d JOIN revisions r ON d.current_revision=r.sequence WHERE d.folder_id IS ?
                ORDER BY r.created_at DESC,r.sequence DESC""", (folder_id,))]
        children.sort(key=lambda child: (child.name.casefold(), child.id))
        return dict(folder=folder, folders=[child.to_dict() for child in children], documents=documents)

    @staticmethod
    def _library_changed(db, updated_at):
        db.execute("""INSERT INTO settings VALUES('library_version','1',?)
                      ON CONFLICT(key) DO UPDATE SET value=CAST(value AS INTEGER)+1, updated_at=excluded.updated_at""",
                   (updated_at,))

    def put_folder(self, folder_id, name, parent_id=UNCHANGED):
        identifier(folder_id)
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                existing = db.execute("SELECT * FROM folders WHERE id=?", (folder_id,)).fetchone()
                parent = (existing["parent_id"] if existing else None) if parent_id is UNCHANGED else parent_id
                now = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                folder = Folder(folder_id, name, parent, existing["created_at"] if existing else now, now)
                if parent is not None:
                    self._folder(db, parent)
                    cycle = db.execute("""WITH RECURSIVE ancestors(id) AS (
                        SELECT ? UNION SELECT f.parent_id FROM folders f JOIN ancestors a ON f.id=a.id
                        WHERE f.parent_id IS NOT NULL
                    ) SELECT 1 FROM ancestors WHERE id=?""", (parent, folder_id)).fetchone()
                    if cycle:
                        raise ValueError("A folder cannot be moved inside one of its descendants.")
                duplicate = db.execute("""SELECT 1 FROM folders WHERE parent_id IS ? AND name_key=? AND id<>?""",
                                       (parent, folder.name.casefold(), folder_id)).fetchone()
                if duplicate:
                    raise RequestConflict("A folder with that name already exists in this folder.")
                if existing and existing["name"] == folder.name and existing["parent_id"] == parent:
                    db.rollback()
                    return Folder.from_row(existing)
                db.execute("""INSERT INTO folders(id,name,name_key,parent_id,created_at,updated_at) VALUES(?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET name=excluded.name,name_key=excluded.name_key,
                        parent_id=excluded.parent_id,updated_at=excluded.updated_at""",
                           (folder.id, folder.name, folder.name.casefold(), parent, folder.created_at, now))
                self._library_changed(db, now)
                db.commit()
            except BaseException:
                db.rollback()
                raise
        if self.on_commit is not None:
            self.on_commit()
        return folder

    def delete_folder(self, folder_id):
        identifier(folder_id)
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                if db.execute("""SELECT 1 FROM folders WHERE parent_id=? UNION ALL
                                  SELECT 1 FROM documents WHERE folder_id=? LIMIT 1""", (folder_id, folder_id)).fetchone():
                    raise RequestConflict("Move this folder's files and subfolders before deleting it.")
                changed = db.execute("DELETE FROM folders WHERE id=?", (folder_id,)).rowcount
                if changed:
                    self._library_changed(db, datetime.now(timezone.utc).isoformat(timespec="microseconds"))
                db.commit()
            except BaseException:
                db.rollback()
                raise
        if changed and self.on_commit is not None:
            self.on_commit()

    def move_document(self, document_id, folder_id):
        return self.move_documents([document_id], folder_id)[0]

    def move_documents(self, document_ids, folder_id):
        if not isinstance(document_ids, list) or not document_ids:
            raise ValueError("Select at least one file to move.")
        for document_id in document_ids:
            identifier(document_id)
        if len(set(document_ids)) != len(document_ids):
            raise ValueError("Select each file only once.")
        if folder_id is not None:
            identifier(folder_id)
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                if folder_id is not None:
                    self._folder(db, folder_id)
                # Validate the whole selection before moving anything.
                existing = []
                for document_id in document_ids:
                    row = db.execute("SELECT folder_id FROM documents WHERE id=? AND current_revision IS NOT NULL", (document_id,)).fetchone()
                    if row is None:
                        raise NotFound("A selected file no longer exists. Refresh the library and retry.")
                    existing.append(row["folder_id"])
                changed = any(parent != folder_id for parent in existing)
                for document_id in document_ids:
                    db.execute("UPDATE documents SET folder_id=? WHERE id=?", (folder_id, document_id))
                if changed:
                    self._library_changed(db, datetime.now(timezone.utc).isoformat(timespec="microseconds"))
                columns = ",".join("r." + name for name in METADATA.split(","))
                response = [representation(db.execute(f"""SELECT {columns},d.folder_id FROM documents d
                    JOIN revisions r ON d.current_revision=r.sequence WHERE d.id=?""", (document_id,)).fetchone())
                    for document_id in document_ids]
                db.commit()
            except BaseException:
                db.rollback()
                raise
        if changed and self.on_commit is not None:
            self.on_commit()
        return response

    def rename_document(self, document_id, name, base_revision, request_id, client_id=None, source=None):
        if base_revision is None:
            raise ValueError("Provide the file's base_revision before renaming it.")
        metadata, raw = self.get(document_id, base_revision)
        # The conditional write rejects a concurrent content save rather than
        # putting older saved bytes back. Existing receipts make retries safe.
        return self.put(document_id, name, raw, base_revision, request_id, metadata["encoding"],
                        client_id=client_id, source=source, require_base_revision=True)

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
                    f"SELECT {columns},d.folder_id FROM documents d JOIN revisions r ON d.current_revision=r.sequence WHERE d.id=?",
                    (document_id,),
                ).fetchone()
            else:
                identifier(str(revision))
                row = db.execute(
                    f"SELECT {columns},d.folder_id FROM revisions r JOIN documents d ON d.id=r.document_id WHERE r.document_id=? AND r.sequence=?",
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
                row = db.execute("""SELECT r.*,d.folder_id FROM documents d JOIN revisions r ON d.current_revision=r.sequence
                                    WHERE d.id=?""", (document_id,)).fetchone()
            else:
                identifier(str(revision))
                row = db.execute("""SELECT r.*,d.folder_id FROM revisions r JOIN documents d ON d.id=r.document_id
                                    WHERE r.document_id=? AND r.sequence=?""", (document_id, revision)).fetchone()
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
            columns = ",".join("r." + name for name in METADATA.split(","))
            return [representation(row) for row in db.execute(
                f"""SELECT {columns},d.folder_id FROM revisions r JOIN documents d ON d.id=r.document_id
                    WHERE r.document_id=? ORDER BY r.sequence DESC""", (document_id,))]

    def put(self, document_id, name, content, base_revision, request_id, encoding_hint=None, client_id=None, source=None,
            require_base_revision=False, folder_id=UNCHANGED):
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
        request_bytes = name.encode() + b"\0" + content
        if folder_id is not UNCHANGED and folder_id is not None:
            identifier(folder_id)
            request_bytes = b"\0folder\0" + json.dumps([name, folder_id]).encode() + b"\0" + content
        request_hash = hashlib.sha256(request_bytes).hexdigest()
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                receipt = db.execute("SELECT * FROM receipts WHERE document_id=? AND request_id=?", (document_id, request_id)).fetchone()
                if receipt is not None:
                    if receipt["request_hash"] != request_hash:
                        raise RequestConflict("This request ID was already used for different contents, a different filename, or a different initial folder.")
                    db.rollback()
                    return json.loads(receipt["response"])
                previous = db.execute(
                    """SELECT r.sequence, r.name, r.content, r.sha256, r.size, r.encoding, r.created_at, r.source, d.folder_id
                       FROM documents d JOIN revisions r ON d.current_revision=r.sequence
                       WHERE d.id=?""",
                    (document_id,),
                ).fetchone()
                current_revision = str(previous["sequence"]) if previous is not None else None
                target_folder = previous["folder_id"] if previous else (None if folder_id is UNCHANGED else folder_id)
                if previous and folder_id is not UNCHANGED and folder_id != target_folder:
                    raise RequestConflict("Use the folder move action to change an existing file's folder.")
                if previous is None and target_folder is not None:
                    self._folder(db, target_folder)
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
                        "folder_id": previous["folder_id"],
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
                db.execute("INSERT OR IGNORE INTO documents(id,folder_id) VALUES (?,?)", (document_id, target_folder))
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
                response["folder_id"] = target_folder
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
            groups = db.execute("SELECT value FROM settings WHERE key='tag_groups_version'").fetchone()
            library = db.execute("SELECT value FROM settings WHERE key='library_version'").fetchone()
            # Keep the numeric event contract used by older CLI clients.
            return str((row[0] if row else 0) + (int(groups[0]) if groups else 0) + (int(library[0]) if library else 0))

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

    def tag_groups(self):
        with closing(self.connect()) as db:
            rows = db.execute("SELECT key, value, updated_at FROM settings WHERE key LIKE 'tag-group:%'").fetchall()
        groups = [dict(id=row["key"][10:], **json.loads(row["value"]), updated_at=row["updated_at"]) for row in rows]
        return sorted(groups, key=lambda group: (group["name"].casefold(), group["id"]))

    def put_tag_group(self, group_id, name, hashtags):
        identifier(group_id)
        if not isinstance(name, str):
            raise ValueError("Give the tag group a name.")
        name = unicodedata.normalize("NFC", name.strip())
        if not name or len(name) > 100 or any(unicodedata.category(c) == "Cc" for c in name):
            raise ValueError("Use a tag group name of 1–100 characters without control characters.")
        if name.casefold() == "errands":
            raise ValueError("Errands is already a built-in tag group. Choose another name.")
        name.encode("utf-8", errors="strict")
        tags = normalize_hashtags(hashtags)
        encoded = json.dumps(dict(name=name, hashtags=tags))
        key = "tag-group:" + group_id
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                for row in db.execute("SELECT key, value FROM settings WHERE key LIKE 'tag-group:%'"):
                    if row["key"] != key and json.loads(row["value"])["name"].casefold() == name.casefold():
                        raise RequestConflict("A tag group with that name already exists.")
                existing = db.execute("SELECT value, updated_at FROM settings WHERE key=?", (key,)).fetchone()
                if existing and existing["value"] == encoded:
                    db.rollback()
                    return dict(id=group_id, name=name, hashtags=tags, updated_at=existing["updated_at"])
                updated_at = datetime.now(timezone.utc).isoformat(timespec="microseconds")
                db.execute("INSERT OR REPLACE INTO settings VALUES(?,?,?)", (key, encoded, updated_at))
                self._tag_groups_changed(db, updated_at)
                db.commit()
            except BaseException:
                db.rollback()
                raise
        if self.on_commit is not None:
            self.on_commit()
        return dict(id=group_id, name=name, hashtags=tags, updated_at=updated_at)

    @staticmethod
    def _tag_groups_changed(db, updated_at):
        db.execute("""INSERT INTO settings VALUES('tag_groups_version','1',?)
                      ON CONFLICT(key) DO UPDATE SET value=CAST(value AS INTEGER)+1, updated_at=excluded.updated_at""",
                   (updated_at,))

    def delete_tag_group(self, group_id):
        identifier(group_id)
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                changed = db.execute("DELETE FROM settings WHERE key=?", ("tag-group:" + group_id,)).rowcount
                if changed:
                    self._tag_groups_changed(db, datetime.now(timezone.utc).isoformat(timespec="microseconds"))
                db.commit()
            except BaseException:
                db.rollback()
                raise
        if changed and self.on_commit is not None:
            self.on_commit()

    def search_hashtags(self, tags=None, hide_completed=False, exclude_ids=()):
        targets = set(normalize_hashtags(tags)) if tags else set()
        excluded = {str(x) for x in (exclude_ids or ())}
        discovered = []
        discovered_set = set()
        matches = []
        with closing(self.connect()) as db:
            rows = db.execute(
                """SELECT r.sequence, r.document_id, r.name, r.content, r.encoding, r.created_at, d.folder_id
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
                    "folder_id": row["folder_id"],
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
