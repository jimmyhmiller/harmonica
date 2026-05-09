package com.jimmyhmiller.harmonica.benchmarks;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.bytecode.Executable;
import com.jimmyhmiller.harmonica.bytecode.Generator;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Run V8 Octane benchmarks (Richards, DeltaBlue, Crypto, RayTrace, Splay,
 * NavierStokes) on harmonica vs Rhino-opt-(-1).
 *
 * <p>Each benchmark file from chromium/octane self-registers a {@code Benchmark}
 * record via the canonical {@code BenchmarkSuite} harness. We replace that
 * harness with a stub (see {@code harness.js}) that captures records into
 * {@code __benchmarks}, then time each {@code run} once after invoking
 * {@code setup} (and {@code tearDown} after).
 *
 * <p>This is "single-execution" timing rather than the score-normalized Octane
 * total — the absolute milliseconds make engine-vs-engine deltas visible.
 */
public final class OctaneRhinoBenchmark {

    private OctaneRhinoBenchmark() {}

    private static final String[] BENCHMARK_FILES = {
        "richards.js",
        "deltablue.js",
        "crypto.js",
        "raytrace.js",
        "splay.js",
        "navier-stokes.js",
    };

    public static void main(String[] args) throws Exception {
        Path benchDir = Paths.get("benchmarks/v8");
        if (!Files.exists(benchDir)) benchDir = Paths.get("../benchmarks/v8");
        String harness = Files.readString(benchDir.resolve("harness.js"));

        // Tail JS that turns the captured __benchmarks into a single
        // setup -> run -> tearDown invocation per registered benchmark and
        // returns a string for the host to print.
        String tail =
            "var __out = '';\n" +
            "for (var __i = 0; __i < __benchmarks.length; __i++) {\n" +
            "  var __b = __benchmarks[__i];\n" +
            "  if (__b.setup) __b.setup();\n" +
            "  __b.run();\n" +
            "  if (__b.tearDown) __b.tearDown();\n" +
            "}\n" +
            "__benchmarks.length;\n";

        System.out.println();
        System.out.println("=== V8 Octane benchmarks: harmonica vs Rhino (-opt -1) ===");
        System.out.printf("JVM: %s %s%n",
            System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.println();
        System.out.printf("%-16s  %-14s  %-14s  %-14s%n",
            "benchmark", "harmonica", "Rhino -1", "ratio");
        System.out.printf("%-16s  %-14s  %-14s  %-14s%n",
            "----------------", "--------------", "--------------", "--------------");

        for (String file : BENCHMARK_FILES) {
            Path p = benchDir.resolve(file);
            String body;
            try {
                body = Files.readString(p);
            } catch (Exception ex) {
                System.out.printf("%-16s  [missing: %s]%n", file, ex.getMessage());
                continue;
            }
            String composite = harness + "\n" + body + "\n" + tail;

            String label = file.replace(".js", "");

            double harm = bestOf(3, () -> runHarmonica(composite));
            double rhin = bestOf(3, () -> runRhino(composite));

            String harmStr = harm < 0 ? "fail" : String.format("%9.1f ms", harm);
            String rhinStr = rhin < 0 ? "fail" : String.format("%9.1f ms", rhin);
            String ratio;
            if (harm < 0 || rhin < 0) ratio = "-";
            else if (harm < rhin) ratio = String.format("%.2fx faster", rhin / harm);
            else                  ratio = String.format("%.2fx slower", harm / rhin);

            System.out.printf("%-16s  %-14s  %-14s  %-14s%n",
                label, harmStr, rhinStr, ratio);
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
            ctx.setOptimizationLevel(-1);
            ctx.setLanguageVersion(Context.VERSION_ES6);
            Scriptable scope = ctx.initStandardObjects();
            ctx.evaluateString(scope, source, "<bench>", 1, null);
        } finally {
            Context.exit();
        }
    }
}
