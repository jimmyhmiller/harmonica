package com.jimmyhmiller.harmonica.bytecode;

/**
 * View state for a TypedArray (ECMA-262 § 23.2). Stored as the
 * {@code ##TypedArrayState##} internal slot on the view's JSObject.
 *
 * <p>The {@link #buffer} reference points at the wrapping ArrayBuffer JSObject
 * (not the raw {@link ArrayBufferData}), so that detachment performed on the
 * buffer object is observable here — § 23.2.5.10 IntegerIndexedElementGet
 * reads the buffer's [[ArrayBufferData]] every call.
 */
public final class TypedArrayState {
    public final TypedArrayKind kind;
    /** The wrapping ArrayBuffer JSObject. */
    public final JSObject buffer;
    public final int byteOffset;
    /**
     * Spec [[ByteLength]]. {@code -1} marks an auto / length-tracking view
     * (resizable buffer + no explicit length supplied to the ctor). § 23.2.4.5
     * TypedArrayCreate uses this to choose between fixed-length and
     * length-tracking semantics.
     */
    public final int byteLengthOrAuto;
    /**
     * Spec [[ArrayLength]] cached for fixed-length views. {@code -1} for
     * length-tracking views (recomputed each access via {@link #length()}).
     */
    public final int arrayLengthOrAuto;

    public TypedArrayState(TypedArrayKind kind, JSObject buffer,
                           int byteOffset, int byteLengthOrAuto, int arrayLengthOrAuto) {
        this.kind = kind;
        this.buffer = buffer;
        this.byteOffset = byteOffset;
        this.byteLengthOrAuto = byteLengthOrAuto;
        this.arrayLengthOrAuto = arrayLengthOrAuto;
    }

    /** {@code true} for views created over a resizable buffer with no length arg. */
    public boolean isLengthTracking() { return arrayLengthOrAuto < 0; }

    /** Pull the buffer's [[ArrayBufferData]] slot, or {@code null} if detached. */
    public ArrayBufferData rawBuffer() {
        return TypedArrays.bufferDataOf(buffer);
    }

    /**
     * ECMA-262 § 23.2.5.16 TypedArrayLength. Returns the current view length
     * in elements. {@code 0} if buffer is detached or out of bounds.
     */
    public int length() {
        if (!isLengthTracking()) return arrayLengthOrAuto;
        ArrayBufferData buf = rawBuffer();
        if (buf == null || buf.isDetached()) return 0;
        int bufLen = buf.byteLength();
        if (byteOffset > bufLen) return 0;
        return (bufLen - byteOffset) / kind.elementSize;
    }

    /**
     * ECMA-262 § 23.2.5.15 IsTypedArrayOutOfBounds. Length-tracking views go
     * out of bounds only when the buffer is detached or shrinks past
     * byteOffset; fixed-length views go out of bounds if the buffer shrinks
     * past byteOffset + byteLength.
     */
    public boolean outOfBounds() {
        ArrayBufferData buf = rawBuffer();
        if (buf == null || buf.isDetached()) return true;
        int bufLen = buf.byteLength();
        if (byteOffset > bufLen) return true;
        if (!isLengthTracking() && byteOffset + byteLengthOrAuto > bufLen) return true;
        return false;
    }

    /** Current effective byte length (recomputed for length-tracking views). */
    public int currentByteLength() {
        if (!isLengthTracking()) return byteLengthOrAuto;
        ArrayBufferData buf = rawBuffer();
        if (buf == null || buf.isDetached()) return 0;
        int bufLen = buf.byteLength();
        if (byteOffset > bufLen) return 0;
        int extra = (bufLen - byteOffset) % kind.elementSize;
        return (bufLen - byteOffset) - extra;
    }
}
