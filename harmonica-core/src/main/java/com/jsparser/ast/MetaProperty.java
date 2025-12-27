package com.jsparser.ast;

public record MetaProperty(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Identifier meta,     // 'new' or 'import'
    Identifier property  // 'target' or 'meta'
) implements Expression {
    public MetaProperty(
        int start,
        int end,
        SourceLocation loc,
        Identifier meta,
        Identifier property
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             meta,
             property);
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
        return "MetaProperty";
    }
}
