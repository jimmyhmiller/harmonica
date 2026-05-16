package com.jimmyhmiller.harmonica.bytecode;

import java.util.Map;

/**
 * ECMA-262 § 23.2 TypedArray family + § 25.1 ArrayBuffer + § 25.3 DataView.
 *
 * <p>All eleven view types ({@code Int8Array} … {@code BigUint64Array}) share a
 * single {@code %TypedArray%} intrinsic + prototype. View instances carry a
 * {@link TypedArrayState} in their {@link #SLOT_TYPED_ARRAY_STATE} internal
 * slot; ArrayBuffer instances carry an {@link ArrayBufferData} in
 * {@link #SLOT_ARRAY_BUFFER_DATA}; DataView instances carry the same
 * {@link TypedArrayState} shape (with {@code kind=null}) plus an explicit
 * {@code ##DataViewByteLength##} marker.
 *
 * <p>Integer-indexed property access is hooked from
 * {@link AbstractOps#getProperty}/{@link AbstractOps#setProperty}: when the
 * receiver has a {@link TypedArrayState}, the typed-array-element semantics
 * (§ 23.2.5.10/.11 IntegerIndexedElementGet/Set) take over.
 */
public final class TypedArrays {

    private TypedArrays() {}

    // ---------------------------------------------------------------
    // Internal-slot keys (Promise-style ##...## convention, ECMA-262
    // § 6.1.7.4 — slots are spec-only; user code can't observe them
    // unless it explicitly probes these names).
    // ---------------------------------------------------------------

    public static final String SLOT_ARRAY_BUFFER_DATA = "##ArrayBufferData##";
    public static final String SLOT_TYPED_ARRAY_STATE = "##TypedArrayState##";
    /** Marker for DataView (distinct from TypedArray so § 25.3 ops can validate). */
    public static final String SLOT_DATA_VIEW         = "##DataView##";

    // ---------------------------------------------------------------
    // Public prototype + intrinsic refs (populated during install()).
    // ---------------------------------------------------------------

    public static volatile JSObject arrayBufferPrototype;
    public static volatile JSObject sharedArrayBufferPrototype;
    public static volatile JSObject dataViewPrototype;
    /** %TypedArray%.prototype — shared between all view types. */
    public static volatile JSObject typedArrayPrototype;
    /** %TypedArray% — the abstract base constructor; throws when called. */
    public static volatile JSFunction typedArrayConstructor;
    /** Per-kind prototype (e.g. Uint8Array.prototype). Parent = typedArrayPrototype. */
    public static final java.util.EnumMap<TypedArrayKind, JSObject> kindPrototypes =
        new java.util.EnumMap<>(TypedArrayKind.class);
    /** Per-kind constructor (e.g. Uint8Array). */
    public static final java.util.EnumMap<TypedArrayKind, JSFunction> kindConstructors =
        new java.util.EnumMap<>(TypedArrayKind.class);

    // ---------------------------------------------------------------
    // Convenience extractors.
    // ---------------------------------------------------------------

    public static ArrayBufferData bufferDataOf(Object o) {
        if (!(o instanceof JSObject jo)) return null;
        Object v = jo.getOwn(SLOT_ARRAY_BUFFER_DATA);
        return v instanceof ArrayBufferData d ? d : null;
    }
    public static TypedArrayState stateOf(Object o) {
        if (!(o instanceof JSObject jo)) return null;
        Object v = jo.getOwn(SLOT_TYPED_ARRAY_STATE);
        return v instanceof TypedArrayState s ? s : null;
    }
    public static boolean isArrayBuffer(Object o) {
        // ArrayBuffer carries SLOT_ARRAY_BUFFER_DATA. DataView carries
        // SLOT_DATA_VIEW (and references the buffer, not stores it). TypedArray
        // carries SLOT_TYPED_ARRAY_STATE. These three slots are disjoint.
        ArrayBufferData d = bufferDataOf(o);
        return d != null && !d.shared;
    }
    public static boolean isSharedArrayBuffer(Object o) {
        ArrayBufferData d = bufferDataOf(o);
        return d != null && d.shared;
    }
    public static boolean isTypedArray(Object o)  { return stateOf(o) != null; }
    public static boolean isDataView(Object o) {
        return o instanceof JSObject jo && jo.getOwn(SLOT_DATA_VIEW) != JSObject.ABSENT;
    }
    /** ArrayBuffer.isView — § 25.1.4.3. */
    public static boolean isAnyView(Object o) { return isTypedArray(o) || isDataView(o); }

    // ---------------------------------------------------------------
    // Spec helpers.
    // ---------------------------------------------------------------

    /** ECMA-262 § 7.1.22 ToIndex. */
    public static long toIndex(Object v) {
        if (v == Undefined.VALUE) return 0L;
        double n = AbstractOps.toNumber(v);
        if (Double.isNaN(n) || n == 0.0) return 0L;
        double integer = n < 0 ? Math.ceil(n) : Math.floor(n);
        if (integer < 0 || integer > 9007199254740991.0) {  // 2^53 - 1
            throw AbruptCompletion.rangeError("Invalid index");
        }
        return (long) integer;
    }

    /** ECMA-262 § 7.1.20 ToLength. Clamped to [0, 2^53 - 1]. */
    public static long toLength(Object v) {
        double n = AbstractOps.toNumber(v);
        if (Double.isNaN(n) || n <= 0) return 0;
        if (n > 9007199254740991.0) return 9007199254740991L;
        return (long) (n < 0 ? Math.ceil(n) : Math.floor(n));
    }

    /** ECMA-262 § 7.1.5 ToIntegerOrInfinity. */
    public static double toIntegerOrInfinity(Object v) {
        double n = AbstractOps.toNumber(v);
        if (Double.isNaN(n) || n == 0.0) return 0;
        if (Double.isInfinite(n)) return n;
        return n < 0 ? Math.ceil(n) : Math.floor(n);
    }

    /**
     * ECMA-262 § 7.1.21 CanonicalNumericIndexString.
     * <p>Returns NaN for non-canonical strings (or strings that don't parse as
     * a finite IEEE-754 round-trip-able number). The returned double is the
     * candidate index; callers must additionally check IsIntegralNumber and
     * sign/range to decide if it's a valid integer-indexed access.
     */
    public static double canonicalNumericIndexString(String s) {
        if (s.equals("-0")) return -0.0;
        // ToNumber on the string. If ToString(ToNumber(s)) != s, return NaN.
        double n;
        try {
            // Match the JS ToNumber semantics for a string (mostly Double.parseDouble
            // but with trimming + "Infinity" + "0x"/"0b"/"0o" prefixes etc.). We
            // restrict to the integer-index shape: optional minus, digits,
            // optional ".d+" — anything else returns NaN early.
            String t = s.trim();
            if (t.isEmpty()) return Double.NaN;
            n = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        // Round-trip check.
        String roundTrip = AbstractOps.toString(n);
        if (!roundTrip.equals(s)) return Double.NaN;
        return n;
    }

    /**
     * Try to interpret {@code key} as an integer-indexed access into a
     * TypedArray view. Returns {@code -1} if the key is not an integer index.
     * Returns the index (as a long, may be {@code >= length}) otherwise.
     *
     * <p>Per § 23.2.5.10 IsValidIntegerIndex, fractional, negative, and
     * out-of-range indices are NOT errors; they yield {@code undefined} on
     * read and silent-drop on write. We surface them by returning {@code -1}
     * for "not an integer index" vs. {@code Long.MIN_VALUE} for "integer
     * index but out of range".
     *
     * <p>Returns {@link #IDX_NOT_INTEGER} if the key isn't an integer index
     * at all (e.g. a real string prop like "byteLength"); returns
     * {@link #IDX_OUT_OF_RANGE} if it's an integer index but out of range
     * (the typed-array spec returns {@code undefined} on read in that case).
     */
    public static final long IDX_NOT_INTEGER  = -1L;
    public static final long IDX_OUT_OF_RANGE = -2L;

    /** Number-keyed integer-index probe. */
    public static long integerIndexFromNumber(double d, int length) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return IDX_NOT_INTEGER;
        if (d != Math.floor(d)) return IDX_NOT_INTEGER;
        if (d == 0.0 && Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(-0.0)) {
            // -0 is NOT a valid integer index per § 23.2.5.10.
            return IDX_NOT_INTEGER;
        }
        long n = (long) d;
        if (n < 0 || n >= length) return IDX_OUT_OF_RANGE;
        return n;
    }

    /** String-keyed integer-index probe (e.g. arr["0"] vs arr["foo"]). */
    public static long integerIndexFromString(String s, int length) {
        if (s.isEmpty()) return IDX_NOT_INTEGER;
        double d = canonicalNumericIndexString(s);
        if (Double.isNaN(d)) return IDX_NOT_INTEGER;
        return integerIndexFromNumber(d, length);
    }

    /**
     * § 23.2.5.10 IsValidIntegerIndex but without the integer-shape check
     * (the caller has already verified that). Returns {@code false} if the
     * view is out of bounds or the index is out of range.
     */
    public static boolean indexInBounds(TypedArrayState state, long idx) {
        if (state.outOfBounds()) return false;
        return idx >= 0 && idx < state.length();
    }

    // ---------------------------------------------------------------
    // Element load/store. Reads from / writes to the buffer's bytes at the
    // appropriate offset. Bounds-checking is the caller's responsibility.
    // Endianness is platform little-endian (host byte order) per
    // § 6.1.6.1.1 — TypedArray indexed accessors use platform endianness;
    // DataView uses an explicit littleEndian arg.
    // ---------------------------------------------------------------

    /** Load element at typed-array index {@code i} (0-based). */
    public static Object loadElement(TypedArrayState state, long i) {
        ArrayBufferData buf = state.rawBuffer();
        if (buf == null || buf.isDetached()) return Undefined.VALUE;
        int off = state.byteOffset + (int) (i * state.kind.elementSize);
        byte[] data = buf.data;
        if (off < 0 || off + state.kind.elementSize > data.length) return Undefined.VALUE;
        switch (state.kind) {
            case INT8:    return (double) data[off];
            case UINT8:
            case UINT8C: return (double) (data[off] & 0xFF);
            case INT16:  return (double) (short) ((data[off] & 0xFF) | (data[off + 1] << 8));
            case UINT16: return (double) ((data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8));
            case INT32:  return (double) (
                (data[off] & 0xFF)
              | ((data[off + 1] & 0xFF) << 8)
              | ((data[off + 2] & 0xFF) << 16)
              | (data[off + 3] << 24)
            );
            case UINT32: {
                long v = (data[off] & 0xFFL)
                       | ((data[off + 1] & 0xFFL) << 8)
                       | ((data[off + 2] & 0xFFL) << 16)
                       | ((data[off + 3] & 0xFFL) << 24);
                return (double) v;
            }
            case FLOAT32: {
                int bits = (data[off] & 0xFF)
                         | ((data[off + 1] & 0xFF) << 8)
                         | ((data[off + 2] & 0xFF) << 16)
                         | (data[off + 3] << 24);
                return (double) Float.intBitsToFloat(bits);
            }
            case FLOAT64: {
                long bits = 0;
                for (int j = 0; j < 8; j++) bits |= (data[off + j] & 0xFFL) << (8 * j);
                return Double.longBitsToDouble(bits);
            }
            case BIGINT64: {
                long bits = 0;
                for (int j = 0; j < 8; j++) bits |= (data[off + j] & 0xFFL) << (8 * j);
                return new JSBigInt(bits);
            }
            case BIGUINT64: {
                long bits = 0;
                for (int j = 0; j < 8; j++) bits |= (data[off + j] & 0xFFL) << (8 * j);
                // Treat as unsigned: if negative, add 2^64.
                java.math.BigInteger bi = java.math.BigInteger.valueOf(bits);
                if (bi.signum() < 0) bi = bi.add(java.math.BigInteger.ONE.shiftLeft(64));
                return new JSBigInt(bi);
            }
        }
        return Undefined.VALUE;
    }

