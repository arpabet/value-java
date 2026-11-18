/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.nio.ByteBuffer;

/** Text, in its canonical form (see {@link Utf8}). */
final class Utf8String extends StringValue {

    static final Utf8String EMPTY = new Utf8String("");

    final String value;

    /** The string must be canonical. */
    Utf8String(String value) {
        this.value = value;
    }

    @Override
    public StringType type() {
        return StringType.UTF8;
    }

    @Override
    public int size() {
        return Utf8.encodedLength(value);
    }

    @Override
    public String utf8() {
        return value;
    }

    @Override
    public byte[] raw() {
        return Utf8.encode(value);
    }

    @Override
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(Utf8.encode(value)).asReadOnlyBuffer();
    }

    @Override
    void pack(Packer p) {
        p.str(value);
    }

    @Override
    void jsonify(StringBuilder out) {
        GoQuote.quote(out, value);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Utf8String && ((Utf8String) o).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
