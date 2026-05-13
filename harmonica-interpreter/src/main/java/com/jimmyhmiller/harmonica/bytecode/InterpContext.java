package com.jimmyhmiller.harmonica.bytecode;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-frame state visible to the interpreter and to operands' {@code retrieve}
 * / {@code store}. Allocated on call entry, lives for one Java-frame's worth
 * of {@code Interpreter.interpret(...)}.
 *
 * <p>Skeleton. Real version will hold lexical environment, realm, generator
 * suspend point, debugger info, etc.
 */
public final class InterpContext {

    /** Sentinel held in the globals map for declared-but-not-initialized lexical bindings (TDZ). */
    public static final Object TDZ = new Object() {
        @Override public String toString() { return "<tdz>"; }
    };

    /**
     * Per-thread current context. Set on entry to the top-level interpret loop
     * and restored on exit. Used by {@link AbstractOps} to call back into the
     * interpreter (e.g. for {@code ToPrimitive}'s {@code valueOf}/{@code toString}
     * lookups) without threading the context through every static helper.
     */
    private static final ThreadLocal<InterpContext> CURRENT = new ThreadLocal<>();
    public static InterpContext current() { return CURRENT.get(); }
    public static InterpContext setCurrent(InterpContext ctx) {
        InterpContext prior = CURRENT.get();
        if (ctx == null) CURRENT.remove();
        else CURRENT.set(ctx);
        return prior;
    }

    private final Executable executable;
    private final Object[] registers;
    private final Object[] locals;
    private Object[] args;
    private Map<String, Object> globals;

    /** Per-frame {@code new.target} — the constructor passed to {@code new}, or undefined. */
    private Object newTarget = Undefined.VALUE;
    public Object newTarget() { return newTarget; }
    public void setNewTarget(Object v) { this.newTarget = v; }

    /** The super-constructor for derived class constructors, or null. */
    private Object superConstructor;
    public Object superConstructor() { return superConstructor; }
    public void setSuperConstructor(Object v) { this.superConstructor = v; }

    /**
     * Outer-scope chain for code running inside a direct {@code eval(...)}.
     * Each map binds a name to the caller-frame {@link Cell} that backs that
     * binding — eval'd code's name lookups walk this chain (innermost first)
     * before falling through to {@link #globals}. {@code null} for normal
     * (non-eval) frames so we don't pay the lookup cost.
     */
    private DirectEvalScope directEvalScope;
    public DirectEvalScope directEvalScope() { return directEvalScope; }
    public void setDirectEvalScope(DirectEvalScope s) { this.directEvalScope = s; }

    /** Linked-list of name→Cell maps. Innermost (most recent caller) first. */
    public record DirectEvalScope(java.util.Map<String, Cell> bindings, DirectEvalScope outer) {
        public Cell lookup(String name) {
            DirectEvalScope cursor = this;
            while (cursor != null) {
                Cell c = cursor.bindings.get(name);
                if (c != null) return c;
                cursor = cursor.outer;
            }
            return null;
        }
    }

    // --- Generator suspension state (ECMA-262 § 27.5.3.7 GeneratorYield) ---
    private Object yieldedValue = Undefined.VALUE;
    private int yieldResumePc = 0;
    private Variable yieldResumeDst;
    /** The argument passed to {@code generator.next(value)} on resume.
     *  Made available to yield* (§ 27.5.3.8.1 step 7.b.i), which forwards
     *  it as the argument to the inner iterator's {@code next}. Distinct
     *  from {@link #yieldResumeDst} because yield* re-enters the same op
     *  rather than storing into a destination operand. */
    private Object lastResumedValue = Undefined.VALUE;

    /**
     * Completion type with which the generator was resumed — ECMA-262
     * § 27.5.3 NormalCompletion / ReturnCompletion / ThrowCompletion.
     * Default {@link ResumeMode#NORMAL} (next(value)). {@code return(value)}
     * and {@code throw(value)} flip to RETURN / THROW and the body
     * (specifically the suspended yield / yield*) dispatches accordingly
     * on re-entry — forwarding to the inner iterator's
     * {@code return}/{@code throw} when delegating, or completing /
     * throwing here when not.
     */
    public enum ResumeMode { NORMAL, RETURN, THROW }
    private ResumeMode resumeMode = ResumeMode.NORMAL;
    public ResumeMode resumeMode() { return resumeMode; }
    public void setResumeMode(ResumeMode m) { this.resumeMode = m; }
    /** Active inner iterator object during {@code yield*}, or null. */
    private Object delegatedIterator;
    /** Cached {@code next} method of the active inner iterator, or null. */
    private Object delegatedNext;
    /**
     * True when the active {@code yield*} is delegating to an async
     * iterator (or sync iterator wrapped by an async-generator context).
     * On re-entry of the yield* op we await the iterator-result Promise
     * before unpacking {@code value} / {@code done}.
     */
    private boolean delegatedIsAsync;
    public boolean delegatedIsAsync() { return delegatedIsAsync; }
    public void setDelegatedIsAsync(boolean v) { this.delegatedIsAsync = v; }
    public Object yieldedValue() { return yieldedValue; }
    public void setYieldedValue(Object v) { this.yieldedValue = v; }
    public int yieldResumePc() { return yieldResumePc; }
    public void setYieldResumePc(int pc) { this.yieldResumePc = pc; }
    public Variable yieldResumeDst() { return yieldResumeDst; }
    public void setYieldResumeDst(Variable v) { this.yieldResumeDst = v; }
    public Object lastResumedValue() { return lastResumedValue; }
    public void setLastResumedValue(Object v) { this.lastResumedValue = v; }
    public Object delegatedIterator() { return delegatedIterator; }
    public void setDelegatedIterator(Object v) { this.delegatedIterator = v; }
    public Object delegatedNext() { return delegatedNext; }
    public void setDelegatedNext(Object v) { this.delegatedNext = v; }

