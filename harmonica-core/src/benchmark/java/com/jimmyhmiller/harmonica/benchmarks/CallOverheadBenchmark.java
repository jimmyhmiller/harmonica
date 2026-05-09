package com.jimmyhmiller.harmonica.benchmarks;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.Executable;
import com.jimmyhmiller.harmonica.bytecode.Generator;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSObject;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/**
 * Isolated function-call overhead micro-bench.
 *
 * <p>Each variant runs an N-iteration loop. The "loop" baseline does the same
 * loop with no call, so subtracting it gives the per-call cost.
 * Same source string is used for harmonica and Rhino — the only thing that
 * varies between rows is which engine runs it.
 */
public final class CallOverheadBenchmark {

    private static final int N = 5_000_000;

    /** No-op loop — measures `for` overhead alone. */
    private static final String LOOP_BASELINE =
        "var n = N; var k = 0; for (var i = 0; i < n; i++) k++;";

    /** Loop calling a 0-arg function once per iter. */
    private static final String CALL0 =
        "function f() { return 0; }\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += f();";

    /** Loop calling a 1-arg function once per iter. */
    private static final String CALL1 =
        "function f(x) { return x; }\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += f(i);";

    /** Loop calling a 2-arg function once per iter. */
    private static final String CALL2 =
        "function f(x, y) { return x + y; }\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += f(i, 1);";

    /** Loop calling a 3-arg function once per iter. */
    private static final String CALL3 =
        "function f(x, y, z) { return x + y + z; }\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += f(i, 1, 2);";

    /** Loop calling a 5-arg function (forces args[] allocation in real engines too). */
    private static final String CALL5 =
        "function f(a, b, c, d, e) { return a + b + c + d + e; }\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += f(i, 1, 2, 3, 4);";

    /** Loop reading one own property from a fresh object via dot notation. */
    private static final String GET_BY_ID =
        "var o = {a: 1, b: 2, c: 3, d: 4, e: 5};\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += o.c;";

    /** Loop reading via [computed key] — string-key index. */
    private static final String GET_BY_STR =
        "var o = {a: 1, b: 2, c: 3, d: 4, e: 5}; var k = 'c';\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += o[k];";

    /** Loop reading inherited prototype method (proto-chain walk). */
    private static final String GET_VIA_PROTO =
        "function P(){} P.prototype.x = 42; var o = new P();\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += o.x;";

    /** Loop indexing a small array. */
    private static final String GET_ARR_IDX =
        "var arr = [10, 20, 30, 40, 50];\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += arr[i % 5];";

    /** Loop calling a method on an object (object-method call). */
    private static final String METHOD_CALL =
        "var o = {f: function(x) { return x; }};\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += o.f(i);";

    /** Loop reading a closure-captured local from the inner function. */
    private static final String CLOSURE_CALL =
        "function make() { var c = 7; return function(x) { return x + c; }; }\n" +
        "var fn = make();\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += fn(i);";

    /** Variadic function that reads `arguments.length` — forces materialization. */
    private static final String ARGUMENTS_LEN =
        "function f() { return arguments.length; }\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += f(i, 1, 2);";

    /** Variadic function that reads `arguments[0]` — lodash style. */
    private static final String ARGUMENTS_IDX =
        "function f() { return arguments[0] + arguments[1]; }\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += f(i, 1);";

    /** apply() pattern — one of lodash's hot wrappers. */
    private static final String APPLY_CALL =
        "function f(x, y) { return x + y; }\n" +
        "var arr = [1, 2];\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) s += f.apply(null, arr);";

    /** Higher-order pattern: arr.forEach(fn). Uses our Array.prototype.forEach. */
    private static final String FOREACH =
        "var arr = [];\n" +
        "for (var i = 0; i < 1000; i++) arr.push(i);\n" +
        "var s = 0;\n" +
        "var n = N / 1000;\n" +
        "for (var i = 0; i < n; i++) arr.forEach(function(v) { s += v; });";

    /** arr.map(fn) — lodash's `_.map` over a native array works similarly. */
    private static final String MAP =
        "var arr = [];\n" +
        "for (var i = 0; i < 100; i++) arr.push(i);\n" +
        "var n = N / 100;\n" +
        "for (var i = 0; i < n; i++) arr.map(function(v) { return v + 1; });";

    /** typeof + property check — lodash uses this everywhere for guarding. */
    private static final String TYPEOF_GUARD =
        "function isFn(v) { return typeof v === 'function'; }\n" +
        "function f() { return 0; }\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) if (isFn(f)) s++;";

