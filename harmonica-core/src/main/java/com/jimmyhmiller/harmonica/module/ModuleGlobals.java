package com.jimmyhmiller.harmonica.module;

import java.util.HashMap;

/**
 * The globals map for an ESM module. Identity-distinct from a plain
 * {@code HashMap} so the interpreter can detect when it's running inside a
 * module frame (and stamp {@code homeGlobals} on newly-created functions).
 *
 * <p>The map stores both the module's own top-level bindings and {@code
 * ImportRef} sentinels for imported names. Reads/writes for ImportRef values
 * are intercepted at the relevant {@code Op.GetGlobal} / {@code Op.SetGlobal}
 * sites — we deliberately do <i>not</i> override {@link HashMap#get} so that
 * other readers (loader code introspecting exports, etc.) see the raw stored
 * value and can decide what to do.
 */
public final class ModuleGlobals extends HashMap<String, Object> {
    private static final long serialVersionUID = 1L;

    public ModuleGlobals() { super(); }
    public ModuleGlobals(int initialCapacity) { super(initialCapacity); }
}
