"""Coalesced commit signals, shared by Gunicorn workers without polling SQLite."""
import os
import select
import threading


class Subscription:
    def __init__(self, changes):
        self.changes = changes
        self.closed = False
        with changes.condition:
            self.generation = changes.generation
            changes.subscriptions.add(self)

    def wait(self):
        changes = self.changes
        with changes.condition:
            changes.condition.wait_for(lambda: self.closed or changes.generation != self.generation)
            if self.closed:
                return False
            self.generation = changes.generation
            return True

    def close(self):
        with self.changes.condition:
            self.closed = True
            self.changes.subscriptions.discard(self)
            self.changes.condition.notify_all()


class ChangeSignal:
    def __init__(self):
        self.channels = []
        self.listener = None
        self.closed = False
        self.condition = threading.Condition()
        self.generation = 0
        self.subscriptions = set()

    def prepare_workers(self, workers):
        """Run in the preloaded master, before forking. Spare slots allow replacement workers."""
        if self.channels:
            raise RuntimeError("Commit signals were already prepared")
        self.channels = [os.eventfd(0, os.EFD_CLOEXEC | os.EFD_NONBLOCK) for _ in range(workers * 2)]

    def start_worker(self, slot):
        # These locks are deliberately process-local. A killed worker cannot leave
        # a shared Python lock held and prevent other workers from being notified.
        self.condition = threading.Condition()
        self.subscriptions = set()
        self.generation = 0
        self.closed = False
        channel = self.channels[slot]

        def listen():
            while True:
                select.select([channel], [], [])  # Blocks until a commit or shutdown signal.
                try:
                    count = os.eventfd_read(channel)
                except BlockingIOError:
                    continue
                if self.closed:
                    break
                self._wake(count)

        self.channel = channel
        self.listener = threading.Thread(target=listen, name="textpad-commit-listener", daemon=True)
        self.listener.start()

    def _wake(self, count=1):
        with self.condition:
            self.generation += count
            self.condition.notify_all()

    def publish(self):
        if not self.channels:
            self._wake()  # Single-process development server and isolated tests.
            return
        for channel in self.channels:
            try:
                os.eventfd_write(channel, 1)
            except BlockingIOError:
                pass  # A saturated counter is already readable: a refresh is pending.

    def subscribe(self):
        return Subscription(self)

    def stop_worker(self):
        self.closed = True
        with self.condition:
            for subscription in tuple(self.subscriptions):
                subscription.close()
        if self.listener is not None:
            os.eventfd_write(self.channel, 1)
            self.listener.join(timeout=2)
            self.listener = None

    def close(self):
        self.stop_worker()
        for channel in self.channels:
            os.close(channel)
        self.channels = []
