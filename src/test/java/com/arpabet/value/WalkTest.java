/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Lists and maps are gone through without recursion (packing, past the first
 * levels; printing, comparing and hashing always). Here that way is held
 * against the plain recursive one, written out in the test, on values of every
 * shape and of depths around the one where packing changes its way.
 */
class WalkTest {

    private static final int DEEP = Walk.deep;

    @AfterEach
    void asTheLibraryComes() {
        Walk.deep = DEEP;
    }

    // --- values --------------------------------------------------------------

    private static Value random(Random random, int depth) {
        if (depth <= 0 || random.nextInt(4) == 0) {
            switch (random.nextInt(6)) {
                case 0:
                    return Value.ofNull();
                case 1:
                    return Value.utf8("s" + random.nextInt(4));
                case 2:
                    return Value.raw(new byte[] {(byte) random.nextInt(3)});
                case 3:
                    return Value.ofBool(random.nextBoolean());
                case 4:
                    return Value.ofDouble(random.nextInt(3) / 2.0);
                default:
                    return Value.ofLong(random.nextInt(5));
            }
        }
        final int n = random.nextInt(4);
        switch (random.nextInt(3)) {
            case 0: {
                final Value[] items = new Value[n];
                for (int i = 0; i < n; i++) {
                    items[i] = random(random, depth - 1);
                }
                return Value.list(items);
            }
            case 1: {
                // Never empty: an empty sparse list packs as the empty map, and reads back as one.
                ListValue sparse = Value.emptySparseList();
                for (int i = 0; i <= n; i++) {
                    sparse = sparse.insertAt(random.nextInt(6), random(random, depth - 1));
                }
                return sparse;
            }
            default: {
                MapValue map = Value.emptyMap();
                for (int i = 0; i < n; i++) {
                    map = map.insert("k" + random.nextInt(4), random(random, depth - 1));
                }
                return map;
            }
        }
    }

    /** A chain of the given depth, of lists, maps and sparse lists in turn, each with a value beside the next. */
    private static Value chain(int depth, Value leaf) {
        Value v = leaf;
        for (int i = 0; i < depth; i++) {
            switch (i % 3) {
                case 0:
                    v = Value.list(Value.utf8("x"), v, Value.ofLong(i));
                    break;
                case 1:
                    v = Value.emptyMap().put("a", Value.ofLong(i)).put("k", v).put("z", Value.ofNull());
                    break;
                default:
                    v = Value.emptySparseList().putAt(1, Value.ofBool(true)).putAt(4, v).putAt(9, Value.utf8("y"));
                    break;
            }
        }
        return v;
    }

    // --- the recursive way, by the public methods ---------------------------------

    private static void packRecursively(Value v, Packer p) {
        if (v instanceof MapValue) {
            final MapValue m = (MapValue) v;
            p.mapHeader(m.size());
            for (MapValue.Entry e : m.entries()) {
                p.str(e.key());
                packRecursively(e.value(), p);
            }
        } else if (v instanceof ListValue && ((ListValue) v).isSparse()) {
            final List<ListValue.Item> items = ((ListValue) v).items();
            p.mapHeader(items.size());
            for (ListValue.Item item : items) {
                p.integer(item.key());
                packRecursively(item.value(), p);
            }
        } else if (v instanceof ListValue) {
            final List<Value> values = ((ListValue) v).values();
            p.listHeader(values.size());
            for (Value each : values) {
                packRecursively(each, p);
            }
        } else {
            v.pack(p);
        }
    }

    private static String hexRecursively(Value v) {
        final Packer p = new Packer();
        packRecursively(v, p);
        return Hex.encode(p.toByteArray());
    }

    private static void printRecursively(Value v, StringBuilder out) {
        if (v instanceof MapValue) {
            out.append('{');
            int i = 0;
            for (MapValue.Entry e : ((MapValue) v).entries()) {
                out.append(i++ == 0 ? "" : ",").append('"').append(e.key()).append("\": ");
                printRecursively(e.value(), out);
            }
            out.append('}');
        } else if (v instanceof ListValue && ((ListValue) v).isSparse()) {
            out.append('{');
            int i = 0;
            for (ListValue.Item item : ((ListValue) v).items()) {
                out.append(i++ == 0 ? "" : ",").append('"').append(item.key()).append("\": ");
                printRecursively(item.value(), out);
            }
            out.append('}');
        } else if (v instanceof ListValue) {
            out.append('[');
            int i = 0;
            for (Value each : ((ListValue) v).values()) {
                out.append(i++ == 0 ? "" : ",");
                printRecursively(each, out);
            }
            out.append(']');
        } else {
            out.append(v.jsonify());
        }
    }

