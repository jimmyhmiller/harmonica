# Standard Objects: Architecture & Bringup Plan

> A guide for implementing the JavaScript standard library in harmonica, modeled on
> Ladybird's LibJS. Audience: a future session opening this repo with the bytecode
> interpreter working and `JSObject` / `JSArray` / `JSFunction` as bare stubs.
>
> This is an *implementation* document, not a spec recap. ECMA-262 says *what*;
> this says *how LibJS arranges the pieces* and *what to copy structurally*.
>
> All `LibJS/...` paths in this doc are relative to
> `Libraries/LibJS/` in the ladybird checkout. Read those for ground truth.

---

## 1. High-level layout: Realm, Intrinsics, GlobalObject

LibJS partitions per-execution-context state into three cooperating cells.
Forget "VM" for a moment; the unit of "a JS world" is a **Realm**.

### Realm
A Realm owns:
- one **Intrinsics** record (the bag of all built-in objects),
- one **GlobalObject** (the thing `globalThis` resolves to),
- one **GlobalEnvironment** (lexical-environment wrapper around the global object plus a declarative record for `let`/`const`),
- a host-defined slot for embedder data.

A `VM` can have many Realms (think iframes). For harmonica's bringup, one Realm
is fine — but **structure your code so the Realm is the parameter**, never a
global. Singletons here are the path to multi-window pain later.

Canonical files: `LibJS/Runtime/Realm.h`, `Realm.cpp`.

### Intrinsics
Intrinsics is a flat record holding `GC::Ptr` references to **every built-in
prototype and constructor** in the realm:
`object_prototype()`, `function_prototype()`, `array_prototype()`,
`array_constructor()`, `error_prototype()`, etc., plus pre-baked `Shape`s
(hidden classes) for common object templates. It also holds a few
"loose" function objects — `eval_function`, `parse_int_function`,
`%ThrowTypeError%` — that don't have prototypes but are referenced by the
global / by other built-ins.

The crucial point: **prototypes and constructors live on Intrinsics, not on
the GlobalObject.** The global object only holds *property-name → constructor*
bindings (`"Array" → array_constructor`) — and even those are mostly lazy
accessors in LibJS, deferred until first read.

Canonical files: `LibJS/Runtime/Intrinsics.h`, `Intrinsics.cpp`.

### GlobalObject
A subclass of `Object`. Its `[[Prototype]]` is `Object.prototype`. It carries
the global bindings (`Array`, `Math`, `JSON`, `globalThis`, `NaN`, `Infinity`,
`parseInt`, ...) added by the spec function `SetDefaultGlobalBindings`.

LibJS uses an **intrinsic accessor** trick (`define_intrinsic_accessor`) for
constructors: rather than eagerly putting `Array` on the global, it installs
a property whose getter calls back into Intrinsics, lazily allocating the
constructor on first read. This pattern is a concrete optimization — embed it
from day one, since lazy construction is also what lets you bootstrap without
allocating Temporal/Intl/RegExp on every script.

Canonical files: `LibJS/Runtime/GlobalObject.h`, `GlobalObject.cpp`,
`set_default_global_bindings()` in particular.

### Reference graph (who points at whom)

```
Realm ─► Intrinsics ─► { ObjectPrototype, FunctionPrototype, ArrayPrototype,
                         ArrayConstructor, ..., %ThrowTypeError%, shapes... }
  │
  └────► GlobalObject ─► { "Array" → ArrayCtor, "Math" → MathObject, ... }
              │
              [[Prototype]] = ObjectPrototype (held by Intrinsics)
```

The GlobalEnvironment wraps the GlobalObject and is what the bytecode
interpreter consults for unqualified name lookup (`x` referring to `globalThis.x`).

### Java-shape sketch

```java
public final class Realm {
    private final VM vm;
    private Intrinsics intrinsics;
    private JSObject globalObject;
    private GlobalEnvironment globalEnv;
    public Intrinsics intrinsics()     { return intrinsics; }
    public JSObject  globalObject()    { return globalObject; }
}

public final class Intrinsics {
    private final Realm realm;
    private JSObject objectPrototype;
    private JSObject functionPrototype;
    private JSObject arrayPrototype;
    private JSFunction arrayConstructor;
    // ... ~80 fields
    private JSFunction throwTypeErrorFunction;
    private Accessor   throwTypeErrorAccessor;
    // pre-baked shapes
    private Shape emptyObjectShape;
    private Shape newObjectShape;          // proto = objectPrototype
}
```

---

## 2. Object model

### Shape vs. own properties

LibJS's `Object` does **not** carry a `Map<String,Value>`. It has:
- a `Shape*` (hidden class) which knows the *layout* — which property names
  exist, in what order, with what attributes — and is shared between objects
  of the same shape;
- a flat `Value m_named_properties[]` array indexed by the slot offsets that
  Shape hands out;
- a separate `m_indexed_elements` array for integer-keyed properties (the
  array-backing storage), with three storage modes (Packed / Holey / Dictionary);
- a small `u8 m_flags` (extensible, is-function, has-magical-length, ...).

The current harmonica `JSObject` uses a `LinkedHashMap`. That works for
correctness but **not** for performance and **not** for proper own-property
ordering rules. When you bring up the runtime, plan to introduce shapes
incrementally — first as a forwarding layer over the map, then as the real
storage. The bytecode IC slots already exist in opcodes (`PropertyLookupCache`,
`ObjectShapeCache`) — they expect a shape system.

