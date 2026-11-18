/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Seals values between two parties: authenticated public-key encryption with
 * a NaCl box (X25519, XSalsa20 and Poly1305), which is what the Go
 * implementation's {@code Seal} and {@code Unseal} do. Bytes sealed by either
 * implementation open in the other.
 *
 * <pre>{@code
 * byte[] alicePrivate = Sealer.newPrivateKey();
 * byte[] alicePublic = Sealer.publicKey(alicePrivate);
 * byte[] bobPrivate = Sealer.newPrivateKey();
 * byte[] bobPublic = Sealer.publicKey(bobPrivate);
 *
 * byte[] sealed = Value.utf8("secret message").seal(bobPublic, alicePrivate);
 * Value opened = Value.unseal(sealed, alicePublic, bobPrivate);
 * }</pre>
 *
 * <p>A sealer is the pair of a peer's public key and one's own private key
 * with the key agreement between them already done, which is most of the work
 * of sealing: keep one per peer rather than calling {@link Value#seal} for
 * every message. Both parties arrive at the same sealer, so each opens what
 * the other sealed.
 *
 * <pre>{@code
 * Sealer toBob = Sealer.of(bobPublic, alicePrivate);
 * byte[] sealed = toBob.seal(message);          // Alice
 * Value opened = Sealer.of(alicePublic, bobPrivate).unseal(sealed);   // Bob
 * }</pre>
 *
 * <p>Sealed bytes are a random nonce of 24 bytes, a tag of 16 bytes and the
 * encrypted canonical bytes of the value, {@value #OVERHEAD} bytes more than
 * what they seal; the length of the value is not hidden. Opening proves that
 * the bytes were sealed with one of the two private keys and not altered
 * since. That is not a signature: either party can seal anything the other
 * could have, so sealed bytes convince nobody else.
 *
 * <p>Keys are 32 bytes. A private key is any 32 random bytes, and the public
 * key is derived from it; keep the private key and give the public key out. A
 * sealer is immutable and safe to share between threads.
 *
 * <p>Any 32 bytes are taken as a public key, as NaCl and the Go implementation
 * take them. A few of them are nobody's key: with 32 zero bytes, for one, the
 * two sides agree on the same key whatever the private key is, so what is
 * sealed to it anyone can open. No key derived from a private key is one of
 * these, and a sealer does not look for them. What protects a message is
 * knowing whose public key it is sealed to, as with any public key.
 */
public final class Sealer {

    /** The length of a public key and of a private key, in bytes. */
    public static final int KEY_LENGTH = 32;

    /** How many bytes longer sealed bytes are than what they seal. */
    public static final int OVERHEAD = 40;

    private static final int NONCE = 24;

    /** Made when a key or a nonce is first needed, not when the class loads. */
    private static final class Entropy {
        static final SecureRandom INSTANCE = new SecureRandom();
    }

    /** The key the two parties share. It never leaves the sealer. */
    private final byte[] key;

    private Sealer(byte[] key) {
        this.key = key;
    }

    /** A new private key: 32 bytes from the platform's secure random source. */
    public static byte[] newPrivateKey() {
        final byte[] key = new byte[KEY_LENGTH];
        Entropy.INSTANCE.nextBytes(key);
        return key;
    }

    /**
     * The public key of a private key.
     *
     * @throws ValueException when the key is not 32 bytes
     */
    public static byte[] publicKey(byte[] privateKey) {
        check(privateKey, "private key");
        final byte[] out = new byte[KEY_LENGTH];
        Curve25519.scalarBase(out, privateKey);
        return out;
    }

    /**
     * The sealer between a peer's public key and one's own private key.
     *
     * @throws ValueException when a key is not 32 bytes
     */
    public static Sealer of(byte[] publicKey, byte[] privateKey) {
        check(publicKey, "public key");
        check(privateKey, "private key");
        final byte[] shared = new byte[KEY_LENGTH];
        Curve25519.scalarMult(shared, privateKey, publicKey);
        final byte[] key = new byte[KEY_LENGTH];
        Salsa20.hsalsa20(key, shared, new byte[16], 0);
        Arrays.fill(shared, (byte) 0);
        return new Sealer(key);
    }

    /** The sealer of a key the two parties already share, which is what a box is once the keys have agreed. */
    static Sealer ofSharedKey(byte[] key) {
        check(key, "shared key");
        return new Sealer(key.clone());
    }

    private static void check(byte[] key, String what) {
        if (key == null) {
            throw new NullPointerException(what);
        }
        if (key.length != KEY_LENGTH) {
            throw new ValueException(what + " must be " + KEY_LENGTH + " bytes, got " + key.length);
        }
    }

    /** Seals the canonical bytes of a value under a new random nonce. */
    public byte[] seal(Value value) {
        final byte[] plain = value.pack();
        return box(nonce(), plain, 0, plain.length);
    }

    /**
     * Opens sealed bytes and reads the value in them.
     *
     * @throws ValueException when the bytes were not sealed between these two
     *         keys or were altered, or what they hold is not a value
     */
    public Value unseal(byte[] sealed) {
        return unseal(sealed, Limits.DEFAULT);
    }

    /** Opens sealed bytes and reads the value in them, within the limits. */
    public Value unseal(byte[] sealed, Limits limits) {
        return Value.unpack(unsealBytes(sealed), limits);
    }

    /**
     * Seals bytes that are not the bytes of a value, such as an object packed
     * by a {@link StructCodec}, in the same form.
     */
    public byte[] sealBytes(byte[] plain) {
        return box(nonce(), plain, 0, plain.length);
    }

    /**
     * Opens sealed bytes and gives what they hold.
     *
     * @throws ValueException when the bytes were not sealed between these two keys or were altered
     */
    public byte[] unsealBytes(byte[] sealed) {
        if (sealed.length < OVERHEAD) {
            throw failed();
        }
        final int len = sealed.length - OVERHEAD;
        final byte[] sub = new byte[KEY_LENGTH];
        final byte[] first = new byte[Salsa20.BLOCK];
        try {
            Salsa20.hsalsa20(sub, key, sealed, 0);
            Salsa20.block(first, sub, sealed, 16, 0);
            if (!Poly1305.verify(sealed, NONCE, sealed, OVERHEAD, len, first)) {
                throw failed();
            }
            final byte[] out = new byte[len];
            final int head = Math.min(len, 32);
            for (int i = 0; i < head; i++) {
                out[i] = (byte) (sealed[OVERHEAD + i] ^ first[32 + i]);
            }
            Salsa20.xor(out, head, sealed, OVERHEAD + head, len - head, sub, sealed, 16, 1);
            return out;
        } finally {
            Arrays.fill(sub, (byte) 0);
            Arrays.fill(first, (byte) 0);
        }
    }

    private static ValueException failed() {
        return new ValueException("unseal error");
    }

    private static byte[] nonce() {
        final byte[] nonce = new byte[NONCE];
        Entropy.INSTANCE.nextBytes(nonce);
        return nonce;
    }

    /**
     * The box of {@code len} bytes of {@code m} under a nonce: the nonce, the
     * tag and the encrypted bytes. A nonce must never seal two messages.
     *
     * <p>The key of the message is derived from the shared key and the first
     * 16 bytes of the nonce; its stream, under the last 8, gives the one-time
     * key of the tag in its first 32 bytes and encrypts with the rest.
     */
    byte[] box(byte[] nonce, byte[] m, int off, int len) {
        final byte[] out = new byte[OVERHEAD + len];
        System.arraycopy(nonce, 0, out, 0, NONCE);
        final byte[] sub = new byte[KEY_LENGTH];
        final byte[] first = new byte[Salsa20.BLOCK];
        Salsa20.hsalsa20(sub, key, nonce, 0);
        Salsa20.block(first, sub, nonce, 16, 0);
        final int head = Math.min(len, 32);
        for (int i = 0; i < head; i++) {
            out[OVERHEAD + i] = (byte) (m[off + i] ^ first[32 + i]);
        }
        Salsa20.xor(out, OVERHEAD + head, m, off + head, len - head, sub, nonce, 16, 1);
        Poly1305.mac(out, NONCE, out, OVERHEAD, len, first);
        Arrays.fill(sub, (byte) 0);
        Arrays.fill(first, (byte) 0);
        return out;
    }
}
