/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/** A decimal: an unscaled integer and a scale, both significant. */
final class DecimalNumber extends NumberValue {

    final BigDecimal value;

    DecimalNumber(BigDecimal value) {
        this.value = value;
    }

    @Override
    public NumberType type() {
        return NumberType.DECIMAL;
    }

    @Override
    public boolean isNaN() {
        return false;
    }

    @Override
    public long longValue() {
        return value.toBigInteger().longValue();
    }

    @Override
    public double doubleValue() {
        return value.doubleValue();
    }

    @Override
    public BigInteger bigIntegerValue() {
        return value.toBigInteger();
    }

    @Override
    public BigDecimal bigDecimalValue() {
        return value;
    }

    @Override
    public NumberValue add(NumberValue other) {
        return new DecimalNumber(value.add(other.bigDecimalValue()));
    }

    @Override
    public NumberValue subtract(NumberValue other) {
        return new DecimalNumber(value.subtract(other.bigDecimalValue()));
    }

    @Override
    void pack(Packer p) {
        // A 4-byte big-endian scale, then the two's-complement unscaled integer.
        final byte[] unscaled = value.unscaledValue().toByteArray();
        final int scale = value.scale();
        final byte[] payload = new byte[4 + unscaled.length];
        payload[0] = (byte) (scale >>> 24);
        payload[1] = (byte) (scale >>> 16);
        payload[2] = (byte) (scale >>> 8);
        payload[3] = (byte) scale;
        System.arraycopy(unscaled, 0, payload, 4, unscaled.length);
        p.ext(ExtensionValue.DECIMAL, payload);
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append('"').append(formatDecimal(value)).append('"');
    }

    @Override
    public String toString() {
        return formatDecimal(value);
    }

    @Override
    public boolean equals(Object o) {
        // BigDecimal.equals compares the unscaled integer and the scale, which
        // is the representation: 1.0 and 1.00 are different.
        return o instanceof DecimalNumber && ((DecimalNumber) o).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode() ^ 0x444543;
    }
}
