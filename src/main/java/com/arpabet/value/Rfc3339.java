/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

/**
 * The text of an instant: RFC 3339 in UTC with the fraction the instant needs,
 * written and read exactly as Go's time package does with its RFC3339Nano
 * layout. (java.time would need Android API 26, and differs from Go in what it
 * accepts and in how it prints a year outside 0000 to 9999.)
 */
final class Rfc3339 {

    private Rfc3339() {
    }

    private static final long SECONDS_PER_DAY = 86400;

    /**
     * Go measures time for display in seconds since March 1 of the year
     * -292277022400, as an unsigned 64-bit integer. The Unix epoch is this
     * many seconds after it.
     */
    private static final long ABSOLUTE_YEAR = -292277022400L;
    private static final long UNIX_TO_ABSOLUTE =
            (-ABSOLUTE_YEAR / 400 * 146097L + 719468L) * SECONDS_PER_DAY;

    /**
     * Formats an instant. Every second a long holds has a text: the year takes
     * as many digits as it needs beyond four, and a negative year a sign, as in
     * Go; the seconds within about 258 years of {@link Long#MIN_VALUE} wrap
     * around to the far future, as they do in Go.
     */
    static String format(long seconds, int nanos) {
        final long abs = seconds + UNIX_TO_ABSOLUTE; // as unsigned
        final long days = (abs >>> 1) / (SECONDS_PER_DAY / 2);
        final int rem = (int) (abs - days * SECONDS_PER_DAY);

        // The civil date of a day number counted from March 1 of a year that
        // is a multiple of 400.
        final long era = days / 146097;
        final int doe = (int) (days - era * 146097);
        final int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        final int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        final int mp = (5 * doy + 2) / 153;
        final int day = doy - (153 * mp + 2) / 5 + 1;
        final int month = mp < 10 ? mp + 3 : mp - 9;
        final long year = era * 400 + yoe + (month <= 2 ? 1 : 0) + ABSOLUTE_YEAR;

        final StringBuilder b = new StringBuilder(32);
        long y = year;
        if (y < 0) {
            b.append('-');
            y = -y;
        }
        if (y < 1000) {
            b.append(y < 10 ? "000" : y < 100 ? "00" : "0");
        }
        b.append(y);
        b.append('-');
        two(b, month);
        b.append('-');
        two(b, day);
        b.append('T');
        two(b, rem / 3600);
        b.append(':');
        two(b, rem / 60 % 60);
        b.append(':');
        two(b, rem % 60);
        if (nanos != 0) {
            b.append('.');
            int scale = 100000000;
            int n = nanos;
            while (n != 0) {
                b.append((char) ('0' + n / scale));
                n %= scale;
                scale /= 10;
            }
        }
        b.append('Z');
        return b.toString();
    }

    private static void two(StringBuilder b, int n) {
        b.append((char) ('0' + n / 10)).append((char) ('0' + n % 10));
    }

    /**
     * Parses RFC 3339 text into seconds since the Unix epoch and nanoseconds,
     * accepting what Go's {@code time.Parse(time.RFC3339Nano, s)} accepts: a
     * four-digit year, {@code T} and {@code Z} in upper case, an hour of one or
     * two digits, a fraction of any length after a period or a comma (digits
     * past the ninth are dropped), and {@code Z} or an offset of up to 24:60.
     */
    static long[] parse(String s) {
        final Cursor c = new Cursor(s);
        if (s.length() < 4 || !c.isDigit(0)) {
            throw c.bad();
        }
        final int year = c.fixed(4);
        c.expect('-');
        final int month = c.fixed(2);
        c.expect('-');
        final int day = c.fixed(2);
        c.expect('T');
        final int hour = c.isDigit(1) ? c.fixed(2) : c.fixed(1);
        c.expect(':');
        final int minute = c.fixed(2);
        c.expect(':');
        final int second = c.fixed(2);
        int nanos = 0;
        if (c.has(2) && (c.peek() == '.' || c.peek() == ',') && c.isDigit(1)) {
            c.pos++;
            int digits = 0;
            while (c.isDigit(0)) {
                if (digits < 9) {
                    nanos = nanos * 10 + (c.peek() - '0');
                    digits++;
                }
                c.pos++;
            }
            for (; digits < 9; digits++) {
                nanos *= 10;
            }
        }
        long offset = 0;
        if (c.has(1) && c.peek() == 'Z') {
            c.pos++;
        } else {
            if (!c.has(6) || s.charAt(c.pos + 3) != ':') {
                throw c.bad();
            }
            final char sign = c.peek();
            if (sign != '+' && sign != '-') {
                throw c.bad();
            }
            c.pos++;
            final int oh = c.fixed(2);
            c.pos++;
            final int om = c.fixed(2);
            if (oh > 24 || om > 60) {
                throw c.bad();
            }
            offset = (oh * 60 + om) * 60L;
            if (sign == '-') {
                offset = -offset;
            }
        }
        if (c.pos != s.length()) {
            throw c.bad();
        }
        if (month < 1 || month > 12 || day < 1 || day > daysIn(month, year)
                || hour >= 24 || minute >= 60 || second >= 60) {
            throw c.bad();
        }
        final long seconds = daysFromCivil(year, month, day) * SECONDS_PER_DAY
                + hour * 3600L + minute * 60L + second - offset;
        return new long[] {seconds, nanos};
    }

    private static int daysIn(int month, int year) {
        switch (month) {
            case 2:
                return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    /** Days from 1970-01-01 to a civil date of the proleptic Gregorian calendar. */
    private static long daysFromCivil(int year, int month, int day) {
        final int y = month <= 2 ? year - 1 : year;
        final int era = (y >= 0 ? y : y - 399) / 400;
        final int yoe = y - era * 400;
        final int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        final int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097L + doe - 719468L;
    }

    private static final class Cursor {
        final String s;
        int pos;

        Cursor(String s) {
            this.s = s;
        }

        boolean has(int n) {
            return pos + n <= s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        boolean isDigit(int ahead) {
            final int i = pos + ahead;
            return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
        }

        int fixed(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (!isDigit(0)) {
                    throw bad();
                }
                v = v * 10 + (s.charAt(pos++) - '0');
            }
            return v;
        }

        void expect(char ch) {
            if (pos >= s.length() || s.charAt(pos) != ch) {
                throw bad();
            }
            pos++;
        }

        ValueException bad() {
            return new ValueException("parse timestamp \"" + s + "\": not RFC 3339");
        }
    }
}