    private static int hashRecursively(Value v) {
        if (v instanceof MapValue) {
            final MapValue m = (MapValue) v;
            int values = 1;
            for (Value each : m.values()) {
                values = 31 * values + hashRecursively(each);
            }
            return m.keys().hashCode() * 31 + values;
        }
        if (v instanceof ListValue && ((ListValue) v).isSparse()) {
            int keys = 1;
            int values = 1;
            for (ListValue.Item item : ((ListValue) v).items()) {
                keys = 31 * keys + item.key();
                values = 31 * values + hashRecursively(item.value());
            }
            return keys * 31 + values;
        }
        if (v instanceof ListValue) {
            int values = 1;
            for (Value each : ((ListValue) v).values()) {
                values = 31 * values + hashRecursively(each);
            }
            return values;
        }
        return v.hashCode();
    }

    private static boolean equalRecursively(Value a, Value b) {
        if (a instanceof MapValue) {
            if (!(b instanceof MapValue) || !((MapValue) a).keys().equals(((MapValue) b).keys())) {
                return false;
            }
            return equalRecursively(((MapValue) a).values(), ((MapValue) b).values());
        }
        if (a instanceof ListValue) {
            if (!(b instanceof ListValue) || ((ListValue) a).isSparse() != ((ListValue) b).isSparse()) {
                return false;
            }
            final List<ListValue.Item> x = ((ListValue) a).items();
            final List<ListValue.Item> y = ((ListValue) b).items();
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (x.get(i).key() != y.get(i).key() || !equalRecursively(x.get(i).value(), y.get(i).value())) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean equalRecursively(List<Value> x, List<Value> y) {
        for (int i = 0; i < x.size(); i++) {
            if (!equalRecursively(x.get(i), y.get(i))) {
                return false;
            }
        }
        return true;
    }

    // --- the tests -----------------------------------------------------------

    private static void check(Value v, String what) {
        final String want = hexRecursively(v);
        final StringBuilder text = new StringBuilder();
        printRecursively(v, text);
        final int hash = hashRecursively(v);
        // With recursion all the way, for the first levels only, and not at all.
        for (int deep : new int[] {Integer.MAX_VALUE, DEEP, 3, 2, 1, 0}) {
            Walk.deep = deep;
            final String how = what + ", with recursion to " + deep;
            assertEquals(want, v.hex(), how);
            assertEquals(text.toString(), v.jsonify(), how);
            if (Walk.container(v)) {
                // The text of a list or a map is its JSON; that of a string is the string.
                assertEquals(text.toString(), v.toString(), how);
            }
            // A value read anew has no hash yet, nor has anything in it.
            final Value again = Value.unpack(v.pack(), Limits.DEFAULT.withMaxDepth(0));
            assertTrue(v.equals(again) && again.equals(v), how);
            assertEquals(hash, again.hashCode(), how);
            assertEquals(hash, v.hashCode(), how);
            assertEquals(v.toJson(JsonMode.CANONICAL, Limits.DEFAULT.withMaxDepth(0)),
                    again.toJson(JsonMode.CANONICAL, Limits.DEFAULT.withMaxDepth(0)), how);
        }
        // The JSON is the same whichever way it was written.
        final Limits unlimited = Limits.DEFAULT.withMaxDepth(0);
        Walk.deep = Integer.MAX_VALUE;
        final String canonical = v.toJson(JsonMode.CANONICAL, unlimited);
        final String relaxed = v.toJson(JsonMode.RELAXED, unlimited);
        for (int deep : new int[] {DEEP, 2, 1, 0}) {
            Walk.deep = deep;
            assertEquals(canonical, v.toJson(JsonMode.CANONICAL, unlimited), what + ", with recursion to " + deep);
            assertEquals(relaxed, v.toJson(JsonMode.RELAXED, unlimited), what + ", with recursion to " + deep);
        }
        Walk.deep = DEEP;
        // And reads back, unless it holds a key twice, which JSON does not carry.
        Value back = null;
        try {
            back = Value.parseJson(canonical, unlimited);
        } catch (ValueException e) {
            assertTrue(e.getMessage().contains("duplicate key"), what + ": " + e.getMessage());
        }
        if (back != null) {
            assertEquals(v, back, what);
        }
    }

    @Test
    void randomValuesOfEveryShape() {
        final Random random = new Random(20261118);
        for (int i = 0; i < 3000; i++) {
            final Value v = random(random, 1 + random.nextInt(6));
            check(v, v.toString());
            // And against another value: equal exactly when the recursive way says so.
            final Value other = random.nextInt(3) == 0 ? Value.unpack(v.pack()) : random(random, 1 + random.nextInt(4));
            Walk.deep = new int[] {0, 1, 2, DEEP}[random.nextInt(4)];
            assertEquals(equalRecursively(v, other), v.equals(other), v + " and " + other);
            assertEquals(equalRecursively(other, v), other.equals(v), other + " and " + v);
            if (v.equals(other)) {
                assertEquals(v.hashCode(), other.hashCode());
            }
        }
    }

    @Test
    void chainsOfTheDepthsAroundTheOneWherePackingChangesItsWay() {
        for (int depth : new int[] {0, 1, 2, 3, DEEP - 2, DEEP - 1, DEEP, DEEP + 1, DEEP + 2, 2 * DEEP, 2 * DEEP + 1, 300}) {
            check(chain(depth, Value.ofLong(7)), "a chain of " + depth);
        }
    }

    @Test
    void aDifferenceAtAnyDepthIsADifference() {
        for (int depth : new int[] {1, 2, 3, 5, DEEP, DEEP + 1, 200}) {
            Walk.deep = new int[] {0, 1, DEEP, Integer.MAX_VALUE}[depth % 4];
            final Value v = chain(depth, Value.ofLong(7));
            final String what = "a chain of " + depth + ", with recursion to " + Walk.deep;
            // Another leaf, of another value and of another kind.
            assertNotEquals(v, chain(depth, Value.ofLong(8)), what);
            assertNotEquals(v, chain(depth, Value.ofDouble(7)), what);
            assertNotEquals(v, chain(depth, Value.list()), what);
            assertNotEquals(chain(depth, Value.list()), chain(depth, Value.emptySparseList()), what);
            assertNotEquals(chain(depth, Value.emptyMap()), chain(depth, Value.emptySparseList()), what);
            assertNotEquals(chain(depth, Value.emptyMap()), chain(depth, Value.list()), what);
            assertNotEquals(chain(depth, Value.list(Value.ofLong(1))), chain(depth, Value.list(Value.ofLong(1), Value.ofLong(1))), what);
            assertNotEquals(chain(depth, Value.emptyMap().put("a", Value.ofLong(1))),
                    chain(depth, Value.emptyMap().put("b", Value.ofLong(1))), what);
            assertNotEquals(chain(depth, Value.emptySparseList().putAt(1, Value.ofLong(1))),
                    chain(depth, Value.emptySparseList().putAt(2, Value.ofLong(1))), what);
            // One level more or less.
            assertNotEquals(v, chain(depth + 1, Value.ofLong(7)), what);
            assertNotEquals(chain(depth + 1, Value.ofLong(7)), v, what);
            // And the same again is the same, with the same hash.
            assertEquals(v, chain(depth, Value.ofLong(7)), what);
            assertEquals(v.hashCode(), chain(depth, Value.ofLong(7)).hashCode(), what);
        }
    }

    @Test
    void hashingAListOrAMapHashesWhatItHoldsOnce() {
        final Value inner = Value.list(Value.ofLong(1), Value.utf8("x"));
        final Value middle = Value.emptyMap().put("inner", inner);
        final Value outer = Value.emptySparseList().putAt(3, middle);
        assertEquals(0, Walk.known(inner));
        assertEquals(0, Walk.known(middle));
        final int hash = outer.hashCode();
        assertEquals(hashRecursively(outer), hash);
        assertEquals(inner.hashCode(), Walk.known(inner));
        assertEquals(middle.hashCode(), Walk.known(middle));
        assertEquals(hash, Walk.known(outer));
        assertEquals(Arrays.hashCode(new Value[] {Value.ofLong(1), Value.utf8("x")}), inner.hashCode());
        // A mutable one has the hash of what it holds at the time.
        final ListValue growing = Value.mutableList().append(Value.ofLong(1));
        assertEquals(Value.list(Value.ofLong(1)).hashCode(), growing.hashCode());
        growing.append(Value.utf8("x"));
        assertEquals(inner.hashCode(), growing.hashCode());
        assertEquals(inner, growing);
    }
}
