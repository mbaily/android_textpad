"""Cross-platform, standard-library-only Textpad sync client."""
__version__ = "0.1.3"


class SyncError(Exception):
    """An actionable error safe to show in the terminal."""


class BusyFile(SyncError):
    """A file is changing or cannot be safely replaced."""
