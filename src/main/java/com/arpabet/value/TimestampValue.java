/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Date;

/**
 * An instant: seconds since the Unix epoch and nanoseconds within that
 * second. It has no zone, so the same instant is one value with one encoding
 * wherever it was observed.
 *
 * <p>It encodes as MessagePack's timestamp extension (type -1) in the smallest
 * of its three forms: 32 bits when there are no nanoseconds and the seconds
 * fit an unsigned 32-bit integer, 64 bits when the seconds fit 34 bits, and 96
 * bits otherwise.
 */
public final class TimestampValue extends Value {

    private static final long NANOS_PER_SECOND = 1000000000L;
    private static final long MAX_SECONDS_32 = (1L << 32) - 1;
    private static final long MAX_SECONDS_34 = (1L << 34) - 1;

    private final long seconds;
    private final int nanos;

    TimestampValue(long seconds, int nanos) {
        this.seconds = seconds;
        this.nanos = nanos;
    }

    static TimestampValue of(long seconds, long nanos) {
        if (nanos < 0 || nanos >= NANOS_PER_SECOND) {
            final long n = nanos / NANOS_PER_SECOND;
            seconds += n;
            nanos -= n * NANOS_PER_SECOND;
            if (nanos < 0) {
                nanos += NANOS_PER_SECOND;
                seconds--;
            }
        }
        return new TimestampValue(seconds, (int) nanos);
    }

    /** Reads the payload of a timestamp extension in any of its three forms. */
    static TimestampValue ofPayload(byte[] data) {
        final long seconds;
        final long nanos;
        switch (data.length) {
            case 4:
                seconds = uint32(data, 0);
                nanos = 0;
                break;
            case 8: {
                final long v = (uint32(data, 0) << 32) | uint32(data, 4);
                nanos = v >>> 34;
                seconds = v & MAX_SECONDS_34;
                break;
            }
            case 12:
                nanos = uint32(data, 0);
                seconds = (uint32(data, 4) << 32) | uint32(data, 8);
                break;
            default:
                throw new ValueException("timestamp payload of " + data.length + " bytes, want 4, 8 or 12");
        }
        if (nanos >= NANOS_PER_SECOND) {
            throw new ValueException("timestamp nanoseconds " + nanos + " out of range");
        }
        return new TimestampValue(seconds, (int) nanos);
    }

    private static long uint32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16) | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    /** The seconds since the Unix epoch; negative before it. */
    public long seconds() {
        return seconds;
    }

    /** The nanoseconds within the second, from 0 to 999,999,999. */
    public int nanos() {
        return nanos;
    }

    /** The milliseconds since the Unix epoch, without the nanoseconds below a millisecond. */
    public long toEpochMilli() {
        return seconds * 1000L + nanos / 1000000;
    }

    /** The instant as a date, which keeps milliseconds. */
    public Date toDate() {
        return new Date(toEpochMilli());
    }

    @Override
    public Kind kind() {
        return Kind.TIMESTAMP;
    }

    @Override
    void pack(Packer p) {
        final byte[] payload;
        if (nanos == 0 && seconds >= 0 && seconds <= MAX_SECONDS_32) {
            payload = new byte[4];
            put32(payload, 0, seconds);
        } else if (seconds >= 0 && seconds <= MAX_SECONDS_34) {
            payload = new byte[8];
            final long v = ((long) nanos << 34) | seconds;
            put32(payload, 0, v >>> 32);
            put32(payload, 4, v);
        } else {
            payload = new byte[12];
            put32(payload, 0, nanos);
            put32(payload, 4, seconds >>> 32);
            put32(payload, 8, seconds);
        }
        p.ext(ExtensionValue.TIMESTAMP, payload);
    }

    private static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append('"').append(toString()).append('"');
    }

    /** RFC 3339 in UTC with the fraction the instant needs: {@code 2026-10-30T07:28:39.075Z}. */
    @Override
    public String toString() {
        return Rfc3339.format(seconds, nanos);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TimestampValue)) {
            return false;
        }
        final TimestampValue t = (TimestampValue) o;
        return t.seconds == seconds && t.nanos == nanos;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(seconds) * 31 + nanos;
    }
}
