package com.jimmyhmiller.harmonica.bytecode.cache;

/**
 * Inline cache for a function call instruction.
 *
 * <p>Caches the most-recently-resolved callee target (and its identity guard,
 * usually the JS function object reference itself) so that repeated calls to
 * the same function can skip the property-lookup / shape-walk that produced
 * the callee.
 *
 * <p>Skeleton — the real implementation will hold a {@code MethodHandle} to
 * the entry trampoline, plus polymorphic guards for monomorphic / dimorphic /
 * polymorphic / megamorphic states (à la JSC's IC tiers). For v1 we just
 * count hits and stash a single observed target.
 */
public final class CallSite {

    /** Last observed callee. Identity-compared on dispatch. */
    private volatile Object cachedCallee;
    /** Hit count — diagnostic / tier-up signal. */
    private long hits;
    private long misses;

    /** Returns true if this site is currently cached on the given callee. */
    public boolean matches(Object callee) {
        if (cachedCallee == callee) {
            hits++;
            return true;
        }
        misses++;
        return false;
    }

    public void install(Object callee) {
        cachedCallee = callee;
    }

    public Object cachedCallee() { return cachedCallee; }
    public long hits()           { return hits; }
    public long misses()         { return misses; }
}
