/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// Command conformance writes the golden files the Java implementation is
// tested against: what go.arpabet.com/value gives for a seeded corpus of
// values, bytes and texts. The Java tests read them from
// src/test/resources/golden (or from the directory named by the Gradle
// property goldenDir) and must give the same answers.
//
//	go run . -out ../src/test/resources/golden          # the committed corpus
//	go run . -out /tmp/golden -scale 50 -seed 7         # a larger one
//
// Every file is tab-separated; a text is in base64 so that any byte travels.
package main

import (
	"encoding/base64"
	"encoding/hex"
	"flag"
	"fmt"
	"math"
	"math/big"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/shopspring/decimal"
	val "go.arpabet.com/value"
)

var rng *rand.Rand

func main() {
	out := flag.String("out", "../src/test/resources/golden", "directory to write the golden files to")
	scale := flag.Int("scale", 1, "multiplies the size of the corpus")
	seed := flag.Int64("seed", 20261118, "seed of the corpus")
	flag.Parse()
	rng = rand.New(rand.NewSource(*seed))
	if err := os.MkdirAll(*out, 0o755); err != nil {
		panic(err)
	}
	n := *scale
	write(*out, "unpack.tsv", unpackFile(n))
	write(*out, "json.tsv", jsonFile(n))
	write(*out, "double.tsv", doubleFile(n))
	write(*out, "float.tsv", floatFile(n))
	write(*out, "timestamp.tsv", timestampFile(n))
	write(*out, "timestamp_parse.tsv", timestampParseFile(n))
	write(*out, "number.tsv", numberFile(n))
	write(*out, "parse.tsv", parseFile(n))
	write(*out, "base64.tsv", base64File(n))
	write(*out, "codec.tsv", codecFile())
	// These two draw from sources of their own, so that the files above stay
	// as they are when these change.
	write(*out, "seal.tsv", sealFile(*seed, n))
	write(*out, "legacy.tsv", legacyFile(*seed, n))
	write(*out, "settings.tsv", settingsFile(*seed, n))
}

func write(dir, name string, lines []string) {
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		panic(err)
	}
	fmt.Printf("%s: %d lines\n", path, len(lines))
}

func b64(s string) string {
	if s == "" {
		return "="
	}
	return base64.StdEncoding.EncodeToString([]byte(s))
}

func tsv(fields ...string) string { return strings.Join(fields, "\t") }

// --- random values -----------------------------------------------------------

// Alphabets whose characters every Unicode version since 6 classifies alike,
// so that the Go rendering of text made of them (which escapes what Go does
// not call printable) is the same in every implementation.
var stableRanges = [][2]rune{
	{0x20, 0x7e}, {0xa0, 0xff}, {0x100, 0x17f}, {0x391, 0x3a1}, {0x3a3, 0x3a9}, {0x3b1, 0x3c9},
	{0x410, 0x44f}, {0x3041, 0x3093}, {0x4e00, 0x9fa5}, {0xe000, 0xe0ff}, {0x1f600, 0x1f64f},
}

var stableSingles = []rune{0, 1, 7, 8, 9, 10, 11, 12, 13, 0x1b, 0x1f, 0x7f, 0x200b, 0x2028, 0xfeff, 0xfffd, 0xffff, 0x10000, 0x10ffff}

func stableRune(r rune) bool {
	for _, x := range stableSingles {
		if r == x {
			return true
		}
	}
	for _, x := range stableRanges {
		if r >= x[0] && r <= x[1] {
			return true
		}
	}
	return false
}

func randRune() rune {
	switch rng.Intn(12) {
	case 0, 1, 2, 3, 4:
		return rune(0x20 + rng.Intn(0x5f))
	case 5:
		return stableSingles[rng.Intn(len(stableSingles))]
	case 6:
		// Anything, to exercise ordering and UTF-8 of every length.
		for {
			r := rune(rng.Intn(0x110000))
			if utf8.ValidRune(r) {
				return r
			}
		}
	default:
		x := stableRanges[rng.Intn(len(stableRanges))]
		return x[0] + rune(rng.Intn(int(x[1]-x[0])+1))
	}
}

func randText(max int) string {
	n := rng.Intn(max + 1)
	var b strings.Builder
	for i := 0; i < n; i++ {
		if rng.Intn(60) == 0 {
			b.WriteByte(byte(0x80 + rng.Intn(0x80))) // a byte that is not UTF-8
			continue
		}
		b.WriteRune(randRune())
	}
	return b.String()
}

func randKey() string {
	switch rng.Intn(6) {
	case 0:
		return randText(6)
	case 1:
		// Keys that order differently by UTF-16 code unit than by byte.
		return string([]rune{[]rune{0xe000, 0xffff, 0x10000, 0x1f600, 0xfffd, 0xd7ff, 0x7f, 0x80}[rng.Intn(8)], rune('a' + rng.Intn(3))})
	case 2:
		return strconv.Itoa(rng.Intn(30))
	default:
		letters := "abcdefghijklmnopqrstuvwxyzABC_$"
		n := 1 + rng.Intn(8)
		b := make([]byte, n)
		for i := range b {
			b[i] = letters[rng.Intn(len(letters))]
		}
		return string(b)
	}
}

func randBytes(max int) []byte {
	b := make([]byte, rng.Intn(max+1))
	rng.Read(b)
	return b
}

var edgeLongs = []int64{0, 1, -1, 127, 128, 255, 256, -32, -33, -128, -129, 32767, 32768, 65535, 65536, -32768, -32769,
	2147483647, 2147483648, 4294967295, 4294967296, -2147483648, -2147483649, math.MaxInt64, math.MinInt64, math.MaxInt64 - 1, math.MinInt64 + 1}

func randLong() int64 {
	switch rng.Intn(4) {
	case 0:
		return edgeLongs[rng.Intn(len(edgeLongs))]
	case 1:
		return int64(rng.Intn(200)) - 100
	default:
		return int64(rng.Uint64()) >> uint(rng.Intn(64))
	}
}

var edgeDoubles = []float64{0, math.Copysign(0, -1), 1, -1, 0.1, 0.5, 1.5, 100, 1e6, 1e7, 1e20, 1e21, 1e22, 1e-6, 1e-7, 123456.7, 1234567, 1200000,
	5e-324, math.MaxFloat64, math.SmallestNonzeroFloat64, 2.2250738585072014e-308, 2.2250738585072009e-308, 9007199254740992, 9007199254740993, 1e23, 9.5367431640625e-07,
	math.Inf(1), math.Inf(-1), math.NaN(), math.MaxFloat32, 1.0 / 3, 2.0 / 3, 4.35, 0.000001234, 1e300, 1e-300, 4.9e-322}

func randDouble() float64 {
	switch rng.Intn(6) {
	case 0:
		return edgeDoubles[rng.Intn(len(edgeDoubles))]
	case 1:
		return math.Float64frombits(rng.Uint64())
	case 2:
		// A short decimal, as people write them.
		f, _ := strconv.ParseFloat(fmt.Sprintf("%d.%de%d", rng.Intn(1000), rng.Intn(1000), rng.Intn(40)-20), 64)
		if rng.Intn(2) == 0 {
			f = -f
		}
		return f
	case 3:
		return float64(rng.Int63n(1 << 54))
	case 4:
		return math.Ldexp(1, rng.Intn(2098)-1074) * float64(1+rng.Intn(3))
	default:
		return float64(float32(rng.NormFloat64() * math.Pow(10, float64(rng.Intn(20)-10))))
	}
}

