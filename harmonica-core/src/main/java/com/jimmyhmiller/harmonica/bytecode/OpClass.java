package com.jimmyhmiller.harmonica.bytecode;

/**
 * Coarse partition of opcodes into dispatch buckets. Inspired by JRuby's
 * OpClass: lets the interpreter route to category-specific handlers before
 * the per-opcode switch, and lets analyses skip irrelevant categories cheaply.
 *
 * <p>Not currently used for two-level dispatch in v1 (we just switch on
 * {@link Operation} directly); kept as metadata for analyses and as a hook
 * for future perf tuning.
 */
public enum OpClass {
    /** Arithmetic, bitwise, comparison, unary numeric. */
    ALU,
    /** Jumps, return, throw, await, yield, end. */
    BRANCH,
    /** Function call / construct / super / eval. */
    CALL,
    /** Property get/put/delete. */
    PROPERTY,
    /** Binding/environment manipulation. */
    BINDING,
    /** Object/array/regex/error/closure construction. */
    CONSTRUCT,
    /** Iterator protocol opcodes. */
    ITERATOR,
    /** Type conversion + predicates (ToBoolean/ToString/Typeof/IsCallable/...). */
    TYPE_CONV,
    /** Mov, exception edges, source-map markers. */
    BOOKKEEPING,
    /** Catch-all. */
    OTHER,
}
