package com.jimmyhmiller.harmonica.bytecode;

/**
 * A Java-side implementation of a built-in function. When a {@link JSFunction}
 * carries a non-null {@code nativeBody}, the interpreter dispatches to this
 * {@code call} method instead of running bytecode.
 *
 * <p>Receiver, args, and the caller's {@link InterpContext} are passed
 * through; throw {@link AbruptCompletion} to surface a JS error.
 */
@FunctionalInterface
public interface NativeBody {
    Object call(Object thisVal, Object[] args, InterpContext ctx);
}
