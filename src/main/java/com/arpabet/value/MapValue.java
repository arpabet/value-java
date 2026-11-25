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
import java.util.function.UnaryOperator;

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
 * <p>Like the Go implementation's, a map may hold several entries under one
 * key ({@link #insert}, {@link #select}), which a map read from bytes that
 * repeat a key does; {@link #get} gives the first of them.
 *
 * <h2>Immutable and mutable maps</h2>
 *
 * A map is one or the other from the moment it is made, and
 * {@link #isMutable()} tells which.
 *
 * <p>An <em>immutable</em> map ({@link #builder()}, {@link Value#emptyMap()},
 * {@link Value#mapOf}, and every map read from bytes or text) never changes:
 * {@link #put}, {@link #remove} and the like leave it as it is and return
 * another map. It is safe to share between threads.
 *
 * <p>A <em>mutable</em> map ({@link Value#mutableMap()}, {@link #toMutable()})
 * is an ordinary mutable object, as a {@code TreeMap} is: the same methods
 * change the map itself, and return it so that calls can be chained;
 * {@link #update} replaces a value in place.
 *
 * <pre>{@code
 * MapValue event = Value.mutableMap();
 * event.put("op", Value.utf8("put"));              // event itself has the entry now
 * ListValue keys = Value.mutableList();
 * event.put("keys", keys);
 * keys.append(Value.utf8("k1"));                   // and shows in event: it is the same list
 * }</pre>
 *
 * <p>A mutable map holds what is put into it as it is: a mutable list or map
 * inside it is that same object, and changing it later shows in the map. An
 * immutable map cannot hold something that changes, so it takes a mutable
 * list or map as an immutable copy of what it is at that moment.
 *
 * <p>A mutable map is a value like any other: it packs, seals, prints and
 * compares by what it holds when asked, and equals the immutable map of the
 * same entries. {@link #toImmutable()} gives an immutable copy, all the way
 * down. A mutable map must not be changed by one thread while another uses
 * it, and must not come to hold itself.
 */
public final class MapValue extends Value implements Iterable<MapValue.Entry> {

    static final MapValue EMPTY = new MapValue(new String[0], new Value[0]);

    /** Canonical strings, ascending by their bytes; equal keys keep the order they were given in. */
    private final String[] keys;
    private final Value[] vals;
    /**
     * Null in an immutable map. A mutable map has no entries of its own: it
     * holds here its state, a map that every change replaces by the next.
     * Unlike a map of its own, the state holds the values as they were given,
     * mutable lists and maps included, and is handed out as an immutable map
     * only when it holds none.
     */
    private final MapValue[] now;
    private int hash;

    private MapValue(String[] keys, Value[] vals) {
        this.keys = keys;
        this.vals = vals;
        this.now = null;
    }

    /** A mutable map that starts as an immutable one. */
    private MapValue(MapValue start) {
        this.keys = null;
        this.vals = null;
        this.now = new MapValue[] {start};
    }

    /** Where the entries are: this map, or the state of a mutable one. */
    private MapValue self() {
        return now == null ? this : now[0];
    }

    /** A value as this map holds it: an immutable one an immutable copy, a mutable one the value itself. */
    private Value hold(Value v) {
        return now == null ? ListValue.element(v) : ListValue.held(this, v);
    }

    /** This map when nothing it holds is mutable; otherwise a copy that holds immutable copies. */
    private MapValue frozen() {
        Value[] v = null;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].isMutable()) {
                if (v == null) {
                    v = vals.clone();
                }
                v[i] = vals[i].toImmutable();
            }
        }
        return v == null ? this : new MapValue(keys, v);
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
    private static int lowerBound(String[] keys, String key) {
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

    private static int run(String[] keys, int from, String key) {
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

    @Override
    public boolean isMutable() {
        return now != null;
    }

    /**
     * The map itself when it is immutable; otherwise an immutable map of what
     * this one holds now, with every mutable list or map inside it copied the
     * same way.
     */
    @Override
    public MapValue toImmutable() {
        if (now == null) {
            return this;
        }
        try {
            return now[0].frozen();
        } catch (StackOverflowError e) {
            throw ListValue.cyclic();
        }
    }

    /**
     * A new mutable map of the entries this one has. The values are not
     * copied: a mutable list or map among them is in both maps.
     */
    public MapValue toMutable() {
        return new MapValue(self());
    }

    /** The number of entries. */
    public int size() {
        return self().keys.length;
    }

    public boolean isEmpty() {
        return self().keys.length == 0;
    }

    /** Reports whether the map has an entry under the key, whatever its value. */
    public boolean containsKey(String key) {
        key = key(key);
        final String[] keys = self().keys;
        final int i = lowerBound(keys, key);
        return i < keys.length && keys[i].equals(key);
    }

    /** The value under a key, or the null value when the map has none. */
    public Value get(String key) {
        key = key(key);
        final MapValue m = self();
        final int i = lowerBound(m.keys, key);
        return i < m.keys.length && m.keys[i].equals(key) ? m.vals[i] : NullValue.NULL;
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

    /** The keys in their order, one per entry. Of a mutable map, the keys it has now. */
    public List<String> keys() {
        return Collections.unmodifiableList(Arrays.asList(self().keys));
    }

    /** The values in the order of their keys. Of a mutable map, the values it has now. */
    public List<Value> values() {
        return Collections.unmodifiableList(Arrays.asList(self().vals));
    }

    /**
     * The entries in the order of their keys, which is the order they encode
     * in. Of a mutable map, the entries it has now.
     */
    public List<Entry> entries() {
        final MapValue m = self();
        return new AbstractList<Entry>() {
            @Override
            public Entry get(int i) {
                return new Entry(m.keys[i], m.vals[i]);
            }

            @Override
            public int size() {
                return m.keys.length;
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
        final MapValue m = self();
        final Map<String, Value> out = new LinkedHashMap<String, Value>(Math.max(16, m.keys.length * 4 / 3 + 1));
        for (int i = 0; i < m.keys.length; i++) {
            out.put(m.keys[i], m.vals[i]);
        }
        return out;
    }

    /** A map with the value under the key, in place of the first one there or new. */
    public MapValue put(String key, Value value) {
        key = key(key);
        final Value[] added = {hold(value)};
        final MapValue m = self();
        final int i = lowerBound(m.keys, key);
        final boolean found = i < m.keys.length && m.keys[i].equals(key);
        return become(m.splice(i, found ? 1 : 0, key, added));
    }

    /**
     * What a method that changes the map gives: the map it made, or, of a
     * mutable map, the map itself, which is that one from now on.
     */
    private MapValue become(MapValue next) {
        if (now == null) {
            return next;
        }
        now[0] = next;
        return this;
    }

    /** A map with one more entry under the key, before any it has there. */
    public MapValue insert(String key, Value value) {
        key = key(key);
        final Value[] added = {hold(value)};
        final MapValue m = self();
        return become(m.splice(lowerBound(m.keys, key), 0, key, added));
    }

    /**
     * Replaces the value of the first entry under a key of a mutable map by
     * what the function makes of it, in place; null from the function is the
     * null value. It is the Go implementation's {@code Update}, and like it
     * does nothing to an immutable map.
     *
     * @return whether a value was replaced: false for an immutable map, and when the map has no such key
     */
    public boolean update(String key, UnaryOperator<Value> updater) {
        if (now == null) {
            return false;
        }
        key = key(key);
        final MapValue m = now[0];
        final int i = lowerBound(m.keys, key);
        if (i == m.keys.length || !m.keys[i].equals(key)) {
            return false;
        }
        final Value v = ListValue.held(this, updater.apply(m.vals[i]));
        // The function may have changed the map; the entry is looked up again.
        final MapValue after = now[0];
        final int at = lowerBound(after.keys, key);
        if (at == after.keys.length || !after.keys[at].equals(key)) {
            return false;
        }
        final Value[] vals = after.vals.clone();
        vals[at] = v;
        now[0] = new MapValue(after.keys, vals);
        return true;
    }

    /** A map without the first entry under the key. */
    public MapValue remove(String key) {
        key = key(key);
        final MapValue m = self();
        final int i = lowerBound(m.keys, key);
        if (i == m.keys.length || !m.keys[i].equals(key)) {
            return this;
        }
        return become(m.splice(i, 1, key, new Value[0]));
    }

    /** Every value under the key. */
    public List<Value> select(String key) {
        key = key(key);
        final MapValue m = self();
        final int i = lowerBound(m.keys, key);
        final int n = run(m.keys, i, key);
        return Collections.unmodifiableList(new ArrayList<Value>(Arrays.asList(m.vals).subList(i, i + n)));
    }

    /** A map with an entry under the key for each of the values, before any it has there. */
    public MapValue insertAll(String key, Collection<? extends Value> values) {
        if (values.isEmpty()) {
            return this;
        }
        key = key(key);
        final Value[] added = now == null ? ListValue.elements(values) : ListValue.heldAll(this, values);
        final MapValue m = self();
        return become(m.splice(lowerBound(m.keys, key), 0, key, added));
    }

    /** A map without any entry under the key. */
    public MapValue deleteAll(String key) {
        key = key(key);
        final MapValue m = self();
        final int i = lowerBound(m.keys, key);
        final int n = run(m.keys, i, key);
        return n == 0 ? this : become(m.splice(i, n, key, new Value[0]));
    }

    /**
     * This immutable map with {@code removed} entries at position {@code at}
     * replaced by {@code added}, all under one key.
     */
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

    /** The values as an immutable dense list, in the order of their keys. */
    ListValue valueList() {
        final Value[] vals = toImmutable().vals;
        return vals.length == 0 ? DenseList.EMPTY : new DenseList(vals.clone());
    }

    /** The value of the last entry under a canonical key, or null when the map has none. */
    Value last(String key) {
        final MapValue m = self();
        final int i = lowerBound(m.keys, key);
        final int n = run(m.keys, i, key);
        return n == 0 ? null : m.vals[i + n - 1];
    }

    String keyAt(int i) {
        return self().keys[i];
    }

    Value valueAt(int i) {
        return self().vals[i];
    }

    /** Tells where the keys and the values are, to go through them without recursion. */
    void open(Walk.Level level) {
        final MapValue m = self();
        level.keys = m.keys;
        level.vals = m.vals;
        level.count = m.keys.length;
    }

    @Override
    void pack(Packer p) {
        if (!p.enter()) {
            Walk.pack(this, p);
            return;
        }
        final MapValue m = self();
        p.mapHeader(m.keys.length);
        for (int i = 0; i < m.keys.length; i++) {
            p.str(m.keys[i]);
            m.vals[i].pack(p);
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
        final MapValue m = self();
        out.append('{');
        for (int i = 0; i < m.keys.length; i++) {
            if (i != 0) {
                out.append(',');
            }
            // The key is quoted as text is, as Go quotes it.
            GoQuote.quote(out, m.keys[i]);
            out.append(": ");
            m.vals[i].jsonify(out, depth + 1);
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
        return same((MapValue) o, 0);
    }

    @Override
    boolean same(Value o, int spent) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof MapValue)) {
            return false;
        }
        final MapValue a = toImmutable();
        final MapValue b = ((MapValue) o).toImmutable();
        if (a == b) {
            return true;
        }
        if (!Arrays.equals(a.keys, b.keys)) {
            return false;
        }
        return spent >= Walk.deep ? Walk.equal(a, b) : Walk.sameValues(a.vals, b.vals, spent);
    }

    /** What {@code Arrays.hashCode} gives for the keys, times 31, plus what it gives for the values. */
    @Override
    public int hashCode() {
        return hash(0);
    }

    @Override
    int hash(int spent) {
        // Of a mutable map, the hash of an immutable copy: its state may hold what changes.
        final MapValue m = toImmutable();
        int h = m.hash;
        if (h == 0) {
            if (spent >= Walk.deep) {
                return Walk.hash(m);
            }
            h = Arrays.hashCode(m.keys) * 31 + Walk.hashValues(m.vals, spent);
            m.hash = h;
        }
        return h;
    }

    /** The hash an immutable map has kept, or 0 when it has none yet. */
    int knownHash() {
        return hash;
    }

    void keepHash(int h) {
        hash = h;
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
            vals.add(ListValue.element(value));
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
            final MapValue m = map.self();
            for (int i = 0; i < m.keys.length; i++) {
                put(m.keys[i], m.vals[i]);
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
