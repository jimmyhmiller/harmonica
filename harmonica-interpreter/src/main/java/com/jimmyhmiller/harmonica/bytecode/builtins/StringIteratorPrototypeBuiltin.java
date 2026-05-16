package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 22.1.5 — %StringIteratorPrototype% and the
 * CreateStringIterator abstract operation. Mirrors LibJS's
 * StringIteratorPrototype.cpp. Yields strings of one Unicode code point
 * (surrogate-pair aware), not raw code units.
 */
public final class StringIteratorPrototypeBuiltin {
    private StringIteratorPrototypeBuiltin() {}

    static final String SLOT_STRING = "##IteratedString##";
    static final String SLOT_INDEX  = "##StringIndex##";

    public static void install() {
        JSObject parent = Realm.iteratorPrototype != null
            ? Realm.iteratorPrototype : Realm.objectPrototype;
        JSObject proto = new JSObject(parent);

        proto.set("next", Realm.nativeFn("next", 0, (thisVal, a, ctx) -> {
            if (!(thisVal instanceof JSObject iter)
                    || !iter.properties().containsKey(SLOT_STRING)) {
                throw AbruptCompletion.typeError(
                    "String Iterator.prototype.next called on incompatible receiver");
            }
            String s = (String) iter.properties().get(SLOT_STRING);
            Number nIdx = (Number) iter.properties().get(SLOT_INDEX);
            int idx = nIdx == null ? 0 : nIdx.intValue();

            JSObject result = new JSObject();
            if (idx >= s.length()) {
                result.set("value", Undefined.VALUE);
                result.set("done", true);
                return result;
            }
            int cp = s.codePointAt(idx);
            String value = new String(Character.toChars(cp));
            iter.set(SLOT_INDEX, (double) (idx + Character.charCount(cp)));
            result.set("value", value);
            result.set("done", false);
            return result;
        }));
        proto.setAttributes("next",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        if (Realm.wellKnownToStringTag != null) {
            String key = Realm.wellKnownToStringTag.asPropertyKey();
            proto.set(key, "String Iterator");
            proto.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        Realm.stringIteratorPrototype = proto;
    }

    /** § 22.1.5.1 CreateStringIterator(string). */
    public static JSObject create(String string) {
        JSObject iter = new JSObject(Realm.stringIteratorPrototype);
        iter.set(SLOT_STRING, string);
        iter.set(SLOT_INDEX, 0.0);
        return iter;
    }
}
