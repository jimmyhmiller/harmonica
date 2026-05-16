package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.JSArray;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 24.1.5 — %MapIteratorPrototype% + CreateMapIterator.
 * Mirrors LibJS's MapIteratorPrototype.cpp. Each instance carries:
 *   [[Map]]       — the source ##MapData## map
 *   [[Index]]     — cursor into the snapshot
 *   [[Snapshot]]  — list of keys captured at iterator creation
 *   [[Kind]]      — "key", "value", or "key+value"
 */
public final class MapIteratorPrototypeBuiltin {
    private MapIteratorPrototypeBuiltin() {}

    public static final int KIND_KEY    = 0;
    public static final int KIND_VALUE  = 1;
    public static final int KIND_ENTRY  = 2;

    static final String SLOT_MAP      = "##IteratedMap##";
    static final String SLOT_INDEX    = "##MapIteratorIndex##";
    static final String SLOT_SNAPSHOT = "##MapIteratorSnapshot##";
    static final String SLOT_KIND     = "##MapIterationKind##";
    static final String SLOT_DONE     = "##MapIteratorDone##";

    public static void install() {
        JSObject parent = Realm.iteratorPrototype != null
            ? Realm.iteratorPrototype : Realm.objectPrototype;
        JSObject proto = new JSObject(parent);

        proto.set("next", Realm.nativeFn("next", 0, (thisVal, a, ctx) -> {
            if (!(thisVal instanceof JSObject iter)
                    || !iter.properties().containsKey(SLOT_MAP)) {
                throw AbruptCompletion.typeError(
                    "Map Iterator.prototype.next called on incompatible receiver");
            }
            @SuppressWarnings("unchecked")
            java.util.LinkedHashMap<Object, Object> data =
                (java.util.LinkedHashMap<Object, Object>) iter.properties().get(SLOT_MAP);
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

            // Pick up keys appended after the iterator was created.
            if (data != null && data.size() > snap.size()) {
                for (Object newK : data.keySet()) {
                    if (!snap.contains(newK)) snap.add(newK);
                }
            }

            JSObject result = new JSObject();
            while (data != null && idx < snap.size()) {
                Object k = snap.get(idx++);
                if (!data.containsKey(k)) continue;
                Object payload;
                if (kind == KIND_KEY)        payload = k;
                else if (kind == KIND_VALUE) payload = data.get(k);
                else {
                    JSArray pair = new JSArray();
                    pair.push(k);
                    pair.push(data.get(k));
                    payload = pair;
                }
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
            proto.set(key, "Map Iterator");
            proto.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        Realm.mapIteratorPrototype = proto;
    }

    /** § 24.1.5.1 CreateMapIterator(map, kind). */
    public static JSObject create(java.util.LinkedHashMap<Object, Object> data, int kind) {
        JSObject iter = new JSObject(Realm.mapIteratorPrototype);
        iter.set(SLOT_MAP, data);
        iter.set(SLOT_INDEX, 0.0);
        iter.set(SLOT_SNAPSHOT, new java.util.ArrayList<>(data.keySet()));
        iter.set(SLOT_KIND, (double) kind);
        return iter;
    }
}
