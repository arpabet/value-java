/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Lists and maps that change in place, which the Go implementation calls mutable. */
class MutableTest {

    private static Value v(long n) {
        return Value.ofLong(n);
    }

    // --- the examples of the Go implementation's README -----------------------------

    @Test
    void theyBuildWhatTheGoExamplesBuild() {
        ListValue l = Value.mutableList();
        l = l.append(v(1));
        l = l.append(Value.utf8("x"));
        assertEquals("9201a178", l.hex());
        assertEquals("[1,\"x\"]", l.jsonify());

        ListValue s = Value.mutableSparseList();
        s = s.putAt(0, v(10));
        s = s.putAt(5, v(50));
        assertEquals("82000a0532", s.hex());

        final MapValue m = Value.mutableMap().put("name", Value.utf8("ann")).put("age", v(30));
        assertEquals("{\"age\": 30,\"name\": \"ann\"}", m.jsonify());

        // The order of building does not show in the bytes.
        final MapValue a = Value.mutableMap().put("zebra", v(1)).put("apple", v(2));
        assertEquals("82a56170706c6502a57a6562726101", a.hex());
        assertEquals(Value.emptyMap().put("apple", v(2)).put("zebra", v(1)), a);
    }

    // --- in place ----------------------------------------------------------------

    @Test
    void aMutableCollectionChangesItselfAndAnImmutableOneDoesNot() {
        final ListValue list = Value.mutableList();
        assertTrue(list.isMutable());
        assertSame(list, list.append(v(1)));
        assertSame(list, list.putAt(3, v(4)));
        assertSame(list, list.insertAt(0, v(0)));
        assertSame(list, list.removeAt(1));
        assertSame(list, list.insertAll(1, Arrays.asList(v(7), null)));
        assertSame(list, list.deleteAll(0));
        assertEquals("[7,null,null,null,4]", list.toString());
        assertEquals(5, list.size());

        final ListValue sparse = Value.mutableSparseList();
        assertTrue(sparse.isMutable() && sparse.isSparse());
        assertSame(sparse, sparse.append(v(1)));
        assertSame(sparse, sparse.putAt(9, v(9)));
        assertSame(sparse, sparse.insertAt(9, v(8)));
        assertSame(sparse, sparse.insertAll(4, Arrays.asList(v(4), v(5))));
        assertSame(sparse, sparse.removeAt(0));
        assertSame(sparse, sparse.deleteAll(4));
        assertEquals("{\"9\": 8,\"9\": 9}", sparse.toString());
        assertEquals(10, sparse.size());
        assertEquals(2, sparse.count());

        final MapValue map = Value.mutableMap();
        assertTrue(map.isMutable());
        assertSame(map, map.put("b", v(2)));
        assertSame(map, map.insert("a", v(1)));
        assertSame(map, map.insertAll("c", Arrays.asList(v(3), v(4))));
        assertSame(map, map.remove("b"));
        assertSame(map, map.deleteAll("c"));
        assertSame(map, map.remove("nothing"));
        assertEquals("{\"a\": 1}", map.toString());
        assertEquals(1, map.size());
        assertFalse(map.isEmpty());
        assertTrue(map.containsKey("a"));

        // The immutable ones give another and stay as they are.
        final ListValue fixed = Value.emptyList();
        assertFalse(fixed.isMutable());
        assertNotSame(fixed, fixed.append(v(1)));
        assertEquals(0, fixed.size());
        final MapValue fixedMap = Value.emptyMap();
        assertFalse(fixedMap.isMutable());
        assertNotSame(fixedMap, fixedMap.put("a", v(1)));
        assertTrue(fixedMap.isEmpty());
        assertFalse(Value.ofLong(1).isMutable());
        assertFalse(Value.utf8("x").isMutable());
    }

