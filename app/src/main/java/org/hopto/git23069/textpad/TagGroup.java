package org.hopto.git23069.textpad;

import org.hopto.git23069.textpad.core.Hashtags;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Immutable saved group; changeId keeps a sync reply from acknowledging a newer local edit. */
record TagGroup(String id, String name, List<String> hashtags, String updatedAt,
                boolean dirty, boolean deleted, String changeId) {
    static final Comparator<TagGroup> ORDER = Comparator.comparing(TagGroup::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TagGroup::id);

    TagGroup {
        hashtags = List.copyOf(hashtags);
    }

    TagGroup edit(String nextName, List<String> tags) {
        return new TagGroup(id, nextName, Hashtags.parseTagList(String.join(" ", tags)), updatedAt,
                true, false, UUID.randomUUID().toString());
    }

    TagGroup delete() {
        return new TagGroup(id, name, hashtags, updatedAt, true, true, UUID.randomUUID().toString());
    }

    static String normalizeName(String value) {
        String name = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 100
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a tag group name of 1–100 characters without control characters.");
        if (name.equalsIgnoreCase("Errands"))
            throw new IllegalArgumentException("Errands is already a built-in tag group. Choose another name.");
        return name;
    }
}
