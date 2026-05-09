package com.jimmyhmiller.harmonica.module;

import java.util.Map;

/**
 * Sentinel placed in a module's globals map under an imported name. Reads on
 * the importing side dereference through to the source module's binding —
 * this is how ECMA-262 § 16.2.1.5 live bindings are realized in our model.
 *
 * <p>Importers never write to imported names (spec: TypeError in strict mode);
 * the interpreter ops detect ImportRef on assignment and throw.
 */
public final class ImportRef {
    /** The source module's globals map. */
    public final Map<String, Object> sourceGlobals;
    /** The export name within the source (already resolved through re-exports). */
    public final String sourceName;

    public ImportRef(Map<String, Object> sourceGlobals, String sourceName) {
        this.sourceGlobals = sourceGlobals;
        this.sourceName = sourceName;
    }

    @Override public String toString() {
        return "ImportRef(" + sourceName + ")";
    }
}
