package com.jsparser.ast;

import java.util.List;

public record VariableDeclaration(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    List<VariableDeclarator> declarations,
    String kind  // "var" | "let" | "const"
) implements Statement {
    public VariableDeclaration(
        int start,
        int end,
        SourceLocation loc,
        List<VariableDeclarator> declarations,
        String kind
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             declarations,
             kind);
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
        return "VariableDeclaration";
    }
}
