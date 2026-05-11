package com.jimmyhmiller.harmonica.bytecode.cache;

/**
 * Cache for object-literal shapes.
 *
 * <p>When an object literal like {@code {a: 1, b: 2}} is evaluated, the shape
 * of the resulting object is stable across evaluations of the same literal
 * site. This cache remembers that shape (and the property offsets) so the
 * runtime can allocate the object directly with the correct shape and write
 * properties to known slot offsets, bypassing the per-property shape
 * transitions that would otherwise occur.
 *
 * <p>Mutable. Held as a {@code final} field on the owning record.
 *
 * <p>Skeleton.
 */
public final class ObjectShapeCache {

    /** Cached final shape for the object literal site. {@code null} until first use. */
    private volatile Object shape;
    /** Property-offset for each property in declaration order. Lazy-populated. */
    private int[] propertyOffsets;

    public Object shape() { return shape; }

    public void setShape(Object shape) { this.shape = shape; }

    public int[] propertyOffsets() { return propertyOffsets; }

    public void setPropertyOffsets(int[] offsets) { this.propertyOffsets = offsets; }
}
