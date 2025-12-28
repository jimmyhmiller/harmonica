package com.jsparser.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.jsparser.ast.*;
import com.jsparser.jackson.mixins.NodeMixin;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Jackson module that configures serialization/deserialization for the AST classes.
 *
 * This module handles:
 * - Polymorphic type handling via NodeMixin
 * - Serialization of loc property (instead of startLine/startCol/endLine/endCol)
 * - Proper null value handling for AST fields that can be null
 * - JavaScript-compatible number serialization
 * - Deserialization with loc field transformation
 */
public class AstModule extends SimpleModule {

    // Fields to exclude from serialization (we use loc instead)
    private static final Set<String> EXCLUDED_FIELDS = Set.of("startLine", "startCol", "endLine", "endCol");

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

        // Explicitly add mixin to ImportDeclaration and ImportSpecifier
        // (mixin inheritance from interfaces may not work consistently across Java versions)
        context.setMixInAnnotations(ImportDeclaration.class, NodeMixin.class);
        context.setMixInAnnotations(ImportSpecifier.class, NodeMixin.class);
        context.setMixInAnnotations(ImportDefaultSpecifier.class, NodeMixin.class);
        context.setMixInAnnotations(ImportNamespaceSpecifier.class, NodeMixin.class);
        context.setMixInAnnotations(ExportSpecifier.class, NodeMixin.class);
        context.setMixInAnnotations(ExportAllDeclaration.class, ExportAllDeclarationMixin.class);

        // Add serialization mixins for special cases
        context.setMixInAnnotations(Literal.class, LiteralMixin.class);
        context.setMixInAnnotations(MethodDefinition.class, MethodDefinitionMixin.class);
        context.setMixInAnnotations(PropertyDefinition.class, PropertyDefinitionMixin.class);
        // VariableDeclarator doesn't implement Node, so it needs explicit type property
        context.setMixInAnnotations(VariableDeclarator.class, TypedSerializationMixin.class);
        // Add mixins for classes that need certain null fields to be serialized
        context.setMixInAnnotations(ArrowFunctionExpression.class, FunctionIdMixin.class);
        context.setMixInAnnotations(FunctionExpression.class, FunctionIdMixin.class);
        context.setMixInAnnotations(FunctionDeclaration.class, FunctionIdMixin.class);
        context.setMixInAnnotations(IfStatement.class, IfStatementMixin.class);
        context.setMixInAnnotations(TryStatement.class, TryStatementMixin.class);
        context.setMixInAnnotations(ExportDefaultDeclaration.class, ExportDefaultMixin.class);
        context.setMixInAnnotations(ExportNamedDeclaration.class, ExportNamedMixin.class);
        context.setMixInAnnotations(ForStatement.class, ForStatementMixin.class);
        context.setMixInAnnotations(ConditionalExpression.class, ConditionalMixin.class);
        context.setMixInAnnotations(CatchClause.class, CatchClauseMixin.class);
        context.setMixInAnnotations(ClassDeclaration.class, ClassMixin.class);
        context.setMixInAnnotations(ClassExpression.class, ClassMixin.class);
        context.setMixInAnnotations(ReturnStatement.class, ReturnStatementMixin.class);
        context.setMixInAnnotations(BreakStatement.class, BreakContinueMixin.class);
        context.setMixInAnnotations(ContinueStatement.class, BreakContinueMixin.class);
        context.setMixInAnnotations(SwitchCase.class, SwitchCaseMixin.class);
        context.setMixInAnnotations(ImportExpression.class, ImportExpressionMixin.class);
        context.setMixInAnnotations(YieldExpression.class, YieldExpressionMixin.class);
        context.setMixInAnnotations(ThrowStatement.class, ThrowStatementMixin.class);
        context.setMixInAnnotations(TemplateElement.TemplateElementValue.class, TemplateElementValueMixin.class);

        // Add serializer modifier to filter out startLine/startCol/endLine/endCol and add loc
        context.addBeanSerializerModifier(new AstSerializerModifier());

