package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;

import java.util.ArrayList;
import java.util.List;

/**
 * Compare our bytecode dump to LibJS's. Used by tests that assert
 * identical bytecode (modulo PC values, which we treat as opaque since our
 * encoding is array-index and LibJS's is byte-offset).
 *
 * <p>{@link #assertMatches(String, LibJsOracle)} is the main entry. It runs
 * the full pipeline both ways and produces a clear failure message listing
 * every divergence.
 */
public final class BytecodeDiff {

    private BytecodeDiff() {}

    public sealed interface Difference {
        String describe();
    }

    public record RegisterCountMismatch(int ours, int theirs) implements Difference {
        @Override public String describe() {
            return "Registers: ours=" + ours + " vs theirs=" + theirs;
        }
    }
    public record BlockCountMismatch(int ours, int theirs) implements Difference {
        @Override public String describe() {
            return "Blocks: ours=" + ours + " vs theirs=" + theirs;
        }
    }
    public record ConstantPoolMismatch(List<String> ours, List<String> theirs) implements Difference {
        @Override public String describe() {
            return "Constants pool differs:\n  ours:   " + ours + "\n  theirs: " + theirs;
        }
    }
    public record InstructionMismatch(
        int blockIndex,
        int instructionIndex,
        BytecodeDump.Instruction ours,
        BytecodeDump.Instruction theirs
    ) implements Difference {
        @Override public String describe() {
            return "block" + blockIndex + " instruction[" + instructionIndex + "]:\n"
                 + "  ours:   " + render(ours) + "\n"
                 + "  theirs: " + render(theirs);
        }
    }
    public record InstructionCountMismatch(int blockIndex, int ours, int theirs) implements Difference {
        @Override public String describe() {
            return "block" + blockIndex + " instruction count: ours=" + ours + " vs theirs=" + theirs;
        }
    }

    private static String render(BytecodeDump.Instruction i) {
        if (i == null) return "(missing)";
        StringBuilder sb = new StringBuilder(i.opcode());
        for (var e : i.operands().entrySet()) sb.append(' ').append(e.getKey()).append(':').append(e.getValue()).append(',');
        for (String p : i.positional()) sb.append(' ').append(p).append(',');
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    // ----------------------------------------------------------------
    //  Comparison
    // ----------------------------------------------------------------

    /** Compute all differences between two parsed dumps. PC values are not compared. */
    public static List<Difference> compare(BytecodeDump ours, BytecodeDump theirs) {
        List<Difference> diffs = new ArrayList<>();
        if (ours.registers() != theirs.registers()) {
            diffs.add(new RegisterCountMismatch(ours.registers(), theirs.registers()));
        }
        if (ours.blocks().size() != theirs.blocks().size()) {
            diffs.add(new BlockCountMismatch(ours.blocks().size(), theirs.blocks().size()));
        }
        if (!ours.constants().equals(theirs.constants())) {
            diffs.add(new ConstantPoolMismatch(ours.constants(), theirs.constants()));
        }

        int commonBlocks = Math.min(ours.blocks().size(), theirs.blocks().size());
        for (int b = 0; b < commonBlocks; b++) {
            var ourBlock = ours.blocks().get(b);
            var theirBlock = theirs.blocks().get(b);
            int ourN = ourBlock.instructions().size();
            int theirN = theirBlock.instructions().size();
            if (ourN != theirN) {
                diffs.add(new InstructionCountMismatch(b, ourN, theirN));
            }
            int common = Math.min(ourN, theirN);
            for (int k = 0; k < common; k++) {
                var our = ourBlock.instructions().get(k);
                var their = theirBlock.instructions().get(k);
                if (!instructionEqualIgnoringPc(our, their)) {
                    diffs.add(new InstructionMismatch(b, k, our, their));
                }
            }
            // Trailing extras in either side.
            for (int k = common; k < ourN; k++) {
                diffs.add(new InstructionMismatch(b, k, ourBlock.instructions().get(k), null));
            }
            for (int k = common; k < theirN; k++) {
                diffs.add(new InstructionMismatch(b, k, null, theirBlock.instructions().get(k)));
            }
        }
        return diffs;
    }

    private static boolean instructionEqualIgnoringPc(BytecodeDump.Instruction a, BytecodeDump.Instruction b) {
        return a.opcode().equals(b.opcode())
            && a.operands().equals(b.operands())
            && a.positional().equals(b.positional());
    }

    // ----------------------------------------------------------------
    //  Assertion entry
    // ----------------------------------------------------------------

    /**
     * Run {@code source} through both engines, parse both dumps, fail if they
     * differ in any way (other than PCs).
     */
    public static void assertMatches(String source, LibJsOracle oracle) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        String oursDumpStr = Disassembler.dump(exe);
        BytecodeDump ours = BytecodeDump.parse(oursDumpStr);

        String theirsDumpStr = oracle.dumpBytecode(source);
        BytecodeDump theirs = BytecodeDump.parse(theirsDumpStr);

        List<Difference> diffs = compare(ours, theirs);
        if (!diffs.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Bytecode mismatch for: ").append(source).append("\n\n");
            msg.append("Differences:\n");
            for (Difference d : diffs) msg.append("  - ").append(d.describe()).append('\n');
            msg.append("\n--- ours ---\n").append(oursDumpStr);
            msg.append("\n--- theirs ---\n").append(theirsDumpStr);
            throw new AssertionError(msg.toString());
        }
    }
}
