package com.jimmyhmiller.harmonica.bytecode.scope;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.BlockStatement;
import com.jimmyhmiller.harmonica.ast.FunctionDeclaration;
import com.jimmyhmiller.harmonica.ast.IfStatement;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.ast.Statement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeAnalysisTest {

    private static ScopeAnalysis analyze(String src) {
        Program p = Parser.parse(src);
        return ScopeAnalysis.analyze(p, /* scriptStrict */ false);
    }

    @Test
    void programScopeIsRootAndIsVarScope() {
        ScopeAnalysis a = analyze("var x = 1;");
        ScopeRecord root = a.rootScope();
        assertNotNull(root);
        assertEquals(ScopeType.Program, root.type());
        assertTrue(root.type().isVarScope());
        assertTrue(root.variables().containsKey("x"));
        assertEquals(BindingKind.Var, root.variables().get("x").kind());
    }

    @Test
    void varHoistsThroughBlocks() {
        ScopeAnalysis a = analyze("{ { var x = 1; } }");
        // var x should appear on the Program scope (the var scope), not the blocks.
        assertTrue(a.rootScope().variables().containsKey("x"));
        assertEquals(BindingKind.Var, a.rootScope().variables().get("x").kind());
        // Inner blocks are visited but don't hold x.
        for (ScopeRecord child : a.rootScope().children()) {
            assertFalse(child.variables().containsKey("x"));
        }
    }

    @Test
    void letStaysInBlock() {
        ScopeAnalysis a = analyze("{ let x = 1; }");
        ScopeRecord root = a.rootScope();
        assertFalse(root.variables().containsKey("x"));
        ScopeRecord block = root.children().get(0);
        assertEquals(ScopeType.Block, block.type());
        assertTrue(block.variables().containsKey("x"));
        assertEquals(BindingKind.Let, block.variables().get("x").kind());
    }

    @Test
    void functionBodyIsTheFunctionScope_notANestedBlock() {
        ScopeAnalysis a = analyze("function f(a, b) { var x; let y; }");
        ScopeRecord root = a.rootScope();
        assertTrue(root.variables().containsKey("f"));
        ScopeRecord fnScope = root.children().get(0);
        assertEquals(ScopeType.Function, fnScope.type());
        // Params + var + let all live in the function scope itself (the body
        // BlockStatement is folded into the function scope).
        assertEquals(BindingKind.Parameter, fnScope.variables().get("a").kind());
        assertEquals(BindingKind.Parameter, fnScope.variables().get("b").kind());
        assertEquals(BindingKind.Var, fnScope.variables().get("x").kind());
        assertEquals(BindingKind.Let, fnScope.variables().get("y").kind());
        // No nested Block child for the function body itself.
        for (ScopeRecord c : fnScope.children()) {
            assertFalse(c.type() == ScopeType.Block && c.astNode() == fnScope.astNode());
        }
    }

    @Test
    void annexB_simpleIfBlockHoistsFunction() {
        // Sloppy mode: function f in a block becomes ALSO a var-hoisted binding.
        ScopeAnalysis a = analyze("if (true) { function f() {} }");
        ScopeRecord root = a.rootScope();
        assertTrue(root.variables().containsKey("f"),
            "Annex B should hoist `function f` from the if-block to the program scope");
        ScopeVariable hoistedVar = root.variables().get("f");
        assertEquals(BindingKind.Var, hoistedVar.kind());
        assertTrue(hoistedVar.isAnnexBHoisted());

        // The original FD is still the block-scoped Function binding.
        IfStatement ifs = (IfStatement) ((Program) root.astNode()).body().get(0);
        BlockStatement block = (BlockStatement) ifs.consequent();
        FunctionDeclaration fd = (FunctionDeclaration) block.body().get(0);
        assertSame(hoistedVar, a.annexBBinding(fd));
    }

    @Test
    void annexB_skippedInStrictMode() {
        Program p = Parser.parse("'use strict'; if (true) { function f() {} }");
        ScopeAnalysis a = ScopeAnalysis.analyze(p, /* scriptStrict */ false);
        // The directive promotes the program to strict; Annex B must not apply.
        ScopeRecord root = a.rootScope();
        assertFalse(root.variables().containsKey("f"),
            "Strict mode disables Annex B function-in-block hoisting");
    }

    @Test
    void annexB_blockedByLetInVarScope() {
        ScopeAnalysis a = analyze("let f; if (true) { function f() {} }");
        ScopeRecord root = a.rootScope();
        // root.f is a Let, not a Var. The synthesis must NOT clobber it.
        ScopeVariable existing = root.variables().get("f");
        assertEquals(BindingKind.Let, existing.kind());
        assertFalse(existing.isAnnexBHoisted());
        // And no annexB binding registered for the FD.
        IfStatement ifs = (IfStatement) ((Program) root.astNode()).body().get(1);
        BlockStatement block = (BlockStatement) ifs.consequent();
        FunctionDeclaration fd = (FunctionDeclaration) block.body().get(0);
        assertNull(a.annexBBinding(fd));
    }

    @Test
    void annexB_compatibleVarMerges() {
        ScopeAnalysis a = analyze("var f; if (true) { function f() {} }");
        ScopeRecord root = a.rootScope();
        ScopeVariable existing = root.variables().get("f");
        assertEquals(BindingKind.Var, existing.kind());
        assertTrue(existing.isAnnexBHoisted(),
            "A pre-existing var with the same name should be marked annex-B so the "
            + "Generator emits the mid-execution update at the FD site");
    }

    @Test
    void evalPoisonsEnclosingFunctionScope() {
        ScopeAnalysis a = analyze("function f() { if (true) { eval('x'); } }");
        ScopeRecord root = a.rootScope();
        ScopeRecord fn = root.children().get(0);
        assertEquals(ScopeType.Function, fn.type());
        assertTrue(fn.evalPoisoned(),
            "Direct eval inside a function should poison the function scope");
        // The Program scope is NOT poisoned — eval can't introduce vars
        // across the function boundary.
        assertFalse(root.evalPoisoned());
    }

    @Test
    void noEvalNoPoison() {
        ScopeAnalysis a = analyze("function f() { var x; }");
        assertFalse(a.rootScope().evalPoisoned());
        assertFalse(a.rootScope().children().get(0).evalPoisoned());
    }

    @Test
    void switchIntroducesItsOwnBlockScope() {
        ScopeAnalysis a = analyze("switch (x) { case 1: let y = 1; }");
        ScopeRecord root = a.rootScope();
        ScopeRecord switchScope = root.children().get(0);
        assertEquals(ScopeType.Block, switchScope.type());
        assertTrue(switchScope.variables().containsKey("y"));
    }

    @Test
    void catchBindingLivesInCatchScope() {
        ScopeAnalysis a = analyze("try {} catch (e) { let q; }");
        ScopeRecord root = a.rootScope();
        // try-block + catch-clause are direct children
        ScopeRecord catchScope = null;
        for (ScopeRecord c : root.children()) {
            if (c.type() == ScopeType.Catch) catchScope = c;
        }
        assertNotNull(catchScope);
        assertEquals(BindingKind.Catch, catchScope.variables().get("e").kind());
        assertEquals(BindingKind.Let, catchScope.variables().get("q").kind());
    }

    @Test
    void forLetIntroducesForLoopScope() {
        ScopeAnalysis a = analyze("for (let i = 0; i < 10; i++) { let j = i; }");
        ScopeRecord root = a.rootScope();
        ScopeRecord forScope = root.children().get(0);
        assertEquals(ScopeType.ForLoop, forScope.type());
        assertEquals(BindingKind.Let, forScope.variables().get("i").kind());
        // Body block is nested inside the for scope.
        ScopeRecord bodyBlock = forScope.children().get(0);
        assertEquals(ScopeType.Block, bodyBlock.type());
        assertTrue(bodyBlock.variables().containsKey("j"));
    }

    @Test
    void destructuringPatternBindsLeaves() {
        ScopeAnalysis a = analyze("let { a, b: { c } = {} } = obj;");
        ScopeRecord root = a.rootScope();
        assertTrue(root.variables().containsKey("a"));
        assertTrue(root.variables().containsKey("c"));
        // 'b' is a property key, not a binding — should NOT be declared.
        assertFalse(root.variables().containsKey("b"));
    }
}
