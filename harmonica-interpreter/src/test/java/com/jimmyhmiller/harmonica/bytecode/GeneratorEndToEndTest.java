package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end tests: JS source → parser → generator → interpreter → result.
 *
 * <p>These exercise the full pipeline against the implemented opcode subset.
 */
class GeneratorEndToEndTest {

    private static Object run(String source) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        // Generator allocates exactly what's needed; oversize locals to avoid threading
        // the count through Executable for now.
        return Interpreter.interpret(exe, new Object[0], /* locals */ 64);
    }

    @Test void numericLiteral()      { assertEquals(42.0, run("42")); }
    @Test void addition()            { assertEquals(3.0,  run("1 + 2")); }
    @Test void chained()             { assertEquals(10.0, run("1 + 2 + 3 + 4")); }
    @Test void mixedArith()          { assertEquals(7.0,  run("1 + 2 * 3")); }
    @Test void comparison()          { assertEquals(true, run("1 < 2")); }
    @Test void strictEquality()      { assertEquals(true, run("3 === 3")); }
    @Test void varDeclThenUse()      { assertEquals(5.0,  run("let x = 5; x")); }
    @Test void varDeclThenAdd()      { assertEquals(8.0,  run("let x = 5; let y = 3; x + y")); }
    @Test void assignmentReturns()   { assertEquals(7.0,  run("let x = 0; x = 7; x")); }
    // Note: top-level `return` is illegal in JS scripts; Return-opcode coverage
    // is in InterpreterSmokeTest. End-to-end Return testing waits for FunctionDeclaration.

    @Test void ifThenTaken() {
        assertEquals(1.0, run("let x = 0; if (1 < 2) x = 1; x"));
    }
    @Test void ifThenNotTaken() {
        assertEquals(0.0, run("let x = 0; if (2 < 1) x = 1; x"));
    }
    @Test void ifElseTaken() {
        assertEquals(2.0, run("let x; if (2 < 1) x = 1; else x = 2; x"));
    }

    @Test void whileLoop() {
        // x starts 0, increments by 1 while x < 3.
        assertEquals(3.0, run("let x = 0; while (x < 3) x = x + 1; x"));
    }

    @Test void whileSum() {
        // Classic 1+2+...+5 = 15.
        assertEquals(15.0, run(
            "let i = 1; let sum = 0;" +
            "while (i < 6) { sum = sum + i; i = i + 1; }" +
            "sum"));
    }

    @Test void uncaughtThrow() {
        AbruptCompletion ex = assertThrows(AbruptCompletion.class,
            () -> run("throw 7"));
        assertEquals(7.0, ex.value());
    }
}
