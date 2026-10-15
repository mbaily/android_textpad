package org.hopto.git23069.textpad.core;

import java.util.HashSet;
import java.util.Set;

/** Bounded text-shingle overlap, used only to rank user-confirmed link suggestions. */
public final class ContentSimilarity {
    private ContentSimilarity() {}
    public static int percent(String left, String right) {
        if (left.equals(right)) return 100;
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> a = shingles(left), b = shingles(right), union = new HashSet<>(a);
        union.addAll(b); a.retainAll(b);
        return union.isEmpty() ? 0 : (int) Math.round(100d * a.size() / union.size());
    }
    private static Set<String> shingles(String text) {
        Set<String> result = new HashSet<>(); int width = Math.min(12,text.length());
        int step = Math.max(1,(text.length() - width) / 2048);
        for (int i = 0; i + width <= text.length(); i += step) result.add(text.substring(i,i+width));
        return result;
    }
}
