package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Behavioral conformance tests against LibJS as the oracle.
 *
 * <p>Each test runs the same JS source through both engines and compares
 * the final completion value. Skipped at runtime if the {@code js} binary
 * isn't built/available.
 *
 * <p>Set the {@code LIBJS_BIN} env var to override the binary location.
 */
class OracleBehavioralTest {

    private static LibJsOracle oracle;

    @BeforeAll
    static void setup() {
        oracle = LibJsOracle.resolve();
        assumeTrue(oracle.isAvailable(),
            "LibJS not built — skipping. Build it with `Meta/ladybird.py build js` "
            + "or set LIBJS_BIN to the binary path. Looking at: " + oracle.binary());
    }

    /** Round-trips a source through ours and through LibJS, compares. */
    private void compare(String source, String expectedFromLibJs) {
        // Sanity: confirm the oracle's view matches the test's claim.
        String oracleSaw = oracle.evaluate(source);
        assertEquals(expectedFromLibJs, oracleSaw,
            "test's expected value disagrees with LibJS oracle for: " + source);

        // Now run ours.
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        Object ours = Interpreter.interpret(exe, new Object[0], 64);
        assertEquals(expectedFromLibJs, formatOurValue(ours),
            "harmonica disagrees with LibJS for: " + source);
    }

