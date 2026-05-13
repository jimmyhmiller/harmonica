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
    public static volatile JSObject promisePrototype;
    /** ECMA-262 § 27.5.1 Generator Prototype (the prototype shared by all generator instances). */
    public static volatile JSObject generatorPrototype;
    public static volatile JSObject mapPrototype;
    public static volatile JSObject setPrototype;
    public static volatile JSObject weakMapPrototype;
    public static volatile JSObject weakSetPrototype;
    public static volatile JSObject errorPrototype;
    public static volatile JSObject regExpPrototype;
    /**
     * Original {@code Array.prototype[@@iterator]} (the {@code values} fn) and
     * {@code String.prototype[@@iterator]}, captured at bootstrap so the
     * iterator-fast-path in {@link Op.GetIterator} can detect monkey-patched
     * overrides and fall back to the @@iterator slow path.
     */
    public static volatile JSFunction defaultArrayIterator;
    public static volatile JSFunction defaultStringIterator;
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
    public static volatile JSSymbol wellKnownUnscopables;

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
     */
    private static final java.util.Map<JSObject, Shape> EMPTY_OBJECT_SHAPES =
        new java.util.IdentityHashMap<>();
    private static volatile Shape rootShapeForNullProto;

    public static Shape shapeForEmptyObject(JSObject proto) {
        if (proto == null) {
            Shape s = rootShapeForNullProto;
            if (s != null) return s;
            synchronized (EMPTY_OBJECT_SHAPES) {
                s = rootShapeForNullProto;
                if (s == null) {
                    s = Shape.root(null);
                    rootShapeForNullProto = s;
                }
                return s;
            }
        }
        synchronized (EMPTY_OBJECT_SHAPES) {
            Shape s = EMPTY_OBJECT_SHAPES.get(proto);
            if (s == null) {
                s = Shape.root(proto);
                EMPTY_OBJECT_SHAPES.put(proto, s);
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
    static final String SLOT_STRING_DATA  = "##StringData##";

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
        prototypesReady = false;
        objectPrototype = null;
        functionPrototype = null;
        arrayPrototype = null;
        stringPrototype = null;
        numberPrototype = null;
        booleanPrototype = null;
        symbolPrototype = null;
        promisePrototype = null;
        generatorPrototype = null;
        mapPrototype = null;
        setPrototype = null;
        weakMapPrototype = null;
        weakSetPrototype = null;
        errorPrototype = null;
        errorPrototypes.clear();
        regExpPrototype = null;
        defaultArrayIterator = null;
        defaultStringIterator = null;
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
        wellKnownUnscopables = null;
        throwTypeError = null;
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
        wellKnownUnscopables        = JSSymbol.wellKnown("Symbol.unscopables");

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

        prototypesReady = true;
    }

    private static JSFunction nativeFn(String name, int arity, NativeBody body) {
        return new JSFunction(name, arity, body);
    }

    private static Object arg(Object[] a, int i) {
        return i < a.length ? a[i] : Undefined.VALUE;
    }

    private static int sliceIndex(Object v, int len, int dflt) {
        if (v == Undefined.VALUE) return dflt;
        int n = AbstractOps.toInt32(v);
        if (n < 0) return Math.max(0, len + n);
        return Math.min(len, n);
    }

    // ============================================================
    //  Object.prototype
    // ============================================================

    private static void installObjectPrototype() {
        objectPrototype.set("toString", nativeFn("toString", 0, (thisVal, a, c) -> {
            if (thisVal instanceof JSArray)    return "[object Array]";
            if (thisVal instanceof JSFunction) return "[object Function]";
            if (thisVal == null)               return "[object Null]";
            if (thisVal == Undefined.VALUE)    return "[object Undefined]";
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
                return idx >= 0 && idx < arr.length();
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
        functionPrototype.set("call", nativeFn("call", 1, (thisVal, a, c) -> {
            if (!(thisVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("Function.prototype.call called on non-function");
            }
            Object newThis = arg(a, 0);
            // Reuse the per-thread args pool — lodash calls Function.prototype.call
            // tens of thousands of times in iteratee dispatch, and the
            // `new Object[a.length-1]` allocation here was 4%+ of allocated
            // bytes in the workload's profile.
            int n = Math.max(0, a.length - 1);
            Object[] callArgs = Interpreter.acquireArgs(n);
            for (int i = 1; i < a.length; i++) callArgs[i - 1] = a[i];
            try {
                return Interpreter.invokeFunction(fn, newThis, callArgs, c);
            } finally {
                Interpreter.releaseArgs(callArgs);
            }
        }));
        functionPrototype.set("apply", nativeFn("apply", 2, (thisVal, a, c) -> {
            if (!(thisVal instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("Function.prototype.apply called on non-function");
            }
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
                return Interpreter.invokeFunction(fn, newThis, callArgs, c);
            } finally {
                Interpreter.releaseArgs(callArgs);
            }
        }));
        functionPrototype.set("bind", nativeFn("bind", 1, (thisVal, a, c) -> {
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
    }

    // ============================================================
    //  Array.prototype
    // ============================================================

    private static JSArray asArray(Object v, String method) {
        if (v instanceof JSArray a) return a;
        throw AbruptCompletion.typeError("Array.prototype." + method + " called on non-array");
    }

    private static void installArrayPrototype() {
        arrayPrototype.set("push", nativeFn("push", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "push");
            for (Object v : a) arr.push(v);
            return (double) arr.length();
        }));
        arrayPrototype.set("pop", nativeFn("pop", 0, (t, a, c) -> {
            JSArray arr = asArray(t, "pop");
            if (arr.length() == 0) return Undefined.VALUE;
            return arr.elements().remove(arr.length() - 1);
        }));
        arrayPrototype.set("shift", nativeFn("shift", 0, (t, a, c) -> {
            JSArray arr = asArray(t, "shift");
            if (arr.length() == 0) return Undefined.VALUE;
            return arr.elements().remove(0);
        }));
        arrayPrototype.set("unshift", nativeFn("unshift", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "unshift");
            for (int i = 0; i < a.length; i++) arr.elements().add(i, a[i]);
            return (double) arr.length();
        }));
        arrayPrototype.set("slice", nativeFn("slice", 2, (t, a, c) -> {
            JSArray arr = asArray(t, "slice");
            int len = arr.length();
            int start = sliceIndex(arg(a, 0), len, 0);
            int end = sliceIndex(arg(a, 1), len, len);
            if (end < start) end = start;
            JSArray out = new JSArray();
            for (int i = start; i < end; i++) out.push(arr.get(i));
            return out;
        }));
        arrayPrototype.set("concat", nativeFn("concat", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "concat");
            JSArray out = new JSArray();
            for (Object e : arr.elements()) out.push(e);
            for (Object x : a) {
                if (x instanceof JSArray other) for (Object e : other.elements()) out.push(e);
                else out.push(x);
            }
            return out;
        }));
        arrayPrototype.set("join", nativeFn("join", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "join");
            String sep = arg(a, 0) == Undefined.VALUE ? "," : AbstractOps.toString(a[0]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(sep);
                Object e = arr.get(i);
                if (e != null && e != Undefined.VALUE) sb.append(AbstractOps.toString(e));
            }
            return sb.toString();
        }));
        arrayPrototype.set("indexOf", nativeFn("indexOf", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "indexOf");
            Object target = arg(a, 0);
            for (int i = 0; i < arr.length(); i++) {
                if (AbstractOps.strictlyEquals(arr.get(i), target)) return (double) i;
            }
            return -1.0;
        }));
        arrayPrototype.set("lastIndexOf", nativeFn("lastIndexOf", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "lastIndexOf");
            Object target = arg(a, 0);
            for (int i = arr.length() - 1; i >= 0; i--) {
                if (AbstractOps.strictlyEquals(arr.get(i), target)) return (double) i;
            }
            return -1.0;
        }));
        arrayPrototype.set("includes", nativeFn("includes", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "includes");
            Object target = arg(a, 0);
            for (Object e : arr.elements()) if (AbstractOps.strictlyEquals(e, target)) return true;
            return false;
        }));
        arrayPrototype.set("reverse", nativeFn("reverse", 0, (t, a, c) -> {
            JSArray arr = asArray(t, "reverse");
            java.util.Collections.reverse(arr.elements());
            return arr;
        }));
        arrayPrototype.set("forEach", nativeFn("forEach", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "forEach");
            JSFunction fn = asCallback(arg(a, 0), "forEach");
            for (int i = 0; i < arr.length(); i++) {
                Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{arr.get(i), (double) i, arr}, c);
            }
            return Undefined.VALUE;
        }));
        arrayPrototype.set("map", nativeFn("map", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "map");
            JSFunction fn = asCallback(arg(a, 0), "map");
            JSArray out = new JSArray();
            for (int i = 0; i < arr.length(); i++) {
                out.push(Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{arr.get(i), (double) i, arr}, c));
            }
            return out;
        }));
        arrayPrototype.set("filter", nativeFn("filter", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "filter");
            JSFunction fn = asCallback(arg(a, 0), "filter");
            JSArray out = new JSArray();
            for (int i = 0; i < arr.length(); i++) {
                Object v = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{arr.get(i), (double) i, arr}, c);
                if (AbstractOps.toBoolean(v)) out.push(arr.get(i));
            }
            return out;
        }));
        arrayPrototype.set("reduce", nativeFn("reduce", 2, (t, a, c) -> {
            JSArray arr = asArray(t, "reduce");
            JSFunction fn = asCallback(arg(a, 0), "reduce");
            int start;
            Object acc;
            if (a.length >= 2) {
                acc = a[1];
                start = 0;
            } else {
                if (arr.length() == 0) {
                    throw AbruptCompletion.typeError("Reduce of empty array with no initial value");
                }
                acc = arr.get(0);
                start = 1;
            }
            for (int i = start; i < arr.length(); i++) {
                acc = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{acc, arr.get(i), (double) i, arr}, c);
            }
            return acc;
        }));
        arrayPrototype.set("find", nativeFn("find", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "find");
            JSFunction fn = asCallback(arg(a, 0), "find");
            for (int i = 0; i < arr.length(); i++) {
                Object v = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{arr.get(i), (double) i, arr}, c);
                if (AbstractOps.toBoolean(v)) return arr.get(i);
            }
            return Undefined.VALUE;
        }));
        arrayPrototype.set("findIndex", nativeFn("findIndex", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "findIndex");
            JSFunction fn = asCallback(arg(a, 0), "findIndex");
            for (int i = 0; i < arr.length(); i++) {
                Object v = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{arr.get(i), (double) i, arr}, c);
                if (AbstractOps.toBoolean(v)) return (double) i;
            }
            return -1.0;
        }));
        arrayPrototype.set("some", nativeFn("some", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "some");
            JSFunction fn = asCallback(arg(a, 0), "some");
            for (int i = 0; i < arr.length(); i++) {
                Object v = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{arr.get(i), (double) i, arr}, c);
                if (AbstractOps.toBoolean(v)) return true;
            }
            return false;
        }));
        arrayPrototype.set("every", nativeFn("every", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "every");
            JSFunction fn = asCallback(arg(a, 0), "every");
            for (int i = 0; i < arr.length(); i++) {
                Object v = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{arr.get(i), (double) i, arr}, c);
                if (!AbstractOps.toBoolean(v)) return false;
            }
            return true;
        }));
        arrayPrototype.set("sort", nativeFn("sort", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "sort");
            Object cmpArg = arg(a, 0);
            JSFunction cmp = (cmpArg instanceof JSFunction f) ? f : null;
            // Hoisted comparator args buffer — Rhino's NativeArray does the
            // same (`Object[] cmpBuf = new Object[2]`). lodash's
            // `_.sortBy(data, ['group','id'])` over 5,000 items triggers
            // ~50,000 comparator invocations; allocating `new Object[]{x, y}`
            // per call was the largest single avoidable allocation in the
            // sort path.
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
                return AbstractOps.toString(x).compareTo(AbstractOps.toString(y));
            };
            arr.elements().sort(comparator);
            return arr;
        }));
        arrayPrototype.set("flat", nativeFn("flat", 0, (t, a, c) -> {
            JSArray arr = asArray(t, "flat");
            int depth = arg(a, 0) == Undefined.VALUE ? 1 : AbstractOps.toInt32(a[0]);
            JSArray out = new JSArray();
            flattenInto(arr, out, depth);
            return out;
        }));
        arrayPrototype.set("flatMap", nativeFn("flatMap", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "flatMap");
            JSFunction fn = asCallback(arg(a, 0), "flatMap");
            JSArray out = new JSArray();
            for (int i = 0; i < arr.length(); i++) {
                Object v = Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{arr.get(i), (double) i, arr}, c);
                if (v instanceof JSArray inner) for (Object e : inner.elements()) out.push(e);
                else out.push(v);
            }
            return out;
        }));
        arrayPrototype.set("fill", nativeFn("fill", 1, (t, a, c) -> {
            JSArray arr = asArray(t, "fill");
            Object value = arg(a, 0);
            int len = arr.length();
            int start = sliceIndex(arg(a, 1), len, 0);
            int end = sliceIndex(arg(a, 2), len, len);
            for (int i = start; i < end; i++) arr.set(i, value);
            return arr;
        }));
        arrayPrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            JSArray arr = asArray(t, "toString");
            return arr.toString();
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
    private static JSObject makeArrayLikeIterator(Object receiver, String kind) {
        if (receiver == null || receiver == Undefined.VALUE) {
            throw AbruptCompletion.typeError(
                "Array.prototype iterator: this is " + (receiver == null ? "null" : "undefined"));
        }
        if (receiver instanceof JSArray a) return makeArrayIterator(a, kind);
        // Snapshot the length once at iterator-create per spec.
        int len;
        if (receiver instanceof String s) {
            len = s.length();
        } else {
            Object lenVal = AbstractOps.getProperty(receiver, "length");
            len = (int) AbstractOps.toNumber(lenVal);
            if (len < 0) len = 0;
        }
        final int finalLen = len;
        final Object recv = receiver;
        JSObject iter = new JSObject();
        int[] idx = {0};
        iter.set("next", nativeFn("next", 0, (t, a, c) -> {
            JSObject result = new JSObject();
            if (idx[0] < finalLen) {
                Object value = recv instanceof String s
                    ? String.valueOf(s.charAt(idx[0]))
                    : AbstractOps.getProperty(recv, Integer.toString(idx[0]));
                Object payload = switch (kind) {
                    case "key" -> (double) idx[0];
                    case "key+value" -> {
                        JSArray pair = new JSArray();
                        pair.push((double) idx[0]);
                        pair.push(value);
                        yield pair;
                    }
                    default -> value;
                };
                idx[0]++;
                result.set("value", payload);
                result.set("done", false);
            } else {
                result.set("value", Undefined.VALUE);
                result.set("done", true);
            }
            return result;
        }));
        iter.set(wellKnownIterator.asPropertyKey(), nativeFn("[Symbol.iterator]", 0,
            (t, a, c) -> t));
        return iter;
    }

    private static JSObject makeArrayIterator(JSArray arr, String kind) {
        JSObject iter = new JSObject();
        int[] idx = {0};
        iter.set("next", nativeFn("next", 0, (t, a, c) -> {
            JSObject result = new JSObject();
            if (idx[0] < arr.length()) {
                Object payload = switch (kind) {
                    case "key" -> (double) idx[0];
                    case "key+value" -> {
                        JSArray pair = new JSArray();
                        pair.push((double) idx[0]);
                        pair.push(arr.get(idx[0]));
                        yield pair;
                    }
                    default -> arr.get(idx[0]);
                };
                idx[0]++;
                result.set("value", payload);
                result.set("done", false);
            } else {
                result.set("value", Undefined.VALUE);
                result.set("done", true);
            }
            return result;
        }));
        // Per § 23.1.5.2.1, array iterators have @@iterator returning themselves.
        iter.set(wellKnownIterator.asPropertyKey(), nativeFn("[Symbol.iterator]", 0,
            (t, a, c) -> t));
        return iter;
    }

    private static void flattenInto(JSArray src, JSArray dst, int depth) {
        for (Object e : src.elements()) {
            if (e instanceof JSArray inner && depth > 0) flattenInto(inner, dst, depth - 1);
            else dst.push(e);
        }
    }

    private static JSFunction asCallback(Object v, String method) {
        if (v instanceof JSFunction fn) return fn;
        throw AbruptCompletion.typeError("" + method + " callback is not a function");
    }

    // ============================================================
    //  String.prototype
    // ============================================================

    private static void installStringPrototype() {
        stringPrototype.set("toUpperCase", nativeFn("toUpperCase", 0,
            (t, a, c) -> AbstractOps.toString(t).toUpperCase()));
        stringPrototype.set("toLowerCase", nativeFn("toLowerCase", 0,
            (t, a, c) -> AbstractOps.toString(t).toLowerCase()));
        stringPrototype.set("charAt", nativeFn("charAt", 1, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int idx = AbstractOps.toInt32(arg(a, 0));
            if (idx < 0 || idx >= s.length()) return "";
            return String.valueOf(s.charAt(idx));
        }));
        stringPrototype.set("charCodeAt", nativeFn("charCodeAt", 1, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int idx = AbstractOps.toInt32(arg(a, 0));
            if (idx < 0 || idx >= s.length()) return Double.NaN;
            return (double) s.charAt(idx);
        }));
        stringPrototype.set("indexOf", nativeFn("indexOf", 1, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            String tgt = AbstractOps.toString(arg(a, 0));
            int from = arg(a, 1) == Undefined.VALUE ? 0 : AbstractOps.toInt32(a[1]);
            return (double) s.indexOf(tgt, Math.max(0, from));
        }));
        stringPrototype.set("lastIndexOf", nativeFn("lastIndexOf", 1, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            String tgt = AbstractOps.toString(arg(a, 0));
            return (double) s.lastIndexOf(tgt);
        }));
        stringPrototype.set("slice", nativeFn("slice", 2, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int len = s.length();
            int start = sliceIndex(arg(a, 0), len, 0);
            int end = sliceIndex(arg(a, 1), len, len);
            if (end < start) end = start;
            return s.substring(start, end);
        }));
        stringPrototype.set("substring", nativeFn("substring", 2, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int len = s.length();
            int x = arg(a, 0) == Undefined.VALUE ? 0 : Math.max(0, Math.min(len, AbstractOps.toInt32(a[0])));
            int y = arg(a, 1) == Undefined.VALUE ? len : Math.max(0, Math.min(len, AbstractOps.toInt32(a[1])));
            return s.substring(Math.min(x, y), Math.max(x, y));
        }));
        stringPrototype.set("split", nativeFn("split", 2, (t, a, c) -> {
            String s = AbstractOps.toString(t);
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
        stringPrototype.set("trim", nativeFn("trim", 0,
            (t, a, c) -> AbstractOps.toString(t).trim()));
        stringPrototype.set("trimStart", nativeFn("trimStart", 0,
            (t, a, c) -> AbstractOps.toString(t).replaceAll("^\\s+", "")));
        stringPrototype.set("trimEnd", nativeFn("trimEnd", 0,
            (t, a, c) -> AbstractOps.toString(t).replaceAll("\\s+$", "")));
        stringPrototype.set("includes", nativeFn("includes", 1, (t, a, c) ->
            AbstractOps.toString(t).contains(AbstractOps.toString(arg(a, 0)))));
        stringPrototype.set("startsWith", nativeFn("startsWith", 1, (t, a, c) ->
            AbstractOps.toString(t).startsWith(AbstractOps.toString(arg(a, 0)))));
        stringPrototype.set("endsWith", nativeFn("endsWith", 1, (t, a, c) ->
            AbstractOps.toString(t).endsWith(AbstractOps.toString(arg(a, 0)))));
        stringPrototype.set("repeat", nativeFn("repeat", 1, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            int n = AbstractOps.toInt32(arg(a, 0));
            if (n < 0) throw AbruptCompletion.rangeError("Invalid count value");
            return s.repeat(n);
        }));
        stringPrototype.set("concat", nativeFn("concat", 1, (t, a, c) -> {
            StringBuilder sb = new StringBuilder(AbstractOps.toString(t));
            for (Object x : a) sb.append(AbstractOps.toString(x));
            return sb.toString();
        }));
        stringPrototype.set("replace", nativeFn("replace", 2, (t, a, c) -> {
            String s = AbstractOps.toString(t);
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
            String s = AbstractOps.toString(t);
            Object pat0 = arg(a, 0);
            String src = asRegExpSource(pat0);
            String flags = src == null ? "" : asRegExpFlags(pat0);
            if (src == null) {
                if (pat0 == Undefined.VALUE) return null;
                src = AbstractOps.toString(pat0);
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
            String s = AbstractOps.toString(t);
            Object pat0 = arg(a, 0);
            String src = asRegExpSource(pat0);
            String flags = src == null ? "" : asRegExpFlags(pat0);
            if (src == null) src = AbstractOps.toString(pat0);
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
        // ECMA-262 § 22.1.3.34 String.prototype [ %Symbol.iterator% ] returns
        // a String Iterator that yields code points (we approximate with
        // chars — accurate for non-surrogate-pair input).
        JSFunction stringIterFn = nativeFn("[Symbol.iterator]", 0, (t, a, c) -> {
            String s = AbstractOps.toString(t);
            JSObject iter = new JSObject();
            int[] idx = {0};
            iter.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject result = new JSObject();
                if (idx[0] < s.length()) {
                    result.set("value", String.valueOf(s.charAt(idx[0])));
                    result.set("done", false);
                    idx[0]++;
                } else {
                    result.set("value", Undefined.VALUE);
                    result.set("done", true);
                }
                return result;
            }));
            iter.set(wellKnownIterator.asPropertyKey(), nativeFn("[Symbol.iterator]", 0, (tt, aa, cc) -> tt));
            return iter;
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
    }

    private static void installBooleanPrototype() {
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

    static JSObject createPromise() {
        JSObject p = new JSObject(promisePrototype);
        p.properties().put(PROM_STATE, "pending");
        p.properties().put(PROM_RESULT, Undefined.VALUE);
        p.properties().put(PROM_FULFILL, new java.util.ArrayList<Runnable>());
        p.properties().put(PROM_REJECT, new java.util.ArrayList<Runnable>());
        return p;
    }

    /**
     * § 27.2.1.4 FulfillPromise: transition pending→fulfilled with value
     * and run the queued fulfill reactions. (We run them synchronously;
     * spec dispatches them as microtasks.)
     */
    static void fulfillPromise(JSObject p, Object value) {
        if (!"pending".equals(p.properties().get(PROM_STATE))) return;
        p.properties().put(PROM_STATE, "fulfilled");
        p.properties().put(PROM_RESULT, value);
        @SuppressWarnings("unchecked")
        java.util.List<Runnable> reactions = (java.util.List<Runnable>) p.properties().get(PROM_FULFILL);
        p.properties().put(PROM_FULFILL, java.util.Collections.<Runnable>emptyList());
        p.properties().put(PROM_REJECT, java.util.Collections.<Runnable>emptyList());
        for (Runnable r : reactions) {
            try { r.run(); } catch (Throwable ignored) { /* reactions swallow */ }
        }
    }

    /** § 27.2.1.5 RejectPromise. */
    static void rejectPromise(JSObject p, Object reason) {
        if (!"pending".equals(p.properties().get(PROM_STATE))) return;
        p.properties().put(PROM_STATE, "rejected");
        p.properties().put(PROM_RESULT, reason);
        @SuppressWarnings("unchecked")
        java.util.List<Runnable> reactions = (java.util.List<Runnable>) p.properties().get(PROM_REJECT);
        p.properties().put(PROM_FULFILL, java.util.Collections.<Runnable>emptyList());
        p.properties().put(PROM_REJECT, java.util.Collections.<Runnable>emptyList());
        for (Runnable r : reactions) {
            try { r.run(); } catch (Throwable ignored) { /* reactions swallow */ }
        }
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
                JSFunction rejectCb = new JSFunction("reject", 1, (t, a, c) -> {
                    rejectPromise(p, arg(a, 0)); return Undefined.VALUE;
                });
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
        mapPrototype.set("delete", nativeFn("delete", 1, (t, a, c) -> mapData(t).remove(arg(a, 0)) != null));
        mapPrototype.set("clear", nativeFn("clear", 0, (t, a, c) -> { mapData(t).clear(); return Undefined.VALUE; }));
        mapPrototype.set("forEach", nativeFn("forEach", 1, (t, a, c) -> {
            JSFunction fn = arg(a, 0) instanceof JSFunction f ? f : null;
            if (fn == null) throw AbruptCompletion.typeError("Map.forEach callback is not a function");
            for (var e : mapData(t).entrySet()) {
                Interpreter.invokeFunction(fn, Undefined.VALUE,
                    new Object[]{e.getValue(), e.getKey(), t}, c);
            }
            return Undefined.VALUE;
        }));
        // § 24.1.3.10 get Map.prototype.size — accessor.
        mapPrototype.set("size", new Accessor(
            nativeFn("get size", 0, (t, a, c) -> (double) mapData(t).size()),
            null));
        // § 24.1.3.6 Map.prototype.entries / @@iterator — yields [key, value] pairs.
        JSFunction mapEntries = nativeFn("entries", 0, (t, a, c) -> {
            java.util.Iterator<java.util.Map.Entry<Object, Object>> it = mapData(t).entrySet().iterator();
            JSObject iter = new JSObject();
            iter.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject result = new JSObject();
                if (it.hasNext()) {
                    var e = it.next();
                    JSArray pair = new JSArray();
                    pair.push(e.getKey());
                    pair.push(e.getValue());
                    result.set("value", pair);
                    result.set("done", false);
                } else {
                    result.set("value", Undefined.VALUE);
                    result.set("done", true);
                }
                return result;
            }));
            iter.set(wellKnownIterator.asPropertyKey(), nativeFn("[Symbol.iterator]", 0,
                (tt, aa, cc) -> tt));
            return iter;
        });
        mapPrototype.set("entries", mapEntries);
        mapPrototype.set("keys", nativeFn("keys", 0, (t, a, c) -> {
            java.util.Iterator<Object> it = mapData(t).keySet().iterator();
            return mapKeysOrValuesIter(it);
        }));
        mapPrototype.set("values", nativeFn("values", 0, (t, a, c) -> {
            java.util.Iterator<Object> it = mapData(t).values().iterator();
            return mapKeysOrValuesIter(it);
        }));
        mapPrototype.set(wellKnownIterator.asPropertyKey(), mapEntries);
    }

    private static JSObject mapKeysOrValuesIter(java.util.Iterator<Object> it) {
        JSObject iter = new JSObject();
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
        iter.set(wellKnownIterator.asPropertyKey(), nativeFn("[Symbol.iterator]", 0, (t, a, c) -> t));
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
            for (Object v : setData(t)) {
                Interpreter.invokeFunction(fn, Undefined.VALUE, new Object[]{v, v, t}, c);
            }
            return Undefined.VALUE;
        }));
        setPrototype.set("size", new Accessor(
            nativeFn("get size", 0, (t, a, c) -> (double) setData(t).size()),
            null));
        // § 24.2.3.10 Set.prototype.values / .keys / @@iterator — yields values.
        JSFunction setValues = nativeFn("values", 0, (t, a, c) -> {
            java.util.Iterator<Object> it = setData(t).iterator();
            return mapKeysOrValuesIter(it);
        });
        setPrototype.set("values", setValues);
        setPrototype.set("keys", setValues);
        setPrototype.set("entries", nativeFn("entries", 0, (t, a, c) -> {
            java.util.Iterator<Object> it = setData(t).iterator();
            JSObject iter = new JSObject();
            iter.set("next", nativeFn("next", 0, (tt, aa, cc) -> {
                JSObject result = new JSObject();
                if (it.hasNext()) {
                    Object v = it.next();
                    JSArray pair = new JSArray();
                    pair.push(v);
                    pair.push(v);
                    result.set("value", pair);
                    result.set("done", false);
                } else {
                    result.set("value", Undefined.VALUE);
                    result.set("done", true);
                }
                return result;
            }));
            iter.set(wellKnownIterator.asPropertyKey(), nativeFn("[Symbol.iterator]", 0, (tt, aa, cc) -> tt));
            return iter;
        }));
        setPrototype.set(wellKnownIterator.asPropertyKey(), setValues);
    }

    private static void installWeakMapPrototype() {
        // v1: backed by an IdentityHashMap so keys compare by reference
        // (matches WeakMap key semantics) but without GC tracking. Strong
        // refs leak — flagged in the deviation list.
        weakMapPrototype.set("get", nativeFn("get", 1, (t, a, c) -> {
            @SuppressWarnings("unchecked")
            java.util.IdentityHashMap<Object, Object> m =
                (java.util.IdentityHashMap<Object, Object>) ((JSObject) t).properties().get(SLOT_WEAK_MAP_DATA);
            Object v = m.get(arg(a, 0));
            return v == null ? Undefined.VALUE : v;
        }));
        weakMapPrototype.set("set", nativeFn("set", 2, (t, a, c) -> {
            @SuppressWarnings("unchecked")
            java.util.IdentityHashMap<Object, Object> m =
                (java.util.IdentityHashMap<Object, Object>) ((JSObject) t).properties().get(SLOT_WEAK_MAP_DATA);
            m.put(arg(a, 0), arg(a, 1));
            return t;
        }));
        weakMapPrototype.set("has", nativeFn("has", 1, (t, a, c) -> {
            @SuppressWarnings("unchecked")
            java.util.IdentityHashMap<Object, Object> m =
                (java.util.IdentityHashMap<Object, Object>) ((JSObject) t).properties().get(SLOT_WEAK_MAP_DATA);
            return m.containsKey(arg(a, 0));
        }));
        weakMapPrototype.set("delete", nativeFn("delete", 1, (t, a, c) -> {
            @SuppressWarnings("unchecked")
            java.util.IdentityHashMap<Object, Object> m =
                (java.util.IdentityHashMap<Object, Object>) ((JSObject) t).properties().get(SLOT_WEAK_MAP_DATA);
            return m.remove(arg(a, 0)) != null;
        }));
    }

    private static void installWeakSetPrototype() {
        weakSetPrototype.set("add", nativeFn("add", 1, (t, a, c) -> {
            @SuppressWarnings("unchecked")
            java.util.Set<Object> s =
                (java.util.Set<Object>) ((JSObject) t).properties().get(SLOT_WEAK_SET_DATA);
            s.add(arg(a, 0));
            return t;
        }));
        weakSetPrototype.set("has", nativeFn("has", 1, (t, a, c) -> {
            @SuppressWarnings("unchecked")
            java.util.Set<Object> s =
                (java.util.Set<Object>) ((JSObject) t).properties().get(SLOT_WEAK_SET_DATA);
            return s.contains(arg(a, 0));
        }));
        weakSetPrototype.set("delete", nativeFn("delete", 1, (t, a, c) -> {
            @SuppressWarnings("unchecked")
            java.util.Set<Object> s =
                (java.util.Set<Object>) ((JSObject) t).properties().get(SLOT_WEAK_SET_DATA);
            return s.remove(arg(a, 0));
        }));
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

    private static void installPromisePrototype() {
        // § 27.2.5.4 Promise.prototype.then.
        promisePrototype.set("then", nativeFn("then", 2, (thisVal, args, ctx) -> {
            if (!isPromise(thisVal)) {
                throw AbruptCompletion.typeError("Promise.prototype.then called on non-Promise");
            }
            JSObject p = (JSObject) thisVal;
            JSObject result = createPromise();
            Object onFulfilled = arg(args, 0);
            Object onRejected = arg(args, 1);
            // § 27.2.5.4.1 PerformPromiseThen — build reactions.
            Runnable fulfillReaction = () -> {
                Object res = p.properties().get(PROM_RESULT);
                if (onFulfilled instanceof JSFunction f) {
                    try {
                        Object v = Interpreter.invokeFunction(f, Undefined.VALUE, new Object[]{res}, ctx);
                        resolvePromise(result, v, ctx);
                    } catch (AbruptCompletion ac) {
                        rejectPromise(result, ac.value());
                    }
                } else {
                    fulfillPromise(result, res);
                }
            };
            Runnable rejectReaction = () -> {
                Object res = p.properties().get(PROM_RESULT);
                if (onRejected instanceof JSFunction f) {
                    try {
                        Object v = Interpreter.invokeFunction(f, Undefined.VALUE, new Object[]{res}, ctx);
                        resolvePromise(result, v, ctx);
                    } catch (AbruptCompletion ac) {
                        rejectPromise(result, ac.value());
                    }
                } else {
                    rejectPromise(result, res);
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
                fulfillReaction.run();
            } else {
                rejectReaction.run();
            }
            return result;
        }));
        // § 27.2.5.1 Promise.prototype.catch ( onRejected ) → this.then(undefined, onRejected).
        promisePrototype.set("catch", nativeFn("catch", 1, (thisVal, args, ctx) -> {
            JSFunction thenFn = (JSFunction) AbstractOps.getProperty(thisVal, "then");
            return Interpreter.invokeFunction(thenFn, thisVal,
                new Object[]{Undefined.VALUE, arg(args, 0)}, ctx);
        }));
        // § 27.2.5.3 Promise.prototype.finally — runs callback regardless,
        // passes through the value/reason.
        promisePrototype.set("finally", nativeFn("finally", 1, (thisVal, args, ctx) -> {
            Object onFinally = arg(args, 0);
            JSFunction thenFn = (JSFunction) AbstractOps.getProperty(thisVal, "then");
            Object onF = onFinally instanceof JSFunction
                ? new JSFunction("onFulfilledFinally", 1, (tt, aa, cc) -> {
                    Interpreter.invokeFunction((JSFunction) onFinally, Undefined.VALUE, new Object[0], cc);
                    return arg(aa, 0);
                })
                : onFinally;
            Object onR = onFinally instanceof JSFunction
                ? new JSFunction("onRejectedFinally", 1, (tt, aa, cc) -> {
                    Interpreter.invokeFunction((JSFunction) onFinally, Undefined.VALUE, new Object[0], cc);
                    throw new AbruptCompletion(arg(aa, 0));
                })
                : onFinally;
            return Interpreter.invokeFunction(thenFn, thisVal, new Object[]{onF, onR}, ctx);
        }));
    }

    private static void installSymbolPrototype() {
        // § 20.4.3.4 Symbol.prototype.toString.
        symbolPrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            if (t instanceof JSSymbol s) return s.toString();
            throw AbruptCompletion.typeError("Symbol.prototype.toString called on non-Symbol");
        }));
        // § 20.4.3.5 Symbol.prototype.valueOf — returns the symbol itself.
        symbolPrototype.set("valueOf", nativeFn("valueOf", 0, (t, a, c) -> {
            if (t instanceof JSSymbol s) return s;
            throw AbruptCompletion.typeError("Symbol.prototype.valueOf called on non-Symbol");
        }));
        // § 20.4.3.2 get Symbol.prototype.description (accessor).
        // v1: a plain data property since we don't have accessor wiring on
        // primitives. Tests reading sym.description hit AbstractOps.getProperty
        // which walks the symbolPrototype — close enough.
        symbolPrototype.set("description", Undefined.VALUE);
        // § 20.4.3.6 Symbol.prototype [ %Symbol.toPrimitive% ].
        symbolPrototype.set(wellKnownToPrimitive.asPropertyKey(),
            nativeFn("[Symbol.toPrimitive]", 1, (t, a, c) -> {
                if (t instanceof JSSymbol s) return s;
                throw AbruptCompletion.typeError("Symbol.prototype[@@toPrimitive] called on non-Symbol");
            }));
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
            // ECMAScript: round half toward +Infinity (NOT Java HALF_UP for negatives).
            double d = AbstractOps.toNumber(arg(a, 0));
            if (Double.isNaN(d) || Double.isInfinite(d)) return d;
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
        globals.putIfAbsent("Math", math);

        // JSON namespace
        JSObject json = new JSObject();
        json.set("stringify", nativeFn("stringify", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v == Undefined.VALUE || v instanceof JSFunction) return Undefined.VALUE;
            return jsonStringify(v);
        }));
        json.set("parse", nativeFn("parse", 1, (t, a, c) -> jsonParse(AbstractOps.toString(arg(a, 0)))));
        globals.putIfAbsent("JSON", json);

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
        symbolCtor.properties().put("unscopables", wellKnownUnscopables);
        // § 20.4.2.2 Symbol.for / § 20.4.2.6 Symbol.keyFor — Global Symbol Registry.
        // v1: process-global, single Realm.
        java.util.Map<String, JSSymbol> registry = new java.util.HashMap<>();
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
            JSFunction rejectCb = new JSFunction("reject", 1, (tt, aa, cc) -> {
                rejectPromise(promiseObj, arg(aa, 0)); return Undefined.VALUE;
            });
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

        // § 27.2.4.7 Promise.resolve.
        promiseCtor.properties().put("resolve", nativeFn("resolve", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (isPromise(v)) return v;
            JSObject p = createPromise();
            resolvePromise(p, v, c);
            return p;
        }));
        // § 27.2.4.6 Promise.reject.
        promiseCtor.properties().put("reject", nativeFn("reject", 1, (t, a, c) -> {
            JSObject p = createPromise();
            rejectPromise(p, arg(a, 0));
            return p;
        }));
        // § 27.2.4.1 Promise.all — sync version: iterates immediately.
        promiseCtor.properties().put("all", nativeFn("all", 1, (t, a, c) -> {
            Object iter = arg(a, 0);
            if (!(iter instanceof JSArray arr)) {
                JSObject rejected = createPromise();
                rejectPromise(rejected, AbruptCompletion.typeError("Promise.all requires an array").value());
                return rejected;
            }
            JSObject result = createPromise();
            JSArray values = new JSArray();
            int[] remaining = {arr.length()};
            if (remaining[0] == 0) {
                fulfillPromise(result, values);
                return result;
            }
            for (int i = 0; i < arr.length(); i++) values.push(Undefined.VALUE);
            for (int i = 0; i < arr.length(); i++) {
                final int idx = i;
                Object item = arr.get(i);
                JSObject pi = isPromise(item) ? (JSObject) item : (JSObject) (
                    Interpreter.invokeFunction((JSFunction) promiseCtor.properties().get("resolve"),
                        promiseCtor, new Object[]{item}, c));
                JSFunction thenFn = (JSFunction) AbstractOps.getProperty(pi, "then");
                JSFunction onFulfill = new JSFunction("onFulfill", 1, (tt, aa, cc) -> {
                    values.set(idx, arg(aa, 0));
                    if (--remaining[0] == 0) fulfillPromise(result, values);
                    return Undefined.VALUE;
                });
                JSFunction onReject = new JSFunction("onReject", 1, (tt, aa, cc) -> {
                    rejectPromise(result, arg(aa, 0)); return Undefined.VALUE;
                });
                Interpreter.invokeFunction(thenFn, pi, new Object[]{onFulfill, onReject}, c);
            }
            return result;
        }));
        // § 27.2.4.5 Promise.race.
        promiseCtor.properties().put("race", nativeFn("race", 1, (t, a, c) -> {
            Object iter = arg(a, 0);
            if (!(iter instanceof JSArray arr)) {
                JSObject rejected = createPromise();
                rejectPromise(rejected, AbruptCompletion.typeError("Promise.race requires an array").value());
                return rejected;
            }
            JSObject result = createPromise();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                JSObject pi = isPromise(item) ? (JSObject) item : (JSObject) (
                    Interpreter.invokeFunction((JSFunction) promiseCtor.properties().get("resolve"),
                        promiseCtor, new Object[]{item}, c));
                JSFunction thenFn = (JSFunction) AbstractOps.getProperty(pi, "then");
                JSFunction onFulfill = new JSFunction("onFulfill", 1, (tt, aa, cc) -> {
                    fulfillPromise(result, arg(aa, 0)); return Undefined.VALUE;
                });
                JSFunction onReject = new JSFunction("onReject", 1, (tt, aa, cc) -> {
                    rejectPromise(result, arg(aa, 0)); return Undefined.VALUE;
                });
                Interpreter.invokeFunction(thenFn, pi, new Object[]{onFulfill, onReject}, c);
            }
            return result;
        }));
        globals.putIfAbsent("Promise", promiseCtor);

        // ECMA-262 § 24.1.1.1 Map ( [ iterable ] ).
        JSFunction mapCtor = nativeFn("Map", 0, (t, a, c) -> {
            if (!Interpreter.isNewCall()) {
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
        globals.putIfAbsent("Map", mapCtor);

        // § 24.2.1.1 Set ( [ iterable ] ).
        JSFunction setCtor = nativeFn("Set", 0, (t, a, c) -> {
            if (!Interpreter.isNewCall()) {
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
        globals.putIfAbsent("Set", setCtor);

        // § 24.3.1.1 WeakMap.
        JSFunction weakMapCtor = nativeFn("WeakMap", 0, (t, a, c) -> {
            if (!Interpreter.isNewCall()) {
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
            if (!Interpreter.isNewCall()) {
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
        JSFunction bigIntCtor = nativeFn("BigInt", 1, (t, a, c) -> {
            throw AbruptCompletion.typeError("BigInt is not implemented in v1");
        });
        globals.putIfAbsent("BigInt", bigIntCtor);

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
        t262.set("detachArrayBuffer", nativeFn("detachArrayBuffer", 1,
            (t, a, c) -> Undefined.VALUE));
        t262.set("agent", new JSObject());   // stub, no real agent
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
        stringCtor.properties().put("fromCharCode", nativeFn("fromCharCode", 1, (t, a, c) -> {
            StringBuilder sb = new StringBuilder();
            for (Object x : a) sb.append((char) AbstractOps.toInt32(x));
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
        dateCtor.properties().put("now", nativeFn("now", 0,
            (t, a, c) -> (double) System.currentTimeMillis()));
        dateCtor.properties().put("UTC", nativeFn("UTC", 7, (t, a, c) -> {
            int year = (int) AbstractOps.toNumber(arg(a, 0));
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
            return (double) cal.getTimeInMillis();
        }));
        // Internal helper for prototype methods: extract the [[DateValue]] slot.
        java.util.function.Function<Object, Double> getTimeOf = self -> {
            if (self instanceof JSObject jo) {
                Object v = jo.properties().get("##time##");
                if (v instanceof Double d) return d;
                if (v instanceof Number n) return n.doubleValue();
            }
            return Double.NaN;
        };
        java.util.function.Function<Object, java.util.Calendar> calOf = self -> {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(getTimeOf.apply(self).longValue());
            return cal;
        };
        datePrototype.set("getTime", nativeFn("getTime", 0, (t, a, c) -> getTimeOf.apply(t)));
        datePrototype.set("valueOf", nativeFn("valueOf", 0, (t, a, c) -> getTimeOf.apply(t)));
        datePrototype.set("getFullYear", nativeFn("getFullYear", 0, (t, a, c) ->
            (double) calOf.apply(t).get(java.util.Calendar.YEAR)));
        datePrototype.set("getYear", nativeFn("getYear", 0, (t, a, c) ->
            (double) (calOf.apply(t).get(java.util.Calendar.YEAR) - 1900)));
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
            if (t instanceof JSObject jo) jo.set("##time##", v);
            return v;
        }));
        datePrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            // Match V8/Spider: "Tue Apr 26 2026 12:34:56 GMT+0000 (TZ)" style;
            // the benchmarks only need a string, not byte-perfect format.
            java.util.Calendar cal = calOf.apply(t);
            return new java.util.Date(cal.getTimeInMillis()).toString();
        }));
        globals.putIfAbsent("Date", dateCtor);

        // Object constructor
        JSFunction objectCtor = nativeFn("Object", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v == Undefined.VALUE || v == null) return new JSObject();
            return v;
        });
        objectCtor.properties().put("keys", nativeFn("keys", 1, (t, a, c) -> {
            JSArray out = new JSArray();
            Object v = arg(a, 0);
            // § 7.3.22 OrdinaryOwnPropertyKeys: array-indices first in
            // ascending numeric order, then string keys in insertion order.
            // § 20.1.2.17: Object.keys returns only OWN ENUMERABLE keys.
            if (v instanceof JSObject jo) {
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
            if (a.length == 0) {
                throw AbruptCompletion.typeError("Object.assign target is undefined");
            }
            if (!(a[0] instanceof JSObject target)) {
                throw AbruptCompletion.typeError("Object.assign target must be object");
            }
            for (int i = 1; i < a.length; i++) {
                if (a[i] instanceof JSObject src) {
                    for (var e : src.properties().entrySet()) {
                        if (isPrivateName(e.getKey())) continue;   // private keys aren't enumerable
                        if (!src.isEnumerable(e.getKey())) continue;
                        target.set(e.getKey(), e.getValue());
                    }
                }
            }
            return target;
        }));
        objectCtor.properties().put("create", nativeFn("create", 2, (t, a, c) -> {
            Object proto = arg(a, 0);
            if (proto != null && proto != Undefined.VALUE && !(proto instanceof JSObject)) {
                throw AbruptCompletion.typeError("Object.create proto must be Object or null");
            }
            JSObject p = (proto instanceof JSObject jo) ? jo : null;
            return new JSObject(p);
        }));
        objectCtor.properties().put("getPrototypeOf", nativeFn("getPrototypeOf", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) {
                JSObject p = jo.proto();
                return p == null ? null : p;
            }
            return null;
        }));
        // ECMA-262 § 20.1.2.6 .freeze, § 20.1.2.13 .isExtensible,
        // § 20.1.2.16 .preventExtensions, § 20.1.2.17 .isFrozen,
        // § 20.1.2.20 .seal, § 20.1.2.15 .isSealed.
        // v1: no [[Extensible]] tracking, so isExtensible is true for all
        // objects, preventExtensions is a no-op, isFrozen/isSealed return
        // false. Tests that rely on real extensibility semantics will fail.
        objectCtor.properties().put("freeze", nativeFn("freeze", 1, (t, a, c) -> arg(a, 0)));
        objectCtor.properties().put("isExtensible", nativeFn("isExtensible", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            // § 20.1.2.13 step 1: if argument is not an Object, return false.
            if (v instanceof JSObject jo) return jo.isExtensible();
            return v instanceof JSArray || v instanceof JSFunction;
        }));
        objectCtor.properties().put("preventExtensions", nativeFn("preventExtensions", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) jo.preventExtensions();
            return v;
        }));
        objectCtor.properties().put("isFrozen", nativeFn("isFrozen", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            // § 20.1.2.17 step 1: non-Object → return true (per spec).
            return !(v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction);
        }));
        objectCtor.properties().put("seal", nativeFn("seal", 1, (t, a, c) -> arg(a, 0)));
        objectCtor.properties().put("isSealed", nativeFn("isSealed", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            return !(v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction);
        }));
        // ECMA-262 § 20.1.2.4 Object.defineProperty(O, P, Attributes).
        // v1: respects {value, get, set} but ignores {writable, enumerable, configurable}.
        objectCtor.properties().put("defineProperty", nativeFn("defineProperty", 3, (t, a, c) -> {
            Object target = arg(a, 0);
            Object rawKey = arg(a, 1);
            String key = rawKey instanceof String ss ? ss
                       : rawKey instanceof JSSymbol sy ? sy.asPropertyKey()
                       : AbstractOps.toString(rawKey);
            Object desc = arg(a, 2);
            if (!(target instanceof JSObject) && !(target instanceof JSFunction)) {
                throw AbruptCompletion.typeError("Object.defineProperty called on non-object");
            }
            if (!(desc instanceof JSObject descObj)) {
                throw AbruptCompletion.typeError("Property description must be an object");
            }
            // Accessor descriptor wins if get or set is present.
            boolean hasGet = descObj.properties().containsKey("get");
            boolean hasSet = descObj.properties().containsKey("set");
            byte attrs = 0;
            if (AbstractOps.toBoolean(descObj.properties().getOrDefault("writable", true))) attrs |= JSObject.ATTR_WRITABLE;
            if (AbstractOps.toBoolean(descObj.properties().getOrDefault("enumerable", false))) attrs |= JSObject.ATTR_ENUMERABLE;
            if (AbstractOps.toBoolean(descObj.properties().getOrDefault("configurable", false))) attrs |= JSObject.ATTR_CONFIGURABLE;
            if (target instanceof JSObject targetObj) {
                if (hasGet || hasSet) {
                    JSFunction getter = (descObj.get("get") instanceof JSFunction g) ? g : null;
                    JSFunction setter = (descObj.get("set") instanceof JSFunction s) ? s : null;
                    Accessor acc = new Accessor(getter, setter);
                    Object existing = targetObj.get(key);
                    if (existing instanceof Accessor prior) acc = prior.merge(acc);
                    targetObj.set(key, acc);
                } else {
                    targetObj.set(key, descObj.get("value"));
                }
                targetObj.setAttributes(key, attrs);
            } else {
                JSFunction targetFn = (JSFunction) target;
                if (hasGet || hasSet) {
                    JSFunction getter = (descObj.get("get") instanceof JSFunction g) ? g : null;
                    JSFunction setter = (descObj.get("set") instanceof JSFunction s) ? s : null;
                    Accessor acc = new Accessor(getter, setter);
                    Object existing = targetFn.properties().get(key);
                    if (existing instanceof Accessor prior) acc = prior.merge(acc);
                    targetFn.properties().put(key, acc);
                } else {
                    targetFn.properties().put(key, descObj.get("value"));
                }
                targetFn.setAttributes(key, attrs);
            }
            return target;
        }));
        // ECMA-262 § 20.1.2.5 Object.defineProperties(O, Properties).
        objectCtor.properties().put("defineProperties", nativeFn("defineProperties", 2, (t, a, c) -> {
            Object target = arg(a, 0);
            Object props = arg(a, 1);
            if (!(target instanceof JSObject) && !(target instanceof JSFunction)) {
                throw AbruptCompletion.typeError("Object.defineProperties called on non-object");
            }
            if (!(props instanceof JSObject propsObj)) {
                throw AbruptCompletion.typeError("Property descriptors must be an object");
            }
            JSFunction defineFn = (JSFunction) objectCtor.properties().get("defineProperty");
            for (var e : propsObj.properties().entrySet()) {
                Interpreter.invokeFunction(defineFn, t,
                    new Object[]{target, e.getKey(), e.getValue()}, c);
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
                    desc.set("value", (double) arr.length());
                    desc.set("writable", true);
                    desc.set("enumerable", false);
                    desc.set("configurable", false);
                    return desc;
                }
                int idx = parseIndex(key);
                if (idx >= 0 && idx < arr.length()) {
                    desc.set("value", arr.get(idx));
                    desc.set("writable", true);
                    desc.set("enumerable", true);
                    desc.set("configurable", true);
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
                if ("prototype".equals(key) && fn.prototypeObject() != null) {
                    desc.set("value", fn.prototypeObject());
                    desc.set("writable", true);
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
            if (v instanceof JSObject jo) {
                appendOrderedOwnPropertyNames(jo.properties().keySet(), out);
            } else if (v instanceof JSFunction fn) {
                appendOrderedOwnPropertyNames(fn.properties().keySet(), out);
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
                jo.setProto(proto instanceof JSObject p ? p : null);
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
                int len = (int) n.doubleValue();
                for (int i = 0; i < len; i++) arr.push(Undefined.VALUE);
            } else {
                for (Object e : a) arr.push(e);
            }
            return arr;
        });
        arrayCtor.properties().put("isArray", nativeFn("isArray", 1,
            (t, a, c) -> arg(a, 0) instanceof JSArray));
        arrayCtor.properties().put("of", nativeFn("of", 0, (t, a, c) -> {
            JSArray arr = new JSArray();
            for (Object e : a) arr.push(e);
            return arr;
        }));
        arrayCtor.properties().put("from", nativeFn("from", 1, (t, a, c) -> {
            Object src = arg(a, 0);
            JSArray out = new JSArray();
            JSFunction mapper = arg(a, 1) instanceof JSFunction f ? f : null;
            if (src instanceof JSArray arr) {
                for (int i = 0; i < arr.length(); i++) {
                    Object v = arr.get(i);
                    if (mapper != null) {
                        v = Interpreter.invokeFunction(mapper, Undefined.VALUE,
                            new Object[]{v, (double) i}, c);
                    }
                    out.push(v);
                }
            } else if (src instanceof String s) {
                for (int i = 0; i < s.length(); i++) {
                    Object v = String.valueOf(s.charAt(i));
                    if (mapper != null) {
                        v = Interpreter.invokeFunction(mapper, Undefined.VALUE,
                            new Object[]{v, (double) i}, c);
                    }
                    out.push(v);
                }
            }
            return out;
        }));
        // § 23.1.2.4 Array.prototype reachable from constructor.
        arrayCtor.setPrototypeObject(arrayPrototype);
        arrayPrototype.set("constructor", arrayCtor);
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
                Object result = Interpreter.interpret(exe, new Object[0], 64);
                if (result instanceof JSFunction) return result;
                throw AbruptCompletion.syntaxError("Function constructor parse failed");
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
            regExpPrototype.set("exec", nativeFn("exec", 1, (t, a, c) -> {
                String src = asRegExpSource(t);
                if (src == null) return null;
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
            }));
            regExpPrototype.set("test", nativeFn("test", 1, (t, a, c) -> {
                String src = asRegExpSource(t);
                if (src == null) return false;
                String flags = asRegExpFlags(t);
                String input = AbstractOps.toString(arg(a, 0));
                return compileJsRegex(src, flags).matcher(input).find();
            }));
            regExpPrototype.set("toString", nativeFn("toString", 0, (t, a, c) -> {
                String src = asRegExpSource(t);
                if (src == null) src = "";
                String flags = asRegExpFlags(t);
                return "/" + src + "/" + flags;
            }));
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
                re.set("source", arg(a, 0) == Undefined.VALUE ? "" : AbstractOps.toString(a[0]));
                re.set("flags", arg(a, 1) == Undefined.VALUE ? "" : AbstractOps.toString(a[1]));
                re.set("lastIndex", 0.0);   // ECMA-262 § 22.2.4.1 step 3
                return re;
            });
            regExpCtor.setPrototypeObject(regExpProto);
            regExpProto.set("constructor", regExpCtor);
        }
        globals.putIfAbsent("RegExp", regExpCtor);

        // ECMA-262 § 28 Reflect — namespace of meta-operations. v1 covers
        // the static-method shape; full semantics (especially Receiver-aware
        // get/set with Proxy) are deferred.
        JSObject reflect = new JSObject(objectPrototype);
        reflect.set("has", nativeFn("has", 2, (t, a, c) -> {
            Object target = arg(a, 0);
            Object key = arg(a, 1);
            String k = key instanceof String s ? s
                : key instanceof JSSymbol sy ? sy.asPropertyKey()
                : AbstractOps.toString(key);
            // ECMA-262 § 9.1.7 [[HasProperty]] walks the proto chain
            // but private names are stored in [[PrivateFieldDescriptors]]
            // and don't participate in HasProperty / [[Get]] / the `in`
            // operator. Treat private names as absent here so
            // Reflect.has, like Object.prototype.hasOwnProperty, returns
            // false. Code that legitimately uses `#x in obj` is lowered
            // through a dedicated private-name op, not through Reflect.
            if (isPrivateName(k)) return false;
            if (target instanceof JSObject jo) return jo.has(k);
            return false;
        }));
        reflect.set("get", nativeFn("get", 3, (t, a, c) -> AbstractOps.getProperty(arg(a, 0), arg(a, 1))));
        reflect.set("set", nativeFn("set", 4, (t, a, c) -> {
            AbstractOps.setProperty(arg(a, 0), arg(a, 1), arg(a, 2));
            return true;
        }));
        reflect.set("deleteProperty", nativeFn("deleteProperty", 2, (t, a, c) -> {
            Object target = arg(a, 0);
            Object key = arg(a, 1);
            String k = key instanceof String s ? s
                : key instanceof JSSymbol sy ? sy.asPropertyKey()
                : AbstractOps.toString(key);
            if (target instanceof JSObject jo) {
                // Spec § 28.1.16 Reflect.deleteProperty calls [[Delete]]
                // which returns false for non-configurable own props.
                Object r = jo.delete(k);
                return r == Boolean.TRUE;
            }
            return false;
        }));
        reflect.set("ownKeys", nativeFn("ownKeys", 1, (t, a, c) -> {
            Object target = arg(a, 0);
            JSArray result = new JSArray();
            if (target instanceof JSObject jo) {
                for (String k : jo.properties().keySet()) {
                    if (k.startsWith("#")) continue;
                    result.push(k);
                }
            }
            return result;
        }));
        reflect.set("getPrototypeOf", nativeFn("getPrototypeOf", 1, (t, a, c) -> {
            Object target = arg(a, 0);
            if (target instanceof JSObject jo) return jo.proto() != null ? jo.proto() : Undefined.VALUE;
            return Undefined.VALUE;
        }));
        reflect.set("setPrototypeOf", nativeFn("setPrototypeOf", 2, (t, a, c) -> {
            Object target = arg(a, 0);
            Object newProto = arg(a, 1);
            if (target instanceof JSObject jo) {
                jo.setProto(newProto instanceof JSObject p ? p : null);
                return true;
            }
            return false;
        }));
        reflect.set("isExtensible", nativeFn("isExtensible", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) return jo.isExtensible();
            return true;
        }));
        reflect.set("preventExtensions", nativeFn("preventExtensions", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) jo.preventExtensions();
            return true;
        }));
        reflect.set("apply", nativeFn("apply", 3, (t, a, c) -> {
            if (!(arg(a, 0) instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("Reflect.apply called on non-function");
            }
            Object thisArg = arg(a, 1);
            Object argList = arg(a, 2);
            Object[] callArgs = (argList instanceof JSArray arr) ? arr.elements().toArray() : new Object[0];
            return Interpreter.invokeFunction(fn, thisArg, callArgs, c);
        }));
        reflect.set("construct", nativeFn("construct", 2, (t, a, c) -> {
            if (!(arg(a, 0) instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("Reflect.construct called on non-constructor");
            }
            Object argList = arg(a, 1);
            Object[] callArgs = (argList instanceof JSArray arr) ? arr.elements().toArray() : new Object[0];
            JSObject receiver = new JSObject(fn.prototypeObject());
            Object result = Interpreter.invokeFunctionAsConstructor(fn, receiver, callArgs, c);
            return (result instanceof JSObject || result instanceof JSArray || result instanceof JSFunction)
                ? result : receiver;
        }));
        globals.putIfAbsent("Reflect", reflect);

        // Mirror everything currently installed onto globalThis, so e.g.
        // `globalThis.Math.PI`, `globalThis.NaN`, `globalThis.parseInt(...)` work.
        Object globalThisVal = globals.get("globalThis");
        if (globalThisVal instanceof JSObject globalThisObj) {
            for (var e : globals.entrySet()) {
                if ("globalThis".equals(e.getKey())) continue;
                if (!globalThisObj.properties().containsKey(e.getKey())) {
                    globalThisObj.set(e.getKey(), e.getValue());
                }
            }
            globalThisObj.set("globalThis", globalThisObj);   // self-reference
        }
    }

    /**
     * Build an Error-typed object with proper prototype wiring so
     * {@code thrown.constructor === SyntaxError} (etc.) holds. Falls back to a
     * bare object if the prototype hasn't been initialized yet (bootstrap).
     */
    public static JSObject makeError(String typeName, String message) {
        JSObject proto = errorPrototypes.get(typeName);
        JSObject err = proto != null ? new JSObject(proto) : new JSObject();
        err.set("name", typeName);
        err.set("message", message != null ? message : "");
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
        if (flags.contains("u")) f |= java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;
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
        StringBuilder sb = new StringBuilder();
        appendJson(sb, v);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object v) {
        if (v == null || v == Undefined.VALUE) { sb.append("null"); return; }
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
            sb.append(AbstractOps.toString(d));
            return;
        }
        if (v instanceof String s) { appendJsonString(sb, s); return; }
        if (v instanceof JSArray arr) {
            sb.append('[');
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(',');
                Object e = arr.get(i);
                if (e instanceof JSFunction || e == Undefined.VALUE) sb.append("null");
                else appendJson(sb, e);
            }
            sb.append(']');
            return;
        }
        if (v instanceof JSObject jo) {
            sb.append('{');
            boolean first = true;
            for (var e : jo.properties().entrySet()) {
                if (isPrivateName(e.getKey())) continue;
                if (!jo.isEnumerable(e.getKey())) continue;
                Object val = e.getValue();
                if (val instanceof JSFunction || val == Undefined.VALUE) continue;
                if (!first) sb.append(',');
                first = false;
                appendJsonString(sb, e.getKey());
                sb.append(':');
                appendJson(sb, val);
            }
            sb.append('}');
            return;
        }
        sb.append("null");
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
