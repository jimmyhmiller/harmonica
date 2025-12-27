package com.jsparser.ast;

public record VariableDeclarator(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Pattern id,
    Expression init     // Can be null
) {
    public VariableDeclarator(
        int start,
        int end,
        SourceLocation loc,
        Pattern id,
        Expression init
    ) {
        this(start, end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             id, init);
    }

    public SourceLocation loc() {
        return new SourceLocation(
            new SourceLocation.Position(startLine, startCol),
            new SourceLocation.Position(endLine, endCol)
        );
    }

    public String type() {
        return "VariableDeclarator";
    }
}
