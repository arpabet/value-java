/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks the implementation against the golden files written by the Go
 * implementation (conformance/main.go): for a corpus of bytes, texts and
 * numbers, what go.arpabet.com/value gives is what this library must give.
 *
 * <p>The committed corpus is in src/test/resources/golden; a larger one,
 * written with {@code go run . -out DIR -scale N}, is used when the Gradle
 * property {@code goldenDir} names it.
 */
class GoldenTest {

    /** Collects the lines that disagree, to report them together. */
    static final class Report {
        final String file;
        final List<String> failures = new ArrayList<>();
        int lines;

        Report(String file) {
            this.file = file;
        }

        void check(boolean ok, String what, Object line, Object want, Object got) {
            if (!ok) {
                failures.add(what + " of " + line + "\n      want " + want + "\n      got  " + got);
            }
        }

        void same(String what, Object line, Object want, Object got) {
            check(want.equals(got), what, line, want, got);
        }

        void done() {
            assertTrue(lines > 0, file + " is empty");
            if (!failures.isEmpty()) {
                final StringBuilder b = new StringBuilder();
                b.append(failures.size()).append(" disagreements with Go in ").append(file).append(" (").append(lines).append(" lines):");
                for (int i = 0; i < Math.min(failures.size(), 25); i++) {
                    b.append("\n  ").append(failures.get(i));
                }
                fail(b.toString());
            }
        }
    }

