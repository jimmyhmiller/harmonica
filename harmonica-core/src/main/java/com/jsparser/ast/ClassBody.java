package com.jsparser.ast;

import java.util.List;

public record ClassBody(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    List<Node> body  // List of MethodDefinition or PropertyDefinition
) implements Node {
    public ClassBody(
        int start,
        int end,
        SourceLocation loc,
        List<Node> body
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
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
        return "ClassBody";
    }
}
