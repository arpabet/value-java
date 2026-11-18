/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package main

import (
	"fmt"
	"math"
	"math/big"
	"reflect"
	"strconv"
	"strings"
	"time"

	"github.com/shopspring/decimal"
	val "go.arpabet.com/value"
)

// The structs below have twins in the Java tests (CodecGoldenTest), field for
// field. codec.tsv holds what Go writes for them:
//
//	<case> marshal <hex>            Pack(Marshal(x))
//	<case> remarshal <hex>          the same of the struct read back from those bytes
//	<case> sign:<selector> <hex>    SignBytes(x, selector)
//	<case> struct <hex>             PackStruct(x)
//	convert <number hex> <default> <lossless>
//	                                a number unmarshaled into a field of each
//	                                numeric type, by the default and the
//	                                lossless codec

type inner struct {
	Epoch uint64 `value:"epoch"`
	Root  []byte `value:"root"`
}

type message struct {
	Account string            `value:"acc"`
	Device  string            `value:"dev"`
	Count   uint32            `value:"count"`
	Seq     int64             `value:"seq"`
	Live    bool              `value:"live"`
	DhPub   []byte            `value:"dh"`
	OPKs    map[string][]byte `value:"opk"`
	Names   []string          `value:"names"`
	Bundle  inner             `value:"bundle"`
	Skipped string            `value:"-"`
}

type allTypes struct {
	B     bool              `value:"b"`
	I8    int8              `value:"i8"`
	I16   int16             `value:"i16"`
	I32   int32             `value:"i32"`
	I64   int64             `value:"i64"`
	U8    uint8             `value:"u8"`
	U16   uint16            `value:"u16"`
	U32   uint32            `value:"u32"`
	U64   uint64            `value:"u64"`
	F32   float32           `value:"f32"`
	F64   float64           `value:"f64"`
	S     string            `value:"s"`
	Bin   []byte            `value:"bin"`
	Big   *big.Int          `value:"big"`
	Dec   decimal.Decimal   `value:"dec"`
	At    time.Time         `value:"at"`
	PI    *int64            `value:"pi"`
	PS    *inner            `value:"ps"`
	LI    []int64           `value:"li"`
	LU    []uint64          `value:"lu"`
	LB    [][]byte          `value:"lb"`
	LS    []inner           `value:"ls"`
	MS    map[string]string `value:"ms"`
	MU    map[string]uint64 `value:"mu"`
	V     val.Value         `value:"v"`
	Any   any               `value:"any"`
	Plain int
}

type optional struct {
	ID   string           `value:"id"`
	Note string           `value:"note,omitempty"`
	Data []byte           `value:"data,omitempty"`
	N    int              `value:"n,omitempty"`
	F    float64          `value:"f,omitempty"`
	On   bool             `value:"on,omitempty"`
	P    *inner           `value:"p,omitempty"`
	L    []string         `value:"l,omitempty"`
	M    map[string]int64 `value:"m,omitempty"`
	V    val.Value        `value:"v,omitempty"`
	U    uint64           `value:"u,omitempty"`
}

type license struct {
	Domain  string `value:"_dom" sign:"license"`
	Product string `value:"product" sign:"license,audit"`
	Seats   int    `value:"seats" sign:"license"`
	Note    string `value:"note,omitempty" sign:"license"`
	Sig     []byte `value:"sig"`
	Nested  inner  `value:"nested" sign:"audit"`
}

type Leaf struct {
	Text val.String `tag:"1"`
}

type Frame struct {
	BoolField   val.Bool      `tag:"1"`
	NumberField val.Number    `tag:"2"`
	StringField val.String    `tag:"3"`
	ListField   val.List      `tag:"4"`
	MapField    val.Map       `tag:"5"`
	TimeField   val.Timestamp `tag:"6"`
	Any         val.Value     `tag:"7"`
	Strings     []val.String  `tag:"20"`
	Trace       []val.String  `tag:"21" repeated:"true"`
	Leaves      []*Leaf       `tag:"30"`
	Hops        []*Leaf       `tag:"31" repeated:"true"`
	Leaf        *Leaf         `tag:"100"`
	Negative    val.Number    `tag:"-5"`
}

