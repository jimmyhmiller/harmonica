package com.jsparser.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.jsparser.ast.*;
import com.jsparser.jackson.mixins.NodeMixin;

/**
 * Jackson module that configures serialization/deserialization for the AST classes.
 */
public class AstModule extends SimpleModule {

    public AstModule() {
        super("AstModule", new Version(1, 0, 0, null, "com.jsparser", "harmonica-jackson"));
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);

        // Register polymorphic type handling
        context.setMixInAnnotations(Node.class, NodeMixin.class);
        context.setMixInAnnotations(Statement.class, NodeMixin.class);
        context.setMixInAnnotations(Expression.class, NodeMixin.class);
        context.setMixInAnnotations(Pattern.class, NodeMixin.class);

        // Add custom deserializer modifier to handle the loc -> startLine/startCol transformation
        context.addBeanDeserializerModifier(new AstDeserializerModifier());
    }

    /**
     * Modifier that wraps AST record deserializers to handle the loc field transformation.
     */
    private static class AstDeserializerModifier extends BeanDeserializerModifier {
        @Override
        public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                                                       BeanDescription beanDesc,
                                                       JsonDeserializer<?> deserializer) {
            Class<?> beanClass = beanDesc.getBeanClass();

            // Check if this is an AST class that needs loc transformation
            if (Node.class.isAssignableFrom(beanClass) && beanClass.isRecord()) {
                return new AstNodeDeserializer(deserializer, beanClass);
            }

            return deserializer;
        }
    }

    /**
     * Custom deserializer that handles the loc field transformation for AST records.
     * Transforms { loc: { start: { line, column }, end: { line, column } } }
     * into flat startLine, startCol, endLine, endCol fields.
     */
    private static class AstNodeDeserializer extends JsonDeserializer<Object> {
        private final JsonDeserializer<?> delegate;
        private final Class<?> beanClass;

        AstNodeDeserializer(JsonDeserializer<?> delegate, Class<?> beanClass) {
            this.delegate = delegate;
            this.beanClass = beanClass;
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws java.io.IOException {
            // Read as tree to manipulate
            JsonNode node = p.readValueAsTree();

            // Transform loc field if present
            if (node.has("loc") && node.get("loc").isObject()) {
                JsonNode loc = node.get("loc");
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("startLine",
                    loc.has("start") && loc.get("start").has("line") ? loc.get("start").get("line").asInt() : 0);
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("startCol",
                    loc.has("start") && loc.get("start").has("column") ? loc.get("start").get("column").asInt() : 0);
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("endLine",
                    loc.has("end") && loc.get("end").has("line") ? loc.get("end").get("line").asInt() : 0);
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("endCol",
                    loc.has("end") && loc.get("end").has("column") ? loc.get("end").get("column").asInt() : 0);
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("loc");
            } else {
                // Ensure fields exist with defaults
                if (!node.has("startLine")) ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("startLine", 0);
                if (!node.has("startCol")) ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("startCol", 0);
                if (!node.has("endLine")) ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("endLine", 0);
                if (!node.has("endCol")) ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("endCol", 0);
            }

            // Now deserialize with the transformed node
            JsonParser transformedParser = node.traverse(p.getCodec());
            transformedParser.nextToken();
            return delegate.deserialize(transformedParser, ctxt);
        }
    }
}
