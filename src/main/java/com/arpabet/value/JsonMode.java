/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/** How {@link Value#toJson(JsonMode)} writes the kinds plain JSON lacks. */
public enum JsonMode {
    /**
     * Longs, finite doubles and decimals are JSON numbers with their exact
     * digits; binary, timestamps, big integers, sparse lists and unknown
     * extensions are wrapped. Reading it back gives the numbers as decimals.
     */
    RELAXED,
    /**
     * Every kind plain JSON lacks is wrapped, numbers included, so that
     * {@link Value#parseJson(String)} reads back the same value. The text is a
     * function of the value.
     */
    CANONICAL
}
