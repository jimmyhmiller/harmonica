package com.jsparser.ast;

import java.util.List;

/**
 * Represents an auto-accessor class field: accessor foo = 1;
 * This creates a getter/setter pair with private backing storage.
 */
public record ClassAccessorProperty(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression key,        // Property name (Identifier or PrivateIdentifier)
    Expression value,      // Can be null for class fields without initializer
    boolean computed,
    boolean isStatic,
    List<Decorator> decorators // Can be empty
) implements Node {

    public ClassAccessorProperty(
        int start,
        int end,
        int startLine,
        int startCol,
        int endLine,
        int endCol,
        Expression key,
        Expression value,
        boolean computed,
        boolean isStatic
    ) {
        this(start, end, startLine, startCol, endLine, endCol, key, value, computed, isStatic, List.of());
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
        return "ClassAccessorProperty";
    }
}
