/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/**
 * The type of a {@link NumberValue}. The four types are distinct values with
 * distinct encodings: the long 1, the double 1.0, the big integer 1 and the
 * decimal 1 are never equal.
 */
public enum NumberType {
    /** A 64-bit signed integer, in the smallest MessagePack integer form. */
    LONG,
    /** A 64-bit IEEE-754 float, always MessagePack's float 64. */
    DOUBLE,
    /** An integer of any size: extension 1, two's complement. */
    BIGINT,
    /** A decimal with its exact digits: extension 2, scale and unscaled integer. */
    DECIMAL
}