Canonical files: `LibJS/Runtime/Object.h`, `Object.cpp`, `Shape.h`, `Shape.cpp`,
`IndexedProperties.h`.

### Internal slots vs. own properties

Two distinct concepts the spec conflates verbally but engines split:
- **Own properties** — what `Object.keys`, `for…in`, `getOwnPropertyDescriptor`
  see. Stored via Shape.
- **Internal slots** — `[[Extensible]]`, `[[Prototype]]`, `[[ArrayLength]]`,
  `[[ErrorData]]`, `[[StringData]]`, `[[Call]]`, `[[Construct]]`. These are
  Java fields on subclasses or bits in `m_flags`. They are NOT in the property
  table.

In LibJS, `[[Prototype]]` lives on the Shape (so a transition changes both
prototype and layout). `[[Extensible]]` is a flag bit. Specialized types
(`Array`, `StringObject`, `Error`) put their internal slots as plain fields
on the subclass.

### The `[[X]]` internal-method dispatch

LibJS gives `Object` a set of `virtual` methods named `internal_get`,
`internal_set`, `internal_get_own_property`, `internal_define_own_property`,
`internal_delete`, `internal_own_property_keys`, `internal_get_prototype_of`,
`internal_set_prototype_of`, `internal_is_extensible`, `internal_prevent_extensions`,
`internal_has_property`, `internal_call`, `internal_construct` (last two on
`FunctionObject`). These correspond directly to the spec's `[[Get]]`, `[[Set]]`,
`[[GetOwnProperty]]`, etc.

Default implementations are the "OrdinaryObject" algorithms from §10.1 of
ECMA-262. Exotic objects (`Array`, `StringObject`, `Proxy`, `Arguments`,
typed arrays, the global, immutable-prototype) override only the methods
they need to behave specially. **`Array` overrides `internal_get_own_property`,
`internal_set`, `internal_define_own_property`, `internal_has_property`,
`internal_delete`, `internal_own_property_keys`** — that's the whole "array
exotic object" magic.

User-facing operations like `get`, `set`, `has_property`, `delete_property_or_throw`
are non-virtual helpers that call the `internal_*` methods. They live on the
base `Object` and exist so the rest of the engine can call them without
knowing whether the object is a Proxy.

### Java-shape sketch

```java
public abstract class JSObject {
    protected Shape shape;
    protected Value[] namedProperties;
    protected IndexedStorage indexed;       // null unless array-like
    protected int flags;                    // extensible, isFunction, ...

    // Spec internal methods — virtual, may be overridden by exotic subclasses.
    public Value   internalGet(PropertyKey k, Value receiver)              { /* Ordinary */ }
    public boolean internalSet(PropertyKey k, Value v, Value receiver)     { /* Ordinary */ }
    public Optional<PropertyDescriptor> internalGetOwnProperty(PropertyKey k){ /* ... */ }
    public boolean internalDefineOwnProperty(PropertyKey k, PropertyDescriptor d) { /* ... */ }
    public boolean internalDelete(PropertyKey k)                           { /* ... */ }
    public List<PropertyKey> internalOwnPropertyKeys()                     { /* ... */ }
    public JSObject internalGetPrototypeOf()                               { return shape.prototype(); }
    public boolean  internalSetPrototypeOf(JSObject p)                     { /* ... */ }

    // User-facing helpers — non-virtual; they call the virtual methods.
    public final Value get(PropertyKey k)              { return internalGet(k, this); }
    public final void  set(PropertyKey k, Value v, boolean throwOnFail) { /* ... */ }
}
```

### PropertyDescriptor

LibJS represents `PropertyDescriptor` as a plain struct with all six fields
optional (`Optional<Value> value`, `Optional<bool> writable`, etc.) plus
`Optional<FunctionObject*> get/set`. "Field absent" is meaningful — it is the
spec's "if HasField(Desc, …)". Don't use sentinel values for absence; use a
real `Optional`.

Helpers `is_data_descriptor()`, `is_accessor_descriptor()`,
`is_generic_descriptor()` are direct ports of the spec predicates. The
`PropertyAttributes` value (a packed `u8` of writable/enumerable/configurable
bits) is the *compact* form stored in shape slots; PropertyDescriptor is the
*spec-faithful* form used during DefineProperty algorithms.

Canonical file: `LibJS/Runtime/PropertyDescriptor.h`.

### Accessor properties

An accessor (getter/setter) is *not* a separate descriptor type at storage
time. LibJS stores accessor properties as a regular property whose **value is
an `Accessor` cell** holding `(getter, setter)` function pointers, plus the
descriptor's writable bit being absent (signaled by attribute flags). When
`internal_get` reads a slot and finds `Accessor`, it calls the getter and
returns its result; `internal_set` invokes the setter. An accessor and a data
property occupy the same physical slot — the storage layer treats `Accessor`
as just another `Value` payload.

Canonical files: `LibJS/Runtime/Accessor.h`, dispatch in `Object.cpp`'s
`internal_get` / `ordinary_set_with_own_descriptor`.

### Subclass map

