package com.jsparser.ast;

public record Literal(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Object value,
    String raw,
    RegexInfo regex,
    String bigint
) implements Expression {
    public Literal(Object value, String raw) {
        this(0, 0, 0, 0, 0, 0, value, raw, null, null);
    }

    public Literal(int start, int end, int startLine, int startCol, int endLine, int endCol, Object value, String raw) {
        this(start, end, startLine, startCol, endLine, endCol, value, raw, null, null);
    }

    public Literal(int start, int end, int startLine, int startCol, int endLine, int endCol, Object value, String raw, RegexInfo regex) {
        this(start, end, startLine, startCol, endLine, endCol, value, raw, regex, null);
    }

    public Literal(
        int start,
        int end,
        SourceLocation loc,
        Object value,
        String raw,
        RegexInfo regex,
        String bigint
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             value,
             raw,
             regex,
             bigint);
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
        return "Literal";
    }

    public record RegexInfo(String pattern, String flags) {}
}
