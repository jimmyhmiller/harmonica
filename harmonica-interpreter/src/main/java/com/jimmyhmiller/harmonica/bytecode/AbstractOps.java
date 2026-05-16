package com.jimmyhmiller.harmonica.bytecode;

import java.util.Map;

/**
 * ECMAScript abstract operations used by the interpreter.
 *
 * <p>This is the thin Java-side intrinsic layer that the interpreter dispatch
 * cases call into. Each method follows the spec algorithm closely (LibJS
 * style: comments quote spec step numbers).
 *
 * <p>v1: only the operations needed by the starter opcodes are implemented.
 * Numeric semantics are deliberately simplified — full BigInt handling,
 * proper string concat, and ToPrimitive will land as we extend the value
 * model.
 */
public final class AbstractOps {

    private AbstractOps() {}

    // -------------------------------------------------------------
    //  Arithmetic
    // -------------------------------------------------------------

    /**
     * ApplyStringOrNumericBinaryOperator for the {@code +} operator —
     * ECMA-262 § 13.15.3.
     *
     * <p>Spec algorithm (step 1, the {@code +} branch):
     * <ol>
     *   <li>{@code lPrim ← ToPrimitive(lVal)} (no hint).
     *   <li>{@code rPrim ← ToPrimitive(rVal)} (no hint).
     *   <li>If {@code lPrim} OR {@code rPrim} is a String → string-concatenate
     *       {@code ToString(lPrim) ‖ ToString(rPrim)}.
     *   <li>Otherwise → numeric: {@code ToNumeric(lPrim) + ToNumeric(rPrim)}.
     * </ol>
     *
     * <p>v1 doesn't model BigInt, so we fold "ToNumeric" into ToNumber.
     */
    public static Object add(Object lhs, Object rhs) {
        // Fast path: numeric + numeric — the dominant case in tight loops.
        // Skip ToPrimitive (which is a no-op for Doubles but still does
        // an instanceof JSObject check) and the String branch.
        if (lhs instanceof Double dl && rhs instanceof Double dr) {
            return boxDouble(dl + dr);
        }
        // Fast path: string-ish + string-ish (covers ConsString chains).
        // Building a cons tree instead of allocating + copying a new flat
        // String per `+=` iteration turns the classic O(n²) accumulation
        // into O(n) with a single flatten at consumption time.
        if (lhs instanceof CharSequence l && rhs instanceof CharSequence r) {
            return ConsString.cons(l, r);
        }
        // BigInt: ECMA-262 § 13.15.3 — BigInt + BigInt allowed, mixing with
        // Number throws TypeError. String coercion takes precedence (BigInt
        // ToString gives the decimal form).
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) {
            return new JSBigInt(lb.value.add(rb.value));
        }
        Object lPrim = toPrimitive(lhs, "default");
        Object rPrim = toPrimitive(rhs, "default");
        if (lPrim instanceof CharSequence || rPrim instanceof CharSequence) {
            return ConsString.cons(toString(lPrim), toString(rPrim));
        }
        if (lPrim instanceof JSBigInt lb && rPrim instanceof JSBigInt rb) {
            return new JSBigInt(lb.value.add(rb.value));
        }
        if (lPrim instanceof JSBigInt || rPrim instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(toNumber(lPrim) + toNumber(rPrim));
    }

