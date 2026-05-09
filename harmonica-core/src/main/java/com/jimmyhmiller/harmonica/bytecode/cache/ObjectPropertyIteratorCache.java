package com.jimmyhmiller.harmonica.bytecode.cache;

/**
 * Cache for {@code for-in}-style property iterators.
 *
 * <p>The set of enumerable property names for a given shape is stable as long
 * as the shape doesn't change. This cache holds the precomputed name list and
 * the shape it was computed against; on a subsequent iteration with the same
 * shape we skip the property walk.
 *
 * <p>Mutable. Skeleton.
 */
public final class ObjectPropertyIteratorCache {

    private volatile Object shapeAtCacheTime;
    private Object[] propertyNames;

    public boolean matches(Object currentShape) {
        return shapeAtCacheTime == currentShape;
    }

    public Object[] propertyNames() { return propertyNames; }

    public void install(Object shape, Object[] names) {
        this.shapeAtCacheTime = shape;
        this.propertyNames = names;
    }

    public void invalidate() {
        this.shapeAtCacheTime = null;
        this.propertyNames = null;
    }
}
