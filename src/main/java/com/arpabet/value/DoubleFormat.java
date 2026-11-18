/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The shortest decimal digits of a double and the texts made from them.
 *
 * <p>The digits are the ones Go's strconv and JavaScript's Number::toString
 * give: the fewest that read back as the same double, and among those the
 * closest to it, a tie going to the even digit. They are computed here with
 * exact integer arithmetic rather than taken from {@link Double#toString},
 * whose output differs between Java versions and Android releases, because the
 * canonical JSON of a value has to be the same text everywhere.
 */
final class DoubleFormat {

    private DoubleFormat() {
    }

    /** Digits without leading or trailing zeros; the value is {@code 0.digits × 10^point}. */
    static final class Digits {
        final String digits;
        final int point;

        Digits(String digits, int point) {
            this.digits = digits;
            this.point = point;
        }
    }

    private static final double TWO_53 = 9007199254740992.0;
    private static final float TWO_24 = 16777216.0f;

    /** The shortest digits of a finite double above zero. */
    static Digits shortest(double d) {
        if (d < TWO_53 && d == Math.rint(d)) {
            // An integer a double holds exactly, with no neighbor within a half.
            final String s = Long.toString((long) d);
            return new Digits(stripTrailingZeros(s), s.length());
        }
        final long bits = Double.doubleToRawLongBits(d);
        final int biased = (int) ((bits >>> 52) & 0x7FF);
        final long frac = bits & 0xFFFFFFFFFFFFFL;
        if (biased == 0) {
            return shortest(frac, -1074, false, d);
        }
        return shortest(frac | (1L << 52), biased - 1075, frac == 0 && biased > 1, d);
    }

    /** The shortest digits of a finite float above zero: the fewest that read back as the same float. */
    static Digits shortest(float f) {
        if (f < TWO_24 && f == (float) Math.rint(f)) {
            final String s = Long.toString((long) f);
            return new Digits(stripTrailingZeros(s), s.length());
        }
        final int bits = Float.floatToRawIntBits(f);
        final int biased = (bits >>> 23) & 0xFF;
        final int frac = bits & 0x7FFFFF;
        if (biased == 0) {
            return shortest(frac, -149, false, f);
        }
        return shortest(frac | (1 << 23), biased - 150, frac == 0 && biased > 1, f);
    }

    /**
     * The shortest digits of m × 2^e, a float of some width whose neighbors
     * are m - 1 and m + 1 at the same exponent, or half as far below when
     * {@code narrow} (m is the power of two at the bottom of its range).
     */
    private static Digits shortest(long m, int e, boolean narrow, double approx) {
        // The numbers that round to this one lie between the halfway points to
        // its neighbors. The bounds themselves round to it when m is even.
        final boolean inclusive = (m & 1) == 0;
        final BigInteger v = BigInteger.valueOf(4 * m);
        final BigInteger upper = BigInteger.valueOf(4 * m + 2);
        final BigInteger lower = BigInteger.valueOf(narrow ? 4 * m - 1 : 4 * m - 2);
        final int be = e - 2; // the number is v × 2^be

        // point: 10^(point-1) <= number < 10^point.
        int point = (int) Math.floor(Math.log10(approx)) + 1;
        while (comparePow10(v, be, point) >= 0) {
            point++;
        }
        while (comparePow10(v, be, point - 1) < 0) {
            point--;
        }

        // The number and its bounds in units of 10^(point-17), the finest unit
        // a shortest text can need: q, lowQ and upQ whole units, and whether a
        // fraction of a unit is left over. This is the only exact arithmetic;
        // every shorter candidate is then a matter of dividing q.
        final int p10 = MAX_DIGITS - point;
        BigInteger mul = be > 0 ? BigInteger.ONE.shiftLeft(be) : BigInteger.ONE;
        BigInteger den = be < 0 ? BigInteger.ONE.shiftLeft(-be) : BigInteger.ONE;
        if (p10 > 0) {
            mul = mul.multiply(BigInteger.TEN.pow(p10));
        } else if (p10 < 0) {
            den = den.multiply(BigInteger.TEN.pow(-p10));
        }
        final BigInteger[] vq = v.multiply(mul).divideAndRemainder(den);
        final BigInteger[] lq = lower.multiply(mul).divideAndRemainder(den);
        final BigInteger[] uq = upper.multiply(mul).divideAndRemainder(den);
        final long q = vq[0].longValue();
        final boolean whole = vq[1].signum() == 0;
        final int half = vq[1].shiftLeft(1).compareTo(den); // the left-over against half a unit
        final long lowQ = lq[0].longValue();
        final boolean lowWhole = lq[1].signum() == 0;
        final long upQ = uq[0].longValue();
        final boolean upWhole = uq[1].signum() == 0;

        for (int k = 1; k <= MAX_DIGITS; k++) {
            // The two decimals of k digits around the number: s and s + 1 in
            // units of 10^(point-k), which is `unit` of the fine units.
            final long unit = POW10[MAX_DIGITS - k];
            final long s = q / unit;
            final long rest = q - s * unit;
            final long chosen;
            if (rest == 0 && whole) {
                chosen = s;
            } else {
                final long down = s * unit;
                final long up = down + unit;
                final boolean downOk = down > lowQ || (inclusive && down == lowQ && lowWhole);
                final boolean upOk = up < upQ || (up == upQ && (inclusive || !upWhole));
                if (downOk && upOk) {
                    // Which is closer; a tie goes to the even one.
                    final int side;
                    if (unit == 1) {
                        side = half;
                    } else {
                        side = 2 * rest < unit ? -1 : 2 * rest > unit ? 1 : whole ? 0 : 1;
                    }
                    chosen = side < 0 || (side == 0 && (s & 1) == 0) ? s : s + 1;
                } else if (downOk) {
                    chosen = s;
                } else if (upOk) {
                    chosen = s + 1;
                } else {
                    continue;
                }
            }
            final String digits = Long.toString(chosen);
            // s + 1 may be 10^k, which is the digit 1 one place higher.
            return new Digits(stripTrailingZeros(digits), point + (digits.length() - k));
        }
        throw new IllegalStateException("no shortest digits for " + m + " x 2^" + e);
    }

    /** Seventeen digits tell any double from its neighbors. */
    private static final int MAX_DIGITS = 17;

    private static final long[] POW10 = new long[MAX_DIGITS + 1];

    static {
        POW10[0] = 1;
        for (int i = 1; i < POW10.length; i++) {
            POW10[i] = POW10[i - 1] * 10;
        }
    }

    /** Compares v × 2^be with 10^p. */
    private static int comparePow10(BigInteger v, int be, int p) {
        BigInteger left = be > 0 ? v.shiftLeft(be) : v;
        BigInteger right = be < 0 ? BigInteger.ONE.shiftLeft(-be) : BigInteger.ONE;
        if (p > 0) {
            right = right.multiply(BigInteger.TEN.pow(p));
        } else if (p < 0) {
            left = left.multiply(BigInteger.TEN.pow(-p));
        }
        return left.compareTo(right);
    }

    private static String stripTrailingZeros(String s) {
        int n = s.length();
        while (n > 1 && s.charAt(n - 1) == '0') {
            n--;
        }
        return n == s.length() ? s : s.substring(0, n);
    }

    private static void zeros(StringBuilder b, int n) {
        for (int i = 0; i < n; i++) {
            b.append('0');
        }
    }

    /**
     * Go's {@code strconv.FormatFloat(d, 'f', -1, 64)}: the shortest digits
     * written out without an exponent, {@code NaN}, {@code +Inf} and
     * {@code -Inf}. It is how a double prints in {@code toString}.
     */
    static String plain(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "+Inf" : "-Inf";
        }
        final boolean negative = Double.doubleToRawLongBits(d) < 0;
        if (d == 0) {
            return negative ? "-0" : "0";
        }
        final Digits g = shortest(Math.abs(d));
        final int k = g.digits.length();
        final StringBuilder b = new StringBuilder(Math.max(k, Math.abs(g.point)) + 4);
        if (negative) {
            b.append('-');
        }
        if (g.point <= 0) {
            b.append("0.");
            zeros(b, -g.point);
            b.append(g.digits);
        } else if (g.point >= k) {
            b.append(g.digits);
            zeros(b, g.point - k);
        } else {
            b.append(g.digits, 0, g.point).append('.').append(g.digits, g.point, k);
        }
        return b.toString();
    }

    /**
     * The shortest text that reads back as the double, in the form JavaScript's
     * Number.prototype.toString writes: plain digits from 1e-7 to 1e21, an
     * exponent outside. -0 keeps its sign; the non-finite doubles are
     * {@code NaN}, {@code Infinity} and {@code -Infinity}. Go's DoubleText.
     */
    static String text(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        final boolean negative = Double.doubleToRawLongBits(d) < 0;
        if (d == 0) {
            return negative ? "-0" : "0";
        }
        final Digits g = shortest(Math.abs(d));
        final String digits = g.digits;
        final int k = digits.length();
        final int n = g.point;
        final StringBuilder b = new StringBuilder(k + 8);
        if (negative) {
            b.append('-');
        }
        if (k <= n && n <= 21) {
            b.append(digits);
            zeros(b, n - k);
        } else if (0 < n && n <= 21) {
            b.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            b.append("0.");
            zeros(b, -n);
            b.append(digits);
        } else {
            b.append(digits.charAt(0));
            if (k > 1) {
                b.append('.').append(digits, 1, k);
            }
            b.append('e');
            if (n - 1 >= 0) {
                b.append('+');
            }
            b.append(n - 1);
        }
        return b.toString();
    }

    /**
     * The decimal of the shortest digits, with no trailing zero in its
     * unscaled integer: 100.0 is 1e2. A zero of either sign is 0.
     */
    static BigDecimal shortestDecimal(double d) {
        if (d == 0) {
            return BigDecimal.ZERO;
        }
        final Digits g = shortest(Math.abs(d));
        BigInteger unscaled = new BigInteger(g.digits);
        if (d < 0) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, g.digits.length() - g.point);
    }

    /** The decimal of the shortest digits of a float. */
    static BigDecimal shortestDecimal(float f) {
        if (f == 0) {
            return BigDecimal.ZERO;
        }
        final Digits g = shortest(Math.abs(f));
        BigInteger unscaled = new BigInteger(g.digits);
        if (f < 0) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, g.digits.length() - g.point);
    }

    /**
     * The decimal Go reads from the shortest text of the double in its 'g'
     * form, which spells an integer below a million out and gives every other
     * double its shortest digits and an exponent: 100.0 is 100 and 1200000.0
     * is 12e5.
     */
    static BigDecimal exactDecimal(double d) {
        if (d == 0) {
            return BigDecimal.ZERO;
        }
        final Digits g = shortest(Math.abs(d));
        final int k = g.digits.length();
        final int x = g.point - 1;
        BigInteger unscaled = new BigInteger(g.digits);
        int scale = k - g.point;
        if (x >= -4 && x < 6 && g.point >= k) {
            unscaled = unscaled.multiply(BigInteger.TEN.pow(g.point - k));
            scale = 0;
        }
        if (d < 0) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, scale);
    }
}
