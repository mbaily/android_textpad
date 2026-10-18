"""Hashtag parsing and matching, shared with the other clients' search rules."""
from pathlib import Path
import re

from . import SyncError

HASHTAG_RE = re.compile(r"(?:^|(?<=[\s(\[{\"']))#([A-Za-z_][A-Za-z0-9_-]*)(?=$|\s|[.,;:!?)\]}\"'])")
CLOSED_TASK_RE = re.compile(r"^[ \t]*(?:[-*+] )?\[[XxCc]\]")
TAG_TOKEN_RE = re.compile(r"#?([A-Za-z_][A-Za-z0-9_-]*)")
TAG_LINE_RE = re.compile(r"#?([A-Za-z_][A-Za-z0-9_-]{0,63})")


def tags_from_file(value):
    path = Path(value).expanduser()
    tags = []
    try:
        with path.open(encoding='utf-8-sig') as stream:
            for number, line in enumerate(stream, start=1):
                line = line.strip()
                if not line:
                    continue
                match = TAG_LINE_RE.fullmatch(line)
                if match is None:
                    raise SyncError(f'Invalid hashtag in {str(path)!r} at line {number}: '
                                    'expected one hashtag per line (optional #, up to 64 characters).')
                tags.append(match[1])
    except (OSError, UnicodeError) as error:
        raise SyncError(f'Could not read hashtag file {str(path)!r}: {error}') from error
    return tags


def normalize_tags(values):
    if isinstance(values, str):
        values = [values]
    tags = dict.fromkeys(token.lower() for value in values for token in TAG_TOKEN_RE.findall(value)
                         if len(token) <= 64)
    return list(tags)[:200]


def scan(value, targets, hide_completed=True):
    # The server/browser normalise CRLF and CR to LF before computing line numbers.
    value = value.replace('\r\n', '\n').replace('\r', '\n')
    discovered = list(dict.fromkeys(match[1].lower() for match in HASHTAG_RE.finditer(value)))
    targets = set(targets)
    lines, file_tags, offset = [], {}, 0
    for number, line in enumerate(value.split('\n'), start=1):
        if targets and (not hide_completed or not CLOSED_TASK_RE.match(line)):
            tags = list(dict.fromkeys(match[1].lower() for match in HASHTAG_RE.finditer(line)
                                      if match[1].lower() in targets))
            if tags:
                file_tags.update(dict.fromkeys(tags))
                lines.append({'line_number': number, 'offset': offset, 'text': line, 'matched_tags': tags})
        offset += len(line) + 1
    return discovered, {'matched_tags': list(file_tags), 'lines': lines}
