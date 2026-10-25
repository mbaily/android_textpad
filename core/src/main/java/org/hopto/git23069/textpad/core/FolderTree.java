package org.hopto.git23069.textpad.core;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Indexed, iterative navigation: nesting never consumes the Java call stack. */
public final class FolderTree {
    public record Row(Folder folder, int depth, boolean expanded) {}
    private final Map<String, Folder> byId = new LinkedHashMap<>();
    private final Map<String, List<Folder>> children = new HashMap<>();

    public FolderTree(List<Folder> folders) {
        for (Folder folder : folders) byId.put(folder.id(), folder);
        for (Folder folder : byId.values()) {
            String parent = byId.containsKey(folder.parentId()) ? folder.parentId() : null;
            children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(folder);
        }
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Comparator<Folder> order = (a, b) -> {
            int compared = collator.compare(a.name(), b.name());
            return compared != 0 ? compared : a.id().compareTo(b.id());
        };
        for (var siblings : children.values()) siblings.sort(order);
    }

    public Folder get(String id) { return byId.get(id); }
    public List<Folder> children(String id) { return List.copyOf(children.getOrDefault(id, List.of())); }
    public List<Folder> path(String id) {
        List<Folder> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (id != null && seen.add(id)) {
            Folder folder = byId.get(id);
            if (folder == null) break;
            path.add(folder);
            id = folder.parentId();
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }
    public Set<String> descendants(String id) {
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>();
        if (id != null) stack.add(id);
        while (!stack.isEmpty()) {
            String next = stack.remove(stack.size() - 1);
            if (!seen.add(next)) continue;
            for (Folder child : children(next)) stack.add(child.id());
        }
        return Set.copyOf(seen);
    }
    public List<Row> rows(Set<String> expanded) {
        List<Row> rows = new ArrayList<>(), stack = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        pushChildren(stack, null, 0, expanded);
        while (!stack.isEmpty()) {
            Row row = stack.remove(stack.size() - 1);
            if (!seen.add(row.folder().id())) continue;
            rows.add(row);
            if (row.expanded()) pushChildren(stack, row.folder().id(), row.depth() + 1, expanded);
        }
        return List.copyOf(rows);
    }
    private void pushChildren(List<Row> stack, String id, int depth, Set<String> expanded) {
        List<Folder> siblings = children.getOrDefault(id, List.of());
        for (int i = siblings.size() - 1; i >= 0; i--) {
            Folder folder = siblings.get(i);
            stack.add(new Row(folder, depth, expanded.contains(folder.id())));
        }
    }
}
