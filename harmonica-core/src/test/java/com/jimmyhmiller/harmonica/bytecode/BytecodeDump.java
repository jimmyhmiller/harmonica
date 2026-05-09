package com.jimmyhmiller.harmonica.bytecode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured representation of a bytecode dump, parsed either from
 * {@link Disassembler} output or from {@code js --dump-bytecode}.
 *
 * <p>The two dumpers share a format on purpose, so we can diff them. This
 * record captures only the parts we care about for the shape oracle:
 * register count, block count, constant pool, and the instruction list per
 * block. PC values and opcode-specific operand structure are kept as opaque
 * strings — fine for diffing, refinable later.
 */
public record BytecodeDump(
    int registers,
    int blocksCount,
    List<String> constants,                     // formatted values, e.g. "Int32(3)", "Undefined"
    List<Block> blocks
) {
    public record Block(int index, List<Instruction> instructions) {}

    /**
     * One instruction line. {@code operands} is a key→value map preserving
     * insertion order ({@code dst}, {@code lhs}, {@code rhs}, etc.).
     * {@code positional} captures bareword arguments like {@code `x`} (the
     * identifier in {@code GetGlobal dst:reg5, `x`}).
     */
    public record Instruction(
        int pc,
        String opcode,
        Map<String, String> operands,
        List<String> positional
    ) {}

    // ----------------------------------------------------------------
    //  Parser
    // ----------------------------------------------------------------

    public static BytecodeDump parse(String input) {
        // Strip ANSI defensively — caller usually already did, but be safe.
        input = input.replaceAll("\\x1B?\\[[0-9;]*[mK]", "");

        // LibJS dumps include nested function bodies too, each prefaced with a
        // {@code $<hash>} header. For v1 we only compare the outer (script)
        // dump; trim everything after the second hash header.
        String[] lines = input.split("\n", -1);
        StringBuilder firstSection = new StringBuilder();
        boolean seenHeader = false;
        // Match a function-body header like `name$hash`, `get name$hash`, or
        // `set name$hash` (LibJS prefixes accessors with `get `/`set `).
        java.util.regex.Pattern headerPat = java.util.regex.Pattern.compile("^\\s*((get|set)\\s+)?([A-Za-z_][A-Za-z0-9_]*)?\\$[0-9a-f]+\\b.*$");
        for (String line : lines) {
            if (headerPat.matcher(line).matches()) {
                if (seenHeader) break;
                seenHeader = true;
            }
            firstSection.append(line).append('\n');
        }
        lines = firstSection.toString().split("\n", -1);

        int registers = -1;
        int blockCount = -1;
        List<String> constants = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();

        // State machine: header → constants? → blocks
        enum Mode { HEADER, CONSTANTS, BLOCKS }
        Mode mode = Mode.HEADER;
        Block currentBlock = null;
        List<Instruction> currentInstructions = null;

        outer:
        for (String rawLine : lines) {
            String line = rawLine.replace("\r", "");
            String trimmed = line.trim();

            // Skip the LibJS hash/location header (starts with `$`).
            if (trimmed.startsWith("$")) continue;
            if (trimmed.isEmpty()) continue;

            switch (mode) {
                case HEADER -> {
                    if (trimmed.startsWith("Registers:")) {
                        registers = Integer.parseInt(trimmed.substring("Registers:".length()).trim());
                    } else if (trimmed.startsWith("Blocks:")) {
                        blockCount = Integer.parseInt(trimmed.substring("Blocks:".length()).trim());
                    } else if (trimmed.startsWith("Locals:")) {
                        // Function-body-only line (e.g. `Locals: x~0, y~1`). Outer-script
                        // dumps don't have it; we ignore for now since the outer-script
                        // is what we diff. Once we compare nested function bodies, parse
                        // these into a structured field.
                    } else if (trimmed.equals("Constants:")) {
                        mode = Mode.CONSTANTS;
                    } else if (trimmed.startsWith("block")) {
                        mode = Mode.BLOCKS;
                        Block b = startBlock(trimmed);
                        blocks.add(b);
                        currentBlock = b;
                        currentInstructions = b.instructions();
                    } else {
                        throw new IllegalArgumentException("BytecodeDump.parse: unexpected line in header: " + line);
                    }
                }
                case CONSTANTS -> {
                    if (trimmed.startsWith("[") && trimmed.contains("] =")) {
                        // [N] = Value. The value may be a multi-line String
                        // literal — when present, subsequent non-`[N] =`
                        // lines are continuations and append (preserving
                        // newlines) onto the prior constant's text. We rely
                        // on the matching dump from our generator producing
                        // the SAME accumulated text for byte-perfect diff.
                        int eq = trimmed.indexOf("=");
                        constants.add(trimmed.substring(eq + 1).trim());
                    } else if (trimmed.startsWith("block")) {
                        mode = Mode.BLOCKS;
                        Block b = startBlock(trimmed);
                        blocks.add(b);
                        currentBlock = b;
                        currentInstructions = b.instructions();
                    } else if (!constants.isEmpty()) {
                        // Continuation line for a multi-line String constant.
                        // Append the raw line (with original whitespace) so
                        // both sides round-trip identically. Use line, not
                        // trimmed, so leading whitespace inside the string
                        // is preserved.
                        int last = constants.size() - 1;
                        constants.set(last, constants.get(last) + "\n" + line);
                    } else {
                        throw new IllegalArgumentException("BytecodeDump.parse: unexpected line in constants: " + line);
                    }
                }
                case BLOCKS -> {
                    if (trimmed.startsWith("block")) {
                        Block b = startBlock(trimmed);
                        blocks.add(b);
                        currentBlock = b;
                        currentInstructions = b.instructions();
                    } else if (trimmed.startsWith("[")) {
                        Instruction i = parseInstruction(trimmed);
                        currentInstructions.add(i);
                    } else if (trimmed.startsWith("Exception handlers:")) {
                        // LibJS prints exception-handler tables after the last block.
                        // For v1 we only diff the block instruction list; ignore handler
                        // lines until we add structured comparison. Once we hit this
                        // section, every remaining trimmed `[ ... ]` line in the dump
                        // is a handler entry, not an instruction — bail out of the loop.
                        break outer;
                    } else {
                        throw new IllegalArgumentException("BytecodeDump.parse: unexpected line in block: " + line);
                    }
                }
            }
        }

        return new BytecodeDump(
            registers >= 0 ? registers : 0,
            blockCount >= 0 ? blockCount : blocks.size(),
            constants,
            blocks
        );
    }

    /** {@code blockN:} → empty block ready to receive instructions. */
    private static Block startBlock(String headerLine) {
        // Strip trailing colon.
        String head = headerLine.endsWith(":") ? headerLine.substring(0, headerLine.length() - 1) : headerLine;
        // Index parses out of "blockN" or "block_N".
        Matcher m = Pattern.compile("block(\\d+)").matcher(head);
        if (!m.find()) {
            throw new IllegalArgumentException("BytecodeDump.parse: malformed block header: " + headerLine);
        }
        return new Block(Integer.parseInt(m.group(1)), new ArrayList<>());
    }

    /** {@code [pc] Opcode key:value, key:value, positional} */
    private static Instruction parseInstruction(String line) {
        // Split off the [pc] prefix.
        int rb = line.indexOf(']');
        if (!line.startsWith("[") || rb < 0) {
            throw new IllegalArgumentException("BytecodeDump.parse: missing [pc] prefix: " + line);
        }
        String pcStr = line.substring(1, rb).trim();
        // PC may be decimal (ours) or hex (LibJS); accept both. Leading zeros are common.
        int pc;
        try {
            pc = Integer.parseInt(pcStr);
        } catch (NumberFormatException e) {
            pc = Integer.parseInt(pcStr, 16);
        }

        String rest = line.substring(rb + 1).trim();
        // Opcode is the first whitespace-separated token.
        int sp = indexOfFirstSpace(rest);
        String opcode = sp < 0 ? rest : rest.substring(0, sp);
        String operandsPart = sp < 0 ? "" : rest.substring(sp + 1).trim();

        Map<String, String> operands = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        if (!operandsPart.isEmpty()) {
            for (String piece : splitTopLevelCommas(operandsPart)) {
                String p = piece.trim();
                if (p.isEmpty()) continue;
                int colon = topLevelColon(p);
                if (colon > 0) {
                    operands.put(p.substring(0, colon).trim(), p.substring(colon + 1).trim());
                } else {
                    positional.add(p);
                }
            }
        }
        return new Instruction(pc, opcode, operands, positional);
    }

    private static int indexOfFirstSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /**
     * Split on commas that aren't inside parentheses, brackets, backticks, or quotes.
     * E.g. {@code "dst:reg5, args:[reg6, reg7], `x`"} →
     *     {@code ["dst:reg5", "args:[reg6, reg7]", "`x`"]}.
     */
    private static List<String> splitTopLevelCommas(String s) {
        List<String> out = new ArrayList<>();
        int depthParen = 0, depthBracket = 0;
        boolean inBacktick = false, inDouble = false, inSingle = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inBacktick) { if (c == '`') inBacktick = false; continue; }
            if (inDouble)   { if (c == '"' && s.charAt(i - 1) != '\\') inDouble = false; continue; }
            if (inSingle)   { if (c == '\'' && s.charAt(i - 1) != '\\') inSingle = false; continue; }
            switch (c) {
                case '(' -> depthParen++;
                case ')' -> depthParen--;
                case '[' -> depthBracket++;
                case ']' -> depthBracket--;
                case '`' -> inBacktick = true;
                case '"' -> inDouble = true;
                case '\'' -> inSingle = true;
                case ',' -> {
                    if (depthParen == 0 && depthBracket == 0) {
                        out.add(s.substring(start, i));
                        start = i + 1;
                    }
                }
                default -> {}
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Find the first {@code :} not inside parens/brackets/backticks/quotes. */
    private static int topLevelColon(String s) {
        int depthParen = 0, depthBracket = 0;
        boolean inBacktick = false, inDouble = false, inSingle = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inBacktick) { if (c == '`') inBacktick = false; continue; }
            if (inDouble)   { if (c == '"' && s.charAt(i - 1) != '\\') inDouble = false; continue; }
            if (inSingle)   { if (c == '\'' && s.charAt(i - 1) != '\\') inSingle = false; continue; }
            switch (c) {
                case '(' -> depthParen++;
                case ')' -> depthParen--;
                case '[' -> depthBracket++;
                case ']' -> depthBracket--;
                case '`' -> inBacktick = true;
                case '"' -> inDouble = true;
                case '\'' -> inSingle = true;
                case ':' -> {
                    if (depthParen == 0 && depthBracket == 0) return i;
                }
                default -> {}
            }
        }
        return -1;
    }
}
