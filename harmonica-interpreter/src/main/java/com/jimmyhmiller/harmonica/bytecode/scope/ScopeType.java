package com.jimmyhmiller.harmonica.bytecode.scope;

/**
 * Categorizes a {@link ScopeRecord} by the source construct it represents.
 * Mirrors LibJS's {@code ScopeType} enum
 * ({@code ladybird/Libraries/LibJS/Rust/src/scope_collector.rs}).
 *
 * <p>Important distinctions:
 * <ul>
 *   <li>{@link #Program} / {@link #Function} are <em>var-capturing</em>:
 *       {@code var x} hoists to the nearest scope of one of these kinds.</li>
 *   <li>{@link #Block}, {@link #ForLoop}, {@link #With}, {@link #Catch},
 *       {@link #ClassStaticInit}, {@link #ClassField}, and
 *       {@link #ClassDeclaration} are <em>lexical-only</em>: they hold
 *       {@code let}/{@code const} but pass {@code var} upward.</li>
 *   <li>{@link #With} additionally pollutes name resolution: a reference
 *       inside it may resolve to a property of the with-object at runtime,
 *       so resolution within or through a with-scope is conservative.</li>
 * </ul>
 */
public enum ScopeType {
    Program,
    Function,
    Block,
    ForLoop,
    With,
    Catch,
    ClassStaticInit,
    ClassField,
    ClassDeclaration;

    /** True for scopes that absorb {@code var} declarations (Program / Function). */
    public boolean isVarScope() {
        return this == Program || this == Function;
    }
}
