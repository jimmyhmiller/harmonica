package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSArray;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.JSSymbol;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 24.3 — WeakMap.prototype methods. Mirrors LibJS's
 * WeakMapPrototype.cpp. Every method:
 *   1. brand-checks the receiver via the {@code ##WeakMapData##} slot, and
 *   2. validates that the key is one of the spec's "CanBeHeldWeakly"
 *      values (any Object, or a non-registered Symbol per the
 *      symbols-as-weakmap-keys proposal).
 */
public final class WeakMapPrototypeBuiltin {
    private WeakMapPrototypeBuiltin() {}

    /** Same slot name as the rest of Realm — kept in sync. */
    public static final String SLOT_DATA = "##WeakMapData##";

    public static void install() {
        Realm.weakMapPrototype.set("get", Realm.nativeFn("get", 1, (t, a, c) -> {
            var m = dataOf(t, "get");
            Object k = Realm.arg(a, 0);
            if (!canBeHeldWeakly(k)) return Undefined.VALUE;
            Object v = m.get(k);
            return v == null ? Undefined.VALUE : v;
        }));
        Realm.weakMapPrototype.set("set", Realm.nativeFn("set", 2, (t, a, c) -> {
            var m = dataOf(t, "set");
            Object k = Realm.arg(a, 0);
            requireWeakKey(k, "set");
            m.put(k, Realm.arg(a, 1));
            return t;
        }));
        Realm.weakMapPrototype.set("has", Realm.nativeFn("has", 1, (t, a, c) -> {
            var m = dataOf(t, "has");
            Object k = Realm.arg(a, 0);
            if (!canBeHeldWeakly(k)) return false;
            return m.containsKey(k);
        }));
        Realm.weakMapPrototype.set("delete", Realm.nativeFn("delete", 1, (t, a, c) -> {
            var m = dataOf(t, "delete");
            Object k = Realm.arg(a, 0);
            if (!canBeHeldWeakly(k)) return false;
            return m.remove(k) != null;
        }));
        // ES2025 upsert proposal: getOrInsert / getOrInsertComputed.
        Realm.weakMapPrototype.set("getOrInsert", Realm.nativeFn("getOrInsert", 2, (t, a, c) -> {
            var m = dataOf(t, "getOrInsert");
            Object k = Realm.arg(a, 0);
            requireWeakKey(k, "getOrInsert");
            Object v = Realm.arg(a, 1);
            if (m.containsKey(k)) return m.get(k);
            m.put(k, v);
            return v;
        }));
        Realm.weakMapPrototype.set("getOrInsertComputed",
            Realm.nativeFn("getOrInsertComputed", 2, (t, a, c) -> {
                var m = dataOf(t, "getOrInsertComputed");
                Object k = Realm.arg(a, 0);
                requireWeakKey(k, "getOrInsertComputed");
                Object cb = Realm.arg(a, 1);
                if (!(cb instanceof JSFunction cbf)) {
                    throw AbruptCompletion.typeError(
                        "WeakMap.prototype.getOrInsertComputed: callback is not callable");
                }
                if (m.containsKey(k)) return m.get(k);
                Object v = Interpreter.invokeFunction(cbf, Undefined.VALUE, new Object[]{k}, c);
                m.put(k, v);
                return v;
            }));
    }

    /** Brand-check + slot fetch. Always throws TypeError when {@code t} is
     *  not a WeakMap — mirrors LibJS's {@code typed_this_object}. */
    @SuppressWarnings("unchecked")
    private static java.util.IdentityHashMap<Object, Object> dataOf(Object t, String where) {
        if (t instanceof JSObject jo) {
            Object data = jo.properties().get(SLOT_DATA);
            if (data instanceof java.util.IdentityHashMap m) {
                return (java.util.IdentityHashMap<Object, Object>) m;
            }
        }
        throw AbruptCompletion.typeError(
            "WeakMap.prototype." + where + " called on incompatible receiver");
    }

    /** § 6.1.7 CanBeHeldWeakly — object or non-registered symbol. */
    private static boolean canBeHeldWeakly(Object v) {
        if (v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction) return true;
        if (v instanceof JSSymbol s) return !isRegisteredSymbol(s);
        return false;
    }

    private static void requireWeakKey(Object k, String where) {
        if (!canBeHeldWeakly(k)) {
            throw AbruptCompletion.typeError(
                "WeakMap.prototype." + where + ": key must be an object or a non-registered Symbol");
        }
    }

    /** § 20.4.2.2 / 6.1.7 — a symbol is "registered" iff it lives in the
     *  Global Symbol Registry populated by {@code Symbol.for}. Well-known
     *  symbols ({@code Symbol.iterator} etc.) are <em>not</em> in the
     *  registry and remain valid WeakMap keys per the symbols-as-weakmap
     *  proposal. */
    private static boolean isRegisteredSymbol(JSSymbol s) {
        for (JSSymbol registered : Realm.globalSymbolRegistry.values()) {
            if (registered == s) return true;
        }
        return false;
    }
}
