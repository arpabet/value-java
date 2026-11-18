/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Sealing as an application meets it; the bytes themselves are checked against Go in SealGoldenTest. */
class SealerTest {

    private static final byte[] ALICE = Sealer.newPrivateKey();
    private static final byte[] BOB = Sealer.newPrivateKey();
    private static final byte[] ALICE_PUBLIC = Sealer.publicKey(ALICE);
    private static final byte[] BOB_PUBLIC = Sealer.publicKey(BOB);

    private static final MapValue MESSAGE = MapValue.builder()
            .put("to", "bob")
            .put("amount", 100)
            .put("memo", new byte[] {1, 2, 3})
            .build();

    @Test
    void whatOneSealsTheOtherOpens() {
        final byte[] sealed = MESSAGE.seal(BOB_PUBLIC, ALICE);
        assertEquals(MESSAGE.pack().length + Sealer.OVERHEAD, sealed.length);
        assertEquals(MESSAGE, Value.unseal(sealed, ALICE_PUBLIC, BOB));
        // The two pairs of keys agree on one key: the writer opens its own.
        assertEquals(MESSAGE, Value.unseal(sealed, BOB_PUBLIC, ALICE));

        final Sealer alice = Sealer.of(BOB_PUBLIC, ALICE);
        final Sealer bob = Sealer.of(ALICE_PUBLIC, BOB);
        assertEquals(MESSAGE, bob.unseal(alice.seal(MESSAGE)));
        assertEquals(MESSAGE, alice.unseal(bob.seal(MESSAGE)));
        assertEquals(MESSAGE, bob.unseal(sealed));
        assertEquals(NullValue.NULL, bob.unseal(alice.seal(NullValue.NULL)));

        // A nonce of its own every time: the same value never seals to the same bytes.
        assertFalse(Arrays.equals(alice.seal(MESSAGE), alice.seal(MESSAGE)));
        // Nothing of the value shows.
        final byte[] plain = MESSAGE.pack();
        final byte[] again = alice.seal(MESSAGE);
        int same = 0;
        for (int i = 0; i < plain.length; i++) {
            same += plain[i] == again[Sealer.OVERHEAD + i] ? 1 : 0;
        }
        assertTrue(same < plain.length / 2, "sealed bytes look like the value");
    }

    @Test
    void anythingAlteredDoesNotOpen() {
        final Sealer bob = Sealer.of(ALICE_PUBLIC, BOB);
        final byte[] sealed = MESSAGE.seal(BOB_PUBLIC, ALICE);
        for (int i = 0; i < sealed.length; i++) {
            for (int bit = 0; bit < 8; bit += 3) {
                final byte[] altered = sealed.clone();
                altered[i] ^= (byte) (1 << bit);
                final ValueException e = assertThrows(ValueException.class, () -> bob.unseal(altered), "byte " + i);
                assertEquals("unseal error", e.getMessage());
            }
        }
        for (int n = 0; n < sealed.length; n++) {
            final byte[] cut = Arrays.copyOf(sealed, n);
            assertThrows(ValueException.class, () -> bob.unseal(cut), "length " + n);
            assertThrows(ValueException.class, () -> bob.unsealBytes(cut), "length " + n);
        }
        assertThrows(ValueException.class, () -> bob.unseal(Arrays.copyOf(sealed, sealed.length + 1)));

        // Other keys.
        final byte[] eve = Sealer.newPrivateKey();
        assertThrows(ValueException.class, () -> Value.unseal(sealed, ALICE_PUBLIC, eve));
        assertThrows(ValueException.class, () -> Value.unseal(sealed, Sealer.publicKey(eve), BOB));
        assertThrows(ValueException.class, () -> Value.unseal(sealed, ALICE_PUBLIC, ALICE));
    }

    @Test
    void keys() {
        assertEquals(32, Sealer.KEY_LENGTH);
        assertEquals(32, ALICE.length);
        assertEquals(32, ALICE_PUBLIC.length);
        assertFalse(Arrays.equals(ALICE, BOB));
        assertFalse(Arrays.equals(Sealer.newPrivateKey(), Sealer.newPrivateKey()));
        assertArrayEquals(ALICE_PUBLIC, Sealer.publicKey(ALICE));
        assertFalse(Arrays.equals(ALICE, ALICE_PUBLIC));

        // The bits of a private key that the agreement sets itself do not matter.
        final byte[] other = ALICE.clone();
        other[0] ^= 7;
        other[31] ^= (byte) 0x80;
        assertArrayEquals(ALICE_PUBLIC, Sealer.publicKey(other));

        for (int length : new int[] {0, 1, 31, 33, 64}) {
            final byte[] wrong = new byte[length];
            assertThrows(ValueException.class, () -> Sealer.publicKey(wrong));
            assertThrows(ValueException.class, () -> Sealer.of(wrong, ALICE));
            assertThrows(ValueException.class, () -> Sealer.of(BOB_PUBLIC, wrong));
            assertThrows(ValueException.class, () -> MESSAGE.seal(wrong, ALICE));
            assertThrows(ValueException.class, () -> Value.unseal(new byte[60], wrong, BOB));
        }
        assertThrows(NullPointerException.class, () -> Sealer.of(null, ALICE));
        assertThrows(NullPointerException.class, () -> Sealer.of(BOB_PUBLIC, null));
        assertThrows(NullPointerException.class, () -> Sealer.publicKey(null));

        // A key that gives every private key the same shared key is not a key.
        for (String small : new String[] {
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        }) {
            final ValueException e = assertThrows(ValueException.class, () -> Sealer.of(Hex.decode(small), ALICE), small);
            assertTrue(e.getMessage().contains("small order"), e.getMessage());
            assertThrows(ValueException.class, () -> MESSAGE.seal(Hex.decode(small), ALICE), small);
        }
    }

