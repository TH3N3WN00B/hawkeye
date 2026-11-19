package gg.moonrise.hawkeye.core.storage.index.place;

import gg.moonrise.hawkeye.util.codec.ByteReader;
import gg.moonrise.hawkeye.util.codec.ByteWriter;
import gg.moonrise.hawkeye.util.codec.CodecException;
import java.util.Arrays;
import java.util.function.LongConsumer;
import org.jetbrains.annotations.NotNull;

/**
 * A set of non-negative longs (segment ids or buckets) as sorted, disjoint, non-adjacent inclusive
 * runs. A chunk that is active all the time is in one run of consecutive segment ids, so the place
 * index stores and merges runs, never single ids. Not thread-safe.
 */
public final class IdRuns {

    private long[] runs;
    private int size;

    /** An empty set. */
    public IdRuns() {
        this.runs = new long[4];
    }

    /**
     * A set of some values.
     *
     * @param values the values, in any order
     * @return the set
     */
    public static @NotNull IdRuns of(long @NotNull ... values) {
        IdRuns out = new IdRuns();
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        for (long value : sorted) {
            out.append(value, value);
        }
        return out;
    }

    /**
     * One run.
     *
     * @param from the first value
     * @param to   the last value, at least {@code from}
     * @return the set
     */
    public static @NotNull IdRuns range(long from, long to) {
        IdRuns out = new IdRuns();
        out.append(from, to);
        return out;
    }

    /**
     * How many runs the set holds.
     *
     * @return the run count
     */
    public int runCount() {
        return size / 2;
    }

    /**
     * The first value of a run.
     *
     * @param run the run number
     * @return the value
     */
    public long start(int run) {
        return runs[run * 2];
    }

    /**
     * The last value of a run.
     *
     * @param run the run number
     * @return the value
     */
    public long end(int run) {
        return runs[run * 2 + 1];
    }

    /**
     * Whether the set is empty.
     *
     * @return {@code true} when it holds no value
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * How many values the set holds.
     *
     * @return the count
     */
    public long count() {
        long count = 0;
        for (int i = 0; i < size; i += 2) {
            count += runs[i + 1] - runs[i] + 1;
        }
        return count;
    }

    /**
     * The largest value.
     *
     * @return the value
     * @throws IllegalStateException when the set is empty
     */
    public long max() {
        if (size == 0) {
            throw new IllegalStateException("empty set");
        }
        return runs[size - 1];
    }

    /**
     * The smallest value.
     *
     * @return the value
     * @throws IllegalStateException when the set is empty
     */
    public long min() {
        if (size == 0) {
            throw new IllegalStateException("empty set");
        }
        return runs[0];
    }

    /**
     * Whether the set holds a value.
     *
     * @param value the value
     * @return {@code true} when it does
     */
    public boolean contains(long value) {
        int run = runAtOrBefore(value);
        return run >= 0 && runs[run * 2 + 1] >= value;
    }

