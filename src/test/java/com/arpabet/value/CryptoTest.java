/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Random;
import javax.crypto.KeyAgreement;
import org.junit.jupiter.api.Test;

/**
 * The primitives under a sealed value, against the vectors of their
 * specifications and against independent implementations: BigInteger for the
 * arithmetic of the field, and the JDK's X25519.
 */
class CryptoTest {

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

    private static byte[] hex(String s) {
        return Hex.decode(s);
    }

    private static String x25519(String scalar, String point) {
        final byte[] out = new byte[32];
        Curve25519.scalarMult(out, hex(scalar), hex(point));
        return Hex.encode(out);
    }

    // --- X25519 ------------------------------------------------------------

    @Test
    void x25519VectorsOfRfc7748() {
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
                x25519("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
                        "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"));
        assertEquals("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
                x25519("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d",
                        "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"));
    }

    @Test
    void x25519IteratedAsRfc7748Tells() {
        byte[] k = hex("0900000000000000000000000000000000000000000000000000000000000000");
        byte[] u = k.clone();
        for (int i = 1; i <= 1000; i++) {
            final byte[] out = new byte[32];
            Curve25519.scalarMult(out, k, u);
            u = k;
            k = out;
            if (i == 1) {
                assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", Hex.encode(k));
            }
        }
        assertEquals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51", Hex.encode(k));
    }

    @Test
    void diffieHellmanOfRfc7748() {
        final byte[] alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        final byte[] bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        final String alicePublic = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
        final String bobPublic = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";
        assertEquals(alicePublic, Hex.encode(Sealer.publicKey(alice)));
        assertEquals(bobPublic, Hex.encode(Sealer.publicKey(bob)));
        final String shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";
        assertEquals(shared, x25519(Hex.encode(alice), bobPublic));
        assertEquals(shared, x25519(Hex.encode(bob), alicePublic));
    }

    /** The JDK computes the same function; it takes the coordinate as a number and the scalar as bytes. */
    @Test
    void x25519AgreesWithTheJdk() throws Exception {
        final KeyFactory factory = KeyFactory.getInstance("X25519");
        final Random random = new Random(20261118);
        for (int i = 0; i < 2000; i++) {
            final byte[] scalar = new byte[32];
            final byte[] point = new byte[32];
            random.nextBytes(scalar);
            random.nextBytes(point);
            if (i % 7 == 0) {
                // The public key of another scalar, as keys are in use.
                Curve25519.scalarBase(point, point.clone());
            }
            final BigInteger u = little(point).clearBit(255);
            final KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(factory.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar)));
            agreement.doPhase(factory.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, u)), true);
            final byte[] want = agreement.generateSecret();

            final byte[] got = new byte[32];
            assertTrue(Curve25519.scalarMult(got, scalar, point));
            assertArrayEquals(want, got, "scalar " + Hex.encode(scalar) + " point " + Hex.encode(point));

            // And the public key is the function of the base point, whose coordinate is 9.
            final KeyAgreement base = KeyAgreement.getInstance("X25519");
            base.init(factory.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar)));
            base.doPhase(factory.generatePublic(
                    new XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9))), true);
            assertArrayEquals(base.generateSecret(), Sealer.publicKey(scalar));
        }
    }

    @Test
    void pointsOfSmallOrderGiveZero() {
        final String[] small = {
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            // The same points with the bit above the coordinate set.
            "0000000000000000000000000000000000000000000000000000000000000080",
            "0100000000000000000000000000000000000000000000000000000000000080",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        };
        final byte[] scalar = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        for (String point : small) {
            final byte[] out = new byte[32];
            assertFalse(Curve25519.scalarMult(out, scalar, hex(point)), point);
            assertArrayEquals(new byte[32], out, point);
        }
    }

    // --- the field -----------------------------------------------------------

    private static BigInteger little(byte[] b) {
        final byte[] big = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) {
            big[b.length - i] = b[i];
        }
        return new BigInteger(big);
    }

    /** The integer the limbs stand for, which may be negative or above the modulus. */
    private static BigInteger number(long[] h) {
        BigInteger n = BigInteger.ZERO;
        for (int i = 0; i < 10; i++) {
            n = n.add(BigInteger.valueOf(h[i]).shiftLeft((51 * i + 1) / 2));
        }
        return n;
    }

    private static BigInteger canonical(long[] h) {
        final byte[] out = new byte[32];
        Curve25519.toBytes(out, h);
        final BigInteger n = little(out);
        assertTrue(n.compareTo(P) < 0, "not reduced: " + n.toString(16));
        return n;
    }

    /** Limbs as large as the operands of a multiplication get, of either sign. */
    private static long[] limbs(Random random, int kind) {
        final long bound = (1L << 27) + (1L << 19);
        final long[] h = new long[10];
        for (int i = 0; i < 10; i++) {
            switch (kind) {
                case 0:
                    h[i] = bound;
                    break;
                case 1:
                    h[i] = -bound;
                    break;
                case 2:
                    h[i] = (i & 1) == 0 ? bound : -bound;
                    break;
                case 3:
                    h[i] = random.nextBoolean() ? bound : -bound;
                    break;
                case 4:
                    h[i] = random.nextInt(3) - 1; // tiny, around zero
                    break;
                default:
                    h[i] = (random.nextLong() % (bound + 1));
                    break;
            }
        }
        return h;
    }

    @Test
    void fieldArithmeticAgreesWithBigInteger() {
        final Random random = new Random(7);
        for (int round = 0; round < 20000; round++) {
            final long[] f = limbs(random, round % 8);
            final long[] g = limbs(random, (round / 8) % 8);
            final BigInteger a = number(f);
            final BigInteger b = number(g);

            final long[] h = new long[10];
            Curve25519.mul(h, f, g);
            assertEquals(a.multiply(b).mod(P), canonical(h), "mul");
            for (int i = 0; i < 10; i++) {
                assertTrue(Math.abs(h[i]) <= (1L << 26) + (1L << 17), "a product is not carried");
            }

            Curve25519.sq(h, f);
            assertEquals(a.multiply(a).mod(P), canonical(h), "sq");

            Curve25519.add(h, f, g);
            assertEquals(a.add(b).mod(P), canonical(h), "add");
            Curve25519.sub(h, f, g);
            assertEquals(a.subtract(b).mod(P), canonical(h), "sub");

            // In place.
            final long[] ff = f.clone();
            Curve25519.mul(ff, ff, g);
            assertEquals(a.multiply(b).mod(P), canonical(ff), "mul in place");
            final long[] gg = g.clone();
            Curve25519.sq(gg, gg);
            assertEquals(b.multiply(b).mod(P), canonical(gg), "sq in place");
        }
    }

    @Test
    void inversionAgreesWithBigInteger() {
        final Random random = new Random(11);
        for (int round = 0; round < 300; round++) {
            final long[] f = limbs(random, 5);
            Curve25519.carry(f);
            final BigInteger a = number(f).mod(P);
            final long[] h = new long[10];
            Curve25519.invert(h, f);
            assertEquals(a.signum() == 0 ? BigInteger.ZERO : a.modInverse(P), canonical(h));
        }
        final long[] zero = new long[10];
        Curve25519.invert(zero, zero);
        assertEquals(BigInteger.ZERO, canonical(zero));
    }

    /** Every number near a multiple of the modulus, however its limbs are spread, comes out reduced. */
    @Test
    void bytesOfAnElementAreTheReducedNumber() {
        final Random random = new Random(13);
        final BigInteger[] around = {
            BigInteger.ZERO, P, P.shiftLeft(1), P.negate(), P.multiply(BigInteger.valueOf(3)),
            BigInteger.ONE.shiftLeft(255), BigInteger.ONE.shiftLeft(256), P.multiply(BigInteger.valueOf(-2)),
        };
        for (BigInteger center : around) {
            for (int delta = -40; delta <= 40; delta++) {
                final BigInteger n = center.add(BigInteger.valueOf(delta));
                for (int spread = 0; spread < 6; spread++) {
                    final long[] h = split(n, random, spread);
                    assertEquals(n, number(h));
                    assertEquals(n.mod(P), canonical(h), "bytes of " + n.toString(16));
                }
            }
        }
        // And what is read from bytes is the number they are, without the top bit.
        for (int i = 0; i < 2000; i++) {
            final byte[] b = new byte[32];
            random.nextBytes(b);
            if (i % 5 == 0) {
                java.util.Arrays.fill(b, (byte) 0xFF);
                b[0] = (byte) (0xDA + i % 40);
            }
            final long[] h = new long[10];
            Curve25519.fromBytes(h, b);
            assertEquals(little(b).clearBit(255), number(h));
            assertEquals(little(b).clearBit(255).mod(P), canonical(h));
        }
    }

    /** Limbs that stand for a number, with random amounts moved between neighbours. */
    private static long[] split(BigInteger n, Random random, int spread) {
        final long[] h = new long[10];
        BigInteger rest = n;
        for (int i = 0; i < 9; i++) {
            final int width = 26 - (i & 1);
            final BigInteger[] qr = rest.divideAndRemainder(BigInteger.ONE.shiftLeft(width));
            BigInteger q = qr[0];
            BigInteger r = qr[1];
            if (r.signum() < 0) {
                r = r.add(BigInteger.ONE.shiftLeft(width));
                q = q.subtract(BigInteger.ONE);
            }
            long limb = r.longValueExact();
            if (spread > 0) {
                // Borrow from, or lend to, the next limb.
                final long move = random.nextInt(2 * spread + 1) - spread;
                limb += move << width;
                q = q.subtract(BigInteger.valueOf(move));
            }
            h[i] = limb;
            rest = q;
        }
        h[9] = rest.longValueExact();
        return h;
    }

    // --- Poly1305 ----------------------------------------------------------

    private static String poly1305(String key, byte[] message) {
        final byte[] tag = new byte[16];
        Poly1305.mac(tag, 0, message, 0, message.length, hex(key));
        assertTrue(Poly1305.verify(tag, 0, message, 0, message.length, hex(key)));
        return Hex.encode(tag);
    }

    @Test
    void poly1305VectorOfRfc8439() {
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9",
                poly1305("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b",
                        "Cryptographic Forum Research Group".getBytes(StandardCharsets.US_ASCII)));
    }

    /** The vectors of RFC 8439 A.3 that push the accumulator past the modulus and through its carries. */
    @Test
    void poly1305AtTheEdgesOfTheModulus() {
        final String zeros = "00000000000000000000000000000000";
        // 2^130 - 5 is the modulus: a block of all ones under r = 2 wraps.
        assertEquals("03000000000000000000000000000000",
                poly1305("02000000000000000000000000000000" + zeros,
                        hex("ffffffffffffffffffffffffffffffff")));
        // The pad of all ones added to 2 wraps past 2^128.
        assertEquals("03000000000000000000000000000000",
                poly1305("02000000000000000000000000000000" + "ffffffffffffffffffffffffffffffff",
                        hex("02000000000000000000000000000000")));
        assertEquals("05000000000000000000000000000000",
                poly1305("01000000000000000000000000000000" + zeros,
                        hex("ffffffffffffffffffffffffffffffff" + "f0ffffffffffffffffffffffffffffff"
                                + "11000000000000000000000000000000")));
        assertEquals("00000000000000000000000000000000",
                poly1305("01000000000000000000000000000000" + zeros,
                        hex("ffffffffffffffffffffffffffffffff" + "fbfefefefefefefefefefefefefefefe"
                                + "01010101010101010101010101010101")));
        assertEquals("faffffffffffffffffffffffffffffff",
                poly1305("02000000000000000000000000000000" + zeros,
                        hex("fdffffffffffffffffffffffffffffff")));
        assertEquals("14000000000000005500000000000000",
                poly1305("01000000000000000400000000000000" + zeros,
                        hex("e33594d7505e43b900000000000000003394d7505e4379cd01000000000000000000000000000000"
                                + "0000000000000000" + "01000000000000000000000000000000")));
        assertEquals("13000000000000000000000000000000",
                poly1305("01000000000000000400000000000000" + zeros,
                        hex("e33594d7505e43b900000000000000003394d7505e4379cd0100000000000000"
                                + "00000000000000000000000000000000")));
        // No message at all: the tag is the pad.
        assertEquals("0102030405060708090a0b0c0d0e0f10",
                poly1305("ffffffffffffffffffffffffffffffff" + "0102030405060708090a0b0c0d0e0f10", new byte[0]));
    }

    @Test
    void poly1305RejectsAnotherTag() {
        final byte[] key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b");
        final byte[] message = "Cryptographic Forum Research Group".getBytes(StandardCharsets.US_ASCII);
        final byte[] tag = new byte[16];
        Poly1305.mac(tag, 0, message, 0, message.length, key);
        for (int bit = 0; bit < 128; bit++) {
            final byte[] other = tag.clone();
            other[bit >>> 3] ^= (byte) (1 << (bit & 7));
            assertFalse(Poly1305.verify(other, 0, message, 0, message.length, key));
        }
    }
}
