/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/**
 * The Salsa20 stream cipher with 20 rounds and a 256-bit key, and HSalsa20,
 * the function that derives a key from a key and 16 more bytes; XSalsa20, the
 * cipher of a NaCl box, is the one after the other.
 */
final class Salsa20 {

    static final int BLOCK = 64;

    // "expand 32-byte k"
    private static final int SIGMA0 = 0x61707865;
    private static final int SIGMA1 = 0x3320646e;
    private static final int SIGMA2 = 0x79622d32;
    private static final int SIGMA3 = 0x6b206574;

    private Salsa20() {
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | (b[at + 3] << 24);
    }

    private static void le32(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }

    /**
     * The 16 words the rounds leave, from a key of 32 bytes and 16 bytes of
     * input at {@code inOff}: a nonce and a block counter for the cipher.
     */
    private static void rounds(int[] x, byte[] key, byte[] in, int inOff) {
        int x0 = SIGMA0;
        int x1 = le32(key, 0);
        int x2 = le32(key, 4);
        int x3 = le32(key, 8);
        int x4 = le32(key, 12);
        int x5 = SIGMA1;
        int x6 = le32(in, inOff);
        int x7 = le32(in, inOff + 4);
        int x8 = le32(in, inOff + 8);
        int x9 = le32(in, inOff + 12);
        int x10 = SIGMA2;
        int x11 = le32(key, 16);
        int x12 = le32(key, 20);
        int x13 = le32(key, 24);
        int x14 = le32(key, 28);
        int x15 = SIGMA3;
        for (int i = 0; i < 20; i += 2) {
            x4 ^= Integer.rotateLeft(x0 + x12, 7);
            x8 ^= Integer.rotateLeft(x4 + x0, 9);
            x12 ^= Integer.rotateLeft(x8 + x4, 13);
            x0 ^= Integer.rotateLeft(x12 + x8, 18);

            x9 ^= Integer.rotateLeft(x5 + x1, 7);
            x13 ^= Integer.rotateLeft(x9 + x5, 9);
            x1 ^= Integer.rotateLeft(x13 + x9, 13);
            x5 ^= Integer.rotateLeft(x1 + x13, 18);

            x14 ^= Integer.rotateLeft(x10 + x6, 7);
            x2 ^= Integer.rotateLeft(x14 + x10, 9);
            x6 ^= Integer.rotateLeft(x2 + x14, 13);
            x10 ^= Integer.rotateLeft(x6 + x2, 18);

            x3 ^= Integer.rotateLeft(x15 + x11, 7);
            x7 ^= Integer.rotateLeft(x3 + x15, 9);
            x11 ^= Integer.rotateLeft(x7 + x3, 13);
            x15 ^= Integer.rotateLeft(x11 + x7, 18);

            x1 ^= Integer.rotateLeft(x0 + x3, 7);
            x2 ^= Integer.rotateLeft(x1 + x0, 9);
            x3 ^= Integer.rotateLeft(x2 + x1, 13);
            x0 ^= Integer.rotateLeft(x3 + x2, 18);

            x6 ^= Integer.rotateLeft(x5 + x4, 7);
            x7 ^= Integer.rotateLeft(x6 + x5, 9);
            x4 ^= Integer.rotateLeft(x7 + x6, 13);
            x5 ^= Integer.rotateLeft(x4 + x7, 18);

            x11 ^= Integer.rotateLeft(x10 + x9, 7);
            x8 ^= Integer.rotateLeft(x11 + x10, 9);
            x9 ^= Integer.rotateLeft(x8 + x11, 13);
            x10 ^= Integer.rotateLeft(x9 + x8, 18);

            x12 ^= Integer.rotateLeft(x15 + x14, 7);
            x13 ^= Integer.rotateLeft(x12 + x15, 9);
            x14 ^= Integer.rotateLeft(x13 + x12, 13);
            x15 ^= Integer.rotateLeft(x14 + x13, 18);
        }
        x[0] = x0;
        x[1] = x1;
        x[2] = x2;
        x[3] = x3;
        x[4] = x4;
        x[5] = x5;
        x[6] = x6;
        x[7] = x7;
        x[8] = x8;
        x[9] = x9;
        x[10] = x10;
        x[11] = x11;
        x[12] = x12;
        x[13] = x13;
        x[14] = x14;
        x[15] = x15;
    }

