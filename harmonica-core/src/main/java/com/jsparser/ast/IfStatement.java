package com.jsparser.ast;

public record IfStatement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression test,
    Statement consequent,
    Statement alternate  // Can be null
) implements Statement {
    public IfStatement(
        int start,
        int end,
        SourceLocation loc,
        Expression test,
        Statement consequent,
        Statement alternate
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             test,
             consequent,
             alternate);
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
        return "IfStatement";
    }
}
