package org.hopto.git23069.textpad.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Filters document lines to show only uncompleted tasks, their ancestors/headings,
 * plain notes indented directly under uncompleted tasks, and heading blocks
 * (blank line, nonblank title, line of dashes) for sections with uncompleted tasks.
 */
public final class TaskFilter {
    private static final Pattern DIVIDER = Pattern.compile("[ \\t]*-+[ \\t]*");

    public enum LineType {
        BLANK,
        UNCOMPLETED_TASK,
        COMPLETED_TASK,
        HEADER,
        PLAIN
    }

    public record FilterResult(
            int[] visibleLines,
            boolean[] isVisible,
            boolean[] isContext,
            boolean[] isUncompleted,
            boolean[] isNote
    ) {
        public boolean hasVisibleLines() { return visibleLines.length > 0; }
        public int visibleCount() { return visibleLines.length; }

        public int indexOf(int realLine) {
            return Arrays.binarySearch(visibleLines, realLine);
        }

        public boolean containsLine(int realLine) {
            return realLine >= 0 && realLine < isVisible.length && isVisible[realLine];
        }
    }

    private TaskFilter() {}

    public static int visualColumn(String line, int tabSize) {
        if (tabSize <= 0) tabSize = 4;
        int col = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                col += tabSize - (col % tabSize);
            } else if (c == ' ') {
                col += 1;
            } else {
                break;
            }
        }
        return col;
    }

    public static LineType classifyLine(String line) {
        int i = 0;
        int len = line.length();
        while (i < len && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        if (i == len) return LineType.BLANK;
        String rest = line.substring(i);
        String taskRest = rest;
        if (taskRest.startsWith("- ") || taskRest.startsWith("* ") || taskRest.startsWith("+ ")) {
            taskRest = taskRest.substring(2);
        }
        if (taskRest.startsWith("[ ]")) return LineType.UNCOMPLETED_TASK;
        if (taskRest.startsWith("[X]") || taskRest.startsWith("[x]")) return LineType.COMPLETED_TASK;
        if (isMarkdownHeader(rest)) return LineType.HEADER;
        return LineType.PLAIN;
    }

    private static boolean isMarkdownHeader(String rest) {
        if (!rest.startsWith("#")) return false;
        int h = 0;
        while (h < rest.length() && rest.charAt(h) == '#') h++;
        if (h >= 1 && h <= 6 && (h == rest.length() || rest.charAt(h) == ' ')) {
            return true;
        }
        return false;
    }

    private static int headerLevel(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        if (i < line.length() && line.charAt(i) == '#') {
            int h = 0;
            while (i + h < line.length() && line.charAt(i + h) == '#') h++;
            if (h >= 1 && h <= 6 && (i + h == line.length() || line.charAt(i + h) == ' ')) return h;
        }
        return 0;
    }

    public static FilterResult filter(String text, int tabSize) {
        String[] rawLines = text.split("\n", -1);
        int n = rawLines.length;
        String[] lines = new String[n];
        int[] indents = new int[n];
        LineType[] types = new LineType[n];
        int[] parents = new int[n];

        for (int i = 0; i < n; i++) {
            String raw = rawLines[i];
            lines[i] = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            indents[i] = visualColumn(lines[i], tabSize);
            types[i] = classifyLine(lines[i]);
            parents[i] = -1;
        }

        boolean[] dividerHeadings = new boolean[n];
        for (int i = 0; i + 2 < n; i++) {
            dividerHeadings[i] = types[i] == LineType.BLANK && types[i + 1] != LineType.BLANK
                    && DIVIDER.matcher(lines[i + 2]).matches();
        }

        int sectionStart = 0;
        for (int i = 0; i < n; i++) {
            if (dividerHeadings[i] || types[i] == LineType.HEADER) sectionStart = i;
            if (types[i] == LineType.BLANK) continue;
            for (int k = i - 1; k >= sectionStart; k--) {
                if (types[k] != LineType.BLANK && indents[k] < indents[i]) {
                    parents[i] = k;
                    break;
                }
            }
        }

        boolean[] isVisible = new boolean[n];
        boolean[] isContext = new boolean[n];
        boolean[] isUncompleted = new boolean[n];
        boolean[] isNote = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (types[i] == LineType.UNCOMPLETED_TASK) {
                isVisible[i] = true;
                isUncompleted[i] = true;
            }
        }

        // A section ends at the next divider heading or Markdown heading.
        int sectionHeading = -1;
        for (int i = 0; i < n; i++) {
            if (dividerHeadings[i]) {
                sectionHeading = i;
            } else if (types[i] == LineType.HEADER && i != sectionHeading + 1) {
                sectionHeading = -1;
            }
            if (isUncompleted[i] && sectionHeading >= 0) {
                for (int h = sectionHeading; h <= sectionHeading + 2; h++) {
                    isVisible[h] = true;
                    isContext[h] = true;
                }
            }
        }

        // Ancestors of uncompleted tasks
        for (int i = 0; i < n; i++) {
            if (!isUncompleted[i]) continue;
            int cur = parents[i];
            int top = i;
            while (cur != -1) {
                isVisible[cur] = true;
                isContext[cur] = true;
                top = cur;
                cur = parents[cur];
            }
            // Enclosing markdown headers
            int targetLevel = 7;
            for (int h = top - 1; h >= 0; h--) {
                if (types[h] == LineType.HEADER) {
                    int lvl = headerLevel(lines[h]);
                    if (lvl > 0 && lvl < targetLevel) {
                        isVisible[h] = true;
                        isContext[h] = true;
                        targetLevel = lvl;
                        if (targetLevel == 1) break;
                    }
                }
            }
        }

        // Plain notes indented under uncompleted tasks
        for (int i = 0; i < n; i++) {
            if (types[i] == LineType.PLAIN && parents[i] != -1) {
                int p = parents[i];
                if (isUncompleted[p] || isNote[p]) {
                    isVisible[i] = true;
                    isNote[i] = true;
                }
            }
        }

        List<Integer> visibleList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (isVisible[i]) visibleList.add(i);
        }
        int[] visibleLines = new int[visibleList.size()];
        for (int i = 0; i < visibleList.size(); i++) visibleLines[i] = visibleList.get(i);

        return new FilterResult(visibleLines, isVisible, isContext, isUncompleted, isNote);
    }
}
