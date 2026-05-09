package com.jimmyhmiller.harmonica.bytecode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard against test262 backslide. Reads a committed baseline of
 * test262 paths that currently pass and re-runs each one — any test in the
 * baseline that no longer passes makes the build fail.
 *
 * <p>Runs by default in {@code mvn test}. Re-verifying the full baseline
 * takes ~7s on Java 25 (one Future.get-bound interpret per test, no real
 * parallelism); fast enough that paying it on every run is the right
 * trade for catching regressions immediately.
 *
 * <h3>Updating the baseline</h3>
 * After intentionally fixing or breaking tests, regenerate via:
 *
 * <pre>{@code
 *   ./mvnw -pl harmonica-core test -Dtest=Test262SnapshotTest \
 *       -Dtest262.snapshot.update=true
 * }</pre>
 *
 * Update mode runs the full test262 corpus (not just the existing baseline),
 * collects every {@code PASS}, and rewrites
 * {@code src/test/resources/test262-passing-baseline.txt}. Inspect the diff
 * before committing — you should see only the tests you intended to flip.
 *
 * <p>The Unicode database visible to {@link Character#isUnicodeIdentifierPart}
 * differs between JDK releases, so a handful of {@code identifiers/*-unicode-*}
 * tests can swing pass/fail depending on the JDK's Unicode level. The project
 * pins Java 25 (see {@code .java-version} and {@code maven.compiler.target}
 * in the root pom); running on an older JDK will surface those as regressions.
 */
@Tag("test262")
class Test262SnapshotTest {

    private static final String BASELINE_RESOURCE = "/test262-passing-baseline.txt";
    /** Where update mode writes the regenerated baseline. */
    private static final Path BASELINE_FILE =
        Paths.get("src/test/resources/test262-passing-baseline.txt");
    private static final Path BASELINE_FILE_FALLBACK =
        Paths.get("harmonica-core/src/test/resources/test262-passing-baseline.txt");

    private static final Path TEST262_LANGUAGE = Paths.get("test-oracles/test262/test/language");
    private static final Path FALLBACK_LANGUAGE = Paths.get("../test-oracles/test262/test/language");
    private static final Path HARNESS_DIR = Paths.get("test-oracles/test262/harness");
    private static final Path FALLBACK_HARNESS = Paths.get("../test-oracles/test262/harness");

    @Test
    void verifyBaselineOrUpdate() throws Exception {
        if (Boolean.getBoolean("test262.snapshot.update")) {
            updateBaseline();
        } else {
            verifyBaseline();
        }
    }

    /**
     * Default mode: each path in the committed baseline must still PASS.
     * Anything that doesn't is a regression — list the first ~20 in the
     * failure message so the diff is visible without grepping logs.
     */
    private void verifyBaseline() throws Exception {
        List<String> baseline = loadBaseline();
        Path languageRoot = resolveLanguageRoot();
        Path harnessRoot = resolveHarnessRoot();
        String harnessAssert = Files.readString(harnessRoot.resolve("assert.js"));
        String harnessSta = Files.readString(harnessRoot.resolve("sta.js"));

        List<String> regressions = new ArrayList<>();
        int total = baseline.size();
        long t0 = System.nanoTime();

        for (String rel : baseline) {
            Path file = languageRoot.resolve(rel);
            if (!Files.exists(file)) {
                regressions.add(rel + " | MISSING from test262 corpus on disk");
                continue;
            }
            String source = Files.readString(file);
            Test262ExecRunner.Frontmatter fm;
            try {
                fm = Test262ExecRunner.parseFrontmatter(source);
            } catch (Exception ex) {
                regressions.add(rel + " | frontmatter parse: " + ex.getMessage());
                continue;
            }
            Test262ExecRunner.TestResult result = Test262ExecRunner.runOne(
                file, source, fm, harnessAssert, harnessSta, harnessRoot);
            if (result.outcome() != Test262ExecRunner.Outcome.PASS) {
                regressions.add(rel + " | " + result.outcome().name()
                    + ": " + truncate(result.detail(), 140));
            }
        }

        double elapsed = (System.nanoTime() - t0) / 1e9;
        System.out.printf(
            "test262 snapshot: ran %d baseline tests in %.1fs — %d regressions%n",
            total, elapsed, regressions.size());

        if (!regressions.isEmpty()) {
            // Always write the full list to a target file so the user can
            // inspect even when the failure message is truncated.
            Path log = Paths.get("target/test262-snapshot-regressions.txt");
            Files.createDirectories(log.getParent());
            Files.write(log, regressions, StandardCharsets.UTF_8);

            int show = Math.min(regressions.size(), 20);
            StringBuilder msg = new StringBuilder();
            msg.append(regressions.size()).append(" test262 baseline regression(s) — ")
               .append("first ").append(show).append(" shown, full list at ").append(log).append(":\n");
            for (int i = 0; i < show; i++) msg.append("  ").append(regressions.get(i)).append('\n');
            if (regressions.size() > show) msg.append("  …and ").append(regressions.size() - show).append(" more\n");
            msg.append("\nIf this regression is intentional, regenerate the baseline with:\n");
            msg.append("  mvn -pl harmonica-core test -Dtest=Test262SnapshotTest ")
               .append("-DrunTest262Snapshot=true -Dtest262.snapshot.update=true\n");
            fail(msg.toString());
        }
    }

    /**
     * Update mode: walk the entire test262 corpus, collect every PASS, and
     * overwrite the baseline file. Same skip rules and timeout settings as
     * {@link Test262ExecTest} so the two stay in sync.
     */
    private void updateBaseline() throws Exception {
        Path languageRoot = resolveLanguageRoot();
        Path harnessRoot = resolveHarnessRoot();
        String harnessAssert = Files.readString(harnessRoot.resolve("assert.js"));
        String harnessSta = Files.readString(harnessRoot.resolve("sta.js"));

        List<String> passing = new ArrayList<>();
        int scanned = 0;
        long t0 = System.nanoTime();

        try (var stream = Files.walk(languageRoot)) {
            var it = stream
                .filter(p -> p.toString().endsWith(".js") && !p.toString().contains("FIXTURE"))
                .sorted()
                .iterator();
            while (it.hasNext()) {
                Path file = it.next();
                scanned++;
                String source;
                Test262ExecRunner.Frontmatter fm;
                try {
                    source = Files.readString(file);
                    fm = Test262ExecRunner.parseFrontmatter(source);
                } catch (Exception io) {
                    continue;   // unreadable / bad frontmatter — not a baseline candidate
                }
                // Mirror Test262ExecTest's skip policy.
                boolean skipForFeature = fm.features().stream()
                    .anyMatch(Test262ExecRunner.UNSUPPORTED_FEATURES::contains);
                if (skipForFeature) continue;

                Test262ExecRunner.TestResult result = Test262ExecRunner.runOne(
                    file, source, fm, harnessAssert, harnessSta, harnessRoot);
                if (result.outcome() == Test262ExecRunner.Outcome.PASS) {
                    passing.add(rel(file, languageRoot));
                }
            }
        }

        passing.sort(String::compareTo);

        Path target = Files.exists(BASELINE_FILE.getParent()) ? BASELINE_FILE : BASELINE_FILE_FALLBACK;
        Files.createDirectories(target.getParent());
        Files.write(target, passing, StandardCharsets.UTF_8);

        double elapsed = (System.nanoTime() - t0) / 1e9;
        System.out.printf(
            "test262 baseline updated: %d passing of %d scanned (%.1f%%, %.1fs). Wrote %s%n",
            passing.size(), scanned, 100.0 * passing.size() / scanned, elapsed, target);
        System.out.println("Inspect `git diff " + target + "` and commit if the changes are expected.");
    }

    private static List<String> loadBaseline() throws java.io.IOException {
        try (InputStream in = Test262SnapshotTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "Baseline resource not found: " + BASELINE_RESOURCE
                    + " — generate it with `-Dtest262.snapshot.update=true`");
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> out = new ArrayList<>();
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) out.add(line);
                }
                return out;
            }
        }
    }

    private static String rel(Path file, Path root) {
        try { return root.relativize(file).toString(); }
        catch (IllegalArgumentException e) { return file.toString(); }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "<null>";
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
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
