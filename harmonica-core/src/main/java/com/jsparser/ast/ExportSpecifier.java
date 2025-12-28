package com.jsparser.ast;

public record ExportSpecifier(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Node local,     // The local name (Identifier or Literal)
    Node exported   // The exported name (Identifier or Literal)
) implements Node {
    public ExportSpecifier(
        int start,
        int end,
        SourceLocation loc,
        Node local,
        Node exported
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             local,
             exported);
    }

    @Override
    public SourceLocation loc() {
        return new SourceLocation(
            new SourceLocation.Position(startLine, startCol),
            new SourceLocation.Position(endLine, endCol)
        );
    }

    @Override
    public String type() {
        return "ExportSpecifier";
    }
}
