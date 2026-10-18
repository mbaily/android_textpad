"""Stable byte reads and guarded atomic writes; no text/newline conversion."""
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import re
import stat
import tempfile
import time
import unicodedata

from . import BusyFile, SyncError
from .api import MAX_BYTES


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def filename(name):
    name = unicodedata.normalize('NFC', name.strip())
    if not name or name in ('.', '..') or len(name) > 255 or re.search(r'[/\\\x00-\x1f\x7f]', name):
        raise SyncError("Use a filename of 1–255 characters without slashes or control characters.")
    try:
        name.encode('utf-8', errors='strict')
    except UnicodeError as error:
        raise SyncError('Filenames must contain valid Unicode characters.') from error
    return name


def text(raw):
    if len(raw) > MAX_BYTES:
        raise SyncError("Files larger than 10 MiB are not supported.")
    encoding, skip = 'utf-8', 0
    if raw.startswith((b'\xff\xfe\0\0', b'\0\0\xfe\xff')):
        raise SyncError("UTF-32 is not supported; use UTF-8 or UTF-16.")
    for prefix, name in [(b'\xef\xbb\xbf', 'utf-8'), (b'\xff\xfe', 'utf-16-le'), (b'\xfe\xff', 'utf-16-be')]:
        if raw.startswith(prefix):
            encoding, skip = name, len(prefix)
            break
    else:
        if len(raw) >= 4 and len(raw) % 2 == 0:
            sample = raw[:1024]
            even, odd = sample[::2].count(0), sample[1::2].count(0)
            if odd > len(sample) // 4 and even == 0:
                encoding = 'utf-16-le'
            elif even > len(sample) // 4 and odd == 0:
                encoding = 'utf-16-be'
    try:
        value = raw[skip:].decode(encoding, errors='strict')
    except UnicodeError as error:
        raise SyncError("Invalid text encoding; use UTF-8 or UTF-16 with a BOM.") from error
    if re.search(r'[\x00-\x08\x0b\x0e-\x1f\x7f]', value):
        raise SyncError("Binary files/control characters are not supported.")
    return value


def deletion_reason(before, after):
    if before is None or before == after:
        return None
    old, new = text(before), text(after)
    old_chars, new_chars = len(old.strip()), len(new.strip())
    if old_chars and not new_chars:
        return 'would replace nonblank content with an empty/whitespace-only file'
    if old_chars >= 100 and new_chars < old_chars * 0.25:
        return 'would remove more than 75% of the text'
    old_lines, new_lines = len(old.splitlines()), len(new.splitlines())
    if old_lines >= 20 and new_lines < old_lines * 0.25:
        return 'would remove more than 75% of the lines'
    return None


def stamp(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def is_link(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, 'st_file_attributes', 0) & 0x400)


def safe_path(root, value, *, check_links=True):
    candidate = Path(value)
    if not candidate.is_absolute():
        candidate = root / candidate
    # abspath normalises .. without first following symlinks.
    candidate = Path(os.path.abspath(candidate))
    try:
        relative = candidate.relative_to(root)
    except ValueError as error:
        raise SyncError("Files must be inside the workspace directory.") from error
    if not relative.parts or relative.parts[0].casefold() == '.textpad':
        raise SyncError("The workspace root and .textpad state directory cannot be synced.")
    try:
        relative.as_posix().encode('utf-8', errors='strict')
    except UnicodeError as error:
        raise SyncError('Paths must contain valid Unicode characters.') from error
    if os.name == 'nt' and any(
        part.endswith((' ', '.')) or ':' in part
        or re.fullmatch(r'(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?', part)
        for part in relative.parts
    ):
        raise SyncError('Reserved Windows filenames and alternate data streams cannot be synced.')
    current = root
    for part in relative.parts if check_links else ():
        current = current / part
        try:
            if is_link(current):
                raise SyncError("Symlinks and Windows reparse points cannot be synced.")
        except FileNotFoundError:
            pass
    return candidate, relative.as_posix()


@dataclass(frozen=True)
class Snapshot:
    content: bytes
    signature: tuple
    mode: int

    @property
    def digest(self):
        return sha256(self.content)


def read(path):
    if is_link(path):
        raise SyncError("Symlinks cannot be synced.")
    if not stat.S_ISREG(path.lstat().st_mode):
        raise SyncError("Only regular files can be synced.")
    flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0)
    with os.fdopen(os.open(path, flags), 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode):
            raise SyncError("Only regular files can be synced.")
        if before.st_size > MAX_BYTES:
            raise SyncError("Files larger than 10 MiB are not supported.")
        raw = stream.read(MAX_BYTES + 1)
        after = os.fstat(stream.fileno())
    if stamp(before) != stamp(after) or stamp(after) != stamp(path.stat()) or len(raw) != after.st_size:
        raise BusyFile("File changed while being read; waiting for the editor to finish saving.")
    text(raw)
    return Snapshot(raw, stamp(after), stat.S_IMODE(after.st_mode))


def settled_read(path, seconds=1):
    first = read(path)
    if seconds:
        time.sleep(seconds)
    second = read(path)
    if first.signature != second.signature or first.content != second.content:
        raise BusyFile("File is still changing; sync again after the editor finishes saving.")
    return second


def flush_directory(path):
    if os.name != 'nt':
        try:
            fd = os.open(path, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        except OSError:
            pass  # Some network filesystems do not support directory fsync.


def write(path, content, expected=None):
    """Create without clobbering, or replace only a still-matching saved file.

    Caller must durably back up expected.content before replacing. No portable
    filesystem operation can compare-and-replace against an uncooperative editor.
    """
    text(content)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.textpad-', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if expected is None:
            # O_EXCL also protects dangling symlinks and concurrent file creation.
            out_fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_BINARY', 0), 0o600)
            with os.fdopen(out_fd, 'wb') as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
        else:
            os.chmod(temporary, expected.mode)
            current = read(path)
            if current.signature != expected.signature or current.content != expected.content:
                raise BusyFile("Local file changed during download; replacement deferred.")
            os.replace(temporary, path)
        flush_directory(path.parent)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


@contextmanager
def workspace_lock(folder, timeout=10):
    path = folder / 'lock'
    with path.open('a+b') as stream:
        if stream.tell() == 0:
            stream.write(b'\0')
            stream.flush()
        deadline = time.monotonic() + timeout
        while True:
            try:
                stream.seek(0)
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise SyncError("Another command is using this workspace; retry shortly or stop watch with Ctrl+C.") from None
                time.sleep(0.05)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
