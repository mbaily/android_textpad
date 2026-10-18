"""Terminal commands. Local files are never edited inside this application."""
import argparse
from dataclasses import asdict
import getpass
import json
import math
import os
from pathlib import Path
import sqlite3
import sys

from . import SyncError, __version__
from .api import Api, endpoint, identifier
from .errands import run as run_errands
from .filesystem import filename, is_link, read, settled_read, write
from .hashtags import tags_from_file
from .storage import Workspace
from .sync import Engine
from .watch import Watcher

DEFAULT_SERVER = 'https://git23069.hopto.org/android_textpad'


def seconds(value):
    try:
        number = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError('Use a number of seconds.') from error
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError('Use a positive, finite number of seconds.')
    return number


def parser():
    p = argparse.ArgumentParser(prog='textpad-sync', description='Sync a directory of local text files with a Textpad server.')
    p.add_argument('--version', action='version', version=__version__)
    p.add_argument('-C', '--directory', help='Workspace directory (otherwise discover from current directory/parents).')
    insecure_help = 'Disable HTTPS certificate and hostname verification for this invocation.'
    p.add_argument('--insecure', action='store_true', help=insecure_help)
    commands = p.add_subparsers(dest='command', required=True)
    init = commands.add_parser('init', help='Create a private SQLite workspace and configure server access.')
    init.add_argument('root', nargs='?')
    init.add_argument('--server', default=DEFAULT_SERVER)
    init.add_argument('--token-file', type=Path, help='Copy the token from this file; otherwise use TEXTPAD_TOKEN or a hidden prompt.')
    init.add_argument('--ca-file', type=Path, help='Trust this PEM CA file for this workspace.')
    remote = commands.add_parser('remote', help='List server document IDs, names, and revisions.')
    remote.add_argument('--json', action='store_true')
    add = commands.add_parser('add', help='Add existing local files to the sync list; upload on the next sync/watch.')
    add.add_argument('files', nargs='+')
    add.add_argument('--document', help='Link one local file to an existing server document ID.')
    add.add_argument('--name', help='Server display name for one new local file.')
    get = commands.add_parser('get', help='Download a current server document to a new local file and track it.')
    get.add_argument('document', type=identifier)
    get.add_argument('path', nargs='?', help='Destination relative to workspace; defaults to the server filename.')
    remove = commands.add_parser('remove', help='Stop syncing files; keep local files, server documents, and backups.')
    remove.add_argument('files', nargs='+')
    status = commands.add_parser('status', help='Show the sync list and local state without accessing the server.')
    status.add_argument('--json', action='store_true')
    sync = commands.add_parser('sync', help='Safely sync all tracked files, or only the named files.')
    sync.add_argument('files', nargs='*')
    sync.add_argument('--settle', type=seconds, default=1.0)
    sync.add_argument('--allow-large-delete', action='store_true', help='Accept a held deletion for the explicitly named files.')
    watch = commands.add_parser('watch', help='Monitor saved files in this terminal until Ctrl+C.')
    watch.add_argument('--settle', type=seconds, default=2.0, help='Seconds a file must remain unchanged before sync (default: 2).')
    watch.add_argument('--interval', type=seconds, default=0.5, help='Local file check interval (default: 0.5 seconds).')
    watch.add_argument('--remote-interval', type=seconds, default=30.0, help='Periodic server reconciliation interval; live notifications also trigger sync (default: 30 seconds).')
    resolve = commands.add_parser('resolve', help='Choose a conflict version; first back up both saved copies.')
    resolve.add_argument('file')
    resolve.add_argument('--use', choices=['local', 'remote'], required=True)
    resolve.add_argument('--allow-large-delete', action='store_true')
    rename = commands.add_parser('rename', help='Change a server display name on the next sync; local path stays the same.')
    rename.add_argument('file')
    rename.add_argument('name', type=filename)
    relocate = commands.add_parser('relocate', help='Update tracking after you move/rename a local file yourself.')
    relocate.add_argument('old')
    relocate.add_argument('new')
    history = commands.add_parser('history', help='List server revisions for a tracked path or document ID.')
    history.add_argument('target')
    history.add_argument('--json', action='store_true')
    download = commands.add_parser('download', help='Download bytes to a new, untracked file for inspection.')
    download.add_argument('document', type=identifier)
    download.add_argument('path')
    download.add_argument('--revision', type=identifier)
    backups = commands.add_parser('backups', help='List retained local recovery snapshots (including removed mappings).')
    backups.add_argument('file', nargs='?')
    backups.add_argument('--json', action='store_true')
    recover = commands.add_parser('recover', help='Recover a local snapshot to a new file; existing files are never overwritten.')
    recover.add_argument('snapshot', type=int)
    recover.add_argument('--to', required=True)
    errands = commands.add_parser('errands', help='Search saved local files and server documents using shared errands hashtags.')
    modes = errands.add_mutually_exclusive_group()
    modes.add_argument('--lines', dest='mode', action='store_const', const='lines', help='Show complete matching lines (default).')
    modes.add_argument('--files', dest='mode', action='store_const', const='files', help='Show matching files with tags and line counts.')
    closed = errands.add_mutually_exclusive_group()
    closed.add_argument('--hide-completed', dest='hide_completed', action='store_true', help='Hide [X]/[C] task lines (default).')
    closed.add_argument('--show-completed', dest='hide_completed', action='store_false', help='Include [X]/[C] task lines.')
    errands.set_defaults(mode='lines', hide_completed=True)
    errands.add_argument('--json', action='store_true')
    errands.add_argument('--offline', action='store_true', help='Use cached errands hashtags and saved tracked files only.')
    errands_commands = errands.add_subparsers(dest='errands_command')
    tags = errands_commands.add_parser('tags', help='View or edit the shared errands hashtags.')
    edit = tags.add_mutually_exclusive_group()
    edit.add_argument('--set', nargs='+', metavar='TAG', help='Replace the list (accepts space/comma-separated tags).')
    edit.add_argument('--add', nargs='+', metavar='TAG', help='Add tags to the list.')
    edit.add_argument('--remove', nargs='+', metavar='TAG', help='Remove tags from the list.')
    edit.add_argument('--set-file', dest='set', type=tags_from_file, metavar='FILE', help='Replace the list from a UTF-8 file, one hashtag per line.')
    edit.add_argument('--add-file', dest='add', type=tags_from_file, metavar='FILE', help='Add tags from a UTF-8 file, one hashtag per line.')
    edit.add_argument('--remove-file', dest='remove', type=tags_from_file, metavar='FILE', help='Remove tags from a UTF-8 file, one hashtag per line.')
    edit.add_argument('--clear', action='store_true', help='Clear the saved list.')
    edit.add_argument('--available', action='store_true', help='List discovered hashtags, including those on [X]/[C] tasks.')
    tags.add_argument('--json', action='store_true', default=argparse.SUPPRESS)
    tags.add_argument('--offline', action='store_true', default=argparse.SUPPRESS, help='View/edit cached settings or discover tags in local files.')
    tags.add_argument('--insecure', action='store_true', default=argparse.SUPPRESS, help=insecure_help)
    for command in commands.choices.values():
        # Keep a global --insecure value when a subcommand does not repeat it.
        command.add_argument('--insecure', action='store_true', default=argparse.SUPPRESS, help=insecure_help)
    return p