func randBig() *big.Int {
	var x *big.Int
	switch rng.Intn(4) {
	case 0:
		x = big.NewInt(randLong())
	case 1:
		x = new(big.Int).Lsh(big.NewInt(1), uint(rng.Intn(200)))
		x.Add(x, big.NewInt(int64(rng.Intn(3))-1))
	default:
		x = new(big.Int).SetBytes(randBytes(40))
	}
	if rng.Intn(2) == 0 {
		x.Neg(x)
	}
	return x
}

func randDecimal() decimal.Decimal {
	exp := int32(rng.Intn(61) - 30)
	switch rng.Intn(8) {
	case 0:
		exp = 0
	case 1:
		exp = int32(rng.Intn(2000001) - 1000000)
	}
	return decimal.NewFromBigInt(randBig(), exp)
}

func randTimestamp() val.Timestamp {
	var sec int64
	switch rng.Intn(6) {
	case 0:
		sec = int64(rng.Uint32())
	case 1:
		sec = rng.Int63n(1 << 34)
	case 2:
		sec = int64(rng.Uint64())
	case 3:
		sec = []int64{0, -1, 1<<32 - 1, 1 << 32, 1<<34 - 1, 1 << 34, 253402300799, 253402300800, -62167219200, -62167219201, -62135596800, math.MaxInt64, math.MinInt64}[rng.Intn(13)]
	default:
		sec = 1793345319 + rng.Int63n(1e9) - 5e8
	}
	var nsec int64
	switch rng.Intn(4) {
	case 0:
		nsec = 0
	case 1:
		nsec = int64(rng.Intn(1000)) * 1e6
	default:
		nsec = rng.Int63n(1e9)
	}
	return val.UnixTime(sec, nsec)
}

func randScalar() val.Value {
	switch rng.Intn(16) {
	case 0:
		return val.Null
	case 1:
		return val.Boolean(rng.Intn(2) == 0)
	case 2, 3, 4:
		return val.Long(randLong())
	case 5, 6:
		return val.Double(randDouble())
	case 7:
		return val.BigInt(randBig())
	case 8:
		return val.Decimal(randDecimal())
	case 9, 10, 11:
		return val.Utf8(randText(12))
	case 12:
		return val.Utf8(randText(300))
	case 13:
		return val.Raw(randBytes(40), false)
	case 14:
		return randTimestamp()
	default:
		// An extension the format does not define, of any length.
		tag := byte(3 + rng.Intn(252))
		data := randBytes([]int{0, 1, 2, 4, 8, 16, 17, 40, 300}[rng.Intn(9)])
		return val.Unknown(append([]byte{tag}, data...))
	}
}

func randValue(depth int) val.Value {
	if depth <= 0 || rng.Intn(3) != 0 {
		return randScalar()
	}
	switch rng.Intn(4) {
	case 0:
		n := rng.Intn(5)
		if rng.Intn(20) == 0 {
			n = 15 + rng.Intn(4)
		}
		items := make([]val.Value, n)
		for i := range items {
			items[i] = randValue(depth - 1)
		}
		return val.ImmutableList(items)
	case 1:
		n := rng.Intn(5)
		items := make([]val.ListItem, 0, n)
		seen := map[int]bool{}
		for i := 0; i < n; i++ {
			k := rng.Intn(40)
			if rng.Intn(10) == 0 {
				k = rng.Intn(1 << 24)
			}
			if seen[k] {
				continue
			}
			seen[k] = true
			items = append(items, val.ImmutableItem(k, randValue(depth-1)))
		}
		return val.SparseList(items, false)
	default:
		n := rng.Intn(6)
		if rng.Intn(20) == 0 {
			n = 15 + rng.Intn(4)
		}
		m := make(map[string]val.Value, n)
		for i := 0; i < n; i++ {
			m[randKey()] = randValue(depth - 1)
		}
		if rng.Intn(12) == 0 {
			// A map whose only key is a JSON wrapper key.
			keys := []string{"$long", "$double", "$bigint", "$decimal", "$bin", "$timestamp", "$sparse", "$ext", "$map"}
			return val.ImmutableMapOf(map[string]val.Value{keys[rng.Intn(len(keys))]: randValue(depth - 1)})
		}
		return val.ImmutableMapOf(m)
	}
}

// --- a writer of the forms a canonical writer does not use ---------------------

type sloppy struct {
	b []byte
	// legacy writes big integers and decimals in the layout of before v1.5.0.
	legacy bool
}

func (w *sloppy) u(n int, v uint64) {
	for i := n - 1; i >= 0; i-- {
		w.b = append(w.b, byte(v>>(8*uint(i))))
	}
}

func (w *sloppy) long(v int64) {
	// Any integer form wide enough.
	forms := []int{}
	if v >= 0 {
		for i, max := range []uint64{0x7f, 0xff, 0xffff, 0xffffffff, math.MaxUint64} {
			if uint64(v) <= max {
				forms = append(forms, i)
			}
		}
	}
	for i, min := range []int64{-32, math.MinInt8, math.MinInt16, math.MinInt32, math.MinInt64} {
		max := []int64{-1, math.MaxInt8, math.MaxInt16, math.MaxInt32, math.MaxInt64}[i]
		if v >= min && v <= max {
			forms = append(forms, 10+i)
		}
	}
	switch f := forms[rng.Intn(len(forms))]; f {
	case 0:
		w.b = append(w.b, byte(v))
	case 1, 2, 3, 4:
		w.b = append(w.b, 0xcc+byte(f-1))
		w.u(1<<uint(f-1), uint64(v))
	case 10:
		w.b = append(w.b, byte(v))
	default:
		w.b = append(w.b, 0xd0+byte(f-11))
		w.u(1<<uint(f-11), uint64(v))
	}
}

func (w *sloppy) header(fix byte, fixMax int, codes []byte, sizes []int, n int) {
	forms := []int{}
	if fixMax > 0 && n <= fixMax {
		forms = append(forms, -1)
	}
	for i, size := range sizes {
		if size == 4 || n < 1<<(8*uint(size)) {
			forms = append(forms, i)
		}
	}
	f := forms[rng.Intn(len(forms))]
	if f < 0 {
		w.b = append(w.b, fix|byte(n))
		return
	}
	w.b = append(w.b, codes[f])
	w.u(sizes[f], uint64(n))
}

func (w *sloppy) str(s string) {
	w.header(0xa0, 31, []byte{0xd9, 0xda, 0xdb}, []int{1, 2, 4}, len(s))
	w.b = append(w.b, s...)
}

func (w *sloppy) ext(tag byte, data []byte) {
	fix := map[int]byte{1: 0xd4, 2: 0xd5, 4: 0xd6, 8: 0xd7, 16: 0xd8}
	if code, ok := fix[len(data)]; ok && rng.Intn(2) == 0 {
		w.b = append(w.b, code, tag)
	} else {
		w.header(0, 0, []byte{0xc7, 0xc8, 0xc9}, []int{1, 2, 4}, len(data))
		w.b = append(w.b, tag)
	}
	w.b = append(w.b, data...)
}

func signExtend(b []byte) []byte {
	pad := byte(0)
	if len(b) > 0 && b[0]&0x80 != 0 {
		pad = 0xff
	}
	out := make([]byte, 0, len(b)+3)
	for i := rng.Intn(4); i > 0; i-- {
		out = append(out, pad)
	}
	return append(out, b...)
}

