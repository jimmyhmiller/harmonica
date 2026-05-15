package com.jimmyhmiller.harmonica.bytecode;

/**
 * The eleven ECMA-262 § 23.2 TypedArray element kinds. Encodes element size,
 * signedness, FP-ness, and BigInt-ness so the integer-indexed [[Get]] / [[Set]]
 * paths can switch once on this enum instead of doing nested type tests.
 *
 * <p>Per § 23.2 Table — name and element-byte-size are normative.
 */
public enum TypedArrayKind {
    INT8    ("Int8Array",         1, false, false, false, true),
    UINT8   ("Uint8Array",        1, false, false, false, false),
    UINT8C  ("Uint8ClampedArray", 1, false, false, true,  false),
    INT16   ("Int16Array",        2, false, false, false, true),
    UINT16  ("Uint16Array",       2, false, false, false, false),
    INT32   ("Int32Array",        4, false, false, false, true),
    UINT32  ("Uint32Array",       4, false, false, false, false),
    FLOAT32 ("Float32Array",      4, true,  false, false, false),
    FLOAT64 ("Float64Array",      8, true,  false, false, false),
    BIGINT64("BigInt64Array",     8, false, true,  false, true),
    BIGUINT64("BigUint64Array",   8, false, true,  false, false);

    public final String name;
    /** Bytes per element (1, 2, 4, or 8). */
    public final int elementSize;
    /** True for Float32Array / Float64Array. */
    public final boolean fp;
    /** True for BigInt64Array / BigUint64Array. */
    public final boolean bigInt;
    /** True for Uint8ClampedArray only. */
    public final boolean clamped;
    /** True for Int8/16/32/BigInt64. */
    public final boolean signed;

    TypedArrayKind(String name, int elementSize, boolean fp, boolean bigInt,
                   boolean clamped, boolean signed) {
        this.name = name;
        this.elementSize = elementSize;
        this.fp = fp;
        this.bigInt = bigInt;
        this.clamped = clamped;
        this.signed = signed;
    }
}
