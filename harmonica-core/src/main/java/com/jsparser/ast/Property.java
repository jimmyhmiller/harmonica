package com.jsparser.ast;

public record Property(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    boolean method,
    boolean shorthand,
    boolean computed,
    Node key,
    Node value,
    String kind
) implements Node {
    public Property(int startLine, int startCol, int endLine, int endCol, Node key, Node value, String kind, boolean computed) {
        this(0, 0, startLine, startCol, endLine, endCol, false, false, computed, key, value, kind);
    }

    public Property(
        int start,
        int end,
        SourceLocation loc,
        boolean method,
        boolean shorthand,
        boolean computed,
        Node key,
        Node value,
        String kind
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             method,
             shorthand,
             computed,
             key,
             value,
             kind);
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
        return "Property";
    }
}
