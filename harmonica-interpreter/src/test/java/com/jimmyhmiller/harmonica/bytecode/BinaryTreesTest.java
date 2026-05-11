package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.Test;

class BinaryTreesTest {

    private static Object run(String source) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        return Interpreter.interpret(exe, new Object[0], 64);
    }

    @Test void stringConcatNeg() {
        try {
            Object r = run("var ret = -4; var expected = -4; var s = 'expected ' + expected + ' but got ' + ret; s");
            System.out.println("concat: '" + r + "'");
        } catch (AbruptCompletion ac) {
            System.out.println("AC: " + ac.value());
        }
    }

    @Test void ifThrowStringConcat() {
        try {
            run("var ret = 5; var expected = -4;\n" +
                "if (ret != expected) throw 'expected ' + expected + ' got ' + ret;\n");
        } catch (AbruptCompletion ac) {
            System.out.println("ifThrow: class=" + (ac.value() == null ? "null" : ac.value().getClass().getSimpleName()) + " value='" + ac.value() + "'");
        }
    }

    @Test void multiVarsConcat() {
        // closer to the binary-tree case: multiple `var` declarations + if + throw concat
        try {
            run(
                "var ret = 0;\n" +
                "for (var n = 4; n <= 5; n++) {\n" +
                "  ret += n;\n" +
                "}\n" +
                "var expected = -4;\n" +
                "if (ret != expected) throw 'expected ' + expected + ' got ' + ret;\n");
        } catch (AbruptCompletion ac) {
            System.out.println("multi: class=" + (ac.value() == null ? "null" : ac.value().getClass().getSimpleName()) + " value='" + ac.value() + "'");
        }
    }

    @Test void throwStringConcat() {
        try {
            run("var ret = 5; var expected = -4; if (ret != expected) throw 'expected ' + expected + ' got ' + ret;");
        } catch (AbruptCompletion ac) {
            System.out.println("throw: class=" + (ac.value() == null ? "null" : ac.value().getClass().getSimpleName()) + " value='" + ac.value() + "'");
        }
    }

    @Test void miniBinaryTrees() {
        // Repro of access-binary-trees, tiny version
        try {
            Object r = run(
                "function TreeNode(left,right,item){\n" +
                "  this.left = left; this.right = right; this.item = item;\n" +
                "}\n" +
                "TreeNode.prototype.itemCheck = function(){\n" +
                "  if (this.left==null) return this.item;\n" +
                "  else return this.item + this.left.itemCheck() - this.right.itemCheck();\n" +
                "};\n" +
                "function bottomUpTree(item, depth){\n" +
                "  if (depth>0) {\n" +
                "    return new TreeNode(bottomUpTree(2*item-1, depth-1), bottomUpTree(2*item, depth-1), item);\n" +
                "  } else {\n" +
                "    return new TreeNode(null, null, item);\n" +
                "  }\n" +
                "}\n" +
                "var t = bottomUpTree(0, 3);\n" +
                "'check=' + t.itemCheck()");
            System.out.println("mini: " + r);
        } catch (AbruptCompletion ac) {
            System.out.println("AC class=" + (ac.value() == null ? "null" : ac.value().getClass().getSimpleName()) + " value=" + ac.value());
        }
    }

    @Test void debugFile() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("/tmp/btree_dbg.js");
        if (!java.nio.file.Files.exists(p)) return;
        String body = java.nio.file.Files.readString(p);
        try {
            run(body);
        } catch (AbruptCompletion ac) {
            Object v = ac.value();
            System.out.println("AC class=" + (v == null ? "null" : v.getClass().getSimpleName()) + " value='" + v + "'");
            if (v instanceof JSObject jo) {
                System.out.println("  name=" + jo.get("name") + " message=" + jo.get("message"));
            }
        }
    }

    @Test void runProbe() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("/tmp/3d_probe.js");
        if (!java.nio.file.Files.exists(p)) return;
        String body = java.nio.file.Files.readString(p);
        try {
            run(body);
        } catch (AbruptCompletion ac) {
            Object v = ac.value();
            System.out.println("PROBE AC: " + (v instanceof JSObject jo ? "name=" + jo.get("name") + " message=" + jo.get("message") : v));
        }
    }

    @Test void dumpFailingBytecode() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("/tmp/btree_dbg.js");
        if (!java.nio.file.Files.exists(p)) return;
        String body = java.nio.file.Files.readString(p);
        Program ast = Parser.parse(body);
        Executable exe = Generator.generate(ast);
        Op[] ops = exe.ops();
        for (int i = 0; i < ops.length; i++) {
            System.out.printf("%3d: %s%n", i, ops[i]);
        }
    }

    @Test void runFullBenchmark() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("benchmarks/sunspider/access-binary-trees.js");
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("../benchmarks/sunspider/access-binary-trees.js");
        String body = java.nio.file.Files.readString(p);
        try {
            run(body);
            System.out.println("full: PASS");
        } catch (AbruptCompletion ac) {
            System.out.println("AC class=" + (ac.value() == null ? "null" : ac.value().getClass().getSimpleName()) + " value=" + ac.value());
        }
    }
}
