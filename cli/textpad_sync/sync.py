"""Three-way reconciliation with durable retry payloads and conservative conflicts."""
from dataclasses import dataclass

from . import BusyFile, SyncError
from .api import ApiError
from .filesystem import deletion_reason, read, settled_read, sha256, write
from .merge import InvalidText, choose, merge_contents


@dataclass(frozen=True)
class Result:
    path: str
    state: str
    message: str

    @property
    def ok(self):
        return self.state in {'synced', 'uploaded', 'downloaded', 'merged'}


class Engine:
    def __init__(self, workspace, api):
        self.workspace, self.api = workspace, api

    def issue(self, row, state, message):
        self.workspace.update(row, issue=state + ': ' + message)
        return Result(row['path'], state, message)

    def sync(self, rows=None, *, settle=1, allow_large_delete=False, expected_signatures=None):
        rows = self.workspace.files() if rows is None else rows
        if not rows:
            return []
        # One listing per pass; a conditional PUT still checks the actual revision
        # in the server's write transaction, including changes after this listing.
        remote = {d.id: d for d in self.api.list()}
        results = []
        for old_row in rows:
            row = self.workspace.file(old_row['path'])
            try:
                path, _ = self.workspace.path(row['path'])
                local = settled_read(path, settle)
                if expected_signatures and local.signature != expected_signatures.get(row['path']):
                    raise BusyFile('File changed since the monitor observed it; waiting for a stable save.')
                result = self.reconcile(row, local, remote.get(row['document_id']), allow_large_delete)
            except FileNotFoundError:
                result = self.issue(row, 'missing', 'Local file is missing; no deletion or download was propagated.')
            except BusyFile as error:
                result = self.issue(row, 'waiting', str(error))
            except (SyncError, OSError) as error:
                result = self.issue(row, 'error', str(error))
            results.append(result)
        return results

    def reconcile(self, row, local, remote, allow_large_delete=False):
        ws = self.workspace
        if row['pending_id']:
            # Retry exactly the persisted payload even if another editor save or
            # remote change occurred while the response was lost.
            result = self.upload(row, local, retry=True)
            if not result.ok:
                return result
            row = ws.file(row['path'])
            local = read(ws.path(row['path'])[0])
            remote = self.api.get(row['document_id'])

        base = row['base_content']
        local_changed = base is None or local.content != base or row['name'] != row['base_name']
        if remote is not None and local.digest == remote.sha256 and row['name'] == remote.name:
            ws.baseline(row, remote, local.content)
            return Result(row['path'], 'synced', 'Local and server copies match.')
        if base is None:
            if remote is not None:
                return self.conflict(row, local, self.api.get(row['document_id']))
            reason = deletion_reason(row['seed_content'], local.content)
            if reason and not allow_large_delete:
                ws.snapshot(row, local.content, 'held-deletion')
                return self.issue(row, 'held', reason + '; use sync FILE --allow-large-delete if intentional.')
            return self.upload(row, local)
        if remote is None:
            return self.issue(row, 'missing-remote', 'The linked server document is missing; no recreation was attempted.')
        remote_changed = remote.sha256 != sha256(base) or remote.name != row['base_name']
        if local_changed and remote_changed:
            return self.merge(row, local, self.api.get(row['document_id']), allow_large_delete)
        if local_changed:
            reason = deletion_reason(base, local.content)
            if reason and not allow_large_delete:
                ws.snapshot(row, local.content, 'held-deletion')
                return self.issue(row, 'held', reason + '; use sync FILE --allow-large-delete if intentional.')
            # Same bytes can have a newer revision. Use the current revision as
            # the compare-and-swap base without altering the local buffer.
            row = ws.update(row, revision=remote.revision)
            return self.upload(row, local)
        if remote_changed:
            downloaded = self.api.get(row['document_id'])
            reason = deletion_reason(base, downloaded.content)
            if reason and not allow_large_delete:
                ws.snapshot(row, downloaded.content, 'held-server-deletion', downloaded.name)
                return self.issue(row, 'held', 'Server change ' + reason + '; inspect history or use resolve FILE --use remote.')
            return self.pull(row, local, downloaded)
        ws.baseline(row, remote, local.content, adopt_name=True)
        return Result(row['path'], 'synced', 'Local and server copies match.')

    def merge(self, row, local, remote, allow_large_delete=False):
        ws, base = self.workspace, row['base_content']
        ws.snapshot(row, base, 'merge-base', row['base_name'])
        ws.snapshot(row, local.content, 'merge-local')
        ws.snapshot(row, remote.content, 'merge-server', remote.name)
        reason = deletion_reason(base, local.content) or deletion_reason(base, remote.content)
        if reason and not allow_large_delete:
            return self.issue(row, 'held', 'Merge ' + reason + '; inspect backups/history before resolving.')
        try:
            name = choose(row['base_name'], remote.name, row['name'])
        except InvalidText:
            return self.conflict(row, local, remote)
        merged = merge_contents(base, remote.content, local.content)
        if merged is None:
            return self.conflict(row, local, remote)
        reason = (deletion_reason(base, merged) or deletion_reason(local.content, merged)
                  or deletion_reason(remote.content, merged))
        if reason and not allow_large_delete:
            return self.issue(row, 'held', 'Merge ' + reason + '; inspect backups/history before resolving.')
        row = ws.queue(row, merged, remote=remote, local=local.content, name=name)
        result = self.upload(row, local, retry=True)
        if result.ok:
            return Result(row['path'], 'merged', 'Merged local and server edits; saved server revision '
                          + ws.file(row['path'])['revision'] + '.')
        return result

    def conflict(self, row, local, remote):
        self.workspace.snapshot(row, local.content, 'conflict-local')
        self.workspace.snapshot(row, remote.content, 'conflict-server', remote.name)
        return self.issue(row, 'conflict', 'Both local and server copies changed. Use resolve FILE --use local|remote after reviewing backups/history.')

    def upload(self, row, local, retry=False):
        ws = self.workspace
        if not retry:
            # Last local check before queuing. Further edits after this point are
            # retained on disk and differ from the acknowledged baseline.
            current = read(ws.path(row['path'])[0])
            if current.signature != local.signature or current.content != local.content:
                raise BusyFile('Local file changed before upload; waiting for the next stable save.')
            row = ws.queue(row, local.content)
        if row['pending_local'] is not None:
            ws.snapshot(row, local.content, 'before-merge')
            content = row['pending_content']
            if local.content not in (row['pending_local'], content):
                # An editor saved after the queue was persisted but before its
                # atomic local replacement. Preserve that edit as well, or hold
                # the operation rather than overwrite an overlapping change.
                content = merge_contents(row['pending_local'], content, local.content)
                if content is None:
                    ws.snapshot(row, local.content, 'conflict-during-merge')
                    return self.issue(row, 'conflict', 'Local file changed during merge. Use resolve FILE --use local|remote after reviewing backups/history.')
                reason = deletion_reason(local.content, content)
                if reason:
                    return self.issue(row, 'held', 'Merge ' + reason + '; inspect backups/history before resolving.')
            ws.snapshot(row, content, 'merged-local')
            if local.content != content:
                write(ws.path(row['path'])[0], content, expected=local)
            row = ws.update(row, pending_local=None)
        try:
            acknowledged = self.api.put(row['document_id'], row['pending_name'], row['pending_content'],
                                        row['pending_base'], row['pending_id'])
        except ApiError as error:
            if error.code == 'revision_conflict':
                ws.clear_pending(row, 'conflict: Server changed before this save.')
                return self.issue(row, 'conflict', 'Server changed before this save; run sync to inspect both versions, then resolve if needed.')
            raise
        # Only these exact bytes, never a fresh read of the local file, become the
        # baseline. A later local save is still a change on the next pass.
        ws.baseline(row, acknowledged, row['pending_content'])
        return Result(row['path'], 'uploaded', 'Saved server revision ' + acknowledged.revision + '.')

    def pull(self, row, local, remote):
        ws = self.workspace
        ws.snapshot(row, local.content, 'before-download')
        ws.snapshot(row, remote.content, 'downloaded-server', remote.name)
        path, _ = ws.path(row['path'])
        if local.content != remote.content:
            write(path, remote.content, expected=local)
        ws.baseline(row, remote, remote.content, adopt_name=True)
        return Result(row['path'], 'downloaded', 'Downloaded server revision ' + remote.revision + '.')

    def resolve(self, row, use, *, settle=1, allow_large_delete=False):
        path, _ = self.workspace.path(row['path'])
        local = settled_read(path, settle)
        # Finish an uncertain old request before resolving against the latest
        # server revision. A lost response must not become a second write.
        if row['pending_id'] and row['pending_local'] is not None:
            # Local staging always finishes durably before the first request.
            # This candidate has never been sent, so an explicit choice may
            # discard its queue without first changing the remote version.
            row = self.workspace.clear_pending(row, None)
        elif row['pending_id']:
            result = self.upload(row, local, retry=True)
            if result.state == 'error':
                return result
            row = self.workspace.file(row['path'])
            local = settled_read(path, settle)
        remote = self.api.get(row['document_id'])
        self.workspace.snapshot(row, local.content, 'before-resolution')
        self.workspace.snapshot(row, remote.content, 'resolution-server', remote.name)
        if use == 'remote':
            return self.pull(row, local, remote)
        reference = row['base_content'] if row['base_content'] is not None else row['seed_content']
        reason = deletion_reason(reference, local.content) or deletion_reason(remote.content, local.content)
        if reason and not allow_large_delete:
            return self.issue(row, 'held', reason + '; add --allow-large-delete if intentional.')
        row = self.workspace.update(row, revision=remote.revision)
        return self.upload(row, local)
