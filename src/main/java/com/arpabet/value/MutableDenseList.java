/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A dense list that changes in place: the values in an array with room to
 * grow, so that appending does not copy what is already there. It holds the
 * values as they are given, mutable lists and maps included.
 */
final class MutableDenseList extends ListValue {

    private static final Value[] NONE = new Value[0];

    private Value[] items;
    private int size;
    /**
     * The immutable list of the same values, from when it is asked for until
     * the next change; kept only while nothing the list holds is mutable,
     * since what is mutable changes without the list hearing of it.
     */
    private DenseList snapshot;

    MutableDenseList(DenseList from) {
        this(from.array(), from.array().length, from);
    }

    private MutableDenseList(Value[] values, int size, DenseList snapshot) {
        this.items = Arrays.copyOf(values, Math.max(size, 8));
        this.size = size;
        this.snapshot = snapshot;
    }

    /** Makes room for a number of values and forgets the snapshot: the list is about to change. */
    private void change(int capacity) {
        snapshot = null;
        if (capacity > items.length) {
            final long doubled = 2L * items.length;
            items = Arrays.copyOf(items, (int) Math.max(capacity, Math.min(doubled, Integer.MAX_VALUE - 8)));
        }
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public boolean isSparse() {
        return false;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int count() {
        return size;
    }

    @Override
    public Value get(int index) {
        return index >= 0 && index < size ? items[index] : NullValue.NULL;
    }

    @Override
    public ListValue toImmutable() {
        try {
            return frozen();
        } catch (StackOverflowError e) {
            throw cyclic();
        }
    }

    private DenseList frozen() {
        DenseList s = snapshot;
        if (s != null) {
            return s;
        }
        boolean fixed = true;
        if (size == 0) {
            s = DenseList.EMPTY;
        } else {
            final Value[] copy = new Value[size];
            for (int i = 0; i < size; i++) {
                Value v = items[i];
                if (v.isMutable()) {
                    fixed = false;
                    v = v.toImmutable();
                }
                copy[i] = v;
            }
            s = new DenseList(copy);
        }
        if (fixed) {
            snapshot = s;
        }
        return s;
    }

    @Override
    public ListValue toMutable() {
        return new MutableDenseList(items, size, snapshot);
    }

    /** The values the list holds now, themselves; the list returned does not follow later changes. */
    @Override
    public List<Value> values() {
        return Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(items, size)));
    }

    @Override
    public List<Item> items() {
        final Value[] now = Arrays.copyOf(items, size);
        return new AbstractList<Item>() {
            @Override
            public Item get(int i) {
                return new Item(i, now[i]);
            }

            @Override
            public int size() {
                return now.length;
            }
        };
    }

    @Override
    public ListValue append(Value value) {
        final Value v = held(this, value);
        change(size + 1);
        items[size++] = v;
        return this;
    }

    @Override
    public ListValue putAt(int index, Value value) {
        if (index < 0) {
            return this;
        }
        final Value v = held(this, value);
        change(Math.max(size, index + 1));
        if (index >= size) {
            Arrays.fill(items, size, index, NullValue.NULL);
            size = index + 1;
        }
        items[index] = v;
        return this;
    }

    @Override
    public boolean updateAt(int index, UnaryOperator<Value> updater) {
        if (index < 0 || index >= size) {
            return false;
        }
        final Value v = held(this, updater.apply(items[index]));
        if (index >= size) {
            return false; // the function took the value away
        }
        change(size);
        items[index] = v;
        return true;
    }

    @Override
    public ListValue insertAt(int index, Value value) {
        if (index < 0) {
            return this;
        }
        if (index >= size) {
            return append(value);
        }
        return splice(index, 0, new Value[] {held(this, value)});
    }

    @Override
    public ListValue removeAt(int index) {
        if (index < 0 || index >= size) {
            return this;
        }
        return splice(index, 1, NONE);
    }

    @Override
    public List<Value> select(int index) {
        final Value v = get(index);
        return v == NullValue.NULL ? Collections.<Value>emptyList() : Collections.singletonList(v);
    }

    @Override
    public ListValue insertAll(int index, Collection<? extends Value> values) {
        if (values.isEmpty() || index < 0) {
            return this;
        }
        final Value[] added = heldAll(this, values);
        return splice(Math.min(index, size), 0, added);
    }

    @Override
    public ListValue deleteAll(int index) {
        return removeAt(index);
    }

    /** Replaces {@code removed} values at {@code at} by {@code added}, moving the ones after them. */
    private ListValue splice(int at, int removed, Value[] added) {
        final int n = size - removed + added.length;
        change(n);
        System.arraycopy(items, at + removed, items, at + added.length, size - at - removed);
        System.arraycopy(added, 0, items, at, added.length);
        if (n < size) {
            Arrays.fill(items, n, size, null);
        }
        size = n;
        return this;
    }

    @Override
    MapValue indexMap() {
        return toImmutable().indexMap();
    }

    @Override
    void open(Walk.Level level) {
        level.vals = items;
        level.count = size;
    }

    @Override
    void pack(Packer p) {
        if (!p.enter()) {
            Walk.pack(this, p);
            return;
        }
        p.listHeader(size);
        for (int i = 0; i < size; i++) {
            items[i].pack(p);
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
        out.append('[');
        for (int i = 0; i < size; i++) {
            if (i != 0) {
                out.append(',');
            }
            items[i].jsonify(out, depth + 1);
        }
        out.append(']');
    }

    @Override
    public boolean equals(Object o) {
        return o == this || (o instanceof ListValue && toImmutable().equals(((ListValue) o).toImmutable()));
    }

    @Override
    public int hashCode() {
        return toImmutable().hashCode();
    }
}
