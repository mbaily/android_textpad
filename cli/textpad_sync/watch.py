"""Foreground file polling with live remote hints and periodic reconciliation."""
from datetime import datetime
import threading
import time

from . import SyncError
from .events import ChangeEvents
from .filesystem import stamp
from .sync import Engine, Result


class Watcher:
    def __init__(self, workspace, api, *, settle=2, remote_interval=30, report=None):
        self.workspace, self.engine = workspace, Engine(workspace, api)
        self.settle, self.remote_interval = settle, remote_interval
        self.report = report if report is not None else lambda message: print(message, flush=True)
        self.observations, self.dirty, self.last_reports = {}, set(), {}
        self.remote_pending = set()
        self.remote_changed, self.wake = threading.Event(), threading.Event()
        self.next_remote = 0

    def notify(self):
        self.remote_changed.set()
        self.wake.set()

    def emit(self, result):
        value = (result.state, result.message)
        if self.last_reports.get(result.path) != value:
            self.last_reports[result.path] = value
            timestamp = datetime.now().astimezone().isoformat(timespec='seconds')
            subject = f'{result.path!r}: ' if result.path else ''
            self.report(f'{timestamp} {result.state}: {subject}{result.message}')

    def step(self, now=None):
        now = time.monotonic() if now is None else now
        with self.workspace.lock():
            rows = self.workspace.files()
            keys = {row['path'] for row in rows}
            self.dirty.intersection_update(keys)
            self.remote_pending.intersection_update(keys)
            for key in set(self.observations) - keys:
                self.observations.pop(key, None)
                self.last_reports.pop(key, None)
            due, signatures = [], {}
            notified = self.remote_changed.is_set()
            if notified:
                self.remote_changed.clear()
            remote_due = notified or now >= self.next_remote
            if remote_due:
                self.remote_pending.update(keys)
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
                    if key in self.remote_pending or key in self.dirty:
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
                    self.remote_pending.difference_update(row['path'] for row in due)

    def run(self, interval=0.5):
        with ChangeEvents(self.engine.api, self.notify, self.wake.set) as events:
            while True:
                self.wake.clear()
                status = events.status()
                if status:
                    message = ('Connected to server; live updates enabled.' if status == 'live' else
                               f'Live updates unavailable; retrying. Periodic checks every {self.remote_interval:g} seconds.')
                    self.emit(Result('', status, message))
                self.step()
                self.wake.wait(interval)
