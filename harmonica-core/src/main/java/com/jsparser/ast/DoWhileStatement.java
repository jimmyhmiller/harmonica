package com.jsparser.ast;

public record DoWhileStatement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Statement body,
    Expression test
) implements Statement {
    public DoWhileStatement(
        int start,
        int end,
        SourceLocation loc,
        Statement body,
        Expression test
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             body,
             test);
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
        return "DoWhileStatement";
    }
}
