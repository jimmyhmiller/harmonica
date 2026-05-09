package com.jimmyhmiller.harmonica.bytecode;

/**
 * The bytecode interpreter. Per-op virtual dispatch — each {@link Op}
 * subclass implements {@link Op#interpret(InterpContext, int)} and returns
 * the next pc. The loop is just:
 *
 * <pre>{@code
 * while (true) {
 *     try {
 *         pc = ops[pc].interpret(ctx, pc);
 *         if (pc == Op.FRAME_DONE) return ctx.registers()[RETURN_VALUE_INDEX];
 *     } catch (AbruptCompletion ex) { ... resume at handler ... }
 * }
 * }</pre>
 *
 * <p>This file holds the loop, frame setup, and the call helpers
 * {@link #invokeFunction} / {@link #invokeFunctionAsConstructor} that the
 * Op classes use to invoke nested functions.
 */
public final class Interpreter {

    private Interpreter() {}

    /**
     * Internal iterator state used by {@link Op.GetIterator},
     * {@link Op.IteratorNextUnpack}, and {@link Op.IteratorToArray}. Holds the
     * snapshot of values plus a mutable index. Only seen at runtime in
     * iterator-object slots — never escapes into user-visible JS values.
     */
    static final class IteratorState {
        final java.util.List<Object> values;
        int index;
        private IteratorState(java.util.List<Object> values) {
            this.values = values;
            this.index = 0;
        }
        static IteratorState ofList(java.util.List<Object> values) { return new IteratorState(values); }
    }

    public static Object interpret(Executable executable, Object[] args, int numberOfLocals) {
        InterpContext ctx = new InterpContext(executable, args, numberOfLocals);
        // Install standard-library prototypes and globals (idempotent for prototypes,
        // per-frame for globals — putIfAbsent so user shadowing is preserved).
        Realm.ensureBootstrapped(ctx.globals());
        // ECMA-262 § 19.4 / § 9.3 Realm Records: top-level `this` is the
        // realm's [[GlobalThisValue]] (the global object for non-module scripts).
        Object globalThisVal = ctx.globals().get("globalThis");
        if (globalThisVal != null) {
            ctx.registers()[Variable.Register.THIS_VALUE_INDEX] = globalThisVal;
        }
        // Pre-bind hoisted top-level `var` names to undefined.
        for (String name : executable.hoistedVarNames()) {
            ctx.globals().putIfAbsent(name, Undefined.VALUE);
        }
        // Materialize hoisted top-level FunctionDeclarations into globals. They
        // can't have captures (top-level scope has no enclosing locals), so the
        // template's capturedCells are left null.
        for (Executable.HoistedFunction h : executable.hoistedFunctions()) {
            ctx.globals().put(h.name(), h.template());
        }
        return interpret(executable, ctx);
    }

    /**
     * Invoke a function value with the given {@code this} and arguments. Sets
     * up the new frame's captures from {@code fn.capturedCells} so that
     * closure-captured outer locals share Cells with the enclosing scope.
     */
    public static Object invokeFunction(JSFunction fn, Object thisVal, Object[] args, InterpContext callerCtx) {
        // Inlined directly so the JIT doesn't have to inline a wrapper
        // ahead of the call dispatch. invokeFunctionImpl is the only
        // entry point now.
        return invokeFunctionImpl(fn, thisVal, args, callerCtx, /* newTarget */ Undefined.VALUE);
    }

    public static Object invokeFunctionAsConstructor(JSFunction fn, Object thisVal, Object[] args, InterpContext callerCtx) {
        return invokeFunctionImpl(fn, thisVal, args, callerCtx, /* newTarget */ fn);
    }

    /**
     * Per-thread current {@code new.target} value (ECMA-262 § 9.4.1). For
     * native function bodies, this is how we expose whether the current call
     * is a constructor call (e.g. so {@code Boolean}/{@code Number}/{@code String}
     * can wrap vs. coerce).
     */
    private static final ThreadLocal<Object> CURRENT_NEW_TARGET = ThreadLocal.withInitial(() -> Undefined.VALUE);

