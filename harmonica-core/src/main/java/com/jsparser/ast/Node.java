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
    StaticBlock,
    ImportAttribute,
    ImportSpecifier,
    ImportDefaultSpecifier,
    ImportNamespaceSpecifier,
    ExportSpecifier,
    CatchClause,
    SwitchCase {

    String type();
    int start();
    int end();
    SourceLocation loc();
}
