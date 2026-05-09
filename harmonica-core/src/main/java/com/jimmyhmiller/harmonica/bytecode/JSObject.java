package com.jimmyhmiller.harmonica.bytecode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A JavaScript object — a string-keyed property bag with insertion-ordered
 * iteration.
 *
 * <p>v1: no shapes/hidden classes, no prototypes, no property descriptors
 * (so no accessors, no enumerability flags). Just a plain map. Real JS object
 * semantics will land as we wire shapes and prototype chains.
 */
public final class JSObject {

    private final Map<String, Object> properties = new LinkedHashMap<>();
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

    public Object get(String key) {
        // Own property?
        if (properties.containsKey(key)) return properties.get(key);
        // Walk proto chain.
        JSObject cursor = proto;
        while (cursor != null) {
            if (cursor.properties.containsKey(key)) return cursor.properties.get(key);
            cursor = cursor.proto;
        }
        return Undefined.VALUE;
    }

    public void set(String key, Object value) {
        // Spec: setting a property always writes on the receiver (own slot),
        // even if a proto has it.
        properties.put(key, value);
    }

    public boolean has(String key) {
        if (properties.containsKey(key)) return true;
        JSObject cursor = proto;
        while (cursor != null) {
            if (cursor.properties.containsKey(key)) return true;
            cursor = cursor.proto;
        }
        return false;
    }

    public JSObject proto() { return proto; }
    public void setProto(JSObject p) { this.proto = p; }

    public Object delete(String key) {
        if (properties.containsKey(key) && !isConfigurable(key)) {
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

    public Map<String, Object> properties() { return properties; }

    @Override
    public String toString() {
        return "[object Object]";
    }
}
