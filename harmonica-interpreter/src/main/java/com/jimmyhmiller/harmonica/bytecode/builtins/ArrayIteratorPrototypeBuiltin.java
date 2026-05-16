package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.JSArray;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 23.1.5 — %ArrayIteratorPrototype% + CreateArrayIterator.
 * Mirrors LibJS's ArrayIteratorPrototype.cpp. {@code next} lives on the
 * prototype and dispatches on the iteration kind stored in slots.
 *
 * <p>Per § 23.1.5.1, the iterator reads {@code length} live each step so
 * {@code arr.push(x)} during iteration is observable.
 */
public final class ArrayIteratorPrototypeBuiltin {
    private ArrayIteratorPrototypeBuiltin() {}

    public static final int KIND_KEY    = 0;
    public static final int KIND_VALUE  = 1;
    public static final int KIND_ENTRY  = 2;

    static final String SLOT_TARGET = "##IteratedArrayLike##";
    static final String SLOT_INDEX  = "##ArrayLikeIteratorIndex##";
    static final String SLOT_KIND   = "##ArrayLikeIterationKind##";
    static final String SLOT_DONE   = "##ArrayLikeIteratorDone##";

    public static void install() {
        JSObject parent = Realm.iteratorPrototype != null
            ? Realm.iteratorPrototype : Realm.objectPrototype;
        JSObject proto = new JSObject(parent);

        proto.set("next", Realm.nativeFn("next", 0, (thisVal, a, ctx) -> {
            if (!(thisVal instanceof JSObject iter)
                    || !iter.properties().containsKey(SLOT_TARGET)) {
                throw AbruptCompletion.typeError(
                    "Array Iterator.prototype.next called on incompatible receiver");
            }
            boolean done = Boolean.TRUE.equals(iter.properties().get(SLOT_DONE));
            JSObject result = new JSObject();
            if (done) {
                result.set("value", Undefined.VALUE);
                result.set("done", true);
                return result;
            }

            Object target = iter.properties().get(SLOT_TARGET);
            int idx  = ((Number) iter.properties().get(SLOT_INDEX)).intValue();
            int kind = ((Number) iter.properties().get(SLOT_KIND)).intValue();
            int len = lengthOf(target);

            if (idx >= len) {
                iter.set(SLOT_DONE, Boolean.TRUE);
                result.set("value", Undefined.VALUE);
                result.set("done", true);
                return result;
            }
            Object value = elementAt(target, idx);
            Object payload;
            if (kind == KIND_KEY)        payload = (double) idx;
            else if (kind == KIND_VALUE) payload = value;
            else {
                JSArray pair = new JSArray();
                pair.push((double) idx);
                pair.push(value);
                payload = pair;
            }
            iter.set(SLOT_INDEX, (double) (idx + 1));
            result.set("value", payload);
            result.set("done", false);
            return result;
        }));
        proto.setAttributes("next",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        if (Realm.wellKnownToStringTag != null) {
            String key = Realm.wellKnownToStringTag.asPropertyKey();
            proto.set(key, "Array Iterator");
            proto.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        Realm.arrayIteratorPrototype = proto;
    }

    /** § 23.1.5.1 CreateArrayIterator(O, kind). */
    public static JSObject create(Object target, int kind) {
        JSObject iter = new JSObject(Realm.arrayIteratorPrototype);
        iter.set(SLOT_TARGET, target);
        iter.set(SLOT_INDEX, 0.0);
        iter.set(SLOT_KIND, (double) kind);
        iter.set(SLOT_DONE, Boolean.FALSE);
        return iter;
    }

    public static int kindFor(String name) {
        return switch (name) {
            case "key"       -> KIND_KEY;
            case "key+value" -> KIND_ENTRY;
            default          -> KIND_VALUE;
        };
    }

    private static int lengthOf(Object target) {
        if (target instanceof JSArray a) return a.length();
        if (target instanceof String s)  return s.length();
        Object lenVal = AbstractOps.getProperty(target, "length");
        int len = (int) AbstractOps.toNumber(lenVal);
        return Math.max(0, len);
    }

    private static Object elementAt(Object target, int idx) {
        if (target instanceof JSArray a) return a.get(idx);
        if (target instanceof String s)  return String.valueOf(s.charAt(idx));
        return AbstractOps.getProperty(target, Integer.toString(idx));
    }
}
