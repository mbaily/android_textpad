"""Strict supported text encodings and preservation of existing file formatting."""
from dataclasses import dataclass
from difflib import SequenceMatcher
import re

MAX_BYTES = 10 * 1024 * 1024


class InvalidText(ValueError):
    pass


@dataclass(frozen=True)
class Decoded:
    raw: bytes
    text: str
    encoding: str
    bom: bool
    endings: tuple[str, ...]

    @property
    def preferred(self):
        return max(dict.fromkeys(self.endings), key=self.endings.count) if self.endings else "\n"

    @property
    def ending_label(self):
        if len(set(self.endings)) > 1:
            return "Mixed"
        return {"\n": "LF", "\r\n": "CRLF", "\r": "CR"}[self.preferred]


def validate(text):
    if re.search(r"[\x00-\x08\x0b\x0e-\x1f\x7f]", text):
        raise InvalidText("Binary files and non-text control characters are not supported.")
    if any(0xD800 <= ord(char) <= 0xDFFF for char in text):
        raise InvalidText("The text contains an incomplete Unicode character.")


def decode(raw, hint=None):
    if len(raw) > MAX_BYTES:
        raise InvalidText("Files must be no larger than 10 MiB.")
    encoding, bom, skip = hint or "utf-8", False, 0
    if raw.startswith((b"\xff\xfe\0\0", b"\0\0\xfe\xff")):
        raise InvalidText("UTF-32 is not supported. Use UTF-8 or UTF-16.")
    for prefix, name in [(b"\xef\xbb\xbf", "utf-8"), (b"\xff\xfe", "utf-16-le"), (b"\xfe\xff", "utf-16-be")]:
        if raw.startswith(prefix):
            encoding, bom, skip = name, True, len(prefix)
            break
    else:
        if hint is None and len(raw) >= 4 and len(raw) % 2 == 0:
            sample = raw[:1024]
            even, odd = sample[::2].count(0), sample[1::2].count(0)
            if odd > len(sample) // 4 and even == 0:
                encoding = "utf-16-le"
            elif even > len(sample) // 4 and odd == 0:
                encoding = "utf-16-be"
    if not isinstance(encoding, str) or encoding not in {"utf-8", "utf-16-le", "utf-16-be", "ascii"}:
        raise InvalidText("Choose UTF-8, UTF-16 LE/BE, or ASCII.")
    try:
        original = raw[skip:].decode(encoding, errors="strict")
    except UnicodeError as error:
        raise InvalidText("Invalid or unsupported text encoding. Use UTF-8 or UTF-16 with a BOM.") from error
    validate(original)
    endings = tuple(re.findall(r"\r\n|\r|\n", original))
    return Decoded(raw, re.sub(r"\r\n|\r", "\n", original), encoding, bom, endings)


def edited(base, text, encoding=None, bom=None):
    if not isinstance(text, str):
        raise InvalidText("Document text must be a string.")
    text = re.sub(r"\r\n|\r", "\n", text)
    validate(text)
    encoding = base.encoding if encoding is None else encoding
    bom = base.bom if bom is None else bom
    if not isinstance(encoding, str) or encoding not in {"utf-8", "utf-16-le", "utf-16-be", "ascii"}:
        raise InvalidText("Choose UTF-8, UTF-16 LE/BE, or ASCII.")
    if not isinstance(bom, bool):
        raise InvalidText("The byte-order mark setting must be true or false.")
    if text == base.text and encoding == base.encoding and bom == base.bom:
        return base.raw
    old_lines, new_lines = base.text.split("\n"), text.split("\n")
    preferred = base.preferred
    endings = []
    for operation, first, last, new_first, new_last in SequenceMatcher(None, old_lines, new_lines, autojunk=True).get_opcodes():
        if operation == "delete":
            continue
        for index in range(new_first, new_last):
            if index == len(new_lines) - 1:
                continue
            original_index = first + index - new_first
            endings.append(base.endings[original_index] if operation != "insert" and original_index < last
                           and original_index < len(base.endings) else preferred)
    pieces = []
    for index, line in enumerate(new_lines):
        pieces.append(line)
        if index < len(endings):
            ending = endings[index]
            if not line and index > 0 and endings[index - 1] == "\r" and ending == "\n":
                ending = "\r\n"
            pieces.append(ending)
    prefix = {"utf-8": b"\xef\xbb\xbf", "utf-16-le": b"\xff\xfe", "utf-16-be": b"\xfe\xff", "ascii": b""}[encoding] if bom else b""
    try:
        raw = prefix + "".join(pieces).encode(encoding, errors="strict")
    except UnicodeError as error:
        raise InvalidText("The selected encoding cannot represent this text. Choose UTF-8 or UTF-16.") from error
    if len(raw) > MAX_BYTES:
        raise InvalidText("The saved file would exceed 10 MiB.")
    return raw


