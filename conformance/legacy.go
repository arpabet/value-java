/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package main

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"math"
	"math/big"
	"math/rand"
	"strconv"

	"github.com/shopspring/decimal"
	val "go.arpabet.com/value"
)

// Before v1.5.0 a big integer travelled as math/big's gob encoding and a
// decimal as shopspring's MarshalBinary. With value.LegacyBigNumbers set the
// decoders read those; the lines here are what they give.

func gob(n *big.Int) []byte {
	b, err := n.GobEncode()
	if err != nil {
		panic(err)
	}
	return b
}

func legacyDecimal(d decimal.Decimal) []byte {
	b, err := d.MarshalBinary()
	if err != nil {
		panic(err)
	}
	return b
}

func exponent(exp int32, coefficient ...byte) []byte {
	b := make([]byte, 4, 4+len(coefficient))
	binary.BigEndian.PutUint32(b, uint32(exp))
	return append(b, coefficient...)
}

func legacyFile(seed int64, scale int) []string {
	rng = rand.New(rand.NewSource(seed + 202))
	saved := val.LegacyBigNumbers
	val.LegacyBigNumbers = true
	defer func() { val.LegacyBigNumbers = saved }()

	var lines []string
	var bigints, decimals [][]byte

	for _, h := range []string{"", "02", "03", "027b", "037b", "0200007b", "03000000", "09", "00", "01", "0401", "ff", "7b", "0201e2af",
		"02ffffffffffffffff", "03ffffffffffffffff", "028000000000000000", "038000000000000000", "038000000000000001", "02010000000000000000"} {
		bigints = append(bigints, unhex(h))
	}
	for i := 0; i < 40*scale; i++ {
		n := randBig()
		b := gob(n)
		if rng.Intn(4) == 0 && len(b) > 0 {
			// Zeros before the magnitude, which the decoder takes.
			b = append([]byte{b[0], 0, 0}, b[1:]...)
		}
		bigints = append(bigints, b)
	}

	for _, h := range []string{"", "00", "000000", "00000000", "fffffffd0201e2af", "fffffffd0301e2af", "0000000002", "0000000003", "000000000900", "00000002027b",
		"fffffffd", "0000000a03", "fffffffe7b"} {
		decimals = append(decimals, unhex(h))
	}
	for _, exp := range []int32{0, 1, -1, 1 << 20, 1<<20 + 1, -(1 << 20), -(1 << 20) - 1, math.MaxInt32, math.MinInt32, math.MinInt32 + 1} {
		decimals = append(decimals, exponent(exp, 0x02, 0x01), exponent(exp))
	}
	for i := 0; i < 40*scale; i++ {
		decimals = append(decimals, legacyDecimal(randDecimal()))
	}

	for _, p := range bigints {
		out := "err"
		if n, err := val.UnpackLegacyBigInt(p); err == nil {
			out = n.String()
		}
		lines = append(lines, tsv("bigint", hx(p), out))
	}
	for _, p := range decimals {
		out := "err"
		if d, err := val.UnpackLegacyDecimal(p); err == nil {
			out = d.Coefficient().String() + "|" + strconv.Itoa(int(d.Exponent()))
		}
		lines = append(lines, tsv("decimal", hx(p), out))
	}

	// The decoder, with the switch on.
	unpack := func(in []byte) {
		line := guard(func() string {
			v, err := val.Unpack(in, true)
			if err != nil || !ordered(v) || renderedKeys(v) {
				return "err"
			}
			return val.Hex(v)
		})
		if v, err := val.Unpack(in, true); err == nil && (!ordered(v) || renderedKeys(v)) {
			return
		}
		lines = append(lines, tsv("unpack", hex.EncodeToString(in), line))
	}
	for _, p := range bigints {
		w := &sloppy{}
		w.ext(1, p)
		unpack(w.b)
	}
	for _, p := range decimals {
		w := &sloppy{}
		w.ext(2, p)
		unpack(w.b)
	}
	for i := 0; i < 150*scale; i++ {
		v := randValue(3)
		w := &sloppy{legacy: true}
		w.value(v)
		unpack(w.b)
		if i%3 == 0 {
			unpack(mutate(w.b))
		}
		if i%4 == 0 {
			// Bytes of the layout in use since v1.5.0, read as the one before.
			packed, _ := val.Pack(v)
			unpack(packed)
		}
	}

	// The $ext wrapper of JSON goes the same way.
	for i, p := range append(append([][]byte{}, bigints...), decimals...) {
		tag := 1
		if i >= len(bigints) {
			tag = 2
		}
		text := `{"$ext":{"base64":"` + base64.StdEncoding.EncodeToString(p) + `","type":` + strconv.Itoa(tag) + `}}`
		v, err := val.ParseJSON([]byte(text))
		out := "err"
		if err == nil {
			out = val.Hex(v)
		}
		lines = append(lines, tsv("json", b64(text), out))
	}
	return lines
}
