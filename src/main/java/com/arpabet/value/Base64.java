/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Arrays;

/**
 * Standard-alphabet base64, with and without padding, read exactly as Go's
 * encoding/base64 reads it: line breaks are skipped, the bits left over in the
 * last character are not checked, and anything else out of place is refused.
 * (java.util.Base64 is stricter, and needs Android API 26.)
 */
final class Base64 {

    private Base64() {
    }

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static final byte[] DECODE = new byte[128];

    static {
        Arrays.fill(DECODE, (byte) -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE[ALPHABET[i]] = (byte) i;
        }
    }

    /** Go's base64.StdEncoding.EncodeToString. */
    static String encodeStd(byte[] b) {
        return encode(b, 0, b.length, true);
    }

    /** Go's base64.RawStdEncoding.EncodeToString. */
    static String encodeRawStd(byte[] b) {
        return encode(b, 0, b.length, false);
    }

    static String encode(byte[] b, int off, int len, boolean padded) {
        final int full = len / 3;
        final int rest = len - full * 3;
        final long size = (long) full * 4 + (rest == 0 ? 0 : padded ? 4 : rest + 1);
        if (size > Integer.MAX_VALUE - 8) {
            throw new ValueException("binary of " + len + " bytes is too long for base64");
        }
        final char[] out = new char[(int) size];
        int o = 0;
        int i = off;
        for (int k = 0; k < full; k++, i += 3) {
            final int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8) | (b[i + 2] & 0xFF);
            out[o++] = ALPHABET[v >>> 18];
            out[o++] = ALPHABET[(v >>> 12) & 63];
            out[o++] = ALPHABET[(v >>> 6) & 63];
            out[o++] = ALPHABET[v & 63];
        }
        if (rest == 1) {
            final int v = (b[i] & 0xFF) << 16;
            out[o++] = ALPHABET[v >>> 18];
            out[o++] = ALPHABET[(v >>> 12) & 63];
            if (padded) {
                out[o++] = '=';
                out[o++] = '=';
            }
        } else if (rest == 2) {
            final int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8);
            out[o++] = ALPHABET[v >>> 18];
            out[o++] = ALPHABET[(v >>> 12) & 63];
            out[o++] = ALPHABET[(v >>> 6) & 63];
            if (padded) {
                out[o++] = '=';
            }
        }
        return new String(out, 0, o);
    }

    /** Go's base64.StdEncoding.DecodeString. */
    static byte[] decodeStd(String s) {
        return decode(s, true);
    }

    /** Go's base64.RawStdEncoding.DecodeString. */
    static byte[] decodeRawStd(String s) {
        return decode(s, false);
    }

    /**
     * Standard base64, padded or not, as the JSON wrappers take it: padded when
     * the text ends in padding or has a multiple of four characters.
     */
    static byte[] decodeJson(String s, Limits limits) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == '=') {
            n--;
        }
        limits.checkByteLength((long) n * 6 / 8);
        if (s.endsWith("=") || s.length() % 4 == 0) {
            return decodeStd(s);
        }
        return decodeRawStd(s);
    }

    private static byte[] decode(String s, boolean padded) {
        final int n = s.length();
        final byte[] out = new byte[(int) ((long) n * 3 / 4) + 3];
        int o = 0;
        int si = 0;
        while (si < n) {
            int d0 = 0, d1 = 0, d2 = 0, d3 = 0;
            int dlen = 4;
            quantum:
            for (int j = 0; j < 4; j++) {
                if (si == n) {
                    if (j == 0) {
                        return Arrays.copyOf(out, o);
                    }
                    if (j == 1 || padded) {
                        throw corrupt(si - j);
                    }
                    dlen = j;
                    break;
                }
                final char in = s.charAt(si++);
                final int v = in < 128 ? DECODE[in] : -1;
                if (v >= 0) {
                    switch (j) {
                        case 0:
                            d0 = v;
                            break;
                        case 1:
                            d1 = v;
                            break;
                        case 2:
                            d2 = v;
                            break;
                        default:
                            d3 = v;
                            break;
                    }
                    continue;
                }
                if (in == '\n' || in == '\r') {
                    j--;
                    continue;
                }
                if (!padded || in != '=') {
                    throw corrupt(si - 1);
                }
                // The end, with padding.
                switch (j) {
                    case 0:
                    case 1:
                        throw corrupt(si - 1);
                    case 2:
                        // "==" is expected; the first "=" is consumed.
                        while (si < n && (s.charAt(si) == '\n' || s.charAt(si) == '\r')) {
                            si++;
                        }
                        if (si == n) {
                            throw corrupt(n);
                        }
                        if (s.charAt(si) != '=') {
                            throw corrupt(si - 1);
                        }
                        si++;
                        break;
                    default:
                        break;
                }
                while (si < n && (s.charAt(si) == '\n' || s.charAt(si) == '\r')) {
                    si++;
                }
                if (si < n) {
                    throw corrupt(si);
                }
                dlen = j;
                break quantum;
            }
            final int val = (d0 << 18) | (d1 << 12) | (d2 << 6) | d3;
            switch (dlen) {
                case 4:
                    out[o + 2] = (byte) val;
                    out[o + 1] = (byte) (val >>> 8);
                    out[o] = (byte) (val >>> 16);
                    break;
                case 3:
                    out[o + 1] = (byte) (val >>> 8);
                    out[o] = (byte) (val >>> 16);
                    break;
                default:
                    out[o] = (byte) (val >>> 16);
                    break;
            }
            o += dlen - 1;
        }
        return Arrays.copyOf(out, o);
    }

    private static ValueException corrupt(int at) {
        return new ValueException("illegal base64 data at input byte " + at);
    }
}