func codecFile() []string {
	var lines []string
	marshal := func(name string, x any) {
		v, err := val.Marshal(x)
		if err != nil {
			panic(fmt.Sprintf("%s: %v", name, err))
		}
		lines = append(lines, tsv(name, "marshal", val.Hex(v)))
		// What the struct read back from those bytes writes, which is not
		// always the same: a field that held the null value comes back unset.
		packed, _ := val.Pack(v)
		decoded, err := val.Unpack(packed, true)
		if err != nil {
			panic(fmt.Sprintf("%s: %v", name, err))
		}
		fresh := reflect.New(reflect.TypeOf(x))
		if err := val.Unmarshal(decoded, fresh.Interface()); err != nil {
			panic(fmt.Sprintf("%s: %v", name, err))
		}
		again, err := val.Marshal(fresh.Elem().Interface())
		if err != nil {
			panic(fmt.Sprintf("%s: %v", name, err))
		}
		lines = append(lines, tsv(name, "remarshal", val.Hex(again)))
	}
	sign := func(name string, x any, selector string) {
		b, err := val.SignBytes(x, selector)
		if err != nil {
			panic(fmt.Sprintf("%s: %v", name, err))
		}
		lines = append(lines, tsv(name, "sign:"+selector, fmt.Sprintf("%x", b)))
	}
	pack := func(name string, x any) {
		b, err := val.PackStruct(x)
		if err != nil {
			panic(fmt.Sprintf("%s: %v", name, err))
		}
		lines = append(lines, tsv(name, "struct", fmt.Sprintf("%x", b)))
	}

	marshal("message/zero", message{})
	marshal("message/full", message{
		Account: "vox-abc", Device: "dev-1", Count: 42, Seq: -7, Live: true,
		DhPub: []byte{1, 2, 3, 4}, OPKs: map[string][]byte{"k1": {9, 9}, "k2": {8}, "": nil},
		Names: []string{"alice", "bob", ""}, Bundle: inner{Epoch: 100, Root: []byte{0xaa, 0xbb}},
		Skipped: "not on the wire",
	})

	marshal("all/zero", allTypes{})
	seven := int64(7)
	marshal("all/full", allTypes{
		B: true, I8: -8, I16: -1600, I32: -320000, I64: -64000000000, U8: 200, U16: 60000, U32: 4000000000,
		U64: 18000000000000000000, F32: 0.1, F64: 0.1, S: "héllo ✓ 😀", Bin: []byte{0, 1, 0xfe, 0xff},
		Big: new(big.Int).Lsh(big.NewInt(1), 100), Dec: decimal.RequireFromString("-1234.5600"),
		At: time.Date(2026, 10, 30, 7, 28, 39, 75000000, time.UTC), PI: &seven, PS: &inner{Epoch: 1, Root: []byte{1}},
		LI: []int64{1, -2, math.MaxInt64, math.MinInt64}, LU: []uint64{0, math.MaxInt64, math.MaxInt64 + 1, math.MaxUint64},
		LB: [][]byte{{1}, nil, {}}, LS: []inner{{Epoch: 5}, {Root: []byte{5}}},
		MS: map[string]string{"b": "2", "a": "1", "B": "3", "aa": "4"}, MU: map[string]uint64{"max": math.MaxUint64, "one": 1},
		V: val.Tuple(val.Long(1), val.Utf8("x"), val.Null), Any: "text", Plain: 5,
	})
	marshal("all/limits", allTypes{
		I8: math.MinInt8, I16: math.MinInt16, I32: math.MinInt32, I64: math.MinInt64, U8: math.MaxUint8, U16: math.MaxUint16,
		U32: math.MaxUint32, U64: math.MaxUint64, F32: math.MaxFloat32, F64: math.SmallestNonzeroFloat64,
		Big: big.NewInt(0), Dec: decimal.New(1, 3), At: time.Unix(-1, 500000000), V: val.Null, Any: nil,
	})
	marshal("all/nan", allTypes{F32: float32(math.NaN()), F64: math.NaN(), Any: map[string]int{"a": 1}})

	marshal("optional/empty", optional{ID: "x"})
	marshal("optional/full", optional{ID: "x", Note: "hi", Data: []byte{1}, N: 1, F: 0.5, On: true, P: &inner{},
		L: []string{"a"}, M: map[string]int64{"k": 0}, V: val.Null, U: 1})
	marshal("optional/blank", optional{ID: "", Data: []byte{}, L: []string{}, M: map[string]int64{}, F: math.Copysign(0, -1)})

	lic := license{Domain: "license/v1", Product: "mailnite", Seats: 5, Sig: []byte{0xde, 0xad}, Nested: inner{Epoch: 9, Root: []byte{9}}}
	marshal("license", lic)
	sign("license", lic, "license")
	sign("license", lic, "audit")
	sign("license", lic, "none")

	pack("frame/empty", &Frame{})
	leaf := &Leaf{Text: val.Utf8("leaf")}
	pack("frame/full", &Frame{
		BoolField: val.True, NumberField: val.Long(123), StringField: val.Utf8("test"), ListField: val.Tuple(val.Long(1), val.Long(2)),
		MapField: val.ImmutableMapOf(map[string]val.Value{"k": val.Null}), TimeField: val.UnixTime(1793345319, 75000000),
		Any:     val.Raw([]byte{1, 2}, false),
		Strings: []val.String{val.Utf8("a"), val.Raw([]byte("b"), false)}, Trace: []val.String{val.Utf8("edge"), val.Utf8("relay")},
		Leaves: []*Leaf{leaf, {}}, Hops: []*Leaf{leaf, leaf}, Leaf: leaf, Negative: val.Double(-0.5),
	})
	pack("frame/blank", &Frame{Strings: []val.String{}, Trace: []val.String{}, Leaves: []*Leaf{}, Hops: []*Leaf{}, Any: val.Null})

	lines = append(lines, convertLines()...)
	return lines
}

