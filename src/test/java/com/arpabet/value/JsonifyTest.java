/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@code jsonify} writes JSON for every value, as the Go implementation's
 * {@code Jsonify} does since its v1.6.2: the cases of its
 * {@code jsonify_test.go}. The golden corpus checks the same against Go on
 * random text.
 */
class JsonifyTest {

    private static final int REPLACEMENT = 0xFFFD;
    /** In the basic plane and not printable. */
    private static final int ZERO_WIDTH = 0x200B;
    /** Beyond the basic plane and not printable. */
    private static final int LANGUAGE_TAG = 0xE0001;

    /** The escape of a UTF-16 code unit as it stands in a JSON text: a backslash, u and four digits. */
    private static String esc(int unit) {
        return String.format("%cu%04x", '\\', unit);
    }

    /** A JSON string of the parts. */
    private static String quoted(String... parts) {
        return '"' + String.join("", parts) + '"';
    }

    private static String text(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    /** A str of bytes, which need not be UTF-8. */
    private static StringValue str(int... bytes) {
        final byte[] packed = new byte[bytes.length + 1];
        packed[0] = (byte) (0xa0 + bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            packed[i + 1] = (byte) bytes[i];
        }
        return (StringValue) Value.unpack(packed);
    }

    /** The rendering is JSON, a JSON reader gives back the text, and it is the rendering expected. */
    private static void same(String json, String readsBackAs, Value v) {
        assertEquals(json, v.jsonify());
        assertEquals(Value.utf8(readsBackAs), Value.parseJson(v.jsonify()), json);
    }

    @Test
    void textThatHadNoJsonFormIsJson() {
        same(quoted(esc(0x07)), text(0x07), Value.utf8(text(0x07)));
        same(quoted(esc(0x0b)), text(0x0b), Value.utf8(text(0x0b)));
        same(quoted(esc(0x00)), text(0x00), Value.utf8(text(0x00)));
        same(quoted(esc(0x1b), "[0m"), text(0x1b) + "[0m", Value.utf8(text(0x1b) + "[0m"));
        same(quoted(esc(0x7f)), text(0x7f), Value.utf8(text(0x7f)));
        // Beyond the basic plane and not printable: its surrogate pair.
        same(quoted(esc(0xdb40), esc(0xdc01)), text(LANGUAGE_TAG), Value.utf8(text(LANGUAGE_TAG)));
        // A byte that is not UTF-8 reads back as U+FFFD.
        same(quoted(esc(REPLACEMENT)), text(REPLACEMENT), str(0xff));
        same(quoted("a", esc(REPLACEMENT)), "a" + text(REPLACEMENT), str('a', 0xc3));
        same(quoted(esc(REPLACEMENT), esc(REPLACEMENT), esc(REPLACEMENT)),
                text(REPLACEMENT, REPLACEMENT, REPLACEMENT), str(0xed, 0xa0, 0x80));
    }

    @Test
    void textThatWasJsonIsRenderedAsItWas() {
        same("\"\"", "", Value.utf8(""));
        same("\"text\"", "text", Value.utf8("text"));
        same("\"quote \\\" and backslash \\\\\"", "quote \" and backslash \\", Value.utf8("quote \" and backslash \\"));
        same("\"line\\nreturn\\rtab\\tbackspace\\bfeed\\f\"", "line\nreturn\rtab\tbackspace\bfeed\f",
                Value.utf8("line\nreturn\rtab\tbackspace\bfeed\f"));
        final String accent = "h" + text(0xe9) + "llo";
        same(quoted(accent), accent, Value.utf8(accent));
        final String cjk = text(0x65e5, 0x672c, 0x8a9e);
        same(quoted(cjk), cjk, Value.utf8(cjk));
        // Printable, beyond the basic plane: as it is.
        same(quoted(text(0x1f600)), text(0x1f600), Value.utf8(text(0x1f600)));
        // Not printable, in the basic plane: escaped, as it was.
        same(quoted("zero", esc(ZERO_WIDTH), "width"), "zero" + text(ZERO_WIDTH) + "width",
                Value.utf8("zero" + text(ZERO_WIDTH) + "width"));
        // The replacement character itself is printable.
        same(quoted(text(REPLACEMENT)), text(REPLACEMENT), Value.utf8(text(REPLACEMENT)));
    }

    @Test
    void mapKeyIsQuotedAsTextIs() {
        final MapValue m = MapValue.builder()
                .put("a\"b", 1)
                .put("back\\slash", 2)
                .put("line\nbreak", 3)
                .put(text(0x00), 4)
                .put("plain", 5)
                .build();
        assertEquals("{" + quoted(esc(0x00)) + ": 4,\"a\\\"b\": 1,\"back\\\\slash\": 2,\"line\\nbreak\": 3,\"plain\": 5}",
                m.jsonify());
        assertEquals(m.jsonify(), m.toString());
        assertEquals(m.keys(), ((MapValue) Value.parseJson(m.jsonify())).keys());

        // A key that needs no escape is written as before.
        final String key = text(0x43a, 0x43b, 0x44e, 0x447);
        assertEquals("{\"name\": \"ann\",\"" + key + "\": 1}",
                MapValue.builder().put("name", "ann").put(key, 1).build().jsonify());
    }

    @Test
    void mapKeyIsQuotedAtAnyDepth() {
        // Past its first levels a value is printed without recursion: the same key there.
        Value v = MapValue.builder().put("q\"", "x").build();
        final StringBuilder open = new StringBuilder();
        final StringBuilder close = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            v = MapValue.builder().put("k", v).build();
            open.append("{\"k\": ");
            close.append('}');
        }
        assertEquals(open + "{\"q\\\"\": \"x\"}" + close, v.jsonify());
        assertEquals(v, Value.parseJson(v.jsonify()));
    }

    @Test
    void doubleThatIsNotFiniteIsNull() {
        for (double d : new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
            assertEquals("null", Value.ofDouble(d).jsonify());
        }
        // toString is not JSON and says what the double is.
        assertEquals("+Inf", Value.ofDouble(Double.POSITIVE_INFINITY).toString());
        assertEquals("-Inf", Value.ofDouble(Double.NEGATIVE_INFINITY).toString());
        final ListValue l = Value.listOf(Arrays.asList(
                Value.ofDouble(Double.POSITIVE_INFINITY), Value.ofDouble(1.5), Value.ofDouble(Double.NEGATIVE_INFINITY)));
        assertEquals("[null,1.5,null]", l.jsonify());
    }
}
