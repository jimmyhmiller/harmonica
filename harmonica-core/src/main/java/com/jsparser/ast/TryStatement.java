package com.jsparser.ast;

public record TryStatement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    BlockStatement block,
    CatchClause handler,      // Can be null
    BlockStatement finalizer  // Can be null
) implements Statement {
    public TryStatement(
        int start,
        int end,
        SourceLocation loc,
        BlockStatement block,
        CatchClause handler,
        BlockStatement finalizer
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             block,
             handler,
             finalizer);
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
        return "TryStatement";
    }
}
