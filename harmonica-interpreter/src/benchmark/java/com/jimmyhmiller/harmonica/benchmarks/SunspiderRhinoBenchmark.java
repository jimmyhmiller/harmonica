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
 * Run Sunspider 1.0.2 (Apple's classic browser-perf suite) on harmonica and
 * Rhino-opt-(-1). Each test is a self-contained script with a built-in
 * sanity check that throws on incorrect output, so failures show up as
 * "fail" in the table.
 */
public final class SunspiderRhinoBenchmark {

    private SunspiderRhinoBenchmark() {}

    private static final String[] TESTS = {
        "3d-cube", "3d-morph", "3d-raytrace",
        "access-binary-trees", "access-fannkuch", "access-nbody", "access-nsieve",
        "bitops-3bit-bits-in-byte", "bitops-bits-in-byte", "bitops-bitwise-and", "bitops-nsieve-bits",
        "controlflow-recursive",
        "crypto-aes", "crypto-md5", "crypto-sha1",
        "date-format-tofte", "date-format-xparb",
        "math-cordic", "math-partial-sums", "math-spectral-norm",
        "regexp-dna",
        "string-base64", "string-fasta", "string-tagcloud", "string-unpack-code", "string-validate-input",
    };

    public static void main(String[] args) throws Exception {
        Path benchDir = Paths.get("benchmarks/sunspider");
        if (!Files.exists(benchDir)) benchDir = Paths.get("../benchmarks/sunspider");

        System.out.println();
        System.out.println("=== Sunspider 1.0.2: harmonica vs Rhino (-opt -1) ===");
        System.out.printf("JVM: %s %s%n",
            System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.println();
        System.out.printf("%-28s  %-14s  %-14s  %-14s%n",
            "test", "harmonica", "Rhino -1", "ratio");
        System.out.printf("%-28s  %-14s  %-14s  %-14s%n",
            "----------------------------", "--------------", "--------------", "--------------");

        double harmTotal = 0, rhinoTotal = 0;
        int harmFails = 0, rhinoFails = 0;
        for (String name : TESTS) {
            Path p = benchDir.resolve(name + ".js");
            String body;
            try {
                body = Files.readString(p);
            } catch (Exception ex) {
                System.out.printf("%-28s  [missing]%n", name);
                continue;
            }

            // Warm up once each (parse caches, JIT/interp setup)
            try { runHarmonica(body); } catch (Throwable ignored) {}
            try { runRhino(body); } catch (Throwable ignored) {}

            double harm = bestOf(3, () -> runHarmonica(body));
            double rhin = bestOf(3, () -> runRhino(body));

            String harmStr = harm < 0 ? "fail" : String.format("%9.1f ms", harm);
            String rhinStr = rhin < 0 ? "fail" : String.format("%9.1f ms", rhin);
            String ratio;
            if (harm < 0 || rhin < 0) ratio = "-";
            else if (harm < rhin) ratio = String.format("%.2fx faster", rhin / harm);
            else                  ratio = String.format("%.2fx slower", harm / rhin);

            if (harm < 0) harmFails++; else harmTotal += harm;
            if (rhin < 0) rhinoFails++; else rhinoTotal += rhin;

            System.out.printf("%-28s  %-14s  %-14s  %-14s%n",
                name, harmStr, rhinStr, ratio);
        }
        System.out.println();
        System.out.printf("totals (passing): harmonica %.1fms, Rhino %.1fms — harm fails %d, rhino fails %d%n",
            harmTotal, rhinoTotal, harmFails, rhinoFails);
        System.out.println();
    }

    private static double bestOf(int iters, Runnable r) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            try { r.run(); }
            catch (Throwable ex) {
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
