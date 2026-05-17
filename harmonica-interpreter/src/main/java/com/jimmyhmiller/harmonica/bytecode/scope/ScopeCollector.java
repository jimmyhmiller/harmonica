package com.jimmyhmiller.harmonica.bytecode.scope;

import com.jimmyhmiller.harmonica.ast.ArrayPattern;
import com.jimmyhmiller.harmonica.ast.ArrowFunctionExpression;
import com.jimmyhmiller.harmonica.ast.AssignmentExpression;
import com.jimmyhmiller.harmonica.ast.AssignmentPattern;
import com.jimmyhmiller.harmonica.ast.BinaryExpression;
import com.jimmyhmiller.harmonica.ast.BlockStatement;
import com.jimmyhmiller.harmonica.ast.CallExpression;
import com.jimmyhmiller.harmonica.ast.CatchClause;
import com.jimmyhmiller.harmonica.ast.ChainExpression;
import com.jimmyhmiller.harmonica.ast.ClassAccessorProperty;
import com.jimmyhmiller.harmonica.ast.ClassDeclaration;
import com.jimmyhmiller.harmonica.ast.ClassExpression;
import com.jimmyhmiller.harmonica.ast.ConditionalExpression;
import com.jimmyhmiller.harmonica.ast.DoWhileStatement;
import com.jimmyhmiller.harmonica.ast.ExportAllDeclaration;
import com.jimmyhmiller.harmonica.ast.ExportDefaultDeclaration;
import com.jimmyhmiller.harmonica.ast.ExportNamedDeclaration;
import com.jimmyhmiller.harmonica.ast.Expression;
import com.jimmyhmiller.harmonica.ast.ExpressionStatement;
import com.jimmyhmiller.harmonica.ast.ForInStatement;
import com.jimmyhmiller.harmonica.ast.ForOfStatement;
import com.jimmyhmiller.harmonica.ast.ForStatement;
import com.jimmyhmiller.harmonica.ast.FunctionDeclaration;
import com.jimmyhmiller.harmonica.ast.FunctionExpression;
import com.jimmyhmiller.harmonica.ast.Identifier;
import com.jimmyhmiller.harmonica.ast.IfStatement;
import com.jimmyhmiller.harmonica.ast.ImportDeclaration;
import com.jimmyhmiller.harmonica.ast.ImportDefaultSpecifier;
import com.jimmyhmiller.harmonica.ast.ImportNamespaceSpecifier;
import com.jimmyhmiller.harmonica.ast.ImportSpecifier;
import com.jimmyhmiller.harmonica.ast.LabeledStatement;
import com.jimmyhmiller.harmonica.ast.Literal;
import com.jimmyhmiller.harmonica.ast.LogicalExpression;
import com.jimmyhmiller.harmonica.ast.MemberExpression;
import com.jimmyhmiller.harmonica.ast.MethodDefinition;
import com.jimmyhmiller.harmonica.ast.NewExpression;
import com.jimmyhmiller.harmonica.ast.Node;
import com.jimmyhmiller.harmonica.ast.ObjectExpression;
import com.jimmyhmiller.harmonica.ast.ObjectPattern;
import com.jimmyhmiller.harmonica.ast.Pattern;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.ast.Property;
import com.jimmyhmiller.harmonica.ast.PropertyDefinition;
import com.jimmyhmiller.harmonica.ast.RestElement;
import com.jimmyhmiller.harmonica.ast.ReturnStatement;
import com.jimmyhmiller.harmonica.ast.SequenceExpression;
import com.jimmyhmiller.harmonica.ast.SpreadElement;
import com.jimmyhmiller.harmonica.ast.Statement;
import com.jimmyhmiller.harmonica.ast.StaticBlock;
import com.jimmyhmiller.harmonica.ast.SwitchCase;
import com.jimmyhmiller.harmonica.ast.SwitchStatement;
import com.jimmyhmiller.harmonica.ast.TaggedTemplateExpression;
import com.jimmyhmiller.harmonica.ast.TemplateLiteral;
import com.jimmyhmiller.harmonica.ast.ThrowStatement;
import com.jimmyhmiller.harmonica.ast.TryStatement;
import com.jimmyhmiller.harmonica.ast.UnaryExpression;
import com.jimmyhmiller.harmonica.ast.UpdateExpression;
import com.jimmyhmiller.harmonica.ast.VariableDeclaration;
import com.jimmyhmiller.harmonica.ast.VariableDeclarator;
import com.jimmyhmiller.harmonica.ast.WhileStatement;
import com.jimmyhmiller.harmonica.ast.WithStatement;
import com.jimmyhmiller.harmonica.ast.YieldExpression;
import com.jimmyhmiller.harmonica.ast.AwaitExpression;
import com.jimmyhmiller.harmonica.ast.ArrayExpression;
import com.jimmyhmiller.harmonica.ast.ImportExpression;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Phase 1: build the {@link ScopeRecord} tree by walking the AST once.
 * Registers every declaration into its enclosing scope and flags
 * {@link ScopeRecord#hasDirectEval()} when a {@code eval(...)} call is
 * encountered.
 *
 * <p>This pass deliberately does <em>not</em> collect every identifier
 * reference yet — only declarations and direct-eval calls. Reference
 * resolution (and the indexed-locals optimization that depends on it)
 * can be layered on in a follow-up without changing the tree shape.
 *
 * <p>Scope folding conventions (mirrors LibJS shape):
 * <ul>
 *   <li>A function body {@link BlockStatement} does <em>not</em> introduce
 *       its own {@link ScopeType#Block} — it shares the function's
 *       {@link ScopeType#Function} scope. Params and top-level
 *       {@code var}/{@code function} all live there together.</li>
 *   <li>A {@link CatchClause} body block is folded into the
 *       {@link ScopeType#Catch} scope (the catch param binding lives there).</li>
 *   <li>A {@code for (...)} loop's init bindings get their own
 *       {@link ScopeType#ForLoop} scope. If the body is a {@link BlockStatement}
 *       it nests a {@link ScopeType#Block} inside the ForLoop scope.</li>
 * </ul>
 */
final class ScopeCollector {
    private final boolean scriptStrict;

    private ScopeRecord rootScope;
    private ScopeRecord currentScope;
    private final IdentityHashMap<Node, ScopeRecord> scopeForNode = new IdentityHashMap<>();

    ScopeCollector(boolean scriptStrict) {
        this.scriptStrict = scriptStrict;
    }

    ScopeRecord rootScope() { return rootScope; }
    IdentityHashMap<Node, ScopeRecord> scopeForNode() { return scopeForNode; }

    // ----- Entry point -----

    void collect(Program program) {
        boolean strict = scriptStrict
            || "module".equals(program.sourceType())
            || hasUseStrictDirective(program.body());
        rootScope = pushScope(ScopeType.Program, program, strict);
        for (Statement s : program.body()) visitStatement(s);
        popScope();
    }

    // ----- Scope stack management -----

    private ScopeRecord pushScope(ScopeType type, Node node, boolean strict) {
        ScopeRecord s = new ScopeRecord(type, currentScope, node, strict);
        scopeForNode.put(node, s);
        currentScope = s;
        return s;
    }

    private void popScope() {
        currentScope = currentScope.parent();
    }

    // ----- Statements -----

    private void visitStatement(Statement s) {
        if (s == null) return;
        if (s instanceof BlockStatement b) {
            visitBlock(b, /* isFreshBlock */ true);
        } else if (s instanceof VariableDeclaration vd) {
            visitVariableDeclaration(vd);
        } else if (s instanceof FunctionDeclaration fd) {
            visitFunctionDeclaration(fd);
        } else if (s instanceof ClassDeclaration cd) {
            visitClassDeclaration(cd);
        } else if (s instanceof IfStatement is) {
            visitExpression(is.test());
            visitStatement(is.consequent());
            if (is.alternate() != null) visitStatement(is.alternate());
        } else if (s instanceof WhileStatement ws) {
            visitExpression(ws.test());
            visitStatement(ws.body());
        } else if (s instanceof DoWhileStatement dws) {
            visitStatement(dws.body());
            visitExpression(dws.test());
        } else if (s instanceof ForStatement fs) {
            visitForStatement(fs);
        } else if (s instanceof ForInStatement fi) {
            visitForInOf(fi.left(), fi.right(), fi.body(), fi);
        } else if (s instanceof ForOfStatement fo) {
            visitForInOf(fo.left(), fo.right(), fo.body(), fo);
        } else if (s instanceof TryStatement ts) {
            visitTry(ts);
        } else if (s instanceof WithStatement ws) {
            visitWith(ws);
        } else if (s instanceof SwitchStatement ss) {
            visitSwitch(ss);
        } else if (s instanceof LabeledStatement ls) {
            visitStatement(ls.body());
        } else if (s instanceof ReturnStatement rs) {
            if (rs.argument() != null) visitExpression(rs.argument());
        } else if (s instanceof ThrowStatement ts) {
            visitExpression(ts.argument());
        } else if (s instanceof ExpressionStatement es) {
            visitExpression(es.expression());
        } else if (s instanceof ImportDeclaration id) {
            visitImportDeclaration(id);
        } else if (s instanceof ExportNamedDeclaration en) {
            if (en.declaration() != null) visitStatement(en.declaration());
        } else if (s instanceof ExportDefaultDeclaration ed) {
            visitExportDefault(ed);
        } else if (s instanceof ExportAllDeclaration) {
            // No bindings introduced locally.
        }
        // BreakStatement, ContinueStatement, DebuggerStatement, EmptyStatement: nothing to do.
    }

    private void visitBlock(BlockStatement block, boolean isFreshBlock) {
        boolean pushed = false;
        if (isFreshBlock) {
            pushScope(ScopeType.Block, block, currentScope.strict());
            pushed = true;
        }
        for (Statement s : block.body()) visitStatement(s);
        if (pushed) popScope();
    }

    private void visitVariableDeclaration(VariableDeclaration vd) {
        BindingKind kind = switch (vd.kind()) {
            case "var" -> BindingKind.Var;
            case "let" -> BindingKind.Let;
            case "const" -> BindingKind.Const;
            // Explicit Resource Management (Stage 4): `using` and `await using`
            // are block-scoped like let/const for binding-resolution purposes.
            // We don't model their disposal semantics here — that's the
            // runtime's job — but the binding kind matters for scope analysis.
            case "using" -> BindingKind.Let;
            case "await using" -> BindingKind.Let;
            default -> throw new IllegalStateException(
                "ScopeCollector: unknown VariableDeclaration.kind '" + vd.kind() + "'");
        };
        ScopeRecord target = (kind == BindingKind.Var)
            ? currentScope.enclosingVarScope()
            : currentScope;
        for (VariableDeclarator d : vd.declarations()) {
            collectBindingsInPattern(d.id(), kind, vd, target);
            if (d.init() != null) visitExpression(d.init());
        }
    }

    private void visitFunctionDeclaration(FunctionDeclaration fd) {
        if (fd.id() != null) {
            // FunctionDeclaration at top level of a function/script is var-hoisted.
            // Inside a block, it gets a lexical binding (Function kind) in the block,
            // and Annex-B may later synthesize a var binding in the enclosing var
            // scope (handled by ScopeAnalyzer).
            BindingKind kind = currentScope.type().isVarScope()
                ? BindingKind.Function
                : BindingKind.Function;
            ScopeRecord target = currentScope.type().isVarScope()
                ? currentScope
                : currentScope; // lexical in the block; Annex B added later
            target.addDeclaration(fd.id().name(), kind, fd);
        }
        visitFunctionBody(fd, fd.params(), fd.body());
    }

    private void visitFunctionBody(Node node, List<Pattern> params, BlockStatement body) {
        boolean bodyStrict = currentScope.strict() || hasUseStrictDirective(body);
        ScopeRecord fnScope = pushScope(ScopeType.Function, node, bodyStrict);
        // Also key the body BlockStatement to the same Function scope so
        // downstream consumers (Generator.generateFunction) that only hold
        // a reference to `body` — not the enclosing FunctionDeclaration /
        // FunctionExpression / ArrowFunctionExpression — can still find it.
        if (body != null) scopeForNode.put(body, fnScope);
        if (params != null) {
            for (Pattern p : params) collectBindingsInPattern(p, BindingKind.Parameter, node, fnScope);
            for (Pattern p : params) visitPatternDefaults(p);
        }
        if (body != null) {
            for (Statement s : body.body()) visitStatement(s);
        }
        popScope();
    }

    private void visitArrowBody(ArrowFunctionExpression arrow) {
        boolean bodyStrict = currentScope.strict()
            || (arrow.body() instanceof BlockStatement bs && hasUseStrictDirective(bs));
        ScopeRecord fnScope = pushScope(ScopeType.Function, arrow, bodyStrict);
        if (arrow.params() != null) {
            for (Pattern p : arrow.params())
                collectBindingsInPattern(p, BindingKind.Parameter, arrow, fnScope);
            for (Pattern p : arrow.params()) visitPatternDefaults(p);
        }
        Node body = arrow.body();
        if (body instanceof BlockStatement bs) {
            for (Statement s : bs.body()) visitStatement(s);
        } else if (body instanceof Expression e) {
            visitExpression(e);
        }
        popScope();
    }

    private void visitClassDeclaration(ClassDeclaration cd) {
        if (cd.id() != null) {
            currentScope.addDeclaration(cd.id().name(), BindingKind.Class, cd);
        }
        visitClassBodyShared(cd, cd.id(), cd.superClass(), cd.body());
    }

    private void visitClassExpression(ClassExpression ce) {
        // The class's own name is bound in an inner scope so the class body can
        // reference itself. We push a ClassDeclaration scope to hold it.
        boolean strict = true; // class bodies are always strict
        pushScope(ScopeType.ClassDeclaration, ce, strict);
        if (ce.id() != null) {
            currentScope.addDeclaration(ce.id().name(), BindingKind.Class, ce);
        }
        visitClassBodyShared(ce, ce.id(), ce.superClass(), ce.body());
        popScope();
    }

    private void visitClassBodyShared(Node classNode, Identifier id, Expression superClass,
                                       com.jimmyhmiller.harmonica.ast.ClassBody body) {
        if (superClass != null) visitExpression(superClass);
        if (body == null) return;
        for (Node member : body.body()) {
            if (member instanceof MethodDefinition md) {
                if (md.computed() && md.key() instanceof Expression ke) visitExpression(ke);
                if (md.value() != null) {
                    visitFunctionBody(md.value(), md.value().params(), md.value().body());
                }
            } else if (member instanceof PropertyDefinition pd) {
                if (pd.computed() && pd.key() instanceof Expression ke) visitExpression(ke);
                if (pd.value() != null) {
                    // Field initializers run in a fresh scope per instance.
                    pushScope(ScopeType.ClassField, pd, true);
                    visitExpression(pd.value());
                    popScope();
                }
            } else if (member instanceof ClassAccessorProperty ap) {
                if (ap.computed() && ap.key() instanceof Expression ke) visitExpression(ke);
                if (ap.value() != null) {
                    pushScope(ScopeType.ClassField, ap, true);
                    visitExpression(ap.value());
                    popScope();
                }
            } else if (member instanceof StaticBlock sb) {
                pushScope(ScopeType.ClassStaticInit, sb, true);
                for (Statement s : sb.body()) visitStatement(s);
                popScope();
            }
        }
    }

    private void visitForStatement(ForStatement fs) {
        pushScope(ScopeType.ForLoop, fs, currentScope.strict());
        if (fs.init() instanceof VariableDeclaration vd) {
            visitVariableDeclaration(vd);
        } else if (fs.init() instanceof Expression e) {
            visitExpression(e);
        }
        if (fs.test() != null) visitExpression(fs.test());
        if (fs.update() != null) visitExpression(fs.update());
        visitForBody(fs.body());
        popScope();
    }

    private void visitForInOf(Node left, Expression right, Statement body, Node loopNode) {
        pushScope(ScopeType.ForLoop, loopNode, currentScope.strict());
        if (left instanceof VariableDeclaration vd) {
            visitVariableDeclaration(vd);
        } else if (left instanceof Expression e) {
            visitExpression(e);
        }
        visitExpression(right);
        visitForBody(body);
        popScope();
    }

    private void visitForBody(Statement body) {
        if (body instanceof BlockStatement b) {
            visitBlock(b, /* isFreshBlock */ true);
        } else {
            visitStatement(body);
        }
    }

    private void visitTry(TryStatement ts) {
        visitBlock(ts.block(), true);
        if (ts.handler() != null) {
            CatchClause cc = ts.handler();
            pushScope(ScopeType.Catch, cc, currentScope.strict());
            if (cc.param() != null) {
                collectBindingsInPattern(cc.param(), BindingKind.Catch, cc, currentScope);
                visitPatternDefaults(cc.param());
            }
            // Catch body shares the catch scope; do not push another Block.
            if (cc.body() != null) {
                for (Statement s : cc.body().body()) visitStatement(s);
            }
            popScope();
        }
        if (ts.finalizer() != null) visitBlock(ts.finalizer(), true);
    }

    private void visitWith(WithStatement ws) {
        visitExpression(ws.object());
        pushScope(ScopeType.With, ws, currentScope.strict());
        visitStatement(ws.body());
        popScope();
    }

    private void visitSwitch(SwitchStatement ss) {
        visitExpression(ss.discriminant());
        // A switch body introduces its own lexical scope (all cases share it).
        pushScope(ScopeType.Block, ss, currentScope.strict());
        for (SwitchCase c : ss.cases()) {
            if (c.test() != null) visitExpression(c.test());
            for (Statement s : c.consequent()) visitStatement(s);
        }
        popScope();
    }

    private void visitImportDeclaration(ImportDeclaration id) {
        for (Node spec : id.specifiers()) {
            if (spec instanceof ImportSpecifier is && is.local() != null) {
                currentScope.addDeclaration(is.local().name(), BindingKind.Import, is);
            } else if (spec instanceof ImportDefaultSpecifier ids && ids.local() != null) {
                currentScope.addDeclaration(ids.local().name(), BindingKind.Import, ids);
            } else if (spec instanceof ImportNamespaceSpecifier ins && ins.local() != null) {
                currentScope.addDeclaration(ins.local().name(), BindingKind.Import, ins);
            }
        }
    }

    private void visitExportDefault(ExportDefaultDeclaration ed) {
        Node decl = ed.declaration();
        if (decl instanceof FunctionDeclaration fd) {
            visitFunctionDeclaration(fd);
        } else if (decl instanceof ClassDeclaration cd) {
            visitClassDeclaration(cd);
        } else if (decl instanceof Expression e) {
            visitExpression(e);
        }
    }

    // ----- Expressions (lightweight: only descend into things that may
    // contain declarations or direct eval) -----

    private void visitExpression(Expression e) {
        if (e == null) return;
        if (e instanceof FunctionExpression fe) {
            // Optional named function expression: its name is bound in a
            // wrapper scope only visible inside the body.
            if (fe.id() != null) {
                pushScope(ScopeType.Function, fe, currentScope.strict());
                currentScope.addDeclaration(fe.id().name(), BindingKind.Function, fe);
                visitFunctionBody(fe, fe.params(), fe.body());
                popScope();
            } else {
                visitFunctionBody(fe, fe.params(), fe.body());
            }
        } else if (e instanceof ArrowFunctionExpression ae) {
            visitArrowBody(ae);
        } else if (e instanceof ClassExpression ce) {
            visitClassExpression(ce);
        } else if (e instanceof CallExpression ce) {
            // Direct eval: callee is a bare Identifier named "eval".
            if (ce.callee() instanceof Identifier idCallee && "eval".equals(idCallee.name())) {
                currentScope.setHasDirectEval(true);
            }
            visitExpression(ce.callee());
            for (Expression a : ce.arguments()) visitExpression(a);
        } else if (e instanceof NewExpression ne) {
            visitExpression(ne.callee());
            for (Expression a : ne.arguments()) visitExpression(a);
        } else if (e instanceof MemberExpression me) {
            visitExpression(me.object());
            if (me.computed() && me.property() instanceof Expression pe) visitExpression(pe);
        } else if (e instanceof BinaryExpression be) {
            visitExpression(be.left());
            visitExpression(be.right());
        } else if (e instanceof LogicalExpression le) {
            visitExpression(le.left());
            visitExpression(le.right());
        } else if (e instanceof UnaryExpression ue) {
            visitExpression(ue.argument());
        } else if (e instanceof UpdateExpression upe) {
            visitExpression(upe.argument());
        } else if (e instanceof AssignmentExpression ae) {
            if (ae.left() instanceof Expression le) {
                visitExpression(le);
            } else if (ae.left() instanceof Pattern lp) {
                // Destructuring assignment can carry default-value expressions
                // (e.g. {a = sideEffect()} = obj) that the analyzer must see.
                visitPatternDefaults(lp);
            }
            visitExpression(ae.right());
        } else if (e instanceof ConditionalExpression ce) {
            visitExpression(ce.test());
            visitExpression(ce.consequent());
            visitExpression(ce.alternate());
        } else if (e instanceof ArrayExpression ae) {
            for (Expression el : ae.elements()) if (el != null) visitExpression(el);
        } else if (e instanceof ObjectExpression oe) {
            for (Node p : oe.properties()) {
                if (p instanceof Property prop) {
                    if (prop.computed() && prop.key() instanceof Expression ke) visitExpression(ke);
                    if (prop.value() instanceof Expression ve) visitExpression(ve);
                } else if (p instanceof SpreadElement sp) {
                    visitExpression(sp.argument());
                }
            }
        } else if (e instanceof SequenceExpression se) {
            for (Expression x : se.expressions()) visitExpression(x);
        } else if (e instanceof TemplateLiteral tl) {
            for (Expression x : tl.expressions()) visitExpression(x);
        } else if (e instanceof TaggedTemplateExpression tte) {
            visitExpression(tte.tag());
            visitExpression(tte.quasi());
        } else if (e instanceof YieldExpression ye) {
            if (ye.argument() != null) visitExpression(ye.argument());
        } else if (e instanceof AwaitExpression awe) {
            visitExpression(awe.argument());
        } else if (e instanceof SpreadElement sp) {
            visitExpression(sp.argument());
        } else if (e instanceof ChainExpression ce) {
            visitExpression(ce.expression());
        } else if (e instanceof ImportExpression ie) {
            visitExpression(ie.source());
        }
        // Identifier, Literal, ThisExpression, Super, MetaProperty, PrivateIdentifier:
        // nothing to collect for now.
    }

    // ----- Pattern binding extraction -----

    /**
     * Walk a binding pattern and record each leaf {@link Identifier} as
     * a declaration of {@code kind} in {@code target} (which may differ
     * from {@link #currentScope} for {@code var}).
     */
    private void collectBindingsInPattern(Pattern p, BindingKind kind, Node declNode,
                                           ScopeRecord target) {
        if (p == null) return;
        if (p instanceof Identifier id) {
            target.addDeclaration(id.name(), kind, declNode);
        } else if (p instanceof ArrayPattern ap) {
            for (Pattern el : ap.elements()) collectBindingsInPattern(el, kind, declNode, target);
        } else if (p instanceof ObjectPattern op) {
            for (Node prop : op.properties()) {
                if (prop instanceof Property pr && pr.value() instanceof Pattern v) {
                    collectBindingsInPattern(v, kind, declNode, target);
                } else if (prop instanceof RestElement re) {
                    collectBindingsInPattern(re.argument(), kind, declNode, target);
                }
            }
        } else if (p instanceof RestElement re) {
            collectBindingsInPattern(re.argument(), kind, declNode, target);
        } else if (p instanceof AssignmentPattern asp) {
            collectBindingsInPattern(asp.left(), kind, declNode, target);
        }
        // MemberExpression patterns (destructuring into a property) bind nothing.
    }

    /** Walk default-value expressions inside binding patterns so any
     *  nested function/class declarations are still discovered. */
    private void visitPatternDefaults(Pattern p) {
        if (p == null) return;
        if (p instanceof AssignmentPattern asp) {
            visitPatternDefaults(asp.left());
            visitExpression(asp.right());
        } else if (p instanceof ArrayPattern ap) {
            for (Pattern el : ap.elements()) visitPatternDefaults(el);
        } else if (p instanceof ObjectPattern op) {
            for (Node prop : op.properties()) {
                if (prop instanceof Property pr) {
                    if (pr.computed() && pr.key() instanceof Expression ke) visitExpression(ke);
                    if (pr.value() instanceof Pattern v) visitPatternDefaults(v);
                } else if (prop instanceof RestElement re) {
                    visitPatternDefaults(re.argument());
                }
            }
        } else if (p instanceof RestElement re) {
            visitPatternDefaults(re.argument());
        }
    }

    // ----- Helpers -----

    private static boolean hasUseStrictDirective(BlockStatement body) {
        return body != null && hasUseStrictDirective(body.body());
    }

    private static boolean hasUseStrictDirective(List<Statement> stmts) {
        if (stmts == null) return false;
        for (Statement s : stmts) {
            if (s instanceof ExpressionStatement es
                && es.expression() instanceof Literal lit
                && lit.value() instanceof String str
                && "use strict".equals(str)) {
                return true;
            }
            // Directives must be at the very start; first non-string-literal ends the prologue.
            if (!(s instanceof ExpressionStatement es2
                  && es2.expression() instanceof Literal lit2
                  && lit2.value() instanceof String)) {
                return false;
            }
        }
        return false;
    }
}
