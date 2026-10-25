package org.hopto.git23069.textpad.core;

import java.text.Normalizer;
import java.util.Objects;

/** A logical library folder. Null parentId means the top level; paths are not identities. */
public record Folder(String id, String name, String parentId, String createdAt, String updatedAt) {
    public Folder {
        checkId(id);
        Objects.requireNonNull(name, "name");
        boolean invalidCharacter = name.codePoints().anyMatch(c -> c == '/' || c == '\\' || c < 32 || c == 127
                || Character.getType(c) == Character.SURROGATE);
        name = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 255
                || name.equals(".") || name.equals("..") || invalidCharacter)
            throw new IllegalArgumentException("Use a folder name of 1–255 characters without slashes or control characters.");
        if (parentId != null) {
            checkId(parentId);
            if (parentId.equals(id)) throw new IllegalArgumentException("A folder cannot contain itself.");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static void checkId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid folder identifier.");
    }
}
