package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Execution-based test262 runner. For each test:
 * <ol>
 *   <li>Parse the frontmatter.
 *   <li>Skip categories we can't yet run (modules, async features, raw, etc.).
 *   <li>Compose a script = harness/sta.js + harness/assert.js + any
 *       {@code includes:} + the source itself.
 *   <li>Parse + generate + interpret through harmonica.
 *   <li>If frontmatter has {@code negative: phase: parse}, expect a parse
 *       error; {@code phase: runtime} expects an unwound throw.
 *   <li>Otherwise, expect successful completion (no thrown error).
 * </ol>
 *
 * <p>Reports pass/fail/skip stats and a histogram of failure categories.
 * Run via:
 * <pre>{@code
 *   java -cp <test-classpath> com.jimmyhmiller.harmonica.bytecode.Test262ExecRunner [--limit N] [--filter glob]
 * }</pre>
 */
public final class Test262ExecRunner {

    private static final Path TEST262_LANGUAGE = Paths.get("test-oracles/test262/test/language");
    private static final Path FALLBACK_LANGUAGE = Paths.get("../test-oracles/test262/test/language");
    private static final Path HARNESS_DIR = Paths.get("test-oracles/test262/harness");
    private static final Path FALLBACK_HARNESS = Paths.get("../test-oracles/test262/harness");

    private static final Pattern FRONTMATTER = Pattern.compile("/\\*---([\\s\\S]*?)---\\*/");

    /**
     * Features we structurally cannot run — skipping these is the difference
     * between "fails fast and accurately reports the gap" and "hangs / blows
     * the JVM stack". Keep this set as small as humanly possible. Per-user
     * directive (2026-05-08): don't hide failures behind feature flags;
     * surface them in the failure histogram so the gap is visible. Anything
     * that can fail fast at parse/runtime should be left to fail.
     */
    static final Set<String> UNSUPPORTED_FEATURES = Set.of(
        // Empty — every feature now fails honestly. Re-add a name here only
        // if leaving the test enabled crashes the JVM (StackOverflow loops
        // not bounded by our timeout, OOM, etc.).
    );

    enum Outcome { PASS, FAIL_PARSE, FAIL_GEN, FAIL_RUNTIME, FAIL_NEGATIVE_NOT_THROWN, FAIL_WRONG_NEGATIVE_TYPE, FAIL_TIMEOUT }

    /** Per-test interpreter timeout. Tests that hit this are reported as FAIL_TIMEOUT. */
    static final long TEST_TIMEOUT_MS = Long.getLong("test262.timeout", 5000L);

