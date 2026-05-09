package com.jimmyhmiller.harmonica.module;

import com.jimmyhmiller.harmonica.bytecode.JSObject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in the {@link ModuleLoader} cache. Carries everything we know
 * about a loaded module — its absolute path, its format, the data needed for
 * cross-module linking (per-module globals + namespace JSObject for ESM, the
 * {@code module.exports} value for CJS), and a phase flag used for cycle
 * detection during the linking + evaluation pass.
 */
public final class ModuleRecord {

    public enum Phase {
        /** Cache slot reserved; source not yet parsed. */
        RESERVED,
        /** Currently linking dependencies (we may re-enter via a cycle). */
        LINKING,
        /** Linking complete; body executing or about to. */
        EVALUATING,
        /** Body has run; namespace fully populated. */
        EVALUATED,
    }

    public final Path path;
    public final ModuleResolver.Format format;

    /**
     * For ESM: the per-module top-level binding store. Holds the module's own
     * lets/consts/vars/functions plus {@link ImportRef} sentinels for each
     * imported name (so reads dereference to the source module live).
     * Null for CJS.
     */
    public Map<String, Object> moduleGlobals;

    /**
     * For ESM: the module namespace exotic object (ECMA-262 § 28.3). Reads of
     * its own properties are live — they go through accessor getters that
     * read {@code moduleGlobals} at access time. Null for CJS.
     */
    public JSObject namespace;

    /**
     * For ESM: maps each exported name (as seen by importers — i.e. after
     * {@code as} renaming) to the {@link ExportBinding} that resolves it. For
     * re-exports ({@code export { x } from './m'}), the binding's source
     * module is the re-exported module, not this one — so reads of
     * {@code namespace.x} stay live across the chain.
     */
    public final Map<String, ExportBinding> exports = new LinkedHashMap<>();

    /**
     * For CJS: the {@code module.exports} value (any JS value). For ESM-from-
     * CJS interop the loader synthesizes this into a namespace.
     */
    public Object cjsExports;

    public Phase phase = Phase.RESERVED;

    public ModuleRecord(Path path, ModuleResolver.Format format) {
        this.path = path;
        this.format = format;
    }

    /**
     * The resolved source of an exported name. For a local export (the source
     * module declares the binding), {@code source} is this module's globals
     * and {@code sourceName} is the local declared name. For a re-export
     * ({@code export { x } from './m'}), {@code source} is m's globals.
     */
    public record ExportBinding(Map<String, Object> source, String sourceName) {}

    /**
     * What does {@code require()} or the CLI hand back to the caller? For CJS
     * it's {@code module.exports}; for ESM it's the namespace object (which
     * has the same default + named-exports view that {@code require()} of an
     * ESM module returns in modern Node). For JSON it's the parsed value.
     */
    public Object effectiveExports() {
        return switch (format) {
            case CJS, JSON -> cjsExports;
            case ESM -> namespace;
            case BUILTIN -> throw new IllegalStateException("node: builtins not implemented");
        };
    }
}
