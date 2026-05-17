package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.JSBigInt;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.NativeBody;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.TypedArrayKind;
import com.jimmyhmiller.harmonica.bytecode.TypedArrayState;
import com.jimmyhmiller.harmonica.bytecode.TypedArrays;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 25.4 Atomics — atomic read-modify-write operations on
 * integer-typed views over (Shared)ArrayBuffer.
 *
 * <p>The harness is single-threaded — agent records and the wait queue are
 * stubs that match the spec's observable shape for single-agent code
 * (waitAsync returns a resolved promise; notify always wakes 0).
 */
public final class AtomicsBuiltin {

    private AtomicsBuiltin() {}

    public static void install(JSObject atomics) {
        install(atomics, "load",            2, AtomicsBuiltin::load);
        install(atomics, "store",           3, AtomicsBuiltin::store);
        install(atomics, "add",             3, mkBinop(AtomicsBuiltin::opAdd));
        install(atomics, "sub",             3, mkBinop(AtomicsBuiltin::opSub));
        install(atomics, "and",             3, mkBinop(AtomicsBuiltin::opAnd));
        install(atomics, "or",              3, mkBinop(AtomicsBuiltin::opOr));
        install(atomics, "xor",             3, mkBinop(AtomicsBuiltin::opXor));
        install(atomics, "exchange",        3, mkBinop(AtomicsBuiltin::opExchange));
        install(atomics, "compareExchange", 4, AtomicsBuiltin::compareExchange);
        install(atomics, "isLockFree",      1, AtomicsBuiltin::isLockFree);
        install(atomics, "wait",            4, AtomicsBuiltin::wait_);
        install(atomics, "waitAsync",       4, AtomicsBuiltin::waitAsync);
        install(atomics, "notify",          3, AtomicsBuiltin::notify_);
        install(atomics, "pause",           0, AtomicsBuiltin::pause);
    }

