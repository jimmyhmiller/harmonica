package com.jsparser.ast;

public record CatchClause(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Pattern param,           // The exception parameter (can be null in ES2019+)
    BlockStatement body
) implements Node {
    public CatchClause(
        int start,
        int end,
        SourceLocation loc,
        Pattern param,
        BlockStatement body
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             param,
             body);
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
        return "CatchClause";
    }
}
