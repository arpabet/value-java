/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A sparse list that changes in place: the immutable list it is at the
 * moment, replaced by the next one at every change.
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
        return now;
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
        now = (SparseList) now.append(value);
        return this;
    }

    @Override
    public ListValue putAt(int index, Value value) {
        now = (SparseList) now.putAt(index, value);
        return this;
    }

    @Override
    public boolean updateAt(int index, UnaryOperator<Value> updater) {
        final SparseList next = now.updated(index, updater);
        if (next == null) {
            return false;
        }
        now = next;
        return true;
    }

    @Override
    public ListValue insertAt(int index, Value value) {
        now = (SparseList) now.insertAt(index, value);
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
        now = (SparseList) now.insertAll(index, values);
        return this;
    }

    @Override
    public ListValue deleteAll(int index) {
        now = (SparseList) now.deleteAll(index);
        return this;
    }

    @Override
    MapValue indexMap() {
        return now.indexMap();
    }

    @Override
    void pack(Packer p) {
        now.pack(p);
    }

    @Override
    void jsonify(StringBuilder out) {
        now.jsonify(out);
    }

    @Override
    public boolean equals(Object o) {
        return o == this || (o instanceof ListValue && now.equals(((ListValue) o).toImmutable()));
    }

    @Override
    public int hashCode() {
        return now.hashCode();
    }
}
