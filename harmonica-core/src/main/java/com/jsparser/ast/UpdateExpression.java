package com.jsparser.ast;

public record UpdateExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    String operator,  // "++" | "--"
    boolean prefix,   // true for ++x, false for x++
    Expression argument
) implements Expression {
    public UpdateExpression(
        int start,
        int end,
        SourceLocation loc,
        String operator,
        boolean prefix,
        Expression argument
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             operator,
             prefix,
             argument);
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
        return "UpdateExpression";
    }
}