    @Test
    void updateReplacesAValueInPlace() {
        final MapValue map = Value.mutableMap().put("n", v(1)).insert("twice", v(20)).insert("twice", v(10));
        assertTrue(map.update("n", old -> ((NumberValue) old).add(Value.ofLong(41))));
        assertEquals(v(42), map.get("n"));
        assertFalse(map.update("absent", old -> v(1)));
        assertFalse(map.containsKey("absent"));
        // Of several entries under a key, the first.
        assertTrue(map.update("twice", old -> v(11)));
        assertEquals(Arrays.asList(v(11), v(20)), map.select("twice"));
        // Null is the null value, as everywhere.
        assertTrue(map.update("n", old -> null));
        assertTrue(map.get("n").isNull() && map.containsKey("n"));

        final ListValue list = Value.mutableList().append(v(1)).append(v(2));
        assertTrue(list.updateAt(1, old -> Value.utf8("two")));
        assertFalse(list.updateAt(2, old -> v(0)));
        assertFalse(list.updateAt(-1, old -> v(0)));
        assertEquals("[1,\"two\"]", list.toString());

        final ListValue sparse = Value.mutableSparseList().putAt(5, v(5)).insertAt(5, v(4));
        assertTrue(sparse.updateAt(5, old -> v(44)));
        assertFalse(sparse.updateAt(4, old -> v(0)));
        assertEquals(Arrays.asList(v(44), v(5)), sparse.select(5));

        // Nothing immutable is updated, and the function is not asked.
        final int[] calls = {0};
        assertFalse(Value.emptyMap().put("n", v(1)).update("n", old -> v(calls[0]++)));
        assertFalse(Value.list(v(1)).updateAt(0, old -> v(calls[0]++)));
        assertFalse(Value.emptySparseList().putAt(0, v(1)).updateAt(0, old -> v(calls[0]++)));
        assertEquals(0, calls[0]);

        // A function that changes the collection it is updating.
        final MapValue reentrant = Value.mutableMap().put("a", v(1)).put("b", v(2));
        assertTrue(reentrant.update("a", old -> {
            reentrant.put("c", v(3));
            return v(10);
        }));
        assertEquals("{\"a\": 10,\"b\": 2,\"c\": 3}", reentrant.toString());
        assertFalse(reentrant.update("a", old -> {
            reentrant.remove("a");
            return v(11);
        }));
        assertEquals("{\"b\": 2,\"c\": 3}", reentrant.toString());
        final ListValue shrinking = Value.mutableList().append(v(1)).append(v(2));
        assertFalse(shrinking.updateAt(1, old -> {
            shrinking.removeAt(0);
            return v(9);
        }));
        assertEquals("[2]", shrinking.toString());
    }

    // --- what is held never changes ------------------------------------------------

    @Test
    void whatACollectionHoldsIsImmutable() {
        final ListValue inner = Value.mutableList().append(v(1));
        final MapValue outer = Value.mutableMap().put("inner", inner);
        inner.append(v(2));
        assertEquals("{\"inner\": [1]}", outer.toString(), "a later change showed in the map it was put into");
        assertFalse(outer.get("inner").isMutable());
        assertFalse(outer.getList("inner").isMutable());

        // The same into immutable collections, and through every way in.
        final MapValue building = Value.mutableMap().put("k", v(1));
        final List<Value> held = new ArrayList<>();
        held.add(Value.list(building, inner).get(0));
        held.add(Value.listOf(Collections.singletonList(building)).get(0));
        held.add(Value.emptyList().append(building).get(0));
        held.add(Value.emptyList().putAt(2, building).get(2));
        held.add(Value.emptyList().insertAt(0, building).get(0));
        held.add(Value.emptyList().insertAll(0, Collections.singletonList(building)).get(0));
        held.add(Value.emptySparseList().append(building).get(0));
        held.add(Value.emptySparseList().putAt(3, building).get(3));
        held.add(Value.emptySparseList().insertAt(3, building).get(3));
        held.add(Value.emptySparseList().insertAll(3, Collections.singletonList(building)).get(3));
        held.add(Value.emptyMap().put("m", building).get("m"));
        held.add(Value.emptyMap().insert("m", building).get("m"));
        held.add(Value.emptyMap().insertAll("m", Collections.singletonList(building)).get("m"));
        held.add(Value.mapOf(Collections.singletonMap("m", building)).get("m"));
        held.add(MapValue.builder().put("m", building).build().get("m"));
        held.add(MapValue.builder().putAll(Value.mutableMap().put("m", building)).build().get("m"));
        held.add(Value.mutableList().append(building).get(0));
        held.add(Value.mutableList().putAt(1, building).get(1));
        held.add(Value.mutableList().append(v(0)).insertAt(0, building).get(0));
        held.add(Value.mutableList().insertAll(0, Collections.singletonList(building)).get(0));
        held.add(Value.mutableSparseList().putAt(8, building).get(8));
        held.add(Value.mutableMap().put("m", building).get("m"));
        held.add(Value.mutableMap().put("m", v(0)).update("m", old -> building) ? building.toImmutable() : null);
        final ListValue updated = Value.mutableList().append(v(0));
        updated.updateAt(0, old -> building);
        held.add(updated.get(0));
        building.put("k", v(2)).put("later", v(3));
        for (int i = 0; i < held.size(); i++) {
            assertFalse(held.get(i).isMutable(), "way " + i);
            assertEquals("{\"k\": 1}", held.get(i).toString(), "way " + i);
        }

        // A collection put into itself holds what it was.
        final ListValue self = Value.mutableList().append(v(1));
        self.append(self);
        assertEquals("[1,[1]]", self.toString());
        final MapValue selfMap = Value.mutableMap().put("a", v(1));
        selfMap.put("me", selfMap);
        assertEquals("{\"a\": 1,\"me\": {\"a\": 1}}", selfMap.toString());
    }

