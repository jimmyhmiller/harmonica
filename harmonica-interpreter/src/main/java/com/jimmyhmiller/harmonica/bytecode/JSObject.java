package com.jimmyhmiller.harmonica.bytecode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A JavaScript object — a string-keyed property bag with insertion-ordered
 * iteration.
 *
 * <p>v1: no shapes/hidden classes, no prototypes (per-key), no property
 * descriptors beyond the (writable, enumerable, configurable) byte flags.
 * Real JS object semantics will land as we wire shapes and prototype chains.
 */
public final class JSObject {

    /**
     * Property storage. Lazy-allocated — many short-lived JSObjects (cloneDeep
     * intermediate dictionaries, error proto-chain stubs, etc.) never get a
     * single {@code set}, so the LinkedHashMap header was wasted on those
     * (~12% of allocated bytes in lodash workload before this change).
     * Allocated on the first write or the first {@link #properties()} call.
     *
     * <p>We tried a flat {@code String[]/Object[]} small-object storage
     * (LibJS-style sans shapes); on the acorn parse-loop it lost — linear
     * scan over 6-12 keys was slower than HashMap's hash+bucket walk for
     * the typical AST-node sizes. The HashMap path wins because string
     * keys are interned by the parser (cached hashCode), so a HashMap
     * lookup is one hash → one bucket-load → one ref-compare on the hot path.
     */
    private Map<String, Object> properties;
    private static final Map<String, Object> EMPTY = java.util.Collections.emptyMap();

    /**
     * Sentinel returned by {@link #getOwn(String)} to distinguish "no own
     * property" from "own property mapped to JS {@code null}" (Java null in
     * our value model). Callers that don't need that distinction can use
     * {@link #get(String)}, which returns {@code Undefined.VALUE} for
     * missing and walks the prototype chain.
     */
    public static final Object ABSENT = new Object() {
        @Override public String toString() { return "<ABSENT>"; }
    };

    /**
     * Sidecar for non-default property attributes. Default for any key is
     * `{writable, enumerable, configurable}` all true. When any of those
     * flags is false, the relevant byte mask lives here. Sparse — most
     * properties never enter this map. ECMA-262 § 6.1.7.1 Property Attributes.
     */
    private Map<String, Byte> attributes;
    public static final byte ATTR_WRITABLE     = 0b001;
    public static final byte ATTR_ENUMERABLE   = 0b010;
    public static final byte ATTR_CONFIGURABLE = 0b100;
    public static final byte ATTR_DEFAULT      = ATTR_WRITABLE | ATTR_ENUMERABLE | ATTR_CONFIGURABLE;

    public void setAttributes(String key, byte attrs) {
        if (attributes == null) attributes = new java.util.HashMap<>();
        attributes.put(key, attrs);
    }
    public byte getAttributes(String key) {
        if (attributes == null) return ATTR_DEFAULT;
        Byte a = attributes.get(key);
        return a == null ? ATTR_DEFAULT : a;
    }
    public boolean isEnumerable(String key) {
        return (getAttributes(key) & ATTR_ENUMERABLE) != 0;
    }
    public boolean isWritable(String key) {
        return (getAttributes(key) & ATTR_WRITABLE) != 0;
    }
    public boolean isConfigurable(String key) {
        return (getAttributes(key) & ATTR_CONFIGURABLE) != 0;
    }

    /** Prototype: walked when an own property lookup misses. */
    private JSObject proto;

    /** Default-link to {@code Object.prototype} if Realm has bootstrapped. */
    public JSObject() { this.proto = Realm.objectPrototype; }
    /** Explicit-prototype constructor — used during Realm bootstrap and by class instances. */
    public JSObject(JSObject proto) { this.proto = proto; }

    /** Get an own property; returns {@link #ABSENT} if not present on this object. */
    public Object getOwn(String key) {
        if (properties == null) return ABSENT;
        Object v = properties.get(key);
        if (v != null) return v;
        // Map.get returns null both for "absent" and for "present, mapped to
        // null". Disambiguate only when the cheap fast path missed.
        if (properties.containsKey(key)) return null;
        return ABSENT;
    }

    /** True iff {@code key} is an own property of this object. */
    public boolean hasOwn(String key) {
        return properties != null && properties.containsKey(key);
    }

    public Object get(String key) {
        // Inlined own-property check for the dominant fast path.
        if (properties != null) {
            Object v = properties.get(key);
            if (v != null) return v;
            if (properties.containsKey(key)) return null;
        }
        // Walk proto chain.
        JSObject cursor = proto;
        while (cursor != null) {
            if (cursor.properties != null) {
                Object v = cursor.properties.get(key);
                if (v != null) return v;
                if (cursor.properties.containsKey(key)) return null;
            }
            cursor = cursor.proto;
        }
        return Undefined.VALUE;
    }

    public void set(String key, Object value) {
        // Spec: setting a property always writes on the receiver (own slot),
        // even if a proto has it.
        // Initial capacity 4 (rather than the JDK default 16) — most JS
        // objects on the benchmark workloads carry 3-6 properties, and the
        // 16-bucket Node[] dominated allocation profiling. JDK rounds up to
        // power of two and applies load factor 0.75: cap=4 → table size 8,
        // resize at 6 entries.
        if (properties == null) properties = new LinkedHashMap<>(4);
        properties.put(key, value);
    }

    public boolean has(String key) {
        if (properties != null && properties.containsKey(key)) return true;
        JSObject cursor = proto;
        while (cursor != null) {
            if (cursor.properties != null && cursor.properties.containsKey(key)) return true;
            cursor = cursor.proto;
        }
        return false;
    }

    public JSObject proto() { return proto; }
    public void setProto(JSObject p) { this.proto = p; }

    public Object delete(String key) {
        if (properties == null || !properties.containsKey(key)) return Boolean.TRUE;
        if (!isConfigurable(key)) {
            // ECMA-262 § 10.1.10.1 [[Delete]]: returns false on a
            // non-configurable own property. The caller (Op.DeleteByValue,
            // delete operator) is responsible for throwing TypeError in
            // strict mode based on this return value.
            return Boolean.FALSE;
        }
        properties.remove(key);
        if (attributes != null) attributes.remove(key);
        return Boolean.TRUE;
    }

    /**
     * Mutable property map. Lazy-allocates on first call so empty objects
     * stay header-only. Callers that only read (containsKey / get) should
     * prefer the {@link #get}/{@link #has}/{@link #getOwn}/{@link #hasOwn}
     * methods above — they don't force allocation.
     */
    public Map<String, Object> properties() {
        if (properties == null) properties = new LinkedHashMap<>(4);
        return properties;
    }

    /** Read-only view; never allocates. Returns empty map when no properties are set. */
    public Map<String, Object> propertiesIfPresent() {
        return properties == null ? EMPTY : properties;
    }

    @Override
    public String toString() {
        return "[object Object]";
    }
}
