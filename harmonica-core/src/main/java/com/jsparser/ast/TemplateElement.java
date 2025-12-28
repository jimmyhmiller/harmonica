package com.jsparser.ast;

public record TemplateElement(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    TemplateElementValue value,
    boolean tail
) implements Node {
    public TemplateElement(
        int start,
        int end,
        SourceLocation loc,
        TemplateElementValue value,
        boolean tail
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             value,
             tail);
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
        return "TemplateElement";
    }

    public record TemplateElementValue(
        String raw,
        String cooked
    ) {}
}
