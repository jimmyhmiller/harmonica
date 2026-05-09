package com.jimmyhmiller.harmonica.bytecode.cache;

/**
 * Polymorphic inline cache for property reads/writes.
 *
 * <p>Each call site (one {@code GetById}, {@code PutById}, {@code GetLength},
 * etc. instruction) owns one of these. The cache remembers up to
 * {@link #MAX_SHAPES} (shape, slot-offset) pairs observed at this site, with
 * a most-recently-used policy.
 *
 * <p>Mutable. The owning instruction holds a {@code final} reference; the
 * record's identity is unchanged when this cache mutates.
 *
 * <p>Skeleton — {@link #lookup} and {@link #install} are minimal placeholders.
 * The real Shape type and prototype-chain validity tracking will be wired in
 * when the object model is defined.
 */
public final class PropertyLookupCache {

    public static final int MAX_SHAPES = 4;

    /** Observed receiver shapes, MRU-first. {@code null} entries past {@link #entries}. */
    private final Object[] shapes = new Object[MAX_SHAPES];
    /** Property slot offset to load/store for the matching shape. */
    private final int[] slotOffsets = new int[MAX_SHAPES];
    /** Number of populated entries (0..MAX_SHAPES). */
    private int entries;
    /** Hit counter — diagnostic / tier-up signal. */
    private long hits;
    /** Miss counter. */
    private long misses;

    /**
     * Look up the slot offset for the given shape, or {@code -1} if not cached.
     * On hit, the matching entry is moved to the MRU position.
     */
    public int lookup(Object shape) {
        int n = entries;
        for (int i = 0; i < n; i++) {
            if (shapes[i] == shape) {
                if (i != 0) {
                    Object foundShape = shapes[i];
                    int foundOffset = slotOffsets[i];
                    System.arraycopy(shapes, 0, shapes, 1, i);
                    System.arraycopy(slotOffsets, 0, slotOffsets, 1, i);
                    shapes[0] = foundShape;
                    slotOffsets[0] = foundOffset;
                }
                hits++;
                return slotOffsets[0];
            }
        }
        misses++;
        return -1;
    }

    /**
     * Install (or refresh) an entry at the MRU position. Existing entries
     * shift down; the oldest is evicted if the cache is full.
     */
    public void install(Object shape, int slotOffset) {
        int copyCount = Math.min(entries, MAX_SHAPES - 1);
        if (copyCount > 0) {
            System.arraycopy(shapes, 0, shapes, 1, copyCount);
            System.arraycopy(slotOffsets, 0, slotOffsets, 1, copyCount);
        }
        shapes[0] = shape;
        slotOffsets[0] = slotOffset;
        if (entries < MAX_SHAPES) entries++;
    }

    public int entries() { return entries; }
    public long hits()   { return hits; }
    public long misses() { return misses; }
}