func (w *sloppy) value(v val.Value) {
	switch v.Kind() {
	case val.NULL:
		w.b = append(w.b, 0xc0)
	case val.BOOL:
		if v.(val.Bool).Boolean() {
			w.b = append(w.b, 0xc3)
		} else {
			w.b = append(w.b, 0xc2)
		}
	case val.NUMBER:
		n := v.(val.Number)
		switch n.Type() {
		case val.LONG:
			w.long(n.Long())
		case val.DOUBLE:
			d := n.Double()
			if f := float32(d); float64(f) == d && rng.Intn(2) == 0 {
				w.b = append(w.b, 0xca)
				w.u(4, uint64(math.Float32bits(f)))
			} else {
				w.b = append(w.b, 0xcb)
				w.u(8, math.Float64bits(d))
			}
		default:
			packed, _ := val.Pack(v)
			_, tag, data := splitExt(packed)
			if w.legacy {
				if n.Type() == val.BIGINT {
					data = gob(n.BigInt())
				} else {
					data = legacyDecimal(n.Decimal())
				}
			} else if n.Type() == val.BIGINT {
				data = signExtend(data)
			} else {
				data = append(append([]byte{}, data[:4]...), signExtend(data[4:])...)
			}
			w.ext(tag, data)
		}
	case val.STRING:
		s := v.(val.String)
		if s.Type() == val.UTF8 {
			w.str(s.Utf8())
		} else {
			w.header(0, 0, []byte{0xc4, 0xc5, 0xc6}, []int{1, 2, 4}, s.Len())
			w.b = append(w.b, s.Raw()...)
		}
	case val.TIMESTAMP:
		sec, nsec := v.(val.Timestamp).Unix()
		forms := []int{12}
		if sec >= 0 && sec < 1<<34 {
			forms = append(forms, 8)
			if nsec == 0 && sec < 1<<32 {
				forms = append(forms, 4)
			}
		}
		w2 := &sloppy{}
		switch forms[rng.Intn(len(forms))] {
		case 4:
			w2.u(4, uint64(sec))
		case 8:
			w2.u(8, uint64(nsec)<<34|uint64(sec))
		default:
			w2.u(4, uint64(nsec))
			w2.u(8, uint64(sec))
		}
		w.ext(0xff, w2.b)
	case val.UNKNOWN:
		packed, _ := val.Pack(v)
		_, tag, data := splitExt(packed)
		w.ext(tag, data)
	case val.LIST:
		l := v.(val.List)
		if _, dense := l.Object().([]val.Value); dense {
			items := l.Values()
			w.header(0x90, 15, []byte{0xdc, 0xdd}, []int{2, 4}, len(items))
			for _, item := range items {
				w.value(item)
			}
			return
		}
		items := append([]val.ListItem(nil), l.Items()...)
		rng.Shuffle(len(items), func(i, j int) { items[i], items[j] = items[j], items[i] })
		w.header(0x80, 15, []byte{0xde, 0xdf}, []int{2, 4}, len(items))
		for _, item := range items {
			w.long(int64(item.Key()))
			w.value(item.Value())
		}
	case val.MAP:
		entries := append([]val.MapEntry(nil), v.(val.Map).Entries()...)
		rng.Shuffle(len(entries), func(i, j int) { entries[i], entries[j] = entries[j], entries[i] })
		w.header(0x80, 15, []byte{0xde, 0xdf}, []int{2, 4}, len(entries))
		for _, e := range entries {
			w.str(e.Key())
			w.value(e.Value())
		}
	}
}

// splitExt returns the header length, the type and the data of a packed extension.
func splitExt(packed []byte) (int, byte, []byte) {
	switch packed[0] {
	case 0xd4, 0xd5, 0xd6, 0xd7, 0xd8:
		return 2, packed[1], packed[2:]
	case 0xc7:
		return 3, packed[2], packed[3:]
	case 0xc8:
		return 4, packed[3], packed[4:]
	default:
		return 6, packed[5], packed[6:]
	}
}

// --- unpack.tsv ----------------------------------------------------------------

// stableTree reports whether the Go rendering of a value is the same in every
// implementation: all its text is made of characters classified alike by
// every Unicode version.
func stableTree(v val.Value) bool {
	okText := func(s string) bool {
		for len(s) > 0 {
			r, size := utf8.DecodeRuneInString(s)
			if r == utf8.RuneError && size == 1 {
				s = s[1:]
				continue
			}
			if !stableRune(r) {
				return false
			}
			s = s[size:]
		}
		return true
	}
	switch v.Kind() {
	case val.STRING:
		if s := v.(val.String); s.Type() == val.UTF8 {
			return okText(s.Utf8())
		}
	case val.LIST:
		for _, item := range v.(val.List).Items() {
			if !stableTree(item.Value()) {
				return false
			}
		}
	case val.MAP:
		for _, e := range v.(val.Map).Entries() {
			if !okText(e.Key()) || !stableTree(e.Value()) {
				return false
			}
		}
	}
	return true
}

// ordered reports whether decoding gave the value an order every
// implementation gives it. Go's sort is not stable, so where a map or a sparse
// list of more than 12 entries repeats a key the order of the repeats is an
// accident of the algorithm.
func ordered(v val.Value) bool {
	switch v.Kind() {
	case val.LIST:
		items := v.(val.List).Items()
		for i, item := range items {
			if len(items) > 12 && i > 0 && items[i-1].Key() == item.Key() {
				return false
			}
			if !ordered(item.Value()) {
				return false
			}
		}
	case val.MAP:
		entries := v.(val.Map).Entries()
		for i, e := range entries {
			if len(entries) > 12 && i > 0 && entries[i-1].Key() == e.Key() {
				return false
			}
			if !ordered(e.Value()) {
				return false
			}
		}
	}
	return true
}

// renderedKeys reports whether a map of the value has a key that looks like
// the rendering of a list or a map and holds a character whose class depends
// on the Unicode version. A key that is not text becomes the text of its
// String(), which for a collection quotes text with strconv.Quote, and what
// that escapes changes with the Unicode tables of the Go release: for such a
// key (which only a foreign MessagePack writer produces) no two
// implementations, and no two Go versions, are bound to agree.
func renderedKeys(v val.Value) bool {
	switch v.Kind() {
	case val.LIST:
		for _, item := range v.(val.List).Items() {
			if renderedKeys(item.Value()) {
				return true
			}
		}
	case val.MAP:
		for _, e := range v.(val.Map).Entries() {
			if k := e.Key(); len(k) > 0 && (k[0] == '[' || k[0] == '{') {
				for _, r := range k {
					if r >= 0x80 && !stableRune(r) {
						return true
					}
				}
			}
			if renderedKeys(e.Value()) {
				return true
			}
		}
	}
	return false
}

func unpackLine(in []byte) (line string, ok bool) {
	defer func() {
		if r := recover(); r != nil {
			line, ok = tsv(hex.EncodeToString(in), "panic"), true
		}
	}()
	v, err := val.Unpack(in, true)
	if err != nil {
		return tsv(hex.EncodeToString(in), "err"), true
	}
	if !ordered(v) || renderedKeys(v) {
		return "", false
	}
	json := func(mode val.JSONMode) string {
		text, err := val.FormatJSON(v, mode)
		if err != nil {
			return "ERR"
		}
		return b64(string(text))
	}
	str, jsonify := "-", "-"
	if stableTree(v) {
		str, jsonify = b64(v.String()), b64(val.Jsonify(v))
	}
	// What the canonical JSON reads back as: the same bytes, except for the
	// values JSON does not carry (a key held twice, a NaN that is not the
	// canonical one, a year outside 0000 to 9999).
	back := "ERR"
	if text, err := val.FormatJSON(v, val.JSONCanonical); err == nil {
		if parsed, err := val.ParseJSON(text); err == nil {
			back = val.Hex(parsed)
		}
	}
	return tsv(hex.EncodeToString(in), "ok", val.Hex(v), json(val.JSONCanonical), json(val.JSONRelaxed), str, jsonify, back), true
}

