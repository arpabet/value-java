/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/**
 * X25519, the Diffie-Hellman function of RFC 7748 on Curve25519.
 *
 * <p>An element of the field of integers modulo 2^255 - 19 is ten limbs in a
 * {@code long[]}: limb {@code i} weighs 2^ceil(25.5 i), so the limbs are 26
 * and 25 bits wide in turn. A limb may be negative and wider than its width;
 * what the arithmetic needs is that the limbs of the operands of a
 * multiplication are below 2^27.4 in magnitude, which holds for a sum or a
 * difference of two products, the only operands the ladder has.
 *
 * <p>Nothing here branches on, or indexes by, the scalar or a coordinate.
 */
final class Curve25519 {

    static final int LENGTH = 32;

    private static final int MASK26 = (1 << 26) - 1;
    private static final int MASK25 = (1 << 25) - 1;

    private Curve25519() {
    }

    /**
     * The u-coordinate of {@code scalar} times the point of u-coordinate
     * {@code point}, both 32 bytes; the scalar is clamped as the RFC tells.
     *
     * @return whether the result is other than zero; zero is what a point of
     *         small order gives whatever the scalar, so it is no secret
     */
    static boolean scalarMult(byte[] out, byte[] scalar, byte[] point) {
        final byte[] e = scalar.clone();
        e[0] &= (byte) 248;
        e[31] &= 127;
        e[31] |= 64;

        final long[] x1 = new long[10];
        fromBytes(x1, point);
        final long[] x2 = new long[10];
        final long[] z2 = new long[10];
        final long[] x3 = x1.clone();
        final long[] z3 = new long[10];
        x2[0] = 1;
        z3[0] = 1;

        final long[] a = new long[10];
        final long[] aa = new long[10];
        final long[] b = new long[10];
        final long[] bb = new long[10];
        final long[] c = new long[10];
        final long[] d = new long[10];
        final long[] da = new long[10];
        final long[] cb = new long[10];
        final long[] t = new long[10];

        long swap = 0;
        for (int i = 254; i >= 0; i--) {
            final long bit = ((e[i >>> 3] & 0xFF) >>> (i & 7)) & 1;
            swap ^= bit;
            cswap(x2, x3, swap);
            cswap(z2, z3, swap);
            swap = bit;

            add(a, x2, z2);
            sq(aa, a);
            sub(b, x2, z2);
            sq(bb, b);
            add(c, x3, z3);
            sub(d, x3, z3);
            mul(da, d, a);
            mul(cb, c, b);
            add(t, da, cb);
            sq(x3, t);
            sub(t, da, cb);
            sq(t, t);
            mul(z3, x1, t);
            mul(x2, aa, bb);
            sub(b, aa, bb); // E
            mul121665(t, b);
            add(t, aa, t);
            mul(z2, b, t);
        }
        cswap(x2, x3, swap);
        cswap(z2, z3, swap);

        invert(z2, z2);
        mul(x2, x2, z2);
        toBytes(out, x2);

        java.util.Arrays.fill(e, (byte) 0);
        int any = 0;
        for (int i = 0; i < LENGTH; i++) {
            any |= out[i];
        }
        return any != 0;
    }

    /** The public key of a private key: the scalar times the base point, whose u-coordinate is 9. */
    static void scalarBase(byte[] out, byte[] scalar) {
        final byte[] base = new byte[LENGTH];
        base[0] = 9;
        scalarMult(out, scalar, base);
    }

    // --- the field -----------------------------------------------------------

    /** Reads the low 255 bits of 32 little-endian bytes; the top bit is not part of a coordinate. */
    static void fromBytes(long[] h, byte[] s) {
        long acc = 0;
        int bits = 0;
        int at = 0;
        for (int i = 0; i < 10; i++) {
            final int width = 26 - (i & 1);
            while (bits < width) {
                acc |= (long) (s[at++] & 0xFF) << bits;
                bits += 8;
            }
            h[i] = acc & ((1L << width) - 1);
            acc >>>= width;
            bits -= width;
        }
    }

