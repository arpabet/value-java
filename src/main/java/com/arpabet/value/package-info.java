/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Deterministic, canonical serialization: the Java implementation of the Go
 * package {@code go.arpabet.com/value}, binary compatible with it.
 *
 * <p>{@link com.arpabet.value.Value} is an immutable, dynamically typed value
 * whose {@linkplain com.arpabet.value.Value#pack() canonical MessagePack
 * bytes} are always the same for the same value, so that they can be hashed
 * and signed. {@link com.arpabet.value.ValueCodec} maps Java objects to values
 * by name and gives the bytes to sign for them;
 * {@link com.arpabet.value.StructCodec} maps them by field number.
 * {@link com.arpabet.value.Value#toJson(JsonMode) Lossless JSON} carries every
 * kind through text, and {@link com.arpabet.value.Limits} bounds what decoding
 * accepts from an untrusted source.
 *
 * <p>The canonical form is specified by CANONICAL.md of the Go repository.
 */
package com.arpabet.value;
