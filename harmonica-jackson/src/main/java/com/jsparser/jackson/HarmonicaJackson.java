package com.jsparser.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

/**
 * Factory for creating properly configured ObjectMapper instances for AST serialization.
 *
 * Usage:
 * <pre>
 * ObjectMapper mapper = HarmonicaJackson.createObjectMapper();
 * String json = mapper.writeValueAsString(program);
 * Program program = mapper.readValue(json, Program.class);
 * </pre>
 */
public final class HarmonicaJackson {

    private HarmonicaJackson() {
        // Utility class
    }

    /**
     * Creates a new ObjectMapper configured for AST serialization/deserialization.
     *
     * The returned mapper:
     * - Serializes AST nodes with loc property (instead of startLine/startCol/endLine/endCol)
     * - Handles polymorphic Node types via the "type" property
     * - Properly serializes null values for fields that can be null in the AST
     * - Uses JavaScript-compatible number serialization
     * - Transforms loc fields during deserialization
     *
     * @return A new configured ObjectMapper instance
     */
    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new ParameterNamesModule());

        // Configure serialization - exclude null values by default
        // Specific null fields (like id, alternate) are included via mixins in AstModule
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // Ignore unknown properties during deserialization
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Register the AST module with all the serialization/deserialization logic
        mapper.registerModule(new AstModule());

        return mapper;
    }
}
