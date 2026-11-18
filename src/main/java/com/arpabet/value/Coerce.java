/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * What the typed getters of a list or a map give for a value of another kind,
 * exactly as the Go implementation's GetBool, GetNumber, GetString, GetList
 * and GetMap do: a value that is not of the kind asked for is read from its
 * text, and a missing or null one is false, 0, the empty text, the empty list
 * or the empty map.
 */
final class Coerce {

    private Coerce() {
    }

    static BoolValue toBool(Value v) {
        if (v == NullValue.NULL) {
            return BoolValue.FALSE;
        }
        if (v instanceof BoolValue) {
            return (BoolValue) v;
        }
        return BoolValue.of(parseBoolean(v.toString()));
    }

    static NumberValue toNumber(Value v) {
        if (v == NullValue.NULL) {
            return LongNumber.of(0);
        }
        if (v instanceof NumberValue) {
            return (NumberValue) v;
        }
        return parseNumber(v.toString(), Limits.DEFAULT);
    }

    static StringValue toString(Value v) {
        if (v == NullValue.NULL) {
            return Utf8String.EMPTY;
        }
        if (v instanceof StringValue) {
            return (StringValue) v;
        }
        return parseString(v.toString());
    }

    static ListValue toList(Value v) {
        if (v instanceof ListValue) {
            return (ListValue) v;
        }
        if (v instanceof MapValue) {
            return ((MapValue) v).valueList();
        }
        return DenseList.EMPTY;
    }

    static MapValue toMap(Value v) {
        if (v instanceof MapValue) {
            return (MapValue) v;
        }
        if (v instanceof ListValue) {
            return ((ListValue) v).indexMap();
        }
        return MapValue.EMPTY;
    }

    /** Go's strconv.ParseBool, false for a text that is not a boolean. */
    static boolean parseBoolean(String s) {
        switch (s) {
            case "1":
            case "t":
            case "T":
            case "TRUE":
            case "true":
            case "True":
                return true;
            default:
                return false;
        }
    }

    /** Text that starts with {@code base64,} and is unpadded base64 after it is binary; any other is text. */
    static StringValue parseString(String s) {
        final String prefix = Value.base64Prefix();
        if (s.startsWith(prefix)) {
            try {
                return new RawString(Base64.decodeRawStd(s.substring(prefix.length())));
            } catch (ValueException e) {
                // not base64: it is the text it looks like
            }
        }
        return new Utf8String(Utf8.canonical(s));
    }

    /** Go's ParseNumber. */
    static NumberValue parseNumber(String s, Limits limits) {
        if (s.isEmpty() || s.equals("null")) {
            return LongNumber.of(0);
        }
        if (s.equals("nan")) {
            return DoubleNumber.NAN;
        }
        if (s.indexOf('.') >= 0) {
            return parseDouble(s);
        }
        if (hasHexPrefix(s)) {
            final NumberValue n = parseHexNumber(s);
            if (n != null) {
                return n;
            }
        }
        if (isInteger(s)) {
            try {
                return LongNumber.of(Long.parseLong(s));
            } catch (NumberFormatException e) {
                // Beyond the range of a long: the big integer it spells, where
                // a double would lose its low digits.
                if (limits.maxDecimalExponent() <= 0 || s.length() <= limits.maxDecimalExponent()) {
                    return new BigIntNumber(new BigInteger(s));
                }
            }
        }
        return parseDouble(s);
    }

    private static NumberValue parseDouble(String s) {
        final Double d = parseFloat(s);
        return d == null ? DoubleNumber.NAN : DoubleNumber.of(d);
    }