    static final class Frame {
        @Tag(1) StringValue method;
        @Tag(2) StringValue payload;
    }

    @Test
    void bytesThatAreNotAValue() {
        final Sealer alice = Sealer.of(BOB_PUBLIC, ALICE);
        final Sealer bob = Sealer.of(ALICE_PUBLIC, BOB);

        final Frame frame = new Frame();
        frame.method = Value.utf8("transfer");
        frame.payload = Value.raw(MESSAGE.pack());
        final byte[] sealed = alice.sealBytes(StructCodec.pack(frame));
        final Frame back = StructCodec.unpack(bob.unsealBytes(sealed), Frame.class);
        assertEquals("transfer", back.method.utf8());
        assertEquals(MESSAGE, Value.unpack(back.payload.raw()));

        // A value sealed is its canonical bytes sealed.
        assertArrayEquals(MESSAGE.pack(), bob.unsealBytes(alice.seal(MESSAGE)));
        assertEquals(MESSAGE, bob.unseal(alice.sealBytes(MESSAGE.pack())));

        // No bytes at all seal and open; they are not a value.
        final byte[] empty = alice.sealBytes(new byte[0]);
        assertEquals(Sealer.OVERHEAD, empty.length);
        assertEquals(0, bob.unsealBytes(empty).length);
        assertThrows(ValueException.class, () -> bob.unseal(empty));
        // Nor is every authentic message one.
        assertThrows(ValueException.class, () -> bob.unseal(alice.sealBytes(new byte[] {(byte) 0x92, 1})));
        // As when unpacking, what follows the first value is not looked at.
        assertEquals(Value.ofLong(1), bob.unseal(alice.sealBytes(new byte[] {1, (byte) 0xff})));

        // Every length around the blocks of the cipher and of the tag.
        for (int n = 0; n < 200; n++) {
            final byte[] plain = new byte[n];
            for (int i = 0; i < n; i++) {
                plain[i] = (byte) (i * 31 + n);
            }
            assertArrayEquals(plain, bob.unsealBytes(alice.sealBytes(plain)), "length " + n);
        }
        final byte[] large = new byte[1 << 20];
        Arrays.fill(large, (byte) 0x5a);
        assertArrayEquals(large, bob.unsealBytes(alice.sealBytes(large)));
    }

    @Test
    void theLimitsOfDecodingApplyToWhatIsOpened() {
        final Value deep = Value.list(Value.list(Value.list(Value.ofLong(1))));
        final byte[] sealed = deep.seal(BOB_PUBLIC, ALICE);
        assertEquals(deep, Value.unseal(sealed, ALICE_PUBLIC, BOB, Limits.DEFAULT.withMaxDepth(3)));
        assertThrows(ValueException.class, () -> Value.unseal(sealed, ALICE_PUBLIC, BOB, Limits.DEFAULT.withMaxDepth(2)));
        assertThrows(ValueException.class,
                () -> Sealer.of(ALICE_PUBLIC, BOB).unseal(sealed, Limits.DEFAULT.withMaxCollectionLength(0).withMaxDepth(1)));
    }

    @Test
    void aSealerIsSharedBetweenThreads() throws Exception {
        final Sealer alice = Sealer.of(BOB_PUBLIC, ALICE);
        final Sealer bob = Sealer.of(ALICE_PUBLIC, BOB);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int thread = t;
                results.add(pool.submit(new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        for (int i = 0; i < 2000; i++) {
                            final Value v = Value.list(Value.ofLong(thread), Value.ofLong(i), Value.utf8("x".repeat(i % 97)));
                            if (!v.equals(bob.unseal(alice.seal(v)))) {
                                return false;
                            }
                        }
                        return true;
                    }
                }));
            }
            for (Future<Boolean> result : results) {
                assertTrue(result.get());
            }
        } finally {
            pool.shutdown();
        }
    }
}
