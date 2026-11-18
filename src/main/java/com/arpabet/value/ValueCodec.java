/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts ordinary Java objects to and from values, and gives the bytes to
 * sign for them. It is the Go implementation's {@code Marshal},
 * {@code Unmarshal} and {@code SignBytes}: an object written here reads there
 * into the struct of the same schema, and the bytes signed for it are the
 * same.
 *
 * <pre>{@code
 * final class License {
 *     @Name("_dom")    @Sign("license")            String domain = "license/v1";
 *     @Name("product") @Sign({"license", "audit"}) String product;
 *     @Name("seats")   @Sign("license")            int seats;
 *     @Name("sig")     @OmitEmpty                  byte[] signature;
 * }
 *
 * byte[] wire = ValueCodec.DEFAULT.pack(license);                  // the whole object
 * byte[] toSign = ValueCodec.DEFAULT.signBytes(license, "license"); // the fields of the selector
 * License back = ValueCodec.DEFAULT.unpack(wire, License.class);
 * }</pre>
 *
 * <h2>The schema of a class</h2>
 *
 * An object is a map of its fields: every field that is not static or
 * transient, of the class and its superclasses, under its {@link Name} or
 * else its Java name. {@link OmitEmpty} leaves an empty field out and makes
 * it optional when reading; {@link Sign} puts it in a signing projection;
 * {@link Unsigned} gives an integer field the reading of a Go unsigned type.
 *
 * <h2>Java types and the kinds they are</h2>
 *
 * <table>
 * <caption>How a Java type maps to a value and to the Go type of the same schema</caption>
 * <tr><th>Java</th><th>value</th><th>Go</th></tr>
 * <tr><td>boolean</td><td>bool</td><td>bool</td></tr>
 * <tr><td>byte, short, int, long</td><td>long</td><td>int8 … int64, int</td></tr>
 * <tr><td>the same with {@link Unsigned}</td><td>long, or big integer above 2^63-1</td><td>uint8 … uint64, uint</td></tr>
 * <tr><td>float, double</td><td>double</td><td>float32, float64</td></tr>
 * <tr><td>String</td><td>text</td><td>string</td></tr>
 * <tr><td>byte[]</td><td>binary</td><td>[]byte, [N]byte</td></tr>
 * <tr><td>BigInteger</td><td>big integer</td><td>*big.Int, big.Int</td></tr>
 * <tr><td>BigDecimal</td><td>decimal</td><td>decimal.Decimal</td></tr>
 * <tr><td>java.util.Date, java.time.Instant, TimestampValue</td><td>timestamp</td><td>time.Time</td></tr>
 * <tr><td>other arrays, List</td><td>dense list</td><td>slice</td></tr>
 * <tr><td>Map with String keys</td><td>map</td><td>map[string]T</td></tr>
 * <tr><td>any {@link Value}</td><td>itself</td><td>value.Value</td></tr>
 * <tr><td>another class</td><td>map of its fields</td><td>struct, *struct</td></tr>
 * </table>
 *
 * <h2>Null</h2>
 *
 * Go has no null text, binary, slice or map: a field that was never set holds
 * the empty one, and that is what its bytes say. So a null {@code String},
 * {@code byte[]}, array, {@code List} or {@code Map} is written as the empty
 * text, binary, list or map, which makes an object that leaves such a field
 * unset sign the same bytes as the Go struct that does. A null of any other
 * type (a boxed number or boolean, a nested object, a {@code BigInteger}, a
 * date, a {@code Value}) is the null value, as a nil pointer is in Go. Reading
 * the null value leaves a field as it is.
 *
 * <h2>Reading</h2>
 *
 * A field must be present in the map unless it is {@link OmitEmpty} or the
 * codec {@linkplain #allowMissing() allows missing fields}; keys the class
 * does not have are ignored. A number goes into a numeric field as Go
 * converts it, truncating and wrapping, unless the codec is
 * {@linkplain #lossless() lossless}. An object is made with its constructor
 * without parameters, or, for a record or a class of final fields, with the
 * constructor that takes every field in order.
 *
 * <p>A codec is immutable and safe to share; what it learns about a class by
 * reflection it learns once. On Android the rules in the jar tell R8 to keep
 * the annotated fields and the constructors of mapped classes; a field
 * without an annotation goes by a name a shrinker changes, so name them all.
 */
public final class ValueCodec {

    /**
     * Converts a type the codec does not know (an enum, a UUID, a domain
     * type) to and from a value; see {@link #with(Class, Converter)}.
     */
    public interface Converter<T> {

        /** The value of an object, which is never null. */
        Value toValue(T object);

        /** The object of a value, which is never the null value. */
        T fromValue(Value value);
    }

    /** The codec with fields required and numbers converted as Go converts them. */
    public static final ValueCodec DEFAULT =
            new ValueCodec(false, false, Collections.<Class<?>, Converter<?>>emptyMap());

    private final boolean allowMissing;
    private final boolean lossless;
    private final Map<Class<?>, Converter<?>> converters;

    private ValueCodec(boolean allowMissing, boolean lossless, Map<Class<?>, Converter<?>> converters) {
        this.allowMissing = allowMissing;
        this.lossless = lossless;
        this.converters = converters;
    }

    /**
     * The codec with absent fields allowed: reading leaves a field whose key
     * the map lacks as it is instead of failing, as a reader of a class that
     * gained fields must for values written before.
     */
    public ValueCodec allowMissing() {
        return new ValueCodec(true, lossless, converters);
    }

    /**
     * The codec with lossless number conversion: reading sets a numeric field
     * only when the number survives the conversion, and fails naming the field
     * otherwise. An integer field takes an integral number within its range, a
     * float field a number whose shortest text reads back as the same number,
     * and a date a timestamp of whole milliseconds. {@code BigInteger} and
     * {@code BigDecimal} fields take only a number they hold exactly in every
     * codec.
     */
    public ValueCodec lossless() {
        return new ValueCodec(allowMissing, true, converters);
    }

    /** The codec that also maps objects of exactly the type through the converter. */
    public <T> ValueCodec with(Class<T> type, Converter<T> converter) {
        if (type == null || converter == null) {
            throw new NullPointerException();
        }
        final Map<Class<?>, Converter<?>> map = new HashMap<Class<?>, Converter<?>>(converters);
        map.put(type, converter);
        return new ValueCodec(allowMissing, lossless, map);
    }

    /** A converter of an enum to and from the text of its constants' names. */
    public static <E extends Enum<E>> Converter<E> enumNames(final Class<E> type) {
        return new Converter<E>() {
            @Override
            public Value toValue(E e) {
                return Value.utf8(e.name());
            }

            @Override
            public E fromValue(Value v) {
                if (v instanceof StringValue) {
                    try {
                        return Enum.valueOf(type, ((StringValue) v).utf8());
                    } catch (IllegalArgumentException e) {
                        throw new ValueException(type.getName() + " has no constant " + v.jsonify());
                    }
                }
                throw mismatch(v, type);
            }
        };
    }

    /** A converter of an enum to and from the long of its constants' ordinals. */
    public static <E extends Enum<E>> Converter<E> enumOrdinals(final Class<E> type) {
        final E[] constants = type.getEnumConstants();
        return new Converter<E>() {
            @Override
            public Value toValue(E e) {
                return Value.ofLong(e.ordinal());
            }

            @Override
            public E fromValue(Value v) {
                if (v instanceof NumberValue) {
                    final long i = ((NumberValue) v).longValue();
                    if (i >= 0 && i < constants.length) {
                        return constants[(int) i];
                    }
                    throw new ValueException(type.getName() + " has no constant " + i);
                }
                throw mismatch(v, type);
            }
        };
    }

    // --- writing ---------------------------------------------------------------

    /**
     * The value of an object: null is the null value, a {@link Value} is
     * itself, and anything else maps as the class documentation tells.
     *
     * @throws ValueException for a type that has no value, or an object that refers back to itself
     */
    public Value marshal(Object obj) {
        try {
            return toValue(obj, obj == null ? Object.class : obj.getClass(), false);
        } catch (StackOverflowError e) {
            throw new ValueException("object refers back to itself or is nested too deeply");
        }
    }

    /** The canonical bytes of an object: {@code marshal(obj).pack()}. */
    public byte[] pack(Object obj) {
        return marshal(obj).pack();
    }

    /**
     * The signing projection of an object for a selector: the map of only the
     * fields whose {@link Sign} carries the selector, each written in full.
     * {@link OmitEmpty} is ignored, so that what is signed has one shape.
     *
     * <p>Give each message type a constant field under the same selector (a
     * domain, such as {@code "license/v1"}) so that a signature for one type
     * cannot stand for another.
     *
     * @throws ValueException for an empty selector, or an object that is not of a class with fields
     */
    public MapValue signProjection(Object obj, String selector) {
        if (selector == null || selector.isEmpty()) {
            throw new ValueException("signing requires a selector");
        }
        if (obj == null) {
            throw new ValueException("signing requires an object, got null");
        }
        if (!isStruct(obj.getClass())) {
            throw new ValueException("signing requires an object with fields, got " + obj.getClass().getName());
        }
        try {
            return structToMap(obj, selector);
        } catch (StackOverflowError e) {
            throw new ValueException("object refers back to itself or is nested too deeply");
        }
    }

    /**
     * The bytes to sign for an object: the canonical bytes of its
     * {@linkplain #signProjection signing projection}. Sign them, and verify
     * against the same projection rebuilt by the peer; canonical packing makes
     * it the same bytes in every process and in both implementations.
     */
    public byte[] signBytes(Object obj, String selector) {
        return signProjection(obj, selector).pack();
    }

    /** The digest of {@link #signBytes}, by a {@link java.security.MessageDigest} algorithm such as {@code SHA-256}. */
    public byte[] signHash(Object obj, String algorithm, String selector) {
        return signProjection(obj, selector).hash(algorithm);
    }

    private Value toValue(Object o, Type declared, boolean unsigned) {
        if (o == null) {
            return absent(raw(declared));
        }
        if (o instanceof Value) {
            return (Value) o;
        }
        if (!converters.isEmpty()) {
            final Class<?> key = o instanceof Enum ? ((Enum<?>) o).getDeclaringClass() : o.getClass();
            @SuppressWarnings("unchecked")
            final Converter<Object> c = (Converter<Object>) converters.get(key);
            if (c != null) {
                final Value v = c.toValue(o);
                return v == null ? NullValue.NULL : v;
            }
        }
        if (o instanceof String) {
            return Value.utf8((String) o);
        }
        if (o instanceof byte[]) {
            return Value.raw((byte[]) o);
        }
        if (o instanceof Boolean) {
            return Value.ofBool((Boolean) o);
        }
        if (o instanceof Long) {
            return unsigned ? Value.ofUnsigned((Long) o) : Value.ofLong((Long) o);
        }
        if (o instanceof Integer) {
            return Value.ofLong(unsigned ? (Integer) o & 0xFFFFFFFFL : (Integer) o);
        }
        if (o instanceof Short) {
            return Value.ofLong(unsigned ? (Short) o & 0xFFFF : (Short) o);
        }
        if (o instanceof Byte) {
            return Value.ofLong(unsigned ? (Byte) o & 0xFF : (Byte) o);
        }
        if (o instanceof Double) {
            return Value.ofDouble((Double) o);
        }
        if (o instanceof Float) {
            return Value.ofFloat((Float) o);
        }
        if (o instanceof BigInteger) {
            return Value.ofBigInt((BigInteger) o);
        }
        if (o instanceof BigDecimal) {
            return Value.ofDecimal((BigDecimal) o);
        }
        if (o instanceof Date) {
            return Value.ofDate((Date) o);
        }
        final Class<?> c = o.getClass();
        if (c.isArray()) {
            final Class<?> component = c.getComponentType();
            if (component == char.class) {
                throw unsupported(c);
            }
            final int n = Array.getLength(o);
            final Value[] items = new Value[n];
            for (int i = 0; i < n; i++) {
                items[i] = toValue(Array.get(o, i), component, unsigned);
            }
            return n == 0 ? DenseList.EMPTY : new DenseList(items);
        }
        if (o instanceof List) {
            final List<?> list = (List<?>) o;
            final Type element = argument(declared, List.class, 0);
            final Value[] items = new Value[list.size()];
            int i = 0;
            for (Object item : list) {
                items[i++] = toValue(item, element, unsigned);
            }
            return items.length == 0 ? DenseList.EMPTY : new DenseList(items);
        }
        if (o instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) o;
            final Type element = argument(declared, Map.class, 1);
            final MapValue.Builder b = MapValue.builder();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new ValueException("map key must be a String, got "
                            + (e.getKey() == null ? "null" : e.getKey().getClass().getName()));
                }
                b.put((String) e.getKey(), toValue(e.getValue(), element, unsigned));
            }
            final MapValue m = b.build();
            if (m.size() != map.size()) {
                throw new ValueException("map has keys of the same bytes");
            }
            return m;
        }
        if (isInstant(c)) {
            return JavaTime.fromObject(o);
        }
        if (!isStruct(c)) {
            throw unsupported(c);
        }
        return structToMap(o, null);
    }

    /** What a null of a declared type is: the empty text, binary, list or map where Go has no null. */
    private static Value absent(Class<?> raw) {
        if (raw == String.class) {
            return Utf8String.EMPTY;
        }
        if (raw == byte[].class) {
            return new RawString(new byte[0]);
        }
        if (raw.isArray() || raw == List.class || raw == Collection.class || raw == ArrayList.class) {
            return DenseList.EMPTY;
        }
        if (raw == Map.class || raw == HashMap.class || raw == LinkedHashMap.class) {
            return MapValue.EMPTY;
        }
        return NullValue.NULL;
    }

    /** The map of an object's fields: all of them, or those of a signing selector. */
    private MapValue structToMap(Object o, String selector) {
        final ClassInfo info = info(o.getClass());
        final String[] keys = new String[info.fields.length];
        final Value[] vals = new Value[info.fields.length];
        int n = 0;
        for (FieldInfo f : info.fields) {
            final Object x;
            try {
                x = f.field.get(o);
            } catch (IllegalAccessException e) {
                throw new ValueException("cannot read field " + f, e);
            }
            if (selector != null) {
                if (!f.selectors.contains(selector)) {
                    continue;
                }
            } else if (f.omitEmpty && isEmpty(x, f.raw)) {
                continue;
            }
            try {
                vals[n] = toValue(x, f.type, f.unsigned);
            } catch (ValueException e) {
                throw new ValueException("field \"" + f.field.getName() + "\": " + e.getMessage(), e);
            }
            keys[n++] = f.name;
        }
        return MapValue.of(java.util.Arrays.copyOf(keys, n), java.util.Arrays.copyOf(vals, n), false);
    }

    /**
     * Whether a field holds the empty value of its declared type, as Go's
     * omitempty sees it: a field declared as an object, a boxed type or an
     * interface is empty only when it is null.
     */
    private static boolean isEmpty(Object x, Class<?> declared) {
        if (x == null) {
            return true;
        }
        if (declared.isPrimitive()) {
            if (declared == boolean.class) {
                return !(Boolean) x;
            }
            if (declared == float.class || declared == double.class) {
                return ((Number) x).doubleValue() == 0;
            }
            return declared != char.class && ((Number) x).longValue() == 0;
        }
        if (declared == String.class) {
            return ((String) x).isEmpty();
        }
        if (declared.isArray()) {
            return Array.getLength(x) == 0;
        }
        if (List.class.isAssignableFrom(declared) || declared == Collection.class) {
            return ((Collection<?>) x).isEmpty();
        }
        if (Map.class.isAssignableFrom(declared)) {
            return ((Map<?, ?>) x).isEmpty();
        }
        return false;
    }

    // --- reading ---------------------------------------------------------------

    /**
     * The object of a value: an instance of the type read from it, or null for
     * the null value.
     *
     * @throws ValueException when the value does not fit the type, or a required field is missing
     */
    @SuppressWarnings("unchecked")
    public <T> T unmarshal(Value v, Class<T> type) {
        final Object o = fromValue(v, type, false);
        if (o == null && type.isPrimitive()) {
            return (T) Array.get(Array.newInstance(type, 1), 0);
        }
        return (T) o;
    }

    /** The object read from canonical bytes: {@code unmarshal(Value.unpack(data), type)}. */
    public <T> T unpack(byte[] data, Class<T> type) {
        return unmarshal(Value.unpack(data), type);
    }

    /** The list of a list value, each element read as the type; null for the null value. */
    @SuppressWarnings("unchecked")
    public <T> List<T> unmarshalList(Value v, Class<T> elementType) {
        return (List<T>) fromValue(v, new Parameterized(List.class, elementType), false);
    }

    /** The map of a map value, each value read as the type; null for the null value. */
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> unmarshalMap(Value v, Class<T> valueType) {
        return (Map<String, T>) fromValue(v, new Parameterized(Map.class, String.class, valueType), false);
    }

    /**
     * Reads a map value into the fields of an existing object, leaving alone
     * the fields the map does not have (when they are optional) or holds as
     * null. The null value changes nothing.
     */
    public void unmarshalInto(Value v, Object target) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (v == NullValue.NULL) {
            return;
        }
        if (!(v instanceof MapValue) || !isStruct(target.getClass())) {
            throw mismatch(v, target.getClass());
        }
        try {
            fill((MapValue) v, target, info(target.getClass()));
        } catch (StackOverflowError e) {
            throw new ValueException("value is nested too deeply for the stack");
        }
    }

    private Object fromValue(Value v, Type type, boolean unsigned) {
        if (v == null || type == null) {
            throw new NullPointerException();
        }
        try {
            return decode(v, type, unsigned);
        } catch (StackOverflowError e) {
            throw new ValueException("value is nested too deeply for the stack");
        }
    }

    private static ValueException mismatch(Value v, Class<?> into) {
        return new ValueException("cannot unmarshal " + v.kind() + " into " + into.getName());
    }

    private static ValueException unsupported(Class<?> c) {
        return new ValueException("no value for the type " + c.getName() + "; register a converter for it");
    }

    private Object decode(Value v, Type type, boolean unsigned) {
        final Class<?> raw = raw(type);
        if (raw == Value.class) {
            return v;
        }
        if (v == NullValue.NULL) {
            return null;
        }
        if (raw == Object.class) {
            return v;
        }
        if (Value.class.isAssignableFrom(raw)) {
            if (raw.isInstance(v)) {
                return v;
            }
            throw mismatch(v, raw);
        }
        if (!converters.isEmpty()) {
            final Converter<?> c = converters.get(raw);
            if (c != null) {
                return c.fromValue(v);
            }
        }
        if (raw == String.class) {
            if (v instanceof StringValue) {
                return ((StringValue) v).utf8();
            }
            throw mismatch(v, raw);
        }
        if (raw == byte[].class) {
            if (v instanceof StringValue) {
                return ((StringValue) v).raw();
            }
            throw mismatch(v, raw);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (v instanceof BoolValue) {
                return ((BoolValue) v).booleanValue();
            }
            throw mismatch(v, raw);
        }
        if (raw == long.class || raw == Long.class) {
            return integer(v, raw, 64, unsigned);
        }
        if (raw == int.class || raw == Integer.class) {
            return (int) integer(v, raw, 32, unsigned);
        }
        if (raw == short.class || raw == Short.class) {
            return (short) integer(v, raw, 16, unsigned);
        }
        if (raw == byte.class || raw == Byte.class) {
            return (byte) integer(v, raw, 8, unsigned);
        }
        if (raw == double.class || raw == Double.class) {
            final NumberValue n = number(v, raw);
            try {
                return lossless ? n.doubleValueExact() : n.doubleValue();
            } catch (ArithmeticException e) {
                throw new ValueException(e.getMessage());
            }
        }
        if (raw == float.class || raw == Float.class) {
            final NumberValue n = number(v, raw);
            try {
                return lossless ? n.floatValueExact() : (float) n.doubleValue();
            } catch (ArithmeticException e) {
                throw new ValueException(e.getMessage());
            }
        }
        if (raw == BigInteger.class || raw == BigDecimal.class) {
            final NumberValue n = number(v, raw);
            try {
                return raw == BigInteger.class ? n.bigIntegerValueExact() : n.bigDecimalValueExact();
            } catch (ArithmeticException e) {
                throw new ValueException(e.getMessage());
            }
        }
        if (raw == Date.class || isInstant(raw)) {
            if (v instanceof TimestampValue) {
                final TimestampValue t = (TimestampValue) v;
                if (raw != Date.class) {
                    return JavaTime.toObject(t);
                }
                if (lossless && t.nanos() % 1000000 != 0) {
                    throw new ValueException("timestamp " + t + " is not a whole number of milliseconds");
                }
                return t.toDate();
            }
            if (v instanceof NumberValue) {
                // Unix milliseconds, which is how a time was written before timestamps.
                final long millis = ((NumberValue) v).longValue();
                return raw == Date.class ? new Date(millis) : JavaTime.ofEpochMilli(millis);
            }
            throw mismatch(v, raw);
        }
        if (raw.isArray()) {
            if (!(v instanceof ListValue) || raw.getComponentType() == char.class) {
                throw mismatch(v, raw);
            }
            final List<Value> values = ((ListValue) v).values();
            final Class<?> component = raw.getComponentType();
            final Object array = Array.newInstance(component, values.size());
            int i = 0;
            for (Value item : values) {
                final Object x = decode(item, component, unsigned);
                if (x != null) {
                    Array.set(array, i, x);
                }
                i++;
            }
            return array;
        }
        if (raw == List.class || raw == Collection.class || raw == Iterable.class || raw == ArrayList.class) {
            if (!(v instanceof ListValue)) {
                throw mismatch(v, raw);
            }
            final Type element = argument(type, List.class, 0);
            final List<Value> values = ((ListValue) v).values();
            final ArrayList<Object> out = new ArrayList<Object>(values.size());
            for (Value item : values) {
                out.add(decode(item, element, unsigned));
            }
            return out;
        }
        if (raw == Map.class || raw == LinkedHashMap.class || raw == HashMap.class) {
            if (!(v instanceof MapValue)) {
                throw mismatch(v, raw);
            }
            final Class<?> key = raw(argument(type, Map.class, 0));
            if (key != String.class && key != Object.class) {
                throw new ValueException("map key must be String, got " + key.getName());
            }
            final Type element = argument(type, Map.class, 1);
            final MapValue m = (MapValue) v;
            final Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (int i = 0; i < m.size(); i++) {
                out.put(m.keyAt(i), decode(m.valueAt(i), element, unsigned));
            }
            return out;
        }
        if (!isStruct(raw)) {
            throw unsupported(raw);
        }
        if (!(v instanceof MapValue)) {
            throw mismatch(v, raw);
        }
        return construct((MapValue) v, raw);
    }

    private static NumberValue number(Value v, Class<?> into) {
        if (v instanceof NumberValue) {
            return (NumberValue) v;
        }
        throw mismatch(v, into);
    }

    private long integer(Value v, Class<?> into, int bits, boolean unsigned) {
        final NumberValue n = number(v, into);
        if (!lossless) {
            return unsigned ? n.unsignedWrapped() : n.longValue();
        }
        try {
            return unsigned ? n.unsignedExact(bits) : n.signedExact(bits);
        } catch (ArithmeticException e) {
            throw new ValueException(e.getMessage());
        }
    }

    private Object construct(MapValue m, Class<?> raw) {
        final ClassInfo info = info(raw);
        try {
            if (info.noArg != null) {
                final Object target = info.noArg.newInstance();
                fill(m, target, info);
                return target;
            }
            if (info.allFields != null) {
                final Object[] args = new Object[info.allTypes.length];
                for (int i = 0; i < args.length; i++) {
                    if (info.allTypes[i].isPrimitive()) {
                        args[i] = Array.get(Array.newInstance(info.allTypes[i], 1), 0);
                    }
                }
                for (FieldInfo f : info.fields) {
                    final Object x = read(m, f);
                    if (x != null) {
                        args[f.position] = x;
                    }
                }
                return info.allFields.newInstance(args);
            }
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ValueException("cannot construct " + raw.getName() + ": " + cause, cause);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new ValueException("cannot construct " + raw.getName() + ": " + e, e);
        }
        throw new ValueException("cannot construct " + raw.getName()
                + ": it needs a constructor without parameters, or one that takes every field in order");
    }

    private void fill(MapValue m, Object target, ClassInfo info) {
        for (FieldInfo f : info.fields) {
            final Object x = read(m, f);
            if (x != null) {
                try {
                    f.field.set(target, x);
                } catch (IllegalAccessException | IllegalArgumentException e) {
                    throw new ValueException("cannot set field " + f + ": " + e, e);
                }
            }
        }
    }

    /** What the map holds for a field, or null to leave the field as it is. */
    private Object read(MapValue m, FieldInfo f) {
        // Where a map holds a key more than once, the last entry counts.
        final Value v = m.last(f.name);
        if (v == null) {
            if (!f.omitEmpty && !allowMissing) {
                throw new ValueException("missing required field \"" + f.name + "\"");
            }
            return null;
        }
        if (v == NullValue.NULL) {
            return null;
        }
        try {
            return decode(v, f.type, f.unsigned);
        } catch (ValueException e) {
            throw new ValueException("field \"" + f.field.getName() + "\": " + e.getMessage(), e);
        }
    }

    /** By name, so that java.time is not touched unless an application has such a field. */
    private static boolean isInstant(Class<?> c) {
        return c.getName().equals("java.time.Instant");
    }

    // --- classes ---------------------------------------------------------------

    private static final class FieldInfo {
        final Field field;
        final String name;
        final Type type;
        final Class<?> raw;
        final boolean omitEmpty;
        final boolean unsigned;
        final HashSet<String> selectors = new HashSet<String>();
        /** The index of the field among the instance fields of its class. */
        final int position;

        FieldInfo(Field field, int position) {
            this.field = field;
            this.position = position;
            final Name name = field.getAnnotation(Name.class);
            this.name = Utf8.canonical(name != null && !name.value().isEmpty() ? name.value() : field.getName());
            this.type = field.getGenericType();
            this.raw = field.getType();
            this.omitEmpty = field.isAnnotationPresent(OmitEmpty.class);
            this.unsigned = field.isAnnotationPresent(Unsigned.class);
            final Sign sign = field.getAnnotation(Sign.class);
            if (sign != null) {
                for (String s : sign.value()) {
                    selectors.add(s.trim());
                }
            }
        }

        @Override
        public String toString() {
            return field.getDeclaringClass().getName() + "." + field.getName();
        }
    }

    private static final class ClassInfo {
        FieldInfo[] fields;
        Constructor<?> noArg;
        Constructor<?> allFields;
        Class<?>[] allTypes;
    }

    private static final ConcurrentHashMap<Class<?>, ClassInfo> CLASSES = new ConcurrentHashMap<Class<?>, ClassInfo>();

    /** Whether a class maps as an object with fields, rather than being a type without a value. */
    private static boolean isStruct(Class<?> c) {
        if (c.isPrimitive() || c.isArray() || c.isEnum() || c.isAnnotation() || c.isInterface()
                || Modifier.isAbstract(c.getModifiers())) {
            return false;
        }
        final String name = c.getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.") || name.startsWith("dalvik."));
    }

    private static ClassInfo info(Class<?> c) {
        ClassInfo info = CLASSES.get(c);
        if (info == null) {
            info = inspect(c);
            CLASSES.put(c, info);
        }
        return info;
    }

    private static ClassInfo inspect(Class<?> c) {
        final ArrayList<Class<?>> chain = new ArrayList<Class<?>>();
        for (Class<?> k = c; k != null && k != Object.class && !k.getName().equals("java.lang.Record"); k = k.getSuperclass()) {
            chain.add(0, k);
        }
        final ArrayList<FieldInfo> fields = new ArrayList<FieldInfo>();
        final ArrayList<Class<?>> types = new ArrayList<Class<?>>();
        final HashSet<String> names = new HashSet<String>();
        for (Class<?> k : chain) {
            for (Field field : k.getDeclaredFields()) {
                final int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || field.isSynthetic()) {
                    continue;
                }
                final int position = types.size();
                types.add(field.getType());
                if (Modifier.isTransient(mod)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (RuntimeException e) {
                    throw new ValueException("cannot access field " + k.getName() + "." + field.getName()
                            + " (is its package open to the library?): " + e, e);
                }
                final FieldInfo f = new FieldInfo(field, position);
                if (!names.add(f.name)) {
                    throw new ValueException(c.getName() + " has two fields named \"" + f.name + "\"");
                }
                fields.add(f);
            }
        }
        final ClassInfo info = new ClassInfo();
        info.fields = fields.toArray(new FieldInfo[0]);
        info.allTypes = types.toArray(new Class<?>[0]);
        info.noArg = constructor(c);
        if (info.noArg == null && chain.size() == 1) {
            info.allFields = constructor(c, info.allTypes);
        }
        return info;
    }

    private static Constructor<?> constructor(Class<?> c, Class<?>... types) {
        try {
            final Constructor<?> k = c.getDeclaredConstructor(types);
            k.setAccessible(true);
            return k;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    // --- types -----------------------------------------------------------------

    private static Class<?> raw(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return raw(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            return Array.newInstance(raw(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        }
        if (type instanceof TypeVariable) {
            return raw(((TypeVariable<?>) type).getBounds()[0]);
        }
        if (type instanceof WildcardType) {
            return raw(((WildcardType) type).getUpperBounds()[0]);
        }
        return Object.class;
    }

    /** The type argument of a declared {@code List<T>} or {@code Map<K, V>}; Object when it is not declared. */
    private static Type argument(Type declared, Class<?> of, int index) {
        if (declared instanceof ParameterizedType) {
            final ParameterizedType p = (ParameterizedType) declared;
            final Type[] args = p.getActualTypeArguments();
            if (of.isAssignableFrom(raw(p)) && index < args.length && args.length == of.getTypeParameters().length) {
                return args[index];
            }
        }
        return Object.class;
    }

    /** {@code List<T>} or {@code Map<String, T>}, for the methods that read into one. */
    private static final class Parameterized implements ParameterizedType {
        private final Class<?> raw;
        private final Type[] args;

        Parameterized(Class<?> raw, Type... args) {
            this.raw = raw;
            this.args = args;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return args.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    }
}
