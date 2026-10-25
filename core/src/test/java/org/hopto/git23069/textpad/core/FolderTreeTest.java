package org.hopto.git23069.textpad.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class FolderTreeTest {
    private Folder folder(String id, String name, String parent) { return new Folder(id, name, parent, "", ""); }

    @Test public void siblingsSortAndCollapsedRowsDoNotHideOtherBranches() {
        var tree = new FolderTree(List.of(folder("z", "zebra", null), folder("a", "Alpha", null),
                folder("b", "Child", "a"), folder("c", "Grandchild", "b")));
        assertEquals(List.of("a", "z"), tree.children(null).stream().map(Folder::id).toList());
        assertEquals(List.of("a", "b", "z"), tree.rows(Set.of("a")).stream().map(r -> r.folder().id()).toList());
        assertEquals(List.of("a", "b", "c"), tree.path("c").stream().map(Folder::id).toList());
        assertEquals(Set.of("a", "b", "c"), tree.descendants("a"));
    }

    @Test public void deepPathsAndDestinationExclusionsAreIterative() {
        List<Folder> folders = new ArrayList<>();
        for (int i = 0; i < 2500; i++) folders.add(folder("f" + i, "Folder " + i, i == 0 ? null : "f" + (i - 1)));
        FolderTree tree = new FolderTree(folders);
        Set<String> expanded = tree.descendants("f0");
        assertEquals(2500, expanded.size());
        assertEquals(2500, tree.path("f2499").size());
        assertEquals(2499, tree.rows(expanded).get(2499).depth());
    }

    @Test public void partialAndMalformedCachesRemainFinite() {
        var tree = new FolderTree(List.of(folder("orphan", "Visible", "missing"),
                folder("a", "A", "b"), folder("b", "B", "a")));
        assertEquals(List.of("orphan"), tree.children(null).stream().map(Folder::id).toList());
        assertEquals(2, tree.path("a").size());
        assertEquals(Set.of("a", "b"), tree.descendants("a"));
        assertEquals(1, tree.rows(Set.of("a", "b")).size());
    }
}
