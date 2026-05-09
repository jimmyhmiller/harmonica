package com.jimmyhmiller.harmonica.bytecode.cache;

/**
 * Cached environment-record lookup result for binding-access instructions.
 *
 * <p>A binding name resolves to a {@code (depth, slot)} pair: the number of
 * environment links to walk outward, then the slot index inside that
 * environment's binding storage. Once resolved at a given site, the result is
 * stable as long as no intervening environment is destroyed and the binding
 * is not deleted — which our generator can usually statically guarantee.
 *
 * <p>Mutable. Skeleton.
 */
public final class EnvironmentCoordinate {

    /** -1 means "uninitialized". */
    private int depth = -1;
    private int slot = -1;
    /** Generation counter for invalidation. */
    private long generation;

    public boolean isResolved() { return depth >= 0; }

    public int depth() { return depth; }
    public int slot()  { return slot; }

    public void install(int depth, int slot, long generation) {
        this.depth = depth;
        this.slot = slot;
        this.generation = generation;
    }

    public boolean isValid(long currentGeneration) {
        return depth >= 0 && generation == currentGeneration;
    }

    public void invalidate() { this.depth = -1; }
}
