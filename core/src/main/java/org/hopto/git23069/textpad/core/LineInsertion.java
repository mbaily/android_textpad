package org.hopto.git23069.textpad.core;

import java.util.regex.Pattern;

/** Plans line-composer edits. Offsets use normalized LF text. */
public final class LineInsertion {
    private static final Pattern TASK_PREFIX = Pattern.compile("[ \\t]*([-*+] )?\\[[ xXcC]\\]");

    private LineInsertion() { }

    public record Insertion(int offset, int end, String text) {
        public int cursor() { return offset + text.length(); }
    }

    public record Deletion(int offset, int end) {
        public int cursor() { return offset; }
    }

    private static int lineStart(CharSequence text, int offset) {
        int start = Math.max(0, Math.min(offset, text.length()));
        while (start > 0 && text.charAt(start - 1) != '\n') start--;
        return start;
    }

    public static String indentation(CharSequence text, int offset) {
        int start = lineStart(text, offset), end = start;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        return text.subSequence(start, end).toString();
    }

    /** Continue any task state as an open task; indentation is managed separately. */
    public static String taskPrefix(CharSequence text, int offset) {
        var match = TASK_PREFIX.matcher(text).region(lineStart(text, offset), text.length());
        if (!match.lookingAt()) return "";
        return (match.group(1) == null ? "" : match.group(1)) + "[ ] ";
    }

    /** Vote on nearby indentation changes; ignore blanks and large alignment offsets. */
    public static String indentUnit(CharSequence text, int offset, int fallback) {
        int start = lineStart(text, offset);
        for (int n = 0; n < 100 && start > 0; n++) start = lineStart(text, start - 1);
        int[] votes = new int[9];
        int previous = -1, tabLines = 0, spaceLines = 0;
        for (int n = 0; n < 201 && start < text.length(); n++) {
            int end = start;
            while (end < text.length() && text.charAt(end) != '\n') end++;
            int content = start;
            boolean tabs = false;
            while (content < end && (text.charAt(content) == ' ' || text.charAt(content) == '\t')) {
                tabs |= text.charAt(content) == '\t';
                content++;
            }
            if (content < end) {
                int width = content - start;
                if (tabs) { tabLines++; previous = -1; }
                else {
                    if (width > 0) spaceLines++;
                    int delta = previous < 0 ? width : Math.abs(width - previous);
                    if (delta > 0 && delta < votes.length) votes[delta]++;
                    previous = width;
                }
            }
            start = end + 1;
        }
        String current = indentation(text, offset);
        if (current.endsWith("\t") || (current.isEmpty() && tabLines > spaceLines)) return "\t";
        int best = 0;
        for (int width = 1; width < votes.length; width++) if (votes[width] > votes[best]) best = width;
        return " ".repeat(best == 0 ? Math.max(1, fallback) : best);
    }

    public static String adjustIndent(String indent, String unit, boolean increase) {
        if (increase) return indent + unit;
        int end = indent.length();
        if (end > 0 && indent.charAt(end - 1) == '\t') return indent.substring(0, end - 1);
        int count = unit.equals("\t") ? 4 : unit.length();
        while (count-- > 0 && end > 0 && indent.charAt(end - 1) == ' ') end--;
        return indent.substring(0, end);
    }

    public static Insertion below(CharSequence text, int offset, String indent, String input) {
        if (!indent.matches("[ \\t]*") || input.indexOf('\n') >= 0 || input.indexOf('\r') >= 0
                || input.indexOf('\u2028') >= 0 || input.indexOf('\u2029') >= 0)
            throw new IllegalArgumentException("Enter a single line");
        int start = lineStart(text, offset), end = start;
        boolean blank = true;
        while (end < text.length() && text.charAt(end) != '\n') {
            char c = text.charAt(end++);
            if (c != ' ' && c != '\t') blank = false;
        }
        return blank ? new Insertion(start, end, indent + input)
                : new Insertion(end, end, "\n" + indent + input);
    }

    /** Remove the line and its following separator, or the preceding separator at EOF. */
    public static Deletion deletionAt(CharSequence text, int offset) {
        int start = lineStart(text, offset), end = start;
        while (end < text.length() && text.charAt(end) != '\n') end++;
        return end < text.length() ? new Deletion(start, end + 1)
                : new Deletion(Math.max(0, start - 1), end);
    }
}
