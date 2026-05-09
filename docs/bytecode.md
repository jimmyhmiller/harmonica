# Bytecode Specification

> **Note on terminology.** We use "bytecode" because it's the conventional name for the IR a JS engine executes. Our IR is **not bytes**. It's an array of Java objects, one per instruction, indexed by an integer program counter. The shape is closer to JRuby's IR than to V8 Ignition or LibJS's serialized byte stream. See *Design principles* below for the rationale.

## Purpose

This document specifies the instruction set for the harmonica JavaScript runtime. The opcode set and operand model are adapted from Ladybird's LibJS bytecode (BSD-2-Clause) so that LibJS can serve as a behavioral oracle: same source → same observable execution.

The *representation* is adapted from JRuby's IR: instructions are objects, operands are objects, and dispatch is a virtual call — `op.interpret(ctx, pc)` — with the implementation of each opcode living on its own class. This shape pays off in cheap analyses, easy inlining, and natural Java 25 ergonomics.

We do not copy LibJS or JRuby source. Names, layouts, and semantics are documented from study of public sources, then implemented from scratch in Java.

## Scope of this document

- The Instruction / Operand split — the central design choice.
- The operand model: sealed types, reserved registers, behavior on operands.
- The instruction model: sealed `Op` types, `Operation` enum, records vs mutable-IC classes.
- The `Executable` container.
- The dispatch model — virtual `interpret` on each `Op` subclass.
- An exhaustive opcode reference.
- An oracle-test protocol against LibJS.

Out of scope (covered separately):
- Inline-cache internal layouts (`PropertyLookupCache`, `CallSite`, `ObjectShapeCache`, etc.). The instruction reference notes which opcodes carry an IC field; the IC class internals are specified elsewhere.
- Garbage collection, value representation (NaN-boxing), shapes/hidden classes.
- Standard library implementation strategy.
- AST → IR lowering rules in the generator.

## Design principles

### Instructions are objects

Each opcode is a distinct Java type. An `AddInstr` is a different class from a `SubInstr`. We do **not** encode instructions as bytes followed by operand bytes. We do not have an instruction-stream byte cursor. The runtime form of a compiled function is `Op[]` — an array of instruction objects.

Why: cheap analyses (constant folding, copy propagation, dead-code elimination, inlining) operate on this object graph by walking pointers and rewriting fields. There is no decoder. A pass like "replace every `Add(dst, c1, c2)` where both are constants with `Mov(dst, c1+c2)`" is a few lines. With a byte stream, every pass needs to know operand widths and re-encode.

### Operands are objects

An operand is a sealed Java type, not a tagged 32-bit integer. The operand kinds — `Register`, `Local`, `Constant`, `Argument` — are subtypes of a sealed `Operand` interface. Each operand carries behavior: `retrieve(InterpContext)` materializes its current value; literals know how to return themselves; variables know how to index into the frame.

This is the central departure from byte-stream designs (V8, LibJS-on-disk). It costs allocation, but it's what makes everything else easy: passes are short, inlining a callee's IR into a caller is pointer rewriting, and the type system catches "you passed a Constant where a Register destination is required."

### Dispatch is virtual — each Op implements its own interpret

The interpreter loop is:

```java
while (true) {
    pc = ops[pc].interpret(ctx, pc);
}
```

Each `Op` subclass implements `int interpret(InterpContext, int pc)` and returns the next pc. Sequential ops return `pc + 1`; jumps return their target. The semantics of each opcode live next to its operand wiring, on the same class.

```java
record AddInstr(Variable dst, Operand lhs, Operand rhs) implements Op {
    public int interpret(InterpContext ctx, int pc) {
        Object l = lhs.retrieve(ctx);
        Object r = rhs.retrieve(ctx);
        dst.store(ctx, AbstractOps.add(l, r));   // may throw AbruptCompletion
        return pc + 1;
    }
}
```

This is JRuby's original IR shape — instructions are objects, operands are objects, dispatch is a virtual call. JRuby later added a giant enum-switch (`InterpreterEngine`) for hot loops, but the virtual-dispatch path is still load-bearing in production.

We chose virtual dispatch over a central enum-switch for these reasons:

1. **Each opcode owns its semantics next to its data.** Adding a new op is one class — there is no central switch to forget to update.
2. **No 1000-line god-method.** The switch grows linearly with opcode count; virtual dispatch fans out by class.
3. **HotSpot handles this fine.** Monomorphic and bimorphic call sites inline cleanly; megamorphic sites fall back to a vtable lookup which is still a single load + indirect jump. Empirically this is not the bottleneck for our interpreter at the size we're operating at.
4. **Lowering to a tableswitch later is a non-breaking change.** If the hot loop ever profiles as megamorphic-bound, we can codegen a parallel `Operation`-enum switch dispatcher *alongside* the virtual methods. The two designs coexist.

What we explicitly do not do:

- **Pattern matching on sealed types in a hot path.** `typeSwitch` bootstrap is real overhead today.
- **A central enum-switch interpreter.** It scales poorly with opcode count and forces every opcode to know about a foreign file.
- **Computed-goto / threaded interpretation.** Java doesn't expose it.

Non-hot-path consumers (disassembler, IR passes, JIT lowering) get an `OpVisitor` interface alongside `interpret` — visitors are useful for transformation, not execution.

### Records for the pure ops, mutable-IC field for the rest

Most instructions are pure data: opcode + operand wiring with no runtime-mutating state. These are Java records.

```java
record AddInstr(Variable dst, Operand lhs, Operand rhs) implements Op {
    public Operation operation() { return Operation.ADD; }
}
```

