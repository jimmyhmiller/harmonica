package com.jsparser.ast;

import java.util.List;

public record SequenceExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    List<Expression> expressions
) implements Expression {
    public SequenceExpression(
        int start,
        int end,
        SourceLocation loc,
        List<Expression> expressions
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             expressions);
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
        return "SequenceExpression";
    }
}
