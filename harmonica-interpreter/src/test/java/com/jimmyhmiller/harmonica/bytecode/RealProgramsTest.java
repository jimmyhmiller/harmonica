package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke tests that exercise the standard library — parse + generate
 * + interpret real programs with Math/Array/String/etc. and verify the result
 * directly (no LibJS oracle needed).
 */
class RealProgramsTest {

    private static Object run(String source) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        Object result = Interpreter.interpret(exe, new Object[0], 64);
        // ConsString is an implementation detail; flatten for test
        // comparison so assertEquals("hi", result) doesn't trip on
        // String.equals(ConsString) being asymmetric.
        if (result instanceof ConsString cs) return cs.toString();
        return result;
    }

    private static String fmt(Object v) {
        if (v == null) return "null";
        if (v == Undefined.VALUE) return "undefined";
        if (v instanceof Double d) {
            if (d == d.longValue() && !d.isInfinite()) return Long.toString(d.longValue());
            return Double.toString(d);
        }
        if (v instanceof Boolean b) return b.toString();
        return v.toString();
    }

    // ---------- Math ----------

    @Test void mathBasic() {
        assertEquals(3.14159, (Double) run("Math.PI"), 0.0001);
        assertEquals(5.0, run("Math.sqrt(25)"));
        assertEquals(8.0, run("Math.pow(2, 3)"));
        assertEquals(3.0, run("Math.floor(3.7)"));
        assertEquals(4.0, run("Math.ceil(3.2)"));
        assertEquals(4.0, run("Math.round(3.5)"));
        assertEquals(5.0, run("Math.abs(-5)"));
        assertEquals(7.0, run("Math.max(1, 7, 3)"));
        assertEquals(1.0, run("Math.min(1, 7, 3)"));
    }

    // ---------- String prototype ----------

    @Test void stringMethods() {
        assertEquals("HELLO", run("'hello'.toUpperCase()"));
        assertEquals("world", run("'WORLD'.toLowerCase()"));
        assertEquals(2.0, run("'hello'.indexOf('ll')"));
        assertEquals("ell", run("'hello'.slice(1, 4)"));
        assertEquals("hihi", run("'hi'.repeat(2)"));
        assertEquals(true, run("'hello'.includes('ell')"));
        assertEquals(true, run("'hello'.startsWith('he')"));
        assertEquals(true, run("'hello'.endsWith('lo')"));
        assertEquals("  hi  ", run("'  hi  '.toString()"));
        assertEquals("hi", run("'  hi  '.trim()"));
    }

    @Test void stringSplit() {
        Object out = run("'a,b,c'.split(',')");
        assertTrue(out instanceof JSArray);
        JSArray arr = (JSArray) out;
        assertEquals(3, arr.length());
        assertEquals("a", arr.get(0));
        assertEquals("b", arr.get(1));
        assertEquals("c", arr.get(2));
    }

    // ---------- Array prototype ----------

    @Test void arrayBasic() {
        assertEquals(3.0, run("[1,2,3].length"));
        assertEquals(4.0, run("let a = [1,2,3]; a.push(4); a.length"));
        assertEquals(2.0, run("let a = [1,2,3]; a.pop(); a.length"));
        assertEquals(3.0, run("let a = [1,2,3]; a.pop()"));
        assertEquals("1,2,3", run("[1,2,3].join(',')"));
        assertEquals(true, run("[1,2,3].includes(2)"));
        assertEquals(1.0, run("[1,2,3].indexOf(2)"));
    }

    @Test void arrayMapFilterReduce() {
        Object out = run("[1,2,3,4].map(x => x * 2)");
        assertTrue(out instanceof JSArray);
        JSArray arr = (JSArray) out;
        assertEquals(4, arr.length());
        assertEquals(2.0, arr.get(0));
        assertEquals(8.0, arr.get(3));

        Object filtered = run("[1,2,3,4,5].filter(x => x > 2)");
        assertTrue(filtered instanceof JSArray);
        assertEquals(3, ((JSArray) filtered).length());

        assertEquals(15.0, run("[1,2,3,4,5].reduce((acc, x) => acc + x, 0)"));
        assertEquals(120.0, run("[1,2,3,4,5].reduce((a, b) => a * b)"));
    }

    @Test void arraySort() {
        Object out = run("[3, 1, 4, 1, 5, 9, 2, 6].sort((a, b) => a - b)");
        assertTrue(out instanceof JSArray);
        JSArray arr = (JSArray) out;
        assertEquals(1.0, arr.get(0));
        assertEquals(9.0, arr.get(7));
    }

    @Test void arrayFlatten() {
        Object out = run("[[1,2],[3,4]].flat()");
        assertTrue(out instanceof JSArray);
        assertEquals(4, ((JSArray) out).length());
    }

    // ---------- Object methods ----------

    @Test void objectKeys() {
        Object out = run("Object.keys({a:1, b:2, c:3})");
        assertTrue(out instanceof JSArray);
        JSArray arr = (JSArray) out;
        assertEquals(3, arr.length());
        assertEquals("a", arr.get(0));
        assertEquals("b", arr.get(1));
        assertEquals("c", arr.get(2));
    }

    @Test void objectAssign() {
        Object out = run("Object.assign({a: 1}, {b: 2}, {c: 3})");
        assertTrue(out instanceof JSObject);
        JSObject obj = (JSObject) out;
        assertEquals(1.0, obj.get("a"));
        assertEquals(2.0, obj.get("b"));
        assertEquals(3.0, obj.get("c"));
    }

    @Test void arrayIsArray() {
        assertEquals(true, run("Array.isArray([1,2,3])"));
        assertEquals(false, run("Array.isArray({a:1})"));
        assertEquals(false, run("Array.isArray('hello')"));
    }

    // ---------- JSON ----------

    @Test void jsonRoundTrip() {
        assertEquals("{\"a\":1,\"b\":[2,3]}", run("JSON.stringify({a: 1, b: [2, 3]})"));
        Object out = run("JSON.parse('{\"x\":1,\"y\":[2,3]}')");
        assertTrue(out instanceof JSObject);
        JSObject obj = (JSObject) out;
        assertEquals(1.0, obj.get("x"));
        assertTrue(obj.get("y") instanceof JSArray);
    }

    // ---------- Number conversions ----------

    @Test void parseAndFormat() {
        assertEquals(42.0, run("parseInt('42')"));
        assertEquals(255.0, run("parseInt('ff', 16)"));
        assertEquals(3.14, (Double) run("parseFloat('3.14')"), 0.0001);
        assertEquals(true, run("isNaN(NaN)"));
        assertEquals(false, run("isFinite(Infinity)"));
        assertEquals("3.14", run("(3.14159).toFixed(2)"));
        assertEquals("ff", run("(255).toString(16)"));
    }

    // ---------- Programs that do real things ----------

    @Test void fibonacci() {
        // Both recursive and iterative.
        assertEquals(55.0, run("function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); } fib(10)"));
        assertEquals(55.0, run(
            "function fib(n) { let a = 0, b = 1; while (n-- > 0) { let t = a + b; a = b; b = t; } return a; }" +
            " fib(10)"));
    }

    @Test void wordFrequency() {
        Object out = run(
            "let text = 'the quick brown fox jumps over the lazy dog the fox';" +
            "let words = text.split(' ');" +
            "let freq = {};" +
            "for (let i = 0; i < words.length; i++) {" +
            "  let w = words[i];" +
            "  freq[w] = (freq[w] || 0) + 1;" +
            "}" +
            "freq.the");
        assertEquals(3.0, out);
    }

    @Test void pipelineComputation() {
        // Sum of squares of even numbers from 1..10
        assertEquals(220.0, run(
            "let nums = [];" +
            "for (let i = 1; i <= 10; i++) nums.push(i);" +
            "nums.filter(n => n % 2 === 0).map(n => n * n).reduce((a, b) => a + b, 0)"));
    }

    @Test void closureCounter() {
        assertEquals(3.0, run(
            "function makeCounter() {" +
            "  let n = 0;" +
            "  return function() { n = n + 1; return n; };" +
            "}" +
            "let c = makeCounter();" +
            "c(); c(); c()"));
    }

    @Test void classBasic() {
        assertEquals(50.0, run(
            "class Rect {" +
            "  constructor(w, h) { this.w = w; this.h = h; }" +
            "  area() { return this.w * this.h; }" +
            "}" +
            "let r = new Rect(5, 10);" +
            "r.area()"));
    }

    @Test void higherOrderCompose() {
        assertEquals(11.0, run(
            "let compose = (f, g) => x => f(g(x));" +
            "let addOne = x => x + 1;" +
            "let double = x => x * 2;" +
            "compose(addOne, double)(5)"));
    }

    @Test void classInheritance() {
        assertEquals("Rex-dog", run(
            "class Animal { constructor(name) { this.name = name; } }" +
            "class Dog extends Animal { constructor(name) { super(name); this.kind = \"dog\"; } }" +
            "let d = new Dog(\"Rex\"); d.name + \"-\" + d.kind"));
    }

    @Test void inheritedMethods() {
        assertEquals("hi from B", run(
            "class A { greet() { return \"hi from \" + this.name; } }" +
            "class B extends A { constructor() { super(); this.name = \"B\"; } }" +
            "new B().greet()"));
    }

    @Test void instanceofOperator() {
        assertEquals(true,  run("class A {} new A() instanceof A"));
        assertEquals(false, run("class A {} class B {} new A() instanceof B"));
        assertEquals(true,  run(
            "class A {} class B extends A {} new B() instanceof A"));
    }

    @Test void inOperator() {
        assertEquals(true,  run("'a' in {a: 1}"));
        assertEquals(false, run("'b' in {a: 1}"));
        assertEquals(true,  run("'length' in [1,2,3]"));
        assertEquals(true,  run("0 in [1,2,3]"));
        assertEquals(false, run("5 in [1,2,3]"));
    }

    @Test void objectLiteralGetter() {
        assertEquals(7.0, run("let o = {get x() { return 7; }}; o.x"));
        assertEquals(10.0, run(
            "let o = {n: 5, get x() { return this.n * 2; }}; o.x"));
    }

    @Test void objectLiteralSetter() {
        assertEquals(42.0, run(
            "let o = {_n: 0, set x(v) { this._n = v; }}; o.x = 42; o._n"));
    }

    @Test void classStaticField() {
        assertEquals(42.0, run("class C { static answer = 42; } C.answer"));
        assertEquals("hello", run("class C { static greeting = \"hello\"; } C.greeting"));
    }

    @Test void classGetSet() {
        assertEquals(20.0, run(
            "class Box { constructor(v) { this._v = v; } get v() { return this._v * 2; } }" +
            "new Box(10).v"));
    }

    @Test void tryCatchWithError() {
        assertEquals("oops", run(
            "try { throw new Error('oops'); } catch (e) { e.message }"));
        assertEquals("TypeError", run(
            "try { throw new TypeError('bad'); } catch (e) { e.name }"));
    }

    @Test void tryCatchRethrow() {
        assertEquals(7.0, run(
            "function f() { try { throw 7; } catch (e) { return e; } }" +
            "f()"));
    }

    @Test void numberStatics() {
        assertEquals(true,  run("Number.isInteger(5)"));
        assertEquals(false, run("Number.isInteger(5.5)"));
        assertEquals(true,  run("Number.isFinite(42)"));
        assertEquals(false, run("Number.isFinite(Infinity)"));
        assertEquals(true,  run("Number.isSafeInteger(9007199254740991)"));
        assertEquals(9007199254740991.0, run("Number.MAX_SAFE_INTEGER"));
    }

    @Test void stringFromCharCode() {
        assertEquals("Hi", run("String.fromCharCode(72, 105)"));
        assertEquals("ABC", run("String.fromCharCode(65, 66, 67)"));
    }

    @Test void arrayFlatMap() {
        Object out = run("[1, 2, 3].flatMap(x => [x, x * 10])");
        assertTrue(out instanceof JSArray);
        JSArray arr = (JSArray) out;
        assertEquals(6, arr.length());
        assertEquals(1.0, arr.get(0));
        assertEquals(10.0, arr.get(1));
        assertEquals(30.0, arr.get(5));
    }

    @Test void arrayFill() {
        Object out = run("[1, 2, 3, 4].fill(0, 1, 3)");
        assertTrue(out instanceof JSArray);
        JSArray arr = (JSArray) out;
        assertEquals(1.0, arr.get(0));
        assertEquals(0.0, arr.get(1));
        assertEquals(0.0, arr.get(2));
        assertEquals(4.0, arr.get(3));
    }

    @Test void shoppingCart() {
        // A small realistic OOP program.
        assertEquals(35.0, run(
            "class Item {" +
            "  constructor(name, price, qty) { this.name = name; this.price = price; this.qty = qty; }" +
            "  total() { return this.price * this.qty; }" +
            "}" +
            "class Cart {" +
            "  constructor() { this.items = []; }" +
            "  add(item) { this.items.push(item); }" +
            "  total() { return this.items.reduce((acc, i) => acc + i.total(), 0); }" +
            "}" +
            "let cart = new Cart();" +
            "cart.add(new Item(\"apple\", 1.5, 10));" +
            "cart.add(new Item(\"bread\", 4, 5));" +
            "cart.total()"));
    }
}
