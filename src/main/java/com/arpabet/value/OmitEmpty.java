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
 * Marks a field {@link ValueCodec#marshal} leaves out while it is empty, and
 * {@link ValueCodec#unmarshal} does not require: the {@code omitempty} option
 * of the Go implementation's {@code value} tag. Empty is null, false, zero,
 * the empty text, and an empty binary, array, list or map; an object is never
 * empty. The signing projection ignores it, so that what is signed keeps one
 * shape.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OmitEmpty {
}
