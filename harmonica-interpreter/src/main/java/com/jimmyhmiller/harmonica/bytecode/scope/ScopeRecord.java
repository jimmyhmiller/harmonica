package com.jimmyhmiller.harmonica.bytecode.scope;

import com.jimmyhmiller.harmonica.ast.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A single scope (Program, Function body, Block, etc.) discovered during
 * {@link ScopeCollector Phase 1} and refined by {@link ScopeAnalyzer Phase 2}.
 *
 * <p>Mutable on purpose: child scopes append themselves to {@link #children}
 * during construction, and the analyzer toggles {@link #evalPoisoned} after
 * the tree is built.
 *
 * <p>Field-for-field analogue of LibJS's {@code ScopeRecord}.
 */
public final class ScopeRecord {
    private final ScopeType type;
    private final ScopeRecord parent;
    private final Node astNode;
    private final boolean strict;

    private final List<ScopeRecord> children = new ArrayList<>();

    /** Declarations introduced directly in this scope, in source order. */
    private final LinkedHashMap<String, ScopeVariable> variables = new LinkedHashMap<>();

    /** Identifier references that appear directly in this scope (not in
     *  child scopes), grouped by name. */
    private final LinkedHashMap<String, IdentifierGroup> identifierRefs = new LinkedHashMap<>();

    /** True if this scope contains a direct call to {@code eval(...)}. */
    private boolean hasDirectEval;

    /** True if {@link #hasDirectEval} is set on this scope OR any descendant.
     *  Propagated by {@link ScopeAnalyzer}. */
    private boolean evalPoisoned;

    public ScopeRecord(ScopeType type, ScopeRecord parent, Node astNode, boolean strict) {
        this.type = type;
        this.parent = parent;
        this.astNode = astNode;
        this.strict = strict;
        if (parent != null) parent.children.add(this);
    }

    public ScopeType type() { return type; }
    public ScopeRecord parent() { return parent; }
    public Node astNode() { return astNode; }
    public boolean strict() { return strict; }
    public List<ScopeRecord> children() { return children; }
    public LinkedHashMap<String, ScopeVariable> variables() { return variables; }
    public LinkedHashMap<String, IdentifierGroup> identifierRefs() { return identifierRefs; }
    public boolean hasDirectEval() { return hasDirectEval; }
    public boolean evalPoisoned() { return evalPoisoned; }

    void setHasDirectEval(boolean v) { this.hasDirectEval = v; }
    void setEvalPoisoned(boolean v) { this.evalPoisoned = v; }

    /**
     * Walk up to the nearest enclosing var scope (Program or Function).
     * Used as the hoisting target for {@code var} and for Annex-B
     * function-in-block synthesis. Returns this if this scope is itself
     * a var scope. Never null on a well-formed tree (Program is always
     * the root and is a var scope).
     */
    public ScopeRecord enclosingVarScope() {
        ScopeRecord s = this;
        while (s != null && !s.type.isVarScope()) s = s.parent;
        if (s == null) {
            throw new IllegalStateException(
                "Scope tree missing var-scope ancestor for " + type
                + " — Program scope was not the root");
        }
        return s;
    }

    /**
     * Add a declaration to this scope. Duplicate names with compatible
     * kinds (var+var, function+var, var+function) are coalesced silently
     * — the earlier wins. Conflicting kinds (let/const/class redeclaration)
     * are NOT a syntax error here; the parser is expected to enforce that.
     * This pass focuses on building structure, not diagnostics.
     *
     * @return the resulting {@link ScopeVariable} (either newly added or
     *         the pre-existing one that absorbed this declaration).
     */
    ScopeVariable addDeclaration(String name, BindingKind kind, Node declarationNode) {
        ScopeVariable existing = variables.get(name);
        if (existing != null) return existing;
        ScopeVariable v = new ScopeVariable(name, kind, declarationNode);
        variables.put(name, v);
        return v;
    }

    /** Record an identifier reference; groups by name. */
    void addIdentifierRef(com.jimmyhmiller.harmonica.ast.Identifier id) {
        identifierRefs
            .computeIfAbsent(id.name(), IdentifierGroup::new)
            .add(id);
    }
}
