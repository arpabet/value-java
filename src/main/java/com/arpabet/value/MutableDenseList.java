/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A dense list that changes in place: the values in an array with room to
 * grow, so that appending does not copy what is already there.
 */
final class MutableDenseList extends ListValue {

    private static final Value[] NONE = new Value[0];

    private Value[] items;
    private int size;
    /** The immutable list of the same values, from when it is asked for until the next change. */
    private DenseList snapshot;

    MutableDenseList(DenseList from) {
        final Value[] values = from.array();
        this.items = Arrays.copyOf(values, Math.max(values.length, 8));
        this.size = values.length;
        this.snapshot = from;
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
        DenseList s = snapshot;
        if (s == null) {
            s = size == 0 ? DenseList.EMPTY : new DenseList(Arrays.copyOf(items, size));
            snapshot = s;
        }
        return s;
    }

    @Override
    public ListValue toMutable() {
        return new MutableDenseList((DenseList) toImmutable());
    }

    /** The values as they are now; the list does not change under the one returned. */
    @Override
    public List<Value> values() {
        return toImmutable().values();
    }

    @Override
    public List<Item> items() {
        return toImmutable().items();
    }

    @Override
    public ListValue append(Value value) {
        final Value v = element(value);
        change(size + 1);
        items[size++] = v;
        return this;
    }

    @Override
    public ListValue putAt(int index, Value value) {
        if (index < 0) {
            return this;
        }
        final Value v = element(value);
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
        final Value v = element(updater.apply(items[index]));
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
        return splice(index, 0, new Value[] {element(value)});
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
        final Value[] added = elements(values);
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
    void pack(Packer p) {
        p.listHeader(size);
        for (int i = 0; i < size; i++) {
            items[i].pack(p);
        }
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append('[');
        for (int i = 0; i < size; i++) {
            if (i != 0) {
                out.append(',');
            }
            items[i].jsonify(out);
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
