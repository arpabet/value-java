/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A number: a long, a double, a big integer or a decimal. The four
 * {@linkplain #type() types} are distinct values with distinct encodings, and
 * none equals another whatever the number: equality follows the canonical
 * bytes.
 *
 * <p>The {@code xValue()} methods convert as the Go implementation does,
 * truncating and wrapping; the {@code xValueExact()} methods convert only
 * when nothing is lost.
 */
public abstract sealed class NumberValue extends Value
        permits LongNumber, DoubleNumber, BigIntNumber, DecimalNumber {

    NumberValue() {
    }

    @Override
    public final Kind kind() {
        return Kind.NUMBER;
    }

    /** The type of the number, which decides its encoding. */
    public abstract NumberType type();

    /** Reports whether this is a double that is not a number. */
    public abstract boolean isNaN();

    /**
     * The number as a long: a double or a decimal without its fraction, a big
     * integer by its low 64 bits, NaN as 0.
     */
    public abstract long longValue();

    /** The number as the nearest double. */
    public abstract double doubleValue();

    /**
     * The number as an integer: a double or a decimal without its fraction,
     * NaN as 0.
     *
     * @throws ArithmeticException for an infinite double
     */
    public abstract BigInteger bigIntegerValue();

    /**
     * The number as a decimal: a double by its shortest digits, NaN as 0.
     *
     * @throws ArithmeticException for an infinite double
     */
    public abstract BigDecimal bigDecimalValue();

    /**
     * The sum, in the type of this number: longs wrap, a decimal keeps the
     * larger scale.
     */
    public abstract NumberValue add(NumberValue other);

    /** The difference, in the type of this number. */
    public abstract NumberValue subtract(NumberValue other);

    /** The one NaN of the canonical form. */
    public static NumberValue nan() {
        return DoubleNumber.NAN;
    }

    /**
     * Reads a number as the typed getters do for a value that is not one, in
     * the texts {@code toString} writes and a few more: an integer is a long,
     * or a big integer beyond the range of one; a text with a period, an
     * exponent, {@code inf} or {@code nan} is a double; {@code 0x7b} is a big
     * integer and {@code 0x01e2afx-03} a decimal, in the hexadecimal form big
     * numbers print in; the empty text and {@code null} are 0; anything else
     * is NaN.
     */
    public static NumberValue parse(String s) {
        return Coerce.parseNumber(s, Limits.DEFAULT);
    }

    static NumberValue unsigned(long bits) {
        if (bits >= 0) {
            return LongNumber.of(bits);
        }
        return new BigIntNumber(unsignedBigInteger(bits));
    }

    static BigInteger unsignedBigInteger(long bits) {
        final BigInteger n = BigInteger.valueOf(bits);
        return bits >= 0 ? n : n.add(TWO_64);
    }

    static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    // --- conversions that lose nothing ---------------------------------------

    /**
     * The number as an integer, when it is one: a double or a decimal must
     * have no fraction.
     *
     * @throws ArithmeticException when the number is not an integer
     */
    public final BigInteger bigIntegerValueExact() {
        switch (type()) {
            case LONG:
                return BigInteger.valueOf(longValue());
            case BIGINT:
                return bigIntegerValue();
            case DOUBLE: {
                final double d = doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
                    throw new ArithmeticException("double " + DoubleFormat.text(d) + " is not an integer");
                }
                return new BigDecimal(d).toBigInteger();
            }
            default: {
                final BigDecimal d = bigDecimalValue();
                try {
                    return d.toBigIntegerExact();
                } catch (ArithmeticException e) {
                    throw new ArithmeticException("decimal " + Json.decimalText(d) + " is not an integer");
                }
            }
        }
    }

    /**
     * The number as a decimal: a double through its shortest text, which must
     * be finite.
     *
     * @throws ArithmeticException for NaN and the infinities
     */
    public final BigDecimal bigDecimalValueExact() {
        switch (type()) {
            case LONG:
                return BigDecimal.valueOf(longValue());
            case BIGINT:
                return new BigDecimal(bigIntegerValue());
            case DOUBLE: {
                final double d = doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new ArithmeticException("double " + DoubleFormat.text(d) + " is not a decimal");
                }
                return DoubleFormat.exactDecimal(d);
            }
            default:
                return bigDecimalValue();
        }
    }

    /** The number as a long, when it is an integer within the range of one. */
    public final long longValueExact() {
        return signedExact(64);
    }

    /** The number as an int, when it is an integer within the range of one. */
    public final int intValueExact() {
        return (int) signedExact(32);
    }

    /**
     * The number as a double: a double always, any other number when the
     * shortest text of the nearest double reads back as the same number (1.10
     * is 1.1; 0.12345678901234567890 and 2^53 + 1 are not doubles).
     *
     * @throws ArithmeticException when the number is not a double
     */
    public final double doubleValueExact() {
        if (type() == NumberType.DOUBLE) {
            return doubleValue();
        }
        final BigDecimal exact = bigDecimalValueExact();
        final double f = Double.parseDouble(exact.toString());
        if (Double.isInfinite(f) || DoubleFormat.shortestDecimal(f).compareTo(exact) != 0) {
            throw new ArithmeticException(this + " is not exactly a float64");
        }
        return f;
    }

    /**
     * The number as a float: a double when it is one, any other number when
     * the shortest text of the nearest float reads back as the same number.
     *
     * @throws ArithmeticException when the number is not a float
     */
    public final float floatValueExact() {
        if (type() == NumberType.DOUBLE) {
            final double d = doubleValue();
            if (!Double.isNaN(d) && (double) (float) d != d) {
                throw new ArithmeticException("double " + DoubleFormat.text(d) + " is not a float32");
            }
            return (float) d;
        }
        final BigDecimal exact = bigDecimalValueExact();
        final float f = Float.parseFloat(exact.toString());
        if (Float.isInfinite(f) || DoubleFormat.shortestDecimal(f).compareTo(exact) != 0) {
            throw new ArithmeticException(this + " is not exactly a float32");
        }
        return f;
    }

    /** The number as a signed integer of the bit size, held in a long. */
    final long signedExact(int bits) {
        final BigInteger i = bigIntegerValueExact();
        if (i.bitLength() > bits - 1) {
            throw new ArithmeticException(this + " overflows int" + bits);
        }
        return i.longValue();
    }

    /** The number as an unsigned integer of the bit size, held in the low bits of a long. */
    final long unsignedExact(int bits) {
        final BigInteger i = bigIntegerValueExact();
        if (i.signum() < 0 || i.bitLength() > bits) {
            throw new ArithmeticException(this + " overflows uint" + bits);
        }
        return i.longValue();
    }

    /**
     * The low 64 bits of the number as an unsigned integer, as a field of an
     * unsigned type takes it without checking: a big integer or a decimal by
     * the low bits of its integer part, any other number through
     * {@link #longValue()}, so that a negative long wraps.
     */
    final long unsignedWrapped() {
        switch (type()) {
            case BIGINT:
            case DECIMAL:
                return bigIntegerValue().longValue();
            default:
                return longValue();
        }
    }

    /**
     * Reports whether two numbers are close: their doubles differ by less than
     * 0.00001. It compares across types, is not consistent with the encoding
     * and is not transitive, so it is no basis for hashing; NaN is close to
     * nothing.
     */
    public final boolean approxEquals(NumberValue other) {
        if (other == null || isNaN() || other.isNaN()) {
            return false;
        }
        return Math.abs(doubleValue() - other.doubleValue()) < 0.00001;
    }

    // --- the hexadecimal text of big numbers ----------------------------------

    /** {@code 0x7b}, {@code -0x7b}, {@code 0x0}: the magnitude in whole bytes. */
    static String formatBigInt(BigInteger n) {
        final StringBuilder b = new StringBuilder();
        b.append(n.signum() >= 0 ? "0x" : "-0x");
        appendMagnitude(b, n);
        return b.toString();
    }

    /** {@code 0x01e2afx-03}: the unscaled integer, then the base-10 exponent after an x. */
    static String formatDecimal(BigDecimal d) {
        final BigInteger c = d.unscaledValue();
        final long exp = -(long) d.scale();
        final StringBuilder b = new StringBuilder();
        b.append(c.signum() >= 0 ? "0x" : "-0x");
        appendMagnitude(b, c);
        if (exp != 0) {
            b.append('x');
            long e = exp;
            if (e < 0) {
                b.append('-');
                e = -e;
            }
            boolean started = false;
            for (int shift = 24; shift >= 0; shift -= 8) {
                final int octet = (int) (e >>> shift) & 0xFF;
                if (octet != 0 || started) {
                    Hex.append(b, octet);
                    started = true;
                }
            }
        }
        return b.toString();
    }

    private static void appendMagnitude(StringBuilder b, BigInteger n) {
        final byte[] mag = n.abs().toByteArray();
        int i = 0;
        while (i < mag.length && mag[i] == 0) {
            i++;
        }
        if (i == mag.length) {
            b.append('0');
            return;
        }
        for (; i < mag.length; i++) {
            Hex.append(b, mag[i]);
        }
    }
}
