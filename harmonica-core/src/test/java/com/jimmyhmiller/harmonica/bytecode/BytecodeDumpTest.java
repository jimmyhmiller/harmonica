package com.jimmyhmiller.harmonica.bytecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeDumpTest {

    @Test
    void parseLibJsDump() {
        String input = """
            $811c9dc5
              Registers: 5
              Blocks:    1
              Constants:
                [0] = Int32(1)
                [1] = Int32(2)
                [2] = Int32(3)

            block0:
              [   0] End value:Int32(3)
            """;
        BytecodeDump d = BytecodeDump.parse(input);
        assertEquals(5, d.registers());
        assertEquals(1, d.blocksCount());
        assertEquals(List.of("Int32(1)", "Int32(2)", "Int32(3)"), d.constants());
        assertEquals(1, d.blocks().size());

        BytecodeDump.Block b = d.blocks().get(0);
        assertEquals(0, b.index());
        assertEquals(1, b.instructions().size());

        BytecodeDump.Instruction i = b.instructions().get(0);
        assertEquals("End", i.opcode());
        assertEquals(0, i.pc());
        assertEquals("Int32(3)", i.operands().get("value"));
    }

    @Test
    void parseLibJsWithMultipleBlocks() {
        String input = """
            $6d0ecf6b eval:1:1
              Registers: 9
              Blocks:    4
              Constants:
                [0] = Int32(0)
                [1] = Undefined

            block0:
              [   0] InitializeLexicalBinding `x`, src:Int32(0)
              [  18] Mov dst:reg5, src:Undefined

            block1:
              [  28] GetGlobal dst:reg6, `x`
              [  40] JumpLessThan lhs:reg6, rhs:Int32(3), true_target:block2, false_target:block3

            block2:
              [  58] Jump target:block1

            block3:
              [  c8] End value:reg7
            """;
        BytecodeDump d = BytecodeDump.parse(input);
        assertEquals(9, d.registers());
        assertEquals(4, d.blocks().size());

        BytecodeDump.Instruction init = d.blocks().get(0).instructions().get(0);
        assertEquals("InitializeLexicalBinding", init.opcode());
        assertEquals(List.of("`x`"), init.positional());
        assertEquals("Int32(0)", init.operands().get("src"));

        BytecodeDump.Instruction jlt = d.blocks().get(1).instructions().get(1);
        assertEquals("JumpLessThan", jlt.opcode());
        assertEquals("reg6", jlt.operands().get("lhs"));
        assertEquals("Int32(3)", jlt.operands().get("rhs"));
        assertEquals("block2", jlt.operands().get("true_target"));
        assertEquals("block3", jlt.operands().get("false_target"));
    }

    @Test
    void parseOurDump() {
        // Run a real Disassembler output through the parser.
        String src = "let x = 0; while (x < 3) x = x + 1; x";
        var ast = com.jimmyhmiller.harmonica.Parser.parse(src);
        Executable exe = Generator.generate(ast);
        String dumpStr = Disassembler.dump(exe);
        BytecodeDump d = BytecodeDump.parse(dumpStr);

        assertTrue(d.registers() > 0);
        assertTrue(d.blocks().size() >= 2, "expected multiple blocks for a while loop");
        // Last block should end with End.
        BytecodeDump.Block last = d.blocks().get(d.blocks().size() - 1);
        BytecodeDump.Instruction lastOp = last.instructions().get(last.instructions().size() - 1);
        assertEquals("End", lastOp.opcode());
    }
}
