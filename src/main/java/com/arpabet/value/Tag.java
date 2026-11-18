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
 * The number of a field in the numeric schema of a {@link StructCodec}: the
 * key of the field on the wire, where the names of fields do not appear. A
 * number is never changed or used again once a protocol has shipped. It is
 * the {@code tag:"1"} tag of the Go implementation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Tag {
    /** The field number. */
    int value();
}
