package org.hopto.git23069.textpad.core;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RevisionPrunerTest {
    @Test public void keepsAllWithinRecentWindowAndOldestRevision() {
        long now = 1_700_000_000_000L;
        long[] few = new long[10];
        for (int i = 0; i < few.length; i++) few[i] = now - i * 7200_000L;
        assertTrue(RevisionPruner.selectPrunedIndices(few, now).isEmpty());

        // 40 revisions all within the same old day (48 hours ago) so time buckets match after the first:
        // newest 10 (indices 0..9), oldest (index 39, rank 0), and exponentially spaced ranks survive.
        long baseTime = now - 48L * 3600_000L;
        long[] many = new long[40];
        for (int i = 0; i < many.length; i++) many[i] = baseTime - i * 1000L;
        List<Integer> pruned = RevisionPruner.selectPrunedIndices(many, now);
        assertFalse(pruned.isEmpty());
        for (int idx = 0; idx < 10; idx++) assertFalse(pruned.contains(idx));
        assertFalse(pruned.contains(39)); // very first revision (rank 0) is always retained
        assertTrue(pruned.size() >= 15);
    }

    @Test public void timeBucketsPreserveRepresentativeSnapshotsAcrossHoursDaysAndWeeks() {
        long now = 1_700_000_000_000L;
        // 20 revisions spaced 2 hours apart: indices 0..9 are in recent count, indices 10..11 are <24h (hourly),
        // indices 12..19 are >24h in distinct hours/days.
        long[] timestamps = new long[20];
        for (int i = 0; i < timestamps.length; i++) timestamps[i] = now - (i + 1) * 2L * 3600_000L;
        List<Integer> pruned = RevisionPruner.selectPrunedIndices(timestamps, now);
        // Indices 10 and 11 are at -22h and -24h (distinct hourly buckets), so both are kept.
        assertFalse(pruned.contains(10));
        assertFalse(pruned.contains(11));
        assertFalse(pruned.contains(19));
    }
}
