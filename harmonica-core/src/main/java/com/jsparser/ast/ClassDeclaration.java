package com.jsparser.ast;

import java.util.List;

public record ClassDeclaration(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Identifier id,         // Class name
    Expression superClass, // Can be null if no extends
    ClassBody body,
    List<Decorator> decorators // Can be empty
) implements Statement {
    public ClassDeclaration(
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

    public ClassDeclaration(
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
        return "ClassDeclaration";
    }
}
