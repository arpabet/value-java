/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The operations of lists and maps, with the results the Go implementation's
 * immutable lists and maps give for the same calls.
 */
class CollectionsTest {

    private static Value n(long v) {
        return Value.ofLong(v);
    }

    @Test
    void denseList() {
        final ListValue l = Value.list(n(1), n(2), n(3));
        assertFalse(l.isSparse());
        assertEquals(3, l.size());
        assertEquals(3, l.count());

        assertEquals("96010203c0c009", l.putAt(5, n(9)).hex());
        assertEquals("9401020309", l.putAt(3, n(9)).hex());
        assertEquals("93010903", l.putAt(1, n(9)).hex());
        assertSame(l, l.putAt(-1, n(9)));

        assertEquals("9409010203", l.insertAt(0, n(9)).hex());
        assertEquals("9401020903", l.insertAt(2, n(9)).hex());
        assertEquals("9401090203", l.insertAt(1, n(9)).hex());
        assertEquals("9401020309", l.insertAt(7, n(9)).hex());
        assertSame(l, l.insertAt(-1, n(9)));

        assertEquals("920203", l.removeAt(0).hex());
        assertEquals("920103", l.removeAt(1).hex());
        assertEquals("920102", l.removeAt(2).hex());
        assertSame(l, l.removeAt(3));
        assertEquals("920103", l.deleteAll(1).hex());

        assertEquals("950107080203", l.insertAll(1, Arrays.asList(n(7), n(8))).hex());
        assertEquals("9401020307", l.insertAll(9, Arrays.asList(n(7))).hex());
        assertEquals("9407010203", l.insertAll(0, Arrays.asList(n(7))).hex());
        assertSame(l, l.insertAll(1, new ArrayList<Value>()));
        assertEquals(Arrays.asList(n(2)), l.select(1));
        assertTrue(l.select(9).isEmpty());

        assertEquals("93010203", l.hex()); // the list itself never changes
        assertEquals("9201c0", Value.list(n(1), null).hex());
        assertEquals("9101", Value.emptyList().append(n(1)).hex());
        assertEquals("91c0", Value.emptyList().append(null).hex());
        assertSame(NullValue.NULL, l.get(3));
        assertSame(NullValue.NULL, l.get(-1));
        assertEquals(n(2), l.get(1));
        assertEquals(Arrays.asList(n(1), n(2), n(3)), l.values());
        assertEquals(1, l.items().get(1).key());
        assertEquals(n(2), l.items().get(1).value());
        assertThrows(UnsupportedOperationException.class, () -> l.values().add(n(4)));
        int sum = 0;
        for (Value v : l) {
            sum += ((NumberValue) v).longValue();
        }
        assertEquals(6, sum);
        assertEquals("[1,2,3]", l.toString());
        assertEquals(Value.listOf(Arrays.asList(n(1), n(2), n(3))), l);
    }

    @Test
    void sparseList() {
        final ListValue sl = Value.emptySparseList().putAt(5, n(50)).putAt(0, n(10)).putAt(5, n(51)).insertAt(5, n(52)).append(n(60));
        assertTrue(sl.isSparse());
        assertEquals("84000a05340533063c", sl.hex());
        assertEquals(7, sl.size());
        assertEquals(4, sl.count());

        assertEquals("83000a0533063c", sl.removeAt(5).hex());
        assertEquals("82000a063c", sl.deleteAll(5).hex());
        assertEquals(Arrays.asList(n(52), n(51)), sl.select(5));
        assertTrue(sl.select(3).isEmpty());
        assertEquals("86000a0301030205340533063c", sl.insertAll(3, Arrays.asList(n(1), n(2))).hex());
        assertEquals("85fe01000a05340533063c", sl.putAt(-2, n(1)).hex());
        assertSame(sl, sl.removeAt(4));
        assertSame(sl, sl.deleteAll(4));
        assertEquals("84000a05340533063c", sl.hex());

        assertEquals("810001", Value.emptySparseList().append(n(1)).hex());
        assertEquals("8100c0", Value.emptySparseList().append(null).hex());
        assertEquals("80", Value.emptySparseList().hex());
        assertEquals(0, Value.emptySparseList().size());

        assertEquals(n(52), sl.get(5)); // the first of the two
        assertSame(NullValue.NULL, sl.get(3));
        assertEquals(Arrays.asList(n(10), NullValue.NULL, NullValue.NULL, NullValue.NULL, NullValue.NULL, n(52), n(60)), sl.values());
        assertEquals(5, sl.items().get(2).key());
        assertEquals(n(51), sl.items().get(2).value());
        assertEquals("{\"0\": 10,\"5\": 52,\"5\": 51,\"6\": 60}", sl.toString());
        assertEquals("{\"$sparse\":{\"0\":{\"$long\":\"10\"},\"2\":null}}",
                Value.emptySparseList().putAt(2, null).putAt(0, n(10)).toJson(JsonMode.CANONICAL));

        // A sparse list reads back as one; unsorted indexes are put in order.
        assertEquals(Value.emptySparseList().putAt(0, n(11)).putAt(5, n(10)), Value.unpack(Hex.decode("82050a000b")));
        assertEquals("82000b050a", Value.unpack(Hex.decode("82050a000b")).hex());
    }