def _hunks(base_seq, target_seq):
    return [
        (i1, i2, target_seq[j1:j2])
        for tag, i1, i2, j1, j2 in SequenceMatcher(None, base_seq, target_seq, autojunk=False).get_opcodes()
        if tag != "equal"
    ]


def _compatible_boundary(a, b):
    first, second = (a, b) if (a[0], a[1]) <= (b[0], b[1]) else (b, a)
    return first[1] < second[0] or (first[1] == second[0] and first[0] < first[1] and second[0] < second[1])


def _merge_line_tokens(base_line, curr_line, inc_line):
    if base_line == curr_line or base_line == inc_line or curr_line == inc_line:
        return None
    base_tokens = re.findall(r"\w+|\s+|[^\w\s]+", base_line)
    if len(base_tokens) < 2:
        return None
    curr_tokens = re.findall(r"\w+|\s+|[^\w\s]+", curr_line)
    inc_tokens = re.findall(r"\w+|\s+|[^\w\s]+", inc_line)
    curr_hunks = _hunks(base_tokens, curr_tokens)
    inc_hunks = _hunks(base_tokens, inc_tokens)
    if not curr_hunks or not inc_hunks:
        return None
    for h in curr_hunks + inc_hunks:
        if h[0] == 0 and h[1] == len(base_tokens):
            return None
    for a in curr_hunks:
        for b in inc_hunks:
            if a == b:
                continue
            if not _compatible_boundary(a, b):
                return None
    combined = []
    for hunk in sorted(curr_hunks + inc_hunks, key=lambda item: (item[0], item[1])):
        if not combined or combined[-1] != hunk:
            combined.append(hunk)
    merged = []
    cursor = 0
    for i1, i2, rep in combined:
        merged.extend(base_tokens[cursor:i1])
        merged.extend(rep)
        cursor = i2
    merged.extend(base_tokens[cursor:])
    result = "".join(merged)
    if result == curr_line or result == inc_line:
        return None
    return result


def merge_3way(base, current, incoming, encoding=None, bom=None):
    if base.text == current.text or base.text == incoming.text or current.text == incoming.text:
        return None
    base_lines = base.text.split("\n")
    curr_lines = current.text.split("\n")
    inc_lines = incoming.text.split("\n")
    curr_hunks = _hunks(base_lines, curr_lines)
    inc_hunks = _hunks(base_lines, inc_lines)
    if not curr_hunks or not inc_hunks:
        return None
    intraline_replacements = {}
    for idx_a, a in enumerate(curr_hunks):
        for idx_b, b in enumerate(inc_hunks):
            if a == b:
                continue
            if _compatible_boundary(a, b):
                continue
            if (
                a[0] == b[0]
                and a[1] == b[1]
                and (a[1] - a[0]) == len(a[2]) == len(b[2]) >= 1
            ):
                merged_block = []
                for k in range(a[1] - a[0]):
                    base_line = base_lines[a[0] + k]
                    curr_line = a[2][k]
                    inc_line = b[2][k]
                    if curr_line == inc_line:
                        merged_block.append(curr_line)
                    elif base_line == curr_line:
                        merged_block.append(inc_line)
                    elif base_line == inc_line:
                        merged_block.append(curr_line)
                    else:
                        merged_line = _merge_line_tokens(base_line, curr_line, inc_line)
                        if merged_line is None:
                            return None
                        merged_block.append(merged_line)
                intraline_replacements[(idx_a, idx_b)] = (a[0], a[1], merged_block)
            else:
                return None
    used_a = {idx_a for idx_a, _ in intraline_replacements}
    used_b = {idx_b for _, idx_b in intraline_replacements}
    all_hunks = (
        [h for i, h in enumerate(curr_hunks) if i not in used_a]
        + [h for i, h in enumerate(inc_hunks) if i not in used_b]
        + list(intraline_replacements.values())
    )
    combined = []
    for hunk in sorted(all_hunks, key=lambda item: (item[0], item[1])):
        if not combined or combined[-1] != hunk:
            combined.append(hunk)
    merged_lines = []
    cursor = 0
    for i1, i2, rep in combined:
        merged_lines.extend(base_lines[cursor:i1])
        merged_lines.extend(rep)
        cursor = i2
    merged_lines.extend(base_lines[cursor:])
    merged_text = "\n".join(merged_lines)
    if merged_text == current.text or merged_text == incoming.text:
        return None
    target_encoding = (
        current.encoding
        if (incoming.encoding == base.encoding and current.encoding != base.encoding)
        else incoming.encoding
    ) if encoding is None else encoding
    target_bom = (
        current.bom
        if (incoming.bom == base.bom and current.bom != base.bom)
        else incoming.bom
    ) if bom is None else bom
    format_base = (
        current
        if (incoming.preferred == base.preferred and current.preferred != base.preferred)
        else incoming
    )
    try:
        return edited(format_base, merged_text, target_encoding, target_bom)
    except InvalidText:
        return None


