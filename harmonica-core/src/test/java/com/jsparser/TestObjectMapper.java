package com.jsparser;

import com.fasterxml.jackson.annotation.*;
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
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.jsparser.ast.*;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * Test utility that provides a properly configured ObjectMapper for AST serialization/deserialization.
 * This duplicates the configuration from harmonica-jackson to avoid cyclic dependencies in tests.
 */
public class TestObjectMapper {

    private static ObjectMapper instance;

    // Fields to exclude from serialization (we use loc instead)
    private static final Set<String> EXCLUDED_FIELDS = Set.of("startLine", "startCol", "endLine", "endCol");

    public static synchronized ObjectMapper get() {
        if (instance == null) {
            instance = createObjectMapper();
        }
        return instance;
    }

    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new ParameterNamesModule());

        // Configure serialization - exclude null values by default
        // Specific null fields (like id, alternate) are included via mixins
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // Ignore unknown properties during deserialization
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Add polymorphic deserialization mixin BEFORE registering the module
        mapper.addMixIn(Node.class, NodeMixin.class);
        mapper.addMixIn(Statement.class, NodeMixin.class);
        mapper.addMixIn(Expression.class, NodeMixin.class);
        mapper.addMixIn(Pattern.class, NodeMixin.class);

        // Explicitly add mixin to ImportDeclaration and ImportSpecifier
        // (mixin inheritance from interfaces may not work consistently across Java versions)
        mapper.addMixIn(ImportDeclaration.class, NodeMixin.class);
        mapper.addMixIn(ImportSpecifier.class, NodeMixin.class);
        mapper.addMixIn(ImportDefaultSpecifier.class, NodeMixin.class);
        mapper.addMixIn(ImportNamespaceSpecifier.class, NodeMixin.class);
        mapper.addMixIn(ExportSpecifier.class, NodeMixin.class);
        mapper.addMixIn(ExportAllDeclaration.class, NodeMixin.class);

        // Add serialization mixins for special cases
        mapper.addMixIn(Literal.class, LiteralMixin.class);
        mapper.addMixIn(MethodDefinition.class, MethodDefinitionMixin.class);
        mapper.addMixIn(PropertyDefinition.class, PropertyDefinitionMixin.class);
        // VariableDeclarator doesn't implement Node, so it needs explicit type property
        mapper.addMixIn(VariableDeclarator.class, TypedSerializationMixin.class);
        // Add mixins for classes that need certain null fields to be serialized
        mapper.addMixIn(ArrowFunctionExpression.class, FunctionIdMixin.class);
        mapper.addMixIn(FunctionExpression.class, FunctionIdMixin.class);
        mapper.addMixIn(FunctionDeclaration.class, FunctionIdMixin.class);
        mapper.addMixIn(IfStatement.class, IfStatementMixin.class);
        mapper.addMixIn(TryStatement.class, TryStatementMixin.class);
        mapper.addMixIn(ExportDefaultDeclaration.class, ExportDefaultMixin.class);
        mapper.addMixIn(ExportNamedDeclaration.class, ExportNamedMixin.class);
        mapper.addMixIn(ForStatement.class, ForStatementMixin.class);
        mapper.addMixIn(ConditionalExpression.class, ConditionalMixin.class);
        mapper.addMixIn(CatchClause.class, CatchClauseMixin.class);
        mapper.addMixIn(ClassDeclaration.class, ClassMixin.class);
        mapper.addMixIn(ClassExpression.class, ClassMixin.class);
        mapper.addMixIn(ReturnStatement.class, ReturnStatementMixin.class);
        mapper.addMixIn(BreakStatement.class, BreakContinueMixin.class);
        mapper.addMixIn(ContinueStatement.class, BreakContinueMixin.class);
        mapper.addMixIn(SwitchCase.class, SwitchCaseMixin.class);
        mapper.addMixIn(ImportExpression.class, ImportExpressionMixin.class);
        mapper.addMixIn(YieldExpression.class, YieldExpressionMixin.class);
        mapper.addMixIn(ThrowStatement.class, ThrowStatementMixin.class);

        // Register module with serializer and deserializer modifiers
        mapper.registerModule(new TestAstModule());

        return mapper;
    }

    // ==================== Serialization Mixins ====================

    // Base mixin that adds loc property
    // Note: @JsonIgnoreProperties doesn't work for records so we use BeanSerializerModifier instead
    // The "type" property is added via @JsonTypeInfo for Node-implementing classes, and via
    // a custom serializer for non-Node classes like VariableDeclarator
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

    // ==================== AST Module ====================

    private static class TestAstModule extends SimpleModule {
        TestAstModule() {
            super("TestAstModule", new Version(1, 0, 0, null, "com.jsparser", "test"));
        }

        @Override
        public void setupModule(SetupContext context) {
            super.setupModule(context);

            // Add serializer modifier to filter out startLine/startCol/endLine/endCol
            context.addBeanSerializerModifier(new AstSerializerModifier());

            // Enable deserializer modifier for loc transformation
            context.addBeanDeserializerModifier(new AstDeserializerModifier());
        }
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
                        BeanPropertyWriter locWriter = createLocPropertyWriter(config, beanDesc, locMethod);
                        if (locWriter != null) {
                            filtered.add(locWriter);
                            System.err.println("DEBUG: Added loc property writer for " + beanClass.getSimpleName());
                        }
                    }
                } catch (NoSuchMethodException e) {
                    // No loc method
                }
            } else {
                System.err.println("DEBUG: loc already present for " + beanClass.getSimpleName());
            }

            return filtered;
        }

        private BeanPropertyWriter createLocPropertyWriter(SerializationConfig config,
                                                            BeanDescription beanDesc,
                                                            java.lang.reflect.Method locMethod) {
            return new LocBeanPropertyWriter(locMethod, config);
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

        LocBeanPropertyWriter(java.lang.reflect.Method locMethod, SerializationConfig config) {
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

    // ==================== Node Mixin ====================

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Program.class, name = "Program"),
        @JsonSubTypes.Type(value = Identifier.class, name = "Identifier"),
        @JsonSubTypes.Type(value = Literal.class, name = "Literal"),
        @JsonSubTypes.Type(value = ExpressionStatement.class, name = "ExpressionStatement"),
        @JsonSubTypes.Type(value = BinaryExpression.class, name = "BinaryExpression"),
        @JsonSubTypes.Type(value = AssignmentExpression.class, name = "AssignmentExpression"),
        @JsonSubTypes.Type(value = MemberExpression.class, name = "MemberExpression"),
        @JsonSubTypes.Type(value = CallExpression.class, name = "CallExpression"),
        @JsonSubTypes.Type(value = ArrayExpression.class, name = "ArrayExpression"),
        @JsonSubTypes.Type(value = ObjectExpression.class, name = "ObjectExpression"),
        @JsonSubTypes.Type(value = NewExpression.class, name = "NewExpression"),
        @JsonSubTypes.Type(value = VariableDeclaration.class, name = "VariableDeclaration"),
        @JsonSubTypes.Type(value = BlockStatement.class, name = "BlockStatement"),
        @JsonSubTypes.Type(value = ReturnStatement.class, name = "ReturnStatement"),
        @JsonSubTypes.Type(value = UnaryExpression.class, name = "UnaryExpression"),
        @JsonSubTypes.Type(value = LogicalExpression.class, name = "LogicalExpression"),
        @JsonSubTypes.Type(value = UpdateExpression.class, name = "UpdateExpression"),
        @JsonSubTypes.Type(value = ConditionalExpression.class, name = "ConditionalExpression"),
        @JsonSubTypes.Type(value = IfStatement.class, name = "IfStatement"),
        @JsonSubTypes.Type(value = WhileStatement.class, name = "WhileStatement"),
        @JsonSubTypes.Type(value = DoWhileStatement.class, name = "DoWhileStatement"),
        @JsonSubTypes.Type(value = ForStatement.class, name = "ForStatement"),
        @JsonSubTypes.Type(value = ForInStatement.class, name = "ForInStatement"),
        @JsonSubTypes.Type(value = ForOfStatement.class, name = "ForOfStatement"),
        @JsonSubTypes.Type(value = BreakStatement.class, name = "BreakStatement"),
        @JsonSubTypes.Type(value = ContinueStatement.class, name = "ContinueStatement"),
        @JsonSubTypes.Type(value = FunctionDeclaration.class, name = "FunctionDeclaration"),
        @JsonSubTypes.Type(value = FunctionExpression.class, name = "FunctionExpression"),
        @JsonSubTypes.Type(value = ArrowFunctionExpression.class, name = "ArrowFunctionExpression"),
        @JsonSubTypes.Type(value = TemplateLiteral.class, name = "TemplateLiteral"),
        @JsonSubTypes.Type(value = TemplateElement.class, name = "TemplateElement"),
        @JsonSubTypes.Type(value = ThisExpression.class, name = "ThisExpression"),
        @JsonSubTypes.Type(value = ObjectPattern.class, name = "ObjectPattern"),
        @JsonSubTypes.Type(value = ArrayPattern.class, name = "ArrayPattern"),
        @JsonSubTypes.Type(value = ClassBody.class, name = "ClassBody"),
        @JsonSubTypes.Type(value = MethodDefinition.class, name = "MethodDefinition"),
        @JsonSubTypes.Type(value = PropertyDefinition.class, name = "PropertyDefinition"),
        @JsonSubTypes.Type(value = ClassDeclaration.class, name = "ClassDeclaration"),
        @JsonSubTypes.Type(value = ClassExpression.class, name = "ClassExpression"),
        @JsonSubTypes.Type(value = Super.class, name = "Super"),
        @JsonSubTypes.Type(value = MetaProperty.class, name = "MetaProperty"),
        @JsonSubTypes.Type(value = ImportDeclaration.class, name = "ImportDeclaration"),
        @JsonSubTypes.Type(value = ImportSpecifier.class, name = "ImportSpecifier"),
        @JsonSubTypes.Type(value = ImportDefaultSpecifier.class, name = "ImportDefaultSpecifier"),
        @JsonSubTypes.Type(value = ImportNamespaceSpecifier.class, name = "ImportNamespaceSpecifier"),
        @JsonSubTypes.Type(value = ExportNamedDeclaration.class, name = "ExportNamedDeclaration"),
        @JsonSubTypes.Type(value = ExportDefaultDeclaration.class, name = "ExportDefaultDeclaration"),
        @JsonSubTypes.Type(value = ExportAllDeclaration.class, name = "ExportAllDeclaration"),
        @JsonSubTypes.Type(value = ExportSpecifier.class, name = "ExportSpecifier"),
        @JsonSubTypes.Type(value = ImportExpression.class, name = "ImportExpression"),
        @JsonSubTypes.Type(value = ThrowStatement.class, name = "ThrowStatement"),
        @JsonSubTypes.Type(value = TryStatement.class, name = "TryStatement"),
        @JsonSubTypes.Type(value = CatchClause.class, name = "CatchClause"),
        @JsonSubTypes.Type(value = WithStatement.class, name = "WithStatement"),
        @JsonSubTypes.Type(value = DebuggerStatement.class, name = "DebuggerStatement"),
        @JsonSubTypes.Type(value = EmptyStatement.class, name = "EmptyStatement"),
        @JsonSubTypes.Type(value = LabeledStatement.class, name = "LabeledStatement"),
        @JsonSubTypes.Type(value = SwitchStatement.class, name = "SwitchStatement"),
        @JsonSubTypes.Type(value = SwitchCase.class, name = "SwitchCase"),
        @JsonSubTypes.Type(value = Property.class, name = "Property"),
        @JsonSubTypes.Type(value = SpreadElement.class, name = "SpreadElement"),
        @JsonSubTypes.Type(value = SequenceExpression.class, name = "SequenceExpression"),
        @JsonSubTypes.Type(value = RestElement.class, name = "RestElement"),
        @JsonSubTypes.Type(value = AssignmentPattern.class, name = "AssignmentPattern"),
        @JsonSubTypes.Type(value = AwaitExpression.class, name = "AwaitExpression"),
        @JsonSubTypes.Type(value = YieldExpression.class, name = "YieldExpression"),
        @JsonSubTypes.Type(value = TaggedTemplateExpression.class, name = "TaggedTemplateExpression"),
        @JsonSubTypes.Type(value = ChainExpression.class, name = "ChainExpression"),
        @JsonSubTypes.Type(value = PrivateIdentifier.class, name = "PrivateIdentifier"),
        @JsonSubTypes.Type(value = StaticBlock.class, name = "StaticBlock"),
        @JsonSubTypes.Type(value = ImportAttribute.class, name = "ImportAttribute"),
        @JsonSubTypes.Type(value = VariableDeclarator.class, name = "VariableDeclarator"),
    })
    private abstract static class NodeMixin extends SerializationMixin {}

    // ==================== Number Serializer ====================

    public static class JavaScriptNumberSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
            } else if (value instanceof Double d) {
                if (d.isInfinite()) {
                    gen.writeNull();
                } else if (d.isNaN()) {
                    gen.writeNull();
                } else if (d == Math.floor(d)) {
                    // For integer-valued doubles, follow JavaScript's JSON.stringify behavior:
                    // - Numbers < 1e21 use plain notation
                    // - Numbers >= 1e21 use scientific notation (with lowercase 'e')
                    //
                    // Long.MAX_VALUE = 9223372036854775807 (~9.22e18) fits in plain notation
                    // Numbers like 1e21 and above use scientific notation
                    //
                    // Important: For numbers larger than MAX_SAFE_INTEGER (2^53), the conversion
                    // from double to long may produce a different decimal representation than
                    // JavaScript's JSON.stringify. For example:
                    // - (long) 9.007199254740991E18 = 9007199254740990976
                    // - JavaScript outputs: 9007199254740991000
                    // So we use BigDecimal for large numbers to match JavaScript behavior.

                    double absD = Math.abs(d);
                    double maxSafeInteger = 9007199254740991.0; // 2^53 - 1

                    if (absD >= 1e21) {
                        // JavaScript uses scientific notation for numbers >= 1e21
                        // Write using raw value to get lowercase 'e' like JavaScript
                        // Also need to add '+' for positive exponents (e22 -> e+22)
                        String jsNotation = Double.toString(d).toLowerCase();
                        // Java uses "e22" but JS uses "e+22" for positive exponents
                        jsNotation = jsNotation.replaceFirst("e(\\d)", "e+$1");
                        gen.writeRawValue(jsNotation);
                    } else if (absD <= maxSafeInteger) {
                        // Within safe integer range - long conversion is exact
                        gen.writeNumber(d.longValue());
                    } else {
                        // Between MAX_SAFE_INTEGER and 1e21 - use BigDecimal for correct JS representation
                        java.math.BigDecimal bd = new java.math.BigDecimal(
                            java.math.BigDecimal.valueOf(d).toPlainString());
                        gen.writeNumber(bd);
                    }
                } else {
                    gen.writeNumber(d);
                }
            } else if (value instanceof Float f) {
                if (f.isInfinite() || f.isNaN()) {
                    gen.writeNull();
                } else if (f == Math.floor(f) && f >= Long.MIN_VALUE && f <= Long.MAX_VALUE) {
                    gen.writeNumber(f.longValue());
                } else {
                    gen.writeNumber(f);
                }
            } else if (value instanceof Long l) {
                gen.writeNumber(l);
            } else if (value instanceof Integer i) {
                gen.writeNumber(i);
            } else if (value instanceof BigInteger bi) {
                gen.writeNumber(bi);
            } else if (value instanceof Number n) {
                gen.writeNumber(n.doubleValue());
            } else if (value instanceof Boolean b) {
                gen.writeBoolean(b);
            } else if (value instanceof String s) {
                gen.writeString(s);
            } else {
                gen.writeObject(value);
            }
        }
    }
}
