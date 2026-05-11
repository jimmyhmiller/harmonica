package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;

import java.util.List;

/**
 * Diagnostic harness: print our bytecode side-by-side with LibJS's for a
 * curated list of programs, so we can see what to align.
 *
 * <p>Run via: {@code mvn -pl harmonica-interpreter exec:java
 * -Dexec.mainClass=com.jimmyhmiller.harmonica.bytecode.SideBySideDump
 * -Dexec.classpathScope=test}
 *
 * <p>Or simpler: from a unit test ({@link OracleShapeReportTest}).
 */
public final class SideBySideDump {

    private SideBySideDump() {}

    private static final List<String> SAMPLES = List.of(
        "1 + 2",
        "let x = 1; x + 2",
        "let x = 0; if (x < 3) x = 1; x",
        "let x = 0; while (x < 3) x = x + 1; x",
        "if (1 < 2) 7; else 9",
        "let i = 1; let sum = 0; while (i < 6) { sum = sum + i; i = i + 1; } sum"
    );

    public static String render(LibJsOracle oracle) {
        StringBuilder out = new StringBuilder();
        for (String src : SAMPLES) {
            out.append("================================================================\n");
            out.append("SOURCE: ").append(src).append('\n');
            out.append("================================================================\n");

            out.append("--- ours ---\n");
            try {
                Program ast = Parser.parse(src);
                Executable exe = Generator.generate(ast);
                out.append(Disassembler.dump(exe));
            } catch (Exception e) {
                out.append("(generator error: ").append(e.getMessage()).append(")\n");
            }

            if (oracle != null && oracle.isAvailable()) {
                out.append("--- LibJS ---\n");
                try {
                    String dump = oracle.dumpBytecode(src);
                    out.append(stripAnsi(dump));
                } catch (Exception e) {
                    out.append("(LibJS error: ").append(e.getMessage()).append(")\n");
                }
            } else {
                out.append("--- LibJS unavailable ---\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    public static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*[mK]", "");
    }
}
