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
    int hash;

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

    // What a mutable list does, which holds a value as it is given: a list
    // made here may hold a mutable list or map, and is then the state of a
    // mutable list and never a list of its own.

    SparseList appended(Value held) {
        final int n = keys.length;
        return splice(n, 0, n == 0 ? Value.firstIndexKey() : keys[n - 1] + 1, new Value[] {held});
    }

    SparseList put(int index, Value held) {
        final int i = lowerBound(index);
        final boolean found = i < keys.length && keys[i] == index;
        return splice(i, found ? 1 : 0, index, new Value[] {held});
    }

    SparseList inserted(int index, Value[] held) {
        return held.length == 0 ? this : splice(lowerBound(index), 0, index, held);
    }

    /** The position of the first value at an index, or -1 when the list has none. */
    int position(int index) {
        final int i = lowerBound(index);
        return i < keys.length && keys[i] == index ? i : -1;
    }

    Value at(int position) {
        return vals[position];
    }

    SparseList replaced(int position, Value held) {
        final Value[] v = vals.clone();
        v[position] = held;
        return new SparseList(keys, v);
    }

    /** This list when nothing it holds is mutable; otherwise a copy that holds immutable copies. */
    SparseList frozen() {
        Value[] v = null;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].isMutable()) {
                if (v == null) {
                    v = vals.clone();
                }
                v[i] = vals[i].toImmutable();
            }
        }
        return v == null ? this : new SparseList(keys, v);
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
        return appended(element(value));
    }

    @Override
    public ListValue putAt(int index, Value value) {
        return put(index, element(value));
    }

    @Override
    public ListValue insertAt(int index, Value value) {
        return inserted(index, new Value[] {element(value)});
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
        return inserted(index, elements(values));
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
    void open(Walk.Level level) {
        level.indexes = keys;
        level.vals = vals;
        level.count = keys.length;
    }

    @Override
    void pack(Packer p) {
        if (!p.enter()) {
            Walk.pack(this, p);
            return;
        }
        p.mapHeader(keys.length);
        for (int i = 0; i < keys.length; i++) {
            p.integer(keys[i]);
            vals[i].pack(p);
        }
        p.leave();
    }

    @Override
    void jsonify(StringBuilder out) {
        jsonify(out, 0);
    }

    @Override
    void jsonify(StringBuilder out, int depth) {
        if (depth >= Walk.deep) {
            Walk.jsonify(this, out);
            return;
        }
        out.append('{');
        for (int i = 0; i < keys.length; i++) {
            if (i != 0) {
                out.append(',');
            }
            out.append('"').append(keys[i]).append("\": ");
            vals[i].jsonify(out, depth + 1);
        }
        out.append('}');
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Value && same((Value) o, 0);
    }

    @Override
    boolean same(Value o, int spent) {
        if (o instanceof MutableSparseList) {
            o = o.toImmutable();
        }
        if (o == this) {
            return true;
        }
        if (!(o instanceof SparseList)) {
            return false;
        }
        final SparseList s = (SparseList) o;
        if (!Arrays.equals(s.keys, keys)) {
            return false;
        }
        return spent >= Walk.deep ? Walk.equal(this, s) : Walk.sameValues(vals, s.vals, spent);
    }

    /** What {@code Arrays.hashCode} gives for the indexes, times 31, plus what it gives for the values. */
    @Override
    public int hashCode() {
        return hash(0);
    }

    @Override
    int hash(int spent) {
        int h = hash;
        if (h == 0) {
            if (spent >= Walk.deep) {
                return Walk.hash(this);
            }
            h = Arrays.hashCode(keys) * 31 + Walk.hashValues(vals, spent);
            hash = h;
        }
        return h;
    }
}