    @Test
    void snapshotsAndCopiesAreIndependent() {
        final ListValue list = Value.mutableList().append(v(1));
        final ListValue frozen = list.toImmutable();
        assertFalse(frozen.isMutable());
        assertSame(frozen, list.toImmutable(), "nothing changed in between");
        assertSame(frozen, frozen.toImmutable());
        final List<Value> values = list.values();
        final List<ListValue.Item> items = list.items();
        list.append(v(2));
        assertEquals("[1]", frozen.toString());
        assertEquals(1, values.size());
        assertEquals(1, items.size());
        assertEquals("[1,2]", list.toImmutable().toString());

        final ListValue copy = frozen.toMutable();
        assertTrue(copy.isMutable());
        copy.append(v(3));
        assertEquals("[1]", frozen.toString());
        assertEquals("[1,3]", copy.toString());
        final ListValue copyOfCopy = copy.toMutable();
        copyOfCopy.removeAt(0);
        assertEquals("[1,3]", copy.toString());
        assertEquals("[3]", copyOfCopy.toString());

        final ListValue sparse = Value.emptySparseList().putAt(2, v(2)).toMutable();
        assertTrue(sparse.isSparse() && sparse.isMutable());
        final ListValue sparseFrozen = sparse.toImmutable();
        sparse.putAt(4, v(4));
        assertEquals("{\"2\": 2}", sparseFrozen.toString());
        assertEquals("{\"2\": 2,\"4\": 4}", sparse.toString());
        assertEquals("{\"2\": 2,\"4\": 4}", sparse.toMutable().toImmutable().toString());

        final MapValue map = Value.mutableMap().put("a", v(1));
        final MapValue frozenMap = map.toImmutable();
        final List<String> keys = map.keys();
        final List<MapValue.Entry> entries = map.entries();
        final List<Value> mapValues = map.values();
        final Map<String, Value> javaMap = map.toMap();
        map.put("b", v(2));
        assertEquals("{\"a\": 1}", frozenMap.toString());
        assertEquals(Collections.singletonList("a"), keys);
        assertEquals(1, entries.size());
        assertEquals(1, mapValues.size());
        assertEquals(1, javaMap.size());
        assertFalse(frozenMap.isMutable());
        assertSame(frozenMap, frozenMap.toImmutable());
        final MapValue mapCopy = map.toMutable();
        mapCopy.remove("a");
        assertEquals("{\"a\": 1,\"b\": 2}", map.toString());
        assertEquals("{\"b\": 2}", mapCopy.toString());

        // Going over a collection while changing it goes over what it was.
        final ListValue growing = Value.mutableList().append(v(1)).append(v(2));
        for (Value each : growing) {
            growing.append(each);
        }
        assertEquals("[1,2,1,2]", growing.toString());
        final MapValue shrinking = Value.mutableMap().put("a", v(1)).put("b", v(2));
        for (MapValue.Entry e : shrinking) {
            shrinking.remove(e.key());
        }
        assertTrue(shrinking.isEmpty());

        // Any value has an immutable form, itself unless it is mutable.
        final Value scalar = Value.utf8("x");
        assertSame(scalar, scalar.toImmutable());
        assertSame(NullValue.NULL, Value.ofNull().toImmutable());
    }

