/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * A list, dense or sparse.
 *
 * <p>A <em>dense</em> list holds a value at every index and encodes as a
 * MessagePack array. A <em>sparse</em> list is keyed by index, holds only the
 * indexes that were put, and encodes as a MessagePack map with integer keys;
 * it may hold several values under one index, which is how a repeated field of
 * a numeric schema travels. The two never encode alike and are never equal.
 *
 * <p>Lists are immutable: the methods that change a list return another one.
 */
public abstract sealed class ListValue extends Value implements Iterable<Value> permits DenseList, SparseList {

    ListValue() {
    }

    @Override
    public final Kind kind() {
        return Kind.LIST;
    }

    /** Reports whether the list is keyed by index and encodes as a map. */
    public abstract boolean isSparse();

    /** The length of the list: for a sparse list, one more than its largest index. */
    public abstract int size();

    /** The number of values the list holds, which is its length unless it is sparse. */
    public abstract int count();

    /** The value at an index, or the null value where the list has none. */
    public abstract Value get(int index);

    /** The boolean at an index; false where there is none. A value of another kind is read from its text. */
    public final BoolValue getBool(int index) {
        return Coerce.toBool(get(index));
    }

    /** The number at an index; 0 where there is none. A value of another kind is read from its text. */
    public final NumberValue getNumber(int index) {
        return Coerce.toNumber(get(index));
    }

    /** The string at an index; the empty text where there is none. A value of another kind is its text. */
    public final StringValue getString(int index) {
        return Coerce.toString(get(index));
    }

    /** The list at an index; a map gives its values, and anything else the empty list. */
    public final ListValue getList(int index) {
        return Coerce.toList(get(index));
    }

    /** The map at an index; a list gives its items keyed by index, and anything else the empty map. */
    public final MapValue getMap(int index) {
        return Coerce.toMap(get(index));
    }

    /**
     * The values by index, as a list of {@link #size()} elements: the null
     * value where a sparse list has none, and the first value where it has
     * several.
     */
    public abstract List<Value> values();

    /** The values the list holds, each with its index, in the order they encode in. */
    public abstract List<Item> items();

    /** A list with the value after the last one. */
    public abstract ListValue append(Value value);

    /**
     * A list with the value at the index: in place of the first one there, or
     * new. A dense list is extended with null values up to the index, and is
     * left as it is for a negative one.
     */
    public abstract ListValue putAt(int index, Value value);

    /**
     * A list with the value inserted at the index. A dense list moves the
     * values from there on up by one, and appends for an index past its end; a
     * sparse list keeps what it holds at the index and puts the value before
     * it.
     */
    public abstract ListValue insertAt(int index, Value value);

    /** A list without the first value at the index; a dense list closes the gap. */
    public abstract ListValue removeAt(int index);

    /** Every value at the index: several only in a sparse list. */
    public abstract List<Value> select(int index);

    /** A list with the values inserted at the index, in their order. */
    public abstract ListValue insertAll(int index, Collection<? extends Value> values);

    /** A list without any value at the index. */
    public abstract ListValue deleteAll(int index);

    /** Iterates over {@link #values()}. */
    @Override
    public Iterator<Value> iterator() {
        return values().iterator();
    }

    /** The items as a map keyed by the decimal text of their index. */
    abstract MapValue indexMap();

    @Override
    public final String toString() {
        return jsonify();
    }

    static Value orNull(Value v) {
        return v == null ? NullValue.NULL : v;
    }

    static Value[] orNull(Collection<? extends Value> values) {
        final Value[] out = values.toArray(new Value[0]);
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) {
                out[i] = NullValue.NULL;
            }
        }
        return out;
    }

    /** A value of a list and its index. */
    public static final class Item {

        private final int key;
        private final Value value;

        Item(int key, Value value) {
            this.key = key;
            this.value = value;
        }

        /** The index. */
        public int key() {
            return key;
        }

        public Value value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Item && ((Item) o).key == key && ((Item) o).value.equals(value);
        }

        @Override
        public int hashCode() {
            return key * 31 + value.hashCode();
        }

        @Override
        public String toString() {
            return key + ": " + value.jsonify();
        }
    }
}
