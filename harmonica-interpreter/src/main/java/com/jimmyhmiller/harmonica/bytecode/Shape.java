package com.jimmyhmiller.harmonica.bytecode;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hidden-class layout descriptor for a {@link JSObject}.
 *
 * <p>Every JSObject carries a {@code Shape*} reference. The Shape encodes
 * the object's:
 * <ul>
 *   <li>property layout — which keys exist and at which {@code storage[]}
 *       offset they live, along with their {@code [[Writable]]} /
 *       {@code [[Enumerable]]} / {@code [[Configurable]]} attributes;</li>
 *   <li>prototype chain link — {@code [[Prototype]]} for instances of
 *       this shape.</li>
 * </ul>
 *
 * <p>Shapes are arranged in a transition tree rooted at a per-prototype
 * empty shape. Every mutation that changes the object's layout (adding a
 * property, configuring an attribute, deleting a key, swapping the
 * prototype) creates a new child shape and reuses the cached child the
 * next time the same mutation is performed. Two objects that walked the
 * same sequence of transitions therefore share the same Shape — and a
 * monomorphic IC keyed on the Shape pointer hits across every instance
 * of "the same kind of object".
 *
 * <p>The IC machinery in Phase 2 caches {@code (shape, offset)} pairs and
 * dispatches to {@link JSObject#getDirect} / {@link JSObject#putDirect} on
 * a hit. That's the win: a property read becomes one pointer compare and
 * one indexed array load, vs. the {@code HashMap.get} probe of the legacy
 * layout.
 *
 * <p>Phase 1 (this file) ships the structural change with behavior
 * preserved — every public API on JSObject works as before, but reads
 * route through {@link #lookup}, and writes route through transitions.
 * No ICs yet; that's Phase 2.
 *
 * <p>Roughly mirrors LibJS's {@code Runtime/Shape.h}/{@code .cpp}. Java
 * GC handles weak-reference niceties LibJS does manually.
 */
public final class Shape {

    public enum TransitionType {
        /** Root of a transition tree — no parent, no key. */
        Root,
        /** Added a new property at offset = previous.propertyCount. */
        Put,
        /** Changed attributes on an existing property (offset unchanged). */
        Configure,
        /** Removed a property. Offsets in storage are NOT compacted. */
        Delete,
        /** Changed the prototype. Property layout unchanged. */
        Prototype,
    }

    /**
     * Layout descriptor for a single property within a Shape.
     *
     * @param offset position in the owning JSObject's {@code storage[]}
     * @param attrs  byte mask: {@link JSObject#ATTR_WRITABLE} |
     *               {@link JSObject#ATTR_ENUMERABLE} |
     *               {@link JSObject#ATTR_CONFIGURABLE}
     */
    public record PropertyMeta(int offset, byte attrs) {}

    /** Transition-cache key for forward transitions ({@link TransitionType#Put} / Configure). */
    private record TransitionKey(String key, byte attrs) {}

    // ------------------------------------------------------------
    // Immutable shape data.
    // ------------------------------------------------------------

    private final Shape previous;            // null at the root
    private final String changedKey;         // key added/changed/deleted on this transition; null for Root and Prototype
    private final byte changedAttrs;         // attrs for Put / Configure
    private final TransitionType transitionType;
    private final int propertyCount;         // # of live own properties
    /**
     * Max storage slots ever allocated on this transition chain. Deletes
     * orphan slots without decrementing this, so new properties added
     * after a delete get fresh offsets instead of collisions.
     */
    private final int storageSize;
    private final JSObject prototype;

    // ------------------------------------------------------------
    // Lazy + mutable caches.
    // ------------------------------------------------------------

    /**
     * Materialized property lookup table. Built lazily from the transition
     * chain on first {@link #lookup} call. Once built, every JSObject that
     * shares this shape benefits — no per-instance allocation.
     *
     * <p>{@code volatile} so other threads observe the publication; the
     * build itself isn't synchronized (races just rebuild — same content).
     */
    private volatile Map<String, PropertyMeta> propertyTable;

    // Transition caches hold children via WeakReference so a child shape with
    // no live JSObjects can be collected. LibJS gets this for free from its
    // mark-sweep GC; in Java we have to be explicit, otherwise every shape
    // ever produced by the program stays pinned by its parent's cache. Stale
    // (cleared) entries are reaped lazily on access.
    /** {@link TransitionType#Put} / {@link TransitionType#Configure} cache. */
    private Map<TransitionKey, WeakReference<Shape>> forwardTransitions;
    /** {@link TransitionType#Prototype} cache, keyed by the new prototype identity. */
    private Map<JSObject, WeakReference<Shape>> prototypeTransitions;
    /** {@link TransitionType#Delete} cache, keyed by the removed key. */
    private Map<String, WeakReference<Shape>> deleteTransitions;

    // ------------------------------------------------------------
    // Construction.
    // ------------------------------------------------------------

    private Shape(Shape previous, String changedKey, byte changedAttrs,
                  TransitionType type, int propertyCount, int storageSize, JSObject prototype) {
        this.previous = previous;
        this.changedKey = changedKey;
        this.changedAttrs = changedAttrs;
        this.transitionType = type;
        this.propertyCount = propertyCount;
        this.storageSize = storageSize;
        this.prototype = prototype;
    }

    /**
     * Make a root shape for objects with the given prototype. The returned
     * shape has zero properties. {@link Realm#shapeForEmptyObject} caches
     * these so all bare {@code new JSObject(proto)} instances start at the
     * same root.
     */
    public static Shape root(JSObject prototype) {
        return new Shape(null, null, (byte) 0, TransitionType.Root, 0, 0, prototype);
    }

    // ------------------------------------------------------------
    // Lookup.
    // ------------------------------------------------------------

    /**
     * Resolve {@code key} to its {@link PropertyMeta} on this shape, or
     * {@code null} if the shape doesn't have the key. The first call on
     * a shape builds the lookup table by walking the transition chain
     * once; subsequent calls are HashMap hits.
     */
    public PropertyMeta lookup(String key) {
        Map<String, PropertyMeta> table = propertyTable;
        if (table == null) table = ensurePropertyTable();
        return table.get(key);
    }

    /** Build the lookup table by walking the transition chain to root. */
    private Map<String, PropertyMeta> ensurePropertyTable() {
        LinkedHashMap<String, PropertyMeta> built = new LinkedHashMap<>(propertyCount * 2);
        appendInto(built);
        propertyTable = built;
        return built;
    }

    private void appendInto(LinkedHashMap<String, PropertyMeta> out) {
        if (previous != null) previous.appendInto(out);
        switch (transitionType) {
            case Put -> out.put(changedKey, new PropertyMeta(storageSize - 1, changedAttrs));
            case Configure -> {
                PropertyMeta existing = out.get(changedKey);
                if (existing != null) {
                    // Preserve offset, update attrs; preserve insertion-order position.
                    out.put(changedKey, new PropertyMeta(existing.offset(), changedAttrs));
                }
            }
            case Delete -> out.remove(changedKey);
            case Prototype, Root -> { /* no property change */ }
        }
    }

    /**
     * Read-only view of the property table for iteration (e.g. {@code
     * Object.keys}, spread, for-in). Same instance as {@link #lookup}'s
     * backing map — callers must not mutate.
     */
    public Map<String, PropertyMeta> propertyTable() {
        Map<String, PropertyMeta> table = propertyTable;
        if (table == null) table = ensurePropertyTable();
        return table;
    }

    // ------------------------------------------------------------
    // Transitions.
    // ------------------------------------------------------------

    /**
     * Add a property to this shape, returning the resulting child shape
     * (cached). Reuses the same child for every future addition of the
     * same {@code (key, attrs)} pair — the trick that lets all instances
     * of the same "class" share a Shape.
     */
    public Shape createPutTransition(String key, byte attrs) {
        TransitionKey tk = new TransitionKey(key, attrs);
        if (forwardTransitions != null) {
            WeakReference<Shape> ref = forwardTransitions.get(tk);
            if (ref != null) {
                Shape cached = ref.get();
                if (cached != null) return cached;
                forwardTransitions.remove(tk);
            }
        }
        Shape child = new Shape(this, key, attrs, TransitionType.Put,
                                propertyCount + 1, storageSize + 1, prototype);
        if (forwardTransitions == null) forwardTransitions = new HashMap<>();
        forwardTransitions.put(tk, new WeakReference<>(child));
        return child;
    }

    /**
     * Change attributes on an existing property. Returns this shape if
     * {@code attrs} already matches; otherwise a child shape with the
     * adjusted attrs.
     */
    public Shape createConfigureTransition(String key, byte attrs) {
        PropertyMeta existing = lookup(key);
        if (existing == null) {
            // Configuring a missing property → fall back to a put.
            return createPutTransition(key, attrs);
        }
        if (existing.attrs() == attrs) return this;
        TransitionKey tk = new TransitionKey(key, attrs);
        if (forwardTransitions != null) {
            WeakReference<Shape> ref = forwardTransitions.get(tk);
            if (ref != null) {
                Shape cached = ref.get();
                if (cached != null) return cached;
                forwardTransitions.remove(tk);
            }
        }
        Shape child = new Shape(this, key, attrs, TransitionType.Configure,
                                propertyCount, storageSize, prototype);
        if (forwardTransitions == null) forwardTransitions = new HashMap<>();
        forwardTransitions.put(tk, new WeakReference<>(child));
        return child;
    }

    /**
     * Remove a property. Property offsets are NOT compacted — the deleted
     * slot is orphaned in {@code storage[]}. A subsequent re-add gets a
     * fresh slot.
     */
    public Shape createDeleteTransition(String key) {
        if (deleteTransitions != null) {
            WeakReference<Shape> ref = deleteTransitions.get(key);
            if (ref != null) {
                Shape cached = ref.get();
                if (cached != null) return cached;
                deleteTransitions.remove(key);
            }
        }
        Shape child = new Shape(this, key, (byte) 0, TransitionType.Delete,
                                propertyCount - 1, storageSize, prototype);
        if (deleteTransitions == null) deleteTransitions = new HashMap<>();
        deleteTransitions.put(key, new WeakReference<>(child));
        return child;
    }

    /**
     * Swap the prototype. Returns this shape if {@code newProto} matches;
     * otherwise a child shape carrying the same property layout but a
     * different proto. Cached on the new prototype's identity.
     */
    public Shape createPrototypeTransition(JSObject newProto) {
        if (newProto == prototype) return this;
        if (prototypeTransitions != null) {
            WeakReference<Shape> ref = prototypeTransitions.get(newProto);
            if (ref != null) {
                Shape cached = ref.get();
                if (cached != null) return cached;
                prototypeTransitions.remove(newProto);
            }
        }
        Shape child = new Shape(this, null, (byte) 0, TransitionType.Prototype,
                                propertyCount, storageSize, newProto);
        if (prototypeTransitions == null) prototypeTransitions = new HashMap<>();
        prototypeTransitions.put(newProto, new WeakReference<>(child));
        return child;
    }

    // ------------------------------------------------------------
    // Accessors.
    // ------------------------------------------------------------

    public int      propertyCount() { return propertyCount; }
    public int      storageSize()   { return storageSize; }
    public JSObject prototype()     { return prototype; }
    public TransitionType type()    { return transitionType; }
}
