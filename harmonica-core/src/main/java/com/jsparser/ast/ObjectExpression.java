package com.jsparser.ast;

import java.util.List;

public record ObjectExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    List<Node> properties  // Can be Property or SpreadElement
) implements Expression {
    public ObjectExpression(List<Node> properties) {
        this(0, 0, 0, 0, 0, 0, properties);
    }

    public ObjectExpression(
        int start,
        int end,
        SourceLocation loc,
        List<Node> properties
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             properties);
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
        return "ObjectExpression";
    }
}