    /** True iff the currently-running native is being invoked via {@code new}. */
    public static boolean isNewCall() {
        return CURRENT_NEW_TARGET.get() != Undefined.VALUE;
    }

    /**
     * Per-thread pool of recyclable {@code Object[]} buffers used as args
     * arrays for {@code Op.Call.interpret}. Indexed by arity in
     * {@code [0..MAX_POOLED_ARITY]}. Each slot holds a small stack of
     * available buffers; depth bounded to keep memory tight.
     *
     * <p>Profile (lodash 5K workload): {@code new Object[args.length]} in
     * {@code Op.Call.interpret} was 43% of allocated bytes after the
     * InterpContext pool landed.
     */
    private static final int MAX_POOLED_ARITY = 8;
    private static final int POOL_DEPTH = 16;
    /**
     * Per-thread pool storage. Outer dimension = arity (0..MAX_POOLED_ARITY);
     * each slot is a lazily-allocated stack of buffers of that arity.
     */
    private static final class ArgsPool {
        final Object[][][] stacks = new Object[MAX_POOLED_ARITY + 1][][];
        final int[] sizes = new int[MAX_POOLED_ARITY + 1];
    }
    private static final ThreadLocal<ArgsPool> ARGS_POOL = ThreadLocal.withInitial(ArgsPool::new);

    /** Acquire an {@code Object[length]} buffer, reusing a pooled one if possible. */
    public static Object[] acquireArgs(int length) {
        if (length > MAX_POOLED_ARITY) return new Object[length];
        ArgsPool pool = ARGS_POOL.get();
        int sz = pool.sizes[length];
        if (sz == 0) return new Object[length];
        Object[][] stack = pool.stacks[length];
        Object[] buf = stack[--sz];
        stack[sz] = null;
        pool.sizes[length] = sz;
        return buf;
    }

    /** Return an args buffer to the pool. Caller must drop all references. */
    public static void releaseArgs(Object[] args) {
        int len = args.length;
        if (len > MAX_POOLED_ARITY) return;
        ArgsPool pool = ARGS_POOL.get();
        int sz = pool.sizes[len];
        if (sz >= POOL_DEPTH) return;
        Object[][] stack = pool.stacks[len];
        if (stack == null) {
            stack = new Object[POOL_DEPTH][];
            pool.stacks[len] = stack;
        }
        java.util.Arrays.fill(args, null);
        stack[sz] = args;
        pool.sizes[len] = sz + 1;
    }