def output(rows, fields, *, as_json=False):
    if as_json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return
    if not rows:
        print('No files.')
        return
    print('\t'.join(fields))
    for row in rows:
        # Escape control characters without concealing ordinary Unicode filenames.
        print('\t'.join(json.dumps(str(row.get(field, '') or ''), ensure_ascii=False)[1:-1] for field in fields))


def api_for(ws, *, insecure=False):
    token = os.environ.get('TEXTPAD_TOKEN', '').strip()
    if not token:
        token_path = ws.folder / 'token'
        if token_path.exists() and is_link(token_path):
            raise SyncError('Workspace token file must not be a symlink.')
        try:
            token = token_path.read_text(encoding='utf-8').strip()
        except FileNotFoundError as error:
            raise SyncError('Set TEXTPAD_TOKEN, or put the API token in .textpad/token (keep it private).') from error
    ca = ws.setting('ca_file')
    return Api(ws.setting('server'), token, ws.setting('client_id'), ws.folder / ca if ca else None, insecure=insecure)


def initialise(args):
    root = args.root or args.directory or '.'
    if (Path(root).resolve() / '.textpad').exists():
        raise SyncError('This directory already contains .textpad state; it will not be overwritten.')
    server = endpoint(args.server)
    env_token = os.environ.get('TEXTPAD_TOKEN', '').strip()
    try:
        token = args.token_file.read_text(encoding='utf-8').strip() if args.token_file else env_token or getpass.getpass('API token: ').strip()
    except EOFError as error:
        raise SyncError('No token was entered. Use TEXTPAD_TOKEN or init --token-file for noninteractive setup.') from error
    # Check access before creating any local state. The token is never put in argv,
    # URLs, a process title, output, or the SQLite database.
    api = Api(server, token, 'cli-setup', args.ca_file, insecure=args.insecure)
    api.capabilities()
    api.list()
    with Workspace.initialise(root, server, token if args.token_file or not env_token else None, args.ca_file) as ws:
        print('Initialised workspace: ' + str(ws.root))
        print('Use add for existing local files, or remote then get DOCUMENT_ID for server files.')
    return 0


def local_status(ws):
    results = []
    for row in ws.files():
        try:
            current = read(ws.path(row['path'])[0])
            if row['pending_id']:
                state = 'pending-upload'
            elif row['base_content'] is None:
                state = 'new'
            elif current.content != row['base_content'] or row['name'] != row['base_name']:
                state = 'modified'
            else:
                state = 'synced'
        except FileNotFoundError:
            state = 'missing'
        except (SyncError, OSError):
            state = 'unavailable'
        results.append({'path': row['path'], 'state': state, 'document_id': row['document_id'],
                        'revision': row['revision'], 'last_issue': row['issue']})
    return results


