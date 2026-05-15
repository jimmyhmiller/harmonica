package com.jimmyhmiller.harmonica.bytecode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * JUnit entry point for the test262 execution runner. Off by default in
 * {@code mvn test} because the full sweep takes minutes; opt in with:
 *
 * <pre>{@code
 *   ./mvnw -pl harmonica-interpreter test -Dtest=Test262ExecTest -DrunTest262=true
 *   ./mvnw -pl harmonica-interpreter test -Dtest=Test262ExecTest -DrunTest262=true -Dtest262.limit=2000
 *   ./mvnw -pl harmonica-interpreter test -Dtest=Test262ExecTest -DrunTest262=true -Dtest262.filter=arrow-function
 * }</pre>
 *
 * <p>Writes:
 * <ul>
 *   <li>{@code target/test262-results.txt} — one line per test
 *       ({@code PASS} / {@code FAIL kind / SKIP reason}). Sorted by path.
 *   <li>{@code target/test262-summary.txt} — pass/fail/skip totals + failure histogram.
 * </ul>
 *
 * <p>The result file is a stable baseline you can diff between runs.
 */
@Tag("test262")
@EnabledIfSystemProperty(named = "runTest262", matches = "true")
class Test262ExecTest {

    // Walks the entire test262 tree (test/language, test/built-ins, test/annexB,
    // test/intl402, test/staging). Set -Dtest262.filter=<substring> to restrict.
    private static final Path TEST262_ROOT = Paths.get("test-oracles/test262/test");
    private static final Path FALLBACK_ROOT = Paths.get("../test-oracles/test262/test");
    private static final Path HARNESS_DIR = Paths.get("test-oracles/test262/harness");
    private static final Path FALLBACK_HARNESS = Paths.get("../test-oracles/test262/harness");

    private static final Path RESULTS_PATH = Paths.get("target/test262-results.txt");
    private static final Path SUMMARY_PATH = Paths.get("target/test262-summary.txt");

