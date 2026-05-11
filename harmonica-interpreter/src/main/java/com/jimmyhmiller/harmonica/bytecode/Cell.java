package com.jimmyhmiller.harmonica.bytecode;

/**
 * A mutable holder for a single value. Used to back function-scope locals so
 * that closures can share state with their enclosing function: when an outer
 * local is captured by a nested function, both scopes hold the same
 * {@code Cell} reference, and writes through either alias are visible to the
 * other.
 *
 * <p>Plain {@code Object[]} slots wouldn't allow this: assigning into the
 * outer's slot wouldn't update the inner's slot. Cells make the indirection
 * explicit.
 */
public final class Cell {
    public Object value;

    public Cell(Object value) { this.value = value; }
}
