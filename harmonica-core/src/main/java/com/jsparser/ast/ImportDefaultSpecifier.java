package com.jsparser.ast;

public record ImportDefaultSpecifier(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Identifier local  // The local binding name for the default import
) implements Node {
    public ImportDefaultSpecifier(
        int start,
        int end,
        SourceLocation loc,
        Identifier local
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
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
        return "ImportDefaultSpecifier";
    }
}
