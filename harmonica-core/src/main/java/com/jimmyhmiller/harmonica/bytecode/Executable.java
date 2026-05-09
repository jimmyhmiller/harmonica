package com.jimmyhmiller.harmonica.bytecode;

/**
 * A compiled function/script/module: a linear array of {@link Op}s plus the
 * supporting tables (constant pool, exception handlers, etc.).
 *
 * <p>Instructions are addressed by integer PC (index into {@link #ops}).
 * There is no byte stream.
 *
 * <p>Skeleton — many fields from the spec ({@code identifierTable},
 * {@code propertyKeyTable}, {@code stringTable}, {@code regexTable},
 * {@code sharedFunctionData}, {@code classBlueprints}, {@code sourceMap})
 * are omitted in v1 and will be added as features land.
 */
public final class Executable {

    private final Op[] ops;
    private final int numberOfRegisters;
    private final int numberOfLocals;
    private final String[] localNames;
    private final Object[] constants;
    private final ExceptionHandler[] exceptionHandlers;
    private final boolean strictMode;

    /**
     * Per-{@link com.jimmyhmiller.harmonica.bytecode.Op.NewFunction
     * NewFunction} table: nested function bodies referenced by index.
     * {@code sharedFunctionData[i]} is the {@link JSFunction} template
     * {@code NewFunction(..., sharedFunctionDataIndex=i, ...)} instantiates.
     */
    private final JSFunction[] sharedFunctionData;

    /**
     * Function declarations hoisted to the script's top level. At script
     * invocation, each is materialized as a JSFunction value and bound in the
     * global lexical environment under {@code name} before the bytecode runs.
     * Matches LibJS's "no body ops for hoisted FunctionDeclaration" pattern.
     *
     * <p>Hoisted templates are kept here, separate from {@link #sharedFunctionData},
     * so they don't consume index slots that {@link Op.NewFunction} addresses —
     * LibJS only counts runtime-created functions in its {@code shared_function_data}
     * table.
     */
    private final HoistedFunction[] hoistedFunctions;
    public HoistedFunction[] hoistedFunctions() { return hoistedFunctions; }
    public record HoistedFunction(String name, JSFunction template) {}

    /**
     * Top-level {@code var} names hoisted to the global environment. Each is
     * pre-bound to {@code undefined} at script-load time. Initializers
     * ({@code var x = expr}) emit a separate {@code SetGlobal} op in the
     * body — we don't emit anything at the declaration site.
     */
    private final String[] hoistedVarNames;
    public String[] hoistedVarNames() { return hoistedVarNames; }

    /**
     * Per-{@link Op.NewClass NewClass} table: class definitions referenced by
     * index. Each blueprint carries enough info for the runtime to
     * materialize the class object (constructor template, methods, fields,
     * super-class reference, etc.).
     */
    private final ClassBlueprint[] classBlueprints;
    public ClassBlueprint[] classBlueprints() { return classBlueprints; }

    /**
     * Reference data used by {@link Op.NewClass} at runtime. The constructor
     * and member templates are referenced by index into
     * {@link #sharedFunctionData} — same as {@link Op.NewFunction} — so
     * NewFunction indices in the dump count classes' inner functions too.
     * (LibJS works this way; otherwise NewFunction's index numbering would
     * skip past a class's constructor and methods.)
     */
    public record ClassBlueprint(
        String name,
        int constructorIndex,
        ClassMember[] members,
        boolean hasSuper
    ) {}

    /**
     * One member of a class blueprint. {@code kind} is one of
     * {@code "method"}, {@code "get"}, {@code "set"}, or {@code "field"};
     * {@code isStatic} marks static members. {@code templateIndex} is the
     * sharedFunctionData index for methods/accessors; for fields it's -1
     * (no template).
     */
    public record ClassMember(
        String kind,
        String key,
        boolean isStatic,
        int templateIndex,
        // For kind="field" with a literal initializer, the pre-computed value
        // (Double / String / Boolean / null). Null otherwise. Non-literal
        // initializers aren't yet wired through at runtime.
        Object literalInitValue,
        // Index into the {@code Op.NewClass.elementKeys} array. Used at
        // runtime to resolve computed keys ({@code class { [k]() {} }}) —
        // when {@link #key()} is null, the runtime retrieves the operand at
        // {@code elementKeys[elementKeyIndex]} and ToString-s it. -1 means
        // the key is statically known via {@link #key()}.
        int elementKeyIndex
    ) {
        // Backwards-compatible constructors.
        public ClassMember(String kind, String key, boolean isStatic, int templateIndex) {
            this(kind, key, isStatic, templateIndex, null, -1);
        }
        public ClassMember(String kind, String key, boolean isStatic, int templateIndex,
                           Object literalInitValue) {
            this(kind, key, isStatic, templateIndex, literalInitValue, -1);
        }
    }

