package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.Test;

/**
 * Tiny end-to-end benchmark: parse + generate + interpret a few small JS
 * programs, report wall-clock timing. Not assertion-driven — meant to print
 * a feel for current interpreter throughput.
 *
 * Run: mvn -pl harmonica-interpreter test -Dtest=FibBenchmark
 */
class FibBenchmark {

    private static Object run(String source) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        return Interpreter.interpret(exe, new Object[0], 64);
    }

    /** Run {@code source} {@code iters} times, return best time in ms. */
    private static double timeBest(String label, String source, int iters) {
        // Warm up once for JIT.
        run(source);
        double best = Double.POSITIVE_INFINITY;
        long total = 0;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            run(source);
            long elapsed = System.nanoTime() - t0;
            total += elapsed;
            if (elapsed < best * 1_000_000) best = elapsed / 1_000_000.0;
        }
        double avg = total / 1_000_000.0 / iters;
        System.out.printf("%-40s  best=%7.2f ms  avg=%7.2f ms  (%d runs)%n",
            label, best, avg, iters);
        return best;
    }

    @Test
    void runBenchmarks() {
        System.out.println();
        System.out.println("=== harmonica interpreter benchmarks ===");
        System.out.println("(parse + generate + interpret per run; first run discarded)");
        System.out.println();

        // Recursive fib — exponential algorithm, lots of call dispatch.
        timeBest("fib(20) recursive",
            "function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); } fib(20)", 5);
        timeBest("fib(25) recursive",
            "function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); } fib(25)", 5);
        timeBest("fib(28) recursive",
            "function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); } fib(28)", 3);

        // Iterative fib — tight loop.
        timeBest("fib(1000) iterative",
            "function fib(n) { let a = 0, b = 1; while (n-- > 0) { let t = a + b; a = b; b = t; } return a; }" +
            " fib(1000)", 10);

        // Sum 1..N via while loop.
        timeBest("sum 1..1_000_000 (while)",
            "let n = 0; let i = 1; while (i <= 1000000) { n = n + i; i = i + 1; } n", 5);

        // Array map/filter/reduce pipeline.
        timeBest("[1..1000].filter(even).map(sq).reduce(+)",
            "let xs = []; for (let i = 1; i <= 1000; i++) xs.push(i);" +
            " xs.filter(n => n % 2 === 0).map(n => n * n).reduce((a, b) => a + b, 0)", 10);

        // Class instantiation + method calls.
        timeBest("class Box; sum 1..10000 .v",
            "class Box { constructor(v) { this.v = v; } get() { return this.v; } }" +
            " let s = 0; for (let i = 1; i <= 10000; i++) s = s + new Box(i).get(); s", 5);

        // Closures (counter pattern).
        timeBest("100k closure ticks",
            "function mk() { let n = 0; return () => ++n; } let c = mk();" +
            " for (let i = 0; i < 100000; i++) c(); c() - 1", 5);

        // Object literal + property access.
        timeBest("object lit + property reads x100k",
            "let s = 0; for (let i = 0; i < 100000; i++) { let o = {a: i, b: i * 2}; s = s + o.a + o.b; } s", 5);

        // String concatenation.
        timeBest("string concat x10000",
            "let s = ''; for (let i = 0; i < 10000; i++) s = s + 'x'; s.length", 5);

        // String split/map/join roundtrip.
        timeBest("'a,b,c,...'.split.map.join x1000",
            "let s = '';" +
            " for (let i = 0; i < 1000; i++) s = s + (i > 0 ? ',' : '') + i;" +
            " s.split(',').map(x => x + '!').join('|').length", 5);

        // JSON round-trip.
        timeBest("JSON stringify+parse x1000",
            "let obj = {a: 1, b: [2, 3, 4], c: 'hi'};" +
            " for (let i = 0; i < 1000; i++) obj = JSON.parse(JSON.stringify(obj));" +
            " obj.a", 5);

        System.out.println();
    }
}
