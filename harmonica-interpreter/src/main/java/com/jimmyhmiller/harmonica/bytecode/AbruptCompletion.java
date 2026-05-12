package com.jimmyhmiller.harmonica.bytecode;

/**
 * The host-Java exception used to unwind a JS abrupt completion (a thrown
 * value) through the interpreter loop and across host frames.
 *
 * <p>Carries the JS value that was thrown. Stack traces are intentionally not
 * filled (they're host-frame traces, not JS-frame traces, so they cost time
 * without giving useful information).
 */
public final class AbruptCompletion extends RuntimeException {

    private final Object value;

    private static final boolean TRACE = Boolean.getBoolean("harmonica.abrupt.trace");

    public AbruptCompletion(Object value) {
        super(null, null, /* enableSuppression */ false, /* writableStackTrace */ TRACE);
        this.value = value;
        if (TRACE) {
            System.err.println("[abrupt] " + (value instanceof JSObject jo
                ? AbstractOps.toString(jo.get("name")) + ": " + AbstractOps.toString(jo.get("message"))
                : AbstractOps.toString(value)));
            this.printStackTrace();
        }
    }

    public Object value() { return value; }

    /** Build a JS Error-shaped JSObject for an internal runtime error. */
    private static AbruptCompletion error(String name, String msg) {
        // Link to the realm's error-type prototype so `.constructor`,
        // `instanceof Error`, and harness checks all work.
        JSObject proto = Realm.errorPrototypes.get(name);
        JSObject err = (proto != null) ? new JSObject(proto) : new JSObject();
        // `name` and `message` shadow the prototype's defaults so they show
        // as own properties (which is what real engines do).
        err.set("name", name);
        err.set("message", msg == null ? "" : msg);
        return new AbruptCompletion(err);
    }

    public static AbruptCompletion typeError(String msg)      { return error("TypeError", msg); }
    public static AbruptCompletion referenceError(String msg) { return error("ReferenceError", msg); }
    public static AbruptCompletion rangeError(String msg)     { return error("RangeError", msg); }
    public static AbruptCompletion syntaxError(String msg)    { return error("SyntaxError", msg); }
    public static AbruptCompletion uriError(String msg)       { return error("URIError", msg); }
    public static AbruptCompletion plainError(String msg)     { return error("Error", msg); }
}
