/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/**
 * Bounds that guard decoding against hostile or corrupt input. The defaults
 * are those of the Go implementation: generous for normal use and finite, so
 * that a small crafted message cannot exhaust memory or the stack. A limit of
 * 0 disables its check.
 *
 * <p>Instances are immutable; {@link #DEFAULT} applies wherever no limits are
 * passed.
 */
public final class Limits {

    /** Depth 1000, 16,777,216 elements, 1 GiB, decimal exponent 1,048,576. */
    public static final Limits DEFAULT = new Limits(1000, 1 << 24, 1 << 30, 1 << 20);

    private final int maxDepth;
    private final int maxCollectionLength;
    private final int maxByteLength;
    private final int maxDecimalExponent;

    private Limits(int maxDepth, int maxCollectionLength, int maxByteLength, int maxDecimalExponent) {
        this.maxDepth = Math.max(0, maxDepth);
        this.maxCollectionLength = Math.max(0, maxCollectionLength);
        this.maxByteLength = Math.max(0, maxByteLength);
        this.maxDecimalExponent = Math.max(0, maxDecimalExponent);
    }

    /** The nesting depth of a decoded or encoded value. */
    public int maxDepth() {
        return maxDepth;
    }

    /**
     * The number of elements of one decoded list or map; also the largest index
     * a decoded sparse list may have.
     */
    public int maxCollectionLength() {
        return maxCollectionLength;
    }

    /** The length of one decoded string, binary or extension. */
    public int maxByteLength() {
        return maxByteLength;
    }

    /**
     * The absolute base-10 exponent of a decoded decimal, and the number of
     * characters of a number in JSON. A huge exponent is cheap to encode and
     * makes later arithmetic materialize an enormous integer.
     */
    public int maxDecimalExponent() {
        return maxDecimalExponent;
    }

    public Limits withMaxDepth(int maxDepth) {
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent);
    }

    public Limits withMaxCollectionLength(int maxCollectionLength) {
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent);
    }

    public Limits withMaxByteLength(int maxByteLength) {
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent);
    }

    public Limits withMaxDecimalExponent(int maxDecimalExponent) {
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent);
    }

    void checkDepth(int depth) {
        if (maxDepth > 0 && depth > maxDepth) {
            throw new ValueException("max depth " + maxDepth + " exceeded");
        }
    }

    /** Checks a count and returns it as an int. */
    int checkCollectionLength(long count) {
        if (count < 0) {
            throw new ValueException("negative collection length " + count);
        }
        if (maxCollectionLength > 0 && count > maxCollectionLength) {
            throw new ValueException("collection length " + count + " exceeds limit " + maxCollectionLength);
        }
        if (count > Integer.MAX_VALUE - 8) {
            throw new ValueException("collection length " + count + " is too large");
        }
        return (int) count;
    }

    /** Checks a byte length and returns it as an int. */
    int checkByteLength(long n) {
        if (n < 0) {
            throw new ValueException("negative byte length " + n);
        }
        if (maxByteLength > 0 && n > maxByteLength) {
            throw new ValueException("byte length " + n + " exceeds limit " + maxByteLength);
        }
        if (n > Integer.MAX_VALUE - 8) {
            throw new ValueException("byte length " + n + " is too large");
        }
        return (int) n;
    }

    void checkDecimalExponent(long exponent) {
        if (maxDecimalExponent > 0 && (exponent > maxDecimalExponent || exponent < -(long) maxDecimalExponent)) {
            throw new ValueException("decimal exponent " + exponent + " exceeds limit " + maxDecimalExponent);
        }
    }

    /** Reports whether a decoded integer map key is the index of a sparse list. */
    boolean isListIndex(long key) {
        return key >= 0 && key <= (maxCollectionLength > 0 ? maxCollectionLength : Integer.MAX_VALUE - 8);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Limits)) {
            return false;
        }
        final Limits l = (Limits) o;
        return maxDepth == l.maxDepth && maxCollectionLength == l.maxCollectionLength
                && maxByteLength == l.maxByteLength && maxDecimalExponent == l.maxDecimalExponent;
    }

    @Override
    public int hashCode() {
        return ((maxDepth * 31 + maxCollectionLength) * 31 + maxByteLength) * 31 + maxDecimalExponent;
    }

    @Override
    public String toString() {
        return "Limits{maxDepth=" + maxDepth + ", maxCollectionLength=" + maxCollectionLength
                + ", maxByteLength=" + maxByteLength + ", maxDecimalExponent=" + maxDecimalExponent + "}";
    }
}
