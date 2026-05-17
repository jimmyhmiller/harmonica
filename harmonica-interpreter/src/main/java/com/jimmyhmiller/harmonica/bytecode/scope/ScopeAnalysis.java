package com.jimmyhmiller.harmonica.bytecode.scope;

import com.jimmyhmiller.harmonica.ast.FunctionDeclaration;
import com.jimmyhmiller.harmonica.ast.Identifier;
import com.jimmyhmiller.harmonica.ast.Node;
import com.jimmyhmiller.harmonica.ast.Program;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Result of running scope analysis over a {@link Program}. Holds the
 * scope tree, per-identifier resolution, and Annex-B function hoisting
 * decisions for downstream consumers (currently
 * {@link com.jimmyhmiller.harmonica.bytecode.Generator}).
 *
 * <p>Entry point: {@link #analyze(Program, boolean)}. Runs Phase 1
 * ({@link ScopeCollector}) followed by Phase 2 ({@link ScopeAnalyzer}).
 *
 * <p>Lookup methods all return {@code null} (or sentinels) for nodes
 * the analysis didn't see — never throw. Callers that haven't been
 * migrated to consume the analysis yet should be able to fall through
 * without inspection.
 *
 * <p>Mirrors the surface LibJS's {@code scope_collector.rs} exposes
 * to its bytecode generator.
 */
public final class ScopeAnalysis {
    private final ScopeRecord rootScope;
    private final IdentityHashMap<Node, ScopeRecord> scopeForNode;
    private final IdentityHashMap<Identifier, IdentifierResolution> resolutions;
    private final IdentityHashMap<FunctionDeclaration, ScopeVariable> annexBBindings;

    ScopeAnalysis(
        ScopeRecord rootScope,
        IdentityHashMap<Node, ScopeRecord> scopeForNode,
        IdentityHashMap<Identifier, IdentifierResolution> resolutions,
        IdentityHashMap<FunctionDeclaration, ScopeVariable> annexBBindings
    ) {
        this.rootScope = rootScope;
        this.scopeForNode = scopeForNode;
        this.resolutions = resolutions;
        this.annexBBindings = annexBBindings;
    }

    /**
     * Entry point. Run both phases and return the analysis result.
     *
     * @param program     the parsed Program AST
     * @param scriptStrict whether the script as a whole runs in strict
     *                     mode (module mode or a top-level {@code "use strict"}).
     *                     Inner function bodies may upgrade this.
     */
    public static ScopeAnalysis analyze(Program program, boolean scriptStrict) {
        ScopeCollector collector = new ScopeCollector(scriptStrict);
        collector.collect(program);
        ScopeAnalyzer analyzer = new ScopeAnalyzer(collector);
        analyzer.analyze();
        return new ScopeAnalysis(
            collector.rootScope(),
            collector.scopeForNode(),
            analyzer.resolutions(),
            analyzer.annexBBindings()
        );
    }

    public ScopeRecord rootScope() { return rootScope; }

    /** Scope introduced by the given AST node, or {@code null} if the node
     *  does not introduce a scope (or wasn't visited). */
    public ScopeRecord scopeFor(Node node) { return scopeForNode.get(node); }

    /** Resolution for an identifier reference, or {@code null} if the
     *  identifier wasn't visited (e.g. it's a property key, not a reference). */
    public IdentifierResolution resolution(Identifier ref) { return resolutions.get(ref); }

    /**
     * The synthesized {@code var}-style binding produced by Annex-B
     * function-in-block hoisting for {@code fd}, or {@code null} if
     * {@code fd} is not Annex-B-eligible.
     *
     * <p>When non-null, the Generator should:
     * <ol>
     *   <li>Allocate the var binding in the enclosing var scope (initially
     *       {@code undefined} — same hoisting prologue as ordinary
     *       {@code var}).</li>
     *   <li>At the function declaration site (inside the block), assign
     *       the current block-binding value to the var binding.</li>
     * </ol>
     */
    public ScopeVariable annexBBinding(FunctionDeclaration fd) {
        return annexBBindings.get(fd);
    }

    /** Read-only view of all Annex-B bindings discovered. */
    public Map<FunctionDeclaration, ScopeVariable> annexBBindings() {
        return java.util.Collections.unmodifiableMap(annexBBindings);
    }
}
