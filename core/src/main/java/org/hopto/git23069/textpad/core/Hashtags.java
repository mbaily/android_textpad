package org.hopto.git23069.textpad.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hashtag extraction, query parsing, and line/file matching across documents.
 */
public final class Hashtags {
    private static final Pattern HASHTAG_IN_TEXT = Pattern.compile(
            "(?:(?<=^)|(?<=[\\s(\\[{\"']))#([A-Za-z_][A-Za-z0-9_-]*)(?=$|\\s|[.,;:!?)\\]}\"'])"
    );
    private static final Pattern TAG_TOKEN = Pattern.compile("#?([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern TAG_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    public record LineMatch(int lineNumber, int lineStartOffset, String lineText, List<String> matchedTags) {}

    public record FileMatch(List<String> matchedTags, List<LineMatch> lines) {
        public boolean matches() {
            return !lines.isEmpty();
        }
    }

    private Hashtags() {}

    /** Normalize a single hashtag name (lowercase, without leading '#'), or null if invalid. */
    public static String normalizeTag(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("#")) trimmed = trimmed.substring(1).trim();
        if (!TAG_NAME.matcher(trimmed).matches()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** Parse a whitespace/comma-separated string of hashtags into normalized, deduplicated tag names. */
    public static List<String> parseTagList(String input) {
        if (input == null || input.isBlank()) return List.of();
        Set<String> result = new LinkedHashSet<>();
        Matcher m = TAG_TOKEN.matcher(input);
        while (m.find()) {
            String tag = normalizeTag(m.group(1));
            if (tag != null) result.add(tag);
        }
        return new ArrayList<>(result);
    }

    /** Format a collection of normalized tags as a space-separated '#tag' string. */
    public static String formatTagList(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            String norm = normalizeTag(tag);
            if (norm == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append('#').append(norm);
        }
        return sb.toString();
    }

    /** Extract all distinct normalized hashtags appearing in the given text, in order of first appearance. */
    public static List<String> extractHashtags(String text) {
        if (text == null || text.isEmpty()) return List.of();
        Set<String> tags = new LinkedHashSet<>();
        Matcher m = HASHTAG_IN_TEXT.matcher(text);
        while (m.find()) {
            tags.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(tags);
    }

    /** Check whether a line is a completed '[X]' or '[x]' todo item. */
    public static boolean isCompletedTodoLine(String line) {
        return line != null && TaskFilter.classifyLine(line) == TaskFilter.LineType.COMPLETED_TASK;
    }

    /**
     * Search a document's text for lines containing at least one of {@code targetTags}.
     *
     * @param text          Document text
     * @param targetTags    Collection of tags to match (OR semantics)
     * @param hideCompleted If true, completed and cancelled task lines are excluded (including list bullets)
     */
    public static FileMatch search(String text, Collection<String> targetTags, boolean hideCompleted) {
        if (text == null || text.isEmpty() || targetTags == null || targetTags.isEmpty()) {
            return new FileMatch(List.of(), List.of());
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String t : targetTags) {
            String norm = normalizeTag(t);
            if (norm != null) targets.add(norm);
        }
        if (targets.isEmpty()) return new FileMatch(List.of(), List.of());

        Set<String> fileTags = new LinkedHashSet<>();
        List<LineMatch> matchingLines = new ArrayList<>();
        int len = text.length();
        int lineStart = 0;
        int lineNumber = 1;
        while (lineStart <= len) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = len;
            int contentEnd = (lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String line = text.substring(lineStart, contentEnd);
            if (!hideCompleted || !TaskFilter.isClosedTask(line)) {
                Set<String> lineMatched = null;
                Matcher m = HASHTAG_IN_TEXT.matcher(line);
                while (m.find()) {
                    String found = m.group(1).toLowerCase(Locale.ROOT);
                    if (targets.contains(found)) {
                        if (lineMatched == null) lineMatched = new LinkedHashSet<>();
                        lineMatched.add(found);
                        fileTags.add(found);
                    }
                }
                if (lineMatched != null) {
                    matchingLines.add(new LineMatch(lineNumber, lineStart, line, new ArrayList<>(lineMatched)));
                }
            }
            if (lineEnd == len) break;
            lineStart = lineEnd + 1;
            lineNumber++;
        }
        return new FileMatch(
                Collections.unmodifiableList(new ArrayList<>(fileTags)),
                Collections.unmodifiableList(matchingLines)
        );
    }
}