    // --- a value like any other ---------------------------------------------------

    @Test
    void equalityIsByContent() {
        final ListValue mutable = Value.mutableList().append(v(1)).append(v(2));
        final ListValue fixed = Value.list(v(1), v(2));
        assertEquals(fixed, mutable);
        assertEquals(mutable, fixed);
        assertEquals(fixed.hashCode(), mutable.hashCode());
        assertEquals(mutable, mutable.toMutable());
        assertNotEquals(mutable, Value.list(v(1)));
        assertNotEquals(Value.list(v(1)), mutable);
        mutable.append(v(3));
        assertNotEquals(fixed, mutable);
        assertNotEquals(mutable, fixed);

        // Dense and sparse are not equal, mutable or not.
        final ListValue sparse = Value.mutableSparseList().putAt(0, v(1)).putAt(1, v(2));
        assertNotEquals(fixed, sparse);
        assertNotEquals(sparse, fixed);
        assertNotEquals(sparse, Value.mutableList().append(v(1)).append(v(2)));
        assertNotEquals(Value.mutableList().append(v(1)).append(v(2)), sparse);
        assertEquals(Value.emptySparseList().putAt(0, v(1)).putAt(1, v(2)), sparse);
        assertEquals(sparse, Value.emptySparseList().putAt(1, v(2)).putAt(0, v(1)));
        assertEquals(sparse.hashCode(), sparse.toImmutable().hashCode());
        assertNotEquals(Value.mutableList(), Value.mutableSparseList());
        assertEquals(Value.mutableList(), Value.emptyList());
        assertEquals(Value.emptySparseList(), Value.mutableSparseList());

        final MapValue map = Value.mutableMap().put("a", v(1));
        assertEquals(Value.emptyMap().put("a", v(1)), map);
        assertEquals(map, Value.emptyMap().put("a", v(1)));
        assertEquals(map, Value.mutableMap().put("a", v(1)));
        assertEquals(map.hashCode(), Value.emptyMap().put("a", v(1)).hashCode());
        assertNotEquals(map, Value.emptyMap());
        assertNotEquals(Value.emptyMap(), map);
        assertNotEquals(map, mutable);
        assertFalse(map.equals(null));
        assertEquals(Value.mutableMap(), Value.emptyMap());

        // As a key, while it does not change.
        final Map<Value, String> byValue = new HashMap<>();
        byValue.put(map, "found");
        assertEquals("found", byValue.get(Value.emptyMap().put("a", v(1))));
    }

    @Test
    void theyPackSealAndConvertAsWhatTheyHold() {
        final MapValue message = Value.mutableMap()
                .put("to", Value.utf8("alice"))
                .put("amounts", Value.mutableList().append(v(100)).append(v(250)))
                .put("fields", Value.mutableSparseList().putAt(7, Value.ofBool(true)));
        final MapValue same = MapValue.builder()
                .put("to", "alice")
                .put("amounts", Value.list(v(100), v(250)))
                .put("fields", Value.emptySparseList().putAt(7, Value.ofBool(true)))
                .build();
        assertEquals(same.hex(), message.hex());
        assertEquals(same.toJson(JsonMode.CANONICAL), message.toJson(JsonMode.CANONICAL));
        assertEquals(same.toJson(JsonMode.RELAXED), message.toJson(JsonMode.RELAXED));
        assertEquals(Hex.encode(same.hash("SHA-256")), Hex.encode(message.hash("SHA-256")));
        assertEquals(same.getList("amounts"), message.getList("amounts"));
        assertEquals(same.getMap("amounts"), message.getMap("amounts"));
        assertEquals(same.getList("to"), message.getList("to"));

        // What is read back is immutable, whatever was written.
        final Value back = Value.unpack(message.pack());
        assertEquals(same, back);
        assertFalse(back.isMutable());
        assertFalse(((MapValue) back).getList("amounts").isMutable());
        assertFalse(Value.parseJson(message.toJson(JsonMode.CANONICAL)).isMutable());
        assertFalse(Value.unpack(Value.mutableSparseList().putAt(1, v(1)).pack()).isMutable());

        final byte[] alice = Sealer.newPrivateKey();
        final byte[] bob = Sealer.newPrivateKey();
        final Value opened = Value.unseal(message.seal(Sealer.publicKey(bob), alice), Sealer.publicKey(alice), bob);
        assertEquals(same, opened);
        assertFalse(opened.isMutable());

        // An object that holds one is written with what it holds then.
        final Holder holder = new Holder();
        holder.payload = message;
        final MapValue marshalled = (MapValue) ValueCodec.DEFAULT.marshal(holder);
        message.put("to", Value.utf8("mallory"));
        assertEquals(same, marshalled.get("payload"));
        assertFalse(marshalled.get("payload").isMutable());
        assertFalse(marshalled.isMutable());
        // A value read into a field is immutable.
        assertFalse(ValueCodec.DEFAULT.unmarshal(marshalled, Holder.class).payload.isMutable());
        // The value of a mutable collection itself is what it holds now.
        assertFalse(ValueCodec.DEFAULT.marshal(message).isMutable());
        assertEquals("mallory", ((MapValue) ValueCodec.DEFAULT.marshal(message)).getString("to").utf8());
    }