Some instructions carry an **inline cache** — runtime-observed state that evolves as the instruction executes (e.g. property-access caches the receiver shape). Records support this naturally: a `final` record component can hold a *reference to a mutable object*. The record's identity (operand wiring) is immutable; the cache's internal state mutates.

```java
record GetByIdInstr(
    Variable dst,
    Operand base,
    PropertyKey property,
    PropertyLookupCache cache         // final ref to mutable cache
) implements Op {
    public Operation operation() { return Operation.GET_BY_ID; }
}

final class PropertyLookupCache {       // mutable
    static final int MAX_SHAPES = 4;
    Shape[] shapes = new Shape[MAX_SHAPES];
    int[] slotOffsets = new int[MAX_SHAPES];
    int entries;
    // mutator methods
}
```

This is the JRuby `CallBase.callSite` pattern. The cache class never overrides `equals`/`hashCode`; identity equality is correct (each call site is its own site). Records' auto-generated `equals` will use that identity equality on the cache field, which gives the right answer.

### One IR, multiple consumers

The same `Op[]` is consumed by:
- The interpreter (this document).
- IR optimization passes.
- A future JIT (if and when).
- The disassembler / debugger / oracle-diff tooling.

Basic-block CFG is a *transient* form built by analysis passes; before execution it's linearized back to `Op[]` with labels resolved to integer PC offsets.

## Operand model

### Sealed hierarchy

```java
sealed interface Operand permits Register, Local, Constant, Argument {
    /** Materialize the current value of this operand in the given interpreter context. */
    Object retrieve(InterpContext ctx);
}
```

Four operand kinds:

| Kind       | Index space                                | Mutable? | Notes                                   |
|------------|--------------------------------------------|----------|-----------------------------------------|
| `Register` | 0..number_of_registers                      | Yes      | Virtual slot in the per-frame register file |
| `Local`    | 0..local_variable_names.size                | Yes      | Source-level binding (`var`/`let`/`const`) |
| `Constant` | 0..constants.size                           | No       | Index into the executable's constant pool |
| `Argument` | 0..argc                                     | Yes      | Positional argument of the current call frame |

```java
record Register(int index) implements Operand {
    public Object retrieve(InterpContext ctx) { return ctx.registers[index]; }
}
record Local(int slot) implements Operand {
    public Object retrieve(InterpContext ctx) { return ctx.locals[slot]; }
}
record Constant(int index) implements Operand {
    public Object retrieve(InterpContext ctx) { return ctx.executable.constants[index]; }
}
record Argument(int position) implements Operand {
    public Object retrieve(InterpContext ctx) { return ctx.args[position]; }
}
```

Some opcodes' destinations must be `Register` or `Local` (writable). The type system can enforce this via a `Variable` sealed sub-interface:

```java
sealed interface Variable extends Operand permits Register, Local, Argument {
    void store(InterpContext ctx, Object value);
}
```

(`Argument` is `Variable` because JS allows assignment to formal parameters.)

`Constant` is `Operand` but not `Variable`, so a generator that tries to store into a constant fails to compile.

### How registers work

Registers are **virtual slots in a per-frame register file**, sized by `Executable.numberOfRegisters` and allocated when the frame is entered. They are not CPU registers; HotSpot will hoist hot ones to CPU registers via escape analysis on the `Object[]` register file.

The first five register indices are **reserved with fixed semantics** (matching LibJS):

| Index | Name                       | Use                                                                 |
|-------|----------------------------|---------------------------------------------------------------------|
| 0     | `accumulator`              | Implicit destination/source for some opcodes; printed as `acc`      |
| 1     | `exception`                | Holds the in-flight exception during throw → handler handoff        |
| 2     | `this_value`               | Bound `this` for the current frame                                  |
| 3     | `return_value`             | Holds the function's return value                                   |
| 4     | `saved_lexical_environment`| Saved environment for restoration on scope exit                     |

