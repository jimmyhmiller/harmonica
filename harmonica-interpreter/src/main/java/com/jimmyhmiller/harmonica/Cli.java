package com.jimmyhmiller.harmonica;

import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.Executable;
import com.jimmyhmiller.harmonica.bytecode.Generator;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.NativeBody;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;
import com.jimmyhmiller.harmonica.module.ModuleLoader;
import com.jimmyhmiller.harmonica.module.ModuleResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line entry point. Reads a JavaScript program from a file, an inline
 * {@code -e} string, or stdin, then runs it.
 *
 * <pre>{@code
 *   harmonica path/to/script.js
 *   harmonica path/to/script.mjs        # ES module entry
 *   harmonica -e 'console.log(1 + 2)'   # script
 *   harmonica --input-type=module -e 'import x from "./x.mjs"; …'
 *   echo 'console.log("hi")' | harmonica -
 * }</pre>
 *
 * <p>For file inputs, the entry mode is auto-detected (Node-compatible):
 * {@code .mjs} → ESM, {@code .cjs} → CJS, {@code .js} → governed by the nearest
 * {@code package.json}'s {@code "type"}. {@code -e} / stdin default to script
 * mode unless {@code --input-type=module} is set.
 */
public final class Cli {

    private Cli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(64); }

        String source = null;
        Path file = null;
        String label = null;
        EntryMode forceMode = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> { usage(); return; }
                case "--input-type=module" -> forceMode = EntryMode.MODULE;
                case "--input-type=commonjs" -> forceMode = EntryMode.COMMONJS;
                case "--input-type=script" -> forceMode = EntryMode.SCRIPT;
                case "-e", "--eval" -> {
                    if (i + 1 >= args.length) die("missing argument for " + a);
                    source = args[++i];
                    label = "<eval>";
                }
                case "-" -> {
                    source = new String(System.in.readAllBytes());
                    label = "<stdin>";
                }
                default -> {
                    if (a.startsWith("-")) die("unknown option: " + a);
                    file = Path.of(a);
                    if (!Files.isRegularFile(file)) die("not a file: " + a);
                    label = a;
                }
            }
        }

        if (file == null && source == null) {
            die("no input — provide a file, -e <code>, or - for stdin");
        }

        try {
            if (file != null) {
                runFile(file, forceMode);
            } else {
                runInline(source, label, forceMode != null ? forceMode : EntryMode.SCRIPT);
            }
        } catch (AbruptCompletion ac) {
            System.err.println("Uncaught " + describeThrown(ac.value()));
            System.exit(1);
        } catch (StackOverflowError soe) {
            System.err.println("Uncaught RangeError: Maximum call stack size exceeded");
            System.exit(1);
        } catch (IOException io) {
            System.err.println(label + ": " + io.getMessage());
            System.exit(1);
        } catch (RuntimeException re) {
            String msg = re.getMessage();
            System.err.println(label + ": " + (msg != null ? msg : re.getClass().getSimpleName()));
            if (Boolean.getBoolean("harmonica.cli.trace")) re.printStackTrace();
            System.exit(1);
        }
    }

    enum EntryMode { SCRIPT, MODULE, COMMONJS }

    /** Run a file by detecting its entry mode (or honoring an override). */
    private static void runFile(Path file, EntryMode override) throws IOException {
        EntryMode mode = override;
        if (mode == null) {
            // Auto-detect: extension first, then nearest package.json's "type".
            ModuleResolver.Format fmt = ModuleResolver.formatForFile(file);
            mode = switch (fmt) {
                case ESM -> EntryMode.MODULE;
                case CJS -> EntryMode.COMMONJS;
                case JSON -> EntryMode.COMMONJS;
                case BUILTIN -> EntryMode.SCRIPT;
            };
        }
        switch (mode) {
            case MODULE, COMMONJS -> {
                ModuleLoader loader = new ModuleLoader();
                loader.runEntry(file);
            }
            case SCRIPT -> runScript(Files.readString(file), file.toString());
        }
    }

    /** Run inline source (-e or stdin) in the chosen mode. */
    private static void runInline(String source, String label, EntryMode mode) throws IOException {
        switch (mode) {
            case SCRIPT -> runScript(source, label);
            case MODULE -> runInlineModule(source, label);
            case COMMONJS -> {
                // Treat -e as a CJS module by writing it to a sibling temp .cjs?
                // Simpler: call directly into the loader via a synthetic file.
                Path tmp = Files.createTempFile("harmonica-eval-", ".cjs");
                Files.writeString(tmp, source);
                try { new ModuleLoader().runEntry(tmp); }
                finally { Files.deleteIfExists(tmp); }
            }
        }
    }

    /** Run inline ESM source via a temp file (so the loader has a real path
     *  to resolve specifiers from). */
    private static void runInlineModule(String source, String label) throws IOException {
        Path tmp = Files.createTempFile("harmonica-eval-", ".mjs");
        Files.writeString(tmp, source);
        try { new ModuleLoader().runEntry(tmp); }
        finally { Files.deleteIfExists(tmp); }
    }

    /** Run plain Script source — the legacy single-globals path. */
    private static void runScript(String source, String label) {
        Program ast;
        try {
            ast = Parser.parse(source);
        } catch (Throwable t) {
            System.err.println(label + ": parse error: " + describe(t));
            System.exit(1);
            return;
        }
        Executable exe;
        try {
            exe = Generator.generate(ast);
        } catch (Throwable t) {
            System.err.println(label + ": codegen error: " + describe(t));
            System.exit(1);
            return;
        }
        // Publish a module loader so dynamic {@code import(...)} in
        // script-mode programs can resolve relative to the script's
        // file (or the current working directory for stdin / -e).
        ModuleLoader loader = new ModuleLoader();
        Path referrer = Paths.get(label).toAbsolutePath();
        ModuleLoader.setActive(loader, referrer);
        try {
            Interpreter.interpret(exe, new Object[0], 64);
        } finally {
            ModuleLoader.clearActive();
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    private static String describeThrown(Object v) {
        if (v instanceof JSObject jo) {
            Object name = jo.get("name");
            Object message = jo.get("message");
            String n = AbstractOps.toString(name);
            String m = AbstractOps.toString(message);
            if (m.isEmpty()) return n;
            return n + ": " + m;
        }
        return AbstractOps.toString(v);
    }

    private static void die(String msg) {
        System.err.println("harmonica: " + msg);
        System.exit(64);
    }

    private static void usage() {
        System.err.println("""
            usage: harmonica [options] <file>
                   harmonica [options] -e '<code>'
                   harmonica [options] -

            options:
              -e, --eval <code>          run the given code string
              --input-type=module        treat -e / stdin as an ES module
              --input-type=commonjs      treat -e / stdin as a CJS module
              --input-type=script        treat -e / stdin as a plain script (default)
              -, (filename "-")          read source from stdin
              -h, --help                 show this message

            For file inputs, mode is auto-detected: .mjs → ES module,
            .cjs → CommonJS, .js → driven by nearest package.json "type"
            (default: CommonJS).
            """);
    }
}
