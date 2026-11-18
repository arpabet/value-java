/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValueTest {

    // The examples of the Go README, which its example_test.go checks.
    @Test
    void readmeExamples() {
        assertEquals("c0", NullValue.NULL.hex());
        assertEquals("c3", Value.ofBool(true).hex());
        assertEquals("c2", Value.ofBool(false).hex());
        assertEquals("7b", Value.ofLong(123).hex());
        assertEquals("cb3ff8000000000000", Value.ofDouble(1.5).hex());
        assertEquals("d4017b", Value.ofBigInt(BigInteger.valueOf(123)).hex());

        final NumberValue d = Value.ofDecimal(new BigDecimal("123.45"));
        assertEquals(123.45, d.doubleValue());
        assertEquals("c70602000000023039", d.hex());

        final TimestampValue t = Value.parseTimestamp("2026-10-30T07:28:39.075Z");
        assertEquals("2026-10-30T07:28:39.075Z", t.toString());
        assertEquals("d7ff11e1a3006ae44727", t.hex());
        assertEquals(t, Value.timestamp(1793345319L, 75000000));
        assertEquals(t, Value.ofEpochMilli(1793345319075L));
        assertEquals(t, Value.ofDate(new Date(1793345319075L)));
        assertEquals(1793345319075L, t.toEpochMilli());

        assertEquals("a568656c6c6f", Value.utf8("hello").hex());
        assertEquals("c403010203", Value.raw(new byte[] {1, 2, 3}).hex());

        final ListValue l = Value.emptyList().append(Value.ofLong(1)).append(Value.utf8("x"));
        assertEquals("9201a178", l.hex());
        assertEquals("[1,\"x\"]", l.jsonify());

        final ListValue sparse = Value.emptySparseList().putAt(0, Value.ofLong(10)).putAt(5, Value.ofLong(50));
        assertEquals("82000a0532", sparse.hex());

        final MapValue m = Value.emptyMap().put("name", Value.utf8("ann")).put("age", Value.ofLong(30));
        assertEquals("{\"age\": 30,\"name\": \"ann\"}", m.jsonify());
        assertEquals(m.jsonify(), m.toString());
    }

    @Test
    void bytesDependOnContentsNotOnConstruction() {
        final MapValue a = Value.emptyMap().put("zebra", Value.ofLong(1)).put("apple", Value.ofLong(2));
        final Map<String, Value> java = new HashMap<>();
        java.put("apple", Value.ofLong(2));
        java.put("zebra", Value.ofLong(1));
        final MapValue b = Value.mapOf(java);
        final MapValue c = MapValue.builder().put("zebra", 1).put("apple", 2).build();
        assertEquals("82a56170706c6502a57a6562726101", a.hex());
        assertEquals(a.hex(), b.hex());
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals(a.hashCode(), c.hashCode());
    }

    @Test
    void packAndUnpack() {
        final Value v = Value.list(Value.ofBool(true), Value.ofLong(123), Value.utf8("text"));
        final Value back = Value.unpack(v.pack());
        assertEquals(v, back);
        assertEquals(Kind.LIST, back.kind());
    }

    @Test
    void hashing() {
        // sha256(0x7b), the canonical encoding of the long 123
        assertEquals("021fb596db81e6d02bf3d2586ee3981fe519f275c0ac9ca76bbcf2ebb4097d96",
                Hex.encode(Value.ofLong(123).hash("SHA-256")));
        assertThrows(ValueException.class, () -> Value.ofLong(1).hash("no such digest"));
    }

    @Test
    void losslessJson() {
        final MapValue m = MapValue.builder().put("qty", 5).put("raw", new byte[] {1, 2}).build();
        assertEquals("{\"qty\":{\"$long\":\"5\"},\"raw\":{\"$bin\":\"AQI=\"}}", m.toJson(JsonMode.CANONICAL));
        assertEquals("{\"qty\":5,\"raw\":{\"$bin\":\"AQI=\"}}", m.toJson(JsonMode.RELAXED));
        assertEquals(m, Value.parseJson(m.toJson(JsonMode.CANONICAL)));

        // A bare number has no type: price is the decimal 1.10, both digits kept.
        final NumberValue price = ((MapValue) Value.parseJson("{\"price\": 1.10}")).getNumber("price");
        assertEquals(NumberType.DECIMAL, price.type());
        assertEquals(Value.ofDecimal(new BigDecimal("1.10")), price);
        assertNotEquals(Value.ofDecimal(new BigDecimal("1.1")), price);
        assertEquals(price, Value.parseJson("1.10".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void equalityFollowsTheBytes() {
        final NumberValue[] one = {
            Value.ofLong(1), Value.ofDouble(1), Value.ofBigInt(BigInteger.ONE), Value.ofDecimal(BigDecimal.ONE)
        };
        for (int i = 0; i < one.length; i++) {
            for (int j = 0; j < one.length; j++) {
                assertEquals(i == j, one[i].equals(one[j]), one[i] + " and " + one[j]);
                assertEquals(i == j, one[i].hex().equals(one[j].hex()));
                assertTrue(one[i].approxEquals(one[j]));
            }
        }
        assertEquals(Value.ofLong(300), Value.ofLong(300));
        assertEquals(Value.ofLong(300).hashCode(), Value.ofLong(300).hashCode());
        assertNotEquals(Value.ofDouble(1.0000005), Value.ofDouble(1.0000006));
        assertTrue(Value.ofDouble(1.0000005).approxEquals(Value.ofDouble(1.0000006)));
        assertNotEquals(Value.ofDouble(0.0), Value.ofDouble(-0.0));
        assertNotEquals(Value.ofDecimal(new BigDecimal("1.0")), Value.ofDecimal(new BigDecimal("1.00")));
        assertEquals(Value.ofDecimal(new BigDecimal("1.0")), Value.ofDecimal(new BigDecimal("1.0")));
        assertNotEquals(Value.utf8("abc"), Value.raw("abc".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(Value.raw("abc".getBytes(StandardCharsets.UTF_8)), Value.utf8("abc"));
        assertNotEquals(Value.list(Value.ofLong(1)), Value.emptySparseList().putAt(0, Value.ofLong(1)));
        assertNotEquals(NullValue.NULL, null);
        assertSame(NullValue.NULL, Value.ofNull());
        assertTrue(NullValue.NULL.isNull());
        assertFalse(Value.ofLong(0).isNull());
    }

    @Test
    void notANumber() {
        // Every NaN of a Java double is Go's math.NaN(); it equals itself.
        final NumberValue nan = Value.ofDouble(Double.NaN);
        assertEquals("cb7ff8000000000001", nan.hex());
        assertEquals("cb7ff8000000000001", Value.ofDouble(0.0 / 0.0).hex());
        assertEquals("cb7ff8000000000001", Value.ofDouble(Double.longBitsToDouble(0xFFF8000000000123L)).hex());
        assertEquals(nan, NumberValue.nan());
        assertEquals(nan, nan);
        assertTrue(nan.isNaN());
        assertFalse(nan.approxEquals(nan));
        assertEquals("NaN", nan.toString());
        assertEquals("null", nan.jsonify());
        assertEquals(0, nan.longValue());
        // A float's NaN is the one a Go float32 widens to.
        assertEquals("cb7ff8000000000000", Value.ofFloat(Float.NaN).hex());
        assertEquals(Value.ofDouble(0.5), Value.ofFloat(0.5f));
        // A NaN read from bytes keeps its bits.
        assertEquals("cbfff8000000000123", Value.unpack(Hex.decode("cbfff8000000000123")).hex());
        assertNotEquals(nan, Value.unpack(Hex.decode("cb7ff8000000000000")));
    }

    @Test
    void unsignedNumbers() {
        assertEquals(Value.ofLong(5), Value.ofUnsigned(5));
        assertEquals(Value.ofLong(Long.MAX_VALUE), Value.ofUnsigned(Long.MAX_VALUE));
        assertEquals("c70901008000000000000000", Value.ofUnsigned(Long.MIN_VALUE).hex());
        assertEquals("c7090100ffffffffffffffff", Value.ofUnsigned(-1).hex());
        assertEquals(NumberType.BIGINT, Value.ofUnsigned(-1).type());
        // MessagePack's uint 64 above the range of a long reads as that number.
        final NumberValue n = (NumberValue) Value.unpack(Hex.decode("cfffffffffffffffff"));
        assertEquals(new BigInteger("18446744073709551615"), n.bigIntegerValue());
        assertEquals("c7090100ffffffffffffffff", n.hex());
        assertEquals(NumberType.LONG, ((NumberValue) Value.unpack(Hex.decode("cf7fffffffffffffff"))).type());
    }

    @Test
    void extensions() {
        assertEquals(Value.ofBigInt(BigInteger.valueOf(123)), Value.ext(1, new byte[] {0x7b}));
        assertEquals(Value.ofBigInt(BigInteger.valueOf(123)), Value.ext(1, new byte[] {0, 0, 0x7b}));
        assertEquals(Value.ofDecimal(new BigDecimal("1.10")), Value.ext(2, new byte[] {0, 0, 0, 2, 0x6e}));
        assertEquals(Value.timestamp(1793345319L, 0), Value.ext(-1, Hex.decode("6ae44727")));
        assertEquals(Value.timestamp(1793345319L, 0), Value.ext(0xff, Hex.decode("6ae44727")));
        assertThrows(ValueException.class, () -> Value.ext(1, new byte[0]));
        assertThrows(ValueException.class, () -> Value.ext(2, new byte[4]));
        assertThrows(ValueException.class, () -> Value.ext(-1, new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> Value.ext(256, new byte[0]));

        final ExtensionValue x = (ExtensionValue) Value.ext(7, new byte[] {1, 2});
        assertEquals(Kind.UNKNOWN, x.kind());
        assertEquals(7, x.type());
        assertArrayEquals(new byte[] {1, 2}, x.data());
        assertEquals("d5070102", x.hex());
        assertEquals("data:application/x-msgpack-ext;base64,BwEC", x.toString());
        assertEquals(x, Value.unpack(Hex.decode("d5070102")));
        assertEquals(-7, ((ExtensionValue) Value.ext(0xf9, new byte[] {1})).type());
        assertEquals("{\"$ext\":{\"base64\":\"AQ==\",\"type\":-7}}", Value.ext(0xf9, new byte[] {1}).toJson(JsonMode.CANONICAL));
    }

    @Test
    void timestampsAreInstants() {
        assertEquals(Value.timestamp(12, 500000000), Value.timestamp(10, 2500000000L));
        assertEquals(Value.timestamp(9, 999999999), Value.timestamp(10, -1));
        assertNotEquals(Value.timestamp(1793345319L, 75000000), Value.timestamp(1793345319L, 75000001));
        assertNotEquals(Value.timestamp(5, 0), Value.ofLong(5));
        assertEquals("2026-10-30T07:28:39Z", Value.timestamp(1793345319L, 0).toString());
        assertEquals("\"2026-10-30T07:28:39Z\"", Value.timestamp(1793345319L, 0).jsonify());
        assertEquals("2026-10-30T07:28:39.075Z", Value.parseTimestamp("2026-10-30T09:28:39.075+02:00").toString());
        assertEquals("c70cff1dcd6500ffffffffffffffff", Value.parseTimestamp("1969-12-31T23:59:59.5Z").hex());
        assertThrows(ValueException.class, () -> Value.parseTimestamp("yesterday"));
        assertEquals(new Date(-500), Value.timestamp(-1, 500000000).toDate());
        assertEquals(Value.timestamp(1793345319L, 123456789), JavaTime.timestamp(java.time.Instant.ofEpochSecond(1793345319L, 123456789)));
        assertEquals(java.time.Instant.ofEpochSecond(-1, 500000000), JavaTime.toInstant(Value.timestamp(-1, 500000000)));
    }

    @Test
    void largeListsUseTheWideHeader() {
        final Value[] items = new Value[70000];
        for (int i = 0; i < items.length; i++) {
            items[i] = Value.ofLong(i & 0x3f);
        }
        final ListValue list = Value.list(items);
        final byte[] packed = list.pack();
        assertEquals((byte) 0xdd, packed[0]);
        assertEquals(list, Value.unpack(packed));
        assertEquals((byte) 0xdc, Value.list(Arrays.copyOf(items, 65535)).pack()[0]);
    }

    @Test
    void textThatIsNotUtf8IsKept() {
        // A str of the bytes c3 28 is not UTF-8; it packs back to the same bytes.
        final StringValue s = (StringValue) Value.unpack(Hex.decode("a2c328"));
        assertEquals(StringType.UTF8, s.type());
        assertEquals("a2c328", s.hex());
        assertEquals("\uDCC3(", s.utf8());
        assertArrayEquals(new byte[] {(byte) 0xc3, 0x28}, s.raw());
        assertEquals(2, s.size());
        assertEquals(s, Value.utf8("\uDCC3("));
        assertEquals("\"\\xc3(\"", s.jsonify());
        assertThrows(ValueException.class, () -> s.toJson(JsonMode.CANONICAL));
        assertEquals("\uDCC3(", ((StringValue) Value.raw(new byte[] {(byte) 0xc3, 0x28})).utf8());

        // A surrogate with no byte form is U+FFFD, and escapes that spell
        // valid UTF-8 are the text they spell.
        assertEquals(Value.utf8("\uFFFD"), Value.utf8("\uD800"));
        assertEquals("a3efbfbd", Value.utf8("\uD83D").hex());
        assertEquals(Value.utf8("é"), Value.utf8("\uDCC3\uDCA9"));
        assertEquals("😀", ((StringValue) Value.unpack(Value.utf8("😀").pack())).utf8());
        assertEquals("a4f09f9880", Value.utf8("😀").hex());
        assertEquals(4, Value.utf8("😀").size());
    }

    @Test
    void mapKeysSortByTheirBytes() {
        // U+E000 is three bytes (ee 80 80) and sorts before U+1F600 (f0 9f 98
        // 80), though as UTF-16 the surrogate d83d sorts before e000.
        final MapValue m = MapValue.builder().put("😀", 1).put("\uE000", 2).put("B", 3).put("a", 4).put("aa", 5).build();
        assertEquals(Arrays.asList("B", "a", "aa", "\uE000", "😀"), m.keys());
        assertEquals("85a14203a16104a2616105a3ee808002a4f09f988001", m.hex());
        assertEquals(m, Value.unpack(m.pack()));
        assertEquals(Value.ofLong(1), m.get("😀"));
        assertTrue(m.containsKey("\uE000"));
        assertFalse(m.containsKey("b"));
        assertSame(NullValue.NULL, m.get("b"));
    }

    @Test
    void unpackerReadsValuesInARow() {
        final byte[] data = Hex.decode("01a161c09201027b");
        final Unpacker in = new Unpacker(data);
        assertEquals(Value.ofLong(1), in.next());
        assertEquals(1, in.position());
        assertEquals(Value.utf8("a"), in.next());
        assertEquals(NullValue.NULL, in.next());
        assertEquals(Value.list(Value.ofLong(1), Value.ofLong(2)), in.next());
        assertEquals(7, in.position());
        assertTrue(in.hasNext());
        assertEquals(Value.ofLong(123), in.next());
        assertFalse(in.hasNext());
        assertNull(in.next());

        // unpack reads the first value and does not look at the rest.
        assertEquals(Value.ofLong(1), Value.unpack(data));
        assertEquals(Value.utf8("a"), Value.unpack(data, 1, 2));
        assertThrows(ValueException.class, () -> Value.unpack(data, 1, 1));
        assertThrows(ValueException.class, () -> Value.unpack(new byte[0]));
        assertThrows(IndexOutOfBoundsException.class, () -> new Unpacker(data, 4, 9));
    }

    @Test
    void streams() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Value[] values = {
            Value.utf8("hello world"), Value.ofLong(2147483647), MapValue.builder().put("k", 1.5).build(), NullValue.NULL
        };
        for (Value v : values) {
            v.writeTo(out);
        }
        // A stream that gives one byte at a time.
        final ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray()) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        for (Value v : values) {
            assertEquals(v, Value.read(in));
        }
        assertNull(Value.read(in));

        final byte[] truncated = Arrays.copyOf(out.toByteArray(), 5);
        assertThrows(ValueException.class, () -> Value.read(new ByteArrayInputStream(truncated)));
    }

    @Test
    void numberAccessors() {
        final NumberValue d = Value.ofDecimal(new BigDecimal("123.99"));
        assertEquals(123, d.longValue());
        assertEquals(BigInteger.valueOf(123), d.bigIntegerValue());
        assertEquals(-1, Value.ofDecimal(new BigDecimal("-1.5")).longValue());
        assertEquals(1000, Value.ofDecimal(new BigDecimal("1e3")).longValue());
        assertEquals("0x01e2afx-03", Value.ofDecimal(new BigDecimal("123.567")).toString());
        assertEquals("-0x7b", Value.ofBigInt(BigInteger.valueOf(-123)).toString());
        assertEquals("\"0x0\"", Value.ofBigInt(BigInteger.ZERO).jsonify());

        assertEquals(Value.ofLong(5), Value.ofLong(3).add(Value.ofLong(2)));
        assertEquals(Value.ofLong(1), Value.ofLong(3).subtract(Value.ofDouble(2.9)));
        assertEquals(Value.ofDecimal(new BigDecimal("3.50")), Value.ofDecimal(new BigDecimal("1.25")).add(Value.ofDecimal(new BigDecimal("2.25"))));
        assertTrue(Value.ofDouble(3.3).add(NumberValue.nan()).isNaN());
        assertEquals(Value.ofBigInt(BigInteger.valueOf(7)), Value.ofBigInt(BigInteger.valueOf(5)).add(Value.ofLong(2)));

        assertEquals(5, Value.ofDecimal(new BigDecimal("5.00")).intValueExact());
        assertThrows(ArithmeticException.class, () -> Value.ofDecimal(new BigDecimal("5.5")).longValueExact());
        assertThrows(ArithmeticException.class, () -> Value.ofBigInt(BigInteger.ONE.shiftLeft(63)).longValueExact());
        assertEquals(1.1, Value.ofDecimal(new BigDecimal("1.10")).doubleValueExact());
        assertThrows(ArithmeticException.class, () -> Value.ofLong((1L << 53) + 1).doubleValueExact());
        assertEquals(0.1f, Value.ofDecimal(new BigDecimal("0.1")).floatValueExact());
        assertThrows(ArithmeticException.class, () -> Value.ofDouble(0.1).floatValueExact());
        assertThrows(ArithmeticException.class, () -> Value.ofDouble(Double.POSITIVE_INFINITY).bigDecimalValue());

        assertEquals(Value.ofLong(123), NumberValue.parse("123"));
        assertEquals(Value.ofBigInt(BigInteger.valueOf(123)), NumberValue.parse("0x7b"));
        assertEquals(Value.ofDecimal(new BigDecimal("123.567")), NumberValue.parse("0x01e2afx-03"));
        assertEquals(Value.ofDouble(123.45), NumberValue.parse("123.45"));
        assertEquals(Value.ofBigInt(new BigInteger("18446744073709551615")), NumberValue.parse("18446744073709551615"));
        assertTrue(NumberValue.parse("not a number").isNaN());
        assertEquals(Value.raw(new byte[] {1, 2, 3}), StringValue.parse("base64,AQID"));
        assertEquals(Value.utf8("base64,!!"), StringValue.parse("base64,!!"));
        assertSame(BoolValue.TRUE, BoolValue.parse("True"));
        assertSame(BoolValue.FALSE, BoolValue.parse("yes"));
    }

    @Test
    void binaryIsNotShared() {
        final byte[] b = {1, 2, 3};
        final StringValue raw = Value.raw(b);
        b[0] = 9;
        assertEquals("c403010203", raw.hex());
        raw.raw()[0] = 9;
        assertEquals("c403010203", raw.hex());
        assertTrue(raw.asByteBuffer().isReadOnly());
        assertEquals(3, raw.asByteBuffer().remaining());
        assertEquals("c4020203", Value.raw(new byte[] {1, 2, 3}, 1, 2).hex());
        assertEquals("base64,AQID", raw.toString());
        assertEquals("\"base64,AQID\"", raw.jsonify());
    }
}
