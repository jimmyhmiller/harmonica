package com.jsparser.ast;

/**
 * A decorator node, part of the decorators proposal.
 *
 * Example:
 * @decorator
 * @decorator.member
 * @decorator()
 * class C {}
 */
public record Decorator(
    int start,
    int end,
    int startLine,
    int startCol,
    int endLine,
    int endCol,
    Expression expression
) implements Node {
    @Override
    public String type() {
        return "Decorator";
    }

    @Override
    public SourceLocation loc() {
        return new SourceLocation(
            new SourceLocation.Position(startLine, startCol),
            new SourceLocation.Position(endLine, endCol)
        );
    }
}
