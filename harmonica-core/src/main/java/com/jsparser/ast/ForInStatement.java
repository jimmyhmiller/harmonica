package com.jsparser.ast;

public record ForInStatement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Node left,   // VariableDeclaration or Expression
    Expression right,
    Statement body
) implements Statement {
    public ForInStatement(
        int start,
        int end,
        SourceLocation loc,
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
        return "ForInStatement";
    }
}