    static final class Holder {
        @Name("payload") Value payload;
    }

    // --- the same as the immutable ones, step by step ----------------------------------

    private static Value randomValue(Random random) {
        switch (random.nextInt(6)) {
            case 0:
                return null;
            case 1:
                return Value.utf8("s" + random.nextInt(5));
            case 2:
                return Value.list(v(random.nextInt(3)));
            case 3:
                return Value.mutableList().append(v(random.nextInt(3)));
            default:
                return v(random.nextInt(10));
        }
    }

    private static void same(ListValue want, ListValue got, String step) {
        assertEquals(want, got, step);
        assertEquals(got, want, step);
        assertEquals(want.hashCode(), got.hashCode(), step);
        assertEquals(want.hex(), got.hex(), step);
        assertEquals(want.toString(), got.toString(), step);
        assertEquals(want.toJson(JsonMode.CANONICAL), got.toJson(JsonMode.CANONICAL), step);
        assertEquals(want.isSparse(), got.isSparse(), step);
        assertEquals(want.size(), got.size(), step);
        assertEquals(want.count(), got.count(), step);
        assertEquals(want.values(), got.values(), step);
        assertEquals(want.items(), got.items(), step);
        assertEquals(Coerce.toMap(want), Coerce.toMap(got), step);
        assertFalse(Coerce.toMap(got).isMutable(), step);
        for (int i = -1; i <= want.size() + 1 && i < 60; i++) {
            assertEquals(want.get(i), got.get(i), step);
            assertEquals(want.select(i), got.select(i), step);
        }
        assertEquals(want, got.toImmutable(), step);
        assertFalse(got.toImmutable().isMutable(), step);
    }

    @Test
    void aMutableListDoesWhatAnImmutableOneDoes() {
        final Random random = new Random(20261118);
        for (int round = 0; round < 400; round++) {
            final boolean sparse = round % 2 == 1;
            ListValue want = sparse ? Value.emptySparseList() : Value.emptyList();
            final ListValue got = sparse ? Value.mutableSparseList() : Value.mutableList();
            final StringBuilder steps = new StringBuilder();
            for (int step = 0; step < 40; step++) {
                final int index = random.nextInt(14) - 2;
                final Value value = randomValue(random);
                final int op = random.nextInt(8);
                steps.append(op).append('@').append(index).append(' ');
                switch (op) {
                    case 0:
                        want = want.append(value);
                        assertSame(got, got.append(value));
                        break;
                    case 1:
                        want = want.putAt(index, value);
                        assertSame(got, got.putAt(index, value));
                        break;
                    case 2:
                        want = want.insertAt(index, value);
                        assertSame(got, got.insertAt(index, value));
                        break;
                    case 3:
                        want = want.removeAt(index);
                        assertSame(got, got.removeAt(index));
                        break;
                    case 4: {
                        final List<Value> values = new ArrayList<>();
                        for (int i = random.nextInt(4); i > 0; i--) {
                            values.add(randomValue(random));
                        }
                        want = want.insertAll(index, values);
                        assertSame(got, got.insertAll(index, values));
                        break;
                    }
                    case 5:
                        want = want.deleteAll(index);
                        assertSame(got, got.deleteAll(index));
                        break;
                    case 6: {
                        // What update does is put at an index that holds a value.
                        final boolean held = !want.select(index).isEmpty() || (!sparse && index >= 0 && index < want.size());
                        assertEquals(held, got.updateAt(index, old -> value), steps.toString());
                        if (held) {
                            want = want.putAt(index, value);
                        }
                        break;
                    }
                    default: {
                        // A copy goes its own way.
                        final ListValue copy = got.toMutable();
                        assertNotSame(got, copy.append(v(99)));
                        assertEquals(want.count() + 1, copy.count(), steps.toString());
                        break;
                    }
                }
                same(want, got, steps.toString());
            }
        }
    }

