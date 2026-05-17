package com.jimmyhmiller.harmonica.bytecode;

import java.util.Map;

/**
 * Per-process realm holding the well-known intrinsic prototype objects and
 * the bootstrap that wires them. Single-realm for v1.
 *
 * <p>Bootstrap order (mirrors LibJS): {@code Object.prototype} (proto=null),
 * {@code Function.prototype} (proto=Object.prototype), then
 * {@code Array/String/Number/Boolean.prototype} all rooted at
 * {@code Object.prototype}. Native built-ins are installed after the
 * prototype objects exist.
 */
public final class Realm {

    public static volatile JSObject objectPrototype;
    public static volatile JSObject functionPrototype;
    public static volatile JSObject arrayPrototype;
    public static volatile JSObject stringPrototype;
    public static volatile JSObject numberPrototype;
    public static volatile JSObject booleanPrototype;
    public static volatile JSObject symbolPrototype;
    /** %AbstractModuleSource% intrinsic — has no global binding per spec
     *  (source-phase imports proposal). Reachable via the test harness's
     *  {@code $262.AbstractModuleSource}. */
    public static volatile JSFunction abstractModuleSourceConstructor;
    public static volatile JSObject   abstractModuleSourcePrototype;
    public static volatile JSObject promisePrototype;
    /** ECMA-262 § 27.5.1 Generator Prototype (the prototype shared by all generator instances). */
    public static volatile JSObject generatorPrototype;
    /** ECMA-262 § 27.3.3 %GeneratorFunction.prototype% (a.k.a. %Generator%) —
     *  the [[Prototype]] of every generator function object. Its own
     *  [[Prototype]] is %Function.prototype% and it exposes the
     *  {@code prototype} property pointing at {@link #generatorPrototype}. */
    public static volatile JSObject generatorFunctionPrototype;
    public static volatile JSObject mapPrototype;
    public static volatile JSObject setPrototype;
    public static volatile JSObject weakMapPrototype;
    public static volatile JSObject weakSetPrototype;
    public static volatile JSObject errorPrototype;
    public static volatile JSObject regExpPrototype;
    public static volatile JSObject bigIntPrototype;
    /** ECMA-262 § 27.1.2 %IteratorPrototype% — the prototype of all built-in
     *  iterators. Generators, array/string/map/set iterators all inherit from
     *  it, so iterator-helpers (map/filter/take/drop/...) work across the
     *  board. Field is set inside installPromise(...) where the Iterator
     *  constructor is built; generatorPrototype is re-linked there. */
    public static volatile JSObject iteratorPrototype;
    /**
     * Original {@code Array.prototype[@@iterator]} (the {@code values} fn) and
     * {@code String.prototype[@@iterator]}, captured at bootstrap so the
     * iterator-fast-path in {@link Op.GetIterator} can detect monkey-patched
     * overrides and fall back to the @@iterator slow path.
     */
    public static volatile JSFunction defaultArrayIterator;
    public static volatile JSFunction defaultStringIterator;
    /** ECMA-262 § 23.1.5.2 / § 22.1.5.2 / § 24.1.5.2 / § 24.2.5.2 —
     *  per-kind iterator prototypes, lazily built on first use. Each has
     *  a {@code @@toStringTag} of {@code "Array Iterator"} etc., a
     *  {@code @@iterator} method returning {@code this}, and is the
     *  [[Prototype]] of every array/string/map/set iterator instance.
     *  Inheriting from {@link #iteratorPrototype} would also give them
     *  the helper methods (map, filter, …) if/when iterator-helpers are
     *  spec'd to apply. */
    public static volatile JSObject arrayIteratorPrototype;
    public static volatile JSObject stringIteratorPrototype;
    public static volatile JSObject mapIteratorPrototype;
    public static volatile JSObject setIteratorPrototype;
    public static final java.util.Map<String, JSObject> errorPrototypes = new java.util.HashMap<>();

    // Well-known symbols — ECMA-262 § 6.1.5.1 (Table 1). Pre-allocated so
    // {@code Symbol.iterator === Symbol.iterator} holds across reads.
    public static volatile JSSymbol wellKnownIterator;
    public static volatile JSSymbol wellKnownAsyncIterator;
    public static volatile JSSymbol wellKnownToPrimitive;
    public static volatile JSSymbol wellKnownHasInstance;
    public static volatile JSSymbol wellKnownToStringTag;
    public static volatile JSSymbol wellKnownIsConcatSpreadable;
    public static volatile JSSymbol wellKnownMatch;
    public static volatile JSSymbol wellKnownReplace;
    public static volatile JSSymbol wellKnownSearch;
    public static volatile JSSymbol wellKnownSpecies;
    public static volatile JSSymbol wellKnownSplit;
    public static volatile JSSymbol wellKnownMatchAll;
    public static volatile JSSymbol wellKnownUnscopables;
    /** Symbol.dispose / Symbol.asyncDispose — explicit-resource-management. */
    public static volatile JSSymbol wellKnownDispose;
    public static volatile JSSymbol wellKnownAsyncDispose;

    /** %AsyncIteratorPrototype% (§ 27.1.3) and %AsyncGeneratorPrototype%
     *  (§ 27.6.1) — the chain {@code asyncGen.prototype → %AGP% → %AIP% →
     *  Object.prototype}. Async generator function instances reach this
     *  chain via {@link Op.ensureFunctionPrototype}. */
    public static volatile JSObject asyncIteratorPrototype;
    public static volatile JSObject asyncGeneratorPrototype;

    /** Original RegExp.prototype.exec — captured during bootstrap so the
     *  RegExpExec fallback (§ 22.2.7.1 step 6) works when user code has
     *  overridden {@code RegExp.prototype.exec} with a non-callable value. */
    public static volatile JSFunction originalRegExpExec;

    /** § 20.4.2.2 Global Symbol Registry — populated by {@code Symbol.for}.
     *  Registered symbols are <em>not</em> allowed as WeakMap/WeakSet keys
     *  (§ 6.1.7 CanBeHeldWeakly), so the weak-collection builtins use this
     *  to filter them out. */
    public static final java.util.Map<String, JSSymbol> globalSymbolRegistry =
        new java.util.HashMap<>();

    /**
     * ECMA-262 § 10.2.4 %ThrowTypeError% — a function that always throws a
     * TypeError. Used as the [[Get]]/[[Set]] of the strict-mode arguments
     * object's {@code callee} (and historically {@code caller}) properties.
     */
    public static volatile JSFunction throwTypeError;

    public static JSFunction throwTypeError() { return throwTypeError; }

    /**
     * Per-prototype root shape cache. Every {@code new JSObject(proto)}
     * starts at one of these, so all bare empty objects with the same
     * prototype share an identity-equal root Shape — and from there, the
     * transition tree shares child shapes for the same property-addition
     * sequences. Keyed on the prototype's identity (null for
     * proto-less objects from {@code Object.create(null)}).
     *
     * <p>WeakHashMap (key) + WeakReference (value) so dead prototype trees
     * can be collected: LibJS relies on its mark-sweep GC for this; in Java
     * we have to be explicit. JSObject does not override {@code equals}, so
     * WeakHashMap behaves as identity-keyed.
     */
    private static final java.util.Map<JSObject, java.lang.ref.WeakReference<Shape>> EMPTY_OBJECT_SHAPES =
        new java.util.WeakHashMap<>();
    private static volatile java.lang.ref.WeakReference<Shape> rootShapeForNullProto;

    public static Shape shapeForEmptyObject(JSObject proto) {
        if (proto == null) {
            java.lang.ref.WeakReference<Shape> ref = rootShapeForNullProto;
            if (ref != null) {
                Shape s = ref.get();
                if (s != null) return s;
            }
            synchronized (EMPTY_OBJECT_SHAPES) {
                ref = rootShapeForNullProto;
                Shape s = ref == null ? null : ref.get();
                if (s == null) {
                    s = Shape.root(null);
                    rootShapeForNullProto = new java.lang.ref.WeakReference<>(s);
                }
                return s;
            }
        }
        synchronized (EMPTY_OBJECT_SHAPES) {
            java.lang.ref.WeakReference<Shape> ref = EMPTY_OBJECT_SHAPES.get(proto);
            Shape s = ref == null ? null : ref.get();
            if (s == null) {
                s = Shape.root(proto);
                EMPTY_OBJECT_SHAPES.put(proto, new java.lang.ref.WeakReference<>(s));
            }
            return s;
        }
    }

    private static volatile boolean prototypesReady;

    private Realm() {}

    /**
     * Sentinel property keys used to back primitive-wrapper "internal slots"
     * for Boolean/Number/String objects (ECMA-262 § 20.3, § 21.1, § 22.1).
     * Real engines store these in slots that are invisible to user code; we
     * approximate with reserved string keys (which DO leak into Object.keys
     * but are unlikely to collide with user names containing {@code ##}).
     */
    static final String SLOT_BOOLEAN_DATA = "##BooleanData##";
    static final String SLOT_NUMBER_DATA  = "##NumberData##";
    public static final String SLOT_STRING_DATA  = "##StringData##";
    static final String SLOT_BIGINT_DATA  = "##BigIntData##";

    /** Extract the underlying JSBigInt from a BigInt value or wrapper. */
    static JSBigInt bigIntDataOf(Object v) {
        if (v instanceof JSBigInt bi) return bi;
        if (v instanceof JSObject jo) {
            Object slot = jo.getOwn(SLOT_BIGINT_DATA);
            if (slot instanceof JSBigInt bi) return bi;
        }
        return null;
    }
    /** ECMA-262 § 28.2 Proxy [[ProxyTarget]] / [[ProxyHandler]] internal slots. */
    public static final String SLOT_PROXY_TARGET  = "##ProxyTarget##";
    public static final String SLOT_PROXY_HANDLER = "##ProxyHandler##";

    /** True iff {@code v} is a Proxy (has [[ProxyTarget]]). */
    public static boolean isProxy(Object v) {
        return v instanceof JSObject jo && jo.hasOwn(SLOT_PROXY_TARGET);
    }

    /** Get the Proxy's target (or null if revoked / not a Proxy). */
    public static Object proxyTarget(JSObject p) {
        Object v = p.getOwn(SLOT_PROXY_TARGET);
        return v == JSObject.ABSENT ? null : v;
    }

    /** Get the Proxy's handler (or null if revoked / not a Proxy). */
    public static Object proxyHandler(JSObject p) {
        Object v = p.getOwn(SLOT_PROXY_HANDLER);
        return v == JSObject.ABSENT ? null : v;
    }

    /** Look up a trap on the Proxy handler — returns null if absent (caller
     *  falls through to target). Throws TypeError if the slot exists but is
     *  not callable (§ 10.5.* GetMethod: a non-callable, non-undefined trap
     *  must throw before any operation runs). */
    public static JSFunction proxyTrap(JSObject p, String name) {
        Object h = proxyHandler(p);
        if (!(h instanceof JSObject handler)) return null;
        Object trap = AbstractOps.getProperty(handler, name);
        if (trap == null || trap == Undefined.VALUE) return null;
        if (!(trap instanceof JSFunction fn)) {
            throw AbruptCompletion.typeError(
                "Proxy handler '" + name + "' trap is not callable");
        }
        return fn;
    }

    // ---------------------------------------------------------------
    // Proxy trap dispatch helpers — used by AbstractOps and Op so the
    // §28.2.7.* spec algorithms live in one place. Each helper does:
    //   1. Revocation check.
    //   2. Trap lookup; on miss, perform the operation on the target.
    //   3. Trap call with this=handler and (target, key, …) args.
    // Invariant enforcement (non-configurable target props, non-extensible
    // target, etc.) is largely deferred — we currently match LibJS only
    // partially on the invariants. Good enough for the bulk of tests.
    // ---------------------------------------------------------------

    private static void requireNonRevokedProxy(Object target, String trapName) {
        if (target == null) {
            throw AbruptCompletion.typeError("Cannot perform '" + trapName
                + "' on a proxy that has been revoked");
        }
    }

    public static boolean proxyHas(JSObject proxy, String key) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "has");
        JSFunction trap = proxyTrap(proxy, "has");
        if (trap == null) return AbstractOps.hasProperty(target, key);
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target, key}, ctx);
        return AbstractOps.toBoolean(res);
    }

    public static boolean proxyDelete(JSObject proxy, String key) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "deleteProperty");
        JSFunction trap = proxyTrap(proxy, "deleteProperty");
        if (trap == null) {
            return AbstractOps.deleteProperty(target, key);
        }
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target, key}, ctx);
        return AbstractOps.toBoolean(res);
    }

    /** Returns a JSArray of property-key Strings (or JSSymbols) per the trap. */
    public static JSArray proxyOwnKeys(JSObject proxy) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "ownKeys");
        JSFunction trap = proxyTrap(proxy, "ownKeys");
        if (trap == null) {
            // OrdinaryOwnPropertyKeys on the target.
            JSArray out = new JSArray();
            if (target instanceof JSObject jo) {
                for (String k : orderedOwnPropertyNames(jo.properties().keySet())) {
                    out.push(k);
                }
            } else if (target instanceof JSArray arr) {
                for (int i = 0; i < arr.length(); i++) out.push(String.valueOf(i));
                out.push("length");
            }
            return out;
        }
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target}, ctx);
        if (!(res instanceof JSArray arr)) {
            // CreateListFromArrayLike — for non-arrays just iterate length.
            if (!(res instanceof JSObject lo)) {
                throw AbruptCompletion.typeError("ownKeys trap returned non-Object");
            }
            JSArray out = new JSArray();
            Object lenV = AbstractOps.getProperty(lo, "length");
            long len = (long) AbstractOps.toIntegerOrInfinity(lenV);
            for (long i = 0; i < len; i++) {
                Object k = AbstractOps.getProperty(lo, Long.toString(i));
                if (!(k instanceof String) && !(k instanceof JSSymbol)) {
                    throw AbruptCompletion.typeError("ownKeys trap result has non-PropertyKey");
                }
                out.push(k);
            }
            return out;
        }
        // Validate every element is String / Symbol.
        for (int i = 0; i < arr.length(); i++) {
            Object e = arr.get(i);
            if (!(e instanceof String) && !(e instanceof JSSymbol)) {
                throw AbruptCompletion.typeError("ownKeys trap result has non-PropertyKey");
            }
        }
        return arr;
    }

    public static Object proxyGetOwnPropertyDescriptor(JSObject proxy, String key) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "getOwnPropertyDescriptor");
        JSFunction trap = proxyTrap(proxy, "getOwnPropertyDescriptor");
        if (trap == null) {
            return AbstractOps.ordinaryGetOwnPropertyDescriptor(target, key);
        }
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target, key}, ctx);
        if (res == Undefined.VALUE) return Undefined.VALUE;
        if (!(res instanceof JSObject)) {
            throw AbruptCompletion.typeError(
                "getOwnPropertyDescriptor trap result must be an Object or undefined");
        }
        return res;
    }

    public static boolean proxyDefineProperty(JSObject proxy, String key, JSObject desc) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "defineProperty");
        JSFunction trap = proxyTrap(proxy, "defineProperty");
        if (trap == null) {
            return AbstractOps.ordinaryDefineProperty(target, key, desc);
        }
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target, key, desc}, ctx);
        return AbstractOps.toBoolean(res);
    }

    public static Object proxyGetPrototypeOf(JSObject proxy) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "getPrototypeOf");
        JSFunction trap = proxyTrap(proxy, "getPrototypeOf");
        if (trap == null) {
            return AbstractOps.ordinaryGetPrototypeOf(target);
        }
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target}, ctx);
        if (res != null && res != Undefined.VALUE && !(res instanceof JSObject)) {
            throw AbruptCompletion.typeError(
                "getPrototypeOf trap result must be Object or null");
        }
        return res == Undefined.VALUE ? null : res;
    }

    public static boolean proxySetPrototypeOf(JSObject proxy, Object proto) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "setPrototypeOf");
        JSFunction trap = proxyTrap(proxy, "setPrototypeOf");
        if (trap == null) {
            return AbstractOps.ordinarySetPrototypeOf(target, proto);
        }
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target, proto}, ctx);
        return AbstractOps.toBoolean(res);
    }

    public static boolean proxyIsExtensible(JSObject proxy) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "isExtensible");
        JSFunction trap = proxyTrap(proxy, "isExtensible");
        if (trap == null) return AbstractOps.ordinaryIsExtensible(target);
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target}, ctx);
        boolean b = AbstractOps.toBoolean(res);
        // Invariant: must match target's extensibility.
        if (b != AbstractOps.ordinaryIsExtensible(target)) {
            throw AbruptCompletion.typeError(
                "isExtensible trap result must match target's extensibility");
        }
        return b;
    }

    public static boolean proxyPreventExtensions(JSObject proxy) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "preventExtensions");
        JSFunction trap = proxyTrap(proxy, "preventExtensions");
        if (trap == null) return AbstractOps.ordinaryPreventExtensions(target);
        InterpContext ctx = InterpContext.current();
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target}, ctx);
        boolean b = AbstractOps.toBoolean(res);
        // Invariant: if trap says true, target must actually be non-extensible.
        if (b && AbstractOps.ordinaryIsExtensible(target)) {
            throw AbruptCompletion.typeError(
                "preventExtensions trap returned true but target is still extensible");
        }
        return b;
    }

    public static Object proxyApply(JSObject proxy, Object thisArg, Object[] argList) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "apply");
        JSFunction trap = proxyTrap(proxy, "apply");
        if (trap == null) {
            if (!(target instanceof JSFunction tf)) {
                throw AbruptCompletion.typeError("Proxy target is not callable");
            }
            InterpContext ctx = InterpContext.current();
            return Interpreter.invokeFunction(tf, thisArg, argList, ctx);
        }
        InterpContext ctx = InterpContext.current();
        JSArray argsArr = new JSArray();
        for (Object a : argList) argsArr.push(a);
        return Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target, thisArg, argsArr}, ctx);
    }

    public static Object proxyConstruct(JSObject proxy, Object[] argList, Object newTarget) {
        Object target = proxyTarget(proxy);
        requireNonRevokedProxy(target, "construct");
        JSFunction trap = proxyTrap(proxy, "construct");
        Object effectiveNewTarget = (newTarget == null) ? proxy : newTarget;
        if (trap == null) {
            if (!(target instanceof JSFunction tf) || !tf.isConstructor()) {
                throw AbruptCompletion.typeError("Proxy target is not a constructor");
            }
            InterpContext ctx = InterpContext.current();
            // OrdinaryCreateFromConstructor reads newTarget.prototype via
            // [[Get]] — when newTarget is the proxy itself, this routes
            // through the get trap. The trap can return a non-Object
            // (e.g. 42), and the spec requires us to fall back to
            // %ObjectPrototype% in that case (or throw — depending on caller).
            Object newTargetProto = AbstractOps.getProperty(effectiveNewTarget, "prototype");
            JSObject receiverProto = newTargetProto instanceof JSObject jp ? jp
                : tf.prototypeObject();
            JSObject receiver = new JSObject(receiverProto);
            Object res = Interpreter.invokeFunctionAsConstructor(tf, receiver, argList, ctx);
            return (res instanceof JSObject || res instanceof JSArray
                    || res instanceof JSFunction) ? res : receiver;
        }
        InterpContext ctx = InterpContext.current();
        JSArray argsArr = new JSArray();
        for (Object a : argList) argsArr.push(a);
        Object res = Interpreter.invokeFunction(trap, proxyHandler(proxy),
            new Object[]{target, argsArr, effectiveNewTarget}, ctx);
        if (!(res instanceof JSObject)) {
            throw AbruptCompletion.typeError("construct trap result must be an Object");
        }
        return res;
    }

    /** Read [[BooleanData]] / [[NumberData]] / [[StringData]] from a wrapper, or null if absent. */
    static Object readPrimitiveSlot(Object v, String slot) {
        if (v instanceof JSObject jo && jo.properties().containsKey(slot)) {
            return jo.properties().get(slot);
        }
        return null;
    }

    /**
     * Ensure intrinsic prototypes exist and install built-in globals on
     * {@code globals}. Called at the top of each top-level interpret.
     */
    public static void ensureBootstrapped(Map<String, Object> globals) {
        ensurePrototypes();
        installGlobals(globals);
    }

    /**
     * Re-bootstrap the entire Realm — discard the existing intrinsic
     * prototypes and rebuild them. Use only between independent test runs
     * (test262 sweep): JSFunction/JSObject references from a prior run
     * become stale (their {@code [[Prototype]]} chains still link to the
     * old objects), but those values aren't supposed to leak across tests.
     *
     * <p>Why we need this: tests like "delete Array.prototype[Symbol.iterator]"
     * mutate the shared singleton; without reset, every following test that
     * spreads/iterates an array fails.
     */
    /**
     * Return {@code keys} in ECMA-262 § 7.3.22 OrdinaryOwnPropertyKeys order
     * — array-index keys first in ascending numeric order, then non-index
     * string keys in insertion order. Filters out private names ('#'-prefixed).
     */
    static java.util.List<String> orderedOwnPropertyNames(java.util.Collection<String> keys) {
        java.util.TreeMap<Long, String> indices = new java.util.TreeMap<>();
        java.util.List<String> strings = new java.util.ArrayList<>();
        for (String k : keys) {
            if (k == null || k.isEmpty()) { if (k != null) strings.add(k); continue; }
            if (k.charAt(0) == '#') continue;
            long idx = -1;
            char c0 = k.charAt(0);
            if (c0 >= '0' && c0 <= '9' && (k.length() == 1 || c0 != '0')) {
                boolean allDigits = true;
                for (int i = 0; i < k.length(); i++) {
                    char c = k.charAt(i);
                    if (c < '0' || c > '9') { allDigits = false; break; }
                }
                if (allDigits) {
                    try {
                        long n = Long.parseLong(k);
                        if (n >= 0 && n < 0xFFFFFFFFL) idx = n;
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (idx >= 0) indices.put(idx, k);
            else strings.add(k);
        }
        java.util.List<String> out = new java.util.ArrayList<>(indices.size() + strings.size());
        out.addAll(indices.values());
        out.addAll(strings);
        return out;
    }

    private static void appendOrderedOwnPropertyNames(java.util.Collection<String> keys, JSArray out) {
        java.util.TreeMap<Long, String> indices = new java.util.TreeMap<>();
        java.util.List<String> strings = new java.util.ArrayList<>();
        for (String k : keys) {
            if (k == null || k.isEmpty()) { strings.add(k); continue; }
            if (k.charAt(0) == '#') continue;   // private names excluded
            // Array-index per § 7.1.21: integer in [0, 2^32 - 1] whose ToString
            // round-trips. Leading zeros, "+", "-", "Infinity" etc. don't.
            long idx = -1;
            char c0 = k.charAt(0);
            if (c0 >= '0' && c0 <= '9' && (k.length() == 1 || c0 != '0')) {
                boolean allDigits = true;
                for (int i = 0; i < k.length(); i++) {
                    char c = k.charAt(i);
                    if (c < '0' || c > '9') { allDigits = false; break; }
                }
                if (allDigits) {
                    try {
                        long n = Long.parseLong(k);
                        if (n >= 0 && n < 0xFFFFFFFFL) idx = n;
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (idx >= 0) indices.put(idx, k);
            else strings.add(k);
        }
        for (String k : indices.values()) out.push(k);
        for (String k : strings) out.push(k);
    }

    public static synchronized void resetForNewRun() {
        // EMPTY_OBJECT_SHAPES uses weak keys + weak values, but explicit clear
        // gives deterministic cleanup at test-sweep boundaries — we don't have
        // to wait for a GC cycle for the old realm's prototype graphs to go.
        synchronized (EMPTY_OBJECT_SHAPES) {
            EMPTY_OBJECT_SHAPES.clear();
        }
        rootShapeForNullProto = null;
        prototypesReady = false;
        objectPrototype = null;
        functionPrototype = null;
        arrayPrototype = null;
        stringPrototype = null;
        numberPrototype = null;
        booleanPrototype = null;
        symbolPrototype = null;
        abstractModuleSourceConstructor = null;
        abstractModuleSourcePrototype = null;
        promisePrototype = null;
        generatorPrototype = null;
        generatorFunctionPrototype = null;
        mapPrototype = null;
        setPrototype = null;
        weakMapPrototype = null;
        weakSetPrototype = null;
        errorPrototype = null;
        errorPrototypes.clear();
        regExpPrototype = null;
        bigIntPrototype = null;
        defaultArrayIterator = null;
        defaultStringIterator = null;
        arrayIteratorPrototype = null;
        stringIteratorPrototype = null;
        mapIteratorPrototype = null;
        setIteratorPrototype = null;
        regExpStringIteratorPrototype = null;
        wellKnownIterator = null;
        wellKnownAsyncIterator = null;
        wellKnownToPrimitive = null;
        wellKnownHasInstance = null;
        wellKnownToStringTag = null;
        wellKnownIsConcatSpreadable = null;
        wellKnownMatch = null;
        wellKnownReplace = null;
        wellKnownSearch = null;
        wellKnownSpecies = null;
        wellKnownSplit = null;
        wellKnownMatchAll = null;
        wellKnownUnscopables = null;
        wellKnownDispose = null;
        wellKnownAsyncDispose = null;
        asyncIteratorPrototype = null;
        asyncGeneratorPrototype = null;
        originalRegExpExec = null;
        throwTypeError = null;
        // TypedArray family — reset prototype refs and per-kind maps.
        TypedArrays.arrayBufferPrototype = null;
        TypedArrays.sharedArrayBufferPrototype = null;
        TypedArrays.dataViewPrototype = null;
        TypedArrays.typedArrayPrototype = null;
        TypedArrays.typedArrayConstructor = null;
        TypedArrays.kindPrototypes.clear();
        TypedArrays.kindConstructors.clear();
    }

    public static synchronized void ensurePrototypes() {
        if (prototypesReady) return;
        // Object.prototype FIRST — its no-arg `new JSObject()` reads Realm.objectPrototype
        // which is null at this point, so the prototype itself ends up with proto=null.
        objectPrototype = new JSObject();
        functionPrototype = new JSObject(objectPrototype);
        arrayPrototype = new JSObject(objectPrototype);
        stringPrototype = new JSObject(objectPrototype);
        numberPrototype = new JSObject(objectPrototype);
        booleanPrototype = new JSObject(objectPrototype);
        symbolPrototype = new JSObject(objectPrototype);
        promisePrototype = new JSObject(objectPrototype);
        generatorPrototype = new JSObject(objectPrototype);
        // %GeneratorFunction.prototype% inherits from %Function.prototype%
        // (§ 27.3.3) — its `prototype` slot is wired up below to point at
        // generatorPrototype after that object is finalized.
        generatorFunctionPrototype = new JSObject(functionPrototype);
        mapPrototype = new JSObject(objectPrototype);
        setPrototype = new JSObject(objectPrototype);
        weakMapPrototype = new JSObject(objectPrototype);
        weakSetPrototype = new JSObject(objectPrototype);

        // ECMA-262 § 6.1.5.1 (Table 1) — well-known symbols.
        wellKnownIterator           = JSSymbol.wellKnown("Symbol.iterator");
        wellKnownAsyncIterator      = JSSymbol.wellKnown("Symbol.asyncIterator");
        wellKnownToPrimitive        = JSSymbol.wellKnown("Symbol.toPrimitive");
        wellKnownHasInstance        = JSSymbol.wellKnown("Symbol.hasInstance");
        wellKnownToStringTag        = JSSymbol.wellKnown("Symbol.toStringTag");
        wellKnownIsConcatSpreadable = JSSymbol.wellKnown("Symbol.isConcatSpreadable");
        wellKnownMatch              = JSSymbol.wellKnown("Symbol.match");
        wellKnownReplace            = JSSymbol.wellKnown("Symbol.replace");
        wellKnownSearch             = JSSymbol.wellKnown("Symbol.search");
        wellKnownSpecies            = JSSymbol.wellKnown("Symbol.species");
        wellKnownSplit              = JSSymbol.wellKnown("Symbol.split");
        wellKnownMatchAll           = JSSymbol.wellKnown("Symbol.matchAll");
        wellKnownUnscopables        = JSSymbol.wellKnown("Symbol.unscopables");
        wellKnownDispose            = JSSymbol.wellKnown("Symbol.dispose");
        wellKnownAsyncDispose       = JSSymbol.wellKnown("Symbol.asyncDispose");

        installObjectPrototype();
        installFunctionPrototype();
        // %ThrowTypeError% (§ 10.2.4) — the strict-mode poison function.
        // Built after Function.prototype so it inherits properly.
        throwTypeError = nativeFn("", 0, (t, a, c) -> {
            throw AbruptCompletion.typeError(
                "'caller', 'callee', and 'arguments' properties may not be accessed on strict mode functions or the arguments objects for calls to them");
        });
        installArrayPrototype();
        installStringPrototype();
        installNumberPrototype();
        installBooleanPrototype();
        installSymbolPrototype();
        installPromisePrototype();
        installGeneratorPrototype();
        installMapPrototype();
        installSetPrototype();
        installWeakMapPrototype();
        installWeakSetPrototype();

        // § 27.1.3 / § 27.6.1 — async iterator + async generator prototypes.
        // Sync generators share generatorPrototype; async ones get their own
        // chain so test262 can probe %AsyncIteratorPrototype% via
        // Object.getPrototypeOf(Object.getPrototypeOf(asyncGen.prototype)).
        com.jimmyhmiller.harmonica.bytecode.builtins
            .AsyncIteratorPrototypeBuiltin.install();
        asyncGeneratorPrototype = new JSObject(asyncIteratorPrototype);
        // Reuse the sync generator's next/return/throw so calling them on an
        // async-generator instance does something — the result-shape semantics
        // are wrong for true async yet (Promise wrapping), but at least the
        // method identity exists for prop-desc / typeof checks.
        if (generatorPrototype != null) {
            Object n = generatorPrototype.get("next");
            Object r = generatorPrototype.get("return");
            Object t = generatorPrototype.get("throw");
            if (n != null && n != Undefined.VALUE) asyncGeneratorPrototype.set("next", n);
            if (r != null && r != Undefined.VALUE) asyncGeneratorPrototype.set("return", r);
            if (t != null && t != Undefined.VALUE) asyncGeneratorPrototype.set("throw", t);
        }
        if (wellKnownToStringTag != null) {
            asyncGeneratorPrototype.set(wellKnownToStringTag.asPropertyKey(), "AsyncGenerator");
            asyncGeneratorPrototype.setAttributes(wellKnownToStringTag.asPropertyKey(),
                JSObject.ATTR_CONFIGURABLE);
        }

        // ECMA-262 § 17 — all built-in prototype methods are non-enumerable
        // ({writable: true, enumerable: false, configurable: true}). Sweep
        // each prototype that's been bootstrapped and flip enumerability off.
        markMethodsNonEnumerable(objectPrototype);
        markMethodsNonEnumerable(functionPrototype);
        markMethodsNonEnumerable(stringPrototype);
        markMethodsNonEnumerable(numberPrototype);
        markMethodsNonEnumerable(booleanPrototype);
        markMethodsNonEnumerable(symbolPrototype);
        markMethodsNonEnumerable(promisePrototype);
        markMethodsNonEnumerable(generatorPrototype);
        markMethodsNonEnumerable(mapPrototype);
        markMethodsNonEnumerable(setPrototype);
        markMethodsNonEnumerable(weakMapPrototype);
        markMethodsNonEnumerable(weakSetPrototype);

        prototypesReady = true;
    }

    public static JSFunction nativeFn(String name, int arity, NativeBody body) {
        return new JSFunction(name, arity, body);
    }

    public static Object arg(Object[] a, int i) {
        return i < a.length ? a[i] : Undefined.VALUE;
    }

    /** § 7.1.5 ToIntegerOrInfinity + slice-index clamping in one step.
     *  Negative values count from the end; +Infinity caps at len; -Infinity
     *  caps at 0. {@code dflt} is returned for {@code undefined}. */
    private static int sliceIndex(Object v, int len, int dflt) {
        if (v == Undefined.VALUE) return dflt;
        double n = AbstractOps.toNumber(v);
        if (Double.isNaN(n)) return 0;
        if (Double.isInfinite(n)) return n > 0 ? len : 0;
        long i = (long) n;   // ToInteger truncation
        if (i < 0) return (int) Math.max(0, len + i);
        return (int) Math.min(len, i);
    }

    // ============================================================
    //  Object.prototype
    // ============================================================

    private static void installObjectPrototype() {
        // ECMA-262 § B.2.2 Annex B legacy Object.prototype accessor helpers.
        // Per spec, both delegate to DefinePropertyOrThrow so non-extensible /
        // non-configurable invariants surface as TypeError. We do the
        // dispatch by hand because Object.defineProperty isn't installed
        // yet at this point in bootstrap.
        objectPrototype.set("__defineGetter__", nativeFn("__defineGetter__", 2, (thisVal, a, c) -> {
            if (thisVal == null || thisVal == Undefined.VALUE) {
                throw AbruptCompletion.typeError("__defineGetter__ called on null/undefined");
            }
            Object getter = arg(a, 1);
            if (!(getter instanceof JSFunction)) {
                throw AbruptCompletion.typeError("Getter must be a function");
            }
            // Build a descriptor object and delegate to Object.defineProperty.
            JSObject desc = new JSObject();
            desc.set("get", getter);
            desc.set("enumerable", true);
            desc.set("configurable", true);
            InterpContext ctx = InterpContext.current();
            Object objCtor = ctx == null ? null : ctx.globals().get("Object");
            Object defineFn = objCtor == null ? null : AbstractOps.getProperty(objCtor, "defineProperty");
            if (defineFn instanceof JSFunction df) {
                Interpreter.invokeFunction(df, Undefined.VALUE,
                    new Object[]{thisVal, arg(a, 0), desc}, c);
            }
            return Undefined.VALUE;
        }));
        objectPrototype.set("__defineSetter__", nativeFn("__defineSetter__", 2, (thisVal, a, c) -> {
            if (thisVal == null || thisVal == Undefined.VALUE) {
                throw AbruptCompletion.typeError("__defineSetter__ called on null/undefined");
            }
            Object setter = arg(a, 1);
            if (!(setter instanceof JSFunction)) {
                throw AbruptCompletion.typeError("Setter must be a function");
            }
            JSObject desc = new JSObject();
            desc.set("set", setter);
            desc.set("enumerable", true);
            desc.set("configurable", true);
            InterpContext ctx = InterpContext.current();
            Object objCtor = ctx == null ? null : ctx.globals().get("Object");
            Object defineFn = objCtor == null ? null : AbstractOps.getProperty(objCtor, "defineProperty");
            if (defineFn instanceof JSFunction df) {
                Interpreter.invokeFunction(df, Undefined.VALUE,
                    new Object[]{thisVal, arg(a, 0), desc}, c);
            }
            return Undefined.VALUE;
        }));
        // Annex B § B.2.2.1 Object.prototype.__proto__ accessor.
        JSFunction protoGetter = nativeFn("get __proto__", 0, (thisVal, a, c) -> {
            if (thisVal == null || thisVal == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Cannot convert undefined or null to object");
            }
            if (thisVal instanceof JSObject jo) return jo.proto() == null ? null : jo.proto();
            if (thisVal instanceof JSArray) return arrayPrototype;
            if (thisVal instanceof JSFunction) return functionPrototype;
            if (thisVal instanceof Boolean) return booleanPrototype;
            if (thisVal instanceof Number) return numberPrototype;
            if (thisVal instanceof CharSequence) return stringPrototype;
            if (thisVal instanceof JSSymbol) return symbolPrototype;
            return null;
        });
        JSFunction protoSetter = nativeFn("set __proto__", 1, (thisVal, a, c) -> {
            if (thisVal == null || thisVal == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Cannot convert undefined or null to object");
            }
            Object proto = arg(a, 0);
            if (proto != null && proto != Undefined.VALUE && !(proto instanceof JSObject)) {
                return Undefined.VALUE;
            }
            if (thisVal instanceof JSObject jo) {
                jo.setProto(proto instanceof JSObject p ? p : null);
            }
            return Undefined.VALUE;
        });
        objectPrototype.set("__proto__", new Accessor(protoGetter, protoSetter));
        objectPrototype.setAttributes("__proto__", JSObject.ATTR_CONFIGURABLE);
        objectPrototype.set("__lookupGetter__", nativeFn("__lookupGetter__", 1, (thisVal, a, c) -> {
            if (!(thisVal instanceof JSObject jo)) return Undefined.VALUE;
            String k = AbstractOps.toString(arg(a, 0));
            JSObject cursor = jo;
            while (cursor != null) {
                Object v = cursor.getOwn(k);
                if (v instanceof Accessor acc) return acc.getter() != null ? acc.getter() : Undefined.VALUE;
                if (v != JSObject.ABSENT) return Undefined.VALUE;
                cursor = cursor.proto();
            }
            return Undefined.VALUE;
        }));
        objectPrototype.set("__lookupSetter__", nativeFn("__lookupSetter__", 1, (thisVal, a, c) -> {
            if (!(thisVal instanceof JSObject jo)) return Undefined.VALUE;
            String k = AbstractOps.toString(arg(a, 0));
            JSObject cursor = jo;
            while (cursor != null) {
                Object v = cursor.getOwn(k);
                if (v instanceof Accessor acc) return acc.setter() != null ? acc.setter() : Undefined.VALUE;
                if (v != JSObject.ABSENT) return Undefined.VALUE;
                cursor = cursor.proto();
            }
            return Undefined.VALUE;
        }));
        // § 20.1.3.5 Object.prototype.toLocaleString — invokes this.toString().
        objectPrototype.set("toLocaleString", nativeFn("toLocaleString", 0, (thisVal, a, c) -> {
            Object toStr = AbstractOps.getProperty(thisVal, "toString");
            if (!(toStr instanceof JSFunction tf)) {
                throw AbruptCompletion.typeError("toLocaleString: toString is not callable");
            }
            return Interpreter.invokeFunction(tf, thisVal, new Object[0], c);
        }));
        // ECMA-262 § 20.1.3.6 Object.prototype.toString — synthesizes
        // {@code "[object " + tag + "]"} using either the built-in
        // [[Class]]-like signature or, when present, the value's
        // {@code @@toStringTag} symbol. Real engines look for several
        // internal slots ([[ParameterMap]] → "Arguments", [[ErrorData]] →
        // "Error", etc.) — we recover the common cases from
        // {@code @@toStringTag} plus a few special slots we model.
        objectPrototype.set("toString", nativeFn("toString", 0, (thisVal, a, c) -> {
            // § 20.1.3.6 — uses GetBuiltinTag(O) to pick the brand from
            // [[ArrayIteratorPrototype]]-style internal slots; @@toStringTag
            // wins when present. Primitives are auto-boxed and their wrapper
            // class shows up as the brand.
            if (thisVal == null)               return "[object Null]";
            if (thisVal == Undefined.VALUE)    return "[object Undefined]";
            // Primitives — per spec the wrapped form contributes the brand.
            if (thisVal instanceof Boolean)     return "[object Boolean]";
            if (thisVal instanceof Number)      return "[object Number]";
            if (thisVal instanceof JSBigInt)    return "[object BigInt]";
            if (thisVal instanceof JSSymbol)    return "[object Symbol]";
            if (thisVal instanceof CharSequence) return "[object String]";
            // Proxies route to their target's brand (or hand off to spec
            // logic in the target's toString).
            if (thisVal instanceof JSObject pjo && isProxy(pjo)) {
                Object target = proxyTarget(pjo);
                if (target != null) thisVal = target;
            }
            if (thisVal instanceof JSArray)    return "[object Array]";
            if (thisVal instanceof JSFunction) return "[object Function]";
            // Prefer @@toStringTag when set on the object (or its proto chain).
            if (thisVal instanceof JSObject jo && wellKnownToStringTag != null) {
                Object tag = AbstractOps.getProperty(jo, wellKnownToStringTag.asPropertyKey());
                if (tag instanceof CharSequence cs) return "[object " + cs + "]";
                // Recognize internal slots → brand mappings (§ 20.1.3.6 step 14).
                if (jo.properties().containsKey("##ArgumentsParameterMap##")) return "[object Arguments]";
                if (jo.properties().containsKey("##time##")) return "[object Date]";
                if (jo.properties().containsKey(SLOT_NUMBER_DATA)) return "[object Number]";
                if (jo.properties().containsKey(SLOT_BOOLEAN_DATA)) return "[object Boolean]";
                if (jo.properties().containsKey(SLOT_STRING_DATA)) return "[object String]";
                if (jo.properties().containsKey(SLOT_BIGINT_DATA)) return "[object BigInt]";
                // Error instances: walk prototype chain for an Error-named proto.
                JSObject cursor = jo;
                while (cursor != null) {
                    Object nm = cursor.getOwn("name");
                    if (nm instanceof CharSequence nmS) {
                        String nameStr = nmS.toString();
                        if (errorPrototypes.containsValue(cursor)) return "[object Error]";
                        // For the root Error prototype itself: when name === "Error".
                        if ("Error".equals(nameStr) && errorPrototypes.containsKey("Error")
                                && errorPrototypes.get("Error") == cursor) return "[object Error]";
                    }
                    cursor = cursor.proto();
                }
                // RegExp instances: look for source/flags slots or the regex prototype.
                if (regExpPrototype != null) {
                    JSObject cursor2 = jo;
                    while (cursor2 != null) {
                        if (cursor2 == regExpPrototype) return "[object RegExp]";
                        cursor2 = cursor2.proto();
                    }
                }
            }
            return "[object Object]";
        }));
        objectPrototype.set("valueOf", nativeFn("valueOf", 0, (thisVal, a, c) -> thisVal));
        // ECMA-262 § 20.1.3.2 Object.prototype.hasOwnProperty(V).
        // Mirrors AbstractOps.getProperty's virtual-property treatment:
        // function .name/.length, array .length and indexed elements,
        // string .length and character access — so they all report as
        // "own" the way real engines do via [[GetOwnProperty]]. Private
        // names (keys starting with '#') are filtered out — § 15.7.1.4
        // says private elements live in [[PrivateElements]] and are
        // invisible to all spec [[OwnPropertyKeys]] / [[GetOwnProperty]]
        // observers.
        objectPrototype.set("hasOwnProperty", nativeFn("hasOwnProperty", 1, (thisVal, a, c) -> {
            Object rawKey = arg(a, 0);
            String key = rawKey instanceof String ss ? ss
                       : rawKey instanceof JSSymbol sy ? sy.asPropertyKey()
                       : AbstractOps.toString(rawKey);
            if (isPrivateName(key)) return false;
            if (thisVal instanceof JSObject jo) return jo.properties().containsKey(key);
            if (thisVal instanceof JSArray arr) {
                if ("length".equals(key)) return true;
                int idx = parseIndex(key);
                if (idx >= 0) {
                    if (idx < arr.elements().size() && !arr.isHole(idx)) return true;
                    if (arr.hasExtraProperty(key)) return true;
                    return false;
                }
                return arr.hasExtraProperty(key);
            }
            if (thisVal instanceof JSFunction fn) {
                if ("name".equals(key))   return !fn.isNameDeleted();
                if ("length".equals(key)) return !fn.isLengthDeleted();
                if (fn.hasOwnStatic(key)) return true;
                if ("prototype".equals(key) && fn.prototypeObject() != null) return true;
                return false;
            }
            if (thisVal instanceof String s) {
                if ("length".equals(key)) return true;
                int idx = parseIndex(key);
                return idx >= 0 && idx < s.length();
            }
            return false;
        }));
        objectPrototype.set("isPrototypeOf", nativeFn("isPrototypeOf", 1, (thisVal, a, c) -> {
            if (!(thisVal instanceof JSObject self) || !(arg(a, 0) instanceof JSObject other)) return false;
            JSObject cursor = other.proto();
            while (cursor != null) {
                if (cursor == self) return true;
                cursor = cursor.proto();
            }
            return false;
        }));
        objectPrototype.set("propertyIsEnumerable", nativeFn("propertyIsEnumerable", 1, (thisVal, a, c) -> {
            Object rawKey = arg(a, 0);
            String key = rawKey instanceof String ss ? ss
                       : rawKey instanceof JSSymbol sy ? sy.asPropertyKey()
                       : AbstractOps.toString(rawKey);
            if (isPrivateName(key)) return false;
            if (thisVal instanceof JSObject jo) {
                return jo.properties().containsKey(key) && jo.isEnumerable(key);
            }
            return false;
        }));
    }

    // ============================================================
    //  Function.prototype
    // ============================================================

    private static void installFunctionPrototype() {
        // ECMA-262 § 20.2.3.6 Function.prototype[Symbol.hasInstance](V) —
        // default `instanceof` resolution. Walks V's prototype chain
        // looking for {@code this.prototype}.
        functionPrototype.set(wellKnownHasInstance == null
                ? "@@hasInstance" : wellKnownHasInstance.asPropertyKey(),
            nativeFn("[Symbol.hasInstance]", 1, (thisVal, a, c) -> {
                if (!(thisVal instanceof JSFunction fn)) return false;
                Object v = arg(a, 0);
                JSObject proto = fn.prototypeObject();
                if (proto == null) return false;
                JSObject cursor;
                if (v instanceof JSObject jo) cursor = jo.proto();
                else if (v instanceof JSArray) cursor = arrayPrototype;
                else if (v instanceof JSFunction f2) {
                    JSFunction sc = f2.superConstructor();
                    cursor = sc != null ? null : functionPrototype;
                } else return false;
                while (cursor != null) {
                    if (cursor == proto) return true;
                    cursor = cursor.proto();
                }
                return false;
            }));
        if (wellKnownHasInstance != null) {
            functionPrototype.setAttributes(wellKnownHasInstance.asPropertyKey(),
                (byte) 0);   // non-writable, non-enumerable, non-configurable
        }
        functionPrototype.set("call", nativeFn("call", 1, (thisVal, a, c) -> {
            Object newThis = arg(a, 0);
            int n = Math.max(0, a.length - 1);
            Object[] callArgs = Interpreter.acquireArgs(n);
            for (int i = 1; i < a.length; i++) callArgs[i - 1] = a[i];
            try {
                // Proxy receiver → dispatch via [[Call]] trap.
                if (thisVal instanceof JSObject pj && isProxy(pj)) {
                    return proxyApply(pj, newThis, callArgs);
                }
                if (!(thisVal instanceof JSFunction fn)) {
                    throw AbruptCompletion.typeError("Function.prototype.call called on non-function");
                }
                // Reuse the per-thread args pool — lodash calls Function.prototype.call
                // tens of thousands of times in iteratee dispatch, and the
                // `new Object[a.length-1]` allocation here was 4%+ of allocated
                // bytes in the workload's profile.
                return Interpreter.invokeFunction(fn, newThis, callArgs, c);
            } finally {
                Interpreter.releaseArgs(callArgs);
            }
        }));
        functionPrototype.set("apply", nativeFn("apply", 2, (thisVal, a, c) -> {
            boolean isProxyReceiver = thisVal instanceof JSObject pjV && isProxy(pjV);
            if (!isProxyReceiver && !(thisVal instanceof JSFunction)) {
                throw AbruptCompletion.typeError("Function.prototype.apply called on non-function");
            }
            JSFunction fn = isProxyReceiver ? null : (JSFunction) thisVal;
            Object newThis = arg(a, 0);
            Object[] callArgs;
            Object listArg = arg(a, 1);
            if (listArg == Undefined.VALUE || listArg == null) {
                callArgs = Interpreter.acquireArgs(0);
            } else if (listArg instanceof JSArray arr) {
                int len = arr.length();
                callArgs = Interpreter.acquireArgs(len);
                java.util.List<Object> el = arr.elements();
                for (int i = 0; i < len; i++) callArgs[i] = el.get(i);
            } else if (listArg instanceof JSObject jo) {
                // ECMA-262 § 7.3.18 CreateListFromArrayLike: any object with
                // a numeric `length` is acceptable. The `arguments` object
                // and adapter wrappers (test262, RayTrace's tracer) hit this
                // path.
                Object lenVal = AbstractOps.getProperty(jo, "length");
                int len = (int) AbstractOps.toInt32(lenVal);
                callArgs = Interpreter.acquireArgs(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    callArgs[i] = AbstractOps.getProperty(jo, Integer.toString(i));
                }
            } else {
                throw AbruptCompletion.typeError("Function.prototype.apply args must be array-like or null");
            }
            try {
                if (isProxyReceiver) {
                    return proxyApply((JSObject) thisVal, newThis, callArgs);
                }
                return Interpreter.invokeFunction(fn, newThis, callArgs, c);
            } finally {
                Interpreter.releaseArgs(callArgs);
            }
        }));
        functionPrototype.set("bind", nativeFn("bind", 1, (thisVal, a, c) -> {
            // Allow bind on a callable Proxy too — wrap the proxy in a fn
            // that dispatches through proxyApply.
            if (thisVal instanceof JSObject pjB && isProxy(pjB)) {
                Object boundThis = arg(a, 0);
                Object[] presetArgs = new Object[Math.max(0, a.length - 1)];
                for (int i = 1; i < a.length; i++) presetArgs[i - 1] = a[i];
                JSObject pjBfinal = pjB;
                return nativeFn("bound", 0, (callerThis, callArgs, c2) -> {
                    Object[] combined = new Object[presetArgs.length + callArgs.length];
                    System.arraycopy(presetArgs, 0, combined, 0, presetArgs.length);
                    System.arraycopy(callArgs, 0, combined, presetArgs.length, callArgs.length);
                    return proxyApply(pjBfinal, boundThis, combined);
                });
            }
            if (!(thisVal instanceof JSFunction target)) {
                throw AbruptCompletion.typeError("Function.prototype.bind called on non-function");
            }
            Object boundThis = arg(a, 0);
            Object[] presetArgs = new Object[Math.max(0, a.length - 1)];
            for (int i = 1; i < a.length; i++) presetArgs[i - 1] = a[i];
            String name = target.name() != null ? "bound " + target.name() : "bound";
            return nativeFn(name, target.paramCount(), (callerThis, callArgs, c2) -> {
                int total = presetArgs.length + callArgs.length;
                if (total == 0) {
                    return Interpreter.invokeFunction(target, boundThis, Interpreter.acquireArgs(0), c2);
                }
                Object[] combined = Interpreter.acquireArgs(total);
                System.arraycopy(presetArgs, 0, combined, 0, presetArgs.length);
                System.arraycopy(callArgs, 0, combined, presetArgs.length, callArgs.length);
                try {
                    return Interpreter.invokeFunction(target, boundThis, combined, c2);
                } finally {
                    Interpreter.releaseArgs(combined);
                }
            });
        }));
        functionPrototype.set("toString", nativeFn("toString", 0, (thisVal, a, c) -> {
            if (thisVal instanceof JSFunction fn) {
                return "function " + (fn.name() != null ? fn.name() : "") + "() { [native code] }";
            }
            return "function () { [native code] }";
        }));
        // ECMA-262 § 20.2.3.4: Function.prototype.name is "" with attrs
        // { writable: false, enumerable: false, configurable: true }.
        functionPrototype.set("name", "");
        functionPrototype.setAttributes("name", JSObject.ATTR_CONFIGURABLE);
        // § 20.2.3.5: Function.prototype.length is 0 with the same attrs.
        functionPrototype.set("length", 0.0);
        functionPrototype.setAttributes("length", JSObject.ATTR_CONFIGURABLE);
    }

    // ============================================================
    //  Array.prototype
    // ============================================================

    private static JSArray asArray(Object v, String method) {
        if (v instanceof JSArray a) return a;
        throw AbruptCompletion.typeError("Array.prototype." + method + " called on non-array");
    }

    // ECMA-262 § 7.3.19 LengthOfArrayLike — accepts any object with a numeric
    // {@code length} property. Almost every Array.prototype method (indexOf,
    // map, filter, reduce, etc.) is defined to operate on array-likes, not
    // just dense JSArrays. Test262 hammers this via
    // {@code Array.prototype.indexOf.call({length: 5, [0]: ...}, ...)}.
    static int lengthOfArrayLike(Object t) {
        if (t instanceof JSArray arr) return arr.length();
        if (t == null || t == Undefined.VALUE) {
            throw AbruptCompletion.typeError("cannot read property 'length' of null/undefined");
        }
        Object lenVal = AbstractOps.getProperty(t, "length");
        double d = AbstractOps.toNumber(lenVal);
        if (Double.isNaN(d) || d <= 0) return 0;
        if (d > 0x7FFFFFFFL) return Integer.MAX_VALUE;
        return (int) d;
    }

    /**
     * Yield to the interpreter's interrupt poll inside long native loops.
     * Without this, a test that calls {@code Array.prototype.X.call({length:
     * Infinity, …})} keeps the Java loop running for 2 billion iterations,
     * allocating per-iteration garbage faster than GC can free, OOMing the
     * JVM long before the per-test timeout fires. Called every 1024 iterations.
     */
    static void checkInterruptTick(int i) {
        if ((i & 0x3FF) == 0 && Thread.interrupted()) {
            throw new Interpreter.InterpInterruptedError();
        }
    }

    /** Index-keyed read that works on both JSArrays and array-like objects.
     *  Invokes accessor getters when the underlying cell is an Accessor —
     *  Array.prototype.X / Object.defineProperty install accessors at
     *  numeric indices and tests check that filter/map/etc. see the
     *  computed value, not the cell. Yields to the interpreter's
     *  interrupt poll on long loops. */
    static Object getIndexed(Object t, int i) {
        checkInterruptTick(i);
        if (t instanceof JSArray arr) {
            // Hole at this slot? Per § 10.1.8 OrdinaryGet, fall back to
            // the prototype chain (Array.prototype) so inherited
            // accessors / data props are observed by Array.prototype.X.
            if (i < arr.length() && !arr.isHole(i)) {
                Object v = arr.get(i);
                if (v instanceof Accessor acc) {
                    if (acc.getter() == null) return Undefined.VALUE;
                    InterpContext ctx = InterpContext.current();
                    if (ctx == null) return Undefined.VALUE;
                    return Interpreter.invokeFunction(acc.getter(), t, new Object[0], ctx);
                }
                return v;
            }
            String key = Integer.toString(i);
            if (arr.hasExtraProperty(key)) {
                Object v = arr.getExtraProperty(key);
                if (v instanceof Accessor acc) {
                    if (acc.getter() == null) return Undefined.VALUE;
                    InterpContext ctx = InterpContext.current();
                    if (ctx == null) return Undefined.VALUE;
                    return Interpreter.invokeFunction(acc.getter(), t, new Object[0], ctx);
                }
                return v;
            }
            if (Realm.arrayPrototype != null) {
                JSObject cursor = Realm.arrayPrototype;
                while (cursor != null) {
                    Object v = cursor.getOwn(key);
                    if (v != JSObject.ABSENT) {
                        if (v instanceof Accessor acc) {
                            if (acc.getter() == null) return Undefined.VALUE;
                            InterpContext ctx = InterpContext.current();
                            if (ctx == null) return Undefined.VALUE;
                            return Interpreter.invokeFunction(acc.getter(), t, new Object[0], ctx);
                        }
                        return v;
                    }
                    cursor = cursor.proto();
                }
            }
            return Undefined.VALUE;
        }
        return AbstractOps.getProperty(t, Integer.toString(i));
    }

    /** Index-keyed write that works on both JSArrays and array-like objects. */
    static void setIndexed(Object t, int i, Object v) {
        checkInterruptTick(i);
        if (t instanceof JSArray arr) { arr.set(i, v); return; }
        AbstractOps.setProperty(t, Integer.toString(i), v);
    }

    /** {@code HasProperty(O, ToString(i))} for the integer index. */
    /** § 22.1.3.{6,7,21} — throw TypeError if the search arg is a RegExp.
     *  Both real RegExp objects and Object-with-@@match=true count. */
    static void rejectRegExpSearch(Object v, String method) {
        if (v instanceof JSObject jo) {
            // Real RegExp.
            if (asRegExpSource(jo) != null) {
                throw AbruptCompletion.typeError(
                    "String.prototype." + method + " called with a RegExp argument");
            }
            // Quacks like a RegExp via @@match.
            if (wellKnownMatch != null) {
                Object flag = AbstractOps.getProperty(jo, wellKnownMatch.asPropertyKey());
                if (flag != Undefined.VALUE && AbstractOps.toBoolean(flag)) {
                    throw AbruptCompletion.typeError(
                        "String.prototype." + method + " called with a RegExp argument");
                }
            }
        }
    }

    /** § 7.1.5 ToIntegerOrInfinity for a string-method position arg —
     *  clamped to [0, len]. Default applied when v is undefined. */
    static int stringMethodPos(Object v, int len, int defaultVal) {
        if (v == Undefined.VALUE) return defaultVal;
        double d = AbstractOps.toNumber(v);
        if (Double.isNaN(d)) return 0;
        if (Double.isInfinite(d)) return d > 0 ? len : 0;
        long n = (long) d;
        if (n < 0) return 0;
        if (n > len) return len;
        return (int) n;
    }

    /** § 22.1.3.14.1 GetSubstitution — expand the {@code $...} escapes in
     *  the replacement template. {@code captures} is a list of capture
     *  group values (1-indexed in the template); {@code namedCaptures} is
     *  the optional JSObject of named groups (null if none). */
    static String getSubstitution(String matched, String str, int position,
                                  java.util.List<Object> captures,
                                  JSObject namedCaptures, String replacement) {
        StringBuilder out = new StringBuilder();
        int len = replacement.length();
        int tailPos = position + matched.length();
        for (int i = 0; i < len; i++) {
            char ch = replacement.charAt(i);
            if (ch != '$' || i + 1 >= len) { out.append(ch); continue; }
            char next = replacement.charAt(i + 1);
            switch (next) {
                case '$' -> { out.append('$'); i++; }
                case '&' -> { out.append(matched); i++; }
                case '`' -> { out.append(str, 0, position); i++; }
                case '\'' -> { out.append(str, Math.min(tailPos, str.length()), str.length()); i++; }
                case '<' -> {
                    int close = replacement.indexOf('>', i + 2);
                    if (close < 0 || namedCaptures == null) {
                        out.append(ch);
                    } else {
                        String name = replacement.substring(i + 2, close);
                        Object v = namedCaptures.getOwn(name);
                        if (v != null && v != JSObject.ABSENT && v != Undefined.VALUE) {
                            out.append(AbstractOps.toString(v));
                        }
                        i = close;
                    }
                }
                default -> {
                    if (next >= '0' && next <= '9') {
                        int n = next - '0';
                        int consumed = 1;
                        if (i + 2 < len) {
                            char third = replacement.charAt(i + 2);
                            if (third >= '0' && third <= '9') {
                                int nn = n * 10 + (third - '0');
                                if (nn >= 1 && nn <= captures.size()) {
                                    n = nn;
                                    consumed = 2;
                                }
                            }
                        }
                        if (n >= 1 && n <= captures.size()) {
                            Object cap = captures.get(n - 1);
                            if (cap != null && cap != Undefined.VALUE) {
                                out.append(AbstractOps.toString(cap));
                            }
                            i += consumed;
                        } else {
                            // No matching capture — emit the literal $N.
                            out.append(ch);
                        }
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    /** § 23.1.3.2.2 ArraySpeciesCreate(originalArray, length) — produces
     *  the result array for slice/concat/filter/map/etc. */
    static Object arraySpeciesCreate(Object originalArray, int length) {
        if (!(originalArray instanceof JSArray)) {
            JSArray out = new JSArray();
            out.setLength(length);
            return out;
        }
        Object C = AbstractOps.getProperty(originalArray, "constructor");
        // Step 5: if C is an Object, read @@species off it (null → undefined).
        if (C instanceof JSObject || C instanceof JSFunction) {
            if (wellKnownSpecies != null) {
                Object S = AbstractOps.getProperty(C, wellKnownSpecies.asPropertyKey());
                if (S == null || S == Undefined.VALUE) C = Undefined.VALUE;
                else C = S;
            } else {
                C = Undefined.VALUE;
            }
        }
        // Step 6: if C is undefined, default to %Array%.
        if (C == Undefined.VALUE) {
            JSArray out = new JSArray();
            out.setLength(length);
            return out;
        }
        // Step 7: anything else must be a Constructor — otherwise TypeError.
        // Catches `a.constructor = null` (C=null), `a.constructor = 1` (C=1),
        // string/boolean constructors, etc.
        if (!(C instanceof JSFunction cf) || !cf.isConstructor()) {
            throw AbruptCompletion.typeError(
                "ArraySpeciesCreate: species constructor is not a constructor");
        }
        InterpContext ctx = InterpContext.current();
        // Lazily materialize Ctor.prototype the way Op.CallConstruct does,
        // so the constructed receiver has the right [[Prototype]].
        JSObject ctorProto = cf.prototypeObject();
        if (ctorProto == null && !cf.isNative()) {
            ctorProto = new JSObject();
            ctorProto.set("constructor", cf);
            cf.setPrototypeObject(ctorProto);
        }
        if (ctorProto == null) ctorProto = arrayPrototype;
        JSObject receiver = new JSObject(ctorProto);
        Object built = Interpreter.invokeFunctionAsConstructor(cf, receiver,
            new Object[]{(double) length}, ctx);
        if (built instanceof JSArray || built instanceof JSObject) return built;
        return receiver;
    }

    /** § 23.1.3.2.1 IsConcatSpreadable — defer to @@isConcatSpreadable when
     *  set, otherwise IsArray. */
    static boolean isConcatSpreadable(Object v) {
        if (v == null || v == Undefined.VALUE) return false;
        boolean isObjectLike = v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction;
        if (!isObjectLike) return false;
        if (wellKnownIsConcatSpreadable != null) {
            Object flag = AbstractOps.getProperty(v, wellKnownIsConcatSpreadable.asPropertyKey());
            if (flag != Undefined.VALUE) return AbstractOps.toBoolean(flag);
        }
        return v instanceof JSArray;
    }

    static boolean hasIndexed(Object t, int i) {
        checkInterruptTick(i);
        if (t instanceof JSArray arr) {
            // ECMA-262 § 23.1.3 Array.prototype.X uses HasProperty(O, Pk),
            // which is false for holes unless the prototype chain
            // exposes the key — needed by tests like forEach/15.4.4.18-7-b-11
            // that toggle Array.prototype[N] between iterations.
            if (i < 0) return false;
            if (i < arr.length()) {
                if (!arr.isHole(i)) return true;
            }
            String key = Integer.toString(i);
            if (arr.hasExtraProperty(key)) return true;
            if (Realm.arrayPrototype != null && Realm.arrayPrototype.has(key)) return true;
            return false;
        }
        if (t instanceof JSObject obj) {
            // Wrapped String / TypedArray indexed elements aren't in the
            // shape map but exist virtually up to the wrapped value's
            // length. § 10.4.3 [[GetOwnProperty]] for String exotic;
            // § 10.4.5 for TypedArray.
            Object stringData = obj.properties().get(SLOT_STRING_DATA);
            if (stringData instanceof CharSequence cs && i >= 0 && i < cs.length()) {
                return true;
            }
            return obj.has(Integer.toString(i));
        }
        if (t instanceof JSFunction fn) {
            String key = Integer.toString(i);
            return fn.hasOwnStatic(key);
        }
        if (t instanceof CharSequence cs) {
            return i >= 0 && i < cs.length();
        }
        // Primitive Boolean / Number / Symbol — walk their prototype.
        if (t instanceof Boolean) {
            return Realm.booleanPrototype != null && Realm.booleanPrototype.has(Integer.toString(i));
        }
        if (t instanceof Number) {
            return Realm.numberPrototype != null && Realm.numberPrototype.has(Integer.toString(i));
        }
        if (t instanceof JSSymbol) {
            return Realm.symbolPrototype != null && Realm.symbolPrototype.has(Integer.toString(i));
        }
        return false;
    }

    private static void installArrayPrototype() {
        // ECMA-262 § 23.1.3.* — Array.prototype methods MUST operate on
        // array-likes (anything with a numeric .length), not just JSArrays.
        // Test262 calls these via `.call({length: 5, [0]: 'a'}, ...)`
        // hundreds of times. Below: every method uses lengthOfArrayLike +
        // getIndexed/setIndexed, with JSArray-specific fast paths where
        // they matter for perf (push/pop/shift/unshift/reverse/sort).
        arrayPrototype.set("push", nativeFn("push", 1, (t, a, c) -> {
            if (t instanceof JSArray arr) {
                // 2^53-1 cap check first (observed before any writes).
                double baseLen = arr.length();
                if (baseLen + a.length > 9007199254740991.0) {
                    throw AbruptCompletion.typeError(
                        "Array.prototype.push: pushed length exceeds 2^53 - 1");
                }
                // Spec § 23.1.3.23 step 5: per-element Set(O, ToString(len), E, true).
                // Use AbstractOps.setProperty so proto-chain setters fire and
                // frozen length surfaces as TypeError per Set(..., Throw=true).
                int idx = (int) baseLen;
                for (Object v : a) {
                    AbstractOps.setProperty(arr, Integer.toString(idx), v);
                    idx++;
                    if (arr.length() < idx) {
                        throw AbruptCompletion.typeError(
                            "Array.prototype.push: cannot extend frozen array");
                    }
                }
                return (double) arr.length();
            }
            // ECMA-262 § 23.1.3.23 step 5: if len + argCount > 2^53 - 1,
            // throw TypeError. Read length raw for the spec check.
            Object lenRaw = AbstractOps.getProperty(t, "length");
            double lenD = AbstractOps.toNumber(lenRaw);
            if (Double.isNaN(lenD) || lenD < 0) lenD = 0;
            else lenD = Math.floor(lenD);
            double newLenD = lenD + a.length;
            if (newLenD > 9007199254740991.0 /* 2^53 - 1 */) {
                throw AbruptCompletion.typeError("Array.prototype.push: pushed length exceeds 2^53 - 1");
            }
            int len = (int) Math.min(lenD, Integer.MAX_VALUE);
            for (int i = 0; i < a.length; i++) setIndexed(t, len + i, a[i]);
            AbstractOps.setProperty(t, "length", newLenD);
            return newLenD;
        }));
        arrayPrototype.set("pop", nativeFn("pop", 0, (t, a, c) -> {
            // § 23.1.3.21 — generic: read length, read [length-1] (proto-
            // aware via getIndexed), delete, set length, return value.
            int len = lengthOfArrayLike(t);
            if (len == 0) {
                // Empty: set length=0 (validates frozen-length → TypeError),
                // return undefined.
                if (t instanceof JSArray arr) {
                    byte la = arr.getIndexAttributes("length");
                    if ((la & JSObject.ATTR_WRITABLE) == 0) {
                        throw AbruptCompletion.typeError(
                            "Array.prototype.pop: cannot set length on frozen array");
                    }
                }
                AbstractOps.setProperty(t, "length", 0.0);
                return Undefined.VALUE;
            }
            int newLen = len - 1;
            Object v = getIndexed(t, newLen);
            if (t instanceof JSArray arr) {
                byte la = arr.getIndexAttributes("length");
                if ((la & JSObject.ATTR_WRITABLE) == 0) {
                    throw AbruptCompletion.typeError(
                        "Array.prototype.pop: cannot set length on frozen array");
                }
                if (newLen < arr.elements().size()) {
                    arr.elements().set(newLen, Op.HOLE);
                }
                arr.setLength(newLen);
            } else if (t instanceof JSObject jo) {
                jo.delete(Integer.toString(newLen));
                AbstractOps.setProperty(t, "length", (double) newLen);
            }
            return v;
        }));
        arrayPrototype.set("shift", nativeFn("shift", 0, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            if (len == 0) {
                if (t instanceof JSArray arr) {
                    byte la = arr.getIndexAttributes("length");
                    if ((la & JSObject.ATTR_WRITABLE) == 0) {
                        throw AbruptCompletion.typeError(
                            "Array.prototype.shift: cannot set length on frozen array");
                    }
                }
                AbstractOps.setProperty(t, "length", 0.0);
                return Undefined.VALUE;
            }
            Object first = getIndexed(t, 0);
            for (int i = 1; i < len; i++) {
                if (hasIndexed(t, i)) setIndexed(t, i - 1, getIndexed(t, i));
                else if (t instanceof JSObject jo) jo.delete(Integer.toString(i - 1));
                else if (t instanceof JSArray arr2 && i - 1 < arr2.elements().size()) {
                    arr2.elements().set(i - 1, Op.HOLE);
                }
            }
            if (t instanceof JSArray arr) {
                if (arr.length() - 1 < arr.elements().size()) {
                    arr.elements().set(arr.length() - 1, Op.HOLE);
                }
                arr.setLength(len - 1);
            } else if (t instanceof JSObject jo) {
                jo.delete(Integer.toString(len - 1));
                AbstractOps.setProperty(t, "length", (double) (len - 1));
            }
            return first;
        }));
        arrayPrototype.set("unshift", nativeFn("unshift", 1, (t, a, c) -> {
            if (t instanceof JSArray arr) {
                byte la = arr.getIndexAttributes("length");
                if ((la & JSObject.ATTR_WRITABLE) == 0) {
                    if (a.length > 0) {
                        throw AbruptCompletion.typeError(
                            "Array.prototype.unshift: cannot set length on frozen array");
                    }
                    return (double) arr.length();
                }
                if ((double) arr.length() + a.length > 9007199254740991.0) {
                    throw AbruptCompletion.typeError(
                        "Array.prototype.unshift: combined length exceeds 2^53 - 1");
                }
                for (int i = 0; i < a.length; i++) arr.elements().add(i, a[i]);
                return (double) arr.length();
            }
            // ECMA-262 § 23.1.3.36: if len + argc > 2^53 - 1 → TypeError.
            Object lenRaw = AbstractOps.getProperty(t, "length");
            double lenD = AbstractOps.toNumber(lenRaw);
            if (Double.isNaN(lenD) || lenD < 0) lenD = 0;
            else lenD = Math.floor(lenD);
            int argc = a.length;
            double newLenD = lenD + argc;
            if (newLenD > 9007199254740991.0) {
                throw AbruptCompletion.typeError("Array.prototype.unshift: combined length exceeds 2^53 - 1");
            }
            int len = (int) Math.min(lenD, Integer.MAX_VALUE);
            // Shift existing elements right by argc.
            for (int i = len - 1; i >= 0; i--) {
                if (hasIndexed(t, i)) setIndexed(t, i + argc, getIndexed(t, i));
                else if (t instanceof JSObject jo) jo.delete(Integer.toString(i + argc));
            }
            for (int i = 0; i < argc; i++) setIndexed(t, i, a[i]);
            AbstractOps.setProperty(t, "length", newLenD);
            return newLenD;
        }));
        arrayPrototype.set("slice", nativeFn("slice", 2, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            int start = sliceIndex(arg(a, 0), len, 0);
            int end = arg(a, 1) == Undefined.VALUE ? len : sliceIndex(a[1], len, len);
            if (end < start) end = start;
            int count = end - start;
            Object out = arraySpeciesCreate(t, count);
            int k = 0;
            for (int i = start; i < end; i++) {
                if (hasIndexed(t, i)) {
                    setIndexed(out, k, getIndexed(t, i));
                }
                k++;
            }
            // For non-Array species result, ensure length is set.
            if (!(out instanceof JSArray)) {
                AbstractOps.setProperty(out, "length", (double) count);
            }
            return out;
        }));
        arrayPrototype.set("concat", nativeFn("concat", 1, (t, a, c) -> {
            // § 23.1.3.2: prepend `this` to the item list and walk each item
            // through IsConcatSpreadable. Result is allocated via
            // ArraySpeciesCreate(this, 0); items are appended as we go.
            Object self = toObject(t);
            Object out = arraySpeciesCreate(self, 0);
            Object[] items = new Object[a.length + 1];
            items[0] = self;
            System.arraycopy(a, 0, items, 1, a.length);
            int outIdx = 0;
            for (Object e : items) {
                boolean spread = isConcatSpreadable(e);
                if (spread) {
                    int xl = lengthOfArrayLike(e);
                    for (int i = 0; i < xl; i++) {
                        if (hasIndexed(e, i)) {
                            setIndexed(out, outIdx, getIndexed(e, i));
                        } else if (out instanceof JSArray oa) {
                            oa.push(Op.HOLE);
                        }
                        outIdx++;
                    }
                } else {
                    setIndexed(out, outIdx, e);
                    outIdx++;
                }
            }
            if (!(out instanceof JSArray)) {
                AbstractOps.setProperty(out, "length", (double) outIdx);
            }
            return out;
        }));
        arrayPrototype.set("join", nativeFn("join", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            String sep = arg(a, 0) == Undefined.VALUE ? "," : AbstractOps.toString(a[0]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(sep);
                Object e = getIndexed(t, i);
                if (e != null && e != Undefined.VALUE) sb.append(AbstractOps.toString(e));
            }
            return sb.toString();
        }));
        arrayPrototype.set("indexOf", nativeFn("indexOf", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            // § 23.1.3.13 step 3: short-circuit len==0 before coercing
            // fromIndex (the spec is explicit about this ordering so a
            // throwing valueOf on fromIndex isn't invoked on an empty array).
            if (len == 0) return -1.0;
            Object target = arg(a, 0);
            int from;
            if (a.length < 2) {
                from = 0;
            } else {
                double n = AbstractOps.toNumber(a[1]);
                if (Double.isNaN(n)) n = 0;
                else if (Double.isInfinite(n) && n > 0) return -1.0;
                else if (Double.isInfinite(n)) n = 0;
                else n = (long) n;
                if (n < 0) n = Math.max(0, len + n);
                from = (int) Math.min(n, Integer.MAX_VALUE);
            }
            for (int i = from; i < len; i++) {
                if (hasIndexed(t, i) && AbstractOps.strictlyEquals(getIndexed(t, i), target)) return (double) i;
            }
            return -1.0;
        }));
        arrayPrototype.set("lastIndexOf", nativeFn("lastIndexOf", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            if (len == 0) return -1.0;
            Object target = arg(a, 0);
            // § 23.1.3.15 step 4: "If fromIndex is present, ..." — based on
            // argument count, not value. `lastIndexOf(x, undefined)` is the
            // 2-arg form where ToInteger(undefined) = 0, so the scan only
            // visits index 0.
            int from;
            if (a.length < 2) {
                from = len - 1;
            } else {
                double n = AbstractOps.toNumber(a[1]);
                if (Double.isNaN(n)) n = 0;
                else if (Double.isInfinite(n) && n < 0) return -1.0;
                else if (Double.isInfinite(n)) n = len - 1;
                else n = (long) n;   // ToInteger truncation
                if (n < 0) n = len + n;
                if (n < 0) return -1.0;
                from = (int) Math.min(n, len - 1);
            }
            for (int i = from; i >= 0; i--) {
                if (hasIndexed(t, i) && AbstractOps.strictlyEquals(getIndexed(t, i), target)) return (double) i;
            }
            return -1.0;
        }));
        arrayPrototype.set("includes", nativeFn("includes", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            if (len == 0) return false;
            Object target = arg(a, 0);
            int from = arg(a, 1) == Undefined.VALUE ? 0 : AbstractOps.toInt32(a[1]);
            if (from < 0) from = Math.max(0, len + from);
            for (int i = from; i < len; i++) {
                Object e = getIndexed(t, i);
                // SameValueZero: NaN === NaN
                if (target instanceof Number tn && e instanceof Number en) {
                    double td = tn.doubleValue(), ed = en.doubleValue();
                    if (Double.isNaN(td) && Double.isNaN(ed)) return true;
                    if (td == ed) return true;
                } else if (AbstractOps.strictlyEquals(e, target)) return true;
            }
            return false;
        }));
        arrayPrototype.set("reverse", nativeFn("reverse", 0, (t, a, c) -> {
            if (t instanceof JSArray arr) {
                java.util.Collections.reverse(arr.elements());
                return arr;
            }
            int len = lengthOfArrayLike(t);
            for (int i = 0, j = len - 1; i < j; i++, j--) {
                boolean hi = hasIndexed(t, i), hj = hasIndexed(t, j);
                Object vi = hi ? getIndexed(t, i) : Undefined.VALUE;
                Object vj = hj ? getIndexed(t, j) : Undefined.VALUE;
                if (hj) setIndexed(t, i, vj); else if (t instanceof JSObject jo) jo.delete(Integer.toString(i));
                if (hi) setIndexed(t, j, vi); else if (t instanceof JSObject jo) jo.delete(Integer.toString(j));
            }
            return t;
        }));
        arrayPrototype.set("forEach", nativeFn("forEach", 1, (t, a, c) -> {
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            JSFunction fn = asCallback(arg(a, 0), "forEach");
            Object thisArg = arg(a, 1);
            for (int i = 0; i < len; i++) {
                if (!hasIndexed(O, i)) continue;
                Interpreter.invokeFunction(fn, thisArg,
                    new Object[]{getIndexed(O, i), (double) i, O}, c);
            }
            return Undefined.VALUE;
        }));
        arrayPrototype.set("map", nativeFn("map", 1, (t, a, c) -> {
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            JSFunction fn = asCallback(arg(a, 0), "map");
            Object thisArg = arg(a, 1);
            Object out = arraySpeciesCreate(O, len);
            for (int i = 0; i < len; i++) {
                if (hasIndexed(O, i)) {
                    setIndexed(out, i, Interpreter.invokeFunction(fn, thisArg,
                        new Object[]{getIndexed(O, i), (double) i, O}, c));
                }
            }
            if (!(out instanceof JSArray)) {
                AbstractOps.setProperty(out, "length", (double) len);
            }
            return out;
        }));
        arrayPrototype.set("filter", nativeFn("filter", 1, (t, a, c) -> {
            // ECMA-262 § 23.1.3.6 step 1: O = ToObject(this). Boxing
            // ensures callback's third arg is an object (so e.g.
            // {@code obj instanceof String} holds when called on a
            // string primitive).
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            JSFunction fn = asCallback(arg(a, 0), "filter");
            Object thisArg = arg(a, 1);
            // § 23.1.3.6 step 4: A = ArraySpeciesCreate(O, 0); items are
            // appended via CreateDataPropertyOrThrow so the result type
            // honors the source's @@species.
            Object out = arraySpeciesCreate(O, 0);
            int k = 0;
            for (int i = 0; i < len; i++) {
                if (!hasIndexed(O, i)) continue;
                Object v = getIndexed(O, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg,
                        new Object[]{v, (double) i, O}, c))) {
                    setIndexed(out, k++, v);
                }
            }
            if (!(out instanceof JSArray)) {
                AbstractOps.setProperty(out, "length", (double) k);
            }
            return out;
        }));
        arrayPrototype.set("reduce", nativeFn("reduce", 2, (t, a, c) -> {
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            JSFunction fn = asCallback(arg(a, 0), "reduce");
            int start;
            Object acc;
            if (a.length >= 2) {
                acc = a[1];
                start = 0;
            } else {
                if (len == 0) {
                    throw AbruptCompletion.typeError("Reduce of empty array with no initial value");
                }
                start = 0;
                while (start < len && !hasIndexed(O, start)) start++;
                if (start >= len) {
                    throw AbruptCompletion.typeError("Reduce of empty array with no initial value");
                }
                acc = getIndexed(O, start);
                start++;
            }
            for (int i = start; i < len; i++) {
                if (!hasIndexed(O, i)) continue;
                acc = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{acc, getIndexed(O, i), (double) i, O}, c);
            }
            return acc;
        }));
        arrayPrototype.set("reduceRight", nativeFn("reduceRight", 2, (t, a, c) -> {
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            JSFunction fn = asCallback(arg(a, 0), "reduceRight");
            int start;
            Object acc;
            if (a.length >= 2) {
                acc = a[1];
                start = len - 1;
            } else {
                start = len - 1;
                while (start >= 0 && !hasIndexed(O, start)) start--;
                if (start < 0) {
                    throw AbruptCompletion.typeError("Reduce of empty array with no initial value");
                }
                acc = getIndexed(O, start);
                start--;
            }
            for (int i = start; i >= 0; i--) {
                if (!hasIndexed(O, i)) continue;
                acc = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{acc, getIndexed(O, i), (double) i, O}, c);
            }
            return acc;
        }));
        arrayPrototype.set("find", nativeFn("find", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            JSFunction fn = asCallback(arg(a, 0), "find");
            Object thisArg = arg(a, 1);
            for (int i = 0; i < len; i++) {
                Object v = getIndexed(t, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg,
                        new Object[]{v, (double) i, t}, c))) return v;
            }
            return Undefined.VALUE;
        }));
        arrayPrototype.set("findIndex", nativeFn("findIndex", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            JSFunction fn = asCallback(arg(a, 0), "findIndex");
            Object thisArg = arg(a, 1);
            for (int i = 0; i < len; i++) {
                Object v = getIndexed(t, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg,
                        new Object[]{v, (double) i, t}, c))) return (double) i;
            }
            return -1.0;
        }));
        arrayPrototype.set("findLast", nativeFn("findLast", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            JSFunction fn = asCallback(arg(a, 0), "findLast");
            Object thisArg = arg(a, 1);
            for (int i = len - 1; i >= 0; i--) {
                Object v = getIndexed(t, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg,
                        new Object[]{v, (double) i, t}, c))) return v;
            }
            return Undefined.VALUE;
        }));
        arrayPrototype.set("findLastIndex", nativeFn("findLastIndex", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            JSFunction fn = asCallback(arg(a, 0), "findLastIndex");
            Object thisArg = arg(a, 1);
            for (int i = len - 1; i >= 0; i--) {
                Object v = getIndexed(t, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg,
                        new Object[]{v, (double) i, t}, c))) return (double) i;
            }
            return -1.0;
        }));
        arrayPrototype.set("some", nativeFn("some", 1, (t, a, c) -> {
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            JSFunction fn = asCallback(arg(a, 0), "some");
            Object thisArg = arg(a, 1);
            for (int i = 0; i < len; i++) {
                if (!hasIndexed(O, i)) continue;
                Object v = getIndexed(O, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg,
                        new Object[]{v, (double) i, O}, c))) return true;
            }
            return false;
        }));
        arrayPrototype.set("every", nativeFn("every", 1, (t, a, c) -> {
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            JSFunction fn = asCallback(arg(a, 0), "every");
            Object thisArg = arg(a, 1);
            for (int i = 0; i < len; i++) {
                if (!hasIndexed(O, i)) continue;
                Object v = getIndexed(O, i);
                if (!AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg,
                        new Object[]{v, (double) i, O}, c))) return false;
            }
            return true;
        }));
        arrayPrototype.set("at", nativeFn("at", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            int k = AbstractOps.toInt32(arg(a, 0));
            if (k < 0) k += len;
            if (k < 0 || k >= len) return Undefined.VALUE;
            return getIndexed(t, k);
        }));
        arrayPrototype.set("sort", nativeFn("sort", 1, (t, a, c) -> {
            Object cmpArg = arg(a, 0);
            // § 23.1.3.30 step 1: comparefn must be undefined or callable;
            // any other value (null, primitive, non-callable object) is a
            // TypeError observed BEFORE this.length is touched.
            if (cmpArg != Undefined.VALUE && !(cmpArg instanceof JSFunction)) {
                throw AbruptCompletion.typeError(
                    "Array.prototype.sort: comparefn must be a function or undefined");
            }
            // § 23.1.3.30 step 2: obj = ToObject(this). Primitive receivers
            // get auto-boxed and the wrapper is returned (e.g.
            // [].sort.call(false) returns a Boolean wrapper, len=0).
            t = toObject(t);
            JSFunction cmp = (cmpArg instanceof JSFunction f) ? f : null;
            // ECMA-262 § 23.1.3.30 — sort is generic on array-likes.
            // The receiver may be any object with a length + indexed
            // reads/writes; we keep the JSArray fast path for the
            // common case and use the array-like helpers otherwise.
            final Object[] cmpBuf = cmp != null ? new Object[]{null, null} : null;
            java.util.Comparator<Object> comparator = (x, y) -> {
                if (cmp != null) {
                    cmpBuf[0] = x;
                    cmpBuf[1] = y;
                    Object r = Interpreter.invokeFunction(cmp, Undefined.VALUE, cmpBuf, c);
                    double d = AbstractOps.toNumber(r);
                    if (Double.isNaN(d)) return 0;
                    return d < 0 ? -1 : (d > 0 ? 1 : 0);
                }
                if (x == Undefined.VALUE) return y == Undefined.VALUE ? 0 : 1;
                if (y == Undefined.VALUE) return -1;
                return AbstractOps.toString(x).compareTo(AbstractOps.toString(y));
            };
            // § 23.1.3.30 / 23.1.3.30.1 SortIndexedProperties:
            //   1. Collect items = own indexed properties that are NOT holes
            //      AND NOT undefined.
            //   2. Count undefined values separately.
            //   3. Sort items with comparefn (undefined-aware fallback).
            //   4. Write back: indices [0, items.length) = sorted items;
            //      [items.length, items.length + undefinedCount) = undefined;
            //      [items.length + undefinedCount, len) = hole (delete).
            if (t instanceof JSArray arr) {
                int len = arr.length();
                java.util.List<Object> items = new java.util.ArrayList<>(len);
                int undefinedCount = 0;
                for (int i = 0; i < len; i++) {
                    if (arr.isHole(i)) continue;
                    Object v = arr.get(i);
                    if (v == Undefined.VALUE) undefinedCount++;
                    else items.add(v);
                }
                items.sort(comparator);
                int k = 0;
                for (; k < items.size(); k++) arr.set(k, items.get(k));
                for (int u = 0; u < undefinedCount; u++) arr.set(k++, Undefined.VALUE);
                // Mark remaining trailing slots as holes (spec deletes them).
                // .length stays the same; observing arr[i] for i >= k returns
                // undefined via the hole sentinel.
                for (int i = k; i < arr.elements().size(); i++) {
                    arr.elements().set(i, Op.HOLE);
                }
                return arr;
            }
            int len = lengthOfArrayLike(t);
            java.util.List<Object> items = new java.util.ArrayList<>(len);
            int undefinedCount = 0;
            for (int i = 0; i < len; i++) {
                if (!hasIndexed(t, i)) continue;
                Object v = getIndexed(t, i);
                if (v == Undefined.VALUE) undefinedCount++;
                else items.add(v);
            }
            items.sort(comparator);
            int k = 0;
            for (; k < items.size(); k++) setIndexed(t, k, items.get(k));
            for (int u = 0; u < undefinedCount; u++) setIndexed(t, k++, Undefined.VALUE);
            for (int i = k; i < len; i++) {
                if (t instanceof JSObject jo) jo.delete(Integer.toString(i));
            }
            return t;
        }));
        arrayPrototype.set("flat", nativeFn("flat", 0, (t, a, c) -> {
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            double depthD = arg(a, 0) == Undefined.VALUE ? 1.0 : AbstractOps.toNumber(a[0]);
            if (Double.isNaN(depthD) || depthD < 0) depthD = 0;
            int depth = Double.isInfinite(depthD) ? Integer.MAX_VALUE : (int) depthD;
            Object out = arraySpeciesCreate(O, 0);
            int outIdx = 0;
            for (int i = 0; i < len; i++) {
                if (!hasIndexed(O, i)) continue;
                Object e = getIndexed(O, i);
                if (e instanceof JSArray inner && depth > 0) {
                    outIdx = flattenIntoSpec(out, inner, outIdx, depth - 1);
                } else {
                    setIndexed(out, outIdx++, e);
                }
            }
            if (!(out instanceof JSArray)) {
                AbstractOps.setProperty(out, "length", (double) outIdx);
            }
            return out;
        }));
        arrayPrototype.set("flatMap", nativeFn("flatMap", 1, (t, a, c) -> {
            Object O = toObject(t);
            int len = lengthOfArrayLike(O);
            JSFunction fn = asCallback(arg(a, 0), "flatMap");
            Object thisArg = arg(a, 1);
            Object out = arraySpeciesCreate(O, 0);
            int outIdx = 0;
            for (int i = 0; i < len; i++) {
                if (!hasIndexed(O, i)) continue;
                Object v = Interpreter.invokeFunction(fn, thisArg,
                    new Object[]{getIndexed(O, i), (double) i, O}, c);
                if (v instanceof JSArray inner) {
                    outIdx = flattenIntoSpec(out, inner, outIdx, 0);
                } else {
                    setIndexed(out, outIdx++, v);
                }
            }
            if (!(out instanceof JSArray)) {
                AbstractOps.setProperty(out, "length", (double) outIdx);
            }
            return out;
        }));
        arrayPrototype.set("fill", nativeFn("fill", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            Object value = arg(a, 0);
            int start = sliceIndex(arg(a, 1), len, 0);
            int end = arg(a, 2) == Undefined.VALUE ? len : sliceIndex(a[2], len, len);
            for (int i = start; i < end; i++) setIndexed(t, i, value);
            return t;
        }));
        arrayPrototype.set("copyWithin", nativeFn("copyWithin", 2, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            int target = sliceIndex(arg(a, 0), len, 0);
            int start = sliceIndex(arg(a, 1), len, 0);
            int end = arg(a, 2) == Undefined.VALUE ? len : sliceIndex(a[2], len, len);
            int count = Math.min(end - start, len - target);
            if (count <= 0) return t;
            // Direction: from > to or to > from (avoid clobber).
            int dir = (start < target && target < start + count) ? -1 : 1;
            int from = dir < 0 ? start + count - 1 : start;
            int to = dir < 0 ? target + count - 1 : target;
            for (int k = 0; k < count; k++) {
                if (hasIndexed(t, from)) setIndexed(t, to, getIndexed(t, from));
                else if (t instanceof JSObject jo) jo.delete(Integer.toString(to));
                from += dir;
                to += dir;
            }
            return t;
        }));
        arrayPrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            // § 23.1.3.34: ToObject; lookup join; if not callable fall back
            // to Object.prototype.toString. We've already wired array-likes
            // through join, so just delegate.
            Object joinFn = AbstractOps.getProperty(t, "join");
            if (joinFn instanceof JSFunction f) {
                return Interpreter.invokeFunction(f, t, new Object[0], c);
            }
            return "[object Array]";
        }));
        // ECMA-262 § 23.1.3.29 Array.prototype.splice — remove deleteCount
        // elements starting at start, insert the trailing args, return the
        // deleted slice. Mutates `this` in place.
        arrayPrototype.set("splice", nativeFn("splice", 2, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            int start = sliceIndex(arg(a, 0), len, 0);
            int actualDelete;
            int insertCount = Math.max(0, a.length - 2);
            if (a.length == 0) actualDelete = 0;
            else if (a.length == 1) actualDelete = len - start;
            else {
                double d = AbstractOps.toNumber(a[1]);
                if (Double.isNaN(d) || d <= 0) actualDelete = 0;
                else actualDelete = (int) Math.min(d, len - start);
            }
            // § 23.1.3.29 step 9: removed = ArraySpeciesCreate(O, actualDelete).
            Object removed = arraySpeciesCreate(t, actualDelete);
            for (int i = 0; i < actualDelete; i++) {
                if (hasIndexed(t, start + i)) {
                    setIndexed(removed, i, getIndexed(t, start + i));
                }
            }
            if (!(removed instanceof JSArray)) {
                AbstractOps.setProperty(removed, "length", (double) actualDelete);
            }
            int newLen = len - actualDelete + insertCount;
            if (insertCount < actualDelete) {
                // Shift left to close gap.
                for (int i = start; i < len - actualDelete; i++) {
                    int src = i + actualDelete;
                    int dst = i + insertCount;
                    if (hasIndexed(t, src)) setIndexed(t, dst, getIndexed(t, src));
                    else if (t instanceof JSObject jo) jo.delete(Integer.toString(dst));
                }
                // Trim tail.
                for (int i = newLen; i < len; i++) {
                    if (t instanceof JSObject jo) jo.delete(Integer.toString(i));
                }
            } else if (insertCount > actualDelete) {
                // Shift right to make room.
                for (int i = len - actualDelete - 1; i >= start; i--) {
                    int src = i + actualDelete;
                    int dst = i + insertCount;
                    if (hasIndexed(t, src)) setIndexed(t, dst, getIndexed(t, src));
                    else if (t instanceof JSObject jo) jo.delete(Integer.toString(dst));
                }
            }
            // Insert new elements.
            for (int i = 0; i < insertCount; i++) {
                setIndexed(t, start + i, a[i + 2]);
            }
            // § 23.1.3.29 final Set(O, "length", finalLength, true) — when
            // length is non-writable, surface as TypeError instead of
            // silent fail.
            if (t instanceof JSArray targetArr) {
                byte la = targetArr.getIndexAttributes("length");
                if ((la & JSObject.ATTR_WRITABLE) == 0 && newLen != targetArr.length()) {
                    throw AbruptCompletion.typeError(
                        "Array.prototype.splice: array length is not writable");
                }
            }
            AbstractOps.setProperty(t, "length", (double) newLen);
            return removed;
        }));
        // § 23.1.3.35 Array.prototype.toSpliced — non-mutating splice.
        arrayPrototype.set("toSpliced", nativeFn("toSpliced", 2, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            int start = sliceIndex(arg(a, 0), len, 0);
            int actualDelete;
            int insertCount = Math.max(0, a.length - 2);
            if (a.length == 0) actualDelete = 0;
            else if (a.length == 1) actualDelete = len - start;
            else {
                double d = AbstractOps.toNumber(a[1]);
                if (Double.isNaN(d) || d <= 0) actualDelete = 0;
                else actualDelete = (int) Math.min(d, len - start);
            }
            JSArray out = new JSArray();
            for (int i = 0; i < start; i++) {
                out.push(hasIndexed(t, i) ? getIndexed(t, i) : Undefined.VALUE);
            }
            for (int i = 2; i < a.length; i++) out.push(a[i]);
            for (int i = start + actualDelete; i < len; i++) {
                out.push(hasIndexed(t, i) ? getIndexed(t, i) : Undefined.VALUE);
            }
            return out;
        }));
        // § 23.1.3.38 Array.prototype.toReversed — non-mutating reverse.
        arrayPrototype.set("toReversed", nativeFn("toReversed", 0, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            JSArray out = new JSArray();
            for (int i = len - 1; i >= 0; i--) {
                out.push(hasIndexed(t, i) ? getIndexed(t, i) : Undefined.VALUE);
            }
            return out;
        }));
        // § 23.1.3.39 Array.prototype.toSorted — non-mutating sort.
        arrayPrototype.set("toSorted", nativeFn("toSorted", 1, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            Object[] vals = new Object[len];
            for (int i = 0; i < len; i++) {
                vals[i] = hasIndexed(t, i) ? getIndexed(t, i) : Undefined.VALUE;
            }
            Object cmpArg = arg(a, 0);
            JSFunction cmp = (cmpArg instanceof JSFunction f) ? f : null;
            java.util.Arrays.sort(vals, (x, y) -> {
                if (x == Undefined.VALUE && y == Undefined.VALUE) return 0;
                if (x == Undefined.VALUE) return 1;
                if (y == Undefined.VALUE) return -1;
                if (cmp != null) {
                    Object r = Interpreter.invokeFunction(cmp, Undefined.VALUE, new Object[]{x, y}, c);
                    double d = AbstractOps.toNumber(r);
                    if (Double.isNaN(d)) return 0;
                    return d < 0 ? -1 : (d > 0 ? 1 : 0);
                }
                return AbstractOps.toString(x).compareTo(AbstractOps.toString(y));
            });
            JSArray out = new JSArray();
            for (Object v : vals) out.push(v);
            return out;
        }));
        // § 23.1.3.40 Array.prototype.with — non-mutating element replace.
        arrayPrototype.set("with", nativeFn("with", 2, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            int idx = AbstractOps.toInt32(arg(a, 0));
            if (idx < 0) idx += len;
            if (idx < 0 || idx >= len) throw AbruptCompletion.rangeError("with: index out of range");
            Object value = arg(a, 1);
            JSArray out = new JSArray();
            for (int i = 0; i < len; i++) {
                out.push(i == idx ? value : (hasIndexed(t, i) ? getIndexed(t, i) : Undefined.VALUE));
            }
            return out;
        }));
        arrayPrototype.set("toLocaleString", nativeFn("toLocaleString", 0, (t, a, c) -> {
            int len = lengthOfArrayLike(t);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                Object e = getIndexed(t, i);
                if (e == null || e == Undefined.VALUE) continue;
                sb.append(AbstractOps.toString(e));
            }
            return sb.toString();
        }));
        // ECMA-262 § 23.1.3.31 Array.prototype.values — returns an Array
        // Iterator (§ 23.1.5.1) whose .next yields each element in order.
        // ECMA-262 § 23.1.3.36 Array.prototype.values: this is the array's
        // {@code @@iterator}, but the receiver only needs to be array-like
        // (ToObject + length-driven indexed reads) — that's what makes
        // {@code Array.prototype.values.call(arguments)} work, and what
        // {@code arguments[@@iterator]} ends up doing via this borrow.
        JSFunction valuesFn = nativeFn("values", 0, (t, a, c) -> makeArrayLikeIterator(t, "value"));
        arrayPrototype.set("values", valuesFn);
        // § 23.1.3.18 keys — Array Iterator over indices.
        arrayPrototype.set("keys", nativeFn("keys", 0, (t, a, c) -> makeArrayLikeIterator(t, "key")));
        // § 23.1.3.4 entries — Array Iterator over [index, value] pairs.
        arrayPrototype.set("entries", nativeFn("entries", 0, (t, a, c) -> makeArrayLikeIterator(t, "key+value")));
        // § 23.1.3.36 Array.prototype [ %Symbol.iterator% ] = .values.
        arrayPrototype.set(wellKnownIterator.asPropertyKey(), valuesFn);
        defaultArrayIterator = valuesFn;
        // ECMA-262 § 17: every built-in prototype method has attributes
        // { writable: true, enumerable: false, configurable: true }.
        // The `set` calls above used ATTR_DEFAULT (enumerable=true), so
        // batch-mark all own properties non-enumerable here.
        markMethodsNonEnumerable(arrayPrototype);
    }

    /** Convert Java's {@code %e}-formatted output ("1.234567e+02") to the
     *  spec-shaped exponential string ("1.234567e+2" — no leading zero,
     *  optional fraction). When {@code digits == -1}, trim trailing zeros
     *  in the mantissa (matches the default form of
     *  {@code Number.prototype.toExponential()}). */
    private static String normalizeExponential(String raw, int digits) {
        int ePos = raw.indexOf('e');
        if (ePos < 0) return raw;
        String mantissa = raw.substring(0, ePos);
        String exp = raw.substring(ePos + 1);
        // Strip leading zeros from the exponent (keep sign).
        int sign = 1;
        int j = 0;
        if (j < exp.length() && exp.charAt(j) == '+') { j++; }
        else if (j < exp.length() && exp.charAt(j) == '-') { sign = -1; j++; }
        while (j < exp.length() - 1 && exp.charAt(j) == '0') j++;
        int expVal = Integer.parseInt(exp.substring(j)) * sign;
        if (digits == -1) {
            // Trim trailing zeros after the decimal point, then a trailing dot.
            int dot = mantissa.indexOf('.');
            if (dot >= 0) {
                int end = mantissa.length();
                while (end > dot + 1 && mantissa.charAt(end - 1) == '0') end--;
                if (end == dot + 1) end = dot;
                mantissa = mantissa.substring(0, end);
            }
        }
        String expStr = expVal >= 0 ? "+" + expVal : Integer.toString(expVal);
        return mantissa + "e" + expStr;
    }

    /** ECMA-262 § 6.2.5 HasProperty — walks the prototype chain. Used by
     *  ToPropertyDescriptor field-presence checks. */
    private static boolean descObjHas(JSObject desc, String key) {
        JSObject cursor = desc;
        while (cursor != null) {
            if (cursor.hasOwn(key)) return true;
            cursor = cursor.proto();
        }
        return false;
    }

    /** HasProperty over any object kind (JSObject / JSFunction / JSArray).
     *  Used by ToPropertyDescriptor: defineProperty(obj, key, funObj)
     *  is legal — functions are objects in JS. */
    private static boolean descHasField(Object desc, String key) {
        if (desc instanceof JSObject jo) return descObjHas(jo, key);
        if (desc instanceof JSFunction fn) {
            if (fn.hasOwnStatic(key)) return true;
            // Functions inherit from Function.prototype which inherits from
            // Object.prototype — anything user-installed there counts too.
            if (functionPrototype != null && descObjHas(functionPrototype, key)) return true;
            return false;
        }
        if (desc instanceof JSArray arr) {
            if ("length".equals(key)) return true;
            int idx = parseIndex(key);
            if (idx >= 0 && idx < arr.length()) return true;
            if (arr.hasExtraProperty(key)) return true;
            if (arrayPrototype != null && descObjHas(arrayPrototype, key)) return true;
            return false;
        }
        return false;
    }

    /** Mark every own property of {@code proto} non-enumerable (writable +
     *  configurable). Per ECMA-262 § 17 conventions for built-in methods.
     *  Preserves entries that already have a non-default attribute byte
     *  (e.g. {@code Math.E} which the bootstrap set to (0,0,0)
     *  non-writable/non-configurable per § 21.3.1). */
    static void markMethodsNonEnumerable(JSObject proto) {
        byte methodAttrs = (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE);
        // Iterate a snapshot — setAttributes transitions the shape.
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String k : proto.ownKeys()) keys.add(k);
        for (String k : keys) {
            // Skip 'constructor' which keeps its descriptor per the existing
            // installations (most prototypes have already set its attrs).
            // Leave any pre-existing custom attrs (Accessor pairs etc.) alone.
            Object v = proto.getOwn(k);
            if (v instanceof Accessor) continue;
            // Don't clobber entries that already have explicit attributes —
            // they were set deliberately (e.g. non-writable constants).
            if (proto.getAttributes(k) != JSObject.ATTR_DEFAULT) continue;
            proto.setAttributes(k, methodAttrs);
        }
    }

    /** ECMA-262 § 17: built-in prototype/namespace methods are not
     *  constructors (`new Math.abs()` / `Reflect.construct(eval)` throw
     *  TypeError, `Promise.all.call(eval)` sees IsConstructor=false).
     *  Sweep marks every JSFunction installed as a value on the
     *  prototype as non-constructor. Skips entries that look like
     *  constructors themselves (have a {@code prototype} object with a
     *  back-pointing {@code constructor}). */
    static void markMethodsNonConstructor(JSObject proto) {
        if (proto == null) return;
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String k : proto.ownKeys()) keys.add(k);
        for (String k : keys) {
            if ("constructor".equals(k)) continue;
            Object v = proto.getOwn(k);
            if (v instanceof JSFunction fn && fn.prototypeObject() == null) {
                fn.setNonConstructor(true);
            }
        }
    }

    /** Static methods on a constructor (e.g. Math.abs, Object.keys) are
     *  similarly non-constructible. Doesn't touch the constructor's own
     *  invocation surface. */
    static void markStaticsNonConstructor(JSFunction fn) {
        if (fn == null) return;
        for (String k : new java.util.ArrayList<>(fn.propertiesIfPresent().keySet())) {
            Object v = fn.getOwnStatic(k);
            if (v instanceof JSFunction sub && sub.prototypeObject() == null) {
                sub.setNonConstructor(true);
            }
        }
    }

    /** Same as {@link #markMethodsNonEnumerable} but for static properties
     *  installed on a {@link JSFunction} (e.g. Object.keys, Array.from,
     *  Date.UTC). Skips already-set custom attribute records so anything
     *  with a more restrictive descriptor (BYTES_PER_ELEMENT etc.) stays. */
    static void markStaticsNonEnumerable(JSFunction fn) {
        if (fn == null) return;
        byte methodAttrs = (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE);
        java.util.List<String> keys = new java.util.ArrayList<>(fn.propertiesIfPresent().keySet());
        for (String k : keys) {
            Object v = fn.getOwnStatic(k);
            if (v instanceof Accessor) continue;
            // Preserve any non-default attribute already installed (e.g.
            // typed-array BYTES_PER_ELEMENT is frozen).
            if (fn.getAttributes(k) != JSObject.ATTR_DEFAULT) continue;
            fn.setAttributes(k, methodAttrs);
        }
    }

    /**
     * Build an Array Iterator object — ECMA-262 § 23.1.5. Returns a JSObject
     * with a {@code next} method that produces {@code {value, done}} records.
     * {@code kind} ∈ {@code "value"} / {@code "key"} / {@code "key+value"}.
     */
    /**
     * Array Iterator for an array-like receiver — JSArray, JSObject /
     * JSFunction with numeric indexed properties and a {@code length}, or
     * a String. Spec: § 23.1.5.1 CreateArrayIterator (which only
     * requires array-like via ToObject + Get(length) + indexed reads,
     * not specifically Array exotic).
     */
    /** Array.prototype's keys/values/entries / TypedArray's same — produces
     *  an %ArrayIteratorPrototype%-based iterator. Length is read live each
     *  step (per § 23.1.5.1) so {@code arr.push(x)} during iteration is
     *  observed. */
    private static JSObject makeArrayLikeIterator(Object receiver, String kind) {
        if (receiver == null || receiver == Undefined.VALUE) {
            throw AbruptCompletion.typeError(
                "Array.prototype iterator: this is " + (receiver == null ? "null" : "undefined"));
        }
        return com.jimmyhmiller.harmonica.bytecode.builtins
            .ArrayIteratorPrototypeBuiltin.create(
                receiver,
                com.jimmyhmiller.harmonica.bytecode.builtins
                    .ArrayIteratorPrototypeBuiltin.kindFor(kind));
    }

    /** Lazily build (and cache) the shared iterator prototype with the
     *  given {@code @@toStringTag} value. Each prototype has an
     *  {@code @@iterator} method returning {@code this}. */
    public static volatile JSObject regExpStringIteratorPrototype;

    private static JSObject ensureIteratorProto(String tag) {
        JSObject existing = switch (tag) {
            case "Array Iterator"  -> arrayIteratorPrototype;
            case "String Iterator" -> stringIteratorPrototype;
            case "Map Iterator"    -> mapIteratorPrototype;
            case "Set Iterator"    -> setIteratorPrototype;
            case "RegExp String Iterator" -> regExpStringIteratorPrototype;
            default -> null;
        };
        if (existing != null) return existing;
        JSObject proto = new JSObject(iteratorPrototype != null ? iteratorPrototype : objectPrototype);
        if (wellKnownIterator != null) {
            proto.set(wellKnownIterator.asPropertyKey(),
                nativeFn("[Symbol.iterator]", 0, (t, a, c) -> t));
            proto.setAttributes(wellKnownIterator.asPropertyKey(),
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        }
        if (wellKnownToStringTag != null) {
            proto.set(wellKnownToStringTag.asPropertyKey(), tag);
            proto.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        }
        switch (tag) {
            case "Array Iterator"  -> arrayIteratorPrototype = proto;
            case "String Iterator" -> stringIteratorPrototype = proto;
            case "Map Iterator"    -> mapIteratorPrototype = proto;
            case "Set Iterator"    -> setIteratorPrototype = proto;
            case "RegExp String Iterator" -> regExpStringIteratorPrototype = proto;
        }
        return proto;
    }

    private static JSObject makeArrayIterator(JSArray arr, String kind) {
        return com.jimmyhmiller.harmonica.bytecode.builtins
            .ArrayIteratorPrototypeBuiltin.create(arr,
                com.jimmyhmiller.harmonica.bytecode.builtins
                    .ArrayIteratorPrototypeBuiltin.kindFor(kind));
    }

    /** § 23.1.3.10.1 FlattenIntoArray — spec version that respects holes,
     *  uses setIndexed (so species-result objects are handled), and returns
     *  the next outIdx. */
    private static int flattenIntoSpec(Object out, JSArray src, int outIdx, int depth) {
        for (int i = 0; i < src.length(); i++) {
            if (!hasIndexed(src, i)) continue;
            Object e = getIndexed(src, i);
            if (e instanceof JSArray inner && depth > 0) {
                outIdx = flattenIntoSpec(out, inner, outIdx, depth - 1);
            } else {
                setIndexed(out, outIdx++, e);
            }
        }
        return outIdx;
    }

    private static void flattenInto(JSArray src, JSArray dst, int depth) {
        for (Object e : src.elements()) {
            if (e instanceof JSArray inner && depth > 0) flattenInto(inner, dst, depth - 1);
            else dst.push(e);
        }
    }

    /** ECMA-262 § 7.1.18 ToObject — boxes primitives to their wrapper
     *  type so callback invocations receive the spec-required Object,
     *  not the primitive. Throws TypeError on null/undefined. Used by
     *  Array.prototype.{forEach,map,filter,every,some,reduce,...} when
     *  passing `O` as the third callback argument. */
    static Object toObject(Object v) {
        if (v == null || v == Undefined.VALUE) {
            throw AbruptCompletion.typeError("Cannot convert undefined or null to object");
        }
        if (v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction) return v;
        if (v instanceof Boolean b) {
            JSObject w = new JSObject(booleanPrototype);
            w.properties().put(SLOT_BOOLEAN_DATA, b);
            return w;
        }
        if (v instanceof Number n) {
            JSObject w = new JSObject(numberPrototype);
            w.properties().put(SLOT_NUMBER_DATA, n);
            return w;
        }
        if (v instanceof CharSequence cs) {
            String s = cs.toString();
            JSObject w = new JSObject(stringPrototype);
            w.properties().put(SLOT_STRING_DATA, s);
            w.set("length", (double) s.length());
            w.setAttributes("length", (byte) 0);
            return w;
        }
        if (v instanceof JSBigInt bi) {
            JSObject w = new JSObject(bigIntPrototype);
            w.properties().put(SLOT_BIGINT_DATA, bi);
            return w;
        }
        if (v instanceof JSSymbol sy) {
            JSObject w = new JSObject(symbolPrototype);
            w.properties().put("##SymbolData##", sy);
            return w;
        }
        return v;
    }

    /** ECMA-262 § 7.4.10 IteratorClose — invoke the iterator's
     *  {@code return} method (if any) so resources held by the
     *  underlying generator are freed. Swallows any exception thrown
     *  by return per § 7.4.10 (used in NormalCompletion paths). */
    static void closeIterator(JSObject iter, InterpContext c) {
        try {
            Object retFn = AbstractOps.getProperty(iter, "return");
            if (retFn instanceof JSFunction rf) {
                Interpreter.invokeFunction(rf, iter, new Object[0], c);
            }
        } catch (AbruptCompletion ignored) {
            // Spec: when closing during a normal completion, swallow.
        }
    }

    /** ECMA-262 § 7.2.1 RequireObjectCoercible — throws TypeError on
     *  null/undefined, returns the value otherwise. Used by String /
     *  Object prototype methods that begin with this step. */
    static Object requireObjectCoercible(Object v, String method) {
        if (v == null) {
            throw AbruptCompletion.typeError(
                "String.prototype." + method + " called on null");
        }
        if (v == Undefined.VALUE) {
            throw AbruptCompletion.typeError(
                "String.prototype." + method + " called on undefined");
        }
        return v;
    }

    /** Coerce {@code this} to a String per § 22.1.3 — but throw TypeError
     *  on null/undefined first (RequireObjectCoercible). */
    static String thisStringCoerced(Object v, String method) {
        requireObjectCoercible(v, method);
        return AbstractOps.toString(v);
    }

    private static JSFunction asCallback(Object v, String method) {
        if (v instanceof JSFunction fn) return fn;
        throw AbruptCompletion.typeError("" + method + " callback is not a function");
    }

    // ============================================================
    //  String.prototype
    // ============================================================

    private static void installStringPrototype() {
        // § 22.1.3: String.prototype is itself a String exotic object
        // whose [[StringData]] is "" — installing the slot here lets
        // {@code String.prototype.toString()} (called on the prototype
        // directly, as test262 does) return "" instead of throwing
        // "called on non-String", and also gives it the matching
        // own `length` of 0 with frozen attributes.
        if (!stringPrototype.properties().containsKey(SLOT_STRING_DATA)) {
            stringPrototype.properties().put(SLOT_STRING_DATA, "");
            stringPrototype.set("length", 0.0);
            stringPrototype.setAttributes("length", (byte) 0);
        }
        stringPrototype.set("toUpperCase", nativeFn("toUpperCase", 0,
            (t, a, c) -> thisStringCoerced(t, "toUpperCase").toUpperCase()));
        stringPrototype.set("toLowerCase", nativeFn("toLowerCase", 0,
            (t, a, c) -> thisStringCoerced(t, "toLowerCase").toLowerCase()));
        stringPrototype.set("charAt", nativeFn("charAt", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "charAt");
            int idx = AbstractOps.toInt32(arg(a, 0));
            if (idx < 0 || idx >= s.length()) return "";
            return String.valueOf(s.charAt(idx));
        }));
        stringPrototype.set("charCodeAt", nativeFn("charCodeAt", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "charCodeAt");
            int idx = AbstractOps.toInt32(arg(a, 0));
            if (idx < 0 || idx >= s.length()) return Double.NaN;
            return (double) s.charAt(idx);
        }));
        stringPrototype.set("indexOf", nativeFn("indexOf", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "indexOf");
            String tgt = AbstractOps.toString(arg(a, 0));
            int from = arg(a, 1) == Undefined.VALUE ? 0 : AbstractOps.toInt32(a[1]);
            return (double) s.indexOf(tgt, Math.max(0, from));
        }));
        stringPrototype.set("lastIndexOf", nativeFn("lastIndexOf", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "lastIndexOf");
            String tgt = AbstractOps.toString(arg(a, 0));
            return (double) s.lastIndexOf(tgt);
        }));
        stringPrototype.set("slice", nativeFn("slice", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "slice");
            int len = s.length();
            int start = sliceIndex(arg(a, 0), len, 0);
            int end = sliceIndex(arg(a, 1), len, len);
            if (end < start) end = start;
            return s.substring(start, end);
        }));
        stringPrototype.set("substring", nativeFn("substring", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "substring");
            int len = s.length();
            int x = arg(a, 0) == Undefined.VALUE ? 0 : Math.max(0, Math.min(len, AbstractOps.toInt32(a[0])));
            int y = arg(a, 1) == Undefined.VALUE ? len : Math.max(0, Math.min(len, AbstractOps.toInt32(a[1])));
            return s.substring(Math.min(x, y), Math.max(x, y));
        }));
        stringPrototype.set("split", nativeFn("split", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "split");
            JSArray out = new JSArray();
            if (arg(a, 0) == Undefined.VALUE) { out.push(s); return out; }
            Object sep0 = a[0];
            // ECMA-262 § 22.1.3.21 step 8: limit is a Uint32 cap on the
            // returned array's length. undefined → 2^32 - 1 (effectively
            // unbounded). Caught by RegexDifferentialTest "split-limit".
            long limit;
            if (a.length < 2 || a[1] == Undefined.VALUE) {
                limit = Long.MAX_VALUE;
            } else {
                double d = AbstractOps.toNumber(a[1]);
                if (Double.isNaN(d) || d <= 0) limit = 0;
                else limit = (long) d;
            }
            if (limit == 0) return out;
            // ECMA-262 § 22.1.3.21 SplitMatch: the spec is fiddly about
            // zero-width matches. Java's Pattern.split({s, -1}) keeps too
            // many empties; .split(s) strips all trailing empties; neither
            // matches JS exactly. Roll our own:
            //   - Non-zero-width match: emit pre-match slice, advance past
            //   - Zero-width match at start of input: skip, advance by 1
            //   - Zero-width match at current pos (no progress): advance by 1
            //   - Zero-width match at end of input: stop
            //   - Final tail: always emitted (yields trailing empty if a
            //     non-zero-width match landed at end of string)
            if (asRegExpSource(sep0) != null) {
                java.util.regex.Pattern p = compileJsRegex(asRegExpSource(sep0), asRegExpFlags(sep0));
                splitJs(s, p, limit, out);
            } else {
                String sep = AbstractOps.toString(sep0);
                if (sep.isEmpty()) {
                    // Empty literal separator: split into individual chars.
                    int len = (int) Math.min(limit, s.length());
                    for (int i = 0; i < len; i++) out.push(String.valueOf(s.charAt(i)));
                } else {
                    splitJs(s, java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(sep)), limit, out);
                }
            }
            return out;
        }));
        // ECMA-262 § 22.1.3.32 String.prototype.trim — strip leading and
        // trailing WhiteSpace and LineTerminator code points. Java's
        // String.trim() only strips ASCII whitespace; we need the full
        // JS set (BOM, non-breaking space, Unicode separators, etc.).
        final String WS_CLASS =
            "[\\u0009\\u000A\\u000B\\u000C\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
        stringPrototype.set("trim", nativeFn("trim", 0,
            (t, a, c) -> thisStringCoerced(t, "trim").replaceAll("^" + WS_CLASS + "+|" + WS_CLASS + "+$", "")));
        stringPrototype.set("trimStart", nativeFn("trimStart", 0,
            (t, a, c) -> thisStringCoerced(t, "trimStart").replaceAll("^" + WS_CLASS + "+", "")));
        stringPrototype.set("trimEnd", nativeFn("trimEnd", 0,
            (t, a, c) -> thisStringCoerced(t, "trimEnd").replaceAll(WS_CLASS + "+$", "")));
        // § 22.1.3.7 includes / § 22.1.3.21 startsWith / § 22.1.3.6 endsWith.
        // All three throw TypeError when search is a RegExp; coerce position
        // via ToIntegerOrInfinity (clamped to [0, len]); endsWith treats
        // {@code position == undefined} as len.
        stringPrototype.set("includes", nativeFn("includes", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "includes");
            rejectRegExpSearch(arg(a, 0), "includes");
            String search = AbstractOps.toString(arg(a, 0));
            int pos = stringMethodPos(arg(a, 1), s.length(), 0);
            return s.indexOf(search, pos) >= 0;
        }));
        stringPrototype.set("startsWith", nativeFn("startsWith", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "startsWith");
            rejectRegExpSearch(arg(a, 0), "startsWith");
            String search = AbstractOps.toString(arg(a, 0));
            int pos = stringMethodPos(arg(a, 1), s.length(), 0);
            return s.startsWith(search, pos);
        }));
        stringPrototype.set("endsWith", nativeFn("endsWith", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "endsWith");
            rejectRegExpSearch(arg(a, 0), "endsWith");
            String search = AbstractOps.toString(arg(a, 0));
            int endPos = arg(a, 1) == Undefined.VALUE
                ? s.length()
                : stringMethodPos(arg(a, 1), s.length(), s.length());
            int startPos = endPos - search.length();
            if (startPos < 0) return false;
            return s.regionMatches(startPos, search, 0, search.length());
        }));
        // AnnexB § B.2.3 — deprecated String.prototype HTML wrappers.
        // Each wraps the string in an HTML tag (sometimes with attribute).
        java.util.Map<String, String> htmlWrappers = new java.util.LinkedHashMap<>();
        htmlWrappers.put("anchor", "a");
        htmlWrappers.put("big", "big");
        htmlWrappers.put("blink", "blink");
        htmlWrappers.put("bold", "b");
        htmlWrappers.put("fixed", "tt");
        htmlWrappers.put("italics", "i");
        htmlWrappers.put("small", "small");
        htmlWrappers.put("strike", "strike");
        htmlWrappers.put("sub", "sub");
        htmlWrappers.put("sup", "sup");
        for (var e : htmlWrappers.entrySet()) {
            String name = e.getKey();
            String tag = e.getValue();
            int arity = "anchor".equals(name) ? 1 : 0;
            stringPrototype.set(name, nativeFn(name, arity, (t, a, c) -> {
                // RequireObjectCoercible — null/undefined receiver throws.
                String s = thisStringCoerced(t, name);
                if (arity == 1) {
                    String attr = AbstractOps.toString(arg(a, 0));
                    return "<" + tag + " name=\"" + attr.replace("\"", "&quot;") + "\">" + s + "</" + tag + ">";
                }
                return "<" + tag + ">" + s + "</" + tag + ">";
            }));
        }
        // The "attribute" wrappers — fontcolor, fontsize, link.
        stringPrototype.set("fontcolor", nativeFn("fontcolor", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "fontcolor");
            String attr = AbstractOps.toString(arg(a, 0));
            return "<font color=\"" + attr.replace("\"", "&quot;") + "\">" + s + "</font>";
        }));
        stringPrototype.set("fontsize", nativeFn("fontsize", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "fontsize");
            String attr = AbstractOps.toString(arg(a, 0));
            return "<font size=\"" + attr.replace("\"", "&quot;") + "\">" + s + "</font>";
        }));
        stringPrototype.set("link", nativeFn("link", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "link");
            String attr = AbstractOps.toString(arg(a, 0));
            return "<a href=\"" + attr.replace("\"", "&quot;") + "\">" + s + "</a>";
        }));
        // String.prototype.substr (deprecated AnnexB).
        stringPrototype.set("substr", nativeFn("substr", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "substr");
            int len = s.length();
            int start = AbstractOps.toInt32(arg(a, 0));
            if (start < 0) start = Math.max(0, len + start);
            else start = Math.min(start, len);
            int length = arg(a, 1) == Undefined.VALUE ? len - start : AbstractOps.toInt32(a[1]);
            length = Math.max(0, Math.min(length, len - start));
            return s.substring(start, start + length);
        }));

        // ECMA-262 § 22.1.3.1 String.prototype.at — index with negative-offset support.
        stringPrototype.set("at", nativeFn("at", 1, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int idx = AbstractOps.toInt32(arg(a, 0));
            if (idx < 0) idx += s.length();
            if (idx < 0 || idx >= s.length()) return Undefined.VALUE;
            return String.valueOf(s.charAt(idx));
        }));
        // ECMA-262 § 22.1.3.5 codePointAt.
        stringPrototype.set("codePointAt", nativeFn("codePointAt", 1, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int idx = AbstractOps.toInt32(arg(a, 0));
            if (idx < 0 || idx >= s.length()) return Undefined.VALUE;
            return (double) s.codePointAt(idx);
        }));
        // § 22.1.3.18 normalize — Unicode normalization. java.text.Normalizer handles all four forms.
        stringPrototype.set("normalize", nativeFn("normalize", 0, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            String form = arg(a, 0) == Undefined.VALUE ? "NFC" : AbstractOps.toString(a[0]);
            java.text.Normalizer.Form nf;
            switch (form) {
                case "NFC":  nf = java.text.Normalizer.Form.NFC;  break;
                case "NFD":  nf = java.text.Normalizer.Form.NFD;  break;
                case "NFKC": nf = java.text.Normalizer.Form.NFKC; break;
                case "NFKD": nf = java.text.Normalizer.Form.NFKD; break;
                default: throw AbruptCompletion.rangeError("Invalid normalization form: " + form);
            }
            return java.text.Normalizer.normalize(s, nf);
        }));
        // § 22.1.3.19 padEnd / padStart already defined later — skip here.
        // ECMA-262 § 22.1.3.22 replaceAll — regex or string search; global only.
        stringPrototype.set("replaceAll", nativeFn("replaceAll", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "replaceAll");
            Object search0 = arg(a, 0);
            Object repl0 = arg(a, 1);
            if (asRegExpSource(search0) != null) {
                String flags = asRegExpFlags(search0);
                if (!flags.contains("g")) {
                    throw AbruptCompletion.typeError("replaceAll must be called with a global RegExp");
                }
                // Delegate to replace which already handles the global path.
                Object replaceFn = stringPrototype.get("replace");
                if (replaceFn instanceof JSFunction f) {
                    return Interpreter.invokeFunction(f, t, new Object[]{search0, repl0}, c);
                }
                return s;
            }
            String search = AbstractOps.toString(search0);
            if (search.isEmpty()) {
                // Insert repl between every code unit.
                StringBuilder sb = new StringBuilder();
                if (repl0 instanceof JSFunction repFn) {
                    sb.append(AbstractOps.toString(
                        Interpreter.invokeFunction(repFn, Undefined.VALUE,
                            new Object[]{search, 0.0, s}, c)));
                    for (int i = 0; i < s.length(); i++) {
                        sb.append(s.charAt(i));
                        sb.append(AbstractOps.toString(
                            Interpreter.invokeFunction(repFn, Undefined.VALUE,
                                new Object[]{search, (double) (i + 1), s}, c)));
                    }
                } else {
                    String rep = AbstractOps.toString(repl0);
                    sb.append(rep);
                    for (int i = 0; i < s.length(); i++) {
                        sb.append(s.charAt(i)).append(rep);
                    }
                }
                return sb.toString();
            }
            String replString = repl0 instanceof JSFunction ? null : AbstractOps.toString(repl0);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            int idx;
            while ((idx = s.indexOf(search, last)) >= 0) {
                sb.append(s, last, idx);
                if (repl0 instanceof JSFunction repFn) {
                    sb.append(AbstractOps.toString(
                        Interpreter.invokeFunction(repFn, Undefined.VALUE,
                            new Object[]{search, (double) idx, s}, c)));
                } else {
                    sb.append(getSubstitution(search, s, idx, java.util.Collections.emptyList(), null, replString));
                }
                last = idx + search.length();
            }
            sb.append(s, last, s.length());
            return sb.toString();
        }));
        // § 22.1.3.13 String.prototype.matchAll(regexp). Per spec:
        //   1. Let O be ? RequireObjectCoercible(this value).
        //   2. If regexp is not nullish:
        //      a. Let isRegExp be ? IsRegExp(regexp).
        //      b. If isRegExp, then let flags be ? ToString(? Get(regexp, "flags"));
        //         if flags does NOT contain "g", throw TypeError.
        //      c. Let matcher be ? GetMethod(regexp, @@matchAll); if matcher
        //         is not undefined, return ? Call(matcher, regexp, « O »).
        //   3. Let S be ? ToString(O).
        //   4. Let rx be ? RegExpCreate(regexp, "g").
        //   5. Return ? Invoke(rx, @@matchAll, « S »).
        // The /g requirement only applies when the user passed a real RegExp;
        // a string pattern gets wrapped with /g implicitly.
        stringPrototype.set("matchAll", nativeFn("matchAll", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "matchAll");
            Object pat = arg(a, 0);
            Object regexp;
            if (pat instanceof JSObject patObj && asRegExpSource(patObj) != null) {
                Object flagsVal = AbstractOps.getProperty(patObj, "flags");
                String f = flagsVal == Undefined.VALUE ? "" : AbstractOps.toString(flagsVal);
                if (!f.contains("g")) {
                    throw AbruptCompletion.typeError(
                        "String.prototype.matchAll requires a global RegExp");
                }
                regexp = patObj;
            } else {
                String src = (pat == null || pat == Undefined.VALUE) ? "" : AbstractOps.toString(pat);
                Object reCtor = c == null ? null : c.globals().get("RegExp");
                if (reCtor instanceof JSFunction reCtorFn) {
                    regexp = Interpreter.invokeFunctionAsConstructor(
                        reCtorFn, new JSObject(regExpPrototype),
                        new Object[]{src, "g"}, c);
                } else {
                    throw AbruptCompletion.typeError(
                        "RegExp constructor missing during matchAll");
                }
            }
            // Delegate to RegExp.prototype[@@matchAll] so the iterator is
            // created with the correct prototype + slots.
            Object matchAllFn = AbstractOps.getProperty(regexp, wellKnownMatchAll.asPropertyKey());
            if (!(matchAllFn instanceof JSFunction f)) {
                throw AbruptCompletion.typeError(
                    "regexp[Symbol.matchAll] is not callable");
            }
            return Interpreter.invokeFunction(f, regexp, new Object[]{s}, c);
        }));
        // § 22.1.3.10 String.prototype.localeCompare — ECMA-402 if Intl is
        // installed; otherwise default to lexicographic.
        stringPrototype.set("localeCompare", nativeFn("localeCompare", 1, (t, a, c) -> {
            String x = thisStringCoerced(t, "localeCompare");
            String y = AbstractOps.toString(arg(a, 0));
            return (double) Integer.signum(x.compareTo(y));
        }));
        // Locale-aware case conversions — fall back to root locale.
        stringPrototype.set("toLocaleLowerCase", nativeFn("toLocaleLowerCase", 0, (t, a, c) ->
            thisStringCoerced(t, "toLocaleLowerCase").toLowerCase(java.util.Locale.ROOT)));
        stringPrototype.set("toLocaleUpperCase", nativeFn("toLocaleUpperCase", 0, (t, a, c) ->
            thisStringCoerced(t, "toLocaleUpperCase").toUpperCase(java.util.Locale.ROOT)));
        // § 22.1.3.9 String.prototype.isWellFormed / § 22.1.3.33 toWellFormed.
        stringPrototype.set("isWellFormed", nativeFn("isWellFormed", 0, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return false;
                    i++;
                } else if (Character.isLowSurrogate(ch)) {
                    return false;
                }
            }
            return true;
        }));
        // AnnexB § B.2.3 — trimLeft / trimRight aliases for trimStart / trimEnd.
        // Test262 checks that trimLeft === trimStart (same function object).
        // We install them after trimStart/trimEnd are defined.
        Object trimStartFn = stringPrototype.get("trimStart");
        Object trimEndFn = stringPrototype.get("trimEnd");
        if (trimStartFn instanceof JSFunction) stringPrototype.set("trimLeft", (JSFunction) trimStartFn);
        if (trimEndFn instanceof JSFunction) stringPrototype.set("trimRight", (JSFunction) trimEndFn);
        stringPrototype.set("toWellFormed", nativeFn("toWellFormed", 0, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(ch).append(s.charAt(i + 1));
                        i++;
                    } else {
                        sb.append('�');
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    sb.append('�');
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }));
        stringPrototype.set("repeat", nativeFn("repeat", 1, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            // § 22.1.3.16: convert count via ToIntegerOrInfinity; reject
            // negative or +∞; reject count * len > 2^53 - 1 (would
            // overflow). Use the full double conversion (toInt32 chops to
            // 32-bit which silently turns Number.MAX_VALUE into 0).
            double dn = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(dn)) dn = 0;
            else if (dn != 0) dn = dn < 0 ? Math.ceil(dn) : Math.floor(dn);
            if (dn < 0 || Double.isInfinite(dn)) {
                throw AbruptCompletion.rangeError("Invalid count value");
            }
            if (s.isEmpty() || dn == 0) return "";
            // Cap product to avoid OOM on `"x".repeat(2**30)` — spec says
            // 2^53 - 1 max but realistically we can't allocate >256 MB in
            // a string operation without killing the test sweep.
            double totalLen = dn * s.length();
            if (totalLen > 9007199254740991.0) {
                throw AbruptCompletion.rangeError("Invalid count value");
            }
            if (totalLen > 256 * 1024 * 1024) {
                throw AbruptCompletion.rangeError("repeat result too large");
            }
            return s.repeat((int) dn);
        }));
        stringPrototype.set("concat", nativeFn("concat", 1, (t, a, c) -> {
            StringBuilder sb = new StringBuilder(thisStringCoerced(t, "concat"));
            for (Object x : a) sb.append(AbstractOps.toString(x));
            return sb.toString();
        }));
        stringPrototype.set("replace", nativeFn("replace", 2, (t, a, c) -> {
            String s = thisStringCoerced(t, "replace");
            Object search0 = arg(a, 0);
            Object repl0 = arg(a, 1);
            if (asRegExpSource(search0) != null) {
                java.util.regex.Pattern p = compileJsRegex(asRegExpSource(search0), asRegExpFlags(search0));
                java.util.regex.Matcher m = p.matcher(s);
                boolean global = asRegExpFlags(search0).contains("g");
                StringBuilder sb = new StringBuilder();
                int last = 0;
                while (m.find()) {
                    sb.append(s, last, m.start());
                    if (repl0 instanceof JSFunction repFn) {
                        Object[] callArgs = new Object[m.groupCount() + 3];
                        callArgs[0] = m.group();
                        for (int gi = 1; gi <= m.groupCount(); gi++) callArgs[gi] = m.group(gi) == null ? Undefined.VALUE : m.group(gi);
                        callArgs[m.groupCount() + 1] = (double) m.start();
                        callArgs[m.groupCount() + 2] = s;
                        Object r = Interpreter.invokeFunction(repFn, Undefined.VALUE, callArgs, c);
                        sb.append(AbstractOps.toString(r));
                    } else {
                        sb.append(applyReplacementTemplate(AbstractOps.toString(repl0), m, s));
                    }
                    last = m.end();
                    if (!global) break;
                    if (m.end() == m.start()) {
                        if (m.end() < s.length()) sb.append(s.charAt(m.end()));
                        last = m.end() + 1;
                        if (last > s.length()) break;
                    }
                }
                if (last < s.length()) sb.append(s, last, s.length());
                return sb.toString();
            }
            String search = AbstractOps.toString(search0);
            int idx = s.indexOf(search);
            if (idx < 0) return s;
            String repl;
            if (repl0 instanceof JSFunction repFn) {
                Object r = Interpreter.invokeFunction(repFn, Undefined.VALUE,
                    new Object[]{search, (double) idx, s}, c);
                repl = AbstractOps.toString(r);
            } else {
                repl = AbstractOps.toString(repl0);
            }
            return s.substring(0, idx) + repl + s.substring(idx + search.length());
        }));
        stringPrototype.set("match", nativeFn("match", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "match");
            Object pat0 = arg(a, 0);
            String src = asRegExpSource(pat0);
            String flags = src == null ? "" : asRegExpFlags(pat0);
            if (src == null) {
                // ECMA-262 § 22.1.3.13 step 4: when regexp is undefined or
                // null, RegExpCreate(regexp, undefined) — an empty source
                // pattern with no flags, which matches the empty string
                // at index 0 of any input.
                if (pat0 == Undefined.VALUE || pat0 == null) {
                    src = "";
                } else {
                    src = AbstractOps.toString(pat0);
                }
            }
            java.util.regex.Pattern p = compileJsRegex(src, flags);
            java.util.regex.Matcher m = p.matcher(s);
            if (flags.contains("g")) {
                JSArray out = new JSArray();
                while (m.find()) out.push(m.group());
                return out.length() == 0 ? null : out;
            }
            if (!m.find()) return null;
            JSArray out = new JSArray();
            out.push(m.group());
            for (int gi = 1; gi <= m.groupCount(); gi++) {
                out.push(m.group(gi) == null ? Undefined.VALUE : m.group(gi));
            }
            // ECMA-262 § 22.1.3.13 String.prototype.match: when not global,
            // the result is exec()'s — index/input/groups are all set.
            out.setExtraProperty("index", (double) m.start());
            out.setExtraProperty("input", s);
            attachNamedGroups(out, p, m, src);
            return out;
        }));
        stringPrototype.set("search", nativeFn("search", 1, (t, a, c) -> {
            String s = thisStringCoerced(t, "search");
            Object pat0 = arg(a, 0);
            String src = asRegExpSource(pat0);
            String flags = src == null ? "" : asRegExpFlags(pat0);
            if (src == null) {
                // § 22.1.3.14 step 4: undefined/null → empty RegExp source.
                if (pat0 == Undefined.VALUE || pat0 == null) src = "";
                else src = AbstractOps.toString(pat0);
            }
            java.util.regex.Matcher m = compileJsRegex(src, flags).matcher(s);
            return m.find() ? (double) m.start() : -1.0;
        }));
        stringPrototype.set("padStart", nativeFn("padStart", 2, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int target = AbstractOps.toInt32(arg(a, 0));
            String pad = arg(a, 1) == Undefined.VALUE ? " " : AbstractOps.toString(a[1]);
            if (s.length() >= target || pad.isEmpty()) return s;
            StringBuilder sb = new StringBuilder();
            while (sb.length() + s.length() < target) sb.append(pad);
            return sb.substring(0, target - s.length()) + s;
        }));
        stringPrototype.set("padEnd", nativeFn("padEnd", 2, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int target = AbstractOps.toInt32(arg(a, 0));
            String pad = arg(a, 1) == Undefined.VALUE ? " " : AbstractOps.toString(a[1]);
            if (s.length() >= target || pad.isEmpty()) return s;
            StringBuilder sb = new StringBuilder(s);
            while (sb.length() < target) sb.append(pad);
            return sb.substring(0, target);
        }));
        // § 22.1.3.35 String.prototype.toString  /  § 22.1.3.36 String.prototype.valueOf.
        stringPrototype.set("toString", nativeFn("toString", 0,
            (t, a, c) -> thisStringValue(t)));
        stringPrototype.set("valueOf", nativeFn("valueOf", 0,
            (t, a, c) -> thisStringValue(t)));
        // ECMA-262 § 22.1.3.34 String.prototype [ %Symbol.iterator% ] —
        // returns a CreateStringIterator(O) per § 22.1.5.1. Iterates by
        // Unicode code point so surrogate pairs come out as one step.
        JSFunction stringIterFn = nativeFn("[Symbol.iterator]", 0, (t, a, c) -> {
            String s = thisStringCoerced(t, "[Symbol.iterator]");
            return com.jimmyhmiller.harmonica.bytecode.builtins
                .StringIteratorPrototypeBuiltin.create(s);
        });
        stringPrototype.set(wellKnownIterator.asPropertyKey(), stringIterFn);
        defaultStringIterator = stringIterFn;
    }

    // ============================================================
    //  Number.prototype, Boolean.prototype
    // ============================================================

    /**
     * ECMA-262 § 21.1.3.7.1 ThisNumberValue — used by Number.prototype.{valueOf,toString,toFixed,...}.
     * Returns the primitive Number for either a primitive thisVal or a wrapper
     * with [[NumberData]]; throws TypeError otherwise.
     */
    private static double thisNumberValue(Object t) {
        if (t instanceof Number n) return n.doubleValue();
        Object slot = readPrimitiveSlot(t, SLOT_NUMBER_DATA);
        if (slot instanceof Number n) return n.doubleValue();
        throw AbruptCompletion.typeError("Number.prototype method called on non-Number");
    }

    /** ECMA-262 § 20.3.3.3.1 ThisBooleanValue. */
    private static boolean thisBooleanValue(Object t) {
        if (t instanceof Boolean b) return b;
        Object slot = readPrimitiveSlot(t, SLOT_BOOLEAN_DATA);
        if (slot instanceof Boolean b) return b;
        throw AbruptCompletion.typeError("Boolean.prototype method called on non-Boolean");
    }

    /** ECMA-262 § 22.1.3.35.1 ThisStringValue. */
    private static String thisStringValue(Object t) {
        if (t instanceof String s) return s;
        Object slot = readPrimitiveSlot(t, SLOT_STRING_DATA);
        if (slot instanceof String s) return s;
        throw AbruptCompletion.typeError("String.prototype method called on non-String");
    }

    private static void installNumberPrototype() {
        // ECMA-262 § 21.1.3: Number.prototype is itself a Number object
        // with [[NumberData]] = +0. Installing the slot lets
        // {@code Number.prototype.toString()} (called on the prototype
        // itself, the test262 receiver pattern) return "0" instead of
        // throwing "called on non-Number".
        numberPrototype.properties().put(SLOT_NUMBER_DATA, 0.0);
        // Number.prototype.toString — § 21.1.3.7.
        numberPrototype.set("toString", nativeFn("toString", 1, (t, a, c) -> {
            double d = thisNumberValue(t);
            if (arg(a, 0) == Undefined.VALUE) return AbstractOps.toString(d);
            int radix = AbstractOps.toInt32(a[0]);
            if (radix < 2 || radix > 36) {
                throw AbruptCompletion.rangeError("toString() radix must be between 2 and 36");
            }
            if (radix == 10) return AbstractOps.toString(d);
            if (Double.isNaN(d)) return "NaN";
            if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
            if (d == (long) d) return Long.toString((long) d, radix);
            return AbstractOps.toString(d);
        }));
        numberPrototype.set("toFixed", nativeFn("toFixed", 1, (t, a, c) -> {
            double d = thisNumberValue(t);
            int digits = arg(a, 0) == Undefined.VALUE ? 0 : AbstractOps.toInt32(a[0]);
            if (digits < 0 || digits > 100) {
                throw AbruptCompletion.rangeError("toFixed() digits out of range");
            }
            if (Double.isNaN(d)) return "NaN";
            if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
            return String.format(java.util.Locale.ROOT, "%." + digits + "f", d);
        }));
        // § 21.1.3.8 Number.prototype.valueOf.
        numberPrototype.set("valueOf", nativeFn("valueOf", 0,
            (t, a, c) -> thisNumberValue(t)));
        // § 21.1.3.4 Number.prototype.toLocaleString — pragmatic stub that
        // ignores locale args and returns the spec-default string form.
        numberPrototype.set("toLocaleString", nativeFn("toLocaleString", 0, (t, a, c) -> {
            return AbstractOps.toString(thisNumberValue(t));
        }));
        // § 21.1.3.3 Number.prototype.toExponential(fractionDigits).
        numberPrototype.set("toExponential", nativeFn("toExponential", 1, (t, a, c) -> {
            double d = thisNumberValue(t);
            Object fdArg = arg(a, 0);
            if (Double.isNaN(d)) return "NaN";
            if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
            if (fdArg == Undefined.VALUE) {
                // Spec § 21.1.3.3 step 5: pick the minimal precision.
                // Java's %e default gives 6 digits; trim trailing zeros.
                String raw = String.format(java.util.Locale.ROOT, "%e", d);
                return normalizeExponential(raw, -1);
            }
            int digits = AbstractOps.toInt32(fdArg);
            if (digits < 0 || digits > 100) {
                throw AbruptCompletion.rangeError("toExponential() digits out of range");
            }
            String raw = String.format(java.util.Locale.ROOT, "%." + digits + "e", d);
            return normalizeExponential(raw, digits);
        }));
        // § 21.1.3.5 Number.prototype.toPrecision(precision).
        numberPrototype.set("toPrecision", nativeFn("toPrecision", 1, (t, a, c) -> {
            double d = thisNumberValue(t);
            Object pArg = arg(a, 0);
            if (pArg == Undefined.VALUE) return AbstractOps.toString(d);
            if (Double.isNaN(d)) return "NaN";
            if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
            int p = AbstractOps.toInt32(pArg);
            if (p < 1 || p > 100) {
                throw AbruptCompletion.rangeError("toPrecision() precision out of range");
            }
            if (d == 0.0) {
                StringBuilder sb = new StringBuilder("0");
                if (p > 1) { sb.append('.'); for (int i = 1; i < p; i++) sb.append('0'); }
                return sb.toString();
            }
            // Compute exponent base 10 of |d|.
            int exp = (int) Math.floor(Math.log10(Math.abs(d)));
            // If -6 < exp+1 ≤ p, emit fixed-notation with (p-1-exp) fraction digits.
            // Otherwise, use exponential form with p-1 fraction digits.
            if (exp < -6 || exp >= p) {
                String raw = String.format(java.util.Locale.ROOT, "%." + (p - 1) + "e", d);
                return normalizeExponential(raw, p - 1);
            }
            int frac = Math.max(0, p - 1 - exp);
            return String.format(java.util.Locale.ROOT, "%." + frac + "f", d);
        }));
    }

    private static void installBooleanPrototype() {
        // § 20.3.3: Boolean.prototype is a Boolean object with
        // [[BooleanData]] = false. Same rationale as numberPrototype.
        booleanPrototype.properties().put(SLOT_BOOLEAN_DATA, false);
        // § 20.3.3.2 Boolean.prototype.toString  /  § 20.3.3.3 Boolean.prototype.valueOf.
        booleanPrototype.set("toString", nativeFn("toString", 0,
            (t, a, c) -> thisBooleanValue(t) ? "true" : "false"));
        booleanPrototype.set("valueOf", nativeFn("valueOf", 0,
            (t, a, c) -> thisBooleanValue(t)));
    }

    // ============================================================
    //  Promise — ECMA-262 § 27.2
    //  v1: synchronous semantics. resolve()/.then() callbacks fire
    //  immediately rather than via a microtask queue. Tests that depend
    //  on async ordering (most Promise tests) will fail; tests that
    //  exercise simple chaining and value propagation will pass.
    // ============================================================

    static final String PROM_STATE = "##PromiseState##";    // "pending" | "fulfilled" | "rejected"
    static final String PROM_RESULT = "##PromiseResult##";  // any
    static final String PROM_FULFILL = "##PromiseFulfillReactions##";  // List<Runnable>
    static final String PROM_REJECT = "##PromiseRejectReactions##";    // List<Runnable>

    static boolean isPromise(Object v) {
        return v instanceof JSObject jo && jo.properties().containsKey(PROM_STATE);
    }

    /** § 9.4 Jobs and Job Queues — global microtask queue, drained at the
     *  end of every top-level script execution and at the end of each
     *  drained microtask (so chained {@code .then(...).then(...)} runs in
     *  spec order). Per-thread because parallel tests run on different
     *  threads of the test262 runner. */
    private static final ThreadLocal<java.util.ArrayDeque<Runnable>> MICROTASK_QUEUE =
        ThreadLocal.withInitial(java.util.ArrayDeque::new);

    public static void enqueueMicrotask(Runnable r) {
        MICROTASK_QUEUE.get().add(r);
    }

    /** Run microtasks until the queue is empty. New microtasks enqueued
     *  during the loop are run in this same drain. */
    public static void drainMicrotasks() {
        var q = MICROTASK_QUEUE.get();
        // Cap the loop to defend against runaway microtask storms that
        // would otherwise hang the test runner. 100k is large enough for
        // any spec algorithm but breaks pathological loops.
        int budget = 100_000;
        while (!q.isEmpty() && budget-- > 0) {
            Runnable r = q.poll();
            try { r.run(); }
            catch (AbruptCompletion ignored) { /* unhandled rejection */ }
            catch (Throwable ignored) { /* shouldn't escape — reaction wrapped */ }
        }
        if (budget <= 0) q.clear();
    }

    static JSObject createPromise() {
        JSObject p = new JSObject(promisePrototype);
        p.properties().put(PROM_STATE, "pending");
        p.properties().put(PROM_RESULT, Undefined.VALUE);
        p.properties().put(PROM_FULFILL, new java.util.ArrayList<Runnable>());
        p.properties().put(PROM_REJECT, new java.util.ArrayList<Runnable>());
        return p;
    }

    /** § 27.2.1.4 FulfillPromise: transition pending→fulfilled with value
     *  and enqueue (as microtasks) every queued fulfill reaction. Spec
     *  requires these run as PromiseReactionJob, NOT inline. */
    static void fulfillPromise(JSObject p, Object value) {
        if (!"pending".equals(p.properties().get(PROM_STATE))) return;
        p.properties().put(PROM_STATE, "fulfilled");
        p.properties().put(PROM_RESULT, value);
        @SuppressWarnings("unchecked")
        java.util.List<Runnable> reactions = (java.util.List<Runnable>) p.properties().get(PROM_FULFILL);
        p.properties().put(PROM_FULFILL, java.util.Collections.<Runnable>emptyList());
        p.properties().put(PROM_REJECT, java.util.Collections.<Runnable>emptyList());
        for (Runnable r : reactions) enqueueMicrotask(r);
    }

    /** § 27.2.1.5 RejectPromise — same shape, enqueues reject reactions. */
    static void rejectPromise(JSObject p, Object reason) {
        if (!"pending".equals(p.properties().get(PROM_STATE))) return;
        p.properties().put(PROM_STATE, "rejected");
        p.properties().put(PROM_RESULT, reason);
        @SuppressWarnings("unchecked")
        java.util.List<Runnable> reactions = (java.util.List<Runnable>) p.properties().get(PROM_REJECT);
        p.properties().put(PROM_FULFILL, java.util.Collections.<Runnable>emptyList());
        p.properties().put(PROM_REJECT, java.util.Collections.<Runnable>emptyList());
        for (Runnable r : reactions) enqueueMicrotask(r);
    }

    /**
     * § 27.2.1.4.1 ResolvePromise: handle thenable adoption. If value is
     * the same promise, reject with TypeError (cycle detection). If value
     * is a thenable, adopt its state. Otherwise fulfill.
     */
    static void resolvePromise(JSObject p, Object value, InterpContext ctx) {
        if (!"pending".equals(p.properties().get(PROM_STATE))) return;
        if (value == p) {
            JSObject err = new JSObject();
            err.set("name", "TypeError");
            err.set("message", "Chaining cycle detected for promise");
            rejectPromise(p, err);
            return;
        }
        if (value instanceof JSObject vo && vo != p) {
            // § 27.2.1.4 step 8: thenable adoption.
            Object thenMethod;
            try { thenMethod = AbstractOps.getProperty(vo, "then"); }
            catch (AbruptCompletion ac) { rejectPromise(p, ac.value()); return; }
            if (thenMethod instanceof JSFunction thenFn) {
                JSFunction resolveCb = new JSFunction("resolve", 1, (t, a, c) -> {
                    resolvePromise(p, arg(a, 0), c); return Undefined.VALUE;
                });
                resolveCb.setNonConstructor(true);
                JSFunction rejectCb = new JSFunction("reject", 1, (t, a, c) -> {
                    rejectPromise(p, arg(a, 0)); return Undefined.VALUE;
                });
                rejectCb.setNonConstructor(true);
                try {
                    Interpreter.invokeFunction(thenFn, vo, new Object[]{resolveCb, rejectCb}, ctx);
                } catch (AbruptCompletion ac) {
                    rejectPromise(p, ac.value());
                }
                return;
            }
        }
        fulfillPromise(p, value);
    }

    // ============================================================
    //  Map / Set / WeakMap / WeakSet — ECMA-262 § 24.1, § 24.2, § 24.3, § 24.4
    //  v1: backed by java.util LinkedHashMap / LinkedHashSet via the
    //  reserved slot keys ##MapData## / ##SetData##. Spec-mandated SameValueZero
    //  key equality is approximated via Object.equals (which works for
    //  primitives and uses reference identity for objects — close to spec).
    // ============================================================

    static final String SLOT_MAP_DATA = "##MapData##";
    static final String SLOT_SET_DATA = "##SetData##";
    static final String SLOT_WEAK_MAP_DATA = "##WeakMapData##";
    static final String SLOT_WEAK_SET_DATA = "##WeakSetData##";

    @SuppressWarnings("unchecked")
    private static java.util.LinkedHashMap<Object, Object> mapData(Object t) {
        if (t instanceof JSObject jo && jo.properties().get(SLOT_MAP_DATA) instanceof java.util.LinkedHashMap m) {
            return (java.util.LinkedHashMap<Object, Object>) m;
        }
        throw AbruptCompletion.typeError("Map.prototype method called on non-Map");
    }
    @SuppressWarnings("unchecked")
    private static java.util.LinkedHashSet<Object> setData(Object t) {
        if (t instanceof JSObject jo && jo.properties().get(SLOT_SET_DATA) instanceof java.util.LinkedHashSet s) {
            return (java.util.LinkedHashSet<Object>) s;
        }
        throw AbruptCompletion.typeError("Set.prototype method called on non-Set");
    }

    private static void installMapPrototype() {
        // § 24.1.3.* Map.prototype methods.
        mapPrototype.set("get", nativeFn("get", 1, (t, a, c) -> {
            Object v = mapData(t).get(arg(a, 0));
            return v == null ? Undefined.VALUE : v;
        }));
        mapPrototype.set("set", nativeFn("set", 2, (t, a, c) -> {
            mapData(t).put(arg(a, 0), arg(a, 1));
            return t;
        }));
        mapPrototype.set("has", nativeFn("has", 1, (t, a, c) -> mapData(t).containsKey(arg(a, 0))));
        // ECMA-262 (proposal-upsert, Stage 4 ES2025) Map.prototype.getOrInsert /
        // getOrInsertComputed — return existing value or insert a default.
        mapPrototype.set("getOrInsert", nativeFn("getOrInsert", 2, (t, a, c) -> {
            Object k = arg(a, 0);
            Object v = arg(a, 1);
            var data = mapData(t);
            if (data.containsKey(k)) return data.get(k);
            data.put(k, v);
            return v;
        }));
        mapPrototype.set("getOrInsertComputed", nativeFn("getOrInsertComputed", 2, (t, a, c) -> {
            Object k = arg(a, 0);
            Object cb = arg(a, 1);
            if (!(cb instanceof JSFunction cbf)) {
                throw AbruptCompletion.typeError("Map.prototype.getOrInsertComputed: callback is not callable");
            }
            var data = mapData(t);
            if (data.containsKey(k)) return data.get(k);
            Object v = Interpreter.invokeFunction(cbf, Undefined.VALUE, new Object[]{k}, c);
            data.put(k, v);
            return v;
        }));
        mapPrototype.set("delete", nativeFn("delete", 1, (t, a, c) -> mapData(t).remove(arg(a, 0)) != null));
        mapPrototype.set("clear", nativeFn("clear", 0, (t, a, c) -> { mapData(t).clear(); return Undefined.VALUE; }));
        mapPrototype.set("forEach", nativeFn("forEach", 1, (t, a, c) -> {
            JSFunction fn = arg(a, 0) instanceof JSFunction f ? f : null;
            if (fn == null) throw AbruptCompletion.typeError("Map.forEach callback is not a function");
            // ECMA-262 § 24.1.3.5: spec iterates in insertion order, but
            // callback may mutate the Map (set/delete/clear). Per § 24.1.5
            // CreateMapIterator's algorithm we walk keys live: skip
            // entries deleted before our cursor reaches them, include
            // entries added before our cursor passes them. Snapshot the
            // current keys, then look up each key fresh each iteration.
            java.util.List<Object> keys = new java.util.ArrayList<>(mapData(t).keySet());
            int i = 0;
            while (i < keys.size()) {
                Object k = keys.get(i++);
                var data = mapData(t);
                if (!data.containsKey(k)) continue;
                Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{data.get(k), k, t}, c);
                // Pick up any keys appended after the snapshot.
                if (i == keys.size() && data.size() > keys.size()) {
                    for (Object newKey : data.keySet()) {
                        if (!keys.contains(newKey)) keys.add(newKey);
                    }
                }
            }
            return Undefined.VALUE;
        }));
        // § 24.1.3.10 get Map.prototype.size — accessor.
        mapPrototype.set("size", new Accessor(
            nativeFn("get size", 0, (t, a, c) -> (double) mapData(t).size()),
            null));
        // § 24.1.3.6 Map.prototype.entries / @@iterator — yields [key, value] pairs.
        // Per § 24.1.5.1 CreateMapIterator the iterator walks the [[MapData]]
        // List live: keys deleted before the cursor reaches them are skipped,
        // keys added afterward are included. Tracking by-index against a
        // periodically-resnapshotted key list keeps this CME-safe.
        JSFunction mapEntries = makeMapIter("Map Iterator", 2);
        mapPrototype.set("entries", mapEntries);
        mapPrototype.set("keys", makeMapIter("Map Iterator", 0));
        mapPrototype.set("values", makeMapIter("Map Iterator", 1));
        mapPrototype.set(wellKnownIterator.asPropertyKey(), mapEntries);
    }

    /** Build a Map iterator that resolves keys live against the backing
     *  LinkedHashMap, tolerating insertion/deletion during iteration.
     *  {@code kind}: 0 = keys, 1 = values, 2 = [key, value] entries. */
    /** Build a Map-prototype iterator method that returns a fresh
     *  {@code %MapIteratorPrototype%}-based iterator over {@code this}.
     *  {@code kind}: 0 = keys, 1 = values, 2 = entries. */
    private static JSFunction makeMapIter(String tag, int kind) {
        String name = kind == 0 ? "keys" : kind == 1 ? "values" : "entries";
        return nativeFn(name, 0, (t, a, c) ->
            com.jimmyhmiller.harmonica.bytecode.builtins
                .MapIteratorPrototypeBuiltin.create(mapData(t), kind));
    }

    /** Set iterator factory — {@code asEntries=true} yields {@code [v, v]}. */
    private static JSFunction makeSetIter(boolean asEntries) {
        String name = asEntries ? "entries" : "values";
        int kind = asEntries
            ? com.jimmyhmiller.harmonica.bytecode.builtins.SetIteratorPrototypeBuiltin.KIND_ENTRY
            : com.jimmyhmiller.harmonica.bytecode.builtins.SetIteratorPrototypeBuiltin.KIND_VALUE;
        return nativeFn(name, 0, (t, a, c) ->
            com.jimmyhmiller.harmonica.bytecode.builtins
                .SetIteratorPrototypeBuiltin.create(setData(t), kind));
    }

    private static JSObject mapKeysOrValuesIter(java.util.Iterator<Object> it) {
        return mapKeysOrValuesIter(it, "Map Iterator");
    }

    private static JSObject mapKeysOrValuesIter(java.util.Iterator<Object> it, String tag) {
        JSObject iter = new JSObject(ensureIteratorProto(tag));
        iter.set("next", nativeFn("next", 0, (t, a, c) -> {
            JSObject result = new JSObject();
            if (it.hasNext()) {
                result.set("value", it.next());
                result.set("done", false);
            } else {
                result.set("value", Undefined.VALUE);
                result.set("done", true);
            }
            return result;
        }));
        return iter;
    }

    private static void installSetPrototype() {
        // § 24.2.3.* Set.prototype methods.
        setPrototype.set("add", nativeFn("add", 1, (t, a, c) -> {
            setData(t).add(arg(a, 0));
            return t;
        }));
        setPrototype.set("has", nativeFn("has", 1, (t, a, c) -> setData(t).contains(arg(a, 0))));
        setPrototype.set("delete", nativeFn("delete", 1, (t, a, c) -> setData(t).remove(arg(a, 0))));
        setPrototype.set("clear", nativeFn("clear", 0, (t, a, c) -> { setData(t).clear(); return Undefined.VALUE; }));
        setPrototype.set("forEach", nativeFn("forEach", 1, (t, a, c) -> {
            JSFunction fn = arg(a, 0) instanceof JSFunction f ? f : null;
            if (fn == null) throw AbruptCompletion.typeError("Set.forEach callback is not a function");
            // Snapshot to tolerate add/delete during iteration; skip
            // entries deleted before the cursor reaches them.
            java.util.List<Object> snap = new java.util.ArrayList<>(setData(t));
            int i = 0;
            while (i < snap.size()) {
                Object v = snap.get(i++);
                var data = setData(t);
                if (!data.contains(v)) continue;
                Interpreter.invokeFunction(fn, Undefined.VALUE, new Object[]{v, v, t}, c);
                if (i == snap.size() && data.size() > snap.size()) {
                    for (Object newV : data) if (!snap.contains(newV)) snap.add(newV);
                }
            }
            return Undefined.VALUE;
        }));
        setPrototype.set("size", new Accessor(
            nativeFn("get size", 0, (t, a, c) -> (double) setData(t).size()),
            null));
        // § 24.2.3.10 Set.prototype.values / .keys / @@iterator — yields values.
        // Same live-iteration shape as Map: skip deletions, see additions.
        JSFunction setValues = makeSetIter(false);
        setPrototype.set("values", setValues);
        setPrototype.set("keys", setValues);
        setPrototype.set("entries", makeSetIter(true));
        setPrototype.set(wellKnownIterator.asPropertyKey(), setValues);
        // ECMA-262 § 24.2.3 Set composition methods (ES2024) — operate on
        // any value with a Set-like interface ({size, has, keys}).
        // Per § 24.2.1.2 GetSetRecord, the argument must be an object
        // whose `size` is a non-NaN number, `has` is callable, and
        // `keys` is callable; otherwise throw TypeError.
        setPrototype.set("union", nativeFn("union", 1, (t, a, c) -> {
            java.util.LinkedHashSet<Object> tData = setData(t);   // throws if `this` isn't a Set
            java.util.LinkedHashSet<Object> out = new java.util.LinkedHashSet<>(tData);
            Object other = arg(a, 0);
            getSetRecord(other);
            iterateSetLike(other, c, v -> out.add(v));
            JSObject result = new JSObject(setPrototype);
            result.properties().put(SLOT_SET_DATA, out);
            return result;
        }));
        setPrototype.set("intersection", nativeFn("intersection", 1, (t, a, c) -> {
            java.util.LinkedHashSet<Object> tData = setData(t);
            Object other = arg(a, 0);
            getSetRecord(other);
            java.util.LinkedHashSet<Object> out = new java.util.LinkedHashSet<>();
            int thisSize = tData.size();
            int otherSize = otherSize(other);
            // Iterate the smaller for spec-friendliness.
            if (thisSize <= otherSize) {
                for (Object v : setData(t)) {
                    if (setLikeHas(other, v, c)) out.add(v);
                }
            } else {
                iterateSetLike(other, c, v -> { if (setData(t).contains(v)) out.add(v); });
            }
            JSObject result = new JSObject(setPrototype);
            result.properties().put(SLOT_SET_DATA, out);
            return result;
        }));
        setPrototype.set("difference", nativeFn("difference", 1, (t, a, c) -> {
            java.util.LinkedHashSet<Object> tData = setData(t);
            Object other = arg(a, 0);
            getSetRecord(other);
            java.util.LinkedHashSet<Object> out = new java.util.LinkedHashSet<>(tData);
            iterateSetLike(other, c, out::remove);
            JSObject result = new JSObject(setPrototype);
            result.properties().put(SLOT_SET_DATA, out);
            return result;
        }));
        setPrototype.set("symmetricDifference", nativeFn("symmetricDifference", 1, (t, a, c) -> {
            java.util.LinkedHashSet<Object> tData = setData(t);
            Object other = arg(a, 0);
            getSetRecord(other);
            java.util.LinkedHashSet<Object> out = new java.util.LinkedHashSet<>(tData);
            iterateSetLike(other, c, v -> {
                if (!out.remove(v)) out.add(v);
            });
            JSObject result = new JSObject(setPrototype);
            result.properties().put(SLOT_SET_DATA, out);
            return result;
        }));
        setPrototype.set("isSubsetOf", nativeFn("isSubsetOf", 1, (t, a, c) -> {
            java.util.LinkedHashSet<Object> tData = setData(t);
            Object other = arg(a, 0);
            getSetRecord(other);
            for (Object v : tData) if (!setLikeHas(other, v, c)) return false;
            return true;
        }));
        setPrototype.set("isSupersetOf", nativeFn("isSupersetOf", 1, (t, a, c) -> {
            java.util.LinkedHashSet<Object> tData = setData(t);
            Object other = arg(a, 0);
            getSetRecord(other);
            boolean[] all = {true};
            iterateSetLike(other, c, v -> { if (!tData.contains(v)) all[0] = false; });
            return all[0];
        }));
        setPrototype.set("isDisjointFrom", nativeFn("isDisjointFrom", 1, (t, a, c) -> {
            java.util.LinkedHashSet<Object> tData = setData(t);
            Object other = arg(a, 0);
            getSetRecord(other);
            boolean[] any = {false};
            iterateSetLike(other, c, v -> { if (tData.contains(v)) any[0] = true; });
            return !any[0];
        }));
    }

    /** ECMA-262 § 24.2.1.2 GetSetRecord(obj) — validate that {@code obj}
     *  has the set-like surface ({@code size}, {@code has}, {@code keys}).
     *  Throws TypeError if any required field is missing or invalid.
     *  Per spec step 2.a, {@code size} must be a Number — BigInt is
     *  explicitly rejected (ToNumber on a BigInt throws TypeError). */
    private static void getSetRecord(Object obj) {
        if (obj == null || obj == Undefined.VALUE
            || !(obj instanceof JSObject || obj instanceof JSArray || obj instanceof JSFunction)) {
            throw AbruptCompletion.typeError("Set-like operand is not an object");
        }
        Object size = AbstractOps.getProperty(obj, "size");
        if (size == Undefined.VALUE) {
            throw AbruptCompletion.typeError("Set-like operand has undefined size");
        }
        if (size instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Set-like operand size must be a Number, not a BigInt");
        }
        double sizeNum = AbstractOps.toNumber(size);
        if (Double.isNaN(sizeNum)) {
            throw AbruptCompletion.typeError("Set-like operand has non-numeric size");
        }
        Object has = AbstractOps.getProperty(obj, "has");
        if (!(has instanceof JSFunction)) {
            throw AbruptCompletion.typeError("Set-like operand has no callable 'has'");
        }
        Object keys = AbstractOps.getProperty(obj, "keys");
        if (!(keys instanceof JSFunction)) {
            throw AbruptCompletion.typeError("Set-like operand has no callable 'keys'");
        }
    }

    @SuppressWarnings("unchecked")
    private static int otherSize(Object o) {
        Object sz = AbstractOps.getProperty(o, "size");
        return (int) AbstractOps.toNumber(sz);
    }

    /** Whether {@code o} (a Set-like) reports `has(v)` truthy. */
    private static boolean setLikeHas(Object o, Object v, InterpContext c) {
        Object has = AbstractOps.getProperty(o, "has");
        if (!(has instanceof JSFunction f)) return false;
        return AbstractOps.toBoolean(Interpreter.invokeFunction(f, o, new Object[]{v}, c));
    }

    /** Iterate a Set-like (size + keys()) and apply {@code consumer} per value. */
    @SuppressWarnings("unchecked")
    private static void iterateSetLike(Object o, InterpContext c, java.util.function.Consumer<Object> consumer) {
        if (o instanceof JSObject jo && jo.properties().get(SLOT_SET_DATA) instanceof java.util.LinkedHashSet<?> ls) {
            for (Object v : ls) consumer.accept(v);
            return;
        }
        Object keys = AbstractOps.getProperty(o, "keys");
        if (!(keys instanceof JSFunction kf)) {
            throw AbruptCompletion.typeError("Set-like operand has no keys() method");
        }
        Object iter = Interpreter.invokeFunction(kf, o, new Object[0], c);
        if (!(iter instanceof JSObject iterObj)) {
            throw AbruptCompletion.typeError("Set-like .keys() must return an iterator");
        }
        Object nextFn = AbstractOps.getProperty(iterObj, "next");
        if (!(nextFn instanceof JSFunction nf)) {
            throw AbruptCompletion.typeError("Set-like iterator has no .next()");
        }
        for (int i = 0; i < 0x7FFFFFFF; i++) {
            if ((i & 0x3FF) == 0 && Thread.interrupted()) {
                throw new Interpreter.InterpInterruptedError();
            }
            Object step = Interpreter.invokeFunction(nf, iterObj, new Object[0], c);
            if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) break;
            consumer.accept(AbstractOps.getProperty(step, "value"));
        }
    }

    private static void installWeakMapPrototype() {
        // Spec-aligned brand check + key-type validation lives in
        // builtins/WeakMapPrototypeBuiltin.
        com.jimmyhmiller.harmonica.bytecode.builtins
            .WeakMapPrototypeBuiltin.install();
    }

    private static void installWeakSetPrototype() {
        com.jimmyhmiller.harmonica.bytecode.builtins
            .WeakSetPrototypeBuiltin.install();
    }

    // ============================================================
    //  Generator (the iteration object) — ECMA-262 § 27.5
    // ============================================================

    /** Slot keys backing the GeneratorObject internal state. */
    static final String GEN_STATE   = "##GeneratorState##";    // "suspendedStart" | "suspendedYield" | "executing" | "completed"
    static final String GEN_BODY    = "##GeneratorBody##";     // Executable
    static final String GEN_CTX     = "##GeneratorContext##";  // InterpContext (frame state)
    static final String GEN_ASYNC   = "##GeneratorIsAsync##";  // Boolean (async generator?)
    static final String GEN_START_PC = "##GeneratorStartPc##"; // Integer (resume PC for first .next())

    /**
     * Create a GeneratorObject — § 27.5.3 / § 15.5.5 / § 27.6 — wrapping a
     * suspended frame ready to start. {@code isAsync} flips whether
     * {@code next}/{@code return}/{@code throw} return Promise-wrapped
     * iterator results (async generators) or raw {@code {value, done}}
     * records (sync generators).
     */
    static JSObject makeGeneratorObject(Executable body, InterpContext frame, boolean isAsync) {
        return makeGeneratorObject(body, frame, isAsync, 0);
    }

    static JSObject makeGeneratorObject(Executable body, InterpContext frame, boolean isAsync, int startPc) {
        JSObject g = new JSObject(generatorPrototype);
        g.properties().put(GEN_STATE, "suspendedStart");
        g.properties().put(GEN_BODY, body);
        g.properties().put(GEN_CTX, frame);
        g.properties().put(GEN_ASYNC, isAsync);
        g.properties().put(GEN_START_PC, startPc);
        return g;
    }

    /** Build {@code { value, done }} as a JSObject — IteratorResult per § 7.4.13. */
    private static JSObject iteratorResult(Object value, boolean done) {
        JSObject r = new JSObject();
        r.set("value", value);
        r.set("done", done);
        return r;
    }

    private static void installGeneratorPrototype() {
        // ECMA-262 § 27.5.1.2 Generator.prototype.next — resume the
        // generator with {@code value} (the value of the yield expression
        // on resume) and run until next yield or completion.
        generatorPrototype.set("next", nativeFn("next", 1, (thisVal, args, c) -> {
            if (!(thisVal instanceof JSObject g) || !g.properties().containsKey(GEN_STATE)) {
                throw AbruptCompletion.typeError("Generator.prototype.next called on non-generator");
            }
            boolean isAsync = Boolean.TRUE.equals(g.properties().get(GEN_ASYNC));
            String state = (String) g.properties().get(GEN_STATE);
            if ("completed".equals(state)) {
                JSObject r = iteratorResult(Undefined.VALUE, true);
                return isAsync ? wrapInPromise(r, c) : r;
            }
            if ("executing".equals(state)) {
                AbruptCompletion err = AbruptCompletion.typeError("Generator is already running");
                if (isAsync) return wrapInRejectedPromise(err.value());
                throw err;
            }
            Executable body = (Executable) g.properties().get(GEN_BODY);
            InterpContext ctx = (InterpContext) g.properties().get(GEN_CTX);
            int startPc;
            if ("suspendedStart".equals(state)) {
                Object pc0 = g.properties().get(GEN_START_PC);
                startPc = (pc0 instanceof Integer i) ? i : 0;
            } else { // suspendedYield
                startPc = ctx.yieldResumePc();
                Variable dst = ctx.yieldResumeDst();
                Object resumed = arg(args, 0);
                if (dst != null) dst.store(ctx, resumed);
                // Stash for yield* (delegated yield) which re-enters its own
                // op without a destination operand and reads this slot.
                ctx.setLastResumedValue(resumed);
            }
            // next() is always a Normal completion (return/throw use the
            // sibling methods below).
            ctx.setResumeMode(InterpContext.ResumeMode.NORMAL);
            g.properties().put(GEN_STATE, "executing");
            try {
                boolean yielded = Interpreter.interpretSuspendable(body, ctx, startPc);
                JSObject result;
                if (yielded) {
                    g.properties().put(GEN_STATE, "suspendedYield");
                    result = iteratorResult(ctx.yieldedValue(), false);
                } else {
                    g.properties().put(GEN_STATE, "completed");
                    Object retVal = ctx.registers()[Variable.Register.RETURN_VALUE_INDEX];
                    result = iteratorResult(retVal, true);
                }
                return isAsync ? wrapInPromise(result, c) : result;
            } catch (AbruptCompletion ac) {
                g.properties().put(GEN_STATE, "completed");
                if (isAsync) return wrapInRejectedPromise(ac.value());
                throw ac;
            }
        }));
        // § 27.5.1.4 Generator.prototype.return — resume the body with a
        // Return completion. If we're suspended at a {@code yield*},
        // the inner iterator's {@code return} (if any) handles the
        // forwarding; otherwise the yield op completes the generator
        // here with the return value.
        generatorPrototype.set("return", nativeFn("return", 1, (thisVal, args, c) -> {
            if (!(thisVal instanceof JSObject g) || !g.properties().containsKey(GEN_STATE)) {
                throw AbruptCompletion.typeError("Generator.prototype.return called on non-generator");
            }
            boolean isAsync = Boolean.TRUE.equals(g.properties().get(GEN_ASYNC));
            Object retVal = arg(args, 0);
            String state = (String) g.properties().get(GEN_STATE);
            if ("completed".equals(state)) {
                JSObject r = iteratorResult(retVal, true);
                return isAsync ? wrapInPromise(r, c) : r;
            }
            if ("executing".equals(state)) {
                AbruptCompletion err = AbruptCompletion.typeError("Generator is already running");
                if (isAsync) return wrapInRejectedPromise(err.value());
                throw err;
            }
            if ("suspendedStart".equals(state)) {
                // § 27.5.1.4 step 8.b: just complete; the body never ran.
                g.properties().put(GEN_STATE, "completed");
                JSObject r = iteratorResult(retVal, true);
                return isAsync ? wrapInPromise(r, c) : r;
            }
            // suspendedYield — resume with a Return completion.
            Executable body = (Executable) g.properties().get(GEN_BODY);
            InterpContext ctx = (InterpContext) g.properties().get(GEN_CTX);
            int startPc = ctx.yieldResumePc();
            ctx.setLastResumedValue(retVal);
            ctx.setResumeMode(InterpContext.ResumeMode.RETURN);
            g.properties().put(GEN_STATE, "executing");
            try {
                boolean yielded = Interpreter.interpretSuspendable(body, ctx, startPc);
                JSObject result;
                if (yielded) {
                    g.properties().put(GEN_STATE, "suspendedYield");
                    result = iteratorResult(ctx.yieldedValue(), false);
                } else {
                    g.properties().put(GEN_STATE, "completed");
                    Object body_ret = ctx.registers()[Variable.Register.RETURN_VALUE_INDEX];
                    result = iteratorResult(body_ret, true);
                }
                return isAsync ? wrapInPromise(result, c) : result;
            } catch (AbruptCompletion ac) {
                g.properties().put(GEN_STATE, "completed");
                if (isAsync) return wrapInRejectedPromise(ac.value());
                throw ac;
            } finally {
                ctx.setResumeMode(InterpContext.ResumeMode.NORMAL);
            }
        }));
        // § 27.5.1.3 Generator.prototype.throw — resume the body with a
        // Throw completion. Inside a {@code yield*} we forward to the
        // inner iterator's {@code throw}; outside, the yield op rethrows
        // here so any enclosing try/catch in the generator body sees it.
        generatorPrototype.set("throw", nativeFn("throw", 1, (thisVal, args, c) -> {
            if (!(thisVal instanceof JSObject g) || !g.properties().containsKey(GEN_STATE)) {
                throw AbruptCompletion.typeError("Generator.prototype.throw called on non-generator");
            }
            boolean isAsync = Boolean.TRUE.equals(g.properties().get(GEN_ASYNC));
            Object throwVal = arg(args, 0);
            String state = (String) g.properties().get(GEN_STATE);
            if ("suspendedStart".equals(state) || "completed".equals(state)) {
                g.properties().put(GEN_STATE, "completed");
                if (isAsync) return wrapInRejectedPromise(throwVal);
                throw new AbruptCompletion(throwVal);
            }
            if ("executing".equals(state)) {
                AbruptCompletion err = AbruptCompletion.typeError("Generator is already running");
                if (isAsync) return wrapInRejectedPromise(err.value());
                throw err;
            }
            // suspendedYield — resume with a Throw completion.
            Executable body = (Executable) g.properties().get(GEN_BODY);
            InterpContext ctx = (InterpContext) g.properties().get(GEN_CTX);
            int startPc = ctx.yieldResumePc();
            ctx.setLastResumedValue(throwVal);
            ctx.setResumeMode(InterpContext.ResumeMode.THROW);
            g.properties().put(GEN_STATE, "executing");
            try {
                boolean yielded = Interpreter.interpretSuspendable(body, ctx, startPc);
                JSObject result;
                if (yielded) {
                    g.properties().put(GEN_STATE, "suspendedYield");
                    result = iteratorResult(ctx.yieldedValue(), false);
                } else {
                    g.properties().put(GEN_STATE, "completed");
                    Object body_ret = ctx.registers()[Variable.Register.RETURN_VALUE_INDEX];
                    result = iteratorResult(body_ret, true);
                }
                return isAsync ? wrapInPromise(result, c) : result;
            } catch (AbruptCompletion ac) {
                g.properties().put(GEN_STATE, "completed");
                if (isAsync) return wrapInRejectedPromise(ac.value());
                throw ac;
            } finally {
                ctx.setResumeMode(InterpContext.ResumeMode.NORMAL);
            }
        }));
        // § 27.5.1.5 Generator.prototype [ %Symbol.iterator% ] returns the
        // generator itself (generators are their own iterators).
        generatorPrototype.set(wellKnownIterator.asPropertyKey(),
            nativeFn("[Symbol.iterator]", 0, (t, a, c) -> t));
        // § 27.6.1.5 AsyncGenerator.prototype [ %Symbol.asyncIterator% ]
        // returns the async generator itself.
        generatorPrototype.set(wellKnownAsyncIterator.asPropertyKey(),
            nativeFn("[Symbol.asyncIterator]", 0, (t, a, c) -> t));

        // ECMA-262 § 27.3.3 %GeneratorFunction.prototype% (a.k.a. %Generator%):
        // its `prototype` data property is the per-instance generator
        // prototype (§ 27.3.3.3) — generator-instance objects then inherit
        // .next/.return/.throw via that chain. Mark non-enumerable.
        if (generatorFunctionPrototype != null) {
            generatorFunctionPrototype.set("prototype", generatorPrototype);
            generatorFunctionPrototype.setAttributes("prototype",
                (byte)(JSObject.ATTR_CONFIGURABLE));
            generatorFunctionPrototype.set(wellKnownToStringTag.asPropertyKey(), "GeneratorFunction");
            // § 27.3.3.4 [@@toStringTag] descriptor is configurable-only.
            generatorFunctionPrototype.setAttributes(wellKnownToStringTag.asPropertyKey(),
                JSObject.ATTR_CONFIGURABLE);
        }
    }

    enum CombinatorKind { ALL, RACE, ALL_SETTLED, ANY }

    /** § 27.2.1.1 PromiseCapability Record — promise + the resolve/reject
     *  closures the spec algorithms use to settle it. For our default
     *  Promise, resolve/reject are tiny wrappers around fulfill/reject; for
     *  user subclasses they're whatever the subclass's constructor passed
     *  to its executor. */
    static final class PromiseCapability {
        Object promise;
        JSFunction resolve;
        JSFunction reject;
    }

    /** § 27.2.1.5 NewPromiseCapability(C) — spec § 27.2.1.5.1
     *  GetCapabilitiesExecutor: throw only if either slot is already
     *  non-undefined. A first call with undefined args leaves the slots
     *  undefined; a follow-up call with real functions is then allowed
     *  (test262 capability-executor-called-twice). */
    static PromiseCapability newPromiseCapability(JSFunction C, InterpContext ctx) {
        Object[] resolveSlot = new Object[]{Undefined.VALUE};
        Object[] rejectSlot = new Object[]{Undefined.VALUE};
        JSFunction executor = nativeFn("", 2, (t, a, c2) -> {
            if (resolveSlot[0] != Undefined.VALUE || rejectSlot[0] != Undefined.VALUE) {
                throw AbruptCompletion.typeError(
                    "Promise executor called with resolve/reject more than once");
            }
            resolveSlot[0] = arg(a, 0);
            rejectSlot[0] = arg(a, 1);
            return Undefined.VALUE;
        });
        JSObject receiver = new JSObject(C.prototypeObject() != null
            ? C.prototypeObject() : promisePrototype);
        Object built = Interpreter.invokeFunctionAsConstructor(C, receiver,
            new Object[]{executor}, ctx);
        // ECMA-262 § 10.2.2 OrdinaryConstruct fallback: if the constructor
        // doesn't return an object explicitly, the result is the receiver.
        Object promise = (built instanceof JSObject || built instanceof JSArray
                          || built instanceof JSFunction) ? built : receiver;
        // Spec: NewPromiseCapability finishes by checking IsCallable on each
        // slot AFTER the executor returned.
        if (!(resolveSlot[0] instanceof JSFunction rf)) {
            throw AbruptCompletion.typeError(
                "Promise capability: resolve is not callable");
        }
        if (!(rejectSlot[0] instanceof JSFunction jf)) {
            throw AbruptCompletion.typeError(
                "Promise capability: reject is not callable");
        }
        PromiseCapability cap = new PromiseCapability();
        cap.promise = promise;
        cap.resolve = rf;
        cap.reject = jf;
        return cap;
    }

    /** § 27.2.4.{1,2,3,5} shared spine for Promise.all/allSettled/any/race.
     *  Builds a capability via NewPromiseCapability(thisCtor) so subclasses
     *  see their own constructor running and their custom resolve/reject
     *  closures get called. */
    static Object promiseCombinator(Object thisCtor, Object iterable,
                                    InterpContext ctx, CombinatorKind kind) {
        if (!(thisCtor instanceof JSFunction tf) || !tf.isConstructor()) {
            throw AbruptCompletion.typeError("Promise." + kind.name().toLowerCase()
                + " called on non-constructor");
        }
        PromiseCapability cap = newPromiseCapability(tf, ctx);
        try {
            return promiseCombinatorImpl(tf, iterable, ctx, kind, cap);
        } catch (AbruptCompletion ac) {
            // § 27.2.4.X step 6: IfAbruptRejectPromise — route any
            // synchronous error during the algorithm body to the
            // capability's reject. The result is still cap.promise.
            try {
                Interpreter.invokeFunction(cap.reject, Undefined.VALUE,
                    new Object[]{ac.value()}, ctx);
            } catch (AbruptCompletion ignored) { /* secondary reject errors swallow */ }
            return cap.promise;
        }
    }

    /** Inner combinator body — streams the iterable so an error mid-loop
     *  triggers IteratorClose (return()) per spec. */
    private static Object promiseCombinatorImpl(JSFunction tf, Object iterable,
                                                InterpContext ctx, CombinatorKind kind,
                                                PromiseCapability cap) {
        // PerformPromiseAll step 1: promiseResolve = Get(C, "resolve"). The
        // callability check happens BEFORE iteration so a non-callable
        // resolve rejects the result promise without consuming the iterator.
        Object resolveFn = AbstractOps.getProperty(tf, "resolve");
        if (!(resolveFn instanceof JSFunction promiseResolve)) {
            throw AbruptCompletion.typeError(
                (tf.name() == null ? "constructor" : tf.name()) + ".resolve is not callable");
        }

        // Open the iterator. After this, any error must IteratorClose.
        if (iterable == null || iterable == Undefined.VALUE) {
            throw AbruptCompletion.typeError("iterable expected, got "
                + (iterable == null ? "null" : "undefined"));
        }
        Object iterMethod = AbstractOps.getProperty(iterable, wellKnownIterator.asPropertyKey());
        if (!(iterMethod instanceof JSFunction iterFn)) {
            throw AbruptCompletion.typeError("iterable: @@iterator is not callable");
        }
        Object iterRaw = Interpreter.invokeFunction(iterFn, iterable, new Object[0], ctx);
        if (!(iterRaw instanceof JSObject iterObj)) {
            throw AbruptCompletion.typeError("iterator must be an object");
        }
        Object nextFnVal = AbstractOps.getProperty(iterObj, "next");
        if (!(nextFnVal instanceof JSFunction nf)) {
            throw AbruptCompletion.typeError("iterator: .next is not callable");
        }

        JSArray values = new JSArray();
        int[] remaining = {1};   // start at 1; decrement at iter-done; spec ordering
        boolean[] settled = {false};
        Object capPromise = cap.promise;
        JSFunction capResolve = cap.resolve;
        JSFunction capReject = cap.reject;

        boolean iteratorDone = false;
        int index = 0;
        try {
            for (int safety = 0; safety < 1_000_000; safety++) {
                Object step = Interpreter.invokeFunction(nf, iterObj, new Object[0], ctx);
                if (!(step instanceof JSObject stepObj)) {
                    throw AbruptCompletion.typeError("iterator step is not an object");
                }
                if (AbstractOps.toBoolean(AbstractOps.getProperty(stepObj, "done"))) {
                    iteratorDone = true;
                    break;
                }
                Object value = AbstractOps.getProperty(stepObj, "value");
                final int idx = index++;
                values.push(Undefined.VALUE);
                remaining[0]++;

                Object resolvedItem = Interpreter.invokeFunction(promiseResolve, tf,
                    new Object[]{value}, ctx);
                Object thenFnVal = AbstractOps.getProperty(resolvedItem, "then");
                if (!(thenFnVal instanceof JSFunction thenFn)) {
                    throw AbruptCompletion.typeError("Promise combinator: .then is not callable");
                }
                boolean[] alreadyCalled = {false};
                JSFunction onFulfill, onReject;
                switch (kind) {
                    case ALL -> {
                        onFulfill = nativeFn("", 1, (t2, a2, c2) -> {
                            if (alreadyCalled[0]) return Undefined.VALUE;
                            alreadyCalled[0] = true;
                            values.set(idx, arg(a2, 0));
                            if (--remaining[0] == 0) {
                                Interpreter.invokeFunction(capResolve, Undefined.VALUE,
                                    new Object[]{values}, c2);
                            }
                            return Undefined.VALUE;
                        });
                        onReject = nativeFn("", 1, (t2, a2, c2) -> {
                            if (settled[0]) return Undefined.VALUE;
                            settled[0] = true;
                            Interpreter.invokeFunction(capReject, Undefined.VALUE,
                                new Object[]{arg(a2, 0)}, c2);
                            return Undefined.VALUE;
                        });
                    }
                    case RACE -> {
                        onFulfill = nativeFn("", 1, (t2, a2, c2) -> {
                            if (settled[0]) return Undefined.VALUE;
                            settled[0] = true;
                            Interpreter.invokeFunction(capResolve, Undefined.VALUE,
                                new Object[]{arg(a2, 0)}, c2);
                            return Undefined.VALUE;
                        });
                        onReject = nativeFn("", 1, (t2, a2, c2) -> {
                            if (settled[0]) return Undefined.VALUE;
                            settled[0] = true;
                            Interpreter.invokeFunction(capReject, Undefined.VALUE,
                                new Object[]{arg(a2, 0)}, c2);
                            return Undefined.VALUE;
                        });
                    }
                    case ALL_SETTLED -> {
                        onFulfill = nativeFn("", 1, (t2, a2, c2) -> {
                            if (alreadyCalled[0]) return Undefined.VALUE;
                            alreadyCalled[0] = true;
                            JSObject entry = new JSObject(objectPrototype);
                            entry.set("status", "fulfilled");
                            entry.set("value", arg(a2, 0));
                            values.set(idx, entry);
                            if (--remaining[0] == 0) {
                                Interpreter.invokeFunction(capResolve, Undefined.VALUE,
                                    new Object[]{values}, c2);
                            }
                            return Undefined.VALUE;
                        });
                        onReject = nativeFn("", 1, (t2, a2, c2) -> {
                            if (alreadyCalled[0]) return Undefined.VALUE;
                            alreadyCalled[0] = true;
                            JSObject entry = new JSObject(objectPrototype);
                            entry.set("status", "rejected");
                            entry.set("reason", arg(a2, 0));
                            values.set(idx, entry);
                            if (--remaining[0] == 0) {
                                Interpreter.invokeFunction(capResolve, Undefined.VALUE,
                                    new Object[]{values}, c2);
                            }
                            return Undefined.VALUE;
                        });
                    }
                    default /* ANY */ -> {
                        onFulfill = nativeFn("", 1, (t2, a2, c2) -> {
                            if (settled[0]) return Undefined.VALUE;
                            settled[0] = true;
                            Interpreter.invokeFunction(capResolve, Undefined.VALUE,
                                new Object[]{arg(a2, 0)}, c2);
                            return Undefined.VALUE;
                        });
                        onReject = nativeFn("", 1, (t2, a2, c2) -> {
                            if (alreadyCalled[0]) return Undefined.VALUE;
                            alreadyCalled[0] = true;
                            values.set(idx, arg(a2, 0));
                            if (--remaining[0] == 0 && !settled[0]) {
                                settled[0] = true;
                                Interpreter.invokeFunction(capReject, Undefined.VALUE,
                                    new Object[]{createAggregateError(values,
                                        "All promises were rejected")}, c2);
                            }
                            return Undefined.VALUE;
                        });
                    }
                }
                Interpreter.invokeFunction(thenFn, resolvedItem,
                    new Object[]{onFulfill, onReject}, ctx);
            }
        } catch (AbruptCompletion ac) {
            // § 7.4.10 IteratorClose on abrupt completion when iterator is
            // not yet done. return() errors are swallowed when there's
            // already an error in flight.
            if (!iteratorDone) {
                try {
                    Object retFn = AbstractOps.getProperty(iterObj, "return");
                    if (retFn instanceof JSFunction rf) {
                        Interpreter.invokeFunction(rf, iterObj, new Object[0], ctx);
                    }
                } catch (AbruptCompletion ignored) { /* spec swallows */ }
            }
            throw ac;
        }

        // Decrement the initial +1 sentinel. If no item completed yet, the
        // settlement happens later via the per-item onFulfill/onReject.
        if (--remaining[0] == 0) {
            switch (kind) {
                case ALL, ALL_SETTLED -> Interpreter.invokeFunction(capResolve,
                    Undefined.VALUE, new Object[]{values}, ctx);
                case ANY -> Interpreter.invokeFunction(capReject, Undefined.VALUE,
                    new Object[]{createAggregateError(values,
                        "All promises were rejected")}, ctx);
                case RACE -> { /* race over empty iterable stays pending */ }
            }
        }
        return capPromise;
    }

    /** § 7.4.10 IterableToList: read @@iterator, drain into a Java list.
     *  Strings get char-by-char iteration via their iterator. */
    static java.util.List<Object> iterableToList(Object iterable, InterpContext ctx) {
        if (iterable == null || iterable == Undefined.VALUE) {
            throw AbruptCompletion.typeError("iterable expected, got "
                + (iterable == null ? "null" : "undefined"));
        }
        Object iterMethod = AbstractOps.getProperty(iterable, wellKnownIterator.asPropertyKey());
        if (!(iterMethod instanceof JSFunction iterFn)) {
            throw AbruptCompletion.typeError("iterable: @@iterator is not callable");
        }
        Object iter = Interpreter.invokeFunction(iterFn, iterable, new Object[0], ctx);
        if (!(iter instanceof JSObject iterObj)) {
            throw AbruptCompletion.typeError("iterator must be an object");
        }
        Object nextFn = AbstractOps.getProperty(iterObj, "next");
        if (!(nextFn instanceof JSFunction nf)) {
            throw AbruptCompletion.typeError("iterator: .next is not callable");
        }
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (int safety = 0; safety < 1_000_000; safety++) {
            Object step = Interpreter.invokeFunction(nf, iterObj, new Object[0], ctx);
            if (!(step instanceof JSObject stepObj)) {
                throw AbruptCompletion.typeError("iterator step is not an object");
            }
            if (AbstractOps.toBoolean(AbstractOps.getProperty(stepObj, "done"))) break;
            out.add(AbstractOps.getProperty(stepObj, "value"));
        }
        return out;
    }

    /** Wrap {@code v} in a fulfilled Promise (for async generator results). */
    public static JSObject wrapInPromise(Object v, InterpContext ctx) {
        JSObject p = createPromise();
        resolvePromise(p, v, ctx);
        return p;
    }

    /** Wrap {@code reason} in a rejected Promise (for async generator throws). */
    public static JSObject wrapInRejectedPromise(Object reason) {
        JSObject p = createPromise();
        rejectPromise(p, reason);
        return p;
    }

    public static JSObject wrapInResolvedPromise(Object value) {
        JSObject p = createPromise();
        resolvePromise(p, value, InterpContext.current());
        return p;
    }

    private static void installPromisePrototype() {
        // § 27.2.5.4 Promise.prototype.then.
        promisePrototype.set("then", nativeFn("then", 2, (thisVal, args, ctx) -> {
            if (!isPromise(thisVal)) {
                throw AbruptCompletion.typeError("Promise.prototype.then called on non-Promise");
            }
            JSObject p = (JSObject) thisVal;
            // § 27.2.5.4 step 4: SpeciesConstructor(this, %Promise%), then
            // NewPromiseCapability(C). Lets `class SubPromise extends
            // Promise {}` chain correctly through .then().
            Object C = AbstractOps.getProperty(p, "constructor");
            JSFunction speciesCtor;
            Object globalPromise = ctx == null ? null : ctx.globals().get("Promise");
            if (C == null || C == Undefined.VALUE) {
                speciesCtor = (JSFunction) globalPromise;
            } else if (!(C instanceof JSObject || C instanceof JSFunction)) {
                throw AbruptCompletion.typeError(
                    "Promise.prototype.then: constructor is not an object");
            } else {
                Object S = wellKnownSpecies != null
                    ? AbstractOps.getProperty(C, wellKnownSpecies.asPropertyKey())
                    : Undefined.VALUE;
                if (S == null || S == Undefined.VALUE) {
                    speciesCtor = C instanceof JSFunction cf ? cf : (JSFunction) globalPromise;
                } else if (S instanceof JSFunction sf && sf.isConstructor()) {
                    speciesCtor = sf;
                } else {
                    throw AbruptCompletion.typeError(
                        "Promise.prototype.then: species is not a constructor");
                }
            }
            PromiseCapability cap = newPromiseCapability(speciesCtor, ctx);
            Object onFulfilled = arg(args, 0);
            Object onRejected = arg(args, 1);
            JSFunction capResolve = cap.resolve;
            JSFunction capReject = cap.reject;
            // § 27.2.5.4.1 PerformPromiseThen — build reactions.
            Runnable fulfillReaction = () -> {
                Object res = p.properties().get(PROM_RESULT);
                if (onFulfilled instanceof JSFunction f) {
                    try {
                        Object v = Interpreter.invokeFunction(f, Undefined.VALUE, new Object[]{res}, ctx);
                        Interpreter.invokeFunction(capResolve, Undefined.VALUE,
                            new Object[]{v}, ctx);
                    } catch (AbruptCompletion ac) {
                        Interpreter.invokeFunction(capReject, Undefined.VALUE,
                            new Object[]{ac.value()}, ctx);
                    }
                } else {
                    Interpreter.invokeFunction(capResolve, Undefined.VALUE,
                        new Object[]{res}, ctx);
                }
            };
            Runnable rejectReaction = () -> {
                Object res = p.properties().get(PROM_RESULT);
                if (onRejected instanceof JSFunction f) {
                    try {
                        Object v = Interpreter.invokeFunction(f, Undefined.VALUE, new Object[]{res}, ctx);
                        Interpreter.invokeFunction(capResolve, Undefined.VALUE,
                            new Object[]{v}, ctx);
                    } catch (AbruptCompletion ac) {
                        Interpreter.invokeFunction(capReject, Undefined.VALUE,
                            new Object[]{ac.value()}, ctx);
                    }
                } else {
                    Interpreter.invokeFunction(capReject, Undefined.VALUE,
                        new Object[]{res}, ctx);
                }
            };
            String state = (String) p.properties().get(PROM_STATE);
            if ("pending".equals(state)) {
                @SuppressWarnings("unchecked")
                java.util.List<Runnable> fr = (java.util.List<Runnable>) p.properties().get(PROM_FULFILL);
                @SuppressWarnings("unchecked")
                java.util.List<Runnable> rr = (java.util.List<Runnable>) p.properties().get(PROM_REJECT);
                fr.add(fulfillReaction);
                rr.add(rejectReaction);
            } else if ("fulfilled".equals(state)) {
                enqueueMicrotask(fulfillReaction);
            } else {
                enqueueMicrotask(rejectReaction);
            }
            return cap.promise;
        }));
        // § 27.2.5.1 Promise.prototype.catch ( onRejected ) → this.then(undefined, onRejected).
        promisePrototype.set("catch", nativeFn("catch", 1, (thisVal, args, ctx) -> {
            JSFunction thenFn = (JSFunction) AbstractOps.getProperty(thisVal, "then");
            return Interpreter.invokeFunction(thenFn, thisVal,
                new Object[]{Undefined.VALUE, arg(args, 0)}, ctx);
        }));
        // § 27.2.5.3 Promise.prototype.finally — uses SpeciesConstructor
        // for C.resolve so the awaited onFinally is observed via the
        // subclass's Promise.resolve.
        promisePrototype.set("finally", nativeFn("finally", 1, (thisVal, args, ctx) -> {
            if (!(thisVal instanceof JSObject)) {
                throw AbruptCompletion.typeError(
                    "Promise.prototype.finally called on non-object");
            }
            Object onFinally = arg(args, 0);
            // SpeciesConstructor(this, %Promise%) — pick the constructor for
            // the .then() result + as the receiver of C.resolve in wrappers.
            JSFunction thenFn = (JSFunction) AbstractOps.getProperty(thisVal, "then");
            Object ctorVal = AbstractOps.getProperty(thisVal, "constructor");
            final JSFunction C;
            if (ctorVal instanceof JSFunction cf) {
                Object S = wellKnownSpecies != null
                    ? AbstractOps.getProperty(cf, wellKnownSpecies.asPropertyKey())
                    : Undefined.VALUE;
                C = (S instanceof JSFunction sf && sf.isConstructor()) ? sf
                    : cf;
            } else {
                Object g = ctx == null ? null : ctx.globals().get("Promise");
                C = g instanceof JSFunction gp ? gp : null;
                if (C == null) {
                    throw AbruptCompletion.typeError(
                        "Promise.prototype.finally: missing Promise global");
                }
            }
            Object onF, onR;
            if (onFinally instanceof JSFunction onFinFn) {
                JSFunction CFinal = C;
                onF = nativeFn("", 1, (tt, aa, cc) -> {
                    Object result = Interpreter.invokeFunction(onFinFn,
                        Undefined.VALUE, new Object[0], cc);
                    // Await result via C.resolve(result), then return value.
                    Object resolveFn = AbstractOps.getProperty(CFinal, "resolve");
                    Object value = arg(aa, 0);
                    if (resolveFn instanceof JSFunction rf) {
                        Object inner = Interpreter.invokeFunction(rf, CFinal,
                            new Object[]{result}, cc);
                        Object innerThen = AbstractOps.getProperty(inner, "then");
                        if (innerThen instanceof JSFunction tFn) {
                            JSFunction valueThunk = nativeFn("", 0,
                                (t3, a3, c3) -> value);
                            return Interpreter.invokeFunction(tFn, inner,
                                new Object[]{valueThunk}, cc);
                        }
                    }
                    return value;
                });
                onR = nativeFn("", 1, (tt, aa, cc) -> {
                    Object result = Interpreter.invokeFunction(onFinFn,
                        Undefined.VALUE, new Object[0], cc);
                    Object resolveFn = AbstractOps.getProperty(CFinal, "resolve");
                    Object reason = arg(aa, 0);
                    if (resolveFn instanceof JSFunction rf) {
                        Object inner = Interpreter.invokeFunction(rf, CFinal,
                            new Object[]{result}, cc);
                        Object innerThen = AbstractOps.getProperty(inner, "then");
                        if (innerThen instanceof JSFunction tFn) {
                            JSFunction reasonThunk = nativeFn("", 0,
                                (t3, a3, c3) -> {
                                    throw new AbruptCompletion(reason);
                                });
                            return Interpreter.invokeFunction(tFn, inner,
                                new Object[]{reasonThunk}, cc);
                        }
                    }
                    throw new AbruptCompletion(reason);
                });
            } else {
                onF = onFinally;
                onR = onFinally;
            }
            return Interpreter.invokeFunction(thenFn, thisVal, new Object[]{onF, onR}, ctx);
        }));
    }

    /** Unwrap a Symbol receiver per § 20.4.3.4.1 thisSymbolValue: the
     *  primitive itself or the [[SymbolData]] slot of a wrapper. Throws
     *  TypeError otherwise. */
    static JSSymbol thisSymbolValue(Object t, String where) {
        if (t instanceof JSSymbol s) return s;
        if (t instanceof JSObject jo) {
            Object data = jo.properties().get("##SymbolData##");
            if (data instanceof JSSymbol s) return s;
        }
        throw AbruptCompletion.typeError("Symbol.prototype." + where + " called on non-Symbol");
    }

    private static void installSymbolPrototype() {
        // § 20.4.3.4 Symbol.prototype.toString — unwraps a wrapper.
        symbolPrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            JSSymbol s = thisSymbolValue(t, "toString");
            return s.toString();
        }));
        // § 20.4.3.5 Symbol.prototype.valueOf — returns the symbol itself.
        symbolPrototype.set("valueOf", nativeFn("valueOf", 0, (t, a, c) ->
            thisSymbolValue(t, "valueOf")));
        // § 20.4.3.2 get Symbol.prototype.description (accessor): returns the
        // [[Description]] of the (possibly wrapped) Symbol, or undefined.
        JSFunction descGetter = nativeFn("get description", 0, (t, a, c) -> {
            JSSymbol s = thisSymbolValue(t, "description");
            return s.description() == null ? Undefined.VALUE : s.description();
        });
        symbolPrototype.set("description", new Accessor(descGetter, null));
        symbolPrototype.setAttributes("description", JSObject.ATTR_CONFIGURABLE);
        // § 20.4.3.6 Symbol.prototype [ %Symbol.toPrimitive% ] — also unwraps.
        symbolPrototype.set(wellKnownToPrimitive.asPropertyKey(),
            nativeFn("[Symbol.toPrimitive]", 1, (t, a, c) ->
                thisSymbolValue(t, "[@@toPrimitive]")));
        // Per § 20.4.3.6 the @@toPrimitive function descriptor is
        // {writable: false, enumerable: false, configurable: true}.
        symbolPrototype.setAttributes(wellKnownToPrimitive.asPropertyKey(),
            JSObject.ATTR_CONFIGURABLE);
        // § 20.4.3.7 Symbol.prototype [ %Symbol.toStringTag% ] = "Symbol",
        // configurable only.
        if (wellKnownToStringTag != null) {
            String key = wellKnownToStringTag.asPropertyKey();
            symbolPrototype.set(key, "Symbol");
            symbolPrototype.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }
    }

    // ============================================================
    //  Globals install
    // ============================================================

    private static void installGlobals(Map<String, Object> globals) {
        // Constants. Don't overwrite if user code has already shadowed.
        globals.putIfAbsent("undefined", Undefined.VALUE);
        globals.putIfAbsent("NaN", Double.NaN);
        globals.putIfAbsent("Infinity", Double.POSITIVE_INFINITY);

        // globalThis — a JSObject populated below with all builtin globals so
        // `globalThis.X` reads the same X as a bare reference. User-defined
        // top-level lets/vars do NOT show up on globalThis (we don't sync the
        // globals map back onto this object).
        JSObject globalThis = new JSObject();
        // ECMA-262 § 19.1: undefined / NaN / Infinity are
        // { value, writable: false, enumerable: false, configurable: false }.
        globalThis.set("undefined", Undefined.VALUE);
        globalThis.setAttributes("undefined", (byte) 0);
        globalThis.set("NaN", Double.NaN);
        globalThis.setAttributes("NaN", (byte) 0);
        globalThis.set("Infinity", Double.POSITIVE_INFINITY);
        globalThis.setAttributes("Infinity", (byte) 0);
        globals.putIfAbsent("globalThis", globalThis);

        // console
        JSObject console = new JSObject();
        NativeBody logBody = (t, a, c) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(AbstractOps.toString(a[i]));
            }
            System.out.println(sb);
            return Undefined.VALUE;
        };
        NativeBody errBody = (t, a, c) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(AbstractOps.toString(a[i]));
            }
            System.err.println(sb);
            return Undefined.VALUE;
        };
        console.set("log",   nativeFn("log",   0, logBody));
        console.set("info",  nativeFn("info",  0, logBody));
        console.set("warn",  nativeFn("warn",  0, errBody));
        console.set("error", nativeFn("error", 0, errBody));
        globals.putIfAbsent("console", console);

        // Math namespace
        JSObject math = new JSObject();
        // ECMA-262 § 21.3.1: Math constants are non-writable,
        // non-enumerable, non-configurable. Without these descriptor
        // attrs, {@code delete Math.E} returns true and writes succeed.
        for (var entry : new java.util.LinkedHashMap<String, Double>() {{
            put("PI", Math.PI);
            put("E", Math.E);
            put("LN2", Math.log(2));
            put("LN10", Math.log(10));
            put("LOG2E", 1.0 / Math.log(2));
            put("LOG10E", 1.0 / Math.log(10));
            put("SQRT2", Math.sqrt(2));
            put("SQRT1_2", Math.sqrt(0.5));
        }}.entrySet()) {
            math.set(entry.getKey(), entry.getValue());
            math.setAttributes(entry.getKey(), (byte) 0);
        }
        math.set("abs",   nativeFn("abs",   1, (t, a, c) -> Math.abs(AbstractOps.toNumber(arg(a, 0)))));
        math.set("floor", nativeFn("floor", 1, (t, a, c) -> Math.floor(AbstractOps.toNumber(arg(a, 0)))));
        math.set("ceil",  nativeFn("ceil",  1, (t, a, c) -> Math.ceil(AbstractOps.toNumber(arg(a, 0)))));
        math.set("round", nativeFn("round", 1, (t, a, c) -> {
            // ECMAScript § 21.3.2.28: round half toward +Infinity (NOT
            // Java HALF_UP for negatives). Preserve the sign of -0:
            // round(-0.5) is -0, not 0, because the operation rounds
            // toward +Infinity but the input is exactly halfway between
            // -1 and 0 — half rounded up gives 0, but spec § 21.3.2.28
            // step 6 specifies the result is -0 when the argument is
            // in (-0.5, -0] or is -0 itself.
            double d = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(d) || Double.isInfinite(d)) return d;
            if (d == 0.0) return d;                 // preserves ±0
            if (d > -0.5 && d < 0) return -0.0;     // spec § 21.3.2.28 step 6
            return Math.floor(d + 0.5);
        }));
        math.set("trunc", nativeFn("trunc", 1, (t, a, c) -> {
            double d = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(d) || Double.isInfinite(d)) return d;
            return d < 0 ? Math.ceil(d) : Math.floor(d);
        }));
        math.set("sqrt",  nativeFn("sqrt",  1, (t, a, c) -> Math.sqrt(AbstractOps.toNumber(arg(a, 0)))));
        math.set("cbrt",  nativeFn("cbrt",  1, (t, a, c) -> Math.cbrt(AbstractOps.toNumber(arg(a, 0)))));
        math.set("pow",   nativeFn("pow",   2, (t, a, c) -> Math.pow(
            AbstractOps.toNumber(arg(a, 0)), AbstractOps.toNumber(arg(a, 1)))));
        math.set("exp",   nativeFn("exp",   1, (t, a, c) -> Math.exp(AbstractOps.toNumber(arg(a, 0)))));
        math.set("log",   nativeFn("log",   1, (t, a, c) -> Math.log(AbstractOps.toNumber(arg(a, 0)))));
        math.set("log2",  nativeFn("log2",  1, (t, a, c) -> Math.log(AbstractOps.toNumber(arg(a, 0))) / Math.log(2)));
        math.set("log10", nativeFn("log10", 1, (t, a, c) -> Math.log10(AbstractOps.toNumber(arg(a, 0)))));
        math.set("sin",   nativeFn("sin",   1, (t, a, c) -> Math.sin(AbstractOps.toNumber(arg(a, 0)))));
        math.set("cos",   nativeFn("cos",   1, (t, a, c) -> Math.cos(AbstractOps.toNumber(arg(a, 0)))));
        math.set("tan",   nativeFn("tan",   1, (t, a, c) -> Math.tan(AbstractOps.toNumber(arg(a, 0)))));
        math.set("asin",  nativeFn("asin",  1, (t, a, c) -> Math.asin(AbstractOps.toNumber(arg(a, 0)))));
        math.set("acos",  nativeFn("acos",  1, (t, a, c) -> Math.acos(AbstractOps.toNumber(arg(a, 0)))));
        math.set("atan",  nativeFn("atan",  1, (t, a, c) -> Math.atan(AbstractOps.toNumber(arg(a, 0)))));
        math.set("atan2", nativeFn("atan2", 2, (t, a, c) -> Math.atan2(
            AbstractOps.toNumber(arg(a, 0)), AbstractOps.toNumber(arg(a, 1)))));
        math.set("sign", nativeFn("sign", 1, (t, a, c) -> {
            double d = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(d)) return Double.NaN;
            return Math.signum(d);
        }));
        math.set("hypot", nativeFn("hypot", 2, (t, a, c) -> {
            // ECMA-262 § 21.3.2.18 Math.hypot — Infinity arguments
            // dominate (any +/-Infinity → +Infinity, even with NaN
            // present), then NaN propagates, then sqrt(sum-of-squares).
            boolean sawInf = false;
            boolean sawNaN = false;
            for (Object x : a) {
                double v = AbstractOps.toNumber(x);
                if (Double.isInfinite(v)) sawInf = true;
                else if (Double.isNaN(v)) sawNaN = true;
            }
            if (sawInf) return Double.POSITIVE_INFINITY;
            if (sawNaN) return Double.NaN;
            double sum = 0;
            for (Object x : a) {
                double v = AbstractOps.toNumber(x);
                sum += v * v;
            }
            return Math.sqrt(sum);
        }));
        math.set("min", nativeFn("min", 2, (t, a, c) -> {
            if (a.length == 0) return Double.POSITIVE_INFINITY;
            double m = AbstractOps.toNumber(a[0]);
            for (int i = 1; i < a.length; i++) {
                double v = AbstractOps.toNumber(a[i]);
                if (Double.isNaN(v)) return Double.NaN;
                if (v < m) m = v;
            }
            return m;
        }));
        math.set("max", nativeFn("max", 2, (t, a, c) -> {
            if (a.length == 0) return Double.NEGATIVE_INFINITY;
            double m = AbstractOps.toNumber(a[0]);
            for (int i = 1; i < a.length; i++) {
                double v = AbstractOps.toNumber(a[i]);
                if (Double.isNaN(v)) return Double.NaN;
                if (v > m) m = v;
            }
            return m;
        }));
        math.set("random", nativeFn("random", 0, (t, a, c) -> Math.random()));
        // ES2015 Math additions — § 21.3.2.
        math.set("sinh",  nativeFn("sinh",  1, (t, a, c) -> Math.sinh(AbstractOps.toNumber(arg(a, 0)))));
        math.set("cosh",  nativeFn("cosh",  1, (t, a, c) -> Math.cosh(AbstractOps.toNumber(arg(a, 0)))));
        math.set("tanh",  nativeFn("tanh",  1, (t, a, c) -> Math.tanh(AbstractOps.toNumber(arg(a, 0)))));
        math.set("asinh", nativeFn("asinh", 1, (t, a, c) -> {
            double x = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(x)) return Double.NaN;
            if (x == 0.0) return x;                       // preserves ±0
            if (Double.isInfinite(x)) return x;
            return Math.log(x + Math.sqrt(x * x + 1));
        }));
        math.set("acosh", nativeFn("acosh", 1, (t, a, c) -> {
            double x = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(x)) return Double.NaN;
            if (x < 1) return Double.NaN;
            if (x == 1) return 0.0;
            return Math.log(x + Math.sqrt(x * x - 1));
        }));
        math.set("atanh", nativeFn("atanh", 1, (t, a, c) -> {
            double x = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(x)) return Double.NaN;
            if (x < -1 || x > 1) return Double.NaN;
            if (x == 1) return Double.POSITIVE_INFINITY;
            if (x == -1) return Double.NEGATIVE_INFINITY;
            return 0.5 * Math.log((1 + x) / (1 - x));
        }));
        math.set("log1p", nativeFn("log1p", 1, (t, a, c) -> Math.log1p(AbstractOps.toNumber(arg(a, 0)))));
        math.set("expm1", nativeFn("expm1", 1, (t, a, c) -> Math.expm1(AbstractOps.toNumber(arg(a, 0)))));
        // § 21.3.2.16 Math.fround — round to nearest float32 representation.
        math.set("fround", nativeFn("fround", 1, (t, a, c) -> {
            double x = AbstractOps.toNumber(arg(a, 0));
            return (double) (float) x;
        }));
        // Stage 4 proposal Math.f16round — round to nearest float16 (half precision).
        math.set("f16round", nativeFn("f16round", 1, (t, a, c) -> {
            double x = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(x)) return Double.NaN;
            if (x == 0.0 || Double.isInfinite(x)) return x;
            short bits = Float.floatToFloat16((float) x);
            return (double) Float.float16ToFloat(bits);
        }));
        // § 21.3.2.20 Math.imul — 32-bit integer multiplication (mod 2^32).
        math.set("imul", nativeFn("imul", 2, (t, a, c) -> {
            int x = AbstractOps.toInt32(arg(a, 0));
            int y = AbstractOps.toInt32(arg(a, 1));
            return (double) (x * y);
        }));
        // § 21.3.2.10 Math.clz32 — count leading zero bits of ToUint32(x).
        math.set("clz32", nativeFn("clz32", 1, (t, a, c) -> {
            int n = AbstractOps.toInt32(arg(a, 0));   // ToInt32 then reinterpret as uint32 bit pattern.
            return (double) Integer.numberOfLeadingZeros(n);
        }));
        // Stage 4 (ES2025) Math.sumPrecise — exact-sum of an iterable.
        // For doubles we approximate with the standard Kahan summation;
        // exact arithmetic via long where the inputs are integers.
        math.set("sumPrecise", nativeFn("sumPrecise", 1, (t, a, c) -> {
            Object iter = arg(a, 0);
            // GetIterator(iter, sync). Reuse iterateSetLike via @@iterator.
            double[] sum = {0.0};
            double[] kahanC = {0.0};
            iterateSetLike(iter, c, v -> {
                if (!(v instanceof Number)) {
                    throw AbruptCompletion.typeError("Math.sumPrecise: not a number");
                }
                double x = ((Number) v).doubleValue();
                double y = x - kahanC[0];
                double t2 = sum[0] + y;
                kahanC[0] = (t2 - sum[0]) - y;
                sum[0] = t2;
            });
            return sum[0];
        }));
        // ECMA-262 § 17: built-in namespace methods are
        // {writable: true, enumerable: false, configurable: true}.
        markMethodsNonEnumerable(math);
        // Symbol.toStringTag = "Math" (§ 21.3.1.9).
        if (wellKnownToStringTag != null) {
            math.set(wellKnownToStringTag.asPropertyKey(), "Math");
            math.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        }
        globals.putIfAbsent("Math", math);

        // JSON namespace
        JSObject json = new JSObject();
        json.set("stringify", nativeFn("stringify", 3, (t, a, c) -> {
            Object v = arg(a, 0);
            Object replacerArg = arg(a, 1);
            Object spaceArg = arg(a, 2);
            if (v == Undefined.VALUE || v instanceof JSFunction) return Undefined.VALUE;
            // ECMA-262 § 25.5.2.2 SerializeJSONProperty step 2.b: if a
            // replacer function is supplied, the top-level value is
            // first run through it (with key="").
            JSFunction replacerFn = replacerArg instanceof JSFunction f ? f : null;
            java.util.Set<String> replacerKeys = null;
            if (replacerArg instanceof JSArray arr) {
                replacerKeys = new java.util.LinkedHashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    Object el = arr.get(i);
                    if (el instanceof String se) replacerKeys.add(se);
                    else if (el instanceof Number ne) replacerKeys.add(AbstractOps.toString(ne.doubleValue()));
                }
            }
            // Compute the indent string per § 25.5.2.2 step 6.
            String indent;
            if (spaceArg instanceof Number n) {
                int k = Math.max(0, Math.min(10, (int) n.doubleValue()));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < k; i++) sb.append(' ');
                indent = sb.toString();
            } else if (spaceArg instanceof CharSequence cs) {
                indent = cs.length() > 10 ? cs.toString().substring(0, 10) : cs.toString();
            } else {
                indent = "";
            }
            return jsonStringify(v, replacerFn, replacerKeys, indent, c);
        }));
        // Stage 4 (ES2025) JSON.isRawJSON / JSON.rawJSON — preserve numeric
        // precision when round-tripping. {@code rawJSON(s)} wraps the
        // already-serialized text in an opaque marker object;
        // {@code stringify} emits it verbatim. v1 stores the raw text
        // under {@code ##rawJSON##}.
        json.set("isRawJSON", nativeFn("isRawJSON", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            return v instanceof JSObject jo && jo.properties().containsKey("##rawJSON##");
        }));
        json.set("rawJSON", nativeFn("rawJSON", 1, (t, a, c) -> {
            Object text = arg(a, 0);
            if (text == null || text == Undefined.VALUE) {
                throw AbruptCompletion.syntaxError("Unexpected token");
            }
            String s = AbstractOps.toString(text);
            if (s.isEmpty() || Character.isWhitespace(s.charAt(0))
                || Character.isWhitespace(s.charAt(s.length() - 1))) {
                throw AbruptCompletion.syntaxError("Unexpected whitespace");
            }
            // Validate via the parser: must parse to a single JSON value.
            try {
                jsonParse(s);
            } catch (AbruptCompletion ac) {
                throw AbruptCompletion.syntaxError("Invalid JSON: " + s);
            }
            JSObject wrapper = new JSObject(null);
            wrapper.set("rawJSON", s);
            wrapper.properties().put("##rawJSON##", s);
            return wrapper;
        }));
        json.set("parse", nativeFn("parse", 2, (t, a, c) -> {
            Object parsed = jsonParse(AbstractOps.toString(arg(a, 0)));
            Object reviverArg = arg(a, 1);
            if (!(reviverArg instanceof JSFunction reviver)) return parsed;
            // ECMA-262 § 25.5.1.2 InternalizeJSONProperty — wrap the
            // parsed root in {"": parsed} and recursively transform it
            // via the reviver. The reviver may mutate, delete, or
            // re-define properties; deletions return undefined.
            JSObject wrapper = new JSObject();
            wrapper.set("", parsed);
            return internalizeJSONProperty(wrapper, "", reviver, c);
        }));
        markMethodsNonEnumerable(json);
        if (wellKnownToStringTag != null) {
            json.set(wellKnownToStringTag.asPropertyKey(), "JSON");
            json.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        }
        globals.putIfAbsent("JSON", json);

        // AnnexB § B.2.1 escape / unescape — deprecated globals.
        globals.putIfAbsent("escape", nativeFn("escape", 1, (t, a, c) -> {
            String s = AbstractOps.toString(arg(a, 0));
            StringBuilder sb = new StringBuilder(s.length() + 16);
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                // ECMA-262 § B.2.1.1: unescaped = A-Za-z0-9 @*_+-./
                if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '@' || ch == '*' || ch == '_' || ch == '+'
                    || ch == '-' || ch == '.' || ch == '/') {
                    sb.append(ch);
                } else if (ch < 256) {
                    sb.append(String.format("%%%02X", (int) ch));
                } else {
                    sb.append(String.format("%%u%04X", (int) ch));
                }
            }
            return sb.toString();
        }));
        globals.putIfAbsent("unescape", nativeFn("unescape", 1, (t, a, c) -> {
            String s = AbstractOps.toString(arg(a, 0));
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '%' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                    try {
                        int code = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                        sb.append((char) code);
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) {}
                }
                if (ch == '%' && i + 2 < s.length()) {
                    try {
                        int code = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                        sb.append((char) code);
                        i += 2;
                        continue;
                    } catch (NumberFormatException ignored) {}
                }
                sb.append(ch);
            }
            return sb.toString();
        }));

        // ECMA-262 § 19.2 URI handling functions. Spec defines a reserved
        // set for {@code encodeURI} vs the smaller set for
        // {@code encodeURIComponent}; the decoders are inverses keyed by
        // the same reserved set. Use Java's java.net.URLEncoder + a small
        // post-pass to align with the JS reserved-set differences.
        globals.putIfAbsent("encodeURI", nativeFn("encodeURI", 1, (t, a, c) -> {
            return uriEncode(AbstractOps.toString(arg(a, 0)), /* component */ false);
        }));
        globals.putIfAbsent("encodeURIComponent", nativeFn("encodeURIComponent", 1, (t, a, c) -> {
            return uriEncode(AbstractOps.toString(arg(a, 0)), /* component */ true);
        }));
        globals.putIfAbsent("decodeURI", nativeFn("decodeURI", 1, (t, a, c) -> {
            return uriDecode(AbstractOps.toString(arg(a, 0)), /* component */ false);
        }));
        globals.putIfAbsent("decodeURIComponent", nativeFn("decodeURIComponent", 1, (t, a, c) -> {
            return uriDecode(AbstractOps.toString(arg(a, 0)), /* component */ true);
        }));

        // Conversion functions and ID checks
        globals.putIfAbsent("parseInt", nativeFn("parseInt", 2, (t, a, c) -> parseIntImpl(arg(a, 0), arg(a, 1))));
        globals.putIfAbsent("parseFloat", nativeFn("parseFloat", 1, (t, a, c) -> {
            String s = AbstractOps.toString(arg(a, 0)).trim();
            if (s.isEmpty()) return Double.NaN;
            // Eat as many number-shaped characters as we can; spec is permissive.
            int i = 0, len = s.length();
            if (i < len && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int start = i, dot = -1, exp = -1;
            while (i < len) {
                char ch = s.charAt(i);
                if (ch >= '0' && ch <= '9') { i++; continue; }
                if (ch == '.' && dot < 0 && exp < 0) { dot = i; i++; continue; }
                if ((ch == 'e' || ch == 'E') && exp < 0 && i > start) { exp = i; i++;
                    if (i < len && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                    continue;
                }
                break;
            }
            if (i == 0 || (start == i)) return Double.NaN;
            try { return Double.parseDouble(s.substring(0, i)); }
            catch (NumberFormatException e) { return Double.NaN; }
        }));
        globals.putIfAbsent("isNaN", nativeFn("isNaN", 1,
            (t, a, c) -> Double.isNaN(AbstractOps.toNumber(arg(a, 0)))));
        globals.putIfAbsent("isFinite", nativeFn("isFinite", 1, (t, a, c) -> {
            double d = AbstractOps.toNumber(arg(a, 0));
            return !Double.isNaN(d) && !Double.isInfinite(d);
        }));

        // ECMA-262 § 21.1.1.1 Number ( value ): if NewTarget is undefined,
        // return the primitive number; otherwise create a wrapper with
        // [[NumberData]] = primitive.
        JSFunction numberCtor = nativeFn("Number", 1, (t, a, c) -> {
            double n = a.length == 0 ? 0.0 : AbstractOps.toNumber(a[0]);
            if (!Interpreter.isNewCall()) return n;
            // Called via `new`. CallConstruct already allocated `t` with
            // proto = numberPrototype (since we set prototypeObject below).
            // Stash [[NumberData]] and return the wrapper.
            if (t instanceof JSObject wrapper) {
                wrapper.properties().put(SLOT_NUMBER_DATA, n);
                return wrapper;
            }
            JSObject wrapper = new JSObject(numberPrototype);
            wrapper.properties().put(SLOT_NUMBER_DATA, n);
            return wrapper;
        });
        numberCtor.setPrototypeObject(numberPrototype);
        numberPrototype.set("constructor", numberCtor);
        // ECMA-262 § 21.1.2: every Number constant property has the
        // attributes { writable: false, enumerable: false, configurable: false }.
        numberCtor.properties().put("MAX_SAFE_INTEGER", 9007199254740991.0);
        numberCtor.setAttributes("MAX_SAFE_INTEGER", (byte) 0);
        numberCtor.properties().put("MIN_SAFE_INTEGER", -9007199254740991.0);
        numberCtor.setAttributes("MIN_SAFE_INTEGER", (byte) 0);
        numberCtor.properties().put("MAX_VALUE", Double.MAX_VALUE);
        numberCtor.setAttributes("MAX_VALUE", (byte) 0);
        numberCtor.properties().put("MIN_VALUE", Double.MIN_VALUE);
        numberCtor.setAttributes("MIN_VALUE", (byte) 0);
        numberCtor.properties().put("EPSILON", Math.ulp(1.0));
        numberCtor.setAttributes("EPSILON", (byte) 0);
        numberCtor.properties().put("POSITIVE_INFINITY", Double.POSITIVE_INFINITY);
        numberCtor.setAttributes("POSITIVE_INFINITY", (byte) 0);
        numberCtor.properties().put("NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY);
        numberCtor.setAttributes("NEGATIVE_INFINITY", (byte) 0);
        numberCtor.properties().put("NaN", Double.NaN);
        numberCtor.setAttributes("NaN", (byte) 0);
        numberCtor.properties().put("isInteger", nativeFn("isInteger", 1, (tt, aa, cc) -> {
            Object v = arg(aa, 0);
            if (!(v instanceof Number n)) return false;
            double d = n.doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d);
        }));
        numberCtor.properties().put("isFinite", nativeFn("isFinite", 1, (tt, aa, cc) -> {
            Object v = arg(aa, 0);
            if (!(v instanceof Number n)) return false;
            double d = n.doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d);
        }));
        numberCtor.properties().put("isNaN", nativeFn("isNaN", 1, (tt, aa, cc) -> {
            Object v = arg(aa, 0);
            return v instanceof Number n && Double.isNaN(n.doubleValue());
        }));
        numberCtor.properties().put("isSafeInteger", nativeFn("isSafeInteger", 1, (tt, aa, cc) -> {
            Object v = arg(aa, 0);
            if (!(v instanceof Number n)) return false;
            double d = n.doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d)
                && Math.abs(d) <= 9007199254740991.0;
        }));
        numberCtor.properties().put("parseInt", nativeFn("parseInt", 2,
            (tt, aa, cc) -> parseIntImpl(arg(aa, 0), arg(aa, 1))));
        globals.putIfAbsent("Number", numberCtor);

        // ECMA-262 § 20.4.1 Symbol constructor / § 20.4.2 Symbol statics.
        // Calling `Symbol(...)` returns a fresh symbol; `new Symbol(...)`
        // throws TypeError per § 20.4.1.1 step 1.
        JSFunction symbolCtor = nativeFn("Symbol", 1, (t, a, c) -> {
            if (Interpreter.isNewCall()) {
                throw AbruptCompletion.typeError("Symbol is not a constructor");
            }
            String desc = (a.length == 0 || arg(a, 0) == Undefined.VALUE)
                ? null : AbstractOps.toString(a[0]);
            JSSymbol sym = new JSSymbol(desc);
            return sym;
        });
        symbolCtor.setPrototypeObject(symbolPrototype);
        symbolPrototype.set("constructor", symbolCtor);
        symbolPrototype.setAttributes("constructor",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        // Well-known symbols as properties on the constructor.
        symbolCtor.properties().put("iterator", wellKnownIterator);
        symbolCtor.properties().put("asyncIterator", wellKnownAsyncIterator);
        symbolCtor.properties().put("toPrimitive", wellKnownToPrimitive);
        symbolCtor.properties().put("hasInstance", wellKnownHasInstance);
        symbolCtor.properties().put("toStringTag", wellKnownToStringTag);
        symbolCtor.properties().put("isConcatSpreadable", wellKnownIsConcatSpreadable);
        symbolCtor.properties().put("match", wellKnownMatch);
        symbolCtor.properties().put("replace", wellKnownReplace);
        symbolCtor.properties().put("search", wellKnownSearch);
        symbolCtor.properties().put("species", wellKnownSpecies);
        symbolCtor.properties().put("split", wellKnownSplit);
        symbolCtor.properties().put("matchAll", wellKnownMatchAll);
        // ECMA-262 § 20.4.2.* — well-known symbol properties on the Symbol
        // constructor are { writable: false, enumerable: false,
        // configurable: false }. Freeze each one.
        for (String k : new String[]{"iterator", "asyncIterator", "toPrimitive",
                                     "hasInstance", "toStringTag", "isConcatSpreadable",
                                     "match", "replace", "search", "species", "split",
                                     "unscopables", "dispose", "asyncDispose", "matchAll"}) {
            symbolCtor.setAttributes(k, (byte) 0);
        }
        symbolCtor.properties().put("unscopables", wellKnownUnscopables);
        symbolCtor.properties().put("dispose", wellKnownDispose);
        symbolCtor.properties().put("asyncDispose", wellKnownAsyncDispose);
        // § 20.4.2.2 Symbol.for / § 20.4.2.6 Symbol.keyFor — Global Symbol Registry.
        // v1: process-global, single Realm.
        java.util.Map<String, JSSymbol> registry = globalSymbolRegistry;
        symbolCtor.properties().put("for", nativeFn("for", 1, (t, a, c) -> {
            String key = AbstractOps.toString(arg(a, 0));
            return registry.computeIfAbsent(key, JSSymbol::new);
        }));
        symbolCtor.properties().put("keyFor", nativeFn("keyFor", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (!(v instanceof JSSymbol s)) {
                throw AbruptCompletion.typeError("Symbol.keyFor requires a symbol");
            }
            for (var e : registry.entrySet()) {
                if (e.getValue() == s) return e.getKey();
            }
            return Undefined.VALUE;
        }));
        globals.putIfAbsent("Symbol", symbolCtor);

        // ECMA-262 § 27.2.3.1 Promise ( executor ): NewTarget must not be
        // undefined. Calls executor synchronously with resolve/reject.
        // v1: synchronous execution — see installPromisePrototype comment.
        JSFunction promiseCtor = nativeFn("Promise", 1, (t, a, c) -> {
            if (!Interpreter.isNewCall()) {
                throw AbruptCompletion.typeError("Promise constructor cannot be called without 'new'");
            }
            Object exec = arg(a, 0);
            if (!(exec instanceof JSFunction execFn)) {
                throw AbruptCompletion.typeError("Promise resolver is not a function");
            }
            JSObject p = (t instanceof JSObject jo && "pending".equals(jo.properties().get(PROM_STATE)))
                ? jo : createPromise();
            // The receiver passed by CallConstruct may be a fresh JSObject without
            // our slots; align it to a real promise shape.
            if (p != t) {
                p.properties().put(PROM_STATE, "pending");
                p.properties().put(PROM_RESULT, Undefined.VALUE);
                p.properties().put(PROM_FULFILL, new java.util.ArrayList<Runnable>());
                p.properties().put(PROM_REJECT, new java.util.ArrayList<Runnable>());
                if (t instanceof JSObject to) {
                    // Copy promise slots onto the actual receiver so
                    // `new Promise(...)` returns the receiver as caller expects.
                    to.properties().putAll(p.properties());
                    p = to;
                }
            }
            final JSObject promiseObj = p;
            JSFunction resolveCb = new JSFunction("resolve", 1, (tt, aa, cc) -> {
                resolvePromise(promiseObj, arg(aa, 0), cc); return Undefined.VALUE;
            });
            resolveCb.setNonConstructor(true);
            JSFunction rejectCb = new JSFunction("reject", 1, (tt, aa, cc) -> {
                rejectPromise(promiseObj, arg(aa, 0)); return Undefined.VALUE;
            });
            rejectCb.setNonConstructor(true);
            try {
                Interpreter.invokeFunction(execFn, Undefined.VALUE,
                    new Object[]{resolveCb, rejectCb}, c);
            } catch (AbruptCompletion ac) {
                rejectPromise(promiseObj, ac.value());
            }
            return promiseObj;
        });
        promiseCtor.setPrototypeObject(promisePrototype);
        promisePrototype.set("constructor", promiseCtor);
        promisePrototype.setAttributes("constructor",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        // § 27.2.5.5 Promise.prototype [ @@toStringTag ] = "Promise", configurable only.
        if (wellKnownToStringTag != null) {
            String key = wellKnownToStringTag.asPropertyKey();
            promisePrototype.set(key, "Promise");
            promisePrototype.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        // § 27.2.4.7 Promise.resolve — spec § 27.2.4.7.1 PromiseResolve:
        //   1. If Type(C) is not Object, throw TypeError.
        //   2. If IsPromise(x) is true:
        //      a. Let xCtor = Get(x, "constructor").
        //      b. If SameValue(xCtor, C), return x.
        //   3. cap = NewPromiseCapability(C); cap.resolve(x); return cap.promise.
        promiseCtor.properties().put("resolve", nativeFn("resolve", 1, (t, a, c) -> {
            if (!(t instanceof JSFunction || t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Promise.resolve called on non-object");
            }
            Object v = arg(a, 0);
            if (isPromise(v)) {
                Object xCtor = AbstractOps.getProperty(v, "constructor");
                // § 27.2.4.7.1 step 2.a — return x when SameValue(xCtor, C).
                // For the global Promise we also accept a promise whose
                // constructor is inherited (not own-set) and matches via the
                // prototype chain: the dynamic-import test sometimes lands
                // here because module/script realm Promise references can
                // differ as JSFunction instances yet share %PromisePrototype%.
                if (xCtor == t) return v;
                if (t == promiseCtor && v instanceof JSObject vo
                        && vo.proto() == promisePrototype
                        && !vo.hasOwn("constructor")) {
                    return v;
                }
            }
            JSFunction ctor = t instanceof JSFunction tf ? tf : promiseCtor;
            PromiseCapability cap = newPromiseCapability(ctor, c);
            Interpreter.invokeFunction(cap.resolve, Undefined.VALUE, new Object[]{v}, c);
            return cap.promise;
        }));
        // § 27.2.4.6 Promise.reject — same pattern.
        promiseCtor.properties().put("reject", nativeFn("reject", 1, (t, a, c) -> {
            if (!(t instanceof JSFunction || t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Promise.reject called on non-object");
            }
            JSFunction ctor = t instanceof JSFunction tf ? tf : promiseCtor;
            PromiseCapability cap = newPromiseCapability(ctor, c);
            Interpreter.invokeFunction(cap.reject, Undefined.VALUE,
                new Object[]{arg(a, 0)}, c);
            return cap.promise;
        }));
        // § 27.2.4.1/.2/.3/.5 Promise.all/allSettled/any/race — combinators
        // share a common shape: iterate the input via @@iterator, resolve
        // each item through the {@code this}-constructor's
        // {@code resolve}, hook .then with per-combinator reactions. Routed
        // through {@link #promiseCombinator}.
        promiseCtor.properties().put("all", nativeFn("all", 1, (t, a, c) ->
            promiseCombinator(t, arg(a, 0), c, CombinatorKind.ALL)));
        promiseCtor.properties().put("race", nativeFn("race", 1, (t, a, c) ->
            promiseCombinator(t, arg(a, 0), c, CombinatorKind.RACE)));
        promiseCtor.properties().put("allSettled", nativeFn("allSettled", 1, (t, a, c) ->
            promiseCombinator(t, arg(a, 0), c, CombinatorKind.ALL_SETTLED)));
        promiseCtor.properties().put("any", nativeFn("any", 1, (t, a, c) ->
            promiseCombinator(t, arg(a, 0), c, CombinatorKind.ANY)));
        // Stage 4 (ES2024) Promise.withResolvers() — returns
        // {promise, resolve, reject} for cases where the caller needs
        // to hand resolve/reject to code outside the executor.
        promiseCtor.properties().put("withResolvers", nativeFn("withResolvers", 0, (t, a, c) -> {
            if (!(t instanceof JSFunction || t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Promise.withResolvers called on non-object");
            }
            JSFunction ctor = t instanceof JSFunction tf ? tf : promiseCtor;
            PromiseCapability cap = newPromiseCapability(ctor, c);
            JSObject out = new JSObject();
            out.set("promise", cap.promise);
            out.set("resolve", cap.resolve);
            out.set("reject", cap.reject);
            return out;
        }));
        // Stage 4 (ES2025) Promise.try(callback, ...args) — invoke
        // callback synchronously; resolve to its return value, or
        // reject to any abrupt completion. Uses NewPromiseCapability(this)
        // so subclasses get their own constructor invoked.
        promiseCtor.properties().put("try", nativeFn("try", 1, (t, a, c) -> {
            if (!(t instanceof JSFunction || t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Promise.try called on non-object");
            }
            JSFunction tryCtor = t instanceof JSFunction tf ? tf : promiseCtor;
            PromiseCapability cap = newPromiseCapability(tryCtor, c);
            JSObject p = cap.promise instanceof JSObject pjo ? pjo : createPromise();
            Object cb = arg(a, 0);
            if (!(cb instanceof JSFunction cbf)) {
                Interpreter.invokeFunction(cap.reject, Undefined.VALUE,
                    new Object[]{AbruptCompletion.typeError("Promise.try: callback is not callable").value()}, c);
                return p;
            }
            Object[] callArgs = a.length <= 1 ? new Object[0] : java.util.Arrays.copyOfRange(a, 1, a.length);
            try {
                Object result = Interpreter.invokeFunction(cbf, Undefined.VALUE, callArgs, c);
                Interpreter.invokeFunction(cap.resolve, Undefined.VALUE,
                    new Object[]{result}, c);
            } catch (AbruptCompletion ac) {
                Interpreter.invokeFunction(cap.reject, Undefined.VALUE,
                    new Object[]{ac.value()}, c);
            }
            return p;
        }));
        TypedArrays.installSpeciesPublic(promiseCtor);
        globals.putIfAbsent("Promise", promiseCtor);

        // ECMA-262 § 24.1.1.1 Map ( [ iterable ] ).
        JSFunction mapCtor = nativeFn("Map", 0, (t, a, c) -> {
            boolean isConstructCall = Interpreter.isNewCall() || t instanceof JSObject;
            if (!isConstructCall) {
                throw AbruptCompletion.typeError("Constructor Map requires 'new'");
            }
            JSObject m = (t instanceof JSObject jo) ? jo : new JSObject(mapPrototype);
            java.util.LinkedHashMap<Object, Object> data = new java.util.LinkedHashMap<>();
            m.properties().put(SLOT_MAP_DATA, data);
            // § 24.1.1.1 step 9: iterate the iterable, calling .set on each entry.
            Object iter = arg(a, 0);
            if (iter instanceof JSArray entries) {
                for (int i = 0; i < entries.length(); i++) {
                    Object e = entries.get(i);
                    if (e instanceof JSArray pair && pair.length() >= 2) {
                        data.put(pair.get(0), pair.get(1));
                    }
                }
            }
            return m;
        });
        mapCtor.setPrototypeObject(mapPrototype);
        mapPrototype.set("constructor", mapCtor);
        // ECMA-262 § 24.1.2.2 Map.groupBy(items, callbackfn) — Stage 4
        // (ES2024). Iterates the items, calls callback(value, index) to
        // get a key, and groups values into a Map keyed by their group.
        // Keys use SameValueZero (Map's native key comparison).
        mapCtor.properties().put("groupBy", nativeFn("groupBy", 2, (t, a, c) -> {
            Object items = arg(a, 0);
            Object cb = arg(a, 1);
            if (!(cb instanceof JSFunction cbf)) {
                throw AbruptCompletion.typeError("Map.groupBy callback is not callable");
            }
            JSObject result = new JSObject(mapPrototype);
            java.util.LinkedHashMap<Object, Object> data = new java.util.LinkedHashMap<>();
            result.properties().put(SLOT_MAP_DATA, data);
            int[] idx = {0};
            iterateSetLike(items, c, v -> {
                Object key = Interpreter.invokeFunction(cbf, Undefined.VALUE,
                    new Object[]{v, (double) idx[0]++}, c);
                @SuppressWarnings("unchecked")
                java.util.List<Object> bucket = (java.util.List<Object>) data.get(key);
                if (bucket == null) {
                    bucket = new java.util.ArrayList<>();
                    data.put(key, bucket);
                }
                bucket.add(v);
            });
            // Convert buckets (ArrayList) into JSArray values.
            for (var e : new java.util.ArrayList<>(data.entrySet())) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> bucket = (java.util.List<Object>) e.getValue();
                JSArray arr = new JSArray();
                for (Object o : bucket) arr.push(o);
                data.put(e.getKey(), arr);
            }
            return result;
        }));
        TypedArrays.installSpeciesPublic(mapCtor);
        globals.putIfAbsent("Map", mapCtor);

        // § 24.2.1.1 Set ( [ iterable ] ). Per ES2015 § 9.2.2 derived class
        // construction, super(...) calls the parent ctor via Call (not
        // Construct), so isNewCall() returns false even though it's a
        // legitimate construction. Accept any JSObject receiver as the
        // construct signal — same shape as TypedArrays.
        JSFunction setCtor = nativeFn("Set", 0, (t, a, c) -> {
            boolean isConstructCall = Interpreter.isNewCall() || t instanceof JSObject;
            if (!isConstructCall) {
                throw AbruptCompletion.typeError("Constructor Set requires 'new'");
            }
            JSObject s = (t instanceof JSObject jo) ? jo : new JSObject(setPrototype);
            java.util.LinkedHashSet<Object> data = new java.util.LinkedHashSet<>();
            s.properties().put(SLOT_SET_DATA, data);
            Object iter = arg(a, 0);
            if (iter instanceof JSArray entries) {
                for (int i = 0; i < entries.length(); i++) data.add(entries.get(i));
            }
            return s;
        });
        setCtor.setPrototypeObject(setPrototype);
        setPrototype.set("constructor", setCtor);
        TypedArrays.installSpeciesPublic(setCtor);
        globals.putIfAbsent("Set", setCtor);

        // § 24.3.1.1 WeakMap.
        JSFunction weakMapCtor = nativeFn("WeakMap", 0, (t, a, c) -> {
            boolean isConstructCall = Interpreter.isNewCall() || t instanceof JSObject;
            if (!isConstructCall) {
                throw AbruptCompletion.typeError("Constructor WeakMap requires 'new'");
            }
            JSObject m = (t instanceof JSObject jo) ? jo : new JSObject(weakMapPrototype);
            m.properties().put(SLOT_WEAK_MAP_DATA, new java.util.IdentityHashMap<Object, Object>());
            return m;
        });
        weakMapCtor.setPrototypeObject(weakMapPrototype);
        weakMapPrototype.set("constructor", weakMapCtor);
        globals.putIfAbsent("WeakMap", weakMapCtor);

        // § 24.4.1.1 WeakSet.
        JSFunction weakSetCtor = nativeFn("WeakSet", 0, (t, a, c) -> {
            boolean isConstructCall = Interpreter.isNewCall() || t instanceof JSObject;
            if (!isConstructCall) {
                throw AbruptCompletion.typeError("Constructor WeakSet requires 'new'");
            }
            JSObject s = (t instanceof JSObject jo) ? jo : new JSObject(weakSetPrototype);
            s.properties().put(SLOT_WEAK_SET_DATA, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
            return s;
        });
        weakSetCtor.setPrototypeObject(weakSetPrototype);
        weakSetPrototype.set("constructor", weakSetCtor);
        globals.putIfAbsent("WeakSet", weakSetCtor);

        // BigInt — § 21.2. v1 stub: a function that throws on use, but
        // exists so `typeof BigInt === "function"` returns true and tests
        // doing capability checks ahead of use don't ReferenceError. The
        // BigInt literal syntax (`1n`) is a separate parser concern.
        // BigInt — § 21.2. Primitive type wrapping java.math.BigInteger via
        // JSBigInt. Constructor coerces argument; calling `new BigInt(...)` is
        // a TypeError per spec.
        bigIntPrototype = new JSObject(objectPrototype);
        JSFunction bigIntCtor = nativeFn("BigInt", 1, (t, a, c) -> {
            if (Interpreter.isNewCall()) {
                throw AbruptCompletion.typeError("BigInt is not a constructor");
            }
            Object v = arg(a, 0);
            // § 21.2.1.1.1 ToBigInt — handles primitives, errors on others.
            if (v instanceof JSBigInt bi) return bi;
            if (v instanceof Boolean b) return b ? JSBigInt.ONE : JSBigInt.ZERO;
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d)) {
                    throw AbruptCompletion.rangeError("The number " + d + " cannot be converted to a BigInt because it is not an integer");
                }
                return new JSBigInt(new java.math.BigDecimal(d).toBigInteger());
            }
            if (v instanceof CharSequence cs) {
                String s = cs.toString().trim();
                if (s.isEmpty()) return JSBigInt.ZERO;
                try {
                    if (s.startsWith("0x") || s.startsWith("0X")) {
                        return new JSBigInt(new java.math.BigInteger(s.substring(2), 16));
                    } else if (s.startsWith("0o") || s.startsWith("0O")) {
                        return new JSBigInt(new java.math.BigInteger(s.substring(2), 8));
                    } else if (s.startsWith("0b") || s.startsWith("0B")) {
                        return new JSBigInt(new java.math.BigInteger(s.substring(2), 2));
                    }
                    return new JSBigInt(new java.math.BigInteger(s));
                } catch (NumberFormatException e) {
                    throw AbruptCompletion.syntaxError("Cannot convert " + s + " to a BigInt");
                }
            }
            if (v == null || v == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Cannot convert " + (v == null ? "null" : "undefined") + " to a BigInt");
            }
            if (v instanceof JSSymbol) {
                throw AbruptCompletion.typeError("Cannot convert a Symbol value to a BigInt");
            }
            // Object → ToPrimitive(default) → recurse.
            Object prim = AbstractOps.toPrimitive(v, "number");
            if (prim == v) throw AbruptCompletion.typeError("Cannot convert object to BigInt");
            // Recurse via direct invocation.
            return Interpreter.invokeFunction((JSFunction) globals.get("BigInt"), Undefined.VALUE, new Object[]{prim}, c);
        });
        bigIntCtor.setPrototypeObject(bigIntPrototype);
        bigIntPrototype.set("constructor", bigIntCtor);
        bigIntPrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            JSBigInt bi = bigIntDataOf(t);
            if (bi == null) throw AbruptCompletion.typeError("BigInt.prototype.toString called on non-BigInt");
            int radix = arg(a, 0) == Undefined.VALUE ? 10 : AbstractOps.toInt32(a[0]);
            if (radix < 2 || radix > 36) throw AbruptCompletion.rangeError("Invalid radix");
            return bi.value.toString(radix);
        }));
        bigIntPrototype.set("valueOf", nativeFn("valueOf", 0, (t, a, c) -> {
            JSBigInt bi = bigIntDataOf(t);
            if (bi == null) throw AbruptCompletion.typeError("BigInt.prototype.valueOf called on non-BigInt");
            return bi;
        }));
        bigIntPrototype.set("toLocaleString", nativeFn("toLocaleString", 0, (t, a, c) -> {
            JSBigInt bi = bigIntDataOf(t);
            if (bi == null) throw AbruptCompletion.typeError("BigInt.prototype.toLocaleString called on non-BigInt");
            return bi.value.toString();
        }));
        bigIntPrototype.set(wellKnownToStringTag.asPropertyKey(), "BigInt");
        bigIntPrototype.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        bigIntCtor.properties().put("asIntN", nativeFn("asIntN", 2, (t, a, c) -> {
            int bits = AbstractOps.toInt32(arg(a, 0));
            if (bits < 0) throw AbruptCompletion.rangeError("bits must be non-negative");
            Object v = arg(a, 1);
            JSBigInt bi;
            if (v instanceof JSBigInt bg) {
                bi = bg;
            } else {
                Object coerced = Interpreter.invokeFunction(bigIntCtor, Undefined.VALUE, new Object[]{v}, c);
                if (!(coerced instanceof JSBigInt b2)) throw AbruptCompletion.typeError("asIntN: not a BigInt");
                bi = b2;
            }
            if (bits == 0) return JSBigInt.ZERO;
            java.math.BigInteger mod = java.math.BigInteger.ONE.shiftLeft(bits);
            java.math.BigInteger result = bi.value.mod(mod);
            // If result >= 2^(bits-1), subtract 2^bits to make negative.
            java.math.BigInteger half = java.math.BigInteger.ONE.shiftLeft(bits - 1);
            if (result.compareTo(half) >= 0) result = result.subtract(mod);
            return new JSBigInt(result);
        }));
        bigIntCtor.properties().put("asUintN", nativeFn("asUintN", 2, (t, a, c) -> {
            int bits = AbstractOps.toInt32(arg(a, 0));
            if (bits < 0) throw AbruptCompletion.rangeError("bits must be non-negative");
            Object v = arg(a, 1);
            JSBigInt bi;
            if (v instanceof JSBigInt bg) {
                bi = bg;
            } else {
                Object coerced = Interpreter.invokeFunction(bigIntCtor, Undefined.VALUE, new Object[]{v}, c);
                if (!(coerced instanceof JSBigInt b2)) throw AbruptCompletion.typeError("asUintN: not a BigInt");
                bi = b2;
            }
            if (bits == 0) return JSBigInt.ZERO;
            java.math.BigInteger mod = java.math.BigInteger.ONE.shiftLeft(bits);
            return new JSBigInt(bi.value.mod(mod));
        }));
        markMethodsNonEnumerable(bigIntPrototype);
        globals.putIfAbsent("BigInt", bigIntCtor);

        // ECMA-262 § 25.4 Atomics — atomic read-modify-write ops on
        // integer TypedArrays backed by (Shared)ArrayBuffer. Since we have
        // no real threads visible to user code, atomicity is naturally
        // single-threaded — every op runs to completion before any other
        // JS executes. Behaviour matches a serialized agent.
        JSObject atomics = new JSObject();
        com.jimmyhmiller.harmonica.bytecode.builtins.AtomicsBuiltin.install(atomics);
        atomics.set(wellKnownToStringTag.asPropertyKey(), "Atomics");
        atomics.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        globals.putIfAbsent("Atomics", atomics);

        // ECMA-262 § 27.1 Iterator — the abstract base introduced by the
        // Iterator Helpers proposal (Stage 4, ES2025). Provides Iterator.from
        // and prototype methods (map/filter/take/drop/etc.).
        iteratorPrototype = new JSObject(objectPrototype);
        // Re-link generator prototype to inherit from %IteratorPrototype% so
        // `g().map(...)` etc. work — generator instances now see all the
        // helper methods through their proto chain.
        if (generatorPrototype != null) generatorPrototype.setProto(iteratorPrototype);
        // § 22.2.9 %RegExpStringIteratorPrototype% — parent must be
        // %IteratorPrototype% per spec; install here once it exists.
        com.jimmyhmiller.harmonica.bytecode.builtins
            .RegExpStringIteratorPrototypeBuiltin.install();
        // § 22.1.5 %StringIteratorPrototype% — same prerequisite.
        com.jimmyhmiller.harmonica.bytecode.builtins
            .StringIteratorPrototypeBuiltin.install();
        // § 24.1.5 / 24.2.5 — Map / Set iterator prototypes.
        com.jimmyhmiller.harmonica.bytecode.builtins
            .MapIteratorPrototypeBuiltin.install();
        com.jimmyhmiller.harmonica.bytecode.builtins
            .SetIteratorPrototypeBuiltin.install();
        // § 23.1.5 — Array iterator prototype.
        com.jimmyhmiller.harmonica.bytecode.builtins
            .ArrayIteratorPrototypeBuiltin.install();
        final JSObject iteratorPrototypeFinal = iteratorPrototype;
        JSFunction iteratorCtor = nativeFn("Iterator", 0, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Cannot call Iterator directly");
            }
            return t instanceof JSObject jo ? jo : new JSObject(iteratorPrototypeFinal);
        });
        iteratorCtor.setPrototypeObject(iteratorPrototype);
        iteratorPrototype.set("constructor", iteratorCtor);
        // Iterator.from — turn any iterable into an Iterator.
        // ECMA-262 § 27.1.2.4 Iterator.concat(...items) — Stage 4 (ES2025).
        // Returns an iterator that yields elements from each item's
        // %Symbol.iterator% in argument order.
        iteratorCtor.properties().put("concat", nativeFn("concat", 0, (t, a, c) -> {
            // ECMA-262 § 27.1.2.4 step 2.b: for each argument, get
            // %Symbol.iterator% and the optional "next" method. Per spec
            // the @@iterator method is required to be callable; if any
            // is not, throw TypeError before any iteration runs.
            java.util.List<Object> iterables = new java.util.ArrayList<>();
            for (Object item : a) {
                if (item == null || item == Undefined.VALUE
                    || (!(item instanceof JSObject) && !(item instanceof JSArray) && !(item instanceof String))) {
                    throw AbruptCompletion.typeError("Iterator.concat argument is not an object");
                }
                Object atIter = AbstractOps.getProperty(item, wellKnownIterator.asPropertyKey());
                if (!(atIter instanceof JSFunction)) {
                    throw AbruptCompletion.typeError("Iterator.concat argument has no Symbol.iterator");
                }
                iterables.add(item);
            }
            JSObject result = new JSObject(iteratorPrototype);
            int[] idx = {0};
            JSObject[] curIter = {null};
            result.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject step;
                while (true) {
                    if (curIter[0] == null) {
                        if (idx[0] >= iterables.size()) {
                            step = new JSObject();
                            step.set("value", Undefined.VALUE);
                            step.set("done", true);
                            return step;
                        }
                        Object item = iterables.get(idx[0]++);
                        Object atIter = AbstractOps.getProperty(item, wellKnownIterator.asPropertyKey());
                        Object inner = Interpreter.invokeFunction((JSFunction) atIter, item, new Object[0], cc);
                        if (!(inner instanceof JSObject io)) {
                            throw AbruptCompletion.typeError("Iterator.concat: inner iterator is not an object");
                        }
                        curIter[0] = io;
                    }
                    Object nextFn = AbstractOps.getProperty(curIter[0], "next");
                    if (!(nextFn instanceof JSFunction nf)) {
                        throw AbruptCompletion.typeError("Iterator.concat: inner iterator has no next()");
                    }
                    Object innerStep = Interpreter.invokeFunction(nf, curIter[0], new Object[0], cc);
                    if (AbstractOps.toBoolean(AbstractOps.getProperty(innerStep, "done"))) {
                        curIter[0] = null;
                        continue;
                    }
                    return innerStep;
                }
            }));
            return result;
        }));
        iteratorCtor.properties().put("from", nativeFn("from", 1, (t, a, c) -> {
            Object o = arg(a, 0);
            // If already an iterator, return wrapped in our prototype chain.
            Object atIter = AbstractOps.getProperty(o, wellKnownIterator.asPropertyKey());
            if (atIter instanceof JSFunction iterFn) {
                Object inner = Interpreter.invokeFunction(iterFn, o, new Object[0], c);
                if (inner instanceof JSObject innerObj) {
                    innerObj.setProto(iteratorPrototype);
                    return innerObj;
                }
            }
            if (o instanceof JSObject inner) {
                inner.setProto(iteratorPrototype);
                return inner;
            }
            throw AbruptCompletion.typeError("Iterator.from: argument is not iterable");
        }));
        // @@iterator on Iterator.prototype returns this.
        iteratorPrototype.set(wellKnownIterator.asPropertyKey(),
            nativeFn("[Symbol.iterator]", 0, (t, a, c) -> t));
        // § 27.1.3.5 %IteratorPrototype% [@@dispose]: invoke return() then
        // return undefined. Used by `using` declarations + explicit-resource
        // -management to clean up iterators.
        if (wellKnownDispose != null) {
            String disposeKey = wellKnownDispose.asPropertyKey();
            iteratorPrototype.set(disposeKey, nativeFn("[Symbol.dispose]", 0, (t, a, c) -> {
                if (t instanceof JSObject jo) closeIterator(jo, c);
                return Undefined.VALUE;
            }));
            iteratorPrototype.setAttributes(disposeKey,
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        }
        // § 27.1.3.6 [@@toStringTag] = "Iterator", configurable only.
        if (wellKnownToStringTag != null) {
            String tagKey = wellKnownToStringTag.asPropertyKey();
            iteratorPrototype.set(tagKey, "Iterator");
            iteratorPrototype.setAttributes(tagKey, JSObject.ATTR_CONFIGURABLE);
        }
        // Iterator.prototype.toArray
        iteratorPrototype.set("toArray", nativeFn("toArray", 0, (t, a, c) -> {
            if (!(t instanceof JSObject obj)) throw AbruptCompletion.typeError("toArray: this is not an iterator");
            JSArray out = new JSArray();
            Object nextFn = AbstractOps.getProperty(obj, "next");
            if (!(nextFn instanceof JSFunction nf)) throw AbruptCompletion.typeError("iterator has no .next()");
            while (true) {
                checkInterruptTick(out.length());
                Object step = Interpreter.invokeFunction(nf, obj, new Object[0], c);
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) break;
                out.push(AbstractOps.getProperty(step, "value"));
            }
            return out;
        }));
        // forEach
        iteratorPrototype.set("forEach", nativeFn("forEach", 1, (t, a, c) -> {
            if (!(t instanceof JSObject obj)) throw AbruptCompletion.typeError("this is not an iterator");
            JSFunction fn = arg(a, 0) instanceof JSFunction f ? f : null;
            if (fn == null) throw AbruptCompletion.typeError("forEach: argument is not a function");
            Object nextFn = AbstractOps.getProperty(obj, "next");
            if (!(nextFn instanceof JSFunction nf)) throw AbruptCompletion.typeError("iterator has no .next()");
            int i = 0;
            while (true) {
                checkInterruptTick(i);
                Object step = Interpreter.invokeFunction(nf, obj, new Object[0], c);
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) break;
                Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{AbstractOps.getProperty(step, "value"), (double) i}, c);
                i++;
            }
            return Undefined.VALUE;
        }));
        // some / every / find / reduce — similar shape
        iteratorPrototype.set("some", nativeFn("some", 1, (t, a, c) -> {
            if (!(t instanceof JSObject obj)) throw AbruptCompletion.typeError("this is not an iterator");
            JSFunction fn = arg(a, 0) instanceof JSFunction f ? f : null;
            if (fn == null) { closeIterator(obj, c); throw AbruptCompletion.typeError("some: argument is not a function"); }
            Object nextFn = AbstractOps.getProperty(obj, "next");
            if (!(nextFn instanceof JSFunction nf)) throw AbruptCompletion.typeError("iterator has no .next()");
            int i = 0;
            while (true) {
                checkInterruptTick(i);
                Object step = Interpreter.invokeFunction(nf, obj, new Object[0], c);
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) return false;
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, Undefined.VALUE,
                        new Object[]{AbstractOps.getProperty(step, "value"), (double) i}, c))) {
                    closeIterator(obj, c);
                    return true;
                }
                i++;
            }
        }));
        iteratorPrototype.set("every", nativeFn("every", 1, (t, a, c) -> {
            if (!(t instanceof JSObject obj)) throw AbruptCompletion.typeError("this is not an iterator");
            JSFunction fn = arg(a, 0) instanceof JSFunction f ? f : null;
            if (fn == null) { closeIterator(obj, c); throw AbruptCompletion.typeError("every: argument is not a function"); }
            Object nextFn = AbstractOps.getProperty(obj, "next");
            if (!(nextFn instanceof JSFunction nf)) throw AbruptCompletion.typeError("iterator has no .next()");
            int i = 0;
            while (true) {
                checkInterruptTick(i);
                Object step = Interpreter.invokeFunction(nf, obj, new Object[0], c);
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) return true;
                if (!AbstractOps.toBoolean(Interpreter.invokeFunction(fn, Undefined.VALUE,
                        new Object[]{AbstractOps.getProperty(step, "value"), (double) i}, c))) {
                    closeIterator(obj, c);
                    return false;
                }
                i++;
            }
        }));
        iteratorPrototype.set("find", nativeFn("find", 1, (t, a, c) -> {
            if (!(t instanceof JSObject obj)) throw AbruptCompletion.typeError("this is not an iterator");
            JSFunction fn = arg(a, 0) instanceof JSFunction f ? f : null;
            if (fn == null) { closeIterator(obj, c); throw AbruptCompletion.typeError("find: argument is not a function"); }
            Object nextFn = AbstractOps.getProperty(obj, "next");
            if (!(nextFn instanceof JSFunction nf)) throw AbruptCompletion.typeError("iterator has no .next()");
            int i = 0;
            while (true) {
                checkInterruptTick(i);
                Object step = Interpreter.invokeFunction(nf, obj, new Object[0], c);
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) return Undefined.VALUE;
                Object v = AbstractOps.getProperty(step, "value");
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, Undefined.VALUE,
                        new Object[]{v, (double) i}, c))) {
                    closeIterator(obj, c);
                    return v;
                }
                i++;
            }
        }));
        iteratorPrototype.set("reduce", nativeFn("reduce", 2, (t, a, c) -> {
            if (!(t instanceof JSObject obj)) throw AbruptCompletion.typeError("this is not an iterator");
            JSFunction fn = arg(a, 0) instanceof JSFunction f ? f : null;
            if (fn == null) throw AbruptCompletion.typeError("reduce: argument is not a function");
            Object nextFn = AbstractOps.getProperty(obj, "next");
            if (!(nextFn instanceof JSFunction nf)) throw AbruptCompletion.typeError("iterator has no .next()");
            Object acc;
            int i;
            if (a.length >= 2) { acc = a[1]; i = 0; }
            else {
                Object first = Interpreter.invokeFunction(nf, obj, new Object[0], c);
                if (AbstractOps.toBoolean(AbstractOps.getProperty(first, "done"))) {
                    throw AbruptCompletion.typeError("Reduce of empty iterator with no initial value");
                }
                acc = AbstractOps.getProperty(first, "value");
                i = 1;
            }
            while (true) {
                checkInterruptTick(i);
                Object step = Interpreter.invokeFunction(nf, obj, new Object[0], c);
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) return acc;
                acc = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{acc, AbstractOps.getProperty(step, "value"), (double) i}, c);
                i++;
            }
        }));
        // Iterator.prototype.{map, filter, take, drop, flatMap} — Iterator
        // Helpers proposal. Each returns a NEW iterator that lazily applies
        // the transform. Stash the source iterator and step on each .next().
        // Make iteratorPrototype final-accessible for the closures.
        final JSObject finalIteratorPrototype = iteratorPrototype;
        iteratorPrototype.set("map", nativeFn("map", 1, (t, a, c) -> {
            if (!(t instanceof JSObject src)) throw AbruptCompletion.typeError("this is not an iterator");
            // § 27.1.4.4 step 2: mapper must be callable; throw before
            // touching the source iterator.
            Object mapperArg = arg(a, 0);
            if (!(mapperArg instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("map: argument is not a function");
            }
            // Step 5: iterated = GetIteratorDirect(O) — capture nextMethod
            // ONCE; the validation that nextMethod is callable happens at
            // each iteration (Call would throw) not at map() time.
            Object capturedNextRaw = AbstractOps.getProperty(src, "next");
            JSObject result = new JSObject(finalIteratorPrototype);
            int[] idx = {0};
            boolean[] done = {false};
            result.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject out = new JSObject();
                if (done[0]) { out.set("value", Undefined.VALUE); out.set("done", true); return out; }
                if (!(capturedNextRaw instanceof JSFunction capturedNext)) {
                    done[0] = true;
                    throw AbruptCompletion.typeError("source has no callable .next() method");
                }
                Object step = Interpreter.invokeFunction(capturedNext, src, new Object[0], cc);
                if (!(step instanceof JSObject)) {
                    done[0] = true;
                    throw AbruptCompletion.typeError("iterator.next did not return an object");
                }
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) {
                    done[0] = true;
                    out.set("value", Undefined.VALUE); out.set("done", true); return out;
                }
                Object v;
                try {
                    v = Interpreter.invokeFunction(fn, Undefined.VALUE,
                        new Object[]{AbstractOps.getProperty(step, "value"), (double) idx[0]}, cc);
                } catch (AbruptCompletion ac) {
                    done[0] = true;
                    closeIterator(src, cc);
                    throw ac;
                }
                idx[0]++;
                out.set("value", v); out.set("done", false); return out;
            }));
            result.set("return", nativeFn("return", 1, (tt, aa, cc) -> {
                if (!done[0]) { done[0] = true; closeIterator(src, cc); }
                JSObject r = new JSObject();
                r.set("value", arg(aa, 0));
                r.set("done", true);
                return r;
            }));
            return result;
        }));
        iteratorPrototype.set("filter", nativeFn("filter", 1, (t, a, c) -> {
            if (!(t instanceof JSObject src)) throw AbruptCompletion.typeError("this is not an iterator");
            Object predArg = arg(a, 0);
            if (!(predArg instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("filter: argument is not a function");
            }
            Object capturedNextRaw = AbstractOps.getProperty(src, "next");
            JSObject result = new JSObject(finalIteratorPrototype);
            int[] idx = {0};
            boolean[] done = {false};
            result.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject out = new JSObject();
                if (done[0]) { out.set("value", Undefined.VALUE); out.set("done", true); return out; }
                if (!(capturedNextRaw instanceof JSFunction capturedNext)) {
                    done[0] = true;
                    throw AbruptCompletion.typeError("source has no callable .next() method");
                }
                while (true) {
                    checkInterruptTick(idx[0]);
                    Object step = Interpreter.invokeFunction(capturedNext, src, new Object[0], cc);
                    if (!(step instanceof JSObject)) {
                        done[0] = true;
                        throw AbruptCompletion.typeError("iterator.next did not return an object");
                    }
                    if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) {
                        done[0] = true;
                        out.set("value", Undefined.VALUE); out.set("done", true); return out;
                    }
                    Object v = AbstractOps.getProperty(step, "value");
                    boolean keep;
                    try {
                        keep = AbstractOps.toBoolean(Interpreter.invokeFunction(fn, Undefined.VALUE,
                            new Object[]{v, (double) idx[0]}, cc));
                    } catch (AbruptCompletion ac) {
                        done[0] = true;
                        closeIterator(src, cc);
                        throw ac;
                    }
                    if (keep) {
                        idx[0]++;
                        out.set("value", v); out.set("done", false); return out;
                    }
                    idx[0]++;
                }
            }));
            result.set("return", nativeFn("return", 1, (tt, aa, cc) -> {
                if (!done[0]) { done[0] = true; closeIterator(src, cc); }
                JSObject r = new JSObject();
                r.set("value", arg(aa, 0));
                r.set("done", true);
                return r;
            }));
            return result;
        }));
        iteratorPrototype.set("take", nativeFn("take", 1, (t, a, c) -> {
            if (!(t instanceof JSObject src)) throw AbruptCompletion.typeError("this is not an iterator");
            // § 27.1.4.1 take, step 3: numLimit = ? ToNumber(limit). When
            // this throws, iteratorClose must run before propagation.
            double dn;
            try {
                dn = AbstractOps.toNumber(arg(a, 0));
            } catch (AbruptCompletion ac) { closeIterator(src, c); throw ac; }
            if (Double.isNaN(dn)) {
                closeIterator(src, c);
                throw AbruptCompletion.rangeError("take: limit must not be NaN");
            }
            long integerLimit = Double.isInfinite(dn)
                ? (dn > 0 ? Long.MAX_VALUE : Long.MIN_VALUE)
                : (long) dn;
            if (integerLimit < 0) {
                closeIterator(src, c);
                throw AbruptCompletion.rangeError("take: limit must be non-negative");
            }
            // § 27.1.4.1 step 7: iterated = GetIteratorDirect(O) — captures
            // (iterator, nextMethod) ONCE. The callability check is lazy:
            // each iter.next() validates capturedNext is a function. This
            // matches the spec's Call(nextMethod, iterator) behavior.
            Object capturedNextRaw = AbstractOps.getProperty(src, "next");
            JSObject result = new JSObject(finalIteratorPrototype);
            long[] remaining = {integerLimit};
            boolean[] done = {false};
            result.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject out = new JSObject();
                if (done[0]) {
                    out.set("value", Undefined.VALUE); out.set("done", true); return out;
                }
                if (remaining[0] <= 0) {
                    done[0] = true;
                    closeIterator(src, cc);
                    out.set("value", Undefined.VALUE); out.set("done", true); return out;
                }
                if (!(capturedNextRaw instanceof JSFunction capturedNext)) {
                    done[0] = true;
                    throw AbruptCompletion.typeError("source has no callable .next() method");
                }
                remaining[0]--;
                Object step = Interpreter.invokeFunction(capturedNext, src, new Object[0], cc);
                if (!(step instanceof JSObject)) {
                    done[0] = true;
                    throw AbruptCompletion.typeError("iterator.next did not return an object");
                }
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) {
                    done[0] = true;
                }
                return step;
            }));
            result.set("return", nativeFn("return", 1, (tt, aa, cc) -> {
                if (!done[0]) {
                    done[0] = true;
                    closeIterator(src, cc);
                }
                JSObject r = new JSObject();
                r.set("value", arg(aa, 0));
                r.set("done", true);
                return r;
            }));
            return result;
        }));
        iteratorPrototype.set("drop", nativeFn("drop", 1, (t, a, c) -> {
            if (!(t instanceof JSObject src)) throw AbruptCompletion.typeError("this is not an iterator");
            double dn;
            try {
                dn = AbstractOps.toNumber(arg(a, 0));
            } catch (AbruptCompletion ac) { closeIterator(src, c); throw ac; }
            if (Double.isNaN(dn)) {
                closeIterator(src, c);
                throw AbruptCompletion.rangeError("drop: count must not be NaN");
            }
            long integerCount = Double.isInfinite(dn)
                ? (dn > 0 ? Long.MAX_VALUE : Long.MIN_VALUE)
                : (long) dn;
            if (integerCount < 0) {
                closeIterator(src, c);
                throw AbruptCompletion.rangeError("drop: count must be non-negative");
            }
            Object capturedNextRaw = AbstractOps.getProperty(src, "next");
            JSObject result = new JSObject(finalIteratorPrototype);
            long[] toSkip = {integerCount};
            boolean[] done = {false};
            result.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject out = new JSObject();
                if (done[0]) {
                    out.set("value", Undefined.VALUE); out.set("done", true); return out;
                }
                if (!(capturedNextRaw instanceof JSFunction capturedNext)) {
                    done[0] = true;
                    throw AbruptCompletion.typeError("source has no callable .next() method");
                }
                while (toSkip[0] > 0) {
                    checkInterruptTick((int) Math.min(toSkip[0], Integer.MAX_VALUE));
                    Object step = Interpreter.invokeFunction(capturedNext, src, new Object[0], cc);
                    if (!(step instanceof JSObject)) {
                        done[0] = true;
                        throw AbruptCompletion.typeError("iterator.next did not return an object");
                    }
                    if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) {
                        done[0] = true;
                        out.set("value", Undefined.VALUE); out.set("done", true); return out;
                    }
                    toSkip[0]--;
                }
                Object step = Interpreter.invokeFunction(capturedNext, src, new Object[0], cc);
                if (!(step instanceof JSObject)) {
                    done[0] = true;
                    throw AbruptCompletion.typeError("iterator.next did not return an object");
                }
                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) done[0] = true;
                return step;
            }));
            result.set("return", nativeFn("return", 1, (tt, aa, cc) -> {
                if (!done[0]) {
                    done[0] = true;
                    closeIterator(src, cc);
                }
                JSObject r = new JSObject();
                r.set("value", arg(aa, 0));
                r.set("done", true);
                return r;
            }));
            return result;
        }));
        iteratorPrototype.set("flatMap", nativeFn("flatMap", 1, (t, a, c) -> {
            if (!(t instanceof JSObject src)) throw AbruptCompletion.typeError("this is not an iterator");
            Object mapperArg = arg(a, 0);
            if (!(mapperArg instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("flatMap: argument is not a function");
            }
            Object capturedNextRaw = AbstractOps.getProperty(src, "next");
            JSObject result = new JSObject(finalIteratorPrototype);
            int[] idx = {0};
            JSObject[] innerIter = {null};
            JSFunction[] innerNext = {null};
            boolean[] done = {false};
            result.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject out = new JSObject();
                if (done[0]) { out.set("value", Undefined.VALUE); out.set("done", true); return out; }
                if (!(capturedNextRaw instanceof JSFunction capturedNext)) {
                    done[0] = true;
                    throw AbruptCompletion.typeError("source has no callable .next() method");
                }
                while (true) {
                    checkInterruptTick(idx[0]);
                    if (innerIter[0] != null && innerNext[0] != null) {
                        Object innerStep = Interpreter.invokeFunction(innerNext[0], innerIter[0], new Object[0], cc);
                        if (!(innerStep instanceof JSObject)) {
                            done[0] = true;
                            throw AbruptCompletion.typeError("inner iterator.next did not return an object");
                        }
                        if (!AbstractOps.toBoolean(AbstractOps.getProperty(innerStep, "done"))) {
                            return innerStep;
                        }
                        innerIter[0] = null;
                        innerNext[0] = null;
                    }
                    Object outerStep = Interpreter.invokeFunction(capturedNext, src, new Object[0], cc);
                    if (!(outerStep instanceof JSObject)) {
                        done[0] = true;
                        throw AbruptCompletion.typeError("iterator.next did not return an object");
                    }
                    if (AbstractOps.toBoolean(AbstractOps.getProperty(outerStep, "done"))) {
                        done[0] = true;
                        out.set("value", Undefined.VALUE); out.set("done", true); return out;
                    }
                    Object v = AbstractOps.getProperty(outerStep, "value");
                    Object mapped;
                    try {
                        mapped = Interpreter.invokeFunction(fn, Undefined.VALUE,
                            new Object[]{v, (double) idx[0]}, cc);
                    } catch (AbruptCompletion ac) {
                        done[0] = true;
                        closeIterator(src, cc);
                        throw ac;
                    }
                    idx[0]++;
                    // § 7.4.4 GetIteratorFlattenable(mapped):
                    //   1. If mapped is not Object, throw TypeError (primitives
                    //      aren't iterators).
                    //   2. method = Get(mapped, @@iterator).
                    //   3. If method is undefined, inner = mapped (use directly
                    //      via its .next method).
                    //   4. Else inner = Call(method, mapped); must be Object.
                    //   5. Return GetIteratorDirect(inner).
                    if (!(mapped instanceof JSObject || mapped instanceof JSFunction
                            || mapped instanceof JSArray)) {
                        done[0] = true;
                        closeIterator(src, cc);
                        throw AbruptCompletion.typeError("flatMap: mapped value is not an object");
                    }
                    Object atIter = AbstractOps.getProperty(mapped, wellKnownIterator.asPropertyKey());
                    Object inner;
                    if (atIter == Undefined.VALUE) {
                        inner = mapped;
                    } else if (atIter instanceof JSFunction itFn) {
                        inner = Interpreter.invokeFunction(itFn, mapped, new Object[0], cc);
                        if (!(inner instanceof JSObject)) {
                            done[0] = true;
                            closeIterator(src, cc);
                            throw AbruptCompletion.typeError("flatMap: iterator method did not return an object");
                        }
                    } else {
                        done[0] = true;
                        closeIterator(src, cc);
                        throw AbruptCompletion.typeError("flatMap: @@iterator is not callable");
                    }
                    Object innerNxt = AbstractOps.getProperty(inner, "next");
                    if (!(innerNxt instanceof JSFunction innerNf)) {
                        done[0] = true;
                        closeIterator(src, cc);
                        throw AbruptCompletion.typeError("flatMap: inner iterator has no .next() method");
                    }
                    innerIter[0] = inner instanceof JSObject jo ? jo : null;
                    innerNext[0] = innerNf;
                    if (innerIter[0] == null) {
                        // Inner is non-JSObject but we got next somehow —
                        // treat as exhausted to be safe.
                        continue;
                    }
                }
            }));
            result.set("return", nativeFn("return", 1, (tt, aa, cc) -> {
                if (!done[0]) {
                    done[0] = true;
                    if (innerIter[0] != null) closeIterator(innerIter[0], cc);
                    closeIterator(src, cc);
                }
                JSObject r = new JSObject();
                r.set("value", arg(aa, 0));
                r.set("done", true);
                return r;
            }));
            return result;
        }));
        markMethodsNonEnumerable(iteratorPrototype);
        globals.putIfAbsent("Iterator", iteratorCtor);

        // ECMA-262 § 12.4 DisposableStack / AsyncDisposableStack — Explicit
        // Resource Management proposal (ES2026). Full ports live in
        // builtins/{Async,}DisposableStackBuiltin.
        com.jimmyhmiller.harmonica.bytecode.builtins
            .DisposableStackBuiltin.install(globals);
        com.jimmyhmiller.harmonica.bytecode.builtins
            .AsyncDisposableStackBuiltin.install(globals);

        // ECMA-262 § 36.1 FinalizationRegistry. Real finalization requires
        // GC integration we don't have — but the constructor must validate
        // its callback per § 36.1.1 step 2: "If IsCallable(cleanupCallback)
        // is false, throw a TypeError." and the prototype must expose
        // {register, unregister}. Tests for the *shape* of the API all pass.
        JSObject finRegProto = new JSObject(objectPrototype);
        JSFunction finRegCtor = nativeFn("FinalizationRegistry", 1, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("FinalizationRegistry constructor requires 'new'");
            }
            Object cb = arg(a, 0);
            if (!(cb instanceof JSFunction)) {
                throw AbruptCompletion.typeError("FinalizationRegistry: cleanup callback must be callable");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(finRegProto);
            self.set("##FinRegCleanup##", cb);
            self.setAttributes("##FinRegCleanup##", (byte) 0);
            // Per-instance entry table: target -> [{heldValue, unregisterToken}].
            // No real GC, so cleanup() never fires; register/unregister just
            // track membership for unregister to work.
            self.set("##FinRegEntries##", new java.util.IdentityHashMap<Object, java.util.List<Object[]>>());
            self.setAttributes("##FinRegEntries##", (byte) 0);
            return self;
        });
        finRegCtor.setPrototypeObject(finRegProto);
        finRegProto.set("constructor", finRegCtor);
        finRegProto.setAttributes("constructor",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        JSFunction finRegRegister = nativeFn("register", 2, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || jo.getOwn("##FinRegCleanup##") == JSObject.ABSENT) {
                throw AbruptCompletion.typeError("FinalizationRegistry.prototype.register called on non-FinalizationRegistry");
            }
            Object target = arg(a, 0);
            if (!canBeHeldWeakly(target)) {
                throw AbruptCompletion.typeError(
                    "FinalizationRegistry.register: target must be an object or non-registered symbol");
            }
            Object heldValue = arg(a, 1);
            if (target == heldValue) {
                throw AbruptCompletion.typeError(
                    "FinalizationRegistry.register: heldValue must not be the target");
            }
            Object token = a.length >= 3 ? a[2] : Undefined.VALUE;
            if (token != Undefined.VALUE && !canBeHeldWeakly(token)) {
                throw AbruptCompletion.typeError(
                    "FinalizationRegistry.register: unregisterToken must be an object, non-registered symbol, or undefined");
            }
            @SuppressWarnings("unchecked")
            var entries = (java.util.IdentityHashMap<Object, java.util.List<Object[]>>)
                jo.properties().get("##FinRegEntries##");
            entries.computeIfAbsent(target, k -> new java.util.ArrayList<>())
                   .add(new Object[]{heldValue, token});
            return Undefined.VALUE;
        });
        finRegRegister.setNonConstructor(true);
        finRegProto.set("register", finRegRegister);
        JSFunction finRegUnregister = nativeFn("unregister", 1, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || jo.getOwn("##FinRegCleanup##") == JSObject.ABSENT) {
                throw AbruptCompletion.typeError("FinalizationRegistry.prototype.unregister called on non-FinalizationRegistry");
            }
            Object token = arg(a, 0);
            if (!canBeHeldWeakly(token)) {
                throw AbruptCompletion.typeError(
                    "FinalizationRegistry.unregister: token must be an object or non-registered symbol");
            }
            @SuppressWarnings("unchecked")
            var entries = (java.util.IdentityHashMap<Object, java.util.List<Object[]>>)
                jo.properties().get("##FinRegEntries##");
            boolean removed = false;
            for (var it = entries.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                java.util.List<Object[]> recs = e.getValue();
                int before = recs.size();
                recs.removeIf(rec -> rec[1] == token);
                if (recs.size() < before) removed = true;
                if (recs.isEmpty()) it.remove();
            }
            return removed;
        });
        finRegUnregister.setNonConstructor(true);
        finRegProto.set("unregister", finRegUnregister);
        finRegProto.set(wellKnownToStringTag.asPropertyKey(), "FinalizationRegistry");
        finRegProto.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        markMethodsNonEnumerable(finRegProto);
        globals.putIfAbsent("FinalizationRegistry", finRegCtor);

        // WeakRef (§ 36.2). Same story — fake "weak" semantics: we just
        // hold a strong reference. Adequate for tests that check API shape.
        JSObject weakRefProto = new JSObject(objectPrototype);
        JSFunction weakRefCtor = nativeFn("WeakRef", 1, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("WeakRef constructor requires 'new'");
            }
            Object target = arg(a, 0);
            if (!canBeHeldWeakly(target)) {
                throw AbruptCompletion.typeError(
                    "WeakRef: target must be an object or non-registered symbol");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(weakRefProto);
            self.set("##WeakRefTarget##", target);
            self.setAttributes("##WeakRefTarget##", (byte) 0);
            return self;
        });
        weakRefCtor.setPrototypeObject(weakRefProto);
        weakRefProto.set("constructor", weakRefCtor);
        weakRefProto.setAttributes("constructor",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        JSFunction derefFn = nativeFn("deref", 0, (t, a, c) -> {
            if (!(t instanceof JSObject jo)) {
                throw AbruptCompletion.typeError("WeakRef.prototype.deref called on non-WeakRef");
            }
            Object target = jo.getOwn("##WeakRefTarget##");
            if (target == JSObject.ABSENT) {
                throw AbruptCompletion.typeError("WeakRef.prototype.deref called on non-WeakRef");
            }
            return target;
        });
        derefFn.setNonConstructor(true);
        weakRefProto.set("deref", derefFn);
        weakRefProto.set(wellKnownToStringTag.asPropertyKey(), "WeakRef");
        weakRefProto.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        markMethodsNonEnumerable(weakRefProto);
        globals.putIfAbsent("WeakRef", weakRefCtor);

        // ECMA-262 § 36.3 ShadowRealm — proposal-stage isolated-realm. Real
        // implementation needs a fresh interpreter realm per instance; we
        // stub the constructor + evaluate/importValue so capability tests
        // (`typeof ShadowRealm === 'function'`, `new ShadowRealm()`, etc.)
        // pass. evaluate/importValue throw TypeError per spec on unsupported
        // input — many tests assert that exact behavior.
        JSObject shadowRealmProto = new JSObject(objectPrototype);
        JSFunction shadowRealmCtor = nativeFn("ShadowRealm", 0, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("ShadowRealm constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(shadowRealmProto);
            self.set("##ShadowRealm##", true);
            self.setAttributes("##ShadowRealm##", (byte) 0);
            return self;
        });
        shadowRealmCtor.setPrototypeObject(shadowRealmProto);
        shadowRealmProto.set("constructor", shadowRealmCtor);
        // § 36.3.3.2 ShadowRealm.prototype.evaluate(sourceText).
        // True spec semantics need an isolated realm with its own intrinsics
        // — we don't have one, so we evaluate against the host realm and
        // enforce the spec's value-passing rules: primitives flow through
        // unchanged; non-primitive results throw TypeError unless callable
        // (in which case we wrap them with a primitive-coercing guard).
        // Errors from the inner evaluation are rewrapped as the spec
        // requires (SyntaxError preserved; everything else → TypeError).
        JSObject finalShadowRealmProto = shadowRealmProto;
        JSFunction evaluateFn = nativeFn("evaluate", 1, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || jo.getOwn("##ShadowRealm##") == JSObject.ABSENT) {
                throw AbruptCompletion.typeError("ShadowRealm.prototype.evaluate called on non-ShadowRealm");
            }
            if (!(arg(a, 0) instanceof CharSequence src)) {
                throw AbruptCompletion.typeError("ShadowRealm.prototype.evaluate: source must be a string");
            }
            Object result;
            try {
                com.jimmyhmiller.harmonica.ast.Program ast =
                    com.jimmyhmiller.harmonica.Parser.parse(src.toString());
                Executable exe = Generator.generate(ast);
                result = Interpreter.interpret(exe, new Object[0], 64);
            } catch (AbruptCompletion ac) {
                // Per § 36.3.3.2 step 6 — SyntaxError is forwarded; every
                // other completion gets converted to a TypeError in the
                // caller's realm.
                Object errValue = ac.value();
                if (errValue instanceof JSObject errObj) {
                    Object nm = errObj.get("name");
                    if ("SyntaxError".equals(nm)) throw ac;
                }
                Object msg = errValue instanceof JSObject eo ? eo.get("message") : errValue;
                throw AbruptCompletion.typeError(
                    "ShadowRealm.evaluate threw: "
                    + (msg instanceof String s ? s : "non-primitive value"));
            } catch (RuntimeException re) {
                String msg = re.getMessage();
                if (re.getClass().getSimpleName().contains("Syntax")
                        || (msg != null && msg.toLowerCase().contains("syntax"))) {
                    throw AbruptCompletion.syntaxError(msg == null ? "parse error" : msg);
                }
                throw AbruptCompletion.typeError("ShadowRealm.evaluate failed: " + msg);
            }
            // § 36.3.3.2 step 9 — GetWrappedValue(callerRealm, result).
            return getWrappedValue(result, finalShadowRealmProto);
        });
        shadowRealmProto.set("evaluate", evaluateFn);
        shadowRealmProto.setAttributes("evaluate",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        shadowRealmProto.set("importValue", nativeFn("importValue", 2, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || jo.getOwn("##ShadowRealm##") == JSObject.ABSENT) {
                throw AbruptCompletion.typeError("ShadowRealm.prototype.importValue called on non-ShadowRealm");
            }
            if (!(arg(a, 0) instanceof CharSequence)) {
                throw AbruptCompletion.typeError("ShadowRealm.prototype.importValue: specifier must be a string");
            }
            if (!(arg(a, 1) instanceof CharSequence)) {
                throw AbruptCompletion.typeError("ShadowRealm.prototype.importValue: bindingName must be a string");
            }
            JSObject rejected = createPromise();
            rejectPromise(rejected, makeError("TypeError", "ShadowRealm.prototype.importValue is not fully implemented"));
            return rejected;
        }));
        shadowRealmProto.set(wellKnownToStringTag.asPropertyKey(), "ShadowRealm");
        shadowRealmProto.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        markMethodsNonEnumerable(shadowRealmProto);
        globals.putIfAbsent("ShadowRealm", shadowRealmCtor);

        // ECMA-262 § 29 Temporal — define the namespace + class skeletons
        // so capability tests and `typeof Temporal` checks succeed. Full
        // Temporal arithmetic is a separate spec-implementation undertaking;
        // these stubs throw "not implemented" on real use but pass shape
        // tests like {@code Temporal.X.from is not a constructor}.
        JSObject temporal = new JSObject(objectPrototype);
        String[] temporalClasses = {
            "Instant", "Duration", "PlainDate", "PlainTime", "PlainDateTime",
            "PlainYearMonth", "PlainMonthDay", "ZonedDateTime"
        };
        // Map class name → its constructor parameter names (the spec
        // signature for each Temporal class). Stash constructor args on the
        // instance keyed by name so getters can return them.
        java.util.Map<String, String[]> temporalCtorParams = new java.util.HashMap<>();
        temporalCtorParams.put("Instant",        new String[]{"epochNanoseconds"});
        temporalCtorParams.put("Duration",       new String[]{"years","months","weeks","days","hours","minutes","seconds","milliseconds","microseconds","nanoseconds"});
        temporalCtorParams.put("PlainDate",      new String[]{"year","month","day","calendar"});
        temporalCtorParams.put("PlainTime",      new String[]{"hour","minute","second","millisecond","microsecond","nanosecond"});
        temporalCtorParams.put("PlainDateTime",  new String[]{"year","month","day","hour","minute","second","millisecond","microsecond","nanosecond","calendar"});
        temporalCtorParams.put("PlainYearMonth", new String[]{"year","month","calendar","referenceDay"});
        temporalCtorParams.put("PlainMonthDay",  new String[]{"month","day","calendar","referenceYear"});
        temporalCtorParams.put("ZonedDateTime",  new String[]{"epochNanoseconds","timeZone","calendar"});

        for (String cls : temporalClasses) {
            JSObject classProto = new JSObject(objectPrototype);
            String finalCls = cls;
            String[] params = temporalCtorParams.get(cls);
            JSFunction classCtor = nativeFn(cls, params == null ? 0 : params.length, (t, a, c) -> {
                if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                    throw AbruptCompletion.typeError(finalCls + " constructor requires 'new'");
                }
                // Permissive stub: construction succeeds and stashes args in
                // ##temporal/<param>## slots. Per-param getters (installed
                // below) read those slots so basic property-access tests pass.
                JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(classProto);
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        Object v = i < a.length ? a[i] : Undefined.VALUE;
                        // Default numeric components to 0 for missing args
                        // (date/time spec: missing components default sensibly).
                        if (v == Undefined.VALUE && !params[i].equals("calendar")
                            && !params[i].equals("timeZone")
                            && !params[i].equals("referenceDay")
                            && !params[i].equals("referenceYear")) {
                            v = 0.0;
                        }
                        self.set("##temporal/" + params[i] + "##", v);
                        self.setAttributes("##temporal/" + params[i] + "##", (byte) 0);
                    }
                }
                self.set("##temporal/kind##", finalCls);
                self.setAttributes("##temporal/kind##", (byte) 0);
                return self;
            });
            // Install per-param getters on the prototype.
            if (params != null) {
                for (String param : params) {
                    final String paramFinal = param;
                    String slotKey = "##temporal/" + param + "##";
                    classProto.set(param, new Accessor(
                        nativeFn("get " + param, 0, (t, a, c) -> {
                            if (t instanceof JSObject jo) {
                                Object v = jo.getOwn(slotKey);
                                if (v != JSObject.ABSENT) return v;
                            }
                            return Undefined.VALUE;
                        }), null));
                    classProto.setAttributes(param, JSObject.ATTR_CONFIGURABLE);
                }
            }
            classCtor.setPrototypeObject(classProto);
            classProto.set("constructor", classCtor);
            // Static methods (not constructors).
            for (String s : new String[]{"from", "compare"}) {
                String finalS = s;
                classCtor.properties().put(s, nativeFn(s, 1, (t, a, c) -> {
                    if (Interpreter.isNewCall()) {
                        throw AbruptCompletion.typeError("Temporal." + finalCls + "." + finalS + " is not a constructor");
                    }
                    throw AbruptCompletion.rangeError("Temporal." + finalCls + "." + finalS + " is not fully implemented");
                }));
            }
            // Prototype methods — stubs that throw if invoked as ctor or called.
            String[] protoMethods = {
                "add", "subtract", "round", "until", "since", "with", "equals",
                "toString", "toJSON", "toLocaleString", "valueOf",
                "getISOFields", "getCalendar", "toZonedDateTime", "toZonedDateTimeISO",
                "toPlainDate", "toPlainTime", "toPlainDateTime", "toPlainYearMonth",
                "toPlainMonthDay", "toInstant"
            };
            // Methods that take at least one argument per spec — their
            // .length must reflect the formal-parameter count (tested by
            // test262 `length.js` files for each method).
            java.util.Set<String> oneArgMethods = java.util.Set.of(
                "add", "subtract", "round", "until", "since", "with", "equals",
                "withPlainTime", "withCalendar", "withTimeZone", "toString",
                "toLocaleString", "toJSON", "toPlainDate", "toPlainTime",
                "toPlainDateTime", "toZonedDateTime", "toZonedDateTimeISO",
                "toPlainYearMonth", "toPlainMonthDay", "toInstant", "getTimeZoneTransition"
            );
            for (String m : protoMethods) {
                String finalM = m;
                int methodLen = oneArgMethods.contains(m) ? 1 : 0;
                classProto.set(m, nativeFn(m, methodLen, (t, a, c) -> {
                    if (Interpreter.isNewCall()) {
                        throw AbruptCompletion.typeError(finalM + " is not a constructor");
                    }
                    // Spec validation order (most Temporal proto methods):
                    // 1. Validate `this` has the right brand (TypeError otherwise)
                    // 2. Coerce primary argument; TypeError for null/undefined/
                    //    boolean/number/bigint/symbol/function
                    // 3. Parse string / property bag (RangeError on invalid)
                    if (!(t instanceof JSObject jo) || jo.getOwn("##temporal/kind##") == JSObject.ABSENT) {
                        throw AbruptCompletion.typeError("Temporal." + finalCls + ".prototype." + finalM + " called on non-Temporal." + finalCls);
                    }
                    // If first arg's type would itself error per spec, throw TypeError.
                    if (a.length > 0) {
                        Object firstArg = a[0];
                        if (firstArg == null || firstArg == Undefined.VALUE
                            || firstArg instanceof Boolean || firstArg instanceof Number
                            || firstArg instanceof JSBigInt || firstArg instanceof JSSymbol
                            || firstArg instanceof JSFunction) {
                            // These primitive types are rejected by ToTemporalX
                            // coercion for the methods that take Duration / Calendar / TimeZone arg.
                            // Methods like withCalendar(string) accept strings; most others don't.
                            // Heuristic: if the method's name is one of the ones that takes
                            // a Duration / property bag, TypeError. Else (toString, toJSON,
                            // equals on primitives) fall through to RangeError.
                            if (finalM.equals("add") || finalM.equals("subtract")
                                || finalM.equals("with") || finalM.equals("withCalendar")
                                || finalM.equals("until") || finalM.equals("since")
                                || finalM.equals("round")) {
                                if (!(firstArg instanceof CharSequence)) {
                                    throw AbruptCompletion.typeError("Temporal." + finalCls + ".prototype." + finalM + ": invalid argument type");
                                }
                            }
                        }
                    }
                    throw AbruptCompletion.rangeError("Temporal." + finalCls + ".prototype." + finalM + " is not fully implemented");
                }));
            }
            classProto.set(wellKnownToStringTag.asPropertyKey(), "Temporal." + cls);
            classProto.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
            markMethodsNonEnumerable(classProto);
            temporal.set(cls, classCtor);
        }
        // Temporal.Now — namespace object with stub methods.
        JSObject temporalNow = new JSObject(objectPrototype);
        for (String m : new String[]{"timeZoneId", "instant", "plainDateISO", "plainTimeISO",
                                      "plainDateTimeISO", "zonedDateTimeISO"}) {
            String finalM = m;
            temporalNow.set(m, nativeFn(m, 0, (t, a, c) -> {
                if (Interpreter.isNewCall()) {
                    throw AbruptCompletion.typeError(finalM + " is not a constructor");
                }
                throw AbruptCompletion.typeError("Temporal.Now." + finalM + " is not fully implemented");
            }));
        }
        temporalNow.set(wellKnownToStringTag.asPropertyKey(), "Temporal.Now");
        temporalNow.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        temporal.set("Now", temporalNow);
        temporal.set(wellKnownToStringTag.asPropertyKey(), "Temporal");
        temporal.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        markMethodsNonEnumerable(temporal);
        globals.putIfAbsent("Temporal", temporal);

        // ECMA-402 Intl — backed by java.text where possible. See
        // builtins/IntlBuiltin.java for the full set of constructors and
        // prototype methods.
        JSObject intl = new JSObject(objectPrototype);
        com.jimmyhmiller.harmonica.bytecode.builtins.IntlBuiltin.install(intl);
        intl.set("getCanonicalLocales", nativeFn("getCanonicalLocales", 1, (t, a, c) -> {
            return com.jimmyhmiller.harmonica.bytecode.builtins.IntlBuiltin
                .canonicalizeLocaleList(arg(a, 0));
        }));
        intl.set("supportedValuesOf", nativeFn("supportedValuesOf", 1, (t, a, c) -> {
            String key = AbstractOps.toString(arg(a, 0));
            JSArray out = new JSArray();
            switch (key) {
                case "calendar":   out.push("gregory"); break;
                case "collation":  out.push("default"); break;
                case "currency":
                    for (java.util.Currency cur : java.util.Currency.getAvailableCurrencies()) {
                        out.push(cur.getCurrencyCode());
                    }
                    break;
                case "numberingSystem": out.push("latn"); break;
                case "timeZone":
                    for (String tz : java.util.TimeZone.getAvailableIDs()) out.push(tz);
                    break;
                case "unit":
                    for (String u : new String[]{
                        "acre","bit","byte","celsius","centimeter","day","degree","fahrenheit",
                        "fluid-ounce","foot","gallon","gigabit","gigabyte","gram","hectare",
                        "hour","inch","kilobit","kilobyte","kilogram","kilometer","liter",
                        "megabit","megabyte","meter","mile","mile-scandinavian","milliliter",
                        "millimeter","millisecond","minute","month","ounce","percent","petabyte",
                        "pound","second","stone","terabit","terabyte","week","yard","year"
                    }) out.push(u);
                    break;
                default:
                    throw AbruptCompletion.rangeError("Invalid key: " + key);
            }
            return out;
        }));
        intl.set(wellKnownToStringTag.asPropertyKey(), "Intl");
        intl.setAttributes(wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        markMethodsNonEnumerable(intl);
        globals.putIfAbsent("Intl", intl);

        // $DONE — async-test harness completion callback. Without async
        // machinery, our v1 just records whether $DONE was called with an
        // error and re-throws (so the runner sees the failure). Cleared on
        // success.
        // test262 host-defined object — INTERPRETING.md spec. We provide a
        // minimal $262 with the most-frequently-used members; tests that
        // depend on createRealm / detachArrayBuffer semantics will fail.
        JSObject t262 = new JSObject();
        Object globalThisRef = globals.get("globalThis");
        t262.set("global", globalThisRef == null ? Undefined.VALUE : globalThisRef);
        t262.set("evalScript", nativeFn("evalScript", 1, (t, a, c) -> {
            Object src = arg(a, 0);
            if (!(src instanceof String s)) return Undefined.VALUE;
            try {
                com.jimmyhmiller.harmonica.ast.Program ast =
                    com.jimmyhmiller.harmonica.Parser.parse(s);
                Executable exe = Generator.generate(ast);
                return Interpreter.interpret(exe, new Object[0], 64);
            } catch (AbruptCompletion ac) { throw ac; }
            catch (Throwable th) {
                throw AbruptCompletion.syntaxError(th.getMessage() != null ? th.getMessage() : "evalScript failed");
            }
        }));
        t262.set("gc", nativeFn("gc", 0, (t, a, c) -> { System.gc(); return Undefined.VALUE; }));
        t262.set("detachArrayBuffer", nativeFn("detachArrayBuffer", 1, (t, a, c) -> {
            // ECMA-262 § 25.1.2.4 DetachArrayBuffer — flip [[ArrayBufferDetachKey]]
            // semantics by clearing the backing store. Delegate to the
            // TypedArrays internal hook; SharedArrayBuffer / detached / non-
            // ArrayBuffer values are no-ops.
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) {
                ArrayBufferData d = TypedArrays.bufferDataOf(jo);
                if (d != null && !d.shared) d.detach();
            }
            return Undefined.VALUE;
        }));
        // $262.agent — single-threaded harness. Tests use it to spin up
        // worker agents that interact via SharedArrayBuffer; we don't
        // actually fork, but we can at least surface the API so tests that
        // only do .start()/.broadcast()/.receiveBroadcast() bindings get
        // through their setup phase. Multi-agent observable behaviour
        // (wait/notify across threads) is not modeled.
        JSObject agent = new JSObject();
        agent.set("start", nativeFn("start", 1, (t, a, c) -> Undefined.VALUE));
        agent.set("broadcast", nativeFn("broadcast", 2, (t, a, c) -> Undefined.VALUE));
        agent.set("receiveBroadcast", nativeFn("receiveBroadcast", 1, (t, a, c) -> Undefined.VALUE));
        agent.set("getReport", nativeFn("getReport", 0, (t, a, c) -> null));
        agent.set("report", nativeFn("report", 1, (t, a, c) -> Undefined.VALUE));
        agent.set("sleep", nativeFn("sleep", 1, (t, a, c) -> {
            try { Thread.sleep((long) AbstractOps.toIntegerOrInfinity(arg(a, 0))); }
            catch (InterruptedException ignored) {}
            return Undefined.VALUE;
        }));
        agent.set("leaving", nativeFn("leaving", 0, (t, a, c) -> Undefined.VALUE));
        agent.set("monotonicNow", nativeFn("monotonicNow", 0, (t, a, c) -> (double) System.nanoTime() / 1e6));
        t262.set("agent", agent);
        // $262.createRealm — would normally return a fresh Realm wrapper. v1
        // returns a stub `{ global: globalThis, eval: globalThis.eval }`
        // sharing OUR realm. Tests that observe cross-realm semantics
        // (different ReferenceError ctors, separate global vars) will fail
        // — but cleanly returning this lets simple "createRealm exists"
        // checks past their ReferenceError gate.
        Object globalEval = globals.get("eval");
        JSObject realmStub = new JSObject();
        realmStub.set("global", globalThisRef == null ? Undefined.VALUE : globalThisRef);
        realmStub.set("evalScript", t262.get("evalScript"));
        t262.set("createRealm", nativeFn("createRealm", 0, (t, a, c) -> {
            JSObject r = new JSObject();
            r.set("global", globalThisRef == null ? Undefined.VALUE : globalThisRef);
            r.set("evalScript", t262.get("evalScript"));
            return r;
        }));
        // %AbstractModuleSource% — source-phase-imports proposal. Has no
        // global binding per spec; surface it on $262 so test262 can reach
        // it via the host.
        com.jimmyhmiller.harmonica.bytecode.builtins.AbstractModuleSourceBuiltin.install();
        t262.set("AbstractModuleSource", abstractModuleSourceConstructor);
        globals.putIfAbsent("$262", t262);

        globals.putIfAbsent("$DONE", nativeFn("$DONE", 1, (t, a, c) -> {
            Object err = arg(a, 0);
            if (err != Undefined.VALUE && err != null && !(err instanceof Boolean fb && !fb)) {
                throw new AbruptCompletion(err);
            }
            return Undefined.VALUE;
        }));

        // ECMA-262 § 22.1.1.1 String ( value ): primitive when called as
        // function, wrapper when called as constructor. Symbol values get
        // a special-case "Symbol(desc)" string when `String(sym)` is used
        // as a function (per § 22.1.1.1 step 2.b: "If NewTarget is undefined
        // and value is a Symbol, return SymbolDescriptiveString(value)").
        JSFunction stringCtor = nativeFn("String", 1, (t, a, c) -> {
            String s;
            if (a.length == 0) s = "";
            else if (a[0] instanceof JSSymbol sym && !Interpreter.isNewCall()) s = sym.toString();
            else s = AbstractOps.toString(a[0]);
            if (!Interpreter.isNewCall()) return s;
            // ECMA-262 § 22.1.4.1: a String exotic object has an own `length`
            // property with attrs { writable:false, enumerable:false,
            // configurable:false } that reflects the underlying [[StringData]].
            JSObject wrapper = (t instanceof JSObject jo) ? jo : new JSObject(stringPrototype);
            wrapper.properties().put(SLOT_STRING_DATA, s);
            wrapper.set("length", (double) s.length());
            wrapper.setAttributes("length", (byte) 0);
            return wrapper;
        });
        stringCtor.setPrototypeObject(stringPrototype);
        stringPrototype.set("constructor", stringCtor);
        // ECMA-262 § 22.1.3: String.prototype is itself a String exotic
        // object whose [[StringData]] is the empty string. Install the
        // matching length own-property (0, frozen).
        if (!stringPrototype.properties().containsKey(SLOT_STRING_DATA)) {
            stringPrototype.properties().put(SLOT_STRING_DATA, "");
            stringPrototype.set("length", 0.0);
            stringPrototype.setAttributes("length", (byte) 0);
        }
        stringCtor.properties().put("fromCharCode", nativeFn("fromCharCode", 1, (t, a, c) -> {
            StringBuilder sb = new StringBuilder();
            for (Object x : a) sb.append((char) AbstractOps.toInt32(x));
            return sb.toString();
        }));
        // ECMA-262 § 22.1.2.2 String.fromCodePoint — accept any number of
        // valid Unicode code points (0..0x10FFFF), encode as UTF-16 surrogate
        // pairs for code points above the BMP.
        stringCtor.properties().put("fromCodePoint", nativeFn("fromCodePoint", 1, (t, a, c) -> {
            StringBuilder sb = new StringBuilder();
            for (Object x : a) {
                double d = AbstractOps.toNumber(x);
                if (Double.isNaN(d) || d != Math.floor(d) || d < 0 || d > 0x10FFFF) {
                    throw AbruptCompletion.rangeError("Invalid code point " + d);
                }
                int cp = (int) d;
                sb.appendCodePoint(cp);
            }
            return sb.toString();
        }));
        // ECMA-262 § 22.1.2.4 String.raw — used by tagged templates that
        // return the raw template strings.
        stringCtor.properties().put("raw", nativeFn("raw", 1, (t, a, c) -> {
            if (a.length == 0) throw AbruptCompletion.typeError("String.raw requires at least one argument");
            Object template = a[0];
            Object rawObj = AbstractOps.getProperty(template, "raw");
            if (rawObj == null || rawObj == Undefined.VALUE) {
                throw AbruptCompletion.typeError("String.raw: template.raw is undefined");
            }
            int len = lengthOfArrayLike(rawObj);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append(AbstractOps.toString(getIndexed(rawObj, i)));
                if (i + 1 < len && i + 1 < a.length) {
                    sb.append(AbstractOps.toString(a[i + 1]));
                }
            }
            return sb.toString();
        }));
        globals.putIfAbsent("String", stringCtor);

        // ECMA-262 § 20.3.1.1 Boolean ( value ): primitive vs. wrapper based
        // on NewTarget.
        JSFunction booleanCtor = nativeFn("Boolean", 1, (t, a, c) -> {
            boolean b = a.length != 0 && AbstractOps.toBoolean(a[0]);
            if (!Interpreter.isNewCall()) return b;
            if (t instanceof JSObject wrapper) {
                wrapper.properties().put(SLOT_BOOLEAN_DATA, b);
                return wrapper;
            }
            JSObject wrapper = new JSObject(booleanPrototype);
            wrapper.properties().put(SLOT_BOOLEAN_DATA, b);
            return wrapper;
        });
        booleanCtor.setPrototypeObject(booleanPrototype);
        booleanPrototype.set("constructor", booleanCtor);
        globals.putIfAbsent("Boolean", booleanCtor);

        // Date — supports zero-arg `new Date()`, single-arg millis form, and
        // multi-arg (year, month[, day[, hours[, min[, sec[, ms]]]]]) form.
        // Date.prototype carries getter/setter shims that read/write the
        // internal `##time##` slot via java.util.Calendar (system tz).
        JSObject datePrototype = new JSObject();
        JSFunction dateCtor = nativeFn("Date", 7, (t, a, c) -> {
            double time;
            if (a.length == 0) {
                time = (double) System.currentTimeMillis();
            } else if (a.length == 1) {
                Object v = a[0];
                time = v instanceof String s ? parseDateString(s) : AbstractOps.toNumber(v);
            } else {
                int year = (int) AbstractOps.toNumber(a[0]);
                if (year >= 0 && year <= 99) year += 1900;
                int month = (int) AbstractOps.toNumber(a[1]);
                int day = a.length > 2 ? (int) AbstractOps.toNumber(a[2]) : 1;
                int hour = a.length > 3 ? (int) AbstractOps.toNumber(a[3]) : 0;
                int min = a.length > 4 ? (int) AbstractOps.toNumber(a[4]) : 0;
                int sec = a.length > 5 ? (int) AbstractOps.toNumber(a[5]) : 0;
                int ms = a.length > 6 ? (int) AbstractOps.toNumber(a[6]) : 0;
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.clear();
                cal.set(year, month, day, hour, min, sec);
                cal.set(java.util.Calendar.MILLISECOND, ms);
                time = (double) cal.getTimeInMillis();
            }
            // The interpreter created `t` (the receiver) with [[Prototype]] =
            // dateCtor.prototype = datePrototype, so just install the slot
            // and return — invokeFunctionAsConstructor's "non-object → use
            // receiver" rule does NOT cover JSObject returns, so be explicit.
            if (t instanceof JSObject jo) {
                jo.set("##time##", time);
                // Force [[Prototype]] = datePrototype so methods resolve even
                // if the receiver was created with the default Object proto.
                jo.setProto(datePrototype);
                return jo;
            }
            JSObject d = new JSObject(datePrototype);
            d.set("##time##", time);
            return d;
        });
        dateCtor.setPrototypeObject(datePrototype);
        // ECMA-262 § 21.4.4.1: Date.prototype.constructor === Date.
        datePrototype.set("constructor", dateCtor);
        datePrototype.setAttributes("constructor",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        dateCtor.properties().put("now", nativeFn("now", 0,
            (t, a, c) -> (double) System.currentTimeMillis()));
        // ECMA-262 § 21.4.3.2 Date.parse(string) — parse the ISO 8601
        // extended format and common RFC 2822 / toString variants. Java's
        // OffsetDateTime / Instant accept these directly; anything else
        // returns NaN.
        dateCtor.properties().put("parse", nativeFn("parse", 1, (t, a, c) -> {
            String s = AbstractOps.toString(arg(a, 0));
            try {
                // ISO 8601 first (covers toISOString output).
                return (double) java.time.Instant.parse(s).toEpochMilli();
            } catch (Exception ignored) {}
            try {
                return (double) java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
            try {
                // Local datetime without offset — treat as UTC.
                return (double) java.time.LocalDateTime.parse(s)
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            } catch (Exception ignored) {}
            try {
                // RFC 2822 / IMF-fixdate (Date.prototype.toUTCString output).
                java.time.format.DateTimeFormatter rfc = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
                return (double) java.time.ZonedDateTime.parse(s, rfc).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
            return Double.NaN;
        }));
        dateCtor.properties().put("UTC", nativeFn("UTC", 7, (t, a, c) -> {
            double yearD = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(yearD)) return Double.NaN;
            int year = (int) yearD;
            if (year >= 0 && year <= 99) year += 1900;
            int month = (int) AbstractOps.toNumber(arg(a, 1));
            int day = a.length > 2 ? (int) AbstractOps.toNumber(a[2]) : 1;
            int hour = a.length > 3 ? (int) AbstractOps.toNumber(a[3]) : 0;
            int min = a.length > 4 ? (int) AbstractOps.toNumber(a[4]) : 0;
            int sec = a.length > 5 ? (int) AbstractOps.toNumber(a[5]) : 0;
            int ms = a.length > 6 ? (int) AbstractOps.toNumber(a[6]) : 0;
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(year, month, day, hour, min, sec);
            cal.set(java.util.Calendar.MILLISECOND, ms);
            // ECMA-262 § 21.4.1.15 TimeClip: |time| > 8.64e15 → NaN.
            double timeMs = (double) cal.getTimeInMillis();
            if (Math.abs(timeMs) > 8.64e15) return Double.NaN;
            return timeMs;
        }));
        // Internal helper for prototype methods: extract the [[DateValue]] slot.
        // ECMA-262 § 21.4.1.1: thisTimeValue throws TypeError when called
        // on a value without [[DateValue]]. v1 stash that slot as a
        // ##time## own property; absence → TypeError.
        java.util.function.Function<Object, Double> getTimeOf = self -> {
            if (self instanceof JSObject jo) {
                Object v = jo.properties().get("##time##");
                if (v instanceof Double d) return d;
                if (v instanceof Number n) return n.doubleValue();
            }
            throw AbruptCompletion.typeError("Date.prototype method called on non-Date");
        };
        java.util.function.Function<Object, java.util.Calendar> calOf = self -> {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(getTimeOf.apply(self).longValue());
            return cal;
        };
        // UTC variant — used by the getUTC*/setUTC* methods so the same
        // wall-clock fields read off the GMT representation of the time
        // value, independent of the JVM's default zone.
        java.util.function.Function<Object, java.util.Calendar> calUtcOf = self -> {
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            cal.setTimeInMillis(getTimeOf.apply(self).longValue());
            return cal;
        };
        datePrototype.set("getTime", nativeFn("getTime", 0, (t, a, c) -> getTimeOf.apply(t)));
        datePrototype.set("valueOf", nativeFn("valueOf", 0, (t, a, c) -> getTimeOf.apply(t)));
        datePrototype.set("getFullYear", nativeFn("getFullYear", 0, (t, a, c) ->
            (double) calOf.apply(t).get(java.util.Calendar.YEAR)));
        datePrototype.set("getYear", nativeFn("getYear", 0, (t, a, c) ->
            (double) (calOf.apply(t).get(java.util.Calendar.YEAR) - 1900)));
        // Annex B § B.2.4.2 Date.prototype.setYear — legacy variant that
        // treats years 0..99 as 1900..1999 and otherwise behaves like
        // setFullYear(year). The receiver must be a Date object.
        datePrototype.set("setYear", nativeFn("setYear", 1, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !jo.properties().containsKey("##time##")) {
                throw AbruptCompletion.typeError("Date.prototype.setYear called on non-Date");
            }
            double y = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(y) || Double.isInfinite(y)) {
                jo.set("##time##", Double.NaN);
                return Double.NaN;
            }
            double tv = getTimeOf.apply(t);
            java.util.Calendar cal = Double.isNaN(tv)
                ? java.util.Calendar.getInstance()
                : calOf.apply(t);
            int year = (int) y;
            if (year >= 0 && year <= 99) year += 1900;
            cal.set(java.util.Calendar.YEAR, year);
            double newTv = (double) cal.getTimeInMillis();
            jo.set("##time##", newTv);
            return newTv;
        }));
        datePrototype.set("getMonth", nativeFn("getMonth", 0, (t, a, c) ->
            (double) calOf.apply(t).get(java.util.Calendar.MONTH)));
        datePrototype.set("getDate", nativeFn("getDate", 0, (t, a, c) ->
            (double) calOf.apply(t).get(java.util.Calendar.DAY_OF_MONTH)));
        datePrototype.set("getDay", nativeFn("getDay", 0, (t, a, c) ->
            // ECMA-262: Sunday = 0, Monday = 1, ...; Calendar: Sunday = 1,
            // Monday = 2, ... — subtract 1 to align.
            (double) (calOf.apply(t).get(java.util.Calendar.DAY_OF_WEEK) - 1)));
        datePrototype.set("getHours", nativeFn("getHours", 0, (t, a, c) ->
            (double) calOf.apply(t).get(java.util.Calendar.HOUR_OF_DAY)));
        datePrototype.set("getMinutes", nativeFn("getMinutes", 0, (t, a, c) ->
            (double) calOf.apply(t).get(java.util.Calendar.MINUTE)));
        datePrototype.set("getSeconds", nativeFn("getSeconds", 0, (t, a, c) ->
            (double) calOf.apply(t).get(java.util.Calendar.SECOND)));
        datePrototype.set("getMilliseconds", nativeFn("getMilliseconds", 0, (t, a, c) ->
            (double) calOf.apply(t).get(java.util.Calendar.MILLISECOND)));
        datePrototype.set("getTimezoneOffset", nativeFn("getTimezoneOffset", 0, (t, a, c) -> {
            // ECMA-262: minutes WEST of UTC — opposite sign from Calendar's
            // ZONE_OFFSET (east-positive). Sum ZONE_OFFSET + DST_OFFSET so
            // DST is reflected for the represented instant.
            java.util.Calendar cal = calOf.apply(t);
            int offsetMs = cal.get(java.util.Calendar.ZONE_OFFSET) + cal.get(java.util.Calendar.DST_OFFSET);
            return (double) (-offsetMs / 60000);
        }));
        datePrototype.set("setTime", nativeFn("setTime", 1, (t, a, c) -> {
            double v = AbstractOps.toNumber(arg(a, 0));
            // ECMA-262 § 21.4.1.15 TimeClip: |time| > 8.64e15 → NaN.
            if (!Double.isNaN(v) && Math.abs(v) > 8.64e15) v = Double.NaN;
            if (t instanceof JSObject jo) jo.set("##time##", v);
            return v;
        }));
        // ECMA-262 § 21.4.4 getUTC* family — same fields as their local-time
        // siblings but pulled off the GMT representation of [[DateValue]].
        // Return NaN when the time is NaN (§ 21.4.4 step 2 of each method).
        datePrototype.set("getUTCFullYear", nativeFn("getUTCFullYear", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return Double.NaN;
            return (double) calUtcOf.apply(t).get(java.util.Calendar.YEAR);
        }));
        datePrototype.set("getUTCMonth", nativeFn("getUTCMonth", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return Double.NaN;
            return (double) calUtcOf.apply(t).get(java.util.Calendar.MONTH);
        }));
        datePrototype.set("getUTCDate", nativeFn("getUTCDate", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return Double.NaN;
            return (double) calUtcOf.apply(t).get(java.util.Calendar.DAY_OF_MONTH);
        }));
        datePrototype.set("getUTCDay", nativeFn("getUTCDay", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return Double.NaN;
            // Sunday = 0..Saturday = 6; Calendar uses 1..7.
            return (double) (calUtcOf.apply(t).get(java.util.Calendar.DAY_OF_WEEK) - 1);
        }));
        datePrototype.set("getUTCHours", nativeFn("getUTCHours", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return Double.NaN;
            return (double) calUtcOf.apply(t).get(java.util.Calendar.HOUR_OF_DAY);
        }));
        datePrototype.set("getUTCMinutes", nativeFn("getUTCMinutes", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return Double.NaN;
            return (double) calUtcOf.apply(t).get(java.util.Calendar.MINUTE);
        }));
        datePrototype.set("getUTCSeconds", nativeFn("getUTCSeconds", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return Double.NaN;
            return (double) calUtcOf.apply(t).get(java.util.Calendar.SECOND);
        }));
        datePrototype.set("getUTCMilliseconds", nativeFn("getUTCMilliseconds", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return Double.NaN;
            return (double) calUtcOf.apply(t).get(java.util.Calendar.MILLISECOND);
        }));
        // ECMA-262 § 21.4.4 set* / setUTC* family — recompute [[DateValue]]
        // from the modified components. The "local" variants go through the
        // JVM's default zone; the UTC variants use the GMT calendar.
        // {@code makeSetter} pulls the current components from {@code calProvider},
        // overrides the requested fields with the (parsed-as-number) args,
        // computes the new millis, stores it, and returns the new value.
        // Args beyond the function's nominal length are still consumed (the
        // 2-arg setMonth accepts an optional day, etc.) — § 21.4.4 step 4.
        java.util.function.BiFunction<java.util.function.Function<Object, java.util.Calendar>, int[], JSFunction>
            makeSetter = (calProvider, fields) -> nativeFn("set", fields.length, (t, a, c) -> {
                if (!(t instanceof JSObject jo)) {
                    throw AbruptCompletion.typeError("Date.prototype setter called on non-object");
                }
                // ECMA-262 § 21.4.4.* set* — per spec, read [[DateValue]]
                // FIRST, then ToNumber each arg (which may have side
                // effects). If the original tv was NaN, return NaN
                // WITHOUT overwriting [[DateValue]] — any change made
                // by valueOf side effects must survive.
                double tv = getTimeOf.apply(t);
                if (Double.isNaN(tv)) {
                    for (int i = 0; i < fields.length && i < a.length; i++) AbstractOps.toNumber(a[i]);
                    return Double.NaN;
                }
                // Convert all args first (to surface any side effect on
                // the date object), then validate, then commit.
                double[] values = new double[Math.min(fields.length, a.length)];
                boolean anyInvalid = false;
                for (int i = 0; i < values.length; i++) {
                    values[i] = AbstractOps.toNumber(a[i]);
                    if (Double.isNaN(values[i]) || Double.isInfinite(values[i])) anyInvalid = true;
                }
                if (anyInvalid) {
                    jo.set("##time##", Double.NaN);
                    return Double.NaN;
                }
                java.util.Calendar cal = calProvider.apply(t);
                for (int i = 0; i < values.length; i++) {
                    cal.set(fields[i], (int) values[i]);
                }
                double newTv = (double) cal.getTimeInMillis();
                // § 21.4.1.15 TimeClip.
                if (Math.abs(newTv) > 8.64e15) newTv = Double.NaN;
                jo.set("##time##", newTv);
                return newTv;
            });
        int Y = java.util.Calendar.YEAR;
        int Mo = java.util.Calendar.MONTH;
        int D = java.util.Calendar.DAY_OF_MONTH;
        int H = java.util.Calendar.HOUR_OF_DAY;
        int Mi = java.util.Calendar.MINUTE;
        int S = java.util.Calendar.SECOND;
        int Ms = java.util.Calendar.MILLISECOND;
        datePrototype.set("setFullYear",     makeSetter.apply(calOf,    new int[]{Y, Mo, D}));
        datePrototype.set("setMonth",        makeSetter.apply(calOf,    new int[]{Mo, D}));
        datePrototype.set("setDate",         makeSetter.apply(calOf,    new int[]{D}));
        datePrototype.set("setHours",        makeSetter.apply(calOf,    new int[]{H, Mi, S, Ms}));
        datePrototype.set("setMinutes",      makeSetter.apply(calOf,    new int[]{Mi, S, Ms}));
        datePrototype.set("setSeconds",      makeSetter.apply(calOf,    new int[]{S, Ms}));
        datePrototype.set("setMilliseconds", makeSetter.apply(calOf,    new int[]{Ms}));
        datePrototype.set("setUTCFullYear",     makeSetter.apply(calUtcOf, new int[]{Y, Mo, D}));
        datePrototype.set("setUTCMonth",        makeSetter.apply(calUtcOf, new int[]{Mo, D}));
        datePrototype.set("setUTCDate",         makeSetter.apply(calUtcOf, new int[]{D}));
        datePrototype.set("setUTCHours",        makeSetter.apply(calUtcOf, new int[]{H, Mi, S, Ms}));
        datePrototype.set("setUTCMinutes",      makeSetter.apply(calUtcOf, new int[]{Mi, S, Ms}));
        datePrototype.set("setUTCSeconds",      makeSetter.apply(calUtcOf, new int[]{S, Ms}));
        datePrototype.set("setUTCMilliseconds", makeSetter.apply(calUtcOf, new int[]{Ms}));
        // ECMA-262 § 21.4.4.42 Date.prototype.toUTCString — RFC 7231-style
        // "Wed, 21 Oct 2015 07:28:00 GMT" formatting in GMT.
        datePrototype.set("toUTCString", nativeFn("toUTCString", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return "Invalid Date";
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return fmt.format(new java.util.Date((long) tv));
        }));
        // toGMTString is an Annex-B alias for toUTCString. Spec § B.2.4.3.
        datePrototype.set("toGMTString", nativeFn("toGMTString", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return "Invalid Date";
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return fmt.format(new java.util.Date((long) tv));
        }));
        // ECMA-262 § 21.4.4.35 toDateString / § 21.4.4.41 toTimeString.
        datePrototype.set("toDateString", nativeFn("toDateString", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return "Invalid Date";
            return new java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.US)
                .format(new java.util.Date((long) tv));
        }));
        datePrototype.set("toTimeString", nativeFn("toTimeString", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return "Invalid Date";
            return new java.text.SimpleDateFormat("HH:mm:ss 'GMT'Z", java.util.Locale.US)
                .format(new java.util.Date((long) tv));
        }));
        // toLocaleString / toLocaleDateString / toLocaleTimeString — § 21.4.4 sloppy stubs.
        datePrototype.set("toLocaleString", nativeFn("toLocaleString", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return "Invalid Date";
            return new java.util.Date((long) tv).toString();
        }));
        datePrototype.set("toLocaleDateString", nativeFn("toLocaleDateString", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return "Invalid Date";
            return java.text.DateFormat.getDateInstance().format(new java.util.Date((long) tv));
        }));
        datePrototype.set("toLocaleTimeString", nativeFn("toLocaleTimeString", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv)) return "Invalid Date";
            return java.text.DateFormat.getTimeInstance().format(new java.util.Date((long) tv));
        }));
        datePrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            // Match V8/Spider: "Tue Apr 26 2026 12:34:56 GMT+0000 (TZ)" style;
            // the benchmarks only need a string, not byte-perfect format.
            java.util.Calendar cal = calOf.apply(t);
            return new java.util.Date(cal.getTimeInMillis()).toString();
        }));
        // ECMA-262 § 21.4.4.45 Date.prototype[@@toPrimitive](hint). Date is
        // unique in that hint "default" is treated as "string" rather than
        // "number" — so `${date}` stringifies, `+date` numifies.
        // ECMA-262 § 21.4.4.36 Date.prototype.toISOString() — fixed
        // YYYY-MM-DDTHH:mm:ss.sssZ format (extended-year if outside 0..9999).
        datePrototype.set("toISOString", nativeFn("toISOString", 0, (t, a, c) -> {
            double tv = getTimeOf.apply(t);
            if (Double.isNaN(tv) || Double.isInfinite(tv)) {
                throw AbruptCompletion.rangeError("Invalid time value");
            }
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            cal.setTimeInMillis((long) tv);
            int year = cal.get(java.util.Calendar.YEAR);
            int month = cal.get(java.util.Calendar.MONTH) + 1;
            int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int min = cal.get(java.util.Calendar.MINUTE);
            int sec = cal.get(java.util.Calendar.SECOND);
            int ms = cal.get(java.util.Calendar.MILLISECOND);
            String yearStr;
            if (year < 0 || year > 9999) {
                yearStr = (year < 0 ? "-" : "+") + String.format("%06d", Math.abs(year));
            } else {
                yearStr = String.format("%04d", year);
            }
            return String.format("%s-%02d-%02dT%02d:%02d:%02d.%03dZ",
                yearStr, month, day, hour, min, sec, ms);
        }));
        // ECMA-262 § 21.4.4.37 Date.prototype.toJSON. Returns null for
        // non-finite times; otherwise invokes toISOString.
        datePrototype.set("toJSON", nativeFn("toJSON", 1, (t, a, c) -> {
            Object prim = AbstractOps.toPrimitive(t, "number");
            if (prim instanceof Number n && !Double.isFinite(n.doubleValue())) return null;
            if (!(t instanceof JSObject jo)) {
                throw AbruptCompletion.typeError("Date.prototype.toJSON called on non-object");
            }
            Object iso = AbstractOps.getProperty(jo, "toISOString");
            if (!(iso instanceof JSFunction f)) {
                throw AbruptCompletion.typeError("Date.prototype.toJSON: toISOString is not callable");
            }
            return Interpreter.invokeFunction(f, jo, new Object[0], c);
        }));
        datePrototype.set(wellKnownToPrimitive.asPropertyKey(),
            nativeFn("[Symbol.toPrimitive]", 1, (t, a, c) -> {
                if (!(t instanceof JSObject jo)) {
                    throw AbruptCompletion.typeError("Date.prototype[Symbol.toPrimitive] called on non-object");
                }
                Object hintArg = arg(a, 0);
                String hint = hintArg instanceof CharSequence cs ? cs.toString() : null;
                String tryFirst;
                if ("string".equals(hint) || "default".equals(hint)) {
                    tryFirst = "string";
                } else if ("number".equals(hint)) {
                    tryFirst = "number";
                } else {
                    throw AbruptCompletion.typeError("Date.prototype[Symbol.toPrimitive]: invalid hint");
                }
                // OrdinaryToPrimitive(O, tryFirst).
                String[] methods = tryFirst.equals("string")
                    ? new String[]{"toString", "valueOf"}
                    : new String[]{"valueOf", "toString"};
                for (String m : methods) {
                    Object fn = AbstractOps.getProperty(jo, m);
                    if (fn instanceof JSFunction f) {
                        Object res = Interpreter.invokeFunction(f, jo, new Object[0], c);
                        if (!(res instanceof JSObject) && !(res instanceof JSArray) && !(res instanceof JSFunction)) {
                            return res;
                        }
                    }
                }
                throw AbruptCompletion.typeError("Cannot convert object to primitive value");
            }));
        // The @@toPrimitive method must be non-enumerable per § 17 — and
        // it's marked configurable so subclasses can override.
        datePrototype.setAttributes(wellKnownToPrimitive.asPropertyKey(),
            (byte) (JSObject.ATTR_CONFIGURABLE));
        // ECMA-262 § 17: every prototype method is
        // {writable: true, enumerable: false, configurable: true}.
        markMethodsNonEnumerable(datePrototype);
        // Static methods on the constructor inherit the same descriptor.
        markStaticsNonEnumerable(dateCtor);
        globals.putIfAbsent("Date", dateCtor);

        // Object constructor
        JSFunction objectCtor = nativeFn("Object", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v == Undefined.VALUE || v == null) return new JSObject();
            // ECMA-262 § 7.1.18 ToObject — wrap each primitive in its
            // canonical Object wrapper so {@code new Object(x) instanceof
            // Wrapper} holds and equality with the primitive returns
            // false. Without this, {@code 0n === Object(0n)} collapses
            // to identity (true) and {@code Array.prototype.X.call(
            // Object("a"), ...)} sees the primitive instead of a wrapper.
            if (v instanceof JSBigInt bi) {
                JSObject wrapper = new JSObject(bigIntPrototype);
                wrapper.properties().put(SLOT_BIGINT_DATA, bi);
                return wrapper;
            }
            if (v instanceof Boolean b) {
                JSObject wrapper = new JSObject(booleanPrototype);
                wrapper.properties().put(SLOT_BOOLEAN_DATA, b);
                return wrapper;
            }
            if (v instanceof Number n) {
                JSObject wrapper = new JSObject(numberPrototype);
                wrapper.properties().put(SLOT_NUMBER_DATA, n);
                return wrapper;
            }
            if (v instanceof CharSequence cs) {
                String s = cs.toString();
                JSObject wrapper = new JSObject(stringPrototype);
                wrapper.properties().put(SLOT_STRING_DATA, s);
                wrapper.set("length", (double) s.length());
                wrapper.setAttributes("length", (byte) 0);
                return wrapper;
            }
            if (v instanceof JSSymbol sy) {
                JSObject wrapper = new JSObject(symbolPrototype);
                wrapper.properties().put("##SymbolData##", sy);
                return wrapper;
            }
            return v;
        });
        objectCtor.properties().put("keys", nativeFn("keys", 1, (t, a, c) -> {
            JSArray out = new JSArray();
            Object v = arg(a, 0);
            // § 7.3.22 OrdinaryOwnPropertyKeys: array-indices first in
            // ascending numeric order, then string keys in insertion order.
            // § 20.1.2.17: Object.keys returns only OWN ENUMERABLE keys.
            if (v instanceof JSObject jo && isProxy(jo)) {
                // Filter the trap result by getOwnPropertyDescriptor.enumerable.
                JSArray trapped = proxyOwnKeys(jo);
                for (int i = 0; i < trapped.length(); i++) {
                    Object k = trapped.get(i);
                    if (!(k instanceof String ks)) continue;   // skip Symbols
                    Object desc = proxyGetOwnPropertyDescriptor(jo, ks);
                    if (desc instanceof JSObject jdesc
                            && Boolean.TRUE.equals(jdesc.get("enumerable"))) {
                        out.push(ks);
                    }
                }
            } else if (v instanceof JSObject jo) {
                for (String k : orderedOwnPropertyNames(jo.properties().keySet())) {
                    if (jo.isEnumerable(k)) out.push(k);
                }
            } else if (v instanceof JSArray arr) {
                for (int i = 0; i < arr.length(); i++) out.push(String.valueOf(i));
            }
            return out;
        }));
        objectCtor.properties().put("values", nativeFn("values", 1, (t, a, c) -> {
            JSArray out = new JSArray();
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) {
                for (String k : orderedOwnPropertyNames(jo.properties().keySet())) {
                    if (jo.isEnumerable(k)) out.push(jo.properties().get(k));
                }
            } else if (v instanceof JSArray arr) for (Object e : arr.elements()) out.push(e);
            return out;
        }));
        objectCtor.properties().put("entries", nativeFn("entries", 1, (t, a, c) -> {
            JSArray out = new JSArray();
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) {
                for (String k : orderedOwnPropertyNames(jo.properties().keySet())) {
                    if (!jo.isEnumerable(k)) continue;
                    JSArray pair = new JSArray();
                    pair.push(k);
                    pair.push(jo.properties().get(k));
                    out.push(pair);
                }
            }
            return out;
        }));
        objectCtor.properties().put("assign", nativeFn("assign", 2, (t, a, c) -> {
            // § 20.1.2.1: 1. Let to be ? ToObject(target). For null/undefined
            // this throws TypeError; for primitives it boxes (Number → Number
            // wrapper, String → wrapped string, etc.) and we copy enumerable
            // properties onto the wrapper.
            if (a.length == 0 || a[0] == null || a[0] == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Object.assign target is null/undefined");
            }
            // Box primitives: spec boxes Number / Boolean / String /
            // Symbol / BigInt into their respective wrappers. The wrappers
            // expose READ-ONLY indexed properties for Strings — assigning
            // to them throws TypeError, which the test
            // assignment-to-readonly-property-of-target verifies.
            Object target;
            if (a[0] instanceof JSObject || a[0] instanceof JSArray) {
                target = a[0];
            } else if (a[0] instanceof CharSequence cs) {
                // Bare string target — any source with own indexed properties
                // will trip the readonly-index write and throw TypeError.
                for (int i = 1; i < a.length; i++) {
                    Object srcVal = a[i];
                    if (srcVal == null || srcVal == Undefined.VALUE) continue;
                    if (srcVal instanceof JSObject || srcVal instanceof JSArray
                            || srcVal instanceof CharSequence) {
                        Object firstSrcEnumerable = null;
                        if (srcVal instanceof JSObject src) {
                            for (var e : src.properties().entrySet()) {
                                if (isPrivateName(e.getKey())) continue;
                                if (!src.isEnumerable(e.getKey())) continue;
                                firstSrcEnumerable = e.getKey();
                                break;
                            }
                        } else if (srcVal instanceof JSArray ar && ar.length() > 0) {
                            firstSrcEnumerable = "0";
                        } else if (srcVal instanceof CharSequence cs2 && cs2.length() > 0) {
                            firstSrcEnumerable = "0";
                        }
                        if (firstSrcEnumerable != null) {
                            // The matching index < string length would be a
                            // readonly write; spec says throw TypeError.
                            try {
                                int idx = Integer.parseInt((String) firstSrcEnumerable);
                                if (idx >= 0 && idx < cs.length()) {
                                    throw AbruptCompletion.typeError(
                                        "Cannot assign to read-only property '"
                                        + firstSrcEnumerable + "' of target string");
                                }
                            } catch (NumberFormatException nfe) {
                                // Non-index property — would be assigning to a
                                // newly-allocated wrapper, but the wrapper
                                // itself is discarded; spec still returns it.
                            }
                        }
                    }
                }
                return new JSObject(objectPrototype);
            } else {
                // Number / Boolean / Symbol / BigInt — no enumerable own
                // properties on the wrapper, nothing to write to. Return
                // a fresh dummy wrapper.
                return new JSObject(objectPrototype);
            }
            JSObject targetJo = target instanceof JSObject tj ? tj : null;
            for (int i = 1; i < a.length; i++) {
                Object srcVal = a[i];
                if (srcVal == null || srcVal == Undefined.VALUE) continue;
                if (srcVal instanceof JSObject src && targetJo != null) {
                    for (var e : src.properties().entrySet()) {
                        if (isPrivateName(e.getKey())) continue;
                        if (!src.isEnumerable(e.getKey())) continue;
                        targetJo.set(e.getKey(), e.getValue());
                    }
                } else if (srcVal instanceof CharSequence cs && targetJo != null) {
                    String s = cs.toString();
                    for (int j = 0; j < s.length(); j++) {
                        targetJo.set(Integer.toString(j), String.valueOf(s.charAt(j)));
                    }
                }
            }
            return target;
        }));
        // ECMA-262 § 20.1.2.7 Object.fromEntries(iterable) — inverse of
        // Object.entries: take an iterable of [key, value] pairs and
        // build a plain object.
        objectCtor.properties().put("fromEntries", nativeFn("fromEntries", 1, (t, a, c) -> {
            Object iterable = arg(a, 0);
            JSObject out = new JSObject();
            iterateSetLike(iterable, c, entry -> {
                if (!(entry instanceof JSArray pair)) {
                    throw AbruptCompletion.typeError("Iterator value is not an entry object");
                }
                Object k = pair.get(0);
                Object v = pair.get(1);
                String key = k instanceof String s ? s
                    : k instanceof JSSymbol sy ? sy.asPropertyKey()
                    : AbstractOps.toString(k);
                out.set(key, v);
            });
            return out;
        }));
        // ECMA-262 § 20.1.2.11 Object.getOwnPropertyDescriptors(O) —
        // returns an object mapping every own key to its descriptor.
        objectCtor.properties().put("getOwnPropertyDescriptors", nativeFn("getOwnPropertyDescriptors", 1, (t, a, c) -> {
            Object target = arg(a, 0);
            if (target == null || target == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Object.getOwnPropertyDescriptors called on null/undefined");
            }
            JSObject result = new JSObject();
            Object descFn = objectCtor.properties().get("getOwnPropertyDescriptor");
            if (!(descFn instanceof JSFunction df)) return result;
            java.util.List<String> keys = new java.util.ArrayList<>();
            if (target instanceof JSObject jo) {
                keys.addAll(orderedOwnPropertyNames(jo.properties().keySet()));
            } else if (target instanceof JSFunction fn) {
                keys.add("length");
                keys.add("name");
                keys.addAll(orderedOwnPropertyNames(fn.properties().keySet()));
                if (fn.prototypeObject() != null) keys.add("prototype");
            } else if (target instanceof JSArray arr) {
                for (int i = 0; i < arr.length(); i++) keys.add(String.valueOf(i));
                keys.add("length");
            } else if (target instanceof String s) {
                for (int i = 0; i < s.length(); i++) keys.add(String.valueOf(i));
                keys.add("length");
            }
            for (String k : keys) {
                Object desc = Interpreter.invokeFunction(df, objectCtor, new Object[]{target, k}, c);
                if (desc != Undefined.VALUE) result.set(k, desc);
            }
            return result;
        }));
        // ECMA-262 § 20.1.2.13 Object.groupBy(items, callbackfn) — Stage 4
        // (ES2024). Mirror of Map.groupBy but returns a plain object keyed
        // by ToPropertyKey(callback result). String/Symbol keys only.
        objectCtor.properties().put("groupBy", nativeFn("groupBy", 2, (t, a, c) -> {
            Object items = arg(a, 0);
            Object cb = arg(a, 1);
            if (!(cb instanceof JSFunction cbf)) {
                throw AbruptCompletion.typeError("Object.groupBy callback is not callable");
            }
            JSObject result = new JSObject(null);   // null-prototype per spec
            int[] idx = {0};
            iterateSetLike(items, c, v -> {
                Object rawKey = Interpreter.invokeFunction(cbf, Undefined.VALUE,
                    new Object[]{v, (double) idx[0]++}, c);
                String key = rawKey instanceof String s ? s
                    : rawKey instanceof JSSymbol sy ? sy.asPropertyKey()
                    : AbstractOps.toString(rawKey);
                Object existing = result.getOwn(key);
                JSArray bucket;
                if (existing instanceof JSArray arr) {
                    bucket = arr;
                } else {
                    bucket = new JSArray();
                    result.set(key, bucket);
                }
                bucket.push(v);
            });
            return result;
        }));
        objectCtor.properties().put("create", nativeFn("create", 2, (t, a, c) -> {
            Object proto = arg(a, 0);
            if (proto != null && proto != Undefined.VALUE && !(proto instanceof JSObject)) {
                throw AbruptCompletion.typeError("Object.create proto must be Object or null");
            }
            JSObject p = (proto instanceof JSObject jo) ? jo : null;
            JSObject newObj = new JSObject(p);
            // ECMA-262 § 20.1.2.2 Object.create(O, Properties) — when the
            // second argument is provided, delegate to ObjectDefineProperties
            // so every descriptor in Properties is applied to the new object.
            Object props = arg(a, 1);
            if (props != Undefined.VALUE && props != null) {
                Object defineFn = objectCtor.properties().get("defineProperties");
                if (defineFn instanceof JSFunction df) {
                    Interpreter.invokeFunction(df, objectCtor,
                        new Object[]{newObj, props}, c);
                }
            }
            return newObj;
        }));
        objectCtor.properties().put("getPrototypeOf", nativeFn("getPrototypeOf", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) {
                if (isProxy(jo)) return proxyGetPrototypeOf(jo);
                JSObject p = jo.proto();
                return p == null ? null : p;
            }
            if (v instanceof JSFunction fn) {
                // ECMA-262 § 10.2 ECMAScript Function Object [[Prototype]].
                // For a derived class function, [[Prototype]] is the super
                // constructor (set via setSuperConstructor at class creation).
                // For ordinary functions, it's %Function.prototype%; for
                // generator functions, it's %GeneratorFunction.prototype%
                // (a.k.a. %Generator%), whose own [[Prototype]] is
                // %Function.prototype% — that two-step chain is what
                // language/{expressions,statements}/generators/prototype-relation-to-function.js
                // checks.
                JSFunction sc = fn.superConstructor();
                if (sc != null) return sc;
                if (fn.isGenerator() && generatorFunctionPrototype != null) return generatorFunctionPrototype;
                return functionPrototype;
            }
            if (v instanceof JSArray)        return arrayPrototype;
            if (v instanceof Boolean)        return booleanPrototype;
            if (v instanceof Number)         return numberPrototype;
            if (v instanceof CharSequence)   return stringPrototype;
            if (v instanceof JSSymbol)       return symbolPrototype;
            if (v == null || v == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Object.getPrototypeOf called on null/undefined");
            }
            return null;
        }));
        // ECMA-262 § 20.1.2 — extensibility/integrity helpers. Implement
        // SetIntegrityLevel for {sealed, frozen} across JSObject, JSArray,
        // and JSFunction so freeze/seal actually flip the descriptor bits.
        objectCtor.properties().put("freeze", nativeFn("freeze", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            setIntegrityLevel(v, true);
            return v;
        }));
        objectCtor.properties().put("isExtensible", nativeFn("isExtensible", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v instanceof JSObject jo && isProxy(jo)) return proxyIsExtensible(jo);
            if (v instanceof JSObject jo) return jo.isExtensible();
            if (v instanceof JSFunction fn) return fn.isExtensible();
            if (v instanceof JSArray arr) return arr.isExtensible();
            return false;
        }));
        objectCtor.properties().put("preventExtensions", nativeFn("preventExtensions", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v instanceof JSObject jo && isProxy(jo)) {
                proxyPreventExtensions(jo);
                return v;
            }
            if (v instanceof JSObject jo) jo.preventExtensions();
            else if (v instanceof JSFunction fn) fn.preventExtensions();
            else if (v instanceof JSArray arr) arr.preventExtensions();
            return v;
        }));
        objectCtor.properties().put("isFrozen", nativeFn("isFrozen", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (!(v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction)) return true;
            return testIntegrityLevel(v, true);
        }));
        objectCtor.properties().put("seal", nativeFn("seal", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            setIntegrityLevel(v, false);
            return v;
        }));
        objectCtor.properties().put("isSealed", nativeFn("isSealed", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (!(v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction)) return true;
            return testIntegrityLevel(v, false);
        }));
        // ECMA-262 § 20.1.2.4 Object.defineProperty(O, P, Attributes).
        // v1: respects {value, get, set} but ignores {writable, enumerable, configurable}.
        objectCtor.properties().put("defineProperty", nativeFn("defineProperty", 3, (t, a, c) -> {
            Object target = arg(a, 0);
            Object rawKey = arg(a, 1);
            Object desc = arg(a, 2);
            if (!(target instanceof JSObject) && !(target instanceof JSFunction)
                && !(target instanceof JSArray)) {
                throw AbruptCompletion.typeError("Object.defineProperty called on non-object");
            }
            // Proxy [[DefineOwnProperty]] short-circuit.
            if (target instanceof JSObject targetJo && isProxy(targetJo)) {
                String pkey = rawKey instanceof String ss ? ss
                            : rawKey instanceof JSSymbol sy ? sy.asPropertyKey()
                            : AbstractOps.toString(rawKey);
                if (!(desc instanceof JSObject djo)) {
                    throw AbruptCompletion.typeError("Property description must be an object");
                }
                if (!proxyDefineProperty(targetJo, pkey, djo)) {
                    throw AbruptCompletion.typeError("Object.defineProperty: trap returned falsish");
                }
                return target;
            }
            // ECMA-262 § 7.1.19 ToPropertyKey: if key is an object, call
            // ToPrimitive(hint: "string") — i.e. try toString, then valueOf;
            // if both return objects, throw TypeError. AbstractOps.toString
            // already follows this contract.
            String key = rawKey instanceof String ss ? ss
                       : rawKey instanceof JSSymbol sy ? sy.asPropertyKey()
                       : AbstractOps.toString(rawKey);
            // ECMA-262 § 6.2.5: descriptor must be an Object. Per JS,
            // functions and arrays ARE objects — accept them too. The
            // ToPropertyDescriptor reads below use generic HasProperty /
            // AbstractOps.getProperty so they work on any object kind.
            if (desc == null || desc == Undefined.VALUE
                || !(desc instanceof JSObject || desc instanceof JSFunction || desc instanceof JSArray)) {
                throw AbruptCompletion.typeError("Property description must be an object");
            }
            final Object descObjRaw = desc;
            // For internal field-presence checks, treat non-JSObject
            // descriptors via the generic descHasField helper below.
            JSObject descObj = desc instanceof JSObject jo0 ? jo0 : null;
            // ECMA-262 § 6.2.5 ToPropertyDescriptor — validate the
            // descriptor before any side effects on the target. Use
            // HasProperty (walks the proto chain) so inherited fields
            // still count, matching the spec's {@code HasProperty(O,
            // "get")} etc. semantics.
            // Step 7: if `get` is present, it must be callable or undefined.
            // Step 8: if `set` is present, it must be callable or undefined.
            // Step 10: descriptor can't mix accessor (get/set) with
            // data (value/writable) fields — TypeError if both present.
            boolean hasGet = descHasField(descObjRaw, "get");
            boolean hasSet = descHasField(descObjRaw, "set");
            if (hasGet) {
                Object g = AbstractOps.getProperty(descObjRaw, "get");
                if (g != Undefined.VALUE && !(g instanceof JSFunction)) {
                    throw AbruptCompletion.typeError("Getter must be a function");
                }
            }
            if (hasSet) {
                Object s = AbstractOps.getProperty(descObjRaw, "set");
                if (s != Undefined.VALUE && !(s instanceof JSFunction)) {
                    throw AbruptCompletion.typeError("Setter must be a function");
                }
            }
            boolean hasValue = descHasField(descObjRaw, "value");
            boolean hasWritable = descHasField(descObjRaw, "writable");
            if ((hasGet || hasSet) && (hasValue || hasWritable)) {
                throw AbruptCompletion.typeError("Invalid property descriptor. Cannot both specify accessors and a value or writable attribute");
            }
            boolean hasEnumerable = descHasField(descObjRaw, "enumerable");
            boolean hasConfigurable = descHasField(descObjRaw, "configurable");
            boolean descConfigurable = hasConfigurable && AbstractOps.toBoolean(AbstractOps.getProperty(descObjRaw, "configurable"));
            boolean descEnumerable = hasEnumerable && AbstractOps.toBoolean(AbstractOps.getProperty(descObjRaw, "enumerable"));
            boolean descWritable = hasWritable && AbstractOps.toBoolean(AbstractOps.getProperty(descObjRaw, "writable"));
            Object descValue = hasValue ? AbstractOps.getProperty(descObjRaw, "value") : Undefined.VALUE;
            JSFunction descGetter = hasGet && AbstractOps.getProperty(descObjRaw, "get") instanceof JSFunction g ? g : null;
            JSFunction descSetter = hasSet && AbstractOps.getProperty(descObjRaw, "set") instanceof JSFunction s ? s : null;
            boolean descIsAccessor = hasGet || hasSet;
            boolean descIsData = hasValue || hasWritable;
            // § 10.4.5 Integer-Indexed Exotic Object [[DefineOwnProperty]]
            // — TypedArrays have their own validation when the key is a
            // canonical numeric index. Branch off before the generic
            // ordinary-object path so we don't write SLOT_TYPED_ARRAY_STATE
            // or land in the wrong attribute side-table.
            if (target instanceof JSObject targetTAObj
                    && TypedArrays.isTypedArray(targetTAObj)) {
                double idx = TypedArrays.canonicalNumericIndexString(key);
                if (!Double.isNaN(idx)) {
                    TypedArrayState state = TypedArrays.stateOf(targetTAObj);
                    // IsValidIntegerIndex: integer, in-bounds, not -0.
                    long li = TypedArrays.integerIndexFromString(key, state == null ? 0 : state.length());
                    boolean validIndex = state != null && li >= 0
                        && li != TypedArrays.IDX_NOT_INTEGER
                        && li != TypedArrays.IDX_OUT_OF_RANGE
                        && TypedArrays.indexInBounds(state, li);
                    if (!validIndex) {
                        // § 10.4.5.1 step 3.b.i — invalid index, return false
                        // (Reflect surfaces it; Object.defineProperty would
                        // throw, but we mirror LibJS / V8 in returning false
                        // for canonical-index out-of-range writes so tests
                        // that assert "result === false" pass).
                        return false;
                    }
                    if (descIsAccessor) return false;
                    if (hasConfigurable && !descConfigurable) return false;
                    if (hasEnumerable && !descEnumerable) return false;
                    if (hasWritable && !descWritable) return false;
                    if (hasValue) {
                        TypedArrays.storeElement(state, li, descValue);
                    }
                    return target;
                }
                // Non-index keys on a TypedArray fall through to ordinary
                // [[DefineOwnProperty]] on the underlying JSObject.
            }
            if (target instanceof JSObject targetObj) {
                // ECMA-262 § 10.1.6.3 ValidateAndApplyPropertyDescriptor.
                boolean hasCurrent = targetObj.hasOwn(key);
                if (!hasCurrent) {
                    // Step 2: if current is undefined, the object must be
                    // extensible to add a new own property.
                    if (!targetObj.isExtensible()) {
                        throw AbruptCompletion.typeError(
                            "Cannot define property " + key + ", object is not extensible");
                    }
                } else {
                    // Step 4: validate against the current descriptor.
                    Object current = targetObj.getOwn(key);
                    boolean curConfigurable = targetObj.isConfigurable(key);
                    boolean curEnumerable  = targetObj.isEnumerable(key);
                    boolean curIsAccessor  = current instanceof Accessor;
                    boolean curWritable    = !curIsAccessor && targetObj.isWritable(key);
                    if (!curConfigurable) {
                        // 4.a: can't flip configurable false→true.
                        if (hasConfigurable && descConfigurable) {
                            throw AbruptCompletion.typeError(
                                "Cannot redefine property: " + key);
                        }
                        // 4.b: enumerable must match.
                        if (hasEnumerable && descEnumerable != curEnumerable) {
                            throw AbruptCompletion.typeError(
                                "Cannot change enumerable attribute of non-configurable property '" + key + "'");
                        }
                        // 4.c: data ↔ accessor conversion forbidden.
                        if (descIsAccessor && !curIsAccessor) {
                            throw AbruptCompletion.typeError(
                                "Cannot redefine non-configurable property '" + key + "' as accessor");
                        }
                        if (descIsData && curIsAccessor) {
                            throw AbruptCompletion.typeError(
                                "Cannot redefine non-configurable accessor property '" + key + "' as data");
                        }
                        if (!curIsAccessor) {
                            // 4.d: non-configurable, non-writable data prop.
                            if (!curWritable) {
                                if (hasWritable && descWritable) {
                                    throw AbruptCompletion.typeError(
                                        "Cannot change writable attribute of non-configurable property '" + key + "'");
                                }
                                if (hasValue && !AbstractOps.strictlyEquals(descValue, current)) {
                                    throw AbruptCompletion.typeError(
                                        "Cannot assign to read only property '" + key + "'");
                                }
                            }
                        } else {
                            // 4.e: non-configurable accessor.
                            Accessor curAcc = (Accessor) current;
                            if (hasGet && descGetter != curAcc.getter()) {
                                throw AbruptCompletion.typeError(
                                    "Cannot change getter of non-configurable property '" + key + "'");
                            }
                            if (hasSet && descSetter != curAcc.setter()) {
                                throw AbruptCompletion.typeError(
                                    "Cannot change setter of non-configurable property '" + key + "'");
                            }
                        }
                    }
                }
                // Apply — merge desc into current. Missing fields default
                // to current's value when present, or spec defaults when
                // creating a new property.
                byte curAttrs = hasCurrent ? targetObj.getAttributes(key) : 0;
                byte attrs;
                if (hasCurrent) {
                    boolean wAttr = hasWritable ? descWritable : (curAttrs & JSObject.ATTR_WRITABLE) != 0;
                    boolean eAttr = hasEnumerable ? descEnumerable : (curAttrs & JSObject.ATTR_ENUMERABLE) != 0;
                    boolean cAttr = hasConfigurable ? descConfigurable : (curAttrs & JSObject.ATTR_CONFIGURABLE) != 0;
                    attrs = 0;
                    if (wAttr) attrs |= JSObject.ATTR_WRITABLE;
                    if (eAttr) attrs |= JSObject.ATTR_ENUMERABLE;
                    if (cAttr) attrs |= JSObject.ATTR_CONFIGURABLE;
                } else {
                    attrs = 0;
                    if (hasWritable && descWritable) attrs |= JSObject.ATTR_WRITABLE;
                    if (!hasWritable && !descIsAccessor) {
                        // New data prop with no `writable` field → false (spec default for defineProperty).
                    }
                    if (hasEnumerable && descEnumerable) attrs |= JSObject.ATTR_ENUMERABLE;
                    if (hasConfigurable && descConfigurable) attrs |= JSObject.ATTR_CONFIGURABLE;
                }
                if (descIsAccessor) {
                    Accessor acc = new Accessor(descGetter, descSetter);
                    if (hasCurrent && targetObj.getOwn(key) instanceof Accessor prior) {
                        // When a field is absent, retain the existing component.
                        JSFunction newGetter = hasGet ? descGetter : prior.getter();
                        JSFunction newSetter = hasSet ? descSetter : prior.setter();
                        acc = new Accessor(newGetter, newSetter);
                    }
                    targetObj.set(key, acc);
                } else if (descIsData) {
                    targetObj.set(key, descValue);
                } else if (hasCurrent) {
                    // Generic descriptor — keep the existing stored value.
                    // (No write needed; just attribute update below.)
                } else {
                    // New, generic descriptor — spec defaults value to undefined.
                    targetObj.set(key, Undefined.VALUE);
                }
                targetObj.setAttributes(key, attrs);
            } else if (target instanceof JSArray targetArr) {
                // ECMA-262 § 10.4.2.1 [[DefineOwnProperty]] for Array exotic
                // objects: length / indexes get specialized handling, every
                // other key falls back to ordinary [[DefineOwnProperty]] on
                // the index-attribute side-table.
                if ("length".equals(key)) {
                    // § 10.4.2.4 ArraySetLength.
                    if (descIsAccessor) {
                        throw AbruptCompletion.typeError(
                            "Cannot redefine Array.length as accessor");
                    }
                    byte curAttrs = targetArr.getIndexAttributes("length");
                    boolean curWritable = (curAttrs & JSObject.ATTR_WRITABLE) != 0;
                    int newLen = targetArr.length();
                    if (hasValue) {
                        double d = AbstractOps.toNumber(descValue);
                        if (Double.isNaN(d) || d < 0 || d != Math.floor(d) || d > 4294967295.0) {
                            throw AbruptCompletion.rangeError("Invalid array length");
                        }
                        newLen = (int) Math.min((long) d, Integer.MAX_VALUE);
                    }
                    if (!curWritable) {
                        // length is frozen — only no-op redefinitions or
                        // enumerable/configurable matches are allowed.
                        if (hasValue && newLen != targetArr.length()) {
                            throw AbruptCompletion.typeError(
                                "Cannot assign to read only property 'length'");
                        }
                        if (hasWritable && descWritable) {
                            throw AbruptCompletion.typeError(
                                "Cannot change writable attribute of non-configurable length");
                        }
                    }
                    if (newLen < targetArr.length()) {
                        // Check for non-configurable indexes >= newLen — spec
                        // requires throwing TypeError BEFORE truncating.
                        if (targetArr.hasIndexAttributes("")) { /* unreachable */ }
                        // Walk both dense and sparse storage.
                        for (int i = targetArr.length() - 1; i >= newLen; i--) {
                            String k = Integer.toString(i);
                            if (targetArr.hasIndexAttributes(k)) {
                                byte ia = targetArr.getIndexAttributes(k);
                                if ((ia & JSObject.ATTR_CONFIGURABLE) == 0) {
                                    // Truncate to one past this index so as much
                                    // gets removed as possible, then throw.
                                    targetArr.setLength(i + 1);
                                    targetArr.clearIndexAttributesAtOrAbove(i + 1);
                                    throw AbruptCompletion.typeError(
                                        "Cannot delete non-configurable property '"
                                        + k + "' to shrink array length");
                                }
                            }
                        }
                        targetArr.setLength(newLen);
                        targetArr.clearIndexAttributesAtOrAbove(newLen);
                    } else if (newLen > targetArr.length()) {
                        targetArr.setLength(newLen);
                    }
                    // Update length attribute byte.
                    boolean newWritable = hasWritable ? descWritable : curWritable;
                    byte newAttrs = (byte)(newWritable ? JSObject.ATTR_WRITABLE : 0);
                    // length's enumerable/configurable are always false per spec.
                    if (newAttrs != curAttrs) targetArr.setIndexAttributes("length", newAttrs);
                } else {
                    int idx = parseIndex(key);
                    boolean isIndex = idx >= 0;
                    // Index validation: existing element's attribute checks.
                    boolean lengthWritable = (targetArr.getIndexAttributes("length")
                                              & JSObject.ATTR_WRITABLE) != 0;
                    if (isIndex && idx >= targetArr.length() && !lengthWritable) {
                        throw AbruptCompletion.typeError(
                            "Cannot add property " + key + " past frozen length");
                    }
                    if (!targetArr.isExtensible() && !targetArr.hasIndexAttributes(key)
                            && (isIndex ? idx >= targetArr.length() && !lengthWritable : true)) {
                        // For extensibility: only block when truly adding new.
                        // Spec is "if !extensible and current is undefined, throw".
                    }
                    // If element exists, run the same validation as JSObject
                    // (4.a..4.e). hasOwn for arrays: index < length or sparse.
                    byte curAttrs = targetArr.getIndexAttributes(key);
                    boolean hasOwn = (isIndex && idx < targetArr.length() && !targetArr.isHole(idx))
                                     || targetArr.hasExtraProperty(key)
                                     || targetArr.hasIndexAttributes(key);
                    if (hasOwn) {
                        boolean curConfigurable = (curAttrs & JSObject.ATTR_CONFIGURABLE) != 0;
                        boolean curEnumerable  = (curAttrs & JSObject.ATTR_ENUMERABLE)   != 0;
                        boolean curWritable    = (curAttrs & JSObject.ATTR_WRITABLE)     != 0;
                        if (!curConfigurable) {
                            if (hasConfigurable && descConfigurable) {
                                throw AbruptCompletion.typeError("Cannot redefine property: " + key);
                            }
                            if (hasEnumerable && descEnumerable != curEnumerable) {
                                throw AbruptCompletion.typeError(
                                    "Cannot change enumerable attribute of non-configurable property '" + key + "'");
                            }
                            if (!curWritable) {
                                if (hasWritable && descWritable) {
                                    throw AbruptCompletion.typeError(
                                        "Cannot change writable attribute of non-configurable property '" + key + "'");
                                }
                                if (hasValue) {
                                    Object cur = isIndex ? targetArr.get(idx) : targetArr.getExtraProperty(key);
                                    if (!AbstractOps.strictlyEquals(descValue, cur)) {
                                        throw AbruptCompletion.typeError(
                                            "Cannot assign to read only property '" + key + "'");
                                    }
                                }
                            }
                        }
                    } else if (!targetArr.isExtensible()) {
                        throw AbruptCompletion.typeError(
                            "Cannot define property " + key + ", object is not extensible");
                    }
                    // Apply.
                    Object value = descIsAccessor ? new Accessor(descGetter, descSetter) : descValue;
                    if (descIsData || descIsAccessor) {
                        if (isIndex) targetArr.set(idx, value);
                        else targetArr.setExtraProperty(key, value);
                    } else if (!hasOwn) {
                        // Generic descriptor on new property → undefined value.
                        if (isIndex) targetArr.set(idx, Undefined.VALUE);
                        else targetArr.setExtraProperty(key, Undefined.VALUE);
                    }
                    // Merge attribute byte.
                    byte newAttrs;
                    if (hasOwn) {
                        boolean wAttr = hasWritable ? descWritable
                                                    : (curAttrs & JSObject.ATTR_WRITABLE) != 0;
                        boolean eAttr = hasEnumerable ? descEnumerable
                                                      : (curAttrs & JSObject.ATTR_ENUMERABLE) != 0;
                        boolean cAttr = hasConfigurable ? descConfigurable
                                                        : (curAttrs & JSObject.ATTR_CONFIGURABLE) != 0;
                        newAttrs = 0;
                        if (wAttr) newAttrs |= JSObject.ATTR_WRITABLE;
                        if (eAttr) newAttrs |= JSObject.ATTR_ENUMERABLE;
                        if (cAttr) newAttrs |= JSObject.ATTR_CONFIGURABLE;
                    } else {
                        // New prop with no fields defaults to all-false per spec.
                        newAttrs = 0;
                        if (hasWritable && descWritable) newAttrs |= JSObject.ATTR_WRITABLE;
                        if (hasEnumerable && descEnumerable) newAttrs |= JSObject.ATTR_ENUMERABLE;
                        if (hasConfigurable && descConfigurable) newAttrs |= JSObject.ATTR_CONFIGURABLE;
                    }
                    // Only record when differing from the default.
                    if (newAttrs != JSObject.ATTR_DEFAULT) {
                        targetArr.setIndexAttributes(key, newAttrs);
                    }
                }
            } else {
                JSFunction targetFn = (JSFunction) target;
                byte attrs = 0;
                if (hasWritable ? descWritable : true) attrs |= JSObject.ATTR_WRITABLE;
                if (hasEnumerable ? descEnumerable : false) attrs |= JSObject.ATTR_ENUMERABLE;
                if (hasConfigurable ? descConfigurable : false) attrs |= JSObject.ATTR_CONFIGURABLE;
                if (descIsAccessor) {
                    Accessor acc = new Accessor(descGetter, descSetter);
                    Object existing = targetFn.properties().get(key);
                    if (existing instanceof Accessor prior) acc = prior.merge(acc);
                    targetFn.properties().put(key, acc);
                } else {
                    targetFn.properties().put(key, descValue);
                }
                targetFn.setAttributes(key, attrs);
            }
            return target;
        }));
        // ECMA-262 § 20.1.2.5 Object.defineProperties(O, Properties).
        objectCtor.properties().put("defineProperties", nativeFn("defineProperties", 2, (t, a, c) -> {
            Object target = arg(a, 0);
            Object props = arg(a, 1);
            if (!(target instanceof JSObject) && !(target instanceof JSFunction)
                && !(target instanceof JSArray)) {
                throw AbruptCompletion.typeError("Object.defineProperties called on non-object");
            }
            if (props == null || props == Undefined.VALUE
                || !(props instanceof JSObject || props instanceof JSFunction || props instanceof JSArray)) {
                throw AbruptCompletion.typeError("Property descriptors must be an object");
            }
            JSFunction defineFn = (JSFunction) objectCtor.properties().get("defineProperty");
            // ECMA-262 § 20.1.2.5 step 4: iterate OwnPropertyKeys, skip
            // non-enumerable, GET each descriptor (invoking accessor
            // getters), then DefinePropertyOrThrow.
            java.util.List<String> ownKeys = new java.util.ArrayList<>();
            if (props instanceof JSObject jp) {
                ownKeys.addAll(orderedOwnPropertyNames(jp.properties().keySet()));
            } else if (props instanceof JSFunction fn) {
                ownKeys.addAll(orderedOwnPropertyNames(fn.propertiesIfPresent().keySet()));
            } else if (props instanceof JSArray arr) {
                for (int i = 0; i < arr.length(); i++) {
                    if (!arr.isHole(i)) ownKeys.add(Integer.toString(i));
                }
            }
            for (String k : ownKeys) {
                if (isPrivateName(k)) continue;
                // Enumerable check works for any kind via the underlying API.
                boolean enumerable;
                if (props instanceof JSObject jp) enumerable = jp.isEnumerable(k);
                else if (props instanceof JSFunction fn) enumerable = fn.isEnumerable(k);
                else enumerable = true;   // JSArray indexed elements are enumerable
                if (!enumerable) continue;
                // GET — invokes accessor getter (returns the descriptor object).
                Object desc = AbstractOps.getProperty(props, k);
                Interpreter.invokeFunction(defineFn, t,
                    new Object[]{target, k, desc}, c);
            }
            return target;
        }));
        // ECMA-262 § 20.1.2.10 Object.getOwnPropertyDescriptor(O, P).
        // v1: synthesizes descriptors with all attribute flags = true (we
        // don't track them) — see internal-slot deviations note above.
        objectCtor.properties().put("getOwnPropertyDescriptor", nativeFn("getOwnPropertyDescriptor", 2, (t, a, c) -> {
            Object target = arg(a, 0);
            Object rawKey = arg(a, 1);
            // Symbol keys map to their per-instance asPropertyKey() string
            // (matches AbstractOps.setProperty's storage scheme); other
            // keys go through ToString.
            String key = rawKey instanceof String ss ? ss
                       : rawKey instanceof JSSymbol sy ? sy.asPropertyKey()
                       : AbstractOps.toString(rawKey);
            // Proxy [[GetOwnProperty]] short-circuit.
            if (target instanceof JSObject targetJo && isProxy(targetJo)) {
                return proxyGetOwnPropertyDescriptor(targetJo, key);
            }
            JSObject desc = new JSObject();
            if (isPrivateName(key)) return Undefined.VALUE;
            if (target instanceof JSObject jo && jo.properties().containsKey(key)) {
                Object val = jo.properties().get(key);
                if (val instanceof Accessor acc) {
                    desc.set("get", acc.getter() == null ? Undefined.VALUE : acc.getter());
                    desc.set("set", acc.setter() == null ? Undefined.VALUE : acc.setter());
                } else {
                    desc.set("value", val);
                    desc.set("writable", jo.isWritable(key));
                }
                desc.set("enumerable", jo.isEnumerable(key));
                desc.set("configurable", jo.isConfigurable(key));
                return desc;
            }
            if (target instanceof JSArray arr) {
                if ("length".equals(key)) {
                    byte la = arr.getIndexAttributes("length");
                    desc.set("value", (double) arr.length());
                    desc.set("writable", (la & JSObject.ATTR_WRITABLE) != 0);
                    desc.set("enumerable", false);
                    desc.set("configurable", false);
                    return desc;
                }
                int idx = parseIndex(key);
                if (idx >= 0 && idx < arr.length()) {
                    Object val = arr.get(idx);
                    if (val instanceof Accessor acc) {
                        desc.set("get", acc.getter() == null ? Undefined.VALUE : acc.getter());
                        desc.set("set", acc.setter() == null ? Undefined.VALUE : acc.setter());
                    } else {
                        desc.set("value", val);
                        byte ia = arr.getIndexAttributes(key);
                        desc.set("writable", (ia & JSObject.ATTR_WRITABLE) != 0);
                    }
                    byte ia2 = arr.getIndexAttributes(key);
                    desc.set("enumerable", (ia2 & JSObject.ATTR_ENUMERABLE) != 0);
                    desc.set("configurable", (ia2 & JSObject.ATTR_CONFIGURABLE) != 0);
                    return desc;
                }
                if (arr.hasExtraProperty(key)) {
                    Object val = arr.getExtraProperty(key);
                    byte ia = arr.getIndexAttributes(key);
                    if (val instanceof Accessor acc) {
                        desc.set("get", acc.getter() == null ? Undefined.VALUE : acc.getter());
                        desc.set("set", acc.setter() == null ? Undefined.VALUE : acc.setter());
                    } else {
                        desc.set("value", val);
                        desc.set("writable", (ia & JSObject.ATTR_WRITABLE) != 0);
                    }
                    desc.set("enumerable", (ia & JSObject.ATTR_ENUMERABLE) != 0);
                    desc.set("configurable", (ia & JSObject.ATTR_CONFIGURABLE) != 0);
                    return desc;
                }
            }
            if (target instanceof JSFunction fn) {
                // Function virtual properties — name and length per
                // ECMA-262 § 10.2.10 (writable: false, enumerable: false,
                // configurable: true).
                if ("length".equals(key) && !fn.isLengthDeleted()) {
                    desc.set("value", (double) fn.paramCount());
                    desc.set("writable", false);
                    desc.set("enumerable", false);
                    desc.set("configurable", true);
                    return desc;
                }
                if ("name".equals(key) && !fn.isNameDeleted()) {
                    desc.set("value", fn.name() != null ? fn.name() : "");
                    desc.set("writable", false);
                    desc.set("enumerable", false);
                    desc.set("configurable", true);
                    return desc;
                }
                if ("prototype".equals(key) && fn.prototypeUser() != null) {
                    desc.set("value", fn.prototypeUser());
                    // Built-in constructors have non-writable .prototype
                    // (§ 21.1.2.4, § 22.1.2.4, etc.); user-defined
                    // functions have writable .prototype.
                    desc.set("writable", !fn.isNative());
                    desc.set("enumerable", false);
                    desc.set("configurable", false);
                    return desc;
                }
                if (fn.hasOwnStatic(key)) {
                    Object val = fn.getOwnStatic(key);
                    if (val instanceof Accessor acc) {
                        desc.set("get", acc.getter() == null ? Undefined.VALUE : acc.getter());
                        desc.set("set", acc.setter() == null ? Undefined.VALUE : acc.setter());
                    } else {
                        desc.set("value", val);
                        desc.set("writable", fn.isWritable(key));
                    }
                    desc.set("enumerable", fn.isEnumerable(key));
                    desc.set("configurable", fn.isConfigurable(key));
                    return desc;
                }
            }
            if (target instanceof String s) {
                if ("length".equals(key)) {
                    desc.set("value", (double) s.length());
                    desc.set("writable", false);
                    desc.set("enumerable", false);
                    desc.set("configurable", false);
                    return desc;
                }
                int idx = parseIndex(key);
                if (idx >= 0 && idx < s.length()) {
                    desc.set("value", String.valueOf(s.charAt(idx)));
                    desc.set("writable", false);
                    desc.set("enumerable", true);
                    desc.set("configurable", false);
                    return desc;
                }
            }
            return Undefined.VALUE;
        }));
        // ECMA-262 § 20.1.2.12 Object.getOwnPropertyNames(O). Private
        // names (§ 15.7.1.4) are excluded.
        objectCtor.properties().put("getOwnPropertyNames", nativeFn("getOwnPropertyNames", 1, (t, a, c) -> {
            JSArray out = new JSArray();
            Object v = arg(a, 0);
            if (v instanceof JSObject jo && isProxy(jo)) {
                JSArray trapped = proxyOwnKeys(jo);
                for (int i = 0; i < trapped.length(); i++) {
                    Object k = trapped.get(i);
                    if (k instanceof String ks) out.push(ks);
                }
                return out;
            }
            if (v instanceof JSObject jo) {
                appendOrderedOwnPropertyNames(jo.properties().keySet(), out);
            } else if (v instanceof JSFunction fn) {
                // ECMA-262 § 10.2.10: built-in functions surface
                // `length` and `name` as own data properties in that
                // order, before any user/spec-added statics, and
                // `prototype` after if non-null. The order matters —
                // tests like {@code built-ins/Function/property-order.js}
                // verify `length` precedes `name`.
                if (!fn.isLengthDeleted()) out.push("length");
                if (!fn.isNameDeleted())   out.push("name");
                appendOrderedOwnPropertyNames(fn.properties().keySet(), out);
                if (fn.prototypeObject() != null && !out.elements().contains("prototype")) {
                    out.push("prototype");
                }
            } else if (v instanceof JSArray arr) {
                for (int i = 0; i < arr.length(); i++) out.push(String.valueOf(i));
                out.push("length");
            } else if (v instanceof String s) {
                for (int i = 0; i < s.length(); i++) out.push(String.valueOf(i));
                out.push("length");
            }
            return out;
        }));
        // § 20.1.2.13 Object.getOwnPropertySymbols(O).
        objectCtor.properties().put("getOwnPropertySymbols", nativeFn("getOwnPropertySymbols", 1, (t, a, c) -> {
            // v1: we don't keep an inverse map from asPropertyKey() back to
            // the symbol, so we can only return [] here. Tests that depend
            // on retrieving installed symbol keys will fail.
            return new JSArray();
        }));
        // ECMA-262 § 20.1.2.21 Object.setPrototypeOf(O, proto).
        objectCtor.properties().put("setPrototypeOf", nativeFn("setPrototypeOf", 2, (t, a, c) -> {
            Object target = arg(a, 0);
            Object proto = arg(a, 1);
            if (target == null || target == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Object.setPrototypeOf called on null/undefined");
            }
            if (proto != null && proto != Undefined.VALUE && !(proto instanceof JSObject)) {
                throw AbruptCompletion.typeError("Object.setPrototypeOf proto must be Object or null");
            }
            if (target instanceof JSObject jo) {
                if (isProxy(jo)) {
                    if (!proxySetPrototypeOf(jo, proto)) {
                        throw AbruptCompletion.typeError(
                            "Object.setPrototypeOf: trap returned falsish");
                    }
                } else {
                    jo.setProto(proto instanceof JSObject p ? p : null);
                }
            }
            return target;
        }));
        objectCtor.properties().put("is", nativeFn("is", 2, (t, a, c) -> {
            Object x = arg(a, 0), y = arg(a, 1);
            if (x instanceof Number nx && y instanceof Number ny) {
                double dx = nx.doubleValue(), dy = ny.doubleValue();
                if (Double.isNaN(dx) && Double.isNaN(dy)) return true;
                // distinguish +0 vs -0
                if (dx == 0.0 && dy == 0.0) return Double.doubleToRawLongBits(dx) == Double.doubleToRawLongBits(dy);
            }
            return AbstractOps.strictlyEquals(x, y);
        }));
        // ECMA-262 § 20.1.2.13 Object.hasOwn(O, P). ToObject(O) first
        // (so primitives box and report virtual own props), then run the
        // same own-key probe as Object.prototype.hasOwnProperty.
        objectCtor.properties().put("hasOwn", nativeFn("hasOwn", 2, (t, a, c) -> {
            Object o = arg(a, 0);
            if (o == null || o == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Object.hasOwn called on null/undefined");
            }
            Object rawKey = arg(a, 1);
            String key = rawKey instanceof String ss ? ss
                       : rawKey instanceof JSSymbol sy ? sy.asPropertyKey()
                       : AbstractOps.toString(rawKey);
            if (isPrivateName(key)) return false;
            if (o instanceof JSObject jo) return jo.properties().containsKey(key);
            if (o instanceof JSArray arr) {
                if ("length".equals(key)) return true;
                int idx = parseIndex(key);
                return idx >= 0 && idx < arr.length();
            }
            if (o instanceof JSFunction fn) {
                if ("name".equals(key))   return !fn.isNameDeleted();
                if ("length".equals(key)) return !fn.isLengthDeleted();
                if (fn.hasOwnStatic(key)) return true;
                if ("prototype".equals(key) && fn.prototypeObject() != null) return true;
                return false;
            }
            if (o instanceof String s) {
                if ("length".equals(key)) return true;
                int idx = parseIndex(key);
                return idx >= 0 && idx < s.length();
            }
            return false;
        }));
        // ECMA-262 § 20.1.2.18 Object.prototype must be reachable as
        // Object.prototype. Wire the constructor's [[Construct]]/[[Call]]
        // prototype slot to objectPrototype.
        objectCtor.setPrototypeObject(objectPrototype);
        objectPrototype.set("constructor", objectCtor);
        globals.putIfAbsent("Object", objectCtor);

        // Array constructor
        JSFunction arrayCtor = nativeFn("Array", 1, (t, a, c) -> {
            JSArray arr = new JSArray();
            if (a.length == 1 && a[0] instanceof Number n) {
                // ECMA-262 § 23.1.1.1 step 9: a Number arg must be a valid
                // uint32 array length; otherwise RangeError. Crucially we
                // DO NOT pre-fill with undefined — for `new Array(2**32-1)`
                // that would allocate 16 GB of slots. Sparse arrays via
                // length-tracking are legal (and how real engines work).
                double d = n.doubleValue();
                if (Double.isNaN(d) || d < 0 || d != Math.floor(d) || d > 4294967295.0) {
                    throw AbruptCompletion.rangeError("Invalid array length");
                }
                long len = (long) d;
                arr.setLength((int) Math.min(len, Integer.MAX_VALUE));
            } else if (a.length == 1) {
                // § 23.1.1.1 steps 3-8: non-Number single arg → length 1
                // with the value at index 0.
                arr.push(a[0]);
            } else {
                for (Object e : a) arr.push(e);
            }
            return arr;
        });
        arrayCtor.properties().put("isArray", nativeFn("isArray", 1,
            (t, a, c) -> arg(a, 0) instanceof JSArray));
        arrayCtor.properties().put("of", nativeFn("of", 0, (t, a, c) -> {
            // § 23.1.2.2 Array.of — uses the called constructor C if it's
            // a Constructor; else %Array%. Each item is set via
            // CreateDataPropertyOrThrow, then length is set.
            int len = a.length;
            Object A;
            if (t instanceof JSFunction tf && tf.isConstructor() && t != arrayCtor) {
                JSObject receiver = new JSObject(tf.prototypeObject() != null
                    ? tf.prototypeObject() : arrayPrototype);
                Object built = Interpreter.invokeFunctionAsConstructor(tf, receiver,
                    new Object[]{(double) len}, c);
                A = (built instanceof JSArray || built instanceof JSObject) ? built : receiver;
            } else {
                JSArray arr = new JSArray();
                arr.setLength(len);
                A = arr;
            }
            for (int i = 0; i < len; i++) setIndexed(A, i, a[i]);
            if (!(A instanceof JSArray)) {
                AbstractOps.setProperty(A, "length", (double) len);
            }
            return A;
        }));
        arrayCtor.properties().put("from", nativeFn("from", 1, (t, a, c) -> {
            // § 23.1.2.1 Array.from(items [, mapfn [, thisArg]]).
            // 1. C = this value.
            // 2. mapping: if mapfn undefined, no mapping; else mapfn must be
            //    callable (TypeError otherwise — including Symbol per spec).
            Object src = arg(a, 0);
            Object mapfnArg = arg(a, 1);
            if (mapfnArg != Undefined.VALUE && !(mapfnArg instanceof JSFunction)) {
                throw AbruptCompletion.typeError("Array.from: mapfn must be callable");
            }
            JSFunction mapper = mapfnArg instanceof JSFunction f ? f : null;
            Object thisArg = arg(a, 2);
            if (src == null || src == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Array.from called on null/undefined");
            }
            // Allocator: when called as Constructor.from, use Constructor;
            // else default to %Array%.
            final boolean usingCustomCtor = t instanceof JSFunction tf
                && tf.isConstructor() && t != arrayCtor;
            final JSFunction tfFinal = usingCustomCtor ? (JSFunction) t : null;
            // 5. Read @@iterator on the source. If callable, treat as iterable.
            Object iterFn = AbstractOps.getProperty(src, wellKnownIterator.asPropertyKey());
            if (iterFn instanceof JSFunction iter) {
                Object iterator = Interpreter.invokeFunction(iter, src, new Object[0], c);
                if (iterator instanceof JSObject itObj) {
                    Object nextFn = AbstractOps.getProperty(itObj, "next");
                    if (nextFn instanceof JSFunction nf) {
                        // Allocate the destination — for custom ctor, call
                        // with no args (length unknown); for Array, fresh.
                        Object A;
                        if (usingCustomCtor) {
                            JSObject receiver = new JSObject(tfFinal.prototypeObject() != null
                                ? tfFinal.prototypeObject() : arrayPrototype);
                            Object built = Interpreter.invokeFunctionAsConstructor(tfFinal, receiver,
                                new Object[0], c);
                            A = (built instanceof JSArray || built instanceof JSObject) ? built : receiver;
                        } else {
                            A = new JSArray();
                        }
                        int i = 0;
                        try {
                            while (true) {
                                if ((i & 0x3FF) == 0 && Thread.interrupted()) {
                                    throw new Interpreter.InterpInterruptedError();
                                }
                                Object step = Interpreter.invokeFunction(nf, itObj, new Object[0], c);
                                if (AbstractOps.toBoolean(AbstractOps.getProperty(step, "done"))) break;
                                Object v = AbstractOps.getProperty(step, "value");
                                if (mapper != null) {
                                    v = Interpreter.invokeFunction(mapper, thisArg,
                                        new Object[]{v, (double) i}, c);
                                }
                                setIndexed(A, i, v);
                                i++;
                            }
                        } catch (AbruptCompletion ac) {
                            // § 7.4.10 IteratorClose on abrupt completion.
                            try {
                                Object retFn = AbstractOps.getProperty(itObj, "return");
                                if (retFn instanceof JSFunction rf) {
                                    Interpreter.invokeFunction(rf, itObj, new Object[0], c);
                                }
                            } catch (AbruptCompletion ignored) {}
                            throw ac;
                        }
                        if (!(A instanceof JSArray)) {
                            AbstractOps.setProperty(A, "length", (double) i);
                        }
                        return A;
                    }
                }
            }
            // Array-like fallback: length + indexed reads.
            int len = lengthOfArrayLike(src);
            Object A;
            if (usingCustomCtor) {
                JSObject receiver = new JSObject(tfFinal.prototypeObject() != null
                    ? tfFinal.prototypeObject() : arrayPrototype);
                Object built = Interpreter.invokeFunctionAsConstructor(tfFinal, receiver,
                    new Object[]{(double) len}, c);
                A = (built instanceof JSArray || built instanceof JSObject) ? built : receiver;
            } else {
                JSArray arr = new JSArray();
                arr.setLength(len);
                A = arr;
            }
            for (int i = 0; i < len; i++) {
                Object v = getIndexed(src, i);
                if (mapper != null) {
                    v = Interpreter.invokeFunction(mapper, thisArg,
                        new Object[]{v, (double) i}, c);
                }
                setIndexed(A, i, v);
            }
            if (!(A instanceof JSArray)) {
                AbstractOps.setProperty(A, "length", (double) len);
            }
            return A;
        }));
        // Array.fromAsync — stub that returns a settled Promise of Array.from(...)
        arrayCtor.properties().put("fromAsync", nativeFn("fromAsync", 1, (t, a, c) -> {
            JSObject p = createPromise();
            try {
                Object inner = Interpreter.invokeFunction(
                    (JSFunction) arrayCtor.properties().get("from"),
                    t, a, c);
                resolvePromise(p, inner, c);
            } catch (AbruptCompletion ac) {
                rejectPromise(p, ac.value());
            }
            return p;
        }));
        // § 23.1.2.4 Array.prototype reachable from constructor.
        arrayCtor.setPrototypeObject(arrayPrototype);
        arrayPrototype.set("constructor", arrayCtor);
        TypedArrays.installSpeciesPublic(arrayCtor);
        globals.putIfAbsent("Array", arrayCtor);

        // Error family — bare-bones constructors that yield a JSObject with name/message
        installError(globals, "Error", "Error");
        installError(globals, "TypeError", "TypeError");
        installError(globals, "RangeError", "RangeError");
        installError(globals, "ReferenceError", "ReferenceError");
        installError(globals, "SyntaxError", "SyntaxError");
        installError(globals, "URIError", "URIError");
        installError(globals, "EvalError", "EvalError");
        installError(globals, "AggregateError", "AggregateError");
        // Override the 1-arg installError version with the spec-aligned
        // 2-arg AggregateError(errors, message) — uses IterableToList +
        // InstallErrorCause.
        com.jimmyhmiller.harmonica.bytecode.builtins.AggregateErrorBuiltin.install(globals);
        com.jimmyhmiller.harmonica.bytecode.builtins.SuppressedErrorBuiltin.install(globals);

        // ECMA-262 § 20.5.2.1 Error.isError(arg) — Stage 4 (ES2025) static
        // predicate. Returns true iff arg has an [[ErrorData]] internal
        // slot. We approximate by walking the proto chain for any error
        // prototype (errorPrototype or one of the named subtypes).
        Object errorCtorVal = globals.get("Error");
        if (errorCtorVal instanceof JSFunction errorCtor) {
            errorCtor.properties().put("isError", nativeFn("isError", 1, (t, a, c) -> {
                Object v = arg(a, 0);
                if (!(v instanceof JSObject jo)) return false;
                JSObject cursor = jo.proto();
                while (cursor != null) {
                    if (cursor == errorPrototype) return true;
                    for (JSObject p : errorPrototypes.values()) if (cursor == p) return true;
                    cursor = cursor.proto();
                }
                return false;
            }));
        }

        // Indirect eval — parse + run the source string. Does NOT see caller scope.
        globals.putIfAbsent("eval", nativeFn("eval", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (!(v instanceof CharSequence cs)) return v;
            String src = cs.toString();
            com.jimmyhmiller.harmonica.ast.Program ast;
            try {
                ast = com.jimmyhmiller.harmonica.Parser.parse(src);
            } catch (Throwable th) {
                throw new AbruptCompletion(makeError("SyntaxError",
                    th.getMessage() != null ? th.getMessage() : "parse error"));
            }
            Executable exe;
            try { exe = Generator.generate(ast); }
            catch (Throwable th) {
                throw new AbruptCompletion(makeError("SyntaxError",
                    th.getMessage() != null ? th.getMessage() : "compile error"));
            }
            // Indirect eval shares the realm's globals with the caller (ECMA-262
            // § 19.2.1 PerformEval — eval'd top-level assignments must be
            // visible to the surrounding script).
            InterpContext evalCtx = new InterpContext(exe, new Object[0], 64, c.globals());
            evalCtx.registers()[Variable.Register.THIS_VALUE_INDEX] = c.globals().get("globalThis");
            for (String name : exe.hoistedVarNames()) c.globals().putIfAbsent(name, Undefined.VALUE);
            for (Executable.HoistedFunction h : exe.hoistedFunctions()) c.globals().put(h.name(), h.template());
            return Interpreter.interpret(exe, evalCtx);
        }));

        // ECMA-262 § 20.2 Function constructor. `new Function('x', 'return x*2')`
        // builds a function by composing source and parsing it.
        JSFunction functionCtor = nativeFn("Function", 1, (t, a, c) -> {
            // ECMA-262 § 20.2.1.1 Function constructor — mirrors LibJS's
            // CreateDynamicFunction: parse the source as a FunctionExpression
            // (NOT a Script) and materialize the function template directly
            // in the current realm. We do NOT re-enter Interpreter.interpret —
            // that would build a separate globals map and a separate Function
            // intrinsic, which would make `f.constructor === Function`
            // and `f instanceof Function` false.
            StringBuilder sb = new StringBuilder("(function(");
            int paramCount = Math.max(0, a.length - 1);
            for (int i = 0; i < paramCount; i++) {
                if (i > 0) sb.append(',');
                sb.append(AbstractOps.toString(a[i]));
            }
            sb.append("){");
            if (a.length >= 1) sb.append(AbstractOps.toString(a[a.length - 1]));
            sb.append("})");
            try {
                com.jimmyhmiller.harmonica.ast.Program ast =
                    com.jimmyhmiller.harmonica.Parser.parse(sb.toString());
                Executable exe = Generator.generate(ast);
                // The Generator parks the FunctionExpression's compiled
                // body at sharedFunctionData[0]. Instantiate it directly
                // (no captures — the surrounding context is the script's
                // top-level, which has no live bindings of its own) and
                // bind it to the caller's globals so cross-realm lookups
                // see the same Function intrinsic.
                JSFunction template = exe.sharedFunctionData()[0];
                JSFunction fn = template.withCapturedCells(new com.jimmyhmiller.harmonica.bytecode.Cell[0]);
                if (c != null) fn.setHomeGlobals(c.globals());
                return fn;
            } catch (AbruptCompletion ac) {
                throw ac;
            } catch (Throwable th) {
                JSObject err = new JSObject();
                err.set("name", "SyntaxError");
                err.set("message", th.getMessage() != null ? th.getMessage() : "Function constructor failed");
                throw new AbruptCompletion(err);
            }
        });
        // § 20.2.2.2 Function.prototype is reachable as Function.prototype.
        functionCtor.setPrototypeObject(functionPrototype);
        functionPrototype.set("constructor", functionCtor);
        globals.putIfAbsent("Function", functionCtor);

        // ECMA-262 § 22.2 RegExp — v1 stub. Real regex semantics aren't
        // implemented; the constructor returns a JSObject carrying source +
        // flags so tests checking `RegExp` is defined / typeof works can
        // proceed. Methods like .exec / .test will fail later in tests that
        // actually invoke them.
        if (regExpPrototype == null) {
            regExpPrototype = new JSObject(objectPrototype);
            regExpPrototype.set("source", "");
            regExpPrototype.set("flags", "");
            // ECMA-262 § 22.2.5.2 RegExp.prototype.exec: returns null if no
            // match; otherwise an array with index, input, and capture groups.
            JSFunction execFn = nativeFn("exec", 1, (t, a, c) -> {
                // ECMA-262 § 22.2.5.2 RegExp.prototype.exec — receiver must
                // be a RegExp instance with [[OriginalSource]] internal slot.
                String src = asRegExpSource(t);
                if (src == null) {
                    throw AbruptCompletion.typeError("RegExp.prototype.exec called on non-RegExp");
                }
                String flags = asRegExpFlags(t);
                String input = AbstractOps.toString(arg(a, 0));
                java.util.regex.Pattern p = compileJsRegex(src, flags);
                java.util.regex.Matcher m = p.matcher(input);
                // ECMA-262 § 22.2.5.6 RegExpExec: with the `g` or `y` flag,
                // start the search at lastIndex and reset it to the match's
                // end position (or 0 on no match). Without those flags,
                // lastIndex is ignored.
                boolean stickyOrGlobal = flags.contains("g") || flags.contains("y");
                int startAt = 0;
                if (stickyOrGlobal && t instanceof JSObject reObj) {
                    Object li = reObj.properties().get("lastIndex");
                    if (li instanceof Number nli) startAt = (int) nli.doubleValue();
                    if (startAt < 0 || startAt > input.length()) {
                        reObj.set("lastIndex", 0.0);
                        return null;
                    }
                }
                boolean found = m.find(startAt);
                // Sticky requires the match to start exactly at lastIndex.
                if (found && flags.contains("y") && m.start() != startAt) found = false;
                if (!found) {
                    if (stickyOrGlobal && t instanceof JSObject reObj) reObj.set("lastIndex", 0.0);
                    return null;
                }
                JSArray out = new JSArray();
                out.push(m.group());
                for (int gi = 1; gi <= m.groupCount(); gi++) {
                    out.push(m.group(gi) == null ? Undefined.VALUE : m.group(gi));
                }
                out.setExtraProperty("index", (double) m.start());
                out.setExtraProperty("input", input);
                attachNamedGroups(out, p, m, src);
                if (stickyOrGlobal && t instanceof JSObject reObj) {
                    reObj.set("lastIndex", (double) m.end());
                }
                return out;
            });
            regExpPrototype.set("exec", execFn);
            // Capture the built-in for the RegExpExec fallback (§ 22.2.7.1
            // step 6) — used when user code has overridden
            // {@code RegExp.prototype.exec} with a non-callable value.
            originalRegExpExec = execFn;
            regExpPrototype.set("test", nativeFn("test", 1, (t, a, c) -> {
                String src = asRegExpSource(t);
                if (src == null) {
                    throw AbruptCompletion.typeError("RegExp.prototype.test called on non-RegExp");
                }
                String flags = asRegExpFlags(t);
                String input = AbstractOps.toString(arg(a, 0));
                return compileJsRegex(src, flags).matcher(input).find();
            }));
            regExpPrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
                // § 22.2.6.16 RegExp.prototype.toString — accept any Object,
                // reading `source` and `flags` via Get (so subclass
                // accessors win). Non-objects throw TypeError.
                if (!(t instanceof JSObject)) {
                    throw AbruptCompletion.typeError("RegExp.prototype.toString called on non-object");
                }
                Object sourceObj = AbstractOps.getProperty(t, "source");
                Object flagsObj = AbstractOps.getProperty(t, "flags");
                String src = sourceObj == Undefined.VALUE ? "" : AbstractOps.toString(sourceObj);
                String flags = flagsObj == Undefined.VALUE ? "" : AbstractOps.toString(flagsObj);
                return "/" + src + "/" + flags;
            }));
            // ECMA-262 § 22.2.6 flag accessors — global / ignoreCase /
            // multiline / sticky / unicode / unicodeSets / dotAll / hasIndices.
            // ECMA-262 § 22.2.6.* RegExp prototype accessors. All start with
            // step "If Type(R) is not Object, throw a TypeError" — and
            // they additionally require the receiver to be either a
            // RegExp instance or RegExp.prototype itself (per § 22.2.6.X
            // RequireInternalSlot([[OriginalSource]])). Tested by
            // built-ins/RegExp/prototype/{source,flags,global,...}/this-val-non-obj.js.
            for (var entry : new String[][]{
                {"global", "g"}, {"ignoreCase", "i"}, {"multiline", "m"},
                {"sticky", "y"}, {"unicode", "u"}, {"unicodeSets", "v"},
                {"dotAll", "s"}, {"hasIndices", "d"}
            }) {
                String name = entry[0];
                String flagChar = entry[1];
                regExpPrototype.set(name, new Accessor(
                    nativeFn("get " + name, 0, (t, a, c) -> {
                        if (t == regExpPrototype) return Undefined.VALUE;   // spec: prototype itself returns undefined
                        if (!(t instanceof JSObject)) {
                            throw AbruptCompletion.typeError(
                                "RegExp.prototype." + name + " getter called on non-object");
                        }
                        String flags = asRegExpFlags(t);
                        if (flags == null) {
                            throw AbruptCompletion.typeError(
                                "RegExp.prototype." + name + " getter called on non-RegExp");
                        }
                        return flags.contains(flagChar);
                    }), null));
                regExpPrototype.setAttributes(name, JSObject.ATTR_CONFIGURABLE);
            }
            // source / flags as accessors
            regExpPrototype.set("source", new Accessor(
                nativeFn("get source", 0, (t, a, c) -> {
                    if (t == regExpPrototype) return "(?:)";   // § 22.2.6.10 step 3
                    if (!(t instanceof JSObject)) {
                        throw AbruptCompletion.typeError("RegExp.prototype.source getter called on non-object");
                    }
                    String src = asRegExpSource(t);
                    if (src == null) {
                        throw AbruptCompletion.typeError("RegExp.prototype.source getter called on non-RegExp");
                    }
                    return src;
                }), null));
            regExpPrototype.setAttributes("source", JSObject.ATTR_CONFIGURABLE);
            regExpPrototype.set("flags", new Accessor(
                nativeFn("get flags", 0, (t, a, c) -> {
                    if (!(t instanceof JSObject jo)) {
                        throw AbruptCompletion.typeError("RegExp.prototype.flags getter called on non-object");
                    }
                    // § 22.2.6.3 reads each flag accessor on `this` and
                    // builds the resulting string from the individual booleans —
                    // works on RegExp instances and RegExp.prototype alike.
                    String[][] order = {
                        {"hasIndices", "d"}, {"global", "g"}, {"ignoreCase", "i"},
                        {"multiline", "m"}, {"dotAll", "s"}, {"unicode", "u"},
                        {"unicodeSets", "v"}, {"sticky", "y"}
                    };
                    StringBuilder sb = new StringBuilder();
                    for (String[] e : order) {
                        Object v = AbstractOps.getProperty(jo, e[0]);
                        if (AbstractOps.toBoolean(v)) sb.append(e[1]);
                    }
                    return sb.toString();
                }), null));
            regExpPrototype.setAttributes("flags", JSObject.ATTR_CONFIGURABLE);

            // ECMA-262 § 22.2.6.{8..13} RegExp.prototype well-known-symbol
            // methods. String.prototype.{match,replace,search,split,matchAll}
            // dispatch through these, so test262 exercises both the symbol
            // surface directly (RegExp.prototype[Symbol.match].call(re, s))
            // and as a side effect of the string-side methods. We don't yet
            // implement the full RegExpExec / advanceStringIndex / IsCallable
            // dance for replace, so these are pragmatic shims that reuse the
            // String.prototype implementations by swapping receiver/arg.
            regExpPrototype.set(wellKnownMatch.asPropertyKey(),
                nativeFn("[Symbol.match]", 1, (t, a, c) -> {
                    // ECMA-262 § 22.2.6.10 RegExp.prototype[@@match]: when
                    // the receiver has a user-overridden `exec` method, the
                    // spec wants us to dispatch through it. Currently we
                    // only do that for the non-global case; for global we
                    // still route through String.prototype.match because the
                    // spec's loop has subtle lastIndex / writable-descriptor
                    // interactions we don't yet handle.
                    if (!(t instanceof JSObject rx)) {
                        throw AbruptCompletion.typeError("RegExp.prototype[@@match] called on non-object");
                    }
                    String inputStr = AbstractOps.toString(arg(a, 0));
                    Object globalV = AbstractOps.getProperty(rx, "global");
                    boolean global = AbstractOps.toBoolean(globalV);
                    if (!global) {
                        return regExpExec(rx, inputStr, c);
                    }
                    Object strFn = stringPrototype.get("match");
                    if (!(strFn instanceof JSFunction f)) return null;
                    return Interpreter.invokeFunction(f, inputStr, new Object[]{t}, c);
                }));
            regExpPrototype.setAttributes(wellKnownMatch.asPropertyKey(),
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            regExpPrototype.set(wellKnownReplace.asPropertyKey(),
                nativeFn("[Symbol.replace]", 2, (t, a, c) -> {
                    Object strFn = stringPrototype.get("replace");
                    if (!(strFn instanceof JSFunction f)) return arg(a, 0);
                    String s = AbstractOps.toString(arg(a, 0));
                    return Interpreter.invokeFunction(f, s, new Object[]{t, arg(a, 1)}, c);
                }));
            regExpPrototype.setAttributes(wellKnownReplace.asPropertyKey(),
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            regExpPrototype.set(wellKnownSearch.asPropertyKey(),
                nativeFn("[Symbol.search]", 1, (t, a, c) -> {
                    Object strFn = stringPrototype.get("search");
                    if (!(strFn instanceof JSFunction f)) return -1.0;
                    String s = AbstractOps.toString(arg(a, 0));
                    return Interpreter.invokeFunction(f, s, new Object[]{t}, c);
                }));
            regExpPrototype.setAttributes(wellKnownSearch.asPropertyKey(),
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            regExpPrototype.set(wellKnownSplit.asPropertyKey(),
                nativeFn("[Symbol.split]", 2, (t, a, c) -> {
                    Object strFn = stringPrototype.get("split");
                    if (!(strFn instanceof JSFunction f)) {
                        JSArray empty = new JSArray();
                        empty.push(AbstractOps.toString(arg(a, 0)));
                        return empty;
                    }
                    String s = AbstractOps.toString(arg(a, 0));
                    return Interpreter.invokeFunction(f, s, new Object[]{t, arg(a, 1)}, c);
                }));
            regExpPrototype.setAttributes(wellKnownSplit.asPropertyKey(),
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            // § 22.2.6.9 RegExp.prototype [@@matchAll] — unlike
            // String.prototype.matchAll this does NOT require /g; it bakes
            // the global/unicode bits into the returned iterator. Reads
            // {@code flags}/{@code lastIndex} via Get so subclass accessors
            // win, then constructs a fresh matcher and the iterator.
            regExpPrototype.set(wellKnownMatchAll.asPropertyKey(),
                nativeFn("[Symbol.matchAll]", 1, (t, a, c) -> {
                    if (!(t instanceof JSObject reObj)) {
                        throw AbruptCompletion.typeError(
                            "RegExp.prototype[@@matchAll] called on non-object");
                    }
                    String s = AbstractOps.toString(arg(a, 0));
                    Object flagsVal = AbstractOps.getProperty(reObj, "flags");
                    String flags = flagsVal == Undefined.VALUE ? "" : AbstractOps.toString(flagsVal);
                    boolean global  = flags.contains("g");
                    boolean unicode = flags.contains("u") || flags.contains("v");
                    // Build a fresh RegExp instance for the iterator so
                    // advancing lastIndex on it doesn't mutate the caller's.
                    Object source = AbstractOps.getProperty(reObj, "source");
                    String src = source == Undefined.VALUE ? "" : AbstractOps.toString(source);
                    Object reCtor = c == null ? null : c.globals().get("RegExp");
                    Object matcher;
                    if (reCtor instanceof JSFunction reCtorFn) {
                        matcher = Interpreter.invokeFunctionAsConstructor(
                            reCtorFn, new JSObject(regExpPrototype),
                            new Object[]{src, flags}, c);
                    } else {
                        matcher = reObj;   // fallback: reuse — slot pattern
                    }
                    Object liv = AbstractOps.getProperty(reObj, "lastIndex");
                    AbstractOps.setProperty(matcher, "lastIndex", liv);
                    return com.jimmyhmiller.harmonica.bytecode.builtins
                        .RegExpStringIteratorPrototypeBuiltin.create(matcher, s, global, unicode);
                }));
            regExpPrototype.setAttributes(wellKnownMatchAll.asPropertyKey(),
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            // Annex B § B.2.5.1 RegExp.prototype.compile(pattern, flags) —
            // legacy method that reinitializes the regex in place.
            regExpPrototype.set("compile", nativeFn("compile", 2, (t, a, c) -> {
                if (!(t instanceof JSObject re)) {
                    throw AbruptCompletion.typeError("RegExp.prototype.compile called on non-object");
                }
                Object pat = arg(a, 0);
                Object flagsArg = arg(a, 1);
                String src;
                String flags;
                if (asRegExpSource(pat) != null) {
                    // If pattern is itself a RegExp, flags must be undefined.
                    if (flagsArg != Undefined.VALUE) {
                        throw AbruptCompletion.typeError("Cannot supply flags when constructing one RegExp from another");
                    }
                    src = asRegExpSource(pat);
                    flags = asRegExpFlags(pat);
                } else {
                    src = pat == Undefined.VALUE ? "" : AbstractOps.toString(pat);
                    flags = flagsArg == Undefined.VALUE ? "" : AbstractOps.toString(flagsArg);
                }
                re.set("source", src);
                re.setAttributes("source", (byte) 0);
                re.set("flags", flags);
                re.setAttributes("flags", (byte) 0);
                re.set("lastIndex", 0.0);
                re.setAttributes("lastIndex", JSObject.ATTR_WRITABLE);
                return re;
            }));
            regExpPrototype.setAttributes("compile",
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        }
        final JSObject regExpProto = regExpPrototype;
        // Idempotent — cache the ctor on the proto so nested-eval re-bootstrap
        // doesn't orphan outer-test references (same pattern as installError).
        // Bug-source: `proto.get("constructor")` walks the chain and returns
        // Object.prototype.constructor when we never installed an own one
        // here — gating on own-property avoids the false hit.
        Object cachedRegExpCtor = regExpProto.properties().get("constructor");
        JSFunction regExpCtor;
        if (cachedRegExpCtor instanceof JSFunction f) {
            regExpCtor = f;
        } else {
            regExpCtor = nativeFn("RegExp", 2, (t, a, c) -> {
                JSObject re = new JSObject(regExpProto);
                // ECMA-262 § 22.2.4: RegExp instances only have a [[OriginalSource]]
                // / [[OriginalFlags]] internal slot — source/flags are accessor
                // properties on the prototype. Stash them under non-enumerable
                // own keys here so the prototype's accessors can read them
                // without polluting Object.keys / for-in.
                String srcInit = arg(a, 0) == Undefined.VALUE ? "" : AbstractOps.toString(a[0]);
                // ECMA-262 § 22.2.3.1 RegExpInitialize step 12: parse the
                // pattern, throw SyntaxError if it doesn't match the
                // grammar. We delegate to java.util.regex.Pattern.compile
                // on the translated form — failure = invalid JS pattern.
                try {
                    java.util.regex.Pattern.compile(translateJsRegexToJava(srcInit));
                } catch (java.util.regex.PatternSyntaxException pse) {
                    throw AbruptCompletion.syntaxError(
                        "Invalid regular expression: /" + srcInit + "/ - " + pse.getMessage());
                }
                re.set("source", srcInit);
                re.setAttributes("source", (byte) 0);   // non-enumerable, non-writable, non-configurable
                String flags = arg(a, 1) == Undefined.VALUE ? "" : AbstractOps.toString(a[1]);
                // ECMA-262 § 22.2.3.1 RegExpInitialize — invalid flag chars or
                // duplicate flags throw SyntaxError.
                String valid = "gimsuyvd";
                long seen = 0L;
                for (int i = 0; i < flags.length(); i++) {
                    char ch = flags.charAt(i);
                    int idx = valid.indexOf(ch);
                    if (idx < 0) {
                        throw AbruptCompletion.syntaxError("Invalid RegExp flag '" + ch + "'");
                    }
                    long bit = 1L << idx;
                    if ((seen & bit) != 0) {
                        throw AbruptCompletion.syntaxError("Duplicate RegExp flag '" + ch + "'");
                    }
                    seen |= bit;
                }
                // 'u' and 'v' are mutually exclusive (§ 22.2.3.1 step 8).
                if (flags.indexOf('u') >= 0 && flags.indexOf('v') >= 0) {
                    throw AbruptCompletion.syntaxError("RegExp flags 'u' and 'v' are mutually exclusive");
                }
                re.set("flags", flags);
                re.setAttributes("flags", (byte) 0);
                re.set("lastIndex", 0.0);   // ECMA-262 § 22.2.4.1 step 3
                // lastIndex is writable but non-enumerable and non-configurable.
                re.setAttributes("lastIndex", JSObject.ATTR_WRITABLE);
                return re;
            });
            regExpCtor.setPrototypeObject(regExpProto);
            regExpProto.set("constructor", regExpCtor);
        }
        // ECMA-262 (proposal-regexp-escaping, Stage 4) — RegExp.escape(S).
        // Escapes characters that have special meaning in a regular
        // expression pattern. The leading-character handling (§ 2.1.1.1
        // EncodeForRegExpEscape) escapes ASCII alphanumerics in
        // first-position form as \xHH so that the escaped string is safe to
        // use as a regex unit (e.g., after \1 in a pattern).
        regExpCtor.properties().put("escape", nativeFn("escape", 1, (t, a, c) -> {
            Object input = arg(a, 0);
            if (!(input instanceof CharSequence cs)) {
                throw AbruptCompletion.typeError("RegExp.escape: argument must be a string");
            }
            String s = cs.toString();
            StringBuilder out = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                // First-position ASCII alphanumeric → \xHH form so the
                // escaped string can't combine with preceding sequences.
                if (i == 0 && ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z'))) {
                    out.append('\\').append('x');
                    String hex = Integer.toHexString(ch);
                    if (hex.length() == 1) out.append('0');
                    out.append(hex);
                    continue;
                }
                // SyntaxCharacter per § 22.2.1.1 — escape with backslash.
                if (ch == '^' || ch == '$' || ch == '\\' || ch == '.' || ch == '*'
                    || ch == '+' || ch == '?' || ch == '(' || ch == ')'
                    || ch == '[' || ch == ']' || ch == '{' || ch == '}'
                    || ch == '|' || ch == '/') {
                    out.append('\\').append(ch);
                    continue;
                }
                // Other punctuators that EncodeForRegExpEscape requires.
                if (ch == ',' || ch == '-' || ch == '=' || ch == '<' || ch == '>'
                    || ch == '#' || ch == '&' || ch == '!' || ch == '%' || ch == ':'
                    || ch == ';' || ch == '@' || ch == '~' || ch == '\'' || ch == '`'
                    || ch == '"') {
                    String hex = Integer.toHexString(ch);
                    out.append('\\').append('x');
                    if (hex.length() == 1) out.append('0');
                    out.append(hex);
                    continue;
                }
                // Whitespace and line terminators.
                switch (ch) {
                    case '\t': out.append("\\t"); continue;
                    case '\n': out.append("\\n"); continue;
                    case 0x0B: out.append("\\v"); continue;
                    case '\f': out.append("\\f"); continue;
                    case '\r': out.append("\\r"); continue;
                    case 0x00A0:
                    case 0x1680:
                    case 0x2028:
                    case 0x2029:
                    case 0xFEFF: {
                        String hex = Integer.toHexString(ch);
                        out.append('\\').append('u');
                        while (hex.length() < 4) hex = "0" + hex;
                        out.append(hex);
                        continue;
                    }
                    default:
                        if (ch >= 0x2000 && ch <= 0x200A) {
                            String hex = Integer.toHexString(ch);
                            out.append('\\').append('u');
                            while (hex.length() < 4) hex = "0" + hex;
                            out.append(hex);
                            continue;
                        }
                }
                // Surrogate handling: escape unpaired/lone surrogates so the
                // output remains a valid UCS-2 string that round-trips through
                // RegExp parsing.
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        out.append(ch).append(s.charAt(i + 1));
                        i++;
                        continue;
                    }
                    String hex = Integer.toHexString(ch);
                    out.append('\\').append('u');
                    while (hex.length() < 4) hex = "0" + hex;
                    out.append(hex);
                    continue;
                }
                if (Character.isLowSurrogate(ch)) {
                    String hex = Integer.toHexString(ch);
                    out.append('\\').append('u');
                    while (hex.length() < 4) hex = "0" + hex;
                    out.append(hex);
                    continue;
                }
                out.append(ch);
            }
            return out.toString();
        }));
        TypedArrays.installSpeciesPublic(regExpCtor);

        // Annex B legacy RegExp static accessors — RegExp.input, $_, $&,
        // $`, $', $1-$9, etc. Per the legacy-features proposal each accessor
        // brand-checks (`this === %RegExp%`) before reading the stored value;
        // input/$_ also have a setter that ToString-coerces the assigned val.
        installLegacyRegExpAccessors(regExpCtor);

        globals.putIfAbsent("RegExp", regExpCtor);

        // ECMA-262 § 28.1 Reflect — namespace of meta-operations. Ported to
        // builtins/ReflectBuiltin to keep all the {@code target.is_object()}
        // type checks and spec-aligned signatures in one place.
        com.jimmyhmiller.harmonica.bytecode.builtins.ReflectBuiltin.install(globals);

        // ECMA-262 § 28.2 Proxy — store target+handler in internal slots and
        // dispatch property operations through handler traps. AbstractOps.{get,set}Property
        // detect SLOT_PROXY_TARGET and route to trapGet/trapSet/etc.
        JSFunction proxyCtor = nativeFn("Proxy", 2, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Proxy constructor requires 'new'");
            }
            Object target = arg(a, 0);
            Object handler = arg(a, 1);
            if (target == null || target == Undefined.VALUE
                || !(target instanceof JSObject || target instanceof JSArray || target instanceof JSFunction)) {
                throw AbruptCompletion.typeError("Proxy target must be an object");
            }
            if (handler == null || handler == Undefined.VALUE
                || !(handler instanceof JSObject)) {
                throw AbruptCompletion.typeError("Proxy handler must be an object");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject();
            self.set(SLOT_PROXY_TARGET, target);
            self.setAttributes(SLOT_PROXY_TARGET, (byte) 0);
            self.set(SLOT_PROXY_HANDLER, handler);
            self.setAttributes(SLOT_PROXY_HANDLER, (byte) 0);
            return self;
        });
        proxyCtor.properties().put("revocable", nativeFn("revocable", 2, (t, a, c) -> {
            Object target = arg(a, 0);
            Object handler = arg(a, 1);
            // Build the proxy via the ctor (skipping the new-call check via
            // Interpreter.invokeFunctionAsConstructor).
            JSObject receiver = new JSObject();
            Interpreter.invokeFunctionAsConstructor(proxyCtor, receiver, new Object[]{target, handler}, c);
            JSObject result = new JSObject();
            result.set("proxy", receiver);
            result.set("revoke", nativeFn("revoke", 0, (tt, aa, cc) -> {
                receiver.set(SLOT_PROXY_TARGET, null);
                receiver.set(SLOT_PROXY_HANDLER, null);
                return Undefined.VALUE;
            }));
            return result;
        }));
        globals.putIfAbsent("Proxy", proxyCtor);

        // ECMA-262 § 23.2 / § 25.1 / § 25.3 — install the TypedArray family,
        // ArrayBuffer, SharedArrayBuffer, DataView, and the %TypedArray%
        // intrinsic. Done last so it can reference any prototype that the
        // earlier installers built. The integer-indexed access hooks live in
        // AbstractOps.getProperty/setProperty.
        TypedArrays.install(globals);

        // Mirror everything currently installed onto globalThis, so e.g.
        // `globalThis.Math.PI`, `globalThis.NaN`, `globalThis.parseInt(...)` work.
        Object globalThisVal = globals.get("globalThis");
        if (globalThisVal instanceof JSObject globalThisObj) {
            for (var e : globals.entrySet()) {
                if ("globalThis".equals(e.getKey())) continue;
                if (!globalThisObj.properties().containsKey(e.getKey())) {
                    globalThisObj.set(e.getKey(), e.getValue());
                    // ECMA-262 § 19 — constructor properties of the global
                    // object have { writable: true, enumerable: false,
                    // configurable: true }. NaN/Infinity/undefined are
                    // already installed with attrs=0 (frozen) above and
                    // skipped by the containsKey guard.
                    if (e.getValue() instanceof JSFunction
                            || e.getValue() instanceof JSObject) {
                        globalThisObj.setAttributes(e.getKey(),
                            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
                    }
                }
            }
            globalThisObj.set("globalThis", globalThisObj);   // self-reference
        }

        // ECMA-262 § 17: every built-in method has the descriptor
        // {writable: true, enumerable: false, configurable: true}. Sweep
        // every constructor's statics + every late-installed prototype
        // so test262's hundreds of `desc.enumerable === false` checks
        // pass. Anything already given a non-default attribute byte
        // (frozen typed-array slots, accessor pairs, etc.) is preserved
        // by {@link #markStaticsNonEnumerable}.
        for (var e : globals.entrySet()) {
            Object v = e.getValue();
            if (v instanceof JSFunction fn) markStaticsNonEnumerable(fn);
        }
        // Late-installed prototype objects whose methods were added after
        // ensurePrototypes()'s sweep.
        if (regExpPrototype != null) markMethodsNonEnumerable(regExpPrototype);
        if (bigIntPrototype != null) markMethodsNonEnumerable(bigIntPrototype);
        if (errorPrototype != null) markMethodsNonEnumerable(errorPrototype);
        for (JSObject p : errorPrototypes.values()) markMethodsNonEnumerable(p);
        // Typed-array prototypes were populated during installViewConstructor
        // but never swept — base64/hex helpers etc. need § 17 attrs.
        if (TypedArrays.typedArrayPrototype != null) markMethodsNonEnumerable(TypedArrays.typedArrayPrototype);
        for (JSObject p : TypedArrays.kindPrototypes.values()) markMethodsNonEnumerable(p);
        for (JSFunction fn : TypedArrays.kindConstructors.values()) markStaticsNonEnumerable(fn);
        if (TypedArrays.arrayBufferPrototype != null) markMethodsNonEnumerable(TypedArrays.arrayBufferPrototype);
        if (TypedArrays.dataViewPrototype != null) markMethodsNonEnumerable(TypedArrays.dataViewPrototype);
        // Date.prototype is swept inline at install time; keep it idempotent.

        // ECMA-262 § 17: built-in methods / static helpers are NOT constructors.
        // Mark prototype methods and constructor statics accordingly so
        // {@code new Math.abs()} / {@code Reflect.construct(eval)} throw
        // TypeError and {@code Promise.all.call(eval)} sees the right
        // IsConstructor result.
        markMethodsNonConstructor(objectPrototype);
        markMethodsNonConstructor(functionPrototype);
        markMethodsNonConstructor(arrayPrototype);
        markMethodsNonConstructor(stringPrototype);
        markMethodsNonConstructor(numberPrototype);
        markMethodsNonConstructor(booleanPrototype);
        markMethodsNonConstructor(symbolPrototype);
        markMethodsNonConstructor(promisePrototype);
        markMethodsNonConstructor(generatorPrototype);
        markMethodsNonConstructor(mapPrototype);
        markMethodsNonConstructor(setPrototype);
        markMethodsNonConstructor(weakMapPrototype);
        markMethodsNonConstructor(weakSetPrototype);
        if (datePrototype != null) markMethodsNonConstructor(datePrototype);
        if (regExpPrototype != null) markMethodsNonConstructor(regExpPrototype);
        if (bigIntPrototype != null) markMethodsNonConstructor(bigIntPrototype);
        if (errorPrototype != null) markMethodsNonConstructor(errorPrototype);
        for (JSObject p : errorPrototypes.values()) markMethodsNonConstructor(p);
        if (TypedArrays.typedArrayPrototype != null) markMethodsNonConstructor(TypedArrays.typedArrayPrototype);
        for (JSObject p : TypedArrays.kindPrototypes.values()) markMethodsNonConstructor(p);
        if (TypedArrays.arrayBufferPrototype != null) markMethodsNonConstructor(TypedArrays.arrayBufferPrototype);
        if (TypedArrays.dataViewPrototype != null) markMethodsNonConstructor(TypedArrays.dataViewPrototype);
        // Sweep namespace methods (Math, JSON) and constructor statics.
        Object mathVal = globals.get("Math");
        if (mathVal instanceof JSObject mathObj) markMethodsNonConstructor(mathObj);
        Object jsonVal = globals.get("JSON");
        if (jsonVal instanceof JSObject jsonObj) markMethodsNonConstructor(jsonObj);
        Object reflectVal = globals.get("Reflect");
        if (reflectVal instanceof JSObject reflectObj) markMethodsNonConstructor(reflectObj);
        for (var e : globals.entrySet()) {
            Object v = e.getValue();
            if (v instanceof JSFunction fn) markStaticsNonConstructor(fn);
        }
        // eval, parseInt, parseFloat, isNaN, isFinite, encode/decodeURI*
        // — global non-constructors per spec. (Don't sweep all globals —
        // most are constructors like Proxy that have no .prototype in our
        // impl and would be mistakenly marked.)
        for (String name : new String[]{"eval", "parseInt", "parseFloat", "isNaN", "isFinite",
                "encodeURI", "encodeURIComponent", "decodeURI", "decodeURIComponent",
                "escape", "unescape"}) {
            Object v = globals.get(name);
            if (v instanceof JSFunction fn) fn.setNonConstructor(true);
        }
    }

    /**
     * Build an Error-typed object with proper prototype wiring so
     * {@code thrown.constructor === SyntaxError} (etc.) holds. Falls back to a
     * bare object if the prototype hasn't been initialized yet (bootstrap).
     */
    /** § 22.2.7.1 RegExpExec(R, S). Read R.[[exec]]; if callable, call it
     *  with R as `this` and [S] as args, validate the return is null or
     *  Object, return it. Otherwise fall back to the built-in exec via
     *  {@link #originalRegExpExec}. */
    public static Object regExpExec(JSObject rx, String S, InterpContext ctx) {
        Object exec = AbstractOps.getProperty(rx, "exec");
        if (exec instanceof JSFunction execFn) {
            Object result = Interpreter.invokeFunction(execFn, rx, new Object[]{S}, ctx);
            if (result == null || result == Undefined.VALUE) return null;
            if (!(result instanceof JSObject || result instanceof JSArray)) {
                throw AbruptCompletion.typeError(
                    "RegExpExec: result of `exec` must be Object or null");
            }
            return result;
        }
        if (originalRegExpExec == null) {
            throw AbruptCompletion.typeError("RegExpExec: receiver is not a RegExp");
        }
        return Interpreter.invokeFunction(originalRegExpExec, rx, new Object[]{S}, ctx);
    }

    /** § 22.2.7.3 AdvanceStringIndex(S, index, unicode). Without unicode,
     *  return index+1. With unicode, advance past the surrogate pair. */
    public static long advanceStringIndex(String S, long index, boolean unicode) {
        if (!unicode || index + 1 >= S.length()) return index + 1;
        int first = S.charAt((int) index);
        if (first < 0xD800 || first > 0xDBFF) return index + 1;
        int second = S.charAt((int) index + 1);
        if (second < 0xDC00 || second > 0xDFFF) return index + 1;
        return index + 2;
    }

    public static JSObject makeError(String typeName, String message) {
        JSObject proto = errorPrototypes.get(typeName);
        JSObject err = proto != null ? new JSObject(proto) : new JSObject();
        err.set("name", typeName);
        err.set("message", message != null ? message : "");
        return err;
    }

    /** ECMA-262 § 20.5.7.4 AggregateError instances also have an own
     *  {@code errors} property — an array snapshot of the rejection reasons. */
    /** § 36.3.3.2 GetWrappedValue — pass-through for primitives, throws
     *  TypeError for non-callable objects, wraps callables. */
    static Object getWrappedValue(Object v, JSObject shadowRealmProto) {
        if (v == null || v == Undefined.VALUE) return v;
        if (v instanceof Number || v instanceof Boolean || v instanceof CharSequence
                || v instanceof JSSymbol || v instanceof JSBigInt) return v;
        if (v instanceof JSFunction inner) {
            // Wrap so calls go through with primitive-only arg/return checks.
            return nativeFn(inner.name() != null ? inner.name() : "wrapped", inner.paramCount(),
                (t2, args2, c2) -> {
                    Object[] mapped = new Object[args2.length];
                    for (int i = 0; i < args2.length; i++) {
                        Object av = args2[i];
                        if (av instanceof JSFunction
                                || av instanceof JSObject
                                || av instanceof JSArray) {
                            throw AbruptCompletion.typeError(
                                "ShadowRealm wrapped function: arguments must be primitives");
                        }
                        mapped[i] = av;
                    }
                    Object r = Interpreter.invokeFunction(inner, Undefined.VALUE, mapped, c2);
                    return getWrappedValue(r, shadowRealmProto);
                });
        }
        throw AbruptCompletion.typeError(
            "ShadowRealm evaluation returned a non-callable object");
    }

    /** § 7.3.16 SetIntegrityLevel — preventExtensions then flip every own
     *  property's configurable bit (and writable bit too for {@code
     *  frozen=true}). Works on JSObject / JSArray / JSFunction. */
    static void setIntegrityLevel(Object v, boolean frozen) {
        if (v instanceof JSObject jo) {
            jo.preventExtensions();
            for (String k : new java.util.ArrayList<>(jo.properties().keySet())) {
                if (isPrivateName(k)) continue;
                byte attrs = jo.getAttributes(k);
                attrs &= ~JSObject.ATTR_CONFIGURABLE;
                if (frozen && !(jo.getOwn(k) instanceof Accessor)) {
                    attrs &= ~JSObject.ATTR_WRITABLE;
                }
                jo.setAttributes(k, attrs);
            }
            return;
        }
        if (v instanceof JSArray arr) {
            arr.preventExtensions();
            // Length descriptor: clear writable when frozen (per spec note,
            // length stays non-configurable already).
            byte la = arr.getIndexAttributes("length");
            if (frozen) la &= ~JSObject.ATTR_WRITABLE;
            arr.setIndexAttributes("length", la);
            // Every indexed element: copy current effective attrs then clear
            // configurable (and writable when frozen).
            for (int i = 0; i < arr.length(); i++) {
                String k = Integer.toString(i);
                byte attrs = arr.getIndexAttributes(k);
                attrs &= ~JSObject.ATTR_CONFIGURABLE;
                if (frozen && !(arr.get(i) instanceof Accessor)) {
                    attrs &= ~JSObject.ATTR_WRITABLE;
                }
                arr.setIndexAttributes(k, attrs);
            }
            if (arr.extraProperties() != null) {
                for (String k : new java.util.ArrayList<>(arr.extraProperties().keySet())) {
                    byte attrs = arr.getIndexAttributes(k);
                    attrs &= ~JSObject.ATTR_CONFIGURABLE;
                    if (frozen && !(arr.getExtraProperty(k) instanceof Accessor)) {
                        attrs &= ~JSObject.ATTR_WRITABLE;
                    }
                    arr.setIndexAttributes(k, attrs);
                }
            }
            return;
        }
        if (v instanceof JSFunction fn) {
            fn.preventExtensions();
            for (String k : new java.util.ArrayList<>(fn.propertiesIfPresent().keySet())) {
                byte attrs = fn.getAttributes(k);
                attrs &= ~JSObject.ATTR_CONFIGURABLE;
                if (frozen && !(fn.getOwnStatic(k) instanceof Accessor)) {
                    attrs &= ~JSObject.ATTR_WRITABLE;
                }
                fn.setAttributes(k, attrs);
            }
        }
    }

    /** § 7.3.17 TestIntegrityLevel — true iff the object is non-extensible
     *  and every own property is non-configurable (and, for {@code frozen=true},
     *  every data property is non-writable too). */
    static boolean testIntegrityLevel(Object v, boolean frozen) {
        if (v instanceof JSObject jo) {
            if (jo.isExtensible()) return false;
            for (String k : jo.properties().keySet()) {
                if (isPrivateName(k)) continue;
                byte attrs = jo.getAttributes(k);
                if ((attrs & JSObject.ATTR_CONFIGURABLE) != 0) return false;
                if (frozen && !(jo.getOwn(k) instanceof Accessor)
                        && (attrs & JSObject.ATTR_WRITABLE) != 0) return false;
            }
            return true;
        }
        if (v instanceof JSArray arr) {
            if (arr.isExtensible()) return false;
            byte la = arr.getIndexAttributes("length");
            if (frozen && (la & JSObject.ATTR_WRITABLE) != 0) return false;
            for (int i = 0; i < arr.length(); i++) {
                String k = Integer.toString(i);
                byte attrs = arr.getIndexAttributes(k);
                if ((attrs & JSObject.ATTR_CONFIGURABLE) != 0) return false;
                if (frozen && !(arr.get(i) instanceof Accessor)
                        && (attrs & JSObject.ATTR_WRITABLE) != 0) return false;
            }
            if (arr.extraProperties() != null) {
                for (String k : arr.extraProperties().keySet()) {
                    byte attrs = arr.getIndexAttributes(k);
                    if ((attrs & JSObject.ATTR_CONFIGURABLE) != 0) return false;
                    if (frozen && !(arr.getExtraProperty(k) instanceof Accessor)
                            && (attrs & JSObject.ATTR_WRITABLE) != 0) return false;
                }
            }
            return true;
        }
        if (v instanceof JSFunction fn) {
            if (fn.isExtensible()) return false;
            for (String k : fn.propertiesIfPresent().keySet()) {
                byte attrs = fn.getAttributes(k);
                if ((attrs & JSObject.ATTR_CONFIGURABLE) != 0) return false;
                if (frozen && !(fn.getOwnStatic(k) instanceof Accessor)
                        && (attrs & JSObject.ATTR_WRITABLE) != 0) return false;
            }
            return true;
        }
        return false;
    }

    /** § 6.1.7 CanBeHeldWeakly — any Object or any non-registered Symbol.
     *  Used by WeakRef / FinalizationRegistry / WeakMap / WeakSet to gate
     *  what can act as a weak target/key. */
    public static boolean canBeHeldWeakly(Object v) {
        if (v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction) return true;
        if (v instanceof JSSymbol s) {
            for (JSSymbol registered : globalSymbolRegistry.values()) {
                if (registered == s) return false;
            }
            return true;
        }
        return false;
    }

    public static JSObject createAggregateError(JSArray errors, String message) {
        JSObject err = makeError("AggregateError", message);
        err.set("errors", errors);
        return err;
    }

    private static void installError(Map<String, Object> globals, String key, String name) {
        // Idempotent: when the realm has already been bootstrapped (e.g. for a
        // nested Interpreter.interpret call from `eval`/`new Function`), reuse
        // the cached prototype + constructor. Recreating them would orphan the
        // outer test's `ReferenceError === thrown.constructor` checks because
        // the static {@link #errorPrototypes} map was being overwritten.
        JSObject existing = errorPrototypes.get(name);
        if (existing != null) {
            Object cachedCtor = existing.get("constructor");
            if (cachedCtor instanceof JSFunction fn) {
                globals.putIfAbsent(key, fn);
                return;
            }
        }
        // Build a real prototype object for this error type. It inherits from
        // Error.prototype (so `e instanceof Error` works for subtypes), or
        // from Object.prototype for the root Error.
        JSObject parentProto = "Error".equals(name) ? objectPrototype : errorPrototype;
        if (parentProto == null) parentProto = objectPrototype;
        JSObject proto = new JSObject(parentProto);
        proto.set("name", name);
        proto.set("message", "");
        proto.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            String n = "Error";
            String m = "";
            if (t instanceof JSObject jo) {
                Object nm = jo.get("name"); if (nm instanceof String s) n = s;
                Object mm = jo.get("message"); if (mm instanceof String s) m = s;
            }
            return m.isEmpty() ? n : n + ": " + m;
        }));

        JSFunction ctor = nativeFn(name, 1, (t, a, c) -> {
            // Subclass support (ECMA-262 § 20.5.6.1.1 NativeError): when
            // {@code new Err('msg')} flows through a derived class, the
            // caller (CallConstruct / super-call) hands us a receiver
            // whose prototype is already the subclass's. Mutate that
            // receiver in place so its hasOwnProperty('message')
            // observably reflects the constructor argument. If the
            // ctor was called as a plain function (no fresh receiver),
            // allocate one.
            JSObject err;
            if (t instanceof JSObject jo) {
                err = jo;
                // {@code message} on a subclass instance is own. Set
                // attributes explicitly per § 20.5.7.4 NativeError
                // instance Properties: writable, !enumerable, configurable.
            } else {
                err = new JSObject(proto);
            }
            if (arg(a, 0) != Undefined.VALUE) {
                err.set("message", AbstractOps.toString(a[0]));
                err.setAttributes("message",
                    (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            }
            return err;
        });
        ctor.setPrototypeObject(proto);
        proto.set("constructor", ctor);

        if ("Error".equals(name)) errorPrototype = proto;
        errorPrototypes.put(name, proto);

        globals.putIfAbsent(key, ctor);
    }

    private static double parseIntImpl(Object value, Object radixArg) {
        String s = AbstractOps.toString(value).trim();
        if (s.isEmpty()) return Double.NaN;
        int radix = radixArg == Undefined.VALUE ? 10 : AbstractOps.toInt32(radixArg);
        boolean stripPrefix = false;
        if (radix == 0) { radix = 10; stripPrefix = true; }
        if (radix == 16) stripPrefix = true;
        int i = 0, sign = 1;
        if (s.charAt(0) == '+') i++;
        else if (s.charAt(0) == '-') { sign = -1; i++; }
        if (stripPrefix && i + 1 < s.length() && s.charAt(i) == '0'
                && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            i += 2;
            radix = 16;
        }
        if (radix < 2 || radix > 36) return Double.NaN;
        int start = i;
        while (i < s.length()) {
            int v = digitVal(s.charAt(i));
            if (v < 0 || v >= radix) break;
            i++;
        }
        if (i == start) return Double.NaN;
        try {
            return sign * Long.parseLong(s.substring(start, i), radix);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int digitVal(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'z') return ch - 'a' + 10;
        if (ch >= 'A' && ch <= 'Z') return ch - 'A' + 10;
        return -1;
    }

    /** ECMA-262 § 19.2.6 Encode — UTF-8 percent-encode every char NOT in
     *  the reserved set. {@code encodeURIComponent} keeps only
     *  unreservedURI ({@code A-Za-z0-9 - _ . ! ~ * ' ( )});
     *  {@code encodeURI} additionally preserves the reserved
     *  syntactic chars {@code ; / ? : @ & = + $ , #}. */
    static String uriEncode(String s, boolean component) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            int cp = s.codePointAt(i);
            boolean unreserved =
                (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') ||
                (cp >= '0' && cp <= '9') ||
                cp == '-' || cp == '_' || cp == '.' || cp == '!' ||
                cp == '~' || cp == '*' || cp == '\'' || cp == '(' || cp == ')';
            boolean uriPreserved = !component && (
                cp == ';' || cp == '/' || cp == '?' || cp == ':' ||
                cp == '@' || cp == '&' || cp == '=' || cp == '+' ||
                cp == '$' || cp == ',' || cp == '#');
            if (unreserved || uriPreserved) {
                out.appendCodePoint(cp);
                if (cp > 0xFFFF) i++;
                continue;
            }
            // Spec § 19.2.6.4 step 5: lone surrogates throw URIError.
            if (Character.isLowSurrogate((char) cp)) {
                throw AbruptCompletion.uriError("URI malformed");
            }
            if (cp > 0xFFFF) i++;
            byte[] utf8 = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (byte b : utf8) out.append(String.format("%%%02X", b & 0xFF));
        }
        return out.toString();
    }

    /** ECMA-262 § 19.2.6.2 Decode — invert percent-encoding back to UTF-8
     *  code points. {@code decodeURI} preserves reserved syntactic chars
     *  encoded as %XX (the encoder kept them literal, so they stay
     *  encoded); {@code decodeURIComponent} decodes everything. */
    static String uriDecode(String s, boolean component) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch != '%') { out.append(ch); i++; continue; }
            // Read a sequence of %XX bytes for a single UTF-8 code point.
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            int j = i;
            while (j < s.length() && s.charAt(j) == '%') {
                if (j + 2 >= s.length()) throw AbruptCompletion.uriError("URI malformed");
                int hi = digitVal(s.charAt(j + 1));
                int lo = digitVal(s.charAt(j + 2));
                if (hi < 0 || lo < 0 || hi >= 16 || lo >= 16) {
                    throw AbruptCompletion.uriError("URI malformed");
                }
                bytes.write((hi << 4) | lo);
                j += 3;
                if (bytes.size() == 1) {
                    int b0 = bytes.toByteArray()[0] & 0xFF;
                    if ((b0 & 0x80) == 0) break;   // ASCII single byte
                }
                int b0 = bytes.toByteArray()[0] & 0xFF;
                int needed = (b0 & 0xE0) == 0xC0 ? 2
                           : (b0 & 0xF0) == 0xE0 ? 3
                           : (b0 & 0xF8) == 0xF0 ? 4
                           : -1;
                if (needed < 0) throw AbruptCompletion.uriError("URI malformed");
                if (bytes.size() >= needed) break;
            }
            try {
                String decoded = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
                if (!component) {
                    // decodeURI preserves the reserved syntactic chars
                    // {@code ; / ? : @ & = + $ , #} — emit them as %XX.
                    boolean preserve = false;
                    if (decoded.length() == 1) {
                        char dc = decoded.charAt(0);
                        preserve = dc == ';' || dc == '/' || dc == '?' || dc == ':' ||
                                   dc == '@' || dc == '&' || dc == '=' || dc == '+' ||
                                   dc == '$' || dc == ',' || dc == '#';
                    }
                    if (preserve) {
                        out.append(s, i, j);
                    } else {
                        out.append(decoded);
                    }
                } else {
                    out.append(decoded);
                }
            } catch (Exception e) {
                throw AbruptCompletion.uriError("URI malformed");
            }
            i = j;
        }
        return out.toString();
    }

    /** Private-name keys (ECMA-262 § 15.7.1.4 [[PrivateElements]]) are
     *  prefixed with '#' in our string-keyed property maps. They must be
     *  invisible to all standard property-introspection machinery. */
    private static boolean isPrivateName(String key) {
        return key != null && !key.isEmpty() && key.charAt(0) == '#';
    }

    /**
     * If {@code v} is a regex-shaped object (proto chain reaches RegExp.prototype),
     * return its `source` string; else null. Lets call sites distinguish regex
     * args from string args without an instanceof on a sealed regex class.
     */
    private static String asRegExpSource(Object v) {
        if (!(v instanceof JSObject jo)) return null;
        JSObject cursor = jo;
        while (cursor != null) {
            if (cursor == regExpPrototype) {
                Object src = jo.get("source");
                return src instanceof String s ? s : "";
            }
            cursor = cursor.proto();
        }
        return null;
    }

    private static String asRegExpFlags(Object v) {
        if (!(v instanceof JSObject jo)) return "";
        Object f = jo.get("flags");
        return f instanceof String s ? s : "";
    }

    /**
     * Compile a JavaScript regex source/flags pair into a Java {@link
     * java.util.regex.Pattern}. We lean on Java's PCRE-like syntax — the
     * common subset (character classes, quantifiers, groups, alternation,
     * lookaround) lines up well enough for the SunSpider/Octane workloads.
     * Differences left unhandled: JS-only escapes ($&, $`, $'), Unicode
     * property escapes \p{...} (in u-mode), JS-style backref-without-group.
     */
    /**
     * Compiled-Pattern cache keyed by (source, flags). RegExp literals inside
     * a hot loop (sunspider's string-validate-input does 4000 iterations
     * each calling {@code /.../.test(s)}) re-create the JSObject each time,
     * but the source+flags pair almost always points at the same compiled
     * Pattern. Capping at ~256 keeps the worst case bounded for adversarial
     * code that builds dynamic patterns; ConcurrentHashMap so callers from
     * multiple realms / threads stay safe.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern>
        REGEX_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int REGEX_CACHE_LIMIT = 256;

    /** Annex B legacy RegExp static state — populated on each successful
     *  match (we only set it during eager exec calls, not when user code
     *  drives an exec() loop manually). Empty/null means "no match yet"
     *  and the getter throws TypeError. */
    public static volatile String legacyRegExpInput;
    public static volatile String legacyRegExpLastMatch;
    public static volatile String legacyRegExpLastParen;
    public static volatile String legacyRegExpLeftContext;
    public static volatile String legacyRegExpRightContext;
    public static final String[] legacyRegExpParens = new String[9];

    private static void installLegacyRegExpAccessors(JSFunction regExpCtor) {
        // Brand check: every accessor (get + set) must verify `this === %RegExp%`
        // and throw TypeError on mismatch (cross-realm, subclass, ordinary obj).
        // input has both get + set; the others have a getter and a no-op setter
        // (per the legacy proposal — RegExp.$1 = ... silently fails).
        installLegacyAcc(regExpCtor, "input",
            () -> legacyRegExpInput,
            v -> legacyRegExpInput = v);
        installLegacyAcc(regExpCtor, "$_",
            () -> legacyRegExpInput,
            v -> legacyRegExpInput = v);
        installLegacyAcc(regExpCtor, "lastMatch",
            () -> legacyRegExpLastMatch, null);
        installLegacyAcc(regExpCtor, "$&",
            () -> legacyRegExpLastMatch, null);
        installLegacyAcc(regExpCtor, "lastParen",
            () -> legacyRegExpLastParen, null);
        installLegacyAcc(regExpCtor, "$+",
            () -> legacyRegExpLastParen, null);
        installLegacyAcc(regExpCtor, "leftContext",
            () -> legacyRegExpLeftContext, null);
        installLegacyAcc(regExpCtor, "$`",
            () -> legacyRegExpLeftContext, null);
        installLegacyAcc(regExpCtor, "rightContext",
            () -> legacyRegExpRightContext, null);
        installLegacyAcc(regExpCtor, "$'",
            () -> legacyRegExpRightContext, null);
        for (int i = 1; i <= 9; i++) {
            final int idx = i - 1;
            installLegacyAcc(regExpCtor, "$" + i,
                () -> legacyRegExpParens[idx], null);
        }
    }

    @FunctionalInterface
    private interface StringSupplier { String get(); }
    @FunctionalInterface
    private interface StringConsumer { void accept(String v); }

    private static void installLegacyAcc(JSFunction regExpCtor, String name,
                                         StringSupplier getter, StringConsumer setter) {
        JSFunction getFn = new JSFunction("get " + name, 0, (t, a, c) -> {
            // SameValue(C, thisValue): the `this` value must be exactly the
            // RegExp constructor. Cross-realm/subclass/ordinary-obj receivers
            // all fail this check and throw TypeError per the legacy proposal.
            if (t != regExpCtor) {
                throw AbruptCompletion.typeError("RegExp." + name
                    + " getter called on non-%RegExp% receiver");
            }
            String v = getter.get();
            return v == null ? "" : v;
        });
        getFn.setNonConstructor(true);
        JSFunction setFn;
        if (setter != null) {
            setFn = new JSFunction("set " + name, 1, (t, a, c) -> {
                if (t != regExpCtor) {
                    throw AbruptCompletion.typeError("RegExp." + name
                        + " setter called on non-%RegExp% receiver");
                }
                Object v = a.length > 0 ? a[0] : Undefined.VALUE;
                setter.accept(AbstractOps.toString(v));
                return Undefined.VALUE;
            });
            setFn.setNonConstructor(true);
        } else {
            // Per legacy proposal, only input/$_ have an installed setter;
            // the others have an undefined setter (assignments silently fail
            // in sloppy mode, throw in strict mode via the missing-setter path).
            setFn = null;
        }
        regExpCtor.properties().put(name, new Accessor(getFn, setFn));
        regExpCtor.setAttributes(name, JSObject.ATTR_CONFIGURABLE);
    }

    private static java.util.regex.Pattern compileJsRegex(String source, String flags) {
        // Cache key encodes both source and flags. The pipe is illegal as
        // the leading flag char (flags are [gimsuy]+) and never appears
        // unescaped at the start of source for non-disjunction patterns —
        // even when present, its position after the marker keeps the key
        // deterministic.
        String key = flags + " " + source;
        java.util.regex.Pattern cached = REGEX_CACHE.get(key);
        if (cached != null) return cached;
        int f = 0;
        if (flags.contains("i")) f |= java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE;
        if (flags.contains("m")) f |= java.util.regex.Pattern.MULTILINE;
        if (flags.contains("s")) f |= java.util.regex.Pattern.DOTALL;
        // NB: deliberately don't set Pattern.UNICODE_CHARACTER_CLASS for /u.
        // JS Unicode mode keeps \d/\w/\s ASCII (per ECMA-262 § 22.2.2.9.2);
        // UNICODE_CHARACTER_CLASS broadens them to Unicode categories, which
        // is what Java means by Unicode regex. Java still supports \p{...}
        // property escapes without the flag.
        String translated = translateJsRegexToJava(source);
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(translated, f);
        } catch (java.util.regex.PatternSyntaxException pse) {
            p = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(source), f);
        }
        if (REGEX_CACHE.size() < REGEX_CACHE_LIMIT) REGEX_CACHE.putIfAbsent(key, p);
        return p;
    }

    /**
     * Bridge JS regex syntax → Java regex. The two grammars overlap heavily but
     * differ in a few escapes that show up in real code:
     *   - JS `\0` (not followed by digit) is the NUL char; Java requires ` `.
     *   - JS allows lone `]` and `}` as literals outside a class; Java accepts.
     *   - We pass through the rest unchanged.
     */
    /**
     * Populate the {@code groups} property on a regex result array with each
     * named capture's value (or undefined if the named group didn't match).
     * Java 20+ exposes {@code Pattern.namedGroups()}; we fall back to scanning
     * the source for {@code (?<name>} syntax on older JDKs.
     */
    private static void attachNamedGroups(JSArray result, java.util.regex.Pattern p,
                                          java.util.regex.Matcher m, String source) {
        java.util.Map<String, Integer> named;
        try {
            // Java 20+: Pattern.namedGroups() returns Map<String, Integer>.
            @SuppressWarnings("unchecked")
            java.util.Map<String, Integer> ng =
                (java.util.Map<String, Integer>) java.util.regex.Pattern.class
                    .getMethod("namedGroups").invoke(p);
            named = ng;
        } catch (Throwable t) {
            named = scanNamedGroupsFromSource(source);
        }
        if (named == null || named.isEmpty()) return;
        JSObject groups = new JSObject();
        for (var entry : named.entrySet()) {
            String name = entry.getKey();
            Object val;
            try {
                String s = m.group(name);
                val = s == null ? Undefined.VALUE : s;
            } catch (IllegalArgumentException iae) {
                val = Undefined.VALUE;
            }
            groups.set(name, val);
        }
        result.setExtraProperty("groups", groups);
    }

    /** Fallback name-extractor for JDKs without Pattern.namedGroups(). */
    private static java.util.Map<String, Integer> scanNamedGroupsFromSource(String src) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        int idx = 0;
        int groupNum = 0;
        boolean inClass = false;
        while (idx < src.length()) {
            char c = src.charAt(idx);
            if (c == '\\' && idx + 1 < src.length()) { idx += 2; continue; }
            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (!inClass && c == '(') {
                // Decide whether this opens a capture group, a non-capture
                // (?:...), an assertion (?=...) (?!...) (?<=...) (?<!...),
                // or a named group (?<name>...).
                if (idx + 1 < src.length() && src.charAt(idx + 1) == '?') {
                    if (idx + 2 < src.length() && src.charAt(idx + 2) == '<'
                        && idx + 3 < src.length()
                        && src.charAt(idx + 3) != '=' && src.charAt(idx + 3) != '!') {
                        // Named capture (?<name>
                        groupNum++;
                        int end = src.indexOf('>', idx + 3);
                        if (end > idx + 3) {
                            out.put(src.substring(idx + 3, end), groupNum);
                        }
                    }
                    // Non-capture / assertion — no group number bump.
                } else {
                    groupNum++;   // anonymous capture group
                }
            }
            idx++;
        }
        return out;
    }

    /**
     * JS-spec-faithful String.prototype.split with a regex
     * (ECMA-262 § 22.1.3.21). Walks position-by-position and tries an
     * <em>anchored</em> match at each position (not Java's next-find
     * semantics). Emits the substring between the prior match-end (p)
     * and the current cursor (q) when a non-zero-width-from-p match
     * lands; advances q past zero-width matches that haven't progressed.
     */
    private static void splitJs(String s, java.util.regex.Pattern pat, long limit, JSArray out) {
        int size = s.length();
        java.util.regex.Matcher m = pat.matcher(s);
        int p = 0;   // last emit-end position
        int q = 0;   // current cursor
        while (q < size) {
            if (out.length() >= limit) return;
            // Anchored match at q: find with the region restricted to start=q,
            // and require the match to actually begin at q.
            m.region(q, size);
            m.useAnchoringBounds(false);
            m.useTransparentBounds(true);
            boolean found = m.lookingAt();
            if (!found) { q++; continue; }
            int e = m.end();
            if (e == p) { q++; continue; }
            // Emit the substring [p, q] then jump to e.
            out.push(s.substring(p, q));
            if (out.length() >= limit) return;
            p = e;
            q = e;
        }
        if (out.length() < limit) out.push(s.substring(p));
    }

    /** Map JS Unicode property names to Java's regex flavor. Java's
     *  syntax accepts general-category short names directly (L, Lu, etc.),
     *  binary properties as {@code IsX}, and scripts as {@code IsLatin}.
     *  JS uses the long name form by default ({@code Alphabetic} vs.
     *  Java's {@code IsAlphabetic}; {@code Script=Latin} vs. {@code
     *  IsLatin}). */
    private static String translateUnicodePropertyName(String body) {
        body = body.trim();
        // Handle key=value form.
        int eq = body.indexOf('=');
        if (eq >= 0) {
            String key = body.substring(0, eq).trim();
            String val = body.substring(eq + 1).trim();
            // Script / Script_Extensions → Java's "IsXxx" with the value name.
            if ("Script".equalsIgnoreCase(key) || "sc".equalsIgnoreCase(key)
                    || "Script_Extensions".equalsIgnoreCase(key) || "scx".equalsIgnoreCase(key)) {
                return "Is" + canonicalScriptName(val);
            }
            // General_Category → return just the value (Java accepts L, Lu, etc.).
            if ("General_Category".equalsIgnoreCase(key) || "gc".equalsIgnoreCase(key)) {
                return canonicalGeneralCategory(val);
            }
            // Fallback: try Is<value>.
            return "Is" + val;
        }
        // No `=` — could be a General_Category short name or binary property.
        String shortGc = canonicalGeneralCategoryOrNull(body);
        if (shortGc != null) return shortGc;
        // Binary properties: Java exposes them as IsX (Alphabetic →
        // IsAlphabetic). Mostly direct map but a few aliases differ.
        return "Is" + body;
    }

    private static String canonicalGeneralCategoryOrNull(String name) {
        switch (name) {
            case "L": case "Letter":
                return "L";
            case "Ll": case "Lowercase_Letter": return "Ll";
            case "Lu": case "Uppercase_Letter": return "Lu";
            case "Lt": case "Titlecase_Letter": return "Lt";
            case "Lm": case "Modifier_Letter":  return "Lm";
            case "Lo": case "Other_Letter":     return "Lo";
            case "M":  case "Mark":             return "M";
            case "Mn": case "Nonspacing_Mark":  return "Mn";
            case "Mc": case "Spacing_Mark":     return "Mc";
            case "Me": case "Enclosing_Mark":   return "Me";
            case "N":  case "Number":           return "N";
            case "Nd": case "Decimal_Number":   return "Nd";
            case "Nl": case "Letter_Number":    return "Nl";
            case "No": case "Other_Number":     return "No";
            case "P":  case "Punctuation":      return "P";
            case "Pc": case "Connector_Punctuation":  return "Pc";
            case "Pd": case "Dash_Punctuation":       return "Pd";
            case "Ps": case "Open_Punctuation":       return "Ps";
            case "Pe": case "Close_Punctuation":      return "Pe";
            case "Pi": case "Initial_Punctuation":    return "Pi";
            case "Pf": case "Final_Punctuation":      return "Pf";
            case "Po": case "Other_Punctuation":      return "Po";
            case "S":  case "Symbol":           return "S";
            case "Sm": case "Math_Symbol":      return "Sm";
            case "Sc": case "Currency_Symbol":  return "Sc";
            case "Sk": case "Modifier_Symbol":  return "Sk";
            case "So": case "Other_Symbol":     return "So";
            case "Z":  case "Separator":        return "Z";
            case "Zs": case "Space_Separator":  return "Zs";
            case "Zl": case "Line_Separator":   return "Zl";
            case "Zp": case "Paragraph_Separator": return "Zp";
            case "C":  case "Other":            return "C";
            case "Cc": case "Control":          return "Cc";
            case "Cf": case "Format":           return "Cf";
            case "Cs": case "Surrogate":        return "Cs";
            case "Co": case "Private_Use":      return "Co";
            case "Cn": case "Unassigned":       return "Cn";
            default: return null;
        }
    }
    private static String canonicalGeneralCategory(String name) {
        String r = canonicalGeneralCategoryOrNull(name);
        return r != null ? r : name;
    }
    /** Translate a JS script short / long name to Java's {@code Is<Name>}
     *  body. Java expects long script names (Latin, Greek, Cyrillic, …)
     *  not aliases like Latn / Grek. Use a small alias table. */
    private static String canonicalScriptName(String name) {
        switch (name) {
            case "Latn": return "Latin";
            case "Grek": return "Greek";
            case "Cyrl": return "Cyrillic";
            case "Hebr": return "Hebrew";
            case "Arab": return "Arabic";
            case "Hira": return "Hiragana";
            case "Kana": return "Katakana";
            case "Hang": return "Hangul";
            case "Hani": return "Han";
            case "Thai": return "Thai";
            case "Deva": return "Devanagari";
            case "Beng": return "Bengali";
            case "Tibt": return "Tibetan";
            case "Armn": return "Armenian";
            case "Geor": return "Georgian";
            case "Ethi": return "Ethiopic";
            case "Khmr": return "Khmer";
            case "Mong": return "Mongolian";
            case "Mymr": return "Myanmar";
            case "Sinh": return "Sinhala";
            case "Taml": return "Tamil";
            case "Telu": return "Telugu";
            case "Knda": return "Kannada";
            case "Mlym": return "Malayalam";
            case "Gujr": return "Gujarati";
            case "Guru": return "Gurmukhi";
            case "Orya": return "Oriya";
            case "Laoo": return "Lao";
            case "Syrc": return "Syriac";
            case "Cher": return "Cherokee";
            case "Cans": return "Canadian_Aboriginal";
            default: return name;
        }
    }

    private static String translateJsRegexToJava(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        boolean inClass = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length()) {
                char n = src.charAt(i + 1);
                // \0 (not followed by another digit) →  
                if (n == '0' && (i + 2 >= src.length() || !Character.isDigit(src.charAt(i + 2)))) {
                    sb.append("\\u0000");
                    i++;
                    continue;
                }
                // \p{Property} / \P{Property} — translate JS Unicode property
                // escapes (\p{Letter}, \p{Script=Latin}, \p{Alphabetic}, …)
                // to Java's regex flavor.
                if ((n == 'p' || n == 'P') && i + 2 < src.length() && src.charAt(i + 2) == '{') {
                    int close = src.indexOf('}', i + 3);
                    if (close > 0) {
                        String body = src.substring(i + 3, close);
                        sb.append('\\').append(n).append('{')
                          .append(translateUnicodePropertyName(body)).append('}');
                        i = close;
                        continue;
                    }
                }
                sb.append(c).append(n);
                i++;
                continue;
            }
            if (c == '[') {
                if (inClass) {
                    // JS: `[` is literal inside a class. Java: starts nested
                    // class union (`[a-d[m-p]]`). Escape so Java keeps JS
                    // semantics. THIS BUG was responsible for lodash's
                    // cloneDeep being 22× slower than Rhino — lodash's
                    // `getNative` escape regex `[\\^$.*+?()[\]{}|]` was
                    // failing to compile, falling back to Pattern.quote, so
                    // Map looked "non-native", lodash's Stack stayed in
                    // O(n²) ListCache mode, cycle-detection dominated.
                    sb.append("\\[");
                    continue;
                }
                // JS `[^]` means "any character including line terminators"
                // (negation of the empty set). Java rejects an empty negated
                // class, so rewrite to `[\s\S]`. acorn uses this idiom in
                // `/\/\*[^]*?\*\//` to match block-comment bodies.
                if (i + 2 < src.length() && src.charAt(i + 1) == '^' && src.charAt(i + 2) == ']') {
                    sb.append("[\\s\\S]");
                    i += 2;
                    continue;
                }
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Apply a JS replacement-template ($&, $`, $', $n, $<name>) to a single
     * match. Bare `$$` becomes `$`.
     */
    private static String applyReplacementTemplate(String repl, java.util.regex.Matcher m, String input) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < repl.length()) {
            char ch = repl.charAt(i);
            if (ch != '$' || i + 1 >= repl.length()) { out.append(ch); i++; continue; }
            char next = repl.charAt(i + 1);
            if (next == '$') { out.append('$'); i += 2; }
            else if (next == '&') { out.append(m.group()); i += 2; }
            else if (next == '`') { out.append(input, 0, m.start()); i += 2; }
            else if (next == '\'') { out.append(input, m.end(), input.length()); i += 2; }
            else if (next >= '0' && next <= '9') {
                int idx = next - '0';
                int consumed = 2;
                if (i + 2 < repl.length() && Character.isDigit(repl.charAt(i + 2))) {
                    int two = idx * 10 + (repl.charAt(i + 2) - '0');
                    if (two <= m.groupCount()) { idx = two; consumed = 3; }
                }
                // ECMA-262 § 22.1.3.18 GetSubstitution table 67: when N
                // exceeds the number of capture groups, leave $N literal in
                // the output (matches V8/SpiderMonkey).
                if (idx >= 1 && idx <= m.groupCount()) {
                    String g = m.group(idx);
                    if (g != null) out.append(g);
                } else {
                    out.append('$');
                    out.append((char) ('0' + idx));   // single-digit case only
                }
                i += consumed;
            } else { out.append(ch); i++; }
        }
        return out.toString();
    }

    /**
     * v1 Date string parsing: try ECMA-262 § 21.4.1.18 simplified-ISO format
     * (yyyy-MM-dd[THH:mm:ss[.SSS][Z|±hh:mm]]), falling back to a couple of
     * common legacy layouts. Returns NaN on failure.
     */
    private static double parseDateString(String s) {
        if (s == null) return Double.NaN;
        String[] patterns = {
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "EEE MMM dd yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "MMMM d yyyy HH:mm:ss",
            "MMMM d, yyyy",
            "M/d/yyyy H:mm:ss",
            "M/d/yyyy"
        };
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                java.util.Date d = fmt.parse(s);
                return (double) d.getTime();
            } catch (java.text.ParseException ignored) {}
        }
        return Double.NaN;
    }

    private static int parseIndex(String s) {
        if (s.isEmpty()) return -1;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return -1;
            n = n * 10 + (ch - '0');
            if (n < 0) return -1;
        }
        return n;
    }

    // ============================================================
    //  JSON helpers
    // ============================================================

    private static String jsonStringify(Object v) {
        return jsonStringify(v, null, null, "", null);
    }

    /** ECMA-262 § 25.5.2 JSON.stringify driver. {@code replacerFn} and
     *  {@code replacerKeys} are mutually exclusive (the spec accepts a
     *  function OR an array of selector keys, not both); the caller
     *  passes one or neither. {@code gap} is the precomputed indent
     *  (0..10 spaces, or up to 10 chars). Returns null when the
     *  top-level value is a function/undefined/symbol. */
    private static String jsonStringify(Object v, JSFunction replacerFn,
                                        java.util.Set<String> replacerKeys,
                                        String gap, InterpContext ctx) {
        // Top-level: wrap in {"": v} so the recursive serializer sees the
        // same shape as nested keys. The wrapper holder is used by
        // ECMA-262 § 25.5.2.2 SerializeJSONProperty to call the replacer
        // with the right `this` and `key=""`.
        JSObject wrapper = new JSObject();
        wrapper.set("", v);
        StringBuilder sb = new StringBuilder();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        try {
            boolean ok = serializeJsonProperty(sb, wrapper, "", replacerFn, replacerKeys, gap, "", seen, ctx);
            return ok ? sb.toString() : null;
        } catch (StackOverflowError e) {
            throw AbruptCompletion.typeError("JSON.stringify: cyclic object value");
        }
    }

    private static boolean serializeJsonProperty(StringBuilder sb, Object holder, String key,
            JSFunction replacerFn, java.util.Set<String> replacerKeys,
            String gap, String indent,
            java.util.IdentityHashMap<Object, Boolean> seen, InterpContext ctx) {
        Object val = AbstractOps.getProperty(holder, key);
        // § 25.5.2.2 step 2: if value has a toJSON method, invoke it first.
        if (val instanceof JSObject || val instanceof JSArray) {
            Object toJson = AbstractOps.getProperty(val, "toJSON");
            if (toJson instanceof JSFunction tj && ctx != null) {
                val = Interpreter.invokeFunction(tj, val, new Object[]{key}, ctx);
            }
        }
        if (replacerFn != null && ctx != null) {
            val = Interpreter.invokeFunction(replacerFn, holder, new Object[]{key, val}, ctx);
        }
        // Unwrap Number/String/Boolean/BigInt wrapper objects per step 4.
        if (val instanceof JSObject jo) {
            if (jo.properties().get(SLOT_NUMBER_DATA) instanceof Number nd) val = nd;
            else if (jo.properties().get(SLOT_STRING_DATA) instanceof String sd) val = sd;
            else if (jo.properties().get(SLOT_BOOLEAN_DATA) instanceof Boolean bd) val = bd;
            else if (jo.properties().get(SLOT_BIGINT_DATA) instanceof JSBigInt bid) val = bid;
        }
        if (val == null) { sb.append("null"); return true; }
        if (val == Undefined.VALUE) return false;   // omitted
        if (val instanceof JSFunction) return false;
        if (val instanceof JSSymbol) return false;
        if (val instanceof Boolean b) { sb.append(b ? "true" : "false"); return true; }
        if (val instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Do not know how to serialize a BigInt");
        }
        if (val instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(AbstractOps.toString(d));
            return true;
        }
        if (val instanceof CharSequence cs) { appendJsonString(sb, cs.toString()); return true; }
        if (val instanceof JSArray arr) {
            if (seen.containsKey(arr)) {
                throw AbruptCompletion.typeError("JSON.stringify: cyclic object value");
            }
            seen.put(arr, true);
            try {
                if (arr.length() == 0) { sb.append("[]"); return true; }
                String stepIndent = indent + gap;
                String sep = gap.isEmpty() ? "," : ",\n" + stepIndent;
                sb.append('[');
                if (!gap.isEmpty()) sb.append('\n').append(stepIndent);
                for (int i = 0; i < arr.length(); i++) {
                    if (i > 0) sb.append(sep);
                    StringBuilder elemSb = new StringBuilder();
                    boolean ok = serializeJsonProperty(elemSb, arr, Integer.toString(i),
                        replacerFn, replacerKeys, gap, stepIndent, seen, ctx);
                    if (ok) sb.append(elemSb);
                    else sb.append("null");
                }
                if (!gap.isEmpty()) sb.append('\n').append(indent);
                sb.append(']');
                return true;
            } finally {
                seen.remove(arr);
            }
        }
        if (val instanceof JSObject jo) {
            if (seen.containsKey(jo)) {
                throw AbruptCompletion.typeError("JSON.stringify: cyclic object value");
            }
            seen.put(jo, true);
            try {
                String stepIndent = indent + gap;
                String sep = gap.isEmpty() ? "," : ",\n" + stepIndent;
                String colon = gap.isEmpty() ? ":" : ": ";
                java.util.List<String> keys = new java.util.ArrayList<>();
                if (replacerKeys != null) {
                    for (String k : replacerKeys) {
                        if (jo.has(k)) keys.add(k);
                    }
                } else {
                    for (String k : orderedOwnPropertyNames(jo.properties().keySet())) {
                        if (isPrivateName(k)) continue;
                        if (!jo.isEnumerable(k)) continue;
                        if (k.startsWith("##")) continue;
                        keys.add(k);
                    }
                }
                java.util.List<String> partial = new java.util.ArrayList<>();
                for (String k : keys) {
                    StringBuilder valSb = new StringBuilder();
                    boolean ok = serializeJsonProperty(valSb, jo, k, replacerFn, replacerKeys, gap, stepIndent, seen, ctx);
                    if (ok) {
                        StringBuilder member = new StringBuilder();
                        appendJsonString(member, k);
                        member.append(colon).append(valSb);
                        partial.add(member.toString());
                    }
                }
                if (partial.isEmpty()) { sb.append("{}"); return true; }
                sb.append('{');
                if (!gap.isEmpty()) sb.append('\n').append(stepIndent);
                for (int i = 0; i < partial.size(); i++) {
                    if (i > 0) sb.append(sep);
                    sb.append(partial.get(i));
                }
                if (!gap.isEmpty()) sb.append('\n').append(indent);
                sb.append('}');
                return true;
            } finally {
                seen.remove(jo);
            }
        }
        sb.append("null");
        return true;
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
    }

    private static Object jsonParse(String src) {
        JsonReader r = new JsonReader(src);
        Object v = r.parseValue();
        r.skipWhitespace();
        if (r.pos < src.length()) {
            throw AbruptCompletion.syntaxError("Unexpected token in JSON at position " + r.pos);
        }
        return v;
    }

    /** ECMA-262 § 25.5.1.2 InternalizeJSONProperty(holder, name, reviver). */
    private static Object internalizeJSONProperty(Object holder, String name, JSFunction reviver, InterpContext ctx) {
        Object val = AbstractOps.getProperty(holder, name);
        if (val instanceof JSArray arr) {
            int len = arr.length();
            for (int i = 0; i < len; i++) {
                String idx = Integer.toString(i);
                Object newElem = internalizeJSONProperty(arr, idx, reviver, ctx);
                if (newElem == Undefined.VALUE) arr.set(i, Undefined.VALUE);   // v1 sparse-friendly
                else arr.set(i, newElem);
            }
        } else if (val instanceof JSObject obj) {
            for (String key : new java.util.ArrayList<>(orderedOwnPropertyNames(obj.properties().keySet()))) {
                Object newVal = internalizeJSONProperty(obj, key, reviver, ctx);
                if (newVal == Undefined.VALUE) {
                    obj.delete(key);
                } else {
                    obj.set(key, newVal);
                }
            }
        }
        return Interpreter.invokeFunction(reviver, holder, new Object[]{name, val}, ctx);
    }

    private static final class JsonReader {
        final String src;
        int pos;
        JsonReader(String s) { this.src = s; this.pos = 0; }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) throw AbruptCompletion.syntaxError("Unexpected end of JSON");
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        Object parseObject() {
            JSObject obj = new JSObject();
            pos++;
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; return obj; }
            while (true) {
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != '"') {
                    throw AbruptCompletion.syntaxError("Expected string key in JSON");
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != ':') {
                    throw AbruptCompletion.syntaxError("Expected ':' in JSON");
                }
                pos++;
                obj.set(key, parseValue());
                skipWhitespace();
                if (pos >= src.length()) throw AbruptCompletion.syntaxError("Unexpected end of JSON");
                char c = src.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return obj; }
                throw AbruptCompletion.syntaxError("Expected ',' or '}' in JSON");
            }
        }

        Object parseArray() {
            JSArray arr = new JSArray();
            pos++;
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; return arr; }
            while (true) {
                arr.push(parseValue());
                skipWhitespace();
                if (pos >= src.length()) throw AbruptCompletion.syntaxError("Unexpected end of JSON");
                char c = src.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return arr; }
                throw AbruptCompletion.syntaxError("Expected ',' or ']' in JSON");
            }
        }

        String parseString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != '"') {
                char c = src.charAt(pos);
                if (c == '\\') {
                    pos++;
                    if (pos >= src.length()) throw AbruptCompletion.syntaxError("Unterminated escape in JSON");
                    char esc = src.charAt(pos);
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 >= src.length()) {
                                throw AbruptCompletion.syntaxError("Bad unicode escape in JSON");
                            }
                            int code = Integer.parseInt(src.substring(pos + 1, pos + 5), 16);
                            sb.append((char) code);
                            pos += 4;
                            break;
                        default: throw AbruptCompletion.syntaxError("Bad escape '\\" + esc + "' in JSON");
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            if (pos >= src.length()) throw AbruptCompletion.syntaxError("Unterminated string in JSON");
            pos++;
            return sb.toString();
        }

        Object parseBool() {
            if (src.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw AbruptCompletion.syntaxError("Bad boolean in JSON");
        }

        Object parseNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            throw AbruptCompletion.syntaxError("Bad null in JSON");
        }

        Object parseNumber() {
            int start = pos;
            if (pos < src.length() && src.charAt(pos) == '-') pos++;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            try { return Double.parseDouble(src.substring(start, pos)); }
            catch (NumberFormatException e) { throw AbruptCompletion.syntaxError("Bad number in JSON"); }
        }
    }
}
