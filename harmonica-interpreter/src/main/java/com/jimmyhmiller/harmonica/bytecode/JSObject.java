package com.jimmyhmiller.harmonica.bytecode;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A JavaScript object — a string-keyed property bag with insertion-ordered
 * iteration.
 *
 * <p><b>Phase 1 of hidden-class migration.</b> Every object carries a
 * {@link Shape} pointer that describes its layout (which keys exist, at
 * which {@code storage[]} offset, with what attributes, and what
 * prototype). The legacy {@code LinkedHashMap<String, Object>} backing
 * is gone — reads route through {@code shape.lookup(key)} and writes
 * route through shape transitions.
 *
 * <p>Two objects that walked the same sequence of {@code set} calls end
 * up sharing a Shape thanks to the transition tree (see
 * {@link Shape#createPutTransition}). Phase 2 will key inline caches on
 * the Shape pointer for one-pointer-compare property access — the actual
 * speedup. Phase 1 is structural: behavior preserved, all 320 unit tests
 * and the 20689-test test262 baseline must stay green.
 *
 * <p>Property attributes live in {@link Shape.PropertyMeta} (one byte
 * each); the sparse sidecar {@code attributes} map of the old layout is
 * gone. {@link #proto()} reads from the shape's prototype slot;
 * {@link #setProto} transitions to a new shape.
 *
 * <p>The {@link #properties()} / {@link #propertiesIfPresent()} API
 * remains for callers that need a {@code Map}-shaped view (the legacy
 * code surface). Those build a snapshot from the shape + storage on
 * demand. Hot-path callers should prefer {@link #get}, {@link #getOwn},
 * {@link #has}, {@link #hasOwn}, and {@link #set}, which talk to the
 * shape directly.
 */
public final class JSObject {

    /**
     * Sentinel returned by {@link #getOwn(String)} to distinguish "no own
     * property" from "own property mapped to JS {@code null}" (Java null
     * in our value model). Callers that don't need the distinction can
     * use {@link #get(String)}, which returns {@code Undefined.VALUE} for
     * missing and walks the prototype chain.
     */
    public static final Object ABSENT = new Object() {
        @Override public String toString() { return "<ABSENT>"; }
    };

    // ECMA-262 § 6.1.7.1 Property Attributes. Default = all three set.
    public static final byte ATTR_WRITABLE     = 0b001;
    public static final byte ATTR_ENUMERABLE   = 0b010;
    public static final byte ATTR_CONFIGURABLE = 0b100;
    public static final byte ATTR_DEFAULT      = ATTR_WRITABLE | ATTR_ENUMERABLE | ATTR_CONFIGURABLE;

    private static final Object[] EMPTY_STORAGE = new Object[0];

    /** Current shape — describes layout + prototype. */
    private Shape shape;

    /**
     * Property values, indexed by {@link Shape.PropertyMeta#offset()}.
     * Grown lazily as properties are added; {@code storage.length} is
     * always {@code >= shape.propertyCount()}.
     */
    private Object[] storage;

    /** Default-link to {@code Object.prototype} if Realm has bootstrapped. */
    public JSObject() { this(Realm.objectPrototype); }

    /** Explicit-prototype constructor — used during Realm bootstrap and by class instances. */
    public JSObject(JSObject proto) {
        this.shape = Realm.shapeForEmptyObject(proto);
        this.storage = EMPTY_STORAGE;
    }

    // ------------------------------------------------------------
    // Shape-aware accessors (Phase 2 IC hot path).
    // ------------------------------------------------------------

    public Shape shape() { return shape; }
    public Object getDirect(int offset) { return storage[offset]; }
    public void   putDirect(int offset, Object value) { storage[offset] = value; }

    // ------------------------------------------------------------
    // Spec-style property access.
    // ------------------------------------------------------------

    /** Get an own property; returns {@link #ABSENT} if not present. */
    public Object getOwn(String key) {
        Shape.PropertyMeta meta = shape.lookup(key);
        return meta == null ? ABSENT : storage[meta.offset()];
    }

    /** True iff {@code key} is an own property of this object. */
    public boolean hasOwn(String key) {
        return shape.lookup(key) != null;
    }

    /** Walks the prototype chain. {@code Undefined.VALUE} if nowhere found. */
    public Object get(String key) {
        Shape.PropertyMeta meta = shape.lookup(key);
        if (meta != null) return storage[meta.offset()];
        JSObject cursor = shape.prototype();
        while (cursor != null) {
            meta = cursor.shape.lookup(key);
            if (meta != null) return cursor.storage[meta.offset()];
            cursor = cursor.shape.prototype();
        }
        return Undefined.VALUE;
    }

    public boolean has(String key) {
        JSObject cursor = this;
        while (cursor != null) {
            if (cursor.shape.lookup(key) != null) return true;
            cursor = cursor.shape.prototype();
        }
        return false;
    }

    /**
     * Set a property (always on the receiver, never delegating to a
     * proto-chain setter — that handling lives in {@code AbstractOps
     * .setProperty} / {@code Op.PutById}). Existing key: value-only
     * update at the cached offset. Missing key: transition to a child
     * shape via {@link Shape#createPutTransition} and grow {@code
     * storage[]} if needed.
     */
    public void set(String key, Object value) {
        Shape.PropertyMeta meta = shape.lookup(key);
        if (meta != null) {
            storage[meta.offset()] = value;
            return;
        }
        Shape newShape = shape.createPutTransition(key, ATTR_DEFAULT);
        int offset = newShape.storageSize() - 1;
        ensureStorage(offset + 1);
        storage[offset] = value;
        shape = newShape;
    }

    public Object delete(String key) {
        Shape.PropertyMeta meta = shape.lookup(key);
        if (meta == null) return Boolean.TRUE;
        if ((meta.attrs() & ATTR_CONFIGURABLE) == 0) {
            // ECMA-262 § 10.1.10.1 [[Delete]]: false on a non-configurable
            // own property; the caller decides whether strict-mode should
            // throw based on this return value.
            return Boolean.FALSE;
        }
        shape = shape.createDeleteTransition(key);
        // Orphan the storage slot — Phase 1 doesn't compact. Clear the
        // reference so the value is eligible for GC.
        storage[meta.offset()] = null;
        return Boolean.TRUE;
    }

    // ------------------------------------------------------------
    // Prototype.
    // ------------------------------------------------------------

    public JSObject proto() { return shape.prototype(); }
    public void setProto(JSObject p) { this.shape = shape.createPrototypeTransition(p); }

    // ------------------------------------------------------------
    // Attributes — read from the Shape; writes are configure transitions.
    // ------------------------------------------------------------

    public void setAttributes(String key, byte attrs) {
        this.shape = shape.createConfigureTransition(key, attrs);
    }
    public byte getAttributes(String key) {
        Shape.PropertyMeta meta = shape.lookup(key);
        return meta == null ? ATTR_DEFAULT : meta.attrs();
    }
    public boolean isWritable(String key)     { return (getAttributes(key) & ATTR_WRITABLE)     != 0; }
    public boolean isEnumerable(String key)   { return (getAttributes(key) & ATTR_ENUMERABLE)   != 0; }
    public boolean isConfigurable(String key) { return (getAttributes(key) & ATTR_CONFIGURABLE) != 0; }

    // ------------------------------------------------------------
    // Map view (legacy callers).
    // ------------------------------------------------------------

    /**
     * Live, write-through Map view of this object's own properties, in
     * insertion order. {@code put} routes to {@link #set}, {@code remove}
     * routes to {@link #delete}, and reads go through {@link #getOwn}.
     *
     * <p>Hot paths should call {@link #get}, {@link #set}, etc. directly —
     * this exists for the legacy callers that work in terms of {@code Map}.
     */
    public Map<String, Object> properties() {
        return propertiesView;
    }

    /** Read-only iteration view. Same instance as {@link #properties()}. */
    public Map<String, Object> propertiesIfPresent() {
        return propertiesView;
    }

    private final Map<String, Object> propertiesView = new AbstractMap<>() {
        @Override
        public Object get(Object key) {
            if (!(key instanceof String s)) return null;
            Object v = getOwn(s);
            return v == ABSENT ? null : v;
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String s && hasOwn(s);
        }

        @Override
        public Object put(String key, Object value) {
            Object prev = getOwn(key);
            set(key, value);
            return prev == ABSENT ? null : prev;
        }

        @Override
        public Object remove(Object key) {
            if (!(key instanceof String s)) return null;
            Object prev = getOwn(s);
            if (prev == ABSENT) return null;
            JSObject.this.delete(s);
            return prev;
        }

        @Override
        public int size() { return shape.propertyCount(); }

        @Override
        public boolean isEmpty() { return shape.propertyCount() == 0; }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Map.Entry<String, Object>> iterator() {
                    Iterator<Map.Entry<String, Shape.PropertyMeta>> base =
                        shape.propertyTable().entrySet().iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return base.hasNext(); }
                        @Override
                        public Map.Entry<String, Object> next() {
                            Map.Entry<String, Shape.PropertyMeta> e = base.next();
                            String k = e.getKey();
                            return new Map.Entry<>() {
                                @Override public String getKey() { return k; }
                                @Override public Object getValue() {
                                    Shape.PropertyMeta m = shape.lookup(k);
                                    return m == null ? null : storage[m.offset()];
                                }
                                @Override public Object setValue(Object value) {
                                    Object prev = getValue();
                                    set(k, value);
                                    return prev;
                                }
                            };
                        }
                    };
                }

                @Override
                public int size() { return shape.propertyCount(); }
            };
        }

        @Override
        public Set<String> keySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<String> iterator() {
                    return shape.propertyTable().keySet().iterator();
                }
                @Override public int size() { return shape.propertyCount(); }
                @Override public boolean contains(Object o) {
                    return o instanceof String s && hasOwn(s);
                }
                @Override public boolean remove(Object o) {
                    return o instanceof String s && JSObject.this.delete(s) == Boolean.TRUE;
                }
            };
        }

        @Override
        public java.util.Collection<Object> values() {
            return new AbstractCollection<>() {
                @Override
                public Iterator<Object> iterator() {
                    Iterator<Shape.PropertyMeta> base = shape.propertyTable().values().iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return base.hasNext(); }
                        @Override public Object next() { return storage[base.next().offset()]; }
                    };
                }
                @Override public int size() { return shape.propertyCount(); }
            };
        }
    };

    /**
     * Direct iteration over own keys without materializing a Map. Used by
     * spread, {@code Object.keys}, {@code for-in}, etc.
     */
    public Iterable<String> ownKeys() {
        return shape.propertyTable().keySet();
    }

    /**
     * Number of own properties — cheap (one read from the shape).
     */
    public int ownPropertyCount() {
        return shape.propertyCount();
    }

    // ------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------

    private void ensureStorage(int requiredLength) {
        if (storage.length >= requiredLength) return;
        int newCap = Math.max(4, storage.length);
        while (newCap < requiredLength) newCap <<= 1;
        storage = Arrays.copyOf(storage, newCap);
    }

    @Override
    public String toString() {
        return "[object Object]";
    }
}
