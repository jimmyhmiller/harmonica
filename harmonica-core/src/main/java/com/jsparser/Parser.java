package com.jsparser;

import com.jsparser.ast.*;

import java.util.ArrayList;
import java.util.List;

public class Parser {
    // ========================================================================
    // Binding Power Constants for Pratt Parser
    // ========================================================================
    // Higher binding power = tighter binding (higher precedence)
    // These values correspond to JavaScript operator precedence levels
    private static final int BP_NONE = 0;           // Lowest - used as minimum for top-level
    private static final int BP_COMMA = 1;          // Comma/Sequence operator
    private static final int BP_ASSIGNMENT = 2;     // Assignment (=, +=, etc.) - right-associative
    private static final int BP_TERNARY = 3;        // Conditional (? :)
    private static final int BP_NULLISH = 4;        // Nullish coalescing (??)
    private static final int BP_OR = 5;             // Logical OR (||)
    private static final int BP_AND = 6;            // Logical AND (&&)
    private static final int BP_BIT_OR = 7;         // Bitwise OR (|)
    private static final int BP_BIT_XOR = 8;        // Bitwise XOR (^)
    private static final int BP_BIT_AND = 9;        // Bitwise AND (&)
    private static final int BP_EQUALITY = 10;      // Equality (==, !=, ===, !==)
    private static final int BP_RELATIONAL = 11;    // Relational (<, <=, >, >=, instanceof, in)
    private static final int BP_SHIFT = 12;         // Shift (<<, >>, >>>)
    private static final int BP_ADDITIVE = 13;      // Additive (+, -)
    private static final int BP_MULTIPLICATIVE = 14;// Multiplicative (*, /, %)
    private static final int BP_EXPONENT = 15;      // Exponentiation (**) - right-associative
    private static final int BP_UNARY = 16;         // Prefix unary (!, -, +, ~, typeof, void, delete, ++, --)
    private static final int BP_POSTFIX = 17;       // Postfix (x++, x--, call, member access, optional chaining)

    private final List<Token> tokens;
    private final int sourceLength;
    private final Lexer lexer;
    private final String source;
    private final char[] sourceBuf;
    private final int[] lineOffsets; // Starting byte offset of each line
    private int current = 0;
    private boolean allowIn = true;
    private boolean inGenerator = false;
    private boolean inAsyncContext = false;
    private boolean inClassFieldInitializer = false;
    private final boolean forceModuleMode;
    private boolean atModuleTopLevel = false;  // true at module top level (for top-level await)

    // Context flags for super, new.target, and return validation
    private boolean inFunction = false;           // true inside function/method body - allows return
    private boolean allowNewTarget = false;       // true inside regular functions (not arrow) - allows new.target
    private boolean allowSuperProperty = false;   // true inside methods - allows super.property
    private boolean allowSuperCall = false;       // true inside constructors - allows super()
    private boolean inDerivedClass = false;       // true inside class that extends another class
    private boolean inStaticBlock = false;        // true inside class static initializer block
    private boolean inFormalParameters = false;   // true while parsing function parameter defaults

    // Tracking for illegal ?? / && / || mixing (must not be mixed at the same expression level)
    private boolean inCoalesceChain = false;      // true if we've bound ?? in the current expression chain
    private boolean inLogicalChain = false;       // true if we've bound && or || in the current expression chain

    // Private name environment tracking for AllPrivateNamesValid validation
    // Stack of sets - one set per class (for nested classes)
    private final java.util.ArrayDeque<java.util.Set<String>> privateNameEnvironment = new java.util.ArrayDeque<>();
    // References to private names that need validation (name -> first token where referenced)
    private final java.util.List<java.util.Map.Entry<String, Token>> pendingPrivateRefs = new java.util.ArrayList<>();

    // Context flags for break/continue validation
    private int loopDepth = 0;                    // > 0 inside a loop - allows continue and break
    private int switchDepth = 0;                  // > 0 inside switch - allows break (but not continue)

    // Label tracking for break/continue validation
    // Maps label name to whether it's an iteration label (true = loop, false = non-loop)
    private final java.util.Map<String, Boolean> labelMap = new java.util.HashMap<>();

    // Strict mode tracking
    private boolean strictMode = false;
    private java.util.Stack<Boolean> strictModeStack = new java.util.Stack<>();

    // Statement-only context tracking (for-of body, while body, etc. where declarations not allowed)
    // Used to determine whether 'let\nidentifier' should use ASI
    private boolean inStatementOnlyContext = false;

    // AnnexB single-statement context tracking (if/while/for/labeled body)
    // In these contexts, function declarations don't contribute to LexicallyDeclaredNames
    // so conflicts with lexical declarations are allowed (function just doesn't hoist)
    private boolean inAnnexBSingleStatementContext = false;

    // Pratt parser context - tracks outer expression start for proper location
    private int exprStartPos = 0;
    private SourceLocation.Position exprStartLoc = null;

    // Track whether current expression came from a parenthesized context (for directive detection)
    private boolean lastExpressionWasParenthesized = false;

    // Track position of parenthesized expressions that are NOT valid simple assignment targets
    // -1 means no such expression; >= 0 is the start position of the parenthesized expression
    // This is used to detect cases like `({}) = 1` where the object literal is parenthesized
    private int parenthesizedNonSimpleTarget = -1;

    // Track whether we're parsing statements in a context where directives are valid
    // (Program body or function body - not loop bodies, if consequents, etc.)
    private boolean inDirectiveContext = false;

    // Track exported names for duplicate detection in modules
    private final java.util.Set<String> exportedNames = new java.util.HashSet<>();

    // Track export specifiers that need to be validated (local name + token for error reporting)
    // These are validated at end of module parsing to ensure they reference declared bindings
    private record PendingExportBinding(String localName, Token token) {}
    private final java.util.List<PendingExportBinding> pendingExportBindings = new ArrayList<>();

    // ========================================================================
    // Scope Tracking for Lexical Redeclaration Validation
    // ========================================================================
    // Tracks declared names within block scopes for duplicate detection
    private static class Scope {
        final java.util.Set<String> lexicalDeclarations = new java.util.HashSet<>();
        final java.util.Set<String> varDeclarations = new java.util.HashSet<>();
        final java.util.Set<String> functionDeclarations = new java.util.HashSet<>();
        // Track names that were declared by "plain" FunctionDeclarations (not generators/async)
        // Per AnnexB, in sloppy mode, duplicate plain function declarations are allowed
        final java.util.Set<String> plainFunctionDeclarations = new java.util.HashSet<>();
        final boolean isFunctionScope; // True for function/program scope, false for block scope

        Scope(boolean isFunctionScope) {
            this.isFunctionScope = isFunctionScope;
        }
    }
    private final java.util.ArrayDeque<Scope> scopeStack = new java.util.ArrayDeque<>();

    private void pushScope(boolean isFunctionScope) {
        scopeStack.push(new Scope(isFunctionScope));
    }

    private void popScope() {
        if (!scopeStack.isEmpty()) {
            scopeStack.pop();
        }
    }

    private Scope currentScope() {
        return scopeStack.isEmpty() ? null : scopeStack.peek();
    }

    private void declareLexicalName(String name, Token token) {
        Scope scope = currentScope();
        if (scope == null) return;

        // Check if already declared as lexical in same scope
        if (scope.lexicalDeclarations.contains(name)) {
            throw new ExpectedTokenException("Identifier '" + name + "' has already been declared", token);
        }
        // Check if declared as var in same scope (var/let conflict)
        if (scope.varDeclarations.contains(name)) {
            throw new ExpectedTokenException("Identifier '" + name + "' has already been declared", token);
        }
        // Check if there's a function declaration in the same block scope
        if (scope.functionDeclarations.contains(name)) {
            throw new ExpectedTokenException("Identifier '" + name + "' has already been declared", token);
        }
        scope.lexicalDeclarations.add(name);
    }

    private void declareVarName(String name, Token token) {
        // Var declarations need to check against lexical declarations in ALL scopes
        // up to (but not including) the nearest function scope
        for (Scope scope : scopeStack) {
            if (scope.lexicalDeclarations.contains(name)) {
                throw new ExpectedTokenException("Identifier '" + name + "' has already been declared", token);
            }
            // In block scopes, also check against function declarations
            // (per ES6: LexicallyDeclaredNames and VarDeclaredNames must be disjoint)
            if (!scope.isFunctionScope && scope.functionDeclarations.contains(name)) {
                throw new ExpectedTokenException("Identifier '" + name + "' has already been declared", token);
            }
            if (scope.isFunctionScope) {
                break; // Stop at function boundary
            }
        }
        // Record in ALL scopes up to function boundary (to simulate var hoisting)
        // This allows subsequent lexical declarations to detect the conflict
        for (Scope scope : scopeStack) {
            scope.varDeclarations.add(name);
            if (scope.isFunctionScope) {
                break;
            }
        }
    }

    private void declareFunctionInBlock(String name, Token token, boolean isPlainFunction) {
        Scope scope = currentScope();
        if (scope == null) return;

        // Block-scoped function declarations (in strict mode or blocks)
        // behave like let declarations for redeclaration purposes
        if (scope.lexicalDeclarations.contains(name)) {
            // In sloppy mode with AnnexB single-statement context (if/while/for/labeled body),
            // function declarations that conflict with lexical declarations are allowed.
            // The function just doesn't get hoisted. This is NOT a syntax error.
            // But in block contexts (switch cases, regular blocks), it's still an error.
            if (strictMode || !inAnnexBSingleStatementContext) {
                throw new ExpectedTokenException("Identifier '" + name + "' has already been declared", token);
            }
            // In AnnexB context, don't add to functionDeclarations (no hoisting) but don't throw
            return;
        }
        if (scope.functionDeclarations.contains(name)) {
            // AnnexB B.3.3.4/B.3.3.5: In sloppy mode, duplicate entries in LexicallyDeclaredNames
            // are allowed if they are "only bound by FunctionDeclarations" (not generators/async)
            // So duplicates are allowed only if BOTH the existing and new declarations are plain functions
            boolean existingIsPlain = scope.plainFunctionDeclarations.contains(name);
            if (!strictMode && existingIsPlain && isPlainFunction) {
                // Both are plain function declarations - allowed in sloppy mode
                // No need to add again since it's already in functionDeclarations
                return;
            }
            // Either in strict mode, or one of them is a generator/async/class - error
            throw new ExpectedTokenException("Identifier '" + name + "' has already been declared", token);
        }
        // Also check against var declarations in the same block scope
        // (per ES6: LexicallyDeclaredNames and VarDeclaredNames must be disjoint)
        if (scope.varDeclarations.contains(name)) {
            throw new ExpectedTokenException("Identifier '" + name + "' has already been declared", token);
        }
        scope.functionDeclarations.add(name);
        if (isPlainFunction) {
            scope.plainFunctionDeclarations.add(name);
        }
    }

    /**
     * Check if a statement is a labeled function declaration (possibly nested through multiple labels).
     * Used for early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
     * This error applies in loop bodies (for, for-in, for-of, while, do-while).
     */
    private boolean isLabelledFunction(Statement stmt) {
        if (stmt instanceof LabeledStatement ls) {
            Statement body = ls.body();
            // Check if the body is directly a FunctionDeclaration
            if (body instanceof FunctionDeclaration) {
                return true;
            }
            // Or if the body is another LabeledStatement that contains a function
            return isLabelledFunction(body);
        }
        return false;
    }

    /**
     * Check if a token contains Unicode escape sequences.
     * Contextual keywords like 'of', 'as', 'from', etc. must not contain escapes.
     */
    private boolean tokenContainsEscapes(Token token) {
        // Compare source length to lexeme length - they differ if escapes were present
        int sourceLength = token.endPosition() - token.position();
        return sourceLength != token.lexeme().length();
    }

    // Strict mode reserved words (Future Reserved Words in strict mode)
    private static final java.util.Set<String> STRICT_MODE_RESERVED_WORDS = java.util.Set.of(
        "implements", "interface", "let", "package", "private", "protected", "public", "static", "yield"
    );

    /**
     * Check if a name is a strict mode reserved word.
     */
    private boolean isStrictModeReservedWord(String name) {
        return STRICT_MODE_RESERVED_WORDS.contains(name);
    }

    /**
     * Check if a string is well-formed Unicode (no unpaired surrogates).
     * Used for module export names which must be valid UTF-16.
     */
    private boolean isStringWellFormedUnicode(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                // High surrogate must be followed by low surrogate
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++; // Skip the low surrogate
            } else if (Character.isLowSurrogate(c)) {
                // Low surrogate without preceding high surrogate
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // Private Name Environment Methods (for AllPrivateNamesValid validation)
    // ========================================================================

    /**
     * Push a new private name scope when entering a class.
     */
    private void pushPrivateNameScope() {
        privateNameEnvironment.push(new java.util.HashSet<>());
    }

    /**
     * Pop private name scope when leaving a class.
     */
    private void popPrivateNameScope() {
        privateNameEnvironment.pop();
    }

    /**
     * Declare a private name in the current class scope.
     */
    private void declarePrivateName(String name) {
        if (!privateNameEnvironment.isEmpty()) {
            privateNameEnvironment.peek().add(name);
        }
    }

    /**
     * Check if a private name is declared in any enclosing class scope.
     */
    private boolean isPrivateNameDeclared(String name) {
        for (java.util.Set<String> scope : privateNameEnvironment) {
            if (scope.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Record a private name reference for later validation.
     */
    private void recordPrivateNameReference(String name, Token token) {
        pendingPrivateRefs.add(new java.util.AbstractMap.SimpleEntry<>(name, token));
    }

    /**
     * Validate all pending private name references against declared names.
     * Called at the end of parsing to check script-level private names.
     */
    private void validatePendingPrivateNames() {
        for (var entry : pendingPrivateRefs) {
            String name = entry.getKey();
            Token token = entry.getValue();
            if (!isPrivateNameDeclared(name)) {
                throw new ExpectedTokenException("Private field '#" + name + "' must be declared in an enclosing class", token);
            }
        }
    }

    /**
     * Validate all pending export bindings reference declared module bindings.
     * Called at the end of module parsing.
     * Per ECMAScript: It is a Syntax Error if any element of the ExportedBindings of
     * ModuleItemList does not also occur in either the VarDeclaredNames of
     * ModuleItemList, or the LexicallyDeclaredNames of ModuleItemList.
     */
    private void validatePendingExportBindings() {
        if (scopeStack.isEmpty()) return;
        Scope moduleScope = scopeStack.peekLast(); // Bottom of stack = module scope
        for (var binding : pendingExportBindings) {
            String name = binding.localName();
            // Check if name is declared in the module scope (var, let, const, function, class, or import)
            if (!moduleScope.lexicalDeclarations.contains(name) &&
                !moduleScope.varDeclarations.contains(name) &&
                !moduleScope.functionDeclarations.contains(name)) {
                throw new ExpectedTokenException("Export '" + name + "' is not defined", binding.token());
            }
        }
    }

    // Helper to validate that a binding name is not a strict mode reserved identifier
    private void validateBindingName(String name, Token token) {
        if (strictMode) {
            if (name.equals("eval") || name.equals("arguments")) {
                throw new ExpectedTokenException("Binding '" + name + "' in strict mode", token);
            }
            // Strict mode reserved words cannot be binding names
            if (isStrictModeReservedWord(name)) {
                throw new ExpectedTokenException("'" + name + "' is a reserved word in strict mode", token);
            }
        }
        // 'await' cannot be a binding name in async functions
        if (inAsyncContext && name.equals("await")) {
            throw new ExpectedTokenException("'await' cannot be used as a binding name in async functions", token);
        }
        // 'await' cannot be a binding name in static blocks (except inside nested functions)
        if (inStaticBlock && !inFunction && name.equals("await")) {
            throw new ExpectedTokenException("'await' cannot be used as a binding name in class static block", token);
        }
        // 'await' cannot be a binding name in module code
        if (forceModuleMode && name.equals("await")) {
            throw new ExpectedTokenException("'await' cannot be used as a binding name in module code", token);
        }
    }

    /**
     * Validate that a class name is not a strict mode reserved word.
     * Class bodies are always in strict mode, so these words cannot be class names.
     */
    private void validateClassName(String name, Token token) {
        if (isStrictModeReservedWord(name)) {
            throw new ExpectedTokenException("'" + name + "' cannot be used as a class name in strict mode", token);
        }
        // 'await' cannot be a class name in static blocks (except inside nested functions)
        if (inStaticBlock && !inFunction && name.equals("await")) {
            throw new ExpectedTokenException("'await' cannot be used as a class name in class static block", token);
        }
        // 'await' cannot be a class name in module code
        if (forceModuleMode && name.equals("await")) {
            throw new ExpectedTokenException("'await' cannot be used as a class name in module code", token);
        }
    }

    /**
     * Validate that there are no duplicate parameter names.
     * Arrow functions and strict mode functions don't allow duplicates.
     */
    private void validateNoDuplicateParams(List<Pattern> params, Token functionToken) {
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (Pattern param : params) {
            java.util.List<String> names = new ArrayList<>();
            collectBindingNames(param, names);
            for (String name : names) {
                if (seenNames.contains(name)) {
                    throw new ExpectedTokenException("Duplicate parameter name not allowed", functionToken);
                }
                // In strict mode, 'eval' and 'arguments' are forbidden as parameter names
                // Arrow functions implicitly have strict parameter lists
                if (strictMode && (name.equals("eval") || name.equals("arguments"))) {
                    throw new ExpectedTokenException("'" + name + "' is not allowed as a parameter name in strict mode", functionToken);
                }
                // Strict mode reserved words are not allowed as parameter names
                if (strictMode && isStrictModeReservedWord(name)) {
                    throw new ExpectedTokenException("'" + name + "' is a reserved word and not allowed as a parameter name in strict mode", functionToken);
                }
                // 'await' is not allowed as a parameter name in static blocks
                // (Arrow functions inherit the static block context for 'await')
                if (inStaticBlock && name.equals("await")) {
                    throw new ExpectedTokenException("'await' is not allowed as a parameter name in class static block", functionToken);
                }
                // 'yield' is not allowed as a parameter name in generators
                if (inGenerator && name.equals("yield")) {
                    throw new ExpectedTokenException("'yield' is not allowed as a parameter name in generator", functionToken);
                }
                // 'enum' is always reserved
                if (name.equals("enum")) {
                    throw new ExpectedTokenException("'enum' is a reserved word and not allowed as a parameter name", functionToken);
                }
                seenNames.add(name);
            }
        }
    }

    // Helper to collect all binding names from a pattern (Identifier, ArrayPattern, ObjectPattern, RestElement, AssignmentPattern)
    private void collectBindingNames(Pattern pattern, java.util.List<String> names) {
        if (pattern instanceof Identifier id) {
            names.add(id.name());
        } else if (pattern instanceof RestElement rest) {
            // Rest element at top level (for function params like ...x)
            collectBindingNames((Pattern) rest.argument(), names);
        } else if (pattern instanceof AssignmentPattern ap) {
            // Assignment pattern at top level (for default params like x = 1)
            collectBindingNames((Pattern) ap.left(), names);
        } else if (pattern instanceof ArrayPattern arr) {
            for (Node elem : arr.elements()) {
                if (elem != null) {
                    if (elem instanceof RestElement rest) {
                        collectBindingNames((Pattern) rest.argument(), names);
                    } else if (elem instanceof Pattern p) {
                        collectBindingNames(p, names);
                    } else if (elem instanceof AssignmentPattern ap) {
                        collectBindingNames((Pattern) ap.left(), names);
                    }
                }
            }
        } else if (pattern instanceof ObjectPattern obj) {
            for (Node prop : obj.properties()) {
                if (prop instanceof Property p) {
                    Node value = p.value();
                    if (value instanceof Pattern pat) {
                        collectBindingNames(pat, names);
                    } else if (value instanceof AssignmentPattern ap) {
                        collectBindingNames((Pattern) ap.left(), names);
                    }
                } else if (prop instanceof RestElement rest) {
                    collectBindingNames((Pattern) rest.argument(), names);
                }
            }
        } else if (pattern instanceof AssignmentPattern ap) {
            collectBindingNames((Pattern) ap.left(), names);
        }
    }

    /**
     * Collect lexically declared names from a block (let, const, function declarations at block level).
     * This is used for checking catch parameter / block conflicts and for-loop head / body conflicts.
     * @param includeHoistable if true, includes function declarations (only true in strict mode)
     */
    private void collectLexicallyDeclaredNames(BlockStatement block, java.util.List<String> names, boolean includeHoistable) {
        for (Statement stmt : block.body()) {
            if (stmt instanceof VariableDeclaration varDecl) {
                // let and const are lexically declared
                if (varDecl.kind().equals("let") || varDecl.kind().equals("const")) {
                    for (VariableDeclarator declarator : varDecl.declarations()) {
                        collectBindingNames(declarator.id(), names);
                    }
                }
            } else if (stmt instanceof FunctionDeclaration funcDecl) {
                // Function declarations in blocks/strict mode are lexically scoped (ES6+)
                // In sloppy mode function bodies, they're var-like and CAN shadow parameters
                if (includeHoistable && funcDecl.id() != null) {
                    names.add(funcDecl.id().name());
                }
            } else if (stmt instanceof ClassDeclaration classDecl) {
                // Class declarations are always lexically scoped
                if (classDecl.id() != null) {
                    names.add(classDecl.id().name());
                }
            }
        }
    }

    /**
     * Collect var declared names from a statement (recursively scans nested blocks).
     * This is used for checking for-loop head / body conflicts.
     */
    private void collectVarDeclaredNames(Statement stmt, java.util.List<String> names) {
        if (stmt instanceof VariableDeclaration varDecl && varDecl.kind().equals("var")) {
            for (VariableDeclarator declarator : varDecl.declarations()) {
                collectBindingNames(declarator.id(), names);
            }
        } else if (stmt instanceof BlockStatement block) {
            for (Statement s : block.body()) {
                collectVarDeclaredNames(s, names);
            }
        } else if (stmt instanceof IfStatement ifStmt) {
            collectVarDeclaredNames(ifStmt.consequent(), names);
            if (ifStmt.alternate() != null) {
                collectVarDeclaredNames(ifStmt.alternate(), names);
            }
        } else if (stmt instanceof WhileStatement whileStmt) {
            collectVarDeclaredNames(whileStmt.body(), names);
        } else if (stmt instanceof DoWhileStatement doWhileStmt) {
            collectVarDeclaredNames(doWhileStmt.body(), names);
        } else if (stmt instanceof ForStatement forStmt) {
            if (forStmt.init() instanceof VariableDeclaration varDecl && varDecl.kind().equals("var")) {
                for (VariableDeclarator declarator : varDecl.declarations()) {
                    collectBindingNames(declarator.id(), names);
                }
            }
            collectVarDeclaredNames(forStmt.body(), names);
        } else if (stmt instanceof ForInStatement forInStmt) {
            if (forInStmt.left() instanceof VariableDeclaration varDecl && varDecl.kind().equals("var")) {
                for (VariableDeclarator declarator : varDecl.declarations()) {
                    collectBindingNames(declarator.id(), names);
                }
            }
            collectVarDeclaredNames(forInStmt.body(), names);
        } else if (stmt instanceof ForOfStatement forOfStmt) {
            if (forOfStmt.left() instanceof VariableDeclaration varDecl && varDecl.kind().equals("var")) {
                for (VariableDeclarator declarator : varDecl.declarations()) {
                    collectBindingNames(declarator.id(), names);
                }
            }
            collectVarDeclaredNames(forOfStmt.body(), names);
        } else if (stmt instanceof SwitchStatement switchStmt) {
            for (SwitchCase c : switchStmt.cases()) {
                for (Statement s : c.consequent()) {
                    collectVarDeclaredNames(s, names);
                }
            }
        } else if (stmt instanceof TryStatement tryStmt) {
            collectVarDeclaredNames(tryStmt.block(), names);
            if (tryStmt.handler() != null) {
                collectVarDeclaredNames(tryStmt.handler().body(), names);
            }
            if (tryStmt.finalizer() != null) {
                collectVarDeclaredNames(tryStmt.finalizer(), names);
            }
        } else if (stmt instanceof WithStatement withStmt) {
            collectVarDeclaredNames(withStmt.body(), names);
        } else if (stmt instanceof LabeledStatement labeledStmt) {
            collectVarDeclaredNames(labeledStmt.body(), names);
        }
        // Note: FunctionDeclaration doesn't contribute to VarDeclaredNames of the enclosing scope
    }

    /**
     * Check for conflicts between parameter names and lexically declared names in body.
     * This implements the ES6 early error: "It is a Syntax Error if any element of the
     * BoundNames of FormalParameters also occurs in the LexicallyDeclaredNames of FunctionBody."
     */
    private void validateNoParamBodyConflicts(List<Pattern> params, BlockStatement body, Token token) {
        // Collect parameter names
        java.util.Set<String> paramNames = new java.util.HashSet<>();
        for (Pattern param : params) {
            java.util.List<String> names = new ArrayList<>();
            collectBindingNames(param, names);
            paramNames.addAll(names);
        }

        // Collect lexically declared names from body
        // In sloppy mode, function declarations CAN shadow parameters (they're var-like)
        // Only in strict mode are they lexical bindings that conflict with params
        java.util.List<String> lexicalNames = new ArrayList<>();
        collectLexicallyDeclaredNames(body, lexicalNames, strictMode);

        // Check for conflicts
        for (String lexName : lexicalNames) {
            if (paramNames.contains(lexName)) {
                throw new ExpectedTokenException("Identifier '" + lexName + "' has already been declared", token);
            }
        }
    }

    /**
     * Register an exported name and check for duplicates.
     * Per ES6 spec, duplicate exported names are a syntax error.
     */
    private void registerExportedName(String name, Token token) {
        if (exportedNames.contains(name)) {
            throw new ExpectedTokenException("Duplicate export of '" + name + "'", token);
        }
        exportedNames.add(name);
    }

    public Parser(String source) {
        this(source, false, false);
    }

    public Parser(String source, boolean forceModuleMode) {
        this(source, forceModuleMode, false);
    }

    public Parser(String source, boolean forceModuleMode, boolean forceStrictMode) {
        this.source = source;
        this.sourceBuf = source.toCharArray();
        this.sourceLength = sourceBuf.length;

        // Module mode is always strict
        // Force strict mode can also be enabled via flag
        boolean initialStrictMode = forceModuleMode || forceStrictMode;
        this.strictMode = initialStrictMode;

        this.lexer = new Lexer(source, initialStrictMode, forceModuleMode);
        this.tokens = lexer.tokenize();
        this.forceModuleMode = forceModuleMode;
        this.lineOffsets = buildLineOffsetIndex();
    }

    public Program parse() {
        List<Statement> statements = new ArrayList<>();

        // Initialize the program-level scope (function scope)
        pushScope(true);

        // In module mode, top-level code allows await
        atModuleTopLevel = forceModuleMode;

        // Enable directive context for program body
        inDirectiveContext = true;
        boolean inPrologue = true;
        while (!isAtEnd()) {
            Statement stmt = parseStatement();

            // Check for directive prologue and apply strict mode immediately
            if (inPrologue && inDirectiveContext) {
                if (isUseStrictDirective(stmt)) {
                    strictMode = true;
                    lexer.setStrictMode(true);
                } else if (!isPotentialDirective(stmt)) {
                    // Non-directive statement ends the prologue
                    inPrologue = false;
                    inDirectiveContext = false;
                }
            }

            statements.add(stmt);
        }
        inDirectiveContext = false;

        // Process directive prologue for program (adds directive property to AST nodes)
        statements = processDirectives(statements);

        // Validate that all private name references are to declared names
        // (AllPrivateNamesValid check - must be done at script/module level)
        validatePendingPrivateNames();

        // Validate that all export specifiers reference declared bindings
        // (only applies to module code)
        if (forceModuleMode) {
            validatePendingExportBindings();
        }

        // Determine sourceType - no auto-detection, must be explicitly set (matches Acorn)
        String sourceType = forceModuleMode ? "module" : "script";

        // Program loc should span entire file (line 1 to last position)
        // Calculate the end position from the actual source length
        SourceLocation.Position endPos = getPositionFromOffset(sourceLength);
        return new Program(0, sourceLength, 1, 0, endPos.line(), endPos.column(), statements, sourceType);
    }

    /**
     * Parse a statement using switch dispatch.
     *
     * Most statement types are handled by inline switch.
     * Special cases requiring lookahead are handled explicitly:
     * - LET: Could be identifier or declaration keyword
     * - IMPORT: Could be dynamic import expression or import declaration
     * - IDENTIFIER: Could be async function, labeled statement, or expression
     */
    private Statement parseStatement() {
        // Reset parenthesizedNonSimpleTarget at statement boundary
        // This ensures the flag doesn't persist across statements
        parenthesizedNonSimpleTarget = -1;

        Token token = peek();
        TokenType type = token.type();

        // Inline switch dispatch for performance (avoids lambda indirection)
        return switch (type) {
            // Variable declarations
            case VAR, CONST -> parseVariableDeclaration();

            // Block statement
            case LBRACE -> parseBlockStatement();

            // Control flow statements
            case IF -> parseIfStatement();
            case WHILE -> parseWhileStatement();
            case DO -> parseDoWhileStatement();
            case FOR -> parseForStatement();
            case SWITCH -> parseSwitchStatement();

            // Jump statements
            case RETURN -> parseReturnStatement();
            case BREAK -> parseBreakStatement();
            case CONTINUE -> parseContinueStatement();
            case THROW -> parseThrowStatement();

            // Exception handling
            case TRY -> parseTryStatement();

            // Other statements
            case WITH -> parseWithStatement();
            case DEBUGGER -> parseDebuggerStatement();
            case SEMICOLON -> parseEmptyStatement();

            // Declarations
            case FUNCTION -> parseFunctionDeclaration(false);
            case CLASS -> parseClassDeclaration();

            // Module declarations
            case EXPORT -> parseExportDeclaration();

            // Special cases requiring lookahead
            case LET -> parseLetStatementOrExpression(token);
            case IMPORT -> parseImportStatementOrExpression(token);
            case IDENTIFIER -> parseIdentifierStatement(token);

            // Default: Parse as expression statement
            default -> parseExpressionStatement(token);
        };
    }

    /**
     * Parse a nested statement (e.g., for-loop body, if consequent).
     * Clears directive context since directives are only valid at the top level of Program/function body.
     */
    private Statement parseNestedStatement() {
        return parseNestedStatement(false, false);
    }

    /**
     * Parse a nested statement for if/else bodies (allows function declarations in non-strict mode via Annex B).
     */
    private Statement parseNestedStatementForIf() {
        return parseNestedStatement(false, true);
    }

    /**
     * Parse a nested statement.
     * @param allowLexicalDeclarations If false, lexical declarations (let/const/class) are not allowed.
     *                                  This is used for single-statement contexts like if/for/while bodies.
     * @param allowFunctionDeclaration If true, allows function declarations in non-strict mode (Annex B for if statements).
     *                                  If false, function declarations are rejected even in non-strict mode.
     */
    private Statement parseNestedStatement(boolean allowLexicalDeclarations, boolean allowFunctionDeclaration) {
        boolean savedDirectiveContext = inDirectiveContext;
        boolean savedAnnexBContext = inAnnexBSingleStatementContext;
        inDirectiveContext = false;
        // When allowFunctionDeclaration is true (if/labeled statement bodies), we're in an AnnexB
        // single-statement context where function declarations don't contribute to LexicallyDeclaredNames
        inAnnexBSingleStatementContext = allowFunctionDeclaration;
        try {
            // Import/export declarations are never allowed in nested statement contexts
            Token currentToken = peek();
            if (currentToken.type() == TokenType.IMPORT && forceModuleMode) {
                // Check if this is an import declaration (not import expression)
                if (this.current + 1 < tokens.size()) {
                    Token next = tokens.get(this.current + 1);
                    // import.meta and import() are expressions, not declarations
                    if (next.type() != TokenType.DOT && next.type() != TokenType.LPAREN) {
                        throw new ExpectedTokenException("'import' is only valid at the top level of a module", currentToken);
                    }
                }
            }
            if (currentToken.type() == TokenType.EXPORT) {
                throw new ExpectedTokenException("'export' is only valid at the top level of a module", currentToken);
            }

            // Check for declarations that are not allowed in single-statement contexts
            if (!allowLexicalDeclarations) {
                if (currentToken.type() == TokenType.CONST) {
                    throw new ExpectedTokenException("Lexical declaration cannot appear in a single-statement context", currentToken);
                }
                if (currentToken.type() == TokenType.LET) {
                    // Check if this is actually a let declaration (not 'let' as identifier)
                    // let is a declaration if followed by identifier, {, or [
                    if (this.current + 1 < tokens.size()) {
                        Token next = tokens.get(this.current + 1);
                        // `let [` is special - the lookahead restriction applies even across newlines
                        // This is because `let [` at the start of an ExpressionStatement is explicitly forbidden
                        if (next.type() == TokenType.LBRACKET) {
                            throw new ExpectedTokenException("'let [' cannot start an expression statement", currentToken);
                        }
                        if (next.type() == TokenType.IDENTIFIER || next.type() == TokenType.LBRACE) {
                            // For identifiers and object patterns, ASI matters
                            if (currentToken.line() == next.line()) {
                                throw new ExpectedTokenException("Lexical declaration cannot appear in a single-statement context", currentToken);
                            }
                        }
                    }
                }
                if (currentToken.type() == TokenType.CLASS) {
                    throw new ExpectedTokenException("Lexical declaration cannot appear in a single-statement context", currentToken);
                }
                // Check for generator function declarations (not allowed in single-statement contexts)
                if (currentToken.type() == TokenType.FUNCTION) {
                    if (this.current + 1 < tokens.size()) {
                        Token next = tokens.get(this.current + 1);
                        if (next.type() == TokenType.STAR) {
                            throw new ExpectedTokenException("Generator declaration cannot appear in a single-statement context", currentToken);
                        }
                    }
                    // In strict mode, function declarations are not allowed in single-statement contexts
                    // In non-strict mode, they are only allowed via Annex B in if statements (allowFunctionDeclaration=true)
                    if (strictMode || !allowFunctionDeclaration) {
                        throw new ExpectedTokenException("In strict mode code, functions can only be declared at top level or inside a block", currentToken);
                    }
                }
                // Check for async function/generator declarations
                if (currentToken.type() == TokenType.IDENTIFIER && currentToken.lexeme().equals("async")) {
                    if (this.current + 1 < tokens.size()) {
                        Token next = tokens.get(this.current + 1);
                        // Check for no line break between async and function (to distinguish from expression)
                        if (next.type() == TokenType.FUNCTION && currentToken.line() == next.line()) {
                            throw new ExpectedTokenException("Async function declaration cannot appear in a single-statement context", currentToken);
                        }
                    }
                }
            }
            // Set Statement-only context when lexical declarations are not allowed
            // This affects how 'let\nidentifier' is parsed in parseLetStatementOrExpression
            boolean savedStatementOnlyContext = inStatementOnlyContext;
            inStatementOnlyContext = !allowLexicalDeclarations;
            try {
                return parseStatement();
            } finally {
                inStatementOnlyContext = savedStatementOnlyContext;
            }
        } finally {
            inDirectiveContext = savedDirectiveContext;
            inAnnexBSingleStatementContext = savedAnnexBContext;
        }
    }

    /**
     * Handle LET which can be either a declaration keyword or an identifier.
     * - let x = 1     → VariableDeclaration
     * - let = 5       → ExpressionStatement (let as identifier)
     * - let[0] = 1    → ExpressionStatement (let as identifier)
     * - let\n x       → VariableDeclaration (NO ASI between let and BindingIdentifier!)
     * - let\n (       → ExpressionStatement with ASI (next token can't be binding identifier)
     *
     * Per spec: ASI cannot occur between `let` and a potential BindingIdentifier because
     * binding identifiers can include contextual keywords like 'await' and 'yield'.
     * The static semantics then reject invalid binding names as a separate error.
     */
    private Statement parseLetStatementOrExpression(Token token) {
        Token letToken = peek();

        // If followed by = (not part of destructuring), it's an identifier being assigned
        if (checkAhead(1, TokenType.ASSIGN)) {
            return parseExpressionStatement(token);
        }

        // Check what follows 'let'
        if (current + 1 < tokens.size()) {
            Token nextToken = tokens.get(current + 1);
            TokenType nextType = nextToken.type();

            // Check for line terminator between 'let' and next token
            boolean hasLineTerminator = letToken.line() != nextToken.line();

            // Per spec: 'let [' is restricted at statement start (lookahead ∉ { let [ })
            // This restriction applies EVEN across newlines - it's a syntax error
            if (nextType == TokenType.LBRACKET) {
                // let [ is always a declaration (lookahead restriction makes this unambiguous)
                return parseVariableDeclaration();
            }

            // For 'let {' with a newline: ASI applies, 'let' is an identifier, '{}' is a block
            // This is because '{' could start either destructuring OR a block statement,
            // and the ExpressionStatement lookahead only restricts 'let [', not 'let {'
            if (hasLineTerminator && nextType == TokenType.LBRACE) {
                return parseExpressionStatement(token);
            }

            // Check if followed by a token that can start a binding (identifier, {, yield, await, let)
            boolean canStartBinding = nextType == TokenType.IDENTIFIER ||
                nextType == TokenType.LBRACE ||
                nextToken.lexeme().equals("yield") ||
                nextToken.lexeme().equals("await") ||
                nextToken.lexeme().equals("let");

            if (canStartBinding) {
                // In Statement-only context (for-of body, while body, etc.) with line terminator:
                // ASI applies, 'let' is an identifier expression statement
                // In StatementListItem context (block, program body): it's a declaration
                if (hasLineTerminator && inStatementOnlyContext) {
                    return parseExpressionStatement(token);
                }
                return parseVariableDeclaration();
            }

            // If followed by line terminator and next token can't start a binding,
            // then ASI applies and 'let' is an identifier
            if (hasLineTerminator) {
                return parseExpressionStatement(token);
            }
        }

        // Parse as variable declaration (includes let x, let [x], let {x}, etc.)
        return parseVariableDeclaration();
    }

    /**
     * Handle IMPORT which can be either a declaration or an expression.
     * - import { foo } from 'module'  → ImportDeclaration
     * - import('./module.js')         → ExpressionStatement (dynamic import)
     * - import.meta                   → ExpressionStatement (import.meta)
     */
    private Statement parseImportStatementOrExpression(Token token) {
        if (current + 1 < tokens.size()) {
            TokenType nextType = tokens.get(current + 1).type();
            if (nextType == TokenType.LPAREN || nextType == TokenType.DOT) {
                // Dynamic import or import.meta - parse as expression statement
                return parseExpressionStatement(token);
            }
        }
        // Import declaration
        return parseImportDeclaration();
    }

    /**
     * Handle IDENTIFIER which can be async function, labeled statement, or expression.
     * - async function foo() {}  → FunctionDeclaration
     * - label: statement         → LabeledStatement
     * - expression;              → ExpressionStatement
     */
    private Statement parseIdentifierStatement(Token token) {
        // Check for async function declaration
        // No line terminator is allowed between async and function
        if (token.lexeme().equals("async") && current + 1 < tokens.size() &&
            tokens.get(current + 1).type() == TokenType.FUNCTION &&
            tokens.get(current).line() == tokens.get(current + 1).line()) {
            // 'async' keyword must not contain Unicode escape sequences
            Token asyncToken = tokens.get(current);
            if (tokenContainsEscapes(asyncToken)) {
                throw new ExpectedTokenException("'async' keyword must not contain Unicode escape sequences", asyncToken);
            }
            return parseFunctionDeclaration(true);
        }

        // Parse as expression first
        Expression expr = parseExpression();

        // Check if this is a labeled statement (identifier followed by colon)
        if (expr instanceof Identifier id && check(TokenType.COLON)) {
            advance(); // consume ':'
            return parseLabeledStatementBody(token, id);
        }

        // Validate that the expression doesn't contain invalid cover grammar
        validateNoCoverInitializedName(expr);

        // Regular expression statement
        consumeSemicolon("Expected ';' after expression");
        Token endToken = previous();
        return new ExpressionStatement(getStart(token), getEnd(endToken), token.line(), token.column(), endToken.endLine(), endToken.endColumn(), expr);
    }

    /**
     * Parse an expression statement (default case for unknown statement types).
     * Also handles labeled statements if the expression is an identifier followed by colon.
     */
    private Statement parseExpressionStatement(Token token) {
        // ExpressionStatement has a lookahead restriction: [lookahead ∉ { {, function, async function, class, let [ }]
        // If the expression starts with 'let' followed by '[', it's a syntax error
        // (to avoid ambiguity with let declarations)
        if (token.type() == TokenType.LET ||
            (token.type() == TokenType.IDENTIFIER && token.lexeme().equals("let"))) {
            // Check if next token (after 'let') is '[' (even across newlines)
            if (checkAhead(1, TokenType.LBRACKET)) {
                throw new ExpectedTokenException("'let [' cannot start an expression statement", token);
            }
        }

        // Reset parenthesized flag before parsing expression
        lastExpressionWasParenthesized = false;
        Expression expr = parseExpression();
        // Capture whether the expression was parenthesized (for directive detection)
        boolean wasParenthesized = lastExpressionWasParenthesized;

        // Check if this is a labeled statement (identifier followed by colon)
        if (expr instanceof Identifier id && check(TokenType.COLON)) {
            advance(); // consume ':'
            return parseLabeledStatementBody(token, id);
        }

        // Validate that the expression doesn't contain invalid cover grammar
        // CoverInitializedName ({ x = 1 }) is only valid in destructuring assignments
        validateNoCoverInitializedName(expr);

        consumeSemicolon("Expected ';' after expression");
        Token endToken = previous();

        // If expression was parenthesized and we're in a directive context, mark it so it won't be treated as directive
        // Only use the empty directive marker in directive contexts (Program/function body)
        String directive = (inDirectiveContext && wasParenthesized) ? "" : null;
        return new ExpressionStatement(getStart(token), getEnd(endToken), token.line(), token.column(), endToken.endLine(), endToken.endColumn(), expr, directive);
    }

    /**
     * Parse the body of a labeled statement.
     * Tracks the label for break/continue validation.
     */
    private LabeledStatement parseLabeledStatementBody(Token startToken, Identifier label) {
        String labelName = label.name();

        // Check for duplicate label
        if (labelMap.containsKey(labelName)) {
            throw new ExpectedTokenException("Label '" + labelName + "' has already been declared", startToken);
        }

        // Determine if this will be an iteration statement
        boolean isIteration = check(TokenType.WHILE) || check(TokenType.DO) || check(TokenType.FOR);

        // Add label to map
        labelMap.put(labelName, isIteration);

        try {
            // Labeled statements have single-statement context restrictions
            // Generators and async functions are rejected, but regular function declarations
            // are allowed in sloppy mode via AnnexB (like if statements)
            Statement body = parseNestedStatementForIf();
            Token endToken = previous();
            return new LabeledStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), label, body);
        } finally {
            // Always remove label when done
            labelMap.remove(labelName);
        }
    }

    private WhileStatement parseWhileStatement() {
        Token startToken = peek();
        advance(); // consume 'while'

        consume(TokenType.LPAREN, "Expected '(' after 'while'");
        Expression test = parseExpression();
        consume(TokenType.RPAREN, "Expected ')' after while condition");

        loopDepth++;
        try {
            Statement body = parseNestedStatement();

            // Early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
            if (isLabelledFunction(body)) {
                throw new ExpectedTokenException("Labeled function declarations are not allowed in while loop body", startToken);
            }

            Token endToken = previous();
            return new WhileStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), test, body);
        } finally {
            loopDepth--;
        }
    }

