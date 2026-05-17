package com.jimmyhmiller.harmonica.bytecode;

import static com.jimmyhmiller.harmonica.bytecode.Operation.Flags.HAS_IC;
import static com.jimmyhmiller.harmonica.bytecode.Operation.Flags.HAS_SIDE_EFFECT;
import static com.jimmyhmiller.harmonica.bytecode.Operation.Flags.NO_THROW;
import static com.jimmyhmiller.harmonica.bytecode.Operation.Flags.TERMINATOR;

/**
 * The opcode tag. Every concrete {@link Op} reports one. The interpreter's
 * hot loop switches on this enum's ordinal — HotSpot lowers a dense enum
 * switch to a JVM {@code tableswitch} (direct jump table).
 *
 * <p>Each entry carries a coarse {@link OpClass} bucket and a flag bitmask:
 * {@link Flags#TERMINATOR}, {@link Flags#NO_THROW}, {@link Flags#HAS_IC},
 * {@link Flags#HAS_SIDE_EFFECT}.
 *
 * <p>This enum lists the full opcode set we plan to support. Many are not
 * yet implemented as concrete records — see {@link Op}. Adding a new opcode
 * means adding an entry here and a record in {@link Op}.
 */
public enum Operation {
    // ---- Arithmetic ----
    ADD               (OpClass.ALU,         0),
    SUB               (OpClass.ALU,         0),
    MUL               (OpClass.ALU,         0),
    DIV               (OpClass.ALU,         0),
    MOD               (OpClass.ALU,         0),
    EXP               (OpClass.ALU,         0),
    BITWISE_AND       (OpClass.ALU,         0),
    BITWISE_OR        (OpClass.ALU,         0),
    BITWISE_XOR       (OpClass.ALU,         0),
    LEFT_SHIFT        (OpClass.ALU,         0),
    RIGHT_SHIFT       (OpClass.ALU,         0),
    UNSIGNED_RIGHT_SHIFT(OpClass.ALU,       0),
    BITWISE_NOT       (OpClass.ALU,         0),
    UNARY_PLUS        (OpClass.ALU,         0),
    UNARY_MINUS       (OpClass.ALU,         0),
    NOT               (OpClass.ALU,         NO_THROW),
    INCREMENT         (OpClass.ALU,         0),
    DECREMENT         (OpClass.ALU,         0),
    POSTFIX_INCREMENT (OpClass.ALU,         0),
    POSTFIX_DECREMENT (OpClass.ALU,         0),

    // ---- Comparison ----
    LESS_THAN             (OpClass.ALU,     0),
    LESS_THAN_EQUALS      (OpClass.ALU,     0),
    GREATER_THAN          (OpClass.ALU,     0),
    GREATER_THAN_EQUALS   (OpClass.ALU,     0),
    LOOSELY_EQUALS        (OpClass.ALU,     0),
    LOOSELY_INEQUALS      (OpClass.ALU,     0),
    STRICTLY_EQUALS       (OpClass.ALU,     0),
    STRICTLY_INEQUALS     (OpClass.ALU,     0),
    IN                    (OpClass.ALU,     0),
    INSTANCE_OF           (OpClass.ALU,     0),

    // ---- Type conversion / predicates ----
    TO_BOOLEAN        (OpClass.TYPE_CONV,   NO_THROW),
    TO_INT32          (OpClass.TYPE_CONV,   0),
    TO_LENGTH         (OpClass.TYPE_CONV,   0),
    TO_OBJECT         (OpClass.TYPE_CONV,   0),
    TO_STRING         (OpClass.TYPE_CONV,   0),
    TO_PRIMITIVE_WITH_STRING_HINT (OpClass.TYPE_CONV, 0),
    TYPEOF            (OpClass.TYPE_CONV,   NO_THROW),
    TYPEOF_BINDING    (OpClass.TYPE_CONV,   HAS_IC),
    IS_CALLABLE       (OpClass.TYPE_CONV,   NO_THROW),
    IS_CONSTRUCTOR    (OpClass.TYPE_CONV,   NO_THROW),

