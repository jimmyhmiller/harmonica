package com.jimmyhmiller.harmonica.bytecode;

/**
 * Writable operand. Sub-interface of {@link Operand} so the type system
 * rejects "store into a Constant" at compile time.
 */
public sealed interface Variable extends Operand permits Variable.Register, Variable.Local, Variable.Argument {

    void store(InterpContext ctx, Object value);

    /**
     * Virtual register in the per-frame register file. The first five indices
     * are reserved (see {@link Register#ACCUMULATOR_INDEX} etc.); user-allocated
     * registers begin at index 5.
     */
    record Register(int index) implements Variable {
        public static final int ACCUMULATOR_INDEX = 0;
        public static final int EXCEPTION_INDEX = 1;
        public static final int THIS_VALUE_INDEX = 2;
        public static final int RETURN_VALUE_INDEX = 3;
        public static final int SAVED_LEXICAL_ENVIRONMENT_INDEX = 4;
        public static final int FIRST_USER_INDEX = 5;

        // Singletons for the reserved registers — interned to avoid per-instruction allocation.
        public static final Register ACCUMULATOR = new Register(ACCUMULATOR_INDEX);
        public static final Register EXCEPTION = new Register(EXCEPTION_INDEX);
        public static final Register THIS_VALUE = new Register(THIS_VALUE_INDEX);
        public static final Register RETURN_VALUE = new Register(RETURN_VALUE_INDEX);
        public static final Register SAVED_LEXICAL_ENVIRONMENT = new Register(SAVED_LEXICAL_ENVIRONMENT_INDEX);

        @Override
        public Object retrieve(InterpContext ctx) {
            return ctx.registers()[index];
        }

        @Override
        public void store(InterpContext ctx, Object value) {
            ctx.registers()[index] = value;
        }
    }

    /**
     * Source-level local binding (var/let/const). Each slot in
     * {@link InterpContext#locals()} holds a {@link Cell} (mutable holder),
     * so closures over this local share the same cell reference with the
     * enclosing function.
     */
    record Local(int slot) implements Variable {
        @Override
        public Object retrieve(InterpContext ctx) {
            Object o = ctx.locals()[slot];
            return o == null ? Undefined.VALUE : ((Cell) o).value;
        }

        @Override
        public void store(InterpContext ctx, Object value) {
            Object o = ctx.locals()[slot];
            if (o == null) {
                ctx.locals()[slot] = new Cell(value);
                return;
            }
            ((Cell) o).value = value;
        }
    }

    /**
     * Positional argument of the current call frame. Writable because JS
     * permits assignment to formal parameters.
     */
    record Argument(int position) implements Variable {
        @Override
        public Object retrieve(InterpContext ctx) {
            // Out-of-range arguments read as undefined per ECMAScript.
            Object[] args = ctx.args();
            return position < args.length ? args[position] : Undefined.VALUE;
        }

        @Override
        public void store(InterpContext ctx, Object value) {
            // Caller is responsible for pre-sizing args[] to formal arity.
            // Out-of-range writes are a generator bug; throw loudly.
            Object[] args = ctx.args();
            if (position >= args.length) {
                throw new IllegalStateException(
                    "Argument.store: position " + position + " out of range (args.length=" + args.length + ")");
            }
            args[position] = value;
        }
    }
}
