/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an integer field (or a field of an array, list or map of integers) as
 * holding unsigned numbers, as a Go {@code uint8} to {@code uint64} field
 * does: its bits are the unsigned integer. It is written as that number, a
 * big integer where a {@code long} holds one above 2^63 - 1, and read back
 * from it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Unsigned {
}
