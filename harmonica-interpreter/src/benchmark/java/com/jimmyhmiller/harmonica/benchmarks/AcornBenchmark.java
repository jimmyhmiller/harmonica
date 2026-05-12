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
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Real-world: load acorn (the JS ECMAScript parser) and run it on a batch
 * of representative JS samples. Both harmonica and Rhino are bare engines
 * with no module loader, so we shim CJS by predeclaring {@code module}
 * and {@code exports} at the top of the composite source — acorn's UMD
 * branch then assigns its public API to {@code module.exports}.
 */
public final class AcornBenchmark {

    public static void main(String[] args) throws Exception {
        Path libsDir = Paths.get("benchmarks/real-world-libs");
        if (!Files.exists(libsDir)) libsDir = Paths.get("../benchmarks/real-world-libs");
        String acorn = Files.readString(libsDir.resolve("acorn.js"));

        // A representative parse workload: a mix of small and medium-sized
        // ES2022 snippets that exercise classes, destructuring, async,
        // arrow functions, template literals, optional chaining.
        String samplesArrayLiteral = buildSamplesArrayLiteral();

        // Composite source = CJS shim + acorn body + workload.
        // Pre-declaring module/exports lets acorn's UMD detect the CJS
        // environment (via `typeof module === 'object'`) and self-assign.
        // The workload re-uses the exported `parse` function in a loop.
        // Parse-loop workload — invoked separately from the loader so we can
        // measure just the parsing cost (without acorn's one-time IIFE
        // startup). Wrapping in a function keeps top-level scope identical
        // between the two runs.
        int parseIters = 200;
        String parseLoop =
            "function __bench_parse() {\n" +
            "  var iters = " + parseIters + ";\n" +
            "  var samples = " + samplesArrayLiteral + ";\n" +
            "  var total = 0;\n" +
            "  for (var i = 0; i < iters; i++) {\n" +
            "    for (var j = 0; j < samples.length; j++) {\n" +
            "      var ast = acorn.parse(samples[j], { ecmaVersion: 2022, sourceType: 'module' });\n" +
            "      total += ast.body.length;\n" +
            "    }\n" +
            "  }\n" +
            "  return total;\n" +
            "}\n" +
            "__bench_parse();\n";

        // For the "load only" run we keep the function definition so the
        // compiled-source size and shape are identical, but don't call it.
        String loadOnly =
            "function __bench_parse() {\n" +
            "  var iters = " + parseIters + ";\n" +
            "  var samples = " + samplesArrayLiteral + ";\n" +
            "  var total = 0;\n" +
            "  for (var i = 0; i < iters; i++) {\n" +
            "    for (var j = 0; j < samples.length; j++) {\n" +
            "      var ast = acorn.parse(samples[j], { ecmaVersion: 2022, sourceType: 'module' });\n" +
            "      total += ast.body.length;\n" +
            "    }\n" +
            "  }\n" +
            "  return total;\n" +
            "}\n" +
            "0;\n";

        String prefix =
            "var module = { exports: {} };\n" +
            "var exports = module.exports;\n"
            + acorn + "\n"
            + "var acorn = module.exports;\n";
        String full = prefix + parseLoop;
        String onlyLoad = prefix + loadOnly;

        // Pre-flight: confirm harmonica can compile the composite, otherwise
        // there's no point measuring.
        long t0 = System.nanoTime();
        try {
            Parser.parse(full);
            double parseMs = (System.nanoTime() - t0) / 1_000_000.0;
            System.out.printf("[harmonica] composite parsed in %.1f ms%n", parseMs);
        } catch (Throwable th) {
            System.err.println("[harmonica] composite failed to parse: " + th);
            System.exit(1);
        }
        long t1 = System.nanoTime();
        try {
            Program ast = Parser.parse(full);
            Generator.generate(ast);
            double genMs = (System.nanoTime() - t1) / 1_000_000.0;
            System.out.printf("[harmonica] composite bytecode generated in %.1f ms%n", genMs);
        } catch (Throwable th) {
            System.err.println("[harmonica] composite failed to lower: " + th);
            System.exit(1);
        }

        System.out.println();
        System.out.println("=== acorn parse loop (" + parseIters + " iters × " + sampleCount()
            + " snippets = " + (parseIters * sampleCount()) + " parses): harmonica vs Rhino (-opt -1) ===");
        System.out.printf("JVM: %s %s%n",
            System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.println();

        // Warmup both runners under both engines so JIT / parser caches settle.
        try { runHarmonica(onlyLoad); runHarmonica(full); }
        catch (Throwable t) { System.err.println("warmup harm: " + t); }
        try { runRhino(onlyLoad); runRhino(full); }
        catch (Throwable t) { System.err.println("warmup rhino: " + t); }

        // best-of-3 each. Parse-loop time is the (full - load) delta — the
        // steady-state cost an application sees after acorn has been loaded.
        double harmFull = bestOf(3, () -> runHarmonica(full));
        double harmLoad = bestOf(3, () -> runHarmonica(onlyLoad));
        double rhinFull = bestOf(3, () -> runRhino(full));
        double rhinLoad = bestOf(3, () -> runRhino(onlyLoad));

        double harmLoop = (harmFull < 0 || harmLoad < 0) ? -1 : harmFull - harmLoad;
        double rhinLoop = (rhinFull < 0 || rhinLoad < 0) ? -1 : rhinFull - rhinLoad;

        System.out.printf("%-12s  %-12s  %-12s  %-12s%n", "engine", "load (ms)", "parse-loop (ms)", "total (ms)");
        System.out.printf("%-12s  %-12s  %-12s  %-12s%n", "------------", "------------", "------------", "------------");
        System.out.printf("%-12s  %12.1f  %12.1f  %12.1f%n", "harmonica", harmLoad, harmLoop, harmFull);
        System.out.printf("%-12s  %12.1f  %12.1f  %12.1f%n", "Rhino -1",  rhinLoad, rhinLoop, rhinFull);

        if (harmLoop > 0 && rhinLoop > 0) {
            String ratio = harmLoop < rhinLoop
                ? String.format("%.2fx faster", rhinLoop / harmLoop)
                : String.format("%.2fx slower", harmLoop / rhinLoop);
            System.out.printf("%nparse-loop ratio: harmonica is %s than Rhino -opt -1%n", ratio);
        }
    }

    /**
     * Snippets covering a representative cross-section of modern JS the
     * parser sees in the wild. Each is escaped into a JS string literal.
     */
    private static String[] samples() {
        return new String[] {
            "const fib = n => n < 2 ? n : fib(n-1) + fib(n-2);",
            "class Greeter { constructor(name) { this.name = name; } greet() { return `Hi ${this.name}`; } }",
            "function sum(arr) { let s = 0; for (const x of arr) s += x; return s; }",
            "async function fetchAll(urls) { const out = []; for (const u of urls) out.push(await fetch(u)); return out; }",
            "const { a, b: { c, d = 1 }, ...rest } = obj;",
            "const xs = [1, 2, 3, ...more, ...other.filter(x => x > 0)];",
            "function* gen() { yield 1; yield 2; yield* other(); }",
            "try { doIt(); } catch (e) { console.error(e); } finally { cleanup(); }",
            "obj?.foo?.bar?.(args, ...rest) ?? defaultVal;",
            "const re = /(?:\\s|\\/\\/.*|\\/\\*[^]*?\\*\\/)*/g;",
            // A small module-shaped block exercising many constructs at once.
            "import { foo } from './a.js';\n"
            + "import * as ns from './b.js';\n"
            + "export const C = class extends Base { #priv = 0; static N = 42; method() { return this.#priv; } };\n"
            + "export default function main(opts = {}) { return Object.assign({}, opts, { ts: Date.now() }); }",
            // A mid-sized chunk simulating a tiny utility module.
            "const memo = new WeakMap();\n"
            + "function once(fn) {\n"
            + "  return function(...args) {\n"
            + "    if (memo.has(fn)) return memo.get(fn);\n"
            + "    const result = fn.apply(this, args);\n"
            + "    memo.set(fn, result);\n"
            + "    return result;\n"
            + "  };\n"
            + "}\n"
            + "const sleep = ms => new Promise(r => setTimeout(r, ms));\n"
            + "async function retry(fn, n = 3) {\n"
            + "  for (let i = 0; i < n; i++) {\n"
            + "    try { return await fn(); }\n"
            + "    catch (e) { if (i === n - 1) throw e; await sleep(100 * (i + 1)); }\n"
            + "  }\n"
            + "}",
        };
    }

    /** Build a JS array literal containing each sample as a single-quoted string. */
    private static String buildSamplesArrayLiteral() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : samples()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("'").append(escape(s)).append("'");
        }
        sb.append("]");
        return sb.toString();
    }

    private static int sampleCount() { return samples().length; }

    private static String escape(String s) {
        // Single-quoted JS string: escape backslash, single quote, and newline.
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
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
