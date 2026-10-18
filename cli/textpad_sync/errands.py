"""Shared errands settings and searches over saved local files and server documents."""
import json
import sys

from . import SyncError
from .filesystem import read, text
from .hashtags import normalize_tags, scan


class Errands:
    def __init__(self, workspace, api=None):
        self.workspace, self.api = workspace, api

    def cached(self):
        raw = self.workspace.setting('errands', default=None)
        if raw is None:
            return {'hashtags': [], 'updated_at': '', 'dirty': False}
        try:
            result = json.loads(raw)
            if (not isinstance(result['hashtags'], list)
                    or any(not isinstance(tag, str) for tag in result['hashtags'])
                    or not isinstance(result['updated_at'], str) or type(result['dirty']) is not bool):
                raise ValueError()
            return result
        except (KeyError, TypeError, ValueError) as error:
            raise SyncError('Invalid cached errands settings in the workspace database.') from error

    def store(self, result, *, dirty=False):
        cached = {**result, 'dirty': dirty}
        self.workspace.set_setting('errands', json.dumps(cached))
        return cached

    def refresh(self):
        cached = self.cached()
        if self.api is None:
            return cached
        if cached['dirty']:
            return self.save(cached['hashtags'])
        return self.store(self.api.errands())

    def save(self, tags):
        cached = self.store({'hashtags': normalize_tags(tags), 'updated_at': self.cached()['updated_at']}, dirty=True)
        if self.api is None:
            return cached
        try:
            return self.store(self.api.errands(cached['hashtags']))
        except (SyncError, OSError) as error:
            raise SyncError(str(error) + ' Errands hashtags are saved locally; run errands tags again to retry.') from error

    def search(self, tags, *, hide_completed=True):
        discovered, results, excluded, issues = set(), [], [], []
        for row in self.workspace.files():
            try:
                value = text(read(self.workspace.path(row['path'])[0]).content)
            except (SyncError, OSError) as error:
                issues.append(f'Could not search {row["path"]!r}: {error}')
                continue
            excluded.append(row['document_id'])
            found, match = scan(value, tags, hide_completed)
            discovered.update(found)
            if match['lines']:
                results.append({'id': row['document_id'], 'name': row['name'], 'path': row['path'],
                                'source': 'local', **match})
        if self.api is not None:
            remote = self.api.search_hashtags(tags, hide_completed=hide_completed, exclude_ids=excluded)
            discovered.update(remote['all_hashtags'])
            results.extend({**match, 'path': None, 'source': 'server'} for match in remote['results'])
        results.sort(key=lambda match: ((match['path'] or match['name']).casefold(), match['id']))
        return {'all_hashtags': sorted(discovered), 'results': results}, issues


def terminal(value):
    # Keep full lines and indentation, while escaping control characters in output.
    return ''.join(f'\\x{ord(char):02x}' if (ord(char) < 32 and char != '\t') or ord(char) == 127 else char
                   for char in value)


def run(workspace, api, args):
    errands = Errands(workspace, api)
    if args.errands_command == 'tags' and args.available:
        search, issues = errands.search([])
        result = {'all_hashtags': search['all_hashtags']}
    elif args.errands_command == 'tags':
        issues = []
        if args.set is not None:
            result = errands.save(args.set)
        elif args.clear:
            result = errands.save([])
        else:
            result = errands.refresh()
            if args.add is not None:
                result = errands.save(result['hashtags'] + args.add)
            elif args.remove is not None:
                removed = set(normalize_tags(args.remove))
                result = errands.save([tag for tag in result['hashtags'] if tag not in removed])
        if result['dirty']:
            print('Errands hashtags saved locally; run errands tags online to sync them.', file=sys.stderr)
    else:
        config = errands.refresh()
        search, issues = (errands.search(config['hashtags'], hide_completed=args.hide_completed)
                          if config['hashtags'] else ({'all_hashtags': [], 'results': []}, []))
        results = search['results']
        if args.mode == 'files':
            results = [{key: value for key, value in match.items() if key != 'lines'}
                       | {'line_count': len(match['lines'])} for match in results]
        result = {**search, 'results': results, 'hashtags': config['hashtags'],
                  'mode': args.mode, 'hide_completed': args.hide_completed, 'offline': args.offline}
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    elif args.errands_command == 'tags':
        tags = result.get('all_hashtags', result.get('hashtags', []))
        print(' '.join('#' + tag for tag in tags) if tags else 'No hashtags.')
    elif not result['hashtags']:
        print("No errands hashtags configured. Use: textpad-sync errands tags --set '#chemist #groceries'")
    elif not result['results']:
        print('No matching ' + args.mode + '.')
    else:
        for match in result['results']:
            label = terminal(match['path'] or f'{match["name"]} [{match["id"]}]')
            if args.mode == 'files':
                tags = ' '.join('#' + tag for tag in match['matched_tags'])
                print(f'{label}\t{tags}\t{match["line_count"]} matching lines')
            else:
                for line in match['lines']:
                    print(f'{label}:{line["line_number"]}: {terminal(line["text"])}')
    for issue in issues:
        print('textpad-sync: ' + issue, file=sys.stderr)
    return 2 if issues else 0
