# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project aims to follow
[Semantic Versioning](https://semver.org/).

## v0.1.0 — unreleased

The first version: the Java implementation of
[`go.arpabet.com/value`](https://github.com/arpabet/value) v1.6.1, binary
compatible with it.

### Added

- **The value model**: `Value` and its kinds (`NullValue`, `BoolValue`,
  `NumberValue`, `StringValue`, `TimestampValue`, `ListValue`, `MapValue`,
  `ExtensionValue`), all immutable, with equality by canonical bytes.
- **Canonical MessagePack**: `Value.pack`, `Value.unpack`, `Value.read`,
  `Value.writeTo`, `Unpacker`, and the decoding `Limits` of the Go
  implementation. The decoder reads every MessagePack form and does not
  recurse.
- **Lossless JSON**: `Value.toJson` in the canonical and relaxed modes and
  `Value.parseJson`; `toString` and `jsonify` render as Go's `String()` and
  `Jsonify` do.
- **`ValueCodec`**: Java objects to and from values by `@Name`, `@OmitEmpty`,
  `@Sign` and `@Unsigned` (Go's `Marshal`, `Unmarshal`, `SignBytes` and
  `SignHash`), with lossless and missing-field modes, records, and converters
  for other types.
- **`StructCodec`**: the numeric schema of `@Tag` and `@Repeated` (Go's
  `PackStruct` and `UnpackStruct`).
- **Conformance**: the vectors of the format, a golden corpus written by the
  Go implementation, and the generator that writes it (`conformance/`).
- Java 17 bytecode with no dependencies; Android API 24 and up, checked by the
  build, with R8 rules in the jar.
