/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/**
 * The Poly1305 one-time authenticator: a 16-byte tag of a message under a
 * 32-byte key that authenticates one message only.
 *
 * <p>The accumulator and the multiplier are five limbs of 26 bits, kept in
 * longs so that a product and the sum of five of them fit.
 */
final class Poly1305 {

    static final int TAG = 16;

    private static final long MASK = 0x3ffffff;

    private Poly1305() {
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8) | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
    }

    /** Writes the tag of {@code len} bytes of {@code m} at {@code off} to {@code out} at {@code outOff}. */
    static void mac(byte[] out, int outOff, byte[] m, int off, int len, byte[] key) {
        // The multiplier r is the first half of the key with 22 bits cleared.
        final long r0 = le32(key, 0) & 0x3ffffff;
        final long r1 = (le32(key, 3) >>> 2) & 0x3ffff03;
        final long r2 = (le32(key, 6) >>> 4) & 0x3ffc0ff;
        final long r3 = (le32(key, 9) >>> 6) & 0x3f03fff;
        final long r4 = (le32(key, 12) >>> 8) & 0x00fffff;
        final long s1 = r1 * 5;
        final long s2 = r2 * 5;
        final long s3 = r3 * 5;
        final long s4 = r4 * 5;

        long h0 = 0;
        long h1 = 0;
        long h2 = 0;
        long h3 = 0;
        long h4 = 0;

        byte[] last = null;
        while (len > 0) {
            final byte[] b;
            final int at;
            final long high;
            if (len >= 16) {
                b = m;
                at = off;
                high = 1L << 24; // the bit above the 16 bytes of a whole block
                off += 16;
                len -= 16;
            } else {
                // What is left, a 1 byte after it, and zeros.
                last = new byte[16];
                System.arraycopy(m, off, last, 0, len);
                last[len] = 1;
                b = last;
                at = 0;
                high = 0;
                len = 0;
            }
            h0 += le32(b, at) & MASK;
            h1 += (le32(b, at + 3) >>> 2) & MASK;
            h2 += (le32(b, at + 6) >>> 4) & MASK;
            h3 += (le32(b, at + 9) >>> 6) & MASK;
            h4 += (le32(b, at + 12) >>> 8) | high;

            // h *= r, modulo 2^130 - 5: a product that reaches 2^130 comes back times 5.
            final long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;

            long c = d0 >>> 26;
            h0 = d0 & MASK;
            d1 += c;
            c = d1 >>> 26;
            h1 = d1 & MASK;
            d2 += c;
            c = d2 >>> 26;
            h2 = d2 & MASK;
            d3 += c;
            c = d3 >>> 26;
            h3 = d3 & MASK;
            d4 += c;
            c = d4 >>> 26;
            h4 = d4 & MASK;
            h0 += c * 5;
            c = h0 >>> 26;
            h0 &= MASK;
            h1 += c;
        }
        if (last != null) {
            java.util.Arrays.fill(last, (byte) 0);
        }

        // Carry h through.
        long c = h1 >>> 26;
        h1 &= MASK;
        h2 += c;
        c = h2 >>> 26;
        h2 &= MASK;
        h3 += c;
        c = h3 >>> 26;
        h3 &= MASK;
        h4 += c;
        c = h4 >>> 26;
        h4 &= MASK;
        h0 += c * 5;
        c = h0 >>> 26;
        h0 &= MASK;
        h1 += c;

        // g = h - (2^130 - 5), which is h + 5 - 2^130.
        long g0 = h0 + 5;
        c = g0 >>> 26;
        g0 &= MASK;
        long g1 = h1 + c;
        c = g1 >>> 26;
        g1 &= MASK;
        long g2 = h2 + c;
        c = g2 >>> 26;
        g2 &= MASK;
        long g3 = h3 + c;
        c = g3 >>> 26;
        g3 &= MASK;
        final long g4 = h4 + c - (1L << 26);

        // Take g when it is not negative, which is when h was not below the modulus.
        final long take = (g4 >>> 63) - 1;
        final long keep = ~take;
        h0 = (h0 & keep) | (g0 & take);
        h1 = (h1 & keep) | (g1 & take);
        h2 = (h2 & keep) | (g2 & take);
        h3 = (h3 & keep) | (g3 & take);
        h4 = (h4 & keep) | (g4 & take);

        // The low 128 bits of h, plus the second half of the key.
        long f = ((h0 | (h1 << 26)) & 0xffffffffL) + le32(key, 16);
        put(out, outOff, f);
        f = (((h1 >>> 6) | (h2 << 20)) & 0xffffffffL) + le32(key, 20) + (f >>> 32);
        put(out, outOff + 4, f);
        f = (((h2 >>> 12) | (h3 << 14)) & 0xffffffffL) + le32(key, 24) + (f >>> 32);
        put(out, outOff + 8, f);
        f = (((h3 >>> 18) | (h4 << 8)) & 0xffffffffL) + le32(key, 28) + (f >>> 32);
        put(out, outOff + 12, f);
    }

    private static void put(byte[] out, int at, long v) {
        out[at] = (byte) v;
        out[at + 1] = (byte) (v >>> 8);
        out[at + 2] = (byte) (v >>> 16);
        out[at + 3] = (byte) (v >>> 24);
    }

    /**
     * Reports whether the 16 bytes of {@code tag} at {@code tagOff} are the
     * tag of the message, in a time that does not depend on where they differ.
     */
    static boolean verify(byte[] tag, int tagOff, byte[] m, int off, int len, byte[] key) {
        final byte[] want = new byte[TAG];
        mac(want, 0, m, off, len, key);
        int diff = 0;
        for (int i = 0; i < TAG; i++) {
            diff |= want[i] ^ tag[tagOff + i];
        }
        return diff == 0;
    }
}
