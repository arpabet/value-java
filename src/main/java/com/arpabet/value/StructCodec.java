/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The numeric struct schema: objects written directly as canonical
 * MessagePack maps keyed by field number, the compact dialect for RPC payloads
 * and binary protocols whose peers share a schema. It is the Go
 * implementation's {@code PackStruct} and {@code UnpackStruct}; the bytes are
 * the same on both sides.
 *
 * <pre>{@code
 * final class RpcFrame {
 *     @Tag(1) StringValue method;
 *     @Tag(2) StringValue payload;
 *     @Tag(3) @Repeated List<StringValue> trace;
 * }
 *
 * byte[] wire = StructCodec.pack(frame);
 * RpcFrame back = StructCodec.unpack(wire, RpcFrame.class);
 * }</pre>
 *
 * <p>It is a different wire shape from {@link ValueCodec}, which maps
 * ordinary Java fields to a map keyed by name; peers must agree on which one
 * defines a payload. The rules of a schema class:
 *
 * <ul>
 * <li>every field that is not static or transient has a {@link Tag}, unique in
 * the class; a number is never changed or used again once a protocol has
 * shipped;</li>
 * <li>a field is a {@link Value} (or one of its kinds, such as
 * {@link StringValue}), another schema class, or a {@code java.util.List} of
 * either;</li>
 * <li>a list travels as one array under its number, or, with
 * {@link Repeated}, as one entry per element under the same number;</li>
 * <li>a null field is absent from the wire, and the fields are written in the
 * order of their numbers, whatever their order in the class;</li>
 * <li>a number the schema does not have is refused when reading, so adding a
 * field takes a version both peers know;</li>
 * <li>the class has a constructor without parameters.</li>
 * </ul>
 */
public final class StructCodec {

    private StructCodec() {
    }

    /**
     * The canonical bytes of an object of a schema class. Null is the null
     * value and a {@link Value} its own bytes.
     */
    public static byte[] pack(Object obj) {
        final Packer p = new Packer();
        try {
            if (obj == null) {
                p.nil();
            } else if (obj instanceof Value) {
                ((Value) obj).pack(p);
            } else {
                pack(p, obj, schema(obj.getClass()));
            }
        } catch (StackOverflowError e) {
            throw new ValueException("object refers back to itself or is nested too deeply");
        }
        return p.toByteArray();
    }

    /**
     * Reads an object of a schema class from the bytes {@link #pack} wrote.
     *
     * @throws ValueException when the bytes are not a struct of the schema
     */
    public static <T> T unpack(byte[] data, Class<T> type) {
        return unpack(data, type, Limits.DEFAULT);
    }

    /** Reads an object of a schema class, within the limits. */
    public static <T> T unpack(byte[] data, Class<T> type, Limits limits) {
        final Unpacker in = new Unpacker(data).limits(limits);
        final Schema schema = schema(type);
        try {
            final Object target = schema.newInstance();
            parse(in, target, schema, 0);
            return type.cast(target);
        } catch (IOException e) {
            throw new ValueException(e.toString(), e);
        } catch (StackOverflowError e) {
            throw new ValueException("value is nested too deeply for the stack");
        }
    }

    private static void pack(Packer p, Object obj, Schema schema) {
        int entries = 0;
        final Object[] held = new Object[schema.fields.length];
        for (int i = 0; i < held.length; i++) {
            final FieldSchema f = schema.fields[i];
            held[i] = f.get(obj);
            if (held[i] != null) {
                entries += f.list && f.repeated ? ((List<?>) held[i]).size() : 1;
            }
        }
        p.mapHeader(entries);
        for (int i = 0; i < held.length; i++) {
            final FieldSchema f = schema.fields[i];
            final Object x = held[i];
            if (x == null) {
                continue;
            }
            if (!f.list) {
                p.integer(f.tag);
                element(p, x, f);
                continue;
            }
            final List<?> list = (List<?>) x;
            if (!f.repeated) {
                p.integer(f.tag);
                p.listHeader(list.size());
            }
            for (Object item : list) {
                if (f.repeated) {
                    p.integer(f.tag);
                }
                element(p, item, f);
            }
        }
    }

    private static void element(Packer p, Object x, FieldSchema f) {
        if (x == null) {
            throw new ValueException("field " + f + " holds a null element");
        }
        if (f.struct) {
            pack(p, x, schema(f.element));
        } else if (x instanceof Value) {
            ((Value) x).pack(p);
        } else {
            throw new ValueException("field " + f + " holds a " + x.getClass().getName() + ", not a value");
        }
    }

    private static void parse(Unpacker in, Object target, Schema schema, int depth) throws IOException {
        final long entries = in.mapHeader(depth);
        for (long i = 0; i < entries; i++) {
            final Value key = in.value(depth + 1);
            if (!(key instanceof NumberValue)) {
                throw new ValueException("expected a field number, got " + key.kind() + " at entry " + i);
            }
            final long tag = ((NumberValue) key).longValue();
            final FieldSchema f = schema.byTag.get(tag);
            if (f == null) {
                throw new ValueException("unknown tag " + tag + " at entry " + i + " of " + schema.type.getName());
            }
            if (!f.list) {
                if (f.struct) {
                    // An entry met again reads into the same object.
                    Object nested = f.get(target);
                    if (nested == null) {
                        nested = schema(f.element).newInstance();
                        f.set(target, nested);
                    }
                    parse(in, nested, schema(f.element), depth + 1);
                } else {
                    f.set(target, item(in, f, depth));
                }
            } else if (!f.repeated) {
                final int n = in.limits().checkCollectionLength(in.listHeader());
                final ArrayList<Object> list = new ArrayList<Object>(Math.min(n, 4096));
                for (int j = 0; j < n; j++) {
                    list.add(item(in, f, depth));
                }
                f.set(target, list);
            } else {
                final Object held = f.get(target);
                final ArrayList<Object> list;
                if (held instanceof ArrayList) {
                    @SuppressWarnings("unchecked")
                    final ArrayList<Object> same = (ArrayList<Object>) held;
                    list = same;
                } else {
                    list = held == null ? new ArrayList<Object>() : new ArrayList<Object>((List<?>) held);
                    f.set(target, list);
                }
                list.add(item(in, f, depth));
            }
        }
    }

