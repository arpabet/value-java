/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/** A boolean: MessagePack's true ({@code c3}) and false ({@code c2}). */
public final class BoolValue extends Value {

    public static final BoolValue TRUE = new BoolValue(true);
    public static final BoolValue FALSE = new BoolValue(false);

    private final boolean value;

    private BoolValue(boolean value) {
        this.value = value;
    }

    public static BoolValue of(boolean b) {
        return b ? TRUE : FALSE;
    }

    /**
     * Reads a boolean as the typed getters do for a value that is not one:
     * {@code 1}, {@code t}, {@code T}, {@code TRUE}, {@code true} and
     * {@code True} are true, and anything else is false.
     */
    public static BoolValue parse(String s) {
        return of(Coerce.parseBoolean(s));
    }

    public boolean booleanValue() {
        return value;
    }

    @Override
    public Kind kind() {
        return Kind.BOOL;
    }

    @Override
    void pack(Packer p) {
        p.bool(value);
    }

    @Override
    void jsonify(StringBuilder out) {
        out.append(value);
    }

    @Override
    public String toString() {
        return value ? "true" : "false";
    }

    @Override
    public boolean equals(Object o) {
        return o == this;
    }

    @Override
    public int hashCode() {
        return value ? 1231 : 1237;
    }
}