    /**
     * Combined invoke — folds the previously 3-deep chain
     * (invokeFunction → invokeFunctionInternal → invokeFunctionDispatch) into
     * one method. Lodash exercises millions of small calls; the per-call
     * Java-frame overhead added 30%+ to runtime.
     */
    private static Object invokeFunctionImpl(JSFunction fn, Object thisVal, Object[] args,
                                              InterpContext callerCtx, Object newTarget) {
        if (fn.isNative()) {
            // Native bodies observe CURRENT_NEW_TARGET via Interpreter.isNewCall()
            // (e.g. String(...) vs `new String(...)` decide whether to wrap or
            // coerce). Save/restore around the call so a nested `new` doesn't
            // leak its new.target back to the caller. Skip the ThreadLocal
            // touch when the call isn't a constructor and the TL is already
            // undefined — the dominant case.
            if (newTarget == Undefined.VALUE && CURRENT_NEW_TARGET.get() == Undefined.VALUE) {
                return fn.nativeBody().call(thisVal, args, callerCtx);
            }
            Object priorNT = CURRENT_NEW_TARGET.get();
            CURRENT_NEW_TARGET.set(newTarget);
            try { return fn.nativeBody().call(thisVal, args, callerCtx); }
            finally { CURRENT_NEW_TARGET.set(priorNT); }
        }
        // Acquire a context from the per-Executable pool (or allocate fresh
        // if pool is empty). Profile (lodash 5K workload) showed
        // InterpContext + its registers/locals arrays were 68% of all bytes
        // allocated; pooling cuts most of that.
        InterpContext callCtx = InterpContext.acquire(fn.body(), args, fn.localCount(), callerCtx.globals());
        Cell[] captured = fn.capturedCells();
        if (captured != null) {
            int[] dst = fn.captureDestSlots();
            int n = captured.length;
            for (int k = 0; k < n; k++) {
                callCtx.installCapturedCell(dst[k], captured[k]);
            }
        }
        // ECMA-262 § 10.2.1.2 OrdinaryCallBindThis: in non-strict mode,
        // null/undefined `this` is replaced by the global object. Strict
        // functions see the call-site value verbatim. Arrow functions
        // resolve `this` through captures so their THIS register is not
        // observed — the assignment here is harmless for them.
        Object boundThis = thisVal;
        if (!fn.body().strictMode() && (boundThis == null || boundThis == Undefined.VALUE)) {
            Object globalThis = callerCtx.globals().get("globalThis");
            if (globalThis != null) boundThis = globalThis;
        }
        callCtx.registers()[Variable.Register.THIS_VALUE_INDEX] = boundThis;
        // ECMA-262 § 10.2.1: arrow functions inherit `this` and `new.target`
        // from the enclosing lexical environment. We approximate that by
        // overriding the call-site values from the caller's frame when the
        // callee is an arrow.
        if (fn.isArrow()) {
            // `this` capture
            callCtx.registers()[Variable.Register.THIS_VALUE_INDEX] =
                callerCtx.registers()[Variable.Register.THIS_VALUE_INDEX];
            // new.target capture
            callCtx.setNewTarget(callerCtx.newTarget());
        } else {
            callCtx.setNewTarget(newTarget);
        }
        if (fn.superConstructor() != null) callCtx.setSuperConstructor(fn.superConstructor());
        // ECMA-262 § 15.5.5 EvaluateGeneratorBody / § 27.6 AsyncGeneratorStart:
        // when called, a (sync or async) generator function does NOT execute
        // the body — it materializes a Generator (or AsyncGenerator) object
        // whose [[GeneratorContext]] is the suspended frame. The body runs
        // lazily on the first .next() call.
        if (fn.isGenerator()) {
            // ECMA-262 § 27.5.4 step 5: FunctionDeclarationInstantiation
            // (parameter destructuring, arguments-binding) runs at call
            // time. Only the suspendable body waits for .next(). If the
            // generator's prologueEndPc is set, run those leading ops
            // synchronously so destructuring throws propagate from the
            // call site.
            int prologueEnd = fn.body().prologueEndPc();
            if (prologueEnd > 0) {
                runRange(fn.body(), callCtx, 0, prologueEnd);
            }
            return Realm.makeGeneratorObject(fn.body(), callCtx, fn.isAsync(), prologueEnd);
        }
        // ECMA-262 § 27.7.5 AsyncFunctionStart: an async function call
        // unconditionally returns a Promise. v1 runs the body synchronously
        // (our Promises are synchronous, so awaits resolve inline).
        if (fn.isAsync()) {
            JSObject promise = Realm.createPromise();
            Object priorNT = CURRENT_NEW_TARGET.get();
            CURRENT_NEW_TARGET.set(newTarget);
            try {
                Object result = runLoop(fn.body(), callCtx);
                Realm.resolvePromise(promise, result, callerCtx);
            } catch (AbruptCompletion ac) {
                Realm.rejectPromise(promise, ac.value());
            } finally {
                CURRENT_NEW_TARGET.set(priorNT);
                callCtx.release();
            }
            return promise;
        }
        // newTarget save/restore was previously a separate method frame; fold
        // into this method to drop one Java stack level per call. Skip the
        // ThreadLocal touch entirely for the common case (newTarget = undefined
        // and CURRENT_NEW_TARGET is already undefined) — almost all calls.
        if (newTarget == Undefined.VALUE) {
            Object priorNT = CURRENT_NEW_TARGET.get();
            if (priorNT == Undefined.VALUE) {
                try { return runLoop(fn.body(), callCtx); }
                finally { callCtx.release(); }
            }
            CURRENT_NEW_TARGET.set(Undefined.VALUE);
            try { return runLoop(fn.body(), callCtx); }
            finally {
                CURRENT_NEW_TARGET.set(priorNT);
                callCtx.release();
            }
        }
        Object priorNT = CURRENT_NEW_TARGET.get();
        CURRENT_NEW_TARGET.set(newTarget);
        try { return runLoop(fn.body(), callCtx); }
        finally {
            CURRENT_NEW_TARGET.set(priorNT);
            callCtx.release();
        }
    }