    /**
     * Adds a value.
     *
     * @param value a non-negative value
     */
    public void add(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative value " + value);
        }
        int run = runAtOrBefore(value);
        if (run >= 0 && runs[run * 2 + 1] >= value) {
            return;
        }
        boolean joinsLeft = run >= 0 && runs[run * 2 + 1] == value - 1;
        boolean joinsRight = (run + 1) * 2 < size && runs[(run + 1) * 2] == value + 1;
        if (joinsLeft && joinsRight) {
            runs[run * 2 + 1] = runs[(run + 1) * 2 + 1];
            System.arraycopy(runs, (run + 2) * 2, runs, (run + 1) * 2, size - (run + 2) * 2);
            size -= 2;
        } else if (joinsLeft) {
            runs[run * 2 + 1] = value;
        } else if (joinsRight) {
            runs[(run + 1) * 2] = value;
        } else {
            insertRun(run + 1, value, value);
        }
    }

    /**
     * Adds every value of another set.
     *
     * @param other the other set
     */
    public void addAll(@NotNull IdRuns other) {
        if (other.isEmpty()) {
            return;
        }
        if (isEmpty() || other.start(0) > max() + 1) {
            for (int run = 0; run < other.runCount(); run++) {
                append(other.start(run), other.end(run));
            }
            return;
        }
        IdRuns merged = new IdRuns();
        int a = 0;
        int b = 0;
        while (a < runCount() || b < other.runCount()) {
            boolean takeA = b >= other.runCount() || a < runCount() && start(a) <= other.start(b);
            if (takeA) {
                merged.append(start(a), end(a));
                a++;
            } else {
                merged.append(other.start(b), other.end(b));
                b++;
            }
        }
        this.runs = merged.runs;
        this.size = merged.size;
    }

    /**
     * Removes a value.
     *
     * @param value the value
     * @return {@code true} when the set held it
     */
    public boolean remove(long value) {
        int run = runAtOrBefore(value);
        if (run < 0 || runs[run * 2 + 1] < value) {
            return false;
        }
        long start = runs[run * 2];
        long end = runs[run * 2 + 1];
        if (start == end) {
            System.arraycopy(runs, (run + 1) * 2, runs, run * 2, size - (run + 1) * 2);
            size -= 2;
        } else if (value == start) {
            runs[run * 2] = value + 1;
        } else if (value == end) {
            runs[run * 2 + 1] = value - 1;
        } else {
            runs[run * 2 + 1] = value - 1;
            insertRun(run + 1, value + 1, end);
        }
        return true;
    }

    /**
     * Removes every value below a bound.
     *
     * @param bound the smallest value kept
     * @return {@code true} when something was removed
     */
    public boolean dropBelow(long bound) {
        int keep = 0;
        while (keep < size && runs[keep + 1] < bound) {
            keep += 2;
        }
        boolean changed = keep > 0;
        if (keep > 0) {
            System.arraycopy(runs, keep, runs, 0, size - keep);
            size -= keep;
        }
        if (size > 0 && runs[0] < bound) {
            runs[0] = bound;
            changed = true;
        }
        return changed;
    }

    /**
     * The values of this set that some bucket set holds: a value {@code v} is kept when the buckets
     * hold {@code v >> shift}.
     *
     * @param buckets the buckets
     * @param shift   how many low bits a bucket drops
     * @return a new set
     */
    public @NotNull IdRuns inBuckets(@NotNull IdRuns buckets, int shift) {
        IdRuns out = new IdRuns();
        int b = 0;
        for (int run = 0; run < runCount(); run++) {
            long from = start(run);
            long to = end(run);
            while (b < buckets.runCount() && ((buckets.end(b) << shift) | ((1L << shift) - 1)) < from) {
                b++;
            }
            for (int c = b; c < buckets.runCount(); c++) {
                long low = buckets.start(c) << shift;
                long high = (buckets.end(c) << shift) | ((1L << shift) - 1);
                if (low > to) {
                    break;
                }
                long first = Math.max(from, low);
                long last = Math.min(to, high);
                if (first <= last) {
                    out.append(first, last);
                }
            }
        }
        return out;
    }

    /**
     * The values both sets hold.
     *
     * @param other the other set
     * @return a new set
     */
    public @NotNull IdRuns intersect(@NotNull IdRuns other) {
        IdRuns out = new IdRuns();
        int a = 0;
        int b = 0;
        while (a < runCount() && b < other.runCount()) {
            long first = Math.max(start(a), other.start(b));
            long last = Math.min(end(a), other.end(b));
            if (first <= last) {
                out.append(first, last);
            }
            if (end(a) < other.end(b)) {
                a++;
            } else {
                b++;
            }
        }
        return out;
    }

    /**
     * Calls a consumer with every value, ascending.
     *
     * @param consumer the consumer
     */
    public void forEach(@NotNull LongConsumer consumer) {
        for (int i = 0; i < size; i += 2) {
            for (long value = runs[i]; value <= runs[i + 1]; value++) {
                consumer.accept(value);
            }
        }
    }

    /**
     * Writes the runs: varint run count, then {@link #writeRuns}.
     *
     * @param out  the writer
     * @param base the value the first start is coded against
     */
    public void write(@NotNull ByteWriter out, long base) {
        out.writeVarLong(runCount());
        writeRuns(out, base);
    }

    /**
     * Writes the runs without their count: the first start as a zig-zag delta from {@code base}, every
     * later start as the gap after the previous run minus 2 (runs are never adjacent), and every run's
     * length minus 1.
     *
     * @param out  the writer
     * @param base the value the first start is coded against
     */
    public void writeRuns(@NotNull ByteWriter out, long base) {
        for (int i = 0; i < size; i += 2) {
            if (i == 0) {
                out.writeZigZag(runs[0] - base);
            } else {
                out.writeVarLong(runs[i] - runs[i - 1] - 2);
            }
            out.writeVarLong(runs[i + 1] - runs[i]);
        }
    }

    /**
     * The set on its own: {@link #write} against 0, uncompressed.
     *
     * @return the bytes
     */
    public byte @NotNull [] toBytes() {
        ByteWriter out = new ByteWriter(4 + size * 3);
        write(out, 0);
        return out.toByteArray();
    }

    /**
     * Reads a set written by {@link #toBytes}.
     *
     * @param bytes the bytes
     * @return the set
     * @throws CodecException when the bytes are corrupt or not all read
     */
    public static @NotNull IdRuns fromBytes(byte @NotNull [] bytes) {
        ByteReader in = new ByteReader(bytes);
        IdRuns out = read(in, 0);
        if (in.hasRemaining()) {
            throw new CodecException("unread bytes after the runs");
        }
        return out;
    }

    /**
     * Reads runs written by {@link #write}.
     *
     * @param in   the reader
     * @param base the value the first start was coded against
     * @return the set
     * @throws CodecException when the runs are corrupt
     */
    public static @NotNull IdRuns read(@NotNull ByteReader in, long base) {
        return readRuns(in, in.readVarInt(), base);
    }

    /**
     * Reads runs written by {@link #writeRuns}.
     *
     * @param in    the reader
     * @param count how many runs
     * @param base  the value the first start was coded against
     * @return the set
     * @throws CodecException when the runs are corrupt
     */
    public static @NotNull IdRuns readRuns(@NotNull ByteReader in, int count, long base) {
        if (count < 0 || count > in.remaining()) {
            throw new CodecException("run count " + count + " with " + in.remaining() + " bytes left");
        }
        IdRuns out = new IdRuns();
        long previousEnd = 0;
        for (int i = 0; i < count; i++) {
            long start = i == 0 ? base + in.readZigZag() : previousEnd + 2 + in.readVarLong();
            long end = start + in.readVarLong();
            if (start < 0 || end < start) {
                throw new CodecException("run " + start + ".." + end + " out of range");
            }
            out.insertRun(out.runCount(), start, end);
            previousEnd = end;
        }
        return out;
    }

    /**
     * Skips runs written by {@link #writeRuns}, returning the first start without building a set.
     *
     * @param in    the reader
     * @param count how many runs
     * @param base  the value the first start was coded against
     * @return the first start ({@code base} when there are no runs)
     * @throws CodecException when the runs are corrupt
     */
    public static long skipRuns(@NotNull ByteReader in, int count, long base) {
        if (count < 0 || count > in.remaining()) {
            throw new CodecException("run count " + count + " with " + in.remaining() + " bytes left");
        }
        long first = base;
        for (int i = 0; i < count; i++) {
            if (i == 0) {
                first = base + in.readZigZag();
            } else {
                in.readVarLong();
            }
            in.readVarLong();
        }
        return first;
    }

    /**
     * Adds a run after every run already held, joining it to the last one when they touch.
     *
     * @param from the first value
     * @param to   the last value
     */
    public void append(long from, long to) {
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("bad run " + from + ".." + to);
        }
        if (size > 0 && from <= runs[size - 1] + 1) {
            if (from < runs[size - 2]) {
                throw new IllegalArgumentException("run " + from + ".." + to + " out of order");
            }
            runs[size - 1] = Math.max(runs[size - 1], to);
            return;
        }
        insertRun(runCount(), from, to);
    }

    /** The last run whose start is at or before a value, or -1. */
    private int runAtOrBefore(long value) {
        int lo = 0;
        int hi = runCount() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (runs[mid * 2] <= value) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    private void insertRun(int run, long from, long to) {
        if (size + 2 > runs.length) {
            runs = Arrays.copyOf(runs, Math.max(4, runs.length * 2));
        }
        System.arraycopy(runs, run * 2, runs, run * 2 + 2, size - run * 2);
        runs[run * 2] = from;
        runs[run * 2 + 1] = to;
        size += 2;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdRuns that && Arrays.equals(runs, 0, size, that.runs, 0, that.size);
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (int i = 0; i < size; i++) {
            hash = hash * 31 + Long.hashCode(runs[i]);
        }
        return hash;
    }

    @Override
    public @NotNull String toString() {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < size; i += 2) {
            text.append(i == 0 ? "" : ", ").append(runs[i]);
            if (runs[i + 1] != runs[i]) {
                text.append("..").append(runs[i + 1]);
            }
        }
        return text.append(']').toString();
    }
}
