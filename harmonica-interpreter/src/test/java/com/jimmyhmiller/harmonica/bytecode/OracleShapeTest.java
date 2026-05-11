package com.jimmyhmiller.harmonica.bytecode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Strict bytecode-shape oracle tests. Each test asserts our bytecode is
 * identical to LibJS's for the same source.
 *
 * <p>Currently we expect <b>most of these to fail</b> because we have several
 * known gaps: no constant folding, no completion register, top-level
 * {@code let} treated as Local rather than lexical binding, no fused jumps,
 * no Mov2. As we close each gap, more tests will go green.
 */
class OracleShapeTest {

    private static LibJsOracle oracle;

    @BeforeAll
    static void setup() {
        oracle = LibJsOracle.resolve();
        assumeTrue(oracle.isAvailable(), "LibJS not built");
    }

    @Test void number()       { BytecodeDiff.assertMatches("1 + 2", oracle); }
    @Test void letBinding()   { BytecodeDiff.assertMatches("let x = 1; x + 2", oracle); }
    @Test void ifTaken()      { BytecodeDiff.assertMatches("let x = 0; if (x < 3) x = 1; x", oracle); }
    @Test void whileLoop()    { BytecodeDiff.assertMatches("let x = 0; while (x < 3) x = x + 1; x", oracle); }
    @Test void ifFolded()     { BytecodeDiff.assertMatches("if (1 < 2) 7; else 9", oracle); }
}