    /**
     * Convert our internal value to the string LibJS would print with
     * {@code --print-last-result}. Crude — refine as we encounter edge cases.
     */
    private static String formatOurValue(Object v) {
        if (v == null) return "null";
        if (v == Undefined.VALUE) return "undefined";
        if (v instanceof Double d) {
            if (d == d.longValue() && !d.isInfinite()) return Long.toString(d.longValue());
            return Double.toString(d);
        }
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof String s) return "\"" + s + "\"";
        return v.toString();
    }

    @Test void number()       { compare("42", "42"); }
    @Test void addition()     { compare("1 + 2", "3"); }
    @Test void chainedAdd()   { compare("1 + 2 + 3 + 4", "10"); }
    @Test void mulPrec()      { compare("1 + 2 * 3", "7"); }
    @Test void compare_lt()   { compare("1 < 2", "true"); }
    @Test void compare_eq()   { compare("3 === 3", "true"); }

    @Test void letBinding()   { compare("let x = 5; x", "5"); }
    @Test void letAndAdd()    { compare("let x = 5; let y = 3; x + y", "8"); }

    @Test void ifTaken()      { compare("let x = 0; if (1 < 2) x = 1; x", "1"); }
    @Test void ifNotTaken()   { compare("let x = 0; if (2 < 1) x = 1; x", "0"); }
    @Test void ifElse()       { compare("let x; if (2 < 1) x = 1; else x = 2; x", "2"); }

    @Test void whileLoop()    { compare("let x = 0; while (x < 3) x = x + 1; x", "3"); }
    @Test void whileSum() {
        compare("let i = 1; let sum = 0; while (i < 6) { sum = sum + i; i = i + 1; } sum", "15");
    }

    // ---------- Additional binary operators ----------
    @Test void mod()              { compare("17 % 5", "2"); }
    @Test void exp()              { compare("2 ** 10", "1024"); }
    @Test void bitwiseAnd()       { compare("0b1100 & 0b1010", "8"); }
    @Test void bitwiseOr()        { compare("0b1100 | 0b1010", "14"); }
    @Test void bitwiseXor()       { compare("0b1100 ^ 0b1010", "6"); }
    @Test void leftShift()        { compare("1 << 4", "16"); }
    @Test void rightShift()       { compare("16 >> 2", "4"); }
    @Test void unsignedRShift()   { compare("(-1) >>> 28", "15"); }

    @Test void lte()              { compare("3 <= 3", "true"); }
    @Test void gt()               { compare("4 > 3", "true"); }
    @Test void gte()              { compare("3 >= 4", "false"); }
    @Test void strictNotEq()      { compare("1 !== 2", "true"); }
    @Test void looseEq()          { compare("1 == 1", "true"); }
    @Test void looseNotEq()       { compare("1 != 2", "true"); }

    // ---------- Unary ----------
    @Test void unaryMinus()       { compare("-(7)", "-7"); }
    @Test void unaryPlus()        { compare("+(\"3\")", "3"); }
    @Test void bitwiseNot()       { compare("~5", "-6"); }
    @Test void logicalNot()       { compare("!true", "false"); }
    @Test void doubleNot()        { compare("!!1", "true"); }

    // ---------- Update (++, --) ----------
    @Test void prefixInc()        { compare("let x = 5; ++x; x", "6"); }
    @Test void postfixInc()       { compare("let x = 5; x++; x", "6"); }
    @Test void prefixDec()        { compare("let x = 5; --x; x", "4"); }
    @Test void postfixDec()       { compare("let x = 5; x--; x", "4"); }
    @Test void postfixReturnsOld(){ compare("let x = 5; let y = x++; y", "5"); }
    @Test void prefixReturnsNew() { compare("let x = 5; let y = ++x; y", "6"); }

    // ---------- Mixed ----------
    @Test void factorialIsh() {
        // No functions yet, so do it inline.
        compare("let n = 5; let r = 1; while (n > 1) { r = r * n; n = n - 1; } r", "120");
    }
    @Test void boolMix()          { compare("let a = 1; let b = 2; (a < b) === !(a >= b)", "true"); }

    // ---------- Functions ----------
    @Test void simpleFn() {
        compare("function inc(n) { return n + 1; } inc(5)", "6");
    }
    @Test void twoArgs() {
        compare("function add(a, b) { return a + b; } add(3, 4)", "7");
    }
    @Test void noReturn() {
        compare("function f() { let x = 1; } f()", "undefined");
    }
    @Test void earlyReturn() {
        compare("function abs(n) { if (n < 0) return -n; return n; } abs(-7)", "7");
    }
    @Test void earlyReturnPositive() {
        compare("function abs(n) { if (n < 0) return -n; return n; } abs(7)", "7");
    }
    @Test void recursive() {
        compare(
            "function fact(n) { if (n < 2) return 1; return n * fact(n - 1); }" +
            "fact(6)",
            "720");
    }
    @Test void fnReadsGlobal() {
        compare("let m = 10; function f(n) { return n * m; } f(3)", "30");
    }
    @Test void fnInLoop() {
        compare(
            "function double(n) { return n * 2; }" +
            "let sum = 0; let i = 1;" +
            "while (i <= 4) { sum = sum + double(i); i = i + 1; }" +
            "sum",
            "20");
    }
    @Test void fnCallsFn() {
        compare(
            "function inner(x) { return x + 1; }" +
            "function outer(x) { return inner(x) * 2; }" +
            "outer(5)",
            "12");
    }

    // ---------- Function expressions and arrows ----------
    @Test void fnExpressionAssigned() {
        compare("let f = function(n) { return n * n; }; f(7)", "49");
    }
    @Test void arrowExpressionBody() {
        compare("let sq = (n) => n * n; sq(8)", "64");
    }
    @Test void arrowBlockBody() {
        compare("let sq = (n) => { return n * n; }; sq(9)", "81");
    }
    @Test void arrowSingleParamNoParens() {
        compare("let inc = n => n + 1; inc(41)", "42");
    }
    @Test void arrowZeroArg() {
        compare("let f = () => 7; f()", "7");
    }
    @Test void higherOrder() {
        compare(
            "function apply(f, x) { return f(x); }" +
            "apply(n => n + 100, 23)",
            "123");
    }

    // ---------- for-loop ----------
    @Test void forSum() {
        compare("let s = 0; for (let i = 1; i <= 5; i = i + 1) s = s + i; s", "15");
    }
    @Test void forIncrementOp() {
        compare("let s = 0; for (let i = 0; i < 4; i++) s = s + i; s", "6");
    }
    @Test void forNoInit() {
        compare("let i = 1; let p = 1; for (; i <= 4; i++) p = p * i; p", "24");
    }
    @Test void forEmpty() {
        compare("let n = 0; for (;;) { n++; if (n === 5) break; }; n", "5");
    }
    // forEmpty needs `break` — falls back to a separate test if break isn't supported yet.

    // ---------- logical / ternary ----------
    @Test void andTrue()      { compare("1 && 2", "2"); }
    @Test void andFalse()     { compare("0 && 2", "0"); }
    @Test void orTrue()       { compare("1 || 2", "1"); }
    @Test void orFalse()      { compare("0 || 5", "5"); }
    @Test void nullishUndef() { compare("undefined ?? 7", "7"); }
    @Test void nullishNull()  { compare("null ?? 7", "7"); }
    @Test void nullishKept()  { compare("0 ?? 7", "0"); }
    @Test void ternaryT()     { compare("(1 < 2) ? 7 : 9", "7"); }
    @Test void ternaryF()     { compare("(2 < 1) ? 7 : 9", "9"); }
    @Test void ternaryWithVar() {
        compare("let x = 5; let y = (x < 10) ? \"small\" : \"big\"; y", "\"small\"");
    }

    // ---------- Object literals ----------
    @Test void objectLiteralRead() {
        compare("let o = {a: 1, b: 2}; o.a + o.b", "3");
    }
    @Test void objectComputedRead() {
        compare("let o = {a: 1, b: 2}; o[\"a\"]", "1");
    }
    @Test void objectMissing()    { compare("let o = {a: 1}; o.b", "undefined"); }
    @Test void objectAssign() {
        compare("let o = {}; o.x = 7; o.x", "7");
    }
    @Test void objectComputedAssign() {
        compare("let o = {}; o[\"k\"] = 9; o.k", "9");
    }
    @Test void nestedObject() {
        compare("let o = {inner: {n: 42}}; o.inner.n", "42");
    }
    @Test void methodCall() {
        compare("let o = {add: function(a, b) { return a + b; }}; o.add(3, 4)", "7");
    }
    @Test void methodArrowCall() {
        compare("let o = {f: x => x * x}; o.f(5)", "25");
    }

    // ---------- Array literals ----------
    @Test void arrayLiteral()     { compare("[1, 2, 3].length", "3"); }
    @Test void arrayIndex()       { compare("let a = [10, 20, 30]; a[1]", "20"); }
    @Test void arrayAssign()      { compare("let a = [1, 2]; a[0] = 99; a[0]", "99"); }
    @Test void arrayLength() {
        compare("let a = [1, 2, 3, 4]; a.length", "4");
    }
    @Test void arraySum() {
        compare(
            "let a = [10, 20, 30, 40]; let s = 0;" +
            "for (let i = 0; i < a.length; i++) s = s + a[i];" +
            "s",
            "100");
    }
    @Test void arrayOfObjects() {
        compare("let a = [{n: 1}, {n: 2}, {n: 3}]; a[1].n", "2");
    }

    // ---------- Strings ----------
    @Test void stringLength()   { compare("\"hello\".length", "5"); }
    @Test void stringIndex()    { compare("\"abc\"[1]", "\"b\""); }
    @Test void stringConcat()   { compare("\"hello, \" + \"world\"", "\"hello, world\""); }

    // ---------- try / catch / finally ----------
    @Test void tryCatchValue() {
        compare("let x = 0; try { throw 7; } catch (e) { x = e; } x", "7");
    }
    @Test void tryNoThrow() {
        compare("let x = 0; try { x = 1; } catch (e) { x = -1; } x", "1");
    }
    @Test void tryFinallyAfterNormal() {
        compare("let x = 0; try { x = 1; } finally { x = x + 10; } x", "11");
    }
    @Test void tryCatchFinally() {
        compare(
            "let x = 0; try { throw 1; } catch (e) { x = e; } finally { x = x + 100; } x",
            "101");
    }
    @Test void nestedTry() {
        compare(
            "let r = 0;" +
            "try {" +
            "  try { throw 1; } catch (e) { r = e + 10; }" +
            "  r = r + 100;" +
            "} catch (e) { r = -1; } r",
            "111");
    }
    @Test void rethrowCaught() {
        compare(
            "let r = 0;" +
            "try {" +
            "  try { throw 7; } catch (e) { throw e + 1; }" +
            "} catch (e) { r = e; } r",
            "8");
    }

    // ---------- switch ----------
    @Test void switchMatch() {
        compare(
            "let x = 2; let r;" +
            "switch (x) { case 1: r = \"one\"; break;" +
            "             case 2: r = \"two\"; break;" +
            "             case 3: r = \"three\"; break; }" +
            "r",
            "\"two\"");
    }
    @Test void switchDefault() {
        compare(
            "let x = 99; let r;" +
            "switch (x) { case 1: r = 1; break;" +
            "             default: r = -1; }" +
            "r",
            "-1");
    }
    @Test void switchFallthrough() {
        compare(
            "let x = 1; let r = 0;" +
            "switch (x) { case 1: r = r + 1;" +
            "             case 2: r = r + 10; break;" +
            "             case 3: r = r + 100; }" +
            "r",
            "11");
    }
    @Test void switchOnString() {
        compare(
            "let x = \"b\"; let r;" +
            "switch (x) { case \"a\": r = 1; break;" +
            "             case \"b\": r = 2; break;" +
            "             default: r = 9; }" +
            "r",
            "2");
    }

    // ---------- Compound assignment ----------
    @Test void plusEq()    { compare("let x = 5; x += 3; x", "8"); }
    @Test void minusEq()   { compare("let x = 5; x -= 3; x", "2"); }
    @Test void mulEq()     { compare("let x = 4; x *= 3; x", "12"); }
    @Test void modEq()     { compare("let x = 17; x %= 5; x", "2"); }
    @Test void andAssign() { compare("let x = 0; x ||= 7; x", "7"); }
    @Test void andAssignKeep() { compare("let x = 5; x ||= 7; x", "5"); }
    @Test void andAndEq()  { compare("let x = 1; x &&= 7; x", "7"); }
    @Test void nullishEq() { compare("let x = null; x ??= 7; x", "7"); }

    @Test void compoundOnObject() {
        compare("let o = {n: 5}; o.n += 3; o.n", "8");
    }
    @Test void compoundOnArray() {
        compare("let a = [1, 2, 3]; a[1] += 10; a[1]", "12");
    }

    // ---------- new expressions ----------
    @Test void simpleNew() {
        compare(
            "function Point(x, y) { this.x = x; this.y = y; }" +
            "let p = new Point(3, 4); p.x + p.y",
            "7");
    }
    @Test void newWithMethod() {
        compare(
            "function Counter() { this.n = 0; }" +
            "let c = new Counter(); c.n = c.n + 1; c.n = c.n + 1; c.n",
            "2");
    }

    // ---------- Closures ----------
    @Test void simpleClosure() {
        compare(
            "function makeCounter() {" +
            "  let n = 0;" +
            "  return function() { n = n + 1; return n; };" +
            "}" +
            "let c = makeCounter(); c(); c(); c()",
            "3");
    }
    @Test void closureWithMutation() {
        compare(
            "function counter() { let n = 0; return () => { n = n + 1; return n; }; }" +
            "let c = counter(); c(); c(); c(); c()",
            "4");
    }
    @Test void multipleClosuresShare() {
        compare(
            "function make() {" +
            "  let n = 10;" +
            "  function inc() { n = n + 1; }" +
            "  function read() { return n; }" +
            "  return { inc: inc, read: read };" +
            "}" +
            "let o = make(); o.inc(); o.inc(); o.read()",
            "12");
    }
    @Test void capturedAcrossArgs() {
        compare(
            "function adder(x) { return y => x + y; }" +
            "let add5 = adder(5); add5(10)",
            "15");
    }
    @Test void deepCapture() {
        compare(
            "function a() { let n = 1; return function b() { return function c() { return n + 100; }; }; }" +
            "a()()()",
            "101");
    }

    // ---------- Classes ----------
    @Test void simpleClass() {
        compare(
            "class Point { constructor(x, y) { this.x = x; this.y = y; } }" +
            "let p = new Point(3, 4); p.x + p.y",
            "7");
    }
    @Test void classWithMethod() {
        compare(
            "class Point {" +
            "  constructor(x, y) { this.x = x; this.y = y; }" +
            "  norm() { return this.x + this.y; }" +
            "}" +
            "let p = new Point(3, 4); p.norm()",
            "7");
    }
    @Test void classMultipleInstances() {
        compare(
            "class Counter {" +
            "  constructor() { this.n = 0; }" +
            "  inc() { this.n = this.n + 1; }" +
            "  read() { return this.n; }" +
            "}" +
            "let a = new Counter(); let b = new Counter();" +
            "a.inc(); a.inc(); a.inc(); b.inc();" +
            "a.read() + b.read()",
            "4");
    }
    @Test void classNoConstructor() {
        compare(
            "class Box { put(v) { this.value = v; } get() { return this.value; } }" +
            "let b = new Box(); b.put(42); b.get()",
            "42");
    }

    // ---------- Destructuring ----------
    @Test void objDestructure()  { compare("let {a, b} = {a: 1, b: 2}; a + b", "3"); }
    @Test void objDestructureRename() {
        compare("let {a: x, b: y} = {a: 1, b: 2}; x + y", "3");
    }
    @Test void objDestructureNested() {
        compare("let {a: {b}} = {a: {b: 5}}; b", "5");
    }
    @Test void arrayDestructure() { compare("let [x, y] = [10, 20]; x + y", "30"); }
    @Test void arrayDestructureSkip() {
        compare("let [, , z] = [1, 2, 3]; z", "3");
    }
    @Test void destructureDefault() {
        compare("let {a = 5, b = 10} = {a: 1}; a + b", "11");
    }
    @Test void destructureMixed() {
        compare("let {pos: [x, y]} = {pos: [3, 4]}; x + y", "7");
    }
    @Test void paramDestructure() {
        compare("function dist({x, y}) { return x + y; } dist({x: 4, y: 5})", "9");
    }
    @Test void paramArrayDestructure() {
        compare("function sum([a, b, c]) { return a + b + c; } sum([1, 2, 3])", "6");
    }
    @Test void paramDefault() {
        compare("function f({n = 7}) { return n; } f({})", "7");
    }
    @Test void destructureAssign() {
        compare("let a, b; [a, b] = [3, 4]; a + b", "7");
    }

    // ---------- Spread / rest ----------
    @Test void arraySpread()       { compare("let a = [2, 3]; let b = [1, ...a, 4]; b[0] + b[1] + b[2] + b[3]", "10"); }
    @Test void arrayConcatViaSpread() {
        compare("let a = [1, 2]; let b = [3, 4]; let c = [...a, ...b]; c.length", "4");
    }
    @Test void callSpread() {
        compare("function add(a, b, c) { return a + b + c; } let xs = [10, 20, 30]; add(...xs)", "60");
    }
    @Test void callMixedSpread() {
        compare("function f(a, b, c, d) { return a + b + c + d; } let xs = [2, 3]; f(1, ...xs, 4)", "10");
    }
    @Test void restParam() {
        compare("function sum(...xs) { let s = 0; for (let i = 0; i < xs.length; i++) s = s + xs[i]; return s; }" +
                "sum(1, 2, 3, 4, 5)", "15");
    }
    @Test void restWithFixed() {
        compare("function f(a, b, ...rest) { return a + b + rest.length; } f(1, 2, 3, 4, 5)", "6");
    }

    // ---------- for-of / for-in ----------
    @Test void forOfArray() {
        compare("let a = [1, 2, 3, 4]; let s = 0; for (let x of a) s = s + x; s", "10");
    }
    @Test void forOfString() {
        compare("let s = 0; for (let c of \"abc\") s = s + 1; s", "3");
    }
    @Test void forOfWithBreak() {
        compare("let n = 0; for (let x of [1,2,3,4,5]) { if (x === 3) break; n = n + x; } n", "3");
    }
    @Test void forInObject() {
        compare(
            "let o = {a: 1, b: 2, c: 3}; let s = 0;" +
            "for (let k in o) s = s + o[k]; s", "6");
    }
    @Test void forInDestructure() {
        compare(
            "let pairs = [[\"a\", 1], [\"b\", 2]];" +
            "let s = 0; for (let [k, v] of pairs) s = s + v; s", "3");
    }

    // ---------- extends / super ----------
    @Test void simpleExtends() {
        compare(
            "class Animal { constructor(name) { this.name = name; } }" +
            "class Dog extends Animal { constructor(name) { super(name); this.kind = \"dog\"; } }" +
            "let d = new Dog(\"Rex\"); d.name + \"-\" + d.kind",
            "\"Rex-dog\"");
    }
    @Test void inheritedMethod() {
        compare(
            "class A { greet() { return \"hi\"; } }" +
            "class B extends A {}" +
            "let b = new B(); b.greet()",
            "\"hi\"");
    }
    @Test void overriddenMethod() {
        compare(
            "class A { greet() { return \"a\"; } }" +
            "class B extends A { greet() { return \"b\"; } }" +
            "let b = new B(); b.greet()",
            "\"b\"");
    }

    // ---------- Default params, computed keys, object spread, labeled jumps ----------
    @Test void simpleDefault()       { compare("function f(x = 7) { return x; } f()", "7"); }
    @Test void defaultProvided()     { compare("function f(x = 7) { return x; } f(99)", "99"); }
    @Test void defaultUsesEarlier()  { compare("function f(a, b = a + 1) { return b; } f(5)", "6"); }

    @Test void computedKey() {
        compare("let k = \"foo\"; let o = {[k]: 42}; o.foo", "42");
    }
    @Test void computedKeyExpr() {
        compare("let o = {[1 + 2]: \"three\"}; o[3]", "\"three\"");
    }

    @Test void objectSpread() {
        compare("let a = {x: 1}; let b = {...a, y: 2}; b.x + b.y", "3");
    }
    @Test void objectSpreadOverride() {
        compare("let a = {x: 1, y: 2}; let b = {...a, x: 10}; b.x + b.y", "12");
    }

    @Test void labeledBreak() {
        compare(
            "let r = 0;" +
            "outer: for (let i = 0; i < 5; i++) {" +
            "  for (let j = 0; j < 5; j++) {" +
            "    if (i + j > 4) { r = i * 10 + j; break outer; }" +
            "  }" +
            "} r",
            "14");
    }
    @Test void labeledContinue() {
        compare(
            "let r = 0;" +
            "outer: for (let i = 0; i < 3; i++) {" +
            "  for (let j = 0; j < 3; j++) {" +
            "    if (j === 1) continue outer;" +
            "    r = r + 1;" +
            "  }" +
            "} r",
            "3");
    }

    // ---------- Optional chaining ----------
    @Test void optionalMember()    { compare("let o = {a: 1}; o?.a", "1"); }
    @Test void optionalNullish()   { compare("let o = null; o?.a", "undefined"); }
    @Test void optionalUndefined() { compare("let o; o?.a", "undefined"); }
    @Test void optionalNested()    { compare("let o = {a: {b: 5}}; o?.a?.b", "5"); }
    @Test void optionalShortCircuit() {
        compare("let o = null; o?.a.b.c", "undefined");
    }
    @Test void optionalCall()      { compare("let f = (x) => x + 1; f?.(7)", "8"); }
    @Test void optionalCallNull()  { compare("let f = null; f?.()", "undefined"); }
    @Test void optionalIndex()     { compare("let a = [10, 20]; a?.[1]", "20"); }
    @Test void optionalIndexNull() { compare("let a = null; a?.[1]", "undefined"); }

    // ---------- Class extras: static, fields, private ----------
    @Test void staticMethod() {
        compare(
            "class Math2 { static square(n) { return n * n; } }" +
            "Math2.square(7)",
            "49");
    }
    @Test void staticField() {
        compare(
            "class C { static answer = 42; }" +
            "C.answer",
            "42");
    }
    @Test void instanceField() {
        compare(
            "class C { x = 5; getX() { return this.x; } }" +
            "let c = new C(); c.getX()",
            "5");
    }
    @Test void instanceFieldOverridable() {
        compare(
            "class C { x = 0; }" +
            "let c = new C(); c.x = 9; c.x",
            "9");
    }
    @Test void privateField() {
        compare(
            "class Counter { #n = 0; bump() { this.#n = this.#n + 1; } read() { return this.#n; } }" +
            "let c = new Counter(); c.bump(); c.bump(); c.read()",
            "2");
    }
    @Test void mixedClass() {
        compare(
            "class Box {" +
            "  static empty() { return new Box(0); }" +
            "  size = 1;" +
            "  constructor(n) { this.n = n; }" +
            "  total() { return this.size * this.n; }" +
            "}" +
            "let b = Box.empty(); b.total()",
            "0");
    }

    // ---------- Object-rest destructuring ----------
    @Test void objectRestSimple() {
        compare("let {a, ...rest} = {a: 1, b: 2, c: 3}; rest.b + rest.c", "5");
    }
    @Test void objectRestEmpty() {
        compare("let {a, ...rest} = {a: 1}; let s = 0; for (let k in rest) s = s + 1; s", "0");
    }

    // ---------- Template literals ----------
    @Test void templateSimple() {
        compare("let x = 7; `value=${x}`", "\"value=7\"");
    }
    @Test void templateMultiple() {
        compare("let a = 1; let b = 2; `${a} + ${b} = ${a + b}`", "\"1 + 2 = 3\"");
    }
    @Test void templateNoExpressions() {
        compare("`hello world`", "\"hello world\"");
    }
    @Test void templateInExpression() {
        compare("function greet(n) { return `Hello, ${n}!`; } greet(\"world\")", "\"Hello, world!\"");
    }

    // ---------- Getters / setters ----------
    @Test void objectGetter() {
        compare("let o = {get x() { return 7; }}; o.x", "7");
    }
    @Test void objectGetterUsesThis() {
        compare("let o = {n: 5, get x() { return this.n * 2; }}; o.x", "10");
    }
    @Test void objectSetter() {
        compare(
            "let o = {_n: 0, set x(v) { this._n = v; }};" +
            "o.x = 42; o._n",
            "42");
    }
    @Test void objectGetSet() {
        compare(
            "let o = {_n: 0, get x() { return this._n; }, set x(v) { this._n = v; }};" +
            "o.x = 9; o.x",
            "9");
    }
    @Test void classGetter() {
        compare(
            "class P { constructor(x, y) { this.x = x; this.y = y; } get norm() { return this.x + this.y; } }" +
            "let p = new P(3, 4); p.norm",
            "7");
    }
    @Test void classSetter() {
        compare(
            "class C { constructor() { this._n = 0; } set v(n) { this._n = n * 2; } get v() { return this._n; } }" +
            "let c = new C(); c.v = 21; c.v",
            "42");
    }

    // ---------- new.target ----------
    @Test void newTargetUndefinedInPlainCall() {
        compare("function f() { return new.target === undefined; } f()", "true");
    }
    @Test void newTargetIsConstructor() {
        compare(
            "function F() { this.invokedWithNew = (new.target !== undefined); }" +
            "let a = new F(); a.invokedWithNew",
            "true");
    }

    // ---------- Regex literal (compiles, structure is exposed) ----------
    @Test void regexHasSource() {
        compare("let r = /abc/g; r.source", "\"abc\"");
    }
    @Test void regexHasFlags() {
        compare("let r = /xyz/im; r.flags", "\"im\"");
    }
}
