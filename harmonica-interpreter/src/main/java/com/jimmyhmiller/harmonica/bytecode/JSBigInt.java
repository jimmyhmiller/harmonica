package com.jimmyhmiller.harmonica.bytecode;

import java.math.BigInteger;

/**
 * ECMA-262 § 21.2 BigInt primitive. Wraps a {@link BigInteger}; immutable.
 *
 * <p>Compares structurally by value (so two distinct JSBigInts holding the
 * same integer are {@code ===} per spec § 7.2.16 SameValueNonNumber).
 *
 * <p>Wrapper objects ({@code Object(1n)}) are still JSObjects with this
 * value stashed in a {@code ##BigIntData##} internal slot, not instances of
 * this class — same pattern as Number/String/Boolean wrappers.
 */
public final class JSBigInt {
    public static final JSBigInt ZERO = new JSBigInt(BigInteger.ZERO);
    public static final JSBigInt ONE  = new JSBigInt(BigInteger.ONE);

    public final BigInteger value;

    public JSBigInt(BigInteger value) { this.value = value; }
    public JSBigInt(long value)       { this.value = BigInteger.valueOf(value); }

    @Override
    public boolean equals(Object o) {
        return o instanceof JSBigInt other && value.equals(other.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }

    @Override
    public String toString() { return value.toString(); }
}
