/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The classes below are the twins of the Go structs in conformance/codec.go,
 * field for field. What {@link ValueCodec} and {@link StructCodec} write for
 * them must be, byte for byte, what Go's Marshal, SignBytes and PackStruct
 * write (golden/codec.tsv), and what Go wrote must read back into them.
 */
class CodecGoldenTest {

    // --- twins of the Go structs ---------------------------------------------

    static final class Inner {
        @Name("epoch") @Unsigned long epoch;
        @Name("root") byte[] root;

        Inner() {
        }

        Inner(long epoch, byte[] root) {
            this.epoch = epoch;
            this.root = root;
        }
    }

    static final class Message {
        @Name("acc") String account;
        @Name("dev") String device;
        @Name("count") @Unsigned int count;
        @Name("seq") long seq;
        @Name("live") boolean live;
        @Name("dh") byte[] dhPub;
        @Name("opk") Map<String, byte[]> opks;
        @Name("names") List<String> names;
        @Name("bundle") Inner bundle = new Inner();
        transient String skipped;
    }

    static final class AllTypes {
        @Name("b") boolean b;
        @Name("i8") byte i8;
        @Name("i16") short i16;
        @Name("i32") int i32;
        @Name("i64") long i64;
        @Name("u8") @Unsigned byte u8;
        @Name("u16") @Unsigned short u16;
        @Name("u32") @Unsigned int u32;
        @Name("u64") @Unsigned long u64;
        @Name("f32") float f32;
        @Name("f64") double f64;
        @Name("s") String s;
        @Name("bin") byte[] bin;
        @Name("big") BigInteger big;
        @Name("dec") BigDecimal dec = BigDecimal.ZERO;
        @Name("at") TimestampValue at = Value.timestamp(-62135596800L, 0); // Go's zero time
        @Name("pi") Long pi;
        @Name("ps") Inner ps;
        @Name("li") long[] li;
        @Name("lu") @Unsigned long[] lu;
        @Name("lb") byte[][] lb;
        @Name("ls") List<Inner> ls;
        @Name("ms") Map<String, String> ms;
        @Name("mu") @Unsigned Map<String, Long> mu;
        @Name("v") Value v;
        @Name("any") Object any;
        int Plain;
    }

    static final class Optional {
        @Name("id") String id;
        @Name("note") @OmitEmpty String note;
        @Name("data") @OmitEmpty byte[] data;
        @Name("n") @OmitEmpty int n;
        @Name("f") @OmitEmpty double f;
        @Name("on") @OmitEmpty boolean on;
        @Name("p") @OmitEmpty Inner p;
        @Name("l") @OmitEmpty List<String> l;
        @Name("m") @OmitEmpty Map<String, Long> m;
        @Name("v") @OmitEmpty Value v;
        @Name("u") @OmitEmpty @Unsigned long u;
    }

    static final class License {
        @Name("_dom") @Sign("license") String domain;
        @Name("product") @Sign({"license", "audit"}) String product;
        @Name("seats") @Sign("license") int seats;
        @Name("note") @OmitEmpty @Sign("license") String note;
        @Name("sig") byte[] sig;
        @Name("nested") @Sign("audit") Inner nested = new Inner();
    }

    static final class Leaf {
        @Tag(1) StringValue text;

        Leaf() {
        }

        Leaf(StringValue text) {
            this.text = text;
        }
    }

    static final class Frame {
        @Tag(1) BoolValue boolField;
        @Tag(2) NumberValue numberField;
        @Tag(3) StringValue stringField;
        @Tag(4) ListValue listField;
        @Tag(5) MapValue mapField;
        @Tag(6) TimestampValue timeField;
        @Tag(7) Value any;
        @Tag(20) List<StringValue> strings;
        @Tag(21) @Repeated List<StringValue> trace;
        @Tag(30) List<Leaf> leaves;
        @Tag(31) @Repeated List<Leaf> hops;
        @Tag(100) Leaf leaf;
        @Tag(-5) NumberValue negative;
    }

    // --- the cases -------------------------------------------------------------

