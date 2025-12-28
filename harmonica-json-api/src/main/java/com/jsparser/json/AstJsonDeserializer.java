package com.jsparser.json;

import com.jsparser.ast.Node;
import com.jsparser.ast.Program;

/**
 * Interface for deserializing AST nodes from JSON.
 */
public interface AstJsonDeserializer {

    /**
     * Deserializes a JSON string to a Program (root AST node).
     *
     * @param json the JSON string to deserialize
     * @return the deserialized Program
     * @throws AstJsonException if deserialization fails
     */
    Program deserializeProgram(String json) throws AstJsonException;

    /**
     * Deserializes a JSON string to a specific AST node type.
     *
     * @param json the JSON string to deserialize
     * @param type the expected node type
     * @param <T> the node type
     * @return the deserialized node
     * @throws AstJsonException if deserialization fails
     */
    <T extends Node> T deserialize(String json, Class<T> type) throws AstJsonException;
}