    /** Writes the one representative below 2^255 - 19 as 32 little-endian bytes. */
    static void toBytes(byte[] out, long[] h) {
        final long[] t = h.clone();
        // Three rounds leave every limb within its width, whatever the sign
        // the element came with: a round takes the multiple of 2^255 above the
        // limbs, or the one below when they are negative, and puts back 19
        // times as much, which is the same number modulo 2^255 - 19.
        carry(t);
        carry(t);
        carry(t);
        // Now 0 <= t < 2^255, and t - (2^255 - 19) is the answer when it is
        // not negative: that is when adding 19 carries out of the top limb.
        final long[] u = t.clone();
        u[0] += 19;
        long c = 0;
        for (int i = 0; i < 10; i++) {
            final int width = 26 - (i & 1);
            u[i] += c;
            c = u[i] >> width;
            u[i] -= c << width;
        }
        final long mask = -c;
        for (int i = 0; i < 10; i++) {
            t[i] ^= mask & (t[i] ^ u[i]);
        }
        long acc = 0;
        int bits = 0;
        int at = 0;
        for (int i = 0; i < 10; i++) {
            acc |= t[i] << bits;
            bits += 26 - (i & 1);
            while (bits >= 8) {
                out[at++] = (byte) acc;
                acc >>>= 8;
                bits -= 8;
            }
        }
        out[at] = (byte) acc;
    }

    static void add(long[] h, long[] f, long[] g) {
        for (int i = 0; i < 10; i++) {
            h[i] = f[i] + g[i];
        }
    }

    static void sub(long[] h, long[] f, long[] g) {
        for (int i = 0; i < 10; i++) {
            h[i] = f[i] - g[i];
        }
    }

    /** Exchanges two elements when {@code bit} is 1 and leaves them when it is 0, in the same time. */
    private static void cswap(long[] f, long[] g, long bit) {
        final long mask = -bit;
        for (int i = 0; i < 10; i++) {
            final long x = mask & (f[i] ^ g[i]);
            f[i] ^= x;
            g[i] ^= x;
        }
    }

