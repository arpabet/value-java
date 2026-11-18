/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Arrays;

/**
 * A MessagePack extension the format does not define: its type and its data,
 * kept as they are and written back byte for byte, so that a reader never
 * drops an extension it does not know. The kind is {@link Kind#UNKNOWN}.
 *
 * <p>The types 1 (big integer), 2 (decimal) and -1 (timestamp) are defined
 * and are never an extension value; {@link Value#ext(int, byte[])} gives the
 * value of any type.
 */
public final class ExtensionValue extends Value {

    static final int BIGINT = 1;
    static final int DECIMAL = 2;
    static final int TIMESTAMP = -1;

    /**
     * What the text of an extension starts with, in {@link #toString()} and
     * {@link #jsonify()}: it is followed by {@link StringValue#BASE64_PREFIX}
     * and the type byte and the data in base64 without padding. It is the Go
     * implementation's {@code UnknownPrefix}.
     */
    public static final String UNKNOWN_PREFIX = "data:application/x-msgpack-ext;";

    private final int type;
    private final byte[] data;

    /** Takes the data without a copy. */
    ExtensionValue(int type, byte[] data) {
        this.type = type;
        this.data = data;
    }

    /** The extension type, from -128 to 127. */
    public int type() {
        return type;
    }

    /** The data of the extension. The array is the caller's. */
    public byte[] data() {
        return data.clone();
    }

    /** The length of the data in bytes. */
    public int size() {
        return data.length;
    }

    String base64() {
        return Base64.encodeStd(data);
    }

    @Override
    public Kind kind() {
        return Kind.UNKNOWN;
    }

    @Override
    void pack(Packer p) {
        p.ext(type, data);
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append('"').append(toString()).append('"');
    }

    /** {@code data:application/x-msgpack-ext;base64,} and the unpadded base64 of the type byte and the data. */
    @Override
    public String toString() {
        final byte[] all = new byte[1 + data.length];
        all[0] = (byte) type;
        System.arraycopy(data, 0, all, 1, data.length);
        return UNKNOWN_PREFIX + StringValue.BASE64_PREFIX + Base64.encodeRawStd(all);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ExtensionValue)) {
            return false;
        }
        final ExtensionValue x = (ExtensionValue) o;
        return x.type == type && Arrays.equals(x.data, data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data) * 31 + type;
    }
}
