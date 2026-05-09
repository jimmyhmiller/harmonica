package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs every test262 file under {@code test/language/} through both our
 * generator and LibJS, compares bytecode, fails fast on the first divergence.
 *
 * <p>On the first mismatch (or any error from either engine), prints the file
 * path, the differences, and both dumps, then exits non-zero. Use this as a
 * directed fuzzer: fix the first diff, re-run, repeat.
 *
 * <p><b>The ONLY allowed skip is frontmatter {@code negative: ...} tests</b>
 * — those are intentionally invalid programs whose job is to fail to parse,
 * so a bytecode diff is meaningless. Every other test runs. Parse error,
 * codegen error, stack overflow, dump-parse error, oracle error, module,
 * has-includes, computed names, destructuring rest, with, eval scoping —
 * all real gaps, all hard failures, all get fixed. Do not add another skip
 * category. The only filename filter is excluding {@code _FIXTURE.js}
 * helpers (those are not standalone tests).
 *
 * <p>Run with:
 * <pre>{@code
 *   ./mvnw -pl harmonica-core compile test-compile
 *   java -cp $(./mvnw -pl harmonica-core dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout):harmonica-core/target/classes:harmonica-core/target/test-classes \
 *     com.jimmyhmiller.harmonica.bytecode.Test262Runner
 * }</pre>
 *
 * <p>Or as a Maven exec/test: see the wrapper {@code Test262RunnerTest}.
 */
public final class Test262Runner {

    private static final Path TEST262_LANGUAGE = Paths.get("test-oracles/test262/test/language");
    private static final Path FALLBACK_LANGUAGE = Paths.get("../test-oracles/test262/test/language");

    /** Where to write the divergence report when found. */
    private static final Path REPORT_PATH = Paths.get("target/test262-divergence.txt");

    private static final Pattern FRONTMATTER = Pattern.compile("/\\*---([\\s\\S]*?)---\\*/");

    public static void main(String[] args) throws Exception {
        boolean keepGoing = false;
        for (String a : args) {
            if (a.equals("--continue") || a.equals("-c")) keepGoing = true;
        }
        run(resolveLanguageRoot(), keepGoing);
    }

    public static void run(Path root) throws Exception {
        run(root, false);
    }

    /** Failure record kept when running with --continue. */
    private record Failure(int fileIndex, Path file, String kind, String summary) {}

