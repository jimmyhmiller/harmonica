package com.jimmyhmiller.harmonica.benchmarks;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.bytecode.Executable;
import com.jimmyhmiller.harmonica.bytecode.Generator;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/**
 * Compare end-to-end script execution between harmonica's interpreter and
 * Mozilla Rhino with optimizationLevel = -1 (pure interpreter, no Java
 * bytecode compilation). Both are tree-walking-style Java interpreters, so
 * this is the apples-to-apples comparison.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn -pl harmonica-core -P benchmark compile dependency:build-classpath \
 *   -Dmdep.outputFile=/tmp/cp.txt -q && \
 * java -cp "$(cat /tmp/cp.txt):harmonica-core/target/classes" \
 *   com.jimmyhmiller.harmonica.benchmarks.RhinoComparisonBenchmark
 * }</pre>
 */
public final class RhinoComparisonBenchmark {

    private RhinoComparisonBenchmark() {}

    record Bench(String label, String source, int iters) {}

    public static void main(String[] args) {
        Bench[] benches = new Bench[] {
            new Bench("fib(20) recursive",
                "function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); } fib(20)", 10),
            new Bench("fib(25) recursive",
                "function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); } fib(25)", 5),
            new Bench("fib(28) recursive",
                "function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); } fib(28)", 5),
            new Bench("fib(1000) iterative",
                "function fib(n) { let a = 0, b = 1; while (n-- > 0) { let t = a + b; a = b; b = t; } return a; } fib(1000)",
                20),
            new Bench("sum 1..1_000_000 (while)",
                "let n = 0; let i = 1; while (i <= 1000000) { n = n + i; i = i + 1; } n", 5),
            new Bench("filter+map+reduce x1000",
                "let xs = []; for (let i = 1; i <= 1000; i++) xs.push(i);" +
                " xs.filter(n => n % 2 === 0).map(n => n * n).reduce((a, b) => a + b, 0)", 20),
            // Function-based "class" so Rhino (no class syntax) can run it too.
            new Bench("Box (proto-style); sum 1..10000",
                "function Box(v) { this.v = v; }" +
                " Box.prototype.get = function() { return this.v; };" +
                " var s = 0; for (var i = 1; i <= 10000; i++) s = s + new Box(i).get(); s", 10),
            new Bench("100k closure ticks",
                "function mk() { let n = 0; return () => ++n; } let c = mk();" +
                " for (let i = 0; i < 100000; i++) c(); c() - 1", 10),
            new Bench("object lit + property reads x100k",
                "let s = 0; for (let i = 0; i < 100000; i++) { let o = {a: i, b: i * 2}; s = s + o.a + o.b; } s",
                10),
            new Bench("string concat x10000",
                "let s = ''; for (let i = 0; i < 10000; i++) s = s + 'x'; s.length", 10),
            new Bench("JSON stringify+parse x1000",
                "let obj = {a: 1, b: [2, 3, 4], c: 'hi'};" +
                " for (let i = 0; i < 1000; i++) obj = JSON.parse(JSON.stringify(obj));" +
                " obj.a", 10),
        };

        System.out.println();
        System.out.println("=== harmonica (interpreter) vs Rhino (-opt -1) ===");
        System.out.printf("JVM: %s %s%n",
            System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.println();
        System.out.printf("%-40s  %-12s  %-12s  %-8s%n",
            "benchmark", "harmonica", "Rhino -1", "ratio");
        System.out.printf("%-40s  %-12s  %-12s  %-8s%n",
            "----------------------------------------", "------------", "------------", "--------");

        for (Bench b : benches) {
            // Warmup once per engine for JIT / parser caches.
            try { runHarmonica(b.source); } catch (Throwable ignored) {}
            try { runRhino(b.source); } catch (Throwable ignored) {}

            double harm = bestOf(b.iters, () -> runHarmonica(b.source));
            double rhin = bestOf(b.iters, () -> runRhino(b.source));

            String harmStr = harm < 0 ? "fail" : String.format("%8.2f ms", harm);
            String rhinStr = rhin < 0 ? "fail" : String.format("%8.2f ms", rhin);
            String ratio;
            if (harm < 0 || rhin < 0) ratio = "-";
            else if (harm < rhin) ratio = String.format("%.2fx faster", rhin / harm);
            else                  ratio = String.format("%.2fx slower", harm / rhin);

            System.out.printf("%-40s  %-12s  %-12s  %-8s%n",
                b.label, harmStr, rhinStr, ratio);
        }
        System.out.println();
    }

    private static double bestOf(int iters, Runnable r) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            try { r.run(); }
            catch (Throwable ex) {
                System.err.println("  [error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage() + "]");
                return -1;
            }
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            if (ms < best) best = ms;
        }
        return best;
    }

    private static void runHarmonica(String source) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        Interpreter.interpret(exe, new Object[0], 64);
    }

    private static void runRhino(String source) {
        Context ctx = Context.enter();
        try {
            ctx.setOptimizationLevel(-1);                              // pure interpreter mode
            ctx.setLanguageVersion(Context.VERSION_ES6);               // arrows / let / const / classes
            Scriptable scope = ctx.initStandardObjects();
            ctx.evaluateString(scope, source, "<bench>", 1, null);
        } finally {
            Context.exit();
        }
    }
}
