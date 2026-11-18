/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * Big numbers in the layout of before v1.5.0, against what the Go
 * implementation reads with {@code LegacyBigNumbers} set
 * (conformance/legacy.go).
 */
class LegacyGoldenTest {

    private static final Limits LEGACY = Limits.DEFAULT.withLegacyBigNumbers(true);

    private static byte[] bytes(String field) {
        return field.equals("=") ? new byte[0] : Hex.decode(field);
    }

    @Test
    void legacy() throws IOException {
        final GoldenTest.Report r = new GoldenTest.Report("legacy.tsv");
        for (String[] f : GoldenTest.load("legacy.tsv")) {
            r.lines++;
            final String line = f[0] + " " + f[1];
            final String want = f[2].equals("PANIC") ? "err" : f[2];
            String got;
            try {
                switch (f[0]) {
                    case "bigint": {
                        final NumberValue n = (NumberValue) Value.ext(ExtensionValue.BIGINT, bytes(f[1]), LEGACY);
                        r.same("type", line, NumberType.BIGINT, n.type());
                        got = n.bigIntegerValue().toString();
                        break;
                    }
                    case "decimal": {
                        final NumberValue n = (NumberValue) Value.ext(ExtensionValue.DECIMAL, bytes(f[1]), LEGACY);
                        r.same("type", line, NumberType.DECIMAL, n.type());
                        got = GoldenTest.parts(n.bigDecimalValue());
                        break;
                    }
                    case "unpack": {
                        final byte[] data = bytes(f[1]);
                        final Value v = Value.unpack(data, LEGACY);
                        got = v.hex();
                        r.same("stream read", line, got, Value.read(new ByteArrayInputStream(data), LEGACY).hex());
                        r.same("unpacker", line, got, new Unpacker(data).limits(LEGACY).next().hex());
                        break;
                    }
                    case "json":
                        got = Value.parseJson(GoldenTest.bytes(f[1]), LEGACY).hex();
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
    void theLayoutIsChosenByTheLimits() {
        final byte[] legacyBigInt = Hex.decode("d501027b"); // 123
        final byte[] legacyDecimal = Hex.decode("d702fffffffd0201e2af"); // 123.567

        // Without the switch the same bytes are another number: nothing tells the layouts apart.
        assertEquals(new BigInteger("635"), ((NumberValue) Value.unpack(legacyBigInt)).bigIntegerValue());
        assertFalse(Limits.DEFAULT.legacyBigNumbers());

        assertEquals(Value.ofBigInt(BigInteger.valueOf(123)), Value.unpack(legacyBigInt, LEGACY));
        assertEquals(Value.ofDecimal(new BigDecimal("123.567")), Value.unpack(legacyDecimal, LEGACY));

        // Packing writes the layout in use now, so reading and packing migrates.
        assertEquals("d4017b", Value.unpack(legacyBigInt, LEGACY).hex());
        assertEquals("c7070200000003" + "01e2af", Value.unpack(legacyDecimal, LEGACY).hex());

        assertEquals(BigInteger.valueOf(-123),
                ((NumberValue) Value.ext(1, new byte[] {0x03, 0x7b}, LEGACY)).bigIntegerValue());
        assertThrows(ValueException.class, () -> Value.ext(1, new byte[] {0x09}, LEGACY));
        assertThrows(ValueException.class, () -> Value.ext(2, new byte[] {0x00}, LEGACY));

        // The other limits stay as they were, and the switch goes off again.
        final Limits limits = Limits.DEFAULT.withMaxDepth(7).withLegacyBigNumbers(true);
        assertTrue(limits.legacyBigNumbers());
        assertEquals(7, limits.maxDepth());
        assertEquals(7, limits.withMaxByteLength(100).withLegacyBigNumbers(false).maxDepth());
        assertTrue(limits.withMaxByteLength(100).legacyBigNumbers());
        assertEquals(Limits.DEFAULT, LEGACY.withLegacyBigNumbers(false));
        assertNotEquals(Limits.DEFAULT, LEGACY);
        assertNotEquals(Limits.DEFAULT.hashCode(), LEGACY.hashCode());
        assertTrue(LEGACY.toString().contains("legacyBigNumbers"));
        assertThrows(ValueException.class,
                () -> Value.unpack(Hex.decode("9191d501027b"), LEGACY.withMaxDepth(1)));
    }
}
