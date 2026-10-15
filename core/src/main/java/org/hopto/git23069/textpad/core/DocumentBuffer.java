package org.hopto.git23069.textpad.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Shared, memory-only text and bounded edit history. Android views own their selections. */
public final class DocumentBuffer {
    private static final int HISTORY_CHARS = 4 * 1024 * 1024;
    private static final int HISTORY_EDITS = 2000;
    private final StringBuilder text;
    private final ArrayList<Integer> breaks = new ArrayList<>();
    private final ArrayList<String> endings;
    private final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private int historyChars;
    private long state, nextState = 1, savedState;
    private TextCodec.Encoding encoding;
    private boolean bom;
    private String preferredEnding;
    private int lfCount, crlfCount, crCount;

    public record Change(int start, String removed, String added, List<String> removedEndings,
                         List<String> addedEndings, long before, long after) {}
    public record Snapshot(long state, byte[] bytes) {}
    public record Content(long state, String text, TextCodec.Encoding encoding, boolean bom, List<String> endings) {
        public Snapshot encode() throws IOException {
            return new Snapshot(state, TextCodec.encode(text, encoding, bom, endings));
        }
    }

    public DocumentBuffer(TextCodec.Decoded decoded) {
        text = new StringBuilder(decoded.text());
        endings = new ArrayList<>(decoded.endings());
        encoding = decoded.encoding(); bom = decoded.bom(); preferredEnding = decoded.preferredEnding();
        for (String ending : endings) countEnding(ending, 1);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') breaks.add(i);
    }

    public static DocumentBuffer empty() {
        return new DocumentBuffer(new TextCodec.Decoded("", TextCodec.Encoding.UTF8, false, List.of()));
    }
    public String text() { return text.toString(); }
    public int length() { return text.length(); }
    public boolean dirty() { return state != savedState; }
    public long state() { return state; }
    public void markSaved(long snapshotState) { savedState = snapshotState; }
    public TextCodec.Encoding encoding() { return encoding; }
    public boolean bom() { return bom; }
    public int lineCount() { return breaks.size() + 1; }
    public int lineAt(int offset) { return lowerBound(Math.max(0, Math.min(offset, text.length()))); }
    public int lineStart(int line) { return line <= 0 ? 0 : breaks.get(Math.min(line, breaks.size()) - 1) + 1; }
    public String endingLabel() {
        if ((lfCount > 0 ? 1 : 0) + (crlfCount > 0 ? 1 : 0) + (crCount > 0 ? 1 : 0) > 1) return "Mixed";
        if (crlfCount > 0) return "CRLF";
        if (crCount > 0) return "CR";
        if (lfCount > 0) return "LF";
        return preferredEnding.equals("\r\n") ? "CRLF" : preferredEnding.equals("\r") ? "CR" : "LF";
    }
    public void format(TextCodec.Encoding newEncoding, boolean newBom) {
        if (encoding == newEncoding && bom == newBom) return;
        encoding = newEncoding; bom = newBom && newEncoding != TextCodec.Encoding.ASCII;
        // Format changes are persistent even when text history is undone.
        undo.clear(); redo.clear(); historyChars = 0; state = nextState++;
    }
    public Snapshot snapshot() throws IOException {
        return capture().encode();
    }
    public Content capture() { return new Content(state, text.toString(), encoding, bom, List.copyOf(endings)); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public Change edit(int start, int end, String replacement) {
        String added = TextCodec.normalize(replacement), removed = text.substring(start, end);
        if (removed.equals(added)) return null;
        int first = lowerBound(start), last = lowerBound(end);
        List<String> oldEndings = List.copyOf(endings.subList(first, last));
        List<String> newEndings = new ArrayList<>();
        for (int i = 0; i < added.length(); i++) if (added.charAt(i) == '\n') newEndings.add(preferredEnding);
        // Indent and replace-all can update whole lines without changing their terminators.
        if (newEndings.size() == oldEndings.size()) newEndings = oldEndings;
        Change change = new Change(start, removed, added, oldEndings, List.copyOf(newEndings), state, nextState++);
        apply(start, end, added, newEndings); state = change.after;
        redo.clear(); undo.addLast(change); historyChars += added.length() + removed.length();
        while (undo.size() > HISTORY_EDITS || (historyChars > HISTORY_CHARS && undo.size() > 1)) {
            Change oldest = undo.removeFirst(); historyChars -= oldest.added.length() + oldest.removed.length();
        }
        return change;
    }

    public Change undo() {
        if (undo.isEmpty()) return null;
        Change c = undo.removeLast();
        apply(c.start, c.start + c.added.length(), c.removed, c.removedEndings);
        state = c.before; redo.addLast(c); return c;
    }
    public Change redo() {
        if (redo.isEmpty()) return null;
        Change c = redo.removeLast();
        apply(c.start, c.start + c.removed.length(), c.added, c.addedEndings);
        state = c.after; undo.addLast(c); return c;
    }

    private void apply(int start, int end, String added, List<String> newEndings) {
        int first = lowerBound(start), last = lowerBound(end), delta = added.length() - (end - start);
        for (String ending : endings.subList(first, last)) countEnding(ending, -1);
        for (String ending : newEndings) countEnding(ending, 1);
        breaks.subList(first, last).clear(); endings.subList(first, last).clear();
        for (int i = first; i < breaks.size(); i++) breaks.set(i, breaks.get(i) + delta);
        int index = first;
        for (int i = 0; i < added.length(); i++) if (added.charAt(i) == '\n') breaks.add(index++, start + i);
        endings.addAll(first, newEndings); text.replace(start, end, added);
    }
    private void countEnding(String ending, int delta) {
        if (ending.equals("\r\n")) crlfCount += delta;
        else if (ending.equals("\r")) crCount += delta;
        else lfCount += delta;
    }
    private int lowerBound(int offset) {
        int low = 0, high = breaks.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (breaks.get(mid) < offset) low = mid + 1; else high = mid;
        }
        return low;
    }
}