    static Map<String, Object> cases() {
        final Map<String, Object> c = new LinkedHashMap<>();
        c.put("message/zero", new Message());

        final Message m = new Message();
        m.account = "vox-abc";
        m.device = "dev-1";
        m.count = 42;
        m.seq = -7;
        m.live = true;
        m.dhPub = new byte[] {1, 2, 3, 4};
        m.opks = new HashMap<>();
        m.opks.put("k1", new byte[] {9, 9});
        m.opks.put("k2", new byte[] {8});
        m.opks.put("", null);
        m.names = Arrays.asList("alice", "bob", "");
        m.bundle = new Inner(100, new byte[] {(byte) 0xaa, (byte) 0xbb});
        m.skipped = "not on the wire";
        c.put("message/full", m);

        c.put("all/zero", new AllTypes());

        final AllTypes a = new AllTypes();
        a.b = true;
        a.i8 = -8;
        a.i16 = -1600;
        a.i32 = -320000;
        a.i64 = -64000000000L;
        a.u8 = (byte) 200;
        a.u16 = (short) 60000;
        a.u32 = (int) 4000000000L;
        a.u64 = Long.parseUnsignedLong("18000000000000000000");
        a.f32 = 0.1f;
        a.f64 = 0.1;
        a.s = "héllo ✓ 😀";
        a.bin = new byte[] {0, 1, (byte) 0xfe, (byte) 0xff};
        a.big = BigInteger.ONE.shiftLeft(100);
        a.dec = new BigDecimal("-1234.5600");
        a.at = Value.timestamp(1793345319L, 75000000);
        a.pi = 7L;
        a.ps = new Inner(1, new byte[] {1});
        a.li = new long[] {1, -2, Long.MAX_VALUE, Long.MIN_VALUE};
        a.lu = new long[] {0, Long.MAX_VALUE, Long.MIN_VALUE, -1};
        a.lb = new byte[][] {{1}, null, {}};
        a.ls = Arrays.asList(new Inner(5, null), new Inner(0, new byte[] {5}));
        a.ms = new HashMap<>();
        a.ms.put("b", "2");
        a.ms.put("a", "1");
        a.ms.put("B", "3");
        a.ms.put("aa", "4");
        a.mu = new HashMap<>();
        a.mu.put("max", -1L);
        a.mu.put("one", 1L);
        a.v = Value.list(Value.ofLong(1), Value.utf8("x"), NullValue.NULL);
        a.any = "text";
        a.Plain = 5;
        c.put("all/full", a);

        final AllTypes l = new AllTypes();
        l.i8 = Byte.MIN_VALUE;
        l.i16 = Short.MIN_VALUE;
        l.i32 = Integer.MIN_VALUE;
        l.i64 = Long.MIN_VALUE;
        l.u8 = -1;
        l.u16 = -1;
        l.u32 = -1;
        l.u64 = -1;
        l.f32 = Float.MAX_VALUE;
        l.f64 = Double.MIN_VALUE;
        l.big = BigInteger.ZERO;
        l.dec = new BigDecimal(BigInteger.ONE, -3);
        l.at = Value.timestamp(-1, 500000000);
        l.v = NullValue.NULL;
        c.put("all/limits", l);

        final AllTypes nan = new AllTypes();
        nan.f32 = Float.NaN;
        nan.f64 = Double.NaN;
        final Map<String, Integer> any = new HashMap<>();
        any.put("a", 1);
        nan.any = any;
        c.put("all/nan", nan);

        final Optional empty = new Optional();
        empty.id = "x";
        c.put("optional/empty", empty);

        final Optional full = new Optional();
        full.id = "x";
        full.note = "hi";
        full.data = new byte[] {1};
        full.n = 1;
        full.f = 0.5;
        full.on = true;
        full.p = new Inner();
        full.l = Arrays.asList("a");
        full.m = new HashMap<>();
        full.m.put("k", 0L);
        full.v = NullValue.NULL;
        full.u = 1;
        c.put("optional/full", full);

        final Optional blank = new Optional();
        blank.id = "";
        blank.data = new byte[0];
        blank.l = Arrays.asList();
        blank.m = new HashMap<>();
        blank.f = -0.0;
        c.put("optional/blank", blank);

        final License lic = new License();
        lic.domain = "license/v1";
        lic.product = "mailnite";
        lic.seats = 5;
        lic.sig = new byte[] {(byte) 0xde, (byte) 0xad};
        lic.nested = new Inner(9, new byte[] {9});
        c.put("license", lic);

        c.put("frame/empty", new Frame());

        final Leaf leaf = new Leaf(Value.utf8("leaf"));
        final Frame f = new Frame();
        f.boolField = BoolValue.TRUE;
        f.numberField = Value.ofLong(123);
        f.stringField = Value.utf8("test");
        f.listField = Value.list(Value.ofLong(1), Value.ofLong(2));
        f.mapField = MapValue.builder().putNull("k").build();
        f.timeField = Value.timestamp(1793345319L, 75000000);
        f.any = Value.raw(new byte[] {1, 2});
        f.strings = Arrays.asList(Value.utf8("a"), Value.raw(new byte[] {'b'}));
        f.trace = Arrays.asList(Value.utf8("edge"), Value.utf8("relay"));
        f.leaves = Arrays.asList(leaf, new Leaf());
        f.hops = Arrays.asList(leaf, leaf);
        f.leaf = leaf;
        f.negative = Value.ofDouble(-0.5);
        c.put("frame/full", f);

        final Frame fb = new Frame();
        fb.strings = Arrays.asList();
        fb.trace = Arrays.asList();
        fb.leaves = Arrays.asList();
        fb.hops = Arrays.asList();
        fb.any = NullValue.NULL;
        c.put("frame/blank", fb);
        return c;
    }