func mutate(b []byte) []byte {
	out := append([]byte(nil), b...)
	for i := 1 + rng.Intn(3); i > 0; i-- {
		switch rng.Intn(5) {
		case 0:
			if len(out) > 0 {
				out[rng.Intn(len(out))] ^= 1 << uint(rng.Intn(8))
			}
		case 1:
			if len(out) > 0 {
				out[rng.Intn(len(out))] = byte(rng.Intn(256))
			}
		case 2:
			if len(out) > 0 {
				out = out[:rng.Intn(len(out))]
			}
		case 3:
			at := rng.Intn(len(out) + 1)
			out = append(out[:at], append([]byte{byte(rng.Intn(256))}, out[at:]...)...)
		default:
			if len(out) > 1 {
				at := rng.Intn(len(out))
				out = append(out[:at], out[at+1:]...)
			}
		}
	}
	return out
}

var handUnpack = []string{
	"", "c0", "c1", "c2", "c3", "00", "7f", "80", "90", "a0", "ff", "e0", "ca3fc00000", "ca3dcccccd", "ca7fc00000", "ca7f800001", "caffc00001", "ca7fa00000", "ca7f800000", "caff800000", "ca80000000",
	"cb7ff8000000000000", "cb7ff8000000000001", "cbfff8000000000000", "cb7ff0000000000001", "cb8000000000000000", "01ff", "81c001", "81c301", "81c201", "81cb3ff800000000000001",
	"81cb7ff800000000000101", "81cb7ff000000000000001", "81cbfff000000000000001", "81c40201ff01", "81d4017b01", "81c70502000000026e01", "81d6ff6ae4472701", "81d50701020a", "8192a161a16201",
	"8181a1610102", "81ff01", "820001ff02", "820a01a16102", "82a161010a02", "83a16101a16102a16203", "83a16201a16102a16103", "83000100020103", "83010100020003",
	"81cfffffffffffffffff01", "81ce0100000101", "81ce0100000001", "82050a000b", "a2c328", "81a1ff01", "82a1ff01a3efbfbd02", "82a3efbfbd02a1ff01", "c70007", "c70001", "c70002", "c700ff",
	"c70307010203", "c8000107aa", "c90000000107aa", "d807000102030405060708090a0b0c0d0e0f", "c7030100007b", "c7060200000002006e", "d7ffee6b27fc00000000", "d90161", "da000161",
	"db0000000161", "c5000101", "c60000000101", "dc000101", "dd0000000101", "de0001a16101", "df00000001a16101", "cc01", "cd0001", "ce00000001", "d001", "d10001", "d200000001",
	"d30000000000000001", "d3ffffffffffffffff", "cf0000000000000001", "cf7fffffffffffffff", "cf8000000000000000", "cfffffffffffffffff", "a261", "82a16101", "9191919101", "d48001", "d40001",
	"d40301", "c705028000000001", "c705027fffffff01", "c70cffffffffff0000000000000000", "c70cff3b9aca000000000000000000", "c70cff3b9ac9ff0000000000000000", "d5ff0000", "d6ffffffffff",
	"d7ffee6b280000000000", "d8ff00000000000000000000000000000000", "c705ff0000000000", "c70cff00000000000000006ae44727", "ddffffffff", "dfffffffff", "dbffffffff", "c6ffffffff", "c9ffffffff07",
	"8c00000101020203030404050506060707080809090a0aa16b63", "82a2efbfbd01a2ee800002", "82a4f09f988001a3efbfbf02", "82a3efbfbf02a4f09f988001", "82a3ee800001a4f090808002",
	"c7020100ff", "c70201ff00", "d5010000", "d501ffff", "d6020000000a", "c7040200000002", "c70502000000640a", "c70502fff0bdc001", "c70502000f424001", "c70502fff0bdbf01", "c70502000f424101",
	"81a0c0", "81a000", "8200c0a0c0", "82a0c000c0", "81d000c0", "81cc00c0", "81cd0000c0", "83000001010202", "8400000101a161020303", "d40100", "d40200", "d4ff00",
	"82a161c0a161c0", "8fa16100a16100a16100a16100a16100a16100a16100a16100a16100a16100a16100a16100a16100a16100a16100", "92c0c1", "81a568656c6c6f93010203",
	"a5c0c1f5f6ff", "a6eda080edb080", "a4f4908080", "a3e08080", "a2c080", "a4f0808080", "a3ed9fbf", "a3eda080", "a3efbfbe", "a4f48fbfbf", "a1c2", "a2e282", "a3f09f98",
}

func unpackFile(scale int) []string {
	var inputs [][]byte
	for _, h := range handUnpack {
		b, err := hex.DecodeString(h)
		if err != nil {
			panic(h)
		}
		inputs = append(inputs, b)
	}
	for i := 0; i < 700*scale; i++ {
		v := randValue(4)
		packed, err := val.Pack(v)
		if err != nil {
			panic(err)
		}
		inputs = append(inputs, packed)
		w := &sloppy{}
		w.value(v)
		inputs = append(inputs, w.b)
		if i%2 == 0 {
			inputs = append(inputs, mutate(packed))
		}
		if i%3 == 0 {
			inputs = append(inputs, mutate(w.b))
		}
		if i%5 == 0 {
			inputs = append(inputs, randBytes(24))
		}
	}
	seen := map[string]bool{}
	var lines []string
	for _, in := range inputs {
		if seen[string(in)] {
			continue
		}
		seen[string(in)] = true
		if line, ok := unpackLine(in); ok {
			lines = append(lines, line)
		}
	}
	return lines
}

// --- json.tsv -------------------------------------------------------------------

func jsonLine(text string) (line string) {
	defer func() {
		if r := recover(); r != nil {
			line = tsv(b64(text), "panic")
		}
	}()
	v, err := val.ParseJSON([]byte(text))
	if err != nil {
		return tsv(b64(text), "err")
	}
	return tsv(b64(text), "ok", val.Hex(v))
}

