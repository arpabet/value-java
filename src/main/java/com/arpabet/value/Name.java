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
 * The name of a field in the map a {@link ValueCodec} writes and reads: the
 * stable, canonical key of the field on the wire and in what is signed. A
 * field without it goes by its Java name, which an obfuscator changes, so
 * durable and signed schemas name every field. It is the {@code value:"name"}
 * tag of the Go implementation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Name {
    /** The key of the field in the map. */
    String value();
}