User-allocated virtual registers begin at index 5. The generator allocates these freely; it is **not** SSA — a register can be assigned more than once. (If we add SSA later, it's a pass over the IR, not a representational change.)

### Locals vs registers

- **Registers** are anonymous, generator-allocated, frame-local. Used for sub-expression results and compiler-introduced temporaries.
- **Locals** are named, source-level bindings. Carry a name (and TDZ flag, mutability flag) in `Executable.localVariableNames`. Survive across the frame's lifetime.
- **Arguments** address the call frame's argument vector positionally.
- **Constants** address the `Executable.constants` pool — JS values known at compile time (numeric literals, interned string literals, undefined/null, regex source, etc.).

The split between `Local` and `Register` lets the generator decide late which source-level locals deserve register treatment vs. environment-record storage. A `let x` captured by a closure becomes a `Local` (or escapes to an environment record); a `let x` not captured can stay in registers.

### Operand singletons

Some operand values are pinned singletons:
- The five reserved registers (`Register.ACCUMULATOR`, `Register.EXCEPTION`, etc.) — interned to avoid per-instruction allocation.
- A `Constant` for each common literal: `undefined`, `null`, `true`, `false`, small integers `-1`/`0`/`1`/`2`, empty string. These intern across all executables.

## Instruction model

### Sealed hierarchy

```java
sealed interface Op {
    Operation operation();
    default boolean isTerminator() { return operation().isTerminator(); }
    default boolean canThrow()     { return operation().canThrow(); }
}
```

Every concrete instruction implements `Op` and is one of:
- A **record** (pure data — most opcodes).
- A **record with a mutable IC field** (the cache reference is `final`, the cache object's internals mutate).

There is **one Java type per opcode**. ~115 types in v1 (see opcode count summary).

### The Operation enum

```java
enum Operation {
    ADD(OpClass.ALU,    Flags.MAY_THROW),
    SUB(OpClass.ALU,    Flags.MAY_THROW),
    JUMP(OpClass.BRANCH, Flags.TERMINATOR | Flags.NO_THROW),
    RETURN(OpClass.BRANCH, Flags.TERMINATOR | Flags.NO_THROW),
    GET_BY_ID(OpClass.PROPERTY, Flags.MAY_THROW | Flags.HAS_IC),
    // … one entry per opcode
    ;

    final OpClass opClass;
    final int flags;
    Operation(OpClass c, int f) { opClass = c; flags = f; }

    boolean isTerminator() { return (flags & Flags.TERMINATOR) != 0; }
    boolean canThrow()     { return (flags & Flags.NO_THROW) == 0; }
    boolean hasIC()        { return (flags & Flags.HAS_IC) != 0; }
}
```

The enum is the dispatch tag. It also carries flags for analyses (`canThrow`, `isTerminator`, `hasSideEffect`, `isCall`) and a coarse `OpClass` bucket (`ALU`, `BRANCH`, `CALL`, `PROPERTY`, `BINDING`, `CONSTRUCT`, `ITERATOR`, `TYPE_CONV`, `BOOKKEEPING`, `OTHER`) for two-level dispatch when we want it.

### Pure-record example

```java
record AddInstr(Variable dst, Operand lhs, Operand rhs) implements Op {
    public Operation operation() { return Operation.ADD; }
}

record JumpInstr(int targetPc) implements Op {
    public Operation operation() { return Operation.JUMP; }
}
```

### IC-bearing record example

```java
record GetByIdInstr(
    Variable dst,
    Operand base,
    PropertyKey property,
    PropertyLookupCache cache
) implements Op {
    public Operation operation() { return Operation.GET_BY_ID; }
}

record CallInstr(
    Variable dst,
    Operand callee,
    Operand thisValue,
    Operand[] args,
    CallSite ic
) implements Op {
    public Operation operation() { return Operation.CALL; }
}
```

The cache classes (`PropertyLookupCache`, `CallSite`, `ObjectShapeCache`, `EnvironmentCoordinate`, `GlobalVariableCache`, `TemplateObjectCache`, `ObjectPropertyIteratorCache`) live in their own files. Their layouts are spec'd separately. Each is referenced from the instructions noted in the opcode reference below.

### Opcodes that need a mutable IC

| Opcodes                                                    | IC type                          |
|------------------------------------------------------------|----------------------------------|
| `GetById`, `GetByIdWithThis`, `GetLength`, `GetLengthWithThis`, `PutById`, `PutByIdWithThis` | `PropertyLookupCache`            |
| `GetGlobal`, `SetGlobal`                                   | `GlobalVariableCache`            |
| `GetBinding`, `GetInitializedBinding`, `SetLexicalBinding`, `SetVariableBinding`, `InitializeLexicalBinding`, `InitializeVariableBinding`, `TypeofBinding`, `GetCalleeAndThisFromEnvironment` | `EnvironmentCoordinate` (mutable resolved-slot cache) |
| `Call`, `CallConstruct`, `CallWithArgumentArray`, `CallConstructWithArgumentArray`, `CallDirectEval`, `CallDirectEvalWithArgumentArray`, `SuperCallWithArgumentArray`, all `CallBuiltin*` | `CallSite`                       |
| `NewObject`, `InitObjectLiteralProperty`, `CacheObjectShape` | `ObjectShapeCache`               |
| `GetObjectPropertyIterator`                                | `ObjectPropertyIteratorCache`    |
| `GetTemplateObject`                                        | `TemplateObjectCache`            |

All other opcodes (~95 of ~115) are pure records.

## Executable container

A compiled function/script/module produces an `Executable`:

```java
final class Executable {
    final Op[] ops;                                  // linearized instruction array
    final int numberOfRegisters;                     // size of register file
    final Object[] constants;                        // constant pool
    final IdentifierTable identifiers;               // interned identifier strings
    final PropertyKeyTable propertyKeys;             // interned property keys
    final StringTable strings;                       // source-derived strings (regex sources, etc.)
    final RegexTable regexes;                        // compiled regex patterns
    final LocalVariable[] localVariableNames;        // names + flags for Local slots
    final SharedFunctionData[] sharedFunctionData;   // metadata for nested functions
    final ClassBlueprint[] classBlueprints;          // metadata for class expressions
    final ExceptionHandler[] exceptionHandlers;      // (startPc, endPc, handlerPc)
    final SourceMapEntry[] sourceMap;                // (pc → source range)
    final SourceCode sourceCode;                     // for diagnostics
    final boolean strictMode;
}
```

There is **no `byte[] bytecode`** field. The executable holds `Op[]`. Instruction "addresses" are integer indices into this array — that's the program counter (`pc`).

The transient CFG (with `BasicBlock` objects) is built by the generator and analysis passes, then linearized to `Op[]`. After linearization, `JumpInstr.targetPc` and similar fields hold concrete integer PCs.

`ExceptionHandler` ranges are also expressed in PC indices: `(startPc, endPc, handlerPc)`. The handler PC points at the first instruction of the catch block, which is conventionally a `Catch` instruction.

## Dispatch model

The interpreter is a single `while` loop indexed by `int pc`. Each `Op` returns the next pc from its `interpret` method:

```java
public Object interpret(Executable exe, InterpContext ctx) {
    Op[] ops = exe.ops;
    int pc = 0;
    while (true) {
        try {
            pc = ops[pc].interpret(ctx, pc);
        } catch (AbruptCompletion ex) {
            int handlerPc = exe.findHandler(pc);
            if (handlerPc < 0) throw ex;
            ctx.registers[Register.EXCEPTION_INDEX] = ex.value();
            pc = handlerPc;
        }
    }
}
```

`Return` and similar opcodes signal end-of-frame either by throwing a sentinel completion or by writing to a frame-result slot the loop checks — see the `Return` opcode entry below for the exact mechanism.

Per-op `interpret` methods own all the work:

```java
record AddInstr(Variable dst, Operand lhs, Operand rhs) implements Op {
    public int interpret(InterpContext ctx, int pc) {
        Object l = lhs.retrieve(ctx);
        Object r = rhs.retrieve(ctx);
        dst.store(ctx, AbstractOps.add(l, r));
        return pc + 1;
    }
}

record JumpInstr(int targetPc) implements Op {
    public int interpret(InterpContext ctx, int pc) {
        return targetPc;
    }
}
```

### Why virtual dispatch

1. **Locality.** Each opcode's semantics live next to its operand wiring. Adding a new op = adding a class.
2. **No central switch to maintain.** The interpreter doesn't know how many opcodes there are. Lowering passes don't have to thread changes through a dispatch table.
3. **JVM handles it.** HotSpot inlines monomorphic/bimorphic call sites; megamorphic sites use a vtable indirect — fine for our scale. Tested empirically — virtual call overhead is not the bottleneck.
4. **Reversible.** A parallel enum-switch dispatcher can be code-generated later if profiling demands it, without touching opcode definitions.

### What we don't use

- A central enum-switch interpreter — scales badly, forces every opcode to know about a foreign file.
- `switch` pattern matching on sealed types in the hot path — `typeSwitch` bootstrap is measurable overhead today.
- Computed-goto / threaded interpretation — Java doesn't expose it.

A separate `OpVisitor` interface is used by non-hot-path consumers (disassembler, JIT lowering, IR passes) where transformation, not execution, is the goal.

## Basic blocks (transient form)

The IR generator produces a per-scope CFG of `BasicBlock` objects. Each basic block holds an `ArrayList<Op>` and a list of successor edges. Blocks are used by:
- The generator, while building.
- Analysis passes (live-variable analysis, DCE, copy propagation, inlining).
- The disassembler (for human-readable output).

Before execution, `CFGLinearizer` flattens the CFG to a single `Op[]`:
1. Order the blocks (reverse postorder typically).
2. Concatenate their instructions into one array.
3. Resolve `Label`-typed targets to integer PC offsets, rewriting jump instructions.
4. Build the `ExceptionHandler[]` array from per-block handler annotations.

After this pass the CFG is discarded; the runtime sees only the `Op[]`.

A label is, conceptually, a forward reference to a basic block. During CFG construction it's a `Label` placeholder operand. After linearization labels are gone — replaced by the integer `targetPc` field on `JumpInstr` and friends.

## Exception flow

When a non-`@nothrow` instruction throws an `AbruptCompletion`:
1. The interpreter catches it in the dispatch loop.
2. It looks up the innermost `ExceptionHandler` whose `[startPc, endPc)` range contains the current PC.
3. If found, the thrown value is written into the `exception` register (index 1) and `pc` is set to `handlerPc`.
4. If not found, the `AbruptCompletion` propagates up to the calling Java frame — a host-level `throw`.

A handler block conventionally begins with a `Catch` instruction that moves the value out of the `exception` register into a destination operand for the JS-visible `catch (e)` binding.

`@nothrow` opcodes (flagged in the reference below) are guaranteed not to throw. The dispatcher does not need to consult the exception table for them.

## Opcode reference

Conventions:
- `dst: Variable` — destination of the produced value (must be writable: `Register`, `Local`, or `Argument`).
- `src`, `lhs`, `rhs`, `value` — source operands (any `Operand` kind).
- `base` — receiver for property access.
- `@nothrow` — guaranteed not to throw; dispatcher can skip exception-table lookup.
- `@terminator` — ends the current basic block (always sets `pc` explicitly).
- `cache: <Kind>` — final field referencing a mutable IC. Internal layout specified in a separate doc.

### Arithmetic

| Opcode             | Operands                            | Spec semantics                          |
|--------------------|-------------------------------------|-----------------------------------------|
| `Add`              | `dst, lhs, rhs`                     | `lhs + rhs` (numeric or string concat)  |
| `Sub`              | `dst, lhs, rhs`                     | `lhs - rhs`                             |
| `Mul`              | `dst, lhs, rhs`                     | `lhs * rhs`                             |
| `Div`              | `dst, lhs, rhs`                     | `lhs / rhs`                             |
| `Mod`              | `dst, lhs, rhs`                     | `lhs % rhs`                             |
| `Exp`              | `dst, lhs, rhs`                     | `lhs ** rhs`                            |
| `BitwiseAnd`       | `dst, lhs, rhs`                     | `lhs & rhs`                             |
| `BitwiseOr`        | `dst, lhs, rhs`                     | `lhs | rhs`                             |
| `BitwiseXor`       | `dst, lhs, rhs`                     | `lhs ^ rhs`                             |
| `LeftShift`        | `dst, lhs, rhs`                     | `lhs << rhs`                            |
| `RightShift`       | `dst, lhs, rhs`                     | `lhs >> rhs`                            |
| `UnsignedRightShift`| `dst, lhs, rhs`                    | `lhs >>> rhs`                           |

Unary operations (single source, write to `dst`):

| Opcode              | Operands             | Semantics                                   |
|---------------------|----------------------|---------------------------------------------|
| `BitwiseNot`        | `dst, src`           | `~src`                                      |
| `UnaryPlus`         | `dst, src`           | `+src` (ToNumber)                           |
| `UnaryMinus`        | `dst, src`           | `-src`                                      |
| `Not` `@nothrow`    | `dst, src`           | `!ToBoolean(src)`                           |

Increment / decrement:

| Opcode             | Operands         | Semantics                                       |
|--------------------|------------------|-------------------------------------------------|
| `Increment`        | `dst`            | `dst = dst + 1` (in place; numeric / BigInt)    |
| `Decrement`        | `dst`            | `dst = dst - 1`                                 |
| `PostfixIncrement` | `dst, src`       | `dst = ToNumeric(src); src = dst + 1`           |
| `PostfixDecrement` | `dst, src`       | `dst = ToNumeric(src); src = dst - 1`           |

### Comparison

All produce a Boolean in `dst`.

| Opcode               | Operands         | Semantics                          |
|----------------------|------------------|------------------------------------|
| `LessThan`           | `dst, lhs, rhs`  | `lhs < rhs`                        |
| `LessThanEquals`     | `dst, lhs, rhs`  | `lhs <= rhs`                       |
| `GreaterThan`        | `dst, lhs, rhs`  | `lhs > rhs`                        |
| `GreaterThanEquals`  | `dst, lhs, rhs`  | `lhs >= rhs`                       |
| `LooselyEquals`      | `dst, lhs, rhs`  | `lhs == rhs`                       |
| `LooselyInequals`    | `dst, lhs, rhs`  | `lhs != rhs`                       |
| `StrictlyEquals`     | `dst, lhs, rhs`  | `lhs === rhs`                      |
| `StrictlyInequals`   | `dst, lhs, rhs`  | `lhs !== rhs`                      |
| `In`                 | `dst, lhs, rhs`  | `lhs in rhs`                       |
| `InstanceOf`         | `dst, lhs, rhs`  | `lhs instanceof rhs`               |

### Type conversion and predicates

| Opcode                         | Operands                       | Semantics                                                |
|--------------------------------|--------------------------------|----------------------------------------------------------|
| `ToBoolean` `@nothrow`         | `dst, value`                   | `ToBoolean(value)`                                       |
| `ToInt32`                      | `dst, value`                   | `ToInt32(value)`                                         |
| `ToLength`                     | `dst, value`                   | `ToLength(value)`                                        |
| `ToObject`                     | `dst, value`                   | `ToObject(value)` (throws TypeError on null/undefined)   |
| `ToString`                     | `dst, value`                   | `ToString(value)`                                        |
| `ToPrimitiveWithStringHint`    | `dst, value`                   | `ToPrimitive(value, "string")`                           |
| `Typeof` `@nothrow`            | `dst, src`                     | `typeof src`                                             |
| `TypeofBinding`                | `dst, identifier, cache: EnvironmentCoordinate` | `typeof <binding>`; tolerates unresolved bindings |
| `IsCallable` `@nothrow`        | `dst, value`                   | `IsCallable(value)`                                      |
| `IsConstructor` `@nothrow`     | `dst, value`                   | `IsConstructor(value)`                                   |

### Move

| Opcode               | Operands                                       |
|----------------------|------------------------------------------------|
| `Mov` `@nothrow`     | `dst, src`                                     |
| `Mov2` `@nothrow`    | `dst1, src1, dst2, src2`                       |
| `Mov3` `@nothrow`    | `dst1, src1, dst2, src2, dst3, src3`           |

### Property access — get

| Opcode                            | Operands                                                              | Semantics                                          |
|-----------------------------------|------------------------------------------------------------------------|----------------------------------------------------|
| `GetById`                         | `dst, base, property: PropertyKey, baseIdentifier?, cache: PropertyLookupCache` | `dst = base[property]` with named property |
| `GetByIdWithThis`                 | `dst, base, property, thisValue, cache`                                | Same as `GetById` but pass explicit `this`         |
| `GetByValue`                      | `dst, base, property: Operand, baseIdentifier?`                        | `dst = base[property]` with computed key           |
| `GetByValueWithThis`              | `dst, base, property, thisValue`                                       | Computed get with explicit `this`                  |
| `GetLength`                       | `dst, base, baseIdentifier?, cache`                                    | `dst = base.length` (specialized)                  |
| `GetLengthWithThis`               | `dst, base, thisValue, cache`                                          | `dst = base.length` with explicit `this`           |
| `GetMethod`                       | `dst, object, property`                                                | `GetMethod(object, property)` — null if absent     |
| `GetGlobal`                       | `dst, identifier, cache: GlobalVariableCache`                          | Read global binding by name                        |
| `GetPrivateById`                  | `dst, base, property: Identifier`                                      | Private slot get                                   |
| `GetTemplateObject` `@nothrow`    | `dst, length, stringsCount, cache: TemplateObjectCache, strings: Operand[]` | Build/cache tagged-template object             |
| `GetCalleeAndThisFromEnvironment` | `callee, thisValue, identifier, cache: EnvironmentCoordinate`          | Resolve a name into both callee and `this`         |
| `GetBinding`                      | `dst, identifier, cache: EnvironmentCoordinate`                        | Read environment binding                           |
| `GetInitializedBinding`           | `dst, identifier, cache`                                               | Read binding asserting it is initialized (not TDZ) |
| `GetCompletionFields` `@nothrow`  | `typeDst, valueDst, completion`                                        | Split a `Completion` record                        |
| `GetIterator`                     | `dstIteratorObject, dstIteratorNext, dstIteratorDone, iterable, hint: IteratorHint` | `GetIterator(iterable, hint)`             |
| `GetObjectPropertyIterator`       | `dstIterator, object, cache: ObjectPropertyIteratorCache`              | Build property-name iterator (for `for-in`)        |
| `GetImportMeta` `@nothrow`        | `dst`                                                                  | `import.meta`                                      |
| `GetLexicalEnvironment` `@nothrow`| `dst`                                                                  | Current lexical environment                        |
| `GetNewTarget` `@nothrow`         | `dst`                                                                  | `new.target`                                       |
| `HasPrivateId`                    | `dst, base, property: Identifier`                                      | `#priv in base`                                    |
| `ResolveSuperBase`                | `dst`                                                                  | `super` base for property access                   |
| `ResolveThisBinding`              | (none)                                                                 | Resolve and store `this` into the reserved register |

### Property access — put / delete

`PutKind` enum distinguishes ordinary `Set`, `DefineProperty`, `DefineGetter`, `DefineSetter` (etc.).

| Opcode               | Operands                                                                            | Semantics                                       |
|----------------------|--------------------------------------------------------------------------------------|-------------------------------------------------|
| `PutById`            | `base, property: PropertyKey, src, kind: PutKind, cache: PropertyLookupCache, baseIdentifier?` | `base[property] = src` (named)              |
| `PutByIdWithThis`    | `base, thisValue, property, src, kind, cache`                                        | Named put with explicit `this`                  |
| `PutByValue`         | `base, property: Operand, src, kind, baseIdentifier?`                                | Computed put                                    |
| `PutByValueWithThis` | `base, property, thisValue, src, kind`                                               | Computed put with explicit `this`               |
| `PutBySpread`        | `base, src`                                                                         | Object spread (`{...src}`) into `base`          |
| `PutPrivateById`     | `base, property: Identifier, src`                                                    | Private slot set                                |
| `SetGlobal`          | `identifier, src, cache: GlobalVariableCache`                                        | Write to a global binding                       |
| `DeleteById`         | `dst, base, property: PropertyKey`                                                   | `delete base.id`                                |
| `DeleteByValue`      | `dst, base, property`                                                                | `delete base[expr]`                             |
| `DeleteVariable`     | `dst, identifier`                                                                    | `delete name` (sloppy mode)                     |

### Bindings and environments

| Opcode                                  | Operands                                                                 | Semantics                                              |
|-----------------------------------------|---------------------------------------------------------------------------|--------------------------------------------------------|
| `CreateLexicalEnvironment` `@nothrow`   | `dst, parent, capacity`                                                  | New `DeclarativeEnvironment`                          |
| `CreateVariableEnvironment` `@nothrow`  | `capacity`                                                                | New `VariableEnvironment` for the running context     |
| `CreatePrivateEnvironment` `@nothrow`   | (none)                                                                   | Push a `PrivateEnvironment`                           |
| `LeavePrivateEnvironment` `@nothrow`    | (none)                                                                   | Pop                                                    |
| `EnterObjectEnvironment`                | `dst, object`                                                            | `with (object) { ... }` entry                         |
| `SetLexicalEnvironment` `@nothrow`      | `environment`                                                            | Replace the running context's lexical env             |
| `CreateImmutableBinding`                | `environment, identifier, strictBinding`                                 | Spec `CreateImmutableBinding`                          |
| `CreateMutableBinding`                  | `environment, identifier, canBeDeleted`                                  | Spec `CreateMutableBinding`                            |
| `CreateVariable`                        | `identifier, mode: EnvironmentMode, isImmutable, isGlobal, isStrict`     | `var`/`let`/`const` declaration installation          |
| `InitializeLexicalBinding`              | `identifier, src, cache: EnvironmentCoordinate`                          | Initialize a `let`/`const` binding                    |
| `InitializeVariableBinding`             | `identifier, src, cache`                                                 | Initialize a `var` binding                             |
| `SetLexicalBinding`                     | `identifier, src, cache`                                                 | Assign to lexical binding                              |
| `SetVariableBinding`                    | `identifier, src, cache`                                                 | Assign to variable binding                             |
| `CreateDataPropertyOrThrow`             | `object, property, value`                                                | Spec abstract op                                       |

### Object / array creation

| Opcode                                | Operands                                                                            | Semantics                                                 |
|---------------------------------------|-------------------------------------------------------------------------------------|-----------------------------------------------------------|
| `NewObject` `@nothrow`                | `dst, cache: ObjectShapeCache`                                                      | `{}` (with shape caching)                                 |
| `NewObjectWithNoPrototype` `@nothrow` | `dst`                                                                              | `Object.create(null)`-style                              |
| `NewArray` `@nothrow`                 | `dst, elements: Operand[]`                                                          | `[e0, e1, …]`                                             |
| `NewArrayWithLength`                  | `dst, arrayLength`                                                                  | `new Array(n)`                                            |
| `NewPrimitiveArray` `@nothrow`        | `dst, elements: Object[]`                                                           | Constant-folded primitive array                           |
| `NewClass`                            | `dst, superClass?, classEnvironment, classBlueprintIndex, lhsName?, elementKeys: Optional<Operand>[]` | `class { … }` construction                |
| `NewFunction` `@nothrow`              | `dst, sharedFunctionDataIndex, lhsName?, homeObject?`                              | Build closure from shared function data                   |
| `NewRegExp` `@nothrow`                | `dst, sourceIndex, flagsIndex, regexIndex`                                          | `/pattern/flags`                                          |
| `NewTypeError` `@nothrow`             | `dst, errorString`                                                                  | Construct a `TypeError`                                   |
| `NewReferenceError` `@nothrow`        | `dst, errorString`                                                                  | Construct a `ReferenceError`                              |
| `ArrayAppend`                         | `dst, src, isSpread`                                                                | Push (or spread) `src` into array `dst`                   |
| `ConcatString`                        | `dst, src`                                                                         | `dst = dst + ToString(src)` (template literal codegen)    |
| `CopyObjectExcludingProperties`       | `dst, fromObject, excludedNames: Operand[]`                                         | `{...from, exclude X, Y}` rest-pattern destructuring      |
| `CreateArguments` `@nothrow`          | `dst?, kind: ArgumentsKind, isImmutable`                                            | Build `arguments` (mapped/unmapped)                       |
| `CreateRestParams` `@nothrow`         | `dst, restIndex`                                                                    | Materialize `...rest` parameter array                     |
| `CreateAsyncFromSyncIterator` `@nothrow` | `dst, iterator, nextMethod, done`                                                | `CreateAsyncFromSyncIterator` spec helper                 |
| `InitObjectLiteralProperty` `@nothrow` | `object, property, src, shapeCacheIndex, propertySlot`                             | Object-literal property init with shape-cache fast path   |
| `CacheObjectShape` `@nothrow`         | `object, cache: ObjectShapeCache`                                                   | Record object shape in cache slot                         |

### Calls

`CallBuiltin*` opcodes are direct fast-paths emitted when the generator can prove the callee is a known intrinsic. They sidestep the generic call dispatch.

| Opcode                                | Operands                                                                                                    |
|---------------------------------------|--------------------------------------------------------------------------------------------------------------|
| `Call`                                | `dst, callee, thisValue, args: Operand[], expressionString?, cache: CallSite`                                |
| `CallWithArgumentArray`               | `dst, callee, thisValue, args: Operand, expressionString?, cache: CallSite`                                  |
| `CallConstruct`                       | `dst, callee, args: Operand[], expressionString?, cache: CallSite`                                           |
| `CallConstructWithArgumentArray`      | `dst, callee, thisValue, args, expressionString?, cache: CallSite`                                           |
| `CallDirectEval`                      | `dst, callee, thisValue, args: Operand[], expressionString?, cache: CallSite`                                |
| `CallDirectEvalWithArgumentArray`     | `dst, callee, thisValue, args, expressionString?, cache: CallSite`                                           |
| `SuperCallWithArgumentArray`          | `dst, args, isSynthetic`                                                                                    |
| `ImportCall`                          | `dst, specifier, options`                                                                                   |

Builtin fast-path calls — same operand shape: `dst, callee, thisValue, [argument(s)], expressionString?, cache: CallSite`:

```
CallBuiltinMathAbs   CallBuiltinMathLog   CallBuiltinMathPow   CallBuiltinMathExp
CallBuiltinMathCeil  CallBuiltinMathFloor CallBuiltinMathImul  CallBuiltinMathRandom
CallBuiltinMathRound CallBuiltinMathSqrt  CallBuiltinMathSin   CallBuiltinMathCos
CallBuiltinMathTan
CallBuiltinRegExpPrototypeExec
CallBuiltinRegExpPrototypeReplace
CallBuiltinRegExpPrototypeSplit
CallBuiltinOrdinaryHasInstance
CallBuiltinArrayIteratorPrototypeNext
CallBuiltinMapIteratorPrototypeNext
CallBuiltinSetIteratorPrototypeNext
CallBuiltinStringIteratorPrototypeNext
CallBuiltinStringFromCharCode
CallBuiltinStringPrototypeCharCodeAt
CallBuiltinStringPrototypeCharAt
```

These are optional in v1: the generic `Call` opcode covers them all. Add as a perf tier later.

### Iteration

| Opcode                            | Operands                                                                            | Semantics                                                       |
|-----------------------------------|-------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| `IteratorNext`                    | `dst, iteratorObject, iteratorNext, iteratorDone`                                   | Spec `IteratorStep` returning iterator result object            |
| `IteratorNextUnpack`              | `dstValue, dstDone, iteratorObject, iteratorNext, iteratorDone`                     | `IteratorStep` then unpack `value` and `done`                   |
| `IteratorClose`                   | `iteratorObject, iteratorNext, iteratorDone, completionType, completionValue`       | `IteratorClose` with completion record                          |
| `IteratorToArray`                 | `dst, iteratorObject, iteratorNextMethod, iteratorDoneProperty`                     | Drain iterator into a fresh array                               |
| `ObjectPropertyIteratorNext`      | `dstValue, dstDone, iteratorObject`                                                 | Step a property-name iterator (`for-in` body)                   |

### Control flow — jumps

All jumps are `@terminator @nothrow`. Targets are integer PCs (resolved from labels at linearization time).

| Opcode                       | Operands                                                |
|------------------------------|----------------------------------------------------------|
| `Jump`                       | `targetPc`                                              |
| `JumpIf`                     | `condition, trueTargetPc, falseTargetPc`                |
| `JumpTrue`                   | `condition, targetPc`                                   |
| `JumpFalse`                  | `condition, targetPc`                                   |
| `JumpNullish`                | `condition, trueTargetPc, falseTargetPc`                |
| `JumpUndefined`              | `condition, trueTargetPc, falseTargetPc`                |
| `JumpStrictlyEquals`         | `lhs, rhs, trueTargetPc, falseTargetPc`                 |
| `JumpStrictlyInequals`       | `lhs, rhs, trueTargetPc, falseTargetPc`                 |
| `JumpLooselyEquals`          | `lhs, rhs, trueTargetPc, falseTargetPc`                 |
| `JumpLooselyInequals`        | `lhs, rhs, trueTargetPc, falseTargetPc`                 |
| `JumpLessThan`               | `lhs, rhs, trueTargetPc, falseTargetPc`                 |
| `JumpLessThanEquals`         | `lhs, rhs, trueTargetPc, falseTargetPc`                 |
| `JumpGreaterThan`            | `lhs, rhs, trueTargetPc, falseTargetPc`                 |
| `JumpGreaterThanEquals`      | `lhs, rhs, trueTargetPc, falseTargetPc`                 |

The `Jump<Compare>` family fuses comparison + branch. The generator emits these instead of separate compare + branch when the comparison result is not otherwise needed.

### Control flow — return / end / throw / catch

| Opcode                            | Operands                                       | Semantics                                                                |
|-----------------------------------|------------------------------------------------|--------------------------------------------------------------------------|
| `Return` `@terminator @nothrow`   | `value`                                        | Function return                                                          |
| `End` `@terminator @nothrow`      | `value`                                        | Top-level script/module completion value                                 |
| `Throw` `@terminator`             | `src`                                          | Throw `src` as exception                                                 |
| `Catch` `@nothrow`                | `dst`                                          | At handler entry: move the in-flight exception into `dst`                |
| `ThrowIfNotObject`                | `src`                                          | Spec assert: throw TypeError unless `src` is an object                   |
| `ThrowIfNullish`                  | `src`                                          | Throw TypeError if `src` is `null`/`undefined`                           |
| `ThrowIfTDZ`                      | `src`                                          | Throw ReferenceError if `src` is the TDZ sentinel                        |
| `ThrowConstAssignment`            | (none)                                         | Throw TypeError for assignment to `const`                                |
| `SetCompletionType` `@nothrow`    | `completion, completionType`                   | Mutate a completion record's type field                                  |

### Async / generators

| Opcode                          | Operands                                              | Semantics                                                |
|---------------------------------|--------------------------------------------------------|----------------------------------------------------------|
| `Await` `@terminator @nothrow`  | `continuationPc, argument`                             | Suspend, schedule resume with awaited value              |
| `Yield` `@terminator @nothrow`  | `continuationPc?, value`                               | Generator yield                                          |

### Private class fields

| Opcode                       | Operands                       | Semantics                                                |
|------------------------------|--------------------------------|----------------------------------------------------------|
| `AddPrivateName` `@nothrow`  | `name: Identifier`             | Register a private name in the current private env       |

(Private get/has/put are listed in the property-access sections.)

## Opcode count summary

| Category                  | Count |
|---------------------------|-------|
| Arithmetic + unary + inc/dec | 16 |
| Comparison                | 10    |
| Type conversion / predicates | 10 |
| Move                      | 3     |
| Property get              | 19    |
| Property put / delete     | 10    |
| Bindings / environments   | 13    |
| Object / array creation   | 16    |
| Calls (generic)           | 8     |
| Calls (builtin fast paths)| 22    |
| Iteration                 | 5     |
| Jumps                     | 14    |
| Return / end / throw / catch | 9 |
| Async / generators        | 2     |
| Private fields            | 1     |
| **Total**                 | **~158** |

Subtracting builtin fast paths and the `Mov2`/`Mov3` density helpers, the minimum-viable set for an ES-conformant interpreter is approximately **115 opcodes**.

## Oracle-test protocol

Goal: validate our IR generator and interpreter against LibJS. Three layers of checks.

### Layer 1: instruction-shape diff

For each test JS source:

1. Run LibJS's shell with bytecode-dump enabled to capture its instruction stream.
   ```
   ladybird-js --dump-bytecode <file.js>
   ```
2. Run our generator and dump our equivalent.
3. Compare:
   - **Strong match**: same opcodes in the same order, same basic-block partitioning, same operand kinds. Operand *indices* may differ if our register allocator differs.
   - **Weak match**: same observable operations (e.g. we emitted `Add` where they emitted `Add` plus a redundant `Mov`).

Use a normalized form (alpha-rename register indices, drop debug-only fields like `expressionString`) for stable diffing.

Acceptable divergences:
- Builtin fast paths: we may emit generic `Call` where LibJS emits `CallBuiltinMathAbs`.
- Mov coalescing: their `Mov2`/`Mov3` density helpers vs. our individual `Mov`s.
- Cache-slot identity: opaque, differs trivially.
- Basic-block names: cosmetic.

### Layer 2: behavioral conformance

Run test262 cases through both engines and diff:
- Final completion value
- Thrown exception type + message (modulo formatting)
- Side effects observable via `print` / `console.log` test harness

LibJS's test262 score is high (~94%); failures we share with LibJS are likely spec-edge cases. Failures we have but they don't are real bugs in us.

### Layer 3: per-op tracing

For tightly-scoped debugging, instrument our interpreter to log each executed opcode + dst-register write. Run LibJS with comparable tracing and diff line-by-line. Slow, but invaluable for "why does this loop iterate one extra time" investigations.

## Attribution

- Opcode set and operand model adapted from Ladybird LibJS (https://github.com/LadybirdBrowser/ladybird), BSD-2-Clause.
- IR representation (instructions-as-objects, operands-as-objects, enum-switch dispatch, mutable-IC-on-instruction) inspired by JRuby (https://github.com/jruby/jruby), EPL/GPL/LGPL — design influence only; no source copied.
