package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

/**
 * ECMA-262 § 22.2.9 — %RegExpStringIteratorPrototype% and the
 * CreateRegExpStringIterator abstract operation. Mirrors LibJS's
 * RegExpStringIteratorPrototype.cpp.
 *
 * <p>{@code next} invokes the spec's {@code RegExpExec(R, S)} via
 * {@code R.exec(S)} so user-overridden {@code exec} wins (per § 22.2.7.1).
 */
public final class RegExpStringIteratorPrototypeBuiltin {
    private RegExpStringIteratorPrototypeBuiltin() {}

    /** Internal slots on a RegExp String Iterator instance. */
    static final String SLOT_REGEXP   = "##IteratingRegExp##";
    static final String SLOT_STRING   = "##IteratedString##";
    static final String SLOT_GLOBAL   = "##GlobalFlag##";
    static final String SLOT_UNICODE  = "##UnicodeFlag##";
    static final String SLOT_DONE     = "##IteratorDone##";

    public static void install() {
        JSObject parent = Realm.iteratorPrototype != null
            ? Realm.iteratorPrototype : Realm.objectPrototype;
        JSObject proto = new JSObject(parent);

        proto.set("next", Realm.nativeFn("next", 0, (thisVal, a, ctx) -> {
            // § 22.2.9.2.1
            if (!(thisVal instanceof JSObject iter)
                    || !iter.properties().containsKey(SLOT_REGEXP)) {
                throw AbruptCompletion.typeError(
                    "RegExp String Iterator.prototype.next called on incompatible receiver");
            }
            // 4. If [[Done]] is true, return { value: undefined, done: true }
            Object done = iter.properties().get(SLOT_DONE);
            if (done instanceof Boolean b && b) return iteratorResult(Undefined.VALUE, true);

            Object regexp = iter.properties().get(SLOT_REGEXP);
            Object stringObj = iter.properties().get(SLOT_STRING);
            String string = stringObj == null ? "" : AbstractOps.toString(stringObj);
            boolean global  = Boolean.TRUE.equals(iter.properties().get(SLOT_GLOBAL));
            boolean unicode = Boolean.TRUE.equals(iter.properties().get(SLOT_UNICODE));

            // 9. Let match be ? RegExpExec(R, S).
            Object match = invokeExec(regexp, string, ctx);

            if (match == null || match == Undefined.VALUE) {
                iter.set(SLOT_DONE, Boolean.TRUE);
                return iteratorResult(Undefined.VALUE, true);
            }
            // 11. If global is false: set Done and return { value: match, done: false }
            if (!global) {
                iter.set(SLOT_DONE, Boolean.TRUE);
                return iteratorResult(match, false);
            }
            // 12. Let matchStr = ? ToString(? Get(match, "0"))
            Object first = AbstractOps.getProperty(match, "0");
            String matchStr = AbstractOps.toString(first);
            // 13. If matchStr is "", bump lastIndex to avoid infinite loop.
            if (matchStr.isEmpty()) {
                Object liv = AbstractOps.getProperty(regexp, "lastIndex");
                int thisIndex = AbstractOps.toInt32(liv);
                int next = advanceStringIndex(string, thisIndex, unicode);
                AbstractOps.setProperty(regexp, "lastIndex", (double) next);
            }
            return iteratorResult(match, false);
        }));
        proto.setAttributes("next",
            (byte)(JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        // § 22.2.9.2.2 — @@toStringTag = "RegExp String Iterator", configurable only.
        if (Realm.wellKnownToStringTag != null) {
            String key = Realm.wellKnownToStringTag.asPropertyKey();
            proto.set(key, "RegExp String Iterator");
            proto.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
        }

        Realm.regExpStringIteratorPrototype = proto;
    }

    /** § 22.2.9.1 CreateRegExpStringIterator(R, S, global, fullUnicode). */
    public static JSObject create(Object regexp, String string, boolean global, boolean unicode) {
        JSObject iter = new JSObject(Realm.regExpStringIteratorPrototype);
        iter.set(SLOT_REGEXP, regexp);
        iter.set(SLOT_STRING, string);
        iter.set(SLOT_GLOBAL, global);
        iter.set(SLOT_UNICODE, unicode);
        iter.set(SLOT_DONE, Boolean.FALSE);
        return iter;
    }

    /** § 22.2.7.1 RegExpExec — call R.exec(S) when callable, otherwise fall
     *  back to the captured built-in (RegExpBuiltinExec). */
    private static Object invokeExec(Object regexp, String string,
                                     com.jimmyhmiller.harmonica.bytecode.InterpContext ctx) {
        Object execFn = AbstractOps.getProperty(regexp, "exec");
        JSFunction f;
        if (execFn instanceof JSFunction jf) {
            f = jf;
        } else if (Realm.originalRegExpExec != null) {
            f = Realm.originalRegExpExec;
        } else {
            throw AbruptCompletion.typeError("RegExp.prototype.exec is not callable");
        }
        Object result = Interpreter.invokeFunction(f, regexp, new Object[]{string}, ctx);
        if (result != null && result != Undefined.VALUE && !(result instanceof JSObject)
                && !(result instanceof com.jimmyhmiller.harmonica.bytecode.JSArray)) {
            throw AbruptCompletion.typeError("RegExp.prototype.exec did not return an object or null");
        }
        return result;
    }

    /** § 22.2.7.3 AdvanceStringIndex — surrogate-pair aware for /u. */
    private static int advanceStringIndex(String s, int index, boolean unicode) {
        if (!unicode) return index + 1;
        if (index + 1 >= s.length()) return index + 1;
        char first = s.charAt(index);
        if (!Character.isHighSurrogate(first)) return index + 1;
        char second = s.charAt(index + 1);
        if (!Character.isLowSurrogate(second)) return index + 1;
        return index + 2;
    }

    private static JSObject iteratorResult(Object value, boolean done) {
        JSObject r = new JSObject();
        r.set("value", value);
        r.set("done", done);
        return r;
    }
}
