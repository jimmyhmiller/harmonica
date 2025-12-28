package com.jsparser.ast;

public record ExpressionStatement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression expression,
    String directive
) implements Statement {
    public ExpressionStatement(Expression expression) {
        this(0, 0, 0, 0, 0, 0, expression, null);
    }

    public ExpressionStatement(int start, int end, int startLine, int startCol, int endLine, int endCol, Expression expression) {
        this(start, end, startLine, startCol, endLine, endCol, expression, null);
    }

    public ExpressionStatement(
        int start,
        int end,
        SourceLocation loc,
        Expression expression,
        String directive
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             expression,
             directive);
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
        return "ExpressionStatement";
    }
}