var handJSON = []string{
	``, `{`, `[1,]`, `{"a":1,}`, `01`, `+1`, `.5`, `1.`, `1e`, `-`, `tru`, `nul`, `"abc`, `{"a":1,"a":2}`, `"\ud800"`, `"\udc00x"`, `"\ud800A"`, "\"\x01\"", "\"\xff\"", `"\x"`, `1 2`,
	`{"$long":"x"}`, `{"$long":5}`, `{"$long":"1.5"}`, `{"$long":"99999999999999999999"}`, `{"$double":"nan"}`, `{"$double":"0x10"}`, `{"$double":"1e999"}`, `{"$bigint":"1.0"}`,
	`{"$decimal":"abc"}`, `{"$decimal":"1e99999999"}`, `{"$bin":"!!"}`, `{"$timestamp":"yesterday"}`, `{"$sparse":[1]}`, `{"$sparse":{"01":1}}`, `{"$sparse":{"-1":1}}`,
	`{"$ext":{"type":200,"base64":""}}`, `{"$ext":{"type":1.5,"base64":""}}`, `{"$ext":{"base64":""}}`, `{"$map":1}`, `{"$timestamp":"2026-10-30T07:28:39Z", "x":`,
	`{"$long":"5","other":true}`, `{"$ext":{"type":-1,"base64":"aeRHJw=="}}`, `["é😀\/", {"$bin":"AQI"}]`, ` {"qty": 5, "price": 1.10, "big": 123456789012345678901234567890, "exp": -1.5E-3, "z": -0} `,
	`null`, `true`, `false`, `0`, `-0`, `1`, `1.0`, `1.10`, `1e3`, `1E3`, `1e+3`, `1e-3`, `-1.5e-3`, `0.0`, `0e0`, `0.000`, `1e1048576`, `1e1048577`, `1e-1048576`, `1e-1048577`, `1.5e1048576`,
	`15e1048575`, `0.1e1048577`, `1e2147483647`, `1e2147483648`, `1e-2147483648`, `1.0e-2147483648`, `1e99999999999999999999`, `123456789012345678901234567890.123456789`,
	`"a"`, `""`, `"A"`, `"é"`, `"😀"`, `"😀"`, `"\ud83d"`, `"\ud83dA"`, `"\ude00\ud83d"`, `"\u12"`, `"\u12G4"`, `"\b\f\n\r\t\"\\\/"`, `"\a"`, "\"\t\"", "\"\x7f\"", "\"\u2028\"",
	`[]`, `[ ]`, `[1]`, `[1,2]`, `[1 2]`, `[,]`, `[1,,2]`, `{}`, `{ }`, `{"a":1}`, `{"a" :1}`, `{"a": 1 ,"b":2}`, `{a:1}`, `{"a"}`, `{"a":}`, `{,}`, `{"b":1,"a":2}`, `{"a":{"b":{"c":[]}}}`,
	`{"$long":"5"}`, `{"$long":"-0"}`, `{"$long":"05"}`, `{"$long":"+5"}`, `{"$long":" 5"}`, `{"$long":"9223372036854775807"}`, `{"$long":"9223372036854775808"}`, `{"$long":"-9223372036854775808"}`,
	`{"$long":"-9223372036854775809"}`, `{"$long":"1e3"}`, `{"$long":""}`, `{"$long":null}`, `{"$long":{"$long":"5"}}`, `{"$long":["5"]}`,
	`{"$double":"1.5"}`, `{"$double":"-0"}`, `{"$double":"0"}`, `{"$double":"NaN"}`, `{"$double":"Infinity"}`, `{"$double":"-Infinity"}`, `{"$double":"+Infinity"}`, `{"$double":"infinity"}`,
	`{"$double":"1e308"}`, `{"$double":"1.7976931348623157e308"}`, `{"$double":"1.7976931348623159e308"}`, `{"$double":"1e-400"}`, `{"$double":"5e-324"}`, `{"$double":"2.4e-324"}`, `{"$double":"1"}`,
	`{"$double":"01"}`, `{"$double":".5"}`, `{"$double":"1."}`, `{"$double":1.5}`, `{"$double":"1_0"}`, `{"$double":"0x1p4"}`, `{"$double":"1e5"}`, `{"$double":"1E5"}`,
	`{"$bigint":"0"}`, `{"$bigint":"-0"}`, `{"$bigint":"123456789012345678901234567890"}`, `{"$bigint":"-7"}`, `{"$bigint":"1e3"}`, `{"$bigint":"007"}`, `{"$bigint":"+7"}`, `{"$bigint":7}`,
	`{"$decimal":"1.10"}`, `{"$decimal":"-0.00"}`, `{"$decimal":"1e3"}`, `{"$decimal":"1E-10"}`, `{"$decimal":"1e400"}`, `{"$decimal":"00.1"}`, `{"$decimal":".1"}`, `{"$decimal":"1e1048577"}`, `{"$decimal":1.10}`,
	`{"$bin":""}`, `{"$bin":"AQI="}`, `{"$bin":"AQI"}`, `{"$bin":"AQID"}`, `{"$bin":"AQ=="}`, `{"$bin":"AQ="}`, `{"$bin":"A"}`, `{"$bin":"AQI=="}`, `{"$bin":"AQ\nID"}`, `{"$bin":"A Q"}`, `{"$bin":"QR"}`, `{"$bin":"QR=="}`,
	`{"$bin":"="}`, `{"$bin":"===="}`, `{"$bin":"AQID="}`, `{"$bin":"AQIDBA"}`, `{"$bin":"AQIDBAU="}`, `{"$bin":"-_"}`, `{"$bin":123}`, `{"$bin":"AQID\r\n"}`, `{"$bin":"AQ==AQ=="}`,
	`{"$timestamp":"2026-10-30T07:28:39.075Z"}`, `{"$timestamp":"2026-10-30T09:28:39.075+02:00"}`, `{"$timestamp":"2026-10-30T7:28:39Z"}`, `{"$timestamp":"2026-10-30T07:28:39,5Z"}`,
	`{"$timestamp":"2026-10-30t07:28:39Z"}`, `{"$timestamp":"10000-01-01T00:00:00Z"}`, `{"$timestamp":"0000-01-01T00:00:00Z"}`, `{"$timestamp":1793345319}`, `{"$timestamp":"2026-02-30T00:00:00Z"}`,
	`{"$sparse":{}}`, `{"$sparse":{"0":"a","5":"b"}}`, `{"$sparse":{"5":"b","0":"a"}}`, `{"$sparse":{"10":true,"2":null}}`, `{"$sparse":{"16777216":1}}`, `{"$sparse":{"16777217":1}}`,
	`{"$sparse":{"00":1}}`, `{"$sparse":{"1.0":1}}`, `{"$sparse":{"":1}}`, `{"$sparse":{"12345678901":1}}`, `{"$sparse":{"a":1}}`, `{"$sparse":{"$long":"5"}}`, `{"$sparse":{"$map":{"1":2}}}`,
	`{"$sparse":{"1":{"$sparse":{"2":3}}}}`, `{"$sparse":"x"}`, `{"$sparse":null}`, `{"$sparse":{"1":1,"1":2}}`, `{"$sparse":{"٣":1}}`,
	`{"$ext":{"base64":"AQ==","type":7}}`, `{"$ext":{"type":7,"base64":"AQ=="}}`, `{"$ext":{"base64":"AQ==","type":-7}}`, `{"$ext":{"base64":"AQ==","type":127}}`, `{"$ext":{"base64":"AQ==","type":128}}`,
	`{"$ext":{"base64":"AQ==","type":-128}}`, `{"$ext":{"base64":"AQ==","type":-129}}`, `{"$ext":{"base64":"AQ==","type":7.0}}`, `{"$ext":{"base64":"AQ==","type":0.7e1}}`, `{"$ext":{"base64":"AQ==","type":70e-1}}`,
	`{"$ext":{"base64":"AQ==","type":{"$long":"7"}}}`, `{"$ext":{"base64":"AQ==","type":{"$double":"7"}}}`, `{"$ext":{"base64":"AQ==","type":{"$double":"NaN"}}}`, `{"$ext":{"base64":"AQ==","type":{"$bigint":"7"}}}`,
	`{"$ext":{"base64":"AQ==","type":"7"}}`, `{"$ext":{"base64":{"$bin":"AQ=="},"type":7}}`, `{"$ext":{"base64":"AQ==","type":7,"x":1}}`, `{"$ext":{"base64":"ew==","type":1}}`, `{"$ext":{"base64":"","type":1}}`,
	`{"$ext":{"base64":"AAAAAm4=","type":2}}`, `{"$ext":{"base64":"AAAA","type":2}}`, `{"$ext":{"base64":"AQ==","type":-1}}`, `{"$ext":{"base64":"","type":0}}`, `{"$ext":[]}`, `{"$ext":{}}`,
	`{"$ext":{"base64":"AQ==","type":1e400}}`, `{"$ext":{"base64":"AQ==","type":1e-400}}`, `{"$ext":{"base64":"AQ==","type":0e400}}`, `{"$ext":{"base64":"AQ==","type":0.0}}`,
	`{"$map":{}}`, `{"$map":{"$bin":"x"}}`, `{"$map":{"$map":{"$long":"1"}}}`, `{"$map":{"a":1,"b":2}}`, `{"$map":{"$long":"5","x":1}}`, `{"$map":[]}`, `{"$map":"x"}`, `{"$map":{"a":1,"a":2}}`,
	`{"$map":{"$map":{"$map":{}}}}`, `{"$unknown":"x"}`, `{"$":1}`, `{"$Long":"5"}`, `[{"$long":"1"},{"$long":"2"}]`, `{"a":{"$long":"1"},"b":{"$double":"2"}}`,
	// Go panics on these two (an infinite extension type); no implementation may.
	`{"$ext":{"base64":"","type":{"$double":"Infinity"}}}`, `{"$ext":{"base64":"","type":{"$double":"-Infinity"}}}`,
	"\ufeff1", "1\n", "\t\r\n 1 \t\r\n", "\v1", "1\x00", "[1]x", "nullx", "truefalse", "{\"a\":1}{}", "/**/1", "'a'", "NaN", "Infinity", "-Infinity", "0x10", "1_000", "--1", "-01", "1.e3", "1.5.5", "1ee3", "1e+-3",
}

