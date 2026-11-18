/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A list, dense or sparse.
 *
 * <p>A <em>dense</em> list holds a value at every index and encodes as a
 * MessagePack array. A <em>sparse</em> list is keyed by index, holds only the
 * indexes that were put, and encodes as a MessagePack map with integer keys;
 * it may hold several values under one index, which is how a repeated field of
 * a numeric schema travels. The two never encode alike and are never equal.
 *
 * <h2>Immutable and mutable lists</h2>
 *
 * A list is one or the other from the moment it is made, and
 * {@link #isMutable()} tells which.
 *
 * <p>An <em>immutable</em> list ({@link Value#list}, {@link Value#emptyList()},
 * and every list read from bytes or text) never changes: {@code append} and
 * the like leave it as it is and return another list. It is safe to share
 * between threads.
 *
 * <p>A <em>mutable</em> list ({@link Value#mutableList()},
 * {@link Value#mutableSparseList()}, {@link #toMutable()}) is an ordinary
 * mutable object, as an {@code ArrayList} is: the same methods change the
 * list itself, and return it so that calls can be chained. Appending does not
 * copy what is already there.
 *
 * <pre>{@code
 * ListValue rows = Value.mutableList();
 * for (Row row : result) {
 *     rows.append(encode(row));          // rows itself grows
 * }
 * rows.updateAt(0, first -> Value.utf8("header"));
 * }</pre>
 *
 * <p>A mutable list holds what is put into it as it is: a mutable list or map
 * inside it is that same object, and changing it later shows in the list.
 * An immutable list cannot hold something that changes, so it takes a mutable
 * list or map as an immutable copy of what it is at that moment.
 *
 * <p>A mutable list is a value like any other: it packs, seals, prints and
 * compares by what it holds when asked, and equals the immutable list of the
 * same values. {@link #toImmutable()} gives an immutable copy, all the way
 * down. A mutable list must not be changed by one thread while another uses
 * it, and must not come to hold itself.
 */
public abstract sealed class ListValue extends Value implements Iterable<Value>
        permits DenseList, SparseList, MutableDenseList, MutableSparseList {

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

    /**
     * The list itself when it is immutable; otherwise an immutable list of
     * what this one holds now, with every mutable list or map inside it
     * copied the same way.
     */
    @Override
    public abstract ListValue toImmutable();

    /**
     * A new mutable list of the values this one holds, dense or sparse as it
     * is. The values are not copied: a mutable list or map among them is in
     * both lists.
     */
    public abstract ListValue toMutable();

    /**
     * Replaces the first value at an index of a mutable list by what the
     * function makes of it, in place; null from the function is the null
     * value. It is the Go implementation's {@code UpdateAt}, and like it does
     * nothing to an immutable list.
     *
     * @return whether a value was replaced: false for an immutable list, and where the list has no value
     */
    public abstract boolean updateAt(int index, UnaryOperator<Value> updater);

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

    /** Tells where the values are, and the indexes of a sparse list, to go through them without recursion. */
    abstract void open(Walk.Level level);

    @Override
    public final String toString() {
        return jsonify();
    }

    /**
     * A value as an immutable list or map holds it: the null value for null,
     * and an immutable copy of a mutable list or map, so that nothing an
     * immutable value holds ever changes.
     */
    static Value element(Value v) {
        return v == null ? NullValue.NULL : v.toImmutable();
    }

    static Value[] elements(Collection<? extends Value> values) {
        final Value[] out = values.toArray(new Value[0]);
        for (int i = 0; i < out.length; i++) {
            out[i] = element(out[i]);
        }
        return out;
    }

    /**
     * A value as a mutable list or map holds it: the null value for null, and
     * any other value itself, a mutable one included.
     */
    static Value held(Value owner, Value v) {
        if (v == owner) {
            throw new IllegalArgumentException("a list or a map cannot hold itself");
        }
        return v == null ? NullValue.NULL : v;
    }

    static Value[] heldAll(Value owner, Collection<? extends Value> values) {
        final Value[] out = values.toArray(new Value[0]);
        for (int i = 0; i < out.length; i++) {
            out[i] = held(owner, out[i]);
        }
        return out;
    }

    /** What is thrown where a mutable list or map is found to hold itself through others. */
    static ValueException cyclic() {
        return new ValueException("a mutable list or map holds itself");
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
