package com.jimmyhmiller.harmonica.bytecode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test-side wrapper around Ladybird's {@code js} shell.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Behavioral</b>: run JS source in LibJS, capture the printed last
 *       completion value and any thrown error. {@link #evaluate(String)}.</li>
 *   <li><b>Bytecode-shape</b>: run JS source through LibJS with bytecode
 *       dumping, capture the dump for diffing against our generator.
 *       {@link #dumpBytecode(String)}.</li>
 * </ul>
 *
 * <p>Locates the {@code js} binary by env var {@code LIBJS_BIN}, then by the
 * default Ladybird build path. {@link #isAvailable()} returns false if the
 * binary isn't found — tests should skip rather than fail in that case.
 */
public final class LibJsOracle {

    /** Override via env var {@code LIBJS_BIN} for non-default install paths. */
    private static final String ENV_OVERRIDE = "LIBJS_BIN";

    /** Default location for a local Ladybird build on macOS. */
    private static final Path DEFAULT_BUILD_PATH = Paths.get(
        System.getProperty("user.home"),
        "Documents/Code/ladybird/Build/release/bin/js");

    private static final long TIMEOUT_SECONDS = 30;

    private final Path jsBinary;

    public LibJsOracle(Path jsBinary) { this.jsBinary = jsBinary; }

    /** Construct using the resolved default location. May not exist; check {@link #isAvailable()}. */
    public static LibJsOracle resolve() {
        String override = System.getenv(ENV_OVERRIDE);
        Path p = override != null ? Paths.get(override) : DEFAULT_BUILD_PATH;
        return new LibJsOracle(p);
    }

    public boolean isAvailable() {
        return Files.isRegularFile(jsBinary) && Files.isExecutable(jsBinary);
    }

    public Path binary() { return jsBinary; }

    // ---------------------------------------------------------------
    //  Behavioral oracle
    // ---------------------------------------------------------------

    /**
     * Run {@code source} through LibJS, return the last completion value as
     * the engine printed it. Equivalent to {@code js -l -c '<source>'}.
     *
     * <p>Throws {@link OracleException} if LibJS reported an error, exited
     * non-zero, or printed to stderr.
     */
    public String evaluate(String source) throws OracleException {
        Result r = run(List.of(
            jsBinary.toString(),
            "--print-last-result",
            "--disable-ansi-colors",
            "--disable-source-location-hints",
            "--evaluate", source
        ));
        if (r.exitCode != 0) {
            throw new OracleException(
                "LibJS exited " + r.exitCode + " for source: " + abbrev(source) + "\n--- stderr ---\n" + r.stderr);
        }
        // -l prints the result on the final line; trim trailing newline only.
        return r.stdout.endsWith("\n") ? r.stdout.substring(0, r.stdout.length() - 1) : r.stdout;
    }

    // ---------------------------------------------------------------
    //  Bytecode-shape oracle
    // ---------------------------------------------------------------

    /**
     * Run {@code source} through LibJS with bytecode dumping enabled.
     * Returns the raw dump string. Parsing and diffing against our generator
     * output is layered on top.
     */
    public String dumpBytecode(String source) throws OracleException {
        return dumpBytecode(source, false);
    }

    public String dumpBytecode(String source, boolean asModule) throws OracleException {
        // The dump goes to stderr in current LibJS. --parse-only suppresses
        // dumping entirely, so we let the program run; the dump is emitted
        // before execution. If the program throws at runtime, "Uncaught
        // exception:" is appended to stderr after the dump — strip it so
        // the parser sees only the bytecode portion.
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add(jsBinary.toString());
        cmd.add("--dump-bytecode");
        cmd.add("--disable-source-location-hints");
        if (asModule) cmd.add("--as-module");
        cmd.add("--evaluate"); cmd.add(source);
        Result r = run(cmd);
        return stripWarnings(trimAtRuntimeError(stripAnsi(r.stderr)));
    }

    /** Strip everything from a runtime-error marker onwards, leaving only the bytecode dump. */
    private static String trimAtRuntimeError(String s) {
        // Common markers: "Uncaught exception:", "[ERROR]", "TypeError:" alone on a line.
        int idx = s.indexOf("\nUncaught exception:");
        if (idx < 0) idx = s.indexOf("Uncaught exception:");
        if (idx >= 0) return s.substring(0, idx);
        return s;
    }

    /**
     * LibJS emits diagnostic {@code WARNING: ...} lines on stderr alongside
     * the bytecode dump (e.g. unhandled-promise-rejection warnings from
     * dynamic imports that fail at runtime). The dump parser doesn't expect
     * them; strip them so the dump remains clean.
     */
    private static String stripWarnings(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            if (line.startsWith("WARNING:")) continue;
            out.append(line).append('\n');
        }
        // Remove the trailing newline we always added.
        if (out.length() > 0) out.setLength(out.length() - 1);
        return out.toString();
    }

    /** Strip ANSI escape sequences (color codes etc.) from a string. */
    public static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*[mK]", "");
    }

    // ---------------------------------------------------------------
    //  Process plumbing
    // ---------------------------------------------------------------

    private record Result(int exitCode, String stdout, String stderr) {}

    private static Result run(List<String> command) throws OracleException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
            // Close stdin immediately — we don't pipe input.
            p.getOutputStream().close();
        } catch (IOException e) {
            throw new OracleException("failed to launch " + command.get(0), e);
        }
        // Drain stdout and stderr CONCURRENTLY in background threads. Doing
        // them sequentially deadlocks: when stderr exceeds the OS pipe buffer
        // (e.g. ~13 MB on test262 S7.4_A5.js, where LibJS dumps eval'd
        // bytecode for each of 65k iterations), the subprocess blocks writing
        // stderr, so stdout EOF never arrives, so a sequential readAllBytes
        // on stdout never returns — and the waitFor timeout below is
        // unreachable. The two-thread drain unblocks the subprocess and lets
        // the timeout actually fire.
        java.util.concurrent.CompletableFuture<byte[]> outF =
            java.util.concurrent.CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
        java.util.concurrent.CompletableFuture<byte[]> errF =
            java.util.concurrent.CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()));
        try {
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                outF.cancel(true);
                errF.cancel(true);
                throw new OracleException("LibJS timed out after " + TIMEOUT_SECONDS + "s");
            }
            String out = new String(outF.get());
            String err = new String(errF.get());
            return new Result(p.exitValue(), out, err);
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            p.destroyForcibly();
            throw new OracleException("error reading LibJS output", e);
        }
    }

    private static byte[] readAll(java.io.InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String abbrev(String s) {
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    public static final class OracleException extends RuntimeException {
        public OracleException(String msg) { super(msg); }
        public OracleException(String msg, Throwable cause) { super(msg, cause); }
    }
}
