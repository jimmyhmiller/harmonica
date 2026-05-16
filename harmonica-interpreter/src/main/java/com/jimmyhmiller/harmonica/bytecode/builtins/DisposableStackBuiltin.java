package com.jimmyhmiller.harmonica.bytecode.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.Accessor;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 12.4.3 — DisposableStack (Explicit Resource Management).
 * Mirrors LibJS's DisposableStack*.cpp files. Per spec:
 *
 *   [[DisposableState]] ∈ {"pending", "disposed"}
 *   [[DisposeCapability]] — list of resource records
 *     { resource, method, hint = "sync-dispose" }
 *
 * AddDisposableResource(stack, value, hint, [method]) is the spec-aligned
 * append; calls happen LIFO at dispose time. Errors thrown by dispose are
 * accumulated and emitted as a single error (or a SuppressedError wrapping
 * multiple) once the loop ends.
 */
public final class DisposableStackBuiltin {
    private DisposableStackBuiltin() {}

    static final String SLOT_STATE     = "##DisposableState##";
    static final String SLOT_RESOURCES = "##DisposeCapability##";

    static final String STATE_PENDING  = "pending";
    static final String STATE_DISPOSED = "disposed";

    /** A spec ResourceRecord. {@code method} may be null for adopt/defer
     *  entries where we synthesize the dispose call. */
    static final class Resource {
        final Object value;     // the resource (may be Undefined for defer)
        final JSFunction method;
        Resource(Object value, JSFunction method) { this.value = value; this.method = method; }
    }

