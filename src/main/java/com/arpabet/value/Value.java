/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * An immutable, dynamically typed value with one canonical encoding.
 *
 * <p>A value is null, a boolean, a number (long, double, big integer or
 * decimal), a string (text or binary), a timestamp, a list (dense or sparse),
 * a map with text keys, or an extension the format does not define. Values
 * form trees, are immutable and are safe to share between threads. The one
 * exception is a list or a map made as a mutable one ({@link #mutableList()},
 * {@link #mutableMap()}), which is an ordinary mutable object; nothing read
 * from bytes or text is ever one, and an immutable list or map never holds
 * one.
 *
 * <p>{@link #pack()} gives the canonical MessagePack bytes of a value: the
 * same value always encodes to exactly the same bytes, so the bytes can be
 * hashed and signed. The encoding is the one of the Go package
 * {@code go.arpabet.com/value} (its CANONICAL.md is the specification); bytes
 * written by either implementation read back to the same value in the other.
 *
 * <pre>{@code
 * MapValue payment = MapValue.builder()
 *         .put("amount", 100)
 *         .put("to", "alice")
 *         .build();
 * byte[] bytes = payment.pack();              // 82a6616d6f756e7464a2746fa5616c696365
 * Value back = Value.unpack(bytes);
 * back.equals(payment);                       // true
 * }</pre>
 *
 * <p>Two values are {@linkplain #equals(Object) equal} exactly when their
 * canonical bytes are equal, but for a sparse list with nothing in it, which
 * has the bytes of the empty map and is not equal to it.
 *
 * <p>A deeper value takes no more of the thread's stack: past its first
 * levels, the lists and maps that are open while a value is read, packed,
 * printed, compared or hashed are kept on the heap.
 *
 * <p>The hierarchy is closed: the kinds are the permitted subclasses, and
 * {@link #kind()} tells them apart without a cast.
 */
public abstract sealed class Value
        permits NullValue, BoolValue, NumberValue, StringValue, TimestampValue, ListValue, MapValue, ExtensionValue {

    Value() {
    }

    // --- making values -------------------------------------------------------

    /** The null value, {@link NullValue#NULL}. */
    public static NullValue ofNull() {
        return NullValue.NULL;
    }

    /** A boolean. */
    public static BoolValue ofBool(boolean b) {
        return b ? BoolValue.TRUE : BoolValue.FALSE;
    }

    /** A 64-bit integer. */
    public static NumberValue ofLong(long n) {
        return LongNumber.of(n);
    }

    /**
     * The number of an unsigned 64-bit integer held in a long: a long while it
     * fits one, and a big integer above, so that the number is the unsigned
     * one in both cases. {@code ofLong} of the same bits is a negative number
     * above the range of a long.
     */
    public static NumberValue ofUnsigned(long bits) {
        return NumberValue.unsigned(bits);
    }

    /**
     * A double. Every NaN is the one NaN of the canonical form (bits
     * {@code 7ff8000000000001}, Go's {@code math.NaN()}), since a Java
     * {@code double} does not carry its NaN payload reliably; a NaN read from
     * bytes keeps the bits it had.
     */
    public static NumberValue ofDouble(double d) {
        return DoubleNumber.of(d);
    }

    /**
     * A float, as the double it widens to, which is what it is in the
     * canonical form. A NaN is the NaN a Go {@code float32} widens to (bits
     * {@code 7ff8000000000000}), which is not the NaN of {@link #ofDouble}.
     */
    public static NumberValue ofFloat(float f) {
        return f != f ? DoubleNumber.FLOAT_NAN : DoubleNumber.of(f);
    }

    /** An integer of any size. It is a big integer even when it fits a long. */
    public static NumberValue ofBigInt(BigInteger n) {
        if (n == null) {
            throw new NullPointerException("n");
        }
        return new BigIntNumber(n);
    }

    /**
     * A decimal: the unscaled integer and the scale of {@code d} exactly, so
     * that 1.0 and 1.00 are different values.
     */
    public static NumberValue ofDecimal(BigDecimal d) {
        if (d == null) {
            throw new NullPointerException("d");
        }
        return new DecimalNumber(d);
    }

    /**
     * Text. A surrogate without its pair has no UTF-8 form: one from U+DC80 to
     * U+DCFF stands for the byte 0x80 to 0xFF it would be in a string read
     * from bytes that are not UTF-8, and any other becomes U+FFFD.
     */
    public static StringValue utf8(String s) {
        return new Utf8String(Utf8.canonical(s));
    }

    /** Binary; the array is copied. */
    public static StringValue raw(byte[] b) {
        return new RawString(b.clone());
    }

    /** Binary, a copy of {@code length} bytes of the array at {@code offset}. */
    public static StringValue raw(byte[] b, int offset, int length) {
        if (offset < 0 || length < 0 || offset > b.length - length) {
            throw new IndexOutOfBoundsException("offset " + offset + ", length " + length + " of " + b.length);
        }
        final byte[] copy = new byte[length];
        System.arraycopy(b, offset, copy, 0, length);
        return new RawString(copy);
    }

    /**
     * The instant {@code seconds} seconds and {@code nanos} nanoseconds after
     * the Unix epoch. The nanoseconds may lie outside a second; they are
     * carried into the seconds.
     */
    public static TimestampValue timestamp(long seconds, long nanos) {
        return TimestampValue.of(seconds, nanos);
    }

    /** The instant a number of milliseconds after the Unix epoch. */
    public static TimestampValue ofEpochMilli(long millis) {
        return TimestampValue.of(Math.floorDiv(millis, 1000L), Math.floorMod(millis, 1000L) * 1000000L);
    }

    /** The instant of a date, which has millisecond precision. */
    public static TimestampValue ofDate(Date date) {
        return ofEpochMilli(date.getTime());
    }

    /**
     * Parses RFC 3339 text such as {@code 2026-10-30T07:28:39.075Z}, the form
     * a timestamp prints in.
     */
    public static TimestampValue parseTimestamp(String text) {
        final long[] t = Rfc3339.parse(text);
        return new TimestampValue(t[0], (int) t[1]);
    }

    /**
     * The value of a MessagePack extension, as the decoder reads it: the big
     * integer, decimal or timestamp its data holds for the types 1, 2 and -1,
     * and an {@link ExtensionValue} for any other type.
     *
     * @param type the extension type, -128 to 127 (0 to 255 is accepted as the byte)
     */
    public static Value ext(int type, byte[] data) {
        if (type < -128 || type > 255) {
            throw new IllegalArgumentException("extension type " + type);
        }
        return Unpacker.extension((byte) type, data.clone(), Limits.DEFAULT);
    }

    /** A dense list of the items; a null item is the null value. */
    public static ListValue list(Value... items) {
        return DenseList.copyOf(items);
    }

    /** A dense list of the items of a collection, in its order. */
    public static ListValue listOf(Collection<? extends Value> items) {
        return DenseList.copyOf(items.toArray(new Value[0]));
    }

    /** The empty dense list. */
    public static ListValue emptyList() {
        return DenseList.EMPTY;
    }

    /**
     * The empty sparse list: a list keyed by index, which encodes as a map
     * with integer keys rather than as an array.
     */
    public static ListValue emptySparseList() {
        return SparseList.EMPTY;
    }

    /** The empty map. */
    public static MapValue emptyMap() {
        return MapValue.EMPTY;
    }

    /**
     * A new, empty mutable dense list: {@code append} and the like change the
     * list itself, and return it. It is the Go implementation's
     * {@code EmptyMutableList}; see {@link ListValue}.
     */
    public static ListValue mutableList() {
        return new MutableDenseList(DenseList.EMPTY);
    }

    /** A new, empty mutable sparse list. */
    public static ListValue mutableSparseList() {
        return new MutableSparseList(SparseList.EMPTY);
    }

    /**
     * A new, empty mutable map: {@code put} and the like change the map
     * itself, and return it. It is the Go implementation's
     * {@code EmptyMutableMap}; see {@link MapValue}.
     */
    public static MapValue mutableMap() {
        return MapValue.EMPTY.toMutable();
    }

    /** A map of the entries of a Java map; a null value is the null value. */
    public static MapValue mapOf(Map<String, ? extends Value> map) {
        final MapValue.Builder b = MapValue.builder();
        for (Map.Entry<String, ? extends Value> e : map.entrySet()) {
            b.put(e.getKey(), e.getValue());
        }
        return b.build();
    }

    // --- the value -----------------------------------------------------------

    /** The kind of the value. */
    public abstract Kind kind();

    /** Reports whether this is the null value. */
    public final boolean isNull() {
        return this == NullValue.NULL;
    }

    /**
     * Reports whether this is a mutable list or map. Any other value never
     * changes, nor does anything it holds, and is safe to share between
     * threads.
     */
    public boolean isMutable() {
        return false;
    }

    /**
     * The value itself, unless it is a mutable list or map: then an immutable
     * copy of what it holds now, with the mutable lists and maps inside it
     * copied the same way, which nothing that happens later changes.
     *
     * @throws ValueException when a mutable list or map holds itself
     */
    public Value toImmutable() {
        return this;
    }

    /**
     * Reports whether {@code o} is a value with the same canonical bytes: the
     * same kind and type holding the same thing. Numbers do not coerce (the
     * long 1, the double 1.0, the big integer 1 and the decimal 1 are four
     * values), doubles compare by their bits, decimals by unscaled integer and
     * scale, timestamps by instant, text and binary are different, and so are
     * a dense and a sparse list.
     */
    @Override
    public abstract boolean equals(Object o);

    @Override
    public abstract int hashCode();

    // --- canonical bytes -----------------------------------------------------

    abstract void pack(Packer p);

    /** The canonical MessagePack bytes of the value. */
    public final byte[] pack() {
        final Packer p = new Packer();
        try {
            pack(p);
        } catch (StackOverflowError e) {
            throw new ValueException("value is nested too deeply to pack");
        }
        return p.toByteArray();
    }

    /** Writes the canonical bytes to a stream. */
    public final void writeTo(OutputStream out) throws IOException {
        out.write(pack());
    }

    /** The canonical bytes in lower-case hexadecimal. */
    public final String hex() {
        return Hex.encode(pack());
    }

    /**
     * The digest of the canonical bytes. Equal values hash identically, in
     * every process and in both implementations.
     *
     * @param algorithm a {@link MessageDigest} algorithm, such as {@code SHA-256}
     */
    public final byte[] hash(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm).digest(pack());
        } catch (NoSuchAlgorithmException e) {
            throw new ValueException("no digest " + algorithm, e);
        }
    }

    /**
     * Reads the value at the start of the array. Bytes after it are not
     * looked at; an {@link Unpacker} tells where a value ends.
     *
     * @throws ValueException when the bytes are not a value or exceed a limit
     */
    public static Value unpack(byte[] buf) {
        return unpack(buf, 0, buf.length, Limits.DEFAULT);
    }

    /** Reads the value at the start of the array, within the limits. */
    public static Value unpack(byte[] buf, Limits limits) {
        return unpack(buf, 0, buf.length, limits);
    }

    /** Reads the value at {@code offset}, looking at no more than {@code length} bytes. */
    public static Value unpack(byte[] buf, int offset, int length) {
        return unpack(buf, offset, length, Limits.DEFAULT);
    }

    /** Reads the value at {@code offset}, within the limits. */
    public static Value unpack(byte[] buf, int offset, int length, Limits limits) {
        final Value v = new Unpacker(buf, offset, length).limits(limits).next();
        if (v == null) {
            throw new ValueException("unexpected end of input");
        }
        return v;
    }

    /**
     * Reads the next value of a stream, taking exactly the bytes of that value
     * from it, so that values written one after another are read one call at a
     * time. A stream that is not buffered is read a few bytes at a time.
     *
     * @return the value, or {@code null} at the end of the stream
     * @throws ValueException when the bytes are not a value or exceed a limit
     */
    public static Value read(InputStream in) throws IOException {
        return read(in, Limits.DEFAULT);
    }

    /** Reads the next value of a stream, within the limits. */
    public static Value read(InputStream in, Limits limits) throws IOException {
        return new Unpacker(in).limits(limits).readNext();
    }

    // --- the variables of the Go package ---------------------------------------------
    //
    // What the Go implementation keeps in package variables is kept here the
    // same way: one setting for the whole process, read wherever it applies,
    // on every thread. Set them when the program starts, as a Go program does,
    // not while values are being read or printed elsewhere.

    private static volatile boolean legacyBigNumbers;
    private static volatile String base64Prefix = "base64,";
    private static volatile String unknownPrefix = "data:application/x-msgpack-ext;";
    private static volatile char decimalExpDelim = 'x';
    private static volatile double precisionLevel = 0.00001;
    private static volatile int firstIndexKey = 0;

    /**
     * Sets what the text of a binary starts with, in {@link #toString()} and
     * {@link #jsonify()}: {@code base64,} unless it is set, followed by the
     * bytes in base64 without padding. {@link StringValue#parse(String)} and
     * the typed getters read text that starts with it back as binary. It is
     * the Go implementation's {@code Base64Prefix}.
     *
     * <p>It changes that text for the whole process and never the canonical
     * bytes, except through a map read from foreign bytes whose key is not
     * text: such a key becomes its text, here as in Go.
     */
    public static void setBase64Prefix(String prefix) {
        if (prefix == null) {
            throw new NullPointerException("prefix");
        }
        base64Prefix = prefix;
    }

    /** What the text of a binary starts with; see {@link #setBase64Prefix}. */
    public static String base64Prefix() {
        return base64Prefix;
    }

    /**
     * Sets what the text of an {@linkplain ExtensionValue extension} starts
     * with, in {@link #toString()} and {@link #jsonify()}:
     * {@code data:application/x-msgpack-ext;} unless it is set. It is followed
     * by {@link #base64Prefix()} and the type byte and the data in base64
     * without padding. It is the Go implementation's {@code UnknownPrefix}.
     */
    public static void setUnknownPrefix(String prefix) {
        if (prefix == null) {
            throw new NullPointerException("prefix");
        }
        unknownPrefix = prefix;
    }

    /** What the text of an extension starts with; see {@link #setUnknownPrefix}. */
    public static String unknownPrefix() {
        return unknownPrefix;
    }

    /**
     * Sets the character between the digits and the exponent in the text of a
     * decimal, which {@link #toString()} writes in hexadecimal:
     * {@code 0x01e2afx-03} is 123.567 with {@code x}, which it is unless it is
     * set. {@link NumberValue#parse(String)} looks for the same character. It
     * is the Go implementation's {@code DecimalExpDelim} and
     * {@code DecimalExpDelimStr}, which are one character here.
     *
     * @throws IllegalArgumentException for a character that is not ASCII
     */
    public static void setDecimalExpDelim(char delimiter) {
        if (delimiter >= 0x80) {
            throw new IllegalArgumentException("delimiter is not ASCII: U+" + Integer.toHexString(delimiter));
        }
        decimalExpDelim = delimiter;
    }

    /** The character before the exponent in the text of a decimal; see {@link #setDecimalExpDelim}. */
    public static char decimalExpDelim() {
        return decimalExpDelim;
    }

    /**
     * Sets how far apart two numbers may be and still be
     * {@linkplain NumberValue#approxEquals close}: 0.00001 unless it is set.
     * It is the Go implementation's {@code PrecisionLevel}.
     */
    public static void setPrecisionLevel(double level) {
        precisionLevel = level;
    }

    /** How far apart two close numbers may be; see {@link #setPrecisionLevel}. */
    public static double precisionLevel() {
        return precisionLevel;
    }

    /**
     * Sets the index {@link ListValue#append} gives the first value of an
     * empty sparse list: 0 unless it is set. It is the Go implementation's
     * {@code FirstIndexKey}.
     */
    public static void setFirstIndexKey(int index) {
        firstIndexKey = index;
    }

    /** The index of the first value appended to an empty sparse list; see {@link #setFirstIndexKey}. */
    public static int firstIndexKey() {
        return firstIndexKey;
    }

    /**
     * Makes every decoder read big integers and decimals in the layouts the Go
     * implementation wrote before its v1.5.0, or, with false, in the ones in
     * use since. It is the Go implementation's {@code LegacyBigNumbers}, and
     * like it a switch for the whole process, off unless it is set.
     *
     * <p>Before v1.5.0 the payload of a big integer (extension 1) was Go's gob
     * encoding of it, a byte of version and sign followed by the magnitude,
     * and that of a decimal (extension 2) a 4-byte exponent followed by that
     * encoding of the coefficient. Since then they are the two's-complement
     * layouts every implementation reads. The two cannot be told apart from
     * the bytes, so a decoder never guesses: it reads the old layouts while
     * this switch is on, and only then. Packing always writes the layouts in
     * use now, so migrating stored bytes is reading them with the switch on
     * and packing what was read:
     *
     * <pre>{@code
     * Value.setLegacyBigNumbers(true);
     * byte[] migrated = Value.unpack(stored).pack();
     * Value.setLegacyBigNumbers(false);
     * }</pre>
     *
     * <p>The switch is read by {@link #unpack(byte[])}, {@link #read(InputStream)},
     * {@link Unpacker}, {@link #ext(int, byte[])}, the {@code $ext} wrapper of
     * {@link #parseJson(String)} and the codecs, on every thread.
     */
    public static void setLegacyBigNumbers(boolean legacy) {
        legacyBigNumbers = legacy;
    }

    /** Whether the decoders read big numbers in the layouts of before v1.5.0; see {@link #setLegacyBigNumbers}. */
    public static boolean legacyBigNumbers() {
        return legacyBigNumbers;
    }

    /**
     * The big integer of a payload written before v1.5.0, whatever the switch
     * says: the Go implementation's {@code UnpackLegacyBigInt}.
     *
     * @throws ValueException when the payload is not Go's gob encoding of a big integer
     */
    public static BigInteger unpackLegacyBigInt(byte[] payload) {
        return Unpacker.legacyBigInt(payload, 0);
    }

    /**
     * The decimal of a payload written before v1.5.0, whatever the switch
     * says: the Go implementation's {@code UnpackLegacyDecimal}.
     *
     * @throws ValueException when the payload is not such a decimal, or its exponent exceeds the default limit
     */
    public static BigDecimal unpackLegacyDecimal(byte[] payload) {
        return Unpacker.legacyDecimal(payload, Limits.DEFAULT);
    }

    // --- sealing -------------------------------------------------------------

    /**
     * Seals the value for a recipient: its canonical bytes, encrypted so that
     * only the holder of the recipient's private key reads them, and
     * authenticated as coming from the holder of the sender's. It is the Go
     * implementation's {@code Seal}; see {@link Sealer}, which also makes the
     * keys, and which does the work the two keys share once when there are
     * several messages to seal.
     *
     * @param recipientPublicKey the 32-byte public key of the reader
     * @param senderPrivateKey the 32-byte private key of the writer
     * @throws ValueException when a key is not 32 bytes
     */
    public final byte[] seal(byte[] recipientPublicKey, byte[] senderPrivateKey) {
        return Sealer.of(recipientPublicKey, senderPrivateKey).seal(this);
    }

    /**
     * Opens bytes sealed for the holder of the recipient's private key by the
     * holder of the sender's, and reads the value in them.
     *
     * @throws ValueException when the bytes were not sealed between these two
     *         keys or were altered since, or what they hold is not a value
     */
    public static Value unseal(byte[] sealed, byte[] senderPublicKey, byte[] recipientPrivateKey) {
        return Sealer.of(senderPublicKey, recipientPrivateKey).unseal(sealed);
    }

    /** Opens sealed bytes and reads the value in them, within the limits. */
    public static Value unseal(byte[] sealed, byte[] senderPublicKey, byte[] recipientPrivateKey, Limits limits) {
        return Sealer.of(senderPublicKey, recipientPrivateKey).unseal(sealed, limits);
    }

    // --- by recursion, for the first levels --------------------------------------
    //
    // A list or a map prints, compares and hashes what it holds by recursion,
    // as packing does, for the first levels of a value, and goes on without
    // recursion past them. These are what it asks of a value it holds, with
    // how much of the allowance for recursion (Walk.deep) the way to that
    // value has taken: its depth for printing, and Walk.COMPARE times its
    // depth for comparing and hashing, which take that much more of the
    // stack a level. Any value but a list or a map answers as it would
    // anywhere.

    void jsonify(StringBuilder out, int depth) {
        jsonify(out);
    }

    boolean same(Value other, int spent) {
        return equals(other);
    }

    int hash(int spent) {
        return hashCode();
    }

    // --- text ----------------------------------------------------------------

    abstract void jsonify(StringBuilder out);

    /**
     * The value as people read it, which is the text the Go implementation's
     * {@code String()} gives: text as it is, binary as {@code base64,} and
     * unpadded base64, big integers and decimals in hexadecimal, a timestamp
     * as RFC 3339, a list or a map as {@link #jsonify()} writes it. It is not
     * canonical and not for parsing; {@link #toJson(JsonMode)} is the lossless
     * text.
     */
    @Override
    public abstract String toString();

    /**
     * The rendering of the Go implementation's {@code Jsonify}, which
     * value-rpc's JSON wire codec speaks: {@code toString} with text quoted.
     * It is JSON for every value: in text and in a map key a character that
     * is not printable is escaped, a byte that is not UTF-8 is written as
     * U+FFFD, and a double that is NaN or infinite is {@code null}. It does
     * not tell every kind apart; {@link #toJson(JsonMode)} does.
     */
    public final String jsonify() {
        final StringBuilder out = new StringBuilder();
        try {
            jsonify(out);
        } catch (StackOverflowError e) {
            throw new ValueException("value is nested too deeply to print");
        }
        return out.toString();
    }

    /**
     * The value as JSON. Plain JSON has numbers, strings, booleans, null,
     * arrays and objects; every other kind is an object with a single wrapper
     * key: {@code $long}, {@code $double}, {@code $bigint}, {@code $decimal},
     * {@code $bin}, {@code $timestamp}, {@code $sparse}, {@code $ext}, and
     * {@code $map} around a map whose only key is one of these.
     * {@link JsonMode#CANONICAL} wraps them all, so that
     * {@link #parseJson(String)} gives back this value;
     * {@link JsonMode#RELAXED} writes longs, finite doubles and decimals as
     * bare numbers.
     *
     * @throws ValueException when a text is not valid UTF-8, which has no JSON form
     */
    public final String toJson(JsonMode mode) {
        return toJson(mode, Limits.DEFAULT);
    }

    /** The value as JSON, nested no deeper than the limits allow. */
    public final String toJson(JsonMode mode, Limits limits) {
        return Json.format(this, mode, limits);
    }

    /**
     * Reads a value from JSON written in either mode. A bare number carries no
     * type and is a decimal with its exact digits, integers included; an
     * object whose only key is a wrapper key is the wrapped kind, and a
     * malformed wrapper fails rather than reading as a map. Duplicate keys,
     * lone surrogates, raw control characters and text after the value fail
     * too.
     */
    public static Value parseJson(String text) {
        return Json.parse(text, Limits.DEFAULT);
    }

    /** Reads a value from JSON, within the limits. */
    public static Value parseJson(String text, Limits limits) {
        return Json.parse(text, limits);
    }

    /** Reads a value from JSON in UTF-8. */
    public static Value parseJson(byte[] utf8) {
        return parseJson(utf8, Limits.DEFAULT);
    }

    /** Reads a value from JSON in UTF-8, within the limits. */
    public static Value parseJson(byte[] utf8, Limits limits) {
        if (!Utf8.isValid(utf8, 0, utf8.length)) {
            throw new ValueException("json: text is not valid UTF-8");
        }
        return Json.parse(new String(utf8, StandardCharsets.UTF_8), limits);
    }
}
