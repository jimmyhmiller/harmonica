package com.jimmyhmiller.harmonica.benchmarks;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.Executable;
import com.jimmyhmiller.harmonica.bytecode.Generator;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Quick-run helper: try each failing benchmark, dump the AbruptCompletion details. */
public final class FailureTriage {
    public static void main(String[] args) throws Exception {
        Path bench = Paths.get("benchmarks");
        if (!Files.exists(bench)) bench = Paths.get("../benchmarks");

        // Sunspider failing tests
        String[] sunspiderFails = {
            "3d-raytrace", "access-binary-trees", "crypto-aes", "crypto-sha1",
            "date-format-tofte", "date-format-xparb", "math-cordic",
            "regexp-dna", "string-tagcloud", "string-unpack-code"
        };
        for (String name : sunspiderFails) {
            Path p = bench.resolve("sunspider/" + name + ".js");
            String body = Files.readString(p);
            triage("sunspider/" + name, body);
        }

        // Octane failing tests
        String harness = Files.readString(bench.resolve("v8/harness.js"));
        String tail =
            "for (var __i = 0; __i < __benchmarks.length; __i++) {\n" +
            "  var __b = __benchmarks[__i];\n" +
            "  if (__b.setup) __b.setup();\n" +
            "  __b.run();\n" +
            "  if (__b.tearDown) __b.tearDown();\n" +
            "}\n";
        for (String name : new String[]{"richards", "deltablue", "crypto", "raytrace"}) {
            Path p = bench.resolve("v8/" + name + ".js");
            String body = harness + "\n" + Files.readString(p) + "\n" + tail;
            triage("octane/" + name, body);
        }
    }

    private static void triage(String label, String src) {
        System.out.println("=== " + label + " ===");
        try {
            Program ast = Parser.parse(src);
            Executable exe = Generator.generate(ast);
            Interpreter.interpret(exe, new Object[0], 64);
            System.out.println("  PASS");
        } catch (AbruptCompletion ac) {
            String name = "?";
            String msg = "?";
            if (ac.value() instanceof JSObject jo) {
                Object n = jo.get("name");
                Object m = jo.get("message");
                if (n != null && n != com.jimmyhmiller.harmonica.bytecode.Undefined.VALUE) name = n.toString();
                if (m != null && m != com.jimmyhmiller.harmonica.bytecode.Undefined.VALUE) msg = m.toString();
            } else {
                msg = String.valueOf(ac.value());
            }
            System.out.println("  AC " + name + ": " + msg);
        } catch (Throwable t) {
            System.out.println("  HOST " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