    public static void install(Map<String, Object> globals) {
        JSObject proto = new JSObject(Realm.objectPrototype);

        JSFunction ctor = Realm.nativeFn("DisposableStack", 0, (thisVal, a, c) -> {
            if (!Interpreter.isNewCall() && !(thisVal instanceof JSObject)) {
                throw AbruptCompletion.typeError(
                    "DisposableStack constructor requires 'new'");
            }
            JSObject self = thisVal instanceof JSObject jo ? jo : new JSObject(proto);
            self.set(SLOT_STATE, STATE_PENDING);
            self.setAttributes(SLOT_STATE, (byte) 0);
            self.set(SLOT_RESOURCES, new ArrayList<Resource>());
            self.setAttributes(SLOT_RESOURCES, (byte) 0);
            return self;
        });
        ctor.setPrototypeObject(proto);
        proto.set("constructor", ctor);
        proto.setAttributes("constructor",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        // get disposed — true iff [[DisposableState]] is "disposed".
        proto.set("disposed", new Accessor(Realm.nativeFn("get disposed", 0,
            (t, a, c) -> {
                JSObject stack = requireStack(t, "disposed");
                return STATE_DISPOSED.equals(stack.properties().get(SLOT_STATE));
            }), null));
        proto.setAttributes("disposed", JSObject.ATTR_CONFIGURABLE);

        proto.set("use", Realm.nativeFn("use", 1, (thisVal, a, c) -> {
            JSObject stack = requireStack(thisVal, "use");
            requireNotDisposed(stack, "use");
            Object value = Realm.arg(a, 0);
            if (value == null || value == Undefined.VALUE) return value;
            addResource(stack, value, lookupDispose(value, "sync"));
            return value;
        }));

        proto.set("adopt", Realm.nativeFn("adopt", 2, (thisVal, a, c) -> {
            JSObject stack = requireStack(thisVal, "adopt");
            requireNotDisposed(stack, "adopt");
            Object value = Realm.arg(a, 0);
            Object onDispose = Realm.arg(a, 1);
            if (!(onDispose instanceof JSFunction onDisposeFn)) {
                throw AbruptCompletion.typeError("adopt: onDispose must be callable");
            }
            // Wrap so the onDispose callback is invoked with the value.
            JSFunction wrapper = Realm.nativeFn("", 0, (t2, a2, c2) ->
                Interpreter.invokeFunction(onDisposeFn, Undefined.VALUE,
                    new Object[]{value}, c2));
            addResource(stack, Undefined.VALUE, wrapper);
            return value;
        }));

        proto.set("defer", Realm.nativeFn("defer", 1, (thisVal, a, c) -> {
            JSObject stack = requireStack(thisVal, "defer");
            requireNotDisposed(stack, "defer");
            Object onDispose = Realm.arg(a, 0);
            if (!(onDispose instanceof JSFunction onDisposeFn)) {
                throw AbruptCompletion.typeError("defer: onDispose must be callable");
            }
            addResource(stack, Undefined.VALUE, onDisposeFn);
            return Undefined.VALUE;
        }));

        proto.set("move", Realm.nativeFn("move", 0, (thisVal, a, c) -> {
            JSObject stack = requireStack(thisVal, "move");
            requireNotDisposed(stack, "move");
            JSObject moved = new JSObject(proto);
            moved.set(SLOT_STATE, STATE_PENDING);
            moved.setAttributes(SLOT_STATE, (byte) 0);
            moved.set(SLOT_RESOURCES, resourcesOf(stack));
            moved.setAttributes(SLOT_RESOURCES, (byte) 0);
            stack.set(SLOT_RESOURCES, new ArrayList<Resource>());
            stack.set(SLOT_STATE, STATE_DISPOSED);
            return moved;
        }));

        JSFunction disposeFn = Realm.nativeFn("dispose", 0, (thisVal, a, c) -> {
            JSObject stack = requireStack(thisVal, "dispose");
            if (STATE_DISPOSED.equals(stack.properties().get(SLOT_STATE))) {
                return Undefined.VALUE;
            }
            stack.set(SLOT_STATE, STATE_DISPOSED);
            disposeResources(resourcesOf(stack), c);
            stack.set(SLOT_RESOURCES, new ArrayList<Resource>());
            return Undefined.VALUE;
        });
        proto.set("dispose", disposeFn);
        proto.setAttributes("dispose",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        // [Symbol.dispose] aliases dispose.
        if (Realm.wellKnownDispose != null) {
            String key = Realm.wellKnownDispose.asPropertyKey();
            proto.set(key, disposeFn);
            proto.setAttributes(key,
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        }

        // § 12.4.3.7 [Symbol.toStringTag] = "DisposableStack", configurable only.
        if (Realm.wellKnownToStringTag != null) {
            String key = Realm.wellKnownToStringTag.asPropertyKey();
            proto.set(key, "DisposableStack");
            proto.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        globals.putIfAbsent("DisposableStack", ctor);
    }

    // ---- shared helpers (also used by AsyncDisposableStackBuiltin) ----

    /** § 12.4.1.1 GetDisposeMethod — read the appropriate dispose hook
     *  off the resource value. Throws TypeError when missing or not
     *  callable (per spec). */
    static JSFunction lookupDispose(Object value, String hint) {
        // For "async-dispose" hint, fall back to @@dispose if @@asyncDispose missing.
        Object method = null;
        if ("async".equals(hint) && Realm.wellKnownAsyncDispose != null) {
            method = AbstractOps.getProperty(value, Realm.wellKnownAsyncDispose.asPropertyKey());
            if (method == null || method == Undefined.VALUE) method = null;
        }
        if (method == null && Realm.wellKnownDispose != null) {
            method = AbstractOps.getProperty(value, Realm.wellKnownDispose.asPropertyKey());
        }
        if (!(method instanceof JSFunction fn)) {
            throw AbruptCompletion.typeError(
                "Disposable resource does not have a [Symbol.dispose] method");
        }
        return fn;
    }

    static void addResource(JSObject stack, Object value, JSFunction method) {
        resourcesOf(stack).add(new Resource(value, method));
    }

    @SuppressWarnings("unchecked")
    static List<Resource> resourcesOf(JSObject stack) {
        return (List<Resource>) stack.properties().get(SLOT_RESOURCES);
    }

    static JSObject requireStack(Object t, String where) {
        if (t instanceof JSObject jo
                && jo.properties().get(SLOT_STATE) instanceof String) {
            return jo;
        }
        throw AbruptCompletion.typeError(
            "DisposableStack.prototype." + where + " called on incompatible receiver");
    }

    static void requireNotDisposed(JSObject stack, String where) {
        if (STATE_DISPOSED.equals(stack.properties().get(SLOT_STATE))) {
            throw AbruptCompletion.referenceError(
                "DisposableStack.prototype." + where + " called on disposed stack");
        }
    }

    /** § 12.4.4 DisposeResources — call dispose methods LIFO, collect
     *  errors, throw the single error or wrap multiple in a SuppressedError. */
    static void disposeResources(List<Resource> resources,
                                 com.jimmyhmiller.harmonica.bytecode.InterpContext c) {
        List<AbruptCompletion> errors = new ArrayList<>();
        for (int i = resources.size() - 1; i >= 0; i--) {
            Resource r = resources.get(i);
            try {
                if (r.value == Undefined.VALUE) {
                    Interpreter.invokeFunction(r.method, Undefined.VALUE, new Object[0], c);
                } else {
                    Interpreter.invokeFunction(r.method, r.value, new Object[0], c);
                }
            } catch (AbruptCompletion ac) {
                errors.add(ac);
            }
        }
        if (errors.isEmpty()) return;
        if (errors.size() == 1) throw errors.get(0);
        // Build nested SuppressedError per § 12.4.4 step 4 — outer wraps the
        // newest error, [[Error]] is the latest, [[Suppressed]] is the
        // accumulated prior chain.
        Object suppressed = errors.get(0).value();
        for (int i = 1; i < errors.size(); i++) {
            JSObject se = new JSObject(Realm.errorPrototypes.get("SuppressedError"));
            se.set("error", errors.get(i).value());
            se.setAttributes("error",
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            se.set("suppressed", suppressed);
            se.setAttributes("suppressed",
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            se.set("message", "An error was suppressed during disposal");
            se.setAttributes("message",
                (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
            suppressed = se;
        }
        throw new AbruptCompletion(suppressed);
    }
}
