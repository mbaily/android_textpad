package org.hopto.git23069.textpad.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FolderTest {
    @Test public void normalizesNamesAndRepresentsRootAndChildFolders() {
        Folder root = new Folder("root-folder", "  Cafe\u0301  ", null, "created", "updated");
        assertEquals("Café", root.name());
        assertNull(root.parentId());
        Folder child = new Folder("child", "Notes", root.id(), "created", "updated");
        assertEquals(root.id(), child.parentId());
    }

    @Test public void nestingDoesNotDependOnPathsOrRecursion() {
        String parent = null;
        for (int depth = 0; depth < 1500; depth++) {
            Folder folder = new Folder("folder-" + depth, "Folder", parent, "created", "updated");
            assertEquals(parent, folder.parentId());
            parent = folder.id();
        }
        assertEquals("folder-1499", parent);
    }

    @Test public void rejectsInvalidNamesIdsAndSelfContainment() {
        for (String name : new String[]{"", "   ", ".", "..", "a/b", "a\\b", "line\nbreak", "\nFolder", "Folder\t", "\u0000", "\u007f", "\ud800", "a".repeat(256)})
            assertThrows(IllegalArgumentException.class, () -> new Folder("id", name, null, "c", "u"));
        assertThrows(IllegalArgumentException.class, () -> new Folder("a/b", "Folder", null, "c", "u"));
        assertThrows(IllegalArgumentException.class, () -> new Folder("id", "Folder", "", "c", "u"));
        assertThrows(IllegalArgumentException.class, () -> new Folder("id", "Folder", "id", "c", "u"));
        assertEquals(255, new Folder("id", "a".repeat(255), null, "c", "u").name().length());
    }
}
