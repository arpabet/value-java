/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lossless JSON (the JSON section of CANONICAL.md).
 *
 * <p>Plain JSON has numbers, strings, booleans, null, arrays and objects; a
 * value has more kinds. Each kind JSON lacks is written as an object with a
 * single wrapper key and read back from it:
 *
 * <pre>
 * {"$long": "5"}                              a 64-bit integer
 * {"$double": "1.5"}                          a double; also "NaN", "Infinity", "-Infinity"
 * {"$bigint": "123"}                          a big integer, in decimal
 * {"$decimal": "1.10"}                        a decimal, with its exact digits
 * {"$bin": "AQI="}                            binary, in standard base64
 * {"$timestamp": "2026-10-30T07:28:39.075Z"}  an instant, RFC 3339 in UTC
 * {"$sparse": {"0": "a", "5": "b"}}           a sparse list, by index
 * {"$ext": {"base64": "AQ==", "type": 7}}     an extension the format does not define
 * {"$map": {"$bin": "x"}}                     a map whose only key is a wrapper key
 * </pre>
 */
final class Json {

    private Json() {
    }

    static final String LONG = "$long";
    static final String DOUBLE = "$double";
    static final String BIGINT = "$bigint";
    static final String DECIMAL = "$decimal";
    static final String BINARY = "$bin";
    static final String TIMESTAMP = "$timestamp";
    static final String SPARSE = "$sparse";
    static final String EXT = "$ext";
    static final String MAP = "$map";

    static boolean isWrapper(String key) {
        switch (key) {
            case LONG:
            case DOUBLE:
            case BIGINT:
            case DECIMAL:
            case BINARY:
            case TIMESTAMP:
            case SPARSE:
            case EXT:
            case MAP:
                return true;
            default:
                return false;
        }
    }

    // --- writing ---------------------------------------------------------------

