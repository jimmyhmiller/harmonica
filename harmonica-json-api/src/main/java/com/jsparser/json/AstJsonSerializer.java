package com.jsparser.json;

import com.jsparser.ast.Node;

/**
 * Interface for serializing AST nodes to JSON.
 */
public interface AstJsonSerializer {

    /**
     * Serializes an AST node to a JSON string.
     *
     * @param node the AST node to serialize
     * @return the JSON representation of the node
     * @throws AstJsonException if serialization fails
     */
    String serialize(Node node) throws AstJsonException;

    /**
     * Serializes an AST node to a pretty-printed JSON string.
     *
     * @param node the AST node to serialize
     * @return the pretty-printed JSON representation of the node
     * @throws AstJsonException if serialization fails
     */
    String serializePretty(Node node) throws AstJsonException;
}
