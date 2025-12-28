package com.jsparser.ast;

import java.util.List;

public record ArrowFunctionExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Identifier id,       // Always null for arrow functions
    boolean expression,  // true if body is expression, false if block
    boolean generator,   // Always false for arrows
    boolean async,
    List<Pattern> params,
    Node body            // Can be Expression or BlockStatement
) implements Expression {
    public ArrowFunctionExpression(
        int start,
        int end,
        SourceLocation loc,
        Identifier id,
        boolean expression,
        boolean generator,
        boolean async,
        List<Pattern> params,
        Node body
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             id,
             expression,
             generator,
             async,
             params,
             body);
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
        return "ArrowFunctionExpression";
    }
}
