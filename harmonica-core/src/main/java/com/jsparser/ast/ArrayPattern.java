package com.jsparser.ast;

import java.util.List;

public record ArrayPattern(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    List<Pattern> elements
) implements Pattern {
    public ArrayPattern(
        int start,
        int end,
        SourceLocation loc,
        List<Pattern> elements
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             elements);
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
        return "ArrayPattern";
    }
}
