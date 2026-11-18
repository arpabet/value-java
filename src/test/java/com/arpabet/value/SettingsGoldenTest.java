/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The settings that are package variables in the Go implementation
 * (Base64Prefix, UnknownPrefix, DecimalExpDelim, PrecisionLevel,
 * FirstIndexKey): with them changed there, what Go gives
 * (conformance/settings.go) is what this library gives with them changed
 * here. They are settings of the whole process; every test puts them back.
 */
class SettingsGoldenTest {

    @AfterEach
    void asTheLibraryComes() {
        Value.setBase64Prefix("base64,");
        Value.setUnknownPrefix("data:application/x-msgpack-ext;");
        Value.setDecimalExpDelim('x');
        Value.setPrecisionLevel(0.00001);
        Value.setFirstIndexKey(0);
    }

    @Test
    void settings() throws IOException {
        final GoldenTest.Report r = new GoldenTest.Report("settings.tsv");
        final Set<String> kinds = new HashSet<>();
        String set = "";
        for (String[] f : GoldenTest.load("settings.tsv")) {
            r.lines++;
            kinds.add(f[0]);
            final String line = set + " | " + String.join(" ", f);
            try {
                switch (f[0]) {
                    case "set":
                        Value.setBase64Prefix(GoldenTest.text(f[1]));
                        Value.setUnknownPrefix(GoldenTest.text(f[2]));
                        Value.setDecimalExpDelim((char) Integer.parseInt(f[3]));
                        Value.setPrecisionLevel(Double.parseDouble(f[4]));
                        Value.setFirstIndexKey(Integer.parseInt(f[5]));
                        set = "[" + Value.base64Prefix() + " " + Value.unknownPrefix() + " " + Value.decimalExpDelim() + "]";
                        break;
                    case "text": {
                        final Value v = Value.unpack(Hex.decode(f[1]));
                        r.same("toString", line, GoldenTest.text(f[2]), v.toString());
                        r.same("jsonify", line, GoldenTest.text(f[3]), v.jsonify());
                        break;
                    }
                    case "getters": {
                        // Where Go panics there is nothing to agree with, and nothing may be thrown.
                        final ListValue one = Value.list(Value.unpack(Hex.decode(f[1])));
                        final String string = one.getString(0).hex();
                        final String number = one.getNumber(0).hex();
                        if (!f[2].equals("PANIC")) {
                            r.same("getString", line, f[2], string);
                        }
                        if (!f[3].equals("PANIC")) {
                            r.same("getNumber", line, f[3], number);
                        }
                        break;
                    }
                    case "parsestring":
                        r.same("StringValue.parse", line, f[2], StringValue.parse(GoldenTest.text(f[1])).hex());
                        break;
                    case "parsenumber": {
                        final String got = NumberValue.parse(GoldenTest.text(f[1])).hex();
                        if (!f[2].equals("PANIC")) {
                            r.same("NumberValue.parse", line, f[2], got);
                        }
                        break;
                    }
                    case "unpack": {
                        String got;
                        try {
                            got = Value.unpack(Hex.decode(f[1])).hex();
                        } catch (ValueException e) {
                            got = "err";
                        }
                        r.same("unpack", line, f[2].equals("PANIC") ? "err" : f[2], got);
                        break;
                    }
                    case "approx": {
                        final NumberValue a = (NumberValue) Value.unpack(Hex.decode(f[1]));
                        final NumberValue b = (NumberValue) Value.unpack(Hex.decode(f[2]));
                        r.same("approxEquals", line, f[3], String.valueOf(a.approxEquals(b)));
                        r.same("approxEquals the other way", line, f[3], String.valueOf(b.approxEquals(a)));
                        break;
                    }
                    case "append":
                        r.same("append", line, f[1],
                                Value.emptySparseList().append(Value.ofLong(7)).append(Value.ofLong(8)).hex());
                        r.same("append to a mutable list", line, f[1],
                                Value.mutableSparseList().append(Value.ofLong(7)).append(Value.ofLong(8)).hex());
                        break;
                    case "appendafter":
                        r.same("append after an index", line, f[1],
                                Value.emptySparseList().putAt(4, Value.ofLong(1)).append(Value.ofLong(2)).hex());
                        r.same("append after an index to a mutable list", line, f[1],
                                Value.mutableSparseList().putAt(4, Value.ofLong(1)).append(Value.ofLong(2)).hex());
                        break;
                    default:
                        r.check(false, "kind", line, "a known kind", f[0]);
                }
            } catch (RuntimeException e) {
                r.check(false, f[0] + " threw", line, "an answer", GoldenTest.describe(e));
            }
        }
        r.done();
        assertTrue(kinds.containsAll(java.util.Arrays.asList(
                "set", "text", "getters", "parsestring", "parsenumber", "unpack", "approx", "append", "appendafter")),
                "settings.tsv lacks a kind: " + kinds);
    }

