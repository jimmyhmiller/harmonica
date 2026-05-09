package com.jimmyhmiller.harmonica.bytecode;

/**
 * A callable JavaScript function value.
 *
 * <p>A JSFunction is one of two shapes:
 * <ul>
 *   <li><b>Bytecode function:</b> {@link #body} non-null, holds the compiled
 *       {@link Executable} plus capture metadata.
 *   <li><b>Native function:</b> {@link #nativeBody} non-null, holds a Java
 *       lambda the interpreter calls directly.
 * </ul>
 *
 * <p>{@code body} and {@code nativeBody} are mutually exclusive: exactly one
 * is non-null.
 */
public final class JSFunction {

    private final String name;
    private final Executable body;
    private final int paramCount;
    private final int localCount;       // total local slots (captures occupy first captureCount of them)
    private final int captureCount;
    private final int[] captureSourceSlots;   // for each capture i, the parent's local slot to source from
    private final int[] captureDestSlots;     // for each capture i, the slot in this function's locals[] to install at
    private final Cell[] capturedCells;       // populated when this function value is materialized
    private final NativeBody nativeBody;       // non-null iff this is a native function

    /**
     * True for {@code function*} declarations / expressions and class methods
     * with the generator marker — ECMA-262 § 27.5 (Generator objects).
     * The interpreter recognizes this on call: instead of running the body
     * directly it materializes a {@code GeneratorObject} that carries the
     * suspended frame and exposes {@code next}/{@code return}/{@code throw}.
     */
    private boolean isGenerator;
    public boolean isGenerator() { return isGenerator; }
    public void setGenerator(boolean g) { this.isGenerator = g; }

    /**
     * True for {@code async function} declarations / expressions and async
     * methods. ECMA-262 § 27.7.5 (Async Function bodies). Calling an async
     * function always returns a Promise; the body's value resolves it (or
     * thrown errors reject it). Combined with {@link #isGenerator} this also
     * marks {@code async function*} (async generator) — § 27.6 — but our v1
     * lumps both into the same async-execution path.
     */
    private boolean isAsync;
    public boolean isAsync() { return isAsync; }
    public void setAsync(boolean a) { this.isAsync = a; }

    /**
     * True for {@code () => ...} arrow function expressions. Arrows are
     * not constructible per ECMA-262 § 15.3 — `new arrow()` must throw
     * TypeError. Also: arrows have no `prototype` property and don't
     * bind their own `this` / `arguments` / `super` / `new.target`.
     */
    private boolean isArrow;
    public boolean isArrow() { return isArrow; }
    public void setArrow(boolean a) { this.isArrow = a; }

    /**
     * Prototype object used when this function is invoked as a constructor.
     * Set by class lowering for class constructors. Mutable because the
     * generator sets it after the function value is materialized.
     */
    private JSObject prototypeObject;
    public JSObject prototypeObject() { return prototypeObject; }
    public void setPrototypeObject(JSObject p) { this.prototypeObject = p; }

    /**
     * Super-constructor — set by {@link Op.NewClass#interpret} on a derived
     * class's constructor. Read by {@link Op.GetSuperConstructor} to resolve
     * {@code super(...)} calls. Null for non-derived constructors and plain
     * functions.
     */
    private JSFunction superConstructor;
    public JSFunction superConstructor() { return superConstructor; }
    public void setSuperConstructor(JSFunction s) { this.superConstructor = s; }

    /** Static-style properties accessed via {@code Foo.bar} on the constructor. */
    /**
     * Static-style properties on the function (Foo.bar = …, plus class
     * static methods). Lazy because most function values don't have any —
     * lambda callbacks created in tight loops just sit in registers / pass
     * through and never get a property write.
     */
    private java.util.LinkedHashMap<String, Object> properties;
    public java.util.Map<String, Object> properties() {
        if (properties == null) properties = new java.util.LinkedHashMap<>(4);
        return properties;
    }
    /** Read-only view; never allocates. Returns empty map if no statics set. */
    public java.util.Map<String, Object> propertiesIfPresent() {
        return properties == null ? java.util.Collections.emptyMap() : properties;
    }
    /** Cheap null-safe key check — preferred to {@code properties().containsKey(k)} on hot paths. */
    public boolean hasOwnStatic(String key) {
        return properties != null && properties.containsKey(key);
    }
    /** Cheap null-safe get — returns null when no map is allocated yet. */
    public Object getOwnStatic(String key) {
        return properties == null ? null : properties.get(key);
    }

