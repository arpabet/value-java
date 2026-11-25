/*
 * Copyright (c) 2026 Karagatan LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.arpabet.value;

import java.util.Arrays;

/**
 * Goes through lists and maps without recursion: the ones that are open are
 * kept on a stack of their own, on the heap, so that how deep a value nests
 * is no matter of the thread's stack. A decoder accepts a thousand levels by
 * default, and a value that deep has to pack, print, compare and hash on a
 * thread with a small stack as on any other, as it does in Go, where a stack
 * grows.
 *
 * <p>The few levels a value usually has are gone through by plain recursion,
 * which is the fastest way and costs no memory. What recursion may take of
 * the stack is bounded ({@link #deep}): a list or a map that finds itself
 * past the bound hands itself, and what it holds, over to the methods here.
 */
final class Walk {

    /**
     * How much of the stack plain recursion may take before a walk goes on
     * from here, counted in levels of packing or printing, whose frames are
     * the smallest. A level of comparing or hashing counts for
     * {@link #COMPARE}, and a level of the writer of JSON for {@link #JSON},
     * which is how much more of the stack they take. So packing and printing
     * recurse for the first 64 levels of a value, comparing and hashing for
     * 32, and the writer of JSON for 16; and whatever the depth of the value,
     * each takes about as much of the stack at the most. Measured on HotSpot
     * 17 and 21, on x86-64 and arm64, interpreted and compiled, that is 10 to
     * 13 KB; the frames of compiled code change with what a program has run.
     *
     * <p>Not final: the tests lower it to send every value this way.
     */
    static int deep = 64;

    /** What a level of comparing or hashing counts for: it is two frames, where packing has one. */
    static final int COMPARE = 2;

    /** What a level of the writer of JSON counts for: its frame is the largest by far. */
    static final int JSON = 4;

    private Walk() {
    }

    /** A list or a map that is open: where its values are, and which comes next. */
    static final class Level {
        Value owner;
        Value[] vals;
        int count;
        int next;
        /** The keys of a map; null for a list. */
        String[] keys;
        /** The indexes of a sparse list; null for a dense list and for a map. */
        int[] indexes;
        /** The hash of the values gone through so far. */
        int hash;
        /** For the writer of JSON: the depth of the values, and what ends the list or map. */
        int depth;
        String close;

        boolean dense() {
            return keys == null && indexes == null;
        }
    }

    static boolean container(Value v) {
        return v instanceof ListValue || v instanceof MapValue;
    }

    /**
     * Whether the values of two lists or maps, which are of one kind and have
     * the same keys, are equal; {@code spent} is how much of {@link #deep}
     * the recursion that came to them has taken.
     */
    static boolean sameValues(Value[] x, Value[] y, int spent) {
        for (int i = 0; i < x.length; i++) {
            final Value v = x[i];
            final Value w = y[i];
            // A value that is no list or map answers at once: its same() would only pass the question on to its equals().
            if (v != w && !(container(v) ? v.same(w, spent + COMPARE) : v.equals(w))) {
                return false;
            }
        }
        return true;
    }

    /** What {@code Arrays.hashCode} gives for the values of a list or a map; {@code spent} is as in {@link #sameValues}. */
    static int hashValues(Value[] vals, int spent) {
        int h = 1;
        for (Value v : vals) {
            h = 31 * h + (container(v) ? v.hash(spent + COMPARE) : v.hashCode());
        }
        return h;
    }

    /** The lists and maps that are open, the innermost last. */
    static final class Open {
        private Level[] levels = new Level[8];
        private int depth;

        /** Opens a list or a map. A mutable one that is open already holds itself. */
        Level push(Value v) {
            if (depth == levels.length) {
                levels = Arrays.copyOf(levels, depth * 2);
            }
            Level l = levels[depth];
            if (l == null) {
                l = new Level();
                levels[depth] = l;
            }
            if (v.isMutable()) {
                for (int i = 0; i < depth; i++) {
                    if (levels[i].owner == v) {
                        throw ListValue.cyclic();
                    }
                }
            }
            l.owner = v;
            l.next = 0;
            l.hash = 1;
            l.keys = null;
            l.indexes = null;
            if (v instanceof MapValue) {
                ((MapValue) v).open(l);
            } else {
                ((ListValue) v).open(l);
            }
            depth++;
            return l;
        }

        /** Closes the innermost and gives the one around it, or null when none is left. */
        Level pop() {
            final Level closed = levels[--depth];
            closed.owner = null;
            closed.vals = null;
            return depth == 0 ? null : levels[depth - 1];
        }
    }

    // --- canonical bytes -----------------------------------------------------