    /** hasOwnProperty.call(o, k) — lodash uses this in baseEach / baseForOwn. */
    private static final String HAS_OWN =
        "var hop = Object.prototype.hasOwnProperty;\n" +
        "var o = {a:1, b:2, c:3};\n" +
        "var n = N; var s = 0; for (var i = 0; i < n; i++) if (hop.call(o, 'b')) s++;";

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("=== Function-call overhead micro-bench: harmonica vs Rhino (-opt -1, ES6) ===");
        System.out.printf("JVM: %s %s    N=%d iterations%n",
            System.getProperty("java.vm.name"), System.getProperty("java.version"), N);
        System.out.println();
        System.out.printf("%-22s  %-14s  %-14s  %-14s  %-12s%n",
            "case", "harmonica", "Rhino -1", "ratio", "ns/call");
        System.out.printf("%-22s  %-14s  %-14s  %-14s  %-12s%n",
            "----------------------", "--------------", "--------------", "--------------", "------------");

        runRow("loop baseline (no call)", LOOP_BASELINE);
        runRow("call f()      (0 args)", CALL0);
        runRow("call f(i)     (1 arg)",  CALL1);
        runRow("call f(i,1)   (2 args)", CALL2);
        runRow("call f(i,1,2) (3 args)", CALL3);
        runRow("call f(...5)  (5 args)", CALL5);
        runRow("o.c (GetById)        ",  GET_BY_ID);
        runRow("o[k] (GetByValue str)",  GET_BY_STR);
        runRow("o.x via proto chain  ",  GET_VIA_PROTO);
        runRow("arr[i%5] (idx access)",  GET_ARR_IDX);
        runRow("o.f(i) method call    ", METHOD_CALL);
        runRow("closure capture call  ", CLOSURE_CALL);
        runRow("arguments.length      ", ARGUMENTS_LEN);
        runRow("arguments[0]+args[1]  ", ARGUMENTS_IDX);
        runRow("f.apply(null, [1,2])  ", APPLY_CALL);
        runRow("arr.forEach(fn) (1k)  ", FOREACH);
        runRow("arr.map(fn)     (100) ", MAP);
        runRow("typeof v==='function' ", TYPEOF_GUARD);
        runRow("hop.call(o, 'b')      ", HAS_OWN);
    }

    private static void runRow(String label, String body) throws Exception {
        // Substitute N at the source level so both engines see a literal —
        // avoids any difference in how globals are resolved.
        String source = body.replace("N", String.valueOf(N));

        // Warmup
        try { runHarm(source); } catch (Throwable t) { System.err.println("[warmup harm] " + t); }
        try { runRhino(source); } catch (Throwable t) { System.err.println("[warmup rhino] " + t); }

        double harm = bestOf(5, () -> { try { runHarm(source); } catch (Throwable t) { throw new RuntimeException(t); } });
        double rhin = bestOf(5, () -> { try { runRhino(source); } catch (Throwable t) { throw new RuntimeException(t); } });

        String harmS = harm < 0 ? "fail" : String.format("%9.1f ms", harm);
        String rhinS = rhin < 0 ? "fail" : String.format("%9.1f ms", rhin);
        String ratio = (harm > 0 && rhin > 0)
            ? String.format("%.2fx %s", harm < rhin ? rhin/harm : harm/rhin, harm < rhin ? "faster" : "slower")
            : "—";
        // ns/call relative to loop baseline isn't done per-row here (would need
        // baseline subtraction); leave it as raw ns/iteration on harmonica.
        String nsPerCall = harm < 0 ? "—" : String.format("%6.1f ns", harm * 1_000_000.0 / N);
        System.out.printf("%-22s  %-14s  %-14s  %-14s  %-12s%n", label, harmS, rhinS, ratio, nsPerCall);
    }

    private static void runHarm(String source) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        Interpreter.interpret(exe, new Object[0], 64);
    }

    private static void runRhino(String source) {
        Context ctx = Context.enter();
        try {
            ctx.setOptimizationLevel(-1);
            ctx.setLanguageVersion(Context.VERSION_ES6);
            Scriptable scope = ctx.initStandardObjects();
            ctx.evaluateString(scope, source, "<bench>", 1, null);
        } finally {
            Context.exit();
        }
    }

    private static double bestOf(int iters, Runnable r) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            try { r.run(); }
            catch (RuntimeException ex) {
                if (ex.getCause() instanceof AbruptCompletion ac) {
                    if (ac.value() instanceof JSObject jo) {
                        System.err.println("[harm AC] " + jo.get("name") + ": " + jo.get("message"));
                    } else {
                        System.err.println("[harm AC] " + ac.value());
                    }
                } else {
                    System.err.println("[error] " + ex);
                }
                return -1;
            } catch (Throwable t) {
                System.err.println("[error] " + t);
                return -1;
            }
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            if (ms < best) best = ms;
        }
        return best;
    }
}
