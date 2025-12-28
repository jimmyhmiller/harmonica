package com.jsparser.ast;

public record PrivateIdentifier(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    String name  // Name without the # prefix
) implements Expression {
    public PrivateIdentifier(
        int start,
        int end,
        SourceLocation loc,
        String name
    ) {
        this(start,
             end,
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
        return "PrivateIdentifier";
    }
}