    @Test
    void theyAreAsInGoUntilSet() {
        assertEquals("base64,", Value.base64Prefix());
        assertEquals("data:application/x-msgpack-ext;", Value.unknownPrefix());
        assertEquals('x', Value.decimalExpDelim());
        assertEquals(0.00001, Value.precisionLevel());
        assertEquals(0, Value.firstIndexKey());

        final Value binary = Value.raw(new byte[] {1, 2});
        final Value extension = Value.ext(7, new byte[] {1, 2});
        final Value decimal = Value.ofDecimal(new BigDecimal("123.567"));
        assertEquals("base64,AQI", binary.toString());
        assertEquals("\"base64,AQI\"", binary.jsonify());
        assertEquals("data:application/x-msgpack-ext;base64,BwEC", extension.toString());
        assertEquals("0x01e2afx-03", decimal.toString());
        assertEquals(binary, StringValue.parse("base64,AQI"));
        assertEquals(decimal, NumberValue.parse("0x01e2afx-03"));
        assertTrue(((NumberValue) Value.ofDouble(1)).approxEquals((NumberValue) Value.ofDouble(1.000001)));
        assertFalse(((NumberValue) Value.ofDouble(1)).approxEquals((NumberValue) Value.ofDouble(1.1)));
        assertEquals("810007", Value.emptySparseList().append(Value.ofLong(7)).hex());
    }

    @Test
    void theyChangeTheTextAndNotTheBytes() {
        final Value binary = Value.raw(new byte[] {1, 2});
        final Value extension = Value.ext(7, new byte[] {1, 2});
        final Value decimal = Value.ofDecimal(new BigDecimal("123.567"));
        final String bytes = Value.list(binary, extension, decimal).hex();
        final String json = Value.list(binary, extension, decimal).toJson(JsonMode.CANONICAL);

        Value.setBase64Prefix("b64:");
        Value.setUnknownPrefix("ext:");
        Value.setDecimalExpDelim('^');
        assertEquals("b64:AQI", binary.toString());
        assertEquals("ext:b64:BwEC", extension.toString());
        assertEquals("0x01e2af^-03", decimal.toString());
        assertEquals("[\"b64:AQI\",\"ext:b64:BwEC\",\"0x01e2af^-03\"]", Value.list(binary, extension, decimal).toString());
        // And is read back by the same settings, and no longer by the old.
        assertEquals(binary, StringValue.parse("b64:AQI"));
        assertEquals(Value.utf8("base64,AQI"), StringValue.parse("base64,AQI"));
        assertEquals(decimal, NumberValue.parse("0x01e2af^-03"));
        // A getter that reads a string out of another kind goes through its text: an
        // extension whose text starts as a binary's does is read as that binary.
        assertEquals(Value.utf8("ext:b64:BwEC"), Value.list(extension).getString(0));
        Value.setUnknownPrefix("");
        assertEquals(Value.raw(new byte[] {7, 1, 2}), Value.list(extension).getString(0));
        Value.setUnknownPrefix("ext:");
        // The canonical bytes and the lossless JSON are what they were.
        assertEquals(bytes, Value.list(binary, extension, decimal).hex());
        assertEquals(json, Value.list(binary, extension, decimal).toJson(JsonMode.CANONICAL));
        assertEquals(Value.list(binary, extension, decimal), Value.unpack(Hex.decode(bytes)));

        Value.setPrecisionLevel(0.5);
        assertTrue(((NumberValue) Value.ofLong(1)).approxEquals((NumberValue) Value.ofDouble(1.4)));
        assertFalse(((NumberValue) Value.ofLong(1)).approxEquals((NumberValue) Value.ofDouble(1.6)));

        Value.setFirstIndexKey(1);
        assertEquals("82010702" + "08", Value.emptySparseList().append(Value.ofLong(7)).append(Value.ofLong(8)).hex());
        assertEquals("810107", Value.mutableSparseList().append(Value.ofLong(7)).hex());
        // A dense list has no index to start from.
        assertEquals("9107", Value.emptyList().append(Value.ofLong(7)).hex());

        assertThrows(NullPointerException.class, () -> Value.setBase64Prefix(null));
        assertThrows(NullPointerException.class, () -> Value.setUnknownPrefix(null));
        assertThrows(IllegalArgumentException.class, () -> Value.setDecimalExpDelim('é'));
        assertEquals('^', Value.decimalExpDelim());
        assertEquals(new BigInteger("7b", 16), ((NumberValue) NumberValue.parse("0x7b")).bigIntegerValue());
    }
}