    /** Store {@code value} at typed-array index {@code i}. Silent on OOB / detached. */
    public static void storeElement(TypedArrayState state, long i, Object value) {
        ArrayBufferData buf = state.rawBuffer();
        if (buf == null || buf.isDetached()) return;
        int off = state.byteOffset + (int) (i * state.kind.elementSize);
        byte[] data = buf.data;
        if (off < 0 || off + state.kind.elementSize > data.length) return;
        switch (state.kind) {
            case INT8: {
                int v = (int) toIntPart(value) & 0xFF;
                data[off] = (byte) v;
                return;
            }
            case UINT8: {
                int v = (int) toIntPart(value) & 0xFF;
                data[off] = (byte) v;
                return;
            }
            case UINT8C: {
                data[off] = (byte) toUint8Clamp(value);
                return;
            }
            case INT16: {
                int v = (int) toIntPart(value) & 0xFFFF;
                data[off]     = (byte)  v;
                data[off + 1] = (byte) (v >> 8);
                return;
            }
            case UINT16: {
                int v = (int) toIntPart(value) & 0xFFFF;
                data[off]     = (byte)  v;
                data[off + 1] = (byte) (v >> 8);
                return;
            }
            case INT32:
            case UINT32: {
                int v = (int) toIntPart(value);
                data[off]     = (byte)  v;
                data[off + 1] = (byte) (v >> 8);
                data[off + 2] = (byte) (v >> 16);
                data[off + 3] = (byte) (v >> 24);
                return;
            }
            case FLOAT32: {
                int bits = Float.floatToRawIntBits((float) AbstractOps.toNumber(value));
                data[off]     = (byte)  bits;
                data[off + 1] = (byte) (bits >> 8);
                data[off + 2] = (byte) (bits >> 16);
                data[off + 3] = (byte) (bits >> 24);
                return;
            }
            case FLOAT64: {
                long bits = Double.doubleToRawLongBits(AbstractOps.toNumber(value));
                for (int j = 0; j < 8; j++) data[off + j] = (byte) (bits >> (8 * j));
                return;
            }
            case BIGINT64:
            case BIGUINT64: {
                // ECMA-262 § 23.2.4.5: ToBigInt the value first, then take
                // the low 64 bits in 2's complement. Numbers / Symbols /
                // null / undefined / non-numeric strings all throw via
                // ToBigInt; Booleans → 0n/1n; objects coerce through
                // @@toPrimitive / valueOf / toString.
                JSBigInt bi = value instanceof JSBigInt jb ? jb : AbstractOps.toBigInt(value);
                long bits = bi.value.longValue();
                for (int j = 0; j < 8; j++) data[off + j] = (byte) (bits >> (8 * j));
                return;
            }
        }
    }

    /**
     * ECMA-262 § 7.1.6 ToInt32 fold — used to coerce the JS Number to the
     * truncated integer that the integer typed-array slots store. Matches
     * the ToInt8/16/32 spec: take ToNumber, drop NaN/inf to 0, modulo 2^32,
     * then reinterpret in the target width. The {@code & 0xFF} / {@code &
     * 0xFFFF} masking at the call site reduces it further.
     */
    private static long toIntPart(Object value) {
        double n = AbstractOps.toNumber(value);
        if (Double.isNaN(n) || Double.isInfinite(n) || n == 0.0) return 0;
        double posInt = n < 0 ? -Math.floor(-n) : Math.floor(n);
        double int32bit = posInt - Math.floor(posInt / 4294967296.0) * 4294967296.0;
        long v = (long) int32bit;
        return v;
    }

    /**
     * ECMA-262 § 7.1.11 ToUint8Clamp. Round-half-to-even, clamped to [0,255].
     */
    private static int toUint8Clamp(Object value) {
        double n = AbstractOps.toNumber(value);
        if (Double.isNaN(n)) return 0;
        if (n <= 0) return 0;
        if (n >= 255) return 255;
        double floor = Math.floor(n);
        // Round half to even.
        double frac = n - floor;
        if (frac < 0.5) return (int) floor;
        if (frac > 0.5) return (int) floor + 1;
        return ((int) floor) % 2 == 0 ? (int) floor : (int) floor + 1;
    }

    // ---------------------------------------------------------------
    // Helpers callers.
    // ---------------------------------------------------------------

    static Object arg(Object[] a, int i) {
        return i < a.length ? a[i] : Undefined.VALUE;
    }

    private static JSFunction nativeFn(String name, int arity, NativeBody body) {
        return new JSFunction(name, arity, body);
    }

    private static TypedArrayState requireTypedArray(Object t, String method) {
        TypedArrayState s = stateOf(t);
        if (s == null) {
            throw AbruptCompletion.typeError(method + " called on non-TypedArray");
        }
        // ECMA-262 § 23.2.4.4 ValidateTypedArray — every prototype method
        // (except constructor / .length / .byteLength getters which return 0
        // for OOB / detached) starts by rejecting detached + out-of-bounds.
        if (s.outOfBounds()) {
            throw AbruptCompletion.typeError(method + " called on TypedArray with detached / out-of-bounds buffer");
        }
        return s;
    }

    /** Like {@link #requireTypedArray} but skips the buffer validation —
     *  used by getters that explicitly want to observe OOB / detached. */
    private static TypedArrayState requireTypedArrayBase(Object t, String method) {
        TypedArrayState s = stateOf(t);
        if (s == null) {
            throw AbruptCompletion.typeError(method + " called on non-TypedArray");
        }
        return s;
    }
    private static JSObject requireArrayBuffer(Object t, String method) {
        if (!(t instanceof JSObject jo) || jo.getOwn(SLOT_ARRAY_BUFFER_DATA) == JSObject.ABSENT) {
            throw AbruptCompletion.typeError(method + " called on non-ArrayBuffer");
        }
        return jo;
    }
    private static JSObject requireDataView(Object t, String method) {
        if (!(t instanceof JSObject jo) || jo.getOwn(SLOT_DATA_VIEW) == JSObject.ABSENT) {
            throw AbruptCompletion.typeError(method + " called on non-DataView");
        }
        return jo;
    }

    // ---------------------------------------------------------------
    // Bootstrap.
    // ---------------------------------------------------------------

    public static void install(Map<String, Object> globals) {
        installArrayBuffer(globals);
        installSharedArrayBuffer(globals);
        installDataView(globals);
        installTypedArrayIntrinsic(globals);
        installViewConstructors(globals);

        // Host hook used by test262 ($262.detachArrayBuffer wraps this).
        globals.put("__detachArrayBuffer", nativeFn("__detachArrayBuffer", 1, (t, a, c) -> {
            Object v = arg(a, 0);
            if (v instanceof JSObject jo) {
                ArrayBufferData d = bufferDataOf(jo);
                if (d != null && !d.shared) d.detach();
            }
            return Undefined.VALUE;
        }));
    }

    // ---------------------------------------------------------------
    // ArrayBuffer
    // ---------------------------------------------------------------

