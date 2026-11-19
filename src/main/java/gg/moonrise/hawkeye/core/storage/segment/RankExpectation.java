package gg.moonrise.hawkeye.core.storage.segment;

import java.util.Arrays;

/**
 * The rank the next row of a (slot, node) is expected to have, walking a segment in stored order: one
 * more than the last rank met for that pair, 0 for the first. Keys are
 * {@code (slot - base slot) * node count + node index}. A dense {@code int[]} when the key space is
 * small next to the row count, an open-addressing long-to-int table otherwise; neither boxes.
 */
public final class RankExpectation {

    private static final int MAX_DENSE_KEYS = 1 << 22;
    private static final long EMPTY = Long.MIN_VALUE;

    private final int[] dense;
    private long[] keys;
    private int[] values;
    private int size;

    /**
     * A tracker for one segment walk.
     *
     * @param slotSpan  the largest slot minus the smallest
     * @param nodeCount how many nodes the segment holds
     * @param rows      how many rows the segment holds
     */
    public RankExpectation(long slotSpan, int nodeCount, int rows) {
        long keySpace = (slotSpan + 1) * nodeCount;
        if (keySpace <= Math.min(MAX_DENSE_KEYS, Math.max(1 << 16, 4L * rows))) {
            dense = new int[(int) keySpace];
            Arrays.fill(dense, -1);
        } else {
            dense = null;
            keys = new long[1024];
            values = new int[keys.length];
            Arrays.fill(keys, EMPTY);
        }
    }

    /**
     * The expected rank of the next row with a key.
     *
     * @param key the (slot, node) key
     * @return one more than the last rank recorded for it, or 0
     */
    public int expected(long key) {
        if (dense != null) {
            return dense[(int) key] + 1;
        }
        int slot = find(key);
        return keys[slot] == EMPTY ? 0 : values[slot] + 1;
    }

    /**
     * Records the rank a row with a key had.
     *
     * @param key  the (slot, node) key
     * @param rank its rank
     */
    public void record(long key, int rank) {
        if (dense != null) {
            dense[(int) key] = rank;
            return;
        }
        int slot = find(key);
        if (keys[slot] == EMPTY) {
            keys[slot] = key;
            size++;
            values[slot] = rank;
            if (size * 2 > keys.length) {
                grow();
            }
            return;
        }
        values[slot] = rank;
    }

    private int find(long key) {
        int mask = keys.length - 1;
        int slot = (int) (mix(key) & mask);
        while (keys[slot] != EMPTY && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new int[keys.length];
        Arrays.fill(keys, EMPTY);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                int slot = find(oldKeys[i]);
                keys[slot] = oldKeys[i];
                values[slot] = oldValues[i];
            }
        }
    }

    private static long mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 32);
    }
}
