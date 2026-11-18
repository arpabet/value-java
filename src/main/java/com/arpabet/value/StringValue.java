/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.nio.ByteBuffer;

/**
 * A string: text ({@link StringType#UTF8}, MessagePack's str) or binary
 * ({@link StringType#RAW}, MessagePack's bin). The two are distinct in the
 * canonical form and never equal, even over the same bytes.
 */
public abstract sealed class StringValue extends Value permits Utf8String, RawString {

    StringValue() {
    }

    @Override
    public final Kind kind() {
        return Kind.STRING;
    }

    /** Text or binary. */
    public abstract StringType type();

    /** The length in bytes: of the UTF-8 encoding for text. */
    public abstract int size();

    /**
     * The string as text: the text itself, or the bytes of a binary read as
     * UTF-8. A byte that is not UTF-8 is the char U+DC80 to U+DCFF, so that the
     * bytes can be told from the text again.
     */
    public abstract String utf8();

    /** The bytes: of the UTF-8 encoding for text. The array is the caller's. */
    public abstract byte[] raw();

    /** The bytes in a read-only buffer, without a copy for a binary. */
    public abstract ByteBuffer asByteBuffer();

    /**
     * Reads a string as the typed getters do for a value that is not one:
     * text that starts with {@code base64,} (or what
     * {@link Value#setBase64Prefix} set) followed by unpadded base64 is the
     * binary it prints as, and any other text is itself.
     */
    public static StringValue parse(String s) {
        return Coerce.parseString(s);
    }
}
