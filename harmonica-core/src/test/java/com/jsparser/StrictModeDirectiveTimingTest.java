package com.jsparser;

import com.jsparser.ast.Program;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify strict mode directive handling timing.
 *
 * Bug report: Strict mode directives are applied only after program and function
 * bodies have been parsed, so strict-only syntax errors (e.g., using `with` in a
 * strict function) are silently accepted.
 *
 * These tests verify whether this bug exists by checking that strict-mode violations
 * are properly rejected during parsing.
 */
public class StrictModeDirectiveTimingTest {

    /**
     * Test that 'with' statement in a function with "use strict" directive throws an error.
     *
     * According to the ECMAScript spec, 'with' statements are not allowed in strict mode.
     * The parser should reject this code during parsing.
     */
    @Test
    @DisplayName("BUG: 'with' in strict function should throw parse error")
    void testWithInStrictFunction_ShouldThrow() {
        String source = "function f(){ \"use strict\"; with (obj) {} }";

        // First verify the oracle (reference parser) rejects this
        Exception oracleException = assertThrows(Exception.class, () -> {
            OracleParser.parse(source);
        }, "Oracle parser should reject 'with' in strict mode function");

        System.out.println("Oracle correctly throws: " + oracleException.getMessage());

        // Now test our parser - if the bug exists, this will NOT throw
        // and the test will fail, proving the bug
        assertThrows(Exception.class, () -> {
            Parser.parse(source);
        }, "BUG CONFIRMED: Parser accepts 'with' in strict mode function - strict mode is not applied until after parsing");
    }

    /**
     * Test that 'with' at program level with "use strict" directive throws an error.
     */
    @Test
    @DisplayName("BUG: 'with' in strict program should throw parse error")
    void testWithInStrictProgram_ShouldThrow() {
        String source = "\"use strict\"; with (obj) { x = 1; }";

        // First verify the oracle rejects this
        Exception oracleException = assertThrows(Exception.class, () -> {
            OracleParser.parse(source);
        }, "Oracle parser should reject 'with' in strict mode program");

        System.out.println("Oracle correctly throws: " + oracleException.getMessage());

        // Now test our parser
        assertThrows(Exception.class, () -> {
            Parser.parse(source);
        }, "BUG CONFIRMED: Parser accepts 'with' in strict mode program - strict mode is not applied until after parsing");
    }

    /**
     * Test that 'with' statement WITHOUT strict mode is allowed (sanity check).
     */
    @Test
    @DisplayName("'with' without strict mode should parse successfully")
    void testWithWithoutStrictMode_ShouldSucceed() {
        String source = "with (obj) { x = 1; }";

        // Both parsers should accept this
        assertDoesNotThrow(() -> {
            OracleParser.parse(source);
        }, "Oracle should accept 'with' without strict mode");

        assertDoesNotThrow(() -> {
            Parser.parse(source);
        }, "Parser should accept 'with' without strict mode");
    }

    /**
     * Test that 'with' in nested function with outer strict mode throws.
     * Strict mode propagates to nested functions.
     */
    @Test
    @DisplayName("BUG: 'with' in nested function under strict mode should throw")
    void testWithInNestedFunctionUnderStrictMode_ShouldThrow() {
        String source = "\"use strict\"; function outer() { function inner() { with (obj) {} } }";

        // Oracle should reject
        Exception oracleException = assertThrows(Exception.class, () -> {
            OracleParser.parse(source);
        }, "Oracle should reject 'with' in nested function under strict mode");

        System.out.println("Oracle correctly throws: " + oracleException.getMessage());

        // Our parser should also reject
        assertThrows(Exception.class, () -> {
            Parser.parse(source);
        }, "BUG CONFIRMED: Parser accepts 'with' in nested function under strict mode");
    }

    /**
     * Test that strict mode works correctly when applied BEFORE the 'with'.
     * This tests if strict mode set in an outer context affects inner parsing.
     */
    @Test
    @DisplayName("BUG: Function-level strict mode should affect subsequent statements")
    void testStrictModeInFunction_AffectsSubsequentStatements() {
        // The "use strict" comes before the with, so it should be rejected
        String source = """
            function test() {
                "use strict";
                var x = 1;
                with (obj) { y = 2; }
            }
            """;

        // Oracle should reject
        assertThrows(Exception.class, () -> {
            OracleParser.parse(source);
        }, "Oracle should reject 'with' after 'use strict' in same function");

        // Our parser should also reject (but won't if the bug exists)
        assertThrows(Exception.class, () -> {
            Parser.parse(source);
        }, "BUG CONFIRMED: Parser accepts 'with' even after 'use strict' directive in same function");
    }

    /**
     * Test that multiple statements after "use strict" are all subject to strict mode.
     */
    @Test
    @DisplayName("BUG: Multiple violations after 'use strict' should all throw")
    void testMultipleStatementsAfterUseStrict() {
        String source = """
            "use strict";
            var x = 1;
            var y = 2;
            with (obj) { z = 3; }
            """;

        // Oracle should reject
        assertThrows(Exception.class, () -> {
            OracleParser.parse(source);
        }, "Oracle should reject 'with' in strict program");

        // Our parser should also reject
        assertThrows(Exception.class, () -> {
            Parser.parse(source);
        }, "BUG CONFIRMED: Parser accepts 'with' even with multiple statements after 'use strict'");
    }

    /**
     * Test strict mode in arrow function body.
     */
    @Test
    @DisplayName("BUG: 'with' in arrow function with strict mode should throw")
    void testWithInStrictArrowFunction_ShouldThrow() {
        String source = "var f = () => { \"use strict\"; with (obj) {} };";

        // Oracle should reject
        Exception oracleException = assertThrows(Exception.class, () -> {
            OracleParser.parse(source);
        }, "Oracle should reject 'with' in strict arrow function");

        System.out.println("Oracle correctly throws: " + oracleException.getMessage());

        // Our parser should also reject
        assertThrows(Exception.class, () -> {
            Parser.parse(source);
        }, "BUG CONFIRMED: Parser accepts 'with' in strict arrow function");
    }

    /**
     * Verify that when strict mode IS properly enforced (before any statements),
     * the parser does reject 'with'.
     *
     * This is a control test - strict mode that's explicitly set should work.
     */
    @Test
    @DisplayName("'with' should be rejected when strictMode is already true")
    void testWithRejectedWhenStrictModeAlreadyTrue() {
        // In a module, strict mode is always on
        String source = "with (obj) { x = 1; }";

        // Parse as module (forceModuleMode=true) - should reject 'with'
        // Modules are implicitly strict
        assertThrows(Exception.class, () -> {
            Parser.parse(source, true); // true = forceModuleMode
        }, "Parser should reject 'with' in module mode (implicit strict)");
    }

    /**
     * Verify that forceStrictMode=true correctly rejects 'with'.
     * This proves the strict mode checking code works when strictMode is set upfront.
     */
    @Test
    @DisplayName("'with' should be rejected when forceStrictMode is true")
    void testWithRejectedWhenForceStrictModeTrue() {
        String source = "with (obj) { x = 1; }";

        // Parse with forceStrictMode=true - should reject 'with'
        assertThrows(Exception.class, () -> {
            Parser.parse(source, false, true); // forceStrictMode=true
        }, "Parser should reject 'with' when forceStrictMode is true");
    }
}
