package com.jimmyhmiller.harmonica.bytecode.cache;

/**
 * Cache slot for the template object produced by a tagged-template literal.
 *
 * <p>Per spec (sec-gettemplateobject), each tagged-template site returns the
 * <em>same</em> array object on every evaluation. We cache it here on first
 * production.
 *
 * <p>Mutable (single-shot — set once, read forever). Skeleton.
 */
public final class TemplateObjectCache {

    private volatile Object cachedTemplateObject;

    public Object cached() { return cachedTemplateObject; }

    public void install(Object templateObject) {
        // Spec requires identity persistence; double-write is a generator bug.
        if (cachedTemplateObject != null && cachedTemplateObject != templateObject) {
            throw new IllegalStateException(
                "TemplateObjectCache.install: attempted to overwrite cached template object with a different instance");
        }
        cachedTemplateObject = templateObject;
    }
}
