package com.jsparser.ast;

import java.util.List;

public record ImportDeclaration(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    List<Node> specifiers,  // ImportSpecifier, ImportDefaultSpecifier, or ImportNamespaceSpecifier
    Literal source,         // String literal for the module path
    List<ImportAttribute> attributes  // Import attributes (with { type: 'json' })
) implements Statement {
    public ImportDeclaration(
        int start,
        int end,
        SourceLocation loc,
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
        return "ImportDeclaration";
    }
}