    @Test
    void map() {
        final MapValue im = Value.emptyMap().put("b", n(2)).put("a", n(1)).insert("b", n(3)).insert("c", n(4)).put("b", n(5));
        assertEquals("84a16101a16205a16202a16304", im.hex());
        assertEquals("{\"a\": 1,\"b\": 5,\"b\": 2,\"c\": 4}", im.jsonify());
        assertEquals(4, im.size());
        assertEquals(Arrays.asList(n(5), n(2)), im.select("b"));
        assertEquals("83a16101a16202a16304", im.remove("b").hex());
        assertEquals("82a16101a16304", im.deleteAll("b").hex());
        assertEquals("86a16101a16207a16208a16205a16202a16304", im.insertAll("b", Arrays.asList(n(7), n(8))).hex());
        assertEquals(n(5), im.get("b"));
        final Map<String, Value> java = im.toMap();
        assertEquals(3, java.size());
        assertEquals(n(2), java.get("b")); // the last entry of a repeated key
        assertEquals(Arrays.asList("a", "b", "c"), new ArrayList<>(java.keySet()));
        assertSame(im, im.remove("zz"));
        assertSame(im, im.deleteAll("zz"));
        assertEquals("84a16101a16205a16202a16304", im.hex());

        assertEquals(Arrays.asList("a", "b", "b", "c"), im.keys());
        assertEquals(Arrays.asList(n(1), n(5), n(2), n(4)), im.values());
        assertEquals("b", im.entries().get(1).key());
        assertEquals(n(5), im.entries().get(1).value());
        int count = 0;
        for (MapValue.Entry e : im) {
            count += e.key().length();
        }
        assertEquals(4, count);

        // The builder keeps the last value put under a key.
        final MapValue built = MapValue.builder().put("k", 1).put("k", 2).put("j", true).put("s", "x").put("d", 1.5)
                .put("bin", new byte[] {1}).putNull("z").put("v", (Value) null).putAll(Value.emptyMap().put("j", n(7))).build();
        assertEquals("{\"bin\": \"base64,AQ\",\"d\": 1.5,\"j\": 7,\"k\": 2,\"s\": \"x\",\"v\": null,\"z\": null}", built.toString());
        assertTrue(Value.emptyMap().isEmpty());
        assertSame(Value.emptyMap(), MapValue.builder().build());
        assertThrows(NullPointerException.class, () -> Value.emptyMap().put(null, n(1)));
    }

    @Test
    void typedGettersCoerceAsGoDoes() {
        final MapValue m = MapValue.builder()
                .put("s", "123").put("b", true).put("n", 5).put("d", 1.5).putNull("nil")
                .put("l", Value.list(n(1), n(2)))
                .put("m", MapValue.builder().put("x", 1).put("a", 2).build())
                .put("raw", new byte[] {'t', 'r', 'u', 'e'}).put("hex", "0x7b").put("ts", Value.timestamp(5, 0))
                .put("big", Value.list(n(0), n(1), n(2), n(3), n(4), n(5), n(6), n(7), n(8), n(9), n(10), n(11)))
                .build();

        assertSame(BoolValue.FALSE, m.getBool("s"));
        assertEquals(n(123), m.getNumber("s"));
        assertEquals("a3313233", m.getString("s").hex());
        assertEquals("90", m.getList("s").hex());
        assertEquals("80", m.getMap("s").hex());

        assertSame(BoolValue.TRUE, m.getBool("b"));
        assertTrue(m.getNumber("b").isNaN());
        assertEquals(Value.utf8("true"), m.getString("b"));
        assertEquals(n(5), m.getNumber("n"));
        assertEquals(Value.utf8("5"), m.getString("n"));
        assertEquals(Value.utf8("1.5"), m.getString("d"));

        for (String absent : new String[] {"nil", "missing"}) {
            assertSame(BoolValue.FALSE, m.getBool(absent));
            assertEquals(n(0), m.getNumber(absent));
            assertEquals("a0", m.getString(absent).hex());
            assertEquals("90", m.getList(absent).hex());
            assertEquals("80", m.getMap(absent).hex());
        }

        assertTrue(m.getNumber("l").isNaN());
        assertEquals("a55b312c325d", m.getString("l").hex());
        assertEquals("920102", m.getList("l").hex());
        assertEquals("82a13001a13102", m.getMap("l").hex());

        assertEquals("af7b2261223a20322c2278223a20317d", m.getString("m").hex());
        assertEquals("920201", m.getList("m").hex());
        assertEquals("82a16102a17801", m.getMap("m").hex());

        assertSame(BoolValue.FALSE, m.getBool("raw"));
        assertTrue(m.getNumber("raw").isNaN());
        assertEquals("c40474727565", m.getString("raw").hex());
        assertEquals(Value.ofBigInt(BigInteger.valueOf(123)), m.getNumber("hex"));
        assertEquals("b4313937302d30312d30315430303a30303a30355a", m.getString("ts").hex());
        assertTrue(m.getNumber("ts").isNaN());
        // A list as a map is keyed by the text of its indexes, in the order of text.
        assertEquals("8ca13000a13101a231300aa231310ba13202a13303a13404a13505a13606a13707a13808a13909", m.getMap("big").hex());

        final ListValue l = Value.list(Value.utf8("7"), NullValue.NULL, BoolValue.TRUE);
        assertEquals(n(7), l.getNumber(0));
        assertEquals(n(0), l.getNumber(1));
        assertEquals(n(0), l.getNumber(5));
        assertSame(BoolValue.TRUE, l.getBool(2));
        assertEquals(Value.utf8("true"), l.getString(2));
        assertEquals("90", l.getList(0).hex());
        assertEquals("80", l.getMap(0).hex());
        final List<Value> values = l.values();
        assertEquals(3, values.size());
    }
}