```
Object
├── FunctionObject (abstract, has [[Call]])
│   ├── ECMAScriptFunctionObject  (user-defined `function`/arrow)
│   ├── NativeFunction            (built-in implemented in Java)
│   │   └── RawNativeFunction     (function-pointer-only specialization)
│   ├── BoundFunction             (the .bind() result)
│   └── ProxyObject               (when target is callable)
├── Array            (array exotic — overrides indexed-property methods + length)
├── StringObject     (boxed string — exotic [[GetOwnProperty]] for indices)
├── NumberObject, BooleanObject, BigIntObject, SymbolObject (boxed primitives)
├── ErrorObject + per-error-type subclasses (TypeError, RangeError, ...)
├── Date, RegExpObject, ArgumentsObject
├── ArrayBuffer, DataView, TypedArray + 11 typed-array subclasses
├── Map, Set, WeakMap, WeakSet, WeakRef
├── Promise
├── ProxyObject
├── GlobalObject
└── Iterator subclasses, AsyncFromSyncIterator, GeneratorObject, ...
```

Plain "namespace" objects (`Math`, `JSON`, `Reflect`, `Atomics`, `console`,
`Intl`, `Temporal`) are *not* a distinct subclass — they're plain `Object`s
populated in their own `initialize()` method.

---

## 3. Prototype-chain bootstrap (the ordering that matters)

The chicken-and-egg:

- `Object.prototype` is the chain root. Its `[[Prototype]]` is `null`.
- `Function.prototype` is itself a callable object whose `[[Prototype]]` is `Object.prototype`.
- `Object` (the constructor) is itself a function — so its `[[Prototype]]` is `Function.prototype`.
- `Object.prototype.constructor === Object` and `Object.prototype` is reachable as `Object.prototype`.
- Same triangle for every other constructor (`Array`, `Error`, `String`, ...).
- `Function.prototype` itself is callable but a no-op; its own `length` is 0.
- The realm has a special `%ThrowTypeError%` function used as a "poisoned"
  getter/setter for restricted properties (`caller`, `arguments`) on strict
  functions and on `Function.prototype` itself.

LibJS resolves this in `Intrinsics::initialize_intrinsics(Realm&)` by
**allocating without prototype, then back-patching**. Read `Intrinsics.cpp`
~lines 213–370. The ordering is:

