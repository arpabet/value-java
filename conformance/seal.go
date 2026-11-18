/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package main

import (
	"bytes"
	"encoding/hex"
	"fmt"
	"math/rand"

	val "go.arpabet.com/value"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"
	"golang.org/x/crypto/poly1305"
	"golang.org/x/crypto/salsa20/salsa"
)

// The sealing of the Go implementation is NaCl box under a random nonce. The
// lines here fix the nonce, which is the only thing Seal draws, so that the
// corpus is the same on every run and the Java side can be asked for the very
// same bytes; every sealed line is opened by value.Unseal before it is
// written.

func hx(b []byte) string {
	if len(b) == 0 {
		return "="
	}
	return hex.EncodeToString(b)
}

func fill(n int) []byte {
	b := make([]byte, n)
	rng.Read(b)
	return b
}

func key32() *[32]byte {
	var k [32]byte
	rng.Read(k[:])
	return &k
}

func nonce24() *[24]byte {
	var n [24]byte
	rng.Read(n[:])
	return &n
}

// keyPair is box.GenerateKey with the seeded source.
func keyPair() (pub, priv *[32]byte) {
	priv = key32()
	pub = new([32]byte)
	curve25519.ScalarBaseMult(pub, priv)
	return
}

func unhex(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

// The points of small order, and the encodings of numbers at and above the
// modulus 2^255 - 19, which stand for the number they are modulo it.
var edgePoints = []string{
	"0000000000000000000000000000000000000000000000000000000000000000",
	"0100000000000000000000000000000000000000000000000000000000000000",
	"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
	"5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
	"ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	"edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	"eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	"0200000000000000000000000000000000000000000000000000000000000000",
	"0900000000000000000000000000000000000000000000000000000000000000",
	"ebffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	"daffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
	"0000000000000000000000000000000000000000000000000000000000000080",
	"0900000000000000000000000000000000000000000000000000000000000080",
	"ffffffffffffffffffffffffffffffff00000000000000000000000000000000",
	"00000000000000000000000000000000ffffffffffffffffffffffffffffff7f",
	"ffffff030000f8ffff1f0000c0ffffff000000feffff070000e0ffff3f000000",
	"000000fcffff070000e0ffff3f000000ffffff010000f8ffff1f0000c0ffff7f",
}

var edgeScalars = []string{
	"0000000000000000000000000000000000000000000000000000000000000000",
	"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
	"0100000000000000000000000000000000000000000000000000000000000000",
	"0700000000000000000000000000000000000000000000000000000000000080",
	"f8ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	"a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
}

func x25519Line(scalar, point []byte) string {
	out, err := curve25519.X25519(scalar, point)
	if err != nil {
		return tsv("x25519", hx(scalar), hx(point), "zero")
	}
	return tsv("x25519", hx(scalar), hx(point), hx(out))
}

// The lengths around the blocks of the cipher (64 bytes, the first of which
// gives 32 to the key of the tag) and of the tag (16 bytes).
var sealLengths = []int{0, 1, 2, 15, 16, 17, 31, 32, 33, 47, 48, 49, 63, 64, 65, 95, 96, 97, 127, 128, 129, 159, 160, 161, 255, 256, 257, 1000, 4099}

func sealFile(seed int64, scale int) []string {
	rng = rand.New(rand.NewSource(seed + 101))
	var lines []string

	// --- X25519 ---
	for _, s := range edgeScalars {
		for _, p := range edgePoints {
			lines = append(lines, x25519Line(unhex(s), unhex(p)))
		}
	}
	for i := 0; i < 60*scale; i++ {
		lines = append(lines, x25519Line(fill(32), fill(32)))
	}
	for _, p := range edgePoints {
		lines = append(lines, x25519Line(fill(32), unhex(p)))
	}

	// --- HSalsa20 and Poly1305 ---
	for i := 0; i < 20*scale; i++ {
		var out [32]byte
		var in [16]byte
		rng.Read(in[:])
		k := key32()
		salsa.HSalsa20(&out, &in, k, &salsa.Sigma)
		lines = append(lines, tsv("hsalsa20", hx(k[:]), hx(in[:]), hx(out[:])))
	}
	poly := func(k *[32]byte, m []byte) {
		var tag [16]byte
		poly1305.Sum(&tag, m, k)
		lines = append(lines, tsv("poly1305", hx(k[:]), hx(m), hx(tag[:])))
	}
	for _, n := range sealLengths {
		poly(key32(), fill(n))
	}
	for i := 0; i < 40*scale; i++ {
		// Keys and messages of mostly ones, which reach the carries.
		k := key32()
		m := fill(rng.Intn(80))
		for j := range k {
			if rng.Intn(4) != 0 {
				k[j] = 0xff
			}
		}
		for j := range m {
			if rng.Intn(4) != 0 {
				m[j] = 0xff
			}
		}
		poly(k, m)
	}

	// --- secretbox: the box of a shared key ---
	for _, n := range sealLengths {
		k, nonce, m := key32(), nonce24(), fill(n)
		lines = append(lines, tsv("secretbox", hx(k[:]), hx(nonce[:]), hx(m), hx(secretbox.Seal(nil, m, nonce, k))))
	}

	// --- box: what Seal does to bytes ---
	boxLine := func(m []byte) {
		senderPub, senderPriv := keyPair()
		recipientPub, recipientPriv := keyPair()
		nonce := nonce24()
		sealed := box.Seal(nonce[:], m, nonce, recipientPub, senderPriv)
		opened, ok := box.Open(nil, sealed[24:], nonce, senderPub, recipientPriv)
		if !ok || !bytes.Equal(opened, m) {
			panic("box does not open")
		}
		lines = append(lines, tsv("box", hx(senderPriv[:]), hx(senderPub[:]), hx(recipientPriv[:]), hx(recipientPub[:]), hx(m), hx(sealed)))
	}
	for _, n := range sealLengths {
		boxLine(fill(n))
	}
	for i := 0; i < 20*scale; i++ {
		boxLine(fill(rng.Intn(600)))
	}

	// --- Seal and Unseal of values ---
	for i := 0; i < 60*scale; i++ {
		v := randValue(rng.Intn(4))
		senderPub, senderPriv := keyPair()
		recipientPub, recipientPriv := keyPair()
		nonce := nonce24()
		packed, err := val.Pack(v)
		if err != nil {
			panic(err)
		}
		// value.Seal, with the nonce it would have drawn.
		sealed := box.Seal(nonce[:], packed, nonce, recipientPub, senderPriv)
		back, err := val.Unseal(sealed, senderPub, recipientPriv)
		if err != nil || val.Hex(back) != val.Hex(v) {
			panic(fmt.Sprintf("Unseal: %v", err))
		}
		lines = append(lines, tsv("seal", hx(senderPriv[:]), hx(senderPub[:]), hx(recipientPriv[:]), hx(recipientPub[:]), val.Hex(v), hx(sealed)))

		// What Unseal says to the same bytes altered, cut, or under another key.
		unseal := func(sealed []byte, pub, priv *[32]byte) {
			out := "err"
			if got, err := val.Unseal(sealed, pub, priv); err == nil {
				out = val.Hex(got)
			}
			lines = append(lines, tsv("unseal", hx(pub[:]), hx(priv[:]), hx(sealed), out))
		}
		switch i % 6 {
		case 0:
			flipped := append([]byte(nil), sealed...)
			flipped[rng.Intn(len(flipped))] ^= byte(1 << rng.Intn(8))
			unseal(flipped, senderPub, recipientPriv)
		case 1:
			unseal(sealed[:rng.Intn(len(sealed))], senderPub, recipientPriv)
		case 2:
			unseal(append(append([]byte(nil), sealed...), byte(rng.Intn(256))), senderPub, recipientPriv)
		case 3:
			otherPub, _ := keyPair()
			unseal(sealed, otherPub, recipientPriv)
		case 4:
			// The sender opens what it sealed: the two key pairs share one key.
			unseal(sealed, recipientPub, senderPriv)
		case 5:
			_, otherPriv := keyPair()
			unseal(sealed, senderPub, otherPriv)
		}
	}

	// Sealed bytes that are not the bytes of one value: Unseal reads the first
	// value and fails when there is none.
	for _, plain := range []string{"", "c0", "c1", "01ff", "92", "dc", "a3616263a1", "d40100", "c70001", "81a16101a162"} {
		senderPub, senderPriv := keyPair()
		recipientPub, recipientPriv := keyPair()
		nonce := nonce24()
		sealed := box.Seal(nonce[:], unhex(plain), nonce, recipientPub, senderPriv)
		out := "err"
		if got, err := val.Unseal(sealed, senderPub, recipientPriv); err == nil {
			out = val.Hex(got)
		}
		lines = append(lines, tsv("unseal", hx(senderPub[:]), hx(recipientPriv[:]), hx(sealed), out))
	}
	for _, short := range []int{0, 1, 23, 24, 39} {
		pub, priv := keyPair()
		sealed := fill(short)
		out := "err"
		if got, err := val.Unseal(sealed, pub, priv); err == nil {
			out = val.Hex(got)
		}
		lines = append(lines, tsv("unseal", hx(pub[:]), hx(priv[:]), hx(sealed), out))
	}
	return lines
}
