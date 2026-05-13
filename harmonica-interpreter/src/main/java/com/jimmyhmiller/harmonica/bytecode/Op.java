package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.bytecode.cache.CallSite;
import com.jimmyhmiller.harmonica.bytecode.cache.EnvironmentCoordinate;
import com.jimmyhmiller.harmonica.bytecode.cache.GlobalVariableCache;
import com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache;

/**
 * Sealed root of the instruction hierarchy. Every concrete opcode is a record
 * (or a final class with mutable IC state) that implements {@code Op}.
 *
 * <p>Dispatch is virtual: the interpreter calls
 * {@link #interpret(InterpContext, int)} which returns the next pc. Each
 * opcode's semantics live next to its operand wiring on the same class, in
 * the spirit of JRuby's IR.
 *
 * <p>Most instructions are pure data and are records. Instructions carrying
 * an inline cache (property access, calls, etc.) are still records: the
 * cache reference is {@code final}, the cache object's internal state is
 * mutable. Identity of the instruction (operand wiring) doesn't change; only
 * runtime-observed fields evolve.
 */
public sealed interface Op {

    /** The dispatch tag — used by analyses, the disassembler, and oracle dumps. */
    Operation operation();

    /**
     * Execute one step of this op against {@code ctx}. Returns the pc of the
     * next instruction. Sequential ops return {@code pc + 1}; jumps return
     * their target. {@link #FRAME_DONE} signals frame exit (the value lives
     * in the {@link Variable.Register#RETURN_VALUE} register).
     */
    int interpret(InterpContext ctx, int pc);

    /** Sentinel pc returned by {@link Return} / {@link End} to exit the frame. */
    int FRAME_DONE = -1;

    /**
     * Sentinel pc returned by {@link Yield} to suspend the frame. The
     * {@link Interpreter#interpretSuspendable} entry point treats this as
     * "pause here, the caller (a {@code GeneratorObject}'s {@code next})
     * will pick up the yielded value via {@link InterpContext#yieldedValue}".
     * The pc to resume at is saved on the InterpContext.
     */
    int YIELD_DONE = -2;

    default boolean isTerminator() { return operation().isTerminator(); }
    default boolean canThrow()     { return operation().canThrow(); }

    // ============================================================
    //  Arithmetic
    // ============================================================

