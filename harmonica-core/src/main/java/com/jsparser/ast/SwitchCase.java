package com.jsparser.ast;

import java.util.List;

public record SwitchCase(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression test,  // null for default case
    List<Statement> consequent
) implements Node {
    public SwitchCase(
        int start,
        int end,
        SourceLocation loc,
        Expression test,
        List<Statement> consequent
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             test,
             consequent);
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
        return "SwitchCase";
    }
}