1. **Allocate `m_empty_object_shape`** (no prototype).
2. **Allocate `ObjectPrototype` raw** (using a special "construct without
   prototype" tag — its own `[[Prototype]]` is `null`).
3. **Allocate `FunctionPrototype` raw** — also without setting prototype yet,
   but immediately afterward set its prototype to `ObjectPrototype`.
4. Allocate the standard **shapes** that depend on these: `new_object_shape`
   (proto = ObjectPrototype), `normal_function_shape`/`native_function_shape`
   (proto = FunctionPrototype), iterator-result shape, arguments-object
   shapes. Property offsets are recorded so future allocations can use them
   directly.
5. Run `m_function_prototype->initialize(realm)` then
   `m_object_prototype->initialize(realm)` — these install their methods
   (`apply`, `bind`, `call`, `toString` on FP; `hasOwnProperty`, `toString`
   etc. on OP). They can do this *now* because they can call
   `define_native_function`, which allocates `NativeFunction`s whose proto is
   FunctionPrototype (already exists), whose shape is native_function_shape
   (already exists).
6. Allocate iterator prototypes, generator/async-generator prototypes — they
   all hang off ObjectPrototype.
7. Allocate `ErrorPrototype`, `ErrorConstructor`, `FunctionConstructor` (these
   are needed before `AggregateError*` and `AsyncFunction*`).
8. Allocate `ProxyConstructor` (no prototype to wire up — Proxy has no
   `Proxy.prototype`).
9. Allocate the global-object plain functions (`eval`, `isFinite`, `isNaN`,
   `parseFloat`, `parseInt`, URI helpers).
10. Allocate `ObjectConstructor`.
11. **Allocate `%ThrowTypeError%`** as a NativeFunction that just throws,
    set its `length` and `name` to non-configurable, prevent extensions, and
    wrap it in an `Accessor` (used as both getter and setter).
12. Run `initialize_constructor` for each `(constructor, prototype)` pair —
    this defines `prototype.constructor = ctor` (writable+configurable but
    not enumerable) and `ctor.name`. This step is what wires the
    `Object.prototype.constructor === Object` invariant.
13. Lazily initialize the rest (`Array`, `String`, `Number`, ...): each
    constructor/prototype pair is allocated on first access via
    `array_constructor()` calling `initialize_array()`. The macro
    `JS_ENUMERATE_BUILTIN_TYPES` generates these.
14. **Post-pass: `add_restricted_function_properties(*function_prototype, realm)`** —
    install `caller` and `arguments` on `Function.prototype` as
    accessor properties whose getter and setter are both `%ThrowTypeError%`.
    This is the standard "poison" for inspecting strict-mode call frames.
    Done after the function is allocated because it requires
    `%ThrowTypeError%` to exist.

You absolutely cannot do this in a single declarative table; the cycles
require the back-patch dance. The pre-baked shapes (steps 1, 4) matter
because they let every later allocation skip a transition.

For a minimal harmonica bringup, **steps 1–3, 4 (just `new_object_shape` and
`native_function_shape`), 5, and the parts of 12 that wire Object/Function/Error
constructors are mandatory**. Everything else can be lazy.

Canonical file: `LibJS/Runtime/Intrinsics.cpp` lines 213–370. Read this once;
it's only ~150 lines and is the spine of the runtime.

---

## 4. The Constructor / Prototype / Instance triple — Array as worked example

Three classes per built-in type:

| Role | Class | Stored in Intrinsics as | What it is |
|------|-------|-------------------------|------------|
| Constructor function | `ArrayConstructor` | `m_array_constructor` | The thing the user calls as `Array(...)` or `new Array(...)`. A `NativeFunction` subclass. Holds **static** methods (`Array.from`, `Array.of`, `Array.isArray`) as own properties. Its `[[Prototype]]` is `Function.prototype`. |
| Prototype object | `ArrayPrototype` | `m_array_prototype` | The thing all array instances inherit from. Holds **instance** methods (`push`, `map`, `slice`, `@@iterator`, `@@unscopables`). Its `[[Prototype]]` is `Object.prototype`. Itself extends `Array` so that `Array.prototype` is itself an array (length 0). |
| Instance class | `Array` | (allocated per-instance) | Per-instance subclass of `Object`. Carries the indexed storage and the magical `length`. New instances' `[[Prototype]]` is `Array.prototype`. |

`ArrayConstructor::initialize` does (roughly):

1. Sets `Array.prototype` to `realm.intrinsics().array_prototype()` (non-writable,
   non-configurable, non-enumerable — the spec attribute for *any* constructor's
   `prototype` property).
2. Defines static methods (`from`, `of`, `isArray`) with `[[Writable]]+[[Configurable]]`.
3. Defines `Array[@@species]` as an accessor (returns `this`, used by
   `slice`/`concat` to know what subclass to allocate).
4. Defines `Array.length = 1` (the spec-required "length" of the constructor).

`ArrayPrototype::initialize` defines all the prototype methods using
`define_native_function`. Each gets attributes `[[Writable]]:true,
[[Enumerable]]:false, [[Configurable]]:true` — the canonical "hidden but
overrideable method" attributes for prototype methods. It also installs
`Array.prototype[@@iterator]` aliased to `values` (so `Object.is(...,
Array.prototype.values)` returns true), and `@@unscopables` as a frozen-ish
plain object listing methods that should not be visible to `with` statements.

The `constructor` property on the prototype is set later by
`initialize_constructor` (the function in `Intrinsics.cpp`) as a single pass
over all `(ctor, proto)` pairs — keeping that off the per-class init code
makes the bootstrap order tractable.

Canonical files for the worked example:
- `LibJS/Runtime/ArrayConstructor.{h,cpp}`
- `LibJS/Runtime/ArrayPrototype.{h,cpp}`
- `LibJS/Runtime/Array.{h,cpp}` (the instance class — note its overrides of
  `internal_define_own_property` for the magical `length`).

For harmonica:
```java
public final class ArrayConstructor extends NativeFunction { /* call/construct + static methods */ }
public final class ArrayPrototype  extends JSArray         { /* instance methods, itself a length-0 array */ }
public final class JSArray         extends JSObject        { /* indexed storage + length internal-method overrides */ }
```

---

## 5. Native-function calling convention

LibJS has a uniform shape for built-ins:

```cpp
JS_DEFINE_NATIVE_FUNCTION(ArrayPrototype::push) {
    auto this_object = TRY(vm.this_value().to_object(vm));
    // ... access vm.argument(0..n), vm.argument_count()
    return Value(new_length);  // or vm.throw_completion<TypeError>(...)
}
```

The macro expands to a static method `ThrowCompletionOr<Value> push(VM& vm)`.
A native function takes **only `VM&`**; everything else is fished out of
`vm`:
- `vm.this_value()` — the receiver.
- `vm.argument_count()` — number of args.
- `vm.argument(i)` — i'th arg, or `undefined` if `i >= argument_count()`.
- `vm.current_realm()` — for allocating new objects in the right realm.

Return is `ThrowCompletionOr<Value>` — a sum type that is either a `Value`
(normal return) or an abrupt completion (a thrown exception). Builtins call
`TRY(...)` to propagate. `vm.throw_completion<TypeError>(ErrorType::Foo, args...)`
is the standard way to throw.

### Registration

```cpp
define_native_function(realm, vm.names.push, push, 1, attr);
//                            ^name           ^fn   ^len ^writable|configurable
```

- `define_native_function` allocates a `NativeFunction` cell, sets its `name`
  and `length` as own data properties (`[[Writable]]:false`,
  `[[Configurable]]:true` for both — spec-mandated for built-ins), and
  installs it on `this` with the given attributes.
- The `length` argument is the *declared* `Function.length` — for
  `Array.prototype.push` the spec says 1.
- The function's `[[Prototype]]` is `Function.prototype` (set when the
  NativeFunction is allocated). Its shape is the pre-baked
  `native_function_shape`.

### Constructors via NativeFunction

`NativeFunction` itself reports `has_constructor() = false`. Subclasses that
*are* constructors override `internal_construct` and `has_constructor()`. So
`ArrayConstructor extends NativeFunction` overrides `call()` (for
`Array(...)`) and `construct(new_target)` (for `new Array(...)`).

### Java-shape sketch

```java
@FunctionalInterface
public interface NativeFn {
    Value invoke(VM vm) throws Completion;
}

public class NativeFunction extends JSFunction {
    private final NativeFn behavior;
    private final int      length;        // initial value, spec-mandated
    private final String   initialName;

    public Value internalCall(ExecutionContext ctx, Value thisValue) throws Completion {
        return behavior.invoke(ctx.vm());
    }
}
```

Helper on `JSObject`:
```java
public void defineNativeFunction(Realm r, PropertyKey name, NativeFn fn, int length, int attrs) {
    NativeFunction f = NativeFunction.create(r, fn, length, name);
    defineDirectProperty(name, f, attrs);
}
```

Canonical files: `LibJS/Runtime/NativeFunction.{h,cpp}`,
`LibJS/Runtime/Object.cpp` (`define_native_function` definition).

---

## 6. Property attributes for built-ins (the rules nobody writes down)

