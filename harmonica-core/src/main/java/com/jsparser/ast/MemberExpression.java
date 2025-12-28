package com.jsparser.ast;

public record MemberExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression object,
    Expression property,
    boolean computed,
    boolean optional
) implements Expression, Pattern {
    public MemberExpression(Expression object, Expression property, boolean computed) {
        this(0, 0, 0, 0, 0, 0, object, property, computed, false);
    }

    public MemberExpression(
        int start,
        int end,
        SourceLocation loc,
        Expression object,
        Expression property,
        boolean computed,
        boolean optional
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             object,
             property,
             computed,
             optional);
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
        return "MemberExpression";
    }
}
