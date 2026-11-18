/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/** The null value: MessagePack's nil, the byte {@code c0}. */
public final class NullValue extends Value {

    /** The one null value. */
    public static final NullValue NULL = new NullValue();

    private NullValue() {
    }

    @Override
    public Kind kind() {
        return Kind.NULL;
    }

    @Override
    void pack(Packer p) {
        p.nil();
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append("null");
    }

    @Override
    public String toString() {
        return "null";
    }

    @Override
    public boolean equals(Object o) {
        return o == this;
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
