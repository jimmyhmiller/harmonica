package com.jimmyhmiller.harmonica.bytecode;

/**
 * Backing data for an ArrayBuffer (ECMA-262 § 25.1) or SharedArrayBuffer
 * (§ 25.2). Stored as the value of the {@code ##ArrayBufferData##} internal
 * slot on the buffer's JSObject — pulled out via {@link TypedArrays#bufferDataOf}.
 *
 * <p>Detachment is represented by {@code data == null}. After detachment all
 * reads/writes through TypedArrays/DataViews silently return undefined or
 * throw TypeError per the spec algorithm (see § 23.2.5.10
 * IntegerIndexedElementGet step 2 — returns undefined; § 25.3.1.5
 * DataView.prototype.getX — throws).
 */
public final class ArrayBufferData {
    /** The underlying bytes. {@code null} iff the buffer has been detached. */
    public byte[] data;

    /** ECMA-262 § 25.2 SharedArrayBuffer — never auto-detached. */
    public final boolean shared;

    /** {@code -1} when non-resizable; otherwise the upper bound on byteLength. */
    public final int maxByteLength;

    public ArrayBufferData(int byteLength, boolean shared, int maxByteLength) {
        if (byteLength < 0) throw new IllegalArgumentException("byteLength < 0");
        if (maxByteLength >= 0 && byteLength > maxByteLength) {
            throw new IllegalArgumentException("byteLength > maxByteLength");
        }
        this.data = new byte[byteLength];
        this.shared = shared;
        this.maxByteLength = maxByteLength;
    }

    /** Wrap an existing byte[] without copying (used by transfer / static helpers). */
    public ArrayBufferData(byte[] data, boolean shared, int maxByteLength) {
        this.data = data;
        this.shared = shared;
        this.maxByteLength = maxByteLength;
    }

    public boolean isDetached()  { return data == null; }
    public boolean isResizable() { return maxByteLength >= 0; }
    public int     byteLength()  { return data == null ? 0 : data.length; }

    public void detach() { this.data = null; }

    /** ECMA-262 § 25.1.5.3 ArrayBufferCopyAndDetach helper — see TypedArrays.transfer. */
    public byte[] takeData() {
        byte[] out = data;
        data = null;
        return out;
    }

    /** ECMA-262 § 25.1.3.4 ArrayBuffer.prototype.resize. */
    public void resize(int newByteLength) {
        if (data == null) throw new IllegalStateException("buffer detached");
        if (!isResizable()) throw new IllegalStateException("buffer not resizable");
        if (newByteLength < 0 || newByteLength > maxByteLength) {
            throw new IllegalArgumentException("newByteLength out of range");
        }
        if (newByteLength == data.length) return;
        byte[] next = new byte[newByteLength];
        System.arraycopy(data, 0, next, 0, Math.min(data.length, newByteLength));
        data = next;
    }
}
