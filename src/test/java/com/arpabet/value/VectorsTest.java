/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The conformance vectors of the canonical form: canonical.json is
 * testdata/canonical.json of go.arpabet.com/value, copied unchanged. Every
 * implementation must produce exactly these bytes for these values and decode
 * them back to equal values, give the String and JSON renderings where the
 * file specifies them, and write and read the canonical lossless JSON of each.
 */
class VectorsTest {

    static Value build(MapValue v) {
        final String kind = v.getString("kind").utf8();
        final String text = v.getString("text").utf8();
        switch (kind) {
            case "null":
                return NullValue.NULL;
            case "bool":
                return v.get("value");
            case "long":
                return Value.ofLong(Long.parseLong(text));
            case "double":
                return Value.ofDouble(Double.parseDouble(text));
            case "bigint":
                return Value.ofBigInt(new BigInteger(text));
            case "decimal":
                return Value.ofDecimal(new BigDecimal(text));
            case "str":
                return Value.utf8(text);
            case "bin":
                return Value.raw(Hex.decode(v.getString("hex").utf8()));
            case "timestamp":
                return Value.timestamp(v.getNumber("seconds").longValueExact(), v.getNumber("nanos").longValueExact());
            case "ext":
                return Value.ext(v.getNumber("tag").intValueExact(), Hex.decode(v.getString("hex").utf8()));
            case "list": {
                final ListValue items = v.getList("items");
                final Value[] out = new Value[items.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = build(items.getMap(i));
                }
                return Value.list(out);
            }
            case "map": {
                final MapValue.Builder b = MapValue.builder();
                for (MapValue.Entry e : v.getMap("entries")) {
                    b.put(e.key(), build((MapValue) e.value()));
                }
                return b.build();
            }
            default:
                throw new AssertionError("unknown vector kind " + kind);
        }
    }

    @Test
    void canonicalVectors() throws IOException {
        final byte[] data;
        try (InputStream in = VectorsTest.class.getResourceAsStream("/canonical.json")) {
            data = in.readAllBytes();
        }
        final ListValue vectors = ((MapValue) Value.parseJson(data)).getList("vectors");
        assertTrue(vectors.size() >= 80, "the file holds the vectors");

        final Set<String> seen = new HashSet<>();
        for (Value item : vectors) {
            final MapValue vec = (MapValue) item;
            final String name = vec.getString("name").utf8();
            assertTrue(seen.add(name), "duplicate vector " + name);
            final String hex = vec.getString("hex").utf8();

            final Value v = build(vec.getMap("value"));
            assertEquals(hex, v.hex(), "encoding of " + name);

            final Value back = Value.unpack(Hex.decode(hex));
            assertEquals(v, back, name + " decodes to an equal value");
            assertEquals(v.hashCode(), back.hashCode(), name);
            assertEquals(hex, back.hex(), "re-encoding of " + name);

            if (vec.containsKey("text")) {
                assertEquals(vec.getString("text").utf8(), v.toString(), "String of " + name);
            }
            if (vec.containsKey("json")) {
                assertEquals(vec.getString("json").utf8(), v.jsonify(), "JSON of " + name);
            }
            assertTrue(vec.containsKey("jsonCanonical"), name + " has its canonical JSON");
            final String canonical = vec.getString("jsonCanonical").utf8();
            assertEquals(canonical, v.toJson(JsonMode.CANONICAL), "canonical JSON of " + name);
            assertEquals(hex, Value.parseJson(canonical).hex(), "canonical JSON of " + name + " read back");
        }
        assertFalse(seen.isEmpty());
    }
}
