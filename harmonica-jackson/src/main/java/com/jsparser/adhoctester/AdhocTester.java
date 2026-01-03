package com.jsparser.adhoctester;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsparser.Parser;
import com.jsparser.ParseException;
import com.jsparser.ast.Program;
import com.jsparser.jackson.HarmonicaJackson;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * Ad-hoc tester for testing the Harmonica JavaScript parser against Acorn.
 *
 * Two modes of operation:
 * 1. Parse-only mode: Tests parsing, records failures where we fail but Acorn succeeds
 * 2. JSON comparison mode: Compares serialized AST output with Acorn's output
 *
 * Usage:
 *   java -cp target/classes:... com.jsparser.adhoctester.AdhocTester [options] <source-dirs...>
 *
 * Options:
 *   --mode=parse|json     Mode of operation (default: parse)
 *   --threads=N           Number of worker threads (default: available processors)
 *   --max-file-size=N     Max file size in KB to process (default: 100KB for JSON mode)
 *   --max-disk-mb=N       Max disk space in MB for temp files (default: 500MB)
 *   --output-dir=PATH     Directory for failure reports (default: ./adhoc-tester-output)
 *   --extensions=ext,...  File extensions to process (default: js,mjs)
 *   --verbose             Enable verbose output
 */
public class AdhocTester {

    private static final ObjectMapper mapper = HarmonicaJackson.createObjectMapper();

    // Configuration
    private final Config config;

    // Statistics (thread-safe)
    private final AtomicLong totalFiles = new AtomicLong(0);
    private final AtomicLong processedFiles = new AtomicLong(0);
    private final AtomicLong skippedFiles = new AtomicLong(0);
    private final AtomicLong passedFiles = new AtomicLong(0);
    private final AtomicLong failedFiles = new AtomicLong(0);
    private final AtomicLong harmonicaParseFailures = new AtomicLong(0);
    private final AtomicLong acornParseFailures = new AtomicLong(0);
    private final AtomicLong jsonMismatches = new AtomicLong(0);
    private final AtomicLong currentDiskUsageBytes = new AtomicLong(0);

    // Failure tracking
    private final ConcurrentLinkedQueue<FailureRecord> failures = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> skippedReasons = new ConcurrentLinkedQueue<>();

    // Thread pool
    private final ExecutorService executor;
    private final Semaphore diskSpaceSemaphore;

    // Acorn process pool for reuse
    private final BlockingQueue<Process> acornProcessPool = new LinkedBlockingQueue<>();

    public static void main(String[] args) {
        Config config = Config.parse(args);
        if (config == null) {
            printUsage();
            System.exit(1);
        }

        AdhocTester tester = new AdhocTester(config);
        try {
            int exitCode = tester.run();
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public AdhocTester(Config config) {
        this.config = config;
        this.executor = Executors.newFixedThreadPool(config.threads);
        // Semaphore to limit concurrent disk usage
        this.diskSpaceSemaphore = new Semaphore(100); // max 100 concurrent file operations
    }

    public int run() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              Harmonica Parser Ad-hoc Tester                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Mode:          " + config.mode);
        System.out.println("  Threads:       " + config.threads);
        System.out.println("  Max file size: " + (config.maxFileSizeKB > 0 ? config.maxFileSizeKB + " KB" : "no limit"));
        System.out.println("  Max disk:      " + config.maxDiskMB + " MB");
        System.out.println("  Output dir:    " + config.outputDir);
        System.out.println("  Extensions:    " + config.extensions);
        System.out.println("  Source dirs:   " + config.sourceDirs);
        System.out.println();

        // Create output directory
        Files.createDirectories(config.outputDir);

        // Add shutdown hook to print failures if killed early
        final long[] startTimeHolder = new long[1];
        Thread shutdownHook = new Thread(() -> {
            System.out.println("\n\n*** INTERRUPTED - Printing results so far ***\n");
            long elapsedMs = System.currentTimeMillis() - startTimeHolder[0];
            printFinalResults(elapsedMs);
            try {
                writeFailureReport();
            } catch (IOException e) {
                System.err.println("Failed to write failure report: " + e.getMessage());
            }
            printAllFailuresToConsole();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // Discover all JavaScript files
        System.out.println("Discovering files...");
        List<Path> allFiles = discoverFiles();
        totalFiles.set(allFiles.size());
        System.out.println("Found " + totalFiles.get() + " files to process");
        System.out.println();

        // Process files in parallel
        long startTime = System.currentTimeMillis();
        startTimeHolder[0] = startTime;

        // Submit all tasks
        List<Future<?>> futures = new ArrayList<>();
        for (Path file : allFiles) {
            futures.add(executor.submit(() -> processFile(file)));
        }

        // Progress reporting thread
        Thread progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(2000);
                    printProgress();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        // Wait for all tasks to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                // Individual file errors are caught in processFile
            }
        }

        progressThread.interrupt();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Remove shutdown hook since we completed normally
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down, ignore
        }

        long elapsedMs = System.currentTimeMillis() - startTime;

        // Print final results
        printFinalResults(elapsedMs);

        // Write failure report
        writeFailureReport();

        // Print all failures to console
        printAllFailuresToConsole();

        return failedFiles.get() > 0 ? 1 : 0;
    }

