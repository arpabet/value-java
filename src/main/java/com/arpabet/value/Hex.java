/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/** Lower-case hexadecimal, as Go's encoding/hex writes and reads it. */
final class Hex {

    private Hex() {
    }

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    static String encode(byte[] b) {
        return encode(b, 0, b.length);
    }

    static String encode(byte[] b, int off, int len) {
        final char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            final int v = b[off + i] & 0xFF;
            out[2 * i] = DIGITS[v >>> 4];
            out[2 * i + 1] = DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    static void append(StringBuilder out, int b) {
        out.append(DIGITS[(b >>> 4) & 0x0F]).append(DIGITS[b & 0x0F]);
    }

    /** Decodes hex of either case; an odd length or another character is refused. */
    static byte[] decode(String s) {
        final int n = s.length();
        if ((n & 1) != 0) {
            throw new ValueException("hex of odd length " + n);
        }
        final byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = digit(s.charAt(2 * i));
            final int lo = digit(s.charAt(2 * i + 1));
            if (hi < 0 || lo < 0) {
                throw new ValueException("invalid hex character at " + (hi < 0 ? 2 * i : 2 * i + 1));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
