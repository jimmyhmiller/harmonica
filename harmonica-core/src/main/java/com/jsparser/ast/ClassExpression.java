package com.jsparser.ast;

import java.util.List;

public record ClassExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Identifier id,         // Class name (can be null for anonymous class)
    Expression superClass, // Can be null if no extends
    ClassBody body,
    List<Decorator> decorators // Can be empty
) implements Expression {
    public ClassExpression(
        int start,
        int end,
        SourceLocation loc,
        Identifier id,
        Expression superClass,
        ClassBody body
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             id,
             superClass,
             body,
             List.of());
    }

    public ClassExpression(
        int start,
        int end,
        int startLine,
        int startCol,
        int endLine,
        int endCol,
        Identifier id,
        Expression superClass,
        ClassBody body
    ) {
        this(start, end, startLine, startCol, endLine, endCol, id, superClass, body, List.of());
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
        return "ClassExpression";
    }
}
