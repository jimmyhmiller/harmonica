package com.jsparser.ast;

public record ImportAttribute(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Node key,      // Identifier or Literal
    Literal value  // Always a Literal (string)
) implements Node {
    public ImportAttribute(
        int start,
        int end,
        SourceLocation loc,
        Node key,
        Literal value
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             key,
             value);
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
        return "ImportAttribute";
    }
}
