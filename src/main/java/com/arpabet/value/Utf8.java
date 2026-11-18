/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.nio.charset.StandardCharsets;

/**
 * UTF-8 that loses no byte.
 *
 * <p>A MessagePack {@code str} holds bytes, and the Go implementation keeps
 * them whatever they are: a string that is not valid UTF-8 packs again to the
 * bytes it was read from. A Java {@code String} holds UTF-16, so the bytes that
 * are not part of a valid UTF-8 sequence are carried as lone low surrogates,
 * one per byte: the byte {@code 0x80 + n} is the char {@code U+DC80 + n}. No
 * valid UTF-8 decodes to a lone surrogate, so the two forms map one to one, and
 * encoding gives back exactly the bytes that were decoded. (It is the
 * "surrogateescape" convention of Python's PEP 383.)
 *
 * <p>A string is <em>canonical</em> when it is what its own bytes decode to.
 * Every string a value holds is canonical, which makes equal strings and equal
 * bytes the same thing. Lone surrogates outside the escape range have no byte
 * form and encode as U+FFFD, as they do in Go and JavaScript.
 */
final class Utf8 {

    private Utf8() {
    }

    private static final int ESCAPE_MIN = 0xDC80;
    private static final int ESCAPE_MAX = 0xDCFF;

    /** The number of bytes {@link #encode} writes for the string. */
    static int encodedLength(String s) {
        final int n = s.length();
        long size = n;
        for (int i = 0; i < n; i++) {
            final char c = s.charAt(i);
            if (c < 0x80) {
                continue;
            }
            if (c < 0x800) {
                size++;
            } else if (c < 0xD800 || c > 0xDFFF) {
                size += 2;
            } else if (c <= 0xDBFF && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                size += 2; // four bytes for the two chars of a pair
                i++;
            } else if (c < ESCAPE_MIN || c > ESCAPE_MAX) {
                size += 2; // a lone surrogate that is not an escaped byte: U+FFFD
            }
            // an escaped byte is one byte for its one char
        }
        if (size > Integer.MAX_VALUE) {
            throw new ValueException("string of " + size + " bytes is too long");
        }
        return (int) size;
    }

