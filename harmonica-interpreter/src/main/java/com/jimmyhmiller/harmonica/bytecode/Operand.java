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
public sealed interface Operand
    permits Operand.Constant, Operand.This,
            Operand.DoubleLit, Operand.BoolLit, Operand.StringLit,
            Operand.UndefinedLit, Operand.NullLit,
            Variable {

    Object retrieve(InterpContext ctx);

    /**
     * Read this operand and coerce to a primitive double. Subclasses with a
     * known numeric value (currently {@link DoubleLit}) override to skip the
     * intermediate {@link Double} box. Hot arithmetic / comparison ops can
     * call this in preference to {@code AbstractOps.toNumber(op.retrieve(ctx))}.
     */
    default double retrieveDouble(InterpContext ctx) {
        return AbstractOps.toNumber(retrieve(ctx));
    }

    /**
     * Read this operand and coerce to a primitive boolean. Subclasses with a
     * known boolean value override.
     */
    default boolean retrieveBoolean(InterpContext ctx) {
        return AbstractOps.toBoolean(retrieve(ctx));
    }

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

        @Override
        public double retrieveDouble(InterpContext ctx) {
            // Constants flowing into arithmetic / comparison ops can skip the
            // box round-trip when we already know the runtime type.
            if (value instanceof Number n) return n.doubleValue();
            return AbstractOps.toNumber(value);
        }

        @Override
        public boolean retrieveBoolean(InterpContext ctx) {
            if (value instanceof Boolean b) return b;
            return AbstractOps.toBoolean(value);
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

    /**
     * Numeric literal that carries the value as a primitive {@code double}.
     * {@link #retrieve} still has to box (to satisfy the {@code Object}
     * return type) so that's no win on its own — the win comes when callers
     * use {@link #retrieveDouble} to skip the box entirely. Mirrors JRuby's
     * {@code org.jruby.ir.operands.Float} operand.
     */
    record DoubleLit(double value) implements Operand {
        @Override public Object retrieve(InterpContext ctx) { return value; }
        @Override public double retrieveDouble(InterpContext ctx) { return value; }
        @Override public boolean retrieveBoolean(InterpContext ctx) {
            return value != 0.0 && !Double.isNaN(value);
        }
    }

    /**
     * Boolean literal. Mirrors JRuby's {@code Boolean} operand. retrieve
     * returns the cached {@code Boolean.TRUE}/{@code Boolean.FALSE} so the
     * cost of producing a non-primitive is at most a field load.
     */
    record BoolLit(boolean value) implements Operand {
        public static final BoolLit TRUE = new BoolLit(true);
        public static final BoolLit FALSE = new BoolLit(false);
        @Override public Object retrieve(InterpContext ctx) {
            return value ? Boolean.TRUE : Boolean.FALSE;
        }
        @Override public boolean retrieveBoolean(InterpContext ctx) { return value; }
        @Override public double retrieveDouble(InterpContext ctx) {
            return value ? 1.0 : 0.0;
        }
    }

    /** String literal. Mirrors JRuby's {@code StringLiteral} operand. */
    record StringLit(String value) implements Operand {
        @Override public Object retrieve(InterpContext ctx) { return value; }
        @Override public boolean retrieveBoolean(InterpContext ctx) {
            return !value.isEmpty();
        }
    }

    /** {@code undefined} literal. Singleton; mirrors JRuby's {@code UndefinedValue}. */
    record UndefinedLit() implements Operand {
        public static final UndefinedLit INSTANCE = new UndefinedLit();
        @Override public Object retrieve(InterpContext ctx) { return Undefined.VALUE; }
        @Override public boolean retrieveBoolean(InterpContext ctx) { return false; }
        @Override public double retrieveDouble(InterpContext ctx) { return Double.NaN; }
    }

    /** {@code null} literal. Singleton; mirrors JRuby's {@code Nil}. */
    record NullLit() implements Operand {
        public static final NullLit INSTANCE = new NullLit();
        @Override public Object retrieve(InterpContext ctx) { return null; }
        @Override public boolean retrieveBoolean(InterpContext ctx) { return false; }
        @Override public double retrieveDouble(InterpContext ctx) { return 0.0; }
    }
}
