/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the README says, checked. */
class ReadmeTest {

    @Test
    void values() {
        final MapValue payment = MapValue.builder()
                .put("amount", 100)
                .put("to", "alice")
                .build();
        final byte[] bytes = payment.pack();
        assertEquals("82a6616d6f756e7464a2746fa5616c696365", payment.hex());
        final Value back = Value.unpack(bytes);
        assertEquals(payment, back);
        assertEquals(100, ((MapValue) back).getNumber("amount").longValue());
    }

    @Test
    void theTableOfKinds() {
        assertEquals("c0", NullValue.NULL.hex());
        assertEquals("c3", Value.ofBool(true).hex());
        assertEquals("7b", Value.ofLong(123).hex());
        assertEquals("cb3ff8000000000000", Value.ofDouble(1.5).hex());
        assertEquals("d4017b", Value.ofBigInt(BigInteger.valueOf(123)).hex());
        assertEquals("c70602000000023039", Value.ofDecimal(new BigDecimal("123.45")).hex());
        assertEquals("a568656c6c6f", Value.utf8("hello").hex());
        assertEquals("c403010203", Value.raw(new byte[] {1, 2, 3}).hex());
        assertEquals("d6ff6ae44727", Value.timestamp(1793345319L, 0).hex());
        assertEquals("9201a178", Value.list(Value.ofLong(1), Value.utf8("x")).hex());
        assertEquals("82000a0532", Value.emptySparseList().putAt(0, Value.ofLong(10)).putAt(5, Value.ofLong(50)).hex());
        assertEquals("82a16101a16202", MapValue.builder().put("a", 1).put("b", 2).build().hex());
        assertEquals("d5070102", Value.ext(7, new byte[] {1, 2}).hex());
    }

    static final class License {
        @Name("_dom") @Sign("license") String domain = "license/v1";
        @Name("product") @Sign({"license", "audit"}) String product;
        @Name("seats") @Sign("license") int seats;
        @Name("sig") @OmitEmpty byte[] signature;
    }

    @Test
    void objects() {
        final License license = new License();
        license.product = "mailnite";
        license.seats = 5;

        final byte[] wire = ValueCodec.DEFAULT.pack(license);
        assertEquals("{\"_dom\": \"license/v1\",\"product\": \"mailnite\",\"seats\": 5}", Value.unpack(wire).toString());
        final byte[] toSign = ValueCodec.DEFAULT.signBytes(license, "license");
        assertArrayEquals(wire, toSign); // nothing but signed fields is set yet
        assertEquals("{\"product\": \"mailnite\"}", Value.unpack(ValueCodec.DEFAULT.signBytes(license, "audit")).toString());

        license.signature = new byte[] {1, 2, 3};
        assertArrayEquals(toSign, ValueCodec.DEFAULT.signBytes(license, "license"));
        final License back = ValueCodec.DEFAULT.unpack(ValueCodec.DEFAULT.pack(license), License.class);
        assertEquals("license/v1", back.domain);
        assertEquals("mailnite", back.product);
        assertEquals(5, back.seats);
        assertArrayEquals(new byte[] {1, 2, 3}, back.signature);
        assertNull(ValueCodec.DEFAULT.unpack(wire, License.class).signature);
    }

    static final class RpcFrame {
        @Tag(1) StringValue method;
        @Tag(2) StringValue payload;
        @Tag(3) @Repeated List<StringValue> trace;
    }

    @Test
    void numericSchema() {
        final RpcFrame frame = new RpcFrame();
        frame.method = Value.utf8("mail.deliver");
        frame.payload = Value.raw(new byte[] {1, 2, 3});
        frame.trace = Arrays.asList(Value.utf8("edge"), Value.utf8("relay"));

        final byte[] wire = StructCodec.pack(frame);
        assertEquals("8401ac6d61696c2e64656c6976657202c40301020303a46564676503a572656c6179", Hex.encode(wire));
        final RpcFrame back = StructCodec.unpack(wire, RpcFrame.class);
        assertEquals(frame.method, back.method);
        assertEquals(frame.payload, back.payload);
        assertEquals(frame.trace, back.trace);
    }

    @Test
    void untrustedInput() {
        final Limits tight = Limits.DEFAULT.withMaxDepth(32).withMaxByteLength(1 << 20);
        assertEquals(32, tight.maxDepth());
        assertEquals(1 << 20, tight.maxByteLength());
        assertEquals(1000, Limits.DEFAULT.maxDepth());
        assertEquals(16_777_216, Limits.DEFAULT.maxCollectionLength());
        assertEquals(1 << 30, Limits.DEFAULT.maxByteLength());
        assertEquals(1_048_576, Limits.DEFAULT.maxDecimalExponent());
        final byte[] deep = new byte[40];
        Arrays.fill(deep, (byte) 0x91);
        deep[39] = 0x01;
        assertTrue(Value.unpack(deep) instanceof ListValue);
        assertThrows(ValueException.class, () -> Value.unpack(deep, tight));
    }
}
