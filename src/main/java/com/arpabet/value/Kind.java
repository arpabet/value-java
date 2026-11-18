/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/** The kind of a {@link Value}. */
public enum Kind {
    /** {@link NullValue}. */
    NULL,
    /** {@link BoolValue}. */
    BOOL,
    /** {@link NumberValue}: a long, a double, a big integer or a decimal. */
    NUMBER,
    /** {@link StringValue}: text or binary. */
    STRING,
    /** {@link ListValue}: dense or sparse. */
    LIST,
    /** {@link MapValue}. */
    MAP,
    /** {@link ExtensionValue}: a MessagePack extension the format does not define. */
    UNKNOWN,
    /** {@link TimestampValue}. */
    TIMESTAMP
}
