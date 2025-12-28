package com.jsparser.ast;

public record PropertyDefinition(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression key,        // Property name (Identifier or PrivateIdentifier)
    Expression value,      // Can be null for class fields without initializer
    boolean computed,
    boolean isStatic
) implements Node {
    public PropertyDefinition(
        int start,
        int end,
        SourceLocation loc,
        Expression key,
        Expression value,
        boolean computed,
        boolean isStatic
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             key,
             value,
             computed,
             isStatic);
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
        return "PropertyDefinition";
    }
}
