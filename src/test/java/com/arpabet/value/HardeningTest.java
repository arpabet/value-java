/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void decodingDoesNotDependOnTheStack() throws Exception {
        // The decoder keeps the lists and maps it has open on a stack of its
        // own, so a thousand levels decode on the smallest of thread stacks.
        final Throwable[] failure = new Throwable[1];
        final Thread small = new Thread(null, () -> {
            try {
                assertEquals(Kind.LIST, Value.unpack(nested(1000, 0x01)).kind());
                final byte[] maps = new byte[3 * 1000 + 1];
                for (int i = 0; i < 1000; i++) {
                    maps[3 * i] = (byte) 0x81;
                    maps[3 * i + 1] = (byte) 0xa1;
                    maps[3 * i + 2] = 'a';
                }
                maps[3000] = 0x01;
                assertEquals(Kind.MAP, Value.unpack(maps).kind());
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "small-stack", 16 * 1024);
        small.start();
        small.join();
        if (failure[0] != null) {
            throw new AssertionError("decoding on a small stack", failure[0]);
        }
    }

    @Test
    void withoutADepthLimitTheStackIsStillSafe() {
        // No limit: a value of any depth decodes, and what cannot be done with
        // it on this stack is refused rather than a crash.
        final Limits unlimited = Limits.DEFAULT.withMaxDepth(0);
        final int depth = 200_000;
        final Value deep = Value.unpack(nested(depth, 0x01), unlimited);
        assertEquals(Kind.LIST, deep.kind());
        assertThrows(ValueException.class, deep::pack);
        assertThrows(ValueException.class, () -> deep.toJson(JsonMode.CANONICAL, unlimited));
        assertThrows(ValueException.class, () -> Value.parseJson(repeat("[", depth) + repeat("]", depth), unlimited));
        // A deep value that does fit works without a limit.
        final Value fits = Value.unpack(nested(1500, 0x01), unlimited);
        assertEquals(fits, Value.unpack(fits.pack(), unlimited));
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
