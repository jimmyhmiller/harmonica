package com.jsparser.ast;

public record LogicalExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression left,
    String operator,  // "&&" | "||" | "??"
    Expression right
) implements Expression {
    public LogicalExpression(
        int start,
        int end,
        SourceLocation loc,
        Expression left,
        String operator,
        Expression right
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             left,
             operator,
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
        return "LogicalExpression";
    }
}