    @Test
    void writesWhatGoWrites() throws IOException {
        final Map<String, Object> cases = cases();
        final GoldenTest.Report r = new GoldenTest.Report("codec.tsv");
        int checked = 0;
        final Map<String, String> written = new HashMap<>();
        for (String[] f : GoldenTest.load("codec.tsv")) {
            if (f[0].equals("convert")) {
                continue;
            }
            if (f[1].equals("marshal")) {
                written.put(f[0], f[2]);
            }
            r.lines++;
            final Object x = cases.get(f[0]);
            if (x == null) {
                r.check(false, "case", f[0], "a twin in CodecGoldenTest", null);
                continue;
            }
            checked++;
            final String what = f[0] + " " + f[1];
            if (f[1].equals("marshal")) {
                r.same("marshal", what, f[2], ValueCodec.DEFAULT.marshal(x).hex());
                r.same("pack", what, f[2], Hex.encode(ValueCodec.DEFAULT.pack(x)));
            } else if (f[1].equals("remarshal")) {
                // What Go wrote reads into the class, and the object read
                // writes what the Go struct read from the same bytes writes.
                final byte[] wire = Hex.decode(written.get(f[0]));
                final Object back = ValueCodec.DEFAULT.unpack(wire, x.getClass());
                r.same("unmarshal then marshal", what, f[2], ValueCodec.DEFAULT.marshal(back).hex());
                final Object lossless = ValueCodec.DEFAULT.lossless().unpack(wire, x.getClass());
                r.same("lossless unmarshal then marshal", what, f[2], ValueCodec.DEFAULT.marshal(lossless).hex());
            } else if (f[1].startsWith("sign:")) {
                r.same("signBytes", what, f[2], Hex.encode(ValueCodec.DEFAULT.signBytes(x, f[1].substring(5))));
            } else {
                r.same("struct pack", what, f[2], Hex.encode(StructCodec.pack(x)));
                final Object back = StructCodec.unpack(Hex.decode(f[2]), x.getClass());
                r.same("struct unpack then pack", what, f[2], Hex.encode(StructCodec.pack(back)));
            }
        }
        r.done();
        assertTrue(checked >= cases.size(), "every case has a line");
    }

    // --- a number into a field of each numeric type ------------------------------

    static final class I8 {
        @Name("x") byte x;
    }

    static final class I16 {
        @Name("x") short x;
    }

    static final class I32 {
        @Name("x") int x;
    }

    static final class I64 {
        @Name("x") long x;
    }

    static final class U8 {
        @Name("x") @Unsigned byte x;
    }

    static final class U16 {
        @Name("x") @Unsigned short x;
    }

    static final class U32 {
        @Name("x") @Unsigned int x;
    }

    static final class U64 {
        @Name("x") @Unsigned long x;
    }

    static final class F32 {
        @Name("x") float x;
    }

    static final class F64 {
        @Name("x") double x;
    }

    static final class BI {
        @Name("x") BigInteger x;
    }

    static final class BD {
        @Name("x") BigDecimal x;
    }

    private interface Show<T> {
        String show(T t);
    }

    private static <T> String field(ValueCodec codec, Value n, Class<T> type, Show<T> show) {
        try {
            return show.show(codec.unmarshal(MapValue.builder().put("x", n).build(), type));
        } catch (ValueException e) {
            return "ERR";
        }
    }

    static String row(ValueCodec codec, Value n) {
        return String.join(",",
                field(codec, n, I8.class, t -> Long.toString(t.x)),
                field(codec, n, I16.class, t -> Long.toString(t.x)),
                field(codec, n, I32.class, t -> Long.toString(t.x)),
                field(codec, n, I64.class, t -> Long.toString(t.x)),
                field(codec, n, U8.class, t -> Long.toString(t.x & 0xFF)),
                field(codec, n, U16.class, t -> Long.toString(t.x & 0xFFFF)),
                field(codec, n, U32.class, t -> Long.toString(t.x & 0xFFFFFFFFL)),
                field(codec, n, U64.class, t -> Long.toUnsignedString(t.x)),
                field(codec, n, F32.class, t -> Float.isNaN(t.x) ? "nan" : String.format("%08x", Float.floatToRawIntBits(t.x))),
                field(codec, n, F64.class, t -> Double.isNaN(t.x) ? "nan" : String.format("%016x", Double.doubleToRawLongBits(t.x))),
                field(codec, n, BI.class, t -> t.x.toString()),
                field(codec, n, BD.class, t -> GoldenTest.parts(t.x)));
    }

    @Test
    void convertsNumbersAsGoConverts() throws IOException {
        final GoldenTest.Report r = new GoldenTest.Report("codec.tsv (convert)");
        for (String[] f : GoldenTest.load("codec.tsv")) {
            if (!f[0].equals("convert")) {
                continue;
            }
            r.lines++;
            final Value n = Value.unpack(Hex.decode(f[1]));
            r.same("default codec", f[1] + " " + n, f[2], row(ValueCodec.DEFAULT, n));
            r.same("lossless codec", f[1] + " " + n, f[3], row(ValueCodec.DEFAULT.lossless(), n));
        }
        r.done();
    }

    @Test
    void datesAreTimestampsOfMilliseconds() {
        final class Event {
            @Name("at") Date at;
        }
        final Event e = new Event();
        e.at = new Date(1793345319075L);
        assertEquals("81a26174d7ff11e1a3006ae44727", ValueCodec.DEFAULT.marshal(e).hex());
    }
}
