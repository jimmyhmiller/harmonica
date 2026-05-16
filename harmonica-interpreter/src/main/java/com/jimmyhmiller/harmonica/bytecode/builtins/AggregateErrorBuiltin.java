package com.jimmyhmiller.harmonica.bytecode.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSArray;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 20.5.7 — AggregateError (errors, message [, options]).
 * Mirrors LibJS's AggregateErrorConstructor / AggregateErrorPrototype.
 * Differs from the other native errors by:
 *  - arity = 2 (errors + message)
 *  - errors is materialized via IterableToList(errors)
 *  - .errors property is non-enumerable with the materialized array
 *  - InstallErrorCause(O, options) handles options.cause
 */
public final class AggregateErrorBuiltin {
    private AggregateErrorBuiltin() {}

    private static final byte NON_ENUM_WC =
        (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE);

    public static void install(Map<String, Object> globals) {
        // Reuse the prototype installError already built when "AggregateError"
        // was registered — keeps prototype chain (Error.prototype) and
        // instanceof checks consistent. We just replace the ctor on globals.
        JSObject proto = Realm.errorPrototypes.get("AggregateError");
        if (proto == null) return;   // bootstrap order — installError ran first

        JSFunction ctor = Realm.nativeFn("AggregateError", 2, (thisVal, a, ctx) -> {
            Object errorsArg = Realm.arg(a, 0);
            Object message   = Realm.arg(a, 1);
            Object options   = Realm.arg(a, 2);

            JSObject err = thisVal instanceof JSObject jo ? jo : new JSObject(proto);

            if (message != Undefined.VALUE) {
                err.set("message", AbstractOps.toString(message));
                err.setAttributes("message", NON_ENUM_WC);
            }

            // § 20.5.8.1 InstallErrorCause.
            if (options instanceof JSObject opts && opts.properties().containsKey("cause")) {
                err.set("cause", opts.get("cause"));
                err.setAttributes("cause", NON_ENUM_WC);
            }

            // § 7.4.2 IterableToList — read @@iterator, iterate, push values.
            List<Object> errorsList = iterableToList(errorsArg, ctx);
            JSArray errorsArr = new JSArray();
            for (Object v : errorsList) errorsArr.push(v);
            err.set("errors", errorsArr);
            err.setAttributes("errors", NON_ENUM_WC);

            return err;
        });
        ctor.setPrototypeObject(proto);
        proto.set("constructor", ctor);
        proto.setAttributes("constructor", NON_ENUM_WC);

        globals.put("AggregateError", ctor);
    }

    /** § 7.4.2 IterableToList. Tests use this to assert error propagation
     *  when the iterator misbehaves — TypeError on missing @@iterator,
     *  callable validation on `next`, etc. */
    private static List<Object> iterableToList(Object iterable,
                                               com.jimmyhmiller.harmonica.bytecode.InterpContext ctx) {
        if (iterable == null || iterable == Undefined.VALUE) {
            throw AbruptCompletion.typeError("AggregateError: errors argument is not iterable");
        }
        Object iterMethod = AbstractOps.getProperty(iterable,
            Realm.wellKnownIterator.asPropertyKey());
        if (!(iterMethod instanceof JSFunction iterFn)) {
            throw AbruptCompletion.typeError("AggregateError: errors is not iterable");
        }
        Object iter = Interpreter.invokeFunction(iterFn, iterable, new Object[0], ctx);
        if (!(iter instanceof JSObject iterObj)) {
            throw AbruptCompletion.typeError("AggregateError: iterator result is not an object");
        }
        Object nextFn = AbstractOps.getProperty(iterObj, "next");
        if (!(nextFn instanceof JSFunction nf)) {
            throw AbruptCompletion.typeError("AggregateError: iterator next is not callable");
        }
        List<Object> out = new ArrayList<>();
        for (int safety = 0; safety < 1_000_000; safety++) {
            Object step = Interpreter.invokeFunction(nf, iterObj, new Object[0], ctx);
            if (!(step instanceof JSObject stepObj)) {
                throw AbruptCompletion.typeError("AggregateError: iterator step is not an object");
            }
            if (AbstractOps.toBoolean(AbstractOps.getProperty(stepObj, "done"))) break;
            out.add(AbstractOps.getProperty(stepObj, "value"));
        }
        return out;
    }
}
