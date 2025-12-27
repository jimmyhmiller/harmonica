package com.jsparser.ast;

public record ImportSpecifier(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Node imported,        // The name in the module (Identifier or Literal)
    Identifier local      // The local binding name (always Identifier)
) implements Node {
    public ImportSpecifier(
        int start,
        int end,
        SourceLocation loc,
        Node imported,
        Identifier local
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             imported,
             local);
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
        return "ImportSpecifier";
    }
}
