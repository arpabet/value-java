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

/** A list with a value at every index: a MessagePack array. */
final class DenseList extends ListValue {

    static final DenseList EMPTY = new DenseList(new Value[0]);

    private final Value[] items;
    int hash;

    /** Takes the array, which holds no null and nothing mutable, without a copy. */
    DenseList(Value[] items) {
        this.items = items;
    }

    /** The values, for a mutable list to start from. The array is not to be changed. */
    Value[] array() {
        return items;
    }

    @Override
    public ListValue toImmutable() {
        return this;
    }

    @Override
    public ListValue toMutable() {
        return new MutableDenseList(this);
    }

    @Override
    public boolean updateAt(int index, UnaryOperator<Value> updater) {
        return false;
    }

    static DenseList copyOf(Value[] src) {
        if (src.length == 0) {
            return EMPTY;
        }
        final Value[] items = new Value[src.length];
        for (int i = 0; i < src.length; i++) {
            items[i] = element(src[i]);
        }
        return new DenseList(items);
    }

    @Override
    public boolean isSparse() {
        return false;
    }

    @Override
    public int size() {
        return items.length;
    }

    @Override
    public int count() {
        return items.length;
    }

    @Override
    public Value get(int index) {
        return index >= 0 && index < items.length ? items[index] : NullValue.NULL;
    }

    @Override
    public List<Value> values() {
        return Collections.unmodifiableList(Arrays.asList(items));
    }

    @Override
    public List<Item> items() {
        return new AbstractList<Item>() {
            @Override
            public Item get(int i) {
                return new Item(i, items[i]);
            }

            @Override
            public int size() {
                return items.length;
            }
        };
    }

    @Override
    public ListValue append(Value value) {
        final Value[] out = Arrays.copyOf(items, items.length + 1);
        out[items.length] = element(value);
        return new DenseList(out);
    }

    @Override
    public ListValue putAt(int index, Value value) {
        if (index < 0) {
            return this;
        }
        final int n = items.length;
        final Value[] out = Arrays.copyOf(items, Math.max(index + 1, n));
        for (int i = n; i < index; i++) {
            out[i] = NullValue.NULL;
        }
        out[index] = element(value);
        return new DenseList(out);
    }

    @Override
    public ListValue insertAt(int index, Value value) {
        if (index < 0) {
            return this;
        }
        if (index >= items.length) {
            return append(value);
        }
        return splice(index, 0, new Value[] {element(value)});
    }

    @Override
    public ListValue removeAt(int index) {
        if (index < 0 || index >= items.length) {
            return this;
        }
        return splice(index, 1, new Value[0]);
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
        return splice(Math.min(index, items.length), 0, elements(values));
    }

    @Override
    public ListValue deleteAll(int index) {
        return removeAt(index);
    }

    /** The list with {@code removed} items at {@code at} replaced by {@code added}. */
    private DenseList splice(int at, int removed, Value[] added) {
        final Value[] out = new Value[items.length - removed + added.length];
        System.arraycopy(items, 0, out, 0, at);
        System.arraycopy(added, 0, out, at, added.length);
        System.arraycopy(items, at + removed, out, at + added.length, items.length - at - removed);
        return out.length == 0 ? EMPTY : new DenseList(out);
    }

    @Override
    MapValue indexMap() {
        final String[] keys = new String[items.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = Integer.toString(i);
        }
        return MapValue.of(keys, items.clone(), false);
    }

    @Override
    void open(Walk.Level level) {
        level.vals = items;
        level.count = items.length;
    }

    @Override
    void pack(Packer p) {
        if (!p.enter()) {
            Walk.pack(this, p);
            return;
        }
        p.listHeader(items.length);
        for (Value v : items) {
            v.pack(p);
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
        for (int i = 0; i < items.length; i++) {
            if (i != 0) {
                out.append(',');
            }
            items[i].jsonify(out, depth + 1);
        }
        out.append(']');
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Value && same((Value) o, 0);
    }

    @Override
    boolean same(Value o, int spent) {
        if (o instanceof MutableDenseList) {
            o = o.toImmutable();
        }
        if (o == this) {
            return true;
        }
        if (!(o instanceof DenseList)) {
            return false;
        }
        final Value[] other = ((DenseList) o).items;
        if (other.length != items.length) {
            return false;
        }
        return spent >= Walk.deep ? Walk.equal(this, o) : Walk.sameValues(items, other, spent);
    }

    /** What {@code Arrays.hashCode} gives for the values. */
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
            h = Walk.hashValues(items, spent);
            hash = h;
        }
        return h;
    }
}
