/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

/** A list keyed by index: a MessagePack map with integer keys. */
final class SparseList extends ListValue {

    static final SparseList EMPTY = new SparseList(new int[0], new Value[0]);

    /** Ascending; equal indexes keep the order they were given in. */
    private final int[] keys;
    private final Value[] vals;
    private int hash;

    private SparseList(int[] keys, Value[] vals) {
        this.keys = keys;
        this.vals = vals;
    }

    /** Takes the arrays without a copy, putting them in order unless they are. */
    static SparseList of(int[] keys, Value[] vals, boolean sorted) {
        final int n = keys.length;
        if (n == 0) {
            return EMPTY;
        }
        if (!sorted) {
            // By index, then by position: a stable order.
            final long[] order = new long[n];
            for (int i = 0; i < n; i++) {
                order[i] = ((long) keys[i] << 32) | i;
            }
            Arrays.sort(order);
            final int[] k = new int[n];
            final Value[] v = new Value[n];
            for (int i = 0; i < n; i++) {
                final int from = (int) order[i];
                k[i] = keys[from];
                v[i] = vals[from];
            }
            return new SparseList(k, v);
        }
        return new SparseList(keys, vals);
    }

    @Override
    public ListValue toImmutable() {
        return this;
    }

    @Override
    public ListValue toMutable() {
        return new MutableSparseList(this);
    }

    @Override
    public boolean updateAt(int index, UnaryOperator<Value> updater) {
        return false;
    }

    /** The list with the first value at the index replaced by what the function makes of it, or null when it has none. */
    SparseList updated(int index, UnaryOperator<Value> updater) {
        final int i = lowerBound(index);
        if (i == keys.length || keys[i] != index) {
            return null;
        }
        final Value[] v = vals.clone();
        v[i] = element(updater.apply(v[i]));
        return new SparseList(keys, v);
    }

    /** The first position whose index is not below the key. */
    private int lowerBound(int key) {
        int lo = 0;
        int hi = keys.length;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (keys[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @Override
    public boolean isSparse() {
        return true;
    }

    @Override
    public int size() {
        final int n = keys.length;
        if (n == 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min((long) keys[n - 1] + 1, Integer.MAX_VALUE));
    }

    @Override
    public int count() {
        return keys.length;
    }

    @Override
    public Value get(int index) {
        final int i = lowerBound(index);
        return i < keys.length && keys[i] == index ? vals[i] : NullValue.NULL;
    }

    @Override
    public List<Value> values() {
        final int size = size();
        return new AbstractList<Value>() {
            @Override
            public Value get(int i) {
                if (i < 0 || i >= size) {
                    throw new IndexOutOfBoundsException("index " + i + " of " + size);
                }
                return SparseList.this.get(i);
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    @Override
    public List<Item> items() {
        return new AbstractList<Item>() {
            @Override
            public Item get(int i) {
                return new Item(keys[i], vals[i]);
            }

            @Override
            public int size() {
                return keys.length;
            }
        };
    }

    @Override
    public ListValue append(Value value) {
        final int n = keys.length;
        return splice(n, 0, n == 0 ? 0 : keys[n - 1] + 1, new Value[] {element(value)});
    }

    @Override
    public ListValue putAt(int index, Value value) {
        final int i = lowerBound(index);
        final boolean found = i < keys.length && keys[i] == index;
        return splice(i, found ? 1 : 0, index, new Value[] {element(value)});
    }

    @Override
    public ListValue insertAt(int index, Value value) {
        return splice(lowerBound(index), 0, index, new Value[] {element(value)});
    }

    @Override
    public ListValue removeAt(int index) {
        final int i = lowerBound(index);
        if (i == keys.length || keys[i] != index) {
            return this;
        }
        return splice(i, 1, index, new Value[0]);
    }

    private int run(int from, int index) {
        int n = 0;
        while (from + n < keys.length && keys[from + n] == index) {
            n++;
        }
        return n;
    }

    @Override
    public List<Value> select(int index) {
        final int i = lowerBound(index);
        final int n = run(i, index);
        return Collections.unmodifiableList(new ArrayList<Value>(Arrays.asList(vals).subList(i, i + n)));
    }

    @Override
    public ListValue insertAll(int index, Collection<? extends Value> values) {
        if (values.isEmpty()) {
            return this;
        }
        return splice(lowerBound(index), 0, index, elements(values));
    }

    @Override
    public ListValue deleteAll(int index) {
        final int i = lowerBound(index);
        final int n = run(i, index);
        return n == 0 ? this : splice(i, n, index, new Value[0]);
    }

    /** The list with {@code removed} items at position {@code at} replaced by {@code added}, all under one index. */
    private SparseList splice(int at, int removed, int index, Value[] added) {
        final int n = keys.length - removed + added.length;
        if (n == 0) {
            return EMPTY;
        }
        final int[] k = new int[n];
        final Value[] v = new Value[n];
        System.arraycopy(keys, 0, k, 0, at);
        System.arraycopy(vals, 0, v, 0, at);
        Arrays.fill(k, at, at + added.length, index);
        System.arraycopy(added, 0, v, at, added.length);
        final int rest = keys.length - at - removed;
        System.arraycopy(keys, at + removed, k, at + added.length, rest);
        System.arraycopy(vals, at + removed, v, at + added.length, rest);
        return new SparseList(k, v);
    }

    @Override
    MapValue indexMap() {
        final String[] names = new String[keys.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = Integer.toString(keys[i]);
        }
        return MapValue.of(names, vals.clone(), false);
    }

    @Override
    void pack(Packer p) {
        p.mapHeader(keys.length);
        for (int i = 0; i < keys.length; i++) {
            p.integer(keys[i]);
            vals[i].pack(p);
        }
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append('{');
        for (int i = 0; i < keys.length; i++) {
            if (i != 0) {
                out.append(',');
            }
            out.append('"').append(keys[i]).append("\": ");
            vals[i].jsonify(out);
        }
        out.append('}');
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof MutableSparseList) {
            o = ((MutableSparseList) o).toImmutable();
        }
        if (o == this) {
            return true;
        }
        if (!(o instanceof SparseList)) {
            return false;
        }
        final SparseList s = (SparseList) o;
        return Arrays.equals(s.keys, keys) && Arrays.equals(s.vals, vals);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0) {
            h = Arrays.hashCode(keys) * 31 + Arrays.hashCode(vals);
            hash = h;
        }
        return h;
    }
}