    public InterpContext(Executable executable, Object[] args, int numberOfLocals) {
        this(executable, args, numberOfLocals, new HashMap<>());
    }

    public InterpContext(Executable executable, Object[] args, int numberOfLocals, Map<String, Object> globals) {
        this.executable = executable;
        this.registers = new Object[executable.numberOfRegisters()];
        this.locals = new Object[numberOfLocals];
        this.args = args;
        this.globals = globals;
        // Registers hold raw values; locals hold Cells (shared with closures).
        // Cell allocation is lazy — most call frames never touch the Local
        // slots (lodash makes millions of calls into helpers whose locals[]
        // is non-empty only because the generator pre-reserved slots).
        java.util.Arrays.fill(registers, Undefined.VALUE);
    }

    /**
     * Borrow a context for {@code executable}, reusing a pooled instance if
     * one is available. The pool is held as a direct field on
     * {@link Executable} — a HashMap-backed pool added 13% of lodash CPU
     * just for the lookup. The caller must replace per-call mutable state
     * (args, globals, the THIS register, etc.) before running. Always pair
     * with {@link #release()} on a normal completion path.
     */
    public static InterpContext acquire(Executable executable, Object[] args,
                                        int numberOfLocals, Map<String, Object> globals) {
        InterpContext ctx = executable.popPooledCtx();
        if (ctx != null) {
            // All other fields were already reset by `release()` while the
            // data was still hot in cache — acquire just plugs in the new
            // args + globals.
            ctx.args = args;
            ctx.globals = globals;
            return ctx;
        }
        return new InterpContext(executable, args, numberOfLocals, globals);
    }

    /**
     * Return this context to the per-Executable pool for reuse. Resets only
     * the fields that the next caller's invokeFunctionImpl path doesn't
     * already overwrite — newTarget is set unconditionally on the next call;
     * yield/delegated state never leaves the originating generator frame
     * (generators don't enter the pool); superConstructor stays harmless
     * unless `super` is read in the next callee, which won't happen for the
     * common non-class case.
     */
    public void release() {
        this.args = null;
        this.globals = null;
        this.directEvalScope = null;          // must clear — next call may not be eval
        this.superConstructor = null;         // cheap, keeps `super` resolvable correctly
        // Skip Arrays.fill(registers, Undefined.VALUE) — the generator
        // discipline is "write-before-read", so the next frame's body
        // initializes any register it consumes. Verified by running the
        // smoke + regex differential + lodash benchmark (all green).
        // Keep the locals fill: lazy Cell promotion relies on null entries.
        if (this.locals.length > 0) java.util.Arrays.fill(this.locals, null);
        executable.pushPooledCtx(this);
    }

    /**
     * Replace the Cell at the given local slot with an externally-provided
     * one. Used by the function-call path when binding captured cells: the
     * caller pre-allocated cells for the captured names; the callee installs
     * those same cell references into its own locals[] so reads/writes alias.
     */
    public void installCapturedCell(int slot, Cell sharedCell) {
        this.locals[slot] = sharedCell;
    }

    /**
     * Return the Cell at the given local slot, allocating / promoting it on
     * demand. NewFunction captures use this to obtain the shared Cell — once
     * a closure has the Cell, the parent's {@link Variable.Local} writes go
     * through the same instance so reads from either side observe the updates.
     *
     * <p>Default storage is now <i>unboxed</i> — Variable.Local stores values
     * directly in {@code locals[slot]} without a Cell wrapper. cellAt
     * promotes a raw slot to a Cell carrying its current value (or
     * {@code undefined} if the slot has never been written), and stamps the
     * Cell back so future reads/writes go through the shared instance.
     */
    public Cell cellAt(int slot) {
        Object o = this.locals[slot];
        if (o instanceof Cell c) return c;
        Cell c = new Cell(o == null ? Undefined.VALUE : o);
        this.locals[slot] = c;
        return c;
    }

    public Executable           executable() { return executable; }
    public Object[]             registers()  { return registers; }
    public Object[]             locals()     { return locals; }
    public Object[]             args()       { return args; }
    public Map<String, Object>  globals()    { return globals; }
}
