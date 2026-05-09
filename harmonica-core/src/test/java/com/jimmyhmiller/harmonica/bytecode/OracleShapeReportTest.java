package com.jimmyhmiller.harmonica.bytecode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diagnostic test: prints our bytecode next to LibJS's for a curated set of
 * sources so we can see divergences.
 *
 * <p>Not an assertion test — just a visibility tool. Output goes to stdout
 * (visible with {@code mvn test ... -Dsurefire.useFile=false} or in
 * surefire-reports/&lt;class&gt;-output.txt).
 */
class OracleShapeReportTest {

    private static LibJsOracle oracle;

    @BeforeAll
    static void setup() {
        oracle = LibJsOracle.resolve();
        assumeTrue(oracle.isAvailable(),
            "LibJS not built — skipping shape report. Looking at: " + oracle.binary());
    }

    @Test
    void printSideBySide() {
        System.out.println(SideBySideDump.render(oracle));
    }
}