    private DoWhileStatement parseDoWhileStatement() {
        Token startToken = peek();
        advance(); // consume 'do'

        loopDepth++;
        Statement body;
        try {
            body = parseNestedStatement();

            // Early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
            if (isLabelledFunction(body)) {
                throw new ExpectedTokenException("Labeled function declarations are not allowed in do-while loop body", startToken);
            }
        } finally {
            loopDepth--;
        }

        consume(TokenType.WHILE, "Expected 'while' after do body");
        consume(TokenType.LPAREN, "Expected '(' after 'while'");
        Expression test = parseExpression();
        consume(TokenType.RPAREN, "Expected ')' after do-while condition");

        // Special ASI rule for do-while: semicolon can be inserted even on same line
        // if previous token is ) and it would complete the do-while
        if (check(TokenType.SEMICOLON)) {
            advance();
        }
        // Otherwise ASI applies (line break, }, EOF, or offending token)

        Token endToken = previous();
        return new DoWhileStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), body, test);
    }

    private Statement parseForStatement() {
        Token startToken = peek();
        advance(); // consume 'for'

        // Check for for-await-of: for await (...)
        // 'await' must not contain Unicode escapes
        boolean isAwait = false;
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("await") && !tokenContainsEscapes(peek())) {
            advance(); // consume 'await'
            isAwait = true;
        }

        consume(TokenType.LPAREN, "Expected '(' after 'for'");

        // Parse init/left clause - can be var/let/const declaration, expression, or empty
        // Disable 'in' as operator to allow for-in detection
        boolean oldAllowIn = allowIn;
        allowIn = false;

        Node initOrLeft = null;
        Token kindToken = null;
        if (!check(TokenType.SEMICOLON)) {
            // Check for var/let/const
            // BUT: 'let' is treated as identifier (not declaration keyword) when followed by:
            // - 'in' (for-in loop): for (let in obj)
            // - 'of' (for-of loop): for (let of arr)
            // - ';' (expression): for (let; ; )
            // - '=' (assignment): for (let = 3; ; )
            // Note: 'let [' IS a declaration (destructuring), not an identifier
            // Note: 'of' with escapes (like o\u0066) is not the 'of' keyword
            boolean isOfKeywordAhead = checkAhead(1, TokenType.IDENTIFIER) &&
                tokens.get(current + 1).lexeme().equals("of") &&
                !tokenContainsEscapes(tokens.get(current + 1));
            boolean isDeclaration = check(TokenType.VAR) || check(TokenType.CONST) ||
                (check(TokenType.LET) && !checkAhead(1, TokenType.IN) && !checkAhead(1, TokenType.SEMICOLON) &&
                 !checkAhead(1, TokenType.ASSIGN) && !isOfKeywordAhead);

            if (isDeclaration && match(TokenType.VAR, TokenType.LET, TokenType.CONST)) {
                // Variable declaration - support destructuring patterns
                kindToken = previous();
                String kind = kindToken.lexeme();
                boolean isLexical = kind.equals("let") || kind.equals("const");
                List<VariableDeclarator> declarators = new ArrayList<>();

                do {
                    Token patternStart = peek();
                    // Use parsePatternBase to support destructuring
                    Pattern pattern = parsePatternBase();

                    // Validate binding names
                    java.util.List<String> bindingNames = new ArrayList<>();
                    collectBindingNames(pattern, bindingNames);
                    java.util.Set<String> seenNames = new java.util.HashSet<>();
                    for (String name : bindingNames) {
                        // In strict mode, eval and arguments cannot be used as binding names
                        validateBindingName(name, patternStart);
                        // 'let' cannot be a binding name in lexical declarations
                        if (isLexical && name.equals("let")) {
                            throw new ExpectedTokenException("'let' is disallowed as a lexically bound name", patternStart);
                        }
                        // Check for duplicate binding names - only for lexical declarations
                        // var declarations in for-in/for-of are allowed to have duplicates
                        if (isLexical && seenNames.contains(name)) {
                            throw new ExpectedTokenException("Duplicate binding name '" + name + "'", patternStart);
                        }
                        seenNames.add(name);
                    }

                    Expression initExpr = null;
                    if (match(TokenType.ASSIGN)) {
                        initExpr = parseExpr(BP_ASSIGNMENT);
                    }

                    Token declaratorEnd = previous();

                    int declaratorStart = getStart(patternStart);
                    int declaratorEndPos = getEnd(declaratorEnd);

                    declarators.add(new VariableDeclarator(declaratorStart, declaratorEndPos, patternStart.line(), patternStart.column(), declaratorEnd.endLine(), declaratorEnd.endColumn(), pattern, initExpr));

                } while (match(TokenType.COMMA));

                Token endToken = previous();
                int declStart = getStart(kindToken);
                int declEnd = getEnd(endToken);
                initOrLeft = new VariableDeclaration(declStart, declEnd, kindToken.line(), kindToken.column(), endToken.endLine(), endToken.endColumn(), declarators, kind);
            } else {
                initOrLeft = parseExpression();
            }
        }

        // Track if the FIRST TOKEN after '(' was literally 'async' (not escaped)
        // followed by 'of' for the for-of restriction: [lookahead ∉ { let, async of }]
        // This is about the token sequence, not the parsed expression
        // So 'for ((async) of ...)' is valid because it starts with '(', not 'async of'
        boolean lhsIsLiteralAsyncOf = false;
        if (!check(TokenType.SEMICOLON) && !check(TokenType.IN)) {
            // We're about to check for 'of' - see if the first token after '(' was 'async of'
            // Look at the token that was at position right after the opening '('
            // That token's position would match the start position of initOrLeft
            if (initOrLeft != null && current > 0 && current < tokens.size()) {
                // The first token of initOrLeft is at the position where parsing started
                int initStart = -1;
                if (initOrLeft instanceof Expression expr) {
                    initStart = expr.start();
                } else if (initOrLeft instanceof VariableDeclaration varDecl) {
                    initStart = varDecl.start();
                }
                if (initStart >= 0) {
                    // Find the token at that position
                    for (int i = 0; i < tokens.size(); i++) {
                        Token t = tokens.get(i);
                        if (t.position() == initStart) {
                            // This was the first token after '('
                            // Check if it's literally 'async' (not escaped) AND the expression is just this identifier
                            if (t.lexeme().equals("async") && !tokenContainsEscapes(t) &&
                                initOrLeft instanceof Identifier) {
                                // And check if the NEXT token in the stream was 'of'
                                if (i + 1 < tokens.size()) {
                                    Token next = tokens.get(i + 1);
                                    if (next.lexeme().equals("of") && !tokenContainsEscapes(next)) {
                                        lhsIsLiteralAsyncOf = true;
                                    }
                                }
                            }
                            break;
                        }
                    }
                }
            }
        }

        // Restore allowIn flag
        allowIn = oldAllowIn;

        // Check for for-in or for-of
        // Note: 'of' is a contextual keyword (IDENTIFIER token with lexeme "of")
        // Contextual keywords must not contain Unicode escapes (e.g., o\u0066 is not valid)
        boolean isOfKeyword = check(TokenType.IDENTIFIER) && peek().lexeme().equals("of") && !tokenContainsEscapes(peek());

        if (check(TokenType.IN)) {
            advance(); // consume 'in'

            // Validate for-in initializers
            // 1. Assignment expressions are never allowed: for (a = 0 in obj)
            // 2. Variable declaration initializers are only allowed in sloppy mode (Annex B): for (var a = 0 in obj)
            // 3. Only single binding allowed: for (let x, y in obj) is invalid
            if (initOrLeft instanceof VariableDeclaration) {
                VariableDeclaration varDecl = (VariableDeclaration) initOrLeft;

                // For-in/for-of only allows a single binding
                if (varDecl.declarations().size() > 1) {
                    throw new ParseException("SyntaxError", peek(), null, "for-in statement",
                        "Only a single binding allowed in for-in loop");
                }

                // Check if any declarator has an initializer
                for (VariableDeclarator declarator : varDecl.declarations()) {
                    if (declarator.init() != null) {
                        // Destructuring patterns (ObjectPattern/ArrayPattern) with initializers are NEVER allowed
                        Pattern pattern = declarator.id();
                        if (pattern instanceof ObjectPattern || pattern instanceof ArrayPattern) {
                            throw new ParseException("SyntaxError", peek(), null, "for-in statement",
                                "for-in loop variable declaration with destructuring may not have an initializer");
                        }

                        // In strict mode, initializers are never allowed
                        if (strictMode) {
                            throw new ParseException("SyntaxError", peek(), null, "for-in statement",
                                "for-in loop variable declaration may not have an initializer in strict mode");
                        }
                        // In sloppy mode, only single 'var' declarations with initializers are allowed (Annex B)
                        // let/const are never allowed, even in sloppy mode
                        if (!varDecl.kind().equals("var")) {
                            throw new ParseException("SyntaxError", peek(), null, "for-in statement",
                                "for-in loop " + varDecl.kind() + " declaration may not have an initializer");
                        }
                        if (varDecl.declarations().size() > 1) {
                            throw new ParseException("SyntaxError", peek(), null, "for-in statement",
                                "for-in loop variable declaration may not have an initializer");
                        }
                    }
                }
            } else if (initOrLeft instanceof Expression expr) {
                // Check if it's an assignment expression
                if (expr instanceof AssignmentExpression) {
                    throw new ParseException("SyntaxError", peek(), null, "for-in statement",
                        "Invalid left-hand side in for-in loop: assignment expression not allowed");
                }

                // Validate that the expression is a valid assignment target
                validateForInOfLHS(expr, peek(), "for-in");

                // Convert left to pattern if it's an expression (for destructuring)
                initOrLeft = convertToPatternIfNeeded(initOrLeft);
            }

            Expression right = parseExpression();
            consume(TokenType.RPAREN, "Expected ')' after for-in");
            loopDepth++;
            try {
                Statement body = parseNestedStatement();

                // Early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
                if (isLabelledFunction(body)) {
                    throw new ExpectedTokenException("Labeled function declarations are not allowed in for-in loop body", startToken);
                }

                // Check for bound name conflicts: lexical declarations in head vs var declarations in body
                if (initOrLeft instanceof VariableDeclaration varDecl &&
                    (varDecl.kind().equals("let") || varDecl.kind().equals("const"))) {
                    java.util.Set<String> boundNames = new java.util.HashSet<>();
                    for (VariableDeclarator declarator : varDecl.declarations()) {
                        java.util.List<String> names = new ArrayList<>();
                        collectBindingNames(declarator.id(), names);
                        boundNames.addAll(names);
                    }
                    java.util.List<String> varNames = new ArrayList<>();
                    collectVarDeclaredNames(body, varNames);
                    for (String varName : varNames) {
                        if (boundNames.contains(varName)) {
                            throw new ExpectedTokenException("Identifier '" + varName + "' has already been declared", startToken);
                        }
                    }
                }

                Token endToken = previous();
                return new ForInStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), initOrLeft, right, body);
            } finally {
                loopDepth--;
            }
        } else if (isOfKeyword) {
            advance(); // consume 'of'
            // Validate for-of initializers - NEVER allowed (unlike for-in in sloppy mode)
            if (initOrLeft instanceof VariableDeclaration varDecl) {
                // For-of only allows a single binding
                if (varDecl.declarations().size() > 1) {
                    throw new ParseException("SyntaxError", peek(), null, "for-of statement",
                        "Only a single binding allowed in for-of loop");
                }
                for (VariableDeclarator declarator : varDecl.declarations()) {
                    if (declarator.init() != null) {
                        throw new ParseException("SyntaxError", peek(), null, "for-of statement",
                            "for-of loop variable declaration may not have an initializer");
                    }
                }
            }
            // Validate and convert left to pattern if it's an expression (for destructuring)
            if (initOrLeft instanceof Expression expr) {
                // 'let' as a bare identifier is disallowed in for-of (but allowed in for-in for compat)
                // spec: for ( [lookahead ≠ let] LeftHandSideExpression of AssignmentExpression ) Statement
                if (expr instanceof Identifier id && id.name().equals("let")) {
                    throw new ParseException("SyntaxError", peek(), null, "for-of statement",
                        "'let' is not a valid identifier in this context");
                }
                // 'async of' as the first two tokens is disallowed in for-of (but allowed in for-await-of)
                // The 'async of' sequence is ambiguous with async arrow function syntax
                // For for-await-of, 'async' IS a valid LHS identifier
                // Only applies to literal 'async of' - escaped versions and (async) are OK
                if (!isAwait && lhsIsLiteralAsyncOf) {
                    throw new ParseException("SyntaxError", peek(), null, "for-of statement",
                        "The left-hand side of a for-of loop may not be 'async'");
                }
                validateForInOfLHS(expr, peek(), "for-of");
                initOrLeft = convertToPatternIfNeeded(initOrLeft);
            }
            // For-of requires an AssignmentExpression, not Expression (comma not allowed)
            Expression right = parseExpr(BP_ASSIGNMENT);
            consume(TokenType.RPAREN, "Expected ')' after for-of");
            loopDepth++;
            try {
                Statement body = parseNestedStatement();

                // Early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
                if (isLabelledFunction(body)) {
                    throw new ExpectedTokenException("Labeled function declarations are not allowed in for-of loop body", startToken);
                }

                // Check for bound name conflicts: lexical declarations in head vs var declarations in body
                if (initOrLeft instanceof VariableDeclaration varDecl &&
                    (varDecl.kind().equals("let") || varDecl.kind().equals("const"))) {
                    java.util.Set<String> boundNames = new java.util.HashSet<>();
                    for (VariableDeclarator declarator : varDecl.declarations()) {
                        java.util.List<String> names = new ArrayList<>();
                        collectBindingNames(declarator.id(), names);
                        boundNames.addAll(names);
                    }
                    java.util.List<String> varNames = new ArrayList<>();
                    collectVarDeclaredNames(body, varNames);
                    for (String varName : varNames) {
                        if (boundNames.contains(varName)) {
                            throw new ExpectedTokenException("Identifier '" + varName + "' has already been declared", startToken);
                        }
                    }
                }

                Token endToken = previous();
                return new ForOfStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), isAwait, initOrLeft, right, body);
            } finally {
                loopDepth--;
            }
        }

        // Regular for loop
        consume(TokenType.SEMICOLON, "Expected ';' after for loop initializer");

        // Parse test clause - can be expression or empty
        Expression test = null;
        if (!check(TokenType.SEMICOLON)) {
            test = parseExpression();
        }
        consume(TokenType.SEMICOLON, "Expected ';' after for loop condition");

        // Parse update clause - can be expression or empty
        Expression update = null;
        if (!check(TokenType.RPAREN)) {
            update = parseExpression();
        }
        consume(TokenType.RPAREN, "Expected ')' after for clauses");

        loopDepth++;
        try {
            Statement body = parseNestedStatement();

            // Early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
            if (isLabelledFunction(body)) {
                throw new ExpectedTokenException("Labeled function declarations are not allowed in for loop body", startToken);
            }

            // Check for bound name conflicts: lexical declarations in head vs var declarations in body
            if (initOrLeft instanceof VariableDeclaration varDecl &&
                (varDecl.kind().equals("let") || varDecl.kind().equals("const"))) {
                // Collect bound names from the head
                java.util.Set<String> boundNames = new java.util.HashSet<>();
                for (VariableDeclarator declarator : varDecl.declarations()) {
                    java.util.List<String> names = new ArrayList<>();
                    collectBindingNames(declarator.id(), names);
                    boundNames.addAll(names);
                }
                // Collect var declared names from the body
                java.util.List<String> varNames = new ArrayList<>();
                collectVarDeclaredNames(body, varNames);
                for (String varName : varNames) {
                    if (boundNames.contains(varName)) {
                        throw new ExpectedTokenException("Identifier '" + varName + "' has already been declared", startToken);
                    }
                }
            }

            Token endToken = previous();
            return new ForStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), initOrLeft, test, update, body);
        } finally {
            loopDepth--;
        }
    }

    private IfStatement parseIfStatement() {
        Token startToken = peek();
        advance(); // consume 'if'

        consume(TokenType.LPAREN, "Expected '(' after 'if'");
        Expression test = parseExpression();
        consume(TokenType.RPAREN, "Expected ')' after if condition");

        // If statements allow function declarations in non-strict mode via Annex B
        Statement consequent = parseNestedStatementForIf();

        // Early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
        if (isLabelledFunction(consequent)) {
            throw new ExpectedTokenException("Labeled function declarations are not allowed in if statement body", startToken);
        }

        Statement alternate = null;
        if (match(TokenType.ELSE)) {
            alternate = parseNestedStatementForIf();

            // Early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
            if (isLabelledFunction(alternate)) {
                throw new ExpectedTokenException("Labeled function declarations are not allowed in if statement body", startToken);
            }
        }

        Token endToken = previous();
        return new IfStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), test, consequent, alternate);
    }

    private ReturnStatement parseReturnStatement() {
        Token startToken = peek();
        Token returnToken = startToken;

        // Return is only allowed inside functions
        if (!inFunction) {
            throw new ExpectedTokenException("'return' statement not allowed outside of a function", startToken);
        }

        advance(); // consume 'return'

        Expression argument = null;

        // [no LineTerminator here] restriction
        // If there's a line break after 'return', treat it as return with no argument
        Token nextToken = peek();
        boolean hasLineBreak = returnToken.line() < nextToken.line();

        // Check if there's an argument
        if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !hasLineBreak && !isAtEnd()) {
            argument = parseExpression();
        }

        consumeSemicolon("Expected ';' after return statement");
        Token endToken = previous();
        return new ReturnStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), argument);
    }

    private BreakStatement parseBreakStatement() {
        Token startToken = peek();
        advance(); // consume 'break'
        Token breakToken = previous(); // the 'break' token we just consumed

        // Optional label for break statement
        // No line terminator is allowed between break and its label
        Identifier label = null;
        if (check(TokenType.IDENTIFIER) && !check(TokenType.SEMICOLON) &&
            breakToken.line() == peek().line()) {
            Token labelToken = peek();
            advance();
            label = new Identifier(getStart(labelToken), getEnd(labelToken), labelToken.line(), labelToken.column(), labelToken.endLine(), labelToken.endColumn(), labelToken.lexeme());
        }

        // break without label must be inside a loop or switch
        if (label == null && loopDepth == 0 && switchDepth == 0) {
            throw new ExpectedTokenException("Illegal break statement: not inside a loop or switch", breakToken);
        }

        // break with label must reference an existing label
        if (label != null && !labelMap.containsKey(label.name())) {
            throw new ExpectedTokenException("Undefined label '" + label.name() + "'", breakToken);
        }

        consumeSemicolon("Expected ';' after break statement");
        Token endToken = previous();
        return new BreakStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), label);
    }

    private ContinueStatement parseContinueStatement() {
        Token startToken = peek();
        advance(); // consume 'continue'
        Token continueToken = previous(); // the 'continue' token we just consumed

        // continue must be inside a loop
        if (loopDepth == 0) {
            throw new ExpectedTokenException("Illegal continue statement: not inside a loop", continueToken);
        }

        // Optional label for continue statement
        // No line terminator is allowed between continue and its label
        Identifier label = null;
        if (check(TokenType.IDENTIFIER) && !check(TokenType.SEMICOLON) &&
            continueToken.line() == peek().line()) {
            Token labelToken = peek();
            advance();
            label = new Identifier(getStart(labelToken), getEnd(labelToken), labelToken.line(), labelToken.column(), labelToken.endLine(), labelToken.endColumn(), labelToken.lexeme());
        }

        // continue with label must reference an iteration statement
        if (label != null) {
            Boolean isIteration = labelMap.get(label.name());
            if (isIteration == null) {
                throw new ExpectedTokenException("Undefined label '" + label.name() + "'", continueToken);
            }
            if (!isIteration) {
                throw new ExpectedTokenException("A 'continue' statement can only jump to a label of an enclosing iteration statement", continueToken);
            }
        }

        consumeSemicolon("Expected ';' after continue statement");
        Token endToken = previous();
        return new ContinueStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), label);
    }

    private SwitchStatement parseSwitchStatement() {
        Token startToken = peek();
        advance(); // consume 'switch'

        consume(TokenType.LPAREN, "Expected '(' after 'switch'");
        Expression discriminant = parseExpression();
        consume(TokenType.RPAREN, "Expected ')' after switch discriminant");

        consume(TokenType.LBRACE, "Expected '{' before switch body");

        // Push a block scope for the switch body
        // All case clauses share the same scope (ES6 spec)
        pushScope(false);
        switchDepth++;

        List<SwitchCase> cases = new ArrayList<>();
        boolean hasDefault = false;

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token caseStart = peek();

            if (match(TokenType.CASE)) {
                // case test: consequent
                Expression test = parseExpression();
                consume(TokenType.COLON, "Expected ':' after case test");

                // Parse consequent statements until we hit another case/default or closing brace
                // Lexical declarations and function declarations are allowed since switch body is a block scope
                // Note: We pass (true, false) - allowing lexical declarations but NOT treating as AnnexB context
                // Switch cases are NOT single-statement contexts for redeclaration purposes
                List<Statement> consequent = new ArrayList<>();
                while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) && !check(TokenType.RBRACE) && !isAtEnd()) {
                    consequent.add(parseNestedStatement(true, false));
                }

                Token caseEnd = previous();
                cases.add(new SwitchCase(getStart(caseStart), getEnd(caseEnd), caseStart.line(), caseStart.column(), caseEnd.endLine(), caseEnd.endColumn(), test, consequent));

            } else if (match(TokenType.DEFAULT)) {
                // Check for duplicate default clause
                if (hasDefault) {
                    throw new ExpectedTokenException("More than one default clause in switch statement", caseStart);
                }
                hasDefault = true;

                // default: consequent
                consume(TokenType.COLON, "Expected ':' after 'default'");

                // Parse consequent statements
                // Lexical declarations and function declarations are allowed since switch body is a block scope
                // Note: We pass (true, false) - allowing lexical declarations but NOT treating as AnnexB context
                // Switch cases are NOT single-statement contexts for redeclaration purposes
                List<Statement> consequent = new ArrayList<>();
                while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) && !check(TokenType.RBRACE) && !isAtEnd()) {
                    consequent.add(parseNestedStatement(true, false));
                }

                Token caseEnd = previous();
                cases.add(new SwitchCase(getStart(caseStart), getEnd(caseEnd), caseStart.line(), caseStart.column(), caseEnd.endLine(), caseEnd.endColumn(), null, consequent));

            } else {
                throw new ExpectedTokenException("'case' or 'default' in switch body", peek());
            }
        }

        consume(TokenType.RBRACE, "Expected '}' after switch body");

        // Pop the switch body scope and restore switch depth
        switchDepth--;
        popScope();

        Token endToken = previous();
        return new SwitchStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), discriminant, cases);
    }

    private ThrowStatement parseThrowStatement() {
        Token startToken = peek();
        advance(); // consume 'throw'

        // ECMAScript spec: No line terminator is allowed between 'throw' and its expression
        Token prevToken = previous();
        Token nextToken = peek();
        if (prevToken.line() < nextToken.line()) {
            throw new ParseException("ValidationError", peek(), null, "throw statement", "Line break is not allowed between 'throw' and its expression");
        }

        // Parse the expression to throw
        Expression argument = parseExpression();
        consumeSemicolon("Expected ';' after throw statement");
        Token endToken = previous();
        return new ThrowStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), argument);
    }

    private TryStatement parseTryStatement() {
        Token startToken = peek();
        advance(); // consume 'try'

        // Parse the try block
        BlockStatement block = parseBlockStatement();

        // Parse optional catch clause
        CatchClause handler = null;
        if (check(TokenType.CATCH)) {
            Token catchStart = peek();
            advance(); // consume 'catch'

            // Parse optional catch parameter (ES2019+ allows catch without parameter)
            Pattern param = null;
            if (match(TokenType.LPAREN)) {
                Token paramStart = peek();
                param = parsePatternBase();

                // Validate catch parameter binding names
                java.util.List<String> bindingNames = new ArrayList<>();
                collectBindingNames(param, bindingNames);
                java.util.Set<String> seenNames = new java.util.HashSet<>();
                for (String name : bindingNames) {
                    // In strict mode, eval and arguments cannot be catch parameter names
                    validateBindingName(name, paramStart);
                    // Check for duplicate binding names
                    if (seenNames.contains(name)) {
                        throw new ExpectedTokenException("Duplicate binding in catch parameter", paramStart);
                    }
                    seenNames.add(name);
                }

                consume(TokenType.RPAREN, "Expected ')' after catch parameter");
            }

            // Parse catch body
            BlockStatement body = parseBlockStatement();

            // Check for conflicts between catch parameter and lexically declared names in body
            if (param != null) {
                java.util.List<String> paramNames = new ArrayList<>();
                collectBindingNames(param, paramNames);
                java.util.Set<String> paramNameSet = new java.util.HashSet<>(paramNames);

                java.util.List<String> lexicalNames = new ArrayList<>();
                // Catch blocks always treat function declarations as lexical (they're in a block, not function body)
                collectLexicallyDeclaredNames(body, lexicalNames, true);
                for (String lexName : lexicalNames) {
                    if (paramNameSet.contains(lexName)) {
                        throw new ExpectedTokenException("Identifier '" + lexName + "' has already been declared", catchStart);
                    }
                }
            }

            Token catchEnd = previous();
            handler = new CatchClause(getStart(catchStart), getEnd(catchEnd), catchStart.line(), catchStart.column(), catchEnd.endLine(), catchEnd.endColumn(), param, body);
        }

        // Parse optional finally clause
        BlockStatement finalizer = null;
        if (match(TokenType.FINALLY)) {
            finalizer = parseBlockStatement();
        }

        // Must have either catch or finally (or both)
        if (handler == null && finalizer == null) {
            throw new ParseException("ValidationError", peek(), null, "try statement", "Missing catch or finally after try");
        }

        Token endToken = previous();
        return new TryStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), block, handler, finalizer);
    }

    private WithStatement parseWithStatement() {
        Token startToken = peek();
        advance(); // consume 'with'

        // Strict mode validation: with statements are not allowed in strict mode
        if (strictMode) {
            throw new ExpectedTokenException("'with' statement is not allowed in strict mode", startToken);
        }

        consume(TokenType.LPAREN, "Expected '(' after 'with'");
        Expression object = parseExpression();
        consume(TokenType.RPAREN, "Expected ')' after with object");

        // Function declarations are not allowed in with statement body
        if (check(TokenType.FUNCTION)) {
            throw new ExpectedTokenException("Function declarations cannot appear in with statement body", peek());
        }

        Statement body = parseNestedStatement();

        // Early error: "It is a Syntax Error if IsLabelledFunction(Statement) is true."
        if (isLabelledFunction(body)) {
            throw new ExpectedTokenException("Labeled function declarations are not allowed in with statement body", startToken);
        }

        Token endToken = previous();
        return new WithStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), object, body);
    }

    private DebuggerStatement parseDebuggerStatement() {
        Token startToken = peek();
        advance(); // consume 'debugger'
        consumeSemicolon("Expected ';' after debugger statement");
        Token endToken = previous();
        return new DebuggerStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn());
    }

    private EmptyStatement parseEmptyStatement() {
        Token startToken = peek();
        advance(); // consume ';'
        return new EmptyStatement(getStart(startToken), getEnd(startToken), startToken.line(), startToken.column(), startToken.endLine(), startToken.endColumn());
    }

    private FunctionDeclaration parseFunctionDeclaration(boolean isAsync) {
        return parseFunctionDeclaration(isAsync, false);
    }

    private FunctionDeclaration parseFunctionDeclaration(boolean isAsync, boolean allowAnonymous) {
        Token startToken = peek();

        if (isAsync) {
            advance(); // consume 'async'
        }

        advance(); // consume 'function'

        // Check for generator
        boolean isGenerator = match(TokenType.STAR);

        // Parse function name (allow yield, of, let as function names in appropriate contexts)
        Identifier id = null;
        Token nameToken = null;
        if (check(TokenType.IDENTIFIER) || check(TokenType.OF) || check(TokenType.LET)) {
            nameToken = peek();
            advance();
            // In strict mode, 'eval' and 'arguments' cannot be function names
            String functionName = nameToken.lexeme();
            if (strictMode && (functionName.equals("eval") || functionName.equals("arguments"))) {
                throw new ExpectedTokenException("'" + functionName + "' is not allowed as a function name in strict mode", nameToken);
            }
            // 'await' cannot be a function name inside static blocks (unless inside a nested function)
            if (inStaticBlock && !inFunction && functionName.equals("await")) {
                throw new ExpectedTokenException("'await' is not allowed as a function name in class static block", nameToken);
            }
            // 'await' cannot be a function name inside async functions (including nested regular functions)
            if (inAsyncContext && functionName.equals("await")) {
                throw new ExpectedTokenException("'await' is not allowed as a function name in async function", nameToken);
            }
            // In module code, 'await' is always reserved
            if (forceModuleMode && functionName.equals("await")) {
                throw new ExpectedTokenException("'await' is a reserved identifier in module code", nameToken);
            }
            // 'yield' cannot be a function name inside generators
            if (inGenerator && functionName.equals("yield")) {
                throw new ExpectedTokenException("'yield' is not allowed as a function name in generator", nameToken);
            }
            id = new Identifier(getStart(nameToken), getEnd(nameToken), nameToken.line(), nameToken.column(), nameToken.endLine(), nameToken.endColumn(), functionName);
        } else if (!allowAnonymous) {
            throw new ExpectedTokenException("function name", peek());
        }

        // Register function declaration for redeclaration checking
        // Block-scoped functions are lexically scoped in ES6+
        // In module mode, top-level functions are also lexically scoped
        if (id != null) {
            Scope scope = currentScope();
            if (scope != null) {
                boolean isModuleTopLevel = forceModuleMode && scope.isFunctionScope && scopeStack.size() == 1;
                if (!scope.isFunctionScope || isModuleTopLevel) {
                    // We're in a block scope or at module top-level - function is lexically scoped
                    // A "plain function" is one that is neither a generator nor async
                    // Per AnnexB, duplicate plain function declarations are allowed in sloppy mode
                    boolean isPlainFunction = !isGenerator && !isAsync;
                    declareFunctionInBlock(id.name(), nameToken, isPlainFunction);
                }
            }
        }

        // Set generator/async context before parsing parameters
        // (parameters can have default values that need correct context)
        boolean savedInGenerator = inGenerator;
        boolean savedInAsyncContext = inAsyncContext;
        boolean savedStrictMode = strictMode;
        boolean savedInClassFieldInitializer = inClassFieldInitializer;
        boolean savedInFunction = inFunction;
        boolean savedAllowNewTarget = allowNewTarget;
        boolean savedAllowSuperProperty = allowSuperProperty;
        boolean savedAllowSuperCall = allowSuperCall;
        boolean savedAtModuleTopLevel = atModuleTopLevel;
        int savedLoopDepth = loopDepth;
        int savedSwitchDepth = switchDepth;
        java.util.Map<String, Boolean> savedLabelMap = new java.util.HashMap<>(labelMap);
        inGenerator = isGenerator;
        inAsyncContext = isAsync;
        inClassFieldInitializer = false; // Function bodies are never class field initializers
        inFunction = true;
        allowNewTarget = true; // Regular functions allow new.target
        allowSuperProperty = false; // Regular functions don't allow super
        allowSuperCall = false;
        atModuleTopLevel = false; // Functions don't inherit top-level await
        loopDepth = 0; // break/continue don't cross function boundaries
        switchDepth = 0;
        labelMap.clear(); // Labels don't cross function boundaries

        // Push a function scope before parsing parameters
        pushScope(true);

        // Parse parameters
        consume(TokenType.LPAREN, "Expected '(' after function name");
        List<Pattern> params = new ArrayList<>();

        // Yield/await expressions are not allowed in parameter defaults
        boolean savedInFormalParameters = inFormalParameters;
        inFormalParameters = true;

        if (!check(TokenType.RPAREN)) {
            do {
                // Check for trailing comma: function foo(a, b,) {}
                if (check(TokenType.RPAREN)) {
                    break;
                }
                // Check for rest parameter: ...param
                if (match(TokenType.DOT_DOT_DOT)) {
                    Token restStart = previous();
                    Pattern argument = parsePatternBase();
                    Token restEnd = previous();
                    params.add(new RestElement(getStart(restStart), getEnd(restEnd), restStart.line(), restStart.column(), restEnd.endLine(), restEnd.endColumn(), argument));
                    // Rest parameter must be last
                    if (match(TokenType.COMMA)) {
                        throw new ParseException("ValidationError", peek(), null, "parameter list", "Rest parameter must be last");
                    }
                    break;
                } else {
                    params.add(parsePattern());
                }
            } while (match(TokenType.COMMA));
        }

        inFormalParameters = savedInFormalParameters;
        consume(TokenType.RPAREN, "Expected ')' after parameters");

        // Parse body (context already set above)
        // Strict mode is inherited from outer scope, and function body can also set it
        // via "use strict" directive (detected immediately in parseBlockStatement)
        BlockStatement body = parseBlockStatement(true); // Function body

        // Validate that "use strict" is not used with non-simple parameters
        validateStrictBodyWithSimpleParams(params, body, startToken);

        // Check for parameter/body conflicts (param name vs lexical declaration in body)
        validateNoParamBodyConflicts(params, body, startToken);

        // If the body made this strict mode, validate that function name is not eval/arguments
        // This handles: function eval() { 'use strict'; }
        if (strictMode && nameToken != null) {
            String functionName = nameToken.lexeme();
            if (functionName.equals("eval") || functionName.equals("arguments")) {
                throw new ExpectedTokenException("'" + functionName + "' is not allowed as a function name in strict mode", nameToken);
            }
        }

        // Check for duplicate parameters if in strict mode
        // This must be done AFTER parsing the body (which might contain "use strict")
        // but BEFORE restoring the saved strict mode
        validateNoDuplicateParameters(params, startToken);

        // Pop the function scope
        popScope();

        // Restore context
        inGenerator = savedInGenerator;
        inAsyncContext = savedInAsyncContext;
        strictMode = savedStrictMode;
        inClassFieldInitializer = savedInClassFieldInitializer;
        inFunction = savedInFunction;
        allowNewTarget = savedAllowNewTarget;
        allowSuperProperty = savedAllowSuperProperty;
        allowSuperCall = savedAllowSuperCall;
        atModuleTopLevel = savedAtModuleTopLevel;
        loopDepth = savedLoopDepth;
        switchDepth = savedSwitchDepth;
        labelMap.clear();
        labelMap.putAll(savedLabelMap);

        Token endToken = previous();
        return new FunctionDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), id, false, isGenerator, isAsync, params, body);
    }

    private ClassDeclaration parseClassDeclaration() {
        return parseClassDeclaration(false);
    }

    private ClassDeclaration parseClassDeclaration(boolean allowAnonymous) {
        Token startToken = peek();
        advance(); // consume 'class'

        // Parse class name (but not if it's 'extends' which starts the extends clause)
        Identifier id = null;
        Token nameToken = null;
        if (check(TokenType.IDENTIFIER) && !peek().lexeme().equals("extends")) {
            nameToken = peek();
            advance();
            String className = nameToken.lexeme();
            // Class names cannot be strict mode reserved words (class bodies are always strict)
            validateClassName(className, nameToken);
            id = new Identifier(getStart(nameToken), getEnd(nameToken), nameToken.line(), nameToken.column(), nameToken.endLine(), nameToken.endColumn(), className);
        } else if (!allowAnonymous && !(check(TokenType.IDENTIFIER) && peek().lexeme().equals("extends"))) {
            throw new ExpectedTokenException("class name", peek());
        }

        // Register class declaration for redeclaration checking
        // Class declarations are always lexically scoped (like let/const)
        if (id != null) {
            declareLexicalName(id.name(), nameToken);
        }

        // Class declarations are always in strict mode (including the heritage expression)
        boolean savedStrictMode = strictMode;
        strictMode = true;

        // Check for extends
        Expression superClass = null;
        boolean savedInDerivedClass = inDerivedClass;
        int pendingRefsBeforeHeritage = pendingPrivateRefs.size();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("extends")) {
            Token extendsToken = peek();
            advance(); // consume 'extends'
            // Capture start position before parsing to detect if arrow is wrapped in parens
            int heritageStart = getStart(peek());
            superClass = parseExpr(BP_TERNARY + 1); // Parse the superclass expression (can be any expression except assignment or ternary)

            // ClassHeritage must be a LeftHandSideExpression - bare arrow functions are not allowed
            // BUT arrow functions wrapped in parens like (async () => {}) ARE valid
            // (they'll throw TypeError at runtime, but it's not a syntax error)
            if (superClass instanceof ArrowFunctionExpression && superClass.start() == heritageStart) {
                throw new ExpectedTokenException("Class heritage cannot be an arrow function", extendsToken);
            }

            inDerivedClass = true;

            // Private name references in the heritage must be valid in the OUTER scope
            // (the class's own private names are not yet visible)
            for (int i = pendingRefsBeforeHeritage; i < pendingPrivateRefs.size(); i++) {
                var entry = pendingPrivateRefs.get(i);
                String name = entry.getKey();
                Token token = entry.getValue();
                if (!isPrivateNameDeclared(name)) {
                    throw new ExpectedTokenException("Private field '#" + name + "' must be declared in an enclosing class", token);
                }
            }
            // Remove validated heritage refs from pending list (in reverse order to maintain indices)
            for (int i = pendingPrivateRefs.size() - 1; i >= pendingRefsBeforeHeritage; i--) {
                pendingPrivateRefs.remove(i);
            }
        } else {
            inDerivedClass = false;
        }

        // Parse class body
        ClassBody body = parseClassBody();
        inDerivedClass = savedInDerivedClass;
        strictMode = savedStrictMode;

        Token endToken = previous();
        return new ClassDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), id, superClass, body);
    }

    private ClassBody parseClassBody() {
        Token startToken = peek();
        consume(TokenType.LBRACE, "Expected '{' before class body");

        // Class bodies are always in strict mode (ES6 spec)
        boolean savedStrictModeClass = strictMode;
        strictMode = true;

        // Push private name scope for this class (for AllPrivateNamesValid validation)
        pushPrivateNameScope();

        // Record pending refs count before parsing body - only refs added during this body
        // should be validated against this class's scope (refs from outer classes should stay pending)
        int pendingRefsBeforeBody = pendingPrivateRefs.size();

        // Track private names for duplicate detection
        // Maps name to kind: "field", "method", "getter", "setter"
        // Getter and setter with same name are allowed, other combinations are not
        java.util.Map<String, String> privateNames = new java.util.HashMap<>();

        // Track if we've seen a constructor (only one allowed per class)
        boolean hasConstructor = false;

        List<Node> bodyElements = new ArrayList<>();

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            // Skip empty statements (semicolons) in class body
            if (match(TokenType.SEMICOLON)) {
                continue;
            }

            // Track the start of the member (before any modifiers)
            Token memberStart = peek();

            boolean isStatic = false;

            // Check for 'static' keyword (but not if it's a method named "static" or field named "static")
            // 'static' must not contain Unicode escapes to be treated as a keyword
            if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("static") && !tokenContainsEscapes(peek())) {
                // Look ahead to see if this is "static()" (method name), "static;" or "static =" (field name),
                // or "static something" (modifier)
                TokenType nextType = current + 1 < tokens.size() ? tokens.get(current + 1).type() : null;
                // static is a modifier unless followed by ( ; = or nothing
                if (nextType != null && nextType != TokenType.LPAREN &&
                    nextType != TokenType.SEMICOLON && nextType != TokenType.ASSIGN) {
                    advance();
                    isStatic = true;

                    // Check for static initialization block: static { ... }
                    if (check(TokenType.LBRACE)) {
                        Token blockStart = memberStart;
                        Token braceStart = peek();
                        advance(); // consume '{'

                        // Static blocks allow super.property and new.target (like functions)
                        // Static blocks also reserve 'await' as a keyword (like modules)
                        // Static blocks do NOT inherit yield from enclosing generators
                        // Static blocks do NOT allow return statements (unlike functions)
                        // Static blocks do NOT allow await expressions (just reserve the keyword)
                        boolean savedInFunction = inFunction;
                        boolean savedAllowNewTarget = allowNewTarget;
                        boolean savedAllowSuperProperty = allowSuperProperty;
                        boolean savedInAsyncContext = inAsyncContext;
                        boolean savedInGenerator = inGenerator;
                        boolean savedInStaticBlock = inStaticBlock;
                        int savedLoopDepth = loopDepth;
                        int savedSwitchDepth = switchDepth;
                        java.util.Map<String, Boolean> savedLabelMap = new java.util.HashMap<>(labelMap);
                        inFunction = false;      // Static blocks do NOT allow return
                        allowNewTarget = true;   // Static blocks allow new.target
                        allowSuperProperty = true;
                        inAsyncContext = false;  // 'await' is reserved but expressions are NOT allowed
                        inGenerator = false;     // 'yield' is not allowed in static blocks
                        inStaticBlock = true;    // Track that we're in a static block
                        loopDepth = 0;           // Break/continue can't cross static block boundaries
                        switchDepth = 0;
                        labelMap.clear();        // Labels don't cross static block boundaries

                        // Push a function scope for the static block
                        // Static blocks have their own lexical scope
                        pushScope(true);

                        // Lexical declarations and function declarations are allowed in static blocks
                        List<Statement> blockBody = new ArrayList<>();
                        while (!check(TokenType.RBRACE) && !isAtEnd()) {
                            blockBody.add(parseNestedStatement(true, true));
                        }

                        // Pop the static block scope
                        popScope();

                        inFunction = savedInFunction;
                        allowNewTarget = savedAllowNewTarget;
                        allowSuperProperty = savedAllowSuperProperty;
                        inAsyncContext = savedInAsyncContext;
                        inGenerator = savedInGenerator;
                        inStaticBlock = savedInStaticBlock;
                        loopDepth = savedLoopDepth;
                        switchDepth = savedSwitchDepth;
                        labelMap.clear();
                        labelMap.putAll(savedLabelMap);

                        Token blockEnd = peek();
                        consume(TokenType.RBRACE, "Expected '}' after static block body");

                        bodyElements.add(new StaticBlock(getStart(blockStart), getEnd(blockEnd), blockStart.line(), blockStart.column(), blockEnd.endLine(), blockEnd.endColumn(), blockBody));
                        continue;
                    }
                }
            }

            // Check for 'async' keyword (but not if it's a method named "async" or field named "async")
            // 'async' must not contain Unicode escapes to be treated as a keyword
            boolean isAsync = false;
            if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("async")) {
                Token asyncToken = peek();
                boolean hasEscapes = tokenContainsEscapes(asyncToken);

                // Look ahead to see if this is "async()" (method name), "async;" or "async =" (field),
                // or "async something" (modifier)
                if (current + 1 < tokens.size()) {
                    Token nextToken = tokens.get(current + 1);
                    TokenType nextType = nextToken.type();

                    // async is NOT a modifier if followed by ( ; = or if there's a line break (ASI)
                    if (nextType != TokenType.LPAREN && nextType != TokenType.SEMICOLON &&
                        nextType != TokenType.ASSIGN) {
                        // Check for ASI: if there's a line break after "async", it's a field, not a modifier
                        boolean hasLineBreak = nextToken.line() > asyncToken.line();
                        if (!hasLineBreak) {
                            // If async contains escapes but is followed by * (generator marker) or identifier,
                            // it's trying to use escaped async as a keyword - reject it
                            if (hasEscapes && (nextType == TokenType.STAR || nextType == TokenType.IDENTIFIER)) {
                                throw new ExpectedTokenException("'async' keyword must not contain Unicode escape sequences", asyncToken);
                            }
                            if (!hasEscapes) {
                                advance();
                                isAsync = true;
                            }
                        }
                    }
                }
            }

            // Check for getter/setter
            // 'get' and 'set' must not contain Unicode escapes to be treated as keywords
            String kind = "method";
            if (check(TokenType.IDENTIFIER) && (peek().lexeme().equals("get") || peek().lexeme().equals("set")) && !tokenContainsEscapes(peek())) {
                // Look ahead to see if this is "get()" / "set()" (method names) or "get something" / "set something" (accessor)
                if (current + 1 < tokens.size()) {
                    Token currentToken = peek();
                    Token nextToken = tokens.get(current + 1);
                    TokenType nextType = nextToken.type();

                    // If next token is LPAREN, this is a method named "get" or "set"
                    // If next token is ASSIGN or SEMICOLON, this is a field named "get" or "set"
                    // Otherwise, check for ASI and potentially treat as accessor
                    if (nextType != TokenType.LPAREN && nextType != TokenType.ASSIGN && nextType != TokenType.SEMICOLON) {
                        // Check for ASI: if there's a line break between "get"/"set" and the next token,
                        // it's a field, not an accessor
                        boolean hasLineBreak = nextToken.line() > currentToken.line();

                        if (!hasLineBreak) {
                            kind = peek().lexeme(); // "get" or "set"
                            advance(); // consume get/set keyword
                        }
                    }
                }
            }

            // Check for generator method (*)
            boolean isGenerator = false;
            if (match(TokenType.STAR)) {
                isGenerator = true;
            }

            // Check for private field/method
            boolean isPrivate = false;
            Token hashToken = null;

            if (match(TokenType.HASH)) {
                isPrivate = true;
                hashToken = previous();
                // No whitespace allowed between # and identifier
                Token nextToken = peek();
                if (hashToken.endPosition() != nextToken.position()) {
                    throw new ExpectedTokenException("identifier immediately after '#' (no whitespace allowed)", nextToken);
                }
            }

            // Parse property key
            Token keyToken = peek();
            Expression key;
            boolean computed = false;

            if (isPrivate) {
                // Private identifier - allow keywords as private names
                if (!check(TokenType.IDENTIFIER) && !isKeyword(keyToken)) {
                    throw new ExpectedTokenException("identifier after '#'", peek());
                }
                // Private class elements cannot be named "#constructor"
                if (keyToken.lexeme().equals("constructor")) {
                    throw new ExpectedTokenException("Classes may not have a private element named '#constructor'", keyToken);
                }
                advance();
                // PrivateIdentifier starts at #, but method/property start is memberStart (may include static)
                key = new PrivateIdentifier(getStart(hashToken), getEnd(keyToken), hashToken.line(), hashToken.column(), keyToken.endLine(), keyToken.endColumn(), keyToken.lexeme());
            } else if (match(TokenType.LBRACKET)) {
                // Computed property name - allow 'in' operator inside
                computed = true;
                boolean savedAllowIn = allowIn;
                allowIn = true;
                key = parseExpression();
                allowIn = savedAllowIn;
                consume(TokenType.RBRACKET, "Expected ']' after computed property name");
            } else if (check(TokenType.STRING) || check(TokenType.NUMBER)) {
                // Literal property name (string or number)
                advance();
                String keyLexeme = keyToken.lexeme();

                // Check if this is a BigInt literal (ends with 'n')
                if (keyLexeme.endsWith("n")) {
                    // BigInt literal: value is null, bigint field has the numeric part
                    String bigintValue = keyLexeme.substring(0, keyLexeme.length() - 1).replace("_", "");

                    // Convert hex/octal/binary to decimal for the bigint field
                    if (bigintValue.startsWith("0x") || bigintValue.startsWith("0X")) {
                        try {
                            java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 16);
                            bigintValue = bi.toString();
                        } catch (NumberFormatException e) {
                            // Keep original if conversion fails
                        }
                    } else if (bigintValue.startsWith("0o") || bigintValue.startsWith("0O")) {
                        try {
                            java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 8);
                            bigintValue = bi.toString();
                        } catch (NumberFormatException e) {
                            // Keep original if conversion fails
                        }
                    } else if (bigintValue.startsWith("0b") || bigintValue.startsWith("0B")) {
                        try {
                            java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 2);
                            bigintValue = bi.toString();
                        } catch (NumberFormatException e) {
                            // Keep original if conversion fails
                        }
                    }
                    // For BigInt literals, Acorn sets value to null
                    key = new Literal(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), null, keyLexeme, null, bigintValue);
                } else {
                    Object literalValue = keyToken.literal();
                    if (literalValue instanceof Double d && (d.isInfinite() || d.isNaN())) {
                        literalValue = null;
                    }
                    key = new Literal(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), literalValue, keyLexeme);
                }
            } else if (check(TokenType.DOT) && current + 1 < tokens.size() && tokens.get(current + 1).type() == TokenType.NUMBER) {
                // Handle .1 as a numeric literal (0.1)
                Token dotToken = peek();
                advance(); // consume DOT
                Token numToken = peek();
                advance(); // consume NUMBER
                String lexeme = "." + numToken.lexeme();
                double value = Double.parseDouble(lexeme);
                key = new Literal(getStart(dotToken), getEnd(numToken), dotToken.line(), dotToken.column(), numToken.endLine(), numToken.endColumn(), value, lexeme);
            } else if (check(TokenType.IDENTIFIER) || check(TokenType.GET) || check(TokenType.SET) ||
                       check(TokenType.TRUE) || check(TokenType.FALSE) || check(TokenType.NULL) ||
                       isKeyword(peek())) {
                // Regular identifier, get/set, keyword, or boolean/null literal as property name
                advance();
                key = new Identifier(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), keyToken.lexeme());
            } else {
                throw new ExpectedTokenException("property name in class body", peek());
            }

            // Check if it's a method or a field
            if (match(TokenType.LPAREN)) {
                // It's a method - parse as function expression
                Token fnStart = previous(); // The opening paren

                // Determine if this is a constructor BEFORE parsing body (for super() validation)
                boolean isConstructorMethod = !isStatic && !computed &&
                    ((key instanceof Identifier id && id.name().equals("constructor")) ||
                     (key instanceof Literal lit && "constructor".equals(lit.value())));

                // Set generator/async context BEFORE parsing parameters
                // Default parameter initializers use the method's context, not the outer
                boolean savedInGenerator = inGenerator;
                boolean savedInAsyncContext = inAsyncContext;
                boolean savedStrictMode = strictMode;
                boolean savedInFunction = inFunction;
                boolean savedAllowNewTarget = allowNewTarget;
                boolean savedAllowSuperProperty = allowSuperProperty;
                boolean savedAllowSuperCall = allowSuperCall;
                boolean savedAtModuleTopLevel = atModuleTopLevel;
                inGenerator = isGenerator;
                inAsyncContext = isAsync;
                inFunction = true;
                allowNewTarget = true; // Class methods allow new.target
                allowSuperProperty = true; // Class methods allow super.property
                allowSuperCall = isConstructorMethod; // Only constructors allow super()
                atModuleTopLevel = false; // Methods don't inherit top-level await

                // Push a function scope for the class method
                pushScope(true);

                List<Pattern> params = new ArrayList<>();

                // Yield/await expressions are not allowed in parameter defaults
                boolean savedInFormalParameters = inFormalParameters;
                inFormalParameters = true;

                while (!check(TokenType.RPAREN)) {
                    if (match(TokenType.DOT_DOT_DOT)) {
                        Token restStart = previous();
                        Pattern argument = parsePatternBase();
                        Token restEnd = previous();
                        params.add(new RestElement(getStart(restStart), getEnd(restEnd), restStart.line(), restStart.column(), restEnd.endLine(), restEnd.endColumn(), argument));
                        if (match(TokenType.COMMA)) {
                            throw new ParseException("ValidationError", peek(), null, "parameter list", "Rest parameter must be last");
                        }
                        break;
                    } else {
                        params.add(parsePattern());
                    }

                    if (!match(TokenType.COMMA)) {
                        break;
                    }
                }

                inFormalParameters = savedInFormalParameters;
                consume(TokenType.RPAREN, "Expected ')' after parameters");

                // Validate getter/setter parameter counts
                if (kind.equals("get") && !params.isEmpty()) {
                    throw new ExpectedTokenException("getter must have zero parameters", memberStart);
                }
                if (kind.equals("set") && params.size() != 1) {
                    throw new ExpectedTokenException("setter must have exactly one parameter", memberStart);
                }

                // Strict mode is inherited from outer scope, and method body can also set it
                // via "use strict" directive (detected immediately in parseBlockStatement)
                BlockStatement body = parseBlockStatement(true); // Method body

                // Validate that "use strict" is not used with non-simple parameters
                validateStrictBodyWithSimpleParams(params, body, memberStart);

                // Check for duplicate parameters - class methods always use UniqueFormalParameters
                validateNoDuplicateParameters(params, memberStart, true);

                // Check for parameter/body conflicts (param name vs lexical declaration in body)
                validateNoParamBodyConflicts(params, body, memberStart);

                // Pop the function scope
                popScope();

                inGenerator = savedInGenerator;
                inAsyncContext = savedInAsyncContext;
                strictMode = savedStrictMode;
                inFunction = savedInFunction;
                allowNewTarget = savedAllowNewTarget;
                allowSuperProperty = savedAllowSuperProperty;
                allowSuperCall = savedAllowSuperCall;
                atModuleTopLevel = savedAtModuleTopLevel;

                Token methodEnd = previous();
                FunctionExpression fnExpr = new FunctionExpression(
                    getStart(fnStart),
                    getEnd(methodEnd),
                    fnStart.line(), fnStart.column(), methodEnd.endLine(), methodEnd.endColumn(),
                    null,  // No id for method
                    false, // expression (methods are not expression context)
                    isGenerator, // generator
                    isAsync, // async
                    params,
                    body
                );

                // Determine method kind (or use the one already set for get/set)
                // Only non-static, non-computed methods can be constructors
                if (kind.equals("method") && !isStatic && !computed) {
                    if (key instanceof Identifier id && id.name().equals("constructor")) {
                        kind = "constructor";
                    } else if (key instanceof Literal lit && "constructor".equals(lit.value())) {
                        kind = "constructor";
                    }
                }

                // Check for duplicate constructors
                if (kind.equals("constructor")) {
                    if (hasConstructor) {
                        throw new ExpectedTokenException("A class may only have one constructor", memberStart);
                    }
                    hasConstructor = true;
                }

                // Special methods (get/set/generator/async) cannot be named "constructor"
                boolean isSpecialMethod = kind.equals("get") || kind.equals("set") || isGenerator || isAsync;
                if (isSpecialMethod && !isStatic && !computed && !isPrivate) {
                    String keyName = null;
                    if (key instanceof Identifier id) {
                        keyName = id.name();
                    } else if (key instanceof Literal lit && lit.value() instanceof String) {
                        keyName = (String) lit.value();
                    }
                    if ("constructor".equals(keyName)) {
                        throw new ExpectedTokenException("Class constructor may not be " +
                            (kind.equals("get") ? "a getter" : kind.equals("set") ? "a setter" :
                             isGenerator ? "a generator" : "an async method"), memberStart);
                    }
                }

                // Static methods cannot be named "prototype"
                if (isStatic && !computed && !isPrivate) {
                    String keyName = null;
                    if (key instanceof Identifier id) {
                        keyName = id.name();
                    } else if (key instanceof Literal lit && lit.value() instanceof String) {
                        keyName = (String) lit.value();
                    }
                    if ("prototype".equals(keyName)) {
                        throw new ExpectedTokenException("Classes may not have a static method named 'prototype'", memberStart);
                    }
                }

                MethodDefinition method = new MethodDefinition(
                    getStart(memberStart),
                    getEnd(methodEnd),
                    memberStart.line(), memberStart.column(), methodEnd.endLine(), methodEnd.endColumn(),
                    key,
                    fnExpr,
                    kind,
                    computed,
                    isStatic
                );

                // Check for duplicate private names
                if (key instanceof PrivateIdentifier pid) {
                    String name = pid.name();
                    String existingKind = privateNames.get(name);
                    if (existingKind != null) {
                        // Allow getter + setter combination only if they have the same staticness
                        boolean existingIsStatic = existingKind.startsWith("static-");
                        String existingType = existingIsStatic ? existingKind.substring(7) : existingKind;
                        boolean isValidCombination =
                            (existingType.equals("getter") && kind.equals("set") && existingIsStatic == isStatic) ||
                            (existingType.equals("setter") && kind.equals("get") && existingIsStatic == isStatic);
                        if (!isValidCombination) {
                            throw new ExpectedTokenException("Duplicate private name #" + name, memberStart);
                        }
                    }
                    String kindValue = kind.equals("get") ? "getter" : kind.equals("set") ? "setter" : "method";
                    privateNames.put(name, isStatic ? "static-" + kindValue : kindValue);
                    declarePrivateName(name); // Register for AllPrivateNamesValid
                }

                bodyElements.add(method);

                // Consume optional semicolon after method
                match(TokenType.SEMICOLON);
            } else {
                // It's a property field
                Expression value = null;
                if (match(TokenType.ASSIGN)) {
                    // Class field initializers are not in async context but allow super.property
                    boolean oldInClassFieldInitializer = inClassFieldInitializer;
                    boolean oldInAsyncContext = inAsyncContext;
                    boolean oldAllowSuperProperty = allowSuperProperty;
                    inClassFieldInitializer = true;
                    inAsyncContext = false;  // Reset async context for class field initializers
                    allowSuperProperty = true;  // Arrow functions in class fields can use super
                    value = parseExpr(BP_ASSIGNMENT);
                    inClassFieldInitializer = oldInClassFieldInitializer;
                    inAsyncContext = oldInAsyncContext;
                    allowSuperProperty = oldAllowSuperProperty;
                }

                // Class fields require semicolon or ASI (newline)
                // Per the grammar: FieldDefinition ;
                Token nextToken = peek();
                if (!match(TokenType.SEMICOLON)) {
                    // No explicit semicolon - check for ASI conditions
                    if (!check(TokenType.RBRACE)) {
                        // Not at end of class body - check if there's a line break (ASI opportunity)
                        Token prevToken = previous();
                        if (nextToken.line() == prevToken.line()) {
                            // Same line, no semicolon - error (no ASI possible)
                            throw new ExpectedTokenException("Expected ';' after class field", nextToken);
                        }
                    }
                }

                // Validate field name restrictions
                // Non-static fields cannot be named "constructor"
                // Static fields cannot be named "constructor" or "prototype"
                if (!computed) {
                    String fieldName = null;
                    if (key instanceof Identifier id) {
                        fieldName = id.name();
                    } else if (key instanceof Literal lit && lit.value() instanceof String) {
                        fieldName = (String) lit.value();
                    }
                    if (fieldName != null) {
                        if (fieldName.equals("constructor")) {
                            throw new ExpectedTokenException("Classes may not have a field named 'constructor'", memberStart);
                        }
                        if (isStatic && fieldName.equals("prototype")) {
                            throw new ExpectedTokenException("Classes may not have a static field named 'prototype'", memberStart);
                        }
                    }
                }

                Token propertyEnd = previous();
                PropertyDefinition property = new PropertyDefinition(
                    getStart(memberStart),
                    getEnd(propertyEnd),
                    memberStart.line(), memberStart.column(), propertyEnd.endLine(), propertyEnd.endColumn(),
                    key,
                    value,
                    computed,
                    isStatic
                );

                // Check for duplicate private names
                if (key instanceof PrivateIdentifier pid) {
                    String name = pid.name();
                    String existingKind = privateNames.get(name);
                    if (existingKind != null) {
                        throw new ExpectedTokenException("Duplicate private name #" + name, memberStart);
                    }
                    privateNames.put(name, "field");
                    declarePrivateName(name); // Register for AllPrivateNamesValid
                }

                bodyElements.add(property);
            }
        }

        consume(TokenType.RBRACE, "Expected '}' after class body");
        Token endToken = previous();

        // Validate private name references that were added during this class body
        // Only refs added after pendingRefsBeforeBody should be checked against this class's scope
        // Refs from outer classes should stay in the pending list
        for (int i = pendingRefsBeforeBody; i < pendingPrivateRefs.size(); i++) {
            var entry = pendingPrivateRefs.get(i);
            String name = entry.getKey();
            Token token = entry.getValue();
            if (!isPrivateNameDeclared(name)) {
                throw new ExpectedTokenException("Private field '#" + name + "' must be declared in an enclosing class", token);
            }
        }
        // Remove validated refs from the pending list (in reverse order to maintain indices)
        for (int i = pendingPrivateRefs.size() - 1; i >= pendingRefsBeforeBody; i--) {
            pendingPrivateRefs.remove(i);
        }

        // Pop private name scope
        popPrivateNameScope();

        // Restore strict mode
        strictMode = savedStrictModeClass;

        return new ClassBody(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), bodyElements);
    }

    private ImportDeclaration parseImportDeclaration() {
        // Import declarations only allowed in module mode (match Acorn behavior)
        if (!forceModuleMode) {
            throw new ParseException("SyntaxError", peek(), null, null,
                "'import' and 'export' may appear only with 'sourceType: module'");
        }

        // Import declarations only allowed at the top level of a module
        // Not inside functions, blocks, or other nested contexts
        // Check if we're at the module top level (scope depth == 1)
        if (inFunction || scopeStack.size() > 1) {
            throw new ExpectedTokenException("'import' is only valid at the top level of a module", peek());
        }

        Token startToken = peek();
        advance(); // consume 'import'

        List<Node> specifiers = new ArrayList<>();

        // Check for import 'module' (side-effect import)
        if (check(TokenType.STRING)) {
            Token sourceToken = advance();
            Literal source = new Literal(getStart(sourceToken), getEnd(sourceToken), sourceToken.line(), sourceToken.column(), sourceToken.endLine(), sourceToken.endColumn(), sourceToken.literal(), sourceToken.lexeme());
            List<ImportAttribute> attributes = parseImportAttributes();
            consumeSemicolon("Expected ';' after import");
            Token endToken = previous();
            return new ImportDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), specifiers, source, attributes);
        }

        // Parse import specifiers
        // import defaultName from 'module'
        // import * as name from 'module'
        // import { name1, name2 } from 'module'
        // import defaultName, { name1 } from 'module'
        // import defaultName, * as name from 'module'

        // Check for default import
        // Note: 'from' can be used as an identifier: import from from 'module'
        // We distinguish by checking if the next token after the identifier is 'from' (keyword) or comma
        if (check(TokenType.IDENTIFIER)) {
            Token localToken = peek();
            // Check if this is actually a default import binding or the 'from' keyword
            // If it's 'from' and the next token is STRING, then this 'from' is the keyword, not a binding
            if (localToken.lexeme().equals("from") && checkAhead(1, TokenType.STRING)) {
                // This is the 'from' keyword, not a binding - don't consume it
            } else {
                // This is a binding name (could be 'from' if followed by 'from' keyword)
                advance();
                validateBindingName(localToken.lexeme(), localToken);
                Identifier local = new Identifier(getStart(localToken), getEnd(localToken), localToken.line(), localToken.column(), localToken.endLine(), localToken.endColumn(), localToken.lexeme());
                specifiers.add(new ImportDefaultSpecifier(getStart(localToken), getEnd(localToken), localToken.line(), localToken.column(), localToken.endLine(), localToken.endColumn(), local));

                // Check for comma (means there are more specifiers)
                if (match(TokenType.COMMA)) {
                    // Continue parsing
                }
            }
        }

        // Check for namespace import: * as name
        if (match(TokenType.STAR)) {
            Token starToken = previous();
            consume(TokenType.IDENTIFIER, "Expected 'as' after '*'");
            Token asToken = previous();
            if (!asToken.lexeme().equals("as")) {
                throw new ExpectedTokenException("'as' after '*'", peek());
            }
            // 'as' keyword must not contain Unicode escape sequences
            if (tokenContainsEscapes(asToken)) {
                throw new ExpectedTokenException("'as' keyword must not contain Unicode escape sequences", asToken);
            }
            Token localToken = peek();
            if (!check(TokenType.IDENTIFIER)) {
                throw new ExpectedTokenException("identifier after 'as'", peek());
            }
            advance();
            validateBindingName(localToken.lexeme(), localToken);
            Identifier local = new Identifier(getStart(localToken), getEnd(localToken), localToken.line(), localToken.column(), localToken.endLine(), localToken.endColumn(), localToken.lexeme());
            specifiers.add(new ImportNamespaceSpecifier(getStart(starToken), getEnd(localToken), starToken.line(), starToken.column(), localToken.endLine(), localToken.endColumn(), local));
        } else if (match(TokenType.LBRACE)) {
            // Named imports: { name1, name2 as alias }
            // Handle empty specifiers: { }
            if (!check(TokenType.RBRACE)) {
                do {
                    Token importedToken = peek();
                    Node imported;
                    Identifier local;
                    boolean isStringImport = check(TokenType.STRING);

                    if (isStringImport) {
                        advance();
                        // Check for unpaired surrogates in string module export name
                        String strValue = importedToken.literal() != null ? importedToken.literal().toString() : "";
                        if (!isStringWellFormedUnicode(strValue)) {
                            throw new ParseException("SyntaxError", importedToken, null, null,
                                "Module export name must not contain unpaired surrogates");
                        }
                        imported = new Literal(getStart(importedToken), getEnd(importedToken), importedToken.line(), importedToken.column(), importedToken.endLine(), importedToken.endColumn(), importedToken.literal(), importedToken.lexeme());
                        // String imports MUST have 'as' with local binding
                        if (!check(TokenType.IDENTIFIER) || !peek().lexeme().equals("as")) {
                            throw new ExpectedTokenException("'as' after string import specifier", peek());
                        }
                        // 'as' keyword must not contain Unicode escape sequences
                        if (tokenContainsEscapes(peek())) {
                            throw new ExpectedTokenException("'as' keyword must not contain Unicode escape sequences", peek());
                        }
                        advance(); // consume 'as'
                        Token localToken = peek();
                        if (!check(TokenType.IDENTIFIER) && !isKeyword(localToken)) {
                            throw new ExpectedTokenException("identifier after 'as'", peek());
                        }
                        advance();
                        local = new Identifier(getStart(localToken), getEnd(localToken), localToken.line(), localToken.column(), localToken.endLine(), localToken.endColumn(), localToken.lexeme());
                        validateBindingName(localToken.lexeme(), localToken);
                    } else if (check(TokenType.IDENTIFIER) || isKeyword(importedToken)) {
                        advance();
                        Identifier importedId = new Identifier(getStart(importedToken), getEnd(importedToken), importedToken.line(), importedToken.column(), importedToken.endLine(), importedToken.endColumn(), importedToken.lexeme());
                        imported = importedId;
                        local = importedId;
                        // Check for 'as'
                        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("as")) {
                            // 'as' keyword must not contain Unicode escape sequences
                            if (tokenContainsEscapes(peek())) {
                                throw new ExpectedTokenException("'as' keyword must not contain Unicode escape sequences", peek());
                            }
                            advance(); // consume 'as'
                            Token localToken = peek();
                            if (!check(TokenType.IDENTIFIER) && !isKeyword(localToken)) {
                                throw new ExpectedTokenException("identifier after 'as'", peek());
                            }
                            advance();
                            local = new Identifier(getStart(localToken), getEnd(localToken), localToken.line(), localToken.column(), localToken.endLine(), localToken.endColumn(), localToken.lexeme());
                            validateBindingName(localToken.lexeme(), localToken);
                        } else {
                            // No 'as' - the imported name is also the local binding
                            validateBindingName(importedToken.lexeme(), importedToken);
                        }
                    } else {
                        throw new ExpectedTokenException("identifier or string in import specifier", peek());
                    }

                    Token prevToken = previous();
                    specifiers.add(new ImportSpecifier(getStart(importedToken), getEnd(prevToken), importedToken.line(), importedToken.column(), prevToken.endLine(), prevToken.endColumn(), imported, local));

                    // Handle trailing comma: { a, }
                    if (match(TokenType.COMMA)) {
                        if (check(TokenType.RBRACE)) {
                            break;
                        }
                    } else {
                        break;
                    }
                } while (true);
            }

            consume(TokenType.RBRACE, "Expected '}' after import specifiers");
        }

        // Parse 'from' clause
        Token fromToken = peek();
        if (!check(TokenType.IDENTIFIER) || !fromToken.lexeme().equals("from")) {
            throw new ExpectedTokenException("'from' after import specifiers", peek());
        }
        // 'from' keyword must not contain Unicode escape sequences
        if (tokenContainsEscapes(fromToken)) {
            throw new ExpectedTokenException("'from' keyword must not contain Unicode escape sequences", fromToken);
        }
        advance(); // consume 'from'

        // Parse module source
        Token sourceToken = peek();
        if (!check(TokenType.STRING)) {
            throw new ExpectedTokenException("string literal after 'from'", peek());
        }
        advance();
        Literal source = new Literal(getStart(sourceToken), getEnd(sourceToken), sourceToken.line(), sourceToken.column(), sourceToken.endLine(), sourceToken.endColumn(), sourceToken.literal(), sourceToken.lexeme());

        // Parse import attributes: with { type: 'json' }
        List<ImportAttribute> attributes = parseImportAttributes();

        // Check for duplicate bound names in import specifiers
        // Import bindings are treated as lexical declarations
        java.util.Set<String> boundNames = new java.util.HashSet<>();
        for (Node specNode : specifiers) {
            String localName = null;
            if (specNode instanceof ImportDefaultSpecifier ids) {
                localName = ids.local().name();
            } else if (specNode instanceof ImportNamespaceSpecifier ins) {
                localName = ins.local().name();
            } else if (specNode instanceof ImportSpecifier is) {
                localName = is.local().name();
            }
            if (localName != null) {
                if (boundNames.contains(localName)) {
                    throw new ExpectedTokenException("Duplicate binding name '" + localName + "' in import", startToken);
                }
                boundNames.add(localName);
                // Add import binding to lexicalDeclarations so export { name } can find it
                declareLexicalName(localName, startToken);
            }
        }

        consumeSemicolon("Expected ';' after import");
        Token endToken = previous();
        return new ImportDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), specifiers, source, attributes);
    }

    private List<ImportAttribute> parseImportAttributes() {
        List<ImportAttribute> attributes = new ArrayList<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();

        // Check for 'with' keyword (can be IDENTIFIER or WITH token type)
        if ((check(TokenType.IDENTIFIER) && peek().lexeme().equals("with")) || check(TokenType.WITH)) {
            advance(); // consume 'with'
            consume(TokenType.LBRACE, "Expected '{' after 'with'");

            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                Token keyToken = peek();
                Node key;
                String keyValue;

                if (check(TokenType.STRING)) {
                    advance();
                    key = new Literal(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), keyToken.literal(), keyToken.lexeme());
                    // For strings, the literal is the resolved value
                    keyValue = keyToken.literal() != null ? keyToken.literal().toString() : keyToken.lexeme();
                } else if (check(TokenType.IDENTIFIER) || isKeyword(keyToken)) {
                    advance();
                    key = new Identifier(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), keyToken.lexeme());
                    // For identifiers, the lexeme is the resolved value (unicode escapes are resolved)
                    keyValue = keyToken.lexeme();
                } else {
                    throw new ExpectedTokenException("identifier or string in import attribute", peek());
                }

                // Check for duplicate keys
                if (seenKeys.contains(keyValue)) {
                    throw new ParseException("SyntaxError", keyToken, null, null,
                        "Duplicate import attribute key: " + keyValue);
                }
                seenKeys.add(keyValue);

                consume(TokenType.COLON, "Expected ':' after import attribute key");

                Token valueToken = peek();
                if (!check(TokenType.STRING)) {
                    throw new ExpectedTokenException("string value in import attribute", peek());
                }
                advance();
                Literal value = new Literal(getStart(valueToken), getEnd(valueToken), valueToken.line(), valueToken.column(), valueToken.endLine(), valueToken.endColumn(), valueToken.literal(), valueToken.lexeme());

                Token attrEnd = previous();
                attributes.add(new ImportAttribute(getStart(keyToken), getEnd(attrEnd), keyToken.line(), keyToken.column(), attrEnd.endLine(), attrEnd.endColumn(), key, value));

                if (!match(TokenType.COMMA)) {
                    break;
                }
            }

            consume(TokenType.RBRACE, "Expected '}' after import attributes");
        }

        return attributes;
    }

    private Statement parseExportDeclaration() {
        // Export declarations only allowed in module mode (match Acorn behavior)
        if (!forceModuleMode) {
            throw new ParseException("SyntaxError", peek(), null, null,
                "'import' and 'export' may appear only with 'sourceType: module'");
        }

        // Export declarations only allowed at the top level of a module
        // Not inside functions, blocks, or other nested contexts
        // Check if we're at the module top level (scope depth == 1)
        if (inFunction || scopeStack.size() > 1) {
            throw new ExpectedTokenException("'export' is only valid at the top level of a module", peek());
        }

        Token startToken = peek();
        advance(); // consume 'export'

        // export default ...
        if (match(TokenType.DEFAULT)) {

            // Parse the default export value
            Node declaration;
            if (check(TokenType.FUNCTION)) {
                // Both named and anonymous export default functions are FunctionDeclarations
                // Anonymous just has id: null
                declaration = parseFunctionDeclaration(false, true);
            } else if (check(TokenType.CLASS)) {
                // Both named and anonymous export default classes are ClassDeclarations
                // Anonymous just has id: null
                declaration = parseClassDeclaration(true);
            } else if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("async") && !tokenContainsEscapes(peek())) {
                // Check for async function declaration
                // 'async' keyword must not contain Unicode escape sequences
                int savedCurrent = current;
                advance(); // consume 'async'
                boolean isFunction = check(TokenType.FUNCTION);
                current = savedCurrent; // restore position

                if (isFunction) {
                    // Both named and anonymous async functions are FunctionDeclarations
                    declaration = parseFunctionDeclaration(true, true);
                } else {
                    declaration = parseExpr(BP_ASSIGNMENT);
                    consumeSemicolon("Expected ';' after export default");
                }
            } else {
                // Expression
                declaration = parseExpr(BP_ASSIGNMENT);
                consumeSemicolon("Expected ';' after export default");
            }

            // Register 'default' as an exported name
            registerExportedName("default", startToken);

            Token endToken = previous();
            return new ExportDefaultDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), declaration);
        }

        // export * from 'module' or export * as name from 'module'
        if (match(TokenType.STAR)) {
            Node exported = null;

            // Check for 'as'
            if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("as")) {
                // 'as' keyword must not contain Unicode escape sequences
                if (tokenContainsEscapes(peek())) {
                    throw new ExpectedTokenException("'as' keyword must not contain Unicode escape sequences", peek());
                }
                advance(); // consume 'as'
                Token nameToken = peek();
                String exportedName = null;
                // Allow keywords as identifiers or strings after 'as'
                if (check(TokenType.STRING)) {
                    advance();
                    exportedName = (String) nameToken.literal();
                    // Check for unpaired surrogates in string module export name
                    if (exportedName != null && !isStringWellFormedUnicode(exportedName)) {
                        throw new ParseException("SyntaxError", nameToken, null, null,
                            "Module export name must not contain unpaired surrogates");
                    }
                    exported = new Literal(getStart(nameToken), getEnd(nameToken), nameToken.line(), nameToken.column(), nameToken.endLine(), nameToken.endColumn(), nameToken.literal(), nameToken.lexeme());
                } else if (check(TokenType.IDENTIFIER) || isKeyword(nameToken) ||
                           check(TokenType.TRUE) || check(TokenType.FALSE) || check(TokenType.NULL)) {
                    advance();
                    exportedName = nameToken.lexeme();
                    exported = new Identifier(getStart(nameToken), getEnd(nameToken), nameToken.line(), nameToken.column(), nameToken.endLine(), nameToken.endColumn(), nameToken.lexeme());
                } else {
                    throw new ExpectedTokenException("identifier or string after 'as'", peek());
                }
                // Register the exported name
                registerExportedName(exportedName, nameToken);
            }

            // Parse 'from'
            Token fromToken = peek();
            if (!check(TokenType.IDENTIFIER) || !fromToken.lexeme().equals("from")) {
                throw new ExpectedTokenException("'from' after export *", peek());
            }
            // 'from' keyword must not contain Unicode escape sequences
            if (tokenContainsEscapes(fromToken)) {
                throw new ExpectedTokenException("'from' keyword must not contain Unicode escape sequences", fromToken);
            }
            advance(); // consume 'from'

            // Parse source
            Token sourceToken = peek();
            if (!check(TokenType.STRING)) {
                throw new ExpectedTokenException("string literal after 'from'", peek());
            }
            advance();
            Literal source = new Literal(getStart(sourceToken), getEnd(sourceToken), sourceToken.line(), sourceToken.column(), sourceToken.endLine(), sourceToken.endColumn(), sourceToken.literal(), sourceToken.lexeme());

            List<ImportAttribute> attributes = parseImportAttributes();
            consumeSemicolon("Expected ';' after export");
            Token endToken = previous();
            return new ExportAllDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), source, exported, attributes);
        }

        // export { name1, name2 } or export { name1 as alias } from 'module'
        if (match(TokenType.LBRACE)) {
            List<Node> specifiers = new ArrayList<>();

            // Handle empty specifiers: { }
            if (!check(TokenType.RBRACE)) {
                do {
                    Token localToken = peek();
                    Node local;
                    if (check(TokenType.STRING)) {
                        advance();
                        // Check for unpaired surrogates in string module export name
                        String strValue = localToken.literal() != null ? localToken.literal().toString() : "";
                        if (!isStringWellFormedUnicode(strValue)) {
                            throw new ParseException("SyntaxError", localToken, null, null,
                                "Module export name must not contain unpaired surrogates");
                        }
                        local = new Literal(getStart(localToken), getEnd(localToken), localToken.line(), localToken.column(), localToken.endLine(), localToken.endColumn(), localToken.literal(), localToken.lexeme());
                    } else if (check(TokenType.IDENTIFIER) || isKeyword(localToken)) {
                        advance();
                        local = new Identifier(getStart(localToken), getEnd(localToken), localToken.line(), localToken.column(), localToken.endLine(), localToken.endColumn(), localToken.lexeme());
                    } else {
                        throw new ExpectedTokenException("identifier or string in export specifier", peek());
                    }

                    Node exported = local;
                    // Check for 'as'
                    if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("as")) {
                        // 'as' keyword must not contain Unicode escape sequences
                        if (tokenContainsEscapes(peek())) {
                            throw new ExpectedTokenException("'as' keyword must not contain Unicode escape sequences", peek());
                        }
                        advance(); // consume 'as'
                        Token exportedToken = peek();
                        if (check(TokenType.STRING)) {
                            advance();
                            // Check for unpaired surrogates in string module export name
                            String strValue = exportedToken.literal() != null ? exportedToken.literal().toString() : "";
                            if (!isStringWellFormedUnicode(strValue)) {
                                throw new ParseException("SyntaxError", exportedToken, null, null,
                                    "Module export name must not contain unpaired surrogates");
                            }
                            exported = new Literal(getStart(exportedToken), getEnd(exportedToken), exportedToken.line(), exportedToken.column(), exportedToken.endLine(), exportedToken.endColumn(), exportedToken.literal(), exportedToken.lexeme());
                        } else if (check(TokenType.IDENTIFIER) || isKeyword(exportedToken) ||
                                   check(TokenType.TRUE) || check(TokenType.FALSE) || check(TokenType.NULL)) {
                            advance();
                            exported = new Identifier(getStart(exportedToken), getEnd(exportedToken), exportedToken.line(), exportedToken.column(), exportedToken.endLine(), exportedToken.endColumn(), exportedToken.lexeme());
                        } else {
                            throw new ExpectedTokenException("identifier or string after 'as'", peek());
                        }
                    }

                    Token prevToken = previous();
                    specifiers.add(new ExportSpecifier(getStart(localToken), getEnd(prevToken), localToken.line(), localToken.column(), prevToken.endLine(), prevToken.endColumn(), local, exported));

                    // Handle trailing comma: { a, }
                    if (match(TokenType.COMMA)) {
                        if (check(TokenType.RBRACE)) {
                            break;
                        }
                    } else {
                        break;
                    }
                } while (true);
            }

            consume(TokenType.RBRACE, "Expected '}' after export specifiers");

            // Register all exported names from specifiers
            for (Node specNode : specifiers) {
                if (specNode instanceof ExportSpecifier spec) {
                    Node exportedNode = spec.exported();
                    String exportedName;
                    if (exportedNode instanceof Identifier id) {
                        exportedName = id.name();
                    } else if (exportedNode instanceof Literal lit) {
                        exportedName = (String) lit.value();
                    } else {
                        continue;
                    }
                    registerExportedName(exportedName, startToken);
                }
            }

            // Check for 'from' (re-export)
            Literal source = null;
            List<ImportAttribute> attributes = new ArrayList<>();
            if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("from")) {
                // 'from' keyword must not contain Unicode escape sequences
                if (tokenContainsEscapes(peek())) {
                    throw new ExpectedTokenException("'from' keyword must not contain Unicode escape sequences", peek());
                }
                advance(); // consume 'from'
                Token sourceToken = peek();
                if (!check(TokenType.STRING)) {
                    throw new ExpectedTokenException("string literal after 'from'", peek());
                }
                advance();
                source = new Literal(getStart(sourceToken), getEnd(sourceToken), sourceToken.line(), sourceToken.column(), sourceToken.endLine(), sourceToken.endColumn(), sourceToken.literal(), sourceToken.lexeme());
                attributes = parseImportAttributes();
            } else {
                // Not a re-export - local names must be identifiers, not strings
                // (strings are only allowed as local names in re-exports like: export { "foo" as bar } from 'mod')
                for (Node specNode : specifiers) {
                    if (specNode instanceof ExportSpecifier spec) {
                        if (spec.local() instanceof Literal) {
                            throw new ParseException("SyntaxError", startToken, null, null,
                                "Cannot export using string name when not re-exporting from another module");
                        }
                        // Track local export bindings for later validation
                        // These must reference bindings declared in the module
                        if (spec.local() instanceof Identifier id) {
                            pendingExportBindings.add(new PendingExportBinding(id.name(), startToken));
                        }
                    }
                }
            }

            consumeSemicolon("Expected ';' after export");
            Token endToken = previous();
            return new ExportNamedDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), null, specifiers, source, attributes);
        }

        // export var/let/const/function/class declaration
        Statement declaration = null;
        if (check(TokenType.VAR) || check(TokenType.LET) || check(TokenType.CONST)) {
            declaration = parseVariableDeclaration();
            // Register all exported names from variable declaration
            if (declaration instanceof VariableDeclaration varDecl) {
                for (VariableDeclarator declarator : varDecl.declarations()) {
                    java.util.List<String> names = new ArrayList<>();
                    collectBindingNames(declarator.id(), names);
                    for (String name : names) {
                        registerExportedName(name, startToken);
                    }
                }
            }
        } else if (check(TokenType.FUNCTION)) {
            declaration = parseFunctionDeclaration(false);
            // Register exported function name
            if (declaration instanceof FunctionDeclaration funcDecl && funcDecl.id() != null) {
                registerExportedName(funcDecl.id().name(), startToken);
            }
        } else if (check(TokenType.CLASS)) {
            declaration = parseClassDeclaration();
            // Register exported class name
            if (declaration instanceof ClassDeclaration classDecl && classDecl.id() != null) {
                registerExportedName(classDecl.id().name(), startToken);
            }
        } else if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("async")) {
            // export async function
            // Don't consume 'async' - let parseFunctionDeclaration handle it
            declaration = parseFunctionDeclaration(true); // pass true for async
            // Register exported async function name
            if (declaration instanceof FunctionDeclaration funcDecl && funcDecl.id() != null) {
                registerExportedName(funcDecl.id().name(), startToken);
            }
        } else {
            throw new UnexpectedTokenException(peek(), "export keyword", "export statement");
        }

        Token endToken = previous();
        return new ExportNamedDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), declaration, new ArrayList<>(), null, new ArrayList<>());
    }

    private BlockStatement parseBlockStatement() {
        return parseBlockStatement(false);
    }

    private BlockStatement parseBlockStatement(boolean isFunctionBody) {
        Token startToken = peek();
        consume(TokenType.LBRACE, "Expected '{'");

        // Push a new block scope (not function scope) for non-function blocks
        // Function bodies have their scope pushed in parseFunction
        if (!isFunctionBody) {
            pushScope(false);
        }

        // Temporarily allow 'in' inside blocks
        boolean oldAllowIn = allowIn;
        allowIn = true;

        // Enable directive context for function bodies
        boolean oldDirectiveContext = inDirectiveContext;
        boolean oldStrictMode = strictMode;
        if (isFunctionBody) {
            inDirectiveContext = true;
        } else {
            inDirectiveContext = false;
        }

        List<Statement> statements = new ArrayList<>();
        boolean inPrologue = isFunctionBody;
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Statement stmt = parseStatement();

            // Check for directive prologue in function bodies and apply strict mode immediately
            if (inPrologue && isFunctionBody && inDirectiveContext) {
                if (isUseStrictDirective(stmt)) {
                    strictMode = true;
                    lexer.setStrictMode(true);
                } else if (!isPotentialDirective(stmt)) {
                    // Non-directive statement ends the prologue
                    inPrologue = false;
                    inDirectiveContext = false;
                }
            }

            statements.add(stmt);
        }

        // Restore directive context
        inDirectiveContext = oldDirectiveContext;

        // Restore strict mode for function bodies (function's strict mode doesn't leak out)
        if (isFunctionBody) {
            strictMode = oldStrictMode;
            lexer.setStrictMode(oldStrictMode);
        }

        // Process directive prologue only for function bodies (adds directive property to AST nodes)
        if (isFunctionBody) {
            statements = processDirectives(statements);
        }

        consume(TokenType.RBRACE, "Expected '}'");
        Token endToken = previous();

        // Pop the block scope (only if we pushed one)
        if (!isFunctionBody) {
            popScope();
        }

        // Restore allowIn
        allowIn = oldAllowIn;

        return new BlockStatement(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), statements);
    }

    /**
     * Check if a statement is a "use strict" directive.
     * Must be an expression statement with a string literal "use strict" (not parenthesized).
     */
    private boolean isUseStrictDirective(Statement stmt) {
        if (stmt instanceof ExpressionStatement exprStmt) {
            // Skip if marked as parenthesized (empty directive marker)
            if (exprStmt.directive() != null && exprStmt.directive().isEmpty()) {
                return false;
            }
            if (exprStmt.expression() instanceof Literal lit && lit.value() instanceof String) {
                String directiveValue = lit.raw().substring(1, lit.raw().length() - 1);
                return directiveValue.equals("use strict");
            }
        }
        return false;
    }

    /**
     * Check if a string literal's raw value contains legacy octal or non-octal decimal escape sequences.
     * These are:
     * - \1 through \7 (octal escapes)
     * - \8 and \9 (non-octal decimal escapes)
     * - \0 followed by any decimal digit (0-9) - makes it a legacy octal escape
     * Note: \0 alone (not followed by a digit) IS allowed in strict mode (null character).
     * Returns the position of the first legacy escape, or -1 if none found.
     */
    private int containsLegacyOctalEscape(String raw) {
        // Skip the opening quote
        for (int i = 1; i < raw.length() - 1; i++) {
            if (raw.charAt(i) == '\\' && i + 1 < raw.length() - 1) {
                char next = raw.charAt(i + 1);
                // Check for octal escapes \0-\7 and non-octal decimal escapes \8-\9
                if (next >= '0' && next <= '9') {
                    // \0 alone (not followed by ANY decimal digit) is allowed in strict mode
                    if (next == '0') {
                        // Check if \0 is followed by any decimal digit (0-9)
                        if (i + 2 < raw.length() - 1) {
                            char afterZero = raw.charAt(i + 2);
                            if (afterZero >= '0' && afterZero <= '9') {
                                // \0X where X is any digit is a legacy escape (or invalid)
                                return i;
                            }
                        }
                        // \0 alone is OK, skip it
                        i++; // skip the 0
                    } else {
                        // \1-\9 are legacy escapes
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Check if a statement could be a directive (string literal expression statement).
     * Used to determine when the directive prologue ends.
     */
    private boolean isPotentialDirective(Statement stmt) {
        if (stmt instanceof ExpressionStatement exprStmt) {
            // Parenthesized expression ends the prologue
            if (exprStmt.directive() != null && exprStmt.directive().isEmpty()) {
                return false;
            }
            // String literal expression could be a directive
            return exprStmt.expression() instanceof Literal lit && lit.value() instanceof String;
        }
        return false;
    }

    // Process directive prologue: add directive property to string literal expression statements at the start
    private List<Statement> processDirectives(List<Statement> statements) {
        List<Statement> processed = new ArrayList<>();
        List<Literal> prologueLiterals = new ArrayList<>(); // Track string literals before "use strict"
        boolean inPrologue = true;
        boolean foundUseStrict = false;

        for (Statement stmt : statements) {
            // First, handle parenthesized expressions (marked with empty directive)
            // These need their empty directive cleared, regardless of prologue state
            if (stmt instanceof ExpressionStatement exprStmt && exprStmt.directive() != null && exprStmt.directive().isEmpty()) {
                // Clear the empty directive marker and add as regular statement
                processed.add(new ExpressionStatement(
                    exprStmt.start(),
                    exprStmt.end(),
                    exprStmt.startLine(),
                    exprStmt.startCol(),
                    exprStmt.endLine(),
                    exprStmt.endCol(),
                    exprStmt.expression(),
                    null
                ));
                // Parenthesized expression ends the prologue
                inPrologue = false;
                continue;
            }

            if (inPrologue && stmt instanceof ExpressionStatement exprStmt) {
                if (exprStmt.expression() instanceof Literal lit && lit.value() instanceof String) {
                    // This is a directive
                    // Directive value is the raw string content (without quotes) to preserve escape sequences
                    String directiveValue = lit.raw().substring(1, lit.raw().length() - 1);

                    // Detect "use strict" directive
                    if (directiveValue.equals("use strict")) {
                        strictMode = true;
                        lexer.setStrictMode(true);
                        foundUseStrict = true;

                        // Validate all preceding string literals for legacy octal escapes
                        for (Literal prevLit : prologueLiterals) {
                            int escapePos = containsLegacyOctalEscape(prevLit.raw());
                            if (escapePos >= 0) {
                                // Create a token for the error location
                                Token errorToken = new Token(
                                    TokenType.STRING, prevLit.raw(), prevLit.value(),
                                    prevLit.startLine(), prevLit.startCol(), prevLit.start()
                                );
                                throw new ParseException(
                                    "SyntaxError", errorToken, null, "directive prologue",
                                    "Octal escape sequences are not allowed in strict mode"
                                );
                            }
                        }
                    }

                    // Track this literal for potential retroactive validation
                    if (!foundUseStrict) {
                        prologueLiterals.add(lit);
                    }

                    processed.add(new ExpressionStatement(
                        exprStmt.start(),
                        exprStmt.end(),
                        exprStmt.startLine(),
                        exprStmt.startCol(),
                        exprStmt.endLine(),
                        exprStmt.endCol(),
                        exprStmt.expression(),
                        directiveValue
                    ));
                    continue;
                } else {
                    // Non-string-literal expression ends the prologue
                    inPrologue = false;
                }
            } else if (inPrologue) {
                // Non-expression statement ends the prologue
                inPrologue = false;
            }
            processed.add(stmt);
        }

        return processed;
    }

    private VariableDeclaration parseVariableDeclaration() {
        Token startToken = peek();
        Token kindToken = advance(); // var, let, or const
        String kind = kindToken.lexeme();
        boolean isLexical = kind.equals("let") || kind.equals("const");

        List<VariableDeclarator> declarators = new ArrayList<>();

        do {
            Token patternStart = peek();
            // Don't parse default values at top level - those are initializers, not defaults
            Pattern pattern = parsePatternBase();

            // Register declarations for redeclaration checking
            java.util.List<String> bindingNames = new ArrayList<>();
            collectBindingNames(pattern, bindingNames);
            for (String name : bindingNames) {
                // In strict mode, eval and arguments cannot be used as binding names
                validateBindingName(name, patternStart);
                // 'let' cannot be a binding name in lexical declarations
                if (isLexical && name.equals("let")) {
                    throw new ExpectedTokenException("'let' is disallowed as a lexically bound name", patternStart);
                }
                if (isLexical) {
                    declareLexicalName(name, patternStart);
                } else {
                    declareVarName(name, patternStart);
                }
            }

            Expression init = null;
            if (match(TokenType.ASSIGN)) {
                init = parseExpr(BP_ASSIGNMENT);
            }

            // const declarations require initializers
            if (kind.equals("const") && init == null) {
                throw new ExpectedTokenException("Missing initializer in const declaration", patternStart);
            }

            Token declaratorEnd = previous();

            int declaratorStart = getStart(patternStart);
            int declaratorEndPos = getEnd(declaratorEnd);

            declarators.add(new VariableDeclarator(declaratorStart, declaratorEndPos, patternStart.line(), patternStart.column(), declaratorEnd.endLine(), declaratorEnd.endColumn(), pattern, init));

        } while (match(TokenType.COMMA));

        consumeSemicolon("Expected ';' after variable declaration");

        Token endToken = previous();
        return new VariableDeclaration(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), declarators, kind);
    }

    private Pattern parsePattern() {
        return parsePatternWithDefault();
    }

    private Pattern parsePatternWithDefault() {
        Token startToken = peek();
        Pattern pattern = parsePatternBase();

        // Check for default value: pattern = defaultValue
        if (match(TokenType.ASSIGN)) {
            // Inside destructuring patterns, 'in' is always the operator, not for-in keyword
            // For example: for (let [x = 'a' in {}] = []; ...) - the 'in' is an operator
            boolean savedAllowIn = allowIn;
            allowIn = true;
            Expression defaultValue = parseExpr(BP_ASSIGNMENT);
            allowIn = savedAllowIn;
            Token endToken = previous();
            return new AssignmentPattern(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), pattern, defaultValue);
        }

        return pattern;
    }

    private Pattern parsePatternBase() {
        Token startToken = peek();

        if (match(TokenType.LBRACE)) {
            // Object pattern: { x, y, z }
            return parseObjectPattern(startToken);
        } else if (match(TokenType.LBRACKET)) {
            // Array pattern: [ a, b, c ]
            return parseArrayPattern(startToken);
        } else if (check(TokenType.IDENTIFIER) || isKeyword(peek())) {
            // Simple identifier pattern (keywords allowed as identifiers in patterns)
            Token idToken = advance();
            // Validate that yield/await are not used as binding identifiers in restricted contexts
            validateIdentifier(idToken.lexeme(), idToken);
            return new Identifier(getStart(idToken), getEnd(idToken), idToken.line(), idToken.column(), idToken.endLine(), idToken.endColumn(), idToken.lexeme());
        } else {
            throw new ExpectedTokenException("identifier in variable declaration", peek());
        }
    }

    private ObjectPattern parseObjectPattern(Token startToken) {
        List<Node> properties = new ArrayList<>();

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            // Check for rest element in object pattern: {...rest}
            if (match(TokenType.DOT_DOT_DOT)) {
                Token restStart = previous();
                Pattern argument = parsePatternBase();
                Token restEnd = previous();
                properties.add(new RestElement(getStart(restStart), getEnd(restEnd), restStart.line(), restStart.column(), restEnd.endLine(), restEnd.endColumn(), argument));
                // Rest element must be last
                if (match(TokenType.COMMA)) {
                    throw new ParseException("ValidationError", peek(), null, "object pattern", "Rest element must be last in object pattern");
                }
                break;
            }

            Token propStart = peek();

            // Parse the key (identifier or computed)
            Node key;
            boolean computed = false;
            boolean shorthand = false;

            if (match(TokenType.LBRACKET)) {
                // Computed property: [expr]
                computed = true;
                // Allow 'in' operator in computed property names
                boolean savedAllowIn = allowIn;
                allowIn = true;
                key = parseExpr(BP_ASSIGNMENT);
                allowIn = savedAllowIn;
                consume(TokenType.RBRACKET, "Expected ']' after computed property");
            } else if (check(TokenType.STRING) || check(TokenType.NUMBER)) {
                // Literal key (string or numeric)
                Token keyToken = advance();
                String keyLexeme = keyToken.lexeme();

                // Check if this is a BigInt literal (ends with 'n')
                if (keyLexeme.endsWith("n")) {
                    // BigInt literal: value is null, bigint field has the numeric part
                    String bigintValue = keyLexeme.substring(0, keyLexeme.length() - 1).replace("_", "");

                    // Convert hex/octal/binary to decimal for the bigint field
                    if (bigintValue.startsWith("0x") || bigintValue.startsWith("0X")) {
                        try {
                            java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 16);
                            bigintValue = bi.toString();
                        } catch (NumberFormatException e) {
                            // Keep original if conversion fails
                        }
                    } else if (bigintValue.startsWith("0o") || bigintValue.startsWith("0O")) {
                        try {
                            java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 8);
                            bigintValue = bi.toString();
                        } catch (NumberFormatException e) {
                            // Keep original if conversion fails
                        }
                    } else if (bigintValue.startsWith("0b") || bigintValue.startsWith("0B")) {
                        try {
                            java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 2);
                            bigintValue = bi.toString();
                        } catch (NumberFormatException e) {
                            // Keep original if conversion fails
                        }
                    }
                    // For BigInt literals, Acorn sets value to null
                    key = new Literal(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), null, keyLexeme, null, bigintValue);
                } else {
                    Object literalValue = keyToken.literal();
                    if (literalValue instanceof Double d && (d.isInfinite() || d.isNaN())) {
                        literalValue = null;
                    }
                    key = new Literal(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), literalValue, keyLexeme);
                }
            } else {
                // Allow identifiers, keywords, and boolean/null literals as property names
                if (!check(TokenType.IDENTIFIER) && !isKeyword(peek()) &&
                    !check(TokenType.TRUE) && !check(TokenType.FALSE) && !check(TokenType.NULL)) {
                    throw new ExpectedTokenException("property name", peek());
                }
                Token keyToken = advance();
                key = new Identifier(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), keyToken.lexeme());
            }

            // Parse the value (pattern)
            Pattern value;
            if (match(TokenType.COLON)) {
                // Full form: { x: y }
                value = parsePattern();
            } else {
                // Shorthand form: { x } or { x = defaultValue }
                shorthand = true;
                if (key instanceof Identifier id) {
                    // Reserved words cannot be used as binding identifiers (even with escape sequences)
                    if (isReservedWord(id.name())) {
                        throw new ExpectedTokenException("'" + id.name() + "' is a reserved word and cannot be used as a binding identifier", propStart);
                    }
                    // Strict mode reserved words also cannot be used in strict mode
                    if (strictMode && isStrictModeReservedWord(id.name())) {
                        throw new ExpectedTokenException("'" + id.name() + "' is a reserved word in strict mode", propStart);
                    }
                    value = id;
                    // Check for default value in shorthand: { x = 1 }
                    if (match(TokenType.ASSIGN)) {
                        Token assignStart = previous();
                        // Inside destructuring patterns, 'in' is always the operator, not for-in keyword
                        boolean savedAllowIn = allowIn;
                        allowIn = true;
                        Expression defaultValue = parseExpr(BP_ASSIGNMENT);
                        allowIn = savedAllowIn;
                        Token assignEnd = previous();
                        value = new AssignmentPattern(getStart(propStart), getEnd(assignEnd), propStart.line(), propStart.column(), assignEnd.endLine(), assignEnd.endColumn(), id, defaultValue);
                    }
                } else {
                    throw new ParseException("ValidationError", peek(), null, "object pattern property", "Shorthand property must have identifier key");
                }
            }

            Token propEnd = previous();
            properties.add(new Property(
                getStart(propStart), getEnd(propEnd), propStart.line(), propStart.column(), propEnd.endLine(), propEnd.endColumn(),
                false, shorthand, computed, key, value, "init"
            ));

            if (!match(TokenType.COMMA)) {
                break;
            }
        }

        consume(TokenType.RBRACE, "Expected '}' after object pattern");
        Token endToken = previous();
        return new ObjectPattern(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), properties);
    }

    private ArrayPattern parseArrayPattern(Token startToken) {
        List<Pattern> elements = new ArrayList<>();

        while (!check(TokenType.RBRACKET) && !isAtEnd()) {
            if (match(TokenType.DOT_DOT_DOT)) {
                // Rest element: ...rest (no default value allowed)
                Token restStart = previous();
                Pattern argument = parsePatternBase();
                Token restEnd = previous();
                elements.add(new RestElement(getStart(restStart), getEnd(restEnd), restStart.line(), restStart.column(), restEnd.endLine(), restEnd.endColumn(), argument));
                // Rest element must be last
                if (match(TokenType.COMMA)) {
                    throw new ParseException("ValidationError", peek(), null, "array pattern", "Rest element must be last in array pattern");
                }
                break;
            } else if (check(TokenType.COMMA)) {
                // Hole in array pattern
                elements.add(null);
            } else {
                // Regular pattern element
                elements.add(parsePattern());
            }

            if (!match(TokenType.COMMA)) {
                break;
            }
        }

        consume(TokenType.RBRACKET, "Expected ']' after array pattern");
        Token endToken = previous();
        return new ArrayPattern(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), elements);
    }

    // Expression parsing with precedence climbing
    // expression -> sequence
    // sequence -> assignment ( "," assignment )*
    // assignment -> conditional ( "=" assignment )?
    private Expression parseExpression() {
        return parseExpr(BP_COMMA);
    }

    // ========================================================================
    // Unified Pratt Parser - parseExpr(int minBp)
    // ========================================================================
    // This is the core of the Pratt parsing algorithm.
    // It parses expressions with binding power >= minBp.
    //
    // The algorithm:
    // 1. Parse a prefix expression (NUD)
    // 2. While the next token has binding power >= minBp, parse infix (LED)
    //
    // This collapses the 8-method chain:
    //   parseExpression → parseSequence → parseAssignment → parseConditional
    //   → parseBinaryExpression → parseUnary → parsePostfix → parsePrimary
    // into a single unified method with flat call stack.

    private Expression parseExpr(int minBp) {
        Token startToken = peek();
        TokenType startType = startToken.type();
        Expression left = null;

        // Save and reset coalesce/logical chain tracking for new expression contexts
        // When minBp <= BP_TERNARY, we're parsing a fresh expression (top-level, ternary branch, comma RHS, etc.)
        // The ??, &&, || mixing restriction only applies within a single expression, not across expression boundaries
        boolean savedInCoalesceChain = inCoalesceChain;
        boolean savedInLogicalChain = inLogicalChain;
        boolean shouldResetChainFlags = minBp <= BP_TERNARY;
        if (shouldResetChainFlags) {
            inCoalesceChain = false;
            inLogicalChain = false;
        }

        // Handle contextual keywords: yield and await
        // These have assignment-level precedence and need special handling
        if (startType == TokenType.IDENTIFIER) {
            String lexeme = startToken.lexeme();

            // Yield expression - only parse as yield if at assignment level or below
            // Yield expressions can only appear at assignment-level precedence or lower
            // At higher binding powers (e.g., operand of +), yield must be treated as identifier (which fails validation)
            boolean canParseYieldExpr = minBp <= BP_ASSIGNMENT;
            // Yield expressions are not allowed in formal parameter defaults
            if (inGenerator && !inFormalParameters && lexeme.equals("yield") &&
                !checkAhead(1, TokenType.ASSIGN) && !checkAhead(1, TokenType.PLUS_ASSIGN) &&
                !checkAhead(1, TokenType.MINUS_ASSIGN) && !checkAhead(1, TokenType.STAR_ASSIGN) &&
                !checkAhead(1, TokenType.SLASH_ASSIGN) && !checkAhead(1, TokenType.PERCENT_ASSIGN) &&
                canParseYieldExpr) {
                left = parseYieldExpr();
            }

            // Await expression (complex logic in shouldParseAwait)
            if (left == null && lexeme.equals("await") && shouldParseAwait(minBp)) {
                left = parseAwaitExpr();
            }

            // Quick check for arrow function: id => or async ...
            if (left == null && current + 1 < tokens.size()) {
                TokenType nextType = tokens.get(current + 1).type();
                if (nextType == TokenType.ARROW) {
                    // Simple arrow: id =>
                    left = tryParseArrowFunction(startToken);
                } else if (lexeme.equals("async") && startToken.line() == tokens.get(current + 1).line()) {
                    // Potential async arrow
                    left = tryParseArrowFunction(startToken);
                }
            }
        } else if (startType == TokenType.OF || startType == TokenType.LET) {
            // of => or let => (rare but valid)
            if (current + 1 < tokens.size() && tokens.get(current + 1).type() == TokenType.ARROW) {
                left = tryParseArrowFunction(startToken);
            }
        } else if (startType == TokenType.LPAREN) {
            // Check for arrow: (params) =>
            // Only call tryParseArrowFunction if it looks like it could be arrow params
            int savedCurrent = current;
            advance(); // consume (
            boolean isArrow = isArrowFunctionParameters();
            current = savedCurrent;

            if (isArrow) {
                left = tryParseArrowFunction(startToken);
            }
        }

        // Prefix handling (NUD - Null Denotation) - only if no special case handled above
        // Inlined switch instead of map lookup + lambda for better performance
        if (left == null) {
            Token token = peek();
            advance();
            Token prevToken = previous();
            left = switch (token.type()) {
                // Literals
                case NUMBER -> prefixNumber(this, prevToken);
                case STRING -> prefixString(this, prevToken);
                case TRUE -> prefixTrue(this, prevToken);
                case FALSE -> prefixFalse(this, prevToken);
                case NULL -> prefixNull(this, prevToken);
                case REGEX -> prefixRegex(this, prevToken);

                // Identifiers and keywords
                case IDENTIFIER -> prefixIdentifier(this, prevToken);
                case LET, OF -> prefixIdentifier(this, prevToken); // let and of are valid identifiers in non-strict mode
                case THIS -> prefixThis(this, prevToken);
                case SUPER -> prefixSuper(this, prevToken);

                // Grouping and collections
                case LPAREN -> prefixGroupedOrArrow(this, prevToken);
                case LBRACKET -> prefixArray(this, prevToken);
                case LBRACE -> prefixObject(this, prevToken);

                // Function/class expressions
                case FUNCTION -> prefixFunction(this, prevToken);
                case CLASS -> prefixClass(this, prevToken);
                case NEW -> prefixNew(this, prevToken);

                // Unary operators
                case BANG, MINUS, PLUS, TILDE, TYPEOF, VOID, DELETE -> prefixUnary(this, prevToken);
                case INCREMENT, DECREMENT -> prefixUpdate(this, prevToken);

                // Templates
                case TEMPLATE_LITERAL, TEMPLATE_HEAD -> prefixTemplate(this, prevToken);

                // Special
                case IMPORT -> prefixImport(this, prevToken);
                case HASH -> prefixPrivateIdentifier(this, prevToken);

                default -> throw new UnexpectedTokenException(token, "expression");
            };
        }

        // ========================================================================
        // Infix loop (LED - Left Denotation) - inlined from continueInfix
        // ========================================================================

        // Store the outer expression start - we need these local vars because handlers may recursively call parseExpr
        int outerStartPos = getStart(startToken);
        SourceLocation.Position outerStartLoc = new SourceLocation.Position(startToken.line(), startToken.column());

        // Track optional chaining for ChainExpression wrapping
        boolean hasOptionalChaining = false;
        Token chainEndToken = null; // Token where the chain portion ends

        // Infix/Postfix loop
        while (true) {
            Token token = peek();

            // Yield/await expressions with no argument have implicit ASI
            // If the next token is on a new line, don't continue with infix operators
            // BUT: if the yield was wrapped in parens like (yield), the parens complete
            // the expression and ASI should NOT apply. We detect this by comparing positions.
            if (left instanceof YieldExpression ye && ye.argument() == null) {
                // Only apply ASI if the yield is "bare" (not wrapped in parens)
                // If left.start() == outerStartPos, the yield is at the outer expression level
                if (left.start() == outerStartPos) {
                    Token prevToken = previous();
                    if (prevToken.line() < token.line()) {
                        break;
                    }
                }
            }
            if (left instanceof AwaitExpression) {
                // Note: await always has an argument, so this is less critical
                // but for consistency, we could add similar check if needed
            }

            // Special case: postfix ++/-- with line terminator restriction
            if ((token.type() == TokenType.INCREMENT || token.type() == TokenType.DECREMENT)) {
                Token prevToken = previous();
                if (prevToken.line() < token.line()) {
                    // Line terminator before postfix operator - stop
                    break;
                }
                // Handle as postfix update
                if (BP_POSTFIX >= minBp) {
                    // Validate that the operand is a valid simple assignment target
                    validateSimpleAssignmentTarget(left, token);

                    // If we have optional chaining and this is a non-chain operator, wrap first
                    if (hasOptionalChaining) {
                        chainEndToken = previous();
                        left = new ChainExpression(outerStartPos, getEnd(chainEndToken), startToken.line(), startToken.column(), chainEndToken.endLine(), chainEndToken.endColumn(), left);
                        hasOptionalChaining = false;
                        // Update start positions for the outer expression
                        outerStartPos = getStart(startToken);
                        outerStartLoc = new SourceLocation.Position(startToken.line(), startToken.column());
                    }
                    advance();
                    Token endToken = previous();
                    left = new UpdateExpression(outerStartPos, getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), token.lexeme(), false, left);
                    continue;
                }
                break;
            }

            // Get binding power for this token - inlined for performance
            TokenType tt = token.type();
            int lbp = switch (tt) {
                case COMMA -> BP_COMMA;
                case ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN,
                     STAR_STAR_ASSIGN, LEFT_SHIFT_ASSIGN, RIGHT_SHIFT_ASSIGN, UNSIGNED_RIGHT_SHIFT_ASSIGN,
                     BIT_AND_ASSIGN, BIT_OR_ASSIGN, BIT_XOR_ASSIGN, AND_ASSIGN, OR_ASSIGN, QUESTION_QUESTION_ASSIGN -> BP_ASSIGNMENT;
                case QUESTION -> BP_TERNARY;
                case QUESTION_QUESTION -> BP_NULLISH;
                case OR -> BP_OR;
                case AND -> BP_AND;
                case BIT_OR -> BP_BIT_OR;
                case BIT_XOR -> BP_BIT_XOR;
                case BIT_AND -> BP_BIT_AND;
                case EQ, NE, EQ_STRICT, NE_STRICT -> BP_EQUALITY;
                case LT, LE, GT, GE, INSTANCEOF, IN -> BP_RELATIONAL;
                case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> BP_SHIFT;
                case PLUS, MINUS -> BP_ADDITIVE;
                case STAR, SLASH, PERCENT -> BP_MULTIPLICATIVE;
                case STAR_STAR -> BP_EXPONENT;
                case DOT, QUESTION_DOT, LBRACKET, LPAREN, TEMPLATE_LITERAL, TEMPLATE_HEAD -> BP_POSTFIX;
                default -> -1; // Not an infix operator
            };

            if (lbp < 0 || lbp < minBp) {
                break;
            }

            // Special case: 'in' operator respects allowIn flag
            if (tt == TokenType.IN && !allowIn) {
                break;
            }

            // ASI rules for ( and [ after arrow functions with block bodies:
            // Arrow functions with block bodies (e.g., () => {}) end with } which cannot
            // be called or indexed. So ( or [ on the next line should trigger ASI.
            // See Test262: fields-asi-1.js (no ASI for obj[]) vs function-name-computed (ASI after arrow)
            // Also: doNotEmitDetachedCommentsAtStartOfLambdaFunction.js (ASI between arrow expressions)
            if (previous().line() < peek().line() && (tt == TokenType.LBRACKET || tt == TokenType.LPAREN)) {
                // Check if left is an arrow function with block body
                if (left instanceof ArrowFunctionExpression arrow && arrow.body() instanceof BlockStatement) {
                    break; // ASI before [ or ( after arrow function with block body
                }
            }

            // Template literals cannot follow optional chaining
            if (hasOptionalChaining && (tt == TokenType.TEMPLATE_LITERAL || tt == TokenType.TEMPLATE_HEAD)) {
                throw new ExpectedTokenException("Tagged template expressions are not permitted in an optional chain", token);
            }

            // Track optional chaining - only ?. and subsequent chain operations
            boolean isChainOperator = tt == TokenType.QUESTION_DOT ||
                (hasOptionalChaining && (
                    tt == TokenType.DOT ||
                    tt == TokenType.LBRACKET ||
                    tt == TokenType.LPAREN
                ));

            if (tt == TokenType.QUESTION_DOT) {
                hasOptionalChaining = true;
            } else if (hasOptionalChaining && !isChainOperator) {
                // We're leaving the chain portion - wrap in ChainExpression first
                chainEndToken = previous();
                left = new ChainExpression(outerStartPos, getEnd(chainEndToken), startToken.line(), startToken.column(), chainEndToken.endLine(), chainEndToken.endColumn(), left);
                hasOptionalChaining = false;
                // Don't update start positions - the ChainExpression is now part of the larger expression
            }

            // Check for illegal mixing of ?? with && or ||
            // Per spec: CoalesceExpression cannot contain LogicalORExpression or LogicalANDExpression
            if (tt == TokenType.QUESTION_QUESTION && inLogicalChain) {
                throw new ExpectedTokenException("Cannot use ?? and && or || together without parentheses", token);
            }
            if ((tt == TokenType.AND || tt == TokenType.OR) && inCoalesceChain) {
                throw new ExpectedTokenException("Cannot use ?? and && or || together without parentheses", token);
            }

            // Update tracking flags BEFORE calling handler (so RHS parsing sees the flag)
            if (tt == TokenType.QUESTION_QUESTION) {
                inCoalesceChain = true;
            } else if (tt == TokenType.AND || tt == TokenType.OR) {
                inLogicalChain = true;
            }

            advance();
            Token opToken = previous();
            // Set instance vars for handler to use, then call handler - inlined for performance
            exprStartPos = outerStartPos;
            exprStartLoc = outerStartLoc;
            left = switch (tt) {
                case COMMA -> infixComma(this, left, opToken);
                case ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN,
                     STAR_STAR_ASSIGN, LEFT_SHIFT_ASSIGN, RIGHT_SHIFT_ASSIGN, UNSIGNED_RIGHT_SHIFT_ASSIGN,
                     BIT_AND_ASSIGN, BIT_OR_ASSIGN, BIT_XOR_ASSIGN, AND_ASSIGN, OR_ASSIGN, QUESTION_QUESTION_ASSIGN -> infixAssignment(this, left, opToken);
                case QUESTION -> infixTernary(this, left, opToken);
                case QUESTION_QUESTION, OR, AND -> infixLogical(this, left, opToken);
                case BIT_OR, BIT_XOR, BIT_AND, EQ, NE, EQ_STRICT, NE_STRICT,
                     LT, LE, GT, GE, INSTANCEOF, IN,
                     LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT,
                     PLUS, MINUS, STAR, SLASH, PERCENT, STAR_STAR -> infixBinary(this, left, opToken);
                case DOT -> infixMember(this, left, opToken);
                case QUESTION_DOT -> infixOptionalChain(this, left, opToken);
                case LBRACKET -> infixComputed(this, left, opToken);
                case LPAREN -> infixCall(this, left, opToken);
                case TEMPLATE_LITERAL, TEMPLATE_HEAD -> infixTaggedTemplate(this, left, opToken);
                default -> left; // unreachable due to lbp check above
            };
            // Note: handler may have overwritten exprStartPos/exprStartLoc via recursive parseExpr calls,
            // but we have our local outerStartPos/outerStartLoc preserved
        }

        // Wrap in ChainExpression if we still have optional chaining at the end
        if (hasOptionalChaining) {
            Token endToken = previous();
            left = new ChainExpression(outerStartPos, getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), left);
        }

        // Restore coalesce/logical chain flags if we saved them
        if (shouldResetChainFlags) {
            inCoalesceChain = savedInCoalesceChain;
            inLogicalChain = savedInLogicalChain;
        }

        return left;
    }

    // ========================================================================
    // Prefix Handlers (NUD)
    // ========================================================================

    private static Expression prefixNumber(Parser p, Token token) {
        String lexeme = token.lexeme();

        // Check for BigInt literal (ends with 'n')
        if (lexeme.endsWith("n")) {
            String bigintValue = lexeme.substring(0, lexeme.length() - 1).replace("_", "");
            // Convert hex/octal/binary to decimal
            if (bigintValue.startsWith("0x") || bigintValue.startsWith("0X")) {
                try {
                    java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 16);
                    bigintValue = bi.toString();
                } catch (NumberFormatException e) { /* keep original */ }
            } else if (bigintValue.startsWith("0o") || bigintValue.startsWith("0O")) {
                try {
                    java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 8);
                    bigintValue = bi.toString();
                } catch (NumberFormatException e) { /* keep original */ }
            } else if (bigintValue.startsWith("0b") || bigintValue.startsWith("0B")) {
                try {
                    java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 2);
                    bigintValue = bi.toString();
                } catch (NumberFormatException e) { /* keep original */ }
            }
            // For BigInt literals, Acorn sets value to null
            return new Literal(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn(), null, lexeme, null, bigintValue);
        }

        // Handle Infinity/-Infinity/NaN - value should be null per ESTree spec
        Object literalValue = token.literal();
        if (literalValue instanceof Double d && (d.isInfinite() || d.isNaN())) {
            literalValue = null;
        }
        return new Literal(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn(), literalValue, token.lexeme());
    }

    private static Expression prefixString(Parser p, Token token) {
        return new Literal(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn(), token.literal(), token.lexeme());
    }

    private static Expression prefixTrue(Parser p, Token token) {
        return new Literal(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn(), true, "true");
    }

    private static Expression prefixFalse(Parser p, Token token) {
        return new Literal(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn(), false, "false");
    }

    private static Expression prefixNull(Parser p, Token token) {
        return new Literal(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn(), null, "null");
    }

    private static Expression prefixRegex(Parser p, Token token) {
        // Value is {} (empty object), the actual regex info goes in the 'regex' field
        Literal.RegexInfo regexInfo = (Literal.RegexInfo) token.literal();
        return new Literal(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn(),
            java.util.Collections.emptyMap(), token.lexeme(), regexInfo);
    }

    private static Expression prefixIdentifier(Parser p, Token token) {
        // Check if this identifier's lexeme (StringValue) matches a reserved word
        // This catches cases like nul\u006c which tokenizes as IDENTIFIER but has lexeme "null"
        String lexeme = token.lexeme();
        if (lexeme.equals("null") || lexeme.equals("true") || lexeme.equals("false")) {
            throw new ExpectedTokenException("'" + lexeme + "' is a reserved word and cannot be used as an identifier", token);
        }

        // Check for dynamic import: im\u0070ort('./module.js')
        // If lexeme is "import" and followed by '(' or '.', it's a dynamic import or import.meta with escapes
        if (lexeme.equals("import") && (p.check(TokenType.LPAREN) || p.check(TokenType.DOT))) {
            if (p.tokenContainsEscapes(token)) {
                throw new ExpectedTokenException("'import' keyword must not contain Unicode escape sequences", token);
            }
            // If no escapes, parse as import expression (shouldn't happen since real import would have IMPORT token type)
            return p.parseImportExpression(token);
        }

        // Check for n\u0065w.target (escaped 'new' in new.target)
        // If lexeme is "new" and followed by '.target', it's new.target with escapes
        if (lexeme.equals("new") && p.check(TokenType.DOT)) {
            if (p.current + 1 < p.tokens.size()) {
                Token maybeTarget = p.tokens.get(p.current + 1);
                if (maybeTarget.type() == TokenType.IDENTIFIER && maybeTarget.lexeme().equals("target")) {
                    // This is an attempt to use new.target with escaped 'new'
                    if (p.tokenContainsEscapes(token)) {
                        throw new ExpectedTokenException("'new' keyword must not contain Unicode escape sequences", token);
                    }
                }
            }
        }

        // Check for async function expression
        // 'async' keyword must not contain Unicode escape sequences
        if (token.lexeme().equals("async") && p.current < p.tokens.size() &&
            p.tokens.get(p.current).type() == TokenType.FUNCTION &&
            token.line() == p.tokens.get(p.current).line()) {
            if (p.tokenContainsEscapes(token)) {
                throw new ExpectedTokenException("'async' keyword must not contain Unicode escape sequences", token);
            }
            return p.parseAsyncFunctionExpressionFromIdentifier(token);
        }

        // In module code, 'await' is ALWAYS a reserved keyword everywhere
        if (p.forceModuleMode && token.lexeme().equals("await")) {
            throw new ParseException("SyntaxError", token, null, null,
                "Unexpected use of 'await' as identifier in module code");
        }
        // In async functions (script mode), 'await' is reserved except in class field initializers
        if (p.inAsyncContext && !p.inClassFieldInitializer && token.lexeme().equals("await")) {
            throw new ParseException("SyntaxError", token, null, null,
                "Unexpected use of 'await' as identifier in async function");
        }
        // In static blocks, 'await' is reserved (except inside nested functions)
        if (p.inStaticBlock && !p.inFunction && token.lexeme().equals("await")) {
            throw new ParseException("SyntaxError", token, null, null,
                "Unexpected use of 'await' as identifier in class static block");
        }

        // 'yield' is reserved inside generators and in strict mode
        if (token.lexeme().equals("yield")) {
            if (p.inGenerator) {
                throw new ParseException("SyntaxError", token, null, null,
                    "Unexpected use of 'yield' as identifier in generator");
            }
            if (p.strictMode) {
                throw new ExpectedTokenException("'yield' is a reserved identifier in strict mode", token);
            }
        }

        // 'arguments' is forbidden in class field initializers (arrow functions inherit this restriction)
        if (token.lexeme().equals("arguments") && p.inClassFieldInitializer) {
            throw new ExpectedTokenException("'arguments' is not allowed in class field initializer", token);
        }

        // 'arguments' is forbidden in static blocks (except inside nested functions)
        // Check the original identifier even if it contains unicode escapes
        if (p.inStaticBlock && !p.inFunction) {
            String name = token.lexeme();
            // Also check for escaped 'arguments' - the lexeme contains the unescaped name
            if (name.equals("arguments")) {
                throw new ExpectedTokenException("'arguments' is not allowed in class static block", token);
            }
        }

        return new Identifier(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn(), token.lexeme());
    }

    private static Expression prefixThis(Parser p, Token token) {
        return new ThisExpression(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn());
    }

    private static Expression prefixSuper(Parser p, Token token) {
        // 'super' must be followed by '.', '[', or '(' (for super.property, super[expr], or super())
        // It cannot be used as a standalone expression
        if (!p.check(TokenType.DOT) && !p.check(TokenType.LBRACKET) && !p.check(TokenType.LPAREN) && !p.check(TokenType.QUESTION_DOT)) {
            throw new ExpectedTokenException("'super' keyword unexpected here", token);
        }
        return new Super(p.getStart(token), p.getEnd(token), token.line(), token.column(), token.endLine(), token.endColumn());
    }

    private static Expression prefixGroupedOrArrow(Parser p, Token token) {
        // This is called when we see '(' and it's NOT an arrow function
        // (arrow functions are handled in tryParseArrowFunction before prefix dispatch)
        // So this is a grouped/parenthesized expression

        // Record the start position of the parenthesized expression
        int parenStart = p.getStart(token);

        // Save and enable allowIn - inside parentheses, 'in' is always allowed as an operator
        // This is important for cases like: for (var x = (a in b) ? 1 : 2; ...)
        boolean oldAllowIn = p.allowIn;
        p.allowIn = true;
        Expression expr = p.parseExpr(BP_COMMA);
        p.allowIn = oldAllowIn;

        p.consume(TokenType.RPAREN, "Expected ')' after expression");

        // Mark that this expression was parenthesized (for directive detection)
        p.lastExpressionWasParenthesized = true;

        // Track if this parenthesized expression is NOT a valid simple assignment target
        // This is used to detect invalid cases like `({}) = 1` or `() => ({}) = 1`
        if (p.parenthesizedNonSimpleTarget < 0 && !p.isSimpleAssignmentTarget(expr)) {
            p.parenthesizedNonSimpleTarget = parenStart;
        }

        return expr;
    }

    private static Expression prefixArray(Parser p, Token token) {
        return p.parseArrayLiteral(token);
    }

    private static Expression prefixObject(Parser p, Token token) {
        return p.parseObjectLiteral(token);
    }

    private static Expression prefixFunction(Parser p, Token token) {
        return p.parseFunctionExpression(token, false);
    }

    private static Expression prefixClass(Parser p, Token token) {
        return p.parseClassExpression(token);
    }

    private static Expression prefixNew(Parser p, Token token) {
        return p.parseNewExpression(token);
    }

    private static Expression prefixUnary(Parser p, Token token) {
        Expression argument = p.parseExpr(BP_UNARY);
        Token endToken = p.previous();

        // Strict mode validation: delete on identifiers is not allowed
        if (p.strictMode && token.type() == TokenType.DELETE && argument instanceof Identifier) {
            throw new ExpectedTokenException("Delete of an unqualified identifier is not allowed in strict mode", token);
        }

        // Delete on private fields is not allowed (in strict mode, which includes class bodies)
        if (p.strictMode && token.type() == TokenType.DELETE && containsPrivateIdentifier(argument)) {
            throw new ExpectedTokenException("Private fields can not be deleted", token);
        }

        return new UnaryExpression(p.getStart(token), p.getEnd(endToken), token.line(), token.column(), endToken.endLine(), endToken.endColumn(), token.lexeme(), true, argument);
    }

    private static Expression prefixUpdate(Parser p, Token token) {
        Expression argument = p.parseExpr(BP_UNARY);
        Token endToken = p.previous();

        // Validate that the argument is a valid simple assignment target
        p.validateSimpleAssignmentTarget(argument, token);

        return new UpdateExpression(p.getStart(token), p.getEnd(endToken), token.line(), token.column(), endToken.endLine(), endToken.endColumn(), token.lexeme(), true, argument);
    }

    private static Expression prefixTemplate(Parser p, Token token) {
        // Back up one token since parseTemplateLiteral expects to start at the template token
        p.current--;
        return p.parseTemplateLiteral(false); // false = not tagged, so invalid escapes are errors
    }

    private static Expression prefixImport(Parser p, Token token) {
        return p.parseImportExpression(token);
    }

    private static Expression prefixPrivateIdentifier(Parser p, Token token) {
        Token nameToken = p.peek();
        // No whitespace allowed between # and identifier
        if (token.endPosition() != nameToken.position()) {
            throw new ExpectedTokenException("identifier immediately after '#' (no whitespace allowed)", nameToken);
        }
        if (!p.check(TokenType.IDENTIFIER) && !p.isKeyword(nameToken)) {
            throw new ExpectedTokenException("identifier after '#'", p.peek());
        }
        p.advance();
        // Record private name reference for AllPrivateNamesValid validation
        p.recordPrivateNameReference(nameToken.lexeme(), token);
        return new PrivateIdentifier(p.getStart(token), p.getEnd(nameToken), token.line(), token.column(), nameToken.endLine(), nameToken.endColumn(), nameToken.lexeme());
    }

    // ========================================================================
    // Infix Handlers (LED)
    // ========================================================================

    private static Expression infixComma(Parser p, Expression left, Token op) {
        // Save outer expression start before recursive calls
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        List<Expression> expressions = new ArrayList<>();
        expressions.add(left);

        // First comma already consumed, parse rest
        expressions.add(p.parseExpr(BP_COMMA + 1)); // Don't allow further commas at same level

        // Continue parsing more comma-separated expressions
        while (p.match(TokenType.COMMA)) {
            expressions.add(p.parseExpr(BP_COMMA + 1));
        }

        Token endToken = p.previous();
        int endPos = p.getEnd(endToken);
        return new SequenceExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), expressions);
    }

    private static Expression infixAssignment(Parser p, Expression left, Token op) {
        // Save outer expression start before recursive calls
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        // Validate that the left-hand side is a valid assignment target
        boolean isSimpleAssignment = op.type() == TokenType.ASSIGN;
        p.validateAssignmentLHS(left, op, isSimpleAssignment);

        // Reset parenthesizedNonSimpleTarget after LHS validation
        // This ensures we don't incorrectly affect RHS parsing
        p.parenthesizedNonSimpleTarget = -1;

        Expression right = p.parseExpr(BP_ASSIGNMENT); // Right-associative
        Token endToken = p.previous();

        // Convert left side to pattern if it's a destructuring target
        Node leftNode = p.convertToPatternIfNeeded(left);

        int endPos = p.getEnd(endToken);
        return new AssignmentExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), op.lexeme(), leftNode, right);
    }

    private static Expression infixTernary(Parser p, Expression test, Token question) {
        // Save outer expression start before recursive calls
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        // Per spec: ConditionalExpression[In] : ... ? AssignmentExpression[+In] : AssignmentExpression[?In]
        // The middle branch (consequent) always allows 'in', but the else branch (alternate) inherits
        boolean oldAllowIn = p.allowIn;
        p.allowIn = true;  // [+In] for consequent
        Expression consequent = p.parseExpr(BP_ASSIGNMENT);
        p.consume(TokenType.COLON, "Expected ':'");
        p.allowIn = oldAllowIn;  // Restore [?In] for alternate
        Expression alternate = p.parseExpr(BP_ASSIGNMENT);

        Token endToken = p.previous();
        int endPos = p.getEnd(endToken);
        return new ConditionalExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), test, consequent, alternate);
    }

    private static Expression infixLogical(Parser p, Expression left, Token op) {
        // Save outer expression start before recursive calls
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        // Note: The check for mixing ?? with && or || is done in the infix loop in parseExpr,
        // not here, because we need to track whether we've seen these operators at the SAME level
        // (parentheses create a new level where mixing is allowed)

        // Logical operators are left-associative: RBP = LBP + 1
        int rbp = switch (op.type()) {
            case QUESTION_QUESTION -> BP_NULLISH + 1;
            case OR -> BP_OR + 1;
            case AND -> BP_AND + 1;
            default -> BP_AND + 1; // should not happen
        };
        Expression right = p.parseExpr(rbp);

        Token endToken = p.previous();
        int endPos = p.getEnd(endToken);
        return new LogicalExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), left, op.lexeme(), right);
    }

    private static Expression infixBinary(Parser p, Expression left, Token op) {
        // Save outer expression start before recursive calls
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        // Exponentiation operator (**) cannot have a UnaryExpression on its left side
        // Only UpdateExpression is allowed (per spec: UpdateExpression ** ExponentiationExpression)
        // BUT: if the unary is wrapped in parens like (-1n) ** 2, it IS allowed because parens disambiguate
        // We detect this by comparing exprStartPos (position of overall expression start, which includes parens)
        // with left.start() (position of the unary operator itself, which is after the parens)
        if (op.type() == TokenType.STAR_STAR) {
            if (left instanceof UnaryExpression ue && p.exprStartPos == left.start()) {
                // delete, typeof, void, +, -, ~, ! are all UnaryExpression operators
                throw new ExpectedTokenException("Unary operator used immediately before exponentiation expression. " +
                    "Parenthesize the left-hand side to disambiguate: (" + ue.operator() + " x) ** y", op);
            }
            // AwaitExpression is also not allowed directly before **
            if (left instanceof AwaitExpression && p.exprStartPos == left.start()) {
                throw new ExpectedTokenException("Unary operator used immediately before exponentiation expression. " +
                    "Parenthesize the left-hand side to disambiguate: (await x) ** y", op);
            }
        }

        // Get RBP based on operator - most are left-associative (RBP = LBP + 1)
        // Only ** is right-associative (RBP = LBP)
        // Special case: `#field in expr` requires RHS to be ShiftExpression (BP_SHIFT + 1)
        int rbp = switch (op.type()) {
            case BIT_OR -> BP_BIT_OR + 1;
            case BIT_XOR -> BP_BIT_XOR + 1;
            case BIT_AND -> BP_BIT_AND + 1;
            case EQ, NE, EQ_STRICT, NE_STRICT -> BP_EQUALITY + 1;
            case LT, LE, GT, GE, INSTANCEOF -> BP_RELATIONAL + 1;
            case IN -> BP_RELATIONAL + 1;
            case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> BP_SHIFT + 1;
            case PLUS, MINUS -> BP_ADDITIVE + 1;
            case STAR, SLASH, PERCENT -> BP_MULTIPLICATIVE + 1;
            case STAR_STAR -> BP_EXPONENT; // right-associative
            default -> BP_ADDITIVE + 1; // should not happen
        };
        // Capture RHS start position before parsing to detect if it's wrapped in parens
        int rhsStart = p.getStart(p.peek());
        Expression right = p.parseExpr(rbp);

        // When PrivateIdentifier in expr, the RHS must be a ShiftExpression
        // ArrowFunctionExpression (bare) is NOT a ShiftExpression, but (arrow) wrapped in parens IS
        // (because parens are a PrimaryExpression which is part of ShiftExpression)
        if (left instanceof PrivateIdentifier && op.type() == TokenType.IN) {
            if (right instanceof PrivateIdentifier) {
                throw new ExpectedTokenException("Invalid right-hand side in 'in' expression: private identifier", op);
            }
            // Bare arrow functions are not valid RHS, but parenthesized ones are
            if (right instanceof ArrowFunctionExpression && right.start() == rhsStart) {
                throw new ExpectedTokenException("Invalid right-hand side in 'in' expression", op);
            }
        }

        Token endToken = p.previous();
        int endPos = p.getEnd(endToken);
        return new BinaryExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), left, op.lexeme(), right);
    }

    private static Expression infixMember(Parser p, Expression object, Token dot) {
        // super.property is only valid inside methods
        if (object instanceof Super && !p.allowSuperProperty) {
            throw new ExpectedTokenException("'super' keyword is only allowed in class methods", dot);
        }

        if (p.match(TokenType.HASH)) {
            // Private field: obj.#x
            // super.#x is never valid - private names cannot be accessed through super
            if (object instanceof Super) {
                throw new ExpectedTokenException("Private fields cannot be accessed through 'super'", p.previous());
            }
            Token hashToken = p.previous();
            Token propertyToken = p.peek();
            // No whitespace allowed between # and identifier
            if (hashToken.endPosition() != propertyToken.position()) {
                throw new ExpectedTokenException("identifier immediately after '#' (no whitespace allowed)", propertyToken);
            }
            if (!p.check(TokenType.IDENTIFIER) && !p.isKeyword(propertyToken)) {
                throw new ExpectedTokenException("identifier after '#'", p.peek());
            }
            p.advance();
            // Record private name reference for AllPrivateNamesValid validation
            p.recordPrivateNameReference(propertyToken.lexeme(), hashToken);
            Expression property = new PrivateIdentifier(p.getStart(hashToken), p.getEnd(propertyToken),
                hashToken.line(), hashToken.column(), propertyToken.endLine(), propertyToken.endColumn(), propertyToken.lexeme());
            Token endToken = p.previous();
            int endPos = p.getEnd(endToken);
            return new MemberExpression(p.exprStartPos, endPos, p.exprStartLoc.line(), p.exprStartLoc.column(), endToken.endLine(), endToken.endColumn(), object, property, false, false);
        } else {
            // Regular property: obj.x
            // IdentifierName is required after '.', not string or number literals
            Token propertyToken = p.peek();
            if (!p.check(TokenType.IDENTIFIER) && !p.isKeyword(propertyToken) &&
                !p.check(TokenType.TRUE) && !p.check(TokenType.FALSE) && !p.check(TokenType.NULL)) {
                throw new ExpectedTokenException("identifier name after '.'", p.peek());
            }
            p.advance();
            Expression property = new Identifier(p.getStart(propertyToken), p.getEnd(propertyToken),
                propertyToken.line(), propertyToken.column(), propertyToken.endLine(), propertyToken.endColumn(), propertyToken.lexeme());
            Token endToken = p.previous();
            int endPos = p.getEnd(endToken);
            return new MemberExpression(p.exprStartPos, endPos, p.exprStartLoc.line(), p.exprStartLoc.column(), endToken.endLine(), endToken.endColumn(), object, property, false, false);
        }
    }

    private static Expression infixOptionalChain(Parser p, Expression object, Token questionDot) {
        // Save outer expression start before any recursive calls
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        if (p.check(TokenType.LPAREN)) {
            // Optional call: obj?.(args)
            p.advance(); // consume (
            List<Expression> args = p.parseArgumentList();
            p.consume(TokenType.RPAREN, "Expected ')' after arguments");
            Token endToken = p.previous();
            int endPos = p.getEnd(endToken);
            return new CallExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), object, args, true);
        } else if (p.check(TokenType.LBRACKET)) {
            // Optional computed: obj?.[expr]
            p.advance(); // consume [
            Expression property = p.parseExpr(BP_COMMA);
            p.consume(TokenType.RBRACKET, "Expected ']' after computed property");
            Token endToken = p.previous();
            int endPos = p.getEnd(endToken);
            return new MemberExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), object, property, true, true);
        } else if (p.match(TokenType.HASH)) {
            // Optional private: obj?.#x
            Token hashToken = p.previous();
            Token propertyToken = p.peek();
            // No whitespace allowed between # and identifier
            if (hashToken.endPosition() != propertyToken.position()) {
                throw new ExpectedTokenException("identifier immediately after '#' (no whitespace allowed)", propertyToken);
            }
            if (!p.check(TokenType.IDENTIFIER) && !p.isKeyword(propertyToken)) {
                throw new ExpectedTokenException("identifier after '#'", p.peek());
            }
            p.advance();
            // Record private name reference for AllPrivateNamesValid validation
            p.recordPrivateNameReference(propertyToken.lexeme(), hashToken);
            Expression property = new PrivateIdentifier(p.getStart(hashToken), p.getEnd(propertyToken),
                hashToken.line(), hashToken.column(), propertyToken.endLine(), propertyToken.endColumn(), propertyToken.lexeme());
            Token endToken = p.previous();
            int endPos = p.getEnd(endToken);
            return new MemberExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), object, property, false, true);
        } else {
            // Optional property: obj?.x
            Token propertyToken = p.peek();
            if (!p.check(TokenType.IDENTIFIER) && !p.isKeyword(propertyToken) &&
                !p.check(TokenType.NUMBER) && !p.check(TokenType.STRING) &&
                !p.check(TokenType.TRUE) && !p.check(TokenType.FALSE) && !p.check(TokenType.NULL)) {
                throw new ExpectedTokenException("property name after '?.'", p.peek());
            }
            p.advance();
            Expression property = new Identifier(p.getStart(propertyToken), p.getEnd(propertyToken),
                propertyToken.line(), propertyToken.column(), propertyToken.endLine(), propertyToken.endColumn(), propertyToken.lexeme());
            Token endToken = p.previous();
            int endPos = p.getEnd(endToken);
            return new MemberExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), object, property, false, true);
        }
    }

    private static Expression infixComputed(Parser p, Expression object, Token lbracket) {
        // super[expr] is only valid inside methods
        if (object instanceof Super && !p.allowSuperProperty) {
            throw new ExpectedTokenException("'super' keyword is only allowed in class methods", lbracket);
        }

        // Save outer expression start before recursive parseExpr call
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        Expression property = p.parseExpr(BP_COMMA);
        p.consume(TokenType.RBRACKET, "Expected ']' after computed property");
        Token endToken = p.previous();
        int endPos = p.getEnd(endToken);
        return new MemberExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), object, property, true, false);
    }

    private static Expression infixCall(Parser p, Expression callee, Token lparen) {
        // super() is only valid inside constructors of derived classes
        if (callee instanceof Super) {
            if (!p.allowSuperCall) {
                throw new ExpectedTokenException("'super()' is only allowed in class constructor", lparen);
            }
            if (!p.inDerivedClass) {
                throw new ExpectedTokenException("'super()' is only valid in a derived class constructor", lparen);
            }
        }

        // Save outer expression start before recursive parseArgumentList calls parseExpr
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        List<Expression> args = p.parseArgumentList();
        p.consume(TokenType.RPAREN, "Expected ')' after arguments");
        Token endToken = p.previous();
        int endPos = p.getEnd(endToken);
        return new CallExpression(savedStartPos, endPos, savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), callee, args, false);
    }

    private static Expression infixTaggedTemplate(Parser p, Expression tag, Token templateStart) {
        // Save outer expression start before parseTemplateLiteral which may call parseExpr for interpolations
        int savedStartPos = p.exprStartPos;
        SourceLocation.Position savedStartLoc = p.exprStartLoc;

        // Back up one token since parseTemplateLiteral expects to start at the template token
        p.current--;
        Expression template = p.parseTemplateLiteral(true); // true = tagged template, invalid escapes allowed
        Token endToken = p.previous();
        return new TaggedTemplateExpression(savedStartPos, template.end(), savedStartLoc.line(), savedStartLoc.column(), endToken.endLine(), endToken.endColumn(), tag, (TemplateLiteral) template);
    }

    // ========================================================================
    // Helper Methods for Pratt Parser
    // ========================================================================

    private boolean shouldParseAwait(int minBp) {
        if (!check(TokenType.IDENTIFIER) || !peek().lexeme().equals("await")) {
            return false;
        }
        // 'await' keyword must not contain Unicode escape sequences
        if (tokenContainsEscapes(peek())) {
            return false;
        }

        if (inAsyncContext && !inClassFieldInitializer && !inFormalParameters) {
            // Await expressions are not allowed in formal parameter defaults
            // At assignment level or below, await with no operand is a valid await expression
            // At higher binding powers (e.g., operand of void), treat as identifier which will fail validation
            boolean followedByTerminator = checkAhead(1, TokenType.SEMICOLON) ||
                checkAhead(1, TokenType.RBRACE) ||
                checkAhead(1, TokenType.RPAREN) ||
                checkAhead(1, TokenType.RBRACKET) ||
                checkAhead(1, TokenType.COMMA) ||
                checkAhead(1, TokenType.COLON) ||
                checkAhead(1, TokenType.EOF);
            if (followedByTerminator && minBp > BP_ASSIGNMENT) {
                // At higher BP and followed by terminator - not an await expression
                return false;
            }
            return true;
        }

        // Top-level await: only at actual module top level, not inside functions
        if (!inAsyncContext && forceModuleMode && atModuleTopLevel && !inClassFieldInitializer) {
            return !checkAhead(1, TokenType.COLON) &&
                   !checkAhead(1, TokenType.ASSIGN) &&
                   !checkAhead(1, TokenType.PLUS_ASSIGN) &&
                   !checkAhead(1, TokenType.MINUS_ASSIGN);
        }

        // Class field initializer validation
        if (inClassFieldInitializer) {
            boolean looksLikeAwaitExpression = checkAhead(1, TokenType.IDENTIFIER) ||
                                               checkAhead(1, TokenType.LPAREN) ||
                                               checkAhead(1, TokenType.LBRACKET) ||
                                               checkAhead(1, TokenType.THIS) ||
                                               checkAhead(1, TokenType.SUPER) ||
                                               checkAhead(1, TokenType.NEW) ||
                                               checkAhead(1, TokenType.CLASS) ||
                                               checkAhead(1, TokenType.FUNCTION) ||
                                               checkAhead(1, TokenType.ASYNC) ||
                                               checkAhead(1, TokenType.STRING) ||
                                               checkAhead(1, TokenType.NUMBER) ||
                                               checkAhead(1, TokenType.TRUE) ||
                                               checkAhead(1, TokenType.FALSE) ||
                                               checkAhead(1, TokenType.NULL);
            if (looksLikeAwaitExpression) {
                throw new ParseException("SyntaxError", peek(), null, null,
                    "Cannot use keyword 'await' outside an async function");
            }
        }

        return false;
    }

    private Expression parseYieldExpr() {
        advance(); // consume 'yield'
        Token yieldToken = previous();
        boolean delegate = false;
        Expression argument = null;

        // [no LineTerminator here] restriction for yield*
        // The * must be on the same line as yield
        boolean hasLineTerminatorBeforeStar = !isAtEnd() && peek().line() > yieldToken.line();
        if (!hasLineTerminatorBeforeStar && match(TokenType.STAR)) {
            delegate = true;
        }

        boolean hasLineTerminator = !delegate && !isAtEnd() && peek().line() > yieldToken.line();
        if (!hasLineTerminator &&
            !check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !check(TokenType.EOF) &&
            !check(TokenType.RPAREN) && !check(TokenType.COMMA) && !check(TokenType.RBRACKET) &&
            !check(TokenType.TEMPLATE_MIDDLE) && !check(TokenType.TEMPLATE_TAIL) &&
            !check(TokenType.COLON)) {
            argument = parseExpr(BP_ASSIGNMENT);
        }

        Token endToken = previous();
        return new YieldExpression(getStart(yieldToken), getEnd(endToken), yieldToken.line(), yieldToken.column(), endToken.endLine(), endToken.endColumn(), delegate, argument);
    }

    private Expression parseAwaitExpr() {
        Token awaitToken = advance();

        // await requires an operand (unlike yield which can be used without)
        if (check(TokenType.SEMICOLON) || check(TokenType.RBRACE) || check(TokenType.EOF) ||
            check(TokenType.RPAREN) || check(TokenType.COMMA) || check(TokenType.RBRACKET) ||
            check(TokenType.INSTANCEOF) || check(TokenType.IN) ||
            check(TokenType.QUESTION) || check(TokenType.COLON)) {
            throw new ExpectedTokenException("expression after await", peek());
        }
        Expression argument = parseExpr(BP_UNARY);

        Token endToken = previous();
        return new AwaitExpression(getStart(awaitToken), getEnd(endToken), awaitToken.line(), awaitToken.column(), endToken.endLine(), endToken.endColumn(), argument);
    }

    private Expression tryParseArrowFunction(Token startToken) {
        // Check for async arrow function: async identifier => or async (params) =>
        boolean isAsync = false;
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("async")) {
            if (current + 1 < tokens.size()) {
                Token asyncToken = peek();
                Token nextToken = tokens.get(current + 1);

                if (asyncToken.line() != nextToken.line()) {
                    // Line terminator - not async arrow
                    return null;
                }

                if (nextToken.type() == TokenType.IDENTIFIER) {
                    if (current + 2 < tokens.size() && tokens.get(current + 2).type() == TokenType.ARROW) {
                        // 'async' keyword must not contain Unicode escape sequences
                        if (tokenContainsEscapes(asyncToken)) {
                            throw new ExpectedTokenException("'async' keyword must not contain Unicode escape sequences", asyncToken);
                        }
                        advance(); // consume 'async'
                        isAsync = true;
                    }
                } else if (nextToken.type() == TokenType.LPAREN) {
                    int savedCurrent = current;
                    advance(); // consume 'async'
                    advance(); // consume '('
                    boolean isArrow = isArrowFunctionParameters();
                    current = savedCurrent;

                    if (isArrow) {
                        // 'async' keyword must not contain Unicode escape sequences
                        if (tokenContainsEscapes(asyncToken)) {
                            throw new ExpectedTokenException("'async' keyword must not contain Unicode escape sequences", asyncToken);
                        }
                        advance(); // consume 'async'
                        isAsync = true;
                    }
                }
            }
        }

        // Check for simple arrow: identifier => or (params) =>
        if ((check(TokenType.IDENTIFIER) || check(TokenType.OF) || check(TokenType.LET))) {
            Token idToken = peek();
            if (current + 1 < tokens.size() && tokens.get(current + 1).type() == TokenType.ARROW) {
                // Check for no line terminator between identifier and =>
                Token arrowToken = tokens.get(current + 1);
                if (idToken.line() != arrowToken.line()) {
                    return null; // Line terminator - not arrow function
                }
                // Validate that the identifier is not a reserved word for parameter context
                String paramName = idToken.lexeme();
                // In strict mode, yield is reserved
                if (strictMode && paramName.equals("yield")) {
                    throw new ExpectedTokenException("'yield' is not allowed as a parameter name in strict mode", idToken);
                }
                // In generators, yield cannot be a parameter name
                if (inGenerator && paramName.equals("yield")) {
                    throw new ExpectedTokenException("'yield' is not allowed as a parameter name in generator functions", idToken);
                }
                // await cannot be a parameter name in async functions or modules
                if ((inAsyncContext || forceModuleMode) && paramName.equals("await")) {
                    throw new ExpectedTokenException("'await' is not allowed as a parameter name", idToken);
                }
                advance(); // consume identifier
                List<Pattern> params = new ArrayList<>();
                params.add(new Identifier(getStart(idToken), getEnd(idToken), idToken.line(), idToken.column(), idToken.endLine(), idToken.endColumn(), paramName));
                consume(TokenType.ARROW, "Expected '=>'");
                return parseArrowFunctionBody(startToken, params, isAsync);
            }
        }

        // Check for parenthesized arrow: (params) =>
        if (check(TokenType.LPAREN)) {
            int savedCurrent = current;
            advance(); // consume (

            boolean isArrow = isArrowFunctionParameters();

            if (isArrow) {
                // Set inFormalParameters to prevent yield/await expressions in parameter defaults
                boolean savedInFormalParameters = inFormalParameters;
                inFormalParameters = true;

                // For async arrow functions, set inAsyncContext BEFORE parsing parameters
                // so that 'await' is properly detected as invalid parameter name
                boolean savedInAsyncContext = inAsyncContext;
                if (isAsync) {
                    inAsyncContext = true;
                }

                List<Pattern> params = new ArrayList<>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        if (check(TokenType.RPAREN)) break;
                        if (match(TokenType.DOT_DOT_DOT)) {
                            Token restStart = previous();
                            Pattern argument = parsePatternBase();
                            Token restEnd = previous();
                            params.add(new RestElement(getStart(restStart), getEnd(restEnd), restStart.line(), restStart.column(), restEnd.endLine(), restEnd.endColumn(), argument));
                            if (match(TokenType.COMMA)) {
                                throw new ParseException("ValidationError", peek(), null, "parameter list", "Rest parameter must be last");
                            }
                            break;
                        } else {
                            params.add(parsePattern());
                        }
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RPAREN, "Expected ')' after parameters");
                inFormalParameters = savedInFormalParameters;
                inAsyncContext = savedInAsyncContext; // Restore before parseArrowFunctionBody sets it again
                consume(TokenType.ARROW, "Expected '=>'");
                return parseArrowFunctionBody(startToken, params, isAsync);
            } else {
                current = savedCurrent;
            }
        }

        return null;
    }

    private List<Expression> parseArgumentList() {
        List<Expression> args = new ArrayList<>();
        // Arguments to function calls should always allow 'in' operator
        boolean savedAllowIn = allowIn;
        allowIn = true;
        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.RPAREN)) break; // trailing comma
                // Reset parenthesizedNonSimpleTarget for each argument
                // The call's parentheses are not "parenthesizing" the argument
                parenthesizedNonSimpleTarget = -1;
                if (match(TokenType.DOT_DOT_DOT)) {
                    Token spreadStart = previous();
                    Expression argument = parseExpr(BP_ASSIGNMENT);
                    Token spreadEnd = previous();
                    args.add(new SpreadElement(getStart(spreadStart), getEnd(spreadEnd), spreadStart.line(), spreadStart.column(), spreadEnd.endLine(), spreadEnd.endColumn(), argument));
                } else {
                    args.add(parseExpr(BP_ASSIGNMENT));
                }
            } while (match(TokenType.COMMA));
        }
        allowIn = savedAllowIn;
        return args;
    }

    private SourceLocation createLocationFromPositions(int start, int end, SourceLocation.Position startPos, Token endToken) {
        // Use endLine/endColumn from token for accurate multi-line token support
        SourceLocation.Position endPos = new SourceLocation.Position(endToken.endLine(), endToken.endColumn());
        return new SourceLocation(startPos, endPos);
    }

    private Expression parseAsyncFunctionExpressionFromIdentifier(Token asyncToken) {
        // 'async' keyword must not contain Unicode escape sequences
        if (tokenContainsEscapes(asyncToken)) {
            throw new ExpectedTokenException("'async' keyword must not contain Unicode escape sequences", asyncToken);
        }
        advance(); // consume 'function'

        boolean isGenerator = match(TokenType.STAR);

        Identifier id = null;
        if (check(TokenType.IDENTIFIER)) {
            Token nameToken = peek();
            advance();
            String functionName = nameToken.lexeme();
            // In strict mode, 'eval' and 'arguments' cannot be function names
            if (strictMode && (functionName.equals("eval") || functionName.equals("arguments"))) {
                throw new ExpectedTokenException("'" + functionName + "' is not allowed as a function name in strict mode", nameToken);
            }
            // 'yield' cannot be used as a function name in strict mode
            if (strictMode && functionName.equals("yield")) {
                throw new ExpectedTokenException("'yield' is not allowed as a function name in strict mode", nameToken);
            }
            // Async generator functions cannot use 'yield' as their name (even in non-strict mode)
            if (isGenerator && functionName.equals("yield")) {
                throw new ExpectedTokenException("'yield' is not allowed as a generator function name", nameToken);
            }
            // 'await' cannot be used as an async function name
            if (functionName.equals("await")) {
                throw new ExpectedTokenException("'await' is not allowed as an async function name", nameToken);
            }
            id = new Identifier(getStart(nameToken), getEnd(nameToken), nameToken.line(), nameToken.column(), nameToken.endLine(), nameToken.endColumn(), functionName);
        }

        consume(TokenType.LPAREN, "Expected '(' after function");

        // Save and set generator/async context BEFORE parsing parameters
        // Default parameter initializers use the inner function's context
        boolean savedInGenerator = inGenerator;
        boolean savedInAsyncContext = inAsyncContext;
        boolean savedStrictMode = strictMode;
        boolean savedInClassFieldInitializer = inClassFieldInitializer;
        boolean savedInFunction = inFunction;
        boolean savedAllowNewTarget = allowNewTarget;
        boolean savedAllowSuperProperty = allowSuperProperty;
        boolean savedAllowSuperCall = allowSuperCall;
        boolean savedAtModuleTopLevel = atModuleTopLevel;
        int savedLoopDepth = loopDepth;
        int savedSwitchDepth = switchDepth;
        java.util.Map<String, Boolean> savedLabelMap = new java.util.HashMap<>(labelMap);
        inGenerator = isGenerator;
        inAsyncContext = true;
        inClassFieldInitializer = false;
        inFunction = true;
        allowNewTarget = true; // Regular async function expressions allow new.target
        allowSuperProperty = false; // Regular async function expressions don't allow super
        allowSuperCall = false;
        atModuleTopLevel = false; // Functions don't inherit top-level await
        loopDepth = 0; // break/continue don't cross function boundaries
        switchDepth = 0;
        labelMap.clear(); // Labels don't cross function boundaries

        // Push a function scope for the async function expression
        pushScope(true);

        List<Pattern> params = new ArrayList<>();

        // Yield/await expressions are not allowed in parameter defaults
        boolean savedInFormalParameters = inFormalParameters;
        inFormalParameters = true;

        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.RPAREN)) break;
                if (match(TokenType.DOT_DOT_DOT)) {
                    Token restStart = previous();
                    Pattern argument = parsePatternBase();
                    Token restEnd = previous();
                    params.add(new RestElement(getStart(restStart), getEnd(restEnd), restStart.line(), restStart.column(), restEnd.endLine(), restEnd.endColumn(), argument));
                    if (match(TokenType.COMMA)) {
                        throw new ParseException("ValidationError", peek(), null, "parameter list", "Rest parameter must be last");
                    }
                    break;
                } else {
                    params.add(parsePattern());
                }
            } while (match(TokenType.COMMA));
        }

        inFormalParameters = savedInFormalParameters;
        consume(TokenType.RPAREN, "Expected ')' after parameters");

        // Strict mode is inherited from outer scope
        BlockStatement body = parseBlockStatement(true);
        validateStrictBodyWithSimpleParams(params, body, asyncToken);
        validateNoDuplicateParameters(params, asyncToken);
        validateNoParamBodyConflicts(params, body, asyncToken);

        // Pop the function scope
        popScope();

        inGenerator = savedInGenerator;
        inAsyncContext = savedInAsyncContext;
        strictMode = savedStrictMode;
        inClassFieldInitializer = savedInClassFieldInitializer;
        inFunction = savedInFunction;
        allowNewTarget = savedAllowNewTarget;
        allowSuperProperty = savedAllowSuperProperty;
        allowSuperCall = savedAllowSuperCall;
        atModuleTopLevel = savedAtModuleTopLevel;
        loopDepth = savedLoopDepth;
        switchDepth = savedSwitchDepth;
        labelMap.clear();
        labelMap.putAll(savedLabelMap);

        Token endToken = previous();
        return new FunctionExpression(getStart(asyncToken), getEnd(endToken), asyncToken.line(), asyncToken.column(), endToken.endLine(), endToken.endColumn(), id, false, isGenerator, true, params, body);
    }

    private Expression parseImportExpression(Token importToken) {
        // 'import' must not contain Unicode escape sequences
        if (tokenContainsEscapes(importToken)) {
            throw new ExpectedTokenException("'import' keyword must not contain Unicode escape sequences", importToken);
        }

        if (match(TokenType.DOT)) {
            Token propertyToken = peek();
            if (check(TokenType.IDENTIFIER) && propertyToken.lexeme().equals("meta")) {
                // import.meta is only allowed in module code
                if (!forceModuleMode) {
                    throw new ExpectedTokenException("'import.meta' is only valid in module code", importToken);
                }
                // 'meta' must not contain escape sequences
                if (tokenContainsEscapes(propertyToken)) {
                    throw new ExpectedTokenException("'meta' keyword must not contain Unicode escape sequences", propertyToken);
                }
                advance();
                Identifier meta = new Identifier(getStart(importToken), getEnd(importToken), importToken.line(), importToken.column(), importToken.endLine(), importToken.endColumn(), "import");
                Identifier property = new Identifier(getStart(propertyToken), getEnd(propertyToken), propertyToken.line(), propertyToken.column(), propertyToken.endLine(), propertyToken.endColumn(), "meta");
                Token endToken = previous();
                return new MetaProperty(getStart(importToken), getEnd(endToken), importToken.line(), importToken.column(), endToken.endLine(), endToken.endColumn(), meta, property);
            }
            throw new ExpectedTokenException("meta", peek());
        }

        // Dynamic import: import(source)
        consume(TokenType.LPAREN, "Expected '(' after import");
        Expression source = parseExpr(BP_ASSIGNMENT);

        // Check for options argument (import attributes)
        Expression options = null;
        if (match(TokenType.COMMA)) {
            if (!check(TokenType.RPAREN)) {
                // Allow 'in' operator in options expression
                boolean savedAllowIn = allowIn;
                allowIn = true;
                options = parseExpr(BP_ASSIGNMENT);
                allowIn = savedAllowIn;
                // Allow trailing comma after second argument: import(source, options,)
                match(TokenType.COMMA);
            }
        }

        consume(TokenType.RPAREN, "Expected ')' after import source");
        Token endToken = previous();
        return new ImportExpression(getStart(importToken), getEnd(endToken), importToken.line(), importToken.column(), endToken.endLine(), endToken.endColumn(), source, options);
    }

    private Expression parseNewExpression(Token newToken) {
        // Handle new.target
        if (match(TokenType.DOT)) {
            Token targetToken = peek();
            if (check(TokenType.IDENTIFIER) && targetToken.lexeme().equals("target")) {
                // new.target is only allowed inside regular functions (not arrow functions)
                if (!allowNewTarget) {
                    throw new ExpectedTokenException("'new.target' not allowed outside of a function", newToken);
                }
                // 'new' must not contain escape sequences
                if (tokenContainsEscapes(newToken)) {
                    throw new ExpectedTokenException("'new' keyword must not contain Unicode escape sequences", newToken);
                }
                // 'target' must not contain escape sequences
                if (tokenContainsEscapes(targetToken)) {
                    throw new ExpectedTokenException("'target' keyword must not contain Unicode escape sequences", targetToken);
                }
                advance();
                Identifier meta = new Identifier(getStart(newToken), getEnd(newToken), newToken.line(), newToken.column(), newToken.endLine(), newToken.endColumn(), "new");
                Identifier property = new Identifier(getStart(targetToken), getEnd(targetToken), targetToken.line(), targetToken.column(), targetToken.endLine(), targetToken.endColumn(), "target");
                return new MetaProperty(getStart(newToken), getEnd(targetToken), newToken.line(), newToken.column(), targetToken.endLine(), targetToken.endColumn(), meta, property);
            }
            throw new ExpectedTokenException("target", peek());
        }

        // Parse callee - need to handle member expressions without calls
        Expression callee = parseNewCallee();

        // Optional arguments
        List<Expression> args = new ArrayList<>();
        if (match(TokenType.LPAREN)) {
            args = parseArgumentList();
            consume(TokenType.RPAREN, "Expected ')' after arguments");
        }

        Token endToken = previous();
        return new NewExpression(getStart(newToken), getEnd(endToken), newToken.line(), newToken.column(), endToken.endLine(), endToken.endColumn(), callee, args);
    }

    private Expression parseNewCallee() {
        // Parse primary expression for new callee
        Token token = peek();
        Expression callee;

        if (check(TokenType.NEW)) {
            // Nested new: new new Foo()
            advance();
            callee = parseNewExpression(previous());
        } else {
            // Inline prefix handler dispatch for performance
            advance();
            Token prevToken = previous();
            callee = switch (token.type()) {
                case NUMBER -> prefixNumber(this, prevToken);
                case STRING -> prefixString(this, prevToken);
                case TRUE -> prefixTrue(this, prevToken);
                case FALSE -> prefixFalse(this, prevToken);
                case NULL -> prefixNull(this, prevToken);
                case REGEX -> prefixRegex(this, prevToken);
                case IDENTIFIER -> prefixIdentifier(this, prevToken);
                case THIS -> prefixThis(this, prevToken);
                case SUPER -> prefixSuper(this, prevToken);
                case LPAREN -> prefixGroupedOrArrow(this, prevToken);
                case LBRACKET -> prefixArray(this, prevToken);
                case LBRACE -> prefixObject(this, prevToken);
                case FUNCTION -> prefixFunction(this, prevToken);
                case CLASS -> prefixClass(this, prevToken);
                case TEMPLATE_LITERAL, TEMPLATE_HEAD -> prefixTemplate(this, prevToken);
                case IMPORT -> {
                    // import.meta is valid as new callee (e.g., new import.meta())
                    // but import() dynamic import is not (you can't do new import())
                    if (check(TokenType.DOT)) {
                        yield parseImportExpression(prevToken);
                    }
                    throw new ExpectedTokenException("'new' cannot be used with 'import()'", prevToken);
                }
                default -> throw new UnexpectedTokenException(token, "expression");
            };
        }

        // Parse member access only (no calls for new callee)
        Token startToken = token;
        while (true) {
            if (match(TokenType.DOT)) {
                if (match(TokenType.HASH)) {
                    Token hashToken = previous();
                    Token propertyToken = peek();
                    // No whitespace allowed between # and identifier
                    if (hashToken.endPosition() != propertyToken.position()) {
                        throw new ExpectedTokenException("identifier immediately after '#' (no whitespace allowed)", propertyToken);
                    }
                    if (!check(TokenType.IDENTIFIER) && !isKeyword(propertyToken)) {
                        throw new ExpectedTokenException("identifier after '#'", peek());
                    }
                    advance();
                    // Record private name reference for AllPrivateNamesValid validation
                    recordPrivateNameReference(propertyToken.lexeme(), hashToken);
                    Expression property = new PrivateIdentifier(getStart(hashToken), getEnd(propertyToken),
                        hashToken.line(), hashToken.column(), propertyToken.endLine(), propertyToken.endColumn(), propertyToken.lexeme());
                    Token endToken = previous();
                    callee = new MemberExpression(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), callee, property, false, false);
                } else {
                    Token propertyToken = peek();
                    if (!check(TokenType.IDENTIFIER) && !isKeyword(propertyToken)) {
                        throw new ExpectedTokenException("property name after '.'", peek());
                    }
                    advance();
                    Expression property = new Identifier(getStart(propertyToken), getEnd(propertyToken),
                        propertyToken.line(), propertyToken.column(), propertyToken.endLine(), propertyToken.endColumn(), propertyToken.lexeme());
                    Token endToken = previous();
                    callee = new MemberExpression(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), callee, property, false, false);
                }
            } else if (match(TokenType.LBRACKET)) {
                Expression property = parseExpr(BP_COMMA);
                consume(TokenType.RBRACKET, "Expected ']' after computed property");
                Token endToken = previous();
                callee = new MemberExpression(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), callee, property, true, false);
            } else if (check(TokenType.TEMPLATE_LITERAL) || check(TokenType.TEMPLATE_HEAD)) {
                // Tagged template: new tag`template` should parse as new (tag`template`)
                // parseTemplateLiteral expects current to point at the template token
                Expression template = parseTemplateLiteral(true); // true = tagged template, invalid escapes allowed
                Token endToken = previous();
                callee = new TaggedTemplateExpression(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), callee, (TemplateLiteral) template);
            } else {
                break;
            }
        }

        return callee;
    }

    private Expression parseFunctionExpression(Token functionToken, boolean isGenerator) {
        if (!isGenerator) {
            isGenerator = match(TokenType.STAR);
        }

        Identifier id = null;
        if (check(TokenType.IDENTIFIER)) {
            Token nameToken = peek();
            advance();
            String functionName = nameToken.lexeme();
            // In strict mode, 'eval' and 'arguments' cannot be function names
            if (strictMode && (functionName.equals("eval") || functionName.equals("arguments"))) {
                throw new ExpectedTokenException("'" + functionName + "' is not allowed as a function name in strict mode", nameToken);
            }
            // 'yield' cannot be used as a function name in strict mode
            if (strictMode && functionName.equals("yield")) {
                throw new ExpectedTokenException("'yield' is not allowed as a function name in strict mode", nameToken);
            }
            // Generator functions cannot use 'yield' as their name (even in non-strict mode)
            if (isGenerator && functionName.equals("yield")) {
                throw new ExpectedTokenException("'yield' is not allowed as a generator function name", nameToken);
            }
            id = new Identifier(getStart(nameToken), getEnd(nameToken), nameToken.line(), nameToken.column(), nameToken.endLine(), nameToken.endColumn(), functionName);
        }

        consume(TokenType.LPAREN, "Expected '(' after function");

        // Save and set generator/async context BEFORE parsing parameters
        // Default parameter initializers use the inner function's context, not the outer
        boolean savedInGenerator = inGenerator;
        boolean savedInAsyncContext = inAsyncContext;
        boolean savedStrictMode = strictMode;
        boolean savedInClassFieldInitializer = inClassFieldInitializer;
        boolean savedInFunction = inFunction;
        boolean savedAllowNewTarget = allowNewTarget;
        boolean savedAllowSuperProperty = allowSuperProperty;
        boolean savedAllowSuperCall = allowSuperCall;
        boolean savedAtModuleTopLevel = atModuleTopLevel;
        int savedLoopDepth = loopDepth;
        int savedSwitchDepth = switchDepth;
        java.util.Map<String, Boolean> savedLabelMap = new java.util.HashMap<>(labelMap);
        inGenerator = isGenerator;
        inAsyncContext = false;
        inClassFieldInitializer = false;
        inFunction = true;
        allowNewTarget = true; // Regular function expressions allow new.target
        allowSuperProperty = false; // Regular function expressions don't allow super
        allowSuperCall = false;
        atModuleTopLevel = false; // Functions don't inherit top-level await
        loopDepth = 0; // break/continue don't cross function boundaries
        switchDepth = 0;
        labelMap.clear(); // Labels don't cross function boundaries

        // Push a function scope for the function expression
        pushScope(true);

        List<Pattern> params = new ArrayList<>();

        // Yield/await expressions are not allowed in parameter defaults
        boolean savedInFormalParameters = inFormalParameters;
        inFormalParameters = true;

        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.RPAREN)) break;
                if (match(TokenType.DOT_DOT_DOT)) {
                    Token restStart = previous();
                    Pattern argument = parsePatternBase();
                    Token restEnd = previous();
                    params.add(new RestElement(getStart(restStart), getEnd(restEnd), restStart.line(), restStart.column(), restEnd.endLine(), restEnd.endColumn(), argument));
                    if (match(TokenType.COMMA)) {
                        throw new ParseException("ValidationError", peek(), null, "parameter list", "Rest parameter must be last");
                    }
                    break;
                } else {
                    params.add(parsePattern());
                }
            } while (match(TokenType.COMMA));
        }

        inFormalParameters = savedInFormalParameters;
        consume(TokenType.RPAREN, "Expected ')' after parameters");

        // Strict mode is inherited from outer scope
        BlockStatement body = parseBlockStatement(true);
        validateStrictBodyWithSimpleParams(params, body, functionToken);

        // If the body made this strict mode, validate that function name is not eval/arguments
        // This handles: (function eval() { 'use strict'; });
        if (strictMode && id != null) {
            String functionName = id.name();
            if (functionName.equals("eval") || functionName.equals("arguments")) {
                throw new ExpectedTokenException("'" + functionName + "' is not allowed as a function name in strict mode", functionToken);
            }
        }

        validateNoDuplicateParameters(params, functionToken);
        validateNoParamBodyConflicts(params, body, functionToken);

        // Pop the function scope
        popScope();

        inGenerator = savedInGenerator;
        inAsyncContext = savedInAsyncContext;
        strictMode = savedStrictMode;
        inClassFieldInitializer = savedInClassFieldInitializer;
        inFunction = savedInFunction;
        allowNewTarget = savedAllowNewTarget;
        allowSuperProperty = savedAllowSuperProperty;
        allowSuperCall = savedAllowSuperCall;
        atModuleTopLevel = savedAtModuleTopLevel;
        loopDepth = savedLoopDepth;
        switchDepth = savedSwitchDepth;
        labelMap.clear();
        labelMap.putAll(savedLabelMap);

        Token endToken = previous();
        return new FunctionExpression(getStart(functionToken), getEnd(endToken), functionToken.line(), functionToken.column(), endToken.endLine(), endToken.endColumn(), id, false, isGenerator, false, params, body);
    }

    private Expression parseClassExpression(Token classToken) {
        // Optional class name - but NOT if the next token is 'extends' (contextual keyword)
        Identifier id = null;
        if (check(TokenType.IDENTIFIER) && !peek().lexeme().equals("extends")) {
            Token nameToken = peek();
            advance();
            String className = nameToken.lexeme();
            // Class names cannot be strict mode reserved words (class bodies are always strict)
            validateClassName(className, nameToken);
            id = new Identifier(getStart(nameToken), getEnd(nameToken), nameToken.line(), nameToken.column(), nameToken.endLine(), nameToken.endColumn(), className);
        }

        // Class expressions are always in strict mode (including the heritage expression)
        boolean savedStrictMode = strictMode;
        strictMode = true;

        // Optional extends
        Expression superClass = null;
        boolean savedInDerivedClass = inDerivedClass;
        int pendingRefsBeforeHeritage = pendingPrivateRefs.size();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("extends")) {
            Token extendsToken = peek();
            advance(); // consume 'extends'
            // Capture start position before parsing to detect if arrow is wrapped in parens
            int heritageStart = getStart(peek());
            // Parse superclass - use BP_COMMA to allow member access like `orig.Minimatch`
            // but stop at comma, etc. Using parseLeftHandSideExpression would be ideal,
            // but BP_COMMA achieves similar result for practical cases
            superClass = parseExpr(BP_COMMA);

            // ClassHeritage must be a LeftHandSideExpression - bare arrow functions are not allowed
            // BUT arrow functions wrapped in parens like (async () => {}) ARE valid
            // (they'll throw TypeError at runtime, but it's not a syntax error)
            if (superClass instanceof ArrowFunctionExpression && superClass.start() == heritageStart) {
                throw new ExpectedTokenException("Class heritage cannot be an arrow function", extendsToken);
            }

            inDerivedClass = true;

            // Private name references in the heritage must be valid in the OUTER scope
            // (the class's own private names are not yet visible)
            for (int i = pendingRefsBeforeHeritage; i < pendingPrivateRefs.size(); i++) {
                var entry = pendingPrivateRefs.get(i);
                String name = entry.getKey();
                Token token = entry.getValue();
                if (!isPrivateNameDeclared(name)) {
                    throw new ExpectedTokenException("Private field '#" + name + "' must be declared in an enclosing class", token);
                }
            }
            // Remove validated heritage refs from pending list (in reverse order to maintain indices)
            for (int i = pendingPrivateRefs.size() - 1; i >= pendingRefsBeforeHeritage; i--) {
                pendingPrivateRefs.remove(i);
            }
        } else {
            inDerivedClass = false;
        }

        // Class body
        ClassBody body = parseClassBody();
        inDerivedClass = savedInDerivedClass;
        strictMode = savedStrictMode;

        Token endToken = previous();
        return new ClassExpression(getStart(classToken), getEnd(endToken), classToken.line(), classToken.column(), endToken.endLine(), endToken.endColumn(), id, superClass, body);
    }

    private Expression parseArrayLiteral(Token lbracket) {
        List<Expression> elements = new ArrayList<>();
        // Allow 'in' operator in array element expressions
        // (e.g., for destructuring: [ x = 'a' in {} ])
        boolean savedAllowIn = allowIn;
        allowIn = true;

        while (!check(TokenType.RBRACKET) && !isAtEnd()) {
            if (check(TokenType.COMMA)) {
                // Elision (hole in array)
                elements.add(null);
                advance();
            } else if (match(TokenType.DOT_DOT_DOT)) {
                // Spread element
                Token spreadStart = previous();
                Expression argument = parseExpr(BP_ASSIGNMENT);
                Token spreadEnd = previous();
                boolean hasTrailingComma = false;
                if (!check(TokenType.RBRACKET)) {
                    consume(TokenType.COMMA, "Expected ',' after spread element");
                    // Check if this is a trailing comma (next token is ])
                    if (check(TokenType.RBRACKET)) {
                        hasTrailingComma = true;
                    }
                }
                elements.add(new SpreadElement(getStart(spreadStart), getEnd(spreadEnd), spreadStart.line(), spreadStart.column(), spreadEnd.endLine(), spreadEnd.endColumn(), argument, hasTrailingComma));
            } else {
                elements.add(parseExpr(BP_ASSIGNMENT));
                if (!check(TokenType.RBRACKET)) {
                    consume(TokenType.COMMA, "Expected ',' or ']'");
                }
            }
        }

        allowIn = savedAllowIn;
        consume(TokenType.RBRACKET, "Expected ']' after array elements");
        Token endToken = previous();
        return new ArrayExpression(getStart(lbracket), getEnd(endToken), lbracket.line(), lbracket.column(), endToken.endLine(), endToken.endColumn(), elements);
    }

    private Expression parseObjectLiteral(Token lbrace) {
        List<Node> properties = new ArrayList<>();
        boolean hasProtoProperty = false;  // Track duplicate __proto__

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token propStart = peek();
            Node prop = parseObjectPropertyNode();

            // Check for duplicate __proto__ properties (only for non-shorthand, non-computed colon properties)
            // Note: This check does NOT apply to destructuring patterns (Object Assignment patterns)
            // We detect potential destructuring by checking if value is a simple identifier
            // (actual destructuring will have identifier values, not complex expressions)
            if (prop instanceof Property p && p.kind().equals("init") && !p.method() && !p.shorthand() && !p.computed()) {
                String keyName = getPropertyKeyName(p.key());
                if ("__proto__".equals(keyName)) {
                    // Only count as proto property if value is NOT a simple identifier
                    // (destructuring patterns would have identifier values)
                    boolean isValueSimpleId = p.value() instanceof Identifier;
                    if (!isValueSimpleId) {
                        if (hasProtoProperty) {
                            throw new ExpectedTokenException("Duplicate __proto__ fields are not allowed in object literals", propStart);
                        }
                        hasProtoProperty = true;
                    }
                }
            }

            properties.add(prop);
            if (!check(TokenType.RBRACE)) {
                consume(TokenType.COMMA, "Expected ',' or '}'");
            }
        }

        consume(TokenType.RBRACE, "Expected '}' after object properties");
        Token endToken = previous();
        return new ObjectExpression(getStart(lbrace), getEnd(endToken), lbrace.line(), lbrace.column(), endToken.endLine(), endToken.endColumn(), properties);
    }

    private Node parseObjectPropertyNode() {
        Token startToken = peek();

        // Check for spread property - return SpreadElement directly
        if (match(TokenType.DOT_DOT_DOT)) {
            Token spreadStart = previous();
            Expression argument = parseExpr(BP_ASSIGNMENT);
            Token spreadEnd = previous();
            return new SpreadElement(getStart(spreadStart), getEnd(spreadEnd), spreadStart.line(), spreadStart.column(), spreadEnd.endLine(), spreadEnd.endColumn(), argument);
        }

        // Check for method shorthand: get/set/async/generator
        // Contextual keywords must not contain Unicode escapes
        boolean isAsync = false;
        boolean isGenerator = false;
        String kind = "init";

        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("async")) {
            Token asyncToken = peek();
            boolean hasEscapes = tokenContainsEscapes(asyncToken);
            if (current + 1 < tokens.size()) {
                Token nextToken = tokens.get(current + 1);
                // async without line terminator followed by property name is async method
                if (asyncToken.line() == nextToken.line() &&
                    nextToken.type() != TokenType.COLON &&
                    nextToken.type() != TokenType.COMMA &&
                    nextToken.type() != TokenType.RBRACE &&
                    nextToken.type() != TokenType.LPAREN) {
                    // If async contains escapes but is being used as a keyword, reject it
                    if (hasEscapes) {
                        throw new ExpectedTokenException("'async' keyword must not contain Unicode escape sequences", asyncToken);
                    }
                    advance(); // consume 'async'
                    isAsync = true;
                }
            }
        }

        if (match(TokenType.STAR)) {
            isGenerator = true;
        }

        if (!isAsync && !isGenerator && check(TokenType.IDENTIFIER)) {
            Token gsToken = peek();
            String lexeme = gsToken.lexeme();
            if (lexeme.equals("get") || lexeme.equals("set")) {
                boolean hasEscapes = tokenContainsEscapes(gsToken);
                if (current + 1 < tokens.size()) {
                    Token nextToken = tokens.get(current + 1);
                    if (nextToken.type() != TokenType.COLON &&
                        nextToken.type() != TokenType.COMMA &&
                        nextToken.type() != TokenType.RBRACE &&
                        nextToken.type() != TokenType.LPAREN) {
                        // If get/set contains escapes but is being used as a keyword, reject it
                        if (hasEscapes) {
                            throw new ExpectedTokenException("'" + lexeme + "' keyword must not contain Unicode escape sequences", gsToken);
                        }
                        kind = lexeme;
                        advance(); // consume 'get' or 'set'
                    }
                }
            }
        }

        // Parse property key
        boolean computed = false;
        Expression key;

        if (match(TokenType.LBRACKET)) {
            computed = true;
            // Allow 'in' operator in computed property names
            // (e.g., { ['a' in {}]: value })
            boolean savedAllowIn = allowIn;
            allowIn = true;
            key = parseExpr(BP_ASSIGNMENT);
            allowIn = savedAllowIn;
            consume(TokenType.RBRACKET, "Expected ']' after computed property name");
        } else if (check(TokenType.STRING) || check(TokenType.NUMBER)) {
            Token keyToken = advance();
            String keyLexeme = keyToken.lexeme();

            // Check if this is a BigInt literal (ends with 'n')
            if (keyToken.type() == TokenType.NUMBER && keyLexeme.endsWith("n")) {
                // BigInt literal: value is null, bigint field has the numeric part
                String bigintValue = keyLexeme.substring(0, keyLexeme.length() - 1).replace("_", "");
                // Convert hex/octal/binary BigInt to decimal string
                if (bigintValue.startsWith("0x") || bigintValue.startsWith("0X")) {
                    try {
                        java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 16);
                        bigintValue = bi.toString();
                    } catch (NumberFormatException e) { /* keep original */ }
                } else if (bigintValue.startsWith("0o") || bigintValue.startsWith("0O")) {
                    try {
                        java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 8);
                        bigintValue = bi.toString();
                    } catch (NumberFormatException e) { /* keep original */ }
                } else if (bigintValue.startsWith("0b") || bigintValue.startsWith("0B")) {
                    try {
                        java.math.BigInteger bi = new java.math.BigInteger(bigintValue.substring(2), 2);
                        bigintValue = bi.toString();
                    } catch (NumberFormatException e) { /* keep original */ }
                }
                // For BigInt literals, Acorn sets value to null (the actual BigInt cannot be serialized to JSON)
                key = new Literal(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), null, keyLexeme, null, bigintValue);
            } else {
                // Handle Infinity/-Infinity/NaN - value should be null per ESTree spec
                Object literalValue = keyToken.literal();
                if (literalValue instanceof Double d && (d.isInfinite() || d.isNaN())) {
                    literalValue = null;
                }
                key = new Literal(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), literalValue, keyLexeme);
            }
        } else {
            Token keyToken = peek();
            if (!check(TokenType.IDENTIFIER) && !isKeyword(keyToken)) {
                throw new ExpectedTokenException("property name", keyToken);
            }
            advance();
            key = new Identifier(getStart(keyToken), getEnd(keyToken), keyToken.line(), keyToken.column(), keyToken.endLine(), keyToken.endColumn(), keyToken.lexeme());
        }

        // Check for method or shorthand
        if (check(TokenType.LPAREN) || isGenerator || isAsync || !kind.equals("init")) {
            // Method - FunctionExpression starts at '(' not at the method name
            Token funcStartToken = peek(); // Save the '(' token for FunctionExpression start
            advance(); // consume (

            // Save and set generator/async context BEFORE parsing parameters
            // Default parameter initializers use the method's context
            boolean savedInGenerator = inGenerator;
            boolean savedInAsyncContext = inAsyncContext;
            boolean savedStrictMode = strictMode;
            boolean savedInClassFieldInitializer = inClassFieldInitializer;
            boolean savedInFunction = inFunction;
            boolean savedAllowNewTarget = allowNewTarget;
            boolean savedAllowSuperProperty = allowSuperProperty;
            boolean savedAllowSuperCall = allowSuperCall;
            boolean savedAtModuleTopLevel = atModuleTopLevel;
            inGenerator = isGenerator;
            inAsyncContext = isAsync;
            inClassFieldInitializer = false;
            inFunction = true;
            allowNewTarget = true; // Object literal methods allow new.target
            allowSuperProperty = true; // Object literal methods allow super.property
            allowSuperCall = false; // Object literal methods don't allow super()
            atModuleTopLevel = false; // Methods don't inherit top-level await

            // Push a function scope for the method
            pushScope(true);

            // Yield/await expressions are not allowed in parameter defaults
            boolean savedInFormalParameters = inFormalParameters;
            inFormalParameters = true;

            List<Pattern> params = new ArrayList<>();

            if (!check(TokenType.RPAREN)) {
                do {
                    if (check(TokenType.RPAREN)) break;
                    if (match(TokenType.DOT_DOT_DOT)) {
                        Token restStart = previous();
                        Pattern argument = parsePatternBase();
                        Token restEnd = previous();
                        params.add(new RestElement(getStart(restStart), getEnd(restEnd), restStart.line(), restStart.column(), restEnd.endLine(), restEnd.endColumn(), argument));
                        if (match(TokenType.COMMA)) {
                            throw new ParseException("ValidationError", peek(), null, "parameter list", "Rest parameter must be last");
                        }
                        break;
                    } else {
                        params.add(parsePattern());
                    }
                } while (match(TokenType.COMMA));
            }

            inFormalParameters = savedInFormalParameters;
            consume(TokenType.RPAREN, "Expected ')' after parameters");

            // Validate getter/setter parameter counts
            if (kind.equals("get") && !params.isEmpty()) {
                throw new ExpectedTokenException("getter must have zero parameters", startToken);
            }
            if (kind.equals("set") && params.size() != 1) {
                throw new ExpectedTokenException("setter must have exactly one parameter", startToken);
            }

            BlockStatement body = parseBlockStatement(true);

            // Validate that "use strict" is not used with non-simple parameters
            validateStrictBodyWithSimpleParams(params, body, startToken);

            // Check for duplicate parameters - methods always use UniqueFormalParameters
            // so duplicates are never allowed (even in sloppy mode with simple params)
            validateNoDuplicateParameters(params, startToken, true);

            // Check for parameter/body conflicts (param name vs lexical declaration in body)
            validateNoParamBodyConflicts(params, body, startToken);

            // Pop the function scope
            popScope();

            inGenerator = savedInGenerator;
            inAsyncContext = savedInAsyncContext;
            strictMode = savedStrictMode;
            inClassFieldInitializer = savedInClassFieldInitializer;
            inFunction = savedInFunction;
            allowNewTarget = savedAllowNewTarget;
            allowSuperProperty = savedAllowSuperProperty;
            allowSuperCall = savedAllowSuperCall;
            atModuleTopLevel = savedAtModuleTopLevel;

            Token endToken = previous();
            // FunctionExpression starts at '(' per ESTree spec for method definitions
            FunctionExpression value = new FunctionExpression(getStart(funcStartToken), getEnd(endToken), funcStartToken.line(), funcStartToken.column(), endToken.endLine(), endToken.endColumn(), null, false, isGenerator, isAsync, params, body);

            // Getters and setters have method=false, only actual methods have method=true
            boolean isMethod = kind.equals("init");
            // Property: start, end, loc, method, shorthand, computed, key, value, kind
            return new Property(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), isMethod, false, computed, key, value, kind);
        } else if (match(TokenType.COLON)) {
            // Regular property - allow 'in' operator in value expressions
            // (e.g., for destructuring: { x: y = 'a' in {} })
            boolean savedAllowIn = allowIn;
            allowIn = true;
            Expression value = parseExpr(BP_ASSIGNMENT);
            allowIn = savedAllowIn;
            Token endToken = previous();
            // Property: start, end, loc, method, shorthand, computed, key, value, kind
            return new Property(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), false, false, computed, key, value, "init");
        } else if (match(TokenType.ASSIGN)) {
            // Shorthand property with default value: { x = value }
            // This is only valid in destructuring patterns but we parse it as an object expression
            // and convert it later. The value is an AssignmentExpression.
            if (!(key instanceof Identifier)) {
                throw new ExpectedTokenException("identifier", peek());
            }
            Identifier id = (Identifier) key;
            // Inside object literals with default values (destructuring), 'in' is always the operator
            boolean savedAllowIn = allowIn;
            allowIn = true;
            Expression defaultValue = parseExpr(BP_ASSIGNMENT);
            allowIn = savedAllowIn;
            Token endToken = previous();
            // Create an AssignmentExpression as the value, which will be converted to AssignmentPattern later
            AssignmentExpression assignExpr = new AssignmentExpression(
                getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(),
                "=", id, defaultValue);
            // Property: start, end, loc, method, shorthand, computed, key, value, kind
            return new Property(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), false, true, computed, key, assignExpr, "init");
        } else {
            // Shorthand property: { x } means { x: x }
            // Computed property names cannot be shorthand - they require a value
            if (computed) {
                throw new ExpectedTokenException("':' after computed property name", peek());
            }
            if (!(key instanceof Identifier)) {
                throw new ExpectedTokenException("':' after property name", peek());
            }
            Identifier id = (Identifier) key;
            // Shorthand property is an IdentifierReference which cannot be a reserved word
            if (isReservedWord(id.name())) {
                throw new ExpectedTokenException("'" + id.name() + "' is a reserved word and cannot be used as an identifier", startToken);
            }
            // Strict mode reserved words also cannot be used in strict mode
            if (strictMode && isStrictModeReservedWord(id.name())) {
                throw new ExpectedTokenException("'" + id.name() + "' is a reserved word in strict mode", startToken);
            }
            // In static blocks, 'await' and 'arguments' cannot be used as identifier references
            // (except inside nested functions which reset inFunction)
            if (inStaticBlock && !inFunction) {
                if (id.name().equals("await")) {
                    throw new ExpectedTokenException("'await' is not allowed as an identifier in class static block", startToken);
                }
                if (id.name().equals("arguments")) {
                    throw new ExpectedTokenException("'arguments' is not allowed in class static block", startToken);
                }
            }
            Token endToken = previous();
            // Property: start, end, loc, method, shorthand, computed, key, value, kind
            return new Property(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), false, true, computed, key, key, "init");
        }
    }

    // End of Pratt parser section
    // ========================================================================

    // Convert Expression to Pattern for destructuring assignments
    private Node convertToPatternIfNeeded(Node node) {
        // If it's not an expression, return as-is
        if (!(node instanceof Expression)) {
            return node;
        }
        Expression expr = (Expression) node;
        if (expr instanceof ArrayExpression arrayExpr) {
            // Convert ArrayExpression to ArrayPattern
            List<Pattern> patternElements = new ArrayList<>();
            boolean seenRest = false;
            List<Expression> elements = arrayExpr.elements();
            for (int i = 0; i < elements.size(); i++) {
                Expression element = elements.get(i);

                // Rest element must be last - no elements or elisions after it
                if (seenRest) {
                    throw new ParseException("SyntaxError", null, null, null, "Rest element must be last element");
                }

                if (element instanceof Identifier id) {
                    // Validate strict mode restrictions
                    validateAssignmentTargetInPattern(id.name());
                    patternElements.add(id);
                } else if (element instanceof ArrayExpression || element instanceof ObjectExpression) {
                    // Recursively convert nested destructuring
                    patternElements.add((Pattern) convertToPatternIfNeeded(element));
                } else if (element instanceof AssignmentExpression assignExpr) {
                    // Convert AssignmentExpression to AssignmentPattern (for default values)
                    Node leftNode = convertToPatternIfNeeded(assignExpr.left());
                    Pattern left = (Pattern) leftNode;
                    patternElements.add(new AssignmentPattern(assignExpr.start(), assignExpr.end(), assignExpr.startLine(), assignExpr.startCol(), assignExpr.endLine(), assignExpr.endCol(), left, assignExpr.right()));
                } else if (element instanceof SpreadElement spreadElem) {
                    // Convert SpreadElement to RestElement
                    // Rest elements do not support initializers (e.g., [...x = 1] is invalid)
                    if (spreadElem.argument() instanceof AssignmentExpression) {
                        throw new ParseException("SyntaxError", null, null, null, "Rest element may not have a default initializer");
                    }
                    // Trailing comma after rest element is not allowed in patterns
                    if (spreadElem.trailingComma()) {
                        throw new ParseException("SyntaxError", null, null, null, "Rest element must be last element in array pattern");
                    }
                    Node argNode = convertToPatternIfNeeded(spreadElem.argument());
                    Pattern argument = (Pattern) argNode;
                    patternElements.add(new RestElement(spreadElem.start(), spreadElem.end(), spreadElem.startLine(), spreadElem.startCol(), spreadElem.endLine(), spreadElem.endCol(), argument));
                    seenRest = true;
                } else {
                    // For other expressions, keep as is (this might be an error in real code)
                    patternElements.add((Pattern) element);
                }
            }
            return new ArrayPattern(arrayExpr.start(), arrayExpr.end(), arrayExpr.startLine(), arrayExpr.startCol(), arrayExpr.endLine(), arrayExpr.endCol(), patternElements);
        } else if (expr instanceof ObjectExpression objExpr) {
            // Convert ObjectExpression to ObjectPattern
            // Need to convert properties: AssignmentExpression -> AssignmentPattern, SpreadElement -> RestElement
            List<Node> convertedProperties = new ArrayList<>();
            boolean seenRest = false;
            List<Node> properties = objExpr.properties();
            for (int i = 0; i < properties.size(); i++) {
                Node prop = properties.get(i);

                // Rest element must be last - no properties after it
                if (seenRest) {
                    throw new ParseException("SyntaxError", null, null, null, "Rest element must be last element");
                }

                if (prop instanceof Property property) {
                    // Getter, setter, and method properties are not valid in patterns
                    if (property.kind().equals("get") || property.kind().equals("set")) {
                        throw new ParseException("SyntaxError", null, null, null, "Object pattern cannot contain getter or setter");
                    }
                    if (property.method()) {
                        throw new ParseException("SyntaxError", null, null, null, "Object pattern cannot contain method definitions");
                    }
                    Node value = property.value();
                    // Convert the value if needed
                    if (value instanceof AssignmentExpression assignExpr) {
                        // Convert AssignmentExpression to AssignmentPattern
                        Node leftNode = convertToPatternIfNeeded(assignExpr.left());
                        Pattern left = (Pattern) leftNode;
                        AssignmentPattern pattern = new AssignmentPattern(assignExpr.start(), assignExpr.end(), assignExpr.startLine(), assignExpr.startCol(), assignExpr.endLine(), assignExpr.endCol(), left, assignExpr.right());
                        convertedProperties.add(new Property(property.start(), property.end(), property.startLine(), property.startCol(), property.endLine(), property.endCol(),
                            property.method(), property.shorthand(), property.computed(),
                            property.key(), pattern, property.kind()));
                    } else if (value instanceof ObjectExpression || value instanceof ArrayExpression) {
                        // Recursively convert nested destructuring
                        Node convertedValue = convertToPatternIfNeeded(value);
                        convertedProperties.add(new Property(property.start(), property.end(), property.startLine(), property.startCol(), property.endLine(), property.endCol(),
                            property.method(), property.shorthand(), property.computed(),
                            property.key(), convertedValue, property.kind()));
                    } else if (value instanceof MetaProperty) {
                        // import.meta is not a valid assignment target
                        throw new ParseException("SyntaxError", null, null, null, "Invalid assignment target");
                    } else if (value instanceof Identifier id) {
                        // Validate strict mode restrictions for shorthand properties
                        validateAssignmentTargetInPattern(id.name());
                        convertedProperties.add(property);
                    } else {
                        convertedProperties.add(property);
                    }
                } else if (prop instanceof SpreadElement spreadElem) {
                    // Convert SpreadElement to RestElement in object patterns
                    Node argNode = convertToPatternIfNeeded(spreadElem.argument());
                    Pattern argument = (Pattern) argNode;
                    convertedProperties.add(new RestElement(spreadElem.start(), spreadElem.end(), spreadElem.startLine(), spreadElem.startCol(), spreadElem.endLine(), spreadElem.endCol(), argument));
                    seenRest = true;
                } else {
                    convertedProperties.add(prop);
                }
            }
            return new ObjectPattern(objExpr.start(), objExpr.end(), objExpr.startLine(), objExpr.startCol(), objExpr.endLine(), objExpr.endCol(), convertedProperties);
        } else if (expr instanceof AssignmentExpression assignExpr) {
            // Convert AssignmentExpression to AssignmentPattern (for default values)
            Node leftNode = convertToPatternIfNeeded(assignExpr.left());
            Pattern left = (Pattern) leftNode;
            return new AssignmentPattern(assignExpr.start(), assignExpr.end(), assignExpr.startLine(), assignExpr.startCol(), assignExpr.endLine(), assignExpr.endCol(), left, assignExpr.right());
        } else if (expr instanceof Identifier id) {
            // Identifier is both Expression and Pattern
            // Validate strict mode restrictions
            validateAssignmentTargetInPattern(id.name());
            return expr;
        } else if (expr instanceof MemberExpression) {
            // MemberExpression is valid as an assignment target, return as-is
            return expr;
        }

        // For other expressions, return as-is
        return expr;
    }

    // Helper method to check if the current position (after opening paren) looks like arrow function parameters
    // This scans ahead to find the matching ) and checks if it's followed by =>
    private boolean isArrowFunctionParameters() {
        int depth = 1; // We've already consumed the opening (
        int checkCurrent = current;

        // Scan ahead to find the matching )
        while (checkCurrent < tokens.size() && depth > 0) {
            TokenType type = tokens.get(checkCurrent).type();

            if (type == TokenType.LPAREN || type == TokenType.LBRACKET || type == TokenType.LBRACE) {
                depth++;
            } else if (type == TokenType.RPAREN || type == TokenType.RBRACKET || type == TokenType.RBRACE) {
                depth--;
                if (depth == 0) {
                    // Found the matching ), check if followed by =>
                    if (checkCurrent + 1 < tokens.size() &&
                        tokens.get(checkCurrent + 1).type() == TokenType.ARROW) {
                        // Check for no line terminator between ) and =>
                        Token rparenToken = tokens.get(checkCurrent);
                        Token arrowToken = tokens.get(checkCurrent + 1);
                        if (rparenToken.line() != arrowToken.line()) {
                            return false; // Line terminator - not arrow function
                        }
                        return true;
                    }
                    return false;
                }
            }
            checkCurrent++;
        }

        return false;
    }

    private Expression parseArrowFunctionBody(Token startToken, List<Pattern> params, boolean isAsync) {
        // Arrow functions always disallow duplicate parameters
        validateNoDuplicateParams(params, startToken);

        // Save and set async context for arrow function body
        boolean savedInAsyncContext = inAsyncContext;
        boolean savedInFunction = inFunction;
        inAsyncContext = isAsync;
        inFunction = true;  // Arrow functions allow return statements
        // Note: Arrow functions do NOT reset inClassFieldInitializer because they
        // don't provide their own 'arguments' - they inherit the restriction
        // Note: Arrow functions inherit allowNewTarget from enclosing context (lexical new.target)
        // so we do NOT modify allowNewTarget - new.target is only valid if enclosing regular function allows it
        // Note: Arrow functions inherit super from enclosing context (lexical super)
        // so we do NOT modify allowSuperProperty or allowSuperCall

        // Push a function scope for the arrow function
        pushScope(true);

        try {
            // Arrow function body can be an expression or block statement
            if (check(TokenType.LBRACE)) {
                // Block body: () => { statements }
                BlockStatement body = parseBlockStatement(true); // Arrow function body (doesn't push another scope)
                // Validate that "use strict" is not used with non-simple parameters
                validateStrictBodyWithSimpleParams(params, body, startToken);
                // Arrow functions use UniqueFormalParameters
                validateNoDuplicateParameters(params, startToken, true);
                // Check for parameter/body conflicts
                validateNoParamBodyConflicts(params, body, startToken);
                Token endToken = previous();
                return new ArrowFunctionExpression(getStart(startToken), getEnd(endToken), startToken.line(), startToken.column(), endToken.endLine(), endToken.endColumn(), null, false, false, isAsync, params, body);
            } else {
                // Expression body: () => expr
                Expression body = parseExpr(BP_ASSIGNMENT);
                Token endToken = previous();

                // Always use endToken for arrow end position - this correctly handles
                // parenthesized expressions like () => (expr) where the ) is consumed
                int arrowEnd = getEnd(endToken);
                int endLine = endToken.endLine();
                int endCol = endToken.endColumn();

                return new ArrowFunctionExpression(getStart(startToken), arrowEnd, startToken.line(), startToken.column(), endLine, endCol, null, true, false, isAsync, params, body);
            }
        } finally {
            // Pop the arrow function scope
            popScope();
            inAsyncContext = savedInAsyncContext;
            inFunction = savedInFunction;
        }
    }

    private Expression parseTemplateLiteral(boolean isTagged) {
        Token startToken = peek();
        TokenType startType = startToken.type();

        if (startType == TokenType.TEMPLATE_LITERAL) {
            // Simple template with no interpolation: `hello`
            advance();
            List<Expression> expressions = new ArrayList<>();
            List<TemplateElement> quasis = new ArrayList<>();

            // Create the single quasi
            // Use the raw value from the token (already processed by lexer)
            String raw = startToken.raw();
            String cooked = (String) startToken.literal();

            // For untagged templates, invalid escapes (cooked = null) are syntax errors
            if (!isTagged && cooked == null) {
                throw new ExpectedTokenException("Invalid escape sequence in template literal", startToken);
            }

            int elemStart = getStart(startToken) + 1; // +1 to skip opening `
            int elemEnd = getEnd(startToken) - 1; // -1 to exclude closing `
            SourceLocation.Position elemStartPos = getPositionFromOffset(elemStart);
            SourceLocation.Position elemEndPos = getPositionFromOffset(elemEnd);
            quasis.add(new TemplateElement(
                elemStart,
                elemEnd,
                elemStartPos.line(), elemStartPos.column(), elemEndPos.line(), elemEndPos.column(),
                new TemplateElement.TemplateElementValue(raw, cooked),
                true // tail
            ));

            SourceLocation.Position templateStartPos = getPositionFromOffset(getStart(startToken));
            SourceLocation.Position templateEndPos = getPositionFromOffset(getEnd(startToken));
            return new TemplateLiteral(getStart(startToken), getEnd(startToken), templateStartPos.line(), templateStartPos.column(), templateEndPos.line(), templateEndPos.column(), expressions, quasis);
        } else if (startType == TokenType.TEMPLATE_HEAD) {
            // Template with interpolations
            int templateStart = getStart(startToken);
            advance(); // consume TEMPLATE_HEAD

            List<Expression> expressions = new ArrayList<>();
            List<TemplateElement> quasis = new ArrayList<>();

            // Add the head quasi
            // Use the raw value from the token (already processed by lexer)
            String raw = startToken.raw();
            String cooked = (String) startToken.literal();

            // For untagged templates, invalid escapes (cooked = null) are syntax errors
            if (!isTagged && cooked == null) {
                throw new ExpectedTokenException("Invalid escape sequence in template literal", startToken);
            }

            int elemStart = templateStart + 1; // +1 for opening `
            // Token endPosition includes the ${ delimiter, so we need to subtract 2
            // This works correctly for both LF and CRLF files since we use actual token positions
            int elemEnd = getEnd(startToken) - 2;
            SourceLocation.Position elemStartPos = getPositionFromOffset(elemStart);
            SourceLocation.Position elemEndPos = getPositionFromOffset(elemEnd);
            quasis.add(new TemplateElement(
                elemStart,
                elemEnd,
                elemStartPos.line(), elemStartPos.column(), elemEndPos.line(), elemEndPos.column(),
                new TemplateElement.TemplateElementValue(raw, cooked),
                false // not tail
            ));

            // Parse expressions and middle/tail quasis
            while (true) {
                // Parse the expression - allow 'in' operator in template interpolations
                boolean savedAllowIn = allowIn;
                allowIn = true;
                Expression expr = parseExpression();
                allowIn = savedAllowIn;
                expressions.add(expr);

                // Next token should be TEMPLATE_MIDDLE or TEMPLATE_TAIL
                Token quasiToken = peek();
                TokenType quasiType = quasiToken.type();

                if (quasiType == TokenType.TEMPLATE_MIDDLE) {
                    advance();
                    // Use the raw value from the token (already processed by lexer)
                    String quasiRaw = quasiToken.raw();
                    String quasiCooked = (String) quasiToken.literal();

                    // For untagged templates, invalid escapes (cooked = null) are syntax errors
                    if (!isTagged && quasiCooked == null) {
                        throw new ExpectedTokenException("Invalid escape sequence in template literal", quasiToken);
                    }

                    int quasiStart = getStart(quasiToken) + 1; // +1 to skip }
                    // Token endPosition includes the ${ delimiter, so we need to subtract 2
                    // This works correctly for both LF and CRLF files since we use actual token positions
                    int quasiEnd = getEnd(quasiToken) - 2;
                    SourceLocation.Position quasiStartPos = getPositionFromOffset(quasiStart);
                    SourceLocation.Position quasiEndPos = getPositionFromOffset(quasiEnd);
                    quasis.add(new TemplateElement(
                        quasiStart,
                        quasiEnd,
                        quasiStartPos.line(), quasiStartPos.column(), quasiEndPos.line(), quasiEndPos.column(),
                        new TemplateElement.TemplateElementValue(quasiRaw, quasiCooked),
                        false // not tail
                    ));
                } else if (quasiType == TokenType.TEMPLATE_TAIL) {
                    Token endToken = quasiToken;
                    advance();
                    // Use the raw value from the token (already processed by lexer)
                    String quasiRaw = quasiToken.raw();
                    String quasiCooked = (String) quasiToken.literal();

                    // For untagged templates, invalid escapes (cooked = null) are syntax errors
                    if (!isTagged && quasiCooked == null) {
                        throw new ExpectedTokenException("Invalid escape sequence in template literal", quasiToken);
                    }

                    int quasiStart = getStart(quasiToken) + 1; // +1 to skip }
                    // Use token's actual end position - 1 to exclude closing `
                    // This is important for files with CRLF line endings where raw is normalized
                    int quasiEnd = getEnd(quasiToken) - 1;
                    SourceLocation.Position quasiStartPos = getPositionFromOffset(quasiStart);
                    SourceLocation.Position quasiEndPos = getPositionFromOffset(quasiEnd);
                    quasis.add(new TemplateElement(
                        quasiStart,
                        quasiEnd,
                        quasiStartPos.line(), quasiStartPos.column(), quasiEndPos.line(), quasiEndPos.column(),
                        new TemplateElement.TemplateElementValue(quasiRaw, quasiCooked),
                        true // tail
                    ));

                    // Calculate template end position (token already includes closing `)
                    int templateEnd = getEnd(endToken);
                    SourceLocation.Position templateStartPos = getPositionFromOffset(templateStart);
                    SourceLocation.Position templateEndPos = getPositionFromOffset(templateEnd);
                    return new TemplateLiteral(templateStart, templateEnd, templateStartPos.line(), templateStartPos.column(), templateEndPos.line(), templateEndPos.column(), expressions, quasis);
                } else {
                    throw new ExpectedTokenException("TEMPLATE_MIDDLE or TEMPLATE_TAIL after expression in template literal", peek());
                }
            }
        } else {
            throw new ExpectedTokenException("template literal token", peek());
        }
    }


    // Helper method to create SourceLocation from tokens
    private SourceLocation createLocation(Token start, Token end) {
        SourceLocation.Position startPos = new SourceLocation.Position(start.line(), start.column());
        // Use token's endLine/endColumn for accurate multi-line token support
        SourceLocation.Position endPos = new SourceLocation.Position(end.endLine(), end.endColumn());
        return new SourceLocation(startPos, endPos);
    }

    // Helper method to get start byte position from token
    private int getStart(Token token) {
        return token.position();
    }

    // Helper method to get end byte position from token
    private int getEnd(Token token) {
        // Use endPosition if available (correct for tokens with escapes)
        return token.endPosition();
    }

    // Build line offset index once during construction (O(n) operation)
    private int[] buildLineOffsetIndex() {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0); // Line 1 starts at offset 0

        for (int i = 0; i < sourceLength; i++) {
            char ch = sourceBuf[i];
            // Handle all line terminators: LF, CR, CRLF, LS, PS
            if (ch == '\n') {
                offsets.add(i + 1);
            } else if (ch == '\r') {
                // Check for CRLF (skip the LF if present)
                if (i + 1 < sourceLength && sourceBuf[i + 1] == '\n') {
                    i++; // Skip the LF
                }
                offsets.add(i + 1);
            } else if (ch == '\u2028' || ch == '\u2029') {
                // Line Separator (LS) and Paragraph Separator (PS)
                offsets.add(i + 1);
            }
        }

        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    // Helper method to compute line and column from a position in source (O(log n) operation)
    private SourceLocation.Position getPositionFromOffset(int offset) {
        // Clamp offset to valid range
        offset = Math.max(0, Math.min(offset, sourceLength));

        // Binary search to find the line
        int low = 0;
        int high = lineOffsets.length - 1;
        int line = 1;

        while (low <= high) {
            int mid = (low + high) / 2;
            if (lineOffsets[mid] <= offset) {
                line = mid + 1; // Lines are 1-indexed
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        // Calculate column as offset from start of line
        int lineStartOffset = lineOffsets[line - 1];
        int column = offset - lineStartOffset;

        return new SourceLocation.Position(line, column);
    }

    // Helper methods

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    private boolean checkAhead(int offset, TokenType type) {
        int pos = current + offset;
        if (pos >= tokens.size()) return false;
        return tokens.get(pos).type() == type;
    }

    private boolean isKeyword(Token token) {
        TokenType type = token.type();
        return type == TokenType.VAR || type == TokenType.LET || type == TokenType.CONST ||
               type == TokenType.FUNCTION || type == TokenType.CLASS ||
               type == TokenType.RETURN || type == TokenType.IF || type == TokenType.ELSE ||
               type == TokenType.FOR || type == TokenType.WHILE || type == TokenType.DO ||
               type == TokenType.BREAK || type == TokenType.CONTINUE || type == TokenType.SWITCH ||
               type == TokenType.CASE || type == TokenType.DEFAULT || type == TokenType.TRY ||
               type == TokenType.CATCH || type == TokenType.FINALLY || type == TokenType.THROW ||
               type == TokenType.NEW || type == TokenType.TYPEOF || type == TokenType.VOID ||
               type == TokenType.DELETE || type == TokenType.THIS || type == TokenType.SUPER ||
               type == TokenType.IN || type == TokenType.OF || type == TokenType.INSTANCEOF ||
               type == TokenType.GET || type == TokenType.SET ||
               type == TokenType.IMPORT || type == TokenType.EXPORT || type == TokenType.WITH ||
               type == TokenType.DEBUGGER || type == TokenType.ASYNC || type == TokenType.AWAIT ||
               type == TokenType.TRUE || type == TokenType.FALSE || type == TokenType.NULL;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        // Fast path: direct position check instead of peek() + type() + enum comparison
        return current >= tokens.size() - 1;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private void consume(TokenType type, String message) {
        if (check(type)) {
            advance();
            return;
        }
        throw new ExpectedTokenException(message, peek());
    }

    /**
     * Validate that an identifier name is allowed in the current context.
     * Throws an exception if the identifier is a strict mode reserved word or
     * otherwise invalid in the current parsing context.
     *
     * Per ES spec, an identifier expressed via unicode escapes that spells a reserved
     * word is also not allowed as a BindingIdentifier or IdentifierReference.
     */
    private void validateIdentifier(String name, Token token) {
        // Check for reserved words (keywords, future reserved words, literals)
        // This catches escaped keywords like unicode-escaped "break" (which lexer returns as IDENTIFIER)
        // Non-escaped keywords would have been lexed as keyword tokens and wouldn't reach here
        if (isReservedWord(name)) {
            throw new ExpectedTokenException("'" + name + "' is a reserved word and cannot be used as an identifier", token);
        }

        // Check for strict mode reserved words
        if (strictMode) {
            // Future reserved words in strict mode (ECMAScript spec section 12.1.1)
            if (name.equals("implements") || name.equals("interface") ||
                name.equals("package") || name.equals("private") ||
                name.equals("protected") || name.equals("public") ||
                name.equals("static")) {
                throw new ExpectedTokenException("'" + name + "' is a reserved identifier in strict mode", token);
            }

            // 'yield' is reserved in strict mode (outside generators)
            if (name.equals("yield") && !inGenerator) {
                throw new ExpectedTokenException("'yield' is a reserved identifier in strict mode", token);
            }
        }

        // 'yield' is always reserved inside generators (even in non-strict mode)
        if (inGenerator && name.equals("yield")) {
            throw new ExpectedTokenException("'yield' is a reserved identifier in generators", token);
        }

        // 'await' is reserved inside async functions and module code
        if ((inAsyncContext || atModuleTopLevel) && !inClassFieldInitializer && name.equals("await")) {
            String context = forceModuleMode && !inAsyncContext ? "module code" : "async function";
            throw new ExpectedTokenException("'await' is a reserved identifier in " + context, token);
        }
    }

    /**
     * Check if a name is a reserved word that cannot be used as an identifier.
     */
    private boolean isReservedWord(String name) {
        return switch (name) {
            // Keywords (always reserved)
            case "break", "case", "catch", "class", "const", "continue", "debugger", "default",
                 "delete", "do", "else", "export", "extends", "finally", "for", "function",
                 "if", "import", "in", "instanceof", "new", "return", "super", "switch",
                 "this", "throw", "try", "typeof", "var", "void", "while", "with" -> true;
            // Future reserved word (always)
            case "enum" -> true;
            // Literals
            case "true", "false", "null" -> true;
            default -> false;
        };
    }

    /**
     * Validate that an identifier is not eval or arguments when used as an assignment target.
     * In strict mode, eval and arguments cannot be assigned to.
     */
    private void validateAssignmentTarget(String name, Token token) {
        if (strictMode && (name.equals("eval") || name.equals("arguments"))) {
            throw new ExpectedTokenException("Cannot assign to '" + name + "' in strict mode", token);
        }
        // 'let' is a reserved word in strict mode and cannot be an identifier/assignment target
        if (strictMode && name.equals("let")) {
            throw new ExpectedTokenException("'let' is a reserved word in strict mode", token);
        }
        // Future reserved words in strict mode (ECMAScript spec section 12.1.1)
        if (strictMode && (name.equals("implements") || name.equals("interface") ||
            name.equals("package") || name.equals("private") ||
            name.equals("protected") || name.equals("public") ||
            name.equals("static"))) {
            throw new ExpectedTokenException("'" + name + "' is a reserved identifier in strict mode", token);
        }
        // 'yield' cannot be used as an identifier in generator contexts
        if (inGenerator && name.equals("yield")) {
            throw new ExpectedTokenException("'yield' cannot be used as an identifier in generators", token);
        }
        // 'await' cannot be used as an identifier in async contexts
        if (inAsyncContext && name.equals("await")) {
            throw new ExpectedTokenException("'await' cannot be used as an identifier in async functions", token);
        }
    }

    /**
     * Validate that an identifier in a destructuring pattern is not eval or arguments in strict mode.
     * Similar to validateAssignmentTarget but used during pattern conversion where we don't have a token.
     */
    private void validateAssignmentTargetInPattern(String name) {
        if (strictMode && (name.equals("eval") || name.equals("arguments"))) {
            throw new ParseException("SyntaxError", null, null, null, "Cannot assign to '" + name + "' in strict mode");
        }
        // Future reserved words in strict mode
        if (strictMode && (name.equals("implements") || name.equals("interface") ||
            name.equals("package") || name.equals("private") ||
            name.equals("protected") || name.equals("public") ||
            name.equals("static"))) {
            throw new ParseException("SyntaxError", null, null, null, "'" + name + "' is a reserved identifier in strict mode");
        }
        // 'yield' cannot be a binding name in generator contexts
        if (inGenerator && name.equals("yield")) {
            throw new ParseException("SyntaxError", null, null, null, "'yield' cannot be used as a binding name in generators");
        }
        // 'await' cannot be a binding name in async contexts
        if (inAsyncContext && name.equals("await")) {
            throw new ParseException("SyntaxError", null, null, null, "'await' cannot be used as a binding name in async functions");
        }
    }

    /**
     * Check if an expression is a simple assignment target (Identifier or MemberExpression).
     * Returns true for valid simple targets, false for everything else.
     * Does NOT throw - just checks validity.
     */
    private boolean isSimpleAssignmentTarget(Expression expr) {
        if (expr instanceof Identifier) {
            return true;
        }
        if (expr instanceof MemberExpression) {
            return true;
        }
        return false;
    }

    /**
     * Check if an expression is a valid simple assignment target (for ++, --, and compound assignments).
     * Returns true for Identifier and MemberExpression, false for everything else.
     * Also validates strict mode restrictions on eval/arguments.
     * Note: ChainExpression (optional chaining like x?.y) is NOT a valid assignment target.
     */
    private void validateSimpleAssignmentTarget(Expression expr, Token operatorToken) {
        if (expr instanceof Identifier id) {
            // Check strict mode restrictions
            validateAssignmentTarget(id.name(), operatorToken);
            return; // Valid
        }

        if (expr instanceof MemberExpression me) {
            // Optional chaining is NOT a valid assignment target
            if (me.optional() || containsOptionalChaining(me.object())) {
                throw new ExpectedTokenException("Invalid left-hand side in assignment: optional chain", operatorToken);
            }
            return; // Valid - both a.b and a[b] are valid
        }

        // ChainExpression (optional chaining) is NOT a valid assignment target
        // even though it contains a MemberExpression internally
        if (expr instanceof ChainExpression) {
            throw new ExpectedTokenException("Invalid left-hand side in assignment: optional chain", operatorToken);
        }

        // Everything else is invalid
        String exprType = getAssignmentTargetErrorDescription(expr);
        throw new ExpectedTokenException("Invalid left-hand side in assignment: " + exprType, operatorToken);
    }

    /**
     * Get a human-readable description of an invalid assignment target for error messages.
     */
    private String getAssignmentTargetErrorDescription(Expression expr) {
        if (expr instanceof Literal lit) {
            Object value = lit.value();
            if (value == null) return "null";
            if (value instanceof Boolean) return "boolean literal";
            if (value instanceof String) return "string literal";
            if (value instanceof Number) return "numeric literal";
            return "literal";
        }
        if (expr instanceof ArrowFunctionExpression) return "arrow function";
        if (expr instanceof FunctionExpression) return "function expression";
        if (expr instanceof ClassExpression) return "class expression";
        if (expr instanceof CallExpression) return "call expression";
        if (expr instanceof NewExpression) return "new expression";
        if (expr instanceof ThisExpression) return "this";
        if (expr instanceof Super) return "super";
        if (expr instanceof MetaProperty mp) {
            return mp.meta().name() + "." + mp.property().name();
        }
        if (expr instanceof ImportExpression) return "import()";
        if (expr instanceof TemplateLiteral) return "template literal";
        if (expr instanceof TaggedTemplateExpression) return "tagged template";
        if (expr instanceof BinaryExpression) return "binary expression";
        if (expr instanceof LogicalExpression) return "logical expression";
        if (expr instanceof UnaryExpression) return "unary expression";
        if (expr instanceof UpdateExpression) return "update expression";
        if (expr instanceof ConditionalExpression) return "conditional expression";
        if (expr instanceof SequenceExpression) return "sequence expression";
        if (expr instanceof YieldExpression) return "yield expression";
        if (expr instanceof AwaitExpression) return "await expression";
        if (expr instanceof ArrayExpression) return "array literal";
        if (expr instanceof ObjectExpression) return "object literal";
        return "expression";
    }

    /**
     * Check if an expression contains optional chaining (?./ operators)
     */
    private boolean containsOptionalChaining(Expression expr) {
        if (expr instanceof MemberExpression me) {
            return me.optional() || containsOptionalChaining(me.object());
        }
        if (expr instanceof CallExpression ce) {
            return ce.optional() || containsOptionalChaining(ce.callee());
        }
        if (expr instanceof ChainExpression) {
            return true;
        }
        return false;
    }

    /**
     * Check if an expression contains a CoverInitializedName (shorthand property with default value).
     * CoverInitializedName is only valid when the object is being used as a destructuring assignment target.
     * Returns the offending token if found, null otherwise.
     */
    private void validateNoCoverInitializedName(Expression expr) {
        if (expr instanceof ObjectExpression objExpr) {
            for (Node node : objExpr.properties()) {
                if (node instanceof Property prop) {
                    // Check for shorthand property with AssignmentExpression value
                    if (prop.shorthand() && prop.value() instanceof AssignmentExpression) {
                        throw new ExpectedTokenException("Shorthand property with default value is only valid in destructuring patterns", null);
                    }
                    // Recursively check nested objects
                    if (prop.value() instanceof Expression nestedExpr) {
                        validateNoCoverInitializedName(nestedExpr);
                    }
                }
            }
        } else if (expr instanceof ArrayExpression arrExpr) {
            for (Expression elem : arrExpr.elements()) {
                if (elem != null) {
                    validateNoCoverInitializedName(elem);
                }
            }
        }
    }

    /**
     * Get the name of a property key (for __proto__ duplicate checking).
     * Returns the string value for Identifier and Literal string keys.
     */
    private String getPropertyKeyName(Node key) {
        if (key instanceof Identifier id) {
            return id.name();
        }
        if (key instanceof Literal lit && lit.value() instanceof String s) {
            return s;
        }
        return null;
    }

    /**
     * Check if an expression directly contains a private identifier access.
     * Used for validating delete operations on private fields.
     */
    private static boolean containsPrivateIdentifier(Expression expr) {
        // Direct MemberExpression with private property: x.#field
        if (expr instanceof MemberExpression me) {
            return me.property() instanceof PrivateIdentifier;
        }
        // ChainExpression wrapping a MemberExpression: x?.#field
        if (expr instanceof ChainExpression ce) {
            return containsPrivateIdentifier(ce.expression());
        }
        return false;
    }

    /**
     * Validate the left-hand side of an assignment expression.
     * For simple assignment (=), allows destructuring patterns (array/object literals).
     * For compound assignments (+=, etc.), only simple assignment targets are allowed.
     * Note: ChainExpression (optional chaining) is NOT a valid assignment target.
     */
    private void validateAssignmentLHS(Expression left, Token operatorToken, boolean isSimpleAssignment) {
        // For compound assignments, only simple targets are allowed
        if (!isSimpleAssignment) {
            validateSimpleAssignmentTarget(left, operatorToken);
            return;
        }

        // ChainExpression (optional chaining) is NOT a valid assignment target
        if (left instanceof ChainExpression) {
            throw new ExpectedTokenException("Invalid left-hand side in assignment: optional chain", operatorToken);
        }

        if (left instanceof Identifier id) {
            validateAssignmentTarget(id.name(), operatorToken);
            return; // Valid
        }

        if (left instanceof MemberExpression) {
            return; // Valid
        }

        if (left instanceof ArrayExpression arrayExpr) {
            // Check if THIS array expression was parenthesized before being used as LHS
            // `([a]) = x` is invalid (= outside parens), but `([a] = x)` is valid (= inside parens)
            // We check if the parenthesized position is AT THE START of the LHS.
            // If the parenthesized position is inside (e.g., a default value), don't reject.
            if (parenthesizedNonSimpleTarget >= 0 && parenthesizedNonSimpleTarget <= arrayExpr.start()) {
                throw new ExpectedTokenException("Invalid left-hand side in assignment: parenthesized array literal", operatorToken);
            }
            // Non-parenthesized arrays will be converted to patterns - valid for destructuring
            return;
        }

        if (left instanceof ObjectExpression objExpr) {
            // Check if THIS object expression was parenthesized before being used as LHS
            // `({a}) = x` is invalid (= outside parens), but `({a} = x)` is valid (= inside parens)
            // We check if the parenthesized position is AT THE START of the LHS (within 1 char for the `{`).
            // If the parenthesized position is inside (e.g., a default value), don't reject.
            if (parenthesizedNonSimpleTarget >= 0 && parenthesizedNonSimpleTarget <= objExpr.start()) {
                throw new ExpectedTokenException("Invalid left-hand side in assignment: parenthesized object literal", operatorToken);
            }
            // Non-parenthesized objects will be converted to patterns - valid for destructuring
            return;
        }

        // Everything else is invalid
        String exprType = getAssignmentTargetErrorDescription(left);
        throw new ExpectedTokenException("Invalid left-hand side in assignment: " + exprType, operatorToken);
    }

    /**
     * Validate the left-hand side expression of a for-in or for-of loop.
     * Valid targets are: Identifier, MemberExpression, ArrayExpression, ObjectExpression
     * Invalid targets include: CallExpression, literals, etc.
     * In strict mode, CallExpression is always invalid.
     * In sloppy mode, CallExpression throws at runtime (but some browsers accept it at parse time - we reject it).
     */
    private void validateForInOfLHS(Expression expr, Token token, String loopType) {
        // Valid simple targets
        if (expr instanceof Identifier id) {
            validateAssignmentTarget(id.name(), token);
            return;
        }

        if (expr instanceof MemberExpression) {
            return;
        }

        // Destructuring patterns are valid
        if (expr instanceof ArrayExpression || expr instanceof ObjectExpression) {
            return;
        }

        // ChainExpression is not valid
        if (expr instanceof ChainExpression) {
            throw new ParseException("SyntaxError", token, null, loopType + " statement",
                "Invalid left-hand side in " + loopType + " loop: optional chain");
        }

        // CallExpression is invalid (strict mode always, sloppy mode at runtime, but we reject at parse time)
        if (expr instanceof CallExpression) {
            throw new ParseException("SyntaxError", token, null, loopType + " statement",
                "Invalid left-hand side in " + loopType + " loop: call expression");
        }

        // Everything else is invalid
        String exprType = getAssignmentTargetErrorDescription(expr);
        throw new ParseException("SyntaxError", token, null, loopType + " statement",
            "Invalid left-hand side in " + loopType + " loop: " + exprType);
    }

    /**
     * Check for duplicate parameter names in a function parameter list.
     * In strict mode, duplicate parameters are not allowed.
     * In sloppy mode, duplicates are also forbidden for non-simple parameter lists
     * (those with default values, rest parameters, or destructuring patterns).
     */
    private void validateNoDuplicateParameters(List<Pattern> params, Token functionToken) {
        validateNoDuplicateParameters(params, functionToken, false);
    }

    /**
     * Validate that there are no duplicate parameter names.
     * @param forceUnique If true, always check for duplicates (for methods, arrow functions).
     */
    private void validateNoDuplicateParameters(List<Pattern> params, Token functionToken, boolean forceUnique) {
        // In strict mode or when forceUnique, always check for duplicates
        // In sloppy mode (without forceUnique), only check if parameter list is non-simple
        if (!strictMode && !forceUnique && isSimpleParameterList(params)) {
            return; // Simple parameters in sloppy mode allow duplicates
        }

        java.util.Set<String> paramNames = new java.util.HashSet<>();
        for (Pattern param : params) {
            // If we reach here (past the early return), we should always check for duplicates
            // Pass actual strictMode for eval/arguments check
            collectParameterNames(param, paramNames, functionToken, strictMode, true);
        }
    }

    /**
     * Check if a parameter list is "simple" (no default values, rest params, or destructuring).
     * Non-simple parameter lists have stricter duplicate checking rules.
     */
    private boolean isSimpleParameterList(List<Pattern> params) {
        for (Pattern param : params) {
            if (!(param instanceof Identifier)) {
                // AssignmentPattern (default value), RestElement, ArrayPattern, ObjectPattern are non-simple
                return false;
            }
        }
        return true;
    }

    /**
     * Check if a function body contains a "use strict" directive in its prologue.
     * This is used to validate that functions with non-simple parameters cannot have "use strict".
     */
    private boolean bodyContainsUseStrict(BlockStatement body) {
        for (Statement stmt : body.body()) {
            if (stmt instanceof ExpressionStatement exprStmt) {
                if ("use strict".equals(exprStmt.directive())) {
                    return true;
                }
                // Continue if this is another directive (could be multiple)
                if (exprStmt.directive() != null) {
                    continue;
                }
            }
            // Non-directive statement - stop checking
            break;
        }
        return false;
    }

    /**
     * Validate that a function with non-simple parameters cannot have "use strict" in its body.
     * Per ECMAScript spec, it's a syntax error to have "use strict" in a function with
     * non-simple parameters (destructuring, default values, or rest parameters).
     */
    private void validateStrictBodyWithSimpleParams(List<Pattern> params, BlockStatement body, Token functionToken) {
        if (!isSimpleParameterList(params) && bodyContainsUseStrict(body)) {
            throw new ExpectedTokenException("Illegal 'use strict' directive in function with non-simple parameter list", functionToken);
        }
    }

    /**
     * Collect parameter names for duplicate checking.
     * @param inStrictMode True if in strict mode (affects eval/arguments restriction)
     * @param checkDuplicates True if duplicates should be rejected (methods, arrow functions, non-simple params)
     */
    private void collectParameterNames(Pattern pattern, java.util.Set<String> names, Token functionToken, boolean inStrictMode, boolean checkDuplicates) {
        if (pattern instanceof Identifier id) {
            String name = id.name();
            // In strict mode, eval and arguments cannot be parameter names
            if (inStrictMode && (name.equals("eval") || name.equals("arguments"))) {
                throw new ExpectedTokenException("Binding '" + name + "' in strict mode", functionToken);
            }
            if (checkDuplicates && names.contains(name)) {
                String reason = inStrictMode ? "in strict mode" : "in this context";
                throw new ExpectedTokenException("Duplicate parameter name '" + name + "' not allowed " + reason, functionToken);
            }
            names.add(name);
        } else if (pattern instanceof AssignmentPattern ap) {
            collectParameterNames(ap.left(), names, functionToken, inStrictMode, checkDuplicates);
        } else if (pattern instanceof ArrayPattern ap) {
            for (Pattern element : ap.elements()) {
                if (element != null) {
                    collectParameterNames(element, names, functionToken, inStrictMode, checkDuplicates);
                }
            }
        } else if (pattern instanceof ObjectPattern op) {
            for (Node node : op.properties()) {
                if (node instanceof Property prop && prop.value() instanceof Pattern p) {
                    collectParameterNames(p, names, functionToken, inStrictMode, checkDuplicates);
                } else if (node instanceof RestElement re) {
                    collectParameterNames(re.argument(), names, functionToken, inStrictMode, checkDuplicates);
                }
            }
        } else if (pattern instanceof RestElement re) {
            collectParameterNames(re.argument(), names, functionToken, inStrictMode, checkDuplicates);
        }
    }

    // ASI-aware semicolon consumption
    // According to ECMAScript spec, semicolons can be automatically inserted when:
    // 1. The next token is }
    // 2. The next token is EOF
    // 3. There's a line terminator between the previous token and the current token
    // 4. The next token would cause a grammatical error (e.g., seeing 'import' after var declaration)
    private void consumeSemicolon(String message) {
        if (check(TokenType.SEMICOLON)) {
            advance();
            return;
        }

        // ASI: Allow missing semicolon if next token is } or EOF
        if (check(TokenType.RBRACE) || isAtEnd()) {
            return;
        }

        // ASI: Allow missing semicolon if there's a line break before the next token
        Token prev = previous();
        Token next = peek();
        if (prev.line() < next.line()) {
            return;
        }

        // ASI: Allow missing semicolon if the next token would start a new statement
        // that cannot be part of the current statement (restricted production)
        TokenType nextType = peek().type();
        if (nextType == TokenType.IMPORT || nextType == TokenType.EXPORT ||
            nextType == TokenType.FUNCTION || nextType == TokenType.CLASS ||
            nextType == TokenType.CONST || nextType == TokenType.LET || nextType == TokenType.VAR) {
            return;
        }

        throw new ExpectedTokenException(message, peek());
    }

    public static Program parse(String source) {
        return new Parser(source).parse();
    }

    public static Program parse(String source, boolean forceModuleMode) {
        return new Parser(source, forceModuleMode).parse();
    }

    public static Program parse(String source, boolean forceModuleMode, boolean forceStrictMode) {
        return new Parser(source, forceModuleMode, forceStrictMode).parse();
    }
}
