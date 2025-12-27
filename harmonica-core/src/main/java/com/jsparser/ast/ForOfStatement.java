package com.jsparser.ast;

public record ForOfStatement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    boolean await,
    Node left,   // VariableDeclaration or Expression
    Expression right,
    Statement body
) implements Statement {
    public ForOfStatement(
        int start,
        int end,
        SourceLocation loc,
        boolean await,
        Node left,
        Expression right,
        Statement body
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             await,
             left,
             right,
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
        return "ForOfStatement";
    }
}
