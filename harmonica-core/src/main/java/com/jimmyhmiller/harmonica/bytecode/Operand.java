package com.jimmyhmiller.harmonica.bytecode;

/**
 * An operand of an instruction. Sealed hierarchy:
 *
 *   Operand
 *     ├── Constant            (read-only — pool index)
 *     └── Variable            (writable)
 *           ├── Register      (per-frame register file slot)
 *           ├── Local         (named source-level binding slot)
 *           └── Argument      (positional call-frame argument slot)
 *
 * Behavior lives on the operand: {@link #retrieve} materializes the current
 * value in a given interpreter context. {@link Variable#store} writes back.
 */
public sealed interface Operand permits Operand.Constant, Operand.This, Variable {

    Object retrieve(InterpContext ctx);

    /**
     * A read-only literal — number, string, boolean, undefined, null, etc.
     *
     * <p>Carries the value <i>directly</i> ({@link #value}) for runtime use,
     * matching JRuby's {@code Fixnum} / {@code Float} / {@code Boolean} operand
     * shape. {@link #index} is the parallel slot in the {@link Executable}'s
     * constants pool, kept for byte-perfect dump output (LibJS oracle compares
     * against this index).
     *
     * <p>Profile (lodash) showed every {@code retrieve} doing
     * {@code ctx.executable().constants()[index]} — three indirections (final
     * field load, getter, array load with bounds check). The JIT inlines all
     * three, but the architecturally-cleaner JRuby shape skips them entirely.
     */
    record Constant(int index, Object value) implements Operand {
        @Override
        public Object retrieve(InterpContext ctx) {
            return value;
        }
    }

    /**
     * Special operand referencing the current function's resolved {@code this}
     * binding. Rendered as {@code this} in dumps; reads from the THIS_VALUE
     * register at runtime. Used together with {@link Op.ResolveThisBinding}
     * to match LibJS's bytecode shape for {@code this.foo} accesses.
     */
    record This() implements Operand {
        public static final This INSTANCE = new This();
        @Override
        public Object retrieve(InterpContext ctx) {
            return ctx.registers()[Variable.Register.THIS_VALUE_INDEX];
        }
    }
}