    static void carry(long[] h) {
        carry(h, h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], h[8], h[9]);
    }

    /**
     * Stores the element with the limbs h0 to h9, each brought within its
     * width; the second limb alone may end a little outside, by the carry of
     * what the top limb gave back to the first.
     */
    private static void carry(long[] h, long h0, long h1, long h2, long h3, long h4,
                              long h5, long h6, long h7, long h8, long h9) {
        long c;
        c = h0 >> 26;
        h1 += c;
        h0 -= c << 26;
        c = h1 >> 25;
        h2 += c;
        h1 -= c << 25;
        c = h2 >> 26;
        h3 += c;
        h2 -= c << 26;
        c = h3 >> 25;
        h4 += c;
        h3 -= c << 25;
        c = h4 >> 26;
        h5 += c;
        h4 -= c << 26;
        c = h5 >> 25;
        h6 += c;
        h5 -= c << 25;
        c = h6 >> 26;
        h7 += c;
        h6 -= c << 26;
        c = h7 >> 25;
        h8 += c;
        h7 -= c << 25;
        c = h8 >> 26;
        h9 += c;
        h8 -= c << 26;
        c = h9 >> 25;
        h0 += 19 * c; // 2^255 is 19
        h9 -= c << 25;
        c = h0 >> 26;
        h1 += c;
        h0 -= c << 26;
        h[0] = h0;
        h[1] = h1;
        h[2] = h2;
        h[3] = h3;
        h[4] = h4;
        h[5] = h5;
        h[6] = h6;
        h[7] = h7;
        h[8] = h8;
        h[9] = h9;
    }

    /**
     * h = f g. The product of limb i and limb j belongs to limb i + j, twice
     * when both are odd (their weights are half a bit above 2^25.5 each), and
     * to limb i + j - 10 times 19 when i + j passes the tenth.
     */
    static void mul(long[] h, long[] f, long[] g) {
        final long f0 = f[0];
        final long f1 = f[1];
        final long f2 = f[2];
        final long f3 = f[3];
        final long f4 = f[4];
        final long f5 = f[5];
        final long f6 = f[6];
        final long f7 = f[7];
        final long f8 = f[8];
        final long f9 = f[9];
        final long g0 = g[0];
        final long g1 = g[1];
        final long g2 = g[2];
        final long g3 = g[3];
        final long g4 = g[4];
        final long g5 = g[5];
        final long g6 = g[6];
        final long g7 = g[7];
        final long g8 = g[8];
        final long g9 = g[9];
        final long g1_19 = 19 * g1;
        final long g2_19 = 19 * g2;
        final long g3_19 = 19 * g3;
        final long g4_19 = 19 * g4;
        final long g5_19 = 19 * g5;
        final long g6_19 = 19 * g6;
        final long g7_19 = 19 * g7;
        final long g8_19 = 19 * g8;
        final long g9_19 = 19 * g9;
        final long f1_2 = 2 * f1;
        final long f3_2 = 2 * f3;
        final long f5_2 = 2 * f5;
        final long f7_2 = 2 * f7;
        final long f9_2 = 2 * f9;
        final long h0 = f0 * g0 + f1_2 * g9_19 + f2 * g8_19 + f3_2 * g7_19 + f4 * g6_19 + f5_2 * g5_19 + f6 * g4_19 + f7_2 * g3_19 + f8 * g2_19 + f9_2 * g1_19;
        final long h1 = f0 * g1 + f1 * g0 + f2 * g9_19 + f3 * g8_19 + f4 * g7_19 + f5 * g6_19 + f6 * g5_19 + f7 * g4_19 + f8 * g3_19 + f9 * g2_19;
        final long h2 = f0 * g2 + f1_2 * g1 + f2 * g0 + f3_2 * g9_19 + f4 * g8_19 + f5_2 * g7_19 + f6 * g6_19 + f7_2 * g5_19 + f8 * g4_19 + f9_2 * g3_19;
        final long h3 = f0 * g3 + f1 * g2 + f2 * g1 + f3 * g0 + f4 * g9_19 + f5 * g8_19 + f6 * g7_19 + f7 * g6_19 + f8 * g5_19 + f9 * g4_19;
        final long h4 = f0 * g4 + f1_2 * g3 + f2 * g2 + f3_2 * g1 + f4 * g0 + f5_2 * g9_19 + f6 * g8_19 + f7_2 * g7_19 + f8 * g6_19 + f9_2 * g5_19;
        final long h5 = f0 * g5 + f1 * g4 + f2 * g3 + f3 * g2 + f4 * g1 + f5 * g0 + f6 * g9_19 + f7 * g8_19 + f8 * g7_19 + f9 * g6_19;
        final long h6 = f0 * g6 + f1_2 * g5 + f2 * g4 + f3_2 * g3 + f4 * g2 + f5_2 * g1 + f6 * g0 + f7_2 * g9_19 + f8 * g8_19 + f9_2 * g7_19;
        final long h7 = f0 * g7 + f1 * g6 + f2 * g5 + f3 * g4 + f4 * g3 + f5 * g2 + f6 * g1 + f7 * g0 + f8 * g9_19 + f9 * g8_19;
        final long h8 = f0 * g8 + f1_2 * g7 + f2 * g6 + f3_2 * g5 + f4 * g4 + f5_2 * g3 + f6 * g2 + f7_2 * g1 + f8 * g0 + f9_2 * g9_19;
        final long h9 = f0 * g9 + f1 * g8 + f2 * g7 + f3 * g6 + f4 * g5 + f5 * g4 + f6 * g3 + f7 * g2 + f8 * g1 + f9 * g0;
        carry(h, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9);
    }

    /** h = f f, with the products that come twice taken once. */
    static void sq(long[] h, long[] f) {
        final long f0 = f[0];
        final long f1 = f[1];
        final long f2 = f[2];
        final long f3 = f[3];
        final long f4 = f[4];
        final long f5 = f[5];
        final long f6 = f[6];
        final long f7 = f[7];
        final long f8 = f[8];
        final long f9 = f[9];
        final long f0_2 = 2 * f0;
        final long f1_2 = 2 * f1;
        final long f2_2 = 2 * f2;
        final long f3_2 = 2 * f3;
        final long f4_2 = 2 * f4;
        final long f5_2 = 2 * f5;
        final long f5_38 = 38 * f5;
        final long f6_19 = 19 * f6;
        final long f6_2 = 2 * f6;
        final long f7_19 = 19 * f7;
        final long f7_2 = 2 * f7;
        final long f7_38 = 38 * f7;
        final long f8_19 = 19 * f8;
        final long f8_2 = 2 * f8;
        final long f9_19 = 19 * f9;
        final long f9_38 = 38 * f9;
        final long h0 = f0 * f0 + f1_2 * f9_38 + f2_2 * f8_19 + f3_2 * f7_38 + f4_2 * f6_19 + f5 * f5_38;
        final long h1 = f0_2 * f1 + f2_2 * f9_19 + f3_2 * f8_19 + f4_2 * f7_19 + f5_2 * f6_19;
        final long h2 = f0_2 * f2 + f1 * f1_2 + f3_2 * f9_38 + f4_2 * f8_19 + f5_2 * f7_38 + f6 * f6_19;
        final long h3 = f0_2 * f3 + f1_2 * f2 + f4_2 * f9_19 + f5_2 * f8_19 + f6_2 * f7_19;
        final long h4 = f0_2 * f4 + f1_2 * f3_2 + f2 * f2 + f5_2 * f9_38 + f6_2 * f8_19 + f7 * f7_38;
        final long h5 = f0_2 * f5 + f1_2 * f4 + f2_2 * f3 + f6_2 * f9_19 + f7_2 * f8_19;
        final long h6 = f0_2 * f6 + f1_2 * f5_2 + f2_2 * f4 + f3 * f3_2 + f7_2 * f9_38 + f8 * f8_19;
        final long h7 = f0_2 * f7 + f1_2 * f6 + f2_2 * f5 + f3_2 * f4 + f8_2 * f9_19;
        final long h8 = f0_2 * f8 + f1_2 * f7_2 + f2_2 * f6 + f3_2 * f5_2 + f4 * f4 + f9 * f9_38;
        final long h9 = f0_2 * f9 + f1_2 * f8 + f2_2 * f7 + f3_2 * f6 + f4_2 * f5;
        carry(h, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9);
    }

    /** h = 121665 f, the constant (486662 - 2) / 4 of the curve. */
    private static void mul121665(long[] h, long[] f) {
        carry(h, f[0] * 121665, f[1] * 121665, f[2] * 121665, f[3] * 121665, f[4] * 121665,
                f[5] * 121665, f[6] * 121665, f[7] * 121665, f[8] * 121665, f[9] * 121665);
    }

    private static void sq(long[] h, long[] f, int times) {
        sq(h, f);
        for (int i = 1; i < times; i++) {
            sq(h, h);
        }
    }

    /** out = z^(2^255 - 21), which is 1 / z; 0 stays 0. */
    static void invert(long[] out, long[] z) {
        final long[] t0 = new long[10];
        final long[] t1 = new long[10];
        final long[] t2 = new long[10];
        final long[] t3 = new long[10];
        sq(t0, z);              // 2
        sq(t1, t0, 2);          // 8
        mul(t1, z, t1);         // 9
        mul(t0, t0, t1);        // 11
        sq(t2, t0);             // 22
        mul(t1, t1, t2);        // 31 = 2^5 - 1
        sq(t2, t1, 5);
        mul(t1, t2, t1);        // 2^10 - 1
        sq(t2, t1, 10);
        mul(t2, t2, t1);        // 2^20 - 1
        sq(t3, t2, 20);
        mul(t2, t3, t2);        // 2^40 - 1
        sq(t2, t2, 10);
        mul(t1, t2, t1);        // 2^50 - 1
        sq(t2, t1, 50);
        mul(t2, t2, t1);        // 2^100 - 1
        sq(t3, t2, 100);
        mul(t2, t3, t2);        // 2^200 - 1
        sq(t2, t2, 50);
        mul(t1, t2, t1);        // 2^250 - 1
        sq(t1, t1, 5);          // 2^255 - 32
        mul(out, t1, t0);       // 2^255 - 21
    }
}
