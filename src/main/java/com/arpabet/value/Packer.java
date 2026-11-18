/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Arrays;

/**
 * Writes MessagePack into a growing byte array, each token in the one form the
 * canonical encoding allows: the smallest integer family, float 64 for every
 * double, the smallest header for every string, binary, extension, array and
 * map. Text goes from the string's chars straight into the buffer as UTF-8.
 */
final class Packer {

    private byte[] buf;
    private int pos;

    Packer() {
        this(64);
    }

    Packer(int capacity) {
        this.buf = new byte[capacity];
    }

    byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    private void ensure(int n) {
        final long need = (long) pos + n;
        if (need > buf.length) {
            if (need > Integer.MAX_VALUE - 8) {
                throw new ValueException("packed value exceeds " + (Integer.MAX_VALUE - 8) + " bytes");
            }
            final long grown = Math.min(Math.max((long) buf.length * 2, need), Integer.MAX_VALUE - 8);
            buf = Arrays.copyOf(buf, (int) grown);
        }
    }

    private void byte1(int code) {
        ensure(1);
        buf[pos++] = (byte) code;
    }

    private void byte2(int code, int v) {
        ensure(2);
        buf[pos++] = (byte) code;
        buf[pos++] = (byte) v;
    }

    private void short3(int code, int v) {
        ensure(3);
        buf[pos++] = (byte) code;
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
    }

    private void int5(int code, int v) {
        ensure(5);
        buf[pos++] = (byte) code;
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
    }

    private void long9(int code, long v) {
        ensure(9);
        buf[pos++] = (byte) code;
        buf[pos++] = (byte) (v >>> 56);
        buf[pos++] = (byte) (v >>> 48);
        buf[pos++] = (byte) (v >>> 40);
        buf[pos++] = (byte) (v >>> 32);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
    }

    void nil() {
        byte1(0xc0);
    }

    void bool(boolean b) {
        byte1(b ? 0xc3 : 0xc2);
    }

    /** An integer in its smallest form: the unsigned family from zero up, the signed one below. */
    void integer(long v) {
        if (v >= 0) {
            if (v <= 0x7f) {
                byte1((int) v);
            } else if (v <= 0xff) {
                byte2(0xcc, (int) v);
            } else if (v <= 0xffff) {
                short3(0xcd, (int) v);
            } else if (v <= 0xffffffffL) {
                int5(0xce, (int) v);
            } else {
                long9(0xcf, v);
            }
        } else if (v >= -32) {
            byte1((int) v);
        } else if (v >= Byte.MIN_VALUE) {
            byte2(0xd0, (int) v);
        } else if (v >= Short.MIN_VALUE) {
            short3(0xd1, (int) v);
        } else if (v >= Integer.MIN_VALUE) {
            int5(0xd2, (int) v);
        } else {
            long9(0xd3, v);
        }
    }

    /** A double by its IEEE-754 bits, always as float 64. */
    void float64(long bits) {
        long9(0xcb, bits);
    }

    @SuppressWarnings("deprecation")
    void str(String s) {
        final int chars = s.length();
        final int len = Utf8.encodedLength(s);
        if (len < 32) {
            byte1(0xa0 | len);
        } else if (len <= 0xff) {
            byte2(0xd9, len);
        } else if (len <= 0xffff) {
            short3(0xda, len);
        } else {
            int5(0xdb, len);
        }
        ensure(len);
        if (len == chars) {
            s.getBytes(0, chars, buf, pos); // ASCII: the low byte of each char
            pos += len;
        } else {
            pos = Utf8.encode(s, buf, pos);
        }
    }

    void bin(byte[] b) {
        final int len = b.length;
        if (len <= 0xff) {
            byte2(0xc4, len);
        } else if (len <= 0xffff) {
            short3(0xc5, len);
        } else {
            int5(0xc6, len);
        }
        raw(b, 0, len);
    }

    void ext(int type, byte[] data) {
        final int len = data.length;
        switch (len) {
            case 1:
                byte2(0xd4, type);
                break;
            case 2:
                byte2(0xd5, type);
                break;
            case 4:
                byte2(0xd6, type);
                break;
            case 8:
                byte2(0xd7, type);
                break;
            case 16:
                byte2(0xd8, type);
                break;
            default:
                if (len <= 0xff) {
                    byte2(0xc7, len);
                } else if (len <= 0xffff) {
                    short3(0xc8, len);
                } else {
                    int5(0xc9, len);
                }
                byte1(type);
                break;
        }
        raw(data, 0, len);
    }

    void listHeader(int n) {
        if (n < 16) {
            byte1(0x90 | n);
        } else if (n <= 0xffff) {
            short3(0xdc, n);
        } else {
            int5(0xdd, n);
        }
    }

    void mapHeader(int n) {
        if (n < 16) {
            byte1(0x80 | n);
        } else if (n <= 0xffff) {
            short3(0xde, n);
        } else {
            int5(0xdf, n);
        }
    }

    void raw(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
    }
}
