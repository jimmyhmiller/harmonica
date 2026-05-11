package com.jimmyhmiller.harmonica.bytecode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Run test262's regex-related tests through harmonica.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code test/built-ins/RegExp/} — the RegExp object itself
 *       (constructor, prototype, instance methods, syntax)
 *   <li>{@code test/built-ins/String/prototype/{match,replace,replaceAll,
 *       search,split,matchAll}/} — String methods that take regex args
 * </ul>
 *
 * <p>Reuses {@link Test262ExecRunner#runOne} so test setup, harness
 * inclusion, and outcome categorization match the language-test runner.
 *
 * <p>Gated behind {@code -DrunTest262Regex=true} so it doesn't slow the
 * normal smoke run; the suite is large (~2,000 tests).
 */
class Test262RegexTest {

    /** Where the test262 corpus root lives (we walk subdirectories from here). */
    private static final Path TEST262_TEST = Paths.get("test-oracles/test262/test");
    private static final Path FALLBACK_TEST = Paths.get("../test-oracles/test262/test");
    private static final Path HARNESS_DIR  = Paths.get("test-oracles/test262/harness");
    private static final Path FALLBACK_HARNESS = Paths.get("../test-oracles/test262/harness");

    /** Subdirectories of {@code test/} that contain regex-related tests. */
    private static final String[] REGEX_ROOTS = {
        "built-ins/RegExp",
        "built-ins/String/prototype/match",
        "built-ins/String/prototype/replace",
        "built-ins/String/prototype/replaceAll",
        "built-ins/String/prototype/search",
        "built-ins/String/prototype/split",
        "built-ins/String/prototype/matchAll",
    };

    @Test
    void runRegexCorpus() throws Exception {
        // Gated: opt-in via -DrunTest262Regex=true (matches Test262ExecTest's flag).
        if (!Boolean.getBoolean("runTest262Regex")) {
            System.out.println("[regex262] skipping — set -DrunTest262Regex=true to enable");
            return;
        }

        Path testRoot = Files.isDirectory(TEST262_TEST) ? TEST262_TEST : FALLBACK_TEST;
        Path harnessRoot = Files.isDirectory(HARNESS_DIR) ? HARNESS_DIR : FALLBACK_HARNESS;
        if (!Files.isDirectory(testRoot)) {
            System.err.println("[regex262] test262 corpus not found at " + TEST262_TEST.toAbsolutePath());
            return;
        }

        String harnessAssert = Files.readString(harnessRoot.resolve("assert.js"));
        String harnessSta = Files.readString(harnessRoot.resolve("sta.js"));

        List<Test262ExecRunner.TestResult> failures = new ArrayList<>();
        Map<Test262ExecRunner.Outcome, AtomicInteger> outcomeCounts = new LinkedHashMap<>();
        for (Test262ExecRunner.Outcome o : Test262ExecRunner.Outcome.values())
            outcomeCounts.put(o, new AtomicInteger());
        Map<String, AtomicInteger> failHist = new java.util.HashMap<>();
        int total = 0, skip = 0;

        long t0 = System.nanoTime();
        for (String sub : REGEX_ROOTS) {
            Path root = testRoot.resolve(sub);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                var it = stream
                    .filter(p -> p.toString().endsWith(".js") && !p.toString().contains("FIXTURE"))
                    .iterator();
                while (it.hasNext()) {
                    Path file = it.next();
                    String source;
                    Test262ExecRunner.Frontmatter fm;
                    try {
                        source = Files.readString(file);
                        fm = Test262ExecRunner.parseFrontmatter(source);
                    } catch (IOException io) {
                        skip++; continue;
                    }
                    if (Test262ExecRunner.shouldSkip(fm, file) != null) {
                        skip++; continue;
                    }
                    total++;
                    Test262ExecRunner.TestResult r = Test262ExecRunner.runOne(
                        file, source, fm, harnessAssert, harnessSta, harnessRoot);
                    outcomeCounts.get(r.outcome()).incrementAndGet();
                    if (r.outcome() != Test262ExecRunner.Outcome.PASS) {
                        if (failures.size() < 50) failures.add(r);
                        failHist.merge(r.detail(), new AtomicInteger(1),
                            (a, b) -> { a.incrementAndGet(); return a; });
                    }
                }
            }
        }

        double secs = (System.nanoTime() - t0) / 1e9;
        int pass = outcomeCounts.get(Test262ExecRunner.Outcome.PASS).get();
        System.out.println();
        System.out.println("=== test262 regex results ===");
        System.out.printf("Run:       %d (%.1fs)%n", total, secs);
        System.out.printf("Passed:    %d (%.1f%%)%n", pass, total == 0 ? 0 : 100.0 * pass / total);
        System.out.printf("Skipped:   %d%n", skip);
        for (var e : outcomeCounts.entrySet()) {
            if (e.getValue().get() > 0)
                System.out.printf("  %-30s %d%n", e.getKey().name(), e.getValue().get());
        }
        System.out.println();
        System.out.println("Top failure messages:");
        failHist.entrySet().stream()
            .sorted((a, b) -> b.getValue().get() - a.getValue().get())
            .limit(15)
            .forEach(e -> System.out.printf("  %5d  %s%n", e.getValue().get(), e.getKey()));

        // Persist a per-test result file so the diff over time is visible.
        Path out = Paths.get("target/test262-regex-results.txt");
        Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# test262 regex results — ").append(total).append(" tests, ").append(pass).append(" pass\n");
        for (var f : failures) {
            sb.append(f.file()).append("  ").append(f.outcome()).append("  ").append(f.detail()).append('\n');
        }
        Files.writeString(out, sb.toString());
        System.out.println("Wrote " + out + " (failure samples)");
    }
}
