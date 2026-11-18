/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.time.Instant;

/**
 * Timestamps as {@link Instant}, which holds the same thing: seconds since the
 * Unix epoch and nanoseconds.
 *
 * <p>This is the one class of the library that uses {@code java.time}, which
 * Android has from API 26 (or with core library desugaring). Nothing else
 * refers to it, so an application that does not call it, and has no
 * {@code Instant} field for a {@link ValueCodec} to map, runs on API 24.
 */
public final class JavaTime {

    private JavaTime() {
    }

    /** The timestamp of an instant. */
    public static TimestampValue timestamp(Instant instant) {
        return Value.timestamp(instant.getEpochSecond(), instant.getNano());
    }

    /**
     * The instant of a timestamp.
     *
     * @throws java.time.DateTimeException when it is beyond the range of an {@code Instant}
     */
    public static Instant toInstant(TimestampValue timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }

    static Value fromObject(Object instant) {
        return timestamp((Instant) instant);
    }

    static Object toObject(TimestampValue timestamp) {
        return toInstant(timestamp);
    }

    static Object ofEpochMilli(long millis) {
        return Instant.ofEpochMilli(millis);
    }
}
