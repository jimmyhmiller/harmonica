package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisassemblerTest {

    private static String dump(String source) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        return Disassembler.dump(exe);
    }

    @Test
    void basicShape() {
        // `1 + 2` is constant-folded by the generator, so we expect just `End value:Int32(3)`.
        String d = dump("1 + 2");
        // Header
        assertTrue(d.contains("Registers:"), d);
        assertTrue(d.contains("Blocks:"),    d);
        assertTrue(d.contains("Constants:"), d);
        assertTrue(d.contains("End "),       d);
        assertTrue(d.contains("Int32(3)"),   d);
    }

    @Test
    void unfoldedAdd() {
        // `let x = 1; x + 2` keeps the Add since x isn't a constant at lowering time.
        String d = dump("let x = 1; x + 2");
        assertTrue(d.contains("Add "), d);
        assertTrue(d.contains("Int32(2)"), d);
    }

    @Test
    void blocksFromJumps() {
        // while loop produces multiple blocks
        String d = dump("let x = 0; while (x < 3) x = x + 1; x");
        assertTrue(d.contains("block0:"), d);
        assertTrue(d.contains("block1:"), d);
        assertTrue(d.contains("block2:"), d);
        assertTrue(d.contains("Jump target:block"), d);
    }

    @Test
    void valueFormatting() {
        assertEquals("Int32(42)",   Disassembler.formatValue(42.0));
        assertEquals("Int32(-1)",   Disassembler.formatValue(-1.0));
        assertEquals("Bool(true)",  Disassembler.formatValue(true));
        assertEquals("Undefined",   Disassembler.formatValue(Undefined.VALUE));
        assertEquals("Null",        Disassembler.formatValue(null));
        assertEquals("Double(1.5)", Disassembler.formatValue(1.5));
    }
}
