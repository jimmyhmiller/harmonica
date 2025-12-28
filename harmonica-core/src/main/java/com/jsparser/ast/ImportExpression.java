package com.jsparser.ast;

public record ImportExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression source,
    Expression options  // Second argument for import attributes: { with: { type: "json" } }
) implements Expression {
    public ImportExpression(
        int start,
        int end,
        SourceLocation loc,
        Expression source,
        Expression options
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             source,
             options);
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
        return "ImportExpression";
    }
}