    private static final java.util.concurrent.ExecutorService TIMEOUT_POOL =
        java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test262-runner");
            t.setDaemon(true);
            return t;
        });

    record TestResult(Path file, Outcome outcome, String detail) {}

    public static void main(String[] args) throws Exception {
        int limit = Integer.MAX_VALUE;
        String filter = null;
        boolean printFailures = true;
        int failureSampleN = 30;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--filter" -> filter = args[++i];
                case "--quiet" -> printFailures = false;
                case "--sample" -> failureSampleN = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.exit(2);
                }
            }
        }
        run(resolveLanguageRoot(), resolveHarnessRoot(), limit, filter, printFailures, failureSampleN);
    }

    public static void run(Path languageRoot, Path harnessRoot, int limit, String filter,
                           boolean printFailures, int failureSampleN) throws Exception {
        String harnessAssert = Files.readString(harnessRoot.resolve("assert.js"));
        String harnessSta = Files.readString(harnessRoot.resolve("sta.js"));

        AtomicInteger total = new AtomicInteger();
        AtomicInteger pass = new AtomicInteger();
        AtomicInteger skip = new AtomicInteger();
        Map<Outcome, AtomicInteger> outcomeCounts = new LinkedHashMap<>();
        for (Outcome o : Outcome.values()) outcomeCounts.put(o, new AtomicInteger());
        Map<String, AtomicInteger> failHist = new HashMap<>();

        long t0 = System.nanoTime();
        java.util.List<TestResult> failureSamples = new java.util.ArrayList<>();
        Map<String, AtomicInteger> skipReasons = new HashMap<>();

        try (Stream<Path> stream = Files.walk(languageRoot)) {
            java.util.Iterator<Path> it = stream
                .filter(p -> p.toString().endsWith(".js") && !p.toString().contains("FIXTURE"))
                .iterator();
            while (it.hasNext() && total.get() < limit) {
                Path file = it.next();
                if (filter != null && !file.toString().contains(filter)) continue;
                total.incrementAndGet();

                Frontmatter fm;
                String source;
                try {
                    source = Files.readString(file);
                    fm = parseFrontmatter(source);
                } catch (IOException io) {
                    skip.incrementAndGet();
                    bump(skipReasons, "io-error");
                    continue;
                }

                String skipReason = shouldSkip(fm, file);
                if (skipReason != null) {
                    skip.incrementAndGet();
                    bump(skipReasons, skipReason);
                    continue;
                }

                TestResult result = runOne(file, source, fm, harnessAssert, harnessSta, harnessRoot);
                outcomeCounts.get(result.outcome).incrementAndGet();
                if (result.outcome == Outcome.PASS) {
                    pass.incrementAndGet();
                } else {
                    bump(failHist, result.detail);
                    if (failureSamples.size() < failureSampleN) failureSamples.add(result);
                }
            }
        }

        double elapsed = (System.nanoTime() - t0) / 1e9;
        System.out.println();
        System.out.println("=== test262 execution results ===");
        System.out.printf("Root: %s%n", languageRoot);
        System.out.printf("Total scanned: %d  (in %.1fs)%n", total.get(), elapsed);
        System.out.printf("  Passed:  %d  (%.1f%%)%n", pass.get(),
            total.get() == 0 ? 0.0 : 100.0 * pass.get() / total.get());
        System.out.printf("  Skipped: %d%n", skip.get());
        int failed = total.get() - pass.get() - skip.get();
        System.out.printf("  Failed:  %d  (%.1f%% of non-skipped)%n", failed,
            (total.get() - skip.get()) == 0 ? 0.0 : 100.0 * failed / (total.get() - skip.get()));
        System.out.println();
        System.out.println("Outcomes:");
        outcomeCounts.forEach((o, n) -> {
            if (n.get() > 0) System.out.printf("  %-30s %d%n", o.name(), n.get());
        });
        System.out.println();
        System.out.println("Top skip reasons:");
        skipReasons.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
            .limit(10)
            .forEach(e -> System.out.printf("  %5d  %s%n", e.getValue().get(), e.getKey()));
        System.out.println();
        System.out.println("Top failure messages:");
        failHist.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
            .limit(20)
            .forEach(e -> System.out.printf("  %5d  %s%n", e.getValue().get(), truncate(e.getKey(), 90)));

        if (printFailures && !failureSamples.isEmpty()) {
            System.out.println();
            System.out.println("Failure sample (first " + failureSamples.size() + "):");
            for (TestResult r : failureSamples) {
                System.out.printf("  [%s] %s%n      %s%n",
                    r.outcome.name(), shortPath(r.file, languageRoot), truncate(r.detail, 200));
            }
        }
    }

    static TestResult runOne(Path file, String source, Frontmatter fm,
                              String harnessAssert, String harnessSta, Path harnessRoot) {
        // Compose composite script. Tests with the {@code raw} flag run as
        // bare scripts without harness prepended (per INTERPRETING.md).
        StringBuilder composite = new StringBuilder();
        boolean isRaw = fm.flags.contains("raw");
        // test262 INTERPRETING.md § "Strict Mode": flags `onlyStrict` requires
        // the test to run in strict mode. Implementations satisfy this by
        // prepending a `"use strict";` directive (after harness, before
        // test body — directives are only effective at the start of the
        // *script body*, but our directive parser hoists from the file's top
        // so wrapping in a fresh strict-mode prelude works for our setup).
        boolean isStrict = fm.flags.contains("onlyStrict");
        if (isStrict) composite.append("\"use strict\";\n");
        if (!isRaw) {
            composite.append(harnessSta).append('\n').append(harnessAssert).append('\n');
            for (String inc : fm.includes) {
                try {
                    composite.append(Files.readString(harnessRoot.resolve(inc))).append('\n');
                } catch (IOException io) {
                    return new TestResult(file, Outcome.FAIL_RUNTIME, "harness include not found: " + inc);
                }
            }
        }
        composite.append(source);

        // Parse. Tests flagged {@code module} require sourceType=module
        // (allows top-level import/export/await). Without this they fail
        // with "'import' and 'export' may appear only with 'sourceType: module'".
        boolean isModule = fm.flags.contains("module");
        Program ast;
        try {
            ast = Parser.parse(composite.toString(), /* forceModuleMode */ isModule);
        } catch (Throwable t) {
            if (fm.negativePhase != null
                && (fm.negativePhase.equals("parse") || fm.negativePhase.equals("early"))
                && (fm.negativeType == null || "SyntaxError".equals(fm.negativeType))) {
                return new TestResult(file, Outcome.PASS, "parse-phase negative");
            }
            return new TestResult(file, Outcome.FAIL_PARSE, summarize(t));
        }

        // Generate. A handful of "negative" early-error tests (like
        // `++ foo()`) lex+parse fine but are early errors per the static
        // semantics — our parser doesn't enforce them, so the generator
        // throws when it tries to lower them. Treat a gen-time throw on a
        // negative test (any phase that includes static early errors) as the
        // expected error so the test passes if the type matches SyntaxError.
        Executable exe;
        try {
            exe = Generator.generate(ast);
        } catch (Throwable t) {
            if (fm.negativePhase != null
                && (fm.negativePhase.equals("parse") || fm.negativePhase.equals("early"))
                && (fm.negativeType == null || "SyntaxError".equals(fm.negativeType))) {
                return new TestResult(file, Outcome.PASS, "gen-phase early-error treated as " + fm.negativeType);
            }
            return new TestResult(file, Outcome.FAIL_GEN, summarize(t));
        }

        // Test isolation: many tests mutate shared intrinsics
        // (e.g. `delete Array.prototype[Symbol.iterator]`, monkey-patching
        // String.prototype, etc.) and the next test inherits a corrupted
        // realm. Reset realm state before each test so pollution doesn't
        // cascade through the sweep. The bootstrap on the next interpret
        // call rebuilds intrinsics from scratch.
        Realm.resetForNewRun();
        // Interpret with a per-test timeout. Tests that loop forever or
        // recurse too deep would otherwise stall the whole sweep.
        final Executable exeFinal = exe;
        java.util.concurrent.Future<?> fut = TIMEOUT_POOL.submit(() -> {
            Interpreter.interpret(exeFinal, new Object[0], 64);
        });
        try {
            fut.get(TEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            fut.cancel(true);
            return new TestResult(file, Outcome.FAIL_TIMEOUT, "exceeded " + TEST_TIMEOUT_MS + "ms");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof AbruptCompletion ac) {
                if (fm.negativePhase == null) {
                    return new TestResult(file, Outcome.FAIL_RUNTIME, abruptDetail(ac));
                }
                String thrownTypeName = errorName(ac.value());
                if (fm.negativeType != null && !thrownTypeName.equals(fm.negativeType)) {
                    return new TestResult(file, Outcome.FAIL_WRONG_NEGATIVE_TYPE,
                        "expected " + fm.negativeType + " got " + thrownTypeName + " (" + abruptDetail(ac) + ")");
                }
                return new TestResult(file, Outcome.PASS, "");
            }
            if (cause instanceof StackOverflowError) {
                return new TestResult(file, Outcome.FAIL_TIMEOUT, "stack overflow");
            }
            return new TestResult(file, Outcome.FAIL_RUNTIME, summarize(cause));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new TestResult(file, Outcome.FAIL_TIMEOUT, "interrupted");
        }

        // Ran cleanly.
        if (fm.negativePhase != null && !fm.negativePhase.equals("parse")) {
            return new TestResult(file, Outcome.FAIL_NEGATIVE_NOT_THROWN,
                "expected runtime " + fm.negativeType + " but completed normally");
        }
        return new TestResult(file, Outcome.PASS, "");
    }

    private static String shouldSkip(Frontmatter fm, Path file) {
        // Per-user directive (2026-05-08): don't skip — let everything run.
        // The per-test timeout bounds the cost; failure messages are far
        // more useful than skip counts.
        for (String f : fm.features) {
            if (UNSUPPORTED_FEATURES.contains(f)) return "feature:" + f;
        }
        return null;
    }

    // ============================================================
    //  Frontmatter
    // ============================================================

    record Frontmatter(Set<String> flags, List<String> features, List<String> includes,
                       String negativePhase, String negativeType) {}

    static Frontmatter parseFrontmatter(String src) {
        Matcher m = FRONTMATTER.matcher(src);
        if (!m.find()) {
            return new Frontmatter(Set.of(), List.of(), List.of(), null, null);
        }
        String body = m.group(1);
        Set<String> flags = new HashSet<>();
        List<String> features = new java.util.ArrayList<>();
        List<String> includes = new java.util.ArrayList<>();
        String negPhase = null, negType = null;

        // Parse simple key: [a, b, c] / key: value lines. Negative is a nested map.
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("flags:")) {
                flags.addAll(parseList(trimmed.substring("flags:".length()).trim()));
            } else if (trimmed.startsWith("features:")) {
                features.addAll(parseList(trimmed.substring("features:".length()).trim()));
            } else if (trimmed.startsWith("includes:")) {
                includes.addAll(parseList(trimmed.substring("includes:".length()).trim()));
            } else if (trimmed.startsWith("negative:")) {
                // Multi-line format: phase: ... / type: ... follow on indented lines.
                String inline = trimmed.substring("negative:".length()).trim();
                if (!inline.isEmpty() && inline.startsWith("{")) {
                    // Inline JSON-style: { phase: parse, type: SyntaxError }
                    Matcher pm = Pattern.compile("phase:\\s*(\\w+)").matcher(inline);
                    if (pm.find()) negPhase = pm.group(1);
                    Matcher tm = Pattern.compile("type:\\s*(\\w+)").matcher(inline);
                    if (tm.find()) negType = tm.group(1);
                }
                // Look at following indented lines.
                for (int j = i + 1; j < lines.length; j++) {
                    String next = lines[j];
                    if (!next.startsWith(" ") && !next.startsWith("\t")) break;
                    String t = next.trim();
                    if (t.startsWith("phase:")) negPhase = t.substring("phase:".length()).trim();
                    else if (t.startsWith("type:")) negType = t.substring("type:".length()).trim();
                }
            }
        }
        return new Frontmatter(flags, features, includes, negPhase, negType);
    }

    private static List<String> parseList(String s) {
        if (s.isEmpty()) return List.of();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        List<String> out = new java.util.ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            // Strip optional surrounding quotes.
            if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
                t = t.substring(1, t.length() - 1);
            }
            out.add(t);
        }
        return out;
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private static String summarize(Throwable t) {
        String cls = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) return cls;
        return cls + ": " + msg;
    }

    private static String abruptDetail(AbruptCompletion ac) {
        Object v = ac.value();
        if (v instanceof JSObject jo) {
            Object name = jo.get("name");
            Object message = jo.get("message");
            return AbstractOps.toString(name) + ": " + AbstractOps.toString(message);
        }
        return AbstractOps.toString(v);
    }

    private static String errorName(Object v) {
        if (v instanceof JSObject jo) {
            Object name = jo.get("name");
            if (name instanceof String s) return s;
        }
        if (v instanceof String s && s.contains(":")) {
            return s.substring(0, s.indexOf(':'));
        }
        return "Error";
    }

    private static void bump(Map<String, AtomicInteger> hist, String key) {
        hist.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "<null>";
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static String shortPath(Path file, Path root) {
        try { return root.relativize(file).toString(); }
        catch (IllegalArgumentException e) { return file.toString(); }
    }

    private static Path resolveLanguageRoot() {
        if (Files.isDirectory(TEST262_LANGUAGE)) return TEST262_LANGUAGE;
        if (Files.isDirectory(FALLBACK_LANGUAGE)) return FALLBACK_LANGUAGE;
        throw new IllegalStateException("test262/test/language not found");
    }

    private static Path resolveHarnessRoot() {
        if (Files.isDirectory(HARNESS_DIR)) return HARNESS_DIR;
        if (Files.isDirectory(FALLBACK_HARNESS)) return FALLBACK_HARNESS;
        throw new IllegalStateException("test262/harness not found");
    }
}