    // ---- Move ----
    MOV               (OpClass.BOOKKEEPING, NO_THROW),
    MOV2              (OpClass.BOOKKEEPING, NO_THROW),
    MOV3              (OpClass.BOOKKEEPING, NO_THROW),

    // ---- Property access (get) ----
    GET_BY_ID                  (OpClass.PROPERTY, HAS_IC),
    GET_BY_ID_WITH_THIS        (OpClass.PROPERTY, HAS_IC),
    GET_BY_VALUE               (OpClass.PROPERTY, 0),
    GET_BY_VALUE_WITH_THIS     (OpClass.PROPERTY, 0),
    GET_LENGTH                 (OpClass.PROPERTY, HAS_IC),
    GET_LENGTH_WITH_THIS       (OpClass.PROPERTY, HAS_IC),
    GET_METHOD                 (OpClass.PROPERTY, 0),
    GET_GLOBAL                 (OpClass.PROPERTY, HAS_IC),
    GET_PRIVATE_BY_ID          (OpClass.PROPERTY, 0),
    GET_TEMPLATE_OBJECT        (OpClass.PROPERTY, NO_THROW | HAS_IC),
    GET_CALLEE_AND_THIS_FROM_ENVIRONMENT (OpClass.PROPERTY, HAS_IC),
    GET_BINDING                (OpClass.BINDING,  HAS_IC),
    GET_INITIALIZED_BINDING    (OpClass.BINDING,  HAS_IC),
    GET_COMPLETION_FIELDS      (OpClass.OTHER,    NO_THROW),
    GET_ITERATOR               (OpClass.ITERATOR, 0),
    GET_OBJECT_PROPERTY_ITERATOR (OpClass.ITERATOR, HAS_IC),
    GET_IMPORT_META            (OpClass.OTHER,    NO_THROW),
    GET_LEXICAL_ENVIRONMENT    (OpClass.BINDING,  NO_THROW),
    GET_NEW_TARGET             (OpClass.OTHER,    NO_THROW),
    GET_SUPER_CONSTRUCTOR      (OpClass.OTHER,    0),
    HAS_PRIVATE_ID             (OpClass.PROPERTY, 0),
    RESOLVE_SUPER_BASE         (OpClass.PROPERTY, 0),
    RESOLVE_THIS_BINDING       (OpClass.BINDING,  0),

    // ---- Property access (put / delete) ----
    PUT_BY_ID                  (OpClass.PROPERTY, HAS_IC),
    PUT_BY_ID_WITH_THIS        (OpClass.PROPERTY, HAS_IC),
    PUT_BY_VALUE               (OpClass.PROPERTY, 0),
    PUT_BY_VALUE_WITH_THIS     (OpClass.PROPERTY, 0),
    PUT_BY_SPREAD              (OpClass.PROPERTY, 0),
    PUT_PRIVATE_BY_ID          (OpClass.PROPERTY, 0),
    SET_GLOBAL                 (OpClass.PROPERTY, HAS_IC),
    DELETE_BY_ID               (OpClass.PROPERTY, 0),
    DELETE_BY_VALUE            (OpClass.PROPERTY, 0),
    DELETE_VARIABLE            (OpClass.BINDING,  0),

    // ---- Bindings / environments ----
    CREATE_LEXICAL_ENVIRONMENT (OpClass.BINDING,  NO_THROW),
    CREATE_VARIABLE_ENVIRONMENT(OpClass.BINDING,  NO_THROW),
    CREATE_PRIVATE_ENVIRONMENT (OpClass.BINDING,  NO_THROW),
    LEAVE_PRIVATE_ENVIRONMENT  (OpClass.BINDING,  NO_THROW),
    ENTER_OBJECT_ENVIRONMENT   (OpClass.BINDING,  0),
    SET_LEXICAL_ENVIRONMENT    (OpClass.BINDING,  NO_THROW),
    CREATE_IMMUTABLE_BINDING   (OpClass.BINDING,  0),
    CREATE_MUTABLE_BINDING     (OpClass.BINDING,  0),
    CREATE_VARIABLE            (OpClass.BINDING,  0),
    INITIALIZE_LEXICAL_BINDING (OpClass.BINDING,  HAS_IC),
    INITIALIZE_VARIABLE_BINDING(OpClass.BINDING,  HAS_IC),
    SET_LEXICAL_BINDING        (OpClass.BINDING,  HAS_IC),
    SET_VARIABLE_BINDING       (OpClass.BINDING,  HAS_IC),
    CREATE_DATA_PROPERTY_OR_THROW (OpClass.PROPERTY, 0),

