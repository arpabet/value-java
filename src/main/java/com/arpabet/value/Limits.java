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
 * <p>The limits also carry the one switch decoding has,
 * {@link #withLegacyBigNumbers}, for bytes written before big numbers got
 * their portable layout.
 *
 * <p>Instances are immutable; {@link #DEFAULT} applies wherever no limits are
 * passed.
 */
public final class Limits {

    /** Depth 1000, 16,777,216 elements, 1 GiB, decimal exponent 1,048,576. */
    public static final Limits DEFAULT = new Limits(1000, 1 << 24, 1 << 30, 1 << 20, false);

    private final int maxDepth;
    private final int maxCollectionLength;
    private final int maxByteLength;
    private final int maxDecimalExponent;
    private final boolean legacyBigNumbers;

    private Limits(int maxDepth, int maxCollectionLength, int maxByteLength, int maxDecimalExponent,
                   boolean legacyBigNumbers) {
        this.maxDepth = Math.max(0, maxDepth);
        this.maxCollectionLength = Math.max(0, maxCollectionLength);
        this.maxByteLength = Math.max(0, maxByteLength);
        this.maxDecimalExponent = Math.max(0, maxDecimalExponent);
        this.legacyBigNumbers = legacyBigNumbers;
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
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent, legacyBigNumbers);
    }

    public Limits withMaxCollectionLength(int maxCollectionLength) {
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent, legacyBigNumbers);
    }

    public Limits withMaxByteLength(int maxByteLength) {
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent, legacyBigNumbers);
    }

    public Limits withMaxDecimalExponent(int maxDecimalExponent) {
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent, legacyBigNumbers);
    }

    /**
     * Whether big integers and decimals are read in the layout the Go
     * implementation wrote before its v1.5.0; see {@link #withLegacyBigNumbers}.
     */
    public boolean legacyBigNumbers() {
        return legacyBigNumbers;
    }

    /**
     * The limits with the layout of big numbers chosen. Before v1.5.0 of the
     * Go implementation the payload of a big integer (extension 1) was Go's
     * gob encoding of it, a byte of version and sign followed by the
     * magnitude, and that of a decimal (extension 2) a 4-byte exponent
     * followed by that encoding of the coefficient. Since then they are the
     * two's-complement layouts every implementation reads. The two cannot be
     * told apart from the bytes, so a decoder never guesses: it reads the old
     * layout when these limits say so, and only then. This is the Go
     * implementation's {@code LegacyBigNumbers}.
     *
     * <p>Packing always writes the layout in use now, so migrating stored
     * bytes is reading them with these limits and packing what was read:
     *
     * <pre>{@code
     * Limits old = Limits.DEFAULT.withLegacyBigNumbers(true);
     * byte[] migrated = Value.unpack(stored, old).pack();
     * }</pre>
     */
    public Limits withLegacyBigNumbers(boolean legacyBigNumbers) {
        return new Limits(maxDepth, maxCollectionLength, maxByteLength, maxDecimalExponent, legacyBigNumbers);
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
                && maxByteLength == l.maxByteLength && maxDecimalExponent == l.maxDecimalExponent
                && legacyBigNumbers == l.legacyBigNumbers;
    }

    @Override
    public int hashCode() {
        return (((maxDepth * 31 + maxCollectionLength) * 31 + maxByteLength) * 31 + maxDecimalExponent) * 31
                + (legacyBigNumbers ? 1 : 0);
    }

    @Override
    public String toString() {
        return "Limits{maxDepth=" + maxDepth + ", maxCollectionLength=" + maxCollectionLength
                + ", maxByteLength=" + maxByteLength + ", maxDecimalExponent=" + maxDecimalExponent
                + (legacyBigNumbers ? ", legacyBigNumbers" : "") + "}";
    }
}
