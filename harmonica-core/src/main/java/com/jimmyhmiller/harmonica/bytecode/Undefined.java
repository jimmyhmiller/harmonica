package com.jimmyhmiller.harmonica.bytecode;

/**
 * Singleton for the JavaScript {@code undefined} value.
 *
 * <p>Uses identity equality. There is exactly one instance, {@link #VALUE}.
 *
 * <p>This is a placeholder until a proper Value type / NaN-boxing is in place.
 * For now we represent JS values as Java {@code Object} and use this singleton
 * for {@code undefined}, {@code null} for JS null, {@link Boolean#TRUE}/
 * {@link Boolean#FALSE} for booleans, etc.
 */
public final class Undefined {
    public static final Undefined VALUE = new Undefined();

    private Undefined() {}

    @Override
    public String toString() {
        return "undefined";
    }
}
