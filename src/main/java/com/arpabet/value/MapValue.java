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
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A map from text keys to values, kept in the order of the keys' UTF-8 bytes,
 * which is the order it encodes in: the bytes of a map depend on what it
 * holds and never on how it was built.
 *
 * <pre>{@code
 * MapValue m = MapValue.builder()
 *         .put("zebra", 1)
 *         .put("apple", 2)
 *         .build();
 * m.hex();                         // 82a56170706c6502a57a6562726101
 * m.getNumber("apple").longValue() // 2
 * }</pre>
 *
 * <p>A map is immutable: {@link #put}, {@link #remove} and the like return
 * another map. Like the Go implementation's, it may hold several entries
 * under one key ({@link #insert}, {@link #select}), which a map read from
 * bytes that repeat a key does; {@link #get} gives the first of them.
 */
public final class MapValue extends Value implements Iterable<MapValue.Entry> {

    static final MapValue EMPTY = new MapValue(new String[0], new Value[0]);

    /** Canonical strings, ascending by their bytes; equal keys keep the order they were given in. */
    private final String[] keys;
    private final Value[] vals;
    private int hash;

    private MapValue(String[] keys, Value[] vals) {
        this.keys = keys;
        this.vals = vals;
    }

    /** Takes the arrays without a copy, putting them in order unless they are. */
    static MapValue of(String[] keys, Value[] vals, boolean sorted) {
        final int n = keys.length;
        if (n == 0) {
            return EMPTY;
        }
        if (!sorted) {
            sorted = true;
            for (int i = 1; i < n && sorted; i++) {
                sorted = Utf8.compare(keys[i - 1], keys[i]) <= 0;
            }
        }
        if (!sorted) {
            final Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            // A stable sort: entries under one key stay in the order they came in.
            Arrays.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Utf8.compare(keys[a], keys[b]);
                }
            });
            final String[] k = new String[n];
            final Value[] v = new Value[n];
            for (int i = 0; i < n; i++) {
                k[i] = keys[order[i]];
                v[i] = vals[order[i]];
            }
            return new MapValue(k, v);
        }
        return new MapValue(keys, vals);
    }

    /** A builder of a map from entries in any order. */
    public static Builder builder() {
        return new Builder();
    }

    private static String key(String key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        return Utf8.canonical(key);
    }

    /** The first position whose key is not below the key. */
    private int lowerBound(String key) {
        int lo = 0;
        int hi = keys.length;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (Utf8.compare(keys[mid], key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private int run(int from, String key) {
        int n = 0;
        while (from + n < keys.length && keys[from + n].equals(key)) {
            n++;
        }
        return n;
    }

    @Override
    public Kind kind() {
        return Kind.MAP;
    }

    /** The number of entries. */
    public int size() {
        return keys.length;
    }

    public boolean isEmpty() {
        return keys.length == 0;
    }

    /** Reports whether the map has an entry under the key, whatever its value. */
    public boolean containsKey(String key) {
        key = key(key);
        final int i = lowerBound(key);
        return i < keys.length && keys[i].equals(key);
    }

    /** The value under a key, or the null value when the map has none. */
    public Value get(String key) {
        key = key(key);
        final int i = lowerBound(key);
        return i < keys.length && keys[i].equals(key) ? vals[i] : NullValue.NULL;
    }

    /** The boolean under a key; false when there is none. A value of another kind is read from its text. */
    public BoolValue getBool(String key) {
        return Coerce.toBool(get(key));
    }

    /** The number under a key; 0 when there is none. A value of another kind is read from its text. */
    public NumberValue getNumber(String key) {
        return Coerce.toNumber(get(key));
    }

    /** The string under a key; the empty text when there is none. A value of another kind is its text. */
    public StringValue getString(String key) {
        return Coerce.toString(get(key));
    }

    /** The list under a key; a map gives its values, and anything else the empty list. */
    public ListValue getList(String key) {
        return Coerce.toList(get(key));
    }

    /** The map under a key; a list gives its items keyed by index, and anything else the empty map. */
    public MapValue getMap(String key) {
        return Coerce.toMap(get(key));
    }

    /** The keys in their order, one per entry. */
    public List<String> keys() {
        return Collections.unmodifiableList(Arrays.asList(keys));
    }

    /** The values in the order of their keys. */
    public List<Value> values() {
        return Collections.unmodifiableList(Arrays.asList(vals));
    }

    /** The entries in the order of their keys, which is the order they encode in. */
    public List<Entry> entries() {
        return new AbstractList<Entry>() {
            @Override
            public Entry get(int i) {
                return new Entry(keys[i], vals[i]);
            }

            @Override
            public int size() {
                return keys.length;
            }
        };
    }

    @Override
    public Iterator<Entry> iterator() {
        return entries().iterator();
    }

    /**
     * The entries as a Java map that iterates in the order of the keys. Where
     * the map has several entries under a key, the last one is kept.
     */
    public Map<String, Value> toMap() {
        final Map<String, Value> out = new LinkedHashMap<String, Value>(Math.max(16, keys.length * 4 / 3 + 1));
        for (int i = 0; i < keys.length; i++) {
            out.put(keys[i], vals[i]);
        }
        return out;
    }

    /** A map with the value under the key, in place of the first one there or new. */
    public MapValue put(String key, Value value) {
        key = key(key);
        final int i = lowerBound(key);
        final boolean found = i < keys.length && keys[i].equals(key);
        return splice(i, found ? 1 : 0, key, new Value[] {ListValue.orNull(value)});
    }

    /** A map with one more entry under the key, before any it has there. */
    public MapValue insert(String key, Value value) {
        key = key(key);
        return splice(lowerBound(key), 0, key, new Value[] {ListValue.orNull(value)});
    }

    /** A map without the first entry under the key. */
    public MapValue remove(String key) {
        key = key(key);
        final int i = lowerBound(key);
        if (i == keys.length || !keys[i].equals(key)) {
            return this;
        }
        return splice(i, 1, key, new Value[0]);
    }

    /** Every value under the key. */
    public List<Value> select(String key) {
        key = key(key);
        final int i = lowerBound(key);
        final int n = run(i, key);
        return Collections.unmodifiableList(new ArrayList<Value>(Arrays.asList(vals).subList(i, i + n)));
    }

    /** A map with an entry under the key for each of the values, before any it has there. */
    public MapValue insertAll(String key, Collection<? extends Value> values) {
        if (values.isEmpty()) {
            return this;
        }
        key = key(key);
        return splice(lowerBound(key), 0, key, ListValue.orNull(values));
    }

    /** A map without any entry under the key. */
    public MapValue deleteAll(String key) {
        key = key(key);
        final int i = lowerBound(key);
        final int n = run(i, key);
        return n == 0 ? this : splice(i, n, key, new Value[0]);
    }

    /** The map with {@code removed} entries at position {@code at} replaced by {@code added}, all under one key. */
    private MapValue splice(int at, int removed, String key, Value[] added) {
        final int n = keys.length - removed + added.length;
        if (n == 0) {
            return EMPTY;
        }
        final String[] k = new String[n];
        final Value[] v = new Value[n];
        System.arraycopy(keys, 0, k, 0, at);
        System.arraycopy(vals, 0, v, 0, at);
        Arrays.fill(k, at, at + added.length, key);
        System.arraycopy(added, 0, v, at, added.length);
        final int rest = keys.length - at - removed;
        System.arraycopy(keys, at + removed, k, at + added.length, rest);
        System.arraycopy(vals, at + removed, v, at + added.length, rest);
        return new MapValue(k, v);
    }

    /** The values as a dense list, in the order of their keys. */
    ListValue valueList() {
        return vals.length == 0 ? DenseList.EMPTY : new DenseList(vals.clone());
    }

    /** The value of the last entry under a canonical key, or null when the map has none. */
    Value last(String key) {
        final int i = lowerBound(key);
        final int n = run(i, key);
        return n == 0 ? null : vals[i + n - 1];
    }

    String keyAt(int i) {
        return keys[i];
    }

    Value valueAt(int i) {
        return vals[i];
    }

    @Override
    void pack(Packer p) {
        p.mapHeader(keys.length);
        for (int i = 0; i < keys.length; i++) {
            p.str(keys[i]);
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
            // The key is written as it is, without escapes, as Go writes it.
            out.append('"').append(keys[i]).append("\": ");
            vals[i].jsonify(out);
        }
        out.append('}');
    }

    @Override
    public String toString() {
        return jsonify();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof MapValue)) {
            return false;
        }
        final MapValue m = (MapValue) o;
        return Arrays.equals(m.keys, keys) && Arrays.equals(m.vals, vals);
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

    /** A key of a map and its value. */
    public static final class Entry {

        private final String key;
        private final Value value;

        Entry(String key, Value value) {
            this.key = key;
            this.value = value;
        }

        public String key() {
            return key;
        }

        public Value value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry && ((Entry) o).key.equals(key) && ((Entry) o).value.equals(value);
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + value.hashCode();
        }

        @Override
        public String toString() {
            return key + ": " + value.jsonify();
        }
    }

    /**
     * Builds a map from entries put in any order. A key put again replaces
     * the earlier entry; a null value, text or binary is the null value.
     */
    public static final class Builder {

        private final ArrayList<String> keys = new ArrayList<String>();
        private final ArrayList<Value> vals = new ArrayList<Value>();

        Builder() {
        }

        public Builder put(String key, Value value) {
            keys.add(key(key));
            vals.add(ListValue.orNull(value));
            return this;
        }

        /** Puts text. */
        public Builder put(String key, String text) {
            return put(key, text == null ? NullValue.NULL : Value.utf8(text));
        }

        /** Puts binary; the array is copied. */
        public Builder put(String key, byte[] binary) {
            return put(key, binary == null ? NullValue.NULL : Value.raw(binary));
        }

        /** Puts a long. */
        public Builder put(String key, long n) {
            return put(key, Value.ofLong(n));
        }

        /** Puts a double. */
        public Builder put(String key, double d) {
            return put(key, Value.ofDouble(d));
        }

        /** Puts a boolean. */
        public Builder put(String key, boolean b) {
            return put(key, Value.ofBool(b));
        }

        /** Puts the null value. */
        public Builder putNull(String key) {
            return put(key, NullValue.NULL);
        }

        /** Puts every entry of a map. */
        public Builder putAll(MapValue map) {
            for (int i = 0; i < map.keys.length; i++) {
                put(map.keys[i], map.vals[i]);
            }
            return this;
        }

        public MapValue build() {
            final int n = keys.size();
            if (n == 0) {
                return EMPTY;
            }
            final MapValue sorted = of(keys.toArray(new String[0]), vals.toArray(new Value[0]), false);
            // Of the entries under one key, the last one put stays.
            int unique = 0;
            for (int i = 0; i < n; i++) {
                if (i + 1 == n || !sorted.keys[i].equals(sorted.keys[i + 1])) {
                    unique++;
                }
            }
            if (unique == n) {
                return sorted;
            }
            final String[] k = new String[unique];
            final Value[] v = new Value[unique];
            int o = 0;
            for (int i = 0; i < n; i++) {
                if (i + 1 == n || !sorted.keys[i].equals(sorted.keys[i + 1])) {
                    k[o] = sorted.keys[i];
                    v[o++] = sorted.vals[i];
                }
            }
            return new MapValue(k, v);
        }
    }
}
