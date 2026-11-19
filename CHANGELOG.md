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
  `ExtensionValue`), immutable, with equality by canonical bytes.
- **Mutable lists and maps** (Go's mutable collections):
  `Value.mutableList`, `Value.mutableSparseList`, `Value.mutableMap`,
  `toMutable`, `toImmutable`, `isMutable`, and `update` / `updateAt` (Go's
  `Update`). They are ordinary mutable objects: the methods that return
  another collection change a mutable one and return it, and it holds what it
  is given as it is. An immutable list or map takes an immutable copy of a
  mutable one, and nothing read from bytes or text is mutable.
- **Sealing**: `Value.seal`, `Value.unseal` and `Sealer` (Go's `Seal` and
  `Unseal`): NaCl box, that is X25519, XSalsa20 and Poly1305, in pure Java
  with no dependency, interoperable with the Go implementation in both
  directions. `Sealer` makes keys, does the key agreement once per peer, and
  seals bytes that are not a value in the same form.
- **Canonical MessagePack**: `Value.pack`, `Value.unpack`, `Value.read`,
  `Value.writeTo`, `Unpacker`, and the decoding `Limits` of the Go
  implementation. The decoder reads every MessagePack form.
- **A deeper value takes no more of the thread's stack**: decoding, packing,
  JSON in both directions, printing, comparing and hashing do not recurse
  with the depth of a value past its first levels, so the thousand levels a
  decoder accepts by default take under 16 KB of stack, as a few dozen do.
- **Lossless JSON**: `Value.toJson` in the canonical and relaxed modes and
  `Value.parseJson`; `toString` and `jsonify` render as Go's `String()` and
  `Jsonify` do.
- **`ValueCodec`**: Java objects to and from values by `@Name`, `@OmitEmpty`,
  `@Sign` and `@Unsigned` (Go's `Marshal`, `Unmarshal`, `SignBytes` and
  `SignHash`), with lossless and missing-field modes, records, and converters
  for other types. `ValueCodec.named` names fields by the annotation of
  another library, as Go's `JSONValueCodec` and `MsgpackValueCodec` do by
  `json` and `msgpack` tags.
- **`Value.setLegacyBigNumbers`** (Go's `LegacyBigNumbers`, a switch for the
  process as there), `Value.unpackLegacyBigInt` and
  `Value.unpackLegacyDecimal`: reading big integers and decimals in the
  layouts the Go implementation wrote before its v1.5.0, for migrating stored
  bytes.
- **The package variables of the Go implementation** as settings of the
  process: `Value.setBase64Prefix`, `Value.setUnknownPrefix`,
  `Value.setDecimalExpDelim`, `Value.setPrecisionLevel` and
  `Value.setFirstIndexKey` (Go's `Base64Prefix`, `UnknownPrefix`,
  `DecimalExpDelim`, `PrecisionLevel` and `FirstIndexKey`).
- **`StructCodec`**: the numeric schema of `@Tag` and `@Repeated` (Go's
  `PackStruct` and `UnpackStruct`).
- **Conformance**: the vectors of the format, a golden corpus written by the
  Go implementation (sealing by `golang.org/x/crypto` included), and the
  generator that writes it (`conformance/`).
- Java 17 bytecode with no dependencies; Android API 24 and up, checked by the
  build, with R8 rules in the jar.
- **Release to Maven Central** by a workflow (`release.yaml`): signed,
  reproducible jars, uploaded to the Central Portal on a version tag.
