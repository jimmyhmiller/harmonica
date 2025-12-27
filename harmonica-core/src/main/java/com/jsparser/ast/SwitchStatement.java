package com.jsparser.ast;

import java.util.List;

public record SwitchStatement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression discriminant,
    List<SwitchCase> cases
) implements Statement {
    public SwitchStatement(
        int start,
        int end,
        SourceLocation loc,
        Expression discriminant,
        List<SwitchCase> cases
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             discriminant,
             cases);
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
        return "SwitchStatement";
    }
}