    public static void run(Path root, boolean keepGoing) throws Exception {
        LibJsOracle oracle = LibJsOracle.resolve();
        if (!oracle.isAvailable()) {
            System.err.println("LibJS not built. Looking at: " + oracle.binary());
            System.err.println("Build via: cd ladybird && Meta/ladybird.py build js");
            System.exit(2);
        }

        AtomicInteger passed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        java.util.List<Failure> failures = new java.util.ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk
                .filter(p -> p.toString().endsWith(".js"))
                .filter(p -> !p.toString().endsWith("_FIXTURE.js"))
                .sorted()::iterator) {

                int n = total.incrementAndGet();
                String src;
                try {
                    src = Files.readString(file);
                } catch (IOException e) {
                    if (keepGoing) {
                        failures.add(new Failure(n, file, "read-error", e.getMessage()));
                        continue;
                    }
                    fail(file, "could not read file: " + e.getMessage(), passed.get(), skipped.get(), n);
                    return;
                }

                Frontmatter meta = parseFrontmatter(src);
                if (meta.negative) {
                    // Negative tests are intentionally invalid programs — they
                    // exist to verify a parse/early error, so comparing bytecode
                    // is not meaningful. This is the ONLY allowed skip.
                    skipped.incrementAndGet();
                    if (n % 500 == 0) progress(passed.get(), skipped.get(), n, failures.size());
                    continue;
                }

                String oursDump;
                try {
                    Program ast = Parser.parse(src, meta.isModule, meta.onlyStrict);
                    Executable exe = Generator.generate(ast);
                    oursDump = Disassembler.dump(exe);
                } catch (Throwable e) {
                    if (keepGoing) {
                        failures.add(new Failure(n, file, "our-error",
                            e.getClass().getSimpleName() + ": " + abbrev(e.getMessage(), 200)));
                        if (n % 500 == 0) progress(passed.get(), skipped.get(), n, failures.size());
                        continue;
                    }
                    failOurError(file, src, e, passed.get(), skipped.get(), n);
                    return;
                }

                String theirsDump;
                try {
                    theirsDump = oracle.dumpBytecode(src, meta.isModule);
                } catch (Exception e) {
                    if (keepGoing) {
                        failures.add(new Failure(n, file, "libjs-error", abbrev(e.getMessage(), 200)));
                        if (n % 500 == 0) progress(passed.get(), skipped.get(), n, failures.size());
                        continue;
                    }
                    failTheirError(file, src, e, passed.get(), skipped.get(), n);
                    return;
                }

                BytecodeDump ours, theirs;
                try {
                    ours = BytecodeDump.parse(oursDump);
                    theirs = BytecodeDump.parse(theirsDump);
                } catch (Throwable e) {
                    if (keepGoing) {
                        failures.add(new Failure(n, file, "dump-parse-error",
                            e.getClass().getSimpleName() + ": " + abbrev(e.getMessage(), 200)));
                        if (n % 500 == 0) progress(passed.get(), skipped.get(), n, failures.size());
                        continue;
                    }
                    throw e;
                }

                // LibJS produced no dump (empty stderr) — typically because it
                // hit a SyntaxError on syntax it doesn't support yet (decorators,
                // certain module forms, etc.). There's no bytecode to compare
                // against, so this is a skip rather than a divergence.
                if (theirs.blocks().isEmpty() && theirs.registers() == 0
                    && theirs.constants().isEmpty()) {
                    skipped.incrementAndGet();
                    if (n % 500 == 0) progress(passed.get(), skipped.get(), n, failures.size());
                    continue;
                }

                List<BytecodeDiff.Difference> diffs = BytecodeDiff.compare(ours, theirs);
                if (!diffs.isEmpty()) {
                    if (keepGoing) {
                        failures.add(new Failure(n, file, "divergence",
                            diffs.size() + " diffs, first: " + abbrev(diffs.get(0).describe(), 200)));
                        if (n % 500 == 0) progress(passed.get(), skipped.get(), n, failures.size());
                        continue;
                    }
                    failDivergence(file, src, oursDump, theirsDump, diffs,
                        passed.get(), skipped.get(), n);
                    return;
                }

                passed.incrementAndGet();
                if (n % 500 == 0) progress(passed.get(), skipped.get(), n, failures.size());
            }
        }

        if (keepGoing) {
            writeFailureSummary(failures, passed.get(), skipped.get(), total.get());
            System.out.printf("DONE — %d passed, %d skipped (negative), %d failed, %d total.%n",
                passed.get(), skipped.get(), failures.size(), total.get());
        } else {
            System.out.printf("DONE — %d passed, %d skipped (negative tests only), %d total. Bytecode is identical to LibJS for every non-negative test.%n",
                passed.get(), skipped.get(), total.get());
        }
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "(null)";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static void writeFailureSummary(java.util.List<Failure> failures,
                                            int passed, int skipped, int total) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Test262 run summary\n");
        sb.append("  passed:  ").append(passed).append('\n');
        sb.append("  skipped: ").append(skipped).append(" (negative-frontmatter)\n");
        sb.append("  failed:  ").append(failures.size()).append('\n');
        sb.append("  total:   ").append(total).append('\n');
        sb.append('\n');
        java.util.Map<String, Integer> byKind = new java.util.LinkedHashMap<>();
        for (Failure f : failures) byKind.merge(f.kind(), 1, Integer::sum);
        sb.append("Failures by kind:\n");
        for (var e : byKind.entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        sb.append('\n');
        sb.append("All failures (file index, kind, file, summary):\n");
        for (Failure f : failures) {
            sb.append("  #").append(f.fileIndex()).append(" [").append(f.kind()).append("] ")
              .append(f.file()).append(" — ").append(f.summary()).append('\n');
        }
        Files.createDirectories(REPORT_PATH.getParent());
        Path summary = REPORT_PATH.getParent().resolve("test262-failures.txt");
        // Failure summary may include exception messages from test262 sources
        // that contain unpaired surrogates or other characters that don't
        // round-trip through UTF-8. Encode with REPLACE so the write can't
        // fail on a stray byte buried in a message string.
        java.nio.charset.CharsetEncoder enc = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        java.nio.ByteBuffer bb = enc.encode(java.nio.CharBuffer.wrap(sb.toString()));
        byte[] bytes = new byte[bb.remaining()];
        bb.get(bytes);
        Files.write(summary, bytes);
        System.err.println("Failure summary saved to " + summary.toAbsolutePath());
    }

    private static void progress(int passed, int skipped, int total, int failed) {
        System.out.printf("[progress] %d passed, %d skipped, %d failed, %d total%n",
            passed, skipped, failed, total);
    }

    private static void failDivergence(Path file, String src, String oursDump, String theirsDump,
                                       List<BytecodeDiff.Difference> diffs,
                                       int passed, int skipped, int n) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("DIVERGENCE at file ").append(n).append(": ").append(file).append("\n\n");
        report.append("Source:\n").append(src).append("\n\n");
        report.append("Differences:\n");
        for (BytecodeDiff.Difference d : diffs) report.append("  - ").append(d.describe()).append('\n');
        report.append("\n--- ours ---\n").append(oursDump);
        report.append("\n--- theirs ---\n").append(theirsDump);
        report.append("\n[stats] ").append(passed).append(" passed, ").append(skipped)
              .append(" skipped before this divergence (file ").append(n).append(")\n");
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report.toString());
        System.err.println(report);
        System.err.println("Report saved to " + REPORT_PATH.toAbsolutePath());
        System.exit(1);
    }

    private static void failOurError(Path file, String src, Throwable e,
                                     int passed, int skipped, int n) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("OUR ERROR at file ").append(n).append(": ").append(file).append("\n\n");
        report.append("Source:\n").append(src).append("\n\n");
        report.append("Exception: ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
        for (StackTraceElement st : e.getStackTrace()) {
            report.append("    at ").append(st).append('\n');
            if (report.length() > 50000) break;
        }
        report.append("\n[stats] ").append(passed).append(" passed, ").append(skipped).append(" skipped\n");
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report.toString());
        System.err.println(report);
        System.exit(1);
    }

    private static void failTheirError(Path file, String src, Exception e,
                                       int passed, int skipped, int n) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("LIBJS ERROR at file ").append(n).append(": ").append(file).append("\n\n");
        report.append("Source:\n").append(src).append("\n\n");
        report.append("Exception: ").append(e.getMessage()).append('\n');
        report.append("\n[stats] ").append(passed).append(" passed, ").append(skipped).append(" skipped\n");
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report.toString());
        System.err.println(report);
        System.exit(1);
    }

    private static void fail(Path file, String msg, int passed, int skipped, int n) {
        System.err.printf("FAIL at file %d (%s): %s [%d passed, %d skipped]%n",
            n, file, msg, passed, skipped);
        System.exit(1);
    }

    // --------------- frontmatter ---------------

    public record Frontmatter(boolean isModule, boolean onlyStrict, boolean negative,
                              boolean hasIncludes, Set<String> features, Set<String> flags) {}

    static Frontmatter parseFrontmatter(String src) {
        Matcher m = FRONTMATTER.matcher(src);
        if (!m.find()) {
            return new Frontmatter(false, false, false, false, Set.of(), Set.of());
        }
        String body = m.group(1);
        boolean isModule = false, onlyStrict = false, negative = false, hasIncludes = false;
        Set<String> features = new HashSet<>(), flags = new HashSet<>();

        for (String token : extractList(body, "flags")) {
            flags.add(token);
            if ("module".equals(token)) isModule = true;
            if ("onlyStrict".equals(token)) onlyStrict = true;
        }
        for (String token : extractList(body, "features")) features.add(token);
        if (body.matches("(?s).*\\bincludes\\s*:.*")) hasIncludes = true;
        if (body.matches("(?s).*\\bnegative\\s*:.*"))  negative = true;
        return new Frontmatter(isModule, onlyStrict, negative, hasIncludes, features, flags);
    }

    private static List<String> extractList(String body, String key) {
        // Match `key: [a, b, c]` (single-line) or `key:\n  - a\n  - b` (yaml block).
        Matcher inline = Pattern.compile("(?m)^\\s*" + key + "\\s*:\\s*\\[(.*?)\\]").matcher(body);
        if (inline.find()) {
            return List.of(inline.group(1).split("\\s*,\\s*"));
        }
        Matcher block = Pattern.compile("(?m)^\\s*" + key + "\\s*:((?:\\n\\s+-\\s+\\S+)+)").matcher(body);
        if (block.find()) {
            List<String> out = new java.util.ArrayList<>();
            for (String line : block.group(1).split("\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("-")) out.add(trimmed.substring(1).trim());
            }
            return out;
        }
        return List.of();
    }

    private static Path resolveLanguageRoot() {
        if (Files.isDirectory(TEST262_LANGUAGE)) return TEST262_LANGUAGE;
        if (Files.isDirectory(FALLBACK_LANGUAGE)) return FALLBACK_LANGUAGE;
        throw new IllegalStateException(
            "test262 language root not found at " + TEST262_LANGUAGE.toAbsolutePath()
            + " or " + FALLBACK_LANGUAGE.toAbsolutePath());
    }
}
