package com.jsparser.jackson;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.jsparser.ast.*;
import com.jsparser.json.*;

/**
 * Jackson-based implementation of AstJsonProvider.
 */
public class JacksonAstJsonProvider implements AstJsonProvider {

    private final ObjectMapper mapper;
    private final AstJsonSerializer serializer;
    private final AstJsonDeserializer deserializer;

    public JacksonAstJsonProvider() {
        this.mapper = createObjectMapper();
        this.serializer = new JacksonSerializer(mapper);
        this.deserializer = new JacksonDeserializer(mapper);
    }

    /**
     * Creates a configured ObjectMapper for AST serialization/deserialization.
     */
    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new ParameterNamesModule());
        mapper.registerModule(new AstModule());

        // Configure serialization
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // Add serialization mixins
        mapper.addMixIn(Node.class, SerializationMixin.class);
        mapper.addMixIn(Literal.class, LiteralMixin.class);
        mapper.addMixIn(MethodDefinition.class, MethodDefinitionMixin.class);
        mapper.addMixIn(PropertyDefinition.class, PropertyDefinitionMixin.class);

        return mapper;
    }

    @Override
    public AstJsonSerializer getSerializer() {
        return serializer;
    }

    @Override
    public AstJsonDeserializer getDeserializer() {
        return deserializer;
    }

    @Override
    public String getName() {
        return "Jackson";
    }

    /**
     * Returns the underlying ObjectMapper for advanced usage.
     */
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    // ==================== Serialization Mixins ====================

    @JsonIgnoreProperties({"startLine", "startCol", "endLine", "endCol"})
    private interface SerializationMixin {
        @JsonProperty("loc")
        SourceLocation loc();
    }

    private interface LiteralMixin extends SerializationMixin {
        @JsonSerialize(using = JavaScriptNumberSerializer.class)
        Object value();
    }

    private interface MethodDefinitionMixin extends SerializationMixin {
        @JsonProperty("static")
        boolean isStatic();
    }

    private interface PropertyDefinitionMixin extends SerializationMixin {
        @JsonProperty("static")
        boolean isStatic();
    }

    // ==================== Inner Classes ====================

    private static class JacksonSerializer implements AstJsonSerializer {
        private final ObjectMapper mapper;

        JacksonSerializer(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String serialize(Node node) throws AstJsonException {
            try {
                return mapper.writeValueAsString(node);
            } catch (Exception e) {
                throw new AstJsonException("Failed to serialize AST node", e);
            }
        }

        @Override
        public String serializePretty(Node node) throws AstJsonException {
            try {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            } catch (Exception e) {
                throw new AstJsonException("Failed to serialize AST node", e);
            }
        }
    }

    private static class JacksonDeserializer implements AstJsonDeserializer {
        private final ObjectMapper mapper;

        JacksonDeserializer(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Program deserializeProgram(String json) throws AstJsonException {
            try {
                return mapper.readValue(json, Program.class);
            } catch (Exception e) {
                throw new AstJsonException("Failed to deserialize Program", e);
            }
        }

        @Override
        public <T extends Node> T deserialize(String json, Class<T> type) throws AstJsonException {
            try {
                return mapper.readValue(json, type);
            } catch (Exception e) {
                throw new AstJsonException("Failed to deserialize " + type.getSimpleName(), e);
            }
        }
    }
}
