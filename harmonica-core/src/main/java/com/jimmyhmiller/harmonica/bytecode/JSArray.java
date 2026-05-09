package com.jimmyhmiller.harmonica.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * A JavaScript array. Backed by an {@link ArrayList}; exposes a dynamic
 * {@code length} property.
 *
 * <p>v1: dense backing only. Sparse arrays (with holes) and accesses past
 * {@code length} aren't fully spec-compliant; we'll tighten when needed.
 */
public final class JSArray {

    private final List<Object> elements = new ArrayList<>();

    /**
     * Non-index "extra" string-keyed properties. Real arrays in JS are
     * objects with both index slots and arbitrary properties; a tagged
     * template's strings array famously has a {@code raw} property
     * (ECMA-262 § 13.3.11.4 GetTemplateObject step 7). Lazily allocated;
     * null when there are no non-index properties (the common case).
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

    public JSArray() {}

    public JSArray(Object[] initialElements) {
        for (Object e : initialElements) elements.add(e);
    }

    public int length() { return elements.size(); }

    public Object get(int index) {
        if (index < 0 || index >= elements.size()) return Undefined.VALUE;
        Object v = elements.get(index);
        // Holes (the Op.HOLE sentinel) aren't user-observable: indexed
        // [[Get]] on a missing key returns undefined per § 10.1.8.1.
        if (v == Op.HOLE) return Undefined.VALUE;
        return v;
    }

    public void set(int index, Object value) {
        // Grow with undefined as needed.
        while (elements.size() <= index) elements.add(Undefined.VALUE);
        elements.set(index, value);
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
