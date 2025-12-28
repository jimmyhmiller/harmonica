package com.jsparser.ast;

public record ForStatement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Node init,        // Can be VariableDeclaration | Expression | null
    Expression test,  // Can be null
    Expression update, // Can be null
    Statement body
) implements Statement {
    public ForStatement(
        int start,
        int end,
        SourceLocation loc,
        Node init,
        Expression test,
        Expression update,
        Statement body
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             init,
             test,
             update,
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
        return "ForStatement";
    }
}
