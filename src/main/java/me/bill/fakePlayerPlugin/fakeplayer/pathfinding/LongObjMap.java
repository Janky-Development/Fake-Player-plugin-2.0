package me.bill.fakePlayerPlugin.fakeplayer.pathfinding;

import java.util.Arrays;

/**
 * A minimal open-addressing {@code long -> V} map (linear probing, power-of-two table). The pathfinder
 * looks up block cells and search nodes tens of thousands of times per search; boxing every key into a
 * {@code Long} and chasing {@code HashMap} nodes made that the dominant cost. Not thread-safe, no removal.
 */
final class LongObjMap<V> {

    private static final long EMPTY = Long.MIN_VALUE; // a packed BlockPos never equals this

    private long[] keys;
    private Object[] values;
    private int size;
    private int mask;

    LongObjMap(int expected) {
        int cap = 16;
        while (cap < expected * 2) cap <<= 1;
        keys = new long[cap];
        values = new Object[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
    }

    int size() {
        return size;
    }

    private static int hash(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        return (int) k;
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        int i = hash(key) & mask;
        while (true) {
            long k = keys[i];
            if (k == key) return (V) values[i];
            if (k == EMPTY) return null;
            i = (i + 1) & mask;
        }
    }

    void put(long key, V value) {
        if ((size + 1) * 2 > keys.length) grow();
        int i = hash(key) & mask;
        while (true) {
            long k = keys[i];
            if (k == key) {
                values[i] = value;
                return;
            }
            if (k == EMPTY) {
                keys[i] = key;
                values[i] = value;
                size++;
                return;
            }
            i = (i + 1) & mask;
        }
    }

    boolean containsKey(long key) {
        int i = hash(key) & mask;
        while (true) {
            long k = keys[i];
            if (k == key) return true;
            if (k == EMPTY) return false;
            i = (i + 1) & mask;
        }
    }

    void clear() {
        Arrays.fill(keys, EMPTY);
        Arrays.fill(values, null);
        size = 0;
    }

    private void grow() {
        long[] oldK = keys;
        Object[] oldV = values;
        int cap = oldK.length << 1;
        keys = new long[cap];
        values = new Object[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
        size = 0;
        for (int i = 0; i < oldK.length; i++) {
            if (oldK[i] != EMPTY) put(oldK[i], castV(oldV[i]));
        }
    }

    @SuppressWarnings("unchecked")
    private V castV(Object o) {
        return (V) o;
    }
}