    record Add(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.ADD; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.add(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record Sub(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.SUB; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.sub(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record Mul(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.MUL; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.mul(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record Div(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.DIV; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.div(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record Mod(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.MOD; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.mod(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record Exp(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.EXP; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.exp(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record BitwiseAnd(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.BITWISE_AND; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.bitwiseAnd(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record BitwiseOr(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.BITWISE_OR; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.bitwiseOr(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record BitwiseXor(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.BITWISE_XOR; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.bitwiseXor(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record LeftShift(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.LEFT_SHIFT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.leftShift(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record RightShift(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.RIGHT_SHIFT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.rightShift(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record UnsignedRightShift(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.UNSIGNED_RIGHT_SHIFT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.unsignedRightShift(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    // ============================================================
    //  Unary
    // ============================================================

    record UnaryMinus(Variable dst, Operand src) implements Op {
        @Override public Operation operation() { return Operation.UNARY_MINUS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.unaryMinus(src.retrieve(ctx)));
            return pc + 1;
        }
    }

    record UnaryPlus(Variable dst, Operand src) implements Op {
        @Override public Operation operation() { return Operation.UNARY_PLUS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.unaryPlus(src.retrieve(ctx)));
            return pc + 1;
        }
    }

    record BitwiseNot(Variable dst, Operand src) implements Op {
        @Override public Operation operation() { return Operation.BITWISE_NOT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.bitwiseNot(src.retrieve(ctx)));
            return pc + 1;
        }
    }

    record Not(Variable dst, Operand src) implements Op {
        @Override public Operation operation() { return Operation.NOT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.not(src.retrieve(ctx)));
            return pc + 1;
        }
    }

    /** Coerce {@code value} to a JS boolean — emitted by `!!x` peephole. */
    record ToBoolean(Variable dst, Operand value) implements Op {
        @Override public Operation operation() { return Operation.TO_BOOLEAN; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.toBoolean(value.retrieve(ctx)));
            return pc + 1;
        }
    }

    record Typeof(Variable dst, Operand src) implements Op {
        @Override public Operation operation() { return Operation.TYPEOF; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.typeofValue(src.retrieve(ctx)));
            return pc + 1;
        }
    }

    /**
     * Typeof on a binding name: {@code dst = typeof <name>}. Distinct from
     * {@code GetGlobal name; Typeof dst, src} because typeof on an unbound
     * identifier yields {@code "undefined"} rather than throwing
     * {@link ReferenceError}. Used when the typeof's operand is a plain
     * identifier (binding lookup); LibJS emits this directly.
     */
    record TypeofBinding(Variable dst, String name) implements Op {
        @Override public Operation operation() { return Operation.TYPEOF_BINDING; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object value = ctx.globals().containsKey(name)
                ? ctx.globals().get(name)
                : Undefined.VALUE;
            dst.store(ctx, AbstractOps.typeofValue(value));
            return pc + 1;
        }
    }

    /** Delete a named property: {@code dst = delete base.property}. */
    record DeleteById(Variable dst, Operand base, String property) implements Op {
        @Override public Operation operation() { return Operation.DELETE_BY_ID; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object b = base.retrieve(ctx);
            if (b == null || b == Undefined.VALUE) {
                throw AbruptCompletion.typeError(
                    "Cannot convert " + (b == null ? "null" : "undefined") + " to object");
            }
            Object result = Boolean.TRUE;
            if (b instanceof JSObject jo) {
                result = jo.delete(property);
                if (result == Boolean.FALSE && ctx.executable() != null
                    && ctx.executable().strictMode()) {
                    // ECMA-262 § 13.5.1.2 step 5.b: strict-mode delete on a
                    // non-configurable own property throws TypeError.
                    throw AbruptCompletion.typeError(
                        "Cannot delete property '" + property + "' of " + b);
                }
            } else if (b instanceof JSFunction jf) {
                // ECMA-262 § 10.2.10: the virtual {@code name} / {@code
                // length} properties are configurable, so {@code delete}
                // removes them. Flip the deleted flag; subsequent reads,
                // {@code hasOwnProperty}, and {@code in} now treat the
                // property as absent.
                if ("name".equals(property)) {
                    jf.markNameDeleted();
                    result = Boolean.TRUE;
                } else if ("length".equals(property)) {
                    jf.markLengthDeleted();
                    result = Boolean.TRUE;
                } else if (jf.hasOwnStatic(property)) {
                    if (jf.isConfigurable(property)) {
                        jf.properties().remove(property);
                        result = Boolean.TRUE;
                    } else {
                        result = Boolean.FALSE;
                        if (ctx.executable() != null && ctx.executable().strictMode()) {
                            throw AbruptCompletion.typeError(
                                "Cannot delete property '" + property + "' of " + b);
                        }
                    }
                }
            } else if (b instanceof JSArray arr) {
                // ECMA-262 § 23.1.4.1: Array's {@code length} is
                // non-configurable, so {@code delete a.length} returns
                // false (or throws in strict mode). Indexed slots are
                // configurable (set to a hole, but we just remove via
                // {@code extraProperties} since our JSArray doesn't model
                // holes); arbitrary non-index props live in
                // {@code extraProperties}.
                if ("length".equals(property)) {
                    result = Boolean.FALSE;
                    if (ctx.executable() != null && ctx.executable().strictMode()) {
                        throw AbruptCompletion.typeError(
                            "Cannot delete property 'length' of " + b);
                    }
                } else if (arr.hasExtraProperty(property)) {
                    arr.extraProperties().remove(property);
                    result = Boolean.TRUE;
                }
            }
            dst.store(ctx, result);
            return pc + 1;
        }
    }

    /** Delete a computed property: {@code dst = delete base[property]}. */
    record DeleteByValue(Variable dst, Operand base, Operand property) implements Op {
        @Override public Operation operation() { return Operation.DELETE_BY_VALUE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object b = base.retrieve(ctx);
            // ECMA-262 § 13.5.1.2 step 5.b: ToObject(ref.[[Base]]) on null /
            // undefined throws TypeError.
            if (b == null || b == Undefined.VALUE) {
                throw AbruptCompletion.typeError(
                    "Cannot convert " + (b == null ? "null" : "undefined") + " to object");
            }
            Object key = property.retrieve(ctx);
            String prop = key instanceof String s ? s
                : key instanceof JSSymbol sym ? sym.asPropertyKey()
                : AbstractOps.toString(key);
            Object result = Boolean.TRUE;
            if (b instanceof JSObject jo) {
                result = jo.delete(prop);
                if (result == Boolean.FALSE && ctx.executable() != null
                    && ctx.executable().strictMode()) {
                    throw AbruptCompletion.typeError(
                        "Cannot delete property '" + prop + "' of " + b);
                }
            } else if (b instanceof JSFunction jf) {
                if ("name".equals(prop)) {
                    jf.markNameDeleted();
                    result = Boolean.TRUE;
                } else if ("length".equals(prop)) {
                    jf.markLengthDeleted();
                    result = Boolean.TRUE;
                } else if (jf.hasOwnStatic(prop)) {
                    if (jf.isConfigurable(prop)) {
                        jf.properties().remove(prop);
                        result = Boolean.TRUE;
                    } else {
                        result = Boolean.FALSE;
                        if (ctx.executable() != null && ctx.executable().strictMode()) {
                            throw AbruptCompletion.typeError(
                                "Cannot delete property '" + prop + "' of " + b);
                        }
                    }
                }
            } else if (b instanceof JSArray arr) {
                if ("length".equals(prop)) {
                    result = Boolean.FALSE;
                    if (ctx.executable() != null && ctx.executable().strictMode()) {
                        throw AbruptCompletion.typeError(
                            "Cannot delete property 'length' of " + b);
                    }
                } else {
                    int idx = -1;
                    try { idx = Integer.parseInt(prop); } catch (NumberFormatException ignored) {}
                    if (idx >= 0 && idx < arr.length()) {
                        // Spec: set the slot to a hole. Our JSArray doesn't
                        // model holes, so substitute {@code undefined} —
                        // observably equivalent for {@code arr[i]} reads.
                        arr.set(idx, Undefined.VALUE);
                        result = Boolean.TRUE;
                    } else if (arr.hasExtraProperty(prop)) {
                        arr.extraProperties().remove(prop);
                        result = Boolean.TRUE;
                    }
                }
            }
            dst.store(ctx, result);
            return pc + 1;
        }
    }

    /**
     * Delete a binding by name: {@code dst = delete name}. Matches LibJS's
     * dedicated opcode for `delete <identifier>`. Returns true if the binding
     * was deleted (or the binding doesn't exist), false if it's a non-
     * configurable binding (per spec semantics).
     */
    record DeleteVariable(Variable dst, String identifier) implements Op {
        @Override public Operation operation() { return Operation.DELETE_VARIABLE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // v1: globals aren't yet a real JSObject so unbound names always
            // succeed. Bound globals would also be deletable in non-strict.
            dst.store(ctx, Boolean.TRUE);
            return pc + 1;
        }
    }

    record Increment(Variable dst) implements Op {
        @Override public Operation operation() { return Operation.INCREMENT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.boxDouble(AbstractOps.toNumber(dst.retrieve(ctx)) + 1.0));
            return pc + 1;
        }
    }

    record Decrement(Variable dst) implements Op {
        @Override public Operation operation() { return Operation.DECREMENT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.boxDouble(AbstractOps.toNumber(dst.retrieve(ctx)) - 1.0));
            return pc + 1;
        }
    }

    record PostfixIncrement(Variable dst, Operand src) implements Op {
        @Override public Operation operation() { return Operation.POSTFIX_INCREMENT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // Reuse the source's already-boxed Double for the dst store; box
            // the new value via the small-double cache so ++ in tight loops
            // doesn't allocate.
            Object orig = src.retrieve(ctx);
            double n = AbstractOps.toNumber(orig);
            dst.store(ctx, orig instanceof Double ? orig : AbstractOps.boxDouble(n));
            if (src instanceof Variable v) v.store(ctx, AbstractOps.boxDouble(n + 1.0));
            return pc + 1;
        }
    }

    record PostfixDecrement(Variable dst, Operand src) implements Op {
        @Override public Operation operation() { return Operation.POSTFIX_DECREMENT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object orig = src.retrieve(ctx);
            double n = AbstractOps.toNumber(orig);
            dst.store(ctx, orig instanceof Double ? orig : AbstractOps.boxDouble(n));
            if (src instanceof Variable v) v.store(ctx, AbstractOps.boxDouble(n - 1.0));
            return pc + 1;
        }
    }

    // ============================================================
    //  Comparison
    // ============================================================

    record LessThan(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.LESS_THAN; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // retrieveDouble skips the Double box for numeric registers and
            // typed-literal operands; Java's `<` already handles NaN per
            // ECMA-262 (NaN < x is false in both).
            dst.store(ctx, lhs.retrieveDouble(ctx) < rhs.retrieveDouble(ctx));
            return pc + 1;
        }
    }

    record LessThanEquals(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.LESS_THAN_EQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, lhs.retrieveDouble(ctx) <= rhs.retrieveDouble(ctx));
            return pc + 1;
        }
    }

    record GreaterThan(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.GREATER_THAN; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, lhs.retrieveDouble(ctx) > rhs.retrieveDouble(ctx));
            return pc + 1;
        }
    }

    record GreaterThanEquals(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.GREATER_THAN_EQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, lhs.retrieveDouble(ctx) >= rhs.retrieveDouble(ctx));
            return pc + 1;
        }
    }

    record StrictlyEquals(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.STRICTLY_EQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.strictlyEquals(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record StrictlyInequals(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.STRICTLY_INEQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.strictlyInequals(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record LooselyEquals(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.LOOSELY_EQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.looselyEquals(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record LooselyInequals(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.LOOSELY_INEQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, AbstractOps.looselyInequals(lhs.retrieve(ctx), rhs.retrieve(ctx)));
            return pc + 1;
        }
    }

    record Instanceof(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.INSTANCE_OF; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object left = lhs.retrieve(ctx);
            Object right = rhs.retrieve(ctx);
            if (!(right instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("Right-hand side of instanceof is not callable");
            }
            JSObject proto = fn.prototypeObject();
            if (proto == null || !(left instanceof JSObject leftObj)) {
                dst.store(ctx, false);
                return pc + 1;
            }
            JSObject cursor = leftObj.proto();
            while (cursor != null) {
                if (cursor == proto) { dst.store(ctx, true); return pc + 1; }
                cursor = cursor.proto();
            }
            dst.store(ctx, false);
            return pc + 1;
        }
    }

    record In(Variable dst, Operand lhs, Operand rhs) implements Op {
        @Override public Operation operation() { return Operation.IN; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object left = lhs.retrieve(ctx);
            Object right = rhs.retrieve(ctx);
            // ECMA-262 § 7.1.19 ToPropertyKey: Symbols pass through, everything
            // else stringifies. Bug-source: previously called ToString and
            // tripped the Symbol→String guard.
            String key = left instanceof String s ? s
                       : left instanceof JSSymbol sy ? sy.asPropertyKey()
                       : AbstractOps.toString(left);
            if (right instanceof JSObject jo) { dst.store(ctx, jo.has(key)); return pc + 1; }
            if (right instanceof JSArray arr) {
                if ("length".equals(key)) { dst.store(ctx, true); return pc + 1; }
                int idx = -1;
                try { idx = Integer.parseInt(key); } catch (NumberFormatException ignored) {}
                dst.store(ctx, idx >= 0 && idx < arr.length());
                return pc + 1;
            }
            if (right instanceof JSFunction fn) {
                if ("prototype".equals(key)) { dst.store(ctx, true); return pc + 1; }
                if (fn.hasOwnStatic(key)) { dst.store(ctx, true); return pc + 1; }
                if (Realm.functionPrototype != null && Realm.functionPrototype.has(key)) {
                    dst.store(ctx, true); return pc + 1;
                }
                dst.store(ctx, false); return pc + 1;
            }
            throw AbruptCompletion.typeError("Cannot use 'in' operator to search for '"
                + key + "' in " + AbstractOps.typeofValue(right));
        }
    }

    // ============================================================
    //  Move
    // ============================================================

    record Mov(Variable dst, Operand src) implements Op {
        @Override public Operation operation() { return Operation.MOV; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, src.retrieve(ctx));
            return pc + 1;
        }
    }

    /** Two Movs packed into one instruction. Executed sequentially. */
    record Mov2(Variable dst1, Operand src1, Variable dst2, Operand src2) implements Op {
        @Override public Operation operation() { return Operation.MOV2; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst1.store(ctx, src1.retrieve(ctx));
            dst2.store(ctx, src2.retrieve(ctx));
            return pc + 1;
        }
    }

    /** Three Movs packed into one instruction. Executed sequentially. */
    record Mov3(Variable dst1, Operand src1, Variable dst2, Operand src2,
                Variable dst3, Operand src3) implements Op {
        @Override public Operation operation() { return Operation.MOV3; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst1.store(ctx, src1.retrieve(ctx));
            dst2.store(ctx, src2.retrieve(ctx));
            dst3.store(ctx, src3.retrieve(ctx));
            return pc + 1;
        }
    }

    // ============================================================
    //  Control flow — jumps
    // ============================================================

    record Jump(int targetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP; }
        @Override public int interpret(InterpContext ctx, int pc) { return targetPc; }
    }

    record JumpTrue(Operand condition, int targetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_TRUE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return condition.retrieveBoolean(ctx) ? targetPc : pc + 1;
        }
    }

    record JumpFalse(Operand condition, int targetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_FALSE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return condition.retrieveBoolean(ctx) ? pc + 1 : targetPc;
        }
    }

    record JumpIf(Operand condition, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_IF; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return condition.retrieveBoolean(ctx) ? trueTargetPc : falseTargetPc;
        }
    }

    /**
     * Fused branch: take {@code trueTargetPc} when {@code condition} is
     * nullish (null or undefined), {@code falseTargetPc} otherwise. Emitted
     * by `??` (nullish-coalescing) lowering instead of a multi-op
     * StrictlyEquals + JumpFalse chain.
     */
    record JumpNullish(Operand condition, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_NULLISH; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object v = condition.retrieve(ctx);
            return (v == null || v == Undefined.VALUE) ? trueTargetPc : falseTargetPc;
        }
    }

    /**
     * Fused branch: take {@code trueTargetPc} when {@code condition} is
     * {@code Undefined}, {@code falseTargetPc} otherwise. Emitted for
     * default-value handling in destructuring patterns
     * ({@code [x = 23] = ...} / {@code {x = 23} = ...}).
     */
    record JumpUndefined(Operand condition, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_UNDEFINED; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return condition.retrieve(ctx) == Undefined.VALUE ? trueTargetPc : falseTargetPc;
        }
    }

    /**
     * Fused compare-and-branch. Two-target form: jump to {@code trueTargetPc}
     * if the comparison holds, {@code falseTargetPc} otherwise. The
     * generator's convention is to set {@code trueTargetPc} to the
     * fall-through PC at emit time, so only the false target needs patching.
     */
    record JumpLessThan(Operand lhs, Operand rhs, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_LESS_THAN; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return (Boolean) AbstractOps.lessThan(lhs.retrieve(ctx), rhs.retrieve(ctx))
                ? trueTargetPc : falseTargetPc;
        }
    }
    record JumpLessThanEquals(Operand lhs, Operand rhs, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_LESS_THAN_EQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            double l = AbstractOps.toNumber(lhs.retrieve(ctx));
            double r = AbstractOps.toNumber(rhs.retrieve(ctx));
            return (l <= r) ? trueTargetPc : falseTargetPc;
        }
    }
    record JumpGreaterThan(Operand lhs, Operand rhs, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_GREATER_THAN; }
        @Override public int interpret(InterpContext ctx, int pc) {
            double l = AbstractOps.toNumber(lhs.retrieve(ctx));
            double r = AbstractOps.toNumber(rhs.retrieve(ctx));
            return (l > r) ? trueTargetPc : falseTargetPc;
        }
    }
    record JumpGreaterThanEquals(Operand lhs, Operand rhs, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_GREATER_THAN_EQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            double l = AbstractOps.toNumber(lhs.retrieve(ctx));
            double r = AbstractOps.toNumber(rhs.retrieve(ctx));
            return (l >= r) ? trueTargetPc : falseTargetPc;
        }
    }
    record JumpStrictlyEquals(Operand lhs, Operand rhs, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_STRICTLY_EQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return (Boolean) AbstractOps.strictlyEquals(lhs.retrieve(ctx), rhs.retrieve(ctx))
                ? trueTargetPc : falseTargetPc;
        }
    }
    record JumpStrictlyInequals(Operand lhs, Operand rhs, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_STRICTLY_INEQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return (Boolean) AbstractOps.strictlyEquals(lhs.retrieve(ctx), rhs.retrieve(ctx))
                ? falseTargetPc : trueTargetPc;
        }
    }
    record JumpLooselyEquals(Operand lhs, Operand rhs, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_LOOSELY_EQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return AbstractOps.looselyEquals(lhs.retrieve(ctx), rhs.retrieve(ctx))
                ? trueTargetPc : falseTargetPc;
        }
    }
    record JumpLooselyInequals(Operand lhs, Operand rhs, int trueTargetPc, int falseTargetPc) implements Op {
        @Override public Operation operation() { return Operation.JUMP_LOOSELY_INEQUALS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            return AbstractOps.looselyEquals(lhs.retrieve(ctx), rhs.retrieve(ctx))
                ? falseTargetPc : trueTargetPc;
        }
    }

    // ============================================================
    //  Return / throw / catch
    // ============================================================

    record Return(Operand value) implements Op {
        @Override public Operation operation() { return Operation.RETURN; }
        @Override public int interpret(InterpContext ctx, int pc) {
            ctx.registers()[Variable.Register.RETURN_VALUE_INDEX] = value.retrieve(ctx);
            return FRAME_DONE;
        }
    }

    record End(Operand value) implements Op {
        @Override public Operation operation() { return Operation.END; }
        @Override public int interpret(InterpContext ctx, int pc) {
            ctx.registers()[Variable.Register.RETURN_VALUE_INDEX] = value.retrieve(ctx);
            return FRAME_DONE;
        }
    }

    record Throw(Operand value) implements Op {
        @Override public Operation operation() { return Operation.THROW; }
        @Override public int interpret(InterpContext ctx, int pc) {
            throw new AbruptCompletion(value.retrieve(ctx));
        }
    }

    /**
     * Throw a TypeError if {@code src} is null or undefined. LibJS emits this
     * before destructuring a value into an object/array pattern (the spec
     * requires CoerceToObject which fails on nullish), and as a guard before
     * iterator-based destructuring.
     */
    record ThrowIfNullish(Operand src) implements Op {
        @Override public Operation operation() { return Operation.THROW_IF_NULLISH; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object v = src.retrieve(ctx);
            if (v == null || v == Undefined.VALUE) {
                throw AbruptCompletion.typeError("cannot destructure null or undefined");
            }
            return pc + 1;
        }
    }

    record Catch(Variable dst) implements Op {
        @Override public Operation operation() { return Operation.CATCH; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // The exception value was placed in EXCEPTION when the handler ran.
            dst.store(ctx, ctx.registers()[Variable.Register.EXCEPTION_INDEX]);
            return pc + 1;
        }
    }

    /**
     * Snapshot the current lexical environment into {@code dst}. Emitted by
     * try-statement codegen so the catch handler can restore the environment
     * after an exception unwinds inner scopes.
     */
    record GetLexicalEnvironment(Variable dst) implements Op {
        @Override public Operation operation() { return Operation.GET_LEXICAL_ENVIRONMENT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // v1: shape-only — no real lexical envs yet. Use Undefined as a
            // marker so a paired SetLexicalEnvironment doesn't NPE.
            dst.store(ctx, Undefined.VALUE);
            return pc + 1;
        }
    }

    /**
     * Restore the current lexical environment from {@code environment}. Used by
     * catch handlers to undo any nested scope entries the unwind may have
     * skipped.
     */
    record SetLexicalEnvironment(Operand environment) implements Op {
        @Override public Operation operation() { return Operation.SET_LEXICAL_ENVIRONMENT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // v1: read the operand (side-use honesty) but do nothing.
            environment.retrieve(ctx);
            return pc + 1;
        }
    }

    /**
     * Create a fresh lexical environment with {@code parent} as its outer
     * environment, storing the new env into {@code dst}. Emitted by codegen
     * for named function expressions (binding the function's own name in a
     * private scope) and for block-statement scopes that contain {@code let}
     * or {@code const} declarations.
     */
    record CreateLexicalEnvironment(Variable dst, Operand parent, int capacity) implements Op {
        @Override public Operation operation() { return Operation.CREATE_LEXICAL_ENVIRONMENT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // v1: shape-only — store a sentinel so subsequent ops don't NPE.
            parent.retrieve(ctx);
            dst.store(ctx, Undefined.VALUE);
            return pc + 1;
        }
    }

    /**
     * Declare a binding in the current lexical environment. Used together
     * with {@link InitializeLexicalBinding} for {@code let}/{@code const} and
     * named function expressions.
     */
    record CreateVariable(String name, boolean isImmutable, boolean isGlobal, boolean isStrict) implements Op {
        @Override public Operation operation() { return Operation.CREATE_VARIABLE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // v1: shape-only — InitializeLexicalBinding writes the actual value;
            // named function expressions reference their own name via a closure.
            return pc + 1;
        }
    }

    /**
     * Add a mutable binding to the given lexical environment. Used by
     * Annex-B function-declarations inside non-function blocks (switch
     * cases, etc.) where the binding lives in a fresh lex env.
     */
    record CreateMutableBinding(Operand environment, boolean canBeDeleted, String name) implements Op {
        @Override public Operation operation() { return Operation.CREATE_MUTABLE_BINDING; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // v1 deviation — we model only one runtime environment (globals
            // map + per-frame locals), so the spec's per-block lex envs collapse.
            // Annex-B function-decl-in-block uses this op to declare the
            // binding before SetVariableBinding writes to it; in our model
            // the binding exists implicitly (globals.put on first write), so
            // a no-op here matches observable behavior. The (environment,
            // canBeDeleted, name) operands are kept for future fidelity.
            return pc + 1;
        }
    }

    /**
     * Assign to a binding looked up by name (Annex-B re-binding for
     * function-declarations inside non-function blocks).
     */
    record SetVariableBinding(String identifier, Operand src, EnvironmentCoordinate cache) implements Op {
        @Override public Operation operation() { return Operation.SET_VARIABLE_BINDING; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // v1 — see {@link CreateMutableBinding}. Falls back to globals.
            Object v = src.retrieve(ctx);
            ctx.globals().put(identifier, v);
            return pc + 1;
        }
    }

    /**
     * Materialize the current function's {@code this} binding into the
     * THIS_VALUE register slot. Emitted at the first reference to {@code this}
     * within a function/script scope; subsequent uses reference the special
     * {@code Operand.This} pseudo-operand, which reads from the same slot.
     */
    record ResolveThisBinding() implements Op {
        @Override public Operation operation() { return Operation.RESOLVE_THIS_BINDING; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // `this` is already in THIS_VALUE at call entry. No-op.
            return pc + 1;
        }
    }

    /**
     * Push a fresh private-name environment onto the private-environment
     * stack. Emitted by class lowering when the class declares any private
     * members (`#name`). v1: shape-only — runtime is a no-op until private
     * fields are wired through.
     */
    record CreatePrivateEnvironment() implements Op {
        @Override public Operation operation() { return Operation.CREATE_PRIVATE_ENVIRONMENT; }
        @Override public int interpret(InterpContext ctx, int pc) { return pc + 1; }
    }

    /** Pop the current private-name environment. */
    record LeavePrivateEnvironment() implements Op {
        @Override public Operation operation() { return Operation.LEAVE_PRIVATE_ENVIRONMENT; }
        @Override public int interpret(InterpContext ctx, int pc) { return pc + 1; }
    }

    /**
     * Add {@code name} to the current private-name environment. Emitted once
     * per private member (`#name`) at class definition time.
     */
    record AddPrivateName(String name) implements Op {
        @Override public Operation operation() { return Operation.ADD_PRIVATE_NAME; }
        @Override public int interpret(InterpContext ctx, int pc) { return pc + 1; }
    }

    // ============================================================
    //  Property access — IC-bearing
    // ============================================================

    /**
     * {@code dst = base[property]} for a named property. Carries a
     * {@link PropertyLookupCache} that the interpreter mutates with observed
     * receiver shapes. The cache reference is {@code final}; only the cache's
     * internal state mutates.
     *
     * <p>{@code baseIdentifier} is a non-null source-side qualified name for
     * the base (e.g. {@code "assert"} for {@code assert.throws}) included in
     * dump output for diagnostics. May be null when the base is an arbitrary
     * expression.
     */
    record GetById(
        Variable dst,
        Operand base,
        String property,
        String baseIdentifier,
        PropertyLookupCache cache
    ) implements Op {
        @Override public Operation operation() { return Operation.GET_BY_ID; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object b = base.retrieve(ctx);
            // Shape-keyed inline cache: on hit, the property's storage offset
            // is known and the read is a single indexed load — the V8/LibJS
            // monomorphic-fast-path trick that makes hot OO code fly.
            if (b instanceof JSObject obj
                    && (property.isEmpty() || property.charAt(0) != '#')) {
                Shape s = obj.shape();
                int slot = cache.lookup(s);
                Object resolved;
                if (slot >= 0) {
                    Object owner = cache.ownerOf();
                    if (owner == null) {
                        Object cached = obj.getDirect(slot);
                        if (!(cached instanceof Accessor)) {
                            dst.store(ctx, cached);
                            return pc + 1;
                        }
                        resolved = cached;
                    } else if (owner instanceof JSObject oj
                                && oj.shape() == cache.ownerShapeOf()) {
                        Object cached = oj.getDirect(slot);
                        if (!(cached instanceof Accessor)) {
                            dst.store(ctx, cached);
                            return pc + 1;
                        }
                        resolved = cached;
                    } else {
                        // Stale proto entry — fall to miss path.
                        resolved = JSObject.ABSENT;
                    }
                } else {
                    resolved = JSObject.ABSENT;
                }
                if (resolved == JSObject.ABSENT) {
                    // Miss: walk own then prototype chain once; capture the
                    // resolved value, install IC, and dispatch on accessor.
                    Shape.PropertyMeta meta = s.lookup(property);
                    if (meta != null) {
                        Object v = obj.getDirect(meta.offset());
                        if (!(v instanceof Accessor)) {
                            cache.install(s, meta.offset());
                            dst.store(ctx, v);
                            return pc + 1;
                        }
                        resolved = v;
                    } else {
                        resolved = Undefined.VALUE;
                        JSObject cursor = obj.proto();
                        while (cursor != null) {
                            Shape cs = cursor.shape();
                            Shape.PropertyMeta cm = cs.lookup(property);
                            if (cm != null) {
                                Object v = cursor.getDirect(cm.offset());
                                if (!(v instanceof Accessor)) {
                                    cache.installProto(s, cursor, cs, cm.offset());
                                    dst.store(ctx, v);
                                    return pc + 1;
                                }
                                resolved = v;
                                break;
                            }
                            cursor = cursor.proto();
                        }
                    }
                }
                if (resolved instanceof Accessor acc && acc.getter() != null) {
                    Object v = Interpreter.invokeFunction(acc.getter(), b, new Object[0], ctx);
                    dst.store(ctx, v);
                    return pc + 1;
                }
                dst.store(ctx, resolved);
                return pc + 1;
            }
            Object v = AbstractOps.getProperty(b, property);
            if (v instanceof Accessor acc && acc.getter() != null) {
                v = Interpreter.invokeFunction(acc.getter(), b, new Object[0], ctx);
            }
            dst.store(ctx, v);
            return pc + 1;
        }
    }

    /**
     * Specialized {@code dst = base.length}. LibJS emits this for any
     * {@code .length} member access — even on values that aren't arrays
     * — because the property name is so common that branching on it pays
     * for itself in cache locality. Carries a {@code baseIdentifier} hint
     * matching {@link GetById}'s convention for diagnostics dumps.
     */
    record GetLength(
        Variable dst,
        Operand base,
        String baseIdentifier,
        PropertyLookupCache cache
    ) implements Op {
        @Override public Operation operation() { return Operation.GET_LENGTH; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object b = base.retrieve(ctx);
            // Fast path: the magical .length properties on Array, String,
            // and arguments-style objects. Mirrors LibJS's GetByIdMode::Length
            // fast path (PropertyAccess.h:75-88) — `xs.length` in for-loops
            // is one of the hottest accesses in any JS workload.
            if (b instanceof JSArray arr) {
                dst.store(ctx, AbstractOps.boxDouble(arr.length()));
                return pc + 1;
            }
            if (b instanceof CharSequence cs) {
                dst.store(ctx, AbstractOps.boxDouble(cs.length()));
                return pc + 1;
            }
            Object v = AbstractOps.getProperty(b, "length");
            if (v instanceof Accessor acc && acc.getter() != null) {
                v = Interpreter.invokeFunction(acc.getter(), b, new Object[0], ctx);
            }
            dst.store(ctx, v);
            return pc + 1;
        }
    }

    /** {@code base[property] = src} for a named property. */
    record PutById(
        Operand base,
        String property,
        Operand src,
        PropertyLookupCache cache,
        // LibJS dump annotation: dot-chain of the base expression, e.g.
        // "obj.foo" for `obj.foo.bar = ...`. Null for arbitrary base
        // expressions. The dump renders as ` (obj.prop)` after the kind.
        String baseIdentifier,
        // LibJS distinguishes regular property writes (Normal), getter
        // installation (Getter), and setter installation (Setter). v1
        // emits Normal for regular `=` assignments to member properties.
        PutByIdKind kind
    ) implements Op {
        @Override public Operation operation() { return Operation.PUT_BY_ID; }

        // Backward-compatible 4-arg constructor — defaults to (null, Normal).
        public PutById(Operand base, String property, Operand src, PropertyLookupCache cache) {
            this(base, property, src, cache, null, PutByIdKind.NORMAL);
        }

        @Override public int interpret(InterpContext ctx, int pc) {
            Object b = base.retrieve(ctx);
            Object value = src.retrieve(ctx);
            switch (kind) {
                case GETTER, SETTER -> {
                    if (!(b instanceof JSObject jo)) {
                        throw AbruptCompletion.typeError("Internal: PutById accessor target not JSObject");
                    }
                    JSFunction fn = (value instanceof JSFunction f) ? f : null;
                    Accessor acc = (kind == PutByIdKind.GETTER)
                        ? new Accessor(fn, null)
                        : new Accessor(null, fn);
                    Object existing = jo.get(property);
                    if (existing instanceof Accessor prior) acc = prior.merge(acc);
                    jo.set(property, acc);
                }
                case OWN -> {
                    if (b instanceof JSObject jo) jo.set(property, value);
                    else AbstractOps.setProperty(b, property, value);
                }
                case NORMAL -> {
                    // Shape-keyed IC fast path. Three cases:
                    //  * own update: cached shape == receiver, write to slot;
                    //  * put-transition: cached source-shape == receiver,
                    //    transition to cached target-shape, write to slot
                    //    (object-literal init hot path);
                    //  * miss: probe shape, install one of the above.
                    if (b instanceof JSObject jo) {
                        Shape s = jo.shape();
                        int slot = cache.lookup(s);
                        if (slot >= 0) {
                            Object kind = cache.ownerOf();
                            if (kind == null) {
                                Object existing = jo.getDirect(slot);
                                if (!(existing instanceof Accessor)) {
                                    Shape.PropertyMeta meta = s.lookup(property);
                                    if (meta != null
                                            && (meta.attrs() & JSObject.ATTR_WRITABLE) != 0) {
                                        jo.putDirect(slot, value);
                                        return pc + 1;
                                    }
                                }
                            } else if (kind == com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache.PUT_TRANSITION) {
                                Shape target = (Shape) cache.ownerShapeOf();
                                jo.putWithTransition(target, slot, value);
                                return pc + 1;
                            }
                            // Fall through on accessor / read-only / proto.
                        } else {
                            // IC miss. Two install paths: existing-key
                            // update vs new-key put-transition.
                            Shape.PropertyMeta meta = s.lookup(property);
                            if (meta != null) {
                                Object existing = jo.getDirect(meta.offset());
                                if (!(existing instanceof Accessor)
                                        && (meta.attrs() & JSObject.ATTR_WRITABLE) != 0) {
                                    cache.install(s, meta.offset());
                                    jo.putDirect(meta.offset(), value);
                                    return pc + 1;
                                }
                            } else {
                                // ECMA-262 § 10.1.9.1 OrdinarySetWithOwnDescriptor
                                // step 3.b: when there's no own
                                // descriptor and {@code [[Extensible]]}
                                // is false, the write fails — silently
                                // in sloppy mode, TypeError in strict.
                                if (!jo.isExtensible()) {
                                    if (ctx.executable() != null && ctx.executable().strictMode()) {
                                        throw AbruptCompletion.typeError(
                                            "Cannot add property '" + property + "', object is not extensible");
                                    }
                                    return pc + 1;
                                }
                                // Inlined proto walk: skip put-transition
                                // install if any proto level intercepts the
                                // write (setter accessor or read-only slot).
                                JSObject probe = jo.proto();
                                boolean intercept = false;
                                while (probe != null) {
                                    Object raw = probe.getOwn(property);
                                    if (raw instanceof Accessor || raw != JSObject.ABSENT) {
                                        intercept = true;
                                        break;
                                    }
                                    probe = probe.proto();
                                }
                                if (!intercept) {
                                    Shape target = s.createPutTransition(property, JSObject.ATTR_DEFAULT);
                                    int offset = target.storageSize() - 1;
                                    cache.installPutTransition(s, target, offset);
                                    jo.putWithTransition(target, offset, value);
                                    return pc + 1;
                                }
                            }
                        }
                        Object own = jo.getOwn(property);
                        if (own != JSObject.ABSENT) {
                            if (own instanceof Accessor acc) {
                                if (acc.setter() != null) {
                                    Interpreter.invokeFunction(acc.setter(), b, new Object[]{value}, ctx);
                                } else if (ctx.executable() != null && ctx.executable().strictMode()) {
                                    throw AbruptCompletion.typeError(
                                        "Cannot set property '" + property + "' of " + b + " which has only a getter");
                                }
                                return pc + 1;
                            }
                            if (!jo.isWritable(property)) {
                                if (ctx.executable() != null && ctx.executable().strictMode()) {
                                    throw AbruptCompletion.typeError(
                                        "Cannot assign to read only property '" + property + "'");
                                }
                                return pc + 1;
                            }
                            jo.set(property, value);
                            return pc + 1;
                        }
                        // Not own — check proto chain for a setter accessor
                        // (or a non-writable data prop). For typical OO code
                        // this walk hits null quickly (the proto chain is
                        // Parser.prototype → Object.prototype; no setters).
                        JSObject cursor = jo.proto();
                        while (cursor != null) {
                            Object raw = cursor.getOwn(property);
                            if (raw instanceof Accessor acc) {
                                if (acc.setter() != null) {
                                    Interpreter.invokeFunction(acc.setter(), b, new Object[]{value}, ctx);
                                    return pc + 1;
                                }
                                if (ctx.executable() != null && ctx.executable().strictMode()) {
                                    throw AbruptCompletion.typeError(
                                        "Cannot set property '" + property + "' of " + b + " which has only a getter");
                                }
                                return pc + 1;
                            }
                            if (raw != JSObject.ABSENT) break;   // shadowed data prop on proto
                            cursor = cursor.proto();
                        }
                        jo.set(property, value);
                        return pc + 1;
                    }
                    // JSFunction (class constructor with static
                    // accessors / data props): keep the existing
                    // accessor-detection path. AbstractOps.setProperty
                    // doesn't know about JSFunction accessors, so do it
                    // inline.
                    if (b instanceof JSFunction fn && fn.hasOwnStatic(property)) {
                        Object existing = fn.getOwnStatic(property);
                        if (existing instanceof Accessor acc) {
                            if (acc.setter() != null) {
                                Interpreter.invokeFunction(acc.setter(), b, new Object[]{value}, ctx);
                            } else if (ctx.executable() != null && ctx.executable().strictMode()) {
                                throw AbruptCompletion.typeError(
                                    "Cannot set property '" + property + "' of " + b + " which has only a getter");
                            }
                            return pc + 1;
                        }
                    }
                    // Non-JSObject / non-JSFunction-with-accessor receiver —
                    // fall back to the generic path.
                    AbstractOps.setProperty(b, property, value);
                }
            }
            return pc + 1;
        }
    }

    enum PutByIdKind {
        NORMAL("Normal"),
        GETTER("Getter"),
        SETTER("Setter"),
        OWN("Own");

        private final String displayName;
        PutByIdKind(String name) { this.displayName = name; }
        @Override public String toString() { return displayName; }
    }

    // ============================================================
    //  Bindings — top-level lexical scope (script-level let/const)
    // ============================================================

    /**
     * Initialize a lexically-scoped binding at the current scope. Used for
     * top-level {@code let}/{@code const} declarations whose names live in
     * the script's lexical environment.
     */
    record InitializeLexicalBinding(
        String identifier,
        Operand src,
        EnvironmentCoordinate cache
    ) implements Op {
        @Override public Operation operation() { return Operation.INITIALIZE_LEXICAL_BINDING; }
        @Override public int interpret(InterpContext ctx, int pc) {
            ctx.globals().put(identifier, src.retrieve(ctx));
            return pc + 1;
        }
    }

    /** Read a binding from the global / top-level lexical environment. */
    record GetGlobal(
        Variable dst,
        String identifier,
        GlobalVariableCache cache
    ) implements Op {
        @Override public Operation operation() { return Operation.GET_GLOBAL; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // Direct-eval scope chain: ECMA-262 § 19.2.1 step 18.a — eval'd
            // code's identifier lookups walk the caller's lexical environment
            // before falling through to globals.
            if (ctx.directEvalScope() != null) {
                Cell cell = ctx.directEvalScope().lookup(identifier);
                if (cell != null) {
                    if (cell.value == InterpContext.TDZ) {
                        throw AbruptCompletion.referenceError("cannot access '" + identifier
                            + "' before initialization");
                    }
                    dst.store(ctx, cell.value);
                    return pc + 1;
                }
            }
            // Single-lookup path: HashMap.get returns null both for "absent"
            // and for "present, mapped to Java null". The common case is
            // "present and non-null" — short-circuit on that.
            Object v = ctx.globals().get(identifier);
            if (v != null) {
                // Module imports: dereference live binding to the source module.
                if (v instanceof com.jimmyhmiller.harmonica.module.ImportRef ref) {
                    if (!ref.sourceGlobals.containsKey(ref.sourceName)) {
                        throw AbruptCompletion.referenceError("cannot access '" + ref.sourceName
                            + "' before initialization");
                    }
                    Object srcVal = ref.sourceGlobals.get(ref.sourceName);
                    if (srcVal == InterpContext.TDZ) {
                        throw AbruptCompletion.referenceError("cannot access '" + ref.sourceName
                            + "' before initialization");
                    }
                    dst.store(ctx, srcVal);
                    return pc + 1;
                }
                if (v == InterpContext.TDZ) {
                    throw AbruptCompletion.referenceError("cannot access '" + identifier
                        + "' before initialization");
                }
                dst.store(ctx, v);
                return pc + 1;
            }
            if (ctx.globals().containsKey(identifier)) {
                // Present-but-null (rare: explicit `let x = null` at module top).
                dst.store(ctx, null);
                return pc + 1;
            }
            // ECMA-262 § 9.3 GlobalEnvironmentRecord: the global object
            // (globalThis) is part of the global environment chain. Reads
            // of an undeclared bare name must observe properties written via
            // `globalThis.X = Y` or `window.X = Y`.
            Object globalThisVal = ctx.globals().get("globalThis");
            if (globalThisVal instanceof JSObject gtObj) {
                Object gv = gtObj.getOwn(identifier);
                if (gv != JSObject.ABSENT) {
                    dst.store(ctx, gv);
                    return pc + 1;
                }
            }
            throw AbruptCompletion.referenceError("" + identifier + " is not defined");
        }
    }

    /**
     * Read a lexically-scoped binding (declared via {@link CreateVariable} +
     * {@link InitializeLexicalBinding}). Used for catch parameters when the
     * catch body contains nested closures that could capture the param —
     * LibJS materializes the binding into a lexical environment in that
     * case (and omits it for local-only catches).
     */
    record GetBinding(
        Variable dst,
        String identifier,
        EnvironmentCoordinate cache
    ) implements Op {
        @Override public Operation operation() { return Operation.GET_BINDING; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // v1 — see {@link CreateMutableBinding} comment. We collapse
            // env chains to globals + locals, so GetBinding falls back to
            // the same name resolution as GetGlobal: locals first
            // (handled by the generator turning local refs into Variable.Local
            // ops), then globals.
            if (ctx.directEvalScope() != null) {
                Cell cell = ctx.directEvalScope().lookup(identifier);
                if (cell != null) {
                    dst.store(ctx, cell.value);
                    return pc + 1;
                }
            }
            if (ctx.globals().containsKey(identifier)) {
                Object v = ctx.globals().get(identifier);
                if (v instanceof com.jimmyhmiller.harmonica.module.ImportRef ref) {
                    Object srcVal = ref.sourceGlobals.get(ref.sourceName);
                    if (srcVal == InterpContext.TDZ) {
                        throw AbruptCompletion.referenceError("cannot access '" + ref.sourceName
                            + "' before initialization");
                    }
                    dst.store(ctx, srcVal);
                    return pc + 1;
                }
                dst.store(ctx, v);
                return pc + 1;
            }
            Object globalThisVal = ctx.globals().get("globalThis");
            if (globalThisVal instanceof JSObject gtObj) {
                Object gv = gtObj.getOwn(identifier);
                if (gv != JSObject.ABSENT) {
                    dst.store(ctx, gv);
                    return pc + 1;
                }
            }
            throw AbruptCompletion.referenceError(identifier + " is not defined");
        }
    }

    /** Assign to a binding in the global / top-level lexical environment. */
    record SetGlobal(
        String identifier,
        Operand src,
        GlobalVariableCache cache
    ) implements Op {
        @Override public Operation operation() { return Operation.SET_GLOBAL; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object value = src.retrieve(ctx);
            // Direct-eval scope: assignments to outer-bound names go through
            // the Cell so the caller frame sees the write.
            if (ctx.directEvalScope() != null) {
                Cell cell = ctx.directEvalScope().lookup(identifier);
                if (cell != null) {
                    cell.value = value;
                    return pc + 1;
                }
            }
            // ECMA-262 § 16.2.1.5: an imported binding is immutable. Assigning
            // through it from the importing module is a TypeError.
            if (ctx.globals().containsKey(identifier)
                    && ctx.globals().get(identifier) instanceof com.jimmyhmiller.harmonica.module.ImportRef) {
                throw AbruptCompletion.typeError(
                    "Assignment to constant variable '" + identifier + "' (imported binding)");
            }
            // ECMA-262 § 6.2.5.5 PutValue:
            //   step 6.a: if Reference is unresolvable AND containing code is
            //   non-strict, create on global object;
            //   step 6.b: if strict, throw ReferenceError.
            if (!ctx.globals().containsKey(identifier)
                    && ctx.executable() != null && ctx.executable().strictMode()) {
                // Strict still allows the assignment if the binding lives on
                // globalThis (e.g. UMD libraries that did `globalThis.foo = …`
                // earlier — that property IS resolvable per § 9.3).
                Object globalThisVal = ctx.globals().get("globalThis");
                if (!(globalThisVal instanceof JSObject gt) || !gt.hasOwn(identifier)) {
                    throw AbruptCompletion.referenceError(identifier + " is not defined");
                }
            }
            ctx.globals().put(identifier, value);
            // Mirror onto globalThis so `globalThis.X` reads see the write —
            // matches ECMA-262 § 9.3's GlobalObject ↔ GlobalEnvironmentRecord
            // contract (the global object IS the binding store for "var" /
            // unqualified writes, not a separate cache).
            Object globalThisVal = ctx.globals().get("globalThis");
            if (globalThisVal instanceof JSObject gt && gt != value) {
                gt.set(identifier, value);
            }
            return pc + 1;
        }
    }

    // ============================================================
    //  Function construction
    // ============================================================

    /**
     * Build a callable function value from the executable stored at
     * {@code sharedFunctionDataIndex} in the enclosing executable's nested
     * function table. {@code homeObject} is non-null when this NewFunction is
     * a method or accessor on an object/class — the function's [[HomeObject]]
     * internal slot for {@code super} lookups; null for plain functions.
     */
    record NewFunction(Variable dst, int sharedFunctionDataIndex, String name, Operand homeObject) implements Op {
        @Override public Operation operation() { return Operation.NEW_FUNCTION; }
        @Override public int interpret(InterpContext ctx, int pc) {
            JSFunction template = ctx.executable().sharedFunctionData()[sharedFunctionDataIndex];
            Cell[] captured = new Cell[template.captureCount()];
            for (int k = 0; k < captured.length; k++) {
                captured[k] = ctx.cellAt(template.captureSourceSlots()[k]);
            }
            JSFunction fn = template.withCapturedCells(captured);
            // Stamp the current frame's globals as the function's home globals
            // so that cross-module calls still see the defining module's
            // bindings (per-module scope + live bindings). For non-module
            // scripts, every frame shares the same globals map so this is a
            // no-op semantically — every function still reads/writes the same
            // map regardless of who calls it.
            if (ctx.globals() instanceof com.jimmyhmiller.harmonica.module.ModuleGlobals) {
                fn.setHomeGlobals(ctx.globals());
            }
            dst.store(ctx, fn);
            return pc + 1;
        }
    }

    // ============================================================
    //  Object & array creation
    // ============================================================

    /** Create a fresh empty object. */
    record NewObject(Variable dst) implements Op {
        @Override public Operation operation() { return Operation.NEW_OBJECT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, new JSObject());
            return pc + 1;
        }
    }

    /**
     * Implements ECMA-262 § B.3.1: in an object initializer the
     * non-computed key {@code __proto__} (with a regular {@code init}
     * value, not a method/getter/setter) sets the new object's
     * {@code [[Prototype]]} when the value is Object or Null. Other
     * values are silently ignored — no own property is created.
     */
    record SetProtoOrNop(Operand target, Operand value) implements Op {
        @Override public Operation operation() { return Operation.SET_PROTO_OR_NOP; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object t = target.retrieve(ctx);
            if (!(t instanceof JSObject jo)) return pc + 1;
            Object v = value.retrieve(ctx);
            if (v == null) jo.setProto(null);
            else if (v instanceof JSObject p) jo.setProto(p);
            // Primitives (including Undefined): no-op per spec.
            return pc + 1;
        }
    }

    /**
     * ECMA-262 § 13.3.7.1.1 SuperCall step 7-8: after a super() call,
     * if the parent constructor returned an object, that becomes the
     * derived class's {@code this}. Primitives (including
     * {@code undefined}) leave the existing receiver in place.
     */
    record SuperBindThis(Operand result) implements Op {
        @Override public Operation operation() { return Operation.SET_PROTO_OR_NOP; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object r = result.retrieve(ctx);
            if (r instanceof JSObject || r instanceof JSArray || r instanceof JSFunction) {
                ctx.registers()[Variable.Register.THIS_VALUE_INDEX] = r;
            }
            return pc + 1;
        }
    }

    /**
     * Fused object-literal construction: allocate a JSObject with a
     * precomputed shape and pre-filled storage in one step. Replaces the
     * sequence {@code NewObject + N×InitObjectLiteralProperty +
     * CacheObjectShape} the generator used to emit for plain literals.
     *
     * <p>The first execution walks {@link Shape#createPutTransition} once
     * per key to build the target shape (sharing the transition tree with
     * any other literal that walks the same key sequence), caches the
     * result, then allocates. Subsequent executions hit the cached shape
     * and skip straight to allocation + storage fill — one JSObject
     * allocation, one Object[] allocation, N array stores. No op-dispatch
     * cycle per property.
     *
     * <p>Only emitted by the generator for plain literals where every key
     * is a static string identifier with default attributes — no
     * accessors, no spread, no computed-key, no numeric literal keys
     * (those force the old PutById Own path). Duplicate keys in the same
     * literal are also rejected at codegen time; they'd need to override
     * a previous slot's value rather than transition.
     */
    record MakeShapedObject(
        Variable dst,
        String[] propertyNames,
        Operand[] values,
        ShapeCache cache
    ) implements Op {
        public static final class ShapeCache {
            private volatile Shape target;
            Shape get() { return target; }
            void set(Shape s) { target = s; }
        }

        @Override public Operation operation() { return Operation.MAKE_SHAPED_OBJECT; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }

        @Override public int interpret(InterpContext ctx, int pc) {
            Shape target = cache.get();
            if (target == null) {
                Shape s = Realm.shapeForEmptyObject(Realm.objectPrototype);
                for (String name : propertyNames) {
                    s = s.createPutTransition(name, JSObject.ATTR_DEFAULT);
                }
                cache.set(s);
                target = s;
            }
            Object[] storage = new Object[target.storageSize()];
            for (int i = 0; i < values.length; i++) storage[i] = values[i].retrieve(ctx);
            dst.store(ctx, new JSObject(target, storage));
            return pc + 1;
        }
    }

    /**
     * Construct: invoke {@code callee} as a constructor with the given args.
     * Allocates a fresh receiver, runs the body with that receiver as
     * {@code this}, then yields either the body's returned object (if any)
     * or the receiver.
     */
    record CallConstruct(Variable dst, Operand callee, Operand[] args, String expressionString, CallSite cache) implements Op {
        @Override public Operation operation() { return Operation.CALL_CONSTRUCT; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object calleeVal = callee.retrieve(ctx);
            if (!(calleeVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("not a constructor: " + calleeVal);
            }
            // ECMA-262 § 15.3 ArrowFunction has no [[Construct]]; § 27.7 async
            // functions and § 27.5 generators also lack [[Construct]] (calling
            // `new` on them should throw before the body runs).
            if (fn.isArrow() || fn.isAsync() || fn.isGenerator()) {
                throw AbruptCompletion.typeError("not a constructor: " + (fn.name() != null ? fn.name() : "<anonymous>"));
            }
            JSObject receiver = new JSObject(ensureFunctionPrototype(fn));
            Object[] argValues = new Object[args.length];
            for (int k = 0; k < args.length; k++) argValues[k] = args[k].retrieve(ctx);
            Object result = Interpreter.invokeFunctionAsConstructor(fn, receiver, argValues, ctx);
            Object finalResult = (result instanceof JSObject || result instanceof JSArray
                                  || result instanceof JSFunction) ? result : receiver;
            dst.store(ctx, finalResult);
            return pc + 1;
        }
    }

    /**
     * For {@code new F()}: if {@code F} is a non-native user function whose
     * {@code prototype} property hasn't been observed yet, lazily synthesize
     * one with a back-pointing {@code constructor}. Mirrors the lazy create
     * in {@link AbstractOps#getProperty} so a fresh instance's
     * {@code [[Prototype]]} is set up correctly even when nobody touched
     * {@code F.prototype} first.
     */
    private static JSObject ensureFunctionPrototype(JSFunction fn) {
        JSObject p = fn.prototypeObject();
        if (p == null && !fn.isNative()) {
            p = new JSObject();
            p.set("constructor", fn);
            fn.setPrototypeObject(p);
        }
        return p;
    }

    /**
     * Construct with a pre-built argument array — emitted when `new` has any
     * spread argument. {@code argumentsArray} is a JS array constructed via
     * NewArray + ArrayAppend (matching the spread-call lowering pattern).
     */
    record CallConstructWithArgumentArray(Variable dst, Operand callee, Operand argumentsArray,
                                           String expressionString, CallSite cache) implements Op {
        @Override public Operation operation() { return Operation.CALL_CONSTRUCT_WITH_ARGUMENT_ARRAY; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object calleeVal = callee.retrieve(ctx);
            if (!(calleeVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("not a constructor: " + calleeVal);
            }
            if (fn.isArrow() || fn.isAsync() || fn.isGenerator()) {
                throw AbruptCompletion.typeError("not a constructor: " + (fn.name() != null ? fn.name() : "<anonymous>"));
            }
            Object argsVal = argumentsArray.retrieve(ctx);
            Object[] argValues = (argsVal instanceof JSArray arr) ? arr.elements().toArray() : new Object[0];
            JSObject receiver = new JSObject(ensureFunctionPrototype(fn));
            Object result = Interpreter.invokeFunctionAsConstructor(fn, receiver, argValues, ctx);
            Object finalResult = (result instanceof JSObject || result instanceof JSArray
                                  || result instanceof JSFunction) ? result : receiver;
            dst.store(ctx, finalResult);
            return pc + 1;
        }
    }

    /** Set {@code function.prototypeObject = prototype}. Used by class lowering. */
    record SetFunctionPrototype(Operand function, Operand prototype) implements Op {
        @Override public Operation operation() { return Operation.SET_FUNCTION_PROTOTYPE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object fnVal = function.retrieve(ctx);
            Object protoVal = prototype.retrieve(ctx);
            if (!(fnVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("Internal: SetFunctionPrototype on non-function: " + fnVal);
            }
            if (!(protoVal instanceof JSObject proto)) {
                throw AbruptCompletion.typeError("Internal: SetFunctionPrototype with non-object prototype: " + protoVal);
            }
            fn.setPrototypeObject(proto);
            return pc + 1;
        }
    }

    /**
     * Materialize a class object from a class blueprint. The blueprint is
     * stored at {@code classBlueprintIndex} in the enclosing executable's
     * blueprint table; {@code elementKeys} lists the literal values of every
     * non-constructor public member key, in declaration order, preserving
     * each key's original literal type (String for identifiers/string
     * literals, Number for numeric literals, etc.). LibJS renders these
     * inline in the dump (e.g. {@code Int32(16)} for {@code get 0x10()}),
     * and they share a constant-pool slot with their typed value, so
     * preserving the type is required for byte-perfect parity. {@code null}
     * entries indicate computed keys.
     * {@code classEnvironment} is the private lexical environment created
     * for the class body. {@code superClass} is non-null for derived
     * classes (the resolved superclass value at NewClass time).
     * {@code displayName} is non-null only for anonymous class expressions
     * whose name was inferred from an outer var/let/const binding
     * (NamedEvaluation hint) — LibJS shows it as a {@code (name)}
     * annotation in the dump for these specifically.
     */
    record NewClass(Variable dst, Operand superClass, Operand classEnvironment,
                    int classBlueprintIndex, Operand[] elementKeys,
                    String displayName) implements Op {
        @Override public Operation operation() { return Operation.NEW_CLASS; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public int interpret(InterpContext ctx, int pc) {
            Executable executable = ctx.executable();
            Executable.ClassBlueprint bp = executable.classBlueprints()[classBlueprintIndex];

            JSFunction ctorTemplate = executable.sharedFunctionData()[bp.constructorIndex()];
            Cell[] captured = new Cell[ctorTemplate.captureCount()];
            for (int k = 0; k < captured.length; k++) {
                captured[k] = ctx.cellAt(ctorTemplate.captureSourceSlots()[k]);
            }
            JSFunction ctor = ctorTemplate.withCapturedCells(captured);

            JSObject proto = new JSObject();
            // ECMA-262 § 15.7.10 / § 10.2.5: F.prototype.constructor === F,
            // non-enumerable + writable + configurable.
            proto.set("constructor", ctor);
            proto.setAttributes("constructor",
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            ctor.setPrototypeObject(proto);

            JSFunction superCtorVal = null;
            if (superClass != null) {
                Object superVal = superClass.retrieve(ctx);
                JSObject parentProto = null;
                if (superVal instanceof JSFunction sf) {
                    // ECMA-262 § 15.7.10 ClassDefinitionEvaluation step
                    // 6.f: IsConstructor(superclass). Arrow, async, and
                    // generator functions lack [[Construct]] and must
                    // throw TypeError when used as the heritage value.
                    if (sf.isArrow() || sf.isAsync() || sf.isGenerator()) {
                        throw AbruptCompletion.typeError(
                            "Class extends value " + (sf.name() != null ? sf.name() : "<anonymous>")
                                + " is not a constructor");
                    }
                    parentProto = sf.prototypeObject();
                    ctor.setSuperConstructor(sf);
                    superCtorVal = sf;
                } else if (superVal == null) {
                    parentProto = null;
                } else if (superVal instanceof JSObject so) {
                    // Non-function objects aren't constructors either —
                    // spec wants TypeError.
                    throw AbruptCompletion.typeError(
                        "Class extends value " + so + " is not a constructor");
                } else if (superVal != Undefined.VALUE) {
                    throw AbruptCompletion.typeError("extends value is not a class or null: " + superVal);
                }
                proto.setProto(parentProto);
            }

            for (Executable.ClassMember m : bp.members()) {
                // ECMA-262 § 15.7.10 ClassDefinitionEvaluation step 28:
                // computed property names are resolved when the class body
                // is evaluated. Keep the resolved value as Object so symbol
                // keys flow through to setProperty / get / merge unchanged
                // (ToString-coercing a Symbol throws per § 7.1.17.2).
                Object key = m.key();
                if (key == null) {
                    int eki = m.elementKeyIndex();
                    if (eki < 0 || eki >= elementKeys.length || elementKeys[eki] == null) {
                        throw AbruptCompletion.typeError(
                            "Internal: missing elementKey operand for computed class member");
                    }
                    key = elementKeys[eki].retrieve(ctx);
                }
                Object installTarget = m.isStatic() ? (Object) ctor : proto;
                // Normalize the key for raw-map storage. Symbols become
                // their per-instance asPropertyKey() string (see
                // AbstractOps.setProperty), other keys go through ToString.
                String keyStr = key instanceof JSSymbol sym ? sym.asPropertyKey()
                              : key instanceof String s    ? s
                              : AbstractOps.toString(key);
                JSFunction memberTemplate = m.templateIndex() >= 0
                    ? executable.sharedFunctionData()[m.templateIndex()]
                    : null;
                if ("method".equals(m.kind())) {
                    Cell[] capt = new Cell[memberTemplate.captureCount()];
                    for (int k = 0; k < capt.length; k++) {
                        capt[k] = ctx.cellAt(memberTemplate.captureSourceSlots()[k]);
                    }
                    JSFunction methodFn = memberTemplate.withCapturedCells(capt);
                    // ECMA-262 § 15.7.10 step 31.b: each method's [[HomeObject]]
                    // is the proto/ctor it lives on. We approximate by stashing
                    // the parent constructor on the method so super.X resolves
                    // correctly via SuperBase = HomeObject.[[Prototype]].
                    if (superCtorVal != null) methodFn.setSuperConstructor(superCtorVal);
                    // ECMA-262 § 15.7.10 step 31.b sub-step (MakeMethod) and
                    // § 15.4.5: class methods are NON-enumerable + configurable
                    // + writable. Set the attributes byte directly to avoid
                    // tripping the enumerable check in test262's verifyProperty.
                    if (installTarget instanceof JSObject jo) {
                        jo.set(keyStr, methodFn);
                        jo.setAttributes(keyStr, (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
                    } else if (installTarget instanceof JSFunction jf) {
                        jf.properties().put(keyStr, methodFn);
                        jf.setAttributes(keyStr, (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
                    }
                } else if ("get".equals(m.kind()) || "set".equals(m.kind())) {
                    Cell[] capt = new Cell[memberTemplate.captureCount()];
                    for (int k = 0; k < capt.length; k++) {
                        capt[k] = ctx.cellAt(memberTemplate.captureSourceSlots()[k]);
                    }
                    JSFunction accFn = memberTemplate.withCapturedCells(capt);
                    if (superCtorVal != null) accFn.setSuperConstructor(superCtorVal);
                    JSFunction getter = "get".equals(m.kind()) ? accFn : null;
                    JSFunction setter = "set".equals(m.kind()) ? accFn : null;
                    Accessor acc = new Accessor(getter, setter);
                    // Raw lookup so the existing-accessor merge sees the
                    // Accessor cell, not its invoked getter result.
                    Object existing = AbstractOps.getOwnPropertyRaw(installTarget, keyStr);
                    if (existing instanceof Accessor prior) acc = prior.merge(acc);
                    // Raw write — bypass AbstractOps.setProperty's accessor-
                    // invoking [[Set]] semantics. We're installing the
                    // accessor cell itself, not assigning through it.
                    // Class accessors (§ 15.7.10): non-enumerable, configurable;
                    // no `writable` for accessor descriptors per § 6.1.7.1.
                    if (installTarget instanceof JSObject jo) {
                        jo.set(keyStr, acc);
                        jo.setAttributes(keyStr, JSObject.ATTR_CONFIGURABLE);
                    } else if (installTarget instanceof JSFunction jf) {
                        jf.properties().put(keyStr, acc);
                        jf.setAttributes(keyStr, JSObject.ATTR_CONFIGURABLE);
                    } else {
                        AbstractOps.setProperty(installTarget, keyStr, acc);
                    }
                } else if ("field".equals(m.kind())) {
                    Object initVal = m.literalInitValue() != null ? m.literalInitValue() : Undefined.VALUE;
                    AbstractOps.setProperty(installTarget, keyStr, initVal);
                } else {
                    throw AbruptCompletion.typeError("Internal: unsupported class member kind: " + m.kind());
                }
            }

            dst.store(ctx, ctor);
            return pc + 1;
        }
    }

    /**
     * Wire {@code child.[[Prototype]] = parent.prototypeObject}. Used to link
     * a derived class's prototype to its base class's prototype, so method
     * lookups walk up the chain.
     */
    record SetPrototypeOf(Operand child, Operand parent) implements Op {
        @Override public Operation operation() { return Operation.RESOLVE_SUPER_BASE; }   // borrowing
        @Override public int interpret(InterpContext ctx, int pc) {
            Object childVal = child.retrieve(ctx);
            Object parentVal = parent.retrieve(ctx);
            if (!(childVal instanceof JSObject co)) {
                throw AbruptCompletion.typeError("Internal: SetPrototypeOf on non-object child: " + childVal);
            }
            JSObject parentProto = null;
            if (parentVal instanceof JSFunction fn) parentProto = fn.prototypeObject();
            else if (parentVal instanceof JSObject obj) parentProto = obj;
            else if (parentVal != null && parentVal != Undefined.VALUE) {
                throw AbruptCompletion.typeError("extends value is not a class or null: " + parentVal);
            }
            co.setProto(parentProto);
            return pc + 1;
        }
    }

    /**
     * Initialize a property on a freshly-created object literal. Carries the
     * shape-cache slot indices that LibJS uses for the inline cache that
     * speeds up subsequent property accesses on objects with this shape.
     * v1: indices are emitted as {@code 0}/{@code 0} since we don't yet have
     * a shape cache; the dump matches LibJS while the runtime ignores them.
     */
    record InitObjectLiteralProperty(Operand object, String property, Operand src,
                                     int shapeCacheIndex, int propertySlot,
                                     com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache cache) implements Op {
        public InitObjectLiteralProperty(Operand object, String property, Operand src,
                                         int shapeCacheIndex, int propertySlot) {
            this(object, property, src, shapeCacheIndex, propertySlot,
                 new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache());
        }
        @Override public Operation operation() { return Operation.INIT_OBJECT_LITERAL_PROPERTY; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object obj = object.retrieve(ctx);
            if (!(obj instanceof JSObject jo)) {
                throw AbruptCompletion.typeError("InitObjectLiteralProperty on non-JSObject: " + obj);
            }
            Object value = src.retrieve(ctx);
            Shape s = jo.shape();
            int slot = cache.lookup(s);
            if (slot >= 0
                    && cache.ownerOf() == com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache.PUT_TRANSITION) {
                // Object-literal init is monomorphic in practice — every
                // execution starts from the same shape and walks the same
                // transition chain. Hit path: transition + indexed store.
                jo.putWithTransition((Shape) cache.ownerShapeOf(), slot, value);
                return pc + 1;
            }
            // Miss: do the lookup. If the key is already present (rare —
            // duplicate keys in a literal), fall back to set; otherwise
            // install a put-transition for next time.
            Shape.PropertyMeta existing = s.lookup(property);
            if (existing == null) {
                Shape target = s.createPutTransition(property, JSObject.ATTR_DEFAULT);
                int offset = target.storageSize() - 1;
                cache.installPutTransition(s, target, offset);
                jo.putWithTransition(target, offset, value);
            } else {
                jo.set(property, value);
            }
            return pc + 1;
        }
    }

    /**
     * Cache the object's shape for inline-cache lookups by future
     * property accesses. Emitted by LibJS at the end of an object literal's
     * initialization. v1: shape-only emission; the interpreter no-ops.
     */
    record CacheObjectShape(Operand object) implements Op {
        @Override public Operation operation() { return Operation.CACHE_OBJECT_SHAPE; }
        @Override public int interpret(InterpContext ctx, int pc) { return pc + 1; }
    }

    /**
     * Install an accessor descriptor (getter and/or setter) on {@code target}
     * under {@code property}. Either {@code getter} or {@code setter} may be
     * an undefined-constant operand to indicate "not provided." Merges with
     * any existing accessor at the same key.
     */
    record DefineAccessor(Operand target, String property, Operand getter, Operand setter) implements Op {
        @Override public Operation operation() { return Operation.DEFINE_ACCESSOR; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object t = target.retrieve(ctx);
            if (!(t instanceof JSObject targetObj)) {
                throw AbruptCompletion.typeError("Internal: DefineAccessor target is not JSObject");
            }
            Object getterVal = getter.retrieve(ctx);
            Object setterVal = setter.retrieve(ctx);
            JSFunction getterFn = (getterVal instanceof JSFunction g) ? g : null;
            JSFunction setterFn = (setterVal instanceof JSFunction s) ? s : null;
            Accessor acc = new Accessor(getterFn, setterFn);
            Object existing = targetObj.get(property);
            if (existing instanceof Accessor prior) acc = prior.merge(acc);
            targetObj.set(property, acc);
            return pc + 1;
        }
    }

    /** Read {@code new.target} for the current call frame. */
    record GetNewTarget(Variable dst) implements Op {
        @Override public Operation operation() { return Operation.GET_NEW_TARGET; }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, ctx.newTarget());
            return pc + 1;
        }
    }

    /**
     * Read the super-constructor for the current frame. Set on a derived
     * class's constructor when the class extends another. Throws a
     * SyntaxError-shaped completion if not in a derived constructor.
     */
    record GetSuperConstructor(Variable dst) implements Op {
        @Override public Operation operation() { return Operation.GET_SUPER_CONSTRUCTOR; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object sup = ctx.superConstructor();
            if (sup == null) {
                throw AbruptCompletion.syntaxError("'super' keyword is only valid inside a derived class constructor");
            }
            dst.store(ctx, sup);
            return pc + 1;
        }
    }

    /**
     * Build the function's {@code arguments} object — ECMA-262 § 10.4.4.6
     * {@code CreateUnmappedArgumentsObject(argumentsList)}.
     *
     * <p>Spec algorithm:
     * <ol>
     *   <li>Let {@code len} be the number of elements in argumentsList.
     *   <li>Let {@code obj} = {@code OrdinaryObjectCreate(%Object.prototype%, « [[ParameterMap]] »)}.
     *   <li>Set {@code obj.[[ParameterMap]]} to undefined.
     *   <li>{@code DefinePropertyOrThrow(obj, "length", { Value: 𝔽(len), Writable: true, Enumerable: false, Configurable: true })}.
     *   <li>For each {@code i} in {@code 0..len}: {@code CreateDataPropertyOrThrow(obj, ToString(𝔽(i)), val)} (default attrs: writable, enumerable, configurable).
     *   <li>{@code DefinePropertyOrThrow(obj, %Symbol.iterator%, { Value: %Array.prototype.values%, Writable: true, Enumerable: false, Configurable: true })}.
     *   <li>{@code DefinePropertyOrThrow(obj, "callee", { Get: %ThrowTypeError%, Set: %ThrowTypeError%, Enumerable: false, Configurable: false })}.
     *   <li>Return obj.
     * </ol>
     *
     * <p>v1 deviations (each TODO with the spec step it skips):
     * <ul>
     *   <li>Step 6 (@@iterator): not installed — no Symbols yet. {@code for..of}
     *       on {@code arguments} therefore won't work; index + length access does.
     *   <li>Step 7 (callee accessor): not installed — would need
     *       {@code %ThrowTypeError%} intrinsic plumbing through Accessor.
     *   <li>Property attributes (Writable/Enumerable/Configurable) are not
     *       enforced; our {@link JSObject} treats every property as
     *       writable+enumerable+configurable. {@code Object.keys(arguments)}
     *       therefore over-reports compared to spec (length leaks in).
     *   <li>Mapped (non-strict) aliasing of formal parameters (§ 10.4.4.7
     *       {@code CreateMappedArgumentsObject}) is not implemented — we
     *       always emit unmapped (strict-mode-equivalent) semantics.
     * </ul>
     */
    record CreateArguments(Variable dst) implements Op {
        @Override public Operation operation() { return Operation.CREATE_ARGUMENTS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object[] args = ctx.args();
            int len = args == null ? 0 : args.length;
            JSObject obj = new JSObject();
            // Spec step 4 — length goes in first.
            obj.set("length", (double) len);
            // Spec step 5 — indexed properties via CreateDataPropertyOrThrow.
            for (int i = 0; i < len; i++) {
                obj.set(Integer.toString(i), args[i]);
            }
            // Spec step 6 — install @@iterator so spread/for-of work on
            // arguments. We borrow Array.prototype[@@iterator] (Array's `values`)
            // — arguments is array-like and the test262 mapped/unmapped
            // Symbol.iterator tests expect identity equality with it.
            if (Realm.wellKnownIterator != null && Realm.arrayPrototype != null) {
                Object arrIter = Realm.arrayPrototype.get(Realm.wellKnownIterator.asPropertyKey());
                if (arrIter != null && arrIter != Undefined.VALUE) {
                    obj.set(Realm.wellKnownIterator.asPropertyKey(), arrIter);
                }
            }
            // Spec step 7 — strict-mode arguments installs a %ThrowTypeError%
            // accessor on `callee` (and per § 10.4.4.6, also on `caller` in
            // legacy specs). Reading or writing must throw a TypeError. v1:
            // we don't enforce setters but the getter trip suffices for the
            // observable test surface.
            if (ctx.executable() != null && ctx.executable().strictMode()) {
                JSFunction throwTypeError = Realm.throwTypeError();
                if (throwTypeError != null) {
                    Accessor poisoned = new Accessor(throwTypeError, throwTypeError);
                    obj.set("callee", poisoned);
                    // Per § 10.4.4.6 step 7: callee is non-enumerable +
                    // non-configurable in strict-mode arguments.
                    obj.setAttributes("callee", (byte) 0);   // all flags false
                }
            }
            dst.store(ctx, obj);
            return pc + 1;
        }
    }

    /** Build a regex value from precompiled source/flags. */
    record NewRegExp(Variable dst, String pattern, String flags) implements Op {
        @Override public Operation operation() { return Operation.NEW_REGEXP; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // Link to RegExp.prototype so `re instanceof RegExp` and method
            // dispatch (.exec, .test) resolve through the chain.
            JSObject re = Realm.regExpPrototype != null
                ? new JSObject(Realm.regExpPrototype)
                : new JSObject();
            re.set("source", pattern);
            re.set("flags", flags);
            re.set("lastIndex", 0.0);   // ECMA-262 § 22.2.4.1 step 3
            dst.store(ctx, re);
            return pc + 1;
        }
    }

    /**
     * {@code yield value} — suspend execution and surface {@code value} to the
     * generator's consumer. v1: lower the op but the interpreter throws since
     * suspend/resume isn't wired yet.
     */
    record Yield(Variable dst, Operand value, boolean delegate, boolean isAsync) implements Op {
        /** Sync-generator convenience. */
        public Yield(Variable dst, Operand value, boolean delegate) {
            this(dst, value, delegate, /* isAsync */ false);
        }
        @Override public Operation operation() { return Operation.YIELD; }
        @Override public int interpret(InterpContext ctx, int pc) {
            if (delegate) {
                // ECMA-262 § 27.5.3.8 yield* — delegate iteration. We re-enter
                // this same op for each pulled value: first invocation
                // resolves the inner iterator from {@code value}, subsequent
                // invocations re-use the cached iterator on ctx and call
                // its .next forwarding the resumed value.
                Object iter = ctx.delegatedIterator();
                Object nextFn = ctx.delegatedNext();
                if (iter == null) {
                    Object iterable = value.retrieve(ctx);
                    if (iterable == null || iterable == Undefined.VALUE) {
                        throw AbruptCompletion.typeError("yield* operand is not iterable: "
                            + (iterable == null ? "null" : "undefined"));
                    }
                    // ECMA-262 § 14.4.14: in an async generator, yield* uses
                    // GetIterator(hint=async), which tries @@asyncIterator
                    // first and falls back to @@iterator with sync→async
                    // wrapping. We probe @@asyncIterator only when present
                    // and callable; otherwise drop to @@iterator. If
                    // @@asyncIterator exists but isn't callable, that's a
                    // hard TypeError (§ 7.4.2 GetMethod step 4).
                    Object method = null;
                    boolean usedAsyncIter = false;
                    if (isAsync && Realm.wellKnownAsyncIterator != null) {
                        Object asyncMethod = AbstractOps.getProperty(iterable,
                            Realm.wellKnownAsyncIterator.asPropertyKey());
                        if (asyncMethod != null && asyncMethod != Undefined.VALUE) {
                            if (!(asyncMethod instanceof JSFunction)) {
                                throw AbruptCompletion.typeError(
                                    "yield*: @@asyncIterator is not callable");
                            }
                            method = asyncMethod;
                            usedAsyncIter = true;
                        }
                    }
                    String iterKey = Realm.wellKnownIterator != null
                        ? Realm.wellKnownIterator.asPropertyKey() : "@@iterator";
                    if (method == null) method = AbstractOps.getProperty(iterable, iterKey);
                    if (!(method instanceof JSFunction iterMethodFn)) {
                        // Fall back to JSArray / String fast path: build a
                        // tiny synthetic iterator-object so the rest of the
                        // protocol works uniformly.
                        if (iterable instanceof JSArray a) {
                            iter = arrayLikeIterator(new java.util.ArrayList<>(a.elements()));
                        } else if (iterable instanceof String s) {
                            java.util.List<Object> chars = new java.util.ArrayList<>(s.length());
                            for (int k = 0; k < s.length(); k++) chars.add(String.valueOf(s.charAt(k)));
                            iter = arrayLikeIterator(chars);
                        } else {
                            throw AbruptCompletion.typeError("yield* operand is not iterable");
                        }
                    } else {
                        iter = Interpreter.invokeFunction(iterMethodFn, iterable, new Object[0], ctx);
                    }
                    if (!(iter instanceof JSObject)) {
                        throw AbruptCompletion.typeError("@@iterator method did not return an object");
                    }
                    nextFn = AbstractOps.getProperty(iter, "next");
                    ctx.setDelegatedIterator(iter);
                    ctx.setDelegatedNext(nextFn);
                    // Remember whether to await each iterator-result for
                    // subsequent re-entries. usedAsyncIter is set when we
                    // resolved via @@asyncIterator above; in an async
                    // generator we also await sync-iterator results per
                    // § 14.4.14 (CreateAsyncFromSyncIterator).
                    ctx.setDelegatedIsAsync(usedAsyncIter || isAsync);
                }
                // ECMA-262 § 27.5.3.8 yield* — completion forwarding.
                // The outer generator may have been resumed via .return /
                // .throw; route to the inner iterator's return/throw
                // method instead of next, falling back per spec when the
                // method is missing.
                InterpContext.ResumeMode resumeMode = ctx.resumeMode();
                Object methodToCall;
                if (resumeMode == InterpContext.ResumeMode.RETURN) {
                    Object returnMethod = AbstractOps.getProperty(iter, "return");
                    if (returnMethod == null || returnMethod == Undefined.VALUE) {
                        // § 27.5.3.8 step 7.c.iii: no inner return — close
                        // the delegation and propagate the Return upward.
                        ctx.setDelegatedIterator(null);
                        ctx.setDelegatedNext(null);
                        ctx.setDelegatedIsAsync(false);
                        ctx.setResumeMode(InterpContext.ResumeMode.NORMAL);
                        ctx.registers()[Variable.Register.RETURN_VALUE_INDEX] = ctx.lastResumedValue();
                        return Op.FRAME_DONE;
                    }
                    methodToCall = returnMethod;
                } else if (resumeMode == InterpContext.ResumeMode.THROW) {
                    Object throwMethod = AbstractOps.getProperty(iter, "throw");
                    if (throwMethod == null || throwMethod == Undefined.VALUE) {
                        // § 27.5.3.8 step 7.b.iii: no inner throw — close
                        // the delegation (calling inner.return if any)
                        // and rethrow inside the outer body.
                        Object closer = AbstractOps.getProperty(iter, "return");
                        if (closer instanceof JSFunction cfn) {
                            try { Interpreter.invokeFunction(cfn, iter, new Object[0], ctx); }
                            catch (AbruptCompletion ignored) { /* swallow per spec — original throw wins */ }
                        }
                        ctx.setDelegatedIterator(null);
                        ctx.setDelegatedNext(null);
                        ctx.setDelegatedIsAsync(false);
                        Object thrown = ctx.lastResumedValue();
                        ctx.setResumeMode(InterpContext.ResumeMode.NORMAL);
                        throw new AbruptCompletion(thrown);
                    }
                    methodToCall = throwMethod;
                } else {
                    methodToCall = nextFn;
                }
                if (!(methodToCall instanceof JSFunction nfn)) {
                    throw AbruptCompletion.typeError(
                        "yield*: iterator." + (resumeMode == InterpContext.ResumeMode.RETURN ? "return"
                                              : resumeMode == InterpContext.ResumeMode.THROW ? "throw"
                                              : "next") + " is not callable");
                }
                // Reset mode so subsequent re-entries (post-yield) default
                // back to Normal until next .return/.throw arrives.
                ctx.setResumeMode(InterpContext.ResumeMode.NORMAL);
                // ECMA-262 § 27.5.3.8 (yield* step 7.a.i) / IteratorNext:
                // always forward the resumed value as the first argument
                // — even on the first iteration where it's {@code undefined}.
                // Tests assert args.length === 1.
                Object[] callArgs = new Object[]{ctx.lastResumedValue()};
                Object result = Interpreter.invokeFunction(nfn, iter, callArgs, ctx);
                // ECMA-262 Await: when in async-iterator delegation, wrap
                // {@code result} via Promise.resolve, which performs
                // thenable assimilation. Real Promises short-circuit;
                // thenables call their {@code then} (or rethrow if the
                // {@code then} getter is itself abrupt) and the inspected
                // settled state gives us {value, done} or a rejection.
                if (ctx.delegatedIsAsync()) {
                    JSObject p;
                    if (Realm.isPromise(result)) {
                        p = (JSObject) result;
                    } else {
                        p = Realm.createPromise();
                        Realm.resolvePromise(p, result, ctx);
                    }
                    String state = (String) p.properties().get(Realm.PROM_STATE);
                    Object inner = p.properties().get(Realm.PROM_RESULT);
                    if ("fulfilled".equals(state)) result = inner;
                    else if ("rejected".equals(state)) throw new AbruptCompletion(inner);
                    else throw AbruptCompletion.typeError(
                        "yield*: iterator.next() returned a still-pending Promise");
                }
                if (!(result instanceof JSObject)) {
                    throw AbruptCompletion.typeError("yield*: iterator.next() did not return an object");
                }
                // ECMA-262 § 7.4.5 IteratorComplete / § 7.4.6 IteratorValue:
                // done is queried first, then value. Test262 logs both
                // accesses and asserts the order.
                Object iterDone = AbstractOps.getProperty(result, "done");
                Object iterValue = AbstractOps.getProperty(result, "value");
                // § 14.4.14 / § 27.6.1.5.1 CreateAsyncFromSyncIterator: in
                // an async generator delegating to a sync iterator (or
                // any async-iter context), the produced value itself is
                // Awaited. {@code Promise.resolve} performs thenable
                // assimilation; a rejected promise turns into a throw.
                if (ctx.delegatedIsAsync()) {
                    JSObject vp = null;
                    if (Realm.isPromise(iterValue)) vp = (JSObject) iterValue;
                    else if (iterValue instanceof JSObject) {
                        vp = Realm.createPromise();
                        Realm.resolvePromise(vp, iterValue, ctx);
                    }
                    if (vp != null) {
                        String vs = (String) vp.properties().get(Realm.PROM_STATE);
                        Object vi = vp.properties().get(Realm.PROM_RESULT);
                        if ("fulfilled".equals(vs)) iterValue = vi;
                        else if ("rejected".equals(vs)) throw new AbruptCompletion(vi);
                    }
                }
                if (AbstractOps.toBoolean(iterDone)) {
                    // Inner exhausted. Two sub-cases per § 27.5.3.8:
                    //  - mode was Normal/Throw: yield* expression yields
                    //    {@code iterValue} as its result; outer body
                    //    continues past it (step 7.a.vii / 7.b.vi).
                    //  - mode was Return: outer generator itself
                    //    completes with {@code iterValue} (step 7.c.viii).
                    ctx.setDelegatedIterator(null);
                    ctx.setDelegatedNext(null);
                    ctx.setDelegatedIsAsync(false);
                    if (resumeMode == InterpContext.ResumeMode.RETURN) {
                        ctx.registers()[Variable.Register.RETURN_VALUE_INDEX] = iterValue;
                        return Op.FRAME_DONE;
                    }
                    if (dst != null) dst.store(ctx, iterValue);
                    return pc + 1;
                }
                // Yield the inner's value to the outer caller and re-enter
                // *this same op* on resume.
                ctx.setYieldedValue(iterValue);
                ctx.setYieldResumePc(pc);
                ctx.setYieldResumeDst(null);   // resumed value goes via lastResumedValue
                return Op.YIELD_DONE;
            }
            // ECMA-262 § 27.5.3.7 GeneratorYield: stash the yielded value,
            // save where to resume, return the YIELD_DONE sentinel so the
            // suspendable interpret loop bails out and hands control back
            // to the GeneratorObject wrapping this frame.
            Object yieldedValue = value.retrieve(ctx);
            // ECMA-262 § 27.6.3.5 AsyncGeneratorYield: in an async generator,
            // the yielded value goes through Await first. A rejected promise
            // therefore becomes a throw inside the generator body.
            if (isAsync && Realm.isPromise(yieldedValue)) {
                JSObject p = (JSObject) yieldedValue;
                String state = (String) p.properties().get(Realm.PROM_STATE);
                Object inner = p.properties().get(Realm.PROM_RESULT);
                if ("fulfilled".equals(state)) {
                    yieldedValue = inner;
                } else if ("rejected".equals(state)) {
                    throw new AbruptCompletion(inner);
                }
                // Pending: leave as-is; spec would defer but we have no
                // microtask queue. Treating it as the resolved value
                // would deadlock the unit test, so propagate the Promise.
            }
            ctx.setYieldedValue(yieldedValue);
            ctx.setYieldResumePc(pc + 1);
            ctx.setYieldResumeDst(dst);
            return Op.YIELD_DONE;
        }

        /** Build a tiny JSObject iterator over a Java list — used by yield*
         *  fast-path when the source is a JSArray/String without an explicit
         *  {@code @@iterator} method. */
        private static JSObject arrayLikeIterator(java.util.List<Object> items) {
            JSObject iter = new JSObject();
            int[] idx = {0};
            iter.set("next", new JSFunction("next", 0, (t, a, c) -> {
                JSObject r = new JSObject();
                if (idx[0] < items.size()) {
                    r.set("value", items.get(idx[0]++));
                    r.set("done", false);
                } else {
                    r.set("value", Undefined.VALUE);
                    r.set("done", true);
                }
                return r;
            }));
            return iter;
        }
    }

    /**
     * {@code await value} — suspend execution and resume when the awaited
     * value settles. v1: lower but throw at runtime until the microtask queue
     * is wired.
     */
    record Await(Variable dst, Operand value) implements Op {
        @Override public Operation operation() { return Operation.AWAIT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // ECMA-262 § 27.7.5.3 Await: spec wraps the value in
            // {@code PromiseResolve(%Promise%, value)} and pauses until it
            // settles. Our v1 has synchronous Promises (executor + resolvers
            // run inline), so a settled Promise is always already-settled
            // here. We extract its result; for non-Promise values, return
            // them as-is. Pending Promises (e.g. created with a deferred
            // resolver via setTimeout, which we don't have) would deadlock —
            // we surface that as a TypeError instead.
            Object v = value.retrieve(ctx);
            if (Realm.isPromise(v)) {
                JSObject p = (JSObject) v;
                String state = (String) p.properties().get(Realm.PROM_STATE);
                Object result = p.properties().get(Realm.PROM_RESULT);
                if ("fulfilled".equals(state)) {
                    dst.store(ctx, result);
                    return pc + 1;
                }
                if ("rejected".equals(state)) {
                    throw new AbruptCompletion(result);
                }
                throw AbruptCompletion.typeError(
                    "await on a still-pending Promise (no microtask queue)");
            }
            // Non-Promise — pass through. Spec: Promise.resolve(v) followed
            // by an await yields v itself (since Promise.resolve on a
            // non-thenable is immediately fulfilled with v).
            dst.store(ctx, v);
            return pc + 1;
        }
    }

    /**
     * Object-literal spread: {@code { ...src }}. Copies enumerable own
     * properties from {@code src} onto {@code base}. LibJS uses this op for
     * spreads inside object literals; the {@link CopyOwnProperties} op (with
     * an {@code excluded} list) is used by the rest-binding lowering instead.
     */
    record PutBySpread(Operand base, Operand src) implements Op {
        @Override public Operation operation() { return Operation.PUT_BY_SPREAD; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object target = base.retrieve(ctx);
            Object source = src.retrieve(ctx);
            if (!(target instanceof JSObject targetObj)) {
                throw AbruptCompletion.typeError("Internal: PutBySpread target is not JSObject");
            }
            if (source == null || source == Undefined.VALUE) {
                // Spec: spreading null/undefined is a silent no-op.
            } else if (source instanceof JSObject sourceObj) {
                // ECMA-262 § 13.2.5 (Spec for { ...source }) → CopyDataProperties:
                // copy own ENUMERABLE properties only. Invoke accessor getters
                // so abrupt completions in user code propagate.
                String[] keys = sourceObj.properties().keySet().toArray(new String[0]);
                for (String key : keys) {
                    if (!key.isEmpty() && key.charAt(0) == '#') continue;
                    if (!sourceObj.isEnumerable(key)) continue;
                    Object raw = sourceObj.properties().get(key);
                    if (raw instanceof Accessor acc && acc.getter() != null) {
                        Object got = Interpreter.invokeFunction(acc.getter(), sourceObj, new Object[0], ctx);
                        targetObj.set(key, got);
                    } else if (!(raw instanceof Accessor)) {
                        targetObj.set(key, raw);
                    }
                }
            } else if (source instanceof JSArray arr) {
                for (int k = 0; k < arr.length(); k++) {
                    targetObj.set(String.valueOf(k), arr.get(k));
                }
            } else if (source instanceof String s) {
                for (int k = 0; k < s.length(); k++) {
                    targetObj.set(String.valueOf(k), String.valueOf(s.charAt(k)));
                }
            }
            return pc + 1;
        }
    }

    /**
     * Copy all own enumerable properties from {@code source} into {@code target},
     * skipping any name listed in {@code excluded}. Used for both object spread
     * ({@code excluded} empty) and object-rest destructuring ({@code excluded}
     * = the names already consumed by the surrounding pattern).
     */
    record CopyOwnProperties(Operand target, Operand source, String[] excluded) implements Op {
        @Override public Operation operation() { return Operation.COPY_OBJECT_EXCLUDING_PROPERTIES; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object t = target.retrieve(ctx);
            Object s = source.retrieve(ctx);
            if (!(t instanceof JSObject targetObj)) {
                throw AbruptCompletion.typeError("Internal: CopyOwnProperties target is not JSObject");
            }
            java.util.Set<String> ex = excluded.length == 0
                ? java.util.Set.of()
                : new java.util.HashSet<>(java.util.Arrays.asList(excluded));
            if (s == null || s == Undefined.VALUE) {
                // no-op
            } else if (s instanceof JSObject sourceObj) {
                // ECMA-262 § 13.2.5 CopyDataProperties calls [[OwnPropertyKeys]]
                // and for each, [[GetOwnProperty]] then [[Get]] (which invokes
                // accessor getters). We need to invoke getters here so abrupt
                // completions in user-defined getters propagate. Snapshot keys
                // first since the getter may mutate the source.
                String[] keys = sourceObj.properties().keySet().toArray(new String[0]);
                for (String key : keys) {
                    if (!key.isEmpty() && key.charAt(0) == '#') continue;
                    if (ex.contains(key)) continue;
                    // ECMA-262 § 13.2.5 CopyDataProperties only copies
                    // ENUMERABLE own properties.
                    if (!sourceObj.isEnumerable(key)) continue;
                    Object raw = sourceObj.properties().get(key);
                    if (raw instanceof Accessor acc && acc.getter() != null) {
                        Object got = Interpreter.invokeFunction(acc.getter(), sourceObj, new Object[0], ctx);
                        targetObj.set(key, got);
                    } else if (!(raw instanceof Accessor)) {
                        targetObj.set(key, raw);
                    }
                    // Pure setter-only accessors: omit (matches V8) — value is
                    // undefined-ish from CopyDataProperties' perspective.
                }
            } else if (s instanceof JSArray arr) {
                for (int k = 0; k < arr.length(); k++) {
                    String key = String.valueOf(k);
                    if (!ex.contains(key)) targetObj.set(key, arr.get(k));
                }
            } else if (s instanceof String str) {
                for (int k = 0; k < str.length(); k++) {
                    String key = String.valueOf(k);
                    if (!ex.contains(key)) targetObj.set(key, String.valueOf(str.charAt(k)));
                }
            }
            return pc + 1;
        }
    }

    /** Create a fresh array with the given initial elements. */
    record NewArray(Variable dst, Operand[] elements) implements Op {
        @Override public Operation operation() { return Operation.NEW_ARRAY; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object[] vals = new Object[elements.length];
            for (int k = 0; k < vals.length; k++) vals[k] = elements[k].retrieve(ctx);
            dst.store(ctx, new JSArray(vals));
            return pc + 1;
        }
    }

    /**
     * Sentinel value used inside {@link NewPrimitiveArray#elements} (and the
     * deferred-evaluation slots of {@link NewArray}) to represent an array
     * literal hole — {@code [,,,]} in source. Distinct from
     * {@link Undefined#VALUE} (which is an explicit assignment) and from
     * {@code null} (which represents the JS {@code null} literal). The
     * disassembler renders this as {@code <empty>}.
     */
    Object HOLE = new Object() {
        @Override public String toString() { return "<empty>"; }
    };

    /**
     * Create a fresh array with the given primitive-only elements (numbers,
     * booleans, null). LibJS specializes this case: the elements are inlined
     * in the dump (e.g. {@code elements:[1, 2.5, true, null]}) and DON'T go
     * through the executable's constants pool.
     *
     * <p>Use {@link NewArray} for any element that's not a number / bool /
     * null literal — including {@code undefined}, strings, identifiers, or
     * sub-expressions.
     */
    record NewPrimitiveArray(Variable dst, Object[] elements) implements Op {
        @Override public Operation operation() { return Operation.NEW_PRIMITIVE_ARRAY; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public int interpret(InterpContext ctx, int pc) {
            dst.store(ctx, new JSArray(elements.clone()));
            return pc + 1;
        }
    }

    /**
     * Append to a JSArray. If {@code isSpread} is false, push {@code src} as
     * a single element. If true, iterate {@code src} as an iterable and push
     * each element.
     */
    record ArrayAppend(Variable dst, Operand src, boolean isSpread) implements Op {
        @Override public Operation operation() { return Operation.ARRAY_APPEND; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object dstVal = dst.retrieve(ctx);
            if (!(dstVal instanceof JSArray arr)) {
                throw AbruptCompletion.typeError("Internal: ArrayAppend on non-JSArray: " + dstVal);
            }
            Object srcVal = src.retrieve(ctx);
            if (isSpread) {
                if (srcVal instanceof JSArray srcArr) {
                    for (Object e : srcArr.elements()) arr.push(e);
                } else if (srcVal instanceof String s) {
                    for (int k = 0; k < s.length(); k++) arr.push(String.valueOf(s.charAt(k)));
                } else {
                    // ECMA-262 § 13.2.4.1 ArrayAccumulation for SpreadElement —
                    // calls GetIterator(spreadObj) then loops IteratorStep +
                    // IteratorValue. User errors (next() throwing, etc.) must
                    // propagate verbatim (Test262 tests assert this).
                    spreadIterableInto(srcVal, arr, ctx);
                }
            } else {
                arr.push(srcVal);
            }
            return pc + 1;
        }
    }

    private static boolean isDefaultArrayIterator(Object v) {
        return Realm.defaultArrayIterator != null && v == Realm.defaultArrayIterator;
    }

    private static boolean isDefaultStringIterator(Object v) {
        return Realm.defaultStringIterator != null && v == Realm.defaultStringIterator;
    }

    private static void spreadIterableInto(Object srcVal, JSArray arr, InterpContext ctx) {
        Object iterMethod = AbstractOps.getProperty(srcVal,
            Realm.wellKnownIterator != null ? Realm.wellKnownIterator.asPropertyKey() : "@@iterator");
        if (!(iterMethod instanceof JSFunction iterFn)) {
            throw AbruptCompletion.typeError("cannot spread non-iterable: " + srcVal);
        }
        Object iter = Interpreter.invokeFunction(iterFn, srcVal, new Object[0], ctx);
        Object nextFn = AbstractOps.getProperty(iter, "next");
        if (!(nextFn instanceof JSFunction nfn)) {
            throw AbruptCompletion.typeError("iterator.next is not callable");
        }
        while (true) {
            Object result = Interpreter.invokeFunction(nfn, iter, new Object[0], ctx);
            if (AbstractOps.toBoolean(AbstractOps.getProperty(result, "done"))) break;
            arr.push(AbstractOps.getProperty(result, "value"));
        }
    }

    /**
     * Like {@link Call} but {@code arguments} is a single array operand whose
     * elements are unpacked as positional arguments. Used when a call site
     * contains a spread.
     */
    record CallWithArgumentArray(Variable dst, Operand callee, Operand thisValue,
                                  Operand arguments, String expressionString, CallSite cache) implements Op {
        @Override public Operation operation() { return Operation.CALL_WITH_ARGUMENT_ARRAY; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object calleeVal = callee.retrieve(ctx);
            if (!(calleeVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("not callable: " + calleeVal);
            }
            Object argsVal = arguments.retrieve(ctx);
            if (!(argsVal instanceof JSArray argsArr)) {
                throw AbruptCompletion.typeError("spread call requires an array");
            }
            Object[] argValues = argsArr.elements().toArray();
            Object thisVal = thisValue.retrieve(ctx);
            dst.store(ctx, Interpreter.invokeFunction(fn, thisVal, argValues, ctx));
            return pc + 1;
        }
    }

    /**
     * Create the rest-parameter array for a function. Slices the call's
     * arguments from {@code restIndex} onwards into a fresh JSArray.
     */
    record CreateRestParams(Variable dst, int restIndex) implements Op {
        @Override public Operation operation() { return Operation.CREATE_REST_PARAMS; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object[] callArgs = ctx.args();
            JSArray rest = new JSArray();
            for (int k = restIndex; k < callArgs.length; k++) rest.push(callArgs[k]);
            dst.store(ctx, rest);
            return pc + 1;
        }
    }

    /**
     * Internal shim: materialize an iterable into a JSArray of values eagerly,
     * for the index-loop {@code for-of} lowering. Supports JSArray and String.
     * Not LibJS-shaped — LibJS iterates lazily via {@link GetIterator} +
     * {@link IteratorNextUnpack}.
     */
    record MaterializeIterable(Variable dst, Operand source) implements Op {
        @Override public Operation operation() { return Operation.MATERIALIZE_ITERABLE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object srcVal = source.retrieve(ctx);
            JSArray out = new JSArray();
            if (srcVal instanceof JSArray a) {
                for (Object e : a.elements()) out.push(e);
            } else if (srcVal instanceof String s) {
                for (int k = 0; k < s.length(); k++) out.push(String.valueOf(s.charAt(k)));
            } else {
                throw AbruptCompletion.typeError("not iterable: " + srcVal);
            }
            dst.store(ctx, out);
            return pc + 1;
        }
    }

    /**
     * GetIterator(obj, "sync") split across three destination registers, per
     * LibJS bytecode shape. Equivalent to spec's GetIterator: reads
     * {@code @@iterator}, calls it on {@code iterable}, then loads
     * {@code .next} and seeds the done flag. Used by the iterator-protocol
     * destructuring lowering.
     */
    record GetIterator(Variable iteratorObject,
                       Variable iteratorNext,
                       Variable iteratorDone,
                       Operand iterable,
                       boolean isAwait) implements Op {
        /** Convenience constructor for sync for-of (await defaults to false). */
        public GetIterator(Variable iteratorObject, Variable iteratorNext,
                           Variable iteratorDone, Operand iterable) {
            this(iteratorObject, iteratorNext, iteratorDone, iterable, /* isAwait */ false);
        }
        @Override public Operation operation() { return Operation.GET_ITERATOR; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // ECMA-262 § 7.4.2 / § 7.4.3 GetIterator: look up @@iterator,
            // call it on the receiver, and unpack iterator + next + done.
            //
            // We keep two paths:
            //  - Fast path for built-in JSArray / String — uses the Java
            //    IteratorState shim. Avoids the overhead of allocating an
            //    iterator JSObject per for-of, and is what a long tail of
            //    existing tests already pass against.
            //  - Spec path via @@iterator for everything else: user-defined
            //    iterables, Map/Set, custom @@iterator overrides.
            Object srcVal = iterable.retrieve(ctx);
            if (srcVal == null || srcVal == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Cannot iterate over " + (srcVal == null ? "null" : "undefined"));
            }
            // Fast path — these are the overwhelming majority of for-of/spread
            // sources and the Java-side state machine matches their semantics
            // exactly. Only takes when the relevant prototype's @@iterator
            // hasn't been monkey-patched (test262 has tests that override
            // {@code Array.prototype[Symbol.iterator]} and assert custom
            // iteration order — those need the spec @@iterator path).
            String iterKeyFast = Realm.wellKnownIterator != null
                ? Realm.wellKnownIterator.asPropertyKey() : null;
            if (srcVal instanceof JSArray a
                && (iterKeyFast == null
                    || Realm.arrayPrototype == null
                    || isDefaultArrayIterator(Realm.arrayPrototype.get(iterKeyFast)))) {
                Interpreter.IteratorState state = Interpreter.IteratorState
                    .ofList(new java.util.ArrayList<>(a.elements()));
                iteratorObject.store(ctx, state);
                iteratorNext.store(ctx, Undefined.VALUE);
                iteratorDone.store(ctx, false);
                return pc + 1;
            }
            if (srcVal instanceof String s
                && (iterKeyFast == null
                    || Realm.stringPrototype == null
                    || isDefaultStringIterator(Realm.stringPrototype.get(iterKeyFast)))) {
                java.util.List<Object> chars = new java.util.ArrayList<>(s.length());
                for (int k = 0; k < s.length(); k++) chars.add(String.valueOf(s.charAt(k)));
                Interpreter.IteratorState state = Interpreter.IteratorState.ofList(chars);
                iteratorObject.store(ctx, state);
                iteratorNext.store(ctx, Undefined.VALUE);
                iteratorDone.store(ctx, false);
                return pc + 1;
            }
            // Spec path. for-await-of (ECMA-262 § 14.7.5.3 step 1.a): try
            // @@asyncIterator first; fall back to @@iterator with implicit
            // sync→async wrapping (our Promise.resolve unwrap is inline).
            Object method = null;
            if (isAwait && Realm.wellKnownAsyncIterator != null) {
                method = AbstractOps.getProperty(srcVal, Realm.wellKnownAsyncIterator.asPropertyKey());
                if (method == Undefined.VALUE || method == null) method = null;
            }
            if (method == null) {
                String iterKey = Realm.wellKnownIterator != null
                    ? Realm.wellKnownIterator.asPropertyKey() : "@@iterator";
                method = AbstractOps.getProperty(srcVal, iterKey);
            }
            if (!(method instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("value is not iterable: " + AbstractOps.toString(srcVal));
            }
            Object iterResult = Interpreter.invokeFunction(fn, srcVal, new Object[0], ctx);
            if (!(iterResult instanceof JSObject)) {
                throw AbruptCompletion.typeError("@@iterator method did not return an object");
            }
            Object nextMethod = AbstractOps.getProperty(iterResult, "next");
            iteratorObject.store(ctx, iterResult);
            iteratorNext.store(ctx, nextMethod);
            iteratorDone.store(ctx, false);
            return pc + 1;
        }
    }

    /**
     * Call {@code iterator_next.call(iterator_object)} and unpack the result
     * record into {@code dst_value} and {@code dst_done}.
     */
    record IteratorNextUnpack(Variable dstValue,
                              Variable dstDone,
                              Operand iteratorObject,
                              Operand iteratorNext,
                              Operand iteratorDone,
                              boolean isAwait) implements Op {
        /** Convenience for sync for-of. */
        public IteratorNextUnpack(Variable dstValue, Variable dstDone,
                                  Operand iteratorObject, Operand iteratorNext,
                                  Operand iteratorDone) {
            this(dstValue, dstDone, iteratorObject, iteratorNext, iteratorDone, /* isAwait */ false);
        }
        @Override public Operation operation() { return Operation.ITERATOR_NEXT_UNPACK; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // ECMA-262 § 7.4.4 IteratorNext / § 7.4.5 IteratorComplete /
            // § 7.4.6 IteratorValue:
            //   1. result = ? Call(iteratorRecord.[[NextMethod]], iteratorRecord.[[Iterator]]).
            //   2. If result is not Object, throw TypeError.
            //   3. done = ToBoolean(? Get(result, "done")).
            //   4. value = ? Get(result, "value").
            Object obj = iteratorObject.retrieve(ctx);
            // Fast path: the Java IteratorState shim used for built-in
            // JSArray/String when no @@iterator method is present.
            if (obj instanceof Interpreter.IteratorState state) {
                if (state.index < state.values.size()) {
                    Object v = state.values.get(state.index);
                    // ECMA-262 § 23.1.5 Array Iterator's [[NextMethod]] reads
                    // the indexed property — array holes become `undefined`
                    // because [[Get]] on a missing key returns undefined.
                    // Without this coercion, `[, ] | for-of`, destructuring
                    // defaults like `[x = 23] = [,]`, etc. observe the HOLE
                    // sentinel and fail to apply defaults.
                    if (v == HOLE) v = Undefined.VALUE;
                    dstValue.store(ctx, v);
                    state.index++;
                    dstDone.store(ctx, false);
                } else {
                    dstValue.store(ctx, Undefined.VALUE);
                    dstDone.store(ctx, true);
                }
                return pc + 1;
            }
            // Spec path: call iterator.next() and unpack {value, done}.
            Object nextFn = iteratorNext.retrieve(ctx);
            if (!(nextFn instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("iterator.next is not callable");
            }
            Object result = Interpreter.invokeFunction(fn, obj, new Object[0], ctx);
            // ECMA-262 § 14.7.5.3 for-await-of step 1.b.i: Await the
            // iterator-result Promise. {@code Promise.resolve} performs
            // thenable assimilation (a non-Promise object with a
            // callable {@code .then} gets adopted), so wrap the result
            // in a fresh promise and inspect its settled state. Real
            // Promises short-circuit through {@code resolvePromise}.
            if (isAwait) {
                JSObject p;
                if (Realm.isPromise(result)) {
                    p = (JSObject) result;
                } else if (result instanceof JSObject) {
                    p = Realm.createPromise();
                    Realm.resolvePromise(p, result, ctx);
                } else {
                    p = null;
                }
                if (p != null) {
                    String state = (String) p.properties().get(Realm.PROM_STATE);
                    Object inner = p.properties().get(Realm.PROM_RESULT);
                    if ("fulfilled".equals(state)) result = inner;
                    else if ("rejected".equals(state)) throw new AbruptCompletion(inner);
                    else throw AbruptCompletion.typeError(
                        "for-await: iterator.next() returned a still-pending Promise");
                }
            }
            if (!(result instanceof JSObject)) {
                throw AbruptCompletion.typeError("iterator.next() returned non-object");
            }
            // § 7.4.5 IteratorComplete / § 7.4.6 IteratorValue: done first.
            Object done = AbstractOps.getProperty(result, "done");
            Object value = AbstractOps.getProperty(result, "value");
            // for-await also awaits {@code value} per § 14.7.5.3 step 1.b.iii.
            if (isAwait && Realm.isPromise(value)) {
                JSObject pv = (JSObject) value;
                String state = (String) pv.properties().get(Realm.PROM_STATE);
                Object inner = pv.properties().get(Realm.PROM_RESULT);
                if ("fulfilled".equals(state)) value = inner;
                else if ("rejected".equals(state)) throw new AbruptCompletion(inner);
                else throw AbruptCompletion.typeError(
                    "for-await: yielded value is a still-pending Promise");
            }
            dstValue.store(ctx, value);
            dstDone.store(ctx, AbstractOps.toBoolean(done));
            return pc + 1;
        }
    }

    /**
     * Drain the remainder of an open iterator (already produced by
     * {@link GetIterator}) into a fresh JSArray.
     */
    record IteratorToArray(Variable dst,
                           Operand iteratorObject,
                           Operand iteratorNext,
                           Operand iteratorDone) implements Op {
        @Override public Operation operation() { return Operation.ITERATOR_TO_ARRAY; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object obj = iteratorObject.retrieve(ctx);
            // Fast path: Java IteratorState shim.
            if (obj instanceof Interpreter.IteratorState state) {
                JSArray out = new JSArray();
                while (state.index < state.values.size()) {
                    out.push(state.values.get(state.index++));
                }
                dst.store(ctx, out);
                return pc + 1;
            }
            // Spec path: drain the iterator via .next() until done.
            Object nextFn = iteratorNext.retrieve(ctx);
            if (!(nextFn instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("iterator.next is not callable");
            }
            JSArray out = new JSArray();
            while (true) {
                Object result = Interpreter.invokeFunction(fn, obj, new Object[0], ctx);
                if (!(result instanceof JSObject)) {
                    throw AbruptCompletion.typeError("iterator.next() returned non-object");
                }
                if (AbstractOps.toBoolean(AbstractOps.getProperty(result, "done"))) break;
                out.push(AbstractOps.getProperty(result, "value"));
            }
            dst.store(ctx, out);
            return pc + 1;
        }
    }

    /**
     * IteratorClose: invoke {@code .return} on the iterator if present. The
     * {@code completion_value} is the completion record being propagated
     * through the close (used by abrupt completions inside for-of bodies).
     */
    record IteratorClose(Operand iteratorObject,
                         Operand iteratorNext,
                         Operand iteratorDone,
                         Operand completionValue) implements Op {
        @Override public Operation operation() { return Operation.ITERATOR_CLOSE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // ECMA-262 § 7.4.10 IteratorClose:
            //   1. Let iterator = iteratorRecord.[[Iterator]].
            //   2. returnMethod = ? GetMethod(iterator, "return").
            //   3. If returnMethod is undefined, return.
            //   4. innerResult = Call(returnMethod, iterator).
            //   5. (Errors from innerResult are swallowed if the surrounding
            //      completion is already abrupt; see step 5b. We don't yet
            //      have completion-record threading here, so we just call
            //      and let any throw propagate.)
            Object iter = iteratorObject.retrieve(ctx);
            // Java IteratorState shim has no return-hook; nothing to do.
            if (iter instanceof Interpreter.IteratorState) return pc + 1;
            if (!(iter instanceof JSObject)) return pc + 1;
            Object returnMethod = AbstractOps.getProperty(iter, "return");
            if (returnMethod instanceof JSFunction fn) {
                Interpreter.invokeFunction(fn, iter, new Object[0], ctx);
            }
            return pc + 1;
        }
    }

    /**
     * Materialize an object's enumerable property names into a JSArray of
     * strings (for {@code for-in}).
     */
    record KeysOf(Variable dst, Operand source) implements Op {
        @Override public Operation operation() { return Operation.OBJECT_PROPERTY_ITERATOR_NEXT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object srcVal = source.retrieve(ctx);
            JSArray out = new JSArray();
            if (srcVal == null || srcVal == Undefined.VALUE) {
                // for-in over null/undefined silently iterates zero times.
            } else if (srcVal instanceof JSObject o) {
                // ECMA-262 § 14.7.5.6 for-in only enumerates ENUMERABLE keys.
                // Private '#' keys are skipped per § 15.7.1.4.
                for (String k : o.properties().keySet()) {
                    if (!k.isEmpty() && k.charAt(0) == '#') continue;
                    if (!o.isEnumerable(k)) continue;
                    out.push(k);
                }
            } else if (srcVal instanceof JSArray a) {
                for (int k = 0; k < a.length(); k++) out.push(String.valueOf(k));
            } else if (srcVal instanceof String s) {
                for (int k = 0; k < s.length(); k++) out.push(String.valueOf(k));
            }
            dst.store(ctx, out);
            return pc + 1;
        }
    }

    // ============================================================
    //  Computed property access
    // ============================================================

    /**
     * {@code dst = base[property]} where property is a computed value.
     * {@code baseIdentifier} is a non-null source-side qualified name for the
     * base (e.g. {@code "x"} for {@code x[3]}) included in dump output for
     * diagnostics; null when the base is an arbitrary expression.
     */
    record GetByValue(Variable dst, Operand base, Operand property, String baseIdentifier) implements Op {
        @Override public Operation operation() { return Operation.GET_BY_VALUE; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object b = base.retrieve(ctx);
            Object key = property.retrieve(ctx);
            // Fast path: Array base + integer-valued numeric key. AbstractOps
            // .getProperty does the same direct-index, but inlining here saves
            // a frame and lets the JIT specialize the dispatch in tight
            // for/array loops (acorn's tokenizer pattern). Mirrors LibJS's
            // GetByValue Int32 fast path (Interpreter.cpp:955).
            if (b instanceof JSArray arr && key instanceof Number n) {
                double d = n.doubleValue();
                int idx = (int) d;
                if (idx == d && idx >= 0 && idx < arr.length()) {
                    Object v = arr.get(idx);
                    if (!(v instanceof Accessor)) {
                        dst.store(ctx, v);
                        return pc + 1;
                    }
                }
            }
            // Fast path: String base + integer-valued numeric key, e.g.
            // `s[i]` in a char-by-char scanner (acorn's lexer reads chars
            // this way after the `charCodeAt` route). Equivalent to
            // String.prototype indexed access — no method call needed.
            if (b instanceof String s && key instanceof Number n) {
                double d = n.doubleValue();
                int idx = (int) d;
                if (idx == d && idx >= 0 && idx < s.length()) {
                    dst.store(ctx, String.valueOf(s.charAt(idx)));
                    return pc + 1;
                }
            }
            // Fast path: JSObject base + String key (lodash's `obj[k]` pattern).
            // Skips the megamorphic dispatch in getProperty.
            if (b instanceof JSObject obj && key instanceof String k
                    && (k.isEmpty() || k.charAt(0) != '#')) {
                Object v = obj.get(k);
                if (!(v instanceof Accessor)) {
                    dst.store(ctx, v);
                    return pc + 1;
                }
            }
            Object v = AbstractOps.getProperty(b, key);
            if (v instanceof Accessor acc && acc.getter() != null) {
                v = Interpreter.invokeFunction(acc.getter(), b, new Object[0], ctx);
            }
            dst.store(ctx, v);
            return pc + 1;
        }
    }

    /**
     * {@code base[property] = src} where property is a computed value.
     * {@code kind} is {@link PutByValueKind#NORMAL} for member assignments,
     * {@link PutByValueKind#OWN} for object-literal computed-property
     * initializers, and {@link PutByValueKind#GETTER}/{@link
     * PutByValueKind#SETTER} for object-literal computed accessors.
     * {@code baseIdentifier} is a non-null source-side qualified name for
     * the base (e.g. {@code "o"} for {@code o[k] = v}); LibJS appends it as
     * {@code (o[reg])} to the dump.
     */
    record PutByValue(Operand base, Operand property, Operand src,
                      PutByValueKind kind, String baseIdentifier) implements Op {
        @Override public Operation operation() { return Operation.PUT_BY_VALUE; }

        // Backward-compatible 3-arg constructor — defaults to (Normal, null).
        public PutByValue(Operand base, Operand property, Operand src) {
            this(base, property, src, PutByValueKind.NORMAL, null);
        }

        @Override public int interpret(InterpContext ctx, int pc) {
            Object b = base.retrieve(ctx);
            Object key = property.retrieve(ctx);
            Object value = src.retrieve(ctx);
            switch (kind) {
                case GETTER, SETTER -> {
                    if (!(b instanceof JSObject jo)) {
                        throw AbruptCompletion.typeError("Internal: PutByValue accessor target not JSObject");
                    }
                    JSFunction fn = (value instanceof JSFunction f) ? f : null;
                    Accessor acc = (kind == PutByValueKind.GETTER)
                        ? new Accessor(fn, null)
                        : new Accessor(null, fn);
                    String keyStr = key instanceof String ss ? ss
                        : key instanceof JSSymbol sym ? sym.asPropertyKey()
                        : AbstractOps.toString(key);
                    Object existing = jo.get(keyStr);
                    if (existing instanceof Accessor prior) acc = prior.merge(acc);
                    jo.set(keyStr, acc);
                }
                case OWN -> AbstractOps.setProperty(b, key, value);
                case NORMAL -> {
                    Object existing = AbstractOps.getProperty(b, key);
                    if (existing instanceof Accessor acc) {
                        if (acc.setter() != null) {
                            Interpreter.invokeFunction(acc.setter(), b, new Object[]{value}, ctx);
                        }
                    } else {
                        AbstractOps.setProperty(b, key, value);
                    }
                }
            }
            return pc + 1;
        }
    }

    enum PutByValueKind {
        NORMAL("Normal"),
        OWN("Own"),
        GETTER("Getter"),
        SETTER("Setter");

        private final String displayName;
        PutByValueKind(String name) { this.displayName = name; }
        @Override public String toString() { return displayName; }
    }

    /**
     * Dynamic import: {@code import(specifier, options)}. Resolves to a
     * promise of the module namespace. {@code options} carries the import
     * attributes object (e.g. {@code { with: { type: "json" } }}); for
     * single-arg dynamic imports, the generator passes {@code Undefined}.
     *
     * <p>v1: requires the entry point (CLI / test runner / a module body)
     * to have published an {@link com.jimmyhmiller.harmonica.module.ModuleLoader}
     * via {@code ModuleLoader.setActive} so we know what referrer to
     * resolve against. Without that we reject with a TypeError.
     */
    record ImportCall(Variable dst, Operand specifier, Operand options) implements Op {
        @Override public Operation operation() { return Operation.IMPORT_CALL; }
        @Override public int interpret(InterpContext ctx, int pc) {
            // ECMA-262 § 13.3.10.2 Runtime Semantics: Evaluation
            //   ImportCall : import(AssignmentExpression)
            //   1-3: evaluate the AssignmentExpression and GetValue — that
            //   already happened when {@code specifier} was retrieved into
            //   its register, so abrupt completions propagated.
            //   4-8: build a promise, ToString the specifier, and hand off
            //   to HostImportModuleDynamically. We treat steps 5-6 inline:
            //   any abrupt during ToString rejects the promise rather than
            //   throwing synchronously.
            Object specVal = specifier.retrieve(ctx);
            String specStr;
            try {
                specStr = AbstractOps.toString(specVal);
            } catch (AbruptCompletion ac) {
                JSObject rejected = Realm.wrapInRejectedPromise(ac.value());
                dst.store(ctx, rejected);
                return pc + 1;
            }
            com.jimmyhmiller.harmonica.module.ModuleLoader.Active active =
                com.jimmyhmiller.harmonica.module.ModuleLoader.active();
            if (active == null) {
                JSObject rejected = Realm.wrapInRejectedPromise(Realm.makeError(
                    "TypeError",
                    "Dynamic import is not available in this context (no active module loader)"));
                dst.store(ctx, rejected);
                return pc + 1;
            }
            try {
                com.jimmyhmiller.harmonica.module.ModuleResolver.Resolved resolved =
                    com.jimmyhmiller.harmonica.module.ModuleResolver.resolveEsm(specStr, active.referrer());
                // ECMA-262 dynamic import is ESM. {@code formatForFile}
                // would return CJS for plain {@code .js} when the closest
                // package.json doesn't set "type": "module" (Node's
                // default), but test262 fixtures and most ESM modules
                // resolved by import() use ESM syntax. Honor the
                // resolver's hint when it picked ESM/JSON; fall back to
                // ESM when it would have routed to CJS.
                com.jimmyhmiller.harmonica.module.ModuleResolver.Format fmt =
                    resolved.format() == com.jimmyhmiller.harmonica.module.ModuleResolver.Format.CJS
                        ? com.jimmyhmiller.harmonica.module.ModuleResolver.Format.ESM
                        : resolved.format();
                com.jimmyhmiller.harmonica.module.ModuleRecord rec =
                    active.loader().load(resolved.path(), fmt);
                Object namespace = rec.effectiveExports();
                dst.store(ctx, Realm.wrapInPromise(namespace, ctx));
            } catch (java.io.IOException io) {
                JSObject rejected = Realm.wrapInRejectedPromise(Realm.makeError(
                    "TypeError",
                    "Module load failed: " + io.getMessage()));
                dst.store(ctx, rejected);
            } catch (AbruptCompletion ac) {
                // Parse/eval errors during module load surface as
                // AbruptCompletion from the loader's recursive interpret.
                dst.store(ctx, Realm.wrapInRejectedPromise(ac.value()));
            } catch (RuntimeException re) {
                dst.store(ctx, Realm.wrapInRejectedPromise(Realm.makeError(
                    "TypeError",
                    "Module load failed: " + re.getMessage())));
            }
            return pc + 1;
        }
    }

    /**
     * ECMAScript {@code ToPrimitive(value, "string")}. Used by computed
     * property-name evaluation in object literals, before {@link PutByValue}
     * installs the property — matches LibJS's lowering of
     * {@code { [k]: v }} and {@code { get [k]() {} }}. The destination may
     * alias the source (LibJS emits in-place coercion when the value is a
     * register, and dst == value when value is already a constant — the
     * coercion is a no-op for primitive constants but the op is still emitted
     * for byte-perfect dump parity).
     */
    record ToPrimitiveWithStringHint(Operand dst, Operand value) implements Op {
        @Override public Operation operation() { return Operation.TO_PRIMITIVE_WITH_STRING_HINT; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object v = value.retrieve(ctx);
            // Spec ToPrimitive(value, "string") — for our supported
            // primitive set the value is already primitive. JSObject would
            // call @@toPrimitive / toString / valueOf, but user-defined
            // conversions aren't yet supported.
            if (dst instanceof Variable dstVar) dstVar.store(ctx, v);
            return pc + 1;
        }
    }

    // ============================================================
    //  Calls — IC-bearing
    // ============================================================

    /**
     * Generic call. {@code dst = callee.call(thisValue, ...args)}.
     * Carries a {@link CallSite} IC on the call site.
     */
    record Call(
        Variable dst,
        Operand callee,
        Operand thisValue,
        Operand[] args,
        String expressionString,
        CallSite cache
    ) implements Op {
        @Override public Operation operation() { return Operation.CALL; }

        // Records auto-generate equals/hashCode that include arrays; we don't
        // want value-equality on call instructions (they're identity-keyed by
        // their site). Override to identity for predictability.
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }

        @Override public int interpret(InterpContext ctx, int pc) {
            Object calleeVal = callee.retrieve(ctx);
            if (!(calleeVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("not callable: " + calleeVal);
            }
            Object thisVal = thisValue.retrieve(ctx);
            Object[] argValues = Interpreter.acquireArgs(args.length);
            for (int k = 0; k < args.length; k++) argValues[k] = args[k].retrieve(ctx);
            try {
                dst.store(ctx, Interpreter.invokeFunction(fn, thisVal, argValues, ctx));
            } finally {
                Interpreter.releaseArgs(argValues);
            }
            return pc + 1;
        }
    }

    /**
     * Fused {@code dst = receiver.<property>(args...)}. Emitted by the
     * generator whenever the call site is a non-computed member expression
     * (the common {@code obj.method(...)} shape). Saves one full Op dispatch
     * cycle and one register write/read versus emitting separate
     * {@link GetById} + {@link Call}.
     *
     * <p>Carries its own {@link PropertyLookupCache} for the method lookup
     * and a {@link CallSite} for the call. The receiver is evaluated once
     * and re-used as the call's {@code this} value (per spec for member
     * calls).
     */
    record CallMethod(
        Variable dst,
        Operand receiver,
        String property,
        Operand[] args,
        String expressionString,
        com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache lookupCache,
        CallSite callCache
    ) implements Op {
        @Override public Operation operation() { return Operation.CALL; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object b = receiver.retrieve(ctx);
            // Shape-keyed IC for method lookup. Class methods live on the
            // prototype — without this, every method call repeats a full
            // proto walk + map probe.
            Object fnVal = null;
            if (b instanceof JSObject obj
                    && (property.isEmpty() || property.charAt(0) != '#')) {
                Shape s = obj.shape();
                int slot = lookupCache.lookup(s);
                if (slot >= 0) {
                    Object owner = lookupCache.ownerOf();
                    if (owner == null) {
                        fnVal = obj.getDirect(slot);
                    } else if (owner instanceof JSObject oj
                                && oj.shape() == lookupCache.ownerShapeOf()) {
                        fnVal = oj.getDirect(slot);
                    }
                }
                if (fnVal == null) {
                    // Miss: walk own then proto chain, installing on a hit.
                    Shape.PropertyMeta meta = s.lookup(property);
                    if (meta != null) {
                        Object v = obj.getDirect(meta.offset());
                        if (!(v instanceof Accessor)) {
                            lookupCache.install(s, meta.offset());
                            fnVal = v;
                        } else {
                            fnVal = v;
                        }
                    } else {
                        JSObject cursor = obj.proto();
                        while (cursor != null) {
                            Shape cs = cursor.shape();
                            Shape.PropertyMeta cm = cs.lookup(property);
                            if (cm != null) {
                                Object v = cursor.getDirect(cm.offset());
                                if (!(v instanceof Accessor)) {
                                    lookupCache.installProto(s, cursor, cs, cm.offset());
                                }
                                fnVal = v;
                                break;
                            }
                            cursor = cursor.proto();
                        }
                        if (fnVal == null) fnVal = Undefined.VALUE;
                    }
                }
            } else {
                fnVal = AbstractOps.getProperty(b, property);
            }
            if (fnVal instanceof Accessor acc && acc.getter() != null) {
                fnVal = Interpreter.invokeFunction(acc.getter(), b, new Object[0], ctx);
            }
            if (!(fnVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("not callable: " + fnVal);
            }
            Object[] argValues = Interpreter.acquireArgs(args.length);
            for (int k = 0; k < args.length; k++) argValues[k] = args[k].retrieve(ctx);
            try {
                dst.store(ctx, Interpreter.invokeFunction(fn, b, argValues, ctx));
            } finally {
                Interpreter.releaseArgs(argValues);
            }
            return pc + 1;
        }
    }

    /**
     * Specialized call: {@code receiver.charCodeAt(index)}. Emitted when the
     * generator sees a {@code <expr>.charCodeAt(<expr>)} CallExpression.
     * Hot in acorn's tokenizer and any character-by-character scanner.
     *
     * <p>Fast path runs entirely inline when {@code receiver} is a String —
     * no GetById on the string prototype, no Call dispatch, no native
     * trampoline. Falls back to the generic call only for monkey-patched
     * receivers or non-string types. Mirrors LibJS's {@code CallBuiltin
     * StringPrototypeCharCodeAt} dispatch (Bytecode/Builtins.h, Interpreter.cpp
     * execute_specialized_builtin_call).
     */
    record CallCharCodeAt(Variable dst, Operand receiver, Operand index) implements Op {
        @Override public Operation operation() { return Operation.CALL; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object base = receiver.retrieve(ctx);
            Object idx = index.retrieve(ctx);
            if (base instanceof String s && idx instanceof Number n) {
                double d = n.doubleValue();
                int i = (int) d;
                // Spec: returns NaN for out-of-range. ToInteger truncates.
                if (i == d && i >= 0 && i < s.length()) {
                    dst.store(ctx, AbstractOps.boxDouble(s.charAt(i)));
                    return pc + 1;
                }
                dst.store(ctx, AbstractOps.boxDouble(Double.NaN));
                return pc + 1;
            }
            // Fallback: do the spec-mandated GetMethod + Call. Covers
            // user-defined `charCodeAt` overrides on the receiver and
            // non-string receivers (which would coerce via ToString first).
            Object fn = AbstractOps.getProperty(base, "charCodeAt");
            if (!(fn instanceof JSFunction f)) {
                throw AbruptCompletion.typeError("not callable: " + fn);
            }
            Object result = Interpreter.invokeFunction(f, base, new Object[]{idx}, ctx);
            dst.store(ctx, result);
            return pc + 1;
        }
    }

    /**
     * Specialized call: {@code receiver.charAt(index)}. Same shape as
     * {@link CallCharCodeAt} — fast path returns a single-character String.
     */
    record CallCharAt(Variable dst, Operand receiver, Operand index) implements Op {
        @Override public Operation operation() { return Operation.CALL; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object base = receiver.retrieve(ctx);
            Object idx = index.retrieve(ctx);
            if (base instanceof String s && idx instanceof Number n) {
                double d = n.doubleValue();
                int i = (int) d;
                // Spec § 22.1.3.2: returns "" when index is out of range.
                if (i == d && i >= 0 && i < s.length()) {
                    dst.store(ctx, String.valueOf(s.charAt(i)));
                    return pc + 1;
                }
                dst.store(ctx, "");
                return pc + 1;
            }
            Object fn = AbstractOps.getProperty(base, "charAt");
            if (!(fn instanceof JSFunction f)) {
                throw AbruptCompletion.typeError("not callable: " + fn);
            }
            Object result = Interpreter.invokeFunction(f, base, new Object[]{idx}, ctx);
            dst.store(ctx, result);
            return pc + 1;
        }
    }

    /**
     * Specialized call: {@code receiver.push(value)} when {@code receiver}
     * is a JSArray. Acorn (and any AST-building parser) accumulates child
     * nodes via {@code list.push(node)} repeatedly. The fast path is one
     * {@code ArrayList.add} call; the fallback covers monkey-patched push
     * and non-array receivers.
     *
     * <p>Note: this is a single-arg specialization. Multi-arg push
     * (deopt to the general call) goes through the generic Call op.
     */
    record CallArrayPush(Variable dst, Operand receiver, Operand value) implements Op {
        @Override public Operation operation() { return Operation.CALL; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object base = receiver.retrieve(ctx);
            Object v = value.retrieve(ctx);
            if (base instanceof JSArray arr) {
                arr.push(v);
                dst.store(ctx, AbstractOps.boxDouble(arr.length()));
                return pc + 1;
            }
            Object fn = AbstractOps.getProperty(base, "push");
            if (!(fn instanceof JSFunction f)) {
                throw AbruptCompletion.typeError("not callable: " + fn);
            }
            dst.store(ctx, Interpreter.invokeFunction(f, base, new Object[]{v}, ctx));
            return pc + 1;
        }
    }

    /**
     * Specialized call: {@code receiver.slice(start)} or
     * {@code receiver.slice(start, end)} when {@code receiver} is a String
     * (acorn extracts token text from {@code this.input} this way 36 times
     * in its source — every parsed identifier / number / string literal).
     * Spec § 22.1.3.27: negative indices are relative to length; out-of-range
     * indices clamp; missing end means "to length".
     */
    record CallStringSlice(Variable dst, Operand receiver, Operand startArg, Operand endArg) implements Op {
        @Override public Operation operation() { return Operation.CALL; }
        @Override public int interpret(InterpContext ctx, int pc) {
            Object base = receiver.retrieve(ctx);
            Object startVal = startArg.retrieve(ctx);
            Object endVal = endArg == null ? Undefined.VALUE : endArg.retrieve(ctx);
            if (base instanceof String s
                && startVal instanceof Number sn
                && (endVal == Undefined.VALUE || endVal instanceof Number)) {
                int len = s.length();
                int from = clampSliceIndex(sn.doubleValue(), len);
                int to = (endVal == Undefined.VALUE)
                    ? len
                    : clampSliceIndex(((Number) endVal).doubleValue(), len);
                if (to < from) to = from;
                dst.store(ctx, s.substring(from, to));
                return pc + 1;
            }
            // Fallback: spec-compliant call through the string prototype.
            Object fn = AbstractOps.getProperty(base, "slice");
            if (!(fn instanceof JSFunction f)) {
                throw AbruptCompletion.typeError("not callable: " + fn);
            }
            Object[] args = (endArg == null)
                ? new Object[]{startVal}
                : new Object[]{startVal, endVal};
            dst.store(ctx, Interpreter.invokeFunction(f, base, args, ctx));
            return pc + 1;
        }
        private static int clampSliceIndex(double idx, int len) {
            if (Double.isNaN(idx)) return 0;
            int i = (int) idx;
            if (idx < 0) i = Math.max(len + i, 0);
            else i = Math.min(i, len);
            return i;
        }
    }

    /**
     * Direct {@code eval(...)} call. ES spec requires the eval'd code to see
     * the calling lexical scope, so this is a distinct opcode. Emitted by
     * the generator only when the callee is the unqualified Identifier
     * {@code eval} (matching LibJS).
     */
    record CallDirectEval(
        Variable dst,
        Operand callee,
        Operand thisValue,
        Operand[] args,
        String expressionString,
        CallSite cache
    ) implements Op {
        @Override public Operation operation() { return Operation.CALL_DIRECT_EVAL; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }

        @Override public int interpret(InterpContext ctx, int pc) {
            Object calleeVal = callee.retrieve(ctx);
            // ECMA-262 § 19.2.1 PerformEval, EvalMode::Direct: only fires
            // when the syntactic form `eval(...)` resolves to the realm's
            // %eval% intrinsic. If the caller shadowed `eval`, drop to a
            // normal call.
            Object globalEval = ctx.globals().get("eval");
            if (calleeVal == globalEval && calleeVal instanceof JSFunction) {
                Object[] argValues = new Object[args.length];
                for (int k = 0; k < args.length; k++) argValues[k] = args[k].retrieve(ctx);
                if (argValues.length == 0 || !(argValues[0] instanceof CharSequence cs)) {
                    dst.store(ctx, argValues.length == 0 ? Undefined.VALUE : argValues[0]);
                    return pc + 1;
                }
                String src = cs.toString();
                com.jimmyhmiller.harmonica.ast.Program ast;
                try {
                    ast = com.jimmyhmiller.harmonica.Parser.parse(src);
                } catch (Throwable th) {
                    throw new AbruptCompletion(Realm.makeError("SyntaxError",
                        th.getMessage() != null ? th.getMessage() : "parse error"));
                }
                Executable exe;
                try { exe = Generator.generate(ast); }
                catch (Throwable th) {
                    throw new AbruptCompletion(Realm.makeError("SyntaxError",
                        th.getMessage() != null ? th.getMessage() : "compile error"));
                }
                // Build a name→Cell map from the caller's local bindings —
                // eval'd code's identifier lookups walk this chain before
                // falling through to globals.
                java.util.Map<String, Cell> bindings = new java.util.HashMap<>();
                if (ctx.executable() != null && ctx.executable().localNames() != null) {
                    String[] names = ctx.executable().localNames();
                    Object[] locals = ctx.locals();
                    // Promote each named slot to a Cell. Locals are now raw
                    // by default — we only allocate the Cell wrapper when a
                    // closure (or eval) needs a stable reference to share
                    // mutation. cellAt does the in-place promote.
                    for (int i = 0; i < names.length && i < locals.length; i++) {
                        if (names[i] == null) continue;
                        bindings.put(names[i], ctx.cellAt(i));
                    }
                }
                InterpContext.DirectEvalScope scope = new InterpContext.DirectEvalScope(
                    bindings, ctx.directEvalScope());
                InterpContext evalCtx = new InterpContext(exe, new Object[0], 64, ctx.globals());
                evalCtx.setDirectEvalScope(scope);
                evalCtx.registers()[Variable.Register.THIS_VALUE_INDEX] = ctx.registers()[Variable.Register.THIS_VALUE_INDEX];
                for (String name : exe.hoistedVarNames()) ctx.globals().putIfAbsent(name, Undefined.VALUE);
                for (Executable.HoistedFunction h : exe.hoistedFunctions()) ctx.globals().put(h.name(), h.template());
                Object result = Interpreter.interpret(exe, evalCtx);
                dst.store(ctx, result);
                return pc + 1;
            }
            // Caller shadowed `eval` — fall back to a normal call.
            if (!(calleeVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("not callable: " + calleeVal);
            }
            Object thisVal = thisValue.retrieve(ctx);
            Object[] argValues = new Object[args.length];
            for (int k = 0; k < args.length; k++) argValues[k] = args[k].retrieve(ctx);
            dst.store(ctx, Interpreter.invokeFunction(fn, thisVal, argValues, ctx));
            return pc + 1;
        }
    }
}
