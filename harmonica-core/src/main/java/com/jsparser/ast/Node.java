package com.jsparser.ast;

/**
 * Base interface for all ESTree AST nodes
 */
public sealed interface Node permits
    Program,
    Statement,
    Expression,
    Pattern,
    TemplateElement,
    Property,
    ClassBody,
    MethodDefinition,
    PropertyDefinition,
    ClassAccessorProperty,
    StaticBlock,
    ImportAttribute,
    ImportSpecifier,
    ImportDefaultSpecifier,
    ImportNamespaceSpecifier,
    ExportSpecifier,
    CatchClause,
    SwitchCase,
    Decorator {

    String type();
    int start();
    int end();
    SourceLocation loc();
}
