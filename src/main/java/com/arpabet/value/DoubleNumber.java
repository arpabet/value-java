/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/** A double, held by its bits so that a NaN read from bytes packs back to them. */
final class DoubleNumber extends NumberValue {

    /** Go's math.NaN(), the NaN of the canonical form. */
    static final DoubleNumber NAN = new DoubleNumber(0x7FF8000000000001L);

    /** What Go's float32(math.NaN()) widens to. */
    static final DoubleNumber FLOAT_NAN = new DoubleNumber(0x7FF8000000000000L);

    final long bits;

    DoubleNumber(long bits) {
        this.bits = bits;
    }

    static DoubleNumber of(double d) {
        return d != d ? NAN : new DoubleNumber(Double.doubleToRawLongBits(d));
    }

    @Override
    public NumberType type() {
        return NumberType.DOUBLE;
    }

    @Override
    public boolean isNaN() {
        final double d = doubleValue();
        return d != d;
    }

    @Override
    public long longValue() {
        return (long) doubleValue(); // NaN is 0; out of range saturates
    }

    @Override
    public double doubleValue() {
        return Double.longBitsToDouble(bits);
    }

    @Override
    public BigInteger bigIntegerValue() {
        final double d = doubleValue();
        if (d != d) {
            return BigInteger.ZERO;
        }
        if (Double.isInfinite(d)) {
            throw new ArithmeticException("infinite double has no integer");
        }
        return new BigDecimal(d).toBigInteger();
    }

    @Override
    public BigDecimal bigDecimalValue() {
        final double d = doubleValue();
        if (d != d) {
            return BigDecimal.ZERO;
        }
        if (Double.isInfinite(d)) {
            throw new ArithmeticException("infinite double has no decimal");
        }
        return DoubleFormat.shortestDecimal(d);
    }

    @Override
    public NumberValue add(NumberValue other) {
        return of(doubleValue() + other.doubleValue());
    }

    @Override
    public NumberValue subtract(NumberValue other) {
        return of(doubleValue() - other.doubleValue());
    }

    @Override
    void pack(Packer p) {
        p.float64(bits);
    }

    @Override
    void jsonify(StringBuilder out) {
        final double d = doubleValue();
        out.append(d != d ? "null" : DoubleFormat.plain(d));
    }

    @Override
    public String toString() {
        return DoubleFormat.plain(doubleValue());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DoubleNumber && ((DoubleNumber) o).bits == bits;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bits) ^ 0x44424C;
    }
}
