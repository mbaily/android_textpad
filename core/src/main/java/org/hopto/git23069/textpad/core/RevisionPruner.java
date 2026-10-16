package org.hopto.git23069.textpad.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Hybrid count + time-bucketed exponential revision/backup pruning. */
public final class RevisionPruner {
    public static final int RECENT_KEEP_COUNT = 10;
    public static final long RECENT_KEEP_MILLIS = 3600_000L;
    public static final long HOURLY_KEEP_MILLIS = 24L * 3600_000L;
    public static final long DAILY_KEEP_MILLIS = 14L * 24L * 3600_000L;
    public static final long WEEKLY_KEEP_MILLIS = 56L * 24L * 3600_000L;

    private RevisionPruner() {}

    public static String timeBucket(long timestampMillis, long nowMillis) {
        long age = Math.max(0L, nowMillis - timestampMillis);
        if (age <= RECENT_KEEP_MILLIS) return null;
        if (age <= HOURLY_KEEP_MILLIS) return "H:" + Math.floorDiv(timestampMillis, 3600_000L);
        if (age <= DAILY_KEEP_MILLIS) return "D:" + Math.floorDiv(timestampMillis, 86400_000L);
        if (age <= WEEKLY_KEEP_MILLIS) return "W:" + Math.floorDiv(timestampMillis, 604800_000L);
        ZonedDateTime dt = Instant.ofEpochMilli(timestampMillis).atZone(ZoneOffset.UTC);
        return String.format(Locale.ROOT, "M:%04d-%02d", dt.getYear(), dt.getMonthValue());
    }

    public static int exponentialStride(int distance) {
        if (distance <= RECENT_KEEP_COUNT) return 1;
        if (distance <= 26) return 2;
        if (distance <= 58) return 4;
        if (distance <= 122) return 8;
        return 16;
    }

    /**
     * Given timestamps ordered newest-first for a single document, returns the indices (in that list)
     * that should be pruned.
     */
    public static List<Integer> selectPrunedIndices(long[] createdMillisNewestFirst, long nowMillis) {
        int total = createdMillisNewestFirst.length;
        if (total <= RECENT_KEEP_COUNT) return List.of();
        Set<String> seenBuckets = new HashSet<>();
        List<Integer> pruned = new ArrayList<>();
        for (int distance = 0; distance < total; distance++) {
            long ts = createdMillisNewestFirst[distance];
            String bucket = timeBucket(ts, nowMillis);
            boolean firstInBucket = bucket != null && seenBuckets.add(bucket);
            int rank = total - 1 - distance;
            int stride = exponentialStride(distance);
            boolean keep = distance < RECENT_KEEP_COUNT
                    || rank == 0
                    || (nowMillis - ts) <= RECENT_KEEP_MILLIS
                    || firstInBucket
                    || (rank % stride == 0);
            if (!keep) pruned.add(distance);
        }
        return pruned;
    }
}
