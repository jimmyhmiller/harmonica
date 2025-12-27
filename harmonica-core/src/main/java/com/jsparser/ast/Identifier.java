package com.jsparser.ast;

public record Identifier(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    String name
) implements Expression, Pattern {
    public Identifier(String name) {
        this(0, 0, 0, 0, 0, 0, name);
    }

    public Identifier(
        int start,
        int end,
        SourceLocation loc,
        String name
    ) {
        this(start, end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             name);
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
        return "Identifier";
    }
}
