"""Library identities and immutable folder metadata, independent of file paths."""
from dataclasses import asdict, dataclass
import re
import unicodedata


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise ValueError("Invalid document, folder, revision, or request identifier.")
    return value


def filename(value):
    if not isinstance(value, str) or not value.strip() or value in {".", ".."} \
            or re.search(r"[/\\\x00-\x1f\x7f]", value):
        raise ValueError("Use a filename of 1–255 characters without slashes or control characters.")
    value = unicodedata.normalize("NFC", value.strip())
    if not value or len(value) > 255 or value in {".", ".."}:
        raise ValueError("Use a filename of 1–255 characters without slashes or control characters.")
    value.encode("utf-8", errors="strict")
    return value


@dataclass(frozen=True)
class Folder:
    id: str
    name: str
    parent_id: str | None
    created_at: str
    updated_at: str

    def __post_init__(self):
        identifier(self.id)
        try:
            object.__setattr__(self, "name", filename(self.name))
        except ValueError as error:
            raise ValueError("Use a folder name of 1–255 characters without slashes or control characters.") from error
        if self.parent_id is not None:
            identifier(self.parent_id)
            if self.parent_id == self.id:
                raise ValueError("A folder cannot contain itself.")

    @classmethod
    def from_row(cls, row):
        return cls(*(row[key] for key in ("id", "name", "parent_id", "created_at", "updated_at")))

    def to_dict(self):
        return asdict(self)