    /** The 32-byte key HSalsa20 derives from a 32-byte key and the 16 bytes of {@code in} at {@code inOff}. */
    static void hsalsa20(byte[] out, byte[] key, byte[] in, int inOff) {
        final int[] x = new int[16];
        rounds(x, key, in, inOff);
        le32(out, 0, x[0]);
        le32(out, 4, x[5]);
        le32(out, 8, x[10]);
        le32(out, 12, x[15]);
        le32(out, 16, x[6]);
        le32(out, 20, x[7]);
        le32(out, 24, x[8]);
        le32(out, 28, x[9]);
        java.util.Arrays.fill(x, 0);
    }

    /** One block of the stream: the words the rounds leave, each plus the word it started as. */
    private static void block(byte[] out, int[] x, byte[] key, byte[] in) {
        rounds(x, key, in, 0);
        le32(out, 0, x[0] + SIGMA0);
        le32(out, 4, x[1] + le32(key, 0));
        le32(out, 8, x[2] + le32(key, 4));
        le32(out, 12, x[3] + le32(key, 8));
        le32(out, 16, x[4] + le32(key, 12));
        le32(out, 20, x[5] + SIGMA1);
        le32(out, 24, x[6] + le32(in, 0));
        le32(out, 28, x[7] + le32(in, 4));
        le32(out, 32, x[8] + le32(in, 8));
        le32(out, 36, x[9] + le32(in, 12));
        le32(out, 40, x[10] + SIGMA2);
        le32(out, 44, x[11] + le32(key, 16));
        le32(out, 48, x[12] + le32(key, 20));
        le32(out, 52, x[13] + le32(key, 24));
        le32(out, 56, x[14] + le32(key, 28));
        le32(out, 60, x[15] + SIGMA3);
    }

    /** The nonce of 8 bytes at {@code nonceOff} and a block counter, as the cipher takes them. */
    private static byte[] input(byte[] nonce, int nonceOff, long counter) {
        final byte[] input = new byte[16];
        System.arraycopy(nonce, nonceOff, input, 0, 8);
        counter(input, counter);
        return input;
    }

    private static void counter(byte[] input, long counter) {
        for (int i = 0; i < 8; i++) {
            input[8 + i] = (byte) (counter >>> (8 * i));
        }
    }

    /** Block {@code counter} of the stream of a key and the 8 bytes of nonce at {@code nonceOff}: 64 bytes. */
    static void block(byte[] out, byte[] key, byte[] nonce, int nonceOff, long counter) {
        final int[] x = new int[16];
        block(out, x, key, input(nonce, nonceOff, counter));
        java.util.Arrays.fill(x, 0);
    }

    /**
     * XORs {@code len} bytes of {@code in} with the stream of a key and the 8
     * bytes of nonce at {@code nonceOff}, from the start of block
     * {@code counter} on, and writes them to {@code out}. The two arrays may
     * be the same at the same offset.
     */
    static void xor(byte[] out, int outOff, byte[] in, int inOff, int len,
                    byte[] key, byte[] nonce, int nonceOff, long counter) {
        if (len <= 0) {
            return;
        }
        final byte[] input = input(nonce, nonceOff, counter);
        final byte[] stream = new byte[BLOCK];
        final int[] x = new int[16];
        while (len > 0) {
            block(stream, x, key, input);
            final int n = Math.min(len, BLOCK);
            for (int i = 0; i < n; i++) {
                out[outOff + i] = (byte) (in[inOff + i] ^ stream[i]);
            }
            outOff += n;
            inOff += n;
            len -= n;
            counter(input, ++counter);
        }
        java.util.Arrays.fill(stream, (byte) 0);
        java.util.Arrays.fill(x, 0);
    }
}
