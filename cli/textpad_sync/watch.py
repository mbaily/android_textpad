"""Foreground polling monitor; works without OS-specific watcher dependencies."""
from datetime import datetime
import time

from . import SyncError
from .filesystem import stamp
from .sync import Engine, Result


class Watcher:
    def __init__(self, workspace, api, *, settle=2, remote_interval=30, report=None):
        self.workspace, self.engine = workspace, Engine(workspace, api)
        self.settle, self.remote_interval = settle, remote_interval
        self.report = report if report is not None else lambda message: print(message, flush=True)
        self.observations, self.dirty, self.last_reports = {}, set(), {}
        self.next_remote = 0

    def emit(self, result):
        value = (result.state, result.message)
        if self.last_reports.get(result.path) != value:
            self.last_reports[result.path] = value
            timestamp = datetime.now().astimezone().isoformat(timespec='seconds')
            self.report(f'{timestamp} {result.state}: {result.path!r}: {result.message}')

    def step(self, now=None):
        now = time.monotonic() if now is None else now
        with self.workspace.lock():
            rows = self.workspace.files()
            keys = {row['path'] for row in rows}
            self.dirty.intersection_update(keys)
            for key in set(self.observations) - keys:
                self.observations.pop(key, None)
                self.last_reports.pop(key, None)
            due, signatures = [], {}
            remote_due = now >= self.next_remote
            for row in rows:
                key = row['path']
                try:
                    path, _ = self.workspace.path(key)
                    signature = stamp(path.stat())
                except FileNotFoundError:
                    signature = ('missing',)
                    self.emit(Result(key, 'missing', 'Local file is missing; no deletion was propagated.'))
                except (SyncError, OSError) as error:
                    signature = ('unavailable', str(error))
                    self.emit(Result(key, 'waiting', str(error)))
                previous = self.observations.get(key)
                if previous is None or previous[0] != signature:
                    self.observations[key] = (signature, now)
                    self.dirty.add(key)
                since = self.observations[key][1]
                if now - since >= self.settle and signature[0] not in ('missing', 'unavailable'):
                    if remote_due or key in self.dirty:
                        due.append(row)
                        signatures[key] = signature
            if remote_due:
                self.next_remote = now + self.remote_interval
            if due:
                try:
                    for result in self.engine.sync(due, settle=0, expected_signatures=signatures):
                        self.emit(result)
                    self.last_reports.pop('', None)
                except (SyncError, OSError) as error:
                    self.emit(Result('', 'offline', str(error)))
                    self.next_remote = now + self.remote_interval
                finally:
                    self.dirty.difference_update(row['path'] for row in due)

    def run(self, interval=0.5):
        while True:
            self.step()
            time.sleep(interval)
