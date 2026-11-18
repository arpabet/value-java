/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/** The type of a {@link StringValue}. */
public enum StringType {
    /** Text: MessagePack's str family. */
    UTF8,
    /** Binary: MessagePack's bin family. */
    RAW
}
