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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodecTest {

    static final ValueCodec CODEC = ValueCodec.DEFAULT;

    static final class Rec {
        @Name("id") String id;
        @Name("note") @OmitEmpty String note;
        @Name("data") @OmitEmpty byte[] data;
    }

    @Test
    void omitEmptyAndRequiredFields() {
        final Rec rec = new Rec();
        rec.id = "x";
        assertEquals("{\"id\": \"x\"}", CODEC.marshal(rec).toString());
        rec.note = "hi";
        rec.data = new byte[] {1};
        assertEquals("{\"data\": \"base64,AQ\",\"id\": \"x\",\"note\": \"hi\"}", CODEC.marshal(rec).toString());

        // A field without OmitEmpty is required when reading.
        final MapValue missing = MapValue.builder().put("note", "hi").put("gone", 1).build();
        final ValueException e = assertThrows(ValueException.class, () -> CODEC.unmarshal(missing, Rec.class));
        assertTrue(e.getMessage().contains("missing required field \"id\""), e.getMessage());

        final Rec partial = CODEC.allowMissing().unmarshal(missing, Rec.class);
        assertNull(partial.id);
        assertEquals("hi", partial.note);

        // An optional field may be absent; a key the class does not have is ignored.
        final Rec only = CODEC.unmarshal(MapValue.builder().put("id", "y").put("gone", true).build(), Rec.class);
        assertEquals("y", only.id);
        assertNull(only.note);

        // The null value leaves a field as it is, and satisfies a required one.
        final Rec nulls = CODEC.unmarshal(MapValue.builder().putNull("id").putNull("note").build(), Rec.class);
        assertNull(nulls.id);
        assertNull(CODEC.unmarshal(NullValue.NULL, Rec.class));
        assertThrows(ValueException.class, () -> CODEC.unmarshal(Value.ofLong(1), Rec.class));
    }

    static final class Inner {
        @Name("n") int n;
    }

    static final class Nulls {
        String s;
        byte[] b;
        List<String> l;
        Map<String, Long> m;
        int[] a;
        Integer boxed;
        Inner obj;
        BigInteger big;
        Date date;
        Value v;
        Object any;
    }

    @Test
    void nullIsTheEmptyValueWhereGoHasNoNull() {
        // Text, binary, lists and maps have no null in Go; everything else does.
        assertEquals("{\"a\": [],\"any\": null,\"b\": \"base64,\",\"big\": null,\"boxed\": null,\"date\": null,"
                + "\"l\": [],\"m\": {},\"obj\": null,\"s\": \"\",\"v\": null}", CODEC.marshal(new Nulls()).toString());
        assertEquals("92a0a0", CODEC.marshal(new String[] {null, ""}).hex());
        assertEquals("92c400c400", CODEC.marshal(new byte[][] {null, {}}).hex());
        assertEquals("91c0", CODEC.marshal(new Integer[] {null}).hex());
        assertEquals("c0", CODEC.marshal(null).hex());
    }

    static final class Msg {
        @Name("_dom") @Sign("sign") String domain = "dr_ack/v1";
        @Name("acc") @Sign("sign") String account;
        @Name("dev") @Sign("sign") String device;
        @Name("dh") @Sign("sign") byte[] dhPub;
        @Name("opt") @OmitEmpty @Sign("sign") byte[] optional;
        @Name("sig") byte[] signature;
        @Name("nonce") @Unsigned long nonce;
    }

    @Test
    void signing() throws Exception {
        final Msg a = new Msg();
        a.account = "vox-a";
        a.device = "dev-1";
        a.dhPub = new byte[] {1, 2, 3};
        a.signature = new byte[] {(byte) 0xde, (byte) 0xad};
        a.nonce = 1;
        final Msg b = new Msg();
        b.account = "vox-a";
        b.device = "dev-1";
        b.dhPub = new byte[] {1, 2, 3};
        b.signature = new byte[] {(byte) 0xbe, (byte) 0xef};
        b.nonce = -1; // 2^64 - 1 as an unsigned number

        // Fields outside the selector do not change what is signed.
        final byte[] toSign = CODEC.signBytes(a, "sign");
        assertArrayEquals(toSign, CODEC.signBytes(b, "sign"));
        assertNotEquals(CODEC.marshal(a), CODEC.marshal(b));
        // A signed field does; and an empty OmitEmpty field is part of the shape.
        b.account = "vox-evil";
        assertFalse(Arrays.equals(toSign, CODEC.signBytes(b, "sign")));
        assertEquals("{\"_dom\": \"dr_ack/v1\",\"acc\": \"vox-a\",\"dev\": \"dev-1\",\"dh\": \"base64,AQID\",\"opt\": \"base64,\"}",
                CODEC.signProjection(a, "sign").toString());
        assertEquals(32, CODEC.signHash(a, "SHA-256", "sign").length);
        assertEquals(0, CODEC.signProjection(a, "other").size());

        assertThrows(ValueException.class, () -> CODEC.signBytes(a, ""));
        assertThrows(ValueException.class, () -> CODEC.signBytes(null, "sign"));
        assertThrows(ValueException.class, () -> CODEC.signBytes("text", "sign"));
        assertThrows(ValueException.class, () -> CODEC.signBytes(new HashMap<String, String>(), "sign"));

        // End to end: the verifier rebuilds the object from the wire and
        // derives the same projection.
        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(toSign);
        a.signature = signer.sign();

        final Msg received = CODEC.unpack(CODEC.pack(a), Msg.class);
        final Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keys.getPublic());
        verifier.update(CODEC.signBytes(received, "sign"));
        assertTrue(verifier.verify(received.signature));
        assertEquals(1, received.nonce);
    }

    record Point(@Name("x") int x, @Name("y") int y, @Name("label") @OmitEmpty String label) {
    }

    static final class Pair {
        @Name("left") final String left;
        @Name("right") final long right;
        transient int cached = 7;

        Pair(String left, long right, int cached) {
            this.left = left;
            this.right = right;
            this.cached = cached;
        }
    }

    @Test
    void recordsAndClassesWithoutADefaultConstructor() {
        final Point p = new Point(3, -4, "p");
        assertEquals("{\"label\": \"p\",\"x\": 3,\"y\": -4}", CODEC.marshal(p).toString());
        assertEquals(p, CODEC.unmarshal(CODEC.marshal(p), Point.class));
        assertEquals(new Point(1, 0, null), CODEC.allowMissing().unmarshal(MapValue.builder().put("x", 1).build(), Point.class));
        assertEquals(Arrays.asList(p, null), CODEC.unmarshalList(Value.list(CODEC.marshal(p), NullValue.NULL), Point.class));

        final Pair pair = CODEC.unmarshal(MapValue.builder().put("left", "l").put("right", 9).build(), Pair.class);
        assertEquals("l", pair.left);
        assertEquals(9, pair.right);
        assertEquals(0, pair.cached);
        assertEquals("{\"left\": \"l\",\"right\": 9}", CODEC.marshal(pair).toString());

        // A record cannot be filled in place.
        assertThrows(ValueException.class, () -> CODEC.unmarshalInto(CODEC.marshal(p), new Point(0, 0, null)));
    }

    enum Color { RED, GREEN }

    static final class Themed {
        @Name("color") Color color;
        @Name("id") UUID id;
    }

    @Test
    void converters() {
        final Themed t = new Themed();
        t.color = Color.GREEN;
        t.id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        // An enum or a UUID has no value of its own.
        assertThrows(ValueException.class, () -> CODEC.marshal(t));
        assertThrows(ValueException.class, () -> CODEC.unmarshal(MapValue.builder().put("color", "RED").putNull("id").build(), Themed.class));

        final ValueCodec.Converter<UUID> uuid = new ValueCodec.Converter<UUID>() {
            @Override
            public Value toValue(UUID u) {
                return Value.utf8(u.toString());
            }

            @Override
            public UUID fromValue(Value v) {
                return UUID.fromString(((StringValue) v).utf8());
            }
        };
        final ValueCodec names = CODEC.with(Color.class, ValueCodec.enumNames(Color.class)).with(UUID.class, uuid);
        assertEquals("{\"color\": \"GREEN\",\"id\": \"00112233-4455-6677-8899-aabbccddeeff\"}", names.marshal(t).toString());
        final Themed back = names.unmarshal(names.marshal(t), Themed.class);
        assertSame(Color.GREEN, back.color);
        assertEquals(t.id, back.id);
        assertThrows(ValueException.class, () -> names.unmarshal(MapValue.builder().put("color", "BLUE").putNull("id").build(), Themed.class));

        final ValueCodec ordinals = names.with(Color.class, ValueCodec.enumOrdinals(Color.class));
        assertEquals("{\"color\": 1,\"id\": \"00112233-4455-6677-8899-aabbccddeeff\"}", ordinals.marshal(t).toString());
        assertSame(Color.GREEN, ordinals.unmarshal(ordinals.marshal(t), Themed.class).color);
        assertThrows(ValueException.class, () -> ordinals.unmarshal(MapValue.builder().put("color", 2).putNull("id").build(), Themed.class));
        assertEquals(Value.utf8("RED"), names.marshal(Color.RED));
    }

    static final class WithChar {
        char c;
    }

    static final class WithSet {
        Set<String> s = new HashSet<>();
    }

    static final class Node {
        @Name("next") Node next;
    }

    static final class Twice {
        @Name("a") int first;
        @Name("a") int second;
    }

    @Test
    void whatHasNoValueIsRefused() {
        assertThrows(ValueException.class, () -> CODEC.marshal(new WithChar()));
        assertThrows(ValueException.class, () -> CODEC.marshal(new WithSet()));
        assertThrows(ValueException.class, () -> CODEC.marshal(Color.RED));
        assertThrows(ValueException.class, () -> CODEC.marshal(new Object()));
        assertThrows(ValueException.class, () -> CODEC.marshal(new Twice()));
        assertThrows(ValueException.class, () -> CODEC.unmarshal(Value.emptyMap(), Runnable.class));

        final Map<Object, String> keyed = new HashMap<>();
        keyed.put(1, "x");
        assertThrows(ValueException.class, () -> CODEC.marshal(keyed));

        // An object that refers back to itself has no tree.
        final Node node = new Node();
        node.next = node;
        assertThrows(ValueException.class, () -> CODEC.marshal(node));
    }

    static final class Times {
        @Name("date") Date date;
        @Name("instant") Instant instant;
        @Name("stamp") TimestampValue stamp;
    }

    @Test
    void times() {
        final Times t = new Times();
        t.date = new Date(1793345319075L);
        t.instant = Instant.ofEpochSecond(1793345319L, 123456789);
        t.stamp = Value.timestamp(-1, 500000000);
        final MapValue m = (MapValue) CODEC.marshal(t);
        assertEquals("{\"date\": \"2026-10-30T07:28:39.075Z\",\"instant\": \"2026-10-30T07:28:39.123456789Z\","
                + "\"stamp\": \"1969-12-31T23:59:59.5Z\"}", m.toString());
        final Times back = CODEC.unmarshal(m, Times.class);
        assertEquals(t.date, back.date);
        assertEquals(t.instant, back.instant);
        assertEquals(t.stamp, back.stamp);

        // A date keeps milliseconds: the lossless codec refuses to drop more.
        final MapValue nanos = m.put("date", Value.timestamp(1793345319L, 75000001));
        assertEquals(t.date, CODEC.unmarshal(nanos, Times.class).date);
        final ValueException e = assertThrows(ValueException.class, () -> CODEC.lossless().unmarshal(nanos, Times.class));
        assertTrue(e.getMessage().contains("field \"date\""), e.getMessage());

        // Unix milliseconds, as a time was written before timestamps.
        final MapValue millis = m.put("date", Value.ofLong(1793345319075L)).put("instant", Value.ofLong(5));
        assertEquals(t.date, CODEC.unmarshal(millis, Times.class).date);
        assertEquals(Instant.ofEpochMilli(5), CODEC.unmarshal(millis, Times.class).instant);
        assertThrows(ValueException.class, () -> CODEC.unmarshal(m.put("date", Value.utf8("2026")), Times.class));
    }

    static final class Holder {
        @Name("any") Object any;
        @Name("raw") Value raw;
        @Name("map") MapValue map;
    }

    @Test
    void valuesPassThrough() {
        final Holder h = new Holder();
        h.any = Arrays.asList(1, "two", 3.0, true, null);
        h.raw = Value.ofLong(123);
        h.map = MapValue.builder().put("k", 1).build();
        final MapValue m = (MapValue) CODEC.marshal(h);
        assertEquals("{\"any\": [1,\"two\",3,true,null],\"map\": {\"k\": 1},\"raw\": 123}", m.toString());
        assertEquals(NumberType.DOUBLE, m.getList("any").getNumber(2).type());

        final Holder back = CODEC.unmarshal(m, Holder.class);
        assertEquals(m.get("any"), back.any); // an Object field holds the tree
        assertEquals(Value.ofLong(123), back.raw);
        assertEquals(h.map, back.map);
        assertThrows(ValueException.class, () -> CODEC.unmarshal(m.put("map", Value.ofLong(1)), Holder.class));
        assertSame(NullValue.NULL, CODEC.unmarshal(NullValue.NULL, Value.class));
        assertEquals(Arrays.asList(Value.ofLong(1), NullValue.NULL), CODEC.unmarshalList(Value.list(Value.ofLong(1), null), Value.class));
    }

    static class Base {
        @Name("id") String id;
    }

    static final class Derived extends Base {
        @Name("extra") int extra;
    }

    @Test
    void fieldsOfSuperclassesAndFillingInPlace() {
        final Derived d = new Derived();
        d.id = "x";
        d.extra = 5;
        assertEquals("{\"extra\": 5,\"id\": \"x\"}", CODEC.marshal(d).toString());

        final Derived target = new Derived();
        target.extra = 9;
        CODEC.allowMissing().unmarshalInto(MapValue.builder().put("id", "y").build(), target);
        assertEquals("y", target.id);
        assertEquals(9, target.extra);
        CODEC.unmarshalInto(NullValue.NULL, target);
        assertEquals("y", target.id);
        assertThrows(ValueException.class, () -> CODEC.unmarshalInto(Value.ofLong(1), target));
    }

    @Test
    void plainJavaValues() {
        assertEquals(Value.utf8("x"), CODEC.marshal("x"));
        assertEquals(Value.ofLong(5), CODEC.marshal(5));
        assertEquals(Value.ofLong(-5), CODEC.marshal((byte) -5));
        assertEquals(Value.ofDouble(1.5), CODEC.marshal(1.5f));
        assertEquals(Value.ofBool(true), CODEC.marshal(true));
        assertEquals(Value.raw(new byte[] {1}), CODEC.marshal(new byte[] {1}));
        assertEquals(Value.ofBigInt(BigInteger.TEN), CODEC.marshal(BigInteger.TEN));
        assertEquals(Value.ofDecimal(new BigDecimal("1.10")), CODEC.marshal(new BigDecimal("1.10")));
        assertEquals("93010203", CODEC.marshal(new int[] {1, 2, 3}).hex());
        assertEquals("92cb3ff8000000000000c3", CODEC.marshal(new Object[] {1.5, true}).hex());
        final Map<String, Object> map = new HashMap<>();
        map.put("b", 2);
        map.put("a", Arrays.asList("x"));
        assertEquals("82a16191a178a16202", CODEC.marshal(map).hex());
        final Value same = Value.ofLong(1);
        assertSame(same, CODEC.marshal(same));

        assertEquals(5, CODEC.unmarshal(Value.ofLong(5), int.class));
        assertEquals(0, CODEC.unmarshal(NullValue.NULL, int.class));
        assertEquals(5L, CODEC.unmarshal(Value.ofDecimal(new BigDecimal("5.9")), Long.class));
        assertEquals("x", CODEC.unmarshal(Value.utf8("x"), String.class));
        assertEquals("\uDCFF", CODEC.unmarshal(Value.raw(new byte[] {(byte) 0xff}), String.class));
        assertArrayEquals(new byte[] {'x'}, CODEC.unmarshal(Value.utf8("x"), byte[].class));
        assertArrayEquals(new long[] {1, 0, 3}, CODEC.unmarshal(Value.list(Value.ofLong(1), null, Value.ofLong(3)), long[].class));
        assertEquals(Arrays.asList("a", null), CODEC.unmarshalList(Value.list(Value.utf8("a"), null), String.class));
        final Map<String, Integer> ints = CODEC.unmarshalMap(MapValue.builder().put("b", 2).put("a", 1).build(), Integer.class);
        assertEquals("{a=1, b=2}", ints.toString());
        assertThrows(ValueException.class, () -> CODEC.unmarshal(Value.utf8("5"), int.class));
        assertThrows(ValueException.class, () -> CODEC.unmarshal(Value.ofLong(1), boolean.class));
        assertThrows(ValueException.class, () -> CODEC.unmarshal(Value.ofLong(1), String.class));
        assertThrows(ValueException.class, () -> CODEC.unmarshal(Value.emptyMap(), int[].class));
    }

    static final class Narrow {
        @Name("i8") byte i8;
        @Name("f32") float f32;
        @Name("big") BigInteger big;
    }

    @Test
    void losslessNamesTheFieldThatDoesNotFit() {
        final MapValue m = MapValue.builder().put("i8", 300).put("f32", 0.5).put("big", Value.ofLong(1)).build();
        assertEquals(44, CODEC.unmarshal(m, Narrow.class).i8); // wraps, as Go's default codec does
        final ValueException e = assertThrows(ValueException.class, () -> CODEC.lossless().unmarshal(m, Narrow.class));
        assertTrue(e.getMessage().contains("field \"i8\"") && e.getMessage().contains("overflows int8"), e.getMessage());
        assertEquals(0.5f, CODEC.lossless().unmarshal(m.put("i8", Value.ofLong(-128)), Narrow.class).f32);
        // A fraction is never a big integer, in either codec.
        assertThrows(ValueException.class, () -> CODEC.unmarshal(m.put("big", Value.ofDouble(1.5)), Narrow.class));
        assertEquals(BigInteger.valueOf(1200), CODEC.unmarshal(m.put("big", Value.ofDecimal(new BigDecimal("12e2"))), Narrow.class).big);
    }

    // --- codecs that name fields by the annotations of another library -----------

    /** As a JSON library's annotation of a field's name is. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface JsonName {
        String value() default "";
    }

    /** As a MessagePack library's is. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface PackName {
        String name();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Ignore {
    }

    /** The codecRecord of the Go implementation's value_codec_test.go. */
    static final class Record {
        @Name("value_name") @JsonName("json_name") @PackName(name = "msgpack_name") @Sign("record") String name;
        @Name("value_count") @JsonName("json_count") @PackName(name = "msgpack_count") @OmitEmpty int count;
        transient String hidden;
    }

    static final ValueCodec JSON = ValueCodec.DEFAULT.named(JsonName.class, JsonName::value).allowMissing();
    static final ValueCodec MSGPACK = ValueCodec.DEFAULT.named(PackName.class, PackName::name).allowMissing();

    @Test
    void codecsNameFieldsByTheirOwnAnnotation() {
        final Object[][] codecs = {{CODEC, "value_name"}, {JSON, "json_name"}, {MSGPACK, "msgpack_name"}};
        for (Object[] c : codecs) {
            final ValueCodec codec = (ValueCodec) c[0];
            final Record record = new Record();
            record.name = "ann";
            record.hidden = "secret";
            final MapValue m = (MapValue) codec.marshal(record);
            assertEquals(1, m.size(), m.toString());
            assertEquals(Value.utf8("ann"), m.get((String) c[1]));

            final Record decoded = codec.unmarshal(m, Record.class);
            assertEquals("ann", decoded.name);
            assertEquals(0, decoded.count);
            assertNull(decoded.hidden);

            // What is signed goes by the names of the codec as well.
            final MapValue signed = (MapValue) Value.unpack(codec.signBytes(record, "record"));
            assertEquals(1, signed.size());
            assertTrue(signed.containsKey((String) c[1]), signed.toString());
        }

        final Record full = new Record();
        full.name = "ann";
        full.count = 7;
        assertEquals("{\"json_count\": 7,\"json_name\": \"ann\"}", JSON.marshal(full).toString());
        assertEquals("{\"msgpack_count\": 7,\"msgpack_name\": \"ann\"}", MSGPACK.marshal(full).toString());
        assertEquals("{\"value_count\": 7,\"value_name\": \"ann\"}", CODEC.marshal(full).toString());
        // The same codec again, and one derived from it, name the same way.
        assertEquals(JSON.pack(full).length, JSON.lossless().with(UUID.class, new ValueCodec.Converter<UUID>() {
            @Override
            public Value toValue(UUID u) {
                return Value.utf8(u.toString());
            }

            @Override
            public UUID fromValue(Value v) {
                return UUID.fromString(v.toString());
            }
        }).pack(full).length);
        assertArrayEquals(JSON.pack(full), JSON.lossless().pack(full));
    }

    @Test
    void aMissingFieldIsRefusedOnlyByACodecThatRequiresIt() {
        assertThrows(ValueException.class, () -> CODEC.unmarshal(Value.emptyMap(), Record.class));
        assertNull(JSON.unmarshal(Value.emptyMap(), Record.class).name);
        assertNull(MSGPACK.unmarshal(Value.emptyMap(), Record.class).name);
        // Naming alone does not allow it.
        final ValueCodec strict = ValueCodec.DEFAULT.named(JsonName.class, JsonName::value);
        final ValueException e = assertThrows(ValueException.class, () -> strict.unmarshal(Value.emptyMap(), Record.class));
        assertTrue(e.getMessage().contains("\"json_name\""), e.getMessage());
    }

    static final class Mixed {
        @JsonName("renamed") String a = "A";
        @JsonName String blank = "B";        // an annotation that names nothing
        String plain = "C";
        @Name("own") String named = "D";     // Name is not the annotation of this codec
        @Ignore String ignored = "E";
        @Unsigned @JsonName("u") int unsigned = -1;
    }

    @Test
    void aFieldWithoutTheAnnotationGoesByItsJavaName() {
        final ValueCodec codec = ValueCodec.DEFAULT.named(JsonName.class, JsonName::value);
        assertEquals("{\"blank\": \"B\",\"ignored\": \"E\",\"named\": \"D\",\"plain\": \"C\",\"renamed\": \"A\",\"u\": 4294967295}",
                codec.marshal(new Mixed()).toString());
        assertEquals("{\"a\": \"A\",\"blank\": \"B\",\"ignored\": \"E\",\"own\": \"D\",\"plain\": \"C\",\"unsigned\": 4294967295}",
                CODEC.marshal(new Mixed()).toString());
    }

    @Test
    void aNamingFunctionMayLeaveFieldsOut() {
        final ValueCodec codec = ValueCodec.DEFAULT.named(field -> {
            if (field.isAnnotationPresent(Ignore.class)) {
                return null;
            }
            final JsonName name = field.getAnnotation(JsonName.class);
            return name == null || name.value().isEmpty() ? field.getName().toUpperCase() : name.value();
        });
        final MapValue m = (MapValue) codec.marshal(new Mixed());
        assertEquals(Arrays.asList("BLANK", "NAMED", "PLAIN", "renamed", "u"), m.keys());

        // Reading does not look for it either, nor set it.
        final Mixed back = codec.unmarshal(m.put("ignored", Value.utf8("X")).put("IGNORED", Value.utf8("Y")).put("PLAIN", Value.utf8("c")), Mixed.class);
        assertEquals("E", back.ignored);
        assertEquals("c", back.plain);

        // A record is made with every component, the one left out at its default.
        final ValueCodec noB = ValueCodec.DEFAULT.named(field -> field.getName().equals("b") ? null : field.getName());
        assertEquals("{\"a\": 1,\"c\": 3}", noB.marshal(new Triple(1, "two", 3)).toString());
        assertEquals(new Triple(1, null, 3), noB.unmarshal(noB.marshal(new Triple(1, "two", 3)), Triple.class));

        // Two fields under one key are refused, whoever named them.
        final ValueCodec same = ValueCodec.DEFAULT.named(field -> "k");
        assertThrows(ValueException.class, () -> same.marshal(new Mixed()));
        assertThrows(NullPointerException.class, () -> ValueCodec.DEFAULT.named(null));
        assertThrows(NullPointerException.class, () -> ValueCodec.DEFAULT.named(JsonName.class, null));
    }

    record Triple(int a, String b, int c) {
    }
}
