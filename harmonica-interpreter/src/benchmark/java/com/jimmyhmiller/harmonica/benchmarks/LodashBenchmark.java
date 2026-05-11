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

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Real-world: load lodash and run a representative workload through it.
 * Lodash is UMD; with no module/require/define globals it falls through to
 * the "browser globals" branch and assigns `_` to root. We initialize root
 * to globalThis (or similar) so the module load works on bare engines.
 */
public final class LodashBenchmark {

    public static void main(String[] args) throws Exception {
        java.nio.file.Path libsDir = Paths.get("benchmarks/real-world-libs");
        if (!Files.exists(libsDir)) libsDir = Paths.get("../benchmarks/real-world-libs");
        String lodash = Files.readString(libsDir.resolve("lodash.js"));

        // Workload that exercises a wide cross-section of lodash:
        //   - chunk/uniq (array)
        //   - map/filter/reduce
        //   - groupBy/keyBy (objects)
        //   - sortBy
        //   - debounce / throttle (skip — async timers)
        //   - difference / intersection
        //   - cloneDeep
        String workload =
            "var n = 5000;\n" +
            "var data = _.range(n).map(function(i) { return { id: i, group: i % 13, name: 'item-' + i }; });\n" +
            "var grouped = _.groupBy(data, 'group');\n" +
            "var sorted = _.sortBy(data, ['group', 'id']);\n" +
            "var sum = _.sumBy(data, 'id');\n" +
            "var uniq = _.uniqBy(data, function(d) { return d.id % 7; });\n" +
            "var partition = _.partition(data, function(d) { return d.group < 5; });\n" +
            "var filtered = _.filter(data, function(d) { return d.id % 3 === 0 && d.group > 2; });\n" +
            "var pluck = _.map(filtered, 'name');\n" +
            "var chunked = _.chunk(data, 50);\n" +
            "var clone = _.cloneDeep(grouped);\n" +
            "var ok = (sum === (n*(n-1))/2) && (uniq.length === 7) && (chunked.length === Math.ceil(n/50));\n" +
            "if (!ok) throw 'workload mismatch';\n" +
            "'sum=' + sum + ' uniq=' + uniq.length + ' chunked=' + chunked.length;\n";

        // Pre-flight: does lodash even parse on harmonica?
        long t0 = System.nanoTime();
        try {
            Parser.parse(lodash);
            double parseMs = (System.nanoTime() - t0) / 1_000_000.0;
            System.out.printf("[harmonica] lodash parsed in %.1f ms%n", parseMs);
        } catch (Throwable th) {
            System.err.println("[harmonica] lodash failed to parse: " + th);
            System.exit(1);
        }

        long t1 = System.nanoTime();
        Executable lodashExe;
        try {
            Program ast = Parser.parse(lodash);
            lodashExe = Generator.generate(ast);
            double genMs = (System.nanoTime() - t1) / 1_000_000.0;
            System.out.printf("[harmonica] lodash bytecode generated in %.1f ms%n", genMs);
        } catch (Throwable th) {
            System.err.println("[harmonica] lodash failed to lower: " + th);
            return;
        }

        // Combine lodash + workload into a single composite source so we run
        // the load+workload in the same realm.
        String composite = lodash + "\n" + workload;

        System.out.println();
        System.out.println("=== lodash + 5,000-item workload: harmonica vs Rhino (-opt -1) ===");
        System.out.printf("JVM: %s %s%n",
            System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.println();

        // Warmup
        try { runHarmonica(composite); } catch (Throwable t) { System.err.println("warmup harm: " + t); }
        try { runRhino(composite); } catch (Throwable t) { System.err.println("warmup rhino: " + t); }

        double harm = bestOf(3, () -> runHarmonica(composite));
        double rhin = bestOf(3, () -> runRhino(composite));

        System.out.printf("harmonica: %s%n", harm < 0 ? "fail" : String.format("%9.1f ms", harm));
        System.out.printf("Rhino -1:  %s%n", rhin < 0 ? "fail" : String.format("%9.1f ms", rhin));
        if (harm > 0 && rhin > 0) {
            String ratio = harm < rhin
                ? String.format("%.2fx faster", rhin / harm)
                : String.format("%.2fx slower", harm / rhin);
            System.out.printf("ratio: harmonica is %s%n", ratio);
        }
    }

    private static double bestOf(int iters, Runnable r) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            try { r.run(); }
            catch (AbruptCompletion ac) {
                if (ac.value() instanceof JSObject jo) {
                    System.err.println("[harm] " + jo.get("name") + ": " + jo.get("message"));
                } else {
                    System.err.println("[harm] AC: " + ac.value());
                }
                return -1;
            }
            catch (Throwable ex) {
                System.err.println("[error] " + ex);
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
