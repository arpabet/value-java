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
 * The signing selectors a field belongs to: {@link ValueCodec#signBytes} of a
 * selector packs the map of exactly the fields that carry it. A field may
 * carry several, to be part of independent signatures. It is the {@code
 * sign:"selector"} tag of the Go implementation, and does not change what
 * {@link ValueCodec#marshal} writes.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Sign {
    /** The selectors. */
    String[] value();
}