    private static void install(JSObject host, String name, int arity, NativeBody body) {
        JSFunction fn = new JSFunction(name, arity, body);
        fn.setNonConstructor(true);
        host.set(name, fn);
        host.setAttributes(name, (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
    }

    // -----------------------------------------------------------------
    // Validation helpers
    // -----------------------------------------------------------------

    private static TypedArrayState validateIntegerTypedArray(Object t, boolean requireWaitable, String op) {
        if (!(t instanceof JSObject jo)) {
            throw AbruptCompletion.typeError("Atomics." + op + ": typedArray must be a TypedArray");
        }
        TypedArrayState state = TypedArrays.stateOf(jo);
        if (state == null || state.kind == null) {
            throw AbruptCompletion.typeError("Atomics." + op + ": typedArray must be a TypedArray");
        }
        switch (state.kind) {
            case INT8: case UINT8: case INT16: case UINT16:
            case INT32: case UINT32: case BIGINT64: case BIGUINT64:
                break;
            default:
                throw AbruptCompletion.typeError(
                    "Atomics." + op + ": typedArray must be an integer TypedArray (got " + state.kind + ")");
        }
        if (requireWaitable && state.kind != TypedArrayKind.INT32
                && state.kind != TypedArrayKind.BIGINT64) {
            throw AbruptCompletion.typeError(
                "Atomics." + op + ": typedArray must be Int32Array or BigInt64Array");
        }
        if (state.outOfBounds()) {
            throw AbruptCompletion.typeError("Atomics." + op + ": typedArray is out of bounds");
        }
        return state;
    }

    private static long validateAtomicAccess(TypedArrayState state, Object indexArg, String op) {
        double d = AbstractOps.toIntegerOrInfinity(indexArg);
        // After coercion the underlying buffer may have been detached
        // by a poisoned valueOf — spec requires TypeError before the bounds
        // check (which would otherwise produce a misleading RangeError).
        if (state.rawBuffer() != null && state.rawBuffer().isDetached()) {
            throw AbruptCompletion.typeError(
                "Atomics." + op + ": buffer is detached");
        }
        if (d < 0 || d >= state.length()) {
            throw AbruptCompletion.rangeError("Atomics." + op + ": index out of range");
        }
        return (long) d;
    }

    // -----------------------------------------------------------------
    // Read / write
    // -----------------------------------------------------------------

    private static Object load(Object t, Object[] a, com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        TypedArrayState state = validateIntegerTypedArray(Realm.arg(a, 0), false, "load");
        long i = validateAtomicAccess(state, Realm.arg(a, 1), "load");
        return TypedArrays.loadElement(state, i);
    }

    private static Object store(Object t, Object[] a, com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        TypedArrayState state = validateIntegerTypedArray(Realm.arg(a, 0), false, "store");
        long i = validateAtomicAccess(state, Realm.arg(a, 1), "store");
        Object value = Realm.arg(a, 2);
        Object coerced = coerceForKind(state.kind, value, "store");
        // Value coercion may have detached the buffer too.
        if (state.rawBuffer() != null && state.rawBuffer().isDetached()) {
            throw AbruptCompletion.typeError("Atomics.store: buffer is detached");
        }
        TypedArrays.storeElement(state, i, coerced);
        return coerced;
    }

    // -----------------------------------------------------------------
    // CAS + RMW
    // -----------------------------------------------------------------

    private static Object compareExchange(Object t, Object[] a, com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        TypedArrayState state = validateIntegerTypedArray(Realm.arg(a, 0), false, "compareExchange");
        long i = validateAtomicAccess(state, Realm.arg(a, 1), "compareExchange");
        Object expected = coerceForKind(state.kind, Realm.arg(a, 2), "compareExchange");
        Object replacement = coerceForKind(state.kind, Realm.arg(a, 3), "compareExchange");
        if (state.rawBuffer() != null && state.rawBuffer().isDetached()) {
            throw AbruptCompletion.typeError("Atomics.compareExchange: buffer is detached");
        }
        // Spec compares expected via NumericToRawBytes — so the comparison is
        // truncation-aware. Truncate `expected` to the kind's storage range
        // before SameValue-ing against the loaded element.
        Object expectedNormalized = truncateForKind(state.kind, expected);
        synchronized (state.rawBuffer()) {
            Object current = TypedArrays.loadElement(state, i);
            if (sameValueZero(current, expectedNormalized)) {
                TypedArrays.storeElement(state, i, replacement);
            }
            return current;
        }
    }

    /** Reduce a coerced Number / BigInt to what storeElement+loadElement
     *  would round-trip — matches the spec's NumericToRawBytes truncation
     *  used for CAS comparisons. */
    private static Object truncateForKind(TypedArrayKind kind, Object value) {
        if (kind.bigInt) {
            // BigInt64 / BigUint64: reduce to 64-bit range.
            java.math.BigInteger bi = ((JSBigInt) value).value;
            java.math.BigInteger mod = bi.and(
                java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE));
            if (kind == TypedArrayKind.BIGINT64) {
                // sign-extend top bit
                if (mod.testBit(63)) {
                    mod = mod.subtract(java.math.BigInteger.ONE.shiftLeft(64));
                }
            }
            return new JSBigInt(mod);
        }
        double d = ((Number) value).doubleValue();
        long l = (long) d;
        switch (kind) {
            case INT8:    return (double) ((byte) l);
            case UINT8:
            case UINT8C:  return (double) (l & 0xFF);
            case INT16:   return (double) ((short) l);
            case UINT16:  return (double) (l & 0xFFFF);
            case INT32:   return (double) ((int) l);
            case UINT32:  return (double) (l & 0xFFFFFFFFL);
            default:      return value;
        }
    }

    @FunctionalInterface
    private interface BinaryRmw {
        Object apply(Object current, Object value, TypedArrayKind kind);
    }

    private static NativeBody mkBinop(BinaryRmw op) {
        return (t, a, c) -> {
            TypedArrayState state = validateIntegerTypedArray(Realm.arg(a, 0), false, "rmw");
            long i = validateAtomicAccess(state, Realm.arg(a, 1), "rmw");
            Object value = coerceForKind(state.kind, Realm.arg(a, 2), "rmw");
            if (state.rawBuffer() != null && state.rawBuffer().isDetached()) {
                throw AbruptCompletion.typeError("Atomics: buffer is detached");
            }
            synchronized (state.rawBuffer()) {
                Object current = TypedArrays.loadElement(state, i);
                Object next = op.apply(current, value, state.kind);
                TypedArrays.storeElement(state, i, next);
                return current;
            }
        };
    }

    private static Object opAdd(Object cur, Object val, TypedArrayKind k) {
        if (k.bigInt) {
            return new JSBigInt(((JSBigInt) cur).value.add(((JSBigInt) val).value));
        }
        return (double) ((long) ((Number) cur).doubleValue() + (long) ((Number) val).doubleValue());
    }
    private static Object opSub(Object cur, Object val, TypedArrayKind k) {
        if (k.bigInt) {
            return new JSBigInt(((JSBigInt) cur).value.subtract(((JSBigInt) val).value));
        }
        return (double) ((long) ((Number) cur).doubleValue() - (long) ((Number) val).doubleValue());
    }
    private static Object opAnd(Object cur, Object val, TypedArrayKind k) {
        if (k.bigInt) {
            return new JSBigInt(((JSBigInt) cur).value.and(((JSBigInt) val).value));
        }
        long a = (long) ((Number) cur).doubleValue(), b = (long) ((Number) val).doubleValue();
        return (double) (a & b);
    }
    private static Object opOr(Object cur, Object val, TypedArrayKind k) {
        if (k.bigInt) {
            return new JSBigInt(((JSBigInt) cur).value.or(((JSBigInt) val).value));
        }
        long a = (long) ((Number) cur).doubleValue(), b = (long) ((Number) val).doubleValue();
        return (double) (a | b);
    }
    private static Object opXor(Object cur, Object val, TypedArrayKind k) {
        if (k.bigInt) {
            return new JSBigInt(((JSBigInt) cur).value.xor(((JSBigInt) val).value));
        }
        long a = (long) ((Number) cur).doubleValue(), b = (long) ((Number) val).doubleValue();
        return (double) (a ^ b);
    }
    private static Object opExchange(Object cur, Object val, TypedArrayKind k) {
        return val;
    }

    // -----------------------------------------------------------------
    // Wait / notify (single-agent stubs that match spec shape)
    // -----------------------------------------------------------------

    private static Object isLockFree(Object t, Object[] a, com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        double size = AbstractOps.toIntegerOrInfinity(Realm.arg(a, 0));
        // Per spec, [[IsLockFree1/2/4/8]] are agent-internal flags. V8 returns
        // true for 1, 2, 4, 8 only.
        return size == 1 || size == 2 || size == 4 || size == 8;
    }

    private static Object wait_(Object t, Object[] a, com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        TypedArrayState state = validateIntegerTypedArray(Realm.arg(a, 0), true, "wait");
        // ECMA-262 § 25.4.10 Atomics.wait: shared-buffer check BEFORE index
        // coercion so poisoned-valueOf getters don't fire ahead of TypeError.
        if (state.rawBuffer() == null || !state.rawBuffer().shared) {
            throw AbruptCompletion.typeError(
                "Atomics.wait: typedArray must be backed by a SharedArrayBuffer");
        }
        long i = validateAtomicAccess(state, Realm.arg(a, 1), "wait");
        Object expected = coerceForKind(state.kind, Realm.arg(a, 2), "wait");
        Object timeoutArg = Realm.arg(a, 3);
        double timeout = (timeoutArg == Undefined.VALUE) ? Double.POSITIVE_INFINITY
            : AbstractOps.toNumber(timeoutArg);
        // ECMA-262 step 12: if [[AgentCanSuspend]] is false, throw TypeError.
        // The main agent in V8/Node can't suspend; test262 expects TypeError.
        throw AbruptCompletion.typeError(
            "Atomics.wait: cannot suspend in this agent");
    }

    private static Object waitAsync(Object t, Object[] a, com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        TypedArrayState state = validateIntegerTypedArray(Realm.arg(a, 0), true, "waitAsync");
        if (state.rawBuffer() == null || !state.rawBuffer().shared) {
            throw AbruptCompletion.typeError(
                "Atomics.waitAsync: typedArray must be backed by a SharedArrayBuffer");
        }
        long i = validateAtomicAccess(state, Realm.arg(a, 1), "waitAsync");
        Object expected = coerceForKind(state.kind, Realm.arg(a, 2), "waitAsync");
        // ECMA-262 § 25.4.11 step 8: timeout = ToNumber(timeout). NaN→+Inf,
        // negative→0, positive→max(value, 0).
        Object timeoutArg = Realm.arg(a, 3);
        double timeout = (timeoutArg == Undefined.VALUE)
            ? Double.POSITIVE_INFINITY
            : AbstractOps.toNumber(timeoutArg);
        if (Double.isNaN(timeout)) timeout = Double.POSITIVE_INFINITY;
        if (timeout < 0) timeout = 0;
        JSObject result = new JSObject();
        Object current = TypedArrays.loadElement(state, i);
        if (!sameValueZero(current, expected)) {
            result.set("async", false);
            result.set("value", "not-equal");
            return result;
        }
        // Spec step 13: if t = 0, return a non-async result with value
        // "timed-out" directly (not a promise).
        if (timeout == 0) {
            result.set("async", false);
            result.set("value", "timed-out");
            return result;
        }
        // Single-agent: resolve immediately with "timed-out" via a promise.
        result.set("async", true);
        JSObject promise = Realm.wrapInResolvedPromise("timed-out");
        result.set("value", promise);
        return result;
    }

    private static Object notify_(Object t, Object[] a, com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        TypedArrayState state = validateIntegerTypedArray(Realm.arg(a, 0), true, "notify");
        validateAtomicAccess(state, Realm.arg(a, 1), "notify");
        // ECMA-262 § 25.4.15 step 3: if count is undefined, c=+∞; else
        // c=max(ToIntegerOrInfinity(count), 0). Coercing a Symbol throws.
        Object countArg = Realm.arg(a, 2);
        if (countArg != Undefined.VALUE) {
            AbstractOps.toIntegerOrInfinity(countArg);
        }
        // No agents are waiting (single-agent runtime). Return 0.
        return 0.0;
    }

    private static Object pause(Object t, Object[] a, com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        // ECMA-262 § 25.4.16 Atomics.pause(N): if N is not undefined and
        // (Type(N) != Number or N is not an integer), throw TypeError.
        Object n = Realm.arg(a, 0);
        if (n != Undefined.VALUE) {
            if (!(n instanceof Number num)) {
                throw AbruptCompletion.typeError("Atomics.pause: iterationNumber must be a Number");
            }
            double d = num.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d)) {
                throw AbruptCompletion.typeError("Atomics.pause: iterationNumber must be an integral Number");
            }
        }
        return Undefined.VALUE;
    }

    // -----------------------------------------------------------------
    // Coercion + SameValueZero
    // -----------------------------------------------------------------

    private static Object coerceForKind(TypedArrayKind kind, Object value, String op) {
        if (kind.bigInt) {
            if (value instanceof JSBigInt) return value;
            if (value instanceof Number n) {
                throw AbruptCompletion.typeError(
                    "Atomics." + op + ": value must be a BigInt for BigInt TypedArrays");
            }
            return new JSBigInt(java.math.BigInteger.valueOf(
                (long) AbstractOps.toIntegerOrInfinity(value)));
        }
        if (value instanceof JSBigInt) {
            throw AbruptCompletion.typeError(
                "Atomics." + op + ": value must be a Number for non-BigInt TypedArrays");
        }
        return AbstractOps.toIntegerOrInfinity(value);
    }

    private static boolean sameValueZero(Object x, Object y) {
        if (x instanceof Number nx && y instanceof Number ny) {
            double dx = nx.doubleValue(), dy = ny.doubleValue();
            if (Double.isNaN(dx) && Double.isNaN(dy)) return true;
            return dx == dy;
        }
        if (x instanceof JSBigInt bx && y instanceof JSBigInt by) {
            return bx.value.equals(by.value);
        }
        return x == y || (x != null && x.equals(y));
    }
}