// convertLines unmarshals a number into a field of each numeric type, one
// field at a time so that one refusal does not hide the others.
func convertLines() []string {
	var numbers []val.Number
	for _, l := range edgeLongs {
		numbers = append(numbers, val.Long(l))
	}
	for _, s := range []string{"0", "5", "5.00", "5.5", "-5.5", "-0.5", "12e2", "127", "128", "255", "256", "-128", "-129", "65535", "65536", "-32768",
		"4294967295", "4294967296", "9223372036854775807", "9223372036854775808", "18446744073709551615", "18446744073709551616", "18446744073709551621", "-1",
		"0.1", "1.10", "0.5", "0.12345678901234567890", "1e400", "3.4028235e38", "3.5e38", "16777217", "9007199254740993", "7.9", "-7.9", "1e-7"} {
		d := decimal.RequireFromString(s)
		numbers = append(numbers, val.Decimal(d))
		if d.IsInteger() {
			numbers = append(numbers, val.BigInt(d.BigInt()))
		}
	}
	for _, d := range []float64{0, math.Copysign(0, -1), 1, -1, 0.5, 0.1, 1.5, -1.5, 3, 127, 128, 255.9, -128.9, 65535, 4294967295, 1e10, 9007199254740992, 9.2e18, -9.2e18,
		math.MaxFloat32, 1e39, 5e-324, 16777217, math.NaN()} {
		numbers = append(numbers, val.Double(d))
	}
	field := func(codec val.ValueCodec, n val.Number, target any, show func() string) string {
		if err := codec.Unmarshal(val.ImmutableMapOf(map[string]val.Value{"x": n}), target); err != nil {
			return "ERR"
		}
		return show()
	}
	row := func(codec val.ValueCodec, n val.Number) string {
		var i8 struct {
			X int8 `value:"x"`
		}
		var i16 struct {
			X int16 `value:"x"`
		}
		var i32 struct {
			X int32 `value:"x"`
		}
		var i64 struct {
			X int64 `value:"x"`
		}
		var u8 struct {
			X uint8 `value:"x"`
		}
		var u16 struct {
			X uint16 `value:"x"`
		}
		var u32 struct {
			X uint32 `value:"x"`
		}
		var u64 struct {
			X uint64 `value:"x"`
		}
		var f32 struct {
			X float32 `value:"x"`
		}
		var f64 struct {
			X float64 `value:"x"`
		}
		var bi struct {
			X *big.Int `value:"x"`
		}
		var dec struct {
			X decimal.Decimal `value:"x"`
		}
		float := func(bits uint64, nan bool, width int) string {
			if nan {
				return "nan"
			}
			return fmt.Sprintf("%0*x", width, bits)
		}
		return strings.Join([]string{
			field(codec, n, &i8, func() string { return strconv.FormatInt(int64(i8.X), 10) }),
			field(codec, n, &i16, func() string { return strconv.FormatInt(int64(i16.X), 10) }),
			field(codec, n, &i32, func() string { return strconv.FormatInt(int64(i32.X), 10) }),
			field(codec, n, &i64, func() string { return strconv.FormatInt(i64.X, 10) }),
			field(codec, n, &u8, func() string { return strconv.FormatUint(uint64(u8.X), 10) }),
			field(codec, n, &u16, func() string { return strconv.FormatUint(uint64(u16.X), 10) }),
			field(codec, n, &u32, func() string { return strconv.FormatUint(uint64(u32.X), 10) }),
			field(codec, n, &u64, func() string { return strconv.FormatUint(u64.X, 10) }),
			field(codec, n, &f32, func() string { return float(uint64(math.Float32bits(f32.X)), f32.X != f32.X, 8) }),
			field(codec, n, &f64, func() string { return float(math.Float64bits(f64.X), f64.X != f64.X, 16) }),
			field(codec, n, &bi, func() string { return bi.X.String() }),
			field(codec, n, &dec, func() string { return decimalParts(dec.X) }),
		}, ",")
	}
	var lines []string
	for _, n := range numbers {
		// Go leaves a float outside the range of an integer to the platform.
		if d := n.Double(); n.Type() == val.DOUBLE && !math.IsNaN(d) && (d <= -9.2e18 || d >= 9.2e18) {
			continue
		}
		lines = append(lines, tsv("convert", val.Hex(n), row(val.DefaultValueCodec(), n), row(val.DefaultValueCodec().Lossless(), n)))
	}
	return lines
}
