package com.jsparser.ast;

public record TaggedTemplateExpression(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression tag,
    TemplateLiteral quasi
) implements Expression {
    public TaggedTemplateExpression(
        int start,
        int end,
        SourceLocation loc,
        Expression tag,
        TemplateLiteral quasi
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             tag,
             quasi);
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
        return "TaggedTemplateExpression";
    }
}
