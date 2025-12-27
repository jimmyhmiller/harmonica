package com.jsparser.ast;

import java.util.List;

public record ExportNamedDeclaration(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Statement declaration,      // Can be null if using specifiers
    List<Node> specifiers,      // List of ExportSpecifier
    Literal source,             // Can be null if not re-exporting
    List<ImportAttribute> attributes  // Import attributes (for re-exports)
) implements Statement {
    public ExportNamedDeclaration(
        int start,
        int end,
        SourceLocation loc,
        Statement declaration,
        List<Node> specifiers,
        Literal source,
        List<ImportAttribute> attributes
    ) {
        this(start,
             end,
             loc != null ? loc.start().line() : 0,
             loc != null ? loc.start().column() : 0,
             loc != null ? loc.end().line() : 0,
             loc != null ? loc.end().column() : 0,
             declaration,
             specifiers,
             source,
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
        return "ExportNamedDeclaration";
    }
}
