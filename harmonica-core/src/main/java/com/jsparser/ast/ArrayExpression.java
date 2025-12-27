package com.jsparser.ast;

import java.util.List;

public record ArrayExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    List<Expression> elements
) implements Expression {
    public ArrayExpression(List<Expression> elements) {
        this(0, 0, 0, 0, 0, 0, elements);
    }

    public ArrayExpression(
        int start,
        int end,
        SourceLocation loc,
        List<Expression> elements
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             elements);
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
        return "ArrayExpression";
    }
}
