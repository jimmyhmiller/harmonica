package com.jimmyhmiller.harmonica.bytecode.scope;

/**
 * How a name was introduced into a scope. Determines hoisting target,
 * mutability (const), TDZ behavior, and Annex-B eligibility.
 */
public enum BindingKind {
    /** {@code var x} — hoists to the nearest var scope. */
    Var,
    /** {@code let x} — block-scoped, TDZ until init. */
    Let,
    /** {@code const x} — block-scoped, TDZ, immutable. */
    Const,
    /** {@code function f(){}} — hoists to nearest var scope; in blocks may
     *  also create a duplicate {@link #Var} binding under Annex B. */
    Function,
    /** Function/method parameter. */
    Parameter,
    /** {@code catch (e)} binding. */
    Catch,
    /** {@code class C} — lexical, TDZ. */
    Class,
    /** {@code import x from ...} — module-scope, immutable. */
    Import;

    /** True for bindings that participate in TDZ (let/const/class). */
    public boolean isTdz() {
        return this == Let || this == Const || this == Class;
    }

    /** True for the lexically-scoped binding kinds (let/const/class/catch). */
    public boolean isLexical() {
        return this == Let || this == Const || this == Class || this == Catch;
    }
}
