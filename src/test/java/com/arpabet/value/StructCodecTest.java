/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructCodecTest {

    static final class Inner {
        @Tag(1) StringValue text;
    }

    static final class Example {
        @Tag(100) Inner inner;
        @Tag(5) MapValue mapField;
        @Tag(3) StringValue stringField;
        @Tag(1) BoolValue boolField;
        @Tag(2) NumberValue numberField;
        @Tag(4) ListValue listField;
        @Tag(9) @Repeated List<StringValue> trace;
        @Tag(8) List<NumberValue> numbers;
    }

    @Test
    void packsByFieldNumber() {
        assertEquals("c0", Hex.encode(StructCodec.pack(null)));
        assertEquals("7b", Hex.encode(StructCodec.pack(Value.ofLong(123))));
        assertEquals("80", Hex.encode(StructCodec.pack(new Example())));

        final Example s = new Example();
        s.boolField = BoolValue.TRUE;
        s.numberField = Value.ofLong(123);
        s.stringField = Value.utf8("test");
        s.listField = Value.emptyList();
        s.mapField = Value.emptyMap();
        s.inner = new Inner();
        s.inner.text = Value.utf8("inner");
        final byte[] wire = StructCodec.pack(s);
        // The fields in the order of their numbers, whatever their order in the class.
        assertEquals("8601c3027b03a47465737404900580" + "6481" + "01a5696e6e6572", Hex.encode(wire));

        final Example d = StructCodec.unpack(wire, Example.class);
        assertEquals(s.boolField, d.boolField);
        assertEquals(s.numberField, d.numberField);
        assertEquals(s.stringField, d.stringField);
        assertEquals(s.listField, d.listField);
        assertEquals(s.mapField, d.mapField);
        assertEquals(s.inner.text, d.inner.text);
        assertNull(d.trace);

        // Read without the schema, a struct is a sparse list by field number.
        final ListValue list = (ListValue) Value.unpack(wire);
        assertTrue(list.isSparse());
        assertEquals(101, list.size());
        assertEquals(s.stringField, list.get(3));
        assertEquals(s.inner.text, list.getList(100).get(1));
    }

    @Test
    void repeatedFieldsAndLists() {
        final Example s = new Example();
        s.trace = Arrays.asList(Value.utf8("edge"), Value.utf8("relay"));
        s.numbers = Arrays.asList(Value.ofLong(1), Value.ofDouble(2));
        final byte[] wire = StructCodec.pack(s);
        assertEquals("83089201cb400000000000000009a46564676509a572656c6179", Hex.encode(wire));
        final Example d = StructCodec.unpack(wire, Example.class);
        assertEquals(s.trace, d.trace);
        assertEquals(s.numbers, d.numbers);
        // Without the schema the repeats are the values of one index.
        assertEquals(s.trace, ((ListValue) Value.unpack(wire)).select(9));

        // An entry met again: a list is replaced, a nested struct read into.
        final Example twice = StructCodec.unpack(Hex.decode("830891010892020364810" + "1a161"), Example.class);
        assertEquals(Arrays.asList(Value.ofLong(2), Value.ofLong(3)), twice.numbers);
        assertEquals(Value.utf8("a"), twice.inner.text);
        // A field number may come in any number form.
        assertEquals(BoolValue.TRUE, StructCodec.unpack(Hex.decode("81cb3ff0000000000000c3"), Example.class).boolField);
    }

    static final class Untagged {
        @Tag(1) StringValue a;
        StringValue b;
    }

    static final class SameTag {
        @Tag(1) StringValue a;
        @Tag(1) StringValue b;
    }

    static final class NotAValue {
        @Tag(1) String a;
    }

    static final class NoConstructor {
        @Tag(1) StringValue a;

        NoConstructor(StringValue a) {
            this.a = a;
        }
    }

    @Test
    void whatIsNotOfTheSchemaIsRefused() {
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("810763"), Example.class)); // unknown tag
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("8101a161"), Example.class)); // text for a bool
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("8103c0"), Example.class)); // null for a string
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("81a16101"), Example.class)); // a text key
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("9101"), Example.class)); // not a map
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("c0"), Example.class));
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("810801"), Example.class)); // not an array
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("8201c3"), Example.class)); // truncated
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("81648101c0"), Example.class));

        assertThrows(ValueException.class, () -> StructCodec.pack(new Untagged()));
        assertThrows(ValueException.class, () -> StructCodec.pack(new SameTag()));
        assertThrows(ValueException.class, () -> StructCodec.pack(new NotAValue()));
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("80"), NoConstructor.class));

        final Example nullElement = new Example();
        nullElement.numbers = Arrays.asList(Value.ofLong(1), null);
        assertThrows(ValueException.class, () -> StructCodec.pack(nullElement));

        final Limits shallow = Limits.DEFAULT.withMaxDepth(1);
        assertEquals(Value.utf8("a"), StructCodec.unpack(Hex.decode("81648101a161"), Example.class).inner.text);
        assertThrows(ValueException.class, () -> StructCodec.unpack(Hex.decode("81648101a161"), Example.class, shallow));
    }
}