Spec rules that LibJS encodes uniformly:

| Property | W | E | C | Notes |
|----------|---|---|---|-------|
| Prototype methods (`Array.prototype.push`, `Object.prototype.toString`) | ✓ | ✗ | ✓ | The default for `define_native_function` |
| Static methods on constructors (`Array.from`, `Object.keys`) | ✓ | ✗ | ✓ | Same |
| `Constructor.prototype` (`Array.prototype` on `Array`) | ✗ | ✗ | ✗ | Locked. `define_direct_property(prototype, ..., 0)` |
| `Prototype.constructor` (`Array.prototype.constructor`) | ✓ | ✗ | ✓ | Set by `initialize_constructor` |
| `name` and `length` on a built-in function | ✗ | ✗ | ✓ | Configurable but not writable |
| `@@toStringTag` on prototypes | ✗ | ✗ | ✓ | E.g. `Map.prototype[@@toStringTag] = "Map"` |
| `@@iterator`, `@@unscopables`, `@@species` | varies | ✗ | ✓ | See per-spec |
| Constants (`Math.PI`, `Number.MAX_VALUE`) | ✗ | ✗ | ✗ | Frozen |
| Globals on the global object: `NaN`, `Infinity`, `undefined` | ✗ | ✗ | ✗ | Frozen |
| Globals: `globalThis`, function/constructor bindings | ✓ | ✗ | ✓ | Writable so user code can shadow |
| `caller`, `arguments` on Function.prototype, strict ECMAScript fns | n/a | ✗ | ✓ | Accessor pair with `%ThrowTypeError%` as both get and set |

The pattern: built-in *methods* and *constructors-as-globals* are
overrideable; built-in *layout properties* (`prototype`, constants) are
locked.

### Array's magical `length`

`length` on an Array instance is **not** a normal data property despite
appearing as one. `Array` overrides `internal_define_own_property` to
intercept writes to `"length"`: setting it to a smaller value truncates the
array, setting it to a larger value pre-allocates, and the descriptor's
configurability is forever false. The instance has a side bit `m_length_writable`
that tracks whether the array was created with a writable length (the normal
case, but `Object.defineProperty(arr, 'length', { writable: false })` flips it).

`Array.prototype.length` is just `0` — a regular data property — because
`Array.prototype` is itself an Array but its length never changes.

This is why `Array` needs to override **`internal_define_own_property`,
`internal_set`, and `internal_get_own_property`** rather than just storing a
length field. Read `LibJS/Runtime/Array.cpp`'s implementations of those —
they're the most subtle exotic-object code in the runtime apart from Proxy.

### Implications for harmonica's Shape design

When you build shapes, two properties of these attribute defaults matter:
- Prototype methods all have `Writable|Configurable` — meaning they live in
  shape slots that *can* transition out (to a different value or a deleted
  state). User code overriding `Array.prototype.push` is legal and cheap.
- Constants are non-writable+non-configurable — IC code can treat their
  values as compile-time constants for the lifetime of the realm.

---

## 7. Abstract operations dispatch

ECMA-262 spreads abstract operations across §7 (type conversion / testing),
§7.3 (operations on objects), §10.1 (ordinary internal methods).

LibJS organizes them like this:

### On `Value` (free functions on the tagged-union type)
- `Value::to_primitive(vm, hint)` — the §7.1.1 ToPrimitive entry point.
  Looks for `@@toPrimitive`, falls back to `OrdinaryToPrimitive`.
- `Value::to_string(vm)`, `Value::to_number(vm)`, `Value::to_boolean()`,
  `Value::to_object(vm)`, `Value::to_int32`, `Value::to_uint32`,
  `Value::to_property_key(vm)`, etc.
- `Value::to_numeric(vm)` — handles BigInt vs. Number.

### On `Object` (instance methods, mostly non-virtual)
- `Object::ordinary_to_primitive(hint)` — the fallback when no `@@toPrimitive`.
- `Object::get(key)`, `Object::set(key, value, throwOnFail)` — non-virtual,
  call the virtual `internal_get`/`internal_set`.
- `Object::has_property`, `Object::has_own_property`,
  `Object::create_data_property`, `Object::define_property_or_throw`.
- `Object::is_extensible()`, `Object::set_integrity_level(IntegrityLevel)`.

### Free functions in `AbstractOperations.{h,cpp}`
- `call_impl(vm, fn, this, args)` — the §7.3.13 Call operation.
- `construct_impl(vm, ctor, args, newTarget)` — the §7.3.14 Construct.
- `length_of_array_like(vm, obj)`.
- `species_constructor(vm, obj, default)`.
- `get_function_realm(vm, fn)`.
- `get_prototype_from_constructor(vm, ctor, intrinsicDefault)`.
- `is_compatible_property_descriptor(...)`.
- Iterator protocol: `get_iterator(vm, obj, hint)`, `iterator_next`,
  `iterator_step`, `iterator_close`, `iterator_value`, `iterator_complete`.

### Where `@@toPrimitive` is consulted

`Value::to_primitive(vm, preferred)`:

1. If `Type(input) is Object`:
   1. `exoticToPrim = GetMethod(input, @@toPrimitive)`. ← here
   2. If not undefined, call it with the hint string; assert result is not
      Object; return it.
3. Otherwise (or if no `@@toPrimitive`), call `OrdinaryToPrimitive(input, hint)`,
   which tries `valueOf` then `toString` (or reverse, depending on hint).