def print_result(result):
    print(f'{result.state}: {result.path!r}: {result.message}', flush=True)


def dispatch(ws, args):
    command = args.command
    if command == 'errands':
        api = None if args.offline else api_for(ws, insecure=args.insecure)
        return run_errands(ws, api, args)
    if command == 'status':
        output(local_status(ws), ['path', 'state', 'document_id', 'revision', 'last_issue'], as_json=args.json)
        return 0
    if command == 'backups':
        output([dict(row) for row in ws.backups(args.file)], ['id', 'path', 'created_at', 'reason', 'size'], as_json=args.json)
        return 0
    if command == 'recover':
        snapshot = ws.backup(args.snapshot)
        path, _ = ws.path(args.to)
        write(path, snapshot['content'])
        print('Recovered snapshot to: ' + str(path))
        return 0
    if command == 'remove':
        rows = [ws.file(value) for value in args.files]
        if any(row['pending_id'] for row in rows):
            raise SyncError('Finish pending uploads with sync before removing these mappings.')
        for row in rows:
            ws.remove(row['path'])
            print('Removed from sync list: ' + repr(row['path']))
        return 0
    if command == 'rename':
        row = ws.file(args.file)
        ws.update(row, name=args.name)
        print('Server name will change on next sync: ' + repr(args.name))
        return 0
    if command == 'relocate':
        row = ws.file(args.old)
        old_path, _ = ws.path(args.old)
        new_path, new_key = ws.path(args.new)
        if old_path.exists():
            raise SyncError('Move the local file yourself first; the old path still exists.')
        settled_read(new_path, 0.1)
        ws.update(row, path=new_key)
        print('Tracking the moved local file: ' + repr(new_key))
        return 0
    api = api_for(ws, insecure=args.insecure) if command != 'add' or args.document else None
    if command == 'remote':
        output([asdict(doc) | {'content': None} for doc in api.list()], ['id', 'name', 'revision', 'size'], as_json=args.json)
    elif command == 'add':
        if (args.document or args.name) and len(args.files) != 1:
            raise SyncError('--document/--name require exactly one local file.')
        if args.document and args.name:
            raise SyncError('Link with --document first; use rename separately for a server display-name change.')
        remote = api.get(identifier(args.document)) if args.document else None
        for value in args.files:
            path, key = ws.path(value)
            local = settled_read(path, 0.1)
            row = ws.add(key, local.content, remote)
            if args.name:
                ws.update(row, name=filename(args.name))
            print('Added: ' + repr(key) + ' -> ' + row['document_id'])
    elif command == 'get':
        remote = api.get(args.document)
        if any(row['document_id'] == remote.id for row in ws.files()):
            raise SyncError('This server document is already on the sync list.')
        path, key = ws.path(args.path if args.path else filename(remote.name))
        write(path, remote.content)
        ws.add(key, remote.content, remote)
        print('Downloaded and tracking: ' + repr(key))
    elif command == 'download':
        remote = api.get(args.document, args.revision)
        path, key = ws.path(args.path)
        ws.snapshot({'document_id': remote.id, 'path': key, 'name': remote.name}, remote.content, 'manual-download')
        write(path, remote.content)
        print('Downloaded an untracked copy: ' + repr(key))
    elif command == 'history':
        try:
            docid = ws.file(args.target)['document_id']
        except SyncError:
            docid = identifier(args.target)
        output([asdict(doc) | {'content': None} for doc in api.history(docid)], ['revision', 'name', 'size', 'sha256'], as_json=args.json)
    elif command == 'sync':
        if args.allow_large_delete and not args.files:
            raise SyncError('Name the affected files explicitly when using --allow-large-delete.')
        rows = [ws.file(value) for value in dict.fromkeys(args.files)] if args.files else ws.files()
        results = Engine(ws, api).sync(rows, settle=args.settle, allow_large_delete=args.allow_large_delete)
        for result in results:
            print_result(result)
        if not results:
            print('No tracked files; use add or get first.')
        return 0 if all(result.ok for result in results) else 2
    elif command == 'resolve':
        result = Engine(ws, api).resolve(ws.file(args.file), args.use, allow_large_delete=args.allow_large_delete)
        print_result(result)
        return 0 if result.ok else 2
    return 0


def main(argv=None):
    try:
        args = parser().parse_args(argv)
        if args.command == 'init':
            return initialise(args)
        with Workspace.discover(args.directory) as ws:
            if args.command == 'watch':
                print('Watching ' + str(ws.root) + '; saved files sync after settling. Stop with Ctrl+C.', flush=True)
                Watcher(ws, api_for(ws, insecure=args.insecure), settle=args.settle, remote_interval=args.remote_interval).run(args.interval)
            else:
                with ws.lock():
                    return dispatch(ws, args)
        return 0
    except KeyboardInterrupt:
        print('\nStopped. Local files, recovery snapshots, and pending saves are retained.', file=sys.stderr)
        return 130
    except (SyncError, OSError, sqlite3.Error) as error:
        print('textpad-sync: ' + str(error), file=sys.stderr)
        return 1