    static List<String[]> load(String name) throws IOException {
        final String dir = System.getProperty("value.golden.dir");
        final byte[] data;
        if (dir != null && !dir.isEmpty()) {
            data = Files.readAllBytes(Paths.get(dir, name));
        } else {
            try (InputStream in = GoldenTest.class.getResourceAsStream("/golden/" + name)) {
                if (in == null) {
                    throw new IOException("no golden file " + name);
                }
                data = in.readAllBytes();
            }
        }
        final List<String[]> lines = new ArrayList<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                lines.add(line.split("\t", -1));
            }
        }
        return lines;
    }

    /** The bytes of a base64 field. */
    static byte[] bytes(String field) {
        return field.equals("=") ? new byte[0] : java.util.Base64.getDecoder().decode(field);
    }

    /** The text of a base64 field, which may hold bytes that are not UTF-8. */
    static String text(String field) {
        return Utf8.decode(bytes(field));
    }

    static String describe(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    @Test
    void unpack() throws IOException {
        unpackAndCheck();
    }

    /**
     * The same with every list and map packed, printed, compared and hashed
     * without recursion, the way only those more than {@link Walk#deep} levels
     * down otherwise are.
     */
    @Test
    void unpackAndTheRestWithoutRecursion() throws IOException {
        final int deep = Walk.deep;
        Walk.deep = 0;
        try {
            unpackAndCheck();
        } finally {
            Walk.deep = deep;
        }
    }

    private static void unpackAndCheck() throws IOException {
        final Report r = new Report("unpack.tsv");
        for (String[] f : load("unpack.tsv")) {
            r.lines++;
            final String in = f[0];
            final byte[] data = Hex.decode(in);
            Value v = null;
            String error = null;
            try {
                v = Value.unpack(data);
            } catch (ValueException e) {
                error = describe(e);
            } catch (RuntimeException e) {
                r.check(false, "unpack threw", in, "a value or a ValueException", describe(e));
                continue;
            }
            if (!f[1].equals("ok")) {
                r.check(v == null, "unpack", in, "an error", v == null ? null : v.hex());
                continue;
            }
            if (v == null) {
                r.check(false, "unpack", in, f[2], error);
                continue;
            }
            r.same("repack", in, f[2], v.hex());

            // The same value from a stream, and from its own canonical bytes.
            try {
                final Value streamed = Value.read(new ByteArrayInputStream(data));
                r.check(v.equals(streamed), "stream read", in, v.hex(), streamed == null ? null : streamed.hex());
            } catch (RuntimeException e) {
                r.check(false, "stream read", in, v.hex(), describe(e));
            }
            final Value again = Value.unpack(v.pack());
            r.check(v.equals(again) && again.equals(v) && v.hashCode() == again.hashCode(), "equality after repack", in, v, again);

            for (int col = 3; col <= 4; col++) {
                final JsonMode mode = col == 3 ? JsonMode.CANONICAL : JsonMode.RELAXED;
                String json;
                try {
                    json = v.toJson(mode);
                } catch (ValueException e) {
                    json = null;
                }
                if (f[col].equals("ERR")) {
                    r.check(json == null, "json " + mode, in, "an error", json);
                    continue;
                }
                r.same("json " + mode, in, text(f[col]), String.valueOf(json));
                if (json != null && mode == JsonMode.CANONICAL) {
                    String back;
                    try {
                        back = Value.parseJson(json).hex();
                    } catch (ValueException e) {
                        back = "ERR";
                    }
                    r.same("canonical json read back", in, f[7], back);
                }
            }
            if (!f[5].equals("-")) {
                r.same("toString", in, text(f[5]), v.toString());
                r.same("jsonify", in, text(f[6]), v.jsonify());
            }
        }
        r.done();
    }

    @Test
    void json() throws IOException {
        final Report r = new Report("json.tsv");
        for (String[] f : load("json.tsv")) {
            r.lines++;
            final byte[] data = bytes(f[0]);
            final String shown = new String(data, StandardCharsets.ISO_8859_1);
            Value v = null;
            String error = null;
            try {
                v = Value.parseJson(data);
            } catch (ValueException e) {
                error = describe(e);
            } catch (RuntimeException e) {
                r.check(false, "parseJson threw", shown, "a value or a ValueException", describe(e));
                continue;
            }
            if (f[1].equals("ok")) {
                r.same("parseJson", shown, f[2], v == null ? error : v.hex());
                if (v != null && Utf8.isValid(data, 0, data.length)) {
                    // The text is the same from a String.
                    r.same("parseJson of a String", shown, f[2], Value.parseJson(new String(data, StandardCharsets.UTF_8)).hex());
                }
            } else {
                // Where Go refuses the text, and where it panics, there is no value.
                r.check(v == null, "parseJson", shown, "an error", v == null ? null : v.hex());
            }
        }
        r.done();
    }

    static String parts(BigDecimal d) {
        return d.unscaledValue() + "|" + (-(long) d.scale());
    }

    @Test
    void doubles() throws IOException {
        final Report r = new Report("double.tsv");
        for (String[] f : load("double.tsv")) {
            r.lines++;
            final long bits = Long.parseUnsignedLong(f[0], 16);
            final double d = Double.longBitsToDouble(bits);
            final NumberValue n = new DoubleNumber(bits);
            r.same("text", f[0], f[1], DoubleFormat.text(d));
            r.same("toString", f[0], f[2], n.toString());
            r.same("jsonify", f[0], f[3], n.jsonify());
            String exact;
            try {
                exact = parts(n.bigDecimalValueExact());
            } catch (ArithmeticException e) {
                exact = "ERR";
            }
            r.same("exact decimal", f[0], f[4], exact);
            String dec;
            try {
                dec = parts(n.bigDecimalValue());
            } catch (ArithmeticException e) {
                dec = "PANIC";
            }
            r.same("decimal", f[0], f[5], dec);
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                // The shortest text reads back as the same double.
                r.same("text read back", f[0], f[0], String.format("%016x", Double.doubleToRawLongBits(Double.parseDouble(DoubleFormat.text(d)))));
            }
        }
        r.done();
    }

    @Test
    void floats() throws IOException {
        final Report r = new Report("float.tsv");
        for (String[] f : load("float.tsv")) {
            r.lines++;
            final float x = Float.intBitsToFloat((int) Long.parseLong(f[0], 16));
            final DoubleFormat.Digits g = DoubleFormat.shortest(Math.abs(x));
            r.same("shortest digits", f[0], f[1] + " " + f[2], g.digits + " " + g.point);
        }
        r.done();
    }

    @Test
    void timestamps() throws IOException {
        final Report r = new Report("timestamp.tsv");
        for (String[] f : load("timestamp.tsv")) {
            r.lines++;
            final TimestampValue t = Value.timestamp(Long.parseLong(f[0]), Long.parseLong(f[1]));
            final String line = f[0] + " " + f[1];
            r.same("toString", line, f[2], t.toString());
            r.same("pack", line, f[3], t.hex());
            r.same("unpack", line, t, Value.unpack(Hex.decode(f[3])));
        }
        r.done();
    }

    @Test
    void timestampParse() throws IOException {
        final Report r = new Report("timestamp_parse.tsv");
        for (String[] f : load("timestamp_parse.tsv")) {
            r.lines++;
            final String text = text(f[0]);
            String got;
            try {
                final TimestampValue t = Value.parseTimestamp(text);
                got = "ok " + t.seconds() + " " + t.nanos();
            } catch (ValueException e) {
                got = "err";
            }
            r.same("parseTimestamp", text, f[1].equals("ok") ? "ok " + f[2] + " " + f[3] : "err", got);
        }
        r.done();
    }

    private static String attempt(java.util.concurrent.Callable<String> f, String onArithmetic) {
        try {
            return f.call();
        } catch (ArithmeticException e) {
            return onArithmetic;
        } catch (Exception e) {
            return describe(e);
        }
    }

    @Test
    void numbers() throws IOException {
        final Report r = new Report("number.tsv");
        for (String[] f : load("number.tsv")) {
            r.lines++;
            final String in = f[0];
            final NumberValue n = (NumberValue) Value.unpack(Hex.decode(in));
            r.same("toString", in, f[1], n.toString());
            if (!f[2].equals("-")) {
                r.same("longValue", in, f[2], Long.toString(n.longValue()));
            }
            if (f[3].equals("nan")) {
                r.check(n.isNaN() && Double.isNaN(n.doubleValue()), "doubleValue", in, "NaN", n.doubleValue());
            } else {
                r.same("doubleValue", in, f[3], String.format("%016x", Double.doubleToRawLongBits(n.doubleValue())));
            }
            r.same("bigIntegerValue", in, f[4], attempt(() -> n.bigIntegerValue().toString(), "PANIC"));
            r.same("bigDecimalValue", in, f[5], attempt(() -> parts(n.bigDecimalValue()), "PANIC"));
            r.same("bigIntegerValueExact", in, f[6], attempt(() -> n.bigIntegerValueExact().toString(), "ERR"));
            r.same("bigDecimalValueExact", in, f[7], attempt(() -> parts(n.bigDecimalValueExact()), "ERR"));
            final String[] ints = f[8].split(",");
            final int[] sizes = {8, 16, 32, 64};
            for (int i = 0; i < sizes.length; i++) {
                final int bits = sizes[i];
                r.same("int" + bits, in, ints[2 * i], attempt(() -> Long.toString(n.signedExact(bits)), "ERR"));
                r.same("uint" + bits, in, ints[2 * i + 1], attempt(() -> Long.toUnsignedString(n.unsignedExact(bits)), "ERR"));
            }
            final String f64 = attempt(() -> {
                final double d = n.doubleValueExact();
                return Double.isNaN(d) ? "nan" : String.format("%016x", Double.doubleToRawLongBits(d));
            }, "ERR");
            r.same("doubleValueExact", in,
                    !f[9].equals("ERR") && Double.isNaN(Double.longBitsToDouble(Long.parseUnsignedLong(f[9], 16))) ? "nan" : f[9], f64);
            final String f32 = attempt(() -> {
                final float x = n.floatValueExact();
                return Float.isNaN(x) ? "nan" : String.format("%08x", Float.floatToRawIntBits(x));
            }, "ERR");
            r.same("floatValueExact", in,
                    !f[10].equals("ERR") && Float.isNaN(Float.intBitsToFloat((int) Long.parseLong(f[10], 16))) ? "nan" : f[10], f32);
        }
        r.done();
    }

    @Test
    void parse() throws IOException {
        final Report r = new Report("parse.tsv");
        for (String[] f : load("parse.tsv")) {
            r.lines++;
            final String text = text(f[0]);
            if (!f[1].equals("PANIC")) {
                r.same("NumberValue.parse", text, f[1], NumberValue.parse(text).hex());
            } else {
                NumberValue.parse(text); // Go panics; this must not
            }
            r.same("BoolValue.parse", text, f[2], BoolValue.parse(text).toString());
            r.same("StringValue.parse", text, f[3], StringValue.parse(text).hex());
        }
        r.done();
    }

    @Test
    void base64() throws IOException {
        final Report r = new Report("base64.tsv");
        for (String[] f : load("base64.tsv")) {
            r.lines++;
            final String text = text(f[0]);
            r.same("padded", text, f[1], attemptBase64(text, true));
            r.same("raw", text, f[2], attemptBase64(text, false));
        }
        r.done();
    }

    private static String attemptBase64(String text, boolean padded) {
        try {
            return "ok:" + Hex.encode(padded ? Base64.decodeStd(text) : Base64.decodeRawStd(text));
        } catch (ValueException e) {
            return "ERR";
        }
    }
}