    /** Writes the bytes of the string at {@code off} and returns the offset after them. */
    static int encode(String s, byte[] dst, int off) {
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            final char c = s.charAt(i);
            if (c < 0x80) {
                dst[off++] = (byte) c;
            } else if (c < 0x800) {
                dst[off++] = (byte) (0xC0 | (c >> 6));
                dst[off++] = (byte) (0x80 | (c & 0x3F));
            } else if (c < 0xD800 || c > 0xDFFF) {
                dst[off++] = (byte) (0xE0 | (c >> 12));
                dst[off++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                dst[off++] = (byte) (0x80 | (c & 0x3F));
            } else if (c <= 0xDBFF && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                final int cp = Character.toCodePoint(c, s.charAt(++i));
                dst[off++] = (byte) (0xF0 | (cp >> 18));
                dst[off++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[off++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[off++] = (byte) (0x80 | (cp & 0x3F));
            } else if (c >= ESCAPE_MIN && c <= ESCAPE_MAX) {
                dst[off++] = (byte) (c - 0xDC00);
            } else {
                dst[off++] = (byte) 0xEF;
                dst[off++] = (byte) 0xBF;
                dst[off++] = (byte) 0xBD;
            }
        }
        return off;
    }

    static byte[] encode(String s) {
        final byte[] out = new byte[encodedLength(s)];
        encode(s, out, 0);
        return out;
    }

    /**
     * Decodes bytes into the canonical string. A byte that is not part of a
     * valid UTF-8 sequence (as Go's {@code unicode/utf8} defines one: shortest
     * form, no surrogates, at most U+10FFFF) becomes an escape, one byte at a
     * time.
     */
    static String decode(byte[] b, int off, int len) {
        final int end = off + len;
        int i = off;
        while (i < end && b[i] >= 0) {
            i++;
        }
        if (i == end) {
            return new String(b, off, len, StandardCharsets.ISO_8859_1);
        }
        final char[] out = new char[len];
        int o = 0;
        for (int j = off; j < i; j++) {
            out[o++] = (char) b[j];
        }
        while (i < end) {
            final int c = b[i] & 0xFF;
            if (c < 0x80) {
                out[o++] = (char) c;
                i++;
                continue;
            }
            final int n = sequenceLength(b, i, end);
            switch (n) {
                case 2:
                    out[o++] = (char) (((c & 0x1F) << 6) | (b[i + 1] & 0x3F));
                    break;
                case 3:
                    out[o++] = (char) (((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F));
                    break;
                case 4: {
                    final int cp = ((c & 0x07) << 18) | ((b[i + 1] & 0x3F) << 12)
                            | ((b[i + 2] & 0x3F) << 6) | (b[i + 3] & 0x3F);
                    out[o++] = Character.highSurrogate(cp);
                    out[o++] = Character.lowSurrogate(cp);
                    break;
                }
                default:
                    out[o++] = (char) (0xDC00 + c);
                    i++;
                    continue;
            }
            i += n;
        }
        return new String(out, 0, o);
    }

    static String decode(byte[] b) {
        return decode(b, 0, b.length);
    }

    /**
     * The length of the valid sequence that starts at {@code i} with a byte of
     * 0x80 or above, or 0 when there is none.
     */
    private static int sequenceLength(byte[] b, int i, int end) {
        final int c = b[i] & 0xFF;
        if (c < 0xC2 || c > 0xF4) {
            return 0;
        }
        if (i + 1 >= end) {
            return 0;
        }
        final int c1 = b[i + 1] & 0xFF;
        if (c < 0xE0) {
            return (c1 & 0xC0) == 0x80 ? 2 : 0;
        }
        if (c < 0xF0) {
            final int lo = c == 0xE0 ? 0xA0 : 0x80;
            final int hi = c == 0xED ? 0x9F : 0xBF;
            if (c1 < lo || c1 > hi || i + 2 >= end || (b[i + 2] & 0xC0) != 0x80) {
                return 0;
            }
            return 3;
        }
        final int lo = c == 0xF0 ? 0x90 : 0x80;
        final int hi = c == 0xF4 ? 0x8F : 0xBF;
        if (c1 < lo || c1 > hi || i + 3 >= end || (b[i + 2] & 0xC0) != 0x80 || (b[i + 3] & 0xC0) != 0x80) {
            return 0;
        }
        return 4;
    }

    /** Reports whether the bytes are valid UTF-8. */
    static boolean isValid(byte[] b, int off, int len) {
        final int end = off + len;
        int i = off;
        while (i < end) {
            if (b[i] >= 0) {
                i++;
                continue;
            }
            final int n = sequenceLength(b, i, end);
            if (n == 0) {
                return false;
            }
            i += n;
        }
        return true;
    }

    /**
     * Reports whether the string has a surrogate outside a pair. A canonical
     * string has one exactly when its bytes are not valid UTF-8.
     */
    static boolean hasLoneSurrogate(String s) {
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            final char c = s.charAt(i);
            if (c < 0xD800 || c > 0xDFFF) {
                continue;
            }
            if (c <= 0xDBFF && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
                continue;
            }
            return true;
        }
        return false;
    }

    /** The canonical string of the same bytes: the string itself unless it has a lone surrogate. */
    static String canonical(String s) {
        if (!hasLoneSurrogate(s)) {
            return s;
        }
        final byte[] b = encode(s);
        return decode(b, 0, b.length);
    }

    /**
     * Compares two canonical strings by their bytes, which is the order of map
     * keys in the canonical form. It differs from {@link String#compareTo}
     * where one string has a character above U+FFFF and the other one from
     * U+E000 to U+FFFF at the same place.
     */
    static int compare(String a, String b) {
        final int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            final char x = a.charAt(i);
            final char y = b.charAt(i);
            if (x == y) {
                continue;
            }
            if (!Character.isSurrogate(x) && !Character.isSurrogate(y)) {
                return x - y;
            }
            // A pair or an escaped byte is involved: compare the bytes themselves.
            return compareBytes(encode(a), encode(b));
        }
        return a.length() - b.length();
    }

    static int compareBytes(byte[] a, byte[] b) {
        final int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            final int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return a.length - b.length;
    }
}
