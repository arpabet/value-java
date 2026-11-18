/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package main

import (
	"encoding/hex"
	"math/big"
	"math/rand"
	"strconv"

	"github.com/shopspring/decimal"
	val "go.arpabet.com/value"
)

// The package variables that shape the text of values (Base64Prefix,
// UnknownPrefix, DecimalExpDelim), the tolerance of ApproxEqual
// (PrecisionLevel) and the first index of a sparse list (FirstIndexKey): what
// the Go implementation gives once they are changed. The Java side has the
// same settings and must give the same.

type settings struct {
	base64, unknown string
	delim           byte
	precision       float64
	first           int
}

func (s settings) apply() {
	val.Base64Prefix = s.base64
	val.UnknownPrefix = s.unknown
	val.DecimalExpDelim = s.delim
	val.DecimalExpDelimStr = string(rune(s.delim))
	val.PrecisionLevel = s.precision
	val.FirstIndexKey = s.first
}

func settingsFile(seed int64, scale int) []string {
	rng = rand.New(rand.NewSource(seed + 303))
	saved := settings{val.Base64Prefix, val.UnknownPrefix, val.DecimalExpDelim, val.PrecisionLevel, val.FirstIndexKey}
	defer saved.apply()

	configs := []settings{
		saved, // as the package comes
		{"b64:", "ext:", '^', 0.5, 1},
		{"", "", 'x', 1e-12, 5},
		{"base64,", "x-", 'e', 0.00001, -2}, // a delimiter that is a hexadecimal digit
		{"é:", "ß", '|', 3, 1 << 20},        // prefixes that are not ASCII
		{"0x", "0x", '-', 0, 0},             // prefixes that look like a number
	}

	var lines []string
	for _, c := range configs {
		c.apply()
		lines = append(lines, tsv("set", b64(c.base64), b64(c.unknown), strconv.Itoa(int(c.delim)),
			strconv.FormatFloat(c.precision, 'g', -1, 64), strconv.Itoa(c.first)))

		// --- the values whose text the variables shape ---
		var samples []val.Value
		for _, n := range []int{0, 1, 2, 3, 10} {
			samples = append(samples, val.Raw(fill(n), false))
		}
		for _, tag := range []byte{3, 7, 100, 0x80, 0xfe} {
			samples = append(samples, val.Unknown(append([]byte{tag}, fill(rng.Intn(5))...)))
		}
		for _, n := range []int64{0, 1, -1, 255, 256, -256, 123, 1 << 40} {
			samples = append(samples, val.BigInt(big.NewInt(n)))
		}
		for _, d := range []decimal.Decimal{decimal.New(0, 0), decimal.New(15, -1), decimal.New(-15, -1), decimal.New(123567, -3),
			decimal.New(1, 10), decimal.New(1, -10), decimal.New(-7, 300), decimal.New(255, -256), decimal.New(0, -2)} {
			samples = append(samples, val.Decimal(d))
		}
		for i := 0; i < 6*scale; i++ {
			samples = append(samples, val.BigInt(randBig()), val.Decimal(randDecimal()), val.Raw(randBytes(12), false))
		}
		samples = append(samples,
			val.Utf8(c.base64+"AQID"), val.Utf8(c.unknown+c.base64+"BwEC"), val.Utf8("base64,AQID"), val.Utf8("plain"),
			val.Long(42), val.Double(1.5), val.Boolean(true), val.Null,
			val.ImmutableList([]val.Value{samples[1], samples[5], samples[12], samples[19], val.Utf8("text")}),
			val.ImmutableMapOf(map[string]val.Value{"raw": samples[2], "ext": samples[6], "big": samples[14], "dec": samples[21]}),
		)
		for _, v := range samples {
			packed, err := val.Pack(v)
			if err != nil {
				panic(err)
			}
			lines = append(lines, tsv("text", hex.EncodeToString(packed), b64(v.String()), b64(val.Jsonify(v))))
			// What the typed getters make of it, which goes through its text.
			one := val.Tuple(v)
			getString := guard(func() string { return val.Hex(one.GetStringAt(0)) })
			getNumber := guard(func() string { return val.Hex(one.GetNumberAt(0)) })
			lines = append(lines, tsv("getters", hex.EncodeToString(packed), getString, getNumber))
		}

		// --- text read back ---
		texts := []string{"", c.base64, c.base64 + "AQID", c.base64 + "AQI", c.base64 + "AQI=", c.base64 + "!!", c.base64 + " AQID",
			"base64,AQID", "b64:AQID", "AQID", "QUJD", "hello", "é:AQID", "0xAQID", c.unknown + c.base64 + "BwEC", "x-base64,BwEC"}
		for _, v := range samples[:10] {
			texts = append(texts, v.String())
		}
		for _, text := range texts {
			lines = append(lines, tsv("parsestring", b64(text), val.Hex(val.ParseString(text))))
		}

		d := string(rune(c.delim))
		numbers := []string{"0x01e2afx-03", "0x01e2af" + d + "-03", "-0x01e2af" + d + "-03", "0x1e2", "0x1" + d + "1", "0x1" + d, "0x" + d + "1",
			"0x7b" + d + "02", "0x7b" + d + "-02", "0x7b" + d + d + "02", "0x7b" + d + "0102030405", "-0x7b" + d + "-0102030405", "0x7b", "-0x7b",
			"0x", "12", "1.5", "0x7bx02", "0x7b^02", "0x7be02", "0x7b|02", "0x7b-02", "0xe", "0xee", "0x1e", "0x-1", d, "0x" + d}
		for _, v := range samples {
			if v.Kind() == val.NUMBER {
				numbers = append(numbers, v.String())
			}
		}
		alphabet := "0123456789abcdefx-" + d + d + d
		for i := 0; i < 20*scale; i++ {
			b := make([]byte, 1+rng.Intn(10))
			for j := range b {
				b[j] = alphabet[rng.Intn(len(alphabet))]
			}
			numbers = append(numbers, []string{"0x", "-0x", "0X", ""}[rng.Intn(4)]+string(b))
		}
		for _, text := range numbers {
			number := guard(func() string { return val.Hex(val.ParseNumber(text)) })
			lines = append(lines, tsv("parsenumber", b64(text), number))
		}

		// --- a key that is not text becomes its text ---
		for _, in := range []string{
			"81c402010201",             // {bin: 1}
			"81d4070101",               // {ext 7: 1}
			"81d4017b01",               // {big integer 123: 1}
			"81c70502000000027b01",     // {decimal 1.23: 1}
			"81c70502fffffffd7b01",     // {decimal 123e3: 1}
			"8191c401ff01",             // {[bin]: 1}
			"8181c40101d4070201",       // {{bin: ext}: 1}
			"83c4010101a16202a9" + hex.EncodeToString([]byte("base64,AQ")) + "03", // a bin key and the text it becomes by default
			"82c40001d4050002",         // {empty bin: 1, ext 5 of one zero byte: 2}
		} {
			raw := unhex(in)
			out := guard(func() string {
				v, err := val.Unpack(raw, true)
				if err != nil {
					return "err"
				}
				return val.Hex(v)
			})
			if v, err := val.Unpack(raw, true); err == nil && (!ordered(v) || renderedKeys(v)) {
				continue
			}
			lines = append(lines, tsv("unpack", in, out))
		}

		// --- PrecisionLevel ---
		pairs := [][2]val.Number{
			{val.Double(1), val.Double(1.000001)}, {val.Double(1), val.Double(1.4)}, {val.Long(1), val.Double(1.6)}, {val.Long(1), val.Long(2)},
			{val.Long(1), val.Long(4)}, {val.Long(1), val.Long(5)}, {val.Double(0.1 + 0.2), val.Double(0.3)}, {val.Long(7), val.Long(7)},
			{val.BigInt(big.NewInt(10)), val.Decimal(decimal.New(105, -1))}, {val.Double(1e-13), val.Long(0)}, {val.Nan, val.Nan},
			{val.Double(-1), val.Double(1)}, {val.Long(1 << 40), val.Double(float64(1<<40) + 0.25)},
		}
		for _, p := range pairs {
			lines = append(lines, tsv("approx", val.Hex(p[0]), val.Hex(p[1]), strconv.FormatBool(val.ApproxEqual(p[0], p[1]))))
		}

		// --- FirstIndexKey ---
		lines = append(lines, tsv("append", val.Hex(val.EmptySparseList().Append(val.Long(7)).Append(val.Long(8)))))
		lines = append(lines, tsv("appendafter", val.Hex(val.EmptySparseList().PutAt(4, val.Long(1)).Append(val.Long(2)))))
	}
	return lines
}