    private static void installArrayBuffer(Map<String, Object> globals) {
        arrayBufferPrototype = new JSObject(Realm.objectPrototype);

        JSFunction ctor = nativeFn("ArrayBuffer", 1, (t, a, c) -> {
            // See per-kind ctor comment — accept JSObject receiver as
            // construct signal to compensate for super-call codegen.
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("ArrayBuffer constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(arrayBufferPrototype);
            long byteLength = toIndex(arg(a, 0));
            // Parse options bag: { maxByteLength }.
            int maxByteLength = -1;
            Object opts = arg(a, 1);
            if (opts instanceof JSObject optsObj) {
                Object m = optsObj.get("maxByteLength");
                if (m != Undefined.VALUE) {
                    long mv = toIndex(m);
                    if (mv < byteLength) {
                        throw AbruptCompletion.rangeError("maxByteLength < byteLength");
                    }
                    if (mv > Integer.MAX_VALUE) {
                        throw AbruptCompletion.rangeError("maxByteLength too large");
                    }
                    maxByteLength = (int) mv;
                }
            }
            if (byteLength > Integer.MAX_VALUE) {
                throw AbruptCompletion.rangeError("byteLength too large");
            }
            self.set(SLOT_ARRAY_BUFFER_DATA, new ArrayBufferData((int) byteLength, false, maxByteLength));
            self.setAttributes(SLOT_ARRAY_BUFFER_DATA, (byte) 0);
            return self;
        });
        ctor.setPrototypeObject(arrayBufferPrototype);
        arrayBufferPrototype.set("constructor", ctor);
        arrayBufferPrototype.setAttributes("constructor", (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        // Statics
        ctor.properties().put("isView", nativeFn("isView", 1, (t, a, c) -> isAnyView(arg(a, 0))));
        // ArrayBuffer[Symbol.species] returns ArrayBuffer.
        installSpecies(ctor);

        // byteLength getter — § 25.1.4.1
        defineGetter(arrayBufferPrototype, "byteLength", "get byteLength", (t, a, c) -> {
            JSObject self = requireArrayBuffer(t, "get byteLength");
            ArrayBufferData d = bufferDataOf(self);
            if (d == null) throw AbruptCompletion.typeError("Not an ArrayBuffer");
            if (d.shared) throw AbruptCompletion.typeError("get byteLength called on SharedArrayBuffer");
            if (d.isDetached()) return 0.0;
            return (double) d.byteLength();
        });
        defineGetter(arrayBufferPrototype, "maxByteLength", "get maxByteLength", (t, a, c) -> {
            JSObject self = requireArrayBuffer(t, "get maxByteLength");
            ArrayBufferData d = bufferDataOf(self);
            if (d == null || d.shared) throw AbruptCompletion.typeError("Not an ArrayBuffer");
            if (d.isDetached()) return 0.0;
            return (double) (d.isResizable() ? d.maxByteLength : d.byteLength());
        });
        defineGetter(arrayBufferPrototype, "resizable", "get resizable", (t, a, c) -> {
            JSObject self = requireArrayBuffer(t, "get resizable");
            ArrayBufferData d = bufferDataOf(self);
            return d != null && !d.shared && d.isResizable();
        });
        defineGetter(arrayBufferPrototype, "detached", "get detached", (t, a, c) -> {
            JSObject self = requireArrayBuffer(t, "get detached");
            ArrayBufferData d = bufferDataOf(self);
            return d != null && !d.shared && d.isDetached();
        });

        // Methods
        arrayBufferPrototype.set("slice", nativeFn("slice", 2, (t, a, c) -> {
            JSObject self = requireArrayBuffer(t, "slice");
            ArrayBufferData d = bufferDataOf(self);
            if (d == null) throw AbruptCompletion.typeError("Not an ArrayBuffer");
            if (d.isDetached()) throw AbruptCompletion.typeError("Cannot slice detached ArrayBuffer");
            int len = d.byteLength();
            int start = clampToIndex(toIntegerOrInfinity(arg(a, 0)), len);
            int end   = arg(a, 1) == Undefined.VALUE ? len
                       : clampToIndex(toIntegerOrInfinity(arg(a, 1)), len);
            int newLen = Math.max(0, end - start);
            JSObject out = new JSObject(arrayBufferPrototype);
            ArrayBufferData copy = new ArrayBufferData(newLen, false, -1);
            System.arraycopy(d.data, start, copy.data, 0, newLen);
            out.set(SLOT_ARRAY_BUFFER_DATA, copy);
            out.setAttributes(SLOT_ARRAY_BUFFER_DATA, (byte) 0);
            return out;
        }));
        arrayBufferPrototype.set("resize", nativeFn("resize", 1, (t, a, c) -> {
            JSObject self = requireArrayBuffer(t, "resize");
            ArrayBufferData d = bufferDataOf(self);
            if (d == null || d.shared) throw AbruptCompletion.typeError("Not an ArrayBuffer");
            if (!d.isResizable()) throw AbruptCompletion.typeError("ArrayBuffer is not resizable");
            if (d.isDetached()) throw AbruptCompletion.typeError("Cannot resize detached ArrayBuffer");
            long newLen = toIndex(arg(a, 0));
            if (newLen > d.maxByteLength) {
                throw AbruptCompletion.rangeError("newLength > maxByteLength");
            }
            d.resize((int) newLen);
            return Undefined.VALUE;
        }));
        arrayBufferPrototype.set("transfer", nativeFn("transfer", 0, (t, a, c) -> {
            JSObject self = requireArrayBuffer(t, "transfer");
            ArrayBufferData d = bufferDataOf(self);
            if (d == null || d.shared) throw AbruptCompletion.typeError("Not an ArrayBuffer");
            if (d.isDetached()) throw AbruptCompletion.typeError("Cannot transfer detached ArrayBuffer");
            long newLen = arg(a, 0) == Undefined.VALUE ? d.byteLength() : toIndex(arg(a, 0));
            if (newLen > Integer.MAX_VALUE) throw AbruptCompletion.rangeError("newLength too large");
            byte[] old = d.takeData();
            byte[] next = new byte[(int) newLen];
            System.arraycopy(old, 0, next, 0, Math.min(old.length, next.length));
            int max = d.isResizable() ? d.maxByteLength : -1;
            ArrayBufferData newD = new ArrayBufferData(next, false, max);
            JSObject out = new JSObject(arrayBufferPrototype);
            out.set(SLOT_ARRAY_BUFFER_DATA, newD);
            out.setAttributes(SLOT_ARRAY_BUFFER_DATA, (byte) 0);
            return out;
        }));
        arrayBufferPrototype.set("transferToFixedLength", nativeFn("transferToFixedLength", 0, (t, a, c) -> {
            JSObject self = requireArrayBuffer(t, "transferToFixedLength");
            ArrayBufferData d = bufferDataOf(self);
            if (d == null || d.shared) throw AbruptCompletion.typeError("Not an ArrayBuffer");
            if (d.isDetached()) throw AbruptCompletion.typeError("Cannot transfer detached ArrayBuffer");
            long newLen = arg(a, 0) == Undefined.VALUE ? d.byteLength() : toIndex(arg(a, 0));
            if (newLen > Integer.MAX_VALUE) throw AbruptCompletion.rangeError("newLength too large");
            byte[] old = d.takeData();
            byte[] next = new byte[(int) newLen];
            System.arraycopy(old, 0, next, 0, Math.min(old.length, next.length));
            ArrayBufferData newD = new ArrayBufferData(next, false, -1);
            JSObject out = new JSObject(arrayBufferPrototype);
            out.set(SLOT_ARRAY_BUFFER_DATA, newD);
            out.setAttributes(SLOT_ARRAY_BUFFER_DATA, (byte) 0);
            return out;
        }));

        // Symbol.toStringTag = "ArrayBuffer".
        setToStringTag(arrayBufferPrototype, "ArrayBuffer");

        globals.putIfAbsent("ArrayBuffer", ctor);
    }

    // ---------------------------------------------------------------
    // SharedArrayBuffer — minimal so test262 sees the constructor.
    // ---------------------------------------------------------------

    private static void installSharedArrayBuffer(Map<String, Object> globals) {
        sharedArrayBufferPrototype = new JSObject(Realm.objectPrototype);
        JSFunction ctor = nativeFn("SharedArrayBuffer", 1, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("SharedArrayBuffer constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(sharedArrayBufferPrototype);
            long byteLength = toIndex(arg(a, 0));
            int maxByteLength = -1;
            Object opts = arg(a, 1);
            if (opts instanceof JSObject optsObj) {
                Object m = optsObj.get("maxByteLength");
                if (m != Undefined.VALUE) maxByteLength = (int) toIndex(m);
            }
            if (byteLength > Integer.MAX_VALUE) {
                throw AbruptCompletion.rangeError("byteLength too large");
            }
            self.set(SLOT_ARRAY_BUFFER_DATA, new ArrayBufferData((int) byteLength, true, maxByteLength));
            self.setAttributes(SLOT_ARRAY_BUFFER_DATA, (byte) 0);
            return self;
        });
        ctor.setPrototypeObject(sharedArrayBufferPrototype);
        sharedArrayBufferPrototype.set("constructor", ctor);
        sharedArrayBufferPrototype.setAttributes("constructor", (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        defineGetter(sharedArrayBufferPrototype, "byteLength", "get byteLength", (t, a, c) -> {
            ArrayBufferData d = bufferDataOf(t);
            if (d == null || !d.shared) {
                throw AbruptCompletion.typeError("get byteLength called on non-SharedArrayBuffer");
            }
            return (double) d.byteLength();
        });
        sharedArrayBufferPrototype.set("slice", nativeFn("slice", 2, (t, a, c) -> {
            if (!(t instanceof JSObject self) || bufferDataOf(self) == null || !bufferDataOf(self).shared) {
                throw AbruptCompletion.typeError("slice called on non-SharedArrayBuffer");
            }
            ArrayBufferData d = bufferDataOf(self);
            int len = d.byteLength();
            int start = clampToIndex(toIntegerOrInfinity(arg(a, 0)), len);
            int end   = arg(a, 1) == Undefined.VALUE ? len
                       : clampToIndex(toIntegerOrInfinity(arg(a, 1)), len);
            int newLen = Math.max(0, end - start);
            JSObject out = new JSObject(sharedArrayBufferPrototype);
            ArrayBufferData copy = new ArrayBufferData(newLen, true, -1);
            System.arraycopy(d.data, start, copy.data, 0, newLen);
            out.set(SLOT_ARRAY_BUFFER_DATA, copy);
            out.setAttributes(SLOT_ARRAY_BUFFER_DATA, (byte) 0);
            return out;
        }));
        setToStringTag(sharedArrayBufferPrototype, "SharedArrayBuffer");
        installSpecies(ctor);
        globals.putIfAbsent("SharedArrayBuffer", ctor);
    }

    // ---------------------------------------------------------------
    // DataView
    // ---------------------------------------------------------------

    private static void installDataView(Map<String, Object> globals) {
        dataViewPrototype = new JSObject(Realm.objectPrototype);

        JSFunction ctor = nativeFn("DataView", 1, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("DataView constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(dataViewPrototype);
            Object bufArg = arg(a, 0);
            if (!(bufArg instanceof JSObject bufObj) || bufferDataOf(bufObj) == null) {
                throw AbruptCompletion.typeError("DataView requires an ArrayBuffer");
            }
            ArrayBufferData d = bufferDataOf(bufObj);
            if (d.isDetached()) throw AbruptCompletion.typeError("ArrayBuffer is detached");
            long byteOffset = arg(a, 1) == Undefined.VALUE ? 0 : toIndex(arg(a, 1));
            int bufLen = d.byteLength();
            if (byteOffset > bufLen) {
                throw AbruptCompletion.rangeError("byteOffset > buffer.byteLength");
            }
            int byteLength;
            if (arg(a, 2) == Undefined.VALUE) {
                if (d.isResizable()) byteLength = -1;
                else byteLength = bufLen - (int) byteOffset;
            } else {
                long bl = toIndex(arg(a, 2));
                if (byteOffset + bl > bufLen) {
                    throw AbruptCompletion.rangeError("byteOffset + byteLength > buffer.byteLength");
                }
                byteLength = (int) bl;
            }
            // Re-check detach (spec: detach can happen during ToIndex callbacks).
            if (d.isDetached()) throw AbruptCompletion.typeError("ArrayBuffer is detached");
            // Store DataView state in the same shape as TypedArrayState (kind=null sentinel).
            self.set(SLOT_DATA_VIEW, new TypedArrayState(null, bufObj, (int) byteOffset, byteLength, -2));
            self.setAttributes(SLOT_DATA_VIEW, (byte) 0);
            return self;
        });
        ctor.setPrototypeObject(dataViewPrototype);
        dataViewPrototype.set("constructor", ctor);
        dataViewPrototype.setAttributes("constructor", (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        // Getters
        defineGetter(dataViewPrototype, "buffer", "get buffer", (t, a, c) -> {
            JSObject self = requireDataView(t, "get buffer");
            TypedArrayState s = (TypedArrayState) self.getOwn(SLOT_DATA_VIEW);
            return s.buffer;
        });
        defineGetter(dataViewPrototype, "byteLength", "get byteLength", (t, a, c) -> {
            JSObject self = requireDataView(t, "get byteLength");
            TypedArrayState s = (TypedArrayState) self.getOwn(SLOT_DATA_VIEW);
            ArrayBufferData d = bufferDataOf(s.buffer);
            if (d == null || d.isDetached()) throw AbruptCompletion.typeError("Buffer is detached");
            if (s.byteLengthOrAuto >= 0) {
                if (s.byteOffset + s.byteLengthOrAuto > d.byteLength()) {
                    throw AbruptCompletion.typeError("DataView is out of bounds");
                }
                return (double) s.byteLengthOrAuto;
            }
            // length-tracking
            if (s.byteOffset > d.byteLength()) {
                throw AbruptCompletion.typeError("DataView is out of bounds");
            }
            return (double) (d.byteLength() - s.byteOffset);
        });
        defineGetter(dataViewPrototype, "byteOffset", "get byteOffset", (t, a, c) -> {
            JSObject self = requireDataView(t, "get byteOffset");
            TypedArrayState s = (TypedArrayState) self.getOwn(SLOT_DATA_VIEW);
            ArrayBufferData d = bufferDataOf(s.buffer);
            if (d == null || d.isDetached()) throw AbruptCompletion.typeError("Buffer is detached");
            return (double) s.byteOffset;
        });

        // Reads
        dvGet(dataViewPrototype, "getInt8",    1, false, TypedArrayKind.INT8);
        dvGet(dataViewPrototype, "getUint8",   1, false, TypedArrayKind.UINT8);
        dvGet(dataViewPrototype, "getInt16",   2, true,  TypedArrayKind.INT16);
        dvGet(dataViewPrototype, "getUint16",  2, true,  TypedArrayKind.UINT16);
        dvGet(dataViewPrototype, "getInt32",   4, true,  TypedArrayKind.INT32);
        dvGet(dataViewPrototype, "getUint32",  4, true,  TypedArrayKind.UINT32);
        dvGet(dataViewPrototype, "getFloat32", 4, true,  TypedArrayKind.FLOAT32);
        dvGet(dataViewPrototype, "getFloat64", 8, true,  TypedArrayKind.FLOAT64);
        dvGet(dataViewPrototype, "getBigInt64", 8, true, TypedArrayKind.BIGINT64);
        dvGet(dataViewPrototype, "getBigUint64", 8, true, TypedArrayKind.BIGUINT64);

        dvSet(dataViewPrototype, "setInt8",    1, false, TypedArrayKind.INT8);
        dvSet(dataViewPrototype, "setUint8",   1, false, TypedArrayKind.UINT8);
        dvSet(dataViewPrototype, "setInt16",   2, true,  TypedArrayKind.INT16);
        dvSet(dataViewPrototype, "setUint16",  2, true,  TypedArrayKind.UINT16);
        dvSet(dataViewPrototype, "setInt32",   4, true,  TypedArrayKind.INT32);
        dvSet(dataViewPrototype, "setUint32",  4, true,  TypedArrayKind.UINT32);
        dvSet(dataViewPrototype, "setFloat32", 4, true,  TypedArrayKind.FLOAT32);
        dvSet(dataViewPrototype, "setFloat64", 8, true,  TypedArrayKind.FLOAT64);
        dvSet(dataViewPrototype, "setBigInt64", 8, true, TypedArrayKind.BIGINT64);
        dvSet(dataViewPrototype, "setBigUint64", 8, true, TypedArrayKind.BIGUINT64);

        setToStringTag(dataViewPrototype, "DataView");
        globals.putIfAbsent("DataView", ctor);
    }

    private static void dvGet(JSObject proto, String name, int size, boolean takesLE, TypedArrayKind kind) {
        int arity = takesLE ? 1 : 1;
        proto.set(name, nativeFn(name, arity, (t, a, c) -> {
            // ECMA-262 § 25.3.1.1 GetViewValue. Spec order:
            //   1. Validate receiver is DataView
            //   4. getIndex = ToIndex(requestIndex)   ← may throw RangeError
            //   6. let buffer = view.[[ViewedArrayBuffer]]
            //   7. If IsDetachedBuffer(buffer) → TypeError
            //   8. If getIndex + size > view.byteLength → RangeError
            JSObject self = requireDataView(t, name);
            TypedArrayState s = (TypedArrayState) self.getOwn(SLOT_DATA_VIEW);
            long byteOffset = toIndex(arg(a, 0));
            boolean littleEndian = takesLE && AbstractOps.toBoolean(arg(a, 1));
            ArrayBufferData d = bufferDataOf(s.buffer);
            if (d == null || d.isDetached()) throw AbruptCompletion.typeError("Buffer is detached");
            int viewLen = s.byteLengthOrAuto >= 0 ? s.byteLengthOrAuto
                                                  : Math.max(0, d.byteLength() - s.byteOffset);
            if (byteOffset + size > viewLen) {
                throw AbruptCompletion.rangeError("Offset out of bounds for " + name);
            }
            int off = s.byteOffset + (int) byteOffset;
            return readBytes(d.data, off, size, kind, littleEndian);
        }));
    }

    private static void dvSet(JSObject proto, String name, int size, boolean takesLE, TypedArrayKind kind) {
        int arity = takesLE ? 2 : 2;
        proto.set(name, nativeFn(name, arity, (t, a, c) -> {
            // Spec order (§ 25.3.1.2 SetViewValue):
            //   1. Validate receiver
            //   4. getIndex = ToIndex(requestIndex)   ← may throw
            //   5. numberValue / bigIntValue = To{Number,BigInt}(value) ← may throw / detach
            //   8. Re-fetch buffer + detach check (after coercions)
            //   9. Bounds check
            JSObject self = requireDataView(t, name);
            TypedArrayState s = (TypedArrayState) self.getOwn(SLOT_DATA_VIEW);
            long byteOffset = toIndex(arg(a, 0));
            Object value = arg(a, 1);
            // Coerce the value to its native form NOW so any side-effecting
            // valueOf / @@toPrimitive (which may detach the buffer) runs
            // before the detach check.
            Object coerced;
            if (kind == TypedArrayKind.BIGINT64 || kind == TypedArrayKind.BIGUINT64) {
                coerced = AbstractOps.toBigInt(value);
            } else {
                coerced = AbstractOps.toNumber(value);
            }
            boolean littleEndian = takesLE && AbstractOps.toBoolean(arg(a, 2));
            ArrayBufferData d = bufferDataOf(s.buffer);
            if (d == null || d.isDetached()) throw AbruptCompletion.typeError("Buffer is detached");
            int viewLen = s.byteLengthOrAuto >= 0 ? s.byteLengthOrAuto
                                                  : Math.max(0, d.byteLength() - s.byteOffset);
            if (byteOffset + size > viewLen) {
                throw AbruptCompletion.rangeError("Offset out of bounds for " + name);
            }
            int off = s.byteOffset + (int) byteOffset;
            writeBytes(d.data, off, size, kind, littleEndian, coerced);
            return Undefined.VALUE;
        }));
    }

    /** Read a single element of {@code kind} at {@code data[off]} with explicit endianness. */
    private static Object readBytes(byte[] data, int off, int size, TypedArrayKind kind, boolean littleEndian) {
        // Build the raw little-endian-bits value first, then swap if needed.
        long bits = 0;
        if (littleEndian) {
            for (int j = 0; j < size; j++) bits |= (data[off + j] & 0xFFL) << (8 * j);
        } else {
            for (int j = 0; j < size; j++) bits |= (data[off + j] & 0xFFL) << (8 * (size - 1 - j));
        }
        switch (kind) {
            case INT8:    return (double) (byte) bits;
            case UINT8:
            case UINT8C: return (double) (bits & 0xFF);
            case INT16:  return (double) (short) bits;
            case UINT16: return (double) (bits & 0xFFFF);
            case INT32:  return (double) (int) bits;
            case UINT32: return (double) (bits & 0xFFFFFFFFL);
            case FLOAT32: return (double) Float.intBitsToFloat((int) bits);
            case FLOAT64: return Double.longBitsToDouble(bits);
            case BIGINT64:
            case BIGUINT64: return (double) bits;
        }
        return Undefined.VALUE;
    }

    private static void writeBytes(byte[] data, int off, int size, TypedArrayKind kind, boolean littleEndian, Object value) {
        long bits;
        switch (kind) {
            case INT8:
            case UINT8:    bits = toIntPart(value) & 0xFFL; break;
            case UINT8C:   bits = toUint8Clamp(value) & 0xFFL; break;
            case INT16:
            case UINT16:   bits = toIntPart(value) & 0xFFFFL; break;
            case INT32:
            case UINT32:   bits = toIntPart(value) & 0xFFFFFFFFL; break;
            case FLOAT32:  bits = Float.floatToRawIntBits((float) AbstractOps.toNumber(value)) & 0xFFFFFFFFL; break;
            case FLOAT64:  bits = Double.doubleToRawLongBits(AbstractOps.toNumber(value)); break;
            case BIGINT64:
            case BIGUINT64:
                // ECMA-262 § 25.3.1.2 ToBigInt64 / ToBigUint64 — clamp the
                // BigInt's bit pattern to a signed/unsigned 64-bit slot.
                if (value instanceof JSBigInt bi) {
                    bits = bi.value.longValue();   // 2's-complement low 64 bits
                } else {
                    bits = AbstractOps.toBigInt(value).value.longValue();
                }
                break;
            default:       bits = 0;
        }
        if (littleEndian) {
            for (int j = 0; j < size; j++) data[off + j] = (byte) (bits >> (8 * j));
        } else {
            for (int j = 0; j < size; j++) data[off + j] = (byte) (bits >> (8 * (size - 1 - j)));
        }
    }

    // ---------------------------------------------------------------
    // %TypedArray% intrinsic (the abstract base; throws when called)
    // and its shared prototype methods.
    // ---------------------------------------------------------------

    private static void installTypedArrayIntrinsic(Map<String, Object> globals) {
        typedArrayPrototype = new JSObject(Realm.objectPrototype);
        typedArrayConstructor = nativeFn("TypedArray", 0, (t, a, c) -> {
            throw AbruptCompletion.typeError("TypedArray cannot be invoked directly");
        });
        typedArrayConstructor.setPrototypeObject(typedArrayPrototype);
        typedArrayPrototype.set("constructor", typedArrayConstructor);
        typedArrayPrototype.setAttributes("constructor", (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        // Statics on the %TypedArray% intrinsic itself. § 23.2.2.1 / § 23.2.2.2 —
        // these check IsConstructor(this) first, so direct invocation (where
        // this is undefined) throws TypeError before any other work.
        typedArrayConstructor.properties().put("from", nativeFn("from", 1, (t, a, c) -> {
            TypedArrayKind k = kindOfConstructor(t);
            if (k == null) throw AbruptCompletion.typeError("TypedArray.from: receiver is not a TypedArray constructor");
            Object src = arg(a, 0);
            Object mapFn = arg(a, 1);
            JSFunction mf = mapFn == Undefined.VALUE ? null : asFn(mapFn, "from");
            Object thisArg = arg(a, 2);
            return fromIterableOrArrayLike(k, src, mf, thisArg, c);
        }));
        typedArrayConstructor.properties().put("of", nativeFn("of", 0, (t, a, c) -> {
            TypedArrayKind k = kindOfConstructor(t);
            if (k == null) throw AbruptCompletion.typeError("TypedArray.of: receiver is not a TypedArray constructor");
            JSObject out = createTypedArrayFromKind(k, a.length);
            TypedArrayState os = stateOf(out);
            for (int i = 0; i < a.length; i++) storeElement(os, i, a[i]);
            return out;
        }));
        installSpecies(typedArrayConstructor);
        setToStringTag(typedArrayPrototype, null);  // No tag on %TypedArray%.prototype directly per spec.

        // Prototype getters — pulled out so each kind's prototype inherits them.
        // These don't validate (§ 23.2.3.x — only require the slot, not the
        // bounds). OOB views return 0 for byteLength/length/byteOffset, but
        // .buffer still works.
        defineGetter(typedArrayPrototype, "buffer", "get buffer", (t, a, c) -> {
            TypedArrayState s = requireTypedArrayBase(t, "get buffer");
            return s.buffer;
        });
        defineGetter(typedArrayPrototype, "byteLength", "get byteLength", (t, a, c) -> {
            TypedArrayState s = requireTypedArrayBase(t, "get byteLength");
            if (s.outOfBounds()) return 0.0;
            return (double) s.currentByteLength();
        });
        defineGetter(typedArrayPrototype, "byteOffset", "get byteOffset", (t, a, c) -> {
            TypedArrayState s = requireTypedArrayBase(t, "get byteOffset");
            if (s.outOfBounds()) return 0.0;
            return (double) s.byteOffset;
        });
        defineGetter(typedArrayPrototype, "length", "get length", (t, a, c) -> {
            TypedArrayState s = requireTypedArrayBase(t, "get length");
            if (s.outOfBounds()) return 0.0;
            return (double) s.length();
        });
        defineGetter(typedArrayPrototype, Realm.wellKnownToStringTag.asPropertyKey(),
                     "get [Symbol.toStringTag]", (t, a, c) -> {
            TypedArrayState s = stateOf(t);
            return s == null ? Undefined.VALUE : s.kind.name;
        });

        installTypedArrayPrototypeMethods();
    }

    /** Install all the §23.2.3.* prototype methods on %TypedArray%.prototype. */
    private static void installTypedArrayPrototypeMethods() {
        JSObject p = typedArrayPrototype;

        p.set("at", nativeFn("at", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "at");
            int len = s.length();
            double rel = toIntegerOrInfinity(arg(a, 0));
            long k = rel >= 0 ? (long) rel : (long) (len + rel);
            if (k < 0 || k >= len) return Undefined.VALUE;
            return loadElement(s, k);
        }));

        p.set("fill", nativeFn("fill", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "fill");
            int len = s.length();
            Object v = arg(a, 0);
            int start = sliceIdx(arg(a, 1), len, 0);
            int end = arg(a, 2) == Undefined.VALUE ? len : sliceIdx(arg(a, 2), len, len);
            if (s.outOfBounds()) throw AbruptCompletion.typeError("TypedArray out of bounds");
            for (int i = start; i < end; i++) storeElement(s, i, v);
            return t;
        }));

        p.set("copyWithin", nativeFn("copyWithin", 2, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "copyWithin");
            int len = s.length();
            int target = sliceIdx(arg(a, 0), len, 0);
            int start  = sliceIdx(arg(a, 1), len, 0);
            int end    = arg(a, 2) == Undefined.VALUE ? len : sliceIdx(arg(a, 2), len, len);
            int count = Math.min(end - start, len - target);
            if (count <= 0) return t;
            ArrayBufferData buf = s.rawBuffer();
            if (buf == null || buf.isDetached()) throw AbruptCompletion.typeError("Detached");
            int es = s.kind.elementSize;
            System.arraycopy(buf.data, s.byteOffset + start * es,
                             buf.data, s.byteOffset + target * es, count * es);
            return t;
        }));

        p.set("slice", nativeFn("slice", 2, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "slice");
            int len = s.length();
            int start = sliceIdx(arg(a, 0), len, 0);
            int end   = arg(a, 1) == Undefined.VALUE ? len : sliceIdx(arg(a, 1), len, len);
            int count = Math.max(0, end - start);
            JSObject out = createTypedArrayFromKind(s.kind, count);
            TypedArrayState os = stateOf(out);
            for (int i = 0; i < count; i++) {
                storeElement(os, i, loadElement(s, start + i));
            }
            return out;
        }));

        p.set("subarray", nativeFn("subarray", 2, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "subarray");
            int len = s.length();
            int start = sliceIdx(arg(a, 0), len, 0);
            int end   = arg(a, 1) == Undefined.VALUE ? len : sliceIdx(arg(a, 1), len, len);
            int newLen = Math.max(0, end - start);
            int newOffset = s.byteOffset + start * s.kind.elementSize;
            JSObject out = new JSObject(kindPrototypes.get(s.kind));
            out.set(SLOT_TYPED_ARRAY_STATE, new TypedArrayState(s.kind, s.buffer, newOffset, newLen * s.kind.elementSize, newLen));
            out.setAttributes(SLOT_TYPED_ARRAY_STATE, (byte) 0);
            return out;
        }));

