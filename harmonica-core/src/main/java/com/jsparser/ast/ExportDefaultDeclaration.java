package com.jsparser.ast;

public record ExportDefaultDeclaration(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Node declaration  // Can be Expression or Declaration
) implements Statement {
    public ExportDefaultDeclaration(
        int start,
        int end,
        SourceLocation loc,
        Node declaration
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             declaration);
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
        return "ExportDefaultDeclaration";
    }
}
