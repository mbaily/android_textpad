import argparse
from datetime import datetime, timezone
import os
from pathlib import Path

from .store import Store


def main():
    parser = argparse.ArgumentParser(description="Textpad database administration")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init")
    backup = sub.add_parser("backup")
    backup.add_argument("--directory", type=Path, default=Path("/var/lib/android-textpad/backups"))
    remove = sub.add_parser("remove-revision")
    remove.add_argument("document_id")
    remove.add_argument("revision")
    args = parser.parse_args()
    store = Store(os.environ.get("TEXTPAD_DATABASE", "/var/lib/android-textpad/textpad.sqlite3"))
    if args.command == "backup":
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        destination = args.directory / f"textpad-{stamp}.sqlite3"
        store.backup(destination)
        print(destination)
    elif args.command == "remove-revision":
        store.remove_revision(args.document_id,args.revision)
        print("Removed historical revision; upload receipt retained.")
    else:
        print("Database initialized.")


if __name__ == "__main__":
    main()