    /** An optional sign and decimal digits, as Go's strconv.ParseInt takes in base 10. */
    private static boolean isInteger(String s) {
        int i = 0;
        final int n = s.length();
        if (n > 0 && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
            i = 1;
        }
        if (i == n) {
            return false;
        }
        for (; i < n; i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean hasHexPrefix(String s) {
        final int i = s.length() >= 1 && s.charAt(0) == '-' ? 1 : 0;
        return s.length() >= i + 2 && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X');
    }

    /**
     * The hexadecimal text big numbers print in: {@code [-]0x<magnitude>} is a
     * big integer and {@code [-]0x<unscaled>x[-]<exponent>} a decimal; null
     * when the text is not of that form.
     */
    private static NumberValue parseHexNumber(String s) {
        boolean negative = false;
        if (s.length() >= 1 && s.charAt(0) == '-') {
            s = s.substring(1);
            negative = true;
        }
        if (s.length() >= 2 && s.charAt(0) == '0' && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) {
            s = s.substring(2);
        }
        int exp = 0;
        final int at = s.indexOf(Value.decimalExpDelim());
        if (at != -1) {
            String e = s.substring(at + 1);
            boolean negativeExp = false;
            if (e.length() >= 1 && e.charAt(0) == '-') {
                e = e.substring(1);
                negativeExp = true;
            }
            final byte[] bytes = hexOrNull(e);
            if (bytes == null || bytes.length > 4) {
                return null;
            }
            for (byte b : bytes) {
                exp = (exp << 8) | (b & 0xFF);
            }
            if (negativeExp) {
                exp = -exp;
            }
            s = s.substring(0, at);
        }
        final byte[] magnitude = hexOrNull(s);
        if (magnitude == null) {
            return null;
        }
        BigInteger mantissa = new BigInteger(1, magnitude);
        if (negative) {
            mantissa = mantissa.negate();
        }
        if (exp == 0) {
            return new BigIntNumber(mantissa);
        }
        // The scale is the negated exponent; the one exponent whose negation
        // is not an int takes the largest scale, as it does when it is packed.
        final long scale = -(long) exp;
        return new DecimalNumber(new BigDecimal(mantissa, (int) Math.min(scale, Integer.MAX_VALUE)));
    }

    private static byte[] hexOrNull(String s) {
        if ((s.length() & 1) == 1) {
            s = "0" + s;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Hex.digit(s.charAt(i)) < 0) {
                return null;
            }
        }
        return Hex.decode(s);
    }

    // --- strconv.ParseFloat ----------------------------------------------------

    /**
     * Go's {@code strconv.ParseFloat(s, 64)}: decimal and hexadecimal floats
     * in Go's syntax, underscores between digits, {@code inf},
     * {@code infinity} and {@code nan} in any case; null where Go gives an
     * error, for text that is not a float or one beyond the range of a double.
     */
    private static Double parseFloat(String s) {
        final Double special = special(s);
        if (special != null) {
            return special;
        }
        final String clean = floatSyntax(s);
        if (clean == null) {
            return null;
        }
        final double d;
        try {
            d = Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return null;
        }
        return Double.isInfinite(d) ? null : d;
    }

    private static Double special(String s) {
        if (s.isEmpty()) {
            return null;
        }
        final char c = s.charAt(0);
        if (c == '+' || c == '-' || c == 'i' || c == 'I') {
            final int sign = c == '+' || c == '-' ? 1 : 0;
            int n = commonPrefixIgnoreCase(s, sign, "infinity");
            if (n > 3 && n < 8) {
                n = 3;
            }
            if ((n == 3 || n == 8) && sign + n == s.length()) {
                return c == '-' ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            return null;
        }
        if ((c == 'n' || c == 'N') && s.length() == 3 && commonPrefixIgnoreCase(s, 0, "nan") == 3) {
            return Double.NaN;
        }
        return null;
    }

    private static int commonPrefixIgnoreCase(String s, int from, String lower) {
        int n = Math.min(s.length() - from, lower.length());
        for (int i = 0; i < n; i++) {
            char c = s.charAt(from + i);
            if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            if (c != lower.charAt(i)) {
                return i;
            }
        }
        return n;
    }

    /**
     * Checks the text against the syntax of Go's float literals and returns it
     * without its underscores, in a form {@link Double#parseDouble} reads to
     * the same double; null when Go refuses the text.
     */
    private static String floatSyntax(String s) {
        final int n = s.length();
        int i = 0;
        if (n == 0) {
            return null;
        }
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            i++;
        }
        boolean hex = false;
        if (i + 2 < n && s.charAt(i) == '0' && lower(s.charAt(i + 1)) == 'x') {
            hex = true;
            i += 2;
        }
        boolean underscores = false;
        boolean sawDot = false;
        boolean sawDigits = false;
        loop:
        for (; i < n; i++) {
            final char c = s.charAt(i);
            if (c == '_') {
                underscores = true;
            } else if (c == '.') {
                if (sawDot) {
                    break loop;
                }
                sawDot = true;
            } else if ((c >= '0' && c <= '9') || (hex && lower(c) >= 'a' && lower(c) <= 'f')) {
                sawDigits = true;
            } else {
                break loop;
            }
        }
        if (!sawDigits) {
            return null;
        }
        if (i < n && lower(s.charAt(i)) == (hex ? 'p' : 'e')) {
            i++;
            if (i >= n) {
                return null;
            }
            if (s.charAt(i) == '+' || s.charAt(i) == '-') {
                i++;
            }
            if (i >= n || s.charAt(i) < '0' || s.charAt(i) > '9') {
                return null;
            }
            for (; i < n && ((s.charAt(i) >= '0' && s.charAt(i) <= '9') || s.charAt(i) == '_'); i++) {
                if (s.charAt(i) == '_') {
                    underscores = true;
                }
            }
        } else if (hex) {
            return null; // a hexadecimal mantissa requires an exponent
        }
        if (i != n) {
            return null;
        }
        if (underscores) {
            if (!underscoreOk(s)) {
                return null;
            }
            return s.replace("_", "");
        }
        return s;
    }

    private static char lower(char c) {
        return (char) (c | ('x' - 'X'));
    }

    /** Go's underscoreOK: an underscore only between digits, or between a base prefix and a digit. */
    private static boolean underscoreOk(String s) {
        char saw = '^';
        int i = 0;
        if (s.length() >= 1 && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            s = s.substring(1);
        }
        boolean hex = false;
        if (s.length() >= 2 && s.charAt(0) == '0'
                && (lower(s.charAt(1)) == 'b' || lower(s.charAt(1)) == 'o' || lower(s.charAt(1)) == 'x')) {
            i = 2;
            saw = '0';
            hex = lower(s.charAt(1)) == 'x';
        }
        for (; i < s.length(); i++) {
            final char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (hex && lower(c) >= 'a' && lower(c) <= 'f')) {
                saw = '0';
                continue;
            }
            if (c == '_') {
                if (saw != '0') {
                    return false;
                }
                saw = '_';
                continue;
            }
            if (saw == '_') {
                return false;
            }
            saw = '!';
        }
        return saw != '_';
    }
}
