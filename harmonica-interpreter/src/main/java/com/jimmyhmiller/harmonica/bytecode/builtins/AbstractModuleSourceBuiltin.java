package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.Accessor;
import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 28.1 / source-phase-imports proposal:
 * %AbstractModuleSource% — abstract constructor that always throws TypeError
 * when called. The base for concrete ModuleSource subclasses. Has no global
 * binding per spec; the only way to reach it is via the host. Mirrors LibJS
 * layout (would be AbstractModuleSourceConstructor /
 * AbstractModuleSourcePrototype if LibJS implemented it).
 */
public final class AbstractModuleSourceBuiltin {
    private AbstractModuleSourceBuiltin() {}

    /** [[ModuleSourceClassName]] slot — present on concrete subclasses. */
    public static final String SLOT_MODULE_SOURCE_CLASS_NAME = "##ModuleSourceClassName##";

    /** Build the constructor + prototype pair and publish on {@link Realm}. */
    public static JSFunction install() {
        JSObject proto = new JSObject(Realm.objectPrototype);

        JSFunction ctor = Realm.nativeFn("AbstractModuleSource", 0, (thisVal, a, c) -> {
            throw AbruptCompletion.typeError("AbstractModuleSource cannot be constructed directly");
        });
        ctor.setPrototypeObject(proto);
        proto.set("constructor", ctor);
        proto.setAttributes("constructor",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        // § 28.1.4.1 — get %AbstractModuleSource%.prototype[@@toStringTag].
        // Returns the [[ModuleSourceClassName]] slot value or undefined.
        if (Realm.wellKnownToStringTag != null) {
            JSFunction tag = Realm.nativeFn("get [Symbol.toStringTag]", 0, (thisVal, a, c) -> {
                if (thisVal instanceof JSObject jo) {
                    Object name = jo.properties().get(SLOT_MODULE_SOURCE_CLASS_NAME);
                    if (name != null) return name;
                }
                return Undefined.VALUE;
            });
            String key = Realm.wellKnownToStringTag.asPropertyKey();
            proto.set(key, new Accessor(tag, null));
            proto.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        Realm.abstractModuleSourceConstructor = ctor;
        Realm.abstractModuleSourcePrototype = proto;
        return ctor;
    }
}
