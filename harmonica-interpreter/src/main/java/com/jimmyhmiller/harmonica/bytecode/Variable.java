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

        @Override
        public double retrieveDouble(InterpContext ctx) {
            // Skip the AbstractOps.toNumber dispatch when the slot already
            // holds a Number (the dominant case in arithmetic chains).
            Object v = ctx.registers()[index];
            if (v instanceof Number n) return n.doubleValue();
            return AbstractOps.toNumber(v);
        }

        @Override
        public boolean retrieveBoolean(InterpContext ctx) {
            Object v = ctx.registers()[index];
            if (v instanceof Boolean b) return b;
            return AbstractOps.toBoolean(v);
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
            // Slots are stored unboxed by default — the {@link Cell} wrapper
            // is only allocated when an inner closure actually captures the
            // slot (see {@link InterpContext#cellAt(int)}). This eliminates
            // the per-call Cell-per-local allocation that lodash hot loops
            // were paying for slots that never got captured.
            Object o = ctx.locals()[slot];
            if (o == null) return Undefined.VALUE;
            if (o instanceof Cell c) {
                if (c.value == InterpContext.TDZ) throw tdzError(ctx);
                return c.value;
            }
            if (o == InterpContext.TDZ) throw tdzError(ctx);
            return o;
        }

        @Override
        public void store(InterpContext ctx, Object value) {
            Object o = ctx.locals()[slot];
            if (o instanceof Cell c) { c.value = value; return; }
            ctx.locals()[slot] = value;
        }

        private AbruptCompletion tdzError(InterpContext ctx) {
            String name = "";
            if (ctx.executable() != null && ctx.executable().localNames() != null) {
                String[] names = ctx.executable().localNames();
                if (slot < names.length && names[slot] != null) name = names[slot];
            }
            return AbruptCompletion.referenceError(
                "Cannot access '" + name + "' before initialization");
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
