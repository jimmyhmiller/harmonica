package com.jsparser.ast;

import java.util.List;

public record NewExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression callee,
    List<Expression> arguments
) implements Expression {
    public NewExpression(Expression callee, List<Expression> arguments) {
        this(0, 0, 0, 0, 0, 0, callee, arguments);
    }

    public NewExpression(
        int start,
        int end,
        SourceLocation loc,
        Expression callee,
        List<Expression> arguments
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             callee,
             arguments);
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
        return "NewExpression";
    }
}
