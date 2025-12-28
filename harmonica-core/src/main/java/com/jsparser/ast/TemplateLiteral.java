package com.jsparser.ast;

import java.util.List;

public record TemplateLiteral(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    List<Expression> expressions,
    List<TemplateElement> quasis
) implements Expression {
    public TemplateLiteral(
        int start,
        int end,
        SourceLocation loc,
        List<Expression> expressions,
        List<TemplateElement> quasis
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             expressions,
             quasis);
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
        return "TemplateLiteral";
    }
}
