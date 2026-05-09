package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives each SunSpider 1.0.2 benchmark we ship at {@code benchmarks/sunspider/}
 * and fails the test if the benchmark throws. Useful for catching benchmark-only
 * regressions that test262 doesn't surface (Date / RegExp / String operations
 * tested under realistic call patterns).
 */
class SunspiderTriageTest {

    private static String load(String rel) throws Exception {
        Path p = Paths.get("benchmarks/sunspider/" + rel);
        if (!Files.exists(p)) p = Paths.get("../benchmarks/sunspider/" + rel);
        return Files.readString(p);
    }

    private static void runBench(String name) throws Exception {
        try {
            Program ast = Parser.parse(load(name));
            Executable exe = Generator.generate(ast);
            Interpreter.interpret(exe, new Object[0], 64);
        } catch (AbruptCompletion ac) {
            Object v = ac.value();
            String msg = v instanceof JSObject jo
                ? "name=" + jo.get("name") + " message=" + jo.get("message")
                : String.valueOf(v);
            fail("benchmark " + name + " threw: " + msg);
        }
    }

    @Test void raytrace3d() throws Exception { runBench("3d-raytrace.js"); }
    @Test void dateFormatTofte() throws Exception { runBench("date-format-tofte.js"); }
    @Test void dateFormatXparb() throws Exception { runBench("date-format-xparb.js"); }
    @Test void stringTagcloud() throws Exception { runBench("string-tagcloud.js"); }
    @Test void cryptoAes() throws Exception { runBench("crypto-aes.js"); }
    @Test void cryptoSha1() throws Exception { runBench("crypto-sha1.js"); }
    @Test void mathCordic() throws Exception { runBench("math-cordic.js"); }
    @Test void regexpDna() throws Exception { runBench("regexp-dna.js"); }
    @Test void stringUnpackCode() throws Exception { runBench("string-unpack-code.js"); }
    @Test void accessBinaryTrees() throws Exception { runBench("access-binary-trees.js"); }
}
