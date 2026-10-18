"""Private per-directory SQLite state and immutable, unpruned recovery snapshots."""
from contextlib import closing, contextmanager
from datetime import datetime, timezone
import os
from pathlib import Path
import sqlite3
import uuid

from . import SyncError
from .filesystem import filename, is_link, safe_path, sha256, workspace_lock


def now():
    return datetime.now(timezone.utc).isoformat(timespec='microseconds')


class Workspace:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.folder = self.root / '.textpad'
        if not self.folder.is_dir() or is_link(self.folder):
            raise SyncError("No workspace here. Run textpad-sync init DIRECTORY --server URL first.")
        dbpath = self.folder / 'state.sqlite3'
        if not dbpath.is_file() or is_link(dbpath):
            raise SyncError("Workspace database is missing or unsafe; restore the .textpad directory from backup.")
        self.db = sqlite3.connect(dbpath, timeout=10)
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA synchronous=FULL')
        if self.db.execute('PRAGMA user_version').fetchone()[0] != 1:
            self.db.close()
            raise SyncError("Unsupported workspace schema; update the CLI without deleting .textpad.")

    def close(self):
        self.db.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    @classmethod
    def discover(cls, directory=None):
        if directory:
            return cls(directory)
        for candidate in (Path.cwd(), *Path.cwd().parents):
            if (candidate / '.textpad').is_dir():
                return cls(candidate)
        raise SyncError("No workspace found. Run textpad-sync init DIRECTORY --server URL.")

    @classmethod
    def initialise(cls, directory, server, token=None, ca_file=None):
        root = Path(directory).resolve()
        root.mkdir(parents=True, exist_ok=True)
        folder = root / '.textpad'
        try:
            folder.mkdir(mode=0o700)
        except FileExistsError as error:
            raise SyncError("This directory already contains .textpad state; it will not be overwritten.") from error
        # Keep an interrupted initialisation for inspection; never remove user data.
        if token:
            token_path = folder / 'token'
            fd = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                stream.write(token + '\n')
                stream.flush()
                os.fsync(stream.fileno())
        if ca_file:
            certificate = Path(ca_file).read_bytes()
            (folder / 'ca.pem').write_bytes(certificate)
        dbpath = folder / 'state.sqlite3'
        with closing(sqlite3.connect(dbpath)) as db:
            db.executescript('''
                CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE files (
                    path TEXT PRIMARY KEY, document_id TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
                    seed_content BLOB NOT NULL, base_name TEXT, revision TEXT, base_content BLOB,
                    pending_id TEXT, pending_name TEXT, pending_base TEXT, pending_content BLOB,
                    issue TEXT
                );
                CREATE TABLE snapshots (
                    id INTEGER PRIMARY KEY, document_id TEXT NOT NULL, path TEXT NOT NULL,
                    name TEXT NOT NULL, created_at TEXT NOT NULL, reason TEXT NOT NULL,
                    sha256 TEXT NOT NULL, content BLOB NOT NULL,
                    UNIQUE(document_id, name, sha256)
                );
                PRAGMA user_version=1;
            ''')
            db.executemany('INSERT INTO settings VALUES (?,?)', [
                ('server', server), ('client_id', 'cli-' + str(uuid.uuid4())),
                ('ca_file', 'ca.pem' if ca_file else ''),
            ])
            db.commit()
        os.chmod(dbpath, 0o600)
        return cls(root)

    @contextmanager
    def lock(self):
        with workspace_lock(self.folder):
            yield self

    def setting(self, key, *, default=...):
        row = self.db.execute('SELECT value FROM settings WHERE key=?', (key,)).fetchone()
        if row is None:
            if default is not ...:
                return default
            raise SyncError('Incomplete workspace configuration: ' + key)
        return row[0]

    def set_setting(self, key, value):
        with self.db:
            self.db.execute('INSERT OR REPLACE INTO settings VALUES (?,?)', (key, value))

    def path(self, value):
        return safe_path(self.root, value)

    def files(self):
        return self.db.execute('SELECT * FROM files ORDER BY path').fetchall()

    def file(self, value):
        # Metadata commands must still work if an editor replaced a tracked file
        # with an inaccessible path or symlink. Content I/O checks links separately.
        path, key = safe_path(self.root, value, check_links=False)
        row = self.db.execute('SELECT * FROM files WHERE path=?', (key,)).fetchone()
        if row is None:
            for existing in self.files():
                candidate = safe_path(self.root, existing['path'], check_links=False)[0]
                if os.path.normcase(str(candidate)) == os.path.normcase(str(path)):
                    row = existing
                    break
                try:
                    if path.samefile(candidate):
                        row = existing
                        break
                except FileNotFoundError:
                    pass
        if row is None:
            raise SyncError('File is not on the sync list: ' + key)
        return row

    def add(self, path, content, document=None):
        candidate, key = self.path(path)
        for existing in self.files():
            try:
                if candidate.samefile(self.path(existing['path'])[0]):
                    raise SyncError('This file is already tracked (possibly through another name or hard link).')
            except FileNotFoundError:
                pass
        name = filename(document.name if document else Path(key).name)
        docid = document.id if document else str(uuid.uuid4())
        try:
            with self.db:
                self.db.execute('''INSERT INTO files
                    (path, document_id, name, seed_content, base_name, revision, base_content)
                    VALUES (?,?,?,?,?,?,?)''', (
                    key, docid, name, content, document.name if document else None,
                    document.revision if document else None, document.content if document else None))
        except sqlite3.IntegrityError as error:
            raise SyncError('This local path or server document is already on the sync list.') from error
        row = self.file(key)
        self.snapshot(row, content, 'added-local')
        if document is not None:
            self.snapshot(row, document.content, 'linked-server', document.name)
        return row

    def update(self, row, **values):
        allowed = {'name', 'base_name', 'revision', 'base_content', 'pending_id', 'pending_name',
                   'pending_base', 'pending_content', 'issue', 'path'}
        if not values or not values.keys() <= allowed:
            raise ValueError('Invalid workspace update')
        assignments = ','.join(key + '=?' for key in values)
        with self.db:
            self.db.execute('UPDATE files SET ' + assignments + ' WHERE path=?', (*values.values(), row['path']))
        return self.file(values.get('path', row['path']))

    def remove(self, value):
        row = self.file(value)
        if row['pending_id']:
            raise SyncError('An upload is awaiting acknowledgement. Run sync before removing this file.')
        with self.db:
            self.db.execute('DELETE FROM files WHERE path=?', (row['path'],))
        return row

    def snapshot(self, row, content, reason, name=None):
        if content is None:
            return
        with self.db:
            self.db.execute('''INSERT OR IGNORE INTO snapshots
                (document_id,path,name,created_at,reason,sha256,content) VALUES (?,?,?,?,?,?,?)''', (
                row['document_id'], row['path'], name or row['name'], now(), reason, sha256(content), content))

    def baseline(self, row, document, content, adopt_name=False):
        self.snapshot(row, content, 'acknowledged', document.name)
        return self.update(row, revision=document.revision, base_content=content, base_name=document.name,
                           name=document.name if adopt_name else row['name'], issue=None,
                           pending_id=None, pending_content=None, pending_name=None, pending_base=None)

    def queue(self, row, content):
        self.snapshot(row, content, 'queued-upload')
        return self.update(row, pending_id=str(uuid.uuid4()), pending_name=row['name'],
                           pending_base=row['revision'], pending_content=content, issue=None)

    def clear_pending(self, row, issue):
        return self.update(row, pending_id=None, pending_name=None, pending_base=None, pending_content=None, issue=issue)

    def backups(self, value=None):
        if value is None:
            return self.db.execute('SELECT id,path,name,created_at,reason,sha256,length(content) AS size FROM snapshots ORDER BY id DESC').fetchall()
        row = self.file(value)
        return self.db.execute('''SELECT id,path,name,created_at,reason,sha256,length(content) AS size
            FROM snapshots WHERE document_id=? ORDER BY id DESC''', (row['document_id'],)).fetchall()

    def backup(self, snapshot_id):
        row = self.db.execute('SELECT * FROM snapshots WHERE id=?', (snapshot_id,)).fetchone()
        if row is None:
            raise SyncError('No recovery snapshot with that ID.')
        if sha256(row['content']) != row['sha256']:
            raise SyncError('Recovery snapshot checksum failed; restore the workspace database from backup.')
        return row
