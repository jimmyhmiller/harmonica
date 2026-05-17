package com.jimmyhmiller.harmonica.bytecode.scope;

import com.jimmyhmiller.harmonica.ast.FunctionDeclaration;
import com.jimmyhmiller.harmonica.ast.Identifier;
import com.jimmyhmiller.harmonica.ast.Node;

import java.util.IdentityHashMap;

/**
 * Phase 2: refine the scope tree built by {@link ScopeCollector}.
 *
 * <p>Currently performs two passes:
 * <ol>
 *   <li><b>Eval-poison propagation</b> — any scope on the path from a direct
 *       {@code eval(...)} call up to the enclosing var scope is marked
 *       {@link ScopeRecord#evalPoisoned()}. The Generator must keep dynamic
 *       lookups (no indexed-local optimization) for names declared in
 *       those scopes.</li>
 *   <li><b>Annex-B function hoisting</b> (ECMA-262 § B.3.2.4) — a
 *       sloppy-mode {@code function f(){}} appearing as a Statement
 *       inside a Block also gets a {@code var f} hoisted to the
 *       enclosing function/script scope, provided no conflicting
 *       binding exists there. The synthesized {@link ScopeVariable} is
 *       recorded in {@link #annexBBindings()} keyed by the original
 *       {@link FunctionDeclaration}.</li>
 * </ol>
 *
 * <p>Identifier resolution (mapping each {@link Identifier} reference to a
 * {@link ScopeVariable} or to {@link IdentifierResolution#GLOBAL}) is not
 * performed yet — Phase 1 doesn't collect references — but the field
 * exists so callers can be wired now.
 */
final class ScopeAnalyzer {
    private final ScopeCollector collector;
    private final IdentityHashMap<Identifier, IdentifierResolution> resolutions = new IdentityHashMap<>();
    private final IdentityHashMap<FunctionDeclaration, ScopeVariable> annexBBindings = new IdentityHashMap<>();

    ScopeAnalyzer(ScopeCollector collector) {
        this.collector = collector;
    }

    IdentityHashMap<Identifier, IdentifierResolution> resolutions() { return resolutions; }
    IdentityHashMap<FunctionDeclaration, ScopeVariable> annexBBindings() { return annexBBindings; }

    void analyze() {
        propagateEvalPoison(collector.rootScope());
        performAnnexBHoisting(collector.rootScope());
    }

    // ----- Eval poison -----

    /**
     * If any descendant scope (within the same var scope) has a direct
     * {@code eval} call, every scope on the chain between {@code root}
     * and that descendant — up to and including {@code root} — is marked
     * {@code evalPoisoned}. The traversal does <em>not</em> cross
     * function boundaries: an inner function with eval doesn't poison
     * the outer function (eval can only inject vars into the calling
     * function's scope, and only in sloppy mode — but for the
     * optimization-blocking flag we still treat it the same).
     *
     * <p>Returns {@code true} if {@code scope} itself or any of its
     * non-Function descendants contains a direct eval — the caller uses
     * this to decide whether to poison the chain upward.
     */
    private boolean propagateEvalPoison(ScopeRecord scope) {
        boolean any = scope.hasDirectEval();
        for (ScopeRecord child : scope.children()) {
            boolean childPoisoned;
            if (child.type() == ScopeType.Function) {
                // Recurse to compute the inner function's own poison
                // independently, but the inner function's eval does NOT
                // bubble up past the function boundary.
                propagateEvalPoison(child);
                childPoisoned = false;
            } else {
                childPoisoned = propagateEvalPoison(child);
            }
            if (childPoisoned) any = true;
        }
        if (any) scope.setEvalPoisoned(true);
        return any;
    }

    // ----- Annex-B function hoisting -----

    private void performAnnexBHoisting(ScopeRecord scope) {
        if (scope.type().isVarScope()) {
            // Strict mode disables Annex B entirely (§ B.3.2 introduction).
            if (!scope.strict()) {
                collectAnnexBCandidates(scope, scope);
            }
        }
        for (ScopeRecord child : scope.children()) {
            performAnnexBHoisting(child);
        }
    }

