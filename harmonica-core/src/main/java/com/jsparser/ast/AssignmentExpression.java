package com.jsparser.ast;

public record AssignmentExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    String operator,
    Node left,  // Can be Expression or Pattern (for destructuring)
    Expression right
) implements Expression {
    public AssignmentExpression(String operator, Node left, Expression right) {
        this(0, 0, 0, 0, 0, 0, operator, left, right);
    }

    public AssignmentExpression(
        int start,
        int end,
        SourceLocation loc,
        String operator,
        Node left,
        Expression right
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             operator,
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
        return "AssignmentExpression";
    }
}
