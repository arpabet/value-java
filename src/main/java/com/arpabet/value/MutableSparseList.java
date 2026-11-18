/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A sparse list that changes in place. Its state is a {@link SparseList}
 * that every change replaces by the next; unlike a list of its own, that one
 * holds the values as they were given, mutable lists and maps included, and
 * is handed out as an immutable list only when it holds none.
 */
final class MutableSparseList extends ListValue {

    private SparseList now;

    MutableSparseList(SparseList now) {
        this.now = now;
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public boolean isSparse() {
        return true;
    }

    @Override
    public int size() {
        return now.size();
    }

    @Override
    public int count() {
        return now.count();
    }

    @Override
    public Value get(int index) {
        return now.get(index);
    }

    @Override
    public ListValue toImmutable() {
        try {
            return now.frozen();
        } catch (StackOverflowError e) {
            throw cyclic();
        }
    }

    @Override
    public ListValue toMutable() {
        return new MutableSparseList(now);
    }

    @Override
    public List<Value> values() {
        return now.values();
    }

    @Override
    public List<Item> items() {
        return now.items();
    }

    @Override
    public ListValue append(Value value) {
        now = now.appended(held(this, value));
        return this;
    }

    @Override
    public ListValue putAt(int index, Value value) {
        now = now.put(index, held(this, value));
        return this;
    }

    @Override
    public boolean updateAt(int index, UnaryOperator<Value> updater) {
        int at = now.position(index);
        if (at < 0) {
            return false;
        }
        final Value v = held(this, updater.apply(now.at(at)));
        at = now.position(index); // the function may have changed the list
        if (at < 0) {
            return false;
        }
        now = now.replaced(at, v);
        return true;
    }

    @Override
    public ListValue insertAt(int index, Value value) {
        now = now.inserted(index, new Value[] {held(this, value)});
        return this;
    }

    @Override
    public ListValue removeAt(int index) {
        now = (SparseList) now.removeAt(index);
        return this;
    }

    @Override
    public List<Value> select(int index) {
        return now.select(index);
    }

    @Override
    public ListValue insertAll(int index, Collection<? extends Value> values) {
        now = now.inserted(index, heldAll(this, values));
        return this;
    }

    @Override
    public ListValue deleteAll(int index) {
        now = (SparseList) now.deleteAll(index);
        return this;
    }

    @Override
    MapValue indexMap() {
        return toImmutable().indexMap();
    }

    @Override
    void open(Walk.Level level) {
        now.open(level);
    }

    @Override
    void pack(Packer p) {
        if (!p.enter()) {
            Walk.pack(this, p);
            return;
        }
        p.leave();
        now.pack(p);
    }

    @Override
    void jsonify(StringBuilder out) {
        jsonify(out, 0);
    }

    @Override
    void jsonify(StringBuilder out, int depth) {
        if (depth >= Walk.deep) {
            Walk.jsonify(this, out);
        } else {
            now.jsonify(out, depth);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o == this || (o instanceof ListValue && toImmutable().equals(((ListValue) o).toImmutable()));
    }

    @Override
    public int hashCode() {
        // Not the hash of the state, which the state would keep: what it holds may change.
        return toImmutable().hashCode();
    }
}
