/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Binary. The array is never handed out or changed. */
final class RawString extends StringValue {

    private final byte[] value;

    /** Takes the array without a copy. */
    RawString(byte[] value) {
        this.value = value;
    }

    @Override
    public StringType type() {
        return StringType.RAW;
    }

    @Override
    public int size() {
        return value.length;
    }

    @Override
    public String utf8() {
        return Utf8.decode(value, 0, value.length);
    }

    @Override
    public byte[] raw() {
        return value.clone();
    }

    @Override
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(value).asReadOnlyBuffer();
    }

    /** Standard base64 with padding, the form of the JSON wrapper. */
    String base64() {
        return Base64.encodeStd(value);
    }

    @Override
    void pack(Packer p) {
        p.bin(value);
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append('"').append(BASE64_PREFIX).append(Base64.encodeRawStd(value)).append('"');
    }

    @Override
    public String toString() {
        return BASE64_PREFIX + Base64.encodeRawStd(value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawString && Arrays.equals(((RawString) o).value, value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value) ^ 0x524157;
    }
}
