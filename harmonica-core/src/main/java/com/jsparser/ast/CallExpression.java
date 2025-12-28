package com.jsparser.ast;

import java.util.List;

public record CallExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression callee,
    List<Expression> arguments,
    boolean optional
) implements Expression {
    public CallExpression(Expression callee, List<Expression> arguments) {
        this(0, 0, 0, 0, 0, 0, callee, arguments, false);
    }

    public CallExpression(
        int start,
        int end,
        SourceLocation loc,
        Expression callee,
        List<Expression> arguments,
        boolean optional
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             callee,
             arguments,
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
        return "CallExpression";
    }
}
