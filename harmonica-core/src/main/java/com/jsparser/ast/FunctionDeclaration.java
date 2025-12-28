package com.jsparser.ast;

import java.util.List;

public record FunctionDeclaration(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Identifier id,
    boolean expression,
    boolean generator,
    boolean async,
    List<Pattern> params,
    BlockStatement body
) implements Statement {
    public FunctionDeclaration(
        int start,
        int end,
        SourceLocation loc,
        Identifier id,
        boolean expression,
        boolean generator,
        boolean async,
        List<Pattern> params,
        BlockStatement body
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             id,
             expression,
             generator,
             async,
             params,
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
        return "FunctionDeclaration";
    }
}
