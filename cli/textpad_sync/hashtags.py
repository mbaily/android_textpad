"""Hashtag parsing and matching, shared with the other clients' search rules."""
import re

HASHTAG_RE = re.compile(r"(?:^|(?<=[\s(\[{\"']))#([A-Za-z_][A-Za-z0-9_-]*)(?=$|\s|[.,;:!?)\]}\"'])")
CLOSED_TASK_RE = re.compile(r"^[ \t]*(?:[-*+] )?\[[XxCc]\]")
TAG_TOKEN_RE = re.compile(r"#?([A-Za-z_][A-Za-z0-9_-]*)")


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
