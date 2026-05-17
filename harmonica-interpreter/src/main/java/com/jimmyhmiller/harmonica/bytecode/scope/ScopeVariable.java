package com.jimmyhmiller.harmonica.bytecode.scope;

import com.jimmyhmiller.harmonica.ast.Node;

/**
 * A single declaration inside a {@link ScopeRecord}. Mutable because
 * Annex-B function hoisting flips {@link #annexBHoisted} after the
 * scope tree is built but before resolution runs.
 */
public final class ScopeVariable {
    private final String name;
    private final BindingKind kind;
    private final Node declarationNode;
    private boolean annexBHoisted;

    public ScopeVariable(String name, BindingKind kind, Node declarationNode) {
        this.name = name;
        this.kind = kind;
        this.declarationNode = declarationNode;
    }

    public String name() { return name; }
    public BindingKind kind() { return kind; }
    public Node declarationNode() { return declarationNode; }

    /**
     * True iff this binding was synthesized by Annex-B function hoisting
     * (the {@code var}-style duplicate of a block-scoped {@code function}
     * declaration). The Generator emits a mid-execution {@code InitVarFromBlock}
     * at the original declaration site for these.
     */
    public boolean isAnnexBHoisted() { return annexBHoisted; }
    void markAnnexBHoisted() { this.annexBHoisted = true; }

    @Override
    public String toString() {
        return "ScopeVariable[" + name + " : " + kind
            + (annexBHoisted ? " (annexB)" : "") + "]";
    }
}
