package com.jimmyhmiller.harmonica.bytecode.builtins;

import java.util.Map;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.Accessor;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSArray;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.JSSymbol;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 28.1 — the Reflect built-in. Mirrors LibJS's ReflectObject.cpp.
 * Each method validates that {@code target} is an Object before
 * dispatching, returning TypeError per spec when it isn't.
 */
public final class ReflectBuiltin {
    private ReflectBuiltin() {}

    public static void install(Map<String, Object> globals) {
        JSObject reflect = new JSObject(Realm.objectPrototype);

        reflect.set("apply", method("apply", 3, (a, c) -> {
            Object target = Realm.arg(a, 0);
            if (!(target instanceof JSFunction fn)) {
                throw AbruptCompletion.typeError("Reflect.apply: target is not a function");
            }
            Object thisArg = Realm.arg(a, 1);
            Object argList = Realm.arg(a, 2);
            Object[] callArgs = createListFromArrayLike(argList, "Reflect.apply");
            return Interpreter.invokeFunction(fn, thisArg, callArgs, c);
        }));

        reflect.set("construct", method("construct", 2, (a, c) -> {
            Object target = Realm.arg(a, 0);
            if (!(target instanceof JSFunction fn) || !fn.isConstructor()) {
                throw AbruptCompletion.typeError("Reflect.construct: target is not a constructor");
            }
            Object argList = Realm.arg(a, 1);
            Object[] callArgs = createListFromArrayLike(argList, "Reflect.construct");
            JSFunction newTarget = fn;
            if (a.length >= 3) {
                Object nt = Realm.arg(a, 2);
                if (!(nt instanceof JSFunction ntf) || !ntf.isConstructor()) {
                    throw AbruptCompletion.typeError(
                        "Reflect.construct: newTarget is not a constructor");
                }
                newTarget = ntf;
            }
            JSObject receiver = new JSObject(newTarget.prototypeObject());
            Object result = Interpreter.invokeFunctionAsConstructor(fn, receiver, callArgs, c);
            return (result instanceof JSObject || result instanceof JSArray
                    || result instanceof JSFunction) ? result : receiver;
        }));

        reflect.set("defineProperty", method("defineProperty", 3, (a, c) -> {
            requireObject(Realm.arg(a, 0), "Reflect.defineProperty");
            Object objectCtor = c == null ? null : c.globals().get("Object");
            Object defineFn = objectCtor instanceof JSFunction f
                ? f.getOwnStatic("defineProperty") : null;
            if (!(defineFn instanceof JSFunction df)) return false;
            try {
                Object result = Interpreter.invokeFunction(df, objectCtor, a, c);
                // Object.defineProperty returns the target on success or
                // {@code false} when an integer-indexed exotic refuses the
                // descriptor (see Realm.java's TypedArray fast path).
                if (result == Boolean.FALSE) return false;
                return true;
            } catch (AbruptCompletion ac) {
                return false;
            }
        }));

        reflect.set("deleteProperty", method("deleteProperty", 2, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.deleteProperty");
            String key = toPropertyKey(Realm.arg(a, 1));
            if (target instanceof JSObject jo) {
                // TypedArray: valid integer index → false; invalid → true.
                if (com.jimmyhmiller.harmonica.bytecode.TypedArrays.isTypedArray(jo)) {
                    double canonical = com.jimmyhmiller.harmonica.bytecode.TypedArrays
                        .canonicalNumericIndexString(key);
                    if (!Double.isNaN(canonical)) {
                        var state = com.jimmyhmiller.harmonica.bytecode.TypedArrays.stateOf(jo);
                        long idx = com.jimmyhmiller.harmonica.bytecode.TypedArrays
                            .integerIndexFromNumber(canonical, state == null ? 0 : state.length());
                        return idx < 0;   // out-of-bounds is "deletable"
                    }
                }
                Object r = jo.delete(key);
                return r == Boolean.TRUE;
            }
            return false;
        }));