        // Add custom deserializer modifier to handle the loc -> startLine/startCol transformation
        context.addBeanDeserializerModifier(new AstDeserializerModifier());
    }

    // ==================== Serialization Mixins ====================

    // Base mixin that adds loc property
    private abstract static class SerializationMixin {
        @JsonProperty("loc")
        abstract SourceLocation loc();
    }

    // Mixin for classes that need explicit type property (don't implement Node)
    // Also includes init for VariableDeclarator which can be null
    private abstract static class TypedSerializationMixin extends SerializationMixin {
        @JsonProperty("type")
        abstract String type();

        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression init();
    }

    private abstract static class LiteralMixin extends SerializationMixin {
        @JsonSerialize(using = JavaScriptNumberSerializer.class)
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Object value();
    }

    private abstract static class MethodDefinitionMixin extends SerializationMixin {
        @JsonProperty("static")
        abstract boolean isStatic();
    }

    private abstract static class PropertyDefinitionMixin extends SerializationMixin {
        @JsonProperty("static")
        abstract boolean isStatic();
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression value();
    }

    // Mixin for functions with id field that should be included even when null
    private abstract static class FunctionIdMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Identifier id();
    }

    // Mixin for IfStatement - alternate should be included even when null
    private abstract static class IfStatementMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Statement alternate();
    }

    // Mixin for TryStatement - handler and finalizer should be included even when null
    private abstract static class TryStatementMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract CatchClause handler();
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract BlockStatement finalizer();
    }

    // Mixin for ExportDefaultDeclaration - declaration should be included even when null
    private abstract static class ExportDefaultMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Node declaration();
    }

    // Mixin for ExportNamedDeclaration - declaration and source should be included even when null
    private abstract static class ExportNamedMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Node declaration();
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Literal source();
    }

    // Mixin for ForStatement - init, test, update should be included even when null
    private abstract static class ForStatementMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Node init();
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression test();
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression update();
    }

    // Mixin for ConditionalExpression - all parts required
    private abstract static class ConditionalMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression test();
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression consequent();
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression alternate();
    }

    // Mixin for CatchClause - param can be null in ES2019+
    private abstract static class CatchClauseMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Pattern param();
    }

    // Mixin for class declarations/expressions - id and superClass can be null
    private abstract static class ClassMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Identifier id();
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression superClass();
    }

    // Mixin for ReturnStatement - argument can be null
    private abstract static class ReturnStatementMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression argument();
    }

    // Mixin for break/continue statements - label can be null
    private abstract static class BreakContinueMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Identifier label();
    }

    // Mixin for SwitchCase - test is null for default case
    private abstract static class SwitchCaseMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression test();
    }

    // Mixin for ImportExpression - options can be null
    private abstract static class ImportExpressionMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression options();
    }

    // Mixin for YieldExpression - argument can be null
    private abstract static class YieldExpressionMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression argument();
    }

    // Mixin for ThrowStatement - argument should always be present
    private abstract static class ThrowStatementMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Expression argument();
    }

    // Mixin for ExportAllDeclaration - exported can be null for "export * from 'mod'"
    private abstract static class ExportAllDeclarationMixin extends SerializationMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract Node exported();
    }

    // Mixin for TemplateElementValue - cooked can be null for invalid escape sequences
    private abstract static class TemplateElementValueMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract String cooked();
    }

    // ==================== Serializer Modifier ====================

    private static class AstSerializerModifier extends BeanSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                          BeanDescription beanDesc,
                                                          List<BeanPropertyWriter> beanProperties) {
            // Check if this is an AST class - either implements Node or has loc() method
            Class<?> beanClass = beanDesc.getBeanClass();
            if (!isAstClass(beanClass)) {
                return beanProperties;
            }

            // Filter out the excluded fields (startLine, startCol, endLine, endCol)
            List<BeanPropertyWriter> filtered = new java.util.ArrayList<>();
            boolean hasLoc = false;
            for (BeanPropertyWriter prop : beanProperties) {
                if (EXCLUDED_FIELDS.contains(prop.getName())) {
                    continue;
                }
                if ("loc".equals(prop.getName())) {
                    hasLoc = true;
                }
                filtered.add(prop);
            }

            // If loc is not present in properties (mixin didn't work), add it manually
            if (!hasLoc) {
                try {
                    java.lang.reflect.Method locMethod = beanClass.getMethod("loc");
                    if (locMethod.getReturnType() == SourceLocation.class) {
                        // Create a virtual property for loc using reflection
                        BeanPropertyWriter locWriter = new LocBeanPropertyWriter(locMethod);
                        filtered.add(locWriter);
                    }
                } catch (NoSuchMethodException e) {
                    // No loc method
                }
            }

            return filtered;
        }

        private boolean isAstClass(Class<?> clazz) {
            // Check if it implements Node
            if (Node.class.isAssignableFrom(clazz)) {
                return true;
            }
            // Check if it has a loc() method returning SourceLocation (for VariableDeclarator, etc.)
            try {
                java.lang.reflect.Method locMethod = clazz.getMethod("loc");
                return locMethod.getReturnType() == SourceLocation.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    }

    /**
     * A property writer that adds 'loc' to the JSON output by calling the loc() method.
     */
    private static class LocBeanPropertyWriter extends BeanPropertyWriter {
        private final java.lang.reflect.Method locMethod;

        LocBeanPropertyWriter(java.lang.reflect.Method locMethod) {
            super();
            this.locMethod = locMethod;
        }

        @Override
        public String getName() {
            return "loc";
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            Object value = locMethod.invoke(bean);
            if (value != null) {
                gen.writeFieldName("loc");
                prov.defaultSerializeValue(value, gen);
            }
        }
    }

    // ==================== Deserializer Modifier ====================

    private static class AstDeserializerModifier extends BeanDeserializerModifier {
        @Override
        public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                                                       BeanDescription beanDesc,
                                                       JsonDeserializer<?> deserializer) {
            Class<?> beanClass = beanDesc.getBeanClass();
            if (Node.class.isAssignableFrom(beanClass) && beanClass.isRecord()) {
                return new AstNodeDeserializer(deserializer, beanClass);
            }
            return deserializer;
        }
    }

    private static class AstNodeDeserializer extends JsonDeserializer<Object> implements ResolvableDeserializer {
        // Track depth of recursive calls per thread to avoid infinite recursion
        private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

        private final JsonDeserializer<?> delegate;
        private final Class<?> beanClass;

        AstNodeDeserializer(JsonDeserializer<?> delegate, Class<?> beanClass) {
            this.delegate = delegate;
            this.beanClass = beanClass;
        }

        @Override
        public void resolve(DeserializationContext ctxt) throws JsonMappingException {
            // If delegate is resolvable, resolve it
            if (delegate instanceof ResolvableDeserializer) {
                ((ResolvableDeserializer) delegate).resolve(ctxt);
            }
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            int depth = DEPTH.get();

            // If we're in a recursive call, the tree was already transformed by the parent
            // Just use normal deserialization which will handle polymorphic types
            if (depth > 0) {
                // Create a new parser from the already-transformed tree and use it
                JsonNode node = p.readValueAsTree();
                JsonParser jp = node.traverse(p.getCodec());
                jp.nextToken();
                return delegate.deserialize(jp, ctxt);
            }

            JsonNode node = p.readValueAsTree();

            // Recursively transform all nodes in the tree BEFORE deserialization
            // Pass true for isRootOrHasType since we know this is an AST node (we're in the AST deserializer)
            transformNode(node, true);

            // Increment depth to prevent our deserializer from re-transforming
            DEPTH.set(depth + 1);
            try {
                // Create parser from transformed tree and use delegate to deserialize
                JsonParser jp = node.traverse(p.getCodec());
                jp.nextToken();
                Object result = delegate.deserialize(jp, ctxt);
                return result;
            } finally {
                DEPTH.set(depth);
            }
        }

        /**
         * Transform a JSON node for deserialization.
         * @param node The node to transform
         * @param isAstNode Whether this node is known to be an AST node (true for root or nodes with "type" field)
         */
        private void transformNode(JsonNode node, boolean isAstNode) {
            if (node == null || !node.isObject()) {
                return;
            }

            com.fasterxml.jackson.databind.node.ObjectNode objNode = (com.fasterxml.jackson.databind.node.ObjectNode) node;

            // If this is not known to be an AST node, check if it has a type field
            // Objects without type field are not AST nodes (e.g., regex value {})
            if (!isAstNode && !objNode.has("type")) {
                // Still need to recurse into child nodes, but they need type field too
                objNode.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    if (value.isObject()) {
                        transformNode(value, false);
                    } else if (value.isArray()) {
                        value.forEach(child -> transformNode(child, false));
                    }
                });
                return;
            }

            // Transform loc field if present
            if (objNode.has("loc") && objNode.get("loc").isObject()) {
                JsonNode loc = objNode.get("loc");
                objNode.put("startLine",
                    loc.has("start") && loc.get("start").has("line") ? loc.get("start").get("line").asInt() : 0);
                objNode.put("startCol",
                    loc.has("start") && loc.get("start").has("column") ? loc.get("start").get("column").asInt() : 0);
                objNode.put("endLine",
                    loc.has("end") && loc.get("end").has("line") ? loc.get("end").get("line").asInt() : 0);
                objNode.put("endCol",
                    loc.has("end") && loc.get("end").has("column") ? loc.get("end").get("column").asInt() : 0);
                objNode.remove("loc");
            } else {
                if (!objNode.has("startLine")) objNode.put("startLine", 0);
                if (!objNode.has("startCol")) objNode.put("startCol", 0);
                if (!objNode.has("endLine")) objNode.put("endLine", 0);
                if (!objNode.has("endCol")) objNode.put("endCol", 0);
            }

            // Add default values for start and end if missing
            if (!objNode.has("start")) objNode.put("start", 0);
            if (!objNode.has("end")) objNode.put("end", 0);

            // Recursively transform all child nodes
            // Child nodes that have a "type" field are AST nodes
            objNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isObject()) {
                    // Child objects are AST nodes if they have a "type" field
                    transformNode(value, value.has("type"));
                } else if (value.isArray()) {
                    value.forEach(child -> {
                        if (child.isObject()) {
                            transformNode(child, child.has("type"));
                        }
                    });
                }
            });
        }
    }
}