    public static Object sub(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return boxDouble(dl - dr);
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) return new JSBigInt(lb.value.subtract(rb.value));
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(toNumber(lhs) - toNumber(rhs));
    }
    public static Object mul(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return boxDouble(dl * dr);
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) return new JSBigInt(lb.value.multiply(rb.value));
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(toNumber(lhs) * toNumber(rhs));
    }
    public static Object div(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return boxDouble(dl / dr);
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) {
            if (rb.value.signum() == 0) throw AbruptCompletion.rangeError("Division by zero");
            return new JSBigInt(lb.value.divide(rb.value));
        }
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(toNumber(lhs) / toNumber(rhs));
    }
    public static Object mod(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return boxDouble(dl % dr);
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) {
            if (rb.value.signum() == 0) throw AbruptCompletion.rangeError("Division by zero");
            return new JSBigInt(lb.value.remainder(rb.value));
        }
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(toNumber(lhs) % toNumber(rhs));
    }
    public static Object exp(Object lhs, Object rhs) {
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) {
            if (rb.value.signum() < 0) throw AbruptCompletion.rangeError("Exponent must be non-negative");
            return new JSBigInt(lb.value.pow(rb.value.intValueExact()));
        }
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(Math.pow(toNumber(lhs), toNumber(rhs)));
    }

    public static Object bitwiseAnd(Object lhs, Object rhs) {
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) return new JSBigInt(lb.value.and(rb.value));
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(toInt32(lhs) & toInt32(rhs));
    }
    public static Object bitwiseOr (Object lhs, Object rhs) {
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) return new JSBigInt(lb.value.or(rb.value));
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(toInt32(lhs) | toInt32(rhs));
    }
    public static Object bitwiseXor(Object lhs, Object rhs) {
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) return new JSBigInt(lb.value.xor(rb.value));
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot mix BigInt and other types, use explicit conversions");
        }
        return boxDouble(toInt32(lhs) ^ toInt32(rhs));
    }

    /** ECMAScript {@code <<} — shift count masked to 5 bits. */
    public static Object leftShift(Object lhs, Object rhs) {
        return boxDouble(toInt32(lhs) << (toInt32(rhs) & 0x1F));
    }
    /** ECMAScript {@code >>} — arithmetic right shift. */
    public static Object rightShift(Object lhs, Object rhs) {
        return boxDouble(toInt32(lhs) >> (toInt32(rhs) & 0x1F));
    }
    /** ECMAScript {@code >>>} — logical/unsigned right shift, result is uint32 promoted to Number. */
    public static Object unsignedRightShift(Object lhs, Object rhs) {
        long u = ((long) toInt32(lhs) & 0xFFFFFFFFL) >>> (toInt32(rhs) & 0x1F);
        return boxDouble(u);
    }

    // -------------------------------------------------------------
    //  Unary
    // -------------------------------------------------------------

    public static Object unaryMinus(Object v) {
        if (v instanceof JSBigInt bi) return new JSBigInt(bi.value.negate());
        return -toNumber(v);
    }
    public static Object unaryPlus (Object v) {
        if (v instanceof JSBigInt) {
            throw AbruptCompletion.typeError("Cannot convert a BigInt to a number");
        }
        return  toNumber(v);
    }
    public static Object bitwiseNot(Object v) {
        if (v instanceof JSBigInt bi) return new JSBigInt(bi.value.not());
        return boxDouble(~toInt32(v));
    }
    public static Object not       (Object v) { return !toBoolean(v); }

    /** ECMA-262 § 13.5.3 typeof operator — Table 38 (typeof Operator Results). */
    public static String typeofValue(Object v) {
        if (v == Undefined.VALUE)    return "undefined";
        if (v == null)               return "object";
        if (v instanceof Boolean)       return "boolean";
        if (v instanceof JSBigInt)      return "bigint";
        if (v instanceof Number)        return "number";
        if (v instanceof CharSequence)  return "string";
        if (v instanceof JSSymbol)      return "symbol";
        if (v instanceof JSFunction) return "function";
        return "object";
    }

    // -------------------------------------------------------------
    //  Comparison
    // -------------------------------------------------------------

    /**
     * ECMA-262 § 7.2.13 IsLessThan. ToPrimitive both sides; if both are
     * strings, compare lexicographically by UTF-16 code unit (Java's
     * String.compareTo); otherwise coerce both to Number and compare.
     */
    /** ECMA-262 § 7.2.16 IsLessThan(x, y, LeftFirst). Returns -1/0/1
     *  (signum) or {@code Integer.MIN_VALUE} for "undefined" (NaN-side
     *  comparisons), so relational ops can map that to false.
     *  {@code leftFirst}: true → coerce x (LHS of relational op) first,
     *  matching spec evaluation order. */
    private static int compareLessThan(Object lhs, Object rhs, boolean leftFirst) {
        Object lp, rp;
        if (leftFirst) {
            lp = lhs instanceof JSObject || lhs instanceof JSArray || lhs instanceof JSFunction
                ? toPrimitive(lhs, "number") : lhs;
            rp = rhs instanceof JSObject || rhs instanceof JSArray || rhs instanceof JSFunction
                ? toPrimitive(rhs, "number") : rhs;
        } else {
            rp = rhs instanceof JSObject || rhs instanceof JSArray || rhs instanceof JSFunction
                ? toPrimitive(rhs, "number") : rhs;
            lp = lhs instanceof JSObject || lhs instanceof JSArray || lhs instanceof JSFunction
                ? toPrimitive(lhs, "number") : lhs;
        }
        if (lp instanceof CharSequence ls && rp instanceof CharSequence rs) {
            return ls.toString().compareTo(rs.toString());
        }
        // BigInt mixed with Number — § 7.2.16 step 6 handles via mathematical value.
        if (lp instanceof JSBigInt lb && rp instanceof JSBigInt rb) {
            return lb.value.compareTo(rb.value);
        }
        if (lp instanceof JSBigInt lb && rp instanceof Number nr) {
            double dr = nr.doubleValue();
            if (Double.isNaN(dr)) return Integer.MIN_VALUE;
            if (dr == Double.POSITIVE_INFINITY) return -1;
            if (dr == Double.NEGATIVE_INFINITY) return 1;
            return lb.value.compareTo(java.math.BigDecimal.valueOf(dr).toBigInteger());
        }
        if (lp instanceof Number nl && rp instanceof JSBigInt rb) {
            double dl = nl.doubleValue();
            if (Double.isNaN(dl)) return Integer.MIN_VALUE;
            if (dl == Double.POSITIVE_INFINITY) return 1;
            if (dl == Double.NEGATIVE_INFINITY) return -1;
            return java.math.BigDecimal.valueOf(dl).toBigInteger().compareTo(rb.value);
        }
        double ln = toNumber(lp), rn = toNumber(rp);
        if (Double.isNaN(ln) || Double.isNaN(rn)) return Integer.MIN_VALUE;
        return Double.compare(ln, rn);
    }

    public static Boolean lessThan         (Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return dl < dr;
        int cmp = compareLessThan(lhs, rhs, /* leftFirst */ true);
        return cmp != Integer.MIN_VALUE && cmp < 0;
    }
    public static Boolean lessThanEquals   (Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return dl <= dr;
        // `a <= b` ↔ !IsLessThan(b, a, false). LeftFirst=false: coerce a (lhs) first.
        int cmp = compareLessThan(rhs, lhs, /* leftFirst */ false);
        return cmp != Integer.MIN_VALUE && cmp > 0 || cmp == 0;
    }
    public static Boolean greaterThan      (Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return dl > dr;
        int cmp = compareLessThan(rhs, lhs, /* leftFirst */ false);
        return cmp != Integer.MIN_VALUE && cmp < 0;
    }
    public static Boolean greaterThanEquals(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return dl >= dr;
        // `a >= b` ↔ !IsLessThan(a, b, true). LeftFirst=true: coerce a (lhs) first.
        int cmp = compareLessThan(lhs, rhs, /* leftFirst */ true);
        return cmp != Integer.MIN_VALUE && cmp > 0 || cmp == 0;
    }
    public static Boolean strictlyInequals(Object lhs, Object rhs) { return !strictlyEquals(lhs, rhs); }

    /** https://tc39.es/ecma262/#sec-islooselyequal */
    public static Boolean looselyEquals(Object lhs, Object rhs) {
        if (lhs == null && rhs == null) return true;
        if (lhs == Undefined.VALUE && rhs == Undefined.VALUE) return true;
        if ((lhs == null && rhs == Undefined.VALUE) || (lhs == Undefined.VALUE && rhs == null)) return true;
        if (lhs == null || rhs == null || lhs == Undefined.VALUE || rhs == Undefined.VALUE) return false;
        if (lhs instanceof Number && rhs instanceof Number) return strictlyEquals(lhs, rhs);
        if (lhs instanceof CharSequence && rhs instanceof CharSequence) return strictlyEquals(lhs.toString(), rhs.toString());
        if (lhs instanceof Number && rhs instanceof CharSequence) return toNumber(lhs) == toNumber(rhs);
        if (lhs instanceof CharSequence && rhs instanceof Number) return toNumber(lhs) == toNumber(rhs);
        if (lhs instanceof Boolean) return looselyEquals(toNumber(lhs), rhs);
        if (rhs instanceof Boolean) return looselyEquals(lhs, toNumber(rhs));
        // ECMA-262 § 7.2.14 IsLooselyEqual steps 9-10: Object on one side,
        // String/Number/BigInt/Symbol on the other — coerce object via
        // ToPrimitive (default hint) and re-compare.
        boolean lIsObj = lhs instanceof JSObject || lhs instanceof JSArray || lhs instanceof JSFunction;
        boolean rIsObj = rhs instanceof JSObject || rhs instanceof JSArray || rhs instanceof JSFunction;
        if (lIsObj && (rhs instanceof Number || rhs instanceof CharSequence || rhs instanceof JSSymbol)) {
            return looselyEquals(toPrimitive(lhs, "default"), rhs);
        }
        if (rIsObj && (lhs instanceof Number || lhs instanceof CharSequence || lhs instanceof JSSymbol)) {
            return looselyEquals(lhs, toPrimitive(rhs, "default"));
        }
        return strictlyEquals(lhs, rhs);
    }

    public static Boolean looselyInequals(Object lhs, Object rhs) { return !looselyEquals(lhs, rhs); }

    /** https://tc39.es/ecma262/#sec-isstrictlyequal */
    public static Boolean strictlyEquals(Object lhs, Object rhs) {
        // Fast identity path. Covers JSObject and JSFunction identity (the
        // canonical case for token-type comparisons like `t === tt.semi` in
        // acorn), interned String literal comparisons, and cached Boolean
        // singletons. Excludes Number boxes because Java `==` on Number is
        // reference equality — two distinct Double objects with value 0
        // should compare strictly equal per ECMA-262 § 7.2.16, and NaN
        // identity must NOT compare true (NaN !== NaN); both fall through
        // to the IEEE-754 branch below.
        if (lhs == rhs && !(lhs instanceof Number)) return Boolean.TRUE;
        if (lhs == null && rhs == null) return true;
        if (lhs == Undefined.VALUE && rhs == Undefined.VALUE) return true;
        if (lhs == null || rhs == null) return false;
        if (lhs == Undefined.VALUE || rhs == Undefined.VALUE) return false;
        // For Numbers, follow IEEE 754: NaN !== NaN, +0 === -0.
        if (lhs instanceof Double dl && rhs instanceof Double dr) {
            return (double) dl == (double) dr;
        }
        if (lhs instanceof Number nl && rhs instanceof Number nr) {
            return nl.doubleValue() == nr.doubleValue();
        }
        // BigInt — structural equality by value. BigInt !== Number per spec.
        if (lhs instanceof JSBigInt lb && rhs instanceof JSBigInt rb) {
            return lb.value.equals(rb.value);
        }
        if (lhs instanceof JSBigInt || rhs instanceof JSBigInt) return false;
        // ConsString equality is asymmetric under String.equals (a String
        // never reports itself equal to a ConsString), so do the
        // length-then-flat-compare ourselves whenever either side is a
        // non-String CharSequence.
        if ((lhs instanceof CharSequence || rhs instanceof CharSequence)
                && !(lhs instanceof String && rhs instanceof String)) {
            if (!(lhs instanceof CharSequence cl) || !(rhs instanceof CharSequence cr)) {
                return false;
            }
            if (cl.length() != cr.length()) return false;
            return cl.toString().equals(cr.toString());
        }
        return lhs.equals(rhs);
    }

    // -------------------------------------------------------------
    //  Type conversion
    // -------------------------------------------------------------

    /** https://tc39.es/ecma262/#sec-toboolean */
    public static boolean toBoolean(Object v) {
        if (v == null)            return false;
        if (v == Undefined.VALUE) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof JSBigInt bi) return bi.value.signum() != 0;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d != 0.0 && !Double.isNaN(d);
        }
        if (v instanceof String s) return !s.isEmpty();
        return true;   // objects are truthy
    }

    /** https://tc39.es/ecma262/#sec-toint32 — number → signed 32-bit. */
    public static int toInt32(Object v) {
        double d = toNumber(v);
        if (Double.isNaN(d) || Double.isInfinite(d) || d == 0.0) return 0;
        // ECMAScript: round toward zero (truncate), then mod 2^32, then sign.
        long truncated = (long) d;
        return (int) (truncated & 0xFFFFFFFFL);
    }

    /**
     * Cheap check for "could this string parse as a number?" — covers the
     * decimal, hex, octal, binary, sign, and Infinity prefixes. Used to
     * short-circuit the {@link Double#parseDouble} fallback path in
     * {@link #toNumber(Object)} so we don't allocate a full
     * {@link NumberFormatException} just to return NaN. Cheap to be wrong
     * (parseDouble still validates), but on the common
     * "this string is clearly not a number" case it saves the throw.
     */
    private static boolean looksLikeNumber(String t) {
        if (t.isEmpty()) return false;
        char c = t.charAt(0);
        if (c >= '0' && c <= '9') return true;
        if (c == '.' || c == '-' || c == '+') return true;
        if (c == 'I' && t.equals("Infinity")) return true;
        if ((c == '-' || c == '+') && t.length() >= 2) {
            char d = t.charAt(1);
            return (d >= '0' && d <= '9') || d == '.' || (d == 'I' && t.endsWith("Infinity"));
        }
        return false;
    }

    /**
     * Cache of {@link Double} boxes for small integer-valued doubles. JS's
     * Number type is uniformly double, but loops, indices, and counter
     * increments are almost always integer-valued in [0, CACHE_MAX). Without
     * this cache, every {@code i++} / arithmetic-result-store allocates a
     * fresh {@code Double} (Java's autoboxing doesn't pre-cache Double the
     * way it does Integer/Long).
     *
     * <p>Profile (lodash 5K workload): {@code Double.valueOf} was 37% of
     * allocated bytes after pooling InterpContext / args[]; mostly from
     * Increment, getProperty, bitwiseAnd, PostfixDecrement.
     */
    private static final int DOUBLE_CACHE_MAX = 4096;
    private static final Double[] DOUBLE_CACHE;
    static {
        DOUBLE_CACHE = new Double[DOUBLE_CACHE_MAX];
        for (int i = 0; i < DOUBLE_CACHE_MAX; i++) DOUBLE_CACHE[i] = (double) i;
    }

    /**
     * Box a primitive double via the cache when its value is an integer in
     * {@code [0, DOUBLE_CACHE_MAX)}; falls back to {@link Double#valueOf}
     * (which still allocates, since {@code Double} has no built-in cache).
     */
    public static Double boxDouble(double d) {
        int i = (int) d;
        if (i >= 0 && i < DOUBLE_CACHE_MAX && (double) i == d) {
            return DOUBLE_CACHE[i];
        }
        return d;
    }

    /** ECMA-262 § 7.1.13 ToBigInt — coerce a value to a BigInt or throw
     *  TypeError. Numbers / Symbols / null / undefined all reject;
     *  Booleans become 0n / 1n; Strings parse as bigint literals;
     *  Objects go through ToPrimitive(hint=number). */
    public static JSBigInt toBigInt(Object v) {
        Object prim = toPrimitive(v, "number");
        if (prim instanceof JSBigInt bi) return bi;
        if (prim instanceof Boolean b) return new JSBigInt(b ? java.math.BigInteger.ONE : java.math.BigInteger.ZERO);
        if (prim instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.isEmpty()) return new JSBigInt(java.math.BigInteger.ZERO);
            try {
                if (s.length() >= 2 && s.charAt(0) == '0') {
                    char p = s.charAt(1);
                    if (p == 'x' || p == 'X') return new JSBigInt(new java.math.BigInteger(s.substring(2), 16));
                    if (p == 'o' || p == 'O') return new JSBigInt(new java.math.BigInteger(s.substring(2), 8));
                    if (p == 'b' || p == 'B') return new JSBigInt(new java.math.BigInteger(s.substring(2), 2));
                }
                return new JSBigInt(new java.math.BigInteger(s));
            } catch (NumberFormatException nfe) {
                throw AbruptCompletion.syntaxError("Cannot convert " + s + " to BigInt");
            }
        }
        if (prim instanceof Number) {
            throw AbruptCompletion.typeError("Cannot convert a Number to a BigInt");
        }
        if (prim instanceof JSSymbol) {
            throw AbruptCompletion.typeError("Cannot convert a Symbol value to a BigInt");
        }
        if (prim == null || prim == Undefined.VALUE) {
            throw AbruptCompletion.typeError("Cannot convert " + (prim == null ? "null" : "undefined") + " to a BigInt");
        }
        throw AbruptCompletion.typeError("Cannot convert to BigInt");
    }

    /** https://tc39.es/ecma262/#sec-tonumber */
    public static double toNumber(Object v) {
        if (v == null)            return 0.0;
        if (v == Undefined.VALUE) return Double.NaN;
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        if (v instanceof Number n)  return n.doubleValue();
        // ECMA-262 § 7.1.4.2 — ToNumber on a Symbol or BigInt throws TypeError.
        if (v instanceof JSSymbol) throw AbruptCompletion.typeError("Cannot convert a Symbol value to a number");
        if (v instanceof JSBigInt) throw AbruptCompletion.typeError("Cannot convert a BigInt value to a number");
        if (v instanceof CharSequence) {
            String t = v.toString().trim();
            if (t.isEmpty()) return 0.0;   // ECMA spec: "" → 0, "  " → 0
            // ECMA-262 § 7.1.4.1 StringNumericLiteral — accept 0x/0o/0b
            // integer literals (no sign on these per spec). Common in
            // test262 ({@code length: "0x0002"} on array-likes).
            if (t.length() >= 2 && t.charAt(0) == '0') {
                char p = t.charAt(1);
                if (p == 'x' || p == 'X') {
                    try { return (double) Long.parseLong(t.substring(2), 16); }
                    catch (NumberFormatException e) { return Double.NaN; }
                }
                if (p == 'o' || p == 'O') {
                    try { return (double) Long.parseLong(t.substring(2), 8); }
                    catch (NumberFormatException e) { return Double.NaN; }
                }
                if (p == 'b' || p == 'B') {
                    try { return (double) Long.parseLong(t.substring(2), 2); }
                    catch (NumberFormatException e) { return Double.NaN; }
                }
            }
            // Fast pre-check: most strings reaching toNumber on the lodash
            // hot path don't look like numbers. parseDouble's
            // NumberFormatException construction (with stack trace allocation
            // — Throwable's writableStackTrace flag isn't honored here)
            // showed up as 12% of allocated bytes. Reject obvious non-numeric
            // strings without throwing.
            if (!looksLikeNumber(t)) return Double.NaN;
            try { return Double.parseDouble(t); }
            catch (NumberFormatException e) { return Double.NaN; }
        }
        // Object: ToPrimitive(v, "number") then ToNumber on the result.
        return toNumber(toPrimitive(v, "number"));
    }

    /**
     * ToPrimitive — ECMA-262 § 7.1.1.
     *
     * <p>Spec algorithm:
     * <ol>
     *   <li>If input is an Object: try {@code @@toPrimitive} (skipped — no
     *       Symbol support), then {@code OrdinaryToPrimitive(input, hint)}.
     *   <li>Else: return input as-is.
     * </ol>
     *
     * <p>OrdinaryToPrimitive (§ 7.1.1.1): for {@code "string"} hint, try
     * {@code toString} then {@code valueOf}; otherwise try {@code valueOf}
     * then {@code toString}. Each returning a primitive wins.
     *
     * <p>v1 limitations:
     * <ul>
     *   <li>No {@code @@toPrimitive} lookup (no Symbols yet).
     *   <li>Hint {@code "default"} is treated as {@code "number"} (matches
     *       spec for non-Date/Symbol receivers — see § 7.1.1 step 1.b.iii).
     * </ul>
     */
    public static Object toPrimitive(Object v, String hint) {
        if (v == null || v == Undefined.VALUE) return v;
        // BigInt, Symbol, Boolean, Number, String — all already primitive.
        if (v instanceof Boolean || v instanceof Number || v instanceof CharSequence
            || v instanceof JSBigInt || v instanceof JSSymbol) return v;
        InterpContext ctx = InterpContext.current();
        // Step 1: GetMethod(input, %Symbol.toPrimitive%). If non-null, call
        // it with the hint string. Result must be primitive or TypeError.
        if (Realm.wellKnownToPrimitive != null) {
            Object exoticToPrim = getProperty(v, Realm.wellKnownToPrimitive.asPropertyKey());
            if (exoticToPrim instanceof JSFunction fn) {
                Object result = Interpreter.invokeFunction(fn, v, new Object[]{hint},
                    ctx != null ? ctx : new InterpContext(null, new Object[0], 0));
                if (isPrimitive(result)) return result;
                throw AbruptCompletion.typeError(
                    "Symbol.toPrimitive returned non-primitive (hint=" + hint + ")");
            }
        }
        // Step 2: OrdinaryToPrimitive — § 7.1.1.1.
        String[] names = "string".equals(hint)
            ? new String[]{"toString", "valueOf"}
            : new String[]{"valueOf", "toString"};
        for (String name : names) {
            Object method = getProperty(v, name);
            if (method instanceof JSFunction fn) {
                Object result = Interpreter.invokeFunction(fn, v, new Object[0],
                    ctx != null ? ctx : new InterpContext(null, new Object[0], 0));
                if (isPrimitive(result)) return result;
            }
        }
        throw AbruptCompletion.typeError("Cannot convert object to primitive value");
    }

    private static boolean isPrimitive(Object v) {
        return v == null
            || v == Undefined.VALUE
            || v instanceof Boolean
            || v instanceof Number
            || v instanceof CharSequence
            || v instanceof JSSymbol;
    }

    // -------------------------------------------------------------
    //  Property access
    // -------------------------------------------------------------

    /**
     * Best-effort string rendering of a property key for use inside an
     * error message — never calls ToPrimitive, so a user-supplied key
     * with a throwing toString won't escape this method.
     */
    private static String displayKeyForError(Object key) {
        if (key == null) return "null";
        if (key == Undefined.VALUE) return "undefined";
        if (key instanceof String s) return s;
        if (key instanceof JSSymbol sy) return sy.toString();
        if (key instanceof Number || key instanceof Boolean || key instanceof JSBigInt) return key.toString();
        return "<object>";
    }

    /** ECMAScript-ish {@code base[key]} read. Throws on null/undefined receiver. */
    public static Object getProperty(Object base, Object key) {
        if (base == null || base == Undefined.VALUE) {
            // Per § 6.2.4.5 GetValue, ToObject(base) runs before
            // ToPropertyKey(key). If key is an object with a throwing
            // toString/valueOf, calling toString here would propagate that
            // error instead of the TypeError the spec requires. Stringify
            // only primitive-ish keys; show "<object>" otherwise.
            String displayKey = displayKeyForError(key);
            throw AbruptCompletion.typeError("Cannot read properties of " + (base == null ? "null" : "undefined")
                + " (reading '" + displayKey + "')");
        }
        // Hot path: array[i] with a numeric key. Profile showed 26% of all
        // allocation was Long.toString stringifying a Double key here just to
        // round-trip it through parseIndex below. Direct-index when the key
        // is integer-valued and in range — no String allocation, no parse.
        if (base instanceof JSArray arr && key instanceof Number n) {
            double d = n.doubleValue();
            int idx = (int) d;
            if (idx == d && idx >= 0 && idx < arr.length()) return arr.get(idx);
        }
        // ECMA-262 § 28.2.7.4 [[Get]] on a Proxy — invoke handler.get trap if
        // present; otherwise fall through to the target's [[Get]].
        if (base instanceof JSObject pj && Realm.isProxy(pj)) {
            JSFunction trap = Realm.proxyTrap(pj, "get");
            Object target = Realm.proxyTarget(pj);
            if (target == null) {
                throw AbruptCompletion.typeError("Cannot perform 'get' on a proxy that has been revoked");
            }
            String pkey = key instanceof String s ? s
                : key instanceof JSSymbol sym ? sym.asPropertyKey()
                : toString(key);
            if (trap != null) {
                InterpContext ctx = InterpContext.current();
                if (ctx != null) {
                    return Interpreter.invokeFunction(trap, Realm.proxyHandler(pj),
                        new Object[]{target, pkey, pj}, ctx);
                }
            }
            return getProperty(target, pkey);
        }
        // ECMA-262 § 23.2.5.10 IntegerIndexedElementGet — typed-array
        // elements live in the buffer, not the property map. Probe for
        // ##TypedArrayState## and route integer indices through there. Out-
        // of-range / fractional / -0 indices fall through to ordinary [[Get]]
        // (returns undefined per the spec algorithm). String keys like "0"
        // also go through this path (see the String-keyed branch below for
        // CanonicalNumericIndexString handling).
        if (base instanceof JSObject jsoEarly) {
            TypedArrayState taState = TypedArrays.stateOf(jsoEarly);
            if (taState != null) {
                // § 10.4.5.4 [[Get]]: any canonical numeric index string —
                // valid, invalid, or out of bounds — short-circuits the
                // proto-chain walk. Only non-canonical keys reach OrdinaryGet.
                if (key instanceof Number nn) {
                    long idx = TypedArrays.integerIndexFromNumber(nn.doubleValue(), taState.length());
                    if (idx >= 0) return TypedArrays.loadElement(taState, idx);
                    return Undefined.VALUE;
                } else if (key instanceof String sk) {
                    double canonical = TypedArrays.canonicalNumericIndexString(sk);
                    if (!Double.isNaN(canonical)) {
                        long idx = TypedArrays.integerIndexFromNumber(canonical, taState.length());
                        if (idx >= 0) return TypedArrays.loadElement(taState, idx);
                        return Undefined.VALUE;
                    }
                }
            }
        }
        // Symbol keys: use the symbol's per-instance stable string key so
        // distinct symbols (even with the same description) don't collide.
        // Real engines key property maps by symbol identity directly; we
        // approximate via JSSymbol.asPropertyKey(). v1 deviation.
        String prop = key instanceof String s ? s
            : key instanceof JSSymbol sym ? sym.asPropertyKey()
            : toString(key);
        if (base instanceof JSObject obj) {
            // ECMA-262 § 10.4.3 String exotic [[GetOwnProperty]]: a wrapped
            // String returns its underlying chars + length virtually.
            Object stringData = obj.properties().get(Realm.SLOT_STRING_DATA);
            if (stringData instanceof CharSequence cs) {
                if ("length".equals(prop)) return boxDouble(cs.length());
                int idxs = parseIndex(prop);
                if (idxs >= 0 && idxs < cs.length()) {
                    return String.valueOf(cs.charAt(idxs));
                }
            }
            // ECMA-262 § 10.1.8.1 OrdinaryGet: when the located property is
            // an accessor, invoke its [[Get]] with the receiver as `this`.
            // Callers that need the raw Accessor cell (e.g. class-member
            // install when merging get/set on the same key) should use
            // {@link #getOwnPropertyRaw} or read the underlying map directly.
            // ECMA-262 § 7.3.30 PrivateGet: accessing a private name (#x)
            // on an object whose class did not declare it throws TypeError.
            // We approximate via '#'-prefixed keys + proto-chain walk (private
            // methods live on the class prototype). Use `has` (which walks
            // chain) to distinguish "missing" from "present-as-undefined".
            if (!prop.isEmpty() && prop.charAt(0) == '#' && !obj.has(prop)) {
                throw AbruptCompletion.typeError("Cannot read private member " + prop + " from an object whose class did not declare it");
            }
            Object v = obj.get(prop);
            if (v instanceof Accessor acc) {
                if (acc.getter() == null) return Undefined.VALUE;
                InterpContext ctx = InterpContext.current();
                if (ctx == null) return v;   // pre-interpret bootstrap path
                return Interpreter.invokeFunction(acc.getter(), base, new Object[0], ctx);
            }
            return v;
        }
        if (base instanceof JSArray arr) {
            if ("length".equals(prop)) return boxDouble(arr.length());
            int idx = parseIndex(prop);
            if (idx >= 0 && idx < arr.length()) {
                Object v = arr.get(idx);
                if (v instanceof Accessor acc) {
                    if (acc.getter() == null) return Undefined.VALUE;
                    InterpContext ctx = InterpContext.current();
                    if (ctx == null) return Undefined.VALUE;
                    return Interpreter.invokeFunction(acc.getter(), base, new Object[0], ctx);
                }
                return v;
            }
            // Non-index extras (e.g. tagged template strings.raw).
            if (arr.hasExtraProperty(prop)) {
                Object v = arr.getExtraProperty(prop);
                if (v instanceof Accessor acc && acc.getter() != null) {
                    InterpContext ctx = InterpContext.current();
                    if (ctx == null) return Undefined.VALUE;
                    return Interpreter.invokeFunction(acc.getter(), base, new Object[0], ctx);
                }
                return v;
            }
            // Fall back to Array.prototype for methods (push, map, etc.).
            if (Realm.arrayPrototype != null) return Realm.arrayPrototype.get(prop);
            return Undefined.VALUE;
        }
        if (base instanceof CharSequence cs) {
            if ("length".equals(prop)) return boxDouble(cs.length());
            int idx = parseIndex(prop);
            if (idx >= 0 && idx < cs.length()) return String.valueOf(cs.charAt(idx));
            if (Realm.stringPrototype != null) return Realm.stringPrototype.get(prop);
            return Undefined.VALUE;
        }
        if (base instanceof JSFunction fn) {
            // ECMA-262 § 10.2.4: strict functions expose `arguments` and
            // `caller` as %ThrowTypeError% poison-pill accessors —
            // observing either throws TypeError. Tested by
            // language/statements/class/strict-mode/arguments-callee.js.
            // Skip for native functions (they have neither slot in spec).
            if (("arguments".equals(prop) || "caller".equals(prop))
                    && !fn.isNative() && !fn.hasOwnStatic(prop)
                    && fn.body() != null && fn.body().strictMode()) {
                throw AbruptCompletion.typeError(
                    "'" + prop + "' may not be accessed on strict mode functions");
            }
            // Static-style properties live on the function itself.
            if (fn.hasOwnStatic(prop)) {
                Object v = fn.getOwnStatic(prop);
                // Accessor-defined static (e.g. Constructor[Symbol.species])
                // — invoke its getter with the function as receiver, per
                // § 10.1.8.1 OrdinaryGet step 7. Missing getter → undefined.
                if (v instanceof Accessor acc) {
                    if (acc.getter() == null) return Undefined.VALUE;
                    InterpContext ctx = InterpContext.current();
                    if (ctx == null) return v;
                    return Interpreter.invokeFunction(acc.getter(), base, new Object[0], ctx);
                }
                return v;
            }
            // Spec virtual properties: name, length. The deleted-flag
            // routes them through the proto-chain like any missing prop
            // so {@code delete fn.name; fn.name} yields {@code undefined}.
            if ("name".equals(prop) && !fn.isNameDeleted()) {
                return fn.name() != null ? fn.name() : "";
            }
            if ("length".equals(prop) && !fn.isLengthDeleted()) {
                return boxDouble(fn.paramCount());
            }
            // `prototype` exposes the function's prototype object. Native
            // functions don't auto-create one; user functions do (so
            // `Foo.prototype.method = ...` just works without `class`).
            if ("prototype".equals(prop)) {
                // If the user explicitly assigned a non-JSObject (JSArray /
                // primitive / etc.), return that raw value — don't replace
                // it with the construct-time wrapper.
                Object user = fn.prototypeUser();
                if (user != null) return user;
                if (fn.prototypeObject() == null && !fn.isNative()) {
                    // § 27.6.1: an async generator function's .prototype
                    // chains through %AsyncGeneratorPrototype%.
                    JSObject parent = (fn.isAsync() && fn.isGenerator()
                                       && Realm.asyncGeneratorPrototype != null)
                        ? Realm.asyncGeneratorPrototype
                        : null;
                    JSObject p = parent == null ? new JSObject() : new JSObject(parent);
                    p.set("constructor", fn);
                    fn.setPrototypeObject(p);
                }
                if (fn.prototypeObject() != null) return fn.prototypeObject();
                return Undefined.VALUE;
            }
            // Function.prototype methods (call, apply, bind, toString).
            if (Realm.functionPrototype != null) {
                Object v = Realm.functionPrototype.get(prop);
                if (v != Undefined.VALUE) return v;
            }
            return Undefined.VALUE;
        }
        if (base instanceof JSBigInt) {
            if (Realm.bigIntPrototype != null) return Realm.bigIntPrototype.get(prop);
            return Undefined.VALUE;
        }
        if (base instanceof Number) {
            if (Realm.numberPrototype != null) return Realm.numberPrototype.get(prop);
            return Undefined.VALUE;
        }
        if (base instanceof Boolean) {
            if (Realm.booleanPrototype != null) return Realm.booleanPrototype.get(prop);
            return Undefined.VALUE;
        }
        if (base instanceof JSSymbol) {
            // Property lookup on Symbol primitives walks Symbol.prototype.
            if (Realm.symbolPrototype != null) return Realm.symbolPrototype.get(prop);
            return Undefined.VALUE;
        }
        return Undefined.VALUE;
    }

    /**
     * Read a property from {@code base} without invoking accessor getters —
     * used by code paths that need to detect/merge {@link Accessor} cells
     * (e.g. class-member install for {@code get}/{@code set} on the same key).
     * Equivalent to {@link JSObject#get(String)} on JSObjects; returns
     * {@link Undefined#VALUE} otherwise.
     */
    public static Object getOwnPropertyRaw(Object base, String key) {
        if (base instanceof JSObject obj) return obj.get(key);
        if (base instanceof JSFunction fn) {
            if (fn.hasOwnStatic(key)) return fn.getOwnStatic(key);
        }
        return Undefined.VALUE;
    }

    /** ECMAScript-ish {@code base[key] = value} write. Throws on null/undefined receiver. */
    public static void setProperty(Object base, Object key, Object value) {
        if (base == null || base == Undefined.VALUE) {
            // See getProperty: avoid toString(key) here so a throwing
            // toString/valueOf on the key doesn't replace the spec-required
            // TypeError with whatever ToPrimitive threw.
            throw AbruptCompletion.typeError("Cannot set properties of " + (base == null ? "null" : "undefined")
                + " (setting '" + displayKeyForError(key) + "')");
        }
        // Hot path: array[i] = value with a numeric key. Skip the
        // Long.toString -> parseIndex round-trip (matches the same fast
        // path in getProperty). Profile showed setProperty was the top
        // caller of Long.toString (17% of allocated bytes for the lodash
        // workload). Only take it when the existing in-range slot isn't
        // an accessor — otherwise we'd silently overwrite a setter
        // (language/statements/for-in/head-lhs-let.js installs one on
        // Array.prototype['1']) and bypass the spec's [[Set]] dispatch.
        if (base instanceof JSArray arr && key instanceof Number n) {
            double d = n.doubleValue();
            int idx = (int) d;
            if (idx == d && idx >= 0 && idx < arr.length()) {
                Object existing = arr.get(idx);
                if (!(existing instanceof Accessor)) {
                    arr.set(idx, value);
                    return;
                }
            }
        }
        // ECMA-262 § 28.2.7.5 [[Set]] on a Proxy.
        if (base instanceof JSObject pj && Realm.isProxy(pj)) {
            JSFunction trap = Realm.proxyTrap(pj, "set");
            Object target = Realm.proxyTarget(pj);
            if (target == null) {
                throw AbruptCompletion.typeError("Cannot perform 'set' on a proxy that has been revoked");
            }
            String pkey = key instanceof String s ? s
                : key instanceof JSSymbol sym ? sym.asPropertyKey()
                : toString(key);
            if (trap != null) {
                InterpContext ctx = InterpContext.current();
                if (ctx != null) {
                    Interpreter.invokeFunction(trap, Realm.proxyHandler(pj),
                        new Object[]{target, pkey, value, pj}, ctx);
                    return;
                }
            }
            setProperty(target, pkey, value);
            return;
        }
        // ECMA-262 § 23.2.5.11 IntegerIndexedElementSet — typed-array
        // writes silently no-op on detached / out-of-range / non-integer
        // indices; only valid in-range integer keys store to the buffer.
        if (base instanceof JSObject jsoEarly) {
            TypedArrayState taState = TypedArrays.stateOf(jsoEarly);
            if (taState != null) {
                // § 10.4.5.5 IntegerIndexedExoticObject [[Set]]: canonical
                // numeric index strings — even invalid ones (NaN, -0, 1.1,
                // out-of-bounds) — never reach the underlying property map.
                // They silent-fail.
                if (key instanceof Number nn) {
                    long idx = TypedArrays.integerIndexFromNumber(nn.doubleValue(), taState.length());
                    if (idx >= 0) TypedArrays.storeElement(taState, idx, value);
                    return;   // canonical numeric: always handled or silent
                }
                if (key instanceof String sk) {
                    double canonical = TypedArrays.canonicalNumericIndexString(sk);
                    if (!Double.isNaN(canonical)) {
                        long idx = TypedArrays.integerIndexFromNumber(canonical, taState.length());
                        if (idx >= 0) TypedArrays.storeElement(taState, idx, value);
                        return;
                    }
                    // Non-canonical key (e.g. "foo") → ordinary set on the
                    // underlying property map.
                }
            }
        }
        String prop = key instanceof String s ? s
            : key instanceof JSSymbol sym ? sym.asPropertyKey()
            : toString(key);
        if (base instanceof JSObject obj) {
            // ECMA-262 § 10.1.9.2 OrdinarySetWithOwnDescriptor: walk the
            // prototype chain looking for an Accessor (or own data prop). If
            // an Accessor with setter is found anywhere in the chain, invoke
            // its setter with `obj` as receiver. Otherwise set as own data prop.
            JSObject cursor = obj;
            while (cursor != null) {
                // Direct own-property check — avoids any Map allocation that
                // `propertiesIfPresent()` would need when JSObject is in
                // flat-array mode (most small objects).
                Object existing = cursor.getOwn(prop);
                if (existing instanceof Accessor acc) {
                    if (acc.setter() != null) {
                        InterpContext ctx = InterpContext.current();
                        // No live ctx means we're being called from a path
                        // that hasn't pushed CURRENT (e.g. some bootstrap
                        // wiring). Fall through to plain set on receiver
                        // — wrong per spec but better than silently dropping
                        // the write.
                        if (ctx != null) {
                            Interpreter.invokeFunction(acc.setter(), obj, new Object[]{value}, ctx);
                            return;
                        }
                        break;
                    }
                    // Setter-less accessor on chain: § 10.1.9.2 step 4
                    // throws TypeError in strict mode.
                    InterpContext ctx = InterpContext.current();
                    if (ctx != null && ctx.executable() != null && ctx.executable().strictMode()) {
                        throw AbruptCompletion.typeError("Cannot set property '" + prop + "' of " + obj + " which has only a getter");
                    }
                    return;
                }
                if (existing != JSObject.ABSENT) {
                    // Own/inherited data prop — § 10.1.9.2 step 3.b: in
                    // strict mode, writing to a non-writable property throws.
                    if (!cursor.isWritable(prop)) {
                        InterpContext ctx = InterpContext.current();
                        if (ctx != null && ctx.executable() != null && ctx.executable().strictMode()) {
                            throw AbruptCompletion.typeError("Cannot assign to read only property '" + prop + "'");
                        }
                        return;   // non-strict: silently fail
                    }
                    break;
                }
                cursor = cursor.proto();
            }
            obj.set(prop, value);
            return;
        }
        if (base instanceof JSArray arr) {
            // ECMA-262 § 10.4.2.4 ArraySetLength — writing .length truncates
            // or extends. Validate as uint32 (RangeError otherwise) and
            // delegate to the sparse-aware setter. Respect a frozen length
            // (writable=false) by silent-failing in sloppy mode.
            if ("length".equals(prop)) {
                byte la = arr.getIndexAttributes("length");
                if ((la & JSObject.ATTR_WRITABLE) == 0) return;   // frozen, silent fail
                double d = toNumber(value);
                if (Double.isNaN(d) || d < 0 || d != Math.floor(d) || d > 4294967295.0) {
                    throw AbruptCompletion.rangeError("Invalid array length");
                }
                int newLen = (int) Math.min((long) d, Integer.MAX_VALUE);
                if (newLen < arr.length()) {
                    // Stop at the first non-configurable index encountered,
                    // matching § 10.4.2.4 step 17 (truncate as far as possible
                    // then return false / silent fail in sloppy mode).
                    for (int i = arr.length() - 1; i >= newLen; i--) {
                        String k = Integer.toString(i);
                        if (arr.hasIndexAttributes(k)) {
                            byte ia = arr.getIndexAttributes(k);
                            if ((ia & JSObject.ATTR_CONFIGURABLE) == 0) {
                                arr.setLength(i + 1);
                                arr.clearIndexAttributesAtOrAbove(i + 1);
                                return;
                            }
                        }
                    }
                    arr.setLength(newLen);
                    arr.clearIndexAttributesAtOrAbove(newLen);
                } else {
                    arr.setLength(newLen);
                }
                return;
            }
            int idx = parseIndex(prop);
            // Reject writes past a frozen length — index would force length to grow.
            if (idx >= 0 && idx >= arr.length()) {
                byte la = arr.getIndexAttributes("length");
                if ((la & JSObject.ATTR_WRITABLE) == 0) return;
            }
            // Reject writes to non-writable indexed slots — silent fail in
            // sloppy mode. Accessor properties have writable=undefined in their
            // descriptor; the [[Set]] path runs the setter (or silent-fails)
            // before this check, so only data slots flow here.
            if (idx >= 0 && idx < arr.length() && arr.hasIndexAttributes(prop)
                    && !(arr.get(idx) instanceof Accessor)) {
                byte ia = arr.getIndexAttributes(prop);
                if ((ia & JSObject.ATTR_WRITABLE) == 0) return;
            }
            if (idx >= 0) {
                // Invoke setter if there's an Accessor at this index.
                if (idx < arr.length()) {
                    Object existing = arr.get(idx);
                    if (existing instanceof Accessor acc) {
                        if (acc.setter() != null) {
                            InterpContext ctx = InterpContext.current();
                            if (ctx != null) {
                                Interpreter.invokeFunction(acc.setter(), base, new Object[]{value}, ctx);
                                return;
                            }
                        }
                        return;   // accessor with no setter: silent drop in sloppy mode
                    }
                }
                // No own element at this index — § 10.1.9.2 step 2 says to
                // walk the prototype chain (Array.prototype, then Object
                // .prototype) for an inherited setter. Required by
                // language/statements/for-in/head-lhs-let.js which defines
                // Array.prototype['1'] as a setter and assigns through it.
                JSObject protoCursor = Realm.arrayPrototype;
                while (protoCursor != null) {
                    Object existing = protoCursor.getOwn(prop);
                    if (existing instanceof Accessor acc) {
                        if (acc.setter() != null) {
                            InterpContext ctx = InterpContext.current();
                            if (ctx != null) {
                                Interpreter.invokeFunction(acc.setter(), base, new Object[]{value}, ctx);
                                return;
                            }
                        }
                        return;   // accessor without setter: silent drop in sloppy mode
                    }
                    if (existing != JSObject.ABSENT) break;
                    protoCursor = protoCursor.proto();
                }
                arr.set(idx, value);
                return;
            }
            // Non-index property — also honor accessors installed on
            // Array.prototype (or higher up the chain) at non-index keys.
            JSObject protoCursor2 = Realm.arrayPrototype;
            while (protoCursor2 != null) {
                Object existing = protoCursor2.getOwn(prop);
                if (existing instanceof Accessor acc) {
                    if (acc.setter() != null) {
                        InterpContext ctx = InterpContext.current();
                        if (ctx != null) {
                            Interpreter.invokeFunction(acc.setter(), base, new Object[]{value}, ctx);
                            return;
                        }
                    }
                    return;
                }
                if (existing != JSObject.ABSENT) break;
                protoCursor2 = protoCursor2.proto();
            }
            // Non-index property — store in the array's extra-properties
            // map so tagged-template strings.raw and similar patterns work.
            arr.setExtraProperty(prop, value);
            return;
        }
        if (base instanceof JSFunction fn) {
            // `Foo.prototype = obj` is special — it routes to the function's
            // own [[Construct]] prototype slot, not to the static-properties map.
            if ("prototype".equals(prop)) {
                // Per § 10.2.4.3 [[Set]] on a function — any value is
                // accepted as fn.prototype (the user can store an Array,
                // primitive, etc.). Non-objects don't participate in
                // `new fn()` proto chains (spec falls back to
                // Object.prototype), but they're still readable via
                // fn.prototype.
                fn.setPrototypeUser(value);
                return;
            }
            // ECMA-262 § 10.2.10: the virtual {@code name} and {@code length}
            // properties on a Function have {writable: false, configurable:
            // true}. Silently drop the write in sloppy mode to match the
            // descriptor (and {@code verifyProperty}'s isWritable probe).
            // A user who really wants to rebind these does so through
            // {@code Object.defineProperty}, which takes a separate path.
            if (("name".equals(prop) || "length".equals(prop)) && !fn.hasOwnStatic(prop)) {
                return;
            }
            // ECMA-262 § 10.1.9.2 OrdinarySetWithOwnDescriptor: if an own
            // static property holds an Accessor with a setter, invoke it.
            // Class statics install accessors via {@code fn.properties().put}
            // (see Op.NewClass), so static {@code get}/{@code set} pairs
            // need this branch to dispatch through the setter rather than
            // overwriting the accessor cell or silently dropping the write.
            if (fn.hasOwnStatic(prop)) {
                Object existing = fn.getOwnStatic(prop);
                if (existing instanceof Accessor acc) {
                    if (acc.setter() != null) {
                        InterpContext ctx = InterpContext.current();
                        if (ctx != null) {
                            Interpreter.invokeFunction(acc.setter(), fn, new Object[]{value}, ctx);
                            return;
                        }
                        // No live ctx (bootstrap) — fall through; better
                        // than silently dropping.
                    } else {
                        InterpContext ctx = InterpContext.current();
                        if (ctx != null && ctx.executable() != null && ctx.executable().strictMode()) {
                            throw AbruptCompletion.typeError("Cannot set property '" + prop + "' of " + fn + " which has only a getter");
                        }
                        return;
                    }
                }
            }
            // Respect non-writable static properties (e.g. {@code
            // BYTES_PER_ELEMENT} on TypedArray constructors). Sloppy mode
            // silently drops the write; strict mode throws — § 10.1.9.2
            // step 3.b.
            if (fn.hasOwnStatic(prop) && !fn.isWritable(prop)) {
                InterpContext ctx = InterpContext.current();
                if (ctx != null && ctx.executable() != null && ctx.executable().strictMode()) {
                    throw AbruptCompletion.typeError("Cannot assign to read only property '" + prop + "'");
                }
                return;
            }
            // Adding a NEW property requires [[Extensible]] = true.
            if (!fn.hasOwnStatic(prop) && !fn.isExtensible()) {
                InterpContext ctx = InterpContext.current();
                if (ctx != null && ctx.executable() != null && ctx.executable().strictMode()) {
                    throw AbruptCompletion.typeError("Cannot add property " + prop + ", function is not extensible");
                }
                return;
            }
            fn.properties().put(prop, value);
            return;
        }
        // strings, numbers etc. silently swallow writes in non-strict mode (we're permissive for v1)
    }

    /** Parse a string as a non-negative array-index integer, or -1 if not a valid index. */
    private static int parseIndex(String s) {
        if (s.isEmpty()) return -1;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            n = n * 10 + (c - '0');
            if (n < 0) return -1;
        }
        return n;
    }

    // -------------------------------------------------------------
    //  Type conversion
    // -------------------------------------------------------------

    /**
     * ECMA-262 § 7.1.12 NumberToString. Java's {@code Double.toString} uses
     * uppercase {@code E}, omits the {@code +} sign on positive exponents,
     * and always emits a {@code .0} mantissa — none of which match the
     * spec. Re-format from Java's canonical string by extracting the
     * significant digits and a decimal exponent, then apply the spec's
     * case rules ({@code 100} vs {@code 0.001} vs {@code 1e-7}).
     */
    static String numberToString(double x) {
        if (Double.isNaN(x))    return "NaN";
        if (x == 0.0)           return "0";
        if (x < 0)              return "-" + numberToString(-x);
        if (Double.isInfinite(x)) return "Infinity";
        String javaStr = Double.toString(x);
        int eIdx = javaStr.indexOf('E');
        String mantissa = eIdx >= 0 ? javaStr.substring(0, eIdx) : javaStr;
        int javaExp = eIdx >= 0 ? Integer.parseInt(javaStr.substring(eIdx + 1)) : 0;
        int dotIdx = mantissa.indexOf('.');
        String s;
        int n;
        if (dotIdx >= 0) {
            int end = mantissa.length();
            while (end > dotIdx + 1 && mantissa.charAt(end - 1) == '0') end--;
            String before = mantissa.substring(0, dotIdx);
            String after = end > dotIdx + 1 ? mantissa.substring(dotIdx + 1, end) : "";
            s = before + after;
            n = before.length() + javaExp;
        } else {
            int end = mantissa.length();
            int trailing = 0;
            while (end > 1 && mantissa.charAt(end - 1) == '0') { end--; trailing++; }
            s = mantissa.substring(0, end);
            n = end + trailing + javaExp;
        }
        // Strip leading zeros from s (shouldn't happen, but defensive).
        int lead = 0;
        while (lead < s.length() - 1 && s.charAt(lead) == '0') lead++;
        if (lead > 0) { s = s.substring(lead); n -= lead; }
        int k = s.length();
        if (k <= n && n <= 21) {
            StringBuilder b = new StringBuilder(s);
            for (int i = 0; i < n - k; i++) b.append('0');
            return b.toString();
        }
        if (0 < n && n <= 21) {
            return s.substring(0, n) + "." + s.substring(n);
        }
        if (-6 < n && n <= 0) {
            StringBuilder b = new StringBuilder("0.");
            for (int i = 0; i < -n; i++) b.append('0');
            b.append(s);
            return b.toString();
        }
        int exp = n - 1;
        String expStr = (exp >= 0 ? "+" : "") + exp;
        if (k == 1) return s + "e" + expStr;
        return s.charAt(0) + "." + s.substring(1) + "e" + expStr;
    }

    /** https://tc39.es/ecma262/#sec-tostring */
    public static String toString(Object v) {
        if (v == null)            return "null";
        if (v == Undefined.VALUE) return "undefined";
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof String s) return s;
        // ConsString: flatten on coercion to a real Java String. The flatten
        // is cached on the ConsString so subsequent toString hits are free.
        if (v instanceof ConsString cs) return cs.toString();
        // ECMA-262 § 7.1.17.2 — ToString on a Symbol throws TypeError.
        // (The {@code String()} constructor has its own path that returns
        // the symbol's descriptive string instead — see Realm.java.)
        if (v instanceof JSSymbol) throw AbruptCompletion.typeError("Cannot convert a Symbol value to a string");
        if (v instanceof JSBigInt bi) return bi.value.toString();
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d))           return "NaN";
            if (d == Double.POSITIVE_INFINITY) return "Infinity";
            if (d == Double.NEGATIVE_INFINITY) return "-Infinity";
            if (d == (long) d && !Double.isInfinite(d) && Math.abs(d) < 1e21) {
                return Long.toString((long) d);
            }
            return numberToString(d);
        }
        // Object: ECMA-262 § 7.1.17 ToString — ToPrimitive(v, "string")
        // then ToString on the result. ToPrimitive throws TypeError if
        // both toString and valueOf return objects (§ 7.1.1 step 6),
        // and that TypeError must propagate to the caller.
        if (v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction) {
            Object prim = toPrimitive(v, "string");
            if (prim != v) return toString(prim);
            // Defensive: if ToPrimitive returned the object itself (no
            // toString/valueOf hooks present), fall back to a canonical
            // string. Real JS engines never reach this branch.
            if (v instanceof JSArray arr)    return arr.toString();
            if (v instanceof JSFunction fn)  return "function " + (fn.name() != null ? fn.name() : "") + "() { [native code] }";
            return "[object Object]";
        }
        return v.toString();
    }
}
