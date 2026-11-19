/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Decoding stays safe on hostile input: the limits of the Go implementation, enforced the same way. */
class HardeningTest {

    private static byte[] nested(int depth, int leaf) {
        final byte[] b = new byte[depth + 1];
        Arrays.fill(b, (byte) 0x91); // an array of one element: the next array
        b[depth] = (byte) leaf;
        return b;
    }

    private static String repeat(String s, int n) {
        final StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }

    @Test
    void aHeaderThatClaimsAHugeLengthIsRefusedNotAllocated() {
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("ddffffffff"))); // array32
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("dfffffffff"))); // map32
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("dbffffffff"))); // str32
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("c6ffffffff"))); // bin32
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("c9ffffffff07"))); // ext32
        // Within the limits but without the data: the claim allocates nothing.
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("dd00ffffff")));
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("c63fffffff")));
        assertThrows(ValueException.class, () -> Value.read(new ByteArrayInputStream(Hex.decode("c63fffffff0102"))));
        assertThrows(ValueException.class, () -> Value.read(new ByteArrayInputStream(Hex.decode("dd00ffffff01"))));
    }

    @Test
    void nestingIsBounded() {
        // A thousand levels is the limit, as in Go.
        assertEquals(Kind.LIST, Value.unpack(nested(1000, 0x01)).kind());
        assertThrows(ValueException.class, () -> Value.unpack(nested(1001, 0x01)));
        assertThrows(ValueException.class, () -> Value.unpack(nested(1001, 0x90)));
        assertThrows(ValueException.class, () -> Value.unpack(nested(1010, 0xc0)));

        final Limits shallow = Limits.DEFAULT.withMaxDepth(3);
        assertEquals("9191910a", Value.unpack(nested(3, 0x0a), shallow).hex());
        assertThrows(ValueException.class, () -> Value.unpack(nested(4, 0x0a), shallow));

        assertEquals(Kind.LIST, Value.parseJson(repeat("[", 1001) + repeat("]", 1001)).kind());
        assertThrows(ValueException.class, () -> Value.parseJson(repeat("[", 1001) + "1" + repeat("]", 1001)));
        assertThrows(ValueException.class, () -> Value.parseJson(repeat("[", 1002) + repeat("]", 1002)));

        final Value deep = Value.unpack(nested(1000, 0x01));
        assertEquals(repeat("[", 1000) + "{\"$long\":\"1\"}" + repeat("]", 1000), deep.toJson(JsonMode.CANONICAL));
        assertThrows(ValueException.class, () -> Value.list(deep).toJson(JsonMode.CANONICAL));
        assertEquals(deep, Value.unpack(deep.pack()));
    }

    /**
     * The stack of the threads the tests below run on: a quarter of the
     * megabyte a thread has by default on Linux and on Android, of which the
     * library takes under 16 KB. Not the least stack a JVM gives, which it
     * gives when asked for less: that is another on every platform (it leaves
     * Java under 40 KB on Linux and 90 KB on a Mac with Apple silicon),
     * nothing to hold a test to.
     */
    private static final long SMALL_STACK = 256 * 1024;

    /** Runs on a thread with a small stack, and reports what was thrown there. */
    private static void onASmallStack(Runnable work) throws Exception {
        final Throwable[] failure = new Throwable[1];
        final Thread small = new Thread(null, () -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "small-stack", SMALL_STACK);
        small.start();
        small.join();
        if (failure[0] != null) {
            throw new AssertionError("on a small stack: " + failure[0], failure[0]);
        }
    }

    /** Maps in one another, each with one key, around a leaf. */
    private static byte[] nestedMaps(int depth, int leaf) {
        final byte[] maps = new byte[3 * depth + 1];
        for (int i = 0; i < depth; i++) {
            maps[3 * i] = (byte) 0x81;
            maps[3 * i + 1] = (byte) 0xa1;
            maps[3 * i + 2] = 'a';
        }
        maps[3 * depth] = (byte) leaf;
        return maps;
    }

    /** Lists, maps and sparse lists in turn, each with something beside the next one, around a leaf. */
    private static Value mixed(int depth, long leaf) {
        Value v = Value.ofLong(leaf);
        for (int i = 0; i < depth; i++) {
            switch (i % 3) {
                case 0:
                    v = Value.list(Value.utf8("x"), v);
                    break;
                case 1:
                    v = Value.emptyMap().put("k", v).put("n", Value.ofLong(i));
                    break;
                default:
                    v = Value.emptySparseList().putAt(2, Value.ofBool(true)).putAt(7, v);
                    break;
            }
        }
        return v;
    }

    /** How many lists and maps are in one another, going by the first list or map that each holds. */
    private static int depth(Value v) {
        int depth = 0;
        while (v instanceof ListValue || v instanceof MapValue) {
            depth++;
            Value inner = null;
            if (v instanceof ListValue) {
                for (ListValue.Item item : ((ListValue) v).items()) {
                    if (item.value() instanceof ListValue || item.value() instanceof MapValue) {
                        inner = item.value();
                        break;
                    }
                }
            } else {
                for (Value each : ((MapValue) v).values()) {
                    if (each instanceof ListValue || each instanceof MapValue) {
                        inner = each;
                        break;
                    }
                }
            }
            v = inner;
        }
        return depth;
    }

    @Test
    void decodingTakesNoStackForTheDepthOfAValue() throws Exception {
        // The decoder keeps the lists and maps it has open on a stack of its
        // own, so a thousand levels decode on a small thread stack.
        onASmallStack(() -> {
            assertEquals(1000, depth(Value.unpack(nested(1000, 0x01))));
            assertEquals(1000, depth(Value.unpack(nestedMaps(1000, 0x01))));
        });
    }

    /**
     * A decoder accepts a thousand levels, so whatever is done next with what
     * it gave has to work at that depth on a thread with a small stack as on
     * any other.
     */
    @Test
    void aThousandLevelsWorkOnASmallStack() throws Exception {
        final byte[] lists = nested(1000, 0x01);
        final byte[] maps = nestedMaps(1000, 0x01);
        final Value tree = mixed(999, 1);
        final byte[] trees = tree.pack();
        final String listJson = repeat("[", 1000) + "1" + repeat("]", 1000);
        final String mapJson = repeat("{\"a\":", 1000) + "1" + repeat("}", 1000);
        final String treeJson = tree.toJson(JsonMode.CANONICAL);
        final String treeRelaxed = tree.toJson(JsonMode.RELAXED);
        final String treeText = tree.toString();
        final byte[] key = Sealer.newPrivateKey();
        final byte[] peer = Sealer.publicKey(Sealer.newPrivateKey());
        // The JSON of a value nests deeper than the value: a wrapper is an
        // object, and a sparse list is two. Reading it back takes a limit to match.
        final Limits roomy = Limits.DEFAULT.withMaxDepth(3000);

        onASmallStack(() -> {
            for (byte[] bytes : new byte[][] {lists, maps, trees}) {
                final Value v = Value.unpack(bytes);
                final Value again = Value.unpack(bytes);
                // Canonical bytes, and everything made of them.
                assertEquals(Hex.encode(bytes), v.hex());
                assertEquals(32, v.hash("SHA-256").length);
                assertEquals(bytes.length + Sealer.OVERHEAD, v.seal(peer, key).length);
                // Equality and hashing, which keep nothing from an earlier call here.
                assertEquals(v, again);
                assertEquals(v.hashCode(), again.hashCode());
                assertFalse(v.equals(Value.unpack(lists == bytes ? maps : lists)));
                // Text.
                assertEquals(again.toString(), v.toString());
                assertEquals(v, Value.parseJson(v.toJson(JsonMode.CANONICAL), roomy));
                assertEquals(v.toJson(JsonMode.RELAXED), again.toJson(JsonMode.RELAXED));
                // The codec takes a value as it is, and a mutable copy is one level.
                assertEquals(v, ValueCodec.DEFAULT.marshal(v));
                assertEquals(v, v instanceof ListValue ? ((ListValue) v).toMutable() : ((MapValue) v).toMutable());
            }
            assertEquals(1000, depth(Value.parseJson(listJson)));
            assertEquals(1000, depth(Value.parseJson(mapJson)));
            assertEquals(treeJson, Value.parseJson(treeJson, roomy).toJson(JsonMode.CANONICAL));
            assertEquals(treeRelaxed, Value.unpack(trees).toJson(JsonMode.RELAXED));
            assertEquals(treeText, Value.unpack(trees).toString());
            assertEquals(treeText, Value.unpack(trees).jsonify());
            // One level more is refused by the limit, not by the stack.
            assertThrows(ValueException.class, () -> Value.parseJson("[" + listJson + "]"));
            assertThrows(ValueException.class, () -> Value.unpack(nested(1001, 0x01)));
        });
    }

    /**
     * None of it takes more of the stack for a deeper value. Without a limit
     * of depth, a hundred thousand lists in one another decode, pack, compare,
     * hash, print and go to JSON and back on a small stack, and so do as many
     * maps, and as many lists, maps and sparse lists in turn: going down all
     * of them by recursion would take megabytes of it.
     */
    @Test
    void noneOfItRecursesWithTheDepthOfAValue() throws Exception {
        final Limits unlimited = Limits.DEFAULT.withMaxDepth(0);
        final int depth = 100_000;
        final byte[][] shapes = {nested(depth, 0x01), nestedMaps(depth, 0x01), mixed(depth, 1).pack()};
        // The same but for the innermost value.
        final byte[][] others = {nested(depth, 0x02), nestedMaps(depth, 0x02), mixed(depth, 2).pack()};
        final String json = repeat("[", depth) + repeat("]", depth);
        final byte[] ours = Sealer.newPrivateKey();
        final byte[] theirs = Sealer.newPrivateKey();

        onASmallStack(() -> {
            for (int i = 0; i < shapes.length; i++) {
                final byte[] bytes = shapes[i];
                final Value v = Value.unpack(bytes, unlimited);
                final Value again = Value.unpack(bytes, unlimited);
                assertEquals(depth, depth(v));
                // Canonical bytes, and what is made of them.
                assertArrayEquals(bytes, v.pack());
                assertEquals(32, v.hash("SHA-256").length);
                final byte[] sealed = v.seal(Sealer.publicKey(theirs), ours);
                assertTrue(v.equals(Value.unseal(sealed, Sealer.publicKey(ours), theirs, unlimited)));
                // Equal all the way down, and another at the very bottom.
                assertTrue(v.equals(again));
                assertEquals(v.hashCode(), again.hashCode());
                assertFalse(v.equals(Value.unpack(others[i], unlimited)));
                // Text, and JSON there and back.
                assertEquals(again.toString(), v.toString());
                assertEquals(again.jsonify(), v.jsonify());
                assertEquals(again.toJson(JsonMode.RELAXED, unlimited), v.toJson(JsonMode.RELAXED, unlimited));
                assertTrue(v.equals(Value.parseJson(v.toJson(JsonMode.CANONICAL, unlimited), unlimited)));
            }
            // What lists around a 1 look like is known to the character.
            final Value lists = Value.unpack(shapes[0], unlimited);
            assertEquals(repeat("[", depth) + "1" + repeat("]", depth), lists.toString());
            assertEquals(lists.toString(), lists.toJson(JsonMode.RELAXED, unlimited));
            assertEquals(depth, depth(Value.parseJson(json, unlimited)));
            // With the limit of every day, it is the limit that speaks.
            assertThrows(ValueException.class, () -> lists.toJson(JsonMode.CANONICAL));
            assertThrows(ValueException.class, () -> Value.parseJson(json));
            assertThrows(ValueException.class, () -> Value.unpack(shapes[0]));
        });
    }

    @Test
    void theLimitsAreConfigurable() {
        final Limits two = Limits.DEFAULT.withMaxCollectionLength(2);
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("93010203"), two));
        assertEquals(2, ((ListValue) Value.unpack(Hex.decode("920102"), two)).size());
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("83a16101a16202a16303"), two));
        assertThrows(ValueException.class, () -> Value.parseJson("[1,2,3]", two));
        assertEquals(2, ((ListValue) Value.parseJson("[1,2]", two)).size());

        final Limits four = Limits.DEFAULT.withMaxByteLength(4);
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("d90568656c6c6f"), four)); // str8 of 5
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("a568656c6c6f"), four)); // fixstr of 5
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("c4050102030405"), four));
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("c705070102030405"), four));
        assertEquals("a468656c6c", Value.unpack(Hex.decode("a468656c6c"), four).hex());
        assertThrows(ValueException.class, () -> Value.parseJson("\"hello\"", four));

        final Limits hundred = Limits.DEFAULT.withMaxDecimalExponent(100);
        assertEquals(Kind.NUMBER, Value.unpack(Hex.decode("c70502000000640a"), hundred).kind()); // scale 100
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("c70502000000650a"), hundred)); // scale 101
        assertThrows(ValueException.class, () -> Value.unpack(Hex.decode("c70502ffffff9b0a"), hundred)); // scale -101
        assertEquals(Kind.NUMBER, Value.unpack(Hex.decode("c70502000000650a"), hundred.withMaxDecimalExponent(0)).kind());
        assertThrows(ValueException.class, () -> Value.parseJson("1e101", hundred));
        assertEquals(Limits.DEFAULT, hundred.withMaxDecimalExponent(1 << 20));
    }

    @Test
    void aHugeIndexMakesAMapNotASparseList() {
        // One enormous integer key: a sparse list would have that length.
        final Value v = Value.unpack(Hex.decode("81cf7fffffffffffffff01"));
        assertEquals(Kind.MAP, v.kind());
        assertEquals(1, ((MapValue) v).getNumber("9223372036854775807").longValue());
        // The largest index a sparse list reads back with, and the first that is a key.
        assertTrue(((ListValue) Value.unpack(Hex.decode("81ce0100000001"))).isSparse());
        assertEquals(Kind.MAP, Value.unpack(Hex.decode("81ce0100000101")).kind());
        assertEquals(Kind.MAP, Value.unpack(Hex.decode("81ff01")).kind());
    }

    @Test
    void integerKeysThenATextKeyMakeASortedMap() {
        // {0:0, 1:1, ... 10:10, "k":99}: "10" does not sort after "2" as text.
        final MapValue m = (MapValue) Value.unpack(Hex.decode("8c00000101020203030404050506060707080809090a0aa16b63"));
        assertEquals(12, m.size());
        assertEquals(0, m.getNumber("0").longValue());
        assertEquals(2, m.getNumber("2").longValue());
        assertEquals(10, m.getNumber("10").longValue());
        assertEquals(99, m.getNumber("k").longValue());
        assertEquals("10", m.keys().get(2));
    }

    @Test
    void whatIsNotAValueIsRefused() {
        for (String hex : new String[] {
            "", "a261", "82a16101", "92", "c7020701", "d5ff0000", "d8ff00000000000000000000000000000000", "c705ff0000000000",
            "d7ffee6b280000000000", "c70cff3b9aca000000000000000000", "c70001", "d6020000000a", "c7040200000002", "cb00", "d9", "c4"
        }) {
            assertThrows(ValueException.class, () -> Value.unpack(Hex.decode(hex)), hex);
        }
        for (String json : new String[] {
            "", "{", "[1,]", "{\"a\":1,}", "01", "+1", ".5", "1.", "1e", "-", "tru", "nul", "\"abc", "{\"a\":1,\"a\":2}", "\"\\ud800\"",
            "\"\\udc00x\"", "\"\\ud800A\"", "\"\u0001\"", "1 2", "{\"$long\":\"x\"}", "{\"$long\":5}", "{\"$double\":\"1e999\"}",
            "{\"$sparse\":{\"01\":1}}", "{\"$ext\":{\"type\":200,\"base64\":\"\"}}", "{\"$map\":1}", "\"\uD83D\""
        }) {
            assertThrows(ValueException.class, () -> Value.parseJson(json), json);
        }
        assertThrows(ValueException.class, () -> Value.parseJson(new byte[] {'"', (byte) 0xff, '"'}));
    }
}