func mutateText(s string) string {
	b := []byte(s)
	alphabet := `{}[]":,$0123456789.eE-+\ntrufalsbd`
	for i := 1 + rng.Intn(2); i > 0; i-- {
		switch rng.Intn(4) {
		case 0:
			if len(b) > 0 {
				b[rng.Intn(len(b))] = alphabet[rng.Intn(len(alphabet))]
			}
		case 1:
			at := rng.Intn(len(b) + 1)
			b = append(b[:at], append([]byte{alphabet[rng.Intn(len(alphabet))]}, b[at:]...)...)
		case 2:
			if len(b) > 1 {
				at := rng.Intn(len(b))
				b = append(b[:at], b[at+1:]...)
			}
		default:
			if len(b) > 0 {
				b = b[:rng.Intn(len(b))]
			}
		}
	}
	return string(b)
}

func jsonFile(scale int) []string {
	texts := append([]string(nil), handJSON...)
	texts = append(texts, strings.Repeat("[", 1001)+strings.Repeat("]", 1001), strings.Repeat("[", 1002)+strings.Repeat("]", 1002),
		strings.Repeat(`{"a":`, 1001)+"1"+strings.Repeat("}", 1001), strings.Repeat(`{"a":`, 1000)+"1"+strings.Repeat("}", 1000))
	for i := 0; i < 400*scale; i++ {
		v := randValue(3)
		for _, mode := range []val.JSONMode{val.JSONCanonical, val.JSONRelaxed} {
			text, err := val.FormatJSON(v, mode)
			if err != nil {
				continue
			}
			texts = append(texts, string(text))
			if i%2 == 0 {
				texts = append(texts, mutateText(string(text)))
			}
		}
	}
	seen := map[string]bool{}
	var lines []string
	for _, text := range texts {
		if seen[text] {
			continue
		}
		seen[text] = true
		lines = append(lines, jsonLine(text))
	}
	return lines
}

// --- double.tsv, float.tsv --------------------------------------------------------

func decimalParts(d decimal.Decimal) string {
	return d.Coefficient().String() + "|" + strconv.Itoa(int(d.Exponent()))
}

func doubleFile(scale int) []string {
	var lines []string
	seen := map[uint64]bool{}
	add := func(d float64) {
		bits := math.Float64bits(d)
		if seen[bits] {
			return
		}
		seen[bits] = true
		n := val.Double(d)
		exact := "ERR"
		if x, err := val.ExactDecimal(n); err == nil {
			exact = decimalParts(x)
		}
		dec := "PANIC"
		func() {
			defer func() { recover() }()
			dec = decimalParts(n.Decimal())
		}()
		lines = append(lines, tsv(fmt.Sprintf("%016x", bits), val.DoubleText(d), n.String(), val.Jsonify(n), exact, dec))
	}
	for _, d := range edgeDoubles {
		add(d)
		add(-d)
		add(math.Nextafter(d, math.Inf(1)))
		add(math.Nextafter(d, math.Inf(-1)))
	}
	for e := -1074; e <= 1023; e += 7 {
		add(math.Ldexp(1, e))
		add(math.Nextafter(math.Ldexp(1, e), 0))
	}
	for e := -330; e <= 310; e += 3 {
		f, _ := strconv.ParseFloat("1e"+strconv.Itoa(e), 64)
		add(f)
		add(math.Nextafter(f, 0))
	}
	for i := 0; i < 1500*scale; i++ {
		add(randDouble())
	}
	return lines
}

func floatFile(scale int) []string {
	var lines []string
	seen := map[uint32]bool{}
	add := func(f float32) {
		bits := math.Float32bits(f)
		if seen[bits] || f != f || math.IsInf(float64(f), 0) || f == 0 {
			return
		}
		seen[bits] = true
		// The shortest digits of the float and the position of its decimal point.
		e := strconv.FormatFloat(math.Abs(float64(f)), 'e', -1, 32)
		at := strings.IndexByte(e, 'e')
		exp, _ := strconv.Atoi(e[at+1:])
		lines = append(lines, tsv(fmt.Sprintf("%08x", bits), strings.Replace(e[:at], ".", "", 1), strconv.Itoa(exp+1)))
	}
	for _, f := range []float32{1, 0.1, 0.5, 16777216, 16777217, 3.4028235e38, 1e-45, 1.1754944e-38, 1.17549421e-38, 100, 1e10, 123456.7, 0.3, 1.0 / 3} {
		add(f)
		add(-f)
		add(math.Nextafter32(f, 0))
	}
	for e := -149; e <= 127; e += 3 {
		add(float32(math.Ldexp(1, e)))
		add(math.Nextafter32(float32(math.Ldexp(1, e)), 0))
	}
	for i := 0; i < 1000*scale; i++ {
		switch rng.Intn(3) {
		case 0:
			add(math.Float32frombits(rng.Uint32()))
		case 1:
			f, _ := strconv.ParseFloat(fmt.Sprintf("%d.%de%d", rng.Intn(100), rng.Intn(100), rng.Intn(30)-15), 32)
			add(float32(f))
		default:
			add(float32(rng.Intn(1 << 25)))
		}
	}
	return lines
}

// --- timestamp.tsv, timestamp_parse.tsv --------------------------------------------

func timestampFile(scale int) []string {
	var lines []string
	add := func(sec, nsec int64) {
		ts := val.UnixTime(sec, nsec)
		s, n := ts.Unix()
		lines = append(lines, tsv(strconv.FormatInt(s, 10), strconv.Itoa(int(n)), ts.String(), val.Hex(ts)))
	}
	for _, sec := range []int64{0, -1, 1, 59, 60, 86399, 86400, -86400, -86401, 951782400, 951868800, 1<<32 - 1, 1 << 32, 1<<34 - 1, 1 << 34, 253402300799, 253402300800,
		-62167219200, -62167219201, -62135596800, -62135596801, math.MaxInt64, math.MinInt64, math.MaxInt64 - 1, math.MinInt64 + 1, -9223372028741760000, -9223372028741760001,
		-9223372028715321600, -9223372028715321601, 9223371974719179007, 4102444800, 4107542400, 13569465600, -2208988800, -11644473600} {
		add(sec, 0)
		add(sec, 1)
		add(sec, 999999999)
		add(sec, 75000000)
	}
	for i := 0; i < 800*scale; i++ {
		ts := randTimestamp()
		s, n := ts.Unix()
		add(s, int64(n))
	}
	return lines
}