So the symbol lookup is only inside `Value::to_primitive`. `OrdinaryToPrimitive`
is its own function on `Object`.

Canonical files: `LibJS/Runtime/Value.cpp` (the `to_*` family),
`LibJS/Runtime/AbstractOperations.{h,cpp}`, `LibJS/Runtime/Iterator.{h,cpp}`.

### Java-shape sketch
```java
public final class Value {
    public Value   toPrimitive(VM vm, PreferredType hint)  throws Completion { ... }
    public String  toString(VM vm)                         throws Completion { ... }
    public Value   toNumber(VM vm)                         throws Completion { ... }
    public JSObject toObject(VM vm)                        throws Completion { ... }
    public PropertyKey toPropertyKey(VM vm)                throws Completion { ... }
}

public final class AbstractOps {  // existing in harmonica — extend it
    public static Value    call(VM vm, Value fn, Value thisVal, Value[] args)  throws Completion;
    public static JSObject construct(VM vm, JSFunction ctor, Value[] args, JSFunction newTarget) throws Completion;
    public static long     lengthOfArrayLike(VM vm, JSObject o)                 throws Completion;
    public static IteratorRecord getIterator(VM vm, Value obj, IteratorHint h)  throws Completion;
}
```

---

## 8. Well-known intrinsics — what must exist when

### Tier 0 — present before *any* user code runs
The bootstrap-fixed-point set. Without these, `function f(){} f()` doesn't work.

- `%Object.prototype%`
- `%Function.prototype%` (callable, returns undefined)
- `%ThrowTypeError%` (the poison function)
- `%Object.prototype%.constructor → %Object%` requires `%Object%` (the
  constructor) to exist.
- The GlobalObject and GlobalEnvironment.
- Pre-baked shapes: empty-object, new-object (proto = Object.prototype),
  normal-function, native-function.

### Tier 1 — required for "say something useful"
- `%Error%` and per-type Error constructors/prototypes (`TypeError`,
  `RangeError`, `ReferenceError`, `SyntaxError`, `URIError`,
  `EvalError`) — needed because the interpreter throws these from many places
  long before user code throws.
- `%Array%` + `%Array.prototype%` (for spread, rest, array literals).
- `%String%` + `%String.prototype%` (indexing into strings, `String(x)`).
- `%Number%` + `%Number.prototype%` (`toString` formatting).
- `%Boolean%` + `%Boolean.prototype%`.
- `%Symbol%` + the well-known symbols (`@@iterator`, `@@toPrimitive`,
  `@@toStringTag`, `@@hasInstance`, `@@species`, `@@unscopables`, `@@isConcatSpreadable`).
  Even if user code never names them, the engine consults them.

### Tier 2 — non-trivial programs
- `%Math%`, `%JSON%`.
- `%Iterator%`, `%ArrayIterator%`, `%StringIterator%`, `%MapIterator%`,
  `%SetIterator%` prototypes (needed for `for…of` and spread).
- `%Date%`.
- `%RegExp%` + `%RegExp.prototype%`.

### Tier 3 — modern JS
- `%Promise%` and the microtask machinery.
- Async / generator infrastructure: `%AsyncFunction%`, `%GeneratorFunction%`,
  `%AsyncGeneratorFunction%`, `%Generator.prototype%`, `%AsyncGenerator.prototype%`,
  `%AsyncFromSyncIterator.prototype%`.
- `%Proxy%`, `%Reflect%`.
- `%Map%`, `%Set%`, `%WeakMap%`, `%WeakSet%`, `%WeakRef%`, `%FinalizationRegistry%`.
- TypedArrays: `%TypedArray%`, the 11 concrete subclasses, `%ArrayBuffer%`,
  `%SharedArrayBuffer%`, `%DataView%`, `%Atomics%`.

### Tier 4 — large feature sets, ship later
- `%Intl%` (Collator, NumberFormat, DateTimeFormat, ListFormat, PluralRules,
  RelativeTimeFormat, DisplayNames, Locale, Segmenter, DurationFormat).
- `%Temporal%` (~10 types).
- `%DisposableStack%`, `%AsyncDisposableStack%`, `SuppressedError`,
  `AggregateError`, the explicit-resource-management bits.
- `%console%` (host-defined but shipped by every engine).

---

## 9. Recommended bringup order for harmonica

**Stage 0 — prep work (no JS visible yet).**
- Replace the property `LinkedHashMap` in `JSObject` with a Shape-aware
  storage layer. Keep the API (`get`, `set`, `has`, `delete`) the same so
  the interpreter doesn't break. Shape-with-fallback-dictionary is fine for
  v1.
- Introduce `PropertyKey` (a sealed type: string-key, integer-key, symbol-key).
- Introduce `PropertyDescriptor` and `PropertyAttributes`.
- Introduce `Completion` / `ThrowCompletionOr` (called `AbruptCompletion` in
  harmonica today — extend it to be a return shape, not a thrown exception).
- Introduce a `Realm` class and route the interpreter's globals through
  `realm.globalObject()`. Decide on `ExecutionContext` to carry `realm`.

**Stage 1 — minimal bootstrap. Goal: `(function(o){return o.x;})({x:1})` works.**
- Allocate `Object.prototype` (proto = null).
- Allocate `Function.prototype` (proto = Object.prototype, callable, returns undefined).
- Pre-bake `newObjectShape` and `nativeFunctionShape`.
- Allocate `%ThrowTypeError%`. Wrap as `Accessor`.
- Allocate `Object.prototype` methods: `hasOwnProperty`, `toString`, `valueOf`,
  `isPrototypeOf`, `propertyIsEnumerable`, the `__proto__` accessor.