    /**
     * Block boundaries: {@code basicBlockStartPcs[i]} is the PC where block
     * {@code i} starts. Always begins with 0; each entry after that is the PC
     * of the first instruction of the next block. The last block runs to the
     * end of {@link #ops}.
     *
     * <p>Recorded by the generator at IR construction time; the disassembler
     * reads these directly rather than recovering blocks heuristically.
     */
    private final int[] basicBlockStartPcs;

    public Executable(
        Op[] ops,
        int numberOfRegisters,
        int numberOfLocals,
        String[] localNames,
        Object[] constants,
        ExceptionHandler[] exceptionHandlers,
        int[] basicBlockStartPcs,
        JSFunction[] sharedFunctionData,
        HoistedFunction[] hoistedFunctions,
        String[] hoistedVarNames,
        ClassBlueprint[] classBlueprints,
        boolean strictMode
    ) {
        this.ops = ops;
        this.numberOfRegisters = numberOfRegisters;
        this.numberOfLocals = numberOfLocals;
        this.localNames = localNames;
        this.constants = constants;
        this.exceptionHandlers = exceptionHandlers;
        this.basicBlockStartPcs = basicBlockStartPcs;
        this.sharedFunctionData = sharedFunctionData;
        this.hoistedFunctions = hoistedFunctions;
        this.hoistedVarNames = hoistedVarNames;
        this.classBlueprints = classBlueprints;
        this.strictMode = strictMode;
    }

    /** Convenience constructor: a single basic block, no locals, no nested fns. */
    public Executable(
        Op[] ops,
        int numberOfRegisters,
        Object[] constants,
        ExceptionHandler[] exceptionHandlers,
        boolean strictMode
    ) {
        this(ops, numberOfRegisters, 0, new String[0], constants, exceptionHandlers, new int[]{0},
            new JSFunction[0], new HoistedFunction[0], new String[0], new ClassBlueprint[0], strictMode);
    }

    /** Convenience constructor: a single basic block, no nested fns. */
    public Executable(
        Op[] ops,
        int numberOfRegisters,
        Object[] constants,
        ExceptionHandler[] exceptionHandlers,
        int[] basicBlockStartPcs,
        boolean strictMode
    ) {
        this(ops, numberOfRegisters, 0, new String[0], constants, exceptionHandlers, basicBlockStartPcs,
            new JSFunction[0], new HoistedFunction[0], new String[0], new ClassBlueprint[0], strictMode);
    }

    public Op[]       ops()                { return ops; }
    public int        numberOfRegisters()  { return numberOfRegisters; }
    public int        numberOfLocals()     { return numberOfLocals; }
    public String[]   localNames()         { return localNames; }
    public Object[]   constants()          { return constants; }
    public ExceptionHandler[] exceptionHandlers() { return exceptionHandlers; }
    public int[]      basicBlockStartPcs() { return basicBlockStartPcs; }
    public JSFunction[] sharedFunctionData() { return sharedFunctionData; }
    public boolean    strictMode()         { return strictMode; }

    /**
     * For generator/async-generator function bodies: the PC at which the
     * synchronous prologue (parameter destructuring, arguments-binding) ends
     * and the suspendable body begins. -1 means "no split needed" (regular
     * function — the whole body runs at call time). Set by {@link Generator}
     * after emitting parameter binding ops; consulted by
     * {@link Interpreter#invokeFunctionDispatch} to run the prologue eagerly
     * (so a destructuring throw propagates from the call site, per § 27.5.4
     * step 5 / FunctionDeclarationInstantiation).
     */
    private int prologueEndPc = -1;
    public int prologueEndPc() { return prologueEndPc; }
    public void setPrologueEndPc(int pc) { this.prologueEndPc = pc; }

    /** Number of basic blocks. */
    public int blocksCount() { return basicBlockStartPcs.length; }

    /** Return the block index that contains PC {@code pc}. */
    public int blockIndexAtPc(int pc) {
        for (int i = basicBlockStartPcs.length - 1; i >= 0; i--) {
            if (basicBlockStartPcs[i] <= pc) return i;
        }
        return 0;
    }

    /**
     * Find the innermost exception handler whose [startPc, endPc) range
     * contains the given PC. Returns the handlerPc, or -1 if none.
     */
    public int findHandlerPc(int pc) {
        // Linear scan; handler tables are short. Replace with sorted-binary-search
        // if they ever get large.
        int innermostStart = -1;
        int innermostHandler = -1;
        for (ExceptionHandler h : exceptionHandlers) {
            if (h.startPc() <= pc && pc < h.endPc() && h.startPc() > innermostStart) {
                innermostStart = h.startPc();
                innermostHandler = h.handlerPc();
            }
        }
        return innermostHandler;
    }

    public record ExceptionHandler(int startPc, int endPc, int handlerPc) {}
}