var handTimestamps = []string{
	"2026-10-30T07:28:39Z", "2026-10-30t07:28:39Z", "2026-10-30T07:28:39z", "2026-10-30 07:28:39Z", "2026-10-30T7:28:39Z", "2026-10-30T07:8:39Z", "2026-10-30T07:28:9Z",
	"2026-1-30T07:28:39Z", "2026-10-3T07:28:39Z", "2026-10-30T07:28:39.Z", "2026-10-30T07:28:39.1Z", "2026-10-30T07:28:39,1Z", "2026-10-30T07:28:39.1234567891Z",
	"2026-10-30T07:28:39.12345678999Z", "2026-10-30T07:28:39+00:00", "2026-10-30T07:28:39-00:00", "2026-10-30T07:28:39+24:00", "2026-10-30T07:28:39+24:60",
	"2026-10-30T07:28:39+25:00", "2026-10-30T07:28:39+00:61", "2026-10-30T07:28:39+0200", "2026-10-30T07:28:39+02", "2026-10-30T07:28:39+02:00:00", "2026-10-30T07:28:39",
	"2026-10-30T24:00:00Z", "2026-10-30T23:60:00Z", "2026-10-30T23:59:60Z", "2026-02-30T00:00:00Z", "2024-02-29T00:00:00Z", "2023-02-29T00:00:00Z", "2000-02-29T00:00:00Z",
	"1900-02-29T00:00:00Z", "2100-02-29T00:00:00Z", "2026-13-01T00:00:00Z", "2026-00-01T00:00:00Z", "2026-01-00T00:00:00Z", "2026-04-31T00:00:00Z", "2026-12-31T23:59:59Z",
	"0000-01-01T00:00:00Z", "0000-02-29T00:00:00Z", "9999-12-31T23:59:59.999999999Z", "10000-01-01T00:00:00Z", "-0001-01-01T00:00:00Z", "0000-01-01T00:00:00+23:59",
	"9999-12-31T23:59:59-23:59", " 2026-10-30T07:28:39Z", "2026-10-30T07:28:39Z ", "2026-10-30T07:28:39ZZ", "", "2026-10-30T07:28:39.000000000Z", "2026-10-30T07:28:39.5+02:00",
	"+026-10-30T07:28:39Z", "2026-10-30T+7:28:39Z", "2026-10-30T07:28:39 Z", "2026-10-30T07:28:39.1 Z", "2026-10-30T07:28:39.١Z", "2026-10-30T1:2:3Z", "2026-10-30T01:02:03+2:00",
	"2026-10-30T012:02:03Z", "2026-10-30T07:28:39Z07:00", "2026-10-30T07:28:39,Z", "2026-10-30T07:28:39.999999999999999999Z", "2026-10-30T07:28:39.000000000000Z",
	"2026-10-30T07:28:39+02:00Z", "2026-10-30T07:28:39−02:00", "2026-10-30T07:28:39+2:000", "2026-10-30T07:28:39+ab:cd", "2026-10-30T07:28:39+02-00", "2026-10-30T00:00:00Z",
	"2026-10-30T0:00:00Z", "2026-10-30T9:59:59.9Z", "2026-10-30T23:59:59+24:60", "2026-10-30T07:28:39.5", "2026-10-30T07:28:39.", "2026-10-30T07:28:39,", "2026-10-30T07:28:39.5z",
	"２０２６-10-30T07:28:39Z", "2026-10-30T07:28:39Z\n", "2026/10/30T07:28:39Z", "20261030T072839Z", "2026-10-30", "T07:28:39Z", "2026-10-30T07:28Z", "2026-10-30T07Z",
}

func timestampParseFile(scale int) []string {
	texts := append([]string(nil), handTimestamps...)
	for i := 0; i < 400*scale; i++ {
		// A timestamp with a four-digit year, as text of several shapes.
		sec := rng.Int63n(253402300800+62167219200) - 62167219200
		nsec := int64(0)
		if rng.Intn(2) == 0 {
			nsec = rng.Int63n(1e9)
		}
		text := val.UnixTime(sec, nsec).String()
		if rng.Intn(3) == 0 {
			text = strings.TrimSuffix(text, "Z") + fmt.Sprintf("%c%02d:%02d", "+-"[rng.Intn(2)], rng.Intn(26), rng.Intn(62))
		}
		texts = append(texts, text)
		b := []byte(text)
		alphabet := "0123456789-:.,TZ+ tz"
		for j := 1 + rng.Intn(2); j > 0 && len(b) > 0; j-- {
			switch rng.Intn(3) {
			case 0:
				b[rng.Intn(len(b))] = alphabet[rng.Intn(len(alphabet))]
			case 1:
				at := rng.Intn(len(b))
				b = append(b[:at], b[at+1:]...)
			default:
				at := rng.Intn(len(b) + 1)
				b = append(b[:at], append([]byte{alphabet[rng.Intn(len(alphabet))]}, b[at:]...)...)
			}
		}
		texts = append(texts, string(b))
	}
	seen := map[string]bool{}
	var lines []string
	for _, text := range texts {
		if seen[text] {
			continue
		}
		seen[text] = true
		ts, err := val.ParseTimestamp(text)
		if err != nil {
			lines = append(lines, tsv(b64(text), "err"))
			continue
		}
		sec, nsec := ts.Unix()
		lines = append(lines, tsv(b64(text), "ok", strconv.FormatInt(sec, 10), strconv.Itoa(int(nsec))))
	}
	return lines
}

// --- number.tsv --------------------------------------------------------------------

func guard(f func() string) (out string) {
	defer func() {
		if r := recover(); r != nil {
			out = "PANIC"
		}
	}()
	return f()
}

func numberFile(scale int) []string {
	var numbers []val.Number
	for _, l := range edgeLongs {
		numbers = append(numbers, val.Long(l), val.BigInt(big.NewInt(l)), val.Decimal(decimal.NewFromInt(l)), val.Double(float64(l)))
	}
	for _, d := range edgeDoubles {
		numbers = append(numbers, val.Double(d), val.Double(-d))
	}
	for _, s := range []string{"0", "1.10", "5.00", "5.5", "-5.5", "0.5", "-0.5", "12e2", "1e3", "-25e2", "123.99", "-1.5", "1e400", "1e-400", "0.1", "0.12345678901234567890",
		"9223372036854775808", "-9223372036854775809", "18446744073709551615", "18446744073709551616", "65535", "65536", "255", "256", "-129", "128", "3.4028235e38", "3.4028236e38",
		"1.7976931348623157e308", "1.7976931348623159e308", "4.9e-324", "2e-324", "16777217", "9007199254740993", "0.30000000000000004", "1e22", "1e23", "0.000001", "1e-7"} {
		numbers = append(numbers, val.Decimal(decimal.RequireFromString(s)))
	}
	for i := 0; i < 400*scale; i++ {
		switch rng.Intn(4) {
		case 0:
			numbers = append(numbers, val.Long(randLong()))
		case 1:
			numbers = append(numbers, val.Double(randDouble()))
		case 2:
			numbers = append(numbers, val.BigInt(randBig()))
		default:
			d := randDecimal()
			if d.Exponent() > 400 || d.Exponent() < -400 {
				d = decimal.NewFromBigInt(d.Coefficient(), d.Exponent()%400)
			}
			numbers = append(numbers, val.Decimal(d))
		}
	}
	var lines []string
	for _, n := range numbers {
		long := "-"
		// Go leaves a float outside the range of an integer to the platform.
		if d := n.Double(); n.Type() != val.DOUBLE || math.IsNaN(d) || (d > -9.2e18 && d < 9.2e18) {
			long = strconv.FormatInt(n.Long(), 10)
		}
		bigInt := guard(func() string {
			if x := n.BigInt(); x != nil {
				return x.String()
			}
			return "PANIC"
		})
		dec := guard(func() string { return decimalParts(n.Decimal()) })
		exactBig := guard(func() string {
			x, err := val.ExactBigInt(n)
			if err != nil {
				return "ERR"
			}
			return x.String()
		})
		exactDec := guard(func() string {
			x, err := val.ExactDecimal(n)
			if err != nil {
				return "ERR"
			}
			return decimalParts(x)
		})
		exact := func(f func() (string, error)) string {
			return guard(func() string {
				s, err := f()
				if err != nil {
					return "ERR"
				}
				return s
			})
		}
		ints := []string{}
		for _, bits := range []int{8, 16, 32, 64} {
			bits := bits
			ints = append(ints, exact(func() (string, error) { x, err := val.ExactInt(n, bits); return strconv.FormatInt(x, 10), err }))
			ints = append(ints, exact(func() (string, error) { x, err := val.ExactUint(n, bits); return strconv.FormatUint(x, 10), err }))
		}
		f64 := exact(func() (string, error) {
			x, err := val.ExactFloat(n, 64)
			return fmt.Sprintf("%016x", math.Float64bits(x)), err
		})
		f32 := exact(func() (string, error) {
			x, err := val.ExactFloat(n, 32)
			return fmt.Sprintf("%08x", math.Float32bits(float32(x))), err
		})
		double := fmt.Sprintf("%016x", math.Float64bits(n.Double()))
		if n.IsNaN() {
			double = "nan"
		}
		lines = append(lines, tsv(val.Hex(n), n.String(), long, double, bigInt, dec, exactBig, exactDec, strings.Join(ints, ","), f64, f32))
	}
	return lines
}

