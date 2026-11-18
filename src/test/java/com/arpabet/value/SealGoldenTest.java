/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Sealing against the Go implementation (conformance/seal.go): what
 * golang.org/x/crypto's curve25519, salsa20, poly1305 and nacl/box give, and
 * what value.Unseal says to sealed bytes, is what this library must give.
 *
 * <p>Go's Seal draws a nonce and nothing else. The golden lines fix it, so
 * the bytes sealed here under the same nonce have to be the very bytes Go
 * sealed; they are the bytes Go's Unseal opened when the file was written.
 */
class SealGoldenTest {

    private static byte[] bytes(String field) {
        return field.equals("=") ? new byte[0] : Hex.decode(field);
    }

    private static String hex(byte[] b) {
        return b.length == 0 ? "=" : Hex.encode(b);
    }

    @Test
    void seal() throws IOException {
        final GoldenTest.Report r = new GoldenTest.Report("seal.tsv");
        final Set<String> kinds = new HashSet<>();
        for (String[] f : GoldenTest.load("seal.tsv")) {
            r.lines++;
            kinds.add(f[0]);
            final String line = String.join(" ", f).substring(0, Math.min(160, String.join(" ", f).length()));
            try {
                switch (f[0]) {
                    case "x25519": {
                        final byte[] out = new byte[32];
                        final boolean ok = Curve25519.scalarMult(out, bytes(f[1]), bytes(f[2]));
                        if (f[3].equals("zero")) {
                            r.check(!ok && Arrays.equals(out, new byte[32]), "x25519", line, "zero", hex(out));
                        } else {
                            r.check(ok, "x25519 is not zero", line, true, ok);
                            r.same("x25519", line, f[3], hex(out));
                        }
                        break;
                    }
                    case "hsalsa20": {
                        final byte[] out = new byte[32];
                        Salsa20.hsalsa20(out, bytes(f[1]), bytes(f[2]), 0);
                        r.same("hsalsa20", line, f[3], hex(out));
                        break;
                    }
                    case "poly1305": {
                        final byte[] m = bytes(f[2]);
                        final byte[] tag = new byte[16];
                        Poly1305.mac(tag, 0, m, 0, m.length, bytes(f[1]));
                        r.same("poly1305", line, f[3], hex(tag));
                        break;
                    }
                    case "secretbox": {
                        final Sealer sealer = Sealer.ofSharedKey(bytes(f[1]));
                        final byte[] nonce = bytes(f[2]);
                        final byte[] m = bytes(f[3]);
                        final byte[] sealed = sealer.box(nonce, m, 0, m.length);
                        r.same("secretbox", line, f[2] + f[4], Hex.encode(sealed));
                        r.same("secretbox opened", line, f[3], hex(sealer.unsealBytes(bytes(f[2] + f[4]))));
                        break;
                    }
                    case "box": {
                        final byte[] senderPrivate = bytes(f[1]);
                        final byte[] senderPublic = bytes(f[2]);
                        final byte[] recipientPrivate = bytes(f[3]);
                        final byte[] recipientPublic = bytes(f[4]);
                        final byte[] m = bytes(f[5]);
                        final byte[] sealed = bytes(f[6]);
                        r.same("public key", line, f[2], hex(Sealer.publicKey(senderPrivate)));
                        r.same("public key", line, f[4], hex(Sealer.publicKey(recipientPrivate)));
                        final Sealer sender = Sealer.of(recipientPublic, senderPrivate);
                        final Sealer recipient = Sealer.of(senderPublic, recipientPrivate);
                        r.same("box", line, f[6], hex(sender.box(Arrays.copyOf(sealed, 24), m, 0, m.length)));
                        r.same("box opened", line, f[5], hex(recipient.unsealBytes(sealed)));
                        r.same("box opened by its sender", line, f[5], hex(sender.unsealBytes(sealed)));
                        // Under a nonce of its own the bytes differ and open the same.
                        final byte[] fresh = sender.sealBytes(m);
                        r.check(fresh.length == m.length + Sealer.OVERHEAD && !Arrays.equals(fresh, sealed),
                                "sealBytes", line, "other bytes of the same length", hex(fresh));
                        r.same("sealBytes opened", line, f[5], hex(recipient.unsealBytes(fresh)));
                        break;
                    }
                    case "seal": {
                        final byte[] senderPrivate = bytes(f[1]);
                        final byte[] senderPublic = bytes(f[2]);
                        final byte[] recipientPrivate = bytes(f[3]);
                        final byte[] recipientPublic = bytes(f[4]);
                        final byte[] sealed = bytes(f[6]);
                        final Value opened = Value.unseal(sealed, senderPublic, recipientPrivate);
                        r.same("unseal", line, f[5], opened.hex());
                        // What Go sealed is what is sealed here, given its nonce.
                        final byte[] plain = opened.pack();
                        r.same("seal", line, f[6],
                                hex(Sealer.of(recipientPublic, senderPrivate).box(Arrays.copyOf(sealed, 24), plain, 0, plain.length)));
                        final byte[] fresh = opened.seal(recipientPublic, senderPrivate);
                        r.same("seal and unseal", line, f[5], Value.unseal(fresh, senderPublic, recipientPrivate).hex());
                        break;
                    }
                    case "unseal": {
                        String got;
                        try {
                            got = Value.unseal(bytes(f[3]), bytes(f[1]), bytes(f[2])).hex();
                        } catch (ValueException e) {
                            got = "err";
                        }
                        r.same("unseal", line, f[4], got);
                        break;
                    }
                    default:
                        r.check(false, "kind", line, "a known kind", f[0]);
                }
            } catch (RuntimeException e) {
                r.check(false, f[0] + " threw", line, "an answer", GoldenTest.describe(e));
            }
        }
        r.done();
        assertTrue(kinds.containsAll(Arrays.asList("x25519", "hsalsa20", "poly1305", "secretbox", "box", "seal", "unseal")),
                "seal.tsv lacks a kind: " + kinds);
    }
}