    /** One value of a field: a nested struct, or a value of the kind the field declares. */
    private static Object item(Unpacker in, FieldSchema f, int depth) throws IOException {
        if (f.struct) {
            final Schema nested = schema(f.element);
            final Object x = nested.newInstance();
            parse(in, x, nested, depth + 1);
            return x;
        }
        final Value v = in.value(depth + 1);
        if (!f.element.isInstance(v)) {
            throw new ValueException("field " + f + " takes " + f.element.getSimpleName() + ", got " + v.kind());
        }
        return v;
    }

    // --- schemas ---------------------------------------------------------------

    private static final class FieldSchema {
        final Field field;
        final int tag;
        final boolean list;
        final boolean repeated;
        final boolean struct;
        /** The type of the field, or of its elements. */
        final Class<?> element;

        FieldSchema(Field field, int tag) {
            this.field = field;
            this.tag = tag;
            this.repeated = field.isAnnotationPresent(Repeated.class);
            Class<?> type = field.getType();
            if (type == List.class) {
                this.list = true;
                final Type generic = field.getGenericType();
                Type arg = null;
                if (generic instanceof ParameterizedType) {
                    arg = ((ParameterizedType) generic).getActualTypeArguments()[0];
                }
                if (!(arg instanceof Class)) {
                    throw new ValueException("field " + this + " must declare the type of its elements");
                }
                type = (Class<?>) arg;
            } else {
                this.list = false;
            }
            this.element = type;
            this.struct = !Value.class.isAssignableFrom(type);
            if (struct && (type.isPrimitive() || type.isArray() || type.isEnum() || type.isInterface()
                    || type.getName().startsWith("java."))) {
                throw new ValueException("field " + this + " of type " + type.getName()
                        + " must be a Value, a class of tagged fields, or a List of either");
            }
        }

        Object get(Object target) {
            try {
                return field.get(target);
            } catch (IllegalAccessException e) {
                throw new ValueException("cannot read field " + this, e);
            }
        }

        void set(Object target, Object x) {
            try {
                field.set(target, x);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                throw new ValueException("cannot set field " + this + ": " + e, e);
            }
        }

        @Override
        public String toString() {
            return field.getDeclaringClass().getName() + "." + field.getName();
        }
    }

    private static final class Schema {
        final Class<?> type;
        /** In the order of their numbers. */
        final FieldSchema[] fields;
        final HashMap<Long, FieldSchema> byTag = new HashMap<Long, FieldSchema>();
        final Constructor<?> constructor;

        Schema(Class<?> type) {
            this.type = type;
            final ArrayList<FieldSchema> list = new ArrayList<FieldSchema>();
            final ArrayList<Class<?>> chain = new ArrayList<Class<?>>();
            for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
                chain.add(0, k);
            }
            for (Class<?> k : chain) {
                for (Field field : k.getDeclaredFields()) {
                    final int mod = field.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || field.isSynthetic()) {
                        continue;
                    }
                    final Tag tag = field.getAnnotation(Tag.class);
                    if (tag == null) {
                        throw new ValueException("no tag on field " + k.getName() + "." + field.getName());
                    }
                    try {
                        field.setAccessible(true);
                    } catch (RuntimeException e) {
                        throw new ValueException("cannot access field " + k.getName() + "." + field.getName()
                                + " (is its package open to the library?): " + e, e);
                    }
                    final FieldSchema f = new FieldSchema(field, tag.value());
                    if (byTag.put((long) f.tag, f) != null) {
                        throw new ValueException(type.getName() + " has two fields with the tag " + f.tag);
                    }
                    list.add(f);
                }
            }
            fields = list.toArray(new FieldSchema[0]);
            Arrays.sort(fields, new Comparator<FieldSchema>() {
                @Override
                public int compare(FieldSchema a, FieldSchema b) {
                    return Integer.compare(a.tag, b.tag);
                }
            });
            Constructor<?> c = null;
            try {
                c = type.getDeclaredConstructor();
                c.setAccessible(true);
            } catch (NoSuchMethodException | RuntimeException e) {
                c = null;
            }
            constructor = c;
        }

        Object newInstance() {
            if (constructor == null) {
                throw new ValueException("cannot construct " + type.getName() + ": it needs a constructor without parameters");
            }
            try {
                return constructor.newInstance();
            } catch (InvocationTargetException e) {
                final Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new ValueException("cannot construct " + type.getName() + ": " + cause, cause);
            } catch (ReflectiveOperationException e) {
                throw new ValueException("cannot construct " + type.getName() + ": " + e, e);
            }
        }
    }

    private static final ConcurrentHashMap<Class<?>, Schema> SCHEMAS = new ConcurrentHashMap<Class<?>, Schema>();

    private static Schema schema(Class<?> type) {
        Schema s = SCHEMAS.get(type);
        if (s == null) {
            s = new Schema(type);
            SCHEMAS.put(type, s);
        }
        return s;
    }
}
