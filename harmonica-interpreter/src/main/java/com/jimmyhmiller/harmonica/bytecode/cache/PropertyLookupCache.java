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
    /**
     * Prototype-chain owner for each entry, or {@code null} if the
     * property is own. When non-null, the cached slot offset refers to
     * the owner's storage, and callers must verify {@code owner.shape()}
     * still matches {@link #ownerShapes} (or the cached resolution is
     * stale because the prototype mutated).
     */
    private final Object[] owners = new Object[MAX_SHAPES];
    /** Owner shape when {@link #owners}{@code [i] != null}. */
    private final Object[] ownerShapes = new Object[MAX_SHAPES];
    /** Number of populated entries (0..MAX_SHAPES). */
    private int entries;
    /** Hit counter — diagnostic / tier-up signal. */
    private long hits;
    /** Miss counter. */
    private long misses;

    /**
     * Look up an entry matching {@code shape}. Returns the slot offset on
     * a hit, or {@code -1} on miss. After a hit, {@link #ownerOf} reveals
     * whether the entry is own ({@code null}) or proto-chain (non-null);
     * for proto-chain entries the slot offset refers to the owner's
     * storage, and callers must verify {@code owner.shape() ==
     * }{@link #ownerShapeOf()}.
     */
    public int lookup(Object shape) {
        int n = entries;
        for (int i = 0; i < n; i++) {
            if (shapes[i] == shape) {
                promote(i);
                hits++;
                return slotOffsets[0];
            }
        }
        misses++;
        return -1;
    }

    /** Owner from the most-recent {@link #lookup} hit ({@code null} = own). */
    public Object ownerOf()      { return owners[0]; }
    /** Owner shape from the most-recent {@link #lookup} hit. */
    public Object ownerShapeOf() { return ownerShapes[0]; }

    /**
     * Install (or refresh) an own-property entry at the MRU position.
     */
    public void install(Object shape, int slotOffset) {
        installEntry(shape, null, null, slotOffset);
    }

    /**
     * Install (or refresh) a prototype-chain entry. {@code owner} is the
     * JSObject (typically a {@code .prototype}) that owns the property,
     * with {@code ownerShape} as its shape at install time.
     */
    public void installProto(Object receiverShape, Object owner, Object ownerShape, int slotOffset) {
        installEntry(receiverShape, owner, ownerShape, slotOffset);
    }

    private void installEntry(Object shape, Object owner, Object ownerShape, int slotOffset) {
        int copyCount = Math.min(entries, MAX_SHAPES - 1);
        if (copyCount > 0) {
            System.arraycopy(shapes,     0, shapes,     1, copyCount);
            System.arraycopy(slotOffsets, 0, slotOffsets, 1, copyCount);
            System.arraycopy(owners,      0, owners,      1, copyCount);
            System.arraycopy(ownerShapes, 0, ownerShapes, 1, copyCount);
        }
        shapes[0] = shape;
        slotOffsets[0] = slotOffset;
        owners[0] = owner;
        ownerShapes[0] = ownerShape;
        if (entries < MAX_SHAPES) entries++;
    }

    private void promote(int i) {
        if (i == 0) return;
        Object foundShape  = shapes[i];
        int    foundOffset = slotOffsets[i];
        Object foundOwner  = owners[i];
        Object foundOwnerS = ownerShapes[i];
        System.arraycopy(shapes,      0, shapes,      1, i);
        System.arraycopy(slotOffsets, 0, slotOffsets, 1, i);
        System.arraycopy(owners,      0, owners,      1, i);
        System.arraycopy(ownerShapes, 0, ownerShapes, 1, i);
        shapes[0]      = foundShape;
        slotOffsets[0] = foundOffset;
        owners[0]      = foundOwner;
        ownerShapes[0] = foundOwnerS;
    }

    public int entries() { return entries; }
    public long hits()   { return hits; }
    public long misses() { return misses; }
}
