package com.jimmyhmiller.harmonica.bytecode;

/**
 * ECMA-262 § 20.4 Symbol values. Each call to {@code Symbol(description)}
 * creates a fresh, unique instance — symbol identity is by reference.
 *
 * <p>v1 caveats:
 * <ul>
 *   <li>{@code Symbol.for / Symbol.keyFor} (the GlobalSymbolRegistry,
 *       § 20.4.2.2 / .3) is implemented in {@link Realm} as a string-keyed
 *       map; the registry is process-global rather than realm-bound.
 *   <li>{@code @@toPrimitive} is recognized by name lookup but our
 *       {@link AbstractOps#toPrimitive} doesn't yet consult it (§ 7.1.1
 *       step 1.a is skipped — see method comment).
 *   <li>{@code @@iterator}, {@code @@asyncIterator}, {@code @@toStringTag},
 *       etc. are pre-allocated as well-known symbols; lookups via these as
 *       property keys map to the symbol's stringified form
 *       ({@code "@@iterator"}, etc.) since our property maps are
 *       String-keyed. This is a v1 deviation — real engines key by symbol
 *       identity.
 * </ul>
 */
public final class JSSymbol {

    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
        new java.util.concurrent.atomic.AtomicInteger();

    private final String description;
    private final int id;

    /** Private — use {@link Realm}'s factory or the {@code Symbol} global. */
    JSSymbol(String description) {
        this.description = description;
        this.id = SEQ.incrementAndGet();
    }

    /** Pre-construct a well-known symbol with a stable identifier. */
    static JSSymbol wellKnown(String description) {
        return new JSSymbol(description);
    }

    public String description() { return description; }
    public int id() { return id; }

    @Override
    public String toString() {
        return "Symbol(" + (description == null ? "" : description) + ")";
    }

    /**
     * Stringified form used as a map key when the runtime's property map is
     * still string-keyed. Each symbol gets a stable, unique key like
     * {@code "@@symbol#7:foo"} so distinct symbols don't collide.
     *
     * <p>Cached: every call site does this concat and then uses the result
     * as a map key, so memoizing avoids an allocation on each invocation —
     * shows up as ~2% of bytes allocated on the lodash benchmark because
     * the {@code in} operator hits Symbol.iterator on every for-of.
     */
    private String cachedKey;
    public String asPropertyKey() {
        String k = cachedKey;
        if (k == null) {
            k = "@@symbol#" + id + ":" + (description == null ? "" : description);
            cachedKey = k;
        }
        return k;
    }

    @Override public int hashCode() { return id; }
    @Override public boolean equals(Object o) { return o == this; }
}
