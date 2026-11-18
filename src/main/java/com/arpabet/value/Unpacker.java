/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Reads values from MessagePack bytes, one after another.
 *
 * <pre>{@code
 * Unpacker in = new Unpacker(bytes);
 * for (Value v = in.next(); v != null; v = in.next()) {
 *     ...
 * }
 * }</pre>
 *
 * <p>It reads every MessagePack form, canonical or not, and gives the value
 * the Go implementation gives for the same bytes: an integer in any integer
 * form, a float 32 as the double it widens to, a {@code uint 64} above the
 * range of a long as a big integer, the extensions 1, 2 and -1 as big integer,
 * decimal and timestamp, any other extension as an {@link ExtensionValue}. A
 * map whose keys are all non-negative integers within
 * {@link Limits#maxCollectionLength()} is a sparse list; any other map is a
 * {@link MapValue}, a key that is not text being the text of its
 * {@code toString}.
 *
 * <p>{@link Value#unpack(byte[])} reads the first value of an array and
 * {@link Value#read(InputStream)} the next value of a stream; this class is
 * for arrays that hold several values, and tells where each one ends.
 */
public final class Unpacker {

    private static final int CHUNK = 1 << 16;

    private final byte[] buf;
    private final int end;
    private int pos;
    private final InputStream in;
    private byte[] scratch;
    private Limits limits = Limits.DEFAULT;

    /** Reads from the whole array. */
    public Unpacker(byte[] buf) {
        this(buf, 0, buf.length);
    }

    /** Reads from {@code length} bytes of the array at {@code offset}. */
    public Unpacker(byte[] buf, int offset, int length) {
        if (offset < 0 || length < 0 || offset > buf.length - length) {
            throw new IndexOutOfBoundsException("offset " + offset + ", length " + length + " of " + buf.length);
        }
        this.buf = buf;
        this.pos = offset;
        this.end = offset + length;
        this.in = null;
    }

    Unpacker(InputStream in) {
        this.buf = null;
        this.end = 0;
        this.in = in;
    }

    /** Sets the decoding limits; {@link Limits#DEFAULT} applies otherwise. */
    public Unpacker limits(Limits limits) {
        if (limits == null) {
            throw new NullPointerException("limits");
        }
        this.limits = limits;
        return this;
    }

    /** The index in the array of the next byte to read. */
    public int position() {
        return pos;
    }

    /** Reports whether bytes are left. */
    public boolean hasNext() {
        return pos < end;
    }

    /**
     * Reads the next value.
     *
     * @return the value, or {@code null} when no bytes are left
     * @throws ValueException when the bytes are not a value or exceed a limit
     */
    public Value next() {
        try {
            return readNext();
        } catch (IOException e) {
            throw new ValueException(e.toString(), e); // an array does not fail
        }
    }

    /** Reads the next value of the array or the stream; null at a clean end. */
    Value readNext() throws IOException {
        try {
            return parse(0, true);
        } catch (StackOverflowError e) {
            // Only the text of a key that is itself a deep list or map recurses.
            throw new ValueException("value is nested too deeply for the stack");
        }
    }

    // --- input -------------------------------------------------------------

    private static ValueException eof() {
        return new ValueException("unexpected end of input");
    }

    /** The next byte, or -1 at the end of the input. */
    private int readByte() throws IOException {
        if (buf != null) {
            return pos < end ? buf[pos++] & 0xFF : -1;
        }
        return in.read();
    }

    private int need() throws IOException {
        final int b = readByte();
        if (b < 0) {
            throw eof();
        }
        return b;
    }

    /** An unsigned big-endian integer of 1, 2, 4 or 8 bytes. */
    private long uint(int n) throws IOException {
        if (buf != null) {
            if (end - pos < n) {
                throw eof();
            }
            long v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 8) | (buf[pos++] & 0xFF);
            }
            return v;
        }
        if (scratch == null) {
            scratch = new byte[8];
        }
        fill(scratch, n);
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (scratch[i] & 0xFF);
        }
        return v;
    }

    private void fill(byte[] dst, int n) throws IOException {
        int have = 0;
        while (have < n) {
            final int r = in.read(dst, have, n - have);
            if (r < 0) {
                throw eof();
            }
            have += r;
        }
    }

    /** The next n bytes, in an array of their own. */
    private byte[] bytes(int n) throws IOException {
        if (buf != null) {
            if (end - pos < n) {
                throw eof();
            }
            final byte[] out = Arrays.copyOfRange(buf, pos, pos + n);
            pos += n;
            return out;
        }
        if (n <= CHUNK) {
            final byte[] out = new byte[n];
            fill(out, n);
            return out;
        }
        // Grow as the bytes arrive: a header that claims a length the stream
        // does not deliver cannot make this allocate it.
        byte[] out = new byte[CHUNK];
        int have = 0;
        while (have < n) {
            if (have == out.length) {
                out = Arrays.copyOf(out, (int) Math.min((long) out.length * 2, n));
            }
            final int r = in.read(out, have, out.length - have);
            if (r < 0) {
                throw eof();
            }
            have += r;
        }
        return out;
    }

    private String string(int n) throws IOException {
        if (buf != null) {
            if (end - pos < n) {
                throw eof();
            }
            final String s = Utf8.decode(buf, pos, n);
            pos += n;
            return s;
        }
        return Utf8.decode(bytes(n), 0, n);
    }

    // --- values ------------------------------------------------------------

    /** Reads one value at a nesting depth, as a field of a struct holds one. */
    Value value(int depth) throws IOException {
        return parse(depth, false);
    }

    Limits limits() {
        return limits;
    }

    /** The number of entries of the map that comes next, which is how a struct of a numeric schema starts. */
    long mapHeader(int depth) throws IOException {
        limits.checkDepth(depth);
        final int code = need();
        if (code >= 0x80 && code <= 0x8f) {
            return code & 0x0f;
        }
        if (code == 0xde) {
            return uint(2);
        }
        if (code == 0xdf) {
            return uint(4);
        }
        throw new ValueException("expected a map for a struct, got MessagePack code 0x" + Integer.toHexString(code));
    }

    /** The number of elements of the array that comes next. */
    long listHeader() throws IOException {
        final int code = need();
        if (code >= 0x90 && code <= 0x9f) {
            return code & 0x0f;
        }
        if (code == 0xdc) {
            return uint(2);
        }
        if (code == 0xdd) {
            return uint(4);
        }
        throw new ValueException("expected an array for a list field, got MessagePack code 0x" + Integer.toHexString(code));
    }

    /**
     * Reads one value, whose depth is {@code base}. The lists and maps that
     * are open while their elements are read are kept on a stack of their own
     * rather than on the thread's, so that how deep a value may nest is a
     * matter of the limit and not of the stack the caller happens to run on.
     *
     * @return the value, or null at the end of the input when {@code top}
     */
    private Value parse(int base, boolean top) throws IOException {
        Frame[] stack = null;
        int sp = 0;
        for (;;) {
            limits.checkDepth(base + sp);
            final int code = readByte();
            if (code < 0) {
                if (sp == 0 && top) {
                    return null;
                }
                throw eof();
            }
            final Frame open = sp == 0 ? null : stack[sp - 1];
            if (open != null && open.wantsKey) {
                // Text is by far the usual key and is read without a value around it.
                final String text = text(code);
                if (text != null) {
                    open.key(text);
                    continue;
                }
            }
            Value v;
            Frame opened = null;
            if ((code >= 0x90 && code <= 0x9f) || code == 0xdc || code == 0xdd) {
                final int n = limits.checkCollectionLength(code <= 0x9f ? code & 0x0f : uint(code == 0xdc ? 2 : 4));
                v = DenseList.EMPTY;
                if (n != 0) {
                    opened = new ListFrame(n);
                }
            } else if ((code >= 0x80 && code <= 0x8f) || code == 0xde || code == 0xdf) {
                final int n = limits.checkCollectionLength(code <= 0x8f ? code & 0x0f : uint(code == 0xde ? 2 : 4));
                v = MapValue.EMPTY;
                if (n != 0) {
                    opened = new MapFrame(n, limits);
                }
            } else {
                v = scalar(code);
            }
            if (opened != null) {
                if (stack == null) {
                    stack = new Frame[8];
                } else if (sp == stack.length) {
                    stack = Arrays.copyOf(stack, sp * 2);
                }
                stack[sp++] = opened;
                continue;
            }
            // The value goes to the list or map it is an element of, which may
            // complete it, and so on outwards.
            for (;;) {
                if (sp == 0) {
                    return v;
                }
                final Frame f = stack[sp - 1];
                if (!f.add(v)) {
                    break;
                }
                v = f.build();
                stack[--sp] = null;
            }
        }
    }

    /** The text of a str token, or null when the token is something else. */
    private String text(int code) throws IOException {
        if (code >= 0xa0 && code <= 0xbf) {
            return string(limits.checkByteLength(code & 0x1f));
        }
        switch (code) {
            case 0xd9:
                return string(limits.checkByteLength(uint(1)));
            case 0xda:
                return string(limits.checkByteLength(uint(2)));
            case 0xdb:
                return string(limits.checkByteLength(uint(4)));
            default:
                return null;
        }
    }

    /** The value of a token that is not the header of a list or a map. */
    private Value scalar(int code) throws IOException {
        if (code <= 0x7f) {
            return LongNumber.of(code);
        }
        if (code >= 0xe0) {
            return LongNumber.of((byte) code);
        }
        final String text = text(code);
        if (text != null) {
            return new Utf8String(text);
        }
        switch (code) {
            case 0xc0:
            case 0xc1: // never used by MessagePack; the Go decoder reads it as nil
                return NullValue.NULL;
            case 0xc2:
                return BoolValue.FALSE;
            case 0xc3:
                return BoolValue.TRUE;
            case 0xc4:
                return new RawString(bytes(limits.checkByteLength(uint(1))));
            case 0xc5:
                return new RawString(bytes(limits.checkByteLength(uint(2))));
            case 0xc6:
                return new RawString(bytes(limits.checkByteLength(uint(4))));
            case 0xc7:
                return ext(limits.checkByteLength(uint(1)));
            case 0xc8:
                return ext(limits.checkByteLength(uint(2)));
            case 0xc9:
                return ext(limits.checkByteLength(uint(4)));
            case 0xca:
                return new DoubleNumber(widen((int) uint(4)));
            case 0xcb:
                return new DoubleNumber(uint(8));
            case 0xcc:
                return LongNumber.of(uint(1));
            case 0xcd:
                return LongNumber.of(uint(2));
            case 0xce:
                return LongNumber.of(uint(4));
            case 0xcf:
                // An integer above the range of a long is the big integer of
                // that number, not the negative long of the same bits.
                return NumberValue.unsigned(uint(8));
            case 0xd0:
                return LongNumber.of((byte) uint(1));
            case 0xd1:
                return LongNumber.of((short) uint(2));
            case 0xd2:
                return LongNumber.of((int) uint(4));
            case 0xd3:
                return LongNumber.of(uint(8));
            case 0xd4:
                return ext(1);
            case 0xd5:
                return ext(2);
            case 0xd6:
                return ext(4);
            case 0xd7:
                return ext(8);
            case 0xd8:
                return ext(16);
            default:
                throw new ValueException("invalid MessagePack code 0x" + Integer.toHexString(code));
        }
    }

    /**
     * The bits of the double a float 32 widens to. A NaN keeps its payload,
     * moved to the top of the wider fraction, and becomes quiet, which is what
     * the conversion does in hardware; doing it here gives the same bits on
     * every runtime.
     */
    private static long widen(int bits) {
        final float f = Float.intBitsToFloat(bits);
        if (f != f) {
            return ((long) (bits & 0x80000000) << 32) | 0x7FF8000000000000L | ((long) (bits & 0x007FFFFF) << 29);
        }
        return Double.doubleToRawLongBits(f);
    }

    private Value ext(int n) throws IOException {
        final int type = (byte) need();
        return extension(type, bytes(n), limits);
    }

    /**
     * The value of an extension: a big integer, a decimal or a timestamp for
     * the types the format defines, and an {@link ExtensionValue} otherwise.
     * The data is not copied.
     */
    static Value extension(int type, byte[] data, Limits limits) {
        switch (type) {
            case ExtensionValue.BIGINT:
                if (Value.legacyBigNumbers()) {
                    return new BigIntNumber(legacyBigInt(data, 0));
                }
                if (data.length == 0) {
                    throw new ValueException("empty big integer payload");
                }
                return new BigIntNumber(new BigInteger(data));
            case ExtensionValue.DECIMAL: {
                if (Value.legacyBigNumbers()) {
                    return new DecimalNumber(legacyDecimal(data, limits));
                }
                if (data.length < 5) {
                    throw new ValueException("decimal payload of " + data.length + " bytes, want at least 5");
                }
                final int scale = (data[0] << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                limits.checkDecimalExponent(-(long) scale);
                return new DecimalNumber(new BigDecimal(new BigInteger(Arrays.copyOfRange(data, 4, data.length)), scale));
            }
            case ExtensionValue.TIMESTAMP:
                return TimestampValue.ofPayload(data);
            default:
                return new ExtensionValue(type, data);
        }
    }

    /**
     * A big integer as Go's math/big wrote it for gob, the payload before
     * v1.5.0: a byte holding the version of the encoding, which is 1, above
     * the sign, then the magnitude. No bytes at all is zero.
     */
    static BigInteger legacyBigInt(byte[] data, int from) {
        if (data.length == from) {
            return BigInteger.ZERO;
        }
        final int head = data[from] & 0xFF;
        if (head >> 1 != 1) {
            throw new ValueException("legacy big integer payload: encoding version " + (head >> 1) + " not supported");
        }
        final BigInteger magnitude = new BigInteger(1, Arrays.copyOfRange(data, from + 1, data.length));
        return (head & 1) != 0 ? magnitude.negate() : magnitude;
    }

    /** A decimal as it travelled before v1.5.0: a 4-byte exponent, then the coefficient as above. */
    static BigDecimal legacyDecimal(byte[] data, Limits limits) {
        if (data.length < 4) {
            throw new ValueException("legacy decimal payload of " + data.length + " bytes, want at least 4");
        }
        final int exponent = (data[0] << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        final BigInteger coefficient = legacyBigInt(data, 4);
        limits.checkDecimalExponent(exponent);
        if (exponent == Integer.MIN_VALUE) {
            throw new ValueException("decimal exponent " + exponent + " is out of range");
        }
        return new BigDecimal(coefficient, -exponent);
    }

    // --- lists and maps being read ------------------------------------------------

    private abstract static class Frame {
        /** Whether the next token is the key of a map entry. */
        boolean wantsKey;

        /** Takes the next element; true when it was the last. */
        abstract boolean add(Value v);

        void key(String text) {
            throw new IllegalStateException();
        }

        abstract Value build();
    }

    private static final class ListFrame extends Frame {
        private final int total;
        private Value[] items;
        private int n;

        ListFrame(int total) {
            this.total = total;
            // Start small and grow: a header that claims a length the input
            // does not deliver fails at the first missing element.
            this.items = new Value[Math.min(total, 4096)];
        }

        @Override
        boolean add(Value v) {
            if (n == items.length) {
                items = Arrays.copyOf(items, (int) Math.min((long) items.length * 2, total));
            }
            items[n++] = v;
            return n == total;
        }

        @Override
        Value build() {
            return new DenseList(items);
        }
    }

    /**
     * A map being read, which turns out to be a sparse list when every key is
     * an index, and a map of text keys otherwise.
     */
    private static final class MapFrame extends Frame {
        private final int total;
        private final Limits limits;
        private int n;
        private int cap;
        private String text;
        private Value key;
        private Value[] vals;
        private int[] indexes;
        private String[] keys;
        private boolean sparse;
        private boolean sorted = true;
        private long prevIndex;
        private String prevKey;

        MapFrame(int total, Limits limits) {
            this.total = total;
            this.limits = limits;
            this.cap = Math.min(total, 4096);
            this.vals = new Value[cap];
            this.wantsKey = true;
        }

        @Override
        void key(String text) {
            this.text = text;
            this.key = null;
            this.wantsKey = false;
        }

        @Override
        boolean add(Value v) {
            if (wantsKey) {
                text = null;
                key = v;
                wantsKey = false;
                return false;
            }
            entry(v);
            wantsKey = true;
            return ++n == total;
        }

        private void entry(Value val) {
            final int i = n;
            final boolean index = key instanceof LongNumber && limits.isListIndex(((LongNumber) key).value);
            if (i == 0) {
                sparse = index;
                if (sparse) {
                    indexes = new int[cap];
                } else {
                    keys = new String[cap];
                }
            }
            if (i == cap) {
                cap = (int) Math.min((long) cap * 2, total);
                vals = Arrays.copyOf(vals, cap);
                if (sparse) {
                    indexes = Arrays.copyOf(indexes, cap);
                } else {
                    keys = Arrays.copyOf(keys, cap);
                }
            }
            if (sparse && index) {
                final long k = ((LongNumber) key).value;
                if (i > 0 && prevIndex > k) {
                    sorted = false;
                }
                indexes[i] = (int) k;
                prevIndex = k;
            } else {
                // A key that is not text is the text of its toString.
                final String name = text != null ? text : key.toString();
                if (sparse) {
                    // Not a list after all: the indexes read so far become the
                    // keys they spell, and the order has to be established.
                    sparse = false;
                    sorted = false;
                    keys = new String[cap];
                    for (int j = 0; j < i; j++) {
                        keys[j] = Integer.toString(indexes[j]);
                    }
                    indexes = null;
                } else if (i > 0 && Utf8.compare(prevKey, name) > 0) {
                    sorted = false;
                }
                keys[i] = name;
                prevKey = name;
            }
            vals[i] = val;
        }

        @Override
        Value build() {
            return sparse ? SparseList.of(indexes, vals, sorted) : MapValue.of(keys, vals, sorted);
        }
    }
}