    static void pack(Value root, Packer p) {
        final Open open = new Open();
        Level l = open.push(root);
        header(l, p);
        for (;;) {
            if (l.next == l.count) {
                l = open.pop();
                if (l == null) {
                    return;
                }
                continue;
            }
            final int i = l.next++;
            if (l.keys != null) {
                p.str(l.keys[i]);
            } else if (l.indexes != null) {
                p.integer(l.indexes[i]);
            }
            final Value v = l.vals[i];
            if (container(v)) {
                l = open.push(v);
                header(l, p);
            } else {
                v.pack(p);
            }
        }
    }

    private static void header(Level l, Packer p) {
        if (l.dense()) {
            p.listHeader(l.count);
        } else {
            p.mapHeader(l.count);
        }
    }

    // --- the text of the Go implementation's Jsonify ---------------------------------

    static void jsonify(Value root, StringBuilder out) {
        final Open open = new Open();
        Level l = open.push(root);
        out.append(l.dense() ? '[' : '{');
        for (;;) {
            if (l.next == l.count) {
                out.append(l.dense() ? ']' : '}');
                l = open.pop();
                if (l == null) {
                    return;
                }
                continue;
            }
            final int i = l.next++;
            if (i != 0) {
                out.append(',');
            }
            // A key is quoted as text is, as Go quotes it.
            if (l.keys != null) {
                GoQuote.quote(out, l.keys[i]);
                out.append(": ");
            } else if (l.indexes != null) {
                out.append('"').append(l.indexes[i]).append("\": ");
            }
            final Value v = l.vals[i];
            if (container(v)) {
                l = open.push(v);
                out.append(l.dense() ? '[' : '{');
            } else {
                v.jsonify(out);
            }
        }
    }

    // --- equality and hashing of immutable lists and maps -------------------------------

    /**
     * Whether two immutable lists or maps hold the same: they are of one kind,
     * dense, sparse or map, with the same keys, and their values are equal.
     */
    static boolean equal(Value a, Value b) {
        final Open left = new Open();
        final Open right = new Open();
        Level l = left.push(a);
        Level r = right.push(b);
        if (!alike(l, r)) {
            return false;
        }
        for (;;) {
            if (l.next == l.count) {
                l = left.pop();
                r = right.pop();
                if (l == null) {
                    return true;
                }
                continue;
            }
            final int i = l.next++;
            final Value x = l.vals[i];
            final Value y = r.vals[i];
            if (x == y) {
                continue;
            }
            final boolean nested = container(x);
            if (nested != container(y)) {
                return false;
            }
            if (!nested) {
                if (!x.equals(y)) {
                    return false;
                }
                continue;
            }
            l = left.push(x);
            r = right.push(y);
            if (!alike(l, r)) {
                return false;
            }
        }
    }

    /** Whether two open lists or maps are of one kind, as long, with the same keys. */
    private static boolean alike(Level l, Level r) {
        if (l.count != r.count) {
            return false;
        }
        if (l.keys != null) {
            return r.keys != null && Arrays.equals(l.keys, r.keys);
        }
        if (l.indexes != null) {
            return r.indexes != null && Arrays.equals(l.indexes, r.indexes);
        }
        return r.dense();
    }

    /**
     * The hash of an immutable list or map, which is what
     * {@code Arrays.hashCode} gives for its values, after that of its keys
     * when it has keys. Every list and map inside it that had no hash yet has
     * one after.
     */
    static int hash(Value root) {
        final Open open = new Open();
        Level l = open.push(root);
        for (;;) {
            if (l.next == l.count) {
                int h = l.hash;
                if (l.keys != null) {
                    h = Arrays.hashCode(l.keys) * 31 + h;
                } else if (l.indexes != null) {
                    h = Arrays.hashCode(l.indexes) * 31 + h;
                }
                keep(l.owner, h);
                l = open.pop();
                if (l == null) {
                    return h;
                }
                l.hash = 31 * l.hash + h;
                l.next++;
                continue;
            }
            final Value v = l.vals[l.next];
            if (container(v)) {
                final int known = known(v);
                if (known == 0) {
                    l = open.push(v);
                    continue;
                }
                l.hash = 31 * l.hash + known;
            } else {
                l.hash = 31 * l.hash + v.hashCode();
            }
            l.next++;
        }
    }

    /** The hash an immutable list or map has kept, or 0 when it has none yet. */
    static int known(Value v) {
        if (v instanceof MapValue) {
            return ((MapValue) v).knownHash();
        }
        if (v instanceof DenseList) {
            return ((DenseList) v).hash;
        }
        return ((SparseList) v).hash;
    }

    private static void keep(Value v, int h) {
        if (v instanceof MapValue) {
            ((MapValue) v).keepHash(h);
        } else if (v instanceof DenseList) {
            ((DenseList) v).hash = h;
        } else {
            ((SparseList) v).hash = h;
        }
    }
}