    @Test
    void runFullSuite() throws Exception {
        int limit = Integer.getInteger("test262.limit", Integer.MAX_VALUE);
        String filter = System.getProperty("test262.filter");

        Path testRoot = resolveTestRoot();
        Path harnessRoot = resolveHarnessRoot();
        String harnessAssert = Files.readString(harnessRoot.resolve("assert.js"));
        String harnessSta = Files.readString(harnessRoot.resolve("sta.js"));

        AtomicInteger total = new AtomicInteger();
        AtomicInteger pass = new AtomicInteger();
        AtomicInteger skip = new AtomicInteger();
        Map<Test262ExecRunner.Outcome, AtomicInteger> outcomeCounts = new LinkedHashMap<>();
        for (Test262ExecRunner.Outcome o : Test262ExecRunner.Outcome.values()) {
            outcomeCounts.put(o, new AtomicInteger());
        }
        Map<String, AtomicInteger> failHist = new java.util.HashMap<>();
        Map<String, AtomicInteger> skipReasons = new java.util.HashMap<>();
        java.util.List<String> resultLines = new java.util.ArrayList<>();

        long t0 = System.nanoTime();
        try (Stream<Path> stream = Files.walk(testRoot)) {
            java.util.Iterator<Path> it = stream
                .filter(p -> p.toString().endsWith(".js") && !p.toString().contains("FIXTURE"))
                .sorted()
                .iterator();
            while (it.hasNext() && total.get() < limit) {
                Path file = it.next();
                if (filter != null && !file.toString().contains(filter)) continue;
                total.incrementAndGet();

                Test262ExecRunner.Frontmatter fm;
                String source;
                try {
                    source = Files.readString(file);
                    fm = Test262ExecRunner.parseFrontmatter(source);
                } catch (Exception io) {
                    skip.incrementAndGet();
                    bump(skipReasons, "io-error");
                    resultLines.add(rel(file, testRoot) + " SKIP io-error");
                    continue;
                }

                String skipReason = skipReasonFor(fm);
                if (skipReason != null) {
                    skip.incrementAndGet();
                    bump(skipReasons, skipReason);
                    resultLines.add(rel(file, testRoot) + " SKIP " + skipReason);
                    continue;
                }

                Test262ExecRunner.TestResult result = Test262ExecRunner.runOne(
                    file, source, fm, harnessAssert, harnessSta, harnessRoot);
                outcomeCounts.get(result.outcome()).incrementAndGet();
                if (result.outcome() == Test262ExecRunner.Outcome.PASS) {
                    pass.incrementAndGet();
                    resultLines.add(rel(file, testRoot) + " PASS");
                } else {
                    bump(failHist, result.detail());
                    resultLines.add(rel(file, testRoot) + " FAIL " + result.outcome()
                        + " | " + truncate(result.detail(), 160));
                }

                // Periodic memory + progress log so we can see the heap-growth
                // curve when the full sweep OOMs partway through. Set
                // -Dtest262.heaplog=N (default 1000) — N=0 disables.
                int logEvery = Integer.getInteger("test262.heaplog", 1000);
                if (logEvery > 0 && total.get() % logEvery == 0) {
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    long totalMb = rt.totalMemory() / (1024 * 1024);
                    long maxMb = rt.maxMemory() / (1024 * 1024);
                    int liveThreads = Thread.activeCount();
                    System.out.printf("[mem] tested=%d  pass=%d  fail=%d  used=%dMB / total=%dMB / max=%dMB  threads=%d  last=%s%n",
                        total.get(), pass.get(), total.get() - pass.get() - skip.get(),
                        usedMb, totalMb, maxMb, liveThreads,
                        rel(file, testRoot));
                }
                // Periodic explicit GC. Diagnostic for the leak: if heap usage
                // stays bounded with -Dtest262.gcevery=200 (call System.gc
                // every N tests) then the heap pressure is just GC lag,
                // not a real leak. Default 0 = disabled.
                int gcEvery = Integer.getInteger("test262.gcevery", 0);
                if (gcEvery > 0 && total.get() % gcEvery == 0) {
                    System.gc();
                }
            }
        }

        double elapsed = (System.nanoTime() - t0) / 1e9;
        int failed = total.get() - pass.get() - skip.get();

        // Write results file. UTF-8 with REPLACE policy so a stray unpaired
        // surrogate in a test detail string can't crash the whole sweep.
        Files.createDirectories(RESULTS_PATH.getParent());
        writeLinesUtf8Lossy(RESULTS_PATH, resultLines);

        // Build the summary.
        StringBuilder summary = new StringBuilder();
        summary.append("=== test262 execution results ===\n");
        summary.append(String.format("Root:      %s%n", testRoot));
        summary.append(String.format("Harness:   %s%n", harnessRoot));
        if (filter != null) summary.append("Filter:    ").append(filter).append('\n');
        if (limit != Integer.MAX_VALUE) summary.append("Limit:     ").append(limit).append('\n');
        summary.append(String.format("Scanned:   %d  (in %.1fs)%n", total.get(), elapsed));
        summary.append(String.format("Passed:    %d  (%.1f%% of total, %.1f%% of non-skipped)%n",
            pass.get(),
            total.get() == 0 ? 0.0 : 100.0 * pass.get() / total.get(),
            total.get() - skip.get() == 0 ? 0.0 : 100.0 * pass.get() / (total.get() - skip.get())));
        summary.append(String.format("Skipped:   %d%n", skip.get()));
        summary.append(String.format("Failed:    %d%n", failed));
        summary.append('\n');
        summary.append("Outcomes:\n");
        outcomeCounts.forEach((o, n) -> {
            if (n.get() > 0) summary.append(String.format("  %-30s %d%n", o.name(), n.get()));
        });
        summary.append('\n');
        summary.append("Top skip reasons:\n");
        skipReasons.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
            .limit(15)
            .forEach(e -> summary.append(String.format("  %5d  %s%n", e.getValue().get(), e.getKey())));
        summary.append('\n');
        summary.append("Top failure messages:\n");
        failHist.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
            .limit(30)
            .forEach(e -> summary.append(String.format("  %5d  %s%n", e.getValue().get(), truncate(e.getKey(), 120))));

        String summaryStr = summary.toString();
        writeStringUtf8Lossy(SUMMARY_PATH, summaryStr);

        System.out.println();
        System.out.println(summaryStr);
        System.out.println("Per-test results: " + RESULTS_PATH);
        System.out.println("Summary file:     " + SUMMARY_PATH);
    }

    private static String skipReasonFor(Test262ExecRunner.Frontmatter fm) {
        // Per-user directive (2026-05-08): don't skip — let everything run.
        for (String f : fm.features()) {
            if (Test262ExecRunner.UNSUPPORTED_FEATURES.contains(f)) return "feature:" + f;
        }
        return null;
    }

    private static void bump(Map<String, AtomicInteger> hist, String key) {
        hist.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "<null>";
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static void writeLinesUtf8Lossy(Path p, List<String> lines) throws java.io.IOException {
        try (java.io.Writer w = lossyUtf8Writer(p)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        }
    }

    private static void writeStringUtf8Lossy(Path p, String s) throws java.io.IOException {
        try (java.io.Writer w = lossyUtf8Writer(p)) {
            w.write(s);
        }
    }

    private static java.io.Writer lossyUtf8Writer(Path p) throws java.io.IOException {
        java.nio.charset.CharsetEncoder enc = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        return new java.io.BufferedWriter(new java.io.OutputStreamWriter(
            Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
            enc));
    }

    private static String rel(Path file, Path root) {
        try { return root.relativize(file).toString(); }
        catch (IllegalArgumentException e) { return file.toString(); }
    }

    private static Path resolveTestRoot() {
        if (Files.isDirectory(TEST262_ROOT)) return TEST262_ROOT;
        if (Files.isDirectory(FALLBACK_ROOT)) return FALLBACK_ROOT;
        throw new IllegalStateException("test-oracles/test262/test not found");
    }

    private static Path resolveHarnessRoot() {
        if (Files.isDirectory(HARNESS_DIR)) return HARNESS_DIR;
        if (Files.isDirectory(FALLBACK_HARNESS)) return FALLBACK_HARNESS;
        throw new IllegalStateException("test262/harness not found");
    }
}