        p.set("set", nativeFn("set", 1, (t, a, c) -> {
            // ECMA-262 § 23.2.3.27 %TypedArray%.prototype.set(source, offset).
            // 1) Validate receiver. 2) ToInteger(offset) (may have side
            //    effects — e.g. detach the buffer). 3) Re-validate buffer.
            //    4) ToObject(source). 5) If source is a typed array,
            //    use the typed-array path; otherwise array-like path.
            TypedArrayState s = requireTypedArray(t, "set");
            Object src = arg(a, 0);
            double offsetD = arg(a, 1) == Undefined.VALUE ? 0 : toIntegerOrInfinity(arg(a, 1));
            if (offsetD < 0 || Double.isNaN(offsetD)) {
                throw AbruptCompletion.rangeError("offset out of range");
            }
            // Re-validate target: offset coercion may have detached.
            if (s.outOfBounds()) {
                throw AbruptCompletion.typeError("TypedArray.prototype.set: target buffer is detached");
            }
            int offset = (int) Math.min(offsetD, Integer.MAX_VALUE);
            if (src == null || src == Undefined.VALUE) {
                throw AbruptCompletion.typeError("TypedArray.prototype.set: source is " + (src == null ? "null" : "undefined"));
            }
            if (src instanceof JSObject so && stateOf(so) != null) {
                TypedArrayState ss = stateOf(so);
                if (ss.outOfBounds()) {
                    throw AbruptCompletion.typeError("TypedArray.prototype.set: source buffer is detached");
                }
                int slen = ss.length();
                if (offset + slen > s.length()) throw AbruptCompletion.rangeError("set: offset + source.length > target.length");
                // Spec note: when src and target overlap, copy through a
                // temporary; otherwise direct.
                Object[] tmp = new Object[slen];
                for (int i = 0; i < slen; i++) tmp[i] = loadElement(ss, i);
                for (int i = 0; i < slen; i++) storeElement(s, offset + i, tmp[i]);
            } else if (src instanceof JSArray arr) {
                int slen = arr.length();
                if (offset + slen > s.length()) throw AbruptCompletion.rangeError("set: offset + source.length > target.length");
                for (int i = 0; i < slen; i++) storeElement(s, offset + i, arr.get(i));
            } else if (src instanceof JSObject so) {
                // Array-like: coerce length, then read each index.
                Object lenVal = AbstractOps.getProperty(so, "length");
                double lenD = AbstractOps.toNumber(lenVal);
                if (Double.isNaN(lenD)) lenD = 0;
                int slen = (int) Math.max(0, Math.min(lenD, Integer.MAX_VALUE));
                if (offset + slen > s.length()) throw AbruptCompletion.rangeError("set: offset + source.length > target.length");
                for (int i = 0; i < slen; i++) {
                    Object v = AbstractOps.getProperty(so, Integer.toString(i));
                    // Each storeElement coerces; if the buffer detaches
                    // mid-coercion, storeElement is a no-op so we re-check.
                    if (s.outOfBounds()) {
                        throw AbruptCompletion.typeError("TypedArray.prototype.set: target buffer detached mid-set");
                    }
                    storeElement(s, offset + i, v);
                }
            } else {
                // Primitive source — wrap to handle as array-like.
                throw AbruptCompletion.typeError("TypedArray.prototype.set: source is not an object");
            }
            return Undefined.VALUE;
        }));