        reflect.set("get", method("get", 2, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.get");
            toPropertyKey(Realm.arg(a, 1));   // validate (also throws if symbol coercion fails)
            Object key = Realm.arg(a, 1);
            // Receiver-aware get: if the resolved property is an accessor,
            // its getter runs with `this = receiver`. Walk the proto chain
            // ourselves so we can spot Accessor cells before going through
            // AbstractOps.getProperty (which would bind `this` to target).
            Object receiver = a.length >= 3 ? a[2] : target;
            String propKey = key instanceof String s ? s
                           : key instanceof JSSymbol sy ? sy.asPropertyKey()
                           : AbstractOps.toString(key);
            Object cur = target;
            while (cur != null && cur != Undefined.VALUE) {
                Object slot;
                if (cur instanceof JSObject jo && jo.properties().containsKey(propKey)) {
                    slot = jo.properties().get(propKey);
                } else if (cur instanceof JSFunction fn && fn.hasOwnStatic(propKey)) {
                    slot = fn.getOwnStatic(propKey);
                } else { slot = null; }
                if (slot instanceof Accessor acc) {
                    JSFunction getter = acc.getter();
                    if (getter == null) return Undefined.VALUE;
                    return Interpreter.invokeFunction(getter, receiver, new Object[0], c);
                }
                if (slot != null) break;
                if (cur instanceof JSObject jo) cur = jo.proto();
                else if (cur instanceof JSFunction fn) cur = fn.prototypeObject();
                else cur = null;
            }
            return AbstractOps.getProperty(target, key);
        }));

        reflect.set("getOwnPropertyDescriptor", method("getOwnPropertyDescriptor", 2, (a, c) -> {
            requireObject(Realm.arg(a, 0), "Reflect.getOwnPropertyDescriptor");
            Object objectCtor = c == null ? null : c.globals().get("Object");
            Object descFn = objectCtor instanceof JSFunction f
                ? f.getOwnStatic("getOwnPropertyDescriptor") : null;
            if (!(descFn instanceof JSFunction df)) return Undefined.VALUE;
            return Interpreter.invokeFunction(df, objectCtor, a, c);
        }));

        reflect.set("getPrototypeOf", method("getPrototypeOf", 1, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.getPrototypeOf");
            if (target instanceof JSObject jo) return jo.proto() != null ? jo.proto() : null;
            if (target instanceof JSFunction fn) {
                JSFunction sc = fn.superConstructor();
                return sc != null ? sc : Realm.functionPrototype;
            }
            if (target instanceof JSArray) return Realm.arrayPrototype;
            return null;
        }));

        reflect.set("has", method("has", 2, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.has");
            String key = toPropertyKey(Realm.arg(a, 1));
            if (key.startsWith("#")) return false;   // private names invisible to Reflect
            if (target instanceof JSObject jo) {
                // TypedArray: canonical numeric index → in-bounds-or-not.
                if (com.jimmyhmiller.harmonica.bytecode.TypedArrays.isTypedArray(jo)) {
                    double canonical = com.jimmyhmiller.harmonica.bytecode.TypedArrays
                        .canonicalNumericIndexString(key);
                    if (!Double.isNaN(canonical)) {
                        var state = com.jimmyhmiller.harmonica.bytecode.TypedArrays.stateOf(jo);
                        long idx = com.jimmyhmiller.harmonica.bytecode.TypedArrays
                            .integerIndexFromNumber(canonical, state == null ? 0 : state.length());
                        return idx >= 0;
                    }
                }
                return jo.has(key);
            }
            return false;
        }));

        reflect.set("isExtensible", method("isExtensible", 1, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.isExtensible");
            if (target instanceof JSObject jo) return jo.isExtensible();
            return true;
        }));

        reflect.set("ownKeys", method("ownKeys", 1, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.ownKeys");
            JSArray result = new JSArray();
            if (target instanceof JSObject jo) {
                for (String k : jo.properties().keySet()) {
                    if (k.startsWith("#")) continue;          // private names
                    if (k.startsWith("##") && k.endsWith("##")) continue;  // internal slots
                    if (k.startsWith("@@symbol#")) continue;   // Symbol keys — see TODO below
                    result.push(k);
                }
                // TODO: also append the symbol-keyed properties. Recovering
                // the JSSymbol from its @@symbol# id requires a registry we
                // don't maintain yet — see Object.getOwnPropertySymbols.
            }
            return result;
        }));

        reflect.set("preventExtensions", method("preventExtensions", 1, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.preventExtensions");
            if (target instanceof JSObject jo) {
                jo.preventExtensions();
                return true;
            }
            return true;
        }));

        reflect.set("set", method("set", 3, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.set");
            Object rawKey = Realm.arg(a, 1);
            String key = toPropertyKey(rawKey);
            // § 10.4.5.5 IntegerIndexedExotic [[Set]]: canonical numeric
            // index strings — whether in-bounds, out-of-bounds, fractional,
            // negative, or NaN — always return true (silent success). The
            // ordinary [[Set]] path is taken only for non-canonical keys.
            if (target instanceof JSObject jo
                    && com.jimmyhmiller.harmonica.bytecode.TypedArrays.isTypedArray(jo)) {
                double canonical = com.jimmyhmiller.harmonica.bytecode.TypedArrays
                    .canonicalNumericIndexString(key);
                if (!Double.isNaN(canonical)) {
                    try {
                        AbstractOps.setProperty(target, rawKey, Realm.arg(a, 2));
                    } catch (AbruptCompletion ignored) {
                        // BigInt vs Number mismatch throws via storeElement —
                        // surface to caller (matches V8).
                        throw ignored;
                    }
                    return true;
                }
            }
            try {
                AbstractOps.setProperty(target, rawKey, Realm.arg(a, 2));
                return true;
            } catch (AbruptCompletion ac) {
                return false;
            }
        }));

        reflect.set("setPrototypeOf", method("setPrototypeOf", 2, (a, c) -> {
            Object target = requireObject(Realm.arg(a, 0), "Reflect.setPrototypeOf");
            // § 28.1.13 step 2: only null and Object are accepted — undefined,
            // primitives, etc. all throw TypeError.
            Object proto = a.length >= 2 ? a[1] : Undefined.VALUE;
            if (proto != null && !(proto instanceof JSObject)) {
                throw AbruptCompletion.typeError(
                    "Reflect.setPrototypeOf: proto must be Object or null");
            }
            if (target instanceof JSObject jo) {
                JSObject newProto = proto instanceof JSObject p ? p : null;
                // § 9.1.2.1 OrdinarySetPrototypeOf returns false if:
                //  - proto equals current proto (no, that returns true)
                //  - target is non-extensible
                //  - proto chain would form a cycle
                if (!jo.isExtensible() && jo.proto() != newProto) return false;
                // Cycle check: walk newProto's chain looking for jo.
                JSObject cursor = newProto;
                while (cursor != null) {
                    if (cursor == jo) return false;
                    cursor = cursor.proto();
                }
                jo.setProto(newProto);
                return true;
            }
            return false;
        }));

        // ECMA-262 § 28.1.14 — Reflect[@@toStringTag] = "Reflect", configurable only.
        if (Realm.wellKnownToStringTag != null) {
            String key = Realm.wellKnownToStringTag.asPropertyKey();
            reflect.set(key, "Reflect");
            reflect.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        // § 17 default attribute byte for built-in methods.
        for (String k : new String[]{
            "apply", "construct", "defineProperty", "deleteProperty", "get",
            "getOwnPropertyDescriptor", "getPrototypeOf", "has", "isExtensible",
            "ownKeys", "preventExtensions", "set", "setPrototypeOf"
        }) {
            reflect.setAttributes(k,
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        }

        globals.putIfAbsent("Reflect", reflect);
    }

    // ---- helpers ----

    private interface Body {
        Object run(Object[] args, com.jimmyhmiller.harmonica.bytecode.InterpContext ctx);
    }

    private static JSFunction method(String name, int arity, Body body) {
        return Realm.nativeFn(name, arity, (thisVal, a, ctx) -> body.run(a, ctx));
    }

    private static Object requireObject(Object v, String where) {
        if (v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction) return v;
        throw AbruptCompletion.typeError(where + " called on non-object");
    }

    private static String toPropertyKey(Object v) {
        if (v instanceof String s) return s;
        if (v instanceof JSSymbol sy) return sy.asPropertyKey();
        return AbstractOps.toString(v);
    }

    /** § 7.3.18 CreateListFromArrayLike — read .length, then 0..length-1. */
    private static Object[] createListFromArrayLike(Object v, String where) {
        if (v == null || v == Undefined.VALUE) {
            throw AbruptCompletion.typeError(where + ": argumentsList must be an object");
        }
        if (v instanceof JSArray arr) {
            return arr.elements().toArray();
        }
        if (!(v instanceof JSObject jo)) {
            throw AbruptCompletion.typeError(where + ": argumentsList must be an object");
        }
        Object lenVal = AbstractOps.getProperty(jo, "length");
        int len = (int) AbstractOps.toNumber(lenVal);
        if (len < 0) len = 0;
        Object[] out = new Object[len];
        for (int i = 0; i < len; i++) {
            out[i] = AbstractOps.getProperty(jo, Integer.toString(i));
        }
        return out;
    }
}