    /**
     * Per-property descriptor flags for static class members. Mirrors
     * {@link JSObject}'s side-car: only present when the default
     * {@code (writable, enumerable, configurable) = (true, true, true)} doesn't
     * apply (e.g. class methods, which are non-enumerable per § 15.7.10).
     */
    private java.util.Map<String, Byte> attributes;
    public void setAttributes(String key, byte attrs) {
        if (attributes == null) attributes = new java.util.HashMap<>();
        attributes.put(key, attrs);
    }
    public byte getAttributes(String key) {
        if (attributes == null) return JSObject.ATTR_DEFAULT;
        Byte a = attributes.get(key);
        return a == null ? JSObject.ATTR_DEFAULT : a;
    }
    public boolean isEnumerable(String key) {
        return (getAttributes(key) & JSObject.ATTR_ENUMERABLE) != 0;
    }
    public boolean isWritable(String key) {
        return (getAttributes(key) & JSObject.ATTR_WRITABLE) != 0;
    }
    public boolean isConfigurable(String key) {
        return (getAttributes(key) & JSObject.ATTR_CONFIGURABLE) != 0;
    }

    public JSFunction(String name, Executable body, int paramCount, int localCount,
                      int captureCount, int[] captureSourceSlots, int[] captureDestSlots, Cell[] capturedCells) {
        this.name = name;
        this.body = body;
        this.paramCount = paramCount;
        this.localCount = localCount;
        this.captureCount = captureCount;
        this.captureSourceSlots = captureSourceSlots;
        this.captureDestSlots = captureDestSlots;
        this.capturedCells = capturedCells;
        this.nativeBody = null;
    }

    /** Convenience constructor for a template (no captured cells yet). */
    public JSFunction(String name, Executable body, int paramCount, int localCount,
                      int captureCount, int[] captureSourceSlots, int[] captureDestSlots) {
        this(name, body, paramCount, localCount, captureCount, captureSourceSlots, captureDestSlots, null);
    }

    /** Native-function constructor — used by the standard library. */
    public JSFunction(String name, int paramCount, NativeBody nativeBody) {
        this.name = name;
        this.body = null;
        this.paramCount = paramCount;
        this.localCount = 0;
        this.captureCount = 0;
        this.captureSourceSlots = new int[0];
        this.captureDestSlots = new int[0];
        this.capturedCells = null;
        this.nativeBody = nativeBody;
    }

    /**
     * The "home globals" of this function — the module's globals map at the
     * point of materialization. Bytecode functions read globals through this
     * map so that a function defined in module A keeps reading A's bindings
     * even when called from module B (live bindings + per-module scope).
     *
     * <p>{@code null} for native functions and for functions defined when no
     * module is active (the legacy single-globals path stays in use).
     */
    private java.util.Map<String, Object> homeGlobals;
    public java.util.Map<String, Object> homeGlobals() { return homeGlobals; }
    public void setHomeGlobals(java.util.Map<String, Object> g) { this.homeGlobals = g; }

    /** Build a runtime function value from this template, binding captured cells. */
    public JSFunction withCapturedCells(Cell[] cells) {
        JSFunction f = new JSFunction(name, body, paramCount, localCount, captureCount,
            captureSourceSlots, captureDestSlots, cells);
        f.isGenerator = this.isGenerator;
        f.isAsync = this.isAsync;
        f.isArrow = this.isArrow;
        f.homeGlobals = this.homeGlobals;
        return f;
    }

    public String     name()                { return name; }
    public Executable body()                { return body; }
    public int        paramCount()          { return paramCount; }
    public int        localCount()          { return localCount; }
    public int        captureCount()        { return captureCount; }
    public int[]      captureSourceSlots()  { return captureSourceSlots; }
    public int[]      captureDestSlots()    { return captureDestSlots; }
    public Cell[]     capturedCells()       { return capturedCells; }
    public NativeBody nativeBody()          { return nativeBody; }
    public boolean    isNative()            { return nativeBody != null; }

    @Override
    public String toString() {
        if (nativeBody != null) {
            return "function " + (name != null ? name : "<native>") + "() { [native code] }";
        }
        return "function " + (name != null ? name : "<anonymous>") + "(" + paramCount + " params)";
    }
}