// --- parse.tsv: the texts the typed getters read -----------------------------------

func parseFile(scale int) []string {
	texts := []string{"", "null", "nan", "NaN", "NAN", "+nan", "-nan", "inf", "Inf", "+inf", "-Inf", "infinity", "Infinity", "-INFINITY", "infi", "in", "1_000.5", "1_000", "1__0.5", "_1.5", "1_.5",
		"1._5", "1.5_", "0x_1p0", "0x1_0p0", "0x1p4", "0x1.8p1", "0X1.8P1", "0x1.8", "0x.8p1", "0x1p", "0x1p+", "0x1p-2", "1e5", "1E5", "1e+5", "1e-5", "1e", "e5", ".5", "5.", ".", "+.5", "-.5", "+5",
		"-5", "++5", "5e3.2", "1.5e3", "1.5e", "1.5e+", "1.5E-3", "1e400", "1.0e400", "-1e400", "1e-400", "1.0e-400", "4.9e-324", "2.4e-324", "2.5e-324", "0x", "-0x", "0X10", "0xx02", "0x1x",
		"0x1x-", "0x1x00", "0xfg", "0x123", "-0x1x02", "0x1xff", "0x01x7fffffff", "0x01xffffffff", "0x1X2", "9223372036854775807", "9223372036854775808",
		"-9223372036854775808", "-9223372036854775809", "+9223372036854775808", "00012", "-0", "+0", "1 ", " 1", "1\n", "0b101", "0o17", "017", "1,5", "١", "１２", "12ab", "true", "false",
		"0.1", "-0.0", "1.0", "100.", "1e0", "0e0", "0x0", "0x00x00", "1.7976931348623157e308", "1.7976931348623159e308", "123456789012345678901234567890", "1.", "0.", "-.", "+", "-",
		// Go panics on an exponent of more than four bytes; no implementation may.
		"0xx0102030405", "0x1x0102030405", "-0x7bx-0102030405",
		"0x7b", "-0x7b", "0x01e2afx-03", "-0x01e2afx-03", "0X7B", "0x7B", "0xABCDEF", "0x1x-1", "0x1x1", "0x1x-0001", "0x-1", "-0x-1", "--0x1", "0x1.5", "0x1p1_0", "0x1p_1", "0x1_p1", "1e1_0", "1e_1",
		"1_e1", "1e+_1", "0x1P+3", "0x.p1", "0xp1", "0x1.p1", ".e1", "1.e1", "infinityx", "+infinity", "+infinit", "nanx", "Nan", "INF", "+INF", "-inf", "1e308", "1e309", "0x1p1023", "0x1p1024",
		"0x1p-1074", "0x1p-1075", "0x1p-1076", "0x1.fffffffffffffp1023", "0x1.fffffffffffff8p1023", "1e23", "8.41e21", "1e22", "5e-324", "t", "T", "TRUE", "True", "tRUE", "1", "0", "f", "F",
		"FALSE", "False", "yes", "on", " true", "base64,AQID", "base64,AQI", "base64,AQI=", "base64,", "base64,A", "base64,AQ\nID", "base64,!!", "base64, AQID", "Base64,AQID",
		"base64,AQIDBA==", "hello", "base64,QQ", "base64,QR", "base64,AQID\r\n", "base64,=", "base64,AQ==", "base64,é", "base64", "base64,AQIDBA", "base64,AQIDBAU", "base64,AQIDBAUG"}
	alphabet := "0123456789+-.eExXpP_abfinNIFT, "
	for i := 0; i < 800*scale; i++ {
		n := 1 + rng.Intn(10)
		b := make([]byte, n)
		for j := range b {
			if rng.Intn(3) == 0 {
				b[j] = alphabet[rng.Intn(len(alphabet))]
			} else {
				b[j] = "0123456789"[rng.Intn(10)]
			}
		}
		prefix := []string{"", "", "", "-", "+", "0x", "-0x", "0X"}[rng.Intn(8)]
		texts = append(texts, prefix+string(b))
	}
	seen := map[string]bool{}
	var lines []string
	for _, text := range texts {
		if seen[text] {
			continue
		}
		seen[text] = true
		number := guard(func() string { return val.Hex(val.ParseNumber(text)) })
		lines = append(lines, tsv(b64(text), number, strconv.FormatBool(bool(val.ParseBoolean(text))), val.Hex(val.ParseString(text))))
	}
	return lines
}

// --- base64.tsv ---------------------------------------------------------------------

func base64File(scale int) []string {
	texts := []string{"", "=", "==", "A", "AA", "AAA", "AAAA", "AA==", "AAA=", "AA=", "A===", "AA=A", "AAA==", "AAAA=", "AAAAAA==", "AAAAA", "AQ\nID", "AQ\r\nID\r\n", "\n", "A\nA==", "AA\n==",
		"AA=\n=", "AA==\n", "AA==\nAA==", "AA==AA", "AB==", "AAB=", "A A=", "-_", "+/", "+/+/", "AQID BA", "é", "AA\t=="}
	alphabet := "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
	for i := 0; i < 500*scale; i++ {
		n := rng.Intn(14)
		b := make([]byte, n)
		for j := range b {
			switch rng.Intn(20) {
			case 0:
				b[j] = '='
			case 1:
				b[j] = "\n\r -_.!"[rng.Intn(7)]
			default:
				b[j] = alphabet[rng.Intn(len(alphabet))]
			}
		}
		s := string(b)
		if rng.Intn(3) == 0 {
			s += strings.Repeat("=", rng.Intn(3))
		}
		texts = append(texts, s)
	}
	seen := map[string]bool{}
	var lines []string
	decode := func(enc *base64.Encoding, s string) string {
		b, err := enc.DecodeString(s)
		if err != nil {
			return "ERR"
		}
		return "ok:" + hex.EncodeToString(b)
	}
	for _, text := range texts {
		if seen[text] {
			continue
		}
		seen[text] = true
		lines = append(lines, tsv(b64(text), decode(base64.StdEncoding, text), decode(base64.RawStdEncoding, text)))
	}
	return lines
}

// --- codec.tsv: Go structs through Marshal, SignBytes and PackStruct -----------------

func sortedKeys(m map[string]string) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
