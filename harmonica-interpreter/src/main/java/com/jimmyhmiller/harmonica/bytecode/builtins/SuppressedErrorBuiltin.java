package com.jimmyhmiller.harmonica.bytecode.builtins;

import java.util.Map;

import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 / TC39 explicit-resource-management proposal:
 * SuppressedError(error, suppressed, message [, options]) — error subtype
 * thrown by DisposableStack when a primary disposal error is suppressed by
 * a subsequent one. Mirrors LibJS's SuppressedErrorConstructor /
 * SuppressedErrorPrototype split.
 */
public final class SuppressedErrorBuiltin {
    private SuppressedErrorBuiltin() {}

    private static final byte NON_ENUM_WC =
        (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE);

    public static void install(Map<String, Object> globals) {
        JSObject errorProto = Realm.errorPrototypes.get("Error");
        JSObject proto = new JSObject(errorProto);
        proto.set("name", "SuppressedError");
        proto.setAttributes("name", NON_ENUM_WC);
        proto.set("message", "");
        proto.setAttributes("message", NON_ENUM_WC);

        JSFunction ctor = Realm.nativeFn("SuppressedError", 3, (thisVal, a, ctx) -> {
            Object error      = Realm.arg(a, 0);
            Object suppressed = Realm.arg(a, 1);
            Object message    = Realm.arg(a, 2);
            Object options    = Realm.arg(a, 3);

            JSObject err = thisVal instanceof JSObject jo ? jo : new JSObject(proto);

            if (message != Undefined.VALUE) {
                err.set("message", AbstractOps.toString(message));
                err.setAttributes("message", NON_ENUM_WC);
            }

            if (options instanceof JSObject opts && opts.properties().containsKey("cause")) {
                err.set("cause", opts.get("cause"));
                err.setAttributes("cause", NON_ENUM_WC);
            }

            err.set("error", error);
            err.setAttributes("error", NON_ENUM_WC);
            err.set("suppressed", suppressed);
            err.setAttributes("suppressed", NON_ENUM_WC);
            return err;
        });
        ctor.setPrototypeObject(proto);
        proto.set("constructor", ctor);
        proto.setAttributes("constructor", NON_ENUM_WC);

        Realm.errorPrototypes.put("SuppressedError", proto);
        globals.putIfAbsent("SuppressedError", ctor);
    }
}
