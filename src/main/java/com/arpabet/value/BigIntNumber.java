/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/** An integer of any size. */
final class BigIntNumber extends NumberValue {

    final BigInteger value;

    BigIntNumber(BigInteger value) {
        this.value = value;
    }

    @Override
    public NumberType type() {
        return NumberType.BIGINT;
    }

    @Override
    public boolean isNaN() {
        return false;
    }

    @Override
    public long longValue() {
        return value.longValue();
    }

    @Override
    public double doubleValue() {
        return value.doubleValue();
    }

    @Override
    public BigInteger bigIntegerValue() {
        return value;
    }

    @Override
    public BigDecimal bigDecimalValue() {
        return new BigDecimal(value);
    }

    @Override
    public NumberValue add(NumberValue other) {
        return new BigIntNumber(value.add(other.bigIntegerValue()));
    }

    @Override
    public NumberValue subtract(NumberValue other) {
        return new BigIntNumber(value.subtract(other.bigIntegerValue()));
    }

    @Override
    void pack(Packer p) {
        // Two's complement, big-endian, in the smallest length that holds the sign.
        p.ext(ExtensionValue.BIGINT, value.toByteArray());
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append('"').append(formatBigInt(value)).append('"');
    }

    @Override
    public String toString() {
        return formatBigInt(value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BigIntNumber && ((BigIntNumber) o).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode() ^ 0x424947;
    }
}