        p.set("includes", nativeFn("includes", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "includes");
            int len = s.length();
            Object target = arg(a, 0);
            int from = arg(a, 1) == Undefined.VALUE ? 0 : (int) toIntegerOrInfinity(arg(a, 1));
            if (from < 0) from = Math.max(0, len + from);
            for (int i = from; i < len; i++) {
                Object v = loadElement(s, i);
                if (sameValueZero(v, target)) return true;
            }
            return false;
        }));

        p.set("indexOf", nativeFn("indexOf", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "indexOf");
            int len = s.length();
            Object target = arg(a, 0);
            if (len == 0) return -1.0;
            int from = arg(a, 1) == Undefined.VALUE ? 0 : (int) toIntegerOrInfinity(arg(a, 1));
            if (from < 0) from = Math.max(0, len + from);
            for (int i = from; i < len; i++) {
                Object v = loadElement(s, i);
                if (AbstractOps.strictlyEquals(v, target)) return (double) i;
            }
            return -1.0;
        }));

        p.set("lastIndexOf", nativeFn("lastIndexOf", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "lastIndexOf");
            int len = s.length();
            Object target = arg(a, 0);
            if (len == 0) return -1.0;
            int from = arg(a, 1) == Undefined.VALUE ? len - 1 : (int) toIntegerOrInfinity(arg(a, 1));
            if (from < 0) from = len + from;
            for (int i = Math.min(from, len - 1); i >= 0; i--) {
                Object v = loadElement(s, i);
                if (AbstractOps.strictlyEquals(v, target)) return (double) i;
            }
            return -1.0;
        }));

        p.set("join", nativeFn("join", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "join");
            int len = s.length();
            String sep = arg(a, 0) == Undefined.VALUE ? "," : AbstractOps.toString(arg(a, 0));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(sep);
                Object v = loadElement(s, i);
                if (v == Undefined.VALUE || v == null) continue;
                sb.append(AbstractOps.toString(v));
            }
            return sb.toString();
        }));

        p.set("toString", nativeFn("toString", 0, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "toString");
            int len = s.length();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                Object v = loadElement(s, i);
                if (v == Undefined.VALUE || v == null) continue;
                sb.append(AbstractOps.toString(v));
            }
            return sb.toString();
        }));

        p.set("reverse", nativeFn("reverse", 0, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "reverse");
            int len = s.length();
            for (int i = 0, j = len - 1; i < j; i++, j--) {
                Object a1 = loadElement(s, i);
                Object b1 = loadElement(s, j);
                storeElement(s, i, b1);
                storeElement(s, j, a1);
            }
            return t;
        }));

        p.set("forEach", nativeFn("forEach", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "forEach");
            JSFunction fn = asFn(arg(a, 0), "forEach");
            Object thisArg = arg(a, 1);
            int len = s.length();
            for (int i = 0; i < len; i++) {
                Interpreter.invokeFunction(fn, thisArg, new Object[]{loadElement(s, i), (double) i, t}, c);
            }
            return Undefined.VALUE;
        }));

        p.set("map", nativeFn("map", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "map");
            JSFunction fn = asFn(arg(a, 0), "map");
            Object thisArg = arg(a, 1);
            int len = s.length();
            JSObject out = createTypedArrayFromKind(s.kind, len);
            TypedArrayState os = stateOf(out);
            for (int i = 0; i < len; i++) {
                Object v = Interpreter.invokeFunction(fn, thisArg, new Object[]{loadElement(s, i), (double) i, t}, c);
                storeElement(os, i, v);
            }
            return out;
        }));

        p.set("filter", nativeFn("filter", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "filter");
            JSFunction fn = asFn(arg(a, 0), "filter");
            Object thisArg = arg(a, 1);
            int len = s.length();
            java.util.List<Object> keep = new java.util.ArrayList<>();
            for (int i = 0; i < len; i++) {
                Object v = loadElement(s, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg, new Object[]{v, (double) i, t}, c))) {
                    keep.add(v);
                }
            }
            JSObject out = createTypedArrayFromKind(s.kind, keep.size());
            TypedArrayState os = stateOf(out);
            for (int i = 0; i < keep.size(); i++) storeElement(os, i, keep.get(i));
            return out;
        }));

        p.set("reduce", nativeFn("reduce", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "reduce");
            JSFunction fn = asFn(arg(a, 0), "reduce");
            int len = s.length();
            int start = 0;
            Object acc;
            if (a.length >= 2) acc = a[1];
            else {
                if (len == 0) throw AbruptCompletion.typeError("Reduce of empty array with no initial value");
                acc = loadElement(s, 0);
                start = 1;
            }
            for (int i = start; i < len; i++) {
                acc = Interpreter.invokeFunction(fn, Undefined.VALUE, new Object[]{acc, loadElement(s, i), (double) i, t}, c);
            }
            return acc;
        }));

        p.set("reduceRight", nativeFn("reduceRight", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "reduceRight");
            JSFunction fn = asFn(arg(a, 0), "reduceRight");
            int len = s.length();
            int start = len - 1;
            Object acc;
            if (a.length >= 2) acc = a[1];
            else {
                if (len == 0) throw AbruptCompletion.typeError("Reduce of empty array with no initial value");
                acc = loadElement(s, len - 1);
                start = len - 2;
            }
            for (int i = start; i >= 0; i--) {
                acc = Interpreter.invokeFunction(fn, Undefined.VALUE, new Object[]{acc, loadElement(s, i), (double) i, t}, c);
            }
            return acc;
        }));

        p.set("some", nativeFn("some", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "some");
            JSFunction fn = asFn(arg(a, 0), "some");
            Object thisArg = arg(a, 1);
            int len = s.length();
            for (int i = 0; i < len; i++) {
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg, new Object[]{loadElement(s, i), (double) i, t}, c))) {
                    return true;
                }
            }
            return false;
        }));

        p.set("every", nativeFn("every", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "every");
            JSFunction fn = asFn(arg(a, 0), "every");
            Object thisArg = arg(a, 1);
            int len = s.length();
            for (int i = 0; i < len; i++) {
                if (!AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg, new Object[]{loadElement(s, i), (double) i, t}, c))) {
                    return false;
                }
            }
            return true;
        }));

        p.set("find", nativeFn("find", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "find");
            JSFunction fn = asFn(arg(a, 0), "find");
            Object thisArg = arg(a, 1);
            int len = s.length();
            for (int i = 0; i < len; i++) {
                Object v = loadElement(s, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg, new Object[]{v, (double) i, t}, c))) return v;
            }
            return Undefined.VALUE;
        }));

        p.set("findIndex", nativeFn("findIndex", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "findIndex");
            JSFunction fn = asFn(arg(a, 0), "findIndex");
            Object thisArg = arg(a, 1);
            int len = s.length();
            for (int i = 0; i < len; i++) {
                Object v = loadElement(s, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg, new Object[]{v, (double) i, t}, c))) return (double) i;
            }
            return -1.0;
        }));

        p.set("findLast", nativeFn("findLast", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "findLast");
            JSFunction fn = asFn(arg(a, 0), "findLast");
            Object thisArg = arg(a, 1);
            int len = s.length();
            for (int i = len - 1; i >= 0; i--) {
                Object v = loadElement(s, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg, new Object[]{v, (double) i, t}, c))) return v;
            }
            return Undefined.VALUE;
        }));

        p.set("findLastIndex", nativeFn("findLastIndex", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "findLastIndex");
            JSFunction fn = asFn(arg(a, 0), "findLastIndex");
            Object thisArg = arg(a, 1);
            int len = s.length();
            for (int i = len - 1; i >= 0; i--) {
                Object v = loadElement(s, i);
                if (AbstractOps.toBoolean(Interpreter.invokeFunction(fn, thisArg, new Object[]{v, (double) i, t}, c))) return (double) i;
            }
            return -1.0;
        }));

        p.set("sort", nativeFn("sort", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "sort");
            int len = s.length();
            Object cmpArg = arg(a, 0);
            JSFunction cmp = cmpArg == Undefined.VALUE ? null : asFn(cmpArg, "sort");
            // ECMA-262 § 23.2.3.30: read all elements via loadElement,
            // sort, write back. Keep them boxed as Object so BigInt
            // typed arrays preserve their JSBigInt values across the sort.
            Object[] vals = new Object[len];
            for (int i = 0; i < len; i++) vals[i] = loadElement(s, i);
            if (cmp == null) {
                // Default compare: ascending numeric (or BigInt), NaN last.
                java.util.Arrays.sort(vals, (x, y) -> {
                    if (x instanceof java.math.BigInteger || x instanceof JSBigInt) {
                        java.math.BigInteger bx = x instanceof JSBigInt jbx ? jbx.value : (java.math.BigInteger) x;
                        java.math.BigInteger by = y instanceof JSBigInt jby ? jby.value : (java.math.BigInteger) y;
                        return bx.compareTo(by);
                    }
                    double dx = x instanceof Number nx ? nx.doubleValue() : Double.NaN;
                    double dy = y instanceof Number ny ? ny.doubleValue() : Double.NaN;
                    if (Double.isNaN(dx)) return Double.isNaN(dy) ? 0 : 1;
                    if (Double.isNaN(dy)) return -1;
                    return Double.compare(dx, dy);
                });
            } else {
                java.util.Arrays.sort(vals, (x, y) -> {
                    Object r = Interpreter.invokeFunction(cmp, Undefined.VALUE, new Object[]{x, y}, c);
                    double d = AbstractOps.toNumber(r);
                    return Double.isNaN(d) ? 0 : (d < 0 ? -1 : (d > 0 ? 1 : 0));
                });
            }
            for (int i = 0; i < len; i++) storeElement(s, i, vals[i]);
            return t;
        }));

        p.set("toReversed", nativeFn("toReversed", 0, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "toReversed");
            int len = s.length();
            JSObject out = createTypedArrayFromKind(s.kind, len);
            TypedArrayState os = stateOf(out);
            for (int i = 0; i < len; i++) storeElement(os, i, loadElement(s, len - 1 - i));
            return out;
        }));

        p.set("toSorted", nativeFn("toSorted", 1, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "toSorted");
            int len = s.length();
            JSObject out = createTypedArrayFromKind(s.kind, len);
            TypedArrayState os = stateOf(out);
            for (int i = 0; i < len; i++) storeElement(os, i, loadElement(s, i));
            // Reuse the sort logic by calling its method.
            Object sortFn = p.get("sort");
            if (sortFn instanceof JSFunction sf) {
                Interpreter.invokeFunction(sf, out, new Object[]{arg(a, 0)}, c);
            }
            return out;
        }));

        p.set("with", nativeFn("with", 2, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "with");
            int len = s.length();
            int idx = (int) toIntegerOrInfinity(arg(a, 0));
            if (idx < 0) idx += len;
            if (idx < 0 || idx >= len) throw AbruptCompletion.rangeError("with: index out of range");
            JSObject out = createTypedArrayFromKind(s.kind, len);
            TypedArrayState os = stateOf(out);
            for (int i = 0; i < len; i++) {
                storeElement(os, i, i == idx ? arg(a, 1) : loadElement(s, i));
            }
            return out;
        }));

        p.set("keys", nativeFn("keys", 0, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "keys");
            return makeArrayIterator(t, "keys");
        }));
        p.set("values", nativeFn("values", 0, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "values");
            return makeArrayIterator(t, "values");
        }));
        p.set("entries", nativeFn("entries", 0, (t, a, c) -> {
            TypedArrayState s = requireTypedArray(t, "entries");
            return makeArrayIterator(t, "entries");
        }));
        // %TypedArray%.prototype[@@iterator] = values
        p.set(Realm.wellKnownIterator.asPropertyKey(), p.get("values"));
        p.setAttributes(Realm.wellKnownIterator.asPropertyKey(),
            (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        p.set("toLocaleString", nativeFn("toLocaleString", 0, (t, a, c) -> {
            // ECMA-262 § 23.2.3.31 — for each element, invoke its
            // .toLocaleString(), ToString the result, join with ",".
            // Numbers/BigInts default to their decimal form (the
            // implementation's locale-aware formatter is identity here).
            TypedArrayState s = requireTypedArray(t, "toLocaleString");
            int len = s.length();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                Object v = loadElement(s, i);
                if (v == null || v == Undefined.VALUE) continue;
                Object toLoc = AbstractOps.getProperty(v, "toLocaleString");
                if (toLoc instanceof JSFunction tf) {
                    Object r = Interpreter.invokeFunction(tf, v, new Object[0], c);
                    sb.append(AbstractOps.toString(r));
                } else {
                    sb.append(AbstractOps.toString(v));
                }
            }
            return sb.toString();
        }));
    }

    /** Construct a brand-new view of the given kind with the given element length. */
    public static JSObject createTypedArrayFromKind(TypedArrayKind kind, int length) {
        JSObject buf = new JSObject(arrayBufferPrototype);
        int bytes = length * kind.elementSize;
        buf.set(SLOT_ARRAY_BUFFER_DATA, new ArrayBufferData(bytes, false, -1));
        buf.setAttributes(SLOT_ARRAY_BUFFER_DATA, (byte) 0);
        JSObject view = new JSObject(kindPrototypes.get(kind));
        view.set(SLOT_TYPED_ARRAY_STATE, new TypedArrayState(kind, buf, 0, bytes, length));
        view.setAttributes(SLOT_TYPED_ARRAY_STATE, (byte) 0);
        return view;
    }

    // ---------------------------------------------------------------
    // Per-kind constructors (§23.2.4 TypedArrayConstructor algorithm).
    // ---------------------------------------------------------------

    private static void installViewConstructors(Map<String, Object> globals) {
        for (TypedArrayKind kind : TypedArrayKind.values()) {
            installViewConstructor(globals, kind);
        }
    }

    private static void installViewConstructor(Map<String, Object> globals, TypedArrayKind kind) {
        JSObject proto = new JSObject(typedArrayPrototype);
        kindPrototypes.put(kind, proto);

        JSFunction ctor = nativeFn(kind.name, 3, (t, a, c) -> {
            // ECMA-262 § 23.2.5.1: TypedArray ctors require new.target.
            // Harmonica's super-call codegen (Generator.java line 8464)
            // emits Op.Call rather than a constructor op, so isNewCall()
            // is false for super(...). Accept any JSObject receiver as
            // the construct signal — the receiver provided by either
            // CallConstruct (new) or SuperCall is always a freshly
            // allocated JSObject with the right prototype chain.
            boolean isConstructCall = Interpreter.isNewCall() || t instanceof JSObject;
            if (!isConstructCall) {
                throw AbruptCompletion.typeError(kind.name + " constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            allocateTypedArray(self, kind, a, c);
            return self;
        });
        ctor.setPrototypeObject(proto);
        proto.set("constructor", ctor);
        proto.setAttributes("constructor", (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));

        // Static BYTES_PER_ELEMENT.
        ctor.properties().put("BYTES_PER_ELEMENT", (double) kind.elementSize);
        ctor.setAttributes("BYTES_PER_ELEMENT", (byte) 0);
        // Instance prototype BYTES_PER_ELEMENT (§23.2.7.5).
        proto.set("BYTES_PER_ELEMENT", (double) kind.elementSize);
        proto.setAttributes("BYTES_PER_ELEMENT", (byte) 0);

        // Set %TypedArray% as constructor's [[Prototype]].
        // (Subclasses observe this via Object.getPrototypeOf(Uint8Array).)
        // JSFunction doesn't expose a clean way; the constructor function's
        // [[Prototype]] handling lives in setSuperConstructor.
        ctor.setSuperConstructor(typedArrayConstructor);

        // Statics inherited from %TypedArray%.
        ctor.properties().put("from", nativeFn("from", 1, (t, a, c) -> {
            Object src = arg(a, 0);
            Object mapFn = arg(a, 1);
            JSFunction mf = mapFn == Undefined.VALUE ? null : asFn(mapFn, "from");
            Object thisArg = arg(a, 2);
            return fromIterableOrArrayLike(kind, src, mf, thisArg, c);
        }));
        ctor.properties().put("of", nativeFn("of", 0, (t, a, c) -> {
            JSObject out = createTypedArrayFromKind(kind, a.length);
            TypedArrayState os = stateOf(out);
            for (int i = 0; i < a.length; i++) storeElement(os, i, a[i]);
            return out;
        }));
        installSpecies(ctor);

        // ECMA-262 (Uint8Array base64/hex proposal, Stage 4 ES2025) —
        // Uint8Array-only static helpers and prototype methods for
        // encoding/decoding base64 and hex.
        if (kind == TypedArrayKind.UINT8) {
            ctor.properties().put("fromBase64", nativeFn("fromBase64", 1, (t, a, c) -> {
                String s = AbstractOps.toString(arg(a, 0));
                try {
                    byte[] decoded = java.util.Base64.getDecoder().decode(s.replaceAll("\\s+", ""));
                    JSObject out = createTypedArrayFromKind(kind, decoded.length);
                    TypedArrayState st = stateOf(out);
                    ArrayBufferData buf = bufferDataOf(st.buffer);
                    System.arraycopy(decoded, 0, buf.data, st.byteOffset, decoded.length);
                    return out;
                } catch (IllegalArgumentException e) {
                    throw AbruptCompletion.syntaxError("Invalid base64 input");
                }
            }));
            ctor.properties().put("fromHex", nativeFn("fromHex", 1, (t, a, c) -> {
                String s = AbstractOps.toString(arg(a, 0));
                if ((s.length() & 1) != 0) throw AbruptCompletion.syntaxError("Hex string length must be even");
                byte[] decoded = new byte[s.length() / 2];
                for (int i = 0; i < decoded.length; i++) {
                    int hi = Character.digit(s.charAt(i * 2), 16);
                    int lo = Character.digit(s.charAt(i * 2 + 1), 16);
                    if (hi < 0 || lo < 0) throw AbruptCompletion.syntaxError("Invalid hex character");
                    decoded[i] = (byte) ((hi << 4) | lo);
                }
                JSObject out = createTypedArrayFromKind(kind, decoded.length);
                TypedArrayState st = stateOf(out);
                ArrayBufferData buf = bufferDataOf(st.buffer);
                System.arraycopy(decoded, 0, buf.data, st.byteOffset, decoded.length);
                return out;
            }));
            proto.set("toBase64", nativeFn("toBase64", 0, (t, a, c) -> {
                TypedArrayState st = stateOf(t instanceof JSObject jo ? jo : null);
                if (st == null || st.kind != TypedArrayKind.UINT8) {
                    throw AbruptCompletion.typeError("toBase64 called on non-Uint8Array");
                }
                ArrayBufferData buf = bufferDataOf(st.buffer);
                if (buf == null || buf.isDetached()) throw AbruptCompletion.typeError("Buffer is detached");
                int len = st.byteLengthOrAuto >= 0 ? st.byteLengthOrAuto
                                                  : Math.max(0, buf.byteLength() - st.byteOffset);
                byte[] slice = new byte[len];
                System.arraycopy(buf.data, st.byteOffset, slice, 0, len);
                // Check for `alphabet` and `omitPadding` options.
                String alphabet = "base64";
                boolean omitPadding = false;
                if (arg(a, 0) instanceof JSObject opts) {
                    Object alpha = AbstractOps.getProperty(opts, "alphabet");
                    if (alpha instanceof CharSequence cs) alphabet = cs.toString();
                    omitPadding = AbstractOps.toBoolean(AbstractOps.getProperty(opts, "omitPadding"));
                }
                java.util.Base64.Encoder enc = "base64url".equals(alphabet)
                    ? java.util.Base64.getUrlEncoder()
                    : java.util.Base64.getEncoder();
                if (omitPadding) enc = enc.withoutPadding();
                return enc.encodeToString(slice);
            }));
            proto.set("toHex", nativeFn("toHex", 0, (t, a, c) -> {
                TypedArrayState st = stateOf(t instanceof JSObject jo ? jo : null);
                if (st == null || st.kind != TypedArrayKind.UINT8) {
                    throw AbruptCompletion.typeError("toHex called on non-Uint8Array");
                }
                ArrayBufferData buf = bufferDataOf(st.buffer);
                if (buf == null || buf.isDetached()) throw AbruptCompletion.typeError("Buffer is detached");
                int len = st.byteLengthOrAuto >= 0 ? st.byteLengthOrAuto
                                                  : Math.max(0, buf.byteLength() - st.byteOffset);
                StringBuilder sb = new StringBuilder(len * 2);
                for (int i = 0; i < len; i++) {
                    int b = buf.data[st.byteOffset + i] & 0xFF;
                    sb.append(Character.forDigit(b >>> 4, 16));
                    sb.append(Character.forDigit(b & 0xF, 16));
                }
                return sb.toString();
            }));
            // setFromBase64 / setFromHex — in-place decode into the target.
            proto.set("setFromBase64", nativeFn("setFromBase64", 1, (t, a, c) -> {
                if (!(t instanceof JSObject self) || stateOf(self) == null
                    || stateOf(self).kind != TypedArrayKind.UINT8) {
                    throw AbruptCompletion.typeError("setFromBase64 called on non-Uint8Array");
                }
                TypedArrayState st = stateOf(self);
                ArrayBufferData buf = bufferDataOf(st.buffer);
                String s = AbstractOps.toString(arg(a, 0));
                byte[] decoded;
                try { decoded = java.util.Base64.getDecoder().decode(s.replaceAll("\\s+", "")); }
                catch (IllegalArgumentException e) { throw AbruptCompletion.syntaxError("Invalid base64 input"); }
                int capacity = st.byteLengthOrAuto >= 0 ? st.byteLengthOrAuto
                                                       : Math.max(0, buf.byteLength() - st.byteOffset);
                int written = Math.min(decoded.length, capacity);
                System.arraycopy(decoded, 0, buf.data, st.byteOffset, written);
                JSObject result = new JSObject();
                result.set("read", (double) written);
                result.set("written", (double) written);
                return result;
            }));
            proto.set("setFromHex", nativeFn("setFromHex", 1, (t, a, c) -> {
                if (!(t instanceof JSObject self) || stateOf(self) == null
                    || stateOf(self).kind != TypedArrayKind.UINT8) {
                    throw AbruptCompletion.typeError("setFromHex called on non-Uint8Array");
                }
                TypedArrayState st = stateOf(self);
                ArrayBufferData buf = bufferDataOf(st.buffer);
                String s = AbstractOps.toString(arg(a, 0));
                if ((s.length() & 1) != 0) throw AbruptCompletion.syntaxError("Hex string length must be even");
                int capacity = st.byteLengthOrAuto >= 0 ? st.byteLengthOrAuto
                                                       : Math.max(0, buf.byteLength() - st.byteOffset);
                int pairCount = Math.min(s.length() / 2, capacity);
                for (int i = 0; i < pairCount; i++) {
                    int hi = Character.digit(s.charAt(i * 2), 16);
                    int lo = Character.digit(s.charAt(i * 2 + 1), 16);
                    if (hi < 0 || lo < 0) throw AbruptCompletion.syntaxError("Invalid hex character");
                    buf.data[st.byteOffset + i] = (byte) ((hi << 4) | lo);
                }
                JSObject result = new JSObject();
                result.set("read", (double) (pairCount * 2));
                result.set("written", (double) pairCount);
                return result;
            }));
        }

        kindConstructors.put(kind, ctor);
        globals.putIfAbsent(kind.name, ctor);
    }

    /** ECMA-262 §23.2.4.1 — TypedArray ctor allocation (the 5 overloads). */
    private static void allocateTypedArray(JSObject self, TypedArrayKind kind, Object[] a, InterpContext c) {
        Object firstArg = a.length == 0 ? Undefined.VALUE : a[0];
        if (a.length == 0 || firstArg == Undefined.VALUE) {
            // Zero-arg → empty typed array.
            allocateZero(self, kind, 0);
            return;
        }
        if (firstArg instanceof Number || firstArg instanceof Boolean
                || firstArg instanceof CharSequence || firstArg == null) {
            // Length-based allocation.
            long len = toIndex(firstArg);
            if (len > Integer.MAX_VALUE / Math.max(1, kind.elementSize)) {
                throw AbruptCompletion.rangeError("length too large");
            }
            allocateZero(self, kind, (int) len);
            return;
        }
        if (firstArg instanceof JSObject obj) {
            TypedArrayState src = stateOf(obj);
            if (src != null) {
                // §23.2.4.3 from another TypedArray.
                int len = src.length();
                allocateZero(self, kind, len);
                TypedArrayState dst = stateOf(self);
                for (int i = 0; i < len; i++) storeElement(dst, i, loadElement(src, i));
                return;
            }
            ArrayBufferData buf = bufferDataOf(obj);
            if (buf != null) {
                // §23.2.4.4 buffer-based.
                long byteOffset = a.length > 1 && a[1] != Undefined.VALUE ? toIndex(a[1]) : 0;
                if (byteOffset % kind.elementSize != 0) {
                    throw AbruptCompletion.rangeError("byteOffset must be aligned");
                }
                if (buf.isDetached()) throw AbruptCompletion.typeError("Cannot construct over detached buffer");
                int bufLen = buf.byteLength();
                if (byteOffset > bufLen) throw AbruptCompletion.rangeError("byteOffset > buffer.byteLength");
                int byteLength;
                int arrayLength;
                if (a.length <= 2 || a[2] == Undefined.VALUE) {
                    if (buf.isResizable()) {
                        // length-tracking view
                        byteLength = -1;
                        arrayLength = -1;
                    } else {
                        if ((bufLen - byteOffset) % kind.elementSize != 0) {
                            throw AbruptCompletion.rangeError("buffer length must be aligned");
                        }
                        byteLength = bufLen - (int) byteOffset;
                        arrayLength = byteLength / kind.elementSize;
                    }
                } else {
                    long len = toIndex(a[2]);
                    arrayLength = (int) len;
                    byteLength = arrayLength * kind.elementSize;
                    if (byteOffset + byteLength > bufLen) {
                        throw AbruptCompletion.rangeError("byteOffset + length out of range");
                    }
                }
                self.set(SLOT_TYPED_ARRAY_STATE, new TypedArrayState(kind, obj, (int) byteOffset, byteLength, arrayLength));
                self.setAttributes(SLOT_TYPED_ARRAY_STATE, (byte) 0);
                return;
            }
        }
        if (firstArg instanceof JSArray arr) {
            int len = arr.length();
            allocateZero(self, kind, len);
            TypedArrayState dst = stateOf(self);
            for (int i = 0; i < len; i++) storeElement(dst, i, arr.get(i));
            return;
        }
        if (firstArg instanceof JSObject obj) {
            // Generic iterable / array-like: read length and indices.
            Object iter = AbstractOps.getProperty(obj, Realm.wellKnownIterator.asPropertyKey());
            if (iter instanceof JSFunction itf) {
                // Iterator protocol.
                java.util.List<Object> vals = new java.util.ArrayList<>();
                Object iterator = Interpreter.invokeFunction(itf, obj, new Object[0], c);
                if (iterator instanceof JSObject itObj) {
                    while (true) {
                        Object nextFn = AbstractOps.getProperty(itObj, "next");
                        if (!(nextFn instanceof JSFunction nf)) break;
                        Object step = Interpreter.invokeFunction(nf, itObj, new Object[0], c);
                        Object done = AbstractOps.getProperty(step, "done");
                        if (AbstractOps.toBoolean(done)) break;
                        vals.add(AbstractOps.getProperty(step, "value"));
                    }
                }
                allocateZero(self, kind, vals.size());
                TypedArrayState dst = stateOf(self);
                for (int i = 0; i < vals.size(); i++) storeElement(dst, i, vals.get(i));
                return;
            }
            Object lenVal = AbstractOps.getProperty(obj, "length");
            int len = (int) AbstractOps.toInt32(lenVal);
            allocateZero(self, kind, Math.max(0, len));
            TypedArrayState dst = stateOf(self);
            for (int i = 0; i < len; i++) {
                Object v = AbstractOps.getProperty(obj, Integer.toString(i));
                storeElement(dst, i, v);
            }
            return;
        }
        // Fallback — empty.
        allocateZero(self, kind, 0);
    }

    private static void allocateZero(JSObject self, TypedArrayKind kind, int length) {
        int bytes = length * kind.elementSize;
        JSObject buf = new JSObject(arrayBufferPrototype);
        buf.set(SLOT_ARRAY_BUFFER_DATA, new ArrayBufferData(bytes, false, -1));
        buf.setAttributes(SLOT_ARRAY_BUFFER_DATA, (byte) 0);
        self.set(SLOT_TYPED_ARRAY_STATE, new TypedArrayState(kind, buf, 0, bytes, length));
        self.setAttributes(SLOT_TYPED_ARRAY_STATE, (byte) 0);
    }

    private static Object fromIterableOrArrayLike(TypedArrayKind kind, Object src, JSFunction mapFn, Object thisArg, InterpContext c) {
        java.util.List<Object> vals = new java.util.ArrayList<>();
        if (src instanceof JSObject obj) {
            Object iter = AbstractOps.getProperty(obj, Realm.wellKnownIterator.asPropertyKey());
            if (iter instanceof JSFunction itf) {
                Object iterator = Interpreter.invokeFunction(itf, obj, new Object[0], c);
                if (iterator instanceof JSObject itObj) {
                    while (true) {
                        Object nextFn = AbstractOps.getProperty(itObj, "next");
                        if (!(nextFn instanceof JSFunction nf)) break;
                        Object step = Interpreter.invokeFunction(nf, itObj, new Object[0], c);
                        Object done = AbstractOps.getProperty(step, "done");
                        if (AbstractOps.toBoolean(done)) break;
                        vals.add(AbstractOps.getProperty(step, "value"));
                    }
                }
            } else {
                Object lenVal = AbstractOps.getProperty(obj, "length");
                int len = (int) AbstractOps.toInt32(lenVal);
                for (int i = 0; i < len; i++) vals.add(AbstractOps.getProperty(obj, Integer.toString(i)));
            }
        } else if (src instanceof JSArray arr) {
            for (Object v : arr.elements()) vals.add(v);
        }
        JSObject out = createTypedArrayFromKind(kind, vals.size());
        TypedArrayState os = stateOf(out);
        for (int i = 0; i < vals.size(); i++) {
            Object v = vals.get(i);
            if (mapFn != null) v = Interpreter.invokeFunction(mapFn, thisArg, new Object[]{v, (double) i}, c);
            storeElement(os, i, v);
        }
        return out;
    }

    // ---------------------------------------------------------------
    // Misc helpers.
    // ---------------------------------------------------------------

    /** Iterators for keys/values/entries. */
    private static JSObject makeArrayIterator(Object backing, String kind) {
        JSObject it = new JSObject(Realm.objectPrototype);
        int[] idx = {0};
        it.set("next", nativeFn("next", 0, (t, a, c) -> {
            JSObject step = new JSObject(Realm.objectPrototype);
            TypedArrayState s = stateOf(backing);
            if (s == null) {
                step.set("value", Undefined.VALUE); step.set("done", true); return step;
            }
            int len = s.length();
            int i = idx[0];
            if (i >= len) {
                step.set("value", Undefined.VALUE); step.set("done", true); return step;
            }
            idx[0] = i + 1;
            Object value;
            switch (kind) {
                case "keys":    value = (double) i; break;
                case "values":  value = loadElement(s, i); break;
                case "entries": {
                    JSArray pair = new JSArray();
                    pair.push((double) i);
                    pair.push(loadElement(s, i));
                    value = pair;
                    break;
                }
                default: value = Undefined.VALUE;
            }
            step.set("value", value); step.set("done", false); return step;
        }));
        // Iterator is its own iterator.
        it.set(Realm.wellKnownIterator.asPropertyKey(), nativeFn("[Symbol.iterator]", 0, (t, a, c) -> t));
        return it;
    }

    private static int clampToIndex(double n, int len) {
        if (Double.isNaN(n)) return 0;
        if (n < 0) return (int) Math.max(0, len + n);
        if (n > len) return len;
        return (int) n;
    }

    private static int sliceIdx(Object v, int len, int dflt) {
        if (v == Undefined.VALUE) return dflt;
        double n = toIntegerOrInfinity(v);
        if (n < 0) return (int) Math.max(0, len + n);
        return (int) Math.min(len, n);
    }

    private static JSFunction asFn(Object v, String method) {
        if (v instanceof JSFunction fn) return fn;
        throw AbruptCompletion.typeError(method + " callback must be a function");
    }

    /** Map a constructor-like receiver back to its TypedArrayKind. Returns
     *  {@code null} for non-constructor / non-TypedArray receivers (causes
     *  the caller to throw TypeError). */
    private static TypedArrayKind kindOfConstructor(Object t) {
        if (!(t instanceof JSFunction fn)) return null;
        for (var e : kindConstructors.entrySet()) {
            if (e.getValue() == fn) return e.getKey();
        }
        return null;
    }

    private static void installSpecies(JSFunction ctor) {
        installSpeciesPublic(ctor);
    }

    /** Public helper for installing the default {@code @@species}
     *  accessor on a constructor — used by Realm.java for Array, Map,
     *  Set, Promise, RegExp. */
    public static void installSpeciesPublic(JSFunction ctor) {
        JSFunction speciesGetter = nativeFn("get [Symbol.species]", 0, (t, a, c) -> t);
        ctor.properties().put(Realm.wellKnownSpecies.asPropertyKey(),
            new Accessor(speciesGetter, null));
        ctor.setAttributes(Realm.wellKnownSpecies.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
    }

    private static void defineGetter(JSObject target, String key, String getterName, NativeBody body) {
        JSFunction getter = nativeFn(getterName, 0, body);
        target.set(key, new Accessor(getter, null));
        target.setAttributes(key, JSObject.ATTR_CONFIGURABLE);
    }

    private static void setToStringTag(JSObject target, String tag) {
        if (tag == null) return;
        target.set(Realm.wellKnownToStringTag.asPropertyKey(), tag);
        target.setAttributes(Realm.wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
    }

    /** ECMA-262 § 7.2.10 SameValueZero. */
    private static boolean sameValueZero(Object x, Object y) {
        if (x == y) return true;
        if (x instanceof Number nx && y instanceof Number ny) {
            double dx = nx.doubleValue(), dy = ny.doubleValue();
            if (Double.isNaN(dx) && Double.isNaN(dy)) return true;
            return dx == dy;
        }
        if (x instanceof CharSequence && y instanceof CharSequence) return x.toString().equals(y.toString());
        return false;
    }
}
