/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/** A 64-bit integer. */
final class LongNumber extends NumberValue {

    private static final LongNumber[] SMALL = new LongNumber[256];

    static {
        for (int i = 0; i < SMALL.length; i++) {
            SMALL[i] = new LongNumber(i - 128);
        }
    }

    final long value;

    private LongNumber(long value) {
        this.value = value;
    }

    static LongNumber of(long v) {
        return v >= -128 && v <= 127 ? SMALL[(int) v + 128] : new LongNumber(v);
    }

    @Override
    public NumberType type() {
        return NumberType.LONG;
    }

    @Override
    public boolean isNaN() {
        return false;
    }

    @Override
    public long longValue() {
        return value;
    }

    @Override
    public double doubleValue() {
        return (double) value;
    }

    @Override
    public BigInteger bigIntegerValue() {
        return BigInteger.valueOf(value);
    }

    @Override
    public BigDecimal bigDecimalValue() {
        return BigDecimal.valueOf(value);
    }

    @Override
    public NumberValue add(NumberValue other) {
        return of(value + other.longValue());
    }

    @Override
    public NumberValue subtract(NumberValue other) {
        return of(value - other.longValue());
    }

    @Override
    void pack(Packer p) {
        p.integer(value);
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LongNumber && ((LongNumber) o).value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }
}
