package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.JSArray;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.JSSymbol;
import com.jimmyhmiller.harmonica.bytecode.Realm;

/**
 * ECMA-262 § 24.4 — WeakSet.prototype methods. Mirrors LibJS's
 * WeakSetPrototype.cpp. Brand-checks the receiver and enforces the
 * CanBeHeldWeakly key invariant.
 */
public final class WeakSetPrototypeBuiltin {
    private WeakSetPrototypeBuiltin() {}

    public static final String SLOT_DATA = "##WeakSetData##";

    public static void install() {
        Realm.weakSetPrototype.set("add", Realm.nativeFn("add", 1, (t, a, c) -> {
            var s = dataOf(t, "add");
            Object v = Realm.arg(a, 0);
            requireWeakKey(v, "add");
            s.add(v);
            return t;
        }));
        Realm.weakSetPrototype.set("has", Realm.nativeFn("has", 1, (t, a, c) -> {
            var s = dataOf(t, "has");
            Object v = Realm.arg(a, 0);
            if (!canBeHeldWeakly(v)) return false;
            return s.contains(v);
        }));
        Realm.weakSetPrototype.set("delete", Realm.nativeFn("delete", 1, (t, a, c) -> {
            var s = dataOf(t, "delete");
            Object v = Realm.arg(a, 0);
            if (!canBeHeldWeakly(v)) return false;
            return s.remove(v);
        }));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Object> dataOf(Object t, String where) {
        if (t instanceof JSObject jo) {
            Object data = jo.properties().get(SLOT_DATA);
            if (data instanceof java.util.Set s) {
                return (java.util.Set<Object>) s;
            }
        }
        throw AbruptCompletion.typeError(
            "WeakSet.prototype." + where + " called on incompatible receiver");
    }

    private static boolean canBeHeldWeakly(Object v) {
        if (v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction) return true;
        if (v instanceof JSSymbol s) {
            // Well-known symbols are not in the registry; only Symbol.for-registered
            // symbols are excluded by § 6.1.7 CanBeHeldWeakly.
            for (JSSymbol registered : Realm.globalSymbolRegistry.values()) {
                if (registered == s) return false;
            }
            return true;
        }
        return false;
    }

    private static void requireWeakKey(Object k, String where) {
        if (!canBeHeldWeakly(k)) {
            throw AbruptCompletion.typeError(
                "WeakSet.prototype." + where + ": value must be an object or a non-registered Symbol");
        }
    }
}