    // ---- Object / array / class / function creation ----
    NEW_OBJECT                  (OpClass.CONSTRUCT, NO_THROW | HAS_IC),
    NEW_OBJECT_WITH_NO_PROTOTYPE(OpClass.CONSTRUCT, NO_THROW),
    MAKE_SHAPED_OBJECT          (OpClass.CONSTRUCT, NO_THROW | HAS_IC),
    SET_PROTO_OR_NOP            (OpClass.PROPERTY,  NO_THROW),
    NEW_ARRAY                   (OpClass.CONSTRUCT, NO_THROW),
    NEW_ARRAY_WITH_LENGTH       (OpClass.CONSTRUCT, 0),
    NEW_PRIMITIVE_ARRAY         (OpClass.CONSTRUCT, NO_THROW),
    NEW_CLASS                   (OpClass.CONSTRUCT, 0),
    SET_FUNCTION_PROTOTYPE      (OpClass.CONSTRUCT, NO_THROW),
    NEW_FUNCTION                (OpClass.CONSTRUCT, NO_THROW),
    NEW_REGEXP                  (OpClass.CONSTRUCT, NO_THROW),
    NEW_TYPE_ERROR              (OpClass.CONSTRUCT, NO_THROW),
    NEW_REFERENCE_ERROR         (OpClass.CONSTRUCT, NO_THROW),
    ARRAY_APPEND                (OpClass.CONSTRUCT, 0),
    CONCAT_STRING               (OpClass.CONSTRUCT, 0),
    COPY_OBJECT_EXCLUDING_PROPERTIES (OpClass.CONSTRUCT, 0),
    CREATE_ARGUMENTS            (OpClass.CONSTRUCT, NO_THROW),
    CREATE_REST_PARAMS          (OpClass.CONSTRUCT, NO_THROW),
    CREATE_ASYNC_FROM_SYNC_ITERATOR (OpClass.CONSTRUCT, NO_THROW),
    INIT_OBJECT_LITERAL_PROPERTY(OpClass.CONSTRUCT, NO_THROW | HAS_IC),
    CACHE_OBJECT_SHAPE          (OpClass.CONSTRUCT, NO_THROW | HAS_IC),
    DEFINE_ACCESSOR             (OpClass.CONSTRUCT, NO_THROW),

