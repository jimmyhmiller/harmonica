package com.jimmyhmiller.harmonica.bytecode;

/**
 * Rope-style lazy string concatenation. Wraps two {@link CharSequence}s
 * and defers building a flat {@link String} until one is actually needed.
 *
 * <p>The motivation is the classic {@code s += x} loop pattern: with
 * immutable Java {@code String}s, every iteration allocates and copies
 * O(n) characters, so the whole loop is O(n²). With this class, every
 * iteration creates one small {@code ConsString} that holds two
 * references; flattening costs O(total) and happens at most once per
 * accumulated value.
 *
 * <p>{@code ConsString} satisfies {@link CharSequence}, so it can flow
 * through anywhere the runtime accepts a generic char-sequence — most
 * notably {@code java.util.regex.Matcher} (regex .test/.match) and
 * {@link StringBuilder#append}, which both implement specialized fast
 * paths that avoid the explicit flatten.
 *
 * <p>Branding: JS values use {@code instanceof CharSequence} (covers
 * {@code String} and {@code ConsString}) instead of {@code instanceof
 * String} at sites that should accept either; everything else (property
 * keys, regex patterns, etc.) continues to take {@code String} and
 * callers flatten first via {@link #toString()}.
 *
 * <p>Not thread-safe; JS execution is single-threaded.
 */
public final class ConsString implements CharSequence {

    private CharSequence left;
    private CharSequence right;
    private final int length;
    /**
     * Cached flat form. Once non-null, {@link #left} is rebound to it and
     * {@link #right} cleared so the cons tree is eligible for GC. Reads
     * after that short-circuit straight to the flat string.
     */
    private String flat;

    private ConsString(CharSequence left, CharSequence right, int length) {
        this.left = left;
        this.right = right;
        this.length = length;
    }

    /**
     * Build a cons of two char-sequences. The result's length is the sum
     * of the inputs'; the tree shape is not balanced (every operation is
     * left-associative as the JS source dictates).
     */
    public static CharSequence cons(CharSequence l, CharSequence r) {
        if (l.length() == 0) return r;
        if (r.length() == 0) return l;
        return new ConsString(l, r, l.length() + r.length());
    }

    @Override
    public int length() { return length; }

    @Override
    public char charAt(int index) {
        if (flat != null) return flat.charAt(index);
        int ll = left.length();
        return index < ll ? left.charAt(index) : right.charAt(index - ll);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        // subSequence is rare in JS workloads (String.prototype.slice
        // handles itself); flatten and substring for simplicity.
        return toString().substring(start, end);
    }

    @Override
    public String toString() {
        if (flat == null) flatten();
        return flat;
    }

    private void flatten() {
        StringBuilder sb = new StringBuilder(length);
        appendTo(sb);
        flat = sb.toString();
        // Collapse the cons tree: future reads hit the flat string and
        // the intermediate ConsString nodes become eligible for GC.
        left = flat;
        right = "";
    }

    private void appendTo(StringBuilder sb) {
        if (flat != null) {
            sb.append(flat);
            return;
        }
        if (left instanceof ConsString cl) cl.appendTo(sb);
        else sb.append(left);
        if (right instanceof ConsString cr) cr.appendTo(sb);
        else sb.append(right);
    }

    /**
     * Equality matches the flattened-string semantics: two
     * {@code CharSequence}s with the same character data compare equal.
     * Flattens both sides — every JS string-equality site has to look at
     * all characters anyway.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other instanceof CharSequence cs) {
            if (cs.length() != length) return false;
            return toString().equals(cs.toString());
        }
        return false;
    }

    @Override
    public int hashCode() {
        // Match String.hashCode so a ConsString and its flattened String
        // hash identically — required for any HashMap that might see
        // either form as a key.
        return toString().hashCode();
    }
}