    /**
     * Walk descendants of {@code varScope} (stopping at nested var scopes),
     * and for each block-scoped function declaration consider hoisting a
     * sibling {@code var} into {@code varScope}.
     */
    private void collectAnnexBCandidates(ScopeRecord varScope, ScopeRecord scope) {
        for (ScopeRecord child : scope.children()) {
            if (child.type().isVarScope()) {
                // Nested function/program; not our concern at this level.
                continue;
            }
            // Skip ClassField / ClassStaticInit / ClassDeclaration — class
            // bodies are always strict, so Annex B doesn't apply inside.
            if (child.type() == ScopeType.ClassField
                || child.type() == ScopeType.ClassStaticInit
                || child.type() == ScopeType.ClassDeclaration) {
                continue;
            }
            for (ScopeVariable v : child.variables().values()) {
                if (v.kind() != BindingKind.Function) continue;
                if (!(v.declarationNode() instanceof FunctionDeclaration fd)) continue;
                if (!isAnnexBHoistable(varScope, child, v)) continue;
                ScopeVariable existing = varScope.variables().get(v.name());
                if (existing != null) {
                    // A var/parameter/function with the same name already lives in
                    // the var scope — just mark it Annex-B-tagged so the Generator
                    // knows to update it on declaration. We do NOT replace its kind.
                    if (existing.kind() == BindingKind.Var
                        || existing.kind() == BindingKind.Function
                        || existing.kind() == BindingKind.Parameter) {
                        existing.markAnnexBHoisted();
                        annexBBindings.put(fd, existing);
                    }
                } else {
                    ScopeVariable synthesized = varScope.addDeclaration(
                        v.name(), BindingKind.Var, fd);
                    synthesized.markAnnexBHoisted();
                    annexBBindings.put(fd, synthesized);
                }
            }
            collectAnnexBCandidates(varScope, child);
        }
    }

    /**
     * Annex-B disqualifying conditions (simplified from § B.3.2.4):
     * <ul>
     *   <li>Any lexical binding (let/const/class) for the same name on the
     *       path from the FD's block up to (but not including) the var
     *       scope blocks hoisting — the implicit var would shadow a TDZ
     *       binding mid-execution.</li>
     *   <li>A catch parameter with the same name blocks hoisting (the FD
     *       lives in the catch's body and would clash on initialization).</li>
     * </ul>
     */
    private boolean isAnnexBHoistable(ScopeRecord varScope, ScopeRecord fdScope, ScopeVariable fdBinding) {
        ScopeRecord s = fdScope;
        while (s != null && s != varScope) {
            ScopeVariable conflict = s.variables().get(fdBinding.name());
            // The FD's own binding in fdScope doesn't count as a conflict.
            if (conflict != null && conflict != fdBinding) {
                if (conflict.kind() == BindingKind.Let
                    || conflict.kind() == BindingKind.Const
                    || conflict.kind() == BindingKind.Class
                    || conflict.kind() == BindingKind.Catch) {
                    return false;
                }
            }
            s = s.parent();
        }
        return true;
    }

    @SuppressWarnings("unused")
    private void resolveIdentifier(Identifier id, ScopeRecord refScope) {
        // Phase 1 doesn't collect references yet. When it does, walk
        // refScope -> parent -> ... looking for a matching ScopeVariable.
        // Stop at the var scope for the Identifier's resolution unless
        // captured by an inner function. For now, callers shouldn't be
        // invoking this — throw if they do, to surface a missing wiring.
        throw new UnsupportedOperationException(
            "ScopeAnalyzer.resolveIdentifier: reference resolution not yet implemented. "
            + "Phase 1 (ScopeCollector) does not collect identifier references, "
            + "so the resolution map is intentionally empty. Wire reference collection "
            + "before consuming ScopeAnalysis.resolution(...).");
    }

    @SuppressWarnings("unused")
    private static Node unused(Node n) { return n; }
}