    /**
     * Resume / start a generator's interpret loop. Runs until either
     * {@link Op#YIELD_DONE} (suspend at yield) or {@link Op#FRAME_DONE}
     * (return). On AbruptCompletion the exception propagates.
     *
     * @return {@code true} if the frame yielded; {@code false} if it
     *         completed via Return/End. The yielded value (or return value)
     *         is left on {@code ctx.yieldedValue()} (yield) or the
     *         RETURN_VALUE register (completion).
     */
    /**
     * Run ops in {@code [startPc, endPc)} on {@code ctx}. Used by
     * generator-call entry to execute the parameter-binding prologue
     * synchronously (so destructuring throws propagate from the call site).
     * Returns when pc reaches endPc; FRAME_DONE / YIELD_DONE inside the range
     * also break out (those would indicate the prologue tried to suspend or
     * return early — neither happens in practice for param init).
     */
    public static void runRange(Executable executable, InterpContext ctx, int startPc, int endPc) {
        Op[] ops = executable.ops();
        int pc = startPc;
        InterpContext prior = InterpContext.setCurrent(ctx);
        try {
            while (pc < endPc) {
                try {
                    pc = ops[pc].interpret(ctx, pc);
                    if (pc == Op.FRAME_DONE || pc == Op.YIELD_DONE) return;
                } catch (AbruptCompletion ex) {
                    int handlerPc = executable.findHandlerPc(pc);
                    if (handlerPc < 0) throw ex;
                    ctx.registers()[Variable.Register.EXCEPTION_INDEX] = ex.value();
                    pc = handlerPc;
                }
            }
        } finally {
            InterpContext.setCurrent(prior);
        }
    }

    public static boolean interpretSuspendable(Executable executable, InterpContext ctx, int startPc) {
        Op[] ops = executable.ops();
        int pc = startPc;
        InterpContext prior = InterpContext.setCurrent(ctx);
        try {
            while (true) {
                try {
                    pc = ops[pc].interpret(ctx, pc);
                    if (pc == Op.YIELD_DONE) return true;
                    if (pc == Op.FRAME_DONE) return false;
                } catch (AbruptCompletion ex) {
                    int handlerPc = executable.findHandlerPc(pc);
                    if (handlerPc < 0) throw ex;
                    ctx.registers()[Variable.Register.EXCEPTION_INDEX] = ex.value();
                    pc = handlerPc;
                }
            }
        } finally {
            InterpContext.setCurrent(prior);
        }
    }

    public static Object interpret(Executable executable, InterpContext ctx) {
        // ThreadLocal save/restore was previously inside the inner loop — that
        // fired on every nested call. AbstractOps.current() only needs a ctx
        // for accessing globals (shared across the call stack), so the OUTER
        // ctx works for nested toPrimitive callbacks. Set CURRENT once per
        // top-level entry; nested calls reuse the existing TL value.
        if (InterpContext.current() == null) {
            InterpContext.setCurrent(ctx);
            try { return runLoop(executable, ctx); }
            finally { InterpContext.setCurrent(null); }
        }
        return runLoop(executable, ctx);
    }

    static Object runLoop(Executable executable, InterpContext ctx) {
        Op[] ops = executable.ops();
        int pc = 0;
        while (true) {
            try {
                pc = ops[pc].interpret(ctx, pc);
                if (pc == Op.FRAME_DONE) {
                    return ctx.registers()[Variable.Register.RETURN_VALUE_INDEX];
                }
            } catch (AbruptCompletion ex) {
                int handlerPc = executable.findHandlerPc(pc);
                if (handlerPc < 0) throw ex;
                ctx.registers()[Variable.Register.EXCEPTION_INDEX] = ex.value();
                pc = handlerPc;
            }
        }
    }
}
