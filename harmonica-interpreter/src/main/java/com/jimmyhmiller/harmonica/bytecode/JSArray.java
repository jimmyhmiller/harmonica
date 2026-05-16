package com.jimmyhmiller.harmonica.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * A JavaScript array. Backed by an {@link ArrayList} for the dense range
 * plus a sparse fallback for indices that would require an excessive
 * grow. Exposes a dynamic {@code length} property whose value is
 * {@code max(densePart.size(), sparseLengthHint)}.
 *
 * <p>v1: dense by default. When a write targets an index that would force
 * the dense backing past {@link #MAX_DENSE_LENGTH}, the value is stored
 * in {@link #extraProperties} keyed by the string index, and
 * {@link #sparseLengthHint} is bumped so {@code .length} keeps the
 * spec-compliant value. test262's {@code S15.4_A1.1_T10.js} uses
 * {@code x[2147483646] = ...} and would otherwise allocate ~16 GB of
 * Object[] slots.
 */
public final class JSArray {

    /** Hard ceiling on dense backing size — beyond this, writes go sparse. */
    public static final int MAX_DENSE_LENGTH = 1 << 20;   // 1,048,576 (~8 MB of Object[])

    private final List<Object> elements;

    /**
     * Logical array length when sparse storage holds values past the dense
     * range. {@code 0} means "use dense length only". This tracks the spec
     * length without forcing the dense ArrayList to grow.
     */
    private int sparseLengthHint = 0;

    /**
     * Non-index "extra" string-keyed properties — and the sparse storage
     * for huge integer indices. A tagged template's strings array famously
     * has a {@code raw} property (ECMA-262 § 13.3.11.4 GetTemplateObject
     * step 7); plus sparse arrays like {@code x[1<<30] = 1} live here so
     * we don't allocate 1 GB of Object[].
     */
    private java.util.LinkedHashMap<String, Object> extraProperties;

    public Object getExtraProperty(String key) {
        return extraProperties == null ? null : extraProperties.get(key);
    }
    public boolean hasExtraProperty(String key) {
        return extraProperties != null && extraProperties.containsKey(key);
    }
    public void setExtraProperty(String key, Object value) {
        if (extraProperties == null) extraProperties = new java.util.LinkedHashMap<>();
        extraProperties.put(key, value);
    }
    public java.util.Map<String, Object> extraProperties() { return extraProperties; }

    /** Per-index attribute side-table for indexed elements + length. Only
     *  populated when an attribute differs from the spec defaults so we don't
     *  allocate on every push. Default for indexed elements is the usual
     *  data-property triple {writable, enumerable, configurable} = (T, T, T);
     *  default for {@code length} is (T, F, F). */
    private java.util.HashMap<String, Byte> indexAttributes;
    /** True iff Object.preventExtensions has been called on this array. */
    private boolean extensible = true;
    public boolean isExtensible() { return extensible; }
    public void preventExtensions() { this.extensible = false; }

    /** Lookup the attribute byte for an indexed key or "length". */
    public byte getIndexAttributes(String key) {
        if (indexAttributes != null) {
            Byte b = indexAttributes.get(key);
            if (b != null) return b;
        }
        if ("length".equals(key)) return JSObject.ATTR_WRITABLE;   // (T, F, F)
        return JSObject.ATTR_DEFAULT;                              // (T, T, T)
    }
    public void setIndexAttributes(String key, byte attrs) {
        if (indexAttributes == null) indexAttributes = new java.util.HashMap<>();
        indexAttributes.put(key, attrs);
    }
    public boolean hasIndexAttributes(String key) {
        return indexAttributes != null && indexAttributes.containsKey(key);
    }
    /** Drop attribute entries for keys outside the new length — used by
     *  Object.defineProperty's length-shrink path so freed slots don't
     *  retain non-configurable flags. */
    public void clearIndexAttributesAtOrAbove(int floor) {
        if (indexAttributes == null) return;
        indexAttributes.entrySet().removeIf(e -> {
            int idx = sparseKeyToIndex(e.getKey());
            return idx >= floor;
        });
    }

    public JSArray() {
        // Default ArrayList capacity is 10. Most JSArrays in the lodash
        // workload start empty and grow via push, so the default works;
        // a smaller pre-size only helps when the size is known. Use the
        // {@link #JSArray(int)} or {@link #JSArray(Object[])} constructors
        // when the final size is known up front.
        this.elements = new ArrayList<>();
    }

    /** Pre-sized array — caller knows the final length. Saves a resize. */
    public JSArray(int initialCapacity) {
        this.elements = new ArrayList<>(initialCapacity);
    }

    public JSArray(Object[] initialElements) {
        // Pre-size the ArrayList to the exact length so the first append
        // doesn't trigger an Object[10]→Object[15] grow.
        this.elements = new ArrayList<>(initialElements.length);
        java.util.Collections.addAll(elements, initialElements);
    }

    public int length() {
        return Math.max(elements.size(), sparseLengthHint);
    }

    /**
     * Set the logical {@code .length} of the array (sparse-aware).
     * Shrinking below {@code elements.size()} truncates the dense range;
     * growing beyond it just bumps {@link #sparseLengthHint} so we don't
     * pre-fill with {@code undefined} (matters for {@code new Array(2**32)}
     * style — pre-fill would allocate 16 GB).
     */
    public void setLength(int newLength) {
        if (newLength < 0) return;
        if (newLength < elements.size()) {
            // Truncate dense range.
            while (elements.size() > newLength) elements.remove(elements.size() - 1);
            sparseLengthHint = 0;
            // Drop sparse entries past the new length.
            if (extraProperties != null) {
                java.util.Iterator<String> it = extraProperties.keySet().iterator();
                while (it.hasNext()) {
                    String k = it.next();
                    int idx = sparseKeyToIndex(k);
                    if (idx >= newLength) it.remove();
                }
            }
        } else {
            sparseLengthHint = newLength;
        }
    }

    /** Parse a non-negative integer string, or return -1 if not such. */
    private static int sparseKeyToIndex(String s) {
        if (s.isEmpty()) return -1;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            int next = n * 10 + (c - '0');
            if (next < n) return -1;
            n = next;
        }
        return n;
    }

    public Object get(int index) {
        if (index < 0) return Undefined.VALUE;
        if (index < elements.size()) {
            Object v = elements.get(index);
            // Holes (the Op.HOLE sentinel) aren't user-observable: indexed
            // [[Get]] on a missing key returns undefined per § 10.1.8.1.
            return v == Op.HOLE ? Undefined.VALUE : v;
        }
        // Sparse fallback: check the extra-properties map keyed by Integer.toString.
        if (extraProperties != null && index >= 0) {
            Object v = extraProperties.get(Integer.toString(index));
            if (v != null) return v;
        }
        return Undefined.VALUE;
    }

    /** True iff the element at {@code index} is a hole — used by
     *  Array.prototype.X HasProperty checks so sparse positions don't
     *  trigger the callback. */
    public boolean isHole(int index) {
        if (index < 0 || index >= elements.size()) return true;
        return elements.get(index) == Op.HOLE;
    }

    public void set(int index, Object value) {
        if (index < 0) return;
        if (index < elements.size()) {
            elements.set(index, value);
            return;
        }
        // Index past dense end. If growing dense would breach MAX_DENSE_LENGTH,
        // fall back to sparse storage so we don't OOM on `x[2147483646] = …`.
        if (index >= MAX_DENSE_LENGTH) {
            setExtraProperty(Integer.toString(index), value);
            if (index + 1 > sparseLengthHint) sparseLengthHint = index + 1;
            return;
        }
        while (elements.size() <= index) elements.add(Undefined.VALUE);
        elements.set(index, value);
        // Once dense covers an index, drop any earlier sparse entry to keep
        // the two storage paths consistent. (Unlikely in practice but defensive.)
        if (extraProperties != null) extraProperties.remove(Integer.toString(index));
    }

    public void push(Object value) { elements.add(value); }

    public List<Object> elements() { return elements; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(',');
            Object v = elements.get(i);
            if (v != null && v != Undefined.VALUE) sb.append(AbstractOps.toString(v));
        }
        return sb.toString();
    }
}