    @Test
    void aMutableMapDoesWhatAnImmutableOneDoes() {
        final Random random = new Random(7);
        final String[] keys = {"a", "b", "c", "é", "", "zz"};
        for (int round = 0; round < 400; round++) {
            MapValue want = Value.emptyMap();
            final MapValue got = Value.mutableMap();
            final StringBuilder steps = new StringBuilder();
            for (int step = 0; step < 40; step++) {
                final String key = keys[random.nextInt(keys.length)];
                final Value value = randomValue(random);
                final int op = random.nextInt(6);
                steps.append(op).append('@').append(key).append(' ');
                switch (op) {
                    case 0:
                        want = want.put(key, value);
                        assertSame(got, got.put(key, value));
                        break;
                    case 1:
                        want = want.insert(key, value);
                        assertSame(got, got.insert(key, value));
                        break;
                    case 2:
                        want = want.remove(key);
                        assertSame(got, got.remove(key));
                        break;
                    case 3: {
                        final List<Value> values = new ArrayList<>();
                        for (int i = random.nextInt(4); i > 0; i--) {
                            values.add(randomValue(random));
                        }
                        want = want.insertAll(key, values);
                        assertSame(got, got.insertAll(key, values));
                        break;
                    }
                    case 4:
                        want = want.deleteAll(key);
                        assertSame(got, got.deleteAll(key));
                        break;
                    default: {
                        final boolean held = want.containsKey(key);
                        assertEquals(held, got.update(key, old -> value), steps.toString());
                        if (held) {
                            want = want.put(key, value);
                        }
                        break;
                    }
                }
                final String at = steps.toString();
                assertEquals(want, got, at);
                assertEquals(got, want, at);
                assertEquals(want.hashCode(), got.hashCode(), at);
                assertEquals(want.hex(), got.hex(), at);
                assertEquals(want.toString(), got.toString(), at);
                assertEquals(want.size(), got.size(), at);
                assertEquals(want.isEmpty(), got.isEmpty(), at);
                assertEquals(want.keys(), got.keys(), at);
                assertEquals(want.values(), got.values(), at);
                assertEquals(want.entries(), got.entries(), at);
                assertEquals(want.toMap(), got.toMap(), at);
                assertEquals(Coerce.toList(want), Coerce.toList(got), at);
                assertFalse(Coerce.toList(got).isMutable(), at);
                for (String k : keys) {
                    assertEquals(want.get(k), got.get(k), at);
                    assertEquals(want.select(k), got.select(k), at);
                    assertEquals(want.containsKey(k), got.containsKey(k), at);
                }
                assertEquals(want, got.toImmutable(), at);
            }
        }
    }

    /** Appending to a mutable list does not copy the list: this many appends to an immutable one would not end. */
    @Test
    void appendingToAMutableListIsCheap() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            final ListValue list = Value.mutableList();
            for (int i = 0; i < 2_000_000; i++) {
                list.append(v(i & 127));
            }
            assertEquals(2_000_000, list.size());
            assertEquals(v(5), list.get(5));
            assertEquals(2_000_005, list.pack().length);
        });
    }

    @Test
    void nullKeysAreRefusedAsEverywhere() {
        assertThrows(NullPointerException.class, () -> Value.mutableMap().put(null, v(1)));
        assertThrows(NullPointerException.class, () -> Value.mutableMap().update(null, old -> old));
        assertThrows(NullPointerException.class, () -> Value.mutableMap().get(null));
    }
}
