package org.example;

/**
 * Thread-safe tracker for request durations. Stores separate statistics for
 * read-only and non-read-only requests.
 */
public final class RequestDurationTracker {

    private static final class Stats {
        private long count = 0;
        private long total = 0;
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;

        synchronized void add(long durationMillis) {
            count++;
            total += durationMillis;
            if (durationMillis < min) min = durationMillis;
            if (durationMillis > max) max = durationMillis;
        }

        synchronized long getCount() { return count; }
        synchronized long getTotal() { return total; }
        synchronized long getMin() { return count == 0 ? 0 : min; }
        synchronized long getMax() { return count == 0 ? 0 : max; }
        synchronized double getAverage() { return count == 0 ? 0.0 : ((double) total) / count; }
    }

    private final Stats readOnlyStats = new Stats();
    private final Stats nonReadOnlyStats = new Stats();

    /**
     * Record a request duration (in milliseconds).
     * @param isReadOnly whether the request was read-only
     * @param durationMillis duration in milliseconds
     */
    public void addDuration(boolean isReadOnly, long durationMillis) {
        if (isReadOnly) readOnlyStats.add(durationMillis);
        else nonReadOnlyStats.add(durationMillis);
    }

    public long getCount(boolean isReadOnly) {
        return isReadOnly ? readOnlyStats.getCount() : nonReadOnlyStats.getCount();
    }

    public long getTotalDurationMillis(boolean isReadOnly) {
        return isReadOnly ? readOnlyStats.getTotal() : nonReadOnlyStats.getTotal();
    }

    public long getMinMillis(boolean isReadOnly) {
        return isReadOnly ? readOnlyStats.getMin() : nonReadOnlyStats.getMin();
    }

    public long getMaxMillis(boolean isReadOnly) {
        return isReadOnly ? readOnlyStats.getMax() : nonReadOnlyStats.getMax();
    }

    public double getAverageMillis(boolean isReadOnly) {
        return isReadOnly ? readOnlyStats.getAverage() : nonReadOnlyStats.getAverage();
    }

    /**
     * Reset both statistics buckets.
     */
    public void reset() {
        synchronized (readOnlyStats) {
            readOnlyStats.count = 0;
            readOnlyStats.total = 0;
            readOnlyStats.min = Long.MAX_VALUE;
            readOnlyStats.max = Long.MIN_VALUE;
        }
        synchronized (nonReadOnlyStats) {
            nonReadOnlyStats.count = 0;
            nonReadOnlyStats.total = 0;
            nonReadOnlyStats.min = Long.MAX_VALUE;
            nonReadOnlyStats.max = Long.MIN_VALUE;
        }
    }
}

