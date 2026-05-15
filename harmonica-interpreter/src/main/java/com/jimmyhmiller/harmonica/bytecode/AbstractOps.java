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
        Object lPrim = toPrimitive(lhs, "default");
        Object rPrim = toPrimitive(rhs, "default");
        if (lPrim instanceof CharSequence || rPrim instanceof CharSequence) {
            return ConsString.cons(toString(lPrim), toString(rPrim));
        }
        return boxDouble(toNumber(lPrim) + toNumber(rPrim));
    }

    public static Object sub(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return boxDouble(dl - dr);
        return boxDouble(toNumber(lhs) - toNumber(rhs));
    }
    public static Object mul(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return boxDouble(dl * dr);
        return boxDouble(toNumber(lhs) * toNumber(rhs));
    }
    public static Object div(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return boxDouble(dl / dr);
        return boxDouble(toNumber(lhs) / toNumber(rhs));
    }
    public static Object mod(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return boxDouble(dl % dr);
        return boxDouble(toNumber(lhs) % toNumber(rhs));
    }
    public static Object exp(Object lhs, Object rhs) { return boxDouble(Math.pow(toNumber(lhs), toNumber(rhs))); }

    public static Object bitwiseAnd(Object lhs, Object rhs) { return boxDouble(toInt32(lhs) & toInt32(rhs)); }
    public static Object bitwiseOr (Object lhs, Object rhs) { return boxDouble(toInt32(lhs) | toInt32(rhs)); }
    public static Object bitwiseXor(Object lhs, Object rhs) { return boxDouble(toInt32(lhs) ^ toInt32(rhs)); }

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

    public static Object unaryMinus(Object v) { return -toNumber(v); }
    public static Object unaryPlus (Object v) { return  toNumber(v); }
    public static Object bitwiseNot(Object v) { return boxDouble(~toInt32(v)); }
    public static Object not       (Object v) { return !toBoolean(v); }

    /** ECMA-262 § 13.5.3 typeof operator — Table 38 (typeof Operator Results). */
    public static String typeofValue(Object v) {
        if (v == Undefined.VALUE)    return "undefined";
        if (v == null)               return "object";
        if (v instanceof Boolean)       return "boolean";
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
    public static Boolean lessThan         (Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return dl < dr;
        Object lp = lhs instanceof JSObject || lhs instanceof JSArray || lhs instanceof JSFunction
                  ? toPrimitive(lhs, "number") : lhs;
        Object rp = rhs instanceof JSObject || rhs instanceof JSArray || rhs instanceof JSFunction
                  ? toPrimitive(rhs, "number") : rhs;
        if (lp instanceof CharSequence ls && rp instanceof CharSequence rs) {
            return ls.toString().compareTo(rs.toString()) <  0;
        }
        return toNumber(lp) <  toNumber(rp);
    }
    public static Boolean lessThanEquals   (Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return dl <= dr;
        Object lp = lhs instanceof JSObject || lhs instanceof JSArray || lhs instanceof JSFunction
                  ? toPrimitive(lhs, "number") : lhs;
        Object rp = rhs instanceof JSObject || rhs instanceof JSArray || rhs instanceof JSFunction
                  ? toPrimitive(rhs, "number") : rhs;
        if (lp instanceof CharSequence ls && rp instanceof CharSequence rs) {
            return ls.toString().compareTo(rs.toString()) <= 0;
        }
        return toNumber(lp) <= toNumber(rp);
    }
    public static Boolean greaterThan      (Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return dl > dr;
        Object lp = lhs instanceof JSObject || lhs instanceof JSArray || lhs instanceof JSFunction
                  ? toPrimitive(lhs, "number") : lhs;
        Object rp = rhs instanceof JSObject || rhs instanceof JSArray || rhs instanceof JSFunction
                  ? toPrimitive(rhs, "number") : rhs;
        if (lp instanceof CharSequence ls && rp instanceof CharSequence rs) {
            return ls.toString().compareTo(rs.toString()) >  0;
        }
        return toNumber(lp) >  toNumber(rp);
    }
    public static Boolean greaterThanEquals(Object lhs, Object rhs) {
        if (lhs instanceof Double dl && rhs instanceof Double dr) return dl >= dr;
        Object lp = lhs instanceof JSObject || lhs instanceof JSArray || lhs instanceof JSFunction
                  ? toPrimitive(lhs, "number") : lhs;
        Object rp = rhs instanceof JSObject || rhs instanceof JSArray || rhs instanceof JSFunction
                  ? toPrimitive(rhs, "number") : rhs;
        if (lp instanceof CharSequence ls && rp instanceof CharSequence rs) {
            return ls.toString().compareTo(rs.toString()) >= 0;
        }
        return toNumber(lp) >= toNumber(rp);
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

    /** https://tc39.es/ecma262/#sec-tonumber */
    public static double toNumber(Object v) {
        if (v == null)            return 0.0;
        if (v == Undefined.VALUE) return Double.NaN;
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        if (v instanceof Number n)  return n.doubleValue();
        // ECMA-262 § 7.1.4.2 — ToNumber on a Symbol throws TypeError.
        if (v instanceof JSSymbol) throw AbruptCompletion.typeError("Cannot convert a Symbol value to a number");
        if (v instanceof CharSequence) {
            String t = v.toString().trim();
            if (t.isEmpty()) return 0.0;   // ECMA spec: "" → 0, "  " → 0
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
        if (v instanceof Boolean || v instanceof Number || v instanceof CharSequence) return v;
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

    /** ECMAScript-ish {@code base[key]} read. Throws on null/undefined receiver. */
    public static Object getProperty(Object base, Object key) {
        if (base == null || base == Undefined.VALUE) {
            // Don't ToString a Symbol key here: it would mask the underlying
            // null/undefined error with a useless Symbol-coercion error.
            String displayKey = key instanceof JSSymbol sy ? sy.toString() : toString(key);
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
                if (key instanceof Number nn) {
                    long idx = TypedArrays.integerIndexFromNumber(nn.doubleValue(), taState.length());
                    if (idx == TypedArrays.IDX_OUT_OF_RANGE) return Undefined.VALUE;
                    if (idx >= 0) return TypedArrays.loadElement(taState, idx);
                    // fall through to normal property access (named props,
                    // prototype methods like fill/slice/etc.)
                } else if (key instanceof String sk) {
                    long idx = TypedArrays.integerIndexFromString(sk, taState.length());
                    if (idx == TypedArrays.IDX_OUT_OF_RANGE) return Undefined.VALUE;
                    if (idx >= 0) return TypedArrays.loadElement(taState, idx);
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
            if (v instanceof Accessor acc && acc.getter() != null) {
                InterpContext ctx = InterpContext.current();
                if (ctx == null) return v;   // pre-interpret bootstrap path
                return Interpreter.invokeFunction(acc.getter(), base, new Object[0], ctx);
            }
            return v;
        }
        if (base instanceof JSArray arr) {
            if ("length".equals(prop)) return boxDouble(arr.length());
            int idx = parseIndex(prop);
            if (idx >= 0 && idx < arr.length()) return arr.get(idx);
            // Non-index extras (e.g. tagged template strings.raw).
            if (arr.hasExtraProperty(prop)) return arr.getExtraProperty(prop);
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
            // Static-style properties live on the function itself.
            if (fn.hasOwnStatic(prop)) return fn.getOwnStatic(prop);
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
                if (fn.prototypeObject() == null && !fn.isNative()) {
                    JSObject p = new JSObject();
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
            throw AbruptCompletion.typeError("Cannot set properties of " + (base == null ? "null" : "undefined"));
        }
        // Hot path: array[i] = value with a numeric key. Skip the
        // Long.toString -> parseIndex round-trip (matches the same fast
        // path in getProperty). Profile showed setProperty was the top
        // caller of Long.toString (17% of allocated bytes for the lodash
        // workload).
        if (base instanceof JSArray arr && key instanceof Number n) {
            double d = n.doubleValue();
            int idx = (int) d;
            if (idx == d && idx >= 0) {
                arr.set(idx, value);
                return;
            }
        }
        // ECMA-262 § 23.2.5.11 IntegerIndexedElementSet — typed-array
        // writes silently no-op on detached / out-of-range / non-integer
        // indices; only valid in-range integer keys store to the buffer.
        if (base instanceof JSObject jsoEarly) {
            TypedArrayState taState = TypedArrays.stateOf(jsoEarly);
            if (taState != null) {
                if (key instanceof Number nn) {
                    long idx = TypedArrays.integerIndexFromNumber(nn.doubleValue(), taState.length());
                    if (idx == TypedArrays.IDX_OUT_OF_RANGE) return;   // silent
                    if (idx >= 0) { TypedArrays.storeElement(taState, idx, value); return; }
                    // not integer-indexed → fall through to normal set
                } else if (key instanceof String sk) {
                    long idx = TypedArrays.integerIndexFromString(sk, taState.length());
                    if (idx == TypedArrays.IDX_OUT_OF_RANGE) return;
                    if (idx >= 0) { TypedArrays.storeElement(taState, idx, value); return; }
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
            int idx = parseIndex(prop);
            if (idx >= 0) { arr.set(idx, value); return; }
            // Non-index property — store in the array's extra-properties
            // map so tagged-template strings.raw and similar patterns work.
            arr.setExtraProperty(prop, value);
            return;
        }
        if (base instanceof JSFunction fn) {
            // `Foo.prototype = obj` is special — it routes to the function's
            // own [[Construct]] prototype slot, not to the static-properties map.
            if ("prototype".equals(prop)) {
                if (value instanceof JSObject p) fn.setPrototypeObject(p);
                else if (value == null || value == Undefined.VALUE) fn.setPrototypeObject(null);
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
        // Object: ToPrimitive(v, "string") then recurse. Falls back to a
        // canonical Java toString() for native types if ToPrimitive throws
        // (we don't want a missing toString/valueOf to crash the whole
        // computation when the caller just wanted a debug-friendly string).
        if (v instanceof JSObject || v instanceof JSArray || v instanceof JSFunction) {
            try {
                Object prim = toPrimitive(v, "string");
                if (prim != v) return toString(prim);
            } catch (AbruptCompletion ignored) { /* fall through */ }
            if (v instanceof JSArray arr)    return arr.toString();
            if (v instanceof JSFunction fn)  return "function " + (fn.name() != null ? fn.name() : "") + "() { [native code] }";
            return "[object Object]";
        }
        return v.toString();
    }
}