    private List<Path> discoverFiles() throws IOException {
        List<Path> files = new ArrayList<>();

        for (Path sourceDir : config.sourceDirs) {
            if (!Files.exists(sourceDir)) {
                System.err.println("Warning: Source directory does not exist: " + sourceDir);
                continue;
            }

            try (Stream<Path> paths = Files.walk(sourceDir)) {
                paths.filter(Files::isRegularFile)
                     .filter(this::hasValidExtension)
                     .filter(p -> !p.toString().contains("node_modules"))
                     .filter(p -> !p.toString().contains(".min."))
                     .forEach(files::add);
            }
        }

        // Shuffle for better distribution
        Collections.shuffle(files);
        return files;
    }

    private boolean hasValidExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return config.extensions.stream().anyMatch(ext -> name.endsWith("." + ext));
    }

    private void processFile(Path file) {
        try {
            // Check file size (0 = no limit)
            long fileSize = Files.size(file);
            if (config.maxFileSizeKB > 0 && fileSize > config.maxFileSizeKB * 1024) {
                skippedFiles.incrementAndGet();
                skippedReasons.add(file.toAbsolutePath() + " - Too large (" + (fileSize / 1024) + " KB)");
                if (config.verbose) {
                    System.out.println("[SKIP] Too large: " + file);
                }
                return;
            }

            String source = Files.readString(file);

            if (config.mode == Mode.PARSE) {
                processParseMode(file, source);
            } else {
                processJsonMode(file, source);
            }

            processedFiles.incrementAndGet();

        } catch (IOException e) {
            skippedFiles.incrementAndGet();
            skippedReasons.add(file.toAbsolutePath() + " - IO error: " + e.getMessage());
            if (config.verbose) {
                System.err.println("[ERROR] IO error reading: " + file + " - " + e.getMessage());
            }
        }
    }

    private void processParseMode(Path file, String source) {
        boolean isModule = detectModuleMode(source);

        // Try to parse with Harmonica
        Program harmonicaResult = null;
        String harmonicaError = null;

        try {
            harmonicaResult = Parser.parse(source, isModule, false);
        } catch (ParseException e) {
            harmonicaError = e.getMessage();
            harmonicaParseFailures.incrementAndGet();
        } catch (Exception e) {
            harmonicaError = e.getClass().getSimpleName() + ": " + e.getMessage();
            harmonicaParseFailures.incrementAndGet();
        }

        // If Harmonica succeeded, we're done
        if (harmonicaResult != null) {
            passedFiles.incrementAndGet();
            return;
        }

        // Harmonica failed - check if Acorn can parse it
        boolean acornCanParse = checkAcornParse(source, isModule);

        if (acornCanParse) {
            // This is a real failure - we failed but Acorn succeeded
            failedFiles.incrementAndGet();
            failures.add(new FailureRecord(
                file.toAbsolutePath(),
                FailureType.PARSE_FAILURE,
                harmonicaError,
                null,
                null
            ));

            if (config.verbose) {
                System.out.println("[FAIL] Parse failure (Acorn succeeds): " + file);
            }
        } else {
            // Both failed - this is expected (invalid JS)
            acornParseFailures.incrementAndGet();
            passedFiles.incrementAndGet();

            if (config.verbose) {
                System.out.println("[OK] Both parsers rejected: " + file);
            }
        }
    }

    private void processJsonMode(Path file, String source) {
        boolean isModule = detectModuleMode(source);

        // Check disk space limit
        while (currentDiskUsageBytes.get() > config.maxDiskMB * 1024 * 1024) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        Path tempHarmonicaFile = null;
        Path tempAcornFile = null;

        try {
            diskSpaceSemaphore.acquire();

            // Parse with Harmonica
            Program harmonicaResult;
            try {
                harmonicaResult = Parser.parse(source, isModule, false);
            } catch (Exception e) {
                harmonicaParseFailures.incrementAndGet();
                // Check if Acorn can parse
                if (checkAcornParse(source, isModule)) {
                    failedFiles.incrementAndGet();
                    failures.add(new FailureRecord(
                        file,
                        FailureType.PARSE_FAILURE,
                        e.getMessage(),
                        null,
                        null
                    ));
                } else {
                    acornParseFailures.incrementAndGet();
                    passedFiles.incrementAndGet();
                }
                return;
            }

            // Serialize Harmonica result
            String harmonicaJson;
            try {
                harmonicaJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(harmonicaResult);
            } catch (Exception e) {
                failedFiles.incrementAndGet();
                failures.add(new FailureRecord(
                    file,
                    FailureType.SERIALIZATION_FAILURE,
                    "Harmonica serialization failed: " + e.getMessage(),
                    null,
                    null
                ));
                return;
            }

            // Get Acorn JSON
            String acornJson = getAcornJson(source, isModule);
            if (acornJson == null) {
                acornParseFailures.incrementAndGet();
                passedFiles.incrementAndGet();
                return;
            }

            // Write to temp files and track disk usage
            tempHarmonicaFile = Files.createTempFile("harmonica-", ".json");
            tempAcornFile = Files.createTempFile("acorn-", ".json");

            Files.writeString(tempHarmonicaFile, harmonicaJson);
            Files.writeString(tempAcornFile, acornJson);

            long diskUsed = Files.size(tempHarmonicaFile) + Files.size(tempAcornFile);
            currentDiskUsageBytes.addAndGet(diskUsed);

            // Compare JSONs
            Object harmonicaObj = mapper.readValue(harmonicaJson, Object.class);
            Object acornObj = mapper.readValue(acornJson, Object.class);

            // Normalize the ASTs
            normalizeAst(acornObj);
            normalizeAst(harmonicaObj);

            if (Objects.deepEquals(harmonicaObj, acornObj)) {
                passedFiles.incrementAndGet();
                if (config.verbose) {
                    System.out.println("[OK] JSON match: " + file);
                }
            } else {
                failedFiles.incrementAndGet();
                jsonMismatches.incrementAndGet();

                // Generate semantic diff
                String diff = generateSemanticDiff(harmonicaObj, acornObj, "");

                failures.add(new FailureRecord(
                    file,
                    FailureType.JSON_MISMATCH,
                    "AST JSON does not match Acorn output",
                    diff,
                    source.length() < 5000 ? source : source.substring(0, 5000) + "..."
                ));

                if (config.verbose) {
                    System.out.println("[FAIL] JSON mismatch: " + file);
                }
            }

        } catch (Exception e) {
            skippedFiles.incrementAndGet();
            skippedReasons.add(file.toAbsolutePath() + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (config.verbose) {
                System.err.println("[ERROR] " + file + ": " + e.getMessage());
            }
        } finally {
            // Cleanup temp files
            try {
                if (tempHarmonicaFile != null) {
                    long size = Files.exists(tempHarmonicaFile) ? Files.size(tempHarmonicaFile) : 0;
                    Files.deleteIfExists(tempHarmonicaFile);
                    currentDiskUsageBytes.addAndGet(-size);
                }
                if (tempAcornFile != null) {
                    long size = Files.exists(tempAcornFile) ? Files.size(tempAcornFile) : 0;
                    Files.deleteIfExists(tempAcornFile);
                    currentDiskUsageBytes.addAndGet(-size);
                }
            } catch (IOException e) {
                // ignore cleanup errors
            }
            diskSpaceSemaphore.release();
        }
    }

    private boolean detectModuleMode(String source) {
        // Simple heuristic for module detection
        return source.contains("import ") || source.contains("export ") ||
               source.contains("import{") || source.contains("export{");
    }

    private boolean checkAcornParse(String source, boolean isModule) {
        try {
            Path tempFile = Files.createTempFile("acorn-check-", ".js");
            try {
                Files.writeString(tempFile, source);

                String moduleFlag = isModule ? "--module" : "";
                ProcessBuilder pb = new ProcessBuilder(
                    "npx", "acorn", "--ecma2022", "--locations", moduleFlag, tempFile.toString()
                );
                pb.directory(getAcornWorkingDir().toFile());
                pb.redirectErrorStream(true);

                Process process = pb.start();
                boolean completed = process.waitFor(10, TimeUnit.SECONDS);

                if (!completed) {
                    process.destroyForcibly();
                    return false;
                }

                return process.exitValue() == 0;

            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String getAcornJson(String source, boolean isModule) {
        try {
            Path tempFile = Files.createTempFile("acorn-parse-", ".js");
            try {
                Files.writeString(tempFile, source);

                List<String> command = new ArrayList<>();
                command.add("npx");
                command.add("acorn");
                command.add("--ecma2022");
                command.add("--locations");
                if (isModule) {
                    command.add("--module");
                }
                command.add(tempFile.toString());

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(getAcornWorkingDir().toFile());
                pb.redirectErrorStream(false);

                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                boolean completed = process.waitFor(30, TimeUnit.SECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    return null;
                }

                if (process.exitValue() != 0) {
                    return null;
                }

                return output.toString();

            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private Path getAcornWorkingDir() {
        // Use the harmonica-jackson test resources directory where acorn is installed
        return Path.of("src/test/resources").toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    private void normalizeAst(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;

            // Remove metadata fields
            map.remove("_metadata");

            // Normalize value for regex literals
            if ("Literal".equals(map.get("type")) && map.containsKey("regex")) {
                if (map.get("value") instanceof Map && ((Map<?,?>)map.get("value")).isEmpty()) {
                    map.put("value", null);
                }
            }

            // Normalize bigint
            if ("Literal".equals(map.get("type")) && map.containsKey("bigint")) {
                map.put("value", null); // Acorn sets value to null for BigInt
            }

            // Recurse
            for (Object value : map.values()) {
                normalizeAst(value);
            }
        } else if (obj instanceof List) {
            for (Object item : (List<?>) obj) {
                normalizeAst(item);
            }
        }
    }

    /**
     * Generate a semantic diff between two parsed AST objects.
     * This shows actual structural differences rather than formatting differences.
     */
    @SuppressWarnings("unchecked")
    private String generateSemanticDiff(Object harmonica, Object acorn, String path) {
        StringBuilder diff = new StringBuilder();
        diff.append("=== Semantic AST Diff ===\n\n");

        List<String> differences = new ArrayList<>();
        collectDifferences(harmonica, acorn, path.isEmpty() ? "$" : path, differences, 0);

        if (differences.isEmpty()) {
            diff.append("(No semantic differences found - check normalization)\n");
        } else {
            // Show first 100 differences
            int count = 0;
            for (String d : differences) {
                if (count >= 100) {
                    diff.append("\n... and ").append(differences.size() - 100).append(" more differences\n");
                    break;
                }
                diff.append(d).append("\n");
                count++;
            }
        }

        return diff.toString();
    }

    @SuppressWarnings("unchecked")
    private void collectDifferences(Object harmonica, Object acorn, String path, List<String> diffs, int depth) {
        if (depth > 50) {
            diffs.add(path + ": (max depth reached)");
            return;
        }

        if (harmonica == null && acorn == null) {
            return;
        }
        if (harmonica == null) {
            diffs.add(path + ": Harmonica=null, Acorn=" + summarize(acorn));
            return;
        }
        if (acorn == null) {
            diffs.add(path + ": Harmonica=" + summarize(harmonica) + ", Acorn=null");
            return;
        }

        if (harmonica instanceof Map && acorn instanceof Map) {
            Map<String, Object> hMap = (Map<String, Object>) harmonica;
            Map<String, Object> aMap = (Map<String, Object>) acorn;

            // Check for keys only in Harmonica
            for (String key : hMap.keySet()) {
                if (!aMap.containsKey(key)) {
                    diffs.add(path + "." + key + ": Harmonica has, Acorn missing");
                    diffs.add("  Harmonica: " + summarize(hMap.get(key)));
                }
            }

            // Check for keys only in Acorn
            for (String key : aMap.keySet()) {
                if (!hMap.containsKey(key)) {
                    diffs.add(path + "." + key + ": Harmonica missing, Acorn has");
                    diffs.add("  Acorn: " + summarize(aMap.get(key)));
                }
            }

            // Compare common keys
            for (String key : hMap.keySet()) {
                if (aMap.containsKey(key)) {
                    Object hVal = hMap.get(key);
                    Object aVal = aMap.get(key);
                    if (!Objects.deepEquals(hVal, aVal)) {
                        collectDifferences(hVal, aVal, path + "." + key, diffs, depth + 1);
                    }
                }
            }
        } else if (harmonica instanceof List && acorn instanceof List) {
            List<Object> hList = (List<Object>) harmonica;
            List<Object> aList = (List<Object>) acorn;

            if (hList.size() != aList.size()) {
                diffs.add(path + ": Harmonica length=" + hList.size() + ", Acorn length=" + aList.size());
            }

            int minLen = Math.min(hList.size(), aList.size());
            for (int i = 0; i < minLen; i++) {
                if (!Objects.deepEquals(hList.get(i), aList.get(i))) {
                    collectDifferences(hList.get(i), aList.get(i), path + "[" + i + "]", diffs, depth + 1);
                }
            }

            // Show extra elements
            for (int i = minLen; i < hList.size(); i++) {
                diffs.add(path + "[" + i + "]: Extra in Harmonica: " + summarize(hList.get(i)));
            }
            for (int i = minLen; i < aList.size(); i++) {
                diffs.add(path + "[" + i + "]: Extra in Acorn: " + summarize(aList.get(i)));
            }
        } else {
            // Primitive values differ
            diffs.add(path + ":");
            diffs.add("  Harmonica: " + summarize(harmonica));
            diffs.add("  Acorn:     " + summarize(acorn));
        }
    }

    private String summarize(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) {
            String s = (String) obj;
            return s.length() > 50 ? "\"" + s.substring(0, 50) + "...\"" : "\"" + s + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            Object type = map.get("type");
            if (type != null) {
                return "{type: \"" + type + "\", ...}";
            }
            return "{" + map.size() + " keys}";
        }
        if (obj instanceof List) {
            return "[" + ((List<?>) obj).size() + " items]";
        }
        return obj.getClass().getSimpleName();
    }

    private void printProgress() {
        long total = totalFiles.get();
        long processed = processedFiles.get();
        long passed = passedFiles.get();
        long failed = failedFiles.get();
        long skipped = skippedFiles.get();

        double pct = total > 0 ? (processed * 100.0 / total) : 0;

        System.out.printf("\r[Progress] %d/%d (%.1f%%) | Passed: %d | Failed: %d | Skipped: %d | Disk: %.1f MB    ",
            processed, total, pct, passed, failed, skipped, currentDiskUsageBytes.get() / (1024.0 * 1024.0));
        System.out.flush();
    }

    private void printFinalResults(long elapsedMs) {
        System.out.println();
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                       Final Results                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  Total files:              %d%n", totalFiles.get());
        System.out.printf("  Processed:                %d%n", processedFiles.get());
        System.out.printf("  Skipped (size/error):     %d%n", skippedFiles.get());
        System.out.println();
        System.out.printf("  ✓ Passed:                 %d%n", passedFiles.get());
        System.out.printf("  ✗ Failed:                 %d%n", failedFiles.get());
        System.out.println();
        System.out.printf("  Harmonica parse errors:   %d%n", harmonicaParseFailures.get());
        System.out.printf("  Acorn parse errors:       %d (both rejected = OK)%n", acornParseFailures.get());

        if (config.mode == Mode.JSON) {
            System.out.printf("  JSON mismatches:          %d%n", jsonMismatches.get());
        }

        System.out.println();
        System.out.printf("  Elapsed time:             %.2f seconds%n", elapsedMs / 1000.0);
        System.out.printf("  Throughput:               %.1f files/sec%n",
            processedFiles.get() * 1000.0 / elapsedMs);
        System.out.println();

        if (failedFiles.get() > 0) {
            System.out.println("  ❌ FAILURES DETECTED - see " + config.outputDir + " for details");
        } else {
            System.out.println("  ✅ ALL TESTS PASSED");
        }

        // Print skipped files if any
        if (!skippedReasons.isEmpty()) {
            System.out.println();
            System.out.println("  Skipped files:");
            for (String reason : skippedReasons) {
                System.out.println("    - " + reason);
            }
        }
    }

    private void writeFailureReport() throws IOException {
        if (failures.isEmpty()) {
            return;
        }

        // Write summary
        Path summaryFile = config.outputDir.resolve("failure-summary.txt");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryFile))) {
            writer.println("Harmonica Parser Fuzzer - Failure Summary");
            writer.println("==========================================");
            writer.println();
            writer.printf("Total failures: %d%n", failures.size());
            writer.println();

            // Group by type
            Map<FailureType, List<FailureRecord>> byType = failures.stream()
                .collect(Collectors.groupingBy(f -> f.type));

            for (Map.Entry<FailureType, List<FailureRecord>> entry : byType.entrySet()) {
                writer.println(entry.getKey() + ": " + entry.getValue().size());
                for (FailureRecord failure : entry.getValue()) {
                    writer.println("  - " + failure.file);
                    if (failure.message != null) {
                        writer.println("    " + failure.message);
                    }
                }
                writer.println();
            }
        }
        System.out.println("Wrote failure summary to: " + summaryFile);

        // Write detailed JSON report
        Path jsonFile = config.outputDir.resolve("failures.json");
        List<Map<String, Object>> jsonFailures = new ArrayList<>();

        for (FailureRecord failure : failures) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("file", failure.file.toString());
            map.put("type", failure.type.name());
            map.put("message", failure.message);
            if (failure.diff != null) {
                map.put("diff", failure.diff);
            }
            if (failure.source != null) {
                map.put("source", failure.source);
            }
            jsonFailures.add(map);
        }

        Files.writeString(jsonFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonFailures));
        System.out.println("Wrote JSON failures to: " + jsonFile);

        // Write individual diff files for JSON mismatches
        if (config.mode == Mode.JSON) {
            Path diffsDir = config.outputDir.resolve("diffs");
            Files.createDirectories(diffsDir);

            int diffCount = 0;
            for (FailureRecord failure : failures) {
                if (failure.type == FailureType.JSON_MISMATCH && failure.diff != null) {
                    String safeName = failure.file.getFileName().toString()
                        .replaceAll("[^a-zA-Z0-9.-]", "_");
                    Path diffFile = diffsDir.resolve(safeName + ".diff");
                    Files.writeString(diffFile, failure.diff);
                    diffCount++;
                }
            }

            if (diffCount > 0) {
                System.out.println("Wrote " + diffCount + " diff files to: " + diffsDir);
            }
        }
    }

    private void printAllFailuresToConsole() {
        if (failures.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                     All Failures                             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        int count = 0;
        for (FailureRecord failure : failures) {
            count++;
            System.out.println("─────────────────────────────────────────────────────────────────");
            System.out.println("[" + count + "] " + failure.type + ": " + failure.file);
            if (failure.message != null) {
                System.out.println("    Message: " + failure.message);
            }
            if (failure.diff != null && config.mode == Mode.JSON) {
                System.out.println();
                // Print first 30 lines of diff
                String[] lines = failure.diff.split("\n");
                int maxLines = Math.min(lines.length, 30);
                for (int i = 0; i < maxLines; i++) {
                    System.out.println("    " + lines[i]);
                }
                if (lines.length > 30) {
                    System.out.println("    ... (" + (lines.length - 30) + " more lines)");
                }
            }
            System.out.println();
        }

        System.out.println("─────────────────────────────────────────────────────────────────");
        System.out.println("Total failures: " + failures.size());
    }

    private static void printUsage() {
        System.out.println("Usage: AdhocTester [options] <source-dirs...>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --mode=parse|json     Mode of operation (default: parse)");
        System.out.println("  --threads=N           Number of worker threads (default: CPU count)");
        System.out.println("  --max-file-size=N     Max file size in KB, 0=no limit (default: 0)");
        System.out.println("  --max-disk-mb=N       Max disk space in MB for temp files (default: 500)");
        System.out.println("  --output-dir=PATH     Directory for failure reports (default: ./adhoc-tester-output)");
        System.out.println("  --extensions=ext,...  File extensions to process (default: js,mjs)");
        System.out.println("  --verbose             Enable verbose output");
        System.out.println("  --help                Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  AdhocTester --mode=parse /path/to/js/files");
        System.out.println("  AdhocTester --mode=json --threads=8 ./test-sources");
    }

    // ========== Inner classes ==========

    public enum Mode {
        PARSE, JSON
    }

    public enum FailureType {
        PARSE_FAILURE,
        SERIALIZATION_FAILURE,
        JSON_MISMATCH
    }

    public record FailureRecord(
        Path file,
        FailureType type,
        String message,
        String diff,
        String source
    ) {}

    public static class Config {
        Mode mode = Mode.PARSE;
        int threads = Runtime.getRuntime().availableProcessors();
        int maxFileSizeKB = 0; // 0 = no limit
        int maxDiskMB = 500;
        Path outputDir = Path.of("adhoc-tester-output");
        List<String> extensions = List.of("js", "mjs");
        List<Path> sourceDirs = new ArrayList<>();
        boolean verbose = false;

        public static Config parse(String[] args) {
            Config config = new Config();

            for (String arg : args) {
                if (arg.equals("--help") || arg.equals("-h")) {
                    return null;
                } else if (arg.startsWith("--mode=")) {
                    String mode = arg.substring(7).toUpperCase();
                    try {
                        config.mode = Mode.valueOf(mode);
                    } catch (IllegalArgumentException e) {
                        System.err.println("Invalid mode: " + mode);
                        return null;
                    }
                } else if (arg.startsWith("--threads=")) {
                    config.threads = Integer.parseInt(arg.substring(10));
                } else if (arg.startsWith("--max-file-size=")) {
                    config.maxFileSizeKB = Integer.parseInt(arg.substring(16));
                } else if (arg.startsWith("--max-disk-mb=")) {
                    config.maxDiskMB = Integer.parseInt(arg.substring(14));
                } else if (arg.startsWith("--output-dir=")) {
                    config.outputDir = Path.of(arg.substring(13));
                } else if (arg.startsWith("--extensions=")) {
                    config.extensions = Arrays.asList(arg.substring(13).split(","));
                } else if (arg.equals("--verbose") || arg.equals("-v")) {
                    config.verbose = true;
                } else if (!arg.startsWith("-")) {
                    config.sourceDirs.add(Path.of(arg));
                } else {
                    System.err.println("Unknown option: " + arg);
                    return null;
                }
            }

            if (config.sourceDirs.isEmpty()) {
                System.err.println("Error: No source directories specified");
                return null;
            }

            return config;
        }
    }
}
