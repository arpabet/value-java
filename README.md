# value-java

**Deterministic, canonical serialization for Java and Android**: the Java
implementation of [`go.arpabet.com/value`](https://github.com/arpabet/value).
The same value always encodes to exactly the same MessagePack bytes, so those
bytes can be **hashed and signed** reproducibly, and the two implementations
are **binary compatible**: bytes written by either read back to the same value
in the other, and an object signed on one side verifies on the other.

* **One wire format.** The canonical form is specified in the Go repository's
  [CANONICAL.md](https://github.com/arpabet/value/blob/main/CANONICAL.md). This
  library passes its conformance vectors unchanged and is checked against the
  Go implementation itself on a corpus of values, bytes and texts
  ([Compatibility](#compatibility-with-the-go-implementation)).
* **Light.** Pure Java, no dependencies, about 165 KB.
* **Java 17 bytecode, Android API 24+.** It calls nothing above API 24 (the
  build enforces it), ships its R8 rules, and does not need core library
  desugaring.
* **Sealing included.** `seal` and `unseal` are the Go implementation's NaCl
  box in pure Java, so a protocol encrypts its messages without a crypto
  library ([Sealing](#sealing)).
* **Immutable by default.** A value is immutable and safe to share between
  threads; as in Go there are mutable lists and maps as well
  ([Mutable lists and maps](#mutable-lists-and-maps)).

It implements version 1.6.1 of the format.

## Install

```kotlin
dependencies {
    implementation("com.arpabet:value:0.1.0")
}
```

It is on Maven Central (`mavenCentral()` in the repositories). To use a build
of your own instead, build it into the local Maven repository
(`./gradlew publishToMavenLocal`, then add `mavenLocal()` to the repositories)
or include this repository as a composite build (`includeBuild("path/to/value-java")`
in `settings.gradle`), which substitutes the dependency above.

## Values

A `Value` is null, a boolean, a number (long, double, big integer or decimal),
a string (text or binary), a timestamp, a list (dense or sparse), a map with
text keys, or an extension the format does not define.

```java
MapValue payment = MapValue.builder()
        .put("amount", 100)
        .put("to", "alice")
        .build();

byte[] bytes = payment.pack();          // canonical MessagePack
payment.hex();                          // 82a6616d6f756e7464a2746fa5616c696365

Value back = Value.unpack(bytes);
back.equals(payment);                   // true
((MapValue) back).getNumber("amount").longValue();   // 100
```

| Kind | Java | MessagePack | Example |
|---|---|---|---|
| NULL | `NullValue.NULL` | nil | `c0` |
| BOOL | `Value.ofBool(true)` | true / false | `c3` |
| NUMBER / long | `Value.ofLong(123)` | smallest integer form | `7b` |
| NUMBER / double | `Value.ofDouble(1.5)` | always float 64 | `cb3ff8000000000000` |
| NUMBER / big integer | `Value.ofBigInt(BigInteger)` | ext 1, two's complement | `d4017b` |
| NUMBER / decimal | `Value.ofDecimal(BigDecimal)` | ext 2, scale + unscaled | `c70602000000023039` (123.45) |
| STRING / text | `Value.utf8("hello")` | str | `a568656c6c6f` |
| STRING / binary | `Value.raw(bytes)` | bin | `c403010203` |
| TIMESTAMP | `Value.timestamp(seconds, nanos)` | ext -1, smallest form | `d6ff6ae44727` |
| LIST / dense | `Value.list(a, b)` | array | `9201a178` |
| LIST / sparse | `Value.emptySparseList().putAt(5, v)` | map with integer keys | `82000a0532` |
| MAP | `MapValue.builder()`, `Value.mapOf(map)` | map with text keys, sorted | `82a16101a16202` |
| UNKNOWN | `Value.ext(type, data)` | any other extension, kept as it is | `d5070102` |

The four number types are distinct values with distinct encodings: the long 1,
the double 1.0, the big integer 1 and the decimal 1 are never equal, and 1.0
and 1.00 are different decimals. **Two values are `equals` exactly when their
canonical bytes are equal**, with one exception that comes with the format:
a sparse list with nothing in it has the bytes of the empty map (`80`), is
read back as one, and is not equal to it.

### Order does not matter

Map keys are sorted by their UTF-8 bytes before encoding, so the bytes depend
on the contents and never on how the map was built:

```java
MapValue a = Value.emptyMap().put("zebra", Value.ofLong(1)).put("apple", Value.ofLong(2));
MapValue b = MapValue.builder().put("apple", 2).put("zebra", 1).build();

a.hex();        // 82a56170706c6502a57a6562726101
a.equals(b);    // true
```

### Hashing and signing

```java
byte[] digest = Value.ofLong(123).hash("SHA-256");
// 021fb596db81e6d02bf3d2586ee3981fe519f275c0ac9ca76bbcf2ebb4097d96 == sha256(0x7b)
```

Sign the canonical bytes with any signature scheme; verification is stable
because the same value always packs to the same bytes, here and in Go.

### Sealing

`seal` encrypts the canonical bytes of a value for one reader and
authenticates them as coming from one writer. It is the Go implementation's
`Seal`/`Unseal`, a NaCl box (X25519, XSalsa20, Poly1305): bytes sealed by
either implementation open in the other.

```java
byte[] alicePrivate = Sealer.newPrivateKey();            // 32 random bytes
byte[] alicePublic  = Sealer.publicKey(alicePrivate);
byte[] bobPrivate   = Sealer.newPrivateKey();
byte[] bobPublic    = Sealer.publicKey(bobPrivate);

byte[] sealed = Value.utf8("secret message").seal(bobPublic, alicePrivate);
Value opened  = Value.unseal(sealed, alicePublic, bobPrivate);   // ValueException unless authentic
```

```go
sealed, _ := value.Seal(value.Utf8("secret message"), bobPublic, alicePrivate)
opened, _ := value.Unseal(sealed, alicePublic, bobPrivate)
```

Most of the work of sealing is the agreement between the two keys, which is
the same for every message between them. A `Sealer` does it once; keep one per
peer:

```java
Sealer toBob = Sealer.of(bobPublic, alicePrivate);       // about 40 µs on a laptop, more on a phone
byte[] sealed = toBob.seal(message);                     // about 1 µs for a small message
Value opened  = Sealer.of(alicePublic, bobPrivate).unseal(sealed);

byte[] frame = toBob.sealBytes(StructCodec.pack(rpc));   // bytes that are not a value, in the same form
```

Sealed bytes are a random 24-byte nonce, a 16-byte tag and the encrypted
bytes: 40 bytes more than what they seal, whose length they do not hide.
Opening proves the bytes were sealed with one of the two private keys and not
altered; it is not a signature, since either party could have sealed them.
Keys are 32 bytes: Go's `box.GenerateKey` and `Sealer.newPrivateKey` /
`Sealer.publicKey` make the same kind. Any 32 bytes are taken as a public key,
here as in Go: sealing protects a message from everyone but the holder of
that key, so what matters is knowing whose key it is.

### Mutable lists and maps

A list or a map is made either immutable or mutable, as in Go, and stays what
it was made.

**Immutable** is what `Value.list`, `Value.emptyMap()`, `MapValue.builder()`
give, and what everything read from bytes, JSON or sealed bytes is. It never
changes: `put` and `append` return another value and leave it as it is.

```java
MapValue a = Value.emptyMap();
MapValue b = a.put("k", Value.ofLong(1));       // a is still empty; b is another map
```

**Mutable** is what `Value.mutableList()`, `Value.mutableSparseList()` and
`Value.mutableMap()` give. It is an ordinary mutable object, as an `ArrayList`
or a `TreeMap` is: the same methods change the object itself (and return it,
so that calls chain), and appending does not copy what is already there.

```java
MapValue event = Value.mutableMap();
event.put("op", Value.utf8("put"));             // event itself has the entry now

ListValue keys = Value.mutableList();
event.put("keys", keys);                        // the map holds this very list
keys.append(Value.utf8("k1"));                  // so this shows in event: {"keys": ["k1"],"op": "put"}

event.update("op", old -> Value.utf8(old + "!"));   // replaces a value in place; false if there is no "op"
byte[] wire = event.pack();                     // a mutable value packs, seals and compares like any other
```

Two rules connect the two kinds:

* **An immutable list or map never holds a mutable one.** Given one, it takes
  an immutable copy of it as it is at that moment, so nothing immutable ever
  changes, at any depth. A mutable one holds whatever it is given as it is.
* **`toImmutable()` and `toMutable()` cross over.** `toImmutable()` copies a
  mutable list or map, and the mutable ones inside it, into an immutable
  value; `toMutable()` gives a new mutable list or map of the same elements.

A mutable list or map must not be changed by one thread while another uses it,
and must not be put into itself. An immutable value is safe to share.

| Go | Java |
|---|---|
| `EmptyMutableMap()`, `EmptyMap(false)` | `Value.mutableMap()` |
| `EmptyImmutableMap()`, `EmptyMap(true)` | `Value.emptyMap()` |
| `SortedMapOf(m)`, `ImmutableMapOf(m)` | `Value.mapOf(m)`, and `.toMutable()` |
| `EmptyMutableList()`, `EmptyList(false)` | `Value.mutableList()` |
| `EmptyImmutableList()`, `EmptyList(true)` | `Value.emptyList()` |
| `Tuple(a, b)`, `Single(a)`, `ImmutableList(s)`, `MutableList(s)` | `Value.list(a, b)`, `Value.listOf(collection)` |
| `EmptySparseList()` | `Value.emptySparseList()`, `Value.mutableSparseList()` |
| `m.Update(key, updater)`, `l.UpdateAt(i, updater)` | `m.update(key, fn)`, `l.updateAt(i, fn)` |
| `ImmutableMapCopyOf(m)`, `SortedMapCopyOf(m)` | `m.toImmutable()`, `m.toMutable()` |

## Objects

`ValueCodec` maps ordinary Java objects to values and back, and gives the
bytes to sign for them. It is the Go implementation's `Marshal`, `Unmarshal`
and `SignBytes`: an object written here reads there into the struct of the
same schema.

```java
final class License {
    @Name("_dom")    @Sign("license")            String domain = "license/v1";
    @Name("product") @Sign({"license", "audit"}) String product;
    @Name("seats")   @Sign("license")            int seats;
    @Name("sig")     @OmitEmpty                  byte[] signature;
}

byte[] wire   = ValueCodec.DEFAULT.pack(license);                   // the whole object
byte[] toSign = ValueCodec.DEFAULT.signBytes(license, "license");   // only the fields of the selector
License back  = ValueCodec.DEFAULT.unpack(wire, License.class);
```

The Go struct of the same schema:

```go
type License struct {
    Domain    string `value:"_dom" sign:"license"`
    Product   string `value:"product" sign:"license,audit"`
    Seats     int    `value:"seats" sign:"license"`
    Signature []byte `value:"sig,omitempty"`
}
```

| Go tag | Java | Meaning |
|---|---|---|
| `value:"name"` | `@Name("name")` | the key of the field in the map |
| `value:",omitempty"` | `@OmitEmpty` | left out while empty; optional when reading |
| `sign:"a,b"` | `@Sign({"a", "b"})` | part of the signing projections `a` and `b` |
| `value:"-"` | `transient` | not on the wire |
| a `uint8` … `uint64` field | `@Unsigned` | the bits are an unsigned number |

| Java field | Value | Go field |
|---|---|---|
| `boolean` | bool | `bool` |
| `byte`, `short`, `int`, `long` | long | `int8` … `int64`, `int` |
| the same with `@Unsigned` | long, or big integer above 2^63-1 | `uint8` … `uint64`, `uint` |
| `float`, `double` | double | `float32`, `float64` |
| `String` | text | `string` |
| `byte[]` | binary | `[]byte`, `[N]byte` |
| `BigInteger` | big integer | `*big.Int`, `big.Int` |
| `BigDecimal` | decimal | `decimal.Decimal` |
| `java.util.Date`, `java.time.Instant`, `TimestampValue` | timestamp | `time.Time` |
| other arrays, `List<T>` | dense list | slice |
| `Map<String, T>` | map | `map[string]T` |
| any `Value` | itself | `value.Value` |
| another class | map of its fields | struct, `*struct` |

**Null.** Go has no null text, binary, slice or map: a field that was never set
holds the empty one, and that is what its bytes say. So a null `String`,
`byte[]`, array, `List` or `Map` is written as the empty text, binary, list or
map, and an object that leaves such a field unset signs the same bytes as the
Go struct that does. A null of any other type (a boxed number, a nested object,
a `BigInteger`, a date, a `Value`) is the null value, as a nil pointer is in
Go. A Go struct field that is not a pointer is never nil, so initialize its
Java twin.

**Reading.** A field must be present unless it is `@OmitEmpty`
(`ValueCodec.DEFAULT.allowMissing()` lifts that); unknown keys are ignored. A
number goes into a numeric field as Go converts it, truncating and wrapping;
`ValueCodec.DEFAULT.lossless()` sets a field only when the number survives the
conversion and fails naming the field otherwise. An object is made with its
constructor without parameters, or, for a record or a class of final fields,
with the constructor that takes every field in order.

**Other types** (an enum, a UUID, a domain type) map through a converter:
`ValueCodec.DEFAULT.with(Color.class, ValueCodec.enumNames(Color.class))`.

**Classes annotated for another library.** The Go implementation has a codec
per dialect of struct tags (`value`, `json`, `msgpack`), so that a struct
tagged for another serializer needs no second set of tags. What a Java class
carries instead of a tag is the annotation of the library it was written for,
so a codec here is told which one names a field:

```java
ValueCodec jackson = ValueCodec.DEFAULT.named(JsonProperty.class, JsonProperty::value).allowMissing();
ValueCodec gson    = ValueCodec.DEFAULT.named(SerializedName.class, SerializedName::value).allowMissing();
ValueCodec moshi   = ValueCodec.DEFAULT.named(Json.class, Json::name).allowMissing();
```

| Go | Java |
|---|---|
| `DefaultValueCodec()` | `ValueCodec.DEFAULT` |
| `JSONValueCodec()`, `MsgpackValueCodec()` | `ValueCodec.DEFAULT.named(annotation, name).allowMissing()` |
| `codec.AllowMissing()`, `codec.Lossless()` | `codec.allowMissing()`, `codec.lossless()` |

Only the keys change: the value of an object is still the canonical one
(binary as binary, a date as a timestamp), not what the other library would
write, and `@OmitEmpty`, `@Sign` and `@Unsigned` mean what they mean in every
codec. A field without the annotation goes by its Java name; a function of
the field (`named(field -> …)`) can also leave fields out, as a library's
"ignore" annotation does.

### Numeric schema

`StructCodec` is the compact, protobuf-style dialect of the Go implementation's
`PackStruct` and `UnpackStruct`: field numbers on the wire instead of names.

```java
final class RpcFrame {
    @Tag(1) StringValue method;
    @Tag(2) StringValue payload;
    @Tag(3) @Repeated List<StringValue> trace;
}

byte[] wire = StructCodec.pack(frame);
RpcFrame back = StructCodec.unpack(wire, RpcFrame.class);
```

A field is a `Value` (or one of its kinds), another schema class, or a `List`
of either. It is a different wire shape from `ValueCodec`; peers must agree on
which one defines a payload.

## Lossless JSON

`toJson` writes JSON that `parseJson` reads back to the same kinds and exact
digits. Kinds plain JSON lacks become single-key wrappers (`$long`, `$double`,
`$bigint`, `$decimal`, `$bin`, `$timestamp`, `$sparse`, `$ext`, and `$map`
around a map whose only key is one of these):

```java
MapValue m = MapValue.builder().put("qty", 5).put("raw", new byte[] {1, 2}).build();

m.toJson(JsonMode.CANONICAL);   // {"qty":{"$long":"5"},"raw":{"$bin":"AQI="}}
m.toJson(JsonMode.RELAXED);     // {"qty":5,"raw":{"$bin":"AQI="}}

Value v = Value.parseJson("{\"price\": 1.10}");
// a bare number has no type: price is the decimal 1.10, both digits kept
```

The canonical text is a function of the value, and is the same text the Go
implementation writes, digit for digit. JSON is not the canonical form: hash
and sign the MessagePack bytes.

`toString()` and `jsonify()` are the Go implementation's human-readable
`String()` and `Jsonify` (binary as `base64,…`, big numbers in hexadecimal).

## Untrusted input

Decoding is bounded by `Limits`, with the defaults of the Go implementation:
depth 1000, 16,777,216 elements per list or map, 1 GiB per string, binary or
extension, decimal exponent 1,048,576. A header that claims a length the input
does not deliver allocates nothing.

A deeper value takes no more of the thread's stack. Decoding and reading JSON
keep the lists and maps they have open on the heap. Packing, printing,
comparing, hashing and writing JSON go through the first levels of a value by
recursion (64 levels; 32 for comparing and hashing, 16 for writing JSON) and
keep what is open on the heap past them. So whatever the depth of a value,
each takes under 16 KB of stack (measured on HotSpot 17 and 21, x86-64 and
arm64), and a value of the full default depth, or of any depth without a
limit, works on the small stack of an Android background thread as it does on
a server's.

```java
Limits tight = Limits.DEFAULT.withMaxDepth(32).withMaxByteLength(1 << 20);
Value v = Value.unpack(bytes, tight);       // ValueException beyond a limit
```

`Value.unpack` reads the first value of an array and does not look at what
follows it, as Go's `Unpack` does; an `Unpacker` reads values in a row and
tells where each one ends. `Value.read(InputStream)` takes exactly the bytes
of one value from a stream, and gives null at its end: a loop over it, and
over `writeTo`, is what Go's `ReadStream` and `WriteStream` are over channels.

### Bytes written before v1.5.0

Before v1.5.0 of the Go implementation a big integer travelled as Go's gob
encoding and a decimal as shopspring's `MarshalBinary`. Those layouts cannot
be told from the portable ones by looking at the bytes, so a decoder never
guesses: it reads them while a switch is on. The switch is Go's
`LegacyBigNumbers`, and like it one for the whole process:

```java
Value.setLegacyBigNumbers(true);                 // Go: value.LegacyBigNumbers = true
byte[] migrated = Value.unpack(stored).pack();   // packing always writes the portable layout
Value.setLegacyBigNumbers(false);
```

`Value.unpackLegacyBigInt` and `Value.unpackLegacyDecimal` read one such
payload whatever the switch says, as Go's functions of those names do.

## Settings of the process

What the Go implementation keeps in package variables is kept here the same
way: one setting for the whole process, on every thread. Set them when the
program starts.

| Go variable | Java | Unless set |
|---|---|---|
| `LegacyBigNumbers` | `Value.setLegacyBigNumbers(boolean)` | `false` |
| `Base64Prefix` | `Value.setBase64Prefix(String)` | `base64,` |
| `UnknownPrefix` | `Value.setUnknownPrefix(String)` | `data:application/x-msgpack-ext;` |
| `DecimalExpDelim`, `DecimalExpDelimStr` | `Value.setDecimalExpDelim(char)` | `x` |
| `PrecisionLevel` | `Value.setPrecisionLevel(double)` | `0.00001` |
| `FirstIndexKey` | `Value.setFirstIndexKey(int)` | `0` |
| `MaxParseDepth`, `MaxParseCollectionLen`, `MaxParseByteLen`, `MaxDecimalExponent` | a `Limits` passed to the call | Go's values |
| `AllowFastAppends` | none: nothing here shares storage | |

Each is read back by the method of the same name without `set`, such as
`Value.base64Prefix()`.

The two prefixes and the delimiter shape the text of `toString()` and
`jsonify()`, which `StringValue.parse` and `NumberValue.parse` read back: a
binary prints as `base64,AQI`, an extension as
`data:application/x-msgpack-ext;base64,BwEC`, the decimal 123.567 as
`0x01e2afx-03`. They never change the canonical bytes or the lossless JSON,
with one exception that Go has too: a map read from foreign bytes whose key
is not text gets the text of that key as its key.

## Android

* minSdk 24; no desugaring needed. `JavaTime` (`java.time.Instant` and
  timestamps) is the one class that needs API 26, and nothing else refers to
  it.
* The jar carries its R8/ProGuard rules: a class with `@Name` or `@Tag` fields
  keeps those fields and its constructors, and may be renamed. A field without
  an annotation is mapped by its Java name, which a shrinker changes: name
  every field of a class that goes on the wire.
* The codecs use reflection once per class and cache what they learn.
* Sealing is the same code on every API level: it takes nothing from the
  platform but `SecureRandom`, for keys and nonces.
* A class that carries only another library's annotations (`ValueCodec.named`)
  needs that library's rules, or its own, to keep its fields and a
  constructor.

## Compatibility with the Go implementation

What is checked, by `./gradlew build`:

* **The conformance vectors** of the format (`testdata/canonical.json` of the
  Go repository, copied unchanged): bytes, decoding, text and canonical JSON.
* **A golden corpus written by the Go implementation** (`conformance/`, about
  13,000 cases in `src/test/resources/golden`): for canonical, non-canonical,
  mutated and random bytes, what Go decodes, re-encodes and renders (both JSON
  modes, `String()`, `Jsonify`); JSON texts and what they parse to; the
  shortest text of doubles and floats; timestamps both ways; every number
  conversion, exact and wrapping; base64; the typed getters; and structs
  through `Marshal`, `SignBytes` and `PackStruct`, with twins of the Go
  structs on this side.
* **Sealing**, against `golang.org/x/crypto`: X25519 (with the points of small
  order and the encodings above the modulus), HSalsa20, Poly1305, and boxes of
  every length around a block, also to the public keys that are nobody's. Go's
  `Seal` draws a nonce and nothing else, so
  with the nonce given the bytes sealed here must be, and are, the very bytes
  Go sealed and its `Unseal` opened; and what `Unseal` says to altered, cut
  and foreign bytes is what `unseal` says. The field arithmetic is also
  checked against `BigInteger`, and X25519 against the JDK's.
* **The layouts of before v1.5.0**, against Go with `LegacyBigNumbers` set.
* **The other package variables**, against Go with them changed: the text of
  values, what is read back from it, a key that is not text, `ApproxEqual`,
  and the first index of a sparse list.

A larger corpus takes seconds to write and check (it needs Go 1.25+):

```sh
cd conformance && go run . -out /tmp/golden -scale 80 -seed 7 && cd ..
./gradlew test -PgoldenDir=/tmp/golden      # about 680,000 cases
```

Where the two differ, on purpose:

* **A mutable list or map is one object.** In Go `m.Put(k, v)` returns a map
  that may share storage with `m`, and `m` is to be left alone from then on;
  here it changes `m` and returns `m`. So `m = m.Put(k, v)` reads the same in
  both, and a mutable list put into a mutable map and appended to afterwards
  shows there, where in Go the map would keep the list as it was. An
  immutable list or map takes a copy of a mutable one, which Go's does not.
* **`equals` is equality of canonical bytes for every pair of values.** Text
  and binary over the same bytes are different, and so are a dense and a
  sparse list with the same elements, where Go's `Equal` is looser (and not
  symmetric).
* **NaN.** A Java `double` does not carry its NaN payload reliably, so
  `Value.ofDouble` of any NaN is the NaN of the canonical form, Go's
  `math.NaN()` (`cb7ff8000000000001`), and `Value.ofFloat` of one is the NaN a
  Go `float32` widens to. A NaN read from bytes keeps its bits.
* **Text that is not UTF-8** is kept, as in Go: a byte that is not part of a
  valid sequence is carried in the `String` as a lone surrogate (`0x80 + n` is
  U+DC80 + n), and packs back to the byte it was. Such text has no JSON form,
  as in Go.
* **A map read from foreign bytes** that repeats a key keeps the repeats in
  the order they came in; Go's order for more than 12 entries is an accident
  of its sort. A key that is itself a list or a map becomes the text of its
  rendering, which quotes text by the Unicode tables of the runtime, as Go's
  does by those of the Go release. A canonical writer produces neither.
* **A sparse list index is an `int`.** With the collection limit disabled, an
  integer key of 2^31 or more is a map key.
* **A double outside the range of a long** converts to the nearest long;
  Go leaves that to the platform.
* **A codec for another dialect** is told which annotation names a field
  instead of reading a `json` or `msgpack` tag.
* **The decoding limits** are an object passed to a call (`Limits`), where Go
  has the variables `MaxParseDepth` and the like. The other package variables
  of Go are [settings of the process](#settings-of-the-process) here too.

## Building

```sh
./gradlew build                  # compile, test, check the Android API level
./gradlew publishToMavenLocal    # com.arpabet:value:0.1.0
```

Two workflows run on GitHub. `build.yaml` builds and tests every push on JDK
17 and 21, checks that the committed corpus is what the generator writes, and
runs the tests against a larger one written on the spot. `release.yaml`
publishes to Maven Central: push the tag `v` + the `version` of
`build.gradle.kts`, or run it by hand to stage a release in the Central
Portal without publishing it. What it needs set up once (the Portal account,
its token and a signing key, as repository secrets) is listed at its top. The
jars are reproducible: the same sources give the same bytes.

JDK 17 or newer. To regenerate the golden corpus after a change of the Go
implementation: `cd conformance && go run .`

## License

[Apache-2.0](LICENSE).
