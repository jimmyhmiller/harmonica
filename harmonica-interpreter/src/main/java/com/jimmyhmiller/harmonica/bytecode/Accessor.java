package com.jimmyhmiller.harmonica.bytecode;

/**
 * An accessor property descriptor — a {@code (getter, setter)} pair stored in
 * an object's property slot in place of a value. Either component may be
 * {@code null}.
 *
 * <p>The interpreter recognizes {@code Accessor} on read/write of a property
 * and dispatches to the relevant function with {@code this} bound to the
 * receiver.
 */
public final class Accessor {
    private final JSFunction getter;
    private final JSFunction setter;

    public Accessor(JSFunction getter, JSFunction setter) {
        this.getter = getter;
        this.setter = setter;
    }

    public JSFunction getter() { return getter; }
    public JSFunction setter() { return setter; }

    /** Combine with another accessor — the other's non-null components win. */
    public Accessor merge(Accessor other) {
        return new Accessor(
            other.getter != null ? other.getter : this.getter,
            other.setter != null ? other.setter : this.setter);
    }
}
