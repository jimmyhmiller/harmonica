package com.jimmyhmiller.harmonica.bytecode.scope;

/**
 * Result of resolving one identifier reference to a declaration.
 *
 * <p>{@link Kind#Global} means the name was not found in any enclosing
 * scope and must be looked up on the global object at runtime.
 * {@link Kind#Unresolvable} means the analyzer could not commit to a
 * binding because something (e.g. a {@code with} object or direct
 * {@code eval}) on the path could shadow it at runtime — the Generator
 * must fall back to dynamic name lookup.
 */
public record IdentifierResolution(
    Kind kind,
    ScopeRecord declaringScope,
    ScopeVariable variable
) {
    public enum Kind { Local, Captured, Global, Unresolvable }

    public static final IdentifierResolution GLOBAL =
        new IdentifierResolution(Kind.Global, null, null);

    public static final IdentifierResolution UNRESOLVABLE =
        new IdentifierResolution(Kind.Unresolvable, null, null);

    public static IdentifierResolution local(ScopeRecord scope, ScopeVariable variable) {
        return new IdentifierResolution(Kind.Local, scope, variable);
    }

    public static IdentifierResolution captured(ScopeRecord scope, ScopeVariable variable) {
        return new IdentifierResolution(Kind.Captured, scope, variable);
    }
}