    static String format(Value v, JsonMode mode, Limits limits) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        final StringBuilder out = new StringBuilder();
        append(out, v, mode, limits, 0, 0);
        return out.toString();
    }

    /**
     * Writes a value at a depth, which is what the limit counts, by recursion
     * for the first levels of a value; a list or a map further down is
     * written without ({@link #deep}), so that the depth a value may have is
     * a matter of the limit and not of the stack the caller happens to run on.
     */
    private static void append(StringBuilder out, Value v, JsonMode mode, Limits limits, int depth, int level) {
        if (v instanceof ListValue) {
            if (level >= Walk.deep) {
                deep(out, v, mode, limits, depth);
                return;
            }
            limits.checkDepth(depth);
            final ListValue list = (ListValue) v;
            if (list.isSparse()) {
                out.append("{\"").append(SPARSE).append("\":{");
                final List<ListValue.Item> items = list.items();
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    final ListValue.Item item = items.get(i);
                    out.append('"').append(item.key()).append("\":");
                    append(out, item.value(), mode, limits, depth + 1, level + 1);
                }
                out.append("}}");
                return;
            }
            out.append('[');
            final List<Value> values = list.values();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                append(out, values.get(i), mode, limits, depth + 1, level + 1);
            }
            out.append(']');
        } else if (v instanceof MapValue) {
            if (level >= Walk.deep) {
                deep(out, v, mode, limits, depth);
                return;
            }
            limits.checkDepth(depth);
            final MapValue map = (MapValue) v;
            if (map.size() == 1 && isWrapper(map.keyAt(0))) {
                // The map would read back as the wrapped kind: escape it,
                // which puts one more object around its entries.
                out.append("{\"").append(MAP).append("\":");
                entries(out, map, mode, limits, depth + 1, level);
                out.append('}');
            } else {
                entries(out, map, mode, limits, depth, level);
            }
        } else {
            limits.checkDepth(depth);
            scalar(out, v, mode);
        }
    }

    private static void entries(StringBuilder out, MapValue map, JsonMode mode, Limits limits, int depth, int level) {
        out.append('{');
        for (int i = 0; i < map.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            string(out, map.keyAt(i));
            out.append(':');
            append(out, map.valueAt(i), mode, limits, depth + 1, level + 1);
        }
        out.append('}');
    }

    /**
     * Writes a list or a map at a depth without recursion: the lists and maps
     * that are open are kept on a stack of their own.
     */
    private static void deep(StringBuilder out, Value v, JsonMode mode, Limits limits, int depth) {
        final Walk.Open open = new Walk.Open();
        Walk.Level l = null;
        for (;;) {
            limits.checkDepth(depth);
            if (Walk.container(v)) {
                l = open.push(v);
                if (l.keys != null) {
                    if (l.count == 1 && isWrapper(l.keys[0])) {
                        out.append("{\"").append(MAP).append("\":{");
                        l.close = "}}";
                        l.depth = depth + 2;
                    } else {
                        out.append('{');
                        l.close = "}";
                        l.depth = depth + 1;
                    }
                } else if (l.indexes != null) {
                    out.append("{\"").append(SPARSE).append("\":{");
                    l.close = "}}";
                    l.depth = depth + 1;
                } else {
                    out.append('[');
                    l.close = "]";
                    l.depth = depth + 1;
                }
            } else {
                scalar(out, v, mode);
            }
            // The next value: of the list or map that is open, or of one around it.
            for (;;) {
                if (l == null) {
                    return;
                }
                if (l.next == l.count) {
                    out.append(l.close);
                    l = open.pop();
                    continue;
                }
                final int i = l.next++;
                if (i > 0) {
                    out.append(',');
                }
                if (l.keys != null) {
                    string(out, l.keys[i]);
                    out.append(':');
                } else if (l.indexes != null) {
                    out.append('"').append(l.indexes[i]).append("\":");
                }
                v = l.vals[i];
                depth = l.depth;
                break;
            }
        }
    }

    /** Writes a value that is not a list or a map. */
    private static void scalar(StringBuilder out, Value v, JsonMode mode) {
        switch (v.kind()) {
            case NULL:
                out.append("null");
                return;
            case BOOL:
                out.append(((BoolValue) v).booleanValue());
                return;
            case NUMBER:
                appendNumber(out, (NumberValue) v, mode);
                return;
            case STRING:
                if (v instanceof RawString) {
                    wrapper(out, BINARY, ((RawString) v).base64());
                } else {
                    string(out, ((Utf8String) v).value);
                }
                return;
            case TIMESTAMP:
                wrapper(out, TIMESTAMP, v.toString());
                return;
            default: {
                final ExtensionValue x = (ExtensionValue) v;
                out.append("{\"").append(EXT).append("\":{\"base64\":\"").append(x.base64())
                        .append("\",\"type\":").append(x.type()).append("}}");
            }
        }
    }

    private static void appendNumber(StringBuilder out, NumberValue n, JsonMode mode) {
        switch (n.type()) {
            case LONG:
                if (mode == JsonMode.CANONICAL) {
                    wrapper(out, LONG, Long.toString(n.longValue()));
                } else {
                    out.append(n.longValue());
                }
                return;
            case DOUBLE: {
                final double d = n.doubleValue();
                if (mode == JsonMode.CANONICAL || Double.isNaN(d) || Double.isInfinite(d)) {
                    wrapper(out, DOUBLE, DoubleFormat.text(d));
                } else {
                    out.append(DoubleFormat.text(d));
                }
                return;
            }
            case BIGINT:
                wrapper(out, BIGINT, n.bigIntegerValue().toString());
                return;
            default: {
                final String text = decimalText(n.bigDecimalValue());
                if (mode == JsonMode.CANONICAL) {
                    wrapper(out, DECIMAL, text);
                } else {
                    out.append(text);
                }
            }
        }
    }

    private static void wrapper(StringBuilder out, String key, String text) {
        out.append("{\"").append(key).append("\":\"").append(text).append("\"}");
    }

    /** A JSON string with only the escapes JSON requires. */
    private static void string(StringBuilder out, String s) {
        if (Utf8.hasLoneSurrogate(s)) {
            throw new ValueException("a string that is not valid UTF-8 has no JSON form");
        }
        out.append('"');
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':
                case '\\':
                    out.append('\\').append(c);
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("\\u00");
                        Hex.append(out, c);
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }

    /**
     * The exact text of a decimal: the digits of its unscaled integer and its
     * exponent, so that 1.10 keeps its trailing zero, 1e3 its exponent and
     * 1e-400 does not spell out its zeros. Go's DecimalText.
     */
    static String decimalText(BigDecimal d) {
        final BigInteger c = d.unscaledValue();
        final long scale = d.scale();
        final String digits = c.abs().toString();
        final int k = digits.length();
        final StringBuilder b = new StringBuilder(k + 16);
        if (c.signum() < 0) {
            b.append('-');
        }
        if (scale == 0) {
            b.append(digits);
        } else if (scale < 0 || scale - k > 6) {
            b.append(digits).append('e').append(-scale);
        } else if (scale < k) {
            b.append(digits, 0, k - (int) scale).append('.').append(digits, k - (int) scale, k);
        } else {
            b.append("0.");
            for (long i = k; i < scale; i++) {
                b.append('0');
            }
            b.append(digits);
        }
        return b.toString();
    }

    // --- reading ---------------------------------------------------------------

    static Value parse(String text, Limits limits) {
        final Parser p = new Parser(text, limits);
        p.space();
        final Object node = p.read();
        p.space();
        if (p.pos != text.length()) {
            throw p.error("text after the value");
        }
        return p.build(node);
    }

    /**
     * An object as it was read: only when it is complete is it known whether
     * it is a wrapper, and whether the object inside a $map escape is a plain
     * map, so wrappers are read when the nodes are built.
     */
    private static final class ObjectNode {
        final int at;
        final ArrayList<String> keys = new ArrayList<String>();
        final ArrayList<Object> nodes = new ArrayList<Object>();
        /** The key that was read last, whose value comes next. */
        String pending;

        ObjectNode(int at) {
            this.at = at;
        }
    }

    /** An array as it was read. */
    private static final class ArrayNode {
        final ArrayList<Object> nodes = new ArrayList<Object>();
    }

    private static final class Parser {
        final String s;
        final int n;
        final Limits limits;
        int pos;

        Parser(String s, Limits limits) {
            this.s = s;
            this.n = s.length();
            this.limits = limits;
        }

        ValueException error(String message) {
            return errorAt(pos, message);
        }

        static ValueException errorAt(int at, String message) {
            return new ValueException("json at offset " + at + ": " + message);
        }

        void space() {
            while (pos < n) {
                final char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        boolean next(char c) {
            if (pos < n && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        /**
         * Reads one value into nodes: a leaf is its value; an array and an
         * object are their nodes. The arrays and objects that are open are kept
         * on a stack of their own, not on the thread's.
         */
        Object read() {
            Object[] open = new Object[8];
            int depth = 0;
            for (;;) {
                limits.checkDepth(depth);
                if (pos >= n) {
                    throw error("unexpected end");
                }
                final char c = s.charAt(pos);
                Object node;
                if (c == '{') {
                    final ObjectNode obj = new ObjectNode(pos);
                    pos++;
                    space();
                    if (next('}')) {
                        node = obj;
                    } else {
                        key(obj);
                        if (depth == open.length) {
                            open = Arrays.copyOf(open, depth * 2);
                        }
                        open[depth++] = obj;
                        continue;
                    }
                } else if (c == '[') {
                    final ArrayNode list = new ArrayNode();
                    pos++;
                    space();
                    if (next(']')) {
                        node = list;
                    } else {
                        if (depth == open.length) {
                            open = Arrays.copyOf(open, depth * 2);
                        }
                        open[depth++] = list;
                        continue;
                    }
                } else {
                    node = leaf(c);
                }
                // The node is complete: it goes to the array or object it is in,
                // which it may complete, and so on outwards.
                for (;;) {
                    if (depth == 0) {
                        return node;
                    }
                    final Object parent = open[depth - 1];
                    if (parent instanceof ObjectNode) {
                        final ObjectNode obj = (ObjectNode) parent;
                        obj.keys.add(obj.pending);
                        obj.nodes.add(node);
                        limits.checkCollectionLength(obj.keys.size());
                        space();
                        if (next(',')) {
                            key(obj);
                            break;
                        }
                        if (next('}')) {
                            node = obj;
                            open[--depth] = null;
                            continue;
                        }
                        throw error("expected ',' or '}'");
                    }
                    final ArrayNode list = (ArrayNode) parent;
                    list.nodes.add(node);
                    limits.checkCollectionLength(list.nodes.size());
                    space();
                    if (next(',')) {
                        space();
                        break;
                    }
                    if (next(']')) {
                        node = list;
                        open[--depth] = null;
                        continue;
                    }
                    throw error("expected ',' or ']'");
                }
            }
        }

        /** Reads the key of the next entry of an object, and the colon after it. */
        void key(ObjectNode obj) {
            space();
            if (pos >= n || s.charAt(pos) != '"') {
                throw error("expected a key");
            }
            obj.pending = string();
            space();
            if (!next(':')) {
                throw error("expected ':'");
            }
            space();
        }

        /** Reads a value that is not an array or an object, which starts with {@code c}. */
        Object leaf(char c) {
            switch (c) {
                case '"':
                    return new Utf8String(string());
                case 't':
                    return literal("true", BoolValue.TRUE);
                case 'f':
                    return literal("false", BoolValue.FALSE);
                case 'n':
                    return literal("null", NullValue.NULL);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        final int at = pos;
                        final int end = scanNumber(s, pos);
                        if (end < 0) {
                            throw error("malformed number");
                        }
                        pos = end;
                        try {
                            return new DecimalNumber(parseDecimal(s.substring(at, end), limits));
                        } catch (ValueException e) {
                            throw errorAt(at, e.getMessage());
                        }
                    }
                    throw error("unexpected '" + c + "'");
            }
        }

        Object literal(String word, Value v) {
            if (!s.startsWith(word, pos)) {
                throw error("unexpected text");
            }
            pos += word.length();
            return v;
        }

        /**
         * Reads a string: escapes decoded, surrogate pairs joined; lone
         * surrogates and raw control characters refused.
         */
        String string() {
            pos++;
            StringBuilder b = null;
            int from = pos;
            while (pos < n) {
                final char c = s.charAt(pos);
                if (c == '"') {
                    final String out;
                    if (b == null) {
                        out = s.substring(from, pos);
                    } else {
                        out = b.append(s, from, pos).toString();
                    }
                    pos++;
                    if (Utf8.hasLoneSurrogate(out)) {
                        throw error("string is not valid UTF-8");
                    }
                    limits.checkByteLength(Utf8.encodedLength(out));
                    return out;
                }
                if (c == '\\') {
                    if (b == null) {
                        b = new StringBuilder();
                    }
                    b.append(s, from, pos);
                    b.appendCodePoint(escape());
                    from = pos;
                } else if (c < 0x20) {
                    throw error("control character in a string");
                } else {
                    pos++;
                }
            }
            throw error("unterminated string");
        }

        int escape() {
            pos++;
            if (pos >= n) {
                throw error("unterminated escape");
            }
            final char c = s.charAt(pos);
            pos++;
            switch (c) {
                case '"':
                case '\\':
                case '/':
                    return c;
                case 'b':
                    return '\b';
                case 'f':
                    return '\f';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 't':
                    return '\t';
                case 'u': {
                    final int r = hex4();
                    if (r >= 0xD800 && r <= 0xDFFF) {
                        if (r >= 0xDC00 || !next('\\') || !next('u')) {
                            throw error("lone surrogate");
                        }
                        final int r2 = hex4();
                        if (r2 < 0xDC00 || r2 > 0xDFFF) {
                            throw error("lone surrogate");
                        }
                        return Character.toCodePoint((char) r, (char) r2);
                    }
                    return r;
                }
                default:
                    throw error("unknown escape '" + c + "'");
            }
        }

        int hex4() {
            if (pos + 4 > n) {
                throw error("short \\u escape");
            }
            int r = 0;
            for (int i = 0; i < 4; i++) {
                final int d = Hex.digit(s.charAt(pos + i));
                if (d < 0) {
                    throw error("malformed \\u escape");
                }
                r = (r << 4) | d;
            }
            pos += 4;
            return r;
        }

        /** An array or an object whose values are being built. */
        private static final class Building {
            final Object node;
            /** An object that is a wrapper: its one value is read, not kept. */
            final boolean wrapper;
            final Value[] vals;
            int next;

            Building(Object node, boolean wrapper, int size) {
                this.node = node;
                this.wrapper = wrapper;
                this.vals = new Value[size];
            }

            Object child(int i) {
                return node instanceof ArrayNode ? ((ArrayNode) node).nodes.get(i) : ((ObjectNode) node).nodes.get(i);
            }
        }

        /**
         * Turns a node into its value, from the leaves up. What is being built
         * is kept on a stack of its own, not on the thread's.
         */
        Value build(Object node) {
            Building[] open = new Building[8];
            int depth = 0;
            // The body of a $map escape is a map whatever its keys.
            boolean literal = false;
            for (;;) {
                Value v;
                Building opened = null;
                if (node instanceof Value) {
                    v = (Value) node;
                } else if (node instanceof ArrayNode) {
                    final int size = ((ArrayNode) node).nodes.size();
                    v = DenseList.EMPTY;
                    if (size != 0) {
                        opened = new Building(node, false, size);
                    }
                } else {
                    final ObjectNode obj = (ObjectNode) node;
                    final int size = obj.keys.size();
                    v = MapValue.EMPTY;
                    if (!literal && size == 1 && isWrapper(obj.keys.get(0))) {
                        if (obj.keys.get(0).equals(MAP)) {
                            final Object inner = obj.nodes.get(0);
                            if (!(inner instanceof ObjectNode)) {
                                throw errorAt(obj.at, MAP + " wants an object");
                            }
                            node = inner;
                            literal = true;
                            continue;
                        }
                        opened = new Building(obj, true, 1);
                    } else if (size != 0) {
                        opened = new Building(obj, false, size);
                    }
                }
                literal = false;
                if (opened != null) {
                    if (depth == open.length) {
                        open = Arrays.copyOf(open, depth * 2);
                    }
                    open[depth++] = opened;
                    node = opened.child(0);
                    continue;
                }
                // The value is built: it goes to what is being built around it,
                // which it may complete, and so on outwards.
                for (;;) {
                    if (depth == 0) {
                        return v;
                    }
                    final Building b = open[depth - 1];
                    b.vals[b.next++] = v;
                    if (b.next < b.vals.length) {
                        node = b.child(b.next);
                        break;
                    }
                    open[--depth] = null;
                    v = finish(b);
                }
            }
        }

        /** The list, the map, or the value of the wrapper that an array or an object is once its values are built. */
        private Value finish(Building b) {
            if (b.node instanceof ArrayNode) {
                return new DenseList(b.vals);
            }
            final ObjectNode obj = (ObjectNode) b.node;
            if (b.wrapper) {
                final String key = obj.keys.get(0);
                try {
                    return wrapped(key, b.vals[0]);
                } catch (ValueException e) {
                    throw errorAt(obj.at, e.getMessage());
                } catch (ArithmeticException e) {
                    throw errorAt(obj.at, key + ": " + e.getMessage());
                }
            }
            final MapValue map = MapValue.of(obj.keys.toArray(new String[0]), b.vals, false);
            for (int i = 1; i < b.vals.length; i++) {
                if (map.keyAt(i - 1).equals(map.keyAt(i))) {
                    throw errorAt(obj.at, "duplicate key \"" + map.keyAt(i) + "\"");
                }
            }
            return map;
        }

        /** Reads the value of a wrapper key. */
        Value wrapped(String key, Value v) {
            switch (key) {
                case LONG: {
                    final String t = text(key, v);
                    if (!isNumber(t) || hasFraction(t)) {
                        throw new ValueException(key + " \"" + t + "\" is not an integer");
                    }
                    try {
                        return LongNumber.of(Long.parseLong(t));
                    } catch (NumberFormatException e) {
                        throw new ValueException(key + " \"" + t + "\": value out of range");
                    }
                }
                case DOUBLE: {
                    final String t = text(key, v);
                    switch (t) {
                        case "NaN":
                            return DoubleNumber.NAN;
                        case "Infinity":
                            return DoubleNumber.of(Double.POSITIVE_INFINITY);
                        case "-Infinity":
                            return DoubleNumber.of(Double.NEGATIVE_INFINITY);
                        default:
                            break;
                    }
                    if (!isNumber(t)) {
                        throw new ValueException(key + " \"" + t + "\" is not a number");
                    }
                    final double d = Double.parseDouble(t);
                    if (Double.isInfinite(d)) {
                        throw new ValueException(key + " \"" + t + "\": value out of range");
                    }
                    return DoubleNumber.of(d);
                }
                case BIGINT: {
                    final String t = text(key, v);
                    if (!isNumber(t) || hasFraction(t)) {
                        throw new ValueException(key + " \"" + t + "\" is not an integer");
                    }
                    final int max = limits.maxDecimalExponent();
                    if (max > 0 && t.length() > max) {
                        throw new ValueException(key + " of " + t.length() + " digits exceeds limit " + max);
                    }
                    return new BigIntNumber(new BigInteger(t));
                }
                case DECIMAL: {
                    final String t = text(key, v);
                    if (!isNumber(t)) {
                        throw new ValueException(key + " \"" + t + "\" is not a number");
                    }
                    return new DecimalNumber(parseDecimal(t, limits));
                }
                case BINARY:
                    return new RawString(Base64.decodeJson(text(key, v), limits));
                case TIMESTAMP:
                    return Value.parseTimestamp(text(key, v));
                case SPARSE: {
                    if (!(v instanceof MapValue)) {
                        throw new ValueException(key + " holds " + v.kind() + ", want an object");
                    }
                    final MapValue m = (MapValue) v;
                    final int[] indexes = new int[m.size()];
                    final Value[] vals = new Value[m.size()];
                    for (int i = 0; i < indexes.length; i++) {
                        final String k = m.keyAt(i);
                        if (k.isEmpty() || k.length() > 10 || (k.charAt(0) == '0' && k.length() > 1) || !isDigits(k)) {
                            throw new ValueException(key + " index \"" + k + "\" is not a non-negative integer");
                        }
                        final long index = Long.parseLong(k);
                        if (!limits.isListIndex(index)) {
                            throw new ValueException(key + " index \"" + k + "\" out of range");
                        }
                        indexes[i] = (int) index;
                        vals[i] = m.valueAt(i);
                    }
                    return SparseList.of(indexes, vals, false);
                }
                default: { // EXT
                    final MapValue m = v instanceof MapValue ? (MapValue) v : null;
                    if (m == null || m.size() != 2 || !(m.get("base64") instanceof Utf8String)
                            || !(m.get("type") instanceof NumberValue)) {
                        throw new ValueException(key + " wants an object with base64 and type");
                    }
                    final BigDecimal t = ((NumberValue) m.get("type")).bigDecimalValue();
                    if (t.compareTo(MIN_TYPE) < 0 || t.compareTo(MAX_TYPE) > 0
                            || (t.signum() != 0 && t.stripTrailingZeros().scale() > 0)) {
                        throw new ValueException(key + " type " + decimalText(t) + " is not an extension type");
                    }
                    final byte[] data = Base64.decodeJson(((Utf8String) m.get("base64")).value, limits);
                    return Unpacker.extension(t.intValue(), data, limits);
                }
            }
        }

        private static final BigDecimal MIN_TYPE = BigDecimal.valueOf(-128);
        private static final BigDecimal MAX_TYPE = BigDecimal.valueOf(127);

        private static String text(String key, Value v) {
            if (!(v instanceof Utf8String)) {
                throw new ValueException(key + " holds " + v.kind() + ", want a string");
            }
            return ((Utf8String) v).value;
        }
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean hasFraction(String s) {
        return s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0;
    }

    /** The end of the JSON number that starts at {@code from}, by RFC 8259's grammar; -1 when there is none. */
    static int scanNumber(String s, int from) {
        final int n = s.length();
        int i = from;
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        if (i < n && s.charAt(i) == '0') {
            i++;
        } else {
            final int start = i;
            i = digits(s, i);
            if (i == start) {
                return -1;
            }
        }
        if (i < n && s.charAt(i) == '.') {
            final int start = ++i;
            i = digits(s, i);
            if (i == start) {
                return -1;
            }
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            final int start = i;
            i = digits(s, i);
            if (i == start) {
                return -1;
            }
        }
        return i;
    }

    private static int digits(String s, int i) {
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i;
    }

    /** Reports whether the text is one whole JSON number. */
    static boolean isNumber(String s) {
        return scanNumber(s, 0) == s.length() && !s.isEmpty();
    }

    /**
     * The decimal of a JSON number, with its exact digits: 1.10 keeps both,
     * and 1e3 its exponent.
     */
    static BigDecimal parseDecimal(String text, Limits limits) {
        final int max = limits.maxDecimalExponent();
        if (max > 0 && text.length() > max) {
            throw new ValueException("number of " + text.length() + " characters exceeds limit " + max);
        }
        String mantissa = text;
        long exp = 0;
        int e = text.indexOf('e');
        if (e < 0) {
            e = text.indexOf('E');
        }
        if (e >= 0) {
            try {
                exp = Integer.parseInt(text.substring(e + 1));
            } catch (NumberFormatException ex) {
                throw new ValueException("number \"" + text + "\": exponent out of range");
            }
            mantissa = text.substring(0, e);
        }
        final int dot = mantissa.indexOf('.');
        if (dot >= 0) {
            exp -= mantissa.length() - dot - 1;
            mantissa = mantissa.substring(0, dot) + mantissa.substring(dot + 1);
        }
        if (exp < Integer.MIN_VALUE || exp > Integer.MAX_VALUE) {
            throw new ValueException("number \"" + text + "\": exponent out of range");
        }
        limits.checkDecimalExponent(exp);
        if (exp == Integer.MIN_VALUE) {
            throw new ValueException("number \"" + text + "\": exponent out of range");
        }
        return new BigDecimal(new BigInteger(mantissa), (int) -exp);
    }
}
