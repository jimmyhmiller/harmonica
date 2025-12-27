package com.jsparser.ast;

public record AssignmentPattern(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Pattern left,
    Expression right
) implements Pattern {
    public AssignmentPattern(
        int start,
        int end,
        SourceLocation loc,
        Pattern left,
        Expression right
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             left,
             right);
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
        return "AssignmentPattern";
    }
}
