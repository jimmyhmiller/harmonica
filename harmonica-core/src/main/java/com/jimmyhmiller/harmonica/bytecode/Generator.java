package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.ast.*;
import com.jimmyhmiller.harmonica.bytecode.cache.EnvironmentCoordinate;
import com.jimmyhmiller.harmonica.bytecode.cache.GlobalVariableCache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AST → bytecode lowering.
 *
 * <p>Single-pass tree walk over the AST emitting an {@link Op} list. Registers
 * are allocated linearly (never reused within a scope); locals are allocated
 * by name. Labels are resolved by emitting placeholder PCs and patching them
 * after the full op list is built.
 *
 * <p>v1 covers: literals, identifiers (as locals), binary arithmetic and
 * comparison, var/let declarations with simple Identifier patterns, return,
 * throw, if/else, while, block statements. Expression statements that don't
 * bind anywhere are still emitted but their result is discarded.
 *
 * <p>Things explicitly deferred (will throw {@link UnsupportedOperationException}):
 * functions, closures, calls, property access, classes, modules, try/catch,
 * destructuring patterns, for loops.
 */
public final class Generator {

    private final List<Op> ops = new ArrayList<>();
    private final List<Object> constants = new ArrayList<>();
    private final Map<Object, Integer> constantIndex = new HashMap<>();
    private final Map<String, Integer> locals = new HashMap<>();
    private final List<String> localNames = new ArrayList<>();

    /**
     * Set when this generator is producing code for an ArrowFunctionExpression.
     * Arrow functions don't have their own {@code arguments} or {@code this}
     * binding (ECMA-262 § 10.2.1.1, § 10.2.1.4) — references to those names
     * resolve to the enclosing non-arrow function via the normal capture path.
     */
    private boolean isArrow;

    /**
     * Local slot for the function's {@code arguments} binding, or -1 if not
     * pre-allocated (arrow functions, or non-arrow functions whose body never
     * references {@code arguments}). Populated at body entry by
     * {@link Op.CreateArguments} per ECMA-262 § 10.4.4.6.
     */
    private int argumentsSlot = -1;

    /**
     * Strict mode for the body being generated. Set by {@link #generate(Program)}
     * (module sourceType or top-level "use strict" directive) or by
     * {@link #generateFunction} (parent strict + own body directive). Stored
     * on the resulting {@link Executable}; runtime ops like {@link Op.SetGlobal}
     * consult it for spec-mandated strict behaviors (§ 6.2.5.5 PutValue,
     * § 9.2.1 etc.).
     */
    private boolean strictMode;

    /**
     * Names that resolve to a binding in an active {@code CreateLexicalEnvironment}
     * scope (currently: catch parameters when the catch body contains nested
     * closures). Identifier resolution emits {@link Op.GetBinding} instead of
     * falling through to {@link Op.GetGlobal}. Pushed on enter / popped on exit
     * of each lex-env scope.
     */
    private final java.util.Set<String> lexEnvBindingNames = new java.util.HashSet<>();

    /**
     * Pre-allocated local slot for each catch clause's parameter. Filled by
     * {@link #preAllocateLetSlotsRecurse} during the DFS post-order pass so
     * the slot lands ahead of any outer-block lets with the same name —
     * matches LibJS's allocation order for tests like 385.
     */
    private final java.util.Map<Object, Integer> catchParamSlots = new java.util.HashMap<>();

    /**
     * Function declarations whose enclosing scope is a non-function block
     * (switch case, plain block) — these get Annex-B "block-scoped function
     * declaration" treatment: hoisted to the enclosing function/script's
     * lex env via CreateMutableBinding/InitializeLexicalBinding, then
     * re-bound at the declaration site via GetBinding + SetVariableBinding.
     * Filled by {@link #collectAnnexBFunctionDecls} during the script-level
     * pre-pass; consulted by {@link #lowerFunctionDeclaration} to switch to
     * the Annex-B emission shape.
     */
    private final java.util.Set<FunctionDeclaration> annexBFunctionDecls = new java.util.HashSet<>();

    /**
     * Function declarations the top-of-body hoisting pre-pass already
     * materialized + bound. The body-pass's {@link #lowerFunctionDeclaration}
     * skips these so we don't emit the NewFunction+Mov pair twice.
     */
    private final java.util.Set<FunctionDeclaration> hoistedNestedFnDecls = new java.util.HashSet<>();
    /** True while emitting the script's prologue (so annexB initial NewFunction
     *  calls don't recurse into the Annex-B re-bind shape). */
    private boolean inAnnexBPrologue;

    /**
     * Pre-allocated block-scope let/const slot mapping, keyed by AST node
     * identity. Filled by {@link #preAllocateLetSlotsForBlock} before any
     * code is emitted; consulted at {@link BlockStatement} lowering to set
     * up the per-block local view via save/restore of {@link #locals}.
     *
     * <p>LibJS allocates local slots for nested {@code let}/{@code const}
     * bindings in DFS post-order — innermost first, outermost last —
     * regardless of source order. We mirror that by walking the AST once
     * up front, recursing into nested blocks before allocating the current
     * block's lets.
     */
    private final java.util.IdentityHashMap<Object, java.util.LinkedHashMap<String, Integer>> blockLetSlots = new java.util.IdentityHashMap<>();

    /**
     * Monotonic counter for {@code InitObjectLiteralProperty}'s
     * {@code shape_cache_index} field — increments once per object literal
     * that has at least one init property. Each property within the same
     * literal shares the literal's index but gets a different
     * {@code property_slot} (0, 1, 2, ...) tracked locally inside
     * {@link #lowerObjectExpression}.
     */
    private int nextShapeCacheIndex = 0;
    private final Set<String> globalNames = new HashSet<>();
    private final Map<String, Integer> params = new HashMap<>();   // param name → position
    private final List<JSFunction> sharedFunctionData = new ArrayList<>();
    private final List<Executable.ClassBlueprint> classBlueprints = new ArrayList<>();

    /**
     * Top-level function declarations hoisted at script-load time. Names are
     * pre-bound in the global env; bodies materialize from the indices listed
     * here. Matches LibJS's "no body op for hoisted FunctionDeclaration" output.
     */
    private final List<Executable.HoistedFunction> hoistedFunctions = new ArrayList<>();
    /** Names of hoisted top-level FunctionDeclarations — used to skip them in the body pass. */
    private final Set<String> hoistedNames = new HashSet<>();

    /** Top-level {@code var} declarations hoisted to globals (pre-bound to undefined). */
    private final List<String> hoistedVarNames = new ArrayList<>();
    private final Set<String> hoistedVarNameSet = new HashSet<>();

    /**
     * For function-body generators only: parent scope used for capture resolution.
     * {@code null} for the script's top-level generator.
     */
    private Generator parent;

    /**
     * Captures: names referenced from outer scopes. Each capture occupies a
     * dedicated local slot (the first {@code captures.size()} entries in
     * {@link #localNames}); reads/writes through that slot go through a Cell
     * shared with the parent. {@code captureSlot[name] = local slot}.
     */
    private final Map<String, Integer> captureSlot = new HashMap<>();
    /** For each capture, the parent's local slot to source the shared Cell from. */
    private final List<Integer> captureSourceSlots = new ArrayList<>();
    /** For each capture, the slot in this function's locals[] where the cell is installed. */
    private final List<Integer> captureDestSlots = new ArrayList<>();
    private final List<Executable.ExceptionHandler> exceptionHandlers = new ArrayList<>();

    /**
     * Block start PCs, recorded as the generator creates new basic blocks.
     * Always starts with 0 (block 0 begins at pc 0). New entries are appended
     * by {@link #startNewBlock} at points where the generator decides a new
     * block begins (after a terminator, at a jump target, etc.).
     */
    private final List<Integer> blockStartPcs = new ArrayList<>(List.of(0));

    /**
     * PCs of forward Jumps that should be peephole-replaced with the inline
     * End op when their target block contains only a single End. Populated
     * at specific emit sites where LibJS is known to inline (e.g.
     * Jump-over-else when the after-if block is just End). The blanket
     * {@link #inlineEndAtJumpTargets} pass over all Jumps regresses other
     * patterns, so we restrict the peephole to tagged sites only.
     */
    private final java.util.Set<Integer> peepholeJumpToEndPcs = new java.util.HashSet<>();

    /** Next fresh register index, only used when the free pool is empty. */
    private int nextRegister = Variable.Register.FIRST_USER_INDEX;

    /**
     * High-water mark of register usage. Tracks the largest register index
     * ever allocated. Used as the {@code Registers} count in {@link Executable},
     * which must include slots reserved during deferred-branch emission even
     * after we restore {@link #nextRegister} to its pre-flush value.
     */
    private int maxRegister = Variable.Register.FIRST_USER_INDEX;

    /**
     * Pool of register indices freed via {@link #release}, ready for reuse.
     * Same idea as LibJS's free-pool allocator: grab the most-recently-freed
     * slot first (LIFO); only bump {@link #nextRegister} when the pool is
     * empty. Stack discipline mirrors LibJS — register numbering depends on
     * the order in which prior allocations released, not on numeric ordering.
     */
    private final Deque<Integer> freePool = new ArrayDeque<>();

    /**
     * Per-register refcount (mirrors LibJS's {@code Rc<ScopedOperandInner>}).
     * {@link #allocRegister} initializes the slot to 1. {@link #retain} bumps
     * it. {@link #release} decrements; only when it reaches 0 does the slot
     * actually get pushed back to {@link #freePool}.
     *
     * <p>Why: the prior single-owner discipline broke down once we introduced
     * places (e.g. {@code priorScopeCompletionReg} aliasing the for-loop's
     * returned {@code completionReg} across the parent block's
     * {@code lastBlockReg} cleanup) where two logical holders both want to
     * "release" the same register at end-of-life. Without refcounting, that
     * double-pushed the index into {@link #freePool}, and a later {@link
     * #allocRegister} popped the duplicate and aliased an already-allocated
     * slot — visible as {@code Add} ops landing their result on a still-live
     * callee register, which then crashed downstream as "not callable: ...".
     *
     * <p>Indexed by register index. Reserved registers (index < {@link
     * Variable.Register#FIRST_USER_INDEX}) are not counted.
     */
    private final java.util.Map<Integer, Integer> regRefCount = new java.util.HashMap<>();

    /**
     * Stack of "current statement-completion register" — each enclosing
     * if/while pushes its own. Top is the innermost. {@code null} top (or
     * empty stack) means we're not in a completion-tracking context, so
     * expression statements don't write to a completion register.
     *
     * <p>The completion register for an if/while body is allocated in
     * {@link #lowerIfReturning} / {@link #lowerWhileReturning} and kept alive
     * until the NEXT enclosing-scope if/while's completion register
     * supersedes it (see {@link #priorScopeCompletionReg}). This mirrors
     * LibJS: at any point in a scope at most TWO completion registers are
     * live — the current and the one immediately prior — so the current
     * statement always has a fresh register to write to without smashing the
     * value the script's End would still need to read.
     */
    private final Deque<Variable.Register> completionRegStack = new ArrayDeque<>();

    /**
     * The previous (one-step-older) if/while completion register in this
     * Generator's scope. Each Generator owns one of these because functions
     * have their own register space. When a new if/while in the same scope
     * finishes, its completion register supersedes this prior one — at that
     * point we release the prior register back to the free pool so the next
     * allocation can reuse it. Mirrors LibJS's "release N-1's completion when
     * N completes" pattern (verified empirically against fourifs.js where
     * completion registers cycle 6→7→5→6 because at most two are live).
     */
    private Variable.Register priorScopeCompletionReg;

    /**
     * Has {@link Op.GetLexicalEnvironment} already been emitted for this
     * Generator's body? LibJS only saves the lexical environment to the
     * dedicated reserved slot once per function/script; subsequent
     * try-statements reuse the same saved value.
     */
    private boolean lexicalEnvironmentSaved;
    /**
     * Per-function-scope flag: true once this scope's first ThisExpression
     * has emitted {@link Op.ResolveThisBinding}. Subsequent {@code this}
     * references reuse the resolved value via the {@link Operand.This}
     * pseudo-operand. Reset on entry to nested function scopes by
     * {@link #generateFunction}.
     */
    private boolean thisBindingResolved;

    /**
     * Deferred branches from for-loop bodies whose body was a single
     * IfStatement or ForStatement. The branches are emitted after the
     * script/function body's End — matching LibJS's out-of-line layout.
     * Flushed by {@link #flushDeferredLoopBranches}.
     */
    private final List<DeferredLoopBranch> deferredLoopBranches = new ArrayList<>();

    /** Marker for deferred branches; permits all the deferred record types. */
    private sealed interface DeferredLoopBranch permits DeferredIfInLoopBody, DeferredForInLoopBody, DeferredIfInAlternate, DeferredFlattenedForUpdate, DeferredDoWhileBody, DeferredTryFinallyTail, DeferredTryBody, DeferredIteratorClose, DeferredForOf {}

    /**
     * Deferred for-of body+handlers, emitted after the script/function body's
     * End. Layout (LibJS shape, codegen.rs:6330-6745):
     * <pre>
     *   block0 (entry, inline): GetLex, RHS, GetIter, Mov completion=Undef, Jump → update
     *   block1 (end, inline): subsequent stmts + script End
     *   --- deferred from here ---
     *   block2 (update): IteratorNextUnpack value, done; JumpIf done → end, !done → body
     *   block3 (catch_preamble): Catch ex; SetLexEnv; Mov typeReg=Int32(1) THROW
     *   block4 (dispatch_throw): JumpStrictlyEquals typeReg, 1, throw_close, normal_close
     *   block5 (body): bind LHS=value; body stmts; Jump → update
     *   block6 (throw_close): IteratorClose iter, completion=ex; Throw ex
     *   block7 (normal_close): IteratorClose iter, completion=Undef;
     *                         JumpStrictlyEquals typeReg, 2, return_block, throw_block
     *   block8 (return_block): Return ex
     *   block9 (throw_block): Throw ex
     *   Exception handler: body PCs → catch_preamble.
     * </pre>
     */
    private record DeferredForOf(
        Node lhs,
        Statement body,
        Variable.Register iter,
        Variable.Register next,
        Variable.Register doneIter,
        Variable.Register completionReg,
        Variable.Register typeReg,
        Variable.Register exReg,
        int jumpToUpdatePc,             // entry's Jump-placeholder; patched to update start
        int endBlockStart,              // PC of end block (for body's break, etc.)
        int snapshotNextRegister,
        java.util.List<Integer> snapshotFreePool,
        java.util.List<LoopContext> snapshotLoopStack
    ) implements DeferredLoopBranch {}

    /**
     * Deferred IteratorClose for non-rest array destructuring. After binding
     * the last element, the destructuring lowering emits {@code JumpFalse done
     * → closeBlockPlaceholder} and lets the caller continue inline. The close
     * block (and its back-Jump to the after-block) lands at the end of the
     * function/program — matching LibJS's layout where IteratorClose appears
     * as the last block(s) of the dump, with a back-Jump into the inline
     * "after" block.
     */
    private record DeferredIteratorClose(
        int jumpFalsePc,        // the JumpFalse(done, ?) to patch with closeBlockStart
        int afterBlockStart,    // PC of the after-destructuring block (jump-back target)
        Variable.Register iteratorObject,
        Variable.Register iteratorNext,
        Variable.Register iteratorDone
    ) implements DeferredLoopBranch {}

    /**
     * Deferred try-body emission for try-catch (no finally). LibJS lays out
     * prologue → catch-handler → after-try → try-body, with try-body at the
     * highest PC. The caller's "after-try" emission happens inline (so subsequent
     * statements continue right after the catch). The try-body is queued here
     * and flushed at script/function end.
     *
     * <p>The try-body's last instruction is {@code Jump → afterTryStart}, which
     * may be peephole-replaced with an inline {@code End/Return} if the after-try
     * block consists of exactly that single terminator op.
     */
    private record DeferredTryBody(
        BlockStatement tryBlock,
        Variable.Register outerCompletion,   // try-statement's value register; both arms propagate into this
        int jumpToTryBodyPc,                 // prologue's Jump-placeholder; patched to try-body start
        int handlerPc,                       // catch handler's start PC; for exception-handler range
        int afterTryStart,                   // try-body's trailing Jump target
        int snapshotNextRegister,
        java.util.List<Integer> snapshotFreePool,
        java.util.List<LoopContext> snapshotLoopStack  // outer loop contexts so a deferred-body break/continue can resolve to the enclosing loop
    ) implements DeferredLoopBranch {}

    /**
     * Trailing abnormal-completion blocks for a try-finally without catch.
     * After the script/function's natural End/Return, we emit:
     * <pre>
     *   abnormalCheck: JumpStrictlyEquals(typeReg, Int32(2), returnTarget, throwTarget)
     *   returnTarget:  Return exceptionReg
     *   throwTarget:   Throw exceptionReg
     * </pre>
     * The {@code finalizerJumpFalsePc} field is the PC of the
     * {@code JumpStrictlyEquals} in the finalizer block whose
     * {@code false_target} we patch to {@code abnormalCheck}'s start.
     */
    private record DeferredTryFinallyTail(
        Variable.Register typeReg,
        Variable.Register exceptionReg,
        int finalizerJumpFalsePc
    ) implements DeferredLoopBranch {}

    /**
     * Nested do-while body: outer's body is itself a {@code DoWhileStatement}.
     * LibJS lays this out as outer-prologue + outer-cond + outer-after, then
     * the inner's body+cond+merge as deferred trailing blocks. The merge block
     * does {@code Mov(outerComp, innerComp); Jump → outerCondStart}. See
     * {@link #lowerDoWhileWithNestedBody} and {@link #finishDeferredDoWhileBody}.
     */
    private record DeferredDoWhileBody(
        DoWhileStatement innerDws,
        // Pre-allocated registers (decided at lowerDoWhileWithNestedBody time
        // so the inline allocation order matches LibJS, even though emission
        // is deferred). innerComp is this level's body-block completion init
        // target; testReg is this level's test result register; outerComp is
        // the parent level's completion (so the merge can write to it).
        Variable.Register innerComp,
        Variable.Register testReg,
        Variable.Register outerComp,
        int outerCondStartPc,
        int bodyJumpPc,
        // Recursively-pre-allocated registers for any further-nested
        // do-while inside this level's body. Null when the inner do-while's
        // body isn't itself a do-while.
        DeferredDoWhileBody nestedChild
    ) implements DeferredLoopBranch {}

    /**
     * Trailing update block + post-script End emitted after the script's End
     * for a flattened for-loop ({@code for(;;<update>) {body-always-exits}}).
     * LibJS emits the update expression in a dead-code block after the script's
     * End, with a Jump back to the body block's start, followed by a duplicate
     * End. See {@link #lowerForFlattened} and {@link #finishDeferredFlattenedForUpdate}.
     */
    private record DeferredFlattenedForUpdate(
        Expression updateExpr,
        int bodyStartPc
    ) implements DeferredLoopBranch {}

    /** True while {@link #flushDeferredLoopBranches} is processing — affects Call-temp release order. */
    private boolean inDeferredFlush;
    /**
     * Depth of currently-active lex-env catch bodies. When > 0, Calls in
     * compound-body expression-statements defer their callee/thisVal/args
     * releases to the expression statement's tail (so dst gets pushed to the
     * pool first and callee lands at the pool head). Mirrors LibJS's release
     * order for compound bodies that materialize a CreateLexicalEnvironment.
     */
    private int lexEnvCatchBodyDepth;

    /**
     * Depth of currently-active compound bodies (do-while body, catch body,
     * BlockStatement) that contain a nested BlockStatement among their
     * top-level statements. When > 0, expression-statement {@code release(dst)}
     * is suppressed — LibJS keeps the call dst alive across statement
     * boundaries in this configuration so the next call's pre-alloc skips
     * reusing the dst slot (registers cycle through higher indices instead).
     * Required for tests like 380 (do-while body) and 392 (catch body) where
     * inner-block calls land at slots one higher than the outer body's.
     */
    private int doWhileWithNestedBlockDepth;

    /**
     * The most recently "leaked" call dst from an expression statement in a
     * compound body with nested blocks (see {@link #doWhileWithNestedBlockDepth}).
     * Released by the enclosing scope (catch body / do-while body) just
     * before allocating its trailing register (catchArmExtra), so the freed
     * slot ends up at the pool head — matches LibJS where the last call's
     * dst register is reused for the scope's "extra" register.
     */
    private Variable.Register pendingExprStmtDstRelease;

    /**
     * When true, {@link #lowerExpressionStatement} skips releasing the
     * expression's dst and returns it (instead of {@code null}) so the
     * enclosing scope (typically a {@link BlockStatement} loop) can
     * implement LibJS-style {@code last_result} tracking — keep prior
     * stmt's dst alive across the next stmt's lowering, drop prior on new
     * non-null arrival. Only set/cleared by callers that handle the
     * release themselves.
     */
    private boolean lastResultTrackingActive;

    /** Call temps deferred to end-of-expression-statement (only used during deferred flushes). */
    private final List<Operand> endOfStatementReleases = new ArrayList<>();

    /**
     * Per-loop info for unlabeled {@code break}/{@code continue}. Pushed on
     * loop entry, popped on exit. Top is the innermost loop.
     */
    private final Deque<LoopContext> loopStack = new ArrayDeque<>();

    private static final class LoopContext {
        int continueTargetPc = -1;
        /** Set after the loop's after-block starts so deferred branches can resolve break inline. */
        int breakTargetPc = -1;
        String label;                          // null if unlabeled
        /**
         * The loop's outer completion register. Used by break/continue
         * lowering to emit {@code Mov(loopCompletion, currentCompletion)}
         * before the Jump — matches LibJS, which propagates the inner
         * scope's completion value to the loop's completion on every
         * abrupt exit (so the loop's value tracks the last evaluated
         * sub-expression even on continue).
         */
        Variable.Register completionRegister;
        final List<Integer> pendingBreakPcs = new ArrayList<>();
        final List<Integer> pendingContinuePcs = new ArrayList<>();
        /**
         * When true, an unlabeled (or this-loop-labeled) {@code break} targeting
         * this loop is emitted as a fall-through — the Jump op is suppressed
         * because the after-loop block is the next basic block in PC order.
         * Set only by the {@link #lowerForFlattened} fast path for
         * {@code for(;;) {body-that-always-exits}} patterns where LibJS
         * collapses the loop skeleton.
         */
        boolean breakIsFallThrough;
    }

    /** Pending label name to attach to the next loop's LoopContext, if any. */
    private String pendingLoopLabel;

    /**
     * True if {@code s} unconditionally terminates the current execution path
     * (continue/break/return/throw). Used by block lowering to elide dead code
     * — LibJS does this and we match.
     */
    private static boolean isUnconditionalTerminator(Statement s) {
        return s instanceof ContinueStatement
            || s instanceof BreakStatement
            || s instanceof ReturnStatement
            || s instanceof ThrowStatement;
    }

    /**
     * True if {@code s}'s last reachable statement is an unconditional
     * terminator (throw/return/break/continue). Used by {@link #lowerIfReturning}
     * to skip the Jump-over-else emission when the consequent doesn't fall
     * through — matches LibJS, which omits unreachable Jumps.
     */
    private static boolean lastReachableIsTerminator(Statement s) {
        if (s == null) return false;
        if (s instanceof BlockStatement bs) {
            if (bs.body().isEmpty()) return false;
            return lastReachableIsTerminator(bs.body().get(bs.body().size() - 1));
        }
        return isUnconditionalTerminator(s);
    }

    /**
     * True if {@code s} doesn't allocate any registers. Used by
     * {@link #tryStartForOnlyBody} to detect inner bodies whose emission
     * leaves the allocator state unchanged (e.g. {@code break label1;}).
     * Conservative: returns false for anything that might allocate.
     */
    /**
     * True if every execution path through {@code s} unconditionally exits
     * the enclosing for-loop via a {@code break} to that loop, a {@code return},
     * or a {@code throw}. Used to detect the {@code for(;;){body-always-exits}}
     * pattern that LibJS collapses into init+body+after (no cond, no update,
     * no back-edge).
     *
     * <p>Conservative: only recognizes a leading terminator inside a
     * BlockStatement and unlabeled break/return/throw. Labeled break, nested
     * if-else with both branches terminating, etc., are not folded — those
     * keep the regular for-loop skeleton.
     */
    private static boolean bodyAlwaysExitsLoop(Statement s) {
        if (s instanceof BreakStatement bs) {
            // Unlabeled break = innermost loop = this for-loop.
            // Labeled break to outer loop is conservatively rejected.
            return bs.label() == null;
        }
        if (s instanceof ReturnStatement || s instanceof ThrowStatement) return true;
        if (s instanceof BlockStatement block) {
            for (Statement inner : block.body()) {
                if (bodyAlwaysExitsLoop(inner)) return true;
                // ContinueStatement would loop back — not an exit.
                if (inner instanceof ContinueStatement) return false;
            }
            return false;
        }
        return false;
    }

    private static boolean isNonAllocatingStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            for (Statement inner : bs.body()) {
                if (!isNonAllocatingStatement(inner)) return false;
                // Statements after an unconditional terminator are dead and
                // get elided during emission — they don't affect allocation.
                if (isUnconditionalTerminator(inner)) return true;
            }
            return true;
        }
        return s instanceof BreakStatement
            || s instanceof ContinueStatement
            || s instanceof EmptyStatement;
    }

    private LoopContext findLoopForLabel(String label) {
        if (label == null) return loopStack.peek();
        for (LoopContext ctx : loopStack) {
            if (label.equals(ctx.label)) return ctx;
        }
        return null;
    }

    private void attachPendingLabel(LoopContext ctx) {
        if (pendingLoopLabel != null) {
            ctx.label = pendingLoopLabel;
            pendingLoopLabel = null;
        }
    }

    /**
     * True while lowering Program.body's direct children. {@code let}/{@code const}
     * declarations encountered while this is true become globals. Set false on
     * descent into nested scopes (BlockStatement, while body, etc.).
     */
    private boolean atTopLevel = true;

    Generator() {}

    /**
     * Lower a {@link Program} to an {@link Executable}.
     *
     * <p>The program's final expression value (or {@code undefined} if the
     * program produces no value) is returned via an {@code End} terminator.
     */
    /**
     * ECMA-262 § 11.2.2 Strict Mode Code: a module is automatically strict;
     * other scripts are strict iff the body's first prologue directive is
     * exactly the string {@code "use strict"}.
     */
    private static boolean detectStrictMode(String sourceType, java.util.List<Statement> body) {
        if ("module".equals(sourceType)) return true;
        return hasUseStrictDirective(body);
    }

    private static boolean hasUseStrictDirective(java.util.List<Statement> body) {
        // Directive prologue: leading string-literal expression statements.
        for (Statement s : body) {
            if (!(s instanceof ExpressionStatement es) || es.directive() == null) break;
            if ("use strict".equals(es.directive())) return true;
        }
        return false;
    }

    public static Executable generate(Program program) {
        Generator g = new Generator();
        g.strictMode = detectStrictMode(program.sourceType(), program.body());
        g.lowerProgram(program);
        return g.finish();
    }

    /**
     * Peephole: replace each FORWARD unconditional {@code Jump → block-with-only-End}
     * with that End inlined directly. Empirically derived from LibJS dumps —
     * see {@code do break; while(c)} where the body's break-to-after-loop
     * Jump becomes a body-block End op. Applies to break/Jump-over-else and
     * similar forward control-flow Jumps that happen to target an End-only
     * block. Skips backward Jumps (loop back-edges like update→body) — LibJS
     * keeps those as Jump even when the back-edge target is an End-only block
     * (e.g. flattened {@code for(false;;false){break;}} where the deferred
     * update's {@code Jump → body} stays a Jump). Functions don't terminate
     * with End (they use Return), so this is a no-op for function bodies.
     */
    /**
     * Targeted version of {@link #inlineEndAtJumpTargets}: only Jumps tagged
     * via {@link #peepholeJumpToEndPcs} are considered. Used for emit sites
     * where LibJS is known to inline End (e.g. Jump-over-else), without
     * regressing other Jump→End-only-block patterns LibJS keeps as Jump.
     */
    private void inlineEndAtTaggedJumpPcs() {
        if (ops.isEmpty() || peepholeJumpToEndPcs.isEmpty()) return;
        int[] blockStarts = blockStartPcs.stream().mapToInt(Integer::intValue).sorted().toArray();
        for (int pc : peepholeJumpToEndPcs) {
            if (pc < 0 || pc >= ops.size()) continue;
            if (!(ops.get(pc) instanceof Op.Jump j)) continue;
            int target = j.targetPc();
            if (target <= pc || target >= ops.size()) continue;
            if (!(ops.get(target) instanceof Op.End endOp)) continue;
            int idx = java.util.Arrays.binarySearch(blockStarts, target);
            if (idx < 0) continue;
            int nextStart = (idx + 1 < blockStarts.length) ? blockStarts[idx + 1] : ops.size();
            if (nextStart != target + 1) continue;
            ops.set(pc, new Op.End(endOp.value()));
        }
    }

    private void inlineEndAtJumpTargets() {
        if (ops.isEmpty()) return;
        int[] blockStarts = blockStartPcs.stream().mapToInt(Integer::intValue).sorted().toArray();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (!(op instanceof Op.Jump j)) continue;
            int target = j.targetPc();
            if (target <= i) continue;  // skip backward / self Jumps
            if (target >= ops.size()) continue;
            if (!(ops.get(target) instanceof Op.End endOp)) continue;
            int idx = java.util.Arrays.binarySearch(blockStarts, target);
            if (idx < 0) continue;
            int nextStart = (idx + 1 < blockStarts.length) ? blockStarts[idx + 1] : ops.size();
            if (nextStart != target + 1) continue;  // block has more than one op
            ops.set(i, new Op.End(endOp.value()));
        }
    }

    private Executable finish() {
        // Peephole inlineEndAtJumpTargets is intentionally disabled — LibJS
        // applies the Jump→End replacement only for specific break/Jump-over-else
        // emit sites, not universally. Replacing all forward Jump→End-only
        // pairs regresses other patterns (e.g. const-false for-loop's
        // init→cond Jump). A targeted version (per-emit-site) is the right
        // direction; see TODO.
        inlineEndAtTaggedJumpPcs();
        int[] blockStarts = blockStartPcs.stream().mapToInt(Integer::intValue).toArray();
        return new Executable(
            ops.toArray(new Op[0]),
            Math.max(nextRegister, maxRegister),
            localNames.size(),
            localNames.toArray(new String[0]),
            constants.toArray(),
            exceptionHandlers.toArray(new Executable.ExceptionHandler[0]),
            blockStarts,
            sharedFunctionData.toArray(new JSFunction[0]),
            hoistedFunctions.toArray(new Executable.HoistedFunction[0]),
            hoistedVarNames.toArray(new String[0]),
            classBlueprints.toArray(new Executable.ClassBlueprint[0]),
            this.strictMode
        );
    }

    /**
     * Lower a function body. Used recursively when the parent generator
     * encounters a {@link FunctionDeclaration} or function expression.
     *
     * <p>Inherits the parent's {@code globalNames} for global resolution. Has
     * its own register, local, and constant tables. Identifier references
     * that resolve to outer locals trigger capture promotion (see
     * {@link #captureFromOuter}).
     */
    /**
     * True iff a class field's initializer is a primitive literal that LibJS
     * stores directly without an anonymous-function wrapper — number, string,
     * boolean, null. Anything else (including the {@code undefined} identifier,
     * unary expressions, calls, member access, function/array/object literals)
     * gets wrapped in a function and consumes a SharedFunctionData slot.
     */
    private boolean isLiteralFieldInitializer(Object value) {
        if (!(value instanceof Literal lit)) return false;
        Object v = literalValue(lit);
        return v == null
            || v instanceof Boolean
            || v instanceof Integer
            || v instanceof Long
            || v instanceof Double
            || v instanceof String;
    }

    /**
     * Empty placeholder pushed to {@link #sharedFunctionData} so its size
     * matches LibJS's count of class-member SharedFunctionData entries. We
     * don't actually lower the field initializer as a function body — the
     * inits are still inlined into the constructor — but the slot has to
     * exist so subsequent {@code NewFunction shared_function_data_index:N}
     * dumps line up.
     */
    private JSFunction fieldInitializerPlaceholder() {
        return generateFunction(null, java.util.List.of(),
            new BlockStatement(0, 0, 0, 0, 0, 0, java.util.List.of()));
    }

    private JSFunction generateFunction(
        String name,
        List<Pattern> params,
        BlockStatement body
    ) {
        return generateFunction(name, params, body, /* isArrow */ false, /* isGenerator */ false, /* isAsync */ false);
    }

    private JSFunction generateFunction(
        String name,
        List<Pattern> params,
        BlockStatement body,
        boolean isArrow
    ) {
        return generateFunction(name, params, body, isArrow, /* isGenerator */ false, /* isAsync */ false);
    }

    private JSFunction generateFunction(
        String name,
        List<Pattern> params,
        BlockStatement body,
        boolean isArrow,
        boolean isGenerator
    ) {
        return generateFunction(name, params, body, isArrow, isGenerator, /* isAsync */ false);
    }

    private JSFunction generateFunction(
        String name,
        List<Pattern> params,
        BlockStatement body,
        boolean isArrow,
        boolean isGenerator,
        boolean isAsync
    ) {
        Generator g = new Generator();
        g.atTopLevel = false;
        g.isArrow = isArrow;
        g.globalNames.addAll(this.globalNames);
        g.parent = this;
        // Strict mode inherits from outer function (§ 11.2.2): a function
        // is strict if its enclosing code is strict OR its own body has a
        // "use strict" prologue directive. Class bodies (methods) are
        // unconditionally strict per § 15.7 — but the parser already flags
        // them via "use strict" so the directive scan handles that too.
        g.strictMode = this.strictMode || hasUseStrictDirective(body.body());

        // ECMA-262 § 10.4.4 — non-arrow functions get their own `arguments`
        // exotic binding; arrow functions inherit it from the enclosing
        // non-arrow function. We pre-bind `arguments` as a local here when:
        //   (a) this is a non-arrow function, AND
        //   (b) the body (or any non-arrow-skipping descendent) references
        //       `arguments` as an Identifier.
        // The runtime populates the slot via Op.CreateArguments at body start.
        // Skip the binding entirely otherwise — keeps bytecode minimal and
        // matches LibJS's emit-only-when-used behavior.
        // Param defaults can also reference `arguments` (e.g.
        // `function f(x = arguments[2]) {...}`) — § 10.2.11 step 24's
        // FunctionDeclarationInstantiation creates the arguments binding
        // before any parameter initializer runs, so the body alone isn't
        // sufficient to decide.
        boolean paramsReferenceArgs = false;
        for (Pattern p : params) {
            if (p instanceof AssignmentPattern asn && referencesArguments(asn.right())) {
                paramsReferenceArgs = true; break;
            }
        }
        boolean needsArguments = !isArrow && (referencesArguments(body) || paramsReferenceArgs);
        if (needsArguments) {
            int slot = g.localNames.size();
            g.localNames.add("arguments");
            g.locals.put("arguments", slot);
            // Emit at body entry (after parameter binding below).
            // We stash the slot for use after params are bound.
            g.argumentsSlot = slot;
        }

        // ECMA-262 § 10.2.11 step 22 (FunctionDeclarationInstantiation)
        // creates the `arguments` binding BEFORE step 27's parameter
        // initialization, so param-default expressions can reference it
        // (`function f(x = arguments[2]) {...}`). Emit CreateArguments now,
        // before param binding, so the slot is populated in time.
        if (g.argumentsSlot >= 0) {
            g.emit(new Op.CreateArguments(new Variable.Local(g.argumentsSlot)));
        }

        // Bind parameters at function entry. Each positional Argument is fed
        // through the destructuring binder so simple Identifier params become
        // named locals (captured uniformly by inner closures), while
        // destructured/defaulted params lower naturally too. A trailing
        // RestElement collects the remaining positional args into an array.
        for (int i = 0; i < params.size(); i++) {
            Pattern p = params.get(i);
            if (p instanceof RestElement rest) {
                if (i != params.size() - 1) {
                    throw new IllegalStateException("rest param must be last");
                }
                Variable.Register restArr = g.allocRegister();
                g.emit(new Op.CreateRestParams(restArr, i));
                g.bindPattern(rest.argument(), restArr, BindMode.LOCAL);
                g.release(restArr);
            } else {
                Variable.Register paramReg = g.allocRegister();
                g.emit(new Op.Mov(paramReg, new Variable.Argument(i)));
                g.bindPattern(p, paramReg, BindMode.LOCAL);
                g.release(paramReg);
            }
        }

        // For generator / async-generator functions, mark where the
        // synchronous prologue (parameter destructuring) ends — the runtime
        // runs ops up to this PC eagerly at call time so destructuring
        // throws propagate from the .method() call rather than being
        // deferred to first .next(). Plain functions don't need this.
        int prologueEnd = (isGenerator) ? g.ops.size() : -1;

        // Pre-allocate locals for `var`-hoisted names. `var` hoists to the
        // nearest enclosing function, so we walk the body (skipping nested
        // functions/classes) and reserve a local for each name. Names that
        // already collide with a parameter binding reuse the existing local.
        g.collectFunctionScopeVarLocals(body.body());

        // Pre-pass: pre-allocate let/const slots for nested blocks in DFS
        // post-order (matches LibJS's allocation timing). Function-body's
        // top-level lets are also block-scoped and need a scope entry —
        // synthesize one keyed by the body BlockStatement.
        g.preAllocateLetSlotsForBlock(body, body.body());
        for (Statement s : body.body()) {
            g.preAllocateLetSlotsRecurse(s);
        }

        // Stage the function-body's top-level let/const names into `locals`
        // BEFORE the FD-hoist pre-pass below, so that an inner function
        // declared at the top of the body can capture them via
        // captureFromOuter (which checks `parent.locals.get(name)`). Without
        // this staging the inner function declarations execute at hoist
        // time but their captures don't see the not-yet-lowered let
        // bindings, leaving them as global lookups that ReferenceError.
        java.util.LinkedHashMap<String, Integer> bodyLetSlots = g.blockLetSlots.get(body);
        if (bodyLetSlots != null) {
            for (var e : bodyLetSlots.entrySet()) {
                if (!g.locals.containsKey(e.getKey())) {
                    g.locals.put(e.getKey(), e.getValue());
                }
            }
        }

        // Pre-pass: hoist top-of-body FunctionDeclarations so they're bound
        // before any user statement runs (§ 10.2.11 step 28). Two phases so
        // sibling-FDs can capture each other (forward references work):
        //   1) Reserve a local slot + register the name in `g.locals` for
        //      every FD. After this, captureFromOuter("foo") on any nested
        //      function finds foo via parent.locals regardless of source
        //      order.
        //   2) Generate each FD's body and emit NewFunction + Mov into the
        //      reserved slot.
        // Use `g.generateFunction(...)` (not bare `generateFunction(...)`)
        // so the recursion's `this` is `g` and the new gen's parent chain
        // climbs through `g` — bare would chain through the outer
        // method's `this`, the outermost script generator, breaking
        // closures.
        java.util.List<FunctionDeclaration> fdsToHoist = new ArrayList<>();
        for (Statement s : body.body()) {
            if (s instanceof FunctionDeclaration fd && fd.id() != null
                && !g.annexBFunctionDecls.contains(fd)) {
                fdsToHoist.add(fd);
                g.localFor(fd.id().name());   // reserve slot + register in locals
                g.hoistedNestedFnDecls.add(fd);
            }
        }
        for (FunctionDeclaration fd : fdsToHoist) {
            String fname = fd.id().name();
            JSFunction fnTpl = g.generateFunction(fname, fd.params(), fd.body(),
                /* isArrow */ false, fd.generator(), fd.async());
            int fnIndex = g.sharedFunctionData.size();
            g.sharedFunctionData.add(fnTpl);
            Variable.Register fnReg = g.allocRegister();
            g.emit(new Op.NewFunction(fnReg, fnIndex, fname, null));
            Variable.Local slot = g.localFor(fname);
            g.emit(new Op.Mov(slot, fnReg));
            g.release(fnReg);
        }

        for (Statement s : body.body()) {
            g.lowerStatement(s);
        }
        // Implicit `return undefined` if body falls through without one.
        g.emit(new Op.Return(g.constant(Undefined.VALUE)));
        g.flushDeferredLoopBranches();

        Executable exe = g.finish();
        if (prologueEnd >= 0) exe.setPrologueEndPc(prologueEnd);
        int[] sourceSlots = g.captureSourceSlots.stream().mapToInt(Integer::intValue).toArray();
        int[] destSlots   = g.captureDestSlots.stream().mapToInt(Integer::intValue).toArray();
        // ECMA-262 § 15.2.5 ExpectedArgumentCount: function.length counts the
        // run of plain BindingElements at the start of the parameter list,
        // stopping at the first param with a default (`AssignmentPattern`)
        // or rest (`RestElement`). Real engines expose this via fn.length —
        // tests call out the difference (e.g. `(a, b = 1) => ...` has length 1).
        int expectedArgCount = 0;
        for (Pattern p : params) {
            if (p instanceof AssignmentPattern || p instanceof RestElement) break;
            expectedArgCount++;
        }
        JSFunction fn = new JSFunction(name, exe, expectedArgCount,
            g.localNames.size(), g.captureSourceSlots.size(), sourceSlots, destSlots);
        fn.setGenerator(isGenerator);
        fn.setAsync(isAsync);
        fn.setArrow(isArrow);
        return fn;
    }

    /**
     * Resolve {@code name} as a capture from this generator's parent chain.
     * Returns the local slot in <em>this</em> generator where the captured
     * Cell will live at runtime. If the parent doesn't have the name as a
     * local or capture, returns {@code -1}.
     */
    private int captureFromOuter(String name) {
        Integer existing = captureSlot.get(name);
        if (existing != null) return existing;

        if (parent == null) return -1;

        // Find the cell's source in the parent: either parent has it as a local,
        // already captured it, or we recurse to grandparent.
        int parentSlot;
        Integer parentLocal = parent.locals.get(name);
        if (parentLocal != null) {
            parentSlot = parentLocal;
        } else {
            Integer parentCapture = parent.captureSlot.get(name);
            if (parentCapture != null) {
                parentSlot = parentCapture;
            } else {
                int upperSlot = parent.captureFromOuter(name);
                if (upperSlot < 0) return -1;
                parentSlot = upperSlot;
            }
        }

        // Allocate a new local slot for the captured cell in THIS generator.
        int mySlot = localNames.size();
        localNames.add(name);
        captureSlot.put(name, mySlot);
        captureSourceSlots.add(parentSlot);
        captureDestSlots.add(mySlot);
        return mySlot;
    }

    /**
     * Mark the current PC as the start of a new basic block. No-op if the
     * current PC already begins a block (avoids creating empty blocks when
     * the generator nominally "starts" multiple times at the same point).
     */
    private void startNewBlock() {
        int pc = ops.size();
        if (blockStartPcs.get(blockStartPcs.size() - 1) != pc) {
            blockStartPcs.add(pc);
        }
    }

    // ------------------------------------------------------------
    //  Helpers — register allocation, constants, emission
    // ------------------------------------------------------------

    /**
     * Allocate a register. Returns a freed-and-pooled slot if one is available,
     * otherwise bumps {@link #nextRegister}. Total register-file size is
     * tracked by {@link #nextRegister} (reused slots don't grow it).
     */
    private Variable.Register allocRegister() {
        int idx = freePool.isEmpty() ? nextRegister++ : freePool.pop();
        if (idx + 1 > maxRegister) maxRegister = idx + 1;
        regRefCount.put(idx, 1);
        return new Variable.Register(idx);
    }

    /**
     * Bump the refcount of {@code reg} to mark a NEW logical holder. Pair
     * this with a future {@link #release} call from the new holder. Mirrors
     * LibJS's {@code ScopedOperand::clone} — both the original and the clone
     * keep the slot alive, and the slot only returns to the free pool when
     * the LAST clone drops.
     */
    private void retain(Variable.Register reg) {
        int idx = reg.index();
        if (idx < Variable.Register.FIRST_USER_INDEX) return;
        regRefCount.merge(idx, 1, Integer::sum);
    }

    /**
     * Update {@link #priorScopeCompletionReg} with proper refcount handling.
     * Drops the prior reference (if any) and retains the new one so the
     * register's owning callers (e.g. the parent block's {@code lastBlockReg})
     * can release independently without double-pushing the slot.
     */
    private void setPriorScopeCompletionReg(Variable.Register reg) {
        if (priorScopeCompletionReg != null && priorScopeCompletionReg != reg) {
            release(priorScopeCompletionReg);
        }
        priorScopeCompletionReg = reg;
        if (reg != null) retain(reg);
    }

    /**
     * Allocate a fresh register, ignoring any freed slots in the pool.
     * Always bumps {@link #nextRegister}. Used when we want the body of a
     * compound statement (e.g. while loop) to reserve a register slot above
     * any pool entries — so the body's subsequent allocations skip past it
     * and the slot is available for use after the compound ends.
     */
    private Variable.Register allocFreshRegister() {
        int idx = nextRegister++;
        if (idx + 1 > maxRegister) maxRegister = idx + 1;
        return new Variable.Register(idx);
    }

    /**
     * Up-front allocation: like {@link #allocRegister} but the register's
     * lifetime extends to enclosing operands too. The current generator's
     * implementation is identical to {@link #allocRegister}; this method
     * exists as a documentation hook for places where allocation order
     * matters (e.g. Call lowering pre-allocates its dst).
     */
    private Variable.Register allocRegisterUpFront() {
        return allocRegister();
    }

    /**
     * Mark a register as no longer needed. Pushes it onto the free pool for
     * reuse by the next allocation. No-op for non-register operands and for
     * reserved registers (indices 0..4).
     */
    private void release(Operand op) {
        if (op instanceof Variable.Register r && r.index() >= Variable.Register.FIRST_USER_INDEX) {
            int idx = r.index();
            Integer count = regRefCount.get(idx);
            // Defensive: missing refcount means a release() call without a
            // preceding allocRegister() — keep the slot in the pool but
            // don't push duplicates.
            int newCount = (count == null ? 0 : count - 1);
            if (newCount > 0) {
                regRefCount.put(idx, newCount);
                return;
            }
            regRefCount.remove(idx);
            freePool.push(idx);
        }
    }

    /** Top of the completion-register stack, or {@code null} if not in a tracked context. */
    private Variable.Register currentCompletionReg() {
        return completionRegStack.peek();
    }

    private Operand.Constant constant(Object value) {
        Integer existing = constantIndex.get(value);
        if (existing != null) return new Operand.Constant(existing, value);
        int index = constants.size();
        constants.add(value);
        constantIndex.put(value, index);
        return new Operand.Constant(index, value);
    }

    /**
     * Emit a literal as the most-specific {@link Operand} subtype available.
     * Unlike {@link #constant}, the result is NOT necessarily an
     * {@link Operand.Constant} — typed literals carry the value directly and
     * expose {@code retrieveDouble} / {@code retrieveBoolean} that skip the
     * box round-trip used by the generic {@code Object retrieve} path.
     *
     * <p>Used by call sites that don't need the constants-pool index (i.e.
     * everything except byte-perfect oracle dumps). Disassembler output
     * still gets coverage from the regular {@link Operand.Constant}
     * fallback when the value isn't a recognized primitive.
     */
    private Operand literal(Object value) {
        if (value == null) return Operand.NullLit.INSTANCE;
        if (value == Undefined.VALUE) return Operand.UndefinedLit.INSTANCE;
        if (value instanceof Boolean b) return b ? Operand.BoolLit.TRUE : Operand.BoolLit.FALSE;
        if (value instanceof Number n) return new Operand.DoubleLit(n.doubleValue());
        if (value instanceof String s) return new Operand.StringLit(s);
        // Fallback: still go through the constants pool so the oracle dump
        // sees an indexed slot for unhandled value types.
        return constant(value);
    }

    /** Get-or-create a local slot for a name. */
    /**
     * DFS post-order pre-allocation of {@code let}/{@code const} slots for
     * a list of statements. Recurses into nested scope-creating constructs
     * (BlockStatement, IfStatement, While/DoWhile, For, Try/Catch, Switch,
     * Labeled) FIRST, then allocates this scope's own lets in source order.
     * The result is stored per-block in {@link #blockLetSlots}.
     *
     * <p>Net effect: a nested {@code { let x; { let x; } }} produces locals
     * {@code [x~0, x~1]} where the inner gets the lower index (allocated
     * first because the outer's pre-allocation recurses into the inner
     * first). Matches LibJS's allocation order.
     */
    private void preAllocateLetSlotsForBlock(Object scopeKey, java.util.List<Statement> stmts) {
        java.util.LinkedHashMap<String, Integer> myMap = new java.util.LinkedHashMap<>();
        blockLetSlots.put(scopeKey, myMap);
        for (Statement s : stmts) {
            preAllocateLetSlotsRecurse(s);
        }
        for (Statement s : stmts) {
            if (s instanceof VariableDeclaration vd
                && ("let".equals(vd.kind()) || "const".equals(vd.kind()))) {
                // preAllocateLetSlotsForBlock is only called for nested
                // BlockStatements (and function bodies); the atTopLevel
                // check that previously sat here was wrong because it tracks
                // "directly under Program", which is unrelated to whether
                // *this* block is nested.
                for (VariableDeclarator d : vd.declarations()) {
                    collectLetIdsFromPattern(d.id(), myMap);
                }
            }
        }
    }

    private void collectLetIdsFromPattern(Node pattern, java.util.LinkedHashMap<String, Integer> map) {
        if (pattern instanceof Identifier id) {
            int slot = localNames.size();
            localNames.add(id.name());
            map.put(id.name(), slot);
        } else if (pattern instanceof ArrayPattern ap) {
            for (Node elem : ap.elements()) {
                if (elem != null) collectLetIdsFromPattern(elem, map);
            }
        } else if (pattern instanceof ObjectPattern op) {
            for (Node prop : op.properties()) {
                if (prop instanceof Property p) {
                    collectLetIdsFromPattern(p.value(), map);
                } else if (prop instanceof RestElement re) {
                    collectLetIdsFromPattern(re.argument(), map);
                }
            }
        } else if (pattern instanceof AssignmentPattern asn) {
            collectLetIdsFromPattern(asn.left(), map);
        } else if (pattern instanceof RestElement re) {
            collectLetIdsFromPattern(re.argument(), map);
        }
    }

    /**
     * Recurse into a single statement's nested scope-creating constructs.
     * Each nested {@code BlockStatement} (and similar block-bearing nodes)
     * gets its own scope keyed by AST identity in {@link #blockLetSlots}.
     */
    private void preAllocateLetSlotsRecurse(Statement s) {
        if (s == null) return;
        if (s instanceof BlockStatement bs) {
            preAllocateLetSlotsForBlock(bs, bs.body());
        } else if (s instanceof IfStatement is) {
            preAllocateLetSlotsRecurse(is.consequent());
            if (is.alternate() != null) preAllocateLetSlotsRecurse(is.alternate());
        } else if (s instanceof WhileStatement ws) {
            preAllocateLetSlotsRecurse(ws.body());
        } else if (s instanceof DoWhileStatement dws) {
            preAllocateLetSlotsRecurse(dws.body());
        } else if (s instanceof ForStatement fs) {
            preAllocateLetSlotsRecurse(fs.body());
        } else if (s instanceof ForOfStatement fos) {
            preAllocateLetSlotsRecurse(fos.body());
        } else if (s instanceof ForInStatement fis) {
            preAllocateLetSlotsRecurse(fis.body());
        } else if (s instanceof TryStatement ts) {
            preAllocateLetSlotsRecurse(ts.block());
            if (ts.handler() != null) {
                preAllocateLetSlotsRecurse(ts.handler().body());
                // Allocate the catch parameter slot at this DFS post-order
                // point — innermost lets in the handler body have already
                // been allocated, so the catch param lands BEFORE the outer
                // block's lets. Matches LibJS for tests like 385 where the
                // catch param shadows an outer-block let with the same name.
                if (ts.handler().param() instanceof Identifier id) {
                    int slot = localNames.size();
                    localNames.add(id.name());
                    catchParamSlots.put(ts.handler(), slot);
                }
            }
            if (ts.finalizer() != null) {
                preAllocateLetSlotsRecurse(ts.finalizer());
            }
        } else if (s instanceof SwitchStatement ss) {
            for (com.jimmyhmiller.harmonica.ast.SwitchCase c : ss.cases()) {
                for (Statement cs : c.consequent()) {
                    preAllocateLetSlotsRecurse(cs);
                }
            }
        } else if (s instanceof LabeledStatement ls) {
            preAllocateLetSlotsRecurse(ls.body());
        } else if (s instanceof WithStatement ws) {
            preAllocateLetSlotsRecurse(ws.body());
        }
        // Other statements don't create nested block scopes for our purposes.
    }

    private Variable.Local localFor(String name) {
        Integer existing = locals.get(name);
        if (existing != null) return new Variable.Local(existing);
        int slot = localNames.size();
        localNames.add(name);
        locals.put(name, slot);
        return new Variable.Local(slot);
    }

    /**
     * Emit an instruction. If the op is a {@link Op.Mov} and the immediate
     * predecessor is also a {@code Mov}, fuse them into {@link Op.Mov2} in
     * place — this is a peephole that matches LibJS's emission. Fusion only
     * shrinks the tail of the ops array, so it never invalidates previously
     * recorded PCs (which point at earlier instructions).
     */
    /**
     * After we drop a Mov as a redundant idempotent-overwrite, we mark the
     * just-grown Mov2 as sealed so the next Mov starts a fresh fusion chain
     * instead of fusing with the sealed Mov2. LibJS does this implicitly:
     * once an emit decides "this Mov adds nothing", the running fusion is
     * over and any further Movs are independent.
     */
    private boolean sealLastFusion = false;

    private int emit(Op op) {
        if (op instanceof Op.Mov newMov
            && !ops.isEmpty()
            && !blockStartPcs.contains(ops.size())) {
            // Fusion across a jump target would change semantics — if the new
            // Mov's pc is the start of a basic block (i.e. some jump targets it),
            // a fused MovN would always run BOTH movs, breaking the branch.
            Op prev = ops.get(ops.size() - 1);
            if (sealLastFusion) {
                // Prior fusion was sealed by a dropped redundant Mov — emit
                // this one fresh.
                sealLastFusion = false;
            } else if (prev instanceof Op.Mov2 prevMov2) {
                // LibJS drops a third Mov whose (dst, src) already appears in
                // the running Mov2 — it's an idempotent overwrite. After the
                // drop, the Mov2 is "sealed" so the next Mov doesn't fuse.
                if (movPairEquals(prevMov2.dst1(), prevMov2.src1(), newMov.dst(), newMov.src())
                 || movPairEquals(prevMov2.dst2(), prevMov2.src2(), newMov.dst(), newMov.src())) {
                    sealLastFusion = true;
                    drainReleaseAfterEmit();
                    return ops.size() - 1;
                }
                // Mov2 + Mov → Mov3.
                ops.set(ops.size() - 1, new Op.Mov3(
                    prevMov2.dst1(), prevMov2.src1(),
                    prevMov2.dst2(), prevMov2.src2(),
                    newMov.dst(), newMov.src()));
                drainReleaseAfterEmit();
                return ops.size() - 1;
            } else if (prev instanceof Op.Mov prevMov) {
                ops.set(ops.size() - 1, new Op.Mov2(
                    prevMov.dst(), prevMov.src(),
                    newMov.dst(), newMov.src()));
                drainReleaseAfterEmit();
                return ops.size() - 1;
            }
        } else {
            // Any non-Mov emit clears the seal — fusion logic only matters
            // for runs of Movs.
            sealLastFusion = false;
        }
        int pc = ops.size();
        ops.add(op);
        drainReleaseAfterEmit();
        return pc;
    }

    /**
     * Registers queued by {@link #scheduleReleaseAfterNextEmit} are pushed to
     * {@link #freePool} after the next {@link #emit} call. Used by the
     * deferred-for mechanism: LibJS releases the inner completion register
     * AFTER the first allocation of the outer update (not before), so we
     * defer the release one emit.
     */
    private static boolean movPairEquals(Variable dst1, Operand src1,
                                         Variable dst2, Operand src2) {
        return java.util.Objects.equals(dst1, dst2) && java.util.Objects.equals(src1, src2);
    }

    private final List<Integer> releaseAfterEmit = new ArrayList<>();

    private void scheduleReleaseAfterNextEmit(Variable.Register r) {
        if (r.index() >= Variable.Register.FIRST_USER_INDEX) {
            releaseAfterEmit.add(r.index());
        }
    }

    private void drainReleaseAfterEmit() {
        if (releaseAfterEmit.isEmpty()) return;
        for (Integer i : releaseAfterEmit) freePool.push(i);
        releaseAfterEmit.clear();
    }


    /**
     * Patch a previously emitted jump's target. For two-target fused jumps
     * (JumpLessThan etc.), this patches the {@code falseTargetPc} — the
     * convention in this generator is to set {@code trueTargetPc} at emit
     * time (since the consequent always falls through).
     *
     * <p>Side effect: marks {@code targetPc} as a basic-block start, so the
     * Mov-fusion peephole won't merge across it.
     */
    private void patchJumpTarget(int pc, int targetPc) {
        // Ensure the target is recorded as a block boundary. Walks blockStartPcs
        // (small) to insert in sorted order.
        if (!blockStartPcs.contains(targetPc)) {
            int insertAt = 0;
            while (insertAt < blockStartPcs.size() && blockStartPcs.get(insertAt) < targetPc) insertAt++;
            blockStartPcs.add(insertAt, targetPc);
        }
        Op old = ops.get(pc);
        Op patched = switch (old) {
            case Op.Jump      j -> new Op.Jump(targetPc);
            case Op.JumpTrue  j -> new Op.JumpTrue(j.condition(), targetPc);
            case Op.JumpFalse j -> new Op.JumpFalse(j.condition(), targetPc);

            case Op.JumpLessThan          j -> new Op.JumpLessThan(j.lhs(), j.rhs(), j.trueTargetPc(), targetPc);
            case Op.JumpLessThanEquals    j -> new Op.JumpLessThanEquals(j.lhs(), j.rhs(), j.trueTargetPc(), targetPc);
            case Op.JumpGreaterThan       j -> new Op.JumpGreaterThan(j.lhs(), j.rhs(), j.trueTargetPc(), targetPc);
            case Op.JumpGreaterThanEquals j -> new Op.JumpGreaterThanEquals(j.lhs(), j.rhs(), j.trueTargetPc(), targetPc);
            case Op.JumpStrictlyEquals    j -> new Op.JumpStrictlyEquals(j.lhs(), j.rhs(), j.trueTargetPc(), targetPc);
            case Op.JumpStrictlyInequals  j -> new Op.JumpStrictlyInequals(j.lhs(), j.rhs(), j.trueTargetPc(), targetPc);
            case Op.JumpLooselyEquals     j -> new Op.JumpLooselyEquals(j.lhs(), j.rhs(), j.trueTargetPc(), targetPc);
            case Op.JumpLooselyInequals   j -> new Op.JumpLooselyInequals(j.lhs(), j.rhs(), j.trueTargetPc(), targetPc);
            case Op.JumpNullish           j -> new Op.JumpNullish(j.condition(), j.trueTargetPc(), targetPc);

            default -> throw new IllegalStateException(
                "patchJumpTarget at pc=" + pc + ": expected a jump opcode, got " + old.operation());
        };
        ops.set(pc, patched);
    }

    private int currentPc() { return ops.size(); }

    public int localCount() { return localNames.size(); }

    // ------------------------------------------------------------
    //  Program / statements
    // ------------------------------------------------------------

    private void lowerProgram(Program program) {
        // Pre-pass: hoist top-level `var` names. Pre-binds each on globalThis
        // to undefined at script-load. Initializers in the body emit SetGlobal.
        collectVarHoists(program.body());

        // Pre-pass: walk the AST allocating local slots for every nested
        // {@code let}/{@code const} declaration, in DFS post-order
        // (innermost-first). Top-level lets remain global lexical bindings
        // and are skipped (preAllocateLetSlotsForBlock checks atTopLevel).
        for (Statement s : program.body()) {
            preAllocateLetSlotsRecurse(s);
        }

        // Pre-pass: hoist top-level FunctionDeclarations. They're bound and
        // materialized at script-load time (matches LibJS) — no ops in the
        // body. Includes async/generator declarations, which are still
        // hoisted by the same VarStatement-style hoisting rule.
        for (Statement s : program.body()) {
            if (s instanceof FunctionDeclaration fd && fd.id() != null) {
                String name = fd.id().name();
                globalNames.add(name);
                hoistedNames.add(name);
                JSFunction fn = generateFunction(name, fd.params(), fd.body(),
                    /* isArrow */ false, fd.generator(), fd.async());
                hoistedFunctions.add(new Executable.HoistedFunction(name, fn));
            }
        }

        // Pre-pass: collect function declarations inside switch cases / nested
        // blocks (Annex-B "block-scoped function declarations"). When present,
        // the script gets a special prologue: a fresh lex env, mutable
        // bindings + initialization for each fn, plus a SetLexicalEnvironment
        // teardown before End.
        collectAnnexBFunctionDecls(program.body());
        boolean hasAnnexBFns = !annexBFunctionDecls.isEmpty();

        // Pre-pass: save the lexical environment up-front if the body
        // contains any try-statement, named function expression, class
        // declaration, or class expression (each creates a private lexical
        // scope that needs the saved env as its parent). LibJS emits this
        // before the first user statement.
        if (hasAnnexBFns
            || containsTryStatement(program.body())
            || containsNamedFunctionExpression(program.body())
            || containsClassDeclaration(program.body())
            || containsClassExpression(program.body())
            || containsForOfStatement(program.body())) {
            emit(new Op.GetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
            lexicalEnvironmentSaved = true;
        }

        // Annex-B prologue: alloc completion register (reg5) + Mov Undef,
        // alloc env reg (reg6) + CreateLexEnv, then for each fn-decl:
        // CreateMutableBinding + NewFunction + InitializeLexicalBinding.
        // The env reg stays alive across the script so subsequent
        // GetBinding/SetVariableBinding ops have a target.
        Variable.Register annexBCompletionReg = null;
        Variable.Register annexBEnvReg = null;
        if (hasAnnexBFns) {
            annexBCompletionReg = allocRegister();
            emit(new Op.Mov(annexBCompletionReg, constant(Undefined.VALUE)));
            annexBEnvReg = allocRegister();
            emit(new Op.CreateLexicalEnvironment(annexBEnvReg,
                Variable.Register.SAVED_LEXICAL_ENVIRONMENT, 0));
            inAnnexBPrologue = true;
            try {
                for (FunctionDeclaration fd : annexBFunctionDecls) {
                    String name = fd.id().name();
                    emit(new Op.CreateMutableBinding(annexBEnvReg,
                        /* canBeDeleted */ false, name));
                    JSFunction fn = generateFunction(name, fd.params(), fd.body(),
                        /* isArrow */ false, fd.generator());
                    int fnIndex = sharedFunctionData.size();
                    sharedFunctionData.add(fn);
                    Variable.Register fnReg = allocRegister();
                    emit(new Op.NewFunction(fnReg, fnIndex, /* displayName */ null, null));
                    emit(new Op.InitializeLexicalBinding(name, fnReg, new EnvironmentCoordinate()));
                    release(fnReg);
                    lexEnvBindingNames.add(name);
                }
            } finally {
                inAnnexBPrologue = false;
            }
        }

        // Main pass: lower each top-level statement (skipping hoisted decls).
        //
        // LibJS rule (Rust port, codegen.rs:2517-2544): a single `last_result`
        // slot holds an Rc-clone of the most recent statement's return value.
        // When a new statement returns Some(value), the prior last_result's
        // Rc clone is dropped — releasing its register if it held one. A
        // statement that returns None (VarDecl with or without init) does
        // NOT update last_result, so it does not release the prior register.
        //
        // We mirror that: track ONE register slot; on each non-null return,
        // release the prior register (if any) and remember the new one.
        Operand last = null;
        Variable.Register lastTopLevelReg = null;
        for (Statement s : program.body()) {
            if (s instanceof FunctionDeclaration fd && fd.id() != null
                && hoistedNames.contains(fd.id().name())) {
                continue;   // already hoisted
            }
            Operand value = lowerStatement(s);
            if (value != null) {
                // New last_result arrives — drop the previous Rc clone.
                if (lastTopLevelReg != null) {
                    release(lastTopLevelReg);
                }
                last = value;
                lastTopLevelReg = (value instanceof Variable.Register r) ? r : null;
            }
        }
        // Annex-B teardown: restore outer lex env before script's End.
        // The annexBCompletionReg holds the script's value for End.
        if (annexBCompletionReg != null) {
            emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
            emit(new Op.End(annexBCompletionReg));
        } else {
            if (last == null) last = constant(Undefined.VALUE);
            emit(new Op.End(last));
        }
        flushDeferredLoopBranches();
    }

    /**
     * Lower a statement. Returns the operand holding its completion value
     * (used at top-level to feed {@code End}), or {@code null} if the
     * statement has no useful completion value.
     */
    private Operand lowerStatement(Statement s) {
        return switch (s) {
            case ExpressionStatement es -> lowerExpressionStatement(es);
            case VariableDeclaration vd -> { lowerVarDecl(vd); yield null; }
            case ReturnStatement rs -> {
                Operand value = rs.argument() != null
                    ? lowerExpression(rs.argument())
                    : constant(Undefined.VALUE);
                emit(new Op.Return(value));
                yield null;
            }
            case ThrowStatement ts -> {
                Operand value = lowerExpression(ts.argument());
                emit(new Op.Throw(value));
                release(value);
                yield null;
            }
            case IfStatement is -> withNonTopLevel(() -> (Operand) lowerIfReturning(is));
            case WhileStatement ws -> withNonTopLevel(() -> (Operand) lowerWhileReturning(ws));
            case DoWhileStatement dws -> withNonTopLevel(() -> (Operand) lowerDoWhileReturning(dws));
            case EmptyStatement es -> null;   // bare `;` produces nothing
            case BlockStatement bs -> withNonTopLevel(() -> {
                // Establish a fresh let/const scope for this block. Pre-allocated
                // slots (via preAllocateLetSlotsForBlock) live in blockLetSlots
                // keyed by the AST node; we install them into `locals` for the
                // duration of the block, then restore on exit. Shadowing of
                // outer same-named lets is automatic because the inner's slot
                // overwrites the outer's entry while we're inside.
                java.util.LinkedHashMap<String, Integer> myLetSlots = blockLetSlots.get(bs);
                java.util.Map<String, Integer> savedShadowed = new java.util.HashMap<>();
                java.util.Set<String> introducedHere = java.util.Set.of();
                if (myLetSlots != null && !myLetSlots.isEmpty()) {
                    introducedHere = new java.util.HashSet<>(myLetSlots.keySet());
                    for (java.util.Map.Entry<String, Integer> e : myLetSlots.entrySet()) {
                        Integer prior = locals.get(e.getKey());
                        if (prior != null) savedShadowed.put(e.getKey(), prior);
                        locals.put(e.getKey(), e.getValue());
                    }
                }
                try {
                    // LibJS-style last_result tracking (codegen.rs:2517-2544):
                    // when this block has a completion register to propagate
                    // into, keep each child's dst alive until the NEXT child
                    // returns a register, then drop prior. Matches LibJS's
                    // pool state for compound bodies with multiple stmts.
                    boolean propagating = currentCompletionReg() != null;
                    boolean savedTracking = lastResultTrackingActive;
                    if (propagating) lastResultTrackingActive = true;
                    Operand last = null;
                    Variable.Register lastBlockReg = null;
                    try {
                        for (Statement inner : bs.body()) {
                            Operand v = lowerStatement(inner);
                            if (v != null) {
                                if (propagating
                                    && v instanceof Variable.Register r
                                    && r.index() >= Variable.Register.FIRST_USER_INDEX) {
                                    if (lastBlockReg != null) release(lastBlockReg);
                                    lastBlockReg = r;
                                } else if (propagating && lastBlockReg != null
                                    && !(v instanceof Variable.Register)) {
                                    // Non-register value (constant) arrives —
                                    // drop prior register tracking.
                                    release(lastBlockReg);
                                    lastBlockReg = null;
                                }
                                last = v;
                            }
                            if (isUnconditionalTerminator(inner)) break;
                        }
                    } finally {
                        lastResultTrackingActive = savedTracking;
                    }
                    // Release the final last_result so it doesn't leak past
                    // the block's scope.
                    if (lastBlockReg != null) release(lastBlockReg);
                    return propagating ? null : last;
                } finally {
                    // Restore: remove names introduced here, re-add prior
                    // shadowed mappings.
                    for (String name : introducedHere) {
                        Integer prior = savedShadowed.get(name);
                        if (prior != null) locals.put(name, prior);
                        else locals.remove(name);
                    }
                }
            });
            case FunctionDeclaration fd -> { lowerFunctionDeclaration(fd); yield null; }
            case ClassDeclaration cd -> { lowerClassDeclaration(cd); yield null; }
            case ImportDeclaration id -> { lowerImport(id); yield null; }
            case ExportNamedDeclaration end -> { lowerExportNamed(end); yield null; }
            case ExportDefaultDeclaration edd -> { lowerExportDefault(edd); yield null; }
            case ExportAllDeclaration ead -> { lowerExportAll(ead); yield null; }
            case ForStatement fs -> withNonTopLevel(() -> (Operand) lowerForReturning(fs));
            case ForOfStatement fos -> withNonTopLevel(() -> (Operand) lowerForEachReturning(fos.left(), fos.right(), fos.body(), false));
            case ForInStatement fis -> { withNonTopLevel(() -> lowerForEach(fis.left(), fis.right(), fis.body(), true)); yield null; }
            case TryStatement ts -> withNonTopLevel(() -> (Operand) lowerTry(ts));
            case WithStatement ws -> {
                // v1: at runtime, with semantics aren't fully implemented — but we
                // can lower the object and body so the bytecode shape is honest.
                Operand objVal = lowerExpression(ws.object());
                release(objVal);
                lowerStatement(ws.body());
                yield null;
            }
            case SwitchStatement ss -> { withNonTopLevel(() -> lowerSwitch(ss)); yield null; }
            case BreakStatement bs -> {
                LoopContext target = findLoopForLabel(bs.label() != null ? bs.label().name() : null);
                if (target == null) {
                    throw new UnsupportedOperationException("Generator: break outside of a matching loop");
                }
                // Match LibJS: emit Mov(targetLoop.completion, currentCompletion)
                // before the Jump (propagates inner scope's value to loop comp).
                Variable.Register currentComp = currentCompletionReg();
                if (target.completionRegister != null
                    && currentComp != null
                    && !target.completionRegister.equals(currentComp)) {
                    emit(new Op.Mov(target.completionRegister, currentComp));
                }
                if (target.breakIsFallThrough) {
                    // Flattened for-loop layout — the after-block is the next
                    // basic block in PC order, so omit the Jump entirely.
                } else if (target.breakTargetPc >= 0) {
                    emit(new Op.Jump(target.breakTargetPc));
                } else {
                    int pc = emit(new Op.Jump(/* placeholder */ -1));
                    target.pendingBreakPcs.add(pc);
                }
                yield null;
            }
            case ContinueStatement cs -> {
                LoopContext target = findLoopForLabel(cs.label() != null ? cs.label().name() : null);
                if (target == null) {
                    throw new UnsupportedOperationException("Generator: continue outside of a matching loop");
                }
                // LibJS emits Mov(targetLoop.completion, currentScope.completion)
                // before the Jump — propagates the inner scope's completion
                // value to the loop's completion on every abrupt exit. Skip
                // the Mov if the source isn't a register (no-op transfer) or
                // already aliases the destination.
                Variable.Register currentComp = currentCompletionReg();
                if (target.completionRegister != null
                    && currentComp != null
                    && !target.completionRegister.equals(currentComp)) {
                    emit(new Op.Mov(target.completionRegister, currentComp));
                }
                // If the loop's continue target is already known (deferred
                // branches emitted after the loop's spine), emit the Jump
                // with the resolved target directly. Otherwise queue for the
                // loop's normal patch pass.
                if (target.continueTargetPc >= 0) {
                    emit(new Op.Jump(target.continueTargetPc));
                } else {
                    int pc = emit(new Op.Jump(/* placeholder */ -1));
                    target.pendingContinuePcs.add(pc);
                }
                yield null;
            }
            case LabeledStatement ls -> {
                // Labeled block (`label: { ... break label; ... }`): create
                // a break-only context whose label resolves to "after the
                // block". For labeled loops, fall through to the
                // pendingLoopLabel mechanism so the loop attaches the label.
                Operand bodyValue;
                if (ls.body() instanceof BlockStatement bs) {
                    LoopContext blockCtx = new LoopContext();
                    blockCtx.label = ls.label().name();
                    blockCtx.completionRegister = currentCompletionReg();
                    // Break to the labeled block's end is fall-through: the
                    // after-block is the immediately-following block in PC
                    // order, so the Jump can be omitted (LibJS does the
                    // same). Dead code after the break is skipped by the
                    // BlockStatement loop's terminator check.
                    blockCtx.breakIsFallThrough = true;
                    loopStack.push(blockCtx);
                    try {
                        // Delegate to BlockStatement lowering so let/const scoping
                        // and the per-block locals save/restore mechanism run.
                        bodyValue = lowerStatement(bs);
                    } finally {
                        loopStack.pop();
                    }
                    // Always start a fresh after-block so subsequent statements
                    // land in their own basic block — matches LibJS even when
                    // no explicit Jump targets the after-block.
                    startNewBlock();
                } else {
                    pendingLoopLabel = ls.label().name();
                    bodyValue = lowerStatement(ls.body());
                    pendingLoopLabel = null;
                }
                // Propagate the body's completion value so the script-end
                // {@code End} op can use it (matches LibJS for top-level
                // `label: { expr; }` constructs).
                yield bodyValue;
            }
            default -> throw new UnsupportedOperationException(
                "Generator: statement type " + s.getClass().getSimpleName() + " is not yet supported");
        };
    }

    /**
     * Lower a {@code function name(params) { body }} declaration:
     * compile the body to a nested {@link JSFunction}, register it in
     * {@link #sharedFunctionData}, then bind the result to {@code name}.
     */
    /**
     * v1 module support: declarations are stubbed — we register imported names as
     * globals (initialized to undefined) so references to them lower successfully.
     * Real module linking comes when the module loader lands.
     */
    private void lowerImport(ImportDeclaration id) {
        if (!atTopLevel) {
            throw new UnsupportedOperationException("Generator: nested import declarations are illegal");
        }
        for (Node spec : id.specifiers()) {
            String localName;
            if (spec instanceof ImportDefaultSpecifier ids) {
                localName = ids.local().name();
            } else if (spec instanceof ImportNamespaceSpecifier ins) {
                localName = ins.local().name();
            } else if (spec instanceof ImportSpecifier is) {
                localName = is.local().name();
            } else {
                throw new UnsupportedOperationException(
                    "Generator: import specifier type " + spec.getClass().getSimpleName() + " not supported");
            }
            globalNames.add(localName);
            emit(new Op.InitializeLexicalBinding(localName, constant(Undefined.VALUE), new EnvironmentCoordinate()));
        }
    }

    /** {@code export} of a declaration just lowers the declaration; named-export
     *  specifiers and re-exports are recorded but otherwise emit nothing in v1. */
    private void lowerExportNamed(ExportNamedDeclaration end) {
        if (end.declaration() != null) {
            lowerStatement(end.declaration());
        }
        // specifiers / source: re-exports are stubbed — accept them silently in v1.
    }

    private void lowerExportDefault(ExportDefaultDeclaration edd) {
        // Lower the inner node — if it's an expression, evaluate (and discard);
        // if a declaration, lower it as a statement. Real "default export"
        // wiring will store it in the module's exports record.
        Node inner = edd.declaration();
        if (inner instanceof Statement s) {
            lowerStatement(s);
        } else if (inner instanceof Expression e) {
            Operand v = lowerExpression(e);
            release(v);
        } else {
            throw new UnsupportedOperationException(
                "Generator: export default of " + inner.getClass().getSimpleName() + " not supported");
        }
    }

    /** {@code export * from 'mod'} — bytecode-only stub. */
    private void lowerExportAll(ExportAllDeclaration ead) { /* no-op */ }

    private void lowerFunctionDeclaration(FunctionDeclaration fd) {
        if (fd.id() == null) {
            throw new UnsupportedOperationException("Generator: anonymous FunctionDeclaration not supported");
        }
        // Body-level FD already hoisted by the pre-pass in generateFunction:
        // emit nothing at the declaration site (the binding is already set).
        if (hoistedNestedFnDecls.contains(fd)) return;
        // Annex-B path: function-decl inside a switch case (or non-function
        // block). The function value was already materialized in the script
        // prologue and bound via InitializeLexicalBinding. At the declaration
        // site we re-bind via GetBinding + SetVariableBinding (matches LibJS).
        if (annexBFunctionDecls.contains(fd) && !inAnnexBPrologue) {
            String name = fd.id().name();
            Variable.Register reg = allocRegister();
            emit(new Op.GetBinding(reg, name, new EnvironmentCoordinate()));
            emit(new Op.SetVariableBinding(name, reg, new EnvironmentCoordinate()));
            release(reg);
            return;
        }
        // async/generator: we emit the bytecode (yield/await ops in the body) but
        // the runtime interpreter will refuse to execute yield/await without
        // suspend/resume. Compilation succeeds.
        // Register the function name as a global BEFORE compiling the body,
        // so the body can refer to itself (recursion) and to siblings.
        boolean isTopLevel = atTopLevel;
        if (isTopLevel) globalNames.add(fd.id().name());

        JSFunction fn = generateFunction(fd.id().name(), fd.params(), fd.body(),
            /* isArrow */ false, fd.generator(), fd.async());
        int fnIndex = sharedFunctionData.size();
        sharedFunctionData.add(fn);

        Variable.Register fnReg = allocRegister();
        emit(new Op.NewFunction(fnReg, fnIndex, fd.id().name(), null));

        if (isTopLevel) {
            emit(new Op.InitializeLexicalBinding(fd.id().name(), fnReg, new EnvironmentCoordinate()));
        } else {
            Variable.Local slot = localFor(fd.id().name());
            emit(new Op.Mov(slot, fnReg));
        }
        release(fnReg);
    }

    /** Run {@code action} with {@link #atTopLevel} forced to {@code false}. */
    private <T> T withNonTopLevel(java.util.function.Supplier<T> action) {
        boolean prev = atTopLevel;
        atTopLevel = false;
        try { return action.get(); } finally { atTopLevel = prev; }
    }

    private void withNonTopLevel(Runnable action) {
        boolean prev = atTopLevel;
        atTopLevel = false;
        try { action.run(); } finally { atTopLevel = prev; }
    }

    /**
     * Emit a conditional branch that falls through when the test is true and
     * jumps to a (later-patched) {@code falseTargetPc} when false. Returns the
     * pc of the emitted branch instruction so the caller can patch its false
     * target.
     *
     * <p>If the test is a comparison BinaryExpression, fuses into a
     * {@code JumpLessThan}-family opcode. Otherwise lowers the test to a
     * value and emits a generic {@code JumpFalse}.
     */
    /**
     * Emit a loop-test conditional branch. Fuses comparisons into
     * {@code JumpLessThan} etc. (matching LibJS, which only fuses at loop heads).
     */
    private int emitLoopConditionalBranch(Expression test) {
        if (test instanceof BinaryExpression bin && isFusableComparison(bin.operator())) {
            return emitFusedComparison(bin);
        }
        Operand cond = lowerExpression(test);
        int pc = emit(new Op.JumpFalse(cond, /* placeholder false target */ -1));
        release(cond);
        return pc;
    }

    private static boolean isFusableComparison(String op) {
        return switch (op) {
            case "<", "<=", ">", ">=", "===", "!==", "==", "!=" -> true;
            default -> false;
        };
    }

    /**
     * Emit a fused compare-and-branch. Caller must have verified the operator
     * is fusable via {@link #isFusableComparison}.
     *
     * <p>The fused jump's {@code trueTargetPc} is set to "fall through" — the
     * pc immediately after the jump. Only the false target needs patching by
     * the caller (via {@link #patchJumpTarget}).
     */
    private int emitFusedComparison(BinaryExpression bin) {
        Operand l = lowerExpression(bin.left());
        Operand r = lowerExpression(bin.right());
        // The instruction we're about to emit will land at currentPc(); the
        // next instruction (the fall-through, i.e. true target) is currentPc()+1.
        int trueTarget = currentPc() + 1;
        int pc = switch (bin.operator()) {
            case "<"   -> emit(new Op.JumpLessThan          (l, r, trueTarget, -1));
            case "<="  -> emit(new Op.JumpLessThanEquals    (l, r, trueTarget, -1));
            case ">"   -> emit(new Op.JumpGreaterThan       (l, r, trueTarget, -1));
            case ">="  -> emit(new Op.JumpGreaterThanEquals (l, r, trueTarget, -1));
            case "===" -> emit(new Op.JumpStrictlyEquals    (l, r, trueTarget, -1));
            case "!==" -> emit(new Op.JumpStrictlyInequals  (l, r, trueTarget, -1));
            case "=="  -> emit(new Op.JumpLooselyEquals     (l, r, trueTarget, -1));
            case "!="  -> emit(new Op.JumpLooselyInequals   (l, r, trueTarget, -1));
            default -> throw new IllegalStateException("non-fusable operator: " + bin.operator());
        };
        release(l);
        release(r);
        return pc;
    }

    /**
     * An ExpressionStatement evaluates an expression and discards the value
     * — except: (a) at script top level, the final ExpressionStatement's value
     * becomes the script's completion (read by {@code End}); (b) inside a
     * compound statement (if/while body), the value is written to the
     * enclosing completion register so the compound's own completion tracks
     * it per ECMAScript spec.
     */
    private Operand lowerExpressionStatement(ExpressionStatement es) {
        // In a lex-env catch body, drain any previous expression statement's
        // deferred Call temps BEFORE evaluating this expression. That places
        // callee+thisVal at the pool head when this expr's lowerCall runs,
        // matching LibJS's register cycling for chained-statement Calls in
        // a catch body that materializes a CreateLexicalEnvironment.
        if (lexEnvCatchBodyDepth > 0 && !endOfStatementReleases.isEmpty()) {
            for (Operand op : endOfStatementReleases) release(op);
            endOfStatementReleases.clear();
        }
        Operand v = lowerExpression(es.expression());
        Variable.Register completionReg = currentCompletionReg();
        if (completionReg != null) {
            emit(new Op.Mov(completionReg, v));
            // In a compound body that contains a nested block, leak the
            // call dst (don't release) so subsequent expression statements
            // allocate fresh registers — matches LibJS's pool cycling for
            // file 380 (do-while) and 392 (catch). Track the most recent
            // leak so the enclosing scope can release ONLY the last one
            // (older leaks are permanent — they expand the register file).
            //
            // When `lastResultTrackingActive` is set by an enclosing scope
            // (BlockStatement loop), skip release entirely and return v —
            // the enclosing scope handles the LibJS `last_result` rule
            // (release-prior-on-new-arrival).
            if (lastResultTrackingActive) {
                // Drain Call-temps that were deferred during a deferred-flush
                // expression-statement — same as the non-tracking path.
                if (lexEnvCatchBodyDepth == 0 && !endOfStatementReleases.isEmpty()) {
                    for (Operand op : endOfStatementReleases) release(op);
                    endOfStatementReleases.clear();
                }
                return v;
            }
            if (doWhileWithNestedBlockDepth == 0) {
                release(v);
            } else if (v instanceof Variable.Register vr
                && vr.index() >= Variable.Register.FIRST_USER_INDEX) {
                pendingExprStmtDstRelease = vr;
            }
            if (lexEnvCatchBodyDepth > 0) {
                // Defer drain to the next expression statement's start (or
                // leak it past the catch body). Leaving the call's dst at the
                // pool head means the catch arm's catchArmExtra alloc reuses
                // that slot — matches LibJS for tests like 372 where the
                // last call's dst becomes the catchArmExtra register.
            } else {
                // Drain Call-temps that were deferred during a deferred-flush
                // expression-statement — LibJS releases dst before callee.
                if (!endOfStatementReleases.isEmpty()) {
                    for (Operand op : endOfStatementReleases) release(op);
                    endOfStatementReleases.clear();
                }
            }
            return null;   // caller (compound body) discards the statement value
        }
        return v;          // top-level: caller may use this as the program completion
    }

    /**
     * Walk the top-level statement list collecting {@code var} declarations.
     * Each name is registered as a hoisted global and added to {@link #globalNames}
     * before any code is lowered.
     */
    private void collectVarHoists(java.util.List<Statement> stmts) {
        for (Statement s : stmts) {
            if (s instanceof VariableDeclaration vd && "var".equals(vd.kind())) {
                for (VariableDeclarator d : vd.declarations()) {
                    collectVarNames(d.id());
                }
            }
            // Walk into nested compound bodies (if/while/for/block) since `var`
            // hoists to the nearest enclosing function — at the script level,
            // that's the script.
            if (s instanceof BlockStatement b) collectVarHoists(b.body());
            else if (s instanceof IfStatement is) {
                collectVarHoists(java.util.List.of(is.consequent()));
                if (is.alternate() != null) collectVarHoists(java.util.List.of(is.alternate()));
            }
            else if (s instanceof WhileStatement ws) collectVarHoists(java.util.List.of(ws.body()));
            else if (s instanceof ForStatement fs) {
                if (fs.init() instanceof VariableDeclaration init && "var".equals(init.kind())) {
                    for (VariableDeclarator d : init.declarations()) collectVarNames(d.id());
                }
                collectVarHoists(java.util.List.of(fs.body()));
            }
            else if (s instanceof ForOfStatement fos && fos.left() instanceof VariableDeclaration init && "var".equals(init.kind())) {
                for (VariableDeclarator d : init.declarations()) collectVarNames(d.id());
            }
            else if (s instanceof ForInStatement fis && fis.left() instanceof VariableDeclaration init && "var".equals(init.kind())) {
                for (VariableDeclarator d : init.declarations()) collectVarNames(d.id());
            }
            else if (s instanceof TryStatement ts) {
                collectVarHoists(ts.block().body());
                if (ts.handler() != null) collectVarHoists(ts.handler().body().body());
                if (ts.finalizer() != null) collectVarHoists(ts.finalizer().body());
            }
            else if (s instanceof LabeledStatement ls) {
                collectVarHoists(java.util.List.of(ls.body()));
            }
            else if (s instanceof DoWhileStatement dws) {
                collectVarHoists(java.util.List.of(dws.body()));
            }
            else if (s instanceof SwitchStatement ss) {
                for (SwitchCase c : ss.cases()) collectVarHoists(c.consequent());
            }
            // Function and class declarations are not walked into — `var` inside
            // them belongs to that function's scope, not the script's.
        }
    }

    private void collectVarNames(Node pattern) {
        if (pattern instanceof Identifier id) {
            if (hoistedVarNameSet.add(id.name())) {
                hoistedVarNames.add(id.name());
                globalNames.add(id.name());
            }
        } else if (pattern instanceof ObjectPattern op) {
            for (Node n : op.properties()) {
                if (n instanceof Property p) collectVarNames(p.value());
                else if (n instanceof RestElement r) collectVarNames(r.argument());
            }
        } else if (pattern instanceof ArrayPattern ap) {
            for (Pattern p : ap.elements()) {
                if (p != null) collectVarNames(p);
            }
        } else if (pattern instanceof AssignmentPattern ap) {
            collectVarNames(ap.left());
        } else if (pattern instanceof RestElement r) {
            collectVarNames(r.argument());
        }
    }

    /**
     * True if {@code stmts} (or any nested expression in them) contains a
     * {@link FunctionExpression} with an {@code id} (named function
     * expression). Each named FE creates a private lexical environment with
     * SAVED_LEXICAL_ENVIRONMENT as its parent, so the prologue must save it.
     * We don't recurse into nested function/class bodies — those have their
     * own prologue.
     */
    /**
     * Returns true if any sub-node references {@code arguments} as a free
     * Identifier. Stops descent at nested non-arrow function-like boundaries
     * (FunctionExpression, FunctionDeclaration, MethodDefinition) since those
     * have their own {@code arguments} binding per ECMA-262 § 10.4.4.
     * ArrowFunctionExpression bodies <em>do</em> get descended — arrows
     * inherit {@code arguments} from the enclosing non-arrow function
     * (§ 10.2.1.4).
     */
    private static boolean referencesArguments(Node node) {
        if (node == null) return false;
        // Boundary: nested non-arrow function-like has its own arguments.
        if (node instanceof FunctionExpression || node instanceof FunctionDeclaration
                || node instanceof MethodDefinition) return false;
        if (node instanceof Identifier id) return "arguments".equals(id.name());
        // ArrowFunctionExpression: descend into params + body (arrows inherit
        // arguments from the enclosing non-arrow function).
        if (node instanceof ArrowFunctionExpression afe) {
            for (Pattern p : afe.params()) if (referencesArguments(p)) return true;
            return referencesArguments(afe.body());
        }
        // Expressions
        if (node instanceof BinaryExpression e)        return referencesArguments(e.left())  || referencesArguments(e.right());
        if (node instanceof LogicalExpression e)       return referencesArguments(e.left())  || referencesArguments(e.right());
        if (node instanceof AssignmentExpression e)    return referencesArguments(e.left())  || referencesArguments(e.right());
        if (node instanceof UnaryExpression e)         return referencesArguments(e.argument());
        if (node instanceof UpdateExpression e)        return referencesArguments(e.argument());
        if (node instanceof MemberExpression e)        return referencesArguments(e.object()) || referencesArguments(e.property());
        if (node instanceof ConditionalExpression e)   return referencesArguments(e.test()) || referencesArguments(e.consequent()) || referencesArguments(e.alternate());
        if (node instanceof CallExpression e) {
            if (referencesArguments(e.callee())) return true;
            for (Expression a : e.arguments()) if (referencesArguments(a)) return true;
            return false;
        }
        if (node instanceof NewExpression e) {
            if (referencesArguments(e.callee())) return true;
            for (Expression a : e.arguments()) if (referencesArguments(a)) return true;
            return false;
        }
        if (node instanceof SequenceExpression e) {
            for (Expression x : e.expressions()) if (referencesArguments(x)) return true;
            return false;
        }
        if (node instanceof ArrayExpression e) {
            for (Expression x : e.elements()) if (x != null && referencesArguments(x)) return true;
            return false;
        }
        if (node instanceof ObjectExpression e) {
            for (Node prop : e.properties()) if (referencesArguments(prop)) return true;
            return false;
        }
        if (node instanceof Property p) {
            if (p.computed() && referencesArguments(p.key())) return true;
            return referencesArguments(p.value());
        }
        if (node instanceof SpreadElement e)           return referencesArguments(e.argument());
        if (node instanceof RestElement e)             return referencesArguments(e.argument());
        if (node instanceof TemplateLiteral e) {
            for (Expression x : e.expressions()) if (referencesArguments(x)) return true;
            return false;
        }
        if (node instanceof TaggedTemplateExpression e) return referencesArguments(e.tag()) || referencesArguments(e.quasi());
        if (node instanceof ChainExpression e)         return referencesArguments(e.expression());
        if (node instanceof YieldExpression e)         return e.argument() != null && referencesArguments(e.argument());
        if (node instanceof AwaitExpression e)         return referencesArguments(e.argument());
        if (node instanceof ImportExpression e)        return referencesArguments(e.source());
        // Patterns
        if (node instanceof ArrayPattern e) {
            for (Node x : e.elements()) if (x != null && referencesArguments(x)) return true;
            return false;
        }
        if (node instanceof ObjectPattern e) {
            for (Node prop : e.properties()) if (referencesArguments(prop)) return true;
            return false;
        }
        if (node instanceof AssignmentPattern e)       return referencesArguments(e.left()) || referencesArguments(e.right());
        // Statements
        if (node instanceof ExpressionStatement s)     return referencesArguments(s.expression());
        if (node instanceof BlockStatement s) {
            for (Statement x : s.body()) if (referencesArguments(x)) return true;
            return false;
        }
        if (node instanceof VariableDeclaration s) {
            for (VariableDeclarator d : s.declarations()) {
                if (referencesArguments(d.id())) return true;
                if (d.init() != null && referencesArguments(d.init())) return true;
            }
            return false;
        }
        if (node instanceof IfStatement s)             return referencesArguments(s.test()) || referencesArguments(s.consequent()) || (s.alternate() != null && referencesArguments(s.alternate()));
        if (node instanceof WhileStatement s)          return referencesArguments(s.test()) || referencesArguments(s.body());
        if (node instanceof DoWhileStatement s)        return referencesArguments(s.test()) || referencesArguments(s.body());
        if (node instanceof ForStatement s) {
            if (s.init() != null && referencesArguments(s.init())) return true;
            if (s.test() != null && referencesArguments(s.test())) return true;
            if (s.update() != null && referencesArguments(s.update())) return true;
            return referencesArguments(s.body());
        }
        if (node instanceof ForInStatement s)          return referencesArguments(s.left()) || referencesArguments(s.right()) || referencesArguments(s.body());
        if (node instanceof ForOfStatement s)          return referencesArguments(s.left()) || referencesArguments(s.right()) || referencesArguments(s.body());
        if (node instanceof ReturnStatement s)         return s.argument() != null && referencesArguments(s.argument());
        if (node instanceof ThrowStatement s)          return referencesArguments(s.argument());
        if (node instanceof TryStatement s) {
            if (referencesArguments(s.block())) return true;
            if (s.handler() != null) {
                if (s.handler().param() != null && referencesArguments(s.handler().param())) return true;
                if (referencesArguments(s.handler().body())) return true;
            }
            if (s.finalizer() != null && referencesArguments(s.finalizer())) return true;
            return false;
        }
        if (node instanceof SwitchStatement s) {
            if (referencesArguments(s.discriminant())) return true;
            for (SwitchCase c : s.cases()) {
                if (c.test() != null && referencesArguments(c.test())) return true;
                for (Statement x : c.consequent()) if (referencesArguments(x)) return true;
            }
            return false;
        }
        if (node instanceof LabeledStatement s)        return referencesArguments(s.body());
        if (node instanceof WithStatement s)           return referencesArguments(s.object()) || referencesArguments(s.body());
        if (node instanceof ClassExpression || node instanceof ClassDeclaration) {
            // Class bodies contain methods (which are MethodDefinitions —
            // boundary above stops them) plus computed key expressions and
            // field initializers, which DO see the enclosing function's
            // arguments. But our v1 punts: descending into class bodies
            // requires per-member rules (key vs. body) we haven't wired.
            // Safe over-approximation: treat as no reference.
            return false;
        }
        // EmptyStatement, BreakStatement, ContinueStatement, DebuggerStatement,
        // Literal, ThisExpression, etc. — no nested identifiers to scan.
        return false;
    }

    private static boolean containsNamedFunctionExpression(List<Statement> stmts) {
        for (Statement s : stmts) {
            if (statementContainsNamedFE(s)) return true;
        }
        return false;
    }

    private static boolean statementContainsNamedFE(Statement s) {
        if (s instanceof ExpressionStatement es) return expressionContainsNamedFE(es.expression());
        if (s instanceof VariableDeclaration vd) {
            for (VariableDeclarator d : vd.declarations()) {
                if (d.init() != null && expressionContainsNamedFE(d.init())) return true;
            }
            return false;
        }
        if (s instanceof ReturnStatement rs) return rs.argument() != null && expressionContainsNamedFE(rs.argument());
        if (s instanceof ThrowStatement ts) return expressionContainsNamedFE(ts.argument());
        if (s instanceof IfStatement is) {
            if (expressionContainsNamedFE(is.test())) return true;
            if (statementContainsNamedFE(is.consequent())) return true;
            if (is.alternate() != null && statementContainsNamedFE(is.alternate())) return true;
            return false;
        }
        if (s instanceof BlockStatement b) return containsNamedFunctionExpression(b.body());
        if (s instanceof WhileStatement ws) {
            return expressionContainsNamedFE(ws.test()) || statementContainsNamedFE(ws.body());
        }
        if (s instanceof DoWhileStatement dws) {
            return expressionContainsNamedFE(dws.test()) || statementContainsNamedFE(dws.body());
        }
        if (s instanceof ForStatement fs) {
            if (fs.init() instanceof Expression initExpr && expressionContainsNamedFE(initExpr)) return true;
            if (fs.init() instanceof VariableDeclaration vd) {
                for (VariableDeclarator d : vd.declarations()) {
                    if (d.init() != null && expressionContainsNamedFE(d.init())) return true;
                }
            }
            if (fs.test() != null && expressionContainsNamedFE(fs.test())) return true;
            if (fs.update() != null && expressionContainsNamedFE(fs.update())) return true;
            return statementContainsNamedFE(fs.body());
        }
        if (s instanceof TryStatement ts) {
            if (containsNamedFunctionExpression(ts.block().body())) return true;
            if (ts.handler() != null && containsNamedFunctionExpression(ts.handler().body().body())) return true;
            if (ts.finalizer() != null && containsNamedFunctionExpression(ts.finalizer().body())) return true;
            return false;
        }
        return false;
    }

    /**
     * True if {@code stmts} contains any nested function/arrow/class expression
     * (or eval call) reachable without crossing into another function body.
     * Catch bodies that contain such constructs need the catch parameter
     * materialized into a {@code CreateLexicalEnvironment} scope (so the
     * nested closure can capture it).
     */
    private static boolean catchBodyNeedsLexEnv(List<Statement> stmts) {
        for (Statement s : stmts) {
            if (statementContainsClosure(s)) return true;
        }
        return false;
    }

    private static boolean statementContainsClosure(Statement s) {
        if (s instanceof ExpressionStatement es) return expressionContainsClosure(es.expression());
        if (s instanceof VariableDeclaration vd) {
            for (VariableDeclarator d : vd.declarations()) {
                if (d.init() != null && expressionContainsClosure(d.init())) return true;
            }
            return false;
        }
        if (s instanceof ReturnStatement rs) return rs.argument() != null && expressionContainsClosure(rs.argument());
        if (s instanceof ThrowStatement ts) return expressionContainsClosure(ts.argument());
        if (s instanceof IfStatement is) {
            if (expressionContainsClosure(is.test())) return true;
            if (statementContainsClosure(is.consequent())) return true;
            if (is.alternate() != null && statementContainsClosure(is.alternate())) return true;
            return false;
        }
        if (s instanceof BlockStatement b) return catchBodyNeedsLexEnv(b.body());
        if (s instanceof WhileStatement ws) {
            return expressionContainsClosure(ws.test()) || statementContainsClosure(ws.body());
        }
        if (s instanceof DoWhileStatement dws) {
            return expressionContainsClosure(dws.test()) || statementContainsClosure(dws.body());
        }
        if (s instanceof ForStatement fs) {
            if (fs.init() instanceof Expression initExpr && expressionContainsClosure(initExpr)) return true;
            if (fs.init() instanceof VariableDeclaration vd) {
                for (VariableDeclarator d : vd.declarations()) {
                    if (d.init() != null && expressionContainsClosure(d.init())) return true;
                }
            }
            if (fs.test() != null && expressionContainsClosure(fs.test())) return true;
            if (fs.update() != null && expressionContainsClosure(fs.update())) return true;
            return statementContainsClosure(fs.body());
        }
        if (s instanceof TryStatement ts) {
            if (catchBodyNeedsLexEnv(ts.block().body())) return true;
            if (ts.handler() != null && catchBodyNeedsLexEnv(ts.handler().body().body())) return true;
            if (ts.finalizer() != null && catchBodyNeedsLexEnv(ts.finalizer().body())) return true;
            return false;
        }
        if (s instanceof FunctionDeclaration) return true;
        if (s instanceof ClassDeclaration) return true;
        if (s instanceof LabeledStatement ls) return statementContainsClosure(ls.body());
        return false;
    }

    /**
     * True if a do-while loop body (which is typically a BlockStatement)
     * contains a nested BlockStatement among its top-level statements. This
     * triggers the {@link #doWhileWithNestedBlockDepth} mechanism to leak
     * call-dst registers, matching LibJS's allocator cycling for tests like
     * 380 where the inner block's calls need pool slots one higher than the
     * outer body's.
     */
    private static boolean doWhileBodyHasNestedBlock(Statement body) {
        if (!(body instanceof BlockStatement bs)) return false;
        return catchBodyHasNestedBlock(bs.body());
    }

    private static boolean catchBodyHasNestedBlock(java.util.List<Statement> stmts) {
        for (Statement s : stmts) {
            if (s instanceof BlockStatement) return true;
        }
        return false;
    }

    /**
     * Collect FunctionDeclarations inside switch cases at the program level
     * (Annex-B function-declaration-in-block scoping). Adds each to
     * {@link #annexBFunctionDecls}.
     */
    private void collectAnnexBFunctionDecls(java.util.List<Statement> stmts) {
        for (Statement s : stmts) {
            collectAnnexBFromStatement(s);
        }
    }

    private void collectAnnexBFromStatement(Statement s) {
        if (s instanceof SwitchStatement ss) {
            for (com.jimmyhmiller.harmonica.ast.SwitchCase c : ss.cases()) {
                for (Statement cs : c.consequent()) {
                    if (cs instanceof FunctionDeclaration fd && fd.id() != null) {
                        annexBFunctionDecls.add(fd);
                    } else {
                        collectAnnexBFromStatement(cs);
                    }
                }
            }
        } else if (s instanceof BlockStatement bs) {
            for (Statement inner : bs.body()) {
                collectAnnexBFromStatement(inner);
            }
        } else if (s instanceof IfStatement is) {
            collectAnnexBFromStatement(is.consequent());
            if (is.alternate() != null) collectAnnexBFromStatement(is.alternate());
        } else if (s instanceof WhileStatement ws) {
            collectAnnexBFromStatement(ws.body());
        } else if (s instanceof DoWhileStatement dws) {
            collectAnnexBFromStatement(dws.body());
        } else if (s instanceof ForStatement fs) {
            collectAnnexBFromStatement(fs.body());
        } else if (s instanceof TryStatement ts) {
            collectAnnexBFromStatement(ts.block());
            if (ts.handler() != null) collectAnnexBFromStatement(ts.handler().body());
            if (ts.finalizer() != null) collectAnnexBFromStatement(ts.finalizer());
        } else if (s instanceof LabeledStatement ls) {
            collectAnnexBFromStatement(ls.body());
        }
    }

    private static boolean expressionContainsClosure(Expression e) {
        if (e == null) return false;
        if (e instanceof FunctionExpression) return true;
        if (e instanceof ArrowFunctionExpression) return true;
        if (e instanceof ClassExpression) return true;
        if (e instanceof BinaryExpression be) {
            return expressionContainsClosure(be.left()) || expressionContainsClosure(be.right());
        }
        if (e instanceof LogicalExpression le) {
            return expressionContainsClosure(le.left()) || expressionContainsClosure(le.right());
        }
        if (e instanceof UnaryExpression ue) return expressionContainsClosure(ue.argument());
        if (e instanceof MemberExpression me) {
            return expressionContainsClosure(me.object())
                || (me.computed() && me.property() instanceof Expression p && expressionContainsClosure(p));
        }
        if (e instanceof CallExpression c) {
            // eval(...) also forces lex-env binding (eval can reference any in-scope name).
            if (c.callee() instanceof Identifier id && "eval".equals(id.name())) return true;
            if (expressionContainsClosure(c.callee())) return true;
            for (Expression a : c.arguments()) if (expressionContainsClosure(a)) return true;
            return false;
        }
        if (e instanceof NewExpression ne) {
            if (expressionContainsClosure(ne.callee())) return true;
            for (Expression a : ne.arguments()) if (expressionContainsClosure(a)) return true;
            return false;
        }
        if (e instanceof AssignmentExpression ae) {
            return expressionContainsClosure(ae.right());
        }
        if (e instanceof ConditionalExpression ce) {
            return expressionContainsClosure(ce.test())
                || expressionContainsClosure(ce.consequent())
                || expressionContainsClosure(ce.alternate());
        }
        return false;
    }

    private static boolean expressionContainsNamedFE(Expression e) {
        if (e == null) return false;
        if (e instanceof FunctionExpression fe && fe.id() != null) return true;
        // Don't recurse into FunctionExpression bodies — they have their own prologue.
        if (e instanceof FunctionExpression) return false;
        if (e instanceof ArrowFunctionExpression) return false;
        if (e instanceof BinaryExpression be) {
            return expressionContainsNamedFE(be.left()) || expressionContainsNamedFE(be.right());
        }
        if (e instanceof LogicalExpression le) {
            return expressionContainsNamedFE(le.left()) || expressionContainsNamedFE(le.right());
        }
        if (e instanceof UnaryExpression ue) return expressionContainsNamedFE(ue.argument());
        if (e instanceof MemberExpression me) {
            return expressionContainsNamedFE(me.object())
                || (me.computed() && me.property() instanceof Expression p && expressionContainsNamedFE(p));
        }
        if (e instanceof CallExpression c) {
            if (expressionContainsNamedFE(c.callee())) return true;
            for (Expression a : c.arguments()) if (expressionContainsNamedFE(a)) return true;
            return false;
        }
        if (e instanceof NewExpression ne) {
            if (expressionContainsNamedFE(ne.callee())) return true;
            for (Expression a : ne.arguments()) if (expressionContainsNamedFE(a)) return true;
            return false;
        }
        if (e instanceof AssignmentExpression ae) {
            return expressionContainsNamedFE(ae.right());
        }
        if (e instanceof ConditionalExpression ce) {
            return expressionContainsNamedFE(ce.test())
                || expressionContainsNamedFE(ce.consequent())
                || expressionContainsNamedFE(ce.alternate());
        }
        return false;
    }

    /**
     * True if {@code stmts} contains a top-level {@link ClassDeclaration}
     * — used by the prologue pre-pass to decide whether to emit
     * {@code GetLexicalEnvironment}. Doesn't recurse into nested function/class
     * bodies (those have their own prologue).
     */
    /**
     * True if {@code stmts} contains any {@link ClassExpression} reachable
     * without crossing into nested function/class bodies. Used by the
     * prologue pre-pass — class expressions need the saved env for their
     * own private lex env.
     */
    private static boolean containsClassExpression(List<Statement> stmts) {
        for (Statement s : stmts) {
            if (statementContainsClassExpr(s)) return true;
        }
        return false;
    }

    private static boolean statementContainsClassExpr(Statement s) {
        if (s instanceof ExpressionStatement es) return expressionContainsClassExpr(es.expression());
        if (s instanceof VariableDeclaration vd) {
            for (VariableDeclarator d : vd.declarations()) {
                if (d.init() != null && expressionContainsClassExpr(d.init())) return true;
            }
            return false;
        }
        if (s instanceof ReturnStatement rs) return rs.argument() != null && expressionContainsClassExpr(rs.argument());
        if (s instanceof ThrowStatement ts) return expressionContainsClassExpr(ts.argument());
        if (s instanceof IfStatement is) {
            if (expressionContainsClassExpr(is.test())) return true;
            if (statementContainsClassExpr(is.consequent())) return true;
            if (is.alternate() != null && statementContainsClassExpr(is.alternate())) return true;
            return false;
        }
        if (s instanceof BlockStatement b) return containsClassExpression(b.body());
        if (s instanceof TryStatement ts) {
            if (containsClassExpression(ts.block().body())) return true;
            if (ts.handler() != null && containsClassExpression(ts.handler().body().body())) return true;
            if (ts.finalizer() != null && containsClassExpression(ts.finalizer().body())) return true;
            return false;
        }
        return false;
    }

    private static boolean expressionContainsClassExpr(Expression e) {
        if (e == null) return false;
        if (e instanceof ClassExpression) return true;
        if (e instanceof FunctionExpression || e instanceof ArrowFunctionExpression) return false;
        if (e instanceof BinaryExpression be) {
            return expressionContainsClassExpr(be.left()) || expressionContainsClassExpr(be.right());
        }
        if (e instanceof LogicalExpression le) {
            return expressionContainsClassExpr(le.left()) || expressionContainsClassExpr(le.right());
        }
        if (e instanceof UnaryExpression ue) return expressionContainsClassExpr(ue.argument());
        if (e instanceof MemberExpression me) {
            return expressionContainsClassExpr(me.object())
                || (me.computed() && me.property() instanceof Expression p && expressionContainsClassExpr(p));
        }
        if (e instanceof CallExpression c) {
            if (expressionContainsClassExpr(c.callee())) return true;
            for (Expression a : c.arguments()) if (expressionContainsClassExpr(a)) return true;
            return false;
        }
        if (e instanceof AssignmentExpression ae) return expressionContainsClassExpr(ae.right());
        if (e instanceof ConditionalExpression ce) {
            return expressionContainsClassExpr(ce.test())
                || expressionContainsClassExpr(ce.consequent())
                || expressionContainsClassExpr(ce.alternate());
        }
        return false;
    }

    private static boolean containsForOfStatement(List<Statement> stmts) {
        for (Statement s : stmts) {
            if (s instanceof ForOfStatement) return true;
            if (s instanceof BlockStatement b && containsForOfStatement(b.body())) return true;
            if (s instanceof IfStatement is) {
                if (containsForOfStatement(java.util.List.of(is.consequent()))) return true;
                if (is.alternate() != null && containsForOfStatement(java.util.List.of(is.alternate()))) return true;
            }
            if (s instanceof WhileStatement ws && containsForOfStatement(java.util.List.of(ws.body()))) return true;
            if (s instanceof DoWhileStatement dws && containsForOfStatement(java.util.List.of(dws.body()))) return true;
            if (s instanceof ForStatement fs && containsForOfStatement(java.util.List.of(fs.body()))) return true;
            if (s instanceof TryStatement ts) {
                if (containsForOfStatement(ts.block().body())) return true;
                if (ts.handler() != null && containsForOfStatement(ts.handler().body().body())) return true;
                if (ts.finalizer() != null && containsForOfStatement(ts.finalizer().body())) return true;
            }
        }
        return false;
    }

    private static boolean containsClassDeclaration(List<Statement> stmts) {
        for (Statement s : stmts) {
            if (s instanceof ClassDeclaration) return true;
            if (s instanceof BlockStatement b && containsClassDeclaration(b.body())) return true;
            if (s instanceof IfStatement is) {
                if (containsClassDeclaration(java.util.List.of(is.consequent()))) return true;
                if (is.alternate() != null && containsClassDeclaration(java.util.List.of(is.alternate()))) return true;
            }
            if (s instanceof TryStatement ts) {
                if (containsClassDeclaration(ts.block().body())) return true;
                if (ts.handler() != null && containsClassDeclaration(ts.handler().body().body())) return true;
                if (ts.finalizer() != null && containsClassDeclaration(ts.finalizer().body())) return true;
            }
        }
        return false;
    }

    /**
     * True if {@code stmts} (or any nested statement reachable from them
     * without crossing into nested function/class bodies) contains a
     * {@link TryStatement}. Used as a pre-pass guard for emitting
     * {@code GetLexicalEnvironment} at the script/function prologue.
     */
    private static boolean containsTryStatement(List<Statement> stmts) {
        for (Statement s : stmts) {
            if (s instanceof TryStatement) return true;
            if (s instanceof BlockStatement b && containsTryStatement(b.body())) return true;
            if (s instanceof IfStatement is) {
                if (containsTryStatement(java.util.List.of(is.consequent()))) return true;
                if (is.alternate() != null && containsTryStatement(java.util.List.of(is.alternate()))) return true;
            }
            if (s instanceof WhileStatement ws && containsTryStatement(java.util.List.of(ws.body()))) return true;
            if (s instanceof DoWhileStatement dws && containsTryStatement(java.util.List.of(dws.body()))) return true;
            if (s instanceof ForStatement fs && containsTryStatement(java.util.List.of(fs.body()))) return true;
            if (s instanceof ForOfStatement fos && containsTryStatement(java.util.List.of(fos.body()))) return true;
            if (s instanceof ForInStatement fis && containsTryStatement(java.util.List.of(fis.body()))) return true;
            if (s instanceof LabeledStatement ls && containsTryStatement(java.util.List.of(ls.body()))) return true;
            if (s instanceof SwitchStatement ss) {
                for (SwitchCase c : ss.cases()) {
                    if (containsTryStatement(c.consequent())) return true;
                }
            }
            // Function and class declarations are NOT walked — their try-statements
            // belong to that nested scope's prologue, not this one.
        }
        return false;
    }

    /**
     * Walk a function body's statement list and pre-allocate a local slot for
     * every {@code var} name. Mirrors the script-level {@link #collectVarHoists}
     * but binds locals (function-scope) instead of globals. Recurses through
     * compound statements but stops at nested {@code FunctionDeclaration},
     * {@code FunctionExpression}, {@code ArrowFunctionExpression}, and
     * {@code ClassDeclaration} bodies — those are different scopes.
     */
    private void collectFunctionScopeVarLocals(java.util.List<Statement> stmts) {
        for (Statement s : stmts) {
            if (s instanceof VariableDeclaration vd && "var".equals(vd.kind())) {
                for (VariableDeclarator d : vd.declarations()) {
                    collectFunctionScopeVarLocalNames(d.id());
                }
            }
            if (s instanceof BlockStatement b) collectFunctionScopeVarLocals(b.body());
            else if (s instanceof IfStatement is) {
                collectFunctionScopeVarLocals(java.util.List.of(is.consequent()));
                if (is.alternate() != null) collectFunctionScopeVarLocals(java.util.List.of(is.alternate()));
            }
            else if (s instanceof WhileStatement ws) collectFunctionScopeVarLocals(java.util.List.of(ws.body()));
            else if (s instanceof DoWhileStatement dws) collectFunctionScopeVarLocals(java.util.List.of(dws.body()));
            else if (s instanceof ForStatement fs) {
                if (fs.init() instanceof VariableDeclaration init && "var".equals(init.kind())) {
                    for (VariableDeclarator d : init.declarations()) collectFunctionScopeVarLocalNames(d.id());
                }
                collectFunctionScopeVarLocals(java.util.List.of(fs.body()));
            }
            else if (s instanceof ForOfStatement fos) {
                if (fos.left() instanceof VariableDeclaration init && "var".equals(init.kind())) {
                    for (VariableDeclarator d : init.declarations()) collectFunctionScopeVarLocalNames(d.id());
                }
                collectFunctionScopeVarLocals(java.util.List.of(fos.body()));
            }
            else if (s instanceof ForInStatement fis) {
                if (fis.left() instanceof VariableDeclaration init && "var".equals(init.kind())) {
                    for (VariableDeclarator d : init.declarations()) collectFunctionScopeVarLocalNames(d.id());
                }
                collectFunctionScopeVarLocals(java.util.List.of(fis.body()));
            }
            else if (s instanceof TryStatement ts) {
                collectFunctionScopeVarLocals(ts.block().body());
                if (ts.handler() != null) collectFunctionScopeVarLocals(ts.handler().body().body());
                if (ts.finalizer() != null) collectFunctionScopeVarLocals(ts.finalizer().body());
            }
            else if (s instanceof SwitchStatement ss) {
                for (SwitchCase c : ss.cases()) collectFunctionScopeVarLocals(c.consequent());
            }
            else if (s instanceof LabeledStatement ls) {
                collectFunctionScopeVarLocals(java.util.List.of(ls.body()));
            }
            // Function/Class declarations are not walked — they introduce their
            // own scope, and their `var`s belong there.
        }
    }

    private void collectFunctionScopeVarLocalNames(Node pattern) {
        if (pattern instanceof Identifier id) {
            // localFor is idempotent: returns existing slot if the name is
            // already a local (e.g. parameter binding with the same name).
            localFor(id.name());
        } else if (pattern instanceof ObjectPattern op) {
            for (Node n : op.properties()) {
                if (n instanceof Property p) collectFunctionScopeVarLocalNames(p.value());
                else if (n instanceof RestElement r) collectFunctionScopeVarLocalNames(r.argument());
            }
        } else if (pattern instanceof ArrayPattern ap) {
            for (Pattern p : ap.elements()) {
                if (p != null) collectFunctionScopeVarLocalNames(p);
            }
        } else if (pattern instanceof AssignmentPattern ap) {
            collectFunctionScopeVarLocalNames(ap.left());
        } else if (pattern instanceof RestElement r) {
            collectFunctionScopeVarLocalNames(r.argument());
        }
    }

    private void lowerVarDecl(VariableDeclaration vd) {
        boolean letLikeAtTopLevel = atTopLevel && ("let".equals(vd.kind()) || "const".equals(vd.kind()));
        boolean isHoistedVar = "var".equals(vd.kind());
        for (VariableDeclarator d : vd.declarations()) {
            // Hoisted `var` with no initializer: nothing to emit (already bound
            // to undefined at script load via hoistedVarNames).
            if (isHoistedVar && d.init() == null) continue;
            // ES2015 NamedEvaluation hint: when binding `var/let/const NAME = <fn>`,
            // any anonymous function or class on the RHS is named NAME.
            if (d.init() != null && d.id() instanceof Identifier varId
                && (d.init() instanceof FunctionExpression
                    || d.init() instanceof ArrowFunctionExpression
                    || d.init() instanceof ClassExpression)) {
                pendingFunctionName = varId.name();
            }
            Operand init = d.init() != null
                ? lowerExpression(d.init())
                : constant(Undefined.VALUE);
            pendingFunctionName = null;
            BindMode mode;
            if (isHoistedVar) {
                // The name is already a hoisted global — write it via SetGlobal,
                // not InitializeLexicalBinding.
                mode = BindMode.ASSIGN;
            } else if (letLikeAtTopLevel) {
                mode = BindMode.GLOBAL;
            } else {
                mode = BindMode.LOCAL;
            }
            bindPattern(d.id(), init, mode);
            release(init);
            // Drain Call-temps deferred by {@link #lowerCall} so they don't
            // leak across declarators / statements.
            if (!endOfStatementReleases.isEmpty()) {
                for (Operand op : endOfStatementReleases) release(op);
                endOfStatementReleases.clear();
            }
        }
    }

    private enum BindMode { LOCAL, GLOBAL, ASSIGN }

    /**
     * Bind {@code source} to {@code pattern}, emitting reads/writes as needed.
     *
     * <p>{@code mode} determines how leaf identifier patterns are bound:
     *   <ul>
     *     <li>{@code LOCAL} — allocate/use a {@link Variable.Local}.</li>
     *     <li>{@code GLOBAL} — emit {@code InitializeLexicalBinding}.</li>
     *     <li>{@code ASSIGN} — write to an existing binding (local, capture,
     *         or global).</li>
     *   </ul>
     *
     * <p>v1 omissions: rest elements in destructuring patterns; computed
     * keys in object patterns.
     */
    private void bindPattern(Node pattern, Operand source, BindMode mode) {
        switch (pattern) {
            case Identifier id -> bindIdentifier(id, source, mode);
            case ObjectPattern op -> bindObjectPattern(op, source, mode);
            case ArrayPattern ap -> bindArrayPattern(ap, source, mode);
            case AssignmentPattern ap -> bindAssignmentPattern(ap, source, mode);
            case MemberExpression me -> {
                if (mode != BindMode.ASSIGN) {
                    throw new UnsupportedOperationException(
                        "Generator: MemberExpression target only valid in ASSIGN mode");
                }
                // Destructuring path: LibJS omits the `(obj.foo)` annotation
                // on the resulting PutById/PutByValue (matches what we see for
                // `({ x.y } = vals)` etc.).
                writeBackAnyTarget(me, asRegister(source), /* annotateBase */ false);
            }
            default -> throw new UnsupportedOperationException(
                "Generator: pattern type " + pattern.getClass().getSimpleName() + " not supported in bindPattern");
        }
    }

    private void bindIdentifier(Identifier id, Operand source, BindMode mode) {
        switch (mode) {
            case LOCAL -> {
                Variable.Local slot = localFor(id.name());
                emit(new Op.Mov(slot, source));
            }
            case GLOBAL -> {
                globalNames.add(id.name());
                emit(new Op.InitializeLexicalBinding(id.name(), source, new EnvironmentCoordinate()));
            }
            case ASSIGN -> {
                // Mirror lowerAssignment's identifier path.
                Integer captured = captureSlot.get(id.name());
                if (captured != null) {
                    emit(new Op.Mov(new Variable.Local(captured), source));
                } else if (locals.containsKey(id.name())) {
                    emit(new Op.Mov(new Variable.Local(locals.get(id.name())), source));
                } else {
                    int upperSlot = captureFromOuter(id.name());
                    if (upperSlot >= 0) {
                        emit(new Op.Mov(new Variable.Local(upperSlot), source));
                    } else {
                        // Sloppy-mode implicit global: assigning to an
                        // undeclared identifier creates/writes a property on
                        // globalThis. LibJS lowers this with the same
                        // SetGlobal it uses for declared globals.
                        emit(new Op.SetGlobal(id.name(), source, new GlobalVariableCache()));
                    }
                }
            }
        }
    }

    private void bindObjectPattern(ObjectPattern op, Operand source, BindMode mode) {
        // Stash source in a register for repeated property reads.
        Variable.Register srcReg = asRegister(source);
        // LibJS guards object destructuring with a ThrowIfNullish — the spec
        // requires CoerceToObject which TypeErrors on null/undefined.
        emit(new Op.ThrowIfNullish(srcReg));
        java.util.List<String> consumedKeys = new ArrayList<>();
        // Runtime-evaluated computed keys must also be excluded from the rest
        // copy. We stash each computed key in a register so the rest-element
        // can iterate them via DeleteByValue post-CopyOwnProperties.
        java.util.List<Variable.Register> computedExcludeRegs = new ArrayList<>();
        for (Node propNode : op.properties()) {
            if (propNode instanceof RestElement rest) {
                // Object rest: collect remaining own properties into a fresh object.
                Variable.Register restObj = allocRegister();
                emit(new Op.NewObject(restObj));
                emit(new Op.CopyOwnProperties(restObj, srcReg,
                    consumedKeys.toArray(new String[0])));
                // Drop any property whose key matches a runtime-computed
                // exclude (per § 13.3.3.4 KeyedDestructuringAssignmentEvaluation
                // step 7's `excludedNames` list, which ToPropertyKeys all keys
                // including computed ones).
                Variable.Register dummyDelDst = allocRegister();
                for (Variable.Register keyReg : computedExcludeRegs) {
                    emit(new Op.DeleteByValue(dummyDelDst, restObj, keyReg));
                }
                release(dummyDelDst);
                bindPattern(rest.argument(), restObj, mode);
                release(restObj);
                continue;
            }
            if (!(propNode instanceof Property prop)) {
                throw new UnsupportedOperationException(
                    "Generator: object-pattern element type " + propNode.getClass().getSimpleName() + " not supported");
            }
            // Computed key (`{[k]: x} = src`): lower the key to an operand,
            // then GetByValue. Stash the key in a fresh register so it can
            // also feed the rest-element exclusion (see above).
            if (prop.computed()) {
                if (!(prop.key() instanceof Expression keyExpr)) {
                    throw new IllegalStateException("Computed key must be an Expression");
                }
                Operand keyOp = lowerExpression(keyExpr);
                Variable.Register keyReg = allocRegister();
                emit(new Op.Mov(keyReg, keyOp));
                release(keyOp);
                computedExcludeRegs.add(keyReg);
                Variable.Register valReg = allocRegister();
                emit(new Op.GetByValue(valReg, srcReg, keyReg, /* baseIdentifier */ null));
                bindPattern(prop.value(), valReg, mode);
                release(valReg);
                continue;
            }
            String keyStr;
            if (prop.key() instanceof Identifier idKey) {
                keyStr = idKey.name();
            } else if (prop.key() instanceof Literal lit) {
                keyStr = AbstractOps.toString(literalValue(lit));
            } else {
                throw new UnsupportedOperationException(
                    "Generator: unsupported destructuring key type " + prop.key().getClass().getSimpleName());
            }
            consumedKeys.add(keyStr);
            // Read source.key into a fresh register.
            Variable.Register valReg = allocRegister();
            emit(new Op.GetById(valReg, srcReg, keyStr, /* baseIdentifier */ null,
                new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            // Recurse into the value pattern.
            bindPattern(prop.value(), valReg, mode);
            release(valReg);
        }
    }

    private void bindArrayPattern(ArrayPattern ap, Operand source, BindMode mode) {
        // Find rest element, validate it's the last position.
        int restIndex = -1;
        Pattern restPattern = null;
        for (int i = 0; i < ap.elements().size(); i++) {
            Pattern e = ap.elements().get(i);
            if (e instanceof RestElement re) {
                if (i != ap.elements().size() - 1) {
                    throw new UnsupportedOperationException(
                        "Generator: RestElement must be the last element of an array pattern");
                }
                restIndex = i;
                restPattern = re;
            }
        }
        int numNonRest = (restIndex >= 0) ? restIndex : ap.elements().size();
        if (numNonRest == 0 && restPattern == null) {
            // `let [] = x;` — empty pattern. LibJS still emits the iterator
            // protocol setup + a JumpFalse(done) to the close block, since
            // GetIterator has observable side effects on `x` (calls
            // `x[Symbol.iterator]`). Match that shape: doneReg + GetIterator +
            // deferred close.
            Variable.Register doneReg = allocRegister();
            emit(new Op.Mov(doneReg, constant(Boolean.FALSE)));
            Variable.Register iterObj = allocRegister();
            Variable.Register iterNext = allocRegister();
            Variable.Register iterDone = allocRegister();
            emit(new Op.GetIterator(iterObj, iterNext, iterDone, source));
            int jumpFalseToClosePc = emit(new Op.JumpFalse(doneReg, /* placeholder */ -1));
            startNewBlock();
            int afterBlockStart = currentPc();
            deferredLoopBranches.add(new DeferredIteratorClose(
                jumpFalseToClosePc, afterBlockStart, iterObj, iterNext, iterDone));
            // Same release order as the non-empty no-rest path:
            // valueReg, iterDone, iterNext, iterObj, doneReg. No valueReg
            // here, but the order otherwise matches.
            release(iterDone);
            release(iterNext);
            release(iterObj);
            release(doneReg);
            return;
        }

        // 1. done flag = false
        Variable.Register doneReg = allocRegister();
        emit(new Op.Mov(doneReg, constant(Boolean.FALSE)));

        // 2. GetIterator(iterable) → (iterObj, iterNext, iterDone)
        Variable.Register iterObj = allocRegister();
        Variable.Register iterNext = allocRegister();
        Variable.Register iterDone = allocRegister();
        emit(new Op.GetIterator(iterObj, iterNext, iterDone, source));

        // 3. Per-element: fetch + bind. The same valueReg is reused across
        // elements (LibJS does this).
        Variable.Register valueReg = allocRegister();
        for (int i = 0; i < numNonRest; i++) {
            Pattern elt = ap.elements().get(i);
            boolean isHole = (elt == null);

            if (i == 0) {
                // First element: fetch directly into valueReg.
                emit(new Op.IteratorNextUnpack(valueReg, doneReg, iterObj, iterNext, iterDone));
                int jumpFalsePc = emit(new Op.JumpFalse(doneReg, /* placeholder */ -1));

                // "done" branch: assign Undefined.
                startNewBlock();
                emit(new Op.Mov(valueReg, constant(Undefined.VALUE)));
                int jumpToBindPc = emit(new Op.Jump(/* placeholder */ -1));

                // Bind block.
                startNewBlock();
                int bindBlockStart = currentPc();
                patchJumpTarget(jumpFalsePc, bindBlockStart);
                patchJumpTarget(jumpToBindPc, bindBlockStart);
            } else {
                // Subsequent element: skip-fetch if already done.
                int jumpFalseToFetchPc = emit(new Op.JumpFalse(doneReg, /* placeholder */ -1));

                // "done" block: Undefined → bind block.
                startNewBlock();
                int doneBlockStart = currentPc();
                emit(new Op.Mov(valueReg, constant(Undefined.VALUE)));
                int jumpToBindPc = emit(new Op.Jump(/* placeholder */ -1));

                // Fetch block: IteratorNextUnpack; if just-now done, Jump to done block.
                startNewBlock();
                int fetchBlockStart = currentPc();
                patchJumpTarget(jumpFalseToFetchPc, fetchBlockStart);
                emit(new Op.IteratorNextUnpack(valueReg, doneReg, iterObj, iterNext, iterDone));
                int jumpTrueToDonePc = emit(new Op.JumpTrue(doneReg, /* placeholder */ -1));
                patchJumpTarget(jumpTrueToDonePc, doneBlockStart);

                // Bind block.
                startNewBlock();
                int bindBlockStart = currentPc();
                patchJumpTarget(jumpToBindPc, bindBlockStart);
            }

            if (!isHole) {
                bindPattern(elt, valueReg, mode);
            }
        }

        if (restPattern != null) {
            // Rest: drain iterator into array (or empty array if already done).
            int jumpFalseToDrainPc = emit(new Op.JumpFalse(doneReg, /* placeholder */ -1));

            // Empty-rest block: NewArray + Jump to bind block.
            startNewBlock();
            Variable.Register restArr = allocRegister();
            emit(new Op.NewArray(restArr, new Operand[0]));
            int jumpToBindRestPc = emit(new Op.Jump(/* placeholder */ -1));

            // Drain block: IteratorToArray.
            startNewBlock();
            int drainBlockStart = currentPc();
            patchJumpTarget(jumpFalseToDrainPc, drainBlockStart);
            emit(new Op.IteratorToArray(restArr, iterObj, iterNext, iterDone));

            // Bind-rest block.
            startNewBlock();
            int bindRestBlockStart = currentPc();
            patchJumpTarget(jumpToBindRestPc, bindRestBlockStart);
            bindPattern(((RestElement) restPattern).argument(), restArr, mode);
            release(restArr);
        } else {
            // No rest: defer IteratorClose to the end of the function/program.
            // Match LibJS's layout where the close block lands as the last
            // block(s), with a back-Jump into the inline after-block.
            int jumpFalseToClosePc = emit(new Op.JumpFalse(doneReg, /* placeholder */ -1));
            startNewBlock();
            int afterBlockStart = currentPc();
            deferredLoopBranches.add(new DeferredIteratorClose(
                jumpFalseToClosePc, afterBlockStart, iterObj, iterNext, iterDone));
            // LibJS releases the iter slots back into the free pool at the
            // start of the after-block — the deferred close block reads them
            // before any after-block writes (execution: skip-close →
            // after-block, OR close → Jump → after-block, with close fully
            // consuming the iter values before Jumping). Subsequent
            // allocations in the after-block can therefore reuse those slots.
            //
            // Release order: valueReg, iterDone, iterNext, iterObj, doneReg.
            // The caller will then release the source value, putting it at
            // the LIFO pool head — matches LibJS's allocator state at the
            // start of the after-block (verified empirically on
            // obj-ptrn-prop-ary-trailing-comma.js).
            release(valueReg);
            release(iterDone);
            release(iterNext);
            release(iterObj);
            release(doneReg);
            return;
        }

        release(valueReg);
        if (restPattern != null) {
            release(iterObj);
            release(iterNext);
            release(iterDone);
        }
        release(doneReg);
    }

    private void finishDeferredIteratorClose(DeferredIteratorClose ic) {
        // Emit the close block: IteratorClose + Jump back to the after-block.
        startNewBlock();
        int closeBlockStart = currentPc();
        patchJumpTarget(ic.jumpFalsePc(), closeBlockStart);
        emit(new Op.IteratorClose(ic.iteratorObject(), ic.iteratorNext(),
                                  ic.iteratorDone(), constant(Undefined.VALUE)));
        // Peephole: when the after-block consists of a single End (typical for
        // script-tail destructuring), inline the End instead of Jump → End.
        // LibJS does the same — see ary-ptrn-elision-exhausted.js.
        int targetPc = ic.afterBlockStart();
        if (targetPc >= 0 && targetPc < ops.size() && ops.get(targetPc) instanceof Op.End endOp) {
            emit(new Op.End(endOp.value()));
        } else {
            emit(new Op.Jump(targetPc));
        }
        // iter slots were released at after-block start — no release here.
    }

    private void bindAssignmentPattern(AssignmentPattern ap, Operand source, BindMode mode) {
        // LibJS shape: when source is in a register, reuse that slot for both
        // the default-branch overwrite and the eventual binding. Layout:
        //
        //   JumpUndefined cond:source, true=defaultBlock, false=bindBlock
        //   defaultBlock: Mov source, default            (falls through)
        //   bindBlock:    bindPattern(left, source)
        //
        // When source is a constant we still need a temp to overwrite, since
        // we can't Mov into a constant.
        Variable.Register tmp;
        if (source instanceof Variable.Register r) {
            tmp = r;
        } else {
            tmp = allocRegister();
            emit(new Op.Mov(tmp, source));
        }

        int jumpUndefPc = emit(new Op.JumpUndefined(tmp,
            /* trueTarget (default block) */ -1,
            /* falseTarget (bind block) */ -1));
        // Default branch starts here (true target).
        startNewBlock();
        int defaultBlockStart = currentPc();
        // ES2015 NamedEvaluation: when the binding target is a plain
        // identifier and the default initializer is an anonymous function /
        // arrow / class, set its `.name` to the target's identifier.
        if (ap.left() instanceof Identifier id) {
            if (ap.right() instanceof FunctionExpression fe && fe.id() == null) {
                pendingFunctionName = id.name();
            } else if (ap.right() instanceof ArrowFunctionExpression) {
                pendingFunctionName = id.name();
            } else if (ap.right() instanceof ClassExpression ce && ce.id() == null) {
                pendingFunctionName = id.name();
            }
        }
        Operand defaultVal = lowerExpression(ap.right());
        pendingFunctionName = null;
        emit(new Op.Mov(tmp, defaultVal));
        release(defaultVal);
        // Falls through into bind block.
        startNewBlock();
        int bindBlockStart = currentPc();
        // Patch the JumpUndefined.
        Op old = ops.get(jumpUndefPc);
        if (!(old instanceof Op.JumpUndefined oldJu)) {
            throw new IllegalStateException("expected JumpUndefined at pc=" + jumpUndefPc);
        }
        ops.set(jumpUndefPc, new Op.JumpUndefined(oldJu.condition(), defaultBlockStart, bindBlockStart));
        bindPattern(ap.left(), tmp, mode);
        // Only release tmp if we allocated it (didn't reuse the source register).
        if (!(source instanceof Variable.Register)) release(tmp);
    }

    /** Stash an operand in a register so it can be read multiple times. */
    private Variable.Register asRegister(Operand source) {
        if (source instanceof Variable.Register r) return r;
        Variable.Register r = allocRegister();
        emit(new Op.Mov(r, source));
        return r;
    }

    /**
     * Lower an if-statement. Returns the completion register so the caller
     * (lowerProgram, etc.) can use it as the value of the if statement.
     */
    private Variable.Register lowerIfReturning(IfStatement is) {
        // Lower the test first so any literals it introduces land in the
        // constant pool BEFORE the {@code Undefined} we'll add for the
        // completion register's initial value — matches LibJS's source-order
        // pool numbering (test literals → Undefined → consequent literals).
        Operand cond = lowerExpression(is.test());

        Variable.Register completionReg = allocRegister();

        // Const-fold dead-branch elimination: if the condition is a constant,
        // only emit the chosen branch. Coerce non-boolean constants
        // (numbers, strings, null, undefined) via toBoolean.
        if (cond instanceof Operand.Constant cc) {
            Object cv = constants.get(cc.index());
            Boolean foldedBool = null;
            if (cv instanceof Boolean b) {
                foldedBool = b;
            } else if (cv == null
                || cv == Undefined.VALUE
                || cv instanceof Integer
                || cv instanceof Long
                || cv instanceof Double
                || cv instanceof String) {
                foldedBool = AbstractOps.toBoolean(cv);
            }
            if (foldedBool != null) {
                boolean b = foldedBool;
                emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));
                Statement chosen = b ? is.consequent() : is.alternate();
                if (chosen != null) {
                    completionRegStack.push(completionReg);
                    try { lowerStatement(chosen); } finally { completionRegStack.pop(); }
                }
                // If the if-statement's CONSEQUENT (regardless of which branch
                // is chosen) is an EmptyStatement, the completion register
                // holds Undefined and will never be referenced afterwards —
                // release it so subsequent statements can reuse the slot.
                // Matches LibJS (file 288: `if (false); ... if (x !== 1)`).
                // When the consequent is non-empty, LibJS keeps the slot
                // alive even if the branch is dropped (file 284:
                // `if (false) x = 1; ...`).
                if (is.consequent() instanceof EmptyStatement) {
                    release(completionReg);
                }
                return completionReg;
            }
        }

        emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));

        // LibJS uses JumpIf (with explicit true+false targets) instead of
        // JumpFalse when deferred branches will be emitted between the if's
        // test and its consequent — those intervening blocks break the
        // natural fall-through that JumpFalse relies on. Otherwise use
        // JumpFalse (consequent immediately follows the test).
        // When inside a deferred flush, the pending entries in
        // deferredLoopBranches are owned by the outer flush iterator and will
        // NOT be drained before this if's consequent (flush is a no-op while
        // re-entered), so they don't actually intervene — use JumpFalse.
        boolean willInterveneDeferred = !inDeferredFlush && !deferredLoopBranches.isEmpty();
        int jumpPc;
        boolean usedJumpIf = false;
        if (willInterveneDeferred) {
            jumpPc = emit(new Op.JumpIf(cond, /* trueTarget */ -1, /* falseTarget */ -1));
            usedJumpIf = true;
        } else {
            jumpPc = emit(new Op.JumpFalse(cond, /* placeholder */ -1));
        }
        // NB: don't release cond here — LibJS keeps it occupied through the
        // consequent/alternate so allocations inside those branches don't
        // collide with the cond register. We release at the after-if join.

        // Flush queued deferred branches from prior for-statements so they
        // land BEFORE this if's consequent — matches LibJS's layout.
        flushDeferredLoopBranches();

        // Then-block.
        startNewBlock();
        int consequentStart = currentPc();
        completionRegStack.push(completionReg);
        try {
            lowerStatement(is.consequent());
        } finally {
            completionRegStack.pop();
        }

        if (is.alternate() != null) {
            // Skip the Jump-over-else when the consequent doesn't fall
            // through (e.g. ends in throw/return) — matches LibJS which
            // omits the unreachable Jump.
            boolean consequentTerminates = lastReachableIsTerminator(is.consequent());
            int jumpOverElsePc = consequentTerminates ? -1 : emit(new Op.Jump(/* placeholder */ -1));
            if (jumpOverElsePc >= 0) peepholeJumpToEndPcs.add(jumpOverElsePc);
            // Else-block.
            startNewBlock();
            int alternateStart = currentPc();
            if (usedJumpIf) {
                // Patch both targets of the JumpIf.
                Op old = ops.get(jumpPc);
                if (!(old instanceof Op.JumpIf oldJif)) {
                    throw new IllegalStateException("expected JumpIf at pc=" + jumpPc);
                }
                ops.set(jumpPc, new Op.JumpIf(oldJif.condition(), consequentStart, alternateStart));
            } else {
                patchJumpTarget(jumpPc, alternateStart);
            }
            // Detect "alternate is a single if-statement" — defer the inner
            // if's consequent/alternate so the outer's after-if block emits
            // BEFORE them. Matches LibJS's layout for nested-if-else (file
            // 299 `S7.9_A5.2_T1.js`).
            DeferredIfInAlternate deferredAlt = consequentTerminates
                ? tryStartIfInAlternate(is.alternate(), completionReg)
                : null;
            if (deferredAlt == null) {
                completionRegStack.push(completionReg);
                try {
                    lowerStatement(is.alternate());
                } finally {
                    completionRegStack.pop();
                }
            }
            // After-if block.
            startNewBlock();
            if (jumpOverElsePc >= 0) patchJumpTarget(jumpOverElsePc, currentPc());
            if (deferredAlt != null) {
                // Queue the deferred branch with the outer-after-if PC so
                // the inner after-if-merge can Jump back here (or inline End).
                deferredLoopBranches.add(new DeferredIfInAlternate(
                    deferredAlt.consequent(),
                    deferredAlt.alternate(),
                    deferredAlt.innerCompletion(),
                    deferredAlt.jumpIfPc(),
                    completionReg,
                    currentPc(),
                    deferredAlt.snapshotNextRegister(),
                    deferredAlt.snapshotFreePool(),
                    deferredAlt.condReg(),
                    deferredAlt.loopStackSnapshot(),
                    deferredAlt.localsSnapshot()));
            }
        } else {
            // After-if block.
            startNewBlock();
            int afterIfStart = currentPc();
            if (usedJumpIf) {
                Op old = ops.get(jumpPc);
                if (!(old instanceof Op.JumpIf oldJif)) {
                    throw new IllegalStateException("expected JumpIf at pc=" + jumpPc);
                }
                ops.set(jumpPc, new Op.JumpIf(oldJif.condition(), consequentStart, afterIfStart));
            } else {
                patchJumpTarget(jumpPc, afterIfStart);
            }
        }
        release(cond);
        // priorScopeCompletionReg cycle releases the previous scope-statement's
        // completion at the end of this scope-statement. At top-level
        // (completionRegStack empty), the grandparent rule in lowerProgram
        // handles this — skip the cycle here to avoid double-release.
        if (!completionRegStack.isEmpty()) {
            setPriorScopeCompletionReg(completionReg);
        }

        // NB: completionReg is intentionally NOT released — see field doc.
        return completionReg;
    }

    /** Convenience adapter that discards the return value (for non-top-level use). */
    private void lowerIf(IfStatement is) {
        lowerIfReturning(is);
    }

    /**
     * If {@code alternate} is a single nested if-statement, lower its test
     * inline (in the alternate block) and emit a {@code JumpIf} with placeholder
     * targets. Returns a {@link DeferredIfInAlternate} describing the deferred
     * consequent/alternate emissions; the caller (outer if-else) will queue
     * it for flush AFTER the outer's after-if block. Otherwise return null
     * so the caller falls back to normal recursive alternate lowering.
     */
    private DeferredIfInAlternate tryStartIfInAlternate(Statement alternate, Variable.Register outerCompletion) {
        Statement effective = alternate;
        if (effective instanceof BlockStatement bs && bs.body().size() == 1) {
            effective = bs.body().get(0);
        }
        if (!(effective instanceof IfStatement is)) return null;
        // Lower the inner test inline (in the alternate's block).
        Operand cond = lowerExpression(is.test());
        Variable.Register innerCompletion = allocRegister();
        emit(new Op.Mov(innerCompletion, constant(Undefined.VALUE)));
        // JumpIf with placeholders for both targets — patched in finish*.
        int jumpIfPc = emit(new Op.JumpIf(cond, /* trueTarget */ -1, /* falseTarget */ -1));
        // Snapshot allocator state — innerCompletion AND cond stay alive.
        int snapshotNext = nextRegister;
        java.util.List<Integer> snapshotPool = new ArrayList<>(freePool);
        java.util.List<LoopContext> loopSnap = new ArrayList<>(loopStack);
        // Pre-register inner consequent/alternate literals.
        preRegisterStatementLiterals(is.consequent());
        if (is.alternate() != null) preRegisterStatementLiterals(is.alternate());
        return new DeferredIfInAlternate(
            is.consequent(), is.alternate(),
            innerCompletion, jumpIfPc,
            outerCompletion,
            /* outerAfterIfPc, set by caller */ -1,
            snapshotNext, snapshotPool,
            cond instanceof Variable.Register cr ? cr : null,
            loopSnap,
            new java.util.HashMap<>(locals));
    }

    /**
     * Emit the deferred inner-if's consequent and after-if-merge, AFTER the
     * outer if-else's after-if block has been emitted. The merge transfers
     * the inner's completion to the outer's, then jumps back to the outer's
     * after-if-PC — or, if that PC's first op is {@code End}, emits End
     * directly (matching LibJS's inlining for script-end-reached cases).
     */
    private void finishDeferredIfInAlternate(DeferredIfInAlternate d) {
        nextRegister = d.snapshotNextRegister();
        freePool.clear();
        java.util.List<Integer> snap = d.snapshotFreePool();
        for (int i = snap.size() - 1; i >= 0; i--) freePool.push(snap.get(i));
        // Restore loopStack snapshot for break/continue.
        java.util.List<LoopContext> savedStack = new ArrayList<>(loopStack);
        loopStack.clear();
        java.util.List<LoopContext> snapStack = d.loopStackSnapshot();
        for (int i = snapStack.size() - 1; i >= 0; i--) {
            loopStack.push(snapStack.get(i));
        }
        // Restore the locals snapshot so identifiers in scope at queue-time
        // (catch params, outer let-decls) are still resolvable now.
        java.util.Map<String, Integer> savedLocals = new java.util.HashMap<>(locals);
        locals.clear();
        locals.putAll(d.localsSnapshot());
        try {
            // Inner consequent block.
            startNewBlock();
            int consequentStart = currentPc();
            completionRegStack.push(d.innerCompletion());
            try {
                lowerStatement(d.consequent());
            } finally {
                completionRegStack.pop();
            }
            // Inner alternate block (if present).
            int alternateStart = -1;
            if (d.alternate() != null) {
                startNewBlock();
                alternateStart = currentPc();
                completionRegStack.push(d.innerCompletion());
                try {
                    lowerStatement(d.alternate());
                } finally {
                    completionRegStack.pop();
                }
            }
            // Inner after-if-merge block: Mov(outerCompletion, innerCompletion);
            // then either Jump(outerAfterIfPc) or, if that PC's first op is
            // End, emit End directly (LibJS inlines for script-end paths).
            startNewBlock();
            int afterIfStart = currentPc();
            emit(new Op.Mov(d.outerCompletion(), d.innerCompletion()));
            int targetPc = d.outerAfterIfPc();
            if (targetPc >= 0 && targetPc < ops.size() && ops.get(targetPc) instanceof Op.End endOp) {
                // Inline the End — matches LibJS's "Mov + End" pattern.
                emit(new Op.End(endOp.value()));
            } else {
                emit(new Op.Jump(targetPc));
            }
            // Patch the JumpIf with both targets.
            Op old = ops.get(d.jumpIfPc());
            if (!(old instanceof Op.JumpIf oldJif)) {
                throw new IllegalStateException("expected JumpIf at pc=" + d.jumpIfPc());
            }
            int falseTarget = d.alternate() != null ? alternateStart : afterIfStart;
            ops.set(d.jumpIfPc(), new Op.JumpIf(oldJif.condition(), consequentStart, falseTarget));
        } finally {
            loopStack.clear();
            for (int i = savedStack.size() - 1; i >= 0; i--) {
                loopStack.push(savedStack.get(i));
            }
            locals.clear();
            locals.putAll(savedLocals);
        }
    }

    /**
     * {@code class Foo [extends Bar] { constructor(...) {...} method(...) {...} }}.
     *
     * <p>Lowered as:
     *   <ol>
     *     <li>compile the constructor body into a JSFunction;</li>
     *     <li>allocate a fresh JSObject as the prototype;</li>
     *     <li>compile each non-constructor method into a JSFunction and install
     *         it on the prototype;</li>
     *     <li>link constructor.prototypeObject = prototype via
     *         {@link Op.SetFunctionPrototype}.</li>
     *   </ol>
     *
     * <p>v1 omissions: extends/super, static members, getters/setters,
     * private fields, decorators.
     */
    /** Reserved local name used to thread the super-class through to constructor bodies. */
    private static final String SUPER_LOCAL_NAME = "__super__";

    /** True if {@code s} is {@code super(...);} as a top-level expression
     *  statement — used by instance-field-injection in derived ctors to
     *  thread {@link Op.CreateArguments}-style "after super()" placement. */
    private static boolean isSuperCallExprStatement(Statement s) {
        return s instanceof ExpressionStatement es
            && es.expression() instanceof CallExpression ce
            && ce.callee() instanceof Super;
    }

    private void installClassMethod(Variable.Register protoReg, MethodDefinition md,
                                    boolean onConstructor, Variable.Register classReg) {
        String name = classMemberKey(md.computed(), md.key());
        JSFunction fn = generateFunction(name, md.value().params(), md.value().body(),
            /* isArrow */ false, md.value().generator(), md.value().async());
        int idx = sharedFunctionData.size();
        sharedFunctionData.add(fn);
        Variable.Register fnReg = allocRegister();
        emit(new Op.NewFunction(fnReg, idx, name, null));
        Variable.Register installTarget = onConstructor ? classReg : protoReg;
        if ("get".equals(md.kind()) || "set".equals(md.kind())) {
            Operand getterOp = "get".equals(md.kind()) ? fnReg : constant(Undefined.VALUE);
            Operand setterOp = "set".equals(md.kind()) ? fnReg : constant(Undefined.VALUE);
            emit(new Op.DefineAccessor(installTarget, name, getterOp, setterOp));
        } else if (onConstructor) {
            emit(new Op.PutById(installTarget, name, fnReg,
                new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
        } else {
            emit(new Op.InitObjectLiteralProperty(installTarget, name, fnReg, 0, 0));
        }
        release(fnReg);
    }

    /** Key extraction for object-literal Property — non-computed, includes # for privates. */
    private String objLiteralKey(Property prop) {
        if (prop.computed()) {
            throw new UnsupportedOperationException("Generator: computed accessor keys in object literals not supported");
        }
        if (prop.key() instanceof Identifier idKey) return idKey.name();
        if (prop.key() instanceof Literal lit) return AbstractOps.toString(literalValue(lit));
        throw new UnsupportedOperationException(
            "Generator: object-literal key type " + prop.key().getClass().getSimpleName() + " not supported");
    }

    /**
     * Resolve a class member key (method name, field name) to its string form.
     * Handles regular Identifier and PrivateIdentifier; the latter is stored
     * with a leading {@code #} so it doesn't collide with public properties.
     */
    private String classMemberKey(boolean computed, Expression key) {
        if (computed) {
            // String-literal computed keys fold to non-computed — LibJS does
            // this since the key is statically resolvable. Keys that look
            // like array indices stay computed.
            if (key instanceof Literal lit && literalValue(lit) instanceof String s
                && !isCanonicalArrayIndex(s)) {
                return s;
            }
            // Truly computed: resolved at runtime; static name is null (the
            // runtime install uses element_keys[i] instead).
            return null;
        }
        if (key instanceof Identifier id) return id.name();
        if (key instanceof PrivateIdentifier pid) return "#" + pid.name();
        if (key instanceof Literal lit) return AbstractOps.toString(literalValue(lit));
        throw new UnsupportedOperationException(
            "Generator: class member key type " + key.getClass().getSimpleName() + " not supported");
    }

    /**
     * The original literal-typed value of a class member key, for the
     * constant pool and {@code element_keys} dump rendering. LibJS preserves
     * the source literal's type (e.g. {@code Int32(16)} for {@code get
     * 0x10()}) rather than stringifying — required for byte-perfect parity.
     * For Identifier and PrivateIdentifier keys, the value is the same
     * String name returned by {@link #classMemberKey}. Returns null for
     * truly-computed keys (their value is computed at runtime — the caller
     * separately lowers the key expression and stores the result register);
     * string-literal computed keys are folded and return their string value.
     */
    private Object classMemberKeyValue(boolean computed, Expression key) {
        if (computed) {
            if (key instanceof Literal lit && literalValue(lit) instanceof String s
                && !isCanonicalArrayIndex(s)) {
                return s;
            }
            return null;
        }
        if (key instanceof Identifier id) return id.name();
        if (key instanceof PrivateIdentifier pid) return "#" + pid.name();
        if (key instanceof Literal lit) return literalValue(lit);
        throw new UnsupportedOperationException(
            "Generator: class member key type " + key.getClass().getSimpleName() + " not supported");
    }

    private void lowerClassDeclaration(ClassDeclaration cd) {
        if (cd.id() == null) {
            throw new UnsupportedOperationException("Generator: anonymous class declaration not supported");
        }
        // ECMA-262 § 15.7 ClassBody is strict-mode code, regardless of
        // whether the surrounding code is strict. Push the flag for the
        // duration of the class body so all member generateFunction calls
        // inherit strict.
        boolean savedStrict = this.strictMode;
        this.strictMode = true;
        try {
            lowerClassDeclarationStrict(cd);
        } finally {
            this.strictMode = savedStrict;
        }
    }

    private void lowerClassDeclarationStrict(ClassDeclaration cd) {
        String className = cd.id().name();
        boolean isTopLevel = atTopLevel;
        // Top-level classes are addressable via GetGlobal (LibJS treats the
        // top-level lex env as a chain ancestor of globalThis); record the
        // name so subsequent references don't ReferenceError in our generator.
        if (isTopLevel) globalNames.add(className);

        // Triage members.
        MethodDefinition ctorMethod = null;
        java.util.List<Node> orderedMembers = new ArrayList<>();
        for (Node n : cd.body().body()) {
            // ECMA-262 § 15.7.1: "constructor" is reserved as the instance
            // constructor only when it's a non-static MethodDefinition. A
            // `static constructor()` is just a static method that happens
            // to be named "constructor" — it lives on the class itself, not
            // as the body of [[Construct]].
            if (n instanceof MethodDefinition md && "constructor".equals(md.kind()) && !md.isStatic()) {
                ctorMethod = md;
                continue;
            }
            orderedMembers.add(n);
        }

        // 1. Build the constructor with instance-field initializers prepended.
        // ECMA-262 § 15.7.10 step 17 — synthesize the default constructor when
        // the class body has no explicit `constructor(...)`. For a derived
        // class (`extends C`) the default is `constructor(...args) { super(...args); }`;
        // for a base class it's `constructor() {}`.
        BlockStatement ctorBody;
        java.util.List<Pattern> ctorParams;
        if (ctorMethod != null) {
            ctorBody = ctorMethod.value().body();
            ctorParams = ctorMethod.value().params();
        } else if (cd.superClass() != null) {
            Identifier argsId = new Identifier(0, 0, 0, 0, 0, 0, "args");
            ctorParams = java.util.List.of(new RestElement(0, 0, 0, 0, 0, 0, argsId));
            CallExpression superCall = new CallExpression(
                new Super(0, 0, 0, 0, 0, 0),
                java.util.List.of(new SpreadElement(0, 0, 0, 0, 0, 0, argsId)));
            ctorBody = new BlockStatement(0, 0, 0, 0, 0, 0,
                java.util.List.<Statement>of(new ExpressionStatement(0, 0, 0, 0, 0, 0, superCall, null)));
        } else {
            ctorBody = new BlockStatement(0, 0, 0, 0, 0, 0, java.util.List.of());
            ctorParams = java.util.List.of();
        }

        java.util.List<PropertyDefinition> instanceFields = new ArrayList<>();
        for (Node n : orderedMembers) {
            if (n instanceof PropertyDefinition pd && !pd.isStatic()) instanceFields.add(pd);
        }
        if (!instanceFields.isEmpty()) {
            // ECMA-262 § 15.7.10 step 31.b: instance fields are initialized
            // immediately after the super() call (for derived classes) or
            // at ctor entry (base classes). For derived ctors we walk the
            // body and inject inits right after the first super() call;
            // for base ctors we prepend.
            java.util.List<Statement> augmentedBody = new ArrayList<>();
            java.util.List<Statement> fieldInits = new ArrayList<>();
            for (PropertyDefinition pd : instanceFields) {
                Expression keyExpr = (Expression) pd.key();
                String keyStr = classMemberKey(pd.computed(), keyExpr);
                Expression target = new MemberExpression(0, 0, 0, 0, 0, 0,
                    new ThisExpression(0, 0, 0, 0, 0, 0),
                    pd.computed() ? keyExpr : new Identifier(0, 0, 0, 0, 0, 0, keyStr),
                    pd.computed(), false);
                Expression value = pd.value() != null
                    ? pd.value()
                    : new Literal(0, 0, 0, 0, 0, 0, null, "undefined");
                Expression initExpr = new AssignmentExpression(0, 0, 0, 0, 0, 0, "=", target, value);
                fieldInits.add(new ExpressionStatement(0, 0, 0, 0, 0, 0, initExpr, null));
            }
            if (cd.superClass() != null) {
                // Derived: inject after super(). v1 only handles the common
                // case where super() is a top-level expression statement in
                // the constructor; otherwise we fall back to prepending
                // (which works in our runtime since `this` is always pre-
                // materialized — a v1 spec deviation).
                boolean injected = false;
                for (Statement s : ctorBody.body()) {
                    augmentedBody.add(s);
                    if (!injected && isSuperCallExprStatement(s)) {
                        augmentedBody.addAll(fieldInits);
                        injected = true;
                    }
                }
                if (!injected) {
                    augmentedBody.clear();
                    augmentedBody.addAll(fieldInits);
                    augmentedBody.addAll(ctorBody.body());
                }
            } else {
                augmentedBody.addAll(fieldInits);
                augmentedBody.addAll(ctorBody.body());
            }
            ctorBody = new BlockStatement(0, 0, 0, 0, 0, 0, augmentedBody);
        }

        // 2. Compile non-constructor members. Member JSFunctions are NOT
        // added to sharedFunctionData yet — that's deferred so any computed-
        // key inner functions (lowered later via element_keys) can claim the
        // earlier SFD slots. LibJS does this: keys come first in the SFD
        // table, then member functions, then ctor.
        java.util.List<Executable.ClassMember> members = new ArrayList<>();
        java.util.List<String> elementKeyNames = new ArrayList<>();
        java.util.List<Object> elementKeyValues = new ArrayList<>();
        java.util.List<Expression> elementKeyExprs = new ArrayList<>();
        // Deferred SFD additions, applied after element_keys lowering.
        // Each entry is the JSFunction to add (in order). For each member
        // that needs an SFD slot, we also remember the corresponding index
        // into `members` so we can backpatch templateIndex after assignment.
        java.util.List<JSFunction> deferredSfd = new ArrayList<>();
        java.util.List<Integer> deferredMemberIdx = new ArrayList<>();   // -1 for placeholder-only entries
        // ECMA-262 § 15.7.10 step 33 — class static blocks are run after
        // all other member definitions, with `this` bound to the class
        // constructor. Collect them here and emit IIFE-style calls below.
        java.util.List<JSFunction> staticBlockFns = new ArrayList<>();
        for (Node n : orderedMembers) {
            if (n instanceof StaticBlock sb) {
                JSFunction fn = generateFunction(null, java.util.List.of(),
                    new BlockStatement(0, 0, 0, 0, 0, 0, sb.body()));
                staticBlockFns.add(fn);
                continue;
            }
            if (n instanceof MethodDefinition md) {
                String name = classMemberKey(md.computed(), md.key());
                Object keyValue = classMemberKeyValue(md.computed(), md.key());
                JSFunction fn = generateFunction(
                    name != null ? name : md.kind(),
                    md.value().params(), md.value().body(),
                    /* isArrow */ false, md.value().generator(), md.value().async());
                // For a `static constructor()` (kind="constructor", isStatic=true)
                // we treat it as a normal static method — the parser tags
                // these as "constructor" but they're not [[Construct]] bodies.
                String kind = "constructor".equals(md.kind()) && md.isStatic()
                    ? "method"
                    : md.kind();   // "method" | "get" | "set"
                int memberIdx = members.size();
                // ElementKeys parallels orderedMembers in source order: each
                // member contributes one entry. So this member's elementKey
                // sits at the current size of elementKeyNames (we add to it
                // immediately below).
                int eki = elementKeyNames.size();
                // Placeholder templateIndex; backpatched after key lowering.
                members.add(new Executable.ClassMember(kind, name, md.isStatic(), -2, null, eki));
                deferredSfd.add(fn);
                deferredMemberIdx.add(memberIdx);
                elementKeyNames.add(name);
                elementKeyValues.add(keyValue);
                elementKeyExprs.add((md.computed() && name == null) ? md.key() : null);
            } else if (n instanceof PropertyDefinition pd && pd.isStatic()) {
                String name = classMemberKey(pd.computed(), (Expression) pd.key());
                Object keyValue = classMemberKeyValue(pd.computed(), (Expression) pd.key());
                if (pd.value() != null && !isLiteralFieldInitializer(pd.value())) {
                    deferredSfd.add(fieldInitializerPlaceholder());
                    deferredMemberIdx.add(-1);   // placeholder-only, no member backpatch
                }
                Object staticInitVal = (pd.value() instanceof Literal sLit && isLiteralFieldInitializer(pd.value()))
                    ? literalValue(sLit) : null;
                int eki = elementKeyNames.size();
                members.add(new Executable.ClassMember("field", name, /* isStatic */ true, -1, staticInitVal, eki));
                elementKeyNames.add(name);
                elementKeyValues.add(keyValue);
                elementKeyExprs.add((pd.computed() && name == null) ? (Expression) pd.key() : null);
            } else if (n instanceof PropertyDefinition pd) {
                String name = classMemberKey(pd.computed(), (Expression) pd.key());
                Object keyValue = classMemberKeyValue(pd.computed(), (Expression) pd.key());
                if (pd.value() != null && !isLiteralFieldInitializer(pd.value())) {
                    deferredSfd.add(fieldInitializerPlaceholder());
                    deferredMemberIdx.add(-1);
                }
                elementKeyNames.add(name);
                elementKeyValues.add(keyValue);
                elementKeyExprs.add((pd.computed() && name == null) ? (Expression) pd.key() : null);
            } else {
                throw new UnsupportedOperationException(
                    "Generator: class body member type " + n.getClass().getSimpleName() + " not supported");
            }
        }

        // Compile the constructor (explicit or auto-generated). Add to SFD
        // last (after member functions and field-init placeholders), since
        // LibJS appends ctor at the end of the class's SFD entries.
        JSFunction ctorFn = generateFunction(className, ctorParams, ctorBody);

        // 3. Pre-register the blueprint. Member templateIndex values are
        // backpatched once element_keys are lowered and member SFD slots
        // are assigned, so the blueprint references final indices.
        int blueprintIdx = classBlueprints.size();
        classBlueprints.add(new Executable.ClassBlueprint(
            className, /* ctorIndex */ -1,
            members.toArray(new Executable.ClassMember[0]),
            cd.superClass() != null));

        // 4. Emit the lex-env setup + NewClass + binding ops.
        ensureLexicalEnvironmentSaved();
        Variable.Register classEnv = allocRegister();
        emit(new Op.CreateLexicalEnvironment(classEnv,
            Variable.Register.SAVED_LEXICAL_ENVIRONMENT, /* capacity */ 0));
        emit(new Op.CreateVariable(className, /* immutable */ true, /* global */ false, /* strict */ false));

        // Collect private member names so we can build the private env. Private
        // members keep their key in the blueprint (with leading `#`) but are
        // EXCLUDED from element_keys (LibJS only lists public members there).
        java.util.List<String> privateNames = new ArrayList<>();
        for (String k : elementKeyNames) {
            if (k != null && k.startsWith("#")) privateNames.add(k);
        }

        // Super class evaluation happens BEFORE the private environment is
        // pushed — `class C extends Base { #p() {} }` evaluates Base in the
        // outer scope, then sets up #p in a fresh private env.
        Variable.Register superReg = null;
        if (cd.superClass() != null) {
            Operand superVal = lowerExpression(cd.superClass());
            superReg = asRegister(superVal);
        }

        if (!privateNames.isEmpty()) {
            emit(new Op.CreatePrivateEnvironment());
            for (String pn : privateNames) emit(new Op.AddPrivateName(pn));
        }

        // Build the public element_keys Operand list. For non-computed keys
        // we use a constant-pool reference; for computed keys we lower the
        // key expression NOW (after super class lowering, before
        // SetLexicalEnvironment) — matches LibJS's emission order.
        java.util.List<Operand> publicElementKeys = new ArrayList<>();
        java.util.List<Operand> computedKeyOps = new ArrayList<>();   // for cleanup after NewClass
        for (int i = 0; i < elementKeyNames.size(); i++) {
            String n = elementKeyNames.get(i);
            if (n != null && n.startsWith("#")) continue;   // skip privates
            if (elementKeyExprs.get(i) != null) {
                Operand keyOp = lowerExpression(elementKeyExprs.get(i));
                publicElementKeys.add(keyOp);
                computedKeyOps.add(keyOp);
            } else {
                publicElementKeys.add(constant(elementKeyValues.get(i)));
            }
        }

        // Now that key expressions have claimed their SFD slots, append the
        // deferred member functions in declaration order, backpatching each
        // member's templateIndex.
        for (int i = 0; i < deferredSfd.size(); i++) {
            int memberIdx = deferredMemberIdx.get(i);
            int sfdIdx = sharedFunctionData.size();
            sharedFunctionData.add(deferredSfd.get(i));
            if (memberIdx >= 0) {
                Executable.ClassMember m = members.get(memberIdx);
                members.set(memberIdx, new Executable.ClassMember(m.kind(), m.key(), m.isStatic(), sfdIdx, m.literalInitValue(), m.elementKeyIndex()));
            }
        }
        // Constructor goes LAST in the SFD table.
        int ctorIndex = sharedFunctionData.size();
        sharedFunctionData.add(ctorFn);
        // Backpatch the blueprint with the final ctor index and member
        // template indices.
        Executable.ClassBlueprint old = classBlueprints.get(blueprintIdx);
        classBlueprints.set(blueprintIdx, new Executable.ClassBlueprint(
            old.name(), ctorIndex,
            members.toArray(new Executable.ClassMember[0]),
            old.hasSuper()));

        emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));

        Variable.Register classReg = allocRegister();
        // A null elementKeys array tells the disassembler to omit the
        // {@code element_keys:[…]} field entirely (matches LibJS for classes
        // with no declared members). Empty array means "all members are
        // private" → render as {@code element_keys:[]}.
        Operand[] elementKeysOp = elementKeyNames.isEmpty()
            ? null
            : publicElementKeys.toArray(new Operand[0]);
        emit(new Op.NewClass(classReg, superReg, classEnv, blueprintIdx,
            elementKeysOp, /* displayName */ null));
        if (superReg != null) release(superReg);
        for (Operand k : computedKeyOps) release(k);
        if (!privateNames.isEmpty()) emit(new Op.LeavePrivateEnvironment());

        // ECMA-262 § 15.7.10 step 35 — initialize the class binding BEFORE
        // running static blocks (step 33's per-element initializers see
        // the class by name).
        emit(new Op.InitializeLexicalBinding(className, classReg, new EnvironmentCoordinate()));

        // Step 33: invoke each static block IIFE with `this` = the class
        // constructor. We materialize one JSFunction per block via
        // sharedFunctionData and emit NewFunction + Call.
        for (JSFunction sbFn : staticBlockFns) {
            int sfdIdx = sharedFunctionData.size();
            sharedFunctionData.add(sbFn);
            Variable.Register fnReg = allocRegister();
            emit(new Op.NewFunction(fnReg, sfdIdx, /* name */ null, /* homeObject */ null));
            Variable.Register resultReg = allocRegister();
            emit(new Op.Call(resultReg, fnReg, classReg, new Operand[0],
                /* expressionString */ null,
                new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
            release(resultReg);
            release(fnReg);
        }
        // Release order matters (LIFO pool): release classEnv first so that
        // subsequent allocations pop classReg's slot first, matching LibJS.
        release(classEnv);
        release(classReg);
    }

    /**
     * Lower a class expression. Same shape as {@link #lowerClassDeclaration}
     * but without the outer-binding `InitializeLexicalBinding` (the caller —
     * usually a var-decl or assignment — handles that). Anonymous class
     * expressions consume the {@link #pendingFunctionName} hint and pass it
     * to {@link Op.NewClass}'s {@code displayName} field for the LibJS
     * {@code (name)} dump annotation.
     */
    private Operand lowerClassExpression(ClassExpression ce) {
        // § 15.7 — class body is strict.
        boolean savedStrict = this.strictMode;
        this.strictMode = true;
        try {
            return lowerClassExpressionStrict(ce);
        } finally {
            this.strictMode = savedStrict;
        }
    }

    private Operand lowerClassExpressionStrict(ClassExpression ce) {
        String ownName = ce.id() != null ? ce.id().name() : null;
        String inferredName = null;
        if (ownName == null && pendingFunctionName != null) {
            inferredName = pendingFunctionName;
            pendingFunctionName = null;
        }

        // Triage members (same as ClassDeclaration).
        MethodDefinition ctorMethod = null;
        java.util.List<Node> orderedMembers = new ArrayList<>();
        for (Node n : ce.body().body()) {
            // ECMA-262 § 15.7.1: "constructor" is reserved as the instance
            // constructor only when it's a non-static MethodDefinition. A
            // `static constructor()` is just a static method that happens
            // to be named "constructor" — it lives on the class itself, not
            // as the body of [[Construct]].
            if (n instanceof MethodDefinition md && "constructor".equals(md.kind()) && !md.isStatic()) {
                ctorMethod = md;
                continue;
            }
            orderedMembers.add(n);
        }

        // ECMA-262 § 15.7.10 step 17 default-constructor synthesis (see
        // {@link #lowerClassDeclarationStrict} for the matching block).
        BlockStatement ctorBody;
        java.util.List<Pattern> ctorParams;
        if (ctorMethod != null) {
            ctorBody = ctorMethod.value().body();
            ctorParams = ctorMethod.value().params();
        } else if (ce.superClass() != null) {
            Identifier argsId = new Identifier(0, 0, 0, 0, 0, 0, "args");
            ctorParams = java.util.List.of(new RestElement(0, 0, 0, 0, 0, 0, argsId));
            CallExpression superCall = new CallExpression(
                new Super(0, 0, 0, 0, 0, 0),
                java.util.List.of(new SpreadElement(0, 0, 0, 0, 0, 0, argsId)));
            ctorBody = new BlockStatement(0, 0, 0, 0, 0, 0,
                java.util.List.<Statement>of(new ExpressionStatement(0, 0, 0, 0, 0, 0, superCall, null)));
        } else {
            ctorBody = new BlockStatement(0, 0, 0, 0, 0, 0, java.util.List.of());
            ctorParams = java.util.List.of();
        }

        java.util.List<PropertyDefinition> instanceFields = new ArrayList<>();
        for (Node n : orderedMembers) {
            if (n instanceof PropertyDefinition pd && !pd.isStatic()) instanceFields.add(pd);
        }
        if (!instanceFields.isEmpty()) {
            // Same logic as lowerClassDeclaration — fields go after super()
            // for derived ctors, at body start otherwise (see § 15.7.10
            // step 31.b).
            java.util.List<Statement> augmentedBody = new ArrayList<>();
            java.util.List<Statement> fieldInits = new ArrayList<>();
            for (PropertyDefinition pd : instanceFields) {
                Expression keyExpr = (Expression) pd.key();
                String keyStr = classMemberKey(pd.computed(), keyExpr);
                Expression target = new MemberExpression(0, 0, 0, 0, 0, 0,
                    new ThisExpression(0, 0, 0, 0, 0, 0),
                    pd.computed() ? keyExpr : new Identifier(0, 0, 0, 0, 0, 0, keyStr),
                    pd.computed(), false);
                Expression value = pd.value() != null
                    ? pd.value()
                    : new Literal(0, 0, 0, 0, 0, 0, null, "undefined");
                Expression initExpr = new AssignmentExpression(0, 0, 0, 0, 0, 0, "=", target, value);
                fieldInits.add(new ExpressionStatement(0, 0, 0, 0, 0, 0, initExpr, null));
            }
            if (ce.superClass() != null) {
                boolean injected = false;
                for (Statement s : ctorBody.body()) {
                    augmentedBody.add(s);
                    if (!injected && isSuperCallExprStatement(s)) {
                        augmentedBody.addAll(fieldInits);
                        injected = true;
                    }
                }
                if (!injected) {
                    augmentedBody.clear();
                    augmentedBody.addAll(fieldInits);
                    augmentedBody.addAll(ctorBody.body());
                }
            } else {
                augmentedBody.addAll(fieldInits);
                augmentedBody.addAll(ctorBody.body());
            }
            ctorBody = new BlockStatement(0, 0, 0, 0, 0, 0, augmentedBody);
        }

        // Constructor template uses the inner ownName (if present) for `name`,
        // otherwise the inferredName, otherwise null.
        String fnName = ownName != null ? ownName : inferredName;

        // Same deferred-SFD pattern as lowerClassDeclaration: hold member
        // functions back so any computed-key inner functions claim earlier
        // SFD slots.
        java.util.List<Executable.ClassMember> members = new ArrayList<>();
        java.util.List<String> elementKeyNames = new ArrayList<>();
        java.util.List<Object> elementKeyValues = new ArrayList<>();
        java.util.List<Expression> elementKeyExprs = new ArrayList<>();
        java.util.List<JSFunction> deferredSfd = new ArrayList<>();
        java.util.List<Integer> deferredMemberIdx = new ArrayList<>();
        java.util.List<JSFunction> staticBlockFns = new ArrayList<>();
        for (Node n : orderedMembers) {
            if (n instanceof StaticBlock sb) {
                JSFunction fn = generateFunction(null, java.util.List.of(),
                    new BlockStatement(0, 0, 0, 0, 0, 0, sb.body()));
                staticBlockFns.add(fn);
                continue;
            }
            if (n instanceof MethodDefinition md) {
                String name = classMemberKey(md.computed(), md.key());
                Object keyValue = classMemberKeyValue(md.computed(), md.key());
                JSFunction fn = generateFunction(
                    name != null ? name : md.kind(),
                    md.value().params(), md.value().body(),
                    /* isArrow */ false, md.value().generator(), md.value().async());
                int memberIdx = members.size();
                int eki = elementKeyNames.size();
                members.add(new Executable.ClassMember(md.kind(), name, md.isStatic(), -2, null, eki));
                deferredSfd.add(fn);
                deferredMemberIdx.add(memberIdx);
                elementKeyNames.add(name);
                elementKeyValues.add(keyValue);
                elementKeyExprs.add((md.computed() && name == null) ? md.key() : null);
            } else if (n instanceof PropertyDefinition pd && pd.isStatic()) {
                String name = classMemberKey(pd.computed(), (Expression) pd.key());
                Object keyValue = classMemberKeyValue(pd.computed(), (Expression) pd.key());
                if (pd.value() != null && !isLiteralFieldInitializer(pd.value())) {
                    deferredSfd.add(fieldInitializerPlaceholder());
                    deferredMemberIdx.add(-1);
                }
                Object staticInitVal = (pd.value() instanceof Literal sLit && isLiteralFieldInitializer(pd.value()))
                    ? literalValue(sLit) : null;
                int eki = elementKeyNames.size();
                members.add(new Executable.ClassMember("field", name, true, -1, staticInitVal, eki));
                elementKeyNames.add(name);
                elementKeyValues.add(keyValue);
                elementKeyExprs.add((pd.computed() && name == null) ? (Expression) pd.key() : null);
            } else if (n instanceof PropertyDefinition pd) {
                String name = classMemberKey(pd.computed(), (Expression) pd.key());
                Object keyValue = classMemberKeyValue(pd.computed(), (Expression) pd.key());
                if (pd.value() != null && !isLiteralFieldInitializer(pd.value())) {
                    deferredSfd.add(fieldInitializerPlaceholder());
                    deferredMemberIdx.add(-1);
                }
                elementKeyNames.add(name);
                elementKeyValues.add(keyValue);
                elementKeyExprs.add((pd.computed() && name == null) ? (Expression) pd.key() : null);
            } else {
                throw new UnsupportedOperationException(
                    "Generator: class body member type " + n.getClass().getSimpleName() + " not supported");
            }
        }

        // Compile constructor (deferred until after key lowering).
        JSFunction ctorFn = generateFunction(fnName, ctorParams, ctorBody);

        int blueprintIdx = classBlueprints.size();
        classBlueprints.add(new Executable.ClassBlueprint(
            fnName != null ? fnName : "",
            /* ctorIndex backpatched */ -1,
            members.toArray(new Executable.ClassMember[0]),
            ce.superClass() != null));
        // No pre-pass pool registration: pool entries for member names are
        // registered later in declaration order, interleaved with computed
        // key lowering, to match LibJS.

        // Emit prologue + NewClass.
        ensureLexicalEnvironmentSaved();
        Variable.Register classEnv = allocRegister();
        emit(new Op.CreateLexicalEnvironment(classEnv,
            Variable.Register.SAVED_LEXICAL_ENVIRONMENT, /* capacity */ 0));
        if (ownName != null) {
            emit(new Op.CreateVariable(ownName, true, false, false));
        }

        java.util.List<String> privateNames = new ArrayList<>();
        for (String k : elementKeyNames) {
            if (k != null && k.startsWith("#")) privateNames.add(k);
        }

        // Super class evaluation happens BEFORE the private environment is
        // pushed (matches LibJS).
        Variable.Register superReg = null;
        if (ce.superClass() != null) {
            Operand superVal = lowerExpression(ce.superClass());
            superReg = asRegister(superVal);
        }

        if (!privateNames.isEmpty()) {
            emit(new Op.CreatePrivateEnvironment());
            for (String pn : privateNames) emit(new Op.AddPrivateName(pn));
        }

        // Build public element_keys list — non-computed keys are constant-pool
        // operands, computed keys are lowered now into fresh registers.
        java.util.List<Operand> publicElementKeys = new ArrayList<>();
        java.util.List<Operand> computedKeyOps = new ArrayList<>();
        for (int i = 0; i < elementKeyNames.size(); i++) {
            String n = elementKeyNames.get(i);
            if (n != null && n.startsWith("#")) continue;
            if (elementKeyExprs.get(i) != null) {
                Operand keyOp = lowerExpression(elementKeyExprs.get(i));
                publicElementKeys.add(keyOp);
                computedKeyOps.add(keyOp);
            } else {
                publicElementKeys.add(constant(elementKeyValues.get(i)));
            }
        }

        // Backpatch deferred member SFD slots after key lowering.
        for (int i = 0; i < deferredSfd.size(); i++) {
            int memberIdx = deferredMemberIdx.get(i);
            int sfdIdx = sharedFunctionData.size();
            sharedFunctionData.add(deferredSfd.get(i));
            if (memberIdx >= 0) {
                Executable.ClassMember m = members.get(memberIdx);
                members.set(memberIdx, new Executable.ClassMember(m.kind(), m.key(), m.isStatic(), sfdIdx, m.literalInitValue(), m.elementKeyIndex()));
            }
        }
        int ctorIndex = sharedFunctionData.size();
        sharedFunctionData.add(ctorFn);
        Executable.ClassBlueprint old = classBlueprints.get(blueprintIdx);
        classBlueprints.set(blueprintIdx, new Executable.ClassBlueprint(
            old.name(), ctorIndex,
            members.toArray(new Executable.ClassMember[0]),
            old.hasSuper()));

        emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));

        Variable.Register classReg = allocRegister();
        // displayName annotation only for anonymous class expressions whose
        // name came from a NamedEvaluation hint — LibJS dumps it as `(name)`.
        String displayName = ownName == null ? inferredName : null;
        Operand[] elementKeysOp = elementKeyNames.isEmpty()
            ? null
            : publicElementKeys.toArray(new Operand[0]);
        emit(new Op.NewClass(classReg, superReg, classEnv, blueprintIdx,
            elementKeysOp, displayName));
        for (Operand k : computedKeyOps) release(k);
        if (superReg != null) release(superReg);
        if (!privateNames.isEmpty()) emit(new Op.LeavePrivateEnvironment());

        // Static blocks run with `this` = the class (§ 15.7.10 step 33).
        for (JSFunction sbFn : staticBlockFns) {
            int sfdIdx = sharedFunctionData.size();
            sharedFunctionData.add(sbFn);
            Variable.Register fnReg = allocRegister();
            emit(new Op.NewFunction(fnReg, sfdIdx, /* name */ null, /* homeObject */ null));
            Variable.Register resultReg = allocRegister();
            emit(new Op.Call(resultReg, fnReg, classReg, new Operand[0],
                /* expressionString */ null,
                new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
            release(resultReg);
            release(fnReg);
        }

        // Caller binds the class to its target via the surrounding assignment
        // / var-decl. We don't emit InitializeLexicalBinding for the inner
        // ownName here (LibJS doesn't either — see clsnamed.js: CreateVariable
        // Y but no InitializeLexicalBinding).
        release(classEnv);
        return classReg;
    }

    /**
     * {@code for (init; test; update) body} desugars to:
     *   <pre>{@code
     *   init;
     *   while (test) {
     *     body;
     *     update;
     *   }
     *   }</pre>
     * with the slight difference that an absent {@code test} is treated as
     * always-true.
     */
    /**
     * True if {@code e} is a JS literal whose ToBoolean conversion is always
     * {@code false}: {@code false}, {@code 0}, {@code NaN}, {@code null},
     * {@code ""}. LibJS uses this to elide loop bodies / cond jumps that can
     * never iterate.
     */
    private boolean isFalsyLiteral(Expression e) {
        if (e instanceof Literal lit) {
            Object v = literalValue(lit);
            if (v == null) return true;                 // null literal
            if (v instanceof Boolean b) return !b;
            if (v instanceof Double d) return d == 0.0 || d.isNaN();
            if (v instanceof String s) return s.isEmpty();
            return false;
        }
        // The bare `undefined` identifier resolves to the undefined value
        // (provided no shadowing binding exists). LibJS treats it as a falsy
        // constant for for-loop test folding.
        if (e instanceof Identifier id && "undefined".equals(id.name())
            && !locals.containsKey("undefined")) {
            return true;
        }
        return false;
    }

    /**
     * Lower a for-loop whose test is the literal {@code false}. The loop body
     * is unreachable, so LibJS emits a placeholder shape: completion init plus
     * a Jump straight to the after-loop block, with dead body and (if present)
     * dead update blocks holding a single {@code End Undefined} op each.
     * Constants for the body are NOT pre-registered (LibJS drops them along
     * with the body). The after-loop block is left empty for the caller to
     * emit the script's End into.
     */
    private Variable.Register lowerForConstantFalseTest(ForStatement fs) {
        // 1. Init.
        if (fs.init() != null) {
            if (fs.init() instanceof VariableDeclaration vd) {
                lowerVarDecl(vd);
            } else if (fs.init() instanceof Expression initExpr) {
                Operand v = lowerExpression(initExpr);
                release(v);
            } else {
                throw new UnsupportedOperationException(
                    "Generator: for-init form " + fs.init().getClass().getSimpleName() + " not supported");
            }
        }

        // 2. Completion register.
        Variable.Register completionReg = allocRegister();
        Operand.Constant undefConst = constant(Undefined.VALUE);
        emit(new Op.Mov(completionReg, undefConst));

        // 3. Pre-register the test's literal (so Bool(false) lands in the pool
        // at the source-order position even though no test op is emitted).
        preRegisterLiterals(fs.test());

        // 4. Jump → after-loop (placeholder; patched once after's PC is known).
        int jumpToAfterPc = emit(new Op.Jump(/* placeholder */ -1));

        // 5. Dead body block (single End Undefined placeholder).
        startNewBlock();
        emit(new Op.End(undefConst));

        // 6. Dead update block, if update was present.
        if (fs.update() != null) {
            startNewBlock();
            preRegisterLiterals(fs.update());
            emit(new Op.End(undefConst));
        }

        // 7. After-loop block — caller's next statement / End lands here.
        startNewBlock();
        patchJumpTarget(jumpToAfterPc, currentPc());

        setPriorScopeCompletionReg(completionReg);
        return completionReg;
    }

    /**
     * Flattened for-loop emission for the {@code for(;;) {body-always-exits}}
     * pattern. Empirically derived from LibJS dumps (see {@code probes} in
     * memory): when the test and update are absent and the body unconditionally
     * leaves the loop on its first execution, LibJS emits no cond block, no
     * update block, and no back-edge — the loop reduces to:
     * <pre>
     *   block0: ...init...
     *           Mov(completion, Undefined)        // falls through
     *   block1: ...body... (with trailing `break` to this loop suppressed)
     *   block2: (after-loop — caller continues here)
     * </pre>
     * The body's terminator is what transitions block1 → block2: an unlabeled
     * break is simply omitted (fall-through), while throw/return emit their
     * op and the basic block boundary closes the block naturally.
     */
    private Variable.Register lowerForFlattened(ForStatement fs) {
        // 1. Init — same lowering as the regular path.
        if (fs.init() != null) {
            if (fs.init() instanceof VariableDeclaration vd) {
                lowerVarDecl(vd);
            } else if (fs.init() instanceof Expression initExpr) {
                Operand v = lowerExpression(initExpr);
                release(v);
            } else {
                throw new UnsupportedOperationException(
                    "Generator: for-init form " + fs.init().getClass().getSimpleName() + " not supported");
            }
        }

        // 2. Completion register.
        Variable.Register completionReg = allocRegister();
        emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));

        // 3. Body block. NO Jump-to-cond — block0 falls through to block1
        // because there's no back-edge in this flattened layout.
        startNewBlock();
        int bodyStart = currentPc();
        LoopContext loop = new LoopContext();
        loop.completionRegister = completionReg;
        loop.breakIsFallThrough = true;
        attachPendingLabel(loop);
        loopStack.push(loop);

        completionRegStack.push(completionReg);
        try {
            lowerStatement(fs.body());
        } finally {
            completionRegStack.pop();
        }
        loopStack.pop();

        // 4. After block — caller's next statement (or End) lands here.
        startNewBlock();

        // 5. If update was present, defer it: LibJS emits a dead-code update
        // block + a duplicate End AFTER the script's End. The Jump in the
        // update block targets the body block's start (which fuses with the
        // after-loop block when the body has no ops).
        if (fs.update() != null) {
            deferredLoopBranches.add(new DeferredFlattenedForUpdate(fs.update(), bodyStart));
        }

        // priorScopeCompletionReg cycle — same as the regular path so a
        // subsequent scope-producing statement can reuse this slot.
        setPriorScopeCompletionReg(completionReg);
        return completionReg;
    }

    /**
     * Emit the deferred update + trailing End for a flattened for-loop. Called
     * by {@link #flushDeferredLoopBranches} after the script's natural End has
     * been emitted. The update block lowers the update expression (purely for
     * side-effect / constants-pool ordering — control never reaches it),
     * then jumps back to the body block's start. Trailing End duplicates the
     * value of the script's End op (looked up by walking back through the ops
     * list — at flush time, the most recent End is the script's).
     */
    private void finishDeferredFlattenedForUpdate(DeferredFlattenedForUpdate d) {
        startNewBlock();
        Operand u = lowerExpression(d.updateExpr());
        release(u);
        emit(new Op.Jump(d.bodyStartPc()));

        startNewBlock();
        Operand endValue = null;
        for (int i = ops.size() - 1; i >= 0; i--) {
            if (ops.get(i) instanceof Op.End e) {
                endValue = e.value();
                break;
            }
        }
        if (endValue == null) endValue = constant(Undefined.VALUE);
        emit(new Op.End(endValue));
    }

    private Variable.Register lowerForReturning(ForStatement fs) {
        // LibJS layout (rotated): init → completion-init → Jump(cond) → body
        //   → update → Jump(cond) → cond → JumpFused(body, after) → after.
        // This places the cond test at the END of the loop's bytecode region
        // so the only way into the body is via the cond block (matching LibJS).

        // Fast path: for(;<empty-test>;<maybe-update>) where the body
        // unconditionally exits via break/return/throw. LibJS collapses such
        // loops into init + body + after with no cond block and no back-edge.
        // The update (if present) emits as a dead-code block after the script's
        // End. See lowerForFlattened.
        if (fs.test() == null && bodyAlwaysExitsLoop(fs.body())) {
            return lowerForFlattened(fs);
        }

        // Fast path: for(<init>; false; <maybe-update>) where the test is the
        // literal `false`. LibJS detects the never-iterates pattern and emits
        // a degenerate shape: completion + Jump-to-after, plus dead-body and
        // dead-update placeholder blocks (each with `End Undefined`), and a
        // final after block where the script's End lands. Body content is
        // dropped entirely — its literals are NOT registered in the pool.
        if (isFalsyLiteral(fs.test())) {
            return lowerForConstantFalseTest(fs);
        }

        // 1. Init.
        if (fs.init() != null) {
            if (fs.init() instanceof VariableDeclaration vd) {
                lowerVarDecl(vd);
            } else if (fs.init() instanceof Expression initExpr) {
                Operand v = lowerExpression(initExpr);
                release(v);
            } else {
                throw new UnsupportedOperationException(
                    "Generator: for-init form " + fs.init().getClass().getSimpleName() + " not supported");
            }
        }

        // 2. Completion register.
        Variable.Register completionReg = allocRegister();
        emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));

        // 2b. Pre-register the test expression's literals so they land in the
        // constant pool BEFORE the body's literals — matches LibJS, which
        // visits the test expression before lowering the body even though the
        // test's bytecode is emitted later (rotated layout).
        if (fs.test() != null) preRegisterLiterals(fs.test());

        // 3. Jump to cond block (placeholder, patched once we know its PC).
        int initToCondPc = emit(new Op.Jump(/* placeholder */ -1));

        // 3b. SAVE-AND-REPLAY for the test expression: LibJS generates test
        // and update BEFORE body in codegen time (codegen.rs:2392-2444), so
        // body sees post-update pool state. Mirror by dry-running the test
        // (and possibly update) into a side buffer, capturing emitted ops,
        // restoring ops/blockStartPcs while KEEPING post-dry-run allocator
        // state. Body lowers with the right pool. At the cond block (later
        // in PC) we re-emit the captured test ops.
        //
        // Restricted to fusable comparison tests — non-fusable tests can call
        // startNewBlock internally (LogicalExpression / ConditionalExpression),
        // which would invalidate captured PCs. For those, fall back to the
        // current order (which is suboptimal but doesn't regress).
        java.util.List<Op> capturedTestOps = null;
        // Saved post-test allocator state — restored before update emits later
        // so update sees the SAME pool LibJS does (post-test, pre-body-and-update).
        int postTestNextRegister = -1;
        java.util.Deque<Integer> postTestFreePool = null;
        boolean testIsFusableComparison = fs.test() instanceof BinaryExpression bin
            && isFusableComparison(bin.operator());
        if (testIsFusableComparison) {
            int preOpsLen = ops.size();
            java.util.List<Integer> preBlockStarts = new ArrayList<>(blockStartPcs);
            // Dry-run lower the test; bodyStart placeholder = -1.
            emitForLoopCondAtTail(fs.test(), /* bodyStart placeholder */ -1);
            // Capture the emitted test ops.
            capturedTestOps = new ArrayList<>(ops.subList(preOpsLen, ops.size()));
            // Save POST-TEST state — used to restore allocator before update
            // emits later in the spine.
            postTestNextRegister = nextRegister;
            postTestFreePool = new ArrayDeque<>(freePool);
            // Dry-run lower the update too — its purpose is to advance the
            // allocator to post-test+post-update state so body sees that
            // (matches LibJS where body codegen happens AFTER update codegen).
            if (fs.update() != null) {
                Operand u = lowerExpression(fs.update());
                release(u);
            }
            // Truncate everything emitted (test + update).
            while (ops.size() > preOpsLen) ops.remove(ops.size() - 1);
            blockStartPcs.clear();
            blockStartPcs.addAll(preBlockStarts);
            // KEEP nextRegister, freePool at their post-test+update state for
            // the body lowering that follows.
            sealLastFusion = true;
        }

        // Flush queued deferred branches from PRIOR for-statements so they
        // land BEFORE this for's body block — matches LibJS's layout where
        // pending deferred-for/deferred-if items emit between the prologue
        // (Jump-to-cond placeholder) and the body block.
        flushDeferredLoopBranches();

        // 4. Body block.
        startNewBlock();
        int bodyStart = currentPc();
        LoopContext loop = new LoopContext();
        loop.completionRegister = completionReg;
        attachPendingLabel(loop);
        loopStack.push(loop);

        // Detect "body is a single if-statement" pattern. LibJS emits the if's
        // consequent and after-if-merge OUT OF LINE (after the loop spine),
        // with the body block ending in a fused JumpIf. We defer the if's
        // branches so they can be emitted at the very end.
        DeferredIfInLoopBody deferredIf = tryStartIfOnlyBody(fs.body(), completionReg, loop);
        // Detect "body is a single for-statement" pattern. LibJS emits the
        // inner for-loop's body/update/cond/after-merge OUT OF LINE (after
        // the outer's spine), with the outer body block ending in the
        // inner's prologue (init + Mov(innerCompletion, Undefined) + Jump).
        DeferredForInLoopBody deferredFor = null;
        if (deferredIf == null) {
            deferredFor = tryStartForOnlyBody(fs.body(), completionReg);
        }

        if (deferredIf == null && deferredFor == null) {
            completionRegStack.push(completionReg);
            try {
                lowerStatement(fs.body());
            } finally {
                completionRegStack.pop();
            }
        }

        // 5. Update block.
        startNewBlock();
        int updateStart = currentPc();
        loop.continueTargetPc = updateStart;
        // Release the deferred-if's cond and ifCompletion slots between body
        // and update. We use addLast (FIFO push) for these so they sit at the
        // BOTTOM of the LIFO stack — the existing top (e.g. an early
        // body-released temp) gets allocated first by the update's GetGlobal,
        // and only after that does the cond/ifCompletion slot get reused.
        // Mirrors LibJS's allocator order on for-loops with deferred ifs.
        if (deferredIf != null) {
            // addLast (FIFO push) preserves the body's pre-existing pool top.
            // We add the deferred slots in a context-dependent order: when
            // body's pool already has entries (a complex test that released
            // intermediates), use ASCENDING register-index order so the lower
            // index lands closer to the head; when body's pool is empty (a
            // simple test like a bare identifier), use DESCENDING order
            // (ifCompletion-first) so the higher index reaches the head
            // first. Mirrors LibJS's empirical allocator behavior — see
            // forifSmall.js / forif43.js / file 43 reproducers for derivation.
            int ifIdx = deferredIf.ifCompletion().index();
            int condIdx = deferredIf.condReg() != null ? deferredIf.condReg().index() : -1;
            boolean bodyPoolEmpty = freePool.isEmpty();
            int first, second;
            if (bodyPoolEmpty) {
                // Descending: higher first.
                first = condIdx >= 0 ? Math.max(ifIdx, condIdx) : ifIdx;
                second = condIdx >= 0 ? Math.min(ifIdx, condIdx) : -1;
            } else {
                // Ascending: lower first.
                first = condIdx >= 0 ? Math.min(ifIdx, condIdx) : ifIdx;
                second = condIdx >= 0 ? Math.max(ifIdx, condIdx) : -1;
            }
            if (first >= Variable.Register.FIRST_USER_INDEX) freePool.addLast(first);
            if (second >= Variable.Register.FIRST_USER_INDEX) freePool.addLast(second);
        }
        if (deferredFor != null) {
            // LibJS releases the inner completion register AFTER the first
            // allocation in the update block (so the first allocation gets a
            // fresh nextRegister, then the inner-completion slot lands in the
            // pool in time for the second allocation). Schedule the release
            // to drain after the next emit.
            scheduleReleaseAfterNextEmit(deferredFor.innerCompletion());
        }
        // Restore post-test allocator state for update emission — LibJS
        // generates update with post-test pool (since body codegen happens
        // AFTER update codegen). Save body's allocator state first so we can
        // also use its high-water mark.
        int bodyEndNextReg = nextRegister;
        java.util.Deque<Integer> bodyEndFreePool = new ArrayDeque<>(freePool);
        if (postTestFreePool != null) {
            nextRegister = postTestNextRegister;
            freePool.clear();
            freePool.addAll(postTestFreePool);
        }
        if (fs.update() != null) {
            Operand u = lowerExpression(fs.update());
            release(u);
        }
        // After update, restore body's post-state pool so the cond block sees
        // a sensible state. The captured test ops will be replayed verbatim
        // anyway; what matters is downstream allocations after the loop.
        if (postTestFreePool != null) {
            // Take the higher of the two nextRegister values to preserve
            // maxRegister (high-water mark).
            int updateEndNextReg = nextRegister;
            nextRegister = Math.max(bodyEndNextReg, updateEndNextReg);
            // Body's freePool is what subsequent code expects.
            freePool.clear();
            freePool.addAll(bodyEndFreePool);
        }
        // No explicit Jump here — the cond block immediately follows update in
        // PC order, so fall-through reaches it naturally. Matches LibJS.

        // 6. Cond block.
        startNewBlock();
        int condStart = currentPc();
        patchJumpTarget(initToCondPc, condStart);
        int condFalsePc = -1;
        if (capturedTestOps != null) {
            // Replay captured test ops from the dry-run, patching the JumpFused
            // (last op) to use the correct bodyStart. The intermediate ops have
            // already-resolved register operands; their indices are stable.
            int n = capturedTestOps.size();
            for (int i = 0; i < n - 1; i++) {
                ops.add(capturedTestOps.get(i));
            }
            // The last captured op is the fused jump with bodyStart=-1.
            // Patch it with the actual bodyStart.
            Op last = capturedTestOps.get(n - 1);
            Op patched = patchFusedJumpTrueTarget(last, bodyStart);
            condFalsePc = ops.size();
            ops.add(patched);
            // Add bodyStart to blockStartPcs (skipped during dry-run).
            if (!blockStartPcs.contains(bodyStart)) {
                int insertAt = 0;
                while (insertAt < blockStartPcs.size() && blockStartPcs.get(insertAt) < bodyStart) insertAt++;
                blockStartPcs.add(insertAt, bodyStart);
            }
            // Seal fusion across the replay boundary.
            sealLastFusion = true;
        } else if (fs.test() != null) {
            condFalsePc = emitForLoopCondAtTail(fs.test(), bodyStart);
        } else {
            // No test: unconditional jump back to body.
            emit(new Op.Jump(bodyStart));
        }

        // 7. After-loop block.
        startNewBlock();
        if (condFalsePc >= 0) patchJumpTarget(condFalsePc, currentPc());

        loopStack.pop();
        loop.breakTargetPc = currentPc();
        for (int p : loop.pendingBreakPcs) patchJumpTarget(p, loop.breakTargetPc);
        for (int p : loop.pendingContinuePcs) patchJumpTarget(p, loop.continueTargetPc);

        // 8. Defer the if-body branches for emission after the script/function
        // body's End — matches LibJS's out-of-line layout.
        if (deferredIf != null) {
            deferredLoopBranches.add(new DeferredIfInLoopBody(
                deferredIf.consequent(),
                deferredIf.alternate(),
                deferredIf.ifCompletion(),
                deferredIf.jumpIfPc(),
                completionReg,
                updateStart,
                deferredIf.snapshotNextRegister(),
                deferredIf.snapshotFreePool(),
                deferredIf.condReg(),
                deferredIf.loopStackSnapshot()));
        }
        if (deferredFor != null) {
            deferredLoopBranches.add(new DeferredForInLoopBody(
                deferredFor.innerFor(),
                deferredFor.innerCompletion(),
                deferredFor.jumpInitToCondPc(),
                completionReg,
                updateStart,
                deferredFor.snapshotNextRegister(),
                deferredFor.snapshotFreePool(),
                deferredFor.loopStackSnapshot(),
                deferredFor.trailingOuterBodyStmts()));
        }
        // Participate in the priorScopeCompletionReg cycle so a subsequent
        // scope-producing statement (if/for/while/try) can reuse this for's
        // completion register slot — matches LibJS, which reuses for-loop
        // completion registers across sibling top-level statements.
        setPriorScopeCompletionReg(completionReg);
        return completionReg;
    }

    /**
     * Container for a for-loop body that's a single IfStatement, lowered in
     * "deferred-branches" mode: the test has already been emitted in the body
     * block and ended with a {@code JumpIf} placeholder; the consequent (and
     * an after-if-merge that transfers the if's completion to the loop's and
     * jumps back to the update target) will be emitted later, after the
     * loop's main spine, by {@link #finishDeferredIfInLoopBody}.
     */
    private record DeferredIfInLoopBody(
        Statement consequent,
        Statement alternate,
        Variable.Register ifCompletion,
        int jumpIfPc,
        Variable.Register loopCompletion,
        int updateStart,
        int snapshotNextRegister,
        java.util.List<Integer> snapshotFreePool,
        Variable.Register condReg,
        // Snapshot of the loopStack (head-to-tail) when this if-body was
        // deferred — restored during deferred lowering so continue/break
        // statements (including labeled forms targeting outer loops) can
        // find their target.
        java.util.List<LoopContext> loopStackSnapshot
    ) implements DeferredLoopBranch {}

    /**
     * If {@code body} is a single if-statement (with no alternate), lower its
     * test inline and emit a {@code JumpIf} with placeholder targets, returning
     * a {@link DeferredIfInLoopBody} so the for-loop can emit the branches
     * out-of-line. Otherwise return {@code null} so the caller falls back to
     * normal body lowering.
     */
    private DeferredIfInLoopBody tryStartIfOnlyBody(Statement body, Variable.Register loopCompletion, LoopContext loop) {
        Statement effective = body;
        if (effective instanceof BlockStatement bs && bs.body().size() == 1) {
            effective = bs.body().get(0);
        }
        if (!(effective instanceof IfStatement is)) return null;
        // Both no-else and if-else patterns are now handled by the deferred
        // branches mechanism. The alternate (if any) is laid out after the
        // consequent, and the after-if-merge (Mov + Jump to update) follows.
        // Lower the test inline. The if-completion register is allocated
        // BEFORE Mov(ifCompletion, Undefined) — see lowerIfReturning for the
        // rationale (matches LibJS's "Undefined-then-cond" constant ordering).
        constant(Undefined.VALUE);
        Operand cond = lowerExpression(is.test());
        Variable.Register ifCompletion = allocRegister();
        emit(new Op.Mov(ifCompletion, constant(Undefined.VALUE)));
        // JumpIf with placeholders for both targets — patched in finish*.
        int jumpIfPc = emit(new Op.JumpIf(cond, /* trueTarget */ -1, /* falseTarget */ -1));
        // Snapshot the allocator state BEFORE releasing cond/ifCompletion —
        // the deferred branches see this snapshot, treating both registers as
        // still occupied so their fresh allocations skip past them (matching
        // LibJS's higher register numbering for the deferred consequent).
        int snapshotNext = nextRegister;
        java.util.List<Integer> snapshotPool = new ArrayList<>(freePool);
        // The caller (lowerForReturning) releases cond and ifCompletion at
        // the start of the update block — LibJS's allocator effectively does
        // this (the update's GetGlobal-i pops a register that requires both
        // releases to land in the pool, in the right order).
        // Snapshot the entire loopStack so deferred consequent/alternate can
        // resolve continue/break (possibly to outer labeled loops).
        java.util.List<LoopContext> loopSnap = new ArrayList<>(loopStack);
        // Pre-register the consequent's and alternate's literal constants
        // into the constant pool — LibJS's pool order matches the source
        // (first-use) order regardless of where the deferred ops are
        // physically emitted. Walk the AST for literals.
        preRegisterStatementLiterals(is.consequent());
        if (is.alternate() != null) preRegisterStatementLiterals(is.alternate());
        return new DeferredIfInLoopBody(is.consequent(), is.alternate(), ifCompletion, jumpIfPc,
            loopCompletion, /* updateStart */ -1,
            snapshotNext, snapshotPool,
            cond instanceof Variable.Register cr ? cr : null,
            loopSnap);
    }

    /**
     * Walk a statement and add every literal value to the constants pool.
     * Used by {@link #tryStartIfOnlyBody} so deferred branches' constants
     * land in the pool at their source-order position rather than at flush
     * time. Recurses into nested statements/expressions but stops at nested
     * function/class bodies (those have their own pool).
     */
    private static boolean isDestructuringPattern(Node node) {
        if (node instanceof ArrayPattern || node instanceof ObjectPattern) return true;
        if (node instanceof VariableDeclaration vd) {
            for (VariableDeclarator decl : vd.declarations()) {
                if (decl.id() instanceof ArrayPattern || decl.id() instanceof ObjectPattern) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Pre-register constants used by destructuring patterns. Mirrors LibJS:
     * - `Bool(false)` for the iterator-protocol `is_exhausted = false` init
     *   (codegen.rs:6917) — added once per top-level array pattern.
     * - default-initializer literals in source order (codegen.rs:7049).
     * Both happen during the pattern's codegen, which for for-of runs at body
     * start (before subsequent statements emit).
     */
    private void preRegisterPatternDefaults(Node node) {
        preRegisterPatternDefaults(node, /* isTopLevelArray */ true);
    }

    private void preRegisterPatternDefaults(Node node, boolean isTopLevelArray) {
        if (node == null) return;
        if (node instanceof VariableDeclaration vd) {
            for (VariableDeclarator decl : vd.declarations()) {
                preRegisterPatternDefaults(decl.id(), isTopLevelArray);
            }
            return;
        }
        if (node instanceof ArrayPattern ap) {
            // Top-level array pattern's iterator protocol uses Bool(false)
            // for is_exhausted init.
            if (isTopLevelArray && !ap.elements().isEmpty()) {
                constant(Boolean.FALSE);
            }
            for (Pattern elt : ap.elements()) {
                preRegisterPatternDefaults(elt, /* isTopLevelArray */ false);
            }
            return;
        }
        if (node instanceof ObjectPattern op) {
            for (Node prop : op.properties()) {
                if (prop instanceof Property p) {
                    preRegisterPatternDefaults(p.value(), false);
                } else if (prop instanceof RestElement re) {
                    preRegisterPatternDefaults(re.argument(), false);
                }
            }
            return;
        }
        if (node instanceof AssignmentPattern asn) {
            preRegisterLiterals(asn.right());
            preRegisterPatternDefaults(asn.left(), false);
            return;
        }
        if (node instanceof RestElement re) {
            preRegisterPatternDefaults(re.argument(), false);
        }
    }

    private void preRegisterStatementLiterals(Statement s) {
        if (s == null) return;
        if (s instanceof BlockStatement bs) {
            for (Statement inner : bs.body()) preRegisterStatementLiterals(inner);
        } else if (s instanceof ExpressionStatement es) {
            preRegisterLiterals(es.expression());
        } else if (s instanceof ThrowStatement ts) {
            preRegisterLiterals(ts.argument());
        } else if (s instanceof ReturnStatement rs) {
            if (rs.argument() != null) preRegisterLiterals(rs.argument());
        } else if (s instanceof IfStatement is) {
            preRegisterLiterals(is.test());
            preRegisterStatementLiterals(is.consequent());
            if (is.alternate() != null) preRegisterStatementLiterals(is.alternate());
        } else if (s instanceof VariableDeclaration vd) {
            for (VariableDeclarator d : vd.declarations()) {
                if (d.init() != null) preRegisterLiterals(d.init());
            }
        }
        // Continue/break/labeled — no literals.
    }

    /**
     * Emit the deferred if's consequent and after-if-merge after the for-loop's
     * spine has been laid out. Patches the body block's {@code JumpIf} to
     * point its true-target at the consequent and false-target at the after-if
     * merge. The merge transfers the if's completion to the loop's and jumps
     * back to the update target so the loop continues.
     */
    private void finishDeferredIfInLoopBody(DeferredIfInLoopBody d) {
        // Restore the allocator state to the snapshot taken right after the
        // body's JumpIf+releases — the deferred branches see the same pool
        // and nextRegister as if they were emitted immediately after the
        // JumpIf, ignoring any reuse the loop spine (update/cond) did in
        // between.
        nextRegister = d.snapshotNextRegister();
        freePool.clear();
        java.util.List<Integer> snap = d.snapshotFreePool();
        for (int i = snap.size() - 1; i >= 0; i--) freePool.push(snap.get(i));

        // Restore the loopStack snapshot so deferred consequent/alternate can
        // resolve continue/break, including labeled forms targeting outer
        // enclosing loops. Save the current loopStack and restore the
        // snapshot for the duration; reverse so the head ends up matching.
        java.util.List<LoopContext> savedStack = new ArrayList<>(loopStack);
        loopStack.clear();
        java.util.List<LoopContext> snapStack = d.loopStackSnapshot();
        for (int i = snapStack.size() - 1; i >= 0; i--) {
            loopStack.push(snapStack.get(i));
        }
        try {
            // Consequent block.
            startNewBlock();
            int consequentStart = currentPc();
            completionRegStack.push(d.ifCompletion());
            try {
                lowerStatement(d.consequent());
            } finally {
                completionRegStack.pop();
            }

            // Alternate block (if present), laid out RIGHT AFTER the consequent.
            int alternateStart = -1;
            if (d.alternate() != null) {
                startNewBlock();
                alternateStart = currentPc();
                completionRegStack.push(d.ifCompletion());
                try {
                    lowerStatement(d.alternate());
                } finally {
                    completionRegStack.pop();
                }
            }

            // After-if merge block: Mov(loopCompletion, ifCompletion); Jump(update).
            startNewBlock();
            int afterIfStart = currentPc();
            emit(new Op.Mov(d.loopCompletion(), d.ifCompletion()));
            emit(new Op.Jump(d.updateStart()));

            // Patch the body's JumpIf with both targets now that we know them.
            Op old = ops.get(d.jumpIfPc());
            if (!(old instanceof Op.JumpIf oldJif)) {
                throw new IllegalStateException("finishDeferredIfInLoopBody: expected JumpIf at pc=" + d.jumpIfPc());
            }
            int falseTarget = d.alternate() != null ? alternateStart : afterIfStart;
            ops.set(d.jumpIfPc(), new Op.JumpIf(oldJif.condition(), consequentStart, falseTarget));
        } finally {
            // Restore the original loopStack.
            loopStack.clear();
            for (int i = savedStack.size() - 1; i >= 0; i--) {
                loopStack.push(savedStack.get(i));
            }
        }
    }

    /**
     * Container for a for-loop body that's a single nested ForStatement,
     * lowered in "deferred-branches" mode: the inner for-loop's init has been
     * emitted in the outer body block and ended with a {@code Jump} placeholder
     * targeting the inner cond. The inner body/update/cond/after-merge will
     * be emitted later by {@link #finishDeferredForInLoopBody}.
     */
    /**
     * Deferred branch for if-else where the alternate is itself a single
     * if-statement. Mirrors {@link DeferredIfInLoopBody}, but the
     * after-merge jumps back to the outer if's after-if block (not a loop
     * update). Required for `S7.9_A5.2_T1.js`-style nested if-else patterns.
     */
    private record DeferredIfInAlternate(
        Statement consequent,
        Statement alternate,
        Variable.Register innerCompletion,
        int jumpIfPc,
        Variable.Register outerCompletion,
        int outerAfterIfPc,
        int snapshotNextRegister,
        java.util.List<Integer> snapshotFreePool,
        Variable.Register condReg,
        java.util.List<LoopContext> loopStackSnapshot,
        // Snapshot of the `locals` map at queue-time. Restored at flush so
        // catch parameters / outer let-decls in scope when the deferred branch
        // was queued are still resolvable when its body actually emits.
        java.util.Map<String, Integer> localsSnapshot
    ) implements DeferredLoopBranch {}

    private record DeferredForInLoopBody(
        ForStatement innerFor,
        Variable.Register innerCompletion,
        int jumpInitToCondPc,
        Variable.Register loopCompletion,
        int outerUpdateStart,
        int snapshotNextRegister,
        java.util.List<Integer> snapshotFreePool,
        java.util.List<LoopContext> loopStackSnapshot,
        // Remaining outer-body statements to emit AFTER the inner spine
        // completes, in the after-inner-merge block. Empty when outer body
        // is just the single inner for-statement.
        java.util.List<Statement> trailingOuterBodyStmts
    ) implements DeferredLoopBranch {}

    /**
     * If {@code body} is a single nested ForStatement, lower its init,
     * completion register, and Jump-to-cond inline (in the outer body block),
     * returning a {@link DeferredForInLoopBody} so the outer for-loop can
     * emit the inner's body/update/cond/after-merge out-of-line. Otherwise
     * return {@code null} so the caller falls back to normal body lowering.
     */
    private DeferredForInLoopBody tryStartForOnlyBody(Statement body, Variable.Register loopCompletion) {
        Statement effective = body;
        java.util.List<Statement> trailing = java.util.List.of();
        if (effective instanceof BlockStatement bs) {
            if (bs.body().isEmpty()) return null;
            // First statement must be a ForStatement; remaining statements
            // (if any) are emitted in the after-inner-merge block.
            Statement first = bs.body().get(0);
            if (!(first instanceof ForStatement)) return null;
            effective = first;
            if (bs.body().size() > 1) {
                trailing = bs.body().subList(1, bs.body().size());
            }
        }
        if (!(effective instanceof ForStatement innerFor)) return null;

        // 1. Inner init.
        if (innerFor.init() != null) {
            if (innerFor.init() instanceof VariableDeclaration vd) {
                lowerVarDecl(vd);
            } else if (innerFor.init() instanceof Expression initExpr) {
                Operand v = lowerExpression(initExpr);
                release(v);
            } else {
                throw new UnsupportedOperationException(
                    "Generator: inner for-init form " + innerFor.init().getClass().getSimpleName() + " not supported");
            }
        }

        // 2. Inner completion register + Mov(innerCompletion, Undefined).
        Variable.Register innerCompletion = allocRegister();
        emit(new Op.Mov(innerCompletion, constant(Undefined.VALUE)));

        // 2b. Pre-register the inner test expression's literals so they land
        // in the constant pool BEFORE the inner body's literals — matches
        // LibJS, which visits the test before lowering the body.
        if (innerFor.test() != null) preRegisterLiterals(innerFor.test());

        // 3. Jump to inner cond block (placeholder, patched in
        //    finishDeferredForInLoopBody once we know the cond block's PC).
        int jumpInitToCondPc = emit(new Op.Jump(/* placeholder */ -1));

        // 4. Snapshot the allocator state — innerCompletion still alive.
        //    finishDeferredForInLoopBody restores this so the inner's
        //    body/update/cond/merge see the same nextRegister and pool as if
        //    they had been lowered immediately after the prologue.
        // When the inner body is non-allocating (e.g. a bare break/continue),
        // pre-allocate two fresh registers and add them to the snapshot pool
        // (in alloc order, so head is the higher index). LibJS's empirical
        // pattern: the inner update's GetGlobal pops the head (higher reg)
        // and PostfixIncrement dst pops the next (lower reg). Reproducer:
        // /tmp/breakfor2.js (no trailing) and file 295 (`S7.9_A2.js`).
        boolean innerBodyNonAllocating = isNonAllocatingStatement(innerFor.body());
        int prealloc = innerBodyNonAllocating ? 2 : 0;
        int[] preallocSlots = new int[prealloc];
        for (int i = 0; i < prealloc; i++) {
            int idx = nextRegister++;
            if (idx + 1 > maxRegister) maxRegister = idx + 1;
            freePool.push(idx);
            preallocSlots[i] = idx;
        }
        int snapshotNext = nextRegister;
        java.util.List<Integer> snapshotPool = new ArrayList<>(freePool);
        // The pre-allocated slots are only for the snapshot — pop them from
        // the live pool so main-spine allocations don't see them.
        for (int i = 0; i < prealloc; i++) {
            freePool.pop();
            nextRegister--;
        }

        // 5. Snapshot the loopStack so deferred body can resolve break/continue
        //    (including labeled forms targeting outer loops).
        java.util.List<LoopContext> loopSnap = new ArrayList<>(loopStack);

        // 6. Pre-register inner body and update literals so the constant pool
        //    matches LibJS's source-order numbering.
        preRegisterStatementLiterals(innerFor.body());
        if (innerFor.update() != null) preRegisterLiterals(innerFor.update());
        // Pre-register literals from any trailing outer-body statements too —
        // they emit in the after-inner-merge block but their literals share
        // the same constant pool numbering.
        for (Statement s : trailing) preRegisterStatementLiterals(s);

        return new DeferredForInLoopBody(
            innerFor, innerCompletion, jumpInitToCondPc,
            loopCompletion, /* outerUpdateStart */ -1,
            snapshotNext, snapshotPool, loopSnap, trailing);
    }

    /**
     * Emit the deferred inner for-loop's body/update/cond/after-merge after
     * the outer loop's spine has been laid out. Patches the outer body
     * block's {@code Jump} to point at the inner cond block. The merge
     * block transfers the inner's completion to the outer's and jumps back
     * to the outer update target so the outer loop continues.
     */
    private void finishDeferredForInLoopBody(DeferredForInLoopBody d) {
        // Restore allocator state to the snapshot (innerCompletion alive).
        nextRegister = d.snapshotNextRegister();
        freePool.clear();
        java.util.List<Integer> snap = d.snapshotFreePool();
        for (int i = snap.size() - 1; i >= 0; i--) freePool.push(snap.get(i));

        // Restore loopStack snapshot for break/continue resolution within
        // the inner body. Save the current stack and restore the snapshot.
        java.util.List<LoopContext> savedStack = new ArrayList<>(loopStack);
        loopStack.clear();
        java.util.List<LoopContext> snapStack = d.loopStackSnapshot();
        for (int i = snapStack.size() - 1; i >= 0; i--) {
            loopStack.push(snapStack.get(i));
        }

        ForStatement innerFor = d.innerFor();
        Variable.Register innerCompletion = d.innerCompletion();

        try {
            // Push the inner loop context (continue/break inside the body
            // resolve to the inner loop unless explicitly labeled).
            LoopContext innerLoop = new LoopContext();
            innerLoop.completionRegister = innerCompletion;
            attachPendingLabel(innerLoop);
            loopStack.push(innerLoop);

            // Inner body block.
            startNewBlock();
            int innerBodyStart = currentPc();
            // Allow nested deferral: if the inner body is itself a single
            // if-statement or for-statement, defer its branches too.
            DeferredIfInLoopBody nestedIf = tryStartIfOnlyBody(innerFor.body(), innerCompletion, innerLoop);
            DeferredForInLoopBody nestedFor = null;
            if (nestedIf == null) {
                nestedFor = tryStartForOnlyBody(innerFor.body(), innerCompletion);
            }
            if (nestedIf == null && nestedFor == null) {
                completionRegStack.push(innerCompletion);
                try {
                    lowerStatement(innerFor.body());
                } finally {
                    completionRegStack.pop();
                }
            }

            // Inner update block.
            startNewBlock();
            int innerUpdateStart = currentPc();
            innerLoop.continueTargetPc = innerUpdateStart;
            // Release the nested deferred-if's slots before the inner update
            // emits (mirrors the same logic in lowerForReturning).
            if (nestedIf != null) {
                int ifIdx = nestedIf.ifCompletion().index();
                int condIdx = nestedIf.condReg() != null ? nestedIf.condReg().index() : -1;
                boolean bodyPoolEmpty = freePool.isEmpty();
                int first, second;
                if (bodyPoolEmpty) {
                    first = condIdx >= 0 ? Math.max(ifIdx, condIdx) : ifIdx;
                    second = condIdx >= 0 ? Math.min(ifIdx, condIdx) : -1;
                } else {
                    first = condIdx >= 0 ? Math.min(ifIdx, condIdx) : ifIdx;
                    second = condIdx >= 0 ? Math.max(ifIdx, condIdx) : -1;
                }
                if (first >= Variable.Register.FIRST_USER_INDEX) freePool.addLast(first);
                if (second >= Variable.Register.FIRST_USER_INDEX) freePool.addLast(second);
            }
            if (nestedFor != null) {
                // Same pattern as lowerForReturning's deferredFor handling:
                // LibJS releases the nested completion after the next emit.
                scheduleReleaseAfterNextEmit(nestedFor.innerCompletion());
            }
            if (innerFor.update() != null) {
                Operand u = lowerExpression(innerFor.update());
                release(u);
            }

            // Inner cond block.
            startNewBlock();
            int innerCondStart = currentPc();
            patchJumpTarget(d.jumpInitToCondPc(), innerCondStart);
            int innerCondFalsePc = -1;
            if (innerFor.test() != null) {
                innerCondFalsePc = emitForLoopCondAtTail(innerFor.test(), innerBodyStart);
            } else {
                emit(new Op.Jump(innerBodyStart));
            }

            // After-inner merge block: Mov outerCompletion := innerCompletion;
            // emit any trailing outer-body statements (e.g. a `throw` after
            // the inner for in `S7.9_A2.js`); then Jump back to outer update
            // so the outer for-loop continues — unless the trailing stmts
            // ended in an unconditional terminator (no Jump needed).
            startNewBlock();
            int afterInnerStart = currentPc();
            if (innerCondFalsePc >= 0) patchJumpTarget(innerCondFalsePc, afterInnerStart);
            emit(new Op.Mov(d.loopCompletion(), innerCompletion));
            boolean lastWasTerminator = false;
            if (!d.trailingOuterBodyStmts().isEmpty()) {
                completionRegStack.push(d.loopCompletion());
                try {
                    for (Statement s : d.trailingOuterBodyStmts()) {
                        lowerStatement(s);
                        if (isUnconditionalTerminator(s)) {
                            lastWasTerminator = true;
                            break;
                        }
                    }
                } finally {
                    completionRegStack.pop();
                }
            }
            if (!lastWasTerminator) {
                emit(new Op.Jump(d.outerUpdateStart()));
            }

            loopStack.pop();
            innerLoop.breakTargetPc = afterInnerStart;
            for (int p : innerLoop.pendingBreakPcs) patchJumpTarget(p, innerLoop.breakTargetPc);
            for (int p : innerLoop.pendingContinuePcs) patchJumpTarget(p, innerLoop.continueTargetPc);

            // Queue any nested deferrals discovered above; they get processed
            // in the same flush (flushDeferredLoopBranches iterates by index).
            if (nestedIf != null) {
                deferredLoopBranches.add(new DeferredIfInLoopBody(
                    nestedIf.consequent(),
                    nestedIf.alternate(),
                    nestedIf.ifCompletion(),
                    nestedIf.jumpIfPc(),
                    innerCompletion,
                    innerUpdateStart,
                    nestedIf.snapshotNextRegister(),
                    nestedIf.snapshotFreePool(),
                    nestedIf.condReg(),
                    nestedIf.loopStackSnapshot()));
            }
            if (nestedFor != null) {
                deferredLoopBranches.add(new DeferredForInLoopBody(
                    nestedFor.innerFor(),
                    nestedFor.innerCompletion(),
                    nestedFor.jumpInitToCondPc(),
                    innerCompletion,
                    innerUpdateStart,
                    nestedFor.snapshotNextRegister(),
                    nestedFor.snapshotFreePool(),
                    nestedFor.loopStackSnapshot(),
                    nestedFor.trailingOuterBodyStmts()));
            }
        } finally {
            loopStack.clear();
            for (int i = savedStack.size() - 1; i >= 0; i--) {
                loopStack.push(savedStack.get(i));
            }
        }
    }

    /**
     * Emit all queued for-loop deferred if-branches. Called by
     * {@link #lowerProgram} after the script's {@code End} is emitted (and by
     * {@link #generateFunction} after the function's {@code Return}).
     */
    private void flushDeferredLoopBranches() {
        // Re-entry guard: when an inner if/for inside a deferred body calls
        // flush, the outer flush is already iterating deferredLoopBranches by
        // index. New entries appended by inner lowering get picked up by that
        // outer iterator. Recursing here would restart iteration from index 0
        // and re-process items the outer flush is in the middle of, which is
        // both wrong (layout) and infinite (recursion). Required for any
        // nested-for whose body contains an if (e.g. test262 S7.4_A5.js).
        if (inDeferredFlush) return;
        if (deferredLoopBranches.isEmpty()) return;
        // Snapshot main-spine allocator state. Deferred branches use their own
        // saved snapshots internally; after the entire flush completes, we
        // restore the main-spine pool so subsequent main-spine allocations
        // don't see registers that the deferred items pushed/popped.
        // {@link #maxRegister} keeps growing across the flush so the final
        // register-file size includes the deferred allocations.
        int savedNextRegister = nextRegister;
        java.util.List<Integer> savedFreePool = new ArrayList<>(freePool);

        boolean savedInDeferred = inDeferredFlush;
        inDeferredFlush = true;
        try {
            // Iterate by index because deferred branches may queue MORE deferred
            // branches as they are emitted (e.g. a deferred for-body that itself
            // contains a nested deferred for or if).
            for (int i = 0; i < deferredLoopBranches.size(); i++) {
                DeferredLoopBranch d = deferredLoopBranches.get(i);
                switch (d) {
                    case DeferredIfInLoopBody di -> finishDeferredIfInLoopBody(di);
                    case DeferredForInLoopBody df -> finishDeferredForInLoopBody(df);
                    case DeferredIfInAlternate da -> finishDeferredIfInAlternate(da);
                    case DeferredFlattenedForUpdate du -> finishDeferredFlattenedForUpdate(du);
                    case DeferredDoWhileBody dd -> finishDeferredDoWhileBody(dd);
                    case DeferredTryFinallyTail tf -> finishDeferredTryFinallyTail(tf);
                    case DeferredTryBody db -> finishDeferredTryBody(db);
                    case DeferredIteratorClose ic -> finishDeferredIteratorClose(ic);
                    case DeferredForOf fo -> finishDeferredForOf(fo);
                }
            }
            deferredLoopBranches.clear();
        } finally {
            inDeferredFlush = savedInDeferred;
        }

        // Restore main-spine allocator state. Deferred items that bridged
        // into the main spine (e.g. an ifCompletion register reused for the
        // next outer-completion) are NOT in the saved pool — that's
        // intentional, because LibJS treats deferred items as isolated and
        // bridges via explicit Mov/Jump rather than allocator state.
        nextRegister = savedNextRegister;
        freePool.clear();
        for (int i = savedFreePool.size() - 1; i >= 0; i--) freePool.push(savedFreePool.get(i));
    }

    /**
     * Walk {@code expr} and add every Literal's value to the constant pool
     * (idempotent via {@link #constant}). Used by the rotated for-loop
     * lowering so test-expression literals land in the pool before body
     * literals — matching LibJS's source-order constant numbering. This is a
     * shape-only pass: no ops are emitted.
     */
    private void preRegisterLiterals(Node node) {
        if (node == null) return;
        if (node instanceof Literal lit) {
            constant(literalValue(lit));
            return;
        }
        if (node instanceof BinaryExpression b) {
            // If both operands are literals AND the binary op would const-fold
            // at lowering time, register only the folded result so the pool
            // matches LibJS's pool (LibJS folds before adding constants).
            Object folded = tryFoldLiteralBinary(b);
            if (folded != null) {
                constant(folded);
                return;
            }
            preRegisterLiterals(b.left());
            preRegisterLiterals(b.right());
        } else if (node instanceof LogicalExpression lo) {
            preRegisterLiterals(lo.left());
            preRegisterLiterals(lo.right());
        } else if (node instanceof UnaryExpression u) {
            preRegisterLiterals(u.argument());
        } else if (node instanceof MemberExpression m) {
            preRegisterLiterals(m.object());
            if (m.computed()) preRegisterLiterals(m.property());
        } else if (node instanceof CallExpression c) {
            preRegisterLiterals(c.callee());
            for (Expression a : c.arguments()) preRegisterLiterals(a);
        } else if (node instanceof NewExpression ne) {
            preRegisterLiterals(ne.callee());
            for (Expression a : ne.arguments()) preRegisterLiterals(a);
        } else if (node instanceof AssignmentExpression ae) {
            preRegisterLiterals(ae.right());
        } else if (node instanceof ConditionalExpression ce) {
            preRegisterLiterals(ce.test());
            preRegisterLiterals(ce.consequent());
            preRegisterLiterals(ce.alternate());
        } else if (node instanceof ArrayExpression arr) {
            for (Expression elt : arr.elements()) {
                if (elt != null) preRegisterLiterals(elt);
            }
        }
        // Identifiers, etc.: no literals to register.
    }

    /**
     * Emit a conditional branch at the cond block of a rotated for-loop. The
     * true-target is the body block (already laid out earlier in the bytecode
     * stream); the false-target is left as a placeholder for the caller to
     * patch with the after-loop block's PC. Uses the fused JumpLessThan etc.
     * opcodes when the test is a comparable BinaryExpression — matching LibJS.
     */
    /**
     * Patch the trueTarget PC of a fused-comparison Jump op (used by the
     * for-loop save-and-replay path). Returns a new op with the same operands
     * but updated trueTarget.
     */
    private static Op patchFusedJumpTrueTarget(Op op, int trueTarget) {
        return switch (op) {
            case Op.JumpLessThan          o -> new Op.JumpLessThan          (o.lhs(), o.rhs(), trueTarget, o.falseTargetPc());
            case Op.JumpLessThanEquals    o -> new Op.JumpLessThanEquals    (o.lhs(), o.rhs(), trueTarget, o.falseTargetPc());
            case Op.JumpGreaterThan       o -> new Op.JumpGreaterThan       (o.lhs(), o.rhs(), trueTarget, o.falseTargetPc());
            case Op.JumpGreaterThanEquals o -> new Op.JumpGreaterThanEquals (o.lhs(), o.rhs(), trueTarget, o.falseTargetPc());
            case Op.JumpStrictlyEquals    o -> new Op.JumpStrictlyEquals    (o.lhs(), o.rhs(), trueTarget, o.falseTargetPc());
            case Op.JumpStrictlyInequals  o -> new Op.JumpStrictlyInequals  (o.lhs(), o.rhs(), trueTarget, o.falseTargetPc());
            case Op.JumpLooselyEquals     o -> new Op.JumpLooselyEquals     (o.lhs(), o.rhs(), trueTarget, o.falseTargetPc());
            case Op.JumpLooselyInequals   o -> new Op.JumpLooselyInequals   (o.lhs(), o.rhs(), trueTarget, o.falseTargetPc());
            default -> throw new IllegalStateException(
                "patchFusedJumpTrueTarget: unexpected op " + op.getClass().getSimpleName());
        };
    }

    private int emitForLoopCondAtTail(Expression test, int bodyStart) {
        if (test instanceof BinaryExpression bin && isFusableComparison(bin.operator())) {
            Operand l = lowerExpression(bin.left());
            Operand r = lowerExpression(bin.right());
            // LibJS's `generate_binary_expression` allocates a `dst` register
            // for the comparison's result. The fused JumpLessThan etc. doesn't
            // use a result register, but the alloc still happens — and the
            // resulting ScopedOperand (test_val) stays alive past the binary
            // expression's return. It drops at the end of the test's outer
            // `if let Some(test)` scope, AFTER lhs and rhs have already been
            // dropped — landing at the LIFO pool head when update emits.
            // Mirror that here with a fake dst alloc.
            Variable.Register fakeDst = allocRegister();
            int pc = switch (bin.operator()) {
                case "<"   -> emit(new Op.JumpLessThan          (l, r, bodyStart, -1));
                case "<="  -> emit(new Op.JumpLessThanEquals    (l, r, bodyStart, -1));
                case ">"   -> emit(new Op.JumpGreaterThan       (l, r, bodyStart, -1));
                case ">="  -> emit(new Op.JumpGreaterThanEquals (l, r, bodyStart, -1));
                case "===" -> emit(new Op.JumpStrictlyEquals    (l, r, bodyStart, -1));
                case "!==" -> emit(new Op.JumpStrictlyInequals  (l, r, bodyStart, -1));
                case "=="  -> emit(new Op.JumpLooselyEquals     (l, r, bodyStart, -1));
                case "!="  -> emit(new Op.JumpLooselyInequals   (l, r, bodyStart, -1));
                default -> throw new IllegalStateException("non-fusable operator: " + bin.operator());
            };
            release(r);
            release(l);
            release(fakeDst);   // pushed last → at LIFO pool head
            // Mark bodyStart as a block boundary so Mov-fusion peephole doesn't
            // span across the trueTarget jump. Skip if bodyStart < 0
            // (placeholder during dry-run capture).
            if (bodyStart >= 0 && !blockStartPcs.contains(bodyStart)) {
                int insertAt = 0;
                while (insertAt < blockStartPcs.size() && blockStartPcs.get(insertAt) < bodyStart) insertAt++;
                blockStartPcs.add(insertAt, bodyStart);
            }
            return pc;
        }
        // Non-fusable: lower the test, then JumpTrue(body) — fall-through is
        // the false case, leading to the after-loop block. If the test is a
        // statically-truthy constant, fold to an unconditional Jump (matches
        // LibJS for `for(;2;);` style loops).
        Operand cond = lowerExpression(test);
        if (cond instanceof Operand.Constant cc && isTruthyConstant(constants.get(cc.index()))) {
            emit(new Op.Jump(bodyStart));
        } else {
            emit(new Op.JumpTrue(cond, bodyStart));
        }
        release(cond);
        // No false-target patch needed; fall-through is the after-loop block.
        return -1;
    }

    /**
     * Static-truthiness test for constant-folded conditional jumps. Returns
     * true only when the value is one whose ECMAScript boolean coercion is
     * unambiguously {@code true}: non-zero numbers, non-empty strings,
     * {@code true}, etc. {@code null} / {@code undefined} / {@code 0} /
     * {@code ""} are NOT truthy. Conservatively returns false for objects
     * (could have @@toPrimitive shenanigans) and unknown shapes.
     */
    private static boolean isTruthyConstant(Object v) {
        if (v == null) return false;
        if (v == Undefined.VALUE) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0.0 && !Double.isNaN(d);
        if (v instanceof Integer i) return i != 0;
        if (v instanceof Long l) return l != 0;
        if (v instanceof String s) return !s.isEmpty();
        return false;
    }

    /**
     * try { tryBody } catch (e) { catchBody } [finally { ... }].
     *
     * <p>v1: finally is supported only as "always run after try/catch", not the
     * full spec semantics (no completion-record interaction with break/return
     * in finally). The try region is registered with an ExceptionHandler that
     * routes thrown values to the catch entry; the catch entry begins with a
     * {@code Catch} op that moves the in-flight exception into the binding.
     */
    private Variable.Register lowerTry(TryStatement ts) {
        // Dispatch: try-finally-without-catch has a different bytecode shape
        // than try-catch (LibJS uses an abrupt-completion-type register and
        // a tail of abnormal-completion blocks). When a finalizer is present
        // and there's no catch handler, take the dedicated path.
        if (ts.handler() == null && ts.finalizer() != null) {
            return lowerTryFinallyNoCatch(ts);
        }
        if (ts.handler() != null && ts.finalizer() != null) {
            return lowerTryCatchFinally(ts);
        }
        return lowerTryWithCatch(ts);
    }

    /**
     * try { B } finally { F }   (no catch clause).
     *
     * <p>LibJS bytecode shape:
     * <pre>
     *   block0: GetLexicalEnvironment + Jump → tryBody
     *   block1: catchHandler — Catch dst:exReg, SetLexEnv, Mov typeReg=Int32(1)
     *           (falls through into finalizer block)
     *   block2: finalizer body, transfer Movs, JumpStrictlyEquals(typeReg, 0,
     *           normalEnd, abnormalCheck)
     *   block3: tryBody — body content, transfer Movs (innerComp, outerComp),
     *           Mov typeReg=Int32(0), Jump → finalizer
     *   normalEnd: caller continues; eventually End / Return
     *   ---deferred (after caller's End)---
     *   abnormalCheck: JumpStrictlyEquals(typeReg, Int32(2), returnTarget,
     *                  throwTarget)
     *   returnTarget: Return exReg
     *   throwTarget: Throw exReg
     * </pre>
     */
    private Variable.Register lowerTryFinallyNoCatch(TryStatement ts) {
        if (!lexicalEnvironmentSaved) {
            emit(new Op.GetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
            lexicalEnvironmentSaved = true;
        }
        int jumpToTryBodyPc = emit(new Op.Jump(/* placeholder */ -1));

        // ----- Catch handler block: catches any exception and falls through
        // to the finalizer with typeReg = Int32(1).
        startNewBlock();
        int handlerPc = currentPc();
        Variable.Register exceptionReg = allocRegister();
        emit(new Op.Catch(exceptionReg));
        emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
        Variable.Register typeReg = allocRegister();
        emit(new Op.Mov(typeReg, constant(1.0d)));

        // ----- Finalizer block. Two entry paths fall into here: (1) the
        // catch handler (after a try-body throw), (2) the try body's
        // explicit Jump after normal completion. The block ends with a
        // JumpStrictlyEquals dispatching on typeReg.
        startNewBlock();
        int finalizerStart = currentPc();
        Variable.Register finalizerComp = allocRegister();
        emit(new Op.Mov(finalizerComp, constant(Undefined.VALUE)));
        completionRegStack.push(finalizerComp);
        Operand lastFinalizerValue = finalizerComp;
        try {
            for (Statement s : ts.finalizer().body()) {
                Operand v = lowerStatement(s);
                if (v != null) lastFinalizerValue = v;
            }
        } finally {
            completionRegStack.pop();
        }
        if (!(lastFinalizerValue instanceof Variable.Register lfvr
              && lfvr.index() == finalizerComp.index())) {
            emit(new Op.Mov(finalizerComp, lastFinalizerValue));
        }
        // JumpStrictlyEquals: false_target patched at normalEnd block start;
        // true_target patched after the deferred abnormal-check emits.
        int finalizerJumpPc = emit(new Op.JumpStrictlyEquals(
            typeReg, constant(0.0d), /* trueTarget */ -1, /* falseTarget */ -1));
        release(finalizerComp);

        // ----- Try body block. The prologue's Jump targets here.
        startNewBlock();
        int tryBodyStart = currentPc();
        patchJumpTarget(jumpToTryBodyPc, tryBodyStart);
        Variable.Register innerCompletion = allocRegister();
        emit(new Op.Mov(innerCompletion, constant(Undefined.VALUE)));
        completionRegStack.push(innerCompletion);
        Operand lastBodyValue = innerCompletion;
        try {
            for (Statement s : ts.block().body()) {
                Operand v = lowerStatement(s);
                if (v != null) lastBodyValue = v;
            }
        } finally {
            completionRegStack.pop();
        }
        Variable.Register outerCompletion = allocRegister();
        if (!(lastBodyValue instanceof Variable.Register lbr
              && lbr.index() == innerCompletion.index())) {
            emit(new Op.Mov(innerCompletion, lastBodyValue));
        }
        emit(new Op.Mov(outerCompletion, innerCompletion));
        emit(new Op.Mov(typeReg, constant(0.0d)));
        emit(new Op.Jump(finalizerStart));
        int tryBodyEnd = currentPc();

        exceptionHandlers.add(new Executable.ExceptionHandler(tryBodyStart, tryBodyEnd, handlerPc));

        // ----- Normal-end block. Finalizer's true_target lands here. Caller
        // continues here; eventually emits End / Return.
        startNewBlock();
        int normalEndStart = currentPc();
        // Patch the JumpStrictlyEquals's true_target.
        Op.JumpStrictlyEquals fjmp = (Op.JumpStrictlyEquals) ops.get(finalizerJumpPc);
        ops.set(finalizerJumpPc, new Op.JumpStrictlyEquals(
            fjmp.lhs(), fjmp.rhs(), normalEndStart, /* falseTarget */ -1));

        release(innerCompletion);

        // Defer the abnormal-check / Return / Throw blocks to the end of the
        // script (or function). They emit at higher PC, reachable only from
        // the finalizer's JumpStrictlyEquals false_target.
        deferredLoopBranches.add(new DeferredTryFinallyTail(typeReg, exceptionReg, finalizerJumpPc));

        // Return the outer completion so the caller can use it as the
        // try-finally statement's value.
        return outerCompletion;
    }

    /**
     * Emit the abnormal-completion tail for a try-finally at the end of the
     * script/function. Patches the finalizer's JumpStrictlyEquals to point
     * its false_target here.
     */
    private void finishDeferredTryFinallyTail(DeferredTryFinallyTail t) {
        startNewBlock();
        int abnormalCheckStart = currentPc();
        // Patch the finalizer's JumpStrictlyEquals false_target.
        Op.JumpStrictlyEquals fjmp = (Op.JumpStrictlyEquals) ops.get(t.finalizerJumpFalsePc());
        ops.set(t.finalizerJumpFalsePc(), new Op.JumpStrictlyEquals(
            fjmp.lhs(), fjmp.rhs(), fjmp.trueTargetPc(), abnormalCheckStart));

        int abnormalJumpPc = emit(new Op.JumpStrictlyEquals(
            t.typeReg(), constant(2.0d), /* trueTarget */ -1, /* falseTarget */ -1));

        startNewBlock();
        int returnStart = currentPc();
        emit(new Op.Return(t.exceptionReg()));

        startNewBlock();
        int throwStart = currentPc();
        emit(new Op.Throw(t.exceptionReg()));

        // Patch the abnormalCheck JumpStrictlyEquals.
        Op.JumpStrictlyEquals ajmp = (Op.JumpStrictlyEquals) ops.get(abnormalJumpPc);
        ops.set(abnormalJumpPc, new Op.JumpStrictlyEquals(
            ajmp.lhs(), ajmp.rhs(), returnStart, throwStart));
    }

    private Variable.Register lowerTryWithCatch(TryStatement ts) {
        // try-catch-finally goes through lowerTryCatchFinally; this path
        // handles try-catch only (no finally).
        // LibJS layout for `try { B } catch (e) { C }`:
        //   prologue: GetLexicalEnvironment dst:reg4; Jump → tryBody
        //   handler:  Catch dst:scratch; SetLexicalEnvironment env:reg4;
        //             Mov(eLocal, scratch); Mov(outerCompletion, Undefined);
        //             ...catch body...
        //   tryBody:  Mov(scratch, Undefined); ...try body...; Mov(outer, scratch)
        //   afterTry: <next statement>
        //
        // Note: the catch BLOCK precedes the try-body block in the bytecode
        // stream — the prologue's Jump skips over it; the exception table
        // points the try-body's PC range at the handler block.

        // Prologue. Save the lexical environment once per scope; subsequent
        // try-statements reuse the same saved slot.
        if (!lexicalEnvironmentSaved) {
            emit(new Op.GetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
            lexicalEnvironmentSaved = true;
        }
        int jumpToTryBodyPc = emit(new Op.Jump(/* placeholder */ -1));

        // ----- Catch block -----
        startNewBlock();
        int handlerPc = currentPc();
        if (ts.handler() == null) {
            // try {…} finally {…} with no catch goes through
            // lowerTryFinallyNoCatch; this method only handles try-catch.
            throw new IllegalStateException(
                "lowerTryWithCatch reached the no-catch branch");
        }
        CatchClause handler = ts.handler();
        // Allocate the Catch dst inline. The slot is reused later by the
        // try-body for its own inner-completion register.
        Variable.Register catchDst = allocRegister();                       // reg5
        emit(new Op.Catch(catchDst));
        emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
        // Save outer `locals` view: the catch binding shadows any outer
        // same-named local while the catch body is being lowered, then we
        // restore on exit so post-catch references see the outer binding.
        Integer savedCatchShadow = null;
        String catchParamName = null;
        // Decision: catch parameter goes into a lexical environment when the
        // catch body contains a nested closure (function/arrow/class/eval) that
        // could capture it. Otherwise stays in a local slot.
        boolean useLexEnv = handler.param() != null
            && catchBodyNeedsLexEnv(handler.body().body());
        Variable.Register catchEnvReg = null;
        boolean catchParamIsPattern = handler.param() != null
            && (handler.param() instanceof ArrayPattern
                || handler.param() instanceof ObjectPattern);
        if (handler.param() != null && !catchParamIsPattern) {
            if (!(handler.param() instanceof Identifier id)) {
                throw new UnsupportedOperationException(
                    "Generator: catch param shape not supported: "
                    + handler.param().getClass().getSimpleName());
            }
            catchParamName = id.name();
            if (useLexEnv) {
                catchEnvReg = allocRegister();
                emit(new Op.CreateLexicalEnvironment(catchEnvReg,
                    Variable.Register.SAVED_LEXICAL_ENVIRONMENT, 0));
                emit(new Op.CreateVariable(catchParamName,
                    /* isImmutable */ false, /* isGlobal */ false, /* isStrict */ false));
                emit(new Op.InitializeLexicalBinding(catchParamName, catchDst,
                    new EnvironmentCoordinate()));
                lexEnvBindingNames.add(catchParamName);
            } else {
                // Use the pre-allocated catch-param slot (filled by
                // preAllocateLetSlotsRecurse) when available — its slot was
                // chosen at DFS time so it lands BEFORE the outer block's
                // same-named let. Fall back to freshLocal for catch arms not
                // visited by the pre-pass (e.g. inside dead code).
                Integer preSlot = catchParamSlots.get(handler);
                Variable.Local exceptionLocal = preSlot != null
                    ? new Variable.Local(preSlot)
                    : freshLocal(id.name());
                emit(new Op.Mov(exceptionLocal, catchDst));
                savedCatchShadow = locals.get(catchParamName);
                locals.put(catchParamName, exceptionLocal.slot());
            }
        } else if (catchParamIsPattern) {
            // Destructuring catch param: bind the exception via bindPattern.
            // Always use lex-env if needed; pattern bindings go to fresh locals.
            if (useLexEnv) {
                catchEnvReg = allocRegister();
                emit(new Op.CreateLexicalEnvironment(catchEnvReg,
                    Variable.Register.SAVED_LEXICAL_ENVIRONMENT, 0));
            }
            // bindPattern with LOCAL mode allocates fresh locals for each
            // identifier in the pattern. catchDst's value flows through.
            bindPattern(handler.param(), catchDst, BindMode.LOCAL);
        }
        Variable.Register innerCatchCompletion = allocRegister();           // reg6 (or reg7 if lex-env)
        emit(new Op.Mov(innerCatchCompletion, constant(Undefined.VALUE)));
        completionRegStack.push(innerCatchCompletion);
        if (useLexEnv) lexEnvCatchBodyDepth++;
        boolean hasNestedBlock = catchBodyHasNestedBlock(handler.body().body());
        Variable.Register savedPendingLeak = pendingExprStmtDstRelease;
        pendingExprStmtDstRelease = null;
        if (hasNestedBlock) doWhileWithNestedBlockDepth++;
        // Track the most recent child-statement completion register so we can
        // emit `Mov(innerCatchCompletion, val)` after the loop and then
        // release `val`'s slot — matches LibJS's `generate_scope_children`
        // (codegen.rs:2517-2544) which emits a completion-mov per iteration
        // and drops the prior `last_result` Rc clone. The `Mov` then fuses
        // with the outer try-statement's transfer Mov into a Mov2.
        Variable.Register catchBodyLastCompletionReg = null;
        try {
            for (Statement s : handler.body().body()) {
                Operand v = lowerStatement(s);
                // For statements that return their own completion register
                // (if/while/for/try return values), capture it. ExpressionStatement
                // already emits Mov(completionReg, v) internally.
                if (v instanceof Variable.Register vr
                    && vr.index() >= Variable.Register.FIRST_USER_INDEX
                    && vr.index() != innerCatchCompletion.index()) {
                    catchBodyLastCompletionReg = vr;
                }
            }
        } finally {
            if (hasNestedBlock) doWhileWithNestedBlockDepth--;
            if (useLexEnv) lexEnvCatchBodyDepth--;
            completionRegStack.pop();
        }
        // scope_children: emit `Mov(catchCompletion, lastChildValue)` for
        // child statements that returned their own completion register
        // (if/while/for/try). The Mov fuses with the outer-completion
        // transfer Mov below into a Mov2.
        if (catchBodyLastCompletionReg != null) {
            emit(new Op.Mov(innerCatchCompletion, catchBodyLastCompletionReg));
            // Release the child's completion — its slot returns to LIFO so
            // the outer-completion alloc reuses it.
            release(catchBodyLastCompletionReg);
        }
        // Release the catch body's last leaked expression-statement dst so
        // the slot lands at the pool head — the catch arm's catchArmExtra
        // alloc reuses it (matches LibJS for tests like 392).
        if (pendingExprStmtDstRelease != null) {
            release(pendingExprStmtDstRelease);
            pendingExprStmtDstRelease = null;
        }
        pendingExprStmtDstRelease = savedPendingLeak;
        if (useLexEnv) {
            // catchArmExtra: at the end of the catch body, allocate a fresh
            // register and Mov inner-completion into it. Same pattern as the
            // non-lex-env case (Mov3 at start), but here the Mov fuses with
            // the body's last expression-statement propagation (Mov2 at end).
            // catchArmExtra stays alive (not released) so post-catch
            // allocations bump past it.
            Variable.Register catchArmExtra = allocRegister();
            emit(new Op.Mov(catchArmExtra, innerCatchCompletion));
            // Restore outer lex-env.
            emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
            lexEnvBindingNames.remove(catchParamName);
        }

        // LibJS branches the layout based on whether the catch body falls
        // through. If yes (e.g. empty `catch (e) {}`): emit a Jump → after-try
        // at the end of catch, defer the try-body to the very end of the
        // script/function (LibJS's "make_block AFTER catch handler" pattern).
        // If no (catch terminates with throw/return): emit try-body INLINE
        // immediately after catch, since catch never reaches after-try.
        boolean catchFallsThrough = !lastReachableIsTerminator(handler.body());

        Variable.Register catchArmExtra = null;
        if (catchFallsThrough) {
            if (!useLexEnv) {
                // Allocate the catch arm's "scope completion" register and bridge
                // the inner catch-completion into it. LibJS does this for
                // local-slot catches that fall through; the Mov fuses with the
                // prior two Movs into Mov3.
                catchArmExtra = allocRegister();                            // reg7
                emit(new Op.Mov(catchArmExtra, innerCatchCompletion));
                // catchArmExtra stays alive (not released) so subsequent
                // try-body allocations bump past it.
            }
            // Release order differs for lex-env vs local-slot catches.
            //
            // Local-slot: release inner-catch then catchDst — pool ends with
            // catchDst (reg5) on top so deferred try-body pops reg5 for its
            // inner completion, reg6 for body call dst.
            //
            // Lex-env: drain any deferred Call-temps from the catch body's
            // last expression (these were left in endOfStatementReleases by
            // lowerExpressionStatement) THEN release catchEnvReg →
            // innerCatchCompletion → catchDst, so pool ends with catchDst
            // (reg5) on top, then innerCatchCompletion (reg7), then
            // catchEnvReg (reg6). After-try's first call cycles through
            // those slots — Call dst:reg5, callee:reg7, arg:reg6. Matches
            // LibJS for tests like 372.
            if (useLexEnv) {
                if (!endOfStatementReleases.isEmpty()) {
                    for (Operand op : endOfStatementReleases) release(op);
                    endOfStatementReleases.clear();
                }
                if (catchEnvReg != null) release(catchEnvReg);
                release(innerCatchCompletion);
                release(catchDst);
            } else {
                // For the local-slot fall-through catch, release inner then
                // catchDst — pool ends with catchDst on top, then inner. The
                // deferred try-body's snapshot picks up these slots first
                // (innerCompletion=catchDst's slot, Call dst=inner's slot).
                // catchArmExtra is released AFTER the snapshot so the try-body
                // skips its slot and bumps to fresh registers.
                release(innerCatchCompletion);
                release(catchDst);
            }
            if (catchParamName != null && !useLexEnv) {
                if (savedCatchShadow != null) locals.put(catchParamName, savedCatchShadow);
                else locals.remove(catchParamName);
            }
            // No Jump from catch-end — after-try is the immediately-following
            // block in PC order, so the Mov3 at catch's tail falls through
            // into after-try (whose first instruction will be End/Return for
            // a script-terminating try-catch, which is a terminator anyway).
            // LibJS's assemble-time peephole drops this Jump-to-fall-through.

            // ----- After-try block (caller continues here inline) -----
            startNewBlock();
            int afterTryStart = currentPc();

            int snapshotNext = nextRegister;
            java.util.List<Integer> snapshotPool = new ArrayList<>(freePool);

            // LibJS returns the outer-completion register (our
            // catchArmExtra), allocated AFTER the catch body — its slot lands
            // at the just-released if/while/for completion's slot via LIFO,
            // and the script's End reads from it (matches Mov2 fusion +
            // JumpToEnd peephole at codegen.rs:7332-7339, generator.rs:1641).
            Variable.Register tryValue = catchArmExtra != null
                ? catchArmExtra
                : innerCatchCompletion;
            deferredLoopBranches.add(new DeferredTryBody(
                ts.block(),
                tryValue,
                jumpToTryBodyPc,
                handlerPc,
                afterTryStart,
                snapshotNext,
                snapshotPool,
                new ArrayList<>(loopStack)));

            return tryValue;
        }

        // ----- Catch terminates: emit try-body INLINE -----
        // Release in REVERSE allocation order — pool ends with catchDst on
        // top, then innerCatchCompletion. Try-body pops catchDst's slot
        // (reg5) for its inner completion; the pre-allocation of any Call
        // dst inside the body picks up innerCatchCompletion's slot (reg6).
        release(innerCatchCompletion);
        if (catchEnvReg != null) release(catchEnvReg);
        release(catchDst);
        if (catchParamName != null && !useLexEnv) {
            if (savedCatchShadow != null) locals.put(catchParamName, savedCatchShadow);
            else locals.remove(catchParamName);
        }
        // No Jump after catch — catch already terminated. Continue emitting
        // the try-body in the next block.

        startNewBlock();
        int tryBodyStart = currentPc();
        patchJumpTarget(jumpToTryBodyPc, tryBodyStart);
        Variable.Register innerCompletion = allocRegister();
        emit(new Op.Mov(innerCompletion, constant(Undefined.VALUE)));
        completionRegStack.push(innerCompletion);
        Variable.Register savedPriorCompletion = priorScopeCompletionReg;
        priorScopeCompletionReg = null;
        Operand lastBodyValue = innerCompletion;
        try {
            for (Statement s : ts.block().body()) {
                Operand v = lowerStatement(s);
                if (v != null) lastBodyValue = v;
            }
        } finally {
            completionRegStack.pop();
        }
        if (priorScopeCompletionReg != null) {
            release(priorScopeCompletionReg);
        }
        priorScopeCompletionReg = savedPriorCompletion;

        // Allocate a fresh "outer completion" register; pool head should now
        // be the slot that held the body's last expression value (e.g. reg6
        // for a Call dst that was released after its expression statement),
        // so the alloc reuses that slot — matching LibJS's behaviour.
        Variable.Register outerCompletion = allocRegister();
        // Transfer Movs: Mov(inner, lastBodyValue); Mov(outer, inner). The
        // emit-time peephole fuses these into Mov2 when both fire.
        if (!(lastBodyValue instanceof Variable.Register lbr
              && lbr.index() == innerCompletion.index())) {
            emit(new Op.Mov(innerCompletion, lastBodyValue));
        }
        emit(new Op.Mov(outerCompletion, innerCompletion));
        int tryBodyEnd = currentPc();

        exceptionHandlers.add(new Executable.ExceptionHandler(tryBodyStart, tryBodyEnd, handlerPc));

        release(innerCompletion);

        // Start the after-try block so subsequent statements land in a fresh
        // block. LibJS materializes a `next_block` here even when the catch
        // arm terminates — it's where the caller continues, distinct from
        // the try-body in the block list.
        startNewBlock();

        return outerCompletion;
    }

    /**
     * Emit the deferred try-body block at the end of the script/function,
     * after all main-spine statements (including the script's End) have
     * been emitted. Patches the prologue's {@code Jump} to point at the
     * try-body's start, lowers the body, propagates its value into the
     * try-statement's outer-completion register, and terminates with a
     * {@code Jump → afterTryStart} (or an inline {@code End}/{@code Return}
     * if the after-try block is exactly that single terminator).
     */
    private void finishDeferredTryBody(DeferredTryBody d) {
        // Restore allocator state so the try-body's allocations cycle through
        // the same registers the catch arm released.
        nextRegister = d.snapshotNextRegister();
        freePool.clear();
        java.util.List<Integer> snap = d.snapshotFreePool();
        for (int i = snap.size() - 1; i >= 0; i--) freePool.push(snap.get(i));

        // Restore the outer loopStack so any break/continue inside the
        // deferred body can resolve to the enclosing loop. The loop has
        // long since been popped from the live loopStack by the time the
        // flush runs (try-body emission is deferred to script/function tail).
        // Save current loopStack contents (head-to-tail order) so we can
        // restore them after the body emits.
        java.util.List<LoopContext> savedLoopStack = new ArrayList<>(loopStack);
        loopStack.clear();
        java.util.List<LoopContext> snapStack = d.snapshotLoopStack();
        for (int i = snapStack.size() - 1; i >= 0; i--) loopStack.push(snapStack.get(i));

        startNewBlock();
        int tryBodyStart = currentPc();
        patchJumpTarget(d.jumpToTryBodyPc(), tryBodyStart);

        Variable.Register innerCompletion = allocRegister();
        emit(new Op.Mov(innerCompletion, constant(Undefined.VALUE)));
        completionRegStack.push(innerCompletion);
        Variable.Register savedPriorCompletion = priorScopeCompletionReg;
        priorScopeCompletionReg = null;
        Operand lastBodyValue = innerCompletion;
        // LibJS-style last_result tracking (codegen.rs:2517-2544): keep the
        // prior stmt's dst alive across the next stmt's lowering. Mirrors
        // BlockStatement's tracking but applied here because we're iterating
        // tryBlock.body() directly (not via lowerStatement on BlockStatement).
        boolean savedTracking = lastResultTrackingActive;
        lastResultTrackingActive = true;
        Variable.Register lastTryBodyReg = null;
        try {
            for (Statement s : d.tryBlock().body()) {
                Operand v = lowerStatement(s);
                if (v != null) {
                    lastBodyValue = v;
                    if (v instanceof Variable.Register r
                        && r.index() >= Variable.Register.FIRST_USER_INDEX) {
                        if (lastTryBodyReg != null) release(lastTryBodyReg);
                        lastTryBodyReg = r;
                    } else if (lastTryBodyReg != null
                        && !(v instanceof Variable.Register)) {
                        release(lastTryBodyReg);
                        lastTryBodyReg = null;
                    }
                }
            }
        } finally {
            lastResultTrackingActive = savedTracking;
            completionRegStack.pop();
        }
        if (lastTryBodyReg != null) release(lastTryBodyReg);
        if (priorScopeCompletionReg != null) {
            release(priorScopeCompletionReg);
        }
        priorScopeCompletionReg = savedPriorCompletion;

        // If the try body terminates (e.g. final statement is a Throw or
        // Return), skip the transfer Movs and trailing Jump — they would be
        // unreachable. LibJS does the same.
        boolean bodyTerminates = isCurrentBlockTerminated();
        if (!bodyTerminates) {
            // Transfer Movs: Mov(inner, lastBodyValue); Mov(outer, inner).
            // Fuse into Mov2 by the emit-time peephole.
            if (!(lastBodyValue instanceof Variable.Register lbr
                  && lbr.index() == innerCompletion.index())) {
                emit(new Op.Mov(innerCompletion, lastBodyValue));
            }
            emit(new Op.Mov(d.outerCompletion(), innerCompletion));
        }
        int tryBodyEnd = currentPc();

        exceptionHandlers.add(new Executable.ExceptionHandler(
            tryBodyStart, tryBodyEnd, d.handlerPc()));

        if (!bodyTerminates) {
            // Terminator: Jump → afterTry, peephole-replaced by inline End/Return
            // when after-try is exactly a single terminator op (LibJS's
            // JumpToEnd/JumpToReturn assemble-time peephole).
            int afterTryStart = d.afterTryStart();
            if (afterTryStart < ops.size()
                && ops.get(afterTryStart) instanceof Op.End endOp
                && isSingleInstructionBlock(afterTryStart)) {
                emit(new Op.End(endOp.value()));
            } else if (afterTryStart < ops.size()
                && ops.get(afterTryStart) instanceof Op.Return retOp
                && isSingleInstructionBlock(afterTryStart)) {
                emit(new Op.Return(retOp.value()));
            } else {
                emit(new Op.Jump(afterTryStart));
            }
        }

        release(innerCompletion);

        // Restore the outer loopStack we replaced before lowering the body.
        loopStack.clear();
        for (int i = savedLoopStack.size() - 1; i >= 0; i--) loopStack.push(savedLoopStack.get(i));
    }

    /**
     * True if {@code pc} is the only instruction in its basic block (i.e. the
     * block consists of exactly one terminator). Used by the deferred
     * try-body to decide whether to inline {@code End/Return} in place of
     * {@code Jump → block-with-only-End}.
     */
    private boolean isSingleInstructionBlock(int pc) {
        // Block start: pc must be in blockStartPcs.
        if (!blockStartPcs.contains(pc)) return false;
        // Block end: pc+1 is either the start of another block, or the end of ops.
        int nextPc = pc + 1;
        return nextPc == ops.size() || blockStartPcs.contains(nextPc);
    }

    /**
     * try { B } catch (e) { C } finally { F }   (all three present).
     *
     * <p>LibJS bytecode shape (verified against codegen.rs:7176+):
     * <pre>
     *   block0: prologue — GetLexicalEnvironment + Jump → tryBody
     *   block1: exception_preamble — Catch dst:exReg, SetLexEnv,
     *           Mov typeReg=Int32(1) [THROW], falls through to finalizer
     *   block2: finalizer body — emits user finalizer body, then
     *           JumpStrictlyEquals(typeReg, Int32(0), normalEnd, afterNormalCheck)
     *   block3: user catch — Catch dst:catchDst, SetLexEnv, bind eLocal,
     *           lower catch body, Mov typeReg=Int32(0) [NORMAL],
     *           Jump → finalizer
     *   block4: try body — body, Mov typeReg=Int32(0), Jump → finalizer
     *   block5: normal-end — caller continues; eventually End / Return
     *   ---deferred---
     *   block6: afterNormalCheck — JumpStrictlyEquals(typeReg, Int32(2),
     *           returnBlock, rethrowBlock)
     *   block7: returnBlock — Return exReg
     *   block8: rethrowBlock — Throw exReg
     * </pre>
     *
     * <p>Two exception ranges are installed: try body → user catch (so a
     * throw inside try is caught by the user); user catch body → preamble
     * (so a throw inside catch routes to finally with type=THROW).
     */
    private Variable.Register lowerTryCatchFinally(TryStatement ts) {
        if (!lexicalEnvironmentSaved) {
            emit(new Op.GetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
            lexicalEnvironmentSaved = true;
        }
        // typeReg holds the abrupt-completion code (0 NORMAL, 1 THROW, 2 RETURN).
        // It's allocated BEFORE the preamble so it lands at a lower index than
        // the preamble's exception register — matches LibJS's dump where
        // typeReg=reg5 and exReg=reg6 in the inner function of file 367.
        Variable.Register typeReg = allocRegister();
        int jumpToTryBodyPc = emit(new Op.Jump(/* placeholder */ -1));

        // ----- Exception preamble (auto handler) -----
        startNewBlock();
        int preamblePc = currentPc();
        Variable.Register exceptionReg = allocRegister();
        emit(new Op.Catch(exceptionReg));
        emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
        emit(new Op.Mov(typeReg, constant(1.0d)));
        // Falls through into finalizer (no Jump emitted; LibJS elides it).

        // ----- Finalizer body -----
        startNewBlock();
        int finalizerStart = currentPc();
        Variable.Register finalizerComp = allocRegister();
        emit(new Op.Mov(finalizerComp, constant(Undefined.VALUE)));
        completionRegStack.push(finalizerComp);
        try {
            for (Statement s : ts.finalizer().body()) lowerStatement(s);
        } finally {
            completionRegStack.pop();
        }
        // Dispatch on type: NORMAL → normalEnd, else → afterNormalCheck.
        // True target patched at normalEnd start; false target patched in
        // the deferred abnormal tail.
        int finalizerJumpPc = emit(new Op.JumpStrictlyEquals(
            typeReg, constant(0.0d), /* trueTarget */ -1, /* falseTarget */ -1));
        release(finalizerComp);

        // ----- User catch handler -----
        startNewBlock();
        int userCatchPc = currentPc();
        CatchClause handler = ts.handler();
        Variable.Register catchDst = allocRegister();
        emit(new Op.Catch(catchDst));
        emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
        Integer savedCatchShadow = null;
        String catchParamName = null;
        if (handler.param() != null) {
            if (handler.param() instanceof Identifier id) {
                Variable.Local exceptionLocal = freshLocal(id.name());
                emit(new Op.Mov(exceptionLocal, catchDst));
                catchParamName = id.name();
                savedCatchShadow = locals.get(catchParamName);
                locals.put(catchParamName, exceptionLocal.slot());
            } else if (handler.param() instanceof ArrayPattern
                || handler.param() instanceof ObjectPattern) {
                bindPattern(handler.param(), catchDst, BindMode.LOCAL);
            } else {
                throw new UnsupportedOperationException(
                    "Generator: catch param shape not supported: "
                    + handler.param().getClass().getSimpleName());
            }
        }
        // Set type=NORMAL before running catch body — fuses with prior Movs
        // into a Mov2/Mov3.
        emit(new Op.Mov(typeReg, constant(0.0d)));
        completionRegStack.push(catchDst); // placeholder; catch body's completion isn't really tracked here
        try {
            for (Statement s : handler.body().body()) lowerStatement(s);
        } finally {
            completionRegStack.pop();
        }
        if (catchParamName != null) {
            if (savedCatchShadow != null) locals.put(catchParamName, savedCatchShadow);
            else locals.remove(catchParamName);
        }
        int userCatchEnd = currentPc();
        emit(new Op.Jump(finalizerStart));
        // The user catch body's PC range routes throws back to the preamble
        // (which sets type=THROW and falls through to finalizer).
        exceptionHandlers.add(new Executable.ExceptionHandler(userCatchPc, userCatchEnd, preamblePc));
        release(catchDst);

        // ----- Try body -----
        startNewBlock();
        int tryBodyStart = currentPc();
        patchJumpTarget(jumpToTryBodyPc, tryBodyStart);
        Variable.Register innerCompletion = allocRegister();
        emit(new Op.Mov(innerCompletion, constant(Undefined.VALUE)));
        completionRegStack.push(innerCompletion);
        try {
            for (Statement s : ts.block().body()) lowerStatement(s);
        } finally {
            completionRegStack.pop();
        }
        // If try body falls through (no terminator), set type=NORMAL and Jump to finalizer.
        if (!isCurrentBlockTerminated()) {
            emit(new Op.Mov(typeReg, constant(0.0d)));
            emit(new Op.Jump(finalizerStart));
        }
        int tryBodyEnd = currentPc();
        // Try body's PC range routes throws to the user catch handler.
        exceptionHandlers.add(new Executable.ExceptionHandler(tryBodyStart, tryBodyEnd, userCatchPc));
        release(innerCompletion);

        // ----- Normal-end block (caller continues here) -----
        startNewBlock();
        int normalEndStart = currentPc();
        Op.JumpStrictlyEquals fjmp = (Op.JumpStrictlyEquals) ops.get(finalizerJumpPc);
        ops.set(finalizerJumpPc, new Op.JumpStrictlyEquals(
            fjmp.lhs(), fjmp.rhs(), normalEndStart, /* falseTarget */ -1));

        // Defer the abnormal tail (afterNormalCheck/return/rethrow).
        deferredLoopBranches.add(new DeferredTryFinallyTail(typeReg, exceptionReg, finalizerJumpPc));

        // The try-catch-finally's value: in LibJS it's a fresh register, but
        // since the test 367 doesn't consume it (function body level), return
        // typeReg as a placeholder. Callers using this value are expected to
        // be at function/script body level where the End/Return doesn't read it
        // (it reads completion via the normal flow's End-Undefined).
        return typeReg;
    }

    /**
     * True if the last emitted op is an unconditional terminator (Jump,
     * Throw, Return, End). Used by lowerTryCatchFinally to decide whether
     * to emit the "Mov type=NORMAL; Jump finalizer" tail at the end of try body.
     */
    private boolean isCurrentBlockTerminated() {
        if (ops.isEmpty()) return false;
        Op last = ops.get(ops.size() - 1);
        return last instanceof Op.Jump
            || last instanceof Op.Throw
            || last instanceof Op.Return
            || last instanceof Op.End;
    }

    /**
     * Allocate a fresh local slot, even if a prior local with this name exists.
     * Used for catch params: each catch's binding gets its own slot, matching
     * LibJS's {@code e~0, e~1, ...} numbering when multiple catches share a name.
     */
    private Variable.Local freshLocal(String name) {
        int slot = localNames.size();
        localNames.add(name);
        // Note: don't update {@link #locals} — that map is for name lookups
        // and must point to the OLDER binding (if any) for outside-catch reads.
        return new Variable.Local(slot);
    }

    /**
     * switch (disc) { case A: ...; case B: ...; default: ...; }
     *
     * <p>Lowered as a chain of strict-equality tests with explicit fallthrough
     * (cases share the post-case label sequence). {@code break} inside cases
     * jumps to the post-switch position via the standard loop-break mechanism.
     */
    private void lowerSwitch(SwitchStatement ss) {
        Operand disc = lowerExpression(ss.discriminant());
        // Stash discriminant in a register UNLESS it's a constant — LibJS
        // inlines literal discriminants directly into JumpStrictlyEquals
        // without an extra Mov.
        Operand discOp;
        if (disc instanceof Variable.Register
            || disc instanceof Operand.Constant) {
            discOp = disc;
        } else {
            Variable.Register discReg = allocRegister();
            emit(new Op.Mov(discReg, disc));
            release(disc);
            discOp = discReg;
        }
        // Start a fresh block for the switch dispatch — LibJS puts the
        // dispatch in its own block (separate from the disc-evaluation
        // block). When disc is a constant, the dispatch starts a new block
        // immediately. Otherwise it follows the Mov.
        startNewBlock();

        // Use the loop-break mechanism for `break` inside case bodies.
        LoopContext breakCtx = new LoopContext();
        loopStack.push(breakCtx);

        // Phase 1: emit comparison cascade. Each case's test is a `case X:`
        // strict-eq check that jumps to the case body if it matches.
        // Default (if present) is a fallback after all explicit tests fail.
        int[] caseBodyJumps = new int[ss.cases().size()];   // placeholders pointing into Phase 2
        int defaultIdx = -1;
        for (int i = 0; i < ss.cases().size(); i++) {
            SwitchCase c = ss.cases().get(i);
            if (c.test() == null) {
                defaultIdx = i;
                caseBodyJumps[i] = -1;   // patched after Phase 2 lays out bodies
                continue;
            }
            Operand testVal = lowerExpression(c.test());
            // Fused JumpStrictlyEquals matches LibJS's switch-case dispatch.
            // The fall-through (false-target) goes to the next test (or
            // default/jumpOver); we record the placeholder to patch after
            // emitting the next instruction.
            caseBodyJumps[i] = emit(new Op.JumpStrictlyEquals(
                discOp, testVal, /* trueTarget */ -1, /* falseTarget */ -1));
            // Patch the false_target to fall through to the immediately-next
            // instruction (next test, or jumpOver/jumpToDefault). The
            // start-of-next-block marker is set so the disassembler renders
            // the false-target as a separate block — matches LibJS layout.
            startNewBlock();
            Op.JumpStrictlyEquals jse = (Op.JumpStrictlyEquals) ops.get(caseBodyJumps[i]);
            ops.set(caseBodyJumps[i], new Op.JumpStrictlyEquals(
                jse.lhs(), jse.rhs(), jse.trueTargetPc(), currentPc()));
            release(testVal);
        }
        // If no test matched and there's a default, jump to it. Skip the
        // Jump when no real cases were emitted — the default body is the
        // immediately-following block in PC order, so fall-through suffices
        // (LibJS does the same).
        int jumpToDefault = -1;
        boolean anyRealCase = false;
        for (int i = 0; i < ss.cases().size(); i++) {
            if (ss.cases().get(i).test() != null) { anyRealCase = true; break; }
        }
        if (defaultIdx >= 0 && anyRealCase) {
            jumpToDefault = emit(new Op.Jump(/* placeholder */ -1));
        }
        // Otherwise, jump past the switch entirely.
        int jumpOverEverything = (defaultIdx < 0) ? emit(new Op.Jump(-1)) : -1;

        // Phase 2: emit case bodies in order; each falls through to the next.
        startNewBlock();
        int[] caseBodyStarts = new int[ss.cases().size()];
        for (int i = 0; i < ss.cases().size(); i++) {
            SwitchCase c = ss.cases().get(i);
            caseBodyStarts[i] = currentPc();
            for (Statement s : c.consequent()) lowerStatement(s);
        }

        // After all bodies — this is the post-switch position.
        startNewBlock();
        int afterPc = currentPc();

        // Patch jumps from Phase 1 to their corresponding case-body starts.
        // For fused JumpStrictlyEquals, the trueTarget is patched (the case
        // body). The falseTarget was already set inline to the next test's
        // PC during Phase 1.
        for (int i = 0; i < caseBodyJumps.length; i++) {
            if (caseBodyJumps[i] >= 0) {
                Op op = ops.get(caseBodyJumps[i]);
                if (op instanceof Op.JumpStrictlyEquals jse) {
                    int target = caseBodyStarts[i];
                    if (!blockStartPcs.contains(target)) {
                        int insertAt = 0;
                        while (insertAt < blockStartPcs.size() && blockStartPcs.get(insertAt) < target) insertAt++;
                        blockStartPcs.add(insertAt, target);
                    }
                    ops.set(caseBodyJumps[i], new Op.JumpStrictlyEquals(
                        jse.lhs(), jse.rhs(), target, jse.falseTargetPc()));
                } else {
                    patchJumpTarget(caseBodyJumps[i], caseBodyStarts[i]);
                }
            }
        }
        if (jumpToDefault >= 0) patchJumpTarget(jumpToDefault, caseBodyStarts[defaultIdx]);
        if (jumpOverEverything >= 0) patchJumpTarget(jumpOverEverything, afterPc);

        // Patch break statements to land here.
        loopStack.pop();
        for (int p : breakCtx.pendingBreakPcs) patchJumpTarget(p, afterPc);
        for (int p : breakCtx.pendingContinuePcs) {
            throw new UnsupportedOperationException("Generator: continue inside switch is not supported here");
        }
    }

    /**
     * for-of and for-in: lowered as
     *   <pre>{@code
     *   let __keys = (forIn ? KeysOf : IteratorToArray)(right);
     *   let __i = 0;
     *   while (__i < __keys.length) {
     *     LEFT = __keys[__i];
     *     body
     *     __i = __i + 1;
     *   }
     *   }</pre>
     */
    private void lowerForEach(Node left, Expression right, Statement body, boolean forIn) {
        lowerForEachReturning(left, right, body, forIn);
    }

    private Operand lowerForEachReturning(Node left, Expression right, Statement body, boolean forIn) {
        if (!forIn) {
            return lowerForOfStatement(left, right, body);
        }
        Operand src = lowerExpression(right);
        Variable.Register itemsArr = allocRegister();
        emit(new Op.KeysOf(itemsArr, src));
        release(src);

        Variable.Register iReg = allocRegister();
        emit(new Op.Mov(iReg, constant(0.0)));

        Variable.Register completionReg = allocRegister();
        emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));

        startNewBlock();
        int loopHead = currentPc();
        // Test: __i < itemsArr.length
        Variable.Register lengthReg = allocRegister();
        emit(new Op.GetById(lengthReg, itemsArr, "length", null,
            new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
        int jumpFalsePc = emit(new Op.JumpLessThan(iReg, lengthReg, currentPc() + 1, -1));
        release(lengthReg);

        startNewBlock();   // body
        // Bind LEFT to itemsArr[__i].
        Variable.Register elem = allocRegister();
        emit(new Op.GetByValue(elem, itemsArr, iReg, null));
        if (left instanceof VariableDeclaration vd) {
            // single declarator with no init.
            for (VariableDeclarator d : vd.declarations()) {
                bindPattern(d.id(), elem, BindMode.LOCAL);
            }
        } else if (left instanceof Pattern p) {
            bindPattern(p, elem, BindMode.ASSIGN);
        } else if (left instanceof Expression e) {
            bindPattern(e, elem, BindMode.ASSIGN);
        } else {
            throw new UnsupportedOperationException(
                "Generator: for-of/in left has unexpected shape: " + left.getClass().getSimpleName());
        }
        release(elem);

        LoopContext loop = new LoopContext();
        attachPendingLabel(loop);
        loopStack.push(loop);
        Variable.Register loopAnchor = allocFreshRegister();
        completionRegStack.push(completionReg);
        try {
            lowerStatement(body);
        } finally {
            completionRegStack.pop();
        }

        // continue lands here — at the increment.
        loop.continueTargetPc = currentPc();
        // __i = __i + 1
        emit(new Op.Increment(iReg));
        emit(new Op.Jump(loopHead));
        release(loopAnchor);

        loopStack.pop();
        for (int p : loop.pendingContinuePcs) patchJumpTarget(p, loop.continueTargetPc);

        startNewBlock();
        patchJumpTarget(jumpFalsePc, currentPc());
        for (int p : loop.pendingBreakPcs) patchJumpTarget(p, currentPc());

        release(itemsArr);
        release(iReg);
        // completionReg intentionally not released (matches while/for convention)
        return completionReg;
    }

    /**
     * For-of statement using LibJS's iterator-protocol layout
     * (codegen.rs:6330+):
     *   block0 (entry, inline): GetLex; RHS; GetIterator; Mov completion=Undef;
     *                           Jump → update.
     *   block1 (end, inline): subsequent stmts and the script's End emit here.
     *   --- deferred (emitted after script/function End) ---
     *   block2 (update): IteratorNextUnpack; JumpIf done → end, else body.
     *   block3 (catch_preamble): Catch; SetLexEnv; Mov typeReg = Int32(1).
     *   block4 (dispatch_throw): JumpStrictlyEquals typeReg, 1,
     *                            throw_close, normal_close.
     *   block5 (body): bind LHS = value; body stmts; Jump → update.
     *   block6 (throw_close): IteratorClose with completion=ex; Throw ex.
     *   block7 (normal_close): IteratorClose with completion=Undef;
     *                          JumpStrictlyEquals typeReg, 2, return, throw.
     *   block8 (return_block): Return ex.
     *   block9 (throw_block): Throw ex.
     *   Exception handler: body PCs → catch_preamble.
     */
    private Variable.Register lowerForOfStatement(Node left, Expression right, Statement body) {
        // 1. Save lex env (needed for SetLexicalEnvironment in catch preamble).
        if (!lexicalEnvironmentSaved) {
            emit(new Op.GetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
            lexicalEnvironmentSaved = true;
        }

        // 2. Lower RHS (the iterable).
        Operand src = lowerExpression(right);

        // 3. GetIterator: allocate and emit.
        Variable.Register iter = allocRegister();
        Variable.Register next = allocRegister();
        Variable.Register doneIter = allocRegister();
        emit(new Op.GetIterator(iter, next, doneIter, src));
        release(src);

        // 4. Allocate the script-completion register and init to Undefined.
        Variable.Register completionReg = allocRegister();
        emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));

        // 5. typeReg + exReg for abrupt-completion handling.
        Variable.Register typeReg = allocRegister();
        Variable.Register exReg = allocRegister();

        // Pre-register LHS-pattern defaults (e.g. for `[a = 10, b = 11]` LHS),
        // then body literals — matches LibJS's source-order constant interning
        // since destructuring runs at body start and adds its default
        // initializer constants BEFORE body statements run.
        preRegisterPatternDefaults(left);
        preRegisterStatementLiterals(body);
        // Pre-register the typeReg sentinel values (Int32(1) for THROW,
        // Int32(2) for RETURN) — these constants are emitted in the deferred
        // catch_preamble and dispatch blocks, but LibJS adds them to the pool
        // during the main for-of codegen pass (before end_block fills).
        constant(1.0d);
        constant(2.0d);

        // 6. Jump to update (placeholder, patched by finishDeferredForOf).
        int jumpToUpdatePc = emit(new Op.Jump(/* placeholder */ -1));

        // Snapshot allocator BEFORE releasing the iter/typeReg/exReg slots —
        // the deferred body must see them as still-alive so its allocations
        // don't clobber iter/next/done at runtime.
        int snapshotNext = nextRegister;
        java.util.List<Integer> snapshotPool = new ArrayList<>(freePool);

        // Now release the iter/type/ex slots so subsequent statements (which
        // emit into end_block right after this method returns) can reuse them
        // — matches LibJS where for-of's locals drop at fn exit BEFORE the
        // caller emits anything new in end_block. Release order matters for
        // LIFO pool ordering: exReg before typeReg (so typeReg lands closer
        // to head, matching LibJS's drop order — ScopedOperand declared in
        // order typeReg, exReg drops in reverse).
        release(exReg);
        release(typeReg);
        release(doneIter);
        release(next);
        release(iter);

        // 7. Start end_block — caller's subsequent statements (and the
        // script's End) emit here.
        startNewBlock();
        int endBlockStart = currentPc();

        // 8. Defer the rest (update + handlers + body + close blocks) so they
        // emit AFTER any subsequent statements + the script's End.
        deferredLoopBranches.add(new DeferredForOf(
            left, body, iter, next, doneIter,
            completionReg, typeReg, exReg,
            jumpToUpdatePc, endBlockStart,
            snapshotNext, snapshotPool,
            new ArrayList<>(loopStack)));
        return completionReg;
    }

    private void finishDeferredForOf(DeferredForOf d) {
        // Restore allocator state to post-entry (so deferred allocations cycle
        // through the same registers LibJS would).
        nextRegister = d.snapshotNextRegister();
        freePool.clear();
        java.util.List<Integer> snap = d.snapshotFreePool();
        for (int i = snap.size() - 1; i >= 0; i--) freePool.push(snap.get(i));

        // Restore loopStack so break/continue inside body resolve correctly.
        java.util.List<LoopContext> savedLoopStack = new ArrayList<>(loopStack);
        loopStack.clear();
        java.util.List<LoopContext> snapStack = d.snapshotLoopStack();
        for (int i = snapStack.size() - 1; i >= 0; i--) loopStack.push(snapStack.get(i));

        // ---- block2 (update) ----
        startNewBlock();
        int updateStart = currentPc();
        patchJumpTarget(d.jumpToUpdatePc(), updateStart);
        Variable.Register valueReg = allocRegister();
        Variable.Register doneFlag = allocRegister();
        emit(new Op.IteratorNextUnpack(valueReg, doneFlag,
            d.iter(), d.next(), d.doneIter()));
        int jumpIfDonePc = emit(new Op.JumpIf(doneFlag,
            /* trueTarget=end */ d.endBlockStart(),
            /* falseTarget=body */ -1));   // patched after body block starts

        // ---- block3 (catch_preamble) ----
        startNewBlock();
        int catchPc = currentPc();
        emit(new Op.Catch(d.exReg()));
        emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
        emit(new Op.Mov(d.typeReg(), constant(1.0d)));
        // Falls through into dispatch.

        // ---- block4 (dispatch_throw) ----
        startNewBlock();
        int dispatchThrowPc = emit(new Op.JumpStrictlyEquals(
            d.typeReg(), constant(1.0d),
            /* trueTarget=throw_close */ -1,
            /* falseTarget=normal_close */ -1));

        // ---- block5 (body) ----
        startNewBlock();
        int bodyStart = currentPc();
        // Patch update's JumpIf falseTarget = body.
        Op old = ops.get(jumpIfDonePc);
        if (!(old instanceof Op.JumpIf oldJif)) {
            throw new IllegalStateException("expected JumpIf at jumpIfDonePc");
        }
        ops.set(jumpIfDonePc, new Op.JumpIf(oldJif.condition(), oldJif.trueTargetPc(), bodyStart));

        // Bind LHS = value. For `var` LHS, the binding was hoisted (global at
        // top level, local in functions). Use ASSIGN so SetGlobal / Mov-to-
        // local fires through the same write-back path. For `let`/`const`,
        // BindMode.GLOBAL emits InitializeLexicalBinding (per-iteration).
        Node lhs = d.lhs();
        if (lhs instanceof VariableDeclaration vd) {
            BindMode mode = "var".equals(vd.kind()) ? BindMode.ASSIGN : BindMode.GLOBAL;
            for (VariableDeclarator decl : vd.declarations()) {
                bindPattern(decl.id(), valueReg, mode);
            }
        } else if (lhs instanceof Pattern p) {
            bindPattern(p, valueReg, BindMode.ASSIGN);
        } else if (lhs instanceof Expression e) {
            bindPattern(e, valueReg, BindMode.ASSIGN);
        } else {
            throw new UnsupportedOperationException(
                "Generator: for-of LHS shape: " + lhs.getClass().getSimpleName());
        }
        // valueReg and doneFlag are kept alive through the body — LibJS
        // treats them as scoped to the for-of's outer scope, dropping at
        // the end of the for-of codegen. Body's allocations skip past them.

        // Lower body. Set up loop context for break/continue.
        LoopContext loop = new LoopContext();
        loop.continueTargetPc = updateStart;
        loop.completionRegister = d.completionReg();
        attachPendingLabel(loop);
        loopStack.push(loop);
        completionRegStack.push(d.completionReg());
        try {
            lowerStatement(d.body());
        } finally {
            completionRegStack.pop();
            loopStack.pop();
        }
        // Body terminates with Jump → update.
        emit(new Op.Jump(updateStart));
        int bodyEnd = currentPc();
        // Patch break/continue placeholders.
        for (int p : loop.pendingContinuePcs) patchJumpTarget(p, updateStart);
        // Note: break inside for-of body should ideally route through the
        // type-dispatcher (typeReg=BREAK), but for v1 we route directly to
        // end_block. This is observably correct for non-iterator-yielding
        // sources but skips IteratorClose on break — TODO.
        for (int p : loop.pendingBreakPcs) patchJumpTarget(p, d.endBlockStart());

        // Install exception handler: body PC range → catch_preamble.
        exceptionHandlers.add(new Executable.ExceptionHandler(bodyStart, bodyEnd, catchPc));

        // Drain any DeferredIteratorClose entries the body queued (e.g. from a
        // destructuring LHS like `for ([x = 1] of ...)`). LibJS allocates the
        // destructuring's done/not_done blocks inline during body codegen
        // (codegen.rs:7068-7080), BEFORE the for-of's throw_close /
        // non_throw_close / return / throw blocks (codegen.rs:6540+, 6618+).
        // Mirror by flushing inner DeferredIteratorClose now so its block
        // numbers land here, before the for-of trailing blocks below.
        java.util.Iterator<DeferredLoopBranch> it = deferredLoopBranches.iterator();
        while (it.hasNext()) {
            DeferredLoopBranch db = it.next();
            if (db instanceof DeferredIteratorClose ic) {
                finishDeferredIteratorClose(ic);
                it.remove();
            }
        }

        // ---- block6 (throw_close) ----
        startNewBlock();
        int throwClosePc = currentPc();
        emit(new Op.IteratorClose(d.iter(), d.next(), d.doneIter(), d.exReg()));
        emit(new Op.Throw(d.exReg()));

        // ---- block7 (normal_close) ----
        startNewBlock();
        int normalClosePc = currentPc();
        emit(new Op.IteratorClose(d.iter(), d.next(), d.doneIter(), constant(Undefined.VALUE)));
        int dispatchReturnPc = emit(new Op.JumpStrictlyEquals(
            d.typeReg(), constant(2.0d),
            /* trueTarget=return_block */ -1,
            /* falseTarget=throw_block */ -1));

        // Patch dispatch_throw to throw_close + normal_close PCs.
        Op oldDispatch = ops.get(dispatchThrowPc);
        if (!(oldDispatch instanceof Op.JumpStrictlyEquals oldJse)) {
            throw new IllegalStateException("expected JumpStrictlyEquals at dispatchThrowPc");
        }
        ops.set(dispatchThrowPc, new Op.JumpStrictlyEquals(
            oldJse.lhs(), oldJse.rhs(), throwClosePc, normalClosePc));

        // ---- block8 (return_block) ----
        startNewBlock();
        int returnPc = currentPc();
        emit(new Op.Return(d.exReg()));

        // ---- block9 (throw_block) ----
        startNewBlock();
        int throwPc = currentPc();
        emit(new Op.Throw(d.exReg()));

        // Patch normal_close's JumpStrictlyEquals to return + throw.
        Op oldDispatchReturn = ops.get(dispatchReturnPc);
        if (!(oldDispatchReturn instanceof Op.JumpStrictlyEquals oldJse2)) {
            throw new IllegalStateException("expected JumpStrictlyEquals at dispatchReturnPc");
        }
        ops.set(dispatchReturnPc, new Op.JumpStrictlyEquals(
            oldJse2.lhs(), oldJse2.rhs(), returnPc, throwPc));

        // iter/next/done/typeReg/exReg were already released into the pool
        // back in lowerForOfStatement (so end_block's emission could reuse
        // those slots). Don't release them again. valueReg / doneFlag are
        // dropped at end of for-of scope.

        // Restore the outer loopStack we replaced.
        loopStack.clear();
        for (int i = savedLoopStack.size() - 1; i >= 0; i--) loopStack.push(savedLoopStack.get(i));
    }

    private Variable.Register lowerWhileReturning(WhileStatement ws) {
        // Const-fold while(false): body never executes, the loop reduces to
        // just the completion register init + a separate post-loop block (LibJS
        // still emits the loop-test block boundary even when folded). The
        // literal still goes into the constants pool — LibJS pre-registers it
        // at parse time.
        if (ws.test() instanceof Literal lit) {
            Object v = literalValue(lit);
            boolean foldable = v == null
                || v instanceof Boolean || v instanceof Integer
                || v instanceof Long || v instanceof Double || v instanceof String;
            if (foldable && !AbstractOps.toBoolean(v)) {
                Variable.Register completionReg = allocRegister();
                emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));
                // Pre-register the literal in the pool (LibJS does so during
                // parse, before realizing it's dead).
                constant(v);
                // Force a block boundary so the post-loop appears in its own
                // block — matches LibJS's structure (two blocks, second has
                // just End).
                startNewBlock();
                return completionReg;
            }
        }

        Variable.Register completionReg = allocRegister();
        emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));

        // Loop-test block: re-entered each iteration.
        startNewBlock();
        int loopHead = currentPc();
        int jumpFalsePc = emitLoopConditionalBranch(ws.test());

        // Push break/continue context. continue → loop head (re-test); break → after-loop.
        LoopContext loop = new LoopContext();
        loop.continueTargetPc = loopHead;
        attachPendingLabel(loop);
        loopStack.push(loop);

        // Body block.
        startNewBlock();

        // Reserve a fresh register slot held across the entire body. The body's
        // allocations skip past it; releasing it at body-end puts it on top of
        // the free pool, where the post-loop allocator pops it first. This
        // mirrors LibJS's per-loop-body register reservation — the allocated
        // slot is never written or read, but its presence lifts the body's
        // register numbering above the post-loop's so post-loop reads come
        // from a lower-numbered register (as if pre-allocated for it).
        //
        // When the body is non-allocating (empty / pure break-continue / dead
        // after unconditional terminator), skip the anchor — LibJS's
        // pre-allocated test-result register effectively coincides with the
        // test's own dst in that case, so an extra slot here just leaks into
        // the register-file high-water mark.
        boolean useAnchor = !isNonAllocatingStatement(ws.body());
        Variable.Register loopAnchor = useAnchor ? allocFreshRegister() : null;

        completionRegStack.push(completionReg);
        try {
            lowerStatement(ws.body());
        } finally {
            completionRegStack.pop();
        }

        emit(new Op.Jump(loopHead));

        // Release the anchor: enters the pool here so the after-loop block
        // can pop it for its first allocation.
        if (loopAnchor != null) release(loopAnchor);

        // Pop loop context and patch break/continue targets.
        loopStack.pop();
        // Continue jumps go to loopHead.
        for (int p : loop.pendingContinuePcs) patchJumpTarget(p, loopHead);

        // After-loop block (also where breaks land).
        startNewBlock();
        patchJumpTarget(jumpFalsePc, currentPc());
        for (int p : loop.pendingBreakPcs) patchJumpTarget(p, currentPc());
        return completionReg;
    }

    private void lowerWhile(WhileStatement ws) {
        lowerWhileReturning(ws);
    }

    /**
     * If the do-while's test is a "result-producing" expression that LibJS
     * lowers as a single op writing into a register (e.g. an Identifier
     * resolving to a global), allocate the result register UP-FRONT before
     * lowering the body. This mirrors LibJS's allocation-order: the test's
     * ScopedOperand is constructed first (in the test block) and stays alive
     * across body codegen, so body allocations skip past the test's slot.
     *
     * <p>Returns null when no pre-allocation applies — falsy literals (no
     * cond op emitted), fusable comparisons (the fused op reads operands
     * directly, no result reg), and currently-unsupported complex tests
     * (Call, Binary-non-comparison, etc., which would require {@code
     * preferred_dst} threading through {@link #lowerExpression}).
     */
    private Variable.Register preAllocateDoWhileTestResultReg(Expression test) {
        if (isFalsyLiteral(test)) return null;
        if (test instanceof BinaryExpression bin && isFusableComparison(bin.operator())) return null;
        // Pre-alloc for plain global-identifier tests — we know we'll emit
        // exactly one GetGlobal whose dst can be the pre-allocated register.
        if (test instanceof Identifier id && resolvesToGlobal(id)) {
            return allocRegister();
        }
        return null;
    }

    /**
     * True iff this Identifier doesn't resolve to a parameter, a captured
     * outer local, or a current-scope local — i.e. it's a free identifier
     * that {@link #lowerExpression} would lower as {@code GetGlobal}.
     */
    private boolean resolvesToGlobal(Identifier id) {
        String name = id.name();
        if ("undefined".equals(name) || "NaN".equals(name) || "Infinity".equals(name)) return false;
        if (params.containsKey(name)) return false;
        if (captureSlot.containsKey(name)) return false;
        if (locals.containsKey(name)) return false;
        return true;
    }

    /**
     * Emit the cond block's test+jump pair, optionally using a pre-allocated
     * result register. If {@code preAllocedResultReg} is non-null, the test
     * is emitted as a single op writing into it. Otherwise falls back to the
     * regular {@link #emitForLoopCondAtTail} path. Returns the PC to patch
     * with the false-target (for fused branches), or -1 if no patch is needed.
     */
    private int emitDoWhileCondJump(Expression test, Variable.Register preAllocedResultReg, int bodyStart) {
        if (isFalsyLiteral(test)) return -1;
        if (preAllocedResultReg != null && test instanceof Identifier id && resolvesToGlobal(id)) {
            emit(new Op.GetGlobal(preAllocedResultReg, id.name(),
                new com.jimmyhmiller.harmonica.bytecode.cache.GlobalVariableCache()));
            emit(new Op.JumpTrue(preAllocedResultReg, bodyStart));
            return -1;
        }
        return emitForLoopCondAtTail(test, bodyStart);
    }

    /**
     * Extract the single {@link DoWhileStatement} from a body, or {@code null}
     * if the body isn't a single nested do-while. Accepts both bare
     * {@code do-while} and a {@link BlockStatement} wrapping one.
     */
    private DoWhileStatement extractNestedDoWhile(Statement body) {
        if (body instanceof DoWhileStatement dws) return dws;
        if (body instanceof BlockStatement bs && bs.body().size() == 1
            && bs.body().get(0) instanceof DoWhileStatement dws) {
            return dws;
        }
        return null;
    }

    /**
     * Lower a do-while whose body is itself a do-while. LibJS lays out:
     * <pre>
     *   prologue:    Mov(outerComp, Undef)               (in caller's block)
     *   outer-body:  Mov(innerComp, Undef); Jump → inner-body
     *   outer-cond:  &lt;test&gt;; JumpTrue → outer-body
     *   after:       (caller continues)
     *   ---deferred (after script's End)---
     *   inner-body:  &lt;recursive&gt;
     *   inner-cond:  &lt;test&gt;; JumpTrue → inner-body
     *   merge:       Mov(outerComp, innerComp); Jump → outer-cond
     * </pre>
     * Recurses for arbitrarily-deep nesting via {@link DeferredDoWhileBody}.
     */
    private Variable.Register lowerDoWhileWithNestedBody(DoWhileStatement outer, DoWhileStatement inner) {
        // Pre-allocate ALL nested do-while registers up front so the
        // allocator order matches LibJS's inline-recursive emission — even
        // though our actual op emission is deferred. After allocation we
        // release them so the caller's pool reflects post-loop state; the
        // deferred ops reference the registers by slot index, which stays
        // valid because (a) the slots are owned by the loop while it runs
        // and (b) the caller's code only executes after the loop exits.
        Variable.Register outerComp = allocRegister();
        emit(new Op.Mov(outerComp, constant(Undefined.VALUE)));

        preRegisterLiterals(outer.test());

        startNewBlock();
        int bodyBlockStart = currentPc();
        LoopContext loop = new LoopContext();
        attachPendingLabel(loop);
        loopStack.push(loop);

        // Allocation order: outer's test reg, then recursively each nested
        // level's (myComp, myTestReg). Empirically matches LibJS — see file
        // 365 where allocations land at reg7 (outer1Test), reg6 (middleComp),
        // reg8 (middleTest), reg9 (inner3Comp), reg10 (inner3Test).
        Variable.Register outerTestReg = preAllocateDoWhileTestResultReg(outer.test());
        DeferredDoWhileBody nested = preAllocateNestedDoWhile(inner, outerComp);
        Variable.Register innerComp = nested.innerComp();

        emit(new Op.Mov(innerComp, constant(Undefined.VALUE)));
        int bodyJumpPc = emit(new Op.Jump(/* placeholder */ -1));

        startNewBlock();
        int condStart = currentPc();
        loop.continueTargetPc = condStart;
        for (int p : loop.pendingContinuePcs) patchJumpTarget(p, condStart);

        int condFalsePc = emitDoWhileCondJump(outer.test(), outerTestReg, bodyBlockStart);

        startNewBlock();
        int afterPc = currentPc();
        if (condFalsePc >= 0) patchJumpTarget(condFalsePc, afterPc);
        loop.breakTargetPc = afterPc;
        for (int p : loop.pendingBreakPcs) patchJumpTarget(p, afterPc);
        loopStack.pop();

        // Release all the pre-allocated nested regs so the caller's pool
        // matches LibJS's post-do-while state. Deepest-first: each level's
        // testReg pushed before its innerComp; outermost level's testReg
        // released last (lands on top of pool).
        releaseNestedDoWhileRegs(nested);
        if (outerTestReg != null) release(outerTestReg);

        // Defer middle's (= the immediate inner's) record with the just-
        // determined PCs. The record was created with -1 placeholders by
        // preAllocateNestedDoWhile; we replace them now.
        DeferredDoWhileBody middleRecord = new DeferredDoWhileBody(
            nested.innerDws(),
            nested.innerComp(),
            nested.testReg(),
            nested.outerComp(),
            condStart,
            bodyJumpPc,
            nested.nestedChild());
        deferredLoopBranches.add(middleRecord);

        return outerComp;
    }

    /**
     * Recursively pre-allocate registers for a nested do-while chain.
     * For each level: alloc {@code myComp}, then alloc {@code myTestReg},
     * then recurse for any deeper nesting. Returns the deferred record
     * for this level with PCs set to -1 (to be filled in at emission time).
     *
     * <p>{@code parentComp} is the COMP register of this level's
     * surrounding parent — stored as {@code outerComp} in the record so
     * the merge block can write {@code Mov(parentComp, myComp)}.
     */
    private DeferredDoWhileBody preAllocateNestedDoWhile(DoWhileStatement dws, Variable.Register parentComp) {
        Variable.Register myComp = allocRegister();
        Variable.Register myTestReg = preAllocateDoWhileTestResultReg(dws.test());
        DoWhileStatement nestedInner = extractNestedDoWhile(dws.body());
        DeferredDoWhileBody deeper = nestedInner != null
            ? preAllocateNestedDoWhile(nestedInner, myComp)
            : null;
        return new DeferredDoWhileBody(
            dws, myComp, myTestReg, parentComp,
            /*outerCondStartPc*/ -1, /*bodyJumpPc*/ -1, deeper);
    }

    /**
     * Release pre-allocated nested-do-while registers in deepest-first order.
     * Each level's testReg is pushed before its myComp; the outermost level's
     * registers end up on top of the LIFO pool — matches LibJS's release
     * order from RAII-style ScopedOperand drops at function-return time.
     */
    private void releaseNestedDoWhileRegs(DeferredDoWhileBody d) {
        if (d == null) return;
        releaseNestedDoWhileRegs(d.nestedChild());
        if (d.testReg() != null) release(d.testReg());
        if (d.innerComp() != null) release(d.innerComp());
    }

    /**
     * Emit the deferred body+cond+merge for a nested do-while level. Uses
     * the pre-allocated registers stored in the record (no further
     * allocations). If the level has a {@code nestedChild}, queues that
     * child for emission via the same flush loop.
     */
    private void finishDeferredDoWhileBody(DeferredDoWhileBody d) {
        // This level's body block.
        startNewBlock();
        int myBodyStart = currentPc();
        patchJumpTarget(d.bodyJumpPc(), myBodyStart);

        LoopContext loop = new LoopContext();
        loopStack.push(loop);
        completionRegStack.push(d.innerComp());

        int childBodyJumpPc = -1;
        if (d.nestedChild() != null) {
            // Body is itself a do-while — emit the deeper level's prologue
            // (Mov + Jump-placeholder), reusing the pre-allocated register.
            emit(new Op.Mov(d.nestedChild().innerComp(), constant(Undefined.VALUE)));
            childBodyJumpPc = emit(new Op.Jump(/* placeholder */ -1));
        } else {
            // Leaf body — lower normally.
            lowerStatement(d.innerDws().body());
        }
        completionRegStack.pop();

        // This level's cond block.
        startNewBlock();
        int myCondStart = currentPc();
        loop.continueTargetPc = myCondStart;
        for (int p : loop.pendingContinuePcs) patchJumpTarget(p, myCondStart);

        preRegisterLiterals(d.innerDws().test());
        int condFalsePc = emitDoWhileCondJump(d.innerDws().test(), d.testReg(), myBodyStart);

        // Merge block: transfer this level's completion to parent's, then
        // jump back to the parent's cond start so the parent re-tests.
        startNewBlock();
        int mergePc = currentPc();
        if (condFalsePc >= 0) patchJumpTarget(condFalsePc, mergePc);
        loop.breakTargetPc = mergePc;
        for (int p : loop.pendingBreakPcs) patchJumpTarget(p, mergePc);

        emit(new Op.Mov(d.outerComp(), d.innerComp()));
        emit(new Op.Jump(d.outerCondStartPc()));

        loopStack.pop();

        if (d.nestedChild() != null) {
            // Queue the deeper level with the just-emitted PCs filled in.
            DeferredDoWhileBody deeperWithPcs = new DeferredDoWhileBody(
                d.nestedChild().innerDws(),
                d.nestedChild().innerComp(),
                d.nestedChild().testReg(),
                d.nestedChild().outerComp(),
                myCondStart,
                childBodyJumpPc,
                d.nestedChild().nestedChild());
            deferredLoopBranches.add(deeperWithPcs);
        }
    }

    /**
     * Do-while: body runs first, then test. LibJS layout:
     * <pre>
     *   block0: completion init
     *   block1: body
     *   block2: cond + JumpTrue/JumpFused → body (false fall-through to after)
     *   block3: after-loop
     * </pre>
     * Special case: when the test is the literal {@code false} the loop runs
     * exactly once and the cond block emits NOTHING — falls through to after.
     * Test literals are pre-registered before body so the constants pool
     * matches LibJS's source-order numbering.
     */
    private Variable.Register lowerDoWhileReturning(DoWhileStatement dws) {
        // Fast path: nested do-while pattern (body is itself a do-while).
        // LibJS uses a deferred-block layout to interleave allocations
        // between outer cond and inner body.
        DoWhileStatement nestedInner = extractNestedDoWhile(dws.body());
        if (nestedInner != null) {
            return lowerDoWhileWithNestedBody(dws, nestedInner);
        }

        Variable.Register completionReg = allocRegister();
        emit(new Op.Mov(completionReg, constant(Undefined.VALUE)));

        // Pre-register test literals: matches LibJS pool order (test before
        // body, even though body lowers first in the bytecode stream).
        preRegisterLiterals(dws.test());

        // Body block.
        startNewBlock();
        int bodyStart = currentPc();
        LoopContext loop = new LoopContext();
        attachPendingLabel(loop);
        loopStack.push(loop);

        // Pre-alloc the test's result reg BEFORE body lowering so body's
        // allocations skip past its slot — matches LibJS, which constructs
        // the test ScopedOperand in the test block before body-block emission
        // and keeps it alive across body codegen. For tests that don't have
        // a single result reg (fusable comparisons, falsy literals, complex
        // expressions we don't yet support), this is a no-op.
        Variable.Register testResultReg = preAllocateDoWhileTestResultReg(dws.test());

        boolean hasNestedBlock = doWhileBodyHasNestedBlock(dws.body());
        completionRegStack.push(completionReg);
        if (hasNestedBlock) doWhileWithNestedBlockDepth++;
        try {
            lowerStatement(dws.body());
        } finally {
            if (hasNestedBlock) doWhileWithNestedBlockDepth--;
            completionRegStack.pop();
        }

        // If the body's last instruction is a continue's Jump (placeholder
        // that would target the cond block immediately following), elide
        // it — fall-through into the cond block matches LibJS's behaviour.
        // Only safe when the Jump is the very last op (no later refs to its
        // PC exist) and it's the only pendingContinuePc at that PC.
        if (!loop.pendingContinuePcs.isEmpty() && !ops.isEmpty()) {
            int lastPc = ops.size() - 1;
            int lastIdx = loop.pendingContinuePcs.size() - 1;
            if (loop.pendingContinuePcs.get(lastIdx) == lastPc
                && ops.get(lastPc) instanceof Op.Jump) {
                ops.remove(lastPc);
                loop.pendingContinuePcs.remove(lastIdx);
            }
        }

        // Cond block. continue jumps land here.
        startNewBlock();
        int condStart = currentPc();
        loop.continueTargetPc = condStart;
        for (int p : loop.pendingContinuePcs) patchJumpTarget(p, condStart);

        int condFalsePc = emitDoWhileCondJump(dws.test(), testResultReg, bodyStart);
        if (testResultReg != null) release(testResultReg);

        // After-loop block.
        startNewBlock();
        int afterPc = currentPc();
        if (condFalsePc >= 0) patchJumpTarget(condFalsePc, afterPc);
        loop.breakTargetPc = afterPc;
        for (int p : loop.pendingBreakPcs) patchJumpTarget(p, afterPc);
        loopStack.pop();

        // Release the completion register so subsequent top-level statements
        // (ExpressionStatement Calls etc.) reuse the lowest-numbered slot —
        // matches LibJS, which uses reg5 (FIRST_USER_INDEX) as the persistent
        // script-completion slot rather than allocating fresh dsts above
        // a pinned do-while completion.
        release(completionReg);
        return completionReg;
    }

    // ------------------------------------------------------------
    //  Expressions
    // ------------------------------------------------------------

    private Operand lowerExpression(Expression e) {
        return switch (e) {
            case Literal lit -> {
                if (lit.regex() != null) {
                    Variable.Register dst = allocRegister();
                    emit(new Op.NewRegExp(dst, lit.regex().pattern(), lit.regex().flags()));
                    yield dst;
                }
                yield constant(literalValue(lit));
            }
            case Identifier id -> {
                // Special globals.
                if ("undefined".equals(id.name())) yield constant(Undefined.VALUE);
                if ("NaN".equals(id.name()))       yield constant(Double.NaN);
                if ("Infinity".equals(id.name()))  yield constant(Double.POSITIVE_INFINITY);

                // Parameter has highest precedence (function-local).
                Integer paramPos = params.get(id.name());
                if (paramPos != null) {
                    yield new Variable.Argument(paramPos);
                }
                // Already-captured name?
                Integer captured = captureSlot.get(id.name());
                if (captured != null) {
                    yield new Variable.Local(captured);
                }
                // Local declared in this scope?
                Integer slot = locals.get(id.name());
                if (slot != null) yield new Variable.Local(slot);

                // Try capturing from outer scope.
                int captureLocalSlot = captureFromOuter(id.name());
                if (captureLocalSlot >= 0) {
                    yield new Variable.Local(captureLocalSlot);
                }

                // Lexically-scoped binding (e.g. catch param materialized into
                // a CreateLexicalEnvironment scope) — emit a GetBinding op.
                if (lexEnvBindingNames.contains(id.name())) {
                    Variable.Register bindDst = allocRegister();
                    emit(new Op.GetBinding(bindDst, id.name(), new EnvironmentCoordinate()));
                    yield bindDst;
                }

                // Fall back to a global lookup. Spec semantics: even if the
                // generator can't statically prove the binding exists, emit
                // GetGlobal — the runtime throws ReferenceError if it really
                // is undefined. This matches how real JS engines treat free
                // identifiers (they're not parse-time errors).
                Variable.Register dst = allocRegister();
                emit(new Op.GetGlobal(dst, id.name(), new GlobalVariableCache()));
                yield dst;
            }
            case CallExpression call -> lowerCall(call);
            case NewExpression ne -> lowerNew(ne);
            case FunctionExpression fe -> lowerFunctionExpression(fe);
            case ArrowFunctionExpression afe -> lowerArrowFunction(afe);
            case LogicalExpression le -> lowerLogical(le);
            case ConditionalExpression ce -> lowerConditional(ce);
            case ChainExpression ce -> lowerChainExpression(ce);
            case TemplateLiteral tl -> lowerTemplateLiteral(tl);
            case TaggedTemplateExpression tte -> lowerTaggedTemplate(tte);
            case MetaProperty mp -> lowerMetaProperty(mp);
            case YieldExpression ye -> lowerYield(ye);
            case AwaitExpression aw -> lowerAwait(aw);
            case ObjectExpression oe -> lowerObjectExpression(oe);
            case ArrayExpression ae  -> lowerArrayExpression(ae);
            case MemberExpression me -> lowerMemberExpression(me);
            case ThisExpression te -> {
                // Emit ResolveThisBinding once per function scope on first
                // reference. Subsequent uses return the special this operand
                // (rendered as `this` in dumps) which reads from the same
                // THIS_VALUE register slot.
                if (!thisBindingResolved) {
                    emit(new Op.ResolveThisBinding());
                    thisBindingResolved = true;
                }
                yield Operand.This.INSTANCE;
            }
            case BinaryExpression bin -> lowerBinary(bin);
            case AssignmentExpression assign -> lowerAssignment(assign);
            case UnaryExpression u -> lowerUnary(u);
            case UpdateExpression u -> lowerUpdate(u);
            case ClassExpression ce -> lowerClassExpression(ce);
            case ImportExpression ie -> {
                Operand spec = lowerExpression(ie.source());
                Operand opts = ie.options() != null
                    ? lowerExpression(ie.options())
                    : constant(Undefined.VALUE);
                Variable.Register dst = allocRegister();
                emit(new Op.ImportCall(dst, spec, opts));
                release(spec);
                if (ie.options() != null) release(opts);
                yield dst;
            }
            case com.jimmyhmiller.harmonica.ast.SequenceExpression seq -> {
                // Comma operator: evaluate each expression for side effects;
                // result is the value of the last. Don't pre-seed Undefined —
                // a SequenceExpression with empty parts is never produced by
                // the parser, and the Undefined registration would land at a
                // pool position that doesn't match LibJS's lazy-Undefined
                // ordering.
                java.util.List<Expression> parts = seq.expressions();
                Operand last = null;
                for (int i = 0; i < parts.size(); i++) {
                    Operand v = lowerExpression(parts.get(i));
                    if (i < parts.size() - 1) {
                        release(v);
                    } else {
                        last = v;
                    }
                }
                yield last == null ? constant(Undefined.VALUE) : last;
            }
            case PrivateIdentifier pid -> {
                // Bare PrivateIdentifier as an expression appears in
                // `#field in obj` (private-element presence check, ES2022).
                // Per § 13.10 step 6, the LHS of `in` for a private name
                // tests obj.[[PrivateElements]] — we approximate by lowering
                // to the "#name" string and letting the `in` op walk the
                // object/proto chain (which holds private fields/methods at
                // those keys).
                yield constant("#" + pid.name());
            }
            default -> throw new UnsupportedOperationException(
                "Generator: expression type " + e.getClass().getSimpleName() + " is not yet supported");
        };
    }

    private Operand lowerUnary(UnaryExpression u) {
        // delete is unique: it inspects the target before evaluating it as a
        // value. Handle separately.
        if ("delete".equals(u.operator())) {
            return lowerDelete(u);
        }
        // void evaluates the operand for side effects, returns undefined.
        // LibJS allocates a dst slot for `void` AFTER lowering the operand,
        // even though the dst is never written (the result is constant
        // Undefined). The slot lifts the register-file high-water mark by
        // one, but lands in any free pool slot vacated by the operand —
        // so for operands that already use ≥ the post-void slot count,
        // the totals match anyway. Allocate then release.
        if ("void".equals(u.operator())) {
            Operand v = lowerExpression(u.argument());
            // Allocate the dst BEFORE releasing the operand — LibJS holds
            // both live simultaneously, so when the operand consumed only one
            // slot, the dst lands above it (lifting the high-water mark by
            // one). When the operand uses ≥ 2 slots, the freshly-popped pool
            // entry lands inside the existing range and the totals match.
            Variable.Register slot = allocRegister();
            release(slot);
            release(v);
            return constant(Undefined.VALUE);
        }
        // `!!x` collapses to `ToBoolean(x)` — LibJS detects this idiomatic
        // boolean coercion and folds the double-negation to a single op.
        // (For constant operands, the fold happens further: `!!1` → Bool(true)).
        // LibJS allocates dst BEFORE lowering src so the result lands in the
        // lowest free register, with src higher.
        if ("!".equals(u.operator())
            && u.argument() instanceof UnaryExpression inner
            && "!".equals(inner.operator())) {
            Variable.Register dst = allocRegister();
            Operand src = lowerExpression(inner.argument());
            if (src instanceof Operand.Constant cc) {
                Object cv = constants.get(cc.index());
                release(dst);
                return constant(AbstractOps.toBoolean(cv));
            }
            emit(new Op.ToBoolean(dst, src));
            release(src);
            return dst;
        }
        // typeof on a plain identifier compiles to TypeofBinding rather than
        // GetGlobal + Typeof. typeof on an unbound name yields "undefined"
        // (not a ReferenceError), and LibJS uses a single dedicated op.
        if ("typeof".equals(u.operator()) && u.argument() instanceof Identifier id) {
            // Only globals get TypeofBinding — locals would still need their
            // value loaded into a register first. Heuristic: if not in
            // {@link #locals}, treat as global.
            if (!locals.containsKey(id.name())) {
                Variable.Register dst = allocRegister();
                emit(new Op.TypeofBinding(dst, id.name()));
                return dst;
            }
        }

        // Allocation order mirrors LibJS, which differs by operator:
        //   - `!`: dst FIRST, src second (lower index = dst).
        //   - `+`, `-`, `~`: src FIRST, dst second (lower index = src).
        // Empirically verified via single-operand reproducers.
        //
        // For src-first operators, we fold compile-time constants BEFORE
        // allocating dst — LibJS's allocator never touches a slot for a
        // folded unary, so allocating-then-releasing here would leak the
        // slot into the register-file high-water mark. For `!`/`typeof`,
        // LibJS DOES touch the dst slot up-front (matched empirically with
        // `!1` reporting Registers:6), so we keep the alloc-then-release.
        boolean dstFirst = "!".equals(u.operator()) || "typeof".equals(u.operator());
        Variable.Register dst;
        Operand src;
        if (dstFirst) {
            dst = allocRegister();
            src = lowerExpression(u.argument());
        } else {
            src = lowerExpression(u.argument());
            if (src instanceof Operand.Constant cc) {
                Object cv = constants.get(cc.index());
                if (cv instanceof Double d) {
                    Object folded = switch (u.operator()) {
                        case "-" -> -d;
                        case "+" -> +d;
                        case "~" -> (double) (~AbstractOps.toInt32(d));
                        default -> null;
                    };
                    if (folded != null) return constant(folded);
                }
            }
            dst = allocRegister();
        }

        // Compile-time fold for numeric literals. The dst we allocated above
        // is a leftover; release it and return the folded constant.
        if (src instanceof Operand.Constant cc) {
            Object cv = constants.get(cc.index());
            if (cv instanceof Double d) {
                Object folded = switch (u.operator()) {
                    case "-" -> -d;
                    case "+" -> +d;
                    case "~" -> (double) (~AbstractOps.toInt32(d));
                    case "!" -> !AbstractOps.toBoolean(d);
                    default -> null;
                };
                if (folded != null) {
                    release(dst);
                    return constant(folded);
                }
            }
            if ("!".equals(u.operator())) {
                release(dst);
                return constant(!AbstractOps.toBoolean(cv));
            }
        }
        Op op = switch (u.operator()) {
            case "-"      -> new Op.UnaryMinus(dst, src);
            case "+"      -> new Op.UnaryPlus(dst, src);
            case "~"      -> new Op.BitwiseNot(dst, src);
            case "!"      -> new Op.Not(dst, src);
            case "typeof" -> new Op.Typeof(dst, src);
            default -> throw new UnsupportedOperationException(
                "Generator: unary operator '" + u.operator() + "' is not yet supported");
        };
        emit(op);
        release(src);
        return dst;
    }

    private Operand lowerDelete(UnaryExpression u) {
        if (u.argument() instanceof MemberExpression me) {
            Operand base = lowerExpression(me.object());
            Variable.Register dst = allocRegister();
            if (me.computed()) {
                Operand prop = lowerExpression(me.property());
                emit(new Op.DeleteByValue(dst, base, prop));
                release(prop);
            } else {
                emit(new Op.DeleteById(dst, base, nonComputedMemberName(me.property())));
            }
            release(base);
            return dst;
        }
        // delete <identifier>: LibJS uses a dedicated DeleteVariable opcode
        // that walks the scope chain, removing the binding if configurable.
        // For arbitrary expressions (e.g. `delete (1+2)`), there's no name —
        // fall back to evaluate-for-side-effects + Mov true (per spec, the
        // result of `delete` on a non-Reference is always true).
        if (u.argument() instanceof Identifier id) {
            Variable.Register dst = allocRegister();
            emit(new Op.DeleteVariable(dst, id.name()));
            return dst;
        }
        Variable.Register dst = allocRegister();
        emit(new Op.Mov(dst, constant(Boolean.TRUE)));
        Operand v = lowerExpression(u.argument());
        release(v);
        return dst;
    }

    private Operand lowerUpdate(UpdateExpression u) {
        boolean isIncrement = "++".equals(u.operator());
        boolean isDecrement = "--".equals(u.operator());
        if (!isIncrement && !isDecrement) {
            throw new UnsupportedOperationException("Generator: unknown update operator " + u.operator());
        }
        // ++ and -- on member expressions (`obj.foo++`, `obj[key]--`, `this.y++`).
        if (u.argument() instanceof MemberExpression me) {
            Operand base = lowerExpression(me.object());
            String foldedName = me.computed() ? foldedComputedMemberName(me.property()) : null;
            boolean useComputed = me.computed() && foldedName == null;
            String propName = null;
            Operand propKey = null;
            Variable.Register valueReg;
            if (useComputed) {
                propKey = lowerExpression(me.property());
                valueReg = allocRegister();
                emit(new Op.GetByValue(valueReg, base, propKey, memberChainNameOrSuffix(me.object())));
            } else {
                propName = foldedName != null ? foldedName : nonComputedMemberName(me.property());
                valueReg = allocRegister();
                emit(new Op.GetById(valueReg, base, propName,
                    memberChainNameOrSuffix(me.object()),
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            }
            if (u.prefix()) {
                emit(isIncrement ? new Op.Increment(valueReg) : new Op.Decrement(valueReg));
                if (useComputed) {
                    emit(new Op.PutByValue(base, propKey, valueReg));
                    release(propKey);
                } else {
                    // LibJS omits the (o.foo) annotation on the increment-
                    // induced PutById even though the matching GetById has it.
                    emit(new Op.PutById(base, propName, valueReg,
                        new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache(),
                        /* baseIdentifier */ null, Op.PutByIdKind.NORMAL));
                }
                release(base);
                return valueReg;
            } else {
                Variable.Register oldVal = allocRegister();
                emit(isIncrement
                    ? new Op.PostfixIncrement(oldVal, valueReg)
                    : new Op.PostfixDecrement(oldVal, valueReg));
                if (useComputed) {
                    emit(new Op.PutByValue(base, propKey, valueReg));
                    release(propKey);
                } else {
                    // LibJS omits the (o.foo) annotation on the increment-
                    // induced PutById even though the matching GetById has it.
                    emit(new Op.PutById(base, propName, valueReg,
                        new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache(),
                        /* baseIdentifier */ null, Op.PutByIdKind.NORMAL));
                }
                release(base);
                release(valueReg);
                return oldVal;
            }
        }
        if (!(u.argument() instanceof Identifier id)) {
            throw new UnsupportedOperationException(
                "Generator: UpdateExpression on " + u.argument().getClass().getSimpleName() + " not yet supported");
        }

        if (u.prefix()) {
            // Prefix: read current into a register, increment, write back, return.
            // For locals/arguments, LibJS increments the binding in place
            // (`Increment dst:x~0`) and returns the local directly — no Mov,
            // no writeBack, since Increment mutates dst.
            Operand current = lowerExpression(id);
            if (current instanceof Variable v
                && (current instanceof Variable.Local || current instanceof Variable.Argument)) {
                emit(isIncrement ? new Op.Increment(v) : new Op.Decrement(v));
                return v;
            }
            Variable.Register valueReg;
            if (current instanceof Variable.Register r) {
                valueReg = r;
            } else {
                valueReg = allocRegister();
                emit(new Op.Mov(valueReg, current));
            }
            emit(isIncrement ? new Op.Increment(valueReg) : new Op.Decrement(valueReg));
            writeBackBinding(id, valueReg);
            return valueReg;
        } else {
            // Postfix: read the current value FIRST, then allocate the
            // old-value (dst) register. Matches LibJS — `var x = i++` dumps
            // as `GetGlobal dst:reg5, i; PostfixIncrement dst:reg6, src:reg5`
            // with src (value) allocated first and dst (oldVal) second.
            // For locals/arguments, LibJS uses the binding directly as src
            // (e.g. `PostfixIncrement dst:reg6, src:x~0`) — PostfixIncrement
            // mutates src in place, so no Mov + writeBack is needed.
            Operand current = lowerExpression(id);
            if (current instanceof Variable.Local || current instanceof Variable.Argument) {
                Variable.Register oldVal = allocRegister();
                emit(isIncrement
                    ? new Op.PostfixIncrement(oldVal, current)
                    : new Op.PostfixDecrement(oldVal, current));
                return oldVal;
            }
            Variable.Register valueReg;
            if (current instanceof Variable.Register r) {
                valueReg = r;
            } else {
                valueReg = allocRegister();
                emit(new Op.Mov(valueReg, current));
            }
            Variable.Register oldVal = allocRegister();
            emit(isIncrement
                ? new Op.PostfixIncrement(oldVal, valueReg)
                : new Op.PostfixDecrement(oldVal, valueReg));
            writeBackBinding(id, valueReg);
            release(valueReg);
            return oldVal;
        }
    }

    /**
     * Short-circuit logical operators. {@code &&} returns lhs if falsy else rhs;
     * {@code ||} returns lhs if truthy else rhs; {@code ??} returns lhs if not
     * nullish else rhs. The result lands in a single destination register.
     */
    /**
     * Lower a BinaryExpression as the rhs of a logical operator, with the
     * binary's dst pre-allocated from the free pool's head. Returns the dst
     * register, or null if the expression isn't a supported BinaryExpression.
     * The pre-alloc lands the binary result directly into the just-released
     * outer dst slot, avoiding a redundant {@code Mov(outerDst, binResult)}.
     */
    private Operand lowerBinaryRhsForLogical(Expression e) {
        if (!(e instanceof BinaryExpression bin)) return null;
        // Pre-alloc binary dst from pool head (= the just-released outer dst).
        Variable.Register binDst = allocRegister();
        Operand l = lowerExpression(bin.left());
        Operand r = lowerExpression(bin.right());
        Op op = switch (bin.operator()) {
            case "+"    -> new Op.Add(binDst, l, r);
            case "-"    -> new Op.Sub(binDst, l, r);
            case "*"    -> new Op.Mul(binDst, l, r);
            case "/"    -> new Op.Div(binDst, l, r);
            case "%"    -> new Op.Mod(binDst, l, r);
            case "**"   -> new Op.Exp(binDst, l, r);
            case "&"    -> new Op.BitwiseAnd(binDst, l, r);
            case "|"    -> new Op.BitwiseOr (binDst, l, r);
            case "^"    -> new Op.BitwiseXor(binDst, l, r);
            case "<<"   -> new Op.LeftShift(binDst, l, r);
            case ">>"   -> new Op.RightShift(binDst, l, r);
            case ">>>"  -> new Op.UnsignedRightShift(binDst, l, r);
            case "<"    -> new Op.LessThan(binDst, l, r);
            case "<="   -> new Op.LessThanEquals(binDst, l, r);
            case ">"    -> new Op.GreaterThan(binDst, l, r);
            case ">="   -> new Op.GreaterThanEquals(binDst, l, r);
            case "==="  -> new Op.StrictlyEquals(binDst, l, r);
            case "!=="  -> new Op.StrictlyInequals(binDst, l, r);
            case "=="   -> new Op.LooselyEquals(binDst, l, r);
            case "!="   -> new Op.LooselyInequals(binDst, l, r);
            case "instanceof" -> new Op.Instanceof(binDst, l, r);
            case "in"   -> new Op.In(binDst, l, r);
            default -> null;
        };
        if (op == null) {
            // Unsupported; release the pre-alloc'd dst and fall back.
            release(binDst);
            // Note: l, r have already been lowered — caller must NOT relower.
            // Return them via a fallback path. For now, emit a Mov from binDst
            // to indicate we should fall back. Actually simpler: throw.
            throw new UnsupportedOperationException(
                "lowerBinaryRhsForLogical: unsupported operator " + bin.operator());
        }
        emit(op);
        release(r);
        release(l);
        return binDst;
    }

    private Operand lowerLogical(LogicalExpression le) {
        Operand lhs = lowerExpression(le.left());
        // Const-fold short-circuiting when lhs is a constant. `&&` returns lhs
        // when lhs is falsy; `||` returns lhs when lhs is truthy; `??` returns
        // lhs when lhs is non-nullish. Otherwise the rhs result is returned.
        if (lhs instanceof Operand.Constant lc) {
            Object lv = constants.get(lc.index());
            switch (le.operator()) {
                case "&&" -> {
                    if (!AbstractOps.toBoolean(lv)) return lhs;
                }
                case "||" -> {
                    if (AbstractOps.toBoolean(lv)) return lhs;
                }
                case "??" -> {
                    if (lv != null && lv != Undefined.VALUE) return lhs;
                }
            }
            // lhs didn't short-circuit: result is the rhs.
            return lowerExpression(le.right());
        }
        Variable.Register dst = allocRegister();
        emit(new Op.Mov(dst, lhs));
        // Use lhs (not dst) as the JumpFalse/JumpTrue condition — matches
        // LibJS, which tests the original lhs. Keep lhs alive through the
        // rhs lowering (don't release here) — file 310 chained `&&` shows
        // LibJS holds lhs's register past JumpFalse.
        Operand condition = lhs instanceof Variable.Register ? lhs : dst;

        int skipPc = switch (le.operator()) {
            case "&&" -> emit(new Op.JumpFalse(condition, /* placeholder */ -1));
            case "||" -> emit(new Op.JumpTrue (condition, /* placeholder */ -1));
            case "??" -> {
                // Fused single op: take true_target (rhs eval) when nullish,
                // false_target (skip rhs) otherwise. Set true_target to the
                // fall-through PC at emit time; the false_target placeholder
                // gets patched later by the merge-block setup. Condition is
                // the original lhs (matches LibJS, which tests the loaded
                // value rather than the Mov's destination).
                int pc = emit(new Op.JumpNullish(condition, /* trueTargetPc */ currentPc() + 1, /* placeholder */ -1));
                yield pc;
            }
            default -> throw new UnsupportedOperationException(
                "Generator: logical operator '" + le.operator() + "' not supported");
        };

        // Start a new block for the rhs-evaluation path — matches LibJS,
        // which puts each `&&` / `||` rhs in its own basic block.
        startNewBlock();

        // Release dst (LIFO push, sits at pool head). For binary-expression
        // rhs (e.g. `(y!==1)` inside `&&`), pre-allocate the binary op's dst
        // by re-popping the just-released slot — this lands the binary op's
        // result directly into dst, avoiding a redundant Mov(dst, binResult).
        // For simple rhs (Identifier, etc.), let the normal expression
        // lowering pop the slot (its first GetGlobal will land in dst).
        // LibJS-style optimization: temporarily release dst's slot so a
        // matching rhs allocation lands directly in it, avoiding a Mov.
        release(dst);
        Operand rhs = lowerBinaryRhsForLogical(le.right());
        if (rhs == null) {
            // Not a binary, or unsupported — fall back.
            rhs = lowerExpression(le.right());
        }
        boolean rhsLandedInDst = (rhs instanceof Variable.Register rr) && rr.index() == dst.index();
        if (rhsLandedInDst) {
            // rhs landed in dst — no Mov needed. dst's slot was popped back
            // out of the pool by rhs's allocator; we own it again.
        } else {
            emit(new Op.Mov(dst, rhs));
            release(rhs);
            // dst is still in the freePool from the release above. Pull it
            // back out so the caller's eventual release(dst) doesn't push
            // a duplicate index into the pool — that aliasing was the
            // source of a `c[x ?? 1](); c[x ?? 1]()` regression where the
            // second call's base + LogicalExpression dst landed on the
            // same physical register.
            freePool.removeFirstOccurrence(dst.index());
        }
        // Now release lhs, after rhs has finished using its register slots.
        release(lhs);

        // Start a new block for the after-skip merge point.
        startNewBlock();
        patchJumpTarget(skipPc, currentPc());
        return dst;
    }

    /** Emit a "skip-rhs if lhs is non-nullish" branch. Implemented as JumpFalse on a `value == null || value === undefined` test. */
    private int emitJumpNotNullish(Variable.Register dst) {
        // dst === null OR dst === undefined → fall through (eval rhs); else skip.
        // Simplification: emit a strict-equals test and short-circuit accordingly.
        // For v1 we use a branch on `dst != null && dst !== undefined`:
        //   if dst is nullish → load rhs; else skip rhs.
        // Done with: temp = (dst === null || dst === undefined); JumpFalse temp, skip.
        // To keep ops list simple, emit two strict-equals + a logical-or via Mov.
        Variable.Register tmp = allocRegister();
        // tmp = (dst === null)
        emit(new Op.StrictlyEquals(tmp, dst, constant(null)));
        // If true, fall through to load rhs (skip target = past Mov).
        // If false, check (dst === undefined). Easier: use JumpTrue tmp → fall-through label,
        // then emit StrictlyEquals tmp = dst === undefined; JumpFalse tmp → skip.
        // Implementation: JumpFalse tmp (=> dst is non-null), check undefined next.
        int notNullCheckPc = emit(new Op.JumpFalse(tmp, /* placeholder */ -1));
        // dst was null → fall through to evaluate rhs (skip is later patched past rhs Mov).
        // We need the skipPc to be the same outcome whether dst is null or not-nullish.
        // Use a Jump to a marker we'll patch.
        int gotoEvalRhs = emit(new Op.Jump(/* placeholder */ -1));
        // dst is not null — check undefined.
        patchJumpTarget(notNullCheckPc, currentPc());
        emit(new Op.StrictlyEquals(tmp, dst, constant(Undefined.VALUE)));
        int undefinedSkipPc = emit(new Op.JumpFalse(tmp, /* placeholder */ -1));
        // dst was undefined → fall through to evaluate rhs.
        patchJumpTarget(gotoEvalRhs, currentPc());
        release(tmp);
        return undefinedSkipPc;   // caller patches this to the post-rhs PC
    }

    private Operand lowerConditional(ConditionalExpression ce) {
        Operand cond = lowerExpression(ce.test());
        // Compile-time fold: if cond is a constant, only emit the chosen branch.
        if (cond instanceof Operand.Constant cc) {
            Object cv = constants.get(cc.index());
            if (cv instanceof Boolean b) {
                return lowerExpression(b ? ce.consequent() : ce.alternate());
            }
        }
        Variable.Register dst = allocRegister();
        int jumpFalsePc = emit(new Op.JumpFalse(cond, /* placeholder */ -1));
        release(cond);

        // Then branch.
        Operand thenVal = lowerExpression(ce.consequent());
        emit(new Op.Mov(dst, thenVal));
        release(thenVal);
        int jumpOverElsePc = emit(new Op.Jump(/* placeholder */ -1));

        patchJumpTarget(jumpFalsePc, currentPc());
        Operand elseVal = lowerExpression(ce.alternate());
        emit(new Op.Mov(dst, elseVal));
        release(elseVal);

        patchJumpTarget(jumpOverElsePc, currentPc());
        return dst;
    }

    private Operand lowerFunctionExpression(FunctionExpression fe) {
        // ES2015 NamedEvaluation: an anonymous function on the RHS of
        // `var name = function() {}` (or similar) gets its `.name` set to the
        // binding identifier. The assignment-side caller publishes that hint
        // via {@link #pendingFunctionName}; consume-once semantics so it
        // doesn't leak to unrelated function expressions.
        boolean hasOwnId = fe.id() != null;
        String name;
        if (hasOwnId) {
            name = fe.id().name();
        } else if (pendingFunctionName != null) {
            name = pendingFunctionName;
            pendingFunctionName = null;
        } else {
            name = null;
        }
        // Named function expressions get a private lexical environment that
        // binds the function's own name as an immutable. This lets the body
        // reference itself by name even if the outer scope rebinds the slot.
        if (hasOwnId) {
            ensureLexicalEnvironmentSaved();
            Variable.Register innerEnv = allocRegister();
            emit(new Op.CreateLexicalEnvironment(innerEnv,
                Variable.Register.SAVED_LEXICAL_ENVIRONMENT, /* capacity */ 0));
            emit(new Op.CreateVariable(name, /* isImmutable */ true,
                /* isGlobal */ false, /* isStrict */ false));
            JSFunction fn = generateFunction(name, fe.params(), fe.body(),
                /* isArrow */ false, fe.generator(), fe.async());
            int fnIndex = sharedFunctionData.size();
            sharedFunctionData.add(fn);
            Variable.Register dst = allocRegister();
            // Named-form NewFunction omits the `(name)` annotation in LibJS's
            // dump (the name is already visible via CreateVariable). Pass null.
            emit(new Op.NewFunction(dst, fnIndex, null, null));
            emit(new Op.InitializeLexicalBinding(name, dst,
                new com.jimmyhmiller.harmonica.bytecode.cache.EnvironmentCoordinate()));
            emit(new Op.SetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
            release(innerEnv);
            return dst;
        }
        JSFunction fn = generateFunction(name, fe.params(), fe.body(),
            /* isArrow */ false, fe.generator(), fe.async());
        int fnIndex = sharedFunctionData.size();
        sharedFunctionData.add(fn);
        Variable.Register dst = allocRegister();
        emit(new Op.NewFunction(dst, fnIndex, name, null));
        return dst;
    }

    /**
     * Emit {@link Op.GetLexicalEnvironment} to the SAVED_LEXICAL_ENVIRONMENT
     * register if it hasn't been emitted yet for this scope. Used by both
     * try-statement codegen and named-function-expression codegen. The pre-pass
     * in {@link #lowerProgram} also emits this when it detects any try; we
     * need it on demand here for named-FE-only programs.
     */
    private void ensureLexicalEnvironmentSaved() {
        if (lexicalEnvironmentSaved) return;
        emit(new Op.GetLexicalEnvironment(Variable.Register.SAVED_LEXICAL_ENVIRONMENT));
        lexicalEnvironmentSaved = true;
    }

    /**
     * Anonymous function name hint set by an enclosing var/let/const init or
     * AssignmentExpression(target=Identifier). Consumed once by the next
     * lowering of a FunctionExpression / ArrowFunctionExpression that has no
     * own id. Cleared after consumption so it can't leak across expressions.
     */
    private String pendingFunctionName;

    private Operand lowerArrowFunction(ArrowFunctionExpression afe) {
        // Arrow body may be an Expression or a BlockStatement. Wrap an
        // expression-bodied arrow as `{ return <expr>; }` for uniform handling.
        BlockStatement body;
        if (afe.body() instanceof BlockStatement bs) {
            body = bs;
        } else if (afe.body() instanceof Expression bodyExpr) {
            ReturnStatement implicitReturn = new ReturnStatement(0, 0, 0, 0, 0, 0, bodyExpr);
            body = new BlockStatement(0, 0, 0, 0, 0, 0, java.util.List.of(implicitReturn));
        } else {
            throw new IllegalStateException("ArrowFunctionExpression body neither block nor expression: "
                + afe.body().getClass().getSimpleName());
        }
        // ES2015 NamedEvaluation: `const f = () => {}` infers name "f" via
        // pendingFunctionName. Consume-once: clear after use so it doesn't
        // leak across expressions.
        String inferredName = pendingFunctionName;
        pendingFunctionName = null;
        // ECMA-262 § 10.2.1.4 — arrow functions don't have their own `arguments`
        // or `this`; they inherit from the enclosing non-arrow function. Pass
        // isArrow=true so generateFunction skips the `arguments` pre-binding.
        JSFunction fn = generateFunction(inferredName, afe.params(), body, /* isArrow */ true, /* isGenerator */ false, afe.async());
        int fnIndex = sharedFunctionData.size();
        sharedFunctionData.add(fn);
        Variable.Register dst = allocRegister();
        emit(new Op.NewFunction(dst, fnIndex, inferredName, null));
        return dst;
    }

    /**
     * LibJS's {@code copy_if_needed_to_preserve_evaluation_order}: if
     * {@code op} is a Local or Argument (slot-bound, mutable in place), copy
     * its current value into a fresh register and return that register.
     * For Register or Constant operands, returns the input unchanged. The
     * point of the copy is to freeze the value at evaluation time so a later
     * sub-expression's side effect can't mutate the local out from under
     * our Call.
     *
     * <p>LibJS applies this to a Call's {@code thisVal} and {@code callee}
     * before lowering args (only when there are args), and to each argument
     * after evaluation. The visible bytecode effect is an extra Mov before
     * the Call when an arg/this/callee is a local.
     */
    private Operand copyIfNeededForCall(Operand op) {
        if (op instanceof Variable.Local || op instanceof Variable.Argument) {
            Variable.Register reg = allocRegister();
            emit(new Op.Mov(reg, op));
            return reg;
        }
        return op;
    }

    private Operand lowerCall(CallExpression call) {
        // LibJS allocates the call result's destination register BEFORE lowering
        // the callee and arguments, so the dst gets the lowest free index and
        // operands occupy higher indices. We do the same for byte-perfect
        // matching with their dump output.
        Variable.Register dst = allocRegisterUpFront();

        // super(args): call the captured super-class function with `this` =
        // current this (already initialized for derived constructors). Args
        // can include spread.
        if (call.callee() instanceof Super) {
            // Read the super-constructor from the per-frame slot (set by
            // invokeFunctionInternal when the caller's ctor has a super).
            Variable.Register superFnReg = allocRegister();
            emit(new Op.GetSuperConstructor(superFnReg));
            Operand superFn = superFnReg;
            Operand thisVal0 = Variable.Register.THIS_VALUE;
            // dst already pre-allocated above
            // Use the same spread/non-spread path as a normal call.
            boolean anySpread = false;
            for (Expression e : call.arguments()) {
                if (e instanceof SpreadElement) { anySpread = true; break; }
            }
            if (!anySpread) {
                Operand[] argOperands = new Operand[call.arguments().size()];
                for (int k = 0; k < argOperands.length; k++) {
                    argOperands[k] = lowerExpression(call.arguments().get(k));
                }
                emit(new Op.Call(dst, superFn, thisVal0, argOperands, null,
                    new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
                for (Operand a : argOperands) release(a);
            } else {
                Variable.Register argsArr = allocRegister();
                emit(new Op.NewArray(argsArr, new Operand[0]));
                for (Expression e : call.arguments()) {
                    if (e instanceof SpreadElement se) {
                        Operand src = lowerExpression(se.argument());
                        emit(new Op.ArrayAppend(argsArr, src, true));
                        release(src);
                    } else {
                        Operand v = lowerExpression(e);
                        emit(new Op.ArrayAppend(argsArr, v, false));
                        release(v);
                    }
                }
                emit(new Op.CallWithArgumentArray(dst, superFn, thisVal0, argsArr, null,
                    new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
                release(argsArr);
            }
            release(superFn);
            return dst;
        }
        // Method-call form: o.foo(args) → `this` is `o`; non-method `this` is undefined.
        Operand callee;
        Operand thisVal;
        // a?.()  — guard the callee for nullishness inside a chain.
        boolean optionalCall = call.optional();
        // Compute the diagnostic "expression string" for method-call form
        // (e.g. "assert.throws"), computed member calls (e.g. "c[<object>]"),
        // and plain-identifier callees (e.g. "f").
        String callExprString = null;
        if (call.callee() instanceof MemberExpression maybeMember
            && !maybeMember.computed()
            && maybeMember.property() instanceof Identifier propId) {
            // Build the full dot-chain. Try identifiable form first
            // (`obj.foo.bar`); fall back to `<object>.<suffix>` when the
            // root is anonymous (`f().g.h` → `<object>.g.h`).
            String fullChain = memberChainName(maybeMember);
            if (fullChain != null) {
                callExprString = fullChain;
            } else {
                String suffix = memberChainSuffix(maybeMember);
                callExprString = "<object>" + (suffix != null ? suffix : "." + propId.name());
            }
        } else if (call.callee() instanceof MemberExpression maybeMember
            && maybeMember.computed()) {
            // Computed member call: LibJS dumps as `c[<object>]` — base name
            // (or `<object>` placeholder) + `[<object>]` placeholder for the
            // property. The string-literal-fold path uses the dotted form.
            String basePart = memberChainName(maybeMember.object());
            if (basePart == null) basePart = "<object>";
            // Computed access always renders with brackets in the Call dump,
            // even when the key folds to a static string. String-literal keys
            // are quoted (e.g. `obj['break']`); number/boolean/null/identifier
            // forms render bare (e.g. `<object>[1]`, `<object>[sym1]`); fully
            // dynamic expressions stay as the `<object>` placeholder.
            String litStr;
            if (maybeMember.property() instanceof Literal lit
                && literalValue(lit) instanceof String s) {
                // String-literal computed: render with quotes (e.g. `obj['x']`).
                // Don't intern here — the actual GetById/GetByValue lowering
                // later will register the constant in source-position order.
                litStr = "'" + s + "'";
            } else {
                litStr = computedKeyLiteralString(maybeMember.property());
            }
            callExprString = basePart + "[" + (litStr != null ? litStr : "<object>") + "]";
        } else if (call.callee() instanceof Identifier directId) {
            callExprString = directId.name();
        }
        if (call.callee() instanceof MemberExpression me) {
            // ECMA-262 § 13.3.7.1.1 MakeSuperPropertyReference — when the
            // member expression's object is `super`, the base for the
            // property lookup is the home object's prototype, but the
            // call's `this` value is the *current* this, not the super base.
            if (me.object() instanceof Super) {
                Variable.Register superCtor = allocRegister();
                emit(new Op.GetSuperConstructor(superCtor));
                Variable.Register superBase = allocRegister();
                emit(new Op.GetById(superBase, superCtor, "prototype", null,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
                release(superCtor);
                Variable.Register calleeReg = allocRegister();
                String foldedKey = me.computed() ? foldedComputedMemberName(me.property()) : null;
                if (me.computed() && foldedKey == null) {
                    Operand prop = lowerExpression(me.property());
                    emit(new Op.GetByValue(calleeReg, superBase, prop, null));
                    release(prop);
                } else {
                    String name = foldedKey != null ? foldedKey : nonComputedMemberName(me.property());
                    emit(new Op.GetById(calleeReg, superBase, name, null,
                        new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
                }
                release(superBase);
                Operand superThisVal = Variable.Register.THIS_VALUE;
                Operand[] argOperands = new Operand[call.arguments().size()];
                for (int k = 0; k < argOperands.length; k++) {
                    argOperands[k] = lowerExpression(call.arguments().get(k));
                }
                emit(new Op.Call(dst, calleeReg, superThisVal, argOperands, null,
                    new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
                for (Operand a : argOperands) release(a);
                release(calleeReg);
                return dst;
            }
            // Evaluate `o` once, then read `o.foo` for the callee, passing `o` as this.
            Operand baseRaw = lowerExpression(me.object());
            // Stash base in a register so we can reuse it for both callee and this.
            Variable.Register baseReg;
            if (baseRaw instanceof Variable.Register r) {
                baseReg = r;
            } else {
                baseReg = allocRegister();
                emit(new Op.Mov(baseReg, baseRaw));
                release(baseRaw);
            }
            Variable.Register calleeReg = allocRegister();
            String foldedCalleeName = me.computed() ? foldedComputedMemberName(me.property()) : null;
            if (me.computed() && foldedCalleeName == null) {
                Operand prop = lowerExpression(me.property());
                // When the GetByValue's result is the callee of an
                // immediately-following Call, LibJS hangs the diagnostic
                // annotation on the Call (as `c[<object>]`) and leaves the
                // GetByValue bare. Pass null baseIdentifier to match.
                emit(new Op.GetByValue(calleeReg, baseReg, prop, /* baseIdentifier */ null));
                release(prop);
            } else {
                // Build the dot-chain name (e.g. `C.prototype` for
                // `C.prototype.method`) for the GetById diagnostics annotation.
                // When the original access was bracket form (computed), LibJS
                // omits the `(obj.foo)` annotation even after folding the key
                // to a static string — pass null so we mirror that.
                String baseId = me.computed() ? null : memberChainNameOrSuffix(me.object());
                String name = foldedCalleeName != null ? foldedCalleeName : nonComputedMemberName(me.property());
                emit(new Op.GetById(calleeReg, baseReg, name, baseId,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            }
            callee = calleeReg;
            thisVal = baseReg;
        } else {
            callee = lowerExpression(call.callee());
            thisVal = constant(Undefined.VALUE);
        }

        // Optional call: short-circuit if callee is nullish.
        if (optionalCall) emitOptionalGuard(callee);

        // Detect spread arguments — switch to argument-array form if any.
        boolean anySpread = false;
        for (Expression e : call.arguments()) {
            if (e instanceof SpreadElement) { anySpread = true; break; }
        }

        // dst already pre-allocated above
        if (!anySpread) {
            // copy_if_needed_to_preserve_evaluation_order — when there are
            // any args, copy thisVal/callee from local/argument slots into
            // fresh registers BEFORE lowering args. Each arg is also copied
            // after evaluation if it's a local/argument. Matches LibJS.
            boolean hasArgs = !call.arguments().isEmpty();
            if (hasArgs) {
                thisVal = copyIfNeededForCall(thisVal);
                callee = copyIfNeededForCall(callee);
            }
            Operand[] argOperands = new Operand[call.arguments().size()];
            for (int i = 0; i < argOperands.length; i++) {
                Operand raw = lowerExpression(call.arguments().get(i));
                argOperands[i] = copyIfNeededForCall(raw);
            }
            // Direct eval: per ES spec, eval seen as an unqualified
            // Identifier and resolved to the global eval function gets a
            // distinct opcode that grants access to the caller's lexical
            // scope. LibJS emits CallDirectEval here.
            boolean isDirectEval = call.callee() instanceof Identifier idCallee
                && "eval".equals(idCallee.name())
                && resolvesToGlobal(idCallee);
            if (isDirectEval) {
                emit(new Op.CallDirectEval(dst, callee, thisVal, argOperands, callExprString,
                    new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
            } else {
                emit(new Op.Call(dst, callee, thisVal, argOperands, callExprString,
                    new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
            }
            for (Operand a : argOperands) release(a);
        } else {
            // Spread path. LibJS groups all LEADING non-spread arguments into
            // a single NewArray (forcing constants into registers via Mov
            // first), then uses ArrayAppend for everything from the first
            // spread onward (with `is_spread:true` for spreads, `false` for
            // remaining non-spread args).
            int firstSpreadIdx = 0;
            while (firstSpreadIdx < call.arguments().size()
                && !(call.arguments().get(firstSpreadIdx) instanceof SpreadElement)) {
                firstSpreadIdx++;
            }
            // Pre-allocate the args array register BEFORE lowering leading
            // args — LibJS allocates argsArr at the lowest free slot, leaving
            // higher slots for the per-arg Mov registers.
            Variable.Register argsArr = allocRegisterUpFront();
            Operand[] leadingRegs = new Operand[firstSpreadIdx];
            for (int i = 0; i < firstSpreadIdx; i++) {
                Operand v = lowerExpression(call.arguments().get(i));
                if (v instanceof Variable.Register r) {
                    leadingRegs[i] = r;
                } else {
                    Variable.Register reg = allocRegister();
                    emit(new Op.Mov(reg, v));
                    leadingRegs[i] = reg;
                }
            }
            emit(new Op.NewArray(argsArr, leadingRegs));
            // NB: don't release leadingRegs here — LibJS keeps them occupied
            // through the rest of the call so subsequent allocations skip
            // their slots. They get released after the CallWithArgumentArray
            // emits below.
            for (int i = firstSpreadIdx; i < call.arguments().size(); i++) {
                Expression e = call.arguments().get(i);
                if (e instanceof SpreadElement se) {
                    Operand src = lowerExpression(se.argument());
                    emit(new Op.ArrayAppend(argsArr, src, true));
                    release(src);
                } else {
                    Operand v = lowerExpression(e);
                    emit(new Op.ArrayAppend(argsArr, v, false));
                    release(v);
                }
            }
            emit(new Op.CallWithArgumentArray(dst, callee, thisVal, argsArr, callExprString,
                new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
            // Release order matters (LIFO push): release the leading-arg Mov
            // registers first so argsArr ends up closer to the pool head — the
            // next allocation in chained calls hits argsArr's slot first,
            // matching LibJS's register cycling.
            for (Operand v : leadingRegs) release(v);
            release(argsArr);
        }

        // In deferred-flush + completion-tracking context (compound body
        // inside a deferred for-body), LibJS releases the Call's dst BEFORE
        // its callee. Defer the temp releases so
        // {@link #lowerExpressionStatement}'s {@code release(v)} runs first,
        // leaving callee at the pool head for subsequent emissions.
        // (Applied only during deferred flushes to avoid regressing
        // non-deferred tests like file 31.)
        if ((inDeferredFlush || lexEnvCatchBodyDepth > 0)
            && currentCompletionReg() != null
            && !(call.callee() instanceof Super)) {
            if (thisVal != callee) endOfStatementReleases.add(thisVal);
            endOfStatementReleases.add(callee);
        } else {
            // LibJS releases thisVal BEFORE callee for method calls — push order
            // matters in the LIFO pool. With this order, the next allocation pops
            // callee's slot first, matching LibJS's chained-method-call register
            // numbering (e.g. `obj.foo().bar()` allocates bar's GetById dst into
            // foo's-callee slot, not foo's-this slot).
            if (thisVal != callee) release(thisVal);
            release(callee);
        }
        return dst;
    }

    private Operand lowerNew(NewExpression ne) {
        // Detect spread args; switch to argument-array form if any.
        boolean anySpread = false;
        for (Expression e : ne.arguments()) {
            if (e instanceof SpreadElement) { anySpread = true; break; }
        }
        // Pre-allocate dst BEFORE lowering callee/args so it gets the lowest
        // free register. Mirrors LibJS's allocation order for CallConstruct.
        Variable.Register dst = allocRegisterUpFront();
        // CallConstruct has an implicit `this_value:Undefined` — LibJS
        // registers it in the constant pool even though the dump format
        // doesn't render it. Pre-touch the constant so our pool order
        // matches.
        constant(Undefined.VALUE);
        Operand callee = lowerExpression(ne.callee());
        Operand[] argOperands = anySpread ? null : new Operand[ne.arguments().size()];
        if (!anySpread) {
            for (int i = 0; i < argOperands.length; i++) {
                argOperands[i] = lowerExpression(ne.arguments().get(i));
            }
        }
        // expression_string: source-side identifier of the callee (e.g.
        // `Test262Error` for `new Test262Error()`, `c.B` for `new c.B()`)
        // for diagnostics dumps. Null for arbitrary expressions like
        // `new (foo())`.
        String exprStr;
        if (ne.callee() instanceof Identifier id) {
            exprStr = id.name();
        } else if (ne.callee() instanceof MemberExpression me
            && !me.computed()
            && me.property() instanceof Identifier prop) {
            // For non-computed dot access, render the chain. Anonymous prefix
            // (e.g. `new (f().B)()`) falls back to `<object>.B` — same
            // convention as Call.
            String fullChain = memberChainName(me);
            if (fullChain != null) {
                exprStr = fullChain;
            } else {
                String suffix = memberChainSuffix(me);
                exprStr = "<object>" + (suffix != null ? suffix : "." + prop.name());
            }
        } else {
            exprStr = null;
        }
        if (anySpread) {
            // Build argument array via NewArray + ArrayAppend, same pattern as
            // the spread-call lowering.
            int firstSpreadIdx = 0;
            while (firstSpreadIdx < ne.arguments().size()
                && !(ne.arguments().get(firstSpreadIdx) instanceof SpreadElement)) {
                firstSpreadIdx++;
            }
            Variable.Register argsArr = allocRegister();
            Operand[] leading = new Operand[firstSpreadIdx];
            for (int i = 0; i < firstSpreadIdx; i++) {
                leading[i] = lowerExpression(ne.arguments().get(i));
            }
            emit(new Op.NewArray(argsArr, leading));
            for (Operand l : leading) release(l);
            for (int i = firstSpreadIdx; i < ne.arguments().size(); i++) {
                Expression e = ne.arguments().get(i);
                if (e instanceof SpreadElement se) {
                    Operand src = lowerExpression(se.argument());
                    emit(new Op.ArrayAppend(argsArr, src, true));
                    release(src);
                } else {
                    Operand v = lowerExpression(e);
                    emit(new Op.ArrayAppend(argsArr, v, false));
                    release(v);
                }
            }
            emit(new Op.CallConstructWithArgumentArray(dst, callee, argsArr, exprStr,
                new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
            release(argsArr);
            release(callee);
            return dst;
        }
        emit(new Op.CallConstruct(dst, callee, argOperands, exprStr,
            new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
        // LibJS releases args BEFORE callee (verified empirically). Push order
        // matters because the free pool is LIFO — releasing in this order means
        // the callee's slot is the most-recent push and gets reused first.
        for (Operand a : argOperands) release(a);
        release(callee);
        return dst;
    }

    private Operand lowerObjectExpression(ObjectExpression oe) {
        Variable.Register dst = allocRegister();
        emit(new Op.NewObject(dst));
        boolean anyInitProperty = false;
        // Detect accessors. When the literal has any get/set, LibJS lowers
        // regular properties via PutById kind:Own (instead of
        // InitObjectLiteralProperty) and accessors via PutById kind:Getter/
        // Setter (instead of DefineAccessor). The object also doesn't get
        // CacheObjectShape, and per-shape index reverts to 0 for nested
        // literals only — outer object literals with accessors don't bump
        // nextShapeCacheIndex.
        boolean hasAccessor = false;
        for (Node propNode : oe.properties()) {
            if (propNode instanceof Property prop
                && ("get".equals(prop.kind()) || "set".equals(prop.kind()))) {
                hasAccessor = true;
                break;
            }
        }
        // Detect any runtime-computed key. When present, LibJS switches every
        // property in the literal — even folded string-literal-computed and
        // non-computed Identifier keys — to PutById kind:Own with no shape
        // cache and no CacheObjectShape, because the computed key forces the
        // engine to give up shape stability for the whole literal.
        boolean hasRuntimeComputedKey = false;
        for (Node propNode : oe.properties()) {
            if (propNode instanceof Property prop && prop.computed()) {
                boolean foldsToString = prop.key() instanceof Literal lit
                    && literalValue(lit) instanceof String;
                if (!foldsToString) { hasRuntimeComputedKey = true; break; }
            }
        }
        // Numeric-literal keys (e.g. `{1: true}`) are treated as computed
        // keys by LibJS: ToPrimitiveWithStringHint + PutByValue kind:Own.
        // Their presence forces every property in the literal off the
        // shape-cache fast path.
        boolean hasNumericLiteralKey = false;
        for (Node propNode : oe.properties()) {
            if (propNode instanceof Property prop && !prop.computed()
                && prop.key() instanceof Literal lit) {
                Object v = literalValue(lit);
                if (v instanceof Integer || v instanceof Long || v instanceof Double) {
                    hasNumericLiteralKey = true;
                    break;
                }
            }
        }
        // Spread elements (`{a, ...rest}`) also force the OWN path — LibJS
        // can't shape-cache an object whose layout depends on runtime spread.
        boolean hasSpread = false;
        for (Node propNode : oe.properties()) {
            if (propNode instanceof SpreadElement) { hasSpread = true; break; }
        }
        boolean forceOwn = hasAccessor || hasRuntimeComputedKey || hasNumericLiteralKey || hasSpread;
        // Per-object-literal shape cache index (lazy-allocated when the
        // first init property emits, so empty/pure-spread objects don't
        // bump the counter). Property slots are 0-based within this
        // literal — first init prop gets slot 0, next gets 1, etc.
        // Pre-allocate the shape_cache_index BEFORE lowering inner property
        // values — LibJS counts the OUTER literal's index first, then any
        // nested literal's index. We only need an index when at least one
        // property will emit InitObjectLiteralProperty (non-forceOwn, non-
        // spread, non-accessor 'init' kind).
        int thisShapeCacheIndex = -1;
        if (!forceOwn) {
            for (Node propNode : oe.properties()) {
                if (propNode instanceof Property prop
                    && "init".equals(prop.kind())) {
                    thisShapeCacheIndex = nextShapeCacheIndex++;
                    break;
                }
            }
        }
        int nextPropertySlot = 0;
        for (Node propNode : oe.properties()) {
            if (propNode instanceof SpreadElement se) {
                Operand src = lowerExpression(se.argument());
                emit(new Op.PutBySpread(dst, src));
                release(src);
                continue;
            }
            if (!(propNode instanceof Property prop)) {
                throw new UnsupportedOperationException(
                    "Generator: object-literal element type "
                    + propNode.getClass().getSimpleName() + " not supported");
            }
            // Object-literal getter/setter.
            if ("get".equals(prop.kind()) || "set".equals(prop.kind())) {
                if (!(prop.value() instanceof FunctionExpression fnExpr)) {
                    throw new IllegalStateException("Accessor value must be a FunctionExpression");
                }
                // String-literal computed accessor (`get ["foo"]() {}`) folds
                // to the non-computed PutById path — LibJS does this too,
                // since the key is statically resolvable.
                boolean isStringLiteralComputed = prop.computed()
                    && prop.key() instanceof Literal lit
                    && literalValue(lit) instanceof String;
                if (!prop.computed() || isStringLiteralComputed) {
                    String keyStr = isStringLiteralComputed
                        ? (String) literalValue((Literal) prop.key())
                        : objLiteralKey(prop);
                    String fnDisplayName = prop.kind() + " " + keyStr;
                    JSFunction accFn = generateFunction(fnDisplayName, fnExpr.params(), fnExpr.body());
                    int idx = sharedFunctionData.size();
                    sharedFunctionData.add(accFn);
                    Variable.Register fnReg = allocRegister();
                    emit(new Op.NewFunction(fnReg, idx, fnDisplayName, dst));
                    emit(new Op.PutById(dst, keyStr, fnReg,
                        new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache(),
                        null,
                        "get".equals(prop.kind()) ? Op.PutByIdKind.GETTER : Op.PutByIdKind.SETTER));
                    release(fnReg);
                    continue;
                }
                // Computed accessor key (non-string-literal): lower the key,
                // run it through ToPrimitiveWithStringHint, build the accessor
                // function, then install via PutByValue with kind:Getter/Setter.
                if (!(prop.key() instanceof Expression keyExpr)) {
                    throw new IllegalStateException("Computed key must be an Expression");
                }
                Operand keyOp = lowerExpression(keyExpr);
                emit(new Op.ToPrimitiveWithStringHint(keyOp, keyOp));
                // For fully-computed accessor keys, LibJS renders no name
                // annotation on NewFunction — pass null displayName.
                JSFunction accFn = generateFunction(prop.kind(), fnExpr.params(), fnExpr.body());
                int idx = sharedFunctionData.size();
                sharedFunctionData.add(accFn);
                Variable.Register fnReg = allocRegister();
                emit(new Op.NewFunction(fnReg, idx, /* displayName */ null, dst));
                emit(new Op.PutByValue(dst, keyOp, fnReg,
                    "get".equals(prop.kind()) ? Op.PutByValueKind.GETTER : Op.PutByValueKind.SETTER,
                    null));
                release(fnReg);
                release(keyOp);
                continue;
            }
            if (!"init".equals(prop.kind())) {
                throw new UnsupportedOperationException(
                    "Generator: object literal kind '" + prop.kind() + "' not supported");
            }
            if (!(prop.value() instanceof Expression valueExpr)) {
                throw new IllegalStateException("Property value is not an Expression: " + prop.value().getClass());
            }
            // Methods (`{ method() {...} }`) lower as a property whose value is
            // a FunctionExpression that gets [[HomeObject]] set to the object
            // literal — matches LibJS's `NewFunction ... home_object:<obj>`
            // dump for methods. The pendingFunctionName hint is also published
            // so the function picks up the property name.
            boolean isMethod = prop.method() && prop.value() instanceof FunctionExpression;
            // String-literal computed key folds to the non-computed path,
            // matching LibJS's `{ ["x"]: 1 }` → InitObjectLiteralProperty.
            boolean isStringLiteralComputed = prop.computed()
                && prop.key() instanceof Literal lit
                && literalValue(lit) instanceof String;
            // For runtime-computed keys, LibJS lowers the KEY before the VALUE
            // (so the resulting bytecode is GetGlobal+ToPrimitive+NewFunction
            // +PutByValue, not NewFunction+GetGlobal+ToPrimitive+PutByValue).
            // We mirror that order to match.
            boolean isComputedRuntimeKey = prop.computed() && !isStringLiteralComputed;
            // Numeric-literal non-computed keys (`{1: true}`) are routed
            // through the runtime key path: ToPrimitiveWithStringHint on the
            // numeric value, then PutByValue kind:Own. Matches LibJS, which
            // treats integer keys as array indices.
            boolean isNumericLiteralKey = !prop.computed()
                && prop.key() instanceof Literal lit2
                && (literalValue(lit2) instanceof Integer
                 || literalValue(lit2) instanceof Long
                 || literalValue(lit2) instanceof Double);
            Operand computedKey = null;
            if (isComputedRuntimeKey) {
                if (!(prop.key() instanceof Expression keyExpr)) {
                    throw new IllegalStateException("Computed key must be an Expression");
                }
                computedKey = lowerExpression(keyExpr);
                emit(new Op.ToPrimitiveWithStringHint(computedKey, computedKey));
            } else if (isNumericLiteralKey) {
                // Lower the numeric literal as a constant operand and run it
                // through ToPrimitiveWithStringHint — LibJS does this
                // unconditionally before PutByValue for numeric keys.
                computedKey = constant(literalValue((Literal) prop.key()));
                emit(new Op.ToPrimitiveWithStringHint(computedKey, computedKey));
            }
            Operand v;
            if (isMethod && prop.value() instanceof FunctionExpression methodFn && methodFn.id() == null) {
                // Inline lowering of the method so we can attach home_object
                // (matches LibJS, which sets [[HomeObject]] on every object-
                // literal method, regardless of whether the key is non-
                // computed, string-literal-computed, or runtime-computed).
                String mname;
                if (!prop.computed() && prop.key() instanceof Identifier idKey) {
                    mname = idKey.name();
                } else if (!prop.computed() && prop.key() instanceof Literal lit) {
                    mname = AbstractOps.toString(literalValue(lit));
                } else if (isStringLiteralComputed) {
                    mname = (String) literalValue((Literal) prop.key());
                } else {
                    // Runtime-computed key — LibJS emits no name annotation.
                    mname = null;
                }
                JSFunction fn = generateFunction(mname == null ? "" : mname,
                    methodFn.params(), methodFn.body(),
                    /* isArrow */ false, methodFn.generator(), methodFn.async());
                int fnIndex = sharedFunctionData.size();
                sharedFunctionData.add(fn);
                Variable.Register fnReg = allocRegister();
                emit(new Op.NewFunction(fnReg, fnIndex, mname, dst));
                v = fnReg;
            } else {
                // ES2015 NamedEvaluation: an anonymous function/class on the
                // RHS of a property gets the property's key as its name.
                // Publish the name hint so lowerFunctionExpression / lowerClassExpression
                // picks it up. Computed-key cases skip this (the name comes
                // from the runtime key value, not statically).
                String savedPendingName = pendingFunctionName;
                if (!prop.computed() && (valueExpr instanceof FunctionExpression
                                       || valueExpr instanceof ArrowFunctionExpression
                                       || valueExpr instanceof ClassExpression)) {
                    String inferredKey = null;
                    if (prop.key() instanceof Identifier idKey) {
                        inferredKey = idKey.name();
                    } else if (prop.key() instanceof Literal lit) {
                        inferredKey = AbstractOps.toString(literalValue(lit));
                    }
                    if (inferredKey != null) pendingFunctionName = inferredKey;
                }
                v = lowerExpression(valueExpr);
                pendingFunctionName = savedPendingName;
            }
            if (isComputedRuntimeKey || isNumericLiteralKey) {
                emit(new Op.PutByValue(dst, computedKey, v, Op.PutByValueKind.OWN, null));
                // Release v FIRST, then computedKey — LibJS pushes the value
                // register first so the key returns to the top of the LIFO
                // pool. The next runtime-computed property's key load will
                // then reuse the LOWER register (matches LibJS's allocator).
                release(v);
                release(computedKey);
                continue;  // skip the trailing release(v) below
            } else {
                String keyStr;
                if (prop.key() instanceof Identifier idKey) {
                    keyStr = idKey.name();
                } else if (prop.key() instanceof Literal lit) {
                    keyStr = AbstractOps.toString(literalValue(lit));
                } else {
                    throw new UnsupportedOperationException(
                        "Generator: unsupported object-literal key type " + prop.key().getClass().getSimpleName());
                }
                if (forceOwn) {
                    // Accessor present, runtime-computed key, or numeric
                    // literal key in the literal: regular properties go
                    // through PutById kind:Own (no shape cache, no
                    // CacheObjectShape). Matches LibJS.
                    emit(new Op.PutById(dst, keyStr, v,
                        new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache(),
                        null, Op.PutByIdKind.OWN));
                } else {
                    // thisShapeCacheIndex was pre-allocated up front (see top
                    // of lowerObjectExpression) so it ranks BEFORE any inner
                    // literal's index. Falling through to here means we have
                    // an init property, so the index must be set.
                    emit(new Op.InitObjectLiteralProperty(dst, keyStr, v, thisShapeCacheIndex, nextPropertySlot++));
                    anyInitProperty = true;
                }
            }
            release(v);
        }
        // LibJS finalizes object-literal initialization with CacheObjectShape
        // (a hint to its IC system about the resulting shape). We emit this
        // unconditionally when there's at least one InitObjectLiteralProperty
        // — empty objects and pure-spread objects don't get the hint.
        // Objects with accessors don't get CacheObjectShape either — they
        // use PutById Own which doesn't participate in shape caching.
        if (anyInitProperty && !forceOwn) {
            emit(new Op.CacheObjectShape(dst));
        }
        return dst;
    }

    private Operand lowerArrayExpression(ArrayExpression ae) {
        // Fast path: no spreads → emit a single NewArray with all elements.
        boolean anySpread = false;
        for (Expression elt : ae.elements()) {
            if (elt instanceof SpreadElement) { anySpread = true; break; }
        }
        if (!anySpread) {
            // Specialized path: when ALL elements are number, boolean, or null
            // literals, emit NewPrimitiveArray. LibJS does this — the elements
            // are inlined in the dump and bypass the constants pool.
            if (allPrimitiveLiterals(ae.elements())) {
                Object[] prims = new Object[ae.elements().size()];
                for (int i = 0; i < prims.length; i++) {
                    Expression elt = ae.elements().get(i);
                    prims[i] = (elt == null) ? Op.HOLE : literalValue((Literal) elt);
                }
                Variable.Register dst = allocRegister();
                emit(new Op.NewPrimitiveArray(dst, prims));
                return dst;
            }
            Operand[] elements = new Operand[ae.elements().size()];
            for (int i = 0; i < elements.length; i++) {
                Expression elt = ae.elements().get(i);
                // Holes (`[1,,3]`) go into the constants pool as a distinct
                // <Empty> sentinel — LibJS treats them differently from
                // explicit `undefined`. Use Op.HOLE.
                elements[i] = (elt == null) ? constant(Op.HOLE) : lowerExpression(elt);
            }
            Variable.Register dst = allocRegister();
            emit(new Op.NewArray(dst, elements));
            for (Operand e : elements) release(e);
            return dst;
        }
        // Spread path. LibJS groups all LEADING non-spread elements into a
        // single NewArray, then uses ArrayAppend for the spread and any
        // trailing elements. Mirror that so the dump matches.
        int firstSpreadIdx = 0;
        while (firstSpreadIdx < ae.elements().size()
            && !(ae.elements().get(firstSpreadIdx) instanceof SpreadElement)) {
            firstSpreadIdx++;
        }
        Variable.Register dst = allocRegister();
        Operand[] leading = new Operand[firstSpreadIdx];
        for (int i = 0; i < firstSpreadIdx; i++) {
            Expression elt = ae.elements().get(i);
            leading[i] = (elt == null) ? constant(Undefined.VALUE) : lowerExpression(elt);
        }
        emit(new Op.NewArray(dst, leading));
        for (Operand e : leading) release(e);
        for (int i = firstSpreadIdx; i < ae.elements().size(); i++) {
            Expression elt = ae.elements().get(i);
            if (elt == null) {
                emit(new Op.ArrayAppend(dst, constant(Undefined.VALUE), false));
            } else if (elt instanceof SpreadElement se) {
                Operand src = lowerExpression(se.argument());
                emit(new Op.ArrayAppend(dst, src, true));
                release(src);
            } else {
                Operand v = lowerExpression(elt);
                emit(new Op.ArrayAppend(dst, v, false));
                release(v);
            }
        }
        return dst;
    }

    /**
     * True if every element of {@code elts} is a literal whose value is a
     * number, boolean, or null. Empty elements (holes), strings, undefined,
     * identifiers, and sub-expressions all fail this test. Used by
     * {@link #lowerArrayExpression} to decide between {@link Op.NewArray} and
     * the specialized {@link Op.NewPrimitiveArray}.
     */
    private static boolean allPrimitiveLiterals(java.util.List<Expression> elts) {
        if (elts.isEmpty()) return false;
        for (Expression e : elts) {
            if (e == null) continue;   // hole — emitted as <empty> sentinel
            if (!(e instanceof Literal lit)) return false;
            Object v = literalValueStatic(lit);
            if (v == null) continue;   // null literal is OK
            if (v instanceof Double || v instanceof Boolean) continue;
            return false;
        }
        return true;
    }

    /**
     * Static variant of {@link #literalValue} usable from a static context.
     * Returns null for the {@code null} literal, the {@link Double} or
     * {@link Boolean} value otherwise; throws for unsupported literal kinds.
     */
    private static Object literalValueStatic(Literal lit) {
        if (lit.value() == null) return null;
        Object v = lit.value();
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b;
        if (v instanceof String) return v;
        return v;
    }

    /**
     * {@code a?.b}, {@code a?.[k]}, {@code a?.()}: short-circuit to undefined if any
     * receiver in the chain is nullish.
     *
     * <p>We allocate a result register, walk the inner expression, and at every
     * optional access we emit a "if nullish, set result=undefined and skip
     * to chain end" branch. Non-optional accesses inside the chain compile as
     * usual.
     */
    private final java.util.Deque<java.util.List<Integer>> chainBailoutPcs = new java.util.ArrayDeque<>();

    /**
     * Template literal {@code `text${expr}more`} → string concatenation of
     * alternating {@code quasis[0]}, {@code expressions[0]}, {@code quasis[1]}, ...
     */
    private Operand lowerMetaProperty(MetaProperty mp) {
        if ("new".equals(mp.meta().name()) && "target".equals(mp.property().name())) {
            Variable.Register dst = allocRegister();
            emit(new Op.GetNewTarget(dst));
            return dst;
        }
        if ("import".equals(mp.meta().name()) && "meta".equals(mp.property().name())) {
            // Stub: import.meta is an empty object until module loading is wired.
            Variable.Register dst = allocRegister();
            emit(new Op.NewObject(dst));
            return dst;
        }
        throw new UnsupportedOperationException(
            "Generator: meta property '" + mp.meta().name() + "." + mp.property().name() + "' not supported");
    }

    private Operand lowerYield(YieldExpression ye) {
        Variable.Register dst = allocRegister();
        Operand value = ye.argument() != null
            ? lowerExpression(ye.argument())
            : constant(Undefined.VALUE);
        emit(new Op.Yield(dst, value, ye.delegate()));
        release(value);
        return dst;
    }

    private Operand lowerAwait(AwaitExpression aw) {
        Variable.Register dst = allocRegister();
        Operand value = lowerExpression(aw.argument());
        emit(new Op.Await(dst, value));
        release(value);
        return dst;
    }

    private Operand lowerTemplateLiteral(TemplateLiteral tl) {
        Variable.Register dst = allocRegister();
        // Start with the first quasi (always present, may be empty).
        emit(new Op.Mov(dst, constant(tl.quasis().get(0).value().cooked())));
        for (int i = 0; i < tl.expressions().size(); i++) {
            // Concatenate the expression's value (after ToString) and the next quasi.
            Operand exprVal = lowerExpression(tl.expressions().get(i));
            // dst = dst + exprVal
            Variable.Register tmp = allocRegister();
            emit(new Op.Add(tmp, dst, exprVal));
            emit(new Op.Mov(dst, tmp));
            release(tmp);
            release(exprVal);
            // Then dst = dst + quasis[i+1]
            String nextQuasi = tl.quasis().get(i + 1).value().cooked();
            if (!nextQuasi.isEmpty()) {
                Variable.Register tmp2 = allocRegister();
                emit(new Op.Add(tmp2, dst, constant(nextQuasi)));
                emit(new Op.Mov(dst, tmp2));
                release(tmp2);
            }
        }
        return dst;
    }

    /**
     * ECMA-262 § 13.3.11 Tagged Template Literals — {@code tag\`a${x}b\`}
     * lowers to {@code tag(strings, ...exprs)} where {@code strings} is an
     * array of cooked-string values with a {@code raw} property holding the
     * raw-string array. v1: builds the strings/raw arrays inline at every
     * call (spec § 13.3.11.4 GetTemplateObject caches them per-source-site;
     * we don't, which means {@code site === site} fails — flagged).
     */
    private Operand lowerTaggedTemplate(TaggedTemplateExpression tte) {
        TemplateLiteral q = tte.quasi();
        // Build the strings array.
        Variable.Register strings = allocRegister();
        emit(new Op.NewArray(strings, new Operand[0]));
        for (int i = 0; i < q.quasis().size(); i++) {
            emit(new Op.ArrayAppend(strings,
                constant(q.quasis().get(i).value().cooked()), false));
        }
        // Build the raw array and attach as `strings.raw`.
        Variable.Register raw = allocRegister();
        emit(new Op.NewArray(raw, new Operand[0]));
        for (int i = 0; i < q.quasis().size(); i++) {
            String rs = q.quasis().get(i).value().raw();
            emit(new Op.ArrayAppend(raw, constant(rs == null ? "" : rs), false));
        }
        emit(new Op.PutById(strings, "raw", raw,
            new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache(),
            null, Op.PutByIdKind.NORMAL));
        release(raw);
        // Lower the tag and call it with strings + interpolated args. Tag
        // can be a member expression (e.g. `obj.fn\`...\``); use the
        // method-call path so `this` wires correctly.
        Variable.Register dst = allocRegister();
        if (tte.tag() instanceof MemberExpression me) {
            Operand baseRaw = lowerExpression(me.object());
            Variable.Register baseReg;
            if (baseRaw instanceof Variable.Register r) baseReg = r;
            else { baseReg = allocRegister(); emit(new Op.Mov(baseReg, baseRaw)); release(baseRaw); }
            Variable.Register calleeReg = allocRegister();
            String foldedKey = me.computed() ? foldedComputedMemberName(me.property()) : null;
            if (me.computed() && foldedKey == null) {
                Operand prop = lowerExpression(me.property());
                emit(new Op.GetByValue(calleeReg, baseReg, prop, null));
                release(prop);
            } else {
                String name = foldedKey != null ? foldedKey : nonComputedMemberName(me.property());
                emit(new Op.GetById(calleeReg, baseReg, name, null,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            }
            Operand[] callArgs = new Operand[1 + q.expressions().size()];
            callArgs[0] = strings;
            for (int i = 0; i < q.expressions().size(); i++) {
                callArgs[i + 1] = lowerExpression(q.expressions().get(i));
            }
            emit(new Op.Call(dst, calleeReg, baseReg, callArgs, null,
                new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
            for (int i = 1; i < callArgs.length; i++) release(callArgs[i]);
            release(calleeReg);
            release(baseReg);
        } else {
            Operand callee = lowerExpression(tte.tag());
            Operand[] callArgs = new Operand[1 + q.expressions().size()];
            callArgs[0] = strings;
            for (int i = 0; i < q.expressions().size(); i++) {
                callArgs[i + 1] = lowerExpression(q.expressions().get(i));
            }
            emit(new Op.Call(dst, callee, constant(Undefined.VALUE), callArgs, null,
                new com.jimmyhmiller.harmonica.bytecode.cache.CallSite()));
            for (int i = 1; i < callArgs.length; i++) release(callArgs[i]);
            release(callee);
        }
        release(strings);
        return dst;
    }

    private Operand lowerChainExpression(ChainExpression ce) {
        Variable.Register dst = allocRegister();
        java.util.List<Integer> bailouts = new ArrayList<>();
        chainBailoutPcs.push(bailouts);
        try {
            Operand inner = lowerExpression(ce.expression());
            emit(new Op.Mov(dst, inner));
            release(inner);
        } finally {
            chainBailoutPcs.pop();
        }
        // Short-circuit landing pad.
        if (!bailouts.isEmpty()) {
            int jumpOver = emit(new Op.Jump(/* placeholder */ -1));
            startNewBlock();
            int bailoutPc = currentPc();
            emit(new Op.Mov(dst, constant(Undefined.VALUE)));
            startNewBlock();
            patchJumpTarget(jumpOver, currentPc());
            for (int p : bailouts) patchJumpTarget(p, bailoutPc);
        }
        return dst;
    }

    /**
     * Emit the nullish-check guard for an optional access inside a chain.
     * If the value is null/undefined, jumps to the chain's bailout block;
     * otherwise falls through.
     */
    private void emitOptionalGuard(Operand value) {
        if (chainBailoutPcs.isEmpty()) {
            // Optional access outside a ChainExpression is technically illegal in
            // ESTree, but be defensive — short-circuit to undefined silently.
            return;
        }
        Variable.Register isNull = allocRegister();
        emit(new Op.StrictlyEquals(isNull, value, constant(null)));
        int jumpIfNull = emit(new Op.JumpTrue(isNull, /* placeholder */ -1));
        chainBailoutPcs.peek().add(jumpIfNull);
        emit(new Op.StrictlyEquals(isNull, value, constant(Undefined.VALUE)));
        int jumpIfUndef = emit(new Op.JumpTrue(isNull, /* placeholder */ -1));
        chainBailoutPcs.peek().add(jumpIfUndef);
        release(isNull);
    }

    /**
     * Build the dot-chain name for a member-expression's base — e.g.
     * {@code "C.prototype"} for {@code C.prototype.method}'s base. Returns
     * the bare identifier name for an Identifier base, the chain string for
     * a non-computed MemberExpression base, or {@code null} otherwise.
     * Used as the {@code baseIdentifier} field on {@link Op.GetById} /
     * {@link Op.GetLength} / {@link Op.GetByValue} for diagnostic dump output.
     */
    /**
     * Resolve a MemberExpression / Identifier / ThisExpression / Literal to
     * a static dotted name. Returns null if the chain has an unidentified
     * prefix (e.g. a Call or NewExpression at the root); use
     * {@link #memberChainSuffix} for those.
     */
    private String memberChainName(Node node) {
        if (node instanceof Identifier id) return id.name();
        if (node instanceof ThisExpression) return "this";
        if (node instanceof Literal lit) {
            Object v = literalValue(lit);
            if (v == null) return null;
            if (v instanceof String s) return "'" + s + "'";
            if (v instanceof Boolean) return v.toString();
            if (v instanceof Integer || v instanceof Long) return v.toString();
            if (v instanceof Double d) {
                if (d == d.longValue() && !Double.isInfinite(d)) return Long.toString(d.longValue());
                return d.toString();
            }
            return null;
        }
        if (node instanceof MemberExpression me && !me.computed()
            && me.property() instanceof Identifier pid) {
            String base = memberChainName(me.object());
            if (base != null) return base + "." + pid.name();
        }
        return null;
    }

    /**
     * Like {@link #memberChainName} but returns the dotted suffix with a
     * leading dot when the chain root is unidentified (e.g.
     * {@code .constructor.prototype} for {@code f().constructor.prototype}).
     * LibJS uses this format for GetById's diagnostic annotation when the
     * base register doesn't trace back to a named source.
     */
    private String memberChainNameOrSuffix(Node node) {
        String name = memberChainName(node);
        if (name != null) return name;
        if (node instanceof MemberExpression me) {
            return memberChainSuffix(me);
        }
        return null;
    }

    private static String memberChainSuffix(MemberExpression me) {
        if (me.computed() || !(me.property() instanceof Identifier pid)) return null;
        String head = "." + pid.name();
        if (me.object() instanceof MemberExpression inner) {
            String innerSuffix = memberChainSuffix(inner);
            if (innerSuffix == null) return null;
            return innerSuffix + head;
        }
        // Inner base is some non-MemberExpression expression (Call, New, etc.)
        return head;
    }

    /**
     * If a computed member's property is a string literal that's NOT a
     * canonical array index (e.g. {@code "x"} or {@code "break"} but not
     * {@code "0"}), return the string so the caller can fold to the
     * non-computed {@code GetById}/{@code PutById} path. Returns null
     * otherwise.
     *
     * <p>Matches LibJS: {@code o["x"]} → GetById, {@code o["0"]} →
     * GetByValue (preserved as array-index access). The fold is gated on
     * the ECMAScript {@code IsArrayIndex} predicate — non-negative integer
     * less than 2³²−1, in canonical decimal form.
     */
    /**
     * Render a number/boolean/null literal as it appears inside the
     * `<object>[…]` annotation in a Call dump — used when a method call
     * uses a computed key whose value is a non-string literal. Returns null
     * if the property isn't a foldable literal (e.g. a Symbol expression,
     * an identifier, or a side-effecting call).
     */
    private String computedKeyLiteralString(Node propertyNode) {
        if (propertyNode instanceof Identifier id) {
            // For an identifier reference (like `[sym1]`), LibJS dumps the
            // bare identifier name inside the brackets.
            return id.name();
        }
        if (!(propertyNode instanceof Literal lit)) return null;
        Object v = literalValue(lit);
        // LibJS only renders numeric literals inline. Null, undefined, and
        // boolean literals fall back to the `<object>` placeholder.
        if (v instanceof Integer i) return Integer.toString(i);
        if (v instanceof Long l) return Long.toString(l);
        if (v instanceof Double d) {
            if (d == d.longValue() && !Double.isInfinite(d)) return Long.toString(d.longValue());
            return Double.toString(d);
        }
        return null;
    }

    private String foldedComputedMemberName(Node propertyNode) {
        if (propertyNode instanceof Literal lit) {
            Object v = literalValue(lit);
            if (v instanceof String s && !isCanonicalArrayIndex(s)) {
                // LibJS still registers the literal string in the constant
                // pool when the access is folded — it pre-registers literals
                // at parse time, then the optimizer rewrites the op without
                // removing the pool entry. We have to mirror this so the
                // pool layouts agree.
                constant(s);
                return s;
            }
        }
        return null;
    }

    private static boolean isCanonicalArrayIndex(String s) {
        int len = s.length();
        if (len == 0) return false;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        if (s.charAt(0) == '0' && len > 1) return false;   // no leading zeros
        try {
            long n = Long.parseLong(s);
            return n < 0xFFFFFFFFL;   // strictly less than 2^32 - 1
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Operand lowerMemberExpression(MemberExpression me) {
        // ECMA-262 § 13.3.7 SuperProperty — `super.X` / `super[X]`. The base
        // value is GetSuperBase() = HomeObject.[[Prototype]]. We approximate
        // by reading the captured super-constructor's `.prototype` (works
        // for instance methods where HomeObject is the class's prototype;
        // static-method super-property is a v1 limitation).
        if (me.object() instanceof Super) {
            Variable.Register superCtor = allocRegister();
            emit(new Op.GetSuperConstructor(superCtor));
            Variable.Register superBase = allocRegister();
            emit(new Op.GetById(superBase, superCtor, "prototype", null,
                new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            release(superCtor);
            Variable.Register dst = allocRegister();
            String foldedName = me.computed() ? foldedComputedMemberName(me.property()) : null;
            if (me.computed() && foldedName == null) {
                Operand prop = lowerExpression(me.property());
                emit(new Op.GetByValue(dst, superBase, prop, null));
                release(prop);
            } else {
                String name = foldedName != null ? foldedName : nonComputedMemberName(me.property());
                emit(new Op.GetById(dst, superBase, name, null,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            }
            release(superBase);
            return dst;
        }
        Operand base = lowerExpression(me.object());
        if (me.optional()) emitOptionalGuard(base);
        // For diagnostic dump annotations (`(obj.foo)`), include leading-dot
        // suffix chains (`.foo.bar`) when the chain root is anonymous — LibJS
        // does this so `f().g.h` annotates as `(.g.h)`.
        String baseIdForExpr = memberChainNameOrSuffix(me.object());
        Variable.Register dst;
        // String-literal computed access folds to the non-computed path.
        String foldedName = me.computed() ? foldedComputedMemberName(me.property()) : null;
        if (me.computed() && foldedName == null) {
            // Computed: lower the property FIRST, then allocate dst — LibJS
            // emits `GetByValue dst:<higher>, base:<low>, property:<low>` so dst
            // gets a register higher than the property's. Allocating dst after
            // prop yields that ordering.
            Operand prop = lowerExpression(me.property());
            dst = allocRegister();
            emit(new Op.GetByValue(dst, base, prop, baseIdForExpr));
            release(prop);
        } else {
            String name = foldedName != null ? foldedName : nonComputedMemberName(me.property());
            dst = allocRegister();
            // LibJS specializes `.length` to a dedicated opcode.
            if ("length".equals(name)) {
                emit(new Op.GetLength(dst, base, baseIdForExpr,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            } else {
                emit(new Op.GetById(dst, base, name, baseIdForExpr,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            }
        }
        release(base);
        return dst;
    }

    /** Resolve a non-computed member's property to its string name; preserves {@code #} for privates. */
    private static String nonComputedMemberName(Node propNode) {
        if (propNode instanceof Identifier id) return id.name();
        if (propNode instanceof PrivateIdentifier pid) return "#" + pid.name();
        throw new IllegalStateException(
            "Non-computed member must have Identifier or PrivateIdentifier property; got "
            + propNode.getClass().getSimpleName());
    }

    private static String compoundBinaryOpFor(String compoundOp) {
        return switch (compoundOp) {
            case "+="    -> "+";   case "-="   -> "-";   case "*="   -> "*";
            case "/="    -> "/";   case "%="   -> "%";   case "**="  -> "**";
            case "&="    -> "&";   case "|="   -> "|";   case "^="   -> "^";
            case "<<="   -> "<<";  case ">>="  -> ">>";  case ">>>=" -> ">>>";
            default      -> null;
        };
    }

    /**
     * x compound= y → x = x op y. Implementation: synthesize a BinaryExpression
     * (lhs, op, rhs) and an AssignmentExpression (=, lhs, that), then lower.
     */
    private Operand lowerCompoundAssignment(Expression lhs, String binaryOp, Expression rhs) {
        // For MemberExpression LHS, naive desugar (`x[prop] = x[prop] op rhs`)
        // re-evaluates `x` and `prop` twice — calling `prop.toString()` twice
        // for computed keys, which is observable. LibJS evaluates base+prop
        // once and reuses them for read and write. Mirror that:
        //   base   = eval(me.object)
        //   prop   = eval(me.property)         (computed)
        //   tmp    = GetBy{Id,Value}(base, prop)
        //   tmp    = tmp <binaryOp> rhs
        //   PutBy{Id,Value}(base, prop, tmp)
        if (lhs instanceof MemberExpression me) {
            Operand base = lowerExpression(me.object());
            String foldedName = me.computed() ? foldedComputedMemberName(me.property()) : null;
            Operand prop = null;
            if (me.computed() && foldedName == null) {
                prop = lowerExpression(me.property());
            }
            // Read current value via the cached base/prop.
            Variable.Register currVal = allocRegister();
            String baseAnno = memberChainNameOrSuffix(me.object());
            if (me.computed() && foldedName == null) {
                emit(new Op.GetByValue(currVal, base, prop, baseAnno));
            } else {
                String name = foldedName != null ? foldedName : nonComputedMemberName(me.property());
                emit(new Op.GetById(currVal, base, name, baseAnno,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache()));
            }
            // copy_if_needed_to_preserve_evaluation_order: rhs evaluation may
            // mutate prop's binding (computed key may be a global), so cache
            // prop in a fresh register before lowering rhs. Matches LibJS.
            Operand propForWrite = prop;
            if (me.computed() && foldedName == null && prop instanceof Variable.Register pr) {
                Variable.Register propCopy = allocRegister();
                emit(new Op.Mov(propCopy, pr));
                propForWrite = propCopy;
            }
            // Compute new value: currVal <op> rhs.
            Operand rhsVal = lowerExpression(rhs);
            Variable.Register newVal = allocRegister();
            Op binOp = switch (binaryOp) {
                case "+"   -> new Op.Add(newVal, currVal, rhsVal);
                case "-"   -> new Op.Sub(newVal, currVal, rhsVal);
                case "*"   -> new Op.Mul(newVal, currVal, rhsVal);
                case "/"   -> new Op.Div(newVal, currVal, rhsVal);
                case "%"   -> new Op.Mod(newVal, currVal, rhsVal);
                case "**"  -> new Op.Exp(newVal, currVal, rhsVal);
                case "&"   -> new Op.BitwiseAnd(newVal, currVal, rhsVal);
                case "|"   -> new Op.BitwiseOr(newVal, currVal, rhsVal);
                case "^"   -> new Op.BitwiseXor(newVal, currVal, rhsVal);
                case "<<"  -> new Op.LeftShift(newVal, currVal, rhsVal);
                case ">>"  -> new Op.RightShift(newVal, currVal, rhsVal);
                case ">>>" -> new Op.UnsignedRightShift(newVal, currVal, rhsVal);
                default    -> throw new UnsupportedOperationException(
                    "Generator: compound assignment operator '" + binaryOp + "=' not supported");
            };
            emit(binOp);
            release(rhsVal);
            release(currVal);
            // Write back via cached base/prop. When prop was Mov-cached into
            // a fresh register (the copy_if_needed case), LibJS omits the
            // base-chain annotation on PutByValue — the dump shows just
            // `PutByValue base:..., property:..., src:..., kind:Normal`
            // without the trailing `(base[reg])` annotation.
            if (me.computed() && foldedName == null) {
                String putAnno = (propForWrite != prop) ? null : baseAnno;
                emit(new Op.PutByValue(base, propForWrite, newVal, Op.PutByValueKind.NORMAL, putAnno));
                if (propForWrite != prop) release(propForWrite);
                release(prop);
            } else {
                String name = foldedName != null ? foldedName : nonComputedMemberName(me.property());
                emit(new Op.PutById(base, name, newVal,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache(),
                    baseAnno, Op.PutByIdKind.NORMAL));
            }
            release(base);
            return newVal;
        }
        // Identifier LHS: the naive desugar is correct (no observable double-eval).
        BinaryExpression combined = new BinaryExpression(0, 0, 0, 0, 0, 0, lhs, binaryOp, rhs);
        AssignmentExpression desugared = new AssignmentExpression(0, 0, 0, 0, 0, 0, "=", lhs, combined);
        return lowerAssignment(desugared);
    }

    /**
     * Logical compound assignment: lhs &&= rhs only assigns rhs when lhs is truthy;
     * lhs ||= rhs assigns when falsy; lhs ??= rhs assigns when nullish.
     * Lowered as a logical expression of (lhs op= rhs) — see spec.
     */
    private Operand lowerLogicalAssign(Expression lhs, Expression rhs, String logicalOp) {
        // We compute lhs once. If short-circuits to lhs's value, no assignment.
        // Otherwise, evaluate rhs and assign.
        // Implementation: equivalent to `lhs op= rhs` desugared as
        //   t = lhs;  if (op-condition) { lhs = rhs; t = rhs; }  yield t.
        Operand lhsVal = lowerExpression(lhs);
        Variable.Register dst = allocRegister();
        emit(new Op.Mov(dst, lhsVal));
        // No release(lhsVal) yet — we may need it for the assignment branch.

        int skipPc = switch (logicalOp) {
            case "&&" -> emit(new Op.JumpFalse(dst, /* placeholder */ -1));
            case "||" -> emit(new Op.JumpTrue (dst, /* placeholder */ -1));
            case "??" -> emitJumpNotNullish(dst);
            default   -> throw new IllegalStateException("unknown logical op: " + logicalOp);
        };
        // Branch taken: assign rhs to lhs.
        Operand rhsVal = lowerExpression(rhs);
        // Use the regular assignment path.
        AssignmentExpression assign = new AssignmentExpression(0, 0, 0, 0, 0, 0, "=", lhs, rhs);
        // We've already lowered rhs; we need the assignment but without re-lowering rhs.
        // Simpler: emit a Mov to dst and write back to lhs target.
        emit(new Op.Mov(dst, rhsVal));
        writeBackAnyTarget(lhs, dst);
        release(rhsVal);
        // (assign is unused; constructed to silence "unused" warnings — drop it.)
        ((Object) assign).hashCode();

        patchJumpTarget(skipPc, currentPc());
        release(lhsVal);
        return dst;
    }

    /** Write {@code value} back to any assignment target (Identifier or MemberExpression). */
    private void writeBackAnyTarget(Expression target, Variable.Register valueReg) {
        writeBackAnyTarget(target, valueReg, /* annotateBase */ true);
    }

    private void writeBackAnyTarget(Expression target, Variable.Register valueReg,
                                    boolean annotateBase) {
        if (target instanceof Identifier id) {
            writeBackBinding(id, valueReg);
        } else if (target instanceof MemberExpression me) {
            Operand base = lowerExpression(me.object());
            String foldedName = me.computed() ? foldedComputedMemberName(me.property()) : null;
            String baseAnno = annotateBase ? memberChainNameOrSuffix(me.object()) : null;
            if (me.computed() && foldedName == null) {
                Operand prop = lowerExpression(me.property());
                emit(new Op.PutByValue(base, prop, valueReg, Op.PutByValueKind.NORMAL, baseAnno));
                release(prop);
            } else {
                String name = foldedName != null ? foldedName : nonComputedMemberName(me.property());
                emit(new Op.PutById(base, name, valueReg,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache(),
                    baseAnno, Op.PutByIdKind.NORMAL));
            }
            release(base);
        } else {
            throw new UnsupportedOperationException(
                "Generator: assignment target type " + target.getClass().getSimpleName() + " not supported");
        }
    }

    /** Emit the appropriate Set/Mov for writing {@code valueReg} back to a binding {@code id}. */
    private void writeBackBinding(Identifier id, Variable.Register valueReg) {
        if (globalNames.contains(id.name())) {
            emit(new Op.SetGlobal(id.name(), valueReg, new GlobalVariableCache()));
        } else {
            Integer slot = locals.get(id.name());
            if (slot != null) {
                emit(new Op.Mov(new Variable.Local(slot), valueReg));
            } else {
                // Sloppy-mode implicit global: write to a property on
                // globalThis. LibJS uses SetGlobal regardless of whether the
                // name was previously declared.
                emit(new Op.SetGlobal(id.name(), valueReg, new GlobalVariableCache()));
            }
        }
    }

    private Object literalValue(Literal lit) {
        Object v = lit.value();
        // ESTree: a Literal with value=null and raw="null" is the JS `null` literal.
        // (JS `undefined` is an Identifier expression, not a Literal — handled in lowerExpression.)
        if (v == null) return null;
        // ESTree numeric literals come through as Integer/Long/Double; normalize to Double for arithmetic.
        if (v instanceof Integer i)    return (double) (int) i;
        if (v instanceof Long l)       return (double) (long) l;
        return v;
    }

    private Operand lowerBinary(BinaryExpression bin) {
        // For SHIFT ops with a NUMERIC literal operand: LibJS pre-converts
        // the literal via ToInt32 (lhs) / ToUInt32 (rhs) and interns the
        // CONVERTED value, NOT the original. So `2147483648.1 << 0` enters
        // the constants pool as `Int32(-2147483648)` and `Int32(0)`, never
        // the original `Double(2147483648.1)`. (codegen.rs:461-503.)
        // Apply the same conversion here so our pool matches.
        String binOpName = bin.operator();
        boolean isShift = "<<".equals(binOpName) || ">>".equals(binOpName) || ">>>".equals(binOpName);
        Operand l;
        Operand r;
        if (isShift && bin.left() instanceof Literal lLit && literalValue(lLit) instanceof Number) {
            l = constant((double) AbstractOps.toInt32(literalValue(lLit)));
        } else {
            l = lowerExpression(bin.left());
        }
        if (isShift && bin.right() instanceof Literal rLit && literalValue(rLit) instanceof Number) {
            // ToUInt32 → unsigned 32-bit. Store as Double in pool (LibJS does
            // the same — small-uint values still get Int32-deduped via the
            // add_constant_number path).
            int n = AbstractOps.toInt32(literalValue(rLit));
            long u = ((long) n) & 0xFFFFFFFFL;
            r = constant((double) u);
        } else {
            r = lowerExpression(bin.right());
        }

        // Try compile-time fold when both operands resolved to constants.
        Object folded = tryFoldBinary(bin.operator(), l, r);
        if (folded != null) return constant(folded);

        Variable.Register dst = allocRegister();
        Op op = switch (bin.operator()) {
            case "+"    -> new Op.Add(dst, l, r);
            case "-"    -> new Op.Sub(dst, l, r);
            case "*"    -> new Op.Mul(dst, l, r);
            case "/"    -> new Op.Div(dst, l, r);
            case "%"    -> new Op.Mod(dst, l, r);
            case "**"   -> new Op.Exp(dst, l, r);
            case "&"    -> new Op.BitwiseAnd(dst, l, r);
            case "|"    -> new Op.BitwiseOr (dst, l, r);
            case "^"    -> new Op.BitwiseXor(dst, l, r);
            case "<<"   -> new Op.LeftShift(dst, l, r);
            case ">>"   -> new Op.RightShift(dst, l, r);
            case ">>>"  -> new Op.UnsignedRightShift(dst, l, r);
            case "<"    -> new Op.LessThan(dst, l, r);
            case "<="   -> new Op.LessThanEquals(dst, l, r);
            case ">"    -> new Op.GreaterThan(dst, l, r);
            case ">="   -> new Op.GreaterThanEquals(dst, l, r);
            case "==="  -> new Op.StrictlyEquals(dst, l, r);
            case "!=="  -> new Op.StrictlyInequals(dst, l, r);
            case "=="   -> new Op.LooselyEquals(dst, l, r);
            case "!="   -> new Op.LooselyInequals(dst, l, r);
            case "instanceof" -> new Op.Instanceof(dst, l, r);
            case "in"   -> new Op.In(dst, l, r);
            default -> throw new UnsupportedOperationException(
                "Generator: binary operator '" + bin.operator() + "' is not yet supported");
        };
        emit(op);
        // Release in reverse-allocation order so the LIFO pool's top register
        // is the LHS slot — next allocation in this scope reuses it. Matches
        // LibJS's release pattern.
        release(r);
        release(l);
        return dst;
    }

    /**
     * Compile-time fold a binary op on constant operands. Returns the folded
     * value, or {@code null} if the inputs aren't both constants or the
     * operator can't be folded safely.
     */
    private Object tryFoldBinary(String operator, Operand lhs, Operand rhs) {
        if (!(lhs instanceof Operand.Constant lc) || !(rhs instanceof Operand.Constant rc)) return null;
        Object lv = constants.get(lc.index());
        Object rv = constants.get(rc.index());
        // String concatenation with `+` — fold when either operand is a
        // string (matches LibJS, which folds e.g. `"...: " + NaN` to
        // `"...: NaN"` at compile time).
        if ("+".equals(operator) && (lv instanceof String || rv instanceof String)) {
            return AbstractOps.toString(lv) + AbstractOps.toString(rv);
        }
        // Strict / loose equality on same-typed string operands: fold to Bool.
        // For same-typed values, == and === produce identical results, so
        // both are foldable here (matches LibJS).
        if (("===".equals(operator) || "!==".equals(operator)
             || "==".equals(operator)  || "!=".equals(operator))
            && lv instanceof String ls && rv instanceof String rs) {
            boolean eq = ls.equals(rs);
            return ("===".equals(operator) || "==".equals(operator)) ? eq : !eq;
        }
        // Same for booleans.
        if (("===".equals(operator) || "!==".equals(operator)
             || "==".equals(operator)  || "!=".equals(operator))
            && lv instanceof Boolean lb && rv instanceof Boolean rb) {
            boolean eq = lb.equals(rb);
            return ("===".equals(operator) || "==".equals(operator)) ? eq : !eq;
        }
        // Strict equality where either side is null/Undefined and the other
        // isn't the same-singleton: the spec gives a definite answer.
        if (("===".equals(operator) || "!==".equals(operator))) {
            boolean lvNull = (lv == null);
            boolean rvNull = (rv == null);
            boolean lvUndef = (lv == Undefined.VALUE);
            boolean rvUndef = (rv == Undefined.VALUE);
            if (lvNull || rvNull || lvUndef || rvUndef) {
                boolean eq = (lvNull && rvNull) || (lvUndef && rvUndef);
                return "===".equals(operator) ? eq : !eq;
            }
        }
        // Strict equality on different types: always false (=== ) / true (!==).
        if (("===".equals(operator) || "!==".equals(operator))
            && lv != null && rv != null
            && lv.getClass() != rv.getClass()
            && !(lv instanceof Double && rv instanceof Double)) {
            // Loose check: same-class is the requirement. Doubles are coerced
            // by the dl/dr branch below so don't short-circuit here.
            return "===".equals(operator) ? false : true;
        }
        // Bitwise ops: both operands ToInt32. ToInt32(null)=0, ToInt32(undefined)=0,
        // ToInt32(bool)=int(b), ToInt32(double)=trunc. So we can fold any pair
        // of those primitive types.
        if ("&".equals(operator) || "|".equals(operator) || "^".equals(operator)
            || "<<".equals(operator) || ">>".equals(operator) || ">>>".equals(operator)) {
            if (canCoerceToInt32(lv) && canCoerceToInt32(rv)) {
                return switch (operator) {
                    case "&"   -> AbstractOps.bitwiseAnd(lv, rv);
                    case "|"   -> AbstractOps.bitwiseOr(lv, rv);
                    case "^"   -> AbstractOps.bitwiseXor(lv, rv);
                    case "<<"  -> AbstractOps.leftShift(lv, rv);
                    case ">>"  -> AbstractOps.rightShift(lv, rv);
                    case ">>>" -> AbstractOps.unsignedRightShift(lv, rv);
                    default    -> null;
                };
            }
        }
        // For arithmetic / relational ops we can safely coerce null/undefined/
        // bool via ToNumber (pure conversion). For == / != we MUST NOT — the
        // == operator gives `null == undefined` ⇒ true while `ToNumber(null)
        // == ToNumber(undefined)` ⇒ `0 == NaN` ⇒ false. Strict equality on
        // null/undefined is already handled above; reaching here means we've
        // been routed past those short-circuits.
        boolean numericOnly = "==".equals(operator) || "!=".equals(operator);
        Double dl = numericOnly ? toDoubleOrNull(lv) : toDoubleOrNullPermissive(lv);
        Double dr = numericOnly ? toDoubleOrNull(rv) : toDoubleOrNullPermissive(rv);
        if (dl == null || dr == null) return null;
        return switch (operator) {
            case "+"    -> dl + dr;
            case "-"    -> dl - dr;
            case "*"    -> dl * dr;
            case "/"    -> dl / dr;
            case "%"    -> dl % dr;
            case "**"   -> Math.pow(dl, dr);
            case "<"    -> dl <  dr;
            case "<="   -> dl <= dr;
            case ">"    -> dl >  dr;
            case ">="   -> dl >= dr;
            case "==="  -> dl.doubleValue() == dr.doubleValue();
            case "!=="  -> dl.doubleValue() != dr.doubleValue();
            case "=="   -> dl.doubleValue() == dr.doubleValue();
            case "!="   -> dl.doubleValue() != dr.doubleValue();
            default    -> null;
        };
    }

    /** ToNumber-style coercion: null→0, undefined→NaN, bool→0/1, else null. */
    private static Double toDoubleOrNullPermissive(Object v) {
        if (v == null) return 0.0;
        if (v == Undefined.VALUE) return Double.NaN;
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        return toDoubleOrNull(v);
    }

    /**
     * AST-level fold: when a BinaryExpression's operands are both Literals
     * we can try the same fold the lowering path would do, without lowering
     * either operand. Returns the folded value, or {@code null} if it can't
     * be folded statically (e.g. operand isn't a literal, fold returns null,
     * fold uses string coercion, etc.).
     */
    private Object tryFoldLiteralBinary(BinaryExpression bin) {
        if (!(bin.left() instanceof Literal lit1) || !(bin.right() instanceof Literal lit2)) {
            return null;
        }
        Object lv = literalValue(lit1);
        Object rv = literalValue(lit2);
        // Build temporary Constant operands; they don't pollute the pool here
        // because tryFoldBinary only reads constants[index] when given an
        // Operand.Constant — we bypass that by inlining the fold.
        return tryFoldBinaryValues(bin.operator(), lv, rv);
    }

    /** Inline of tryFoldBinary that takes raw values instead of constants. */
    private Object tryFoldBinaryValues(String operator, Object lv, Object rv) {
        if ("+".equals(operator) && (lv instanceof String || rv instanceof String)) {
            return AbstractOps.toString(lv) + AbstractOps.toString(rv);
        }
        if (("===".equals(operator) || "!==".equals(operator)
             || "==".equals(operator)  || "!=".equals(operator))
            && lv instanceof String ls && rv instanceof String rs) {
            boolean eq = ls.equals(rs);
            return ("===".equals(operator) || "==".equals(operator)) ? eq : !eq;
        }
        if (("===".equals(operator) || "!==".equals(operator)
             || "==".equals(operator)  || "!=".equals(operator))
            && lv instanceof Boolean lb && rv instanceof Boolean rb) {
            boolean eq = lb.equals(rb);
            return ("===".equals(operator) || "==".equals(operator)) ? eq : !eq;
        }
        if (("===".equals(operator) || "!==".equals(operator))) {
            boolean lvNull = (lv == null);
            boolean rvNull = (rv == null);
            boolean lvUndef = (lv == Undefined.VALUE);
            boolean rvUndef = (rv == Undefined.VALUE);
            if (lvNull || rvNull || lvUndef || rvUndef) {
                boolean eq = (lvNull && rvNull) || (lvUndef && rvUndef);
                return "===".equals(operator) ? eq : !eq;
            }
        }
        if (("===".equals(operator) || "!==".equals(operator))
            && lv != null && rv != null
            && lv.getClass() != rv.getClass()
            && !(lv instanceof Double && rv instanceof Double)) {
            return "===".equals(operator) ? false : true;
        }
        if ("&".equals(operator) || "|".equals(operator) || "^".equals(operator)
            || "<<".equals(operator) || ">>".equals(operator) || ">>>".equals(operator)) {
            if (canCoerceToInt32(lv) && canCoerceToInt32(rv)) {
                return switch (operator) {
                    case "&"   -> AbstractOps.bitwiseAnd(lv, rv);
                    case "|"   -> AbstractOps.bitwiseOr(lv, rv);
                    case "^"   -> AbstractOps.bitwiseXor(lv, rv);
                    case "<<"  -> AbstractOps.leftShift(lv, rv);
                    case ">>"  -> AbstractOps.rightShift(lv, rv);
                    case ">>>" -> AbstractOps.unsignedRightShift(lv, rv);
                    default    -> null;
                };
            }
        }
        boolean numericOnly = "==".equals(operator) || "!=".equals(operator);
        Double dl = numericOnly ? toDoubleOrNull(lv) : toDoubleOrNullPermissive(lv);
        Double dr = numericOnly ? toDoubleOrNull(rv) : toDoubleOrNullPermissive(rv);
        if (dl == null || dr == null) return null;
        return switch (operator) {
            case "+"    -> dl + dr;
            case "-"    -> dl - dr;
            case "*"    -> dl * dr;
            case "/"    -> dl / dr;
            case "%"    -> dl % dr;
            case "**"   -> Math.pow(dl, dr);
            case "<"    -> dl <  dr;
            case "<="   -> dl <= dr;
            case ">"    -> dl >  dr;
            case ">="   -> dl >= dr;
            case "==="  -> dl.doubleValue() == dr.doubleValue();
            case "!=="  -> dl.doubleValue() != dr.doubleValue();
            case "=="   -> dl.doubleValue() == dr.doubleValue();
            case "!="   -> dl.doubleValue() != dr.doubleValue();
            default    -> null;
        };
    }

    /** Operands whose ToInt32 is well-defined and pure for compile-time folding. */
    private static boolean canCoerceToInt32(Object v) {
        return v == null
            || v == Undefined.VALUE
            || v instanceof Boolean
            || v instanceof Integer
            || v instanceof Long
            || v instanceof Double;
    }

    private static Double toDoubleOrNull(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Integer i) return i.doubleValue();
        if (v instanceof Long l) return l.doubleValue();
        return null;
    }

    private Operand lowerAssignment(AssignmentExpression assign) {
        // Compound assignment: x op= y → x = x op y. Desugar before dispatch.
        // Logical compound (||=, &&=, ??=) have short-circuit semantics — only
        // assigns when the lhs is falsy / truthy / nullish — handle separately.
        String op = assign.operator();
        if (!"=".equals(op)) {
            // Compound forms only apply when LHS is an expression (not a destructuring pattern).
            if (!(assign.left() instanceof Expression lhsExpr)) {
                throw new UnsupportedOperationException(
                    "Generator: compound assignment to destructuring patterns not supported");
            }
            String binaryOp = compoundBinaryOpFor(op);
            if (binaryOp != null) {
                return lowerCompoundAssignment(lhsExpr, binaryOp, assign.right());
            }
            return switch (op) {
                case "&&=" -> lowerLogicalAssign(lhsExpr, assign.right(), "&&");
                case "||=" -> lowerLogicalAssign(lhsExpr, assign.right(), "||");
                case "??=" -> lowerLogicalAssign(lhsExpr, assign.right(), "??");
                default -> throw new UnsupportedOperationException(
                    "Generator: assignment operator '" + op + "' is not yet supported");
            };
        }
        // Destructuring assignment: `[a, b] = arr` or `{a, b} = obj`.
        if (assign.left() instanceof ObjectPattern || assign.left() instanceof ArrayPattern) {
            Operand value = lowerExpression(assign.right());
            bindPattern(assign.left(), value, BindMode.ASSIGN);
            return value;
        }
        // `obj.prop = value` and `obj[key] = value`.
        if (assign.left() instanceof MemberExpression me) {
            Operand base = lowerExpression(me.object());
            // Computed key: lower it BEFORE the value (matches LibJS, which
            // walks the LHS left-to-right and interns key constants ahead of
            // value constants in the pool). Non-computed keys don't allocate
            // a register, so we still defer their fold until after the value.
            String foldedName = me.computed() ? foldedComputedMemberName(me.property()) : null;
            Operand prop = null;
            if (me.computed() && foldedName == null) {
                prop = lowerExpression(me.property());
            }
            Operand value = lowerExpression(assign.right());
            if (me.computed() && foldedName == null) {
                emit(new Op.PutByValue(base, prop, value, Op.PutByValueKind.NORMAL,
                    memberChainNameOrSuffix(me.object())));
                release(prop);
            } else {
                String name = foldedName != null ? foldedName : nonComputedMemberName(me.property());
                emit(new Op.PutById(base, name, value,
                    new com.jimmyhmiller.harmonica.bytecode.cache.PropertyLookupCache(),
                    memberChainNameOrSuffix(me.object()), Op.PutByIdKind.NORMAL));
            }
            release(base);
            return value;
        }
        if (!(assign.left() instanceof Identifier id)) {
            throw new UnsupportedOperationException(
                "Generator: only Identifier and MemberExpression targets are supported in assignment; got "
                + assign.left().getClass().getSimpleName());
        }
        // ES2015 NamedEvaluation: `name = function() {...}` (or arrow / class)
        // gives the anonymous value its `.name = "name"`. Same hint mechanism
        // as lowerVarDecl uses for var-init.
        if (assign.right() instanceof FunctionExpression fe && fe.id() == null) {
            pendingFunctionName = id.name();
        } else if (assign.right() instanceof ArrowFunctionExpression) {
            pendingFunctionName = id.name();
        } else if (assign.right() instanceof ClassExpression ce && ce.id() == null) {
            pendingFunctionName = id.name();
        }
        Operand value = lowerExpression(assign.right());
        pendingFunctionName = null;
        // Already-captured name: write through the capture's local slot.
        // Return `value` (the assigned source) — LibJS treats the assignment
        // expression's value as the rhs's register/constant, avoiding a
        // redundant load from the local for subsequent reads.
        Integer captured = captureSlot.get(id.name());
        if (captured != null) {
            emit(new Op.Mov(new Variable.Local(captured), value));
            return value;
        }
        // Local in this scope?
        Integer slot = locals.get(id.name());
        if (slot != null) {
            emit(new Op.Mov(new Variable.Local(slot), value));
            return value;
        }
        // Try capturing from outer scope.
        int captureLocalSlot = captureFromOuter(id.name());
        if (captureLocalSlot >= 0) {
            emit(new Op.Mov(new Variable.Local(captureLocalSlot), value));
            return value;
        }
        // Fall back to a global write. SetGlobal will ReferenceError at runtime
        // if no binding exists (sloppy-mode would create a global; strict mode
        // would throw). Emitting unconditionally matches spec resolution.
        emit(new Op.SetGlobal(id.name(), value, new GlobalVariableCache()));
        return value;
    }
}
