package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Bytecode is generated for {@code source}" tests.
 *
 * <p>For features whose runtime support isn't fully wired (yield, await,
 * import/export), we still want the generator to produce a valid
 * {@link Executable} from the AST without crashing. The interpreter may
 * refuse to execute a particular opcode, but compilation must succeed.
 */
class GenerationOnlyTest {

    private static Executable generate(String source) {
        Program ast = Parser.parse(source);
        return Generator.generate(ast);
    }

    private static Executable generateModule(String source) {
        Program ast = Parser.parse(source, /* forceModuleMode */ true);
        return Generator.generate(ast);
    }

    private static void compilesAndDumps(String source) {
        Executable exe = assertDoesNotThrow(() -> generate(source));
        // Disassembler must also handle every emitted opcode.
        String dump = assertDoesNotThrow(() -> Disassembler.dump(exe));
        assertTrue(dump.contains("Registers:"), "expected dump to include registers header");
    }

    private static void compilesAndDumpsModule(String source) {
        Executable exe = assertDoesNotThrow(() -> generateModule(source));
        String dump = assertDoesNotThrow(() -> Disassembler.dump(exe));
        assertTrue(dump.contains("Registers:"), "expected dump to include registers header");
    }

    // ---------- Generators ----------
    @Test void generatorFunction() {
        compilesAndDumps("function* gen() { yield 1; yield 2; yield 3; }");
    }
    @Test void yieldStar() {
        compilesAndDumps("function* gen() { yield* [1, 2, 3]; }");
    }
    @Test void yieldExpression() {
        compilesAndDumps("function* gen() { let x = yield 1; return x; }");
    }
    @Test void emptyYield() {
        compilesAndDumps("function* gen() { yield; }");
    }

    // ---------- Async ----------
    @Test void asyncFunction() {
        compilesAndDumps("async function f() { let x = await 7; return x + 1; }");
    }
    @Test void asyncArrow() {
        compilesAndDumps("let f = async () => await 42;");
    }
    @Test void asyncGenerator() {
        compilesAndDumps("async function* g() { yield await 1; yield await 2; }");
    }

    // ---------- Modules ----------
    @Test void importDefault() {
        compilesAndDumpsModule("import x from 'm';");
    }
    @Test void importNamed() {
        compilesAndDumpsModule("import {a, b as c} from 'm';");
    }
    @Test void importNamespace() {
        compilesAndDumpsModule("import * as ns from 'm';");
    }
    @Test void exportNamed() {
        compilesAndDumpsModule("let x = 1; export {x};");
    }
    @Test void exportDefault() {
        compilesAndDumpsModule("export default 42;");
    }
    @Test void exportFrom() {
        compilesAndDumpsModule("export {a, b} from 'm';");
    }
    @Test void exportAll() {
        compilesAndDumpsModule("export * from 'm';");
    }
}
