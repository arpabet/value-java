/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/**
 * Thrown when bytes or text do not hold a value, when a value exceeds a
 * {@linkplain Limits limit}, and when an object does not map to or from a
 * value.
 */
public class ValueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValueException(String message) {
        super(message);
    }

    public ValueException(String message, Throwable cause) {
        super(message, cause);
    }
}
