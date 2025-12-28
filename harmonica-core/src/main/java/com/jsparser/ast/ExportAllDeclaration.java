package com.jsparser.ast;

public record ExportAllDeclaration(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Literal source,      // String literal for the module
    Node exported,       // Can be null for "export * from 'mod'", or Identifier/Literal for "export * as ns from 'mod'"
    java.util.List<ImportAttribute> attributes  // Import attributes
) implements Statement {
    public ExportAllDeclaration(
        int start,
        int end,
        SourceLocation loc,
        Literal source,
        Node exported,
        java.util.List<ImportAttribute> attributes
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             source,
             exported,
             attributes);
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
        return "ExportAllDeclaration";
    }
}