    // ---- Calls ----
    CALL                                (OpClass.CALL, HAS_IC),
    CALL_WITH_ARGUMENT_ARRAY            (OpClass.CALL, HAS_IC),
    CALL_CONSTRUCT                      (OpClass.CALL, HAS_IC),
    CALL_CONSTRUCT_WITH_ARGUMENT_ARRAY  (OpClass.CALL, HAS_IC),
    CALL_DIRECT_EVAL                    (OpClass.CALL, HAS_IC),
    CALL_DIRECT_EVAL_WITH_ARGUMENT_ARRAY(OpClass.CALL, HAS_IC),
    SUPER_CALL_WITH_ARGUMENT_ARRAY      (OpClass.CALL, 0),
    IMPORT_CALL                         (OpClass.CALL, 0),
    // Builtin fast-path calls — declared but optional in v1.
    CALL_BUILTIN_MATH_ABS               (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_LOG               (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_POW               (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_EXP               (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_CEIL              (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_FLOOR             (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_IMUL              (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_RANDOM            (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_ROUND             (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_SQRT              (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_SIN               (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_COS               (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MATH_TAN               (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_REGEXP_PROTOTYPE_EXEC  (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_REGEXP_PROTOTYPE_REPLACE(OpClass.CALL, HAS_IC),
    CALL_BUILTIN_REGEXP_PROTOTYPE_SPLIT (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_ORDINARY_HAS_INSTANCE  (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_ARRAY_ITERATOR_PROTOTYPE_NEXT (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_MAP_ITERATOR_PROTOTYPE_NEXT   (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_SET_ITERATOR_PROTOTYPE_NEXT   (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_STRING_ITERATOR_PROTOTYPE_NEXT(OpClass.CALL, HAS_IC),
    CALL_BUILTIN_STRING_FROM_CHAR_CODE         (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_STRING_PROTOTYPE_CHAR_CODE_AT (OpClass.CALL, HAS_IC),
    CALL_BUILTIN_STRING_PROTOTYPE_CHAR_AT      (OpClass.CALL, HAS_IC),

    // ---- Iteration ----
    ITERATOR_NEXT             (OpClass.ITERATOR, 0),
    ITERATOR_NEXT_UNPACK      (OpClass.ITERATOR, 0),
    ITERATOR_CLOSE            (OpClass.ITERATOR, 0),
    ITERATOR_TO_ARRAY         (OpClass.ITERATOR, 0),
    MATERIALIZE_ITERABLE      (OpClass.ITERATOR, 0),
    OBJECT_PROPERTY_ITERATOR_NEXT (OpClass.ITERATOR, 0),

    // ---- Jumps ----
    JUMP                      (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_IF                   (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_TRUE                 (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_FALSE                (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_NULLISH              (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_UNDEFINED            (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_STRICTLY_EQUALS      (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_STRICTLY_INEQUALS    (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_LOOSELY_EQUALS       (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_LOOSELY_INEQUALS     (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_LESS_THAN            (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_LESS_THAN_EQUALS     (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_GREATER_THAN         (OpClass.BRANCH, TERMINATOR | NO_THROW),
    JUMP_GREATER_THAN_EQUALS  (OpClass.BRANCH, TERMINATOR | NO_THROW),

    // ---- Return / end / throw / catch ----
    RETURN                    (OpClass.BRANCH, TERMINATOR | NO_THROW),
    END                       (OpClass.BRANCH, TERMINATOR | NO_THROW),
    THROW                     (OpClass.BRANCH, TERMINATOR),
    CATCH                     (OpClass.OTHER,  NO_THROW),
    THROW_IF_NOT_OBJECT       (OpClass.OTHER,  0),
    THROW_IF_NULLISH          (OpClass.OTHER,  0),
    THROW_IF_TDZ              (OpClass.OTHER,  0),
    THROW_CONST_ASSIGNMENT    (OpClass.OTHER,  0),
    SET_COMPLETION_TYPE       (OpClass.OTHER,  NO_THROW),

    // ---- Async / generators ----
    AWAIT                     (OpClass.BRANCH, TERMINATOR | NO_THROW),
    YIELD                     (OpClass.BRANCH, TERMINATOR | NO_THROW),

    // ---- Private fields ----
    ADD_PRIVATE_NAME          (OpClass.BINDING, NO_THROW),

    // ---- with statement ----
    PUSH_WITH_ENV             (OpClass.BINDING, NO_THROW),
    POP_WITH_ENV              (OpClass.BINDING, NO_THROW);

    // -------------- Flag bits --------------
    // Held in a nested class so they're available in enum-constant initializers
    // (enum constants run before sibling static-field initializers within the
    // same class, but a separate nested class is initialized lazily on first
    // reference, which happens before the enum constants need them).

    public static final class Flags {
        public static final int TERMINATOR      = 1 << 0;
        public static final int NO_THROW        = 1 << 1;
        public static final int HAS_IC          = 1 << 2;
        public static final int HAS_SIDE_EFFECT = 1 << 3;

        private Flags() {}
    }

    private final OpClass opClass;
    private final int flags;

    Operation(OpClass opClass, int flags) {
        this.opClass = opClass;
        this.flags = flags;
    }

    public OpClass opClass()       { return opClass; }
    public int flags()             { return flags; }
    public boolean isTerminator()  { return (flags & TERMINATOR) != 0; }
    public boolean canThrow()      { return (flags & NO_THROW) == 0; }
    public boolean hasIC()         { return (flags & HAS_IC) != 0; }
    public boolean hasSideEffect() { return (flags & HAS_SIDE_EFFECT) != 0; }
}
