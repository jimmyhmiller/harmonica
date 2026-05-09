package com.jimmyhmiller.harmonica.benchmarks;
import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.bytecode.*;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import java.nio.file.*;

public final class LodashSplitBenchmark {
    public static void main(String[] args) throws Exception {
        Path libsDir = Paths.get("benchmarks/real-world-libs");
        if (!Files.exists(libsDir)) libsDir = Paths.get("../benchmarks/real-world-libs");
        String lodash = Files.readString(libsDir.resolve("lodash.js"));
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
            "var clone = _.cloneDeep(grouped);\n";

        // Profile mode: just run the lodash workload in a tight loop so the
        // attached profiler sees only the steady-state body (no Rhino, no
        // parser, no setup noise). Pre-compile once, run for ~30s.
        if (args.length > 0 && args[0].equals("loop-clonedeep")) {
            String src = lodash + "\n" +
                "var n = 5000;\n" +
                "var data = _.range(n).map(function(i){return {id:i, group:i%13, name:'item-'+i};});\n" +
                "var grouped = _.groupBy(data, 'group');\n";
            String loop = "var k = 0; while (k < 1000000) { _.cloneDeep(grouped); k++; }";
            Program ast = Parser.parse(src);
            Executable exe = Generator.generate(ast);
            Interpreter.interpret(exe, new Object[0], 64);
            // re-parse with the loop appended
            Program loopAst = Parser.parse(src + loop);
            Executable loopExe = Generator.generate(loopAst);
            for (int i = 0; i < 2; i++) Interpreter.interpret(loopExe, new Object[0], 64);
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                Interpreter.interpret(loopExe, new Object[0], 64);
            }
            return;
        }
        if (args.length > 0 && args[0].equals("loop")) {
            String composite = lodash + "\n" + workload;
            Program ast = Parser.parse(composite);
            Executable exe = Generator.generate(ast);
            for (int i = 0; i < 3; i++) Interpreter.interpret(exe, new Object[0], 64);
            long deadline = System.nanoTime() + 90_000_000_000L;
            int iters = 0;
            while (System.nanoTime() < deadline) {
                Interpreter.interpret(exe, new Object[0], 64);
                iters++;
            }
            System.out.printf("loop iters: %d%n", iters);
            return;
        }
        // Same shape but for Rhino opt=-1 + ES6, so an attached profiler sees
        // only Rhino's steady-state interpreter loop. Pre-compiles once into
        // a Script (InterpretedFunction), then re-runs in a fresh scope each
        // iteration to mirror what the timed bench does.
        // `loop-rhino-legacy` is the same shape but skips setLanguageVersion
        // (Rhino's default is VERSION_DEFAULT) — the user's question about
        // whether ES6 mode changes the interpreter is observable that way.
        if (args.length > 0 && (args[0].equals("loop-rhino") || args[0].equals("loop-rhino-legacy"))) {
            boolean es6 = args[0].equals("loop-rhino");
            String composite = lodash + "\n" + workload;
            Context ctx = Context.enter();
            try {
                ctx.setOptimizationLevel(-1);
                if (es6) ctx.setLanguageVersion(Context.VERSION_ES6);
                System.out.println("[rhino] mode=" + (es6 ? "ES6" : "legacy")
                    + " langVer=" + ctx.getLanguageVersion()
                    + " optLevel=" + ctx.getOptimizationLevel());
                org.mozilla.javascript.Script script =
                    ctx.compileString(composite, "<bench>", 1, null);
                System.out.println("[rhino] compiled class=" + script.getClass().getName());
                for (int i = 0; i < 3; i++) script.exec(ctx, ctx.initStandardObjects());
                long deadline = System.nanoTime() + 90_000_000_000L;
                int iters = 0;
                while (System.nanoTime() < deadline) {
                    Scriptable scope = ctx.initStandardObjects();
                    script.exec(ctx, scope);
                    iters++;
                }
                System.out.printf("loop iters: %d%n", iters);
            } finally { Context.exit(); }
            return;
        }
        // Phase 1: parse lodash
        long t0;
        for (int i = 0; i < 3; i++) {
            t0 = System.nanoTime();
            Program ast = Parser.parse(lodash);
            System.out.printf("[harm] parse lodash: %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        }
        // Phase 2: generate
        Executable lodashExe = null;
        for (int i = 0; i < 3; i++) {
            t0 = System.nanoTime();
            Program ast = Parser.parse(lodash);
            lodashExe = Generator.generate(ast);
            System.out.printf("[harm] parse+gen lodash: %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        }
        // Phase 3: execute lodash (just bootstrap)
        for (int i = 0; i < 3; i++) {
            t0 = System.nanoTime();
            Interpreter.interpret(lodashExe, new Object[0], 64);
            System.out.printf("[harm] execute lodash bootstrap: %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        }
        // Phase 4: pre-compile composite once, then time pure execution
        String composite = lodash + "\n" + workload;
        Program compAst = Parser.parse(composite);
        Executable compExe = Generator.generate(compAst);
        for (int i = 0; i < 8; i++) {
            t0 = System.nanoTime();
            Interpreter.interpret(compExe, new Object[0], 64);
            System.out.printf("[harm] execute composite (warm %d): %.1f ms%n", i, (System.nanoTime() - t0) / 1e6);
        }

        // Verify Rhino actually runs the workload to completion (no silent
        // throw or partial run masking as a fast time).
        {
            Context ctx = Context.enter();
            try {
                ctx.setOptimizationLevel(-1);
                ctx.setLanguageVersion(Context.VERSION_ES6);
                Scriptable scope = ctx.initStandardObjects();
                Object out = ctx.evaluateString(scope,
                    lodash + "\n" + workload + "'sum=' + sum + ' uniq=' + uniq.length + ' chunked=' + chunked.length + ' sorted=' + sorted.length;",
                    "<bench>", 1, null);
                System.out.println("[rhino sanity] result = " + Context.toString(out));
                Object ok = ctx.evaluateString(scope,
                    "(typeof _) + ' ' + (typeof data) + ' ' + (data && data.length) + ' ' + (typeof grouped)",
                    "<sanity>", 1, null);
                System.out.println("[rhino sanity] post-state = " + Context.toString(ok));
            } catch (Throwable t) {
                System.out.println("[rhino sanity] THREW: " + t);
            } finally { Context.exit(); }
        }

        // Rhino with opt=-1 (interpreted)
        for (int i = 0; i < 8; i++) {
            t0 = System.nanoTime();
            Context ctx = Context.enter();
            try {
                ctx.setOptimizationLevel(-1);
                ctx.setLanguageVersion(Context.VERSION_ES6);
                Scriptable scope = ctx.initStandardObjects();
                ctx.evaluateString(scope, lodash + "\n" + workload, "<bench>", 1, null);
            } finally { Context.exit(); }
            System.out.printf("[rhino opt=-1] full #%d: %.1f ms%n", i, (System.nanoTime() - t0) / 1e6);
        }

        // Sanity: also run Rhino with DEFAULT opt level (it uses bytecode +
        // JIT). If opt=-1 is much slower than default, our flag took effect.
        for (int i = 0; i < 4; i++) {
            t0 = System.nanoTime();
            Context ctx = Context.enter();
            try {
                Scriptable scope = ctx.initStandardObjects();
                ctx.evaluateString(scope, lodash + "\n" + workload, "<bench>", 1, null);
            } finally { Context.exit(); }
            System.out.printf("[rhino default-opt] full #%d: %.1f ms%n", i, (System.nanoTime() - t0) / 1e6);
        }
    }
}