- Allocate `Function.prototype` methods: `call`, `apply`, `bind`, `toString`.
  Install `caller`/`arguments` poison via `add_restricted_function_properties`.
- Allocate Object constructor (`Object`, `Object.create`, `Object.keys`,
  `Object.getOwnPropertyDescriptor`, `Object.defineProperty`,
  `Object.getPrototypeOf`, `Object.setPrototypeOf`, `Object.freeze`,
  `Object.assign`, `Object.is`).
- Allocate Function constructor (skip the parsing — just enough for `Function`
  to be a thing on the global with a `.prototype`).
- Allocate the `Error` family: `Error`, `TypeError`, `RangeError`,
  `ReferenceError`, `SyntaxError`, `URIError`. They're needed because the
  engine throws them.
- Build the GlobalObject. Install `globalThis`, `NaN`, `Infinity`, `undefined`,
  `Object`, `Function`, error constructors. Install `parseInt`, `parseFloat`,
  `isFinite`, `isNaN` as plain native functions.

  **test262 unblocks:** `built-ins/Object/prototype/*`, `built-ins/Function/prototype/*`,
  `built-ins/Error/*` (basic), `language/expressions/property-accessors/*`,
  `language/expressions/call/*` (where `this` doesn't depend on tier-2 types).

**Stage 2 — primitives' prototypes. Goal: `"foo".toUpperCase()` and `(3.14).toFixed(2)`.**
- `%Boolean%` + `%Boolean.prototype%`.
- `%Number%` + `%Number.prototype%` (toFixed, toString(radix), constants).
- `%String%` + `%String.prototype%`. **Note: StringObject is exotic** — it
  exposes integer-keyed character access as own properties (`"abc"[0] === "a"`).
  Override `internal_get_own_property` and `internal_own_property_keys`.
- `%Symbol%` + the well-known symbols. Wire `@@toPrimitive` lookup in
  `Value.toPrimitive`.
- `%BigInt%` if your value-rep supports it (skip otherwise — guard with a
  clear "BigInt not implemented" throw).

  **test262 unblocks:** `built-ins/String/prototype/*`, `built-ins/Number/prototype/*`,
  `built-ins/Boolean/prototype/*`, the half of `built-ins/Symbol/*` that
  doesn't need iterators, autoboxing tests in `language/expressions/`.

**Stage 3 — Array and friends. Goal: `[1,2,3].map(x=>x*2).filter(...)` works.**
- `%Array%` + `%Array.prototype%`. Implement the magical `length`. Implement
  the indexed-storage on `JSArray` (start with packed; add holey-as-Value-array
  later; dictionary much later).
- Array static methods: `Array.from`, `Array.of`, `Array.isArray`, `Array[@@species]`.
- All 38 prototype methods. Use the spec algorithms verbatim — many are subtle
  (`sort`, `splice`, `flat`).
- `%ArrayIterator%`, `%StringIterator%` prototypes — needed for spread / for-of.
- `%Iterator%` prototype (the abstract one).

  **test262 unblocks:** `built-ins/Array/prototype/*`, `built-ins/Array/from*`,
  much of `language/statements/for-of/*`, `language/expressions/spread/*`.

**Stage 4 — namespace objects.**
- `%Math%` (~30 static methods, all data properties on a plain object).
- `%JSON%` (`parse` and `stringify`). `stringify` is non-trivial — uses the
  `replacer` callback path and recursive `@@toJSON` handling.
- `%Date%` + `%Date.prototype%`. Mostly mechanical but voluminous.
- `%RegExp%` + `%RegExp.prototype%`. Requires a regex engine — your call
  whether to wrap `java.util.regex` (close-but-not-spec-conformant — you'll
  fail many test262 tests on character classes) or write a real one.

  **test262 unblocks:** `built-ins/Math/*`, `built-ins/JSON/*`, much of
  `built-ins/Date/*`, `built-ins/RegExp/*` (modulo regex-engine fidelity).

**Stage 5 — collections & weak references.**
- `%Map%`, `%Set%`, `%WeakMap%`, `%WeakSet%`, their iterators.
- `%WeakRef%`, `%FinalizationRegistry%` (needs cooperation with your
  GC — for harmonica's JVM GC, use `WeakReference`/`PhantomReference`).

**Stage 6 — async.**
- `%Promise%` + the microtask queue. Hook the queue into the
  bytecode-interpreter's tick loop.
- `%AsyncFunction%`, `%GeneratorFunction%`, `%AsyncGeneratorFunction%` and
  their prototypes. These require a *generator-aware* lowering in the bytecode
  generator (suspend/resume opcodes, `Frame::resumePc`, etc.) — likely a
  separate large project.

**Stage 7 — proxy / reflect / typed arrays.**
- `%Proxy%`, `%Reflect%`. Proxy is the only place where every internal-method
  override is non-trivial; treat its implementation as a port of the spec
  algorithms verbatim.
- `%ArrayBuffer%`, `%DataView%`, `%TypedArray%` + concrete subclasses.

**Stage 8 — `Intl`, `Temporal`, `console`, B.x annexes.**
Defer indefinitely. Add `console.log` (host-defined, ~10 lines) early just
for ergonomics — it's not standardized but everyone ships it.

---

## 10. Pointers back to LibJS — file-by-file map

The future session should read these files directly when working on each
topic. **Don't grep for behavior — read the file.**

| Topic | Read |
|-------|------|
| Realm record, `InitializeHostDefinedRealm` | `Runtime/Realm.{h,cpp}` |
| The intrinsics record + the bootstrap order | `Runtime/Intrinsics.{h,cpp}` (the `initialize_intrinsics` function) |
| GlobalObject + `SetDefaultGlobalBindings` + `eval`/`parseInt`/etc. | `Runtime/GlobalObject.{h,cpp}` |
| The base `Object`, `internal_*` methods, ordinary algorithms | `Runtime/Object.{h,cpp}` |
| Hidden classes, slot offsets, transitions | `Runtime/Shape.{h,cpp}` |
| Indexed storage modes (Packed/Holey/Dictionary) | `Runtime/IndexedProperties.{h,cpp}` |
| PropertyDescriptor, PropertyKey, PropertyAttributes | `Runtime/PropertyDescriptor.{h,cpp}`, `Runtime/PropertyKey.h`, `Runtime/PropertyAttributes.h` |
| Accessor cell (getter/setter pair) | `Runtime/Accessor.h` |
| Free abstract operations (Call, Construct, GetMethod, GetIterator, …) | `Runtime/AbstractOperations.{h,cpp}` |
| Value type conversions (ToPrimitive, ToString, ToNumber, ToObject) | `Runtime/Value.cpp` |
| Iterator protocol algorithms | `Runtime/Iterator.{h,cpp}` |
| Throw/return completion machinery | `Runtime/Completion.{h,cpp}` |
| Function-object base (`[[Call]]`, `[[Construct]]` interface) | `Runtime/FunctionObject.{h,cpp}` |
| NativeFunction, RawNativeFunction, CapturingNativeFunction, CreateBuiltinFunction | `Runtime/NativeFunction.{h,cpp}` |
| ECMAScript (user-defined) function object | `Runtime/ECMAScriptFunctionObject.{h,cpp}` |
| BoundFunction (`fn.bind(this)` result) | `Runtime/BoundFunction.{h,cpp}` |
| `Object.prototype` methods (hasOwnProperty, toString, valueOf, __proto__, isPrototypeOf, …) | `Runtime/ObjectPrototype.{h,cpp}` |
| `Object` constructor (Object.keys, Object.create, defineProperty, freeze, …) | `Runtime/ObjectConstructor.{h,cpp}` |
| `Function.prototype` (call, apply, bind, toString, @@hasInstance) | `Runtime/FunctionPrototype.{h,cpp}` |
| Array exotic object (length-magic, indexed methods) | `Runtime/Array.{h,cpp}` |
| ArrayPrototype methods (38 of them) | `Runtime/ArrayPrototype.{h,cpp}` |
| ArrayConstructor (Array.from, Array.of, Array.isArray, @@species) | `Runtime/ArrayConstructor.{h,cpp}` |
| StringObject exotic (integer-keyed character properties) | `Runtime/StringObject.{h,cpp}` |
| Error class hierarchy + `ErrorData` slot | `Runtime/Error.{h,cpp}`, `Runtime/ErrorTypes.h` |
| Math, JSON, Reflect, Atomics namespaces | `Runtime/MathObject.{h,cpp}`, `Runtime/JSONObject.{h,cpp}`, `Runtime/ReflectObject.{h,cpp}`, `Runtime/AtomicsObject.{h,cpp}` |
| Symbol + well-known symbols + Symbol registry | `Runtime/Symbol.{h,cpp}`, `Runtime/SymbolConstructor.{h,cpp}`, `Runtime/SymbolPrototype.{h,cpp}` |
| Map / Set / WeakMap / WeakSet | `Runtime/Map*.{h,cpp}`, `Runtime/Set*.{h,cpp}`, `Runtime/WeakMap*.{h,cpp}`, `Runtime/WeakSet*.{h,cpp}` |
| Proxy (every internal method routes through traps) | `Runtime/ProxyObject.{h,cpp}`, `Runtime/ProxyConstructor.{h,cpp}` |
| Promise + Job/Microtask machinery | `Runtime/Promise*.{h,cpp}`, `Runtime/Job.{h,cpp}` |
| Common property-name interning | `Runtime/CommonPropertyNames.h` |
| Bytecode-side calls into the runtime (how `GetById`/`PutById` reach `Object::get`) | `LibJS/Bytecode/Interpreter.cpp` (the `get_by_id`, `put_by_id` handlers) |

---

## Closing notes

1. **Build the smallest fixed-point first.** Stage 1 is the only stage where
   ordering is load-bearing; everything later is incremental.
2. **Don't shortcut PropertyDescriptor.** Many subtle behaviors (`Object.freeze`
   vs. `Object.preventExtensions`, descriptor merging during
   `defineProperty`) only work if you carry the full optional-fields struct
   through the abstract-operations layer.
3. **Resist the urge to make `Realm` global.** Pass it in via
   `ExecutionContext`. You'll thank yourself when test262's `$262.createRealm`
   stops segfaulting.
4. **When in doubt, read the same file LibJS does.** The spec is the source
   of truth; the LibJS file is an annotated translation. If you're unsure
   what an attribute should be, the LibJS source has the answer in two
   minutes; the spec takes thirty.
5. **Stub-with-throw, not stub-with-quiet-default.** Per global CLAUDE.md:
   if `Symbol.toPrimitive` lookup isn't wired yet, throw a clear
   `UnimplementedError("Symbol.toPrimitive lookup")` from
   `Value.toPrimitive`. Returning a wrong value silently here will manifest
   as test failures three layers up.
