package com.jsparser.ast;

import java.util.List;

public record MethodDefinition(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression key,        // Property name (Identifier or PrivateIdentifier)
    FunctionExpression value,
    String kind,          // "constructor" | "method" | "get" | "set"
    boolean computed,
    boolean isStatic
) implements Node {
    public MethodDefinition(
        int start,
        int end,
        SourceLocation loc,
        Expression key,
        FunctionExpression value,
        String kind,
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
             kind,
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
        return "MethodDefinition";
    }
}
