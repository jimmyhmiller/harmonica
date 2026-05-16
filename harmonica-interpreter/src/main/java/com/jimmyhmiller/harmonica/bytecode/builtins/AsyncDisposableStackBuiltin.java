package com.jimmyhmiller.harmonica.bytecode.builtins;

import java.util.ArrayList;
import java.util.Map;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.Accessor;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 12.4.4 — AsyncDisposableStack (Explicit Resource Management).
 * Mirrors LibJS's AsyncDisposableStack*.cpp files. Behavior parallels
 * {@link DisposableStackBuiltin}; the differences are the
 * {@code asyncDispose} hint when looking up the dispose hook and that
 * {@code disposeAsync} returns a Promise (here a synchronously-resolved
 * stand-in until we have full Promise machinery).
 */
public final class AsyncDisposableStackBuiltin {
    private AsyncDisposableStackBuiltin() {}

    public static void install(Map<String, Object> globals) {
        JSObject proto = new JSObject(Realm.objectPrototype);

        JSFunction ctor = Realm.nativeFn("AsyncDisposableStack", 0, (thisVal, a, c) -> {
            if (!Interpreter.isNewCall() && !(thisVal instanceof JSObject)) {
                throw AbruptCompletion.typeError(
                    "AsyncDisposableStack constructor requires 'new'");
            }
            JSObject self = thisVal instanceof JSObject jo ? jo : new JSObject(proto);
            self.set(DisposableStackBuiltin.SLOT_STATE, DisposableStackBuiltin.STATE_PENDING);
            self.setAttributes(DisposableStackBuiltin.SLOT_STATE, (byte) 0);
            self.set(DisposableStackBuiltin.SLOT_RESOURCES,
                new ArrayList<DisposableStackBuiltin.Resource>());
            self.setAttributes(DisposableStackBuiltin.SLOT_RESOURCES, (byte) 0);
            return self;
        });
        ctor.setPrototypeObject(proto);
        proto.set("constructor", ctor);
        proto.setAttributes("constructor",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        proto.set("disposed", new Accessor(Realm.nativeFn("get disposed", 0,
            (t, a, c) -> {
                JSObject stack = DisposableStackBuiltin.requireStack(t, "disposed");
                return DisposableStackBuiltin.STATE_DISPOSED.equals(
                    stack.properties().get(DisposableStackBuiltin.SLOT_STATE));
            }), null));
        proto.setAttributes("disposed", JSObject.ATTR_CONFIGURABLE);

        proto.set("use", Realm.nativeFn("use", 1, (thisVal, a, c) -> {
            JSObject stack = DisposableStackBuiltin.requireStack(thisVal, "use");
            DisposableStackBuiltin.requireNotDisposed(stack, "use");
            Object value = Realm.arg(a, 0);
            if (value == null || value == Undefined.VALUE) return value;
            DisposableStackBuiltin.addResource(stack, value,
                DisposableStackBuiltin.lookupDispose(value, "async"));
            return value;
        }));

        proto.set("adopt", Realm.nativeFn("adopt", 2, (thisVal, a, c) -> {
            JSObject stack = DisposableStackBuiltin.requireStack(thisVal, "adopt");
            DisposableStackBuiltin.requireNotDisposed(stack, "adopt");
            Object value = Realm.arg(a, 0);
            Object onDispose = Realm.arg(a, 1);
            if (!(onDispose instanceof JSFunction onDisposeFn)) {
                throw AbruptCompletion.typeError("adopt: onDispose must be callable");
            }
            JSFunction wrapper = Realm.nativeFn("", 0, (t2, a2, c2) ->
                Interpreter.invokeFunction(onDisposeFn, Undefined.VALUE,
                    new Object[]{value}, c2));
            DisposableStackBuiltin.addResource(stack, Undefined.VALUE, wrapper);
            return value;
        }));

        proto.set("defer", Realm.nativeFn("defer", 1, (thisVal, a, c) -> {
            JSObject stack = DisposableStackBuiltin.requireStack(thisVal, "defer");
            DisposableStackBuiltin.requireNotDisposed(stack, "defer");
            Object onDispose = Realm.arg(a, 0);
            if (!(onDispose instanceof JSFunction onDisposeFn)) {
                throw AbruptCompletion.typeError("defer: onDispose must be callable");
            }
            DisposableStackBuiltin.addResource(stack, Undefined.VALUE, onDisposeFn);
            return Undefined.VALUE;
        }));

        proto.set("move", Realm.nativeFn("move", 0, (thisVal, a, c) -> {
            JSObject stack = DisposableStackBuiltin.requireStack(thisVal, "move");
            DisposableStackBuiltin.requireNotDisposed(stack, "move");
            JSObject moved = new JSObject(proto);
            moved.set(DisposableStackBuiltin.SLOT_STATE, DisposableStackBuiltin.STATE_PENDING);
            moved.setAttributes(DisposableStackBuiltin.SLOT_STATE, (byte) 0);
            moved.set(DisposableStackBuiltin.SLOT_RESOURCES,
                DisposableStackBuiltin.resourcesOf(stack));
            moved.setAttributes(DisposableStackBuiltin.SLOT_RESOURCES, (byte) 0);
            stack.set(DisposableStackBuiltin.SLOT_RESOURCES,
                new ArrayList<DisposableStackBuiltin.Resource>());
            stack.set(DisposableStackBuiltin.SLOT_STATE, DisposableStackBuiltin.STATE_DISPOSED);
            return moved;
        }));

        JSFunction disposeAsyncFn = Realm.nativeFn("disposeAsync", 0, (thisVal, a, c) -> {
            JSObject stack = DisposableStackBuiltin.requireStack(thisVal, "disposeAsync");
            if (DisposableStackBuiltin.STATE_DISPOSED.equals(
                    stack.properties().get(DisposableStackBuiltin.SLOT_STATE))) {
                return Undefined.VALUE;
            }
            stack.set(DisposableStackBuiltin.SLOT_STATE, DisposableStackBuiltin.STATE_DISPOSED);
            DisposableStackBuiltin.disposeResources(
                DisposableStackBuiltin.resourcesOf(stack), c);
            stack.set(DisposableStackBuiltin.SLOT_RESOURCES,
                new ArrayList<DisposableStackBuiltin.Resource>());
            return Undefined.VALUE;
        });
        proto.set("disposeAsync", disposeAsyncFn);
        proto.setAttributes("disposeAsync",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        if (Realm.wellKnownAsyncDispose != null) {
            String key = Realm.wellKnownAsyncDispose.asPropertyKey();
            proto.set(key, disposeAsyncFn);
            proto.setAttributes(key,
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        }

        if (Realm.wellKnownToStringTag != null) {
            String key = Realm.wellKnownToStringTag.asPropertyKey();
            proto.set(key, "AsyncDisposableStack");
            proto.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        globals.putIfAbsent("AsyncDisposableStack", ctor);
    }
}
