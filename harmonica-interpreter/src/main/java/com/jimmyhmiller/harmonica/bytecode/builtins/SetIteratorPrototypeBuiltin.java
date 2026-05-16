package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.JSArray;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 24.2.5 — %SetIteratorPrototype% + CreateSetIterator. Mirrors
 * LibJS's SetIteratorPrototype.cpp. Slots: [[Set]], [[Index]], [[Snapshot]],
 * [[Kind]] where kind is KEY_PLUS_VALUE (entries) or VALUE.
 */
public final class SetIteratorPrototypeBuiltin {
    private SetIteratorPrototypeBuiltin() {}

    public static final int KIND_VALUE = 1;
    public static final int KIND_ENTRY = 2;   // "key+value" (Set entries yield [v, v])

    static final String SLOT_SET      = "##IteratedSet##";
    static final String SLOT_INDEX    = "##SetIteratorIndex##";
    static final String SLOT_SNAPSHOT = "##SetIteratorSnapshot##";
    static final String SLOT_KIND     = "##SetIterationKind##";
    static final String SLOT_DONE     = "##SetIteratorDone##";

    public static void install() {
        JSObject parent = Realm.iteratorPrototype != null
            ? Realm.iteratorPrototype : Realm.objectPrototype;
        JSObject proto = new JSObject(parent);

        proto.set("next", Realm.nativeFn("next", 0, (thisVal, a, ctx) -> {
            if (!(thisVal instanceof JSObject iter)
                    || !iter.properties().containsKey(SLOT_SET)) {
                throw AbruptCompletion.typeError(
                    "Set Iterator.prototype.next called on incompatible receiver");
            }
            @SuppressWarnings("unchecked")
            java.util.LinkedHashSet<Object> data =
                (java.util.LinkedHashSet<Object>) iter.properties().get(SLOT_SET);
            @SuppressWarnings("unchecked")
            java.util.List<Object> snap =
                (java.util.List<Object>) iter.properties().get(SLOT_SNAPSHOT);
            int idx  = ((Number) iter.properties().get(SLOT_INDEX)).intValue();
            int kind = ((Number) iter.properties().get(SLOT_KIND)).intValue();
            boolean done = Boolean.TRUE.equals(iter.properties().get(SLOT_DONE));
            if (done) {
                JSObject doneResult = new JSObject();
                doneResult.set("value", Undefined.VALUE);
                doneResult.set("done", true);
                return doneResult;
            }

            if (data != null && data.size() > snap.size()) {
                for (Object newV : data) if (!snap.contains(newV)) snap.add(newV);
            }

            JSObject result = new JSObject();
            while (data != null && idx < snap.size()) {
                Object v = snap.get(idx++);
                if (!data.contains(v)) continue;
                Object payload;
                if (kind == KIND_ENTRY) {
                    JSArray pair = new JSArray();
                    pair.push(v); pair.push(v);
                    payload = pair;
                } else payload = v;
                iter.set(SLOT_INDEX, (double) idx);
                result.set("value", payload);
                result.set("done", false);
                return result;
            }
            iter.set(SLOT_INDEX, (double) idx);
            iter.set(SLOT_DONE, Boolean.TRUE);
            result.set("value", Undefined.VALUE);
            result.set("done", true);
            return result;
        }));
        proto.setAttributes("next",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        if (Realm.wellKnownToStringTag != null) {
            String key = Realm.wellKnownToStringTag.asPropertyKey();
            proto.set(key, "Set Iterator");
            proto.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        Realm.setIteratorPrototype = proto;
    }

    /** § 24.2.5.1 CreateSetIterator(set, kind). */
    public static JSObject create(java.util.LinkedHashSet<Object> data, int kind) {
        JSObject iter = new JSObject(Realm.setIteratorPrototype);
        iter.set(SLOT_SET, data);
        iter.set(SLOT_INDEX, 0.0);
        iter.set(SLOT_SNAPSHOT, new java.util.ArrayList<>(data));
        iter.set(SLOT_KIND, (double) kind);
        return iter;
    }
}
