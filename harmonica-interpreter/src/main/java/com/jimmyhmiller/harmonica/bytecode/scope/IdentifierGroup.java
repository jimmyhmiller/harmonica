package com.jimmyhmiller.harmonica.bytecode.scope;

import com.jimmyhmiller.harmonica.ast.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * All identifier references with the same name inside one
 * {@link ScopeRecord}, grouped so resolution can be done once per
 * (scope, name) rather than once per reference.
 */
public final class IdentifierGroup {
    private final String name;
    private final List<Identifier> references = new ArrayList<>();

    public IdentifierGroup(String name) {
        this.name = name;
    }

    public String name() { return name; }
    public List<Identifier> references() { return references; }

    void add(Identifier ref) { references.add(ref); }
}
