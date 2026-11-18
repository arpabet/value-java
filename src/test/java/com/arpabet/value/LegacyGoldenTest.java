/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Big numbers in the layout of before v1.5.0, against what the Go
 * implementation reads with {@code LegacyBigNumbers} set
 * (conformance/legacy.go). The switch is one for the whole process here as it
 * is there; the tests of this class set it and put it back.
 */
class LegacyGoldenTest {

    @AfterEach
    void switchOff() {
        Value.setLegacyBigNumbers(false);
    }

    private static byte[] bytes(String field) {
        return field.equals("=") ? new byte[0] : Hex.decode(field);
    }

    @Test
    void legacy() throws IOException {
        final GoldenTest.Report r = new GoldenTest.Report("legacy.tsv");
        Value.setLegacyBigNumbers(true);
        for (String[] f : GoldenTest.load("legacy.tsv")) {
            r.lines++;
            final String line = f[0] + " " + f[1];
            final String want = f[2].equals("PANIC") ? "err" : f[2];
            String got;
            try {
                switch (f[0]) {
                    case "bigint": {
                        got = Value.unpackLegacyBigInt(bytes(f[1])).toString();
                        // The decoder gives the same number for the same payload.
                        final NumberValue n = (NumberValue) Value.ext(ExtensionValue.BIGINT, bytes(f[1]));
                        r.same("type", line, NumberType.BIGINT, n.type());
                        r.same("as an extension", line, got, n.bigIntegerValue().toString());
                        break;
                    }
                    case "decimal": {
                        got = GoldenTest.parts(Value.unpackLegacyDecimal(bytes(f[1])));
                        final NumberValue n = (NumberValue) Value.ext(ExtensionValue.DECIMAL, bytes(f[1]));
                        r.same("type", line, NumberType.DECIMAL, n.type());
                        r.same("as an extension", line, got, GoldenTest.parts(n.bigDecimalValue()));
                        break;
                    }
                    case "unpack": {
                        final byte[] data = bytes(f[1]);
                        final Value v = Value.unpack(data);
                        got = v.hex();
                        r.same("stream read", line, got, Value.read(new ByteArrayInputStream(data)).hex());
                        r.same("unpacker", line, got, new Unpacker(data).next().hex());
                        break;
                    }
                    case "json":
                        got = Value.parseJson(GoldenTest.bytes(f[1])).hex();
                        break;
                    default:
                        got = "unknown kind";
                }
            } catch (ValueException e) {
                got = "err";
            } catch (RuntimeException e) {
                got = "threw " + GoldenTest.describe(e);
            }
            r.same(f[0], line, want, got);
        }
        r.done();
    }

    /** The test of the Go implementation, bignum_test.go. */
    @Test
    void theLayoutIsChosenByTheSwitch() {
        final byte[] legacyBigInt = Hex.decode("d501027b"); // 123
        final byte[] legacyDecimal = Hex.decode("d702fffffffd0201e2af"); // 123.567

        // Without the switch the same bytes are another number: nothing tells the layouts apart.
        assertFalse(Value.legacyBigNumbers());
        assertEquals(new BigInteger("635"), ((NumberValue) Value.unpack(legacyBigInt)).bigIntegerValue());

        Value.setLegacyBigNumbers(true);
        assertTrue(Value.legacyBigNumbers());
        assertEquals(Value.ofBigInt(BigInteger.valueOf(123)), Value.unpack(legacyBigInt));
        assertEquals(Value.ofDecimal(new BigDecimal("123.567")), Value.unpack(legacyDecimal));
        // Packing writes the layout in use now, so reading and packing migrates.
        assertEquals("d4017b", Value.unpack(legacyBigInt).hex());
        assertEquals("c7070200000003" + "01e2af", Value.unpack(legacyDecimal).hex());
        // The limits apply as ever.
        assertThrows(ValueException.class,
                () -> Value.unpack(Hex.decode("9191d501027b"), Limits.DEFAULT.withMaxDepth(1)));

        Value.setLegacyBigNumbers(false);
        assertEquals(new BigInteger("635"), ((NumberValue) Value.unpack(legacyBigInt)).bigIntegerValue());

        // The two functions read the old layouts whatever the switch says.
        assertEquals(BigInteger.valueOf(-123), Value.unpackLegacyBigInt(new byte[] {0x03, 0x7b}));
        assertEquals(new BigDecimal("123.567"), Value.unpackLegacyDecimal(Hex.decode("fffffffd0201e2af")));
        assertThrows(ValueException.class, () -> Value.unpackLegacyBigInt(new byte[] {0x09}));
        assertThrows(ValueException.class, () -> Value.unpackLegacyDecimal(new byte[] {0x00}));
    }
}
