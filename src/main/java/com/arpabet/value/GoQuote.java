/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/**
 * Go's {@code strconv.Quote}, which the human-readable rendering of a value
 * ({@code toString}, {@code jsonify}) quotes text with: a double-quoted Go
 * string literal in which a character Go does not call printable is escaped.
 *
 * <p>Printable means a letter, mark, number, punctuation or symbol of Unicode,
 * or the ASCII space. The tables are those of the running Java, so a character
 * assigned in a Unicode version newer than the runtime knows is escaped here
 * where a newer Go prints it. That rendering is for people and is not
 * canonical; the lossless JSON and the bytes do not depend on it.
 */
final class GoQuote {

    private GoQuote() {
    }

    static String quote(String s) {
        final StringBuilder b = new StringBuilder(s.length() + 2);
        quote(b, s);
        return b.toString();
    }

    static void quote(StringBuilder b, String s) {
        b.append('"');
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            final char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                if (c == '"' || c == '\\') {
                    b.append('\\');
                }
                b.append(c);
                continue;
            }
            int r = c;
            if (Character.isSurrogate(c)) {
                if (c <= 0xDBFF && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    r = Character.toCodePoint(c, s.charAt(++i));
                } else {
                    if (c >= 0xDC80 && c <= 0xDCFF) {
                        // A byte that is not UTF-8.
                        b.append("\\x");
                        Hex.append(b, c - 0xDC00);
                    } else {
                        // No byte form: it encodes as U+FFFD, which is printable.
                        b.append('\uFFFD');
                    }
                    continue;
                }
            }
            if (isPrint(r)) {
                b.appendCodePoint(r);
                continue;
            }
            switch (r) {
                case 0x07:
                    b.append("\\a");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                case 0x0B:
                    b.append("\\v");
                    break;
                default:
                    if (r < 0x20 || r == 0x7F) {
                        b.append("\\x");
                        Hex.append(b, r);
                    } else if (r < 0x10000) {
                        b.append("\\u");
                        Hex.append(b, r >>> 8);
                        Hex.append(b, r);
                    } else {
                        b.append("\\U");
                        Hex.append(b, r >>> 24);
                        Hex.append(b, r >>> 16);
                        Hex.append(b, r >>> 8);
                        Hex.append(b, r);
                    }
                    break;
            }
        }
        b.append('"');
    }

    /** Go's strconv.IsPrint. */
    static boolean isPrint(int r) {
        if (r < 0x80) {
            return r >= 0x20 && r < 0x7F;
        }
        switch (Character.getType(r)) {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.COMBINING_SPACING_MARK:
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER:
            case Character.OTHER_NUMBER:
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }
}
